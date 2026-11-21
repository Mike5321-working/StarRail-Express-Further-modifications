package com.habitrain.core.network.replay;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Editing requests and acknowledgement; vanilla block-entity updates broadcast saved settings. */
public final class ReplayBoardSettingsPackets {
    private ReplayBoardSettingsPackets() {}

    public record Save(ResourceLocation dimension, BlockPos pos, ReplayBoardSettings settings) implements CustomPacketPayload {
        public static final Type<Save> TYPE = new Type<>(HabiTrainCore.id("replay_board_settings_save"));
        public static final StreamCodec<FriendlyByteBuf, Save> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeResourceLocation(value.dimension);
            buf.writeBlockPos(value.pos);
            var s = value.settings;
            buf.writeFloat(s.mapSize()); buf.writeFloat(s.height());
            buf.writeFloat(s.offsetX()); buf.writeFloat(s.offsetZ()); buf.writeFloat(s.rotation());
        }, buf -> {
            ResourceLocation dimension = buf.readResourceLocation();
            BlockPos pos = buf.readBlockPos();
            ReplayBoardSettings settings = new ReplayBoardSettings(buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat());
            if (buf.isReadable()) throw new DecoderException("Trailing table settings bytes");
            return new Save(dimension, pos, settings);
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

    public record CheckedSave(UUID requestId, ReplayBoardSettings expected, Save save) implements CustomPacketPayload {
        public static final Type<CheckedSave> TYPE = new Type<>(HabiTrainCore.id("replay_board_settings_checked_save"));
        public static final StreamCodec<FriendlyByteBuf, CheckedSave> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.requestId);
            var settings = value.expected;
            buf.writeFloat(settings.mapSize()); buf.writeFloat(settings.height());
            buf.writeFloat(settings.offsetX()); buf.writeFloat(settings.offsetZ()); buf.writeFloat(settings.rotation());
            Save.CODEC.encode(buf, value.save);
        }, buf -> new CheckedSave(buf.readUUID(), new ReplayBoardSettings(buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat()), Save.CODEC.decode(buf)));
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

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Save.TYPE, Save.CODEC);
        PayloadTypeRegistry.playS2C().register(Result.TYPE, Result.CODEC);
        PayloadTypeRegistry.playC2S().register(CheckedSave.TYPE, CheckedSave.CODEC);
        PayloadTypeRegistry.playS2C().register(CheckedResult.TYPE, CheckedResult.CODEC);
    }
}
