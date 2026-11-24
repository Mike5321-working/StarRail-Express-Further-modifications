package com.habitrain.core.network.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.LastMatchReplay.Position;
import com.habitrain.core.api.match.replay.LastMatchReplay.Sample;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.*;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

/**
 * Tracks use per-point time/coordinate deltas; decimation is deterministic and bounded.
 *
 * <p>Supports both legacy {@code v1} raw tracks ({@link #encodeWithStatus}) and {@code v2}
 * high-rate pose/item/status/disguise GZIP-compressed delta tracks ({@link #encodeV2WithStatus}).
 * {@link #decode(byte[], int, int)} transparently decodes both formats.</p>
 */
public final class ReplayTracks {
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_TRACK = 2048;
    public static final int MAX_V2_BYTES = 512 * 1024;
    public static final int MAX_TRACK_V2 = 24_000;
    private static final int MAX_V2_UNCOMPRESSED = 8 * 1024 * 1024;
    private static final int IMPULSE_ACTIONS = ACT_SWING_MAIN | ACT_SWING_OFF | ACT_HURT_FLASH;

    // 4-byte v2 magic: first byte 0xFF followed by 'H'(0x48) cannot be a valid v1 track count (<= 2048).
    private static final byte MAGIC_0 = (byte) 0xFF;
    private static final byte MAGIC_1 = 'H';
    private static final byte MAGIC_2 = 'T';
    private static final byte MAGIC_3 = '2';

    private ReplayTracks() {}

    public static LastMatchReplay expand(ReplayPackets.LastMatchSummaryS2C packet) {
        var replay = packet.replay();
        int playerCount = replay.participants().size();
        var tracks = decode(packet.tracks(), playerCount + replay.drones().size(), replay.durationTicks());
        var players = new ArrayList<Participant>(playerCount);
        for (int index = 0; index < playerCount; index++) {
            var player = replay.participants().get(index);
            players.add(new Participant(player.id(), player.name(), player.startRole(), player.startColor(),
                    player.endRole(), player.endColor(), player.faction(), player.winner(), player.survivalSeconds(),
                    player.kills(), player.score(), player.itemUses(), tracks.get(index)));
        }
        var drones = new ArrayList<Drone>(replay.drones().size());
        for (int index = 0; index < replay.drones().size(); index++) {
            var drone = replay.drones().get(index);
            drones.add(new Drone(drone.id(), drone.owner(), tracks.get(playerCount + index)));
        }
        return new LastMatchReplay(replay.roundId(), replay.playbackAnchorTick(), replay.durationTicks(), replay.mapName(),
                replay.modeId(), replay.winStatus(), replay.winKind(), replay.customWinId(), replay.customTitleJson(),
                replay.customSubtitleJson(), replay.customColor(), replay.origin(), replay.manifest(), players, replay.events(),
                replay.spans(), replay.mvp(), replay.killCount(), replay.limited(), drones, replay.killSounds(),
                replay.blockDeltas(), replay.entityTracks(), replay.skillEvents(), replay.gameSounds());
    }

    public static boolean isV2(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == MAGIC_0 && bytes[1] == MAGIC_1
                && bytes[2] == MAGIC_2 && bytes[3] == MAGIC_3;
    }

    /** Legacy v1 append (retains existing collinear simplification semantics). */
    public static void append(List<Sample> track, Sample point) {
        if (!track.isEmpty() && point.tick() < track.getLast().tick()) return;
        int n = track.size();
        if (n >= 2 && redundant(track.get(n - 2), track.getLast(), point)) track.set(n - 1, point);
        else track.add(point);
        if (track.size() > MAX_TRACK) thin(track);
    }

