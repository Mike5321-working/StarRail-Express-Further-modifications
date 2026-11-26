package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Participants on the diorama: complete two-layer 3D humanoid skin characters (head/body/arms/legs,
 * capes, walk/sprint/sneak/crawl/sleep/hurt/aim/swing animations, 3D held items, disguise swaps
 * with god-view violet phantom echoes), faction / state rings, Promo Tier-1 spotlight/dimming
 * emphasis, and comet trails over a faint full-history thread.
 */
final class ReplayActorPainter {
    private ReplayActorPainter() {}

    private static float[] points = new float[3 * 512];
    private static float[] widths = new float[512];
    private static int[] colors = new int[512];
    private static float[] haloWidths = new float[512];
    private static int[] haloColors = new int[512];

    static void paint(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayActorSizing heads) {
        paint(f, s, d, heads, null);
    }

    static void paint(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayActorSizing heads,
                      @org.jetbrains.annotations.Nullable ReplayBoardHistoryCache history) {
        ReplayBoardModel model = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        if (model == null || stage == null || stage.actors() <= 0.01) return;
        double t = f.replayTick;
        double baseAlpha = stage.actors() * s.fade();
        boolean review = f.frame != null && (f.frame.phase() == ReplayTimeline.Phase.REVIEW || f.frame.phase() == ReplayTimeline.Phase.HONORS);
        float elemScale = s.scale() * (float) f.focus.zoom();
        VertexConsumer glow = f.glow();
        var scope = history == null ? null : f.activeMapScope();
        Vector3f trailPoint = new Vector3f();
        boolean singleFocus = f.focusPlayer != null;
        ReplayFullPlayerRenderer.HighlightSpec highlight = f.highlight != null
                ? f.highlight : ReplayFullPlayerRenderer.HighlightSpec.NONE;

        for (ReplayBoardModel.Actor actor : model.actors) {
            boolean focusedTrail = singleFocus && f.focusPlayer.equals(actor.p.id());
            boolean mvpMode = f.mvpSpotlightActor != null;
            boolean isMvp = mvpMode && f.mvpSpotlightActor.equals(actor.p.id());
            if (mvpMode && !isMvp) continue; // Keep the MVP showcase stage focused solely on the champion without overlapping corpses.
            ReplayTrackSampler.Pose pose = f.pose(actor);
            var position = pose.visible() ? f.project(actor, heads) : null;
            float actorDim = highlight.brightnessFor(actor.p.id());
            double alpha = baseAlpha * actorDim;

            boolean drawBody = position != null && position.size() > 0;
            float size = drawBody ? position.size() * highlight.scaleFor(actor.p.id()) : 0f;
            float x = drawBody ? position.x() : 0f;
            float y = drawBody ? position.y() + 0.006f : 0f;
            float z = drawBody ? position.z() : 0f;
            if (drawBody && !heads.visible(x, y, z, size)) drawBody = false;
            boolean alive = pose.state() == ReplayTrackSampler.State.ALIVE;
            int ringColor = alive ? actor.color : ReplayPalette.GHOST;
            double ringA = alive ? 0.85 : 0.35;
            if (review && actor.p.winner()) ringColor = ReplayPalette.BRASS_LIGHT;
            if (actorDim < 0.99f) ringColor = ReplayPalette.scale(ringColor, actorDim);

            boolean wantTrail = (!singleFocus || focusedTrail);
            double trailAlpha = mvpMode ? (isMvp ? alpha * 0.22 : alpha * 0.05) : alpha;
            boolean emitTrail = wantTrail && trailAlpha > 0.015;
            if (history != null) {
                // Settled review caches both the full trail and the static floor ring/disc in one replay slot.
                if (!history.emit(scope, actor.index)) {
                    boolean recordRing = position != null && position.size() > 0;
                    float rSize = recordRing ? position.size() * highlight.scaleFor(actor.p.id()) : 0f;
                    float rx = recordRing ? position.x() : 0f;
                    float ry = recordRing ? position.y() + 0.006f : 0f;
                    float rz = recordRing ? position.z() : 0f;
                    int rColor = ringColor;
                    double rAlpha = ringA;
                    history.record(scope, actor.index, target -> {
                        if (emitTrail) {
                            var live = alive ? position : null;
                            trails(f, target, d, actor, t, trailAlpha, stage.history(), review, elemScale,
                                    live, trailPoint, s.detail(), false);
                        }
                        if (recordRing) {
                            ReplayDraw.ring(target, d, rx, ry, rz, rSize * 0.58f, rSize * 0.12f, 20,
                                    ReplayPalette.alpha(rColor, rAlpha * alpha),
                                    ReplayPalette.alpha(rColor, rAlpha * 0.25 * alpha));
                            ReplayDraw.disc(target, d, rx, ry - 0.001f, rz, rSize * 0.5f, 16,
                                    ReplayPalette.alpha(rColor, 0.18 * alpha),
                                    ReplayPalette.alpha(rColor, 0));
                        }
                    });
                }
            } else if (emitTrail) {
                var live = alive ? position : null;
                trails(f, glow, d, actor, t, trailAlpha, stage.history(), review, elemScale,
                        live, trailPoint, s.detail(), focusedTrail);
            }
            if (!drawBody) continue;
            ReplayHeadSkins.rememberName(actor.p.id(), actor.p.name());

            if (history == null) {
                ReplayDraw.ring(glow, d, x, y, z, size * 0.58f, size * 0.12f, 20, ReplayPalette.alpha(ringColor, ringA * alpha),
                        ReplayPalette.alpha(ringColor, ringA * 0.25 * alpha));
                ReplayDraw.disc(glow, d, x, y - 0.001f, z, size * 0.5f, 16, ReplayPalette.alpha(ringColor, 0.18 * alpha),
                        ReplayPalette.alpha(ringColor, 0));
            }
            if (focusedTrail) {
                ReplayDraw.ring(glow, d, x, y + 0.002f, z, size * 0.82f, size * 0.11f, 24,
                        ReplayPalette.alpha(ReplayPalette.FOCUS_TRAIL, 0.9 * alpha),
                        ReplayPalette.alpha(ReplayPalette.FOCUS_TRAIL_GLOW, 0.25 * alpha));
            }
            if (highlight.isFocused(actor.p.id()) && highlight.spotlightRing()) {
                ReplayFullPlayerRenderer.emitSpotlightAura(glow, d, x, y, z, size * 0.96f, size * 2.4f,
                        actor.factionColor(), f.anim, baseAlpha);
            }
            if (alive) aliveActor(f, s, d, actor, pose, x, y, z, alpha, actorDim, elemScale, size);
            else deadActor(f, s, d, actor, pose, x, y, z, alpha, actorDim, elemScale, size);
        }
    }

