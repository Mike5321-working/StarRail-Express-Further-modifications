package com.habitrain.core.client.title;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * State-batched 2D/2.5D Kinetic Flat 3D OpenGL renderer operating in a virtual {@code 1920x1080}
 * coordinate frame mapped directly onto Minecraft's physical framebuffer pixels (independent of
 * Minecraft GUI scale for razor-sharp typography and hairline geometry).
 *
 * <p>Consecutive primitives sharing the same vertex format, blend mode, and texture (including flat
 * quads, polylines, circles, convex polygons, textured sprites, and ASCII atlas strings) are
 * automatically batched in strict painter's-order submission, dramatically reducing per-frame
 * OpenGL draw calls and state transitions while remaining pixel-identical and fully compatible
 * with vanilla OpenGL, Sodium, and Iris.</p>
 */
@Environment(EnvType.CLIENT)
public final class HabiKineticGfx {

    public static final double W = 1920.0;
    public static final double H = 1080.0;
    public static final double CX = 960.0;
    public static final double CY = 540.0;
    public static final double FOCAL = 1600.0;
    public static final double TAU = Math.PI * 2.0;

    public enum BlendMode {
        NORMAL,
        ADDITIVE,
        MULTIPLY,
        SCREEN
    }

    private enum BatchKind {
        NONE,
        FLAT_QUADS,
        TEX_QUADS
    }

    private static final int MAX_BATCH_QUADS = 4096;

    // Saved Minecraft matrices for clean restoration at endFrame()
    private Matrix4f savedProjection;
    private VertexSorting savedSorting;
    private Matrix4f savedModelView;

    // Active virtual viewport (visible region inside 1920x1080 virtual canvas)
    private double viewX;
    private double viewY;
    private double viewW = W;
    private double viewH = H;
    private int fbWidth = 1920;
    private int fbHeight = 1080;
    private float drawZ = 0.0f;
    private boolean clipActive;
    private boolean scissorActive;

    // Active state-coalescing batch
    private BatchKind activeBatch = BatchKind.NONE;
    private BlendMode activeBlend = BlendMode.NORMAL;
    private int activeTexId = -1;
    private BufferBuilder activeBuilder;
    private int activeBatchQuads;
    private int frameDrawCalls;
    private int lastFrameDrawCalls;

    private double[] arcScratch = new double[128];

    public double viewLeft() { return viewX; }
    public double viewTop() { return viewY; }
    public double viewRight() { return viewX + viewW; }
    public double viewBottom() { return viewY + viewH; }
    public double viewWidth() { return viewW; }
    public double viewHeight() { return viewH; }

    public int getLastFrameDrawCalls() {
        return lastFrameDrawCalls;
    }

    float currentDrawZ() {
        return drawZ;
    }

    /** Updates virtual cover viewport for current physical window dimensions. */
    public void updateViewport(Window window) {
        fbWidth = Math.max(1, window.getWidth());
        fbHeight = Math.max(1, window.getHeight());
        double aspect = fbWidth / (double) fbHeight;
        double targetAspect = W / H;
        if (aspect > targetAspect) {
            double vh = W / aspect;
            viewX = 0.0;
            viewY = (H - vh) * 0.5;
            viewW = W;
            viewH = vh;
        } else {
            double vw = H * aspect;
            viewX = (W - vw) * 0.5;
            viewY = 0.0;
            viewW = vw;
            viewH = H;
        }
    }

    /** Converts Minecraft GUI-scaled mouse coordinates into virtual 1920x1080 coordinates. */
    public double guiToVirtualX(Minecraft mc, double guiX) {
        Window w = mc.getWindow();
        double guiW = Math.max(1.0, w.getGuiScaledWidth());
        return viewX + (guiX / guiW) * viewW;
    }

    public double guiToVirtualY(Minecraft mc, double guiY) {
        Window w = mc.getWindow();
        double guiH = Math.max(1.0, w.getGuiScaledHeight());
        return viewY + (guiY / guiH) * viewH;
    }

    /** Begins a virtual 1920x1080 state-batched OpenGL frame. Always pair with {@link #endFrame()}. */
    public void beginFrame(Minecraft mc) {
        Window window = mc.getWindow();
        updateViewport(window);

        savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        savedSorting = RenderSystem.getVertexSorting();
        var mv = RenderSystem.getModelViewStack();
        savedModelView = new Matrix4f(mv);

        Matrix4f ortho = new Matrix4f().setOrtho(
                (float) viewX, (float) (viewX + viewW),
                (float) (viewY + viewH), (float) viewY,
                -1000.0f, 1000.0f
        );
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
        mv.identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        drawZ = 0.0f;
        clipActive = false;
        scissorActive = false;
        activeBatch = BatchKind.NONE;
        activeBlend = BlendMode.NORMAL;
        activeTexId = -1;
        activeBuilder = null;
        activeBatchQuads = 0;
        frameDrawCalls = 0;
    }

    /** Flushes any pending batched geometry and restores Minecraft's exact projection/modelview/GL state. */
    public void endFrame() {
        try {
            flush();
        } finally {
            lastFrameDrawCalls = frameDrawCalls;
            if (scissorActive) {
                RenderSystem.disableScissor();
                scissorActive = false;
            }
            if (clipActive) {
                endDepthClip();
            }
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableDepthTest();
            RenderSystem.enableCull();

            if (savedProjection != null) {
                RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            }
            if (savedModelView != null) {
                var mv = RenderSystem.getModelViewStack();
                mv.set(savedModelView);
                RenderSystem.applyModelViewMatrix();
            }
        }
    }

    // ── State-Coalescing Batch Engine ────────────────────────────────────────

