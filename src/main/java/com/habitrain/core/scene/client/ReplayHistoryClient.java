package com.habitrain.core.scene.client;

import com.habitrain.core.api.client.replay.MatchReplayClientApi;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.client.ClientConnectionSession;
import com.habitrain.core.network.replay.ReplayArchiveData;
import com.habitrain.core.network.replay.ReplayArchivePackets.*;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.replay.ReplayHistoryScreen;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static com.habitrain.core.network.replay.ReplayArchivePackets.*;

public final class ReplayHistoryClient {
    private static final long RETRY_NS = 3_000_000_000L, WATCH_NS = 2_000_000_000L;
    private static final long SETTINGS_COOLDOWN_NS = 300_000_000L;
    private static final long MAX_ROUNDS_BYTES = 128L * 1024L * 1024L;
    private static final Map<UUID, Round> rounds = new LinkedHashMap<>(8, .75f, true);
    private static Settings settings;
    private static VoiceRecordingSettings voiceSettings;
    private static UUID sharing;
    private static int shareResult;
    private static long shareDeadline, nextSend;
    private static final Map<PreparingBoard, Long> lastReady = new HashMap<>();
    private static Set<UUID> boardPins = Set.of();
    private static final Map<UUID, Integer> pinCounts = new HashMap<>();
    private static long nextStats, nextLimit, nextRecording;
    private static final java.util.concurrent.ThreadPoolExecutor CACHE_IO = createCacheIoExecutor();

    private static java.util.concurrent.ThreadPoolExecutor createCacheIoExecutor() {
        var exec = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                task -> Thread.ofPlatform().daemon().name("habitrain-replay-client-cache").unstarted(task));
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    public static void shutdown() {
        CACHE_IO.shutdownNow();
    }

    /** Drops non-pinned history rounds on match start so lobby replay data does not linger in heap mid-match. */
    public static void onGameStarted() {
        rounds.entrySet().removeIf(entry -> entry.getValue().pins <= 0);
    }

    public enum Phase {
        LOADING, MAP, READY, MISSING, FAILED;

        boolean finishedLoading() { return this == READY || this == MISSING || this == FAILED; }
    }

    private record PreparingBoard(BlockPos pos, UUID round) {}
    static final class Round {
        final UUID id;
        long watched, requested, voiceRequested;
        int attempts;
        Path path;
        byte[] cached, voiceBuffer;
        BitSet voiceHave;
        int voiceReceivedParts, voiceTotalParts;
        boolean reading = true, voiceReady, voiceUnavailable;
        int voiceAttempts;
        int pins;
        Phase phase = Phase.LOADING;
        LastMatchReplay replay;
        ReplayTimeline timeline;
        ClientReplayBoard.MapData map;
        ReplayVoiceData voice = ReplayVoiceData.EMPTY;
        long estimatedVoiceBytes;
        Round(UUID id) { this.id = id; }

        long estimatedBytes() {
            long total = estimatedVoiceBytes;
            if (cached != null) total += cached.length;
            if (voiceBuffer != null) total += voiceBuffer.length;
            if (map != null) {
                var assembled = map.assembled;
                if (assembled != null) {
                    total += com.habitrain.core.api.scene.asset.SceneAssetCodec.estimateRetainedBytes(assembled);
                } else {
                    total += (long) map.parts.size() * 64L * 1024L;
                }
            }
            return total;
        }
    }
    private ReplayHistoryClient() {}

