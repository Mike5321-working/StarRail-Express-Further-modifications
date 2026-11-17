package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shields mouse button clicks and scroll-wheel hotbar changes at high priority ({@code 400})
 * while piloting the recon drone in FPV, routing discrete clicks exclusively to
 * {@link DroneOperatorClient} and preventing hotbar slot desync.
 */
@Mixin(value = MouseHandler.class, priority = 400)
public abstract class DroneMouseHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract boolean isMouseGrabbed();

    @Shadow
    public abstract void grabMouse();

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void habitrain$shieldMouseButtonsInDroneFpv(
            long window,
            int button,
            int action,
            int mods,
            CallbackInfo ci
    ) {
        if (window != this.minecraft.getWindow().getWindow()) {
            return;
        }
        if (this.minecraft.screen == null && DroneOperatorClient.isPilotingFpv()) {
            if (!this.isMouseGrabbed()) {
                if (action == GLFW.GLFW_PRESS && this.minecraft.isWindowActive()) {
                    DroneOperatorClient.latchCurrentButtonsBeforeRefocus();
                    this.grabMouse();
                }
                ci.cancel();
                return;
            }
            DroneOperatorClient.onRawMouseButton(button, action);
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void habitrain$blockHotbarScrollInDroneFpv(
            long window,
            double xOffset,
            double yOffset,
            CallbackInfo ci
    ) {
        if (window == this.minecraft.getWindow().getWindow()
                && this.minecraft.screen == null
                && DroneOperatorClient.isPilotingFpv()) {
            ci.cancel();
        }
    }
}
