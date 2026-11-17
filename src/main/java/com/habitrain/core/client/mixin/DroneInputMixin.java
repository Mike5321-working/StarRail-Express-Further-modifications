package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes local player body movement input while piloting the recon drone;
 * {@link DroneOperatorClient} reads raw {@code KeyMapping} states directly to fly the drone.
 */
@Mixin(KeyboardInput.class)
public abstract class DroneInputMixin extends Input {
    @Inject(method = "tick", at = @At("RETURN"))
    private void habitrain$lockBodyInputDuringFpv(CallbackInfo ci) {
        if (!DroneOperatorClient.isPilotingFpv()) {
            return;
        }
        leftImpulse = 0.0F;
        forwardImpulse = 0.0F;
        jumping = false;
        shiftKeyDown = false;
        up = false;
        down = false;
        left = false;
        right = false;
    }
}