    /** Flushes the active vertex batch to the GPU if non-empty. */
    public void flush() {
        BufferBuilder builder = this.activeBuilder;
        BatchKind kind = this.activeBatch;
        BlendMode blend = this.activeBlend;
        int texId = this.activeTexId;
        this.activeBuilder = null;
        this.activeBatch = BatchKind.NONE;
        this.activeTexId = -1;
        this.activeBatchQuads = 0;
        if (builder == null || kind == BatchKind.NONE) return;

        var mesh = builder.build();
        if (mesh == null) return;
        RenderSystem.enableBlend();
        applyBlend(blend);
        if (kind == BatchKind.FLAT_QUADS) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, texId);
        }
        BufferUploader.drawWithShader(mesh);
        this.frameDrawCalls++;
    }

    BufferBuilder acquireFlatQuads(BlendMode mode) {
        if (activeBatch != BatchKind.FLAT_QUADS || activeBlend != mode || activeBatchQuads >= MAX_BATCH_QUADS) {
            flush();
            activeBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            activeBatch = BatchKind.FLAT_QUADS;
            activeBlend = mode;
            activeTexId = -1;
            activeBatchQuads = 0;
        }
        return activeBuilder;
    }

    BufferBuilder acquireTexturedQuads(int glTexId, BlendMode mode) {
        if (activeBatch != BatchKind.TEX_QUADS || activeBlend != mode || activeTexId != glTexId
                || activeBatchQuads >= MAX_BATCH_QUADS) {
            flush();
            activeBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            activeBatch = BatchKind.TEX_QUADS;
            activeBlend = mode;
            activeTexId = glTexId;
            activeBatchQuads = 0;
        }
        return activeBuilder;
    }

    void noteQuadsAdded(int quads) {
        activeBatchQuads += quads;
    }

    // ── Arbitrary Polygon Clipping via 24-bit Depth Buffer ───────────────────

    /**
     * Enables clipping to an arbitrary convex polygon (defined by alternating x,y pairs in virtual
     * 1920x1080 space). Uses the depth buffer instead of stencil so it works universally on vanilla
     * OpenGL, Sodium, and Iris.
     */
    public void beginDepthClipPolygon(double... xy) {
        if (xy == null || xy.length < 6) return;
        flush();
        GL11.glClearDepth(1.0);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        // With setOrtho(..., -1000, 1000), z = +500 maps to depth 0.25 (closer than z = 0 -> 0.50).
        float prevZ = drawZ;
        drawZ = 500.0f;
        fillConvexPolygon(0xFFFFFFFF, xy);
        flush();
        drawZ = prevZ;

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);
        RenderSystem.depthFunc(GL11.GL_GREATER); // draw at z=0 (depth 0.50) passes ONLY where mask wrote 0.25!
        clipActive = true;
    }

    /**
     * Enables clipping to the EXTERIOR of a convex polygon (draws pass ONLY outside the polygon).
     * Uses the depth buffer instead of stencil so it works universally on vanilla OpenGL, Sodium, and Iris.
     */
    public void beginInvertedDepthClipPolygon(double... xy) {
        if (xy == null || xy.length < 6) return;
        flush();
        GL11.glClearDepth(1.0);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        float prevZ = drawZ;
        drawZ = 500.0f;
        fillConvexPolygon(0xFFFFFFFF, xy);
        flush();
        drawZ = prevZ;

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);
        RenderSystem.depthFunc(GL11.GL_LESS); // z=0 (depth 0.50) passes OUTSIDE (depth 1.0), fails INSIDE (depth 0.25)!
        clipActive = true;
    }

    /**
     * Enables clipping to the EXTERIOR of a star-shaped polygon centered at {@code (cx, cy)}
     * with boundary points {@code ringXY[0..pointCount*2-1]} (exact cutout for the non-convex Apple Portal Iris).
     */
    public void beginInvertedDepthClipStar(double cx, double cy, double[] ringXY, int pointCount) {
        if (ringXY == null || pointCount < 3) return;
        flush();
        GL11.glClearDepth(1.0);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        float prevZ = drawZ;
        drawZ = 500.0f;
        prepareFlat(BlendMode.NORMAL);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float fcx = (float) cx, fcy = (float) cy;
        for (int i = 0; i < pointCount; i++) {
            int j = (i + 1) % pointCount;
            b.addVertex(fcx, fcy, drawZ).setColor(0xFFFFFFFF);
            b.addVertex((float) ringXY[i * 2], (float) ringXY[i * 2 + 1], drawZ).setColor(0xFFFFFFFF);
            b.addVertex((float) ringXY[j * 2], (float) ringXY[j * 2 + 1], drawZ).setColor(0xFFFFFFFF);
        }
        var mesh = b.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            frameDrawCalls++;
        }
        drawZ = prevZ;

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);
        RenderSystem.depthFunc(GL11.GL_LESS);
        clipActive = true;
    }

    /** Ends active depth-buffer polygon clipping. */
    public void endDepthClip() {
        flush();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        clipActive = false;
    }

    /** Enables axis-aligned hardware scissor in virtual 1920x1080 coordinates. */
    public void enableVirtualScissor(double x0, double y0, double x1, double y1) {
        flush();
        double sx0 = ((x0 - viewX) / viewW) * fbWidth;
        double sx1 = ((x1 - viewX) / viewW) * fbWidth;
        double sy0 = ((y0 - viewY) / viewH) * fbHeight;
        double sy1 = ((y1 - viewY) / viewH) * fbHeight;
        int ix = Math.max(0, (int) Math.floor(Math.min(sx0, sx1)));
        int iw = Math.max(0, (int) Math.ceil(Math.abs(sx1 - sx0)));
        int iyTop = (int) Math.floor(Math.min(sy0, sy1));
        int iyBot = (int) Math.ceil(Math.max(sy0, sy1));
        int ih = Math.max(0, iyBot - iyTop);
        int glY = Math.max(0, fbHeight - iyBot);
        RenderSystem.enableScissor(ix, glY, iw, ih);
        scissorActive = true;
    }

    public void disableVirtualScissor() {
        if (scissorActive) {
            flush();
            RenderSystem.disableScissor();
            scissorActive = false;
        }
    }

    // ── Flat Color Primitives ────────────────────────────────────────────────

    private void prepareFlat(BlendMode mode) {
        RenderSystem.enableBlend();
        applyBlend(mode);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private void applyBlend(BlendMode mode) {
        switch (mode) {
            case NORMAL -> RenderSystem.defaultBlendFunc();
            case ADDITIVE -> RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            case MULTIPLY -> RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            case SCREEN -> RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        }
    }

    public void fillRect(double x, double y, double w, double h, int argb) {
        fillRect(x, y, w, h, argb, BlendMode.NORMAL);
    }

    public void fillRect(double x, double y, double w, double h, int argb, BlendMode mode) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        fillQuad(x, y, x, y + h, x + w, y + h, x + w, y, argb, mode);
    }

    public void fillGradientRectV(double x, double y, double w, double h, int topArgb, int botArgb, BlendMode mode) {
        if (((topArgb >>> 24) == 0 && (botArgb >>> 24) == 0) || w <= 0 || h <= 0) return;
        fillQuadColored(x, y, topArgb, x, y + h, botArgb, x + w, y + h, botArgb, x + w, y, topArgb, mode);
    }

    public void fillGradientRectH(double x, double y, double w, double h, int leftArgb, int rightArgb, BlendMode mode) {
        if (((leftArgb >>> 24) == 0 && (rightArgb >>> 24) == 0) || w <= 0 || h <= 0) return;
        fillQuadColored(x, y, leftArgb, x, y + h, leftArgb, x + w, y + h, rightArgb, x + w, y, rightArgb, mode);
    }

    public void fillQuad(double x0, double y0, double x1, double y1,
                         double x2, double y2, double x3, double y3, int argb) {
        fillQuad(x0, y0, x1, y1, x2, y2, x3, y3, argb, BlendMode.NORMAL);
    }

    public void fillQuad(double x0, double y0, double x1, double y1,
                         double x2, double y2, double x3, double y3, int argb, BlendMode mode) {
        if ((argb >>> 24) == 0) return;
        BufferBuilder b = acquireFlatQuads(mode);
        b.addVertex((float) x0, (float) y0, drawZ).setColor(argb);
        b.addVertex((float) x1, (float) y1, drawZ).setColor(argb);
        b.addVertex((float) x2, (float) y2, drawZ).setColor(argb);
        b.addVertex((float) x3, (float) y3, drawZ).setColor(argb);
        noteQuadsAdded(1);
    }

    public void fillQuadColored(double x0, double y0, int c0,
                                double x1, double y1, int c1,
                                double x2, double y2, int c2,
                                double x3, double y3, int c3, BlendMode mode) {
        if (((c0 >>> 24) | (c1 >>> 24) | (c2 >>> 24) | (c3 >>> 24)) == 0) return;
        BufferBuilder b = acquireFlatQuads(mode);
        b.addVertex((float) x0, (float) y0, drawZ).setColor(c0);
        b.addVertex((float) x1, (float) y1, drawZ).setColor(c1);
        b.addVertex((float) x2, (float) y2, drawZ).setColor(c2);
        b.addVertex((float) x3, (float) y3, drawZ).setColor(c3);
        noteQuadsAdded(1);
    }

    public void fillDiamond(double cx, double cy, double rx, double ry, int argb, BlendMode mode) {
        fillQuad(cx, cy - ry, cx - rx, cy, cx, cy + ry, cx + rx, cy, argb, mode);
    }

    public void fillConvexPolygon(int argb, double... xy) {
        fillConvexPolygon(argb, BlendMode.NORMAL, xy);
    }

    public void fillConvexPolygon(int argb, BlendMode mode, double... xy) {
        if ((argb >>> 24) == 0 || xy == null || xy.length < 6) return;
        fillConvexPolygonCount(argb, mode, xy, xy.length / 2);
    }

    public void fillConvexPolygonCount(int argb, BlendMode mode, double[] xy, int pointCount) {
        if ((argb >>> 24) == 0 || xy == null || pointCount < 3) return;
        // Pair adjacent fan triangles (0, i, i+1) + (i+1, i+2, 0) into QUADS so polygons share the flat batch!
        BufferBuilder b = acquireFlatQuads(mode);
        float x0 = (float) xy[0], y0 = (float) xy[1];
        int quads = 0;
        for (int i = 1; i < pointCount - 1; i += 2) {
            int i1 = i + 1;
            int i2 = Math.min(i + 2, pointCount - 1);
            b.addVertex(x0, y0, drawZ).setColor(argb);
            b.addVertex((float) xy[i * 2], (float) xy[i * 2 + 1], drawZ).setColor(argb);
            b.addVertex((float) xy[i1 * 2], (float) xy[i1 * 2 + 1], drawZ).setColor(argb);
            b.addVertex((float) xy[i2 * 2], (float) xy[i2 * 2 + 1], drawZ).setColor(argb);
            quads++;
        }
        noteQuadsAdded(quads);
    }

    /** Draws a chamfered rectangle matching CSS {@code clip-path: polygon(...)} for Station 01 cards. */
    public void fillChamferRect(double x, double y, double w, double h,
                                double cutTL, double cutTR, double cutBR, double cutBL, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        double[] pts = {
                x + cutTL, y,
                x + w - cutTR, y,
                x + w, y + cutTR,
                x + w, y + h - cutBR,
                x + w - cutBR, y + h,
                x + cutBL, y + h,
                x, y + h - cutBL,
                x, y + cutTL
        };
        fillConvexPolygon(argb, pts);
    }

    public void strokeRect(double x, double y, double w, double h, double lineWidth, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        double lw = Math.max(0.8, lineWidth);
        fillRect(x, y, w, lw, argb);
        fillRect(x, y + h - lw, w, lw, argb);
        fillRect(x, y + lw, lw, Math.max(0, h - lw * 2), argb);
        fillRect(x + w - lw, y + lw, lw, Math.max(0, h - lw * 2), argb);
    }

    public void drawLine(double x0, double y0, double x1, double y1, double width, int argb) {
        drawLine(x0, y0, x1, y1, width, argb, BlendMode.NORMAL);
    }

    public void drawLine(double x0, double y0, double x1, double y1, double width, int argb, BlendMode mode) {
        if ((argb >>> 24) == 0) return;
        double dx = x1 - x0, dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 1e-4) return;
        double hw = Math.max(0.65, width) * 0.5;
        double nx = (-dy / len) * hw;
        double ny = (dx / len) * hw;
        fillQuad(x0 + nx, y0 + ny, x0 - nx, y0 - ny, x1 - nx, y1 - ny, x1 + nx, y1 + ny, argb, mode);
    }

    /** Batched line segments (x0, y0, x1, y1, ...) merged into the active flat quad batch. */
    public void drawSegments(double[] segs, int segCount, double width, int argb) {
        drawSegments(segs, segCount, width, argb, BlendMode.NORMAL);
    }

    public void drawSegments(double[] segs, int segCount, double width, int argb, BlendMode mode) {
        if ((argb >>> 24) == 0 || segCount <= 0) return;
        BufferBuilder b = acquireFlatQuads(mode);
        double hw = Math.max(0.65, width) * 0.5;
        int added = 0;
        for (int i = 0; i < segCount; i++) {
            int p = i * 4;
            double x0 = segs[p], y0 = segs[p + 1], x1 = segs[p + 2], y1 = segs[p + 3];
            double dx = x1 - x0, dy = y1 - y0;
            double len = Math.hypot(dx, dy);
            if (len < 1e-4) continue;
            double nx = (-dy / len) * hw;
            double ny = (dx / len) * hw;
            b.addVertex((float) (x0 + nx), (float) (y0 + ny), drawZ).setColor(argb);
            b.addVertex((float) (x0 - nx), (float) (y0 - ny), drawZ).setColor(argb);
            b.addVertex((float) (x1 - nx), (float) (y1 - ny), drawZ).setColor(argb);
            b.addVertex((float) (x1 + nx), (float) (y1 + ny), drawZ).setColor(argb);
            added++;
        }
        noteQuadsAdded(added);
    }

    /** Batched connected polyline merged into the active flat quad batch. */
    public void drawPolyline(double[] xy, int pointCount, double width, int argb, boolean closed) {
        drawPolyline(xy, pointCount, width, argb, closed, BlendMode.NORMAL);
    }

    public void drawPolyline(double[] xy, int pointCount, double width, int argb, boolean closed, BlendMode mode) {
        if ((argb >>> 24) == 0 || pointCount < 2) return;
        BufferBuilder b = acquireFlatQuads(mode);
        double hw = Math.max(0.65, width) * 0.5;
        int segs = closed ? pointCount : pointCount - 1;
        boolean opaque = (argb >>> 24) >= 245 || mode == BlendMode.ADDITIVE;
        int added = 0;
        for (int i = 0; i < segs; i++) {
            int j = (i + 1) % pointCount;
            double x0 = xy[i * 2], y0 = xy[i * 2 + 1];
            double x1 = xy[j * 2], y1 = xy[j * 2 + 1];
            double dx = x1 - x0, dy = y1 - y0;
            double len = Math.hypot(dx, dy);
            if (len < 1e-4) continue;
            double ux = dx / len, uy = dy / len;
            if (opaque) {
                double ext = Math.min(hw * 0.55, len * 0.42);
                x0 -= ux * ext; y0 -= uy * ext;
                x1 += ux * ext; y1 += uy * ext;
            }
            double nx = -uy * hw, ny = ux * hw;
            b.addVertex((float) (x0 + nx), (float) (y0 + ny), drawZ).setColor(argb);
            b.addVertex((float) (x0 - nx), (float) (y0 - ny), drawZ).setColor(argb);
            b.addVertex((float) (x1 - nx), (float) (y1 - ny), drawZ).setColor(argb);
            b.addVertex((float) (x1 + nx), (float) (y1 + ny), drawZ).setColor(argb);
            added++;
        }
        noteQuadsAdded(added);
    }

    public void fillCircle(double cx, double cy, double r, int argb, int segments) {
        fillCircle(cx, cy, r, argb, segments, BlendMode.NORMAL);
    }

    public void fillCircle(double cx, double cy, double r, int argb, int segments, BlendMode mode) {
        if ((argb >>> 24) == 0 || r <= 0 || segments < 3) return;
        // Pair adjacent pie slices into QUADS so filled circles batch seamlessly with lines and rects!
        BufferBuilder b = acquireFlatQuads(mode);
        float fcx = (float) cx, fcy = (float) cy;
        int quads = 0;
        for (int i = 0; i < segments; i += 2) {
            double a0 = (i / (double) segments) * TAU;
            double a1 = ((i + 1) / (double) segments) * TAU;
            double a2 = (Math.min(i + 2, segments) / (double) segments) * TAU;
            b.addVertex(fcx, fcy, drawZ).setColor(argb);
            b.addVertex((float) (cx + Math.cos(a0) * r), (float) (cy + Math.sin(a0) * r), drawZ).setColor(argb);
            b.addVertex((float) (cx + Math.cos(a1) * r), (float) (cy + Math.sin(a1) * r), drawZ).setColor(argb);
            b.addVertex((float) (cx + Math.cos(a2) * r), (float) (cy + Math.sin(a2) * r), drawZ).setColor(argb);
            quads++;
        }
        noteQuadsAdded(quads);
    }

    public void strokeCircle(double cx, double cy, double r, double width, int argb, int segments) {
        strokeArc(cx, cy, r, 0.0, TAU, width, argb, segments, BlendMode.NORMAL);
    }

    public void strokeArc(double cx, double cy, double r, double a0, double a1,
                          double width, int argb, int segments, BlendMode mode) {
        if ((argb >>> 24) == 0 || r <= 0 || segments < 1) return;
        int needed = (segments + 1) * 2;
        if (arcScratch.length < needed) {
            arcScratch = new double[needed];
        }
        for (int i = 0; i <= segments; i++) {
            double a = lerp(a0, a1, i / (double) segments);
            arcScratch[i * 2] = cx + Math.cos(a) * r;
            arcScratch[i * 2 + 1] = cy + Math.sin(a) * r;
        }
        drawPolyline(arcScratch, segments + 1, width, argb, false, mode);
    }

    // ── Textured Quads & 2.5D Perspective Extruded Slabs ─────────────────────

    public void bindTexture(int glTexId, boolean repeat, BlendMode mode) {
        flush();
        // Filter (GL_LINEAR) and wrap mode (CLAMP_TO_EDGE / REPEAT for noiseTex) are configured once at upload.
        RenderSystem.enableBlend();
        applyBlend(mode);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, glTexId);
    }

    public void drawTexturedRect(int glTexId, double x, double y, double w, double h, int argb, BlendMode mode) {
        drawTexturedQuad(glTexId,
                x, y, 0f, 0f,
                x, y + h, 0f, 1f,
                x + w, y + h, 1f, 1f,
                x + w, y, 1f, 0f,
                argb, false, mode);
    }

    public void drawTexturedRectRotated(int glTexId, double cx, double cy, double w, double h,
                                        double angleRad, int argb, BlendMode mode) {
        double c = Math.cos(angleRad), s = Math.sin(angleRad);
        double hx = w * 0.5, hy = h * 0.5;
        double x0 = cx + (-hx) * c - (-hy) * s, y0 = cy + (-hx) * s + (-hy) * c;
        double x1 = cx + (-hx) * c - (hy) * s,  y1 = cy + (-hx) * s + (hy) * c;
        double x2 = cx + (hx) * c - (hy) * s,   y2 = cy + (hx) * s + (hy) * c;
        double x3 = cx + (hx) * c - (-hy) * s,  y3 = cy + (hx) * s + (-hy) * c;
        drawTexturedQuad(glTexId, x0, y0, 0f, 0f, x1, y1, 0f, 1f, x2, y2, 1f, 1f, x3, y3, 1f, 0f,
                argb, false, mode);
    }

    public void drawTiledTexture(int glTexId, double x, double y, double w, double h,
                                 double tileSize, double uOff, double vOff, int argb, BlendMode mode) {
        float u0 = (float) uOff;
        float v0 = (float) vOff;
        float u1 = (float) (uOff + w / tileSize);
        float v1 = (float) (vOff + h / tileSize);
        drawTexturedQuad(glTexId,
                x, y, u0, v0,
                x, y + h, u0, v1,
                x + w, y + h, u1, v1,
                x + w, y, u1, v0,
                argb, true, mode);
    }

    public void drawTexturedQuad(int glTexId,
                                 double x0, double y0, float u0, float v0,
                                 double x1, double y1, float u1, float v1,
                                 double x2, double y2, float u2, float v2,
                                 double x3, double y3, float u3, float v3,
                                 int argb, boolean repeat, BlendMode mode) {
        if (glTexId <= 0 || (argb >>> 24) == 0) return;
        BufferBuilder b = acquireTexturedQuads(glTexId, mode);
        b.addVertex((float) x0, (float) y0, drawZ).setUv(u0, v0).setColor(argb);
        b.addVertex((float) x1, (float) y1, drawZ).setUv(u1, v1).setColor(argb);
        b.addVertex((float) x2, (float) y2, drawZ).setUv(u2, v2).setColor(argb);
        b.addVertex((float) x3, (float) y3, drawZ).setUv(u3, v3).setColor(argb);
        noteQuadsAdded(1);
    }

    /**
     * Projects a 2D planar rectangle in 3D space (center {@code O}, orientation {@code M}, anchor
     * {@code ax, ay}, size {@code w, h}) into screen coordinates via {@code cam} and writes 4 vertices.
     */
    public boolean appendPlaneQuad(BufferBuilder b, Cam cam, double[] O, double[] M,
                                   double ax, double ay, double w, double h, int argb) {
        double ux0 = M[0], ux1 = M[3], ux2 = M[6];
        double uy0 = M[1], uy1 = M[4], uy2 = M[7];
        double xL = -ax, xR = w - ax;
        double yT = -ay, yB = h - ay;

        double[] p0 = cam.project(O[0] + ux0 * xL + uy0 * yT, O[1] + ux1 * xL + uy1 * yT, O[2] + ux2 * xL + uy2 * yT);
        double[] p1 = cam.project(O[0] + ux0 * xL + uy0 * yB, O[1] + ux1 * xL + uy1 * yB, O[2] + ux2 * xL + uy2 * yB);
        double[] p2 = cam.project(O[0] + ux0 * xR + uy0 * yB, O[1] + ux1 * xR + uy1 * yB, O[2] + ux2 * xR + uy2 * yB);
        double[] p3 = cam.project(O[0] + ux0 * xR + uy0 * yT, O[1] + ux1 * xR + uy1 * yT, O[2] + ux2 * xR + uy2 * yT);
        if (p0 == null || p1 == null || p2 == null || p3 == null) return false;

        b.addVertex((float) p0[0], (float) p0[1], drawZ).setUv(0f, 0f).setColor(argb);
        b.addVertex((float) p1[0], (float) p1[1], drawZ).setUv(0f, 1f).setColor(argb);
        b.addVertex((float) p2[0], (float) p2[1], drawZ).setUv(1f, 1f).setColor(argb);
        b.addVertex((float) p3[0], (float) p3[1], drawZ).setUv(1f, 0f).setColor(argb);
        return true;
    }

    /**
     * Exact Kinetic Flat 3D extruded slab renderer (batched in GPU memory for high FPS).
     * Draws optional drop shadow, stacked side extrusion layers using {@code slab.maskTexId()} tinted
     * to {@code slab.sideRgb()}, and crisp front face using {@code slab.faceTexId()}.
     */
    public void drawSlab(Cam cam, double[] O, double[] M, HabiKineticAtlas.Slab slab,
                         double depth, double alpha, double shadowAlpha, boolean faceOnly,
                         boolean useSideMaskForFace) {
        drawSlab(cam, O, M, slab, depth, alpha, shadowAlpha, faceOnly, useSideMaskForFace, false);
    }

    /**
     * Exact Kinetic Flat 3D extruded slab renderer (batched in GPU memory for high FPS).
     * When {@code bothFaces} is {@code true}, renders {@code faceTex} on whichever side faces the camera.
     */
    public void drawSlab(Cam cam, double[] O, double[] M, HabiKineticAtlas.Slab slab,
                         double depth, double alpha, double shadowAlpha, boolean faceOnly,
                         boolean useSideMaskForFace, boolean bothFaces) {
        if (slab == null || alpha <= 0.003) return;
        double a = sat(alpha);
        if (a < 0.985 && !faceOnly && !bothFaces) {
            faceOnly = true;
        }
        double zc0 = M[2], zc1 = M[5], zc2 = M[8];
        boolean front = -(zc0 * O[0] + zc1 * O[1] + zc2 * (O[2] + FOCAL)) < 0.0;

        // 1) Optional paper drop shadow
        if (shadowAlpha > 0.002 && !faceOnly && slab.maskTexId() > 0) {
            int shColor = withAlpha(0x1A1208, a * shadowAlpha);
            double[] sO = {O[0] + 18.0, O[1] + 28.0, O[2] + 80.0};
            BufferBuilder sb = acquireTexturedQuads(slab.maskTexId(), BlendMode.NORMAL);
            if (appendPlaneQuad(sb, cam, sO, M, slab.ax(), slab.ay(), slab.w(), slab.h(), shColor)) {
                noteQuadsAdded(1);
            }
        }

        // 2) Face-only fast path
        if (faceOnly || !(depth > 0.0) || slab.maskTexId() <= 0) {
            int texId = useSideMaskForFace ? slab.maskTexId() : slab.faceTexId();
            if (texId <= 0) return;
            int tint = useSideMaskForFace ? withAlpha(slab.sideRgb(), a) : withAlpha(0xFFFFFF, a);
            BufferBuilder fb = acquireTexturedQuads(texId, BlendMode.NORMAL);
            if (appendPlaneQuad(fb, cam, O, M, slab.ax(), slab.ay(), slab.w(), slab.h(), tint)) {
                noteQuadsAdded(1);
            }
            return;
        }

        // 3) Extruded 3D thickness layers (coalesced directly into the active mask texture batch!)
        double[] back = {O[0] + zc0 * depth, O[1] + zc1 * depth, O[2] + zc2 * depth};
        double[] p0 = cam.project(O[0], O[1], O[2]);
        double[] p1 = cam.project(back[0], back[1], back[2]);
        if (p0 == null || p1 == null) return;
        double len = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
        int maxSteps = a < 0.985 ? 10 : 64;
        int n = (int) clamp(Math.ceil(len / (a < 0.985 ? 2.4 : 0.85)) + 1.0, 2.0, maxSteps);

        int sideColor = withAlpha(slab.sideRgb(), a < 0.985 ? a * 0.72 : a);
        BufferBuilder sideBuf = acquireTexturedQuads(slab.maskTexId(), BlendMode.NORMAL);
        int sideQuads = 0;
        double[] layerO = new double[3];
        for (int j = 0; j <= n; j++) {
            int i = front ? (n - j) : j;
            boolean isVisibleFace = front ? (i == 0) : (bothFaces && i == n);
            if (isVisibleFace) continue;
            double d = (depth * i) / n;
            layerO[0] = O[0] + zc0 * d;
            layerO[1] = O[1] + zc1 * d;
            layerO[2] = O[2] + zc2 * d;
            if (appendPlaneQuad(sideBuf, cam, layerO, M, slab.ax(), slab.ay(), slab.w(), slab.h(), sideColor)) {
                sideQuads++;
            }
        }
        noteQuadsAdded(sideQuads);

        // 4) Visible front/back face on top
        if (front || bothFaces) {
            int faceTex = useSideMaskForFace ? slab.maskTexId() : slab.faceTexId();
            if (faceTex <= 0) return;
            int faceColor = useSideMaskForFace ? withAlpha(slab.sideRgb(), a) : withAlpha(0xFFFFFF, a);
            double[] faceO = front ? O : back;
            BufferBuilder faceBuf = acquireTexturedQuads(faceTex, BlendMode.NORMAL);
            if (appendPlaneQuad(faceBuf, cam, faceO, M, slab.ax(), slab.ay(), slab.w(), slab.h(), faceColor)) {
                noteQuadsAdded(1);
            }
        }
    }

    /**
     * Renders a smooth curved/flexing textured mesh ({@code cols x rows} screen-projected vertices)
     * in a single GPU draw call with zero triangle-seam artifacts and optional per-vertex tinting.
     */
    public void drawCurvedTexturedMesh(int glTexId, double[][] pts, int nx, int ny,
                                       int[] vertexColors, BlendMode mode) {
        if (glTexId <= 0 || pts == null || nx < 1 || ny < 1) return;
        int cols = nx + 1;
        bindTexture(glTexId, false, mode);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        boolean added = false;
        for (int j = 0; j < ny; j++) {
            float v0 = j / (float) ny;
            float v1 = (j + 1) / (float) ny;
            for (int i = 0; i < nx; i++) {
                int k00 = j * cols + i, k10 = k00 + 1, k01 = k00 + cols, k11 = k01 + 1;
                double[] p00 = pts[k00], p10 = pts[k10], p11 = pts[k11], p01 = pts[k01];
                if (p00 == null || p10 == null || p11 == null || p01 == null) continue;
                float u0 = i / (float) nx;
                float u1 = (i + 1) / (float) nx;
                int c00 = vertexColors != null ? vertexColors[k00] : 0xFFFFFFFF;
                int c10 = vertexColors != null ? vertexColors[k10] : 0xFFFFFFFF;
                int c11 = vertexColors != null ? vertexColors[k11] : 0xFFFFFFFF;
                int c01 = vertexColors != null ? vertexColors[k01] : 0xFFFFFFFF;
                if (((c00 >>> 24) | (c10 >>> 24) | (c11 >>> 24) | (c01 >>> 24)) == 0) continue;

                b.addVertex((float) p00[0], (float) p00[1], drawZ).setUv(u0, v0).setColor(c00);
                b.addVertex((float) p10[0], (float) p10[1], drawZ).setUv(u1, v0).setColor(c10);
                b.addVertex((float) p11[0], (float) p11[1], drawZ).setUv(u1, v1).setColor(c11);

                b.addVertex((float) p00[0], (float) p00[1], drawZ).setUv(u0, v0).setColor(c00);
                b.addVertex((float) p11[0], (float) p11[1], drawZ).setUv(u1, v1).setColor(c11);
                b.addVertex((float) p01[0], (float) p01[1], drawZ).setUv(u0, v1).setColor(c01);
                added = true;
            }
        }
        var mesh = b.build();
        if (added && mesh != null) {
            BufferUploader.drawWithShader(mesh);
            frameDrawCalls++;
        } else if (mesh != null) {
            mesh.close();
        }
        if (mode != BlendMode.NORMAL) RenderSystem.defaultBlendFunc();
    }

    /** Draws a soft radial glow texture centered at {@code (cx, cy)} with radius {@code r}. */
    public void drawGlow(int glowTexId, double cx, double cy, double r, int argb, BlendMode mode) {
        if (glowTexId <= 0 || r <= 0.5 || (argb >>> 24) == 0) return;
        drawTexturedRect(glowTexId, cx - r, cy - r, r * 2.0, r * 2.0, argb, mode);
    }

    /** Draws a soft elliptical glow texture centered at {@code (cx, cy)} with half-extents {@code rx, ry}. */
    public void drawGlowEllipse(int glowTexId, double cx, double cy, double rx, double ry, int argb, BlendMode mode) {
        if (glowTexId <= 0 || rx <= 0.5 || ry <= 0.5 || (argb >>> 24) == 0) return;
        drawTexturedRect(glowTexId, cx - rx, cy - ry, rx * 2.0, ry * 2.0, argb, mode);
    }

    /** Draws an arbitrary 2D sprite texture placed onto a 3D plane (O, M). */
    public void drawPlaneSprite(Cam cam, double[] O, double[] M, int glTexId,
                                double ax, double ay, double w, double h, int argb, BlendMode mode) {
        if (glTexId <= 0 || (argb >>> 24) == 0) return;
        BufferBuilder b = acquireTexturedQuads(glTexId, mode);
        if (appendPlaneQuad(b, cam, O, M, ax, ay, w, h, argb)) {
            noteQuadsAdded(1);
        }
    }

    public void fillPlaneRect(Cam cam, double[] O, double[] M,
                              double x, double y, double w, double h, int argb) {
        fillPlaneRect(cam, O, M, x, y, w, h, argb, BlendMode.NORMAL);
    }

    public void fillPlaneRect(Cam cam, double[] O, double[] M,
                              double x, double y, double w, double h, int argb, BlendMode mode) {
        if ((argb >>> 24) == 0) return;
        fillPlaneQuadColored(cam, O, M,
                x, y, argb,
                x, y + h, argb,
                x + w, y + h, argb,
                x + w, y, argb, mode);
    }

    public void fillPlaneGradientV(Cam cam, double[] O, double[] M,
                                   double x, double y, double w, double h,
                                   int topArgb, int botArgb, BlendMode mode) {
        if (((topArgb >>> 24) == 0 && (botArgb >>> 24) == 0)) return;
        fillPlaneQuadColored(cam, O, M,
                x, y, topArgb,
                x, y + h, botArgb,
                x + w, y + h, botArgb,
                x + w, y, topArgb, mode);
    }

    public void fillPlaneGradientH(Cam cam, double[] O, double[] M,
                                   double x, double y, double w, double h,
                                   int leftArgb, int rightArgb, BlendMode mode) {
        if (((leftArgb >>> 24) == 0 && (rightArgb >>> 24) == 0)) return;
        fillPlaneQuadColored(cam, O, M,
                x, y, leftArgb,
                x, y + h, leftArgb,
                x + w, y + h, rightArgb,
                x + w, y, rightArgb, mode);
    }

    public void fillPlaneQuadColored(Cam cam, double[] O, double[] M,
                                     double lx0, double ly0, int c0,
                                     double lx1, double ly1, int c1,
                                     double lx2, double ly2, int c2,
                                     double lx3, double ly3, int c3, BlendMode mode) {
        if (((c0 >>> 24) | (c1 >>> 24) | (c2 >>> 24) | (c3 >>> 24)) == 0) return;
        double ux0 = M[0], ux1 = M[3], ux2 = M[6];
        double uy0 = M[1], uy1 = M[4], uy2 = M[7];
        double[] p0 = cam.project(O[0] + ux0 * lx0 + uy0 * ly0, O[1] + ux1 * lx0 + uy1 * ly0, O[2] + ux2 * lx0 + uy2 * ly0);
        double[] p1 = cam.project(O[0] + ux0 * lx1 + uy0 * ly1, O[1] + ux1 * lx1 + uy1 * ly1, O[2] + ux2 * lx1 + uy2 * ly1);
        double[] p2 = cam.project(O[0] + ux0 * lx2 + uy0 * ly2, O[1] + ux1 * lx2 + uy1 * ly2, O[2] + ux2 * lx2 + uy2 * ly2);
        double[] p3 = cam.project(O[0] + ux0 * lx3 + uy0 * ly3, O[1] + ux1 * lx3 + uy1 * ly3, O[2] + ux2 * lx3 + uy2 * ly3);
        if (p0 == null || p1 == null || p2 == null || p3 == null) return;
        fillQuadColored(p0[0], p0[1], c0, p1[0], p1[1], c1, p2[0], p2[1], c2, p3[0], p3[1], c3, mode);
    }

    /** Projects an arbitrary 4-corner quad inside a 3D plane (O, M). */
    public void fillPlaneQuad(Cam cam, double[] O, double[] M,
                              double lx0, double ly0, double lx1, double ly1,
                              double lx2, double ly2, double lx3, double ly3, int argb) {
        fillPlaneQuadColored(cam, O, M, lx0, ly0, argb, lx1, ly1, argb, lx2, ly2, argb, lx3, ly3, argb, BlendMode.NORMAL);
    }

    public void strokePlaneRect(Cam cam, double[] O, double[] M,
                                double x, double y, double w, double h, double lineWidth, int argb) {
        double ux0 = M[0], ux1 = M[3], ux2 = M[6];
        double uy0 = M[1], uy1 = M[4], uy2 = M[7];
        double[] p0 = cam.project(O[0] + ux0 * x + uy0 * y, O[1] + ux1 * x + uy1 * y, O[2] + ux2 * x + uy2 * y);
        double[] p1 = cam.project(O[0] + ux0 * (x + w) + uy0 * y, O[1] + ux1 * (x + w) + uy1 * y, O[2] + ux2 * (x + w) + uy2 * y);
        double[] p2 = cam.project(O[0] + ux0 * (x + w) + uy0 * (y + h), O[1] + ux1 * (x + w) + uy1 * (y + h), O[2] + ux2 * (x + w) + uy2 * (y + h));
        double[] p3 = cam.project(O[0] + ux0 * x + uy0 * (y + h), O[1] + ux1 * x + uy1 * (y + h), O[2] + ux2 * x + uy2 * (y + h));
        if (p0 == null || p1 == null || p2 == null || p3 == null) return;
        drawPolyline(new double[]{p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]}, 4, lineWidth, argb, true);
    }

    // ── 3D Perspective Camera & Matrix/Easing Math (1:1 port of scene.js) ────

    public static final class Cam {
        public final double[] R;
        public final double zoom;

        public Cam(double rx, double ry, double rz, double zoom) {
            this.R = euler(rx, ry, rz);
            this.zoom = zoom;
        }

        public double[] v(double[] w) {
            return mv(R, w);
        }

        public double[] v(double x, double y, double z) {
            return new double[]{
                    R[0] * x + R[1] * y + R[2] * z,
                    R[3] * x + R[4] * y + R[5] * z,
                    R[6] * x + R[7] * y + R[8] * z
            };
        }

        public double[] p(double[] v) {
            return project(v[0], v[1], v[2]);
        }

        public double[] project(double vx, double vy, double vz) {
            double d = FOCAL + vz;
            if (d < 40.0) return null;
            double s = (FOCAL / d) * zoom;
            return new double[]{CX + vx * s, CY + vy * s, s};
        }
    }

    public static double clamp(double x, double a, double b) { return Math.min(b, Math.max(a, x)); }
    public static double sat(double x) { return clamp(x, 0.0, 1.0); }
    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    public static double sstep(double a, double b, double x) {
        double t = sat((x - a) / (b - a));
        return t * t * (3.0 - 2.0 * t);
    }
    public static double eOutCubic(double t) { double u = 1.0 - sat(t); return 1.0 - u * u * u; }
    public static double eInCubic(double t) { double u = sat(t); return u * u * u; }
    public static double eInOutCubic(double t) {
        double u = sat(t);
        return u < 0.5 ? 4.0 * u * u * u : 1.0 - Math.pow(-2.0 * u + 2.0, 3.0) / 2.0;
    }
    public static double eOutExpo(double t) {
        double u = sat(t);
        return u >= 1.0 ? 1.0 : 1.0 - Math.pow(2.0, -10.0 * u);
    }
    public static double eOutBack(double t, double c) {
        double u = sat(t) - 1.0;
        return 1.0 + (c + 1.0) * u * u * u + c * u * u;
    }
    public static double eInBack(double t, double c) {
        double u = sat(t);
        return (c + 1.0) * u * u * u - c * u * u;
    }
    public static double hash(double n) {
        double x = Math.sin(n * 127.1 + 311.7) * 43758.5453;
        return x - Math.floor(x);
    }

    public static String typed(String str, double k, double seed) {
        if (str == null || str.isEmpty()) return "";
        int len = str.length();
        int n = (int) Math.floor(sat(k) * len);
        if (n >= len) return str;
        if (k <= 0.0) return "";
        String pool = "01#/_-+X";
        char tail = pool.charAt(Math.floorMod((int) Math.floor(hash(seed + n) * pool.length()), pool.length()));
        return str.substring(0, n) + tail;
    }

    public static int withAlpha(int rgbOrArgb, double alpha) {
        int a = (int) Math.round(sat(alpha) * 255.0);
        return (a << 24) | (rgbOrArgb & 0x00FFFFFF);
    }

    public static int scaleAlpha(int argb, double factor) {
        int baseA = (argb >>> 24) & 0xFF;
        int a = (int) Math.round(baseA * sat(factor));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    public static int lerpRgb(int a, int b, double t) {
        double u = sat(t);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int rr = (int) Math.round(lerp(ar, br, u));
        int rg = (int) Math.round(lerp(ag, bg, u));
        int rb = (int) Math.round(lerp(ab, bb, u));
        return (rr << 16) | (rg << 8) | rb;
    }

    public static double[] mm(double[] a, double[] b) {
        return new double[]{
                a[0] * b[0] + a[1] * b[3] + a[2] * b[6], a[0] * b[1] + a[1] * b[4] + a[2] * b[7], a[0] * b[2] + a[1] * b[5] + a[2] * b[8],
                a[3] * b[0] + a[4] * b[3] + a[5] * b[6], a[3] * b[1] + a[4] * b[4] + a[5] * b[7], a[3] * b[2] + a[4] * b[5] + a[5] * b[8],
                a[6] * b[0] + a[7] * b[3] + a[8] * b[6], a[6] * b[1] + a[7] * b[4] + a[8] * b[7], a[6] * b[2] + a[7] * b[5] + a[8] * b[8]
        };
    }

    public static double[] mv(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
        };
    }

    public static double[] va(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    public static double[] vs(double[] a, double k) { return new double[]{a[0] * k, a[1] * k, a[2] * k}; }
    public static double[] vl(double[] a, double[] b, double t) { return new double[]{lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)}; }
    public static double[] RX(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{1, 0, 0, 0, c, -s, 0, s, c}; }
    public static double[] RY(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, 0, s, 0, 1, 0, -s, 0, c}; }
    public static double[] RZ(double a) { double c = Math.cos(a), s = Math.sin(a); return new double[]{c, -s, 0, s, c, 0, 0, 0, 1}; }
    public static double[] euler(double rx, double ry, double rz) { return mm(RY(ry), mm(RX(rx), RZ(rz))); }
    public static double[] msc(double[] m, double k) {
        double[] out = new double[9];
        for (int i = 0; i < 9; i++) out[i] = m[i] * k;
        return out;
    }
}
