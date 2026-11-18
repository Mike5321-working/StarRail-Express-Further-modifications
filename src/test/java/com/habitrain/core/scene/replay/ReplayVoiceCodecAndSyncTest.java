package com.habitrain.core.scene.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;

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
