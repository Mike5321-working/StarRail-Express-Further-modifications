package com.habitrain.core.game.sre.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayArchiveQueueTest {
    @Test void saturatedReadersCannotDiscardSettledRound(@TempDir Path world) throws Exception {
        var gate = new CountDownLatch(1);
        var saved = new CompletableFuture<Void>();
        var id = UUID.randomUUID();
        try (var queue = new ReplayArchiveQueue(2)) {
            try {
                var archive = new ReplayArchive(world);
                queue.submitWork(false, () -> {
                    try { gate.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
                queue.submitWork(false, () -> {});
                assertThrows(RejectedExecutionException.class, () -> queue.submitWork(false, () -> {}));
                queue.submitWork(true, () -> {
                    try {
                        archive.save(id, 1, "a".repeat(64), new byte[]{3}, List.of(new byte[]{7}));
                        saved.complete(null);
                    } catch (Exception failure) { saved.completeExceptionally(failure); }
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
}
