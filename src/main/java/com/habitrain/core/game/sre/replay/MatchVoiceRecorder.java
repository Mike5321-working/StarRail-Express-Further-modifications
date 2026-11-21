package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.game.sre.mode.RepairModeManager;
import com.habitrain.core.scene.replay.ReplaySoundCuePlan;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Server-side rolling proximity voice recorder for match replays.
 *
 * <p>Kept completely free of Simple Voice Chat and client classes so dedicated servers without
 * {@code voicechat} can load {@link MatchReplayRecorder} safely. The optional {@link ReplayVoicePlugin}
 * feeds decoded 48 kHz microphone PCM frames on Simple Voice Chat's processing thread, while the
 * server thread records participant positions and preserves audio near selected highlights.
 * Ordinary speech rolls out of the buffer as the match advances.</p>
 */
public final class MatchVoiceRecorder {
    private static final int PRE_EVENT_MS = 2000;
    private static final int POST_EVENT_MS = 1000;
    private static final int WINDOW_MS = PRE_EVENT_MS + POST_EVENT_MS;
    private static final int WINDOW_SAMPLES_24K = (WINDOW_MS * ReplayVoiceData.SAMPLE_RATE) / 1000;
    private static final int DEATH_GRACE_MS = 150;
    private static final int WINDOW_CHECK_TICKS = 20;
    private static final int FRAME_GRACE_MS = 200;
    private static final int HISTORY_MS = WINDOW_MS + WINDOW_CHECK_TICKS * 50 + 2 * FRAME_GRACE_MS;
    private static final int MAX_FRAMES_PER_SPEAKER = 320;
    private static final int SILENCE_THRESHOLD = 260;
    private static final int PAD_SAMPLES_24K = (40 * ReplayVoiceData.SAMPLE_RATE) / 1000;
    private static final int FADE_SAMPLES_24K = (30 * ReplayVoiceData.SAMPLE_RATE) / 1000;

    private static volatile @Nullable ActiveRound active;

    private MatchVoiceRecorder() {}

    public static boolean isRecording() {
        ActiveRound round = active;
        return round != null && round.capturing;
    }

    public static void beginRound(UUID roundId, ServerLevel level, SceneBounds bounds) {
        active = new ActiveRound(roundId, level, bounds);
    }

    public static void activateRound(UUID roundId) {
        ActiveRound round = active;
        if (round != null && round.roundId.equals(roundId)) {
            round.activate();
        }
    }

    static void tick(ServerLevel level, int matchTick, Set<UUID> deathsAtTick, List<LastMatchReplay.Node> events) {
        ActiveRound round = active;
        if (round == null || round.level != level || !round.capturing) return;
        round.recordTick(level, matchTick, deathsAtTick);
        if (matchTick % WINDOW_CHECK_TICKS == 0) round.captureClosedWindows(matchTick, events);
    }

    static void stop(ServerLevel level) {
        ActiveRound round = active;
        if (round != null && round.level == level) round.stopCapture();
    }

    static void freezePostEventPositions(UUID roundId) {
        ActiveRound round = active;
        if (round != null && round.roundId.equals(roundId)) round.freezePostEventPositions();
    }

    static void leave(ServerLevel level, UUID speaker, int tick) {
        ActiveRound round = active;
        if (round != null && round.level == level && round.capturing) round.leave(speaker, tick);
    }

    static @Nullable UUID recordingRound(UUID speaker, String dimension) {
        ActiveRound round = active;
        return round != null && round.capturing && round.dimension.equals(dimension)
                && round.clock.speakers().contains(speaker) ? round.roundId : null;
    }

    public static void reset() {
        ActiveRound round = active;
        active = null;
        if (round != null) round.stopCapture();
    }

    static int postEventTicks() {
        return (POST_EVENT_MS + 49) / 50;
    }

    /** Called from {@link ReplayVoicePlugin} on Simple Voice Chat's audio thread. */
    static void recordPcm48k(UUID roundId, UUID speaker, float maxDistanceBlocks, short[] pcm48k) {
        ActiveRound round = active;
        if (round == null || !round.roundId.equals(roundId) || pcm48k == null || pcm48k.length == 0) return;
        round.appendLivePcm48k(speaker, maxDistanceBlocks, pcm48k);
    }

