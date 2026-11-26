package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.replay.ReplaySoundCuePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVoiceAudibilityResolver;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.replay.ReplayWsolaTimeStretch;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Multi-speaker concurrent spatial audio &amp; game-sound playback pool for both live 3D
 * {@code SettlementSummaryBoard} linear sessions and {@link ReplayHistoryScreen} previews.
 *
 * <p>Replaces the legacy single-{@code activeVoice} slot that truncated overlapping speakers:
 * up to {@link #MAX_CONCURRENT_VOICES} simultaneous utterances play concurrently through OpenAL,
 * filtered via {@link ReplayVoiceAudibilityResolver} when following a player and pitch-preserved
 * via {@link ReplayWsolaTimeStretch} at {@code 0.5x / 2x / 4x} playback speeds.</p>
 */
public final class ReplayAudioPlaybackController {
    static final float REPLAY_GAIN = 0.60f;
    static final int MAX_CONCURRENT_VOICES = 12;
    private static final int MAX_PLAYING_EFFECTS = 24;
    private static final ResourceLocation BOARD_VOICE_ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "replay_voice");
    private static final ResourceLocation REPLAY_EFFECT_ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "replay_effect");
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double MS_PER_TICK = 50.0;
    private static final int VOICE_RATE = ReplayVoiceData.VOICECHAT_SAMPLE_RATE;
    private static final int NATIVE_VOICE_RATE = ReplayVoiceData.SAMPLE_RATE;
    private static final int PCM_CACHE_MAX_ENTRIES = 96;
    private static final long PCM_CACHE_MAX_BYTES = 24L * 1024L * 1024L;
    private static final int PCM_CACHE_MAX_ENTRY_BYTES = 3 * 1024 * 1024;
    private static final long SCRUB_COOLDOWN_NS = 90_000_000L;
    private static final ThreadPoolExecutor PCM_WORKERS = pcmWorkers();
    private static final LinkedHashMap<PcmCacheKey, byte[]> PCM_CACHE = new LinkedHashMap<>(32, 0.75f, true);
    private static long pcmCacheBytes;

    private static final Map<BlockPos, PlaybackPool> BOARDS = new HashMap<>();
    private static @Nullable PlaybackPool history;

    private ReplayAudioPlaybackController() {}

    private static ThreadPoolExecutor pcmWorkers() {
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 4);
        var executor = new ThreadPoolExecutor(threads, threads, 15, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_CONCURRENT_VOICES * 4), task -> {
                    Thread worker = new Thread(task, "HabiTrain replay audio");
                    worker.setDaemon(true);
                    return worker;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class PcmCacheKey {
        final Object source;
        final int startSample;
        final int maxSamples;
        final int speedCentis;
        final int hash;

        PcmCacheKey(Object source, int startSample, int maxSamples, int speedCentis) {
            this.source = source;
            this.startSample = startSample;
            this.maxSamples = maxSamples;
            this.speedCentis = speedCentis;
            int h = System.identityHashCode(source);
            h = 31 * h + startSample;
            h = 31 * h + maxSamples;
            h = 31 * h + speedCentis;
            this.hash = h;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof PcmCacheKey other
                    && source == other.source
                    && startSample == other.startSample
                    && maxSamples == other.maxSamples
                    && speedCentis == other.speedCentis);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static byte @Nullable [] getCachedPcm(PcmCacheKey key) {
        synchronized (PCM_CACHE) {
            return PCM_CACHE.get(key);
        }
    }

    private static void putCachedPcm(PcmCacheKey key, byte[] pcm) {
        if (pcm.length == 0 || pcm.length > PCM_CACHE_MAX_ENTRY_BYTES) return;
        synchronized (PCM_CACHE) {
            byte[] previous = PCM_CACHE.put(key, pcm);
            if (previous != null) pcmCacheBytes -= previous.length;
            pcmCacheBytes += pcm.length;
            while (PCM_CACHE.size() > PCM_CACHE_MAX_ENTRIES || pcmCacheBytes > PCM_CACHE_MAX_BYTES) {
                var evicted = PCM_CACHE.pollFirstEntry();
                if (evicted == null) break;
                pcmCacheBytes -= evicted.getValue().length;
            }
        }
    }

    static void clearPcmCache() {
        synchronized (PCM_CACHE) {
            PCM_CACHE.clear();
            pcmCacheBytes = 0L;
        }
    }

    private record ActiveVoice(Object key, @Nullable UUID speakerId, float gain, boolean isSelf,
                               VoiceClipSoundInstance instance) {}

    record PendingVoice(Object key, @Nullable UUID speakerId, float gain, boolean isSelf,
                        Vec3 position, boolean relative, double startTick, double endTick,
                        long sliceStartMs, FutureTask<byte[]> pcm) {
        void cancel() {
            pcm.cancel(true);
            PCM_WORKERS.remove(pcm);
        }
    }

    static final class PlaybackPool {
        @Nullable UUID roundId;
        @Nullable LastMatchReplay replay;
        ReplayVoiceData voice = ReplayVoiceData.EMPTY;
        @Nullable ReplayVoiceAudibilityResolver.Context audibility;
        ReplaySoundCuePlan.Schedule legacySchedule = ReplaySoundCuePlan.Schedule.EMPTY;
        double prevMatchTick = -1.0;
        float prevSpeed = 1.0f;
        @Nullable UUID prevFollow;
        long lastDiscontinuousNs;
        int nextLegacySoundIdx;
        int nextLegacyVoiceIdx;
        final ArrayDeque<ReplayEffectSound> effects = new ArrayDeque<>();
        final List<ActiveVoice> activeVoices = new ArrayList<>();
        final List<PendingVoice> pendingVoices = new ArrayList<>();
        final Map<ReplayVoiceData.VoiceUtterance, Long> startedUtterances = new IdentityHashMap<>();

        void ensureLoaded(Minecraft mc, LastMatchReplay nextReplay, ReplayVoiceData nextVoice) {
            boolean newRound = !nextReplay.roundId().equals(roundId) || replay != nextReplay;
            boolean newVoice = voice != nextVoice;
            if (!newRound && !newVoice) return;
            if (newRound) {
                stopAll(mc);
                roundId = nextReplay.roundId();
                replay = nextReplay;
                prevMatchTick = -1.0;
            } else {
                clearPendingVoices();
            }
            voice = nextVoice;
            audibility = ReplayVoiceAudibilityResolver.Context.of(nextReplay, nextVoice);
            if (nextReplay.gameSounds().isEmpty() || !nextVoice.hasMultitrack()) {
                legacySchedule = ReplaySoundCuePlan.forLinearReplay(nextReplay, nextVoice,
                        file -> mc.getResourceManager().getResource(file).isPresent());
            } else {
                legacySchedule = ReplaySoundCuePlan.Schedule.EMPTY;
            }
            if (prevMatchTick >= 0) {
                nextLegacySoundIdx = firstSoundAfter(legacySchedule.sounds(), prevMatchTick);
                nextLegacyVoiceIdx = firstVoiceAtOrAfter(legacySchedule.voices(), prevMatchTick);
            } else {
                nextLegacySoundIdx = 0;
                nextLegacyVoiceIdx = 0;
            }
        }

        void pruneFinished(Minecraft mc) {
            SoundManager manager = mc.getSoundManager();
            Iterator<ActiveVoice> it = activeVoices.iterator();
            while (it.hasNext()) {
                ActiveVoice v = it.next();
                if (v.instance().isStopped() || !manager.isActive(v.instance())) {
                    it.remove();
                }
            }
            effects.removeIf(e -> !manager.isActive(e));
        }

        void stopAll(@Nullable Minecraft mc) {
            clearPendingVoices();
            for (ActiveVoice v : activeVoices) {
                v.instance().stopClip();
                if (mc != null && mc.getSoundManager() != null) {
                    mc.getSoundManager().stop(v.instance());
                }
            }
            activeVoices.clear();
            startedUtterances.clear();
            stopEffects(mc, effects);
        }

        void clearPendingVoices() {
            pendingVoices.forEach(PendingVoice::cancel);
            pendingVoices.clear();
        }

        boolean isPlayingKey(Object key) {
            for (ActiveVoice v : activeVoices) {
                if (v.key() == key) return true;
            }
            for (PendingVoice voice : pendingVoices) {
                if (voice.key() == key) return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ 3D Replay Table entry points

    /** Legacy compatibility entrypoint (delegates to linear session playback at 1.0x global view). */
    static void updateBoard(Minecraft mc, BlockPos pos, Vec3 cam, ReplayBoardSite site,
                            ReplayBoardModel model, ClientReplayBoard.View view,
                            boolean active, double elapsedTicks) {
        if (model == null) return;
        double matchTick = model.timeline != null ? model.timeline.atElapsed(elapsedTicks).replayTick() : elapsedTicks;
        updateBoardSession(mc, pos, cam, site, model, view, active, matchTick, 1.0f, null);
    }

    /**
     * Drives synchronized multi-track spatial voices and server-recorded game sounds for one active
     * 3D replay table session at linear {@code matchTick} and {@code speed} ({@code 0.5x/1x/2x/4x}).
     *
     * <p>When {@code followPlayerUuid != null}, audio is automatically filtered through
     * {@link ReplayVoiceAudibilityResolver} to the followed player's own voice + audible peers +
     * audible game sounds in their hearing radius.</p>
     */
    static void updateBoardSession(Minecraft mc, BlockPos pos, Vec3 cam, ReplayBoardSite site,
                                   ReplayBoardModel model, ClientReplayBoard.View view,
                                   boolean playing, double matchTick, float speed,
                                   @Nullable UUID followPlayerUuid) {
        if (mc == null || mc.getSoundManager() == null || model == null || view == null) return;
        int duration = Math.max(0, model.replay.durationTicks());
        if (!playing || matchTick < 0 || matchTick >= duration + 20.0) {
            PlaybackPool existing = BOARDS.get(pos);
            if (existing != null) {
                existing.stopAll(mc);
                existing.prevMatchTick = -1.0;
            }
            return;
        }
        PlaybackPool pool = BOARDS.computeIfAbsent(pos.immutable(), k -> new PlaybackPool());
        ReplayVoiceData currentVoice = view.voice() == null ? ReplayVoiceData.EMPTY : view.voice();
        float fade = Math.clamp(site.fade(), 0f, 1f);
        stepPool(mc, pool, model.replay, currentVoice, matchTick, speed, followPlayerUuid, fade, false, cam, site, model);
    }

    static void endBoardFrame(Minecraft mc, Set<BlockPos> activeBoards) {
        if (BOARDS.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, PlaybackPool>> it = BOARDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, PlaybackPool> entry = it.next();
            if (!activeBoards.contains(entry.getKey())) {
                entry.getValue().stopAll(mc);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ History Screen entry points

    /** Legacy 1.0x global history audio entrypoint (kept for binary/source compatibility). */
    static void updateHistory(Minecraft mc, @Nullable UUID roundId, @Nullable LastMatchReplay replay,
                              @Nullable ReplayVoiceData voice, boolean playing, double cursorTicks) {
        updateHistory(mc, roundId, replay, voice, playing, cursorTicks, 1.0f, null);
    }

    /** Full linear history audio entrypoint supporting speed ({@code 0.5x..4x}) and followed-player audibility filter. */
    static void updateHistory(Minecraft mc, @Nullable UUID roundId, @Nullable LastMatchReplay replay,
                              @Nullable ReplayVoiceData voice, boolean playing, double cursorTicks,
                              float speed, @Nullable UUID followPlayerUuid) {
        if (mc == null || mc.getSoundManager() == null) return;
        if (!playing || roundId == null || replay == null) {
            if (history != null) {
                history.stopAll(mc);
                history.prevMatchTick = -1.0;
            }
            return;
        }
        if (history == null || !roundId.equals(history.roundId)) {
            stopHistory(mc);
            history = new PlaybackPool();
        }
        ReplayVoiceData currentVoice = voice == null ? ReplayVoiceData.EMPTY : voice;
        stepPool(mc, history, replay, currentVoice, cursorTicks, speed, followPlayerUuid, 0.95f, true, Vec3.ZERO, null, null);
    }

    static void onHistoryScrub() {
        if (history != null) {
            history.stopAll(Minecraft.getInstance());
            history.prevMatchTick = -1.0;
        }
    }

    static void stopHistory(Minecraft mc) {
        if (history != null) {
            history.stopAll(mc);
            history = null;
        }
    }

    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        for (PlaybackPool pool : BOARDS.values()) {
            pool.stopAll(mc);
        }
        BOARDS.clear();
        stopHistory(mc);
        clearPcmCache();
    }

    static boolean isBoardVoiceActive(Minecraft mc, BlockPos pos) {
        PlaybackPool pool = BOARDS.get(pos);
        if (pool == null || pool.activeVoices.isEmpty()) return false;
        SoundManager mgr = mc.getSoundManager();
        for (ActiveVoice v : pool.activeVoices) {
            if (!v.instance().isStopped() && mgr.isActive(v.instance())) return true;
        }
        return false;
    }

    static int activeVoiceCount(BlockPos pos) {
        PlaybackPool pool = BOARDS.get(pos);
        return pool == null ? 0 : pool.activeVoices.size();
    }

    // ------------------------------------------------------------------ core multi-track pool engine

    private static void stepPool(Minecraft mc, PlaybackPool pool, LastMatchReplay replay,
                                 ReplayVoiceData currentVoice, double matchTick, float rawSpeed,
                                 @Nullable UUID followPlayerUuid, float masterFade, boolean headRelative,
                                 Vec3 cam, @Nullable ReplayBoardSite site, @Nullable ReplayBoardModel model) {
        pool.ensureLoaded(mc, replay, currentVoice);
        pool.pruneFinished(mc);

        float speed = Math.clamp(Float.isFinite(rawSpeed) && rawSpeed > 0f ? rawSpeed : 1.0f, 0.5f, 4.0f);
        boolean speedChanged = Math.abs(speed - pool.prevSpeed) > 0.01f;
        boolean followChanged = !Objects.equals(followPlayerUuid, pool.prevFollow);
        double maxForwardJump = Math.max(12.0, 14.0 * speed);
        boolean discontinuous = pool.prevMatchTick < 0
                || matchTick < pool.prevMatchTick - 1.5
                || matchTick - pool.prevMatchTick > maxForwardJump
                || speedChanged
                || followChanged;

        if (discontinuous) {
            long nowNs = System.nanoTime();
            boolean rapidScrub = pool.lastDiscontinuousNs != 0L && (nowNs - pool.lastDiscontinuousNs) < SCRUB_COOLDOWN_NS;
            pool.lastDiscontinuousNs = nowNs;
            pool.stopAll(mc);
            pool.prevSpeed = speed;
            pool.prevFollow = followPlayerUuid;
            pool.nextLegacySoundIdx = firstSoundAfter(pool.legacySchedule.sounds(), matchTick - 0.5);
            pool.nextLegacyVoiceIdx = firstVoiceAtOrAfter(pool.legacySchedule.voices(), matchTick);
            pool.prevMatchTick = Math.max(0.0, matchTick - 0.5);
            // Trigger utterances already in progress unless the user is actively rapid-scrubbing/cycling speed.
            if (!rapidScrub && masterFade > 0.02f && currentVoice.hasMultitrack() && pool.audibility != null) {
                long nowMs = Math.round(matchTick * MS_PER_TICK);
                launchAudibleVoices(pool, followPlayerUuid, nowMs, speed, masterFade, headRelative, cam, site, model, matchTick);
            }
            pool.prevMatchTick = matchTick;
            return;
        }

        if (masterFade <= 0.02f) {
            pool.clearPendingVoices();
            pool.prevMatchTick = matchTick;
            return;
        }

        playPreparedVoices(mc, pool, matchTick, speed);
        long prevMs = Math.round(pool.prevMatchTick * MS_PER_TICK);
        long nowMs = Math.round(matchTick * MS_PER_TICK);

        // 1. Server-recorded global game sounds (v2) OR fallback legacy event cues (v1 archives)
        if (!replay.gameSounds().isEmpty() && pool.audibility != null) {
            if (nowMs > prevMs) {
                List<ReplayVoiceAudibilityResolver.AudibleSound> sounds =
                        pool.audibility.resolveGameSoundsInWindow(followPlayerUuid, (int) (prevMs + 1), (int) nowMs);
                for (ReplayVoiceAudibilityResolver.AudibleSound sound : sounds) {
                    playRecordedGameSound(mc, pool, sound, masterFade, headRelative, followPlayerUuid != null, cam, site, model);
                }
            }
        } else {
            List<ReplaySoundCuePlan.SoundCue> legacySounds = pool.legacySchedule.sounds();
            while (pool.nextLegacySoundIdx < legacySounds.size()) {
                ReplaySoundCuePlan.SoundCue cue = legacySounds.get(pool.nextLegacySoundIdx);
                if (cue.time() > matchTick) break;
                if (cue.time() >= pool.prevMatchTick - 0.5) {
                    Vec3 emitter = headRelative || site == null || model == null
                            ? Vec3.ZERO : clampAudibleDistance(cam, boardEventWorldPos(cam, site, model, cue.eventIndex()), 14.0);
                    playLegacyEffect(mc, pool.effects, cue, Math.min(1.25f, cue.volume() * masterFade), emitter, headRelative);
                }
                pool.nextLegacySoundIdx++;
            }
        }

        // 2. Multi-speaker concurrent voice pool (HRVC v2) OR concurrent legacy v1 clips
        if (currentVoice.hasMultitrack() && pool.audibility != null) {
            if (nowMs >= prevMs) {
                launchAudibleVoices(pool, followPlayerUuid, nowMs, speed, masterFade, headRelative, cam, site, model, matchTick);
            }
        } else {
            List<ReplaySoundCuePlan.VoiceCue> legacyVoices = pool.legacySchedule.voices();
            while (pool.nextLegacyVoiceIdx < legacyVoices.size()) {
                ReplaySoundCuePlan.VoiceCue voiceCue = legacyVoices.get(pool.nextLegacyVoiceIdx);
                if (voiceCue.startTime() > matchTick) break;
                if (matchTick < voiceCue.endTime() - 1.0 && !pool.isPlayingKey(voiceCue.clip())) {
                    double skipSeconds = (matchTick - voiceCue.sampleStartTime(TICKS_PER_SECOND)) / TICKS_PER_SECOND;
                    double maxDuration = (voiceCue.endTime() - matchTick) / TICKS_PER_SECOND;
                    Vec3 emitter = headRelative || site == null || model == null
                            ? Vec3.ZERO : clampAudibleDistance(cam, boardEventWorldPos(cam, site, model, voiceCue.clip().eventIndex()), 14.0);
                    prepareVoice(pool, voiceCue.clip(), null, masterFade, false, emitter, headRelative,
                            matchTick, voiceCue.endTime(), -1L, () -> buildPcm48kSlice(voiceCue.clip(), skipSeconds, maxDuration, speed));
                }
                pool.nextLegacyVoiceIdx++;
            }
        }

        pool.prevMatchTick = matchTick;
        pool.prevSpeed = speed;
        pool.prevFollow = followPlayerUuid;
    }

    private static void launchAudibleVoices(PlaybackPool pool, @Nullable UUID listener, long nowMs,
                                            float speed, float masterFade, boolean headRelative, Vec3 cam,
                                            @Nullable ReplayBoardSite site, @Nullable ReplayBoardModel model, double matchTick) {
        for (var audible : pool.audibility.resolveVoices(listener, nowMs)) {
            if (pool.isPlayingKey(audible.utterance())) continue;
            var slice = pool.audibility.playbackSlice(audible, listener, nowMs);
            if (slice != null) {
                launchMultitrackVoice(pool, slice, nowMs, speed, masterFade, headRelative,
                        listener != null, cam, site, model, matchTick);
            }
        }
    }

    private static void launchMultitrackVoice(PlaybackPool pool,
                                              ReplayVoiceAudibilityResolver.VoiceSlice slice,
                                              long nowMs, float speed, float masterFade,
                                              boolean headRelative, boolean povFiltered,
                                              Vec3 cam, @Nullable ReplayBoardSite site,
                                              @Nullable ReplayBoardModel model, double matchTick) {
        var av = slice.voice();
        ReplayVoiceData.VoiceUtterance utterance = av.utterance();
        if (Objects.equals(pool.startedUtterances.get(utterance), slice.startMs())) return;
        double skipSec = Math.max(0.0, (nowMs - utterance.startMs()) / 1000.0);
        double remainSec = (slice.endMs() - nowMs) / 1000.0;
        if (remainSec < 0.08) return;

        float effectiveGain = Math.clamp(av.gain() * masterFade, 0f, 1.35f);
        if (effectiveGain < 0.02f) return;

        boolean useRelative;
        Vec3 emitter;
        if (headRelative || povFiltered || site == null || model == null) {
            // Stereo head-relative panning matching ReplayVoiceAudibilityResolver (self centered, peers panned L/R).
            useRelative = true;
            emitter = new Vec3(av.pan() * 2.2, 0.0, -1.0);
        } else {
            useRelative = false;
            Vec3 speakerWorld = boardActorWorldPos(cam, site, model, utterance.speakerId(), matchTick);
            emitter = clampAudibleDistance(cam, speakerWorld, 14.0);
        }
        double endTick = matchTick + remainSec * TICKS_PER_SECOND;
        // Defer startedUtterances registration until playback actually begins so cancelled or failed
        // background WSOLA tasks (e.g. stepping away briefly) can retry cleanly instead of staying muted.
        prepareVoice(pool, utterance, utterance.speakerId(), effectiveGain, av.isSelf(), emitter,
                useRelative, matchTick, endTick, slice.startMs(),
                () -> buildUtterancePcm48k(utterance, skipSec, remainSec, speed));
    }

    static boolean prepareVoice(PlaybackPool pool, Object key, @Nullable UUID speakerId,
                                float gain, boolean isSelf, Vec3 position, boolean relative,
                                double startTick, double endTick, long sliceStartMs, Callable<byte[]> build) {
        if (pool.pendingVoices.size() >= MAX_CONCURRENT_VOICES * 2) return false;
        var task = new FutureTask<>(build);
        try {
            PCM_WORKERS.execute(task);
        } catch (RejectedExecutionException busy) {
            return false;
        }
        pool.pendingVoices.add(new PendingVoice(key, speakerId, gain, isSelf, position, relative,
                startTick, endTick, sliceStartMs, task));
        return true;
    }

    static int skipBytesForDelay(double elapsedTicks, float speed) {
        if (!Double.isFinite(elapsedTicks) || elapsedTicks <= 0.0) return 0;
        double safeSpeed = Math.clamp(Float.isFinite(speed) && speed > 0f ? speed : 1.0f, 0.5f, 4.0f);
        long samples = Math.round(elapsedTicks * VOICE_RATE / (TICKS_PER_SECOND * safeSpeed));
        if (samples <= 0L) return 0;
        long bytes = samples * 2L;
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE & ~1 : (int) bytes;
    }

    private static void playPreparedVoices(Minecraft mc, PlaybackPool pool, double tick, float speed) {
        for (var iterator = pool.pendingVoices.iterator(); iterator.hasNext(); ) {
            var voice = iterator.next();
            if (tick >= voice.endTick()) {
                voice.cancel();
                iterator.remove();
                continue;
            }
            var state = voice.pcm().state();
            if (state == Future.State.RUNNING) continue;
            iterator.remove();
            if (state != Future.State.SUCCESS) continue;
            byte[] pcm = voice.pcm().resultNow();
            int skip = skipBytesForDelay(tick - voice.startTick(), speed);
            if (pcm.length - skip < VOICE_RATE / 10) continue;
            if (voice.sliceStartMs() >= 0L && voice.key() instanceof ReplayVoiceData.VoiceUtterance utterance) {
                pool.startedUtterances.put(utterance, voice.sliceStartMs());
            }
            enqueueVoice(mc, pool, voice.key(), voice.speakerId(), voice.gain(), voice.isSelf(),
                    pcm, skip, voice.position(), voice.relative());
        }
    }

    /**
     * Pure voice-pool arbitration helper: returns {@code -2} when under capacity (accept without
     * cutting any active speaker), {@code -1} when the incoming stream should be dropped, or a
     * {@code >= 0} slot index to evict (preserving self-voice priority over peer streams).
     */
    static int selectVoiceEvictionIndex(float[] existingGains, boolean[] existingSelf,
                                        float incomingGain, boolean incomingSelf, int capacity) {
        int active = Math.min(existingGains.length, existingSelf.length);
        if (active < capacity) return -2;
        int peerVictimIdx = -1;
        float lowestPeerGain = Float.MAX_VALUE;
        int selfVictimIdx = -1;
        float lowestSelfGain = Float.MAX_VALUE;
        for (int i = 0; i < active; i++) {
            if (existingSelf[i]) {
                if (existingGains[i] < lowestSelfGain) {
                    lowestSelfGain = existingGains[i];
                    selfVictimIdx = i;
                }
            } else if (existingGains[i] < lowestPeerGain) {
                lowestPeerGain = existingGains[i];
                peerVictimIdx = i;
            }
        }
        if (incomingSelf) {
            return peerVictimIdx >= 0 ? peerVictimIdx : selfVictimIdx;
        }
        if (peerVictimIdx < 0 || incomingGain <= lowestPeerGain) return -1;
        return peerVictimIdx;
    }

    private static void enqueueVoice(Minecraft mc, PlaybackPool pool, Object key, @Nullable UUID speakerId,
                                     float gain, boolean isSelf, byte[] pcm, int offset, Vec3 pos, boolean relative) {
        int size = pool.activeVoices.size();
        if (size >= MAX_CONCURRENT_VOICES) {
            float[] gains = new float[size];
            boolean[] selfFlags = new boolean[size];
            for (int i = 0; i < size; i++) {
                ActiveVoice existing = pool.activeVoices.get(i);
                gains[i] = existing.gain();
                selfFlags[i] = existing.isSelf();
            }
            int victimIdx = selectVoiceEvictionIndex(gains, selfFlags, gain, isSelf, MAX_CONCURRENT_VOICES);
            if (victimIdx < 0) return;
            ActiveVoice evicted = pool.activeVoices.remove(victimIdx);
            evicted.instance().stopClip();
            mc.getSoundManager().stop(evicted.instance());
        }
        VoiceClipSoundInstance instance = new VoiceClipSoundInstance(pcm, offset, gain, pos.x, pos.y, pos.z, relative);
        pool.activeVoices.add(new ActiveVoice(key, speakerId, gain, isSelf, instance));
        mc.getSoundManager().play(instance);
    }

    private static void playRecordedGameSound(Minecraft mc, PlaybackPool pool,
                                              ReplayVoiceAudibilityResolver.AudibleSound sound,
                                              float masterFade, boolean headRelative, boolean povFiltered,
                                              Vec3 cam, @Nullable ReplayBoardSite site, @Nullable ReplayBoardModel model) {
        ResourceLocation id = ResourceLocation.tryParse(sound.soundId());
        if (id == null || mc.getSoundManager().getSoundEvent(id) == null) return;
        float volume = Math.clamp(sound.gain() * masterFade, 0f, 1.35f);
        if (volume < 0.02f) return;

        boolean useRelative;
        Vec3 emitter;
        if (headRelative || povFiltered || site == null || model == null
                || sound.cue().audibilityScope() == ReplayGameSoundData.AudibilityScope.GLOBAL) {
            useRelative = true;
            emitter = new Vec3(sound.pan() * 2.0, 0.0, -1.0);
        } else {
            useRelative = false;
            Vec3 world = boardPointWorldPos(cam, site, model,
                    sound.cue().blockX(), sound.cue().blockY(), sound.cue().blockZ());
            emitter = clampAudibleDistance(cam, world, 14.0);
        }

        SoundManager manager = mc.getSoundManager();
        if (pool.effects.size() >= MAX_PLAYING_EFFECTS) {
            manager.stop(pool.effects.removeFirst());
        }
        ReplayEffectSound instance = new ReplayEffectSound(id, null, volume, sound.cue().pitch(), emitter, useRelative);
        pool.effects.addLast(instance);
        manager.play(instance);
    }

    // ------------------------------------------------------------------ PCM & WSOLA helpers

    static byte[] buildPcm48kSlice(ReplayVoiceData.Clip clip, double skipSeconds, double maxDurationSeconds) {
        return buildPcm48kSlice(clip, skipSeconds, maxDurationSeconds, 1.0);
    }

    static byte[] buildPcm48kSlice(ReplayVoiceData.Clip clip, double skipSeconds,
                                    double maxDurationSeconds, double speed) {
        return buildPcm48kCached24k(clip, clip.sampleCount(), clip::decode24k, skipSeconds, maxDurationSeconds, speed);
    }

    static byte[] buildUtterancePcm48k(ReplayVoiceData.VoiceUtterance utterance, double skipSeconds,
                                       double maxDurationSeconds, double speed) {
        return buildPcm48kCached24k(utterance, utterance.sampleCount24k(), utterance::decode24k, skipSeconds, maxDurationSeconds, speed);
    }

    /**
     * Runs WSOLA at the native 24 kHz voice rate before 2x linear upsampling to 48 kHz OpenAL PCM,
     * cutting WSOLA window and search work by ~4x with equivalent vocal bandwidth.
     */
    private static byte[] buildPcm48kCached24k(Object source, int expected24kSamples,
                                               java.util.function.Supplier<short[]> decoder24k,
                                               double skipSeconds, double maxDurationSeconds, double speed) {
        if (expected24kSamples <= 0) return new byte[0];
        int start = Math.clamp((int) Math.round(skipSeconds * NATIVE_VOICE_RATE), 0, expected24kSamples);
        int maxCount = Math.clamp((int) Math.round(maxDurationSeconds * NATIVE_VOICE_RATE), 0, expected24kSamples - start);
        if (maxCount < NATIVE_VOICE_RATE / 15) return new byte[0];
        double clampedSpeed = ReplayWsolaTimeStretch.clampSpeed(speed);
        int speedCentis = (int) Math.round(clampedSpeed * 100.0);
        var cacheKey = new PcmCacheKey(source, start, maxCount, speedCentis);
        byte[] cached = getCachedPcm(cacheKey);
        if (cached != null) return cached;
        byte[] built = buildPcm48kFromSamples24k(decoder24k.get(), start, maxCount, clampedSpeed);
        if (built.length > 0 && !Thread.currentThread().isInterrupted()) {
            putCachedPcm(cacheKey, built);
        }
        return built;
    }

    private static byte[] buildPcm48kFromSamples24k(short[] samples24k, int start, int maxCount, double speed) {
        if (samples24k.length == 0 || start >= samples24k.length) return new byte[0];
        int avail = Math.min(maxCount, samples24k.length - start);
        if (avail < NATIVE_VOICE_RATE / 15) return new byte[0];

        short[] stretched24k;
        if (Math.abs(speed - 1.0) > 0.02) {
            stretched24k = ReplayWsolaTimeStretch.stretchMonoRange(samples24k, start, avail, NATIVE_VOICE_RATE, speed);
        } else {
            stretched24k = Arrays.copyOfRange(samples24k, start, start + avail);
        }
        if (stretched24k.length < NATIVE_VOICE_RATE / 20 || Thread.currentThread().isInterrupted()) return new byte[0];
        short[] processed = ReplayVoiceData.upsample24kTo48k(stretched24k);

        int count = processed.length;
        int fadeIn = Math.min(VOICE_RATE / 30, count / 4);   // ~33 ms
        int fadeOut = Math.min(VOICE_RATE / 16, count / 3);  // ~62 ms
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            double env = 1.0;
            if (i < fadeIn && fadeIn > 0) {
                env = 0.5 * (1.0 - Math.cos(Math.PI * i / fadeIn));
            } else if (i >= count - fadeOut && fadeOut > 0) {
                int rem = count - 1 - i;
                env = 0.5 * (1.0 - Math.cos(Math.PI * rem / fadeOut));
            }
            short v = (short) Math.clamp(Math.round(processed[i] * env), Short.MIN_VALUE, Short.MAX_VALUE);
            bytes[i * 2] = (byte) (v & 0xFF);
            bytes[i * 2 + 1] = (byte) ((v >>> 8) & 0xFF);
        }
        return bytes;
    }

    // ------------------------------------------------------------------ sound & spatial utilities

    static @Nullable ResourceLocation resolveSound(Minecraft mc, List<String> candidates) {
        SoundManager manager = mc.getSoundManager();
        for (String candidate : candidates) {
            ResourceLocation id = ResourceLocation.tryParse(candidate);
            if (id != null && manager.getSoundEvent(id) != null) return id;
        }
        return null;
    }

    private static void playLegacyEffect(Minecraft mc, ArrayDeque<ReplayEffectSound> effects,
                                         ReplaySoundCuePlan.SoundCue cue, float volume, Vec3 position, boolean relative) {
        ResourceLocation file = cue.recordedFile();
        ResourceLocation id = file == null ? resolveSound(mc, cue.candidates()) : REPLAY_EFFECT_ID;
        if (id == null || file != null && mc.getResourceManager().getResource(file).isEmpty()) return;
        SoundManager manager = mc.getSoundManager();
        effects.removeIf(effect -> !manager.isActive(effect));
        if (effects.size() >= MAX_PLAYING_EFFECTS) manager.stop(effects.removeFirst());
        var effect = new ReplayEffectSound(id, file, volume, cue.pitch(), position, relative);
        effects.addLast(effect);
        manager.play(effect);
    }

    private static void stopEffects(@Nullable Minecraft mc, ArrayDeque<ReplayEffectSound> effects) {
        if (mc != null && !effects.isEmpty()) effects.forEach(mc.getSoundManager()::stop);
        effects.clear();
    }

    private static Vec3 boardActorWorldPos(Vec3 cam, ReplayBoardSite site, ReplayBoardModel model,
                                           UUID speakerId, double matchTick) {
        ReplayBoardModel.Actor actor = model.participant(speakerId);
        if (actor != null) {
            ReplayTrackSampler.Pose pose = actor.track.sample(matchTick);
            if (pose.visible()) {
                return boardPointWorldPos(cam, site, model, pose.x(), pose.y(), pose.z());
            }
        }
        return new Vec3(site.pos().getX() + 0.5, site.pos().getY() + 1.35, site.pos().getZ() + 0.5);
    }

    private static Vec3 boardEventWorldPos(Vec3 cam, ReplayBoardSite site, ReplayBoardModel model, int eventIndex) {
        if (eventIndex >= 0 && eventIndex < model.replay.events().size()) {
            LastMatchReplay.Node ev = model.replay.events().get(eventIndex);
            LastMatchReplay.Position pos = ev.to() != null ? ev.to() : ev.from();
            if (pos != null) {
                return boardPointWorldPos(cam, site, model, pos.blockX(), pos.blockY(), pos.blockZ());
            }
        }
        return new Vec3(site.pos().getX() + 0.5, site.pos().getY() + 1.35, site.pos().getZ() + 0.5);
    }

    private static Vec3 boardPointWorldPos(Vec3 cam, ReplayBoardSite site, ReplayBoardModel model,
                                           double bx, double by, double bz) {
        float[] projected = new float[3];
        model.project(bx, by, bz, projected);
        Vector3f rel = site.board().transformPosition(
                projected[0], ReplayBoardSite.MAP_BASE + projected[1], projected[2], new Vector3f());
        return new Vec3(cam.x + rel.x, cam.y + rel.y, cam.z + rel.z);
    }

    private static Vec3 clampAudibleDistance(Vec3 cam, Vec3 emitter, double maxDist) {
        Vec3 delta = emitter.subtract(cam);
        double distSq = delta.lengthSqr();
        if (distSq <= maxDist * maxDist || distSq < 1e-6) return emitter;
        return cam.add(delta.scale(maxDist / Math.sqrt(distSq)));
    }

    private static int firstSoundAfter(List<ReplaySoundCuePlan.SoundCue> cues, double tick) {
        for (int i = 0; i < cues.size(); i++) {
            if (cues.get(i).time() >= tick) return i;
        }
        return cues.size();
    }

    private static int firstVoiceAtOrAfter(List<ReplaySoundCuePlan.VoiceCue> cues, double tick) {
        for (int i = 0; i < cues.size(); i++) {
            if (cues.get(i).endTime() > tick + 1.0) return i;
        }
        return cues.size();
    }

    // ------------------------------------------------------------------ OpenAL sound instances

    static final class ReplayEffectSound extends SimpleSoundInstance {
        private final @Nullable Sound recorded;

        ReplayEffectSound(ResourceLocation id, @Nullable ResourceLocation file, float volume, float pitch,
                          Vec3 position, boolean relative) {
            super(id, SoundSource.PLAYERS, volume * REPLAY_GAIN, pitch, SoundInstance.createUnseededRandom(),
                    false, 0, relative ? Attenuation.NONE : Attenuation.LINEAR,
                    position.x, position.y, position.z, relative);
            recorded = file == null ? null : new Sound(file.withPath(path -> path.substring(7, path.length() - 4)),
                    ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, false, false, 16);
        }

        @Override public WeighedSoundEvents resolve(SoundManager manager) {
            if (recorded == null) return super.resolve(manager);
            this.sound = recorded;
            return new WeighedSoundEvents(this.location, null);
        }
    }

    /**
     * Streaming 48 kHz mono 16-bit PCM {@link SoundInstance} backed by Fabric's {@link FabricSoundInstance}
     * hook so server-recorded ADPCM clips &amp; WSOLA time-stretched voices play natively through OpenAL.
     */
    static final class VoiceClipSoundInstance extends AbstractTickableSoundInstance {
        private final byte[] pcmBytes;
        private final int offset;
        private final int maxTicks;
        private int ticksAlive;

        VoiceClipSoundInstance(byte[] pcmBytes, int offset, float volume, double x, double y, double z, boolean relative) {
            super(SoundEvent.createFixedRangeEvent(BOARD_VOICE_ID, 32.0F), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.pcmBytes = pcmBytes;
            this.offset = offset;
            this.volume = Math.clamp(volume, 0f, 1.5f) * REPLAY_GAIN;
            this.pitch = 1.0f;
            this.x = x;
            this.y = y;
            this.z = z;
            this.looping = false;
            this.delay = 0;
            this.relative = relative;
            this.attenuation = relative ? Attenuation.NONE : Attenuation.LINEAR;
            this.maxTicks = (int) Math.ceil((pcmBytes.length - offset) / (VOICE_RATE * 2.0) * TICKS_PER_SECOND) + 30;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            this.sound = new Sound(
                    FabricSoundInstance.EMPTY_SOUND,
                    ConstantFloat.of(1.0F),
                    ConstantFloat.of(1.0F),
                    1,
                    Sound.Type.FILE,
                    true,
                    false,
                    32);
            return new WeighedSoundEvents(this.location, null);
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id,
                                                             boolean repeatInstantly) {
            return CompletableFuture.completedFuture(new PcmByteAudioStream(pcmBytes, offset));
        }

        @Override
        public void tick() {
            if (++ticksAlive >= maxTicks) {
                this.stop();
            }
        }

        void stopClip() {
            this.stop();
        }
    }

    static final class PcmByteAudioStream implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(VOICE_RATE, 16, 1, true, false);
        static final int RESUME_FADE_BYTES = VOICE_RATE / 100;
        private final byte[] pcm;
        private final int fadeEnd;
        private int cursor;

        PcmByteAudioStream(byte[] pcm, int offset) {
            this.pcm = pcm;
            this.cursor = offset;
            this.fadeEnd = offset == 0 ? 0 : offset + RESUME_FADE_BYTES;
        }

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public @Nullable ByteBuffer read(int size) throws IOException {
            if (cursor >= pcm.length || size <= 0) {
                return null;
            }
            int aligned = Math.min(size & ~1, pcm.length - cursor);
            if (aligned <= 0) {
                cursor = pcm.length;
                return null;
            }
            ByteBuffer direct = BufferUtils.createByteBuffer(aligned);
            direct.put(pcm, cursor, aligned);
            int fadeBytes = Math.min(aligned, fadeEnd - cursor);
            for (int index = 0; index < fadeBytes; index += 2) {
                int sample = (short) ((pcm[cursor + index] & 0xFF) | (pcm[cursor + index + 1] << 8));
                int scaled = sample * (RESUME_FADE_BYTES - fadeEnd + cursor + index) / RESUME_FADE_BYTES;
                direct.put(index, (byte) scaled);
                direct.put(index + 1, (byte) (scaled >> 8));
            }
            direct.flip();
            cursor += aligned;
            return direct;
        }

        @Override
        public void close() {}
    }
}
