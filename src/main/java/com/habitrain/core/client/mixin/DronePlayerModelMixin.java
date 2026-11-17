package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders full-body third-person poses for the Drone Operator:
 * <ul>
 *   <li>{@code 0} — One-arm upward tactical toss on deploy</li>
 *   <li>{@code 1} — Grounded tactical half-squat gripping & operating the 3D twin-stick controller</li>
 *   <li>{@code 2} — Two-handed magnetic battery swap motion</li>
 *   <li>{@code 3} — Forward magnetic catch on recall</li>
 *   <li>{@code 4} — Two-handed resupply take-out &amp; arm-unfold ceremony</li>
 * </ul>
 */
@Mixin(PlayerModel.class)
public abstract class DronePlayerModelMixin<T extends LivingEntity> extends HumanoidModel<T> {

    @Unique
    private boolean habitrain$hadCustomPivots;

    protected DronePlayerModelMixin(ModelPart root) {
        super(root);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void habitrain$resetCustomDronePivots(
            T entity,
            float walk,
            float speed,
            float age,
            float yaw,
            float pitch,
            CallbackInfo ci
    ) {
        if (!habitrain$hadCustomPivots) {
            return;
        }
        habitrain$hadCustomPivots = false;
        head.y = 0.0F;
        body.y = 0.0F;
        rightArm.y = 2.0F;
        leftArm.y = 2.0F;
        rightLeg.y = 12.0F;
        leftLeg.y = 12.0F;
        head.z = 0.0F;
        body.z = 0.0F;
        rightArm.z = 0.0F;
        leftArm.z = 0.0F;
        rightLeg.z = 0.0F;
        leftLeg.z = 0.0F;
        body.yRot = 0.0F;
        body.zRot = 0.0F;
        leftLeg.yRot = 0.0F;
        leftLeg.zRot = 0.0F;
        rightLeg.yRot = 0.0F;
        rightLeg.zRot = 0.0F;
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void habitrain$droneOperatorPoses(
            T entity,
            float walk,
            float speed,
            float age,
            float yaw,
            float pitch,
            CallbackInfo ci
    ) {
        if (!(entity instanceof Player player)) {
            return;
        }
        int pose = DroneOperatorClient.getThirdPersonOperatorPose(player);
        if (pose == DroneOperatorClient.POSE_NONE) {
            return;
        }

        switch (pose) {
            case DroneOperatorClient.POSE_DEPLOY -> {
                // Deploy toss: right arm sweeps upward at 45°, head tracks launch trajectory
                head.xRot = -0.35F;
                rightArm.xRot = -2.05F;
                rightArm.yRot = -0.15F;
                rightArm.zRot = 0.12F;
                leftArm.xRot = -0.65F;
                leftArm.yRot = 0.25F;
            }
            case DroneOperatorClient.POSE_PILOTING -> {
                // Tactical Half-Squat Operating Controller:
                // Lowered center of gravity, forward-pitched torso, split bent-knee stance planted on floor,
                // bowed head watching the FPV OLED screen, and both hands working the twin gimbal sticks.
                habitrain$hadCustomPivots = true;

                head.y = 4.8F;
                head.z = -1.8F;
                head.xRot = 0.56F;
                head.yRot = Mth.sin(age * 0.14F) * 0.05F;
                head.zRot = 0.0F;

                body.y = 4.6F;
                body.z = -1.8F;
                body.xRot = 0.54F;
                body.yRot = 0.0F;
                body.zRot = 0.0F;

                float stickR = Mth.sin(age * 0.52F) * 0.045F;
                float stickL = Mth.cos(age * 0.44F) * 0.045F;
                rightArm.y = 6.2F;
                rightArm.z = -1.2F;
                rightArm.xRot = -0.96F + stickR;
                rightArm.yRot = -0.38F;
                rightArm.zRot = 0.06F;

                leftArm.y = 6.2F;
                leftArm.z = -1.2F;
                leftArm.xRot = -0.96F + stickL;
                leftArm.yRot = 0.38F;
                leftArm.zRot = -0.06F;

                // Asymmetric tactical half-squat legs (hip y=13.6 + 12*cos(0.52) = 24.01 -> exact ground contact)
                leftLeg.y = 13.6F;
                leftLeg.z = 3.2F;
                leftLeg.xRot = -0.52F;
                leftLeg.yRot = -0.15F;
                leftLeg.zRot = -0.03F;

                rightLeg.y = 13.6F;
                rightLeg.z = 4.2F;
                rightLeg.xRot = 0.52F;
                rightLeg.yRot = 0.15F;
                rightLeg.zRot = 0.03F;
            }
            case DroneOperatorClient.POSE_BATTERY_SWAP -> {
                // Magnetic hover battery swap: slight crouch + rhythmic pull-out & snap-in gesture
                habitrain$hadCustomPivots = true;
                head.y = 1.6F;
                head.xRot = 0.18F;
                body.y = 1.6F;
                body.xRot = 0.20F;
                float swapWave = Mth.sin(age * 0.85F) * 0.24F;
                rightArm.y = 3.4F;
                leftArm.y = 3.4F;
                rightArm.xRot = -1.42F + swapWave;
                leftArm.xRot = -1.35F - swapWave * 0.5F;
                rightArm.yRot = -0.28F;
                leftArm.yRot = 0.28F;
                leftLeg.y = 12.4F;
                leftLeg.z = 1.8F;
                leftLeg.xRot = -0.18F;
                rightLeg.y = 12.4F;
                rightLeg.z = 2.2F;
                rightLeg.xRot = 0.18F;
            }
            case DroneOperatorClient.POSE_RECALL -> {
                // Recall catch: right arm extended forward to catch folding frame
                head.xRot = 0.08F;
                rightArm.xRot = -1.55F;
                rightArm.yRot = -0.18F;
                leftArm.xRot = -0.85F;
                leftArm.yRot = 0.22F;
            }
            case DroneOperatorClient.POSE_UNBOX -> {
                // Resupply take-out & unfold: operator pulls new folded unit up with both hands and snaps X-arms open
                float p = Mth.clamp(DroneOperatorClient.getThirdPersonUnboxProgress(player, 0.0F), 0.0F, 1.0F);
                float lift = p < 0.25F ? (p / 0.25F) : (p > 0.75F ? (1.0F - (p - 0.75F) / 0.25F) : 1.0F);
                float snapPulse = (p >= 0.28F && p <= 0.56F)
                        ? Mth.sin(((p - 0.28F) / 0.28F) * (float) Math.PI) * 0.14F
                        : 0.0F;
                head.xRot = Mth.lerp(lift, 0.10F, 0.30F);
                rightArm.xRot = Mth.lerp(lift, -0.55F, -1.24F) - snapPulse;
                leftArm.xRot = Mth.lerp(lift, -0.55F, -1.24F) - snapPulse;
                rightArm.yRot = Mth.lerp(lift, -0.12F, -0.32F) + snapPulse * 0.6F;
                leftArm.yRot = Mth.lerp(lift, 0.12F, 0.32F) - snapPulse * 0.6F;
                rightArm.zRot = 0.06F;
                leftArm.zRot = -0.06F;
            }
            default -> {}
        }

        PlayerModel<?> model = (PlayerModel<?>) (Object) this;
        model.hat.copyFrom(head);
        model.jacket.copyFrom(body);
        model.rightSleeve.copyFrom(rightArm);
        model.leftSleeve.copyFrom(leftArm);
        model.rightPants.copyFrom(rightLeg);
        model.leftPants.copyFrom(leftLeg);
    }
}
