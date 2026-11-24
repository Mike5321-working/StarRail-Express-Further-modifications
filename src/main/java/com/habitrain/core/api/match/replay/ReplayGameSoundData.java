package com.habitrain.core.api.match.replay;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compact server-recorded global match game sound timeline.
 *
 * <p>Replaces fragile client-polled kill-window sound recording with authoritative server-side capture
 * of {@code ServerLevel.playSound}, {@code playSeededSound}, {@code levelEvent}, and mod audio broadcasts.
 * Coordinates {@code (qx, qy, qz)} are quantized in 1/16th-block units relative to {@code LastMatchReplay.origin()}.</p>
 */
public final class ReplayGameSoundData {
    public static final int MAGIC = 0x48524753; // "HRGS"
    public static final int VERSION = 1;
    public static final int MAX_DICTIONARY = 2048;
    public static final int MAX_CUES = 32_768;
    public static final int MAX_COMPRESSED_BYTES = 256 * 1024;
    public static final int MAX_UNCOMPRESSED_BYTES = 2 * 1024 * 1024;
    public static final ReplayGameSoundData EMPTY = new ReplayGameSoundData(List.of(), List.of());

    /** Sound category mirroring vanilla {@code SoundSource} ordinals without client class links. */
    public static final byte CAT_MASTER  = 0;
    public static final byte CAT_MUSIC   = 1;
    public static final byte CAT_RECORDS = 2;
    public static final byte CAT_WEATHER = 3;
    public static final byte CAT_BLOCKS  = 4;
    public static final byte CAT_HOSTILE = 5;
    public static final byte CAT_NEUTRAL = 6;
    public static final byte CAT_PLAYERS = 7;
    public static final byte CAT_AMBIENT = 8;
    public static final byte CAT_VOICE   = 9;

    public enum AudibilityScope {
        /** Normal 3D positional world sound attenuated by Minecraft distance rules. */
        WORLD_3D,
        /** Match-wide non-positional cue (e.g., train horn, blackout alarm, settlement chime). */
        GLOBAL,
        /** Private sound sent only to {@code sourceActor} (audible only when following that player). */
        ACTOR_ONLY,
        /** Sound audible only to killer-faction players. */
        TEAM_KILLER,
        /** Positional sound played to all players except {@code sourceActor}. */
        EXCLUDING_ACTOR
    }

    /**
     * One server-recorded game sound cue.
     *
     * @param matchMs        milliseconds since match ACTIVE start
     * @param soundDictId    index into {@link ReplayGameSoundData#dictionary()}
     * @param sourceCategory category constant ({@code CAT_*})
     * @param qx             1/16-block X offset relative to {@code replay.origin().minX()}
     * @param qy             1/16-block Y offset relative to {@code replay.origin().minY()}
     * @param qz             1/16-block Z offset relative to {@code replay.origin().minZ()}
     * @param packedVolume   unsigned byte [0..255], decoded as {@code packedVolume / 64.0f}
     * @param packedPitch    unsigned byte [0..255], decoded as {@code 0.25f + packedPitch / 128.0f}
     * @param sourceActor    originating player/entity UUID if known
     * @param audibilityScope visibility/routing rule for POV & sandbox playback
     */
    public record Cue(
            int matchMs,
            int soundDictId,
            byte sourceCategory,
            short qx,
            short qy,
            short qz,
            int packedVolume,
            int packedPitch,
            @Nullable UUID sourceActor,
            AudibilityScope audibilityScope
    ) {
        public Cue {
            if (matchMs < 0) throw new IllegalArgumentException("Negative sound matchMs: " + matchMs);
            if (soundDictId < 0 || soundDictId >= MAX_DICTIONARY) {
                throw new IllegalArgumentException("Invalid soundDictId: " + soundDictId);
            }
            packedVolume = packedVolume & 0xFF;
            packedPitch = packedPitch & 0xFF;
            audibilityScope = audibilityScope == null ? AudibilityScope.WORLD_3D : audibilityScope;
        }

        public double blockX() { return qx / 16.0; }
        public double blockY() { return qy / 16.0; }
        public double blockZ() { return qz / 16.0; }

        public float volume() { return packedVolume / 64.0f; }
        public float pitch() { return 0.25f + packedPitch / 128.0f; }

        /** Standard Minecraft positional hearing radius for this cue's volume. */
        public float maxHearingDistance() {
            float v = volume();
            return v > 1.0f ? 16.0f * v : 16.0f;
        }

        public static int packVolume(float volume) {
            if (!Float.isFinite(volume) || volume <= 0f) return 0;
            return Math.clamp(Math.round(volume * 64.0f), 1, 255);
        }

        public static int packPitch(float pitch) {
            if (!Float.isFinite(pitch)) return 96; // 1.0f
            return Math.clamp(Math.round((pitch - 0.25f) * 128.0f), 0, 255);
        }
    }

