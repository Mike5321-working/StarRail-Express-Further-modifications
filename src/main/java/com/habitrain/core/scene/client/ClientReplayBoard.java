package com.habitrain.core.scene.client;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.network.replay.ReplayTracks;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
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

/** Client data bridge for the future renderer. All public methods are game-thread only. */
public final class ClientReplayBoard {
    private static final ClientMatchMapSectionCache LOCAL = new ClientMatchMapSectionCache();
    private static final long RETRY_NS = 8_000_000_000L, VIEW_NS = 1_500_000_000L;
    private static final double VIEW_DISTANCE_SQUARED = 64 * 64;
    private static Available available = new Available(EMPTY, false);
    private static LastMatchReplay replay;
    private static ReplayTimeline timeline;
    private static MapData map;
    private static ReplayVoiceData voice = ReplayVoiceData.EMPTY;
    private static boolean voiceReady;
    private static @Nullable UUID voiceRound;
    private static byte @Nullable [] voiceBuffer;
    private static @Nullable BitSet voiceHave;
    private static long lastView, nextSummary, nextVoiceRequest;
    private static BlockPos board;
    private static ClientLevel boardLevel;

    private static final Map<BlockPos, ClientLevel> LOADED_BOARDS = new HashMap<>();
    private static ClientLevel observedLevel;
    private static boolean wasInMatch;

    private ClientReplayBoard() {}

    public record View(@Nullable LastMatchReplay replay, @Nullable ReplayTimeline timeline,
                       @Nullable SceneAssetCodec.AssetData asset, int presentSections, int totalSections,
                       boolean hasRound, ReplayVoiceData voice, boolean voiceReady) {
        public View(@Nullable LastMatchReplay replay, @Nullable ReplayTimeline timeline,
                    @Nullable SceneAssetCodec.AssetData asset, int presentSections, int totalSections,
                    boolean hasRound) {
            this(replay, timeline, asset, presentSections, totalSections, hasRound, ReplayVoiceData.EMPTY, true);
        }
    }

