package com.habitrain.core.network.replay;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public record CorpseAppearance(UUID entity, UUID owner, UUID skin, int born, double x, double y, double z,
                              float yaw, float pitch, float bodyYaw, float headYaw, float scale,
                              boolean hidden, boolean corrupted) {
    public byte[] encode() {
        return VisualTape.bytes(out -> {
            uuid(out,entity); optional(out,owner); optional(out,skin); out.writeInt(born);
            out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
            out.writeFloat(yaw); out.writeFloat(pitch); out.writeFloat(bodyYaw); out.writeFloat(headYaw);
            out.writeFloat(scale); out.writeBoolean(hidden); out.writeBoolean(corrupted);
        });
    }
    public static byte[] removal(UUID entity) { return VisualTape.bytes(out -> uuid(out,entity)); }
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static void optional(DataOutputStream out, UUID id) throws IOException {
        out.writeBoolean(id!=null); if (id!=null) uuid(out,id);
    }
    public static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(),in.readLong()); }
    public static CorpseAppearance read(UUID entity, DataInputStream in) throws IOException {
        if (in.available()==0) return null;
        var value = new CorpseAppearance(entity,in.readBoolean()?uuid(in):null,in.readBoolean()?uuid(in):null,in.readInt(),
                in.readDouble(),in.readDouble(),in.readDouble(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),
                in.readFloat(),in.readBoolean(),in.readBoolean());
        if (in.read()!=-1 || !(Math.abs(value.x)<=30000000 && Math.abs(value.y)<=30000000 && Math.abs(value.z)<=30000000
                && value.scale>0 && value.scale<=64 && Float.isFinite(value.yaw) && Float.isFinite(value.pitch)
                && Float.isFinite(value.bodyYaw) && Float.isFinite(value.headYaw))) throw new IOException("Invalid recorded body appearance");
        return value;
    }
}
