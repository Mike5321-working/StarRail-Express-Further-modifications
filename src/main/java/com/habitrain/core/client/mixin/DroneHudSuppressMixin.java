package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes vanilla & SRE bottom hotbar/taskbar, item name popups, status bars,
 * status effect icons, and default crosshair while operating the drone in FPV.
 */
@Mixin(Gui.class)
public abstract class DroneHudSuppressMixin {

    @Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
    private void habitrain$hideHotbarInDroneFpv(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (DroneOperatorClient.isCameraControlledByDrone()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void habitrain$hideCrosshairInDroneFpv(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (DroneOperatorClient.isCameraControlledByDrone()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void habitrain$hideEffectsInDroneFpv(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (DroneOperatorClient.isCameraControlledByDrone()) {
            ci.cancel();
        }
    }
}
