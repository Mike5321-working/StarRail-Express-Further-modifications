package com.habitrain.core.network.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay.Position;
import com.habitrain.core.api.match.replay.LastMatchReplay.Sample;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Collections;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

/** Tracks use per-point time/coordinate deltas; decimation is deterministic and bounded. */
public final class ReplayTracks {
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_TRACK = 2048;
    private ReplayTracks() {}

    public static void append(List<Sample> track, Sample point) {
        if (!track.isEmpty() && point.tick() < track.getLast().tick()) return;
        int n = track.size();
        if (n >= 2 && redundant(track.get(n - 2), track.getLast(), point)) track.set(n - 1, point);
        else track.add(point);
        if (track.size() > MAX_TRACK) thin(track);
    }

    private static boolean redundant(Sample a, Sample b, Sample c) {
        if (a.flags() != b.flags() || b.flags() != c.flags() || (b.flags() & BREAK) != 0
                || a.yaw() != b.yaw() || b.yaw() != c.yaw() || c.tick() <= a.tick()) return false;
        double f = (double) (b.tick() - a.tick()) / (c.tick() - a.tick());
        return interpolated(a.position().x(), b.position().x(), c.position().x(), f)
                && interpolated(a.position().y(), b.position().y(), c.position().y(), f)
                && interpolated(a.position().z(), b.position().z(), c.position().z(), f);
    }

    private static boolean interpolated(int a, int b, int c, double f) {
        return Math.abs(a + (c - a) * f - b) < 0.01;
    }

    public static void compact(List<Sample> track) {
        compact(track, Collections.emptyNavigableSet());
    }

    private static void compact(List<Sample> track, NavigableSet<Integer> highlights) {
        if (track.size() < 3) return;
        // Preserve transitions and both ends as long as there is space to do so.
        boolean remove = false;
        int write = 1;
        for (int i = 1; i < track.size() - 1; i++) {
            Sample p = track.get(i);
            boolean stable = track.get(i - 1).flags() == p.flags() && p.flags() == track.get(i + 1).flags();
            if (!stable || (p.flags() & BREAK) != 0 || nearHighlight(p, highlights)
                    || (remove = !remove)) track.set(write++, p);
        }
        track.set(write++, track.getLast());
        track.subList(write, track.size()).clear();
    }

    private static boolean nearHighlight(Sample point, NavigableSet<Integer> highlights) {
        Integer next = highlights.ceiling(point.tick() - 10);
        return next != null && next <= (long) point.tick() + 10;
    }

    public record Encoding(byte[] bytes, boolean thinned) {}

    public static byte[] encode(List<? extends List<Sample>> tracks) {
        return encodeWithStatus(tracks).bytes();
    }

    public static Encoding encodeWithStatus(List<? extends List<Sample>> tracks) {
        return encodeWithStatus(tracks, Integer.MAX_VALUE, Collections.nCopies(tracks.size(), Collections.emptyNavigableSet()));
    }

