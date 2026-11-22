package com.habitrain.core.scene.replay;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayKillSounds;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;
import net.minecraft.resources.ResourceLocation;

class ReplayVoiceCodecAndSyncTest {

    @Test
    void adpcmAndContainerRoundTripPreservesWaveformAndOffsets() {
        int sr = ReplayVoiceData.VOICECHAT_SAMPLE_RATE;
        short[] src48k = new short[sr]; // 1.0s at 48 kHz
        for (int i = 0; i < src48k.length; i++) {
            double t = i / (double) sr;
            double v = 0.45 * Math.sin(2.0 * Math.PI * 340.0 * t)
                    + 0.25 * Math.sin(2.0 * Math.PI * 780.0 * t)
                    + 0.15 * Math.cos(2.0 * Math.PI * 1450.0 * t);
            src48k[i] = (short) Math.round(v * 24000.0);
        }

        short[] down24k = ReplayVoiceData.downsample48kTo24k(src48k);
        assertEquals(ReplayVoiceData.SAMPLE_RATE, down24k.length);
        byte[] adpcm = ReplayVoiceData.encodeAdpcm(down24k);
        ReplayVoiceData.Clip clip = new ReplayVoiceData.Clip(3, 240, -640, down24k.length, adpcm);
        ReplayVoiceData encoded = new ReplayVoiceData(List.of(clip));

        byte[] raw = encoded.toBytes();
        ReplayVoiceData decoded = ReplayVoiceData.fromBytes(raw);
        assertEquals(1, decoded.clips().size());
        ReplayVoiceData.Clip roundTrip = decoded.clips().getFirst();
        assertEquals(3, roundTrip.eventIndex());
        assertEquals(240, roundTrip.tick());
        assertEquals(-640, roundTrip.offsetMs());
        assertEquals(down24k.length, roundTrip.sampleCount());

        short[] rec48k = roundTrip.decode48k();
        assertEquals(src48k.length, rec48k.length);
        double corr = pearsonCorrelation(src48k, rec48k, 2000, src48k.length - 2000);
        assertTrue(corr > 0.96, "Expected high speech-band ADPCM correlation, got " + corr);
    }

    @Test
    void invalidVoiceBytesAreRejected() {
        assertTrue(ReplayVoiceData.fromBytes(new byte[0]).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ReplayVoiceData.fromBytes(new byte[]{1, 2, 3}));
        byte[] badMagic = new byte[12];
        ByteBuffer.wrap(badMagic).putInt(0x12345678).putInt(1).putInt(0);
        assertThrows(IllegalArgumentException.class, () -> ReplayVoiceData.fromBytes(badMagic));
    }

