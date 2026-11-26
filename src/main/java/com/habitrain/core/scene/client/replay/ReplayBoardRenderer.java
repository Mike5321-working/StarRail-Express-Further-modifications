package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.client.mixin.FrustumAccessor;
import com.habitrain.core.client.render.ClientRenderState;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.client.SceneShaders;
import com.habitrain.core.scene.replay.ReplayBoardPlacement;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayFanCutaway;
import com.habitrain.core.scene.replay.ReplayFocusPlan;
import com.habitrain.core.scene.replay.ReplayMapViewport;
import com.habitrain.core.scene.replay.ReplayStagePlan;
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
import net.minecraft.util.Mth;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;

/**
 * World renderer for settlement summary boards: a wooden diorama tray one block above each loaded
 * board, the shared miniature of the match map (with seek-safe dynamic doors/walls), full 3D
 * humanoid players, entities &amp; multi-mod skills, smooth over-the-shoulder follow rotation,
 * Side-Edge Deck Console interactive controls, and the anti-occlusion holographic kill feed.
 *
 * <p>Boards are not block-entity rendered: the vanilla per-block-entity culling box would clip a
 * configurable diorama or elevated kill feed. Instead the loaded-board index from
 * {@link ClientReplayBoard} is culled here against the whole table, tray, and elevated feed
 * envelope, faded between 48 and 64 blocks and capped to the nearest few boards.</p>
 */
