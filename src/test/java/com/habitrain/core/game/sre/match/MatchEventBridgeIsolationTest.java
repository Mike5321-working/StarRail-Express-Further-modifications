package com.habitrain.core.game.sre.match;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MatchEventBridgeIsolationTest {
    @Test
    void upstreamFailureStillRunsFinalizationWithoutMaskingTheOriginal() {
        var gate = new SingleMatchGate();
        assertTrue(gate.nativeConfirm("world"));
        var round = gate.snapshot();
        var upstreamFailure = new IllegalStateException("upstream statistics failed");
        var cleanupFailure = new IllegalArgumentException("cleanup failure");
        Throwable thrown = assertThrows(IllegalStateException.class, () -> MatchEventBridge.runFinalization(
                () -> { throw upstreamFailure; }, completed -> {
                    assertFalse(completed);
                    gate.release(round);
                    throw cleanupFailure;
                }));
        assertSame(upstreamFailure, thrown);
        assertArrayEquals(new Throwable[]{cleanupFailure}, thrown.getSuppressed());
        assertTrue(gate.vote("world"));
    }

    @Test
    void failedSnapshotRunsCleanupWithoutPublishingAResult() {
        var cleaned = new AtomicBoolean();
        MatchEventBridge.dispatchSettlement(() -> { throw new IllegalStateException("snapshot failed"); },
                settlement -> fail("must not publish an incomplete result"), published -> {
                    assertFalse(published);
                    cleaned.set(true);
                });
        assertTrue(cleaned.get());
    }

    @SuppressWarnings("removal")
    static Stream<Error> fatalFailures() {
        return Stream.of(new NoClassDefFoundError("synthetic linkage failure"),
                new OutOfMemoryError("synthetic; no allocation pressure"), new ThreadDeath());
    }

    static Stream<Throwable> nonFatalFailures() {
        return Stream.of(new IllegalStateException("listener failure"), new AssertionError("listener assertion"));
    }

    @ParameterizedTest @MethodSource("fatalFailures")
    void internalSettlementPhasePropagatesFatalFailure(Error fatal) {
        var event = MatchEventBridge.settlementEvent();
        var next = new AtomicBoolean();
        event.register((level, settlement) -> { throw fatal; });
        event.register((level, settlement) -> next.set(true));
        assertSame(fatal, assertThrows(fatal.getClass(), () -> event.invoker().onRoundEnded(null, null)));
        assertFalse(next.get());
    }

    @ParameterizedTest @MethodSource("nonFatalFailures")
    void ordinarySettlementFailureDoesNotSkipLaterCleanup(Throwable failure) {
        var event = MatchEventBridge.settlementEvent();
        var next = new AtomicBoolean();
        event.register((level, settlement) -> {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        });
        event.register((level, settlement) -> next.set(true));
        assertDoesNotThrow(() -> event.invoker().onRoundEnded(null, null));
        assertTrue(next.get());
    }
}
