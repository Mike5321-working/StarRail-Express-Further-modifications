package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.client.scene.compat.SceneMaterialKey;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.client.SceneShaders;
import com.habitrain.core.scene.replay.ReplayFanCutaway;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Draws visible replay terrain batches grouped by {@link RenderType} layer so each opaque layer
 * configures GL render state once across all visible spatial batches instead of per-batch.
 * Translucent meshes retain their shared sort order.
 */
final class ReplayTerrainRenderer {
    private static VisibleSlot[] visibleSlots = new VisibleSlot[64];
    private static final Set<SceneMaterialKey> CUSTOM_OPAQUE_KEYS = new LinkedHashSet<>();

    private static final class VisibleSlot {
        SceneMeshSet mesh;
        boolean insideClip;
        final float[] anchors = new float[ReplayFanCutaway.PACKED_FLOATS];
        ReplayFanCutaway.ShaderFans fans;
    }

    private ReplayTerrainRenderer() {}

    static void draw(SceneMeshSet mesh, Matrix4f modelView, Matrix4f projection, ReplayFanCutaway fans,
                     float minX, float minZ, float maxX, float maxZ, int scanAxis, float scanLimit) {
        var allFans = fans.shaderFans();
        var batches = mesh.batches();
        int visibleCount = 0;
        boolean hasSolid = mesh.hasLayer(SceneMeshSet.Layer.SOLID);
        boolean hasCutoutMipped = mesh.hasLayer(SceneMeshSet.Layer.CUTOUT_MIPPED);
        boolean hasCutout = mesh.hasLayer(SceneMeshSet.Layer.CUTOUT);
        CUSTOM_OPAQUE_KEYS.clear();
        mesh.collectCustomOpaqueKeys(CUSTOM_OPAQUE_KEYS);

        if (!batches.isEmpty()) {
            var frustum = new FrustumIntersection(new Matrix4f(projection).mul(modelView));
            for (int i = 0; i < batches.size(); i++) {
                var batch = batches.get(i);
                if (!visible(batch, frustum, minX, minZ, maxX, maxZ, scanAxis, scanLimit)) continue;
                VisibleSlot slot = slotAt(visibleCount++);
                slot.mesh = batch.mesh();
                slot.insideClip = fullyInsideClip(batch, minX, minZ, maxX, maxZ, scanAxis, scanLimit);
                if (allFans.count() == 0) {
                    slot.fans = allFans;
                } else {
                    int kept = fans.selectAnchorsInto(
                            Math.max(minX, batch.minX()), Math.max(minZ, batch.minZ()),
                            Math.min(maxX, batch.maxX()), batch.maxY(), Math.min(maxZ, batch.maxZ()),
                            slot.anchors);
                    slot.fans = kept == 0
                            ? new ReplayFanCutaway.ShaderFans(slot.anchors, 0, allFans.directionX(), allFans.directionZ())
                            : new ReplayFanCutaway.ShaderFans(slot.anchors, kept, allFans.directionX(), allFans.directionZ());
                }
                if (!hasSolid) hasSolid = slot.mesh.hasLayer(SceneMeshSet.Layer.SOLID);
                if (!hasCutoutMipped) hasCutoutMipped = slot.mesh.hasLayer(SceneMeshSet.Layer.CUTOUT_MIPPED);
                if (!hasCutout) hasCutout = slot.mesh.hasLayer(SceneMeshSet.Layer.CUTOUT);
                slot.mesh.collectCustomOpaqueKeys(CUSTOM_OPAQUE_KEYS);
            }
        }

        try (var scope = SceneShaders.fans(allFans, false)) {
            if (hasSolid) {
                drawOpaqueLayer(mesh, allFans, visibleCount, SceneMeshSet.Layer.SOLID,
                        RenderType.solid(), modelView, projection);
            }
            if (hasCutoutMipped) {
                drawOpaqueLayer(mesh, allFans, visibleCount, SceneMeshSet.Layer.CUTOUT_MIPPED,
                        RenderType.cutoutMipped(), modelView, projection);
            }
            if (hasCutout) {
                drawOpaqueLayer(mesh, allFans, visibleCount, SceneMeshSet.Layer.CUTOUT,
                        RenderType.cutout(), modelView, projection);
            }
            if (!CUSTOM_OPAQUE_KEYS.isEmpty()) {
                for (SceneMaterialKey key : CUSTOM_OPAQUE_KEYS) {
                    drawCustomOpaqueMaterial(mesh, allFans, visibleCount, key, modelView, projection);
                }
            }
            mesh.renderLayer(SceneMeshSet.Layer.TRANSLUCENT, modelView, projection, RenderType.translucent());
            mesh.renderCustomTranslucentBatches(modelView, projection);
        } finally {
            for (int i = 0; i < visibleCount; i++) {
                visibleSlots[i].mesh = null;
                visibleSlots[i].fans = null;
            }
            CUSTOM_OPAQUE_KEYS.clear();
        }
    }

