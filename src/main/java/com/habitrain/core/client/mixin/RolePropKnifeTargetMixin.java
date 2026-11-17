package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.AttackableRoleProp;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.wifi.starrailexpress.content.item.KnifeItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Single client-side knife raycast covering all {@link AttackableRoleProp} entities. */
@Mixin(value = KnifeItem.class, remap = false, priority = 900)
public abstract class RolePropKnifeTargetMixin {
    @WrapMethod(method = "getKnifeTarget")
    private static HitResult habitrain$includeRoleProps(Player user, Operation<HitResult> call) {
        HitResult original = call.call(user);
        HitResult propHit = ProjectileUtil.getHitResultOnViewVector(user,
                e -> e instanceof AttackableRoleProp prop && prop.isAttackableRoleProp(), 4.0F);
        if (propHit instanceof EntityHitResult && (original == null || original.getType() == HitResult.Type.MISS
                || user.getEyePosition().distanceToSqr(propHit.getLocation())
                < user.getEyePosition().distanceToSqr(original.getLocation()))) {
            return propHit;
        }
        return original;
    }
}
