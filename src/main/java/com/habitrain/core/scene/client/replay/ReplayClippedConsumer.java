package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.function.Supplier;

/** Clips replay quads against the four vertical tray walls, preserving all vertex attributes. */
final class ReplayClippedConsumer implements VertexConsumer, AutoCloseable {
    private static final int ATTRIBUTES = 18;
    private static final float INSIDE_MARGIN = 0.0001f;

    enum ClipState {
        INSIDE,
        OUTSIDE,
        STRADDLE
    }

    private final Supplier<VertexConsumer> output;
    private final Matrix4f inverseBoard;
    private final float m00, m10, m20, m30;
    private final float m02, m12, m22, m32;
    private final float invScaleX, invScaleZ;
    private final float hw, hd;
    private final float innerHw, innerHd;
    private final int primitiveSize;
    private final float[][] quad = new float[4][ATTRIBUTES];
    private float[][][] polygons;
    private final Vector3f local = new Vector3f();
    private int count, fields;
    private float[] vertex;

    ReplayClippedConsumer(VertexConsumer output, Matrix4f inverseBoard, float hw, float hd) {
        this(() -> output, inverseBoard, hw, hd, 4);
    }

    ReplayClippedConsumer(Supplier<VertexConsumer> output, Matrix4f inverseBoard, float hw, float hd, int primitiveSize) {
        this.output = output;
        this.inverseBoard = inverseBoard;
        this.m00 = inverseBoard.m00(); this.m10 = inverseBoard.m10(); this.m20 = inverseBoard.m20(); this.m30 = inverseBoard.m30();
        this.m02 = inverseBoard.m02(); this.m12 = inverseBoard.m12(); this.m22 = inverseBoard.m22(); this.m32 = inverseBoard.m32();
        this.invScaleX = (float) Math.sqrt(m00 * m00 + m10 * m10 + m20 * m20);
        this.invScaleZ = (float) Math.sqrt(m02 * m02 + m12 * m12 + m22 * m22);
        this.hw = hw;
        this.hd = hd;
        this.innerHw = hw - INSIDE_MARGIN;
        this.innerHd = hd - INSIDE_MARGIN;
        this.primitiveSize = primitiveSize;
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        if (count == primitiveSize) flush();
        vertex = quad[count++];
        vertex[0] = x; vertex[1] = y; vertex[2] = z;
        vertex[16] = m00 * x + m10 * y + m20 * z + m30;
        vertex[17] = m02 * x + m12 * y + m22 * z + m32;
        return this;
    }

    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        fields |= 1;
        vertex[3] = r; vertex[4] = g; vertex[5] = b; vertex[6] = a;
        return this;
    }

    @Override public VertexConsumer setUv(float u, float v) {
        fields |= 2;
        vertex[7] = u; vertex[8] = v;
        return this;
    }

    @Override public VertexConsumer setUv1(int u, int v) {
        fields |= 4;
        vertex[9] = u; vertex[10] = v;
        return this;
    }

    @Override public VertexConsumer setUv2(int u, int v) {
        fields |= 8;
        vertex[11] = u; vertex[12] = v;
        return this;
    }

    @Override public VertexConsumer setNormal(float x, float y, float z) {
        fields |= 16;
        vertex[13] = x; vertex[14] = y; vertex[15] = z;
        return this;
    }

    /**
     * Exact classification of a horizontal square {@code [cx - r, cx + r] x [cz - r, cz + r]} in
     * {@code transform}'s local frame against the four vertical tray walls.
     */
    ClipState classifyHorizontal(Matrix4f transform, float cx, float cy, float cz, float radius) {
        float px = transform.m00() * cx + transform.m10() * cy + transform.m20() * cz + transform.m30();
        float py = transform.m01() * cx + transform.m11() * cy + transform.m21() * cz + transform.m31();
        float pz = transform.m02() * cx + transform.m12() * cy + transform.m22() * cz + transform.m32();
        float lx = m00 * px + m10 * py + m20 * pz + m30;
        float lz = m02 * px + m12 * py + m22 * pz + m32;
        float r = Math.max(0f, radius);
        float ax = m00 * transform.m00() + m10 * transform.m01() + m20 * transform.m02();
        float bx = m00 * transform.m20() + m10 * transform.m21() + m20 * transform.m22();
        float rx = r * (Math.abs(ax) + Math.abs(bx));
        if (lx + rx < -hw || lx - rx > hw) return ClipState.OUTSIDE;
        float az = m02 * transform.m00() + m12 * transform.m01() + m22 * transform.m02();
        float bz = m02 * transform.m20() + m12 * transform.m21() + m22 * transform.m22();
        float rz = r * (Math.abs(az) + Math.abs(bz));
        if (lz + rz < -hd || lz - rz > hd) return ClipState.OUTSIDE;
        if (Math.abs(lx) + rx < innerHw && Math.abs(lz) + rz < innerHd) return ClipState.INSIDE;
        return ClipState.STRADDLE;
    }

    /** Conservative classification of a camera-space sphere (billboards / particles) against the tray walls. */
    ClipState classifyCameraSphere(float cx, float cy, float cz, float radius) {
        float lx = m00 * cx + m10 * cy + m20 * cz + m30;
        float r = Math.max(0f, radius);
        float rx = r * invScaleX;
        if (lx + rx < -hw || lx - rx > hw) return ClipState.OUTSIDE;
        float lz = m02 * cx + m12 * cy + m22 * cz + m32;
        float rz = r * invScaleZ;
        if (lz + rz < -hd || lz - rz > hd) return ClipState.OUTSIDE;
        if (Math.abs(lx) + rx < innerHw && Math.abs(lz) + rz < innerHd) return ClipState.INSIDE;
        return ClipState.STRADDLE;
    }

    /** Flushes any completed pending primitive and returns the direct unclipped target when safe. */
    VertexConsumer directOutput() {
        if (primitiveSize != 4 || (count != 0 && count != 4)) return this;
        if (count != 0) flush();
        return output.get();
    }

    /** A circle fits the clip when the four corners of its enclosing square fit. */
    VertexConsumer horizontalArea(Matrix4f transform, float x, float y, float z, float radius) {
        if (primitiveSize != 4 || (count != 0 && count != 4)) return this;
        if (classifyHorizontal(transform, x, y, z, radius) != ClipState.INSIDE) return this;
        if (count != 0) flush();
        return output.get();
    }

    private boolean inside(float x, float y, float z) {
        float lx = m00 * x + m10 * y + m20 * z + m30;
        float lz = m02 * x + m12 * y + m22 * z + m32;
        return Math.abs(lx) < innerHw && Math.abs(lz) < innerHd;
    }

    private int outcode(float lx, float lz) {
        int code = 0;
        if (lx < -hw) code |= 1;
        else if (lx > hw) code |= 2;
        if (lz < -hd) code |= 4;
        else if (lz > hd) code |= 8;
        return code;
    }

    /**
     * Fast path for camera-facing position-color ribbons: transforms each ribbon edge vertex once
     * into local XZ space, discards completely exterior segments before emitting any vertices,
     * writes strictly interior quads straight to the underlying buffer, and reuses local XZ on
     * straddling quads without a second inverse-matrix transform.
     */
    void ribbon(float[] xyz, int n, float[] widths, int[] colors) {
        if (n < 2) return;
        if (primitiveSize != 4 || (count != 0 && count != 4)) {
            ReplayDraw.ribbonDirect(this, xyz, n, widths, colors);
            return;
        }
        if (count != 0) flush();
        VertexConsumer direct = null;
        float pax = 0, pay = 0, paz = 0, pbx = 0, pby = 0, pbz = 0;
        float paLx = 0, paLz = 0, pbLx = 0, pbLz = 0;
        int pCodeA = 0, pCodeB = 0;
        for (int i = 0; i < n; i++) {
            int o = i * 3;
            int prev = Math.max(0, i - 1) * 3, next = Math.min(n - 1, i + 1) * 3;
            float tx = xyz[next] - xyz[prev], ty = xyz[next + 1] - xyz[prev + 1], tz = xyz[next + 2] - xyz[prev + 2];
            float px = xyz[o], py = xyz[o + 1], pz = xyz[o + 2];
            float sx = ty * pz - tz * py, sy = tz * px - tx * pz, sz = tx * py - ty * px;
            float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (len < 1.0e-7f) { sx = 0; sy = 1; sz = 0; len = 1; }
            float h = widths[i] * 0.5f / len;
            sx *= h; sy *= h; sz *= h;
            float ax = px - sx, ay = py - sy, az = pz - sz;
            float bx = px + sx, by = py + sy, bz = pz + sz;
            float aLx = m00 * ax + m10 * ay + m20 * az + m30;
            float aLz = m02 * ax + m12 * ay + m22 * az + m32;
            float bLx = m00 * bx + m10 * by + m20 * bz + m30;
            float bLz = m02 * bx + m12 * by + m22 * bz + m32;
            int codeA = outcode(aLx, aLz);
            int codeB = outcode(bLx, bLz);
            if (i > 0) {
                int cPrev = colors[i - 1], cCur = colors[i];
                if (((cPrev | cCur) >>> 24) != 0) {
                    if ((pCodeA | pCodeB | codeA | codeB) == 0) {
                        if (direct == null) direct = output.get();
                        ReplayDraw.ribbonQuad(direct, pax, pay, paz, pbx, pby, pbz, bx, by, bz, ax, ay, az, cPrev, cCur);
                    } else if ((pCodeA & pCodeB & codeA & codeB) == 0) {
                        clipPretransformedColorQuad(
                                pax, pay, paz, paLx, paLz, cPrev,
                                pbx, pby, pbz, pbLx, pbLz, cPrev,
                                bx, by, bz, bLx, bLz, cCur,
                                ax, ay, az, aLx, aLz, cCur);
                    }
                }
            }
            pax = ax; pay = ay; paz = az; pbx = bx; pby = by; pbz = bz;
            paLx = aLx; paLz = aLz; pbLx = bLx; pbLz = bLz;
            pCodeA = codeA; pCodeB = codeB;
        }
    }

    private void clipPretransformedColorQuad(
            float x0, float y0, float z0, float lx0, float lz0, int c0,
            float x1, float y1, float z1, float lx1, float lz1, int c1,
            float x2, float y2, float z2, float lx2, float lz2, int c2,
            float x3, float y3, float z3, float lx3, float lz3, int c3) {
        setColorQuadVertex(quad[0], x0, y0, z0, lx0, lz0, c0);
        setColorQuadVertex(quad[1], x1, y1, z1, lx1, lz1, c1);
        setColorQuadVertex(quad[2], x2, y2, z2, lx2, lz2, c2);
        setColorQuadVertex(quad[3], x3, y3, z3, lx3, lz3, c3);
        count = 4;
        fields = 1;
        flush();
    }

    private static void setColorQuadVertex(float[] v, float x, float y, float z, float lx, float lz, int argb) {
        v[0] = x; v[1] = y; v[2] = z;
        v[3] = (argb >>> 16) & 0xFF;
        v[4] = (argb >>> 8) & 0xFF;
        v[5] = argb & 0xFF;
        v[6] = (argb >>> 24) & 0xFF;
        v[16] = lx; v[17] = lz;
    }

    void ribbonQuad(float ax, float ay, float az, float bx, float by, float bz,
                    float cx, float cy, float cz, float dx, float dy, float dz, int startColor, int endColor) {
        VertexConsumer target = this;
        if (primitiveSize == 4 && (count == 0 || count == 4)
                && inside(ax, ay, az) && inside(bx, by, bz) && inside(cx, cy, cz) && inside(dx, dy, dz)) {
            if (count != 0) flush();
            target = output.get();
        }
        ReplayDraw.ribbonQuad(target, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, startColor, endColor);
    }

    private void flush() {
        if (count != primitiveSize) return;
        boolean inside = true;
        for (int index = 0; index < count; index++) inside &= Math.abs(quad[index][16]) <= hw && Math.abs(quad[index][17]) <= hd;
        if (inside) {
            VertexConsumer target = output.get();
            for (int index = 0; index < count; index++) emit(target, quad[index]);
        } else if (primitiveSize == 2) {
            clipLine();
        } else {
            for (int plane = 0; plane < 4; plane++) {
                boolean outside = true;
                for (int index = 0; index < count; index++) outside &= distance(quad[index], plane) < 0;
                if (outside) { count = fields = 0; return; }
            }
            if (polygons == null) polygons = new float[2][12][ATTRIBUTES];
            boolean colorOnly = fields == 1;
            for (int i = 0; i < count; i++) copyVertex(quad[i], polygons[0][i], colorOnly);
            int n = count, source = 0;
            for (int plane = 0; plane < 4 && n > 0; plane++) {
                float[][] input = polygons[source], result = polygons[1 - source];
                int written = 0;
                float[] previous = input[n - 1];
                float dp = distance(previous, plane);
                for (int i = 0; i < n; i++) {
                    float[] next = input[i];
                    float dn = distance(next, plane);
                    if ((dp >= 0) != (dn >= 0)) {
                        float t = dp / (dp - dn);
                        float[] intersection = result[written++];
                        if (colorOnly) interpolateColorOnly(previous, next, t, intersection);
                        else interpolate(previous, next, t, intersection);
                    }
                    if (dn >= 0) copyVertex(next, result[written++], colorOnly);
                    previous = next;
                    dp = dn;
                }
                n = written;
                source = 1 - source;
            }
            if (n >= 3) {
                VertexConsumer target = output.get();
                for (int i = 1; i < n - 1; i++) {
                    emit(target, polygons[source][0]);
                    emit(target, polygons[source][i]);
                    emit(target, polygons[source][i + 1]);
                    if (primitiveSize == 4) emit(target, polygons[source][i + 1]);
                }
            }
        }
        count = fields = 0;
    }

    private static void copyVertex(float[] src, float[] dst, boolean colorOnly) {
        if (colorOnly) {
            dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2];
            dst[3] = src[3]; dst[4] = src[4]; dst[5] = src[5]; dst[6] = src[6];
            dst[16] = src[16]; dst[17] = src[17];
        } else {
            System.arraycopy(src, 0, dst, 0, ATTRIBUTES);
        }
    }

    private void clipLine() {
        float start = 0, end = 1;
        for (int plane = 0; plane < 4; plane++) {
            float left = distance(quad[0], plane), right = distance(quad[1], plane);
            if (left < 0 && right < 0) return;
            if (left < 0) start = Math.max(start, left / (left - right));
            else if (right < 0) end = Math.min(end, left / (left - right));
        }
        if (start >= end) return;
        VertexConsumer target = output.get();
        if (polygons == null) polygons = new float[1][1][ATTRIBUTES];
        float[] endpoint = polygons[0][0];
        interpolate(quad[0], quad[1], start, endpoint);
        emit(target, endpoint);
        interpolate(quad[0], quad[1], end, endpoint);
        emit(target, endpoint);
    }

    private static void interpolateColorOnly(float[] from, float[] to, float fraction, float[] result) {
        result[0] = from[0] + (to[0] - from[0]) * fraction;
        result[1] = from[1] + (to[1] - from[1]) * fraction;
        result[2] = from[2] + (to[2] - from[2]) * fraction;
        result[3] = from[3] + (to[3] - from[3]) * fraction;
        result[4] = from[4] + (to[4] - from[4]) * fraction;
        result[5] = from[5] + (to[5] - from[5]) * fraction;
        result[6] = from[6] + (to[6] - from[6]) * fraction;
        result[16] = from[16] + (to[16] - from[16]) * fraction;
        result[17] = from[17] + (to[17] - from[17]) * fraction;
    }

    private static void interpolate(float[] from, float[] to, float fraction, float[] result) {
        for (int index = 0; index < ATTRIBUTES; index++) result[index] = from[index] + (to[index] - from[index]) * fraction;
    }

    private float distance(float[] v, int plane) {
        return switch (plane) {
            case 0 -> hw + v[16];
            case 1 -> hw - v[16];
            case 2 -> hd + v[17];
            default -> hd - v[17];
        };
    }

    private void emit(VertexConsumer target, float[] v) {
        target.addVertex(v[0], v[1], v[2]);
        if ((fields & 1) != 0) target.setColor(Math.round(v[3]), Math.round(v[4]), Math.round(v[5]), Math.round(v[6]));
        if ((fields & 2) != 0) target.setUv(v[7], v[8]);
        if ((fields & 4) != 0) target.setUv1(Math.round(v[9]), Math.round(v[10]));
        if ((fields & 8) != 0) target.setUv2(Math.round(v[11]), Math.round(v[12]));
        if ((fields & 16) != 0) target.setNormal(v[13], v[14], v[15]);
    }

    @Override public void close() {
        try {
            flush();
        } finally {
            count = fields = 0;
            vertex = null;
        }
    }
}
