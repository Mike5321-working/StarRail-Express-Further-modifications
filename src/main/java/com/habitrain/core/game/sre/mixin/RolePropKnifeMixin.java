package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiActionLock;
import com.habitrain.core.game.sre.role.RolePropCombatHelper;
import io.wifi.starrailexpress.network.original.KnifeStabPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KnifeStabPayload.Receiver.class, remap = false)
public abstract class RolePropKnifeMixin {
    @Inject(method = "receive(Lio/wifi/starrailexpress/network/original/KnifeStabPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$stabRoleProp(KnifeStabPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (HabiActionLock.isMovementAndInteractionLocked(context.player())) {
            ci.cancel();
            return;
        }
        if (RolePropCombatHelper.tryKnifeHit(context.player(), payload.target())) {
            ci.cancel();
        }
    }
}
