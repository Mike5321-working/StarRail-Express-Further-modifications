package com.habitrain.core.scene.client.replay;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

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
        Path ffmpeg = ReplayFfmpegManager.resolveAvailable(null, null);
        Assumptions.assumeTrue(ffmpeg != null, "FFmpeg is required to test external MP4 export");

        // 854 is not divisible by 16 (53 * 16 + 6); exercises SPS cropping and multi-GOP (65 frames).
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

        // Write to a .part path first (matching ReplayVideoExport's temp file flow) to verify explicit -f mp4.
        Path mp4 = tempDir.resolve("sample-480p.part");
        try (ReplayVideoWriter writer = new ReplayMp4Writer(ffmpeg, mp4, w, h, 30)) {
            for (int f = 0; f < 65; f++) {
                writer.add(bottomUp);
            }
            writer.finish();
        }

        assertTrue(Files.size(mp4) > 512);
        byte[] rgb = decodeSingleFrameRgb24(ffmpeg, mp4, 64);
        assertEquals(w * h * 3, rgb.length, "Decoded frame size should match 854x480 RGB24");

        int stride = w * 3;
        int topIdx = 4 * stride + 100 * 3;
        int topR = rgb[topIdx] & 0xFF;
        int topG = rgb[topIdx + 1] & 0xFF;
        int topB = rgb[topIdx + 2] & 0xFF;
        assertTrue(topR > 200 && topG < 30 && topB < 30,
                "Expected top band to be red after vertical flip, got RGB=(" + topR + "," + topG + "," + topB + ")");

        int botIdx = (h - 5) * stride + 100 * 3;
        int botR = rgb[botIdx] & 0xFF;
        int botG = rgb[botIdx + 1] & 0xFF;
        int botB = rgb[botIdx + 2] & 0xFF;
        assertTrue(botB > 200 && botR < 30 && botG < 30,
                "Expected bottom band to be blue after vertical flip, got RGB=(" + botR + "," + botG + "," + botB + ")");
    }

    @Test
    void mp4CompressionSignificantlyShrinksVideoAndMaintainsHighPsnrComparedToAvi(@TempDir Path tempDir) throws Exception {
        Path ffmpeg = ReplayFfmpegManager.resolveAvailable(null, null);
        Assumptions.assumeTrue(ffmpeg != null, "FFmpeg is required to test external MP4 export");

        int w = 640, h = 360;
        byte[] frame = new byte[w * h * 3];
        byte[] referenceFrame15 = new byte[w * h * 3];
        Path mp4 = tempDir.resolve("compressed.mp4");
        Path avi = tempDir.resolve("uncompressed.avi");

        try (ReplayVideoWriter mp4Writer = new ReplayMp4Writer(ffmpeg, mp4, w, h, 30);
             ReplayVideoWriter aviWriter = new ReplayAviWriter(avi, w, h, 30)) {
            for (int f = 0; f < 30; f++) {
                populateSceneFrame(frame, w, h, f);
                if (f == 15) System.arraycopy(frame, 0, referenceFrame15, 0, frame.length);
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

        byte[] decoded15 = decodeSingleFrameRgb24(ffmpeg, mp4, 15);
        assertEquals(w * h * 3, decoded15.length);
        double psnr = computeTopDownRgbVsBottomUpBgrPsnr(decoded15, referenceFrame15, w, h);
        assertTrue(psnr > 34.0, "Expected sharp H.264 output with PSNR > 34 dB, got " + psnr + " dB");
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

    private static byte[] decodeSingleFrameRgb24(Path ffmpeg, Path mp4, int frameIndex) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                ffmpeg.toAbsolutePath().toString(),
                "-hide_banner", "-loglevel", "error",
                "-i", mp4.toAbsolutePath().toString(),
                "-vf", "select=eq(n\\," + frameIndex + ")",
                "-vsync", "0",
                "-vframes", "1",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"
        ).start();
        byte[] rgb = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Timed out decoding video frame");
        assertEquals(0, process.exitValue(), "FFmpeg decode failed: " + new String(err, StandardCharsets.UTF_8));
        return rgb;
    }

    private static double computeTopDownRgbVsBottomUpBgrPsnr(byte[] topDownRgb, byte[] bottomUpBgr, int w, int h) {
        double mseSum = 0.0;
        int stride = w * 3;
        for (int y = 0; y < h; y++) {
            int rgbRow = y * stride;
            int bgrRow = (h - 1 - y) * stride;
            for (int x = 0; x < w; x++) {
                int r0 = topDownRgb[rgbRow + x * 3] & 0xFF;
                int g0 = topDownRgb[rgbRow + x * 3 + 1] & 0xFF;
                int b0 = topDownRgb[rgbRow + x * 3 + 2] & 0xFF;

                int b1 = bottomUpBgr[bgrRow + x * 3] & 0xFF;
                int g1 = bottomUpBgr[bgrRow + x * 3 + 1] & 0xFF;
                int r1 = bottomUpBgr[bgrRow + x * 3 + 2] & 0xFF;

                int dr = r0 - r1, dg = g0 - g1, db = b0 - b1;
                mseSum += dr * dr + dg * dg + db * db;
            }
        }
        double mse = mseSum / (w * h * 3.0);
        if (mse <= 1e-9) return 99.0;
        return 10.0 * Math.log10((255.0 * 255.0) / mse);
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
