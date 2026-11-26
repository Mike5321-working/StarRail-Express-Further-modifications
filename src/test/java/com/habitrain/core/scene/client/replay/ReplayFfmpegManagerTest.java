package com.habitrain.core.scene.client.replay;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ReplayFfmpegManagerTest {

    @AfterEach
    void cleanup() {
        ReplayFfmpegManager.resetForTests();
    }

    @Test
    void detectsAllSupportedDesktopPlatformsAndBuildsChinaMirrorFallbackChain() {
        assertEquals(ReplayFfmpegManager.WIN_X64, ReplayFfmpegManager.detectPlatformAsset("Windows 11", "amd64"));
        assertEquals(ReplayFfmpegManager.WIN_X64, ReplayFfmpegManager.detectPlatformAsset("Windows 11", "aarch64"));
        assertEquals(ReplayFfmpegManager.MAC_ARM64, ReplayFfmpegManager.detectPlatformAsset("Mac OS X", "aarch64"));
        assertEquals(ReplayFfmpegManager.MAC_X64, ReplayFfmpegManager.detectPlatformAsset("Mac OS X", "x86_64"));
        assertEquals(ReplayFfmpegManager.LINUX_X64, ReplayFfmpegManager.detectPlatformAsset("Linux", "amd64"));
        assertEquals(ReplayFfmpegManager.LINUX_ARM64, ReplayFfmpegManager.detectPlatformAsset("Linux", "aarch64"));
        assertNull(ReplayFfmpegManager.detectPlatformAsset("Windows 10", "x86"));

        List<String> mirrors = ReplayFfmpegManager.mirrorUrls(ReplayFfmpegManager.WIN_X64);
        assertTrue(mirrors.size() >= 5, "Expected multi-mirror fallback chain");
        assertTrue(mirrors.getFirst().contains("npmmirror.com"), "China CDN mirror should be prioritized first");
        assertTrue(mirrors.getLast().startsWith("https://github.com/"), "Direct GitHub should be the final fallback");
    }

    @Test
    void installsWithMirrorFailoverRejectingHttpErrorsAndCorruptedSha256(@TempDir Path tempDir) throws Exception {
        byte[] payload = "fake-ffmpeg-binary-payload-v6.1.1".getBytes(StandardCharsets.UTF_8);
        byte[] validGz = gzip(payload);
        byte[] corruptGz = gzip("tampered-binary-payload".getBytes(StandardCharsets.UTF_8));
        String expectedSha256 = sha256(validGz);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mirror-500", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.createContext("/mirror-corrupt", ex -> {
            ex.sendResponseHeaders(200, corruptGz.length);
            ex.getResponseBody().write(corruptGz);
            ex.close();
        });
        server.createContext("/mirror-ok", ex -> {
            ex.sendResponseHeaders(200, validGz.length);
            ex.getResponseBody().write(validGz);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String base = "http://127.0.0.1:" + port;
            Path binDir = ReplayFfmpegManager.binDirectory(tempDir);
            ReplayFfmpegManager.PlatformAsset asset = new ReplayFfmpegManager.PlatformAsset(
                    "test-x64", "ffmpeg-test.gz", validGz.length, expectedSha256, ReplayFfmpegManager.isWindows());

            Path installed = ReplayFfmpegManager.installFromMirrors(
                    binDir,
                    asset,
                    List.of(base + "/mirror-500", base + "/mirror-corrupt", base + "/mirror-ok"),
                    candidate -> {
                        try {
                            return java.util.Arrays.equals(payload, Files.readAllBytes(candidate));
                        } catch (IOException e) {
                            return false;
                        }
                    });

            assertEquals(binDir.resolve(asset.executableName()), installed);
            assertArrayEquals(payload, Files.readAllBytes(installed));
            try (var stream = Files.list(binDir)) {
                assertEquals(List.of(installed), stream.toList(), "No temporary .part files should remain");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void probeRejectsNonExecutableFiles(@TempDir Path tempDir) throws Exception {
        Path bogus = tempDir.resolve("not-ffmpeg.exe");
        Files.writeString(bogus, "this is not an executable", StandardCharsets.UTF_8);
        assertFalse(ReplayFfmpegManager.probeExecutable(bogus));
    }

    @Test
    void stalledDownloadAbortsPromptlySoMirrorFailoverCanProceed(@TempDir Path tempDir) throws Exception {
        var releaseServer = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stalled", ex -> {
            ex.sendResponseHeaders(200, 1024 * 1024);
            ex.getResponseBody().write(new byte[]{1, 2, 3, 4});
            ex.getResponseBody().flush();
            try {
                releaseServer.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try { ex.close(); } catch (RuntimeException ignored) {}
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var client = java.net.http.HttpClient.newHttpClient();
            Path dest = tempDir.resolve("stalled.gz.part");
            long startNs = System.nanoTime();
            IOException ex = assertThrows(IOException.class, () -> ReplayFfmpegManager.downloadAndVerifyGzip(
                    client, "http://127.0.0.1:" + port + "/stalled", dest,
                    1024 * 1024, "0".repeat(64), java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250)));
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertTrue(ex.getMessage().contains("stalled"), "Expected stall message, got: " + ex.getMessage());
            assertTrue(elapsedMs < 3000, "Stalled download should abort well under 3s, took " + elapsedMs + " ms");
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