    /** High-rate v2 append (1~2 tick dynamic keyframes preserving pitch, headYaw, actions, items, status, disguise). */
    public static void appendV2(List<Sample> track, Sample point) {
        if (!track.isEmpty() && point.tick() < track.getLast().tick()) return;
        if (!track.isEmpty() && point.tick() == track.getLast().tick()) {
            track.set(track.size() - 1, point);
            return;
        }
        int n = track.size();
        if (n >= 2 && redundantV2(track.get(n - 2), track.getLast(), point)) {
            track.set(n - 1, point);
        } else {
            track.add(point);
        }
        if (track.size() > MAX_TRACK_V2) {
            compactV2(track, Collections.emptyNavigableSet());
            if (track.size() > MAX_TRACK_V2) forceThin(track, Collections.emptyNavigableSet());
        }
    }

    private static boolean redundant(Sample a, Sample b, Sample c) {
        if (a.flags() != b.flags() || b.flags() != c.flags() || (b.flags() & BREAK) != 0
                || a.yaw() != b.yaw() || b.yaw() != c.yaw() || c.tick() <= a.tick()) return false;
        double f = (double) (b.tick() - a.tick()) / (c.tick() - a.tick());
        return interpolated(a.position().x(), b.position().x(), c.position().x(), f)
                && interpolated(a.position().y(), b.position().y(), c.position().y(), f)
                && interpolated(a.position().z(), b.position().z(), c.position().z(), f);
    }

    private static boolean redundantV2(Sample a, Sample b, Sample c) {
        if (a.flags() != b.flags() || b.flags() != c.flags() || (b.flags() & BREAK) != 0) return false;
        if ((b.actionFlags() & IMPULSE_ACTIONS) != 0) return false;
        if (a.actionFlags() != b.actionFlags() || b.actionFlags() != c.actionFlags()) return false;
        if (a.statusFlags() != b.statusFlags() || b.statusFlags() != c.statusFlags()) return false;
        if (!a.mainHandItemId().equals(b.mainHandItemId()) || !b.mainHandItemId().equals(c.mainHandItemId())) return false;
        if (!a.offHandItemId().equals(b.offHandItemId()) || !b.offHandItemId().equals(c.offHandItemId())) return false;
        if (!Objects.equals(a.disguiseTargetUuid(), b.disguiseTargetUuid())
                || !Objects.equals(b.disguiseTargetUuid(), c.disguiseTargetUuid())) return false;
        if (c.tick() <= a.tick() || c.tick() - a.tick() > 12) return false;
        if (angleDeltaByte(a.yaw(), b.yaw()) > 1 || angleDeltaByte(b.yaw(), c.yaw()) > 1) return false;
        if (angleDeltaByte(a.headYaw(), b.headYaw()) > 1 || angleDeltaByte(b.headYaw(), c.headYaw()) > 1) return false;
        if (Math.abs(a.pitch() - b.pitch()) > 1 || Math.abs(b.pitch() - c.pitch()) > 1) return false;
        double f = (double) (b.tick() - a.tick()) / (c.tick() - a.tick());
        return interpolated(a.position().x(), b.position().x(), c.position().x(), f, 0.35)
                && interpolated(a.position().y(), b.position().y(), c.position().y(), f, 0.35)
                && interpolated(a.position().z(), b.position().z(), c.position().z(), f, 0.35);
    }

    private static int angleDeltaByte(byte a, byte b) {
        int d = ((b - a) & 0xFF);
        return Math.min(d, 256 - d);
    }

    private static boolean interpolated(int a, int b, int c, double f) {
        return interpolated(a, b, c, f, 0.01);
    }

    private static boolean interpolated(int a, int b, int c, double f, double eps) {
        return Math.abs(a + (c - a) * f - b) < eps;
    }

    public static void compact(List<Sample> track) {
        compact(track, Collections.emptyNavigableSet());
    }

