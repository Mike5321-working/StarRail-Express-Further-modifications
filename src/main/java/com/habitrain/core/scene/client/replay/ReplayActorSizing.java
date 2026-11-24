package com.habitrain.core.scene.client.replay;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.UUID;
import java.util.function.ToDoubleFunction;

/** Shared head sizing in diorama units; screen budgets are fractions of the viewport height. */
final class ReplayActorSizing {
    private final float base;
    private final float projectionScale;
    private final float displayScale;
    private final Matrix4f clip;
    private final FrustumIntersection frustum;
    private final ToDoubleFunction<UUID> emphasis;
    private final boolean offscreen;

    ReplayActorSizing(float mapScale, float displayScale, Matrix4f diorama, Matrix4f view, Matrix4f projection,
                      ToDoubleFunction<UUID> emphasis, boolean offscreen) {
        base = baseSize(mapScale);
        this.displayScale = displayScale;
        projectionScale = Math.abs(projection.m11()) * 0.5f * displayScale;
        clip = new Matrix4f(projection).mul(view).mul(diorama);
        frustum = new FrustumIntersection(clip);
        this.emphasis = emphasis;
        this.offscreen = offscreen;
    }

    static float baseSize(float mapScale) {
        return (float) Math.clamp(0.12 * Math.sqrt(mapScale / 0.0625), 0.075, 0.23);
    }

    float size(UUID id, float x, float y, float z) {
        float depth = clip.m03() * x + clip.m13() * (y + centerOffset(base)) + clip.m23() * z + clip.m33();
        if (!(depth > 1e-4f)) return 0;
        float focus = id == null ? 0 : (float) Math.clamp(emphasis.applyAsDouble(id), 0, 1);
        float unitsPerScreen = depth / projectionScale;
        float min = (offscreen ? 0.019f : 0.012f) * unitsPerScreen;
        float maxFraction = offscreen ? 0.098f + 0.032f * focus : 0.072f + 0.028f * focus;
        float max = maxFraction * depth / (projectionScale + maxFraction * displayScale);
        float desired = Math.max(base, min) * (1 + 0.38f * focus);
        return Math.min(Math.min(max, 0.34f + 0.12f * focus), desired);
    }

    static float centerOffset(float size) { return size * 0.67f; }

    boolean visible(float x, float y, float z, float size) {
        // Allow room for held items, capes, death poses, revival effects and labels.
        float margin = 0.5f + size * 3f;
        return frustum.testAab(x - margin, y - margin, z - margin, x + margin, y + margin, z + margin);
    }
}
