package com.habitrain.core.scene.client;

import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.ReplayArchivePackets;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.server.SceneBandwidthGovernor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayHistoryRecoveryTest {
    @Test void archiveCongestionKeepsTheViewerWaitingAtTheCurrentRetryInterval() {
        var round = new ReplayHistoryClient.Round(UUID.randomUUID());
        round.attempts = 12;
        round.requested = 5000;
        ReplayHistoryClient.applyReply(round, ReplayArchivePackets.TRANSFER_BUSY);
        assertEquals(ReplayHistoryClient.Phase.LOADING, round.phase);
        assertEquals(5000, round.requested);
        assertEquals(0, round.attempts);
    }

    @Test void terminalHistoryFailuresReleaseTheSharedPlaybackBarrier() {
        assertFalse(ReplayHistoryClient.Phase.LOADING.finishedLoading());
        assertFalse(ReplayHistoryClient.Phase.MAP.finishedLoading());
        assertTrue(ReplayHistoryClient.Phase.READY.finishedLoading());
        assertTrue(ReplayHistoryClient.Phase.MISSING.finishedLoading());
        assertTrue(ReplayHistoryClient.Phase.FAILED.finishedLoading());
    }

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
        var abandoned = Files.write(root.resolve("replay-123.part"), new byte[32]);
        var unknownPart = Files.write(root.resolve("notes.part"), new byte[32]);
        Files.setLastModifiedTime(expired, FileTime.fromMillis(now - ReplayRoundCache.AGE - 5000));
        Files.setLastModifiedTime(old, FileTime.fromMillis(now - 5000));
        ReplayRoundCache.prune(root, 6, now);
        assertFalse(Files.exists(expired));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
        assertFalse(Files.exists(abandoned));
        assertTrue(Files.exists(unknownPart));
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

    @Test void stalledMapExposesFailureAndRetriesWithBackoff() {
        var manifest = new ReplayMapManifest("test", "test:level", new SceneBounds(0,0,0,16,16,16), "0".repeat(64),
                List.of(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(0,0,0), 1)));
        var data = new ClientReplayBoard.MapData(manifest);
        data.diskLoading = false;
        data.markFailed(0);
        assertTrue(data.failed());
        data.step(1, ignored -> fail("failed map retried before backoff elapsed"));
        assertTrue(data.failed());
        data.step(5_000_000_000L, ignored -> true);
        assertFalse(data.failed());
    }

    @Test void fortyViewerQueueCanCrossRetryDeadlineWithoutDiscardingMapFragments() {
        var bandwidth = new SceneBandwidthGovernor(() -> 0L, 4L * 1024 * 1024);
        long lastReady = 0;
        for (int i = 0; i < 40; i++) {
            lastReady = bandwidth.reserve(new UUID(0L, i), ReplayPackets.MAX_SUMMARY, 512L * 1024);
        }
        assertTrue(lastReady > 8_000_000_000L);

        var manifest = new ReplayMapManifest("test", "test:level", new SceneBounds(0, 0, 0, 16, 16, 16),
                "0".repeat(64), List.of(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(0, 0, 0), 1)));
        var data = new ClientReplayBoard.MapData(manifest);
        data.diskLoading = false;
        data.step(0, requested -> true);
        int fragmentSize = ReplayPackets.FRAGMENT_BYTES;
        data.accept(new ReplayPackets.ReplaySectionPatchS2C(UUID.randomUUID(), 0, 0,
                fragmentSize + 1, new byte[fragmentSize]));
        Object partial = data.partial.get(0);
        assertNotNull(partial);
        data.step(data.retryAt, requested -> {
            assertEquals(BitSet.valueOf(new long[]{1L}), requested);
            return true;
        });
        assertSame(partial, data.partial.get(0));
    }

    @Test void cachedReplyWithMissingOrCorruptedLocalSummaryResetsForFullServerFetch() {
        var missingCache = new ReplayHistoryClient.Round(UUID.randomUUID());
        missingCache.requested = 5000;
        missingCache.attempts = 4;
        missingCache.cached = null;
        ReplayHistoryClient.applyReply(missingCache, ReplayArchivePackets.CACHED);
        assertEquals(ReplayHistoryClient.Phase.LOADING, missingCache.phase);
        assertEquals(0, missingCache.requested);
        assertEquals(0, missingCache.attempts);

        var corruptedCache = new ReplayHistoryClient.Round(UUID.randomUUID());
        corruptedCache.requested = 5000;
        corruptedCache.attempts = 4;
        corruptedCache.cached = new byte[]{1, 2, 3, 4};
        ReplayHistoryClient.applyReply(corruptedCache, ReplayArchivePackets.CACHED);
        assertEquals(ReplayHistoryClient.Phase.LOADING, corruptedCache.phase);
        assertNull(corruptedCache.cached);
        assertEquals(0, corruptedCache.requested);
        assertEquals(0, corruptedCache.attempts);
    }

    @Test void installingSummaryAfterMissingResetsVoiceUnavailableAndSkipsLocalCacheWhenForced() {
        UUID roundId = UUID.randomUUID();
        var manifest = new ReplayMapManifest("Train", "minecraft:overworld", new SceneBounds(0, 0, 0, 32, 16, 16),
                "0".repeat(64), List.of(
                        new ReplayMapManifest.Entry(new ReplayMapManifest.Section(0, 0, 0), 1),
                        new ReplayMapManifest.Entry(new ReplayMapManifest.Section(1, 0, 0), 2)));
        var replay = new LastMatchReplay(roundId, 0L, 120, "Train", "murder", "", MatchWinKind.NONE,
                "", "", "", 0, new SceneBounds(0, 0, 0, 32, 16, 16), manifest,
                List.of(), List.of(), List.of(), null, 0, false);
        var packet = new ReplayPackets.LastMatchSummaryS2C(replay, new byte[0]);

        var round = new ReplayHistoryClient.Round(roundId);
        round.skipLocalMapCache = true;
        ReplayHistoryClient.applyReply(round, ReplayArchivePackets.MISSING);
        assertEquals(ReplayHistoryClient.Phase.MISSING, round.phase);
        assertTrue(round.voiceUnavailable);

        ReplayHistoryClient.install(round, packet);
        assertEquals(ReplayHistoryClient.Phase.MAP, round.phase);
        assertFalse(round.voiceUnavailable);
        assertFalse(round.voiceReady);
        assertNotNull(round.map);
        assertFalse(round.map.diskLoading);

        var requested = new ArrayList<BitSet>();
        round.map.step(0L, bits -> {
            requested.add((BitSet) bits.clone());
            return true;
        });
        assertEquals(1, requested.size());
        assertEquals(BitSet.valueOf(new long[]{0b11L}), requested.getFirst());
    }
}
