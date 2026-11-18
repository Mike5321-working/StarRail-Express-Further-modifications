package com.habitrain.core.network.replay;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The same bounded wire format is used in both the archive and the local match cache. */
public final class ReplayArchiveData {
    private ReplayArchiveData() {}

    public static byte[] encode(ReplayPackets.LastMatchSummaryS2C packet) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ReplayPackets.LastMatchSummaryS2C.CODEC.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    public static ReplayPackets.LastMatchSummaryS2C decode(byte[] bytes) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try { return ReplayPackets.LastMatchSummaryS2C.CODEC.decode(buffer); }
        finally { buffer.release(); }
    }

    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
