package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import com.habitrain.core.api.match.replay.ReplaySkillEvent;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplayCinemaDirector;
import com.habitrain.core.scene.replay.ReplayExportContentOptions;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.ActorOverlayFilter;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.ExportMode;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.IdentityReveal;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PersonalPacing;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PersonalView;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PromoScope;
import com.habitrain.core.scene.replay.ReplayHighlightScorer;
import com.habitrain.core.scene.replay.ReplayHighlightScorer.HighlightTier;
import com.habitrain.core.scene.replay.ReplayMusicPlan;
import com.habitrain.core.scene.replay.ReplayStreamingAudioMixer;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.replay.ReplayVoiceData.ChannelMode;
import com.habitrain.core.scene.replay.ReplayVoiceData.VoiceTimelineMeta;
import com.habitrain.core.scene.replay.ReplayVoiceData.VoiceUtterance;
import com.habitrain.core.scene.replay.ReplayWsolaTimeStretch;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification suite for all 4 replay video export modes, 40-player 20-minute
 * 5-tier highlight rarity pyramid scoring, WSOLA pitch-preserving voice time-stretch (Defect #2),
 * 20-minute zero-230MB streaming audio mixing (Defect #3), 14-toggle freeform content customizer,
 * and three-tier unknown-mod skill fallback generation.
 */
class ReplayVideoModesAndScoringTest {

    @Test
    void personalWholeMatchDoesNotStopAtPermanentDeath() {
        UUID subject = UUID.randomUUID();
        var position = LastMatchReplay.Position.quantize(1, 1, 1);
        var actor = new LastMatchReplay.Participant(subject, "Subject", "role", 0, "role", 0,
                MatchWinFaction.PASSENGER, false, 10, 0, 0, 0, List.of(
                new LastMatchReplay.Sample(0, position, (byte) 0, ALIVE),
                new LastMatchReplay.Sample(200, position, (byte) 0, (byte) 0)));
        var replay = new LastMatchReplay(UUID.randomUUID(), 0, 1_200, "test", "murder", "NONE", MatchWinKind.NONE,
                "", "", "", 0, new SceneBounds(0, 0, 0, 20, 8, 20), null, List.of(actor), List.of(), List.of(), null, 0, false);
        var options = ReplayExportContentOptions.defaultsFor(ExportMode.PERSONAL, subject);
        var film = ReplayCinemaDirector.direct(replay, ReplayVoiceData.EMPTY, options.toBuilder().trimRange(0, -1).build());
        assertEquals(1_200, film.trimEndTick());
        assertEquals(1_200, film.segments().getLast().endTick());
        assertEquals(700, ReplayCinemaDirector.direct(replay, ReplayVoiceData.EMPTY,
                options.toBuilder().trimRange(0, 700).build()).trimEndTick());
    }

    @Test
    void splitAudioUsesRightPanelClockEvenWhenBothPanelsContainTheCue() throws Exception {
        var split = new ReplayCinemaDirector.DirectedSegment(0, ReplayCinemaDirector.SegmentKind.APPROACH,
                ReplayCinemaDirector.CameraDirective.SPLIT_DIORAMA_AND_POV, ReplayCinemaDirector.CutStyle.NONE,
                0, 1.4, 176, 200, 0.95, 0.45, false, true, 0.535, 0.535, 170, 200,
                UUID.randomUUID(), null, 1, 1, 0, 0.9, "");
        var method = ReplayCinemaDirector.class.getDeclaredMethod("mapTickToSegmentVideoTime", ReplayCinemaDirector.DirectedSegment.class, double.class);
        method.setAccessible(true);
        double audio = (double) method.invoke(null, split, 184.0);
        assertEquals(184.0, split.splitRightTickAt(audio), 0.000001);
        assertNotEquals(184.0, split.tickAt(audio), 0.1);
    }

    @Test
    void allFourExportModesRespectCruiseCapsSplitScreenAndPersonalPacing() {
        SimulatedMatch sim = build40Player20MinuteMatch();
        UUID starKiller = sim.killers.getFirst();

        // Mode 1: SHORT — readable cruise <= 5.0x (eliminates 46x teleport Defect #1) + kill split-screen POV
        var shortOpts = ReplayExportContentOptions.defaultsFor(ExportMode.SHORT);
        var shortFilm = ReplayCinemaDirector.direct(sim.replay, sim.voice, shortOpts);
        assertTrue(shortFilm.maxCruiseSpeed() <= ReplayCinemaDirector.MAX_SHORT_CRUISE_SPEED + 1e-6,
                "Short mode cruise speed must stay <= 5.0x (no 46x teleport), got " + shortFilm.maxCruiseSpeed());
        for (var seg : shortFilm.segments()) {
            if (seg.kind() == ReplayCinemaDirector.SegmentKind.CRUISE) {
                assertTrue(Math.max(seg.startRate(), seg.endRate()) <= ReplayCinemaDirector.MAX_SHORT_CRUISE_SPEED + 1e-6);
            }
        }
        boolean hasKillSplit = shortFilm.segments().stream().anyMatch(seg ->
                seg.splitScreen()
                        && seg.camera() == ReplayCinemaDirector.CameraDirective.SPLIT_DIORAMA_AND_POV
                        && seg.splitLeftRatioStart() >= 0.50 && seg.splitLeftRatioStart() <= 0.56
                        && seg.splitRightStartTick() < seg.startTick());
        assertTrue(hasKillSplit, "Short mode must produce 4-deg split-screen with right killer POV starting pre-kill");

        // Mode 2: PERSONAL — True POV vs Sandbox Follow x 1x / 2x / 4x / Highlights Only
        var p1xOpts = ReplayExportContentOptions.builder(ExportMode.PERSONAL)
                .focusPlayer(starKiller).personalView(PersonalView.TRUE_POV).personalPacing(PersonalPacing.SPEED_1X)
                .trimRange(0, 2400).includeTitle(false).includeVictoryMvp(false).build();
        var p2xOpts = p1xOpts.toBuilder().personalPacing(PersonalPacing.SPEED_2X).build();
        var p4xOpts = p1xOpts.toBuilder().personalPacing(PersonalPacing.SPEED_4X).build();
        var pHiOpts = ReplayExportContentOptions.builder(ExportMode.PERSONAL)
                .focusPlayer(starKiller).personalView(PersonalView.SANDBOX_FOLLOW)
                .personalPacing(PersonalPacing.HIGHLIGHTS_ONLY).build();

        var p1x = ReplayCinemaDirector.direct(sim.replay, sim.voice, p1xOpts);
        var p2x = ReplayCinemaDirector.direct(sim.replay, sim.voice, p2xOpts);
        var p4x = ReplayCinemaDirector.direct(sim.replay, sim.voice, p4xOpts);
        var pHi = ReplayCinemaDirector.direct(sim.replay, sim.voice, pHiOpts);

        assertEquals(120.0, p1x.actLength(ReplayVideoScript.Act.ACTION), 1.0);
        assertEquals(60.0, p2x.actLength(ReplayVideoScript.Act.ACTION), 1.0);
        assertEquals(30.0, p4x.actLength(ReplayVideoScript.Act.ACTION), 1.0);
        assertTrue(p1x.segments().stream().anyMatch(s -> s.camera() == ReplayCinemaDirector.CameraDirective.POV_FIRST_PERSON));
        assertTrue(pHi.segments().stream().anyMatch(s -> s.camera() == ReplayCinemaDirector.CameraDirective.DIORAMA_FOLLOW));
        assertTrue(pHi.segments().stream().anyMatch(s -> s.kind() == ReplayCinemaDirector.SegmentKind.TRANSITION_CUT
                && (s.cutStyle() == ReplayCinemaDirector.CutStyle.TICKET_FLIP
                || s.cutStyle() == ReplayCinemaDirector.CutStyle.BRASS_SWEEP)),
                "Personal Highlights Only must join 1x clips with ticket-flip or brass-sweep transitions");

        // Mode 3: SANDBOX_FULL — readable <= 2.5x cruise with full-fidelity diorama
        var sandboxOpts = ReplayExportContentOptions.defaultsFor(ExportMode.SANDBOX_FULL);
        var sandboxFilm = ReplayCinemaDirector.direct(sim.replay, sim.voice, sandboxOpts);
        assertTrue(sandboxFilm.maxCruiseSpeed() <= ReplayCinemaDirector.MAX_SANDBOX_CRUISE_SPEED + 1e-6);

        // Mode 4: CINEMATIC_PROMO — cruise STRICTLY <= 3.0x (both Global Ensemble and Personal MVP)
        var promoGlobal = ReplayCinemaDirector.direct(sim.replay, sim.voice,
                ReplayExportContentOptions.defaultsFor(ExportMode.CINEMATIC_PROMO));
        var promoMvp = ReplayCinemaDirector.direct(sim.replay, sim.voice,
                ReplayExportContentOptions.builder(ExportMode.CINEMATIC_PROMO)
                        .promoScope(PromoScope.PERSONAL_MVP).focusPlayer(starKiller).build());

        for (var film : List.of(promoGlobal, promoMvp)) {
            assertTrue(film.maxCruiseSpeed() <= ReplayCinemaDirector.MAX_PROMO_CRUISE_SPEED + 1e-6,
                    "Promo mode cruise speed must be strictly <= 3.0x, got " + film.maxCruiseSpeed());
            for (var seg : film.segments()) {
                if (seg.kind() == ReplayCinemaDirector.SegmentKind.CRUISE) {
                    assertTrue(seg.startRate() <= ReplayCinemaDirector.MAX_PROMO_CRUISE_SPEED + 1e-6
                                    && seg.endRate() <= ReplayCinemaDirector.MAX_PROMO_CRUISE_SPEED + 1e-6,
                            "Cruise segment exceeded 3.0x cap: " + seg);
                }
            }
        }
    }

    @Test
    void fortyPlayerTwentyMinuteMatchProducesValidFiveTierHighlightPyramidAndChoreography() {
        SimulatedMatch sim = build40Player20MinuteMatch();
        var quota = ReplayHighlightScorer.PyramidQuota.forMatch(24_000, 40, false);
        assertEquals(ReplayHighlightScorer.PyramidQuota.standard40p20m(), quota);

        var highlights = ReplayHighlightScorer.scoreAndAllocate(sim.replay, sim.voice, null, 0, 24_000);
        assertFalse(highlights.isEmpty());

        EnumMap<HighlightTier, Integer> counts = new EnumMap<>(HighlightTier.class);
        for (HighlightTier t : HighlightTier.values()) counts.put(t, 0);
        for (var h : highlights) counts.merge(h.tier(), 1, Integer::sum);

        // Strict 5-tier rarity pyramid quotas: T5 <= 1, T4 <= 2, T3 <= 3, T2 <= 6, T1 <= 14
        assertTrue(counts.get(HighlightTier.TIER_5_APOTHEOSIS) <= 1, "T5 quota exceeded: " + counts);
        assertTrue(counts.get(HighlightTier.TIER_4_DUAL_VISION) <= 2, "T4 quota exceeded: " + counts);
        assertTrue(counts.get(HighlightTier.TIER_3_SHADOW_SLASH) <= 3, "T3 quota exceeded: " + counts);
        assertTrue(counts.get(HighlightTier.TIER_2_CLOSEUP) <= 6, "T2 quota exceeded: " + counts);
        assertTrue(counts.get(HighlightTier.TIER_1_GLIMMER) <= 14, "T1 quota exceeded: " + counts);
        assertTrue(highlights.size() >= 10, "40p 20m action-packed match should select >= 10 multi-tier highlights, got " + counts);
        assertTrue(counts.get(HighlightTier.TIER_5_APOTHEOSIS) == 1,
                "Legendary clutch multi-kill final blow should earn Tier 5 Apotheosis, counts=" + counts);

        // Verify Cinema Director emits the signature Tier 1/3/4/5 choreography & Tier 5 music suction duck
        var promo = ReplayCinemaDirector.direct(sim.replay, sim.voice,
                ReplayExportContentOptions.defaultsFor(ExportMode.CINEMATIC_PROMO));
        boolean hasT1 = promo.segments().stream().anyMatch(s -> s.kind() == ReplayCinemaDirector.SegmentKind.TIER1_GLIMMER
                && Math.abs(s.actorHighlightScale() - ReplayCinemaDirector.TIER1_HIGHLIGHT_SCALE) < 0.05
                && Math.abs(s.backgroundDimAlpha() - ReplayCinemaDirector.TIER1_DIM_OTHERS_ALPHA) < 0.05);
        boolean hasT3 = promo.segments().stream().anyMatch(s -> s.kind() == ReplayCinemaDirector.SegmentKind.TIER3_HERO_SIDE
                && s.whipAngleDeg() >= 90.0 && s.whipAngleDeg() <= 116.0);
        boolean hasT4 = promo.segments().stream().anyMatch(s -> s.kind() == ReplayCinemaDirector.SegmentKind.TIER4_POV_EXPAND);
        boolean hasT5Suction = promo.segments().stream().anyMatch(s -> s.kind() == ReplayCinemaDirector.SegmentKind.TIER5_SUCTION_DOLLY);
        boolean hasT5Pov = promo.segments().stream().anyMatch(s -> s.kind() == ReplayCinemaDirector.SegmentKind.TIER5_POV_NATIVE);
        boolean hasT5Apex = promo.segments().stream().anyMatch(s -> s.kind() == ReplayCinemaDirector.SegmentKind.TIER5_APEX_FREEZE);
        boolean hasT5Encore = promo.segments().stream().anyMatch(s -> s.kind() == ReplayCinemaDirector.SegmentKind.TIER5_ORBIT_ENCORE
                && s.instantReplay());

        assertTrue(hasT1, "Expected Tier 1 Glimmer segment with 1.65x highlight and 42% dim");
        assertTrue(hasT3, "Expected Tier 3 Hero Side whip segment (90..115 deg)");
        assertTrue(hasT4, "Expected Tier 4 sliding dual-vision POV expand segment");
        assertTrue(hasT5Suction && hasT5Pov && hasT5Apex && hasT5Encore,
                "Expected complete 4-phase Tier 5 Apotheosis choreography (Suction -> 1x POV -> Apex Freeze -> 360 Encore)");
        assertFalse(promo.directorDucks().isEmpty(), "Tier 5 Apotheosis must schedule pre-drop music suction duck");
    }

    @Test
    void wsolaPreservesFundamentalPitchAndSyllableEnergyAcrossSpeedsAndVariableRamps() {
        int sr = 48_000;
        double targetFreq = 320.0; // Natural human vocal fundamental
        short[] speech = new short[sr * 2]; // 2.0 seconds
        for (int i = 0; i < speech.length; i++) {
            double t = i / (double) sr;
            // Amplitude-modulated vocal harmonic signal (simulating 4 distinct syllables)
            double syllEnv = 0.35 + 0.65 * Math.pow(Math.sin(Math.PI * 2.0 * t), 2.0);
            double wave = 0.72 * Math.sin(2.0 * Math.PI * targetFreq * t)
                    + 0.22 * Math.sin(2.0 * Math.PI * (targetFreq * 2.0) * t);
            speech[i] = (short) Math.round(syllEnv * wave * 18_000.0);
        }
        double baseRms = rms(speech, sr / 10, speech.length - sr / 10);

        for (double speed : new double[]{0.5, 2.0, 4.0}) {
            short[] stretched = ReplayWsolaTimeStretch.stretchMono(speech, sr, speed);
            int expectedLen = (int) Math.round(speech.length / speed);
            assertEquals(expectedLen, stretched.length, 4.0, "Duration mismatch at speed=" + speed);

            // Pitch preservation via zero-crossing / autocorrelation fundamental estimate
            double estFreq = estimateFundamentalAutocorr(stretched, sr, 150.0, 600.0);
            assertEquals(targetFreq, estFreq, targetFreq * 0.06,
                    "WSOLA must preserve vocal pitch (~" + targetFreq + " Hz) at speed=" + speed + ", got " + estFreq);

            // Syllable energy preservation (no swallowed/zeroed speech)
            double stretchedRms = rms(stretched, stretched.length / 10, stretched.length * 9 / 10);
            assertTrue(stretchedRms > baseRms * 0.55,
                    "WSOLA must retain vocal energy without swallowing syllables at speed=" + speed
                            + " (base=" + baseRms + ", out=" + stretchedRms + ")");
        }

        // Variable-rate slow-motion ramp (1.0x -> 0.4x -> 1.0x) produces smooth non-silent output
        int varOutLen = sr * 3;
        short[] ramped = ReplayWsolaTimeStretch.stretchVariableRate(speech, sr, varOutLen,
                outIdx -> (outIdx / (double) varOutLen) * speech.length);
        assertEquals(varOutLen, ramped.length);
        assertTrue(rms(ramped, sr / 4, varOutLen - sr / 4) > baseRms * 0.45);
    }

    @Test
    void twentyMinuteStreamingMixAvoidsMonolithic230MbAllocationAndMixesConcurrentVoicesWithAutoDucking() throws Exception {
        // 20 minutes @ 48 kHz stereo 16-bit = 57,600,000 stereo frames = 230,400,000 raw bytes!
        long twentyMinFrames = 20L * 60L * ReplayStreamingAudioMixer.SAMPLE_RATE;

        short[] voiceWave = new short[48_000]; // 1.0 s @ 48 kHz
        for (int i = 0; i < voiceWave.length; i++) {
            voiceWave[i] = (short) Math.round(16_000 * Math.sin(2.0 * Math.PI * 300.0 * i / 48_000.0));
        }

        // Schedule 3 concurrent speakers at t = 2.0s (testing concurrent mixing + Auto-Ducking)
        // plus one late speaker at 19m58s (testing end-of-match streaming accuracy)
        List<ReplayStreamingAudioMixer.VoiceSegment> voices = List.of(
                new ReplayStreamingAudioMixer.VoiceSegment(UUID.randomUUID(), 2.0, 3.0, 0.0, 1.0, 0.9f, -0.5f, false, () -> voiceWave),
                new ReplayStreamingAudioMixer.VoiceSegment(UUID.randomUUID(), 2.0, 3.0, 0.0, 1.0, 0.9f, 0.5f, false, () -> voiceWave),
                new ReplayStreamingAudioMixer.VoiceSegment(UUID.randomUUID(), 2.2, 3.2, 0.0, 2.0, 0.8f, 0.0f, true, () -> voiceWave),
                new ReplayStreamingAudioMixer.VoiceSegment(UUID.randomUUID(), 1198.0, 1199.0, 0.0, 1.0, 1.0f, 0.0f, false, () -> voiceWave)
        );
        var plan = new ReplayStreamingAudioMixer.MixPlan(
                twentyMinFrames, List.of(), voices,
                List.of(new ReplayStreamingAudioMixer.DirectorDuck(10.0, 12.0, 0.10f, 0.1, 0.1)),
                1.0f, 1.0f, 0.8f, true, ReplayStreamingAudioMixer.DEFAULT_MASTER_GAIN);

        AtomicLong byteTotal = new AtomicLong();
        AtomicInteger maxChunkBytes = new AtomicInteger();
        double[] chunkRms = new double[1200];
        AtomicInteger chunkIdx = new AtomicInteger();

        // Constant low music bed so we can verify Auto-Ducking and DirectorDuck
        ReplayStreamingAudioMixer.MusicChunkMixer steadyMusic = (outL, outR, offset, count) -> {
            for (int i = 0; i < count; i++) {
                float v = (float) (0.35 * Math.sin(2.0 * Math.PI * 220.0 * (offset + i) / 48_000.0));
                outL[i] += v;
                outR[i] += v;
            }
        };

        ReplayStreamingAudioMixer.MixStats stats = ReplayStreamingAudioMixer.mixToSink(plan, null, steadyMusic, (bytes, len) -> {
            byteTotal.addAndGet(len);
            maxChunkBytes.accumulateAndGet(len, Math::max);
            int idx = chunkIdx.getAndIncrement();
            if (idx < chunkRms.length) {
                chunkRms[idx] = pcmBytesRms(bytes, len);
            }
        });

        assertEquals(twentyMinFrames, stats.totalStereoFrames());
        assertEquals(twentyMinFrames * 4L, stats.totalBytesWritten());
        assertEquals(twentyMinFrames * 4L, byteTotal.get());
        assertEquals(1200, stats.chunksProcessed());
        assertEquals(3, stats.maxConcurrentVoices(), "Must track 3 concurrent speakers without serial truncation");
        // Defect #3 fix: scratch buffers stay tiny (~1.34 MiB), nowhere near 230 MiB!
        assertTrue(stats.peakScratchBufferBytes() < 2L * 1024L * 1024L,
                "Peak scratch buffer must stay < 2 MiB for 20-minute mix, got " + stats.peakScratchBufferBytes());
        assertEquals(ReplayStreamingAudioMixer.CHUNK_FRAMES * 4, maxChunkBytes.get());
        // DirectorDuck at 10..12s sharply lowers music bed compared to normal music at 5..6s
        assertTrue(chunkRms[11] < chunkRms[5] * 0.40,
                "DirectorDuck should strongly attenuate music at 11s (rms[11]=" + chunkRms[11] + ", rms[5]=" + chunkRms[5] + ")");
        // Late voice at 1198s raises energy clearly above bare music bed
        assertTrue(chunkRms[1198] > chunkRms[5] * 1.35,
                "Late voice at 19m58s must mix cleanly at end of 20-minute stream");
    }

    @Test
    void freeformContentCustomizerSupportsAllFourteenTogglesAntiSpoilerAndTrimWindow() throws Exception {
        SimulatedMatch sim = build40Player20MinuteMatch();
        UUID focus = sim.killers.getFirst();
        UUID bystander = sim.passengers.getFirst();

        // Exercise every toggle in the 4-category 14-control customizer
        ReplayExportContentOptions stripped = ReplayExportContentOptions.builder(ExportMode.SHORT)
                .focusPlayer(focus)
                .roofCutaway(false)
                // Category 1 (6): disable all structure & cinematic framing
                .includeTitle(false).includeReview(false).includeVictoryMvp(false)
                .includeOutro(false).includeLetterboxDof(false).includeWatermark(false)
                // Category 2 (6 + skill toasts + split toggle): HUD & anti-spoiler filters
                .showTimecode(false).showAliveMatrix(false).showKillFeed(false).showTimeline(false)
                .identityReveal(IdentityReveal.SUSPENSE_FOG)
                .trailFilter(ActorOverlayFilter.FOCUS_ONLY)
                .nameplateFilter(ActorOverlayFilter.FOCUS_AND_COMBAT)
                .showSkillToasts(false).splitScreenKillPov(false)
                // Category 3: audio sliders + music + auto-ducking
                .voiceVolume(0.0f).sfxVolume(1.5f).musicLevel(ReplayMusicPlan.Level.OFF).autoDucking(false)
                // Category 4: clip trim window [2000..4000]
                .trimRange(2000, 4000)
                .build();

        assertFalse(stripped.voiceEnabled());
        assertTrue(stripped.sfxEnabled());
        assertFalse(stripped.musicEnabled());
        assertTrue(stripped.trailFilter().matches(focus, focus, false));
        assertFalse(stripped.trailFilter().matches(bystander, focus, true));
        assertTrue(stripped.nameplateFilter().matches(bystander, focus, true));
        assertFalse(stripped.nameplateFilter().matches(bystander, focus, false));

        // Suspense fog conceals non-focus player role before first reveal tick, then reveals it after
        assertTrue(stripped.isRoleRevealedAt(focus, 0.0, 500));
        assertFalse(stripped.isRoleRevealedAt(bystander, 100.0, 500));
        assertTrue(stripped.isRoleRevealedAt(bystander, 500.0, 500));

        var hiddenOpts = stripped.toBuilder().identityReveal(IdentityReveal.HIDDEN).build();
        assertFalse(hiddenOpts.isRoleRevealedAt(bystander, 9999.0, 500));

        var film = ReplayCinemaDirector.direct(sim.replay, sim.voice, stripped);
        assertEquals(0.0, film.actLength(ReplayVideoScript.Act.TITLE), 1e-9);
        assertEquals(0.0, film.actLength(ReplayVideoScript.Act.REVIEW), 1e-9);
        assertEquals(0.0, film.actLength(ReplayVideoScript.Act.VICTORY), 1e-9);
        assertEquals(0.0, film.actLength(ReplayVideoScript.Act.MVP), 1e-9);
        assertEquals(0.0, film.actLength(ReplayVideoScript.Act.OUTRO), 1e-9);
        assertEquals(2000, film.trimStartTick());
        assertEquals(4000, film.trimEndTick());
        assertTrue(film.segments().stream().noneMatch(ReplayCinemaDirector.DirectedSegment::splitScreen),
                "Disabling splitScreenKillPov must suppress split-screen segments");

        // Audio mix plan with voiceVolume=0 & no SFX resolver skips all voice decoding and mixes exact silence
        var mixPlan = ReplayCinemaDirector.buildAudioMixPlan(film, sim.voice, film.frames(30), 30, rl -> false);
        assertEquals(0.0f, mixPlan.voiceVolume(), 1e-6f);
        assertEquals(1.5f, mixPlan.sfxVolume(), 1e-6f);
        assertFalse(mixPlan.autoDucking());
        AtomicLong nonZeroBytes = new AtomicLong();
        var mutedStats = ReplayStreamingAudioMixer.mixToSink(mixPlan, null, null, (bytes, len) -> {
            for (int i = 0; i < len; i++) if (bytes[i] != 0) nonZeroBytes.incrementAndGet();
        });
        assertEquals(0, mutedStats.maxConcurrentVoices(), "Muted voice slider (0.0) must never decode/activate voices");
        assertEquals(0L, nonZeroBytes.get(), "Muted voice + no SFX + music OFF must mix pure digital silence");
    }

    @Test
    void unknownModSkillThreeLayerFallbackRegistryToastsAndFlashIntensity() {
        UUID caster = UUID.randomUUID();
        UUID victim = UUID.randomUUID();

        // Known native adapters are recognized; unknown third-party mod skills fall through to 3-layer fallback
        var knownKnife = new ReplaySkillEffectRegistry.SkillSignal(
                1, 100, "starrailexpress:knife_stab", "Knife", Kind.KILL, caster, victim,
                "starrailexpress:killer", 0xFFFF3344, true, 10, 2, 10, true, 11, 2, 10, 20, 0, false);
        var knownDrone = new ReplaySkillEffectRegistry.SkillSignal(
                2, 110, "habitrain_core:drone_scan", "Drone Scan", null, caster, null,
                "habitrain_core:drone_operator", 0xFF38BDF8, true, 10, 2, 10, false, 0, 0, 0, 30, 0, false);
        var unknownThirdParty = new ReplaySkillEffectRegistry.SkillSignal(
                3, 120, "mystery_addon:void_singularity", "虚空奇点", null, caster, victim,
                "mystery_addon:void_mage", 0xFF9966FF, true, 10, 2, 10, true, 16, 2, 12, 40, 0, false);

        assertTrue(ReplaySkillEffectRegistry.hasNativeAdapter(knownKnife));
        assertTrue(ReplaySkillEffectRegistry.hasNativeAdapter(knownDrone));
        assertFalse(ReplaySkillEffectRegistry.hasNativeAdapter(unknownThirdParty),
                "Unknown third-party skill must NOT match a native adapter so 3-layer fallback fires");

        // Build replay with both unknown third-party skill event and flashbang event
        var flashEvent = new ReplaySkillEvent(10, 150, "noellesroles:flash_grenade", "闪光弹",
                ReplaySkillEvent.CAT_ITEM_USE, caster, victim, "starrailexpress:killer", 0xFFFFFFFF,
                Position.quantize(10, 2, 10), Position.quantize(11, 2, 10), 40, "noellesroles:flash", false);
        var unknownEvent = new ReplaySkillEvent(11, 120, "mystery_addon:void_singularity", "虚空奇点",
                ReplaySkillEvent.CAT_ROLE_SKILL, caster, victim, "mystery_addon:void_mage", 0xFF9966FF,
                Position.quantize(10, 2, 10), Position.quantize(16, 2, 12), 40, "mystery_addon:cast", true);

        List<Participant> people = List.of(
                new Participant(caster, "VoidCaster", "mystery_addon:void_mage", 0xFF9966FF,
                        "mystery_addon:void_mage", 0xFF9966FF, MatchWinFaction.NEUTRAL, true, 60, 0, 10, 1,
                        List.of(new Sample(0, Position.quantize(10, 2, 10), (byte) 0, ALIVE),
                                new Sample(300, Position.quantize(10, 2, 10), (byte) 0, ALIVE))),
                new Participant(victim, "TargetBob", "starrailexpress:civilian", 0xFF44AAFF,
                        "starrailexpress:civilian", 0xFF44AAFF, MatchWinFaction.PASSENGER, true, 60, 0, 10, 0,
                        List.of(new Sample(0, Position.quantize(11, 2, 10), (byte) 0, ALIVE),
                                new Sample(300, Position.quantize(11, 2, 10), (byte) 0, ALIVE)))
        );
        LastMatchReplay replay = new LastMatchReplay(UUID.randomUUID(), 0, 300, "Express", "murder", "",
                MatchWinKind.CUSTOM, "", "", "", 0, new SceneBounds(0, 0, 0, 40, 16, 24), null,
                people, List.of(), List.of(), caster, 0, false)
                .withWorldDynamics(List.of(), List.of(), List.of(unknownEvent, flashEvent), ReplayGameSoundData.EMPTY);
        ReplayBoardModel model = new ReplayBoardModel(replay, new ReplayTimeline(replay));

        // Tactical Action Toast appears during active window [120 .. 192] and expires afterwards
        assertTrue(ReplaySkillFallbackHud.collectActive(model, 110.0).isEmpty());
        var activeToasts = ReplaySkillFallbackHud.collectActive(model, 130.0);
        assertEquals(1, activeToasts.size(), "Unknown fallback skill should generate 1 tactical toast");
        assertEquals("VoidCaster", activeToasts.getFirst().actorName());
        assertEquals("虚空奇点", activeToasts.getFirst().skillName());
        assertTrue(activeToasts.getFirst().visibility() > 0.5);
        assertTrue(ReplaySkillFallbackHud.collectActive(model, 210.0).isEmpty(), "Expired toast must disappear");

        // Flashbang intensity spikes right after tick 150 for nearby victim and decays to 0 later
        assertEquals(0f, ReplaySkillEffectRegistry.flashIntensityFor(victim, model, 140.0), 1e-4f);
        assertTrue(ReplaySkillEffectRegistry.flashIntensityFor(victim, model, 153.0) > 0.6f);
        assertEquals(0f, ReplaySkillEffectRegistry.flashIntensityFor(victim, model, 220.0), 1e-4f);
    }

    @Test
    void buildAudioMixPlanDeduplicatesCrossSegmentAndLegacyImpactSfx() {
        // Regression guard for #33/#34: legacy v1 replays (gameSounds empty) must NOT triple-fire
        // kill SFX across APPROACH -> FREEZE -> AFTERMATH_SLOW, and v2 boundary sounds must fire once.
        UUID killer = new UUID(0x101L, 1L);
        UUID victim = new UUID(0x202L, 2L);
        SceneBounds bounds = new SceneBounds(0, 0, 0, 24, 12, 24);
        List<Sample> kTrack = new ArrayList<>();
        List<Sample> vTrack = new ArrayList<>();
        for (int t = 0; t <= 200; t += 5) {
            kTrack.add(new Sample(t, Position.quantize(4 + t * 0.03, 1, 8), (byte) 0, ALIVE));
            vTrack.add(new Sample(t, Position.quantize(6, 1, 8), (byte) 64, t < 80 ? ALIVE : 0));
        }
        Position pShot = Position.quantize(5.5, 1, 8);
        Position pKill = Position.quantize(6.0, 1, 8);
        List<Node> events = List.of(
                new Node(1, 79, Kind.GUNSHOT, killer, victim, pShot, pKill, "starrailexpress:revolver", HIT),
                new Node(2, 80, Kind.KILL, killer, victim, pShot, pKill, "starrailexpress:revolver", HIT)
        );
        List<Participant> people = List.of(
                new Participant(killer, "K", "killer", 0xFFCC3333, "killer", 0xFFCC3333, MatchWinFaction.KILLER, true, 10, 1, 100, 1, kTrack),
                new Participant(victim, "V", "civilian", 0xFF44AAEE, "civilian", 0xFF44AAEE, MatchWinFaction.PASSENGER, false, 4, 0, 10, 0, vTrack)
        );
        // Case 1: v1 legacy archive (empty gameSounds)
        LastMatchReplay v1 = new LastMatchReplay(UUID.randomUUID(), 0L, 200, "LegacyCar", "classic", "KILLERS",
                MatchWinKind.KILLER, "", "", "", 0xFFCC3333, bounds, null, people, events, List.of(), killer, 1, false);
        var opts = ReplayExportContentOptions.defaultsFor(ExportMode.SHORT).toBuilder()
                .includeTitle(false).includeVictoryMvp(false).musicLevel(ReplayMusicPlan.Level.OFF).build();
        var v1Film = ReplayCinemaDirector.direct(v1, ReplayVoiceData.EMPTY, opts);
        var v1Plan = ReplayCinemaDirector.buildAudioMixPlan(v1Film, ReplayVoiceData.EMPTY, v1Film.frames(30), 30, id -> false);
        long revolverCount = v1Plan.sfxEvents().stream()
                .filter(ev -> ev.keys().stream().anyMatch(k -> k.contains("revolver")))
                .count();
        assertEquals(1L, revolverCount, "Legacy v1 kill gunshot must fire exactly once (no APPROACH/FREEZE/AFTERMATH triple-fire)");

        // Case 2: v2 archive with server-recorded gameSound landing exactly on segment boundary tick 80 (4000 ms)
        ReplayGameSoundData v2Sounds = new ReplayGameSoundData(
                List.of("starrailexpress:item.revolver.shoot"),
                List.of(new ReplayGameSoundData.Cue(80 * 50, 0, ReplayGameSoundData.CAT_PLAYERS,
                        pShot.x(), pShot.y(), pShot.z(), 180, 96, killer, ReplayGameSoundData.AudibilityScope.WORLD_3D)));
        LastMatchReplay v2 = v1.withWorldDynamics(List.of(), List.of(), List.of(), v2Sounds);
        var v2Film = ReplayCinemaDirector.direct(v2, ReplayVoiceData.EMPTY, opts);
        var v2Plan = ReplayCinemaDirector.buildAudioMixPlan(v2Film, ReplayVoiceData.EMPTY, v2Film.frames(30), 30, id -> false);
        long v2RevolverCount = v2Plan.sfxEvents().stream()
                .filter(ev -> ev.keys().contains("starrailexpress:item.revolver.shoot"))
                .count();
        assertEquals(1L, v2RevolverCount, "Server-recorded boundary SFX at tick 80 must not double-fire across adjacent segments");

        // Regression guard for #35/#36: Short mode must keep two kills separated by 100 ticks (5 s)
        // without Promo-tier 110..200 tick NMS suppressing the earlier kill.
        UUID victim2 = new UUID(0x303L, 3L);
        List<Sample> v2Track = new ArrayList<>();
        for (int t = 0; t <= 240; t += 5) {
            v2Track.add(new Sample(t, Position.quantize(10, 1, 8), (byte) 128, t < 160 ? ALIVE : 0));
        }
        List<Participant> threePeople = List.of(
                people.get(0),
                people.get(1),
                new Participant(victim2, "V2", "civilian", 0xFF44AAEE, "civilian", 0xFF44AAEE, MatchWinFaction.PASSENGER, false, 8, 0, 10, 0, v2Track));
        List<Node> twoKills = List.of(
                new Node(1, 59, Kind.GUNSHOT, killer, victim, pShot, pKill, "starrailexpress:revolver", HIT),
                new Node(2, 60, Kind.KILL, killer, victim, pShot, pKill, "starrailexpress:revolver", HIT),
                new Node(3, 160, Kind.KILL, killer, victim2, Position.quantize(10, 1, 8), Position.quantize(10, 1, 8), "starrailexpress:knife_stab", HIT));
        LastMatchReplay twoKillReplay = new LastMatchReplay(UUID.randomUUID(), 0L, 240, "ShortNmsCar", "classic", "KILLERS",
                MatchWinKind.KILLER, "", "", "", 0xFFCC3333, bounds, null, threePeople, twoKills, List.of(), killer, 2, false);
        var shortFilm = ReplayCinemaDirector.direct(twoKillReplay, ReplayVoiceData.EMPTY,
                ReplayExportContentOptions.defaultsFor(ExportMode.SHORT));
        assertEquals(2, shortFilm.beats().size(), "Short mode must retain both kills spaced 100 ticks (5s) apart");
    }

    @Test
    void cinemaDirectorAndStreamingMixerHaveNoClientBytecodeLinks() throws Exception {
        String pkg = "com/habitrain/core/";
        for (String name : List.of("scene/replay/ReplayCinemaDirector", "scene/replay/ReplayStreamingAudioMixer")) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(pkg + name + ".class")) {
                assertNotNull(stream, "Missing class " + name);
                String cp = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of("net/minecraft/client/", "com/mojang/blaze3d/",
                        "net/fabricmc/fabric/api/client/", pkg + "client/", pkg + "scene/client/")) {
                    assertFalse(cp.contains(forbidden), name + " must stay server/common-safe, found " + forbidden);
                }
            }
        }
    }

    // ===== Synthetic 40-player 20-minute match builder =====

    private record SimulatedMatch(LastMatchReplay replay, ReplayVoiceData voice, List<UUID> killers, List<UUID> passengers) {}

    private static SimulatedMatch build40Player20MinuteMatch() {
        int duration = 24_000; // 20 minutes @ 20 TPS
        SceneBounds bounds = new SceneBounds(0, 0, 0, 120, 16, 32);
        List<UUID> killers = new ArrayList<>();
        List<UUID> passengers = new ArrayList<>();
        List<Participant> people = new ArrayList<>();
        List<Node> events = new ArrayList<>();
        List<VoiceUtterance> utterances = new ArrayList<>();
        List<String> soundDict = List.of("starrailexpress:item.revolver.shoot", "starrailexpress:item.knife.stab", "noellesroles:grenade.explode");
        List<ReplayGameSoundData.Cue> soundCues = new ArrayList<>();

        for (int i = 0; i < 6; i++) killers.add(new UUID(0xAA00L + i, 1L));
        for (int i = 0; i < 34; i++) passengers.add(new UUID(0xBB00L + i, 2L));

        Map<UUID, Integer> deathTick = new HashMap<>();
        int eventId = 1;

        // Spread 26 varied combat events across 20 minutes (including early first blood, mid-match duels,
        // grenade blasts, revivals, and a legendary 3-kill clutch final blow at tick 23,400!)
        int victimCursor = 0;
        for (int e = 0; e < 22; e++) {
            int tick = 600 + e * 960;
            UUID killer = killers.get(e % killers.size());
            UUID victim = passengers.get(victimCursor++);
            deathTick.put(victim, tick);
            Position kPos = Position.quantize(10 + (e * 4) % 95, 2, 10 + (e % 3) * 4);
            Position vPos = Position.quantize(13 + (e * 4) % 95, 2, 10 + (e % 3) * 4);
            if (e % 3 == 0) {
                events.add(new Node(eventId++, tick - 2, Kind.GUNSHOT, killer, victim, kPos, vPos, "starrailexpress:revolver", HIT));
                soundCues.add(new ReplayGameSoundData.Cue(tick * 50, 0, ReplayGameSoundData.CAT_PLAYERS,
                        kPos.x(), kPos.y(), kPos.z(), 128, 96, killer, ReplayGameSoundData.AudibilityScope.WORLD_3D));
            }
            events.add(new Node(eventId++, tick, Kind.KILL, killer, victim, kPos, vPos,
                    e % 2 == 0 ? "starrailexpress:revolver" : "starrailexpress:knife_stab", HIT));
            if (e % 5 == 2) {
                events.add(new Node(eventId++, tick + 10, Kind.GRENADE_EXPLOSION, killer, null, kPos, vPos, "noellesroles:grenade", SUCCESS));
                soundCues.add(new ReplayGameSoundData.Cue((tick + 10) * 50, 2, ReplayGameSoundData.CAT_BLOCKS,
                        vPos.x(), vPos.y(), vPos.z(), 180, 96, killer, ReplayGameSoundData.AudibilityScope.WORLD_3D));
            }
            if (e == 8) {
                events.add(new Node(eventId++, tick + 80, Kind.PLAYER_REVIVAL, passengers.get(30), victim, vPos, vPos, "revive", SUCCESS));
                deathTick.remove(victim);
            }
        }

        // Legendary Tier 5 Apotheosis climax: star killer pulls a rapid 3-kill clutch combo with blackout + 6 simultaneous screaming voices!
        UUID star = killers.getFirst();
        int climaxTick = 23_400;
        Position cPos = Position.quantize(60, 2, 16);
        for (int k = 0; k < 3; k++) {
            UUID v = passengers.get(victimCursor++);
            int t = climaxTick + k * 14;
            deathTick.put(v, t);
            Position vp = Position.quantize(62 + k * 3, 2, 16);
            events.add(new Node(eventId++, t - 2, Kind.GUNSHOT, star, v, cPos, vp, "starrailexpress:sniper_rifle", HIT));
            events.add(new Node(eventId++, t, Kind.KILL, star, v, cPos, vp, "starrailexpress:sniper_rifle", HIT));
            soundCues.add(new ReplayGameSoundData.Cue(t * 50, 0, ReplayGameSoundData.CAT_PLAYERS,
                    cPos.x(), cPos.y(), cPos.z(), 200, 96, star, ReplayGameSoundData.AudibilityScope.WORLD_3D));
        }

        // High acoustic tension voices around climax and active mid-match skirmishes (realistic 40p voice activity)
        short[] loud24k = new short[ReplayVoiceData.SAMPLE_RATE]; // 1.0s
        for (int i = 0; i < loud24k.length; i++) {
            loud24k[i] = (short) Math.round(20_000 * Math.sin(2.0 * Math.PI * 360.0 * i / ReplayVoiceData.SAMPLE_RATE));
        }
        byte[] loudAdpcm = ReplayVoiceData.encodeAdpcm(loud24k);
        for (int s = 0; s < 6; s++) {
            utterances.add(new VoiceUtterance(killers.get(s), climaxTick * 50L - 300L + s * 40L,
                    loud24k.length, 48f, ChannelMode.NORMAL_OPEN, null, loudAdpcm, 0.85f));
        }
        // Add multi-speaker vocal tension & shield-break combos to several mid-match engagements so Tiers 1..5 all populate
        for (int skirmish : new int[]{0, 3, 6, 9, 12, 15, 18}) {
            int tick = 600 + skirmish * 960;
            int speakers = (skirmish % 2 == 0) ? 4 : 3;
            for (int s = 0; s < speakers; s++) {
                utterances.add(new VoiceUtterance(killers.get(s % killers.size()), tick * 50L - 250L + s * 30L,
                        loud24k.length, 48f, ChannelMode.NORMAL_OPEN, null, loudAdpcm, 0.78f));
            }
            Position p = Position.quantize(12 + (skirmish * 4) % 95, 2, 12);
            events.add(new Node(eventId++, tick - 4, Kind.SHIELD_BROKEN, killers.get(skirmish % killers.size()),
                    passengers.get(skirmish), p, p, "shield", SUCCESS));
            soundCues.add(new ReplayGameSoundData.Cue(tick * 50 - 100, 1, ReplayGameSoundData.CAT_PLAYERS,
                    p.x(), p.y(), p.z(), 170, 96, killers.get(skirmish % killers.size()), ReplayGameSoundData.AudibilityScope.WORLD_3D));
        }

        for (int i = 0; i < killers.size(); i++) {
            UUID id = killers.get(i);
            people.add(new Participant(id, "Killer" + i, "starrailexpress:killer", 0xFFFF4A52,
                    "starrailexpress:killer", 0xFFFF4A52, MatchWinFaction.KILLER, true, 1200, i == 0 ? 7 : 3, 120 - i * 10, 4,
                    List.of(new Sample(0, Position.quantize(10 + i * 5, 2, 12), (byte) 0, ALIVE),
                            new Sample(climaxTick, Position.quantize(60, 2, 16), (byte) 0, ALIVE),
                            new Sample(duration, Position.quantize(65, 2, 16), (byte) 0, ALIVE))));
        }
        for (int i = 0; i < passengers.size(); i++) {
            UUID id = passengers.get(i);
            Integer dt = deathTick.get(id);
            List<Sample> tr = new ArrayList<>();
            tr.add(new Sample(0, Position.quantize(12 + (i * 3) % 90, 2, 16), (byte) 0, ALIVE));
            if (dt != null) {
                tr.add(new Sample(Math.max(1, dt - 1), Position.quantize(60, 2, 16), (byte) 0, ALIVE));
                tr.add(new Sample(dt, Position.quantize(60, 2, 16), (byte) 0, (byte) 0));
            } else {
                tr.add(new Sample(duration, Position.quantize(62, 2, 16), (byte) 0, ALIVE));
            }
            people.add(new Participant(id, "Pass" + i, "starrailexpress:civilian", 0xFF7FD1FF,
                    "starrailexpress:civilian", 0xFF7FD1FF, MatchWinFaction.PASSENGER, false,
                    dt == null ? 1200 : dt / 20, 0, 20, 1, tr));
        }

        List<Span> spans = List.of(
                new Span(SpanKind.BLACKOUT, null, 8000, 8600),
                new Span(SpanKind.BLACKOUT, null, climaxTick - 200, climaxTick + 100)
        );
        LastMatchReplay replay = new LastMatchReplay(UUID.randomUUID(), 1000L, duration, "Star Express", "murder",
                "KILLERS", MatchWinKind.KILLER, "", "", "", 0xFFFF4A52, bounds, null,
                people, events, spans, star, 25, false)
                .withGameSounds(new ReplayGameSoundData(soundDict, soundCues));
        ReplayVoiceData voice = ReplayVoiceData.ofV2(utterances, VoiceTimelineMeta.EMPTY);
        return new SimulatedMatch(replay, voice, killers, passengers);
    }

    private static double rms(short[] s, int from, int to) {
        int a = Math.max(0, from), b = Math.min(s.length, to);
        if (b <= a) return 0.0;
        double sum = 0.0;
        for (int i = a; i < b; i++) sum += (double) s[i] * s[i];
        return Math.sqrt(sum / (b - a));
    }

    private static double pcmBytesRms(byte[] bytes, int len) {
        int samples = len / 2;
        if (samples <= 0) return 0.0;
        ByteBuffer bb = ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        double sum = 0.0;
        for (int i = 0; i < samples; i++) {
            short s = bb.getShort();
            sum += (double) s * s;
        }
        return Math.sqrt(sum / samples);
    }

    private static double estimateFundamentalAutocorr(short[] pcm, int sr, double minHz, double maxHz) {
        int start = pcm.length / 4;
        int win = Math.min(4096, pcm.length / 2);
        int minLag = Math.max(2, (int) Math.floor(sr / maxHz));
        int maxLag = Math.min(win / 2, (int) Math.ceil(sr / minHz));
        int bestLag = minLag;
        double bestCorr = Double.NEGATIVE_INFINITY;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double dot = 0.0;
            for (int i = 0; i < win - lag; i++) {
                dot += (double) pcm[start + i] * pcm[start + i + lag];
            }
            if (dot > bestCorr) {
                bestCorr = dot;
                bestLag = lag;
            }
        }
        return sr / (double) bestLag;
    }
}
