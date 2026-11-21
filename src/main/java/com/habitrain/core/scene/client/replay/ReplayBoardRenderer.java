package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.client.mixin.FrustumAccessor;
import com.habitrain.core.client.render.ClientRenderState;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.client.SceneShaders;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayBoardPlacement;
import com.habitrain.core.scene.replay.ReplayFanCutaway;
import com.habitrain.core.scene.replay.ReplayFocusPlan;
import com.habitrain.core.scene.replay.ReplayMapViewport;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import io.wifi.starrailexpress.event.client.OnGameStartedClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;

/**
 * World renderer for settlement summary boards: a wooden diorama tray one block above each loaded
 * board, the shared miniature of the last match's map, synchronised avatars, trails and event nodes,
 * and the honors screen.
 *
 * <p>Boards are not block-entity rendered: the vanilla per-block-entity culling box would clip a
 * configurable diorama. Instead the loaded-board index from {@link ClientReplayBoard} is culled here
 * against the whole table, transformed tray and honors screen volume, faded between 48 and 64 blocks and
 * capped to the nearest few boards. Only the nearest board renews the data demand; the others reuse
 * the latest-match view unless a historical match was shared to a specific table.</p>
 */
public final class ReplayBoardRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayBoardRenderer.class.getSimpleName());
    private static final int MAX_BOARDS = 4;
    private static final float DEFAULT_TRAY_HALF_SIZE = 2.3f;
    private static final float TRAY_MARGIN = 0.3f;
    private static final float WORLD_TRAY_HALF_SIZE = ReplayBoardSettings.DEFAULT_MAP_SIZE / 2 + TRAY_MARGIN;
    private static final ReplayHonorsLayer HONORS_LAYER = new ReplayHonorsLayer();

    private static MultiBufferSource.BufferSource buffers;
    private static final List<ByteBufferBuilder> OWNED = new ArrayList<>();
    private static final ReplayFanCutaway FAN_CUTAWAY = new ReplayFanCutaway();
    private static RenderType trayType;
    private static @Nullable ReplayBoardModel model;
    private static @Nullable LastMatchReplay modelSource;
    private static final java.util.LinkedHashMap<java.util.UUID, ReplayBoardModel> archivedModels = new java.util.LinkedHashMap<>(8, .75f, true);
    private static @Nullable ClientLevel observedLevel;
    private static @Nullable ClientLevel animOriginLevel;
    private static long animOriginTick;
    private static long lastAnimGameTime;
    private static boolean registered;
    private static long nextRenderAttempt;
    private static long lastHonorsDraw;

    private ReplayBoardRenderer() {}

    public static void init() {
        if (registered) return;
        registered = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ReplayBoardRenderer::render);
        ReplayBoardLocalState.register();
        OnGameStartedClient.EVENT.register(() -> {
            var client = Minecraft.getInstance();
            client.execute(() -> ReplayExportClient.onGameStarted(client));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ReplayBoardRenderer::reset));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != observedLevel) {
                observedLevel = client.level;
                animOriginLevel = null;
                nextRenderAttempt = 0;
                lastHonorsDraw = 0;
                HONORS_LAYER.close();
                ReplayDioramaMesh.reset();
                model = null;
                modelSource = null;
            }
            if (lastHonorsDraw != 0 && System.nanoTime() - lastHonorsDraw >= 3_000_000_000L) {
                HONORS_LAYER.close();
                lastHonorsDraw = 0;
            }
            ReplayDioramaMesh.tickIdle();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ReplayExportClient.reset();
            reset();
        });
    }

    /** Resource reload: atlas sprites and baked models changed, so the diorama mesh must be rebuilt. */
    public static void onResourceReload() {
        ReplayTrayPainter.invalidateSprites();
        ReplayVideoCanvas.invalidate();
        ReplayDioramaMesh.reset();
    }

    /** Disconnect / session reset: drop GPU mesh, cached skins, models and local toggles. */
    public static void reset() {
        ReplayAudioPlaybackController.reset();
        ReplayExportClient.reset();
        nextRenderAttempt = 0;
        lastHonorsDraw = 0;
        releaseBuffers();
        animOriginLevel = null;
        ReplayDioramaMesh.reset();
        ReplayHeadSkins.clear();
        ReplayBoardLocalState.reset();
        ReplayBoardButtons.reset();
        archivedModels.clear();
        model = null;
        modelSource = null;
    }

    private static void render(WorldRenderContext context) {
        if (context == null || context.camera() == null
                || nextRenderAttempt != 0 && System.nanoTime() - nextRenderAttempt < 0) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        ReplayBoardButtons.beginFrame(mc, context.camera().getPosition(),
                context.tickCounter().getGameTimeDeltaPartialTick(true));
        List<BlockPos> loaded = ClientReplayBoard.loadedBoards();
        if (loaded.isEmpty()) {
            ReplayAudioPlaybackController.endBoardFrame(mc, Set.of());
            return;
        }
        try {
            renderBoards(context, mc, level, loaded);
        } catch (RuntimeException failure) {
            // A renderer bug must never take the world pass down with it.
            nextRenderAttempt = System.nanoTime() + 3_000_000_000L;
            ReplayBoardButtons.reset();
            releaseBuffers();
            ReplayAudioPlaybackController.endBoardFrame(mc, Set.of());
            LOGGER.error("Settlement board rendering paused after an unexpected failure", failure);
        }
    }

    private record Candidate(BlockPos pos, Direction facing, double distance, ReplayBoardSettings settings,
                             @Nullable SettlementSummaryBoard.Marker.Playback playback, boolean visible) {}

    private record BoardDraw(ReplayFrame frame, ReplayBoardSite site, @Nullable ClientReplayBoard.View mapView) {}

    static float trayHalfSize(@Nullable ReplayBoardModel current) {
        if (current == null || current.layout == null) return DEFAULT_TRAY_HALF_SIZE;
        // Fit the longer map axis without changing the diorama's scale or aspect ratio.
        return Math.max(1.3f, Math.max(current.mapW, current.mapD) / 2 + TRAY_MARGIN);
    }

    private static void renderBoards(WorldRenderContext context, Minecraft mc, ClientLevel level, List<BlockPos> loaded) {
        Vec3 cam = context.camera().getPosition();
        Frustum frustum = context.frustum();
        List<Candidate> candidates = new ArrayList<>();
        for (BlockPos pos : loaded) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(SettlementSummaryBoard.BLOCK)) continue;
            if (!(level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) continue;
            ReplayBoardSettings preview = ReplayBoardSettingsScreen.previewAt(pos);
            ReplayBoardSettings settings = preview == null ? marker.settings() : preview;
            Direction facing = state.getValue(SettlementSummaryBoard.FACING);
            Matrix4f worldTable = ReplayFrame.boardMatrix(pos, facing, 0, 0, 0);
            Vector3f center = worldTable.transformPosition(settings.offsetX(), settings.height(), settings.offsetZ(), new Vector3f());
            double distance = settings.viewDistance(pos, facing, cam);
            double distScale = Math.max(1.0, settings.scale());
            if (distance > ReplayStagePlan.FADE_END * distScale) continue;
            float trayHalf = WORLD_TRAY_HALF_SIZE;
            // Rotation can point the square tray's diagonal at the camera. Include all 4 plaques,
            // the floating honors screen, and the real table even when offset from the marker.
            float honorsRadius = ReplayHonorsPainter.PANEL_RADIUS * ReplayHonorsPainter.honorsWorldScale(1, trayHalf);
            float honorsCenter = ReplayHonorsPainter.anchorHeight(trayHalf) - ReplayBoardSite.MAP_BASE;
            float reach = Math.max((trayHalf + 0.65f) * 1.415f, honorsRadius) * settings.scale();
            float visualBottom = Math.min(-0.7f, honorsCenter - honorsRadius) * settings.scale();
            float visualTop = Math.max(Math.max(3.8f, honorsCenter + honorsRadius),
                    model == null ? 0 : (model.mapH + 0.8f) * (float) (ReplayFocusPlan.MAX_ZOOM * 1.15)) * settings.scale();
            boolean visible = frustum == null || ((FrustumAccessor) frustum).habitrain$cubeInFrustum(
                    Math.min(pos.getX(), center.x - reach), Math.min(pos.getY(), center.y + visualBottom),
                    Math.min(pos.getZ(), center.z - reach), Math.max(pos.getX() + 1, center.x + reach),
                    Math.max(pos.getY() + 1, center.y + visualTop), Math.max(pos.getZ() + 1, center.z + reach));
            var playback = marker.playback();
            if (visible || playback != null && playback.pendingOrPlaying(level.getGameTime()))
                candidates.add(new Candidate(pos, facing, distance, settings, playback, visible));
        }
        if (candidates.isEmpty()) {
            ReplayHistoryClient.updateBoardPins(Set.of());
            ReplayAudioPlaybackController.endBoardFrame(mc, Set.of());
            return;
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance));
        ClientReplayBoard.View view = ClientReplayBoard.viewAt(candidates.getFirst().pos());
        boolean roundAvailable = !ReplayPackets.EMPTY.equals(ClientReplayBoard.availableRound());
        for (int index = 1; roundAvailable && !view.hasRound() && index < candidates.size(); index++) {
            view = ClientReplayBoard.viewAt(candidates.get(index).pos());
        }
        LastMatchReplay replay = view.replay();
        ReplayTimeline timeline = view.timeline();
        if (replay == null || timeline == null) {
            if (!view.hasRound()) { model = null; modelSource = null; }
        } else if (replay != modelSource) {
            if (model != null && !model.replay.roundId().equals(replay.roundId())) ReplayBoardLocalState.reset();
            model = new ReplayBoardModel(replay, timeline);
            modelSource = replay;
        }
        // While a newer round's summary is still in flight, show the loading state rather than the stale model.
        ReplayBoardModel current = view.hasRound() && replay != null ? model : null;
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(true);
        long gameTime = level.getGameTime();
        if (level != animOriginLevel) {
            animOriginLevel = level;
            animOriginTick = gameTime;
            lastAnimGameTime = gameTime;
        } else if (Math.abs(gameTime - lastAnimGameTime) > 200L) {
            // Re-anchor smoothly when the initial server time packet arrives on pre-aged lobby saves
            animOriginTick += (gameTime - lastAnimGameTime);
            lastAnimGameTime = gameTime;
        } else {
            lastAnimGameTime = gameTime;
        }
        double anim = ((double) (gameTime - animOriginTick) + (double) partial) / 20.0;

        var round = ClientReplayBoard.availableRound();
        MultiBufferSource.BufferSource source = buffers();
        var camera = context.camera();
        boolean allowBoardAudio = !ClientReplayBoard.inMatch(level);
        Set<BlockPos> activeAudioBoards = new HashSet<>();
        Set<java.util.UUID> activeHistoricalRounds = new HashSet<>();
        List<BoardDraw> boards = new ArrayList<>(MAX_BOARDS);
        try (var guard = ClientRenderState.capture()) {
            for (Candidate c : candidates) {
                double distScale = Math.max(1.0, c.settings().scale());
                float fade = (float) ReplayStagePlan.distanceFade(c.distance() / distScale);
                if (fade <= 0.01f) continue;
                var playing = c.playback();
                boolean active = playing != null && playing.playing(gameTime);
                boolean draw = c.visible() && boards.size() < MAX_BOARDS;
                if (!draw && !active) continue;
                boolean historical = playing != null && playing.pendingOrPlaying(gameTime)
                        && (playing.shared() || !playing.roundId().equals(round));
                var selected = historical ? ReplayHistoryClient.watch(playing.roundId()) : view;
                if (historical) activeHistoricalRounds.add(playing.roundId());
                ReplayBoardModel shown = null;
                if (active && selected.replay() != null && selected.timeline() != null) {
                    if (selected == view) shown = current;
                    else {
                        var id = selected.replay().roundId();
                        shown = archivedModels.get(id);
                        if (shown == null || shown.replay != selected.replay()) {
                            shown = new ReplayBoardModel(selected.replay(), selected.timeline());
                            archivedModels.put(id, shown);
                            while (archivedModels.size() > MAX_BOARDS) archivedModels.pollFirstEntry();
                        }
                    }
                }
                double elapsed = active ? gameTime - playing.startTick() + partial : 0;
                int light = LightTexture.FULL_BRIGHT;
                if (draw) {
                    int raw = LevelRenderer.getLightColor(level, c.pos().above());
                    light = LightTexture.pack(Math.max(7, LightTexture.block(raw)), LightTexture.sky(raw));
                }
                Matrix4f table = ReplayFrame.boardMatrix(c.pos(), c.facing(), cam.x, cam.y, cam.z);
                Matrix4f display = ReplayBoardPlacement.displayMatrix(table, c.settings(), ReplayBoardSite.MAP_BASE);
                var site = new ReplayBoardSite(c.pos(), c.facing(), c.distance(), display, table, c.settings().scale(),
                        WORLD_TRAY_HALF_SIZE, WORLD_TRAY_HALF_SIZE, fade,
                        c.distance() < ReplayStagePlan.DETAIL_DISTANCE * distScale, light);
                if (active && shown != null && allowBoardAudio && activeAudioBoards.isEmpty()) {
                    activeAudioBoards.add(c.pos().immutable());
                    ReplayAudioPlaybackController.updateBoard(mc, c.pos(), cam, site, shown, selected, true, elapsed);
                }
                if (!draw) continue;
                ReplayTimeline.Frame frame = shown == null ? null : shown.timeline.atElapsed(elapsed);
                boolean loading = selected.hasRound() && selected.replay() == null
                        || playing != null && playing.preparing();
                var f = new ReplayFrame(mc, level, camera.rotation(), source, trayType,
                        active ? elapsed / 20.0 : anim, anim, shown, frame, loading,
                        active ? selected.presentSections() : 0, active ? selected.totalSections() : 0,
                        new Vector3f(camera.getLeftVector()).negate(), new Vector3f(camera.getUpVector()),
                        new Vector3f(camera.getLookVector()));
                boards.add(new BoardDraw(f, site, playing != null && playing.pendingOrPlaying(gameTime) ? selected : null));
            }
            ReplayHistoryClient.updateBoardPins(activeHistoricalRounds);
            drawBoards(context, mc, boards);
        }
        ReplayAudioPlaybackController.endBoardFrame(mc, activeAudioBoards);
    }

    private static void drawBoards(WorldRenderContext context, Minecraft mc, List<BoardDraw> boards) {
        boolean honors = false;
        for (var board : boards) {
            if (ReplayHonorsPainter.visible(board.frame())) honors = true;
            else ReplayIdleCubes.prepareButtons(board.frame(), board.site());
        }
        var world = mc.getMainRenderTarget();
        boolean isolated = honors && HONORS_LAYER.captureWorldDepth(world);
        if (isolated) lastHonorsDraw = System.nanoTime();
        for (var board : boards) {
            SceneMeshSet mesh = null;
            var mapView = board.mapView();
            board.frame().meshFailed = mapView != null && mapView.mapFailed();
            if (mapView != null && mapView.asset() != null) {
                ReplayDioramaMesh.updateWorld(mapView.asset(), mapView.presentSections(), mapView.totalSections());
                if (ReplayDioramaMesh.matches(mapView.asset())) mesh = ReplayDioramaMesh.current();
                board.frame().meshFailed |= mesh == null && ReplayDioramaMesh.failed(
                        new ReplayDioramaMesh.Key(mapView.asset().fingerprint, mapView.presentSections()));
            }
            drawFrame(context.positionMatrix(), context.projectionMatrix(), board.frame(), board.site(), mesh,
                    ReplayBoardLocalState.fullBody());
        }
        if (!honors) return;
        Runnable paint = () -> {
            for (var board : boards.reversed()) {
                if (!ReplayHonorsPainter.visible(board.frame())) continue;
                ReplayHonorsPainter.paint(board.frame(), board.site());
                flushBoardPasses(board.frame());
            }
        };
        if (isolated) HONORS_LAYER.drawOver(world, paint);
        else paint.run();
    }

    static ReplayActorSizing drawVideoFrame(Minecraft mc, ReplayBoardModel current, ReplayTimeline.Frame frame, ReplayStagePlan.Stage stage,
                               double anim, ReplayVideoCamera.Shot shot, SceneMeshSet mesh, boolean fullBody, ReplayVideoFx fx) {
        var buffers = buffers();
        var eye = shot.eye();
        var board = new Matrix4f().translation(-eye.x, -eye.y, -eye.z);
        float halfSize = trayHalfSize(current);
        var site = new ReplayBoardSite(BlockPos.ZERO, Direction.SOUTH, 0, board, board, 1,
                halfSize, halfSize, 1, true, LightTexture.FULL_BRIGHT);
        Quaternionf rotation = shot.rotation();
        var f = new ReplayFrame(mc, mc.level, rotation, buffers, trayType, anim, anim, current,
                frame, false, 0, 0, rotation.transform(new Vector3f(1, 0, 0)),
                rotation.transform(new Vector3f(0, 1, 0)), shot.look(), stage);
        Matrix4f diorama = diorama(f, site);
        var heads = new ReplayActorSizing(current.scale, 1, diorama, shot.view(), shot.projection(), shot::emphasis, true);
        // Same pass order as the board, minus the in-world briefing and honors panel: the video draws
        // those as designed 2D motion graphics instead.
        var source = f.buffers;
        ReplayTrayPainter.tray(f, site);
        source.endBatch(trayType);
        drawMap(shot.view(), shot.projection(), f, site, mesh, fullBody, diorama, heads);
        ReplayTrayPainter.lights(f, site);
        ReplayTrayPainter.plaque(f, site);
        if (fx != null) fx.environment(f, site);
        if (current.layout != null) {
            float sink = (float) stage.mapSink() * 0.2f;
            try (var mapScope = f.mapScope(site)) {
                ReplayTrayPainter.overlays(f, site, diorama, sink);
                ReplayActorPainter.paint(f, site, diorama, heads);
                ReplayDronePainter.paint(f, site, diorama);
                ReplayEventPainter.paint(f, site, diorama);
                if (fx != null) fx.map(f, site, diorama, heads);
            }
        }
        source.endBatch(ReplayDraw.FILL);
        f.flushModels();
        source.endBatch(ReplayDraw.GLOW);
        f.flushTexts();
        source.endBatch();
        return heads;
    }

    private static void drawFrame(Matrix4f view, Matrix4f projection, ReplayFrame f, ReplayBoardSite site,
                                  @Nullable SceneMeshSet mesh, boolean fullBody) {
        var source = f.buffers;
        ReplayTrayPainter.tray(f, site);
        source.endBatch(trayType);
        if (f.model != null && f.model.layout != null && f.stage != null) {
            float sink = (float) f.stage.mapSink() * 0.2f;
            Matrix4f d = diorama(f, site);
            var heads = new ReplayActorSizing(f.model.scale, site.scale() * (float) f.focus.zoom(), d, view, projection,
                    f.focusSample::emphasis, false);
            if (mesh != null) drawMap(view, projection, f, site, mesh, fullBody, d, heads);
            try (var mapScope = f.mapScope(site)) {
                if (mesh == null && f.stage.scan() > 0.01) loadingOutline(f, site, d, f.model);
                ReplayTrayPainter.overlays(f, site, d, sink);
                ReplayActorPainter.paint(f, site, d, heads);
                ReplayDronePainter.paint(f, site, d);
                ReplayEventPainter.paint(f, site, d);
            }
            ReplayEventPainter.briefing(f, site);
        }
        ReplayTrayPainter.lights(f, site);
        ReplayTrayPainter.plaque(f, site);
        ReplayTrayPainter.handoff(f, site);
        if (!ReplayHonorsPainter.visible(f)) ReplayIdleCubes.paint(f, site);
        flushBoardPasses(f);
    }

    private static void flushBoardPasses(ReplayFrame f) {
        var source = f.buffers;
        source.endBatch(ReplayDraw.FILL);
        // In-world buttons write depth, so they must be flushed before the additive glow pass.
        source.endBatch(ReplayDraw.PANEL);
        f.flushModels();
        source.endBatch(ReplayDraw.GLOW);
        f.flushTexts();
        source.endBatch();
    }

    private static Matrix4f diorama(ReplayFrame frame, ReplayBoardSite site) {
        float sink = frame.stage == null ? 0 : (float) frame.stage.mapSink() * 0.2f;
        return frame.focus.apply(new Matrix4f(site.board()).translate(0, ReplayBoardSite.MAP_BASE - sink, 0));
    }

    private static void loadingOutline(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayBoardModel current) {
        double reveal = f.stage.scan();
        double pulse = reveal * (0.45 + 0.35 * Math.sin(f.anim * 3.2));
        float w = current.mapW / 2, dd = current.mapD / 2, h = Math.max(0.1f, current.mapH);
        ReplayDraw.wireBox(f.glow(), d, -w, 0, -dd, w, h, dd, 0.008f, ReplayPalette.alpha(ReplayPalette.BRASS, pulse * s.fade()));
        double sweep = (f.anim * 0.4) % 1.0;
        float x = -w + (float) (2 * w * sweep);
        int c = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.35 * s.fade() * reveal);
        ReplayDraw.quad(f.glow(), d, x, 0.004f, -dd, x, 0.004f, dd, x, h, dd, x, h, -dd, c, c,
                ReplayPalette.fade(c, 0), ReplayPalette.fade(c, 0));
    }

    private static void drawMap(Matrix4f view, Matrix4f projection, ReplayFrame f, ReplayBoardSite s,
                                SceneMeshSet mesh, boolean fullBody, Matrix4f diorama, ReplayActorSizing heads) {
        ReplayBoardModel current = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        if (current == null || current.layout == null || stage == null || stage.scan() <= 0.001) return;
        var src = current.source;
        Matrix4f sourceToDiorama = new Matrix4f().rotateY(current.rotated ? (float) Math.toRadians(90) : 0f)
                .scale(current.scale)
                .translate(-src.sizeX() * 0.5f, 0, -src.sizeZ() * 0.5f);
        Matrix4f sourceToTray = f.focus.apply(new Matrix4f()).mul(sourceToDiorama);
        Matrix4f model = new Matrix4f(diorama).mul(sourceToDiorama);
        var bounds = ReplayMapViewport.sourceBounds(sourceToTray, s.hw() - ReplayBoardSite.FRAME - 0.04f,
                s.hd() - ReplayBoardSite.FRAME - 0.04f);
        Matrix4f modelView = new Matrix4f(view).mul(model);
        double dim = (0.55 + 0.45 * s.fade()) * (1 - stage.mapSink() * 0.6);
        float r = (float) dim, g = (float) dim, b = (float) dim;
        if (f.blackout) {
            double pulse = 0.5 + 0.5 * Math.sin(f.anim * 5.2);
            r *= (float) (0.5 + 0.08 * pulse);
            g *= 0.34f;
            b *= 0.38f;
        }
        float[] previous = RenderSystem.getShaderColor().clone();
        boolean clip = stage.scan() < 0.999;
        int axisLength = current.rotated ? src.sizeZ() : src.sizeX();
        float threshold = (float) (stage.scan() * (axisLength + 1.0)) - 0.5f;
        updateFans(f, current, model, fullBody, heads);
        // The tray already has a local fill light. Give the train the same minimum so
        // the lobby's night lightmap cannot leave the train dark on a lit tray.
        try (var scope = SceneShaders.model(model, s.light());
             var boundsScope = SceneShaders.bounds(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
             var fanScope = SceneShaders.fans(FAN_CUTAWAY);
             var clipScope = clip ? (current.rotated ? SceneShaders.clip(0, 0, 1, threshold) : SceneShaders.clip(1, 0, 0, threshold))
                     : SceneShaders.clip(0, 0, 0, 1)) {
            RenderSystem.setShaderColor(r, g, b, 1f);
            opaque(mesh, modelView, projection);
            for (SceneMeshSet.Batch batch : mesh.batches()) opaque(batch.mesh(), modelView, projection);
            mesh.renderLayer(SceneMeshSet.Layer.TRANSLUCENT, modelView, projection, RenderType.translucent());
            mesh.renderCustomTranslucentBatches(modelView, projection);
        } finally {
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }

    private static void updateFans(ReplayFrame f, ReplayBoardModel current, Matrix4f model, boolean fullBody, ReplayActorSizing heads) {
        FAN_CUTAWAY.clear();
        if (fullBody || f.stage == null || f.stage.actors() <= 0.01) return;
        // Use a direction (w=0), not a position: offsets, table rotation and display size must
        // not reverse the opening. A quarter-turn source map is accounted for by this matrix too.
        FAN_CUTAWAY.faceViewer(model, new Vector3f(f.look), current.source);
        float[] projected = new float[3];
        for (ReplayBoardModel.Actor actor : current.actors) {
            var pose = f.pose(actor);
            if (!pose.visible()) continue;
            current.project(pose.x(), pose.y(), pose.z(), projected);
            float size = heads.size(actor.p.id(), projected[0], projected[1], projected[2]);
            if (size <= 0) continue;
            double hatClearance = pose.has(LastMatchReplay.DISGUISED) ? 1.2 : 0.82;
            FAN_CUTAWAY.add(pose, current.layout.origin(), current.source, Math.max(1.25, size * hatClearance / current.scale));
        }
        for (var drone : current.drones) {
            FAN_CUTAWAY.add(drone.track().sample(f.replayTick), current.layout.origin(), current.source,
                    Math.max(1, 0.19 / current.scale));
        }
    }

    private static void opaque(SceneMeshSet mesh, Matrix4f modelView, Matrix4f projection) {
        mesh.renderLayer(SceneMeshSet.Layer.SOLID, modelView, projection, RenderType.solid());
        mesh.renderLayer(SceneMeshSet.Layer.CUTOUT_MIPPED, modelView, projection, RenderType.cutoutMipped());
        mesh.renderLayer(SceneMeshSet.Layer.CUTOUT, modelView, projection, RenderType.cutout());
        mesh.renderCustomOpaqueBatches(modelView, projection);
    }

    private static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            trayType = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
            SequencedMap<RenderType, ByteBufferBuilder> fixed = new LinkedHashMap<>();
            fixed.put(trayType, own(1 << 17));
            fixed.put(ReplayDraw.FILL, own(1 << 18));
            fixed.put(ReplayDraw.PANEL, own(1 << 15));
            fixed.put(ReplayDraw.GLOW, own(1 << 18));
            for (var type : ReplayFrame.glintTypes()) fixed.put(type, own(4096));
            buffers = MultiBufferSource.immediateWithBuffers(fixed, own(1 << 17));
        }
        return buffers;
    }

    private static ByteBufferBuilder own(int size) {
        ByteBufferBuilder builder = new ByteBufferBuilder(size);
        OWNED.add(builder);
        return builder;
    }

    private static void releaseBuffers() {
        HONORS_LAYER.close();
        buffers = null;
        for (ByteBufferBuilder builder : OWNED) builder.close();
        OWNED.clear();
    }
}
