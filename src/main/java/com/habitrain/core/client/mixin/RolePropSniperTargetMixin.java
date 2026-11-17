package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.AttackableRoleProp;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.wifi.starrailexpress.content.item.SniperRifleItem;
import io.wifi.starrailexpress.util.SniperProjectileUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Single client-side sniper raycast covering all {@link AttackableRoleProp} entities. */
@Mixin(value = SniperRifleItem.class, remap = false, priority = 900)
public abstract class RolePropSniperTargetMixin {
    @WrapMethod(method = "getGunTarget")
    private static HitResult habitrain$includeRoleProps(Player player, Operation<HitResult> call) {
        HitResult original = call.call(player);
        HitResult propHit = SniperProjectileUtil.getSniperHitResult(player,
                entity -> entity instanceof AttackableRoleProp prop && prop.isAttackableRoleProp(), 200.0F);
        if (propHit instanceof EntityHitResult && (original == null || original.getType() == HitResult.Type.MISS
                || player.getEyePosition().distanceToSqr(propHit.getLocation())
                < player.getEyePosition().distanceToSqr(original.getLocation()))) {
            return propHit;
        }
        return original;
    }
}
