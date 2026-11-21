package com.habitrain.core.scene.client.replay;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * External FFmpeg H.264 (+ optional AAC-LC stereo audio) / MP4 writer.
 *
 * <p>Streams OpenGL's bottom-up {@code bgr24} frames directly into FFmpeg's {@code stdin} with SIMD
 * vertical flip and BT.709 color-space conversion, while passing pre-mixed 48 kHz 16-bit stereo PCM
 * via a temporary file to prevent dual-pipe deadlocks and maintain exact A/V sync.</p>
 */
final class ReplayMp4Writer implements ReplayVideoWriter {
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int MAX_LOG_BYTES = 16 * 1024;
    private static final long FINISH_TIMEOUT_SECONDS = 60L;

    private final Process process;
    private final OutputStream videoOut;
    private final Thread logDrainThread;
    private final ByteArrayOutputStream logBuffer = new ByteArrayOutputStream(1024);
    private final @Nullable Path tempAudioPcm;
    private final int expectedBytes;
    private boolean finished;
    private boolean closed;

    ReplayMp4Writer(Path ffmpegExecutable, Path path, int width, int height, int fps) throws IOException {
        this(ffmpegExecutable, path, width, height, fps, null);
    }

    ReplayMp4Writer(Path ffmpegExecutable, Path path, int width, int height, int fps,
                    @Nullable short[] soundtrackStereo48k) throws IOException {
        Objects.requireNonNull(ffmpegExecutable, "ffmpegExecutable");
        Objects.requireNonNull(path, "path");
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("MP4 dimensions must be positive even integers: " + width + "x" + height);
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("FPS must be positive: " + fps);
        }
        this.expectedBytes = Math.multiplyExact(Math.multiplyExact(width, height), 3);

        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path audioTemp = null;
        Process started = null;
        try {
            if (soundtrackStereo48k != null && soundtrackStereo48k.length >= AUDIO_CHANNELS) {
                audioTemp = writeTempPcm(parent != null ? parent : Path.of("."), soundtrackStereo48k);
            }
            List<String> cmd = buildCommand(ffmpegExecutable, path, width, height, fps, audioTemp);
            started = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            this.tempAudioPcm = audioTemp;
            this.process = started;
            this.videoOut = new BufferedOutputStream(started.getOutputStream(), 256 * 1024);
            this.logDrainThread = Thread.ofPlatform().daemon().name("habitrain-ffmpeg-log").start(this::drainOutput);
        } catch (IOException | RuntimeException | Error failure) {
            if (started != null) {
                started.destroyForcibly();
            }
            deleteQuietly(audioTemp);
            throw failure;
        }
    }

    static List<String> buildCommand(Path ffmpegExecutable, Path outputPath, int width, int height, int fps,
                                     @Nullable Path audioPcmFile) {
        List<String> cmd = new ArrayList<>(36);
        cmd.add(ffmpegExecutable.toAbsolutePath().toString());
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-f");
        cmd.add("rawvideo");
        cmd.add("-pix_fmt");
        cmd.add("bgr24");
        cmd.add("-s");
        cmd.add(width + "x" + height);
        cmd.add("-r");
        cmd.add(Integer.toString(fps));
        cmd.add("-i");
        cmd.add("pipe:0");
        if (audioPcmFile != null) {
            cmd.add("-f");
            cmd.add("s16le");
            cmd.add("-ar");
            cmd.add(Integer.toString(AUDIO_SAMPLE_RATE));
            cmd.add("-ac");
            cmd.add(Integer.toString(AUDIO_CHANNELS));
            cmd.add("-i");
            cmd.add(audioPcmFile.toAbsolutePath().toString());
        }
        cmd.add("-vf");
        cmd.add("vflip,scale=in_range=full:out_color_matrix=bt709:out_range=tv");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add(width >= 2560 ? "fast" : "medium");
        cmd.add("-crf");
        cmd.add("18");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-colorspace");
        cmd.add("bt709");
        cmd.add("-color_primaries");
        cmd.add("bt709");
        cmd.add("-color_trc");
        cmd.add("bt709");
        cmd.add("-color_range");
        cmd.add("tv");
        if (audioPcmFile != null) {
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("192k");
            cmd.add("-shortest");
        }
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add("-f");
        cmd.add("mp4");
        cmd.add(outputPath.toAbsolutePath().toString());
        return cmd;
    }

    private static Path writeTempPcm(Path directory, short[] interleavedStereo48k) throws IOException {
        Path temp = Files.createTempFile(directory, ".replay-audio-", ".pcm");
        int sampleCount = interleavedStereo48k.length & ~1;
        byte[] chunk = new byte[65536];
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp), 65536)) {
            int offset = 0;
            while (offset < sampleCount) {
                int batchSamples = Math.min(chunk.length / 2, sampleCount - offset);
                for (int i = 0; i < batchSamples; i++) {
                    short s = interleavedStereo48k[offset + i];
                    chunk[i * 2] = (byte) (s & 0xFF);
                    chunk[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
                }
                out.write(chunk, 0, batchSamples * 2);
                offset += batchSamples;
            }
        } catch (IOException | RuntimeException | Error failure) {
            deleteQuietly(temp);
            throw failure;
        }
        return temp;
    }

    private void drainOutput() {
        byte[] buf = new byte[4096];
        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                synchronized (logBuffer) {
                    int remaining = MAX_LOG_BYTES - logBuffer.size();
                    if (remaining > 0) {
                        logBuffer.write(buf, 0, Math.min(n, remaining));
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private String capturedLog() {
        synchronized (logBuffer) {
            return logBuffer.toString(StandardCharsets.UTF_8).trim();
        }
    }

    @Override
    public void add(byte[] bottomUpBgr) throws IOException {
        if (bottomUpBgr.length != expectedBytes) {
            throw new IOException("Unexpected video frame size: " + bottomUpBgr.length + " != " + expectedBytes);
        }
        try {
            videoOut.write(bottomUpBgr);
        } catch (IOException writeFailure) {
            waitBrieflyForLog();
            String details = capturedLog();
            if (!details.isEmpty()) {
                throw new IOException("FFmpeg pipe failed: " + details, writeFailure);
            }
            throw writeFailure;
        }
    }

    @Override
    public void finish() throws IOException {
        if (finished) return;
        finished = true;
        try {
            videoOut.flush();
            videoOut.close();
        } catch (IOException closeFailure) {
            waitBrieflyForLog();
            String details = capturedLog();
            if (!details.isEmpty()) {
                throw new IOException("FFmpeg input close failed: " + details, closeFailure);
            }
            throw closeFailure;
        }
        int exitCode;
        try {
            if (!process.waitFor(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                throw new IOException("Timed out waiting for FFmpeg to finalize MP4");
            }
            exitCode = process.exitValue();
            logDrainThread.join(3000);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for FFmpeg to finalize MP4", interrupted);
        }
        if (exitCode != 0) {
            String details = capturedLog();
            throw new IOException("FFmpeg exited with code " + exitCode
                    + (details.isEmpty() ? "" : ": " + details));
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            try {
                videoOut.close();
            } catch (IOException ignored) {}
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                logDrainThread.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            deleteQuietly(tempAudioPcm);
        }
    }

    private void waitBrieflyForLog() {
        try {
            process.waitFor(1, TimeUnit.SECONDS);
            logDrainThread.join(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(@Nullable Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }
}