    private static void aliveActor(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayBoardModel.Actor actor,
                                   ReplayTrackSampler.Pose pose, float x, float y, float z,
                                   double alpha, float brightness, float elemScale, float size) {
        ReplayBoardModel model = f.model;
        VertexConsumer glow = f.glow();
        double revive = (pose.revived() && model.hasRevivalNear(actor.p.id(), pose.stateSince()))
                ? ReplayStagePlan.pulse(f.replayTick - pose.stateSince(), ReplayStagePlan.effectWindow(1.0, model.rate))
                : 0;
        float lift = (float) (revive * size * 0.55);
        int tint = ReplayPalette.alpha(ReplayPalette.scale(0xFFFFFFFF, brightness), alpha);
        float yawOffset = model.rotated ? 90f : 0f;

        if (f.fullBodyCharacters) {
            ReplayFullPlayerRenderer.PoseSpec spec = ReplayFullPlayerRenderer.poseFrom(model, actor, pose, f.replayTick);
            float charScale = size * 0.92f;
            Matrix4f m = new Matrix4f(d).translate(x, y + lift, z);
            f.model(m, charScale * 1.15f, -0.15f * charScale, 2.25f * charScale,
                    stack -> ReplayFullPlayerRenderer.renderCharacter(
                            stack, f.modelBuffers(), spec, charScale, yawOffset, f.anim,
                            tint, LightTexture.FULL_BRIGHT, 1.18f, true));
        } else {
            double moving = Math.min(1, pose.speed() / 0.12);
            float bob = (float) (Math.abs(Math.sin(f.anim * 9.0 + actor.index * 1.7)) * size * 0.15 * moving);
            float hy = y + ReplayActorSizing.centerOffset(size) + bob + lift;
            float yaw = yawOffset - pose.yaw();
            head(f, d, actor, x, hy, z, yaw, size, tint, alpha);
        }

        float topY = y + lift + (f.fullBodyCharacters ? size * 1.95f : ReplayActorSizing.centerOffset(size) + size * 0.9f);
        if (revive > 0) {
            ReplayDraw.column(glow, d, x, y, z, 0.45f, 0.06f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.7 * revive * alpha),
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0));
            ReplayDraw.ring(glow, d, x, y + 0.002f, z, (float) (0.08 + (1 - revive) * 0.18), 0.02f, 24,
                    ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, revive * alpha), ReplayPalette.alpha(ReplayPalette.BRASS, 0));
        }
        boolean disguised = pose.has(LastMatchReplay.DISGUISED) || pose.hasStatus(LastMatchReplay.STATUS_DISGUISED);
        if (disguised) {
            ReplayDraw.ring(glow, d, x, y + 0.003f, z, size * 0.79f, size * 0.083f, 20, ReplayPalette.alpha(ReplayPalette.DISGUISE, 0.7 * alpha),
                    ReplayPalette.alpha(ReplayPalette.DISGUISE, 0.1 * alpha));
            Vector3f badge = d.transformPosition(x + size * 0.55f, topY - size * 0.12f, z, new Vector3f());
            ReplayDraw.billboard(glow, badge, f.right, f.up, size * 0.17f * elemScale, size * 0.17f * elemScale,
                    ReplayPalette.alpha(ReplayPalette.DISGUISE, 0.9 * alpha));
        }
        if (pose.hasStatus(LastMatchReplay.STATUS_SHIELDED)) {
            ReplayDraw.ring(glow, d, x, y + size * 0.85f, z, size * 0.62f, size * 0.07f, 20,
                    ReplayPalette.alpha(ReplayPalette.SHIELD, 0.72 * alpha), ReplayPalette.alpha(ReplayPalette.SHIELD, 0.1 * alpha));
        }
        if (pose.hasStatus(LastMatchReplay.STATUS_MUTED)) {
            ReplayDraw.ring(glow, d, x, y + size * 1.35f, z, size * 0.50f, size * 0.06f, 18,
                    ReplayPalette.alpha(0xFF8B5CF6, 0.78 * alpha), ReplayPalette.alpha(0xFF8B5CF6, 0.08 * alpha));
        }
        boolean psycho = pose.has(LastMatchReplay.PSYCHO) || pose.hasStatus(LastMatchReplay.STATUS_PSYCHO)
                || model.psychoAt(actor.p.id(), f.replayTick);
        if (psycho) {
            float midY = y + size * 0.95f;
            Vector3f c = d.transformPosition(x, midY, z, new Vector3f());
            for (int i = 0; i < 6; i++) {
                double ph = f.anim * 6 + i * 1.3 + actor.index;
                float ox = (float) Math.cos(i * 1.05 + f.anim) * size * 0.55f;
                float oz = (float) Math.sin(i * 1.05 + f.anim) * size * 0.55f;
                float h = (float) (0.48 + 0.36 * Math.abs(Math.sin(ph))) * size;
                Vector3f p = d.transformPosition(x + ox, y + h * 0.5f, z + oz, new Vector3f());
                ReplayDraw.softBillboard(glow, p, f.right, f.up, size * 0.20f * elemScale, h * 1.1f * elemScale,
                        ReplayPalette.alpha(ReplayPalette.PSYCHO, 0.48 * alpha), 0.35f, 0.9f);
            }
            ReplayDraw.softBillboard(glow, c, f.right, f.up, size * 1.05f * elemScale, size * 1.05f * elemScale,
                    ReplayPalette.alpha(ReplayPalette.PSYCHO, 0.22 * alpha), 0.45f, 1.1f);
        }
        if (s.detail() && !f.video) {
            Vector3f top = d.transformPosition(x, topY, z, new Vector3f());
            float dist = top.length();
            float distScale = (float) ReplayStagePlan.detailScale(s.scale());
            if (pose.has(LastMatchReplay.ASLEEP) || pose.hasStatus(LastMatchReplay.STATUS_ASLEEP)) {
                f.billboardText(new Vector3f(top).add(0, 0.03f * elemScale, 0), Component.literal("z Z"), 0.0035f * elemScale,
                        ReplayPalette.alpha(ReplayPalette.PASSENGER, alpha));
            } else if (dist < 6.5f * distScale && f.stage.history() < 0.5) {
                double near = 1 - ReplayStagePlan.smoothstep(4.5 * distScale, 6.5 * distScale, dist);
                f.billboardText(top, actor.name, 0.0032f * elemScale, ReplayPalette.alpha(actor.color, near * alpha));
            }
        }
    }

    private static void deadActor(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayBoardModel.Actor actor,
                                  ReplayTrackSampler.Pose pose, float x, float y, float z,
                                  double alpha, float brightness, float elemScale, float size) {
        ReplayBoardModel model = f.model;
        double window = ReplayStagePlan.effectWindow(0.9, model.rate);
        double age = f.replayTick - pose.stateSince();
        float yawOffset = model.rotated ? 90f : 0f;
        float yaw = yawOffset - pose.yaw();
        VertexConsumer glow = f.glow();
        if (age >= 0 && age < window) {
            double k = ReplayStagePlan.easeOutCubic(age / window);
            if (f.fullBodyCharacters) {
                ReplayFullPlayerRenderer.PoseSpec spec = ReplayFullPlayerRenderer.poseFrom(model, actor, pose, f.replayTick);
                float charScale = size * 0.72f;
                int tint = ReplayPalette.alpha(ReplayPalette.lerp(0xFFFFFFFF, 0xFFFF7070, 1.0 - k * 0.5), alpha * (1.0 - 0.35 * k));
                Matrix4f m = new Matrix4f(d).translate(x, y, z);
                f.model(m, charScale * 2.1f, -0.2f * charScale, 2.1f * charScale,
                        stack -> ReplayFullPlayerRenderer.renderCharacter(
                                stack, f.modelBuffers(), spec, charScale, yawOffset, f.anim,
                                tint, LightTexture.FULL_BRIGHT, 1.10f, true));
            } else {
                float drop = (float) (k * size * 0.58);
                float fallen = (float) (size * (1 - 0.35 * k));
                float center = y + ReplayActorSizing.centerOffset(size) - drop;
                head(f, d, actor, x, center, z, yaw + (float) (k * 70), fallen, 0xFFFF8080, alpha * (1 - 0.6 * k));
            }
            Vector3f c = d.transformPosition(x, y + size * 0.5f, z, new Vector3f());
            if (s.detail()) {
                ReplayDraw.star(glow, c, f.right, f.up, (float) (0.42 + k) * size * elemScale, 8, (float) (k * 2),
                        ReplayPalette.alpha(ReplayPalette.RUBY, (1 - k) * alpha), ReplayPalette.alpha(ReplayPalette.RUBY, 0));
            }
            return;
        }
        // Fallen full-body silhouette on ground + subtle Art Deco ghost indicator
        if (f.fullBodyCharacters) {
            ReplayFullPlayerRenderer.PoseSpec spec = ReplayFullPlayerRenderer.poseFrom(model, actor, pose, f.replayTick);
            float charScale = size * 0.68f;
            int corpseTint = ReplayPalette.alpha(ReplayPalette.scale(0xFFD6DCE8, brightness), 0.78 * alpha);
            Matrix4f m = new Matrix4f(d).translate(x, y, z);
            f.model(m, charScale * 2.1f, -0.2f * charScale, 2.1f * charScale,
                    stack -> ReplayFullPlayerRenderer.renderCharacter(
                            stack, f.modelBuffers(), spec, charScale, yawOffset, f.anim,
                            corpseTint, LightTexture.FULL_BRIGHT, 1.06f, true));
        } else {
            // Legacy tombstone fallback
            VertexConsumer fill = f.fill();
            int stone = ReplayPalette.alpha(0xFF6E7380, 0.85 * alpha), stoneTop = ReplayPalette.alpha(0xFF9AA0AE, 0.85 * alpha);
            Matrix4f tm = new Matrix4f(d).translate(x, y, z).rotateY((float) Math.toRadians(yaw));
            float w = size * 0.23f, h = size * 0.5f, t = size * 0.1f;
            ReplayDraw.rectXY(fill, tm, -w, 0, w, h, t, stoneTop, stone);
            ReplayDraw.rectXY(fill, tm, -w, 0, w, h, -t, stoneTop, stone);
            ReplayDraw.quad(fill, tm, -w, h, -t, w, h, -t, w, h, t, -w, h, t, stoneTop, stoneTop, stoneTop, stoneTop);
            ReplayDraw.quad(fill, tm, -w, 0, -t, -w, 0, t, -w, h, t, -w, h, -t, stone, stone, stoneTop, stoneTop);
            ReplayDraw.quad(fill, tm, w, 0, -t, w, 0, t, w, h, t, w, h, -t, stone, stone, stoneTop, stoneTop);
            int cross = ReplayPalette.alpha(ReplayPalette.GHOST, 0.75 * alpha);
            ReplayDraw.rectXY(fill, tm, -w * 0.14f, h * 0.35f, w * 0.14f, h * 0.85f, t + 0.001f, cross, cross);
            ReplayDraw.rectXY(fill, tm, -w * 0.5f, h * 0.62f, w * 0.5f, h * 0.7f, t + 0.001f, cross, cross);
        }
        // Translucent hovering ghost head above fallen player
        float floatY = (float) (1.15 + Math.sin(f.anim * 1.6 + actor.index) * 0.1) * size;
        head(f, d, actor, x, y + floatY, z, yaw, size * 0.68f, 0xFFB9C3D9, alpha * 0.28);
    }

    /** Two-layer skin head (8x8x8 + 1.125x hat) centred at a diorama-frame point (also used by Honors podium). */
    static void head(ReplayFrame f, Matrix4f frame, ReplayBoardModel.Actor actor, float x, float y, float z, float yawDeg,
                     float size, int tint, double alpha) {
        if (alpha <= 0.02) return;
        ResourceLocation skin = ReplayHeadSkins.skin(actor.p.id(), actor.p.name()).texture();
        Matrix4f m = new Matrix4f(frame).translate(x, y, z).rotateY((float) Math.toRadians(yawDeg)).scale(size);
        int color = ReplayPalette.alpha(tint, alpha);
        f.model(m, 0.65f, -0.65f, 0.65f, stack -> drawHead(f, stack, skin, color));
    }

    static void drawHead(ReplayFrame f, PoseStack stack, ResourceLocation skin, int color) {
        VertexConsumer vc = f.modelBuffers().getBuffer(RenderType.entityTranslucent(skin));
        PoseStack.Pose pose = stack.last();
        cube(vc, pose, 0.5f, 0, color);
        cube(vc, pose, 0.5625f, 32, color);
    }

    private static void cube(VertexConsumer vc, PoseStack.Pose pose, float h, int uOff, int color) {
        int light = LightTexture.FULL_BRIGHT;
        // front (+Z): u 8..16
        face(vc, pose, color, light, 0, 0, 1, -h, h, h, h, h, h, h, -h, h, -h, -h, h, 8 + uOff, 8, 16 + uOff, 16);
        // back (-Z): u 24..32
        face(vc, pose, color, light, 0, 0, -1, h, h, -h, -h, h, -h, -h, -h, -h, h, -h, -h, 24 + uOff, 8, 32 + uOff, 16);
        // player's right (-X): u 0..8
        face(vc, pose, color, light, -1, 0, 0, -h, h, -h, -h, h, h, -h, -h, h, -h, -h, -h, uOff, 8, 8 + uOff, 16);
        // player's left (+X): u 16..24
        face(vc, pose, color, light, 1, 0, 0, h, h, h, h, h, -h, h, -h, -h, h, -h, h, 16 + uOff, 8, 24 + uOff, 16);
        // top: u 8..16, v 0..8
        face(vc, pose, color, light, 0, 1, 0, -h, h, -h, h, h, -h, h, h, h, -h, h, h, 8 + uOff, 0, 16 + uOff, 8);
        // bottom: u 16..24, v 0..8
        face(vc, pose, color, light, 0, -1, 0, -h, -h, h, h, -h, h, h, -h, -h, -h, -h, -h, 16 + uOff, 0, 24 + uOff, 8);
    }

    private static void face(VertexConsumer vc, PoseStack.Pose pose, int color, int light, float nx, float ny, float nz,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float u0, float v0, float u1, float v1) {
        float a = u0 / 64f, b = v0 / 64f, c = u1 / 64f, e = v1 / 64f;
        vc.addVertex(pose, x0, y0, z0).setColor(color).setUv(a, b).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, x1, y1, z1).setColor(color).setUv(c, b).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, x2, y2, z2).setColor(color).setUv(c, e).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, x3, y3, z3).setColor(color).setUv(a, e).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    // ------------------------------------------------------------------ trails

    private static void trails(ReplayFrame f, VertexConsumer glow, Matrix4f d, ReplayBoardModel.Actor actor, double t, double alpha,
                               double history, boolean review, float elemScale,
                               @org.jetbrains.annotations.Nullable ReplayFrame.ActorProjection livePosition,
                               Vector3f tmp, boolean detail, boolean focused) {
        ReplayTrackSampler track = actor.track;
        int last = track.indexAt(t);
        if (last < 0) return;
        ReplayBoardModel model = f.model;
        VertexConsumer fill = focused ? f.fill() : null;
        double cometStart = t - model.comet;
        boolean videoReview = f.video && f.frame != null && f.frame.phase() == ReplayTimeline.Phase.REVIEW;
        double baseA = alpha * (videoReview ? 0.22 + 0.58 * history : (0.13 + 0.32 * history) * (review ? 1.2 : 1));
        int base = ReplayPalette.scale(actor.color, 0.85);
        int tip = ReplayPalette.lerp(actor.color, 0xFFFFFFFF, 0.35);
        int recent = Math.max(0, track.indexAt(cometStart));
        int first = detail || focused ? 0 : recent;
        int stride = f.video ? 1 : Math.max(1, (recent + 95) / 96);
        float trailScale = focused ? (videoReview ? elemScale * 1.45f : elemScale) : (videoReview ? elemScale * 1.65f : elemScale);
        float yLift = focused ? 0.014f : 0.012f;
        float[] px = actor.projX, py = actor.projY, pz = actor.projZ;
        int n = 0;
        int cap = widths.length - 1;
        for (int i = first; i <= last; i = f.video
                ? actor.nextTrailPoint(i, recent, stride)
                : actor.nextVisualTrailPoint(i, recent, last, stride)) {
            boolean startsSegment = i == first || !track.connects(i);
            if (startsSegment) {
                if (n >= 2) emitTrail(fill, glow, n, focused);
                n = 0;
            }
            if (n >= cap) {
                emitTrail(fill, glow, n, focused);
                int o = (n - 1) * 3;
                points[0] = points[o]; points[1] = points[o + 1]; points[2] = points[o + 2];
                widths[0] = widths[n - 1]; colors[0] = colors[n - 1];
                if (focused) {
                    haloWidths[0] = haloWidths[n - 1]; haloColors[0] = haloColors[n - 1];
                }
                n = 1;
            }
            d.transformPosition(px[i], py[i] + yLift, pz[i], tmp);
            if (focused) {
                putFocused(n++, tmp, track.tick(i), cometStart, t, alpha, videoReview, trailScale);
            } else {
                put(n++, tmp, track.tick(i), cometStart, t, baseA, base, tip, alpha, trailScale);
            }
        }
        if (livePosition != null && n >= 1) {
            d.transformPosition(livePosition.x(), livePosition.y() + yLift, livePosition.z(), tmp);
            if (focused) {
                putFocused(n++, tmp, t, cometStart, t, alpha, videoReview, trailScale);
            } else {
                put(n++, tmp, t, cometStart, t, baseA, base, tip, alpha, trailScale);
            }
        }
        if (n >= 2) emitTrail(fill, glow, n, focused);
    }

    private static void put(int index, Vector3f p, double tick, double cometStart, double t, double baseA, int base, int tip,
                            double alpha, float elemScale) {
        int o = index * 3;
        points[o] = p.x; points[o + 1] = p.y; points[o + 2] = p.z;
        double comet = tick >= cometStart ? (tick - cometStart) / Math.max(1, t - cometStart) : 0;
        double a = baseA + comet * comet * 0.85 * alpha;
        int c = comet == 0 ? base : ReplayPalette.lerp(base, tip, comet);
        colors[index] = ReplayPalette.alpha(c, a);
        widths[index] = (float) (0.010 + 0.022 * comet) * elemScale;
    }

    /** Bold light-red trail (alpha-blended core + soft additive halo) for single-player perspective export. */
    private static void putFocused(int index, Vector3f p, double tick, double cometStart, double t,
                                   double alpha, boolean videoReview, float elemScale) {
        int o = index * 3;
        points[o] = p.x; points[o + 1] = p.y; points[o + 2] = p.z;
        double comet = tick >= cometStart ? (tick - cometStart) / Math.max(1, t - cometStart) : 0;
        double coreAlpha = (videoReview ? 0.85 : 0.72) + comet * comet * 0.23;
        int coreColor = ReplayPalette.lerp(ReplayPalette.FOCUS_TRAIL, ReplayPalette.FOCUS_TRAIL_HEAD, comet * 0.65);
        colors[index] = ReplayPalette.alpha(coreColor, Math.min(1.0, coreAlpha) * alpha);
        widths[index] = (float) (0.026 + 0.024 * comet) * elemScale;
        double haloAlpha = (videoReview ? 0.32 : 0.26) + comet * comet * 0.34;
        haloColors[index] = ReplayPalette.alpha(ReplayPalette.FOCUS_TRAIL_GLOW, Math.min(1.0, haloAlpha) * alpha);
        haloWidths[index] = (float) (0.042 + 0.032 * comet) * elemScale;
    }

    private static void emitTrail(@org.jetbrains.annotations.Nullable VertexConsumer fill, VertexConsumer glow, int n, boolean focused) {
        if (focused && fill != null) {
            ReplayDraw.ribbon(fill, points, n, widths, colors);
            ReplayDraw.ribbon(glow, points, n, haloWidths, haloColors);
        } else {
            ReplayDraw.ribbon(glow, points, n, widths, colors);
        }
    }
}
