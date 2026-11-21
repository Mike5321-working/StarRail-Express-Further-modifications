package com.habitrain.core.game.sre.replay;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class ReplayArchiveQueue extends ThreadPoolExecutor {
    private final Semaphore reads;
    private final Semaphore writes = new Semaphore(2);
    private final AtomicLong bytesInUse = new AtomicLong();
    private final long writeBudget;
    private final Runnable releaseOwnership;

    ReplayArchiveQueue(int readLimit, long writeBudget) {
        this(readLimit, writeBudget, () -> {});
    }

    ReplayArchiveQueue(int readLimit, long writeBudget, Runnable releaseOwnership) {
        super(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "habitrain-replay-archive");
            thread.setDaemon(true);
            return thread;
        });
        reads = new Semaphore(readLimit);
        this.writeBudget = writeBudget;
        this.releaseOwnership = releaseOwnership;
    }

    @Override protected void terminated() {
        releaseOwnership.run();
    }

    void submitRead(Runnable task) {
        if (!reads.tryAcquire()) throw new RejectedExecutionException("Replay archive read queue full");
        submitReserved(task, reads::release);
    }

    CompletableFuture<Void> submitSettlement(long bytes, Callable<Void> task) {
        if (bytes < 0 || !writes.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Replay archive write queue full"));
        }
        long previous = bytesInUse.getAndAdd(bytes);
        if (bytes > writeBudget - previous) {
            bytesInUse.addAndGet(-bytes);
            writes.release();
            return CompletableFuture.failedFuture(new RejectedExecutionException("Replay archive write budget exceeded"));
        }
        var result = new CompletableFuture<Void>();
        try {
            submitReserved(() -> {
                try { result.complete(task.call()); }
                catch (Exception failure) { result.completeExceptionally(failure); }
                catch (Error failure) {
                    result.completeExceptionally(failure);
                    throw failure;
                }
            }, () -> {
                bytesInUse.addAndGet(-bytes);
                writes.release();
            });
        } catch (RejectedExecutionException stopped) {
            result.completeExceptionally(stopped);
        }
        return result;
    }

    private void submitReserved(Runnable task, Runnable release) {
        try {
            execute(() -> {
                try { task.run(); } finally { release.run(); }
            });
        } catch (RejectedExecutionException stopped) {
            release.run();
            throw stopped;
        }
    }
}
