package com.habitrain.core.scene.client.replay;

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
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

final class ReplayPreviewTarget {
    private TextureTarget target;

    void draw(GuiGraphics g, Minecraft mc, ReplayBoardModel model, ReplayTimeline.Frame frame, ReplayStagePlan.Stage stage,
              SceneMeshSet mesh, int x, int y, int w, int h, double yaw, double pitch, double zoom) {
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
            int tw = w * 2, th = h * 2;
            if (target == null || target.width != tw || target.height != th) {
                close();
                target = new TextureTarget(tw, th, true, Minecraft.ON_OSX);
                target.setClearColor(.04f, .065f, .12f, 1);
            }
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            var shot = camera(model, mesh, yaw, pitch, zoom, (float) w / h);
            matrices.set(shot.view());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(shot.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            FogRenderer.setupNoFog();
            mc.gameRenderer.lightTexture().turnOnLightLayer();
            ReplayBoardRenderer.drawVideoFrame(mc, model, frame, stage, frame.replayTick() / 20, shot, mesh,
                    ReplayBoardLocalState.fullBody(), null);
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

    private static ReplayVideoCamera.Shot camera(ReplayBoardModel model, SceneMeshSet mesh, double yaw, double pitch,
                                                  double zoom, float aspect) {
        float radius = ReplayBoardRenderer.trayHalfSize(model);
        float focusY = ReplayBoardSite.MAP_BASE + model.mapH * .28f;
        double dist = radius * (3.5 / zoom) / Math.min(1, aspect);
        double horizontal = dist * Math.cos(pitch);
        var eye = new Vector3f((float) (horizontal * Math.sin(yaw)), focusY + (float) (dist * Math.sin(pitch)),
                (float) (horizontal * Math.cos(yaw)));
        if (Math.abs(eye.x) < model.mapW * .5f + .15f && Math.abs(eye.z) < model.mapD * .5f + .15f) {
            eye.y = Math.max(eye.y, ReplayVideoCamera.ceiling((float) (mesh.replayCeiling() * model.scale)) + .3f);
        }
        var look = new Vector3f(0, focusY, 0).sub(eye).normalize();
        var view = new Matrix4f().lookAlong(look, new Vector3f(0, 1, 0));
        var rotation = view.getUnnormalizedRotation(new Quaternionf()).conjugate().normalize();
        var projection = new Matrix4f().perspective((float) Math.toRadians(43), aspect, .05f, 128);
        return new ReplayVideoCamera.Shot(eye, look, rotation, view, projection);
    }

    void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
