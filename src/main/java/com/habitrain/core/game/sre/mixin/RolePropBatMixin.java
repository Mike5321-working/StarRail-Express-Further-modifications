package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.RolePropCombatHelper;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Run the prop branch before SRE's cancellable bat attack at the same injection point.
@Mixin(value = ServerPlayer.class, priority = 900)
public abstract class RolePropBatMixin {
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void habitrain$hitRolePropWithBat(Entity target, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (player.getMainHandItem().is(TMMItems.BAT) && RolePropCombatHelper.tryBatHit(player, target)) {
            ci.cancel();
        }
    }
}
