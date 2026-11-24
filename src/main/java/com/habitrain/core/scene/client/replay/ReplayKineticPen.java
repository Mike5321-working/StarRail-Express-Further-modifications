package com.habitrain.core.scene.client.replay;

import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Minimal drawing surface used by the kinetic-typography (MV style) motion graphics. Coordinates are in the
 * 1280x720 design space with a top-left origin. The in-game implementation wraps {@link ReplayVideoCanvas}; a
 * Java2D implementation renders preview stills in tests so the choreography can be checked without a client.
 */
interface ReplayKineticPen {
    /** Type faces: condensed display caps (Bebas + CJK bold), body CJK bold, and the narrow UI face for labels. */
    enum Face { DISPLAY, TEXT, MONO }

    float width();

    float height();

    void quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3);

    /** Connected line strip through {@code n} points stored as x,y pairs. */
    void polyline(float[] xy, int n, float lineWidth, int color, boolean closed);

    /** {@code y} is the vertical centre of the line; {@code px} the nominal line height in design pixels. */
    void text(Component text, Face face, float x, float y, float px, int color, int align);

    float textWidth(Component text, Face face, float px);

    void push();

    void pop();

    void translate(float x, float y);

    void rotate(float radians);

    void scale(float sx, float sy);

    void face(UUID player, float x, float y, float size, int tint);

    /** Photographic negative of the already drawn frame inside the rectangle, blended by {@code amount}. */
    void invert(float x0, float y0, float x1, float y1, double amount);

    /** Soft additive radial light. */
    void glow(float cx, float cy, float w, float h, int color);

    void vignette(double alpha);

    void grain(double alpha, int frame);
}
