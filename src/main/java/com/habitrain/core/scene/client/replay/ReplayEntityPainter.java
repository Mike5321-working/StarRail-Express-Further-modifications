package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayEntityTrack;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.habitrain.core.scene.client.replay.ReplayPalette.*;

/**
 * Renders non-player replay entities and persistent area zones in both miniature diorama space and
 * 1:1 POV world space: grenades, throwing knives/projectiles, drones, idiot chicks/eggs, tailor
 * dolls, fallen corpses (with drag tether &amp; corruption/hiding states), dropped revolvers/items,
 * placed C4 &amp; traps, and smoke/fire/slime/poison persistent areas.
 */
public final class ReplayEntityPainter {
    private static final int EMERALD = 0xFF4ADE80;
    private static final int SMOKE_GRAY = 0xFF94A3B8;
    private static final int CHICK_YELLOW = 0xFFFFE57F;
    private static final int CHICK_BEAK = 0xFFFF8F2B;
    /** Client render-thread scratch containers to avoid per-frame list/array churn. */
    private static final List<ActiveEntity> SCRATCH_ENTITIES = new ArrayList<>();
    private static final float[] SCRATCH_PROJ = new float[3];
    private static final Vector3f SCRATCH_POS = new Vector3f();

    private ReplayEntityPainter() {}

    public enum VisualKind {
        GRENADE, PROJECTILE, DRONE, IDIOT_CHICK, IDIOT_EGG, TAILOR_DOLL,
        CORPSE, DROPPED_ITEM, PLACED_C4, TRAP,
        ZONE_SMOKE, ZONE_FIRE, ZONE_SLIME, ZONE_POISON, ZONE_FOG
    }

    /** Normalized snapshot of one active entity or area zone at the current replay tick. */
    public record ActiveEntity(
            UUID entityId,
            VisualKind kind,
            String typeOrItemId,
            @Nullable UUID ownerUuid,
            @Nullable UUID targetUuid,
            @Nullable String targetName,
            float x, float y, float z,
            float yawDeg, float pitchDeg,
            float radius,
            double speed,
            boolean dragged,
            boolean corrupted,
            boolean hidden,
            boolean armed,
            boolean complete,
            double ageSeconds
    ) {}

    /** Paints all non-player entities and persistent zones in diorama space. */
    public static void paintDiorama(ReplayFrame f, ReplayBoardSite site, Matrix4f diorama) {
        if (f.model == null || f.stage == null || f.stage.actors() <= 0.01) return;
        float alpha = (float) (site.fade() * f.stage.actors());
        float u = Math.max(0.05f, f.model.scale);
        float elemScale = site.scale() * (float) f.focus.zoom();
        collectActiveInto(f.model, f.replayTick, true, SCRATCH_ENTITIES, SCRATCH_PROJ);
        if (SCRATCH_ENTITIES.isEmpty()) return;
        float yawOffset = f.model.rotated ? 90f : 0f;
        boolean detail = site.detail();
        VertexConsumer glowBase = f.glow();
        VertexConsumer fillBase = f.fill();
        ReplayClippedConsumer clippedGlow = glowBase instanceof ReplayClippedConsumer c ? c : null;
        ReplayClippedConsumer clippedFill = fillBase instanceof ReplayClippedConsumer c ? c : null;
        try {
            for (int i = 0; i < SCRATCH_ENTITIES.size(); i++) {
                ActiveEntity entity = SCRATCH_ENTITIES.get(i);
                VertexConsumer glowTarget = glowBase;
                VertexConsumer fillTarget = fillBase;
                if (clippedGlow != null && !(entity.kind() == VisualKind.CORPSE && entity.dragged())) {
                    float cullRadius = dioramaCullRadius(entity, u, elemScale);
                    var clip = clippedGlow.classifyHorizontal(diorama, entity.x(), entity.y(), entity.z(), cullRadius);
                    if (clip == ReplayClippedConsumer.ClipState.OUTSIDE) continue;
                    if (clip == ReplayClippedConsumer.ClipState.INSIDE) {
                        glowTarget = clippedGlow.directOutput();
                        if (clippedFill != null) fillTarget = clippedFill.directOutput();
                    }
                }
                renderOne(entity, f.model, f.replayTick, true, diorama, glowTarget, fillTarget,
                        f.right, f.up, f.anim, u, elemScale, yawOffset, alpha, true, detail, f, null, null);
            }
        } finally {
            SCRATCH_ENTITIES.clear();
        }
        f.entitiesPainted = true;
    }

