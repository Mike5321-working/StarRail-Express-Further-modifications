package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.client.render.ClientRenderState;
import com.habitrain.core.scene.replay.ReplayCinemaDirector;
import com.habitrain.core.scene.replay.ReplayExportContentOptions;
import com.habitrain.core.scene.replay.ReplayRoofCutaway;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.replay.ReplayMusicPlan;
import com.habitrain.core.scene.replay.ReplayStagePlan;
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
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Renders the directed replay film one frame per client frame across all 4 export modes:
 * <ul>
 *     <li>Mode 1 (Short): readable diorama + 4° brass split-screen killer POV on kills;</li>
 *     <li>Mode 2 (Personal): True Player POV or Sandbox Follow at 1x/2x/4x (WSOLA) or Highlights Only;</li>
 *     <li>Mode 3 (Sandbox Full-Fidelity): dynamic doors/blocks, full 3D players/entities and all-mod skills;</li>
 *     <li>Mode 4 (Cinematic Promo &amp; MVP): &le;3x cruise + 5-tier rarity pyramid choreography.</li>
 * </ul>
 * Audio is streamed in 1-second chunks into a temporary PCM file so even a 20-minute match never allocates
 * a monolithic heap soundtrack array.
 */
final class ReplayVideoExport {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayVideoExport.class);
    private static final SystemToast.SystemToastId OPEN_FOLDER_TOAST = new SystemToast.SystemToastId();
    static final int FPS = 30;
    private static final long PREPARE_LIMIT_NS = TimeUnit.MINUTES.toNanos(2);
    private static final long MAX_ACTIVE_STEP_NS = TimeUnit.SECONDS.toNanos(1);
    private static final long MISSING_VOICE_SOURCE_FALLBACK_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long SKIN_PREFETCH_WAIT_NS = TimeUnit.SECONDS.toNanos(3);

    final ReplayExportContentOptions options;
    final ReplayExportResolution resolution;
    final boolean compress;
    final ReplayExportQuality quality;
    final ReplayMusicPlan.Level music;
    final @Nullable UUID focusPlayer;
    private final @Nullable Path ffmpegExecutable;
    private final boolean fullBody;
    private final UUID roundId;
    private final int frameBytes;
    final ReplayExportBackend backend;
    final ReplayVisualDownload visuals;
    private @Nullable ReplayForegroundWorld foregroundWorld;
    private boolean foregroundFrameReady;
    private boolean nativeFallback;

    boolean inForeground() {
        return backend == ReplayExportBackend.NATIVE && !nativeFallback && running() && !finalizing();
    }

    private boolean nativeAction() {
        if (backend != ReplayExportBackend.NATIVE || nativeFallback || film == null || encoder == null || submitted >= encoder.frames) return false;
        var sample = film.sample(submitted / (double) FPS);
        return sample.baseSample().act() == ReplayVideoScript.Act.ACTION && sample.camera() == ReplayCinemaDirector.CameraDirective.POV_FIRST_PERSON;
    }

    void drawWindow(Minecraft client, java.util.function.Consumer<net.minecraft.client.DeltaTracker> drawWorld) {
        foregroundFrameReady = false;
        if (!nativeAction() || !running() || encoder.pool.isEmpty()) return;
        var mainTarget = client.getMainRenderTarget();
        if (mainTarget == null || mainTarget.viewWidth <= 0 || mainTarget.viewHeight <= 0) return;
        try {
            var shot = film.sample(submitted / (double) FPS);
            UUID subject = shot.povSubjectUuid() == null ? film.focusPlayer() : shot.povSubjectUuid();
            if (visuals.need(com.habitrain.core.network.replay.VisualTape.WORLD) || visuals.need(subject)) return;
            if (foregroundWorld == null) foregroundWorld = new ReplayForegroundWorld(client, model, snapshot.asset(), visuals);
            foregroundFrameReady = foregroundWorld.draw(client, subject, shot.replayTick(), drawWorld);
            if (foregroundFrameReady) foregroundWorld.paintHud(client);
        } catch (RuntimeException failure) {
            LOGGER.warn("Native foreground replay rendering failed; falling back to simplified renderer", failure);
            if (foregroundWorld != null) {
                try { foregroundWorld.detach(client); } catch (RuntimeException ignored) {}
                foregroundWorld = null;
            }
            foregroundFrameReady = false;
            nativeFallback = true;
            if (snapshot != null && snapshot.asset() != null) {
                ReplayDioramaMesh.retry(snapshot.asset().fingerprint);
            }
        }
    }

    void preview(Minecraft client) {
        if (!foregroundFrameReady && target != null && submitted > 0) blit(target, client.getMainRenderTarget());
    }

    private static void blit(com.mojang.blaze3d.pipeline.RenderTarget source, com.mojang.blaze3d.pipeline.RenderTarget destination) {
        if (source == null || destination == null || source.viewWidth <= 0 || source.viewHeight <= 0
                || destination.viewWidth <= 0 || destination.viewHeight <= 0) return;
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
            double scale = Math.min((double)destination.viewWidth / source.viewWidth, (double)destination.viewHeight / source.viewHeight);
            int width = (int)Math.round(source.viewWidth * scale), height = (int)Math.round(source.viewHeight * scale);
            int left = (destination.viewWidth - width) / 2, bottom = (destination.viewHeight - height) / 2;
            if (width != destination.viewWidth || height != destination.viewHeight) {
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    GL30.glClearBufferfv(GL11.GL_COLOR, 0, stack.floats(0f, 0f, 0f, 1f));
                }
            }
            GL30.glBlitFramebuffer(0, 0, source.viewWidth, source.viewHeight, left, bottom, left + width, bottom + height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }
    private ClientReplayBoard.View snapshot;
    private long lastRenderTickNs;
    private long voiceWaitActiveNs;
    private long boardVoiceMissingActiveNs;
    private long meshWaitActiveNs;
    private long skinWaitDeadline;
    private @Nullable ReplayDioramaMesh.DynamicWorld dynamicWorld;
    private @Nullable SceneAssetCodec.AssetData staticExportAsset;
    private @Nullable SceneAssetCodec.AssetData reviewAsset;
    private ReplayBoardModel model;
    private ReplayCinemaDirector.DirectedFilm film;
    private ReplayVideoFx fx;
    private ReplayVideoOverlay overlay;
    private Map<UUID, net.minecraft.resources.ResourceLocation> exportSkins = Map.of();
    private ReplayVideoCamera.Subjects subjects;
    private ReplayVideoCamera.Stage cameraStage;
    private static final int PBO_COUNT = 2;
    private TextureTarget target;
    private @Nullable TextureTarget povTarget;
    private ByteBuffer pixels;
    private int @Nullable [] pbos;
    private boolean pboUnavailable;
    private int pendingPbo = -1;
    private float pendingAberration;
    private Encoder encoder;
    private java.util.concurrent.FutureTask<Path> audioTask;
    private @Nullable Path stagedAudioPcm;
    private int submitted;
    private int captured;
    private @Nullable Boolean voiceExportAllowed;
    private @Nullable String failureStatus;
    private boolean failed;
    private boolean cancelled;
    private boolean voiceUnavailable;

    ReplayVideoExport(ClientReplayBoard.View snapshot, boolean fullBody, ReplayExportResolution resolution,
                      boolean compress, @Nullable Path ffmpegExecutable) {
        this(snapshot, fullBody, resolution, compress, ffmpegExecutable, ReplayMusicPlan.Level.OFF, null);
    }

    ReplayVideoExport(ClientReplayBoard.View snapshot, boolean fullBody, ReplayExportResolution resolution,
                      boolean compress, @Nullable Path ffmpegExecutable, ReplayMusicPlan.Level music) {
        this(snapshot, fullBody, resolution, compress, ffmpegExecutable, music, null);
    }

    ReplayVideoExport(ClientReplayBoard.View snapshot, boolean fullBody, ReplayExportResolution resolution,
                      boolean compress, @Nullable Path ffmpegExecutable, ReplayMusicPlan.Level music,
                      @Nullable UUID focusPlayer) {
        this(snapshot, fullBody, resolution, compress, ReplayExportQuality.DEFAULT, ffmpegExecutable, music, focusPlayer);
    }

    ReplayVideoExport(ClientReplayBoard.View snapshot, boolean fullBody, ReplayExportResolution resolution,
                      boolean compress, ReplayExportQuality quality, @Nullable Path ffmpegExecutable,
                      ReplayMusicPlan.Level music, @Nullable UUID focusPlayer) {
        this(snapshot, ReplayExportContentOptions.defaultsFor(ReplayExportContentOptions.ExportMode.SHORT, focusPlayer)
                        .toBuilder()
                        .roofCutaway(!fullBody)
                        .musicLevel(music == null ? ReplayMusicPlan.Level.OFF : music)
                        .build(),
                resolution, compress, quality, ffmpegExecutable);
    }

    ReplayVideoExport(ClientReplayBoard.View snapshot, ReplayExportContentOptions options,
                      ReplayExportResolution resolution, boolean compress,
                      ReplayExportQuality quality, @Nullable Path ffmpegExecutable) {
        this(snapshot, options, resolution, compress, quality, ffmpegExecutable, ReplayExportBackend.SIMPLE);
    }

    ReplayVideoExport(ClientReplayBoard.View snapshot, ReplayExportContentOptions options,
                      ReplayExportResolution resolution, boolean compress, ReplayExportQuality quality,
                      @Nullable Path ffmpegExecutable, ReplayExportBackend backend) {
        this.snapshot = snapshot;
        this.backend = backend;
        this.roundId = Objects.requireNonNull(snapshot.replay(), "replay").roundId();
        this.visuals = new ReplayVisualDownload(roundId);
        this.options = options == null
                ? ReplayExportContentOptions.defaultsFor(ReplayExportContentOptions.ExportMode.SHORT)
                : options;
        this.fullBody = !this.options.roofCutaway();
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.compress = compress;
        this.quality = quality == null ? ReplayExportQuality.DEFAULT : quality;
        this.music = this.options.musicLevel();
        this.focusPlayer = this.options.focusPlayer();
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
    boolean wantsPlayerVoice() { return options.voiceVolume() > 0.001f; }
    boolean waitingForVoiceAccess() { return preparing() && wantsPlayerVoice() && voiceExportAllowed == null; }
    boolean voiceExportRestricted() { return Boolean.FALSE.equals(voiceExportAllowed) || voiceUnavailable; }
    boolean waitingForVoice() { return preparing() && wantsPlayerVoice() && !voiceExportRestricted() && !snapshot.voiceReady(); }
    void setVoiceExportAllowed(boolean allowed) {
        if (waitingForVoiceAccess()) voiceExportAllowed = allowed;
    }
    boolean finalizing() { return encoder != null && captured >= encoder.frames && running(); }
    boolean encoderBusy() { return encoder != null && encoder.thread.isAlive(); }
    boolean running() { return !failed() && !cancelled && saved() == null; }
    boolean failed() { return failed || (encoder != null && encoder.failed); }
    boolean cancelled() { return cancelled; }
    Path saved() { return encoder == null ? null : encoder.saved; }
    double displayProgress() {
        if (saved() != null) return 1;
        return encoder == null ? 0 : Math.min(0.99, encoder.written / (double) encoder.frames);
    }

    Component status() {
        String key;
        if (failed()) {
            String reason = failureStatus != null ? failureStatus : (encoder != null ? encoder.failureStatus : null);
            key = reason != null ? reason
                    : (backend == ReplayExportBackend.NATIVE && !nativeFallback ? "native_failed" : "failed");
        }
        else if (cancelled()) key = "cancelled";
        else if (saved() != null) key = voiceUnavailable ? "saved_without_missing_voice" : voiceExportRestricted() ? "saved_no_player_voice" : "saved";
        else if (waitingForVoiceAccess()) key = "checking_voice_access";
        else if (waitingForVoice()) key = "waiting_for_voice";
        else if (preparing()) key = "preparing";
        else if (finalizing() && compress) key = "compressing";
        else if (backend == ReplayExportBackend.NATIVE && !nativeFallback) key = "native_progress";
        else key = "progress";
        return ReplayBoardTexts.tr("controls." + key, (int) (displayProgress() * 100));
    }

    void renderNext(Minecraft client, @Nullable Supplier<ClientReplayBoard.View> voiceSource) {
        if (!running()) { releaseRenderResources(); return; }
        try {
            if (encoder != null && captured >= encoder.frames) return;
            long now = System.nanoTime();
            long stepNs = lastRenderTickNs == 0 ? 0L : Math.clamp(now - lastRenderTickNs, 0L, MAX_ACTIVE_STEP_NS);
            lastRenderTickNs = now;

            var view = snapshot;
            if (waitingForVoice() && Boolean.TRUE.equals(voiceExportAllowed)) {
                var liveView = voiceSource == null ? null : voiceSource.get();
                boolean liveMatches = liveView != null && liveView.replay() != null && roundId().equals(liveView.replay().roundId());
                if (!liveMatches && ReplayHistoryClient.supported()) {
                    liveView = ReplayHistoryClient.watch(roundId());
                    liveMatches = liveView != null && liveView.replay() != null && roundId().equals(liveView.replay().roundId());
                }
                if (liveMatches && liveView.voiceReady()) {
                    snapshot = view = new ClientReplayBoard.View(view.replay(), view.timeline(), view.asset(),
                            view.presentSections(), view.totalSections(), true, liveView.voice(), true);
                    boardVoiceMissingActiveNs = 0;
                } else if (ReplayHistoryClient.voiceUnavailable(roundId())) {
                    voiceUnavailable = true;
                } else if (!liveMatches && !ReplayHistoryClient.supported()) {
                    boardVoiceMissingActiveNs += stepNs;
                    if (boardVoiceMissingActiveNs >= MISSING_VOICE_SOURCE_FALLBACK_NS) {
                        LOGGER.warn("Replay table left view without archive support while waiting for voice; continuing without player voice");
                        voiceUnavailable = true;
                    }
                } else {
                    boardVoiceMissingActiveNs = 0;
                }
            }
            if (waitingForVoiceAccess() || waitingForVoice()) {
                voiceWaitActiveNs += stepNs;
                if (voiceWaitActiveNs >= PREPARE_LIMIT_NS) {
                    if (waitingForVoiceAccess()) {
                        LOGGER.warn("Timed out confirming voice export permission for {}; exporting with game audio only", roundId);
                        voiceExportAllowed = false;
                    } else {
                        LOGGER.warn("Timed out waiting for replay voice for {}; exporting with game audio only", roundId);
                        voiceUnavailable = true;
                    }
                }
            }
            var rawAsset = view.asset();
            if (rawAsset == null) {
                failureStatus = "mesh_failed";
                throw new IOException("Replay map asset unavailable");
            }
            SceneMeshSet mesh = null;
            boolean useNative = backend == ReplayExportBackend.NATIVE && !nativeFallback;
            if (!useNative || (encoder != null && !nativeAction() && submitted < encoder.frames)) {
                mesh = prepareMesh(view, stepNs);
                if (mesh == null) return;
            }
            if (encoder == null) {
                int maxTex = RenderSystem.maxSupportedTextureSize();
                if (resolution.width > maxTex || resolution.height > maxTex) {
                    failureStatus = "gpu_resolution_too_large";
                    throw new IOException("Export resolution " + resolution.dimensions()
                            + " exceeds GPU max texture size " + maxTex);
                }
                if (waitingForVoiceAccess() || waitingForVoice()) return;
                if (audioTask == null) {
                    model = new ReplayBoardModel(view.replay(), view.timeline());
                    if (model.layout != null && !useNative) {
                        dynamicWorld = ReplayDioramaMesh.prepareDynamic(
                                rawAsset, model.layout.origin(), view.replay().blockDeltas());
                    }
                    staticExportAsset = (dynamicWorld != null && dynamicWorld.hasDynamics())
                            ? dynamicWorld.staticAsset() : rawAsset;
                    if (staticExportAsset != rawAsset) {
                        ReplayDioramaMesh.retry(staticExportAsset.fingerprint);
                    }
                    if (!useNative && !fullBody && model.roofCutLayer > 0) {
                        var cut = ReplayRoofCutaway.apply(staticExportAsset, model.roofCutLayer);
                        if (cut != staticExportAsset && !cut.sections.isEmpty()) {
                            reviewAsset = cut;
                            ReplayDioramaMesh.retry(cut.fingerprint);
                        }
                    }
                    ReplayHeadSkins.prefetch(model);
                    skinWaitDeadline = System.nanoTime() + SKIN_PREFETCH_WAIT_NS;
                    ReplayVoiceData voice = !wantsPlayerVoice() || voiceExportRestricted() ? ReplayVoiceData.EMPTY : view.voice();
                    film = ReplayCinemaDirector.direct(view.replay(), voice, options);
                    fx = new ReplayVideoFx(film, model);
                    overlay = new ReplayVideoOverlay(film, model, compress, quality);
                    subjects = new ModelSubjects(model);
                    cameraStage = new ReplayVideoCamera.Stage(ReplayBoardRenderer.trayHalfSize(model), model.mapW, model.mapD, model.mapH,
                            mesh == null ? (float)model.mapH : (float) Math.max(model.mapH, mesh.replayCeiling() * model.scale),
                            ReplayActorSizing.baseSize(model.scale));
                    var audio = ReplayVideoAudioRenderer.prepareStreaming(client, directory(client), film, voice, film.frames(FPS));
                    audioTask = new java.util.concurrent.FutureTask<>(audio::get);
                    Thread.ofPlatform().daemon().name("habitrain-replay-audio-mix").start(audioTask);
                    return;
                }
                if (!audioTask.isDone()) return;
                if (ReplayHeadSkins.hasPending(model) && System.nanoTime() - skinWaitDeadline < 0) return;
                Path mixedPcm;
                try { mixedPcm = audioTask.get(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                catch (java.util.concurrent.ExecutionException failure) {
                    failureStatus = "audio_failed";
                    throw new IOException("Could not prepare replay audio", failure.getCause());
                }
                audioTask = null;
                stagedAudioPcm = mixedPcm;
                exportSkins = ReplayHeadSkins.snapshot(model);
                var focusData = model.participantData(film.focusPlayer());
                String focusSlug = focusData == null ? null : sanitizeFileSlug(focusData.name());
                String modeSlug = sanitizeFileSlug(film.mode().id);
                String combinedSlug = focusSlug == null ? modeSlug : (modeSlug == null ? focusSlug : modeSlug + "-" + focusSlug);
                encoder = new Encoder(directory(client), view.replay().roundId(), resolution, compress, quality,
                        ffmpegExecutable, film.frames(FPS), mixedPcm, combinedSlug);
                stagedAudioPcm = null; // Ownership transferred to Encoder
                encoder.thread.start();
                if (useNative) return;
            }

            if (pendingPbo >= 0) {
                VideoFrame ready = encoder.pool.poll();
                if (ready == null) return; // The encoder can never build an unbounded backlog.
                try {
                    drainPbo(pbos[pendingPbo], ready);
                } catch (IOException | RuntimeException failure) {
                    disablePbos(failure);
                    // The export framebuffer still holds this frame, even if its PBO was corrupted.
                    readPixels(0, ready);
                }
                ready.aberration = pendingAberration;
                pendingPbo = -1;
                if (!encoder.pending.offer(ready)) throw new IllegalStateException("Video frame queue full");
                captured++;
                if (captured >= encoder.frames) {
                    releaseRenderResources();
                    return;
                }
            }

            if (submitted < encoder.frames) {
                if (nativeAction() && !foregroundFrameReady) return;
                ensurePbos();
                if (!pboUnavailable && pbos != null) {
                    int slot = submitted % PBO_COUNT;
                    try {
                        pendingAberration = capture(client, mesh, submitted, null, pbos[slot]);
                        pendingPbo = slot;
                        submitted++;
                    } catch (PboReadException failure) {
                        disablePbos(failure);
                        // No frame was submitted. Capture this index through the fallback below.
                    }
                }
                if (pboUnavailable) {
                    VideoFrame frame = encoder.pool.poll();
                    if (frame == null) return; // The encoder can never build an unbounded backlog.
                    frame.aberration = capture(client, mesh, submitted, frame, 0);
                    if (!encoder.pending.offer(frame)) throw new IllegalStateException("Video frame queue full");
                    submitted++;
                    captured++;
                    if (captured >= encoder.frames) {
                        releaseRenderResources();
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            failed = true;
            if (encoder != null) encoder.cancel();
            releaseRenderResources();
            LOGGER.warn("Replay video export failed", failure);
        }
    }

    private @Nullable SceneMeshSet prepareMesh(ClientReplayBoard.View view, long stepNs) throws IOException {
        var rawAsset = view.asset();
        var activeAsset = staticExportAsset != null ? staticExportAsset : rawAsset;
        if (reviewAsset != null && film != null
                && film.sample(submitted / (double) FPS).baseSample().act() == ReplayVideoScript.Act.REVIEW) {
            activeAsset = reviewAsset;
        }
        int expectedSections = activeAsset == rawAsset ? view.totalSections() : activeAsset.sections.size();
        int presentSections = activeAsset == rawAsset ? view.presentSections() : expectedSections;
        ReplayDioramaMesh.update(activeAsset, presentSections, expectedSections);
        if (ReplayDioramaMesh.failed(new ReplayDioramaMesh.Key(activeAsset.fingerprint, expectedSections))) {
            if (activeAsset == reviewAsset) {
                LOGGER.warn("Roof-cutaway review mesh failed for {}; falling back to full export mesh", roundId);
                reviewAsset = null;
                meshWaitActiveNs = 0;
                return null;
            }
            if (activeAsset == staticExportAsset && staticExportAsset != rawAsset) {
                LOGGER.warn("Static dynamic-world mesh failed for {}; falling back to raw map mesh", roundId);
                dynamicWorld = null;
                staticExportAsset = rawAsset;
                ReplayDioramaMesh.retry(rawAsset.fingerprint);
                meshWaitActiveNs = 0;
                return null;
            }
            failureStatus = "mesh_failed";
            throw new IOException("Could not build the complete replay map");
        }
        var key = ReplayDioramaMesh.builtKey();
        if (key == null || key.present() != expectedSections || !ReplayDioramaMesh.matches(activeAsset)) {
            meshWaitActiveNs += stepNs;
            if (meshWaitActiveNs >= PREPARE_LIMIT_NS) {
                failureStatus = "mesh_timeout";
                throw new IOException("Timed out preparing the replay map");
            }
            return null;
        }
        meshWaitActiveNs = 0;
        var mesh = ReplayDioramaMesh.current();
        if (mesh == null) {
            failureStatus = "mesh_failed";
            throw new IOException("Replay map mesh unavailable");
        }
        return mesh;
    }

    private void ensurePbos() {
        if (pbos != null || pboUnavailable) return;
        int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] created = new int[PBO_COUNT];
        try {
            clearGlErrors();
            GL15.glGenBuffers(created);
            checkGl("generate pixel pack buffers");
            for (int id : created) {
                if (id == 0) {
                    GL15.glDeleteBuffers(created);
                    pboUnavailable = true;
                    return;
                }
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, id);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, frameBytes, GL15.GL_STREAM_READ);
                checkGl("allocate pixel pack buffer");
            }
            pbos = created;
        } catch (IOException | RuntimeException failure) {
            GL15.glDeleteBuffers(created);
            pboUnavailable = true;
            LOGGER.debug("Falling back to synchronous replay readback after PBO setup failure", failure);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
        }
    }

    private void drainPbo(int pboId, VideoFrame output) throws IOException {
        int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            clearGlErrors();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboId);
            checkGl("bind pending pixel pack buffer");
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0L, frameBytes, GL30.GL_MAP_READ_BIT);
            if (mapped == null) throw new IOException("Could not map pending pixel pack buffer");
            boolean valid;
            try {
                mapped.get(0, output.data);
            } finally {
                valid = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            if (!valid) throw new IOException("Pending pixel pack buffer was corrupted");
            checkGl("read pending pixel pack buffer");
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
        }
    }

    private static void clearGlErrors() {
        for (int i = 0; i < 16 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
            // Isolate the following operation without looping indefinitely after context loss.
        }
    }

    private static void checkGl(String operation) throws IOException {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) throw new IOException(operation + " failed (GL 0x" + Integer.toHexString(error) + ")");
    }

    private static final class PboReadException extends IOException {
        PboReadException(Exception cause) { super(cause); }
    }

    private void readPixels(int pboId, @Nullable VideoFrame output) throws IOException {
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        try {
            clearGlErrors();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboId);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            checkGl("prepare replay readback");
            if (pboId != 0) {
                GL11.glReadPixels(0, 0, resolution.width, resolution.height, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, 0L);
            } else {
                if (pixels == null) pixels = MemoryUtil.memAlloc(frameBytes);
                pixels.clear();
                GL11.glReadPixels(0, 0, resolution.width, resolution.height, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, pixels);
            }
            checkGl("read replay pixels");
            if (output != null) pixels.get(0, output.data);
        } catch (IOException | RuntimeException failure) {
            if (pboId != 0) throw new PboReadException(failure);
            throw failure;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPackBuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        }
    }

    private float capture(Minecraft client, SceneMeshSet mesh, int index, @Nullable VideoFrame output, int pboId) throws IOException {
        int exportWidth = resolution.width;
        int exportHeight = resolution.height;
        if (nativeAction()) {
            int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            try {
                if (target == null) target = new TextureTarget(exportWidth, exportHeight, false, Minecraft.ON_OSX);
                blit(client.getMainRenderTarget(), target);
                readPixels(pboId, output);
            } finally {
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
                RenderSystem.viewport(0, 0, client.getMainRenderTarget().viewWidth, client.getMainRenderTarget().viewHeight);
            }
            return 0f;
        }
        double time = index / (double) FPS;
        var cs = film.sample(time);
        var sample = cs.baseSample();
        var stage = film.stage(sample);
        var frame = film.frame(sample);
        var shot = ReplayVideoCamera.at(film, subjects, cameraStage, time);
        fx.at(time, cs);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = new int[4];
        if (scissor) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        int lightTexture = RenderSystem.getShaderTexture(2);
        var projection = RenderSystem.getProjectionMatrix();
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack().pushMatrix();
        try (var state = ClientRenderState.capture(); var skins = new ReplayHeadSkins.ExportScope(exportSkins)) {
            RenderSystem.disableScissor();
            RenderSystem.depthMask(true);
            if (target == null || !target.useDepth) {
                destroyTarget(target);
                target = new TextureTarget(exportWidth, exportHeight, true, Minecraft.ON_OSX);
                target.setClearColor(0.016f, 0.024f, 0.06f, 1);
            }
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            // Keep 2D motion graphics in the authored 1280x720 design coordinate space while
            // rendering into the native exportWidth x exportHeight framebuffer viewport.
            var canvas = new ReplayVideoCanvas(client, ReplayVideoCanvas.DESIGN_WIDTH, ReplayVideoCanvas.DESIGN_HEIGHT);
            var rawAsset = snapshot != null ? snapshot.asset() : null;
            var camDir = cs.camera();
            boolean isAction = sample.act() == ReplayVideoScript.Act.ACTION;
            ReplayActorSizing heads = null;

            if (isAction && isFullPov(camDir)) {
                var povCam = ReplayVideoCamera.povCameraAt(film, model, cs, time, false);
                if (povCam != null) {
                    var povOpts = buildPovOptions(cs, cs.replayTick());
                    ReplayPovSceneRenderer.renderToCurrentTarget(client, model, rawAsset, mesh,
                            cs.replayTick(), sample.world(), 0, 0, exportWidth, exportHeight, povCam, povOpts);
                } else {
                    heads = drawDioramaPass(client, canvas, modelView, mesh, frame, stage, sample, cs, shot, time);
                }
            } else if (isAction && cs.splitScreen()
                    && (camDir == ReplayCinemaDirector.CameraDirective.SPLIT_DIORAMA_AND_POV
                    || camDir == ReplayCinemaDirector.CameraDirective.SPLIT_HERO_SIDE_AND_POV)) {
                double leftRatio = cs.splitLeftRatio();
                var povCamRight = ReplayVideoCamera.povCameraAt(film, model, cs, time, true);
                if (leftRatio <= 0.015 && povCamRight != null) {
                    // Tier 4 has slid fully open into full-screen 1x native POV.
                    var rightOpts = buildPovOptions(cs, cs.rightPovTick());
                    ReplayPovSceneRenderer.renderToCurrentTarget(client, model, rawAsset, mesh,
                            cs.rightPovTick(), sample.world(), 0, 0, exportWidth, exportHeight, povCamRight, rightOpts);
                } else {
                    // Left panel: classic diorama (Mode 1) or 90-115° Hero-Side (Mode 4 Tier 4)
                    if (camDir == ReplayCinemaDirector.CameraDirective.SPLIT_HERO_SIDE_AND_POV) {
                        var povCamLeft = ReplayVideoCamera.povCameraAt(film, model, cs, time, false);
                        if (povCamLeft != null) {
                            var leftOpts = buildPovOptions(cs, cs.replayTick());
                            ReplayPovSceneRenderer.renderToCurrentTarget(client, model, rawAsset, mesh,
                                    cs.replayTick(), sample.world(), 0, 0, exportWidth, exportHeight, povCamLeft, leftOpts);
                        } else {
                            heads = drawDioramaPass(client, canvas, modelView, mesh, frame, stage, sample, cs, shot, time);
                        }
                    } else {
                        heads = drawDioramaPass(client, canvas, modelView, mesh, frame, stage, sample, cs, shot, time);
                    }
                    // Right panel: synchronized killer/hero 1st-person POV rendered in independent FBO
                    if (leftRatio < 0.985 && povCamRight != null) {
                        if (povTarget == null) {
                            povTarget = new TextureTarget(exportWidth, exportHeight, true, Minecraft.ON_OSX);
                            povTarget.setClearColor(0.016f, 0.024f, 0.06f, 1);
                        }
                        povTarget.clear(Minecraft.ON_OSX);
                        povTarget.bindWrite(true);
                        var rightOpts = buildPovOptions(cs, cs.rightPovTick());
                        ReplayPovSceneRenderer.renderToCurrentTarget(client, model, rawAsset, mesh,
                                cs.rightPovTick(), sample.world(), 0, 0, exportWidth, exportHeight, povCamRight, rightOpts);
                        target.bindWrite(true);
                        canvas.begin();
                        float[] seam = ReplayVideoOverlay.splitSeamX(leftRatio,
                                ReplayVideoCanvas.DESIGN_WIDTH, ReplayVideoCanvas.DESIGN_HEIGHT);
                        canvas.drawDiagonalRightSplit(povTarget.getColorTextureId(), seam[0], seam[1], 0xFFFFFFFF);
                    }
                }
            } else {
                heads = drawDioramaPass(client, canvas, modelView, mesh, frame, stage, sample, cs, shot, time);
            }

            target.bindWrite(true);
            canvas.begin();
            overlay.foreground(canvas, time, sample, cs, shot, index, heads);
            RenderSystem.enableDepthTest();

            readPixels(pboId, output);
            return (float) overlay.aberration(time);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            else RenderSystem.disableScissor();
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderTexture(2, lightTexture);
        }
    }

    private ReplayActorSizing drawDioramaPass(Minecraft client, ReplayVideoCanvas canvas,
                                              org.joml.Matrix4fStack modelView, SceneMeshSet mesh,
                                              ReplayTimeline.Frame frame, ReplayStagePlan.Stage stage,
                                              ReplayVideoScript.Sample sample,
                                              @Nullable ReplayCinemaDirector.CinemaSample cs,
                                              ReplayVideoCamera.Shot shot,
                                              double time) {
        canvas.begin();
        overlay.background(canvas, time, sample, cs, shot);

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
        return ReplayBoardRenderer.drawVideoFrame(client, model, frame, stage, sample.world(), shot, mesh,
                fullBody, fx, film.focusPlayer(), dynamicWorld);
    }

    private static boolean isFullPov(ReplayCinemaDirector.CameraDirective dir) {
        return dir == ReplayCinemaDirector.CameraDirective.POV_FIRST_PERSON
                || dir == ReplayCinemaDirector.CameraDirective.POV_OVER_SHOULDER
                || dir == ReplayCinemaDirector.CameraDirective.POV_HERO_SIDE
                || dir == ReplayCinemaDirector.CameraDirective.POV_ORBIT_360;
    }

    private ReplayPovSceneRenderer.RenderOptions buildPovOptions(ReplayCinemaDirector.CinemaSample cs, double matchTick) {
        boolean showTrails = options.trailFilter() != ReplayExportContentOptions.ActorOverlayFilter.NONE;
        boolean showNameplates = options.nameplateFilter() != ReplayExportContentOptions.ActorOverlayFilter.NONE;
        boolean showRoles = options.identityReveal() != ReplayExportContentOptions.IdentityReveal.HIDDEN;
        Set<UUID> revealedRoles = null;
        if (options.identityReveal() == ReplayExportContentOptions.IdentityReveal.SUSPENSE_FOG) {
            revealedRoles = new HashSet<>();
            for (var actor : model.actors) {
                if (film.isRoleRevealedAt(actor.p.id(), matchTick)) revealedRoles.add(actor.p.id());
            }
        }
        Set<UUID> nameplateFilter = null;
        if (options.nameplateFilter() == ReplayExportContentOptions.ActorOverlayFilter.FOCUS_ONLY) {
            UUID f = film.focusPlayer() != null ? film.focusPlayer() : cs.povSubjectUuid();
            if (f != null) nameplateFilter = Set.of(f);
        } else if (options.nameplateFilter() == ReplayExportContentOptions.ActorOverlayFilter.FOCUS_AND_COMBAT) {
            Set<UUID> active = new HashSet<>();
            if (film.focusPlayer() != null) active.add(film.focusPlayer());
            if (cs.povSubjectUuid() != null) active.add(cs.povSubjectUuid());
            var h = cs.activeHighlight();
            if (h != null) {
                if (h.primaryActor() != null) active.add(h.primaryActor());
                if (h.secondaryActor() != null) active.add(h.secondaryActor());
            }
            if (!active.isEmpty()) nameplateFilter = Set.copyOf(active);
        }
        return new ReplayPovSceneRenderer.RenderOptions(
                options.roofCutaway(), true, showTrails, showNameplates, showRoles,
                true, true, true, true, true,
                null, null, 1.0f, null, nameplateFilter, revealedRoles
        );
    }

    boolean cancel() {
        if (running() && (encoder == null || encoder.cancel())) cancelled = true;
        releaseRenderResources();
        return cancelled;
    }

    void shutdown() {
        cancel();
        try {
            if (encoder != null) encoder.thread.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void releaseRenderResources() {
        if (audioTask != null) { audioTask.cancel(true); audioTask = null; }
        if (stagedAudioPcm != null) {
            try { Files.deleteIfExists(stagedAudioPcm); } catch (IOException ignored) {}
            stagedAudioPcm = null;
        }
        snapshot = null;
        if (foregroundWorld != null) { foregroundWorld.detach(Minecraft.getInstance()); foregroundWorld = null; }
        dynamicWorld = null;
        staticExportAsset = null;
        reviewAsset = null;
        model = null;
        film = null;
        fx = null;
        overlay = null;
        exportSkins = Map.of();
        subjects = null;
        cameraStage = null;
        destroyTarget(target);
        target = null;
        destroyTarget(povTarget);
        povTarget = null;
        pendingPbo = -1;
        deletePbos();
        if (pixels != null) { MemoryUtil.memFree(pixels); pixels = null; }
    }

    private static void destroyTarget(@Nullable TextureTarget fbo) {
        if (fbo == null) return;
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int removed = fbo.frameBufferId;
        try {
            fbo.destroyBuffers();
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw == removed ? 0 : draw);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read == removed ? 0 : read);
        }
    }

    private void disablePbos(Exception failure) {
        pboUnavailable = true;
        deletePbos();
        LOGGER.debug("Falling back to synchronous replay readback", failure);
    }

    private void deletePbos() {
        if (pbos != null) {
            int prevPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            boolean rebound = false;
            for (int id : pbos) if (id != 0 && id == prevPackBuffer) { rebound = true; break; }
            if (rebound) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            try {
                GL15.glDeleteBuffers(pbos);
            } finally {
                pbos = null;
            }
        }
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

        @Override public @Nullable Double mvpFrontYawRad() {
            if (model.mvp == null) return null;
            var pose = model.mvp.track.sample(model.replay.durationTicks());
            if (!pose.visible()) return null;
            float yawOffset = model.rotated ? 90f : 0f;
            return Math.toRadians(yawOffset - pose.headYawDegrees());
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
        if (shift <= 0 || width <= 0 || height <= 0) return;
        int stride = width * 3;
        int clampedShift = Math.min(shift, width - 1);
        int byteShift = clampedShift * 3;
        int rightStart = width - clampedShift;
        int leftLimit = Math.min(clampedShift, rightStart);
        int lastRedOffset = stride - 1;
        for (int y = 0; y < height; y++) {
            int base = y * stride;
            System.arraycopy(bgr, base, row, 0, stride);
            byte firstBlue = row[0];
            byte lastRed = row[lastRedOffset];
            int dst = base;
            for (int x = 0; x < leftLimit; x++, dst += 3) {
                bgr[dst] = firstBlue;
                bgr[dst + 2] = row[(x + clampedShift) * 3 + 2];
            }
            int midSrcBlue = 0;
            int midSrcRed = byteShift * 2 + 2;
            for (int x = leftLimit; x < rightStart; x++, dst += 3, midSrcBlue += 3, midSrcRed += 3) {
                bgr[dst] = row[midSrcBlue];
                bgr[dst + 2] = row[midSrcRed];
            }
            for (int x = rightStart; x < width; x++, dst += 3) {
                int blueIdx = x >= shift ? (x - shift) * 3 : 0;
                bgr[dst] = row[blueIdx];
                bgr[dst + 2] = lastRed;
            }
        }
    }

    static @Nullable String sanitizeFileSlug(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > 24 ? cleaned.substring(0, 24) : cleaned;
    }

    private static final class Encoder implements Runnable {
        final int frames;
        final ReplayExportResolution resolution;
        final boolean compress;
        final ReplayExportQuality quality;
        final @Nullable Path ffmpegExecutable;
        @Nullable Path preMixedAudioPcm;
        final Path directory, output;
        final ArrayBlockingQueue<VideoFrame> pool;
        final ArrayBlockingQueue<VideoFrame> pending;
        final Thread thread = new Thread(this, "habitrain-replay-video");
        volatile int written;
        volatile boolean failed, cancelled;
        volatile @Nullable String failureStatus;
        volatile Path saved;
        private volatile @Nullable ReplayVideoWriter activeWriter;
        private boolean committing;

        Encoder(Path directory, UUID round, ReplayExportResolution resolution, boolean compress,
                ReplayExportQuality quality, @Nullable Path ffmpegExecutable, int frames,
                @Nullable Path preMixedAudioPcm, @Nullable String focusSlug) {
            this.directory = directory;
            this.resolution = resolution;
            this.compress = compress;
            this.quality = quality == null ? ReplayExportQuality.DEFAULT : quality;
            this.ffmpegExecutable = ffmpegExecutable;
            this.frames = frames;
            this.preMixedAudioPcm = preMixedAudioPcm;
            int poolSize = resolution.highLoad() ? 2 : 4;
            this.pool = new ArrayBlockingQueue<>(poolSize);
            this.pending = new ArrayBlockingQueue<>(poolSize);
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String ext = compress ? ".mp4" : ".avi";
            String focusPart = focusSlug == null || focusSlug.isBlank() ? "" : "-" + focusSlug;
            output = directory.resolve("replay-" + date + "-" + round.toString().substring(0, 8)
                    + focusPart + "-" + resolution.id + "-" + UUID.randomUUID().toString().substring(0, 8) + ext);
            int frameBytes = resolution.frameBytes();
            for (int i = 0; i < poolSize; i++) pool.add(new VideoFrame(frameBytes));
            thread.setDaemon(true);
        }

        boolean cancel() {
            ReplayVideoWriter writerToAbort;
            synchronized (this) {
                if (committing || saved != null || failed) return false;
                cancelled = true;
                writerToAbort = activeWriter;
            }
            thread.interrupt();
            if (writerToAbort != null) {
                writerToAbort.abort();
            }
            return true;
        }

        @Override public void run() {
            Path temp = null;
            int width = resolution.width;
            int height = resolution.height;
            int audioFrameBytes = ReplayVideoAudioRenderer.SAMPLES_PER_VIDEO_FRAME * ReplayVideoAudioRenderer.CHANNELS * 2;
            byte[] audioChunk = compress ? null : new byte[audioFrameBytes];
            InputStream aviAudioStream = null;
            try {
                byte[] row = new byte[width * 3];
                Files.createDirectories(directory);
                temp = Files.createTempFile(directory, ".replay-", ".part");
                if (!compress && preMixedAudioPcm != null && Files.isRegularFile(preMixedAudioPcm)) {
                    aviAudioStream = new BufferedInputStream(Files.newInputStream(preMixedAudioPcm), 65536);
                }
                try (ReplayVideoWriter writer = compress
                        ? new ReplayMp4Writer(ffmpegExecutable, temp, width, height, FPS, preMixedAudioPcm, quality, false)
                        : new ReplayAviWriter(temp, width, height, FPS, true)) {
                    activeWriter = writer;
                    if (cancelled) {
                        writer.abort();
                        return;
                    }
                    try {
                        while (written < frames && !cancelled) {
                            VideoFrame frame = pending.poll(250, TimeUnit.MILLISECONDS);
                            if (frame == null) continue;
                            if (frame.aberration > 0.02f) aberrate(frame.data, row, width, height, frame.aberration);
                            writer.add(frame.data);
                            if (!compress && audioChunk != null) {
                                int filled = 0;
                                if (aviAudioStream != null) {
                                    while (filled < audioFrameBytes) {
                                        int n = aviAudioStream.read(audioChunk, filled, audioFrameBytes - filled);
                                        if (n < 0) break;
                                        filled += n;
                                    }
                                }
                                if (filled < audioFrameBytes) Arrays.fill(audioChunk, filled, audioFrameBytes, (byte) 0);
                                writer.addAudioBytes(audioChunk, 0, audioFrameBytes);
                            }
                            written++;
                            pool.offer(frame);
                        }
                        if (cancelled) return;
                        writer.finish();
                    } finally {
                        activeWriter = null;
                    }
                }
                synchronized (this) {
                    if (cancelled) return;
                    committing = true;
                }
                moveFileWithRetry(temp, output);
                saved = output;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordFailure(interrupted);
            } catch (IOException | RuntimeException | Error failure) {
                recordFailure(failure);
            } finally {
                if (aviAudioStream != null) {
                    try { aviAudioStream.close(); } catch (IOException ignored) {}
                }
                if (preMixedAudioPcm != null) {
                    try {
                        deleteFileWithRetry(preMixedAudioPcm);
                    } catch (IOException failure) {
                        LOGGER.warn("Could not remove temporary replay audio file", failure);
                    }
                    preMixedAudioPcm = null;
                }
                pool.clear();
                pending.clear();
                if (temp != null) {
                    try {
                        deleteFileWithRetry(temp);
                    } catch (IOException failure) {
                        LOGGER.warn("Could not remove partial replay video", failure);
                    }
                }
            }
        }

        private synchronized void recordFailure(Throwable failure) {
            if (!cancelled) {
                failed = true;
                failureStatus = classifyEncoderFailure(failure, committing);
                LOGGER.warn("Could not write replay video", failure);
            }
        }
    }

    static String classifyEncoderFailure(Throwable failure, boolean committing) {
        if (failure instanceof ReplayAviWriter.AviSizeLimitException) return "avi_limit_exceeded";
        if (failure instanceof ReplayMp4Writer.FfmpegTimeoutException) return "ffmpeg_timeout";
        if (failure instanceof ReplayMp4Writer.FfmpegProcessException) return "ffmpeg_failed";
        if (committing || failure instanceof java.nio.file.FileSystemException) return "disk_error";
        return "failed";
    }

    @FunctionalInterface
    interface FileDeleter {
        boolean deleteIfExists(Path path) throws IOException;
    }

    static void deleteFileWithRetry(@Nullable Path path) throws IOException {
        deleteFileWithRetry(path, new long[]{50L, 100L, 200L, 350L, 500L}, Files::deleteIfExists);
    }

    static void deleteFileWithRetry(@Nullable Path path, long[] backoffsMs, FileDeleter deleter) throws IOException {
        if (path == null) return;
        boolean wasInterrupted = Thread.interrupted();
        IOException lastFailure = null;
        try {
            for (int attempt = 0; attempt <= backoffsMs.length; attempt++) {
                try {
                    deleter.deleteIfExists(path);
                    return;
                } catch (IOException failure) {
                    lastFailure = failure;
                    if (attempt == backoffsMs.length) break;
                    long delayMs = backoffsMs[attempt];
                    if (delayMs > 0L) {
                        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
                        while (true) {
                            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
                            if (remainingMs <= 0L) break;
                            try {
                                Thread.sleep(remainingMs);
                            } catch (InterruptedException interrupted) {
                                wasInterrupted = true;
                            }
                        }
                    }
                }
            }
            throw lastFailure;
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt();
        }
    }

    static void moveFileWithRetry(Path source, Path target) throws IOException {
        long[] backoffsMs = {100L, 200L, 400L, 600L, 800L, 1000L};
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= backoffsMs.length; attempt++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                if (attempt == backoffsMs.length) break;
                try {
                    Thread.sleep(backoffsMs[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while committing replay video file", interrupted);
                }
            }
        }
        throw lastFailure;
    }
}
