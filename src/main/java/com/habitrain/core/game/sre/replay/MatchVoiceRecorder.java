package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.game.sre.mode.RepairModeManager;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.drone.DroneOperatorService;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.role.capability.ServerVoicePermissions;
import com.habitrain.core.scene.replay.ReplaySoundCuePlan;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.replay.ReplayVoiceData.ChannelMode;
import com.habitrain.core.scene.replay.ReplayVoiceData.RecordedVoicePolicy;
import com.habitrain.core.scene.replay.ReplayVoiceData.SpeakerStateSpan;
import com.habitrain.core.scene.replay.ReplayVoiceData.VoiceTimelineMeta;
import com.habitrain.core.scene.replay.ReplayVoiceData.VoiceUtterance;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREAbilityPlayerComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.noellesroles.content.item.RadioItem;
import org.agmas.noellesroles.game.roles.neutral.commander.CommanderHandler;
import org.agmas.noellesroles.role.ModRoles;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-match multi-track server voice recorder producing {@code HRVC v2} containers.
 *
 * <p>Kept completely free of Simple Voice Chat and client classes so dedicated servers without
 * {@code voicechat} load cleanly. Incoming 48 kHz mono frames from {@link ReplayVoicePlugin} are
 * anti-alias decimated to 24 kHz, VAD-segmented with 60 ms leading/trailing breath padding, and
 * incrementally encoded to 4-bit IMA ADPCM per speaker across the entire match (from {@code ACTIVE}
 * through settlement {@code +1000 ms}), alongside full {@code RoleVoicePolicy}, mute, private-channel,
 * and group state timelines.</p>
 */
public final class MatchVoiceRecorder {
    private static final int PRE_EVENT_MS = 2000;
    private static final int POST_EVENT_MS = 1000;
    private static final int WINDOW_MS = PRE_EVENT_MS + POST_EVENT_MS;
    private static final int WINDOW_SAMPLES_24K = (WINDOW_MS * ReplayVoiceData.SAMPLE_RATE) / 1000;
    private static final int DEATH_GRACE_MS = 150;

    // VAD & breath padding constants (24 kHz)
    private static final int VAD_PEAK_THRESHOLD = 240;
    private static final double VAD_RMS_THRESHOLD = 110.0;
    private static final int BREATH_PAD_MS = 60;
    private static final int BREATH_PAD_SAMPLES_24K = (BREATH_PAD_MS * ReplayVoiceData.SAMPLE_RATE) / 1000; // 1440
    private static final int VAD_HANGOVER_MS = 320;
    private static final int MAX_UTTERANCE_SPLIT_SAMPLES_24K = ReplayVoiceData.SAMPLE_RATE * 15; // 15 s
    private static final int MIN_UTTERANCE_SAMPLES_24K = (40 * ReplayVoiceData.SAMPLE_RATE) / 1000; // 40 ms
    private static final int FADE_SAMPLES_24K = (10 * ReplayVoiceData.SAMPLE_RATE) / 1000; // 10 ms
    private static final int LEGACY_SILENCE_THRESHOLD = 260;
    private static final int LEGACY_PAD_SAMPLES_24K = (40 * ReplayVoiceData.SAMPLE_RATE) / 1000;
    private static final int LEGACY_FADE_SAMPLES_24K = (30 * ReplayVoiceData.SAMPLE_RATE) / 1000;

