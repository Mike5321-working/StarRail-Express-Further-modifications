package com.habitrain.core.network.replay;

import java.io.*;
import java.util.*;

public final class VisualFrames {
    private VisualFrames() {}
    public record Hands(float oldMain,float main,float oldOff,float off,float oldSwing,float swing,boolean offSwing) {
        static Hands read(DataInputStream in) throws IOException {
            var hands=new Hands(unit(in),unit(in),unit(in),unit(in),unit(in),unit(in),in.readBoolean()); return hands;
        }
        private static float unit(DataInputStream in) throws IOException { float number=in.readFloat(); if(!(number>=0 && number<=1)) throw new IOException("Invalid recorded hand"); return number; }
        void write(DataOutputStream out) throws IOException {out.writeFloat(oldMain);out.writeFloat(main);out.writeFloat(oldOff);out.writeFloat(off);out.writeFloat(oldSwing);out.writeFloat(swing);out.writeBoolean(offSwing);}
    }
    public record Camera(double x, double y, double z, float yaw, float pitch, float fov, boolean spectator, boolean hideGui, int filters, Hands hands) {
        public Camera(double x,double y,double z,float yaw,float pitch,float fov,boolean spectator,boolean hideGui) {
            this(x,y,z,yaw,pitch,fov,spectator,hideGui,0,null);
        }
        public static Camera read(DataInputStream in) throws IOException {
            var result = new Camera(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readFloat(), in.readBoolean(), in.readBoolean(), in.available()==0?0:in.readUnsignedByte(),in.available()==0?null:Hands.read(in));
            if (!(Math.abs(result.x) <= 30000000 && Math.abs(result.z) <= 30000000 && Math.abs(result.y) <= 30000000 && Float.isFinite(result.yaw) && Float.isFinite(result.pitch) && result.fov > 0 && result.fov <= 180)) throw new IOException("Invalid recorded camera");
            return result;
        }
        public byte[] encode() { return VisualTape.bytes(out -> {
            out.writeDouble(x); out.writeDouble(y); out.writeDouble(z); out.writeFloat(yaw); out.writeFloat(pitch); out.writeFloat(fov); out.writeBoolean(spectator); out.writeBoolean(hideGui); out.writeByte(filters); if(hands!=null) hands.write(out);
        }); }
    }
    public record Draw(int kind, float x, float y, float xx, float yy, int color, String text, float[] uv) {
        public void write(DataOutputStream out) throws IOException {
            out.writeByte(kind); out.writeFloat(x); out.writeFloat(y); out.writeFloat(xx); out.writeFloat(yy);
            out.writeInt(color); out.writeUTF(text); if (kind == 3) for (float value : uv) out.writeFloat(value);
        }
    }
    public record Hud(int width, int height, List<Draw> draws) {
        public byte[] encode() { return VisualTape.bytes(out -> {
            out.writeShort(width); out.writeShort(height); out.writeShort(draws.size());
            for (var draw : draws) draw.write(out);
        }); }
        public static Hud read(DataInputStream in) throws IOException {
            int width = in.readUnsignedShort(), height = in.readUnsignedShort(), count = in.readUnsignedShort();
            if (width < 1 || height < 1 || width > 16384 || height > 16384 || count > 256) throw new IOException("Invalid HUD dimensions");
            var draws = new ArrayList<Draw>(count);
            for (int index = 0; index < count; index++) {
                int kind = in.readUnsignedByte(); float x = coordinate(in), y = coordinate(in), xx = coordinate(in), yy = coordinate(in);
                int color = in.readInt(); String text = in.readUTF();
                if (kind > 4 || text.length() > 512 || ((kind == 3 || kind == 4) && (!text.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || text.contains("..")))) throw new IOException("Invalid HUD command");
                float[] uv = kind == 3 ? new float[]{coordinate(in), coordinate(in), coordinate(in), coordinate(in)} : null;
                draws.add(new Draw(kind, x, y, xx, yy, color, text, uv));
            }
            return new Hud(width, height, draws);
        }
        private static float coordinate(DataInputStream in) throws IOException {
            float value = in.readFloat(); if (!(Math.abs(value) <= 65536)) throw new IOException("Invalid HUD coordinate"); return value;
        }
    }
    public static boolean valid(byte[] camera, byte[] hud) {
        try {
            if (camera.length != 0) try (var in = new DataInputStream(new ByteArrayInputStream(camera))) { Camera.read(in); if (in.read() != -1) return false; }
            if (hud.length != 0) try (var in = new DataInputStream(new ByteArrayInputStream(hud))) { Hud.read(in); if (in.read() != -1) return false; }
            return true;
        } catch (IOException invalid) { return false; }
    }
    public static Map<UUID,Integer> outlines(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort(); if (count > 128 || in.available() != count*20) throw new IOException("Invalid outlines");
        var result = new HashMap<UUID,Integer>();
        for (int index=0;index<count;index++) result.put(new UUID(in.readLong(),in.readLong()),in.readInt());
        return result;
    }
}
