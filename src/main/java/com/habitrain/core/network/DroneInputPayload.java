package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Mth;

/**
 * C2S flight control input while the operator is piloting a {@code recon_drone} in FPV.
 */
public record DroneInputPayload(
        float forward,
        float strafe,
        float vertical,
        float yaw,
        float pitch,
        boolean sprinting
) implements CustomPacketPayload {

    public static final Type<DroneInputPayload> TYPE = new Type<>(HabiTrainCore.id("drone_input"));
    public static final StreamCodec<FriendlyByteBuf, DroneInputPayload> CODEC = StreamCodec.ofMember(
            DroneInputPayload::write, DroneInputPayload::read
    );
    private static boolean registered;

    public DroneInputPayload {
        forward = Float.isFinite(forward) ? Mth.clamp(forward, -1.0F, 1.0F) : 0.0F;
        strafe = Float.isFinite(strafe) ? Mth.clamp(strafe, -1.0F, 1.0F) : 0.0F;
        vertical = Float.isFinite(vertical) ? Mth.clamp(vertical, -1.0F, 1.0F) : 0.0F;
        yaw = Float.isFinite(yaw) ? Mth.wrapDegrees(yaw) : 0.0F;
        pitch = Float.isFinite(pitch) ? Mth.clamp(pitch, -89.0F, 89.0F) : 0.0F;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(forward);
        buf.writeFloat(strafe);
        buf.writeFloat(vertical);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeBoolean(sprinting);
    }

    private static DroneInputPayload read(FriendlyByteBuf buf) {
        return new DroneInputPayload(
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readBoolean()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