    private final List<String> dictionary;
    private final List<Cue> cues;

    public ReplayGameSoundData(List<String> dictionary, List<Cue> cues) {
        List<String> dict = dictionary == null ? List.of() : List.copyOf(dictionary);
        if (dict.size() > MAX_DICTIONARY) {
            throw new IllegalArgumentException("Sound dictionary exceeds limit: " + dict.size());
        }
        List<Cue> list;
        if (cues == null || cues.isEmpty()) {
            list = List.of();
        } else {
            List<Cue> copy = new ArrayList<>(Math.min(cues.size(), MAX_CUES));
            for (int i = 0; i < cues.size() && copy.size() < MAX_CUES; i++) {
                Cue c = cues.get(i);
                if (c.soundDictId() >= dict.size()) {
                    throw new IllegalArgumentException("Sound dictId out of bounds: " + c.soundDictId());
                }
                copy.add(c);
            }
            copy.sort(Comparator.comparingInt(Cue::matchMs));
            list = List.copyOf(copy);
        }
        this.dictionary = dict;
        this.cues = list;
    }

    public List<String> dictionary() { return dictionary; }
    public List<Cue> cues() { return cues; }
    public boolean isEmpty() { return cues.isEmpty(); }
    public int size() { return cues.size(); }

    public String soundId(int dictId) {
        return dictId >= 0 && dictId < dictionary.size() ? dictionary.get(dictId) : "";
    }

    public String soundId(Cue cue) {
        return cue == null ? "" : soundId(cue.soundDictId());
    }

    /** O(log N) slice of all cues whose {@code matchMs} lies in {@code [startMs, endMs]}. */
    public List<Cue> cuesInWindow(int startMs, int endMs) {
        if (cues.isEmpty() || endMs < startMs) return List.of();
        int lo = lowerBound(startMs);
        if (lo >= cues.size()) return List.of();
        int hi = lo;
        while (hi < cues.size() && cues.get(hi).matchMs() <= endMs) hi++;
        return hi == lo ? List.of() : cues.subList(lo, hi);
    }