    static void register() {
        maintainCache(ReplayRoundCache.LIMIT);
        MatchReplayClientApi.install(ReplayHistoryScreen::open);
        MatchReplayClientApi.installNearbyTableBridge(new MatchReplayClientApi.NearbyTableBridge() {
            @Override
            public Screen openWithFocus(Screen parent, String matchId, boolean export, @Nullable UUID focusPlayer) {
                return ReplayHistoryScreen.open(parent, matchId, export, focusPlayer);
            }

            @Override
            public @Nullable MatchReplayClientApi.NearbyTableInfo nearestActiveTable(double maxDistance) {
                return ReplayHistoryClient.nearestActiveTable(maxDistance);
            }

            @Override
            public boolean controlNearestTable(double maxDistance, String action, double numericArg,
                                               @Nullable UUID uuidArg, boolean flagArg) {
                var table = ReplayHistoryClient.nearestActiveTable(maxDistance);
                return table != null && ReplayHistoryClient.controlTable(table.pos(), action, numericArg, uuidArg, flagArg);
            }

            @Override
            public @Nullable Screen openNearbyTableExport(Screen parent, double maxDistance) {
                var table = ReplayHistoryClient.nearestActiveTable(maxDistance);
                if (table == null || table.roundId().isEmpty()) return null;
                return ReplayHistoryScreen.open(parent, table.roundId(), true, table.followPlayerUuid());
            }

            @Override
            public @Nullable Screen openNearbyTablePreview(Screen parent, double maxDistance) {
                var table = ReplayHistoryClient.nearestActiveTable(maxDistance);
                if (table == null || table.roundId().isEmpty()) return null;
                return ReplayHistoryScreen.openSynced(parent, table.roundId());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Reply.TYPE, (r, ctx) -> ClientConnectionSession.executeIfConnected(ctx, () -> onReply(r)));
        ClientPlayNetworking.registerGlobalReceiver(Summary.TYPE, (r, ctx) -> ClientConnectionSession.executeIfConnected(ctx, () -> onSummary(r.data())));
        ClientPlayNetworking.registerGlobalReceiver(Section.TYPE, (r, ctx) -> ClientConnectionSession.executeIfConnected(ctx, () -> {
            var round = rounds.get(r.data().roundId());
            if (round != null && round.map != null) round.map.accept(r.data());
        }));
        ClientPlayNetworking.registerGlobalReceiver(Voice.TYPE, (r, ctx) -> ClientConnectionSession.executeIfConnected(ctx, () -> onVoice(r.data())));
        ClientPlayNetworking.registerGlobalReceiver(Settings.TYPE, (r, ctx) -> ClientConnectionSession.executeIfConnected(ctx, () -> settings = r));
        ClientPlayNetworking.registerGlobalReceiver(VoiceRecordingSettings.TYPE, (r, ctx) -> ClientConnectionSession.executeIfConnected(ctx, () -> voiceSettings = r));
        ClientTickEvents.END_CLIENT_TICK.register(ReplayHistoryClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            rounds.clear();
            lastReady.clear();
            boardPins = Set.of();
            pinCounts.clear();
            settings = null;
            voiceSettings = null;
            sharing = null;
            nextSend = 0;
            nextStats = nextLimit = nextRecording = 0;
        }));
    }

    public static boolean supported() { return ClientPlayNetworking.canSend(Request.TYPE); }
    public static Settings settings() { return settings; }
    public static VoiceRecordingSettings voiceSettings() { return voiceSettings; }
    public static boolean canSetVoiceRecording() { return ClientPlayNetworking.canSend(SetVoiceRecording.TYPE); }
    public static boolean canRequestVoiceRecording() { return canSetVoiceRecording() && due(nextRecording); }
    public static boolean setVoiceRecording(boolean enabled) {
        if (!canRequestVoiceRecording()) return false;
        nextRecording = System.nanoTime() + SETTINGS_COOLDOWN_NS;
        ClientPlayNetworking.send(new SetVoiceRecording(enabled));
        return true;
    }
    public static boolean canRequestSettings(long value) { return supported() && due(value == 0 ? nextStats : nextLimit); }
    public static boolean requestSettings(long value, long expectedLimit) {
        if (!canRequestSettings(value)) return false;
        long deadline = System.nanoTime() + SETTINGS_COOLDOWN_NS;
        if (value == 0) nextStats = deadline;
        else nextLimit = deadline;
        if (value != 0 && ClientPlayNetworking.canSend(CheckedLimit.TYPE)) {
            ClientPlayNetworking.send(new CheckedLimit(expectedLimit, value));
        } else {
            ClientPlayNetworking.send(Request.settings(value == 0 ? STATS : LIMIT, value));
        }
        return true;
    }

