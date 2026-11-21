package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.replay.ReplaySoundCuePlan;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReplayVideoAudioMuxTest {

    @Test
    void trimmedVoiceIsSilentBeforeItsScheduledStartAndKeepsItsSampleOffset() {
        int rate = ReplayVoiceData.SAMPLE_RATE;
        short[] samples = new short[rate];
        Arrays.fill(samples, 0, rate / 2, (short) -12000);
        Arrays.fill(samples, rate / 2, rate, (short) 12000);
        var clip = new ReplayVoiceData.Clip(0, 0, -500, samples.length, ReplayVoiceData.encodeAdpcm(samples));
        var cue = new ReplaySoundCuePlan.VoiceCue(clip, 1.0, 1.0, 1.5, 0f);
        var schedule = new ReplaySoundCuePlan.Schedule(List.of(), List.of(cue));
        short[] pcm = ReplayVideoAudioRenderer.renderSchedule(schedule, keys -> null, 60);
        int outputRate = ReplayVideoAudioRenderer.SAMPLE_RATE;

        assertEquals(0, pcm[(outputRate - 1) * 2]);
        assertTrue(pcm[(outputRate + outputRate / 10) * 2] > 1000);
    }

    @Test
    void capturedSoundUsesExactResourceInsteadOfRandomEventVariant() {
        var file = net.minecraft.resources.ResourceLocation.parse("test:sounds/private_hint.ogg");
        var cue = new ReplaySoundCuePlan.SoundCue(.25, 0, List.of(), .5f, 1f, -1f, file);
        var schedule = new ReplaySoundCuePlan.Schedule(List.of(cue), List.of());
        int rate = ReplayVideoAudioRenderer.SAMPLE_RATE;
        float[] samples = new float[rate / 4];
        Arrays.fill(samples, 1f);
        short[] pcm = ReplayVideoAudioRenderer.renderSchedule(schedule, keys -> {
            assertEquals(List.of(file.toString()), keys);
            return new ReplayVideoAudioRenderer.DecodedSound(samples, 1, rate, 1f, 1f);
        }, 30);
        assertEquals(0, pcm[rate / 2 - 2]);
        assertEquals(Math.round(.5f * .85f * .60f * 32767), pcm[rate / 2]);
        assertEquals(0, pcm[rate]);
    }

    @Test
    void soundContinuesAcrossMixWindowsWithoutFadingOrDecodingTwice() {
        int rate = ReplayVideoAudioRenderer.SAMPLE_RATE;
        float[] constant = new float[rate / 2];
        Arrays.fill(constant, .5f);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var schedule = new ReplaySoundCuePlan.Schedule(List.of(
                new ReplaySoundCuePlan.SoundCue(.75, 0, List.of("continuous"), 1, 1, -1)), List.of());
        short[] pcm = ReplayVideoAudioRenderer.renderSchedule(schedule, keys -> {
            calls.incrementAndGet();
            return new ReplayVideoAudioRenderer.DecodedSound(constant, 1, rate, 1, 1);
        }, 42);
        assertEquals(1, calls.get());
        assertEquals(0, pcm[(rate * 3 / 4 - 1) * 2]);
        for (int frame = rate * 3 / 4; frame < rate * 5 / 4; frame++) {
            assertEquals(Math.round(.5f * .85f * .60f * 32767), pcm[frame * 2]);
            assertEquals(0, pcm[frame * 2 + 1]);
        }
        assertEquals(0, pcm[rate * 5 / 4 * 2]);
    }

    @Test
    void overlappingReplaySoundsStayBelowTheQuietOutputCeiling() {
        int rate = ReplayVideoAudioRenderer.SAMPLE_RATE;
        float[] constant = new float[rate];
        Arrays.fill(constant, 1f);
        var cue = new ReplaySoundCuePlan.SoundCue(.25, 0, List.of("loud"), 1, 1, -1);
        var schedule = new ReplaySoundCuePlan.Schedule(List.of(cue, cue, cue, cue), List.of());
        short[] pcm = ReplayVideoAudioRenderer.renderSchedule(schedule,
                keys -> new ReplayVideoAudioRenderer.DecodedSound(constant, 1, rate, 1, 1), 60);

        int peak = 0;
        for (short sample : pcm) peak = Math.max(peak, Math.abs((int) sample));
        assertTrue(peak > 17_000 && peak <= Math.round(.60f * Short.MAX_VALUE), "Replay peak: " + peak);
    }

    @Test
    void mp4AndAviMuxVideoAndAudioInExactFrameLockstep(@TempDir Path tempDir) throws Exception {
        Path ffmpeg = ReplayFfmpegManager.resolveAvailable(null, null);
        Assumptions.assumeTrue(ffmpeg != null, "FFmpeg is required to test external MP4 muxing");

        int fps = 30;
        int frames = 60; // 2.00s
        double t1 = 0.600;
        double t2 = 1.300;

        // Build schedule with synthetic SFX cues + proximity voice clip centered at t1.
        short[] voice24k = new short[ReplayVoiceData.SAMPLE_RATE / 2]; // 0.50s
        for (int i = 0; i < voice24k.length; i++) {
            voice24k[i] = (short) Math.round(12000.0 * Math.sin(2.0 * Math.PI * 310.0 * i / ReplayVoiceData.SAMPLE_RATE));
        }
        ReplayVoiceData.Clip clip = new ReplayVoiceData.Clip(0, 100, -150, voice24k.length, ReplayVoiceData.encodeAdpcm(voice24k));
        ReplaySoundCuePlan.Schedule schedule = new ReplaySoundCuePlan.Schedule(
                List.of(
                        new ReplaySoundCuePlan.SoundCue(t1, 0, List.of("gun"), 1.0f, 1.0f, -0.6f),
                        new ReplaySoundCuePlan.SoundCue(t2, 1, List.of("knife"), 1.0f, 1.0f, 0.6f)
                ),
                List.of(
                        new ReplaySoundCuePlan.VoiceCue(clip, t1 - 0.15, t1, t1 + 0.35, 0f)
                )
        );
        ReplayVideoAudioRenderer.SoundResolver syntheticResolver = candidates -> {
            float[] buf = new float[4800]; // 100ms
            double freq = candidates.contains("gun") ? 420.0 : 880.0;
            for (int i = 0; i < buf.length; i++) {
                buf[i] = (float) (0.7 * Math.exp(-i / 1800.0) * Math.sin(2.0 * Math.PI * freq * i / 48_000.0));
            }
            return new ReplayVideoAudioRenderer.DecodedSound(buf, 1, 48_000f, 1.0f, 1.0f);
        };
        short[] soundtrack = ReplayVideoAudioRenderer.renderSchedule(schedule, syntheticResolver, frames);
        assertEquals(frames * ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME * 2, soundtrack.length);

        int w = 320, h = 180;
        byte[] bgr = new byte[w * h * 3];
        Arrays.fill(bgr, (byte) 40);

        Path mp4 = tempDir.resolve("with-audio.mp4");
        Path avi = tempDir.resolve("with-audio.avi");
        try (ReplayVideoWriter mp4Writer = new ReplayMp4Writer(ffmpeg, mp4, w, h, fps, soundtrack);
             ReplayVideoWriter aviWriter = new ReplayAviWriter(avi, w, h, fps, true)) {
            for (int f = 0; f < frames; f++) {
                mp4Writer.add(bgr);
                aviWriter.add(bgr);
                aviWriter.addAudio(soundtrack,
                        f * ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME,
                        ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME);
            }
            mp4Writer.finish();
            aviWriter.finish();
        }

        // Verify MP4 audio track decodes cleanly via FFmpeg in exact stereo sync
        short[] mp4Pcm = decodeMp4Audio48kStereo(ffmpeg, mp4);
        assertTrue(mp4Pcm.length > 0, "Expected decoded AAC PCM samples from MP4");
        assertPeakSync(mp4Pcm, t1, t2);

        // Verify AVI container has 2 streams (vids + auds) and PCM chunks in lockstep
        byte[] aviBytes = Files.readAllBytes(avi);
        String headerAscii = new String(aviBytes, 0, Math.min(aviBytes.length, 512), StandardCharsets.US_ASCII);
        assertTrue(headerAscii.contains("vids"));
        assertTrue(headerAscii.contains("auds"));
        short[] aviPcm = extractAviPcm(aviBytes);
        assertEquals(soundtrack.length, aviPcm.length);
        assertPeakSync(aviPcm, t1, t2);
    }

    private static short[] decodeMp4Audio48kStereo(Path ffmpeg, Path mp4) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                ffmpeg.toAbsolutePath().toString(),
                "-hide_banner", "-loglevel", "error",
                "-i", mp4.toAbsolutePath().toString(),
                "-vn", "-f", "s16le", "-ac", "2", "-ar", "48000", "pipe:1"
        ).start();
        byte[] pcmBytes = process.getInputStream().readAllBytes();
        byte[] errBytes = process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Timed out decoding MP4 audio");
        assertEquals(0, process.exitValue(), "FFmpeg decode error: " + new String(errBytes, StandardCharsets.UTF_8));
        short[] out = new short[pcmBytes.length / 2];
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    private static void assertPeakSync(short[] interleavedStereo, double t1Sec, double t2Sec) {
        int sr = 48_000;
        double introSilent = channelRms(interleavedStereo, 0, 2000, (int) (0.35 * sr));
        double rmsAtT1 = channelRms(interleavedStereo, 0, (int) (t1Sec * sr), (int) ((t1Sec + 0.08) * sr));
        double rmsAtT2 = channelRms(interleavedStereo, 1, (int) (t2Sec * sr), (int) ((t2Sec + 0.08) * sr));
        assertTrue(introSilent < 200, "Expected quiet pre-cue intro, got " + introSilent);
        assertTrue(rmsAtT1 > 3000, "Expected strong synced SFX+voice energy at t1, got " + rmsAtT1);
        assertTrue(rmsAtT2 > 2500, "Expected strong synced SFX energy at t2, got " + rmsAtT2);
    }

    private static short[] extractAviPcm(byte[] aviBytes) {
        ByteBuffer buf = ByteBuffer.wrap(aviBytes).order(ByteOrder.LITTLE_ENDIAN);
        int totalShorts = 0;
        int p = 12;
        int moviEnd = -1;
        // Locate LIST .... movi
        while (p + 8 <= aviBytes.length) {
            int id = buf.getInt(p);
            int size = buf.getInt(p + 4);
            if (id == 0x5453494C /* LIST */ && p + 12 <= aviBytes.length && buf.getInt(p + 8) == 0x69766F6D /* movi */) {
                moviEnd = p + 8 + size;
                p += 12;
                break;
            }
            p += 8 + ((size + 1) & ~1);
        }
        assertTrue(moviEnd > 0, "AVI must contain LIST movi");
        int scan = p;
        while (scan + 8 <= moviEnd) {
            int id = buf.getInt(scan);
            int size = buf.getInt(scan + 4);
            if (id == 0x62773130 /* 01wb */) totalShorts += size / 2;
            scan += 8 + ((size + 1) & ~1);
        }
        short[] out = new short[totalShorts];
        int idx = 0;
        scan = p;
        while (scan + 8 <= moviEnd) {
            int id = buf.getInt(scan);
            int size = buf.getInt(scan + 4);
            if (id == 0x62773130 /* 01wb */) {
                for (int off = 0; off < size; off += 2) {
                    out[idx++] = buf.getShort(scan + 8 + off);
                }
            }
            scan += 8 + ((size + 1) & ~1);
        }
        return out;
    }

    private static double channelRms(short[] interleaved, int ch, int startFrame, int endFrame) {
        int s = Math.max(0, startFrame);
        int e = Math.min(interleaved.length / 2, endFrame);
        if (e <= s) return 0;
        double sum = 0;
        for (int f = s; f < e; f++) {
            double v = interleaved[f * 2 + ch];
            sum += v * v;
        }
        return Math.sqrt(sum / (e - s));
    }
}
