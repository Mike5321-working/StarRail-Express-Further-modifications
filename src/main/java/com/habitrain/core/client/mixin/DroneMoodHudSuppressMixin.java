package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import io.wifi.starrailexpress.client.gui.HudMoodRenderer;
import io.wifi.utils.client.betterrender.FakeGuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes the player's top-left mood & task bar ({@link HudMoodRenderer}) while
 * operating the drone in FPV.
 */
@Mixin(value = HudMoodRenderer.class, remap = false)
public abstract class DroneMoodHudSuppressMixin {

    @Inject(method = "renderHud", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$hidePlayerTasksInDroneFpv(
            Player player,
            Font textRenderer,
            FakeGuiGraphics context,
            DeltaTracker tickCounter,
            CallbackInfo ci
    ) {
        if (DroneOperatorClient.isCameraControlledByDrone()) {
            ci.cancel();
        }
    }
}
