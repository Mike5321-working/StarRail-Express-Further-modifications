package com.habitrain.core.client.drone;

import com.habitrain.core.HabiTrainCore;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * Core shader for the Drone FPV tactical lens (vignette, scanlines, signal glitch,
 * boot aperture, and danger pulse), with automatic {@link GuiGraphics} fallback.
 */
@Environment(EnvType.CLIENT)
public final class DroneShaders {
    private static ShaderInstance fpvShader;
    private static boolean registered;

    private DroneShaders() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(HabiTrainCore.id("drone_fpv"),
                        DefaultVertexFormat.POSITION_TEX_COLOR,
                        program -> fpvShader = program));
    }

    public static void renderFpvOverlay(
            GuiGraphics g,
            float timeSeconds,
            float signalNoise,
            float dangerPulse,
            float bootProgress
    ) {
        int w = g.guiWidth();
        int h = g.guiHeight();
        ShaderInstance shader = fpvShader;
        if (shader != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            try {
                shader.safeGetUniform("Time").set(timeSeconds);
                shader.safeGetUniform("SignalNoise").set(signalNoise);
                shader.safeGetUniform("DangerPulse").set(dangerPulse);
                shader.safeGetUniform("BootProgress").set(bootProgress);
                RenderSystem.setShader(() -> shader);

                Matrix4f mat = g.pose().last().pose();
                BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                buf.addVertex(mat, 0.0F, (float) h, 0.0F).setUv(0.0F, 1.0F).setColor(255, 255, 255, 255);
                buf.addVertex(mat, (float) w, (float) h, 0.0F).setUv(1.0F, 1.0F).setColor(255, 255, 255, 255);
                buf.addVertex(mat, (float) w, 0.0F, 0.0F).setUv(1.0F, 0.0F).setColor(255, 255, 255, 255);
                buf.addVertex(mat, 0.0F, 0.0F, 0.0F).setUv(0.0F, 0.0F).setColor(255, 255, 255, 255);
                BufferUploader.drawWithShader(buf.buildOrThrow());
                return;
            } catch (Throwable ignored) {
                // Fall through to pure GuiGraphics fallback
            } finally {
                RenderSystem.enableDepthTest();
                RenderSystem.disableBlend();
            }
        }

        // Safe GuiGraphics fallback
        int edge = Math.max(24, h / 6);
        int topCol = ((int) (95 + dangerPulse * 70) << 24) | (dangerPulse > 0.2F ? 0x551018 : 0x061622);
        g.fillGradient(0, 0, w, edge, topCol, 0x00061622);
        g.fillGradient(0, h - edge, w, h, 0x00061622, topCol);
        for (int y = 0; y < h; y += 4) {
            g.fill(0, y, w, y + 1, 0x1438BDF8);
        }
        if (signalNoise > 0.05F) {
            int staticOverlay = ((int) Math.min(210, signalNoise * 185) << 24) | 0x99D6EC;
            int bandY = (int) ((timeSeconds * 190.0F) % Math.max(1, h));
            g.fill(0, bandY, w, Math.min(h, bandY + 12), staticOverlay);
        }
    }
}
