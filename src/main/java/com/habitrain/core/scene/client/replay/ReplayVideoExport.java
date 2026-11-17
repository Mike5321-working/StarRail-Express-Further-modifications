package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.render.ClientRenderState;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Renders the directed replay film one frame per GUI frame: designed background, the 3D diorama through
 * the virtual camera with video effects, then the 2D motion graphics. Only pixel data crosses into the
 * encoder thread, which adds the impact lens effect and writes either H.264 MP4 (compressed) or Motion-JPEG AVI.
 */
final class ReplayVideoExport {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayVideoExport.class);
    static final int FPS = 30;
    private static final long PREPARE_LIMIT_NS = TimeUnit.MINUTES.toNanos(2);

    final UUID round;
    final ReplayExportResolution resolution;
    final boolean compress;
    private final boolean fullBody;
    private final int frameBytes;
    private final long deadline = System.nanoTime() + PREPARE_LIMIT_NS;
    private ReplayBoardModel model;
    private ReplayVideoScript script;
    private ReplayVideoFx fx;
    private ReplayVideoOverlay overlay;
    private ReplayVideoCamera.Subjects subjects;
    private ReplayVideoCamera.Stage cameraStage;
    private ReplayDioramaMesh.Key meshKey;
    private TextureTarget target;
    private ByteBuffer pixels;
    private Encoder encoder;
    private int captured;
    private boolean failed;
    private boolean cancelled;

    ReplayVideoExport(UUID round, boolean fullBody) {
        this(round, fullBody, ReplayExportResolution.P720, true);
    }

    ReplayVideoExport(UUID round, boolean fullBody, ReplayExportResolution resolution, boolean compress) {
        this.round = round;
        this.fullBody = fullBody;
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.compress = compress;
        this.frameBytes = resolution.frameBytes();
    }

    static Path directory(Minecraft client) {
        return client.gameDirectory.toPath().resolve("habitrain-replays").toAbsolutePath().normalize();
    }

    static boolean openFolder(Minecraft client) {
        try {
            Util.getPlatform().openFile(Files.createDirectories(directory(client)).toFile());
            return true;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not open replay video directory", failure);
            return false;
        }
    }

    boolean preparing() { return encoder == null && running(); }
    boolean finalizing() { return encoder != null && captured >= encoder.frames && running(); }
    boolean running() { return !failed() && !cancelled && saved() == null; }
    boolean failed() { return failed || encoder != null && encoder.failed; }
    boolean cancelled() { return cancelled; }
    Path saved() { return encoder == null ? null : encoder.saved; }
    double progress() { return encoder == null ? 0 : Math.min(0.99, encoder.written / (double) encoder.frames); }
    double seconds() { return script == null ? 0 : script.total(); }

    void renderNext(Minecraft client, ClientReplayBoard.View view) {
        if (!running()) { releaseRenderResources(); return; }
        try {
            if (!round.equals(ClientReplayBoard.availableRound())) {
                cancel();
                return;
            }
            if (encoder == null) {
                if (System.nanoTime() - deadline >= 0) throw new IOException("Timed out preparing the replay map");
                if (view.replay() == null || view.timeline() == null || view.asset() == null ||
                        view.presentSections() < view.totalSections()) return;
                int maxTex = RenderSystem.maxSupportedTextureSize();
                if (resolution.width > maxTex || resolution.height > maxTex) {
                    throw new IOException("Export resolution " + resolution.dimensions()
                            + " exceeds GPU max texture size " + maxTex);
                }
                ReplayDioramaMesh.update(view.asset(), view.presentSections(), view.totalSections());
                var key = ReplayDioramaMesh.builtKey();
                if (key == null || key.present() != view.totalSections() || !ReplayDioramaMesh.matches(view.asset())) return;
                SceneMeshSet mesh = ReplayDioramaMesh.current();
                if (mesh == null) throw new IOException("Replay map mesh unavailable");
                meshKey = key;
                model = new ReplayBoardModel(view.replay(), view.timeline());
                script = new ReplayVideoScript(view.replay());
                fx = new ReplayVideoFx(script, model);
                overlay = new ReplayVideoOverlay(script, model, compress);
                subjects = new ModelSubjects(model);
                cameraStage = new ReplayVideoCamera.Stage(ReplayBoardRenderer.trayHalfSize(model), model.mapW, model.mapD, model.mapH,
                        (float) Math.max(model.mapH, mesh.replayCeiling() * model.scale));
                pixels = MemoryUtil.memAlloc(frameBytes);
                encoder = new Encoder(directory(client), round, resolution, compress, script.frames(FPS));
                encoder.thread.start();
            }

            if (captured >= encoder.frames) { releaseRenderResources(); return; }
            SceneMeshSet mesh = ReplayDioramaMesh.current();
            if (mesh == null || !meshKey.equals(ReplayDioramaMesh.builtKey())) {
                throw new IOException("Replay map unloaded during export");
            }
            VideoFrame frame = encoder.pool.poll();
            if (frame == null) return; // The encoder can never build an unbounded backlog.
            capture(client, mesh, captured, frame);
            if (!encoder.pending.offer(frame)) throw new IllegalStateException("Video frame queue full");
            captured++;
            if (captured >= encoder.frames) {
                releaseRenderResources();
            }
        } catch (IOException | RuntimeException failure) {
            failed = true;
            if (encoder != null) encoder.cancel();
            releaseRenderResources();
            LOGGER.warn("Replay video export failed", failure);
        }
    }

    private void capture(Minecraft client, SceneMeshSet mesh, int index, VideoFrame output) {
        int exportWidth = resolution.width;
        int exportHeight = resolution.height;
        double time = index / (double) FPS;
        var sample = script.sample(time);
        var stage = script.stage(sample);
        var frame = script.frame(sample);
        var shot = ReplayVideoCamera.at(script, subjects, cameraStage, time);
        fx.at(time, sample);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        int lightTexture = RenderSystem.getShaderTexture(2);
        int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        var projection = RenderSystem.getProjectionMatrix();
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack().pushMatrix();
        try (var state = ClientRenderState.capture()) {
            if (target == null) {
                target = new TextureTarget(exportWidth, exportHeight, true, Minecraft.ON_OSX);
                target.setClearColor(0.016f, 0.024f, 0.06f, 1);
            }
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            // Keep 2D motion graphics in the authored 1280x720 design coordinate space while
            // rendering into the native exportWidth x exportHeight framebuffer viewport.
            var canvas = new ReplayVideoCanvas(client, ReplayVideoCanvas.DESIGN_WIDTH, ReplayVideoCanvas.DESIGN_HEIGHT);
            canvas.begin();
            overlay.background(canvas, time, sample, shot);

            modelView.set(shot.view());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(shot.projection(), VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            FogRenderer.setupNoFog();
            client.gameRenderer.lightTexture().turnOnLightLayer();
            ReplayBoardRenderer.drawVideoFrame(client, model, frame, stage, sample.world(), shot, mesh, fullBody, fx);

            canvas.begin();
            overlay.foreground(canvas, time, sample, shot, index);
            RenderSystem.enableDepthTest();

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            pixels.clear();
            GL11.glReadPixels(0, 0, exportWidth, exportHeight, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, pixels);
            pixels.get(0, output.data);
            output.aberration = (float) overlay.aberration(time);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderTexture(2, lightTexture);
        }
    }

    void cancel() {
        if (running()) {
            cancelled = true;
            if (encoder != null) encoder.cancel();
        }
        releaseRenderResources();
    }

    private void releaseRenderResources() {
        if (target != null) {
            int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int removed = target.frameBufferId;
            try {
                target.destroyBuffers();
            } finally {
                target = null;
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw == removed ? 0 : draw);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read == removed ? 0 : read);
            }
        }
        if (pixels != null) { MemoryUtil.memFree(pixels); pixels = null; }
    }

    /** Board-frame positions of the match's subjects for the camera crew. */
    private static final class ModelSubjects implements ReplayVideoCamera.Subjects {
        private final ReplayBoardModel model;
        private final Map<LastMatchReplay.Node, ReplayBoardModel.EventMark> marks = new IdentityHashMap<>();
        private final float[] tmp = new float[3];

        ModelSubjects(ReplayBoardModel model) {
            this.model = model;
            for (var mark : model.events) marks.put(mark.node, mark);
        }

        private Vector3f board(double x, double y, double z) {
            model.project(x, y, z, tmp);
            return new Vector3f(tmp[0], ReplayBoardSite.MAP_BASE + tmp[1], tmp[2]);
        }

        @Override public Vector3f centroid(double tick) {
            float x = 0, y = 0, z = 0;
            int n = 0;
            for (var actor : model.actors) {
                var pose = actor.track.sample(tick);
                if (pose.state() != ReplayTrackSampler.State.ALIVE) continue;
                var p = board(pose.x(), pose.y(), pose.z());
                x += p.x; y += p.y; z += p.z; n++;
            }
            return n == 0 ? new Vector3f(0, ReplayBoardSite.MAP_BASE, 0) : new Vector3f(x / n, y / n, z / n);
        }

        @Override public @Nullable Vector3f actor(@Nullable UUID id, double tick) {
            var actor = model.participant(id);
            if (actor == null) return null;
            var pose = actor.track.sample(tick);
            return pose.visible() ? board(pose.x(), pose.y(), pose.z()) : null;
        }

        @Override public Vector3f event(LastMatchReplay.Node node, boolean to) {
            var mark = marks.get(node);
            if (mark != null) {
                return to ? new Vector3f(mark.tx, ReplayBoardSite.MAP_BASE + mark.ty, mark.tz)
                        : new Vector3f(mark.fx, ReplayBoardSite.MAP_BASE + mark.fy, mark.fz);
            }
            var actor = actor(to ? node.target() : node.actor(), node.tick());
            return actor != null ? actor : new Vector3f(0, ReplayBoardSite.MAP_BASE, 0);
        }

        @Override public @Nullable Vector3f mvp() {
            return model.mvp == null ? null : actor(model.mvp.p.id(), model.replay.durationTicks());
        }

        @Override public Vector3f winners() {
            float x = 0, z = 0;
            int n = 0;
            for (var actor : model.winners) {
                var p = actor(actor.p.id(), model.replay.durationTicks());
                if (p == null) continue;
                x += p.x; z += p.z; n++;
            }
            return n == 0 ? new Vector3f(0, ReplayBoardSite.MAP_BASE, 0) : new Vector3f(x / n, ReplayBoardSite.MAP_BASE, z / n);
        }
    }

    static final class VideoFrame {
        final byte[] data;
        volatile float aberration;

        VideoFrame(int frameBytes) {
            this.data = new byte[frameBytes];
        }
    }

    /** Lateral RGB split for impact frames, on bottom-up BGR rows. */
    static void aberrate(byte[] bgr, byte[] row, int width, int height, float strength) {
        int shift = Math.round(strength * 7f * width / ReplayVideoCanvas.DESIGN_WIDTH);
        if (shift <= 0) return;
        int stride = width * 3;
        for (int y = 0; y < height; y++) {
            int base = y * stride;
            System.arraycopy(bgr, base, row, 0, stride);
            for (int x = 0; x < width; x++) {
                int red = Math.min(width - 1, x + shift), blue = Math.max(0, x - shift);
                bgr[base + x * 3 + 2] = row[red * 3 + 2];
                bgr[base + x * 3] = row[blue * 3];
            }
        }
    }

    private static final class Encoder implements Runnable {
        final int frames;
        final ReplayExportResolution resolution;
        final boolean compress;
        final Path directory, output;
        final ArrayBlockingQueue<VideoFrame> pool;
        final ArrayBlockingQueue<VideoFrame> pending;
        final Thread thread = new Thread(this, "habitrain-replay-video");
        volatile int written;
        volatile boolean failed, cancelled;
        volatile Path saved;

        Encoder(Path directory, UUID round, ReplayExportResolution resolution, boolean compress, int frames) {
            this.directory = directory;
            this.resolution = resolution;
            this.compress = compress;
            this.frames = frames;
            int poolSize = resolution.highLoad() ? 2 : 3;
            this.pool = new ArrayBlockingQueue<>(poolSize);
            this.pending = new ArrayBlockingQueue<>(poolSize);
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String ext = compress ? ".mp4" : ".avi";
            output = directory.resolve("replay-" + date + "-" + round.toString().substring(0, 8)
                    + "-" + resolution.id + "-" + UUID.randomUUID().toString().substring(0, 8) + ext);
            int frameBytes = resolution.frameBytes();
            for (int i = 0; i < poolSize; i++) pool.add(new VideoFrame(frameBytes));
            // Let the worker remove its temporary file on ordinary client shutdown.
            thread.setDaemon(false);
        }

        synchronized void cancel() {
            if (saved == null) {
                cancelled = true;
                thread.interrupt();
            }
        }

        @Override public void run() {
            Path temp = null;
            int width = resolution.width;
            int height = resolution.height;
            byte[] row = new byte[width * 3];
            try {
                Files.createDirectories(directory);
                temp = Files.createTempFile(directory, ".replay-", ".part");
                try (ReplayVideoWriter writer = compress
                        ? new ReplayMp4Writer(temp, width, height, FPS)
                        : new ReplayAviWriter(temp, width, height, FPS)) {
                    while (written < frames && !cancelled) {
                        VideoFrame frame = pending.poll(250, TimeUnit.MILLISECONDS);
                        if (frame == null) continue;
                        if (frame.aberration > 0.02f) aberrate(frame.data, row, width, height, frame.aberration);
                        writer.add(frame.data);
                        written++;
                        pool.offer(frame);
                    }
                    if (cancelled) return;
                    writer.finish();
                }
                synchronized (this) {
                    if (!cancelled) {
                        Files.move(temp, output);
                        saved = output;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failure) {
                if (!cancelled) {
                    failed = true;
                    LOGGER.warn("Could not write replay video", failure);
                }
            } finally {
                pool.clear();
                pending.clear();
                if (temp != null) {
                    boolean wasInterrupted = Thread.interrupted();
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException failure) {
                        LOGGER.warn("Could not remove partial replay video", failure);
                    } finally {
                        if (wasInterrupted) Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }
}
