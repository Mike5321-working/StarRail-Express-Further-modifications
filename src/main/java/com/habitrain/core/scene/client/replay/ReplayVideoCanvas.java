package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Immediate-mode 2D canvas for the video's motion graphics, drawn in pixel space (top-left origin) into
 * the export framebuffer. Shapes are vertex-coloured quads, art is linearly filtered textures with tint
 * and additive light, text is the game font with the bundled display faces as Latin primary fonts.
 */
final class ReplayVideoCanvas {
    static final int DESIGN_WIDTH = 1280, DESIGN_HEIGHT = 720;
    static final ResourceLocation BIG = ResourceLocation.fromNamespaceAndPath("habitrain_core", "replay_big");
    static final ResourceLocation UI = ResourceLocation.fromNamespaceAndPath("habitrain_core", "replay_ui");
    private static final Set<ResourceLocation> PREPARED = new HashSet<>();

    enum Tex {
        GLOW("glow", 128, 128), RING("ring", 256, 256), BOKEH("bokeh", 128, 128), STREAK("streak", 256, 32),
        RAYS("rays", 512, 512), VIGNETTE("vignette", 256, 256), GRAIN("grain", 256, 256), SCANLINES("scanlines", 64, 64),
        SPEEDLINES("speedlines", 512, 512), NEBULA("nebula", 512, 256), BRAND("brand", 741, 148), TICKET("ticket", 848, 378),
        STAMP("stamp", 256, 256), HEX_FRAME("hex_frame", 256, 256), LAUREL("laurel", 296, 254), CROWN("crown", 160, 120),
        BRUSH("brush", 1024, 180), ICONS("icons", 256, 256),
        T_FIRST_BLOOD("t_first_blood", 717, 174), T_DOUBLE_KILL("t_double_kill", 706, 175), T_TRIPLE_KILL("t_triple_kill", 657, 175),
        T_MULTI_KILL("t_multi_kill", 627, 174), T_FINAL_BLOW("t_final_blow", 680, 173), T_ELIMINATED("t_eliminated", 678, 175),
        T_REVIVED("t_revived", 487, 175), T_DETONATION("t_detonation", 702, 175), T_DEFUSED("t_defused", 513, 175),
        T_BLACKOUT("t_blackout", 593, 175), T_PSYCHO("t_psycho", 460, 173), T_MVP("t_mvp", 281, 173), T_VICTORY("t_victory", 487, 174),
        T_OVERVIEW("t_overview", 969, 175), T_DEPARTURE("t_departure", 656, 175), T_FINAL_STATION("t_final_station", 824, 174),
        T_SHIELD("t_shield", 864, 175);

        final ResourceLocation id;
        final int w, h;
        Tex(String name, int w, int h) {
            id = ResourceLocation.fromNamespaceAndPath("habitrain_core", "textures/replay_video/" + name + ".png");
            this.w = w;
            this.h = h;
        }
    }

    /** Icon atlas cells. */
    static final int SKULL = 0, CROSSHAIR = 1, REVIVE = 2, BOMB = 3, SHIELD = 4, POISON = 5, DOOR = 6, MASK = 7, SWAP = 8,
            BOLT = 9, POTION = 10, TRAP = 11, ROPE = 12, BULB = 13, FLAME = 14, GRENADE = 15;

    final Minecraft mc;
    final int width, height;
    final Matrix4fStack pose = new Matrix4fStack(16);
    private final MultiBufferSource.BufferSource buffers;
    private final GuiGraphics graphics;

    ReplayVideoCanvas(Minecraft mc, int width, int height) {
        this.mc = mc;
        this.width = width;
        this.height = height;
        this.buffers = mc.renderBuffers().bufferSource();
        this.graphics = new GuiGraphics(mc, buffers);
    }

