package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayKillSounds;
import com.habitrain.core.network.replay.ReplayPackets.CaptureKillSoundS2C;
import com.habitrain.core.network.replay.ReplayPackets.KillSoundC2S;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class MatchKillSoundRecorder {
    private static final int MAX_PENDING = 128;
    private static final int MAX_LISTENERS = 8;
    private static final double LISTENER_RADIUS = 32.0;
    private static final Map<UUID, Request> pending = new ConcurrentHashMap<>();

    private record Request(UUID listenerId, CompletableFuture<List<ReplayKillSounds.Cue>> result) {}

    private final Map<LastMatchReplay.Node, CompletableFuture<List<ReplayKillSounds.Cue>>> captures = new IdentityHashMap<>();

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(KillSoundC2S.TYPE, (packet, context) -> {
            Request request = pending.get(packet.ticket());
            if (request == null || !context.player().getUUID().equals(request.listenerId())) return;
            pending.remove(packet.ticket(), request);
            request.result().complete(packet.cues().stream()
                    .sorted(java.util.Comparator.comparingInt(ReplayKillSounds.Cue::offsetMs)).toList());
        });
    }

    static void resetPending() {
        pending.values().forEach(request -> request.result().complete(List.of()));
        pending.clear();
    }

    void capture(ServerPlayer victim, LastMatchReplay.Node kill) {
        if (captures.size() >= ReplayKillSounds.MAX_WINDOWS) return;
        pending.values().removeIf(request -> request.result().isDone());

        List<ServerPlayer> listeners = listeners(victim, kill);
        List<CompletableFuture<List<ReplayKillSounds.Cue>>> results = new ArrayList<>();
        for (ServerPlayer listener : listeners) {
            if (pending.size() >= MAX_PENDING) break;
            UUID ticket = UUID.randomUUID();
            CompletableFuture<List<ReplayKillSounds.Cue>> result = new CompletableFuture<>();
            result.whenComplete((ignored, failure) -> pending.remove(ticket));
            pending.put(ticket, new Request(listener.getUUID(), result));
            results.add(result.completeOnTimeout(List.of(), 2, TimeUnit.SECONDS));
            try {
                ServerPlayNetworking.send(listener, new CaptureKillSoundS2C(ticket, listener.getEyePosition(),
                        listener.serverLevel().getGameTime()));
            } catch (RuntimeException failure) {
                result.complete(List.of());
                LoggerFactory.getLogger(MatchKillSoundRecorder.class).warn("Could not request replay kill sound from {}",
                        listener.getUUID(), failure);
            }
        }
        if (results.isEmpty()) return;
        captures.put(kill, combine(results));
    }

    private static List<ServerPlayer> listeners(ServerPlayer victim, LastMatchReplay.Node kill) {
        Vec3 origin = victim.position();
        UUID killer = kill.actor();
        Map<UUID, ServerPlayer> byId = new HashMap<>();
        for (ServerPlayer player : victim.serverLevel().players()) byId.put(player.getUUID(), player);

        List<ServerPlayer> selected = new ArrayList<>();
        addListener(selected, byId.get(victim.getUUID()), true);
        addListener(selected, killer == null ? null : byId.get(killer), false);

        byId.values().stream()
                .filter(player -> player != victim && !player.isSpectator() && player.isAlive())
                .filter(player -> player.distanceToSqr(origin) <= LISTENER_RADIUS * LISTENER_RADIUS)
                .sorted(Comparator.comparingDouble(player -> player.distanceToSqr(origin)))
                .forEach(player -> addListener(selected, player, false));
        return selected.size() > MAX_LISTENERS ? List.copyOf(selected.subList(0, MAX_LISTENERS)) : List.copyOf(selected);
    }

    private static void addListener(List<ServerPlayer> selected, ServerPlayer candidate, boolean includeDead) {
        if (candidate == null || candidate.isSpectator() && !includeDead || !includeDead && !candidate.isAlive()
                || !ServerPlayNetworking.canSend(candidate, CaptureKillSoundS2C.TYPE)
                || selected.stream().anyMatch(player -> player.getUUID().equals(candidate.getUUID()))) return;
        selected.add(candidate);
    }

    private static CompletableFuture<List<ReplayKillSounds.Cue>> combine(
            List<CompletableFuture<List<ReplayKillSounds.Cue>>> results) {
        return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<CueKey, ReplayKillSounds.Cue> merged = new HashMap<>();
            for (CompletableFuture<List<ReplayKillSounds.Cue>> result : results) {
                for (ReplayKillSounds.Cue cue : result.getNow(List.of())) {
                    CueKey key = new CueKey(cue.offsetMs(), cue.file(), cue.pitch());
                    merged.merge(key, cue, (left, right) -> left.volume() >= right.volume() ? left : right);
                }
            }
            return merged.values().stream()
                    .sorted(Comparator.comparingInt(ReplayKillSounds.Cue::offsetMs))
                    .limit(ReplayKillSounds.MAX_PER_KILL)
                    .toList();
        });
    }

    private record CueKey(int offsetMs, net.minecraft.resources.ResourceLocation file, float pitch) {}

    CompletableFuture<List<ReplayKillSounds.Window>> finish(List<LastMatchReplay.Node> events) {
        if (captures.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.allOf(captures.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<ReplayKillSounds.Window> windows = new ArrayList<>();
            int remaining = ReplayKillSounds.MAX_PER_ROUND;
            for (int index = 0; index < events.size() && remaining > 0; index++) {
                var capture = captures.get(events.get(index));
                if (capture == null) continue;
                var cues = capture.getNow(null);
                if (cues == null || cues.isEmpty()) continue;
                if (cues.size() > remaining) cues = cues.subList(0, remaining);
                remaining -= cues.size();
                windows.add(new ReplayKillSounds.Window(index, cues));
            }
            return List.copyOf(windows);
        });
    }
}
