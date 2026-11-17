package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiActionLock;
import com.habitrain.core.game.sre.role.RolePropCombatHelper;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.agmas.noellesroles.content.item.ZeroOneFiveShootPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ZeroOneFiveShootPayload.Receiver.class, remap = false)
public abstract class RolePropZeroOneFiveMixin {
    @Inject(method = "receive(Lorg/agmas/noellesroles/content/item/ZeroOneFiveShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$lock015Shot(ZeroOneFiveShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (HabiActionLock.isMovementAndInteractionLocked(context.player())) {
            ci.cancel();
        }
    }

    @Inject(method = "receive(Lorg/agmas/noellesroles/content/item/ZeroOneFiveShootPayload;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V",
            at = @At("TAIL"))
    private void habitrain$shootRoleProp015(ZeroOneFiveShootPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        RolePropCombatHelper.onRangedHit(context.player(), payload.target(), 30.0D);
    }
}
