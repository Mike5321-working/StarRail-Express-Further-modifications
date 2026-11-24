package com.habitrain.core.network.replay;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

public record WorldEntityFrame(double x,double y,double z,float yaw,float pitch,float bodyYaw,float headYaw,int born) {
    public byte[] encode(UUID id) {
        return VisualTape.bytes(out->{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());
            out.writeDouble(x);out.writeDouble(y);out.writeDouble(z);out.writeFloat(yaw);out.writeFloat(pitch);out.writeFloat(bodyYaw);out.writeFloat(headYaw);out.writeInt(born);});
    }
    public static WorldEntityFrame read(DataInputStream in) throws IOException {
        if (in.available()==0) return null;
        var frame=new WorldEntityFrame(in.readDouble(),in.readDouble(),in.readDouble(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readInt());
        if (in.read()!=-1 || !(Math.abs(frame.x)<=30000000 && Math.abs(frame.y)<=30000000 && Math.abs(frame.z)<=30000000 && Float.isFinite(frame.yaw) && Float.isFinite(frame.pitch) && Float.isFinite(frame.headYaw) && Float.isFinite(frame.bodyYaw))) throw new IOException("Invalid recorded entity position");
        return frame;
    }
}