    private static float dioramaCullRadius(ActiveEntity e, float u, float elemScale) {
        return switch (e.kind()) {
            case ZONE_SMOKE, ZONE_FOG -> {
                float rad = Math.max(1.8f, e.radius()) * u;
                yield rad * (0.95f + 0.65f * elemScale) + 0.15f * u;
            }
            case ZONE_FIRE -> Math.max(1.4f, e.radius()) * u * 1.05f + 0.35f * u * elemScale;
            case ZONE_SLIME -> Math.max(1.5f, e.radius()) * u * 1.1f + 0.2f * u * elemScale;
            case ZONE_POISON -> Math.max(1.6f, e.radius()) * u * 1.05f + 0.35f * u * elemScale;
            case PROJECTILE -> 0.85f * u + 0.15f * u * elemScale;
            case CORPSE, TAILOR_DOLL -> 1.15f * u;
            default -> Math.max(0.75f, e.radius()) * u + 0.25f * u * elemScale;
        };
    }

    /** Paints all non-player entities and persistent zones in 1:1 POV world space. */
    public static void paintWorld(PoseStack stack, MultiBufferSource buffers, VertexConsumer glow, VertexConsumer fill,
                                  Matrix4f worldToCam, Vector3f right, Vector3f up, ReplayBoardModel model,
                                  double matchTick, double animSec, float alpha, boolean godView) {
        if (model == null || alpha <= 0.01f) return;
        collectActiveInto(model, matchTick, false, SCRATCH_ENTITIES, SCRATCH_PROJ);
        if (SCRATCH_ENTITIES.isEmpty()) return;
        try {
            for (int i = 0; i < SCRATCH_ENTITIES.size(); i++) {
                renderOne(SCRATCH_ENTITIES.get(i), model, matchTick, false, worldToCam, glow, fill,
                        right, up, animSec, 1.0f, 1.0f, 0f, alpha, godView, true, null, stack, buffers);
            }
        } finally {
            SCRATCH_ENTITIES.clear();
        }
    }

    /**
     * Collects active non-player entities at {@code matchTick} from {@code LastMatchReplay.entityTracks()}.
     */
    static List<ActiveEntity> collectActive(ReplayBoardModel model, double matchTick, boolean projectToDiorama) {
        if (model == null || model.entityTracks.length == 0) return List.of();
        List<ActiveEntity> out = new ArrayList<>();
        collectActiveInto(model, matchTick, projectToDiorama, out, new float[3]);
        return out;
    }

    static void collectActiveInto(ReplayBoardModel model, double matchTick, boolean projectToDiorama,
                                  List<ActiveEntity> out, float[] tmp) {
        out.clear();
        if (model == null || model.entityTracks.length == 0) return;
        int upper = model.entityTrackCountUpTo(matchTick);
        if (upper == 0) return;
        ReplayBoardModel.EntityTrackEntry[] indexed = model.entityTracks;
        int[] maxEndPrefix = model.entityTrackMaxEndPrefix;
        for (int i = upper - 1; i >= 0; i--) {
            if (maxEndPrefix[i] <= matchTick) break;
            ReplayBoardModel.EntityTrackEntry entry = indexed[i];
            if (entry.endTick() <= matchTick) continue;
            ReplayEntityTrack track = entry.track();
            List<ReplayEntityTrack.EntitySample> samples = track.samples();
            int firstTick = entry.firstTick();

            double sx, sy, sz;
            float yawDeg, pitchDeg;
            int flags;
            ReplayEntityTrack.EntitySample tail = samples.getLast();
            if (matchTick >= tail.tick()) {
                // O(1) tail fast path: skip binary search & Sampled allocation for persistent tracks.
                if (!tail.has(ReplayEntityTrack.FLAG_ACTIVE)) continue;
                sx = tail.position().blockX();
                sy = tail.position().blockY();
                sz = tail.position().blockZ();
                yawDeg = tail.yawDegrees();
                pitchDeg = tail.pitchDegrees();
                flags = tail.flags();
            } else {
                ReplayEntityTrack.Sampled s = track.sampleAt(matchTick);
                if (s == null || !s.active()) continue;
                sx = s.x();
                sy = s.y();
                sz = s.z();
                yawDeg = s.yawDeg();
                pitchDeg = s.pitchDeg();
                flags = s.flags();
            }

            float x, y, z;
            if (projectToDiorama) {
                model.project(sx, sy, sz, tmp);
                x = tmp[0]; y = tmp[1]; z = tmp[2];
            } else {
                x = (float) sx; y = (float) sy; z = (float) sz;
            }
            VisualKind kind = mapKind(track.kind());
            String targetName = null;
            if (track.targetUuid() != null) {
                ReplayBoardModel.Actor targetActor = model.participant(track.targetUuid());
                if (targetActor != null) targetName = targetActor.p.name();
            }
            double ageSec = Math.max(0.0, (matchTick - firstTick) / 20.0);
            out.add(new ActiveEntity(
                    track.entityId(), kind, track.entityTypeId(),
                    track.ownerUuid(), track.targetUuid(), targetName,
                    x, y, z, yawDeg, pitchDeg, track.radius(), 0.0,
                    (flags & ReplayEntityTrack.FLAG_DRAGGED) != 0,
                    (flags & ReplayEntityTrack.FLAG_CORRUPTED) != 0,
                    (flags & ReplayEntityTrack.FLAG_HIDDEN) != 0,
                    (flags & ReplayEntityTrack.FLAG_ARMED) != 0,
                    (flags & ReplayEntityTrack.FLAG_COMPLETE) != 0,
                    ageSec
            ));
        }
    }