public final class ReplayBoardRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayBoardRenderer.class.getSimpleName());
    private static final int MAX_BOARDS = 4;
    private static final float DEFAULT_TRAY_HALF_SIZE = 2.3f;
    private static final float TRAY_MARGIN = 0.3f;
    private static final float WORLD_TRAY_HALF_SIZE = ReplayBoardSettings.DEFAULT_MAP_SIZE / 2 + TRAY_MARGIN;
    private static final float FOLLOW_ZOOM = 1.65f;
    private static final float FOLLOW_BACK_SHIFT = 0.38f;
    private static final ReplayHonorsLayer HONORS_LAYER = new ReplayHonorsLayer();
    private static final ReplayTrayMeshCache TRAY_MESHES = new ReplayTrayMeshCache();

    private static MultiBufferSource.BufferSource buffers;
    private static final List<ByteBufferBuilder> OWNED = new ArrayList<>();
    private static final ReplayFanCutaway FAN_CUTAWAY = new ReplayFanCutaway();
    private static final Map<BlockPos, BoardAnimState> BOARD_STATES = new HashMap<>();
    private static final Map<BlockPos, ReplayBoardHistoryCache> SETTLED_HISTORY = new HashMap<>();
    private static RenderType trayType;
    private static @Nullable ReplayBoardModel model;
    private static final LinkedHashMap<UUID, ReplayBoardModel> archivedModels = new LinkedHashMap<>(8, .75f, true);
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
            ReplayExportClient.tickForeground(client);
            if (client.level != observedLevel) {
                observedLevel = client.level;
                animOriginLevel = null;
                nextRenderAttempt = 0;
                lastHonorsDraw = 0;
                HONORS_LAYER.close();
                TRAY_MESHES.clear();
                ReplayDioramaMesh.reset();
                BOARD_STATES.clear();
                SETTLED_HISTORY.clear();
                model = null;
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
        TRAY_MESHES.clear();
        ReplayVideoCanvas.invalidate();
        ReplayDioramaMesh.reset();
        SETTLED_HISTORY.clear();
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
        BOARD_STATES.clear();
        archivedModels.clear();
        model = null;
    }

    private static void render(WorldRenderContext context) {
        if (ReplayForegroundWorld.rendering()) return;
        if (context == null || context.camera() == null
                || nextRenderAttempt != 0 && System.nanoTime() - nextRenderAttempt < 0) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        ReplayBoardButtons.beginFrame(mc, context.camera().getPosition(),
                context.tickCounter().getGameTimeDeltaPartialTick(true));
        List<BlockPos> loaded = ClientReplayBoard.loadedBoards();
        if (loaded.isEmpty()) {
            BOARD_STATES.clear();
            SETTLED_HISTORY.clear();
            ReplayHistoryClient.updateBoardPins(Set.of());
            ReplayAudioPlaybackController.endBoardFrame(mc, Set.of());
            return;
        }
        var profiler = mc.getProfiler();
        profiler.push("habitrainReplayBoards");
        try {
            renderBoards(context, mc, level, loaded);
        } catch (RuntimeException failure) {
            // A renderer bug must never take the world pass down with it.
            nextRenderAttempt = System.nanoTime() + 3_000_000_000L;
            ReplayBoardButtons.reset();
            releaseBuffers();
            ReplayAudioPlaybackController.endBoardFrame(mc, Set.of());
            LOGGER.error("Settlement board rendering paused after an unexpected failure", failure);
        } finally {
            profiler.pop();
        }
    }

    private record Candidate(BlockPos pos, Direction facing, double distance, ReplayBoardSettings settings,
                             @Nullable ReplayBoardSessionState session,
                             @Nullable SettlementSummaryBoard.Marker.Playback legacyPlayback,
                             @Nullable ClientReplayBoard.ExitAnim exitAnim,
                             boolean visible) {
        boolean usesLatestSession(UUID latestRound, long gameTime) {
            if (session != null) return !session.shared() && session.roundId().equals(latestRound);
            if (legacyPlayback != null && legacyPlayback.pendingOrPlaying(gameTime)) {
                return !legacyPlayback.shared() && legacyPlayback.roundId().equals(latestRound);
            }
            return exitAnim != null && visible && exitAnim.roundId().equals(latestRound);
        }
    }

    private record FollowTransform(float anchorX, float anchorY, float anchorZ,
                                   float counterYawDeg, float zoom, float backShiftZ) {
        static final FollowTransform NONE = new FollowTransform(0f, 0f, 0f, 0f, 1.0f, 0f);

        boolean isActive() {
            return zoom > 1.005f || Math.abs(counterYawDeg) > 0.15f || Math.abs(backShiftZ) > 0.005f;
        }

        boolean isNonCardinalRotation() {
            float rem = Math.abs(counterYawDeg % 90f);
            return rem > 0.08f && rem < 89.92f;
        }

        Matrix4f apply(Matrix4f base) {
            return ReplayFocusPlan.applyFollowTransform(base, anchorX, anchorY, anchorZ, counterYawDeg, zoom, backShiftZ);
        }
    }

    private static final class BoardAnimState {
        @Nullable UUID roundId;
        double introStartSec = -1.0;
        double reviewStartSec = -1.0;
        double lastAnimSec = -1.0;
        double lastReplayTick;
        double smoothPlayerX, smoothPlayerY, smoothPlayerZ;
        double smoothCounterYawDeg;
        double smoothZoom = 1.0;
        double smoothBackShift;

        void syncRound(UUID nextRound, double animSec) {
            if (!Objects.equals(roundId, nextRound) || introStartSec < 0) {
                roundId = nextRound;
                introStartSec = animSec;
                reviewStartSec = -1.0;
                smoothPlayerX = smoothPlayerY = smoothPlayerZ = 0.0;
                smoothCounterYawDeg = 0.0;
                smoothZoom = 1.0;
                smoothBackShift = 0.0;
            }
        }

        void resetToIdle() {
            roundId = null;
            introStartSec = -1.0;
            reviewStartSec = -1.0;
            smoothPlayerX = smoothPlayerY = smoothPlayerZ = 0.0;
            smoothCounterYawDeg = 0.0;
            smoothZoom = 1.0;
            smoothBackShift = 0.0;
        }

        double buildProgress(double animSec) {
            if (introStartSec < 0) return 1.0;
            return Mth.clamp((animSec - introStartSec) / (ReplayStagePlan.LINEAR_INTRO_TICKS / 20.0), 0.0, 1.0);
        }

        double reviewProgress(boolean finishedHold, double animSec) {
            if (!finishedHold) {
                reviewStartSec = -1.0;
                return 0.0;
            }
            if (reviewStartSec < 0) reviewStartSec = animSec;
            return Mth.clamp((animSec - reviewStartSec) / 1.4, 0.0, 1.0);
        }

        FollowTransform stepFollow(@Nullable ReplayBoardModel model, @Nullable UUID followUuid,
                                   double replayTick, double animSec) {
            double dt = lastAnimSec < 0 ? 0.016 : Mth.clamp(animSec - lastAnimSec, 0.001, 0.12);
            lastAnimSec = animSec;

            double targetX = 0.0, targetY = 0.0, targetZ = 0.0;
            double targetYaw = 0.0;
            double targetZoom = 1.0;
            double targetBack = 0.0;
            boolean followingVisible = false;

            if (model != null && followUuid != null) {
                ReplayBoardModel.Actor actor = model.participant(followUuid);
                if (actor != null) {
                    var pose = actor.track.sample(replayTick);
                    if (pose.visible()) {
                        float[] proj = new float[3];
                        model.project(pose.x(), pose.y(), pose.z(), proj);
                        targetX = proj[0];
                        targetY = proj[1];
                        targetZ = proj[2];
                        targetYaw = ReplayFocusPlan.followCounterYawDeg(pose.headYawDegrees(), model.rotated);
                        targetZoom = FOLLOW_ZOOM;
                        targetBack = FOLLOW_BACK_SHIFT;
                        followingVisible = true;
                    }
                }
            }

            double alphaPos = 1.0 - Math.exp(-(followingVisible ? 10.0 : 8.5) * dt);
            double alphaAng = 1.0 - Math.exp(-(followingVisible ? 7.5 : 8.0) * dt);
            smoothPlayerX = Mth.lerp(alphaPos, smoothPlayerX, targetX);
            smoothPlayerY = Mth.lerp(alphaPos, smoothPlayerY, targetY);
            smoothPlayerZ = Mth.lerp(alphaPos, smoothPlayerZ, targetZ);
            smoothCounterYawDeg += ReplayFocusPlan.shortestAngleDeltaDeg((float) smoothCounterYawDeg, (float) targetYaw) * alphaAng;
            smoothZoom = Mth.lerp(alphaPos, smoothZoom, targetZoom);
            smoothBackShift = Mth.lerp(alphaPos, smoothBackShift, targetBack);

            if (!followingVisible && smoothZoom < 1.004 && Math.abs(smoothCounterYawDeg) < 0.12 && Math.abs(smoothBackShift) < 0.004) {
                smoothPlayerX = smoothPlayerY = smoothPlayerZ = 0.0;
                smoothCounterYawDeg = 0.0;
                smoothZoom = 1.0;
                smoothBackShift = 0.0;
                return FollowTransform.NONE;
            }
            return new FollowTransform((float) smoothPlayerX, (float) smoothPlayerY, (float) smoothPlayerZ,
                    (float) smoothCounterYawDeg, (float) smoothZoom, (float) smoothBackShift);
        }
    }

    private record BoardDraw(ReplayFrame frame, ReplayBoardSite site, @Nullable ClientReplayBoard.View mapView,
                             @Nullable ReplayBoardSessionState session, double displayTick, boolean finishedHold,
                             double playbackSpeed, boolean fullBody, FollowTransform followTransform,
                             @Nullable UUID followPlayerUuid) {}

    static float trayHalfSize(@Nullable ReplayBoardModel current) {
        if (current == null || current.layout == null) return DEFAULT_TRAY_HALF_SIZE;
        // Fit the longer map axis without changing the diorama's scale or aspect ratio.
        return Math.max(1.3f, Math.max(current.mapW, current.mapD) / 2 + TRAY_MARGIN);
    }

    /**
     * Computes tray-safe source-space clip bounds for {@link SceneShaders#bounds}. When the diorama is
     * rotated by a non-cardinal angle during over-the-shoulder follow mode, shrinks to the largest
     * inscribed source-space box centered on the tray so corners never protrude past the wooden rim.
     */
    static ReplayMapViewport.Bounds safeSourceBounds(Matrix4f sourceToTray, float innerHw, float innerHd,
                                                     boolean nonCardinalRotation) {
        if (!nonCardinalRotation) {
            return ReplayMapViewport.sourceBounds(sourceToTray, innerHw, innerHd);
        }
        Matrix4f inverse = new Matrix4f(sourceToTray).invert();
        Vector3f center = inverse.transformPosition(0f, 0f, 0f, new Vector3f());
        float denomX = Math.max(1.0e-5f, Math.abs(sourceToTray.m00()) + Math.abs(sourceToTray.m20()));
        float denomZ = Math.max(1.0e-5f, Math.abs(sourceToTray.m02()) + Math.abs(sourceToTray.m22()));
        float r = Math.min(innerHw / denomX, innerHd / denomZ);
        return new ReplayMapViewport.Bounds(center.x - r, center.z - r, center.x + r, center.z + r);
    }

    private static void renderBoards(WorldRenderContext context, Minecraft mc, ClientLevel level, List<BlockPos> loaded) {
        Vec3 cam = context.camera().getPosition();
        Frustum frustum = context.frustum();
        long gameTime = level.getGameTime();
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(true);

        BOARD_STATES.keySet().removeIf(pos -> !ClientReplayBoard.isLoadedBoard(pos));

        UUID latestRound = ClientReplayBoard.availableRound();
        if (model != null && !model.replay.roundId().equals(latestRound)) {
            model = null;
            ReplayBoardLocalState.reset();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (BlockPos pos : loaded) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(SettlementSummaryBoard.BLOCK)) continue;
            if (!(level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) continue;

            ReplayBoardSettings preview = ReplayBoardSettingsScreen.previewAt(pos);
            ReplayBoardSettings settings = preview == null ? marker.settings() : preview;
            Direction previewFacing = ReplayBoardSettingsScreen.previewFacingAt(pos);
            Direction facing = previewFacing != null ? previewFacing : state.getValue(SettlementSummaryBoard.FACING);

            double distance = settings.viewDistance(pos, facing, cam);
            double distScale = Math.max(1.0, settings.scale());
            if (distance > ReplayStagePlan.FADE_END * distScale) continue;

            double centerX = pos.getX() + 0.5 + settings.offsetX() * facing.getStepZ() + settings.offsetZ() * facing.getStepX();
            double centerY = pos.getY() + 1.0 + settings.height();
            double centerZ = pos.getZ() + 0.5 - settings.offsetX() * facing.getStepX() + settings.offsetZ() * facing.getStepZ();

            float trayHalf = WORLD_TRAY_HALF_SIZE;
            float honorsRadius = ReplayHonorsPainter.PANEL_RADIUS * ReplayHonorsPainter.honorsWorldScale(1, trayHalf);
            float honorsCenter = ReplayHonorsPainter.anchorHeight(trayHalf) - ReplayBoardSite.MAP_BASE;
            float reach = Math.max((trayHalf + 0.85f) * 1.415f, honorsRadius) * settings.scale();
            float visualBottom = Math.min(-0.85f, honorsCenter - honorsRadius) * settings.scale();
            float feedEnvelope = model == null ? 0f : ReplayStagePlan.effectiveFeedTop(
                    ReplayBoardSite.MAP_BASE, model.mapH, Double.NEGATIVE_INFINITY, model.scale,
                    ReplayFocusPlan.MAX_ZOOM, ReplayStagePlan.DEFAULT_FEED_CLEARANCE) + 1.45f;
            float visualTop = Math.max(Math.max(4.4f, honorsCenter + honorsRadius), feedEnvelope) * settings.scale();
            boolean visible = frustum == null || ((FrustumAccessor) frustum).habitrain$cubeInFrustum(
                    Math.min(pos.getX(), centerX - reach), Math.min(pos.getY(), centerY + visualBottom),
                    Math.min(pos.getZ(), centerZ - reach), Math.max(pos.getX() + 1, centerX + reach),
                    Math.max(pos.getY() + 1, centerY + visualTop), Math.max(pos.getZ() + 1, centerZ + reach));

            ReplayBoardSessionState session = ClientReplayBoard.boardSession(pos, marker);
            boolean hasSession = session != null && session.active();
            var legacy = hasSession ? null : marker.playback();
            var exitAnim = hasSession ? null : ClientReplayBoard.exitAnim(pos, gameTime);
            boolean running = hasSession || (legacy != null && legacy.pendingOrPlaying(gameTime));
            if (visible || running) {
                candidates.add(new Candidate(pos, facing, distance, settings, hasSession ? session : null,
                        legacy, exitAnim, visible));
            }
        }
        if (candidates.isEmpty()) {
            SETTLED_HISTORY.clear();
            ReplayHistoryClient.updateBoardPins(Set.of());
            ReplayAudioPlaybackController.endBoardFrame(mc, Set.of());
            return;
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance));

        ClientReplayBoard.View view = latestView(candidates, latestRound, gameTime);

        if (level != animOriginLevel) {
            animOriginLevel = level;
            animOriginTick = gameTime;
            lastAnimGameTime = gameTime;
        } else if (Math.abs(gameTime - lastAnimGameTime) > 200L) {
            animOriginTick += (gameTime - lastAnimGameTime);
            lastAnimGameTime = gameTime;
        } else {
            lastAnimGameTime = gameTime;
        }
        double anim = ((double) (gameTime - animOriginTick) + (double) partial) / 20.0;

        MultiBufferSource.BufferSource source = buffers();
        var camera = context.camera();
        Vector3f camRight = new Vector3f(camera.getLeftVector()).negate();
        Vector3f camUp = new Vector3f(camera.getUpVector());
        Vector3f camLook = new Vector3f(camera.getLookVector());

        boolean allowBoardAudio = !ClientReplayBoard.inMatch(level);
        Set<BlockPos> activeAudioBoards = new HashSet<>();
        Set<UUID> activeHistoricalRounds = new HashSet<>();
        List<BoardDraw> boards = new ArrayList<>(MAX_BOARDS);

        try (var guard = ClientRenderState.capture()) {
            for (Candidate c : candidates) {
                double distScale = Math.max(1.0, c.settings().scale());
                float fade = (float) ReplayStagePlan.distanceFade(c.distance() / distScale);
                if (fade <= 0.01f) continue;

                ReplayBoardSessionState session = c.session();
                var legacy = c.legacyPlayback();
                var exitAnim = c.exitAnim();
                boolean linearActive = session != null && session.active();
                boolean legacyActive = !linearActive && legacy != null && legacy.playing(gameTime);
                boolean draw = c.visible() && boards.size() < MAX_BOARDS;
                if (!draw && !linearActive && !legacyActive) continue;

                int light = LightTexture.FULL_BRIGHT;
                if (draw) {
                    int raw = LevelRenderer.getLightColor(level, c.pos().above());
                    light = LightTexture.pack(Math.max(7, LightTexture.block(raw)), LightTexture.sky(raw));
                }
                Matrix4f table = ReplayFrame.boardMatrix(c.pos(), c.facing(), cam.x, cam.y, cam.z);
                Matrix4f display = ReplayBoardPlacement.displayMatrix(table, c.settings(), ReplayBoardSite.MAP_BASE);
                var site = new ReplayBoardSite(c.pos(), c.facing(), c.distance(), display, table, c.settings().scale(),
                        WORLD_TRAY_HALF_SIZE, WORLD_TRAY_HALF_SIZE, fade,
                        c.distance() < ReplayStagePlan.DETAIL_DISTANCE * ReplayStagePlan.detailScale(c.settings().scale()), light);

                BoardAnimState animState = linearActive || legacyActive || exitAnim != null
                        ? BOARD_STATES.computeIfAbsent(c.pos(), k -> new BoardAnimState()) : null;

                if (linearActive) {
                    boolean historical = session.shared() || !session.roundId().equals(latestRound);
                    var selected = historical ? ReplayHistoryClient.watch(session.roundId()) : view;
                    if (historical) activeHistoricalRounds.add(session.roundId());

                    ReplayBoardModel shown = session.preparing() ? null : resolveModel(selected, !historical);
                    if (shown != null) {
                        animState.syncRound(shown.replay.roundId(), anim);
                        int duration = Math.max(1, shown.replay.durationTicks());
                        double serverTick = session.currentReplayTick(gameTime, partial, duration);
                        double previewTick = ReplayBoardButtons.previewTickFor(c.pos(), Double.NaN);
                        boolean scrubbing = Double.isFinite(previewTick);
                        double replayTick = scrubbing ? Mth.clamp(previewTick, 0.0, duration) : serverTick;
                        boolean finishedHold = !scrubbing && session.isFinishedHold(gameTime, partial, duration);
                        animState.lastReplayTick = replayTick;

                        // Drive concurrent spatial audio + audibility filter (paused or finished hold stays silent)
                        boolean audioPlaying = !session.paused() && !finishedHold && !scrubbing;
                        if (allowBoardAudio && activeAudioBoards.isEmpty()) {
                            activeAudioBoards.add(c.pos().immutable());
                            ReplayAudioPlaybackController.updateBoardSession(
                                    mc, c.pos(), cam, site, shown, selected, audioPlaying,
                                    replayTick, session.speed(), session.followPlayerUuid());
                        }
                        if (!draw) continue;

                        double buildProgress = animState.buildProgress(anim);
                        double reviewProgress = animState.reviewProgress(finishedHold, anim);
                        ReplayStagePlan.Stage stage = ReplayStagePlan.linearStage(
                                replayTick, duration, buildProgress, reviewProgress, 0.0);
                        ReplayStagePlan.Handoff handoff = ReplayStagePlan.linearHandoff(buildProgress, 0.0);

                        FollowTransform followTransform = animState.stepFollow(
                                shown, session.followPlayerUuid(), replayTick, anim);
                        boolean followEngaged = session.followPlayerUuid() != null || followTransform.isActive();

                        // Kill close-up is OFF by default (Defect #8) and suppressed while following a player
                        ReplayFocusPlan.Sample focusSample = !followEngaged && session.killCloseupEnabled()
                                ? shown.focus.sampleLinear(replayTick, session.speed(), true)
                                : ReplayFocusPlan.UNFOCUSED;

                        double progress = Mth.clamp(replayTick / duration, 0.0, 1.0);
                        ReplayTimeline.Frame linearFrame = new ReplayTimeline.Frame(
                                finishedHold ? ReplayTimeline.Phase.REVIEW : ReplayTimeline.Phase.PLAY,
                                progress, replayTick, focusSample.pose().zoom() > 1.08);
                        boolean blackout = !finishedHold && shown.blackoutAt(replayTick);

                        ReplayFrame f = ReplayFrame.forBoardSession(
                                mc, level, camera.rotation(), source, trayType,
                                replayTick / 20.0, anim, shown, linearFrame, false,
                                selected.presentSections(), selected.totalSections(),
                                new Vector3f(camRight), new Vector3f(camUp), new Vector3f(camLook),
                                stage, handoff, focusSample, null, blackout);
                        if (session.followPlayerUuid() != null) {
                            f.highlight = new ReplayFullPlayerRenderer.HighlightSpec(
                                    Set.of(session.followPlayerUuid()), 1.08f, 0.90f, true, null);
                        }
                        boards.add(new BoardDraw(f, site, selected, session, replayTick, finishedHold,
                                session.speed(), !session.roofCutaway(), followTransform, session.followPlayerUuid()));
                    } else {
                        if (!draw) continue;
                        var f = new ReplayFrame(mc, level, camera.rotation(), source, trayType,
                                anim, anim, null, null, true,
                                selected.presentSections(), selected.totalSections(),
                                new Vector3f(camRight), new Vector3f(camUp), new Vector3f(camLook));
                        boards.add(new BoardDraw(f, site, selected, session, 0.0, false,
                                session.speed(), !session.roofCutaway(), FollowTransform.NONE, null));
                    }
                } else if (legacyActive) {
                    boolean historical = legacy.shared() || !legacy.roundId().equals(latestRound);
                    var selected = historical ? ReplayHistoryClient.watch(legacy.roundId()) : view;
                    if (historical) activeHistoricalRounds.add(legacy.roundId());
                    ReplayBoardModel shown = resolveModel(selected, !historical);
                    double elapsed = gameTime - legacy.startTick() + partial;
                    if (shown != null && allowBoardAudio && activeAudioBoards.isEmpty()) {
                        activeAudioBoards.add(c.pos().immutable());
                        ReplayAudioPlaybackController.updateBoard(mc, c.pos(), cam, site, shown, selected, true, elapsed);
                    }
                    if (!draw) continue;
                    ReplayTimeline.Frame frame = shown == null ? null : shown.timeline.atElapsed(elapsed);
                    boolean loading = selected.hasRound() && selected.replay() == null || legacy.preparing();
                    var f = new ReplayFrame(mc, level, camera.rotation(), source, trayType,
                            elapsed / 20.0, anim, shown, frame, loading,
                            selected.presentSections(), selected.totalSections(),
                            new Vector3f(camRight), new Vector3f(camUp), new Vector3f(camLook));
                    boards.add(new BoardDraw(f, site, selected, null,
                            frame == null ? 0.0 : frame.replayTick(), false,
                            shown == null ? 1.0 : shown.rate, ReplayBoardLocalState.fullBody(),
                            FollowTransform.NONE, null));
                } else if (exitAnim != null && draw) {
                    // Explicit End-to-Idle smooth retraction back to ReplayIdleCubes
                    double exitProgress = exitAnim.progress(gameTime, partial);
                    boolean historical = !exitAnim.roundId().equals(latestRound);
                    var selected = historical ? ReplayHistoryClient.watch(exitAnim.roundId()) : view;
                    ReplayBoardModel shown = resolveModel(selected, !historical);
                    if (shown != null) {
                        int duration = Math.max(1, shown.replay.durationTicks());
                        double holdTick = Mth.clamp(animState.lastReplayTick, 0.0, duration);
                        ReplayStagePlan.Stage stage = ReplayStagePlan.linearStage(
                                holdTick, duration, 1.0, 0.0, exitProgress);
                        ReplayStagePlan.Handoff handoff = ReplayStagePlan.linearHandoff(1.0, exitProgress);
                        ReplayTimeline.Frame exitFrame = new ReplayTimeline.Frame(
                                ReplayTimeline.Phase.REVIEW, Mth.clamp(holdTick / duration, 0.0, 1.0),
                                holdTick, false);
                        ReplayFrame f = ReplayFrame.forBoardSession(
                                mc, level, camera.rotation(), source, trayType,
                                anim, anim, shown, exitFrame, false,
                                selected.presentSections(), selected.totalSections(),
                                new Vector3f(camRight), new Vector3f(camUp), new Vector3f(camLook),
                                stage, handoff, ReplayFocusPlan.UNFOCUSED, null, false);
                        boards.add(new BoardDraw(f, site, selected, null, holdTick, true,
                                1.0, ReplayBoardLocalState.fullBody(), FollowTransform.NONE, null));
                    } else {
                        animState.resetToIdle();
                        var f = new ReplayFrame(mc, level, camera.rotation(), source, trayType,
                                anim, anim, null, null, view.hasRound() && view.replay() == null, 0, 0,
                                new Vector3f(camRight), new Vector3f(camUp), new Vector3f(camLook));
                        boards.add(new BoardDraw(f, site, null, null, 0.0, false,
                                1.0, ReplayBoardLocalState.fullBody(), FollowTransform.NONE, null));
                    }
                } else {
                    BOARD_STATES.remove(c.pos());
                    if (!draw) continue;
                    boolean loading = view.hasRound() && view.replay() == null
                            || legacy != null && legacy.preparing();
                    var f = new ReplayFrame(mc, level, camera.rotation(), source, trayType,
                            anim, anim, null, null, loading, 0, 0,
                            new Vector3f(camRight), new Vector3f(camUp), new Vector3f(camLook));
                    boards.add(new BoardDraw(f, site, null, null, 0.0, false,
                            1.0, ReplayBoardLocalState.fullBody(), FollowTransform.NONE, null));
                }
            }
            ReplayHistoryClient.updateBoardPins(activeHistoricalRounds);
            drawBoards(context, mc, boards);
        }
        ReplayAudioPlaybackController.endBoardFrame(mc, activeAudioBoards);
    }

    private static ClientReplayBoard.View latestView(List<Candidate> candidates, UUID latestRound, long gameTime) {
        if (!ReplayPackets.EMPTY.equals(latestRound)) {
            for (Candidate candidate : candidates) {
                if (!candidate.usesLatestSession(latestRound, gameTime)) continue;
                var view = ClientReplayBoard.viewAt(candidate.pos(), true);
                if (view.hasRound()) return view;
            }
            for (Candidate candidate : candidates) {
                var view = ClientReplayBoard.viewAt(candidate.pos(), false);
                if (view.hasRound()) return view;
            }
        }
        return ClientReplayBoard.viewAt(candidates.getFirst().pos(), false);
    }

    private static @Nullable ReplayBoardModel resolveModel(ClientReplayBoard.View selected, boolean latest) {
        if (selected.replay() == null || selected.timeline() == null) return null;
        if (latest) {
            if (model == null || model.replay != selected.replay()) {
                model = new ReplayBoardModel(selected.replay(), selected.timeline());
            }
            return model;
        }
        UUID id = selected.replay().roundId();
        ReplayBoardModel cached = archivedModels.get(id);
        if (cached == null || cached.replay != selected.replay()) {
            cached = new ReplayBoardModel(selected.replay(), selected.timeline());
            archivedModels.put(id, cached);
            while (archivedModels.size() > MAX_BOARDS) archivedModels.pollFirstEntry();
        }
        return cached;
    }

    private static void drawBoards(WorldRenderContext context, Minecraft mc, List<BoardDraw> boards) {
        SETTLED_HISTORY.keySet().removeIf(pos -> boards.stream().noneMatch(
                board -> canReuseReview(board) && board.site().pos().equals(pos)));
        boolean honors = false;
        for (var board : boards) {
            if (ReplayHonorsPainter.visible(board.frame())) {
                honors = true;
            } else if (board.session() != null && board.session().active() && !board.session().preparing()
                    && board.frame().model != null) {
                ReplayBoardButtons.collectDeck(board.frame(), board.site(), board.frame().model,
                        board.session(), board.displayTick(), board.finishedHold());
            } else {
                ReplayIdleCubes.prepareButtons(board.frame(), board.site());
            }
        }
        var world = mc.getMainRenderTarget();
        int[] honorsRect = honors ? computeHonorsBlitRect(context.positionMatrix(), context.projectionMatrix(),
                boards, world.width, world.height) : null;
        boolean honorsHasPixels = honors && (honorsRect == null || (honorsRect[2] > honorsRect[0] && honorsRect[3] > honorsRect[1]));
        boolean isolated = honorsHasPixels && HONORS_LAYER.captureWorldDepth(world, honorsRect);
        if (isolated) lastHonorsDraw = System.nanoTime();
        if (!boards.isEmpty()) TRAY_MESHES.beginPass();
        for (var board : boards) {
            SceneMeshSet mesh = null;
            ReplayDioramaMesh.DynamicWorld dynamicWorld = null;
            var mapView = board.mapView();
            board.frame().meshFailed = mapView != null && mapView.mapFailed();
            if (mapView != null && mapView.asset() != null) {
                SceneAssetCodec.AssetData meshAsset = mapView.asset();
                var m = board.frame().model;
                if (m != null && m.layout != null && !m.replay.blockDeltas().isEmpty()) {
                    dynamicWorld = ReplayDioramaMesh.prepareDynamic(
                            mapView.asset(), m.layout.origin(), m.replay.blockDeltas());
                    if (dynamicWorld != null) {
                        meshAsset = dynamicWorld.staticAsset();
                    }
                }
                ReplayDioramaMesh.updateWorld(meshAsset, mapView.presentSections(), mapView.totalSections());
                if (ReplayDioramaMesh.matches(meshAsset)) mesh = ReplayDioramaMesh.current();
                board.frame().meshFailed |= mesh == null && ReplayDioramaMesh.failed(
                        new ReplayDioramaMesh.Key(meshAsset.fingerprint, mapView.presentSections()));
            }
            drawFrame(context.positionMatrix(), context.projectionMatrix(), board, mesh, dynamicWorld);
        }
        if (!honorsHasPixels) return;
        Runnable paint = () -> {
            for (var board : boards.reversed()) {
                if (!ReplayHonorsPainter.visible(board.frame())) continue;
                ReplayHonorsPainter.paint(board.frame(), board.site());
                flushBoardPasses(board.frame());
            }
        };
        if (isolated) HONORS_LAYER.drawOver(world, honorsRect, paint);
        else paint.run();
    }

    private static int @Nullable [] computeHonorsBlitRect(Matrix4f view, Matrix4f projection,
                                                          List<BoardDraw> boards, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        Matrix4f viewProj = new Matrix4f(projection).mul(view);
        Vector3f anchor = new Vector3f();
        int minX = width, minY = height, maxX = 0, maxY = 0;
        for (var board : boards) {
            if (!ReplayHonorsPainter.visible(board.frame())) continue;
            var site = board.site();
            site.board().transformPosition(0f, ReplayHonorsPainter.anchorHeight(site.hw()), 0f, anchor);
            float r = ReplayHonorsPainter.PANEL_RADIUS * ReplayHonorsPainter.honorsWorldScale(site.scale(), site.hw());
            int[] rect = ReplayHonorsLayer.projectBlitRect(viewProj,
                    anchor.x - r, anchor.y - r, anchor.z - r,
                    anchor.x + r, anchor.y + r, anchor.z + r,
                    width, height, ReplayHonorsLayer.blitMarginPx());
            if (rect == null) return null; // Corner behind near plane -> safe full-screen fallback
            if (rect[2] > rect[0] && rect[3] > rect[1]) {
                if (rect[0] < minX) minX = rect[0];
                if (rect[1] < minY) minY = rect[1];
                if (rect[2] > maxX) maxX = rect[2];
                if (rect[3] > maxY) maxY = rect[3];
            }
        }
        if (maxX <= minX || maxY <= minY) return new int[]{0, 0, 0, 0};
        long area = (long) (maxX - minX) * (maxY - minY);
        if (area >= (long) width * height * 85L / 100L) return null;
        return new int[]{minX, minY, maxX, maxY};
    }

    // ------------------------------------------------------------------ Video & Preview entry points

    static ReplayActorSizing drawVideoFrame(Minecraft mc, ReplayBoardModel current, ReplayTimeline.Frame frame, ReplayStagePlan.Stage stage,
                                            double anim, ReplayVideoCamera.Shot shot, SceneMeshSet mesh, boolean fullBody, ReplayVideoFx fx) {
        return drawVideoFrame(mc, current, frame, stage, anim, shot, mesh, fullBody, fx, null, null);
    }

    static ReplayActorSizing drawVideoFrame(Minecraft mc, ReplayBoardModel current, ReplayTimeline.Frame frame, ReplayStagePlan.Stage stage,
                                            double anim, ReplayVideoCamera.Shot shot, SceneMeshSet mesh, boolean fullBody, ReplayVideoFx fx,
                                            @Nullable UUID focusPlayer) {
        return drawVideoFrame(mc, current, frame, stage, anim, shot, mesh, fullBody, fx, focusPlayer, null);
    }

    /** History preview &amp; extended video frame renderer supporting dynamic doors/walls, full 3D entities and multi-mod skills. */
    static ReplayActorSizing drawPreviewFrame(Minecraft mc, ReplayBoardModel current, ReplayTimeline.Frame frame, ReplayStagePlan.Stage stage,
                                              double anim, ReplayVideoCamera.Shot shot, SceneMeshSet mesh,
                                              @Nullable ReplayDioramaMesh.DynamicWorld dynamicWorld,
                                              boolean fullBody, @Nullable UUID focusPlayer) {
        return drawVideoFrame(mc, current, frame, stage, anim, shot, mesh, fullBody, null, focusPlayer, dynamicWorld);
    }

    static ReplayActorSizing drawVideoFrame(Minecraft mc, ReplayBoardModel current, ReplayTimeline.Frame frame, ReplayStagePlan.Stage stage,
                                            double anim, ReplayVideoCamera.Shot shot, SceneMeshSet mesh, boolean fullBody,
                                            @Nullable ReplayVideoFx fx, @Nullable UUID focusPlayer,
                                            @Nullable ReplayDioramaMesh.DynamicWorld dynamicWorld) {
        var buffers = buffers();
        var eye = shot.eye();
        var board = new Matrix4f().translation(-eye.x, -eye.y, -eye.z);
        float halfSize = trayHalfSize(current);
        var site = new ReplayBoardSite(BlockPos.ZERO, Direction.SOUTH, 0, board, board, 1,
                halfSize, halfSize, 1, true, LightTexture.FULL_BRIGHT);
        Quaternionf rotation = shot.rotation();
        var f = new ReplayFrame(mc, mc.level, rotation, buffers, trayType, anim, anim, current,
                frame, false, 0, 0, rotation.transform(new Vector3f(1, 0, 0)),
                rotation.transform(new Vector3f(0, 1, 0)), shot.look(), stage, focusPlayer);
        Matrix4f diorama = diorama(f, site, FollowTransform.NONE);
        var heads = new ReplayActorSizing(current.scale, 1, diorama, shot.view(), shot.projection(), shot::emphasis, true);
        var source = f.buffers;
        ReplayTrayPainter.tray(f, site);
        source.endBatch(trayType);
        drawMap(shot.view(), shot.projection(), f, site, mesh, dynamicWorld, fullBody, diorama, heads, FollowTransform.NONE, focusPlayer);
        ReplayTrayPainter.lights(f, site);
        ReplayTrayPainter.plaque(f, site);
        if (fx != null) fx.environment(f, site);
        if (current.layout != null) {
            float sink = (float) stage.mapSink() * 0.2f;
            try (var mapScope = f.mapScope(site)) {
                if (dynamicWorld != null && dynamicWorld.hasDynamics() && mesh != null) {
                    Matrix4f dynModel = new Matrix4f(diorama).mul(sourceToDiorama(current));
                    f.model(dynModel, pose -> dynamicWorld.renderDynamicFallbacks(
                            mc, pose.last().pose(), f.modelBuffers(), f.replayTick,
                            fullBody ? null : FAN_CUTAWAY, site.light()));
                }
                ReplayTrayPainter.overlays(f, site, diorama, sink);
                ReplayActorPainter.paint(f, site, diorama, heads);
                ReplayDronePainter.paint(f, site, diorama);
                if (!f.entitiesPainted) ReplayEntityPainter.paintDiorama(f, site, diorama);
                ReplayEventPainter.paint(f, site, diorama);
                if (!f.skillsPainted) ReplaySkillEffectRegistry.paintDiorama(f, site, diorama, focusPlayer == null);
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

    // ------------------------------------------------------------------ World table frame

    private static boolean canReuseReview(BoardDraw board) {
        var frame = board.frame();
        var stage = frame.stage;
        return board.session() != null && board.finishedHold() && frame.model != null
                && frame.model.layout != null && frame.focusPlayer == null && frame.mvpSpotlightActor == null
                && stage != null && stage.actors() == 1.0 && stage.history() == 1.0;
    }

    private static @Nullable ReplayBoardHistoryCache settledHistory(BoardDraw board) {
        if (!canReuseReview(board)) return null;
        var f = board.frame();
        var site = board.site();
        var cache = SETTLED_HISTORY.computeIfAbsent(site.pos(), ignored -> new ReplayBoardHistoryCache());
        var boardMat = site.board();
        var basis = new Matrix4f(boardMat).setTranslation(0, 0, 0);
        var localDiorama = diorama(f, new Matrix4f(basis), board.followTransform());
        var viewDir = new Matrix4f(basis).invert().transformDirection(
                new Vector3f(-boardMat.m30(), -boardMat.m31(), -boardMat.m32()));
        var view = new ReplayBoardHistoryCache.View(f.model, f.replayTick, basis, localDiorama, site.scale(),
                site.fade(), site.hw(), site.hd(), site.detail(), f.focus.zoom(), f.highlight);
        return cache.prepare(view, viewDir, System.nanoTime()) ? cache : null;
    }

    private static void drawFrame(Matrix4f view, Matrix4f projection, BoardDraw board,
                                  @Nullable SceneMeshSet mesh, @Nullable ReplayDioramaMesh.DynamicWorld dynamicWorld) {
        ReplayFrame f = board.frame();
        ReplayBoardSite site = board.site();
        boolean fullBody = board.fullBody();
        FollowTransform followTransform = board.followTransform();
        var source = f.buffers;

        var profiler = f.mc.getProfiler();
        profiler.push("tray");
        try {
            if (!TRAY_MESHES.draw(trayType, site)) {
                ReplayTrayPainter.tray(f, site);
                source.endBatch(trayType);
            }
        } finally {
            profiler.pop();
        }

        if (f.model != null && f.model.layout != null && f.stage != null) {
            float sink = (float) f.stage.mapSink() * 0.2f;
            Matrix4f d = diorama(f, site, followTransform);
            float currentZoom = followTransform.isActive() ? followTransform.zoom() : (float) f.focus.zoom();
            var heads = new ReplayActorSizing(f.model.scale, site.scale() * currentZoom, d, view, projection,
                    f.focusSample::emphasis, false);
            drawMap(view, projection, f, site, mesh, dynamicWorld, fullBody, d, heads, followTransform, board.followPlayerUuid());
            try (var mapScope = f.mapScope(site)) {
                if (mesh == null && f.stage.scan() > 0.01) loadingOutline(f, site, d, f.model);
                if (dynamicWorld != null && dynamicWorld.hasDynamics() && mesh != null && f.stage.scan() > 0.01) {
                    Matrix4f dynModel = new Matrix4f(d).mul(sourceToDiorama(f.model));
                    f.model(dynModel, pose -> dynamicWorld.renderDynamicFallbacks(
                            f.mc, pose.last().pose(), f.modelBuffers(), f.replayTick,
                            fullBody ? null : FAN_CUTAWAY, site.light()));
                }
                ReplayTrayPainter.overlays(f, site, d, sink);
                var history = settledHistory(board);
                ReplayActorPainter.paint(f, site, d, heads, history);
                ReplayDronePainter.paint(f, site, d);
                if (!f.entitiesPainted) ReplayEntityPainter.paintDiorama(f, site, d);
                if (history == null) ReplayEventPainter.paint(f, site, d, board.playbackSpeed());
                else ReplayEventPainter.paintSettledReview(f, site, d, history);
                if (!f.skillsPainted) ReplaySkillEffectRegistry.paintDiorama(f, site, d, true);
            }
            double meshCeiling = mesh == null ? Double.NEGATIVE_INFINITY : mesh.replayCeiling();
            ReplayEventPainter.killFeed(f, site, meshCeiling, board.playbackSpeed(), currentZoom);
        }

        ReplayTrayPainter.lights(f, site);
        ReplayTrayPainter.plaque(f, site);
        ReplayBoardButtons.paintDeck(f, site);
        ReplayTrayPainter.handoff(f, site);
        if (!ReplayHonorsPainter.visible(f)) ReplayIdleCubes.paint(f, site);
        flushBoardPasses(f);
    }

    private static void flushBoardPasses(ReplayFrame f) {
        var source = f.buffers;
        source.endBatch(ReplayDraw.FILL);
        // Depth-writing panels (Side-Edge Deck Console, idle buttons, kill-feed slabs) flush before models/glow/text.
        source.endBatch(ReplayDraw.PANEL);
        f.flushModels();
        source.endBatch(ReplayDraw.GLOW);
        f.flushTexts();
        source.endBatch();
    }

    private static Matrix4f diorama(ReplayFrame frame, ReplayBoardSite site, FollowTransform followTransform) {
        return diorama(frame, new Matrix4f(site.board()), followTransform);
    }

    private static Matrix4f diorama(ReplayFrame frame, Matrix4f base, FollowTransform followTransform) {
        float sink = frame.stage == null ? 0 : (float) frame.stage.mapSink() * 0.2f;
        base.translate(0, ReplayBoardSite.MAP_BASE - sink, 0);
        if (followTransform != null && followTransform.isActive()) {
            return followTransform.apply(base);
        }
        return frame.focus.apply(base);
    }

    private static Matrix4f sourceToDiorama(ReplayBoardModel current) {
        var src = current.source;
        return new Matrix4f()
                .rotateY(current.rotated ? (float) Math.toRadians(90) : 0f)
                .scale(current.scale)
                .translate(-src.sizeX() * 0.5f, 0, -src.sizeZ() * 0.5f);
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
                                @Nullable SceneMeshSet mesh, @Nullable ReplayDioramaMesh.DynamicWorld dynamicWorld,
                                boolean fullBody, Matrix4f diorama, ReplayActorSizing heads,
                                FollowTransform followTransform, @Nullable UUID followPlayerUuid) {
        FAN_CUTAWAY.clear();
        ReplayBoardModel current = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        if (current == null || current.layout == null || stage == null || stage.scan() <= 0.001) return;
        var src = current.source;
        Matrix4f srcToDiorama = sourceToDiorama(current);
        Matrix4f modelMat = new Matrix4f(diorama).mul(srcToDiorama);
        updateFans(f, current, modelMat, fullBody, heads, followPlayerUuid);
        if (mesh == null) return;
        Matrix4f trayTransform = followTransform != null && followTransform.isActive()
                ? followTransform.apply(new Matrix4f())
                : f.focus.apply(new Matrix4f());
        Matrix4f sourceToTray = trayTransform.mul(srcToDiorama);
        var bounds = safeSourceBounds(sourceToTray, s.hw() - ReplayBoardSite.FRAME - 0.04f,
                s.hd() - ReplayBoardSite.FRAME - 0.04f,
                followTransform != null && followTransform.isNonCardinalRotation());
        Matrix4f modelView = new Matrix4f(view).mul(modelMat);
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
        int scanAxis = clip ? (current.rotated ? 2 : 0) : -1;
        try (var scope = SceneShaders.model(modelMat, s.light());
             var boundsScope = SceneShaders.bounds(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
             var clipScope = clip ? (current.rotated ? SceneShaders.clip(0, 0, 1, threshold) : SceneShaders.clip(1, 0, 0, threshold))
                     : SceneShaders.clip(0, 0, 0, 1)) {
            RenderSystem.setShaderColor(r, g, b, 1f);
            ReplayTerrainRenderer.draw(mesh, modelView, projection, FAN_CUTAWAY,
                    bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), scanAxis, threshold);
            if (dynamicWorld != null && dynamicWorld.hasDynamics()) {
                dynamicWorld.renderStaticBatched(f.mc, modelView, projection, FAN_CUTAWAY,
                        bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), scanAxis, threshold, f.replayTick);
            }
        } finally {
            RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
        }
    }

    private static void updateFans(ReplayFrame f, ReplayBoardModel current, Matrix4f model, boolean fullBody,
                                   ReplayActorSizing heads, @Nullable UUID followPlayerUuid) {
        if (fullBody || f.stage == null || f.stage.actors() <= 0.01) return;
        FAN_CUTAWAY.faceViewer(model, f.look, current.source);
        for (ReplayBoardModel.Actor actor : current.actors) {
            if (f.mvpSpotlightActor != null && !f.mvpSpotlightActor.equals(actor.p.id())) continue;
            var pose = f.pose(actor);
            if (!pose.visible()) continue;
            // Skip fallen off-train players below the floor plane so their fan cutaway never slices the hull/coal car.
            if (pose.y() < -0.5) continue;
            var projected = f.project(actor, heads);
            float size = projected.size();
            if (size <= 0) continue;
            double hatClearance = pose.has(LastMatchReplay.DISGUISED) ? 1.2 : 0.82;
            double radius = Math.max(1.25, size * hatClearance / current.scale);
            if (followPlayerUuid != null && followPlayerUuid.equals(actor.p.id())) {
                radius = Math.max(1.85, size * 1.15 / current.scale);
            }
            FAN_CUTAWAY.add(pose, current.layout.origin(), current.source, radius);
        }
        for (var drone : current.drones) {
            FAN_CUTAWAY.add(drone.track().sample(f.replayTick), current.layout.origin(), current.source,
                    Math.max(1, 0.19 / current.scale));
        }
    }

    private static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            trayType = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
            SequencedMap<RenderType, ByteBufferBuilder> fixed = new LinkedHashMap<>();
            fixed.put(trayType, own(1 << 17));
            fixed.put(ReplayDraw.FILL, own(1 << 18));
            fixed.put(ReplayDraw.PANEL, own(1 << 16));
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
        SETTLED_HISTORY.clear();
        TRAY_MESHES.clear();
        HONORS_LAYER.close();
        buffers = null;
        for (ByteBufferBuilder builder : OWNED) builder.close();
        OWNED.clear();
    }
}
