package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayProjection;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Deterministic event close-ups, timed in presentation ticks rather than compressed match time. */
public final class ReplayFocusPlan {
    public static final double MAX_ZOOM = 2.4;
    public static final double ATTACK = 6, HOLD = 16, RELEASE = 14;
    public static final double LENGTH = HOLD + RELEASE;
    public static final Pose WIDE = new Pose(1, 0, 0, 0);
    public static final Sample UNFOCUSED = new Sample(WIDE, Highlight.NONE, Highlight.NONE);

    /** Offset is in the unscaled diorama frame. The tray, plaque and honors screen never move. */
    public record Pose(double zoom, double x, double y, double z) {
        public Matrix4f apply(Matrix4f base) {
            return new Matrix4f(base).translate((float) x, (float) y, (float) z).scale((float) zoom);
        }
    }

    public record Highlight(@Nullable LastMatchReplay.Node event, double weight) {
        private static final Highlight NONE = new Highlight(null, 0);

        double amount(UUID id) {
            return event != null && (id.equals(event.actor()) || id.equals(event.target())) ? weight : 0;
        }

        Highlight scaled(double factor) { return new Highlight(event, weight * factor); }
    }

    public record Sample(Pose pose, Highlight outgoing, Highlight current) {
        public double emphasis(UUID id) { return outgoing.amount(id) + current.amount(id); }
    }

    private record Cue(double tick, int priority, Pose incoming, Pose target, LastMatchReplay.Node event,
                       Highlight previous) {
        Pose at(double tick) {
            double age = tick - this.tick;
            if (age < 0) return incoming;
            if (age >= LENGTH) return WIDE;
            if (age < ATTACK) {
                // A fast punch, ~12% overshoot, then recoil into the held framing.
                double zoom = impact(age / ATTACK);
                return mix(incoming, target, zoom, ReplayStagePlan.easeOutCubic(age / ATTACK));
            }
            if (age <= HOLD) return target;
            double out = ReplayStagePlan.easeInOutQuart((age - HOLD) / RELEASE);
            return mix(target, WIDE, out, out);
        }

        Highlight highlightAt(double tick) {
            double age = tick - this.tick;
            return new Highlight(event, age < ATTACK ? attackWeight(age)
                    : 1 - ReplayStagePlan.easeInOutQuart((age - HOLD) / RELEASE));
        }

        private static double attackWeight(double age) {
            return ReplayStagePlan.smoothstep(0, ATTACK, age);
        }
    }

    private final List<Cue> cues;
    private final List<LinearCue> linearCues;
    private final double actionLength;

    private record LinearCue(int matchTick, int priority, Pose target, LastMatchReplay.Node event) {}

    public ReplayFocusPlan(LastMatchReplay replay, ReplayTimeline timeline) {
        actionLength = timeline.cycleTicks() - ReplayTimeline.INTRO - ReplayTimeline.REVIEW - ReplayTimeline.HONORS;
        ReplayProjection.Layout layout = ReplayProjection.layout(replay);
        List<Cue> built = new ArrayList<>();
        List<LinearCue> linearBuilt = new ArrayList<>();
        if (layout != null) {
            List<LastMatchReplay.Node> sorted = replay.events().stream()
                    .filter(e -> priority(e) > 0 && (e.from() != null || e.to() != null))
                    .sorted(Comparator.comparingInt(LastMatchReplay.Node::tick)
                            .thenComparing(Comparator.comparingInt(ReplayFocusPlan::priority).reversed())).toList();
            for (var event : sorted) {
                double at = timeline.actionTickAt(event.tick());
                Cue previous = built.isEmpty() ? null : built.getLast();
                int priority = priority(event);
                Pose targetPose = target(layout, event, priority);
                if (priority >= 3) {
                    LinearCue prevLin = linearBuilt.isEmpty() ? null : linearBuilt.getLast();
                    if (prevLin == null || event.tick() - prevLin.matchTick >= 8
                            || priority > prevLin.priority) {
                        if (prevLin != null && event.tick() - prevLin.matchTick < 8 && priority > prevLin.priority) {
                            linearBuilt.set(linearBuilt.size() - 1, new LinearCue(event.tick(), priority, targetPose, event));
                        } else {
                            linearBuilt.add(new LinearCue(event.tick(), priority, targetPose, event));
                        }
                    }
                }
                // Simultaneous events form one shot. Incidental actions cannot steal a kill close-up.
                if (previous != null && (at - previous.tick < ATTACK
                        || at - previous.tick < HOLD && priority < previous.priority)) continue;
                Pose incoming = previous == null ? WIDE : previous.at(at);
                built.add(new Cue(at, priority, incoming, targetPose, event,
                        previous == null ? Highlight.NONE : previous.highlightAt(at)));
            }
        }
        cues = List.copyOf(built);
        linearCues = List.copyOf(linearBuilt);
    }

