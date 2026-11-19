package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Composites honors over the boards using only the original world's depth for occlusion. */
final class ReplayHonorsLayer {
    private TextureTarget target;

    boolean captureWorldDepth(RenderTarget world) {
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
            target.copyDepthFrom(world);
            return true;
        } finally {
            restoreBindings(world, read);
        }
    }

    void drawOver(RenderTarget world, Runnable paint) {
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            copyColor(world, target);
            paint.run();
            copyColor(target, world);
        } finally {
            restoreBindings(world, read);
        }
    }

    private static void copyColor(RenderTarget from, RenderTarget to) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
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