    private static VisualKind mapKind(ReplayEntityTrack.EntityKind kind) {
        return switch (kind) {
            case GRENADE -> VisualKind.GRENADE;
            case PROJECTILE -> VisualKind.PROJECTILE;
            case DRONE -> VisualKind.DRONE;
            case IDIOT_CHICK -> VisualKind.IDIOT_CHICK;
            case IDIOT_EGG -> VisualKind.IDIOT_EGG;
            case TAILOR_DOLL -> VisualKind.TAILOR_DOLL;
            case CORPSE -> VisualKind.CORPSE;
            case DROPPED_ITEM -> VisualKind.DROPPED_ITEM;
            case PLACED_C4 -> VisualKind.PLACED_C4;
            case TRAP -> VisualKind.TRAP;
            case ZONE_SMOKE -> VisualKind.ZONE_SMOKE;
            case ZONE_FIRE -> VisualKind.ZONE_FIRE;
            case ZONE_SLIME -> VisualKind.ZONE_SLIME;
            case ZONE_POISON -> VisualKind.ZONE_POISON;
            case ZONE_FOG -> VisualKind.ZONE_FOG;
        };
    }

    private static void renderOne(ActiveEntity e, ReplayBoardModel model, double matchTick, boolean dioramaSpace,
                                  Matrix4f frame, VertexConsumer glow, VertexConsumer fill,
                                  Vector3f right, Vector3f up, double animSec, float u, float lineScale,
                                  float yawOffset, float alpha, boolean godView, boolean detail,
                                  @Nullable ReplayFrame replayFrame,
                                  @Nullable PoseStack worldStack, @Nullable MultiBufferSource worldBuffers) {
        if (e.hidden() && !godView) return;
        float effAlpha = e.hidden() ? alpha * 0.28f : alpha;
        switch (e.kind()) {
            case GRENADE -> renderGrenade(e, frame, glow, right, up, animSec, u, lineScale, effAlpha, replayFrame, worldStack, worldBuffers);
            case PROJECTILE -> renderProjectile(e, frame, glow, animSec, u, lineScale, yawOffset, effAlpha, replayFrame, worldStack, worldBuffers);
            case DRONE -> {
                if (worldStack != null && worldBuffers != null) {
                    ReplayDronePainter.renderWorldDrone(worldStack, worldBuffers, glow, frame,
                            e.x(), e.y(), e.z(), e.yawDeg(), e.pitchDeg(), animSec,
                            e.speed() > 0.22, e.armed(), e.corrupted(), e.hidden(), effAlpha, LightTexture.FULL_BRIGHT);
                }
            }
            case IDIOT_CHICK -> renderIdiotChick(e, frame, fill, glow, animSec, u, yawOffset, effAlpha);
            case IDIOT_EGG -> renderSpinningItem(e, frame, glow, new ItemStack(Items.EGG), BRASS_LIGHT, animSec, u * 0.42f, effAlpha, replayFrame, worldStack, worldBuffers);
            case TAILOR_DOLL -> renderTailorDoll(e, frame, glow, animSec, u, yawOffset, effAlpha, godView, replayFrame, worldStack, worldBuffers);
            case CORPSE -> renderCorpse(e, model, matchTick, dioramaSpace, frame, glow, animSec, u, lineScale, yawOffset, effAlpha, godView, replayFrame, worldStack, worldBuffers);
            case DROPPED_ITEM -> {
                ItemStack item = ReplayFullPlayerRenderer.resolveItem(e.typeOrItemId());
                if (item.isEmpty()) item = new ItemStack(Items.CROSSBOW);
                renderSpinningItem(e, frame, glow, item, BRASS_LIGHT, animSec, u * 0.55f, effAlpha, replayFrame, worldStack, worldBuffers);
            }
            case PLACED_C4 -> renderPlacedC4(e, frame, fill, glow, right, up, animSec, u, lineScale, yawOffset, effAlpha, detail);
            case TRAP -> renderTrap(e, frame, fill, glow, animSec, u, lineScale, yawOffset, effAlpha);
            case ZONE_SMOKE, ZONE_FOG -> renderSmokeZone(e, frame, fill, glow, right, up, animSec, u, lineScale, effAlpha, detail);
            case ZONE_FIRE -> renderFireZone(e, frame, glow, right, up, animSec, u, lineScale, effAlpha, detail);
            case ZONE_SLIME -> renderSlimeZone(e, frame, fill, glow, right, up, animSec, u, lineScale, effAlpha, detail);
            case ZONE_POISON -> renderPoisonZone(e, frame, fill, glow, right, up, animSec, u, lineScale, effAlpha, detail);
        }
    }

