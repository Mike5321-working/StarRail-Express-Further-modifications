package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayBlockDelta;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.client.SceneBuildScheduler;
import com.habitrain.core.scene.client.SceneMeshBuilder;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.replay.ReplayFanCutaway;
import com.mojang.blaze3d.vertex.PoseStack;
import io.wifi.starrailexpress.content.block.DoorPartBlock;
import io.wifi.starrailexpress.content.block_entity.DoorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final long MAX_SLOTS_BYTES = 128L * 1024L * 1024L;

    record Key(String fingerprint, int present) {
        boolean sameMap(Key other) { return other != null && fingerprint.equals(other.fingerprint); }
    }

    private static final java.util.LinkedHashMap<String, Slot> slots = new java.util.LinkedHashMap<>(8, .75f, true);
    private static final java.util.LinkedHashMap<String, DynamicWorld> dynamicCache = new java.util.LinkedHashMap<>(8, .75f, true);
    private static Slot selected;
    private ReplayDioramaMesh() {}

    static @Nullable SceneMeshSet current() { return selected == null ? null : selected.current(); }
    static @Nullable Key builtKey() { return selected == null ? null : selected.builtKey; }
    static boolean matches(@Nullable SceneAssetCodec.AssetData asset) { return selected != null && selected.matches(asset); }

    /** Returns an already-built neighbor-culled replay mesh matching {@code asset} without triggering a new build. */
    static @Nullable SceneMeshSet readyCutaway(@Nullable SceneAssetCodec.AssetData asset) {
        if (asset == null) return null;
        Slot slot = slots.get(asset.fingerprint);
        return slot != null && slot.matches(asset) ? slot.current() : null;
    }

    /**
     * Resolves the shared neighbor-culled 2D-batched mesh for 1:1 POV views. Because replay table
     * meshes now use standard neighbor culling as well, both views share one GPU VBO slot pool.
     */
    static @Nullable SceneMeshSet resolveSolid(@Nullable SceneAssetCodec.AssetData asset, int present, int total) {
        if (asset == null || asset.sections.isEmpty()) return null;
        Slot slot = slots.computeIfAbsent(asset.fingerprint, key -> new Slot(false));
        enforceSlotBudget(asset.fingerprint);
        slot.update(asset, present, total);
        return slot.matches(asset) ? slot.current() : null;
    }

    static boolean failed(Key key) { return selected != null && selected.hasFailed(key); }

    static void retry(String fingerprint) {
        Slot slot = slots.get(fingerprint);
        if (slot != null) slot.failedKey = null;
    }

    private static long totalSlotBytes() {
        long total = 0L;
        for (Slot slot : slots.values()) {
            total += slot.estimatedBytes();
        }
        return total;
    }

    private static void enforceSlotBudget(@Nullable String keepFingerprint) {
        while (slots.size() > 5 || totalSlotBytes() > MAX_SLOTS_BYTES) {
            String victimKey = null;
            for (var entry : slots.entrySet()) {
                if (Objects.equals(entry.getKey(), keepFingerprint) || entry.getValue() == selected) {
                    continue;
                }
                victimKey = entry.getKey();
                break;
            }
            if (victimKey == null) break;
            Slot removed = slots.remove(victimKey);
            if (removed != null) removed.reset();
        }
    }

    /** Immediately evicts and closes an export-only diorama slot (even if it was last selected by the exporter). */
    static void evictSlot(@Nullable String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return;
        Slot removed = slots.remove(fingerprint);
        if (removed != null) {
            if (removed == selected) selected = null;
            removed.reset();
        }
    }

    /** Evicts a cached slot only when it is not currently selected by an active board. */
    static void evictIfUnselected(@Nullable String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return;
        Slot slot = slots.get(fingerprint);
        if (slot == null || slot == selected) return;
        slots.remove(fingerprint);
        slot.reset();
    }

    /**
     * Prepares (and caches) a {@link DynamicWorld} combining an immutable static base asset (with
     * dynamic coordinates masked out so surrounding faces stay closed under neighbor culling)
     * and an O(log N) tick-indexed dynamic block state overlay for seek-safe door/wall replay.
     */
    static @Nullable DynamicWorld prepareDynamic(@Nullable SceneAssetCodec.AssetData baseAsset,
                                                 @Nullable SceneBounds origin,
                                                 @Nullable List<ReplayBlockDelta> deltas) {
        if (baseAsset == null || baseAsset.sections.isEmpty()) return null;
        if (deltas == null || deltas.isEmpty() || origin == null || origin.isEmpty()) {
            return DynamicWorld.staticOnly(baseAsset);
        }
        String cacheKey = baseAsset.fingerprint + "#" + baseAsset.sections.size() + "#" + deltaSignature(deltas);
        DynamicWorld cached = dynamicCache.get(cacheKey);
        if (cached != null) {
            cached.touch();
            return cached;
        }
        DynamicWorld created = DynamicWorld.create(baseAsset, origin, deltas, cacheKey);
        dynamicCache.put(cacheKey, created);
        while (dynamicCache.size() > 6) dynamicCache.pollFirstEntry().getValue().close();
        return created;
    }

    private static int deltaSignature(List<ReplayBlockDelta> deltas) {
        int h = deltas.size() * 31;
        if (!deltas.isEmpty()) {
            h = h * 31 + deltas.getFirst().hashCode();
            h = h * 31 + deltas.getLast().hashCode();
            h = h * 31 + deltas.get(deltas.size() / 2).hashCode();
        }
        return h;
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
        enforceSlotBudget(asset.fingerprint);
        if (autoRetry && selected.retryAfter != 0 && System.nanoTime() - selected.retryAfter >= 0) selected.failedKey = null;
        selected.update(asset, present, total);
    }

    static void tickIdle() {
        long now = System.nanoTime();
        evictIdle(slots, now, true);
        var dynIt = dynamicCache.values().iterator();
        while (dynIt.hasNext()) {
            var dw = dynIt.next();
            if (now - dw.lastUse > IDLE_RELEASE_NS) {
                dw.close();
                dynIt.remove();
            }
        }
    }

    private static void evictIdle(Map<String, Slot> map, long now, boolean clearSelected) {
        var iterator = map.values().iterator();
        while (iterator.hasNext()) {
            var slot = iterator.next();
            if (now - slot.lastUse > IDLE_RELEASE_NS) {
                slot.reset();
                if (clearSelected && slot == selected) selected = null;
                iterator.remove();
            }
        }
    }

    static void reset() {
        slots.values().forEach(Slot::reset);
        slots.clear();
        dynamicCache.values().forEach(DynamicWorld::close);
        dynamicCache.clear();
        ReplayPovTerrain.reset();
        ReplayFirstPersonHands.reset();
        selected = null;
    }

    /** One resolved dynamic block at a given {@code matchTick}. */
    record ResolvedBlock(int localX, int localY, int localZ, String stateString, byte reasonFlags) {}

    /**
     * Seek-safe dynamic block overlay derived from an immutable base {@link SceneAssetCodec.AssetData}
     * and time-ordered {@link ReplayBlockDelta} events.
     *
     * <p>Dynamic coordinates are stripped once from the static base mesh (which preserves adjacent
     * solid faces via {@code retainHiddenFaces = true}), while the few affected doors/walls are
     * resolved in O(log N) for any {@code matchTick} (forward or backward seek) and rendered cleanly
     * in the exact same local source coordinate frame.</p>
     */
    static final class DynamicWorld {
        private final SceneAssetCodec.AssetData staticAsset;
        private final List<ReplayBlockDelta> sortedDeltas;
        private final int offsetX, offsetY, offsetZ;
        private final Map<Long, BlockHistory> histories;
        private final Map<String, BlockState> parsedCache = new HashMap<>();
        private final Map<Long, DoorBlockEntity> replayDoors = new HashMap<>();
        private int cachedRevision = -1;
        private Map<Long, ResolvedBlock> cachedStates = Map.of();
        private int bakedMeshRevision = -1;
        private @Nullable SceneMeshSet bakedMesh;
        private List<ResolvedBlock> bakedFallbacks = List.of();
        private long lastUse = System.nanoTime();

        void touch() { lastUse = System.nanoTime(); }

        void close() {
            SceneMeshSet old = bakedMesh;
            bakedMesh = null;
            bakedMeshRevision = -1;
            bakedFallbacks = List.of();
            if (old != null) old.close();
        }

        private record BlockChange(int tick, ResolvedBlock state) {}

        private record BlockHistory(ResolvedBlock initial, List<BlockChange> changes) {
            ResolvedBlock at(double tick) {
                int lo = 0, hi = changes.size();
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (changes.get(mid).tick() <= tick) lo = mid + 1;
                    else hi = mid;
                }
                return lo == 0 ? initial : changes.get(lo - 1).state();
            }
        }

        private DynamicWorld(SceneAssetCodec.AssetData staticAsset, List<ReplayBlockDelta> sortedDeltas,
                             int offsetX, int offsetY, int offsetZ, Map<Long, BlockHistory> histories) {
            this.staticAsset = staticAsset;
            this.sortedDeltas = sortedDeltas;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.histories = histories;
        }

        static DynamicWorld staticOnly(SceneAssetCodec.AssetData base) {
            return new DynamicWorld(base, List.of(), 0, 0, 0, Map.of());
        }

        static DynamicWorld create(SceneAssetCodec.AssetData base, SceneBounds origin,
                                   List<ReplayBlockDelta> deltas, String fingerprint) {
            SceneBounds src = base.sourceBounds;
            int offX = origin.minX() - src.minX();
            int offY = origin.minY() - src.minY();
            int offZ = origin.minZ() - src.minZ();

            List<ReplayBlockDelta> ordered = new ArrayList<>(deltas.size());
            boolean sorted = true;
            int prevTick = -1;
            Map<Long, int[]> uniquePositions = new LinkedHashMap<>();
            for (ReplayBlockDelta d : deltas) {
                int lx = offX + d.x();
                int ly = offY + d.y();
                int lz = offZ + d.z();
                if (lx < 0 || ly < 0 || lz < 0 || lx >= src.sizeX() || ly >= src.sizeY() || lz >= src.sizeZ()) {
                    continue;
                }
                if (d.tick() < prevTick) sorted = false;
                prevTick = d.tick();
                ordered.add(d);
                uniquePositions.putIfAbsent(d.posKey(), new int[]{lx, ly, lz});
            }
            if (ordered.isEmpty()) return staticOnly(base);
            if (!sorted) ordered.sort(Comparator.comparingInt(ReplayBlockDelta::tick));

            // Group dynamic cells by section so untouched sections are shared by reference
            Map<Long, List<long[]>> bySection = new HashMap<>();
            for (var entry : uniquePositions.entrySet()) {
                int[] loc = entry.getValue();
                int wx = src.minX() + loc[0];
                int wy = src.minY() + loc[1];
                int wz = src.minZ() + loc[2];
                int relX = Math.floorDiv(wx, 16) - src.minSectionX();
                int relY = Math.floorDiv(wy, 16) - src.minSectionY();
                int relZ = Math.floorDiv(wz, 16) - src.minSectionZ();
                int cellIdx = ((wy & 15) << 8) | ((wz & 15) << 4) | (wx & 15);
                long secKey = packSection(relX, relY, relZ);
                bySection.computeIfAbsent(secKey, k -> new ArrayList<>())
                        .add(new long[]{entry.getKey(), cellIdx, loc[0], loc[1], loc[2]});
            }

            Map<Long, ResolvedBlock> initials = new LinkedHashMap<>(uniquePositions.size());
            List<SceneAssetCodec.SectionData> patchedSections = new ArrayList<>(base.sections.size());
            for (SceneAssetCodec.SectionData sec : base.sections) {
                long secKey = packSection(sec.relX, sec.relY, sec.relZ);
                List<long[]> touches = bySection.get(secKey);
                if (touches == null) {
                    patchedSections.add(sec);
                    continue;
                }
                short[] cloned = sec.blockIndices.clone();
                for (long[] t : touches) {
                    long posKey = t[0];
                    int cellIdx = (int) t[1];
                    int lx = (int) t[2], ly = (int) t[3], lz = (int) t[4];
                    int pIdx = cellIdx >= 0 && cellIdx < cloned.length ? (cloned[cellIdx] & 0xFFFF) : 0;
                    String stateStr = pIdx > 0 && pIdx < sec.palette.size()
                            ? sec.palette.get(pIdx) : SceneAssetCodec.AIR_BLOCK_ID;
                    initials.put(posKey, new ResolvedBlock(lx, ly, lz, stateStr, ReplayBlockDelta.REASON_OTHER));
                    if (cellIdx >= 0 && cellIdx < cloned.length) {
                        cloned[cellIdx] = 0; // Mask out so base mesh leaves adjacent faces closed
                    }
                }
                patchedSections.add(new SceneAssetCodec.SectionData(
                        sec.relX, sec.relY, sec.relZ, sec.palette, cloned, sec.skyLight, sec.blockLight));
            }
            // Any dynamic block in a previously-air section starts as air
            for (var entry : uniquePositions.entrySet()) {
                initials.putIfAbsent(entry.getKey(),
                        new ResolvedBlock(entry.getValue()[0], entry.getValue()[1], entry.getValue()[2],
                                SceneAssetCodec.AIR_BLOCK_ID, ReplayBlockDelta.REASON_OTHER));
            }

            SceneAssetCodec.AssetData stripped = new SceneAssetCodec.AssetData(
                    base.formatVersion, base.dataVersion, base.dimensionId,
                    base.sourceBounds, base.haloBounds, fingerprint, patchedSections, base.blockPayloads);
            Map<Long, List<BlockChange>> changes = new HashMap<>(initials.size());
            for (ReplayBlockDelta delta : ordered) {
                var state = new ResolvedBlock(offX + delta.x(), offY + delta.y(), offZ + delta.z(),
                        delta.blockState(), delta.reasonFlags());
                changes.computeIfAbsent(delta.posKey(), key -> new ArrayList<>())
                        .add(new BlockChange(delta.tick(), state));
            }
            Map<Long, BlockHistory> histories = new LinkedHashMap<>(initials.size());
            for (var entry : initials.entrySet()) {
                histories.put(entry.getKey(), new BlockHistory(entry.getValue(), changes.get(entry.getKey())));
            }
            return new DynamicWorld(stripped, List.copyOf(ordered), offX, offY, offZ, histories);
        }

        /** Static base asset with dynamic coordinates masked out (pass into {@link ReplayDioramaMesh#update}). */
        SceneAssetCodec.AssetData staticAsset() { return staticAsset; }

        boolean hasDynamics() { return !sortedDeltas.isEmpty(); }

        boolean manages(int localX, int localY, int localZ) {
            long key = packSection(localX - offsetX, localY - offsetY, localZ - offsetZ);
            return histories.containsKey(key);
        }

        /** Count of deltas with {@code tick <= matchTick}, computed in O(log N). */
        int revisionAt(double matchTick) {
            int lo = 0, hi = sortedDeltas.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (sortedDeltas.get(mid).tick() <= matchTick) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        /**
         * Returns the exact state of every dynamic block at {@code matchTick}.
         * Works identically for forward playback and arbitrary backward/forward Seek.
         */
        Map<Long, ResolvedBlock> resolveAt(double matchTick) {
            if (sortedDeltas.isEmpty()) return Map.of();
            int rev = revisionAt(matchTick);
            if (rev == cachedRevision) return cachedStates;
            Map<Long, ResolvedBlock> map = LinkedHashMap.newLinkedHashMap(histories.size());
            for (var entry : histories.entrySet()) {
                map.put(entry.getKey(), entry.getValue().at(matchTick));
            }
            cachedRevision = rev;
            cachedStates = Collections.unmodifiableMap(map);
            return cachedStates;
        }

        /**
         * Renders all standard model dynamic blocks via a revision-cached static {@link SceneMeshSet}
         * inside the active {@code SceneShaders} scope (sharing terrain lighting, blackout dim, scan clip
         * and fan cutaway), rebuilding only when the block-delta revision changes.
         */
        void renderStaticBatched(Minecraft mc, Matrix4f modelView, Matrix4f projection, ReplayFanCutaway fans,
                                 float minX, float minZ, float maxX, float maxZ, int scanAxis, float scanLimit,
                                 double matchTick) {
            if (sortedDeltas.isEmpty() || mc == null) return;
            touch();
            ensureBakedMesh(mc, matchTick);
            if (bakedMesh != null && !bakedMesh.isEmpty() && !bakedMesh.isClosed()) {
                ReplayTerrainRenderer.draw(bakedMesh, modelView, projection, fans,
                        minX, minZ, maxX, maxZ, scanAxis, scanLimit);
            }
        }

        /** Renders only non-static animated block-entity fallbacks (if any) for the current revision. */
        void renderDynamicFallbacks(Minecraft mc, Matrix4f modelToCamera, MultiBufferSource buffers,
                                    double matchTick, @Nullable ReplayFanCutaway fanCutaway, int packedLight) {
            if (sortedDeltas.isEmpty() || mc == null) return;
            touch();
            ensureBakedMesh(mc, matchTick);
            if (bakedFallbacks.isEmpty()) return;
            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
            int fallbackLight = packedLight == 0 ? LightTexture.FULL_BRIGHT : packedLight;
            PoseStack stack = new PoseStack();
            for (int i = 0; i < bakedFallbacks.size(); i++) {
                ResolvedBlock rb = bakedFallbacks.get(i);
                if (fanCutaway != null && fanCutaway.removes(rb.localX() + 0.5, rb.localY() + 0.5, rb.localZ() + 0.5)) {
                    continue;
                }
                BlockState state = parsedCache.computeIfAbsent(rb.stateString(), SceneMeshBuilder::parseBlockState);
                if (state == null || state.isAir() || state.getBlock() == Blocks.AIR) continue;
                stack.pushPose();
                stack.mulPose(modelToCamera);
                stack.translate(rb.localX(), rb.localY(), rb.localZ());
                if (isSreOpenDoor(state)) applyOpenDoorSwing(stack, state);
                dispatcher.renderSingleBlock(state, stack, buffers, fallbackLight, OverlayTexture.NO_OVERLAY);
                stack.popPose();
            }
        }

        private void ensureBakedMesh(Minecraft mc, double matchTick) {
            Map<Long, ResolvedBlock> active = resolveAt(matchTick);
            if (bakedMeshRevision == cachedRevision) return;
            SceneMeshSet previous = bakedMesh;
            bakedMesh = null;
            bakedMeshRevision = cachedRevision;
            if (previous != null) previous.close();
            if (active.isEmpty()) {
                bakedFallbacks = List.of();
                return;
            }
            List<SceneMeshBuilder.DynamicBlockSpec> specs = new ArrayList<>(active.size());
            List<ResolvedBlock> fallbacks = null;
            for (ResolvedBlock rb : active.values()) {
                BlockState state = parsedCache.computeIfAbsent(rb.stateString(), SceneMeshBuilder::parseBlockState);
                if (state == null || state.isAir() || state.getBlock() == Blocks.AIR) continue;
                if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                    float yaw = 0f, hx = 0f, hz = 0f;
                    if (isSreOpenDoor(state)) {
                        net.minecraft.core.Direction facing = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                                ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                                : net.minecraft.core.Direction.NORTH;
                        hx = 0.5f + facing.getClockWise().getStepX() * 0.44f;
                        hz = 0.5f + facing.getClockWise().getStepZ() * 0.44f;
                        yaw = 88f;
                    }
                    specs.add(new SceneMeshBuilder.DynamicBlockSpec(
                            rb.localX(), rb.localY(), rb.localZ(), state, yaw, hx, hz));
                } else {
                    if (fallbacks == null) fallbacks = new ArrayList<>();
                    fallbacks.add(rb);
                }
            }
            bakedFallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
            if (!specs.isEmpty()) {
                bakedMesh = SceneMeshBuilder.buildDynamicBlocksMesh(staticAsset, specs);
            }
        }

        /**
         * Renders all active non-air dynamic blocks (doors, windows, breakable walls) at {@code matchTick}
         * in the exact same local source coordinate frame as the static {@link SceneMeshSet}.
         */
        void render(Minecraft mc, Matrix4f modelToCamera, MultiBufferSource buffers,
                    double matchTick, int roofCutLayer, @Nullable ReplayFanCutaway fanCutaway, int packedLight) {
            render(mc, modelToCamera, buffers, matchTick, roofCutLayer, fanCutaway, packedLight, null);
        }

        void render(Minecraft mc, Matrix4f modelToCamera, MultiBufferSource buffers, double matchTick,
                    int roofCutLayer, @Nullable ReplayFanCutaway fanCutaway, int packedLight,
                    @Nullable ReplayPovTerrain terrain) {
            if (sortedDeltas.isEmpty() || mc == null) return;
            touch();
            Map<Long, ResolvedBlock> active = resolveAt(matchTick);
            if (active.isEmpty()) return;
            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
            int fallbackLight = packedLight == 0 ? LightTexture.FULL_BRIGHT : packedLight;
            PoseStack stack = new PoseStack();
            for (var entry : active.entrySet()) {
                ResolvedBlock rb = entry.getValue();
                if (roofCutLayer > 0 && rb.localY() >= roofCutLayer) continue;
                if (fanCutaway != null && fanCutaway.removes(rb.localX() + 0.5, rb.localY() + 0.5, rb.localZ() + 0.5)) {
                    continue;
                }
                BlockState state = parsedCache.computeIfAbsent(rb.stateString(), SceneMeshBuilder::parseBlockState);
                if (state == null || state.isAir() || state.getBlock() == Blocks.AIR) continue;
                stack.pushPose();
                stack.mulPose(modelToCamera);
                stack.translate(rb.localX(), rb.localY(), rb.localZ());
                int light = fallbackLight;
                if (terrain != null) {
                    var bounds = staticAsset.sourceBounds;
                    var position = new BlockPos(bounds.minX() + rb.localX(), bounds.minY() + rb.localY(), bounds.minZ() + rb.localZ());
                    light = terrain.lightAt(position.getX(), position.getY(), position.getZ(), fallbackLight);
                    if (state.getBlock() instanceof DoorPartBlock doorBlock) {
                        DoorBlockEntity door = replayDoors.get(entry.getKey());
                        if (door == null || door.getBlockState() != state) {
                            door = doorBlock.newBlockEntity(position, state) instanceof DoorBlockEntity blockEntity ? blockEntity : null;
                            if (door != null) replayDoors.put(entry.getKey(), door);
                        }
                        if (door != null && ReplayPovTerrain.renderDoor(mc, door, stack, buffers,
                                terrain.doorLight(position, fallbackLight))) {
                            stack.popPose();
                            continue;
                        }
                    }
                }
                // SRE SmallDoorBlock/DoorPartBlock uses a single static blockstate variant (BER animated in live world);
                // rotate the leaf open by 90 degrees around its hinge edge when OPEN=true so open doors are visually unmistakable.
                if (isSreOpenDoor(state)) {
                    applyOpenDoorSwing(stack, state);
                }
                dispatcher.renderSingleBlock(state, stack, buffers, light, OverlayTexture.NO_OVERLAY);
                stack.popPose();
            }
        }

        private static boolean isSreOpenDoor(BlockState state) {
            if (!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) return false;
            if (!state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) return false;
            return state.getBlock() instanceof io.wifi.starrailexpress.content.block.DoorPartBlock;
        }

        private static void applyOpenDoorSwing(PoseStack stack, BlockState state) {
            net.minecraft.core.Direction facing = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    : net.minecraft.core.Direction.NORTH;
            // Pivot around the clockwise hinge edge of the door block and swing 88 degrees open
            float hx = 0.5f + facing.getClockWise().getStepX() * 0.44f;
            float hz = 0.5f + facing.getClockWise().getStepZ() * 0.44f;
            stack.translate(hx, 0f, hz);
            stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(88f));
            stack.translate(-hx, 0f, -hz);
        }

        private static long packSection(int rx, int ry, int rz) {
            return (((long) rx & 0x1FFFFFL) << 42) | (((long) rz & 0x1FFFFFL) << 21) | ((long) ry & 0x1FFFFFL);
        }
    }

    static final class Slot {
        private final boolean cutaway;
        private @Nullable SceneMeshSet mesh;
        private @Nullable Key builtKey;
        private @Nullable Key failedKey;
        private @Nullable CompletableFuture<SceneMeshBuilder.MeshBuildResult> pending;
        private @Nullable Key pendingKey;
        private Object ticket = new Object();
        private long lastSubmit, lastUse;
        private long retryAfter;

        Slot() { this(true); }

        Slot(boolean cutaway) { this.cutaway = cutaway; }



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
                future = cutaway
                        ? SceneMeshBuilder.buildCutawayMeshAsync(asset, SceneBuildScheduler.Priority.ADDITIONAL)
                        : SceneMeshBuilder.buildSolidReplayMeshAsync(asset, SceneBuildScheduler.Priority.ADDITIONAL);
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

        boolean hasFailed(Key key) { return key.equals(failedKey); }

        long estimatedBytes() {
            SceneMeshSet m = mesh;
            return (m == null || m.isClosed()) ? 0L : m.estimatedBytes();
        }

        void installBuilt(Key key, @Nullable SceneMeshSet built, @Nullable Throwable failure) {
            if (failure != null || built == null || built.isClosed() || built.isEmpty()) {
                if (built != null) built.close();
                Throwable cause = failure instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null
                        ? ce.getCause() : failure;
                if (cause instanceof java.util.concurrent.CancellationException) {
                    // Aborted across a level switch or superseded build: keep failedKey clear so the new level rebuilds cleanly.
                    return;
                }
                if (cause != null) {
                    LOGGER.warn("Replay diorama mesh build failed", cause);
                }
                // Remember the key so a failing input does not resubmit every frame.
                failedKey = key;
                retryAfter = cause == null ? 0 : System.nanoTime() + FAILURE_RETRY_NS;
                return;
            }
            SceneMeshSet previous = mesh;
            mesh = built;
            builtKey = key;
            failedKey = null;
            retryAfter = 0;
            if (previous != null && previous != built) previous.close();
            enforceSlotBudget(key.fingerprint());
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
