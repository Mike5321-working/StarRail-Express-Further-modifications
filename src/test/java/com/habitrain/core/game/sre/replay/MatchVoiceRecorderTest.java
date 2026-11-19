package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchVoiceRecorderTest {

    @Test
    void enforcesProximityWhisperAndDeathSpectatorCutoffWhileKeepingDeathCry() {
        UUID roundId = UUID.randomUUID();
        SceneBounds bounds = new SceneBounds(0, 0, 0, 100, 20, 40);
        MatchVoiceRecorder.beginTestRound(roundId, bounds);

        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        UUID farPlayer = UUID.randomUUID();
        UUID whisperer = UUID.randomUUID();
        UUID deadSpectator = UUID.randomUUID();

        // Kill happens at match tick 100 (matchMs = 5000) at (20, 4, 20).
        int killTick = 100;
        for (int t = 80; t <= 125; t += 5) {
            boolean victimAlive = t < killTick;
            MatchVoiceRecorder.recordPositionForTest(roundId, killer, t, 18, 4, 20, true);
            MatchVoiceRecorder.recordPositionForTest(roundId, victim, t, 20, 4, 20, victimAlive);
            MatchVoiceRecorder.recordPositionForTest(roundId, farPlayer, t, 85, 4, 20, true);      // 65m away (> 48m)
            MatchVoiceRecorder.recordPositionForTest(roundId, whisperer, t, 36, 4, 20, true);      // 16m away (> 8m whisper)
            MatchVoiceRecorder.recordPositionForTest(roundId, deadSpectator, t, 21, 4, 20, false); // already dead before window
        }

        // Distinctive sine tones (48 kHz, 960 samples = 20 ms per packet)
        short[] nearFrame = sine48k(440.0, 18_000);   // audible near kill (t = 90..95 -> 4500..4760ms)
        short[] cryFrame = sine48k(660.0, 16_000);    // victim death cry 80ms after kill (within 150ms grace!)
        short[] farFrame = sine48k(1500.0, 22_000);   // 65m away (> maxDist 48m) -> must be excluded
        short[] whisperFrame = sine48k(2100.0, 22_000); // 16m away (> whisper 8m) -> must be excluded
        short[] ghostFrame = sine48k(2800.0, 22_000); // dead spectator -> must be excluded

        for (int k = 0; k < 8; k++) {
            MatchVoiceRecorder.recordPcm48kAtMs(roundId, killer, 4500L + k * 20L, 48.0f, nearFrame);
            MatchVoiceRecorder.recordPcm48kAtMs(roundId, farPlayer, 5100L + k * 20L, 48.0f, farFrame);
            MatchVoiceRecorder.recordPcm48kAtMs(roundId, whisperer, 5100L + k * 20L, 8.0f, whisperFrame);
            MatchVoiceRecorder.recordPcm48kAtMs(roundId, deadSpectator, 5200L + k * 20L, 48.0f, ghostFrame);
        }
        // Victim death cry right after kill tick (5000..5120 ms <= 6000 ms)
        for (int k = 0; k < 5; k++) {
            MatchVoiceRecorder.recordPcm48kAtMs(roundId, victim, 5020L + k * 20L, 48.0f, cryFrame);
        }

        LastMatchReplay.Position pos = LastMatchReplay.Position.quantize(20, 4, 20);
        LastMatchReplay.Node kill = new LastMatchReplay.Node(killTick, LastMatchReplay.Kind.KILL,
                killer, victim, pos, pos, "starrailexpress:revolver", LastMatchReplay.HIT);

        ReplayVoiceData extracted = MatchVoiceRecorder.extractForTest(roundId, List.of(kill));
        assertEquals(1, extracted.clips().size());
        ReplayVoiceData.Clip clip = extracted.clips().getFirst();
        assertTrue(clip.offsetMs() < 0, "Killer speech at 4500ms should give negative lead-in offset, got " + clip.offsetMs());

        short[] decoded = clip.decode48k();
        // Convert offsetMs relative to kill (5000ms) -> sample index of 4540ms (killer), 5040ms (victim cry), 5240ms (ghost/far).
        int sr = ReplayVoiceData.VOICECHAT_SAMPLE_RATE;
        int clipStartMs = 5000 + clip.offsetMs();
        int killerIdx = Math.max(0, (4540 - clipStartMs) * (sr / 1000));
        int cryIdx = Math.max(0, (5040 - clipStartMs) * (sr / 1000));
        double killerRms = rms(decoded, killerIdx, Math.min(decoded.length, killerIdx + 2400));
        double cryRms = rms(decoded, cryIdx, Math.min(decoded.length, cryIdx + 1920));
        assertTrue(killerRms > 3000, "Expected strong in-range killer voice RMS, got " + killerRms);
        assertTrue(cryRms > 2000, "Expected preserved victim death-cry RMS, got " + cryRms);

        // Tail past 5180ms only contained farPlayer, whisperer, and deadSpectator -> must be silent / trimmed!
        int postWindowSamples = (5180 - clipStartMs) * (sr / 1000);
        if (postWindowSamples < decoded.length) {
            double ghostRms = rms(decoded, postWindowSamples, decoded.length);
            assertTrue(ghostRms < 200, "Expected out-of-range/whisper/dead voices excluded, got tail RMS " + ghostRms);
        }
    }

    @Test
    void keepsEarlyAndLateHighlightsAfterLongConcurrentSpeech() {
        UUID round = UUID.randomUUID(), speaker = UUID.randomUUID();
        List<UUID> voices = List.of(speaker, UUID.randomUUID(), UUID.randomUUID());
        MatchVoiceRecorder.beginTestRound(round, new SceneBounds(0, 0, 0, 100, 20, 40));
        var early = kill(40, speaker);
        var late = kill(6_300, speaker);
        var frame = sine48k(440, 18_000);
        for (int tick = 0; tick <= 6_360; tick += 20) {
            MatchVoiceRecorder.recordPositionForTest(round, speaker, tick, 20, 4, 20, true);
            for (int i = 0; i < 50; i++) {
                for (UUID id : voices) MatchVoiceRecorder.recordPcm48kAtMs(round, id, tick * 50L + i * 20, 48, frame);
            }
            if (tick < early.tick()) {
                MatchVoiceRecorder.captureClosedWindowsForTest(tick, List.of());
            } else {
                MatchVoiceRecorder.captureClosedWindowsForTest(tick, tick < late.tick() ? List.of(early) : List.of(early, late));
            }
        }
        var result = MatchVoiceRecorder.extractForTest(round, List.of(early, late));
        assertEquals(List.of(0, 1), result.clips().stream().map(ReplayVoiceData.Clip::eventIndex).toList());
    }

    @Test
    void excludesSpeechAfterWorldDepartureEvenDuringDeathGrace() {
        UUID round = UUID.randomUUID(), speaker = UUID.randomUUID();
        MatchVoiceRecorder.beginTestRound(round, new SceneBounds(0, 0, 0, 100, 20, 40));
        MatchVoiceRecorder.recordPositionForTest(round, speaker, 95, 20, 4, 20, true);
        MatchVoiceRecorder.leaveForTest(speaker, 100);
        var frame = sine48k(440, 18_000);
        for (int i = 0; i < 7; i++) {
            MatchVoiceRecorder.recordPcm48kAtMs(round, speaker, 5_000 + i * 20, 48, frame);
        }
        assertTrue(MatchVoiceRecorder.extractForTest(round, List.of(kill(100, speaker))).isEmpty());
    }

    @Test
    void keepsCapturedSpeechAndRejectsPacketsAfterRecordingStops() {
        UUID round = UUID.randomUUID(), speaker = UUID.randomUUID();
        MatchVoiceRecorder.beginTestRound(round, new SceneBounds(0, 0, 0, 100, 20, 40));
        MatchVoiceRecorder.recordPositionForTest(round, speaker, 80, 20, 4, 20, true);
        var frame = sine48k(440, 18_000);
        for (int i = 0; i < 8; i++) MatchVoiceRecorder.recordPcm48kAtMs(round, speaker, 4_500 + i * 20, 48, frame);
        MatchVoiceRecorder.stop(null);
        for (int i = 0; i < 8; i++) MatchVoiceRecorder.recordPcm48kAtMs(round, speaker, 5_000 + i * 20, 48, frame);
        var clip = MatchVoiceRecorder.extractForTest(round, List.of(kill(100, speaker))).clips().getFirst();
        assertTrue(clip.offsetMs() + clip.durationSeconds() * 1000 < 0);
    }

    private static LastMatchReplay.Node kill(int tick, UUID actor) {
        var pos = LastMatchReplay.Position.quantize(20, 4, 20);
        return new LastMatchReplay.Node(tick, LastMatchReplay.Kind.KILL, actor, null, pos, pos, "", LastMatchReplay.HIT);
    }

    private static short[] sine48k(double freqHz, int amp) {
        short[] out = new short[960];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) Math.round(amp * Math.sin(2.0 * Math.PI * freqHz * i / 48_000.0));
        }
        return out;
    }

    private static double rms(short[] s, int from, int to) {
        if (to <= from) return 0;
        double sum = 0;
        for (int i = from; i < to; i++) sum += (double) s[i] * s[i];
        return Math.sqrt(sum / (to - from));
    }
}
