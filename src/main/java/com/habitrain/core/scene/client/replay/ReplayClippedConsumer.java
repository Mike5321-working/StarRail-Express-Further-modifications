package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.function.Supplier;

/** Clips replay quads against the four vertical tray walls, preserving all vertex attributes. */
final class ReplayClippedConsumer implements VertexConsumer, AutoCloseable {
    private static final int ATTRIBUTES = 18;
    private final Supplier<VertexConsumer> output;
    private final Matrix4f inverseBoard;
    private final float hw, hd;
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
        this.hw = hw;
        this.hd = hd;
        this.primitiveSize = primitiveSize;
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        if (count == primitiveSize) flush();
        vertex = quad[count++];
        vertex[0] = x; vertex[1] = y; vertex[2] = z;
        inverseBoard.transformPosition(x, y, z, local);
        vertex[16] = local.x; vertex[17] = local.z;
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

    /** A circle fits the clip when the four corners of its enclosing square fit. */
    VertexConsumer horizontalArea(Matrix4f transform, float x, float y, float z, float radius) {
        if (primitiveSize != 4 || count != 0 && count != 4) return this;
        if (!inside(transform, x - radius, y, z - radius)
                || !inside(transform, x + radius, y, z - radius)
                || !inside(transform, x + radius, y, z + radius)
                || !inside(transform, x - radius, y, z + radius)) return this;
        if (count != 0) flush();
        return output.get();
    }

    private boolean inside(Matrix4f transform, float x, float y, float z) {
        transform.transformPosition(x, y, z, local);
        return inside(local.x, local.y, local.z);
    }

    private boolean inside(float x, float y, float z) {
        inverseBoard.transformPosition(x, y, z, local);
        // Keep a small margin so interpolation and matrix round-off at the tray rim use the full clip path.
        return Math.abs(local.x) < hw - 0.0001f && Math.abs(local.z) < hd - 0.0001f;
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
            for (int i = 0; i < count; i++) System.arraycopy(quad[i], 0, polygons[0][i], 0, ATTRIBUTES);
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
                        interpolate(previous, next, t, intersection);
                    }
                    if (dn >= 0) System.arraycopy(next, 0, result[written++], 0, ATTRIBUTES);
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
