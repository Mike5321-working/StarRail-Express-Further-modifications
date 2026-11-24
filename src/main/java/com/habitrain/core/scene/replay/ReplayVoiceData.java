package com.habitrain.core.scene.replay;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Pure-Java binary container for server-recorded match voice tracks ({@code HRVC v2}, fully backward
 * compatible with {@code HRVC v1}).
 *
 * <p>Audio is stored as 24 kHz mono 4-bit IMA ADPCM (~12 KB/s per active utterance). In {@code v2},
 * every participant's speech from match {@code ACTIVE} through settlement {@code +1000 ms} is stored
 * as separate VAD-trimmed per-speaker {@link VoiceUtterance} segments together with recorded
 * {@link VoiceTimelineMeta} (role voice policies, mute spans, private channels, and group timelines)
 * so {@link ReplayVoiceAudibilityResolver} can reconstruct exact per-listener spatial audio offline.</p>
 */
public final class ReplayVoiceData {
    public static final int MAGIC = 0x48525643; // "HRVC"
    public static final int VERSION = 1;
    public static final int VERSION_V2 = 2;
    public static final int SAMPLE_RATE = 24_000;
    public static final int VOICECHAT_SAMPLE_RATE = 48_000;
    public static final int MAX_CLIPS = 32;
    public static final int MAX_CLIP_SAMPLES = SAMPLE_RATE * 6;
    public static final int MAX_UTTERANCES = 32_768;
    public static final int MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 30;
    public static final int MAX_POLICIES = 256;
    public static final int MAX_STATE_SPANS = 32_768;
    public static final int MAX_VOICE_BYTES = 64 * 1024 * 1024;
    public static final ReplayVoiceData EMPTY = new ReplayVoiceData(List.of());

    // SpeakerStateSpan stateFlags bitmask
    public static final int STATE_MUTED_SLOTH    = 1 << 0;
    public static final int STATE_MUTED_MIME     = 1 << 1;
    public static final int STATE_MUTED_DRONE    = 1 << 2;
    public static final int STATE_RADIO_CHANNEL  = 1 << 3;
    public static final int STATE_KILLER_CHANNEL = 1 << 4;
    public static final int STATE_GROUP_OPEN     = 1 << 5;
    public static final int STATE_DEAD           = 1 << 6;
    public static final int STATE_ABSENT         = 1 << 7;

    private static final int V2_FLAG_HAS_META = 1 << 0;
    private static final int V2_FLAG_HAS_CLIPS = 1 << 1;
    private static final int V2_FLAG_TRUNCATED = 1 << 2;

    private static final int[] INDEX_TABLE = {
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
    };

    private static final int[] STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    /** Logical channel mode captured on the server when the utterance was transmitted. */
    public enum ChannelMode {
        NORMAL_OPEN,
        WHISPER,
        KILLER_PRIVATE,
        RADIO,
        GROUP
    }

    /** Legacy v1 pre-mixed proximity voice segment anchored to an event in {@code LastMatchReplay.events()}. */
    public record Clip(int eventIndex, int tick, int offsetMs, int sampleCount, byte[] adpcm) {
        public Clip {
            if (eventIndex < 0) throw new IllegalArgumentException("Negative eventIndex");
            if (tick < 0) throw new IllegalArgumentException("Negative tick");
            if (sampleCount < 0 || sampleCount > MAX_CLIP_SAMPLES) {
                throw new IllegalArgumentException("Bad voice sample count: " + sampleCount);
            }
            adpcm = adpcm == null ? new byte[0] : adpcm.clone();
        }

        public byte[] adpcm() { return adpcm.clone(); }
        public double durationSeconds() { return sampleCount / (double) SAMPLE_RATE; }
        public short[] decode24k() { return decodeAdpcm(adpcm, sampleCount); }
        public short[] decode48k() { return upsample24kTo48k(decodeAdpcm(adpcm, sampleCount)); }
    }

