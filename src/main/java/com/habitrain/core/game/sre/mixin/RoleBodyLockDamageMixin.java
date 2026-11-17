package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiActionLock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class RoleBodyLockDamageMixin {
    @Inject(method = "hurt", at = @At("RETURN"))
    private void habitrain$interruptRoleLockOnDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && amount > 0.0F && (Object) this instanceof ServerPlayer player) {
            HabiActionLock.onPlayerDamaged(player);
        }
    }
}
