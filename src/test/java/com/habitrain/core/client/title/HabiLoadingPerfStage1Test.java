package com.habitrain.core.client.title;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class HabiLoadingPerfStage1Test {

    @AfterEach
    void tearDown() {
        HabiLoadingPerfMonitor.getInstance().resetForTest();
    }

    @Test
    void perfMonitorComputesFpsPercentilesStuttersPhasesAndSpikes() {
        HabiLoadingPerfMonitor mon = HabiLoadingPerfMonitor.getInstance();
        mon.resetForTest();

        mon.recordAtlasCpuBake("KineticAtlas", "zero", 48.5, true);
        mon.recordAtlasUpload("KineticAtlas", "zero", 52, 4.2, 0.0);
        mon.beginSession(HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, "TestWorld // LOCAL");

        long t = 1_000_000_000L;
        // 100 smooth frames at 16ms in START
        for (int i = 0; i < 100; i++) {
            mon.recordFrameAtNanos(HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, t,
                    "START", i * 0.016, 0.05, 1.5, i == 0 ? 3.0 : 0.0, 28, "TitleScreen");
            t += 16_000_000L;
        }
        // Inject 3 stutter spikes in LOADING (120ms, 310ms, 640ms)
        t += 104_000_000L; // total 120ms gap
        mon.recordFrameAtNanos(HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, t,
                "LOADING", 0.50, 0.45, 2.1, 0.4, 34, "TrainLoadingScreen");
        t += 310_000_000L;
        mon.recordFrameAtNanos(HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, t,
                "LOADING", 0.55, 0.72, 2.4, 0.3, 36, "TrainLoadingScreen");
        t += 640_000_000L;
        mon.recordFrameAtNanos(HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, t,
                "END", 0.49, 1.0, 3.8, 0.0, 42, "InWorld");
        mon.recordDynamicTextBake(1.8);

        HabiLoadingPerfMonitor.SessionReport report = mon.finishSessionAtNanos(
                HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, t + 16_000_000L, "completed", false);
        assertNotNull(report);
        assertEquals(103, report.frames());
        assertEquals(3, report.stutters50Ms());
        assertEquals(3, report.stutters100Ms());
        assertEquals(2, report.stutters250Ms());
        assertEquals(1, report.stutters500Ms());
        assertEquals(640.0, report.maxFrameMs(), 0.5);
        assertEquals(16.0, report.p50FrameMs(), 0.5);
        assertTrue(report.onePercentLowFps() < 5.0,
                "1% low FPS should reflect worst 1% frames (~640ms/310ms), got " + report.onePercentLowFps());
        assertEquals(1, report.dynamicTextMisses());
        assertEquals(1.8, report.dynamicTextBakeMs(), 0.01);
        assertEquals(42, report.maxDrawCalls());
        assertTrue(report.atlasSummary().contains("KineticAtlas.zero:cpu=48.5ms(bg),up=4.2ms/52tex/1f"),
                "Unexpected atlas summary: " + report.atlasSummary());

        assertEquals(3, report.phases().size());
        assertEquals("START", report.phases().get(0).phase());
        assertEquals("LOADING", report.phases().get(1).phase());
        assertEquals("END", report.phases().get(2).phase());

        assertFalse(report.topSpikes().isEmpty());
        assertEquals(640.0, report.topSpikes().get(0).frameGapMs(), 0.5);
        assertEquals("InWorld", report.topSpikes().get(0).context());
    }

    @Test
    void cpuImageSwizzlesArgbToNativeRgbaBytesAndPreservesAlphaInSilhouette() {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        // Pixel 0: A=0x80, R=0x11, G=0x22, B=0x33
        img.setRGB(0, 0, 0x80112233);
        // Pixel 1: A=0xFF, R=0xAA, G=0xBB, B=0xCC
        img.setRGB(1, 0, 0xFFAABBCC);

        HabiKineticAtlas.CpuImage cpu = HabiKineticAtlas.CpuImage.fromBufferedImage(img, false);
        ByteBuffer nativeBuf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        nativeBuf.asIntBuffer().put(cpu.packedRgba);

        // OpenGL GL_RGBA + GL_UNSIGNED_BYTE reads sequential bytes [R, G, B, A]
        assertEquals((byte) 0x11, nativeBuf.get(0));
        assertEquals((byte) 0x22, nativeBuf.get(1));
        assertEquals((byte) 0x33, nativeBuf.get(2));
        assertEquals((byte) 0x80, nativeBuf.get(3));
        assertEquals((byte) 0xAA, nativeBuf.get(4));
        assertEquals((byte) 0xBB, nativeBuf.get(5));
        assertEquals((byte) 0xCC, nativeBuf.get(6));
        assertEquals((byte) 0xFF, nativeBuf.get(7));

        // Silhouette keeps exact alpha byte and replaces RGB with 0xE1243B
        HabiKineticAtlas.CpuImage sil = HabiKineticAtlas.CpuImage.silhouetteFrom(cpu, 0xE1243B);
        ByteBuffer silBuf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        silBuf.asIntBuffer().put(sil.packedRgba);
        assertEquals((byte) 0xE1, silBuf.get(0));
        assertEquals((byte) 0x24, silBuf.get(1));
        assertEquals((byte) 0x3B, silBuf.get(2));
        assertEquals((byte) 0x80, silBuf.get(3));
        assertEquals((byte) 0xE1, silBuf.get(4));
        assertEquals((byte) 0x24, silBuf.get(5));
        assertEquals((byte) 0x3B, silBuf.get(6));
        assertEquals((byte) 0xFF, silBuf.get(7));
    }

    @Test
    void headlessCpuRasterizationOfKineticAndTunnelAtlasesSucceedsWithoutOpenGl() {
        long t0 = System.nanoTime();
        HabiKineticAtlas.getInstance().prebakeCpuOnlyForTest();
        double kineticMs = (System.nanoTime() - t0) / 1_000_000.0;

        long t1 = System.nanoTime();
        HabiTunnelAtlas.getInstance().prebakeCpuOnlyForTest(HabiKineticAtlas.getInstance());
        double tunnelMs = (System.nanoTime() - t1) / 1_000_000.0;

        System.out.printf("[Stage1Bench] Headless CPU rasterization: KineticAtlas=%.1fms, TunnelAtlas=%.1fms%n",
                kineticMs, tunnelMs);
        assertTrue(kineticMs >= 0.0 && tunnelMs >= 0.0);
    }
}
