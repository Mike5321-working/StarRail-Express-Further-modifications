package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import com.habitrain.core.game.sre.role.drone.ReconDroneEntity;
import com.habitrain.core.network.DroneStatePayload;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smoothly transitions and locks the client camera onto the quadcopter's nose
 * gimbal during deploy (0.5s), active FPV flight, and recall (0.3s), while
 * setting {@code detached = true} so the operator's own 3P body stays visible.
 */
@Mixin(Camera.class)
public abstract class DroneCameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void habitrain$setupDroneCamera(
            BlockGetter area,
            Entity focusedEntity,
            boolean thirdPerson,
            boolean inverseView,
            float partialTick,
            CallbackInfo ci
    ) {
        if (focusedEntity == null || !DroneOperatorClient.isCameraControlledByDrone()) {
            return;
        }
        ReconDroneEntity drone = DroneOperatorClient.activeDroneEntity();
        if (drone == null) {
            return;
        }

        this.detached = true;

        Vec3 playerEye = focusedEntity.getEyePosition(partialTick);
        float playerYaw = focusedEntity.getViewYRot(partialTick);
        float playerPitch = focusedEntity.getViewXRot(partialTick);

        float targetYaw;
        float targetPitch;
        if (drone.chargeTicks() > 0) {
            targetYaw = drone.getViewYRot(partialTick);
            targetPitch = drone.getViewXRot(partialTick);
        } else if (DroneOperatorClient.isPilotingFpv()) {
            targetYaw = DroneOperatorClient.fpvYaw();
            targetPitch = DroneOperatorClient.fpvPitch();
        } else {
            targetYaw = drone.getViewYRot(partialTick);
            targetPitch = drone.getViewXRot(partialTick);
        }
        targetPitch = Mth.clamp(
                targetPitch - DroneOperatorClient.getDroneRecoilPitch(drone.getId(), partialTick) * 9.0F,
                -89.0F,
                89.0F
        );

        Vec3 droneCenter = drone.getPosition(partialTick).add(0.0D, 0.18D, 0.0D);
        Vec3 noseDir = Vec3.directionFromRotation(targetPitch, targetYaw);
        Vec3 droneCamPos = droneCenter.add(noseDir.scale(0.16D));

        byte tm = DroneOperatorClient.state().transitionMode();
        int tt = DroneOperatorClient.state().transitionTicks();

        if (tm == DroneStatePayload.TRANSITION_DEPLOYING) {
            float progress = Mth.clamp(1.0F - (tt - partialTick) / (float) DroneRules.DEPLOY_TICKS, 0.0F, 1.0F);
            float eased = progress * progress * (3.0F - 2.0F * progress);
            Vec3 pos = playerEye.lerp(droneCamPos, eased);
            setRotation(Mth.rotLerp(eased, playerYaw, targetYaw), Mth.lerp(eased, playerPitch, targetPitch));
            setPosition(pos.x, pos.y, pos.z);
        } else if (tm == DroneStatePayload.TRANSITION_RECALLING) {
            float progress = Mth.clamp((tt - partialTick) / (float) DroneRules.RECALL_TICKS, 0.0F, 1.0F);
            float eased = progress * progress * (3.0F - 2.0F * progress);
            Vec3 pos = playerEye.lerp(droneCamPos, eased);
            setRotation(Mth.rotLerp(eased, playerYaw, targetYaw), Mth.lerp(eased, playerPitch, targetPitch));
            setPosition(pos.x, pos.y, pos.z);
        } else {
            setRotation(targetYaw, targetPitch);
            setPosition(droneCamPos.x, droneCamPos.y, droneCamPos.z);
        }
    }
}
