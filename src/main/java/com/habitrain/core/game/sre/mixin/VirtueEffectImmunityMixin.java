package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.modifier.virtue.ChastityVirtue;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects newly applied ordinary debuffs before their first tick. */
@Mixin(LivingEntity.class)
public abstract class VirtueEffectImmunityMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$rejectEffectFromEntity(MobEffectInstance effect, Entity source,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player) || effect == null) return;
        var holder = effect.getEffect();
        if (GluttonyComponent.isOrdinaryDebuff(holder) && GluttonyComponent.isGluttony(player)) {
            cir.setReturnValue(false);
            return;
        }
        if (ChastityVirtue.isRegisteredPoison(holder) && ChastityVirtue.hasChastity(player)) {
            cir.setReturnValue(false);
        }
    }
}
