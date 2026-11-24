package com.habitrain.core.network.replay;

import java.io.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class VisualTape {
    public static final UUID WORLD = new UUID(0, 0);
    public static final int PAGE = 64 * 1024, MAX_EVENT = 24 * 1024, MAX_STREAM = 16 << 20, MAX_ROUND = 64 << 20;
    public static final int CLOCK = 1, BLOCK = 2, TILE = 3, CAMERA = 4, HUD = 5, PARTICLES = 6, STATE = 7, END_VIEW = 8, HUD_DIFF = 9, LEVEL_EVENT = 10, BODY = 11, ITEMS = 12;
    private static final int MAGIC = 0x48565431;
    public static final int OUTLINES = 13;
    public static final int SERVER_CAMERA = 14;
    public static final int CORPSE = 15;
    public static final int ENTITY_POSE = 16, ENTITY_APPEARANCE = 17;
    private VisualTape() {}

    public interface Writer { void write(DataOutputStream out) throws IOException; }
    public static byte[] bytes(Writer writer) {
        var bytes = new ByteArrayOutputStream();
        try { writer.write(new DataOutputStream(bytes)); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        return bytes.toByteArray();
    }

    public static final class Builder {
        private final Map<UUID, ByteArrayOutputStream> streams = new HashMap<>();
        private int size;
        private final Map<UUID, Integer> counts = new HashMap<>();
        private final Map<UUID, byte[]> lastHud = new HashMap<>();
        private final Map<UUID, Integer> hudKey = new HashMap<>();
        private final Set<UUID> full = new HashSet<>();
        private boolean limited;
        public int size() { return size; }
        public boolean limited() { return limited; }
        public void markLimited() { limited=true; }
        public boolean writable(UUID subject) { return !full.contains(subject) && size<MAX_ROUND; }
        public void append(UUID subject, int tick, int kind, byte[] payload) {
            if (full.contains(subject)) return;
            if (kind == HUD && lastHud.containsKey(subject) && tick-hudKey.getOrDefault(subject,0) < 100) {
                byte[] diff = difference(lastHud.get(subject),payload);
                if (diff.length < payload.length) { append(subject,tick,HUD_DIFF,diff); lastHud.put(subject,payload); return; }
            }
            if (kind == END_VIEW) lastHud.remove(subject);
            int length = 7 + payload.length;
            if (tick < 0 || payload.length > MAX_EVENT || (!streams.containsKey(subject) && streams.size() >= 129)) { limited = true; return; }
            if (size + length > MAX_ROUND || counts.getOrDefault(subject,0) >= 400000) { limited = true; full.add(subject); return; }
            var stream = streams.computeIfAbsent(subject, ignored -> new ByteArrayOutputStream());
            if (stream.size() + length > MAX_STREAM) { limited = true; full.add(subject); return; }
            try {
                var out = new DataOutputStream(stream);
                out.writeInt(tick); out.writeByte(kind); out.writeShort(payload.length); out.write(payload);
                size += length;
                counts.merge(subject,1,Integer::sum);
                if (kind == HUD) { lastHud.put(subject,payload); hudKey.put(subject,tick); }
            } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        }
        public Map<UUID, byte[]> compress() throws IOException {
            return compress(streams.keySet());
        }
        public Map<UUID, byte[]> compress(Set<UUID> participants) throws IOException {
            Map<UUID, byte[]> result = new LinkedHashMap<>();
            for (var entry : streams.entrySet()) {
                if (!WORLD.equals(entry.getKey()) && !participants.contains(entry.getKey())) continue;
                var packed = new ByteArrayOutputStream();
                try (var out = new DataOutputStream(new DeflaterOutputStream(packed))) {
                    out.writeInt(MAGIC); entry.getValue().writeTo(out);
                }
                result.put(entry.getKey(), packed.toByteArray());
            }
            return result;
        }
    }

    static byte[] difference(byte[] old, byte[] next) {
        return bytes(out -> {
            out.writeShort(next.length);
            for (int index = 0; index < next.length;) {
                if (index < old.length && next[index] == old[index]) { index++; continue; }
                int start = index++;
                while (index < next.length && (index >= old.length || old[index] != next[index])) index++;
                out.writeShort(start); out.writeShort(index-start); out.write(next,start,index-start);
            }
        });
    }
    public static byte[] applyDifference(byte[] old, DataInputStream in) throws IOException {
        int size = in.readUnsignedShort(); if (size > MAX_EVENT) throw new IOException("Oversized HUD change");
        var result = Arrays.copyOf(old,size); int previous = 0;
        while (in.available() > 0) {
            int start = in.readUnsignedShort(), length = in.readUnsignedShort();
            if (start < previous || length == 0 || start + length > size) throw new IOException("Invalid HUD change");
            in.readFully(result,start,length); previous = start+length;
        }
        return result;
    }

    public record Event(int tick, int kind, byte[] data) {
        public DataInputStream input() { return new DataInputStream(new ByteArrayInputStream(data)); }
    }
    public static List<Event> decode(byte[] packed) throws IOException {
        if (packed.length == 0) return List.of();
        if (packed.length > MAX_STREAM + PAGE) throw new IOException("Oversized visual stream");
        try (var in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(packed))))) {
            if (in.readInt() != MAGIC) throw new IOException("Unsupported visual stream");
            var result = new ArrayList<Event>();
            int used = 0, last = 0, high;
            while ((high = in.read()) != -1) {
                int tick = high << 24 | in.readUnsignedByte() << 16 | in.readUnsignedShort();
                int kind = in.readUnsignedByte(), length = in.readUnsignedShort();
                used += 7 + length;
                if (tick < last || tick > 1728000 || length > MAX_EVENT || used > MAX_STREAM || result.size() >= 400000) throw new IOException("Invalid visual event");
                byte[] data = new byte[length]; in.readFully(data);
                result.add(new Event(tick, kind, data)); last = tick;
            }
            return List.copyOf(result);
        }
    }
}
