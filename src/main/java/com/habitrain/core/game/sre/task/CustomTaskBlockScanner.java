package com.habitrain.core.game.sre.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.network.CustomTaskBlockPayload;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public final class CustomTaskBlockScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomTaskBlockScanner.class);
    private static final int TICK_BUDGET_NS = 2_000_000;
    private static final int MAX_SECTIONS_PER_TICK = 8;
    private static final int CHUNK_TIMEOUT_TICKS = 200;
    private static final TicketType<ChunkPos> TICKET = TicketType.create("habitrain_task_scan",
            Comparator.comparingLong(ChunkPos::toLong));
    private static final ThreadPoolExecutor DISK = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), work -> {
                Thread thread = new Thread(work, "habitrain-task-scan-cache");
                thread.setDaemon(true);
                return thread;
            });
    private static Session active;

    private CustomTaskBlockScanner() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> active = new Session(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Session session = session(server);
            if (session != null) {
                active = null;
                session.clear(false);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Session session = session(server);
            if (session != null) session.tick();
        });
    }

    public static void beginMapLoad(ServerLevel level) {
        if (level == null) return;
        Session session = session(level.getServer());
        if (session != null) session.invalidate();
    }

    public static void cancelPending(ServerLevel level) {
        if (level == null) return;
        Session session = session(level.getServer());
        if (session != null && session.scan != null && session.context.level == level) session.invalidate();
    }

    public static void ensureCurrent(ServerLevel level, AreasWorldComponent areas) {
        request(level, areas, false);
    }

    public static void scan(ServerLevel level, AreasWorldComponent areas) {
        request(level, areas, true);
    }

    private static void request(ServerLevel level, AreasWorldComponent areas, boolean force) {
        if (level == null) return;
        Session session = session(level.getServer());
        if (session == null) return;
        if (force) session.invalidate();
        BoundingBox bounds = resolveScanBox(areas);
        if (bounds == null) {
            session.clear(true);
            return;
        }
        if (session.context != null && session.context.level == level && matchesArea(session.context.key, areas.mapName, bounds)
                && (session.scan != null || session.snapshot != null)) return;
        session.clear(true);
        var key = createKey(level, areas, bounds, ++session.revision);
        session.context = new Context(level, key);
        session.scan = new Scan(session.context, session.allowDisk);
    }

    public static Map<BlockPos, Set<Integer>> snapshotFor(ServerLevel level) {
        Session session = session(level.getServer());
        if (session == null || session.context == null || session.snapshot == null
                || session.context.level != level || !session.context.matches()) return Map.of();
        return session.snapshot;
    }

    private static Session session(MinecraftServer server) {
        Session session = active;
        return session != null && session.server == server ? session : null;
    }

    private static final class Session {
        final MinecraftServer server;
        Context context;
        Scan scan;
        Map<BlockPos, Set<Integer>> snapshot;
        long revision;
        boolean allowDisk = true;

        Session(MinecraftServer server) { this.server = server; }

        void invalidate() {
            allowDisk = false;
            clear(true);
        }

        void clear(boolean broadcast) {
            Context previous = context;
            boolean hadSnapshot = snapshot != null && !snapshot.isEmpty();
            context = null;
            snapshot = null;
            if (scan != null) scan.cancel();
            scan = null;
            if (broadcast && hadSnapshot) CustomTaskBlockPayload.broadcastToLevel(previous.level);
        }

        void tick() {
            if (context == null) return;
            if (scan == null && server.getTickCount() % 10 != 0) return;
            if (!context.matches()) {
                clear(true);
                return;
            }
            if (scan == null) return;
            try {
                long startedStep = System.nanoTime();
                var result = scan.step();
                scan.longestStep = Math.max(scan.longestStep, System.nanoTime() - startedStep);
                if (result == null) return;
                snapshot = result;
                LOGGER.info("Custom task scan completed: map={}, revision={}, source={}, sections={}, entries={}, elapsedMs={}, longestStepUs={}",
                        context.key.mapName(), context.key.mapRevision(), scan.finished == null ? "disk" : "world",
                        scan.sections, snapshot.size(), (System.nanoTime() - scan.startedAt) / 1_000_000, scan.longestStep / 1_000);
                if (scan.finished != null) scan.persist(result);
                scan = null;
                CustomTaskBlockPayload.broadcastToLevel(context.level);
            } catch (Exception failure) {
                LOGGER.warn("Custom task scan aborted for {}", context.key.mapName(), failure);
                clear(true);
            }
        }
    }

    private record Context(ServerLevel level, CustomTaskBlockScanKey key) {
        boolean matches() {
            if (level.getServer().getLevel(level.dimension()) != level) return false;
            var areas = AreasWorldComponent.KEY.get(level);
            return matchesArea(key, areas == null ? null : areas.mapName, resolveScanBox(areas));
        }
    }

    static boolean matchesArea(CustomTaskBlockScanKey key, String mapName, BoundingBox box) {
        return box != null && key.mapName().equals(mapName == null ? "" : mapName)
                && key.minX() == box.minX() && key.minY() == box.minY() && key.minZ() == box.minZ()
                && key.maxX() == box.maxX() && key.maxY() == box.maxY() && key.maxZ() == box.maxZ();
    }

    private static final class Scan {
        final Context context;
        final CustomTaskBlockScanKey diskKey;
        final File file;
        final Map<Block, Set<Integer>> typesByBlock;
        final CustomTaskBlockCache index = new CustomTaskBlockCache();
        final long startedAt = System.nanoTime();
        long longestStep;
        final int minY, maxY;
        int chunkX, chunkZ, sectionY;
        int sections;
        ChunkPos ticket;
        long ticketDeadline;
        Future<Optional<Map<BlockPos, Set<Integer>>>> diskRead;
        Future<Map<BlockPos, Set<Integer>>> finished;

        Scan(Context context, boolean allowDisk) {
            this.context = context;
            var key = context.key;
            diskKey = toDiskScanKey(key);
            file = CustomTaskBlockDiskCache.getCacheFile(context.level, key.mapName());
            typesByBlock = scanTypes();
            chunkX = key.minX() >> 4;
            chunkZ = key.minZ() >> 4;
            minY = Math.max(key.minY(), context.level.getMinBuildHeight());
            maxY = Math.min(key.maxY(), context.level.getMaxBuildHeight() - 1);
            sectionY = minY >> 4;
            if (allowDisk && file != null) {
                try {
                    diskRead = DISK.submit(() -> CustomTaskBlockDiskCache.loadValidated(file, diskKey));
                } catch (RejectedExecutionException busy) {
                    LOGGER.debug("Task disk cache busy; scanning {}", key.mapName());
                }
            }
        }

        Map<BlockPos, Set<Integer>> step() throws Exception {
            if (finished != null) return finished.isDone() ? finished.get() : null;
            if (diskRead != null) {
                if (!diskRead.isDone()) return null;
                var hit = diskRead.get();
                diskRead = null;
                if (hit.isPresent()) return hit.get();
            }
            var key = context.key;
            var level = context.level;
            if (typesByBlock.isEmpty() || minY > maxY || chunkX > (key.maxX() >> 4)) {
                finish();
                return null;
            }
            long deadline = System.nanoTime() + TICK_BUDGET_NS;
            for (int work = 0; work < MAX_SECTIONS_PER_TICK && System.nanoTime() - deadline < 0; work++) {
                var chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    if (ticket == null) {
                        ticket = new ChunkPos(chunkX, chunkZ);
                        ticketDeadline = (long) level.getServer().getTickCount() + CHUNK_TIMEOUT_TICKS;
                        level.getChunkSource().addRegionTicket(TICKET, ticket, 0, ticket);
                    } else if (level.getServer().getTickCount() >= ticketDeadline) {
                        throw new IllegalStateException("Timed out loading chunk " + ticket);
                    }
                    return null;
                }
                var section = chunk.getSection(level.getSectionIndex(sectionY << 4));
                if (!section.hasOnlyAir() && section.maybeHas(state -> typesByBlock.containsKey(state.getBlock()))) {
                    collect(section, key);
                }
                sections++;
                if (++sectionY <= (maxY >> 4)) continue;
                release();
                sectionY = minY >> 4;
                if (++chunkZ <= (key.maxZ() >> 4)) continue;
                chunkZ = key.minZ() >> 4;
                if (++chunkX > (key.maxX() >> 4)) {
                    finish();
                    return null;
                }
            }
            return null;
        }

        private void collect(net.minecraft.world.level.chunk.LevelChunkSection section, CustomTaskBlockScanKey key) {
            int baseX = chunkX << 4, baseY = sectionY << 4, baseZ = chunkZ << 4;
            var cursor = new BlockPos.MutableBlockPos();
            for (int x = Math.max(key.minX(), baseX); x <= Math.min(key.maxX(), baseX + 15); x++) {
                for (int z = Math.max(key.minZ(), baseZ); z <= Math.min(key.maxZ(), baseZ + 15); z++) {
                    for (int y = Math.max(minY, baseY); y <= Math.min(maxY, baseY + 15); y++) {
                        var block = section.getBlockState(x & 15, y & 15, z & 15).getBlock();
                        var types = typesByBlock.get(block);
                        if (types != null) {
                            cursor.set(x, y, z);
                            for (int type : types) index.put(cursor, type, block);
                        }
                    }
                }
            }
        }

        private void finish() {
            var completedIndex = index;
            try {
                finished = DISK.submit(completedIndex::immutableSnapshot);
            } catch (RejectedExecutionException busy) {
                LOGGER.debug("Task disk cache busy; deferring snapshot for {}", diskKey.mapName());
            }
        }

        void persist(Map<BlockPos, Set<Integer>> result) {
            if (file == null) return;
            File target = file;
            var key = diskKey;
            try {
                DISK.execute(() -> CustomTaskBlockDiskCache.save(target, key, result));
            } catch (RejectedExecutionException busy) {
                LOGGER.warn("Task disk cache busy; not saving {}", key.mapName());
            }
        }

        void cancel() {
            release();
            if (diskRead != null) diskRead.cancel(false);
            if (finished != null) finished.cancel(false);
            DISK.purge();
        }

        private void release() {
            if (ticket == null) return;
            context.level.getChunkSource().removeRegionTicket(TICKET, ticket, 0, ticket);
            ticket = null;
        }
    }

    private static Map<Block, Set<Integer>> scanTypes() {
        var map = new HashMap<Block, Set<Integer>>();
        for (var definition : TaskRegistry.getAll()) {
            int type = definition.getBlockTypeId();
            if (type < CustomTaskBlockIndexLimits.CUSTOM_OVERLAY_MIN_TYPE_ID) continue;
            for (var block : definition.getScanBlocks()) {
                if (block != Blocks.AIR) map.computeIfAbsent(block, unused -> new TreeSet<>()).add(type);
            }
            for (String id : definition.getScanBlockIds()) {
                var location = ResourceLocation.tryParse(id);
                if (location == null) continue;
                var block = BuiltInRegistries.BLOCK.get(location);
                if (block != Blocks.AIR) map.computeIfAbsent(block, unused -> new TreeSet<>()).add(type);
            }
        }
        return map;
    }

    private static CustomTaskBlockScanKey createKey(ServerLevel level, AreasWorldComponent areas, BoundingBox box, long revision) {
        return new CustomTaskBlockScanKey(level.dimension().location().toString(), areas.mapName,
                box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString(),
                definitionFingerprint(), Long.toString(revision));
    }

    static CustomTaskBlockScanKey toDiskScanKey(CustomTaskBlockScanKey key) {
        return new CustomTaskBlockScanKey(key.dimensionId(), key.mapName(), key.minX(), key.minY(), key.minZ(),
                key.maxX(), key.maxY(), key.maxZ(), key.worldId(), key.definitionFingerprint(), "disk-v2");
    }

    static CustomTaskBlockScanKey currentContext(ServerLevel level) {
        if (level == null) return null;
        var areas = AreasWorldComponent.KEY.get(level);
        var box = resolveScanBox(areas);
        return box == null ? null : toDiskScanKey(createKey(level, areas, box, 0));
    }

    private static String definitionFingerprint() {
        List<String> definitions = new ArrayList<>();
        for (var definition : TaskRegistry.getAll()) {
            if (definition.getBlockTypeId() < CustomTaskBlockIndexLimits.CUSTOM_OVERLAY_MIN_TYPE_ID) continue;
            List<String> blocks = new ArrayList<>(definition.getScanBlockIds());
            for (Block block : definition.getScanBlocks()) blocks.add(BuiltInRegistries.BLOCK.getKey(block).toString());
            Collections.sort(blocks);
            definitions.add(definition.getFullId() + ":" + definition.getBlockTypeId() + ":" + String.join(",", blocks));
        }
        Collections.sort(definitions);
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", definitions).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static BoundingBox resolveScanBox(AreasWorldComponent areas) {
        if (areas == null) return null;
        AABB paste = areas.getResetPasteArea();
        if (paste == null) return null;
        AABB template = areas.getResetTemplateArea();
        BlockPos start = BlockPos.containing(paste.getMinPosition());
        if (template == null) return BoundingBox.fromCorners(start, BlockPos.containing(paste.getMaxPosition()));
        var templateBox = BoundingBox.fromCorners(BlockPos.containing(template.getMinPosition()),
                BlockPos.containing(template.getMaxPosition()));
        return BoundingBox.fromCorners(start, start.offset(templateBox.getLength()));
    }
}
