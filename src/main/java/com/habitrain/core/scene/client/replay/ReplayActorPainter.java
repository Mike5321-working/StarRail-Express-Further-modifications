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
 * Participants on the diorama: two-layer 3D skin heads, faction / state rings, comet trails over a
 * faint full-history thread, and death / revival / disguise / psycho / sleep states.
 */
final class ReplayActorPainter {
    private ReplayActorPainter() {}

    private static float[] points = new float[3 * 512];
    private static float[] widths = new float[512];
    private static int[] colors = new int[512];

    static void paint(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayActorSizing heads) {
        ReplayBoardModel model = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        if (model == null || stage == null || stage.actors() <= 0.01) return;
        double t = f.replayTick;
        double alpha = stage.actors() * s.fade();
        boolean review = f.frame != null && (f.frame.phase() == ReplayTimeline.Phase.REVIEW || f.frame.phase() == ReplayTimeline.Phase.HONORS);
        float elemScale = s.scale() * (float) f.focus.zoom();
        VertexConsumer glow = f.glow();
        float[] tmp = new float[3];
        Vector3f trailPoint = new Vector3f();
        for (ReplayBoardModel.Actor actor : model.actors) {
            ReplayTrackSampler.Pose pose = f.pose(actor);
            if (pose.visible()) model.project(pose.x(), pose.y(), pose.z(), tmp);
            trails(f, d, actor, t, alpha, stage.history(), review, elemScale, pose, tmp, trailPoint, s.detail());
            if (!pose.visible()) continue;
            float size = heads.size(actor.p.id(), tmp[0], tmp[1], tmp[2]);
            if (size <= 0) continue;
            float x = tmp[0], y = tmp[1] + 0.006f, z = tmp[2];
            boolean alive = pose.state() == ReplayTrackSampler.State.ALIVE;
            int ringColor = alive ? actor.color : ReplayPalette.GHOST;
            double ringA = alive ? 0.85 : 0.35;
            if (review && actor.p.winner()) ringColor = ReplayPalette.BRASS_LIGHT;
            ReplayDraw.ring(glow, d, x, y, z, size * 0.58f, size * 0.12f, 20, ReplayPalette.alpha(ringColor, ringA * alpha),
                    ReplayPalette.alpha(ringColor, ringA * 0.25 * alpha));
            ReplayDraw.disc(glow, d, x, y - 0.001f, z, size * 0.5f, 16, ReplayPalette.alpha(ringColor, 0.18 * alpha),
                    ReplayPalette.alpha(ringColor, 0));
            if (alive) aliveActor(f, s, d, actor, pose, x, y, z, alpha, elemScale, size);
            else deadActor(f, s, d, actor, pose, x, y, z, alpha, elemScale, size);
        }
    }

