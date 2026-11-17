package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Applies classified RGB outline colors for drone pulse scan targets and routes
 * local mouse movement to the FPV gimbal instead of turning the player's body.
 */
@Mixin(Entity.class)
public abstract class DroneTurnAndOutlineMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void habitrain$droneScanTeamColor(CallbackInfoReturnable<Integer> cir) {
        OptionalInt color = DroneOperatorClient.scanOutlineColor((Entity) (Object) this);
        if (color.isPresent()) {
            cir.setReturnValue(color.getAsInt());
        }
    }

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void habitrain$redirectMouseTurnToDrone(double yaw, double pitch, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player && DroneOperatorClient.isPilotingFpv()) {
            DroneOperatorClient.onMouseTurn(yaw, pitch);
            ci.cancel();
        }
    }
}
