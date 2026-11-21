package com.habitrain.core.game.sre.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayArchiveQueueTest {
    @Test void sameWorldCannotOpenAnArchiveWhileThePreviousWriterDrains(@TempDir Path world) throws Exception {
        var lock = ReplayArchiveOwnership.tryAcquire(world);
        assertNotNull(lock);
        var releaseWrite = new CountDownLatch(1);
        var writing = new CountDownLatch(1);
        var id = UUID.randomUUID();
        try (var queue = new ReplayArchiveQueue(2, 32, () -> {
            try { lock.close(); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
        })) {
            try {
                var pending = queue.submitSettlement(1, () -> {
                    var archive = new ReplayArchive(world);
                    writing.countDown();
                    releaseWrite.await();
                    archive.save(id, 1, "a".repeat(64), new byte[]{3}, List.of(new byte[]{7}));
                    return null;
                });
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                queue.shutdown();
                assertFalse(queue.awaitTermination(25, TimeUnit.MILLISECONDS));
                assertNull(ReplayArchiveOwnership.tryAcquire(world.resolve(".")));
                try (var otherWorld = ReplayArchiveOwnership.tryAcquire(world.resolve("other-world"))) {
                    assertNotNull(otherWorld);
                }
                releaseWrite.countDown();
                pending.get(5, TimeUnit.SECONDS);
                assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
                try (var reopened = ReplayArchiveOwnership.tryAcquire(world)) {
                    assertNotNull(reopened);
                    assertArrayEquals(new byte[]{3}, new ReplayArchive(world).summary(id));
                }
            } finally {
                releaseWrite.countDown();
            }
        }
    }

    @Test void saturatedReadersCannotDiscardSettledRound(@TempDir Path world) throws Exception {
        var gate = new CountDownLatch(1);
        var id = UUID.randomUUID();
        try (var queue = new ReplayArchiveQueue(2, 32)) {
            try {
                var archive = new ReplayArchive(world);
                queue.submitRead(() -> {
                    try { gate.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
                queue.submitRead(() -> {});
                assertThrows(RejectedExecutionException.class, () -> queue.submitRead(() -> {}));
                var saved = queue.submitSettlement(2, () -> {
                    archive.save(id, 1, "a".repeat(64), new byte[]{3}, List.of(new byte[]{7}));
                    return null;
                });
                assertFalse(saved.isDone());
                gate.countDown();
                saved.get(5, TimeUnit.SECONDS);
                assertArrayEquals(new byte[]{3}, new ReplayArchive(world).summary(id));
            } finally {
                gate.countDown();
                queue.shutdownNow();
                assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void completedCaptureCannotBlockWhenBothWriteSlotsAreOccupied() throws Exception {
        var gate = new CountDownLatch(1);
        try (var queue = new ReplayArchiveQueue(2, 10); var publisher = Executors.newSingleThreadExecutor()) {
            try {
                var first = queue.submitSettlement(6, () -> { gate.await(); return null; });
                assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class,
                        () -> queue.submitSettlement(5, () -> null).join()).getCause());
                var second = queue.submitSettlement(4, () -> { throw new java.io.IOException("disk failure"); });
                var immediate = publisher.submit(() -> CompletableFuture.completedFuture("captured")
                        .thenCompose(capture -> queue.submitSettlement(1, () -> null))).get(2, TimeUnit.SECONDS);
                assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class, immediate::join).getCause());
                assertFalse(first.isDone());
                assertFalse(second.isDone());
                gate.countDown();
                first.get(5, TimeUnit.SECONDS);
                assertInstanceOf(java.io.IOException.class, assertThrows(ExecutionException.class,
                        () -> second.get(5, TimeUnit.SECONDS)).getCause());
            } finally {
                gate.countDown();
            }
        }
    }
}
