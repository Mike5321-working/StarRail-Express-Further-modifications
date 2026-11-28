package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

/**
 * Spatial event nodes: kill pulses with floating 3D weapons and slash lines, ballistic beams with
 * muzzle stars and impact sparks, explosion / poison / shield / door / skill halos, plus the
 * anti-occlusion side-wing holographic kill feed positioned strictly above the diorama's dynamic
 * roof envelope. In the review act every node is shown as a quiet static marker for an overview.
 */
final class ReplayEventPainter {
    private static final int MAX_FEED = 5;
    private static final double FEED_DURATION_SEC = 4.6;
    private static final double FEED_IN_SEC = 0.28;
    private static final double FEED_OUT_START_SEC = 3.95;

    private ReplayEventPainter() {}

    static void paint(ReplayFrame f, ReplayBoardSite s, Matrix4f d) {
        paint(f, s, d, f.model == null ? 1.0 : f.model.rate);
    }

    static void paint(ReplayFrame f, ReplayBoardSite s, Matrix4f d, double playbackRate) {
        ReplayBoardModel model = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        if (model == null || stage == null || f.frame == null || stage.actors() <= 0.01) return;
        double t = f.replayTick;
        double a = s.fade() * stage.actors();
        boolean play = f.frame.phase() == ReplayTimeline.Phase.PLAY;
        double emphasis = f.frame.slowMotion() ? 1.6 : 1.0;
        double effectiveRate = Math.max(0.5, playbackRate);
        float elemScale = s.scale() * (float) f.focus.zoom();
        VertexConsumer glow = f.glow();
        ReplayClippedConsumer clippedGlow = glow instanceof ReplayClippedConsumer c ? c : null;
        int limit = model.eventCountUpTo(t);
        for (int i = 0; i < limit; i++) {
            ReplayBoardModel.EventMark e = model.events[i];
            double age = t - e.node.tick();
            if (age < 0) continue;
            double window = window(e.node.kind(), effectiveRate);
            boolean isLive = play && age <= window;
            if (!isLive && !(stage.history() > 0.01 || age > window)) continue;
            VertexConsumer targetGlow = glow;
            if (clippedGlow != null) {
                var clip = classifyEvent(clippedGlow, d, e, isLive, emphasis, elemScale);
                if (clip == ReplayClippedConsumer.ClipState.OUTSIDE) continue;
                if (clip == ReplayClippedConsumer.ClipState.INSIDE) targetGlow = clippedGlow.directOutput();
            }
            if (isLive) {
                // Exported videos render dedicated video-clock 3D effects in ReplayVideoFx;
                // skipping board live effects here avoids double beams/rings and giant frozen weapons.
                if (!f.video) live(f, s, d, targetGlow, e, age, window, a, emphasis, elemScale);
            } else {
                double alpha = a * (play ? 0.35 : stage.history());
                marker(f, d, targetGlow, e, alpha, elemScale);
                if (e.node.kind() == Kind.KILL && alpha > 0.5 && !f.video) markerWeapon(f, d, e, alpha, elemScale);
            }
        }
    }

    private static ReplayClippedConsumer.ClipState classifyEvent(ReplayClippedConsumer clipped, Matrix4f d,
                                                                 ReplayBoardModel.EventMark e, boolean live,
                                                                 double emphasis, float elemScale) {
        Kind kind = e.node.kind();
        boolean twoPoint = kind == Kind.GUNSHOT || (live && (kind == Kind.GRENADE_THROWN || (kind == Kind.KILL && e.hasFrom)));
        float cx, cy, cz, radius;
        if (twoPoint) {
            cx = (e.fx + e.tx) * 0.5f;
            cy = (e.fy + e.ty) * 0.5f;
            cz = (e.fz + e.tz) * 0.5f;
            float dx = e.tx - e.fx, dz = e.tz - e.fz;
            radius = (float) Math.hypot(dx, dz) * 0.5f + (live ? (float) (0.45 * emphasis) + 0.14f * elemScale : 0.06f * elemScale);
        } else {
            cx = e.tx;
            cy = e.ty;
            cz = e.tz;
            radius = live ? (float) (0.52 * emphasis) + 0.18f * elemScale : 0.12f + 0.05f * elemScale;
        }
        return clipped.classifyHorizontal(d, cx, cy, cz, radius);
    }

    static void paintSettledReview(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayBoardHistoryCache history) {
        var model = f.model;
        var scope = f.activeMapScope();
        double alpha = s.fade();
        float elemScale = s.scale() * (float) f.focus.zoom();
        int slot = model.actors.length;
        for (var run : history.markers()) {
            if (!history.emit(scope, slot)) {
                history.record(scope, slot, glow -> {
                    for (int i = run.from(); i < run.to(); i++) {
                        var event = model.events[i];
                        if (event.node.tick() <= f.replayTick) marker(f, d, glow, event, alpha, elemScale);
                    }
                });
            }
            if (run.weapon() >= 0) markerWeapon(f, d, model.events[run.weapon()], alpha, elemScale);
            slot++;
        }
    }