    private static void renderGrenade(ActiveEntity e, Matrix4f frame, VertexConsumer glow, Vector3f right, Vector3f up,
                                      double animSec, float u, float lineScale, float alpha,
                                      @Nullable ReplayFrame rf, @Nullable PoseStack ws, @Nullable MultiBufferSource wb) {
        String id = e.typeOrItemId() == null ? "" : e.typeOrItemId().toLowerCase(Locale.ROOT);
        int accent = id.contains("flash") ? 0xFFFFFFFF : id.contains("smoke") ? SMOKE_GRAY
                : id.contains("slime") ? EMERALD : id.contains("decoy") ? 0xFF38BDF8 : EXPLOSION;
        ItemStack stack = ReplayFullPlayerRenderer.resolveItem(e.typeOrItemId());
        if (stack.isEmpty()) stack = new ItemStack(Items.FIRE_CHARGE);
        renderItemModel(frame, e.x(), e.y() + 0.14f * u, e.z(), (float) (animSec * 6.0), 0.36f * u, stack, rf, ws, wb);
        boolean blink = Math.sin(animSec * 18.0) > 0;
        Vector3f c = frame.transformPosition(e.x(), e.y() + 0.22f * u, e.z(), SCRATCH_POS);
        ReplayDraw.billboard(glow, c, right, up, 0.14f * u * lineScale, 0.14f * u * lineScale,
                alpha(accent, (blink ? 0.92 : 0.35) * alpha));
    }

    private static void renderProjectile(ActiveEntity e, Matrix4f frame, VertexConsumer glow, double animSec,
                                         float u, float lineScale, float yawOffset, float alpha,
                                         @Nullable ReplayFrame rf, @Nullable PoseStack ws, @Nullable MultiBufferSource wb) {
        ItemStack stack = ReplayFullPlayerRenderer.resolveItem(e.typeOrItemId());
        if (stack.isEmpty()) stack = new ItemStack(Items.IRON_SWORD);
        float yaw = yawOffset - e.yawDeg();
        renderItemModel(frame, e.x(), e.y() + 0.1f * u, e.z(), (float) Math.toRadians(yaw) + (float) (animSec * 12.0),
                0.42f * u, stack, rf, ws, wb);
        // Velocity streak behind projectile
        double rad = Math.toRadians(yaw);
        float tail = 0.65f * u;
        ReplayDraw.segment(glow, frame, e.x(), e.y() + 0.1f * u, e.z(),
                e.x() - (float) Math.sin(rad) * tail, e.y() + 0.1f * u, e.z() - (float) Math.cos(rad) * tail,
                Math.max(0.012f, 0.03f * u) * lineScale, alpha(0xFFFFFFFF, 0.85 * alpha), alpha(BRASS_LIGHT, 0));
    }

