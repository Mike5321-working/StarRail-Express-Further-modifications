package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.render.ClientRenderState;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.network.chat.Component;
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
import java.util.function.Supplier;

/**
 * Renders the directed replay film one frame per client frame: designed background, the 3D diorama through
 * the virtual camera with video effects, then the 2D motion graphics. Only pixel data crosses into the
 * encoder thread, which adds the impact lens effect and writes either H.264 MP4 (compressed) or Motion-JPEG AVI.
 */
final class ReplayVideoExport {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayVideoExport.class);
    private static final SystemToast.SystemToastId OPEN_FOLDER_TOAST = new SystemToast.SystemToastId();
    static final int FPS = 30;
    private static final long PREPARE_LIMIT_NS = TimeUnit.MINUTES.toNanos(2);

    final ReplayExportResolution resolution;
    final boolean compress;
    private final @Nullable Path ffmpegExecutable;
    private final boolean fullBody;
    private final UUID roundId;
    private final int frameBytes;
    private ClientReplayBoard.View snapshot;
    private long meshDeadline;
    private ReplayBoardModel model;
    private ReplayVideoScript script;
    private ReplayVideoFx fx;
    private ReplayVideoOverlay overlay;
    private Map<UUID, net.minecraft.resources.ResourceLocation> exportSkins = Map.of();
    private ReplayVideoCamera.Subjects subjects;
    private ReplayVideoCamera.Stage cameraStage;
    private TextureTarget target;
    private ByteBuffer pixels;
    private Encoder encoder;
    private java.util.concurrent.FutureTask<short[]> audioTask;
    private int captured;
    private final long voiceDeadline = System.nanoTime() + PREPARE_LIMIT_NS;
    private @Nullable Boolean voiceExportAllowed;
    private @Nullable String failureStatus;
    private boolean failed;
    private boolean cancelled;
    private boolean voiceUnavailable;

    ReplayVideoExport(ClientReplayBoard.View snapshot, boolean fullBody, ReplayExportResolution resolution,
                      boolean compress, @Nullable Path ffmpegExecutable) {
        this.snapshot = snapshot;
        this.roundId = Objects.requireNonNull(snapshot.replay(), "replay").roundId();
        this.fullBody = fullBody;
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.compress = compress;
        this.ffmpegExecutable = compress ? Objects.requireNonNull(ffmpegExecutable, "ffmpegExecutable") : null;
        this.frameBytes = resolution.frameBytes();
        if (snapshot.asset() != null) ReplayDioramaMesh.retry(snapshot.asset().fingerprint);
    }

    static Path directory(Minecraft client) {
        return client.gameDirectory.toPath().resolve("habitrain-replays").toAbsolutePath().normalize();
    }

    static boolean openFolder(Minecraft client) {
        Path dir = directory(client);
        client.getToasts().addToast(SystemToast.multiline(client, OPEN_FOLDER_TOAST,
                ReplayBoardTexts.tr("controls.folder"), ReplayBoardTexts.tr("controls.folder_opening", dir.toString())));
        try {
            ReplayFolderOpener.open(dir);
            return true;
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            LOGGER.warn("Could not open replay video directory: {}", dir, failure);
            return false;
        }
    }

    boolean preparing() { return encoder == null && running(); }
    UUID roundId() { return roundId; }
    boolean waitingForVoiceAccess() { return preparing() && voiceExportAllowed == null; }
    boolean voiceExportRestricted() { return Boolean.FALSE.equals(voiceExportAllowed) || voiceUnavailable; }
    boolean waitingForVoice() { return preparing() && !voiceExportRestricted() && !snapshot.voiceReady(); }
    void setVoiceExportAllowed(boolean allowed) {
        if (waitingForVoiceAccess()) voiceExportAllowed = allowed;
    }
    boolean finalizing() { return encoder != null && captured >= encoder.frames && running(); }
    boolean encoderBusy() { return encoder != null && encoder.thread.isAlive(); }
    boolean running() { return !failed() && !cancelled && saved() == null; }
    boolean failed() { return failed || encoder != null && encoder.failed; }
    boolean cancelled() { return cancelled; }
    Path saved() { return encoder == null ? null : encoder.saved; }
    double displayProgress() {
        if (saved() != null) return 1;
        return encoder == null ? 0 : Math.min(0.99, encoder.written / (double) encoder.frames);
    }

    Component status() {
        String key;
        if (failed()) key = failureStatus == null ? "failed" : failureStatus;
        else if (cancelled()) key = "cancelled";
        else if (saved() != null) key = voiceUnavailable ? "saved_without_missing_voice" : voiceExportRestricted() ? "saved_no_player_voice" : "saved";
        else if (waitingForVoiceAccess()) key = "checking_voice_access";
        else if (waitingForVoice()) key = "waiting_for_voice";
        else if (preparing()) key = "preparing";
        else if (finalizing() && compress) key = "compressing";
        else key = "progress";
        return ReplayBoardTexts.tr("controls." + key, (int) (displayProgress() * 100));
    }

    void renderNext(Minecraft client, @Nullable Supplier<ClientReplayBoard.View> voiceSource) {
        if (!running()) { releaseRenderResources(); return; }
        try {
            if (encoder != null && captured >= encoder.frames) return;
            var view = snapshot;
            if (waitingForVoice() && Boolean.TRUE.equals(voiceExportAllowed)) {
                var liveView = voiceSource == null ? null : voiceSource.get();
                if ((liveView == null || liveView.replay() == null || !roundId().equals(liveView.replay().roundId()))
                        && ReplayHistoryClient.supported()) {
                    liveView = ReplayHistoryClient.watch(roundId());
                }
                if (liveView != null && liveView.voiceReady() && liveView.replay() != null
                        && view.replay().roundId().equals(liveView.replay().roundId())) {
                    snapshot = view = new ClientReplayBoard.View(view.replay(), view.timeline(), view.asset(),
                            view.presentSections(), view.totalSections(), true, liveView.voice(), true);
                } else if (ReplayHistoryClient.voiceUnavailable(roundId())) voiceUnavailable = true;
            }
            if ((waitingForVoiceAccess() || waitingForVoice()) && System.nanoTime() - voiceDeadline >= 0) {
                failureStatus = waitingForVoiceAccess() ? "voice_access_timeout" : "voice_download_timeout";
                throw new IOException("Timed out waiting for replay voice");
            }
            // The original map remains available when the table unloads or the next match replaces it.
            // A world switch or resource reload can invalidate the GPU mesh, so rebuild it from that map.
            var asset = view.asset();
            if (asset == null) throw new IOException("Replay map asset unavailable");
            ReplayDioramaMesh.update(asset, view.presentSections(), view.totalSections());
            if (ReplayDioramaMesh.failed(new ReplayDioramaMesh.Key(asset.fingerprint, view.totalSections()))) {
                throw new IOException("Could not build the complete replay map");
            }
            var key = ReplayDioramaMesh.builtKey();
            if (key == null || key.present() != view.totalSections() || !ReplayDioramaMesh.matches(asset)) {
                long now = System.nanoTime();
                if (meshDeadline == 0) meshDeadline = now + PREPARE_LIMIT_NS;
                if (now - meshDeadline >= 0) throw new IOException("Timed out preparing the replay map");
                return;
            }
            meshDeadline = 0;
            SceneMeshSet mesh = ReplayDioramaMesh.current();
            if (mesh == null) throw new IOException("Replay map mesh unavailable");
            if (encoder == null) {
                int maxTex = RenderSystem.maxSupportedTextureSize();
                if (resolution.width > maxTex || resolution.height > maxTex) {
                    throw new IOException("Export resolution " + resolution.dimensions()
                            + " exceeds GPU max texture size " + maxTex);
                }
                if (waitingForVoiceAccess() || waitingForVoice()) return;
                if (audioTask == null) {
                    model = new ReplayBoardModel(view.replay(), view.timeline());
                    for (var actor : model.actors) ReplayHeadSkins.texture(actor.p.id());
                    script = new ReplayVideoScript(view.replay());
                    fx = new ReplayVideoFx(script, model);
                    overlay = new ReplayVideoOverlay(script, model, compress);
                    subjects = new ModelSubjects(model);
                    cameraStage = new ReplayVideoCamera.Stage(ReplayBoardRenderer.trayHalfSize(model), model.mapW, model.mapD, model.mapH,
                            (float) Math.max(model.mapH, mesh.replayCeiling() * model.scale), ReplayActorSizing.baseSize(model.scale));
                    ReplayVoiceData voice = voiceExportRestricted() ? ReplayVoiceData.EMPTY : view.voice();
                    var audio = ReplayVideoAudioRenderer.prepare(client, view.replay(), script, voice, script.frames(FPS));
                    audioTask = new java.util.concurrent.FutureTask<>(audio::get);
                    Thread.ofPlatform().daemon().name("habitrain-replay-audio-mix").start(audioTask);
                    return;
                }
                if (!audioTask.isDone()) return;
                short[] soundtrack;
                try { soundtrack = audioTask.get(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                catch (java.util.concurrent.ExecutionException failure) { throw new IOException("Could not prepare replay audio", failure.getCause()); }
                audioTask = null;
                exportSkins = ReplayHeadSkins.snapshot(model);
                pixels = MemoryUtil.memAlloc(frameBytes);
                encoder = new Encoder(directory(client), view.replay().roundId(), resolution, compress,
                        ffmpegExecutable, script.frames(FPS), soundtrack);
                encoder.thread.start();
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
        try (var state = ClientRenderState.capture(); var skins = new ReplayHeadSkins.ExportScope(exportSkins)) {
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
            var heads = ReplayBoardRenderer.drawVideoFrame(client, model, frame, stage, sample.world(), shot, mesh, fullBody, fx);

            canvas.begin();
            overlay.foreground(canvas, time, sample, shot, index, heads);
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

    boolean cancel() {
        if (running() && (encoder == null || encoder.cancel())) cancelled = true;
        releaseRenderResources();
        return cancelled;
    }

    void shutdown() {
        cancel();
        if (encoder == null) return;
        try {
            encoder.thread.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void releaseRenderResources() {
        if (audioTask != null) { audioTask.cancel(true); audioTask = null; }
        snapshot = null;
        model = null;
        script = null;
        fx = null;
        overlay = null;
        exportSkins = Map.of();
        subjects = null;
        cameraStage = null;
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

        @Override public @Nullable UUID mvpId() { return model.mvp == null ? null : model.mvp.p.id(); }

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
        final @Nullable Path ffmpegExecutable;
        short[] soundtrack;
        final Path directory, output;
        final ArrayBlockingQueue<VideoFrame> pool;
        final ArrayBlockingQueue<VideoFrame> pending;
        final Thread thread = new Thread(this, "habitrain-replay-video");
        volatile int written;
        volatile boolean failed, cancelled;
        volatile Path saved;
        private boolean committing;

        Encoder(Path directory, UUID round, ReplayExportResolution resolution, boolean compress,
                @Nullable Path ffmpegExecutable, int frames, short[] soundtrack) {
            this.directory = directory;
            this.resolution = resolution;
            this.compress = compress;
            this.ffmpegExecutable = ffmpegExecutable;
            this.frames = frames;
            this.soundtrack = soundtrack == null ? new short[0] : soundtrack;
            int poolSize = resolution.highLoad() ? 2 : 3;
            this.pool = new ArrayBlockingQueue<>(poolSize);
            this.pending = new ArrayBlockingQueue<>(poolSize);
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String ext = compress ? ".mp4" : ".avi";
            output = directory.resolve("replay-" + date + "-" + round.toString().substring(0, 8)
                    + "-" + resolution.id + "-" + UUID.randomUUID().toString().substring(0, 8) + ext);
            int frameBytes = resolution.frameBytes();
            for (int i = 0; i < poolSize; i++) pool.add(new VideoFrame(frameBytes));
            thread.setDaemon(true);
        }

        synchronized boolean cancel() {
            if (committing || saved != null || failed) return false;
            cancelled = true;
            thread.interrupt();
            return true;
        }

        @Override public void run() {
            Path temp = null;
            int width = resolution.width;
            int height = resolution.height;
            try {
                byte[] row = new byte[width * 3];
                Files.createDirectories(directory);
                temp = Files.createTempFile(directory, ".replay-", ".part");
                try (ReplayVideoWriter writer = compress
                        ? new ReplayMp4Writer(ffmpegExecutable, temp, width, height, FPS, soundtrack)
                        : new ReplayAviWriter(temp, width, height, FPS, true)) {
                    while (written < frames && !cancelled) {
                        VideoFrame frame = pending.poll(250, TimeUnit.MILLISECONDS);
                        if (frame == null) continue;
                        if (frame.aberration > 0.02f) aberrate(frame.data, row, width, height, frame.aberration);
                        writer.add(frame.data);
                        if (!compress) {
                            writer.addAudio(soundtrack,
                                    written * ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME,
                                    ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME);
                        }
                        written++;
                        pool.offer(frame);
                    }
                    if (cancelled) return;
                    writer.finish();
                }
                synchronized (this) {
                    if (cancelled) return;
                    committing = true;
                }
                Files.move(temp, output);
                saved = output;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordFailure(interrupted);
            } catch (IOException | RuntimeException | Error failure) {
                recordFailure(failure);
            } finally {
                soundtrack = null;
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

        private synchronized void recordFailure(Throwable failure) {
            if (!cancelled) {
                failed = true;
                LOGGER.warn("Could not write replay video", failure);
            }
        }
    }
}
