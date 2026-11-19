package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.client.SceneMeshSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayMeshPublicationTest {
    @Test void failedCompletionDoesNotPromotePartialMeshAndRecoveryReplacesIt() {
        var slot = new ReplayDioramaMesh.Slot();
        var partialKey = new ReplayDioramaMesh.Key("map", 1);
        var fullKey = new ReplayDioramaMesh.Key("map", 2);
        var partial = mesh();
        slot.installBuilt(partialKey, partial, null);
        var failed = new SceneMeshSet();
        slot.installBuilt(fullKey, failed, null);
        assertTrue(failed.isClosed());
        assertEquals(partialKey, slot.installedKey());
        assertSame(partial, slot.current());
        var full = mesh();
        slot.installBuilt(fullKey, full, null);
        assertEquals(fullKey, slot.installedKey());
        assertSame(full, slot.current());
        assertTrue(partial.isClosed());
        slot.reset();
    }

    private SceneMeshSet mesh() {
        var result = new SceneMeshSet();
        result.addBatch(new SceneMeshSet.Batch(new SceneMeshSet(), 0, 0, 0, 1, 1, 1));
        return result;
    }
}
