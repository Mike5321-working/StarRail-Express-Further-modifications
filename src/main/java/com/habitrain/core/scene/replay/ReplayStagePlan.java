package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.ReplayTimeline;

/**
 * Pure presentation plan for the four-act loop. Every value is a deterministic function of the
 * synchronized {@link ReplayTimeline.Frame}, so all clients that agree on the world clock agree on
 * the choreography too. The renderer only maps these scalars onto geometry.
 */
public final class ReplayStagePlan {
    private ReplayStagePlan() {}

    /** Distance (blocks) at which the board starts to fade, and where it is no longer submitted. */
    public static final double FADE_START = 48.0, FADE_END = 64.0;
    /** Below this distance the board submits fine detail (name plates, briefings, rivets). */
    public static final double DETAIL_DISTANCE = 24.0;
    /** Transition durations (ticks) for the interactive linear board session. */
    public static final int LINEAR_INTRO_TICKS = 36;
    public static final int LINEAR_EXIT_TICKS = 24;
    /** Minimum headroom (board blocks) above the highest diorama vertex for floating overlays/feeds. */
    public static final float DEFAULT_FEED_CLEARANCE = 0.34f;

    /**
     * @param trayGlow    brass lamp strip brightness 0..1
     * @param scan        fraction of the diorama revealed along its long axis (1 = all)
     * @param plaqueFlip  flip-board title reveal 0..1
     * @param actors      avatar / trail visibility 0..1
     * @param reviewWave  position 0..1 of the golden settlement wave, or -1 when absent
     * @param history     how strongly the full-match history (all trails, all nodes) is shown 0..1
     * @param mapSink     honors-phase sink/dim of the diorama 0..1
     * @param honors      honors screen unfold 0..1
     * @param honorsTime  seconds since the honors act began (for staggered rows)
     */
    public record Stage(double trayGlow, double scan, double plaqueFlip, double actors, double reviewWave,
                        double history, double mapSink, double honors, double honorsTime) {}

    public record Handoff(double idle, double idlePlaque, double pulse, double pulseRadius) {}

    public static final Handoff IDLE = new Handoff(1, 1, 0, 0);
    public static final Handoff REPLAY = new Handoff(0, 0, 0, 0);

    public static Handoff boardHandoff(ReplayTimeline.Frame frame) {
        double progress = clamp01(frame.progress());
        return switch (frame.phase()) {
            case BUILD -> new Handoff(1 - smoothstep(0, 0.36, progress), 1 - smoothstep(0.12, 0.54, progress),
                    smoothstep(0.08, 0.32, progress) * (1 - smoothstep(0.32, 0.66, progress)),
                    smoothstep(0.20, 0.66, progress));
            case HONORS -> new Handoff(smoothstep(0.89, 1, progress), smoothstep(0.84, 0.98, progress),
                    smoothstep(0.79, 0.91, progress) * (1 - smoothstep(0.91, 0.995, progress)),
                    1 - smoothstep(0.79, 0.95, progress));
            default -> REPLAY;
        };
    }

    public static Stage boardStage(ReplayTimeline.Frame frame) {
        double progress = clamp01(frame.progress());
        if (frame.phase() == ReplayTimeline.Phase.BUILD) {
            Stage base = stage(frame);
            double lamp = 0.75 + 0.25 * smoothstep(0, 0.50, progress);
            double scan = smoothstep(0.25, 0.94, progress);
            return new Stage(lamp, scan, base.plaqueFlip(), base.actors(), -1, 0, 0, 0, 0);
        }
        if (frame.phase() == ReplayTimeline.Phase.HONORS && progress > 0.75) {
            double fold = smoothstep(0.75, 0.89, progress);
            double retract = smoothstep(0.79, 0.94, progress);
            double sink = 0.65 + 0.35 * retract;
            return new Stage(1 - 0.25 * smoothstep(0.82, 1, progress), 1 - retract, 1,
                    0.15 * (1 - retract), -1, 0, sink, 1 - fold, progress * ReplayTimeline.HONORS / 20.0);
        }
        Stage base = stage(frame);
        if (frame.phase() != ReplayTimeline.Phase.HONORS) return base;
        return new Stage(base.trayGlow(), base.scan(), base.plaqueFlip(), base.actors(), base.reviewWave(),
                base.history(), base.mapSink() * 0.65, base.honors(), base.honorsTime());
    }