    private static void aliveActor(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayBoardModel.Actor actor,
                                   ReplayTrackSampler.Pose pose, float x, float y, float z, double alpha, float elemScale, float size) {
        ReplayBoardModel model = f.model;
        VertexConsumer glow = f.glow();
        double moving = Math.min(1, pose.speed() / 0.12);
        float bob = (float) (Math.abs(Math.sin(f.anim * 9.0 + actor.index * 1.7)) * size * 0.15 * moving);
        float hy = y + ReplayActorSizing.centerOffset(size) + bob;
        float yaw = (model.rotated ? 90f : 0f) - pose.yaw();
        double revive = pose.revived() ? ReplayStagePlan.pulse(f.replayTick - pose.stateSince(),
                ReplayStagePlan.effectWindow(1.0, model.rate)) : 0;
        float lift = (float) (revive * size * 0.67);
        int tint = 0xFFFFFFFF;
        hy += lift;
        head(f, d, actor, x, hy, z, yaw, size, tint, alpha);
        if (revive > 0) {
            ReplayDraw.column(glow, d, x, y, z, 0.45f, 0.06f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.7 * revive * alpha),
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0));
            ReplayDraw.ring(glow, d, x, y + 0.002f, z, (float) (0.08 + (1 - revive) * 0.18), 0.02f, 24,
                    ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, revive * alpha), ReplayPalette.alpha(ReplayPalette.BRASS, 0));
        }
        if (pose.has(LastMatchReplay.DISGUISED)) {
            float wob = (float) Math.sin(f.anim * 3 + actor.index) * size * 0.1f;
            head(f, d, actor, x + size * 0.375f + wob, hy + size * 0.21f, z - size * 0.25f, yaw + 12,
                    size * 1.05f, 0xFFB46CFF, alpha * 0.38);
            ReplayDraw.ring(glow, d, x, y + 0.003f, z, size * 0.79f, size * 0.083f, 20, ReplayPalette.alpha(ReplayPalette.DISGUISE, 0.7 * alpha),
                    ReplayPalette.alpha(ReplayPalette.DISGUISE, 0.1 * alpha));
            Vector3f badge = d.transformPosition(x + size * 0.6f, hy + size * 0.75f, z, new Vector3f());
            ReplayDraw.billboard(glow, badge, f.right, f.up, size * 0.17f * elemScale, size * 0.17f * elemScale,
                    ReplayPalette.alpha(ReplayPalette.DISGUISE, 0.9 * alpha));
        }
        boolean psycho = pose.has(LastMatchReplay.PSYCHO) || model.psychoAt(actor.p.id(), f.replayTick);
        if (psycho) {
            Vector3f c = d.transformPosition(x, hy, z, new Vector3f());
            for (int i = 0; i < 6; i++) {
                double ph = f.anim * 6 + i * 1.3 + actor.index;
                float ox = (float) Math.cos(i * 1.05 + f.anim) * size * 0.55f;
                float oz = (float) Math.sin(i * 1.05 + f.anim) * size * 0.55f;
                float h = (float) (0.42 + 0.33 * Math.abs(Math.sin(ph))) * size;
                Vector3f p = d.transformPosition(x + ox, hy - size * 0.3f + h * 0.5f, z + oz, new Vector3f());
                ReplayDraw.billboard(glow, p, f.right, f.up, size * 0.15f * elemScale, h * elemScale,
                        ReplayPalette.alpha(ReplayPalette.PSYCHO, 0.45 * alpha));
            }
            ReplayDraw.billboard(glow, c, f.right, f.up, size * 0.95f * elemScale, size * 0.95f * elemScale, ReplayPalette.alpha(ReplayPalette.PSYCHO, 0.18 * alpha));
        }
        if (s.detail() && !f.video) {
            Vector3f top = d.transformPosition(x, hy + size * 0.9f, z, new Vector3f());
            float dist = top.length();
            float distScale = Math.max(1f, s.scale());
            if (pose.has(LastMatchReplay.ASLEEP)) {
                f.billboardText(new Vector3f(top).add(0, 0.03f * elemScale, 0), Component.literal("z Z"), 0.0035f * elemScale,
                        ReplayPalette.alpha(ReplayPalette.PASSENGER, alpha));
            } else if (dist < 6.5f * distScale && f.stage.history() < 0.5) {
                double near = 1 - ReplayStagePlan.smoothstep(4.5 * distScale, 6.5 * distScale, dist);
                f.billboardText(top, actor.name, 0.0032f * elemScale, ReplayPalette.alpha(actor.color, near * alpha));
            }
        }
    }

    private static void deadActor(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayBoardModel.Actor actor,
                                  ReplayTrackSampler.Pose pose, float x, float y, float z, double alpha, float elemScale, float size) {
        ReplayBoardModel model = f.model;
        double window = ReplayStagePlan.effectWindow(0.9, model.rate);
        double age = f.replayTick - pose.stateSince();
        float yaw = (model.rotated ? 90f : 0f) - pose.yaw();
        VertexConsumer glow = f.glow();
        if (age >= 0 && age < window) {
            double k = ReplayStagePlan.easeOutCubic(age / window);
            float drop = (float) (k * size * 0.58);
            float fallen = (float) (size * (1 - 0.35 * k));
            float center = y + ReplayActorSizing.centerOffset(size) - drop;
            head(f, d, actor, x, center, z, yaw + (float) (k * 70), fallen, 0xFFFF8080, alpha * (1 - 0.6 * k));
            Vector3f c = d.transformPosition(x, center, z, new Vector3f());
            if (s.detail()) {
                ReplayDraw.star(glow, c, f.right, f.up, (float) (0.42 + k) * size * elemScale, 8, (float) (k * 2),
                        ReplayPalette.alpha(ReplayPalette.RUBY, (1 - k) * alpha), ReplayPalette.alpha(ReplayPalette.RUBY, 0));
            }
            return;
        }
        // tombstone
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
        // translucent ghost
        float floatY = (float) (1.15 + Math.sin(f.anim * 1.6 + actor.index) * 0.1) * size;
        head(f, d, actor, x, y + floatY, z, yaw, size * 0.8f, 0xFFB9C3D9, alpha * 0.32);
    }

    /** Two-layer skin head (8x8x8 + 1.125x hat) centred at a diorama-frame point. */
    static void head(ReplayFrame f, Matrix4f frame, ReplayBoardModel.Actor actor, float x, float y, float z, float yawDeg,
                     float size, int tint, double alpha) {
        if (alpha <= 0.02) return;
        ResourceLocation skin = ReplayHeadSkins.texture(actor.p.id());
        Matrix4f m = new Matrix4f(frame).translate(x, y, z).rotateY((float) Math.toRadians(yawDeg)).scale(size);
        int color = ReplayPalette.alpha(tint, alpha);
        f.model(m, stack -> drawHead(f, stack, skin, color));
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

    private static void trails(ReplayFrame f, Matrix4f d, ReplayBoardModel.Actor actor, double t, double alpha,
                               double history, boolean review, float elemScale, ReplayTrackSampler.Pose live,
                               float[] livePosition, Vector3f tmp, boolean detail) {
        ReplayTrackSampler track = actor.track;
        int last = track.indexAt(t);
        if (last < 0) return;
        ReplayBoardModel model = f.model;
        VertexConsumer glow = f.glow();
        double cometStart = t - model.comet;
        double baseA = alpha * (0.13 + 0.32 * history) * (review ? 1.2 : 1);
        int base = actor.color;
        boolean appendLive = live.state() == ReplayTrackSampler.State.ALIVE;
        int recent = Math.max(0, track.indexAt(cometStart));
        int first = detail ? 0 : recent;
        int stride = f.video ? 1 : Math.max(1, (recent + 95) / 96);
        int n = 0;
        int cap = widths.length - 1;
        for (int i = first; i <= last; i++) {
            boolean startsSegment = i == first || !track.connects(i);
            if (startsSegment) {
                if (n >= 2) emit(glow, n);
                n = 0;
            }
            if (!startsSegment && i < recent && i % stride != 0 && track.connects(i + 1)) continue;
            if (n >= cap) {
                emit(glow, n);
                int o = (n - 1) * 3;
                points[0] = points[o]; points[1] = points[o + 1]; points[2] = points[o + 2];
                widths[0] = widths[n - 1]; colors[0] = colors[n - 1];
                n = 1;
            }
            d.transformPosition(actor.xs[i], actor.ys[i] + 0.012f, actor.zs[i], tmp);
            put(n++, tmp, track.tick(i), cometStart, t, baseA, base, alpha, elemScale);
        }
        if (appendLive && n >= 1) {
            d.transformPosition(livePosition[0], livePosition[1] + 0.012f, livePosition[2], tmp);
            put(n++, tmp, t, cometStart, t, baseA, base, alpha, elemScale);
        }
        if (n >= 2) emit(glow, n);
    }

    private static void put(int index, Vector3f p, double tick, double cometStart, double t, double baseA, int color,
                            double alpha, float elemScale) {
        int o = index * 3;
        points[o] = p.x; points[o + 1] = p.y; points[o + 2] = p.z;
        double comet = tick >= cometStart ? (tick - cometStart) / Math.max(1, t - cometStart) : 0;
        double a = baseA + comet * comet * 0.85 * alpha;
        int c = ReplayPalette.lerp(ReplayPalette.scale(color, 0.85), ReplayPalette.lerp(color, 0xFFFFFFFF, 0.35), comet);
        colors[index] = ReplayPalette.alpha(c, a);
        widths[index] = (float) (0.010 + 0.022 * comet) * elemScale;
    }

    private static void emit(VertexConsumer glow, int n) {
        ReplayDraw.ribbon(glow, points, n, widths, colors);
    }
}
