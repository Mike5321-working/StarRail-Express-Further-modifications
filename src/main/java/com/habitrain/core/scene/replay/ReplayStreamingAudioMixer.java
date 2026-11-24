package com.habitrain.core.scene.replay;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

/**
 * Streaming chunked multi-track audio mixer for replay video exports.
 *
 * <p>Mixes 48 kHz 16-bit interleaved stereo PCM in fixed 1-second windows ({@link #CHUNK_FRAMES} = 48,000
 * stereo frames, ~1 MiB peak scratch memory regardless of video duration) directly into a temporary PCM
 * file or stream sink, completely eliminating the ~230 MiB monolithic {@code short[]} heap allocation on
 * 20-minute exports.</p>
 *
 * <p>Supports:</p>
 * <ul>
 *     <li>Concurrent multi-speaker spatial voice mixing without serial truncation;</li>
 *     <li>Pitch-preserving WSOLA time-stretching ({@link ReplayWsolaTimeStretch}) for 2x/4x speed-ups and
 *     0.3x..0.5x slow-motion/freeze windows;</li>
 *     <li>Server-recorded and fallback game SFX cues with LRU decoding cache;</li>
 *     <li>Procedural dynamic music bus with real-time envelope Auto-Ducking driven by speech &amp; SFX energy
 *     plus director-scheduled silence/suction windows (e.g. Tier 5 Apotheosis pre-drop).</li>
 * </ul>
 */
public final class ReplayStreamingAudioMixer {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 2;
    public static final int BYTES_PER_STEREO_FRAME = CHANNELS * 2;
    public static final int CHUNK_FRAMES = SAMPLE_RATE; // 1.0 second per streaming slice
    public static final float DEFAULT_MASTER_GAIN = 0.60f;
    private static final long MAX_SOUND_CACHE_BYTES = 24L * 1024 * 1024;
    private static final int MAX_SOUND_CACHE_ENTRIES = 48;

    private ReplayStreamingAudioMixer() {}

    /** Decoded mono/stereo float sound asset independent of Minecraft client classes. */
    public record DecodedSfx(float[] samples, int channels, float sampleRate, float assetVolume, float assetPitch) {}

    @FunctionalInterface
    public interface SfxResolver {
        @Nullable DecodedSfx resolve(List<String> keys);
    }

    /** Streaming music bus callback writing additive stereo samples for {@code [offsetFrame, offsetFrame + count)}. */
    @FunctionalInterface
    public interface MusicChunkMixer {
        void mixChunk(float[] outL, float[] outR, long offsetFrame, int count);
    }

    /** Receiver of little-endian 16-bit stereo PCM bytes chunk by chunk. */
    @FunctionalInterface
    public interface PcmChunkSink {
        void accept(byte[] pcm16LeStereo, int length) throws IOException;
    }

    /**
     * One scheduled game SFX cue on the video clock.
     *
     * @param videoTimeSec   start time on the exported video timeline in seconds
     * @param keys           ordered sound identifiers or exact recorded resource key
     * @param volume         cue gain (typically 0..1.5)
     * @param pitch          playback pitch multiplier
     * @param pan            stereo pan (-1 left .. +1 right)
     * @param maxDurationSec optional cap in seconds ({@code <= 0} means full sound length)
     */
    public record SfxEvent(double videoTimeSec, List<String> keys, float volume, float pitch, float pan,
                           float maxDurationSec) {
        public SfxEvent {
            keys = List.copyOf(keys);
        }

        public SfxEvent(double videoTimeSec, List<String> keys, float volume, float pitch, float pan) {
            this(videoTimeSec, keys, volume, pitch, pan, 0f);
        }
    }

