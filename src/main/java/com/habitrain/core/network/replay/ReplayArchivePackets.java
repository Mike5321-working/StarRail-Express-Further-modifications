package com.habitrain.core.network.replay;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.BitSet;
import java.util.UUID;

public final class ReplayArchivePackets {
    public static final int LOOKUP = 0, SECTIONS = 1, SHARE = 2, STATS = 3, LIMIT = 4, BOARD_READY = 5, VOICE = 6;
    public static final int READY = 0, CACHED = 1, MISSING = 2, ERROR = 3, SHARED = 4, NO_TABLE = 5, TABLE_BUSY = 6, SHARE_ERROR = 7;
    public static final int TRANSFER_BUSY = 8;
    private ReplayArchivePackets() {}

    public record Request(int action, UUID round, String digest, BitSet missing, BlockPos board, long limit)
            implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(HabiTrainCore.id("replay_archive_request"));
        private static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.ofMember(Request::write, Request::read);

        private static Request read(FriendlyByteBuf b) {
            int action = b.readUnsignedByte();
            UUID round = b.readUUID();
            return switch (action) {
                case LOOKUP -> lookup(round, b.readUtf(64));
                case SECTIONS -> sections(round, BitSet.valueOf(b.readByteArray(ReplayMapManifest.MAX_SECTIONS / 8)));
                case SHARE -> share(round, b.readBlockPos());
                case BOARD_READY -> boardReady(round, b.readBlockPos());
                case STATS, LIMIT -> settings(action, action == LIMIT ? b.readLong() : 0);
                case VOICE -> voice(round);
                default -> throw new IllegalArgumentException("Unknown replay archive operation");
            };
        }

        private void write(FriendlyByteBuf b) {
            b.writeByte(action);
            b.writeUUID(round);
            switch (action) {
                case LOOKUP -> b.writeUtf(digest, 64);
                case SECTIONS -> b.writeByteArray(missing.toByteArray());
                case SHARE, BOARD_READY -> b.writeBlockPos(board);
                case LIMIT -> b.writeLong(limit);
                case VOICE -> {}
            }
        }

