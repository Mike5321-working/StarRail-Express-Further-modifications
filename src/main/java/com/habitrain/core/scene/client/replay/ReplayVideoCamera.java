package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Virtual camera crew for the exported video. Every shot is a rig (target, yaw, pitch, distance, lens)
 * evaluated from the script; rigs are blended in spherical space so cuts become fast, eased camera moves.
 *
 * <p>Shot grammar: a descending crane over the title, a drifting three-quarter overview that tracks the
 * crowd during hyper-lapse, a lead-in that follows the attacker into each highlight, a bullet-time orbit
 * with a dolly punch on impact and a slow pull-back, a god's-eye rise for the overview, a low hero angle
 * for the victory, an off-centre portrait orbit for the MVP and a wide resolve for the credits.</p>
 */
final class ReplayVideoCamera {
    static final float NEAR = 0.05f;
    /** Clearance between the near plane and the tallest emitted vertex while above the map footprint. */
    static final float SAFE_GAP = 0.32f;
    static final float ASPECT = 16f / 9f;

    record Shot(Vector3f eye, Vector3f look, Quaternionf rotation, Matrix4f view, Matrix4f projection,
                Map<UUID, Double> focusedActors) {
        Shot(Vector3f eye, Vector3f look, Quaternionf rotation, Matrix4f view, Matrix4f projection) {
            this(eye, look, rotation, view, projection, Map.of());
        }

        double emphasis(UUID id) { return focusedActors.getOrDefault(id, 0.0); }
        /** Board-frame point to pixel coordinates (top-left origin); z &lt; 0 when behind the camera. */
        Vector3f toScreen(Vector3f board, float width, float height) {
            var clip = new org.joml.Vector4f(board.x - eye.x, board.y - eye.y, board.z - eye.z, 1);
            view.transform(clip);
            projection.transform(clip);
            if (clip.w <= 1.0e-4f) return new Vector3f(-1, -1, -1);
            return new Vector3f((clip.x / clip.w * 0.5f + 0.5f) * width, (0.5f - clip.y / clip.w * 0.5f) * height, 1);
        }
    }

    /** Positions in the board frame (diorama origin at the tray centre, map base at {@link ReplayBoardSite#MAP_BASE}). */
    interface Subjects {
        Vector3f centroid(double tick);
        @Nullable Vector3f actor(@Nullable UUID id, double tick);
        Vector3f event(LastMatchReplay.Node node, boolean to);
        @Nullable Vector3f mvp();
        default @Nullable UUID mvpId() { return null; }
        Vector3f winners();
    }

    record Stage(float half, float mapW, float mapD, float mapH, float geometryTop, float headSize) {}

    private static final class Rig {
        double tx, ty, tz, yaw, pitch, dist, fov = 34, shift, roll;

        Rig set(double tx, double ty, double tz, double yaw, double pitch, double dist, double fov, double shift) {
            this.tx = tx; this.ty = ty; this.tz = tz; this.yaw = yaw; this.pitch = pitch; this.dist = dist;
            this.fov = fov; this.shift = shift; this.roll = 0;
            return this;
        }

        void mix(Rig o, double k) {
            if (k <= 0) return;
            tx += (o.tx - tx) * k; ty += (o.ty - ty) * k; tz += (o.tz - tz) * k;
            double dy = Math.IEEEremainder(o.yaw - yaw, Math.PI * 2);
            yaw += dy * k;
            pitch += (o.pitch - pitch) * k;
            dist = Math.exp(Math.log(dist) + (Math.log(o.dist) - Math.log(dist)) * k);
            fov += (o.fov - fov) * k; shift += (o.shift - shift) * k; roll += (o.roll - roll) * k;
        }
    }

    private ReplayVideoCamera() {}

    static float fitDistance(float half) {
        double hf = Math.atan(Math.tan(Math.toRadians(34) / 2) * ASPECT);
        return (float) ((half + 0.35) / Math.tan(hf) + half * 0.35);
    }

