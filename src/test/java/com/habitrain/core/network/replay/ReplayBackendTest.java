package com.habitrain.core.network.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.match.replay.ReplayProjection;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static com.habitrain.core.network.replay.ReplayPackets.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayBackendTest {
    private static final UUID ROUND = new UUID(1, 2);
    private static final UUID PLAYER = new UUID(3, 4);
    private static final String HASH = "a".repeat(64);
    private static final SceneBounds BOUNDS = new SceneBounds(-19, 6, 2, 17, 14, 8);

    @Test void quantizationRejectsOverflowAndResolvesSignedSixteenths() {
        var p = Position.quantize(-2.20, 12.0625, 0.03125);
        assertNotNull(p);
        assertEquals(-2.1875, p.blockX());
        assertEquals(12.0625, p.blockY());
        assertEquals(0.0625, p.blockZ());
        assertNull(Position.quantize(2048, 0, 0));
        assertNotNull(Position.quantize(-2048, 0, 0));
        assertNull(Position.quantize(Double.NaN, 0, 0));
    }

    @Test void trackSimplificationRetainsCornersAndStateChanges() {
        List<Sample> path = new ArrayList<>();
        ReplayTracks.append(path, point(0, 0, 0, ALIVE));
        ReplayTracks.append(path, point(5, 16, 0, ALIVE));
        ReplayTracks.append(path, point(10, 32, 0, ALIVE));
        ReplayTracks.append(path, point(15, 32, -16, ALIVE));
        ReplayTracks.append(path, point(16, 32, -16, (byte) 0));
        ReplayTracks.append(path, point(25, 48, -16, (byte) (ALIVE | BREAK)));
        assertEquals(List.of(0, 10, 15, 16, 25), path.stream().map(Sample::tick).toList());
        byte[] wire = ReplayTracks.encode(List.of(path, List.of()));
        assertEquals(List.of(path, List.of()), ReplayTracks.decode(wire, 2, 25));
        assertThrows(RuntimeException.class, () -> ReplayTracks.decode(wire, 2, 24));
    }

    @Test void oversizedTrackBudgetStillRetainsEveryPlayersEndpoints() {
        List<List<Sample>> tracks = new ArrayList<>();
        for (int p = 0; p < MAX_PLAYERS; p++) {
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < 2000; i++) ReplayTracks.append(samples,
                    point(i * 5, (short) (i * 67), (short) (i * -221), i % 2 == 0 ? ALIVE : (byte) 0));
            tracks.add(samples);
        }
        var encoded = ReplayTracks.encodeWithStatus(tracks);
        assertTrue(encoded.thinned(), "Expected oversized track payload to trigger thinning");
        byte[] wire = encoded.bytes();
        assertTrue(wire.length <= ReplayTracks.MAX_BYTES);
        var decoded = ReplayTracks.decode(wire, MAX_PLAYERS, 10_000);
        for (var track : decoded) {
            assertEquals(0, track.getFirst().tick());
            assertEquals(9995, track.getLast().tick());
        }
    }

    @Test void encoderValidatesTimesAndMakesOversizedTracksDecodable() {
        var earlier = point(5, 0, 0, ALIVE);
        var later = point(10, 16, 0, ALIVE);
        var noHighlights = List.of(Collections.<Integer>emptyNavigableSet());
        assertThrows(IllegalArgumentException.class,
                () -> ReplayTracks.encodeWithStatus(List.of(List.of(later, earlier)), 10, noHighlights));
        assertThrows(IllegalArgumentException.class,
                () -> ReplayTracks.encodeWithStatus(List.of(List.of(earlier, later)), 9, noHighlights));
        var overlong = new ArrayList<Sample>();
        for (int tick = 0; tick <= ReplayTracks.MAX_TRACK; tick++) overlong.add(point(tick, tick, 0, ALIVE));
        var encoded = ReplayTracks.encodeWithStatus(List.of(overlong), ReplayTracks.MAX_TRACK, noHighlights);
        assertTrue(encoded.thinned());
        var track = ReplayTracks.decode(encoded.bytes(), 1, ReplayTracks.MAX_TRACK).getFirst();
        assertTrue(track.size() <= ReplayTracks.MAX_TRACK);
        assertEquals(overlong.getLast(), track.getLast());
    }

    @Test void byteBudgetProtectsExistingSamplesAroundHighlightedAction() {
        var samples = new ArrayList<Sample>();
        for (int tick = 0; tick < 6000; tick += 5) samples.add(point(tick, tick, tick & 31, ALIVE));
        var highlights = new TreeSet<>(List.of(3000));
        var encoded = ReplayTracks.encodeWithStatus(Collections.nCopies(12, samples), 6000,
                Collections.nCopies(12, highlights));
        var track = ReplayTracks.decode(encoded.bytes(), 12, 6000).getFirst();
        assertTrue(encoded.thinned());
        assertEquals(List.of(2990, 2995, 3000, 3005, 3010), track.stream().map(Sample::tick)
                .filter(tick -> tick >= 2990 && tick <= 3010).toList());
    }

    @Test void irreducibleTracksExceedingBudgetFailFastInsteadOfLoopingForever() {
        List<List<Sample>> irreducible = new ArrayList<>();
        for (int i = 0; i < 6_000; i++) {
            irreducible.add(List.of(
                    point(0, 100, -100, ALIVE),
                    point(1000, -100, 100, (byte) (ALIVE | BREAK))
            ));
        }
        assertThrows(IllegalArgumentException.class, () -> ReplayTracks.encodeWithStatus(irreducible));
    }

    @Test void manifestComputesOnlyMissingOrChangedSections() {
        var a = new ReplayMapManifest.Section(0, 0, 0);
        var b = new ReplayMapManifest.Section(1, 0, 0);
        var c = new ReplayMapManifest.Section(2, 0, 0);
        var manifest = new ReplayMapManifest("train", "minecraft:overworld", BOUNDS, HASH,
                List.of(new ReplayMapManifest.Entry(a, 1), new ReplayMapManifest.Entry(b, 2), new ReplayMapManifest.Entry(c, 3)));
        Map<ReplayMapManifest.Section, Integer> cached = Map.of(a, 1, b, 99);
        assertArrayEquals(new int[]{1, 2}, manifest.missing(cached::get).stream().toArray());
        assertTrue(manifest.missing(Map.of(a, 1, b, 2, c, 3)::get).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ReplayMapManifest("train", "d", BOUNDS, HASH,
                List.of(new ReplayMapManifest.Entry(a, 1), new ReplayMapManifest.Entry(a, 2))));
    }

    @Test void sceneV3PatchRetainsItsGeometryFingerprintAndExactLocalOffsets() throws Exception {
        short[] cells = new short[4096];
        cells[255] = 1;
        var world = new SceneAssetCodec.SectionData(-1, 0, 0, List.of("minecraft:air", "minecraft:stone"), cells, null, null);
        var rel = ReplaySectionGeometry.relative(world, BOUNDS);
        assertEquals(1, rel.relX);
        var another = new SceneAssetCodec.SectionData(0, 0, 0, rel.palette, cells, null, null);
        var asset = new SceneAssetCodec.AssetData(3955, "minecraft:overworld", BOUNDS, HASH, List.of(rel));
        var decoded = SceneAssetCodec.decodeGeometry(SceneAssetCodec.encode(asset), 1).sections.getFirst();
        assertEquals(ReplaySectionGeometry.crc(world), ReplaySectionGeometry.crc(decoded));
        assertArrayEquals(cells, decoded.blockIndices);
        cells[255] = 0;
        assertNotEquals(ReplaySectionGeometry.crc(world), ReplaySectionGeometry.crc(decoded));
        var two = new SceneAssetCodec.AssetData(3955, "minecraft:overworld", BOUNDS, HASH, List.of(rel, another));
        assertThrows(IOException.class, () -> SceneAssetCodec.decodeGeometry(SceneAssetCodec.encode(two), 1));
    }

    @Test void unfinishedMapPublishesCapturedSectionsWithoutShrinkingThePlayingArea() throws Exception {
        SharedConstants.tryDetectVersion();
        short[] cells = new short[4096];
        cells[(6 << 8) | (2 << 4)] = 1;
        var world = new SceneAssetCodec.SectionData(-1, 0, 0,
                List.of("minecraft:air", "minecraft:stone"), cells, null, null);
        var occupied = new SceneBounds(-16, 6, 2, -15, 7, 3);
        var snapshot = new ReplaySectionGeometry.Snapshot(world, ReplaySectionGeometry.crc(world), occupied);
        var partial = ReplayMapCapture.encode(new ReplayMapCapture.Raw("train", "minecraft:overworld",
                List.of(snapshot), BOUNDS), Map.of());
        assertEquals(BOUNDS, partial.manifest().sourceBounds());
        assertEquals(List.of(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(1, 0, 0), snapshot.crc())),
                partial.manifest().sections());
        var patch = SceneAssetCodec.decodeGeometry(partial.patches().getFirst(), 1);
        assertEquals(BOUNDS, patch.sourceBounds);
        assertEquals(1, patch.sections.getFirst().relX);
        assertArrayEquals(cells, patch.sections.getFirst().blockIndices);

        var complete = ReplayMapCapture.encode(new ReplayMapCapture.Raw("train", "minecraft:overworld",
                List.of(snapshot), null), Map.of(partial.manifest().hash(), partial));
        assertEquals(occupied, complete.manifest().sourceBounds());
        assertNotEquals(partial.manifest().hash(), complete.manifest().hash());
    }

    @Test void timelineRepeatsDeterministicallyAndHasActualSlowMotion() {
        var clock = new ReplayTimeline(1000, 1200, List.of(200, 600, 601, 1000));
        assertEquals(ReplayTimeline.Phase.BUILD, clock.at(999, .5f).phase());
        assertEquals(ReplayTimeline.Phase.PLAY, clock.at(1050, 0).phase());
        double prior = 0;
        boolean sawSlow = false;
        boolean sawReview = false, sawHonors = false;
        for (int t = 0; t < clock.cycleTicks(); t++) {
            var frame = clock.at(1000L + t, .25f);
            assertEquals(frame, clock.at(1000L + t + 5L * clock.cycleTicks(), .25f));
            assertTrue(frame.replayTick() >= prior);
            prior = frame.replayTick();
            var next = clock.at(1001L + t, .25f);
            if (frame.slowMotion() && next.slowMotion()) {
                sawSlow = true;
                assertEquals(.35, next.replayTick() - frame.replayTick(), 1e-8);
            }
            sawReview |= frame.phase() == ReplayTimeline.Phase.REVIEW;
            sawHonors |= frame.phase() == ReplayTimeline.Phase.HONORS;
        }
        assertTrue(sawSlow && sawReview && sawHonors);
        assertEquals(1200.0, prior);
    }

    @Test void summaryWirePreservesWinnerProjectionEventsAndTrackBlob() {
        var where = Position.quantize(16, 1, 3);
        var actor = new Participant(PLAYER, "Mike", "test:old_role", 0xffaa0000, "test:new_role", 0xff00aa00,
                MatchWinFaction.NEUTRAL, true, 2, 1, 4, 3, List.of(new Sample(10, where, (byte) 9, ALIVE)));
        var shot = new Node(10, Kind.GUNSHOT, PLAYER, null, where, where, "sre:revolver", HIT | GUN_DROP);
        var manifest = new ReplayMapManifest("train", "minecraft:overworld", BOUNDS, HASH,
                List.of(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(0, 0, 0), 123)));
        var replay = new LastMatchReplay(ROUND, 1000, 40, "Train", "sre:murder", "CUSTOM", MatchWinKind.CUSTOM,
                "test:winner", "{\"translate\":\"test.winner\"}", "", 0xff00aa00, BOUNDS, manifest,
                List.of(actor), List.of(shot), List.of(new Span(SpanKind.BLACKOUT, null, 5, 20)), PLAYER, 1, false);
        var layout = ReplayProjection.layout(replay);
        assertNotNull(layout);
        assertEquals((16 - 18) * layout.scale(), layout.project(where).x(), 1e-8);

        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            byte[] track = ReplayTracks.encode(List.of(actor.track()));
            LastMatchSummaryS2C.CODEC.encode(buf, new LastMatchSummaryS2C(replay, track));
            var read = LastMatchSummaryS2C.CODEC.decode(buf);
            assertEquals(replay.manifest(), read.replay().manifest());
            assertEquals(replay.events(), read.replay().events());
            assertEquals(replay.spans(), read.replay().spans());
            assertEquals(PLAYER, read.replay().mvp());
            assertTrue(read.replay().participants().getFirst().winner());
            assertEquals(List.of(actor.track()), ReplayTracks.decode(read.tracks(), 1, 40));
        } finally { buf.release(); }
    }

    @Test void droneTrackRoundTripPreservesIndependentLifecycleAndOwner() {
        var operator = new Participant(PLAYER, "Pilot", "", 0, "", 0,
                MatchWinFaction.PASSENGER, false, 0, 0, 0, 0, List.of(point(0, 0, 0, ALIVE)));
        var drone = new Drone(new UUID(3, 5), PLAYER, List.of(
                point(2, 16, 0, (byte) (ALIVE | Drone.PILOTED)),
                point(5, 32, 0, (byte) (ALIVE | Drone.CRASHED)),
                point(9, 32, 0, BREAK)));
        var clip = new LastMatchReplay(ROUND, 0, 20, "Train", "murder", "", MatchWinKind.INNOCENT,
                "", "", "", 0, BOUNDS, null, List.of(operator), List.of(), List.of(), null, 0, false, List.of(drone));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LastMatchSummaryS2C.CODEC.encode(buf, new LastMatchSummaryS2C(clip,
                    ReplayTracks.encode(List.of(operator.track(), drone.track()))));
            var got = LastMatchSummaryS2C.CODEC.decode(buf);
            assertEquals(List.of(new Drone(drone.id(), PLAYER, List.of())), got.replay().drones());
            var samples = ReplayTracks.decode(got.tracks(), 2, 20);
            assertEquals(operator.track(), samples.getFirst());
            var sampler = new com.habitrain.core.scene.replay.ReplayTrackSampler(samples.getLast());
            assertFalse(sampler.sample(1).visible());
            assertTrue(sampler.sample(3).has(Drone.PILOTED));
            assertTrue(sampler.sample(6).has(Drone.CRASHED));
            assertFalse(sampler.sample(9).visible());
        } finally { buf.release(); }
    }

    @Test void killSoundsRoundTripWithOptionalLegacySummary() {
        var file = net.minecraft.resources.ResourceLocation.parse("test:sounds/role_hint.ogg");
        var cue = new com.habitrain.core.api.match.replay.ReplayKillSounds.Cue(180, file, .35f, 1.25f);
        var window = new com.habitrain.core.api.match.replay.ReplayKillSounds.Window(0, List.of(cue));
        var events = List.of(new Node(100, Kind.KILL, null, PLAYER, null, Position.quantize(1, 2, 3), "", 0));
        var replay = new LastMatchReplay(ROUND, 0, 100, "Train", "murder", "", MatchWinKind.INNOCENT, "", "", "",
                0, BOUNDS, null, List.of(), events, List.of(), null, 1, false).withKillSounds(List.of(window));
        var packet = new LastMatchSummaryS2C(replay, new byte[0]);
        assertEquals(List.of(window), ReplayArchiveData.decode(ReplayArchiveData.encode(packet)).replay().killSounds());
        var old = ReplayArchiveData.decode(ReplayArchiveData.encode(packet.withoutKillSounds())).replay();
        assertTrue(old.killSounds().isEmpty());
        assertEquals(events, old.events());

        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var report = new ReplayPackets.KillSoundC2S(ROUND, List.of(cue));
            ReplayPackets.KillSoundC2S.CODEC.encode(buffer, report);
            assertEquals(report, ReplayPackets.KillSoundC2S.CODEC.decode(buffer));
            buffer.clear();
            buffer.writeUUID(ROUND).writeVarInt(49);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> ReplayPackets.KillSoundC2S.CODEC.decode(buffer));
            buffer.clear();
            var capture = new ReplayPackets.CaptureKillSoundS2C(ROUND, new net.minecraft.world.phys.Vec3(1, 2, 3), 9876L);
            ReplayPackets.CaptureKillSoundS2C.CODEC.encode(buffer, capture);
            assertEquals(capture, ReplayPackets.CaptureKillSoundS2C.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void aTableHasOneScheduledPassAndElapsedVideoDoesNotLoop() {
        var timeline = new ReplayTimeline(0, 800, List.of(240));
        var start = new com.habitrain.core.scene.block.SettlementSummaryBoard.Marker.Playback(
                ROUND, 1200, 1200 + timeline.cycleTicks());
        assertFalse(start.playing(1199));
        assertTrue(start.pendingOrPlaying(1199));
        assertEquals(ReplayTimeline.Phase.BUILD, timeline.atElapsed(0).phase());
        assertTrue(start.playing(1200));
        assertTrue(start.playing(start.endTick() - 1));
        assertFalse(start.pendingOrPlaying(start.endTick()));
        assertFalse(start.playing(start.endTick()));
        assertEquals(ReplayTimeline.Phase.HONORS, timeline.atElapsed(timeline.cycleTicks() + 1).phase());
    }

    @Test void invalidMissingRequestsAndSectionFragmentsAreRejected() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BitSet tooMany = new BitSet();
            tooMany.set(0, MAX_REQUESTED + 1);
            buf.writeBlockPos(BlockPos.ZERO); buf.writeUUID(ROUND); buf.writeByteArray(tooMany.toByteArray());
            assertThrows(DecoderException.class, () -> ReplayMissingSectionsC2S.CODEC.decode(buf));
        } finally { buf.release(); }
        assertThrows(IllegalArgumentException.class, () -> new ReplaySectionPatchS2C(ROUND, 0, 1, 2, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new ReplaySectionPatchS2C(ROUND, 0, 0, MAX_SECTION_BYTES + 1, new byte[FRAGMENT_BYTES]));
    }

    @Test void largeMapAndSectionRequestsSupportIndicesAboveTheOldLimit() {
        var bounds = new SceneBounds(0, 0, 0, 192, 128, 192);
        var entries = new ArrayList<ReplayMapManifest.Entry>();
        for (int sectionX = 0; sectionX < 12; sectionX++) {
            for (int sectionY = 0; sectionY < 8; sectionY++) {
                for (int sectionZ = 0; sectionZ < 12; sectionZ++) {
                    entries.add(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(sectionX, sectionY, sectionZ), 0));
                }
            }
        }
        var manifest = new ReplayMapManifest("12x12", "minecraft:overworld", bounds, HASH, entries);
        assertTrue(ReplaySectionGeometry.supported(bounds));
        assertEquals(1152, manifest.sections().size());
        var missing = new BitSet();
        missing.set(1088, 1152);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ReplayMissingSectionsC2S.CODEC.encode(buf, new ReplayMissingSectionsC2S(BlockPos.ZERO, ROUND, missing));
            assertEquals(missing, ReplayMissingSectionsC2S.CODEC.decode(buf).missing());
            var patch = new ReplaySectionPatchS2C(ROUND, 1151, 0, 1, new byte[]{42});
            ReplaySectionPatchS2C.CODEC.encode(buf, patch);
            assertEquals(1151, ReplaySectionPatchS2C.CODEC.decode(buf).index());
        } finally {
            buf.release();
        }
    }

    @Test void dedicatedReplayBytecodeHasNoClientLinks() throws Exception {
        var pkg = "com/habitrain/core/";
        for (String name : List.of("api/match/replay/LastMatchReplay", "api/match/replay/ReplayMapManifest",
                "api/match/replay/ReplayProjection", "api/match/replay/ReplayTimeline", "network/replay/ReplayPackets",
                "network/replay/ReplayTracks", "scene/block/SettlementSummaryBoard", "scene/replay/ReplayMapCapture",
                "scene/replay/ReplaySectionGeometry", "game/sre/replay/MatchReplayRecorder", "game/sre/replay/ReplayBoardService")) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(pkg + name + ".class")) {
                assertNotNull(stream, name);
                String constants = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of("net/minecraft/client/", "com/mojang/blaze3d/",
                        "net/fabricmc/fabric/api/client/", pkg + "client/", pkg + "scene/client/")) {
                    assertFalse(constants.contains(forbidden), name + " links to " + forbidden);
                }
            }
        }
    }

    private static Sample point(int tick, int x, int z, byte flags) {
        return new Sample(tick, new Position((short) x, (short) 0, (short) z), (byte) 0, flags);
    }
}
