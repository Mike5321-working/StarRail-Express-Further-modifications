package com.habitrain.core.client.drone;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

/**
 * Renders the 3D Twin-Stick Drone Controller centered between both hands during
 * the Drone Operator's half-squat piloting pose ({@code pose == 1}), and renders
 * the 3D High-Energy Battery Pack during magnetic battery swap ({@code pose == 2}).
 */
@Environment(EnvType.CLIENT)
public final class DroneOperatorEquipmentLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    public DroneOperatorEquipmentLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer) {
        super(renderer);
    }

    @Override
    public void render(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            AbstractClientPlayer player,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        if (player.isInvisible() || player.isSpectator()) {
            return;
        }
        int pose = DroneOperatorClient.getThirdPersonOperatorPose(player);
        if (pose == DroneOperatorClient.POSE_PILOTING) {
            DroneEquipmentModels.renderTwoHandedOperatorController(poseStack, buffer, packedLight, ageInTicks);
        } else if (pose == DroneOperatorClient.POSE_BATTERY_SWAP) {
            DroneEquipmentModels.renderSwappingBatteryInHand(poseStack, buffer, packedLight, ageInTicks);
        } else if (pose == DroneOperatorClient.POSE_UNBOX) {
            float prog = DroneOperatorClient.getThirdPersonUnboxProgress(player, partialTicks);
            if (prog >= 0.0F) {
                DroneEquipmentModels.renderThirdPersonUnboxDrone(poseStack, buffer, packedLight, prog, ageInTicks);
            }
        }
    }
}