    static Shot at(ReplayVideoScript script, Subjects subjects, Stage stage, double time) {
        var sample = script.sample(time);
        double k = sample.actProgress();
        float fit = fitDistance(stage.half());
        double base = ReplayBoardSite.MAP_BASE;
        Rig rig = new Rig();
        Map<UUID, Double> focusedActors = new HashMap<>();
        switch (sample.act()) {
            case TITLE -> {
                double e = ReplayStagePlan.easeInOutQuart(k), o = ReplayStagePlan.easeOutCubic(k);
                rig.set(0, base + stage.mapH() * 0.35, 0, lerp(-1.4, -0.55, e), lerp(1.02, 0.58, e),
                        fit * lerp(2.15, 0.95, o), 34, 0);
                rig.roll = lerp(-0.06, 0, e);
            }
            case ACTION -> {
                overview(rig, script, subjects, stage, time, fit);
                for (var beat : script.beats()) {
                    double w = ReplayVideoScript.influence(beat, time, 0.8, 0.95);
                    if (w <= 0) continue;
                    Rig close = new Rig();
                    beat(close, beat, subjects, stage, time, overviewYaw(script, beat.approach()), sample.replayTick());
                    // influence() is already smoothstep-eased; easing again would turn the move into a whip.
                    rig.mix(close, w);
                    focusedActors.replaceAll((id, weight) -> weight * (1 - w));
                    var node = beat.node();
                    if (node.actor() != null) focusedActors.merge(node.actor(), w, Double::sum);
                    if (node.target() != null && !node.target().equals(node.actor())) focusedActors.merge(node.target(), w, Double::sum);
                }
            }
            case REVIEW -> {
                double e = ReplayStagePlan.easeInOutQuart(k);
                rig.set(0, base + stage.mapH() * 0.1, 0, lerp(-0.55, 0.42, e), lerp(0.6, 1.14, e), fit * lerp(0.92, 1.06, k), 34, 0);
            }
            case VICTORY -> {
                var w = subjects.winners();
                double e = ReplayStagePlan.easeInOutQuart(k);
                rig.set(w.x * 0.45, base + stage.mapH() * 0.32, w.z * 0.45, lerp(0.55, -0.15, e), lerp(0.4, 0.3, k),
                        fit * lerp(0.9, 0.78, ReplayStagePlan.easeOutCubic(k)), 32, 0);
            }
            case MVP -> {
                Vector3f m = subjects.mvp();
                if (m == null) {
                    rig.set(0, base + stage.mapH() * 0.3, 0, -0.6 + 0.6 * k, 0.5, fit * 0.7, 32, -0.3);
                } else {
                    rig.set(m.x, m.y + ReplayActorSizing.centerOffset(stage.headSize()) * 1.38, m.z, -0.65 + 0.8 * k, lerp(0.52, 0.4, k),
                            lerp(1.75, 1.15, ReplayStagePlan.easeOutCubic(k)), 30, -0.34);
                    UUID id = subjects.mvpId();
                    if (id != null) focusedActors.put(id, ReplayStagePlan.smoothstep(0, 0.12, k));
                }
            }
            case OUTRO -> rig.set(0, base + stage.mapH() * 0.2, 0, 0.35 - 0.45 * k, 0.8, fit * lerp(1.0, 1.28, k), 34, -0.42);
        }
        shake(rig, script, time);
        return build(rig, stage, Map.copyOf(focusedActors));
    }

    private static void overview(Rig rig, ReplayVideoScript script, Subjects subjects, Stage stage, double time, float fit) {
        // Track the crowd, averaged over ~2.4 s of video so hyper-lapse never jitters the frame.
        float cx = 0, cz = 0, weight = 0;
        for (int i = -3; i <= 3; i++) {
            var s = script.sample(Math.clamp(time + i * 0.4, script.actStart(ReplayVideoScript.Act.ACTION),
                    script.actStart(ReplayVideoScript.Act.REVIEW) - 1.0e-3));
            var c = subjects.centroid(s.replayTick());
            float w = 4 - Math.abs(i);
            cx += c.x * w; cz += c.z * w; weight += w;
        }
        cx /= weight; cz /= weight;
        double t = time - script.actStart(ReplayVideoScript.Act.ACTION);
        rig.set(cx * 0.6, ReplayBoardSite.MAP_BASE + stage.mapH() * 0.25, cz * 0.4,
                overviewYaw(script, time), 0.6 + 0.04 * Math.sin(t * 0.09), fit * 0.86, 34, 0);
    }

    private static double overviewYaw(ReplayVideoScript script, double time) {
        return -0.55 + 0.22 * Math.sin((time - script.actStart(ReplayVideoScript.Act.ACTION)) * 0.13);
    }

