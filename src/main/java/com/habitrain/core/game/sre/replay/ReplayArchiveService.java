package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.config.MenuGateService;
import com.habitrain.core.network.replay.ReplayArchiveData;
import com.habitrain.core.network.replay.ReplayArchivePackets;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static com.habitrain.core.network.replay.ReplayArchivePackets.*;

public final class ReplayArchiveService {
    private static final Logger LOG = LoggerFactory.getLogger(ReplayArchiveService.class);
    private static final int CAPACITY = 16;
    private static final long RATE = 384 * 1024, GLOBAL = 2 * 1024 * 1024;
    private static volatile State state;
    private ReplayArchiveService() {}

    public static void register() {
        ReplayArchivePackets.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> state = new State(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            State s = state;
            state = null;
            if (s != null) {
                s.io.shutdown();
                try {
                    if (!s.io.awaitTermination(8, TimeUnit.SECONDS)) LOG.warn("Replay archive writes still draining");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> { if (state != null) state.tick(); });
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            if (state != null) {
                UUID id = h.player.getUUID();
                state.pending.remove(id);
                state.last.remove(id);
                state.lastShare.remove(id);
                state.lastStats.remove(id);
                state.lastLimit.remove(id);
                state.lastExport.remove(id);
                state.lastRecording.remove(id);
                state.viewed.remove(id);
                state.bandwidth.forget(id);
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
    }

    static boolean shouldRecordVoice(MinecraftServer server) {
        State active = state;
        return active != null && active.server == server && active.recordVoice;
    }

    static boolean canExportVoice(LastMatchReplay replay, UUID player, boolean admin) {
        return admin || replay.participants().stream().anyMatch(participant -> participant.id().equals(player));
    }

    static void archive(MinecraftServer owner, ReplayPackets.LastMatchSummaryS2C data, ReplayMapCapture.Result map, long finishedAt) {
        archive(owner, data, map, finishedAt, new byte[0]);
    }

    static void archive(MinecraftServer owner, ReplayPackets.LastMatchSummaryS2C data, ReplayMapCapture.Result map,
                        long finishedAt, byte[] voice) {
        State s = state;
        if (s == null || s.server != owner || map == null) return;
        byte[] voiceCopy = voice == null ? new byte[0] : voice.clone();
        s.submit(true, () -> {
            if (s.archive != null) s.archive.save(data.replay().roundId(), finishedAt, map.manifest().hash(),
                    ReplayArchiveData.encode(data), map.patches(), voiceCopy);
        }, () -> LOG.warn("Could not queue 3D replay {}", data.replay().roundId()));
    }

    private record Delivery(ServerPlayer player, Iterator<CustomPacketPayload> packets, CustomPacketPayload next, long at) {}

    private record Preparing(ServerLevel level, SettlementSummaryBoard.Marker marker, UUID round,
                             int duration, long deadline, Set<UUID> waiting) {}

    private static final class State {
        final MinecraftServer server;
        final ReplayArchiveQueue io = new ReplayArchiveQueue(32);
        final Path root;
        final Map<UUID, Delivery> pending = new LinkedHashMap<>();
        final Map<UUID, Long> last = new HashMap<>();
        final Map<SettlementSummaryBoard.Marker, Preparing> preparing = new HashMap<>();
        final Set<UUID> sharing = new HashSet<>();
        final Map<UUID, Long> lastShare = new HashMap<>();
        final Map<UUID, Long> lastStats = new HashMap<>(), lastLimit = new HashMap<>();
        final Map<UUID, Long> lastExport = new HashMap<>(), lastRecording = new HashMap<>();
        final Map<UUID, LinkedHashSet<UUID>> viewed = new HashMap<>();
        final SceneBandwidthGovernor bandwidth = new SceneBandwidthGovernor(System::nanoTime, GLOBAL);
        ReplayArchive archive;
        volatile boolean recordVoice;

        State(MinecraftServer server) {
            this.server = server;
            root = server.getWorldPath(LevelResource.ROOT).resolve("habitrain").resolve("replay-archive");
            submit(() -> {
                archive = new ReplayArchive(root);
                recordVoice = archive.recordVoice();
            }, () -> {});
        }

        void submit(Work work, Runnable rejected) {
            submit(false, work, rejected);
        }

        void submit(boolean settlement, Work work, Runnable rejected) {
            try {
                io.submitWork(settlement, () -> {
                    try { work.run(); }
                    catch (Exception failure) { LOG.warn("Replay archive I/O failed", failure); }
                });
            } catch (RejectedExecutionException busy) { rejected.run(); }
        }

        boolean online(ServerPlayer p) {
            return state == this && server.getPlayerList().getPlayer(p.getUUID()) == p;
        }

        void receive(ServerPlayer p, Request r) {
            if (!online(p) || !ServerPlayNetworking.canSend(p, Reply.TYPE)) return;
            if (r.action() == BOARD_READY) {
                for (Preparing work : preparing.values()) {
                    if (work.level == p.serverLevel() && work.marker.getBlockPos().equals(r.board()) && work.round.equals(r.round())) {
                        work.waiting.remove(p.getUUID());
                        break;
                    }
                }
                return;
            }
            long now = System.nanoTime();
            var throttle = switch (r.action()) {
                case SHARE -> lastShare;
                case STATS -> lastStats;
                case LIMIT -> lastLimit;
                default -> last;
            };
            Long previous = throttle.get(p.getUUID());
            if (previous != null && now - previous < 200_000_000L) return;
            throttle.put(p.getUUID(), now);
            if (r.action() == STATS || r.action() == LIMIT) {
                settings(p, r);
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
                if (visible == null || !visible.contains(r.round())) {
                    ServerPlayNetworking.send(p, new Reply(r.round(), ERROR));
                    return;
                }
            }
            if (r.action() != SHARE && (pending.size() >= CAPACITY || pending.containsKey(p.getUUID()))) return;
            if (r.action() == SECTIONS && (r.missing().isEmpty() || r.missing().cardinality() > ReplayPackets.MAX_REQUESTED)) return;
            ServerLevel level = p.serverLevel();
            if (r.action() == SHARE && board(p, r.board(), level) == null) {
                ServerPlayNetworking.send(p, new Reply(r.round(), NO_TABLE));
                return;
            }
            UUID id = p.getUUID();
            if (r.action() == SHARE) {
                if (preparing.size() >= CAPACITY || !sharing.add(id)) {
                    ServerPlayNetworking.send(p, new Reply(r.round(), TABLE_BUSY));
                    return;
                }
            } else {
                pending.put(id, new Delivery(p, null, null, 0));
            }
            submit(() -> {
                List<CustomPacketPayload> packets;
                try {
                    packets = fetch(r);
                } catch (Exception failed) {
                    LOG.debug("Replay request failed for {}", r.round(), failed);
                    packets = List.of(new Reply(r.round(), ERROR));
                }
                List<CustomPacketPayload> fetched = packets;
                server.execute(() -> {
                    if (r.action() == SHARE) sharing.remove(id);
                    if (!online(p)) return;
                    if (r.action() == SHARE) {
                        if (!fetched.isEmpty() && fetched.getFirst() instanceof Summary summary) share(p, r, level, summary.data());
                        else ServerPlayNetworking.send(p, new Reply(r.round(), SHARE_ERROR));
                    } else {
                        pending.put(id, new Delivery(p, fetched.iterator(), null, 0));
                    }
                });
            }, () -> {
                if (r.action() == SHARE) sharing.remove(id); else pending.remove(id);
                ServerPlayNetworking.send(p, new Reply(r.round(), r.action() == SHARE ? SHARE_ERROR : ERROR));
            });
        }

        List<CustomPacketPayload> fetch(Request r) throws Exception {
            UUID round = r.round();
            if (archive == null) return List.of(new Reply(round, ERROR));
            if (r.action() == VOICE) {
                byte[] voice = archive.voice(round);
                if (voice == null) return List.of(new Reply(round, MISSING));
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
            if (r.action() == LOOKUP && !r.digest().isEmpty() && r.digest().equals(ReplayArchiveData.digest(raw))) {
                return List.of(new Reply(round, CACHED));
            }
            var data = ReplayArchiveData.decode(raw);
            if (!round.equals(data.replay().roundId()) || data.replay().manifest() == null) return List.of(new Reply(round, ERROR));
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
            Set<UUID> visible = viewed.get(id);
            if (visible == null || !visible.contains(round)) {
                sendExportAccess(player, round, false);
                return;
            }
            submit(() -> {
                byte[] summary = archive == null ? null : archive.summary(round);
                boolean allowed = summary != null && canExportVoice(ReplayArchiveData.decode(summary).replay(), id, admin);
                server.execute(() -> { if (online(player)) sendExportAccess(player, round, allowed); });
            }, () -> {});
        }

        void sendExportAccess(ServerPlayer player, UUID round, boolean allowed) {
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

        void settings(ServerPlayer p, Request r) {
            if (!ServerPlayNetworking.canSend(p, Settings.TYPE)) return;
            boolean editable = editable(p);
            submit(() -> {
                boolean ok = archive != null;
                try {
                    if (r.action() == LIMIT) {
                        ok = ok && editable && ReplayArchive.validLimit(r.limit());
                        if (ok) archive.setLimit(r.limit());
                    }
                } catch (Exception failed) {
                    ok = false;
                    LOG.warn("Could not update replay archive limit", failed);
                }
                Settings info = new Settings(archive == null ? 0 : archive.used(),
                        archive == null ? ReplayArchive.DEFAULT_LIMIT : archive.limit(), root.toAbsolutePath().normalize().toString(),
                        editable, ok);
                server.execute(() -> {
                    if (!online(p)) return;
                    ServerPlayNetworking.send(p, info);
                    if (r.action() == STATS) sendRecordingSettings(p, editable, info.ok());
                });
            }, () -> ServerPlayNetworking.send(p, new Settings(0, ReplayArchive.DEFAULT_LIMIT, "", false, false)));
        }

        static SettlementSummaryBoard.Marker board(ServerPlayer p, BlockPos pos, ServerLevel level) {
            if (p.isSpectator() || p.serverLevel() != level || p.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 16 * 16
                    || level.isOutsideBuildHeight(pos)) return null;
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || !chunk.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)) return null;
            return chunk.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker ? marker : null;
        }

        void share(ServerPlayer p, Request r, ServerLevel level, ReplayPackets.LastMatchSummaryS2C packet) {
            var marker = board(p, r.board(), level);
            int status = NO_TABLE;
            if (marker != null) {
                var current = marker.playback();
                if (current != null && current.pendingOrPlaying(level.getGameTime())) {
                    status = TABLE_BUSY;
                } else {
                    Set<UUID> waiting = new HashSet<>();
                    BlockPos pos = marker.getBlockPos();
                    for (ServerPlayer player : level.players()) {
                        if (near(player, level, pos) && ServerPlayNetworking.canSend(player, Summary.TYPE)) waiting.add(player.getUUID());
                    }
                    if (marker.preparePlayback(r.round())) {
                        preparing.put(marker, new Preparing(level, marker, r.round(), new ReplayTimeline(packet.replay()).cycleTicks(),
                                System.nanoTime() + TimeUnit.MINUTES.toNanos(3), waiting));
                        status = SHARED;
                    }
                }
            }
            ServerPlayNetworking.send(p, new Reply(r.round(), status));
        }

        private static boolean near(ServerPlayer p, ServerLevel level, BlockPos pos) {
            return p != null && p.serverLevel() == level &&
                    p.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 48 * 48;
        }

        void tick() {
            var shares = preparing.values().iterator();
            while (shares.hasNext()) {
                Preparing work = shares.next();
                if (work.marker.isRemoved()) { shares.remove(); continue; }
                work.waiting.removeIf(id -> !near(server.getPlayerList().getPlayer(id), work.level, work.marker.getBlockPos()));
                if (work.waiting.isEmpty() || System.nanoTime() - work.deadline >= 0) {
                    work.marker.beginPreparedPlayback(work.round, work.duration);
                    shares.remove();
                }
            }
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                Delivery delivery = entry.getValue();
                if (!online(delivery.player)) { iterator.remove(); continue; }
                if (delivery.packets == null) continue;
                if (delivery.next == null) {
                    if (!delivery.packets.hasNext()) { iterator.remove(); continue; }
                    CustomPacketPayload next = delivery.packets.next();
                    int bytes = next instanceof Section s ? s.data().bytes().length + 64
                            : next instanceof Voice v ? v.data().bytes().length + 64
                            : next instanceof Summary s ? s.data().tracks().length + 128 * 1024
                            + 144 * s.data().replay().killSounds().stream().mapToInt(window -> window.cues().size()).sum() : 64;
                    delivery = new Delivery(delivery.player, delivery.packets, next,
                            bandwidth.reserve(entry.getKey(), bytes, RATE));
                    entry.setValue(delivery);
                }
                if (bandwidth.nowNanos() - delivery.at < 0) continue;
                if (ServerPlayNetworking.canSend(delivery.player, delivery.next.type())) {
                    CustomPacketPayload payload = delivery.next instanceof Summary summary
                            && !ServerPlayNetworking.canSend(delivery.player, ReplayPackets.CaptureKillSoundS2C.TYPE)
                            ? new Summary(summary.data().withoutKillSounds()) : delivery.next;
                    ServerPlayNetworking.send(delivery.player, payload);
                    UUID round = delivery.next instanceof Summary s ? s.data().replay().roundId()
                            : delivery.next instanceof Reply r && r.status() == CACHED ? r.round() : null;
                    if (round != null) {
                        var recent = viewed.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>());
                        recent.addLast(round);
                        if (recent.size() > 32) recent.removeFirst();
                    }
                }
                if (delivery.packets.hasNext()) entry.setValue(new Delivery(delivery.player, delivery.packets, null, 0));
                else iterator.remove();
            }
        }
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }
}
