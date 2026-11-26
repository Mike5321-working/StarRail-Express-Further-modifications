package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * World-space drawing primitives for the replay board.
 *
 * <p>All glow geometry (trails, beams, rings, wireframes) is emitted as camera-facing
 * triangle strips expressed as quads: {@code GL_LINES} widths are clamped to 1 px on most
 * drivers, which is unusable for a miniature. Positions passed to the "camera space" helpers
 * are camera-relative world coordinates (the world pass keeps the view rotation in the
 * model-view stack, so no further matrix is applied).</p>
 */
final class ReplayDraw {
    private ReplayDraw() {}

    /** Additive hologram light (src-alpha, one): overlaps brighten, never darken. */
    static final RenderType GLOW = RenderType.create("habitrain_replay_glow", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1 << 16, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false));

    /** Alpha-blended flat fills (panels, shadows, tombstones). */
    static final RenderType FILL = RenderType.create("habitrain_replay_fill", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1 << 15, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false));

    // ------------------------------------------------------------------ flat quads in a local frame

    /**
     * Alpha-blended panels that also write depth. Used for the in-world buttons: the additive {@link #GLOW} pass
     * is flushed afterwards, so anything glowing <em>behind</em> a button is rejected instead of shining through it.
     */
    static final RenderType PANEL = RenderType.create("habitrain_replay_panel", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 1 << 14, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .createCompositeState(false));

    static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                     float x2, float y2, float z2, float x3, float y3, float z3, int c0, int c1, int c2, int c3) {
        vc.addVertex(m, x0, y0, z0).setColor(c0);
        vc.addVertex(m, x1, y1, z1).setColor(c1);
        vc.addVertex(m, x2, y2, z2).setColor(c2);
        vc.addVertex(m, x3, y3, z3).setColor(c3);
    }

    /** Axis-aligned rectangle in the local XY plane (z constant), top/bottom gradient. */
    static void rectXY(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z, int top, int bottom) {
        quad(vc, m, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, bottom, bottom, top, top);
    }

    /** Rectangle in the local XZ plane (y constant). */
    static void rectXZ(VertexConsumer vc, Matrix4f m, float x0, float z0, float x1, float z1, float y, int color) {
        quad(vc, m, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, color, color, color, color);
    }

    /** Flat annulus in the local XZ plane. */
    static void ring(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, float radius, float thickness,
                     int segments, int inner, int outer) {
        float r0 = Math.max(0, radius - thickness * 0.5f), r1 = radius + thickness * 0.5f;
        if (vc instanceof ReplayClippedConsumer clipped) {
            var state = clipped.classifyHorizontal(m, cx, cy, cz, Math.max(r0, Math.abs(r1)));
            if (state == ReplayClippedConsumer.ClipState.OUTSIDE) return;
            if (state == ReplayClippedConsumer.ClipState.INSIDE) vc = clipped.directOutput();
        }
        float c0 = Mth.cos(0), s0 = Mth.sin(0);
        for (int i = 0; i < segments; i++) {
            float a1 = Mth.TWO_PI * (i + 1) / segments;
            float c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            quad(vc, m, cx + c0 * r0, cy, cz + s0 * r0, cx + c0 * r1, cy, cz + s0 * r1,
                    cx + c1 * r1, cy, cz + s1 * r1, cx + c1 * r0, cy, cz + s1 * r0, inner, outer, outer, inner);
            c0 = c1;
            s0 = s1;
        }
    }

    /** Filled disc in the local XZ plane, bright centre fading out. */
    static void disc(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, float radius, int segments,
                     int center, int edge) {
        if (vc instanceof ReplayClippedConsumer clipped) {
            var state = clipped.classifyHorizontal(m, cx, cy, cz, radius);
            if (state == ReplayClippedConsumer.ClipState.OUTSIDE) return;
            if (state == ReplayClippedConsumer.ClipState.INSIDE) vc = clipped.directOutput();
        }
        float x0 = cx + Mth.cos(0) * radius, z0 = cz + Mth.sin(0) * radius;
        for (int i = 0; i < segments; i++) {
            float a1 = Mth.TWO_PI * (i + 1) / segments;
            float x1 = cx + Mth.cos(a1) * radius, z1 = cz + Mth.sin(a1) * radius;
            quad(vc, m, cx, cy, cz, cx, cy, cz, x1, cy, z1, x0, cy, z0, center, center, edge, edge);
            x0 = x1;
            z0 = z1;
        }
    }

    /** Vertical light column (two crossed planes) in a local frame. */
    static void column(VertexConsumer vc, Matrix4f m, float cx, float y0, float cz, float height, float radius,
                       int bottom, int top) {
        quad(vc, m, cx - radius, y0, cz, cx + radius, y0, cz, cx + radius, y0 + height, cz, cx - radius, y0 + height, cz,
                bottom, bottom, top, top);
        quad(vc, m, cx, y0, cz - radius, cx, y0, cz + radius, cx, y0 + height, cz + radius, cx, y0 + height, cz - radius,
                bottom, bottom, top, top);
    }

    /** Flat partial annulus arc in the local XZ plane (angles in radians). */
    static void arcXZ(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, float radius, float thickness,
                      float startRad, float endRad, int segments, int inner, int outer) {
        if (segments <= 0 || Math.abs(endRad - startRad) < 1e-4f) return;
        float r0 = Math.max(0, radius - thickness * 0.5f), r1 = radius + thickness * 0.5f;
        for (int i = 0; i < segments; i++) {
            float a0 = startRad + (endRad - startRad) * i / segments;
            float a1 = startRad + (endRad - startRad) * (i + 1) / segments;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            quad(vc, m, cx + c0 * r0, cy, cz + s0 * r0, cx + c0 * r1, cy, cz + s0 * r1,
                    cx + c1 * r1, cy, cz + s1 * r1, cx + c1 * r0, cy, cz + s1 * r0, inner, outer, outer, inner);
        }
    }

    /** Regular polygon outline in the local XZ plane. */
    static void polygonXZ(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, float radius, int sides,
                          float spinRad, float width, int color) {
        if (sides < 3 || radius <= 1e-4f) return;
        for (int i = 0; i < sides; i++) {
            float a0 = spinRad + Mth.TWO_PI * i / sides;
            float a1 = spinRad + Mth.TWO_PI * (i + 1) / sides;
            segment(vc, m, cx + Mth.cos(a0) * radius, cy, cz + Mth.sin(a0) * radius,
                    cx + Mth.cos(a1) * radius, cy, cz + Mth.sin(a1) * radius, width, color, color);
        }
    }

    /** Star-Night Brass astrolabe rune: dual counter-rotating segmented rings + star ticks in XZ plane. */
    static void astrolabeRune(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, float radius,
                              float spinRad, float lineWidth, int primaryColor, int brassColor) {
        // Outer dashed brass arcs (4 quadrants with Art Deco gaps)
        for (int q = 0; q < 4; q++) {
            float base = spinRad + q * Mth.HALF_PI;
            arcXZ(vc, m, cx, cy, cz, radius, Math.max(lineWidth * 1.4f, radius * 0.06f),
                    base + 0.14f, base + Mth.HALF_PI - 0.14f, 6, brassColor, ReplayPalette.fade(brassColor, 0.35));
        }
        // Inner counter-rotating hexagon in role primary color
        polygonXZ(vc, m, cx, cy + 0.002f, cz, radius * 0.72f, 6, -spinRad * 1.35f, lineWidth, primaryColor);
        // Cardinal diamond ticks
        for (int i = 0; i < 8; i++) {
            float a = spinRad * 0.7f + Mth.TWO_PI * i / 8f;
            float rIn = radius * (i % 2 == 0 ? 0.78f : 0.86f);
            float rOut = radius * (i % 2 == 0 ? 1.12f : 1.04f);
            int c = i % 2 == 0 ? brassColor : primaryColor;
            segment(vc, m, cx + Mth.cos(a) * rIn, cy + 0.003f, cz + Mth.sin(a) * rIn,
                    cx + Mth.cos(a) * rOut, cy + 0.003f, cz + Mth.sin(a) * rOut, lineWidth * 0.85f, c, ReplayPalette.fade(c, 0.25));
        }
    }

    /** Volumetric cone between apex and circular base (for spotlights, drone scan cones, warning beams). */
    static void cone(VertexConsumer vc, Matrix4f m, float apexX, float apexY, float apexZ,
                     float baseX, float baseY, float baseZ, float baseRadius, int segments,
                     int apexColor, int baseColor) {
        if (segments < 4 || baseRadius <= 1e-4f) return;
        Vector3f axis = new Vector3f(baseX - apexX, baseY - apexY, baseZ - apexZ);
        if (axis.lengthSquared() < 1e-7f) return;
        axis.normalize();
        Vector3f ref = Math.abs(axis.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        Vector3f u = new Vector3f(axis).cross(ref).normalize().mul(baseRadius);
        Vector3f v = new Vector3f(axis).cross(u).normalize().mul(baseRadius);
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments;
            float a1 = Mth.TWO_PI * (i + 1) / segments;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float bx0 = baseX + u.x * c0 + v.x * s0, by0 = baseY + u.y * c0 + v.y * s0, bz0 = baseZ + u.z * c0 + v.z * s0;
            float bx1 = baseX + u.x * c1 + v.x * s1, by1 = baseY + u.y * c1 + v.y * s1, bz1 = baseZ + u.z * c1 + v.z * s1;
            quad(vc, m, apexX, apexY, apexZ, apexX, apexY, apexZ, bx1, by1, bz1, bx0, by0, bz0,
                    apexColor, apexColor, baseColor, baseColor);
        }
    }

    /** Glowing parabolic energy arc from (x0,y0,z0) to (x1,y1,z1), clipped to {@code progress} in (0..1]. */
    static void parabolicArc(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0,
                             float x1, float y1, float z1, float peakHeight, int steps, float width,
                             int cStart, int cEnd, float progress) {
        if (steps < 2 || progress <= 0.01f) return;
        float clamped = Math.min(1f, progress);
        Vector3f prev = m.transformPosition(x0, y0, z0, new Vector3f());
        int prevColor = cStart;
        for (int i = 1; i <= steps; i++) {
            float u = (float) i / steps;
            if (u > clamped + 1f / steps) break;
            float t = Math.min(u, clamped);
            float h = 4f * t * (1f - t) * peakHeight;
            Vector3f next = m.transformPosition(
                    x0 + (x1 - x0) * t,
                    y0 + (y1 - y0) * t + h,
                    z0 + (z1 - z0) * t,
                    new Vector3f());
            int nextColor = ReplayPalette.lerp(cStart, cEnd, t);
            segment(vc, prev, next, width, prevColor, nextColor);
            prev = next;
            prevColor = nextColor;
        }
    }

    /** Three orthogonal glowing rings forming a holographic sphere (drone pulse, flash sphere). */
    static void sphereRings(VertexConsumer vc, Matrix4f m, float cx, float cy, float cz, float radius,
                            float width, int segments, int color) {
        if (radius <= 1e-4f || segments < 8) return;
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments, a1 = Mth.TWO_PI * (i + 1) / segments;
            float c0 = Mth.cos(a0) * radius, s0 = Mth.sin(a0) * radius;
            float c1 = Mth.cos(a1) * radius, s1 = Mth.sin(a1) * radius;
            segment(vc, m, cx + c0, cy, cz + s0, cx + c1, cy, cz + s1, width, color, color);
            segment(vc, m, cx + c0, cy + s0, cz, cx + c1, cy + s1, cz, width, color, color);
            segment(vc, m, cx, cy + c0, cz + s0, cx, cy + c1, cz + s1, width, color, color);
        }
    }

    /** Untextured flat-colored 3D axis-aligned box (useful for tiny props, traps, C4 bricks, chicks). */
    static void solidBox(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0,
                         float x1, float y1, float z1, int color) {
        int top = color;
        int sideZ = ReplayPalette.scale(color, 0.85);
        int sideX = ReplayPalette.scale(color, 0.72);
        int bot = ReplayPalette.scale(color, 0.58);
        quad(vc, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, top, top, top, top);
        quad(vc, m, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, bot, bot, bot, bot);
        quad(vc, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, sideZ, sideZ, sideZ, sideZ);
        quad(vc, m, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, sideZ, sideZ, sideZ, sideZ);
        quad(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, sideX, sideX, sideX, sideX);
        quad(vc, m, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, sideX, sideX, sideX, sideX);
    }

    /** 45°-chamfered Art Deco plate in the local XY plane. */
    static void chamferRectXY(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z,
                              float cut, int top, int bottom) {
        float c = Math.max(0f, Math.min(cut, Math.min((x1 - x0) * 0.45f, (y1 - y0) * 0.45f)));
        if (c <= 1e-4f) {
            rectXY(vc, m, x0, y0, x1, y1, z, top, bottom);
            return;
        }
        // Centre band
        quad(vc, m, x0, y0 + c, z, x1, y0 + c, z, x1, y1 - c, z, x0, y1 - c, z, bottom, bottom, top, top);
        // Bottom chamfer trapezoid
        quad(vc, m, x0 + c, y0, z, x1 - c, y0, z, x1, y0 + c, z, x0, y0 + c, z, bottom, bottom, bottom, bottom);
        // Top chamfer trapezoid
        quad(vc, m, x0, y1 - c, z, x1, y1 - c, z, x1 - c, y1, z, x0 + c, y1, z, top, top, top, top);
    }

    // ------------------------------------------------------------------ camera-space strips

    /**
     * Camera-facing ribbon through {@code n} camera-relative points ({@code xyz} interleaved).
     * Per-point width and colour allow comet tails and gradients; joints share averaged normals
     * so there are no cracks at corners.
     */
    static void ribbon(VertexConsumer vc, float[] xyz, int n, float[] widths, int[] colors) {
        if (n < 2) return;
        if (vc instanceof ReplayClippedConsumer clipped) {
            clipped.ribbon(xyz, n, widths, colors);
            return;
        }
        ribbonDirect(vc, xyz, n, widths, colors);
    }

    static void ribbonDirect(VertexConsumer vc, float[] xyz, int n, float[] widths, int[] colors) {
        if (n < 2) return;
        float pax = 0, pay = 0, paz = 0, pbx = 0, pby = 0, pbz = 0;
        for (int i = 0; i < n; i++) {
            int o = i * 3;
            int prev = Math.max(0, i - 1) * 3, next = Math.min(n - 1, i + 1) * 3;
            float tx = xyz[next] - xyz[prev], ty = xyz[next + 1] - xyz[prev + 1], tz = xyz[next + 2] - xyz[prev + 2];
            float px = xyz[o], py = xyz[o + 1], pz = xyz[o + 2];
            // side = tangent x (point - camera); the camera is the origin.
            float sx = ty * pz - tz * py, sy = tz * px - tx * pz, sz = tx * py - ty * px;
            float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (len < 1.0e-7f) { sx = 0; sy = 1; sz = 0; len = 1; }
            float h = widths[i] * 0.5f / len;
            sx *= h; sy *= h; sz *= h;
            float ax = px - sx, ay = py - sy, az = pz - sz, bx = px + sx, by = py + sy, bz = pz + sz;
            if (i > 0) {
                int cPrev = colors[i - 1], cCur = colors[i];
                if (((cPrev | cCur) >>> 24) != 0) {
                    ribbonQuad(vc, pax, pay, paz, pbx, pby, pbz, bx, by, bz, ax, ay, az, cPrev, cCur);
                }
            }
            pax = ax; pay = ay; paz = az; pbx = bx; pby = by; pbz = bz;
        }
    }

    static void ribbonQuad(VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz,
                           float cx, float cy, float cz, float dx, float dy, float dz, int startColor, int endColor) {
        vc.addVertex(ax, ay, az).setColor(startColor);
        vc.addVertex(bx, by, bz).setColor(startColor);
        vc.addVertex(cx, cy, cz).setColor(endColor);
        vc.addVertex(dx, dy, dz).setColor(endColor);
    }

    private static final float[] SEG = new float[6];
    private static final float[] SEG_W = new float[2];
    private static final int[] SEG_C = new int[2];

    /** Single glowing segment between two camera-relative points. */
    static void segment(VertexConsumer vc, Vector3f a, Vector3f b, float width, int ca, int cb) {
        SEG[0] = a.x; SEG[1] = a.y; SEG[2] = a.z; SEG[3] = b.x; SEG[4] = b.y; SEG[5] = b.z;
        SEG_W[0] = SEG_W[1] = width;
        SEG_C[0] = ca; SEG_C[1] = cb;
        ribbon(vc, SEG, 2, SEG_W, SEG_C);
    }

    /** Segment in a local frame (transformed through {@code m}). */
    static void segment(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                        float width, int c0, int c1) {
        Vector3f a = m.transformPosition(x0, y0, z0, new Vector3f());
        Vector3f b = m.transformPosition(x1, y1, z1, new Vector3f());
        segment(vc, a, b, width, c0, c1);
    }

    /** Twelve glowing edges of a local axis-aligned box. */
    static void wireBox(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                        float width, int color) {
        segment(vc, m, x0, y0, z0, x1, y0, z0, width, color, color);
        segment(vc, m, x1, y0, z0, x1, y0, z1, width, color, color);
        segment(vc, m, x1, y0, z1, x0, y0, z1, width, color, color);
        segment(vc, m, x0, y0, z1, x0, y0, z0, width, color, color);
        segment(vc, m, x0, y1, z0, x1, y1, z0, width, color, color);
        segment(vc, m, x1, y1, z0, x1, y1, z1, width, color, color);
        segment(vc, m, x1, y1, z1, x0, y1, z1, width, color, color);
        segment(vc, m, x0, y1, z1, x0, y1, z0, width, color, color);
        segment(vc, m, x0, y0, z0, x0, y1, z0, width, color, color);
        segment(vc, m, x1, y0, z0, x1, y1, z0, width, color, color);
        segment(vc, m, x1, y0, z1, x1, y1, z1, width, color, color);
        segment(vc, m, x0, y0, z1, x0, y1, z1, width, color, color);
    }

    /** Camera-facing star burst (muzzle flash, sparkles) around a camera-relative centre. */
    static void star(VertexConsumer vc, Vector3f c, Vector3f right, Vector3f up, float radius, int rays,
                     float spin, int core, int tip) {
        double baseSpin = Double.isFinite(spin) ? Math.IEEEremainder(spin, Math.PI * 2.0) : 0.0;
        for (int i = 0; i < rays; i++) {
            double a = baseSpin + (Math.PI * 2.0) * i / rays;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            float len = (i % 2 == 0 ? 1f : 0.55f) * radius;
            Vector3f tipPos = new Vector3f(c).add(right.x * dx * len + up.x * dy * len,
                    right.y * dx * len + up.y * dy * len, right.z * dx * len + up.z * dy * len);
            segment(vc, c, tipPos, radius * 0.18f, core, tip);
        }
    }

    private static final float[] COS_12 = unitCircle(12, true);
    private static final float[] SIN_12 = unitCircle(12, false);
    private static final float[] COS_8 = unitCircle(8, true);
    private static final float[] SIN_8 = unitCircle(8, false);

    private static float[] unitCircle(int segments, boolean cos) {
        float[] table = new float[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            table[i] = (float) (cos ? Math.cos(angle) : Math.sin(angle));
        }
        return table;
    }

    /** Camera-facing filled quad centred on a camera-relative point. */
    static void billboard(VertexConsumer vc, Vector3f c, Vector3f right, Vector3f up, float halfW, float halfH, int color) {
        if (vc instanceof ReplayClippedConsumer clipped) {
            var state = clipped.classifyCameraSphere(c.x, c.y, c.z, (float) Math.hypot(halfW, halfH));
            if (state == ReplayClippedConsumer.ClipState.OUTSIDE) return;
            if (state == ReplayClippedConsumer.ClipState.INSIDE) vc = clipped.directOutput();
        }
        float rx = right.x * halfW, ry = right.y * halfW, rz = right.z * halfW;
        float ux = up.x * halfH, uy = up.y * halfH, uz = up.z * halfH;
        vc.addVertex(c.x - rx - ux, c.y - ry - uy, c.z - rz - uz).setColor(color);
        vc.addVertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz).setColor(color);
        vc.addVertex(c.x + rx + ux, c.y + ry + uy, c.z + rz + uz).setColor(color);
        vc.addVertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz).setColor(color);
    }

    /**
     * Camera-facing radial soft circle (bright centre fading to transparent rim) with smooth
     * near-camera distance culling/fade so particles crossing near the lens never turn into hard squares.
     */
    static void softBillboard(VertexConsumer vc, Vector3f c, Vector3f right, Vector3f up,
                              float radiusX, float radiusY, int color, float nearCullDist, float nearFullDist) {
        softBillboard(vc, c, right, up, radiusX, radiusY, color, nearCullDist, nearFullDist, 12);
    }

    static void softBillboard(VertexConsumer vc, Vector3f c, Vector3f right, Vector3f up,
                              float radiusX, float radiusY, int color, float nearCullDist, float nearFullDist, int segs) {
        if ((color >>> 24) == 0 || radiusX <= 1e-5f || radiusY <= 1e-5f) return;
        float distSq = c.lengthSquared();
        if (distSq <= nearCullDist * nearCullDist) return;
        if (vc instanceof ReplayClippedConsumer clipped) {
            var state = clipped.classifyCameraSphere(c.x, c.y, c.z, Math.max(radiusX, radiusY));
            if (state == ReplayClippedConsumer.ClipState.OUTSIDE) return;
            if (state == ReplayClippedConsumer.ClipState.INSIDE) vc = clipped.directOutput();
        }
        int center = color;
        if (distSq < nearFullDist * nearFullDist && nearFullDist > nearCullDist) {
            float dist = (float) Math.sqrt(distSq);
            double k = (dist - nearCullDist) / (nearFullDist - nearCullDist);
            k = k * k * (3.0 - 2.0 * k);
            center = ReplayPalette.fade(color, k);
            if ((center >>> 24) == 0) return;
        }
        int edge = ReplayPalette.alpha(center, 0);
        boolean low = segs <= 8;
        int count = low ? 8 : 12;
        float[] cosT = low ? COS_8 : COS_12;
        float[] sinT = low ? SIN_8 : SIN_12;
        float rRx = right.x * radiusX, rRy = right.y * radiusX, rRz = right.z * radiusX;
        float uRx = up.x * radiusY, uRy = up.y * radiusY, uRz = up.z * radiusY;
        float x0 = c.x + rRx * cosT[0] + uRx * sinT[0];
        float y0 = c.y + rRy * cosT[0] + uRy * sinT[0];
        float z0 = c.z + rRz * cosT[0] + uRz * sinT[0];
        for (int i = 0; i < count; i++) {
            float c1 = cosT[i + 1], s1 = sinT[i + 1];
            float x1 = c.x + rRx * c1 + uRx * s1;
            float y1 = c.y + rRy * c1 + uRy * s1;
            float z1 = c.z + rRz * c1 + uRz * s1;
            vc.addVertex(c.x, c.y, c.z).setColor(center);
            vc.addVertex(c.x, c.y, c.z).setColor(center);
            vc.addVertex(x1, y1, z1).setColor(edge);
            vc.addVertex(x0, y0, z0).setColor(edge);
            x0 = x1; y0 = y1; z0 = z1;
        }
    }

    // ------------------------------------------------------------------ textured cuboids (entity format)

    /**
     * Textured, lit cuboid. Faces larger than {@code tile} blocks are split into repeated tiles so
     * herringbone / plank textures keep their real-world scale instead of being stretched.
     */
    static void box(VertexConsumer vc, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1,
                    TextureAtlasSprite side, TextureAtlasSprite top, int tint, int light, float tile) {
        // up / down
        face(vc, pose, top, tint, light, 0, 1, 0, x0, y1, z0, x1, y1, z0, x0, y1, z1, tile);
        face(vc, pose, side, scaleTint(tint, 0.55f), light, 0, -1, 0, x0, y0, z1, x1, y0, z1, x0, y0, z0, tile);
        // north / south
        face(vc, pose, side, scaleTint(tint, 0.8f), light, 0, 0, -1, x1, y1, z0, x0, y1, z0, x1, y0, z0, tile);
        face(vc, pose, side, scaleTint(tint, 0.8f), light, 0, 0, 1, x0, y1, z1, x1, y1, z1, x0, y0, z1, tile);
        // west / east
        face(vc, pose, side, scaleTint(tint, 0.65f), light, -1, 0, 0, x0, y1, z0, x0, y1, z1, x0, y0, z0, tile);
        face(vc, pose, side, scaleTint(tint, 0.65f), light, 1, 0, 0, x1, y1, z1, x1, y1, z0, x1, y0, z1, tile);
    }

    /**
     * Parallelogram face spanned by origin {@code o}, u-edge end {@code u} and v-edge end {@code v}.
     */
    static void face(VertexConsumer vc, PoseStack.Pose pose, TextureAtlasSprite sprite, int tint, int light,
                     float nx, float ny, float nz, float ox, float oy, float oz, float ux, float uy, float uz,
                     float vx, float vy, float vz, float tile) {
        float eux = ux - ox, euy = uy - oy, euz = uz - oz;
        float evx = vx - ox, evy = vy - oy, evz = vz - oz;
        float lu = (float) Math.sqrt(eux * eux + euy * euy + euz * euz);
        float lv = (float) Math.sqrt(evx * evx + evy * evy + evz * evz);
        if (lu < 1.0e-5f || lv < 1.0e-5f) return;
        int nu = Math.max(1, Math.min(48, (int) Math.ceil(lu / tile - 1.0e-3f)));
        int nvv = Math.max(1, Math.min(48, (int) Math.ceil(lv / tile - 1.0e-3f)));
        Matrix4f matrix = pose.pose();
        var normal = pose.transformNormal(nx, ny, nz, new Vector3f());
        var position = new Vector3f();
        float u0 = sprite.getU(0), v0 = sprite.getV(0);
        for (int i = 0; i < nu; i++) {
            float a0 = i * tile / lu, a1 = Math.min(1f, (i + 1) * tile / lu);
            float u1 = sprite.getU(Math.min(1f, (a1 - a0) * lu / tile));
            float x0 = ox + eux * a0, y0 = oy + euy * a0, z0 = oz + euz * a0;
            float x1 = ox + eux * a1, y1 = oy + euy * a1, z1 = oz + euz * a1;
            for (int j = 0; j < nvv; j++) {
                float b0 = j * tile / lv, b1 = Math.min(1f, (j + 1) * tile / lv);
                float v1 = sprite.getV(Math.min(1f, (b1 - b0) * lv / tile));
                vertex(vc, matrix, position, normal, x0 + evx * b0, y0 + evy * b0, z0 + evz * b0, tint, u0, v0, light);
                vertex(vc, matrix, position, normal, x1 + evx * b0, y1 + evy * b0, z1 + evz * b0, tint, u1, v0, light);
                vertex(vc, matrix, position, normal, x1 + evx * b1, y1 + evy * b1, z1 + evz * b1, tint, u1, v1, light);
                vertex(vc, matrix, position, normal, x0 + evx * b1, y0 + evy * b1, z0 + evz * b1, tint, u0, v1, light);
            }
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f matrix, Vector3f position, Vector3f normal,
                               float x, float y, float z, int color, float u, float v, int light) {
        matrix.transformPosition(x, y, z, position);
        vc.addVertex(position.x, position.y, position.z, color, u, v,
                OverlayTexture.NO_OVERLAY, light, normal.x, normal.y, normal.z);
    }

    private static int scaleTint(int tint, float k) {
        return ReplayPalette.scale(tint, k);
    }
}
