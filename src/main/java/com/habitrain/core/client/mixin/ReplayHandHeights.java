package com.habitrain.core.client.mixin;

import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemInHandRenderer.class)
public interface ReplayHandHeights {
    @Accessor("oMainHandHeight") float habitrain$oldMain();
    @Accessor("mainHandHeight") float habitrain$main();
    @Accessor("oOffHandHeight") float habitrain$oldOff();
    @Accessor("offHandHeight") float habitrain$off();
    @Accessor("oMainHandHeight") void habitrain$oldMain(float value);
    @Accessor("mainHandHeight") void habitrain$main(float value);
    @Accessor("oOffHandHeight") void habitrain$oldOff(float value);
    @Accessor("offHandHeight") void habitrain$off(float value);
}
