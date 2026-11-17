package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enables through-wall glowing outlines for players and corpses marked by
 * the drone's battery-powered pulse scan (and shared killer highlights).
 */
@Mixin(value = Minecraft.class, priority = 910)
public abstract class DroneGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
    private void habitrain$droneScanGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (DroneOperatorClient.scanOutlineColor(entity).isPresent()) {
            cir.setReturnValue(true);
        }
    }
}
