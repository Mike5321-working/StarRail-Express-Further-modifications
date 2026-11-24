package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.*;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import java.util.*;
import static net.minecraft.network.syncher.EntityDataSerializers.*;
import static com.habitrain.core.network.replay.VisualTape.*;

final class WorldEntityRecorder {
    private static final Set<EntityDataSerializer<?>> VISUAL=Set.of(BYTE,INT,LONG,FLOAT,BOOLEAN,BLOCK_STATE,OPTIONAL_BLOCK_STATE,
            ROTATIONS,DIRECTION,POSE,VECTOR3,QUATERNION,CAT_VARIANT,WOLF_VARIANT,FROG_VARIANT,PAINTING_VARIANT,ARMADILLO_STATE,SNIFFER_STATE,VILLAGER_DATA);
    private final Builder tape;
    private final SceneBounds bounds;
    private final AABB box;
    private final Map<Integer,Entry> active=new HashMap<>();
    private final Set<UUID> known=new HashSet<>();
    private static final class Entry {
        final Entity entity;
        WorldEntityFrame previous;
        byte[] appearance;
        boolean dirty=true;
        Entry(Entity entity) { this.entity=entity; }
    }
    WorldEntityRecorder(Builder tape,SceneBounds bounds) {
        this.tape=tape; this.bounds=bounds; box=new AABB(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX()+1,bounds.maxY()+1,bounds.maxZ()+1);
    }
    void observe(Entity entity,int tick) {
        if (!tape.writable(WORLD) || !eligible(entity) || !bounds.contains(entity.blockPosition()) || active.containsKey(entity.getId())) return;
        if (active.size()>=512 || (known.size()>=2048 && !known.contains(entity.getUUID()))) { tape.markLimited(); return; }
        known.add(entity.getUUID());
        var entry=new Entry(entity); active.put(entity.getId(),entry); sample(entry,tick);
    }
    private static boolean eligible(Entity entity) { return !(entity instanceof Player || entity instanceof PlayerBodyEntity || entity.getType()==EntityType.MARKER || entity.getType()==EntityType.INTERACTION); }
    void dirty(int runtimeId) { var entry=active.get(runtimeId); if(entry!=null) entry.dirty=true; }
    void remove(Entity entity,int tick) {
        var entry=active.get(entity.getId());
        if (entry!=null && entry.entity==entity) { active.remove(entity.getId()); tape.append(WORLD,tick,ENTITY_POSE,CorpseAppearance.removal(entity.getUUID())); }
    }
    void tick(ServerLevel level,int tick) {
        if (!tape.writable(WORLD)) return;
        if (tick%40==0) for(var entity:level.getEntities((Entity)null,box,WorldEntityRecorder::eligible)) observe(entity,tick);
        for(var it=active.values().iterator();it.hasNext();) {
            var entry=it.next(); var entity=entry.entity;
            if(entity.isRemoved() || !bounds.contains(entity.blockPosition())) { it.remove(); tape.append(WORLD,tick,ENTITY_POSE,CorpseAppearance.removal(entity.getUUID())); }
            else if(tick%2==0 || entry.dirty) sample(entry,tick);
        }
    }
    private void sample(Entry entry,int tick) {
        var entity=entry.entity; var id=entity.getUUID();
        float bodyYaw=entity instanceof LivingEntity living?living.yBodyRot:entity.getYRot();
        var pose=new WorldEntityFrame(entity.getX(),entity.getY(),entity.getZ(),entity.getYRot(),entity.getXRot(),bodyYaw,entity.getYHeadRot(),tick-entity.tickCount);
        if(!pose.equals(entry.previous)) { tape.append(WORLD,tick,ENTITY_POSE,pose.encode(id)); entry.previous=pose; }
        if (!entry.dirty) return;
        entry.dirty=false;
        var buf=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),entity.registryAccess());
        try {
            buf.writeUUID(id); buf.writeUtf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            var values=new ArrayList<SynchedEntityData.DataValue<?>>(); var raw=entity.getEntityData().getNonDefaultValues();
            if(raw!=null) for(var value:raw) {
                if(VISUAL.contains(value.serializer())) values.add(value);
                else if(value.serializer()==ITEM_STACK) values.add(new SynchedEntityData.DataValue<>(value.id(),ITEM_STACK,visibleStack((ItemStack)value.value())));
                else if(entity instanceof Display.TextDisplay && value.serializer()==COMPONENT) {
                    String text=((net.minecraft.network.chat.Component)value.value()).getString(512);
                    values.add(new SynchedEntityData.DataValue<>(value.id(),COMPONENT,net.minecraft.network.chat.Component.literal(text)));
                }
            }
            ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf,new ClientboundSetEntityDataPacket(0,values));
            if(entity instanceof LivingEntity living) for(var slot:EquipmentSlot.values()) ItemStack.OPTIONAL_STREAM_CODEC.encode(buf,visibleStack(living.getItemBySlot(slot)));
            if(buf.readableBytes()>MAX_EVENT) { tape.markLimited(); return; }
            byte[] data=new byte[buf.readableBytes()]; buf.readBytes(data);
            if(!Arrays.equals(data,entry.appearance)) {entry.appearance=data;tape.append(WORLD,tick,ENTITY_APPEARANCE,data);}
        } finally {buf.release();}
    }
    private static ItemStack visibleStack(ItemStack stack) {
        if(stack.isEmpty()) return ItemStack.EMPTY;
        var result=new ItemStack(stack.getItem(),stack.getCount());
        copy(stack,result,DataComponents.CUSTOM_MODEL_DATA); copy(stack,result,DataComponents.DYED_COLOR); copy(stack,result,DataComponents.TRIM);
        copy(stack,result,DataComponents.DAMAGE); copy(stack,result,DataComponents.POTION_CONTENTS); copy(stack,result,DataComponents.MAP_COLOR);
        copy(stack,result,DataComponents.BASE_COLOR); copy(stack,result,DataComponents.BANNER_PATTERNS);
        result.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,stack.hasFoil());
        return result;
    }
    private static <T> void copy(ItemStack source,ItemStack result,DataComponentType<T> key) { var value=source.get(key); if(value!=null) result.set(key,value); }
}
