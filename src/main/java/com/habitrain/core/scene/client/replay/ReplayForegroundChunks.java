package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayBlockDelta;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.client.SceneMeshBuilder;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Method;
import java.util.*;

final class ReplayForegroundChunks {
    private final ClientLevel level;
    private final SceneBounds source;
    private final SceneBounds origin;
    private final Map<ChunkPos, List<SceneAssetCodec.SectionData>> columns = new HashMap<>();
    private final Set<ChunkPos> loaded = new HashSet<>();
    private final Map<ChunkPos, Map<BlockPos, BlockState>> changes = new HashMap<>();
    private final Map<BlockPos, BlockState> originals = new HashMap<>();
    private final List<ReplayBlockDelta> deltas;
    private final int radius;
    private final Object tracker;
    private final Method lightReady;
    private int cursor;
    private ChunkPos center;
    private final Deque<ChunkPos> pending = new ArrayDeque<>();
    private final Map<ChunkPos, List<com.habitrain.core.network.replay.VisualTape.Event>> visualEvents = new HashMap<>();
    private final Map<ChunkPos, Integer> cursors = new HashMap<>();
    private final Map<BlockPos, io.wifi.starrailexpress.content.block_entity.DoorBlockEntity> animating = new HashMap<>();
    private int visualTick = -1;
    private int lastRecordedState = -1;

    void visualEvents(List<com.habitrain.core.network.replay.VisualTape.Event> events) throws java.io.IOException {
        for (var event : events) {
            if (event.kind() != com.habitrain.core.network.replay.VisualTape.BLOCK && event.kind() != com.habitrain.core.network.replay.VisualTape.TILE && event.kind() != com.habitrain.core.network.replay.VisualTape.STATE) continue;
            if (event.kind()==com.habitrain.core.network.replay.VisualTape.STATE) lastRecordedState=event.tick();
            var pos = BlockPos.of(event.input().readLong());
            visualEvents.computeIfAbsent(new ChunkPos(pos), ignored -> new ArrayList<>()).add(event);
        }
    }

