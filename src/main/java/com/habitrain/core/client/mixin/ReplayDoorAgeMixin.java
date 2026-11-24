package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.content.block_entity.DoorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=DoorBlockEntity.class, remap=false)
public interface ReplayDoorAgeMixin {
    @Accessor("age") void habitrain$visualAge(int age);
}
