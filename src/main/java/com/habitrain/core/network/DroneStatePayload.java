package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C private HUD / camera / battery synchronization for {@code habitrain_core:drone_operator}.
 */
public record DroneStatePayload(
        int droneEntityId,
        boolean hasDroneFrame,
        boolean everHadDrone,
        int battery,
        boolean fpvActive,
        byte transitionMode,
        int transitionTicks,
        int scanCooldownTicks,
        boolean bodyDangerNearby,
        int spareBatteries,
        float drainPerSec,
        double homeX,
        double homeY,
        double homeZ
) implements CustomPacketPayload {

    public static final byte TRANSITION_NONE = 0;
    public static final byte TRANSITION_DEPLOYING = 1;
    public static final byte TRANSITION_RECALLING = 2;
    public static final byte TRANSITION_SWAPPING = 3;
    public static final byte TRANSITION_DESTROYED_STATIC = 4;
    public static final byte TRANSITION_UNBOXING = 5;

    public static final DroneStatePayload EMPTY = new DroneStatePayload(
            -1, false, false, 0, false, TRANSITION_NONE, 0, 0, false, 0, 0.0F, 0.0D, 0.0D, 0.0D
    );

    public static final Type<DroneStatePayload> TYPE = new Type<>(HabiTrainCore.id("drone_state"));
    public static final StreamCodec<FriendlyByteBuf, DroneStatePayload> CODEC = StreamCodec.ofMember(
            DroneStatePayload::write, DroneStatePayload::read
    );
    private static boolean registered;

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(droneEntityId);
        buf.writeBoolean(hasDroneFrame);
        buf.writeBoolean(everHadDrone);
        buf.writeVarInt(battery);
        buf.writeBoolean(fpvActive);
        buf.writeByte(transitionMode);
        buf.writeVarInt(transitionTicks);
        buf.writeVarInt(scanCooldownTicks);
        buf.writeBoolean(bodyDangerNearby);
        buf.writeVarInt(spareBatteries);
        buf.writeFloat(drainPerSec);
        buf.writeDouble(homeX);
        buf.writeDouble(homeY);
        buf.writeDouble(homeZ);
    }

    private static DroneStatePayload read(FriendlyByteBuf buf) {
        return new DroneStatePayload(
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readByte(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readFloat(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