    /**
     * One scheduled speaker voice segment on the video clock.
     *
     * @param speakerId                   speaker UUID (or null for legacy v1 mixed clips)
     * @param videoStartSec               video timeline start where this segment becomes audible
     * @param videoEndSec                 video timeline end where this segment fades out
     * @param sourceOffsetSec             starting offset inside the decoded 48 kHz mono source clip
     * @param playbackSpeed               nominal speech time-scale (e.g. 1.0, 2.0, 4.0, 0.45)
     * @param gain                        spatial/audibility gain (0..1.5)
     * @param pan                         stereo pan (-1 .. +1; 0 for self/centered)
     * @param preservePitchWsola          whether to apply {@link ReplayWsolaTimeStretch} when speed != 1.0
     * @param pcm48kSupplier              lazy decoder returning 48 kHz mono 16-bit samples
     * @param variableSourceSecAtLocalSec optional non-linear mapping from local video seconds {@code (t - videoStartSec)}
     *                                    to source seconds inside the decoded clip (overrides constant {@code playbackSpeed})
     */
    public record VoiceSegment(
            @Nullable UUID speakerId,
            double videoStartSec,
            double videoEndSec,
            double sourceOffsetSec,
            double playbackSpeed,
            float gain,
            float pan,
            boolean preservePitchWsola,
            Supplier<short[]> pcm48kSupplier,
            @Nullable DoubleUnaryOperator variableSourceSecAtLocalSec
    ) {
        public VoiceSegment(@Nullable UUID speakerId, double videoStartSec, double videoEndSec,
                            double sourceOffsetSec, double playbackSpeed, float gain, float pan,
                            boolean preservePitchWsola, Supplier<short[]> pcm48kSupplier) {
            this(speakerId, videoStartSec, videoEndSec, sourceOffsetSec, playbackSpeed, gain, pan,
                    preservePitchWsola, pcm48kSupplier, null);
        }
    }

    /** Explicit director-scheduled music ducking or low-end suction window (e.g. Tier 5 pre-drop). */
    public record DirectorDuck(double startSec, double endSec, float targetGain, double attackSec, double releaseSec) {
        public float factor(double t) {
            if (t < startSec - attackSec || t > endSec + releaseSec) return 1f;
            if (t < startSec) {
                double k = (t - (startSec - attackSec)) / Math.max(1e-6, attackSec);
                return (float) (1.0 - (1.0 - targetGain) * k);
            }
            if (t <= endSec) return targetGain;
            double k = (t - endSec) / Math.max(1e-6, releaseSec);
            return (float) (targetGain + (1.0 - targetGain) * k);
        }
    }

    /** Complete streaming mix plan. */
    public record MixPlan(
            long totalStereoFrames,
            List<SfxEvent> sfxEvents,
            List<VoiceSegment> voiceSegments,
            List<DirectorDuck> directorDucks,
            float voiceVolume,
            float sfxVolume,
            float musicVolume,
            boolean autoDucking,
            float masterGain
    ) {
        public MixPlan {
            totalStereoFrames = Math.max(0L, totalStereoFrames);
            sfxEvents = sfxEvents == null ? List.of() : List.copyOf(sfxEvents);
            voiceSegments = voiceSegments == null ? List.of() : List.copyOf(voiceSegments);
            directorDucks = directorDucks == null ? List.of() : List.copyOf(directorDucks);
            voiceVolume = Math.clamp(Float.isFinite(voiceVolume) ? voiceVolume : 1f, 0f, 2f);
            sfxVolume = Math.clamp(Float.isFinite(sfxVolume) ? sfxVolume : 1f, 0f, 2f);
            musicVolume = Math.clamp(Float.isFinite(musicVolume) ? musicVolume : 1f, 0f, 2f);
            masterGain = Math.clamp(Float.isFinite(masterGain) ? masterGain : DEFAULT_MASTER_GAIN, 0f, 1.5f);
        }
    }

    /** Telemetry returned upon completing a streaming mix pass. */
    public record MixStats(
            long totalStereoFrames,
            long totalBytesWritten,
            int chunksProcessed,
            int maxConcurrentVoices,
            long peakScratchBufferBytes
    ) {}

