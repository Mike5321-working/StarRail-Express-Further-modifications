package com.habitrain.core.network.replay;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import java.util.UUID;

public final class VisualPackets {
    private VisualPackets() {}
    public record Session(UUID round, boolean active) implements CustomPacketPayload {
        public static final Type<Session> TYPE = new Type<>(HabiTrainCore.id("visual_session"));
        public Type<Session> type() { return TYPE; }
    }
    public record Frame(UUID round, byte[] camera, byte[] hud, byte[] outlines) implements CustomPacketPayload {
        public static final Type<Frame> TYPE = new Type<>(HabiTrainCore.id("visual_frame"));
        public Type<Frame> type() { return TYPE; }
    }
    public record Request(UUID round, UUID subject, int offset) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(HabiTrainCore.id("visual_request"));
        public Type<Request> type() { return TYPE; }
    }
    public record Page(UUID round, UUID subject, int offset, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<Page> TYPE = new Type<>(HabiTrainCore.id("visual_page"));
        public Type<Page> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.playS2C().register(Session.TYPE, StreamCodec.ofMember((p,b) -> { b.writeUUID(p.round); b.writeBoolean(p.active); }, b -> new Session(b.readUUID(), b.readBoolean())));
        PayloadTypeRegistry.playC2S().register(Frame.TYPE, StreamCodec.ofMember((p,b) -> { b.writeUUID(p.round); b.writeByteArray(p.camera); b.writeByteArray(p.hud); b.writeByteArray(p.outlines); }, b -> new Frame(b.readUUID(), b.readByteArray(64), b.readByteArray(VisualTape.MAX_EVENT), b.readByteArray(2+128*20))));
        PayloadTypeRegistry.playC2S().register(Request.TYPE, StreamCodec.ofMember((p,b) -> { b.writeUUID(p.round); b.writeUUID(p.subject); b.writeInt(p.offset); }, b -> new Request(b.readUUID(), b.readUUID(), b.readInt())));
        PayloadTypeRegistry.playS2C().register(Page.TYPE, StreamCodec.ofMember((p,b) -> { b.writeUUID(p.round); b.writeUUID(p.subject); b.writeInt(p.offset); b.writeInt(p.total); b.writeByteArray(p.data); }, b -> new Page(b.readUUID(), b.readUUID(), b.readInt(), b.readInt(), b.readByteArray(VisualTape.PAGE))));
    }
}
