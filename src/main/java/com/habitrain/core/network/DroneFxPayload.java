package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C world FX packet for high-visibility 3P/1P drone payload bursts, deploy downwash,
 * recall streams, door data arcs, muzzle flashes, and blade arcs.
 */
public record DroneFxPayload(
        int droneEntityId,
        byte fxType,
        double x,
        double y,
        double z,
        double tx,
        double ty,
        double tz
) implements CustomPacketPayload {

    public static final byte FX_GUN_SHOT = 1;
    public static final byte FX_BLADE_SLASH = 2;
    public static final byte FX_DOOR_ARC = 3;
    public static final byte FX_MIST_SPRAY = 4;
    public static final byte FX_EXPLOSIVE_DROP = 5;
    public static final byte FX_POISON_STING = 6;
    public static final byte FX_NET_SHOT = 7;
    public static final byte FX_SILENCE_FIELD = 8;
    public static final byte FX_AIRDROP = 9;
    public static final byte FX_DEPLOY_BURST = 10;
    public static final byte FX_RECALL_STREAM = 11;
    /** Operator entity ID in {@link #droneEntityId}: triggers 3P/world take-out &amp; unfold animation. */
    public static final byte FX_RESUPPLY_UNBOX = 12;
    /** Scrapped old drone spark &amp; disconnect burst. */
    public static final byte FX_DRONE_SCRAPPED = 13;

    public static final Type<DroneFxPayload> TYPE = new Type<>(HabiTrainCore.id("drone_fx"));
    public static final StreamCodec<FriendlyByteBuf, DroneFxPayload> CODEC = StreamCodec.ofMember(
            DroneFxPayload::write, DroneFxPayload::read
    );
    private static boolean registered;

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(droneEntityId);
        buf.writeByte(fxType);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeDouble(tx);
        buf.writeDouble(ty);
        buf.writeDouble(tz);
    }

    private static DroneFxPayload read(FriendlyByteBuf buf) {
        return new DroneFxPayload(
                buf.readVarInt(),
                buf.readByte(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
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