    /** One per-speaker VAD-segmented utterance stored in {@code HRVC v2}. */
    public record VoiceUtterance(
            UUID speakerId,
            long startMs,
            int sampleCount24k,
            float baseMaxDist,
            ChannelMode channelMode,
            @Nullable UUID groupId,
            byte[] adpcmBytes,
            float rmsEnergy
    ) {
        public VoiceUtterance {
            Objects.requireNonNull(speakerId, "speakerId");
            if (startMs < 0) throw new IllegalArgumentException("Negative utterance startMs: " + startMs);
            if (sampleCount24k < 0 || sampleCount24k > MAX_UTTERANCE_SAMPLES) {
                throw new IllegalArgumentException("Bad utterance sampleCount24k: " + sampleCount24k);
            }
            if (!Float.isFinite(baseMaxDist) || baseMaxDist < 0f) {
                throw new IllegalArgumentException("Bad baseMaxDist: " + baseMaxDist);
            }
            channelMode = channelMode == null ? ChannelMode.NORMAL_OPEN : channelMode;
            adpcmBytes = adpcmBytes == null ? new byte[0] : adpcmBytes.clone();
            rmsEnergy = Float.isFinite(rmsEnergy) ? Math.clamp(rmsEnergy, 0f, 1f) : 0f;
        }

        /** Convenience constructor computing normalized RMS from ADPCM bytes automatically. */
        public VoiceUtterance(UUID speakerId, long startMs, int sampleCount24k, float baseMaxDist,
                              ChannelMode channelMode, @Nullable UUID groupId, byte[] adpcmBytes) {
            this(speakerId, startMs, sampleCount24k, baseMaxDist, channelMode, groupId, adpcmBytes,
                    computeNormalizedRms(decodeAdpcm(adpcmBytes, sampleCount24k)));
        }

        public byte[] adpcmBytes() { return adpcmBytes.clone(); }
        public long endMs() { return startMs + Math.round(sampleCount24k * 1000.0 / SAMPLE_RATE); }
        public double durationSeconds() { return sampleCount24k / (double) SAMPLE_RATE; }
        public short[] decode24k() { return decodeAdpcm(adpcmBytes, sampleCount24k); }
        public short[] decode48k() { return upsample24kTo48k(decodeAdpcm(adpcmBytes, sampleCount24k)); }
    }

    /** Snapshot of one active {@code RoleVoicePolicy} recorded during the match. */
    public record RecordedVoicePolicy(
            String roleId,
            boolean muteSend,
            boolean muteReceive,
            boolean isolateGroup,
            boolean hearWorld,
            float maxDistance
    ) {
        public RecordedVoicePolicy {
            roleId = roleId == null ? "" : roleId;
            if (!Float.isFinite(maxDistance) || maxDistance < 0f) maxDistance = 0f;
        }
    }

    /** One interval of a participant's voice mute/channel/group/role state during the match. */
    public record SpeakerStateSpan(
            UUID playerId,
            long startMs,
            long endMs,
            int stateFlags,
            @Nullable UUID groupId,
            String roleId
    ) {
        public SpeakerStateSpan {
            Objects.requireNonNull(playerId, "playerId");
            if (startMs < 0) startMs = 0;
            if (endMs < startMs) endMs = startMs;
            roleId = roleId == null ? "" : roleId;
        }

        public boolean hasState(int flag) { return (stateFlags & flag) != 0; }
        public boolean contains(long matchMs) { return matchMs >= startMs && matchMs <= endMs; }
    }

    /** Complete voice-routing timeline metadata embedded in {@code HRVC v2}. */
    public record VoiceTimelineMeta(
            List<RecordedVoicePolicy> policies,
            List<SpeakerStateSpan> stateSpans,
            float defaultVoiceDistance,
            float whisperDistance
    ) {
        public static final VoiceTimelineMeta EMPTY = new VoiceTimelineMeta(List.of(), List.of(), 48.0f, 16.0f);

        public VoiceTimelineMeta {
            policies = policies == null ? List.of() : List.copyOf(policies);
            stateSpans = stateSpans == null ? List.of() : List.copyOf(stateSpans);
            defaultVoiceDistance = Float.isFinite(defaultVoiceDistance) && defaultVoiceDistance > 0f ? defaultVoiceDistance : 48.0f;
            whisperDistance = Float.isFinite(whisperDistance) && whisperDistance > 0f ? whisperDistance : 16.0f;
        }

        public boolean isEmpty() {
            return policies.isEmpty() && stateSpans.isEmpty();
        }
    }