    private static boolean due(long deadline) { return deadline == 0 || System.nanoTime() - deadline >= 0; }

    public static void retry(UUID id) { rounds.remove(id); }

    /** Keeps a round resident while a screen, export, or shared board is using it. */
    public static void pin(UUID id) {
        if (id == null) return;
        Round round = rounds.get(id);
        if (round == null) {
            watch(id);
            round = rounds.get(id);
        }
        if (round != null) {
            int count = pinCounts.merge(id, 1, Integer::sum);
            round.pins = count;
        }
    }

    public static void unpin(UUID id) {
        if (id == null) return;
        Round round = rounds.get(id);
        Integer count = pinCounts.get(id);
        if (count == null) return;
        if (count <= 1) pinCounts.remove(id); else pinCounts.put(id, count - 1);
        if (round != null) round.pins = Math.max(0, count - 1);
    }

    /** Updates the set of rounds currently referenced by in-world shared boards. */
    public static void updateBoardPins(Set<UUID> active) {
        Set<UUID> next = active == null || active.isEmpty() ? Set.of() : Set.copyOf(active);
        if (next.equals(boardPins)) return;
        for (UUID id : boardPins) if (!next.contains(id)) unpin(id);
        for (UUID id : next) if (!boardPins.contains(id)) pin(id);
        boardPins = next;
    }

    public static boolean voiceUnavailable(UUID id) {
        var round = rounds.get(id);
        return round != null && round.voiceUnavailable;
    }

    /**
     * Returns voice download progress in {@code [0, 1]} while streaming multi-track HRVC v2 voice,
     * {@code 1.0f} once ready, or {@code -1.0f} if unavailable or not yet started.
     */
    public static float voiceProgress(UUID id) {
        var round = rounds.get(id);
        if (round == null || round.voiceUnavailable) return -1.0f;
        if (round.voiceReady) return 1.0f;
        if (round.voiceTotalParts <= 0) return -1.0f;
        return Math.clamp(round.voiceReceivedParts / (float) round.voiceTotalParts, 0.0f, 1.0f);
    }