    public static void register() {
        ReplayHistoryClient.register();
        ClientTickEvents.END_CLIENT_TICK.register(ClientReplayBoard::tick);
        ClientChunkEvents.CHUNK_UNLOAD.register(LOCAL::unload);
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof SettlementSummaryBoard.Marker) LOADED_BOARDS.put(entity.getBlockPos().immutable(), level);
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof SettlementSummaryBoard.Marker) LOADED_BOARDS.remove(entity.getBlockPos(), level);
        });
        OnGameStartedClient.EVENT.register(() -> {
            var level = Minecraft.getInstance().level;
            if (level != null) LOCAL.begin(level);
        });
        OnGameFinishedClient.EVENT.register(() -> {
            var level = Minecraft.getInstance().level;
            if (level != null) LOCAL.finish(level);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            clearView();
            available = new Available(EMPTY, false);
            LOCAL.clear();
            LOADED_BOARDS.clear();
            observedLevel = null;
            wasInMatch = false;
        });
    }

    public static List<BlockPos> loadedBoards() {
        ClientLevel level = Minecraft.getInstance().level;
        return LOADED_BOARDS.entrySet().stream().filter(e -> e.getValue() == level)
                .map(Map.Entry::getKey).toList();
    }

    /** Call for one nearby, loaded board after the renderer's distance/frustum test. */
    public static View viewAt(BlockPos pos) {
        var client = Minecraft.getInstance();
        if (!visible(client, client.level, pos)) return new View(null, null, null, 0, 0, false);
        board = pos.immutable(); boardLevel = client.level; lastView = System.nanoTime();
        return new View(replay, timeline, map == null ? null : map.asset(),
                map == null ? 0 : map.parts.size(), map == null ? 0 : map.manifest.sections().size(),
                !available.roundId().equals(EMPTY), voice, voiceReady);
    }

    public static UUID availableRound() { return available.roundId(); }

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
            replay = expand(payload);
            timeline = new ReplayTimeline(replay);
            if (s.manifest() != null && (map == null || !map.manifest.hash().equals(s.manifest().hash()))) {
                map = loadMap(s.manifest());
            }
        } catch (RuntimeException malformed) {
            org.slf4j.LoggerFactory.getLogger(ClientReplayBoard.class).warn("Rejected replay data", malformed);
        }
    }

    static LastMatchReplay expand(LastMatchSummaryS2C payload) {
        var s = payload.replay();
        int playerCount = s.participants().size();
        var tracks = ReplayTracks.decode(payload.tracks(), playerCount + s.drones().size(), s.durationTicks());
        var players = new ArrayList<Participant>();
        for (int i = 0; i < playerCount; i++) {
            var p = s.participants().get(i);
            players.add(new Participant(p.id(), p.name(), p.startRole(), p.startColor(), p.endRole(), p.endColor(),
                    p.faction(), p.winner(), p.survivalSeconds(), p.kills(), p.score(), p.itemUses(), tracks.get(i)));
        }
        List<LastMatchReplay.Drone> drones = new ArrayList<>(s.drones().size());
        for (int i = 0; i < s.drones().size(); i++) {
            var meta = s.drones().get(i);
            drones.add(new LastMatchReplay.Drone(meta.id(), meta.owner(), tracks.get(playerCount + i)));
        }
        return new LastMatchReplay(s.roundId(), s.playbackAnchorTick(), s.durationTicks(), s.mapName(), s.modeId(),
                s.winStatus(), s.winKind(), s.customWinId(), s.customTitleJson(), s.customSubtitleJson(), s.customColor(),
                s.origin(), s.manifest(), players, s.events(), s.spans(), s.mvp(), s.killCount(), s.limited(), drones);
    }

    static MapData loadMap(ReplayMapManifest manifest) {
        MapData state = new MapData(manifest);
        SceneAssetCache.getInstance().readReplayMap(manifest).whenComplete((asset, failure) ->
                Minecraft.getInstance().execute(() -> {
                    state.diskLoading = false;
                    if (asset != null) {
                        for (int i = 0; i < asset.sections.size(); i++) state.add(i, asset.sections.get(i));
                        state.saved = true;
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
        nextVoiceRequest = System.nanoTime() + RETRY_NS;
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
            voiceReady = true;
        } catch (RuntimeException malformed) {
            org.slf4j.LoggerFactory.getLogger(ClientReplayBoard.class).warn("Rejected replay voice data", malformed);
            voice = ReplayVoiceData.EMPTY;
        }
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (observedLevel != level) {
            observedLevel = level;
            wasInMatch = false;
            board = null;
            boardLevel = null;
            LOADED_BOARDS.values().removeIf(other -> other != level);
        }
        if (level == null) return;
        var status = SREGameWorldComponent.KEY.get(level).getGameStatus();
        boolean inMatch = status == SREGameWorldComponent.GameStatus.INITIATING
                || status == SREGameWorldComponent.GameStatus.ACTIVE;
        if (inMatch && !wasInMatch) LOCAL.begin(level);
        else if (inMatch) LOCAL.observe(level);
        else if (wasInMatch) LOCAL.finish(level);
        wasInMatch = inMatch;
        LOCAL.tick(level);
        if (!looking(client) || available.roundId().equals(EMPTY)) return;
        long now = System.nanoTime();
        if (replay == null || available.mapReady() && replay.manifest() == null) {
            if ((nextSummary == 0 || now - nextSummary >= 0) && ClientPlayNetworking.canSend(RequestSummary.TYPE)) {
                nextSummary = now + RETRY_NS;
                ClientPlayNetworking.send(new RequestSummary(board, available.roundId()));
            }
            return;
        }
        if (map != null) {
            map.step(now, missing -> {
                if (!ClientPlayNetworking.canSend(ReplayMissingSectionsC2S.TYPE)) return false;
                ClientPlayNetworking.send(new ReplayMissingSectionsC2S(board, replay.roundId(), missing));
                return true;
            });
            if (!map.complete()) return;
        } else if (available.mapReady()) {
            return;
        }
        if (!voiceReady && (nextVoiceRequest == 0 || now - nextVoiceRequest >= 0)
                && ClientPlayNetworking.canSend(RequestVoiceC2S.TYPE)) {
            nextVoiceRequest = now + 2_000_000_000L;
            ClientPlayNetworking.send(new RequestVoiceC2S(board, replay.roundId()));
        }
    }

    private static boolean looking(Minecraft client) {
        return board != null && System.nanoTime() - lastView < VIEW_NS && visible(client, boardLevel, board);
    }

    private static boolean visible(Minecraft client, ClientLevel level, BlockPos pos) {
        if (client.player == null || level == null || level != client.level
                || client.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > VIEW_DISTANCE_SQUARED) return false;
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && level.getBlockState(pos).is(SettlementSummaryBoard.BLOCK);
    }

    private static void clearView() {

        board = null; boardLevel = null; nextSummary = 0; nextVoiceRequest = 0;
        replay = null; timeline = null; map = null;
        voice = ReplayVoiceData.EMPTY; voiceReady = false; voiceRound = null; voiceBuffer = null; voiceHave = null;
    }

    private static final class Fragment {
        final byte[] data;
        final BitSet have = new BitSet();
        Fragment(int size) { data = new byte[size]; }
    }

    static final class MapData {
        final ReplayMapManifest manifest;
        final TreeMap<Integer, SceneAssetCodec.SectionData> parts = new TreeMap<>();
        final Map<Integer, Fragment> partial = new HashMap<>();
        final BitSet decoding = new BitSet();
        BitSet requested = new BitSet();
        final long localDeadline = System.nanoTime() + 1_000_000_000L;
        long retryAt, nextBatch;
        boolean diskLoading = true, saved;
        SceneAssetCodec.AssetData assembled;

        MapData(ReplayMapManifest manifest) { this.manifest = manifest; }
        void add(int index, SceneAssetCodec.SectionData s) {
            if (parts.putIfAbsent(index, s) == null) assembled = null;
        }
        SceneAssetCodec.AssetData asset() {
            if (parts.isEmpty()) return null;
            if (assembled == null) assembled = ReplaySectionGeometry.asset(manifest, new ArrayList<>(parts.values()));
            return assembled;
        }
        boolean complete() { return parts.size() == manifest.sections().size(); }

        void accept(ReplaySectionPatchS2C patch) {
            if (patch.index() >= manifest.sections().size() || !requested.get(patch.index())) return;
            retryAt = System.nanoTime() + RETRY_NS;
            Fragment fragments = partial.get(patch.index());
            if (fragments == null || fragments.data.length != patch.total()) {
                if (patch.offset() != 0) return;
                fragments = new Fragment(patch.total());
                partial.put(patch.index(), fragments);
            }
            int part = patch.offset() / FRAGMENT_BYTES;
            if (fragments.have.get(part)) return;
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
            var entries = manifest.sections();
            BitSet missing = new BitSet(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                if (parts.containsKey(i)) continue;
                var local = LOCAL.lookup(manifest, entries.get(i));
                if (local != null) add(i, ReplaySectionGeometry.relative(local.data(), manifest.sourceBounds()));
                else if (!decoding.get(i) && missing.cardinality() < MAX_REQUESTED) missing.set(i);
            }
            if (parts.size() == entries.size()) {
                if (!saved && !diskLoading && !parts.isEmpty()) {
                    saved = true;
                    SceneAssetCache.getInstance().writeReplayMap(manifest, asset());
                }
                return;
            }
            if (diskLoading || missing.isEmpty() || LOCAL.finalizingGeometry() && now - localDeadline < 0
                    || nextBatch != 0 && now - nextBatch < 0) return;
            if (!requested.isEmpty()) {
                if (now - retryAt < 0) return;
                partial.clear();
            }
            if (send.test(missing)) {
                requested = (BitSet) missing.clone();
                retryAt = now + RETRY_NS;
                nextBatch = now + 600_000_000L;
            }
        }
    }
}
