package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiWorldEntrySession;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the real 2D gameplay HUD while the opaque world-entry tunnel or opening
 * Apple Portal Iris covers the screen, revealing the real HUD cleanly at {@code t = 1.06s}
 * underneath the fading warm golden bloom.
 */
@Environment(EnvType.CLIENT)
@Mixin(Gui.class)
public abstract class WorldEntryGuiMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$suppressHudDuringWorldEntry(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (HabiWorldEntrySession.getInstance().shouldSuppressInGameHud()) {
            ci.cancel();
        }
    }
}
