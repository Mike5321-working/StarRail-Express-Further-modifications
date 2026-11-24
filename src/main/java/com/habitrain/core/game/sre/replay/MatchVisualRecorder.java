package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.*;
import io.wifi.starrailexpress.cca.SREGameTimeComponent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.GameRules;
import net.minecraft.nbt.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import static com.habitrain.core.network.replay.VisualTape.*;

final class MatchVisualRecorder {
    final Builder tape = new Builder();
    final WorldEntityRecorder entities;
    private final Set<UUID> contributors = new HashSet<>();
    private final Map<UUID, Integer> lastFrames = new HashMap<>();
    private final Map<UUID,byte[]> bodies = new HashMap<>();
    private final BodyBuffer bodyBuffer = new BodyBuffer();
    private final Map<UUID,CorpseAppearance> corpses = new HashMap<>();
    private final Map<UUID,byte[]> fallbackCameras = new HashMap<>();
    private final Map<UUID,net.minecraft.world.item.ItemStack[]> equipment = new HashMap<>();
    private final Set<Packet<?>> shared = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Long> initialTiles = new HashSet<>();
    private final UUID round;
    private final SceneBounds bounds;
    private long lastGame, lastDay;
    private int lastTime, lastFlags, lastTick = -1, lastWeather;
    private int packetTick = -1;
    MatchVisualRecorder(UUID round, SceneBounds bounds) { this.round = round; this.bounds = bounds; this.entities=new WorldEntityRecorder(tape,bounds); }