    private final int formatVersion;
    private final List<Clip> clips;
    private final List<VoiceUtterance> utterances;
    private final VoiceTimelineMeta timelineMeta;
    private final boolean truncated;

    /** Legacy v1 constructor (produces {@code HRVC v1} when serialized). */
    public ReplayVoiceData(List<Clip> clips) {
        this.formatVersion = VERSION;
        if (clips == null || clips.isEmpty()) {
            this.clips = List.of();
        } else if (clips.size() > MAX_CLIPS) {
            this.clips = List.copyOf(clips.subList(0, MAX_CLIPS));
        } else {
            this.clips = List.copyOf(clips);
        }
        this.utterances = List.of();
        this.timelineMeta = VoiceTimelineMeta.EMPTY;
        this.truncated = false;
    }

    /** Multi-track {@code HRVC v2} constructor. */
    public ReplayVoiceData(List<VoiceUtterance> utterances, VoiceTimelineMeta timelineMeta, List<Clip> fallbackClips) {
        this(utterances, timelineMeta, fallbackClips, false);
    }

    public ReplayVoiceData(List<VoiceUtterance> utterances, VoiceTimelineMeta timelineMeta, List<Clip> fallbackClips,
                           boolean truncated) {
        this.formatVersion = VERSION_V2;
        this.truncated = truncated || (utterances != null && utterances.size() > MAX_UTTERANCES);
        if (utterances == null || utterances.isEmpty()) {
            this.utterances = List.of();
        } else {
            List<VoiceUtterance> copy = new ArrayList<>(Math.min(utterances.size(), MAX_UTTERANCES));
            for (int i = 0; i < utterances.size() && copy.size() < MAX_UTTERANCES; i++) {
                copy.add(utterances.get(i));
            }
            copy.sort(Comparator.comparingLong(VoiceUtterance::startMs)
                    .thenComparing(VoiceUtterance::speakerId));
            this.utterances = List.copyOf(copy);
        }
        this.timelineMeta = timelineMeta == null ? VoiceTimelineMeta.EMPTY : timelineMeta;
        if (fallbackClips == null || fallbackClips.isEmpty()) {
            this.clips = List.of();
        } else if (fallbackClips.size() > MAX_CLIPS) {
            this.clips = List.copyOf(fallbackClips.subList(0, MAX_CLIPS));
        } else {
            this.clips = List.copyOf(fallbackClips);
        }
    }

    public static ReplayVoiceData ofV2(List<VoiceUtterance> utterances, VoiceTimelineMeta meta) {
        return new ReplayVoiceData(utterances, meta, List.of());
    }

    public int formatVersion() { return formatVersion; }
    public List<Clip> clips() { return clips; }
    public List<VoiceUtterance> utterances() { return utterances; }
    public VoiceTimelineMeta timelineMeta() { return timelineMeta; }
    public boolean truncated() { return truncated; }
    public boolean hasMultitrack() { return !utterances.isEmpty(); }
    public boolean isEmpty() { return clips.isEmpty() && utterances.isEmpty(); }

    /** Returns all utterances overlapping {@code [windowStartMs, windowEndMs]}. */
    public List<VoiceUtterance> utterancesInWindow(long windowStartMs, long windowEndMs) {
        if (utterances.isEmpty() || windowEndMs < windowStartMs) return List.of();
        long earliest = Math.max(0, windowStartMs) - MAX_UTTERANCE_SAMPLES * 1_000L / SAMPLE_RATE;
        int lower = 0;
        int upper = utterances.size();
        while (lower < upper) {
            int middle = (lower + upper) >>> 1;
            if (utterances.get(middle).startMs() < earliest) lower = middle + 1;
            else upper = middle;
        }
        List<VoiceUtterance> out = new ArrayList<>();
        for (int index = lower; index < utterances.size(); index++) {
            var u = utterances.get(index);
            if (u.startMs() > windowEndMs) break;
            if (u.endMs() >= windowStartMs) out.add(u);
        }
        return out;
    }

