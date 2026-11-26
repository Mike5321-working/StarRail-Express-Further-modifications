package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityStatus;
import com.habitrain.core.game.sre.replay.MatchVoiceRecorder;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREAbilityPlayerComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.agmas.noellesroles.content.item.RadioItem;
import org.agmas.noellesroles.game.roles.neutral.commander.CommanderHandler;
import org.agmas.noellesroles.role.ModRoles;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/** World access belongs exclusively to the server thread; UDP readers see one immutable revision. */
public final class ServerVoicePermissions {
    private static volatile VoicePermissionSnapshot current = VoicePermissionSnapshot.EMPTY;
    private static volatile boolean accepting = true;
    private ServerVoicePermissions() {}
    public static VoicePermissionSnapshot current() { return current; }
    public static void clear() { current = VoicePermissionSnapshot.EMPTY; }
    public static void start() { clear(); accepting = true; }
    public static void stop() { accepting = false; clear(); }
    public static void remove(UUID playerId) {
        var previous = current;
        if (previous == VoicePermissionSnapshot.EMPTY || previous == VoicePermissionSnapshot.PASS_THROUGH) {
            return;
        }
        if (!previous.players().containsKey(playerId) && !previous.privateChannels().contains(playerId)) {
            return;
        }
        var players = new HashMap<>(previous.players());
        players.remove(playerId);
        Set<UUID> privateChannels = previous.privateChannels();
        if (privateChannels.contains(playerId)) {
            var mutable = new HashSet<>(privateChannels);
            mutable.remove(playerId);
            privateChannels = mutable;
        }
        current = new VoicePermissionSnapshot(players, previous.policies(), privateChannels);
    }
    public static void refresh(MinecraftServer server) {
        if (!accepting) return;
        if (!server.isSameThread()) throw new IllegalStateException("Voice snapshot requires server thread");
        try {
            publish(server);
        } catch (RuntimeException failure) {
            clear();
            com.habitrain.core.HabiTrainCore.LOGGER.error("Cannot publish voice permission snapshot", failure);
        }
    }
    private static void publish(MinecraftServer server) {
        var capability = RoleCapabilityApi.instance();
        if (!(capability instanceof RoleCapabilityServiceImpl service)) { clear(); return; }
        var policies = service.enabledVoicePolicies();
        boolean recording = MatchVoiceRecorder.isRecording();
        boolean voiceAvailable = service.status(RoleCapabilityKey.VOICE) == RoleCapabilityStatus.AVAILABLE;
        if (policies.isEmpty() && !recording) {
            current = voiceAvailable ? VoicePermissionSnapshot.PASS_THROUGH : VoicePermissionSnapshot.EMPTY;
            return;
        }
        var serverPlayers = server.getPlayerList().getPlayers();
        if (serverPlayers.isEmpty() && RadioItem.RADIO_GROUP.isEmpty()) {
            current = policies.isEmpty() && voiceAvailable
                    ? VoicePermissionSnapshot.PASS_THROUGH
                    : VoicePermissionSnapshot.EMPTY;
            return;
        }
        var players = new HashMap<UUID, VoicePermissionSnapshot.Participant>(Math.max(4, serverPlayers.size() * 2));
        Set<UUID> privateChannels = RadioItem.RADIO_GROUP.isEmpty() ? null : new HashSet<>(RadioItem.RADIO_GROUP);
        var dimStrings = new IdentityHashMap<ServerLevel, String>(4);
        var killerChannelOpen = false;
        var catalog = RoleCatalogApi.instance();
        for (var player : serverPlayers) {
            try {
                ServerLevel level = player.serverLevel();
                var game = SREGameWorldComponent.KEY.get(level);
                var role = game.getRole(player);
                if (game.isRole(player, ModRoles.COMMANDER) && SREAbilityPlayerComponent.KEY.get(player).status == 1) {
                    if (privateChannels == null) privateChannels = new HashSet<>();
                    privateChannels.add(player.getUUID());
                    killerChannelOpen = true;
                }
                if (killerChannelOpen && role != null && CommanderHandler.canUseKillerChannel(role)) {
                    if (privateChannels == null) privateChannels = new HashSet<>();
                    privateChannels.add(player.getUUID());
                }
                RoleKey key = role == null ? null : catalog.canonicalize(role.identifier());
                String dimStr = dimStrings.computeIfAbsent(level, l -> l.dimension().location().toString());
                players.put(player.getUUID(), new VoicePermissionSnapshot.Participant(key,
                        service.groupOf(player.getUUID()), dimStr,
                        player.getX(), player.getY(), player.getZ()));
            } catch (RuntimeException failure) {
                com.habitrain.core.HabiTrainCore.LOGGER.warn("Cannot refresh voice permissions for {}", player.getUUID(), failure);
            }
        }
        // A commander can activate the killer channel after the first pass.  Add eligible killer
        // roles in a second pass so the immutable snapshot is complete for the voice thread.
        if (killerChannelOpen) {
            if (privateChannels == null) privateChannels = new HashSet<>();
            for (var player : serverPlayers) {
                try {
                    var role = SREGameWorldComponent.KEY.get(player.serverLevel()).getRole(player);
                    if (role != null && CommanderHandler.canUseKillerChannel(role)) {
                        privateChannels.add(player.getUUID());
                    }
                } catch (RuntimeException failure) {
                    com.habitrain.core.HabiTrainCore.LOGGER.debug("Cannot refresh private voice channel for {}", player.getUUID(), failure);
                }
            }
        }
        current = new VoicePermissionSnapshot(players, policies, privateChannels == null ? Set.of() : privateChannels);
    }
}
