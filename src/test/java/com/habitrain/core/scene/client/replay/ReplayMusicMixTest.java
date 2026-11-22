package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplayMusicPlan;
import com.habitrain.core.scene.replay.ReplaySoundCuePlan;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.habitrain.core.scene.replay.ReplayVideoScriptTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayMusicMixTest {
    private static final int RATE = ReplayVideoAudioRenderer.SAMPLE_RATE;

    private static ReplayVideoScript script() {
        var events = List.of(node(900, LastMatchReplay.Kind.KILL, A, B), node(1300, LastMatchReplay.Kind.KILL, A, C),
                node(2500, LastMatchReplay.Kind.PLAYER_REVIVAL, B, null), node(4000, LastMatchReplay.Kind.GRENADE_EXPLOSION, C, null));
        return new ReplayVideoScript(replay(6000, events, List.of()));
    }

    private static double rms(short[] pcm, double from, double to) {
        int a = (int) (from * RATE), b = (int) (to * RATE);
        double sum = 0;
        for (int i = a; i < b; i++) {
            double l = pcm[i * 2], r = pcm[i * 2 + 1];
            sum += l * l + r * r;
        }
        return Math.sqrt(sum / Math.max(1, 2 * (b - a)));
    }

    @Test void soundtrackFillsTheFilmAndFallsSilentBeforeEachImpact() {
        var script = script();
        int frames = script.frames(ReplayVideoExport.FPS);
        var score = ReplayMusicPlan.compose(script, ReplaySoundCuePlan.Schedule.EMPTY);
        long started = System.nanoTime();
        short[] pcm = ReplayVideoAudioRenderer.renderSchedule(ReplaySoundCuePlan.Schedule.EMPTY, keys -> null, frames,
                score, ReplayMusicPlan.Level.MEDIUM.gain);
        double seconds = (System.nanoTime() - started) / 1e9;
        assertTrue(seconds < 20, "render took " + seconds + " s");

        double action = script.actStart(ReplayVideoScript.Act.ACTION);
        double review = script.actStart(ReplayVideoScript.Act.REVIEW);
        double outro = script.actStart(ReplayVideoScript.Act.OUTRO);
        assertTrue(rms(pcm, 0.6, action - 0.6) > 150, "title music");
        assertTrue(rms(pcm, action + 0.05, action + 1.5) > 400, "action music");
        assertTrue(rms(pcm, review + 0.5, review + 2.5) > 150, "review music");
        assertTrue(rms(pcm, outro + 0.2, outro + 2.0) > 150, "outro music");
        int peak = 0;
        for (short s : pcm) peak = Math.max(peak, Math.abs(s));
        assertTrue(peak < 32767, "limited, not clipped");

        for (var duck : score.ducks()) {
            if (duck.depth() > 0 || duck.end() - duck.start() < 0.03) continue;
            int a = (int) Math.ceil((duck.start() + 0.008) * RATE), b = (int) ((duck.end() - 0.004) * RATE);
            for (int i = a; i < b; i++) {
                assertEquals(0, pcm[i * 2], "silence before the impact at " + duck.end());
                assertEquals(0, pcm[i * 2 + 1], "silence before the impact at " + duck.end());
            }
            assertTrue(rms(pcm, duck.end() + 0.003, duck.end() + 0.08) > 500, "hit on the impact at " + duck.end());
        }
        assertEquals(0, pcm[pcm.length - 2], "fades to silence");
    }

    @Test void offLevelKeepsTheOldMixBitExact() {
        var script = script();
        int frames = script.frames(ReplayVideoExport.FPS);
        short[] off = ReplayVideoAudioRenderer.renderSchedule(ReplaySoundCuePlan.Schedule.EMPTY, keys -> null, frames, null, 0f);
        for (short s : off) assertEquals(0, s);
        var score = ReplayMusicPlan.compose(script, null);
        short[] a = ReplayVideoAudioRenderer.renderSchedule(ReplaySoundCuePlan.Schedule.EMPTY, keys -> null, frames, score, .46f);
        short[] b = ReplayVideoAudioRenderer.renderSchedule(ReplaySoundCuePlan.Schedule.EMPTY, keys -> null, frames, score, .46f);
        assertArrayEquals(a, b, "deterministic export");
    }
}
