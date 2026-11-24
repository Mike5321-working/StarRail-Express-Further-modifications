package com.habitrain.core.api.client.replay;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client entry point for a caller that already has the server's match record ID,
 * plus optional live-sync hooks for nearby active lobby replay tables ({@code <= 16} blocks).
 *
 * <p>Binary compatibility: {@link #install(ScreenFactory)} and {@link #open(Screen, String, boolean)}
 * preserve their exact 2.0.37 signatures for downstream mods such as {@code habitrain_lottery}.</p>
 */
@Environment(EnvType.CLIENT)
public final class MatchReplayClientApi {
    public static final double DEFAULT_NEARBY_RANGE = 16.0;

    @FunctionalInterface
    public interface ScreenFactory {
        Screen open(Screen parent, String matchId, boolean export);
    }

    /** Immutable snapshot of the nearest active lobby replay table within range. */
    public record NearbyTableInfo(
            BlockPos pos,
            String roundId,
            String mapName,
            double currentTick,
            int durationTicks,
            float speed,
            boolean paused,
            boolean preparing,
            boolean finishedHold,
            @Nullable UUID followPlayerUuid,
            String followPlayerName,
            boolean killCloseupEnabled,
            boolean roofCutaway,
            double distanceBlocks
    ) {
        public NearbyTableInfo {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            roundId = roundId == null ? "" : roundId;
            mapName = mapName == null ? "" : mapName;
            followPlayerName = followPlayerName == null ? "" : followPlayerName;
        }
    }

    /** Internal client implementation bridge installed by {@code ReplayHistoryClient}. */
    public interface NearbyTableBridge {
        default Screen openWithFocus(Screen parent, String matchId, boolean export, @Nullable UUID focusPlayer) {
            return MatchReplayClientApi.open(parent, matchId, export);
        }

        @Nullable NearbyTableInfo nearestActiveTable(double maxDistance);

        boolean controlNearestTable(double maxDistance, String action, double numericArg,
                                    @Nullable UUID uuidArg, boolean flagArg);

        @Nullable Screen openNearbyTableExport(Screen parent, double maxDistance);

        @Nullable Screen openNearbyTablePreview(Screen parent, double maxDistance);
    }

    private static ScreenFactory factory = (parent, matchId, export) -> parent;
    private static @Nullable NearbyTableBridge nearbyBridge;

    private MatchReplayClientApi() {}

    public static void install(ScreenFactory clientFactory) {
        factory = Objects.requireNonNull(clientFactory);
    }

    public static void installNearbyTableBridge(@Nullable NearbyTableBridge bridge) {
        nearbyBridge = bridge;
    }

    public static Screen open(Screen parent, String matchId, boolean export) {
        return factory.open(parent, matchId, export);
    }

    public static Screen open(Screen parent, String matchId, boolean export, @Nullable UUID focusPlayer) {
        NearbyTableBridge bridge = nearbyBridge;
        return bridge != null ? bridge.openWithFocus(parent, matchId, export, focusPlayer)
                : factory.open(parent, matchId, export);
    }

    public static @Nullable NearbyTableInfo nearbyTable() {
        return nearbyTable(DEFAULT_NEARBY_RANGE);
    }

    public static @Nullable NearbyTableInfo nearbyTable(double maxDistance) {
        NearbyTableBridge bridge = nearbyBridge;
        return bridge == null ? null : bridge.nearestActiveTable(maxDistance);
    }

    /**
     * Reflection-friendly snapshot of the nearest active lobby replay table within 16 blocks,
     * using only JDK types so downstream mods compiled against older core API jars can bind safely.
     */
    public static @Nullable Map<String, Object> nearbyTableSnapshot() {
        NearbyTableInfo info = nearbyTable(DEFAULT_NEARBY_RANGE);
        if (info == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("roundId", info.roundId());
        map.put("mapName", info.mapName());
        map.put("currentTick", info.currentTick());
        map.put("durationTicks", info.durationTicks());
        map.put("speed", info.speed());
        map.put("paused", info.paused());
        map.put("preparing", info.preparing());
        map.put("finishedHold", info.finishedHold());
        map.put("followPlayerUuid", info.followPlayerUuid() == null ? "" : info.followPlayerUuid().toString());
        map.put("followPlayerName", info.followPlayerName());
        map.put("killCloseupEnabled", info.killCloseupEnabled());
        map.put("roofCutaway", info.roofCutaway());
        map.put("distanceBlocks", info.distanceBlocks());
        map.put("posX", info.pos().getX());
        map.put("posY", info.pos().getY());
        map.put("posZ", info.pos().getZ());
        return map;
    }

    /**
     * Sends a session control action to the nearest active lobby replay table within 16 blocks.
     * Supported actions: {@code toggle_pause}, {@code play}, {@code pause}, {@code seek}, {@code step},
     * {@code speed}, {@code cycle_speed}, {@code follow}, {@code cycle_follow}, {@code closeup},
     * {@code roof}, {@code end}.
     */
    public static boolean controlNearbyTable(String action, double numericArg,
                                             @Nullable String uuidArg, boolean flagArg) {
        NearbyTableBridge bridge = nearbyBridge;
        if (bridge == null || action == null || action.isEmpty()) return false;
        UUID parsed = null;
        if (uuidArg != null && !uuidArg.isBlank()) {
            try {
                parsed = UUID.fromString(uuidArg.trim());
            } catch (IllegalArgumentException ignored) {
                parsed = null;
            }
        }
        return bridge.controlNearestTable(DEFAULT_NEARBY_RANGE, action, numericArg, parsed, flagArg);
    }

    /** Opens {@code ReplayExportScreen} directly for the nearby active table's current round. */
    public static @Nullable Screen openNearbyTableExport(Screen parent) {
        NearbyTableBridge bridge = nearbyBridge;
        return bridge == null ? null : bridge.openNearbyTableExport(parent, DEFAULT_NEARBY_RANGE);
    }

    /** Opens {@code ReplayHistoryScreen} synced to the nearby active table's current round. */
    public static @Nullable Screen openNearbyTablePreview(Screen parent) {
        NearbyTableBridge bridge = nearbyBridge;
        return bridge == null ? null : bridge.openNearbyTablePreview(parent, DEFAULT_NEARBY_RANGE);
    }
}
