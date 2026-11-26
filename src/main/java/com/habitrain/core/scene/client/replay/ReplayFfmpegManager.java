package com.habitrain.core.scene.client.replay;

import com.habitrain.core.client.config.ClientVisualPreferences;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * Manages discovery, multi-mirror auto-installation, SHA-256 verification and manual import of the
 * external FFmpeg binary stored under {@code <gameDir>/habitrain-replays/bin/}.
 */
final class ReplayFfmpegManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayFfmpegManager.class);
    static final String RELEASE_TAG = "b6.1.1";
    private static final String GITHUB_RELEASE_BASE =
            "https://github.com/eugeneware/ffmpeg-static/releases/download/" + RELEASE_TAG + "/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final long READ_STALL_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(25);
    private static final long PROBE_TIMEOUT_SECONDS = 20L;
    private static final long RECHECK_INTERVAL_NS = TimeUnit.SECONDS.toNanos(2);

    record PlatformAsset(String id, String fileName, long expectedBytes, String sha256, boolean windows) {
        String executableName() {
            return windows ? "ffmpeg.exe" : "ffmpeg";
        }
    }

    static final PlatformAsset WIN_X64 = new PlatformAsset(
            "win32-x64", "ffmpeg-win32-x64.gz", 29_581_307L,
            "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77", true);
    static final PlatformAsset MAC_ARM64 = new PlatformAsset(
            "darwin-arm64", "ffmpeg-darwin-arm64.gz", 19_246_198L,
            "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa", false);
    static final PlatformAsset MAC_X64 = new PlatformAsset(
            "darwin-x64", "ffmpeg-darwin-x64.gz", 25_296_431L,
            "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106", false);
    static final PlatformAsset LINUX_X64 = new PlatformAsset(
            "linux-x64", "ffmpeg-linux-x64.gz", 29_354_986L,
            "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa", false);
    static final PlatformAsset LINUX_ARM64 = new PlatformAsset(
            "linux-arm64", "ffmpeg-linux-arm64.gz", 25_568_691L,
            "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0", false);

    enum State {
        IDLE,
        DOWNLOADING,
        EXTRACTING,
        VERIFYING,
        READY,
        FAILED,
        CANCELLED,
        UNSUPPORTED_PLATFORM,
        IMPORTING,
        IMPORT_INVALID
    }

    private record ProbeCache(String fingerprint, @Nullable Path executable, long checkedAtNs) {}

    private static final Object LOCK = new Object();
    private static volatile State state = State.IDLE;
    private static volatile double progress;
    private static volatile int mirrorIndex;
    private static volatile int mirrorCount;
    private static volatile boolean cancelRequested;
    private static volatile @Nullable Thread activeWorker;
    private static volatile boolean probing;
    private static volatile @Nullable ProbeCache cachedProbe;

    private ReplayFfmpegManager() {}

    static Path binDirectory(Path gameDir) {
        return gameDir.resolve("habitrain-replays").resolve("bin").toAbsolutePath().normalize();
    }

    static Path managedExecutable(Path gameDir) {
        return binDirectory(gameDir).resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static @Nullable PlatformAsset detectPlatformAsset() {
        return detectPlatformAsset(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static @Nullable PlatformAsset detectPlatformAsset(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        boolean isArm64 = arch.contains("aarch64") || arch.contains("arm64");
        boolean isX64 = arch.contains("64") && !isArm64;

        if (os.contains("win")) {
            // Windows ARM64 runs x64 binaries seamlessly via Prism emulation.
            return (isX64 || isArm64) ? WIN_X64 : null;
        }
        if (os.contains("mac") || os.contains("darwin") || os.contains("osx")) {
            if (isArm64) return MAC_ARM64;
            if (isX64) return MAC_X64;
            return null;
        }
        if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            if (isArm64) return LINUX_ARM64;
            if (isX64) return LINUX_X64;
            return null;
        }
        return null;
    }

    /** Multi-public China mirror fallback chain followed by direct GitHub release fallback. */
    static List<String> mirrorUrls(PlatformAsset asset) {
        String file = asset.fileName();
        String github = GITHUB_RELEASE_BASE + file;
        return List.of(
                "https://cdn.npmmirror.com/binaries/ffmpeg-static/" + RELEASE_TAG + "/" + file,
                "https://registry.npmmirror.com/-/binary/ffmpeg-static/" + RELEASE_TAG + "/" + file,
                "https://gh-proxy.com/" + github,
                "https://ghproxy.net/" + github,
                "https://ghfast.top/" + github,
                github
        );
    }

    static State state() {
        return state;
    }

    static double progress() {
        return progress;
    }

    static boolean isInstalling() {
        State current = state;
        return current == State.DOWNLOADING || current == State.EXTRACTING || current == State.VERIFYING;
    }

    static boolean isImporting() {
        return state == State.IMPORTING;
    }

    static boolean isBusy() {
        return isInstalling() || isImporting();
    }

    static Component statusText() {
        return switch (state) {
            case DOWNLOADING -> ReplayBoardTexts.tr("export.ffmpeg_downloading",
                    Math.max(1, mirrorIndex), Math.max(1, mirrorCount), (int) Math.round(progress * 100.0));
            case EXTRACTING -> ReplayBoardTexts.tr("export.ffmpeg_extracting");
            case VERIFYING -> ReplayBoardTexts.tr("export.ffmpeg_verifying");
            case READY -> ReplayBoardTexts.tr("export.ffmpeg_ready");
            case FAILED -> ReplayBoardTexts.tr("export.ffmpeg_failed");
            case CANCELLED -> ReplayBoardTexts.tr("export.ffmpeg_cancelled");
            case UNSUPPORTED_PLATFORM -> ReplayBoardTexts.tr("export.ffmpeg_unsupported");
            case IMPORTING -> ReplayBoardTexts.tr("export.ffmpeg_importing");
            case IMPORT_INVALID -> ReplayBoardTexts.tr("export.ffmpeg_import_invalid");
            case IDLE -> ReplayBoardTexts.tr("export.ffmpeg_missing");
        };
    }

    static boolean isProbing() {
        synchronized (LOCK) {
            return probing && cachedProbe == null;
        }
    }

    /**
     * Non-blocking check for UI screens: returns the cached verified executable if known, and triggers
     * a single background probe whenever the configured path or managed bin directory changes.
     */
    static @Nullable Path peekAvailable(Path gameDir) {
        String custom = readCustomPathSafely();
        String fingerprint = candidateFingerprint(gameDir, custom);
        ProbeCache cache = cachedProbe;
        if (cache != null && cache.fingerprint().equals(fingerprint)) {
            if (cache.executable() != null) {
                if (Files.isRegularFile(cache.executable())) return cache.executable();
                cachedProbe = null;
            } else if (System.nanoTime() - cache.checkedAtNs() < RECHECK_INTERVAL_NS) {
                return null;
            }
        }
        triggerAsyncProbe(gameDir, custom, fingerprint);
        return cache != null && cache.executable() != null && Files.isRegularFile(cache.executable())
                ? cache.executable() : null;
    }

    static boolean isReady(Path gameDir) {
        return peekAvailable(gameDir) != null;
    }

    /** Synchronous lookup used right before starting an export or in tests. */
    static @Nullable Path resolveAvailable(Path gameDir) {
        String custom = readCustomPathSafely();
        return resolveAvailable(gameDir, custom);
    }

    static @Nullable Path resolveAvailable(@Nullable Path gameDir, @Nullable String customPath) {
        String fingerprint = candidateFingerprint(gameDir, customPath);
        ProbeCache cache = cachedProbe;
        if (cache != null && cache.fingerprint().equals(fingerprint) && cache.executable() != null
                && Files.isRegularFile(cache.executable())) {
            return cache.executable();
        }
        Path resolved = findValidExecutable(gameDir, customPath);
        cachedProbe = new ProbeCache(fingerprint, resolved, System.nanoTime());
        return resolved;
    }

    static void invalidateCache() {
        cachedProbe = null;
    }

    private static void triggerAsyncProbe(Path gameDir, String customPath, String fingerprint) {
        synchronized (LOCK) {
            if (probing) return;
            probing = true;
        }
        Thread.ofPlatform().daemon().name("habitrain-ffmpeg-probe").start(() -> {
            try {
                Path found = findValidExecutable(gameDir, customPath);
                cachedProbe = new ProbeCache(fingerprint, found, System.nanoTime());
                if (found != null && (state == State.FAILED || state == State.CANCELLED
                        || state == State.IMPORT_INVALID || state == State.UNSUPPORTED_PLATFORM)) {
                    state = State.READY;
                }
            } finally {
                synchronized (LOCK) {
                    probing = false;
                }
            }
        });
    }

    private static @Nullable Path findValidExecutable(@Nullable Path gameDir, @Nullable String customPath) {
        List<Path> candidates = new ArrayList<>();
        if (customPath != null && !customPath.isBlank()) {
            try {
                candidates.add(Path.of(customPath.trim()).toAbsolutePath().normalize());
            } catch (RuntimeException ignored) {}
        }
        if (gameDir != null) {
            candidates.add(managedExecutable(gameDir));
        }
        candidates.addAll(findInSystemPath());
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate) && probeExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static List<Path> findInSystemPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) return List.of();
        boolean win = isWindows();
        String[] names = win ? new String[]{"ffmpeg.exe", "ffmpeg.bat", "ffmpeg.cmd"} : new String[]{"ffmpeg"};
        List<Path> found = new ArrayList<>();
        for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) continue;
            String trimmed = entry.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            try {
                Path dir = Path.of(trimmed);
                for (String name : names) {
                    Path candidate = dir.resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        found.add(candidate.toAbsolutePath().normalize());
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        return found;
    }

    /** Verifies the executable runs as FFmpeg and supports both libx264 and aac encoders. */
    static boolean probeExecutable(Path executable) {
        if (executable == null || !Files.isRegularFile(executable)) return false;
        Process process = null;
        var output = new java.util.concurrent.atomic.AtomicReference<>("");
        Thread drain = null;
        try {
            process = new ProcessBuilder(executable.toAbsolutePath().toString(), "-hide_banner", "-encoders")
                    .redirectErrorStream(true)
                    .start();
            Process running = process;
            drain = Thread.ofPlatform().daemon().name("habitrain-ffmpeg-probe-output").start(() -> {
                try {
                    output.set(readProcessOutputBounded(running.getInputStream(), 256 * 1024));
                } catch (IOException ignored) {
                    output.set("");
                }
            });
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return false;
            }
            if (drain != null) drain.join(1000);
            if (process.exitValue() != 0) return false;
            String text = output.get();
            boolean hasX264 = text.contains("libx264");
            boolean hasAac = text.contains(" aac ") || text.contains(" aac\n") || text.contains(" aac\r");
            return hasX264 && hasAac && probeEncodingPipeline(executable);
        } catch (IOException | RuntimeException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (drain != null && drain.isAlive()) drain.interrupt();
        }
    }

    /** Runs a tiny 1-frame dry-run to confirm required filters and libx264 options work before export starts. */
    private static boolean probeEncodingPipeline(Path executable) {
        Process process = null;
        Thread drain = null;
        try {
            process = new ProcessBuilder(
                    executable.toAbsolutePath().toString(),
                    "-hide_banner", "-loglevel", "error",
                    "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", "16x16", "-r", "30", "-i", "pipe:0",
                    "-vf", "vflip,scale=in_range=full:out_color_matrix=bt709:out_range=tv",
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "23",
                    "-aq-mode", "3", "-aq-strength", "0.85",
                    "-pix_fmt", "yuv420p", "-frames:v", "1", "-f", "null", "-"
            ).redirectErrorStream(true).start();
            Process running = process;
            drain = Thread.ofPlatform().daemon().name("habitrain-ffmpeg-pipeline-probe").start(() -> {
                try {
                    readProcessOutputBounded(running.getInputStream(), 16 * 1024);
                } catch (IOException ignored) {}
            });
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(new byte[16 * 16 * 3]);
                stdin.flush();
            } catch (IOException ignored) {}
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return false;
            }
            if (drain != null) drain.join(1000);
            return process.exitValue() == 0;
        } catch (IOException | RuntimeException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (drain != null && drain.isAlive()) drain.interrupt();
        }
    }

    static boolean startInstall(Path gameDir) {
        PlatformAsset asset = detectPlatformAsset();
        if (asset == null) {
            state = State.UNSUPPORTED_PLATFORM;
            return false;
        }
        synchronized (LOCK) {
            if (isBusy()) return false;
            cancelRequested = false;
            progress = 0.0;
            mirrorIndex = 0;
            mirrorCount = mirrorUrls(asset).size();
            state = State.DOWNLOADING;
            Thread worker = Thread.ofPlatform().daemon().name("habitrain-ffmpeg-install").unstarted(() -> {
                try {
                    Path installed = installFromMirrors(
                            binDirectory(gameDir), asset, mirrorUrls(asset), ReplayFfmpegManager::probeExecutable);
                    cachedProbe = new ProbeCache(
                            candidateFingerprint(gameDir, readCustomPathSafely()), installed, System.nanoTime());
                    progress = 1.0;
                    state = State.READY;
                    LOGGER.info("Installed FFmpeg {} to {}", RELEASE_TAG, installed);
                } catch (CancelledException cancelled) {
                    state = State.CANCELLED;
                    progress = 0.0;
                    LOGGER.info("FFmpeg installation cancelled");
                } catch (IOException | RuntimeException failure) {
                    state = State.FAILED;
                    progress = 0.0;
                    LOGGER.warn("Could not install FFmpeg automatically", failure);
                } finally {
                    synchronized (LOCK) {
                        activeWorker = null;
                    }
                }
            });
            activeWorker = worker;
            worker.start();
            return true;
        }
    }

    static void cancelInstall() {
        Thread worker;
        synchronized (LOCK) {
            cancelRequested = true;
            worker = activeWorker;
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    static boolean startManualImport(Path gameDir) {
        synchronized (LOCK) {
            if (isBusy()) return false;
            state = State.IMPORTING;
            Thread worker = Thread.ofPlatform().daemon().name("habitrain-ffmpeg-import").unstarted(() -> {
                try {
                    String selected = openNativeFileDialog(gameDir);
                    if (selected == null || selected.isBlank()) {
                        Path existing = resolveAvailable(gameDir);
                        state = existing != null ? State.READY : State.IDLE;
                        return;
                    }
                    Path chosen = Path.of(selected.trim()).toAbsolutePath().normalize();
                    if (!probeExecutable(chosen)) {
                        state = State.IMPORT_INVALID;
                        LOGGER.warn("Selected file is not a valid FFmpeg binary with libx264 & aac: {}", chosen);
                        return;
                    }
                    ClientVisualPreferences.setReplayVideoFfmpegPath(chosen.toString());
                    invalidateCache();
                    cachedProbe = new ProbeCache(
                            candidateFingerprint(gameDir, chosen.toString()), chosen, System.nanoTime());
                    state = State.READY;
                    LOGGER.info("Configured custom FFmpeg executable: {}", chosen);
                } catch (Throwable failure) {
                    LOGGER.warn("Could not import FFmpeg via file chooser; falling back to opening bin folder", failure);
                    openBinFolder(gameDir);
                    state = State.IDLE;
                } finally {
                    synchronized (LOCK) {
                        activeWorker = null;
                    }
                }
            });
            activeWorker = worker;
            worker.start();
            return true;
        }
    }

    static boolean openBinFolder(Path gameDir) {
        Path dir = binDirectory(gameDir);
        try {
            ReplayFolderOpener.open(dir);
            invalidateCache();
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            LOGGER.warn("Could not open FFmpeg bin directory: {}", dir, failure);
            return false;
        }
    }

    private static @Nullable String openNativeFileDialog(Path gameDir) {
        String defaultPath = binDirectory(gameDir).toString() + java.io.File.separator;
        boolean win = isWindows();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = null;
            String filterDesc = null;
            if (win) {
                filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.exe"));
                filters.flip();
                filterDesc = "FFmpeg executable (ffmpeg.exe)";
            }
            return TinyFileDialogs.tinyfd_openFileDialog(
                    "Select FFmpeg Executable",
                    defaultPath,
                    filters,
                    filterDesc,
                    false
            );
        }
    }

    /**
     * Downloads, SHA-256 validates, decompresses, probes and atomically installs FFmpeg from the
     * supplied mirror chain. Package-private so tests can exercise mirror failover deterministically.
     */
    static Path installFromMirrors(Path binDir, PlatformAsset asset, List<String> mirrors,
                                   Predicate<Path> validator) throws IOException {
        if (mirrors.isEmpty()) throw new IOException("No FFmpeg download mirrors configured");
        Files.createDirectories(binDir);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Path gzTemp = Files.createTempFile(binDir, ".ffmpeg-download-", ".gz.part");
        Path binTemp = Files.createTempFile(binDir, ".ffmpeg-extract-", ".bin.part");
        IOException lastError = null;
        try {
            boolean downloaded = false;
            mirrorCount = mirrors.size();
            for (int i = 0; i < mirrors.size(); i++) {
                checkCancelled();
                mirrorIndex = i + 1;
                progress = 0.0;
                String url = mirrors.get(i);
                try {
                    downloadAndVerifyGzip(client, url, gzTemp, asset.expectedBytes(), asset.sha256());
                    downloaded = true;
                    break;
                } catch (CancelledException cancelled) {
                    throw cancelled;
                } catch (IOException failure) {
                    lastError = failure;
                    LOGGER.warn("FFmpeg mirror {}/{} failed ({}): {}", i + 1, mirrors.size(), url, failure.getMessage());
                }
            }
            if (!downloaded) {
                throw lastError != null ? lastError : new IOException("All FFmpeg mirrors failed");
            }

            checkCancelled();
            state = State.EXTRACTING;
            progress = 0.90;
            decompressGzip(gzTemp, binTemp);
            if (!asset.windows()) {
                makeExecutable(binTemp);
            }

            checkCancelled();
            state = State.VERIFYING;
            progress = 0.96;
            if (!validator.test(binTemp)) {
                throw new IOException("Extracted FFmpeg binary failed self-check");
            }

            Path destination = binDir.resolve(asset.executableName());
            moveReplacing(binTemp, destination);
            if (!asset.windows()) {
                makeExecutable(destination);
            }
            return destination;
        } finally {
            deleteQuietly(gzTemp);
            deleteQuietly(binTemp);
        }
    }

    private static void downloadAndVerifyGzip(HttpClient client, String url, Path dest,
                                              long fallbackExpectedBytes, String expectedSha256) throws IOException {
        downloadAndVerifyGzip(client, url, dest, fallbackExpectedBytes, expectedSha256, READ_STALL_TIMEOUT_NS);
    }

    static void downloadAndVerifyGzip(HttpClient client, String url, Path dest,
                                      long fallbackExpectedBytes, String expectedSha256,
                                      long readStallTimeoutNs) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "HabiTrainCore-FFmpegInstaller/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            try (InputStream ignored = response.body()) {}
            throw new IOException("HTTP " + status);
        }
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(fallbackExpectedBytes);
        MessageDigest digest = sha256Digest();
        long received = 0L;
        byte[] buffer = new byte[65536];
        var lastActivityNs = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        var stalled = new java.util.concurrent.atomic.AtomicBoolean();
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        long effectiveStallNs = Math.max(TimeUnit.MILLISECONDS.toNanos(100), readStallTimeoutNs);
        try (InputStream raw = response.body();
             DigestInputStream in = new DigestInputStream(new BufferedInputStream(raw, 65536), digest);
             OutputStream out = Files.newOutputStream(dest)) {
            Thread watchdog = Thread.ofPlatform().daemon().name("habitrain-ffmpeg-stall-guard").start(() -> {
                while (!done.get()) {
                    long elapsed = System.nanoTime() - lastActivityNs.get();
                    if (elapsed >= effectiveStallNs) {
                        stalled.set(true);
                        try { raw.close(); } catch (IOException ignored) {}
                        return;
                    }
                    long sleepMs = Math.clamp(TimeUnit.NANOSECONDS.toMillis(effectiveStallNs - elapsed), 25L, 500L);
                    try { Thread.sleep(sleepMs); }
                    catch (InterruptedException ignored) { return; }
                }
            });
            try {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    lastActivityNs.set(System.nanoTime());
                    checkCancelled();
                    out.write(buffer, 0, read);
                    received += read;
                    if (contentLength > 0) {
                        progress = Math.min(0.88, (received / (double) contentLength) * 0.88);
                    }
                }
            } catch (IOException readFailure) {
                if (stalled.get()) {
                    throw new IOException("Download stalled for "
                            + TimeUnit.NANOSECONDS.toMillis(effectiveStallNs) + " ms", readFailure);
                }
                throw readFailure;
            } finally {
                done.set(true);
                watchdog.interrupt();
            }
        }
        if (stalled.get()) {
            throw new IOException("Download stalled for " + TimeUnit.NANOSECONDS.toMillis(effectiveStallNs) + " ms");
        }
        if (received <= 0) {
            throw new IOException("Empty download response");
        }
        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("SHA-256 mismatch: expected " + expectedSha256 + ", got " + actualSha256);
        }
    }

    private static void decompressGzip(Path gzSource, Path dest) throws IOException {
        byte[] buffer = new byte[65536];
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(gzSource), 65536), 65536);
             OutputStream out = Files.newOutputStream(dest)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkCancelled();
                out.write(buffer, 0, read);
            }
        }
    }

    private static void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            file.toFile().setExecutable(true, false);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void checkCancelled() {
        if (cancelRequested || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String readProcessOutputBounded(InputStream in, int maxBytes) throws IOException {
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 8192));
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (total < maxBytes) {
                int take = Math.min(n, maxBytes - total);
                out.write(buf, 0, take);
                total += take;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String candidateFingerprint(@Nullable Path gameDir, @Nullable String customPath) {
        StringBuilder sb = new StringBuilder();
        sb.append(customPath == null ? "" : customPath.trim()).append('|');
        if (customPath != null && !customPath.isBlank()) {
            appendFileStamp(sb, customPath.trim());
        }
        sb.append('|');
        if (gameDir != null) {
            Path managed = managedExecutable(gameDir);
            sb.append(managed).append(':');
            appendFileStamp(sb, managed);
        }
        return sb.toString();
    }

    private static void appendFileStamp(StringBuilder sb, String rawPath) {
        try {
            appendFileStamp(sb, Path.of(rawPath));
        } catch (RuntimeException ignored) {
            sb.append("invalid");
        }
    }

    private static void appendFileStamp(StringBuilder sb, Path path) {
        try {
            if (Files.isRegularFile(path)) {
                sb.append(Files.size(path)).append('@').append(Files.getLastModifiedTime(path).toMillis());
            } else {
                sb.append("missing");
            }
        } catch (IOException ignored) {
            sb.append("err");
        }
    }

    private static String readCustomPathSafely() {
        try {
            return ClientVisualPreferences.getReplayVideoFfmpegPath();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void deleteQuietly(@Nullable Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    static void resetForTests() {
        synchronized (LOCK) {
            cancelRequested = false;
            activeWorker = null;
            probing = false;
            cachedProbe = null;
            progress = 0.0;
            mirrorIndex = 0;
            mirrorCount = 0;
            state = State.IDLE;
        }
    }

    private static final class CancelledException extends RuntimeException {}
}
