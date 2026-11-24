package com.habitrain.core.client.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Level.class)
public interface ReplayBlockTickAccessor {
    @Invoker("tickBlockEntities") void habitrain$tickVisualBlocks();
}
