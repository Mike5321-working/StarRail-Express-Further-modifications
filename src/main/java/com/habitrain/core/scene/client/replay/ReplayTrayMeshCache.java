package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;

/** Shares opaque tray geometry between tables whose display basis, dimensions, detail and lighting match. */
final class ReplayTrayMeshCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayTrayMeshCache.class);
    private static final int MAX_MESHES = 16;

    private record Key(Matrix3f basis, float halfWidth, float halfDepth, boolean detail, int light) {
        static Key of(ReplayBoardSite site) {
            return new Key(new Matrix3f(site.board()), site.hw(), site.hd(), site.detail(), site.light());
        }
    }

    private final LinkedHashMap<Key, VertexBuffer> meshes = new LinkedHashMap<>(16, .75f, true);
    private ByteBufferBuilder uploadScratch;
    private boolean compatiblePass;
    private boolean canUpload;
    private boolean failed;

    void beginPass() {
        compatiblePass = !failed && !IrisStatus.ACTIVE.getAsBoolean();
        canUpload = true;
    }

    boolean draw(RenderType type, ReplayBoardSite site) {
        if (!compatiblePass || !clearOfFog(site)) return false;
        Key key = Key.of(site);
        VertexBuffer mesh = meshes.get(key);
        if (mesh == null && !canUpload) return false;

        try {
            type.setupRenderState();
            try {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null || shader.getClass() != ShaderInstance.class) return false;
                if (mesh == null) {
                    // Amortize several differently lit or rotated tables arriving in one frame.
                    canUpload = false;
                    mesh = upload(type, site);
                    meshes.put(key, mesh);
                    if (meshes.size() > MAX_MESHES) meshes.pollFirstEntry().getValue().close();
                }
                var board = site.board();
                var view = new Matrix4f(RenderSystem.getModelViewMatrix()).translate(board.m30(), board.m31(), board.m32());
                mesh.bind();
                mesh.drawWithShader(view, RenderSystem.getProjectionMatrix(), shader);
                return true;
            } finally {
                VertexBuffer.unbind();
                type.clearRenderState();
            }
        } catch (RuntimeException failure) {
            clear();
            failed = true;
            LOGGER.warn("Could not render cached replay table tray, using immediate geometry", failure);
            return false;
        }
    }

    private static boolean clearOfFog(ReplayBoardSite site) {
        // The entity shader measures fog from Position before ModelViewMat. Use the tight tray+plaque
        // bounding radius so large boards (up to 32 blocks) keep VBO caching hot at normal lobby distances.
        var board = site.board();
        double x = board.m30(), y = board.m31(), z = board.m32();
        double radius = site.scale() * Math.hypot(site.hw() + 0.25, site.hd() + 0.25);
        return Math.sqrt(x * x + y * y + z * z) + radius <= RenderSystem.getShaderFogStart();
    }

    private VertexBuffer upload(RenderType type, ReplayBoardSite site) {
        // Draw time supplies only translation. Bake the display basis so the entity shader sees
        // the same world-facing normals as the immediate tray (including custom display rotation).
        var basis = new Matrix4f(site.board()).setTranslation(0f, 0f, 0f);
        if (uploadScratch == null) uploadScratch = new ByteBufferBuilder(1 << 19);
        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
            var builder = new BufferBuilder(uploadScratch, type.mode(), type.format());
            ReplayTrayPainter.tray(builder, basis, site.hw(), site.hd(), site.detail(), site.light());
            buffer.bind();
            try (var data = builder.buildOrThrow()) {
                buffer.upload(data);
            }
            return buffer;
        } catch (RuntimeException failure) {
            buffer.close();
            throw failure;
        }
    }

    void clear() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::clear);
            return;
        }
        for (var mesh : meshes.values()) mesh.close();
        meshes.clear();
        if (uploadScratch != null) {
            uploadScratch.close();
            uploadScratch = null;
        }
        compatiblePass = false;
        canUpload = false;
        failed = false;
    }

    private static final class IrisStatus {
        static final BooleanSupplier ACTIVE = resolve();

        private static BooleanSupplier resolve() {
            if (!FabricLoader.getInstance().isModLoaded("iris")) return () -> false;
            try {
                var iris = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                var instance = iris.getMethod("getInstance").invoke(null);
                var active = iris.getMethod("isShaderPackInUse");
                return () -> {
                    try {
                        return (boolean) active.invoke(instance);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                        return true;
                    }
                };
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return () -> true;
            }
        }
    }
}

