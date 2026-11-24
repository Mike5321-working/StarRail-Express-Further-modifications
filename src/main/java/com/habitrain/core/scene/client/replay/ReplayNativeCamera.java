package com.habitrain.core.scene.client.replay;

import net.minecraft.util.Mth;
import org.joml.Matrix4f;

record ReplayNativeCamera(float baseFov, boolean bobView, float fovEffectScale) {
    float fov(boolean sprinting) {
        float multiplier = sprinting ? 1f + 0.15f * fovEffectScale : 1f;
        return Mth.clamp(baseFov * multiplier, 20f, 115f);
    }

    Matrix4f view(Matrix4f eyeView, double distance, double speed, boolean grounded) {
        if (!bobView || !grounded || speed <= 0) return eyeView;
        float phase = (float) (-distance * 0.6 * Math.PI);
        float amplitude = (float) Math.min(speed, 0.1);
        float stride = Mth.sin(phase) * amplitude;
        return new Matrix4f()
                .translate(stride * 0.5f, -Math.abs(Mth.cos(phase) * amplitude), 0)
                .rotateZ((float) Math.toRadians(stride * 3))
                .rotateX((float) Math.toRadians(Math.abs(Mth.cos(phase - 0.2f) * amplitude) * 5))
                .mul(eyeView);
    }
}
