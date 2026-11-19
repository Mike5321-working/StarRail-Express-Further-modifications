package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayKillSounds;
import com.habitrain.core.network.replay.ReplayPackets.CaptureKillSoundS2C;
import com.habitrain.core.network.replay.ReplayPackets.KillSoundC2S;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class MatchKillSoundRecorder {
    private static final int MAX_PENDING = 128;
    private static final Map<UUID, Request> pending = new HashMap<>();

    private record Request(ServerPlayer victim, CompletableFuture<List<ReplayKillSounds.Cue>> result) {}

    private final Map<LastMatchReplay.Node, CompletableFuture<List<ReplayKillSounds.Cue>>> captures = new IdentityHashMap<>();

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(KillSoundC2S.TYPE, (packet, context) -> {
            Request request = pending.get(packet.ticket());
            if (request == null || context.player() != request.victim()) return;
            pending.remove(packet.ticket());
            request.result().complete(packet.cues().stream()
                    .sorted(java.util.Comparator.comparingInt(ReplayKillSounds.Cue::offsetMs)).toList());
        });
    }

    static void resetPending() { pending.clear(); }

    void capture(ServerPlayer victim, LastMatchReplay.Node kill) {
        if (captures.size() >= ReplayKillSounds.MAX_WINDOWS || !ServerPlayNetworking.canSend(victim, CaptureKillSoundS2C.TYPE)) return;
        pending.values().removeIf(request -> request.result().isDone());
        if (pending.size() >= MAX_PENDING) return;
        UUID ticket = UUID.randomUUID();
        var result = new CompletableFuture<List<ReplayKillSounds.Cue>>().completeOnTimeout(null, 2, TimeUnit.SECONDS);
        captures.put(kill, result);
        pending.put(ticket, new Request(victim, result));
        ServerPlayNetworking.send(victim, new CaptureKillSoundS2C(ticket, victim.getEyePosition(),
                victim.serverLevel().getGameTime()));
    }

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