    /** {@code referenceYaw} is fixed per beat, so the chosen side of the action line never flips mid-move. */
    private static void beat(Rig rig, ReplayVideoScript.Beat beat, Subjects subjects, Stage stage, double time, double referenceYaw,
                             double tick) {
        var node = beat.node();
        Vector3f from = subjects.event(node, false), to = subjects.event(node, true);
        // Follow the attacker's live position while the camera travels in.
        Vector3f live = beat.type() == ReplayVideoScript.BeatType.KILL && time < beat.impact() ? subjects.actor(node.actor(), tick) : null;
        float sx = to.x - from.x, sz = to.z - from.z;
        float len = (float) Math.sqrt(sx * sx + sz * sz);
        Vector3f mid = new Vector3f(from).add(to).mul(0.5f);
        mid.y = Math.max(from.y, to.y) + ReplayActorSizing.centerOffset(stage.headSize()) * 1.38f;
        double yaw0;
        if (len < 0.05f) yaw0 = -0.45;
        else {
            double px = sz, pz = -sx;
            if (pz < 0) { px = -px; pz = -pz; }
            yaw0 = Math.atan2(px, pz) + 0.3;
        }
        // Both sides of the action line are valid; take the one nearer the current framing.
        if (Math.abs(Math.IEEEremainder(yaw0 + Math.PI - referenceYaw, Math.PI * 2))
                < Math.abs(Math.IEEEremainder(yaw0 - referenceYaw, Math.PI * 2)) - 0.6) yaw0 += Math.PI;
        double u = beat.local(time);
        double freeze = beat.release() - beat.impact();
        double orbit = 0.1 * u + 0.42 * ReplayStagePlan.smoothstep(0, Math.max(0.05, freeze), u);
        double punch = u < 0 ? 0 : Math.exp(-u * 6) * (1 - Math.exp(-u * 35));
        double pull = ReplayStagePlan.smoothstep(beat.release(), beat.end(), time);
        double dist = Math.clamp(0.85 + 1.15 * len, 0.85, 3.4) * (beat.type() == ReplayVideoScript.BeatType.EXPLOSION ? 1.35 : 1.0);
        dist *= 1 - 0.15 * punch + 0.2 * pull;
        // During the approach the frame leads from the attacker towards the point of impact.
        double lead = ReplayStagePlan.smoothstep(beat.approach(), beat.impact(), time);
        Vector3f start = live != null ? new Vector3f(live.x, mid.y, live.z) : new Vector3f(from.x, mid.y, from.z);
        Vector3f target = start.lerp(mid, (float) lead);
        rig.set(target.x, target.y, target.z, yaw0 + orbit, 0.5 + 0.08 * pull, dist, 33 * (1 - 0.07 * punch), 0);
    }

    private static void shake(Rig rig, ReplayVideoScript script, double time) {
        double env = 0;
        for (var beat : script.beats()) {
            double u = beat.local(time);
            if (u < 0 || u > 1.5) continue;
            double strength = switch (beat.type()) {
                case EXPLOSION -> 1.7;
                case KILL -> 1.0 + 0.25 * (beat.streak() - 1);
                case REVIVAL -> 0.35;
                default -> 0.5;
            };
            env += strength * ReplayVideoScript.impulse(u, 5.5);
        }
        if (env <= 1.0e-3) return;
        double amp = env * rig.dist * 0.012;
        rig.tx += amp * noise(time, 1.3);
        rig.ty += amp * noise(time, 4.1) * 0.6;
        rig.tz += amp * noise(time, 7.7);
        rig.roll += env * 0.012 * noise(time, 2.9);
    }

    static double noise(double t, double seed) {
        return Math.sin(t * 23.1 + seed) * 0.5 + Math.sin(t * 37.7 + seed * 2.1) * 0.3 + Math.sin(t * 61.3 + seed * 3.7) * 0.2;
    }

    static float ceiling(float geometryTop) {
        return Math.max(ReplayBoardSite.FRAME_TOP, ReplayBoardSite.MAP_BASE + geometryTop);
    }

    private static Shot build(Rig rig, Stage stage, Map<UUID, Double> focusedActors) {
        double cp = Math.cos(rig.pitch);
        var target = new Vector3f((float) rig.tx, (float) rig.ty, (float) rig.tz);
        var eye = new Vector3f((float) (rig.tx + rig.dist * cp * Math.sin(rig.yaw)), (float) (rig.ty + rig.dist * Math.sin(rig.pitch)),
                (float) (rig.tz + rig.dist * cp * Math.cos(rig.yaw)));
        // Above the map footprint the near plane must clear the tallest vertex; beside it only the tray rim.
        float ex = stage.mapW() / 2 + 0.25f, ez = stage.mapD() / 2 + 0.25f;
        float outside = Math.max(Math.abs(eye.x) - ex, Math.abs(eye.z) - ez);
        double inside = 1 - ReplayStagePlan.smoothstep(0, 0.5, outside);
        float minY = (float) (ReplayBoardSite.FRAME_TOP + 0.1 + inside * (ceiling(stage.geometryTop()) + SAFE_GAP - ReplayBoardSite.FRAME_TOP - 0.1));
        eye.y = Math.max(eye.y, minY);
        var direction = new Vector3f(target).sub(eye);
        if (direction.lengthSquared() < 1.0e-8f) direction.set(0, -1, -1e-3f);
        direction.normalize();
        var up = new Vector3f(0, 1, 0);
        if (Math.abs(rig.roll) > 1.0e-5) up.rotateAxis((float) rig.roll, direction.x, direction.y, direction.z);
        var view = new Matrix4f().lookAlong(direction, up);
        var rotation = view.getUnnormalizedRotation(new Quaternionf()).conjugate().normalize();
        var projection = new Matrix4f().translation((float) rig.shift, 0, 0)
                .mul(new Matrix4f().perspective((float) Math.toRadians(rig.fov), ASPECT, NEAR, Math.max(128, eye.length() * 5)));
        return new Shot(eye, direction, rotation, view, projection, focusedActors);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
