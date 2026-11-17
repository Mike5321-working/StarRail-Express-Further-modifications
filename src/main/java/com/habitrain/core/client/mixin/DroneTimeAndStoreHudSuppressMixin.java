package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import io.wifi.starrailexpress.client.gui.HudStoreRenderer;
import io.wifi.starrailexpress.client.gui.TimeRenderer;
import io.wifi.utils.client.betterrender.FakeGuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the top-center SRE countdown timer ({@link TimeRenderer}) and top-right
 * store balance ({@link HudStoreRenderer}) while operating the drone in FPV so
 * the UAV tactical compass and cockpit telemetry render without collision.
 */
@Mixin(value = {TimeRenderer.class, HudStoreRenderer.class}, remap = false)
public abstract class DroneTimeAndStoreHudSuppressMixin {

    @Inject(method = "renderHud", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$hideTimerAndCoinsInDroneFpv(
            Font renderer,
            LocalPlayer player,
            FakeGuiGraphics context,
            float delta,
            CallbackInfo ci
    ) {
        if (DroneOperatorClient.isCameraControlledByDrone()) {
            ci.cancel();
        }
    }
}
