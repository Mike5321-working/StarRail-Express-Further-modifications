package com.habitrain.core.game.sre.task;

import com.habitrain.core.api.TaskRegistry;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CustomTaskBlockCacheTest {
    private final CustomTaskBlockCache cache = new CustomTaskBlockCache();

    @BeforeEach
    @AfterEach
    void resetCache() {
        cache.clear();
    }

    @Test
    void putRefusesAfterDefaultTypeCap() {
        int cap = CustomTaskBlockIndexLimits.DEFAULT_TYPE_CAP;
        for (int i = 0; i < cap; i++) {
            assertTrue(cache.put(new BlockPos(i, 0, 0), 20));
        }
        assertFalse(cache.put(new BlockPos(cap, 0, 0), 20));
        assertEquals(cap, cache.size());
        assertEquals(cap, cache.positionsForType(20).size());
        assertTrue(cache.positionsForType(21).isEmpty());
    }

    @Test
    void putRefusesAfterGlobalCap() {
        int cap = CustomTaskBlockIndexLimits.DEFAULT_TYPE_CAP;
        int global = CustomTaskBlockCache.MAX_ENTRIES;
        for (int i = 0; i < global; i++) {
            assertTrue(cache.put(new BlockPos(i, 1, 0), 100 + i / cap));
        }
        assertFalse(cache.put(new BlockPos(global, 1, 0), 100 + global / cap));
        assertEquals(global, cache.size());
    }

    @Test
    void bucketsReturnOnlyThatType() {
        BlockPos coal = new BlockPos(1, 2, 3);
        BlockPos other = new BlockPos(4, 5, 6);
        assertTrue(cache.put(coal, 20));
        assertTrue(cache.put(coal, 21));
        assertTrue(cache.put(other, 21));

        Set<BlockPos> type20 = cache.positionsForType(20);
        Set<BlockPos> type21 = cache.positionsForType(21);
        assertEquals(Set.of(coal), Set.copyOf(type20));
        assertEquals(Set.of(coal, other), Set.copyOf(type21));
        assertEquals(Set.of(coal, other), cache.positionsForTypes(20, 21));
        assertTrue(cache.positionsForType(99).isEmpty());
    }

    @Test
    void snapshotLoadRestoresEntriesAndBuckets() {
        BlockPos a = new BlockPos(1, 2, 3);
        BlockPos b = new BlockPos(4, 5, 6);
        cache.put(a, 20);
        cache.put(a, 21);
        cache.put(b, 21);

        Map<BlockPos, Set<Integer>> snapshot = cache.snapshot();
        cache.clear();
        assertTrue(cache.isEmpty());

        cache.loadFromSnapshot(snapshot);

        assertEquals(2, cache.size());
        assertEquals(Set.of(20, 21), cache.get(a));
        assertEquals(Set.of(21), cache.get(b));
        assertEquals(Set.of(a), Set.copyOf(cache.positionsForType(20)));
        assertEquals(Set.of(a, b), Set.copyOf(cache.positionsForType(21)));
    }

    @Test
    void bulkBlockCapUsesCoalWhenMinecraftBlocksAvailable() {
        net.minecraft.world.level.block.Block coal;
        try {
            coal = net.minecraft.world.level.block.Blocks.COAL_BLOCK;
        } catch (Throwable t) {
            assumeTrue(false, "Blocks not bootstrapped");
            return;
        }
        assumeTrue(coal != null);

        int cap = CustomTaskBlockIndexLimits.BULK_BLOCK_CAP;
        for (int i = 0; i < cap; i++) {
            assertTrue(cache.put(new BlockPos(i, 2, 0), 20, coal));
        }
        assertFalse(cache.put(new BlockPos(cap, 2, 0), 20, coal));
        assertEquals(cap, cache.positionsForType(20).size());

        assertTrue(cache.put(new BlockPos(0, 3, 0), 21));
        assertEquals(cap + 1, cache.size());
    }

    @Test
    void snapshotLoadRestoresUniqueScanBlockWhenAvailable() {
        net.minecraft.world.level.block.Block stone;
        try {
            stone = net.minecraft.world.level.block.Blocks.STONE;
        } catch (Throwable t) {
            assumeTrue(false, "Blocks not bootstrapped");
            return;
        }
        assumeTrue(stone != null);

        String taskId = "cache_restore_" + UUID.randomUUID();
        int typeId = 77;
        TaskRegistry.register("habitrain_test", taskId, builder -> builder
                .blockTypeId(typeId)
                .scanBlocks(stone));

        BlockPos pos = new BlockPos(8, 8, 8);
        assertTrue(cache.put(pos, typeId));
        assertNull(cache.getBlockAt(pos));

        Map<BlockPos, Set<Integer>> snapshot = cache.snapshot();
        cache.clear();
        cache.loadFromSnapshot(snapshot);

        assertEquals(stone, cache.getBlockAt(pos));
        assertEquals(Set.of(pos), Set.copyOf(cache.positionsForType(typeId)));
    }
}
