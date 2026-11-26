package com.habitrain.core.network.replay;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.*;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

public final class ReplayPackets {
    public static final UUID EMPTY = new UUID(0, 0);
    /** Kept safely under Minecraft 1.21.1's 1 MiB S2C custom payload limit. */
    public static final int MAX_SUMMARY = 960 * 1024;
    public static final int FRAGMENT_BYTES = 24 * 1024;
    public static final int MAX_SECTION_BYTES = 256 * 1024;
    public static final int MAX_REQUESTED = 64;
    public static final int SECTION_BATCH_BYTES = FRAGMENT_BYTES + 64;
    public static final int SECTION_BATCH_FRAGMENTS = 16;
    private static final int BITSET_BYTES = ReplayMapManifest.MAX_SECTIONS / 8;
    private static final int SUMMARY_V2_MAGIC = 0x48525632; // "HRV2"
    private static final int SUMMARY_V2_VERSION = 2;
    private static final int MAX_V2_TRAILER_UNCOMPRESSED = 8 * 1024 * 1024;

    private ReplayPackets() {}

    /** Empty UUID means there is no completed round. A new revision publishes a newly available manifest. */
    public record Available(UUID roundId, boolean mapReady) implements CustomPacketPayload {
        public static final Type<Available> TYPE = typeId("last_match_available");
        static final StreamCodec<FriendlyByteBuf, Available> CODEC = codec(17,
                b -> new Available(b.readUUID(), b.readBoolean()),
                (b, p) -> { b.writeUUID(p.roundId); b.writeBoolean(p.mapReady); });
        @Override public Type<Available> type() { return TYPE; }
    }

    public record RequestSummary(BlockPos board, UUID roundId) implements CustomPacketPayload {
        public static final Type<RequestSummary> TYPE = typeId("last_match_request");
        static final StreamCodec<FriendlyByteBuf, RequestSummary> CODEC = codec(24,
                b -> new RequestSummary(b.readBlockPos(), b.readUUID()),
                (b, p) -> { b.writeBlockPos(p.board); b.writeUUID(p.roundId); });
        @Override public Type<RequestSummary> type() { return TYPE; }
    }

    public record StartPlayback(BlockPos board, UUID roundId) implements CustomPacketPayload {
        public static final Type<StartPlayback> TYPE = typeId("replay_board_start");
        static final StreamCodec<FriendlyByteBuf, StartPlayback> CODEC = codec(24,
                b -> new StartPlayback(b.readBlockPos(), b.readUUID()),
                (b, p) -> { b.writeBlockPos(p.board); b.writeUUID(p.roundId); });
        @Override public Type<StartPlayback> type() { return TYPE; }
    }