    @Test
    void soundCuePlanLocksPrimaryWeaponAndVoiceOntoExactVideoAndBoardCloseUpImpacts() {
        UUID killer = UUID.randomUUID();
        UUID victimA = UUID.randomUUID();
        UUID victimB = UUID.randomUUID();
        SceneBounds bounds = new SceneBounds(0, 0, 0, 80, 16, 24);

        LastMatchReplay.Position kPos = LastMatchReplay.Position.quantize(20, 2, 10);
        LastMatchReplay.Position vAPos = LastMatchReplay.Position.quantize(26, 2, 12);
        LastMatchReplay.Position vBPos = LastMatchReplay.Position.quantize(55, 2, 15);

        List<LastMatchReplay.Participant> people = List.of(
                new LastMatchReplay.Participant(killer, "Killer", "killer", 0xFFAA2222, "killer", 0xFFAA2222,
                        MatchWinFaction.KILLER, true, 120, 2, 100, 2, List.of()),
                new LastMatchReplay.Participant(victimA, "VictimA", "civilian", 0xFF44AAFF, "civilian", 0xFF44AAFF,
                        MatchWinFaction.PASSENGER, false, 15, 0, 10, 0, List.of()),
                new LastMatchReplay.Participant(victimB, "VictimB", "civilian", 0xFF44AAFF, "civilian", 0xFF44AAFF,
                        MatchWinFaction.PASSENGER, false, 60, 0, 20, 0, List.of())
        );

        // Index 0: GUNSHOT at tick 100, Index 1: KILL (revolver) at tick 101 (paired), Index 2: KILL (knife) at tick 320.
        List<LastMatchReplay.Node> events = List.of(
                new LastMatchReplay.Node(100, LastMatchReplay.Kind.GUNSHOT, killer, victimA, kPos, vAPos,
                        "starrailexpress:revolver", LastMatchReplay.HIT),
                new LastMatchReplay.Node(101, LastMatchReplay.Kind.KILL, killer, victimA, kPos, vAPos,
                        "starrailexpress:revolver", LastMatchReplay.HIT),
                new LastMatchReplay.Node(320, LastMatchReplay.Kind.KILL, killer, victimB, vBPos, vBPos,
                        "starrailexpress:knife_stab", 0)
        );
        LastMatchReplay replay = new LastMatchReplay(
                UUID.randomUUID(), 1000L, 500, "Express", "classic", "KILLERS", MatchWinKind.KILLER,
                "", "", "", 0xFFAA2222, bounds, null, people, events, List.of(), killer, 2, false);

        short[] dummyVoice = new short[ReplayVoiceData.SAMPLE_RATE]; // 1.0s @ 24kHz
        Arrays.fill(dummyVoice, (short) 4000);
        ReplayVoiceData voice = new ReplayVoiceData(List.of(
                new ReplayVoiceData.Clip(1, 101, -400, dummyVoice.length, ReplayVoiceData.encodeAdpcm(dummyVoice)),
                new ReplayVoiceData.Clip(2, 320, -350, dummyVoice.length, ReplayVoiceData.encodeAdpcm(dummyVoice))
        ));

        ReplayVideoScript script = new ReplayVideoScript(replay);
        ReplaySoundCuePlan.Schedule video = ReplaySoundCuePlan.forVideo(replay, script, voice);
        assertEquals(2, script.beats().size());
        assertEquals(2, video.voices().size());

        // Beat 0: paired revolver kill -> gunshot SFX at exact beat.impact(), NO duplicate gunshot!
        ReplayVideoScript.Beat beat0 = script.beats().get(0);
        assertEquals(beat0.impact(), video.voices().get(0).impactTime(), 1e-6);
        List<ReplaySoundCuePlan.SoundCue> revolverCues = video.sounds().stream()
                .filter(c -> c.candidates().contains("starrailexpress:item.revolver.shoot"))
                .toList();
        assertEquals(1, revolverCues.size(), "Paired GUNSHOT+KILL must emit a single synchronized gunshot cue");
        assertEquals(beat0.impact(), revolverCues.getFirst().time(), 1e-6);

        // Beat 1: knife kill -> prepare before impact (-0.18s) and stab on exact beat.impact()
        ReplayVideoScript.Beat beat1 = script.beats().get(1);
        assertEquals(beat1.impact(), video.voices().get(1).impactTime(), 1e-6);
        ReplaySoundCuePlan.SoundCue knifeStab = video.sounds().stream()
                .filter(c -> c.candidates().contains("starrailexpress:item.knife.stab"))
                .findFirst().orElseThrow();
        assertEquals(beat1.impact(), knifeStab.time(), 1e-6);

        // 3D Board close-ups: sound and voice lock onto the exact ReplayFocusPlan zoom anchor
        // (tick 100 GUNSHOT paired into tick 101 KILL, and tick 320 knife KILL).
        ReplayTimeline timeline = new ReplayTimeline(replay);
        ReplaySoundCuePlan.Schedule board = ReplaySoundCuePlan.forBoard(replay, timeline, voice);
        double expectedBoardKill0 = timeline.actionTickAt(100);
        double expectedBoardKill1 = timeline.actionTickAt(320);
        assertEquals(2, board.voices().size());
        assertEquals(expectedBoardKill0, board.voices().get(0).impactTime(), 1e-6);
        assertEquals(expectedBoardKill1, board.voices().get(1).impactTime(), 1e-6);

        ReplaySoundCuePlan.SoundCue boardShot = board.sounds().stream()
                .filter(c -> c.candidates().contains("starrailexpress:item.revolver.shoot"))
                .findFirst().orElseThrow();
        assertEquals(expectedBoardKill0, boardShot.time(), 1e-6);
    }