    private static void compact(List<Sample> track, NavigableSet<Integer> highlights) {
        if (track.size() < 3) return;
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

    private static void compactV2(List<Sample> track, NavigableSet<Integer> highlights) {
        if (track.size() < 3) return;
        boolean remove = false;
        int write = 1;
        for (int i = 1; i < track.size() - 1; i++) {
            Sample prev = track.get(i - 1);
            Sample p = track.get(i);
            Sample next = track.get(i + 1);
            boolean stable = prev.flags() == p.flags() && p.flags() == next.flags()
                    && (p.actionFlags() & IMPULSE_ACTIONS) == 0
                    && prev.statusFlags() == p.statusFlags()
                    && prev.mainHandItemId().equals(p.mainHandItemId())
                    && prev.offHandItemId().equals(p.offHandItemId())
                    && Objects.equals(prev.disguiseTargetUuid(), p.disguiseTargetUuid());
            if (!stable || (p.flags() & BREAK) != 0 || nearHighlight(p, highlights, 20)
                    || (remove = !remove)) {
                track.set(write++, p);
            }
        }
        track.set(write++, track.getLast());
        track.subList(write, track.size()).clear();
    }

    private static boolean nearHighlight(Sample point, NavigableSet<Integer> highlights) {
        return nearHighlight(point, highlights, 10);
    }

    private static boolean nearHighlight(Sample point, NavigableSet<Integer> highlights, int radiusTicks) {
        if (highlights == null || highlights.isEmpty()) return false;
        Integer next = highlights.ceiling(point.tick() - radiusTicks);
        return next != null && next <= (long) point.tick() + radiusTicks;
    }

    public record Encoding(byte[] bytes, boolean thinned) {}

    public static byte[] encode(List<? extends List<Sample>> tracks) {
        return encodeWithStatus(tracks).bytes();
    }

    public static Encoding encodeWithStatus(List<? extends List<Sample>> tracks) {
        return encodeWithStatus(tracks, Integer.MAX_VALUE, Collections.nCopies(tracks.size(), Collections.emptyNavigableSet()));
    }

    /** Legacy v1 encoder (exact 64 KiB budget, kept for backward compatibility and existing tests). */
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

    public static byte[] encodeV2(List<? extends List<Sample>> tracks) {
        return encodeV2WithStatus(tracks, Integer.MAX_VALUE,
                Collections.nCopies(tracks.size(), Collections.emptyNavigableSet())).bytes();
    }

    /** High-fidelity v2 track encoder with item/disguise dictionaries, change bitmasks, and GZIP compression. */
    public static Encoding encodeV2WithStatus(List<? extends List<Sample>> tracks, int duration,
                                              List<? extends NavigableSet<Integer>> highlights) {
        return encodeV2WithStatus(tracks, duration, highlights, MAX_V2_BYTES);
    }

    public static Encoding encodeV2WithStatus(List<? extends List<Sample>> tracks, int duration,
                                              List<? extends NavigableSet<Integer>> highlights, int maxBytes) {
        int budget = Math.clamp(maxBytes, 1024, MAX_V2_BYTES);
        if (duration < 0 || tracks.size() > MAX_PLAYERS + MAX_DRONES || highlights.size() != tracks.size()) {
            throw new IllegalArgumentException("Tracks exceed limits");
        }
        var work = new ArrayList<List<Sample>>(tracks.size());
        boolean thinned = false;
        for (int idx = 0; idx < tracks.size(); idx++) {
            var track = tracks.get(idx);
            int previous = 0;
            for (Sample point : track) {
                if (point.tick() < previous || point.tick() > duration) throw new IllegalArgumentException("Bad track time");
                previous = point.tick();
            }
            var copy = new ArrayList<>(track);
            var protectedTicks = highlights.get(idx);
            while (copy.size() > MAX_TRACK_V2) {
                int prevSize = copy.size();
                compactV2(copy, protectedTicks);
                if (copy.size() == prevSize) forceThin(copy, protectedTicks);
                if (copy.size() == prevSize) forceThin(copy, Collections.emptyNavigableSet());
                thinned = true;
            }
            work.add(copy);
        }
        while (true) {
            byte[] encoded = serializeV2(work);
            if (encoded.length <= budget) {
                return new Encoding(encoded, thinned);
            }
            int prevTotal = totalSamples(work);
            thinned = true;
            for (int i = 0; i < work.size(); i++) compactV2(work.get(i), highlights.get(i));
            if (totalSamples(work) == prevTotal) {
                for (int i = 0; i < work.size(); i++) forceThin(work.get(i), highlights.get(i));
            }
            if (totalSamples(work) == prevTotal) {
                for (var t : work) forceThin(t, Collections.emptyNavigableSet());
            }
            if (totalSamples(work) >= prevTotal) {
                throw new IllegalArgumentException("Replay v2 tracks exceed budget (" + budget + ") after maximum thinning");
            }
        }
    }

    private static byte[] serializeV2(List<List<Sample>> tracks) {
        List<String> items = new ArrayList<>();
        Map<String, Integer> itemIdx = new HashMap<>();
        items.add("");
        itemIdx.put("", 0);

        List<UUID> disguises = new ArrayList<>();
        Map<UUID, Integer> disguiseIdx = new HashMap<>();

        for (var track : tracks) {
            for (Sample s : track) {
                if (!itemIdx.containsKey(s.mainHandItemId())) {
                    itemIdx.put(s.mainHandItemId(), items.size());
                    items.add(s.mainHandItemId());
                }
                if (!itemIdx.containsKey(s.offHandItemId())) {
                    itemIdx.put(s.offHandItemId(), items.size());
                    items.add(s.offHandItemId());
                }
                if (s.disguiseTargetUuid() != null && !disguiseIdx.containsKey(s.disguiseTargetUuid())) {
                    disguiseIdx.put(s.disguiseTargetUuid(), disguises.size());
                    disguises.add(s.disguiseTargetUuid());
                }
            }
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(items.size());
            for (String item : items) buf.writeUtf(item, 128);
            buf.writeVarInt(disguises.size());
            for (UUID id : disguises) buf.writeUUID(id);

            for (var track : tracks) {
                buf.writeVarInt(track.size());
                int t = 0, x = 0, y = 0, z = 0;
                byte prevPitch = 0;
                int prevAct = ACT_ON_GROUND, prevStatus = 0;
                int prevMain = 0, prevOff = 0, prevDisguise = 0;
                for (Sample s : track) {
                    buf.writeVarInt(s.tick() - t);
                    signed(buf, s.position().x() - x);
                    signed(buf, s.position().y() - y);
                    signed(buf, s.position().z() - z);
                    buf.writeByte(s.yaw()).writeByte(s.flags());

                    int mainId = itemIdx.get(s.mainHandItemId());
                    int offId = itemIdx.get(s.offHandItemId());
                    int disgId = s.disguiseTargetUuid() == null ? 0 : disguiseIdx.get(s.disguiseTargetUuid()) + 1;

                    int mask = 0;
                    if (s.pitch() != prevPitch) mask |= 1;
                    if (s.headYaw() != s.yaw()) mask |= 2;
                    if (s.actionFlags() != prevAct) mask |= 4;
                    if (s.statusFlags() != prevStatus) mask |= 8;
                    if (mainId != prevMain) mask |= 16;
                    if (offId != prevOff) mask |= 32;
                    if (disgId != prevDisguise) mask |= 64;
                    buf.writeByte(mask);

                    if ((mask & 1) != 0) { buf.writeByte(s.pitch()); prevPitch = s.pitch(); }
                    if ((mask & 2) != 0) { buf.writeByte(s.headYaw()); }
                    if ((mask & 4) != 0) { buf.writeVarInt(s.actionFlags()); prevAct = s.actionFlags(); }
                    if ((mask & 8) != 0) { buf.writeVarInt(s.statusFlags()); prevStatus = s.statusFlags(); }
                    if ((mask & 16) != 0) { buf.writeVarInt(mainId); prevMain = mainId; }
                    if ((mask & 32) != 0) { buf.writeVarInt(offId); prevOff = offId; }
                    if ((mask & 64) != 0) { buf.writeVarInt(disgId); prevDisguise = disgId; }

                    t = s.tick();
                    x = s.position().x();
                    y = s.position().y();
                    z = s.position().z();
                }
            }
            byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            byte[] gz = ReplayGameSoundData.gzip(raw);
            byte[] out = new byte[4 + gz.length];
            out[0] = MAGIC_0;
            out[1] = MAGIC_1;
            out[2] = MAGIC_2;
            out[3] = MAGIC_3;
            System.arraycopy(gz, 0, out, 4, gz.length);
            return out;
        } finally {
            buf.release();
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
                next = next.withFlags((byte) (next.flags() | BREAK));
            }
            track.set(write++, next);
            discontinuity = false;
        }
        track.subList(write, size).clear();
    }

