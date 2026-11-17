package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.AttackableRoleProp;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.wifi.starrailexpress.content.item.RevolverItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Single client-side gun raycast covering all {@link AttackableRoleProp} entities. */
@Mixin(value = {
        RevolverItem.class,
        org.agmas.noellesroles.content.item.BanditRevolverItem.class,
        org.agmas.noellesroles.content.item.OnceRevolverItem.class,
        org.agmas.noellesroles.content.item.ZeroOneFiveGunItem.class
}, remap = false, priority = 900)
public abstract class RolePropGunTargetMixin {
    @WrapMethod(method = "getGunTarget")
    private static HitResult habitrain$includeRoleProps(Player user, Operation<HitResult> call) {
        HitResult original = call.call(user);
        var held = user.getMainHandItem();
        double range = held.is(org.agmas.noellesroles.init.ModItems.ZERO_ONE_FIVE_GUN) ? 30.0D
                : held.is(org.agmas.noellesroles.init.ModItems.ONCE_REVOLVER) ? 15.0D : 20.0D;
        HitResult propHit = ProjectileUtil.getHitResultOnViewVector(user,
                e -> e instanceof AttackableRoleProp prop && prop.isAttackableRoleProp(), range);
        if (propHit instanceof EntityHitResult && (original == null || original.getType() == HitResult.Type.MISS
                || user.getEyePosition().distanceToSqr(propHit.getLocation())
                < user.getEyePosition().distanceToSqr(original.getLocation()))) {
            return propHit;
        }
        return original;
    }
}
