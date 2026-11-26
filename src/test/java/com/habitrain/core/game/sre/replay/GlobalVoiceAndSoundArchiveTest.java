package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.*;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.ReplayArchivePackets;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.network.replay.ReplayTracks;
import com.habitrain.core.scene.replay.ReplayCinemaDirector;
import com.habitrain.core.scene.replay.ReplayExportContentOptions;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVoiceAudibilityResolver;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.replay.ReplayVoiceData.ChannelMode;
import com.habitrain.core.scene.replay.ReplayVoiceData.RecordedVoicePolicy;
import com.habitrain.core.scene.replay.ReplayVoiceData.SpeakerStateSpan;
import com.habitrain.core.scene.replay.ReplayVoiceData.VoiceTimelineMeta;
import com.habitrain.core.scene.replay.ReplayVoiceData.VoiceUtterance;
import com.habitrain.core.scene.server.SceneBandwidthGovernor;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification suite for the server-side global voice ({@code HRVC v2}),
 * audibility solver, server-side game sound capture, v2 tracks/world-dynamics wire protocol,
 * stable {@code eventId} identity, archive quota/quarantine (Defect #13), and dedicated-server isolation.
 */
class GlobalVoiceAndSoundArchiveTest {
    @TempDir Path tempDir;

    @Test
    void archiveGroupsSmallSectionPacketsInOrderWithinBandwidth() {
        var lane = new ReplayArchiveService.TransferLane<Integer>(1);
        UUID player = UUID.randomUUID();
        long[] time = {0};
        var bandwidth = new SceneBandwidthGovernor(() -> time[0], 4_000_000);
        var sent = new ArrayList<Integer>();
        var input = List.of(7001, 7002, 7003, 7004, 7005);
        assertTrue(lane.installIfCurrent(player, lane.begin(player), input.iterator()));
        Runnable drain = () -> lane.drain(id -> true, id -> false, Integer::intValue, packet -> true,
                bandwidth, 1_000_000, (id, packet) -> sent.add(packet));

        drain.run();
        assertEquals(input.subList(0, 3), sent);
        drain.run();
        assertEquals(input.subList(0, 3), sent);
        time[0] = 25_000_000;
        drain.run();
        assertEquals(input, sent);
        assertFalse(lane.hasActive(player));
    }

    @Test
    void voiceDeliveryClipsPrivateAudioAtGroupBoundaryAndRejectsObservers() {
        UUID speaker = UUID.randomUUID();
        UUID listener = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        String role = "starrailexpress:civilian";
        var track = List.of(sampleAt(0, 1, 1, 1, 0, ALIVE));
        var replay = new LastMatchReplay(UUID.randomUUID(), 0, 200, "test", "murder", "NONE", MatchWinKind.NONE,
                "", "", "", 0, new SceneBounds(0, 0, 0, 20, 8, 20), null, List.of(
                new Participant(speaker, "Speaker", role, 0, role, 0, MatchWinFaction.PASSENGER,
                        false, 10, 0, 0, 0, track),
                new Participant(listener, "Listener", role, 0, role, 0, MatchWinFaction.PASSENGER,
                        false, 10, 0, 0, 0, track)), List.of(), List.of(), null, 0, false);
        var summary = new ReplayPackets.LastMatchSummaryS2C(replay, ReplayTracks.encodeWithStatus(List.of(track, track)).bytes());
        short[] pcm = new short[48_000];
        Arrays.fill(pcm, (short) 9_000);
        var voice = new ReplayVoiceData(List.of(
                new VoiceUtterance(speaker, 1_000, pcm.length, 48, ChannelMode.GROUP, group, ReplayVoiceData.encodeAdpcm(pcm)),
                new VoiceUtterance(speaker, 4_000, pcm.length, 48, ChannelMode.RADIO, null, ReplayVoiceData.encodeAdpcm(pcm))),
                new VoiceTimelineMeta(List.of(), List.of(
                        new SpeakerStateSpan(speaker, 0, 6_000, 0, group, role),
                        new SpeakerStateSpan(listener, 0, 1_499, 0, group, role),
                        new SpeakerStateSpan(listener, 1_500, 6_000, ReplayVoiceData.STATE_RADIO_CHANNEL, null, role)), 48, 16),
                List.of(new ReplayVoiceData.Clip(0, 20, 0, pcm.length, ReplayVoiceData.encodeAdpcm(pcm))), true);
        byte[] stored = voice.toBytes();
        assertEquals(0, ReplayVoiceDelivery.forRecipient(summary, stored, UUID.randomUUID(), false).length);
        assertArrayEquals(stored, ReplayVoiceDelivery.forRecipient(summary, stored, UUID.randomUUID(), true));
        var scoped = ReplayVoiceData.fromBytes(ReplayVoiceDelivery.forRecipient(summary, stored, listener, false));
        assertTrue(scoped.truncated());
        assertTrue(scoped.clips().isEmpty());
        assertEquals(1, scoped.utterances().size());
        assertEquals(1_000, scoped.utterances().getFirst().startMs());
        assertEquals(1_500, scoped.utterances().getFirst().endMs());

        var options = ReplayExportContentOptions.defaultsFor(ReplayExportContentOptions.ExportMode.PERSONAL, listener);
        var film = ReplayCinemaDirector.direct(replay, voice, options);
        var context = ReplayVoiceAudibilityResolver.Context.of(replay, voice);
        var slices = context.resolveVoiceSlices(listener, 1_000, 3_000);
        assertEquals(1, slices.size());
        assertEquals(1_500, slices.getFirst().endMs());
        var mix = ReplayCinemaDirector.buildAudioMixPlan(film, voice, film.frames(30), 30, ignored -> false);
        assertTrue(mix.voiceSegments().stream().anyMatch(segment -> speaker.equals(segment.speakerId()) && segment.sourceOffsetSec() == 0.0));
    }

    @Test
    void twentyMinuteRecordingRetainsTheFortyPlayerCapacityRegression() {
        UUID round = UUID.randomUUID();
        var bounds = new SceneBounds(0, 0, 0, 120, 20, 40);
        List<UUID> speakers = java.util.stream.IntStream.range(0, 40).mapToObj(index -> new UUID(2, index)).toList();
        short[] frame = sine48k(350, 12_000);
        MatchVoiceRecorder.beginTestRound(round, bounds);
        try {
            for (int burst = 0; burst < 20; burst++) {
                for (int offset = 0; offset < 4_000; offset += 20) {
                    for (UUID speaker : speakers) {
                        MatchVoiceRecorder.recordPcm48kAtMs(round, speaker, burst * 55_000L + offset,
                                48, ChannelMode.NORMAL_OPEN, null, frame);
                    }
                }
            }
            for (int offset = 0; offset < 1_000; offset += 20) {
                MatchVoiceRecorder.recordPcm48kAtMs(round, speakers.getFirst(), 1_180_000L + offset,
                        48, ChannelMode.NORMAL_OPEN, null, frame);
            }
            var voice = ReplayVoiceData.fromBytes(MatchVoiceRecorder.finishRound(round, List.of()));
            assertEquals(3_201, voice.utterances().stream().mapToDouble(VoiceUtterance::durationSeconds).sum(), 0.5);
            assertFalse(voice.truncated());
        } finally {
            MatchVoiceRecorder.reset();
        }
    }

    @Test
    void fortyConcurrentSpeakersVadSegmentationBreathPaddingAndHrvcV2RoundTrip() {
        UUID roundId = UUID.randomUUID();
        SceneBounds bounds = new SceneBounds(0, 0, 0, 120, 20, 40);
        MatchVoiceRecorder.beginTestRound(roundId, bounds);

        List<UUID> speakers = new ArrayList<>(40);
        for (int i = 0; i < 40; i++) {
            UUID id = new UUID(0x1000L + i, 0x2000L + i);
            speakers.add(id);
            MatchVoiceRecorder.recordPositionForTest(roundId, id, 0, 10 + (i % 10) * 3, 2, 10 + (i / 10) * 4, true);
        }

        short[] silence = new short[960]; // 20 ms @ 48 kHz
        short[] speech = sine48k(380.0, 14_000);
        UUID groupA = UUID.randomUUID();

        // Feed leading silence (100ms), active speech (400ms), trailing silence (500ms) for all 40 players concurrently
        for (int i = 0; i < 40; i++) {
            UUID id = speakers.get(i);
            ChannelMode mode = switch (i % 5) {
                case 0 -> ChannelMode.NORMAL_OPEN;
                case 1 -> ChannelMode.WHISPER;
                case 2 -> ChannelMode.KILLER_PRIVATE;
                case 3 -> ChannelMode.RADIO;
                default -> ChannelMode.GROUP;
            };
            UUID grp = mode == ChannelMode.GROUP ? groupA : null;
            float maxDist = mode == ChannelMode.WHISPER ? 12.0f : 48.0f;

            // Pure silence block far earlier must NOT produce an utterance
            for (int f = 0; f < 10; f++) {
                MatchVoiceRecorder.recordPcm48kAtMs(roundId, id, 200L + f * 20L, maxDist, mode, grp, silence);
            }
            // Pre-roll breath silence (100 ms) right before speech at 2000 ms
            for (int f = 0; f < 5; f++) {
                MatchVoiceRecorder.recordPcm48kAtMs(roundId, id, 1900L + f * 20L, maxDist, mode, grp, silence);
            }
            // 400 ms active speech (2000..2400 ms)
            for (int f = 0; f < 20; f++) {
                MatchVoiceRecorder.recordPcm48kAtMs(roundId, id, 2000L + f * 20L, maxDist, mode, grp, speech);
            }
            // 440 ms trailing silence (> 320 ms hangover)
            for (int f = 0; f < 22; f++) {
                MatchVoiceRecorder.recordPcm48kAtMs(roundId, id, 2400L + f * 20L, maxDist, mode, grp, silence);
            }
        }

        MatchVoiceRecorder.recordPolicyForTest(roundId,
                new RecordedVoicePolicy("habitrain_core:isolated_role", false, false, true, false, 18.0f));
        MatchVoiceRecorder.recordStateSpanForTest(roundId,
                new SpeakerStateSpan(speakers.get(0), 0, 5000, ReplayVoiceData.STATE_RADIO_CHANNEL, groupA, "starrailexpress:civilian"));

        ReplayVoiceData extracted = MatchVoiceRecorder.extractForTest(roundId, List.of());
        assertEquals(ReplayVoiceData.VERSION_V2, extracted.formatVersion());
        assertTrue(extracted.hasMultitrack());
        assertEquals(40, extracted.utterances().size(), "All 40 concurrent speakers should produce 1 VAD utterance each");

        for (VoiceUtterance u : extracted.utterances()) {
            // 60ms leading + 400ms speech + 60ms trailing = ~520ms (~12480 samples at 24kHz)
            assertTrue(u.startMs() <= 2000L && u.startMs() >= 1900L, "Expected pre-roll breath padding, startMs=" + u.startMs());
            assertTrue(u.sampleCount24k() >= (int) (0.44 * ReplayVoiceData.SAMPLE_RATE)
                    && u.sampleCount24k() <= (int) (0.65 * ReplayVoiceData.SAMPLE_RATE),
                    "Unexpected VAD trimmed length: " + u.sampleCount24k());
            assertTrue(u.rmsEnergy() > 0.10f);
        }

        byte[] raw = extracted.toBytes();
        assertTrue(raw.length > 0 && raw.length < 512 * 1024, "40 speakers x 0.5s IMA ADPCM should stay compact, got " + raw.length);
        ReplayVoiceData decoded = ReplayVoiceData.fromBytes(raw);
        assertEquals(ReplayVoiceData.VERSION_V2, decoded.formatVersion());
        assertEquals(40, decoded.utterances().size());
        assertEquals(1, decoded.timelineMeta().policies().size());
        assertEquals(1, decoded.timelineMeta().stateSpans().size());
        assertEquals("habitrain_core:isolated_role", decoded.timelineMeta().policies().getFirst().roleId());
        assertEquals(40, decoded.utterancesInWindow(2100L, 2200L).size());
        assertTrue(decoded.utterancesInWindow(500L, 800L).isEmpty(), "Silence-only region must have no utterances");

        // Verify HRVC v1 backward compatibility
        short[] pcm24 = ReplayVoiceData.downsample48kTo24k(speech);
        ReplayVoiceData v1 = new ReplayVoiceData(List.of(
                new ReplayVoiceData.Clip(0, 40, -200, pcm24.length, ReplayVoiceData.encodeAdpcm(pcm24))));
        byte[] v1Bytes = v1.toBytes();
        ReplayVoiceData v1Decoded = ReplayVoiceData.fromBytes(v1Bytes);
        assertEquals(ReplayVoiceData.VERSION, v1Decoded.formatVersion());
        assertFalse(v1Decoded.hasMultitrack());
        assertEquals(1, v1Decoded.clips().size());
        assertEquals(pcm24.length * 2, v1Decoded.clips().getFirst().decode48k().length);
    }

    @Test
    void audibilityResolverEnforcesSelfCenterSpatialPanDistanceDeathGraceChannelsMutesAndRolePolicyIsolation() {
        UUID listener = new UUID(1, 1);
        UUID rightSpeaker = new UUID(1, 2);
        UUID leftSpeaker = new UUID(1, 3);
        UUID farSpeaker = new UUID(1, 4);
        UUID dyingSpeaker = new UUID(1, 5);
        UUID slothMuted = new UUID(1, 6);
        UUID mimeMuted = new UUID(1, 7);
        UUID droneMuted = new UUID(1, 8);
        UUID radioPeer = new UUID(1, 9);
        UUID killerPeer = new UUID(1, 10);
        UUID isolatedOutsider = new UUID(1, 11);
        UUID groupPeer = new UUID(1, 12);
        UUID groupAlpha = new UUID(9, 1);
        UUID groupBeta = new UUID(9, 2);

        // Listener at (20, 2, 20) facing South (yaw=0 -> +Z forward, so right ear = -X, left ear = +X)
        List<Participant> participants = List.of(
                part(listener, "Listener", "starrailexpress:civilian", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 20, 2, 20, 0f, ALIVE), sampleAt(400, 20, 2, 20, 0f, ALIVE))),
                part(rightSpeaker, "Right", "starrailexpress:civilian", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 12, 2, 20, 0f, ALIVE), sampleAt(400, 12, 2, 20, 0f, ALIVE))),
                part(leftSpeaker, "Left", "starrailexpress:civilian", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 28, 2, 20, 0f, ALIVE), sampleAt(400, 28, 2, 20, 0f, ALIVE))),
                part(farSpeaker, "Far", "starrailexpress:civilian", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 90, 2, 20, 0f, ALIVE), sampleAt(400, 90, 2, 20, 0f, ALIVE))),
                // Dies at tick 100 (5000 ms)
                part(dyingSpeaker, "Dying", "starrailexpress:civilian", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 20, 2, 23, 0f, ALIVE), sampleAt(99, 20, 2, 23, 0f, ALIVE),
                                sampleAt(100, 20, 2, 23, 0f, (byte) 0))),
                part(slothMuted, "Sloth", "habitrain_core:sloth", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 21, 2, 20, 0f, ALIVE), sampleAt(400, 21, 2, 20, 0f, ALIVE))),
                part(mimeMuted, "Mime", "habitrain_core:mime", MatchWinFaction.KILLER,
                        track(sampleAt(0, 21, 2, 20, 0f, ALIVE), sampleAt(400, 21, 2, 20, 0f, ALIVE))),
                part(droneMuted, "Drone", "habitrain_core:drone_operator", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 21, 2, 20, 0f, ALIVE), sampleAt(400, 21, 2, 20, 0f, ALIVE))),
                part(radioPeer, "RadioPeer", "starrailexpress:civilian", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 95, 2, 20, 0f, ALIVE), sampleAt(400, 95, 2, 20, 0f, ALIVE))),
                part(killerPeer, "KillerPeer", "starrailexpress:killer", MatchWinFaction.KILLER,
                        track(sampleAt(0, 95, 2, 20, 0f, ALIVE), sampleAt(400, 95, 2, 20, 0f, ALIVE))),
                part(isolatedOutsider, "Isolated", "habitrain_core:secret_cult", MatchWinFaction.NEUTRAL,
                        track(sampleAt(0, 22, 2, 20, 0f, ALIVE), sampleAt(400, 22, 2, 20, 0f, ALIVE))),
                part(groupPeer, "GroupPeer", "starrailexpress:civilian", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 95, 2, 20, 0f, ALIVE), sampleAt(400, 95, 2, 20, 0f, ALIVE)))
        );

        Node deathNode = new Node(1, 100, Kind.KILL, killerPeer, dyingSpeaker,
                Position.quantize(20, 2, 23), Position.quantize(20, 2, 23), "starrailexpress:knife", 0);

        byte[] adpcm = ReplayVoiceData.encodeAdpcm(new short[24_000]); // 1.0s
        List<VoiceUtterance> utterances = List.of(
                new VoiceUtterance(listener, 4800, 24_000, 48f, ChannelMode.NORMAL_OPEN, null, adpcm, 0.5f),
                new VoiceUtterance(rightSpeaker, 4800, 24_000, 48f, ChannelMode.NORMAL_OPEN, null, adpcm, 0.5f),
                new VoiceUtterance(leftSpeaker, 4800, 24_000, 48f, ChannelMode.NORMAL_OPEN, null, adpcm, 0.5f),
                new VoiceUtterance(farSpeaker, 4800, 24_000, 48f, ChannelMode.NORMAL_OPEN, null, adpcm, 0.5f),
                new VoiceUtterance(dyingSpeaker, 4900, 24_000, 48f, ChannelMode.NORMAL_OPEN, null, adpcm, 0.5f),
                new VoiceUtterance(slothMuted, 4800, 24_000, 48f, ChannelMode.NORMAL_OPEN, null, adpcm, 0.5f),
                new VoiceUtterance(mimeMuted, 4800, 24_000, 48f, ChannelMode.NORMAL_OPEN, null, adpcm, 0.5f),
                new VoiceUtterance(droneMuted, 4800, 24_000, 48f, ChannelMode.NORMAL_OPEN, null, adpcm, 0.5f),
                new VoiceUtterance(radioPeer, 4800, 24_000, 48f, ChannelMode.RADIO, null, adpcm, 0.5f),
                new VoiceUtterance(killerPeer, 4800, 24_000, 48f, ChannelMode.KILLER_PRIVATE, null, adpcm, 0.5f),
                new VoiceUtterance(isolatedOutsider, 4800, 24_000, 48f, ChannelMode.NORMAL_OPEN, groupBeta, adpcm, 0.5f),
                new VoiceUtterance(groupPeer, 4800, 24_000, 48f, ChannelMode.GROUP, groupAlpha, adpcm, 0.5f)
        );

        VoiceTimelineMeta meta = new VoiceTimelineMeta(
                List.of(new RecordedVoicePolicy("habitrain_core:secret_cult", false, false, true, false, 48f)),
                List.of(
                        new SpeakerStateSpan(listener, 0, 10_000, ReplayVoiceData.STATE_RADIO_CHANNEL, groupAlpha, "starrailexpress:civilian"),
                        new SpeakerStateSpan(slothMuted, 0, 10_000, ReplayVoiceData.STATE_MUTED_SLOTH, null, "habitrain_core:sloth"),
                        new SpeakerStateSpan(mimeMuted, 0, 10_000, ReplayVoiceData.STATE_MUTED_MIME, null, "habitrain_core:mime"),
                        new SpeakerStateSpan(droneMuted, 0, 10_000, ReplayVoiceData.STATE_MUTED_DRONE, null, "habitrain_core:drone_operator"),
                        new SpeakerStateSpan(radioPeer, 0, 10_000, ReplayVoiceData.STATE_RADIO_CHANNEL, null, "starrailexpress:civilian"),
                        new SpeakerStateSpan(killerPeer, 0, 10_000, ReplayVoiceData.STATE_KILLER_CHANNEL, null, "starrailexpress:killer"),
                        new SpeakerStateSpan(isolatedOutsider, 0, 10_000, 0, groupBeta, "habitrain_core:secret_cult"),
                        new SpeakerStateSpan(groupPeer, 0, 10_000, 0, groupAlpha, "starrailexpress:civilian")
                ),
                48f, 16f
        );

        LastMatchReplay replay = new LastMatchReplay(UUID.randomUUID(), 0, 400, "Train", "murder", "",
                MatchWinKind.KILLER, "", "", "", 0, new SceneBounds(0, 0, 0, 120, 20, 40), null,
                participants, List.of(deathNode), List.of(), null, 1, false);
        ReplayVoiceData voice = ReplayVoiceData.ofV2(utterances, meta);
        var ctx = ReplayVoiceAudibilityResolver.Context.of(replay, voice);

        // At 5100ms (100ms after dyingSpeaker died -> still within 150ms death grace!)
        Map<UUID, ReplayVoiceAudibilityResolver.AudibleVoice> at5100 = new HashMap<>();
        for (var av : ctx.resolveVoices(listener, 5100L)) at5100.put(av.utterance().speakerId(), av);

        assertTrue(at5100.containsKey(listener) && at5100.get(listener).isSelf());
        assertEquals(1.0f, at5100.get(listener).gain(), 1e-5f);
        assertEquals(0.0f, at5100.get(listener).pan(), 1e-5f);

        assertTrue(at5100.containsKey(rightSpeaker));
        assertTrue(at5100.get(rightSpeaker).pan() > 0.5f, "Right-ear speaker should pan positive, got " + at5100.get(rightSpeaker).pan());
        assertTrue(at5100.containsKey(leftSpeaker));
        assertTrue(at5100.get(leftSpeaker).pan() < -0.5f, "Left-ear speaker should pan negative, got " + at5100.get(leftSpeaker).pan());

        assertTrue(at5100.containsKey(dyingSpeaker), "Victim speech 100ms after death must be kept within 150ms grace");
        assertTrue(at5100.containsKey(radioPeer), "Listener on RADIO channel must hear radio peer despite 75m distance");
        assertTrue(at5100.containsKey(groupPeer), "Listener in same groupAlpha must hear GROUP peer");

        assertFalse(at5100.containsKey(farSpeaker), "70m away (> 48m) must be excluded");
        assertFalse(at5100.containsKey(slothMuted), "Sloth sleeping mute must be excluded");
        assertFalse(at5100.containsKey(mimeMuted), "Mime mute must be excluded");
        assertFalse(at5100.containsKey(droneMuted), "Drone operator mute must be excluded");
        assertFalse(at5100.containsKey(killerPeer), "Civilian listener must NOT hear KILLER_PRIVATE");
        assertFalse(at5100.containsKey(isolatedOutsider), "RoleVoicePolicy(isolateGroup=true, hearWorld=false) must isolate outsider");

        // At 5250ms (250ms after dyingSpeaker died -> past 150ms grace -> must be cut off!)
        Map<UUID, ReplayVoiceAudibilityResolver.AudibleVoice> at5250 = new HashMap<>();
        for (var av : ctx.resolveVoices(listener, 5250L)) at5250.put(av.utterance().speakerId(), av);
        assertFalse(at5250.containsKey(dyingSpeaker), "Dead spectator speech after 150ms grace must be excluded");

        // Global sandbox (listener == null) excludes private channels (RADIO/KILLER_PRIVATE/GROUP) by default
        Set<UUID> globalSpeakers = new HashSet<>();
        for (var av : ctx.resolveVoices(null, 5100L)) globalSpeakers.add(av.utterance().speakerId());
        assertTrue(globalSpeakers.contains(listener));
        assertTrue(globalSpeakers.contains(rightSpeaker));
        assertFalse(globalSpeakers.contains(radioPeer));
        assertFalse(globalSpeakers.contains(killerPeer));
        assertFalse(globalSpeakers.contains(groupPeer));
    }

    @Test
    void globalServerGameSoundRecorderCompressionDeduplicationAndAudibilityScoping() {
        UUID roundId = UUID.randomUUID();
        UUID listener = new UUID(2, 1);
        UUID killer = new UUID(2, 2);
        SceneBounds bounds = new SceneBounds(100, 60, 200, 180, 80, 240);

        GlobalMatchSoundRecorder.beginTest(roundId, bounds);
        // Same-tick same-block duplicate must deduplicate cleanly
        GlobalMatchSoundRecorder.recordForTest(roundId, 1000, "starrailexpress:item.revolver.shoot",
                ReplayGameSoundData.CAT_PLAYERS, 120.5, 64.0, 210.5, 2.0f, 1.0f, killer,
                ReplayGameSoundData.AudibilityScope.WORLD_3D);
        GlobalMatchSoundRecorder.recordForTest(roundId, 1000, "starrailexpress:item.revolver.shoot",
                ReplayGameSoundData.CAT_PLAYERS, 120.7, 64.1, 210.3, 2.0f, 1.0f, killer,
                ReplayGameSoundData.AudibilityScope.WORLD_3D);
        // Verify recentSoundNear returns non-footstep sound near (20.5, 4.0, 10.5) relative to origin at tick 20
        String near = GlobalMatchSoundRecorder.recentSoundNear(roundId, 20, Position.quantize(20.5, 4.0, 10.5));
        assertEquals("starrailexpress:item.revolver.shoot", near);
        // Private actor-only UI cue
        GlobalMatchSoundRecorder.recordForTest(roundId, 1200, "habitrain_core:role_hint",
                ReplayGameSoundData.CAT_PLAYERS, 120.0, 64.0, 210.0, 0.8f, 1.25f, killer,
                ReplayGameSoundData.AudibilityScope.ACTOR_ONLY);
        // Team killer private cue
        GlobalMatchSoundRecorder.recordForTest(roundId, 1400, "noellesroles:killer_whisper",
                ReplayGameSoundData.CAT_PLAYERS, 120.0, 64.0, 210.0, 1.0f, 1.0f, killer,
                ReplayGameSoundData.AudibilityScope.TEAM_KILLER);
        // Out-of-bounds ambient sound must be dropped, while always-global train horn is kept as GLOBAL
        GlobalMatchSoundRecorder.recordForTest(roundId, 1500, "minecraft:block.stone.step",
                ReplayGameSoundData.CAT_BLOCKS, 999.0, 64.0, 999.0, 1.0f, 1.0f, null,
                ReplayGameSoundData.AudibilityScope.WORLD_3D);
        GlobalMatchSoundRecorder.recordForTest(roundId, 1600, "starrailexpress:ambient.train.horn",
                ReplayGameSoundData.CAT_AMBIENT, 999.0, 64.0, 999.0, 1.5f, 1.0f, null,
                ReplayGameSoundData.AudibilityScope.WORLD_3D);

        ReplayGameSoundData data = GlobalMatchSoundRecorder.finish(roundId);
        assertEquals(4, data.size(), "Duplicate gunshot & out-of-bounds footstep must be dropped");

        byte[] gz = data.toCompressedBytes();
        ReplayGameSoundData decoded = ReplayGameSoundData.fromCompressedBytes(gz);
        assertEquals(data, decoded);
        assertEquals(2, decoded.cuesInWindow(1000, 1200).size());

        // Audibility resolver check on game sounds
        List<Participant> people = List.of(
                part(listener, "Passenger", "starrailexpress:civilian", MatchWinFaction.PASSENGER,
                        track(sampleAt(0, 18, 4, 10, 0f, ALIVE), sampleAt(100, 18, 4, 10, 0f, ALIVE))),
                part(killer, "Killer", "starrailexpress:killer", MatchWinFaction.KILLER,
                        track(sampleAt(0, 20, 4, 10, 0f, ALIVE), sampleAt(100, 20, 4, 10, 0f, ALIVE)))
        );
        LastMatchReplay replay = new LastMatchReplay(roundId, 0, 100, "Train", "murder", "",
                MatchWinKind.KILLER, "", "", "", 0, bounds, null, people, List.of(), List.of(), null, 0, false)
                .withGameSounds(decoded);
        var ctx = ReplayVoiceAudibilityResolver.Context.of(replay, null);

        var passengerSounds = ctx.resolveGameSoundsInWindow(listener, 0, 2000).stream()
                .map(ReplayVoiceAudibilityResolver.AudibleSound::soundId).toList();
        assertEquals(List.of("starrailexpress:item.revolver.shoot", "starrailexpress:ambient.train.horn"), passengerSounds);

        var killerSounds = ctx.resolveGameSoundsInWindow(killer, 0, 2000).stream()
                .map(ReplayVoiceAudibilityResolver.AudibleSound::soundId).toList();
        assertEquals(4, killerSounds.size(), "Killer should hear WORLD_3D + ACTOR_ONLY + TEAM_KILLER + GLOBAL");

        var globalSounds = ctx.resolveGameSoundsInWindow(null, 0, 2000).stream()
                .map(ReplayVoiceAudibilityResolver.AudibleSound::soundId).toList();
        assertEquals(List.of("starrailexpress:item.revolver.shoot", "starrailexpress:ambient.train.horn"), globalSounds);
    }

    @Test
    void v2TracksWorldDynamicsStableEventIdAndLegacyBackwardCompatWireRoundTrip() {
        UUID roundId = UUID.randomUUID();
        UUID a = new UUID(3, 1);
        UUID b = new UUID(3, 2);
        UUID entId = new UUID(3, 9);
        SceneBounds bounds = new SceneBounds(0, 0, 0, 64, 16, 32);

        List<Sample> richTrack = new ArrayList<>();
        ReplayTracks.appendV2(richTrack, new Sample(0, Position.quantize(2, 1, 2), Sample.quantizeYaw(90f), ALIVE,
                Sample.quantizePitch(-15f), Sample.quantizeYaw(95f),
                ACT_ON_GROUND | ACT_SPRINT | ACT_SWING_MAIN, STATUS_SHIELDED | STATUS_DISGUISED,
                "starrailexpress:knife", "habitrain_core:drone_terminal", b));
        ReplayTracks.appendV2(richTrack, new Sample(10, Position.quantize(6, 1, 2), Sample.quantizeYaw(90f), ALIVE,
                Sample.quantizePitch(10f), Sample.quantizeYaw(85f),
                ACT_ON_GROUND | ACT_SNEAK, STATUS_POISONED,
                "tacz:modern_kinetic_gun#tacz:glock_17", "", null));

        byte[] v2Wire = ReplayTracks.encodeV2(List.of(richTrack));
        assertTrue(ReplayTracks.isV2(v2Wire));
        List<List<Sample>> decodedTracks = ReplayTracks.decode(v2Wire, 1, 100);
        assertEquals(1, decodedTracks.size());
        assertEquals(2, decodedTracks.getFirst().size());
        Sample s0 = decodedTracks.getFirst().getFirst();
        assertEquals("starrailexpress:knife", s0.mainHandItemId());
        assertEquals("habitrain_core:drone_terminal", s0.offHandItemId());
        assertEquals(b, s0.disguiseTargetUuid());
        assertTrue(s0.hasAction(ACT_SWING_MAIN));
        assertTrue(s0.hasStatus(STATUS_SHIELDED | STATUS_DISGUISED));

        // Impulse actions (SWING_MAIN) decay after 4 ticks in ReplayTrackSampler
        ReplayTrackSampler sampler = new ReplayTrackSampler(decodedTracks.getFirst());
        assertTrue(sampler.sample(2.0).hasAction(ACT_SWING_MAIN));
        assertFalse(sampler.sample(6.0).hasAction(ACT_SWING_MAIN));
        assertEquals("starrailexpress:knife", sampler.sample(2.0).mainHandItemId());

        // Stable eventId (Defect #5): backfire preserves eventId, eventById/indexOfEventId survive reordering/trimming
        Node shot = new Node(42, 20, Kind.GUNSHOT, a, b, Position.quantize(4, 2, 2), Position.quantize(12, 2, 2),
                "starrailexpress:revolver", HIT);
        Node backfired = MatchReplayRecorder.backfireShot(shot, Position.quantize(4, 1, 2));
        assertEquals(42, backfired.eventId(), "In-place backfire replacement must preserve stable eventId");

        Node kill = new Node(99, 50, Kind.KILL, a, b, Position.quantize(8, 1, 2), Position.quantize(9, 1, 2),
                "starrailexpress:knife", 0);
        ReplayBlockDelta doorOpen = new ReplayBlockDelta(15, 5, 1, 3,
                "starrailexpress:small_door[facing=north,half=lower,open=true]", ReplayBlockDelta.REASON_DOOR_TOGGLE);
        ReplayEntityTrack grenade = new ReplayEntityTrack(entId, ReplayEntityTrack.EntityKind.GRENADE,
                "noellesroles:flash_grenade", a, null, 4.5f, List.of(
                new ReplayEntityTrack.EntitySample(18, Position.quantize(5, 2, 2), (byte) 0, (byte) 0, ReplayEntityTrack.FLAG_ACTIVE),
                new ReplayEntityTrack.EntitySample(30, Position.quantize(11, 1, 2), (byte) 64, (byte) 10, ReplayEntityTrack.FLAG_ACTIVE),
                new ReplayEntityTrack.EntitySample(31, Position.quantize(11, 1, 2), (byte) 64, (byte) 10, ReplayEntityTrack.FLAG_BREAK)));
        ReplaySkillEvent unknownFallback = new ReplaySkillEvent(101, 25, "thirdparty_mod:chrono_blink",
                "时空闪烁", ReplaySkillEvent.CAT_ROLE_SKILL, a, b, "thirdparty_mod:chronomancer", 0xFF8844FF,
                Position.quantize(6, 1, 2), Position.quantize(14, 1, 2), 40, "thirdparty_mod:blink.cast", true);
        assertFalse(MatchReplayRecorder.isKnownAdapterSkillOrItem("thirdparty_mod:chrono_blink"));
        assertTrue(MatchReplayRecorder.isKnownAdapterSkillOrItem("starrailexpress:revolver"));

        ReplayGameSoundData sounds = new ReplayGameSoundData(
                List.of("thirdparty_mod:blink.cast"),
                List.of(new ReplayGameSoundData.Cue(1250, 0, ReplayGameSoundData.CAT_PLAYERS,
                        (short) 96, (short) 16, (short) 32,
                        ReplayGameSoundData.Cue.packVolume(1.0f), ReplayGameSoundData.Cue.packPitch(1.0f),
                        a, ReplayGameSoundData.AudibilityScope.WORLD_3D)));

        LastMatchReplay replay = new LastMatchReplay(roundId, 1000L, 100, "Express", "murder", "KILLERS",
                MatchWinKind.KILLER, "", "", "", 0, bounds, null,
                List.of(part(a, "Alpha", "starrailexpress:killer", MatchWinFaction.KILLER, List.of())),
                List.of(backfired, kill), List.of(), a, 1, false)
                .withWorldDynamics(List.of(doorOpen), List.of(grenade), List.of(unknownFallback), sounds);

        assertEquals(kill, replay.eventById(99));
        assertEquals(1, replay.indexOfEventId(99));
        assertEquals(backfired, replay.eventById(42));
        assertNull(replay.eventById(777));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ReplayPackets.LastMatchSummaryS2C.CODEC.encode(buf, new ReplayPackets.LastMatchSummaryS2C(replay, v2Wire));
            ReplayPackets.LastMatchSummaryS2C wireRead = ReplayPackets.LastMatchSummaryS2C.CODEC.decode(buf);
            LastMatchReplay r2 = wireRead.replay();
            assertEquals(42, r2.events().get(0).eventId());
            assertEquals(99, r2.events().get(1).eventId());
            assertEquals(List.of(doorOpen), r2.blockDeltas());
            assertEquals(List.of(grenade), r2.entityTracks());
            assertEquals(List.of(unknownFallback), r2.skillEvents());
            assertEquals(sounds, r2.gameSounds());
            assertNotNull(r2.entityTracks().getFirst().sampleAt(24.0));
            assertNull(r2.entityTracks().getFirst().sampleAt(35.0));
        } finally {
            buf.release();
        }
    }

    @Test
    void archiveSupportsLargeV2VoiceQuotaFiveGibDefaultAndQuarantineStatsRoundTrip() throws Exception {
        Path root = tempDir.resolve("archive-5gib");
        ReplayArchive store = new ReplayArchive(root);
        assertEquals(5L << 30, store.limit(), "Default quota must remain 5 GiB");

        // Verify > 4 MiB (e.g. 6 MiB within 32 MiB cap) voice blob saves and reloads cleanly
        UUID bigRound = UUID.randomUUID();
        String mapHash = "f".repeat(64);
        byte[] voice6MiB = new byte[6 * 1024 * 1024];
        for (int i = 0; i < voice6MiB.length; i += 4096) voice6MiB[i] = (byte) ((i / 4096) & 0x7F);
        assertTrue(store.save(bigRound, 100L, mapHash, new byte[]{1, 2, 3}, List.of(new byte[]{4, 5}), voice6MiB));
        assertEquals(1, store.roundCount());
        assertEquals(0, store.quarantinedCount());
        assertArrayEquals(voice6MiB, store.voice(bigRound));

        // Inject corrupted .round + paired .voice on disk and reopen -> quarantined without hurting bigRound
        UUID badRound = UUID.randomUUID();
        byte[] badBytes = new byte[]{9, 8, 7, 6, 5, 4};
        Files.write(root.resolve("rounds").resolve(badRound + ".round"), badBytes);
        Files.write(root.resolve("voice").resolve(badRound + ".voice"), new byte[]{1, 2});

        ReplayArchive reopened = new ReplayArchive(root);
        assertEquals(1, reopened.roundCount());
        assertEquals(1, reopened.quarantinedCount());
        assertEquals(badBytes.length + 2L, reopened.quarantinedBytes());
        assertNotNull(reopened.summary(bigRound));
        assertArrayEquals(voice6MiB, reopened.voice(bigRound));

        // Verify Settings packet carries roundCount / quarantinedCount / quarantinedBytes
        ReplayArchivePackets.Settings settings = new ReplayArchivePackets.Settings(
                reopened.used(), reopened.limit(), root.toString(), true, true,
                reopened.roundCount(), reopened.quarantinedCount(), reopened.quarantinedBytes());
        assertEquals(1, settings.roundCount());
        assertEquals(1, settings.quarantinedCount());
        assertEquals(8L, settings.quarantinedBytes());
    }

    @Test
    void archiveServiceTransferLaneCancelsStaleStreamsOnRoundSwitchAndSeparatesThrottleBuckets() {
        UUID playerA = new UUID(8, 1);
        UUID playerB = new UUID(8, 2);

        // Defect #14: switching to a new round supersedes stale async fetch and partially drained streams
        ReplayArchiveService.TransferLane<String> summaryLane = new ReplayArchiveService.TransferLane<>(1);
        ReplayArchiveService.TransferLane<String> voiceLane = new ReplayArchiveService.TransferLane<>(2);

        long oldEpoch = summaryLane.begin(playerA);
        assertTrue(oldEpoch > 0);
        // Capacity=1 rejects a second distinct player while playerA is active
        assertEquals(-1L, summaryLane.begin(playerB));
        // PlayerA starts draining old round's packets
        assertTrue(summaryLane.installIfCurrent(playerA, oldEpoch, List.of("roundA-p1", "roundA-p2", "roundA-p3").iterator()));
        assertEquals("roundA-p1", summaryLane.poll(playerA));
        assertTrue(summaryLane.hasReady(playerA));

        // PlayerA switches to roundB mid-stream -> must supersede instead of silently dropping!
        long newEpoch = summaryLane.begin(playerA);
        assertTrue(newEpoch > oldEpoch);
        assertFalse(summaryLane.isCurrent(playerA, oldEpoch));
        assertTrue(summaryLane.isCurrent(playerA, newEpoch));
        assertFalse(summaryLane.hasReady(playerA), "New in-flight transfer must clear old queued stream");
        // Late async result from oldEpoch must be rejected
        assertFalse(summaryLane.installIfCurrent(playerA, oldEpoch, List.of("stale").iterator()));
        // Late rejection callback from oldEpoch must NOT cancel newEpoch
        assertFalse(summaryLane.cancelIfCurrent(playerA, oldEpoch));
        assertTrue(summaryLane.isCurrent(playerA, newEpoch));

        // Voice lane operates independently from summary lane (never cancels each other)
        long voiceEpoch = voiceLane.begin(playerA);
        assertTrue(voiceLane.installIfCurrent(playerA, voiceEpoch, List.of("voice-chunk-0", "voice-chunk-1").iterator()));
        assertTrue(summaryLane.installIfCurrent(playerA, newEpoch, List.of("roundB-p1").iterator()));
        assertEquals("roundB-p1", summaryLane.poll(playerA));
        assertFalse(summaryLane.hasActive(playerA));
        assertEquals("voice-chunk-0", voiceLane.poll(playerA));
        assertEquals("voice-chunk-1", voiceLane.poll(playerA));
        assertFalse(voiceLane.hasActive(playerA));

        // Separate throttle buckets: immediate SECTIONS & VOICE after LOOKUP must never be dropped
        ReplayArchiveService.RequestThrottle throttle = new ReplayArchiveService.RequestThrottle();
        long t0 = 1_000_000_000L;
        assertTrue(throttle.allow(ReplayArchivePackets.LOOKUP, playerA, t0));
        assertFalse(throttle.allow(ReplayArchivePackets.LOOKUP, playerA, t0 + 10_000_000L));
        assertTrue(throttle.allow(ReplayArchivePackets.SECTIONS, playerA, t0 + 5_000_000L),
                "SECTIONS immediately following LOOKUP must not share LOOKUP's throttle bucket");
        assertTrue(throttle.allow(ReplayArchivePackets.VOICE, playerA, t0 + 5_000_000L),
                "VOICE immediately following LOOKUP must not share LOOKUP's throttle bucket");
        assertTrue(throttle.allow(ReplayArchivePackets.LOOKUP, playerA, t0 + 60_000_000L));
    }

    @Test
    void newCommonAndServerReplayClassesHaveNoClientBytecodeLinks() throws Exception {
        String pkg = "com/habitrain/core/";
        List<String> classes = List.of(
                "api/match/replay/LastMatchReplay",
                "api/match/replay/ReplayBlockDelta",
                "api/match/replay/ReplayEntityTrack",
                "api/match/replay/ReplaySkillEvent",
                "api/match/replay/ReplayGameSoundData",
                "scene/replay/ReplayVoiceData",
                "scene/replay/ReplayVoiceAudibilityResolver",
                "scene/replay/ReplayBoardSessionState",
                "scene/replay/ReplayTrackSampler",
                "scene/replay/ReplayWsolaTimeStretch",
                "scene/replay/ReplayExportContentOptions",
                "scene/replay/ReplayHighlightScorer",
                "network/replay/ReplayPackets",
                "network/replay/ReplayTracks",
                "network/replay/ReplayArchivePackets",
                "network/replay/ReplayBoardSettingsPackets",
                "game/sre/replay/GlobalMatchSoundRecorder",
                "game/sre/replay/MatchVoiceRecorder",
                "game/sre/replay/MatchReplayRecorder",
                "game/sre/replay/ReplayArchive",
                "game/sre/replay/ReplayArchiveService",
                "game/sre/replay/ReplayBoardService"
        );
        for (String name : classes) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(pkg + name + ".class")) {
                assertNotNull(stream, "Missing class bytecode for " + name);
                String constants = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of(
                        "net/minecraft/client/",
                        "com/mojang/blaze3d/",
                        "net/fabricmc/fabric/api/client/",
                        pkg + "client/",
                        pkg + "scene/client/")) {
                    assertFalse(constants.contains(forbidden), name + " must not reference client class " + forbidden);
                }
            }
        }
    }

    private static short[] sine48k(double freqHz, int amp) {
        short[] out = new short[960];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) Math.round(amp * Math.sin(2.0 * Math.PI * freqHz * i / 48_000.0));
        }
        return out;
    }

    private static Sample sampleAt(int tick, double x, double y, double z, float yawDeg, byte flags) {
        return new Sample(tick, Position.quantize(x, y, z), Sample.quantizeYaw(yawDeg), flags);
    }

    private static List<Sample> track(Sample... samples) {
        return List.of(samples);
    }

    private static Participant part(UUID id, String name, String role, MatchWinFaction faction, List<Sample> track) {
        return new Participant(id, name, role, 0xFF44AAFF, role, 0xFF44AAFF, faction, true, 60, 0, 10, 0, track);
    }
}
