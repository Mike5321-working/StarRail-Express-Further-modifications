package com.habitrain.core.scene.client.replay;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayActorSizingTest {
    private static final UUID SUBJECT = new UUID(0, 1);
    private static final UUID BYSTANDER = new UUID(0, 2);

    @Test void compactMapsHaveBiggerHeadsButWideMapsKeepALegibleMinimum() {
        float previous = Float.POSITIVE_INFINITY;
        for (int width = 1; width <= 4096; width++) {
            float size = ReplayActorSizing.baseSize(6.25f / width);
            assertTrue(size <= previous);
            assertTrue(size >= 0.075f && size <= 0.23f);
            previous = size;
        }
        assertEquals(0.12f, ReplayActorSizing.baseSize(6.25f / 100), 1e-6f);
        var lens = perspective(34, 16f / 9);
        var wide = new Matrix4f().translation(0, 0, -12);
        float tiny = sizing(4096, 1, wide, lens, true).size(BYSTANDER, 0, 0, 0);
        assertTrue(height(wide, lens, tiny, 720) >= 13);
    }

    @Test void headAndHatStayWithinViewportBudgetAcrossBoardSizesFovAndCameraDepth() {
        for (boolean video : new boolean[]{true, false}) {
            for (int width : new int[]{8, 24, 100, 512, 4096}) {
                for (float display : video ? new float[]{1} : new float[]{1 / 6.25f, 1, 32 / 6.25f}) {
                    for (float zoom : video ? new float[]{1} : new float[]{1, 1.55f, 2.55f}) {
                        for (int fov : new int[]{30, 43, 70, 110}) {
                            var lens = perspective(fov, video ? 16f / 9 : 4f / 3);
                            for (float depth : new float[]{0.2f, 0.8f, 2, 6, 18, 64}) {
                                float scale = display * zoom;
                                var transform = new Matrix4f().translation(0, 0, -depth).scale(scale);
                                var policy = sizing(width, scale, transform, lens, video);
                                float other = policy.size(BYSTANDER, 0, 0, 0);
                                float focus = policy.size(SUBJECT, 0, 0, 0);
                                assertTrue(other > 0 && other <= 0.34f);
                                assertTrue(focus >= other && focus <= 0.46f);
                                assertTrue(height(transform, lens, focus, 720) <= 720 * (video ? 0.17 : 0.13));
                                if (other < 0.34f) assertTrue(height(transform, lens, other, 720) >= 720 * (video ? 0.019 : 0.012));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void dollyLensAndBoardZoomIncreaseProjectedSizeBeforeTheCap() {
        var wide = perspective(60, 16f / 9);
        var narrow = perspective(35, 16f / 9);
        var far = new Matrix4f().translation(0, 0, -5);
        var near = new Matrix4f().translation(0, 0, -2.5f);
        var boardZoom = new Matrix4f(far).scale(2);
        float overview = height(far, wide, sizing(100, 1, far, wide, true).size(BYSTANDER, 0, 0, 0), 720);
        assertTrue(height(near, wide, sizing(100, 1, near, wide, true).size(BYSTANDER, 0, 0, 0), 720) > overview * 1.6);
        assertTrue(height(far, narrow, sizing(100, 1, far, narrow, true).size(BYSTANDER, 0, 0, 0), 720) > overview * 1.6);
        assertTrue(height(boardZoom, wide, sizing(100, 2, boardZoom, wide, false).size(BYSTANDER, 0, 0, 0), 720) > overview * 1.6);
        assertEquals(0, sizing(100, 1, far, wide, true).size(SUBJECT, 0, 0, 6));
    }

    private static Matrix4f perspective(int fov, float aspect) {
        return new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 256);
    }

    private static ReplayActorSizing sizing(int width, float scale, Matrix4f transform, Matrix4f lens, boolean video) {
        return new ReplayActorSizing(6.25f / width, scale, transform, new Matrix4f(), lens,
                id -> id.equals(SUBJECT) ? 1 : 0, video);
    }

    private static float height(Matrix4f transform, Matrix4f projection, float size, int viewport) {
        var clip = new Matrix4f(projection).mul(transform);
        float halfHat = size * 0.5625f;
        float center = ReplayActorSizing.centerOffset(size);
        var top = clip.transform(new Vector4f(0, center + halfHat, halfHat, 1));
        var bottom = clip.transform(new Vector4f(0, center - halfHat, halfHat, 1));
        return (top.y / top.w - bottom.y / bottom.w) * viewport / 2;
    }
}