    public static Encoding encodeWithStatus(List<? extends List<Sample>> tracks, int duration,
                                            List<? extends NavigableSet<Integer>> highlights) {
        if (duration < 0 || tracks.size() > MAX_PLAYERS + MAX_DRONES || highlights.size() != tracks.size()) {
            throw new IllegalArgumentException("Tracks exceed limits");
        }
        var work = new ArrayList<List<Sample>>();
        boolean thinned = false;
        for (var track : tracks) {
            int previous = 0;
            for (Sample point : track) {
                if (point.tick() < previous || point.tick() > duration) throw new IllegalArgumentException("Bad track time");
                previous = point.tick();
            }
            var copy = new ArrayList<>(track);
            var protectedTicks = highlights.get(work.size());
            while (copy.size() > MAX_TRACK) {
                int previousSize = copy.size();
                compact(copy, protectedTicks);
                if (copy.size() == previousSize) forceThin(copy, protectedTicks);
                if (copy.size() == previousSize) forceThin(copy, Collections.emptyNavigableSet());
                thinned = true;
            }
            work.add(copy);
        }
        while (true) {
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                for (var track : work) {
                    buf.writeVarInt(track.size());
                    int t = 0, x = 0, y = 0, z = 0;
                    for (Sample point : track) {
                        buf.writeVarInt(point.tick() - t);
                        signed(buf, point.position().x() - x);
                        signed(buf, point.position().y() - y);
                        signed(buf, point.position().z() - z);
                        buf.writeByte(point.yaw()).writeByte(point.flags());
                        t = point.tick(); x = point.position().x(); y = point.position().y(); z = point.position().z();
                    }
                    if (buf.readableBytes() > MAX_BYTES) break;
                }
                if (buf.readableBytes() <= MAX_BYTES) {
                    byte[] result = new byte[buf.readableBytes()];
                    buf.readBytes(result);
                    return new Encoding(result, thinned);
                }
            } finally { buf.release(); }
            int prevTotalSamples = totalSamples(work);
            thinned = true;
            for (int index = 0; index < work.size(); index++) compact(work.get(index), highlights.get(index));
            if (totalSamples(work) == prevTotalSamples) {
                for (int index = 0; index < work.size(); index++) forceThin(work.get(index), highlights.get(index));
            }
            if (totalSamples(work) == prevTotalSamples) {
                for (var track : work) forceThin(track, Collections.emptyNavigableSet());
            }
            if (totalSamples(work) >= prevTotalSamples) {
                throw new IllegalArgumentException("Replay tracks exceed MAX_BYTES (" + MAX_BYTES + ") after maximum thinning");
            }
        }
    }

    private static int totalSamples(List<List<Sample>> tracks) {
        int sum = 0;
        for (var track : tracks) sum += track.size();
        return sum;
    }

    private static void thin(List<Sample> track) {
        int n = track.size();
        compact(track);
        if (track.size() == n) forceThin(track, Collections.emptyNavigableSet());
    }

    private static void forceThin(List<Sample> track, NavigableSet<Integer> highlights) {
        int size = track.size();
        if (size <= 2) return;
        int write = 1;
        boolean discontinuity = false;
        byte previous = track.getFirst().flags();
        for (int index = 1; index < size; index++) {
            Sample next = track.get(index);
            discontinuity |= previous != next.flags() || (next.flags() & BREAK) != 0;
            previous = next.flags();
            if (index % 2 != 0 && index != size - 1 && !nearHighlight(next, highlights)) continue;
            if (discontinuity) {
                next = new Sample(next.tick(), next.position(), next.yaw(), (byte) (next.flags() | BREAK));
            }
            track.set(write++, next);
            discontinuity = false;
        }
        track.subList(write, size).clear();
    }

    public static List<List<Sample>> decode(byte[] bytes, int players, int duration) {
        if (bytes.length > MAX_BYTES || players < 0 || players > MAX_PLAYERS + MAX_DRONES) throw new IllegalArgumentException("Tracks exceed limits");
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            List<List<Sample>> result = new ArrayList<>(players);
            for (int i = 0; i < players; i++) {
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_TRACK || count > buf.readableBytes() / 6) throw new IllegalArgumentException("Bad track count");
                List<Sample> track = new ArrayList<>(count);
                int t = 0, x = 0, y = 0, z = 0;
                for (int n = 0; n < count; n++) {
                    int dt = buf.readVarInt();
                    if (dt < 0 || dt > duration - t) throw new IllegalArgumentException("Bad track time");
                    t += dt;
                    x = coordinate(x, buf); y = coordinate(y, buf); z = coordinate(z, buf);
                    track.add(new Sample(t, new Position((short) x, (short) y, (short) z), buf.readByte(), buf.readByte()));
                }
                result.add(List.copyOf(track));
            }
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing replay track data");
            return List.copyOf(result);
        } finally { buf.release(); }
    }

    private static void signed(FriendlyByteBuf buf, int n) { buf.writeVarInt((n << 1) ^ (n >> 31)); }
    private static int coordinate(int prev, FriendlyByteBuf buf) {
        int v = buf.readVarInt();
        if (v < 0 || v > 131070) throw new IllegalArgumentException("Bad coordinate delta");
        int n = prev + ((v >>> 1) ^ -(v & 1));
        if (n < Short.MIN_VALUE || n > Short.MAX_VALUE) throw new IllegalArgumentException("Bad coordinate");
        return n;
    }
}
