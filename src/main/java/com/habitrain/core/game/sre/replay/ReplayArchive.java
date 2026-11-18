package com.habitrain.core.game.sre.replay;

import com.habitrain.core.scene.replay.ReplayVoiceData;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/** Per-world 3D archive. Accessed only on the archive I/O thread. */
final class ReplayArchive {
    static final long GIB = 1L << 30, DEFAULT_LIMIT = 5 * GIB, MAX_LIMIT = 1024 * GIB;
    private static final int MAGIC = 0x48525031, MAX_SUMMARY = 768 * 1024, MAX_PATCH = 256 * 1024;
    private final Path root, rounds, maps, voice, config, voiceConfig;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<String, Long> mapSizes = new HashMap<>();
    private long limit = DEFAULT_LIMIT, bytes;
    private boolean recordVoice = true;

    private record Entry(UUID id, Path file, long when, String map, long bytes, long voiceBytes) {}

    ReplayArchive(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        rounds = this.root.resolve("rounds");
        maps = this.root.resolve("maps");
        voice = this.root.resolve("voice");
        config = this.root.resolve("limit-bytes.txt");
        voiceConfig = this.root.resolve("record-voice.txt");
        for (Path directory : List.of(this.root, rounds, maps, voice)) {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Invalid replay directory");
        }
        if (Files.isRegularFile(config, NOFOLLOW_LINKS) && Files.size(config) <= 32) {
            try {
                long saved = Long.parseLong(Files.readString(config).strip());
                if (validLimit(saved)) limit = saved;
            } catch (NumberFormatException ignored) { }
        }
        if (Files.isRegularFile(voiceConfig, NOFOLLOW_LINKS) && Files.size(voiceConfig) <= 32) {
            recordVoice = !Files.readString(voiceConfig).strip().equalsIgnoreCase("false");
        }
        try (Stream<Path> list = Files.list(maps)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (name.matches("(?:[0-9a-f]{64}\\.map|[0-9a-f-]{36}\\.round)\\.part")
                        && Files.isRegularFile(p, NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(p);
                    continue;
                }
                if (name.matches("[0-9a-f]{64}\\.map") && Files.isRegularFile(p, NOFOLLOW_LINKS)) {
                    long n = Files.size(p);
                    mapSizes.put(name.substring(0, 64), n);
                    bytes += n;
                }
            }
        }
        try (Stream<Path> list = Files.list(voice)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (name.matches("[0-9a-f-]{36}\\.voice\\.part") && Files.isRegularFile(p, NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(p);
                }
            }
        }
        try (Stream<Path> list = Files.list(rounds)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (name.matches("(?:[0-9a-f]{64}\\.map|[0-9a-f-]{36}\\.round)\\.part")
                        && Files.isRegularFile(p, NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(p);
                    continue;
                }
                if (!name.endsWith(".round") || !Files.isRegularFile(p, NOFOLLOW_LINKS)) continue;
                UUID id;
                try { id = UUID.fromString(name.substring(0, name.length() - 6)); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!p.equals(roundPath(id))) continue;
                long n = Files.size(p);
                String map = null;
                long time = 0;
                try (var in = new DataInputStream(Files.newInputStream(p, NOFOLLOW_LINKS))) {
                    if (in.readInt() == MAGIC) {
                        time = in.readLong();
                        map = in.readUTF();
                    }
                } catch (EOFException | UTFDataFormatException ignored) { }
                if (map == null || !mapSizes.containsKey(map) || n <= 78 || n > MAX_SUMMARY + 78L) {
                    Files.delete(p);
                    continue;
                }
                long voiceSize = 0;
                Path vp = voicePath(id);
                if (Files.isRegularFile(vp, NOFOLLOW_LINKS)) {
                    long vs = Files.size(vp);
                    if (vs > 0 && vs <= ReplayVoiceData.MAX_VOICE_BYTES) {
                        voiceSize = vs;
                    } else {
                        Files.deleteIfExists(vp);
                    }
                }
                entries.put(id, new Entry(id, p, time, map, n, voiceSize));
                bytes += n + voiceSize;
            }
        }
        dropOrphans(null);
        prune();
    }

    Path root() { return root; }
    long used() { return bytes; }
    long limit() { return limit; }
    boolean recordVoice() { return recordVoice; }
    static boolean validLimit(long value) { return value >= (1L << 20) && value <= MAX_LIMIT; }

    void setLimit(long value) throws IOException {
        if (!validLimit(value)) throw new IllegalArgumentException("Invalid replay limit");
        atomicWrite(config, out -> out.writeBytes(Long.toString(value)));
        limit = value;
        dropOrphans(null);
        prune();
    }

    void setRecordVoice(boolean enabled) throws IOException {
        atomicWrite(voiceConfig, out -> out.writeBytes(Boolean.toString(enabled)));
        recordVoice = enabled;
    }

    void save(UUID id, long when, String map, byte[] summary, List<byte[]> patches) throws IOException {
        save(id, when, map, summary, patches, new byte[0]);
    }

