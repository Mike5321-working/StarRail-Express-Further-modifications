package com.habitrain.core.network.replay;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Editing requests and acknowledgement; vanilla block-entity updates broadcast saved settings. */
public final class ReplayBoardSettingsPackets {
    private ReplayBoardSettingsPackets() {}

    /**
     * Requests updating a replay table's persisted display settings and optionally its horizontal block {@code facing}.
     * Passing {@code facing == null} preserves the existing block facing.
     */
    public record Save(ResourceLocation dimension, BlockPos pos, ReplayBoardSettings settings,
                       @Nullable Direction facing) implements CustomPacketPayload {
        public Save {
            if (facing != null && !facing.getAxis().isHorizontal()) {
                throw new IllegalArgumentException("Replay table facing must be horizontal: " + facing);
            }
        }

        /** Backward-compatible 3-arg constructor (keeps current block facing). */
        public Save(ResourceLocation dimension, BlockPos pos, ReplayBoardSettings settings) {
            this(dimension, pos, settings, null);
        }

        public static final Type<Save> TYPE = new Type<>(HabiTrainCore.id("replay_board_settings_save"));
        public static final StreamCodec<FriendlyByteBuf, Save> CODEC = StreamCodec.of((buf, value) -> {
            writeSaveBody(buf, value);
        }, buf -> {
            Save save = readSaveBody(buf);
            if (buf.isReadable()) throw new DecoderException("Trailing table settings bytes");
            return save;
        });

        @Override public Type<Save> type() { return TYPE; }
    }

    public record Result(ResourceLocation dimension, BlockPos pos, boolean saved) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(HabiTrainCore.id("replay_board_settings_result"));
        public static final StreamCodec<FriendlyByteBuf, Result> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeResourceLocation(value.dimension); buf.writeBlockPos(value.pos); buf.writeBoolean(value.saved);
        }, buf -> {
            var result = new Result(buf.readResourceLocation(), buf.readBlockPos(), buf.readBoolean());
            if (buf.isReadable()) throw new DecoderException("Trailing table settings result bytes");
            return result;
        });
        @Override public Type<Result> type() { return TYPE; }
    }

    public record CheckedSave(UUID requestId, ReplayBoardSettings expected,
                              @Nullable Direction expectedFacing, Save save) implements CustomPacketPayload {
        public CheckedSave {
            if (expectedFacing != null && !expectedFacing.getAxis().isHorizontal()) {
                throw new IllegalArgumentException("Expected replay table facing must be horizontal: " + expectedFacing);
            }
        }

        /** Backward-compatible 3-arg constructor (does not conflict-check facing). */
        public CheckedSave(UUID requestId, ReplayBoardSettings expected, Save save) {
            this(requestId, expected, null, save);
        }

        public static final Type<CheckedSave> TYPE = new Type<>(HabiTrainCore.id("replay_board_settings_checked_save"));
        public static final StreamCodec<FriendlyByteBuf, CheckedSave> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.requestId);
            var settings = value.expected;
            buf.writeFloat(settings.mapSize()); buf.writeFloat(settings.height());
            buf.writeFloat(settings.offsetX()); buf.writeFloat(settings.offsetZ()); buf.writeFloat(settings.rotation());
            buf.writeBoolean(value.expectedFacing != null);
            if (value.expectedFacing != null) buf.writeByte(value.expectedFacing.get2DDataValue());
            writeSaveBody(buf, value.save);
        }, buf -> {
            UUID reqId = buf.readUUID();
            ReplayBoardSettings expected = new ReplayBoardSettings(buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat());
            Direction expFacing = buf.readBoolean() ? readHorizontalDirection(buf) : null;
            Save save = readSaveBody(buf);
            if (buf.isReadable()) throw new DecoderException("Trailing checked table settings bytes");
            return new CheckedSave(reqId, expected, expFacing, save);
        });
        @Override public Type<CheckedSave> type() { return TYPE; }
    }

    public record CheckedResult(UUID requestId, Status status) implements CustomPacketPayload {
        public enum Status { REJECTED, SAVED, CONFLICT }
        public static final Type<CheckedResult> TYPE = new Type<>(HabiTrainCore.id("replay_board_settings_checked_result"));
        public static final StreamCodec<FriendlyByteBuf, CheckedResult> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeUUID(value.requestId); buf.writeEnum(value.status); },
                buf -> new CheckedResult(buf.readUUID(), buf.readEnum(Status.class)));
        @Override public Type<CheckedResult> type() { return TYPE; }
    }

    private static void writeSaveBody(FriendlyByteBuf buf, Save value) {
        buf.writeResourceLocation(value.dimension);
        buf.writeBlockPos(value.pos);
        var s = value.settings;
        buf.writeFloat(s.mapSize()); buf.writeFloat(s.height());
        buf.writeFloat(s.offsetX()); buf.writeFloat(s.offsetZ()); buf.writeFloat(s.rotation());
        if (value.facing != null) buf.writeByte(value.facing.get2DDataValue());
    }

    private static Save readSaveBody(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        BlockPos pos = buf.readBlockPos();
        ReplayBoardSettings settings = new ReplayBoardSettings(buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat());
        Direction facing = buf.isReadable() ? readHorizontalDirection(buf) : null;
        return new Save(dimension, pos, settings, facing);
    }

    private static Direction readHorizontalDirection(FriendlyByteBuf buf) {
        int raw = buf.readUnsignedByte();
        if (raw > 3) throw new DecoderException("Invalid horizontal facing value: " + raw);
        return Direction.from2DDataValue(raw);
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Save.TYPE, Save.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.TYPE, Result.CODEC);
        PayloadTypeRegistry.playC2S().register(CheckedSave.TYPE, CheckedSave.CODEC);
        PayloadTypeRegistry.playS2C().register(CheckedResult.TYPE, CheckedResult.CODEC);
    }
}
