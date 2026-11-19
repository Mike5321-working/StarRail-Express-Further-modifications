package com.habitrain.core.game.sre.replay;

import java.util.concurrent.*;

final class ReplayArchiveQueue extends ThreadPoolExecutor {
    private final Semaphore reads;
    private final Semaphore writes = new Semaphore(2);

    ReplayArchiveQueue(int readLimit) {
        super(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "habitrain-replay-archive");
            thread.setDaemon(true);
            return thread;
        });
        reads = new Semaphore(readLimit);
    }

    void submitWork(boolean settlement, Runnable task) {
        Semaphore permits = settlement ? writes : reads;
        if (settlement) permits.acquireUninterruptibly();
        else if (!permits.tryAcquire()) throw new RejectedExecutionException("Replay archive busy");
        try {
            execute(() -> {
                try { task.run(); } finally { permits.release(); }
            });
        } catch (RejectedExecutionException stopped) {
            permits.release();
            throw stopped;
        }
    }
}
