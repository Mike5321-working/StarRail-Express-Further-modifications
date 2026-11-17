package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneEquipmentModels;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla 2D item sprites with custom 3D procedural hardware models
 * for the Drone Operator's Tactical FPV Terminal, High-Energy Spare Battery,
 * and Whole-Unit Resupply crate across GUI, 1P, 3P, ground, and frame contexts.
 */
@Mixin(ItemRenderer.class)
public abstract class DroneItemRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void habitrain$renderCustom3DDroneGear(
            ItemStack itemStack,
            ItemDisplayContext displayContext,
            boolean leftHand,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int combinedLight,
            int combinedOverlay,
            BakedModel model,
            CallbackInfo ci
    ) {
        if (DroneEquipmentModels.renderCustomDroneItem(
                itemStack, displayContext, leftHand, poseStack, buffer, combinedLight)) {
            ci.cancel();
        }
    }
}
