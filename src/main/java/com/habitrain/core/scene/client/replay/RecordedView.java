package com.habitrain.core.scene.client.replay;

import com.habitrain.core.network.replay.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.client.particle.ParticleEngine;
import java.io.*;
import java.util.*;

final class RecordedView {
    private final NavigableMap<Integer,VisualFrames.Camera> cameras = new TreeMap<>();
    private final NavigableMap<Integer,VisualFrames.Camera> fallbackCameras = new TreeMap<>();
    private final NavigableMap<Integer,VisualTape.Event> displays = new TreeMap<>();
    private final NavigableMap<Integer,List<VisualTape.Event>> particles = new TreeMap<>();
    private final NavigableMap<Integer,VisualTape.Event> clocks = new TreeMap<>();
    private final Map<UUID,NavigableMap<Integer,CorpseAppearance>> corpses = new HashMap<>();
    boolean tracksCorpse(UUID id) { return corpses.containsKey(id); }
    CorpseAppearance corpse(UUID id,int tick) {
        var timeline=corpses.get(id); var entry=timeline==null?null:timeline.floorEntry(tick);
        return entry==null?null:entry.getValue();
    }
    private final NavigableMap<Integer,VisualTape.Event> bodies = new TreeMap<>(), items = new TreeMap<>();
    private VisualTape.Event equipment;
    private final NavigableMap<Integer,VisualTape.Event> colors = new TreeMap<>();
    private VisualTape.Event previousColors;
    private Map<UUID,Integer> outlines = Map.of();
    Map<UUID,Integer> outlines(int tick) throws IOException {
        var floor=colors.floorEntry(tick); var event=floor==null?null:floor.getValue();
        if(event!=previousColors){ previousColors=event; outlines=event==null || event.kind()==VisualTape.END_VIEW?Map.of():VisualFrames.outlines(event.input()); }
        return outlines;
    }
    private net.minecraft.world.item.ItemStack main = net.minecraft.world.item.ItemStack.EMPTY, off = net.minecraft.world.item.ItemStack.EMPTY;
    private final NavigableSet<Integer> hudKeys = new TreeSet<>();
    private byte[] rawHud = new byte[0];
    private int previousHud = -1;
    private VisualFrames.Hud hud;
    private int particleTick = -1;
    RecordedView(List<VisualTape.Event> world, List<VisualTape.Event> personal) throws IOException {
        for (var event : world) {
            if (event.kind() == VisualTape.CLOCK) clocks.put(event.tick(), event);
            if (event.kind() == VisualTape.CORPSE) {
                var input=event.input(); var id=CorpseAppearance.uuid(input);
                corpses.computeIfAbsent(id,ignored -> new TreeMap<>()).put(event.tick(),CorpseAppearance.read(id,input));
            }
        }
        for (var event : personal) switch (event.kind()) {
            case VisualTape.CAMERA -> cameras.put(event.tick(), VisualFrames.Camera.read(event.input()));
            case VisualTape.SERVER_CAMERA -> fallbackCameras.put(event.tick(), VisualFrames.Camera.read(event.input()));
            case VisualTape.HUD, VisualTape.HUD_DIFF -> { displays.put(event.tick(),event); if (event.kind() == VisualTape.HUD) hudKeys.add(event.tick()); }
            case VisualTape.END_VIEW -> { cameras.put(event.tick(),null); displays.put(event.tick(),event); colors.put(event.tick(),event); hudKeys.add(event.tick()); }
            case VisualTape.OUTLINES -> colors.put(event.tick(),event);
            case VisualTape.PARTICLES, VisualTape.LEVEL_EVENT -> particles.computeIfAbsent(event.tick(), ignored -> new ArrayList<>()).add(event);
            case VisualTape.BODY -> bodies.put(event.tick(),event);
            case VisualTape.ITEMS -> items.put(event.tick(),event);
        }
    }
    boolean body(net.minecraft.client.player.LocalPlayer player, int tick, boolean spectator) throws IOException {
        var hand = items.floorEntry(tick);
        if (hand != null) {
            if (equipment != hand.getValue()) {
                equipment = hand.getValue(); var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(equipment.data()),player.registryAccess());
                try { main = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer); off = net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer); } finally { buffer.release(); }
            }
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,main); player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,off);
        }
        var floor = bodies.floorEntry(tick);
        if (floor == null) return spectator;
        var in = floor.getValue().input(); player.setHealth(in.readFloat()); player.hurtTime=in.readUnsignedByte(); player.hurtDuration=in.readUnsignedByte(); player.deathTime=in.readUnsignedShort();
        spectator = in.readBoolean(); player.setRemainingFireTicks(in.readBoolean()?20:0); player.setTicksFrozen(in.readUnsignedShort());
        player.getActiveEffectsMap().clear();
        int count = in.readUnsignedByte();
        for (int index=0; index<count; index++) {
            var holder = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(net.minecraft.resources.ResourceLocation.parse(in.readUTF()));
            int amplifier=in.readUnsignedByte(), end=in.readInt();
            if (holder.isPresent() && (end < 0 || end > tick)) player.getActiveEffectsMap().put(holder.get(),new net.minecraft.world.effect.MobEffectInstance(holder.get(),end<0?-1:end-tick,amplifier));
        }
        return spectator;
    }
    VisualFrames.Camera camera(double tick) {
        var left = cameras.floorEntry((int)tick);
        var source=cameras;
        if (left == null || left.getValue() == null) { source=fallbackCameras; left=source.floorEntry((int)tick); }
        if (left == null || left.getValue() == null) return null;
        var start = left.getValue(); var right = source.higherEntry(left.getKey());
        if (right == null || right.getValue() == null || right.getKey()-left.getKey() > 4) return start;
        var end = right.getValue(); double part = (tick-left.getKey())/(right.getKey()-left.getKey());
        double dx=end.x()-start.x(), dy=end.y()-start.y(), dz=end.z()-start.z();
        if (dx*dx+dy*dy+dz*dz > 64 || end.spectator()!=start.spectator()) return start;
        return new VisualFrames.Camera(start.x()+dx*part,start.y()+dy*part,start.z()+dz*part,start.yaw()+(float)part*net.minecraft.util.Mth.wrapDegrees(end.yaw()-start.yaw()),start.pitch()+(float)part*(end.pitch()-start.pitch()),start.fov(),start.spectator(),start.hideGui(),start.filters(),start.hands());
    }
    VisualFrames.Hud hud(int tick) throws IOException {
        var key = hudKeys.floor(tick);
        if (key == null) { hud=null; previousHud=-1; return null; }
        if (tick < previousHud || previousHud < key) previousHud = key-1;
        var changes = displays.subMap(previousHud,false,tick,true);
        for (var event : changes.values()) rawHud = switch(event.kind()) {
            case VisualTape.HUD -> event.data(); case VisualTape.HUD_DIFF -> VisualTape.applyDifference(rawHud,event.input()); default -> new byte[0];
        };
        if (!changes.isEmpty()) hud = rawHud.length == 0 ? null : VisualFrames.Hud.read(new DataInputStream(new ByteArrayInputStream(rawHud)));
        previousHud = tick;
        return hud;
    }
    void time(ClientLevel level, int tick) throws IOException {
        var entry = clocks.floorEntry(tick);
        if (entry == null) { level.setGameTime(tick); return; }
        int elapsed = tick-entry.getKey(); var in = entry.getValue().input();
        long game = in.readLong(), day = in.readLong(); int time = in.readInt(), flags = in.readUnsignedByte(), weather = in.readUnsignedShort();
        level.setGameTime(game+elapsed); level.setDayTime(day+((flags & 2) == 0 ? 0 : elapsed));
        level.setRainLevel((weather>>8)/255f); level.setThunderLevel((weather&255)/255f); level.updateSkyBrightness();
        var component = io.wifi.starrailexpress.cca.SREGameTimeComponent.KEY.get(level);
        component.timeFrozen = (flags & 1) != 0; component.time = Math.max(0, time-(component.timeFrozen ? 0 : elapsed));
    }
    void particles(ClientLevel level, ParticleEngine engine, int tick) throws IOException {
        if (particleTick == tick) return;
        if (particleTick < 0 || particleTick > tick || tick - particleTick > 40) { ((ReplayParticleState.Exchange)engine).habitrain$clearVisuals(); particleTick = Math.max(-1,tick-40); }
        while (particleTick < tick) {
            particleTick++; engine.tick();
            for (var event : particles.getOrDefault(particleTick,List.of())) {
                if (event.kind() == VisualTape.LEVEL_EVENT) {
                    var in = event.input(); var pos = net.minecraft.core.BlockPos.of(in.readLong()); int type = in.readInt(), data = in.readInt();
                    if (type >= 2000 && type < 4000) level.levelEvent(null,type,pos,data);
                    continue;
                }
                var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(event.data()),level.registryAccess());
                try {
                    var packet = ClientboundLevelParticlesPacket.STREAM_CODEC.decode(buf);
                    var random = new Random(31L*particleTick + Arrays.hashCode(event.data()));
                    for (int index=0; index<Math.min(4096,Math.max(1,packet.getCount())); index++) {
                        boolean single = packet.getCount()==0;
                        engine.createParticle(packet.getParticle(), packet.getX()+(single?0:random.nextGaussian()*packet.getXDist()), packet.getY()+(single?0:random.nextGaussian()*packet.getYDist()), packet.getZ()+(single?0:random.nextGaussian()*packet.getZDist()), (single?packet.getXDist():random.nextGaussian())*packet.getMaxSpeed(), (single?packet.getYDist():random.nextGaussian())*packet.getMaxSpeed(), (single?packet.getZDist():random.nextGaussian())*packet.getMaxSpeed());
                    }
                } finally { buf.release(); }
            }
        }
    }
}