        public static Request lookup(UUID round, String digest) { return new Request(LOOKUP, round, digest, null, null, 0); }
        public static Request sections(UUID round, BitSet bits) { return new Request(SECTIONS, round, "", bits, null, 0); }
        public static Request share(UUID round, BlockPos board) { return new Request(SHARE, round, "", null, board, 0); }
        public static Request boardReady(UUID round, BlockPos board) { return new Request(BOARD_READY, round, "", null, board, 0); }
        public static Request settings(int action, long limit) { return new Request(action, ReplayPackets.EMPTY, "", null, null, limit); }
        public static Request voice(UUID round) { return new Request(VOICE, round, "", null, null, 0); }
        @Override public Type<Request> type() { return TYPE; }
    }

    public record Reply(UUID round, int status) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = new Type<>(HabiTrainCore.id("replay_archive_reply"));
        private static final StreamCodec<FriendlyByteBuf, Reply> CODEC = StreamCodec.ofMember(
                (p, b) -> { b.writeUUID(p.round); b.writeByte(p.status); }, b -> new Reply(b.readUUID(), b.readUnsignedByte()));
        @Override public Type<Reply> type() { return TYPE; }
    }

    public record Settings(long used, long limit, String path, boolean editable, boolean ok,
                           int roundCount, int quarantinedCount, long quarantinedBytes) implements CustomPacketPayload {
        public static final Type<Settings> TYPE = new Type<>(HabiTrainCore.id("replay_archive_settings"));
        private static final StreamCodec<FriendlyByteBuf, Settings> CODEC = StreamCodec.ofMember(
                (p, b) -> {
                    b.writeLong(p.used);
                    b.writeLong(p.limit);
                    b.writeUtf(p.path, 4096);
                    b.writeBoolean(p.editable);
                    b.writeBoolean(p.ok);
                    b.writeVarInt(p.roundCount);
                    b.writeVarInt(p.quarantinedCount);
                    b.writeLong(p.quarantinedBytes);
                },
                b -> {
                    long used = b.readLong();
                    long limit = b.readLong();
                    String path = b.readUtf(4096);
                    boolean editable = b.readBoolean();
                    boolean ok = b.readBoolean();
                    int rounds = b.isReadable() ? b.readVarInt() : 0;
                    int qCount = b.isReadable() ? b.readVarInt() : 0;
                    long qBytes = b.isReadable() ? b.readLong() : 0L;
                    return new Settings(used, limit, path, editable, ok, rounds, qCount, qBytes);
                });

        /** Backward-compatible 5-arg constructor. */
        public Settings(long used, long limit, String path, boolean editable, boolean ok) {
            this(used, limit, path, editable, ok, 0, 0, 0L);
        }

        @Override public Type<Settings> type() { return TYPE; }
    }

    public record SetVoiceRecording(boolean enabled) implements CustomPacketPayload {
        public static final Type<SetVoiceRecording> TYPE = new Type<>(HabiTrainCore.id("replay_archive_set_voice_recording"));
        private static final StreamCodec<FriendlyByteBuf, SetVoiceRecording> CODEC = StreamCodec.ofMember(
                (p, b) -> b.writeBoolean(p.enabled), b -> new SetVoiceRecording(b.readBoolean()));
        @Override public Type<SetVoiceRecording> type() { return TYPE; }
    }

    public record CheckedLimit(long expected, long limit) implements CustomPacketPayload {
        public static final Type<CheckedLimit> TYPE = new Type<>(HabiTrainCore.id("replay_archive_checked_limit"));
        private static final StreamCodec<FriendlyByteBuf, CheckedLimit> CODEC = StreamCodec.ofMember(
                (p, b) -> { b.writeLong(p.expected); b.writeLong(p.limit); },
                b -> new CheckedLimit(b.readLong(), b.readLong()));
        @Override public Type<CheckedLimit> type() { return TYPE; }
    }

    public record VoiceRecordingSettings(boolean enabled, boolean editable, boolean ok) implements CustomPacketPayload {
        public static final Type<VoiceRecordingSettings> TYPE = new Type<>(HabiTrainCore.id("replay_archive_voice_recording"));
        private static final StreamCodec<FriendlyByteBuf, VoiceRecordingSettings> CODEC = StreamCodec.ofMember(
                (p, b) -> { b.writeBoolean(p.enabled); b.writeBoolean(p.editable); b.writeBoolean(p.ok); },
                b -> new VoiceRecordingSettings(b.readBoolean(), b.readBoolean(), b.readBoolean()));
        @Override public Type<VoiceRecordingSettings> type() { return TYPE; }
    }

    public record Summary(ReplayPackets.LastMatchSummaryS2C data) implements CustomPacketPayload {
        public static final Type<Summary> TYPE = new Type<>(HabiTrainCore.id("replay_archive_summary"));
        private static final StreamCodec<FriendlyByteBuf, Summary> CODEC = StreamCodec.ofMember(
                (p, b) -> ReplayPackets.LastMatchSummaryS2C.CODEC.encode(b, p.data),
                b -> new Summary(ReplayPackets.LastMatchSummaryS2C.CODEC.decode(b)));
        @Override public Type<Summary> type() { return TYPE; }
    }

    public record Section(ReplayPackets.ReplaySectionPatchS2C data) implements CustomPacketPayload {
        public static final Type<Section> TYPE = new Type<>(HabiTrainCore.id("replay_archive_section"));
        private static final StreamCodec<FriendlyByteBuf, Section> CODEC = StreamCodec.ofMember(
                (p, b) -> ReplayPackets.ReplaySectionPatchS2C.CODEC.encode(b, p.data),
                b -> new Section(ReplayPackets.ReplaySectionPatchS2C.CODEC.decode(b)));
        @Override public Type<Section> type() { return TYPE; }
    }

    public record Voice(ReplayPackets.ReplayVoiceChunkS2C data) implements CustomPacketPayload {
        public static final Type<Voice> TYPE = new Type<>(HabiTrainCore.id("replay_archive_voice"));
        private static final StreamCodec<FriendlyByteBuf, Voice> CODEC = StreamCodec.ofMember(
                (p, b) -> ReplayPackets.ReplayVoiceChunkS2C.CODEC.encode(b, p.data),
                b -> new Voice(ReplayPackets.ReplayVoiceChunkS2C.CODEC.decode(b)));
        @Override public Type<Voice> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.playC2S().register(SetVoiceRecording.TYPE, SetVoiceRecording.CODEC);
        PayloadTypeRegistry.playC2S().register(CheckedLimit.TYPE, CheckedLimit.CODEC);
        PayloadTypeRegistry.playS2C().register(Reply.TYPE, Reply.CODEC);
        PayloadTypeRegistry.playS2C().register(Summary.TYPE, Summary.CODEC);
        PayloadTypeRegistry.playS2C().register(Section.TYPE, Section.CODEC);
        PayloadTypeRegistry.playS2C().register(Voice.TYPE, Voice.CODEC);
        PayloadTypeRegistry.playS2C().register(Settings.TYPE, Settings.CODEC);
        PayloadTypeRegistry.playS2C().register(VoiceRecordingSettings.TYPE, VoiceRecordingSettings.CODEC);
    }
}