    /** Pixel-space projection. The caller saves and restores the 3D matrices around the 2D passes. */
    void begin() {
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, width, height, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
        var modelView = RenderSystem.getModelViewStack();
        modelView.identity();
        modelView.translation(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        Lighting.setupFor3DItems();
        pose.identity();
    }

    // ------------------------------------------------------------------ shapes

    void quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        if (((c0 | c1 | c2 | c3) >>> 24) == 0) return;
        prepareFlat();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        b.addVertex(pose, x0, y0, 0).setColor(c0);
        b.addVertex(pose, x1, y1, 0).setColor(c1);
        b.addVertex(pose, x2, y2, 0).setColor(c2);
        b.addVertex(pose, x3, y3, 0).setColor(c3);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    void rect(float x0, float y0, float x1, float y1, int color) { rect(x0, y0, x1, y1, color, color); }

    void rect(float x0, float y0, float x1, float y1, int top, int bottom) {
        quad(x0, y0, top, x0, y1, bottom, x1, y1, bottom, x1, y0, top);
    }

    void rectH(float x0, float y0, float x1, float y1, int left, int right) {
        quad(x0, y0, left, x0, y1, left, x1, y1, right, x1, y0, right);
    }

    /** Parallelogram slanted to the right by {@code skew} pixels at the top. */
    void slab(float x0, float y0, float x1, float y1, float skew, int left, int right) {
        quad(x0 + skew, y0, left, x0, y1, left, x1, y1, right, x1 + skew, y0, right);
    }

    void line(float x0, float y0, float x1, float y1, float width, int c0, int c1) {
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0e-4f) return;
        float nx = -dy / len * width / 2, ny = dx / len * width / 2;
        quad(x0 + nx, y0 + ny, c0, x0 - nx, y0 - ny, c0, x1 - nx, y1 - ny, c1, x1 + nx, y1 + ny, c1);
    }

