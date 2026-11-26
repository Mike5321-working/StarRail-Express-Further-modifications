package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import com.habitrain.core.network.replay.ReplayTracks;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.server.SceneBandwidthGovernor;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static com.habitrain.core.network.replay.ReplayPackets.*;

public final class ReplayBoardService {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ReplayBoardService.class);
    public static final int VIEW_DISTANCE = 64;
    private static final long PER_PLAYER = 512 * 1024L, GLOBAL = 4L * 1024 * 1024;
    private static final int MAX_PENDING = 64;
    private static final long MAX_VOICE_TRANSFER_BYTES = 2L * ReplayVoiceData.MAX_VOICE_BYTES;
    private static final SceneBandwidthGovernor BANDWIDTH = new SceneBandwidthGovernor(System::nanoTime, GLOBAL);
    private static final Map<UUID, Transfer> TRANSFERS = new LinkedHashMap<>();
    private static final Map<UUID, Transfer> VOICE_TRANSFERS = new LinkedHashMap<>();
    private static final LinkedHashMap<UUID, VoiceWork> PENDING_VOICE = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_SUMMARY_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_SECTION_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_VOICE_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> LAST_CONTROL_REQUEST = new HashMap<>();
    private static final LinkedHashMap<String, ReplayMapCapture.Result> MAP_CACHE = new LinkedHashMap<>(8, .75f, true);
    private static ThreadPoolExecutor worker;
    private static ExecutorService voiceWorker;
    private static @Nullable VoiceWork activeVoiceWork;
    private static MinecraftServer server;
    private static Published latest;
    private static long nextPublication, lastPublication;

    private record Published(LastMatchSummaryS2C packet, int bytes, ReplayMapCapture.Result map, VoiceSource voice) {}

    private static final class VoiceSource {
        final byte[] bytes;
        // Only the single voice worker accesses the prepared context.
        private ReplayVoiceDelivery.Prepared prepared;

        VoiceSource(byte[] bytes) {
            this.bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        byte[] forRecipient(LastMatchSummaryS2C packet, UUID recipient) {
            if (prepared == null) prepared = ReplayVoiceDelivery.prepare(packet, bytes);
            return prepared.forRecipient(recipient);
        }
    }

    private ReplayBoardService() {}

    public static void register() {
        MatchReplayRecorder.register();
        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            nextPublication = lastPublication = 0;
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "habitrain-replay-encode"); thread.setDaemon(true); return thread;
            });
            voiceWorker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "habitrain-replay-table-voice");
                thread.setDaemon(true);
                return thread;
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            // Flush any pending match settlement while server & ReplayArchiveService are still accepting tasks.
            MatchReplayRecorder.flushPendingBeforeStop();
            MatchReplayRecorder.reset();
            server = null;
            latest = null;
            TRANSFERS.clear();
            VOICE_TRANSFERS.clear();
            PENDING_VOICE.clear();
            activeVoiceWork = null;
            LAST_SUMMARY_REQUEST.clear();
            LAST_SECTION_REQUEST.clear();
            LAST_VOICE_REQUEST.clear();
            LAST_CONTROL_REQUEST.clear();
            BANDWIDTH.reset();
            if (voiceWorker != null) voiceWorker.shutdownNow();
            if (worker != null) worker.shutdown();
            awaitWorker(worker);
            awaitWorker(voiceWorker);
            worker = null;
            voiceWorker = null;
            synchronized (MAP_CACHE) { MAP_CACHE.clear(); }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> announce(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            UUID id = handler.player.getUUID();
            TRANSFERS.remove(id);
            VOICE_TRANSFERS.remove(id);
            PENDING_VOICE.remove(id);
            LAST_SUMMARY_REQUEST.remove(id);
            LAST_SECTION_REQUEST.remove(id);
            LAST_VOICE_REQUEST.remove(id);
            LAST_CONTROL_REQUEST.remove(id);
            BANDWIDTH.forget(id);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((p, old, next) -> {
            UUID id = p.getUUID();
            TRANSFERS.remove(id);
            VOICE_TRANSFERS.remove(id);
            PENDING_VOICE.remove(id);
            LAST_SUMMARY_REQUEST.remove(id);
            LAST_SECTION_REQUEST.remove(id);
            LAST_VOICE_REQUEST.remove(id);
            LAST_CONTROL_REQUEST.remove(id);
            announce(p);
        });
        ServerTickEvents.END_SERVER_TICK.register(ReplayBoardService::tick);
        ServerPlayNetworking.registerGlobalReceiver(BoardSessionControlC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> control(ctx.player(), payload)));
    }

    private static void awaitWorker(@Nullable ExecutorService executor) {
        if (executor == null) return;
        try {
            if (!executor.awaitTermination(8, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static CompletableFuture<ReplayMapCapture.Result> encodeMap(ReplayMapCapture.Raw raw) {
        ThreadPoolExecutor executor = worker;
        if (executor == null) return CompletableFuture.completedFuture(null);
        try {
            return CompletableFuture.supplyAsync(() -> {
                Map<String, ReplayMapCapture.Result> recent;
                synchronized (MAP_CACHE) { recent = Map.copyOf(MAP_CACHE); }
                try {
                    var result = ReplayMapCapture.encode(raw, recent);
                    synchronized (MAP_CACHE) {
                        if (!executor.isShutdown()) {
                            MAP_CACHE.put(result.manifest().hash(), result);
                            long bytes = MAP_CACHE.values().stream().mapToLong(ReplayMapCapture.Result::bytes).sum();
                            while (MAP_CACHE.size() > 3 || bytes > 96L * 1024 * 1024) bytes -= MAP_CACHE.pollFirstEntry().getValue().bytes();
                        }
                    }
                    return result;
                } catch (IOException | RuntimeException failure) {
                    LoggerFactory.getLogger(ReplayBoardService.class).warn("Could not encode replay map {}", raw.map(), failure);
                    return null;
                }
            }, executor);
        } catch (RejectedExecutionException ignored) {
            return CompletableFuture.completedFuture(null);
        }
    }

    public static void publish(LastMatchReplay summary, CompletableFuture<ReplayMapCapture.Result> map) {
        publish(summary, map, new byte[0]);
    }

    public static void publish(LastMatchReplay summary, CompletableFuture<ReplayMapCapture.Result> map, byte[] voice) {
        publish(summary, map, voice, new com.habitrain.core.network.replay.VisualTape.Builder());
    }
    static void publish(LastMatchReplay summary, CompletableFuture<ReplayMapCapture.Result> map, byte[] voice, com.habitrain.core.network.replay.VisualTape.Builder visuals) {
        MinecraftServer owner = server;
        if (owner == null) return;
        long finishedAt = System.currentTimeMillis();
        dispatch(owner, () -> {
            if (server != owner) return;
            long sequence = ++nextPublication;
            Runnable prepare = () -> preparePublication(owner, summary, map, voice, finishedAt, visuals, sequence);
            try {
                worker.execute(prepare);
            } catch (RejectedExecutionException busy) {
                prepare.run();
            }
        });
    }

    private static void dispatch(MinecraftServer owner, Runnable task) {
        if (owner.isSameThread()) task.run();
        else owner.execute(task);
    }

    private static ReplayTracks.Encoding encodeTracks(LastMatchReplay summary) {
        var data = new ArrayList<List<LastMatchReplay.Sample>>(summary.participants().size() + summary.drones().size());
        summary.participants().forEach(p -> data.add(p.track()));
        summary.drones().forEach(drone -> data.add(drone.track()));
        var highlights = new HashMap<UUID, NavigableSet<Integer>>();
        for (var event : summary.events()) {
            if (!event.highlight() || event.tick() < 0 || event.tick() > summary.durationTicks()) continue;
            if (event.actor() != null) highlights.computeIfAbsent(event.actor(), ignored -> new TreeSet<>()).add(event.tick());
            if (event.target() != null) highlights.computeIfAbsent(event.target(), ignored -> new TreeSet<>()).add(event.tick());
        }
        var focusTicks = new ArrayList<NavigableSet<Integer>>(data.size());
        summary.participants().forEach(p -> focusTicks.add(highlights.getOrDefault(p.id(), Collections.emptyNavigableSet())));
        summary.drones().forEach(d -> focusTicks.add(highlights.getOrDefault(d.owner(), Collections.emptyNavigableSet())));

        // Encode high-rate v2 tracks with generous budget while keeping full summary safely under MAX_SUMMARY (960 KiB)
        return ReplayTracks.encodeV2WithStatus(data, summary.durationTicks(), focusTicks, 448 * 1024);
    }

    private static void preparePublication(MinecraftServer owner, LastMatchReplay summary,
                                           CompletableFuture<ReplayMapCapture.Result> map, byte[] voice, long finishedAt,
                                           com.habitrain.core.network.replay.VisualTape.Builder visuals, long sequence) {
        try {
            var encoded = encodeTracks(summary);
            var voiceSource = new VoiceSource(voice);
            var resolved = map == null || map.isCompletedExceptionally() ? null : map.getNow(null);
            // Pre-encode the summary packet & gzip v2 trailer off the server tick thread when possible.
            Published preEncoded = encodeRevisionWithFallback(summary, encoded.bytes(), resolved, true, encoded.thinned(), voiceSource);
            if (map != null) map.thenCompose(result -> result == null
                    ? CompletableFuture.<Void>failedFuture(new IOException("No complete replay geometry"))
                    : ReplayArchiveService.archive(owner, makePacket(summary, encoded.bytes(), result, encoded.thinned()),
                            result, finishedAt, voiceSource.bytes, visuals))
                    .whenComplete((saved, failure) -> {
                        if (failure == null) LOG.debug("Saved 3D replay {}", summary.roundId());
                        else LOG.warn("3D replay history unavailable for round {}", summary.roundId(), failure);
                    });
            dispatch(owner, () -> publishReady(owner, summary, map, encoded, voiceSource, preEncoded, resolved, sequence));
        } catch (RuntimeException encodingFailure) {
            LOG.warn("Could not prepare replay {}", summary.roundId(), encodingFailure);
        }
    }

    private static void publishReady(MinecraftServer owner, LastMatchReplay summary,
                                     CompletableFuture<ReplayMapCapture.Result> map, ReplayTracks.Encoding encoded,
                                     VoiceSource voiceSource, @Nullable Published preEncoded,
                                     @Nullable ReplayMapCapture.Result preResolved, long sequence) {
        if (server != owner || sequence <= lastPublication) return;
        if (latest != null && latest.packet.replay().playbackAnchorTick() > summary.playbackAnchorTick()) return;
        lastPublication = sequence;
        byte[] tracks = encoded.bytes();
        var resolved = map == null || map.isCompletedExceptionally() ? null : map.getNow(null);
        if (preEncoded != null && resolved == preResolved) {
            installRevision(preEncoded, true);
        } else {
            publishRevision(summary, tracks, resolved, true, encoded.thinned(), voiceSource);
        }
        if (map != null && resolved == null) map.thenAccept(result -> {
            if (result != null) owner.execute(() -> {
                if (server == owner && latest != null && latest.packet.replay().roundId().equals(summary.roundId())) {
                    publishRevision(summary, tracks, result, false, encoded.thinned(), voiceSource);
                }
            });
        });
    }

    private static @Nullable Published encodeRevisionWithFallback(LastMatchReplay s, byte[] tracks,
                                                                  @Nullable ReplayMapCapture.Result map, boolean newRound,
                                                                  boolean tracksThinned, VoiceSource voice) {
        try {
            return encodeRevision(s, tracks, map, tracksThinned, voice);
        } catch (RuntimeException failure) {
            LOG.warn("Could not encode replay summary {}, falling back", s.roundId(), failure);
            if (!s.gameSounds().isEmpty()) {
                return encodeRevisionWithFallback(s.withGameSounds(ReplayGameSoundData.EMPTY), tracks, map, newRound, tracksThinned, voice);
            } else if (!s.killSounds().isEmpty()) {
                return encodeRevisionWithFallback(s.withKillSounds(List.of()), tracks, map, newRound, tracksThinned, voice);
            } else if (newRound && map != null) {
                return encodeRevisionWithFallback(s, tracks, null, true, tracksThinned, voice);
            }
            return null;
        }
    }

    private static void publishRevision(LastMatchReplay s, byte[] tracks, ReplayMapCapture.Result map, boolean newRound,
                                        boolean tracksThinned, VoiceSource voice) {
        Published revision = encodeRevisionWithFallback(s, tracks, map, newRound, tracksThinned, voice);
        if (revision == null) return;
        installRevision(revision, newRound);
    }

    private static void installRevision(Published revision, boolean newRound) {
        latest = revision;
        LAST_SUMMARY_REQUEST.clear();
        if (newRound) {
            TRANSFERS.clear();
            VOICE_TRANSFERS.clear();
            PENDING_VOICE.clear();
            LAST_VOICE_REQUEST.clear();
        } else {
            TRANSFERS.replaceAll((id, transfer) -> transfer.indices == null
                    ? new Transfer(transfer.board, revision, (int[]) null) : transfer);
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) announce(p);
    }

    private static Published encodeRevision(LastMatchReplay s, byte[] tracks, ReplayMapCapture.Result map,
                                            boolean tracksThinned, VoiceSource voice) {
        var packet = makePacket(s, tracks, map, tracksThinned);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LastMatchSummaryS2C.CODEC.encode(buffer, packet);
            return new Published(packet, buffer.readableBytes(), map, voice);
        } finally {
            buffer.release();
        }
    }

    private static LastMatchSummaryS2C makePacket(LastMatchReplay s, byte[] tracks, ReplayMapCapture.Result map, boolean tracksThinned) {
        var people = s.participants().stream().map(p -> new LastMatchReplay.Participant(p.id(), p.name(),
                p.startRole(), p.startColor(), p.endRole(), p.endColor(), p.faction(), p.winner(),
                p.survivalSeconds(), p.kills(), p.score(), p.itemUses(), List.of())).toList();
        var replay = new LastMatchReplay(s.roundId(), s.playbackAnchorTick(), s.durationTicks(), s.mapName(), s.modeId(),
                s.winStatus(), s.winKind(), s.customWinId(), s.customTitleJson(), s.customSubtitleJson(), s.customColor(),
                s.origin(), map == null ? null : map.manifest(), people, s.events(), s.spans(),
                s.mvp(), s.killCount(), s.limited() || tracksThinned, s.drones().stream()
                        .map(d -> new LastMatchReplay.Drone(d.id(), d.owner(), List.of())).toList(),
                s.killSounds(), s.blockDeltas(), s.entityTracks(), s.skillEvents(), s.gameSounds());
        return new LastMatchSummaryS2C(replay, tracks);
    }

    private static void announce(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, Available.TYPE)) {
            ServerPlayNetworking.send(player, new Available(latest == null ? EMPTY : latest.packet.replay().roundId(),
                    latest != null && latest.map != null));
        }
    }

    static LastMatchReplay latestReplay(UUID roundId) {
        Published source = latest;
        return source != null && source.packet.replay().roundId().equals(roundId) ? source.packet.replay() : null;
    }

    static int resolveDuration(UUID roundId) {
        LastMatchReplay latestRep = latestReplay(roundId);
        if (latestRep != null) return latestRep.durationTicks();
        int archived = ReplayArchiveService.cachedDuration(roundId);
        return archived >= 0 ? archived : LastMatchReplay.MAX_TICKS;
    }

    static void broadcastSession(ServerLevel level, BlockPos board, @Nullable ReplayBoardSessionState sessionState) {
        if (level == null || board == null) return;
        BoardSessionSyncS2C sync = new BoardSessionSyncS2C(board.immutable(), sessionState);
        double maxDistSq = (double) VIEW_DISTANCE * VIEW_DISTANCE;
        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(board.getX() + 0.5, board.getY() + 0.5, board.getZ() + 0.5) <= maxDistSq
                    && ServerPlayNetworking.canSend(viewer, BoardSessionSyncS2C.TYPE)) {
                ServerPlayNetworking.send(viewer, sync);
            }
        }
    }

    public static void start(ServerPlayer player, StartPlayback packet) {
        Published source = latest;
        BlockPos pos = packet.board();
        if (server == null || server != player.getServer() || player.isSpectator()
                || !accessible(player, pos) || source == null
                || !source.packet.replay().roundId().equals(packet.roundId())) return;
        if (player.serverLevel().getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker) {
            double reach = marker.settings().interactReach();
            if (player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > reach * reach) return;
            long now = player.serverLevel().getGameTime();
            // Always start a clean ephemeral session with default controls (1.0x, killCloseup=false, roofCutaway=true).
            ReplayBoardSessionState fresh = ReplayBoardSessionState.fresh(packet.roundId(), false, now + 6);
            marker.setSession(fresh);
            broadcastSession(player.serverLevel(), pos, fresh);
        }
    }

    public static void control(ServerPlayer player, BoardSessionControlC2S packet) {
        BlockPos pos = packet.board();
        if (server == null || server != player.getServer() || player.isSpectator() || !accessible(player, pos)) return;
        long now = player.serverLevel().getGameTime();
        Long prev = LAST_CONTROL_REQUEST.get(player.getUUID());
        if (prev != null && now - prev < 1) return; // max 1 control op per tick per player
        LAST_CONTROL_REQUEST.put(player.getUUID(), now);

        if (!(player.serverLevel().getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) return;
        double reach = Math.max(16.0, marker.settings().interactReach());
        if (player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > reach * reach) return;

        ReplayBoardSessionState current = marker.session();
        if (current == null || !current.active()) {
            // Pressing PLAY on an idle table starts the latest round automatically
            if (packet.action() == ReplayBoardSessionState.Action.PLAY && latest != null) {
                ReplayBoardSessionState fresh = ReplayBoardSessionState.fresh(latest.packet.replay().roundId(), false, now + 4);
                marker.setSession(fresh);
                broadcastSession(player.serverLevel(), pos, fresh);
            }
            return;
        }
        int duration = Math.max(0, resolveDuration(current.roundId()));
        ReplayBoardSessionState updated = current.applyControl(
                packet.action(), now, duration, packet.value(), packet.speed(), packet.followPlayerUuid(), packet.flag());
        if (updated.endedToIdle()) {
            marker.setSession(null);
            broadcastSession(player.serverLevel(), pos, null);
        } else {
            marker.setSession(updated);
            broadcastSession(player.serverLevel(), pos, updated);
        }
    }

    public static void request(ServerPlayer p, RequestSummary packet) {
        if (!canRequest(p, packet.board()) || !ServerPlayNetworking.canSend(p, LastMatchSummaryS2C.TYPE)) return;
        UUID id = p.getUUID();
        long tick = server.overworld().getGameTime();
        Long previous = LAST_SUMMARY_REQUEST.get(id);
        if (previous != null && tick - previous < 5) return;
        LAST_SUMMARY_REQUEST.put(id, tick);
        if (latest == null || !latest.packet.replay().roundId().equals(packet.roundId())) {
            announce(p);
            return;
        }
        Transfer pending = TRANSFERS.get(id);
        if (pending == null && TRANSFERS.size() >= MAX_PENDING) return;
        if (pending != null && pending.round == latest && pending.indices == null && pending.board.equals(packet.board())) return;
        TRANSFERS.put(id, new Transfer(packet.board(), latest, (int[]) null));
    }

    public static void request(ServerPlayer p, ReplayMissingSectionsC2S packet) {
        if (!canRequest(p, packet.board()) || !ServerPlayNetworking.canSend(p, ReplaySectionPatchS2C.TYPE)) return;
        UUID id = p.getUUID();
        long tick = server.overworld().getGameTime();
        Long previous = LAST_SECTION_REQUEST.get(id);
        Transfer pending = TRANSFERS.get(id);
        if (pending != null && previous != null && tick - previous < 5) return;
        Published source = latest;
        BitSet bits = packet.missing();
        if (source == null || source.map == null || !source.packet.replay().roundId().equals(packet.roundId())
                || bits.isEmpty() || bits.cardinality() > MAX_REQUESTED || bits.length() > source.map.patches().size()
                || (!TRANSFERS.containsKey(id) && TRANSFERS.size() >= MAX_PENDING)) return;
        LAST_SECTION_REQUEST.put(id, tick);
        int[] indices = bits.stream().toArray();
        if (pending != null && pending.round == source && pending.board.equals(packet.board()) && pending.indices != null
                && Arrays.equals(pending.indices, pending.cursor, pending.indices.length, indices, 0, indices.length)) return;
        TRANSFERS.put(id, new Transfer(packet.board(), source, indices));
    }

    public static void request(ServerPlayer p, RequestVoiceC2S packet) {
        if (!canRequest(p, packet.board()) || !ServerPlayNetworking.canSend(p, ReplayVoiceChunkS2C.TYPE)) return;
        long tick = server.overworld().getGameTime();
        Long previous = LAST_VOICE_REQUEST.get(p.getUUID());
        if (previous != null && tick - previous < 5) return;
        LAST_VOICE_REQUEST.put(p.getUUID(), tick);
        Published source = latest;
        if (source == null || !source.packet.replay().roundId().equals(packet.roundId())) return;

        MinecraftServer owner = server;
        UUID recipient = p.getUUID();
        boolean admin = p.hasPermissions(4)
                || (!owner.isDedicatedServer() && owner.isSingleplayerOwner(p.getGameProfile()));
        if (!ReplayArchiveService.canExportVoice(source.packet.replay(), recipient, admin)
                || source.voice.bytes.length == 0 || !ServerPlayNetworking.canSend(p, VoiceExportAccessS2C.TYPE)) {
            ServerPlayNetworking.send(p, new ReplayVoiceChunkS2C(packet.roundId(), 0, 0, new byte[0]));
            return;
        }
        if (PENDING_VOICE.containsKey(recipient) || VOICE_TRANSFERS.containsKey(recipient)
                || PENDING_VOICE.size() + VOICE_TRANSFERS.size() >= MAX_PENDING || voiceWorker == null) return;
        if (admin) {
            enqueueVoice(recipient, packet.board(), source, source.voice.bytes);
            return;
        }
        PENDING_VOICE.put(recipient, new VoiceWork(owner, p, packet.board().immutable(), source));
    }

    private static void prepareNextVoice() {
        if (activeVoiceWork != null || PENDING_VOICE.isEmpty() || voiceWorker == null) return;
        var pending = PENDING_VOICE.firstEntry();
        VoiceWork work = pending.getValue();
        if (!work.canDeliver()) {
            PENDING_VOICE.pollFirstEntry();
            return;
        }
        // Wait for the server to consume the result before encoding another potentially large payload.
        activeVoiceWork = work;
        voiceWorker.execute(work);
    }

    private record VoiceWork(MinecraftServer owner, ServerPlayer player, BlockPos board, Published source)
            implements Runnable {
        @Override public void run() {
            UUID recipient = player.getUUID();
            byte[] voice = null;
            try {
                voice = source.voice.forRecipient(source.packet, recipient);
            } catch (RuntimeException failure) {
                LOG.warn("Could not prepare replay voice for {}", source.packet.replay().roundId(), failure);
            } finally {
                byte[] prepared = voice;
                owner.execute(() -> complete(recipient, prepared));
            }
        }

        private boolean canDeliver() {
            return server == owner && latest != null && latest.voice == source.voice
                    && canRequest(player, board);
        }

        private void complete(UUID recipient, byte @Nullable [] voice) {
            if (activeVoiceWork != this) return;
            activeVoiceWork = null;
            if (PENDING_VOICE.get(recipient) != this) return;
            PENDING_VOICE.remove(recipient);
            if (!canDeliver()) return;
            if (voice == null || voice.length == 0) {
                ServerPlayNetworking.send(player, new ReplayVoiceChunkS2C(source.packet.replay().roundId(), 0, 0, new byte[0]));
            } else {
                enqueueVoice(recipient, board, source, voice);
            }
        }
    }

    private static void enqueueVoice(UUID recipient, BlockPos board, Published source, byte[] voice) {
        long inFlight = VOICE_TRANSFERS.values().stream().mapToLong(transfer -> transfer.voice.length).sum();
        if (inFlight + voice.length <= MAX_VOICE_TRANSFER_BYTES) {
            VOICE_TRANSFERS.put(recipient, new Transfer(board, source, voice));
        }
    }

    private static boolean canRequest(ServerPlayer p, BlockPos board) {
        return server != null && server == p.getServer() && server.getPlayerList().getPlayer(p.getUUID()) == p
                && accessible(p, board);
    }

    private static boolean accessible(ServerPlayer p, BlockPos board) {
        if (p.serverLevel().isOutsideBuildHeight(board)) return false;
        var chunk = p.serverLevel().getChunkSource().getChunkNow(board.getX() >> 4, board.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(board) instanceof SettlementSummaryBoard.Marker marker
                && marker.settings().inView(board, marker.getBlockState().getValue(SettlementSummaryBoard.FACING), p.getEyePosition());
    }

    private static void tick(MinecraftServer s) {
        prepareNextVoice();
        deliver(s, TRANSFERS);
        deliver(s, VOICE_TRANSFERS);
    }

    private static void deliver(MinecraftServer s, Map<UUID, Transfer> transfers) {
        if (transfers.isEmpty()) return;
        long now = BANDWIDTH.nowNanos();
        var iterator = transfers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var t = entry.getValue();
            if (t.ready != null && now - t.ready < 0) continue;
            ServerPlayer player = s.getPlayerList().getPlayer(entry.getKey());
            if (player == null || latest == null || !latest.packet.replay().roundId().equals(t.round.packet.replay().roundId())
                    || !accessible(player, t.board)) {
                iterator.remove();
                continue;
            }
            if (t.voice != null && TRANSFERS.containsKey(entry.getKey())) continue;
            if (t.ready == null) {
                int size = t.voice != null ? Math.min(FRAGMENT_BYTES, t.voice.length - t.offset) + 32
                        : t.indices == null ? t.round.bytes : t.prepareSectionBatch();
                t.ready = BANDWIDTH.reserve(entry.getKey(), size, PER_PLAYER);
            }
            if (BANDWIDTH.nowNanos() - t.ready < 0) continue;
            if (t.voice != null) {
                byte[] voice = t.voice;
                int end = Math.min(t.offset + FRAGMENT_BYTES, voice.length);
                ServerPlayNetworking.send(player, new ReplayVoiceChunkS2C(t.round.packet.replay().roundId(),
                        t.offset, voice.length, Arrays.copyOfRange(voice, t.offset, end)));
                t.ready = null;
                t.offset = end;
                if (end >= voice.length) iterator.remove();
                continue;
            }
            if (t.indices == null) {
                ServerPlayNetworking.send(player, t.round.packet);
                iterator.remove();
                continue;
            }
            for (int fragment = 0; fragment < t.batchFragments; fragment++) {
                byte[] patch = t.round.map.patches().get(t.indices[t.cursor]);
                int end = Math.min(t.offset + FRAGMENT_BYTES, patch.length);
                ServerPlayNetworking.send(player, new ReplaySectionPatchS2C(t.round.packet.replay().roundId(),
                        t.indices[t.cursor], t.offset, patch.length, Arrays.copyOfRange(patch, t.offset, end)));
                t.offset = end;
                if (end == patch.length) {
                    t.offset = 0;
                    t.cursor++;
                }
            }
            t.ready = null;
            if (t.cursor == t.indices.length) iterator.remove();
        }
    }

    private static final class Transfer {
        final BlockPos board;
        final Published round;
        final int[] indices;
        final @Nullable byte[] voice;
        Long ready;
        int cursor, offset, batchFragments;
        Transfer(BlockPos board, Published round, int[] indices) {
            this.board = board.immutable(); this.round = round; this.indices = indices; this.voice = null;
        }
        Transfer(BlockPos board, Published round, byte[] voice) {
            this.board = board.immutable(); this.round = round; this.indices = null; this.voice = voice;
        }

        int prepareSectionBatch() {
            batchFragments = 0;
            int size = 0;
            int index = cursor;
            int bytesSent = offset;
            while (index < indices.length && batchFragments < SECTION_BATCH_FRAGMENTS) {
                byte[] patch = round.map.patches().get(indices[index]);
                int bytes = Math.min(FRAGMENT_BYTES, patch.length - bytesSent);
                if (size + bytes + 32 > SECTION_BATCH_BYTES) break;
                size += bytes + 32;
                batchFragments++;
                bytesSent += bytes;
                if (bytesSent == patch.length) {
                    index++;
                    bytesSent = 0;
                }
            }
            return size;
        }
    }
}
