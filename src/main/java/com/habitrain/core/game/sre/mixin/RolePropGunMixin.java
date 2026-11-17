package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiActionLock;
import com.habitrain.core.game.sre.role.RolePropCombatHelper;
import io.wifi.starrailexpress.network.original.GunShootPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GunShootPayload.Receiver.class, remap = false)
public abstract class RolePropGunMixin {
    @Inject(method = "receive(Lio/wifi/starrailexpress/network/original/GunShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$blockLockedShot(GunShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (HabiActionLock.isMovementAndInteractionLocked(context.player())) {
            ci.cancel();
        }
    }

    @Inject(method = "receive(Lio/wifi/starrailexpress/network/original/GunShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V",
            at = @At("TAIL"))
    private void habitrain$shootRoleProp(GunShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        RolePropCombatHelper.onGunHit(context.player(), payload.target());
    }
}