    public static Stage stage(ReplayTimeline.Frame frame) {
        double p = clamp01(frame.progress());
        return switch (frame.phase()) {
            case BUILD -> new Stage(easeOutCubic(clamp01(p / 0.4)), easeInOutQuart(clamp01((p - 0.12) / 0.82)),
                    easeOutCubic(clamp01((p - 0.45) / 0.5)), easeOutCubic(clamp01((p - 0.85) / 0.15)) * 0.6,
                    -1, 0, 0, 0, 0);
            case PLAY -> new Stage(1, 1, 1, Math.max(0.6, easeOutCubic(clamp01(p / 0.03))), -1, 0, 0, 0, 0);
            case REVIEW -> new Stage(1, 1, 1, 1, p < 0.62 ? easeInOutQuart(p / 0.62) : -1,
                    easeOutCubic(clamp01((p - 0.08) / 0.5)), 0, 0, 0);
            case HONORS -> {
                double sink = easeInOutQuart(clamp01(p / 0.12));
                double unfold = easeOutCubic(clamp01((p - 0.05) / 0.14));
                double scan = 1;
                if (p > 0.9) {
                    double out = easeInOutQuart(clamp01((p - 0.9) / 0.1));
                    unfold *= 1 - out;
                    scan = 1 - out;
                    sink *= 1 - out * 0.35;
                }
                yield new Stage(1, scan, 1, 1 - sink * 0.85, -1, 1 - sink, sink, unfold,
                        p * ReplayTimeline.HONORS / 20.0);
            }
        };
    }

    /**
     * Handoff between the idle crystal cube sculpture and an active linear replay session.
     *
     * <p>Unlike the legacy auto-looping {@link #boardHandoff}, reaching the end of the match never
     * folds back to idle; {@code exitProgress > 0} is driven strictly by an explicit session end.</p>
     */
    public static Handoff linearHandoff(double buildProgress, double exitProgress) {
        double exit = clamp01(exitProgress);
        if (exit > 0) {
            return new Handoff(
                    smoothstep(0.32, 1.0, exit),
                    smoothstep(0.24, 0.92, exit),
                    smoothstep(0.06, 0.44, exit) * (1 - smoothstep(0.44, 0.94, exit)),
                    1 - smoothstep(0.06, 0.86, exit));
        }
        double build = clamp01(buildProgress);
        if (build < 1) {
            return new Handoff(
                    1 - smoothstep(0.0, 0.36, build),
                    1 - smoothstep(0.10, 0.50, build),
                    smoothstep(0.06, 0.32, build) * (1 - smoothstep(0.32, 0.68, build)),
                    smoothstep(0.18, 0.68, build));
        }
        return REPLAY;
    }

    /**
     * Choreography for the linear interactive table player.
     *
     * <p>At the end of the match ({@code replayTick >= durationTicks}), the diorama stays fully
     * revealed in final-review state ({@code scan = 1, actors = 1, mapSink = 0, honors = 0, history -> 1})
     * so spectators can keep scrubbing and inspecting paths; only {@code exitProgress > 0} retracts
     * the diorama back to the idle sculpture.</p>
     */
    public static Stage linearStage(double replayTick, int durationTicks, double buildProgress,
                                    double reviewProgress, double exitProgress) {
        double exit = clamp01(exitProgress);
        if (exit > 0) {
            double retract = smoothstep(0.06, 0.82, exit);
            double actors = 1 - smoothstep(0.0, 0.58, exit);
            double glow = 1 - 0.25 * smoothstep(0.35, 1.0, exit);
            return new Stage(glow, 1 - retract, 1 - smoothstep(0.2, 0.85, exit),
                    actors, -1, (1 - retract) * clamp01(reviewProgress), retract * 0.45, 0, 0);
        }
        double build = clamp01(buildProgress);
        if (build < 1) {
            double lamp = 0.75 + 0.25 * smoothstep(0.0, 0.50, build);
            double scan = smoothstep(0.22, 0.94, build);
            double flip = easeOutCubic(clamp01((build - 0.40) / 0.50));
            double actors = easeOutCubic(clamp01((build - 0.72) / 0.28));
            return new Stage(lamp, scan, flip, actors, -1, 0, 0, 0, 0);
        }
        boolean atEnd = durationTicks <= 0 || replayTick >= durationTicks - 1e-4;
        if (atEnd) {
            double rp = clamp01(reviewProgress);
            double wave = rp < 0.62 ? easeInOutQuart(rp / 0.62) : -1;
            double history = easeOutCubic(clamp01((rp - 0.05) / 0.45));
            return new Stage(1, 1, 1, 1, wave, Math.max(0.35, history), 0, 0, 0);
        }
        return new Stage(1, 1, 1, 1, -1, 0, 0, 0, 0);
    }