    /** Ephemeral playback control action sent from table side-edge controls or nearby backpack UI. */
    public record BoardSessionControlC2S(
            BlockPos board,
            ReplayBoardSessionState.Action action,
            double value,
            float speed,
            @Nullable UUID followPlayerUuid,
            boolean flag
    ) implements CustomPacketPayload {
        public static final Type<BoardSessionControlC2S> TYPE = typeId("replay_board_session_control");
        public static final StreamCodec<FriendlyByteBuf, BoardSessionControlC2S> CODEC = codec(64,
                b -> new BoardSessionControlC2S(
                        b.readBlockPos(),
                        b.readEnum(ReplayBoardSessionState.Action.class),
                        b.readDouble(),
                        b.readFloat(),
                        readId(b),
                        b.readBoolean()),
                (b, p) -> {
                    b.writeBlockPos(p.board);
                    b.writeEnum(p.action);
                    b.writeDouble(p.value);
                    b.writeFloat(p.speed);
                    writeId(b, p.followPlayerUuid);
                    b.writeBoolean(p.flag);
                });

        public static BoardSessionControlC2S play(BlockPos board) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.PLAY, 0, 1f, null, false);
        }
        public static BoardSessionControlC2S pause(BlockPos board) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.PAUSE, 0, 1f, null, false);
        }
        public static BoardSessionControlC2S togglePause(BlockPos board) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.TOGGLE_PAUSE, 0, 1f, null, false);
        }
        public static BoardSessionControlC2S seek(BlockPos board, double tick) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.SEEK, tick, 1f, null, false);
        }
        public static BoardSessionControlC2S step(BlockPos board, double deltaTicks) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.STEP, deltaTicks, 1f, null, false);
        }
        public static BoardSessionControlC2S setSpeed(BlockPos board, float speed) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.SET_SPEED, 0, speed, null, false);
        }
        public static BoardSessionControlC2S setFollow(BlockPos board, @Nullable UUID playerUuid) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.SET_FOLLOW, 0, 1f, playerUuid, false);
        }
        public static BoardSessionControlC2S setKillCloseup(BlockPos board, boolean enabled) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.SET_KILL_CLOSEUP, 0, 1f, null, enabled);
        }
        public static BoardSessionControlC2S setRoofCutaway(BlockPos board, boolean enabled) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.SET_ROOF_CUTAWAY, 0, 1f, null, enabled);
        }
        public static BoardSessionControlC2S endToIdle(BlockPos board) {
            return new BoardSessionControlC2S(board, ReplayBoardSessionState.Action.END_TO_IDLE, 0, 1f, null, false);
        }

        @Override public Type<BoardSessionControlC2S> type() { return TYPE; }
    }

    /** Server broadcast of one replay table's ephemeral session state ({@code state == null} clears to idle). */
    public record BoardSessionSyncS2C(BlockPos board, @Nullable ReplayBoardSessionState state)
            implements CustomPacketPayload {
        public static final Type<BoardSessionSyncS2C> TYPE = typeId("replay_board_session_sync");
        public static final StreamCodec<FriendlyByteBuf, BoardSessionSyncS2C> CODEC = codec(96,
                b -> new BoardSessionSyncS2C(b.readBlockPos(), b.readBoolean() ? ReplayBoardSessionState.read(b) : null),
                (b, p) -> {
                    b.writeBlockPos(p.board);
                    b.writeBoolean(p.state != null);
                    if (p.state != null) p.state.write(b);
                });
        @Override public Type<BoardSessionSyncS2C> type() { return TYPE; }
    }

    /** Seamless handoff from backpack history preview directly onto a nearby lobby replay table with state. */
    public record ShareSessionC2S(
            BlockPos board,
            UUID roundId,
            double currentTick,
            float speed,
            boolean paused,
            @Nullable UUID followPlayerUuid,
            boolean killCloseupEnabled,
            boolean roofCutaway
    ) implements CustomPacketPayload {
        public static final Type<ShareSessionC2S> TYPE = typeId("replay_share_session");
        public static final StreamCodec<FriendlyByteBuf, ShareSessionC2S> CODEC = codec(80,
                b -> new ShareSessionC2S(
                        b.readBlockPos(),
                        b.readUUID(),
                        b.readDouble(),
                        b.readFloat(),
                        b.readBoolean(),
                        readId(b),
                        b.readBoolean(),
                        b.readBoolean()),
                (b, p) -> {
                    b.writeBlockPos(p.board);
                    b.writeUUID(p.roundId);
                    b.writeDouble(p.currentTick);
                    b.writeFloat(p.speed);
                    b.writeBoolean(p.paused);
                    writeId(b, p.followPlayerUuid);
                    b.writeBoolean(p.killCloseupEnabled);
                    b.writeBoolean(p.roofCutaway);
                });
        @Override public Type<ShareSessionC2S> type() { return TYPE; }
    }

    public static final class LastMatchSummaryS2C implements CustomPacketPayload {
        public static final Type<LastMatchSummaryS2C> TYPE = typeId("last_match_summary");
        public static final StreamCodec<FriendlyByteBuf, LastMatchSummaryS2C> CODEC =
                codec(MAX_SUMMARY, ReplayPackets::readSummary, ReplayPackets::writeSummary);
        private final LastMatchReplay replay;
        private final byte[] tracks;
        private volatile byte[] encodedTrailer;

        public LastMatchSummaryS2C(LastMatchReplay replay, byte[] tracks) {
            this.replay = replay;
            this.tracks = tracks;
        }

        public LastMatchReplay replay() { return replay; }
        public byte[] tracks() { return tracks; }
        @Override public Type<LastMatchSummaryS2C> type() { return TYPE; }

        // The same immutable replay is sent to every viewer. Only the first encode needs gzip.
        private byte[] trailer() {
            byte[] cached = encodedTrailer;
            if (cached != null) return cached;
            synchronized (this) {
                if (encodedTrailer == null) encodedTrailer = encodeV2Trailer(replay);
                return encodedTrailer;
            }
        }

        public LastMatchSummaryS2C withoutKillSounds() {
            return replay.killSounds().isEmpty() ? this : new LastMatchSummaryS2C(replay.withKillSounds(List.of()), tracks);
        }
    }

    /** @deprecated Retired client-polled kill-sound capture; replaced by server-side {@code GlobalMatchSoundRecorder}. */
    @Deprecated
    public record CaptureKillSoundS2C(UUID ticket, Vec3 listener, long serverGameTime) implements CustomPacketPayload {
        public static final Type<CaptureKillSoundS2C> TYPE = typeId("capture_kill_sound");
        public static final StreamCodec<FriendlyByteBuf, CaptureKillSoundS2C> CODEC = codec(48,
                buffer -> new CaptureKillSoundS2C(buffer.readUUID(),
                        new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readLong()),
                (buffer, value) -> {
                    buffer.writeUUID(value.ticket());
                    buffer.writeDouble(value.listener().x).writeDouble(value.listener().y).writeDouble(value.listener().z)
                            .writeLong(value.serverGameTime());
                });
        @Override public Type<CaptureKillSoundS2C> type() { return TYPE; }
    }

    /** @deprecated Retired client-polled kill-sound report; replaced by server-side {@code GlobalMatchSoundRecorder}. */
    @Deprecated
    public record KillSoundC2S(UUID ticket, List<ReplayKillSounds.Cue> cues) implements CustomPacketPayload {
        public static final Type<KillSoundC2S> TYPE = typeId("kill_sound");
        public static final StreamCodec<FriendlyByteBuf, KillSoundC2S> CODEC = codec(8192,
                buffer -> new KillSoundC2S(buffer.readUUID(), readKillCues(buffer)),
                (buffer, value) -> {
                    buffer.writeUUID(value.ticket());
                    writeKillCues(buffer, value.cues());
                });
        public KillSoundC2S { cues = List.copyOf(cues); }
        @Override public Type<KillSoundC2S> type() { return TYPE; }
    }

    /** One bounded batch; indices reference the exact round's manifest ordering. */
    public record ReplayMissingSectionsC2S(BlockPos board, UUID roundId, BitSet missing) implements CustomPacketPayload {
        public static final Type<ReplayMissingSectionsC2S> TYPE = typeId("replay_missing_sections");
        public static final StreamCodec<FriendlyByteBuf, ReplayMissingSectionsC2S> CODEC = codec(24 + BITSET_BYTES + 2,
                b -> {
                    BlockPos pos = b.readBlockPos(); UUID id = b.readUUID();
                    BitSet bits = BitSet.valueOf(b.readByteArray(BITSET_BYTES));
                    if (bits.isEmpty() || bits.cardinality() > MAX_REQUESTED) throw new DecoderException("Bad replay section count");
                    return new ReplayMissingSectionsC2S(pos, id, bits);
                },
                (b, p) -> {
                    if (p.missing.length() > ReplayMapManifest.MAX_SECTIONS || p.missing.isEmpty()
                            || p.missing.cardinality() > MAX_REQUESTED) throw new IllegalArgumentException("Bad missing sections");
                    b.writeBlockPos(p.board); b.writeUUID(p.roundId); b.writeByteArray(p.missing.toByteArray());
                });
        @Override public Type<ReplayMissingSectionsC2S> type() { return TYPE; }
    }

    public record ReplaySectionPatchS2C(UUID roundId, int index, int offset, int total, byte[] bytes)
            implements CustomPacketPayload {
        public static final Type<ReplaySectionPatchS2C> TYPE = typeId("replay_section_patch");
        public static final StreamCodec<FriendlyByteBuf, ReplaySectionPatchS2C> CODEC = codec(FRAGMENT_BYTES + 33,
                b -> new ReplaySectionPatchS2C(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                        b.readByteArray(FRAGMENT_BYTES)),
                (b, p) -> {
                    b.writeUUID(p.roundId); b.writeVarInt(p.index); b.writeVarInt(p.offset);
                    b.writeVarInt(p.total); b.writeByteArray(p.bytes);
                });
        public ReplaySectionPatchS2C {
            if (index < 0 || index >= ReplayMapManifest.MAX_SECTIONS || offset < 0 || offset % FRAGMENT_BYTES != 0
                    || total <= offset || total > MAX_SECTION_BYTES
                    || bytes.length != Math.min(FRAGMENT_BYTES, total - offset)) {
                throw new IllegalArgumentException("Bad replay section fragment");
            }
        }
        @Override public Type<ReplaySectionPatchS2C> type() { return TYPE; }
    }

    public record RequestVoiceC2S(BlockPos board, UUID roundId) implements CustomPacketPayload {
        public static final Type<RequestVoiceC2S> TYPE = typeId("replay_voice_request");
        static final StreamCodec<FriendlyByteBuf, RequestVoiceC2S> CODEC = codec(24,
                b -> new RequestVoiceC2S(b.readBlockPos(), b.readUUID()),
                (b, p) -> { b.writeBlockPos(p.board); b.writeUUID(p.roundId); });
        @Override public Type<RequestVoiceC2S> type() { return TYPE; }
    }

    public record ExportPresenceC2S(UUID session, boolean open) implements CustomPacketPayload {
        public static final Type<ExportPresenceC2S> TYPE = typeId("replay_export_presence");
        static final StreamCodec<FriendlyByteBuf, ExportPresenceC2S> CODEC = codec(17,
                b -> new ExportPresenceC2S(b.readUUID(), b.readBoolean()),
                (b, p) -> { b.writeUUID(p.session); b.writeBoolean(p.open); });
        @Override public Type<ExportPresenceC2S> type() { return TYPE; }
    }

    public record ExportMatchS2C(UUID session, boolean active) implements CustomPacketPayload {
        public static final Type<ExportMatchS2C> TYPE = typeId("replay_export_match");
        static final StreamCodec<FriendlyByteBuf, ExportMatchS2C> CODEC = codec(17,
                b -> new ExportMatchS2C(b.readUUID(), b.readBoolean()),
                (b, p) -> { b.writeUUID(p.session); b.writeBoolean(p.active); });
        @Override public Type<ExportMatchS2C> type() { return TYPE; }
    }

    public record RequestVoiceExportC2S(UUID roundId) implements CustomPacketPayload {
        public static final Type<RequestVoiceExportC2S> TYPE = typeId("replay_voice_export_request");
        static final StreamCodec<FriendlyByteBuf, RequestVoiceExportC2S> CODEC = codec(16,
                b -> new RequestVoiceExportC2S(b.readUUID()), (b, p) -> b.writeUUID(p.roundId));
        @Override public Type<RequestVoiceExportC2S> type() { return TYPE; }
    }

    public record VoiceExportAccessS2C(UUID roundId, boolean allowed) implements CustomPacketPayload {
        public static final Type<VoiceExportAccessS2C> TYPE = typeId("replay_voice_export_access");
        static final StreamCodec<FriendlyByteBuf, VoiceExportAccessS2C> CODEC = codec(17,
                b -> new VoiceExportAccessS2C(b.readUUID(), b.readBoolean()),
                (b, p) -> { b.writeUUID(p.roundId); b.writeBoolean(p.allowed); });
        @Override public Type<VoiceExportAccessS2C> type() { return TYPE; }
    }

    public record ReplayVoiceChunkS2C(UUID roundId, int offset, int total, byte[] bytes)
            implements CustomPacketPayload {
        public static final Type<ReplayVoiceChunkS2C> TYPE = typeId("replay_voice_chunk");
        public static final StreamCodec<FriendlyByteBuf, ReplayVoiceChunkS2C> CODEC = codec(FRAGMENT_BYTES + 32,
                b -> new ReplayVoiceChunkS2C(b.readUUID(), b.readVarInt(), b.readVarInt(),
                        b.readByteArray(FRAGMENT_BYTES)),
                (b, p) -> {
                    b.writeUUID(p.roundId); b.writeVarInt(p.offset);
                    b.writeVarInt(p.total); b.writeByteArray(p.bytes);
                });
        public ReplayVoiceChunkS2C {
            if (total == 0) {
                if (offset != 0 || bytes.length != 0) throw new IllegalArgumentException("Bad empty replay voice marker");
            } else if (total < 0 || total > ReplayVoiceData.MAX_VOICE_BYTES || offset < 0 || offset % FRAGMENT_BYTES != 0
                    || offset >= total || bytes.length != Math.min(FRAGMENT_BYTES, total - offset)) {
                throw new IllegalArgumentException("Bad replay voice fragment");
            }
        }
        @Override public Type<ReplayVoiceChunkS2C> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ExportPresenceC2S.TYPE, ExportPresenceC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(ExportMatchS2C.TYPE, ExportMatchS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(Available.TYPE, Available.CODEC);
        PayloadTypeRegistry.playS2C().register(LastMatchSummaryS2C.TYPE, LastMatchSummaryS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CaptureKillSoundS2C.TYPE, CaptureKillSoundS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(KillSoundC2S.TYPE, KillSoundC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(ReplaySectionPatchS2C.TYPE, ReplaySectionPatchS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ReplayVoiceChunkS2C.TYPE, ReplayVoiceChunkS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(VoiceExportAccessS2C.TYPE, VoiceExportAccessS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(BoardSessionSyncS2C.TYPE, BoardSessionSyncS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestSummary.TYPE, RequestSummary.CODEC);
        PayloadTypeRegistry.playC2S().register(StartPlayback.TYPE, StartPlayback.CODEC);
        PayloadTypeRegistry.playC2S().register(BoardSessionControlC2S.TYPE, BoardSessionControlC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ShareSessionC2S.TYPE, ShareSessionC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ReplayMissingSectionsC2S.TYPE, ReplayMissingSectionsC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestVoiceC2S.TYPE, RequestVoiceC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestVoiceExportC2S.TYPE, RequestVoiceExportC2S.CODEC);
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeId(String id) {
        return new CustomPacketPayload.Type<>(HabiTrainCore.id(id));
    }

    private static <T> StreamCodec<FriendlyByteBuf, T> codec(int max, Function<FriendlyByteBuf, T> read,
                                                            BiConsumer<FriendlyByteBuf, T> write) {
        return new StreamCodec<>() {
            @Override public T decode(FriendlyByteBuf b) {
                if (b.readableBytes() > max) throw new DecoderException("Replay packet exceeds " + max);
                T result;
                try {
                    result = read.apply(b);
                } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
                    throw new DecoderException("Invalid replay packet", malformed);
                }
                if (b.isReadable()) throw new DecoderException("Trailing replay packet bytes");
                return result;
            }

            @Override public void encode(FriendlyByteBuf b, T value) {
                int start = b.writerIndex();
                write.accept(b, value);
                if (b.writerIndex() - start > max) throw new IllegalArgumentException("Replay packet exceeds " + max);
            }
        };
    }

    private static boolean hasV2Extension(LastMatchReplay s) {
        if (!s.blockDeltas().isEmpty() || !s.entityTracks().isEmpty()
                || !s.skillEvents().isEmpty() || !s.gameSounds().isEmpty()) {
            return true;
        }
        for (Node n : s.events()) {
            if (n.eventId() >= 0) return true;
        }
        return false;
    }

    private static void writeSummary(FriendlyByteBuf b, LastMatchSummaryS2C p) {
        LastMatchReplay s = p.replay;
        b.writeUUID(s.roundId()); b.writeLong(s.playbackAnchorTick()); b.writeVarInt(s.durationTicks());
        b.writeUtf(s.mapName(), 128); b.writeUtf(s.modeId(), 128); b.writeUtf(s.winStatus(), 64);
        b.writeEnum(s.winKind()); b.writeUtf(s.customWinId(), 128);
        b.writeUtf(s.customTitleJson(), 2048); b.writeUtf(s.customSubtitleJson(), 2048); b.writeInt(s.customColor());
        writeBounds(b, s.origin());
        b.writeBoolean(s.manifest() != null);
        if (s.manifest() != null) writeManifest(b, s.manifest());
        b.writeVarInt(s.participants().size());
        for (Participant player : s.participants()) {
            b.writeUUID(player.id()); b.writeUtf(player.name(), 64);
            b.writeUtf(player.startRole(), 128); b.writeInt(player.startColor());
            b.writeUtf(player.endRole(), 128); b.writeInt(player.endColor());
            b.writeEnum(player.faction()); b.writeBoolean(player.winner());
            b.writeVarInt(player.survivalSeconds()); b.writeVarInt(player.kills());
            b.writeVarInt(player.score()); b.writeVarInt(player.itemUses());
        }
        b.writeByteArray(p.tracks);
        b.writeVarInt(s.events().size());
        for (Node node : s.events()) {
            b.writeVarInt(node.tick()); b.writeEnum(node.kind()); writeId(b, node.actor()); writeId(b, node.target());
            writePosition(b, node.from()); writePosition(b, node.to()); b.writeUtf(node.detail(), 128); b.writeByte(node.flags());
        }
        b.writeVarInt(s.spans().size());
        for (Span span : s.spans()) {
            b.writeEnum(span.kind()); writeId(b, span.actor());
            b.writeVarInt(span.startTick()); b.writeVarInt(span.endTick());
        }
        writeId(b, s.mvp()); b.writeVarInt(s.killCount()); b.writeBoolean(s.limited());

        boolean v2 = hasV2Extension(s);
        if (v2 || !s.drones().isEmpty() || !s.killSounds().isEmpty()) {
            if (s.drones().size() > MAX_DRONES) throw new IllegalArgumentException("Too many replay drones");
            b.writeVarInt(s.drones().size());
            for (Drone drone : s.drones()) {
                b.writeUUID(drone.id()); b.writeUUID(drone.owner());
            }
        }
        if (v2 || !s.killSounds().isEmpty()) {
            checkKillWindows(s.events(), s.killSounds());
            b.writeVarInt(s.killSounds().size());
            for (var window : s.killSounds()) {
                b.writeVarInt(window.eventIndex());
                writeKillCues(b, window.cues());
            }
        }
        if (v2) {
            b.writeInt(SUMMARY_V2_MAGIC);
            b.writeByteArray(p.trailer());
        }
    }

    private static LastMatchSummaryS2C readSummary(FriendlyByteBuf b) {
        UUID id = b.readUUID();
        long anchor = b.readLong();
        int duration = count(b, MAX_TICKS);
        String map = b.readUtf(128), mode = b.readUtf(128), status = b.readUtf(64);
        MatchWinKind win = b.readEnum(MatchWinKind.class);
        String custom = b.readUtf(128), title = b.readUtf(2048), subtitle = b.readUtf(2048);
        int color = b.readInt();
        SceneBounds origin = readBounds(b);
        ReplayMapManifest manifest = b.readBoolean() ? readManifest(b) : null;
        int players = count(b, MAX_PLAYERS);
        List<Participant> people = new ArrayList<>(players);
        for (int i = 0; i < players; i++) {
            people.add(new Participant(b.readUUID(), b.readUtf(64), b.readUtf(128), b.readInt(),
                    b.readUtf(128), b.readInt(), b.readEnum(MatchWinFaction.class), b.readBoolean(),
                    b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), List.of()));
        }
        // Decode tracks on the receiving game thread only when the round is still current.
        byte[] tracks = b.readByteArray(ReplayTracks.MAX_V2_BYTES);
        int eventCount = count(b, MAX_EVENTS);
        List<Node> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(new Node(count(b, duration), b.readEnum(Kind.class), readId(b), readId(b),
                    readPosition(b), readPosition(b), b.readUtf(128), b.readUnsignedByte()));
        }
        int spanCount = count(b, MAX_SPANS);
        List<Span> spans = new ArrayList<>(spanCount);
        for (int i = 0; i < spanCount; i++) {
            SpanKind kind = b.readEnum(SpanKind.class);
            UUID actor = readId(b);
            int start = count(b, duration), end = count(b, duration);
            if (end < start) throw new DecoderException("Inverted replay span");
            spans.add(new Span(kind, actor, start, end));
        }
        UUID mvp = readId(b);
        int killCount = b.readVarInt();
        boolean limited = b.readBoolean();
        int drones = b.isReadable() ? count(b, MAX_DRONES) : 0;
        List<Drone> aircraft = new ArrayList<>(drones);
        for (int i = 0; i < drones; i++) aircraft.add(new Drone(b.readUUID(), b.readUUID(), List.of()));
        int windows = b.isReadable() ? count(b, ReplayKillSounds.MAX_WINDOWS) : 0;
        List<ReplayKillSounds.Window> killSounds = new ArrayList<>(windows);
        for (int index = 0; index < windows; index++) {
            killSounds.add(new ReplayKillSounds.Window(count(b, MAX_EVENTS - 1), readKillCues(b)));
        }
        checkKillWindows(events, killSounds);

        List<ReplayBlockDelta> blockDeltas = List.of();
        List<ReplayEntityTrack> entityTracks = List.of();
        List<ReplaySkillEvent> skillEvents = List.of();
        ReplayGameSoundData gameSounds = ReplayGameSoundData.EMPTY;

        if (b.isReadable()) {
            int magic = b.readInt();
            if (magic != SUMMARY_V2_MAGIC) throw new DecoderException("Invalid replay v2 trailer magic");
            byte[] compressed = b.readByteArray(MAX_SUMMARY);
            V2Trailer v2 = decodeV2Trailer(compressed, events, duration);
            events = v2.events;
            blockDeltas = v2.blockDeltas;
            entityTracks = v2.entityTracks;
            skillEvents = v2.skillEvents;
            gameSounds = v2.gameSounds;
        }

        return new LastMatchSummaryS2C(new LastMatchReplay(id, anchor, duration, map, mode, status, win,
                custom, title, subtitle, color, origin, manifest, people, events, spans,
                mvp, killCount, limited, aircraft, killSounds, blockDeltas, entityTracks, skillEvents, gameSounds), tracks);
    }

    private record V2Trailer(List<Node> events, List<ReplayBlockDelta> blockDeltas,
                             List<ReplayEntityTrack> entityTracks, List<ReplaySkillEvent> skillEvents,
                             ReplayGameSoundData gameSounds) {}

    private static byte[] encodeV2Trailer(LastMatchReplay s) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(SUMMARY_V2_VERSION);

            boolean anyEventId = false;
            for (Node n : s.events()) if (n.eventId() >= 0) { anyEventId = true; break; }
            buf.writeBoolean(anyEventId);
            if (anyEventId) {
                for (Node n : s.events()) buf.writeVarInt(n.eventId() + 1);
            }

            List<ReplayBlockDelta> deltas = s.blockDeltas();
            List<String> stateDict = new ArrayList<>();
            Map<String, Integer> stateIdx = new HashMap<>();
            for (ReplayBlockDelta d : deltas) {
                if (!stateIdx.containsKey(d.blockState())) {
                    stateIdx.put(d.blockState(), stateDict.size());
                    stateDict.add(d.blockState());
                }
            }
            buf.writeVarInt(stateDict.size());
            for (String st : stateDict) buf.writeUtf(st, ReplayBlockDelta.MAX_STATE_LENGTH);
            buf.writeVarInt(deltas.size());
            int prevDeltaTick = 0;
            for (ReplayBlockDelta d : deltas) {
                buf.writeVarInt(Math.max(0, d.tick() - prevDeltaTick));
                prevDeltaTick = d.tick();
                buf.writeVarInt(zigzag(d.x())).writeVarInt(zigzag(d.y())).writeVarInt(zigzag(d.z()));
                buf.writeVarInt(stateIdx.get(d.blockState()));
                buf.writeByte(d.reasonFlags());
            }

            List<ReplayEntityTrack> entities = s.entityTracks();
            buf.writeVarInt(entities.size());
            for (ReplayEntityTrack track : entities) {
                buf.writeUUID(track.entityId());
                buf.writeEnum(track.kind());
                buf.writeUtf(track.entityTypeId(), ReplayEntityTrack.MAX_TYPE_ID_LENGTH);
                writeId(buf, track.ownerUuid());
                writeId(buf, track.targetUuid());
                buf.writeFloat(track.radius());
                buf.writeVarInt(track.samples().size());
                int t = 0, x = 0, y = 0, z = 0;
                for (var sample : track.samples()) {
                    buf.writeVarInt(sample.tick() - t);
                    buf.writeVarInt(zigzag(sample.position().x() - x));
                    buf.writeVarInt(zigzag(sample.position().y() - y));
                    buf.writeVarInt(zigzag(sample.position().z() - z));
                    buf.writeByte(sample.yaw()).writeByte(sample.pitch());
                    buf.writeVarInt(sample.flags());
                    t = sample.tick();
                    x = sample.position().x();
                    y = sample.position().y();
                    z = sample.position().z();
                }
            }

            List<ReplaySkillEvent> skills = s.skillEvents();
            buf.writeVarInt(skills.size());
            for (ReplaySkillEvent ev : skills) {
                buf.writeVarInt(ev.eventId() + 1);
                buf.writeVarInt(ev.tick());
                buf.writeUtf(ev.skillOrItemId(), ReplaySkillEvent.MAX_ID_LENGTH);
                buf.writeUtf(ev.displayName(), ReplaySkillEvent.MAX_NAME_LENGTH);
                buf.writeUtf(ev.category(), ReplaySkillEvent.MAX_CATEGORY_LENGTH);
                writeId(buf, ev.actorUuid());
                writeId(buf, ev.targetUuid());
                buf.writeUtf(ev.actorRoleId(), ReplaySkillEvent.MAX_ID_LENGTH);
                buf.writeInt(ev.accentColor());
                writePosition(buf, ev.from());
                writePosition(buf, ev.to());
                buf.writeVarInt(ev.durationTicks());
                buf.writeUtf(ev.soundId(), ReplaySkillEvent.MAX_ID_LENGTH);
                buf.writeBoolean(ev.fallback());
            }

            boolean hasSounds = !s.gameSounds().isEmpty();
            buf.writeBoolean(hasSounds);
            if (hasSounds) {
                s.gameSounds().writeUncompressed(buf);
            }

            byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            return ReplayGameSoundData.gzip(raw);
        } finally {
            buf.release();
        }
    }

    private static V2Trailer decodeV2Trailer(byte[] compressed, List<Node> baseEvents, int duration) {
        byte[] raw = ReplayGameSoundData.gunzip(compressed, MAX_V2_TRAILER_UNCOMPRESSED);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            int ver = buf.readVarInt();
            if (ver != SUMMARY_V2_VERSION) throw new DecoderException("Unsupported replay v2 trailer version: " + ver);

            List<Node> events = baseEvents;
            if (buf.readBoolean()) {
                List<Node> updated = new ArrayList<>(baseEvents.size());
                for (Node n : baseEvents) {
                    int encodedId = buf.readVarInt();
                    if (encodedId < 0) throw new DecoderException("Bad replay eventId");
                    updated.add(n.withEventId(encodedId - 1));
                }
                events = updated;
            }

            int dictSize = count(buf, ReplayBlockDelta.MAX_DELTAS);
            List<String> stateDict = new ArrayList<>(dictSize);
            for (int i = 0; i < dictSize; i++) stateDict.add(buf.readUtf(ReplayBlockDelta.MAX_STATE_LENGTH));
            int deltaCount = count(buf, ReplayBlockDelta.MAX_DELTAS);
            List<ReplayBlockDelta> deltas = new ArrayList<>(deltaCount);
            int dTick = 0;
            for (int i = 0; i < deltaCount; i++) {
                int dt = buf.readVarInt();
                if (dt < 0 || dTick + dt > Math.max(duration, MAX_TICKS)) throw new DecoderException("Bad block delta tick");
                dTick += dt;
                int x = unzigzag(buf.readVarInt());
                int y = unzigzag(buf.readVarInt());
                int z = unzigzag(buf.readVarInt());
                int stIdx = buf.readVarInt();
                if (stIdx < 0 || stIdx >= stateDict.size()) throw new DecoderException("Bad block state dict index");
                byte reason = buf.readByte();
                deltas.add(new ReplayBlockDelta(dTick, x, y, z, stateDict.get(stIdx), reason));
            }

            int entityCount = count(buf, ReplayEntityTrack.MAX_TRACKS);
            List<ReplayEntityTrack> entities = new ArrayList<>(entityCount);
            for (int i = 0; i < entityCount; i++) {
                UUID eid = buf.readUUID();
                ReplayEntityTrack.EntityKind kind = buf.readEnum(ReplayEntityTrack.EntityKind.class);
                String typeId = buf.readUtf(ReplayEntityTrack.MAX_TYPE_ID_LENGTH);
                UUID owner = readId(buf);
                UUID target = readId(buf);
                float radius = buf.readFloat();
                int sCount = count(buf, ReplayEntityTrack.MAX_SAMPLES_PER_TRACK);
                List<ReplayEntityTrack.EntitySample> samples = new ArrayList<>(sCount);
                int t = 0, x = 0, y = 0, z = 0;
                for (int j = 0; j < sCount; j++) {
                    int dt = buf.readVarInt();
                    if (dt < 0 || t + dt > Math.max(duration, MAX_TICKS)) throw new DecoderException("Bad entity sample tick");
                    t += dt;
                    x += unzigzag(buf.readVarInt());
                    y += unzigzag(buf.readVarInt());
                    z += unzigzag(buf.readVarInt());
                    if (x < Short.MIN_VALUE || x > Short.MAX_VALUE || y < Short.MIN_VALUE || y > Short.MAX_VALUE
                            || z < Short.MIN_VALUE || z > Short.MAX_VALUE) {
                        throw new DecoderException("Entity coordinate out of range");
                    }
                    byte yaw = buf.readByte();
                    byte pitch = buf.readByte();
                    int flags = buf.readVarInt();
                    samples.add(new ReplayEntityTrack.EntitySample(t, new Position((short) x, (short) y, (short) z),
                            yaw, pitch, flags));
                }
                entities.add(new ReplayEntityTrack(eid, kind, typeId, owner, target, radius, samples));
            }

            int skillCount = count(buf, ReplaySkillEvent.MAX_SKILL_EVENTS);
            List<ReplaySkillEvent> skills = new ArrayList<>(skillCount);
            for (int i = 0; i < skillCount; i++) {
                int evId = buf.readVarInt() - 1;
                int tick = count(buf, Math.max(duration, MAX_TICKS));
                String skillId = buf.readUtf(ReplaySkillEvent.MAX_ID_LENGTH);
                String displayName = buf.readUtf(ReplaySkillEvent.MAX_NAME_LENGTH);
                String cat = buf.readUtf(ReplaySkillEvent.MAX_CATEGORY_LENGTH);
                UUID actor = readId(buf);
                UUID target = readId(buf);
                String roleId = buf.readUtf(ReplaySkillEvent.MAX_ID_LENGTH);
                int accent = buf.readInt();
                Position from = readPosition(buf);
                Position to = readPosition(buf);
                int dur = count(buf, MAX_TICKS);
                String soundId = buf.readUtf(ReplaySkillEvent.MAX_ID_LENGTH);
                boolean fallback = buf.readBoolean();
                skills.add(new ReplaySkillEvent(evId, tick, skillId, displayName, cat, actor, target,
                        roleId, accent, from, to, dur, soundId, fallback));
            }

            ReplayGameSoundData gameSounds = buf.readBoolean()
                    ? ReplayGameSoundData.readUncompressed(buf)
                    : ReplayGameSoundData.EMPTY;

            if (buf.isReadable()) throw new DecoderException("Trailing replay v2 trailer bytes");
            return new V2Trailer(events, deltas, entities, skills, gameSounds);
        } finally {
            buf.release();
        }
    }

    private static int zigzag(int n) { return (n << 1) ^ (n >> 31); }
    private static int unzigzag(int v) { return (v >>> 1) ^ -(v & 1); }

    private static void checkKillWindows(List<Node> events, List<ReplayKillSounds.Window> windows) {
        int last = -1;
        int total = 0;
        if (windows.size() > ReplayKillSounds.MAX_WINDOWS) throw new IllegalArgumentException("Too many kill sound windows");
        for (var window : windows) {
            int index = window.eventIndex();
            total += window.cues().size();
            if (index <= last || index >= events.size() || events.get(index).kind() != Kind.KILL
                    || total > ReplayKillSounds.MAX_PER_ROUND) {
                throw new IllegalArgumentException("Invalid replay kill sound index or count");
            }
            last = index;
        }
    }

    private static List<ReplayKillSounds.Cue> readKillCues(FriendlyByteBuf buffer) {
        int size = count(buffer, ReplayKillSounds.MAX_PER_KILL);
        List<ReplayKillSounds.Cue> cues = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            cues.add(new ReplayKillSounds.Cue(buffer.readShort(),
                    ResourceLocation.parse(buffer.readUtf(ReplayKillSounds.MAX_FILE_LENGTH)), buffer.readFloat(), buffer.readFloat()));
        }
        return cues;
    }

    private static void writeKillCues(FriendlyByteBuf buffer, List<ReplayKillSounds.Cue> cues) {
        if (cues.size() > ReplayKillSounds.MAX_PER_KILL) throw new IllegalArgumentException("Too many recorded sounds");
        buffer.writeVarInt(cues.size());
        for (var cue : cues) {
            buffer.writeShort(cue.offsetMs());
            buffer.writeUtf(cue.file().toString(), ReplayKillSounds.MAX_FILE_LENGTH);
            buffer.writeFloat(cue.volume()).writeFloat(cue.pitch());
        }
    }

    private static void writeManifest(FriendlyByteBuf b, ReplayMapManifest m) {
        b.writeUtf(m.mapKey(), 128); b.writeUtf(m.dimension(), 128); writeBounds(b, m.sourceBounds());
        b.writeUtf(m.hash(), 64); b.writeVarInt(m.sections().size());
        for (var entry : m.sections()) {
            var s = entry.section();
            b.writeVarInt(s.x()); b.writeVarInt(s.y()); b.writeVarInt(s.z()); b.writeInt(entry.crc32());
        }
    }

    private static ReplayMapManifest readManifest(FriendlyByteBuf b) {
        String key = b.readUtf(128), dimension = b.readUtf(128);
        SceneBounds bounds = readBounds(b);
        String hash = b.readUtf(64);
        int n = count(b, ReplayMapManifest.MAX_SECTIONS);
        List<ReplayMapManifest.Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new ReplayMapManifest.Entry(
                    new ReplayMapManifest.Section(b.readVarInt(), b.readVarInt(), b.readVarInt()), b.readInt()));
        }
        return new ReplayMapManifest(key, dimension, bounds, hash, entries);
    }

    private static int count(FriendlyByteBuf b, int max) {
        int value = b.readVarInt();
        if (value < 0 || value > max) throw new DecoderException("Bad replay count: " + value);
        return value;
    }

    private static void writeBounds(FriendlyByteBuf b, SceneBounds bounds) {
        b.writeInt(bounds.minX()).writeInt(bounds.minY()).writeInt(bounds.minZ());
        b.writeInt(bounds.maxX()).writeInt(bounds.maxY()).writeInt(bounds.maxZ());
    }

    private static SceneBounds readBounds(FriendlyByteBuf b) {
        int x = b.readInt(), y = b.readInt(), z = b.readInt(), xx = b.readInt(), yy = b.readInt(), zz = b.readInt();
        if (x > xx || y > yy || z > zz) throw new DecoderException("Inverted replay bounds");
        return new SceneBounds(x, y, z, xx, yy, zz);
    }

    private static void writeId(FriendlyByteBuf b, @Nullable UUID id) {
        b.writeBoolean(id != null);
        if (id != null) b.writeUUID(id);
    }

    private static @Nullable UUID readId(FriendlyByteBuf b) { return b.readBoolean() ? b.readUUID() : null; }

    private static void writePosition(FriendlyByteBuf b, @Nullable Position p) {
        b.writeBoolean(p != null);
        if (p != null) b.writeShort(p.x()).writeShort(p.y()).writeShort(p.z());
    }

    private static @Nullable Position readPosition(FriendlyByteBuf b) {
        return b.readBoolean() ? new Position(b.readShort(), b.readShort(), b.readShort()) : null;
    }
}
