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
    void worldEntryAudioFadesOutHomepageMenuBgmWithinOneSecondAndPlaysNoTunnelMusic() throws Exception {
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

        // 2) Entering a world fades out homepage BGM smoothly within <= 1.0 second and stops cleanly
        assertTrue(HabiBootAudioEngine.WORLD_ENTRY_MENU_FADE_SEC <= 1.0,
                "World-entry menu fade must complete within 1.0 second");
        engine.startTunnelEntry(true);
        assertEquals(HabiBootAudioEngine.State.TUNNEL, engine.currentStateForTest());

        double earlyFadeRms = 0.0;
        double lateFadeRms = 0.0;
        int chunksUntilStopped = 0;
        for (int i = 0; i < 20; i++) {
            ByteBuffer buf = engine.pullOpenAlChunkForTest();
            if (buf == null) {
                break;
            }
            chunksUntilStopped++;
            double r = pcm16Rms(buf);
            if (i < 3) earlyFadeRms = Math.max(earlyFadeRms, r);
            if (i >= 8) lateFadeRms = Math.max(lateFadeRms, r);
        }
        // Each chunk is 64ms -> <= 15 chunks = <= 0.96s (fade finishes at 0.78s = ~13 chunks)
        assertTrue(chunksUntilStopped >= 10 && chunksUntilStopped <= 15,
                "Menu fade should complete smoothly right under 1.0s (~13 chunks), got chunks=" + chunksUntilStopped);
        assertTrue(earlyFadeRms > lateFadeRms * 2.0,
                "Homepage BGM must fade down smoothly: early=" + earlyFadeRms + " late=" + lateFadeRms);
        assertEquals(HabiBootAudioEngine.State.STOPPED, engine.currentStateForTest(),
                "Engine must reach STOPPED immediately once 1s menu fade completes");
        assertNull(engine.pullOpenAlChunkForTest(), "No tunnel music may play after menu fade finishes");

        // 3) Direct world entry (no homepage BGM playing) plays zero music immediately
        engine.startTunnelEntry(false);
        assertEquals(HabiBootAudioEngine.State.STOPPED, engine.currentStateForTest(),
                "Direct world entry without active menu BGM must stay completely silent");
        assertNull(engine.pullOpenAlChunkForTest(), "Direct world entry must not produce tunnel BGM audio");
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
