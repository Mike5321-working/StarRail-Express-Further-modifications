package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.ReplayArchiveData;
import com.habitrain.core.network.replay.ReplayPackets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayArchiveTest {
    @TempDir Path world;

    @Test void preservesRecordsAndSharedMapsAcrossRestart() throws Exception {
        Path root = world.resolve("archive");
        var store = new ReplayArchive(root);
        assertEquals(5L << 30, store.limit());
        store.setLimit(2L << 20);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        byte[] left = {1, 2}, right = {3, 4, 5};
        String map = "0".repeat(64);
        store.save(first, 1, map, new byte[]{9}, List.of(left, right));
        long one = store.used();
        store.save(second, 2, map, new byte[]{7, 8}, List.of(left, right));
        assertEquals(one + 80, store.used());
        var reopened = new ReplayArchive(root);
        assertEquals(store.used(), reopened.used());
        assertEquals(store.limit(), reopened.limit());
        assertArrayEquals(new byte[]{7, 8}, reopened.summary(second));
        BitSet need = new BitSet();
        need.set(1);
        var loaded = reopened.sections(first, need);
        assertEquals(1, loaded.size());
        assertArrayEquals(right, loaded.get(1));
    }

    @Test void preservesLargeMapSectionIndicesAcrossRestart() throws Exception {
        Path root = world.resolve("archive");
        var store = new ReplayArchive(root);
        UUID round = UUID.randomUUID();
        assertTrue(store.save(round, 1, "a".repeat(64), new byte[]{1},
                java.util.Collections.nCopies(1152, new byte[]{42}), new byte[0]));
        var requested = new BitSet();
        requested.set(1151);
        var reopened = new ReplayArchive(root);
        assertArrayEquals(new byte[]{42}, reopened.sections(round, requested).get(1151));
    }

    @Test void reducingQuotaEvictsOldestRoundsAndOnlyUnreferencedMaps() throws Exception {
        Path archive = world.resolve("archive");
        Path text = world.resolve("old-match.json");
        Files.writeString(text, "existing text record");
        var store = new ReplayArchive(archive);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        byte[] section = new byte[256 * 1024];
        var large = List.of(section, section, section);
        var small = List.of(new byte[160 * 1024]);
        String a = "a".repeat(64), b = "b".repeat(64);
        store.save(first, 1, a, new byte[80 * 1024], large);
        store.save(second, 2, b, new byte[24 * 1024], small);
        store.save(third, 3, a, new byte[40 * 1024], large);
        store.setLimit(1L << 20);
        assertNull(store.summary(first));
        assertNotNull(store.summary(second));
        assertNotNull(store.summary(third));
        assertTrue(Files.isRegularFile(archive.resolve("maps").resolve(a + ".map")));
        assertTrue(store.used() <= store.limit());
        UUID newest = UUID.randomUUID();
        store.save(newest, 4, "c".repeat(64), new byte[]{7}, List.of(section, section, section));
        assertNull(store.summary(second));
        assertNull(store.summary(third));
        assertNotNull(store.summary(newest));
        assertFalse(Files.exists(archive.resolve("maps").resolve(a + ".map")));
        assertEquals("existing text record", Files.readString(text));
    }

    @Test void anOversizedRoundDoesNotEvictExistingReplays() throws Exception {
        var store = new ReplayArchive(world.resolve("archive"));
        store.setLimit(1L << 20);
        UUID first = UUID.randomUUID(), tooLarge = UUID.randomUUID();
        byte[] section = new byte[256 * 1024];
        store.save(first, 1, "a".repeat(64), new byte[]{1}, List.of(section));
        store.save(tooLarge, 2, "b".repeat(64), new byte[]{2}, List.of(section, section, section, section));
        assertNotNull(store.summary(first));
        assertNull(store.summary(tooLarge));
    }

    @Test void unreadableArchiveQuarantinesCorruptedFilesAndUnblocksMapCleanup() throws Exception {
        Path root = world.resolve("archive");
        var store = new ReplayArchive(root);
        UUID round = UUID.randomUUID();
        String map = "e".repeat(64);
        store.save(round, 1, map, new byte[]{1}, List.of(new byte[]{2}), new byte[]{3});
        Path roundFile = root.resolve("rounds").resolve(round + ".round");
        byte[] unknownFormat = {5, 6, 7, 8, 9};
        Files.write(roundFile, unknownFormat);

        var reopened = new ReplayArchive(root);
        assertNull(reopened.summary(round));
        assertEquals(1, reopened.quarantinedCount());
        assertEquals(unknownFormat.length + 1L, reopened.quarantinedBytes());
        assertEquals(0, reopened.roundCount());
        assertFalse(reopened.save(round, 2, map, new byte[]{10}, List.of(new byte[]{2}), new byte[]{3}));
        assertFalse(Files.exists(roundFile));
        assertFalse(Files.exists(root.resolve("voice").resolve(round + ".voice")));
        assertArrayEquals(unknownFormat, Files.readAllBytes(reopened.quarantineDir().resolve(round + ".round")));
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(reopened.quarantineDir().resolve(round + ".voice")));
        // Defect #13 fix: quarantining the corrupted round unblocks orphan .map cleanup.
        assertFalse(Files.exists(root.resolve("maps").resolve(map + ".map")));
        assertEquals(0L, reopened.used());
    }

    @Test void corruptedOversizedRoundIsQuarantinedSoQuotaAndNewSavesStayUnblocked() throws Exception {
        Path root = world.resolve("archive");
        UUID readable = UUID.randomUUID();
        UUID unreadable = UUID.randomUUID();
        String map = "a".repeat(64);
        var sections = List.of(new byte[256 * 1024]);
        new ReplayArchive(root).save(readable, 1, map, new byte[]{1}, sections);
        Files.write(root.resolve("rounds").resolve(unreadable + ".round"), new byte[800 * 1024]);

        var reopened = new ReplayArchive(root);
        reopened.setLimit(1L << 20);
        assertEquals(1, reopened.quarantinedCount());
        assertEquals(800 * 1024L, reopened.quarantinedBytes());
        assertArrayEquals(new byte[]{1}, reopened.summary(readable));
        UUID fresh = UUID.randomUUID();
        assertTrue(reopened.save(fresh, 2, map, new byte[]{2}, sections, new byte[0]));
        assertArrayEquals(new byte[]{2}, reopened.summary(fresh));
        assertTrue(reopened.used() <= reopened.limit());
    }

    @Test void blockedQuotaCleanupKeepsOldRoundReadableAndNewSaveSuccessful() throws Exception {
        Path root = world.resolve("archive");
        var store = new ReplayArchive(root);
        store.setLimit(1L << 20);
        UUID old = UUID.randomUUID(), newest = UUID.randomUUID();
        byte[] section = new byte[256 * 1024];
        store.save(old, 1, "a".repeat(64), new byte[]{1}, List.of(section, section));
        Path blocker = Files.createDirectory(root.resolve("voice").resolve(old + ".voice"));
        Files.writeString(blocker.resolve("occupied"), "data");

        assertTrue(store.save(newest, 2, "b".repeat(64), new byte[]{2}, List.of(section, section), new byte[0]));
        assertArrayEquals(new byte[]{1}, store.summary(old));
        assertArrayEquals(new byte[]{2}, store.summary(newest));
        assertTrue(store.used() > store.limit());

        Files.delete(blocker.resolve("occupied"));
        Files.delete(blocker);
        store.setLimit(store.limit());
        assertNull(store.summary(old));
        assertArrayEquals(new byte[]{2}, store.summary(newest));
        assertTrue(store.used() <= store.limit());
    }

    @Test void aFailedRoundWriteDoesNotEvictExistingReplays() throws Exception {
        Path root = world.resolve("archive");
        var store = new ReplayArchive(root);
        store.setLimit(1L << 20);
        UUID first = UUID.randomUUID(), failed = UUID.randomUUID();
        byte[] section = new byte[256 * 1024];
        store.save(first, 1, "a".repeat(64), new byte[]{1}, List.of(section, section, section), new byte[]{2});
        long used = store.used();
        Path blocker = Files.createDirectory(root.resolve("rounds").resolve(failed + ".round.part"));
        Files.writeString(blocker.resolve("occupied"), "data");
        assertThrows(IOException.class, () ->
                store.save(failed, 2, "b".repeat(64), new byte[]{3}, List.of(section, section), new byte[]{4}));
        assertEquals(used, store.used());
        assertArrayEquals(new byte[]{1}, store.summary(first));
        assertArrayEquals(new byte[]{2}, store.voice(first));
        assertFalse(Files.exists(root.resolve("maps").resolve("b".repeat(64) + ".map")));
        assertFalse(Files.exists(root.resolve("voice").resolve(failed + ".voice")));
    }

    @Test void voiceRecordingDefaultsToOnAndCanBePersistentlyDisabled() throws Exception {
        Path root = world.resolve("archive");
        var store = new ReplayArchive(root);
        assertTrue(store.recordVoice());
        store.setRecordVoice(false);
        var reopened = new ReplayArchive(root);
        assertFalse(reopened.recordVoice());
        reopened.setRecordVoice(true);
        assertTrue(new ReplayArchive(root).recordVoice());
    }

    @Test void onlyParticipantsAndAdministratorsCanExportPlayerVoiceFromStoredRounds() throws Exception {
        UUID participant = UUID.randomUUID(), viewer = UUID.randomUUID(), round = UUID.randomUUID();
        var person = new LastMatchReplay.Participant(participant, "Participant", "", 0, "", 0,
                MatchWinFaction.PASSENGER, false, 2, 0, 0, 0, List.of());
        var replay = new LastMatchReplay(round, 0, 40, "Train", "murder", "", MatchWinKind.NONE,
                "", "", "", 0, new SceneBounds(0, 0, 0, 15, 15, 15), null,
                List.of(person), List.of(), List.of(), null, 0, false);
        var archive = new ReplayArchive(world.resolve("archive"));
        byte[] summary = ReplayArchiveData.encode(new ReplayPackets.LastMatchSummaryS2C(replay, new byte[0]));
        archive.save(round, 1, "a".repeat(64), summary, List.of(new byte[]{1}));
        var loaded = ReplayArchiveData.decode(archive.summary(round)).replay();
        assertTrue(ReplayArchiveService.canExportVoice(loaded, participant, false));
        assertTrue(ReplayArchiveService.canExportVoice(loaded, viewer, true));
        assertFalse(ReplayArchiveService.canExportVoice(loaded, viewer, false));
    }

    @Test void voiceDataCountsTowardQuotaPersistsAcrossRestartAndEvictsWithRound() throws Exception {
        Path archive = world.resolve("archive-voice");
        var store = new ReplayArchive(archive);
        store.setLimit(2L << 20); // 2 MiB
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String map = "d".repeat(64);
        byte[] patch = new byte[256 * 1024];
        byte[] summary = new byte[4 * 1024];
        byte[] voiceA = new byte[400 * 1024];
        byte[] voiceB = new byte[420 * 1024];
        for (int i = 0; i < voiceA.length; i++) voiceA[i] = (byte) (i * 31 + 7);
        for (int i = 0; i < voiceB.length; i++) voiceB[i] = (byte) (i * 17 + 3);

        store.save(first, 10, map, summary, List.of(patch, patch), voiceA);
        long afterFirst = store.used();
        assertTrue(Files.isRegularFile(archive.resolve("voice").resolve(first + ".voice")));
        assertArrayEquals(voiceA, store.voice(first));

        store.save(second, 20, map, summary, List.of(patch, patch), voiceB);
        assertEquals(afterFirst + 78L + summary.length + voiceB.length, store.used());

        // Orphan voice files on disk should be cleaned up automatically.
        UUID orphan = UUID.randomUUID();
        Files.write(archive.resolve("voice").resolve(orphan + ".voice"), new byte[]{1, 2, 3, 4});
        var reopened = new ReplayArchive(archive);
        assertFalse(Files.exists(archive.resolve("voice").resolve(orphan + ".voice")));
        assertArrayEquals(voiceA, reopened.voice(first));
        assertArrayEquals(voiceB, reopened.voice(second));
        assertEquals(store.used(), reopened.used());

        // Reducing quota should evict the oldest round along with its .voice file.
        reopened.setLimit(1L << 20); // 1 MiB (< ~1.35 MiB combined)
        assertTrue(reopened.used() <= reopened.limit());
        assertNull(reopened.summary(first));
        assertNull(reopened.voice(first));
        assertFalse(Files.exists(archive.resolve("voice").resolve(first + ".voice")));
        assertArrayEquals(voiceB, reopened.voice(second));
        assertTrue(Files.isRegularFile(archive.resolve("voice").resolve(second + ".voice")));
    }
}
