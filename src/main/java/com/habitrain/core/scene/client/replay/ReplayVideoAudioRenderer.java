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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

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
        return prepare(client, replay, script, voice, totalVideoFrames).get();
    }

    static Supplier<short[]> prepare(Minecraft client, LastMatchReplay replay, ReplayVideoScript script,
                                     ReplayVoiceData voice, int frames) {
        var schedule = ReplaySoundCuePlan.forVideo(replay, script, voice,
                file -> client != null && client.getResourceManager().getResource(file).isPresent());
        var assets = new HashMap<List<String>, List<SoundAsset>>();
        var random = RandomSource.create(0x5245504C4159L);
        if (client != null) for (var cue : schedule.sounds()) {
            assets.computeIfAbsent(soundKey(cue), keys -> cue.recordedFile() == null ? resolve(client, keys, random)
                    : client.getResourceManager().getResource(cue.recordedFile())
                            .map(resource -> List.of(new SoundAsset(cue.recordedFile(), resource, 1f, 1f))).orElseGet(List::of));
        }
        return () -> renderSchedule(schedule, keys -> decode(assets.getOrDefault(keys, List.of())), frames);
    }

    static short[] renderSchedule(ReplaySoundCuePlan.Schedule schedule, SoundResolver resolver, int totalVideoFrames) {
        int totalStereoFrames = Math.max(0, totalVideoFrames * SAMPLES_PER_VIDEO_FRAME);
        short[] pcm = new short[totalStereoFrames * CHANNELS];
        if (totalStereoFrames == 0 || schedule == null) return pcm;

        float[] mixL = new float[Math.min(SAMPLE_RATE, totalStereoFrames)];
        float[] mixR = new float[mixL.length];
        var cache = new LinkedHashMap<List<String>, Optional<DecodedSound>>(32, 0.75f, true);
        var ended = new IdentityHashMap<ReplaySoundCuePlan.SoundCue, Integer>();
        var voices = new java.util.IdentityHashMap<ReplayVoiceData.Clip, short[]>();
        for (int offset = 0; offset < totalStereoFrames; offset += mixL.length) {
            checkInterrupted();
            java.util.Arrays.fill(mixL, 0);
            java.util.Arrays.fill(mixR, 0);
            for (var cue : schedule.sounds()) {
                if (Math.round(cue.time() * SAMPLE_RATE) >= offset + mixL.length
                        || ended.getOrDefault(cue, totalStereoFrames) <= offset) continue;
                DecodedSound asset = cache.computeIfAbsent(soundKey(cue), keys -> Optional.ofNullable(resolver.resolve(keys))).orElse(null);
                if (cache.size() > 32) cache.pollFirstEntry();
                int end = asset == null ? offset : mixSoundCue(mixL, mixR, totalStereoFrames, offset, cue, asset);
                ended.put(cue, end);
            }
            int nextAllowedVoiceSample = 0;
            for (var cue : schedule.voices()) {
                short[] samples = voices.computeIfAbsent(cue.clip(), ReplayVoiceData.Clip::decode48k);
                nextAllowedVoiceSample = mixVoiceCue(mixL, mixR, totalStereoFrames, offset, cue, nextAllowedVoiceSample, samples);
            }
            masterFinalize(mixL, mixR, pcm, totalStereoFrames, offset);
        }
        return pcm;
    }

    private static List<String> soundKey(ReplaySoundCuePlan.SoundCue cue) {
        return cue.recordedFile() == null ? cue.candidates() : List.of(cue.recordedFile().toString());
    }

    private static int mixSoundCue(float[] mixL, float[] mixR, int totalStereoFrames, int offset,
                                    ReplaySoundCuePlan.SoundCue cue, DecodedSound asset) {
        int startStereo = (int) Math.round(cue.time() * SAMPLE_RATE);
        if (startStereo >= totalStereoFrames) return totalStereoFrames;

        float effectivePitch = Math.clamp(cue.pitch() * asset.soundPitch(), 0.45f, 2.2f);
        double srcStep = (asset.sampleRate() * effectivePitch) / (double) SAMPLE_RATE;
        int srcFrames = asset.samples().length / Math.max(1, asset.channels());
        if (srcFrames <= 0 || srcStep <= 0) return offset;

        int dstFrames = (int) Math.floor((srcFrames - 1) / srcStep) + 1;
        float gain = cue.volume() * asset.soundVolume() * 0.85f;
        double panAngle = (Math.clamp(cue.pan(), -1f, 1f) + 1.0) * (Math.PI * 0.25);
        float gainL = (float) Math.cos(panAngle) * gain;
        float gainR = (float) Math.sin(panAngle) * gain;

        int dstStart = Math.max(offset, startStereo);
        int dstEnd = Math.min(Math.min(totalStereoFrames, offset + mixL.length), startStereo + dstFrames);
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
            mixL[dst - offset] += lSample * gainL;
            mixR[dst - offset] += rSample * gainR;
        }
        return Math.min(totalStereoFrames, startStereo + dstFrames);
    }

    private static int mixVoiceCue(float[] mixL, float[] mixR, int totalStereoFrames, int offset,
                                   ReplaySoundCuePlan.VoiceCue voiceCue, int minStartSample, short[] samples) {
        if (samples.length == 0) return minStartSample;

        long rawStartSample = Math.round(voiceCue.sampleStartTime(1.0) * SAMPLE_RATE);
        long scheduledStartSample = Math.round(voiceCue.startTime() * SAMPLE_RATE);
        int activeStart = (int) Math.max(minStartSample, Math.max(rawStartSample, scheduledStartSample));
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

        for (int i = Math.max(0, offset - activeStart); i < Math.min(count, offset + mixL.length - activeStart); i++) {
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
            mixL[dst - offset] += s * gainL;
            mixR[dst - offset] += s * gainR;
        }
        return maxEnd + SAMPLE_RATE / 20; // leave at least 50 ms breathing room before next voice clip
    }

    private static void masterFinalize(float[] mixL, float[] mixR, short[] outInterleaved, int totalStereoFrames, int offset) {
        int edgeIn = Math.min(256, totalStereoFrames / 4);
        int edgeOut = Math.min(480, totalStereoFrames / 4);
        for (int i = offset; i < Math.min(offset + mixL.length, totalStereoFrames); i++) {
            if ((i & 16383) == 0) checkInterrupted();
            float env = 1.0f;
            if (i < edgeIn && edgeIn > 0) env = i / (float) edgeIn;
            else if (i >= totalStereoFrames - edgeOut && edgeOut > 0) env = (totalStereoFrames - 1 - i) / (float) edgeOut;

            float l = softLimit(mixL[i - offset] * env);
            float r = softLimit(mixR[i - offset] * env);
            outInterleaved[i * 2] = (short) Math.round(l * ReplayAudioPlaybackController.REPLAY_GAIN * 32767.0f);
            outInterleaved[i * 2 + 1] = (short) Math.round(r * ReplayAudioPlaybackController.REPLAY_GAIN * 32767.0f);
        }
    }

    private static float softLimit(float x) {
        float abs = Math.abs(x);
        if (abs <= 0.85f) return x;
        return (float) (Math.signum(x) * (0.85 + 0.145 * Math.tanh((abs - 0.85) / 0.35)));
    }

    private record SoundAsset(ResourceLocation location, Resource resource, float volume, float pitch) {}

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static List<SoundAsset> resolve(Minecraft client, List<String> candidates, RandomSource random) {
            var sources = new ArrayList<SoundAsset>();
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
                sources.add(new SoundAsset(fileLoc, res.get(), Math.clamp(sound.getVolume().sample(random), 0.25f, 1.5f),
                        Math.clamp(sound.getPitch().sample(random), 0.5f, 2.0f)));
            }
            return sources;
    }

    private static DecodedSound decode(List<SoundAsset> sources) {
            for (var source : sources) {
                checkInterrupted();
                try (InputStream raw = source.resource().open();
                     JOrbisAudioStream stream = new JOrbisAudioStream(raw)) {
                    AudioFormat format = stream.getFormat();
                    FloatArrayList pcm = new FloatArrayList(16_384);
                    while (stream.readChunk(pcm::add)) {
                        if (pcm.size() >= SAMPLE_RATE * 16) break; // safety cap (~8-16s per SFX)
                    }
                    if (!pcm.isEmpty()) {
                        return new DecodedSound(pcm.toFloatArray(), Math.max(1, format.getChannels()),
                                format.getSampleRate(), source.volume(), source.pitch());
                    }
                } catch (IOException | RuntimeException ex) {
                    LOGGER.debug("Could not decode replay SFX resource {}", source.location(), ex);
                }
            }
            return null;
    }
}
