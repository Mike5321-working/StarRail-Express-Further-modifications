package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts raw GLFW keyboard callbacks at high priority ({@code 400}) while piloting the
 * recon drone in FPV so non-drone keys (skills, inventory, chat, perspective toggle, zoom, etc.)
 * never propagate to vanilla or other mods, while preserving {@code ESC} / {@code F2} / {@code F11}.
 */
@Mixin(value = KeyboardHandler.class, priority = 400)
public abstract class DroneKeyboardHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void habitrain$shieldKeyboardInDroneFpv(
            long window,
            int key,
            int scancode,
            int action,
            int mods,
            CallbackInfo ci
    ) {
        if (window != this.minecraft.getWindow().getWindow()) {
            return;
        }
        DroneOperatorClient.onRawKeyEvent(key, scancode, action);
        if (this.minecraft.screen == null
                && DroneOperatorClient.isPilotingFpv()
                && !DroneOperatorClient.isSystemBypassKey(key)) {
            ci.cancel();
        }
    }
}