    /**
     * Pure anti-occlusion height envelope for floating table HUD / kill-feed panels.
     *
     * <p>Accounts for both the nominal bounding height ({@code mapH}) and the actual highest rendered
     * vertex ({@code meshReplayCeiling * mapScale}, covering multi-story stations and tall mod props),
     * multiplied by {@code currentFocusZoom} (up to {@code 2.4x}) plus safety clearance.</p>
     */
    public static float effectiveFeedTop(float mapBase, float mapH, double meshReplayCeiling,
                                         float mapScale, double focusZoom, float clearance) {
        double meshTop = (Double.isFinite(meshReplayCeiling) && meshReplayCeiling > 0 && mapScale > 0)
                ? meshReplayCeiling * mapScale : 0.0;
        double effectiveMapH = Math.max(Math.max(0.20, Double.isFinite(mapH) ? mapH : 0.0), meshTop);
        double zoom = Math.max(1.0, Double.isFinite(focusZoom) ? focusZoom : 1.0);
        double pad = Math.max(0.22, Double.isFinite(clearance) ? clearance : DEFAULT_FEED_CLEARANCE);
        return (float) (mapBase + effectiveMapH * zoom + pad);
    }

    /** Opacity multiplier from the camera distance: 1 inside {@link #FADE_START}, 0 beyond {@link #FADE_END}. */
    public static double distanceFade(double distance) {
        return 1 - smoothstep(FADE_START, FADE_END, distance);
    }

    /**
     * Sub-linear scale multiplier for fine detail LOD thresholds (nameplates, rivets, full-length trails,
     * idle cube subdivision). Keeps large boards (up to 32 blocks / 5.12x) visible at long distance while
     * preventing detail LOD from staying pinned on at 120+ blocks away.
     */
    public static double detailScale(double scale) {
        return Math.sqrt(Math.max(1.0, scale));
    }

    /** Approximate replay ticks advanced per presentation tick during the fast portion of the action act. */
    public static double playbackRate(int durationTicks, int cycleTicks) {
        int action = cycleTicks - ReplayTimeline.INTRO - ReplayTimeline.REVIEW - ReplayTimeline.HONORS;
        if (action <= 0) return 1;
        return Math.max(1, durationTicks / (double) action);
    }

    /** Comet tail length in replay ticks: 10 s of match time, never shorter than ~1.2 s on screen. */
    public static double cometTicks(double playbackRate) {
        return Math.max(200, 24 * playbackRate);
    }

    /** Effect window in replay ticks for an effect meant to last {@code seconds} of presentation time. */
    public static double effectWindow(double seconds, double playbackRate) {
        return Math.max(4, seconds * 20 * playbackRate);
    }

    /** 1 at the event, decaying to 0 after {@code window}; 0 before the event. */
    public static double pulse(double age, double window) {
        if (age < 0 || window <= 0 || age > window) return 0;
        return 1 - easeOutCubic(age / window);
    }

    public static String clock(double ticks) {
        long seconds = Math.max(0, (long) Math.floor(ticks / 20.0));
        return String.format(java.util.Locale.ROOT, "%02d:%02d", Math.min(99, seconds / 60), seconds % 60);
    }

    public static double easeOutCubic(double t) {
        t = clamp01(t);
        double inv = 1 - t;
        return 1 - inv * inv * inv;
    }

    public static double easeInOutQuart(double t) {
        t = clamp01(t);
        return t < 0.5 ? 8 * t * t * t * t : 1 - Math.pow(-2 * t + 2, 4) / 2;
    }

    public static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3 - 2 * t);
    }

    public static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