    @Test
    void recordedPrivateCueReplacesSimulatedKillSoundOnAllThreeTimelines() {
        UUID victim = UUID.randomUUID();
        var pos = LastMatchReplay.Position.quantize(10, 2, 10);
        var kill = new LastMatchReplay.Node(100, LastMatchReplay.Kind.KILL, null, victim, null, pos, "knife", 0);
        var privateSound = ResourceLocation.parse("noellesroles:sounds/role/private_hint.ogg");
        var replay = new LastMatchReplay(UUID.randomUUID(), 0, 120, "Train", "classic", "", MatchWinKind.KILLER,
                "", "", "", 0, new SceneBounds(0, 0, 32, 80, 16, 64), null,
                List.of(), List.of(kill), List.of(), null, 1, false).withKillSounds(List.of(
                new ReplayKillSounds.Window(0, List.of(new ReplayKillSounds.Cue(-200, privateSound, .35f, 1.3f)))));
        var video = new ReplayVideoScript(replay);
        var board = new ReplayTimeline(replay);

        var live = ReplaySoundCuePlan.forLinearReplay(replay, null).sounds();
        var table = ReplaySoundCuePlan.forBoard(replay, board, null).sounds();
        var export = ReplaySoundCuePlan.forVideo(replay, video, null).sounds();
        var exportEventCues = export.stream().filter(cue -> cue.eventIndex() >= 0).toList();
        assertEquals(1, live.size());
        assertEquals(1, table.size());
        assertEquals(3, export.size());
        assertEquals(1, exportEventCues.size());
        assertEquals(privateSound, live.getFirst().recordedFile());
        assertEquals(96.0, live.getFirst().time(), 1e-6);
        assertEquals(board.actionTickAt(100) - 4, table.getFirst().time(), 1e-6);
        assertEquals(video.beats().getFirst().impact() - .2, exportEventCues.getFirst().time(), 1e-6);
        assertEquals(.35f, exportEventCues.getFirst().volume());
        assertEquals(1.3f, exportEventCues.getFirst().pitch());

        var silent = replay.withKillSounds(List.of(new ReplayKillSounds.Window(0, List.of())));
        assertFalse(ReplaySoundCuePlan.forLinearReplay(silent, null).sounds().isEmpty());
        var legacy = replay.withKillSounds(List.of());
        assertEquals(ReplaySoundCuePlan.forLinearReplay(legacy, null).sounds(),
                ReplaySoundCuePlan.forLinearReplay(replay, null, file -> false).sounds());
        assertEquals(ReplaySoundCuePlan.forBoard(legacy, board, null).sounds(),
                ReplaySoundCuePlan.forBoard(replay, board, null, file -> false).sounds());
        assertEquals(ReplaySoundCuePlan.forVideo(legacy, video, null).sounds(),
                ReplaySoundCuePlan.forVideo(replay, video, null, file -> false).sounds());
    }

    @Test
    void boardSchedulesFinalImpactAndItsFullRecordedTailIntoReview() {
        var kill = knifeKillAt(600);
        var file = ResourceLocation.parse("test:sounds/finish.ogg");
        var replay = replayWithKills(600, kill).withKillSounds(List.of(new ReplayKillSounds.Window(0, List.of(
                new ReplayKillSounds.Cue(0, file, .5f, 1f),
                new ReplayKillSounds.Cue(ReplayKillSounds.AFTER_MS, file, .5f, 1f)))));
        var timeline = new ReplayTimeline(replay);
        double impact = timeline.actionTickAt(replay.durationTicks());
        var sounds = ReplaySoundCuePlan.forBoard(replay, timeline, null).sounds();

        assertEquals(2, sounds.size());
        assertEquals(impact, sounds.getFirst().time(), 1e-6);
        double tail = sounds.getLast().time();
        assertEquals(impact + 20, tail, 1e-6);
        assertEquals(ReplayTimeline.Phase.REVIEW, timeline.atElapsed(ReplayTimeline.INTRO + tail).phase());
    }

