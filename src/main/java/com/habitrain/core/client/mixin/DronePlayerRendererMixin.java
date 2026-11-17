package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorEquipmentLayer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches {@link DroneOperatorEquipmentLayer} to {@link PlayerRenderer} so the
 * half-squat operator visibly holds and operates the 3D twin-stick controller.
 */
@Mixin(PlayerRenderer.class)
public abstract class DronePlayerRendererMixin extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    protected DronePlayerRendererMixin(EntityRendererProvider.Context context, PlayerModel<AbstractClientPlayer> model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;Z)V", at = @At("TAIL"))
    private void habitrain$addDroneOperatorLayer(EntityRendererProvider.Context context, boolean useSlimModel, CallbackInfo ci) {
        this.addLayer(new DroneOperatorEquipmentLayer(this));
    }
}
