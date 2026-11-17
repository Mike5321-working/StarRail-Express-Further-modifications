package com.habitrain.core.client.drone;

import com.habitrain.core.game.sre.role.drone.DronePayloadCategory;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import com.habitrain.core.game.sre.role.drone.ReconDroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.wifi.starrailexpress.index.TMMItems;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Renders {@link ReconDroneEntity} with its Triple High-Visibility 3P Payload Presentation:
 * <ol>
 *   <li>1.35x belly mechanical claw item + category-specific 3D hardware (IR/snipe red laser,
 *       bayonet crimson trail + glint, alternating red/yellow explosive strobe, fluorescent
 *       green poison vial/needle, yellow nozzle, gold decoder pincer, purple containment grid);</li>
 *   <li>Fullbright ({@code 15728880}) 3D billboard floating diamond hologram badge at {@code +0.46Y}
 *       (1:1 identical crimson double-frame + {@code !} for both real lethal and fake weapons);</li>
 *   <li>Horizontal rotating translucent colored warning ring around the rotor plane + charred
 *       fused belly state.</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class ReconDroneRenderer extends EntityRenderer<ReconDroneEntity> {

    private final ReconDroneModel model = new ReconDroneModel();
    private final ItemRenderer itemRenderer;

    public ReconDroneRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        this.shadowRadius = 0.25F;
    }

    @Override
    public ResourceLocation getTextureLocation(ReconDroneEntity entity) {
        return ReconDroneModel.texture();
    }

    @Override
    public void render(
            ReconDroneEntity drone,
            float entityYaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight
    ) {
        if (DroneOperatorClient.shouldHideOwnDroneMeshInFpv(drone)) {
            return;
        }

        float age = drone.tickCount + partialTick;
        float yaw = Mth.rotLerp(partialTick, drone.yRotO, drone.getYRot());
        float pitch = Mth.lerp(partialTick, drone.xRotO, drone.getXRot());
        DronePayloadCategory category = drone.payloadCategory();
        ItemStack payload = drone.payloadStack();

        // Hover bobbing when airborne
        boolean groundedWreck = drone.isCrashed() || drone.isScrapped();
        float hoverBob = groundedWreck ? 0.0F : Mth.sin(age * 0.28F) * 0.025F;
        float recoilKick = DroneOperatorClient.getDroneRecoilPitch(drone.getId(), partialTick);

        pose.pushPose();
        pose.translate(0.0D, 0.18D + hoverBob, 0.0D);

        // --- 1. Drone Chassis, Rotors & Tilt ---
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));

        float flightPitch = drone.forwardTilt() * 14.0F - recoilKick * 22.0F;
        float flightRoll = -drone.strafeTilt() * 16.0F;
        if (drone.isScrapped()) {
            flightPitch = 22.0F;
            flightRoll = -26.0F;
        } else if (drone.isCrashed()) {
            flightPitch = 12.0F;
            flightRoll = 18.0F;
        }
        pose.mulPose(Axis.XP.rotationDegrees(flightPitch));
        pose.mulPose(Axis.ZP.rotationDegrees(flightRoll));

        pose.pushPose();
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0.0F, -1.35F, 0.0F);
        model.setupAnim(drone, age, partialTick);
        VertexConsumer hullBuf = buffers.getBuffer(RenderType.entityTranslucent(getTextureLocation(drone)));
        int hullTint = drone.isScrapped() ? 0xFF788496 : 0xFFFFFFFF;
        model.renderBody(pose, hullBuf, packedLight, OverlayTexture.NO_OVERLAY, hullTint);
        pose.popPose();

        // Fullbright nose camera optic lens + rear battery status LED
        renderChassisGlowLeds(drone, age, pose, buffers);

        // --- 2. Belly Payload 1.35x Item + Category Custom 3D Attachments ---
        if (category.hasPayload() && !payload.isEmpty()) {
            renderBellyAttachmentAndItem(drone, category, payload, age, pitch, pose, buffers);
        }

        // --- 3. Horizontal Rotating Translucent Warning Ring around Rotor Plane ---
        renderHorizontalWarningRing(drone, category, age, pose, buffers);
        pose.popPose();

        // --- 4. World-Aligned Gun / Fake-Gun IR Laser Sight ---
        if (category == DronePayloadCategory.LETHAL_GUN || category == DronePayloadCategory.FAKE_GUN) {
            renderGunInfraredLaser(drone, age, partialTick, pose, buffers);
        }

        // --- 5. Fullbright 3D Billboard Floating Diamond Badge (+0.46Y) ---
        if (category.hasPayload() && !payload.isEmpty()) {
            renderFloatingBillboardBadge(drone, category, payload, age, pose, buffers);
        }

        pose.popPose();
        super.render(drone, entityYaw, partialTick, pose, buffers, packedLight);
    }

    private void renderChassisGlowLeds(ReconDroneEntity drone, float age, PoseStack pose, MultiBufferSource buffers) {
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f mat = pose.last().pose();

        // Nose optic cyan lens (or crimson during lethal charge / sparking short-circuit when scrapped!)
        int lensColor = drone.isScrapped()
                ? (((int) age % 7 == 0) ? 0xFFFACC15 : 0xFF1E293B)
                : (drone.chargeTicks() > 0 ? 0xFFFF2442 : (drone.isCrashed() ? 0xFF334155 : 0xFF38BDF8));
        drawBox(mat, vc, -0.055F, -0.035F, -0.34F, 0.055F, 0.035F, -0.31F, lensColor);

        // Rear battery LED
        int bat = drone.battery();
        int ledColor;
        if (bat <= 0) {
            ledColor = ((int) age % 8 < 3) ? 0xFFEF4444 : 0xFF1E293B;
        } else if (DroneRules.isLowBattery(bat)) {
            ledColor = ((int) age % 6 < 3) ? 0xFFFF2E4C : 0xFF7F1D1D;
        } else if (bat < DroneRules.MAX_BATTERY / 2) {
            ledColor = 0xFFFBBF24;
        } else {
            ledColor = 0xFF22C55E;
        }
        drawBox(mat, vc, -0.06F, 0.02F, 0.30F, 0.06F, 0.06F, 0.33F, ledColor);
    }

    private void renderBellyAttachmentAndItem(
            ReconDroneEntity drone,
            DronePayloadCategory category,
            ItemStack payload,
            float age,
            float pitch,
            PoseStack pose,
            MultiBufferSource buffers
    ) {
        // 1.35x fullbright item clamped under belly claws
        pose.pushPose();
        pose.translate(0.0F, -0.22F, 0.0F);
        if (category == DronePayloadCategory.LETHAL_BLADE || category == DronePayloadCategory.FAKE_BLADE) {
            pose.translate(0.0F, 0.03F, -0.18F);
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(-45.0F));
        } else if (category == DronePayloadCategory.LETHAL_GUN || category == DronePayloadCategory.FAKE_GUN) {
            pose.translate(0.0F, 0.02F, -0.08F);
            pose.mulPose(Axis.YP.rotationDegrees(90.0F));
        } else {
            pose.mulPose(Axis.YP.rotationDegrees(age * 1.8F));
        }
        pose.scale(1.35F, 1.35F, 1.35F);
        itemRenderer.renderStatic(payload, ItemDisplayContext.FIXED,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, drone.level(), drone.getId());
        pose.popPose();

        // Category-specific custom 3D hardware
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f mat = pose.last().pose();

        switch (category) {
            case LETHAL_GUN, FAKE_GUN -> {
                // Dark gunmetal under-slung barrel rail + laser emitter pod
                drawBox(mat, vc, -0.04F, -0.20F, -0.44F, 0.04F, -0.13F, 0.08F, 0xEE1E293B);
                drawBox(mat, vc, -0.025F, -0.23F, -0.36F, 0.025F, -0.19F, -0.16F, 0xFFFF2442);
            }
            case LETHAL_BLADE, FAKE_BLADE -> {
                // Forward bayonet rail + crimson edge glow + metallic tip glint
                drawBox(mat, vc, -0.02F, -0.19F, -0.52F, 0.02F, -0.15F, -0.10F, 0xFFE2E8F0);
                int edgeAlpha = drone.chargeTicks() > 0 ? 0xFFFF1E38 : 0xCCEF4444;
                drawBox(mat, vc, -0.035F, -0.20F, -0.54F, 0.035F, -0.14F, -0.24F, edgeAlpha);
                float glint = Mth.sin(age * 0.45F);
                if (glint > 0.65F) {
                    float s = (glint - 0.65F) * 0.28F;
                    drawBox(mat, vc, -s, -0.17F - s, -0.55F, s, -0.17F + s, -0.53F, 0xFFFFFFFF);
                }
            }
            case LETHAL_EXPLOSIVE, FAKE_EXPLOSIVE -> {
                // Alternating red / yellow high-frequency strobe beacon
                boolean redPhase = ((int) (age * 0.5F) & 1) == 0;
                int strobe = redPhase ? 0xFFFF1E2E : 0xFFFACC15;
                drawBox(mat, vc, -0.075F, -0.31F, -0.075F, 0.075F, -0.22F, 0.075F, strobe);
                drawBox(mat, vc, -0.11F, -0.33F, -0.11F, 0.11F, -0.20F, 0.11F, (strobe & 0x00FFFFFF) | 0x55000000);
            }
            case LETHAL_POISON -> {
                // Translucent fluorescent green toxin canister + downward micro-syringe needle
                drawBox(mat, vc, -0.065F, -0.27F, -0.065F, 0.065F, -0.15F, 0.065F, 0xCC22C55E);
                drawBox(mat, vc, -0.012F, -0.39F, -0.012F, 0.012F, -0.27F, 0.012F, 0xFF86EFAC);
            }
            case CONTROL, DECOY_SMOKE -> {
                // Bright lemon-yellow / amber tactical dispersal nozzle
                drawBox(mat, vc, -0.07F, -0.28F, -0.07F, 0.07F, -0.17F, 0.07F, 0xEEFACC15);
            }
            case INFILTRATION_LOGISTICS -> {
                if (payload.is(TMMItems.BODY_BAG)) {
                    // Dark-purple containment grid
                    drawBox(mat, vc, -0.11F, -0.28F, -0.11F, 0.11F, -0.16F, 0.11F, 0xAA9333EA);
                } else if (payload.is(TMMItems.LOCKPICK) || payload.is(TMMItems.CROWBAR)
                        || payload.is(TMMItems.MONITOR_BROKEN) || payload.is(TMMItems.BLACKOUT)) {
                    // Golden dual-pincer cyber decoder arm
                    drawBox(mat, vc, -0.08F, -0.22F, -0.36F, -0.05F, -0.18F, -0.12F, 0xFFFBBF24);
                    drawBox(mat, vc, 0.05F, -0.22F, -0.36F, 0.08F, -0.18F, -0.12F, 0xFFFBBF24);
                } else {
                    // Cyan-gold cargo clamp
                    drawBox(mat, vc, -0.08F, -0.27F, -0.08F, 0.08F, -0.17F, 0.08F, 0xAA38BDF8);
                }
            }
            default -> {}
        }
    }

    private void renderHorizontalWarningRing(
            ReconDroneEntity drone,
            DronePayloadCategory category,
            float age,
            PoseStack pose,
            MultiBufferSource buffers
    ) {
        if (drone.isCrashed() || drone.isScrapped()) return;

        int baseRgb;
        int alpha;
        float rotSpeed;
        if (category.alertTier() == DronePayloadCategory.AlertTier.RED_DANGER) {
            baseRgb = 0xFF2442;
            alpha = 155 + (int) (75.0F * Mth.sin(age * 0.55F));
            rotSpeed = 4.2F;
        } else if (category.alertTier() == DronePayloadCategory.AlertTier.AMBER_CONTROL) {
            baseRgb = 0xFBBF24;
            alpha = 150;
            rotSpeed = 2.6F;
        } else if (category.alertTier() == DronePayloadCategory.AlertTier.CYAN_UTILITY) {
            baseRgb = 0x38BDF8;
            alpha = 150;
            rotSpeed = 2.2F;
        } else {
            baseRgb = 0x38BDF8;
            alpha = 65;
            rotSpeed = 1.1F;
        }

        int argb = (Mth.clamp(alpha, 30, 235) << 24) | baseRgb;
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());

        pose.pushPose();
        pose.translate(0.0F, 0.06F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(age * rotSpeed));
        Matrix4f mat = pose.last().pose();

        int segments = 16;
        float rInner = 0.52F;
        float rOuter = 0.59F;
        for (int i = 0; i < segments; i++) {
            // Dashed tactical arc look (skip every 4th segment when carrying payload for high-contrast motion)
            if (category.hasPayload() && (i % 4 == 3)) continue;
            float a0 = (float) (i * Math.PI * 2.0D / segments);
            float a1 = (float) ((i + 0.82D) * Math.PI * 2.0D / segments);
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1), s1 = Mth.sin(a1);

            addDoubleSidedQuad(mat, vc,
                    c0 * rInner, 0.0F, s0 * rInner,
                    c1 * rInner, 0.0F, s1 * rInner,
                    c1 * rOuter, 0.0F, s1 * rOuter,
                    c0 * rOuter, 0.0F, s0 * rOuter,
                    argb);
        }
        pose.popPose();
    }

    private void renderGunInfraredLaser(
            ReconDroneEntity drone,
            float age,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers
    ) {
        if (drone.isCrashed() || drone.isScrapped()) return;
        boolean charging = drone.chargeTicks() > 0;
        double maxDist = charging ? 18.0D : 6.0D;

        float yaw = Mth.rotLerp(partialTick, drone.yRotO, drone.getYRot());
        float pitch = Mth.lerp(partialTick, drone.xRotO, drone.getXRot());
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw).normalize();
        Vec3 startWorld = drone.getPosition(partialTick).add(0.0D, 0.14D, 0.0D).add(dir.scale(0.32D));
        BlockHitResult clip = drone.level().clip(new ClipContext(
                startWorld, startWorld.add(dir.scale(maxDist)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone
        ));
        double length = clip.getType() == HitResult.Type.MISS
                ? maxDist
                : startWorld.distanceTo(clip.getLocation());
        if (length < 0.1D) return;

        pose.pushPose();
        pose.translate(0.0F, -0.04F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));

        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f mat = pose.last().pose();
        float halfThickness = charging ? 0.032F : 0.011F;
        int beamColor = charging ? 0xEEFF1E38 : 0x88DC2626;
        float z0 = 0.28F;
        float z1 = (float) (0.28D + length);

        // Two perpendicular intersecting quads so the laser is visible from any 3P angle
        addDoubleSidedQuad(mat, vc, -halfThickness, 0.0F, z0, halfThickness, 0.0F, z0,
                halfThickness, 0.0F, z1, -halfThickness, 0.0F, z1, beamColor);
        addDoubleSidedQuad(mat, vc, 0.0F, -halfThickness, z0, 0.0F, halfThickness, z0,
                0.0F, halfThickness, z1, 0.0F, -halfThickness, z1, beamColor);

        if (charging) {
            // Bright white-hot core during 0.5s snipe lock
            float core = 0.010F;
            addDoubleSidedQuad(mat, vc, -core, 0.0F, z0, core, 0.0F, z0, core, 0.0F, z1, -core, 0.0F, z1, 0xEEFFFFFF);
        }
        pose.popPose();
    }

    private void renderFloatingBillboardBadge(
            ReconDroneEntity drone,
            DronePayloadCategory category,
            ItemStack payload,
            float age,
            PoseStack pose,
            MultiBufferSource buffers
    ) {
        DronePayloadCategory.AlertTier tier = category.alertTier();
        int borderRgb = tier.rgb();
        boolean danger = tier == DronePayloadCategory.AlertTier.RED_DANGER;
        float pulse = danger ? (1.0F + 0.08F * Mth.sin(age * 0.55F)) : 1.0F;

        pose.pushPose();
        pose.translate(0.0D, 0.48D, 0.0D);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.scale(0.42F * pulse, 0.42F * pulse, 0.42F);

        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());

        // Rotated 45-degree diamond hologram frame
        pose.pushPose();
        pose.mulPose(Axis.ZP.rotationDegrees(45.0F));
        Matrix4f dMat = pose.last().pose();

        int bgCol = danger ? 0xAA2A0810 : 0x99081826;
        int mainBorder = (0xEE << 24) | borderRgb;
        float r = 0.44F;
        addDoubleSidedQuad(dMat, vc, -r, -r, -0.02F, r, -r, -0.02F, r, r, -0.02F, -r, r, -0.02F, bgCol);
        drawFrame2D(dMat, vc, -r, -r, r, r, 0.045F, -0.015F, mainBorder);
        if (danger) {
            // Outer double-border for lethal & fake weapons (1:1 identical!)
            float r2 = 0.53F;
            int outerAlpha = (140 + (int) (85 * Mth.sin(age * 0.6F))) << 24 | borderRgb;
            drawFrame2D(dMat, vc, -r2, -r2, r2, r2, 0.028F, -0.015F, outerAlpha);
        }
        pose.popPose();

        // Crisp fullbright 2D item icon centered inside diamond
        pose.pushPose();
        pose.scale(0.62F, 0.62F, 0.05F);
        itemRenderer.renderStatic(payload, ItemDisplayContext.GUI,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, drone.level(), drone.getId());
        pose.popPose();

        // Bold fullbright "!" above red danger diamonds
        if (danger) {
            Font font = Minecraft.getInstance().font;
            pose.pushPose();
            pose.translate(0.0F, 0.86F, 0.01F);
            pose.scale(0.042F, -0.042F, 0.042F);
            String mark = "!";
            float xOff = -font.width(mark) / 2.0F;
            font.drawInBatch(mark, xOff, 0.0F, 0xFFFF3B5C, false,
                    pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }

        pose.popPose();
    }

    private static void drawFrame2D(Matrix4f mat, VertexConsumer vc, float x0, float y0, float x1, float y1, float t, float z, int argb) {
        addDoubleSidedQuad(mat, vc, x0, y0, z, x1, y0, z, x1, y0 + t, z, x0, y0 + t, z, argb);
        addDoubleSidedQuad(mat, vc, x0, y1 - t, z, x1, y1 - t, z, x1, y1, z, x0, y1, z, argb);
        addDoubleSidedQuad(mat, vc, x0, y0 + t, z, x0 + t, y0 + t, z, x0 + t, y1 - t, z, x0, y1 - t, z, argb);
        addDoubleSidedQuad(mat, vc, x1 - t, y0 + t, z, x1, y0 + t, z, x1, y1 - t, z, x1 - t, y1 - t, z, argb);
    }

    private static void drawBox(Matrix4f mat, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        addDoubleSidedQuad(mat, vc, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, argb);
        addDoubleSidedQuad(mat, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, argb);
        addDoubleSidedQuad(mat, vc, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, argb);
        addDoubleSidedQuad(mat, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, argb);
        addDoubleSidedQuad(mat, vc, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, argb);
        addDoubleSidedQuad(mat, vc, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, argb);
    }

    private static void addDoubleSidedQuad(
            Matrix4f mat, VertexConsumer vc,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            int argb
    ) {
        vc.addVertex(mat, x0, y0, z0).setColor(argb);
        vc.addVertex(mat, x1, y1, z1).setColor(argb);
        vc.addVertex(mat, x2, y2, z2).setColor(argb);
        vc.addVertex(mat, x3, y3, z3).setColor(argb);

        vc.addVertex(mat, x3, y3, z3).setColor(argb);
        vc.addVertex(mat, x2, y2, z2).setColor(argb);
        vc.addVertex(mat, x1, y1, z1).setColor(argb);
        vc.addVertex(mat, x0, y0, z0).setColor(argb);
    }
}
