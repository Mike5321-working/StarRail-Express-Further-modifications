package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.client.SceneBuildScheduler;
import com.habitrain.core.scene.client.SceneMeshBuilder;
import com.habitrain.core.scene.client.SceneMeshSet;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps a bounded set of GPU meshes for the world boards, archive viewer and video exporter.
 *
 * <p>Rebuilds are keyed by map fingerprint and delivered section count. The moving fan openings
 * are applied at draw time, so avatar motion and camera turns never rebuild the map. While patches
 * are still arriving, partial rebuilds are coalesced (one at a time, at most every few seconds);
 * the final complete map always rebuilds immediately. Late results from superseded builds, level
 * switches, disconnects or resource reloads are closed instead of installed.</p>
 */
final class ReplayDioramaMesh {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayDioramaMesh.class.getSimpleName());
    private static final long PARTIAL_REBUILD_NS = 2_500_000_000L;
    private static final long FAILURE_RETRY_NS = 15_000_000_000L;
    private static final long IDLE_RELEASE_NS = 45_000_000_000L;

    record Key(String fingerprint, int present) {
        boolean sameMap(Key other) { return other != null && fingerprint.equals(other.fingerprint); }
    }

    private static final java.util.LinkedHashMap<String, Slot> slots = new java.util.LinkedHashMap<>(8, .75f, true);
    private static Slot selected;
    private ReplayDioramaMesh() {}

    static @Nullable SceneMeshSet current() { return selected == null ? null : selected.current(); }
    static @Nullable Key builtKey() { return selected == null ? null : selected.builtKey; }
    static boolean matches(@Nullable SceneAssetCodec.AssetData asset) { return selected != null && selected.matches(asset); }

    static boolean failed(Key key) { return selected != null && key.equals(selected.failedKey); }

    static void retry(String fingerprint) {
        Slot slot = slots.get(fingerprint);
        if (slot != null) slot.failedKey = null;
    }

    static void update(@Nullable SceneAssetCodec.AssetData asset, int present, int total) {
        update(asset, present, total, false);
    }

    static void updateWorld(SceneAssetCodec.AssetData asset, int present, int total) {
        update(asset, present, total, true);
    }

    private static void update(@Nullable SceneAssetCodec.AssetData asset, int present, int total, boolean autoRetry) {
        if (asset == null || asset.sections.isEmpty()) { selected = null; return; }
        selected = slots.computeIfAbsent(asset.fingerprint, key -> new Slot());
        while (slots.size() > 5) slots.pollFirstEntry().getValue().reset();
        if (autoRetry && selected.retryAfter != 0 && System.nanoTime() - selected.retryAfter >= 0) selected.failedKey = null;
        selected.update(asset, present, total);
    }

    static void tickIdle() {
        long now = System.nanoTime();
        var iterator = slots.values().iterator();
        while (iterator.hasNext()) {
            var slot = iterator.next();
            if (now - slot.lastUse > IDLE_RELEASE_NS) {
                slot.reset();
                if (slot == selected) selected = null;
                iterator.remove();
            }
        }
    }

    static void reset() {
        slots.values().forEach(Slot::reset);
        slots.clear();
        selected = null;
    }

    static final class Slot {
        private @Nullable SceneMeshSet mesh;
        private @Nullable Key builtKey;
        private @Nullable Key failedKey;
        private @Nullable CompletableFuture<SceneMeshBuilder.MeshBuildResult> pending;
        private @Nullable Key pendingKey;
        private Object ticket = new Object();
        private long lastSubmit, lastUse;
        private long retryAfter;



        /** Mesh to draw this frame (possibly an older partial one), or null. */
        @Nullable SceneMeshSet current() {
            lastUse = System.nanoTime();
            SceneMeshSet m = mesh;
            return m == null || m.isClosed() || m.isEmpty() ? null : m;
        }



        void update(@Nullable SceneAssetCodec.AssetData asset, int present, int total) {
            lastUse = System.nanoTime();
            if (asset == null || asset.sections.isEmpty()) return;
            Key want = new Key(asset.fingerprint, present);
            if (want.equals(builtKey) || want.equals(pendingKey) || want.equals(failedKey)) return;
            if (builtKey != null && !builtKey.sameMap(want)) closeMesh();
            if (pending != null) {
                if (pendingKey != null && pendingKey.sameMap(want)) return;
                cancelPending();
            }
            long now = System.nanoTime();
            boolean complete = present >= total;
            boolean sameGeometryClass = builtKey != null && builtKey.sameMap(want);
            if (sameGeometryClass && !complete && now - lastSubmit < PARTIAL_REBUILD_NS) return;
            submit(asset, want);
        }

        private void submit(SceneAssetCodec.AssetData asset, Key key) {
            Object token = new Object();
            ticket = token;
            lastSubmit = System.nanoTime();
            CompletableFuture<SceneMeshBuilder.MeshBuildResult> future;
            try {
                future = SceneMeshBuilder.buildCutawayMeshAsync(asset, SceneBuildScheduler.Priority.ADDITIONAL);
            } catch (RuntimeException failure) {
                failedKey = key;
                retryAfter = System.nanoTime() + FAILURE_RETRY_NS;
                LOGGER.warn("Replay diorama mesh submission failed", failure);
                return;
            }
            pending = future;
            pendingKey = key;
            future.whenComplete((result, failure) -> {
                Runnable install = () -> install(token, key, future, result, failure);
                Minecraft client = Minecraft.getInstance();
                if (client.isSameThread()) install.run();
                else client.execute(install);
            });
        }

        private void install(Object token, Key key, CompletableFuture<?> future,
                                    SceneMeshBuilder.MeshBuildResult result, Throwable failure) {
            SceneMeshSet built = result == null ? null : result.meshSet();
            if (token != ticket) {
                if (built != null) built.close();
                return;
            }
            if (pending == future) { pending = null; pendingKey = null; }
            installBuilt(key, built, failure);
        }

        @Nullable Key installedKey() { return builtKey; }

        void installBuilt(Key key, @Nullable SceneMeshSet built, @Nullable Throwable failure) {
            if (failure != null || built == null || built.isClosed() || built.isEmpty()) {
                if (built != null) built.close();
                if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) {
                    LOGGER.warn("Replay diorama mesh build failed", failure);
                }
                // Remember the key so a failing input does not resubmit every frame.
                failedKey = key;
                retryAfter = failure == null ? 0 : System.nanoTime() + FAILURE_RETRY_NS;
                return;
            }
            SceneMeshSet previous = mesh;
            mesh = built;
            builtKey = key;
            failedKey = null;
            retryAfter = 0;
            if (previous != null && previous != built) previous.close();
        }

        private void cancelPending() {
            CompletableFuture<?> p = pending;
            pending = null;
            pendingKey = null;
            ticket = new Object();
            if (p != null) p.cancel(false);
        }

        private void closeMesh() {
            SceneMeshSet m = mesh;
            mesh = null;
            builtKey = null;
            if (m != null) m.close();
        }

        /** Disconnect, level change, new incompatible round or resource reload. */
        void reset() {
            cancelPending();
            closeMesh();
            failedKey = null;
            retryAfter = 0;
            lastSubmit = 0;
        }

        boolean matches(@Nullable SceneAssetCodec.AssetData asset) {
            return asset != null && builtKey != null && Objects.equals(builtKey.fingerprint(), asset.fingerprint);
        }
    }
}
