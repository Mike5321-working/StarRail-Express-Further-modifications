package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.client.SceneMeshSet;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayTerrainRendererTest {
    @Test void cullsOutsideTheCameraTrayOrScanAndKeepsPartiallyVisibleGeometry() {
        var frustum = new FrustumIntersection(new Matrix4f().perspective(1.0f, 1.0f, 0.1f, 128f));
        var mesh = new SceneMeshSet();
        var visible = new SceneMeshSet.Batch(mesh, 1, -1, -10, 3, 1, -8);
        var outside = new SceneMeshSet.Batch(mesh, 20, -1, -10, 32, 1, -8);
        assertTrue(ReplayTerrainRenderer.visible(visible, frustum, -50, -50, 2, 50, 2, -9));
        assertFalse(ReplayTerrainRenderer.visible(visible, frustum, -50, -50, 0, 50, -1, 0));
        assertFalse(ReplayTerrainRenderer.visible(visible, frustum, -50, -50, 50, 50, 0, 0.5f));
        assertFalse(ReplayTerrainRenderer.visible(outside, frustum, -50, -50, 50, 50, -1, 0));
    }
}
