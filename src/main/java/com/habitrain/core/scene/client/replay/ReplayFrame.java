package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayFocusPlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One frame of board rendering: shared clock / stage, buffers and deferred passes.
 *
 * <p>Pass order is fixed by the renderer: textured tray (opaque) → diorama VBO → flat fills →
 * deferred models (heads, weapon items) → additive glow → deferred text. Fills and glow live in
 * dedicated buffers so painters can emit them in any order; models and text are deferred because
 * they switch render types per texture.</p>
 */
final class ReplayFrame {
    private static class GlintLayers {
        static final List<RenderType> TYPES = List.of(RenderType.armorEntityGlint(), RenderType.glint(),
                RenderType.glintTranslucent(), RenderType.entityGlint(), RenderType.entityGlintDirect());
    }

    static List<RenderType> glintTypes() { return GlintLayers.TYPES; }
    private static final VertexConsumer SKIP_LAYER = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    };
    final Minecraft mc;
    final ClientLevel level;
    final Quaternionf rotation;
    final Vector3f look;
    final Vector3f right, up;
    final MultiBufferSource.BufferSource buffers;
    final RenderType trayType;
    final double anim;
    final double ambient;
    final @Nullable ReplayBoardModel model;
    final @Nullable ReplayTimeline.Frame frame;
    final @Nullable ReplayStagePlan.Stage stage;
    final ReplayStagePlan.Handoff handoff;
    /** Directed video export frame (its own effects replace some board-only decorations). */
    final boolean video;
    /** Optional single-player perspective target during video export; null for global view. */
    final @Nullable UUID focusPlayer;
    final double replayTick;
    final ReplayFocusPlan.Pose focus;
    final ReplayFocusPlan.Sample focusSample;
    final boolean blackout;
    final boolean loading;
    final int presentSections, totalSections;
    boolean meshFailed;
    /** Optional Promo Tier-1 spotlight/dimming emphasis applied to 3D actors. */
    ReplayFullPlayerRenderer.HighlightSpec highlight = ReplayFullPlayerRenderer.HighlightSpec.NONE;
    /** Active MVP spotlight actor during the MVP honors act (dims non-MVP trails & crowd). */
    @Nullable UUID mvpSpotlightActor;
    /** Whether full 3D humanoid players are rendered (default true). */
    boolean fullBodyCharacters = true;
    /** Guards against double-painting entities/skills when both legacy & new renderers invoke painters. */
    boolean entitiesPainted;
    boolean skillsPainted;
    private ReplayTrackSampler.Pose[] poses;
    private ActorProjection[] actorProjections;
    private final float[] projectionScratch = new float[3];
    private final List<DeferredModel> models = new ArrayList<>();
    private final List<DeferredText> texts = new ArrayList<>();
    private final PoseStack modelPoseScratch = new PoseStack();
    private final Matrix4f textMatrixScratch = new Matrix4f();
    private MapScope mapScope;
    private MultiBufferSource drawingBuffers;

    private record DeferredModel(@Nullable ViewportBuffers scope, Matrix4f transform, Consumer<PoseStack> draw) {}

    private record DeferredText(@Nullable ViewportBuffers scope, Matrix4f transform, Component text,
                                float x, float y, float z, float scale, int color, int align,
                                boolean shadow, float maxWidth) {}

    ReplayFrame(Minecraft mc, ClientLevel level, Quaternionf rotation, MultiBufferSource.BufferSource buffers, RenderType trayType,
                double anim, double ambient, @Nullable ReplayBoardModel model, @Nullable ReplayTimeline.Frame frame,
                boolean loading, int presentSections, int totalSections,
                Vector3f right, Vector3f up, Vector3f look) {
        this(mc, level, rotation, buffers, trayType, anim, ambient, model, frame, loading, presentSections, totalSections,
                right, up, look, null, null);
    }

    /** Video export: the director supplies its own act choreography and keeps the map at its natural scale. */
    ReplayFrame(Minecraft mc, ClientLevel level, Quaternionf rotation, MultiBufferSource.BufferSource buffers, RenderType trayType,
                double anim, double ambient, @Nullable ReplayBoardModel model, @Nullable ReplayTimeline.Frame frame,
                boolean loading, int presentSections, int totalSections,
                Vector3f right, Vector3f up, Vector3f look,
                @Nullable ReplayStagePlan.Stage stageOverride) {
        this(mc, level, rotation, buffers, trayType, anim, ambient, model, frame, loading, presentSections, totalSections,
                right, up, look, stageOverride, null);
    }

    ReplayFrame(Minecraft mc, ClientLevel level, Quaternionf rotation, MultiBufferSource.BufferSource buffers, RenderType trayType,
                double anim, double ambient, @Nullable ReplayBoardModel model, @Nullable ReplayTimeline.Frame frame,
                boolean loading, int presentSections, int totalSections,
                Vector3f right, Vector3f up, Vector3f look,
                @Nullable ReplayStagePlan.Stage stageOverride, @Nullable UUID focusPlayer) {
        this(mc, level, rotation, buffers, trayType, anim, ambient, model, frame, loading, presentSections, totalSections,
                right, up, look, stageOverride != null,
                stageOverride != null ? stageOverride : frame == null ? null : ReplayStagePlan.boardStage(frame),
                stageOverride != null ? ReplayStagePlan.REPLAY : frame == null ? ReplayStagePlan.IDLE : ReplayStagePlan.boardHandoff(frame),
                stageOverride != null || model == null ? ReplayFocusPlan.UNFOCUSED : model.focus.sample(frame),
                stageOverride != null ? focusPlayer : null,
                model != null && frame != null && frame.phase() == ReplayTimeline.Phase.PLAY && model.blackoutAt(frame.replayTick()));
    }

    /**
     * Board linear-session factory: keeps {@code video = false} while letting the table session supply
     * explicit linear stage, handoff, optional kill-closeup focus sample, follow player and blackout state.
     */
    static ReplayFrame forBoardSession(Minecraft mc, ClientLevel level, Quaternionf rotation,
                                       MultiBufferSource.BufferSource buffers, RenderType trayType,
                                       double anim, double ambient, @Nullable ReplayBoardModel model,
                                       @Nullable ReplayTimeline.Frame frame, boolean loading,
                                       int presentSections, int totalSections,
                                       Vector3f right, Vector3f up, Vector3f look,
                                       @Nullable ReplayStagePlan.Stage stage, ReplayStagePlan.Handoff handoff,
                                       ReplayFocusPlan.Sample focusSample, @Nullable UUID focusPlayer,
                                       boolean blackout) {
        return new ReplayFrame(mc, level, rotation, buffers, trayType, anim, ambient, model, frame,
                loading, presentSections, totalSections, right, up, look, false,
                stage, handoff == null ? ReplayStagePlan.IDLE : handoff,
                focusSample == null ? ReplayFocusPlan.UNFOCUSED : focusSample,
                focusPlayer, blackout);
    }

    private ReplayFrame(Minecraft mc, ClientLevel level, Quaternionf rotation, MultiBufferSource.BufferSource buffers, RenderType trayType,
                        double anim, double ambient, @Nullable ReplayBoardModel model, @Nullable ReplayTimeline.Frame frame,
                        boolean loading, int presentSections, int totalSections,
                        Vector3f right, Vector3f up, Vector3f look, boolean video,
                        @Nullable ReplayStagePlan.Stage stage, ReplayStagePlan.Handoff handoff,
                        ReplayFocusPlan.Sample focusSample, @Nullable UUID focusPlayer, boolean blackout) {
        this.mc = mc;
        this.level = level;
        this.rotation = rotation;
        this.right = right;
        this.up = up;
        this.look = look;
        this.buffers = buffers;
        this.trayType = trayType;
        this.anim = anim;
        this.ambient = ambient;
        this.model = model;
        this.frame = frame;
        this.stage = stage;
        this.video = video;
        this.focusPlayer = focusPlayer;
        this.handoff = handoff;
        this.replayTick = frame == null ? 0 : frame.replayTick();
        this.focusSample = focusSample;
        this.focus = focusSample.pose();
        this.drawingBuffers = buffers;
        this.blackout = blackout;
        this.loading = loading;
        this.presentSections = presentSections;
        this.totalSections = totalSections;
    }

    VertexConsumer glow() { return mapScope == null ? buffers.getBuffer(ReplayDraw.GLOW) : mapScope.glow; }
    VertexConsumer fill() { return mapScope == null ? buffers.getBuffer(ReplayDraw.FILL) : mapScope.fill; }
    VertexConsumer tray() { return buffers.getBuffer(trayType); }
    MultiBufferSource modelBuffers() { return drawingBuffers; }

    ReplayTrackSampler.Pose pose(ReplayBoardModel.Actor actor) {
        if (poses == null) poses = new ReplayTrackSampler.Pose[model.actors.length];
        var cached = poses[actor.index];
        if (cached != null) return cached;
        return poses[actor.index] = actor.track.sample(replayTick);
    }

    record ActorProjection(float x, float y, float z, float size) {}

    ActorProjection project(ReplayBoardModel.Actor actor, ReplayActorSizing sizing) {
        if (actorProjections == null) actorProjections = new ActorProjection[model.actors.length];
        var cached = actorProjections[actor.index];
        if (cached != null) return cached;
        var pose = pose(actor);
        model.project(pose.x(), pose.y(), pose.z(), projectionScratch);
        float x = projectionScratch[0], y = projectionScratch[1], z = projectionScratch[2];
        return actorProjections[actor.index] = new ActorProjection(x, y, z, sizing.size(actor.p.id(), x, y, z));
    }

    /** Clip all map decorations; fixed tray UI and honors are collected outside this scope. */
    MapScope mapScope(ReplayBoardSite site) { return new MapScope(site); }

    MapScope activeMapScope() {
        return java.util.Objects.requireNonNull(mapScope);
    }

    private record Viewport(Matrix4f inverseBoard, float hw, float hd) {
        ReplayClippedConsumer wrap(VertexConsumer source) {
            return new ReplayClippedConsumer(source, inverseBoard, hw, hd);
        }
    }

    final class MapScope implements AutoCloseable {
        final ReplayClippedConsumer fill, glow;
        final VertexConsumer glowBuffer;
        final ViewportBuffers deferred;
        final float originX, originY, originZ;
        private final Viewport viewport;

        private MapScope(ReplayBoardSite site) {
            if (mapScope != null) throw new IllegalStateException("Nested replay map scope");
            originX = site.board().m30();
            originY = site.board().m31();
            originZ = site.board().m32();
            viewport = new Viewport(new Matrix4f(site.board()).invert(), site.hw() - ReplayBoardSite.FRAME - 0.04f,
                    site.hd() - ReplayBoardSite.FRAME - 0.04f);
            fill = viewport.wrap(buffers.getBuffer(ReplayDraw.FILL));
            glowBuffer = buffers.getBuffer(ReplayDraw.GLOW);
            glow = viewport.wrap(glowBuffer);
            deferred = new ViewportBuffers(viewport, buffers);
            mapScope = this;
        }

        ReplayClippedConsumer clipGlow(VertexConsumer target) {
            return viewport.wrap(target);
        }

        @Override public void close() {
            try { fill.close(); glow.close(); }
            finally { mapScope = null; }
        }
    }

    static final class ViewportBuffers implements MultiBufferSource, AutoCloseable {
        private final Function<RenderType, ReplayClippedConsumer> factory;
        private final Map<RenderType, ReplayClippedConsumer> layers = new HashMap<>();
        private final List<ReplayClippedConsumer> usedLayers = new ArrayList<>();
        private @Nullable RenderType activeType;
        private @Nullable ReplayClippedConsumer activeConsumer;
        private int drawLayerCount;

        private ViewportBuffers(Viewport viewport, MultiBufferSource.BufferSource buffers) {
            this(layer -> new ReplayClippedConsumer(() -> buffers.getBuffer(layer),
                    viewport.inverseBoard(), viewport.hw(), viewport.hd(), primitiveSize(layer)));
        }

        ViewportBuffers(Function<RenderType, ReplayClippedConsumer> factory) {
            this.factory = factory;
        }

        private static int primitiveSize(RenderType type) {
            return switch (type.mode()) {
                case QUADS -> 4;
                case TRIANGLES -> 3;
                case LINES, DEBUG_LINES -> 2;
                default -> 0;
            };
        }

        void beginDraw() {
            drawLayerCount = 0;
        }

        void finishDraw() {
            // Keep single-layer draws open across consecutive same-RenderType models/texts so they
            // share one clipped batch; flush multi-layer draws immediately to preserve layer order.
            if (usedLayers.size() > 1) close();
        }

        @Override public VertexConsumer getBuffer(RenderType type) {
            if (type.equals(activeType) && activeConsumer != null) {
                if (drawLayerCount == 0) drawLayerCount = 1;
                return activeConsumer;
            }
            if (primitiveSize(type) == 0) return SKIP_LAYER;
            if (drawLayerCount == 0 && !usedLayers.isEmpty()) {
                // Previous single-layer draw ended on a different RenderType; finish its trailing
                // primitive before the shared BufferSource switches layers.
                close();
            }
            var clipped = layers.computeIfAbsent(type, factory);
            if (!usedLayers.contains(clipped)) usedLayers.add(clipped);
            activeType = type;
            activeConsumer = clipped;
            drawLayerCount++;
            return clipped;
        }

        @Override public void close() {
            // Finish each draw in its original layer order before the next draw reuses the scratch buffers.
            for (var layer : usedLayers) layer.close();
            usedLayers.clear();
            activeType = null;
            activeConsumer = null;
            drawLayerCount = 0;
        }
    }

    /** Defers a model draw; the consumer receives a pose stack positioned at {@code m}. */
    void model(Matrix4f m, Consumer<PoseStack> draw) {
        ViewportBuffers deferred = mapScope == null ? null : mapScope.deferred;
        models.add(new DeferredModel(deferred, new Matrix4f(m), draw));
    }

    /**
     * Defers text in the local XY plane of {@code m} (visible from +Z). {@code align}: -1 left, 0 centre, 1 right.
     * Text wider than {@code maxWidth} (blocks, 0 = unlimited) is scaled down to fit.
     */
    void text(Matrix4f m, Component text, float x, float y, float z, float scale, int color, int align,
              boolean shadow, float maxWidth) {
        if (((color >>> 24) & 0xFF) < 8) return;
        ViewportBuffers deferred = mapScope == null ? null : mapScope.deferred;
        texts.add(new DeferredText(deferred, new Matrix4f(m), text, x, y, z, scale, color, align, shadow, maxWidth));
    }

    /** Camera-facing text at a camera-relative point. */
    void billboardText(Vector3f at, Component text, float scale, int color) {
        Matrix4f m = new Matrix4f().translate(at).rotate(rotation);
        text(m, text, 0, 0, 0, scale, color, 0, false, 0);
    }

    void flushModels() {
        ViewportBuffers currentScope = null;
        try {
            for (DeferredModel item : models) {
                if (item.scope() != currentScope) {
                    if (currentScope != null) currentScope.close();
                    currentScope = item.scope();
                }
                modelPoseScratch.pushPose();
                try {
                    modelPoseScratch.mulPose(item.transform());
                    if (currentScope != null) {
                        currentScope.beginDraw();
                        drawingBuffers = currentScope;
                    } else {
                        drawingBuffers = buffers;
                    }
                    item.draw().accept(modelPoseScratch);
                    if (currentScope != null) currentScope.finishDraw();
                } finally {
                    modelPoseScratch.popPose();
                }
            }
        } finally {
            if (currentScope != null) currentScope.close();
            drawingBuffers = buffers;
            models.clear();
        }
        buffers.endLastBatch();
        for (var type : glintTypes()) buffers.endBatch(type);
    }

    void flushTexts() {
        Font font = mc.font;
        ViewportBuffers currentScope = null;
        try {
            for (DeferredText item : texts) {
                if (item.scope() != currentScope) {
                    if (currentScope != null) currentScope.close();
                    currentScope = item.scope();
                }
                float width = font.width(item.text());
                float s = item.scale();
                if (item.maxWidth() > 0 && width * s > item.maxWidth()) {
                    s = item.maxWidth() / Math.max(1f, width);
                }
                textMatrixScratch.set(item.transform()).translate(item.x(), item.y(), item.z()).scale(s, -s, s);
                float dx = item.align() < 0 ? 0 : item.align() == 0 ? -width / 2f : -width;
                if (currentScope != null) currentScope.beginDraw();
                font.drawInBatch(item.text(), dx, -font.lineHeight / 2f, item.color(), item.shadow(),
                        textMatrixScratch, currentScope == null ? buffers : currentScope,
                        Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
                if (currentScope != null) currentScope.finishDraw();
            }
        } finally {
            if (currentScope != null) currentScope.close();
            texts.clear();
        }
    }

    /** Camera-relative transform of the board frame (origin one block above the marker, +Z = front). */
    static Matrix4f boardMatrix(BlockPos pos, Direction facing, double camX, double camY, double camZ) {
        return new Matrix4f().translate((float) (pos.getX() + 0.5 - camX), (float) (pos.getY() + 1.0 - camY),
                (float) (pos.getZ() + 0.5 - camZ)).rotateY((float) Math.toRadians(-facing.toYRot()));
    }
}
