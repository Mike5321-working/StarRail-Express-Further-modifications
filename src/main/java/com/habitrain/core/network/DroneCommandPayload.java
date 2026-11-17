package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S discrete tactical command from the drone operator.
 */
public record DroneCommandPayload(Action action) implements CustomPacketPayload {

    public enum Action {
        DEPLOY_OR_TOGGLE,
        EXIT_FPV,
        RECALL_FOLD,
        SWAP_BATTERY,
        PULSE_SCAN,
        TRIGGER_PAYLOAD,
        UNLOAD_PAYLOAD,
        USE_SPARE_BATTERY;

        public static Action byId(int id) {
            Action[] values = values();
            return id >= 0 && id < values.length ? values[id] : EXIT_FPV;
        }
    }

    public static final Type<DroneCommandPayload> TYPE = new Type<>(HabiTrainCore.id("drone_command"));
    public static final StreamCodec<FriendlyByteBuf, DroneCommandPayload> CODEC = StreamCodec.ofMember(
            DroneCommandPayload::write, DroneCommandPayload::read
    );
    private static boolean registered;

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt((action == null ? Action.EXIT_FPV : action).ordinal());
    }

    private static DroneCommandPayload read(FriendlyByteBuf buf) {
        return new DroneCommandPayload(Action.byId(buf.readVarInt()));
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
