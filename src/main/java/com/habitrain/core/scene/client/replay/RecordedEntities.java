package com.habitrain.core.scene.client.replay;

import com.habitrain.core.network.replay.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.*;

final class RecordedEntities {
    private static final class Track {
        final NavigableMap<Integer,WorldEntityFrame> poses=new TreeMap<>();
        final NavigableMap<Integer,VisualTape.Event> looks=new TreeMap<>();
        VisualTape.Event current;
        Entity entity;
    }
    private final Map<UUID,Track> tracks=new HashMap<>();
    private final NavigableMap<Integer,Set<UUID>> transitions=new TreeMap<>();
    private final Set<UUID> visible=new HashSet<>();
    private int previous=-1;
    RecordedEntities(List<VisualTape.Event> events) throws IOException {
        for (var event:events) {
            if(event.kind()!=VisualTape.ENTITY_POSE && event.kind()!=VisualTape.ENTITY_APPEARANCE) continue;
            var in=event.input(); var id=CorpseAppearance.uuid(in);
            if(tracks.size()>=2048 && !tracks.containsKey(id)) throw new IOException("Too many recorded world entities");
            var track=tracks.computeIfAbsent(id,ignored->new Track());
            if(event.kind()==VisualTape.ENTITY_APPEARANCE) track.looks.put(event.tick(),event);
            else {
                var pose=WorldEntityFrame.read(in);
                if(track.poses.isEmpty() || (track.poses.lastEntry().getValue()==null)!=(pose==null)) transitions.computeIfAbsent(event.tick(),ignored->new HashSet<>()).add(id);
                track.poses.put(event.tick(),pose);
            }
        }
    }
    boolean contains(UUID id) { return tracks.containsKey(id); }
    private static void discard(ClientLevel level,Track track) {
        if(track.entity!=null) level.removeEntity(track.entity.getId(),Entity.RemovalReason.DISCARDED);
        track.entity=null; track.current=null;
    }
    void draw(ClientLevel level,double time) {
        int tick=(int)time;
        if(previous>tick) { for(var id:visible) discard(level,tracks.get(id)); visible.clear(); previous=-1; }
        for(var group:transitions.subMap(previous,false,tick,true).values()) for(var id:group) {
            var track=tracks.get(id); var pose=track.poses.floorEntry(tick);
            if(pose!=null && pose.getValue()!=null) visible.add(id);
            else if(visible.remove(id)) discard(level,track);
        }
        previous=tick;
        for(var id:visible) {
            var track=tracks.get(id); var look=track.looks.floorEntry(tick); if(look==null) continue;
            var pose=track.poses.floorEntry(tick); var start=pose.getValue();
            if(track.current!=look.getValue()) {
                discard(level,track); track.current=look.getValue();
                var buffer=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(track.current.data()),level.registryAccess());
                try {
                    buffer.readUUID(); var key=ResourceLocation.parse(buffer.readUtf()); var type=BuiltInRegistries.ENTITY_TYPE.getOptional(key);
                    var entity=type.isEmpty()?null:type.get().create(level); if(entity==null) continue;
                    entity.setUUID(id); var packet=ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer); entity.getEntityData().assignValues(packet.packedItems());
                    if(entity instanceof LivingEntity living) for(var slot:EquipmentSlot.values()) living.setItemSlot(slot,ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
                    entity.moveTo(start.x(),start.y(),start.z(),start.yaw(),start.pitch());
                    if(entity instanceof Display display) display.tick();
                    level.addEntity(entity); track.entity=entity;
                } catch(RuntimeException unsupported) { org.slf4j.LoggerFactory.getLogger(RecordedEntities.class).debug("Unsupported replay entity {}",id,unsupported); }
                finally {buffer.release();}
            }
            var entity=track.entity; if(entity==null) continue;
            var next=track.poses.higherEntry(pose.getKey()); double x=start.x(),y=start.y(),z=start.z();
            if(next!=null && next.getValue()!=null && next.getKey()-pose.getKey()<=2) {
                var end=next.getValue(); double part=(time-pose.getKey())/(next.getKey()-pose.getKey());
                if(Math.abs(end.x()-x)+Math.abs(end.y()-y)+Math.abs(end.z()-z)<8) { x+=(end.x()-x)*part; y+=(end.y()-y)*part; z+=(end.z()-z)*part; }
            }
            entity.moveTo(x,y,z,start.yaw(),start.pitch()); entity.setOldPosAndRot(); entity.tickCount=(int)Math.clamp((long)tick-start.born(),0,Integer.MAX_VALUE);
            if(entity instanceof LivingEntity living) {living.yHeadRot=living.yHeadRotO=start.headYaw();living.yBodyRot=living.yBodyRotO=start.bodyYaw();}
        }
    }
}