    private static Path cacheDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("habitrain-cache").resolve("replay-rounds-v1");
    }

    public static void clearDiskCache() { maintainCache(0); }

    private static void maintainCache(long limit) {
        Path directory = cacheDirectory();
        CACHE_IO.execute(() -> {
            try { ReplayRoundCache.prune(directory, limit, System.currentTimeMillis()); }
            catch (IOException failure) { org.slf4j.LoggerFactory.getLogger(ReplayHistoryClient.class).warn("Replay cache cleanup failed", failure); }
        });
    }

    public static Phase phase(UUID id) {
        var r = rounds.get(id);
        return r == null ? Phase.LOADING : r.phase;
    }

    private static void evictIfOverBudget(@Nullable UUID keepId) {
        long total = 0L;
        for (Round round : rounds.values()) total += round.estimatedBytes();
        while (rounds.size() > 6 || total > MAX_ROUNDS_BYTES) {
            UUID victim = null;
            long victimBytes = 0L;
            for (Round candidate : rounds.values()) {
                if (candidate.pins == 0 && !candidate.id.equals(keepId)) {
                    victim = candidate.id;
                    victimBytes = candidate.estimatedBytes();
                    break;
                }
            }
            if (victim == null) break;
            rounds.remove(victim);
            total -= victimBytes;
        }
    }

    public static ClientReplayBoard.View watch(UUID id) {
        Round r = rounds.get(id);
        if (r == null && supported()) {
            while (rounds.size() >= 6) {
                UUID victim = rounds.values().stream().filter(candidate -> candidate.pins == 0)
                        .map(candidate -> candidate.id).findFirst().orElse(null);
                if (victim == null) break;
                rounds.remove(victim);
            }
            r = new Round(id);
            r.pins = pinCounts.getOrDefault(id, 0);
            rounds.put(id, r);
            evictIfOverBudget(id);
            load(r);
        }
        if (r == null) return new ClientReplayBoard.View(null, null, null, 0, 0, false);
        r.watched = System.nanoTime();
        var map = r.map;
        return new ClientReplayBoard.View(r.replay, r.timeline, map == null ? null : map.asset(),
                map == null ? 0 : map.parts.size(), map == null ? 0 : map.manifest.sections().size(), true,
                r.voice, r.voiceReady, map != null && map.failed());
    }

    private static void load(Round r) {
        Minecraft mc = Minecraft.getInstance();
        Path dir = cacheDirectory();
        r.path = dir.resolve(r.id + ".bin");
        CACHE_IO.execute(() -> {
            byte[] contents = null;
            try {
                if (Files.isRegularFile(r.path, LinkOption.NOFOLLOW_LINKS)) {
                    try (var input = Files.newInputStream(r.path, LinkOption.NOFOLLOW_LINKS)) {
                        contents = input.readNBytes(ReplayPackets.MAX_SUMMARY + 1);
                        if (contents.length > ReplayPackets.MAX_SUMMARY) contents = null;
                    }
                }
            } catch (IOException ignored) { }
            byte[] found = contents;
            mc.execute(() -> {
                r.cached = found;
                r.reading = false;
            });
        });
    }

    private static void save(Round r, ReplayPackets.LastMatchSummaryS2C data) {
        CACHE_IO.execute(() -> {
            Path tmp = null;
            try {
                Files.createDirectories(r.path.getParent());
                tmp = Files.createTempFile(r.path.getParent(), "replay-", ".part");
                Files.write(tmp, ReplayArchiveData.encode(data));
                Files.move(tmp, r.path, StandardCopyOption.REPLACE_EXISTING);
                ReplayRoundCache.prune(r.path.getParent(), ReplayRoundCache.LIMIT, System.currentTimeMillis());
            } catch (IOException | RuntimeException ignored) {
                // A read-only client directory should not prevent playback.
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        });
    }

    private static void onVoice(ReplayPackets.ReplayVoiceChunkS2C chunk) {
        Round r = rounds.get(chunk.roundId());
        if (r == null || r.voiceReady || r.voiceUnavailable) return;
        r.voiceRequested = System.nanoTime();
        if (chunk.total() == 0) {
            r.voice = ReplayVoiceData.EMPTY;
            r.voiceReady = true;
            r.voiceBuffer = null;
            r.voiceHave = null;
            r.voiceReceivedParts = 0;
            r.voiceTotalParts = 0;
            return;
        }
        if (chunk.total() < 0 || chunk.total() > ReplayVoiceData.MAX_VOICE_BYTES
                || chunk.offset() < 0 || chunk.offset() + chunk.bytes().length > chunk.total()) {
            return;
        }
        int totalParts = (chunk.total() + ReplayPackets.FRAGMENT_BYTES - 1) / ReplayPackets.FRAGMENT_BYTES;
        if (r.voiceBuffer == null || r.voiceBuffer.length != chunk.total()) {
            if (chunk.offset() != 0) return;
            r.voiceBuffer = new byte[chunk.total()];
            r.voiceHave = new BitSet(totalParts);
            r.voiceReceivedParts = 0;
            r.voiceTotalParts = totalParts;
        }
        int part = chunk.offset() / ReplayPackets.FRAGMENT_BYTES;
        if (r.voiceHave.get(part)) return;
        System.arraycopy(chunk.bytes(), 0, r.voiceBuffer, chunk.offset(), chunk.bytes().length);
        r.voiceHave.set(part);
        r.voiceReceivedParts = r.voiceHave.cardinality();
        if (r.voiceReceivedParts != totalParts) return;
        byte[] assembled = r.voiceBuffer;
        r.voiceBuffer = null;
        r.voiceHave = null;
        try {
            r.voice = ReplayVoiceData.fromBytes(assembled);
            r.estimatedVoiceBytes = assembled.length;
            if (r.voice.truncated()) ClientReplayBoard.notifyVoiceTruncated();
            r.voiceReady = true;
            evictIfOverBudget(r.id);
        } catch (RuntimeException malformed) {
            r.voice = ReplayVoiceData.EMPTY;
            r.estimatedVoiceBytes = 0L;
            stopVoiceRequests(r);
        }
    }

    private static void onReply(Reply result) {
        if (result.round().equals(sharing) && (result.status() == SHARED || result.status() == NO_TABLE || result.status() == TABLE_BUSY || result.status() == SHARE_ERROR)) {
            shareResult = result.status();
            return;
        }
        Round r = rounds.get(result.round());
        if (r == null) return;
        applyReply(r, result.status());
    }

    static void applyReply(Round r, int status) {
        if (r.phase == Phase.READY) {
            if (!r.voiceReady && r.voiceRequested != 0 && (status == MISSING || status == ERROR)) stopVoiceRequests(r);
            return;
        }
        switch (status) {
            case CACHED -> {
                try { install(r, ReplayArchiveData.decode(r.cached)); }
                catch (RuntimeException invalid) { r.cached = null; r.requested = 0; r.attempts = 0; }
            }
            case MISSING -> {
                r.phase = Phase.MISSING;
                stopVoiceRequests(r);
            }
            case ERROR -> {
                r.phase = Phase.FAILED;
                stopVoiceRequests(r);
            }
        }
    }

    private static void onSummary(ReplayPackets.LastMatchSummaryS2C data) {
        Round r = rounds.get(data.replay().roundId());
        if (r == null || r.replay != null) return;
        try {
            install(r, data);
            save(r, data);
        } catch (RuntimeException malformed) {
            r.phase = Phase.FAILED;
            stopVoiceRequests(r);
        }
    }

    private static void install(Round r, ReplayPackets.LastMatchSummaryS2C data) {
        if (!r.id.equals(data.replay().roundId()) || data.replay().manifest() == null) {
            throw new IllegalArgumentException("Unexpected replay archive round");
        }
        r.replay = ClientReplayBoard.expand(data);
        r.timeline = new ReplayTimeline(r.replay);
        r.map = ClientReplayBoard.loadMap(r.replay.manifest());
        r.cached = null;
        r.phase = Phase.MAP;
        evictIfOverBudget(r.id);
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || !supported()) return;
        long now = System.nanoTime();
        watchPreparingBoards(mc, now);
        // Most recently watched view first. One request at a time avoids trampling a map batch
        // with another table's summary request while several boards are visible.
        for (Round r : List.copyOf(rounds.values()).reversed()) {
            if (now - r.watched > WATCH_NS) continue;
            if (r.phase == Phase.LOADING && !r.reading && (r.requested == 0 || now - r.requested >= RETRY_NS)) {
                if (r.attempts >= 12) { r.phase = Phase.FAILED; stopVoiceRequests(r); continue; }
                if (send(Request.lookup(r.id, r.cached == null ? "" : ReplayArchiveData.digest(r.cached)), now)) {
                    r.requested = now;
                    r.attempts++;
                }
            } else if (r.phase == Phase.MAP) {
                if (r.map.complete()) {
                    r.phase = Phase.READY;
                    r.map.step(now, bits -> false);
                } else if (r.map.stalled(now)) {
                    r.map.markFailed(now);
                    r.phase = Phase.FAILED;
                    stopVoiceRequests(r);
                } else {
                    r.map.step(now, bits -> send(Request.sections(r.id, bits), now));
                }
            } else if (r.phase == Phase.READY && !r.voiceReady && !r.voiceUnavailable
                    && (r.voiceRequested == 0 || now - r.voiceRequested >= RETRY_NS)) {
                if (r.voiceAttempts >= 12) { stopVoiceRequests(r); continue; }
                if (send(Request.voice(r.id), now)) {
                    r.voiceRequested = now;
                    r.voiceAttempts++;
                }
            }
        }
    }

    private static void watchPreparingBoards(Minecraft mc, long now) {
        if (mc.player == null) return;
        lastReady.entrySet().removeIf(e -> now - e.getValue() > 3_000_000_000L);
        for (BlockPos pos : ClientReplayBoard.loadedBoards()) {
            if (mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 48 * 48
                    || !(mc.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) continue;
            UUID preparingRound = preparingRoundOf(marker);
            if (preparingRound == null) continue;
            watch(preparingRound);
            var key = new PreparingBoard(pos, preparingRound);
            if (phase(preparingRound).finishedLoading() && !lastReady.containsKey(key)) {
                ClientPlayNetworking.send(Request.boardReady(preparingRound, pos));
                lastReady.put(key, now);
            }
        }
    }

    private static @Nullable UUID preparingRoundOf(SettlementSummaryBoard.Marker marker) {
        ReplayBoardSessionState session = ClientReplayBoard.boardSession(marker.getBlockPos(), marker);
        if (session != null) {
            return session.preparing() && !session.endedToIdle() ? session.roundId() : null;
        }
        var playback = marker.playback();
        return playback != null && playback.preparing() ? playback.roundId() : null;
    }

    /** Finds the nearest lobby replay table (idle or active) within {@code maxDistance} blocks. */
    public static @Nullable BlockPos nearestBoardPos(double maxDistance) {
        ClientReplayBoard.NearbyBoard nearby = ClientReplayBoard.nearestBoard(maxDistance);
        return nearby == null ? null : nearby.pos();
    }

    /**
     * Probes the nearest active (preparing, playing, paused, or final-review-held) lobby replay table
     * within {@code maxDistance} blocks (16 blocks by default for backpack sync).
     */
    public static @Nullable MatchReplayClientApi.NearbyTableInfo nearestActiveTable(double maxDistance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator() || mc.level == null) return null;
        long gameTime = mc.level.getGameTime();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);

        ClientReplayBoard.NearbyBoard nearby = ClientReplayBoard.nearestActiveBoard(maxDistance);
        if (nearby != null && nearby.session() != null && nearby.session().active()) {
            ReplayBoardSessionState session = nearby.session();
            LastMatchReplay replay = nearby.replay();
            if (replay == null) {
                ClientReplayBoard.View view = resolveBoardView(nearby.pos(), session.roundId());
                if (view != null) replay = view.replay();
            }
            String mapName = replay == null ? "" : replay.mapName();
            int durationTicks = replay == null ? 0 : replay.durationTicks();
            double curTick = session.currentReplayTick(gameTime, partial, durationTicks);
            boolean finished = durationTicks > 0 && session.isFinishedHold(gameTime, partial, durationTicks);
            String followName = resolvePlayerName(replay, session.followPlayerUuid());
            return new MatchReplayClientApi.NearbyTableInfo(
                    nearby.pos(),
                    session.roundId().toString(),
                    mapName,
                    curTick,
                    durationTicks,
                    session.speed(),
                    session.paused(),
                    session.preparing(),
                    finished,
                    session.followPlayerUuid(),
                    followName,
                    session.killCloseupEnabled(),
                    session.roofCutaway(),
                    nearby.distance()
            );
        }

        // Legacy Playback fallback for older servers without BoardSessionSyncS2C
        double bestDistSq = maxDistance * maxDistance;
        BlockPos bestPos = null;
        SettlementSummaryBoard.Marker.Playback bestPlayback = null;
        for (BlockPos pos : ClientReplayBoard.loadedBoards()) {
            if (!(mc.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) continue;
            double distSq = mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            if (distSq > bestDistSq) continue;
            var playback = marker.playback();
            if (playback != null && playback.pendingOrPlaying(gameTime)) {
                bestDistSq = distSq;
                bestPos = pos;
                bestPlayback = playback;
            }
        }
        if (bestPos == null) return null;

        UUID roundId = bestPlayback.roundId();
        ClientReplayBoard.View view = resolveBoardView(bestPos, roundId);
        LastMatchReplay replay = view == null ? null : view.replay();
        String mapName = replay == null ? "" : replay.mapName();
        int durationTicks = replay == null ? 0 : replay.durationTicks();
        boolean preparing = bestPlayback.preparing();
        double elapsed = !preparing && gameTime >= bestPlayback.startTick()
                ? (gameTime - bestPlayback.startTick() + partial) : 0.0;
        double curTick = durationTicks > 0 ? Math.clamp(elapsed, 0.0, durationTicks) : 0.0;
        return new MatchReplayClientApi.NearbyTableInfo(
                bestPos,
                roundId.toString(),
                mapName,
                curTick,
                durationTicks,
                1.0f,
                false,
                preparing,
                false,
                null,
                "",
                false,
                true,
                Math.sqrt(bestDistSq)
        );
    }

    private static @Nullable ClientReplayBoard.View resolveBoardView(BlockPos pos, UUID roundId) {
        if (roundId != null && !roundId.equals(ClientReplayBoard.availableRound())) {
            Round cached = rounds.get(roundId);
            if (cached != null && cached.replay != null) {
                return watch(roundId);
            }
        }
        ClientReplayBoard.View boardView = ClientReplayBoard.viewForBoard(pos);
        if (boardView != null && boardView.replay() != null && roundId.equals(boardView.replay().roundId())) {
            return boardView;
        }
        return supported() ? watch(roundId) : boardView;
    }

    private static String resolvePlayerName(@Nullable LastMatchReplay replay, @Nullable UUID playerUuid) {
        if (playerUuid == null) return "";
        if (replay != null) {
            for (var p : replay.participants()) {
                if (playerUuid.equals(p.id())) {
                    return p.name() == null || p.name().isBlank() ? playerUuid.toString().substring(0, 8) : p.name();
                }
            }
        }
        return playerUuid.toString().substring(0, 8);
    }

    /** Sends a remote session control command to a nearby lobby replay table. */
    public static boolean controlTable(BlockPos pos, String action, double numericArg,
                                       @Nullable UUID uuidArg, boolean flagArg) {
        if (pos == null || action == null || !ClientPlayNetworking.canSend(ReplayPackets.BoardSessionControlC2S.TYPE)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) {
            return false;
        }
        ReplayBoardSessionState session = ClientReplayBoard.boardSession(pos);
        ReplayPackets.BoardSessionControlC2S packet = switch (action.toLowerCase(Locale.ROOT)) {
            case "play" -> ReplayPackets.BoardSessionControlC2S.play(pos);
            case "pause" -> ReplayPackets.BoardSessionControlC2S.pause(pos);
            case "toggle_pause" -> ReplayPackets.BoardSessionControlC2S.togglePause(pos);
            case "seek" -> ReplayPackets.BoardSessionControlC2S.seek(pos, Math.max(0.0, numericArg));
            case "step" -> ReplayPackets.BoardSessionControlC2S.step(pos, numericArg);
            case "speed" -> ReplayPackets.BoardSessionControlC2S.setSpeed(pos, (float) numericArg);
            case "cycle_speed" -> {
                float cur = session == null ? ReplayBoardSessionState.DEFAULT_SPEED : session.speed();
                float next = nextSpeed(cur);
                yield ReplayPackets.BoardSessionControlC2S.setSpeed(pos, next);
            }
            case "follow" -> ReplayPackets.BoardSessionControlC2S.setFollow(pos, uuidArg);
            case "cycle_follow" -> {
                int delta = numericArg < 0 ? -1 : 1;
                UUID nextFollow = nextFollowTarget(pos, session, delta);
                yield ReplayPackets.BoardSessionControlC2S.setFollow(pos, nextFollow);
            }
            case "closeup" -> ReplayPackets.BoardSessionControlC2S.setKillCloseup(pos,
                    session == null ? flagArg : !session.killCloseupEnabled());
            case "set_closeup" -> ReplayPackets.BoardSessionControlC2S.setKillCloseup(pos, flagArg);
            case "roof" -> ReplayPackets.BoardSessionControlC2S.setRoofCutaway(pos,
                    session == null ? flagArg : !session.roofCutaway());
            case "set_roof" -> ReplayPackets.BoardSessionControlC2S.setRoofCutaway(pos, flagArg);
            case "end" -> ReplayPackets.BoardSessionControlC2S.endToIdle(pos);
            default -> null;
        };
        if (packet == null) return false;
        ClientPlayNetworking.send(packet);
        return true;
    }

    private static float nextSpeed(float current) {
        float[] speeds = ReplayBoardSessionState.ALLOWED_SPEEDS;
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(current - speeds[i]) < 0.05f) {
                return speeds[(i + 1) % speeds.length];
            }
        }
        return ReplayBoardSessionState.DEFAULT_SPEED;
    }

    private static @Nullable UUID nextFollowTarget(BlockPos pos, @Nullable ReplayBoardSessionState session, int delta) {
        if (session == null) return null;
        ClientReplayBoard.View view = resolveBoardView(pos, session.roundId());
        LastMatchReplay replay = view == null ? null : view.replay();
        if (replay == null || replay.participants().isEmpty()) return null;
        var list = replay.participants();
        UUID current = session.followPlayerUuid();
        if (current == null) {
            return delta >= 0 ? list.getFirst().id() : list.getLast().id();
        }
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (current.equals(list.get(i).id())) { idx = i; break; }
        }
        if (idx < 0) return list.getFirst().id();
        int next = idx + delta;
        if (next < 0 || next >= list.size()) return null; // wrap back to Global
        return list.get(next).id();
    }

    private static void stopVoiceRequests(Round round) {
        round.voiceUnavailable = true;
        round.voiceBuffer = null;
        round.voiceHave = null;
        round.voiceReceivedParts = 0;
        round.voiceTotalParts = 0;
    }

    private static boolean send(Request request, long now) {
        if (nextSend != 0 && now - nextSend < 0) return false;
        ClientPlayNetworking.send(request);
        nextSend = now + 250_000_000L;
        return true;
    }

    public static void share(UUID id, BlockPos board) {
        shareSession(id, board, 0.0, ReplayBoardSessionState.DEFAULT_SPEED, false, null, false, true);
    }

    /**
     * Seamless Session Handoff: casts the current backpack preview state (timestamp, speed, pause,
     * follow-player perspective, kill close-up flag, and roof cutaway flag) onto a nearby lobby table.
     */
    public static void shareSession(UUID id, BlockPos board, double currentTick, float speed, boolean paused,
                                    @Nullable UUID followPlayerUuid, boolean killCloseupEnabled, boolean roofCutaway) {
        if (!supported() || id == null || board == null) return;
        sharing = id;
        shareResult = READY; // waiting
        shareDeadline = System.nanoTime() + 4_000_000_000L;
        if (ClientPlayNetworking.canSend(ReplayPackets.ShareSessionC2S.TYPE)) {
            ClientPlayNetworking.send(new ReplayPackets.ShareSessionC2S(
                    board,
                    id,
                    Math.max(0.0, currentTick),
                    ReplayBoardSessionState.sanitizeSpeed(speed),
                    paused,
                    followPlayerUuid,
                    killCloseupEnabled,
                    roofCutaway));
        } else {
            ClientPlayNetworking.send(Request.share(id, board));
        }
    }

    public static int shareStatus(UUID id) {
        if (id == null || !id.equals(sharing)) return -1;
        if (shareResult == READY && System.nanoTime() - shareDeadline >= 0) return ERROR;
        return shareResult;
    }
}
