package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplaySoundCuePlan;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Renders the sample-accurate 48 kHz interleaved stereo soundtrack for exported replay videos by
 * decoding mod/vanilla {@code .ogg} sound assets via {@link JOrbisAudioStream} and mixing them in
 * lockstep with server-recorded ADPCM proximity voice clips.
 */
final class ReplayVideoAudioRenderer {
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 2;
    static final int SAMPLES_PER_VIDEO_FRAME = SAMPLE_RATE / ReplayVideoExport.FPS; // 1600 stereo frames per 30fps frame

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayVideoAudioRenderer.class);

    private ReplayVideoAudioRenderer() {}

    record DecodedSound(float[] samples, int channels, float sampleRate, float soundVolume, float soundPitch) {}

    @FunctionalInterface
    interface SoundResolver {
        @Nullable DecodedSound resolve(List<String> candidates);
    }

    static short[] render(@Nullable Minecraft client, LastMatchReplay replay, ReplayVideoScript script,
                          @Nullable ReplayVoiceData voice, int totalVideoFrames) {
        if (replay == null || script == null || totalVideoFrames <= 0) {
            return new short[Math.max(0, totalVideoFrames) * SAMPLES_PER_VIDEO_FRAME * CHANNELS];
        }
        ReplaySoundCuePlan.Schedule schedule = ReplaySoundCuePlan.forVideo(replay, script, voice);
        SoundResolver resolver = client == null ? candidates -> null : createClientResolver(client);
        return renderSchedule(schedule, resolver, totalVideoFrames);
    }

    static short[] renderSchedule(ReplaySoundCuePlan.Schedule schedule, SoundResolver resolver, int totalVideoFrames) {
        int totalStereoFrames = Math.max(0, totalVideoFrames * SAMPLES_PER_VIDEO_FRAME);
        short[] pcm = new short[totalStereoFrames * CHANNELS];
        if (totalStereoFrames == 0 || schedule == null) return pcm;

        float[] mixL = new float[totalStereoFrames];
        float[] mixR = new float[totalStereoFrames];
        Map<List<String>, Optional<DecodedSound>> cache = new HashMap<>();

        for (ReplaySoundCuePlan.SoundCue cue : schedule.sounds()) {
            DecodedSound asset = cache.computeIfAbsent(cue.candidates(),
                    keys -> Optional.ofNullable(resolver.resolve(keys))).orElse(null);
            if (asset == null || asset.samples().length == 0) continue;
            mixSoundCue(mixL, mixR, totalStereoFrames, cue, asset);
        }

        int nextAllowedVoiceSample = 0;
        for (ReplaySoundCuePlan.VoiceCue voiceCue : schedule.voices()) {
            nextAllowedVoiceSample = mixVoiceCue(mixL, mixR, totalStereoFrames, voiceCue, nextAllowedVoiceSample);
        }

        masterFinalize(mixL, mixR, pcm, totalStereoFrames);
        return pcm;
    }

    private static void mixSoundCue(float[] mixL, float[] mixR, int totalStereoFrames,
                                    ReplaySoundCuePlan.SoundCue cue, DecodedSound asset) {
        int startStereo = (int) Math.round(cue.time() * SAMPLE_RATE);
        if (startStereo >= totalStereoFrames) return;

        float effectivePitch = Math.clamp(cue.pitch() * asset.soundPitch(), 0.45f, 2.2f);
        double srcStep = (asset.sampleRate() * effectivePitch) / (double) SAMPLE_RATE;
        int srcFrames = asset.samples().length / Math.max(1, asset.channels());
        if (srcFrames <= 0 || srcStep <= 0) return;

        int dstFrames = (int) Math.floor((srcFrames - 1) / srcStep) + 1;
        float gain = cue.volume() * asset.soundVolume() * 0.85f;
        double panAngle = (Math.clamp(cue.pan(), -1f, 1f) + 1.0) * (Math.PI * 0.25);
        float gainL = (float) Math.cos(panAngle) * gain;
        float gainR = (float) Math.sin(panAngle) * gain;

        int dstStart = Math.max(0, startStereo);
        int dstEnd = Math.min(totalStereoFrames, startStereo + dstFrames);
        float[] src = asset.samples();
        int ch = asset.channels();

        for (int dst = dstStart; dst < dstEnd; dst++) {
            double srcPos = (dst - startStereo) * srcStep;
            int idx0 = (int) srcPos;
            if (idx0 < 0 || idx0 >= srcFrames) continue;
            int idx1 = Math.min(srcFrames - 1, idx0 + 1);
            float frac = (float) (srcPos - idx0);
            float lSample;
            float rSample;
            if (ch == 1) {
                float s = src[idx0] + (src[idx1] - src[idx0]) * frac;
                lSample = s;
                rSample = s;
            } else {
                int b0 = idx0 * ch;
                int b1 = idx1 * ch;
                lSample = src[b0] + (src[b1] - src[b0]) * frac;
                rSample = src[b0 + 1] + (src[b1 + 1] - src[b0 + 1]) * frac;
            }
            mixL[dst] += lSample * gainL;
            mixR[dst] += rSample * gainR;
        }
    }

    private static int mixVoiceCue(float[] mixL, float[] mixR, int totalStereoFrames,
                                   ReplaySoundCuePlan.VoiceCue voiceCue, int minStartSample) {
        ReplayVoiceData.Clip clip = voiceCue.clip();
        short[] samples = clip.decode48k();
        if (samples.length == 0) return minStartSample;

        long impactSample = Math.round(voiceCue.impactTime() * SAMPLE_RATE);
        long rawStartSample = impactSample + Math.round(clip.offsetMs() * (SAMPLE_RATE / 1000.0));
        int activeStart = (int) Math.max(minStartSample, Math.max(0L, rawStartSample));
        int maxEnd = (int) Math.min(totalStereoFrames, Math.min(Math.round(voiceCue.endTime() * SAMPLE_RATE),
                rawStartSample + samples.length));
        int count = maxEnd - activeStart;
        if (count <= SAMPLE_RATE / 10) return minStartSample;

        int fadeIn = Math.min(2880, count / 4);  // 60 ms
        int fadeOut = Math.min(4800, count / 3); // 100 ms
        double panAngle = (Math.clamp(voiceCue.pan() * 0.35f, -1f, 1f) + 1.0) * (Math.PI * 0.25);
        float voiceGain = 1.05f / 32768.0f;
        float gainL = (float) (Math.cos(panAngle) * Math.sqrt(2.0) * voiceGain);
        float gainR = (float) (Math.sin(panAngle) * Math.sqrt(2.0) * voiceGain);

        for (int i = 0; i < count; i++) {
            int dst = activeStart + i;
            int srcIdx = (int) (dst - rawStartSample);
            if (srcIdx < 0 || srcIdx >= samples.length) continue;
            float env = 1.0f;
            if (i < fadeIn && fadeIn > 0) {
                env = (float) (0.5 * (1.0 - Math.cos(Math.PI * i / fadeIn)));
            } else if (i >= count - fadeOut && fadeOut > 0) {
                int rem = count - 1 - i;
                env = (float) (0.5 * (1.0 - Math.cos(Math.PI * rem / fadeOut)));
            }
            float s = samples[srcIdx] * env;
            mixL[dst] += s * gainL;
            mixR[dst] += s * gainR;
        }
        return maxEnd + SAMPLE_RATE / 20; // leave at least 50 ms breathing room before next voice clip
    }

    private static void masterFinalize(float[] mixL, float[] mixR, short[] outInterleaved, int totalStereoFrames) {
        int edgeIn = Math.min(256, totalStereoFrames / 4);
        int edgeOut = Math.min(480, totalStereoFrames / 4);
        for (int i = 0; i < totalStereoFrames; i++) {
            float env = 1.0f;
            if (i < edgeIn && edgeIn > 0) env = i / (float) edgeIn;
            else if (i >= totalStereoFrames - edgeOut && edgeOut > 0) env = (totalStereoFrames - 1 - i) / (float) edgeOut;

            float l = softLimit(mixL[i] * env);
            float r = softLimit(mixR[i] * env);
            outInterleaved[i * 2] = (short) Math.clamp(Math.round(l * 32767.0f), Short.MIN_VALUE, Short.MAX_VALUE);
            outInterleaved[i * 2 + 1] = (short) Math.clamp(Math.round(r * 32767.0f), Short.MIN_VALUE, Short.MAX_VALUE);
        }
    }

    private static float softLimit(float x) {
        float abs = Math.abs(x);
        if (abs <= 0.85f) return x;
        return (float) (Math.signum(x) * (0.85 + 0.145 * Math.tanh((abs - 0.85) / 0.35)));
    }

    private static SoundResolver createClientResolver(Minecraft client) {
        RandomSource random = RandomSource.create(0x5245504C4159L);
        return candidates -> {
            SoundManager manager = client.getSoundManager();
            for (String candidate : candidates) {
                ResourceLocation eventId = ResourceLocation.tryParse(candidate);
                if (eventId == null) continue;
                WeighedSoundEvents weighed = manager.getSoundEvent(eventId);
                if (weighed == null) continue;
                Sound sound = weighed.getSound(random);
                if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
                    continue;
                }
                if (sound.getType() == Sound.Type.SOUND_EVENT) {
                    WeighedSoundEvents nested = manager.getSoundEvent(sound.getLocation());
                    if (nested == null) continue;
                    sound = nested.getSound(random);
                    if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND) {
                        continue;
                    }
                }
                ResourceLocation fileLoc = sound.getPath();
                Optional<Resource> res = client.getResourceManager().getResource(fileLoc);
                if (res.isEmpty()) continue;
                try (InputStream raw = res.get().open();
                     JOrbisAudioStream stream = new JOrbisAudioStream(raw)) {
                    AudioFormat format = stream.getFormat();
                    FloatArrayList pcm = new FloatArrayList(16_384);
                    while (stream.readChunk(pcm::add)) {
                        if (pcm.size() >= SAMPLE_RATE * 16) break; // safety cap (~8-16s per SFX)
                    }
                    if (!pcm.isEmpty()) {
                        float vol = Math.clamp(sound.getVolume().sample(random), 0.25f, 1.5f);
                        float pitch = Math.clamp(sound.getPitch().sample(random), 0.5f, 2.0f);
                        return new DecodedSound(pcm.toFloatArray(), Math.max(1, format.getChannels()),
                                format.getSampleRate(), vol, pitch);
                    }
                } catch (IOException | RuntimeException ex) {
                    LOGGER.debug("Could not decode replay SFX resource {}", fileLoc, ex);
                }
            }
            return null;
        };
    }
}
