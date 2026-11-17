package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import io.wifi.utils.client.betterrender.FakeGuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.agmas.noellesroles.client.hud.CommonClientHudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides top-right auxiliary info/credit lines while operating the drone in FPV.
 */
@Mixin(value = CommonClientHudRenderer.class, remap = false)
public abstract class DroneCommonHudSuppressMixin {

    @Inject(method = "renderMessagesBelowMoney", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$hideInfoLinesInDroneFpv(
            Minecraft client,
            FakeGuiGraphics guiGraphics,
            DeltaTracker deltaTracker,
            boolean inventory,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!inventory && DroneOperatorClient.isCameraControlledByDrone()) {
            cir.setReturnValue(CommonClientHudRenderer.INFO_LINES_TOP_Y);
        }
    }
}