    private int lowerBound(int targetMs) {
        int lo = 0, hi = cues.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).matchMs() < targetMs) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    public byte[] toCompressedBytes() {
        if (cues.isEmpty()) return new byte[0];
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writeUncompressed(buf);
            byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            return gzip(raw);
        } finally {
            buf.release();
        }
    }

    public static ReplayGameSoundData fromCompressedBytes(byte[] compressed) {
        if (compressed == null || compressed.length == 0) return EMPTY;
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Compressed game sound blob too large: " + compressed.length);
        }
        byte[] raw = gunzip(compressed, MAX_UNCOMPRESSED_BYTES);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            ReplayGameSoundData data = readUncompressed(buf);
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing game sound bytes");
            return data;
        } finally {
            buf.release();
        }
    }

    public void writeUncompressed(FriendlyByteBuf buf) {
        buf.writeInt(MAGIC);
        buf.writeVarInt(VERSION);
        buf.writeVarInt(dictionary.size());
        for (String s : dictionary) buf.writeUtf(s, 128);

        List<UUID> actors = new ArrayList<>();
        Map<UUID, Integer> actorIndices = new HashMap<>();
        for (Cue cue : cues) {
            if (cue.sourceActor() != null && !actorIndices.containsKey(cue.sourceActor())) {
                actorIndices.put(cue.sourceActor(), actors.size());
                actors.add(cue.sourceActor());
            }
        }
        buf.writeVarInt(actors.size());
        for (UUID actor : actors) buf.writeUUID(actor);

        buf.writeVarInt(cues.size());
        int prevMs = 0;
        for (Cue cue : cues) {
            buf.writeVarInt(cue.matchMs() - prevMs);
            prevMs = cue.matchMs();
            buf.writeVarInt(cue.soundDictId());
            int catScope = ((cue.sourceCategory() & 0x0F) << 4) | (cue.audibilityScope().ordinal() & 0x0F);
            buf.writeByte(catScope);
            buf.writeShort(cue.qx()).writeShort(cue.qy()).writeShort(cue.qz());
            buf.writeByte(cue.packedVolume()).writeByte(cue.packedPitch());
            int actorIdx = cue.sourceActor() == null ? 0 : actorIndices.get(cue.sourceActor()) + 1;
            buf.writeVarInt(actorIdx);
        }
    }

    public static ReplayGameSoundData readUncompressed(FriendlyByteBuf buf) {
        if (buf.readInt() != MAGIC) throw new IllegalArgumentException("Invalid game sound magic");
        int ver = buf.readVarInt();
        if (ver != VERSION) throw new IllegalArgumentException("Unsupported game sound version: " + ver);
        int dictSize = buf.readVarInt();
        if (dictSize < 0 || dictSize > MAX_DICTIONARY) throw new IllegalArgumentException("Bad sound dict size: " + dictSize);
        List<String> dict = new ArrayList<>(dictSize);
        for (int i = 0; i < dictSize; i++) dict.add(buf.readUtf(128));

        int actorCount = buf.readVarInt();
        if (actorCount < 0 || actorCount > 1024) throw new IllegalArgumentException("Bad sound actor count: " + actorCount);
        List<UUID> actors = new ArrayList<>(actorCount);
        for (int i = 0; i < actorCount; i++) actors.add(buf.readUUID());

        int cueCount = buf.readVarInt();
        if (cueCount < 0 || cueCount > MAX_CUES) throw new IllegalArgumentException("Bad sound cue count: " + cueCount);
        AudibilityScope[] scopes = AudibilityScope.values();
        List<Cue> cues = new ArrayList<>(cueCount);
        int ms = 0;
        for (int i = 0; i < cueCount; i++) {
            int dt = buf.readVarInt();
            if (dt < 0) throw new IllegalArgumentException("Negative sound delta ms");
            ms += dt;
            int dictId = buf.readVarInt();
            int catScope = buf.readUnsignedByte();
            byte cat = (byte) ((catScope >>> 4) & 0x0F);
            int scopeOrd = catScope & 0x0F;
            AudibilityScope scope = scopeOrd < scopes.length ? scopes[scopeOrd] : AudibilityScope.WORLD_3D;
            short qx = buf.readShort(), qy = buf.readShort(), qz = buf.readShort();
            int vol = buf.readUnsignedByte();
            int pitch = buf.readUnsignedByte();
            int actorIdx = buf.readVarInt();
            if (actorIdx < 0 || actorIdx > actors.size()) throw new IllegalArgumentException("Bad sound actor index");
            UUID actor = actorIdx == 0 ? null : actors.get(actorIdx - 1);
            cues.add(new Cue(ms, dictId, cat, qx, qy, qz, vol, pitch, actor, scope));
        }
        return cues.isEmpty() ? EMPTY : new ReplayGameSoundData(dict, cues);
    }

    public static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(raw);
            }
            return baos.toByteArray();
        } catch (IOException io) {
            throw new IllegalStateException("GZIP compression failed", io);
        }
    }

    public static byte[] gunzip(byte[] compressed, int maxBytes) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, compressed.length * 4));
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = gz.read(chunk)) >= 0) {
                total += n;
                if (total > maxBytes) throw new IllegalArgumentException("Decompressed replay block exceeds limit: " + maxBytes);
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException io) {
            throw new IllegalArgumentException("Invalid GZIP replay payload", io);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReplayGameSoundData other)) return false;
        return dictionary.equals(other.dictionary) && cues.equals(other.cues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dictionary, cues);
    }
}
