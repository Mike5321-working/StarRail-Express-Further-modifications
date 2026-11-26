package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.config.MenuGateService;
import com.habitrain.core.network.replay.ReplayArchiveData;
import com.habitrain.core.network.replay.ReplayArchivePackets;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.network.replay.VisualPackets;
import com.habitrain.core.network.replay.VisualTape;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.server.SceneBandwidthGovernor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import static com.habitrain.core.network.replay.ReplayArchivePackets.*;

public final class ReplayArchiveService {
    private static final Logger LOG = LoggerFactory.getLogger(ReplayArchiveService.class);
    private static final int CAPACITY = 16;
    private static final long WRITE_BUDGET_BYTES = 128L * 1024 * 1024;
    private static final long RATE = 768 * 1024L, GLOBAL = 4L * 1024 * 1024;
    private static volatile State state;

    private ReplayArchiveService() {}

    public static void register() {
        ReplayArchivePackets.register();
        VisualPackets.register();
        ServerPlayNetworking.registerGlobalReceiver(VisualPackets.Frame.TYPE, (packet, ctx) -> MatchReplayRecorder.visualFrame(ctx.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(VisualPackets.Request.TYPE, (packet, ctx) -> {
            var current = state;
            if (current != null && current.server == ctx.server()) current.visualPage(ctx.player(), packet);
        });
        com.habitrain.core.api.match.MatchEvents.STARTED.register(level -> {
            State current = state;
            if (current != null && current.server == level.getServer()) {
                for (var player : level.players()) current.exports.started(player);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ReplayPackets.ExportPresenceC2S.TYPE, (packet, ctx) ->
                ctx.server().execute(() -> {
                    State current = state;
                    if (current == null || current.server != ctx.server()
                            || ctx.server().getPlayerList().getPlayer(ctx.player().getUUID()) != ctx.player()) return;
                    current.exports.presence(ctx.player().getUUID(), packet.session(), packet.open());
                }));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> state = new State(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // Flush any pending post-match voice tail BEFORE shutting down the archive I/O queue (Defect #14).
            MatchReplayRecorder.flushPendingBeforeStop();
            State s = state;
            if (s != null && s.server == server) {
                state = null;
                s.io.shutdown();
                try {
                    if (!s.io.awaitTermination(8, TimeUnit.SECONDS)) LOG.warn("Replay archive writes still draining");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            State s = state;
            if (s != null && s.server == server) {
                s.tick();
                if (server.getTickCount() % 5 == 0) s.exports.poll(server);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            State s = state;
            if (s != null) {
                UUID id = h.player.getUUID();
                s.exports.disconnect(id);
                s.summaryLane.remove(id);
                s.voiceLane.remove(id);
                s.visualLane.remove(id);
                s.throttle.remove(id);
                s.lastShare.remove(id);
                s.lastExport.remove(id);
                s.lastVisual.remove(id);
                s.lastRecording.remove(id);
                s.viewed.remove(id);
                s.bandwidth.forget(id);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (request, ctx) -> ctx.server().execute(() -> {
            if (state != null && state.server == ctx.server()) state.receive(ctx.player(), request);
        }));
        ServerPlayNetworking.registerGlobalReceiver(ReplayPackets.RequestVoiceExportC2S.TYPE, (request, ctx) -> ctx.server().execute(() -> {
            if (state != null && state.server == ctx.server()) state.exportAccess(ctx.player(), request.roundId());
        }));
        ServerPlayNetworking.registerGlobalReceiver(SetVoiceRecording.TYPE, (request, ctx) -> ctx.server().execute(() -> {
            if (state != null && state.server == ctx.server()) state.setVoiceRecording(ctx.player(), request.enabled());
        }));
        ServerPlayNetworking.registerGlobalReceiver(CheckedLimit.TYPE, (request, ctx) -> ctx.server().execute(() -> {
            if (state != null && state.server == ctx.server() && ReplayArchive.validLimit(request.expected())) {
                state.receive(ctx.player(), Request.settings(LIMIT, request.limit()), request.expected());
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(ReplayPackets.ShareSessionC2S.TYPE, (request, ctx) -> ctx.server().execute(() -> {
            if (state != null && state.server == ctx.server()) state.receiveShareSession(ctx.player(), request);
        }));
    }

    /** Handles seamless stateful handoff from backpack history preview to a nearby lobby replay table. */
    public static void shareSession(ServerPlayer player, ReplayPackets.ShareSessionC2S packet) {
        State s = state;
        if (s != null && s.server == player.getServer()) {
            s.receiveShareSession(player, packet);
        }
    }

    static boolean shouldRecordVoice(MinecraftServer server) {
        State active = state;
        return active != null && active.server == server && active.voicePolicyReady && active.recordVoice;
    }

    static boolean canExportVoice(LastMatchReplay replay, UUID player, boolean admin) {
        return admin || replay.participants().stream().anyMatch(participant -> participant.id().equals(player));
    }

    /** Returns cached duration ticks for a recently viewed or shared archived round, or -1 if unknown. */
    static int cachedDuration(UUID roundId) {
        State s = state;
        if (s == null || roundId == null) return -1;
        Integer dur = s.durationCache.get(roundId);
        return dur == null ? -1 : dur;
    }

    /** Checks whether any loaded replay table near {@code player} is currently actively playing {@code roundId}. */
    static boolean isNearActiveBoardPlaying(ServerPlayer player, UUID roundId) {
        if (player == null || roundId == null || player.isSpectator()) return false;
        ServerLevel level = player.serverLevel();
        State s = state;
        if (s != null) {
            for (var prep : s.preparing.values()) {
                if (prep.level == level && prep.round.equals(roundId) && State.near(player, level, prep.marker.getBlockPos())) {
                    return true;
                }
            }
            for (var entry : s.activeSharedBoards.entrySet()) {
                BlockPos pos = entry.getKey();
                if (roundId.equals(entry.getValue()) && State.near(player, level, pos)) {
                    var be = level.getBlockEntity(pos);
                    if (be instanceof SettlementSummaryBoard.Marker m && m.session() != null
                            && m.session().active() && m.session().roundId().equals(roundId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static CompletableFuture<Void> archive(MinecraftServer owner, ReplayPackets.LastMatchSummaryS2C data,
                                           ReplayMapCapture.Result map, long finishedAt, byte[] voice, VisualTape.Builder visuals) {
        State s = state;
        if (s == null || s.server != owner) return CompletableFuture.failedFuture(new IOException("Replay archive is closed"));
        s.rememberDuration(data.replay().roundId(), data.replay().durationTicks());
        return s.io.submitSettlement(map.bytes() + ReplayPackets.MAX_SUMMARY + (long) voice.length + visuals.size(), () -> {
            if (s.archive == null) throw new IOException(s.lockBusy
                    ? "A previous replay archive writer is still draining" : "Replay archive initialization failed");
            if (!s.archive.save(data.replay().roundId(), finishedAt, map.manifest().hash(),
                    ReplayArchiveData.encode(data), map.patches(), voice, visuals.compress(data.replay().participants().stream().map(LastMatchReplay.Participant::id).collect(java.util.stream.Collectors.toSet())))) {
                throw new IOException("Replay does not fit the archive quota");
            }
            return null;
        });
    }

    /**
     * Pure, Minecraft-free per-action throttle separating LOOKUP / SECTIONS / VOICE (50 ms)
     * from admin control actions (200 ms), so immediate SECTIONS / VOICE follow-ups after a
     * Summary are never silently dropped (Defect #14 fix).
     */
    static final class RequestThrottle {
        static final long FAST_INTERVAL_NANOS = 50_000_000L;
        static final long CONTROL_INTERVAL_NANOS = 200_000_000L;

        private final Map<UUID, Long> lastLookup = new HashMap<>();
        private final Map<UUID, Long> lastSections = new HashMap<>();
        private final Map<UUID, Long> lastVoice = new HashMap<>();
        private final Map<UUID, Long> lastStats = new HashMap<>();
        private final Map<UUID, Long> lastLimit = new HashMap<>();

        boolean allow(int action, UUID player, long nowNanos) {
            Map<UUID, Long> bucket = switch (action) {
                case LOOKUP -> lastLookup;
                case SECTIONS -> lastSections;
                case VOICE -> lastVoice;
                case STATS -> lastStats;
                case LIMIT -> lastLimit;
                default -> null;
            };
            if (bucket == null) return true;
            long minInterval = (action == LOOKUP || action == SECTIONS || action == VOICE)
                    ? FAST_INTERVAL_NANOS : CONTROL_INTERVAL_NANOS;
            Long previous = bucket.get(player);
            if (previous != null && nowNanos - previous < minInterval) return false;
            bucket.put(player, nowNanos);
            return true;
        }

        void remove(UUID player) {
            lastLookup.remove(player);
            lastSections.remove(player);
            lastVoice.remove(player);
            lastStats.remove(player);
            lastLimit.remove(player);
        }
    }

    /**
     * Pure, Minecraft-free transfer lane state machine backing summary/section and voice streams.
     * Each {@link #begin(UUID)} issues a strictly monotonic epoch that supersedes any in-flight
     * async fetch or partially drained stream for that player without affecting other lanes (Defect #14).
     */
    static final class TransferLane<T> {
        private static final class Slot<T> {
            final long epoch;
            @Nullable Iterator<T> packets;
            @Nullable T next;
            final ArrayList<T> batch = new ArrayList<>();
            long readyAtNanos;

            Slot(long epoch) {
                this.epoch = epoch;
            }
        }

        private final int capacity;
        private final Map<UUID, Slot<T>> slots = new LinkedHashMap<>();
        private long nextEpoch;

        TransferLane(int capacity) {
            this.capacity = Math.max(1, capacity);
        }

        /**
         * Starts or supersedes a transfer for {@code player}.
         * Returns a positive monotonic epoch, or {@code -1L} when full for a new player.
         */
        long begin(UUID player) {
            if (!slots.containsKey(player) && slots.size() >= capacity) return -1L;
            long epoch = ++nextEpoch;
            slots.put(player, new Slot<>(epoch));
            return epoch;
        }

        /**
         * Installs fetched packets only if {@code epoch} is still current and still awaiting I/O.
         * Returns {@code false} (dropping stale async results) if superseded or cancelled.
         */
        boolean installIfCurrent(UUID player, long epoch, Iterator<T> packets) {
            Slot<T> slot = slots.get(player);
            if (slot == null || slot.epoch != epoch || slot.packets != null) return false;
            if (packets == null || !packets.hasNext()) {
                slots.remove(player);
                return true;
            }
            slot.packets = packets;
            return true;
        }

        boolean isCurrent(UUID player, long epoch) {
            Slot<T> slot = slots.get(player);
            return slot != null && slot.epoch == epoch;
        }

        boolean cancelIfCurrent(UUID player, long epoch) {
            Slot<T> slot = slots.get(player);
            if (slot == null || slot.epoch != epoch) return false;
            slots.remove(player);
            return true;
        }

        boolean hasActive(UUID player) {
            return slots.containsKey(player);
        }

        boolean hasReady(UUID player) {
            Slot<T> slot = slots.get(player);
            return slot != null && slot.packets != null;
        }

        int size() {
            return slots.size();
        }

        void remove(UUID player) {
            slots.remove(player);
        }

        /**
         * Drains the next ready item for {@code player} without bandwidth pacing (useful for unit tests).
         * Returns {@code null} if idle or still awaiting async I/O; clears the slot when exhausted.
         */
        @Nullable
        T poll(UUID player) {
            Slot<T> slot = slots.get(player);
            if (slot == null || slot.packets == null) return null;
            T out;
            if (!slot.batch.isEmpty()) {
                out = slot.batch.removeFirst();
                if (slot.batch.isEmpty()) slot.readyAtNanos = 0L;
            } else if (slot.next != null) {
                out = slot.next;
                slot.next = null;
            } else if (slot.packets.hasNext()) {
                out = slot.packets.next();
            } else {
                slots.remove(player);
                return null;
            }
            if (slot.batch.isEmpty() && slot.next == null && !slot.packets.hasNext()) {
                slots.remove(player);
            }
            return out;
        }

        void drain(Predicate<UUID> alive,
                   Predicate<UUID> defer,
                   ToIntFunction<T> byteEstimator,
                   Predicate<T> sectionPacket,
                   SceneBandwidthGovernor bandwidth,
                   long rateBytesPerSec,
                   BiConsumer<UUID, T> sender) {
            var iterator = slots.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                UUID id = entry.getKey();
                Slot<T> slot = entry.getValue();
                if (!alive.test(id)) {
                    iterator.remove();
                    continue;
                }
                if (slot.packets == null) continue;
                if (defer.test(id)) continue;
                if (slot.batch.isEmpty()) {
                    if (slot.next == null && !slot.packets.hasNext()) {
                        iterator.remove();
                        continue;
                    }
                    T first = slot.next == null ? slot.packets.next() : slot.next;
                    slot.next = null;
                    slot.batch.add(first);
                    int bytes = byteEstimator.applyAsInt(first);
                    if (sectionPacket.test(first)) {
                        while (slot.batch.size() < ReplayPackets.SECTION_BATCH_FRAGMENTS && slot.packets.hasNext()) {
                            T next = slot.packets.next();
                            int size = byteEstimator.applyAsInt(next);
                            if (bytes + size > ReplayPackets.SECTION_BATCH_BYTES || !sectionPacket.test(next)) {
                                slot.next = next;
                                break;
                            }
                            bytes += size;
                            slot.batch.add(next);
                        }
                    }
                    slot.readyAtNanos = bandwidth.reserve(id, bytes, rateBytesPerSec);
                }
                if (bandwidth.nowNanos() - slot.readyAtNanos < 0) continue;
                for (T packet : slot.batch) sender.accept(id, packet);
                slot.batch.clear();
                if (slot.next == null && !slot.packets.hasNext()) {
                    iterator.remove();
                }
            }
        }
    }

    private record ShareParams(double currentTick, float speed, boolean paused,
                               @Nullable UUID followPlayerUuid, boolean killCloseupEnabled, boolean roofCutaway) {
        static final ShareParams DEFAULT = new ShareParams(0.0, ReplayBoardSessionState.DEFAULT_SPEED, false, null, false, true);
    }

    private record Preparing(ServerLevel level, SettlementSummaryBoard.Marker marker, UUID round,
                             int durationTicks, ShareParams params, long deadline, Set<UUID> waiting) {}

    private static final class State {
        final ExportMatchSessions exports = new ExportMatchSessions();
        final MinecraftServer server;
        final ReplayArchiveQueue io;
        final Path root;
        final TransferLane<CustomPacketPayload> summaryLane = new TransferLane<>(CAPACITY);
        final TransferLane<CustomPacketPayload> voiceLane = new TransferLane<>(CAPACITY / 2);
        final RequestThrottle throttle = new RequestThrottle();
        final Map<SettlementSummaryBoard.Marker, Preparing> preparing = new HashMap<>();
        final Map<BlockPos, UUID> activeSharedBoards = new ConcurrentHashMap<>();
        final Map<UUID, Integer> durationCache = new ConcurrentHashMap<>();
        final Set<UUID> sharing = new HashSet<>();
        final Map<UUID, Long> lastShare = new HashMap<>();
        final Map<UUID, Long> lastVisual = new HashMap<>();
        final TransferLane<CustomPacketPayload> visualLane = new TransferLane<>(CAPACITY);

        void visualPage(ServerPlayer player, VisualPackets.Request request) {
            UUID id = player.getUUID(); long now = System.nanoTime();
            if (!online(player) || !ServerPlayNetworking.canSend(player, VisualPackets.Page.TYPE) || request.offset() < 0 || request.offset() > VisualTape.MAX_STREAM + VisualTape.PAGE || request.offset() % VisualTape.PAGE != 0 || now - lastVisual.getOrDefault(id, now - 1000000000L) < 50000000L || visualLane.hasActive(id)) return;
            long epoch=visualLane.begin(id);
            if (epoch<0) return;
            lastVisual.put(id, now);
            submit(() -> {
                VisualPackets.Page page;
                try { page = archive == null ? null : archive.visualPage(request); }
                catch (IOException failure) { LOG.warn("Unreadable replay visual stream {}", request.round(), failure); page = null; }
                var result = page == null ? new VisualPackets.Page(request.round(), request.subject(), request.offset(), -2, new byte[0]) : page;
                server.execute(() -> { if (online(player)) visualLane.installIfCurrent(id,epoch,List.<CustomPacketPayload>of(result).iterator()); else visualLane.cancelIfCurrent(id,epoch); });
            }, () -> server.execute(() -> visualLane.cancelIfCurrent(id,epoch)));
        }
        final Map<UUID, Long> lastExport = new HashMap<>(), lastRecording = new HashMap<>();
        final Map<UUID, LinkedHashSet<UUID>> viewed = new HashMap<>();
        final SceneBandwidthGovernor bandwidth = new SceneBandwidthGovernor(System::nanoTime, GLOBAL);
        ReplayArchive archive;
        ReplayArchiveOwnership ownership;
        volatile boolean lockBusy;
        volatile boolean openPending;
        volatile long nextOpenTick;
        volatile int retryDelayTicks = 20;
        volatile boolean recordVoice;
        volatile boolean voicePolicyReady;

        State(MinecraftServer server) {
            this.server = server;
            root = server.getWorldPath(LevelResource.ROOT).resolve("habitrain").resolve("replay-archive");
            recordVoice = readVoicePolicy(root);
            voicePolicyReady = true;
            io = new ReplayArchiveQueue(32, WRITE_BUDGET_BYTES, this::releaseOwnership);
            openArchive();
        }

        void rememberDuration(UUID roundId, int durationTicks) {
            if (roundId == null || durationTicks < 0) return;
            if (durationCache.size() > 128) durationCache.clear();
            durationCache.put(roundId, durationTicks);
        }

        private static boolean readVoicePolicy(Path root) {
            Path config = root.resolve("record-voice.txt");
            try {
                return !Files.isRegularFile(config) || Files.size(config) > 32
                        || !Files.readString(config).strip().equalsIgnoreCase("false");
            } catch (IOException | RuntimeException failure) {
                return true;
            }
        }

        void openArchive() {
            if (archive != null || openPending || io.isShutdown()) return;
            openPending = true;
            submit(() -> {
                try {
                    ownership = ReplayArchiveOwnership.tryAcquire(root);
                    if (ownership == null) {
                        lockBusy = true;
                        LOG.warn("Replay archive is already owned, will retry: {}", root);
                        return;
                    }
                    ReplayArchive opened = new ReplayArchive(root);
                    boolean openedRecordVoice = opened.recordVoice();
                    archive = opened;
                    recordVoice = openedRecordVoice;
                    lockBusy = false;
                    retryDelayTicks = 20;
                    LOG.debug("Opened replay archive at {} (rounds={}, quarantined={})",
                            root, opened.roundCount(), opened.quarantinedCount());
                } catch (Exception failure) {
                    lockBusy = false;
                    LOG.error("Could not initialize replay archive at {}, will retry", root, failure);
                } finally {
                    if (archive == null) releaseOwnership();
                    openPending = false;
                    if (archive == null) {
                        nextOpenTick = server.getTickCount() + retryDelayTicks;
                        retryDelayTicks = Math.min(retryDelayTicks * 2, 20 * 60);
                    }
                }
            }, () -> {
                openPending = false;
                lockBusy = !io.isShutdown();
                if (lockBusy) {
                    nextOpenTick = server.getTickCount() + retryDelayTicks;
                    retryDelayTicks = Math.min(retryDelayTicks * 2, 20 * 60);
                }
            });
        }

        void releaseOwnership() {
            if (ownership == null) return;
            try { ownership.close(); }
            catch (IOException failure) { LOG.warn("Could not close replay archive writer lock for {}", root, failure); }
            finally { ownership = null; }
        }

        void submit(Work work, Runnable rejected) {
            try {
                io.submitRead(() -> {
                    try { work.run(); }
                    catch (Exception failure) { LOG.warn("Replay archive I/O failed", failure); }
                });
            } catch (RejectedExecutionException busy) { rejected.run(); }
        }

        boolean online(ServerPlayer p) {
            return state == this && server.getPlayerList().getPlayer(p.getUUID()) == p;
        }

        void receive(ServerPlayer p, Request r) {
            receive(p, r, 0);
        }

        void receiveShareSession(ServerPlayer p, ReplayPackets.ShareSessionC2S packet) {
            ShareParams params = new ShareParams(
                    Double.isFinite(packet.currentTick()) ? Math.max(0.0, packet.currentTick()) : 0.0,
                    ReplayBoardSessionState.sanitizeSpeed(packet.speed()),
                    packet.paused(),
                    packet.followPlayerUuid(),
                    packet.killCloseupEnabled(),
                    packet.roofCutaway());
            receiveShareInternal(p, Request.share(packet.roundId(), packet.board()), params);
        }

        void receive(ServerPlayer p, Request r, long expectedLimit) {
            if (!online(p) || !ServerPlayNetworking.canSend(p, Reply.TYPE)) return;
            if (r.action() == BOARD_READY) {
                for (Preparing work : preparing.values()) {
                    if (near(p, work.level, r.board()) && work.marker.getBlockPos().equals(r.board()) && work.round.equals(r.round())) {
                        work.waiting.remove(p.getUUID());
                        break;
                    }
                }
                return;
            }
            if (r.action() == SHARE) {
                receiveShareInternal(p, r, ShareParams.DEFAULT);
                return;
            }
            if (!throttle.allow(r.action(), p.getUUID(), System.nanoTime())) return;

            if (r.action() == STATS || r.action() == LIMIT) {
                settings(p, r, expectedLimit);
                return;
            }
            if (r.round().equals(ReplayPackets.EMPTY)) return;
            if (r.action() == VOICE) {
                if (!ServerPlayNetworking.canSend(p, Voice.TYPE)) return;
                if (!ServerPlayNetworking.canSend(p, ReplayPackets.VoiceExportAccessS2C.TYPE)) {
                    ServerPlayNetworking.send(p, new Voice(new ReplayPackets.ReplayVoiceChunkS2C(r.round(), 0, 0, new byte[0])));
                    return;
                }
                Set<UUID> visible = viewed.get(p.getUUID());
                boolean allowedViaBoard = isNearActiveBoardPlaying(p, r.round());
                if ((visible == null || !visible.contains(r.round())) && !allowedViaBoard) {
                    ServerPlayNetworking.send(p, new Reply(r.round(), ERROR));
                    return;
                }
            }
            if (r.action() == SECTIONS && (r.missing().isEmpty() || r.missing().cardinality() > ReplayPackets.MAX_REQUESTED)) return;

            UUID id = p.getUUID();
            boolean isVoice = (r.action() == VOICE);
            TransferLane<CustomPacketPayload> lane = isVoice ? voiceLane : summaryLane;
            // Cancel any stale transfer for this player cleanly instead of silently dropping (Defect #14 fix).
            long epoch = lane.begin(id);
            if (epoch < 0L) {
                ServerPlayNetworking.send(p, new Reply(r.round(), ERROR));
                return;
            }

            boolean isAdmin = admin(p);

            submit(() -> {
                List<CustomPacketPayload> packets;
                try {
                    packets = fetch(r, id, isAdmin);
                } catch (Exception failed) {
                    LOG.debug("Replay request failed for {}", r.round(), failed);
                    packets = List.of(new Reply(r.round(), ERROR));
                }
                List<CustomPacketPayload> fetched = packets;
                server.execute(() -> {
                    if (!online(p)) {
                        lane.cancelIfCurrent(id, epoch);
                        return;
                    }
                    lane.installIfCurrent(id, epoch, fetched.iterator());
                });
            }, () -> server.execute(() -> {
                if (lane.cancelIfCurrent(id, epoch) && online(p)) {
                    ServerPlayNetworking.send(p, new Reply(r.round(), ERROR));
                }
            }));
        }

        private void receiveShareInternal(ServerPlayer p, Request r, ShareParams params) {
            if (!online(p) || !ServerPlayNetworking.canSend(p, Reply.TYPE)) return;
            long now = System.nanoTime();
            Long previous = lastShare.get(p.getUUID());
            if (previous != null && now - previous < 200_000_000L) return;
            lastShare.put(p.getUUID(), now);
            if (r.round().equals(ReplayPackets.EMPTY)) return;

            ServerLevel level = p.serverLevel();
            if (board(p, r.board(), level) == null) {
                ServerPlayNetworking.send(p, new Reply(r.round(), NO_TABLE));
                return;
            }
            UUID id = p.getUUID();
            if (preparing.size() >= CAPACITY || !sharing.add(id)) {
                ServerPlayNetworking.send(p, new Reply(r.round(), TABLE_BUSY));
                return;
            }
            submit(() -> {
                List<CustomPacketPayload> packets;
                try {
                    packets = fetch(r, id, true);
                } catch (Exception failed) {
                    LOG.debug("Replay share lookup failed for {}", r.round(), failed);
                    packets = List.of(new Reply(r.round(), SHARE_ERROR));
                }
                List<CustomPacketPayload> fetched = packets;
                server.execute(() -> {
                    sharing.remove(id);
                    if (!online(p)) return;
                    if (!fetched.isEmpty() && fetched.getFirst() instanceof Summary summary) {
                        share(p, r, level, summary.data(), params);
                    } else {
                        ServerPlayNetworking.send(p, new Reply(r.round(), SHARE_ERROR));
                    }
                });
            }, () -> server.execute(() -> {
                sharing.remove(id);
                if (online(p)) ServerPlayNetworking.send(p, new Reply(r.round(), SHARE_ERROR));
            }));
        }

        List<CustomPacketPayload> fetch(Request r, UUID requesterId, boolean isAdmin) throws Exception {
            UUID round = r.round();
            if (archive == null) return List.of(new Reply(round, ERROR));
            if (r.action() == VOICE) {
                ReplayPackets.LastMatchSummaryS2C summary = null;
                if (!isAdmin) {
                    byte[] rawSum = archive.summary(round);
                    if (rawSum == null) return List.of(new Reply(round, MISSING));
                    summary = ReplayArchiveData.decode(rawSum);
                    rememberDuration(round, summary.replay().durationTicks());
                    if (!canExportVoice(summary.replay(), requesterId, false)) {
                        return List.of(new Reply(round, ERROR));
                    }
                }
                byte[] voice = archive.voice(round);
                if (voice == null) return List.of(new Reply(round, MISSING));
                if (summary != null) voice = ReplayVoiceDelivery.forRecipient(summary, voice, requesterId, false);
                if (voice.length == 0) {
                    return List.of(new Voice(new ReplayPackets.ReplayVoiceChunkS2C(round, 0, 0, new byte[0])));
                }
                List<CustomPacketPayload> packets = new ArrayList<>();
                for (int off = 0; off < voice.length; off += ReplayPackets.FRAGMENT_BYTES) {
                    int end = Math.min(voice.length, off + ReplayPackets.FRAGMENT_BYTES);
                    packets.add(new Voice(new ReplayPackets.ReplayVoiceChunkS2C(round, off, voice.length,
                            Arrays.copyOfRange(voice, off, end))));
                }
                return packets;
            }
            if (r.action() == SECTIONS) {
                var patches = archive.sections(round, r.missing());
                if (patches == null) return List.of(new Reply(round, MISSING));
                List<CustomPacketPayload> packets = new ArrayList<>();
                patches.forEach((idx, data) -> {
                    for (int off = 0; off < data.length; off += ReplayPackets.FRAGMENT_BYTES) {
                        int end = Math.min(data.length, off + ReplayPackets.FRAGMENT_BYTES);
                        packets.add(new Section(new ReplayPackets.ReplaySectionPatchS2C(round, idx, off, data.length,
                                Arrays.copyOfRange(data, off, end))));
                    }
                });
                return packets;
            }
            byte[] raw = archive.summary(round);
            if (raw == null) return List.of(new Reply(round, MISSING));
            var data = ReplayArchiveData.decode(raw);
            if (!round.equals(data.replay().roundId()) || data.replay().manifest() == null) return List.of(new Reply(round, ERROR));
            rememberDuration(round, data.replay().durationTicks());
            if (r.action() == LOOKUP && !r.digest().isEmpty() && r.digest().equals(ReplayArchiveData.digest(raw))) {
                return List.of(new Reply(round, CACHED));
            }
            return List.of(new Summary(data));
        }

        boolean admin(ServerPlayer player) {
            return player.hasPermissions(4)
                    || !server.isDedicatedServer() && server.isSingleplayerOwner(player.getGameProfile());
        }

        boolean editable(ServerPlayer player) {
            return admin(player) && (!server.isDedicatedServer() || !MenuGateService.isEnabled() || MenuGateService.isAllowed(player));
        }

        boolean allowControl(ServerPlayer player, Map<UUID, Long> requests) {
            if (!online(player)) return false;
            UUID id = player.getUUID();
            long now = System.nanoTime();
            Long previous = requests.get(id);
            if (previous != null && now - previous < 200_000_000L) return false;
            requests.put(id, now);
            return true;
        }

        void exportAccess(ServerPlayer player, UUID round) {
            if (!ServerPlayNetworking.canSend(player, ReplayPackets.VoiceExportAccessS2C.TYPE)
                    || !allowControl(player, lastExport) || ReplayPackets.EMPTY.equals(round)) return;
            UUID id = player.getUUID();
            boolean admin = admin(player);
            LastMatchReplay latest = ReplayBoardService.latestReplay(round);
            if (latest != null) {
                sendExportAccess(player, round, canExportVoice(latest, id, admin));
                return;
            }
            submit(() -> {
                boolean allowed = false;
                byte[] summary = archive == null ? null : archive.summary(round);
                if (summary != null) {
                    try {
                        LastMatchReplay decoded = ReplayArchiveData.decode(summary).replay();
                        rememberDuration(round, decoded.durationTicks());
                        allowed = canExportVoice(decoded, id, admin);
                    } catch (RuntimeException malformed) {
                        LOG.warn("Could not inspect replay voice export access for {}", round, malformed);
                    }
                }
                boolean result = allowed;
                server.execute(() -> { if (online(player)) sendExportAccess(player, round, result); });
            }, () -> LOG.debug("Replay archive busy checking voice export access for {}; client may retry", round));
        }

        void sendExportAccess(ServerPlayer player, UUID round, boolean allowed) {
            if (allowed) {
                var recent = viewed.computeIfAbsent(player.getUUID(), ignored -> new LinkedHashSet<>());
                recent.addLast(round);
                if (recent.size() > 32) recent.removeFirst();
            }
            ServerPlayNetworking.send(player, new ReplayPackets.VoiceExportAccessS2C(round, allowed));
        }

        void setVoiceRecording(ServerPlayer player, boolean enabled) {
            if (!ServerPlayNetworking.canSend(player, VoiceRecordingSettings.TYPE)
                    || !allowControl(player, lastRecording)) return;
            boolean editable = editable(player);
            if (!editable) {
                sendRecordingSettings(player, false, false);
                return;
            }
            submit(() -> {
                boolean saved = false;
                try {
                    if (archive != null) {
                        archive.setRecordVoice(enabled);
                        saved = true;
                    }
                } catch (Exception failure) {
                    LOG.warn("Could not update replay voice recording", failure);
                }
                boolean ok = saved;
                server.execute(() -> {
                    if (state != this) return;
                    if (ok) {
                        recordVoice = enabled;
                        if (!enabled) MatchVoiceRecorder.reset();
                    }
                    if (online(player)) sendRecordingSettings(player, editable, ok);
                });
            }, () -> sendRecordingSettings(player, editable, false));
        }

        void sendRecordingSettings(ServerPlayer player, boolean editable, boolean ok) {
            if (ServerPlayNetworking.canSend(player, VoiceRecordingSettings.TYPE)) {
                ServerPlayNetworking.send(player, new VoiceRecordingSettings(recordVoice, editable, ok));
            }
        }

        void settings(ServerPlayer p, Request r, long expectedLimit) {
            if (!ServerPlayNetworking.canSend(p, Settings.TYPE)) return;
            boolean editable = editable(p);
            submit(() -> {
                boolean ok = archive != null;
                try {
                    if (r.action() == LIMIT) {
                        ok = ok && editable && ReplayArchive.validLimit(r.limit())
                                && (expectedLimit == 0 || archive.limit() == expectedLimit);
                        if (ok) archive.setLimit(r.limit());
                    }
                } catch (Exception failed) {
                    ok = false;
                    LOG.warn("Could not update replay archive limit", failed);
                }
                Settings info = new Settings(
                        archive == null ? 0 : archive.used(),
                        archive == null ? ReplayArchive.DEFAULT_LIMIT : archive.limit(),
                        root.toAbsolutePath().normalize().toString(),
                        editable, ok,
                        archive == null ? 0 : archive.roundCount(),
                        archive == null ? 0 : archive.quarantinedCount(),
                        archive == null ? 0L : archive.quarantinedBytes());
                server.execute(() -> {
                    if (!online(p)) return;
                    ServerPlayNetworking.send(p, info);
                    if (r.action() == STATS) sendRecordingSettings(p, editable, info.ok());
                });
            }, () -> {
                ServerPlayNetworking.send(p, new Settings(0, ReplayArchive.DEFAULT_LIMIT, "", editable, false, 0, 0, 0L));
                if (r.action() == STATS) sendRecordingSettings(p, editable, false);
            });
        }

        static SettlementSummaryBoard.Marker board(ServerPlayer p, BlockPos pos, ServerLevel level) {
            if (p.isSpectator() || p.serverLevel() != level || p.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 16 * 16
                    || level.isOutsideBuildHeight(pos)) return null;
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || !chunk.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)) return null;
            return chunk.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker ? marker : null;
        }

        void share(ServerPlayer p, Request r, ServerLevel level, ReplayPackets.LastMatchSummaryS2C packet, ShareParams params) {
            var marker = board(p, r.board(), level);
            int status = NO_TABLE;
            if (marker != null) {
                ReplayBoardSessionState existing = marker.session();
                int existingDur = existing == null ? 0 : Math.max(0, ReplayBoardService.resolveDuration(existing.roundId()));
                boolean busyMidPlay = existing != null && existing.active()
                        && (existing.preparing() || !existing.isFinishedHold(level.getGameTime(), 0f, existingDur));
                if (busyMidPlay) {
                    status = TABLE_BUSY;
                } else {
                    Set<UUID> waiting = new HashSet<>();
                    BlockPos pos = marker.getBlockPos();
                    for (ServerPlayer player : level.players()) {
                        if (near(player, level, pos) && ServerPlayNetworking.canSend(player, Summary.TYPE)) waiting.add(player.getUUID());
                    }
                    int durationTicks = Math.max(0, packet.replay().durationTicks());
                    rememberDuration(r.round(), durationTicks);
                    double startTick = Math.clamp(params.currentTick(), 0.0, durationTicks);
                    ReplayBoardSessionState prepSession = ReplayBoardSessionState.preparing(
                            r.round(), true, startTick, params.speed(), params.paused(),
                            params.followPlayerUuid(), params.killCloseupEnabled(), params.roofCutaway());
                    marker.setSession(prepSession);
                    activeSharedBoards.put(pos.immutable(), r.round());
                    ReplayBoardService.broadcastSession(level, pos, prepSession);
                    preparing.put(marker, new Preparing(level, marker, r.round(), durationTicks, params,
                            System.nanoTime() + TimeUnit.MINUTES.toNanos(2), waiting));
                    status = SHARED;
                }
            }
            ServerPlayNetworking.send(p, new Reply(r.round(), status));
        }

        private static boolean near(ServerPlayer p, ServerLevel level, BlockPos pos) {
            return p != null && p.serverLevel() == level &&
                    p.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 48 * 48;
        }

        void tick() {
            if (archive == null && !openPending && !io.isShutdown() && server.getTickCount() >= nextOpenTick) openArchive();
            var shares = preparing.values().iterator();
            while (shares.hasNext()) {
                Preparing work = shares.next();
                if (work.marker.isRemoved()) {
                    activeSharedBoards.remove(work.marker.getBlockPos());
                    shares.remove();
                    continue;
                }
                work.waiting.removeIf(id -> !near(server.getPlayerList().getPlayer(id), work.level, work.marker.getBlockPos()));
                if (work.waiting.isEmpty() || System.nanoTime() - work.deadline >= 0) {
                    ReplayBoardSessionState cur = work.marker.session();
                    if (cur != null && cur.preparing() && cur.roundId().equals(work.round)) {
                        ReplayBoardSessionState started = cur.beginAt(work.level.getGameTime() + 6, work.durationTicks);
                        work.marker.setSession(started);
                        ReplayBoardService.broadcastSession(work.level, work.marker.getBlockPos(), started);
                    }
                    shares.remove();
                }
            }
            pumpLane(summaryLane, false);
            pumpLane(voiceLane, true);
            pumpLane(visualLane, true);
        }

        private static int estimateBytes(CustomPacketPayload payload) {
            return payload instanceof Section s ? s.data().bytes().length + 64
                    : payload instanceof VisualPackets.Page page ? page.data().length + 64
                    : payload instanceof Voice v ? v.data().bytes().length + 64
                    : payload instanceof Summary s ? s.data().tracks().length + 128 * 1024 : 64;
        }

        private void pumpLane(TransferLane<CustomPacketPayload> lane, boolean isVoiceLane) {
            lane.drain(
                    id -> state == this && server.getPlayerList().getPlayer(id) != null,
                    // Let ready summary/section packets go ahead of large voice streams for the same player
                    // without ever cancelling or starving the voice lane.
                    id -> isVoiceLane && summaryLane.hasReady(id),
                    State::estimateBytes,
                    payload -> payload instanceof Section,
                    bandwidth,
                    RATE,
                    (id, payload) -> {
                        ServerPlayer player = server.getPlayerList().getPlayer(id);
                        if (player == null || !ServerPlayNetworking.canSend(player, payload.type())) return;
                        ServerPlayNetworking.send(player, payload);
                        if (!isVoiceLane) {
                            UUID round = payload instanceof Summary s ? s.data().replay().roundId()
                                    : payload instanceof Reply r && r.status() == CACHED ? r.round() : null;
                            if (round != null) {
                                var recent = viewed.computeIfAbsent(id, ignored -> new LinkedHashSet<>());
                                recent.addLast(round);
                                if (recent.size() > 32) recent.removeFirst();
                            }
                        }
                    });
        }
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }
}
