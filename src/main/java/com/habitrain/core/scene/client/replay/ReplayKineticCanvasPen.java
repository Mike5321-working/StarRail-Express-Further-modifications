package com.habitrain.core.scene.client.replay;

import net.minecraft.network.chat.Component;

import java.util.UUID;

import static com.habitrain.core.scene.client.replay.ReplayVideoCanvas.Tex;

/** {@link ReplayKineticPen} backed by the in-game export canvas. */
final class ReplayKineticCanvasPen implements ReplayKineticPen {
    private final ReplayVideoCanvas c;
    private final int frame;

    ReplayKineticCanvasPen(ReplayVideoCanvas c, int frame) {
        this.c = c;
        this.frame = frame;
    }

    private static Component styled(Component text, Face face) {
        return switch (face) {
            case DISPLAY -> ReplayVideoCanvas.kineticFont(text, ReplayVideoCanvas.MV_DISPLAY);
            case TEXT -> ReplayVideoCanvas.kineticFont(text, ReplayVideoCanvas.MV_TEXT);
            case MONO -> ReplayVideoCanvas.kineticFont(text, ReplayVideoCanvas.MV_MONO);
        };
    }

    @Override public float width() { return c.width; }

    @Override public float height() { return c.height; }

    @Override
    public void quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        c.quad(x0, y0, c0, x1, y1, c1, x2, y2, c2, x3, y3, c3);
    }

    @Override public void polyline(float[] xy, int n, float lineWidth, int color, boolean closed) {
        c.polyline(xy, n, lineWidth, color, closed);
    }

    @Override public void text(Component text, Face face, float x, float y, float px, int color, int align) {
        if (px <= 0) return;
        c.text(styled(text, face), x, y, px / 9f, color, align, false);
    }

    @Override public float textWidth(Component text, Face face, float px) {
        return c.width(styled(text, face), px / 9f);
    }

    @Override public void push() { c.pose.pushMatrix(); }

    @Override public void pop() { c.pose.popMatrix(); }

    @Override public void translate(float x, float y) { c.pose.translate(x, y, 0); }

    @Override public void rotate(float radians) { c.pose.rotateZ(radians); }

    @Override public void scale(float sx, float sy) { c.pose.scale(sx, sy, 1); }

    @Override public void face(UUID player, float x, float y, float size, int tint) { c.face(player, x, y, size, tint); }

    @Override public void invert(float x0, float y0, float x1, float y1, double amount) { c.invert(x0, y0, x1, y1, amount); }

    @Override public void glow(float cx, float cy, float w, float h, int color) {
        c.sprite(Tex.GLOW, cx, cy, w, h, 0, color, true);
    }

    @Override public void vignette(double alpha) {
        c.sprite(Tex.VIGNETTE, c.width / 2f, c.height / 2f, c.width, c.height, 0, ReplayPalette.alpha(0xFFFFFFFF, alpha), false);
    }

    @Override public void grain(double alpha, int f) {
        if (alpha <= 0) return;
        c.tile(Tex.GRAIN, 0, 0, c.width, c.height, 1.0f, ReplayVideoFx.rnd(f, 1), ReplayVideoFx.rnd(f, 2),
                ReplayPalette.alpha(0xFFFFFFFF, alpha));
    }

    int frame() { return frame; }
}
