package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiActionLock;
import com.habitrain.core.game.sre.role.RolePropCombatHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.agmas.noellesroles.content.item.ShortShotgunItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShortShotgunItem.class)
public abstract class RolePropShortShotgunMixin {
    @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
    private void habitrain$lockShotgunWhileImmobilized(ItemStack stack, Level world, LivingEntity user, int remainingUseTicks, CallbackInfo ci) {
        if (!world.isClientSide && user instanceof ServerPlayer sp && HabiActionLock.isMovementAndInteractionLocked(sp)) {
            ci.cancel();
        }
    }

    // Only executes after ShortShotgunItem.releaseUsing has passed all server/alive/charge guards and fires the shot.
    @Inject(method = "releaseUsing", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"
    ))
    private void habitrain$fanHitRoleProp(ItemStack stack, Level world, LivingEntity user, int remainingUseTicks, CallbackInfo ci) {
        if (user instanceof ServerPlayer sp) {
            int chargeTicks = 72000 - remainingUseTicks;
            double range = Math.min(4.0D, 2.0D + 2.0D * Math.min(1.0D, Math.max(0.0D, (chargeTicks - 4) / 36.0D)));
            RolePropCombatHelper.onShortShotgunFanHit(sp, range);
        }
    }
}
