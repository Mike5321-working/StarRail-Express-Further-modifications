package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.drone.ReconDroneModel;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class ReplayDronePainter {
    private static ReconDroneModel model;

    private ReplayDronePainter() {}

    static void paint(ReplayFrame f, ReplayBoardSite site, Matrix4f map) {
        if (f.model == null || f.stage == null || f.stage.actors() < 0.05) return;
        float[] point = new float[3];
        for (var drone : f.model.drones) {
            var pose = drone.track().sample(f.replayTick);
            if (pose.state() != ReplayTrackSampler.State.ALIVE) continue;
            f.model.project(pose.x(), pose.y(), pose.z(), point);
            boolean wreck = pose.has(LastMatchReplay.Drone.CRASHED) || pose.has(LastMatchReplay.Drone.SCRAPPED);
            float yaw = pose.yaw() - (f.model.rotated ? 90 : 0);
            var anchor = new Matrix4f(map).translate(point[0], point[1] + 0.10f, point[2]);
            f.model(new Matrix4f(anchor).scale(0.46f), stack -> {
                if (model == null) model = new ReconDroneModel();
                stack.mulPose(Axis.YP.rotationDegrees(180 - yaw));
                if (wreck) stack.mulPose(Axis.ZP.rotationDegrees(pose.has(LastMatchReplay.Drone.SCRAPPED) ? -26 : 18));
                stack.scale(-1, -1, 1);
                stack.translate(0, -1.35, 0);
                float spin = wreck ? 0 : (float) (f.anim * (pose.has(LastMatchReplay.SPRINT) ? 47 : 31));
                model.rotorFL.yRot = model.rotorBR.yRot = spin;
                model.rotorFR.yRot = model.rotorBL.yRot = -spin;
                model.renderBody(stack, f.modelBuffers().getBuffer(RenderType.entityTranslucent(ReconDroneModel.texture())),
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        ReplayPalette.alpha(wreck ? 0xFF8995A6 : 0xFFFFFFFF, site.fade() * f.stage.actors()));
            });
            if (site.detail() && drone.owner() != null) {
                Vector3f label = anchor.transformPosition(0, .20f, 0, new Vector3f());
                f.billboardText(label, ReplayBoardTexts.tr("drone", drone.owner().name),
                        0.012f * site.scale(), ReplayPalette.alpha(0xFF8EF1FF, site.fade() * f.stage.actors()));
            }
        }
    }
}