    ReplayForegroundChunks(ClientLevel level, SceneAssetCodec.AssetData asset, SceneBounds origin,
                           List<ReplayBlockDelta> deltas, int radius) throws ReflectiveOperationException {
        this.level = level;
        this.source = asset.sourceBounds;
        this.origin = origin;
        this.deltas = deltas.stream().sorted(Comparator.comparingInt(ReplayBlockDelta::tick)).toList();
        this.radius = radius;
        for (var section : asset.sections) {
            var pos = new ChunkPos(source.minSectionX() + section.relX, source.minSectionZ() + section.relZ);
            columns.computeIfAbsent(pos, ignored -> new ArrayList<>()).add(section);
        }
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            Class<?> holder = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder");
            tracker = holder.getMethod("sodium$getTracker").invoke(level);
            lightReady = tracker.getClass().getMethod("onChunkStatusAdded", int.class, int.class, int.class);
        } else { tracker = null; lightReady = null; }
    }

    boolean prepare(ChunkPos eye, double tick) throws ReflectiveOperationException, java.io.IOException {
        if ((int)tick < visualTick && !visualEvents.isEmpty()) {
            for (var pos : loaded) level.getChunkSource().drop(pos);
            loaded.clear(); cursors.clear(); animating.clear(); changes.clear(); originals.clear(); cursor = 0; center = null;
        }
        visualTick = (int)tick;
        if (!eye.equals(center)) {
            center = eye;
            level.getChunkSource().updateViewCenter(eye.x, eye.z);
            loaded.removeIf(pos -> {
                if (pos.getChessboardDistance(eye) <= radius) return false;
                level.getChunkSource().drop(pos);
                cursors.remove(pos);
                return true;
            });
            pending.clear();
            var wanted = new HashSet<ChunkPos>();
            for (var pos : columns.keySet()) {
                if (pos.getChessboardDistance(eye) > radius + 1) continue;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    var neighbor = new ChunkPos(pos.x + dx, pos.z + dz);
                    if (!loaded.contains(neighbor) && neighbor.getChessboardDistance(eye) <= radius) wanted.add(neighbor);
                }
            }
            wanted.stream().sorted(Comparator.comparingInt(eye::getChessboardDistance)).forEach(pending::add);
        }
        if (cursor > 0 && deltas.get(cursor - 1).tick() > tick) {
            originals.forEach((pos, state) -> { if (loaded.contains(new ChunkPos(pos))) level.setBlock(pos, state, 11); });
            changes.clear();
            cursor = 0;
        }
        while (cursor < deltas.size() && deltas.get(cursor).tick() <= tick) {
            var change = deltas.get(cursor++);
            if (change.tick()<=lastRecordedState) continue;
            var pos = new BlockPos(origin.minX() + change.x(), origin.minY() + change.y(), origin.minZ() + change.z());
            var column = new ChunkPos(pos);
            var state = SceneMeshBuilder.parseBlockState(change.blockState());
            changes.computeIfAbsent(column, ignored -> new HashMap<>()).put(pos, state);
            if (loaded.contains(column)) apply(pos, state);
        }
        long deadline = System.nanoTime() + 4_000_000;
        for (int count = 0; count < 3 && !pending.isEmpty(); count++) {
            importColumn(pending.remove());
            if (System.nanoTime() >= deadline) break;
        }
        level.getLightEngine().runLightUpdates();
        for (var pos : loaded) {
            var events = visualEvents.get(pos); if (events == null) continue;
            int index = cursors.getOrDefault(pos, 0);
            while (index < events.size() && events.get(index).tick() <= tick) applyVisual(events.get(index++));
            cursors.put(pos, index);
        }
        return pending.isEmpty();
    }

    void alignDoorAnimations() {
        animating.entrySet().removeIf(entry -> {
            if (entry.getValue().isRemoved() || !loaded.contains(new ChunkPos(entry.getKey()))) return true;
            ((com.habitrain.core.client.mixin.ReplayDoorAgeMixin) entry.getValue()).habitrain$visualAge(visualTick);
            return false;
        });
    }

    private void applyVisual(com.habitrain.core.network.replay.VisualTape.Event event) throws java.io.IOException {
        var in = event.input(); var pos = BlockPos.of(in.readLong()); String key = in.readUTF();
        if (event.kind() == com.habitrain.core.network.replay.VisualTape.STATE) { level.setBlock(pos, SceneMeshBuilder.parseBlockState(key), 11); return; }
        var entity = level.getBlockEntity(pos);
        if (event.kind() == com.habitrain.core.network.replay.VisualTape.TILE) {
            if (entity != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(key)) entity.loadWithComponents(net.minecraft.nbt.NbtIo.read(in,net.minecraft.nbt.NbtAccounter.create(1024*1024)),level.registryAccess());
        } else {
            var block = level.getBlockState(pos).getBlock();
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString().equals(key)) return;
            if (entity instanceof io.wifi.starrailexpress.content.block_entity.DoorBlockEntity door) {
                ((com.habitrain.core.client.mixin.ReplayDoorAgeMixin)door).habitrain$visualAge(event.tick()); animating.put(pos,door);
            }
            level.blockEvent(pos,block,in.readInt(),in.readInt());
        }
    }

    private void apply(BlockPos pos, BlockState state) {
        originals.computeIfAbsent(pos, level::getBlockState);
        level.setBlock(pos, state, 11);
    }

    private void importColumn(ChunkPos pos) throws ReflectiveOperationException {
        var chunk = new LevelChunk(level, pos);
        List<BlockPos> entityPositions = new ArrayList<>();
        var sections = columns.getOrDefault(pos, List.of());
        for (var section : sections) {
            int index = level.getSectionIndexFromSectionY(source.minSectionY() + section.relY);
            if (index < 0 || index >= chunk.getSections().length) continue;
            var dest = chunk.getSection(index);
            var palette = section.palette.stream().map(SceneMeshBuilder::parseBlockState).toList();
            for (int cell = 0; cell < section.blockIndices.length; cell++) {
                int entry = Short.toUnsignedInt(section.blockIndices[cell]);
                if (entry > 0 && entry < palette.size()) {
                    var state = palette.get(entry);
                    dest.setBlockState(cell & 15, cell >> 8, cell >> 4 & 15, state, false);
                    if (state.hasBlockEntity()) entityPositions.add(new BlockPos(pos.getMinBlockX()+(cell&15), ((source.minSectionY()+section.relY)<<4)+(cell>>8), pos.getMinBlockZ()+(cell>>4&15)));
                }
            }
        }
        if (!sections.isEmpty()) Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.MOTION_BLOCKING));
        var packet = new ClientboundLevelChunkPacketData(chunk);
        var bytes = packet.getReadBuffer();
        LevelChunk installed;
        try { installed = level.getChunkSource().replaceWithPacketData(pos.x, pos.z, bytes, packet.getHeightmaps(), ignored -> {}); }
        finally { bytes.release(); }
        if (installed == null) throw new IllegalStateException("Replay column outside view: " + pos);
        for (var position : entityPositions) installed.getBlockEntity(position, LevelChunk.EntityCreationType.IMMEDIATE);
        var engine = level.getLightEngine();
        engine.setLightEnabled(pos, true);
        installed.initializeLightSources();
        for (var section : sections) {
            int sectionY = source.minSectionY() + section.relY;
            var sectionPos = SectionPos.of(pos, sectionY);
            int index = level.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= installed.getSections().length) continue;
            engine.updateSectionStatus(sectionPos, installed.getSection(index).hasOnlyAir());
            if (ReplaySectionGeometry.hasRecordedLight(section)) {
                engine.queueSectionData(LightLayer.SKY, sectionPos, new DataLayer(section.skyLight.clone()));
                engine.queueSectionData(LightLayer.BLOCK, sectionPos, new DataLayer(section.blockLight.clone()));
            }
            level.setSectionDirtyWithNeighbors(pos.x, sectionY, pos.z);
        }
        engine.propagateLightSources(pos);
        installed.setLightCorrect(true);
        if (tracker != null) lightReady.invoke(tracker, pos.x, pos.z, 3);
        loaded.add(pos);
        changes.getOrDefault(pos, Map.of()).forEach(this::apply);
    }
}
