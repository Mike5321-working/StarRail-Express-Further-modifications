package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.RolePropCombatHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.agmas.noellesroles.content.item.OnceRevolverItem;
import org.agmas.noellesroles.init.ModItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = OnceRevolverItem.class, remap = false)
public abstract class RolePropOnceRevolverMixin {
    // The server has accepted use and the role's onUseGun, but hasn't consumed the one shot yet.
    @WrapOperation(method = "use", at = @At(value = "INVOKE", ordinal = 1, remap = true, target =
            "Lnet/minecraft/world/item/ItemStack;hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V"))
    private void habitrain$onceRevolverRolePropHit(ItemStack stack, int cost, LivingEntity user, EquipmentSlot slot,
                                                   Operation<Void> original, Level level, Player player,
                                                   InteractionHand hand) {
        Entity target = null;
        if (user instanceof ServerPlayer shooter && hand == InteractionHand.MAIN_HAND
                && stack.is(ModItems.ONCE_REVOLVER) && stack.getDamageValue() < stack.getMaxDamage()
                && !shooter.getCooldowns().isOnCooldown(ModItems.ONCE_REVOLVER)) {
            target = RolePropCombatHelper.onceRevolverTarget(shooter);
        }
        original.call(stack, cost, user, slot);
        if (target != null) {
            RolePropCombatHelper.destroyPropHitByOnceRevolver(target, user);
        }
    }
}