    /** Deterministic timestamp injection hook for unit/integration tests and self-test verification. */
    public static void recordPcm48kAtMs(UUID roundId, UUID speaker, long matchMs,
                                        float maxDistanceBlocks, short[] pcm48k) {
        ActiveRound round = active;
        if (round == null || !round.roundId.equals(roundId) || speaker == null || pcm48k == null || pcm48k.length == 0) {
            return;
        }
        round.appendPcm48kAtMs(speaker, matchMs, maxDistanceBlocks, pcm48k);
    }

    /** Deterministic position sample injection hook for pure unit tests without a live Minecraft world. */
    public static void seedSelfTestPosition(UUID roundId, UUID playerId, int tick,
                                              double x, double y, double z, boolean alive) {
        recordPositionForTest(roundId, playerId, tick, x, y, z, alive);
    }

    static void recordPositionForTest(UUID roundId, UUID playerId, int tick,
                                      double x, double y, double z, boolean alive) {
        ActiveRound round = active;
        if (round == null || !round.roundId.equals(roundId)) return;
        round.recordPlayerPos(playerId, tick, x, y, z, alive);
    }

    static void beginTestRound(UUID roundId, SceneBounds bounds) {
        ActiveRound round = new ActiveRound(roundId, null, bounds);
        round.activate();
        active = round;
    }

    public static byte[] finishRound(UUID roundId, List<LastMatchReplay.Node> sortedEvents) {
        ReplayVoiceData voice = finish(roundId, sortedEvents);
        return voice.isEmpty() ? new byte[0] : voice.toBytes();
    }

    static ReplayVoiceData extractForTest(UUID roundId, List<LastMatchReplay.Node> sortedEvents) {
        return finish(roundId, sortedEvents);
    }

    static void captureClosedWindowsForTest(int tick, List<LastMatchReplay.Node> events) {
        if (active != null) active.captureClosedWindows(tick, events);
    }

    static void leaveForTest(UUID speaker, int tick) {
        if (active != null) active.leave(speaker, tick);
    }

    private static ReplayVoiceData finish(UUID roundId, List<LastMatchReplay.Node> sortedEvents) {
        ActiveRound round = active;
        if (round == null || !round.roundId.equals(roundId)) return ReplayVoiceData.EMPTY;
        reset();
        return round.extractVoiceData(sortedEvents);
    }

