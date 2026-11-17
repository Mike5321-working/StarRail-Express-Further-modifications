package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses single-hand item rendering while the Drone Operator is in the
 * two-handed half-squat controller pose ({@code pose == 1}) or magnetic
 * battery swap pose ({@code pose == 2}), preventing duplicate hand items.
 */
@Mixin(PlayerItemInHandLayer.class)
public abstract class DroneHeldItemLayerMixin {

    @Inject(
            method = "renderArmWithItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void habitrain$hideSingleHandItemDuringTwoHandedDronePose(
            LivingEntity entity,
            ItemStack itemStack,
            ItemDisplayContext displayContext,
            HumanoidArm arm,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        if (entity instanceof Player player) {
            int pose = DroneOperatorClient.getThirdPersonOperatorPose(player);
            if (pose == DroneOperatorClient.POSE_PILOTING
                    || pose == DroneOperatorClient.POSE_BATTERY_SWAP
                    || pose == DroneOperatorClient.POSE_UNBOX) {
                ci.cancel();
            }
        }
    }
}