    void ring(float cx, float cy, float r, float thickness, int color, int segments) {
        if ((color >>> 24) == 0) return;
        prepareFlat();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float r0 = Math.max(0, r - thickness / 2), r1 = r + thickness / 2;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            b.addVertex(pose, cx + c0 * r0, cy + s0 * r0, 0).setColor(color);
            b.addVertex(pose, cx + c0 * r1, cy + s0 * r1, 0).setColor(color);
            b.addVertex(pose, cx + c1 * r1, cy + s1 * r1, 0).setColor(color);
            b.addVertex(pose, cx + c1 * r0, cy + s1 * r0, 0).setColor(color);
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    void disc(float cx, float cy, float r, int center, int edge, int segments) {
        if (((center | edge) >>> 24) == 0) return;
        prepareFlat();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            b.addVertex(pose, cx, cy, 0).setColor(center);
            b.addVertex(pose, cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, 0).setColor(edge);
            b.addVertex(pose, cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r, 0).setColor(edge);
            b.addVertex(pose, cx, cy, 0).setColor(center);
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /** Diamond (rotated square) marker. */
    void diamond(float cx, float cy, float r, int color) {
        quad(cx, cy - r, color, cx - r, cy, color, cx, cy + r, color, cx + r, cy, color);
    }

    // ------------------------------------------------------------------ art

    void sprite(Tex tex, float cx, float cy, float w, float h, float angle, int color, boolean additive) {
        region(tex, cx, cy, w, h, angle, 0, 0, 1, 1, color, additive);
    }

    /** Sprite scaled uniformly from its pixel size. */
    void art(Tex tex, float cx, float cy, float scale, float angle, int color, boolean additive) {
        sprite(tex, cx, cy, tex.w * scale, tex.h * scale, angle, color, additive);
    }

    void region(Tex tex, float cx, float cy, float w, float h, float angle, float u0, float v0, float u1, float v1,
                int color, boolean additive) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
        prepareTexture(tex.id, false, additive);
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        float hx = w / 2, hy = h / 2;
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        vertex(b, cx, cy, -hx, -hy, c, s, u0, v0, color);
        vertex(b, cx, cy, -hx, hy, c, s, u0, v1, color);
        vertex(b, cx, cy, hx, hy, c, s, u1, v1, color);
        vertex(b, cx, cy, hx, -hy, c, s, u1, v0, color);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.defaultBlendFunc();
    }

    /** Left-anchored partial reveal of a sprite (wipe-on banners). */
    void reveal(Tex tex, float x, float cy, float w, float h, float amount, int color, boolean additive) {
        float k = Math.clamp(amount, 0, 1);
        if (k <= 0) return;
        region(tex, x + w * k / 2, cy, w * k, h, 0, 0, 0, k, 1, color, additive);
    }

    void icon(int cell, float cx, float cy, float size, int color) {
        float u = (cell % 4) / 4f, v = (cell / 4) / 4f;
        region(Tex.ICONS, cx, cy, size, size, 0, u, v, u + 0.25f, v + 0.25f, color, false);
    }

    /** Repeating texture over a rectangle (grain, scan lines). */
    void tile(Tex tex, float x0, float y0, float x1, float y1, float texScale, float uOffset, float vOffset, int color) {
        if ((color >>> 24) == 0) return;
        prepareTexture(tex.id, true, false);
        float u0 = uOffset, v0 = vOffset, u1 = uOffset + (x1 - x0) / (tex.w * texScale), v1 = vOffset + (y1 - y0) / (tex.h * texScale);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        b.addVertex(pose, x0, y0, 0).setUv(u0, v0).setColor(color);
        b.addVertex(pose, x0, y1, 0).setUv(u0, v1).setColor(color);
        b.addVertex(pose, x1, y1, 0).setUv(u1, v1).setColor(color);
        b.addVertex(pose, x1, y0, 0).setUv(u1, v0).setColor(color);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    private void vertex(BufferBuilder b, float cx, float cy, float x, float y, float c, float s, float u, float v, int color) {
        b.addVertex(pose, cx + x * c - y * s, cy + x * s + y * c, 0).setUv(u, v).setColor(color);
    }

    // ------------------------------------------------------------------ text, faces and items

    static MutableComponent font(Component text, ResourceLocation font) {
        return Component.empty().append(text).withStyle(style -> style.withFont(font));
    }

    float width(Component text, float scale) { return mc.font.width(text) * scale; }

    /** {@code align}: -1 left, 0 centre, 1 right; {@code y} is the vertical centre of the line. */
    void text(Component text, float x, float y, float scale, int color, int align, boolean shadow) {
        if (((color >>> 24) & 0xFF) < 8 || scale <= 0) return;
        Font f = mc.font;
        float w = f.width(text);
        float dx = align < 0 ? 0 : align == 0 ? -w / 2 : -w;
        Matrix4f m = new Matrix4f(pose).translate(x, y, 0).scale(scale, scale, 1);
        f.drawInBatch(text, dx, -f.lineHeight / 2f + 0.5f, color, shadow, m, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        buffers.endBatch();
    }

    /** Text squeezed to {@code maxWidth} pixels when necessary. */
    void textFit(Component text, float x, float y, float scale, float maxWidth, int color, int align, boolean shadow) {
        float w = width(text, scale);
        text(text, x, y, w > maxWidth && w > 0 ? scale * maxWidth / w : scale, color, align, shadow);
    }

    void face(UUID player, float x, float y, float size, int tint) {
        if ((tint >>> 24) == 0) return;
        ResourceLocation skin = ReplayHeadSkins.texture(player);
        float scale = size / 16f;
        graphics.pose().pushPose();
        graphics.pose().mulPose(pose);
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.setColor(((tint >> 16) & 0xFF) / 255f, ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, ((tint >>> 24) & 0xFF) / 255f);
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        PlayerFaceRenderer.draw(graphics, skin, 0, 0, 16);
        graphics.setColor(1, 1, 1, 1);
        graphics.pose().popPose();
        graphics.flush();
    }

    void item(ItemStack stack, float cx, float cy, float size) {
        if (stack.isEmpty()) return;
        graphics.pose().pushPose();
        graphics.pose().mulPose(pose);
        graphics.pose().translate(cx - size / 2, cy - size / 2, 0);
        graphics.pose().scale(size / 16f, size / 16f, 1);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
        graphics.flush();
        RenderSystem.disableDepthTest();
    }

    // ------------------------------------------------------------------ state

    private void prepareFlat() {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private void prepareTexture(ResourceLocation id, boolean repeat, boolean additive) {
        var texture = mc.getTextureManager().getTexture(id);
        if (PREPARED.add(id)) {
            texture.setFilter(true, false);
        }
        RenderSystem.bindTexture(texture.getId());
        int wrap = repeat ? GL11.GL_REPEAT : 0x812F; // GL_CLAMP_TO_EDGE
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        if (additive) RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        else RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, id);
    }

    /** Resource reloads replace textures; forget cached sampler setup. */
    static void invalidate() { PREPARED.clear(); }

    // ------------------------------------------------------------------ colour helpers

    static int argb(int rgb, double alpha) { return ReplayPalette.alpha(rgb, alpha); }
}
