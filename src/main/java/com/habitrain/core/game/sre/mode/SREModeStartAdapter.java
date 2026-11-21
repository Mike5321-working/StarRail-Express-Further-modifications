package com.habitrain.core.game.sre.mode;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.game.sre.match.ServerMatchContext;
import com.habitrain.core.internal.FailurePolicy;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.ParticipationComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Thin adapter around SRE MapManager / GameUtils / game-mode constants.
 * The fixed SRE upstream is a required dependency; catches isolate runtime failures.
 */
public final class SREModeStartAdapter {
    private SREModeStartAdapter() {}

    /**
     * Pre-start refuse predicate. Lookup failure counts as blocking so start is refused.
     */
    static boolean blockingFrom(boolean starting, boolean running, boolean lookupFailed) {
        return lookupFailed || starting || running;
    }

    /**
     * Post-start success predicate. Lookup failure is not proven started.
     */
    static boolean startedFrom(boolean starting, boolean running, boolean lookupFailed) {
        return !lookupFailed && (starting || running);
    }

    /** True when an SRE round is already starting/running (or CCA lookup failed). */
    public static boolean isSreGameBlocking(ServerLevel level) {
        try {
            boolean starting = io.wifi.starrailexpress.game.GameUtils.isStartingGame;
            var gw = SREGameWorldComponent.KEY.get(level);
            boolean running = gw != null && (gw.isRunning() || isCountingDown(gw.getGameStatus()));
            return blockingFrom(starting, running, false);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error(
                    "[SREModeStartAdapter] isSreGameBlocking lookup failed; refuse start", t);
            return blockingFrom(false, false, true);
        }
    }

    /** True when SRE is proven STARTING/RUNNING after a start attempt. */
    public static boolean isSreGameStarted(ServerLevel level) {
        try {
            boolean starting = io.wifi.starrailexpress.game.GameUtils.isStartingGame
                    && com.habitrain.core.game.sre.match.ServerMatchContext.GATE.owns(
                    com.habitrain.core.game.sre.match.ServerMatchContext.dimension(level));
            var gw = SREGameWorldComponent.KEY.get(level);
            boolean accepted = gw != null && (isCountingDown(gw.getGameStatus()) || gw.isRunning());
            return startedFrom(starting, accepted, false);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error(
                    "[SREModeStartAdapter] post-start SRE probe failed; treat as not started", t);
            return startedFrom(false, false, true);
        }
    }

    static boolean isCountingDown(SREGameWorldComponent.GameStatus status) {
        return status == SREGameWorldComponent.GameStatus.STARTING;
    }

    public static List<String> getAvailableMaps(ServerLevel level) {
        try {
            return new ArrayList<>(io.wifi.starrailexpress.game.MapManager.getAvailableMaps(level));
        } catch (Throwable t) {
            return List.of();
        }
    }

    public static boolean loadMap(ServerLevel level, String mapId) {
        try {
            return io.wifi.starrailexpress.game.MapManager.loadMap(level, mapId);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("loadMap failed: {}", mapId, t);
            return false;
        }
    }

    /**
     * @param registryFullId e.g. habitrain_core:sre:murder
     */
    public static boolean startMode(ServerLevel level, String registryFullId) {
        if (level == null || registryFullId == null || registryFullId.isBlank()) {
            return false;
        }
        try {
            GameMode registered = GameModeRegistry.get(registryFullId);
            if (registered == null) return false;
            if (requestNativeStart(registered, level, true)) return true;
            GameModeRegistry.start(registryFullId, level);
            return GameModeRegistry.hasExplicitActiveMode(level);
        } catch (Throwable t) {
            FailurePolicy.rethrowIfFatal(t);
            HabiTrainCore.LOGGER.error("startMode failed: {}", registryFullId, t);
            return false;
        }
    }

    /** Resolves the registered mode and the upstream target before changing maps. */
    public static boolean isModeAvailable(String fullId) {
        GameMode mode = GameModeRegistry.get(fullId);
        if (mode == null) return false;
        if (mode instanceof SreOriginalModeProxy proxy) {
            return SREGameModes.GAME_MODES.containsKey(proxy.getSreId());
        }
        return true;
    }

    public static boolean requestNativeStart(GameMode mode, ServerLevel level, boolean fromVote) {
        var nativeMode = switch (mode) {
            case SREMurderMode ignored -> SREGameModes.MURDER;
            case SRERepairMode ignored -> SREGameModes.REPAIR_ESCAPE_MODE;
            case SreOriginalModeProxy proxy -> SREGameModes.GAME_MODES.get(proxy.getSreId());
            default -> null;
        };
        if (nativeMode == null) {
            if (mode instanceof SreOriginalModeProxy proxy) {
                throw new IllegalStateException("Upstream mode unavailable: " + proxy.getSreId());
            }
            return false;
        }
        if (!ServerMatchContext.GATE.canStartRequest(ServerMatchContext.dimension(level))
                || isSreGameBlocking(level)) {
            throw new IllegalStateException("Another match already owns this server");
        }
        boolean accepted = false;
        try {
            if (fromVote) forceReadyParticipants(level);
            GameUtils.startGame(level, nativeMode, GameConstants.getInTicks(nativeMode.defaultStartTime, 0));
            accepted = isSreGameStarted(level);
            if (!accepted) throw new IllegalStateException("SRE did not accept start for " + nativeMode.identifier);
            return true;
        } finally {
            if (fromVote && !accepted) {
                ForcedReadyJoinGate.clear();
                GameUtils.clearForcedReadyPlayers();
            }
        }
    }

    /**
     * 让所有已确认参与对局的玩家强制就绪，绕过 SRE 开局人数判定对就绪区域
     * （readyArea）的过滤。
     *
     * <p>投票后 {@code MapManager.loadMap} 会把 {@code areas.readyArea} 覆盖为获胜地图的
     * 区域，而玩家此刻仍站在旧大厅位置、未被传送；{@code GameUtils.trueStartGame} 又用
     * {@code getStartingPlayers}（默认按 readyArea 过滤）做 {@code minPlayerCount} 判定，
     * 会把这些人判为 0 人而误报「人数不足」。这里像 {@code tmm:start force_all_players}
     * 一样调用 {@code GameUtils.setForcedReadyPlayers}，让 {@code getStartingPlayers}
     * 短路 readyArea 检查、按参与组件返回全部参加者。SRE 在 abort 路径
     * （{@code trueStartGame} 内）和成功路径 {@code initializeGame} 都会
     * {@code clearForcedReadyPlayers()}。core 另拷一份 UUID 到
     * {@link ForcedReadyJoinGate}，SRE 清自己的 list 后仍用于 STARTING 加入门控。</p>
     */
    private static void forceReadyParticipants(ServerLevel level) {
        if (level == null) return;
        try {
            ParticipationComponent participation = ParticipationComponent.KEY.get(level);
            List<UUID> participants = new ArrayList<>();
            for (ServerPlayer p : level.players()) {
                if (p != null && participation.isParticipating(p)) {
                    participants.add(p.getUUID());
                }
            }
            if (participants.isEmpty()) {
                ForcedReadyJoinGate.clear();
                return;
            }
            io.wifi.starrailexpress.game.GameUtils.setForcedReadyPlayers(participants);
            ForcedReadyJoinGate.snapshot(participants);
        } catch (Throwable t) {
            ForcedReadyJoinGate.clear();
            HabiTrainCore.LOGGER.warn("[SREModeStartAdapter] forceReadyParticipants failed", t);
        }
    }
}
