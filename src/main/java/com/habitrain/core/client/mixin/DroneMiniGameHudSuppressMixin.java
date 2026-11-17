package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import io.wifi.starrailexpress.client.gui.MiniGameHudRenderer;
import io.wifi.utils.client.betterrender.FakeGuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes the repair-mode minigame task bar ({@link MiniGameHudRenderer}) while
 * operating the drone in FPV.
 */
@Mixin(value = MiniGameHudRenderer.class, remap = false)
public abstract class DroneMiniGameHudSuppressMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$hideMiniGameTasksInDroneFpv(
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
