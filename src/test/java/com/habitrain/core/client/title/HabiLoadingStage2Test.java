package com.habitrain.core.client.title;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HabiLoadingStage2Test {

    @AfterEach
    void tearDown() {
        HabiLoadingPerfMonitor.getInstance().resetForTest();
        HabiWorldEntrySession.getInstance().resetForTest();
        HabiLoadingFramePumper.resetRenderDepthForTest();
    }

    @Test
    void framePumperPoliciesEnforceLoadingOnlyTimeSlicingAndAdaptiveDoWorldLoadSleep() {
        // Time-slicing & catch-up tick cap active ONLY when kinetic loading enabled AND boot overlay or world-entry screen active
        assertFalse(HabiLoadingFramePumper.shouldTimeSliceForState(false, true, true));
        assertFalse(HabiLoadingFramePumper.shouldTimeSliceForState(true, false, false));
        assertTrue(HabiLoadingFramePumper.shouldTimeSliceForState(true, true, false));
        assertTrue(HabiLoadingFramePumper.shouldTimeSliceForState(true, false, true));

        assertFalse(HabiLoadingFramePumper.shouldCapCatchupTicksForState(true, false, false));
        assertTrue(HabiLoadingFramePumper.shouldCapCatchupTicksForState(true, true, false));
        assertTrue(HabiLoadingFramePumper.shouldCapCatchupTicksForState(true, false, true));

        // Boot atlas/shader loop pumping MUST require mc.level == null (never sample partial atlas in-world!)
        assertTrue(HabiLoadingFramePumper.shouldPumpBootLoopForState(true, true, true, false, false));
        assertFalse(HabiLoadingFramePumper.shouldPumpBootLoopForState(true, true, false, false, false));
        assertFalse(HabiLoadingFramePumper.shouldPumpBootLoopForState(true, true, true, true, false));
        assertFalse(HabiLoadingFramePumper.shouldPumpBootLoopForState(true, true, true, false, true));

        // Pump interval gating
        long interval = HabiLoadingFramePumper.PUMP_INTERVAL_NANOS;
        assertTrue(HabiLoadingFramePumper.shouldPumpNow(1_000_000_000L, 0L, interval));
        assertFalse(HabiLoadingFramePumper.shouldPumpNow(1_010_000_000L, 1_000_000_000L, interval));
        assertTrue(HabiLoadingFramePumper.shouldPumpNow(1_016_000_000L, 1_000_000_000L, interval));

        // VSync ON (runTick(false) already took 16ms) -> 0ms extra sleep (eliminates 30 FPS double-sleep!)
        assertEquals(0L, HabiLoadingFramePumper.computeRemainingWorldLoadSleepMs(16L, 16L, 11L));
        assertEquals(0L, HabiLoadingFramePumper.computeRemainingWorldLoadSleepMs(16L, 11L, 11L));
        // VSync OFF (runTick(false) took 2ms) -> sleep 9ms (~90 FPS cap so server thread keeps CPU)
        assertEquals(9L, HabiLoadingFramePumper.computeRemainingWorldLoadSleepMs(16L, 2L, 11L));
        assertEquals(0L, HabiLoadingFramePumper.computeRemainingWorldLoadSleepMs(0L, 0L, 11L));

        // Nested GameRenderer.render() depth counter must NOT prematurely clear on inner exit
        HabiLoadingFramePumper.resetRenderDepthForTest();
        assertFalse(HabiLoadingFramePumper.isInGameRendererRender());
        HabiLoadingFramePumper.enterGameRendererRender();
        HabiLoadingFramePumper.enterGameRendererRender();
        assertTrue(HabiLoadingFramePumper.isInGameRendererRender());
        HabiLoadingFramePumper.exitGameRendererRender();
        assertTrue(HabiLoadingFramePumper.isInGameRendererRender(),
                "Inner GameRenderer exit must keep outer render guard active");
        HabiLoadingFramePumper.exitGameRendererRender();
        assertFalse(HabiLoadingFramePumper.isInGameRendererRender());
        HabiLoadingFramePumper.exitGameRendererRender(); // safe underflow clamp
        assertFalse(HabiLoadingFramePumper.isInGameRendererRender());
    }

    @Test
    void perfMonitorRecordsStage2PumpAndSliceCounters() {
        HabiLoadingPerfMonitor mon = HabiLoadingPerfMonitor.getInstance();
        mon.resetForTest();
        mon.beginSession(HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, "STMAP // LOCAL");
        mon.recordFrameAtNanos(HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, 1_000_000_000L,
                "START", 0.0, 0.0, 1.0, 0.0, 40, "TitleScreen");
        mon.recordTaskSliceYield();
        mon.recordTaskSliceYield();
        mon.recordManagedBlockPump();
        mon.recordManagedBlockPump();
        mon.recordManagedBlockPump();
        mon.recordWorldLoadSpinPump();
        mon.recordAtlasUploadPump();
        mon.recordAtlasUploadPump();
        mon.recordShaderReloadPump();
        mon.recordSlowTask("net.minecraft.client.renderer.texture.TextureAtlas$$Lambda/0x00001234", 412.5);
        mon.recordSlowTask("net.minecraft.client.multiplayer.ClientPacketListener", 15.0); // below 40ms threshold
        mon.recordWorldLoadSleepSaved(16L);
        mon.recordWorldLoadSleepSaved(14L);
        mon.recordFrameAtNanos(HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, 1_016_000_000L,
                "LOADING", 0.02, 0.5, 1.1, 0.0, 42, "TrainLoadingScreen");

        HabiLoadingPerfMonitor.SessionReport rep = mon.finishSessionAtNanos(
                HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, 1_032_000_000L, "completed", false);
        assertNotNull(rep);
        assertEquals(2, rep.taskSliceYields());
        assertEquals(3, rep.managedBlockPumps());
        assertEquals(1, rep.worldLoadSpinPumps());
        assertEquals(2, rep.atlasUploadPumps());
        assertEquals(1, rep.shaderReloadPumps());
        assertEquals(30L, rep.worldLoadSleepSavedMs());
        assertEquals(1, rep.topSlowTasks().size());
        assertEquals("TextureAtlas$$Lambda", rep.topSlowTasks().get(0).taskName());
        assertEquals(412.5, rep.topSlowTasks().get(0).durationMs(), 0.01);
        assertFalse(mon.hasAnyActiveSession());
    }

    @Test
    void worldEntryMessageRecognizesResourceLoadAndPrebakedSfxMatchesFullRenderBitExact() {
        assertTrue(HabiWorldEntrySession.isWorldEntryMessage(Component.translatable("selectWorld.resource_load")));
        assertTrue(HabiWorldEntrySession.isWorldEntryMessage(Component.translatable("selectWorld.data_read")));

        // Verify raw stereo -> gain scaling is bit-identical to full renderSfxPcm and vastly faster
        List<HabiBootAudioEngine.SfxSpec> specs = List.of(
                new HabiBootAudioEngine.SfxSpec(155.0, 34.0, 0.92, 0.38, HabiBootAudioEngine.Wave.SINE, 0.0, 0.0),
                new HabiBootAudioEngine.SfxSpec(440.0, 441.5, 1.15, 0.06, HabiBootAudioEngine.Wave.TRIANGLE, 0.2, 0.02)
        );
        HabiBootAudioEngine.NoiseBurstSpec noise = new HabiBootAudioEngine.NoiseBurstSpec(
                HabiBootAudioEngine.FilterMode.LOWPASS, 4600.0, 340.0, 0.88, 0.018, 0.92, 0.96, 0.28, 0.707);

        byte[] direct = HabiBootAudioEngine.renderSfxPcm(specs, noise, 0.75);
        float[] raw = HabiBootAudioEngine.renderRawSfxStereo(specs, noise);

        long t0 = System.nanoTime();
        byte[] scaled = HabiBootAudioEngine.renderScaledPcmFromRaw(raw, 0.75);
        double scaleMs = (System.nanoTime() - t0) / 1_000_000.0;

        assertArrayEquals(direct, scaled, "Pre-rendered scaled SFX PCM must match direct render bit-for-bit");
        assertTrue(scaleMs < 25.0, "Scaling prebaked SFX buffer should be sub-millisecond/fast, took " + scaleMs + "ms");

        // Ensure background prebake succeeds cleanly
        HabiBootAudioEngine.getInstance().prebakeTunnelSfxSync();
    }
}
