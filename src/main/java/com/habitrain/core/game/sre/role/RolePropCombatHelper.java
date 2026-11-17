package com.habitrain.core.game.sre.role;

import com.habitrain.core.game.sre.role.drone.DroneOperatorService;
import com.habitrain.core.game.sre.role.drone.ReconDroneEntity;
import com.habitrain.core.game.sre.role.sins.ServerAimTargeting;
import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Unified server-side weapon hit routing for {@link AttackableRoleProp} entities
 * ({@link ReconDroneEntity} and {@link TailorDollEntity}).
 */
public final class RolePropCombatHelper {
    private RolePropCombatHelper() {}

    public static boolean tryKnifeHit(ServerPlayer attacker, int targetEntityId) {
        Entity target = attacker.serverLevel().getEntity(targetEntityId);
        if (target instanceof ReconDroneEntity drone) {
            DroneOperatorService.knifeHit(attacker, drone);
            return true;
        }
        if (target instanceof TailorDollEntity doll) {
            DollTailorService.knifeHit(attacker, doll);
            return true;
        }
        return false;
    }

    public static void onGunHit(ServerPlayer attacker, int targetEntityId) {
        Entity target = attacker.serverLevel().getEntity(targetEntityId);
        if (target instanceof ReconDroneEntity) {
            DroneOperatorService.gunHit(attacker, targetEntityId);
        } else if (target instanceof TailorDollEntity) {
            DollTailorService.gunHit(attacker, targetEntityId);
        }
    }

    public static void onRangedHit(ServerPlayer attacker, int targetEntityId, double range) {
        Entity target = attacker.serverLevel().getEntity(targetEntityId);
        if (target instanceof ReconDroneEntity) {
            DroneOperatorService.rangedHit(attacker, targetEntityId, range);
        } else if (target instanceof TailorDollEntity) {
            DollTailorService.rangedHit(attacker, targetEntityId, range);
        }
    }

    public static @Nullable Entity onceRevolverTarget(ServerPlayer attacker) {
        if (!ServerAimTargeting.isAliveParticipant(attacker) || HabiActionLock.isMovementAndInteractionLocked(attacker)) {
            return null;
        }
        HitResult hit = ProjectileUtil.getHitResultOnViewVector(attacker, entity ->
                (entity instanceof ReconDroneEntity drone && drone.isAttackableProp() && DroneOperatorService.owns(drone))
                        || (entity instanceof TailorDollEntity doll && doll.complete() && DollTailorService.owns(doll))
                        || (entity instanceof ServerPlayer player && ServerAimTargeting.isAliveParticipant(player)), 15.0D);
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof AttackableRoleProp) {
            return entityHit.getEntity();
        }
        return null;
    }

    public static void destroyPropHitByOnceRevolver(@Nullable Entity target, LivingEntity shooter) {
        if (target instanceof ReconDroneEntity drone) {
            DroneOperatorService.destroyDrone(drone, true, shooter);
        } else if (target instanceof TailorDollEntity doll) {
            DollTailorService.destroy(doll, true);
        }
    }

    public static boolean tryBatHit(ServerPlayer attacker, Entity target) {
        if (target instanceof ReconDroneEntity drone) {
            DroneOperatorService.batHit(attacker, drone);
            return true;
        }
        if (target instanceof TailorDollEntity doll) {
            DollTailorService.batHit(attacker, doll);
            return true;
        }
        return false;
    }

    public static void onShortShotgunFanHit(ServerPlayer attacker, double killRange) {
        DroneOperatorService.shortShotgunFanHit(attacker, killRange);
        DollTailorService.shortShotgunFanHit(attacker, killRange);
    }
}