    static double window(Kind kind, double rate) {
        double seconds = switch (kind) {
            case KILL -> 2.4;
            case GUNSHOT -> 0.7;
            case GRENADE_EXPLOSION, BOMB_DETONATE -> 1.6;
            case POISON, SHIELD_BROKEN, PLAYER_REVIVAL -> 1.3;
            default -> 1.0;
        };
        return ReplayStagePlan.effectWindow(seconds, rate);
    }

    private static void live(ReplayFrame f, ReplayBoardSite s, Matrix4f d, VertexConsumer glow,
                             ReplayBoardModel.EventMark e, double age, double window, double a, double emphasis, float elemScale) {
        double k = age / window;
        double fadeOut = 1 - ReplayStagePlan.easeOutCubic(k);
        float x = e.tx, y = e.ty + 0.01f, z = e.tz;
        switch (e.node.kind()) {
            case KILL -> {
                for (int ring = 0; ring < 2; ring++) {
                    double rk = Math.min(1, k * 1.6 - ring * 0.25);
                    if (rk <= 0) continue;
                    float r = (float) ((0.05 + 0.32 * ReplayStagePlan.easeOutCubic(rk)) * emphasis);
                    ReplayDraw.ring(glow, d, x, y, z, r, 0.022f, 28, ReplayPalette.alpha(ReplayPalette.RUBY, (1 - rk) * 0.9 * a),
                            ReplayPalette.alpha(ReplayPalette.RUBY, 0));
                }
                ReplayDraw.column(glow, d, x, y, z, (float) (0.5 * emphasis), 0.05f,
                        ReplayPalette.alpha(ReplayPalette.RUBY, 0.55 * fadeOut * a), ReplayPalette.alpha(ReplayPalette.RUBY, 0));
                if (e.hasFrom && k < 0.45) {
                    double sk = 1 - k / 0.45;
                    ReplayDraw.segment(glow, d, e.fx, e.fy + 0.08f, e.fz, x, y + 0.08f, z, 0.03f * elemScale,
                            ReplayPalette.alpha(0xFFFFFFFF, 0.2 * sk * a), ReplayPalette.alpha(ReplayPalette.RUBY, 0.95 * sk * a));
                }
                weapon(f, d, e, x, y + 0.26f, z, (float) (0.15 * emphasis), fadeOut * a, elemScale);
            }
            case GUNSHOT -> {
                boolean hit = e.has(HIT);
                int core = e.has(BACKFIRE) ? ReplayPalette.EXPLOSION : 0xFFFFFFFF;
                int halo = e.has(BACKFIRE) ? ReplayPalette.EXPLOSION : ReplayPalette.BRASS_LIGHT;
                double beam = k < 0.65 ? 1 - k / 0.65 : 0;
                if (beam > 0) {
                    ReplayDraw.segment(glow, d, e.fx, e.fy, e.fz, e.tx, e.ty, e.tz, 0.045f * elemScale,
                            ReplayPalette.alpha(halo, 0.35 * beam * a), ReplayPalette.alpha(halo, 0.15 * beam * a));
                    ReplayDraw.segment(glow, d, e.fx, e.fy, e.fz, e.tx, e.ty, e.tz, 0.014f * elemScale,
                            ReplayPalette.alpha(core, 0.95 * beam * a), ReplayPalette.alpha(core, 0.6 * beam * a));
                }
                Vector3f muzzle = d.transformPosition(e.fx, e.fy, e.fz, new Vector3f());
                ReplayDraw.star(glow, muzzle, f.right, f.up, (float) (0.09 * emphasis * fadeOut) * elemScale, 8, (float) (age * 0.3),
                        ReplayPalette.alpha(core, fadeOut * a), ReplayPalette.alpha(halo, 0));
                Vector3f impact = d.transformPosition(e.tx, e.ty, e.tz, new Vector3f());
                if (hit) {
                    ReplayDraw.ring(glow, d, e.tx, e.ty, e.tz, (float) (0.04 + 0.12 * k), 0.016f, 20,
                            ReplayPalette.alpha(ReplayPalette.RUBY, fadeOut * a), ReplayPalette.alpha(ReplayPalette.RUBY, 0));
                } else if (e.has(BACKFIRE)) {
                    ReplayDraw.star(glow, impact, f.right, f.up, (float) (0.12 * emphasis * fadeOut) * elemScale, 8, 0.4f,
                            ReplayPalette.alpha(ReplayPalette.EXPLOSION, fadeOut * a), ReplayPalette.alpha(ReplayPalette.LAMP, 0));
                } else if (s.detail()) {
                    ReplayDraw.star(glow, impact, f.right, f.up, (float) (0.05 + 0.05 * k) * elemScale, 10, 0.4f,
                            ReplayPalette.alpha(ReplayPalette.LAMP, fadeOut * a), ReplayPalette.alpha(ReplayPalette.LAMP, 0));
                }
                if (e.has(GUN_DROP)) warning(f, glow, d.transformPosition(e.fx, e.fy + 0.2f, e.fz, new Vector3f()), fadeOut * a, elemScale);
            }
            case GRENADE_EXPLOSION, BOMB_DETONATE -> {
                for (int ring = 0; ring < 3; ring++) {
                    double rk = Math.min(1, k * 2 - ring * 0.2);
                    if (rk <= 0) continue;
                    ReplayDraw.ring(glow, d, x, y, z, (float) ((0.06 + 0.42 * ReplayStagePlan.easeOutCubic(rk)) * emphasis),
                            0.03f, 32, ReplayPalette.alpha(ReplayPalette.EXPLOSION, (1 - rk) * 0.9 * a),
                            ReplayPalette.alpha(ReplayPalette.LAMP, 0));
                }
                Vector3f c = d.transformPosition(x, y + 0.08f, z, new Vector3f());
                float flash = (float) (0.22 * emphasis * (1 - k)) * elemScale;
                ReplayDraw.billboard(glow, c, f.right, f.up, flash, flash, ReplayPalette.alpha(ReplayPalette.LAMP, 0.5 * fadeOut * a));
                if (e.node.kind() == Kind.BOMB_DETONATE) weapon(f, d, e, x, y + 0.24f, z, 0.12f, fadeOut * a, elemScale);
            }
            case GRENADE_THROWN -> {
                if (e.hasFrom && e.hasTo) arc(glow, d, e, fadeOut * a, ReplayPalette.EXPLOSION, elemScale);
                halo(glow, d, x, y, z, k, a, ReplayPalette.EXPLOSION, 0.12f);
            }
            case POISON -> {
                for (int ring = 0; ring < 3; ring++) {
                    double rk = (k * 1.4 - ring * 0.22);
                    if (rk <= 0 || rk >= 1) continue;
                    ReplayDraw.ring(glow, d, x, y, z, (float) (0.04 + 0.2 * rk), 0.016f, 24,
                            ReplayPalette.alpha(ReplayPalette.POISON, (1 - rk) * 0.85 * a), ReplayPalette.alpha(ReplayPalette.POISON, 0));
                }
                Vector3f c = d.transformPosition(x, y + 0.16f + (float) k * 0.05f, z, new Vector3f());
                ReplayDraw.billboard(glow, c, f.right, f.up, 0.025f * elemScale, 0.04f * elemScale, ReplayPalette.alpha(ReplayPalette.POISON, fadeOut * a));
            }
            case SHIELD_BROKEN -> {
                for (int i = 0; i < 6; i++) {
                    double ang = Math.PI * 2 * i / 6;
                    float r0 = (float) (0.06 + k * 0.18), r1 = r0 + 0.06f;
                    float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
                    float c2 = (float) Math.cos(ang + 0.7), s2 = (float) Math.sin(ang + 0.7);
                    ReplayDraw.segment(glow, d, x + c * r0, y + 0.06f, z + sn * r0, x + c2 * r1, y + 0.06f + (float) k * 0.05f,
                            z + s2 * r1, 0.016f * elemScale, ReplayPalette.alpha(ReplayPalette.SHIELD, fadeOut * a), ReplayPalette.alpha(ReplayPalette.SHIELD, 0.3 * fadeOut * a));
                }
            }
            case BOMB_PLANT -> {
                double blink = Math.sin(f.anim * 12) > 0 ? 1 : 0.25;
                Vector3f c = d.transformPosition(x, y + 0.03f, z, new Vector3f());
                ReplayDraw.billboard(glow, c, f.right, f.up, 0.03f * elemScale, 0.03f * elemScale, ReplayPalette.alpha(ReplayPalette.RUBY, blink * a));
                halo(glow, d, x, y, z, k, a, ReplayPalette.RUBY, 0.1f);
            }
            case BOMB_DEFUSE -> halo(glow, d, x, y, z, k, a, ReplayPalette.SHIELD, 0.16f);
            case PLAYER_REVIVAL -> {
                ReplayDraw.column(glow, d, x, y, z, 0.6f, 0.07f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.7 * fadeOut * a),
                        ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0));
                halo(glow, d, x, y, z, k, a, ReplayPalette.CHAMPAGNE, 0.2f);
            }
            case DISGUISE -> halo(glow, d, x, y, z, k, a, ReplayPalette.DISGUISE, 0.14f);
            case CHANGE_ROLE -> halo(glow, d, x, y, z, k, a, actorColor(f, e.node.actor()), 0.16f);
            case LOCKPICK_ATTEMPT, DOOR_PRY, DOOR_SEAL, DOOR_LOCK, DOOR_UNLOCK, DOOR_OPEN, DOOR_CLOSE -> {
                float r = 0.05f + (float) k * 0.04f;
                int c = ReplayPalette.alpha(e.node.kind() == Kind.DOOR_SEAL || e.node.kind() == Kind.DOOR_LOCK
                        ? ReplayPalette.SHIELD : ReplayPalette.LAMP, fadeOut * a);
                ReplayDraw.wireBox(glow, d, x - r, y, z - r, x + r, y + 0.12f, z + r, 0.01f * elemScale, c);
            }
            default -> {
                int color = actorColor(f, e.node.actor());
                halo(glow, d, x, y, z, k, a, color, 0.1f);
                if (s.detail()) {
                    Vector3f c = d.transformPosition(x, y + 0.12f, z, new Vector3f());
                    ReplayDraw.star(glow, c, f.right, f.up, (float) (0.05 * fadeOut + 0.02) * elemScale, 6, (float) f.anim,
                            ReplayPalette.alpha(color, fadeOut * a), ReplayPalette.alpha(color, 0));
                }
            }
        }
    }

    private static void marker(ReplayFrame f, Matrix4f d, VertexConsumer glow, ReplayBoardModel.EventMark e, double a, float elemScale) {
        if (a <= 0.02) return;
        float x = e.tx, y = e.ty + 0.008f, z = e.tz;
        switch (e.node.kind()) {
            case KILL -> {
                boolean overview = f.video && f.frame != null && f.frame.phase() == ReplayTimeline.Phase.REVIEW;
                float r = overview ? 0.058f : 0.035f;
                float w = (overview ? 0.020f : 0.014f) * elemScale;
                int c = ReplayPalette.alpha(ReplayPalette.RUBY, (overview ? 1.0 : 0.95) * a);
                ReplayDraw.segment(glow, d, x - r, y, z - r, x + r, y, z + r, w, c, c);
                ReplayDraw.segment(glow, d, x - r, y, z + r, x + r, y, z - r, w, c, c);
                ReplayDraw.ring(glow, d, x, y, z, overview ? 0.082f : 0.05f, overview ? 0.014f : 0.008f, 18,
                        ReplayPalette.alpha(ReplayPalette.RUBY, (overview ? 0.78 : 0.5) * a),
                        ReplayPalette.alpha(ReplayPalette.RUBY, 0.1 * a));
            }
            case GUNSHOT -> ReplayDraw.segment(glow, d, e.fx, e.fy, e.fz, e.tx, e.ty, e.tz, 0.006f * elemScale,
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.35 * a), ReplayPalette.alpha(e.has(HIT) ? ReplayPalette.RUBY : ReplayPalette.LAMP, 0.35 * a));
            case GRENADE_EXPLOSION, BOMB_DETONATE -> ReplayDraw.ring(glow, d, x, y, z, 0.07f, 0.012f, 20,
                    ReplayPalette.alpha(ReplayPalette.EXPLOSION, 0.7 * a), ReplayPalette.alpha(ReplayPalette.EXPLOSION, 0.1 * a));
            case POISON -> ReplayDraw.disc(glow, d, x, y, z, 0.035f, 12, ReplayPalette.alpha(ReplayPalette.POISON, 0.7 * a),
                    ReplayPalette.alpha(ReplayPalette.POISON, 0));
            default -> ReplayDraw.disc(glow, d, x, y, z, 0.022f, 10,
                    ReplayPalette.alpha(actorColor(f, e.node.actor()), 0.45 * a), ReplayPalette.alpha(ReplayPalette.BRASS, 0));
        }
    }

    private static void halo(VertexConsumer glow, Matrix4f d, float x, float y, float z, double k, double a, int color, float size) {
        double fadeOut = 1 - ReplayStagePlan.easeOutCubic(k);
        ReplayDraw.ring(glow, d, x, y, z, (float) (size * (0.4 + k)), 0.016f, 22, ReplayPalette.alpha(color, 0.85 * fadeOut * a),
                ReplayPalette.alpha(color, 0));
        ReplayDraw.disc(glow, d, x, y - 0.001f, z, size * 0.5f, 14, ReplayPalette.alpha(color, 0.3 * fadeOut * a),
                ReplayPalette.alpha(color, 0));
    }

    private static void arc(VertexConsumer glow, Matrix4f d, ReplayBoardModel.EventMark e, double a, int color, float elemScale) {
        Vector3f prev = d.transformPosition(e.fx, e.fy, e.fz, new Vector3f());
        float dist = (float) Math.sqrt((e.tx - e.fx) * (e.tx - e.fx) + (e.tz - e.fz) * (e.tz - e.fz));
        for (int i = 1; i <= 12; i++) {
            float u = i / 12f;
            float h = 4 * u * (1 - u) * Math.max(0.06f, dist * 0.35f);
            Vector3f p = d.transformPosition(e.fx + (e.tx - e.fx) * u, e.fy + (e.ty - e.fy) * u + h, e.fz + (e.tz - e.fz) * u, new Vector3f());
            if (i % 2 == 1) ReplayDraw.segment(glow, prev, p, 0.012f * elemScale, ReplayPalette.alpha(color, 0.8 * a), ReplayPalette.alpha(color, 0.8 * a));
            prev = p;
        }
    }

    static void warning(ReplayFrame f, VertexConsumer glow, Vector3f at, double a, float elemScale) {
        Vector3f top = new Vector3f(at).add(new Vector3f(f.up).mul(0.05f * elemScale));
        Vector3f left = new Vector3f(at).sub(new Vector3f(f.right).mul(0.045f * elemScale)).sub(new Vector3f(f.up).mul(0.03f * elemScale));
        Vector3f right = new Vector3f(at).add(new Vector3f(f.right).mul(0.045f * elemScale)).sub(new Vector3f(f.up).mul(0.03f * elemScale));
        int c = ReplayPalette.alpha(ReplayPalette.LAMP, a);
        ReplayDraw.segment(glow, top, left, 0.012f * elemScale, c, c);
        ReplayDraw.segment(glow, left, right, 0.012f * elemScale, c, c);
        ReplayDraw.segment(glow, right, top, 0.012f * elemScale, c, c);
        f.billboardText(new Vector3f(at).sub(new Vector3f(f.up).mul(0.005f * elemScale)), Component.literal("!"), 0.004f * elemScale, c);
    }

    /** Floating, slowly spinning 3D weapon model above a kill / detonation. */
    private static void markerWeapon(ReplayFrame f, Matrix4f d, ReplayBoardModel.EventMark e, double alpha, float elemScale) {
        weapon(f, d, e, e.tx, e.ty + 0.008f + 0.14f, e.tz, 0.08f, alpha * 0.8, elemScale);
    }

    private static void weapon(ReplayFrame f, Matrix4f d, ReplayBoardModel.EventMark e, float x, float y, float z,
                               float size, double a, float elemScale) {
        if (a <= 0.1) return;
        Vector3f c = d.transformPosition(x, y, z, new Vector3f());
        if (!f.video) {
            ReplayDraw.billboard(f.glow(), c, f.right, f.up, size * 0.75f * elemScale, size * 0.75f * elemScale,
                    ReplayPalette.alpha(e.icon.color(), 0.18 * a));
        }
        float bob = (float) Math.sin(f.anim * 2.4 + e.node.tick()) * 0.012f;
        Matrix4f m = new Matrix4f(d).translate(x, y + bob, z).rotateY((float) (f.anim * 1.6 + e.node.tick() * 0.1)).scale(size * 1.6f);
        var stack = e.item();
        f.model(m, pose -> Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, f.modelBuffers(), f.level, 0));
    }

    private static int actorColor(ReplayFrame f, @Nullable java.util.UUID id) {
        ReplayBoardModel.Actor actor = f.model == null ? null : f.model.participant(id);
        return actor == null ? ReplayPalette.BRASS_LIGHT : actor.color;
    }

    // ------------------------------------------------------------------ anti-occlusion side-wing kill feed

    static void briefing(ReplayFrame f, ReplayBoardSite s) {
        killFeed(f, s, Double.NEGATIVE_INFINITY, f.model == null ? 1.0 : f.model.rate, 1.0);
    }

    /**
     * Star-Night Express holographic side-wing kill &amp; action feed matching {@link ReplayVideoOverlay}.
     *
     * <p>Positions the stack dynamically above the true highest diorama geometry
     * ({@code effectiveTop = MAP_BASE + max(mapH, meshCeiling * scale) * currentZoom + clearance})
     * and offset onto the viewer's right flank so single-story trains, multi-story stations, and
     * 2.4x close-up zooms never intersect or occlude the feed cards.</p>
     */
    static void killFeed(ReplayFrame f, ReplayBoardSite s, double meshReplayCeiling,
                         double playbackSpeed, double currentZoom) {
        ReplayBoardModel model = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        if (model == null || stage == null || f.frame == null || stage.actors() <= 0.05) return;
        double t = f.replayTick;
        double speed = Math.max(0.5, playbackSpeed);
        double windowTicks = FEED_DURATION_SEC * 20.0 * speed;

        var feed = model.feedEvents;
        int lo = 0, hi = feed.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (feed.get(mid).node.tick() <= t) lo = mid + 1;
            else hi = mid;
        }
        int end = lo;
        int from = end;
        while (from > 0 && end - from < MAX_FEED && t - feed.get(from - 1).node.tick() <= windowTicks) from--;
        int count = end - from;
        if (count == 0) return;
        float worldScale = s.scale();
        float cardW = Math.min(2.10f, Math.max(1.38f, s.hw() * 0.56f));
        float cardH = 0.215f;
        float rowStep = 0.248f;
        float stackHeight = (count - 1) * rowStep + cardH;

        float clearance = ReplayStagePlan.DEFAULT_FEED_CLEARANCE + 0.06f * (float) Math.max(1.0, currentZoom);
        float effectiveTop = ReplayStagePlan.effectiveFeedTop(
                ReplayBoardSite.MAP_BASE, model.mapH, meshReplayCeiling, model.scale, currentZoom, clearance);
        // Compensate for camera pitch tilt so the lowest card edge stays strictly above effectiveTop.
        float pitchSafeLift = stackHeight * 0.5f * Math.abs(f.look.y) + 0.04f;
        float bottomCardCenterY = effectiveTop + cardH * 0.5f + pitchSafeLift;

        // Place onto the viewer's right flank of the tray (matches top-right feed in exported videos).
        Vector3f horizRight = new Vector3f(f.right.x, 0f, f.right.z);
        if (horizRight.lengthSquared() < 1e-5f) horizRight.set(1f, 0f, 0f);
        else horizRight.normalize();
        Vector3f horizTowardViewer = new Vector3f(-f.look.x, 0f, -f.look.z);
        if (horizTowardViewer.lengthSquared() < 1e-5f) horizTowardViewer.set(0f, 0f, 1f);
        else horizTowardViewer.normalize();

        float flankOffset = Math.max(0.55f, s.hw() * 0.62f - cardW * 0.25f) * worldScale;
        float viewerPull = Math.min(0.45f, s.hd() * 0.16f) * worldScale;
        Vector3f baseAnchor = s.board().transformPosition(0f, bottomCardCenterY, 0f, new Vector3f());
        baseAnchor.add(horizRight.x * flankOffset + horizTowardViewer.x * viewerPull,
                0f,
                horizRight.z * flankOffset + horizTowardViewer.z * viewerPull);

        VertexConsumer panel = f.buffers.getBuffer(ReplayDraw.PANEL);
        VertexConsumer glow = f.glow();
        Font font = f.mc.font;

        // Render oldest at bottom (index 0 above effectiveTop) and newest on top.
        for (int idx = 0; idx < count; idx++) {
            ReplayBoardModel.EventMark mark = feed.get(from + idx);
            double ageSec = (t - mark.node.tick()) / (20.0 * speed);
            double in = ReplayStagePlan.easeOutCubic(ageSec / FEED_IN_SEC);
            double out = 1.0 - ReplayStagePlan.smoothstep(FEED_OUT_START_SEC, FEED_DURATION_SEC, ageSec);
            double a = in * out * s.fade() * stage.actors();
            if (a <= 0.02) continue;

            float localY = idx * rowStep;
            float slideX = (float) ((1.0 - in) * 0.42);
            Matrix4f m = new Matrix4f()
                    .translate(baseAnchor.x, baseAnchor.y + localY * worldScale, baseAnchor.z)
                    .rotate(f.rotation)
                    .scale(worldScale)
                    .translate(slideX, 0f, 0f);

            drawFeedCard(f, panel, glow, font, m, mark, cardW, cardH, worldScale, a);
        }
    }

    static boolean isFeedEvent(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case KILL, BOMB_DETONATE, GRENADE_EXPLOSION, PLAYER_REVIVAL, BOMB_DEFUSE, SHIELD_BROKEN -> true;
            case GUNSHOT -> (node.flags() & LastMatchReplay.BACKFIRE) != 0;
            default -> false;
        };
    }

    private static void drawFeedCard(ReplayFrame f, VertexConsumer panel, VertexConsumer glow, Font font,
                                     Matrix4f m, ReplayBoardModel.EventMark mark,
                                     float cardW, float cardH, float worldScale, double a) {
        LastMatchReplay.Node node = mark.node;
        ReplayBoardModel model = f.model;
        ReplayBoardModel.Actor killer = model.participant(node.actor());
        ReplayBoardModel.Actor victim = model.participant(node.target());

        float hw = cardW * 0.5f;
        float hh = cardH * 0.5f;
        float skew = 0.042f;
        int accent = feedAccentColor(node, killer, victim);

        VertexConsumer ui = f.buffers.getBuffer(ReplayDraw.UI_OVERLAY);
        // Slanted Star-Night Express Art Deco plate (matches ReplayVideoOverlay.feed slab)
        int leftCol = ReplayPalette.alpha(ReplayPalette.NIGHT_1, 0.84 * a);
        int rightCol = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.95 * a);
        ReplayDraw.quad(panel, m,
                -hw, -hh, 0f,
                hw, -hh, 0f,
                hw + skew, hh, 0f,
                -hw + skew, hh, 0f,
                leftCol, rightCol, rightCol, leftCol);

        // Faction / event right side accent strip (flat in UI_OVERLAY, crisp even in daylight)
        float barW = 0.030f;
        int barCol = ReplayPalette.alpha(accent, 0.96 * a);
        ReplayDraw.quad(ui, m,
                hw - barW, -hh, 0.004f,
                hw, -hh, 0.004f,
                hw + skew, hh, 0.004f,
                hw - barW + skew, hh, 0.004f,
                barCol, barCol, barCol, barCol);

        // Crisp flat brass top & bottom Art Deco hairlines (zero billboard depth clipping)
        int brassHi = ReplayPalette.alpha(ReplayPalette.BRASS, 0.86 * a);
        int brassLo = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.38 * a);
        ReplayDraw.lineXY(ui, m, -hw + skew, hh - 0.003f, hw + skew, hh - 0.003f, 0.005f, 0.006f, brassLo, brassHi);
        ReplayDraw.lineXY(ui, m, -hw, -hh + 0.003f, hw, -hh + 0.003f, 0.005f, 0.005f, brassLo, brassLo);

        float textScale = 0.0056f;
        float leftPad = -hw + 0.06f;
        float rightLimit = hw - barW - 0.04f;

        if (node.kind() == Kind.KILL) {
            float faceSize = cardH * 0.68f;
            float cursorX = leftPad;
            // Killer avatar + role-colored name
            if (killer != null) {
                drawFeedAvatar(f, m, killer.p.id(), cursorX + faceSize * 0.5f, 0f, 0.008f, faceSize, 0xFFFFFFFF, a);
                cursorX += faceSize + 0.04f;
                int kColor = ReplayPalette.alpha(ReplayPalette.lerp(killer.color, 0xFFFFFFFF, 0.20), a);
                float killerMaxW = (rightLimit - cursorX) * 0.36f;
                f.text(m, killer.name, cursorX, 0f, 0.011f, textScale, kColor, -1, true, killerMaxW);
                cursorX += Math.min(killerMaxW, font.width(killer.name) * textScale) + 0.045f;
            } else {
                Component env = ReplayBoardTexts.tr("environment");
                float envMaxW = (rightLimit - cursorX) * 0.28f;
                f.text(m, env, cursorX, 0f, 0.011f, textScale, ReplayPalette.alpha(ReplayPalette.TEXT_MUTED, a), -1, true, envMaxW);
                cursorX += Math.min(envMaxW, font.width(env) * textScale) + 0.045f;
            }

            // Weapon icon diamond medallion in center (clean symmetric Art Deco badge)
            float wx = cursorX + 0.075f;
            float mr = 0.066f;
            int discCol = ReplayPalette.alpha(accent, 0.22 * a);
            ReplayDraw.quad(ui, m, wx, mr, 0.004f, wx + mr, 0f, 0.004f, wx, -mr, 0.004f, wx - mr, 0f, 0.004f,
                    discCol, discCol, discCol, discCol);
            ReplayDraw.ringXY(ui, m, wx, 0f, 0.005f, 0.058f, 0.005f, 16, ReplayPalette.alpha(accent, 0.55 * a));
            var weaponStack = mark.item();
            Matrix4f itemM = new Matrix4f(m).translate(wx, 0f, 0.010f).scale(0.135f);
            f.model(itemM, pose -> Minecraft.getInstance().getItemRenderer().renderStatic(
                    weaponStack, ItemDisplayContext.GUI, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, pose, f.modelBuffers(), f.level, 0));
            cursorX = wx + 0.095f;

            // Victim red-X + strikethrough name on the right
            Component victimName = ReplayBoardTexts.name(victim == null ? null : victim.p);
            float xMarkR = 0.030f;
            float xMarkCx = cursorX + xMarkR;
            int rubyCol = ReplayPalette.alpha(ReplayPalette.RUBY, 0.95 * a);
            ReplayDraw.lineXY(ui, m, xMarkCx - xMarkR, -xMarkR, xMarkCx + xMarkR, xMarkR, 0.007f, 0.008f, rubyCol, rubyCol);
            ReplayDraw.lineXY(ui, m, xMarkCx - xMarkR, xMarkR, xMarkCx + xMarkR, -xMarkR, 0.007f, 0.008f, rubyCol, rubyCol);
            float vTextX = xMarkCx + xMarkR + 0.035f;
            float vAvail = Math.max(0.15f, rightLimit - vTextX);
            float vRawW = font.width(victimName) * textScale;
            float vDrawnW = Math.min(vAvail, vRawW);
            f.text(m, victimName, vTextX, 0f, 0.010f, textScale, ReplayPalette.alpha(ReplayPalette.TEXT, 0.92 * a), -1, true, vAvail);
            // Ruby strikethrough bar across victim name (matches ReplayVideoOverlay.card)
            ReplayDraw.lineXY(glow, m, vTextX, 0.002f, vTextX + vDrawnW, 0.002f, 0.012f, 0.006f, rubyCol, rubyCol);
        } else {
            ReplayBoardModel.Actor subject = node.kind() == Kind.SHIELD_BROKEN && victim != null ? victim
                    : killer != null ? killer : victim;
            float faceSize = cardH * 0.66f;
            float cursorX = leftPad;
            if (subject != null) {
                drawFeedAvatar(f, m, subject.p.id(), cursorX + faceSize * 0.5f, 0f, 0.008f, faceSize, 0xFFFFFFFF, a);
                cursorX += faceSize + 0.045f;
            }
            Component summary = switch (node.kind()) {
                case BOMB_DETONATE, GRENADE_EXPLOSION -> ReplayBoardTexts.tr("brief.explosion",
                        ReplayBoardTexts.name(subject == null ? null : subject.p));
                case GUNSHOT -> ReplayBoardTexts.tr("brief.shot",
                        ReplayBoardTexts.name(killer == null ? null : killer.p),
                        ReplayBoardTexts.weapon(node),
                        ReplayBoardTexts.name(victim == null ? null : victim.p));
                case PLAYER_REVIVAL -> Component.translatable("habitrain_core.replay_video.card.revived",
                        ReplayBoardTexts.name(subject == null ? null : subject.p));
                case BOMB_DEFUSE -> Component.translatable("habitrain_core.replay_video.card.defused",
                        ReplayBoardTexts.name(subject == null ? null : subject.p));
                case SHIELD_BROKEN -> Component.translatable("habitrain_core.replay_video.card.shield",
                        ReplayBoardTexts.name(subject == null ? null : subject.p));
                default -> ReplayBoardTexts.name(subject == null ? null : subject.p);
            };
            f.text(m, summary, cursorX, 0f, 0.011f, textScale,
                    ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), -1, true, Math.max(0.2f, rightLimit - cursorX));
        }
    }

    private static int feedAccentColor(LastMatchReplay.Node node,
                                       @Nullable ReplayBoardModel.Actor killer,
                                       @Nullable ReplayBoardModel.Actor victim) {
        return switch (node.kind()) {
            case KILL -> killer != null ? killer.factionColor() : ReplayPalette.RUBY;
            case BOMB_DETONATE, GRENADE_EXPLOSION -> ReplayPalette.EXPLOSION;
            case PLAYER_REVIVAL -> ReplayPalette.BRASS_LIGHT;
            case BOMB_DEFUSE, SHIELD_BROKEN -> ReplayPalette.SHIELD;
            case GUNSHOT -> ReplayPalette.LAMP;
            default -> victim != null ? victim.factionColor() : ReplayPalette.BRASS;
        };
    }

    /** Crisp 2D player face (base + hat layer) on a world-space panel. */
    static void drawFeedAvatar(ReplayFrame f, Matrix4f panelMatrix, java.util.UUID playerId,
                               float cx, float cy, float z, float size, int tint, double alpha) {
        if (alpha <= 0.02) return;
        ResourceLocation skin = ReplayHeadSkins.texture(playerId);
        int color = ReplayPalette.alpha(tint, alpha);
        float half = size * 0.5f;
        Matrix4f m = new Matrix4f(panelMatrix).translate(cx, cy, z);
        f.model(m, stack -> {
            VertexConsumer vc = f.modelBuffers().getBuffer(RenderType.entityTranslucent(skin));
            PoseStack.Pose pose = stack.last();
            int light = LightTexture.FULL_BRIGHT;
            // Base face UV: 8..16 / 64
            emitFaceQuad(vc, pose, half, 0f, 8f / 64f, 8f / 64f, 16f / 64f, 16f / 64f, color, light);
            // Hat overlay UV: 40..48 / 64, clearly proud in Z to avoid z-fighting with base face
            emitFaceQuad(vc, pose, half * 1.07f, 0.003f, 40f / 64f, 8f / 64f, 48f / 64f, 16f / 64f, color, light);
        });
    }

    private static void emitFaceQuad(VertexConsumer vc, PoseStack.Pose pose, float h, float z,
                                     float u0, float v0, float u1, float v1, int color, int light) {
        vc.addVertex(pose, -h, h, z).setColor(color).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, h, h, z).setColor(color).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, h, -h, z).setColor(color).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, -h, -h, z).setColor(color).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 0, 1);
    }
}