    private static void renderIdiotChick(ActiveEntity e, Matrix4f frame, VertexConsumer fill, VertexConsumer glow,
                                         double animSec, float u, float yawOffset, float alpha) {
        float hop = (float) Math.abs(Math.sin(animSec * 9.0 + (e.entityId().hashCode() & 15))) * 0.12f * u;
        Matrix4f m = new Matrix4f(frame).translate(e.x(), e.y() + hop, e.z())
                .rotateY((float) Math.toRadians(yawOffset - e.yawDeg())).scale(u);
        int body = alpha(CHICK_YELLOW, alpha);
        int beak = alpha(CHICK_BEAK, alpha);
        // Cute baby chick voxel silhouette
        ReplayDraw.solidBox(fill, m, -0.14f, 0.06f, -0.18f, 0.14f, 0.30f, 0.18f, body);
        ReplayDraw.solidBox(fill, m, -0.11f, 0.28f, 0.08f, 0.11f, 0.48f, 0.28f, body);
        ReplayDraw.solidBox(fill, m, -0.05f, 0.32f, 0.28f, 0.05f, 0.38f, 0.36f, beak);
        // Subtle joyful brass sparkle ring
        ReplayDraw.ring(glow, frame, e.x(), e.y() + 0.02f * u, e.z(), 0.26f * u, 0.04f * u, 16,
                alpha(BRASS_LIGHT, 0.45 * alpha), alpha(BRASS_LIGHT, 0));
    }

    private static void renderTailorDoll(ActiveEntity e, Matrix4f frame, VertexConsumer glow, double animSec,
                                         float u, float yawOffset, float alpha, boolean godView,
                                         @Nullable ReplayFrame rf, @Nullable PoseStack ws, @Nullable MultiBufferSource wb) {
        UUID skinId = e.targetUuid() != null ? e.targetUuid() : e.entityId();
        float dollAlpha = e.complete() ? alpha : alpha * 0.62f;
        var spec = new ReplayFullPlayerRenderer.PoseSpec(
                skinId, e.targetName() == null ? "Doll" : e.targetName(), NEUTRAL,
                true, e.yawDeg(), e.yawDeg(), 0f, 0, 0,
                false, false, false, false, false, 0, 0, 0,
                false, false, false, false, false, false, null, null, null, null, 0f
        );
        float scale = u * ReplayFullPlayerRenderer.WORLD_PLAYER_SCALE * 0.96f;
        int tint = alpha(0xFFFFFFFF, dollAlpha);
        float bob = (float) Math.sin(animSec * 3.0) * 0.03f * u;
        if (rf != null) {
            Matrix4f m = new Matrix4f(frame).translate(e.x(), e.y() + bob, e.z());
            rf.model(m, pose -> ReplayFullPlayerRenderer.renderCharacter(pose, rf.modelBuffers(), spec,
                    scale, yawOffset, animSec, tint, LightTexture.FULL_BRIGHT, 1.08f, godView));
        } else if (ws != null && wb != null) {
            ws.pushPose();
            ws.translate(e.x(), e.y() + bob, e.z());
            ReplayFullPlayerRenderer.renderCharacter(ws, wb, spec, scale, yawOffset, animSec,
                    tint, LightTexture.FULL_BRIGHT, 1.05f, godView);
            ws.popPose();
        }
        // Lavender-brass tailor thread ring
        ReplayDraw.ring(glow, frame, e.x(), e.y() + 0.03f * u, e.z(), 0.52f * u, 0.07f * u, 20,
                alpha(NEUTRAL, 0.72 * alpha), alpha(BRASS_LIGHT, 0.1 * alpha));
    }

