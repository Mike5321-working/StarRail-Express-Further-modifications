package com.habitrain.core.game.sre.replay;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

final class ReplayArchiveOwnership implements AutoCloseable {
    private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
    private final Path lockPath;
    private final FileChannel channel;

    private ReplayArchiveOwnership(Path lockPath, FileChannel channel) {
        this.lockPath = lockPath;
        this.channel = channel;
    }

    static ReplayArchiveOwnership tryAcquire(Path root) throws IOException {
        Files.createDirectories(root);
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw new IOException("Invalid replay directory");
        Path lockPath = root.toRealPath().resolve(".writer.lock");
        if (!OWNED.add(lockPath)) return null;
        FileChannel channel = null;
        boolean acquired = false;
        try {
            channel = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS);
            acquired = channel.tryLock() != null;
            return acquired ? new ReplayArchiveOwnership(lockPath, channel) : null;
        } catch (OverlappingFileLockException busy) {
            return null;
        } finally {
            if (!acquired) {
                try { if (channel != null) channel.close(); }
                finally { OWNED.remove(lockPath); }
            }
        }
    }

    @Override public synchronized void close() throws IOException {
        if (!channel.isOpen()) return;
        try { channel.close(); }
        finally { OWNED.remove(lockPath); }
    }
}
