package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.MatchPhase;
import com.habitrain.core.game.sre.match.MatchStateAccess;
import com.habitrain.core.network.replay.ReplayPackets.ExportMatchS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class ExportMatchSessions {
    private record Session(UUID token, boolean notified) {}
    private final Map<UUID, Session> sessions = new HashMap<>();

    void presence(UUID owner, UUID token, boolean open) {
        Session previous = sessions.get(owner);
        if (open && (previous == null || !token.equals(previous.token))) sessions.put(owner, new Session(token, false));
        if (!open && previous != null && token.equals(previous.token)) disconnect(owner);
    }

    void disconnect(UUID owner) { sessions.remove(owner); }

    void started(net.minecraft.server.level.ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, ExportMatchS2C.TYPE)) return;
        var notice = transition(player.getUUID(), MatchPhase.ACTIVE);
        if (notice != null) ServerPlayNetworking.send(player, notice);
    }

    ExportMatchS2C transition(UUID owner, MatchPhase phase) {
        Session session = sessions.get(owner);
        if (session == null || phase == MatchPhase.UNKNOWN) return null;
        boolean active = phase == MatchPhase.ACTIVE || phase == MatchPhase.STARTING || phase == MatchPhase.INITIATING;
        if (active == session.notified) return null;
        sessions.put(owner, new Session(session.token, active));
        return new ExportMatchS2C(session.token, active);
    }

    void poll(MinecraftServer server) {
        for (UUID owner : sessions.keySet()) {
            var player = server.getPlayerList().getPlayer(owner);
            if (player == null || !ServerPlayNetworking.canSend(player, ExportMatchS2C.TYPE)) continue;
            var notice = transition(owner, MatchStateAccess.phase(player.serverLevel()));
            if (notice != null) ServerPlayNetworking.send(player, notice);
        }
    }
}
