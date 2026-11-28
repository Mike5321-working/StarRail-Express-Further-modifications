package com.habitrain.core.scene.client;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.client.ClientConnectionSession;
import com.habitrain.core.network.replay.ReplayTracks;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.client.OnGameFinishedClient;
import io.wifi.starrailexpress.event.client.OnGameStartedClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.habitrain.core.api.match.replay.LastMatchReplay.Participant;
import static com.habitrain.core.network.replay.ReplayPackets.*;

/** Client data bridge for the replay table renderer, side-edge controls, and nearby backpack sync. All public methods are game-thread only. */
public final class ClientReplayBoard {
    private static final ClientMatchMapSectionCache LOCAL = new ClientMatchMapSectionCache();
    private static final long RETRY_NS = 8_000_000_000L, VIEW_NS = 1_500_000_000L;
    private static final int MAX_VOICE_ATTEMPTS = 4;
    private static Available available = new Available(EMPTY, false);
    private static LastMatchReplay replay;
    private static ReplayTimeline timeline;
    private static MapData map;
    private static ReplayVoiceData voice = ReplayVoiceData.EMPTY;
    private static boolean voiceReady;
    private static int voiceAttempts;
    private static @Nullable UUID voiceRound;
    private static byte @Nullable [] voiceBuffer;
    private static @Nullable BitSet voiceHave;
    private static long lastView, lastMediaView, nextSummary, nextVoiceRequest;
    private static BlockPos board;
    private static ClientLevel boardLevel;

    private static final Map<BlockPos, ClientLevel> LOADED_BOARDS = new HashMap<>();
    private static @Nullable ClientLevel boardsSnapshotLevel;
    private static @Nullable List<BlockPos> boardsSnapshot;
    private static final Map<BlockPos, ReplayBoardSessionState> LIVE_SESSIONS = new HashMap<>();
    private static final Map<BlockPos, ExitAnim> EXITING_BOARDS = new HashMap<>();
    private static ClientLevel observedLevel;
    private static boolean wasInMatch;

    private ClientReplayBoard() {}

    /** Snapshot of a nearby loaded replay table for backpack linkage UIs. */
    public record NearbyBoard(BlockPos pos, @Nullable ReplayBoardSessionState session,
                              @Nullable LastMatchReplay replay, boolean mapReady, double distance) {
        public boolean activeSession() { return session != null && session.active(); }
    }

    /** Short smooth exit animation recorded when a table's session explicitly ends back to idle. */
    public record ExitAnim(UUID roundId, double freezeTick, long startGameTick) {
        public double progress(long gameTime, float partialTick) {
            double elapsed = (gameTime - startGameTick) + Math.clamp(partialTick, 0.0f, 1.0f);
            return ReplayStagePlan.clamp01(elapsed / ReplayStagePlan.LINEAR_EXIT_TICKS);
        }
    }

    public record View(@Nullable LastMatchReplay replay, @Nullable ReplayTimeline timeline,
                       @Nullable SceneAssetCodec.AssetData asset, int presentSections, int totalSections,
                       boolean hasRound, ReplayVoiceData voice, boolean voiceReady, boolean mapFailed) {
        public View(@Nullable LastMatchReplay replay, @Nullable ReplayTimeline timeline,
                    @Nullable SceneAssetCodec.AssetData asset, int presentSections, int totalSections,
                    boolean hasRound) {
            this(replay, timeline, asset, presentSections, totalSections, hasRound, ReplayVoiceData.EMPTY, true, false);
        }
        public View(@Nullable LastMatchReplay replay, @Nullable ReplayTimeline timeline,
                    @Nullable SceneAssetCodec.AssetData asset, int presentSections, int totalSections,
                    boolean hasRound, ReplayVoiceData voice, boolean voiceReady) {
            this(replay, timeline, asset, presentSections, totalSections, hasRound, voice, voiceReady, false);
        }
    }

    private static final View EMPTY_VIEW = new View(null, null, null, 0, 0, false);