    /** Reserve ~1.5 MiB inside the 48 MiB cap for metadata, headers, and optional legacy clips. */
    private static final int MAX_UTTERANCE_ADPCM_BUDGET = ReplayVoiceData.MAX_VOICE_BYTES - (3 * 512 * 1024);

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
    }

    static void stop(ServerLevel level) {
        ActiveRound round = active;
        if (round != null && (level == null || round.level == level)) round.stopCapture();
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

    /** Server-thread snapshot read safely by {@link ReplayVoicePlugin} on the UDP audio thread. */
    static ChannelHint channelHint(UUID speaker) {
        ActiveRound round = active;
        if (round == null) return ChannelHint.NONE;
        return round.channelHints.getOrDefault(speaker, ChannelHint.NONE);
    }

    static void updateLiveGroup(UUID speaker, @Nullable UUID groupId, boolean openGroup) {
        ActiveRound round = active;
        if (round != null && speaker != null) {
            if (groupId == null) round.liveGroups.remove(speaker);
            else round.liveGroups.put(speaker, new LiveGroup(groupId, openGroup));
        }
    }

    static void updateVoiceDistances(float defaultDist, float whisperDist) {
        ActiveRound round = active;
        if (round != null) {
            if (Float.isFinite(defaultDist) && defaultDist > 0f) round.defaultVoiceDist = defaultDist;
            if (Float.isFinite(whisperDist) && whisperDist > 0f) round.whisperVoiceDist = whisperDist;
        }
    }

    public static void reset() {
        ActiveRound round = active;
        active = null;
        if (round != null) round.stopCapture();
    }

    static int postEventTicks() {
        return (POST_EVENT_MS + 49) / 50;
    }

    /** Called from {@link ReplayVoicePlugin} on Simple Voice Chat's audio thread (proximity fallback overload). */
    static void recordPcm48k(UUID roundId, UUID speaker, float maxDistanceBlocks, short[] pcm48k) {
        recordPcm48k(roundId, speaker, maxDistanceBlocks, ChannelMode.NORMAL_OPEN, null, pcm48k);
    }

    /** Called from {@link ReplayVoicePlugin} on Simple Voice Chat's audio thread with full channel metadata. */
    static void recordPcm48k(UUID roundId, UUID speaker, float maxDistanceBlocks,
                             ChannelMode channelMode, @Nullable UUID groupId, short[] pcm48k) {
        ActiveRound round = active;
        if (round == null || !round.roundId.equals(roundId) || pcm48k == null || pcm48k.length == 0) return;
        round.appendLivePcm48k(speaker, maxDistanceBlocks, channelMode, groupId, pcm48k);
    }

    /** Deterministic timestamp injection hook for unit/integration tests and self-test verification. */
    public static void recordPcm48kAtMs(UUID roundId, UUID speaker, long matchMs,
                                        float maxDistanceBlocks, short[] pcm48k) {
        recordPcm48kAtMs(roundId, speaker, matchMs, maxDistanceBlocks, ChannelMode.NORMAL_OPEN, null, pcm48k);
    }

    public static void recordPcm48kAtMs(UUID roundId, UUID speaker, long matchMs, float maxDistanceBlocks,
                                        ChannelMode channelMode, @Nullable UUID groupId, short[] pcm48k) {
        ActiveRound round = active;
        if (round == null || !round.roundId.equals(roundId) || speaker == null || pcm48k == null || pcm48k.length == 0) {
            return;
        }
        round.appendPcm48kAtMs(speaker, matchMs, maxDistanceBlocks, channelMode, groupId, pcm48k);
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

    static void recordPolicyForTest(UUID roundId, RecordedVoicePolicy policy) {
        ActiveRound round = active;
        if (round != null && round.roundId.equals(roundId) && policy != null) {
            round.recordedPolicies.put(policyKey(policy), policy);
        }
    }

    static void recordStateSpanForTest(UUID roundId, SpeakerStateSpan span) {
        ActiveRound round = active;
        if (round != null && round.roundId.equals(roundId) && span != null) {
            round.extraTestSpans.add(span);
        }
    }

    static void beginTestRound(UUID roundId, SceneBounds bounds) {
        ActiveRound round = new ActiveRound(roundId, null, bounds);
        round.activate();
        active = round;
    }

    /** O(1) server-thread detach so ADPCM flush, highlight mixing and serialization run off-thread. */
    static @Nullable DetachedRound detachRound(UUID roundId) {
        ActiveRound round = active;
        if (round == null || !round.roundId.equals(roundId)) return null;
        active = null;
        round.stopCapture();
        return new DetachedRound(round);
    }

    static final class DetachedRound {
        private final ActiveRound round;

        DetachedRound(ActiveRound round) {
            this.round = round;
        }

        byte[] finishToBytes(List<LastMatchReplay.Node> sortedEvents) {
            ReplayVoiceData voice = round.extractVoiceData(sortedEvents);
            return voice.isEmpty() && !voice.truncated() ? new byte[0] : voice.toBytes();
        }
    }

    public static byte[] finishRound(UUID roundId, List<LastMatchReplay.Node> sortedEvents) {
        ReplayVoiceData voice = finish(roundId, sortedEvents);
        return voice.isEmpty() && !voice.truncated() ? new byte[0] : voice.toBytes();
    }

    static ReplayVoiceData extractForTest(UUID roundId, List<LastMatchReplay.Node> sortedEvents) {
        return finish(roundId, sortedEvents);
    }

    /** No-op compatibility hook for legacy tests (v2 retains full-match VAD utterances continuously). */
    static void captureClosedWindowsForTest(int tick, List<LastMatchReplay.Node> events) {
        ActiveRound round = active;
        if (round != null) round.flushIdleVad(tick * 50L);
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

    record ChannelHint(boolean radio, boolean killerPrivate) {
        static final ChannelHint NONE = new ChannelHint(false, false);
    }

    private record LiveGroup(UUID groupId, boolean open) {}

    private record PosSample(int tick, double x, double y, double z, boolean alive, boolean present) {
        double distanceTo(double ox, double oy, double oz) {
            double dx = x - ox, dy = y - oy, dz = z - oz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private record CaptureClock(int tick, long nanos, Set<UUID> speakers) {}

    private record RawFrame(long matchMs, short[] pcm24k) {}

    private static String policyKey(RecordedVoicePolicy p) {
        return p.roleId() + "|" + p.muteSend() + "|" + p.muteReceive() + "|" + p.isolateGroup()
                + "|" + p.hearWorld() + "|" + p.maxDistance();
    }

    private static boolean hasVoiceActivity(short[] pcm24k) {
        int peak = 0;
        double sumSq = 0;
        for (short s : pcm24k) {
            int a = Math.abs((int) s);
            if (a > peak) peak = a;
            sumSq += (double) s * s;
        }
        if (peak >= VAD_PEAK_THRESHOLD) return true;
        return Math.sqrt(sumSq / pcm24k.length) >= VAD_RMS_THRESHOLD;
    }

    /** Per-speaker streaming VAD & 60 ms pre-roll/post-pad utterance assembler. */
    private static final class SpeakerVadState {
        final UUID speakerId;
        final ArrayDeque<RawFrame> preRoll = new ArrayDeque<>(6);
        int preRollSamples;

        volatile boolean activeSpeech;
        long startMs;
        long lastFrameEndMs;
        volatile long lastActiveEndMs;
        float maxDist;
        ChannelMode mode = ChannelMode.NORMAL_OPEN;
        @Nullable UUID groupId;
        final List<short[]> chunks = new ArrayList<>();
        int totalSamples;

        SpeakerVadState(UUID speakerId) {
            this.speakerId = speakerId;
        }

        synchronized void push(long matchMs, float frameMaxDist, ChannelMode frameMode, @Nullable UUID frameGroup,
                  short[] pcm24k, List<VoiceUtterance> output, int[] budgetRemaining, Object outputLock) {
            long frameDurMs = Math.max(1L, Math.round(pcm24k.length * 1000.0 / ReplayVoiceData.SAMPLE_RATE));
            long frameEndMs = matchMs + frameDurMs;
            boolean voiced = hasVoiceActivity(pcm24k);

            // If metadata changed or there is a large gap during active speech, flush first
            if (activeSpeech && (frameMode != mode
                    || !Objects.equals(frameGroup, groupId)
                    || Math.abs(frameMaxDist - maxDist) > 1.0f
                    || matchMs - lastFrameEndMs > VAD_HANGOVER_MS)) {
                flush(output, budgetRemaining, outputLock);
            }

            if (!activeSpeech) {
                if (voiced) {
                    activeSpeech = true;
                    maxDist = frameMaxDist;
                    mode = frameMode;
                    groupId = frameGroup;
                    chunks.clear();
                    totalSamples = 0;
                    // Drain contiguous pre-roll breath buffer (up to 60ms)
                    long earliestAcceptable = matchMs - BREATH_PAD_MS - 40L;
                    startMs = matchMs;
                    boolean tookPreRoll = false;
                    while (!preRoll.isEmpty()) {
                        RawFrame pf = preRoll.removeFirst();
                        if (pf.matchMs() >= earliestAcceptable && pf.matchMs() <= matchMs) {
                            if (!tookPreRoll) {
                                startMs = pf.matchMs();
                                tookPreRoll = true;
                            }
                            chunks.add(pf.pcm24k());
                            totalSamples += pf.pcm24k().length;
                        }
                    }
                    preRollSamples = 0;
                    chunks.add(pcm24k);
                    totalSamples += pcm24k.length;
                    lastFrameEndMs = frameEndMs;
                    lastActiveEndMs = frameEndMs;
                } else {
                    preRoll.addLast(new RawFrame(matchMs, pcm24k));
                    preRollSamples += pcm24k.length;
                    while (preRollSamples > BREATH_PAD_SAMPLES_24K && preRoll.size() > 1) {
                        preRollSamples -= preRoll.removeFirst().pcm24k().length;
                    }
                }
                return;
            }

            // Already inside active speech
            chunks.add(pcm24k);
            totalSamples += pcm24k.length;
            lastFrameEndMs = frameEndMs;
            if (voiced) {
                lastActiveEndMs = frameEndMs;
            }
            if (totalSamples >= MAX_UTTERANCE_SPLIT_SAMPLES_24K) {
                flush(output, budgetRemaining, outputLock);
            } else if (!voiced && frameEndMs - lastActiveEndMs >= VAD_HANGOVER_MS) {
                flush(output, budgetRemaining, outputLock);
            }
        }

        boolean needsIdleFlush(long nowMs) {
            return activeSpeech && nowMs - lastActiveEndMs >= VAD_HANGOVER_MS;
        }

        synchronized void flushIfIdle(long nowMs, List<VoiceUtterance> output, int[] budgetRemaining, Object outputLock) {
            if (activeSpeech && nowMs - lastActiveEndMs >= VAD_HANGOVER_MS) {
                flush(output, budgetRemaining, outputLock);
            }
        }

        synchronized void flush(List<VoiceUtterance> output, int[] budgetRemaining, Object outputLock) {
            if (!activeSpeech || totalSamples == 0) {
                activeSpeech = false;
                chunks.clear();
                totalSamples = 0;
                return;
            }
            short[] merged = new short[totalSamples];
            int pos = 0;
            for (short[] c : chunks) {
                System.arraycopy(c, 0, merged, pos, c.length);
                pos += c.length;
            }
            activeSpeech = false;
            chunks.clear();
            totalSamples = 0;

            // Find exact first/last voiced sample and retain 60 ms leading & trailing breath pad
            int first = -1, last = -1;
            for (int i = 0; i < merged.length; i++) {
                if (Math.abs((int) merged[i]) >= VAD_PEAK_THRESHOLD / 2) {
                    if (first < 0) first = i;
                    last = i;
                }
            }
            if (first < 0) return;

            int trimStart = Math.max(0, first - BREATH_PAD_SAMPLES_24K);
            int trimEnd = Math.min(merged.length, last + 1 + BREATH_PAD_SAMPLES_24K);
            int len = trimEnd - trimStart;
            if (len < MIN_UTTERANCE_SAMPLES_24K) return;

            short[] trimmed = Arrays.copyOfRange(merged, trimStart, trimEnd);
            int fade = Math.min(FADE_SAMPLES_24K, len / 4);
            for (int i = 0; i < fade; i++) {
                float env = i / (float) fade;
                trimmed[i] = (short) Math.round(trimmed[i] * env);
                trimmed[len - 1 - i] = (short) Math.round(trimmed[len - 1 - i] * env);
            }

            // Perform ADPCM encoding and RMS computation outside the global utterance list lock.
            byte[] adpcm = ReplayVoiceData.encodeAdpcm(trimmed);
            long adjustedStartMs = Math.max(0L, startMs + Math.round(trimStart * 1000.0 / ReplayVoiceData.SAMPLE_RATE));
            float rms = ReplayVoiceData.computeNormalizedRms(trimmed);
            VoiceUtterance utterance = new VoiceUtterance(speakerId, adjustedStartMs, trimmed.length, maxDist,
                    mode, groupId, adpcm, rms);
            synchronized (outputLock) {
                if (adpcm.length + 48 > budgetRemaining[0] || output.size() >= ReplayVoiceData.MAX_UTTERANCES) {
                    budgetRemaining[1]++;
                    return;
                }
                budgetRemaining[0] -= (adpcm.length + 48);
                output.add(utterance);
            }
        }
    }

    private static final class OpenSpan {
        final long startMs;
        final int stateFlags;
        final @Nullable UUID groupId;
        final String roleId;

        OpenSpan(long startMs, int stateFlags, @Nullable UUID groupId, String roleId) {
            this.startMs = startMs;
            this.stateFlags = stateFlags;
            this.groupId = groupId;
            this.roleId = roleId;
        }

        boolean matches(int flags, @Nullable UUID group, String role) {
            return stateFlags == flags && Objects.equals(groupId, group) && roleId.equals(role);
        }
    }

    private static final class PlayerPosTrack {
        private final ArrayList<PosSample> samples = new ArrayList<>();
        private volatile boolean lastPresent = true;

        synchronized void record(PosSample next) {
            if (!samples.isEmpty()) {
                PosSample prev = samples.getLast();
                if (prev.tick() == next.tick()) {
                    samples.set(samples.size() - 1, next);
                    lastPresent = next.present();
                    return;
                }
                if (prev.alive() == next.alive() && prev.present() == next.present()
                        && next.tick() - prev.tick() < 5 && prev.distanceTo(next.x(), next.y(), next.z()) < 0.2) {
                    return;
                }
            }
            samples.add(next);
            lastPresent = next.present();
        }

        synchronized void markAbsent(int tick) {
            if (samples.isEmpty() || !samples.getLast().present()) return;
            PosSample last = samples.getLast();
            record(new PosSample(tick, last.x(), last.y(), last.z(), false, false));
        }

        boolean isAbsentAt(int tick) {
            if (lastPresent) return false;
            synchronized (this) {
                PosSample at = ActiveRound.lookupPos(samples, tick);
                return at != null && !at.present();
            }
        }

        synchronized @Nullable PosSample lookup(int tick) {
            return ActiveRound.lookupPos(samples, tick);
        }

        synchronized boolean isEmpty() {
            return samples.isEmpty();
        }
    }

    private static final class ActiveRound {
        final UUID roundId;
        final @Nullable ServerLevel level;
        final String dimension;
        final SceneBounds bounds;

        final ConcurrentHashMap<UUID, SpeakerVadState> vadBySpeaker = new ConcurrentHashMap<>();
        final ConcurrentHashMap<UUID, Long> lastLiveEndMs = new ConcurrentHashMap<>();
        final Object utteranceLock = new Object();
        final List<VoiceUtterance> utterances = new ArrayList<>();
        final int[] utteranceBudget = new int[]{MAX_UTTERANCE_ADPCM_BUDGET, 0};

        final ConcurrentHashMap<UUID, PlayerPosTrack> posByPlayer = new ConcurrentHashMap<>();
        final Map<UUID, OpenSpan> openSpans = new HashMap<>();
        final List<SpeakerStateSpan> closedSpans = new ArrayList<>();
        final List<SpeakerStateSpan> extraTestSpans = new ArrayList<>();
        final Map<String, RecordedVoicePolicy> recordedPolicies = new LinkedHashMap<>();
        @Nullable List<RoleVoicePolicy> lastPolicySnapshot;

        final ConcurrentHashMap<UUID, LiveGroup> liveGroups = new ConcurrentHashMap<>();
        volatile Map<UUID, ChannelHint> channelHints = Map.of();
        volatile boolean capturing;
        volatile CaptureClock clock = new CaptureClock(0, 0, Set.of());
        volatile boolean postEventTail;
        volatile float defaultVoiceDist = 48.0f;
        volatile float whisperVoiceDist = 16.0f;
        volatile long lastRecordedTickMs;

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
            long matchMs = matchTick * 50L;
            lastRecordedTickMs = Math.max(lastRecordedTickMs, matchMs);

            var game = SREGameWorldComponent.KEY.get(srvLevel);
            boolean closing = game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE;
            if (closing && postEventTail) {
                clock = new CaptureClock(matchTick, System.nanoTime(), clock.speakers());
                if (matchTick % 10 == 0) scheduleIdleVadFlush(matchMs);
                return;
            }

            var permSnap = ServerVoicePermissions.current();
            var snapPolicies = permSnap.policies();
            if (snapPolicies != lastPolicySnapshot) {
                lastPolicySnapshot = snapPolicies;
                for (RoleVoicePolicy pol : snapPolicies) {
                    if (pol == null || pol.role() == null) continue;
                    RecordedVoicePolicy rec = new RecordedVoicePolicy(
                            pol.role().location().toString(),
                            pol.muteSend(), pol.muteReceive(), pol.isolateGroup(), pol.hearWorld(),
                            (float) pol.maxDistance());
                    recordedPolicies.putIfAbsent(policyKey(rec), rec);
                }
            }

            boolean killerChannelOpen = false;
            for (ServerPlayer player : srvLevel.players()) {
                if (game.isRole(player, ModRoles.COMMANDER)) {
                    var ab = SREAbilityPlayerComponent.KEY.get(player);
                    if (ab != null && ab.status == 1) {
                        killerChannelOpen = true;
                        break;
                    }
                }
            }

            Set<UUID> present = new HashSet<>();
            Map<UUID, ChannelHint> hints = new HashMap<>();

            for (ServerPlayer player : srvLevel.players()) {
                UUID id = player.getUUID();
                SRERole role = game.getRole(player);
                if (!closing && RepairModeManager.isRepairer(id)) continue;
                if (!closing && (role == null || TMMRoles.DISCOVERY_CIVILIAN.identifier().equals(role.identifier()))) continue;
                if (closing && !clock.speakers().contains(id)) continue;
                present.add(id);

                boolean alive = GameUtils.isPlayerAliveAndSurvival(player) && !deathsAtTick.contains(id);
                recordPlayerPos(id, matchTick, player.getX(), player.getY(), player.getZ(), alive);

                int stateFlags = 0;
                if (!alive) stateFlags |= ReplayVoiceData.STATE_DEAD;
                if (SlothComponent.isSleepingSloth(player)) stateFlags |= ReplayVoiceData.STATE_MUTED_SLOTH;
                if (MimeKillerComponent.isMuted(player)) stateFlags |= ReplayVoiceData.STATE_MUTED_MIME;
                if (DroneOperatorService.isVoiceMuted(id)) stateFlags |= ReplayVoiceData.STATE_MUTED_DRONE;

                boolean inRadio = RadioItem.RADIO_GROUP.contains(id);
                boolean inKillerChan = killerChannelOpen && role != null && CommanderHandler.canUseKillerChannel(role);
                if (inRadio) stateFlags |= ReplayVoiceData.STATE_RADIO_CHANNEL;
                if (inKillerChan) stateFlags |= ReplayVoiceData.STATE_KILLER_CHANNEL;
                if (inRadio || inKillerChan) hints.put(id, new ChannelHint(inRadio, inKillerChan));

                var permPlayer = permSnap.players().get(id);
                LiveGroup liveGroup = liveGroups.get(id);
                UUID effectiveGroup = permPlayer != null && permPlayer.group() != null
                        ? permPlayer.group() : (liveGroup == null ? null : liveGroup.groupId());
                if (liveGroup != null && liveGroup.open()) stateFlags |= ReplayVoiceData.STATE_GROUP_OPEN;

                String roleId = "";
                if (role != null && role.identifier() != null) {
                    RoleKey canonical = RoleCatalogApi.instance().canonicalize(role.identifier());
                    roleId = canonical != null ? canonical.location().toString() : role.identifier().toString();
                }

                updateStateSpan(id, matchMs, stateFlags, effectiveGroup, roleId);
            }

            for (UUID id : clock.speakers()) {
                if (!present.contains(id)) {
                    markAbsent(id, matchTick);
                    updateStateSpan(id, matchMs, ReplayVoiceData.STATE_ABSENT, null, "");
                }
            }
            channelHints = hints.isEmpty() ? Map.of() : Map.copyOf(hints);
            clock = new CaptureClock(matchTick, System.nanoTime(), Set.copyOf(present));
            if (matchTick % 20 == 0) scheduleIdleVadFlush(matchMs);
        }

        private void updateStateSpan(UUID id, long matchMs, int stateFlags, @Nullable UUID groupId, String roleId) {
            OpenSpan cur = openSpans.get(id);
            if (cur == null) {
                openSpans.put(id, new OpenSpan(matchMs, stateFlags, groupId, roleId));
                return;
            }
            if (cur.matches(stateFlags, groupId, roleId)) return;
            if (closedSpans.size() < ReplayVoiceData.MAX_STATE_SPANS) {
                closedSpans.add(new SpeakerStateSpan(id, cur.startMs, Math.max(cur.startMs, matchMs - 1L),
                        cur.stateFlags, cur.groupId, cur.roleId));
            }
            openSpans.put(id, new OpenSpan(matchMs, stateFlags, groupId, roleId));
        }

        synchronized void leave(UUID speaker, int tick) {
            markAbsent(speaker, tick);
            updateStateSpan(speaker, tick * 50L, ReplayVoiceData.STATE_ABSENT, null, "");
            if (!clock.speakers().contains(speaker)) return;
            Set<UUID> remaining = new HashSet<>(clock.speakers());
            remaining.remove(speaker);
            clock = new CaptureClock(clock.tick(), clock.nanos(), Set.copyOf(remaining));
        }

        void markAbsent(UUID speaker, int tick) {
            PlayerPosTrack track = posByPlayer.get(speaker);
            if (track != null) track.markAbsent(tick);
        }

        void recordPlayerPos(UUID id, int tick, double x, double y, double z, boolean alive) {
            recordPlayerPos(id, new PosSample(tick, x, y, z, alive, true));
        }

        private void recordPlayerPos(UUID id, PosSample next) {
            posByPlayer.computeIfAbsent(id, ignored -> new PlayerPosTrack()).record(next);
        }

        void appendLivePcm48k(UUID speaker, float maxDist, ChannelMode mode, @Nullable UUID groupId, short[] pcm48k) {
            CaptureClock snapshot = clock;
            if (!capturing || !snapshot.speakers().contains(speaker)) return;
            long subTickMs = Math.max(0L, (System.nanoTime() - snapshot.nanos()) / 1_000_000L);
            append(speaker, snapshot.tick() * 50L + subTickMs, maxDist, mode, groupId, pcm48k, true);
        }

        void appendPcm48kAtMs(UUID speaker, long matchMs, float maxDist, ChannelMode mode, @Nullable UUID groupId, short[] pcm48k) {
            append(speaker, matchMs, maxDist, mode, groupId, pcm48k, false);
        }

        void append(UUID speaker, long matchMs, float maxDist, ChannelMode mode, @Nullable UUID groupId,
                    short[] pcm48k, boolean live) {
            if (!capturing || !Float.isFinite(maxDist) || maxDist <= 0.0f || matchMs < 0L) return;
            short[] pcm24k = ReplayVoiceData.downsample48kTo24k(pcm48k);
            if (pcm24k.length == 0) return;

            if (!capturing || (live && !clock.speakers().contains(speaker))) return;
            // Exclude packets arriving after a recorded departure (thread-safe per-player lock).
            PlayerPosTrack posTrack = posByPlayer.get(speaker);
            if (posTrack != null && posTrack.isAbsentAt((int) (matchMs / 50L))) return;

            SpeakerVadState vad = vadBySpeaker.computeIfAbsent(speaker, SpeakerVadState::new);
            long frameMs = matchMs;
            if (live) {
                long prevEnd = lastLiveEndMs.getOrDefault(speaker, Long.MIN_VALUE);
                if (prevEnd > frameMs) frameMs = prevEnd;
            }
            vad.push(frameMs, maxDist, mode, groupId, pcm24k, utterances, utteranceBudget, utteranceLock);
            long endMs = frameMs + Math.round(pcm24k.length * 1000.0 / ReplayVoiceData.SAMPLE_RATE);
            if (endMs > lastRecordedTickMs) lastRecordedTickMs = endMs;
            if (live) {
                lastLiveEndMs.put(speaker, frameMs + pcm24k.length * 1000L / ReplayVoiceData.SAMPLE_RATE);
            }
        }

        void scheduleIdleVadFlush(long nowMs) {
            boolean anyIdle = false;
            for (SpeakerVadState vad : vadBySpeaker.values()) {
                if (vad.needsIdleFlush(nowMs)) {
                    anyIdle = true;
                    break;
                }
            }
            if (!anyIdle) return;
            if (!ReplayBoardService.submitBackgroundVoiceWork(() -> flushIdleVad(nowMs))) {
                flushIdleVad(nowMs);
            }
        }

        void flushIdleVad(long nowMs) {
            for (SpeakerVadState vad : vadBySpeaker.values()) {
                if (vad.needsIdleFlush(nowMs)) {
                    vad.flushIfIdle(nowMs, utterances, utteranceBudget, utteranceLock);
                }
            }
        }

        ReplayVoiceData extractVoiceData(List<LastMatchReplay.Node> sortedEvents) {
            for (SpeakerVadState vad : vadBySpeaker.values()) {
                vad.flush(utterances, utteranceBudget, utteranceLock);
            }
            List<VoiceUtterance> finalizedUtterances;
            List<SpeakerStateSpan> allSpans;
            List<RecordedVoicePolicy> policies;
            int droppedUtterances;
            synchronized (this) {
                long endMs = Math.max(lastRecordedTickMs, clock.tick() * 50L + POST_EVENT_MS);
                allSpans = new ArrayList<>(closedSpans.size() + openSpans.size() + extraTestSpans.size());
                allSpans.addAll(closedSpans);
                allSpans.addAll(extraTestSpans);
                for (var entry : openSpans.entrySet()) {
                    if (allSpans.size() >= ReplayVoiceData.MAX_STATE_SPANS) break;
                    OpenSpan s = entry.getValue();
                    allSpans.add(new SpeakerStateSpan(entry.getKey(), s.startMs, Math.max(s.startMs, endMs),
                            s.stateFlags, s.groupId, s.roleId));
                }
                synchronized (utteranceLock) {
                    finalizedUtterances = new ArrayList<>(utterances);
                    droppedUtterances = utteranceBudget[1];
                }
                finalizedUtterances.sort(Comparator.comparingLong(VoiceUtterance::startMs)
                        .thenComparing(VoiceUtterance::speakerId));
                policies = List.copyOf(recordedPolicies.values());
            }

            VoiceTimelineMeta meta = new VoiceTimelineMeta(policies, allSpans, defaultVoiceDist, whisperVoiceDist);
            if (droppedUtterances > 0) {
                com.habitrain.core.HabiTrainCore.LOGGER.warn("Replay voice for {} reached its recording limit and omitted {} utterances",
                        roundId, droppedUtterances);
            }
            if (finalizedUtterances.isEmpty() && droppedUtterances == 0) {
                return ReplayVoiceData.EMPTY;
            }

            // Synthesize optional compact highlight proximity clips from the already-recorded v2 utterances
            // (stable index lookup after final event sort; zero IdentityHashMap usage).
            List<ReplayVoiceData.Clip> fallbackClips = buildHighlightClips(sortedEvents, finalizedUtterances);
            return new ReplayVoiceData(finalizedUtterances, meta, fallbackClips, droppedUtterances > 0);
        }

        private List<ReplayVoiceData.Clip> buildHighlightClips(List<LastMatchReplay.Node> sortedEvents,
                                                               List<VoiceUtterance> allUtterances) {
            if (sortedEvents == null || sortedEvents.isEmpty() || allUtterances.isEmpty()) return List.of();
            List<ReplayVoiceData.Clip> clips = new ArrayList<>();
            int clipByteBudget = 768 * 1024;
            for (int index : ReplaySoundCuePlan.selectCloseUpEventIndices(sortedEvents)) {
                if (clipByteBudget <= 4096) break;
                LastMatchReplay.Node node = sortedEvents.get(index);
                ReplayVoiceData.Clip clip = mixHighlightClip(index, node, allUtterances);
                if (clip != null && clip.adpcm().length <= clipByteBudget) {
                    clipByteBudget -= clip.adpcm().length;
                    clips.add(clip);
                }
            }
            return clips;
        }

        private @Nullable ReplayVoiceData.Clip mixHighlightClip(int eventIdx, LastMatchReplay.Node node,
                                                                List<VoiceUtterance> allUtterances) {
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

            for (VoiceUtterance u : allUtterances) {
                if (u.startMs() > windowEndMs) break;
                if (u.endMs() <= windowStartMs) continue;
                if (u.channelMode() != ChannelMode.NORMAL_OPEN && u.channelMode() != ChannelMode.WHISPER) continue;
                PlayerPosTrack positions = posByPlayer.get(u.speakerId());
                if (positions == null || positions.isEmpty()) continue;

                short[] pcm = u.decode24k();
                if (pcm.length == 0) continue;

                // Mix in 20 ms sub-frames so per-tick distance & death grace cutoffs are exact
                final int step = 480; // 20 ms at 24 kHz
                for (int off = 0; off < pcm.length; off += step) {
                    int chunkLen = Math.min(step, pcm.length - off);
                    long chunkMs = u.startMs() + Math.round(off * 1000.0 / ReplayVoiceData.SAMPLE_RATE);
                    long chunkEndMs = chunkMs + Math.round(chunkLen * 1000.0 / ReplayVoiceData.SAMPLE_RATE);
                    if (chunkEndMs <= windowStartMs || chunkMs >= windowEndMs) continue;

                    int frameTick = Math.max(0, (int) (chunkMs / 50L));
                    PosSample pos = positions.lookup(frameTick);
                    if (pos == null || !pos.present()) continue;

                    boolean aliveEligible = pos.alive();
                    if (!aliveEligible && chunkMs <= eventMs + DEATH_GRACE_MS) {
                        PosSample pre = positions.lookup(Math.max(0, node.tick() - 1));
                        aliveEligible = pre != null && pre.alive()
                                && (u.speakerId().equals(node.target()) || u.speakerId().equals(node.actor())
                                || chunkMs <= eventMs + 50L);
                    }
                    if (!aliveEligible) continue;

                    double dist = pos.distanceTo(ex, ey, ez);
                    if (dist > u.baseMaxDist()) continue;
                    float gain = dist <= 2.0 ? 1.0f : Math.clamp(1.0f - (float) (dist / u.baseMaxDist()), 0.0f, 1.0f);
                    if (gain <= 0.02f) continue;

                    int startSample = (int) Math.round((chunkMs - windowStartMs) * (ReplayVoiceData.SAMPLE_RATE / 1000.0));
                    for (int i = 0; i < chunkLen; i++) {
                        int dst = startSample + i;
                        if (dst >= 0 && dst < mix.length) {
                            mix[dst] += pcm[off + i] * gain;
                            anySignal = true;
                        }
                    }
                }
            }

            if (!anySignal) return null;
            int first = -1, last = -1;
            for (int i = 0; i < mix.length; i++) {
                if (Math.abs(mix[i]) >= LEGACY_SILENCE_THRESHOLD) {
                    if (first < 0) first = i;
                    last = i;
                }
            }
            if (first < 0) return null;

            int trimStart = Math.max(0, first - LEGACY_PAD_SAMPLES_24K);
            int trimEnd = Math.min(mix.length, last + 1 + LEGACY_PAD_SAMPLES_24K);
            int len = trimEnd - trimStart;
            if (len < 1200) return null;

            short[] clipped = new short[len];
            int fade = Math.min(LEGACY_FADE_SAMPLES_24K, len / 4);
            for (int i = 0; i < len; i++) {
                float v = mix[trimStart + i];
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
