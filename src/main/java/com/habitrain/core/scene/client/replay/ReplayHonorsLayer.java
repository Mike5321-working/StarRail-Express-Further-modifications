package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Composites honors over the boards using only the original world's depth for occlusion,
 * restricting framebuffer depth/color blits to the projected screen-space bounds of visible
 * honors panels whenever safe.
 */
final class ReplayHonorsLayer {
    private static final int BLIT_MARGIN_PX = 24;
    private TextureTarget target;

    /**
     * Projects an axis-aligned camera-relative bounding box onto pixel bounds {@code [x0, y0, x1, y1]}
     * in OpenGL bottom-left coordinates. Returns {@code null} when any corner crosses the near plane
     * (caller falls back to full-screen blit) or {@code [0, 0, 0, 0]} when completely off-screen.
     */
    static int @Nullable [] projectBlitRect(Matrix4f viewProj, float minX, float minY, float minZ,
                                            float maxX, float maxY, float maxZ,
                                            int width, int height, int marginPx) {
        if (width <= 0 || height <= 0) return new int[]{0, 0, 0, 0};
        float ndcMinX = Float.POSITIVE_INFINITY, ndcMinY = Float.POSITIVE_INFINITY;
        float ndcMaxX = Float.NEGATIVE_INFINITY, ndcMaxY = Float.NEGATIVE_INFINITY;
        Vector4f corner = new Vector4f();
        for (int mask = 0; mask < 8; mask++) {
            float x = (mask & 1) == 0 ? minX : maxX;
            float y = (mask & 2) == 0 ? minY : maxY;
            float z = (mask & 4) == 0 ? minZ : maxZ;
            corner.set(x, y, z, 1.0f).mul(viewProj);
            if (!Float.isFinite(corner.w) || corner.w <= 0.05f) return null;
            float invW = 1.0f / corner.w;
            float nx = corner.x * invW;
            float ny = corner.y * invW;
            if (nx < ndcMinX) ndcMinX = nx;
            if (nx > ndcMaxX) ndcMaxX = nx;
            if (ny < ndcMinY) ndcMinY = ny;
            if (ny > ndcMaxY) ndcMaxY = ny;
        }
        if (ndcMaxX < -1.0f || ndcMinX > 1.0f || ndcMaxY < -1.0f || ndcMinY > 1.0f) {
            return new int[]{0, 0, 0, 0};
        }
        int x0 = Math.clamp((int) Math.floor((ndcMinX * 0.5f + 0.5f) * width) - marginPx, 0, width);
        int y0 = Math.clamp((int) Math.floor((ndcMinY * 0.5f + 0.5f) * height) - marginPx, 0, height);
        int x1 = Math.clamp((int) Math.ceil((ndcMaxX * 0.5f + 0.5f) * width) + marginPx, 0, width);
        int y1 = Math.clamp((int) Math.ceil((ndcMaxY * 0.5f + 0.5f) * height) + marginPx, 0, height);
        if (x1 <= x0 || y1 <= y0) return new int[]{0, 0, 0, 0};
        return new int[]{x0, y0, x1, y1};
    }

    static int blitMarginPx() {
        return BLIT_MARGIN_PX;
    }

    boolean captureWorldDepth(RenderTarget world) {
        return captureWorldDepth(world, null);
    }

    boolean captureWorldDepth(RenderTarget world, int @Nullable [] rect) {
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != world.frameBufferId) return false;
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            if (target == null || target.width != world.width || target.height != world.height) {
                int[] viewport = new int[4];
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                try {
                    close();
                    target = new TextureTarget(world.width, world.height, true, Minecraft.ON_OSX);
                } finally {
                    RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                }
            }
            if (rect == null) {
                target.copyDepthFrom(world);
            } else if (rect[2] > rect[0] && rect[3] > rect[1]) {
                blitRegion(world, target, rect, GL11.GL_DEPTH_BUFFER_BIT);
            }
            return true;
        } finally {
            restoreBindings(world, read);
        }
    }

    void drawOver(RenderTarget world, Runnable paint) {
        drawOver(world, null, paint);
    }

    void drawOver(RenderTarget world, int @Nullable [] rect, Runnable paint) {
        if (rect != null && (rect[2] <= rect[0] || rect[3] <= rect[1])) return;
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            copyColor(world, target, rect);
            paint.run();
            copyColor(target, world, rect);
        } finally {
            restoreBindings(world, read);
        }
    }

    private static void copyColor(RenderTarget from, RenderTarget to, int @Nullable [] rect) {
        if (rect == null) {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
            GlStateManager._glBlitFrameBuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } else {
            blitRegion(from, to, rect, GL11.GL_COLOR_BUFFER_BIT);
        }
    }

    private static void blitRegion(RenderTarget from, RenderTarget to, int[] rect, int mask) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(rect[0], rect[1], rect[2], rect[3],
                rect[0], rect[1], rect[2], rect[3], mask, GL11.GL_NEAREST);
    }

    private static void restoreBindings(RenderTarget world, int read) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, world.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
    }

    void close() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