    public static void register() {
        ReplayHistoryClient.register();
        ClientPlayNetworking.registerGlobalReceiver(BoardSessionSyncS2C.TYPE, (payload, context) ->
                ClientConnectionSession.executeIfConnected(context, () -> acceptSessionSync(payload)));
        ClientTickEvents.END_CLIENT_TICK.register(ClientReplayBoard::tick);
        ClientChunkEvents.CHUNK_UNLOAD.register(LOCAL::unload);
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof SettlementSummaryBoard.Marker marker) {
                BlockPos key = entity.getBlockPos().immutable();
                LOADED_BOARDS.put(key, level);
                boardsSnapshot = null;
                ReplayBoardSessionState cached = LIVE_SESSIONS.get(key);
                if (cached != null) marker.applyClientSession(cached);
                else if (marker.session() != null && marker.session().active()) LIVE_SESSIONS.put(key, marker.session());
            }
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof SettlementSummaryBoard.Marker) {
                BlockPos key = entity.getBlockPos();
                LOADED_BOARDS.remove(key, level);
                boardsSnapshot = null;
                EXITING_BOARDS.remove(key);
            }
        });
        OnGameStartedClient.EVENT.register(() -> {
            var level = Minecraft.getInstance().level;
            if (level != null) LOCAL.begin(level);
        });
        OnGameFinishedClient.EVENT.register(() -> {
            var level = Minecraft.getInstance().level;
            if (level != null) LOCAL.finish(level);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            clearView();
            available = new Available(EMPTY, false);
            LOCAL.clear();
            LOADED_BOARDS.clear();
            boardsSnapshot = null;
            boardsSnapshotLevel = null;
            LIVE_SESSIONS.clear();
            EXITING_BOARDS.clear();
            observedLevel = null;
            wasInMatch = false;
        }));
    }

    public static void acceptSessionSync(BoardSessionSyncS2C payload) {
        BlockPos pos = payload.board().immutable();
        ReplayBoardSessionState next = payload.state();
        Minecraft client = Minecraft.getInstance();
        ReplayBoardSessionState prev = boardSession(pos);
        if (next == null || next.endedToIdle()) {
            LIVE_SESSIONS.remove(pos);
            if (prev != null && prev.active() && !prev.preparing() && client.level != null) {
                View v = viewForBoard(pos);
                int dur = v.replay() == null ? 0 : v.replay().durationTicks();
                double at = prev.currentReplayTick(client.level.getGameTime(), 0f, dur);
                EXITING_BOARDS.put(pos, new ExitAnim(prev.roundId(), at, client.level.getGameTime()));
            }
        } else {
            EXITING_BOARDS.remove(pos);
            if (next.active() && !next.preparing() && pos.equals(board) && next.roundId().equals(available.roundId())
                    && (prev == null || prev.preparing() || !prev.roundId().equals(next.roundId()))
                    && voiceReady && voice.isEmpty() && !voice.truncated()) {
                voiceReady = false;
                voiceRound = null;
                voiceAttempts = 0;
                nextVoiceRequest = 0;
            }
            if (prev == null || !prev.roundId().equals(next.roundId()) || next.revision() >= prev.revision()) {
                LIVE_SESSIONS.put(pos, next);
            }
        }
        if (client.level != null && client.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker) {
            marker.applyClientSession(next);
        }
    }

    /** Returns the active exit fold animation if this board was just explicitly ended back to idle. */
    public static @Nullable ExitAnim exitAnim(BlockPos pos, long gameTime) {
        ExitAnim anim = EXITING_BOARDS.get(pos);
        if (anim == null) return null;
        if (gameTime - anim.startGameTick() >= ReplayStagePlan.LINEAR_EXIT_TICKS + 2) {
            EXITING_BOARDS.remove(pos);
            return null;
        }
        return anim;
    }

    /** Authoritative client-side transient session for a table, or {@code null} when idle. */
    public static @Nullable ReplayBoardSessionState boardSession(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null && client.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker) {
            return boardSession(pos, marker);
        }
        return boardSession(pos, null);
    }

    public static @Nullable ReplayBoardSessionState boardSession(BlockPos pos, @Nullable SettlementSummaryBoard.Marker marker) {
        ReplayBoardSessionState fromMarker = marker == null ? null : marker.session();
        ReplayBoardSessionState cached = LIVE_SESSIONS.get(pos);
        if (fromMarker != null && fromMarker.active()) {
            if (cached == null || !cached.roundId().equals(fromMarker.roundId()) || fromMarker.revision() >= cached.revision()) {
                return fromMarker;
            }
        }
        return cached != null && cached.active() ? cached : null;
    }

    public static List<BlockPos> loadedBoards() {
        ClientLevel level = Minecraft.getInstance().level;
        if (boardsSnapshot == null || boardsSnapshotLevel != level) {
            boardsSnapshot = LOADED_BOARDS.entrySet().stream().filter(e -> e.getValue() == level)
                    .map(Map.Entry::getKey).toList();
            boardsSnapshotLevel = level;
        }
        return boardsSnapshot;
    }

    public static boolean isLoadedBoard(BlockPos pos) {
        ClientLevel level = LOADED_BOARDS.get(pos);
        return level != null && level == Minecraft.getInstance().level;
    }

    /**
     * Probes the nearest loaded replay table within {@code maxDistance} blocks (for backpack sync bars).
     * Prefers a table with an active playback session when multiple tables are in range.
     */
    public static @Nullable NearbyBoard nearestBoard(double maxDistance) {
        return findNearbyBoard(maxDistance, false);
    }

    /**
     * Probes the nearest loaded replay table within {@code maxDistance} blocks that currently has an
     * active playback session (playing, paused, or in end-of-round hold).
     */
    public static @Nullable NearbyBoard nearestActiveBoard(double maxDistance) {
        return findNearbyBoard(maxDistance, true);
    }

    private static @Nullable NearbyBoard findNearbyBoard(double maxDistance, boolean activeOnly) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isSpectator()) return null;
        double limitSq = maxDistance * maxDistance;
        BlockPos bestPos = null;
        ReplayBoardSessionState bestSession = null;
        SettlementSummaryBoard.Marker.Playback bestPlayback = null;
        double bestDistSq = Double.MAX_VALUE;
        boolean bestActive = false;

        for (BlockPos pos : loadedBoards()) {
            double dSq = mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (dSq > limitSq || !(mc.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) continue;
            ReplayBoardSessionState session = boardSession(pos, marker);
            boolean active = session != null && session.active();
            if (activeOnly && !active) continue;
            if (bestPos == null || (active && !bestActive) || (active == bestActive && dSq < bestDistSq)) {
                bestPos = pos;
                bestSession = session;
                bestPlayback = marker.playback();
                bestDistSq = dSq;
                bestActive = active;
            }
        }
        if (bestPos == null) return null;
        boolean playing = bestActive || bestPlayback != null && bestPlayback.pendingOrPlaying(mc.level.getGameTime());
        View v = playing ? viewForBoard(bestPos) : viewAt(bestPos, false);
        boolean ready = v.replay() != null && (v.totalSections() > 0 && v.presentSections() >= v.totalSections());
        return new NearbyBoard(bestPos.immutable(), bestSession, v.replay(), ready, Math.sqrt(bestDistSq));
    }

    // ------------------------------------------------------------------ session control senders

    public static boolean startBoardPlayback(BlockPos boardPos, UUID roundId) {
        if (boardPos == null || roundId == null || EMPTY.equals(roundId)
                || !ClientPlayNetworking.canSend(StartPlayback.TYPE)) return false;
        ClientPlayNetworking.send(new StartPlayback(boardPos, roundId));
        return true;
    }

    public static boolean sendSessionControl(BoardSessionControlC2S control) {
        if (control == null || !ClientPlayNetworking.canSend(BoardSessionControlC2S.TYPE)) return false;
        ClientPlayNetworking.send(control);
        return true;
    }

    public static boolean togglePause(BlockPos boardPos) {
        return sendSessionControl(BoardSessionControlC2S.togglePause(boardPos));
    }

    public static boolean setPaused(BlockPos boardPos, boolean paused) {
        return sendSessionControl(paused ? BoardSessionControlC2S.pause(boardPos) : BoardSessionControlC2S.play(boardPos));
    }

    public static boolean seekBoard(BlockPos boardPos, double tick) {
        return sendSessionControl(BoardSessionControlC2S.seek(boardPos, tick));
    }

    public static boolean stepBoard(BlockPos boardPos, double deltaTicks) {
        return sendSessionControl(BoardSessionControlC2S.step(boardPos, deltaTicks));
    }

    public static boolean setBoardSpeed(BlockPos boardPos, float speed) {
        return sendSessionControl(BoardSessionControlC2S.setSpeed(boardPos, speed));
    }

    public static boolean setBoardFollow(BlockPos boardPos, @Nullable UUID followPlayerUuid) {
        return sendSessionControl(BoardSessionControlC2S.setFollow(boardPos, followPlayerUuid));
    }

    public static boolean setBoardKillCloseup(BlockPos boardPos, boolean enabled) {
        return sendSessionControl(BoardSessionControlC2S.setKillCloseup(boardPos, enabled));
    }

    public static boolean setBoardRoofCutaway(BlockPos boardPos, boolean enabled) {
        return sendSessionControl(BoardSessionControlC2S.setRoofCutaway(boardPos, enabled));
    }

    public static boolean endBoardSession(BlockPos boardPos) {
        return sendSessionControl(BoardSessionControlC2S.endToIdle(boardPos));
    }

    /** Call for one nearby, loaded board after the renderer's distance/frustum test. */
    public static View viewAt(BlockPos pos) {
        return viewAt(pos, true);
    }

    /** Idle tables only prefetch the summary. Playback and export also request map geometry and audio. */
    public static View viewAt(BlockPos pos, boolean requestMedia) {
        var client = Minecraft.getInstance();
        if (!visible(client, client.level, pos)) return EMPTY_VIEW;
        board = pos.immutable(); boardLevel = client.level; lastView = System.nanoTime();
        if (requestMedia) lastMediaView = lastView;
        return new View(replay, timeline, requestMedia && map != null ? map.asset() : null,
                map == null ? 0 : map.parts.size(), map == null ? 0 : map.manifest.sections().size(),
                !available.roundId().equals(EMPTY), voice, voiceReady, map != null && map.failed());
    }

    /**
     * Returns the archived round ID displayed on a replay board when the board is currently playing
     * a shared or non-latest historical round, or {@code null} when it shows the latest round.
     */
    public static @Nullable UUID boardHistoryRound(BlockPos pos) {
        var client = Minecraft.getInstance();
        if (client.level != null && client.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker) {
            ReplayBoardSessionState session = boardSession(pos, marker);
            if (session != null && session.active()
                    && (session.shared() || !session.roundId().equals(available.roundId()))) {
                return session.roundId();
            }
            var playback = marker.playback();
            if (playback != null && playback.pendingOrPlaying(client.level.getGameTime())
                    && (playback.shared() || !playback.roundId().equals(available.roundId()))) {
                return playback.roundId();
            }
        }
        return null;
    }

    /**
     * Returns the replay shown by a board, including a historical round shared to that board.
     * Controls opened from a shared board use the board's active round instead of the latest round.
     */
    public static View viewForBoard(BlockPos pos) {
        UUID archived = boardHistoryRound(pos);
        return archived != null ? ReplayHistoryClient.watch(archived) : viewAt(pos);
    }

    public static UUID availableRound() { return available.roundId(); }

    public static boolean inMatch(ClientLevel level) {
        var status = SREGameWorldComponent.KEY.get(level).getGameStatus();
        return status == SREGameWorldComponent.GameStatus.INITIATING || status == SREGameWorldComponent.GameStatus.ACTIVE;
    }

    public static boolean mapAvailable() { return available.mapReady(); }

    /** True once the summary of the currently available round has arrived and can be played or exported. */
    public static boolean replayLoaded() {
        return replay != null && timeline != null && replay.roundId().equals(available.roundId());
    }

    public static void accept(Available update) {
        if (!update.roundId().equals(available.roundId())) clearView();
        if (!available.equals(update)) nextSummary = 0;
        available = update;
    }

    public static void accept(LastMatchSummaryS2C payload) {
        var s = payload.replay();
        if (!available.roundId().equals(s.roundId()) || !looking(Minecraft.getInstance())) return;
        if (replay != null && replay.roundId().equals(s.roundId()) && replay.manifest() != null && s.manifest() == null) return;
        try {
            var expanded = expand(payload);
            var nextTimeline = new ReplayTimeline(expanded);
            if (map != null && (s.manifest() == null || !map.manifest.hash().equals(s.manifest().hash()))) map = null;
            replay = expanded;
            timeline = nextTimeline;
        } catch (RuntimeException malformed) {
            org.slf4j.LoggerFactory.getLogger(ClientReplayBoard.class).warn("Rejected replay data", malformed);
        }
    }

    static LastMatchReplay expand(LastMatchSummaryS2C payload) {
        return ReplayTracks.expand(payload);
    }

    static MapData loadMap(ReplayMapManifest manifest) {
        return loadMap(manifest, false);
    }

    static MapData loadMap(ReplayMapManifest manifest, boolean skipLocalCache) {
        MapData state = new MapData(manifest);
        if (skipLocalCache) {
            state.diskLoading = false;
            state.skipLocalLookup();
            return state;
        }
        SceneAssetCache.getInstance().readReplayMap(manifest).whenComplete((asset, failure) ->
                Minecraft.getInstance().execute(() -> {
                    state.diskLoading = false;
                    if (asset != null) {
                        for (int i = 0; i < asset.sections.size(); i++) state.add(i, asset.sections.get(i));
                        state.saved = true;
                    } else {
                        state.persistIfComplete();
                    }
                }));
        return state;
    }

    public static void accept(ReplaySectionPatchS2C patch) {
        if (map != null && replay != null && replay.roundId().equals(patch.roundId())) map.accept(patch);
    }

    public static void accept(ReplayVoiceChunkS2C chunk) {
        if (replay == null || !replay.roundId().equals(chunk.roundId())) return;
        if (voiceReady && chunk.roundId().equals(voiceRound)) return;
        if (chunk.total() == 0) {
            voice = ReplayVoiceData.EMPTY;
            voiceReady = true;
            voiceRound = chunk.roundId();
            voiceBuffer = null;
            voiceHave = null;
            return;
        }
        if (!chunk.roundId().equals(voiceRound) || voiceBuffer == null || voiceBuffer.length != chunk.total()) {
            if (chunk.offset() != 0) return;
            voiceRound = chunk.roundId();
            voiceBuffer = new byte[chunk.total()];
            voiceHave = new BitSet();
        }
        int part = chunk.offset() / FRAGMENT_BYTES;
        if (voiceHave.get(part)) return;
        nextVoiceRequest = System.nanoTime() + RETRY_NS;
        voiceAttempts = 0;
        System.arraycopy(chunk.bytes(), 0, voiceBuffer, chunk.offset(), chunk.bytes().length);
        voiceHave.set(part);
        int totalParts = (chunk.total() + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES;
        if (voiceHave.cardinality() != totalParts) return;
        byte[] assembled = voiceBuffer;
        voiceRound = chunk.roundId();
        voiceBuffer = null;
        voiceHave = null;
        try {
            voice = ReplayVoiceData.fromBytes(assembled);
            if (voice.truncated()) notifyVoiceTruncated();
        } catch (RuntimeException malformed) {
            org.slf4j.LoggerFactory.getLogger(ClientReplayBoard.class).warn("Rejected replay voice data", malformed);
            voice = ReplayVoiceData.EMPTY;
        }
        voiceReady = true;
    }

    static void notifyVoiceTruncated() {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "habitrain_core.replay.voice_truncated"), false);
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (observedLevel != level) {
            observedLevel = level;
            wasInMatch = false;
            board = null;
            boardLevel = null;
            lastMediaView = 0;
            LOADED_BOARDS.values().removeIf(other -> other != level);
            boardsSnapshot = null;
            LIVE_SESSIONS.clear();
            EXITING_BOARDS.clear();
        }
        if (level == null) return;
        boolean inMatch = inMatch(level);
        if (inMatch && !wasInMatch) LOCAL.begin(level);
        else if (inMatch) LOCAL.observe(level);
        else if (wasInMatch) LOCAL.finish(level);
        wasInMatch = inMatch;
        LOCAL.tick(level);
        if (board == null || available.roundId().equals(EMPTY)) return;
        long now = System.nanoTime();
        if (now - lastView >= VIEW_NS) return;
        if (replay == null || available.mapReady() && replay.manifest() == null) {
            if ((nextSummary == 0 || now - nextSummary >= 0) && ClientPlayNetworking.canSend(RequestSummary.TYPE)
                    && visible(client, boardLevel, board)) {
                nextSummary = now + RETRY_NS;
                ClientPlayNetworking.send(new RequestSummary(board, available.roundId()));
            }
            return;
        }
        if (lastMediaView == 0 || now - lastMediaView >= VIEW_NS || !visible(client, boardLevel, board)) return;
        if (map == null && replay.manifest() != null) map = loadMap(replay.manifest());
        if (map != null) {
            if (!map.complete() && map.stalled(now)) map.markFailed(now);
            map.step(now, missing -> {
                if (!ClientPlayNetworking.canSend(ReplayMissingSectionsC2S.TYPE)) return false;
                ClientPlayNetworking.send(new ReplayMissingSectionsC2S(board, replay.roundId(), missing));
                return true;
            });
            if (!map.complete()) return;
        } else if (available.mapReady()) {
            return;
        }
        if (!voiceReady && (nextVoiceRequest == 0 || now - nextVoiceRequest >= 0)) {
            if (voiceAttempts >= MAX_VOICE_ATTEMPTS || !ClientPlayNetworking.canSend(RequestVoiceC2S.TYPE)) {
                voiceBuffer = null;
                voiceHave = null;
                voiceReady = true;
            } else {
                voiceAttempts++;
                nextVoiceRequest = now + RETRY_NS;
                ClientPlayNetworking.send(new RequestVoiceC2S(board, replay.roundId()));
            }
        }
    }

    private static boolean looking(Minecraft client) {
        return board != null && System.nanoTime() - lastView < VIEW_NS && visible(client, boardLevel, board);
    }

    private static boolean visible(Minecraft client, ClientLevel level, BlockPos pos) {
        return client.player != null && level != null && level == client.level && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                && level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker
                && marker.settings().inView(pos, marker.getBlockState().getValue(SettlementSummaryBoard.FACING), client.player.getEyePosition());
    }

    private static void clearView() {
        board = null; boardLevel = null; lastMediaView = 0; nextSummary = 0; nextVoiceRequest = 0;
        replay = null; timeline = null; map = null;
        voiceAttempts = 0;
        voice = ReplayVoiceData.EMPTY; voiceReady = false; voiceRound = null; voiceBuffer = null; voiceHave = null;
    }

    private static final class Fragment {
        final byte[] data;
        final BitSet have = new BitSet();
        Fragment(int size) { data = new byte[size]; }
    }

    static final class MapData {
        final ReplayMapManifest manifest;
        final TreeMap<Integer, SceneAssetCodec.SectionData> parts;
        final Map<Integer, Fragment> partial = new HashMap<>();
        final BitSet decoding = new BitSet();
        final BitSet received = new BitSet();
        BitSet requested = new BitSet();
        final long localDeadline = System.nanoTime() + 1_000_000_000L;
        long retryAt;
        private int attemptsWithoutProgress;
        private boolean localChecked;
        boolean diskLoading = true, saved;
        boolean failed;
        long retryAfter;
        SceneAssetCodec.AssetData assembled;

        MapData(ReplayMapManifest manifest) { this(manifest, new TreeMap<>()); }
        MapData(ReplayMapManifest manifest, TreeMap<Integer, SceneAssetCodec.SectionData> parts) {
            this.manifest = manifest;
            this.parts = parts;
            parts.keySet().forEach(received::set);
        }
        void add(int index, SceneAssetCodec.SectionData s) {
            if (parts.putIfAbsent(index, s) == null) {
                received.set(index);
                assembled = null;
                attemptsWithoutProgress = 0;
            }
        }
        SceneAssetCodec.AssetData asset() {
            if (parts.isEmpty()) return null;
            if (assembled == null) assembled = ReplaySectionGeometry.asset(manifest, new ArrayList<>(parts.values()));
            return assembled;
        }
        boolean complete() { return parts.size() == manifest.sections().size(); }

        boolean stalled(long now) {
            return !failed && attemptsWithoutProgress >= 4 && decoding.isEmpty() && now - retryAt >= 0;
        }

        void waitingForTransfer() {
            attemptsWithoutProgress = 0;
        }

        void skipLocalLookup() {
            localChecked = true;
        }

        void markFailed(long now) {
            failed = true;
            retryAfter = now + 5_000_000_000L;
            requested.clear();
            partial.clear();
        }

        boolean failed() { return failed; }

        void accept(ReplaySectionPatchS2C patch) {
            if (patch.index() >= manifest.sections().size() || !requested.get(patch.index())) return;
            Fragment fragments = partial.get(patch.index());
            if (fragments == null || fragments.data.length != patch.total()) {
                if (patch.offset() != 0) return;
                fragments = new Fragment(patch.total());
                partial.put(patch.index(), fragments);
            }
            int part = patch.offset() / FRAGMENT_BYTES;
            if (fragments.have.get(part)) return;
            retryAt = System.nanoTime() + RETRY_NS;
            System.arraycopy(patch.bytes(), 0, fragments.data, patch.offset(), patch.bytes().length);
            fragments.have.set(part);
            if (fragments.have.cardinality() != (patch.total() + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES) return;
            partial.remove(patch.index());
            requested.clear(patch.index());
            decoding.set(patch.index());
            var expected = manifest.sections().get(patch.index());
            SceneAssetCache.getInstance().decodeReplaySection(manifest, expected, fragments.data).whenComplete((section, failure) ->
                    Minecraft.getInstance().execute(() -> {
                        decoding.clear(patch.index());
                        if (section != null) add(patch.index(), section);
                    }));
        }

        void step(long now, java.util.function.Predicate<BitSet> send) {
            if (complete()) { persistIfComplete(); return; }
            if (failed) {
                if (now - retryAfter < 0) return;
                failed = false;
                attemptsWithoutProgress = 0;
                retryAfter = 0;
                retryAt = 0;
                requested.clear();
                partial.clear();
            }
            if (!requested.isEmpty()) {
                if (now - retryAt >= 0 && send.test((BitSet) requested.clone())) {
                    attemptsWithoutProgress++;
                    retryAt = now + RETRY_NS;
                }
                return;
            }
            var entries = manifest.sections();
            if (diskLoading || decoding.cardinality() >= MAX_REQUESTED) return;
            if (!localChecked) {
                boolean finalizingLocal = LOCAL.finalizingGeometry();
                if (finalizingLocal && now - localDeadline < 0) return;
                for (int index = received.nextClearBit(0); index < entries.size(); index = received.nextClearBit(index + 1)) {
                    if (decoding.get(index)) continue;
                    var local = LOCAL.lookup(manifest, entries.get(index));
                    if (local != null) add(index, ReplaySectionGeometry.relative(local.data(), manifest.sourceBounds()));
                }
                localChecked = !finalizingLocal;
            }
            if (complete()) {
                persistIfComplete();
                return;
            }
            BitSet missing = new BitSet(entries.size());
            int count = 0;
            for (int index = received.nextClearBit(0); index < entries.size() && count < MAX_REQUESTED;
                 index = received.nextClearBit(index + 1)) {
                if (decoding.get(index)) continue;
                missing.set(index);
                count++;
            }
            if (!missing.isEmpty() && send.test(missing)) {
                requested = (BitSet) missing.clone();
                attemptsWithoutProgress++;
                retryAt = now + RETRY_NS;
            }
        }

        private void persistIfComplete() {
            if (saved || diskLoading || !complete() || parts.isEmpty()) return;
            saved = true;
            SceneAssetCache.getInstance().writeReplayMap(manifest, asset());
        }
    }
}
