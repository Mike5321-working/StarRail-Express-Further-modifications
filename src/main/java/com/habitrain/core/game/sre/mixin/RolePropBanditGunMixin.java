package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiActionLock;
import com.habitrain.core.game.sre.role.RolePropCombatHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.agmas.noellesroles.packet.BanditRevolverShootPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BanditRevolverShootPayload.class, remap = false)
public abstract class RolePropBanditGunMixin {
    @Inject(method = "extracted", at = @At("HEAD"), cancellable = true)
    private static void habitrain$lockBanditShot(ServerPlayer player, Entity target, CallbackInfo ci) {
        if (HabiActionLock.isMovementAndInteractionLocked(player)) {
            ci.cancel();
        }
    }

    @Inject(method = "extracted", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V",
            ordinal = 0, remap = true))
    private static void habitrain$hitRolePropBandit(ServerPlayer player, Entity target, CallbackInfo ci) {
        if (target != null) {
            RolePropCombatHelper.onGunHit(player, target.getId());
        }
    }
}