    @Test
    void videoScheduleIncludesUpstreamMatchStartAndEndSoundCues() {
        var replay = replayWithKills(600);
        var script = new ReplayVideoScript(replay);
        var timeline = new ReplayTimeline(replay);

        var videoSounds = ReplaySoundCuePlan.forVideo(replay, script, null).sounds();
        assertEquals(2, videoSounds.size());

        var startCue = videoSounds.getFirst();
        assertEquals(script.actStart(ReplayVideoScript.Act.TITLE), startCue.time(), 1e-6);
        assertEquals(0.0, startCue.time(), 1e-6);
        assertEquals(-1, startCue.eventIndex());
        assertEquals(ReplaySoundCuePlan.VIDEO_MATCH_START_SOUNDS, startCue.candidates());
        assertEquals(0f, startCue.pan());
        assertNull(startCue.recordedFile());

        var endCue = videoSounds.getLast();
        assertEquals(script.actStart(ReplayVideoScript.Act.VICTORY), endCue.time(), 1e-6);
        assertEquals(-1, endCue.eventIndex());
        assertEquals(ReplaySoundCuePlan.VIDEO_MATCH_END_SOUNDS, endCue.candidates());
        assertEquals(0f, endCue.pan());
        assertNull(endCue.recordedFile());

        assertTrue(ReplaySoundCuePlan.forBoard(replay, timeline, null).sounds().isEmpty());
        assertTrue(ReplaySoundCuePlan.forLinearReplay(replay, null).sounds().isEmpty());
    }

    @Test
    void overlappingSecondaryKillVoiceUsesOnlyTheGapAfterTheFeaturedVoice() {
        var replay = replayWithKills(600, knifeKillAt(200), knifeKillAt(200));
        var featured = voiceClip(0, 200, -400, 1);
        var secondary = voiceClip(1, 200, -500, 2);
        var voice = new ReplayVoiceData(List.of(featured, secondary));
        var featuredOnly = new ReplayVoiceData(List.of(featured));
        var timeline = new ReplayTimeline(replay);
        var script = new ReplayVideoScript(replay);

        var board = ReplaySoundCuePlan.forBoard(replay, timeline, voice).voices();
        var video = ReplaySoundCuePlan.forVideo(replay, script, voice).voices();
        assertVoiceFitsAfterFeatured(board, ReplaySoundCuePlan.forBoard(replay, timeline, featuredOnly).voices().getFirst(),
                secondary, 20);
        assertVoiceFitsAfterFeatured(video, ReplaySoundCuePlan.forVideo(replay, script, featuredOnly).voices().getFirst(),
                secondary, 1);
    }

    private static void assertVoiceFitsAfterFeatured(List<ReplaySoundCuePlan.VoiceCue> voices,
                                                     ReplaySoundCuePlan.VoiceCue original,
                                                     ReplayVoiceData.Clip secondary, double unitsPerSecond) {
        assertEquals(2, voices.size());
        assertEquals(original, voices.getFirst());
        var added = voices.getLast();
        assertSame(secondary, added.clip());
        assertTrue(added.startTime() >= original.endTime() + .08 * unitsPerSecond - 1e-6);
        assertTrue(added.sampleStartTime(unitsPerSecond) < added.startTime());
    }

    private static LastMatchReplay.Node knifeKillAt(int tick) {
        var pos = LastMatchReplay.Position.quantize(10, 2, 10);
        return new LastMatchReplay.Node(tick, LastMatchReplay.Kind.KILL, null, UUID.randomUUID(), null, pos, "knife", 0);
    }

    private static LastMatchReplay replayWithKills(int duration, LastMatchReplay.Node... kills) {
        return new LastMatchReplay(UUID.randomUUID(), 0, duration, "Train", "classic", "", MatchWinKind.KILLER,
                "", "", "", 0, new SceneBounds(0, 0, 0, 80, 16, 64), null,
                List.of(), List.of(kills), List.of(), null, kills.length, false);
    }

    private static ReplayVoiceData.Clip voiceClip(int eventIndex, int tick, int offsetMs, int seconds) {
        short[] samples = new short[seconds * ReplayVoiceData.SAMPLE_RATE];
        return new ReplayVoiceData.Clip(eventIndex, tick, offsetMs, samples.length, ReplayVoiceData.encodeAdpcm(samples));
    }

    private static double pearsonCorrelation(short[] a, short[] b, int start, int end) {
        double sumAA = 0, sumBB = 0, sumAB = 0;
        for (int i = start; i < end; i++) {
            double x = a[i];
            double y = b[i];
            sumAA += x * x;
            sumBB += y * y;
            sumAB += x * y;
        }
        return sumAB / Math.sqrt(sumAA * sumBB);
    }
}