    private record PosSample(int tick, double x, double y, double z, boolean alive, boolean present) {
        double distanceTo(double ox, double oy, double oz) {
            double dx = x - ox, dy = y - oy, dz = z - oz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Raw 24 kHz frames stay lossless until each highlight is mixed and encoded once. */
    private record VoiceFrame(long matchMs, float maxDist, int samples24k, short[] pcm24k) {}
    private record CaptureClock(int tick, long nanos, Set<UUID> speakers) {}

    private static final class ActiveRound {
        final UUID roundId;
        final @Nullable ServerLevel level;
        final String dimension;
        final SceneBounds bounds;
        final Map<UUID, ArrayDeque<VoiceFrame>> framesBySpeaker = new HashMap<>();
        final Map<UUID, Long> lastLiveEndMs = new HashMap<>();
        final Map<UUID, List<PosSample>> posByPlayer = new HashMap<>();
        final Map<LastMatchReplay.Node, Optional<ReplayVoiceData.Clip>> closedWindows = new IdentityHashMap<>();
        volatile boolean capturing;
        volatile CaptureClock clock = new CaptureClock(0, 0, Set.of());
        volatile boolean postEventTail;

        ActiveRound(UUID roundId, @Nullable ServerLevel level, SceneBounds bounds) {
            this.roundId = roundId;
            this.level = level;
            this.dimension = level == null ? "" : level.dimension().location().toString();
            this.bounds = bounds;
        }

        void activate() {
            clock = new CaptureClock(0, System.nanoTime(), Set.of());
            capturing = true;
        }

        synchronized void stopCapture() {
            capturing = false;
        }

        synchronized void freezePostEventPositions() {
            postEventTail = true;
        }

        void recordTick(ServerLevel srvLevel, int matchTick, Set<UUID> deathsAtTick) {
            var game = SREGameWorldComponent.KEY.get(srvLevel);
            boolean closing = game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE;
            if (closing && postEventTail) {
                // Finalization teleports players to the lobby before the next world tick.  Keep
                // the last in-match positions and roster for the short voice tail instead of
                // replacing them with those lobby coordinates.
                clock = new CaptureClock(matchTick, System.nanoTime(), clock.speakers());
                return;
            }
            Set<UUID> present = new HashSet<>();
            for (ServerPlayer player : srvLevel.players()) {
                UUID id = player.getUUID();
                SRERole role = game.getRole(player);
                // During the one-second post-match voice tail the role component may already be
                // transitioning to INACTIVE.  Keep the last active speaker roster usable for
                // proximity checks while retaining the normal role filters during the match.
                if (!closing && RepairModeManager.isRepairer(id)) continue;
                if (!closing && (role == null || TMMRoles.DISCOVERY_CIVILIAN.identifier().equals(role.identifier()))) continue;
                if (closing && !clock.speakers().contains(id)) continue;
                present.add(id);
                boolean alive = GameUtils.isPlayerAliveAndSurvival(player) && !deathsAtTick.contains(id);
                recordPlayerPos(id, matchTick, player.getX(), player.getY(), player.getZ(), alive);
            }
            for (UUID id : clock.speakers()) {
                if (!present.contains(id)) markAbsent(id, matchTick);
            }
            clock = new CaptureClock(matchTick, System.nanoTime(), Set.copyOf(present));
        }

        synchronized void leave(UUID speaker, int tick) {
            markAbsent(speaker, tick);
            if (!clock.speakers().contains(speaker)) return;
            Set<UUID> remaining = new HashSet<>(clock.speakers());
            remaining.remove(speaker);
            clock = new CaptureClock(clock.tick(), clock.nanos(), Set.copyOf(remaining));
        }

        void markAbsent(UUID speaker, int tick) {
            List<PosSample> positions = posByPlayer.get(speaker);
            if (positions == null || positions.isEmpty() || !positions.getLast().present()) return;
            PosSample last = positions.getLast();
            recordPlayerPos(speaker, new PosSample(tick, last.x(), last.y(), last.z(), false, false));
        }

        void recordPlayerPos(UUID id, int tick, double x, double y, double z, boolean alive) {
            recordPlayerPos(id, new PosSample(tick, x, y, z, alive, true));
        }

        private void recordPlayerPos(UUID id, PosSample next) {
            List<PosSample> list = posByPlayer.computeIfAbsent(id, ignored -> new ArrayList<>());
            if (!list.isEmpty()) {
                PosSample prev = list.getLast();
                if (prev.tick() == next.tick()) {
                    list.set(list.size() - 1, next);
                    return;
                }
                if (prev.alive() == next.alive() && prev.present() == next.present()
                        && next.tick() - prev.tick() < 5 && prev.distanceTo(next.x(), next.y(), next.z()) < 0.2) {
                    return;
                }
            }
            list.add(next);
        }

        void appendLivePcm48k(UUID speaker, float maxDist, short[] pcm48k) {
            CaptureClock snapshot = clock;
            if (!capturing || !snapshot.speakers().contains(speaker)) return;
            // A voice packet can arrive after the nominal 50 ms server tick on a lagging server.
            // Keep the wall-clock offset instead of folding it back into the previous tick, then
            // enforce a per-speaker monotonic end so consecutive 20 ms packets never overlap.
            long subTickMs = Math.max(0L, (System.nanoTime() - snapshot.nanos()) / 1_000_000L);
            append(speaker, snapshot.tick() * 50L + subTickMs, maxDist, pcm48k, true);
        }

        void appendPcm48kAtMs(UUID speaker, long matchMs, float maxDist, short[] pcm48k) {
            append(speaker, matchMs, maxDist, pcm48k, false);
        }

        void append(UUID speaker, long matchMs, float maxDist, short[] pcm48k, boolean live) {
            if (!capturing || !Float.isFinite(maxDist) || maxDist <= 0.0f) return;
            short[] pcm24k = ReplayVoiceData.downsample48kTo24k(pcm48k);
            if (pcm24k.length == 0) return;
            VoiceFrame frame = new VoiceFrame(matchMs, maxDist, pcm24k.length, pcm24k);
            synchronized (this) {
                if (!capturing || live && !clock.speakers().contains(speaker)) return;
                var frames = framesBySpeaker.computeIfAbsent(speaker, ignored -> new ArrayDeque<>());
                if (frames.size() >= MAX_FRAMES_PER_SPEAKER) frames.removeFirst();
                long frameMs = matchMs;
                if (live) frameMs = Math.max(frameMs, lastLiveEndMs.getOrDefault(speaker, Long.MIN_VALUE));
                if (frameMs != frame.matchMs()) frame = new VoiceFrame(frameMs, frame.maxDist(), frame.samples24k(), frame.pcm24k());
                frames.addLast(frame);
                if (live) lastLiveEndMs.put(speaker,
                        frameMs + frame.samples24k() * 1000L / ReplayVoiceData.SAMPLE_RATE);
            }
        }

        void captureClosedWindows(int tick, List<LastMatchReplay.Node> events) {
            long nowMs = tick * 50L;
            Set<LastMatchReplay.Node> selected = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int index : ReplaySoundCuePlan.selectCloseUpEventIndices(events)) selected.add(events.get(index));
            closedWindows.keySet().retainAll(selected);
            Map<UUID, List<VoiceFrame>> frames = null;
            for (LastMatchReplay.Node node : selected) {
                if (closedWindows.containsKey(node) || nowMs < node.tick() * 50L + POST_EVENT_MS + FRAME_GRACE_MS) continue;
                if (frames == null) frames = snapshotFrames();
                closedWindows.put(node, Optional.ofNullable(mixClip(0, node, frames)));
            }
            discardBefore(nowMs - HISTORY_MS);
        }

        private synchronized Map<UUID, List<VoiceFrame>> snapshotFrames() {
            Map<UUID, List<VoiceFrame>> snapshot = new HashMap<>();
            framesBySpeaker.forEach((id, frames) -> {
                List<VoiceFrame> ordered = new ArrayList<>(frames);
                ordered.sort(Comparator.comparingLong(VoiceFrame::matchMs));
                snapshot.put(id, ordered);
            });
            return snapshot;
        }

        private void discardBefore(long oldestMs) {
            synchronized (this) {
                framesBySpeaker.values().forEach(frames -> frames.removeIf(frame ->
                        frame.matchMs() + frame.samples24k() * 1000L / ReplayVoiceData.SAMPLE_RATE <= oldestMs));
                framesBySpeaker.values().removeIf(ArrayDeque::isEmpty);
            }
            long oldestTick = oldestMs / 50;
            posByPlayer.values().removeIf(positions -> !positions.getLast().present() && positions.getLast().tick() < oldestTick);
            for (List<PosSample> positions : posByPlayer.values()) {
                int start = 0;
                while (start + 1 < positions.size() && positions.get(start + 1).tick() < oldestTick) start++;
                if (start > 0) positions.subList(0, start).clear();
            }
        }

        ReplayVoiceData extractVoiceData(List<LastMatchReplay.Node> sortedEvents) {
            Map<UUID, List<VoiceFrame>> remaining = snapshotFrames();
            List<ReplayVoiceData.Clip> clips = new ArrayList<>();
            for (int index : ReplaySoundCuePlan.selectCloseUpEventIndices(sortedEvents)) {
                LastMatchReplay.Node node = sortedEvents.get(index);
                Optional<ReplayVoiceData.Clip> closed = closedWindows.get(node);
                ReplayVoiceData.Clip clip = closed == null ? mixClip(index, node, remaining) : closed.orElse(null);
                if (clip != null) {
                    clips.add(closed == null ? clip : new ReplayVoiceData.Clip(index, clip.tick(),
                            clip.offsetMs(), clip.sampleCount(), clip.adpcm()));
                }
            }
            return clips.isEmpty() ? ReplayVoiceData.EMPTY : new ReplayVoiceData(clips);
        }

        private @Nullable ReplayVoiceData.Clip mixClip(int eventIdx, LastMatchReplay.Node node,
                                                       Map<UUID, List<VoiceFrame>> frames) {
            if (frames.isEmpty()) return null;
            LastMatchReplay.Position rel = node.to() != null ? node.to() : node.from();
            if (rel == null) return null;
            double ex = bounds.minX() + rel.blockX();
            double ey = bounds.minY() + rel.blockY();
            double ez = bounds.minZ() + rel.blockZ();
            long eventMs = node.tick() * 50L;
            long windowStartMs = eventMs - PRE_EVENT_MS;
            long windowEndMs = eventMs + POST_EVENT_MS;

            float[] mix = new float[WINDOW_SAMPLES_24K];
            boolean anySignal = false;

            for (var entry : frames.entrySet()) {
                UUID speaker = entry.getKey();
                List<PosSample> positions = posByPlayer.get(speaker);
                if (positions == null || positions.isEmpty()) continue;

                int prevEndSample = Integer.MIN_VALUE;
                for (VoiceFrame vf : entry.getValue()) {
                    long frameEndMs = vf.matchMs() + (vf.samples24k() * 1000L) / ReplayVoiceData.SAMPLE_RATE;
                    if (frameEndMs <= windowStartMs || vf.matchMs() >= windowEndMs) continue;

                    int frameTick = Math.max(0, (int) (vf.matchMs() / 50));
                    PosSample pos = lookupPos(positions, frameTick);
                    if (pos == null || !pos.present()) continue;

                    boolean aliveEligible = pos.alive();
                    if (!aliveEligible && vf.matchMs() <= eventMs + DEATH_GRACE_MS) {
                        PosSample pre = lookupPos(positions, Math.max(0, node.tick() - 1));
                        aliveEligible = pre != null && pre.alive()
                                && (speaker.equals(node.target()) || speaker.equals(node.actor()) || vf.matchMs() <= eventMs + 50);
                    }
                    if (!aliveEligible) continue;

                    double dist = pos.distanceTo(ex, ey, ez);
                    if (dist > vf.maxDist()) continue;

                    float gain = dist <= 2.0 ? 1.0f : Math.clamp(1.0f - (float) (dist / vf.maxDist()), 0.0f, 1.0f);
                    if (gain <= 0.02f) continue;

                    short[] pcm24k = vf.pcm24k();
                    int startSample = (int) Math.round((vf.matchMs() - windowStartMs) * (ReplayVoiceData.SAMPLE_RATE / 1000.0));
                    // Snap contiguous 20ms packets from the same speaker to avoid UDP jitter micro-gaps.
                    if (prevEndSample != Integer.MIN_VALUE && Math.abs(startSample - prevEndSample) <= 288) {
                        startSample = prevEndSample;
                    }
                    for (int i = 0; i < pcm24k.length; i++) {
                        int dst = startSample + i;
                        if (dst >= 0 && dst < mix.length) {
                            mix[dst] += pcm24k[i] * gain;
                            anySignal = true;
                        }
                    }
                    prevEndSample = startSample + pcm24k.length;
                }
            }

            if (!anySignal) return null;
            int first = -1, last = -1;
            for (int i = 0; i < mix.length; i++) {
                if (Math.abs(mix[i]) >= SILENCE_THRESHOLD) {
                    if (first < 0) first = i;
                    last = i;
                }
            }
            if (first < 0) return null;

            int trimStart = Math.max(0, first - PAD_SAMPLES_24K);
            int trimEnd = Math.min(mix.length, last + 1 + PAD_SAMPLES_24K);
            int len = trimEnd - trimStart;
            if (len < 1200) return null; // < 50ms blip, ignore noise

            short[] clipped = new short[len];
            int fade = Math.min(FADE_SAMPLES_24K, len / 4);
            for (int i = 0; i < len; i++) {
                float v = mix[trimStart + i];
                // Soft-knee limiter above 26,000 prevents clipping when multiple players talk simultaneously.
                float abs = Math.abs(v);
                if (abs > 26_000f) {
                    float excess = (abs - 26_000f) / 6_767f;
                    v = Math.copySign(26_000f + 6_767f * (float) Math.tanh(excess), v);
                }
                float env = 1.0f;
                if (fade > 0 && i < fade) env = i / (float) fade;
                else if (fade > 0 && i >= len - fade) env = (len - 1 - i) / (float) fade;
                clipped[i] = (short) Math.clamp(Math.round(v * env), Short.MIN_VALUE, Short.MAX_VALUE);
            }
            int offsetMs = (int) Math.round(-PRE_EVENT_MS + trimStart * 1000.0 / ReplayVoiceData.SAMPLE_RATE);
            return new ReplayVoiceData.Clip(eventIdx, node.tick(), offsetMs, len, ReplayVoiceData.encodeAdpcm(clipped));
        }

        private static @Nullable PosSample lookupPos(List<PosSample> list, int tick) {
            if (list.isEmpty()) return null;
            int lo = 0, hi = list.size() - 1;
            if (tick <= list.getFirst().tick()) {
                return list.getFirst().tick() == tick ? list.getFirst() : null;
            }
            if (tick >= list.getLast().tick()) {
                return list.getLast();
            }
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (list.get(mid).tick() <= tick) lo = mid;
                else hi = mid - 1;
            }
            return list.get(lo);
        }
    }
}
