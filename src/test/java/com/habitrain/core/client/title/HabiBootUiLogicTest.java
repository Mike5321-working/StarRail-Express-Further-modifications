package com.habitrain.core.client.title;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HabiBootUiLogicTest {

    @Test
    void bundledAudioStemsDecodeWithExact100BpmLengths() throws Exception {
        String base = "assets/habitrain_core/audio/boot_v3/";
        float[] intro = HabiBootAudioEngine.decodeWavResource(base + "intro.wav");
        float[] idle = HabiBootAudioEngine.decodeWavResource(base + "idle.wav");
        float[] load = HabiBootAudioEngine.decodeWavResource(base + "load_layer.wav");
        float[] exit = HabiBootAudioEngine.decodeWavResource(base + "exit.wav");
        float[] menu = HabiBootAudioEngine.decodeWavResource(base + "menu.wav");

        assertNotNull(intro, "intro.wav missing from classpath");
        assertNotNull(idle, "idle.wav missing from classpath");
        assertNotNull(load, "load_layer.wav missing from classpath");
        assertNotNull(exit, "exit.wav missing from classpath");
        assertNotNull(menu, "menu.wav missing from classpath");

        // Stereo 48kHz: idle & load_layer must match 4.8s (230,400 frames) for phase-locked looping
        assertEquals(230_400, idle.length / 2, 16, "idle.wav must be 4.8s at 48kHz");
        assertEquals(idle.length, load.length, "load_layer.wav must strictly match idle.wav length");
        assertEquals(921_600, menu.length / 2, 16, "menu.wav must be 19.2s (8 bars @ 100 BPM)");
        assertTrue(exit.length / 2 >= 57_600, "exit.wav must span at least 1.20s handoff");
        assertTrue(rms(menu) > 0.01, "menu.wav must carry audible signal");
    }

    @Test
    void bundledTypographyFontsExistOnClasspath() {
        for (String font : List.of("bebas.ttf", "noto_sans_sc_bold.ttf", "DejaVuSansMono-Bold.ttf")) {
            InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("assets/habitrain_core/font/replay/" + font);
            assertNotNull(in, "Missing bundled font: " + font);
        }
    }

    @Test
    void beatQuantizeLocksToEighthNoteGrid() {
        HabiBootAudioEngine engine = HabiBootAudioEngine.getInstance();
        engine.setQuantize(true);

        // Exactly on beat boundary after 3.0s -> immediate departure
        assertEquals(0.0, engine.computeQuantizeDelay(3.0), 1e-6);
        assertEquals(0.0, engine.computeQuantizeDelay(3.60), 1e-6);
        // Mid eighth note (3.15s) -> waits 0.15s to hit 3.30s
        assertEquals(0.15, engine.computeQuantizeDelay(3.15), 1e-6);
        // Never exceeds one eighth note (0.30s)
        for (double t = 3.0; t < 12.0; t += 0.037) {
            double d = engine.computeQuantizeDelay(t);
            assertTrue(d >= 0.0 && d <= HabiBootAudioEngine.EIGHTH + 1e-6, "delay out of range: " + d);
        }

        engine.setQuantize(false);
        assertEquals(0.0, engine.computeQuantizeDelay(3.15), 1e-6);
        engine.setQuantize(true);
    }

    @Test
    void loadLayerAdaptiveGainAndCutoffFollowProgress() {
        double gLow = HabiBootAudioEngine.calcLoadLayerGain(0.10);
        double gHigh = HabiBootAudioEngine.calcLoadLayerGain(0.95);
        double gReady = HabiBootAudioEngine.calcLoadLayerGain(1.0);
        assertTrue(gHigh > gLow, "load_layer gain should rise during loading");
        assertTrue(gReady < gHigh, "load_layer gain should settle softly at 100%");

        double fLow = HabiBootAudioEngine.calcLoadLayerCutoff(0.10);
        double fHigh = HabiBootAudioEngine.calcLoadLayerCutoff(0.95);
        double fReady = HabiBootAudioEngine.calcLoadLayerCutoff(1.0);
        assertTrue(fHigh > fLow, "load_layer filter cutoff should open as progress advances");
        assertEquals(5200.0, fReady, 1e-6);
    }

    @Test
    void realProgressTrackerMapsListenersAndNeverFakesCompletion() {
        HabiRealProgressTracker tracker = new HabiRealProgressTracker(HabiRealProgressTracker.BOOT_STAGES);

        PreparableReloadListener lLang = namedListener("LanguageManager");
        PreparableReloadListener lSound = namedListener("SoundManager");
        PreparableReloadListener lTex = namedListener("TextureManager");
        PreparableReloadListener lModel = namedListener("ModelManager");
        PreparableReloadListener lMod = namedListener("HabiSceneReloadListener");

        assertEquals(0, HabiRealProgressTracker.classifyReloadListener(lLang));
        assertEquals(1, HabiRealProgressTracker.classifyReloadListener(lSound));
        assertEquals(2, HabiRealProgressTracker.classifyReloadListener(lTex));
        assertEquals(3, HabiRealProgressTracker.classifyReloadListener(lModel));
        assertEquals(4, HabiRealProgressTracker.classifyReloadListener(lMod));

        FakeReload reload = new FakeReload(List.of(lLang, lSound, lTex, lModel, lMod));
        reload.pending = Set.of(lTex, lModel, lMod);
        reload.actualProgress = 0.45f;

        var mid = tracker.updateResourceReload(4.0, 0.05, reload, false, true, 1.0f, 2.65, false, false);
        assertFalse(mid.ready(), "Must never report READY before reload.isDone() is true");
        assertTrue(mid.progress() < 0.99);
        assertTrue(mid.stages().get(0).done(), "Stage 01 should be done once LanguageManager prepared");
        assertTrue(mid.stages().get(1).done(), "Stage 02 should be done once SoundManager & stems ready");
        assertFalse(mid.stages().get(2).done(), "Stage 03 should still be pending");

        // Now complete real reload
        reload.pending = Set.of();
        reload.allPrepDone = true;
        reload.finishedReloads = 5;
        reload.actualProgress = 1.0f;
        reload.done = true;

        HabiRealProgressTracker.LoadSnapshot end = null;
        for (int i = 0; i < 40; i++) {
            end = tracker.updateResourceReload(4.1 + i * 0.05, 0.05, reload, false, true, 1.0f, 2.65, false, false);
        }
        assertNotNull(end);
        assertTrue(end.ready(), "Must reach READY once real reload completes");
        assertEquals(1.0, end.progress(), 1e-4);
        assertTrue(end.stages().stream().allMatch(HabiRealProgressTracker.StageState::done));
    }

    @Test
    void bgmVolumeFollowsMasterAndLocalPrefWithoutDoubleScalingOnOpenAl() {
        // Early Java Sound fallback scales by both MASTER and local BGM preference
        assertEquals(0.85, HabiBootAudioEngine.computeBgmVolume(true, 1.0f), 1e-6);
        assertEquals(0.425, HabiBootAudioEngine.computeBgmVolume(true, 0.5f), 1e-6);
        assertEquals(0.2125, HabiBootAudioEngine.computeBgmVolume(true, 0.5f, 0.5f), 1e-6);
        assertEquals(0.0, HabiBootAudioEngine.computeBgmVolume(true, 0.0f), 1e-6);
        assertEquals(0.0, HabiBootAudioEngine.computeBgmVolume(true, 1.0f, 0.0f), 1e-6);
        assertEquals(0.0, HabiBootAudioEngine.computeBgmVolume(false, 1.0f), 1e-6);
        assertEquals(0.85, HabiBootAudioEngine.computeSfxVolume(true, 1.0f), 1e-6);

        // OpenAL stream uses SoundSource.MASTER (Listener already applies MASTER gain),
        // so PCM gain only multiplies by local BGM preference (never double-scaled).
        assertEquals(0.85, HabiBootAudioEngine.computeOpenAlBgmGain(true, 1.0f), 1e-6);
        assertEquals(0.425, HabiBootAudioEngine.computeOpenAlBgmGain(true, 0.5f), 1e-6);
        assertEquals(0.0, HabiBootAudioEngine.computeOpenAlBgmGain(true, 0.0f), 1e-6);
        assertEquals(0.0, HabiBootAudioEngine.computeOpenAlBgmGain(false, 1.0f), 1e-6);
        assertEquals(0.425, HabiBootAudioEngine.computeOpenAlSfxGain(true, 0.5f), 1e-6);
    }

    @Test
    void openAlStreamingMixesAudibleMenuPcmAndRecoversAfterDepartureOrMute() throws Exception {
        HabiBootAudioEngine engine = HabiBootAudioEngine.getInstance();
        try {
            assertTrue(engine.preloadAsync().get(10, TimeUnit.SECONDS), "boot_v3 stems must preload");

            // Simulate departing while muted -> ensureMenuPlaying after unmuting must recover Station 01 BGM
            engine.stopImmediately();
            engine.setTestVolumeOverrides(1.0f, 1.0f, false);
            engine.setUserMuted(true, 3.0, false, 0.0);
            engine.scheduleSeamlessDeparture(3.2);
            assertEquals(HabiBootAudioEngine.State.DEPARTING, engine.currentStateForTest());
            assertFalse(engine.hasActiveMusicChannelForTest());

            // Unmute & arrive at Station 01
            engine.setUserMuted(false, 5.0, true, 0.2);
            engine.ensureMenuPlaying(0.2);
            assertEquals(HabiBootAudioEngine.State.MENU, engine.currentStateForTest());
            assertTrue(engine.hasActiveMusicChannelForTest(), "Station 01 menu channel must be active");

            // Pull several 64 ms OpenAL chunks (first chunk ramps fade-in, later chunks carry full menu signal)
            double peakRms = 0.0;
            for (int c = 0; c < 6; c++) {
                ByteBuffer buf = engine.pullOpenAlChunkForTest();
                assertNotNull(buf, "OpenAL stream chunk must not be null while active");
                assertTrue(buf.remaining() > 0 && (buf.remaining() % 4) == 0);
                peakRms = Math.max(peakRms, pcm16Rms(buf));
            }
            assertTrue(peakRms > 0.02, "OpenAL mixed menu PCM must carry audible signal, rms=" + peakRms);

            // Stopping must produce clean EOF (null)
            engine.stopImmediately();
            assertNull(engine.pullOpenAlChunkForTest(), "Stopped engine must return null EOF for OpenAL");

            // Procedural one-shot UI SFX PCM must render valid audible 16-bit stereo bytes
            byte[] sfx = HabiBootAudioEngine.renderSfxPcm(
                    List.of(new HabiBootAudioEngine.SfxSpec(880.0, 880.0, 0.15, 0.12, HabiBootAudioEngine.Wave.SINE, 0.0, 0.0)),
                    0.8
            );
            assertTrue(sfx.length > 0 && (sfx.length % 4) == 0);
            assertTrue(pcm16Rms(ByteBuffer.wrap(sfx)) > 0.01, "One-shot SFX PCM must be audible");
        } finally {
            engine.stopImmediately();
            engine.setTestVolumeOverrides(1.0f, 1.0f, false);
            engine.setUserMuted(false, 0.0, false, 0.0);
        }
    }

    @Test
    void syncMinecraftVolumesIgnoresUpstreamDisableTitleScreenSound() {
        HabiBootAudioEngine engine = HabiBootAudioEngine.getInstance();
        try {
            engine.stopImmediately();
            engine.setTestVolumeOverrides(1.0f, 1.0f, true);
            assertFalse(engine.isSoundEffectivelyEnabled());
            // Syncing with null Minecraft refreshes local preference (default true) and clears stale disable flag,
            // never consulting upstream SREClientConfig.disableTitleScreenSound.
            engine.syncMinecraftVolumes(null);
            assertTrue(engine.isSoundEffectivelyEnabled(),
                    "Kinetic UI sound must stay enabled regardless of upstream SRE disableTitleScreenSound");
        } finally {
            engine.stopImmediately();
        }
    }

    @Test
    void initialBootDetectionHandlesGenericMessageScreenAndRejectsMidGameReload() {
        // Initial Minecraft startup: GenericMessageScreen, fadeIn=false, gameLoadFinished=false
        assertTrue(HabiKineticUiController.shouldTreatAsInitialBoot(false, false, false, false, true));
        // Even if another mod replaces the early screen before gameLoadFinished turns true
        assertTrue(HabiKineticUiController.shouldTreatAsInitialBoot(false, false, false, false, false));
        // Early non-fadeIn overlay on a boot screen
        assertTrue(HabiKineticUiController.shouldTreatAsInitialBoot(false, false, false, true, true));

        // F3+T reload after Station 01 arrival or in-world must use standalone reload
        assertFalse(HabiKineticUiController.shouldTreatAsInitialBoot(true, false, false, false, true));
        assertFalse(HabiKineticUiController.shouldTreatAsInitialBoot(false, true, false, false, true));
        assertFalse(HabiKineticUiController.shouldTreatAsInitialBoot(false, false, true, true, true));
    }

    @Test
    void bootTimelineClampsLongReloadStallsSoOpeningAnimationNeverSkips() {
        assertEquals(0.0, HabiBootSession.clampTimelineDelta(0.0), 1e-9);
        assertEquals(0.0, HabiBootSession.clampTimelineDelta(-0.2), 1e-9);
        assertEquals(0.0, HabiBootSession.clampTimelineDelta(Double.NaN), 1e-9);
        assertEquals(0.016, HabiBootSession.clampTimelineDelta(0.016), 1e-9);
        assertEquals(HabiBootSession.MAX_TIMELINE_DT, HabiBootSession.clampTimelineDelta(3.8), 1e-9);

        // Even 5 consecutive 2-second texture-stitch stalls stay well before the 1.12s red wipe
        double t = 0.0;
        for (int i = 0; i < 5; i++) {
            t += HabiBootSession.clampTimelineDelta(2.0);
        }
        assertTrue(t < HabiZeroStationRenderer.TL_WIPE,
                "Opening animation must not skip past TL_WIPE on reload stalls, t=" + t);
    }

    @Test
    void externalBootDingSuppressionBlocksDingAndAmbienceWhilePreservingUiClicks() {
        assertTrue(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.withDefaultNamespace("entity.experience_orb.pickup"), SoundSource.MASTER, false));
        assertTrue(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.withDefaultNamespace("block.note_block.bell"), SoundSource.MASTER, false));
        assertTrue(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.withDefaultNamespace("entity.player.levelup"), SoundSource.MASTER, false));
        assertTrue(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.fromNamespaceAndPath("wathe", "ambient.train.outside"), SoundSource.AMBIENT, false));
        assertTrue(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.fromNamespaceAndPath("ding", "ding"), SoundSource.MASTER, false));
        assertTrue(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.withDefaultNamespace("music.menu"), SoundSource.MUSIC, true));

        assertFalse(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.withDefaultNamespace("ui.button.click"), SoundSource.MASTER, true));
        assertFalse(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.fromNamespaceAndPath("habitrain_core", "custom_click"), SoundSource.MASTER, true));
        assertFalse(HabiBootAudioEngine.shouldSuppressExternalBootSound(
                ResourceLocation.withDefaultNamespace("music.menu"), SoundSource.MUSIC, false));
    }

    @Test
    void markAlreadyCompletedPreventsSpuriousStageTickOnFallbackEntry() {
        HabiRealProgressTracker tracker = new HabiRealProgressTracker(HabiRealProgressTracker.BOOT_STAGES);
        tracker.markAlreadyCompleted(3.2);
        var snap = tracker.updateResourceReload(3.2, 0.016, null, true, true, 1.0f, 2.65, false, false);
        assertTrue(snap.ready(), "Fallback entry should already be READY");
        assertNull(snap.stageJustCompleted(), "Fallback entry must not emit a spurious stage tick ding");
        assertTrue(snap.stages().stream().allMatch(HabiRealProgressTracker.StageState::done));
    }

    @Test
    void openAlAudibleClockGatesStartUntilPrefillAndCompensatesDepartureLatency() throws Exception {
        HabiBootAudioEngine engine = HabiBootAudioEngine.getInstance();
        try {
            assertTrue(engine.preloadAsync().get(10, TimeUnit.SECONDS), "boot_v3 stems must preload");
            engine.stopImmediately();
            engine.setEarlyJavaSoundUnavailableForTest(true);
            engine.setTestVolumeOverrides(1.0f, 1.0f, false);
            engine.setUserMuted(false, 0.0, false, 0.0);

            // Start fresh loading sequence at t = 0.0s with an OpenAL stream about to prefill
            engine.startLoadingSequence(0.0, 0.0);
            HabiBootAudioEngine.KineticBgmSoundInstance owner = engine.attachMockOpenAlStreamForTest();

            // Chunks 1..3 are still filling OpenAL's 4-buffer queue before alSourcePlay() -> must wait!
            for (int i = 0; i < 3; i++) {
                assertNotNull(engine.pullOwnedOpenAlChunkForTest(owner));
                assertTrue(engine.getAudibleLoadingTimelineSeconds() < 0.0,
                        "Audible clock must not start before 4th OpenAL prefill chunk (alSourcePlay), chunk=" + (i + 1));
                assertTrue(engine.shouldWaitForAudibleBootStart());
            }

            // 4th prefill chunk completes -> Channel.attachBufferStream immediately calls alSourcePlay() at t = 0.0s
            assertNotNull(engine.pullOwnedOpenAlChunkForTest(owner));
            double tAtPlay = engine.getAudibleLoadingTimelineSeconds();
            assertTrue(tAtPlay >= 0.0 && tAtPlay < 0.05,
                    "Audible clock must start right at t=0.0s on 4th prefill chunk, got " + tAtPlay);
            assertFalse(engine.shouldWaitForAudibleBootStart());

            // Simulate 120 ms of playback elapsed -> audible clock advances smoothly to ~0.12s
            engine.shiftOpenAlAnchorBackForTest(120_000_000L);
            double tMid = engine.getAudibleLoadingTimelineSeconds();
            assertEquals(0.12, tMid, 0.03, "Audible clock should track OpenAL playback elapsed time");

            // Simulate a 2-second main-thread stall with no new OpenAL buffer pulls ->
            // audible clock clamps cleanly to the 4-buffer queue ceiling (4 * 64ms = 256ms) instead of drifting!
            engine.shiftOpenAlAnchorBackForTest(2_000_000_000L);
            double tClamped = engine.getAudibleLoadingTimelineSeconds();
            assertEquals(0.256, tClamped, 0.015,
                    "Audible clock must clamp to queued OpenAL samples during stalls, got " + tClamped);

            // Fresh replay (t = 0.0) must immediately flush/close the stale OpenAL stream
            engine.setQuantize(false);
            engine.startLoadingSequence(0.0, 0.0);
            assertTrue(owner.isMarkedClosed(), "Fresh t=0.0 start/replay must flush stale OpenAL queue");

            HabiBootAudioEngine.KineticBgmSoundInstance owner2 = engine.attachMockOpenAlStreamForTest();
            for (int i = 0; i < 4; i++) {
                assertNotNull(engine.pullOwnedOpenAlChunkForTest(owner2));
            }
            // Right after 4-chunk prefill: heard = S0, mixerSampleClock = S0 + 0.256s
            double visualDelay = engine.scheduleSeamlessDeparture(3.0);
            assertTrue(visualDelay >= 0.22 && visualDelay <= 0.30,
                    "Visual departure delay must compensate ~256ms OpenAL queue latency, got " + visualDelay);
            double departElapsedAtTrigger = engine.getAudibleDepartureElapsedSeconds();
            assertEquals(-visualDelay, departElapsedAtTrigger, 0.025,
                    "Audible departure elapsed must begin at -visualDelay for zero jump on triggerBoarding");
        } finally {
            engine.setQuantize(true);
            engine.stopImmediately();
            engine.setEarlyJavaSoundUnavailableForTest(false);
            engine.setTestVolumeOverrides(1.0f, 1.0f, false);
            engine.setUserMuted(false, 0.0, false, 0.0);
        }
    }

    @Test
    void lateOpenAlAttachmentResyncsStemsToVisualTimelineInsteadOfStartingAtZero() throws Exception {
        HabiBootAudioEngine engine = HabiBootAudioEngine.getInstance();
        try {
            assertTrue(engine.preloadAsync().get(10, TimeUnit.SECONDS), "boot_v3 stems must preload");
            engine.stopImmediately();
            engine.setEarlyJavaSoundUnavailableForTest(true);
            engine.setTestVolumeOverrides(1.0f, 1.0f, false);
            engine.setUserMuted(false, 0.0, false, 0.0);

            // Simulate visuals advancing to t = 2.10s before any OpenAL backend attached
            engine.startLoadingSequence(0.0, 0.0);
            engine.updateLoadingProgress(0.65, 2.10);

            // Now OpenAL attaches late and pulls 4 prefill chunks -> must resync to ~2.10s, NOT 0.0s!
            HabiBootAudioEngine.KineticBgmSoundInstance owner = engine.attachMockOpenAlStreamForTest();
            for (int i = 0; i < 4; i++) {
                assertNotNull(engine.pullOwnedOpenAlChunkForTest(owner));
            }
            double audibleT = engine.getAudibleLoadingTimelineSeconds();
            assertEquals(2.10, audibleT, 0.05,
                    "Late OpenAL attachment must resync intro/idle to current visual timeline instead of 0.0s");
        } finally {
            engine.stopImmediately();
            engine.setEarlyJavaSoundUnavailableForTest(false);
            engine.setTestVolumeOverrides(1.0f, 1.0f, false);
            engine.setUserMuted(false, 0.0, false, 0.0);
        }
    }

    @Test
    void syncedVisualTimelineTracksAudibleClockMonotonically() {
        assertEquals(0.0, HabiBootSession.resolveSyncedTimeline(0.0, 0.0), 1e-9);
        assertEquals(0.42, HabiBootSession.resolveSyncedTimeline(0.40, 0.42), 1e-9);
        // Minor backward jitter from audio clock must never pull visuals backward
        assertEquals(1.12, HabiBootSession.resolveSyncedTimeline(1.12, 1.10), 1e-9);
        // Invalid / unavailable audio clock preserves current visual time
        assertEquals(1.50, HabiBootSession.resolveSyncedTimeline(1.50, -1.0), 1e-9);
        assertEquals(1.50, HabiBootSession.resolveSyncedTimeline(1.50, Double.NaN), 1e-9);
    }

    @Test
    void rightVerticalStampUsesAppleTrainCopyAndClearsCargoCardAcrossAspects() {
        assertEquals("苹果列车", HabiZeroStationRenderer.BRAND_CN);
        assertTrue(HabiZeroStationRenderer.RIGHT_STAMP_COL1_INSET < 74.0,
                "Right vertical stamp should shift closer to the right edge");
        assertEquals(HabiZeroStationRenderer.RIGHT_STAMP_COL1_INSET + 8.0,
                HabiZeroStationRenderer.RIGHT_STAMP_BAR_INSET, 1e-9,
                "16px top accent bar must stay centered above col1");
        assertEquals(708.0, HabiZeroStationRenderer.computeCardBWorldX(1920.0), 1e-9,
                "16:9+ aspect ratios should keep CARGO // 02 card at its original world X");

        // Verify across 16:9, 16:10, 3:2, and 4:3 under maximum rightward camera sway + pointer offset (ry = +0.155)
        HabiKineticGfx.Cam maxRightCam = new HabiKineticGfx.Cam(0.0, 0.155, 0.0, 1.0);
        double[] cardRot = HabiKineticGfx.euler(0.0, -0.32, 0.06);
        double[] M = HabiKineticGfx.mm(maxRightCam.R, cardRot);

        for (double aspect : new double[]{16.0 / 9.0, 1.6, 1.5, 4.0 / 3.0}) {
            double viewW = aspect >= (16.0 / 9.0) ? HabiKineticGfx.W : HabiKineticGfx.H * aspect;
            double R = (HabiKineticGfx.W + viewW) * 0.5;
            double cardX = HabiZeroStationRenderer.computeCardBWorldX(R);
            double[] O = maxRightCam.v(cardX, -40.0, 120.0);

            double maxProjectedRight = Double.NEGATIVE_INFINITY;
            for (double depth : new double[]{0.0, 10.0}) {
                double ox = O[0] + M[2] * depth;
                double oy = O[1] + M[5] * depth;
                double oz = O[2] + M[8] * depth;
                for (double lx : new double[]{-76.0, 76.0}) {
                    for (double ly : new double[]{-100.0, 100.0}) {
                        double[] proj = maxRightCam.project(
                                ox + M[0] * lx + M[1] * ly,
                                oy + M[3] * lx + M[4] * ly,
                                oz + M[6] * lx + M[7] * ly
                        );
                        assertNotNull(proj);
                        maxProjectedRight = Math.max(maxProjectedRight, proj[0]);
                    }
                }
            }

            double col2LeftEdge = R - HabiZeroStationRenderer.RIGHT_STAMP_COL2_INSET - 9.0;
            double col1RightEdge = R - HabiZeroStationRenderer.RIGHT_STAMP_COL1_INSET + 9.0;
            assertTrue(maxProjectedRight + 20.0 < col2LeftEdge,
                    "CARGO // 02 card must clear right vertical stamp at aspect=" + aspect
                            + " (cardRight=" + maxProjectedRight + ", col2Left=" + col2LeftEdge + ")");
            assertTrue(col1RightEdge < R - 20.0,
                    "col1 must keep clean breathing margin inside right edge at aspect=" + aspect);
        }
    }

    @Test
    void feedbackMenuEntryTargetsHabiTrainIssuesRepository() {
        assertEquals(
                "https://github.com/Mike5321-working/StarRail-Express-Further-modifications/issues",
                HabiTitleTelemetry.FEEDBACK_ISSUES_URL
        );
        HabiTitleTelemetry.MenuVisualMeta meta = HabiTitleTelemetry.describeEntry(
                null, Component.translatable("menu.sre.feedback"));
        assertEquals("ISSUE REPORT", meta.enSub());
        assertEquals("GITHUB", meta.tag());
        assertTrue(meta.cardDesc().contains("Mike5321-working/StarRail-Express-Further-modifications"),
                "Feedback telemetry description should point to HabiTrain issues repo, got: " + meta.cardDesc());
        assertFalse(meta.cardDesc().contains("StarRailExpress Issues"),
                "Feedback telemetry description must not reference upstream repo");
    }

    private static double rms(float[] pcm) {
        double sum = 0.0;
        for (float v : pcm) sum += v * v;
        return Math.sqrt(sum / Math.max(1, pcm.length));
    }

    private static double pcm16Rms(ByteBuffer pcmLe) {
        ByteBuffer dup = pcmLe.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int samples = dup.remaining() / 2;
        if (samples <= 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < samples; i++) {
            double v = dup.getShort() / 32768.0;
            sum += v * v;
        }
        return Math.sqrt(sum / samples);
    }

    private static PreparableReloadListener namedListener(String name) {
        return new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
                                                  ProfilerFiller prepProfiler, ProfilerFiller reloadProfiler,
                                                  Executor bgExecutor, Executor gameExecutor) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String getName() {
                return name;
            }
        };
    }

    private static final class FakeReload implements ReloadInstance, HabiReloadProgressAccess {
        final List<PreparableReloadListener> all;
        Set<PreparableReloadListener> pending = Set.of();
        boolean allPrepDone;
        int finishedReloads;
        float actualProgress;
        boolean done;

        FakeReload(List<PreparableReloadListener> all) {
            this.all = all;
        }

        @Override public CompletableFuture<?> done() { return done ? CompletableFuture.completedFuture(null) : new CompletableFuture<>(); }
        @Override public float getActualProgress() { return actualProgress; }
        @Override public boolean isDone() { return done; }
        @Override public List<PreparableReloadListener> habitrain$getAllListeners() { return all; }
        @Override public Set<PreparableReloadListener> habitrain$getPendingPreparingListeners() { return pending; }
        @Override public boolean habitrain$isAllPreparationsDone() { return allPrepDone; }
        @Override public int habitrain$getStartedReloads() { return all.size(); }
        @Override public int habitrain$getFinishedReloads() { return finishedReloads; }
        @Override public int habitrain$getStartedTaskCount() { return all.size() * 2; }
        @Override public int habitrain$getDoneTaskCount() { return all.size() - pending.size() + finishedReloads; }
        @Override public int habitrain$getListenerCount() { return all.size(); }
    }
}
