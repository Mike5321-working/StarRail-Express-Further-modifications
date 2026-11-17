package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiActionLock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Unified server-side packet lock for immobilized role states (Doll Tailor sewing, Drone Operator FPV). */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RoleBodyLockMovementMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = {"handleMovePlayer", "handlePlayerAction", "handleUseItem", "handleUseItemOn", "handleInteract"},
            at = @At("HEAD"), cancellable = true)
    private void habitrain$lockBodyMovementAndUse(CallbackInfo ci) {
        if (player.server.isSameThread() && HabiActionLock.isMovementAndInteractionLocked(player)) {
            ci.cancel();
        }
    }

    @Inject(method = {"handleContainerClick", "handleContainerButtonClick", "handleSetCarriedItem", "handleMoveVehicle"},
            at = @At("HEAD"), cancellable = true)
    private void habitrain$lockContainerAndVehicle(CallbackInfo ci) {
        if (player.server.isSameThread() && HabiActionLock.isContainerAndVehicleLocked(player)) {
            ci.cancel();
        }
    }
}