    public byte[] toBytes() {
        if (isEmpty() && timelineMeta.isEmpty() && !truncated) return new byte[0];
        if (formatVersion == VERSION && utterances.isEmpty() && timelineMeta.isEmpty()) {
            return toBytesV1();
        }
        return toBytesV2();
    }

    private byte[] toBytesV1() {
        if (clips.isEmpty()) return new byte[0];
        int totalSize = 12;
        for (Clip clip : clips) totalSize += 20 + clip.adpcm.length;
        if (totalSize > MAX_VOICE_BYTES) {
            throw new IllegalArgumentException("Voice track exceeds max bytes: " + totalSize);
        }
        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(MAGIC);
        buf.putInt(VERSION);
        buf.putInt(clips.size());
        for (Clip clip : clips) {
            buf.putInt(clip.eventIndex());
            buf.putInt(clip.tick());
            buf.putInt(clip.offsetMs());
            buf.putInt(clip.sampleCount());
            buf.putInt(clip.adpcm.length);
            buf.put(clip.adpcm);
        }
        return buf.array();
    }

    private byte[] toBytesV2() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(Math.max(256, utterances.size() * 64)));
        try {
            buf.writeInt(MAGIC);
            buf.writeInt(VERSION_V2);
            int flags = 0;
            if (!timelineMeta.isEmpty()) flags |= V2_FLAG_HAS_META;
            if (!clips.isEmpty()) flags |= V2_FLAG_HAS_CLIPS;
            if (truncated) flags |= V2_FLAG_TRUNCATED;
            buf.writeInt(flags);

            List<UUID> uuids = new ArrayList<>();
            Map<UUID, Integer> uuidIndex = new HashMap<>();
            List<String> roles = new ArrayList<>();
            Map<String, Integer> roleIndex = new HashMap<>();
            roles.add("");
            roleIndex.put("", 0);

            for (VoiceUtterance u : utterances) {
                internUuid(uuids, uuidIndex, u.speakerId());
                if (u.groupId() != null) internUuid(uuids, uuidIndex, u.groupId());
            }
            for (SpeakerStateSpan span : timelineMeta.stateSpans()) {
                internUuid(uuids, uuidIndex, span.playerId());
                if (span.groupId() != null) internUuid(uuids, uuidIndex, span.groupId());
                internRole(roles, roleIndex, span.roleId());
            }
            for (RecordedVoicePolicy p : timelineMeta.policies()) {
                internRole(roles, roleIndex, p.roleId());
            }

            buf.writeVarInt(uuids.size());
            for (UUID id : uuids) buf.writeUUID(id);
            buf.writeVarInt(roles.size());
            for (String role : roles) buf.writeUtf(role, 128);

            if ((flags & V2_FLAG_HAS_META) != 0) {
                buf.writeFloat(timelineMeta.defaultVoiceDistance());
                buf.writeFloat(timelineMeta.whisperDistance());
                buf.writeVarInt(timelineMeta.policies().size());
                for (RecordedVoicePolicy p : timelineMeta.policies()) {
                    buf.writeVarInt(roleIndex.get(p.roleId()));
                    int pFlags = (p.muteSend() ? 1 : 0) | (p.muteReceive() ? 2 : 0)
                            | (p.isolateGroup() ? 4 : 0) | (p.hearWorld() ? 8 : 0);
                    buf.writeByte(pFlags);
                    buf.writeFloat(p.maxDistance());
                }
                buf.writeVarInt(timelineMeta.stateSpans().size());
                for (SpeakerStateSpan span : timelineMeta.stateSpans()) {
                    buf.writeVarInt(uuidIndex.get(span.playerId()));
                    buf.writeVarLong(span.startMs());
                    buf.writeVarLong(span.endMs() - span.startMs());
                    buf.writeVarInt(span.stateFlags());
                    buf.writeVarInt(span.groupId() == null ? 0 : uuidIndex.get(span.groupId()) + 1);
                    buf.writeVarInt(roleIndex.get(span.roleId()));
                }
            }

            buf.writeVarInt(utterances.size());
            long prevStart = 0;
            for (VoiceUtterance u : utterances) {
                buf.writeVarInt(uuidIndex.get(u.speakerId()));
                buf.writeVarLong(u.startMs() - prevStart);
                prevStart = u.startMs();
                buf.writeVarInt(u.sampleCount24k());
                buf.writeFloat(u.baseMaxDist());
                buf.writeByte(u.channelMode().ordinal());
                buf.writeVarInt(u.groupId() == null ? 0 : uuidIndex.get(u.groupId()) + 1);
                buf.writeShort(Math.clamp(Math.round(u.rmsEnergy() * 32767f), 0, 32767));
                buf.writeVarInt(u.adpcmBytes.length);
                buf.writeBytes(u.adpcmBytes);
                if (buf.readableBytes() > MAX_VOICE_BYTES) {
                    throw new IllegalArgumentException("Voice track v2 exceeds max bytes: " + buf.readableBytes());
                }
            }

            if ((flags & V2_FLAG_HAS_CLIPS) != 0) {
                buf.writeVarInt(clips.size());
                for (Clip clip : clips) {
                    buf.writeInt(clip.eventIndex());
                    buf.writeInt(clip.tick());
                    buf.writeInt(clip.offsetMs());
                    buf.writeInt(clip.sampleCount());
                    buf.writeInt(clip.adpcm.length);
                    buf.writeBytes(clip.adpcm);
                }
            }

            if (buf.readableBytes() > MAX_VOICE_BYTES) {
                throw new IllegalArgumentException("Voice track v2 exceeds max bytes: " + buf.readableBytes());
            }
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static void internUuid(List<UUID> list, Map<UUID, Integer> index, UUID id) {
        if (!index.containsKey(id)) {
            index.put(id, list.size());
            list.add(id);
        }
    }

    private static void internRole(List<String> list, Map<String, Integer> index, String role) {
        String key = role == null ? "" : role;
        if (!index.containsKey(key)) {
            index.put(key, list.size());
            list.add(key);
        }
    }

    public static ReplayVoiceData fromBytes(byte[] raw) {
        if (raw == null || raw.length == 0) return EMPTY;
        if (raw.length < 12 || raw.length > MAX_VOICE_BYTES) {
            throw new IllegalArgumentException("Invalid voice archive size: " + raw.length);
        }
        ByteBuffer header = ByteBuffer.wrap(raw, 0, 12).order(ByteOrder.BIG_ENDIAN);
        if (header.getInt() != MAGIC) throw new IllegalArgumentException("Invalid voice magic");
        int version = header.getInt();
        if (version == VERSION) return fromBytesV1(raw);
        if (version == VERSION_V2) return fromBytesV2(raw);
        throw new IllegalArgumentException("Unsupported voice version: " + version);
    }

    private static ReplayVoiceData fromBytesV1(byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        buf.getInt(); // MAGIC
        buf.getInt(); // VERSION
        int count = buf.getInt();
        if (count < 0 || count > MAX_CLIPS) throw new IllegalArgumentException("Invalid voice clip count: " + count);
        List<Clip> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 20) throw new IllegalArgumentException("Truncated voice clip header");
            int eventIndex = buf.getInt();
            int tick = buf.getInt();
            int offsetMs = buf.getInt();
            int sampleCount = buf.getInt();
            int adpcmLen = buf.getInt();
            if (adpcmLen < 0 || adpcmLen > buf.remaining()) {
                throw new IllegalArgumentException("Invalid ADPCM length: " + adpcmLen);
            }
            if (sampleCount > 0 && adpcmLen < expectedAdpcmBytes(sampleCount)) {
                throw new IllegalArgumentException("Truncated ADPCM data for samples: " + sampleCount);
            }
            byte[] adpcm = new byte[adpcmLen];
            buf.get(adpcm);
            list.add(new Clip(eventIndex, tick, offsetMs, sampleCount, adpcm));
        }
        if (buf.hasRemaining()) throw new IllegalArgumentException("Trailing voice archive bytes");
        return list.isEmpty() ? EMPTY : new ReplayVoiceData(list);
    }

    private static ReplayVoiceData fromBytesV2(byte[] raw) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            buf.readInt(); // MAGIC
            buf.readInt(); // VERSION_V2
            int flags = buf.readInt();

            int uuidCount = buf.readVarInt();
            if (uuidCount < 0 || uuidCount > 4096) throw new IllegalArgumentException("Invalid voice uuid table: " + uuidCount);
            List<UUID> uuids = new ArrayList<>(uuidCount);
            for (int i = 0; i < uuidCount; i++) uuids.add(buf.readUUID());

            int roleCount = buf.readVarInt();
            if (roleCount < 0 || roleCount > 1024) throw new IllegalArgumentException("Invalid voice role table: " + roleCount);
            List<String> roles = new ArrayList<>(roleCount);
            for (int i = 0; i < roleCount; i++) roles.add(buf.readUtf(128));

            VoiceTimelineMeta meta = VoiceTimelineMeta.EMPTY;
            if ((flags & V2_FLAG_HAS_META) != 0) {
                float defDist = buf.readFloat();
                float whispDist = buf.readFloat();
                int pCount = buf.readVarInt();
                if (pCount < 0 || pCount > MAX_POLICIES) throw new IllegalArgumentException("Invalid voice policy count: " + pCount);
                List<RecordedVoicePolicy> policies = new ArrayList<>(pCount);
                for (int i = 0; i < pCount; i++) {
                    int rIdx = buf.readVarInt();
                    if (rIdx < 0 || rIdx >= roles.size()) throw new IllegalArgumentException("Bad policy role index");
                    int pf = buf.readUnsignedByte();
                    float maxDist = buf.readFloat();
                    policies.add(new RecordedVoicePolicy(roles.get(rIdx),
                            (pf & 1) != 0, (pf & 2) != 0, (pf & 4) != 0, (pf & 8) != 0, maxDist));
                }
                int sCount = buf.readVarInt();
                if (sCount < 0 || sCount > MAX_STATE_SPANS) throw new IllegalArgumentException("Invalid state span count: " + sCount);
                List<SpeakerStateSpan> spans = new ArrayList<>(sCount);
                for (int i = 0; i < sCount; i++) {
                    int pIdx = buf.readVarInt();
                    if (pIdx < 0 || pIdx >= uuids.size()) throw new IllegalArgumentException("Bad span player index");
                    long startMs = buf.readVarLong();
                    long durMs = buf.readVarLong();
                    if (startMs < 0 || durMs < 0) throw new IllegalArgumentException("Negative span timing");
                    int stateFlags = buf.readVarInt();
                    int gIdx = buf.readVarInt();
                    if (gIdx < 0 || gIdx > uuids.size()) throw new IllegalArgumentException("Bad span group index");
                    int rIdx = buf.readVarInt();
                    if (rIdx < 0 || rIdx >= roles.size()) throw new IllegalArgumentException("Bad span role index");
                    spans.add(new SpeakerStateSpan(uuids.get(pIdx), startMs, startMs + durMs, stateFlags,
                            gIdx == 0 ? null : uuids.get(gIdx - 1), roles.get(rIdx)));
                }
                meta = new VoiceTimelineMeta(policies, spans, defDist, whispDist);
            }

            int uCount = buf.readVarInt();
            if (uCount < 0 || uCount > MAX_UTTERANCES) throw new IllegalArgumentException("Invalid utterance count: " + uCount);
            ChannelMode[] modes = ChannelMode.values();
            List<VoiceUtterance> utterances = new ArrayList<>(uCount);
            long curStart = 0;
            for (int i = 0; i < uCount; i++) {
                int sIdx = buf.readVarInt();
                if (sIdx < 0 || sIdx >= uuids.size()) throw new IllegalArgumentException("Bad speaker index");
                long deltaMs = buf.readVarLong();
                if (deltaMs < 0) throw new IllegalArgumentException("Negative utterance delta");
                curStart += deltaMs;
                int sampleCount = buf.readVarInt();
                float maxDist = buf.readFloat();
                int modeOrd = buf.readUnsignedByte();
                if (modeOrd >= modes.length) throw new IllegalArgumentException("Invalid voice channel: " + modeOrd);
                ChannelMode mode = modes[modeOrd];
                int gIdx = buf.readVarInt();
                if (gIdx < 0 || gIdx > uuids.size()) throw new IllegalArgumentException("Bad group index");
                float rms = Math.clamp(buf.readUnsignedShort() / 32767.0f, 0f, 1f);
                int adpcmLen = buf.readVarInt();
                if (adpcmLen < 0 || adpcmLen > buf.readableBytes()) {
                    throw new IllegalArgumentException("Invalid v2 ADPCM length: " + adpcmLen);
                }
                if (sampleCount > 0 && adpcmLen < expectedAdpcmBytes(sampleCount)) {
                    throw new IllegalArgumentException("Truncated v2 ADPCM data for samples: " + sampleCount);
                }
                byte[] adpcm = new byte[adpcmLen];
                buf.readBytes(adpcm);
                utterances.add(new VoiceUtterance(uuids.get(sIdx), curStart, sampleCount, maxDist,
                        mode, gIdx == 0 ? null : uuids.get(gIdx - 1), adpcm, rms));
            }

            List<Clip> legacyClips = List.of();
            if ((flags & V2_FLAG_HAS_CLIPS) != 0) {
                int cCount = buf.readVarInt();
                if (cCount < 0 || cCount > MAX_CLIPS) throw new IllegalArgumentException("Invalid v2 clip count");
                List<Clip> list = new ArrayList<>(cCount);
                for (int i = 0; i < cCount; i++) {
                    int eventIndex = buf.readInt();
                    int tick = buf.readInt();
                    int offsetMs = buf.readInt();
                    int sampleCount = buf.readInt();
                    int adpcmLen = buf.readInt();
                    if (adpcmLen < 0 || adpcmLen > buf.readableBytes()) throw new IllegalArgumentException("Invalid v2 clip ADPCM");
                    byte[] adpcm = new byte[adpcmLen];
                    buf.readBytes(adpcm);
                    list.add(new Clip(eventIndex, tick, offsetMs, sampleCount, adpcm));
                }
                legacyClips = list;
            }

            if (buf.isReadable()) throw new IllegalArgumentException("Trailing v2 voice archive bytes");
            return new ReplayVoiceData(utterances, meta, legacyClips, (flags & V2_FLAG_TRUNCATED) != 0);
        } finally {
            buf.release();
        }
    }

    public static float computeNormalizedRms(short[] pcm) {
        if (pcm == null || pcm.length == 0) return 0f;
        double sumSq = 0;
        for (short s : pcm) sumSq += (double) s * s;
        return Math.clamp((float) (Math.sqrt(sumSq / pcm.length) / 32768.0), 0f, 1f);
    }

    public static int expectedAdpcmBytes(int sampleCount) {
        if (sampleCount <= 0) return 0;
        if (sampleCount == 1) return 4;
        return 4 + (sampleCount / 2);
    }

    /** Encodes 24 kHz mono 16-bit PCM into 4-byte header + 4-bit IMA ADPCM nibbles. */
    public static byte[] encodeAdpcm(short[] pcm) {
        if (pcm == null || pcm.length == 0) return new byte[0];
        int n = pcm.length;
        int nibbleBytes = n <= 1 ? 0 : (n / 2);
        byte[] out = new byte[4 + nibbleBytes];
        int predictor = pcm[0];
        int index = 0;
        out[0] = (byte) (predictor & 0xFF);
        out[1] = (byte) ((predictor >>> 8) & 0xFF);
        out[2] = (byte) index;
        out[3] = 0;

        int outPos = 4;
        int packedByte = 0;
        boolean highNibble = false;
        for (int i = 1; i < n; i++) {
            int step = STEP_TABLE[index];
            int diff = pcm[i] - predictor;
            int nibble = 0;
            if (diff < 0) {
                nibble = 8;
                diff = -diff;
            }
            int vpdiff = step >> 3;
            if (diff >= step) {
                nibble |= 4;
                diff -= step;
                vpdiff += step;
            }
            step >>= 1;
            if (diff >= step) {
                nibble |= 2;
                diff -= step;
                vpdiff += step;
            }
            step >>= 1;
            if (diff >= step) {
                nibble |= 1;
                vpdiff += step;
            }
            if ((nibble & 8) != 0) predictor -= vpdiff;
            else predictor += vpdiff;
            predictor = Math.clamp(predictor, Short.MIN_VALUE, Short.MAX_VALUE);
            index = Math.clamp(index + INDEX_TABLE[nibble], 0, STEP_TABLE.length - 1);

            if (!highNibble) {
                packedByte = nibble & 0x0F;
                highNibble = true;
            } else {
                out[outPos++] = (byte) (packedByte | ((nibble & 0x0F) << 4));
                highNibble = false;
            }
        }
        if (highNibble && outPos < out.length) {
            out[outPos] = (byte) packedByte;
        }
        return out;
    }

    /** Decodes 4-byte header + IMA ADPCM nibbles into {@code sampleCount} 24 kHz mono samples. */
    public static short[] decodeAdpcm(byte[] adpcm, int sampleCount) {
        if (sampleCount <= 0 || adpcm == null || adpcm.length < 4) return new short[0];
        short[] out = new short[sampleCount];
        int predictor = (short) ((adpcm[0] & 0xFF) | ((adpcm[1] & 0xFF) << 8));
        int index = Math.clamp(adpcm[2] & 0xFF, 0, STEP_TABLE.length - 1);
        out[0] = (short) predictor;

        int byteIndex = 4;
        boolean highNibble = false;
        for (int i = 1; i < sampleCount; i++) {
            if (byteIndex >= adpcm.length) break;
            int nibble = highNibble
                    ? ((adpcm[byteIndex++] >>> 4) & 0x0F)
                    : (adpcm[byteIndex] & 0x0F);
            highNibble = !highNibble;

            int step = STEP_TABLE[index];
            int vpdiff = step >> 3;
            if ((nibble & 4) != 0) vpdiff += step;
            if ((nibble & 2) != 0) vpdiff += step >> 1;
            if ((nibble & 1) != 0) vpdiff += step >> 2;
            if ((nibble & 8) != 0) predictor -= vpdiff;
            else predictor += vpdiff;
            predictor = Math.clamp(predictor, Short.MIN_VALUE, Short.MAX_VALUE);
            index = Math.clamp(index + INDEX_TABLE[nibble], 0, STEP_TABLE.length - 1);
            out[i] = (short) predictor;
        }
        return out;
    }

    /** 3-tap [0.25, 0.5, 0.25] anti-aliased decimation from 48 kHz to 24 kHz. */
    public static short[] downsample48kTo24k(short[] pcm48k) {
        if (pcm48k == null || pcm48k.length == 0) return new short[0];
        int outLen = pcm48k.length / 2;
        if (outLen == 0) return Arrays.copyOf(pcm48k, 1);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            int center = i * 2;
            int prev = center > 0 ? pcm48k[center - 1] : pcm48k[center];
            int curr = pcm48k[center];
            int next = center + 1 < pcm48k.length ? pcm48k[center + 1] : curr;
            out[i] = (short) Math.clamp((prev + 2 * curr + next + 2) >> 2, Short.MIN_VALUE, Short.MAX_VALUE);
        }
        return out;
    }

    /** Linear 2x upsampling from 24 kHz to 48 kHz. */
    public static short[] upsample24kTo48k(short[] pcm24k) {
        if (pcm24k == null || pcm24k.length == 0) return new short[0];
        short[] out = new short[pcm24k.length * 2];
        for (int i = 0; i < pcm24k.length; i++) {
            short cur = pcm24k[i];
            short next = i + 1 < pcm24k.length ? pcm24k[i + 1] : cur;
            out[i * 2] = cur;
            out[i * 2 + 1] = (short) ((cur + next) >> 1);
        }
        return out;
    }
}