    private static void renderCorpse(ActiveEntity e, ReplayBoardModel model, double matchTick, boolean dioramaSpace,
                                     Matrix4f frame, VertexConsumer glow, double animSec,
                                     float u, float lineScale, float yawOffset, float alpha, boolean godView,
                                     @Nullable ReplayFrame rf, @Nullable PoseStack ws, @Nullable MultiBufferSource wb) {
        UUID victimId = e.targetUuid() != null ? e.targetUuid() : e.entityId();
        var spec = new ReplayFullPlayerRenderer.PoseSpec(
                victimId, e.targetName() == null ? "" : e.targetName(), GHOST,
                false, e.yawDeg(), e.yawDeg(), 0f, 0, 0,
                false, false, false, false, false, 0, 0, 0,
                false, false, false, false, false, false, null, null, null, null,
                (float) Math.max(0.5, e.ageSeconds())
        );
        float scale = u * ReplayFullPlayerRenderer.WORLD_PLAYER_SCALE;
        int tint = alpha(e.corrupted() ? 0xFFB8B4A8 : 0xFFE6E6EC, alpha);
        if (rf != null) {
            Matrix4f m = new Matrix4f(frame).translate(e.x(), e.y(), e.z());
            rf.model(m, pose -> ReplayFullPlayerRenderer.renderCharacter(pose, rf.modelBuffers(), spec,
                    scale, yawOffset, animSec, tint, LightTexture.FULL_BRIGHT, 1.0f, godView));
        } else if (ws != null && wb != null) {
            ws.pushPose();
            ws.translate(e.x(), e.y(), e.z());
            ReplayFullPlayerRenderer.renderCharacter(ws, wb, spec, scale, yawOffset, animSec,
                    tint, LightTexture.FULL_BRIGHT, 1.0f, godView);
            ws.popPose();
        }
        if (e.dragged()) {
            ReplayDraw.ring(glow, frame, e.x(), e.y() + 0.03f * u, e.z(), 0.65f * u, 0.08f * u, 20,
                    alpha(LAMP, 0.8 * alpha), alpha(LAMP, 0));
            if (e.ownerUuid() != null && model != null) {
                var dragger = model.participant(e.ownerUuid());
                if (dragger != null) {
                    var dp = dragger.track.sample(matchTick);
                    if (dp.visible()) {
                        float dx, dy, dz;
                        if (dioramaSpace) {
                            model.project(dp.x(), dp.y(), dp.z(), SCRATCH_PROJ);
                            dx = SCRATCH_PROJ[0]; dy = SCRATCH_PROJ[1]; dz = SCRATCH_PROJ[2];
                        } else {
                            dx = (float) dp.x(); dy = (float) dp.y(); dz = (float) dp.z();
                        }
                        ReplayDraw.segment(glow, frame, e.x(), e.y() + 0.25f * u, e.z(),
                                dx, dy + 0.85f * u, dz, Math.max(0.012f, 0.03f * u) * lineScale,
                                alpha(LAMP, 0.85 * alpha), alpha(BRASS_LIGHT, 0.9 * alpha));
                    }
                }
            }
        }
    }

    private static void renderSpinningItem(ActiveEntity e, Matrix4f frame, VertexConsumer glow, ItemStack stack,
                                           int ringColor, double animSec, float itemScale, float alpha,
                                           @Nullable ReplayFrame rf, @Nullable PoseStack ws, @Nullable MultiBufferSource wb) {
        float bob = (float) Math.sin(animSec * 3.5 + (e.entityId().hashCode() & 15)) * itemScale * 0.14f;
        renderItemModel(frame, e.x(), e.y() + itemScale * 0.45f + bob, e.z(), (float) (animSec * 2.1),
                itemScale, stack, rf, ws, wb);
        ReplayDraw.ring(glow, frame, e.x(), e.y() + 0.02f * itemScale, e.z(), itemScale * 0.62f, itemScale * 0.12f, 20,
                alpha(ringColor, 0.72 * alpha), alpha(ringColor, 0));
    }

    private static void renderPlacedC4(ActiveEntity e, Matrix4f frame, VertexConsumer fill, VertexConsumer glow,
                                       Vector3f right, Vector3f up, double animSec, float u, float lineScale,
                                       float yawOffset, float alpha, boolean detail) {
        Matrix4f m = new Matrix4f(frame).translate(e.x(), e.y(), e.z())
                .rotateY((float) Math.toRadians(yawOffset - e.yawDeg())).scale(u);
        ReplayDraw.solidBox(fill, m, -0.22f, 0f, -0.16f, 0.22f, 0.14f, 0.16f, alpha(0xFF3A3F4B, alpha));
        boolean blink = Math.sin(animSec * (e.armed() ? 16.0 : 5.0)) > 0;
        Vector3f led = frame.transformPosition(e.x(), e.y() + 0.18f * u, e.z(), SCRATCH_POS);
        float lr = 0.18f * u * lineScale;
        ReplayDraw.softBillboard(glow, led, right, up, lr, lr,
                alpha(RUBY, (blink ? 0.96 : 0.22) * alpha), 0.45f * u, 1.25f * u, detail ? 12 : 8);
        ReplayDraw.ring(glow, frame, e.x(), e.y() + 0.02f * u, e.z(), 0.55f * u, 0.06f * u, detail ? 20 : 12,
                alpha(RUBY, (blink ? 0.8 : 0.3) * alpha), alpha(RUBY, 0));
    }

