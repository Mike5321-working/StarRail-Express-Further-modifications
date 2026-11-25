package com.habitrain.core.scene.client.replay;

import com.habitrain.core.network.replay.VisualFrames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReplayVisualClientConcurrencyTest {

    @AfterEach
    void cleanup() {
        ReplayVisualClient.clearOutlines();
    }

    @Test
    void concurrentRecordDrainAndClearNeverThrowOrCorruptWireFormat() throws Exception {
        List<UUID> pool = new ArrayList<>(160);
        for (int i = 0; i < 160; i++) {
            pool.add(new UUID(0x1000_0000L + i, 0x2000_0000L + i));
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);

        try {
            Future<?> writer1 = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int i = 0;
                    while (!stop.get()) {
                        UUID id = pool.get(i % pool.size());
                        ReplayVisualClient.recordOutline(id, 0xFF0000 | (i & 0xFFFF));
                        i++;
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    stop.set(true);
                }
            });

            Future<?> writer2 = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    int i = 0;
                    while (!stop.get()) {
                        UUID id = pool.get((pool.size() - 1) - (i % pool.size()));
                        ReplayVisualClient.recordOutline(id, 0x00FF00 | (i & 0xFFFF));
                        if ((i & 0x3FF) == 0) {
                            ReplayVisualClient.clearOutlines();
                        }
                        i++;
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    stop.set(true);
                }
            });

            Future<?> drainer = executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int round = 0; round < 4000 && !stop.get(); round++) {
                        byte[] encoded = ReplayVisualClient.drainOutlines();
                        Map<UUID, Integer> decoded = VisualFrames.outlines(
                                new DataInputStream(new ByteArrayInputStream(encoded)));
                        assertTrue(decoded.size() <= 128, "Decoded outline count exceeded cap: " + decoded.size());
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    stop.set(true);
                }
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            drainer.get(15, TimeUnit.SECONDS);
            stop.set(true);
            writer1.get(5, TimeUnit.SECONDS);
            writer2.get(5, TimeUnit.SECONDS);
        } finally {
            stop.set(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        if (failure.get() != null) {
            fail("Concurrent outline recording/draining failed", failure.get());
        }
    }

    @Test
    void drainOutlinesPreservesCanonicalOrderClearsStateAndRespectsCap() throws IOException {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);

        ReplayVisualClient.recordOutline(b, 0x222222);
        ReplayVisualClient.recordOutline(a, 0x111111);
        byte[] first = ReplayVisualClient.drainOutlines();

        // Draining clears the buffer.
        Map<UUID, Integer> afterDrain = VisualFrames.outlines(
                new DataInputStream(new ByteArrayInputStream(ReplayVisualClient.drainOutlines())));
        assertTrue(afterDrain.isEmpty());

        // Different insertion order yields byte-identical wire representation.
        ReplayVisualClient.recordOutline(a, 0x111111);
        ReplayVisualClient.recordOutline(b, 0x222222);
        byte[] second = ReplayVisualClient.drainOutlines();
        assertArrayEquals(first, second);

        // Cap at 128 distinct UUIDs, while still allowing updates to an existing entry.
        for (int i = 0; i < 140; i++) {
            ReplayVisualClient.recordOutline(new UUID(1, i), i);
        }
        UUID existing = new UUID(1, 0);
        ReplayVisualClient.recordOutline(existing, 0xABCDEF);
        Map<UUID, Integer> capped = VisualFrames.outlines(
                new DataInputStream(new ByteArrayInputStream(ReplayVisualClient.drainOutlines())));
        assertEquals(128, capped.size());
        assertEquals(0xABCDEF, capped.get(existing));
    }

    @Test
    void nullInputsAndForegroundNullEntityAreIgnoredSafely() {
        ReplayVisualClient.saw(null, OptionalInt.of(0xFF0000));
        ReplayVisualClient.recordOutline(null, 0xFF0000);
        assertEquals(OptionalInt.empty(), ReplayForegroundWorld.outline(null));
    }
}
