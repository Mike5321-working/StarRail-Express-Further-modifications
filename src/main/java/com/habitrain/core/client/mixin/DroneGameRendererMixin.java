package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies quadcopter strafe banking (±6° roll), sprint FOV boost, suppresses
 * player walk-bobbing, and hides 1P held hands while piloting via FPV link.
 */
@Mixin(GameRenderer.class)
public abstract class DroneGameRendererMixin {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void habitrain$applyDroneRoll(PoseStack poseStack, float partialTicks, CallbackInfo ci) {
        if (!DroneOperatorClient.isCameraControlledByDrone()) {
            return;
        }
        float roll = DroneOperatorClient.cameraRollDegrees();
        if (Math.abs(roll) > 0.01F) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
        }
        ci.cancel();
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void habitrain$suppressDroneBobView(PoseStack poseStack, float partialTicks, CallbackInfo ci) {
        if (DroneOperatorClient.isCameraControlledByDrone()) {
            ci.cancel();
        }
    }

    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private double habitrain$boostDroneSprintFov(double original, Camera camera, float partialTicks, boolean useFovSetting) {
        if (useFovSetting && DroneOperatorClient.isPilotingFpv()) {
            return original * DroneOperatorClient.fovMultiplier();
        }
        return original;
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void habitrain$hideFirstPersonHandInFpv(Camera camera, float partialTick, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (DroneOperatorClient.isCameraControlledByDrone()) {
            ci.cancel();
        }
    }
}
