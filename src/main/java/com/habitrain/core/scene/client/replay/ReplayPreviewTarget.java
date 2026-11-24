package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.client.render.ClientRenderState;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Objects;
import java.util.UUID;

/**
 * Offscreen 2x supersampled 3D diorama preview renderer for {@link ReplayHistoryScreen}.
 *
 * <p>Supports global orbit viewing, optional kill close-up camera tracking (off by default),
 * and smooth over-the-shoulder behind-player follow camera aligned with the lobby replay table.</p>
 */
final class ReplayPreviewTarget {
    private static final int MAX_FBO_DIM = 4096;

    private TextureTarget target;
    private boolean hasCamera;
    private @Nullable UUID lastFollow;
    private @Nullable UUID lastRound;
    private long lastDrawNanos;
    private double curFocusX, curFocusY, curFocusZ;
    private double curYaw, curPitch, curDist;

    void resetCamera() {
        hasCamera = false;
        lastDrawNanos = 0;
    }

    void draw(GuiGraphics g, Minecraft mc, ReplayBoardModel model, ReplayTimeline.Frame frame, ReplayStagePlan.Stage stage,
              SceneMeshSet mesh, @Nullable ReplayDioramaMesh.DynamicWorld dynamicWorld,
              int x, int y, int w, int h,
              double orbitYaw, double orbitPitch, double zoom,
              @Nullable UUID followPlayerUuid, boolean killCloseupEnabled, boolean roofCutaway) {
        if (w <= 0 || h <= 0) return;
        g.flush();
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        int lightTexture = RenderSystem.getShaderTexture(2);
        var projection = RenderSystem.getProjectionMatrix();
        var sorting = RenderSystem.getVertexSorting();
        var matrices = RenderSystem.getModelViewStack().pushMatrix();
        try (var guard = ClientRenderState.capture()) {
            int tw = Math.min(MAX_FBO_DIM, Math.max(2, w * 2));
            int th = Math.min(MAX_FBO_DIM, Math.max(2, h * 2));
            if (target == null || target.width != tw || target.height != th) {
                close();
                target = new TextureTarget(tw, th, true, Minecraft.ON_OSX);
                target.setClearColor(.028f, .048f, .095f, 1f);
            }
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            var shot = camera(model, frame, mesh, orbitYaw, orbitPitch, zoom, (float) w / h,
                    followPlayerUuid, killCloseupEnabled);
            matrices.set(shot.view());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(shot.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            FogRenderer.setupNoFog();
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            ReplayBoardRenderer.drawPreviewFrame(mc, model, frame, stage, frame.replayTick() / 20.0, shot, mesh,
                    dynamicWorld, !roofCutaway, followPlayerUuid);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            RenderSystem.viewport(vp[0], vp[1], vp[2], vp[3]);
            matrices.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderTexture(2, lightTexture);
        }
        try (var guard = ClientRenderState.capture()) {
            RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, target.getColorTextureId());
            RenderSystem.setShaderColor(1, 1, 1, 1);
            var m = g.pose().last().pose();
            var b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            b.addVertex(m, x, y, 0).setUv(0, 1);
            b.addVertex(m, x, y + h, 0).setUv(0, 0);
            b.addVertex(m, x + w, y + h, 0).setUv(1, 0);
            b.addVertex(m, x + w, y, 0).setUv(1, 1);
            BufferUploader.drawWithShader(b.buildOrThrow());
        }
    }