    private static void renderTrap(ActiveEntity e, Matrix4f frame, VertexConsumer fill, VertexConsumer glow,
                                   double animSec, float u, float lineScale, float yawOffset, float alpha) {
        String id = e.typeOrItemId() == null ? "" : e.typeOrItemId().toLowerCase(Locale.ROOT);
        if (id.contains("mud")) {
            ReplayDraw.disc(fill, frame, e.x(), e.y() + 0.02f * u, e.z(), Math.max(0.75f, e.radius()) * u, 18,
                    alpha(0xFF5C4033, 0.65 * alpha), alpha(0xFF3E2723, 0.15 * alpha));
        } else {
            Matrix4f m = new Matrix4f(frame).translate(e.x(), e.y(), e.z())
                    .rotateY((float) Math.toRadians(yawOffset - e.yawDeg())).scale(u);
            ReplayDraw.solidBox(fill, m, -0.28f, 0f, -0.28f, 0.28f, 0.06f, 0.28f, alpha(BRASS_DIM, alpha));
            ReplayDraw.ring(glow, frame, e.x(), e.y() + 0.04f * u, e.z(), 0.42f * u, 0.05f * u, 16,
                    alpha(id.contains("silence") ? 0xFF8B5CF6 : RUBY, 0.65 * alpha), alpha(RUBY, 0));
        }
    }

    private static void renderSmokeZone(ActiveEntity e, Matrix4f frame, VertexConsumer fill, VertexConsumer glow,
                                        Vector3f right, Vector3f up, double animSec, float u, float lineScale,
                                        float alpha, boolean detail) {
        float rad = Math.max(1.8f, e.radius()) * u;
        int seed = e.entityId().hashCode();
        int step = detail ? 1 : 2;
        int segs = detail ? 12 : 8;
        int col = alpha(e.kind() == VisualKind.ZONE_FOG ? 0xFF64748B : SMOKE_GRAY, (detail ? 0.34 : 0.42) * alpha);
        for (int i = 0; i < 12; i += step) {
            float a = i * Mth.TWO_PI / 12f + (float) (animSec * 0.22 + (seed & 7));
            float rr = rad * (0.25f + 0.55f * ((i * 37 % 10) / 10f));
            float py = (0.35f + (i % 4) * 0.48f + (float) Math.sin(animSec * 1.4 + i) * 0.14f) * u;
            Vector3f p = frame.transformPosition(e.x() + Mth.cos(a) * rr, e.y() + py, e.z() + Mth.sin(a) * rr, SCRATCH_POS);
            float puff = rad * (0.40f + 0.12f * (i % 3)) * lineScale;
            ReplayDraw.softBillboard(fill, p, right, up, puff, puff * 0.85f, col, 0.85f * u, 2.2f * u, segs);
        }
        ReplayDraw.ring(glow, frame, e.x(), e.y() + 0.04f * u, e.z(), rad, 0.10f * u, detail ? 28 : 16,
                alpha(SMOKE_GRAY, 0.35 * alpha), alpha(SMOKE_GRAY, 0));
    }

    private static void renderFireZone(ActiveEntity e, Matrix4f frame, VertexConsumer glow, Vector3f right, Vector3f up,
                                       double animSec, float u, float lineScale, float alpha, boolean detail) {
        float rad = Math.max(1.4f, e.radius()) * u;
        ReplayDraw.disc(glow, frame, e.x(), e.y() + 0.03f * u, e.z(), rad, detail ? 24 : 14,
                alpha(EXPLOSION, 0.44 * alpha), alpha(LAMP, 0));
        int step = detail ? 1 : 2;
        int segs = detail ? 12 : 8;
        for (int i = 0; i < 10; i += step) {
            float a = i * Mth.TWO_PI / 10f;
            float rr = rad * (0.2f + 0.65f * ((i % 3) / 2f));
            float h = (float) (0.55 + 0.48 * Math.abs(Math.sin(animSec * 9.5 + i * 1.7))) * u;
            Vector3f p = frame.transformPosition(e.x() + Mth.cos(a) * rr, e.y() + h * 0.5f, e.z() + Mth.sin(a) * rr, SCRATCH_POS);
            ReplayDraw.softBillboard(glow, p, right, up, 0.32f * u * lineScale, h * 1.1f * lineScale,
                    alpha(i % 2 == 0 ? EXPLOSION : LAMP, 0.60 * alpha), 0.70f * u, 1.85f * u, segs);
        }
    }