    /** Streams the entire mixed soundtrack directly into a newly created temporary {@code .pcm} file in {@code dir}. */
    public static Path mixToTempPcmFile(Path directory,
                                        MixPlan plan,
                                        @Nullable SfxResolver sfxResolver,
                                        @Nullable MusicChunkMixer musicMixer) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".replay-stream-audio-", ".pcm");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp), 128 * 1024)) {
            mixToSink(plan, sfxResolver, musicMixer, (bytes, len) -> out.write(bytes, 0, len));
            out.flush();
            return temp;
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            throw failure;
        }
    }

    /** Streams the entire mixed soundtrack directly to {@code outputFile} and returns mix telemetry. */
    public static MixStats mixToFile(Path outputFile,
                                     MixPlan plan,
                                     @Nullable SfxResolver sfxResolver,
                                     @Nullable MusicChunkMixer musicMixer) throws IOException {
        Path parent = outputFile.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputFile), 128 * 1024)) {
            MixStats stats = mixToSink(plan, sfxResolver, musicMixer, (bytes, len) -> out.write(bytes, 0, len));
            out.flush();
            return stats;
        }
    }

    /**
     * Core chunked streaming mixer: processes at most {@link #CHUNK_FRAMES} stereo frames at a time and emits
     * little-endian 16-bit interleaved stereo PCM bytes into {@code sink}.
     */
    public static MixStats mixToSink(MixPlan plan,
                                     @Nullable SfxResolver sfxResolver,
                                     @Nullable MusicChunkMixer musicMixer,
                                     PcmChunkSink sink) throws IOException {
        if (plan == null || plan.totalStereoFrames() <= 0) {
            return new MixStats(0L, 0L, 0, 0, 0L);
        }
        long totalFrames = plan.totalStereoFrames();
        int chunkCap = (int) Math.min(CHUNK_FRAMES, totalFrames);

        float[] voiceL = new float[chunkCap];
        float[] voiceR = new float[chunkCap];
        float[] sfxL = new float[chunkCap];
        float[] sfxR = new float[chunkCap];
        float[] musicL = new float[chunkCap];
        float[] musicR = new float[chunkCap];
        byte[] pcmOut = new byte[chunkCap * BYTES_PER_STEREO_FRAME];
        long peakScratchBytes = (long) chunkCap * (6L * Float.BYTES + BYTES_PER_STEREO_FRAME);

        // Sort SFX and Voice lists by start time for O(1) sliding window cursor advancement.
        List<SfxEvent> sortedSfx = new ArrayList<>(plan.sfxEvents());
        sortedSfx.sort(Comparator.comparingDouble(SfxEvent::videoTimeSec));
        List<VoiceSegment> sortedVoices = new ArrayList<>(plan.voiceSegments());
        sortedVoices.sort(Comparator.comparingDouble(VoiceSegment::videoStartSec));

        // Bounded LRU cache for decoded SFX assets.
        LinkedHashMap<List<String>, Optional<DecodedSfx>> sfxCache = new LinkedHashMap<>(MAX_SOUND_CACHE_ENTRIES, 0.75f, true);
        long cachedSfxBytes = 0L;

        // Active SFX spanning across chunk boundaries.
        List<ActiveSfx> activeSfx = new ArrayList<>();
        int nextSfxIdx = 0;

        // Active prepared voice tracks spanning across chunk boundaries (lazily decoded, immediately evicted once done).
        List<ActiveVoice> activeVoices = new ArrayList<>();
        int nextVoiceIdx = 0;

        // Real-time Auto-Ducking envelope state (persists seamlessly across chunk boundaries).
        float duckGain = 1.0f;
        final float attackCoeff = (float) (1.0 - Math.exp(-1.0 / (0.025 * SAMPLE_RATE)));  // 25 ms fast attack
        final float releaseCoeff = (float) (1.0 - Math.exp(-1.0 / (0.260 * SAMPLE_RATE))); // 260 ms smooth release
        float voiceEnv = 0.0f;
        float sfxEnv = 0.0f;
        final float envRelease = (float) (1.0 - Math.exp(-1.0 / (0.080 * SAMPLE_RATE)));   // 80 ms RMS follower

        long totalBytesWritten = 0L;
        int chunksProcessed = 0;
        int maxConcurrentVoices = 0;
        long edgeIn = Math.min(256L, totalFrames / 4);
        long edgeOut = Math.min(480L, totalFrames / 4);

        for (long offset = 0L; offset < totalFrames; offset += chunkCap) {
            checkInterrupted();
            int count = (int) Math.min(chunkCap, totalFrames - offset);
            long chunkEnd = offset + count;

            Arrays.fill(voiceL, 0, count, 0f);
            Arrays.fill(voiceR, 0, count, 0f);
            Arrays.fill(sfxL, 0, count, 0f);
            Arrays.fill(sfxR, 0, count, 0f);
            Arrays.fill(musicL, 0, count, 0f);
            Arrays.fill(musicR, 0, count, 0f);

            // 1. Activate and mix SFX cues overlapping [offset, chunkEnd)
            if (plan.sfxVolume() > 0.001f && sfxResolver != null) {
                while (nextSfxIdx < sortedSfx.size()) {
                    SfxEvent ev = sortedSfx.get(nextSfxIdx);
                    long startFrame = Math.round(ev.videoTimeSec() * SAMPLE_RATE);
                    if (startFrame >= chunkEnd) break;
                    nextSfxIdx++;
                    if (ev.keys().isEmpty() || ev.volume() <= 0.0001f) continue;

                    DecodedSfx asset = resolveCached(sfxCache, ev.keys(), sfxResolver);
                    if (asset == null) continue;
                    // Track approximate cache size
                    cachedSfxBytes = maintainCacheBudget(sfxCache, cachedSfxBytes, ev.keys(), asset);

                    ActiveSfx act = ActiveSfx.create(ev, asset, startFrame, totalFrames);
                    if (act != null && act.endFrame > offset) {
                        activeSfx.add(act);
                    }
                }
                Iterator<ActiveSfx> sfxIt = activeSfx.iterator();
                while (sfxIt.hasNext()) {
                    ActiveSfx s = sfxIt.next();
                    mixActiveSfx(sfxL, sfxR, offset, count, s, plan.sfxVolume());
                    if (s.endFrame <= chunkEnd) {
                        sfxIt.remove();
                    }
                }
            }

            // 2. Activate and mix concurrent Voice segments overlapping [offset, chunkEnd)
            if (plan.voiceVolume() > 0.001f) {
                while (nextVoiceIdx < sortedVoices.size()) {
                    VoiceSegment seg = sortedVoices.get(nextVoiceIdx);
                    long startFrame = Math.max(0L, Math.round(seg.videoStartSec() * SAMPLE_RATE));
                    if (startFrame >= chunkEnd) break;
                    nextVoiceIdx++;
                    long endFrame = Math.min(totalFrames, Math.round(seg.videoEndSec() * SAMPLE_RATE));
                    if (endFrame <= offset || endFrame <= startFrame || seg.gain() <= 0.0001f) continue;

                    ActiveVoice act = prepareActiveVoice(seg, startFrame, endFrame);
                    if (act != null && act.endFrame > offset) {
                        activeVoices.add(act);
                    }
                }
                if (activeVoices.size() > maxConcurrentVoices) {
                    maxConcurrentVoices = activeVoices.size();
                }
                // Gentle headroom scaling when 3+ players shout simultaneously so concurrent speech stays clear.
                float crowdHeadroom = activeVoices.size() <= 2 ? 1.0f : (float) (1.25 / Math.sqrt(activeVoices.size()));
                Iterator<ActiveVoice> voiceIt = activeVoices.iterator();
                while (voiceIt.hasNext()) {
                    ActiveVoice v = voiceIt.next();
                    mixActiveVoice(voiceL, voiceR, offset, count, v, plan.voiceVolume() * crowdHeadroom);
                    if (v.endFrame <= chunkEnd) {
                        voiceIt.remove();
                    }
                }
            }

            // 3. Mix dynamic procedural music bus for this chunk
            if (musicMixer != null && plan.musicVolume() > 0.001f) {
                musicMixer.mixChunk(musicL, musicR, offset, count);
            }

            // 4. Master sum with real-time Auto-Ducking, Director Ducks, soft limiter, and 16-bit LE encode
            float musicScale = plan.musicVolume();
            float masterGain = plan.masterGain();
            boolean autoDuck = plan.autoDucking();
            List<DirectorDuck> dirDucks = plan.directorDucks();

            for (int k = 0; k < count; k++) {
                if ((k & 16383) == 0) checkInterrupted();
                long globalFrame = offset + k;
                float vl = voiceL[k], vr = voiceR[k];
                float sl = softLimitSfx(sfxL[k]), sr = softLimitSfx(sfxR[k]);
                float ml = musicL[k] * musicScale, mr = musicR[k] * musicScale;

                if (musicMixer != null && musicScale > 0.001f) {
                    float targetDuck = 1.0f;
                    if (autoDuck) {
                        float vMag = Math.max(Math.abs(vl), Math.abs(vr));
                        float sMag = Math.max(Math.abs(sl), Math.abs(sr));
                        voiceEnv = vMag > voiceEnv ? vMag : voiceEnv + (vMag - voiceEnv) * envRelease;
                        sfxEnv = sMag > sfxEnv ? sMag : sfxEnv + (sMag - sfxEnv) * envRelease;
                        if (voiceEnv > 0.015f) {
                            float strength = Math.clamp((voiceEnv - 0.015f) / 0.09f, 0f, 1f);
                            targetDuck = Math.min(targetDuck, 1.0f - 0.64f * strength); // down to 0.36x under speech
                        }
                        if (sfxEnv > 0.14f) {
                            float strength = Math.clamp((sfxEnv - 0.14f) / 0.35f, 0f, 1f);
                            targetDuck = Math.min(targetDuck, 1.0f - 0.28f * strength); // down to 0.72x under loud SFX
                        }
                    }
                    if (!dirDucks.isEmpty()) {
                        double tSec = globalFrame / (double) SAMPLE_RATE;
                        for (int d = 0; d < dirDucks.size(); d++) {
                            targetDuck = Math.min(targetDuck, dirDucks.get(d).factor(tSec));
                        }
                    }
                    float coeff = targetDuck < duckGain ? attackCoeff : releaseCoeff;
                    duckGain += (targetDuck - duckGain) * coeff;
                    ml *= duckGain;
                    mr *= duckGain;
                }

                float env = 1.0f;
                if (globalFrame < edgeIn && edgeIn > 0) {
                    env = globalFrame / (float) edgeIn;
                } else if (globalFrame >= totalFrames - edgeOut && edgeOut > 0) {
                    env = (totalFrames - 1 - globalFrame) / (float) edgeOut;
                }

                float sumL = softLimit((vl + sl + ml) * env);
                float sumR = softLimit((vr + sr + mr) * env);
                short outL = (short) Math.clamp(Math.round(sumL * masterGain * 32767.0f), Short.MIN_VALUE, Short.MAX_VALUE);
                short outR = (short) Math.clamp(Math.round(sumR * masterGain * 32767.0f), Short.MIN_VALUE, Short.MAX_VALUE);

                int bp = k * 4;
                pcmOut[bp] = (byte) (outL & 0xFF);
                pcmOut[bp + 1] = (byte) ((outL >>> 8) & 0xFF);
                pcmOut[bp + 2] = (byte) (outR & 0xFF);
                pcmOut[bp + 3] = (byte) ((outR >>> 8) & 0xFF);
            }

            int byteLen = count * BYTES_PER_STEREO_FRAME;
            sink.accept(pcmOut, byteLen);
            totalBytesWritten += byteLen;
            chunksProcessed++;
        }

        return new MixStats(totalFrames, totalBytesWritten, chunksProcessed, maxConcurrentVoices, peakScratchBytes);
    }

    /** Utility converting a legacy {@link ReplaySoundCuePlan.Schedule} into a {@link MixPlan}. */
    public static MixPlan fromLegacySchedule(ReplaySoundCuePlan.Schedule schedule,
                                             long totalStereoFrames,
                                             ReplayExportContentOptions options,
                                             float masterGain) {
        if (schedule == null) {
            return new MixPlan(totalStereoFrames, List.of(), List.of(), List.of(),
                    options.voiceVolume(), options.sfxVolume(), options.musicLevel().gain,
                    options.autoDucking(), masterGain);
        }
        List<SfxEvent> sfx = new ArrayList<>(schedule.sounds().size());
        for (ReplaySoundCuePlan.SoundCue cue : schedule.sounds()) {
            List<String> keys = cue.recordedFile() == null ? cue.candidates() : List.of(cue.recordedFile().toString());
            sfx.add(new SfxEvent(cue.time(), keys, cue.volume(), cue.pitch(), cue.pan()));
        }
        List<VoiceSegment> voices = new ArrayList<>(schedule.voices().size());
        for (ReplaySoundCuePlan.VoiceCue vc : schedule.voices()) {
            double rawStart = vc.sampleStartTime(1.0);
            double start = Math.max(vc.startTime(), rawStart);
            double srcOffset = Math.max(0.0, start - rawStart);
            double end = Math.min(vc.endTime(), rawStart + vc.clip().durationSeconds());
            if (end - start >= 0.08) {
                voices.add(new VoiceSegment(
                        null, start, end, srcOffset, 1.0,
                        1.05f, vc.pan() * 0.35f, false, vc.clip()::decode48k
                ));
            }
        }
        return new MixPlan(totalStereoFrames, sfx, voices, List.of(),
                options.voiceVolume(), options.sfxVolume(), 1.0f, options.autoDucking(), masterGain);
    }

    // ------------------------------------------------------------------ active track helpers

    private static final class ActiveSfx {
        final DecodedSfx asset;
        final long startFrame;
        final long endFrame;
        final double srcStep;
        final int srcFrames;
        final float gainL;
        final float gainR;

        private ActiveSfx(DecodedSfx asset, long startFrame, long endFrame, double srcStep, int srcFrames,
                          float gainL, float gainR) {
            this.asset = asset;
            this.startFrame = startFrame;
            this.endFrame = endFrame;
            this.srcStep = srcStep;
            this.srcFrames = srcFrames;
            this.gainL = gainL;
            this.gainR = gainR;
        }

        static @Nullable ActiveSfx create(SfxEvent ev, DecodedSfx asset, long startFrame, long totalFrames) {
            int ch = Math.max(1, asset.channels());
            int srcFrames = asset.samples().length / ch;
            if (srcFrames <= 0) return null;
            float effectivePitch = Math.clamp(ev.pitch() * asset.assetPitch(), 0.35f, 2.4f);
            double srcStep = (asset.sampleRate() * effectivePitch) / (double) SAMPLE_RATE;
            if (srcStep <= 0) return null;
            long dstFrames = (long) Math.floor((srcFrames - 1) / srcStep) + 1L;
            if (ev.maxDurationSec() > 0f) {
                dstFrames = Math.min(dstFrames, Math.round(ev.maxDurationSec() * SAMPLE_RATE));
            }
            long endFrame = Math.min(totalFrames, startFrame + dstFrames);
            if (endFrame <= startFrame) return null;

            float gain = Math.min(1.25f, ev.volume() * asset.assetVolume()) * 0.80f;
            double panAngle = (Math.clamp(ev.pan(), -1f, 1f) + 1.0) * (Math.PI * 0.25);
            float gainL = (float) Math.cos(panAngle) * gain;
            float gainR = (float) Math.sin(panAngle) * gain;
            return new ActiveSfx(asset, startFrame, endFrame, srcStep, srcFrames, gainL, gainR);
        }
    }

    private static void mixActiveSfx(float[] mixL, float[] mixR, long chunkOffset, int chunkCount,
                                     ActiveSfx sfx, float busVolume) {
        long dstStart = Math.max(chunkOffset, sfx.startFrame);
        long dstEnd = Math.min(chunkOffset + chunkCount, sfx.endFrame);
        if (dstEnd <= dstStart) return;

        float[] src = sfx.asset.samples();
        int ch = Math.max(1, sfx.asset.channels());
        float gL = sfx.gainL * busVolume;
        float gR = sfx.gainR * busVolume;

        for (long dst = dstStart; dst < dstEnd; dst++) {
            double srcPos = (dst - sfx.startFrame) * sfx.srcStep;
            int idx0 = (int) srcPos;
            if (idx0 < 0 || idx0 >= sfx.srcFrames) continue;
            int idx1 = Math.min(sfx.srcFrames - 1, idx0 + 1);
            float frac = (float) (srcPos - idx0);
            float lSample, rSample;
            if (ch == 1) {
                float v = src[idx0] + (src[idx1] - src[idx0]) * frac;
                lSample = v;
                rSample = v;
            } else {
                int b0 = idx0 * ch;
                int b1 = idx1 * ch;
                lSample = src[b0] + (src[b1] - src[b0]) * frac;
                rSample = src[b0 + 1] + (src[b1 + 1] - src[b0 + 1]) * frac;
            }
            int k = (int) (dst - chunkOffset);
            mixL[k] += lSample * gL;
            mixR[k] += rSample * gR;
        }
    }

    private static final class ActiveVoice {
        final float[] samples;
        final long startFrame;
        final long endFrame;
        final int fadeIn;
        final int fadeOut;
        final float gainL;
        final float gainR;

        ActiveVoice(float[] samples, long startFrame, long endFrame, float gainL, float gainR) {
            this.samples = samples;
            this.startFrame = startFrame;
            this.endFrame = endFrame;
            int len = (int) Math.min(samples.length, endFrame - startFrame);
            this.fadeIn = Math.min(1440, len / 4);  // 30 ms smooth cosine fade-in
            this.fadeOut = Math.min(2400, len / 3); // 50 ms smooth cosine fade-out
            this.gainL = gainL;
            this.gainR = gainR;
        }
    }

    private static @Nullable ActiveVoice prepareActiveVoice(VoiceSegment seg, long startFrame, long endFrame) {
        short[] raw48k = seg.pcm48kSupplier() == null ? null : seg.pcm48kSupplier().get();
        if (raw48k == null || raw48k.length == 0) return null;

        int targetFrames = (int) Math.min(Integer.MAX_VALUE, endFrame - startFrame);
        if (targetFrames <= 0) return null;

        float[] prepared;
        if (seg.variableSourceSecAtLocalSec() != null) {
            float[] srcFloat = toFloatNormalized(raw48k);
            DoubleUnaryOperator map = seg.variableSourceSecAtLocalSec();
            prepared = ReplayWsolaTimeStretch.stretchVariableRate(
                    srcFloat, SAMPLE_RATE, targetFrames,
                    outIdx -> map.applyAsDouble(outIdx / (double) SAMPLE_RATE) * SAMPLE_RATE
            );
        } else {
            int srcStart = Math.clamp((int) Math.round(seg.sourceOffsetSec() * SAMPLE_RATE), 0, raw48k.length);
            int srcAvail = raw48k.length - srcStart;
            if (srcAvail <= 0) return null;

            double speed = ReplayWsolaTimeStretch.clampSpeed(seg.playbackSpeed());
            int neededSrc = Math.min(srcAvail, (int) Math.ceil(targetFrames * speed) + 2048);
            if (seg.preservePitchWsola() && Math.abs(speed - 1.0) > 1.0e-3) {
                float[] slice = new float[neededSrc];
                for (int i = 0; i < neededSrc; i++) slice[i] = raw48k[srcStart + i] / 32768.0f;
                prepared = ReplayWsolaTimeStretch.stretchMonoFloat(slice, SAMPLE_RATE, speed);
            } else {
                int copyLen = Math.min(targetFrames, srcAvail);
                prepared = new float[copyLen];
                for (int i = 0; i < copyLen; i++) prepared[i] = raw48k[srcStart + i] / 32768.0f;
            }
        }
        if (prepared.length == 0) return null;

        long actualEnd = Math.min(endFrame, startFrame + prepared.length);
        double panAngle = (Math.clamp(seg.pan(), -1f, 1f) + 1.0) * (Math.PI * 0.25);
        float gainL = (float) (Math.cos(panAngle) * Math.sqrt(2.0) * seg.gain());
        float gainR = (float) (Math.sin(panAngle) * Math.sqrt(2.0) * seg.gain());
        return new ActiveVoice(prepared, startFrame, actualEnd, gainL, gainR);
    }

    private static void mixActiveVoice(float[] mixL, float[] mixR, long chunkOffset, int chunkCount,
                                       ActiveVoice voice, float busVolume) {
        long dstStart = Math.max(chunkOffset, voice.startFrame);
        long dstEnd = Math.min(chunkOffset + chunkCount, voice.endFrame);
        if (dstEnd <= dstStart) return;

        int totalLen = (int) (voice.endFrame - voice.startFrame);
        float gL = voice.gainL * busVolume;
        float gR = voice.gainR * busVolume;
        float[] src = voice.samples;

        for (long dst = dstStart; dst < dstEnd; dst++) {
            int i = (int) (dst - voice.startFrame);
            if (i < 0 || i >= src.length) continue;
            float env = 1.0f;
            if (i < voice.fadeIn && voice.fadeIn > 0) {
                env = (float) (0.5 * (1.0 - Math.cos(Math.PI * i / voice.fadeIn)));
            } else if (i >= totalLen - voice.fadeOut && voice.fadeOut > 0) {
                int rem = totalLen - 1 - i;
                env = (float) (0.5 * (1.0 - Math.cos(Math.PI * rem / voice.fadeOut)));
            }
            float sample = src[i] * env;
            int k = (int) (dst - chunkOffset);
            mixL[k] += sample * gL;
            mixR[k] += sample * gR;
        }
    }

    private static @Nullable DecodedSfx resolveCached(LinkedHashMap<List<String>, Optional<DecodedSfx>> cache,
                                                      List<String> keys, SfxResolver resolver) {
        Optional<DecodedSfx> existing = cache.get(keys);
        if (existing != null) return existing.orElse(null);
        return resolver.resolve(keys);
    }

    private static long maintainCacheBudget(LinkedHashMap<List<String>, Optional<DecodedSfx>> cache,
                                            long currentBytes, List<String> keys, @Nullable DecodedSfx asset) {
        if (cache.containsKey(keys)) return currentBytes;
        long bytes = asset == null ? 0L : (long) asset.samples().length * Float.BYTES;
        if (bytes > MAX_SOUND_CACHE_BYTES) return currentBytes;
        while ((cache.size() >= MAX_SOUND_CACHE_ENTRIES || currentBytes + bytes > MAX_SOUND_CACHE_BYTES) && !cache.isEmpty()) {
            var evicted = cache.pollFirstEntry();
            if (evicted != null && evicted.getValue().isPresent()) {
                currentBytes -= (long) evicted.getValue().get().samples().length * Float.BYTES;
            }
        }
        cache.put(keys, Optional.ofNullable(asset));
        return currentBytes + bytes;
    }

    private static float[] toFloatNormalized(short[] pcm) {
        float[] out = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) out[i] = pcm[i] / 32768.0f;
        return out;
    }

    private static float softLimitSfx(float x) {
        float abs = Math.abs(x);
        if (abs <= 0.65f) return x;
        return (float) (Math.signum(x) * (0.65 + 0.25 * Math.tanh((abs - 0.65) / 0.45)));
    }

    private static float softLimit(float x) {
        float abs = Math.abs(x);
        if (abs <= 0.78f) return x;
        return (float) (Math.signum(x) * (0.78 + 0.14 * Math.tanh((abs - 0.78) / 0.35)));
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
