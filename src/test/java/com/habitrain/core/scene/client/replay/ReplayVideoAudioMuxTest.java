package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.replay.ReplaySoundCuePlan;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.TrackType;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayVideoAudioMuxTest {

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
            assertEquals(Math.round(.5f * .85f * 32767), pcm[frame * 2]);
            assertEquals(0, pcm[frame * 2 + 1]);
        }
        assertEquals(0, pcm[rate * 5 / 4 * 2]);
    }

    @Test
    void aacEncoderRoundTripsThroughJaadWithExactSyncAndStereoSeparation() throws Exception {
        int sr = 48_000;
        int totalStereoFrames = sr * 2; // 2.0s @ 48 kHz
        short[] stereo = new short[totalStereoFrames * 2];

        // Impulse burst 1 at 0.600s (panned hard LEFT), Impulse burst 2 at 1.300s (panned hard RIGHT).
        int t1 = (int) (0.600 * sr);
        int t2 = (int) (1.300 * sr);
        for (int i = 0; i < 4800; i++) { // 100ms tonal burst
            double env = Math.sin(Math.PI * i / 4800.0);
            short val = (short) Math.round(18000.0 * env * Math.sin(2.0 * Math.PI * 520.0 * i / sr));
            stereo[(t1 + i) * 2] = val;     // LEFT
            stereo[(t2 + i) * 2 + 1] = val; // RIGHT
        }

        List<byte[]> adtsFrames = new java.util.ArrayList<>();
        ReplayAacEncoder encoder = new ReplayAacEncoder(buf -> {
            byte[] b = new byte[buf.remaining()];
            buf.get(b);
            adtsFrames.add(b);
        });
        encoder.push(stereo, 0, totalStereoFrames);
        encoder.finish();
        assertTrue(adtsFrames.size() >= 94, "Expected >= 94 AAC frames for 2s stereo 48k audio");

        // AudioSpecificConfig for AAC-LC (2), 48 kHz (3), stereo (2) = 0x11, 0x90
        Decoder decoder = new Decoder(new byte[]{0x11, (byte) 0x90});
        SampleBuffer sb = new SampleBuffer();
        short[] decoded = new short[adtsFrames.size() * 1024 * 2];
        int cursor = 0;
        for (byte[] adts : adtsFrames) {
            byte[] rawAac = Arrays.copyOfRange(adts, 7, adts.length);
            decoder.decodeFrame(rawAac, sb);
            assertEquals(48_000, sb.getSampleRate());
            assertEquals(2, sb.getChannels());
            byte[] pcm = sb.getData();
            sb.setBigEndian(false);
            // Read decoded samples directly via ShortBuffer matching sb endianness:
            ByteBuffer bb = ByteBuffer.wrap(pcm).order(sb.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            while (bb.hasRemaining() && cursor < decoded.length) {
                decoded[cursor++] = bb.getShort();
            }
        }

        // Check burst 1 at 0.600s (t1 .. t1 + 4800): strong LEFT, near-silent RIGHT
        double l1 = channelRms(decoded, 0, t1, t1 + 4800);
        double r1 = channelRms(decoded, 1, t1, t1 + 4800);
        assertTrue(l1 > 4500, "Expected strong decoded left burst at 0.600s, got " + l1);
        assertTrue(l1 > r1 * 8.0, "Expected stereo L/R isolation at 0.600s, got L=" + l1 + " R=" + r1);

        // Check burst 2 at 1.300s (t2 .. t2 + 4800): strong RIGHT, near-silent LEFT
        double l2 = channelRms(decoded, 0, t2, t2 + 4800);
        double r2 = channelRms(decoded, 1, t2, t2 + 4800);
        assertTrue(r2 > 4500, "Expected strong decoded right burst at 1.300s, got " + r2);
        assertTrue(r2 > l2 * 8.0, "Expected stereo R/L isolation at 1.300s, got L=" + l2 + " R=" + r2);

        // Check silent gap [0.15s .. 0.40s] stays silent
        double gap = channelRms(decoded, 0, (int) (0.15 * sr), (int) (0.40 * sr));
        assertTrue(gap < 150, "Expected quiet silence gap, got " + gap);
    }

    @Test
    void mp4AndAviMuxVideoAndAudioInExactFrameLockstep(@TempDir Path tempDir) throws Exception {
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
        try (ReplayVideoWriter mp4Writer = new ReplayMp4Writer(mp4, w, h, fps, true);
             ReplayVideoWriter aviWriter = new ReplayAviWriter(avi, w, h, fps, true)) {
            for (int f = 0; f < frames; f++) {
                mp4Writer.add(bgr);
                mp4Writer.addAudio(soundtrack,
                        f * ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME,
                        ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME);
                aviWriter.add(bgr);
                aviWriter.addAudio(soundtrack,
                        f * ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME,
                        ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME);
            }
            mp4Writer.finish();
            aviWriter.finish();
        }

        // Verify MP4 container has both video & AAC audio tracks and decodes cleanly in sync
        try (var ch = NIOUtils.readableChannel(mp4.toFile())) {
            MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(ch);
            assertEquals(1, demuxer.getVideoTracks().size());
            assertEquals(1, demuxer.getAudioTracks().size());
            DemuxerTrack audio = demuxer.getAudioTracks().getFirst();
            DemuxerTrackMeta meta = audio.getMeta();
            assertEquals(TrackType.AUDIO, meta.getType());
            assertNotNull(meta.getCodecPrivate(), "MP4 mp4a track must contain esds codec private");
            byte[] asc = new byte[meta.getCodecPrivate().remaining()];
            meta.getCodecPrivate().duplicate().get(asc);

            Decoder dec = new Decoder(asc);
            SampleBuffer sb = new SampleBuffer();
            short[] mp4Pcm = new short[frames * ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME * 2 + 8192];
            int pos = 0;
            Packet pkt;
            while ((pkt = audio.nextFrame()) != null) {
                ByteBuffer data = pkt.getData();
                // CodecMP4DemuxerTrack.convertPacket prepends a 7-byte ADTS header; verify it parses and strip before raw AAC decode.
                var adts = org.jcodec.codecs.aac.ADTSParser.read(data);
                assertNotNull(adts, "Demuxed AAC packet should reconstruct valid ADTS header from esds");
                assertEquals(48_000, adts.getSampleRate());
                byte[] frameBytes = new byte[data.remaining()];
                data.get(frameBytes);
                dec.decodeFrame(frameBytes, sb);
                sb.setBigEndian(false);
                ByteBuffer pcmBuf = ByteBuffer.wrap(sb.getData()).order(ByteOrder.LITTLE_ENDIAN);
                while (pcmBuf.hasRemaining() && pos < mp4Pcm.length) {
                    mp4Pcm[pos++] = pcmBuf.getShort();
                }
            }
            assertTrue(pos > 0, "Expected decoded AAC PCM samples from MP4");
            assertPeakSync(mp4Pcm, t1, t2);
        }

        // Verify AVI container has 2 streams (vids + auds) and PCM chunks in lockstep
        byte[] aviBytes = Files.readAllBytes(avi);
        String headerAscii = new String(aviBytes, 0, Math.min(aviBytes.length, 512), java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(headerAscii.contains("vids"));
        assertTrue(headerAscii.contains("auds"));
        short[] aviPcm = extractAviPcm(aviBytes);
        assertEquals(soundtrack.length, aviPcm.length);
        assertPeakSync(aviPcm, t1, t2);
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
