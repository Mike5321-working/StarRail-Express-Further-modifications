package com.habitrain.core.client.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiGraphics.class)
public interface ReplayHudTextureAccessor {
    @Invoker("innerBlit") void habitrain$drawTexture(ResourceLocation texture, int left, int right, int top, int bottom, int depth, float u, float uEnd, float v, float vEnd, float red, float green, float blue, float alpha);
}