    void clock(ServerLevel level, int tick) {
        var clock = SREGameTimeComponent.KEY.get(level);
        long day = level.getDayTime(), game = level.getGameTime();
        int flags = (clock.isTimeFrozen() ? 1 : 0) | (level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT) ? 2 : 0);
        int weather = Math.round(level.getRainLevel(1) * 255) << 8 | Math.round(level.getThunderLevel(1) * 255);
        int elapsed = tick - lastTick;
        if (lastTick < 0 || flags != lastFlags || weather != lastWeather || game != lastGame + elapsed || day != lastDay + ((flags & 2) == 0 ? 0 : elapsed) || clock.time != Math.max(0, lastTime - ((flags & 1) == 0 ? elapsed : 0))) {
            tape.append(WORLD, tick, CLOCK, bytes(out -> { out.writeLong(game); out.writeLong(day); out.writeInt(clock.time); out.writeByte(flags); out.writeShort(weather); }));
        }
        lastTick = tick; lastFlags = flags; lastDay = day; lastGame = game; lastTime = clock.time; lastWeather = weather;
    }
    void join(ServerPlayer player) {
        UUID id = player.getUUID();
        if (contributors.contains(id) || !ServerPlayNetworking.canSend(player, VisualPackets.Session.TYPE)) return;
        contributors.add(id);
        ServerPlayNetworking.send(player, new VisualPackets.Session(round, true));
    }
    void corpse(io.wifi.starrailexpress.content.entity.PlayerBodyEntity body, int tick) {
        var id=body.getUUID();
        if (body.isRemoved() || !bounds.contains(body.blockPosition())) { removeCorpse(id,tick); return; }
        var appearance=new CorpseAppearance(id,body.getPlayerUuid(),body.getComponent().getSkinUuid(),tick-body.tickCount,
                body.getX(),body.getY(),body.getZ(),body.getYRot(),body.getXRot(),body.yBodyRot,body.yHeadRot,
                body.getScale(),body.isInvisible(),body.isCorrupted());
        if (!appearance.equals(corpses.put(id,appearance))) tape.append(WORLD,tick,CORPSE,appearance.encode());
    }
    void removeCorpse(UUID id,int tick) {
        if (corpses.remove(id)!=null) tape.append(WORLD,tick,CORPSE,CorpseAppearance.removal(id));
    }
    void body(ServerPlayer player, int tick) {
        UUID id = player.getUUID();
        if (tick%2 == 0 && (player.isSpectator() || !contributors.contains(id) || !lastFrames.containsKey(id))) {
            var camera = player.getCamera(); var eye = camera.getEyePosition();
            var sample = new VisualFrames.Camera(eye.x,eye.y,eye.z,camera.getYRot(),camera.getXRot(),70,player.isSpectator(),false).encode();
            if (!Arrays.equals(sample,fallbackCameras.get(id))) { fallbackCameras.put(id,sample); tape.append(id,tick,SERVER_CAMERA,sample); }
        }
        bodyBuffer.writeState(player, tick);
        if (!bodyBuffer.matches(bodies.get(id))) {
            byte[] state = bodyBuffer.toByteArray();
            bodies.put(id, state);
            tape.append(id, tick, BODY, state);
        }
        var main = player.getMainHandItem(); var off = player.getOffhandItem(); var old = equipment.get(id);
        if (old != null && net.minecraft.world.item.ItemStack.matches(main,old[0]) && net.minecraft.world.item.ItemStack.matches(off,old[1])) return;
        equipment.put(id,new net.minecraft.world.item.ItemStack[]{main.copy(),off.copy()});
        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
        try {
            for (var hand : List.of(main,off)) {
                var stack = hand.copy();
                stack.remove(net.minecraft.core.component.DataComponents.CONTAINER); stack.remove(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
                stack.remove(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT); stack.remove(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT);
                stack.remove(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA); stack.remove(net.minecraft.core.component.DataComponents.ENTITY_DATA);
                net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(buf,stack);
            }
            if (buf.readableBytes() <= MAX_EVENT) { byte[] data = new byte[buf.readableBytes()]; buf.readBytes(data); tape.append(id,tick,ITEMS,data); }
        } finally { buf.release(); }
    }

    private static final class BodyBuffer extends ByteArrayOutputStream {
        private final DataOutputStream out = new DataOutputStream(this);

        void writeState(ServerPlayer player, int tick) {
            reset();
            try {
                out.writeFloat(player.getHealth());
                out.writeByte(player.hurtTime);
                out.writeByte(player.hurtDuration);
                out.writeShort(player.deathTime);
                out.writeBoolean(player.isSpectator());
                out.writeBoolean(player.isOnFire());
                out.writeShort(Math.clamp(player.getTicksFrozen(), 0, 32767));
                var effects = player.getActiveEffects();
                out.writeByte(Math.min(128, effects.size()));
                int count = 0;
                for (var effect : effects) {
                    if (++count > 128) break;
                    out.writeUTF(effect.getEffect().unwrapKey().orElseThrow().location().toString());
                    out.writeByte(effect.getAmplifier());
                    out.writeInt(effect.isInfiniteDuration() ? -1 : tick + effect.getDuration());
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        boolean matches(byte[] previous) {
            return previous != null && Arrays.equals(buf, 0, count, previous, 0, previous.length);
        }
    }
    void leave(ServerPlayer player, int tick) {
        if (!contributors.remove(player.getUUID())) return;
        lastFrames.remove(player.getUUID()); tape.append(player.getUUID(), tick, END_VIEW, new byte[0]);
        if (ServerPlayNetworking.canSend(player, VisualPackets.Session.TYPE)) ServerPlayNetworking.send(player, new VisualPackets.Session(round, false));
    }
    void stop(ServerLevel level, int tick) {
        for (var player : level.players()) if (contributors.contains(player.getUUID()) && ServerPlayNetworking.canSend(player, VisualPackets.Session.TYPE)) ServerPlayNetworking.send(player, new VisualPackets.Session(round, false));
        contributors.clear();
    }
    void frame(ServerPlayer player, int tick, VisualPackets.Frame frame) {
        var id = player.getUUID();
        if (!round.equals(frame.round()) || !contributors.contains(id)) return;
        if (frame.camera().length == 0 && frame.hud().length == 0 && frame.outlines().length == 0) {
            if (lastFrames.remove(id) != null) tape.append(id,tick,END_VIEW,new byte[0]);
            return;
        }
        if (Objects.equals(lastFrames.put(id, tick), tick)) return;
        if (!VisualFrames.valid(frame.camera(), frame.hud())) return;
        if (frame.camera().length > 0) tape.append(id, tick, CAMERA, frame.camera());
        if (frame.hud().length > 0) tape.append(id, tick, HUD, frame.hud());
        if (frame.outlines().length >= 2 && frame.outlines().length == 2 + 20 * ((frame.outlines()[0]&255)*256+(frame.outlines()[1]&255))) tape.append(id,tick,OUTLINES,frame.outlines());
    }
    void initial(LevelChunk chunk, int section, int tick) {
        for (var entry : chunk.getBlockEntities().entrySet()) {
            var pos = entry.getKey();
            if ((pos.getY() >> 4) == section && bounds.contains(pos) && initialTiles.add(pos.asLong())) {
                var entity = entry.getValue(); tile(tick, pos, entity, entity.getUpdateTag(chunk.getLevel().registryAccess()));
            }
        }
    }
    private void tile(int tick, BlockPos pos, BlockEntity entity, CompoundTag tag) {
        if (tag == null || tag.isEmpty() || tag.sizeInBytes() > MAX_EVENT) return;
        tape.append(WORLD, tick, TILE, bytes(out -> { out.writeLong(pos.asLong()); out.writeUTF(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString()); NbtIo.write(tag, out); }));
    }
    void packet(ServerPlayer player, Packet<?> packet, int tick) {
        if (packet instanceof ClientboundSetEntityDataPacket update) { entities.dirty(update.id()); return; }
        if (packet instanceof ClientboundSetEquipmentPacket update) { entities.dirty(update.getEntity()); return; }
        if (packetTick != tick) { packetTick = tick; shared.clear(); }
        if (packet instanceof ClientboundBlockEventPacket event && bounds.contains(event.getPos()) && shared.add(packet)) {
            tape.append(WORLD, tick, BLOCK, bytes(out -> { out.writeLong(event.getPos().asLong()); out.writeUTF(BuiltInRegistries.BLOCK.getKey(event.getBlock()).toString()); out.writeInt(event.getB0()); out.writeInt(event.getB1()); }));
        } else if (packet instanceof ClientboundBlockEntityDataPacket update && bounds.contains(update.getPos()) && shared.add(packet)) {
            var entity = player.level().getBlockEntity(update.getPos());
            if (entity != null && entity.getType() == update.getType()) { initialTiles.add(update.getPos().asLong()); tile(tick, update.getPos(), entity, update.getTag()); }
        } else if (packet instanceof ClientboundLevelEventPacket event && contributors.contains(player.getUUID()) && bounds.contains(event.getPos())) {
            tape.append(player.getUUID(),tick,LEVEL_EVENT,bytes(out -> { out.writeLong(event.getPos().asLong()); out.writeInt(event.getType()); out.writeInt(event.getData()); }));
        } else if (packet instanceof ClientboundLevelParticlesPacket particles && contributors.contains(player.getUUID()) && bounds.contains(BlockPos.containing(particles.getX(), particles.getY(), particles.getZ()))) {
            var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), player.registryAccess());
            try { ClientboundLevelParticlesPacket.STREAM_CODEC.encode(buf, particles); if (buf.readableBytes() <= MAX_EVENT) { byte[] data = new byte[buf.readableBytes()]; buf.readBytes(data); tape.append(player.getUUID(), tick, PARTICLES, data); } }
            finally { buf.release(); }
        }
    }
}