    public static List<List<Sample>> decode(byte[] bytes, int players, int duration) {
        if (players < 0 || players > MAX_PLAYERS + MAX_DRONES) throw new IllegalArgumentException("Tracks exceed limits");
        if (isV2(bytes)) return decodeV2(bytes, players, duration);
        return decodeV1(bytes, players, duration);
    }

    private static List<List<Sample>> decodeV1(byte[] bytes, int players, int duration) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Tracks exceed limits");
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

    private static List<List<Sample>> decodeV2(byte[] bytes, int players, int duration) {
        if (bytes.length > MAX_V2_BYTES) throw new IllegalArgumentException("Tracks v2 exceed limits");
        byte[] gz = Arrays.copyOfRange(bytes, 4, bytes.length);
        byte[] raw = ReplayGameSoundData.gunzip(gz, MAX_V2_UNCOMPRESSED);
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            int itemCount = buf.readVarInt();
            if (itemCount < 1 || itemCount > 4096) throw new IllegalArgumentException("Bad item dict size");
            List<String> items = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) items.add(buf.readUtf(128));

            int disgCount = buf.readVarInt();
            if (disgCount < 0 || disgCount > 1024) throw new IllegalArgumentException("Bad disguise dict size");
            List<UUID> disguises = new ArrayList<>(disgCount);
            for (int i = 0; i < disgCount; i++) disguises.add(buf.readUUID());

