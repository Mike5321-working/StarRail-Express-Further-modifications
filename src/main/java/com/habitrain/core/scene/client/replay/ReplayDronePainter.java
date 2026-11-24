package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.drone.ReconDroneModel;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Renders deployed recon drones on both the miniature diorama and 1:1 POV worlds, including
 * spinning rotors, crash/scrap posture, cyan volumetric scan cone, and pulsing telemetry ring.
 */
final class ReplayDronePainter {
    private static ReconDroneModel model;
    private static final int DRONE_CYAN = 0xFF38BDF8;
    private static final int DRONE_ALERT = 0xFF8EF1FF;

    private ReplayDronePainter() {}

    static void paint(ReplayFrame f, ReplayBoardSite site, Matrix4f map) {
        if (f.model == null || f.stage == null || f.stage.actors() < 0.05) return;
        float[] point = new float[3];
        double alpha = site.fade() * f.stage.actors();
        VertexConsumer glow = f.glow();
        for (var drone : f.model.drones) {
            var pose = drone.track().sample(f.replayTick);
            if (pose.state() != ReplayTrackSampler.State.ALIVE) continue;
            f.model.project(pose.x(), pose.y(), pose.z(), point);
            boolean wreck = pose.has(LastMatchReplay.Drone.CRASHED) || pose.has(LastMatchReplay.Drone.SCRAPPED);
            boolean piloted = pose.has(LastMatchReplay.Drone.PILOTED);
            float yaw = pose.yaw() - (f.model.rotated ? 90 : 0);
            float dx = point[0], dy = point[1] + 0.10f, dz = point[2];
            var anchor = new Matrix4f(map).translate(dx, dy, dz);
            f.model(new Matrix4f(anchor).scale(0.46f), stack ->
                    renderDroneBody(stack, f.modelBuffers(), yaw, 0f, f.anim,
                            pose.has(LastMatchReplay.SPRINT), wreck, pose.has(LastMatchReplay.Drone.SCRAPPED),
                            alpha, LightTexture.FULL_BRIGHT));
            if (!wreck) {
                emitScanCone(glow, map, dx, dy, dz, yaw, piloted, f.anim,
                        Math.max(0.14f, f.model.scale * 2.4f), Math.max(0.08f, f.model.scale * 1.4f), alpha);
            } else {
                // Wreckage faint ember ring
                ReplayDraw.ring(glow, map, dx, point[1] + 0.01f, dz, 0.07f, 0.015f, 16,
                        ReplayPalette.alpha(ReplayPalette.EXPLOSION, 0.45 * alpha),
                        ReplayPalette.alpha(ReplayPalette.EXPLOSION, 0));
            }
            if (site.detail() && drone.owner() != null && !f.video) {
                Vector3f label = anchor.transformPosition(0, .20f, 0, new Vector3f());
                f.billboardText(label, ReplayBoardTexts.tr("drone", drone.owner().name),
                        0.012f * site.scale(), ReplayPalette.alpha(DRONE_ALERT, alpha));
            }
        }
        // Automatically render v2 non-player entities and skill/fallback effects if not already painted explicitly
        if (!f.entitiesPainted) {
            ReplayEntityPainter.paintDiorama(f, site, map);
        }
        if (!f.skillsPainted) {
            ReplaySkillEffectRegistry.paintDiorama(f, site, map, true);
        }
    }

    /** Renders a 1:1 world-scale recon drone inside a POV scene. */
    static void renderWorldDrone(PoseStack stack, MultiBufferSource buffers, VertexConsumer glow,
                                 Matrix4f worldToCam, float x, float y, float z, float yawDeg, float pitchDeg,
                                 double animSec, boolean sprinting, boolean piloted, boolean crashed, boolean scrapped,
                                 double alpha, int light) {
        boolean wreck = crashed || scrapped;
        stack.pushPose();
        stack.translate(x, y, z);
        renderDroneBody(stack, buffers, yawDeg, pitchDeg, animSec, sprinting, wreck, scrapped, alpha, light);
        stack.popPose();
        if (!wreck) {
            emitScanCone(glow, worldToCam, x, y, z, yawDeg, piloted, animSec, 3.2f, 1.55f, alpha);
        }
    }

    private static void renderDroneBody(PoseStack stack, MultiBufferSource buffers, float yawDeg, float pitchDeg,
                                        double animSec, boolean sprinting, boolean wreck, boolean scrapped,
                                        double alpha, int light) {
        if (model == null) model = new ReconDroneModel();
        stack.mulPose(Axis.YP.rotationDegrees(180f - yawDeg));
        if (wreck) {
            stack.mulPose(Axis.ZP.rotationDegrees(scrapped ? -26f : 18f));
        } else if (Math.abs(pitchDeg) > 0.5f) {
            stack.mulPose(Axis.XP.rotationDegrees(pitchDeg * 0.35f));
        }
        stack.scale(-1f, -1f, 1f);
        stack.translate(0f, -1.35f, 0f);
        float spin = wreck ? 0f : (float) (animSec * (sprinting ? 47.0 : 31.0));
        model.rotorFL.yRot = model.rotorBR.yRot = spin;
        model.rotorFR.yRot = model.rotorBL.yRot = -spin;
        model.renderBody(stack, buffers.getBuffer(RenderType.entityTranslucent(ReconDroneModel.texture())),
                light, OverlayTexture.NO_OVERLAY,
                ReplayPalette.alpha(wreck ? 0xFF8995A6 : 0xFFFFFFFF, alpha));
    }

    private static void emitScanCone(VertexConsumer glow, Matrix4f frame, float x, float y, float z,
                                     float yawDeg, boolean piloted, double animSec,
                                     float coneLength, float baseRadius, double alpha) {
        if (alpha <= 0.03) return;
        double rad = Math.toRadians(yawDeg);
        // Forward-downward cyan reconnaissance cone
        float fx = (float) (-Math.sin(rad)) * coneLength * 0.55f;
        float fz = (float) (Math.cos(rad)) * coneLength * 0.55f;
        float fy = -coneLength * 0.72f;
        double pulse = 0.72 + 0.28 * Math.sin(animSec * (piloted ? 5.5 : 2.8));
        int apex = ReplayPalette.alpha(DRONE_ALERT, (piloted ? 0.34 : 0.18) * pulse * alpha);
        int base = ReplayPalette.alpha(DRONE_CYAN, 0.02 * alpha);
        ReplayDraw.cone(glow, frame, x, y - 0.02f, z, x + fx, y + fy, z + fz, baseRadius, 18, apex, base);
        ReplayDraw.ring(glow, frame, x + fx, Math.max(0.01f, y + fy), z + fz,
                baseRadius * (float) (0.78 + 0.18 * ((animSec * 0.9) % 1.0)),
                baseRadius * 0.08f, 20,
                ReplayPalette.alpha(DRONE_CYAN, (piloted ? 0.55 : 0.28) * alpha),
                ReplayPalette.alpha(DRONE_CYAN, 0));
    }
}
