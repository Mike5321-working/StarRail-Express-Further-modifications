package com.habitrain.core.game.sre.role;

import com.habitrain.core.game.sre.role.drone.DroneOperatorService;
import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Unified server-side body action lock and damage interrupt hooks shared across roles
 * that immobilize the player's body (Doll Tailor sewing cast, Drone Operator FPV link).
 */
public final class HabiActionLock {
    private HabiActionLock() {}

    public static boolean isMovementAndInteractionLocked(@Nullable Player player) {
        return player != null && (DollTailorService.isCasting(player) || DroneOperatorService.isLockedInFpv(player));
    }

    public static boolean isContainerAndVehicleLocked(@Nullable Player player) {
        return player != null && DroneOperatorService.isLockedInFpv(player);
    }

    public static boolean isSkillLocked(@Nullable ServerPlayer player) {
        return player != null && (DollTailorService.isCasting(player) || DroneOperatorService.isLockedInFpv(player));
    }

    public static void onPlayerDamaged(ServerPlayer player) {
        DollTailorService.interrupt(player);
        DroneOperatorService.onOperatorDamaged(player);
    }
}
