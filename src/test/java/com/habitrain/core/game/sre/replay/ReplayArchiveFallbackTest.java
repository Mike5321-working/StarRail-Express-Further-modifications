package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.ReplayArchiveData;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.habitrain.core.network.replay.ReplayArchivePackets.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayArchiveFallbackTest {
    @TempDir Path tempDir;

    private static ReplayMapManifest manifest(String hash, int sectionCount) {
        List<ReplayMapManifest.Entry> entries = new ArrayList<>();
        for (int i = 0; i < sectionCount; i++) {
            entries.add(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(i, 0, 0), 1000 + i));
        }
        return new ReplayMapManifest("Train", "minecraft:overworld", new SceneBounds(0, 0, 0, 32, 16, 16), hash, entries);
    }

    private static ReplayPackets.LastMatchSummaryS2C summaryPacket(UUID roundId, UUID participantId, ReplayMapManifest manifest, int durationTicks) {
        var participant = new LastMatchReplay.Participant(participantId, "PlayerA", "civilian", 0xFFFFFF, "civilian", 0xFFFFFF,
                MatchWinFaction.PASSENGER, true, 120, 1, 50, 2, List.of());
        var replay = new LastMatchReplay(roundId, 100L, durationTicks, "Train", "murder", "PASSENGERS_WIN",
                MatchWinKind.NONE, "", "", "", 0, new SceneBounds(0, 0, 0, 32, 16, 16), manifest,
                List.of(participant), List.of(), List.of(), participantId, 1, false);
        return new ReplayPackets.LastMatchSummaryS2C(replay, new byte[0]);
    }

    private static List<CustomPacketPayload> drain(ReplayArchiveService.ResolvedStream resolved) {
        List<CustomPacketPayload> out = new ArrayList<>();
        Iterator<CustomPacketPayload> it = resolved.stream().packets();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    @Test void emptyDigestAlwaysReturnsFullSummaryFromArchiveAndOnlyMatchesNonEmptyDigest() throws Exception {
        UUID round = UUID.randomUUID();
        UUID participant = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();
        String mapHash = "a".repeat(64);
        var m = manifest(mapHash, 2);
        var packet = summaryPacket(round, participant, m, 240);
        byte[] encodedSummary = ReplayArchiveData.encode(packet);
        String digest = ReplayArchiveData.digest(encodedSummary);
        byte[] patch0 = {1, 2, 3, 4};
        byte[] patch1 = {5, 6, 7, 8, 9};

        var archive = new ReplayArchive(tempDir.resolve("archive"));
        assertTrue(archive.save(round, 100L, mapHash, encodedSummary, List.of(patch0, patch1), new byte[0]));

        AtomicInteger rememberedDuration = new AtomicInteger(-1);
        var full = ReplayArchiveService.resolveArchiveRequest(
                Request.lookup(round, ""), newcomer, false, archive, null, false, false,
                (id, dur) -> rememberedDuration.set(dur));
        assertFalse(full.registerWaiting());
        var fullPackets = drain(full);
        assertEquals(1, fullPackets.size());
        assertInstanceOf(Summary.class, fullPackets.getFirst());
        assertEquals(round, ((Summary) fullPackets.getFirst()).data().replay().roundId());
        assertEquals(240, rememberedDuration.get());

        var cached = ReplayArchiveService.resolveArchiveRequest(
                Request.lookup(round, digest), newcomer, false, archive, null, false, false, null);
        var cachedPackets = drain(cached);
        assertEquals(1, cachedPackets.size());
        assertInstanceOf(Reply.class, cachedPackets.getFirst());
        assertEquals(CACHED, ((Reply) cachedPackets.getFirst()).status());
    }

    @Test void fallsBackToLatestMemorySnapshotForSummarySectionsAndVoicePrivacy() throws Exception {
        UUID round = UUID.randomUUID();
        UUID participant = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        String mapHash = "b".repeat(64);
        var m = manifest(mapHash, 2);
        var packet = summaryPacket(round, participant, m, 360);
        String digest = ReplayArchiveData.digest(ReplayArchiveData.encode(packet));
        byte[] patch0 = {10, 20, 30};
        byte[] patch1 = {40, 50, 60, 70};
        var map = new ReplayMapCapture.Result(m, List.of(patch0, patch1), patch0.length + patch1.length);
        byte[] rawVoice = {1, 2, 3, 4, 5};
        var latest = new ReplayBoardService.LatestFallback(packet, digest, map, rawVoice);

        var emptyArchive = new ReplayArchive(tempDir.resolve("empty-archive"));

        // Non-participant newcomer with no local cache gets full Summary from in-memory fallback.
        var summaryRes = ReplayArchiveService.resolveArchiveRequest(
                Request.lookup(round, ""), newcomer, false, emptyArchive, latest, false, false, null);
        assertFalse(summaryRes.registerWaiting());
        var summaryPackets = drain(summaryRes);
        assertEquals(1, summaryPackets.size());
        assertInstanceOf(Summary.class, summaryPackets.getFirst());

        // Non-participant newcomer gets all requested map sections from in-memory fallback.
        BitSet need = new BitSet();
        need.set(0);
        need.set(1);
        var sectionsRes = ReplayArchiveService.resolveArchiveRequest(
                Request.sections(round, need), newcomer, false, emptyArchive, latest, false, false, null);
        var sectionPackets = drain(sectionsRes);
        assertEquals(2, sectionPackets.size());
        assertArrayEquals(patch0, ((Section) sectionPackets.get(0)).data().bytes());
        assertArrayEquals(patch1, ((Section) sectionPackets.get(1)).data().bytes());

        // Out-of-range section index returns ERROR.
        BitSet badIndex = new BitSet();
        badIndex.set(5);
        var badRes = ReplayArchiveService.resolveArchiveRequest(
                Request.sections(round, badIndex), newcomer, false, emptyArchive, latest, false, false, null);
        assertEquals(ERROR, ((Reply) drain(badRes).getFirst()).status());

        // Voice privacy: non-participant gets ERROR; admin gets raw voice bytes from memory fallback.
        var newcomerVoice = ReplayArchiveService.resolveArchiveRequest(
                Request.voice(round), newcomer, false, emptyArchive, latest, false, false, null);
        assertEquals(ERROR, ((Reply) drain(newcomerVoice).getFirst()).status());

        var adminVoice = ReplayArchiveService.resolveArchiveRequest(
                Request.voice(round), admin, true, emptyArchive, latest, false, false, null);
        var adminVoicePackets = drain(adminVoice);
        assertEquals(1, adminVoicePackets.size());
        assertInstanceOf(Voice.class, adminVoicePackets.getFirst());
        assertArrayEquals(rawVoice, ((Voice) adminVoicePackets.getFirst()).data().bytes());

        // Participant gets voice packet (empty voice when raw bytes are empty) from memory fallback.
        var emptyVoiceLatest = new ReplayBoardService.LatestFallback(packet, digest, map, new byte[0]);
        var participantVoice = ReplayArchiveService.resolveArchiveRequest(
                Request.voice(round), participant, false, emptyArchive, emptyVoiceLatest, false, false, null);
        var participantPackets = drain(participantVoice);
        assertEquals(1, participantPackets.size());
        assertInstanceOf(Voice.class, participantPackets.getFirst());
        assertEquals(0, ((Voice) participantPackets.getFirst()).data().total());
    }

    @Test void inFlightRoundOrOpeningArchiveReturnsTransferBusyAndRegistersWaiterInsteadOfMissing() throws Exception {
        UUID round = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();
        var emptyArchive = new ReplayArchive(tempDir.resolve("empty"));

        // Case 1: Round is still pending/encoding/archiving.
        var inFlightLookup = ReplayArchiveService.resolveArchiveRequest(
                Request.lookup(round, ""), newcomer, false, emptyArchive, null, false, true, null);
        assertTrue(inFlightLookup.registerWaiting());
        assertEquals(TRANSFER_BUSY, ((Reply) drain(inFlightLookup).getFirst()).status());

        // Case 2: Archive is still opening on server startup.
        var openingLookup = ReplayArchiveService.resolveArchiveRequest(
                Request.lookup(round, ""), newcomer, false, null, null, true, false, null);
        assertTrue(openingLookup.registerWaiting());
        assertEquals(TRANSFER_BUSY, ((Reply) drain(openingLookup).getFirst()).status());

        // Case 3: Latest exists in memory without map yet while round is still in flight.
        var noMapPacket = summaryPacket(round, UUID.randomUUID(), null, 180);
        var latestWithoutMap = new ReplayBoardService.LatestFallback(
                noMapPacket, ReplayArchiveData.digest(ReplayArchiveData.encode(noMapPacket)), null, new byte[0]);
        var waitingForMap = ReplayArchiveService.resolveArchiveRequest(
                Request.lookup(round, ""), newcomer, false, emptyArchive, latestWithoutMap, false, true, null);
        assertTrue(waitingForMap.registerWaiting());
        assertEquals(TRANSFER_BUSY, ((Reply) drain(waitingForMap).getFirst()).status());

        // Case 4: SHARE while in flight returns SHARE_ERROR without registering lookup waiter.
        var shareInFlight = ReplayArchiveService.resolveArchiveRequest(
                Request.share(round, BlockPos.ZERO), newcomer, true, emptyArchive, null, false, true, null);
        assertFalse(shareInFlight.registerWaiting());
        assertEquals(SHARE_ERROR, ((Reply) drain(shareInFlight).getFirst()).status());

        // Case 5: Once neither opening nor in flight and absent everywhere, returns MISSING.
        var trulyMissing = ReplayArchiveService.resolveArchiveRequest(
                Request.lookup(round, ""), newcomer, false, emptyArchive, null, false, false, null);
        assertFalse(trulyMissing.registerWaiting());
        assertEquals(MISSING, ((Reply) drain(trulyMissing).getFirst()).status());
    }

    @Test void saveWithQuotaFallbackDropsVisualsThenVoiceToPreserve3DReplay() throws Exception {
        var archive = new ReplayArchive(tempDir.resolve("tight-quota"));
        archive.setLimit(1L << 20); // 1 MiB minimum legal quota

        UUID participant = UUID.randomUUID();
        String mapHash = "c".repeat(64);
        var m = manifest(mapHash, 2);
        List<byte[]> patches = List.of(new byte[180 * 1024], new byte[180 * 1024]); // 360 KiB total, each <= 256 KiB

        // Stage 1: dropping visuals alone makes summary + map + voice fit -> voice is preserved.
        UUID roundKeepVoice = UUID.randomUUID();
        byte[] summary1 = ReplayArchiveData.encode(summaryPacket(roundKeepVoice, participant, m, 120));
        byte[] mediumVoice = new byte[300 * 1024];
        Map<UUID, byte[]> hugeVisuals = Map.of(participant, new byte[500 * 1024]);
        assertTrue(ReplayArchiveService.saveWithQuotaFallback(
                archive, roundKeepVoice, 40L, mapHash, summary1, patches, mediumVoice, hugeVisuals));
        assertNotNull(archive.summary(roundKeepVoice));
        assertEquals(mediumVoice.length, archive.voice(roundKeepVoice).length);

        // Stage 2: even without visuals, summary + map + hugeVoice exceeds 1 MiB -> drops voice to preserve 3D replay.
        UUID roundDropVoice = UUID.randomUUID();
        byte[] summary2 = ReplayArchiveData.encode(summaryPacket(roundDropVoice, participant, m, 120));
        byte[] hugeVoice = new byte[800 * 1024];
        assertTrue(ReplayArchiveService.saveWithQuotaFallback(
                archive, roundDropVoice, 50L, mapHash, summary2, patches, hugeVoice, hugeVisuals));
        assertNotNull(archive.summary(roundDropVoice));
        assertArrayEquals(new byte[0], archive.voice(roundDropVoice));
        BitSet sec0 = new BitSet();
        sec0.set(0);
        assertEquals(180 * 1024, archive.sections(roundDropVoice, sec0).get(0).length);
    }

    @Test void loadLatestRestoresNewestArchivedRoundAcrossRestart() throws Exception {
        Path root = tempDir.resolve("restart-archive");
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID participant = UUID.randomUUID();

        var olderMap = manifest("d".repeat(64), 1);
        var newerMap = manifest("e".repeat(64), 2);
        var olderSummary = summaryPacket(older, participant, olderMap, 100);
        var newerSummary = summaryPacket(newer, participant, newerMap, 420);
        byte[] p0 = {11, 22, 33};
        byte[] p1 = {44, 55, 66, 77};
        byte[] voice = {9, 8, 7, 6};

        var store = new ReplayArchive(root);
        assertTrue(store.save(older, 1000L, olderMap.hash(), ReplayArchiveData.encode(olderSummary),
                List.of(new byte[]{1}), new byte[]{2}));
        assertTrue(store.save(newer, 2000L, newerMap.hash(), ReplayArchiveData.encode(newerSummary),
                List.of(p0, p1), voice));

        var reopened = new ReplayArchive(root);
        var latest = reopened.loadLatest();
        assertNotNull(latest);
        assertEquals(newer, latest.id());
        assertEquals(420, latest.packet().replay().durationTicks());
        assertEquals(newerMap.hash(), latest.map().manifest().hash());
        assertEquals(2, latest.map().patches().size());
        assertArrayEquals(p0, latest.map().patches().get(0));
        assertArrayEquals(p1, latest.map().patches().get(1));
        assertArrayEquals(voice, latest.voice());
    }

    @Test void encodeArchivePacketProducesValidPacketWithManifest() {
        UUID round = UUID.randomUUID();
        UUID participant = UUID.randomUUID();
        var m = manifest("f".repeat(64), 1);
        var map = new ReplayMapCapture.Result(m, List.of(new byte[]{1, 2, 3}), 3);
        var summary = summaryPacket(round, participant, null, 200).replay();

        var encoded = ReplayBoardService.encodeArchivePacket(summary, new byte[0], map, false, null);
        assertNotNull(encoded);
        assertEquals(round, encoded.replay().roundId());
        assertNotNull(encoded.replay().manifest());
        assertEquals(m.hash(), encoded.replay().manifest().hash());
    }
}