            List<List<Sample>> result = new ArrayList<>(players);
            for (int i = 0; i < players; i++) {
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_TRACK_V2) throw new IllegalArgumentException("Bad v2 track count");
                List<Sample> track = new ArrayList<>(count);
                int t = 0, x = 0, y = 0, z = 0;
                byte pitch = 0;
                int act = ACT_ON_GROUND, status = 0;
                int mainId = 0, offId = 0, disgId = 0;
                for (int n = 0; n < count; n++) {
                    int dt = buf.readVarInt();
                    if (dt < 0 || dt > duration - t) throw new IllegalArgumentException("Bad track time");
                    t += dt;
                    x = coordinate(x, buf); y = coordinate(y, buf); z = coordinate(z, buf);
                    byte yaw = buf.readByte();
                    byte flags = buf.readByte();
                    int mask = buf.readUnsignedByte();
                    if ((mask & 1) != 0) pitch = buf.readByte();
                    byte headYaw = (mask & 2) != 0 ? buf.readByte() : yaw;
                    if ((mask & 4) != 0) act = buf.readVarInt();
                    if ((mask & 8) != 0) status = buf.readVarInt();
                    if ((mask & 16) != 0) {
                        mainId = buf.readVarInt();
                        if (mainId < 0 || mainId >= items.size()) throw new IllegalArgumentException("Bad mainHand index");
                    }
                    if ((mask & 32) != 0) {
                        offId = buf.readVarInt();
                        if (offId < 0 || offId >= items.size()) throw new IllegalArgumentException("Bad offHand index");
                    }
                    if ((mask & 64) != 0) {
                        disgId = buf.readVarInt();
                        if (disgId < 0 || disgId > disguises.size()) throw new IllegalArgumentException("Bad disguise index");
                    }
                    track.add(new Sample(t, new Position((short) x, (short) y, (short) z), yaw, flags,
                            pitch, headYaw, act, status, items.get(mainId), items.get(offId),
                            disgId == 0 ? null : disguises.get(disgId - 1)));
                }
                result.add(List.copyOf(track));
            }
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing replay v2 track data");
            return List.copyOf(result);
        } finally {
            buf.release();
        }
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
