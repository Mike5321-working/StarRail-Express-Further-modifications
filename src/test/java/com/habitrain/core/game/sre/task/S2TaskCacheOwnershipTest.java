package com.habitrain.core.game.sre.task;

import com.habitrain.core.client.cache.ClientTaskBlockCache;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class S2TaskCacheOwnershipTest {
    private final CustomTaskBlockCache cache = new CustomTaskBlockCache();

    @Test void newScanCannotInheritCoordinatesFromAnOldSnapshot() {
        var position = new BlockPos(17, 40, 23);
        cache.put(position, 20);
        var previous = cache.immutableSnapshot();
        assertTrue(new CustomTaskBlockCache().immutableSnapshot().isEmpty());
        cache.put(position, 21);
        assertEquals(java.util.Set.of(20), previous.get(position));
        assertThrows(UnsupportedOperationException.class, () -> previous.get(position).clear());
    }
    @Test void integratedClientResetCannotEraseTheServerIndex() {
        BlockPos position = new BlockPos(17, 40, 23);
        cache.clear(); ClientTaskBlockCache.clear();
        try {
            assertTrue(cache.put(position, 20));
            ClientTaskBlockCache.loadFromSnapshot(cache.snapshot());
            assertEquals(cache.snapshot(), ClientTaskBlockCache.snapshot());
            ClientTaskBlockCache.clear();
            assertTrue(ClientTaskBlockCache.isEmpty());
            assertTrue(cache.get(position).contains(20));
            assertTrue(ClientTaskBlockCache.put(position, 21));
            assertFalse(cache.get(position).contains(21));
            assertThrows(UnsupportedOperationException.class, () -> cache.get(position).clear());
        } finally {
            cache.clear(); ClientTaskBlockCache.clear();
        }
    }
}
