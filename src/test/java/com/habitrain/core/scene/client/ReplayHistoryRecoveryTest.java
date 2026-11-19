package com.habitrain.core.scene.client;

import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.ReplayArchivePackets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayHistoryRecoveryTest {
    @Test void voiceFailureNeverInvalidatesReadyMap() {
        for (int status : List.of(ReplayArchivePackets.MISSING, ReplayArchivePackets.ERROR)) {
            var round = new ReplayHistoryClient.Round(UUID.randomUUID());
            round.phase = ReplayHistoryClient.Phase.READY;
            round.voiceRequested = 1;
            round.voiceBuffer = new byte[16];
            round.voiceHave = new BitSet();
            ReplayHistoryClient.applyReply(round, status);
            assertTrue(round.voiceUnavailable);
            assertEquals(ReplayHistoryClient.Phase.READY, round.phase);
            assertNull(round.voiceBuffer);
            assertNull(round.voiceHave);
        }
    }

    @Test void cacheQuotaAgeAndClearOnlyRemoveOwnedFiles(@TempDir Path root) throws Exception {
        long now = System.currentTimeMillis();
        var expired = Files.write(root.resolve(UUID.randomUUID() + ".voice"), new byte[4]);
        var old = Files.write(root.resolve(UUID.randomUUID() + ".bin"), new byte[4]);
        var recent = Files.write(root.resolve(UUID.randomUUID() + ".voice"), new byte[4]);
        var other = Files.write(root.resolve("notes.bin"), new byte[32]);
        Files.setLastModifiedTime(expired, FileTime.fromMillis(now - ReplayRoundCache.AGE - 5000));
        Files.setLastModifiedTime(old, FileTime.fromMillis(now - 5000));
        ReplayRoundCache.prune(root, 6, now);
        assertFalse(Files.exists(expired));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
        ReplayRoundCache.prune(root, 0, now);
        assertFalse(Files.exists(recent));
        assertTrue(Files.exists(other));
    }

    @Test void completeMapDoesNotVisitManifestOrSendAnotherBatch() {
        var manifest = new ReplayMapManifest("test", "test:level", new SceneBounds(0,0,0,16,16,16), "0".repeat(64),
                List.of(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(0,0,0), 1)));
        var comparisons = new java.util.concurrent.atomic.AtomicInteger();
        var data = new ClientReplayBoard.MapData(manifest, new TreeMap<>((left, right) -> {
            comparisons.incrementAndGet(); return Integer.compare(left, right);
        }));
        data.saved = true;
        data.parts.put(0, null);
        comparisons.set(0);
        data.step(0, ignored -> fail("complete map requested new sections"));
        assertEquals(0, comparisons.get());
        assertNull(data.assembled);
    }
}
