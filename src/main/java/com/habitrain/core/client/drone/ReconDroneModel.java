package com.habitrain.core.client.drone;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import com.habitrain.core.game.sre.role.drone.ReconDroneEntity;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Custom procedural 3D quadcopter model for {@link ReconDroneEntity}:
 * carbon-fiber chassis, nose gimbal lens, rear hinged battery hatch,
 * 4 folding arms, 4 counter-rotating rotor assemblies, and belly payload claws.
 */
@Environment(EnvType.CLIENT)
public final class ReconDroneModel {

    private static ResourceLocation cachedTexture;

    public final ModelPart root;
    public final ModelPart chassis;
    public final ModelPart gimbal;
    public final ModelPart batteryHatch;
    public final ModelPart armFL;
    public final ModelPart armFR;
    public final ModelPart armBL;
    public final ModelPart armBR;
    public final ModelPart rotorFL;
    public final ModelPart rotorFR;
    public final ModelPart rotorBL;
    public final ModelPart rotorBR;
    public final ModelPart clawLeft;
    public final ModelPart clawRight;

    public ReconDroneModel() {
        this.root = createLayer().bakeRoot();
        this.chassis = root.getChild("chassis");
        this.gimbal = chassis.getChild("gimbal");
        this.batteryHatch = chassis.getChild("battery_hatch");
        this.armFL = chassis.getChild("arm_fl");
        this.armFR = chassis.getChild("arm_fr");
        this.armBL = chassis.getChild("arm_bl");
        this.armBR = chassis.getChild("arm_br");
        this.rotorFL = armFL.getChild("rotor_fl");
        this.rotorFR = armFR.getChild("rotor_fr");
        this.rotorBL = armBL.getChild("rotor_bl");
        this.rotorBR = armBR.getChild("rotor_br");
        this.clawLeft = chassis.getChild("claw_left");
        this.clawRight = chassis.getChild("claw_right");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition chassis = root.addOrReplaceChild("chassis",
                CubeListBuilder.create()
                        // Main carbon hull
                        .texOffs(0, 0).addBox(-2.5F, -1.5F, -3.5F, 5.0F, 2.5F, 7.0F)
                        // Top telemetry GPS dome
                        .texOffs(0, 12).addBox(-1.5F, -2.2F, -1.5F, 3.0F, 0.8F, 3.5F),
                PartPose.offset(0.0F, 21.5F, 0.0F));

        // Front nose gimbal pod
        chassis.addOrReplaceChild("gimbal",
                CubeListBuilder.create()
                        .texOffs(24, 0).addBox(-1.4F, -1.1F, -1.8F, 2.8F, 2.2F, 2.0F),
                PartPose.offset(0.0F, 0.2F, -3.2F));

        // Rear hinged battery bay hatch
        chassis.addOrReplaceChild("battery_hatch",
                CubeListBuilder.create()
                        .texOffs(24, 6).addBox(-1.8F, -0.4F, 0.0F, 3.6F, 1.8F, 2.2F),
                PartPose.offset(0.0F, -1.2F, 2.8F));

        // 4 folding carbon arms + spinning dual-blade rotors
        PartDefinition armFL = chassis.addOrReplaceChild("arm_fl",
                CubeListBuilder.create()
                        .texOffs(0, 20).addBox(-0.6F, -0.5F, -5.2F, 1.2F, 1.0F, 5.4F)
                        .texOffs(16, 20).addBox(-1.0F, -1.0F, -5.6F, 2.0F, 1.6F, 2.0F),
                PartPose.offsetAndRotation(-2.0F, -0.4F, -2.0F, 0.0F, 0.785F, 0.0F));
        armFL.addOrReplaceChild("rotor_fl",
                CubeListBuilder.create()
                        .texOffs(0, 28).addBox(-3.6F, -0.2F, -0.45F, 7.2F, 0.2F, 0.9F)
                        .texOffs(0, 30).addBox(-0.45F, -0.2F, -3.6F, 0.9F, 0.2F, 7.2F),
                PartPose.offset(0.0F, -1.15F, -4.6F));

        PartDefinition armFR = chassis.addOrReplaceChild("arm_fr",
                CubeListBuilder.create()
                        .texOffs(0, 20).addBox(-0.6F, -0.5F, -5.2F, 1.2F, 1.0F, 5.4F)
                        .texOffs(16, 20).addBox(-1.0F, -1.0F, -5.6F, 2.0F, 1.6F, 2.0F),
                PartPose.offsetAndRotation(2.0F, -0.4F, -2.0F, 0.0F, -0.785F, 0.0F));
        armFR.addOrReplaceChild("rotor_fr",
                CubeListBuilder.create()
                        .texOffs(0, 28).addBox(-3.6F, -0.2F, -0.45F, 7.2F, 0.2F, 0.9F)
                        .texOffs(0, 30).addBox(-0.45F, -0.2F, -3.6F, 0.9F, 0.2F, 7.2F),
                PartPose.offset(0.0F, -1.15F, -4.6F));

        PartDefinition armBL = chassis.addOrReplaceChild("arm_bl",
                CubeListBuilder.create()
                        .texOffs(0, 20).addBox(-0.6F, -0.5F, 0.0F, 1.2F, 1.0F, 5.4F)
                        .texOffs(16, 20).addBox(-1.0F, -1.0F, 3.6F, 2.0F, 1.6F, 2.0F),
                PartPose.offsetAndRotation(-2.0F, -0.4F, 2.0F, 0.0F, -0.785F, 0.0F));
        armBL.addOrReplaceChild("rotor_bl",
                CubeListBuilder.create()
                        .texOffs(0, 28).addBox(-3.6F, -0.2F, -0.45F, 7.2F, 0.2F, 0.9F)
                        .texOffs(0, 30).addBox(-0.45F, -0.2F, -3.6F, 0.9F, 0.2F, 7.2F),
                PartPose.offset(0.0F, -1.15F, 4.6F));

        PartDefinition armBR = chassis.addOrReplaceChild("arm_br",
                CubeListBuilder.create()
                        .texOffs(0, 20).addBox(-0.6F, -0.5F, 0.0F, 1.2F, 1.0F, 5.4F)
                        .texOffs(16, 20).addBox(-1.0F, -1.0F, 3.6F, 2.0F, 1.6F, 2.0F),
                PartPose.offsetAndRotation(2.0F, -0.4F, 2.0F, 0.0F, 0.785F, 0.0F));
        armBR.addOrReplaceChild("rotor_br",
                CubeListBuilder.create()
                        .texOffs(0, 28).addBox(-3.6F, -0.2F, -0.45F, 7.2F, 0.2F, 0.9F)
                        .texOffs(0, 30).addBox(-0.45F, -0.2F, -3.6F, 0.9F, 0.2F, 7.2F),
                PartPose.offset(0.0F, -1.15F, 4.6F));

        // Belly mechanical payload claws
        chassis.addOrReplaceChild("claw_left",
                CubeListBuilder.create()
                        .texOffs(36, 0).addBox(-0.6F, 0.0F, -1.6F, 0.8F, 2.6F, 3.2F)
                        .texOffs(36, 8).addBox(-0.4F, 2.2F, -1.4F, 1.4F, 0.6F, 2.8F),
                PartPose.offset(-1.9F, 0.9F, 0.0F));

        chassis.addOrReplaceChild("claw_right",
                CubeListBuilder.create()
                        .texOffs(36, 0).addBox(-0.2F, 0.0F, -1.6F, 0.8F, 2.6F, 3.2F)
                        .texOffs(36, 8).addBox(-1.0F, 2.2F, -1.4F, 1.4F, 0.6F, 2.8F),
                PartPose.offset(1.9F, 0.9F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    public void setupAnim(ReconDroneEntity drone, float ageInTicks, float partialTick) {
        byte mode = drone.droneMode();
        float unfold = 1.0F;
        if (mode == ReconDroneEntity.MODE_DEPLOYING) {
            float rem = Math.max(0.0F, drone.modeTicks() - partialTick);
            unfold = Mth.clamp(1.0F - rem / DroneRules.DEPLOY_TICKS, 0.0F, 1.0F);
            unfold = 1.0F - (1.0F - unfold) * (1.0F - unfold);
        } else if (mode == ReconDroneEntity.MODE_RECALLING) {
            float rem = Math.max(0.0F, drone.modeTicks() - partialTick);
            unfold = Mth.clamp(rem / DroneRules.RECALL_TICKS, 0.0F, 1.0F);
        } else if (mode == ReconDroneEntity.MODE_SCRAPPED) {
            unfold = 0.72F;
        }

        // Smooth arm fold/unfold interpolation
        float armAngle = Mth.lerp(unfold, 0.12F, 0.785F);
        armFL.yRot = armAngle;
        armFR.yRot = -armAngle;
        armBL.yRot = -armAngle;
        armBR.yRot = armAngle;

        float armDrop = (1.0F - unfold) * 0.42F;
        armFL.xRot = -armDrop;
        armFR.xRot = -armDrop;
        armBL.xRot = armDrop;
        armBR.xRot = armDrop;

        // Gimbal pitch tracks drone XRot smoothly
        gimbal.xRot = Mth.clamp(drone.getViewXRot(partialTick) * Mth.DEG_TO_RAD * 0.65F, -0.75F, 0.85F);

        // Battery bay hatch opens upward during 1.5s magnetic battery swap
        if (mode == ReconDroneEntity.MODE_SWAPPING) {
            float swapProgress = 1.0F - Mth.clamp((drone.modeTicks() - partialTick) / DroneRules.BATTERY_SWAP_TICKS, 0.0F, 1.0F);
            float openCurve = swapProgress < 0.2F ? swapProgress / 0.2F
                    : (swapProgress > 0.85F ? (1.0F - swapProgress) / 0.15F : 1.0F);
            batteryHatch.xRot = -1.1F * Mth.clamp(openCurve, 0.0F, 1.0F);
        } else {
            batteryHatch.xRot = 0.0F;
        }

        // Rotor spin: fast in flight, faster when sprinting, stopped when crashed or scrapped
        float spinRate = (mode == ReconDroneEntity.MODE_CRASHED || mode == ReconDroneEntity.MODE_SCRAPPED) ? 0.0F
                : (drone.isDroneSprinting() ? 2.35F : 1.55F) * unfold;
        float spin = ageInTicks * spinRate;
        rotorFL.yRot = spin;
        rotorBR.yRot = spin;
        rotorFR.yRot = -spin;
        rotorBL.yRot = -spin;

        // Belly mechanical claws clamp inward when holding a payload
        boolean holding = drone.payloadCategory().hasPayload();
        float grip = holding ? 0.22F : -0.14F;
        clawLeft.zRot = -grip;
        clawRight.zRot = grip;
    }

    public void renderBody(PoseStack pose, VertexConsumer consumer, int light, int overlay, int argb) {
        chassis.render(pose, consumer, light, overlay, argb);
    }

    public static ResourceLocation texture() {
        if (cachedTexture != null) {
            return cachedTexture;
        }
        NativeImage img = new NativeImage(64, 64, true);
        // ABGR colors in NativeImage.setPixelRGBA
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                boolean checker = ((x + y) & 1) == 0;
                img.setPixelRGBA(x, y, DroneEquipmentModels.argbToAbgr(checker ? 0xFF1E293B : 0xFF0F172A));
            }
        }
        // Cyan tactical trim stripe on hull & dome
        DroneEquipmentModels.fillRect(img, 0, 12, 16, 18, 0xFF0EA5E9);
        DroneEquipmentModels.fillRect(img, 2, 2, 18, 4, 0xFF38BDF8);
        // Gimbal metallic + cyan lens
        DroneEquipmentModels.fillRect(img, 24, 0, 35, 6, 0xFF334155);
        DroneEquipmentModels.fillRect(img, 25, 1, 29, 4, 0xFF38BDF8);
        // Battery hatch titanium + stripe
        DroneEquipmentModels.fillRect(img, 24, 6, 36, 12, 0xFF475569);
        DroneEquipmentModels.fillRect(img, 26, 7, 32, 9, 0xFF38BDF8);
        // Rotor blades semi-translucent cyan-silver
        DroneEquipmentModels.fillRect(img, 0, 28, 32, 40, 0xB894A3B8);
        DroneEquipmentModels.fillRect(img, 0, 28, 6, 32, 0xD838BDF8);
        // Belly claws industrial dark steel + hazard trim
        DroneEquipmentModels.fillRect(img, 36, 0, 56, 16, 0xFF334155);
        DroneEquipmentModels.fillRect(img, 36, 8, 52, 12, 0xFF64748B);

        cachedTexture = HabiTrainCore.id("textures/entity/recon_drone_chassis");
        Minecraft.getInstance().getTextureManager().register(cachedTexture, new DynamicTexture(img));
        return cachedTexture;
    }
}
