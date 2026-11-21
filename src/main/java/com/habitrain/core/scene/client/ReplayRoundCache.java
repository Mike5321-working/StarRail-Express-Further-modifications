package com.habitrain.core.scene.client;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;

final class ReplayRoundCache {
    static final long LIMIT = 64L << 20;
    static final long AGE = Duration.ofDays(30).toMillis();
    private static final Pattern OWNED = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(?:voice|bin)");
    private static final Pattern PART = Pattern.compile("(?:replay|voice)-[0-9]+\\.part");

    static void prune(Path directory, long limit, long now) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
        record FileInfo(Path path, long size, long modified) {}
        var entries = new ArrayList<FileInfo>();
        long total = 0;
        try (var listing = Files.newDirectoryStream(directory)) {
            for (Path path : listing) {
                String name = path.getFileName().toString();
                boolean part = PART.matcher(name).matches();
                if (!part && !OWNED.matcher(name).matches()) continue;
                var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile()) continue;
                long modified = attrs.lastModifiedTime().toMillis();
                if (part || limit == 0 || now - modified > AGE) Files.deleteIfExists(path);
                else {
                    entries.add(new FileInfo(path, attrs.size(), modified));
                    total += attrs.size();
                }
            }
        }
        if (total <= limit) return;
        entries.sort(Comparator.comparingLong(FileInfo::modified).thenComparing(FileInfo::path));
        for (var entry : entries) {
            Files.deleteIfExists(entry.path());
            total -= entry.size();
            if (total <= limit) break;
        }
    }
}
