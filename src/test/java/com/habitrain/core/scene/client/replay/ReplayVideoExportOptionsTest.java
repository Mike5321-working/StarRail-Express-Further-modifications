package com.habitrain.core.scene.client.replay;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.ColorUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReplayVideoExportOptionsTest {

    @Test
    void resolutionPresetsAreEven16By9AndCycleCleanly() {
        assertEquals(ReplayExportResolution.P720, ReplayExportResolution.fromId(null));
        assertEquals(ReplayExportResolution.P720, ReplayExportResolution.fromId(""));
        assertEquals(ReplayExportResolution.P720, ReplayExportResolution.fromId("unknown"));
        assertEquals(ReplayExportResolution.P1080, ReplayExportResolution.fromId("  1080P "));
        assertEquals(ReplayExportResolution.P2160, ReplayExportResolution.fromId("2160p"));

        ReplayExportResolution current = ReplayExportResolution.P480;
        for (int i = 0; i < ReplayExportResolution.values().length; i++) {
            assertEquals(0, current.width & 1, current + " width must be even for YUV420");
            assertEquals(0, current.height & 1, current + " height must be even for YUV420");
            assertEquals(current, current.next().previous());
            assertEquals(current, ReplayExportResolution.fromId(current.id));
            current = current.next();
        }
        assertEquals(ReplayExportResolution.P480, current);
        assertFalse(ReplayExportResolution.P1080.highLoad());
        assertTrue(ReplayExportResolution.P1440.highLoad());
        assertTrue(ReplayExportResolution.P2160.highLoad());
    }

    @Test
    void mp4WriterPreservesNonMacroblockDimensionsVerticalFlipAndMultiGopStream(@TempDir Path tempDir) throws Exception {
        // 854 is not divisible by 16 (53 * 16 + 6); exercises SPS cropping and multi-GOP (65 frames > KEY_INTERVAL=60).
        int w = ReplayExportResolution.P480.width;
        int h = ReplayExportResolution.P480.height;
        byte[] bottomUp = new byte[w * h * 3];
        // Bottom-up row (h - 1) is the TOP of the displayed image -> paint pure RED (B=0, G=0, R=240).
        for (int y = h - 16; y < h; y++) {
            int base = y * w * 3;
            for (int x = 0; x < w; x++) {
                bottomUp[base + x * 3] = 0;
                bottomUp[base + x * 3 + 1] = 0;
                bottomUp[base + x * 3 + 2] = (byte) 240;
            }
        }
        // Bottom-up row 0..15 is the BOTTOM of the displayed image -> paint pure BLUE (B=240, G=0, R=0).
        for (int y = 0; y < 16; y++) {
            int base = y * w * 3;
            for (int x = 0; x < w; x++) {
                bottomUp[base + x * 3] = (byte) 240;
                bottomUp[base + x * 3 + 1] = 0;
                bottomUp[base + x * 3 + 2] = 0;
            }
        }

        Path mp4 = tempDir.resolve("sample-480p.mp4");
        try (ReplayVideoWriter writer = new ReplayMp4Writer(mp4, w, h, 30)) {
            for (int f = 0; f < 65; f++) {
                writer.add(bottomUp);
            }
            writer.finish();
        }

        assertTrue(Files.size(mp4) > 512);
        Picture last = FrameGrab.getFrameFromFile(mp4.toFile(), 64);
        assertEquals(w, last.getCroppedWidth());
        assertEquals(h, last.getCroppedHeight());

        Picture rgb = Picture.create(last.getWidth(), last.getHeight(), ColorSpace.RGB);
        ColorUtil.getTransform(last.getColor(), ColorSpace.RGB).transform(last, rgb);
        byte[] plane = rgb.getPlaneData(0);
        int stride = rgb.getWidth() * 3;

        // Top row (y = 4, x = 100) must be predominantly red
        int topIdx = 4 * stride + 100 * 3;
        int topR = plane[topIdx] + 128;
        int topG = plane[topIdx + 1] + 128;
        int topB = plane[topIdx + 2] + 128;
        assertTrue(topR > 180 && topG < 50 && topB < 50,
                "Expected top band to be red after vertical flip, got RGB=(" + topR + "," + topG + "," + topB + ")");

        // Bottom row (y = h - 5, x = 100) must be predominantly blue
        int botIdx = (h - 5) * stride + 100 * 3;
        int botR = plane[botIdx] + 128;
        int botG = plane[botIdx + 1] + 128;
        int botB = plane[botIdx + 2] + 128;
        assertTrue(botB > 180 && botR < 50 && botG < 50,
                "Expected bottom band to be blue after vertical flip, got RGB=(" + botR + "," + botG + "," + botB + ")");
    }

    @Test
    void mp4CompressionSignificantlyShrinksVideoComparedToMotionJpegAvi(@TempDir Path tempDir) throws Exception {
        int w = 640, h = 360;
        byte[] frame = new byte[w * h * 3];
        Path mp4 = tempDir.resolve("compressed.mp4");
        Path avi = tempDir.resolve("uncompressed.avi");

        try (ReplayVideoWriter mp4Writer = new ReplayMp4Writer(mp4, w, h, 30);
             ReplayVideoWriter aviWriter = new ReplayAviWriter(avi, w, h, 30)) {
            for (int f = 0; f < 30; f++) {
                populateSceneFrame(frame, w, h, f);
                mp4Writer.add(frame);
                aviWriter.add(frame);
            }
            mp4Writer.finish();
            aviWriter.finish();
        }

        long mp4Bytes = Files.size(mp4);
        long aviBytes = Files.size(avi);
        assertTrue(mp4Bytes > 0 && aviBytes > 0);
        assertTrue(mp4Bytes * 3 < aviBytes,
                "Expected H.264 MP4 (" + mp4Bytes + " B) to be < 1/3 of MJPG AVI (" + aviBytes + " B)");
    }

    @Test
    void chromaticAberrationShiftScalesWithExportWidth() {
        byte[] bgr720 = new byte[1280 * 2 * 3];
        byte[] row720 = new byte[1280 * 3];
        bgr720[640 * 3 + 2] = (byte) 255; // single red spike at x = 640
        ReplayVideoExport.aberrate(bgr720, row720, 1280, 2, 1.0f);
        // At 1280 width, shift = 7 -> red channel at x = 633 reads from 633 + 7 = 640
        assertEquals((byte) 255, bgr720[(640 - 7) * 3 + 2]);

        byte[] bgr2560 = new byte[2560 * 2 * 3];
        byte[] row2560 = new byte[2560 * 3];
        bgr2560[1280 * 3 + 2] = (byte) 255;
        ReplayVideoExport.aberrate(bgr2560, row2560, 2560, 2, 1.0f);
        // At 2560 width (2x), shift = 14 -> proportional visual offset
        assertEquals((byte) 255, bgr2560[(1280 - 14) * 3 + 2]);
    }

    private static void populateSceneFrame(byte[] bgr, int w, int h, int frame) {
        int avatarX = 80 + frame * 5;
        for (int y = 0; y < h; y++) {
            int row = y * w * 3;
            for (int x = 0; x < w; x++) {
                int r = 10 + (y * 20) / h;
                int g = 16 + (y * 28) / h;
                int b = 38 + (y * 45) / h;
                int starHash = (x * 374761393) ^ (y * 668265263);
                if ((starHash & 0x1FF) == 1) {
                    r = 240; g = 235; b = 220;
                }
                int wx = x + (frame >> 1);
                if (x > w / 6 && x < w * 5 / 6 && y > h / 4 && y < h * 3 / 4) {
                    int tex = (((wx >> 1) * 1103515245) ^ ((y >> 1) * 12345)) & 0x3F;
                    boolean seam = ((y & 15) == 0) || ((wx & 31) == 0);
                    r = Math.clamp((seam ? 90 : 145) + (tex - 32), 0, 255);
                    g = Math.clamp((seam ? 62 : 104) + (tex - 32) * 2 / 3, 0, 255);
                    b = Math.clamp((seam ? 40 : 68) + (tex - 32) / 2, 0, 255);
                }
                if (x >= avatarX && x < avatarX + 28 && y >= 150 && y < 178) {
                    r = 230; g = 75; b = 80;
                }
                int idx = row + x * 3;
                bgr[idx] = (byte) b;
                bgr[idx + 1] = (byte) g;
                bgr[idx + 2] = (byte) r;
            }
        }
    }
}
