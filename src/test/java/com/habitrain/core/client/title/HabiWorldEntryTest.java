package com.habitrain.core.client.title;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HabiWorldEntryTest {

    @AfterEach
    void tearDown() {
        HabiWorldEntrySession.getInstance().resetForTest();
        HabiBootAudioEngine engine = HabiBootAudioEngine.getInstance();
        engine.stopImmediately();
        engine.setEarlyJavaSoundUnavailableForTest(false);
        engine.setTestVolumeOverrides(1.0f, 1.0f, false);
        engine.setUserMuted(false, 0.0, false, 0.0);
    }

    @Test
    void fullThreeActStationEntryLifecycleExecutesDeferredLaunchMonotonicProgressAndRealWorldIrisReveal() {
        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        session.resetForTest();
        session.setAudioEnabledForTest(false);

        AtomicInteger launched = new AtomicInteger(0);
        session.beginFromStation(
                4.25, 0.15, -0.10,
                "苹果园主世界", "LOCAL WORLD // SURVIVAL",
                launched::incrementAndGet, true
        );

        assertTrue(session.isActive());
        assertEquals(HabiWorldEntrySession.Phase.START, session.getPhase());
        assertTrue(session.hasPendingLaunch());
        assertTrue(session.shouldSuppressInGameHud());
        assertTrue(session.shouldDeferJoinCleanup());
        assertFalse(session.shouldCloseReceivingScreen());
        assertEquals(1.0, session.getLandingFovMultiplier(), 1e-9);
        assertEquals(0, launched.get());

        // Advance Act 1 up to 2.65s -> deferred singleplayer launch must NOT fire before doorway covers screen
        stepMany(session, 53, 0.05, false); // 2.65s
        assertEquals(HabiWorldEntrySession.Phase.START, session.getPhase());
        assertEquals(0, launched.get(), "Deferred launch must wait until doorway fills viewport (>= 2.72s)");
        assertEquals(1.0, session.getMenuFade(), 1e-6);

        // Cross 2.72s -> deferred launch fires exactly once
        stepMany(session, 2, 0.05, false); // 2.75s
        assertEquals(1, launched.get(), "Deferred launch must execute right at >= 2.72s");
        assertFalse(session.hasPendingLaunch());

        // Cross 2.88s -> enters Act 2 LOADING seamlessly
        stepMany(session, 4, 0.05, false); // 2.95s
        assertEquals(HabiWorldEntrySession.Phase.LOADING, session.getPhase());
        assertEquals(1, launched.get());

        // Feed real connect status & receiving-not-ready signals -> progress climbs monotonically but NEVER hits 100%
        double prevProg = session.getProgress();
        for (int i = 0; i < 40; i++) {
            session.reportConnectStatus(Component.literal("Generating terrain"));
            session.reportReceivingLevel(false, "LOCAL WORLD");
            session.step(0.05, false, null);
            assertTrue(session.getProgress() >= prevProg - 1e-9, "Progress must stay monotonic");
            prevProg = session.getProgress();
        }
        assertEquals(HabiWorldEntrySession.Phase.LOADING, session.getPhase(),
                "Must stay in LOADING while worldReady is false");
        assertTrue(session.getProgress() < 0.96, "Unready world must not fake 100%, got " + session.getProgress());

        // World finishes loading -> climbs to 100% and transitions to Act 3 END
        session.reportReceivingLevel(true, "LOCAL WORLD");
        for (int i = 0; i < 40 && session.getPhase() == HabiWorldEntrySession.Phase.LOADING; i++) {
            session.step(0.05, false, null);
        }
        assertEquals(HabiWorldEntrySession.Phase.END, session.getPhase(),
                "Ready world after MIN_TUNNEL_CRUISE_SEC must enter Act 3 END");

        // Arrival seal hold (0.00..0.48s): ReceivingLevelScreen closes immediately behind the 100% opaque
        // Arrival Postmark Seal so live 3D world pre-warms invisibly; HUD stays hidden and FOV stays 1.0
        stepMany(session, 8, 0.05, false); // 0.40s
        assertEquals(HabiWorldEntrySession.Phase.END, session.getPhase());
        assertTrue(session.shouldCloseReceivingScreen(), "Receiving screen closes at END t=0.00s for opaque 3D warmup");
        assertTrue(session.shouldSuppressInGameHud());
        assertEquals(1.0, session.getLandingFovMultiplier(), 1e-9);

        // Cross 0.48s -> Apple Portal Iris opens over already-warmed live 3D Minecraft world
        stepMany(session, 2, 0.05, false); // 0.50s
        assertTrue(session.shouldCloseReceivingScreen(), "Receiving screen stays closed at END_PORTAL_START");
        assertTrue(session.shouldSuppressInGameHud(), "2D gameplay HUD stays hidden until t = 1.06s");
        double fovAtPortalOpen = session.getLandingFovMultiplier();
        assertTrue(fovAtPortalOpen < 0.95 && fovAtPortalOpen > 0.85,
                "Landing FOV multiplier should zoom in gently (~1/1.13 = 0.885), got " + fovAtPortalOpen);

        // Cross 1.06s -> real gameplay HUD reveals underneath warm bloom, FOV eases smoothly toward 1.0
        stepMany(session, 12, 0.05, true); // 1.10s
        assertEquals(HabiWorldEntrySession.Phase.END, session.getPhase());
        assertFalse(session.shouldSuppressInGameHud(), "2D gameplay HUD must reveal at >= 1.06s");
        double fovMid = session.getLandingFovMultiplier();
        assertTrue(fovMid > fovAtPortalOpen && fovMid <= 1.0,
                "Landing FOV must decelerate smoothly toward 1.0, got " + fovMid);

        // Cross 1.66s -> cutscene completes cleanly and resets to INACTIVE
        stepMany(session, 14, 0.05, true); // 1.80s
        assertFalse(session.isActive());
        assertEquals(HabiWorldEntrySession.Phase.INACTIVE, session.getPhase());
        assertFalse(session.shouldSuppressInGameHud());
        assertFalse(session.shouldDeferJoinCleanup());
        assertEquals(1.0, session.getLandingFovMultiplier(), 1e-9);
    }

    @Test
    void directTunnelEntryFadesInSmoothlyAndCompletesOnInWorldNoScreenSafetyPath() {
        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        session.resetForTest();
        session.setAudioEnabledForTest(false);

        session.beginDirectTunnel("下界传送", "NETHER // EXPRESS 01");
        assertEquals(HabiWorldEntrySession.Phase.LOADING, session.getPhase());
        assertTrue(session.isDirectEntry());
        assertEquals(0.0, session.getDirectEntryFadeIn(), 1e-6);

        // Direct fade-in reaches 1.0 by 0.28s
        stepMany(session, 6, 0.05, false); // 0.30s
        assertEquals(1.0, session.getDirectEntryFadeIn(), 1e-6);

        // Simulate entering live world without ReceivingLevelScreen -> safety path transitions to END and finishes
        for (int i = 0; i < 40 && session.getPhase() == HabiWorldEntrySession.Phase.LOADING; i++) {
            session.step(0.05, true, null);
        }
        assertEquals(HabiWorldEntrySession.Phase.END, session.getPhase(),
                "Safety path must enter Act 3 END once in-world without screen");
        stepMany(session, 40, 0.05, true);
        assertFalse(session.isActive(), "Act 3 END must finish cleanly to INACTIVE");

        // Verify world-stage weights sum exactly to 1.0
        double weightSum = 0.0;
        for (HabiWorldEntrySession.StageDef def : HabiWorldEntrySession.WORLD_STAGES) {
            weightSum += def.weight();
        }
        assertEquals(1.0, weightSum, 1e-9, "WORLD_STAGES weights must sum to 1.0");
    }

    @Test
    void endPhasePortalNeverReplaysOrSnapsBackwardsOnMidPortalStutters() {
        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        session.resetForTest();
        session.setAudioEnabledForTest(false);
        session.setForceWarmupGateForTest(true);

        session.beginDirectTunnel("STMAP", "LOCAL WORLD // ADVENTURE");
        session.reportReceivingLevel(true, "LOCAL WORLD");
        for (int i = 0; i < 50 && session.getPhase() == HabiWorldEntrySession.Phase.LOADING; i++) {
            session.stepWithRawFrameMsForTest(30.0, false);
        }
        assertEquals(HabiWorldEntrySession.Phase.END, session.getPhase());

        // Simulate heavy 600ms first-frame InWorld stutter + continued slow frames up to holdBarrier (0.46s)
        session.stepWithRawFrameMsForTest(600.0, true);
        for (int i = 0; i < 12; i++) {
            session.stepWithRawFrameMsForTest(120.0, true);
        }
        assertEquals(HabiWorldEntrySession.Phase.END, session.getPhase());
        assertTrue(session.getPhaseT() <= HabiWorldEntrySession.END_PORTAL_START,
                "Warmup gate must hold strictly before Apple Portal opens, got t=" + session.getPhaseT());

        // One smooth frame releases the warmup latch permanently -> Apple Portal opens past 0.48s
        session.stepWithRawFrameMsForTest(16.7, true);
        session.stepWithRawFrameMsForTest(50.0, true);
        double openedT = session.getPhaseT();
        assertTrue(openedT >= HabiWorldEntrySession.END_PORTAL_START,
                "Portal must open once warmup releases, got t=" + openedT);

        // Now inject 3 severe mid-portal stutters (>100ms): phaseT MUST stay strictly monotonic and NEVER snap back!
        double prevT = openedT;
        double[] stuttersMs = {180.0, 220.0, 600.0, 16.7, 150.0};
        for (double ms : stuttersMs) {
            session.stepWithRawFrameMsForTest(ms, true);
            assertEquals(HabiWorldEntrySession.Phase.END, session.getPhase());
            assertTrue(session.getPhaseT() > prevT,
                    "Apple Portal phaseT must NEVER snap backwards on stutter (" + ms + "ms): prev="
                            + prevT + " cur=" + session.getPhaseT());
            prevT = session.getPhaseT();
        }

        // Continue to clean completion
        for (int i = 0; i < 40 && session.isActive(); i++) {
            session.stepWithRawFrameMsForTest(50.0, true);
        }
        assertFalse(session.isActive(), "END phase must complete cleanly after monotonic Apple Portal expansion");
    }

    @Test
    void messageClassificationSeparatesWorldEntryFromWorldSaving() {
        assertTrue(HabiWorldEntrySession.isWorldSavingMessage(Component.translatable("menu.savingLevel")));
        assertTrue(HabiWorldEntrySession.isWorldSavingMessage(Component.translatable("menu.savingChunks")));
        assertTrue(HabiWorldEntrySession.isWorldSavingMessage(Component.translatable("connect.disconnecting")));
        assertTrue(HabiWorldEntrySession.isWorldSavingMessage(Component.literal("正在保存世界")));
        assertFalse(HabiWorldEntrySession.isWorldSavingMessage(Component.translatable("selectWorld.data_read")));

        assertTrue(HabiWorldEntrySession.isWorldEntryMessage(Component.translatable("selectWorld.data_read")));
        assertTrue(HabiWorldEntrySession.isWorldEntryMessage(Component.translatable("connect.negotiating")));
        assertTrue(HabiWorldEntrySession.isWorldEntryMessage(Component.translatable("multiplayer.downloadingTerrain")));
        assertFalse(HabiWorldEntrySession.isWorldEntryMessage(Component.translatable("menu.savingLevel")));
    }

    @Test
    void worldEntryAudioRestoresFullThreeActSoundscapeWithDeepBufferAndCleanBreakout() throws Exception {
        HabiBootAudioEngine engine = HabiBootAudioEngine.getInstance();
        assertTrue(engine.preloadAsync().get(10, java.util.concurrent.TimeUnit.SECONDS), "boot_v3 stems must preload");
        engine.stopImmediately();
        engine.setEarlyJavaSoundUnavailableForTest(true);
        engine.setTestVolumeOverrides(1.0f, 1.0f, false);
        engine.setUserMuted(false, 0.0, false, 0.0);

        // 1) Start Station 01 homepage menu BGM and pull a few chunks until audible
        engine.startMenuDirect(1.0);
        assertEquals(HabiBootAudioEngine.State.MENU, engine.currentStateForTest());
        double menuRms = 0.0;
        for (int i = 0; i < 6; i++) {
            ByteBuffer buf = engine.pullOpenAlChunkForTest();
            assertNotNull(buf);
            menuRms = Math.max(menuRms, pcm16Rms(buf));
        }
        assertTrue(menuRms > 0.02, "Homepage menu BGM must be audible before entering world, rms=" + menuRms);

        // 2) Entering world from Station 01 fades out menu.wav while launching Act 1 engine charge,
        //    bandpass wind bed, and Act 2 100 BPM tunnel arpeggio across deep 128 ms chunks (> 3.5 s cushion!)
        assertTrue(HabiTunnelOpenAlPlayer.TARGET_BUFFER_SECONDS >= 3.0,
                "Dedicated OpenAL tunnel queue must hold >= 3.0 s cushion, got "
                        + HabiTunnelOpenAlPlayer.TARGET_BUFFER_SECONDS);
        engine.startTunnelEntry(true);
        assertEquals(HabiBootAudioEngine.State.TUNNEL, engine.currentStateForTest());
        int gen = engine.currentTunnelGenerationForTest();

        byte[] chunkBuf = new byte[HabiTunnelOpenAlPlayer.CHUNK_BYTES];
        HabiTunnelOpenAlPlayer.ChunkMeta meta = new HabiTunnelOpenAlPlayer.ChunkMeta();
        double act1EarlyRms = 0.0;
        double act2ArpRms = 0.0;
        long prevStartSample = Long.MIN_VALUE;

        // Pull 32 consecutive 128 ms chunks (~4.10 s: Act 1 menu fade + engine sweep + Act 2 100 BPM arpeggio)
        for (int c = 0; c < 32; c++) {
            if (c == 20) {
                engine.updateTunnelProgress(0.75);
            }
            assertTrue(engine.pullDedicatedTunnelChunkForTest(gen, chunkBuf, meta),
                    "Dedicated tunnel mixer must keep producing audio continuously across Act 1 & Act 2, chunk=" + c);
            assertTrue(meta.hasAudio, "Tunnel chunk must carry active soundscape at chunk=" + c);
            if (prevStartSample != Long.MIN_VALUE) {
                assertEquals(prevStartSample + HabiTunnelOpenAlPlayer.CHUNK_FRAMES, meta.startSample,
                        "Dedicated tunnel chunk sample clock must advance monotonically by exact CHUNK_FRAMES");
            }
            prevStartSample = meta.startSample;
            double r = pcm16Rms(ByteBuffer.wrap(chunkBuf));
            if (c < 10) act1EarlyRms = Math.max(act1EarlyRms, r);
            if (c >= 20) act2ArpRms = Math.max(act2ArpRms, r); // Past 2.56 s: menu.wav already faded, Act 2 wind + 100 BPM arp active!
        }
        assertTrue(act1EarlyRms > 0.015, "Act 1 menu fade + charge must be audible, rms=" + act1EarlyRms);
        assertTrue(act2ArpRms > 0.004, "Act 2 wind bed + 100 BPM tunnel arpeggio must remain audible past menu fade, rms=" + act2ArpRms);
        assertEquals(HabiBootAudioEngine.State.TUNNEL, engine.currentStateForTest());

        // 3) Act 3 breakout pre-renders full 1.55 s static wind-surge-and-dissolve buffer (immune to 3D warmup stutters)
        byte[] breakoutWind = engine.renderBreakoutWindForTest(1100.0, 0.15, 0);
        int expectedMinBytes = (int) (1.55 * HabiBootAudioEngine.SAMPLE_RATE) * 4;
        assertTrue(breakoutWind.length >= expectedMinBytes,
                "Static breakout wind buffer must span >= 1.55 s, got bytes=" + breakoutWind.length);
        double surgePeakRms = slicePcm16Rms(breakoutWind, 0.55, 0.85);
        double dissolveTailRms = slicePcm16Rms(breakoutWind, 1.40, 1.54);
        assertTrue(surgePeakRms > 0.01, "Breakout wind surge around 0.7s must be clearly audible, rms=" + surgePeakRms);
        assertTrue(surgePeakRms > dissolveTailRms * 5.0,
                "Breakout wind must dissolve smoothly by 1.55 s: surge=" + surgePeakRms + " tail=" + dissolveTailRms);

        // Trigger breakout & verify stems can be released immediately on world join while breakout state completes cleanly
        engine.triggerTunnelBreakout();
        assertTrue(engine.isTunnelBreakoutTriggeredForTest());
        engine.fadeOutAndReleaseStems();
        assertFalse(engine.areStemsLoaded(), "Boot WAV stems must be released immediately on world join after breakout");
        assertEquals(HabiBootAudioEngine.State.TUNNEL, engine.currentStateForTest(),
                "Act 3 breakout tail must not be abruptly aborted by stem release");

        // Advance headless breakout wind envelope through 1.55 s -> reaches STOPPED cleanly
        int chunksUntilStopped = 0;
        for (int c = 0; c < 20; c++) {
            if (!engine.pullDedicatedTunnelChunkForTest(gen, chunkBuf, meta)) {
                break;
            }
            chunksUntilStopped++;
        }
        assertTrue(chunksUntilStopped >= 10 && chunksUntilStopped <= 16,
                "Breakout envelope should finish cleanly around ~1.55 s (~12-13 chunks), got " + chunksUntilStopped);
        assertEquals(HabiBootAudioEngine.State.STOPPED, engine.currentStateForTest());

        // 4) Direct world entry (no prior menu BGM) must ALSO play audible tunnel soundscape
        engine.startTunnelEntry(false);
        assertEquals(HabiBootAudioEngine.State.TUNNEL, engine.currentStateForTest());
        int directGen = engine.currentTunnelGenerationForTest();
        double directRms = 0.0;
        for (int c = 0; c < 8; c++) {
            assertTrue(engine.pullDedicatedTunnelChunkForTest(directGen, chunkBuf, meta));
            directRms = Math.max(directRms, pcm16Rms(ByteBuffer.wrap(chunkBuf)));
        }
        assertTrue(directRms > 0.004, "Direct world entry must produce audible wind + 100 BPM arpeggio, rms=" + directRms);

        // 5) Mute & unmute during tunnel stops cleanly and resumes tunnel (never flashing boot/menu stems)
        engine.setUserMuted(true, 0.0, false, 0.0);
        assertEquals(HabiBootAudioEngine.State.STOPPED, engine.currentStateForTest());
        assertFalse(engine.pullDedicatedTunnelChunkForTest(directGen, chunkBuf, meta));

        engine.unmuteForTunnel(true, 0.50);
        assertEquals(HabiBootAudioEngine.State.TUNNEL, engine.currentStateForTest());
        engine.stopTunnelAudio();
        assertEquals(HabiBootAudioEngine.State.STOPPED, engine.currentStateForTest());
    }

    @Test
    void dedicatedTunnelPlayerQueuePlannerAndSampleLockedSpectrumSelectorWorkAccurately() {
        // Queue fill planner saturates full 28-buffer (3.584 s) target and halts on EOF / breakout fade-out
        assertEquals(25, HabiTunnelOpenAlPlayer.computeChunksToFill(3, 28, 25, false, false));
        assertEquals(10, HabiTunnelOpenAlPlayer.computeChunksToFill(3, 28, 10, false, false));
        assertEquals(0, HabiTunnelOpenAlPlayer.computeChunksToFill(28, 28, 4, false, false));
        assertEquals(0, HabiTunnelOpenAlPlayer.computeChunksToFill(5, 28, 20, true, false));
        assertEquals(0, HabiTunnelOpenAlPlayer.computeChunksToFill(5, 28, 20, false, true));

        // Sample-locked spectrum delay line returns the chunk matching the audible speaker sample,
        // NOT the chunk mixed 3.5 seconds ahead into the OpenAL queue!
        var ring = new java.util.concurrent.atomic.AtomicReferenceArray<HabiTunnelOpenAlPlayer.SpectrumSnapshot>(8);
        ring.set(0, new HabiTunnelOpenAlPlayer.SpectrumSnapshot(0L, new float[16], 0.20, 0.30));
        ring.set(1, new HabiTunnelOpenAlPlayer.SpectrumSnapshot(6144L, new float[16], 0.55, 0.65));
        ring.set(2, new HabiTunnelOpenAlPlayer.SpectrumSnapshot(12288L, new float[16], 0.90, 0.95));

        var atAudible = HabiTunnelOpenAlPlayer.selectSpectrumSnapshot(ring, 7500L);
        assertNotNull(atAudible);
        assertEquals(6144L, atAudible.startSample());
        assertEquals(0.55, atAudible.bass(), 1e-6);

        var atLatest = HabiTunnelOpenAlPlayer.selectSpectrumSnapshot(ring, 50_000L);
        assertNotNull(atLatest);
        assertEquals(12288L, atLatest.startSample());
        assertEquals(0.90, atLatest.bass(), 1e-6);
    }

    private static double slicePcm16Rms(byte[] pcmLe, double startSec, double endSec) {
        int startFrame = Math.max(0, (int) Math.round(startSec * HabiBootAudioEngine.SAMPLE_RATE));
        int endFrame = Math.min(pcmLe.length / 4, (int) Math.round(endSec * HabiBootAudioEngine.SAMPLE_RATE));
        int byteOff = startFrame * 4;
        int byteLen = Math.max(0, (endFrame - startFrame) * 4);
        if (byteLen < 4) return 0.0;
        return pcm16Rms(ByteBuffer.wrap(pcmLe, byteOff, byteLen));
    }

    private static void stepMany(HabiWorldEntrySession session, int count, double dt, boolean inWorldNoScreen) {
        for (int i = 0; i < count; i++) {
            session.step(dt, inWorldNoScreen, null);
        }
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
}