    /**
     * Evaluates optional kill/highlight close-ups on the linear match-tick clock.
     *
     * <p>When {@code killCloseupEnabled} is {@code false} (the default for the interactive replay table),
     * this always returns {@link #UNFOCUSED} so the diorama never jerks or obstructs spectators.</p>
     */
    public Sample sampleLinear(double matchTick, double playbackSpeed, boolean killCloseupEnabled) {
        if (!killCloseupEnabled || linearCues.isEmpty() || !Double.isFinite(matchTick)) return UNFOCUSED;
        double speed = Math.clamp(Double.isFinite(playbackSpeed) ? playbackSpeed : 1.0, 0.5, 4.0);
        double attack = ATTACK * speed;
        double hold = HOLD * speed;
        double release = RELEASE * speed;
        double length = hold + release;
        LinearCue selected = null;
        for (LinearCue cue : linearCues) {
            if (cue.matchTick > matchTick) break;
            selected = cue;
        }
        if (selected == null) return UNFOCUSED;
        double age = matchTick - selected.matchTick;
        if (age < 0 || age >= length) return UNFOCUSED;
        Pose pose;
        double weight;
        if (age < attack) {
            double u = age / attack;
            pose = mix(WIDE, selected.target, impact(u), ReplayStagePlan.easeOutCubic(u));
            weight = ReplayStagePlan.smoothstep(0, attack, age);
        } else if (age <= hold) {
            pose = selected.target;
            weight = 1.0;
        } else {
            double out = ReplayStagePlan.easeInOutQuart((age - hold) / release);
            pose = mix(selected.target, WIDE, out, out);
            weight = 1.0 - out;
        }
        return new Sample(pose, Highlight.NONE, new Highlight(selected.event, weight));
    }

    /**
     * Counter-rotation angle (degrees about +Y) that aligns a followed player's facing direction
     * toward {@code -Z} (away from the table's front edge), producing a true over-the-shoulder view
     * from the front of the replay table.
     */
    public static float followCounterYawDeg(float playerYawDeg, boolean mapRotated) {
        return 180.0f + playerYawDeg - (mapRotated ? 90.0f : 0.0f);
    }

    /** Shortest signed angular delta in {@code (-180, 180]} degrees. */
    public static float shortestAngleDeltaDeg(float fromDeg, float toDeg) {
        float diff = (toDeg - fromDeg) % 360.0f;
        if (diff > 180.0f) diff -= 360.0f;
        if (diff <= -180.0f) diff += 360.0f;
        return diff;
    }

    /**
     * Builds the diorama transform anchored on a followed player, smoothly counter-rotated so their
     * forward heading points along {@code -Z} into the tray.
     */
    public static Matrix4f applyFollowTransform(Matrix4f base, float playerX, float playerY, float playerZ,
                                                float counterYawDeg, float zoom, float backShiftZ) {
        float z = Math.max(1.0f, zoom);
        return new Matrix4f(base)
                .translate(0f, -playerY * (z - 1f), backShiftZ)
                .rotateY((float) Math.toRadians(counterYawDeg))
                .scale(z)
                .translate(-playerX, 0f, -playerZ);
    }

    public Pose at(ReplayTimeline.Frame frame) {
        return sample(frame).pose();
    }

    public Sample sample(@Nullable ReplayTimeline.Frame frame) {
        if (frame == null || frame.phase() != ReplayTimeline.Phase.PLAY) return UNFOCUSED;
        double tick = frame.progress() * actionLength;
        Cue selected = null;
        for (Cue cue : cues) {
            if (cue.tick > tick) break;
            selected = cue;
        }
        if (selected == null || tick - selected.tick >= LENGTH) return UNFOCUSED;
        Pose pose = selected.at(tick);
        // Even a last-tick kill must hand the complete map back to the review act without a cut.
        double exit = ReplayStagePlan.easeInOutQuart(Math.max(0, (tick - (actionLength - RELEASE)) / RELEASE));
        double attack = Cue.attackWeight(tick - selected.tick);
        return new Sample(mix(pose, WIDE, exit, exit), selected.previous.scaled((1 - attack) * (1 - exit)),
                selected.highlightAt(tick).scaled(1 - exit));
    }

    public static int priority(LastMatchReplay.Node event) {
        return switch (event.kind()) {
            case KILL -> 4;
            case BOMB_DETONATE, GRENADE_EXPLOSION, PLAYER_REVIVAL, BOMB_DEFUSE -> 3;
            case GUNSHOT -> (event.flags() & (LastMatchReplay.HIT | LastMatchReplay.BACKFIRE)) != 0 ? 3 : 1;
            case POISON, SHIELD_BROKEN, BOMB_PLANT, SKILL_RELEASE, TRAP_TRIGGERED, ROPE_PULL -> 2;
            case GRENADE_THROWN, DISGUISE, CHANGE_ROLE, ITEM_USED, LOCKPICK_ATTEMPT, DOOR_PRY, DOOR_SEAL -> 1;
            default -> 0;
        };
    }

    private static Pose target(ReplayProjection.Layout layout, LastMatchReplay.Node event, int priority) {
        var a = layout.project(event.from() == null ? event.to() : event.from());
        var b = layout.project(event.to() == null ? event.from() : event.to());
        double x = (a.x() + b.x()) / 2, z = (a.z() + b.z()) / 2;
        // Keep attacker and target in view, including ranged kills across several carriages.
        double zoom = Math.min(priority == 4 ? MAX_ZOOM : priority == 3 ? 2.1 : priority == 2 ? 1.8 : 1.55,
                Math.min(5.6 / (Math.abs(a.x() - b.x()) + 0.65), 5.6 / (Math.abs(a.z() - b.z()) + 0.65)));
        zoom = Math.max(1, zoom);
        if (layout.source().sizeZ() > layout.source().sizeX()) {
            double oldX = x;
            x = z;
            z = -oldX;
        }
        double y = (a.y() + b.y()) / 2;
        return new Pose(zoom, -x * zoom, -y * (zoom - 1), -z * zoom);
    }

    public static double impact(double t) {
        t = ReplayStagePlan.clamp01(t) - 1;
        return 1 + 2.9 * t * t * t + 1.9 * t * t;
    }

    private static Pose mix(Pose a, Pose b, double zoom, double pan) {
        return new Pose(a.zoom + (b.zoom - a.zoom) * zoom, a.x + (b.x - a.x) * pan,
                a.y + (b.y - a.y) * pan, a.z + (b.z - a.z) * pan);
    }
}
