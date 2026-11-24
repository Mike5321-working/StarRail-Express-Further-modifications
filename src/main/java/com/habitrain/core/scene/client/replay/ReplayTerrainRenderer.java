package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.client.SceneShaders;
import com.habitrain.core.scene.replay.ReplayFanCutaway;
import net.minecraft.client.renderer.RenderType;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/** Draws visible replay terrain batches. Translucent meshes retain their shared sort order. */
final class ReplayTerrainRenderer {
    private ReplayTerrainRenderer() {}

    static void draw(SceneMeshSet mesh, Matrix4f modelView, Matrix4f projection, ReplayFanCutaway fans,
                     float minX, float minZ, float maxX, float maxZ, int scanAxis, float scanLimit) {
        var allFans = fans.shaderFans();
        try (var scope = SceneShaders.fans(allFans)) {
            opaque(mesh, modelView, projection);
            if (!mesh.batches().isEmpty()) {
                var frustum = new FrustumIntersection(new Matrix4f(projection).mul(modelView));
                for (var batch : mesh.batches()) {
                    if (!visible(batch, frustum, minX, minZ, maxX, maxZ, scanAxis, scanLimit)) continue;
                    var nearbyFans = allFans.count() == 0 ? allFans : fans.shaderFans(
                            Math.max(minX, batch.minX()), Math.max(minZ, batch.minZ()),
                            Math.min(maxX, batch.maxX()), batch.maxY(), Math.min(maxZ, batch.maxZ()));
                    try (var batchScope = SceneShaders.fans(nearbyFans)) {
                        opaque(batch.mesh(), modelView, projection);
                    }
                }
            }
            mesh.renderLayer(SceneMeshSet.Layer.TRANSLUCENT, modelView, projection, RenderType.translucent());
            mesh.renderCustomTranslucentBatches(modelView, projection);
        }
    }

    static boolean visible(SceneMeshSet.Batch batch, FrustumIntersection frustum,
                           float minX, float minZ, float maxX, float maxZ, int scanAxis, float scanLimit) {
        float x0 = (float) Math.max(minX, batch.minX()), z0 = (float) Math.max(minZ, batch.minZ());
        float x1 = (float) Math.min(maxX, batch.maxX()), z1 = (float) Math.min(maxZ, batch.maxZ());
        if (scanAxis == 0) x1 = Math.min(x1, scanLimit);
        if (scanAxis == 2) z1 = Math.min(z1, scanLimit);
        return x0 <= x1 && z0 <= z1
                && frustum.testAab(x0, (float) batch.minY(), z0, x1, (float) batch.maxY(), z1);
    }

    private static void opaque(SceneMeshSet mesh, Matrix4f modelView, Matrix4f projection) {
        mesh.renderLayer(SceneMeshSet.Layer.SOLID, modelView, projection, RenderType.solid());
        mesh.renderLayer(SceneMeshSet.Layer.CUTOUT_MIPPED, modelView, projection, RenderType.cutoutMipped());
        mesh.renderLayer(SceneMeshSet.Layer.CUTOUT, modelView, projection, RenderType.cutout());
        mesh.renderCustomOpaqueBatches(modelView, projection);
    }
}