    private static void renderSlimeZone(ActiveEntity e, Matrix4f frame, VertexConsumer fill, VertexConsumer glow,
                                        Vector3f right, Vector3f up, double animSec, float u, float lineScale,
                                        float alpha, boolean detail) {
        float rad = Math.max(1.5f, e.radius()) * u;
        int ringSegs = detail ? 24 : 14;
        ReplayDraw.disc(fill, frame, e.x(), e.y() + 0.03f * u, e.z(), rad, ringSegs,
                alpha(EMERALD, 0.45 * alpha), alpha(POISON, 0.14 * alpha));
        ReplayDraw.ring(glow, frame, e.x(), e.y() + 0.05f * u, e.z(), rad, 0.12f * u, ringSegs,
                alpha(EMERALD, 0.78 * alpha), alpha(EMERALD, 0));
        int count = detail ? 5 : 3;
        int segs = detail ? 12 : 8;
        for (int i = 0; i < count; i++) {
            float a = i * Mth.TWO_PI / count + (float) animSec;
            float rise = (float) ((animSec * 1.1 + i * 0.23) % 1.0);
            Vector3f p = frame.transformPosition(e.x() + Mth.cos(a) * rad * 0.55f, e.y() + (0.08f + rise * 0.55f) * u,
                    e.z() + Mth.sin(a) * rad * 0.55f, SCRATCH_POS);
            float bs = (0.14f * (1f - rise * 0.5f)) * u * lineScale;
            ReplayDraw.softBillboard(glow, p, right, up, bs, bs,
                    alpha(EMERALD, (1f - rise) * 0.76 * alpha), 0.70f * u, 1.85f * u, segs);
        }
    }

    private static void renderPoisonZone(ActiveEntity e, Matrix4f frame, VertexConsumer fill, VertexConsumer glow,
                                         Vector3f right, Vector3f up, double animSec, float u, float lineScale,
                                         float alpha, boolean detail) {
        float rad = Math.max(1.6f, e.radius()) * u;
        ReplayDraw.disc(fill, frame, e.x(), e.y() + 0.03f * u, e.z(), rad, detail ? 24 : 14,
                alpha(POISON, 0.34 * alpha), alpha(POISON, 0.05 * alpha));
        int step = detail ? 1 : 2;
        int segs = detail ? 12 : 8;
        int col = alpha(POISON, 0.48 * alpha);
        for (int i = 0; i < 8; i += step) {
            float a = i * Mth.TWO_PI / 8f + (float) (animSec * 0.9);
            float py = (0.3f + (float) ((animSec * 0.7 + i * 0.2) % 1.2)) * u;
            Vector3f p = frame.transformPosition(e.x() + Mth.cos(a) * rad * 0.52f, e.y() + py,
                    e.z() + Mth.sin(a) * rad * 0.52f, SCRATCH_POS);
            float pr = 0.28f * u * lineScale;
            ReplayDraw.softBillboard(glow, p, right, up, pr, pr, col, 0.80f * u, 2.0f * u, segs);
        }
    }

    private static void renderItemModel(Matrix4f frame, float x, float y, float z, float spinRad, float scale,
                                        ItemStack stack, @Nullable ReplayFrame rf,
                                        @Nullable PoseStack ws, @Nullable MultiBufferSource wb) {
        if (stack.isEmpty()) return;
        if (rf != null) {
            Matrix4f m = new Matrix4f(frame).translate(x, y, z).rotateY(spinRad).scale(scale);
            rf.model(m, pose -> Minecraft.getInstance().getItemRenderer().renderStatic(
                    stack, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    pose, rf.modelBuffers(), rf.level, 0));
        } else if (ws != null && wb != null) {
            ws.pushPose();
            ws.translate(x, y, z);
            ws.mulPose(Axis.YP.rotation(spinRad));
            ws.scale(scale, scale, scale);
            Minecraft.getInstance().getItemRenderer().renderStatic(
                    stack, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    ws, wb, Minecraft.getInstance().level, 0);
            ws.popPose();
        }
    }
}