    private ReplayVideoCamera.Shot camera(ReplayBoardModel model, ReplayTimeline.Frame frame, SceneMeshSet mesh,
                                          double orbitYaw, double orbitPitch, double zoom, float aspect,
                                          @Nullable UUID followPlayerUuid, boolean killCloseupEnabled) {
        float radius = ReplayBoardRenderer.trayHalfSize(model);
        double defaultFocusY = ReplayBoardSite.MAP_BASE + model.mapH * 0.28;
        double targetFocusX = 0.0, targetFocusY = defaultFocusY, targetFocusZ = 0.0;
        double targetYaw = orbitYaw;
        double targetPitch = orbitPitch;
        double targetDist = radius * (3.5 / Math.max(0.25, zoom)) / Math.min(1.0, aspect);
        boolean followingVisible = false;

        if (followPlayerUuid != null) {
            ReplayBoardModel.Actor actor = model.participant(followPlayerUuid);
            if (actor != null) {
                var pose = actor.track.sample(frame.replayTick());
                if (pose.visible()) {
                    float[] proj = new float[3];
                    model.project(pose.x(), pose.y() + 1.1, pose.z(), proj);
                    double yawRad = Math.toRadians(pose.headYawDegrees());
                    double fx = -Math.sin(yawRad);
                    double fz = Math.cos(yawRad);
                    double dx = model.rotated ? fz : fx;
                    double dz = model.rotated ? -fx : fz;

                    // Look slightly ahead of the followed player so they sit cleanly in lower-center foreground
                    double lead = Math.min(0.24, Math.max(0.08, model.scale * 2.2));
                    targetFocusX = proj[0] + dx * lead;
                    targetFocusY = ReplayBoardSite.MAP_BASE + proj[1];
                    targetFocusZ = proj[2] + dz * lead;
                    // Position camera directly behind player's shoulder (+ subtle user drag offset)
                    targetYaw = Math.atan2(-dx, -dz) + orbitYaw;
                    targetPitch = Math.clamp(orbitPitch, 0.16, 1.32);
                    targetDist = Math.clamp(radius * (0.68 / Math.max(0.35, zoom)) / Math.min(1.0, aspect),
                            Math.max(0.30, model.scale * 5.0), radius * 2.1);
                    followingVisible = true;
                }
            }
        } else if (killCloseupEnabled) {
            CloseupTarget closeup = findActiveKillCloseup(model, frame.replayTick());
            if (closeup != null && closeup.weight() > 0.01) {
                double w = closeup.weight();
                targetFocusX = lerp(0.0, closeup.x(), w);
                targetFocusY = lerp(defaultFocusY, ReplayBoardSite.MAP_BASE + closeup.y(), w);
                targetFocusZ = lerp(0.0, closeup.z(), w);
                double closeDist = Math.clamp(radius * 0.85 / Math.min(1.0, aspect), 0.55, targetDist);
                targetDist = lerp(targetDist, closeDist, w);
                targetPitch = lerp(targetPitch, Math.min(targetPitch, 0.58), w * 0.65);
            }
        }

        long now = System.nanoTime();
        double dt = lastDrawNanos == 0 ? 0.016 : Math.clamp((now - lastDrawNanos) / 1_000_000_000.0, 0.001, 0.12);
        lastDrawNanos = now;

        boolean roundChanged = !Objects.equals(lastRound, model.replay.roundId());
        boolean followChanged = !Objects.equals(lastFollow, followPlayerUuid);
        lastRound = model.replay.roundId();
        lastFollow = followPlayerUuid;

        if (!hasCamera || roundChanged) {
            curFocusX = targetFocusX;
            curFocusY = targetFocusY;
            curFocusZ = targetFocusZ;
            curYaw = targetYaw;
            curPitch = targetPitch;
            curDist = targetDist;
            hasCamera = true;
        } else {
            double posRate = followingVisible ? 10.5 : (followChanged ? 8.0 : 12.0);
            double angRate = followingVisible ? 7.5 : 14.0;
            double alphaPos = 1.0 - Math.exp(-posRate * dt);
            double alphaAng = 1.0 - Math.exp(-angRate * dt);
            curFocusX = lerp(curFocusX, targetFocusX, alphaPos);
            curFocusY = lerp(curFocusY, targetFocusY, alphaPos);
            curFocusZ = lerp(curFocusZ, targetFocusZ, alphaPos);
            curYaw = lerpAngle(curYaw, targetYaw, alphaAng);
            curPitch = lerp(curPitch, targetPitch, alphaAng);
            curDist = lerp(curDist, targetDist, alphaPos);
        }

        double horizontal = curDist * Math.cos(curPitch);
        var eye = new Vector3f(
                (float) (curFocusX + horizontal * Math.sin(curYaw)),
                (float) (curFocusY + curDist * Math.sin(curPitch)),
                (float) (curFocusZ + horizontal * Math.cos(curYaw)));
        if (!followingVisible && Math.abs(eye.x) < model.mapW * 0.5f + 0.15f && Math.abs(eye.z) < model.mapD * 0.5f + 0.15f) {
            eye.y = Math.max(eye.y, ReplayVideoCamera.ceiling((float) (mesh.replayCeiling() * model.scale)) + 0.3f);
        }
        var look = new Vector3f((float) curFocusX, (float) curFocusY, (float) curFocusZ).sub(eye).normalize();
        var view = new Matrix4f().lookAlong(look, new Vector3f(0, 1, 0));
        var rotation = view.getUnnormalizedRotation(new Quaternionf()).conjugate().normalize();
        var projection = new Matrix4f().perspective((float) Math.toRadians(43), aspect, .04f, 128f);
        return new ReplayVideoCamera.Shot(eye, look, rotation, view, projection);
    }

    private record CloseupTarget(double x, double y, double z, double weight) {}

    private static @Nullable CloseupTarget findActiveKillCloseup(ReplayBoardModel model, double tick) {
        CloseupTarget best = null;
        for (var mark : model.events) {
            var kind = mark.node.kind();
            if (kind != LastMatchReplay.Kind.KILL && kind != LastMatchReplay.Kind.BOMB_DETONATE
                    && kind != LastMatchReplay.Kind.GRENADE_EXPLOSION) {
                continue;
            }
            double delta = tick - mark.node.tick();
            if (delta < -14.0 || delta > 36.0) continue;
            double in = ReplayStagePlan.smoothstep(-14.0, -2.0, delta);
            double out = 1.0 - ReplayStagePlan.smoothstep(20.0, 36.0, delta);
            double weight = Math.clamp(in * out, 0.0, 1.0);
            if (best == null || weight > best.weight()) {
                double x = mark.hasTo ? mark.tx : mark.fx;
                double y = mark.hasTo ? mark.ty : mark.fy;
                double z = mark.hasTo ? mark.tz : mark.fz;
                best = new CloseupTarget(x, y, z, weight);
            }
        }
        return best;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double lerpAngle(double a, double b, double t) {
        double diff = (b - a) % (Math.PI * 2.0);
        if (diff > Math.PI) diff -= Math.PI * 2.0;
        if (diff < -Math.PI) diff += Math.PI * 2.0;
        return a + diff * t;
    }

    void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        hasCamera = false;
        lastDrawNanos = 0;
    }
}