    private static VisibleSlot slotAt(int index) {
        if (index >= visibleSlots.length) {
            visibleSlots = Arrays.copyOf(visibleSlots, visibleSlots.length * 2);
        }
        VisibleSlot slot = visibleSlots[index];
        if (slot == null) {
            slot = new VisibleSlot();
            visibleSlots[index] = slot;
        }
        return slot;
    }

    private static void drawOpaqueLayer(SceneMeshSet root, ReplayFanCutaway.ShaderFans rootFans,
                                        int visibleCount, SceneMeshSet.Layer layer, RenderType renderType,
                                        Matrix4f modelView, Matrix4f projection) {
        renderType.setupRenderState();
        try {
            ShaderInstance baseShader = RenderSystem.getShader();
            if (root.hasLayer(layer)) {
                try (var ignored = SceneShaders.fans(rootFans, false)) {
                    root.drawBoundLayer(layer, modelView, projection, baseShader);
                }
            }
            for (int i = 0; i < visibleCount; i++) {
                VisibleSlot slot = visibleSlots[i];
                if (!slot.mesh.hasLayer(layer)) continue;
                try (var ignored = SceneShaders.fans(slot.fans, slot.insideClip)) {
                    slot.mesh.drawBoundLayer(layer, modelView, projection, baseShader);
                }
            }
        } finally {
            VertexBuffer.unbind();
            renderType.clearRenderState();
        }
    }

    private static void drawCustomOpaqueMaterial(SceneMeshSet root, ReplayFanCutaway.ShaderFans rootFans,
                                                 int visibleCount, SceneMaterialKey key,
                                                 Matrix4f modelView, Matrix4f projection) {
        RenderType renderType = key.toRenderType();
        renderType.setupRenderState();
        try {
            ShaderInstance baseShader = RenderSystem.getShader();
            if (root.getCustomBuffer(key) != null) {
                try (var ignored = SceneShaders.fans(rootFans, false)) {
                    root.drawBoundCustomMaterial(key, modelView, projection, baseShader);
                }
            }
            for (int i = 0; i < visibleCount; i++) {
                VisibleSlot slot = visibleSlots[i];
                if (slot.mesh.getCustomBuffer(key) == null) continue;
                try (var ignored = SceneShaders.fans(slot.fans, slot.insideClip)) {
                    slot.mesh.drawBoundCustomMaterial(key, modelView, projection, baseShader);
                }
            }
        } finally {
            VertexBuffer.unbind();
            renderType.clearRenderState();
        }
    }

    static boolean fullyInsideClip(SceneMeshSet.Batch batch, float minX, float minZ, float maxX, float maxZ,
                                   int scanAxis, float scanLimit) {
        if (scanAxis == 0) maxX = Math.min(maxX, scanLimit);
        if (scanAxis == 2) maxZ = Math.min(maxZ, scanLimit);
        return batch.minX() >= minX && batch.minZ() >= minZ && batch.maxX() <= maxX && batch.maxZ() <= maxZ;
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
}