    void save(UUID id, long when, String map, byte[] summary, List<byte[]> patches, byte[] voiceData) throws IOException {
        if (entries.containsKey(id)) return;
        byte[] v = voiceData == null ? new byte[0] : voiceData;
        if (!map.matches("[0-9a-f]{64}") || summary.length < 1 || summary.length > MAX_SUMMARY ||
                patches.isEmpty() || patches.size() > 1024 || v.length > ReplayVoiceData.MAX_VOICE_BYTES) {
            throw new IOException("Invalid replay archive input");
        }
        long mapBytes = 4L + 8L * patches.size();
        for (byte[] patch : patches) {
            if (patch.length < 1 || patch.length > MAX_PATCH) throw new IOException("Invalid replay section size");
            mapBytes += patch.length;
        }
        long roundBytes = 78L + summary.length;
        long voiceBytes = v.length;
        if (roundBytes + voiceBytes + mapSizes.getOrDefault(map, mapBytes) > limit) return;
        if (!mapSizes.containsKey(map)) {
            atomicWrite(mapPath(map), out -> {
                out.writeInt(patches.size());
                int offset = 4 + 8 * patches.size();
                for (byte[] patch : patches) {
                    out.writeInt(offset);
                    out.writeInt(patch.length);
                    offset += patch.length;
                }
                for (byte[] patch : patches) out.write(patch);
            });
            mapSizes.put(map, mapBytes);
            bytes += mapBytes;
        }
        Path file = roundPath(id);
        Path vFile = voicePath(id);
        try {
            if (voiceBytes > 0) {
                atomicWrite(vFile, out -> out.write(v));
            }
            atomicWrite(file, out -> {
                out.writeInt(MAGIC);
                out.writeLong(when);
                out.writeUTF(map);
                out.write(summary);
            });
        } catch (IOException failure) {
            try { Files.deleteIfExists(vFile); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { dropOrphans(null); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        entries.put(id, new Entry(id, file, when, map, roundBytes, voiceBytes));
        bytes += roundBytes + voiceBytes;
        // The new round is readable before any existing round can be evicted.
        prune();
    }

    byte[] summary(UUID id) throws IOException {
        Entry e = entries.get(id);
        if (e == null) return null;
        try (var in = Files.newInputStream(e.file, NOFOLLOW_LINKS)) {
            in.skipNBytes(78);
            byte[] contents = in.readNBytes(MAX_SUMMARY + 1);
            if (contents.length != e.bytes - 78) throw new IOException("Wrong replay summary length");
            return contents;
        }
    }

    byte[] voice(UUID id) throws IOException {
        Entry e = entries.get(id);
        if (e == null) return null;
        if (e.voiceBytes == 0) return new byte[0];
        Path p = voicePath(id);
        if (!Files.isRegularFile(p, NOFOLLOW_LINKS)) return new byte[0];
        try (var in = Files.newInputStream(p, NOFOLLOW_LINKS)) {
            byte[] contents = in.readNBytes(ReplayVoiceData.MAX_VOICE_BYTES + 1);
            if (contents.length != e.voiceBytes) throw new IOException("Wrong replay voice length");
            return contents;
        }
    }

    Map<Integer, byte[]> sections(UUID id, BitSet requested) throws IOException {
        Entry e = entries.get(id);
        if (e == null) return null;
        Path p = mapPath(e.map);
        if (!Files.isRegularFile(p, NOFOLLOW_LINKS)) throw new IOException("Missing replay map");
        try (var file = new RandomAccessFile(p.toFile(), "r")) {
            int count = file.readInt();
            if (count < 1 || count > 1024 || requested.length() > count) throw new IOException("Invalid replay section index");
            Map<Integer, byte[]> parts = new LinkedHashMap<>();
            for (int index = requested.nextSetBit(0); index >= 0; index = requested.nextSetBit(index + 1)) {
                file.seek(4L + 8L * index);
                int offset = file.readInt(), length = file.readInt();
                if (offset < 4 + 8 * count || length < 1 || length > MAX_PATCH
                        || (long) offset + length > file.length()) throw new IOException("Invalid replay map offset");
                byte[] data = new byte[length];
                file.seek(offset);
                file.readFully(data);
                parts.put(index, data);
            }
            return parts;
        }
    }

    private void prune() throws IOException {
        if (bytes <= limit) return;
        var oldest = entries.values().stream().sorted(Comparator.comparingLong(Entry::when).thenComparing(Entry::id)).toList();
        var references = references();
        for (Entry entry : oldest) {
            if (bytes <= limit) break;
            Files.deleteIfExists(entry.file);
            Files.deleteIfExists(voicePath(entry.id));
            entries.remove(entry.id);
            bytes -= (entry.bytes + entry.voiceBytes);
            if (references.merge(entry.map, -1, Integer::sum) == 0) dropMap(entry.map);
        }
    }

    private Map<String, Integer> references() {
        Map<String, Integer> count = new HashMap<>();
        for (Entry entry : entries.values()) count.merge(entry.map, 1, Integer::sum);
        return count;
    }

    private void dropOrphans(String keep) throws IOException {
        var referenced = references();
        for (String map : List.copyOf(mapSizes.keySet())) {
            if (!map.equals(keep) && !referenced.containsKey(map)) dropMap(map);
        }
        try (Stream<Path> list = Files.list(voice)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".voice") || !Files.isRegularFile(p, NOFOLLOW_LINKS)) continue;
                try {
                    UUID id = UUID.fromString(name.substring(0, name.length() - 6));
                    if (!entries.containsKey(id)) Files.deleteIfExists(p);
                } catch (IllegalArgumentException ignored) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private void dropMap(String hash) throws IOException {
        Files.deleteIfExists(mapPath(hash));
        bytes -= mapSizes.remove(hash);
    }

    private Path mapPath(String hash) { return maps.resolve(hash + ".map"); }
    private Path roundPath(UUID id) { return rounds.resolve(id + ".round"); }
    private Path voicePath(UUID id) { return voice.resolve(id + ".voice"); }

    @FunctionalInterface private interface Output { void write(DataOutputStream stream) throws IOException; }

    private static void atomicWrite(Path name, Output output) throws IOException {
        Path tmp = name.resolveSibling(name.getFileName() + ".part");
        Files.deleteIfExists(tmp);
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW)))) {
                output.write(out);
            }
            try { Files.move(tmp, name, ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException fallback) { Files.move(tmp, name, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
