package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneEquipmentModels;
import com.habitrain.core.client.drone.DroneOperatorClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the two-handed 1st-person "Taking Out &amp; Unfolding New Recon Drone"
 * animation right after the Drone Operator purchases a whole-unit resupply in the shop.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class DroneFirstPersonUnboxMixin {

    @Shadow
    protected abstract void renderPlayerArm(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int combinedLight,
            float equippedProgress,
            float swingProgress,
            HumanoidArm side
    );

    @Inject(
            method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void habitrain$renderDroneResupplyUnboxAnimation(
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            LocalPlayer player,
            int combinedLight,
            CallbackInfo ci
    ) {
        if (DroneOperatorClient.isCameraControlledByDrone()) {
            return;
        }
        float progress = DroneOperatorClient.getFirstPersonUnboxProgress(partialTicks);
        if (progress < 0.0F) {
            return;
        }

        float timeSec = (DroneOperatorClient.clientTicks() + partialTicks) / 20.0F;
        DroneEquipmentModels.renderFirstPersonDroneUnbox(
                poseStack,
                bufferSource,
                combinedLight,
                progress,
                timeSec,
                (armPose, side, equipProg) -> this.renderPlayerArm(armPose, bufferSource, combinedLight, equipProg, 0.0F, side)
        );
        bufferSource.endBatch();
        ci.cancel();
    }
}
