package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C pulse scan highlight packet sent to the drone operator (6s classified outlines + holo wave)
 * and living killer teammates (4s shared innocent/armed highlights).
 */
public record DroneScanHighlightPayload(
        boolean pulseWave,
        double originX,
        double originY,
        double originZ,
        List<Target> targets
) implements CustomPacketPayload {

    public record Target(UUID uuid, int entityId, int rgbColor, int durationTicks) {}

    public DroneScanHighlightPayload {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public static final Type<DroneScanHighlightPayload> TYPE = new Type<>(HabiTrainCore.id("drone_scan_highlight"));
    public static final StreamCodec<FriendlyByteBuf, DroneScanHighlightPayload> CODEC = StreamCodec.ofMember(
            DroneScanHighlightPayload::write, DroneScanHighlightPayload::read
    );
    private static boolean registered;

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(pulseWave);
        buf.writeDouble(originX);
        buf.writeDouble(originY);
        buf.writeDouble(originZ);
        buf.writeVarInt(targets.size());
        for (Target target : targets) {
            buf.writeUUID(target.uuid());
            buf.writeVarInt(target.entityId());
            buf.writeInt(target.rgbColor());
            buf.writeVarInt(target.durationTicks());
        }
    }

    private static DroneScanHighlightPayload read(FriendlyByteBuf buf) {
        boolean pulseWave = buf.readBoolean();
        double ox = buf.readDouble();
        double oy = buf.readDouble();
        double oz = buf.readDouble();
        int count = buf.readVarInt();
        if (count < 0 || count > 256) {
            throw new DecoderException("Invalid drone scan target count: " + count);
        }
        List<Target> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Target(buf.readUUID(), buf.readVarInt(), buf.readInt(), buf.readVarInt()));
        }
        return new DroneScanHighlightPayload(pulseWave, ox, oy, oz, list);
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
