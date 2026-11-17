package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Suppresses direct {@link InputConstants#isKeyDown(long, int)} polling by external client mods
 * while piloting the recon drone in FPV (with no GUI screen open). {@link DroneOperatorClient}
 * queries GLFW directly, so only drone hotkeys and essential system keys remain active.
 */
@Mixin(value = InputConstants.class, priority = 400)
public abstract class DroneInputConstantsMixin {
    @ModifyReturnValue(method = "isKeyDown", at = @At("RETURN"))
    private static boolean habitrain$suppressExternalRawKeyPollingInDroneFpv(
            boolean original,
            long window,
            int key
    ) {
        if (original
                && DroneOperatorClient.shouldSuppressVanillaKeyMapping()
                && !DroneOperatorClient.isSystemBypassKey(key)) {
            return false;
        }
        return original;
    }
}
