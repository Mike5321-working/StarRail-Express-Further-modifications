package com.habitrain.core.game.sre.match;

import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchSettlement;
import com.habitrain.core.internal.FailurePolicy;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bridges SRE {@code OnGameStarted}/{@code OnGameEnd} onto public {@link MatchEvents}.
 *
 * <p>When invoked inside {@code GameUtils.finalizeGame}, settlement construction is deferred
 * until immediately after {@code GameMode#recordWinStats} (and {@code LustFollowVictoryMixin})
 * so {@code SREGameRoundEndComponent#didWin} is populated while round-end CCA and roles are still live.</p>
 */
public final class MatchEventBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchEventBridge");
    private static final Set<ResourceKey<Level>> FINALIZING_LEVELS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> PENDING_ROUND_END = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> DELIVERED_ROUND_END = ConcurrentHashMap.newKeySet();
    /** Internal phases: winner observers run before any round-state cleanup. */
    public static final Event<MatchEvents.RoundEnded> WINNERS_FINALIZED = settlementEvent();
    public static final Event<RoundCleanup> CLEANUP = EventFactory.createArrayBacked(RoundCleanup.class,
            listeners -> (level, published) -> {
                for (RoundCleanup listener : listeners) {
                    try { listener.run(level, published); }
                    catch (Throwable failure) {
                        FailurePolicy.rethrowIfFatal(failure);
                        LOGGER.error("Internal round cleanup failed", failure);
                    }
                }
            });
    private static boolean registered;

    private MatchEventBridge() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            OnGameStarted.EVENT.register(level -> {
                if (level == null) {
                    return;
                }
                FINALIZING_LEVELS.remove(level.dimension());
                PENDING_ROUND_END.remove(level.dimension());
                DELIVERED_ROUND_END.remove(level.dimension());
                // SRE fires this during INITIATING; the ACTIVE tick releases the reconnect roster.
                MatchEvents.STARTED.invoker().onMatchStarted(level);
            });
            OnGameEnd.EVENT.register((level, game) -> {
                if (level == null) {
                    return;
                }
                ResourceKey<Level> dim = level.dimension();
                if (FINALIZING_LEVELS.contains(dim)) {
                    PENDING_ROUND_END.add(dim);
                    return;
                }
                var reservation = ServerMatchContext.nativeReservation(level);
                try { dispatchRoundEnded(level, game); }
                finally { ServerMatchContext.GATE.release(reservation); }
            });
            LOGGER.info("Registered MatchEvents bridge (OnGameStarted/OnGameEnd)");
        } catch (Throwable t) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
            LOGGER.error("Failed to register MatchEvents bridge", t);
        }
    }

    public static void finalizeNativeRound(ServerLevel level, Runnable upstream) {
        var dimension = level.dimension();
        var reservation = ServerMatchContext.nativeReservation(level);
        if (!FINALIZING_LEVELS.add(dimension)) return;
        if (reservation != null) {
            ServerMatchContext.GATE.transition(reservation.dimension(), SingleMatchGate.Phase.SETTLING);
        }
        runFinalization(upstream, completed -> {
            FINALIZING_LEVELS.remove(dimension);
            boolean pending = PENDING_ROUND_END.remove(dimension);
            try {
                if (pending && completed) dispatchRoundEnded(level, null);
                else if ((pending || reservation != null) && DELIVERED_ROUND_END.add(dimension)) {
                    CLEANUP.invoker().run(level, false);
                }
            } finally {
                ServerMatchContext.GATE.release(reservation);
            }
        });
    }

    static void runFinalization(Runnable upstream, Consumer<Boolean> finish) {
        Throwable originalFailure = null;
        try {
            upstream.run();
        } catch (RuntimeException | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            try { finish.accept(originalFailure == null); }
            catch (RuntimeException | Error failure) {
                if (originalFailure == null) throw failure;
                if (originalFailure != failure) originalFailure.addSuppressed(failure);
            }
        }
    }

    public static void onAfterRecordWinStats(ServerLevel level) {
        if (PENDING_ROUND_END.remove(level.dimension())) dispatchRoundEnded(level, null);
    }

    private static void dispatchRoundEnded(ServerLevel level, @Nullable SREGameWorldComponent game) {
        if (!DELIVERED_ROUND_END.add(level.dimension())) return;
        dispatchSettlement(() -> MatchSettlementFactory.fromRecordedStats(level,
                game == null ? SREGameWorldComponent.KEY.get(level) : game), settlement -> {
            try { GameEndTransitionCoordinator.onWinStatsRecorded(level, settlement); }
            catch (Throwable failure) {
                FailurePolicy.rethrowIfFatal(failure);
                LOGGER.error("Could not update the settlement transition", failure);
            }
            WINNERS_FINALIZED.invoker().onRoundEnded(level, settlement);
            MatchEvents.ROUND_ENDED.invoker().onRoundEnded(level, settlement);
        }, published -> CLEANUP.invoker().run(level, published));
    }

    static void dispatchSettlement(Supplier<MatchSettlement> snapshot, Consumer<MatchSettlement> publish, Consumer<Boolean> cleanup) {
        boolean published = false;
        try {
            publish.accept(snapshot.get());
            published = true;
        } catch (Throwable failure) {
            FailurePolicy.rethrowIfFatal(failure);
            LOGGER.error("Could not publish native round settlement", failure);
        } finally {
            cleanup.accept(published);
        }
    }

    @FunctionalInterface
    public interface RoundCleanup {
        void run(ServerLevel level, boolean settlementPublished);
    }

    static Event<MatchEvents.RoundEnded> settlementEvent() {
        return EventFactory.createArrayBacked(MatchEvents.RoundEnded.class, listeners -> (level, settlement) -> {
            for (MatchEvents.RoundEnded listener : listeners) {
                try { listener.onRoundEnded(level, settlement); }
                catch (Throwable failure) {
                    com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(failure);
                    LOGGER.error("Internal settlement listener failed", failure);
                }
            }
        });
    }

    public static void resetAll() {
        FINALIZING_LEVELS.clear();
        PENDING_ROUND_END.clear();
        DELIVERED_ROUND_END.clear();
    }
}

