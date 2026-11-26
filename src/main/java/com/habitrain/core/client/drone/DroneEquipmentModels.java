package com.habitrain.core.client.drone;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import com.habitrain.core.network.DroneStatePayload;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * High-precision procedural 3D models, dynamic 128x128 hardware textures, and first/third-person
 * animations for the Drone Operator's dedicated equipment:
 * <ul>
 *   <li><b>Tactical FPV Terminal ({@code 战术图传终端})</b>: 4x integer-voxel twin-grip carbon/gunmetal
 *       RC flight deck, sculpted ergonomic rubberized handles with palm swells, 4-wall recessed metallic
 *       screen bezel, live state-aware fullbright OLED telemetry display (standby / ready / active FPV),
 *       machined gimbal sockets with animated crowned thumbsticks, rotary gain dial, guarded master-arm
 *       switch, shoulder triggers, rear heatsink fins, lanyard loop, foldable 3-panel sun-hood visor,
 *       and articulated dual high-gain patch V-antennas;</li>
 *   <li><b>High-Energy Spare Battery ({@code 高能备用电池})</b>: 2x integer-voxel armored LiPo cell with
 *       titanium shock rails, brass contact pins, quick-latch handle, and glowing plasma charge bars;</li>
 *   <li><b>Resupply Drone Unbox Animation ({@code 拿出无人机})</b>: 1st-person two-handed &amp; 3rd-person
 *       choreographed pull-out, mechanical 4-arm snap-unfold, optical gimbal self-test, and rotor spin-up.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class DroneEquipmentModels {

    @FunctionalInterface
    public interface FirstPersonArmDrawer {
        void drawArm(PoseStack pose, HumanoidArm arm, float equipProgress);
    }

    private static ResourceLocation controllerTexture;
    private static ResourceLocation batteryTexture;

    private static ControllerRig controllerRig;
    private static BatteryRig batteryRig;
    private static ReconDroneModel miniDroneModel;

    private DroneEquipmentModels() {}

    /**
     * Authored at 4x integer voxel scale (scaled by {@code 0.25F} at render time) so every
     * {@code ModelPart.Cube} has integer dimensions {@code >= 1} and crisp, non-truncated UVs
     * on a {@code 128x128} procedural hardware texture.
     */
    private static final class ControllerRig {
        final ModelPart root;
        final ModelPart deck;
        final ModelPart stickLeft;
        final ModelPart stickRight;
        final ModelPart antennaLeft;
        final ModelPart antennaRight;
        final ModelPart visor;

        ControllerRig() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition r = mesh.getRoot();

            // Convention (4 units = 1 model unit): -Y = UP (screen side), -Z = FRONT (away from operator)
            PartDefinition d = r.addOrReplaceChild("deck",
                    CubeListBuilder.create()
                            // Central unibody carbon-titanium console chassis
                            .texOffs(0, 0).addBox(-14.0F, -4.0F, -10.0F, 28.0F, 7.0F, 19.0F)
                            // Upper chamfered carbon deck plate
                            .texOffs(0, 28).addBox(-12.0F, -5.0F, -9.0F, 24.0F, 2.0F, 17.0F)
                            // 4-wall raised brushed-metal bezel framing the recessed OLED bay (-9..+9 X, -7..+4 Z)
                            .texOffs(0, 48).addBox(-11.0F, -6.0F, -9.0F, 22.0F, 2.0F, 2.0F)
                            .texOffs(0, 48).addBox(-11.0F, -6.0F, 4.0F, 22.0F, 2.0F, 2.0F)
                            .texOffs(50, 48).addBox(-11.0F, -6.0F, -7.0F, 2.0F, 2.0F, 11.0F)
                            .texOffs(50, 48).addBox(9.0F, -6.0F, -7.0F, 2.0F, 2.0F, 11.0F)
                            // Recessed dark OLED glass bed (top sits at y = -5.0)
                            .texOffs(0, 54).addBox(-9.0F, -5.0F, -7.0F, 18.0F, 1.0F, 11.0F)
                            // Contoured left & right rubberized ergonomic handgrips + lower palm swells
                            .texOffs(0, 68).addBox(-19.0F, -3.0F, -9.0F, 6.0F, 8.0F, 20.0F)
                            .texOffs(0, 98).addBox(-18.0F, 4.0F, -6.0F, 5.0F, 3.0F, 15.0F)
                            .texOffs(54, 68).addBox(13.0F, -3.0F, -9.0F, 6.0F, 8.0F, 20.0F)
                            .texOffs(42, 98).addBox(13.0F, 4.0F, -6.0F, 5.0F, 3.0F, 15.0F)
                            // Front RF transmitter heatsink module + index-finger shoulder triggers
                            .texOffs(82, 28).addBox(-9.0F, -3.0F, -13.0F, 18.0F, 5.0F, 3.0F)
                            .texOffs(82, 38).addBox(-17.0F, -2.0F, -11.0F, 5.0F, 3.0F, 3.0F)
                            .texOffs(82, 38).addBox(12.0F, -2.0F, -11.0F, 5.0F, 3.0F, 3.0F)
                            // Operator-side neck-strap lanyard loop
                            .texOffs(100, 38).addBox(-3.0F, -1.0F, 9.0F, 6.0F, 2.0F, 3.0F)
                            // Machined left & right gimbal socket collars
                            .texOffs(78, 48).addBox(-17.0F, -4.0F, 2.0F, 5.0F, 2.0F, 5.0F)
                            .texOffs(78, 48).addBox(12.0F, -4.0F, 2.0F, 5.0F, 2.0F, 5.0F)
                            // Tactile rotary telemetry dial + guarded crimson master-arm button
                            .texOffs(100, 48).addBox(-8.0F, -6.0F, 6.0F, 3.0F, 2.0F, 2.0F)
                            .texOffs(114, 48).addBox(5.0F, -6.0F, 6.0F, 3.0F, 2.0F, 2.0F),
                    PartPose.ZERO);

            // 3-panel articulated anti-glare sun-hood visor along the front bezel edge
            d.addOrReplaceChild("visor",
                    CubeListBuilder.create()
                            .texOffs(60, 56).addBox(-11.0F, -7.0F, -1.0F, 22.0F, 7.0F, 2.0F)
                            .texOffs(110, 54).addBox(-11.0F, -6.0F, 1.0F, 2.0F, 6.0F, 6.0F)
                            .texOffs(110, 54).addBox(9.0F, -6.0F, 1.0F, 2.0F, 6.0F, 6.0F),
                    PartPose.offsetAndRotation(0.0F, -5.5F, -9.0F, -0.36F, 0.0F, 0.0F));

            // Dual precision gimbal thumbsticks (stainless shaft + knurled anodized crown)
            d.addOrReplaceChild("stick_left",
                    CubeListBuilder.create()
                            .texOffs(84, 98).addBox(-1.0F, -6.0F, -1.0F, 2.0F, 6.0F, 2.0F)
                            .texOffs(94, 98).addBox(-2.0F, -8.0F, -2.0F, 4.0F, 2.0F, 4.0F),
                    PartPose.offset(-14.5F, -3.5F, 4.5F));

            d.addOrReplaceChild("stick_right",
                    CubeListBuilder.create()
                            .texOffs(84, 98).addBox(-1.0F, -6.0F, -1.0F, 2.0F, 6.0F, 2.0F)
                            .texOffs(94, 106).addBox(-2.0F, -8.0F, -2.0F, 4.0F, 2.0F, 4.0F),
                    PartPose.offset(14.5F, -3.5F, 4.5F));

            // Twin articulated high-gain directional patch/dipole V-antennas
            d.addOrReplaceChild("antenna_left",
                    CubeListBuilder.create()
                            .texOffs(108, 68).addBox(-1.0F, -15.0F, -1.0F, 2.0F, 15.0F, 2.0F)
                            .texOffs(112, 88).addBox(-2.0F, -17.0F, -1.5F, 4.0F, 5.0F, 3.0F),
                    PartPose.offsetAndRotation(-6.0F, -3.0F, -12.0F, -0.36F, 0.0F, -0.26F));

            d.addOrReplaceChild("antenna_right",
                    CubeListBuilder.create()
                            .texOffs(108, 68).addBox(-1.0F, -15.0F, -1.0F, 2.0F, 15.0F, 2.0F)
                            .texOffs(112, 88).addBox(-2.0F, -17.0F, -1.5F, 4.0F, 5.0F, 3.0F),
                    PartPose.offsetAndRotation(6.0F, -3.0F, -12.0F, -0.36F, 0.0F, 0.26F));

            this.root = LayerDefinition.create(mesh, 128, 128).bakeRoot();
            this.deck = root.getChild("deck");
            this.visor = deck.getChild("visor");
            this.stickLeft = deck.getChild("stick_left");
            this.stickRight = deck.getChild("stick_right");
            this.antennaLeft = deck.getChild("antenna_left");
            this.antennaRight = deck.getChild("antenna_right");
        }

        void setupAnim(float timeSec, boolean activeLink, boolean hasDrone) {
            if (activeLink) {
                stickLeft.xRot = Mth.sin(timeSec * 4.8F) * 0.24F;
                stickLeft.zRot = Mth.cos(timeSec * 3.5F) * 0.19F;
                stickRight.xRot = Mth.cos(timeSec * 5.2F) * 0.26F;
                stickRight.zRot = Mth.sin(timeSec * 4.1F) * 0.21F;
                visor.xRot = -0.42F;
                antennaLeft.xRot = -0.44F;
                antennaRight.xRot = -0.44F;
                antennaLeft.zRot = -0.30F;
                antennaRight.zRot = 0.30F;
            } else if (hasDrone) {
                stickLeft.xRot = Mth.sin(timeSec * 1.6F) * 0.05F;
                stickLeft.zRot = 0.0F;
                stickRight.xRot = -Mth.sin(timeSec * 1.6F) * 0.05F;
                stickRight.zRot = 0.0F;
                visor.xRot = -0.34F;
                antennaLeft.xRot = -0.32F;
                antennaRight.xRot = -0.32F;
                antennaLeft.zRot = -0.20F;
                antennaRight.zRot = 0.20F;
            } else {
                stickLeft.xRot = 0.0F;
                stickLeft.zRot = 0.0F;
                stickRight.xRot = 0.0F;
                stickRight.zRot = 0.0F;
                visor.xRot = -0.22F;
                antennaLeft.xRot = -0.20F;
                antennaRight.xRot = -0.20F;
                antennaLeft.zRot = -0.10F;
                antennaRight.zRot = 0.10F;
            }
        }
    }

    /**
     * Authored at 2x integer voxel scale (scaled by {@code 0.5F} at render time) on a {@code 64x64} texture.
     */
    private static final class BatteryRig {
        final ModelPart root;
        final ModelPart cell;

        BatteryRig() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition r = mesh.getRoot();

            r.addOrReplaceChild("cell",
                    CubeListBuilder.create()
                            // Main armored LiPo core block
                            .texOffs(0, 0).addBox(-4.0F, -6.0F, -3.0F, 8.0F, 12.0F, 6.0F)
                            // Side titanium shock-rails
                            .texOffs(28, 0).addBox(-5.0F, -5.0F, -2.0F, 1.0F, 10.0F, 4.0F)
                            .texOffs(28, 0).addBox(4.0F, -5.0F, -2.0F, 1.0F, 10.0F, 4.0F)
                            // Top magnetic dock header & hazard collar
                            .texOffs(0, 20).addBox(-3.5F, -7.0F, -2.5F, 7.0F, 1.0F, 5.0F)
                            // Twin brass high-current connector pins
                            .texOffs(28, 20).addBox(-2.0F, -8.0F, -1.0F, 1.0F, 1.0F, 2.0F)
                            .texOffs(28, 20).addBox(1.0F, -8.0F, -1.0F, 1.0F, 1.0F, 2.0F)
                            // Quick-swap pull handle arch
                            .texOffs(38, 0).addBox(-3.0F, -9.0F, -0.5F, 6.0F, 1.0F, 1.0F)
                            // Bottom rubberized base bumper
                            .texOffs(0, 28).addBox(-4.5F, 6.0F, -3.5F, 9.0F, 1.0F, 7.0F),
                    PartPose.ZERO);

            this.root = LayerDefinition.create(mesh, 64, 64).bakeRoot();
            this.cell = root.getChild("cell");
        }
    }

    private static ControllerRig controller() {
        if (controllerRig == null) controllerRig = new ControllerRig();
        return controllerRig;
    }

    private static BatteryRig battery() {
        if (batteryRig == null) batteryRig = new BatteryRig();
        return batteryRig;
    }

    private static ReconDroneModel miniDrone() {
        if (miniDroneModel == null) miniDroneModel = new ReconDroneModel();
        return miniDroneModel;
    }

    public static boolean renderCustomDroneItem(
            ItemStack stack,
            ItemDisplayContext context,
            boolean leftHand,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight
    ) {
        if (stack == null || stack.isEmpty()) return false;
        boolean isCompass = stack.is(net.minecraft.world.item.Items.RECOVERY_COMPASS);
        boolean isShard = !isCompass && stack.is(net.minecraft.world.item.Items.ECHO_SHARD);
        if (!isCompass && !isShard) {
            return false;
        }
        boolean isTerminal = isCompass && HabiRoleItems.hasFlag(stack, HabiRoleItems.TAG_DRONE_TERMINAL);
        boolean isBattery = isShard && HabiRoleItems.hasFlag(stack, HabiRoleItems.TAG_DRONE_BATTERY);
        boolean isResupply = isCompass && !isTerminal && HabiRoleItems.hasFlag(stack, HabiRoleItems.TAG_DRONE_RESUPPLY);
        if (!isTerminal && !isBattery && !isResupply) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        float timeSec = (DroneOperatorClient.clientTicks() + partial) / 20.0F;

        pose.pushPose();
        if (isTerminal) {
            applyTerminalDisplayTransform(context, leftHand, pose, timeSec);
            pose.scale(-1.0F, -1.0F, 1.0F);
            DroneStatePayload st = DroneOperatorClient.state();
            boolean active = st.droneEntityId() != -1;
            boolean hasDrone = st.hasDroneFrame() || active;
            float batRatio = hasDrone ? Mth.clamp(st.battery() / (float) DroneRules.MAX_BATTERY, 0.0F, 1.0F) : 0.0F;
            renderControllerMesh(pose, buffers, packedLight, timeSec, active, hasDrone, batRatio);
        } else if (isBattery) {
            applyBatteryDisplayTransform(context, leftHand, pose, timeSec);
            pose.scale(-1.0F, -1.0F, 1.0F);
            renderBatteryMesh(pose, buffers, packedLight, timeSec);
        } else {
            applyResupplyDroneTransform(context, pose, timeSec);
            ReconDroneModel dm = miniDrone();
            dm.armFL.yRot = 0.785F;
            dm.armFR.yRot = -0.785F;
            dm.armBL.yRot = -0.785F;
            dm.armBR.yRot = 0.785F;
            dm.armFL.xRot = dm.armFR.xRot = dm.armBL.xRot = dm.armBR.xRot = 0.0F;
            dm.gimbal.xRot = 0.0F;
            dm.batteryHatch.xRot = 0.0F;
            float spin = timeSec * 14.0F;
            dm.rotorFL.yRot = spin;
            dm.rotorBR.yRot = spin;
            dm.rotorFR.yRot = -spin;
            dm.rotorBL.yRot = -spin;
            VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(ReconDroneModel.texture()));
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(0.0F, -1.35F, 0.0F);
            dm.renderBody(pose, vc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xEE93E5FF);
        }
        pose.popPose();
        return true;
    }

    /**
     * Renders the 1st-person choreographed "Taking Out &amp; Unfolding New Recon Drone" animation
     * immediately after buying a whole-unit resupply in the shop ({@code progress} in {@code [0, 1]}).
     */
    public static void renderFirstPersonDroneUnbox(
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            float progress,
            float timeSec,
            FirstPersonArmDrawer armDrawer
    ) {
        float p = Mth.clamp(progress, 0.0F, 1.0F);

        // Phase 1 (0.00..0.26): Lift folded drone out from pack
        // Phase 2 (0.26..0.68): Snap-unfold 4 carbon X-arms + optical calibration + rotor spin self-test
        // Phase 3 (0.68..1.00): Inspect & smoothly stow ready for launch
        float lift;
        if (p < 0.26F) {
            float t = p / 0.26F;
            lift = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        } else if (p < 0.72F) {
            lift = 1.0F;
        } else {
            float t = (p - 0.72F) / 0.28F;
            lift = 1.0F - t * t;
        }

        // Mechanical spring-snap arm unfold curve (0 -> 1 with crisp overshoot)
        float unfoldRaw = Mth.clamp((p - 0.20F) / 0.30F, 0.0F, 1.0F);
        float unfold = unfoldRaw <= 0.0F ? 0.0F
                : (unfoldRaw >= 1.0F ? 1.0F
                : 1.0F + 1.8F * (float) Math.pow(unfoldRaw - 1.0F, 3) + 0.8F * (float) Math.pow(unfoldRaw - 1.0F, 2));

        float stowSlideX = p > 0.72F ? ((p - 0.72F) / 0.28F) * 0.22F : 0.0F;
        float inspectRoll = (p >= 0.24F && p <= 0.76F)
                ? Mth.sin(((p - 0.24F) / 0.52F) * (float) Math.PI * 2.0F) * 6.5F
                : 0.0F;
        float snapJoltY = (p >= 0.36F && p <= 0.48F)
                ? Mth.sin(((p - 0.36F) / 0.12F) * (float) Math.PI) * 0.032F
                : 0.0F;

        // 1. Both player 1st-person arms cradling the drone from left & right
        float armDrop = 1.0F - lift;
        for (HumanoidArm side : new HumanoidArm[]{HumanoidArm.LEFT, HumanoidArm.RIGHT}) {
            float dir = side == HumanoidArm.RIGHT ? 1.0F : -1.0F;
            pose.pushPose();
            pose.translate(dir * -0.20F + stowSlideX, -0.06F - armDrop * 0.45F + snapJoltY, -0.10F);
            pose.mulPose(Axis.YP.rotationDegrees(dir * 16.0F));
            pose.mulPose(Axis.XP.rotationDegrees(10.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(dir * -8.0F));
            armDrawer.drawArm(pose, side, armDrop * 0.55F);
            pose.popPose();
        }

        // 2. Central 3D ReconDroneModel unfolding & spinning up in front of camera
        pose.pushPose();
        float yPos = Mth.lerp(lift, -0.86F, -0.31F) + snapJoltY;
        float zPos = Mth.lerp(lift, -0.82F, -0.66F);
        pose.translate(stowSlideX, yPos, zPos);
        pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(lift, 38.0F, 20.0F)));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F + Mth.sin(p * (float) Math.PI) * 10.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(inspectRoll));
        pose.scale(0.86F, 0.86F, 0.86F);

        renderUnfoldingDroneMesh(pose, buffers, packedLight, p, unfold, timeSec);
        pose.popPose();
    }

    /**
     * Renders the 3rd-person unfolding {@link ReconDroneModel} held between the operator's
     * hands when buying a drone resupply ({@code pose == 4}).
     */
    public static void renderThirdPersonUnboxDrone(
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            float progress,
            float ageInTicks
    ) {
        float p = Mth.clamp(progress, 0.0F, 1.0F);
        float timeSec = ageInTicks / 20.0F;
        float lift = p < 0.25F ? (p / 0.25F) : (p > 0.75F ? (1.0F - (p - 0.75F) / 0.25F) : 1.0F);
        float unfold = Mth.clamp((p - 0.20F) / 0.30F, 0.0F, 1.0F);
        unfold = 1.0F - (1.0F - unfold) * (1.0F - unfold);

        pose.pushPose();
        pose.translate(0.0F, Mth.lerp(lift, 0.58F, 0.34F), Mth.lerp(lift, -0.32F, -0.58F));
        pose.mulPose(Axis.XP.rotationDegrees(-18.0F));
        pose.scale(0.76F, 0.76F, 0.76F);
        renderUnfoldingDroneMesh(pose, buffers, packedLight, p, unfold, timeSec);
        pose.popPose();
    }

    private static void renderUnfoldingDroneMesh(
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            float progress,
            float unfold,
            float timeSec
    ) {
        ReconDroneModel dm = miniDrone();
        float armAngle = Mth.lerp(unfold, 0.10F, 0.785F);
        dm.armFL.yRot = armAngle;
        dm.armFR.yRot = -armAngle;
        dm.armBL.yRot = -armAngle;
        dm.armBR.yRot = armAngle;

        float armFoldPitch = (1.0F - Mth.clamp(unfold, 0.0F, 1.05F)) * 0.40F;
        dm.armFL.xRot = -armFoldPitch;
        dm.armFR.xRot = -armFoldPitch;
        dm.armBL.xRot = armFoldPitch;
        dm.armBR.xRot = armFoldPitch;

        // Gimbal nod self-test during power-up
        dm.gimbal.xRot = progress > 0.32F ? Mth.sin((progress - 0.32F) * 14.0F) * 0.26F : 0.22F;
        dm.batteryHatch.xRot = 0.0F;

        // Belly claws flex & lock
        float clawFlex = progress > 0.28F && progress < 0.58F
                ? Mth.sin(((progress - 0.28F) / 0.30F) * (float) Math.PI) * 0.28F
                : 0.0F;
        dm.clawLeft.zRot = clawFlex;
        dm.clawRight.zRot = -clawFlex;

        // Rotor diagnostic spin burst once arms snap open
        float spinFactor = Mth.clamp((progress - 0.34F) / 0.45F, 0.0F, 1.0F);
        float spin = spinFactor * spinFactor * 22.0F + (progress > 0.34F ? timeSec * 16.0F : 0.0F);
        dm.rotorFL.yRot = spin;
        dm.rotorBR.yRot = spin;
        dm.rotorFR.yRot = -spin;
        dm.rotorBL.yRot = -spin;

        pose.pushPose();
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0.0F, -1.35F, 0.0F);
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(ReconDroneModel.texture()));
        dm.renderBody(pose, vc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        pose.popPose();

        // Power-on cyan optical lens, green battery full LED, and expanding calibration halo
        if (progress > 0.30F) {
            VertexConsumer glow = buffers.getBuffer(RenderType.debugQuads());
            Matrix4f mat = pose.last().pose();
            addFlatZQuad(mat, glow, -0.055F, 0.055F, -0.035F, 0.035F, -0.34F, 0xFF38BDF8);
            addFlatZQuad(mat, glow, -0.06F, 0.06F, 0.02F, 0.06F, 0.32F, 0xFF22C55E);

            if (progress < 0.85F) {
                float ringT = (progress - 0.30F) / 0.55F;
                float radius = Mth.lerp(ringT, 0.18F, 0.52F);
                int alpha = Math.round((1.0F - ringT) * 190.0F);
                int ringCol = (Mth.clamp(alpha, 0, 220) << 24) | 0x38BDF8;
                addFlatYQuad(mat, glow, -radius, radius, -0.02F, -radius, -radius + 0.025F, ringCol);
                addFlatYQuad(mat, glow, -radius, radius, -0.02F, radius - 0.025F, radius, ringCol);
                addFlatYQuad(mat, glow, -radius, -radius + 0.025F, -0.02F, -radius, radius, ringCol);
                addFlatYQuad(mat, glow, radius - 0.025F, radius, -0.02F, -radius, radius, ringCol);
            }
        }
    }

    /**
     * Renders the 3D Twin-Stick Controller directly in {@code PlayerModel} space
     * between both hands during the half-squat piloting pose ({@code pose == 1}).
     */
    public static void renderTwoHandedOperatorController(
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            float ageInTicks
    ) {
        float timeSec = ageInTicks / 20.0F;
        pose.pushPose();
        pose.translate(0.0F, 11.35F / 16.0F, -8.35F / 16.0F);
        pose.mulPose(Axis.XP.rotationDegrees(-32.0F));
        pose.scale(0.92F, 0.92F, 0.92F);
        renderControllerMesh(pose, buffers, packedLight, timeSec, true, true, 0.85F);
        pose.popPose();
    }

    /**
     * Renders the 3D High-Energy Battery in {@code PlayerModel} space during the
     * 1.5s magnetic battery swap animation ({@code pose == 2}).
     */
    public static void renderSwappingBatteryInHand(
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            float ageInTicks
    ) {
        float timeSec = ageInTicks / 20.0F;
        float swapWave = Mth.sin(ageInTicks * 0.85F) * 0.06F;
        pose.pushPose();
        pose.translate(-0.10F, 0.34F, -0.56F + swapWave);
        pose.mulPose(Axis.XP.rotationDegrees(-75.0F));
        pose.scale(0.78F, 0.78F, 0.78F);
        renderBatteryMesh(pose, buffers, packedLight, timeSec);
        pose.popPose();
    }

    private static void renderControllerMesh(
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            float timeSec,
            boolean activeLink,
            boolean hasDrone,
            float batteryRatio
    ) {
        ControllerRig rig = controller();
        rig.setupAnim(timeSec, activeLink, hasDrone);

        // Render 4x integer-voxel rig scaled down by 0.25x -> razor-sharp sub-voxel geometry & UVs
        pose.pushPose();
        pose.scale(0.25F, 0.25F, 0.25F);
        VertexConsumer hullVc = buffers.getBuffer(RenderType.entityTranslucent(controllerTexture()));
        rig.deck.render(pose, hullVc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        pose.popPose();

        // Fullbright recessed OLED screen & status LEDs inside the 4-wall metallic bezel
        // Screen bay in model units: X in [-2.20, +2.20], Z in [-1.70, +0.95], recessed floor at Y = -1.25
        VertexConsumer glow = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f mat = pose.last().pose();
        float s = 1.0F / 16.0F;
        float yBase = -1.275F * s;
        float yHud = -1.295F * s;
        float yTop = -1.312F * s;

        int screenBg = !hasDrone ? 0xFF160910 : (activeLink ? 0xFF05283E : 0xFF071D2C);
        addFlatYQuad(mat, glow, -2.20F * s, 2.20F * s, yBase, -1.70F * s, 0.95F * s, screenBg);

        int accent = !hasDrone ? 0xFFEF4444 : (activeLink ? 0xFF38BDF8 : 0xFF34D399);
        // Crisp top & bottom telemetry header/footer rails inside screen
        addFlatYQuad(mat, glow, -2.12F * s, 2.12F * s, yHud, -1.64F * s, -1.48F * s, accent);
        addFlatYQuad(mat, glow, -2.12F * s, 2.12F * s, yHud, 0.78F * s, 0.90F * s, (accent & 0x00FFFFFF) | 0xAA000000);

        if (!hasDrone) {
            // Standby / No-Drone diagnostic pulsing amber-red bars
            boolean blink = ((int) (timeSec * 3.2F) & 1) == 0;
            int warnCol = blink ? 0xFFFF4D6D : 0xAA991B1B;
            addFlatYQuad(mat, glow, -1.35F * s, 1.35F * s, yTop, -0.55F * s, -0.25F * s, warnCol);
            addFlatYQuad(mat, glow, -0.85F * s, 0.85F * s, yTop, 0.02F * s, 0.22F * s, 0xCCF59E0B);
        } else {
            // Sweeping telemetry scanline
            float scanSpeed = activeLink ? 1.05F : 0.45F;
            float scanZ = Mth.lerp((timeSec * scanSpeed) % 1.0F, -1.42F, 0.68F) * s;
            int scanCol = activeLink ? 0x887DD3FC : 0x5534D399;
            addFlatYQuad(mat, glow, -2.02F * s, 2.02F * s, yHud, scanZ, scanZ + 0.14F * s, scanCol);

            // Live battery bar along bottom of OLED display
            float barW = 3.80F * Mth.clamp(batteryRatio, 0.08F, 1.0F);
            int batCol = batteryRatio <= 0.20F ? 0xFFFF3B5C : (batteryRatio <= 0.50F ? 0xFFFBBF24 : 0xFF34D399);
            addFlatYQuad(mat, glow, -1.90F * s, (-1.90F + barW) * s, yTop, 0.48F * s, 0.68F * s, batCol);

            if (activeLink) {
                // Live artificial horizon + center crosshair + radar blip
                float bankShift = Mth.sin(timeSec * 2.4F) * 0.22F;
                addFlatYQuad(mat, glow, -1.55F * s, -0.35F * s, yTop, (-0.42F + bankShift) * s, (-0.28F + bankShift) * s, 0xEE38BDF8);
                addFlatYQuad(mat, glow, 0.35F * s, 1.55F * s, yTop, (-0.42F - bankShift) * s, (-0.28F - bankShift) * s, 0xEE38BDF8);
                addFlatYQuad(mat, glow, -0.18F * s, 0.18F * s, yTop, -0.44F * s, -0.26F * s, 0xFFFFFFFF);
                addFlatYQuad(mat, glow, -0.05F * s, 0.05F * s, yTop, -1.05F * s, 0.22F * s, 0xBB38BDF8);
            } else {
                // Stowed READY quadcopter cross emblem
                addFlatYQuad(mat, glow, -0.95F * s, 0.95F * s, yTop, -0.45F * s, -0.30F * s, 0xDD34D399);
                addFlatYQuad(mat, glow, -0.08F * s, 0.08F * s, yTop, -0.95F * s, 0.12F * s, 0xDD38BDF8);
            }
        }

        // 4 hardware status micro-LEDs on the chamfered lower deck strip (y = -1.27 * s, z = 1.62..1.88 * s)
        float ledY = -1.27F * s;
        int led1 = hasDrone ? 0xFF22C55E : 0xFF475569;
        int led2 = hasDrone && batteryRatio > 0.35F ? 0xFF22C55E : (hasDrone ? 0xFFFBBF24 : 0xFF475569);
        int led3 = activeLink ? 0xFF38BDF8 : (hasDrone ? 0xFF0EA5E9 : 0xFF475569);
        int led4 = activeLink
                ? (((int) (timeSec * 5.0F) & 1) == 0 ? 0xFF38BDF8 : 0xFFFF3B5C)
                : (hasDrone ? 0xFF22C55E : 0xFFEF4444);
        addFlatYQuad(mat, glow, -0.95F * s, -0.55F * s, ledY, 1.62F * s, 1.88F * s, led1);
        addFlatYQuad(mat, glow, -0.42F * s, -0.02F * s, ledY, 1.62F * s, 1.88F * s, led2);
        addFlatYQuad(mat, glow, 0.11F * s, 0.51F * s, ledY, 1.62F * s, 1.88F * s, led3);
        addFlatYQuad(mat, glow, 0.64F * s, 1.04F * s, ledY, 1.62F * s, 1.88F * s, led4);
    }

    private static void renderBatteryMesh(
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            float timeSec
    ) {
        BatteryRig rig = battery();
        pose.pushPose();
        pose.scale(0.5F, 0.5F, 0.5F);
        VertexConsumer hullVc = buffers.getBuffer(RenderType.entityTranslucent(batteryTexture()));
        rig.cell.render(pose, hullVc, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        pose.popPose();

        // Fullbright 3-segment glowing cyan/emerald LiPo energy bars on front & back windows
        VertexConsumer glow = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f mat = pose.last().pose();
        float s = 1.0F / 16.0F;
        float pulse = 0.82F + 0.18F * Mth.sin(timeSec * 4.5F);
        int a = Mth.clamp((int) (pulse * 255.0F), 180, 255);
        int bar1 = (a << 24) | 0x22C55E;
        int bar2 = (a << 24) | 0x34D399;
        int bar3 = (a << 24) | 0x38BDF8;

        for (float zFace : new float[]{-1.54F * s, 1.54F * s}) {
            addFlatZQuad(mat, glow, -1.15F * s, 1.15F * s, -2.15F * s, -1.05F * s, zFace, bar3);
            addFlatZQuad(mat, glow, -1.15F * s, 1.15F * s, -0.65F * s, 0.45F * s, zFace, bar2);
            addFlatZQuad(mat, glow, -1.15F * s, 1.15F * s, 0.85F * s, 1.95F * s, zFace, bar1);
        }
    }

    private static void applyTerminalDisplayTransform(ItemDisplayContext ctx, boolean leftHand, PoseStack pose, float timeSec) {
        float sign = leftHand ? -1.0F : 1.0F;
        switch (ctx) {
            case GUI -> {
                pose.translate(0.0F, -0.03F, 0.0F);
                pose.mulPose(Axis.XP.rotationDegrees(46.0F));
                pose.mulPose(Axis.YP.rotationDegrees(-26.0F));
                pose.mulPose(Axis.ZP.rotationDegrees(7.0F));
                pose.scale(1.44F, 1.44F, 1.44F);
            }
            case FIRST_PERSON_RIGHT_HAND, FIRST_PERSON_LEFT_HAND -> {
                float breathe = Mth.sin(timeSec * 1.8F) * 0.005F;
                pose.translate(sign * -0.11F, -0.09F + breathe, -0.07F);
                pose.mulPose(Axis.XP.rotationDegrees(40.0F));
                pose.mulPose(Axis.YP.rotationDegrees(sign * 16.0F));
                pose.mulPose(Axis.ZP.rotationDegrees(sign * -4.0F));
                pose.scale(1.22F, 1.22F, 1.22F);
            }
            case THIRD_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND -> {
                pose.translate(0.0F, 0.08F, -0.06F);
                pose.mulPose(Axis.XP.rotationDegrees(24.0F));
                pose.scale(0.95F, 0.95F, 0.95F);
            }
            case GROUND -> {
                pose.translate(0.0F, 0.14F, 0.0F);
                pose.scale(0.95F, 0.95F, 0.95F);
            }
            default -> {
                pose.mulPose(Axis.XP.rotationDegrees(28.0F));
                pose.scale(1.16F, 1.16F, 1.16F);
            }
        }
    }

    private static void applyBatteryDisplayTransform(ItemDisplayContext ctx, boolean leftHand, PoseStack pose, float timeSec) {
        float sign = leftHand ? -1.0F : 1.0F;
        switch (ctx) {
            case GUI -> {
                pose.translate(0.0F, -0.02F, 0.0F);
                pose.mulPose(Axis.XP.rotationDegrees(24.0F));
                pose.mulPose(Axis.YP.rotationDegrees(-36.0F));
                pose.scale(1.52F, 1.52F, 1.52F);
            }
            case FIRST_PERSON_RIGHT_HAND, FIRST_PERSON_LEFT_HAND -> {
                pose.translate(sign * -0.06F, -0.08F, -0.04F);
                pose.mulPose(Axis.XP.rotationDegrees(14.0F));
                pose.mulPose(Axis.YP.rotationDegrees(sign * 24.0F));
                pose.scale(1.12F, 1.12F, 1.12F);
            }
            case THIRD_PERSON_RIGHT_HAND, THIRD_PERSON_LEFT_HAND -> {
                pose.translate(0.0F, 0.08F, -0.02F);
                pose.mulPose(Axis.XP.rotationDegrees(12.0F));
                pose.scale(0.88F, 0.88F, 0.88F);
            }
            case GROUND -> {
                pose.translate(0.0F, 0.22F, 0.0F);
                pose.mulPose(Axis.YP.rotationDegrees(timeSec * 32.0F));
                pose.scale(0.92F, 0.92F, 0.92F);
            }
            default -> pose.scale(1.2F, 1.2F, 1.2F);
        }
    }

    private static void applyResupplyDroneTransform(ItemDisplayContext ctx, PoseStack pose, float timeSec) {
        if (ctx == ItemDisplayContext.GUI) {
            pose.translate(0.0F, -0.04F, 0.0F);
            pose.mulPose(Axis.XP.rotationDegrees(30.0F));
            pose.mulPose(Axis.YP.rotationDegrees(-38.0F + timeSec * 18.0F));
            pose.scale(0.95F, 0.95F, 0.95F);
        } else {
            pose.scale(0.75F, 0.75F, 0.75F);
        }
    }

    private static ResourceLocation controllerTexture() {
        if (controllerTexture != null) return controllerTexture;
        NativeImage img = new NativeImage(128, 128, true);

        // Base diagonal carbon-fiber twill weave across entire sheet
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int pattern = (x + y * 2) & 3;
                int col = switch (pattern) {
                    case 0 -> 0xFF243247;
                    case 1 -> 0xFF1A2536;
                    case 2 -> 0xFF131C2B;
                    default -> 0xFF1E293B;
                };
                img.setPixelRGBA(x, y, argbToAbgr(col));
            }
        }

        // Upper chamfered carbon-titanium plate with subtle metallic sheen (0..80, 28..46)
        for (int y = 28; y < 46; y++) {
            for (int x = 0; x < 80; x++) {
                boolean weave = ((x ^ y) & 1) == 0;
                img.setPixelRGBA(x, y, argbToAbgr(weave ? 0xFF2B3B52 : 0xFF212E42));
            }
        }

        // 4-wall brushed anodized titanium bezel + sky-cyan chamfer stripe (0..76, 48..61)
        fillRect(img, 0, 48, 76, 61, 0xFF475569);
        fillRect(img, 0, 49, 48, 50, 0xFF38BDF8);
        fillRect(img, 50, 49, 76, 51, 0xFF64748B);

        // Deep anti-reflective OLED glass bed (0..58, 54..66)
        fillRect(img, 0, 54, 58, 66, 0xFF05131F);

        // Ribbed non-slip elastomer handgrips + palm swells (0..106, 68..116)
        for (int y = 68; y < 116; y++) {
            boolean ridge = (y % 3) == 0;
            int gripCol = ridge ? 0xFF0B111E : 0xFF151F30;
            for (int x = 0; x < 106; x++) {
                img.setPixelRGBA(x, y, argbToAbgr(gripCol));
            }
        }
        // Subtle cyan accent seam along grip bolsters
        fillRect(img, 0, 69, 106, 70, 0xFF0EA5E9);

        // Front RF heatsink fins (82..124, 28..36) + silver shoulder triggers (82..98, 38..44)
        for (int x = 82; x < 124; x++) {
            int finCol = (x % 2 == 0) ? 0xFF0F172A : 0xFF334155;
            fillRect(img, x, 28, x + 1, 36, finCol);
        }
        fillRect(img, 82, 38, 98, 44, 0xFF94A3B8);
        fillRect(img, 100, 38, 118, 43, 0xFF64748B);

        // Gimbal sockets (78..98, 48..55), rotary dial (100..112, 48..53), guarded crimson arm switch (114..126, 48..53)
        fillRect(img, 78, 48, 98, 55, 0xFF0F172A);
        fillRect(img, 80, 49, 96, 51, 0xFF475569);
        fillRect(img, 100, 48, 112, 53, 0xFFFBBF24);
        fillRect(img, 114, 48, 126, 53, 0xFFEF4444);

        // 3-panel sun-hood visor matte carbon + glowing cyan lip (60..126, 54..66)
        fillRect(img, 60, 54, 126, 66, 0xFF0F172A);
        fillRect(img, 60, 56, 108, 58, 0xFF38BDF8);

        // Stainless joystick shafts (84..92, 98..106) + knurled cyan left crown / crimson right crown
        fillRect(img, 84, 98, 92, 106, 0xFFCBD5E1);
        fillRect(img, 94, 98, 110, 104, 0xFF0EA5E9);
        fillRect(img, 96, 98, 108, 100, 0xFF7DD3FC);
        fillRect(img, 94, 106, 110, 112, 0xFFEF4444);
        fillRect(img, 96, 106, 108, 108, 0xFFFCA5A5);

        // Directional V-antenna masts (108..116, 68..85) + high-gain patch tips (112..126, 88..96)
        fillRect(img, 108, 68, 116, 85, 0xFF334155);
        fillRect(img, 112, 88, 126, 96, 0xFF1E293B);
        fillRect(img, 113, 89, 125, 92, 0xFF38BDF8);

        controllerTexture = HabiTrainCore.id("textures/item/drone_controller_v2_3d");
        Minecraft.getInstance().getTextureManager().register(controllerTexture, new DynamicTexture(img));
        return controllerTexture;
    }

    private static ResourceLocation batteryTexture() {
        if (batteryTexture != null) return batteryTexture;
        NativeImage img = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                boolean grain = ((x ^ y) & 1) == 0;
                img.setPixelRGBA(x, y, argbToAbgr(grain ? 0xFF1E293B : 0xFF162032));
            }
        }
        // Titanium side rails
        fillRect(img, 28, 0, 40, 16, 0xFF475569);
        // Hazard amber collar + magnetic top dock
        fillRect(img, 0, 20, 26, 27, 0xFFF59E0B);
        // Brass high-current pins
        fillRect(img, 28, 20, 42, 27, 0xFFFDE047);
        // Quick-pull handle
        fillRect(img, 38, 0, 56, 8, 0xFF64748B);
        // Heavy rubber base
        fillRect(img, 0, 28, 36, 38, 0xFF090D16);

        batteryTexture = HabiTrainCore.id("textures/item/drone_battery_v2_3d");
        Minecraft.getInstance().getTextureManager().register(batteryTexture, new DynamicTexture(img));
        return batteryTexture;
    }

    private static void addFlatYQuad(Matrix4f mat, VertexConsumer vc, float x0, float x1, float y, float z0, float z1, int argb) {
        vc.addVertex(mat, x0, y, z0).setColor(argb);
        vc.addVertex(mat, x1, y, z0).setColor(argb);
        vc.addVertex(mat, x1, y, z1).setColor(argb);
        vc.addVertex(mat, x0, y, z1).setColor(argb);

        vc.addVertex(mat, x0, y, z1).setColor(argb);
        vc.addVertex(mat, x1, y, z1).setColor(argb);
        vc.addVertex(mat, x1, y, z0).setColor(argb);
        vc.addVertex(mat, x0, y, z0).setColor(argb);
    }

    private static void addFlatZQuad(Matrix4f mat, VertexConsumer vc, float x0, float x1, float y0, float y1, float z, int argb) {
        vc.addVertex(mat, x0, y0, z).setColor(argb);
        vc.addVertex(mat, x1, y0, z).setColor(argb);
        vc.addVertex(mat, x1, y1, z).setColor(argb);
        vc.addVertex(mat, x0, y1, z).setColor(argb);

        vc.addVertex(mat, x0, y1, z).setColor(argb);
        vc.addVertex(mat, x1, y1, z).setColor(argb);
        vc.addVertex(mat, x1, y0, z).setColor(argb);
        vc.addVertex(mat, x0, y0, z).setColor(argb);
    }

    static void fillRect(NativeImage img, int x0, int y0, int x1, int y1, int argb) {
        int abgr = argbToAbgr(argb);
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x1); x++) {
                img.setPixelRGBA(x, y, abgr);
            }
        }
    }

    static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
