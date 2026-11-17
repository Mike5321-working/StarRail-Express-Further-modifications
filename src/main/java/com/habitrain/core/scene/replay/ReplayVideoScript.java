package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Edit decision list of an exported replay video. Pure and deterministic: every frame is a function of
 * the replay and the video clock, so the camera, 3D effects and 2D motion graphics always agree.
 *
 * <p>The video has its own dramaturgy instead of the board's loop: a ticket-style title, the match with
 * adaptive hyper-lapse between highlights, a slow approach / freeze-frame / slow-motion aftermath for
 * every featured beat (kills, revivals, explosions ...), a full-match overview, the victory card, the MVP
 * showcase and a passenger-list outro. Match time is remapped through a monotone cubic spline, so
 * speed ramps are smooth and a freeze frame holds exactly on the event tick.</p>
 */
public final class ReplayVideoScript {
    public static final double TITLE = 3.6, REVIEW = 4.2, VICTORY = 4.0, MVP = 5.6, OUTRO = 6.4, TAIL = 0.9;
    /** Half length of the slab wipe around each act boundary. */
    public static final double WIPE = 0.3;
    public static final int MAX_BEATS = 10;
    /** Kills by one player within this many ticks count as one streak (double / triple / multi kill). */
    public static final int STREAK_TICKS = 600;
    private static final int CLUSTER_TICKS = 40;
    private static final double REAL = 20;

    public enum Act { TITLE, ACTION, REVIEW, VICTORY, MVP, OUTRO }

    public enum BeatType {
        KILL(0.55, 1.6, 0.30), REVIVAL(0.4, 1.3, 0.32), EXPLOSION(0.42, 1.3, 0.32),
        DEFUSE(0.32, 1.1, 0.35), SHIELD(0.26, 1.0, 0.38), HIT(0.22, 0.9, 0.4);
        public final double freeze, slow, slowRate;
        BeatType(double freeze, double slow, double slowRate) { this.freeze = freeze; this.slow = slow; this.slowRate = slowRate; }
    }

    /**
     * One featured highlight. Times are absolute video seconds.
     *
     * @param approach video time the lead-in begins (camera starts to travel, time starts to slow)
     * @param impact   video time of the event tick (freeze frame begins)
     * @param release  video time the freeze frame ends
     * @param end      video time the slow-motion aftermath ends
     * @param streak   kill streak of the actor (1 = single kill)
     */
    public record Beat(int index, BeatType type, LastMatchReplay.Node node, int tick, double approach, double impact,
                       double release, double end, int streak, boolean firstBlood, boolean finalBlow, int kills) {
        public double local(double time) { return time - impact; }
    }

    public record Feed(LastMatchReplay.Node node, double time) {}
    public record SpanWindow(LastMatchReplay.Span span, double start, double end) {}

    /**
     * @param speed     match ticks per real second divided by 20 (1 = real time, 0 = frozen)
     * @param world     animation clock in seconds: slows down in slow motion and nearly stops when frozen
     * @param wipe      progress 0..1 of the act transition currently on screen, or -1
     */
    public record Sample(Act act, double time, double actTime, double actLength, double replayTick, double speed,
                         boolean slow, boolean freeze, double world, double wipe) {
        public double actProgress() { return actLength <= 0 ? 1 : ReplayStagePlan.clamp01(actTime / actLength); }
    }

    private final LastMatchReplay replay;
    private final int duration;
    private final boolean mvpAct;
    private final double actionStart, actionLength, reviewStart, victoryStart, mvpStart, outroStart, total;
    private final double[] kv, kt, km, kw;
    private final List<Beat> beats;
    private final List<Feed> feeds;
    private final List<SpanWindow> spans;
    private final double fastSpeed;

    public ReplayVideoScript(LastMatchReplay replay) {
        this.replay = replay;
        this.duration = Math.max(0, replay.durationTicks());
        this.mvpAct = replay.mvp() != null || replay.participants().stream().anyMatch(LastMatchReplay.Participant::winner);

        List<Cluster> chosen = chooseBeats(replay, duration);
        // ---- knots of the action act: (video seconds since action start, match tick)
        List<double[]> knots = new ArrayList<>();
        knots.add(new double[]{0, 0});
        List<int[]> gaps = new ArrayList<>();       // [fromTick, toTick] fast stretches
        List<double[]> plan = new ArrayList<>();     // per beat: approachTicks, slowTicks
        int cursor = 0;
        for (Cluster c : chosen) {
            int tb = c.tick;
            int start = Math.min(tb, Math.max(cursor, tb - 13));
            int slowTicks = (int) Math.round(c.type.slow * REAL * c.type.slowRate);
            int end = Math.min(duration, tb + slowTicks);
            gaps.add(new int[]{cursor, start});
            plan.add(new double[]{start, end});
            cursor = end;
        }
        gaps.add(new int[]{cursor, duration});
        double fastTarget = Math.clamp(duration / REAL / 13.0, 7, 26);
        double speed = solveSpeed(gaps, fastTarget);
        this.fastSpeed = speed / REAL;

        double v = 0;
        List<Beat> built = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Cluster c = chosen.get(i);
            v = gap(knots, v, gaps.get(i), speed);
            int start = (int) plan.get(i)[0], end = (int) plan.get(i)[1];
            double approachAt = v;
            int approachTicks = c.tick - start;
            if (approachTicks > 0) {
                v += Math.max(0.45, 1.15 * approachTicks / 13.0);
                knots.add(new double[]{v, c.tick});
            } else if (built.isEmpty() && v == 0) {
                // A highlight on the very first tick still gets a short establishing beat.
                v += 0.6;
                knots.add(new double[]{v, c.tick});
            }
            double impact = v;
            v += c.type.freeze;
            knots.add(new double[]{v, c.tick});
            double release = v;
            if (end > c.tick) {
                v += c.type.slow;
                knots.add(new double[]{v, end});
            }
            built.add(new Beat(built.size(), c.type, c.primary, c.tick, approachAt, impact, release, v,
                    c.streak, c.firstBlood, c.finalBlow, c.kills));
        }
        v = gap(knots, v, gaps.getLast(), speed);
        v += TAIL;
        knots.add(new double[]{v, duration});

        int n = knots.size();
        kv = new double[n];
        kt = new double[n];
        for (int i = 0; i < n; i++) { kv[i] = knots.get(i)[0]; kt[i] = knots.get(i)[1]; }
        km = tangents(kv, kt);
        kw = new double[n];
        for (int i = 1; i < n; i++) {
            double h = kv[i] - kv[i - 1];
            double rate = Math.clamp((kt[i] - kt[i - 1]) / h / REAL, 0.04, 1);
            kw[i] = kw[i - 1] + h * rate;
        }

        actionStart = TITLE;
        actionLength = kv[n - 1];
        reviewStart = actionStart + actionLength;
        victoryStart = reviewStart + REVIEW;
        mvpStart = victoryStart + VICTORY;
        outroStart = mvpStart + (mvpAct ? MVP : 0);
        total = outroStart + OUTRO;

        List<Beat> shifted = new ArrayList<>(built.size());
        for (Beat b : built) {
            shifted.add(new Beat(b.index(), b.type(), b.node(), b.tick(), b.approach() + actionStart, b.impact() + actionStart,
                    b.release() + actionStart, b.end() + actionStart, b.streak(), b.firstBlood(), b.finalBlow(), b.kills()));
        }
        beats = List.copyOf(shifted);

        List<Feed> feed = new ArrayList<>();
        for (LastMatchReplay.Node node : replay.events()) {
            if (!feedable(node)) continue;
            boolean featured = false;
            for (Beat b : beats) if (b.node() == node) { featured = true; break; }
            if (featured) continue;
            feed.add(new Feed(node, videoTimeOf(node.tick())));
        }
        feeds = List.copyOf(feed);
        List<SpanWindow> windows = new ArrayList<>();
        for (LastMatchReplay.Span span : replay.spans()) {
            double s = videoTimeOf(span.startTick()), e = videoTimeOf(Math.min(span.endTick(), duration));
            if (e > s) windows.add(new SpanWindow(span, s, e));
        }
        spans = List.copyOf(windows);
    }

    // ------------------------------------------------------------------ public queries

    public LastMatchReplay replay() { return replay; }
    public double total() { return total; }
    public int frames(int fps) { return (int) Math.ceil(total * fps); }
    public List<Beat> beats() { return beats; }
    public List<Feed> feeds() { return feeds; }
    public List<SpanWindow> spans() { return spans; }
    public boolean hasMvpAct() { return mvpAct; }
    public double fastSpeed() { return fastSpeed; }
    public int duration() { return duration; }

    public double actStart(Act act) {
        return switch (act) {
            case TITLE -> 0;
            case ACTION -> actionStart;
            case REVIEW -> reviewStart;
            case VICTORY -> victoryStart;
            case MVP -> mvpStart;
            case OUTRO -> outroStart;
        };
    }

    public double actLength(Act act) {
        return switch (act) {
            case TITLE -> TITLE;
            case ACTION -> actionLength;
            case REVIEW -> REVIEW;
            case VICTORY -> VICTORY;
            case MVP -> mvpAct ? MVP : 0;
            case OUTRO -> OUTRO;
        };
    }

    public Sample sample(double time) {
        double t = Math.clamp(time, 0, Math.nextDown(total));
        Act act = actAt(t);
        double actTime = t - actStart(act);
        double tick, speed = 0, world;
        if (act == Act.TITLE) {
            tick = 0;
            world = t;
        } else if (act == Act.ACTION) {
            tick = eval(actTime);
            speed = slope(actTime) / REAL;
            world = actionStart + worldAt(actTime);
        } else {
            tick = duration;
            world = actionStart + kw[kw.length - 1] + (t - reviewStart);
        }
        boolean freeze = act == Act.ACTION && speed < 0.02;
        boolean slow = act == Act.ACTION && !freeze && speed < 0.75;
        return new Sample(act, t, actTime, actLength(act), tick, speed, slow, freeze, world, wipe(t));
    }

    /** Earliest video time at which the match clock reaches {@code tick}. */
    public double videoTimeOf(double tick) {
        if (tick <= 0) return actionStart;
        if (tick >= kt[kt.length - 1]) {
            for (int i = 1; i < kt.length; i++) if (kt[i] >= tick) return actionStart + kv[i];
            return actionStart + kv[kv.length - 1];
        }
        int i = 0;
        while (i < kt.length - 2 && kt[i + 1] < tick) i++;
        double lo = 0, hi = 1;
        for (int it = 0; it < 30; it++) {
            double mid = (lo + hi) / 2;
            if (hermite(i, mid) < tick) lo = mid; else hi = mid;
        }
        return actionStart + kv[i] + (kv[i + 1] - kv[i]) * hi;
    }

    public ReplayStagePlan.Stage stage(Sample s) {
        double k = s.actTime(), p = s.actProgress();
        return switch (s.act()) {
            case TITLE -> new ReplayStagePlan.Stage(ReplayStagePlan.easeOutCubic(k / 0.8),
                    ReplayStagePlan.easeInOutQuart((k - 0.35) / 2.1), ReplayStagePlan.easeOutCubic((k - 1.6) / 1.0),
                    ReplayStagePlan.easeOutCubic((k - 2.9) / 0.7) * 0.5, -1, 0, 0, 0, 0);
            case ACTION -> new ReplayStagePlan.Stage(1, 1, 1, 0.5 + 0.5 * ReplayStagePlan.easeOutCubic(k / 0.6), -1, 0, 0, 0, 0);
            case REVIEW -> new ReplayStagePlan.Stage(1, 1, 1, 1, p < 0.7 ? ReplayStagePlan.easeInOutQuart(p / 0.7) : -1,
                    ReplayStagePlan.easeOutCubic((p - 0.05) / 0.45), 0, 0, 0);
            case VICTORY -> new ReplayStagePlan.Stage(1, 1, 1, 1, -1, 1 - 0.45 * p, 0, 0, 0);
            case MVP -> new ReplayStagePlan.Stage(1, 1, 1, 1, -1, 0.4, 0.1, 0, 0);
            case OUTRO -> new ReplayStagePlan.Stage(1, 1, 1, 1, -1, 0.55, 0.1 + 0.3 * ReplayStagePlan.easeOutCubic(p / 0.3), 0, 0);
        };
    }

    public ReplayTimeline.Frame frame(Sample s) {
        return switch (s.act()) {
            case TITLE -> new ReplayTimeline.Frame(ReplayTimeline.Phase.BUILD, s.actProgress(), 0, false);
            case ACTION -> new ReplayTimeline.Frame(ReplayTimeline.Phase.PLAY, s.actProgress(), s.replayTick(), s.slow() || s.freeze());
            case REVIEW -> new ReplayTimeline.Frame(ReplayTimeline.Phase.REVIEW, s.actProgress(), duration, false);
            default -> new ReplayTimeline.Frame(ReplayTimeline.Phase.HONORS, s.actProgress(), duration, false);
        };
    }

    /** 1 at a beat's impact decaying with {@code rate} per second; 0 before. */
    public static double impulse(double local, double rate) {
        return local < 0 ? 0 : Math.exp(-local * rate);
    }

    /** Eased 0..1 influence of a beat: lead-in, hold, release. */
    public static double influence(Beat b, double time, double leadIn, double out) {
        if (time < b.approach() - 0.1 || time > b.end() + out) return 0;
        double in = ReplayStagePlan.smoothstep(b.approach() - 0.1, Math.min(b.impact() - 0.05, b.approach() + leadIn), time);
        double release = 1 - ReplayStagePlan.smoothstep(b.end(), b.end() + out, time);
        return Math.min(in, release);
    }

    // ------------------------------------------------------------------ act clock

    private Act actAt(double t) {
        if (t < actionStart) return Act.TITLE;
        if (t < reviewStart) return Act.ACTION;
        if (t < victoryStart) return Act.REVIEW;
        if (t < mvpStart) return Act.VICTORY;
        if (t < outroStart) return Act.MVP;
        return Act.OUTRO;
    }

    private double wipe(double t) {
        double[] boundaries = mvpAct ? new double[]{actionStart, reviewStart, victoryStart, mvpStart, outroStart}
                : new double[]{actionStart, reviewStart, victoryStart, outroStart};
        for (double b : boundaries) {
            if (Math.abs(t - b) <= WIPE) return (t - (b - WIPE)) / (2 * WIPE);
        }
        return -1;
    }

    // ------------------------------------------------------------------ spline

    private int segment(double v) {
        int lo = 0, hi = kv.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (kv[mid] <= v) lo = mid; else hi = mid - 1;
        }
        return Math.max(0, lo);
    }

    private double eval(double v) {
        if (kv.length < 2) return kt[0];
        if (v <= 0) return kt[0];
        if (v >= kv[kv.length - 1]) return kt[kt.length - 1];
        int i = segment(v);
        return hermite(i, (v - kv[i]) / (kv[i + 1] - kv[i]));
    }

    private double hermite(int i, double s) {
        double h = kv[i + 1] - kv[i];
        double s2 = s * s, s3 = s2 * s;
        double value = (2 * s3 - 3 * s2 + 1) * kt[i] + (s3 - 2 * s2 + s) * h * km[i]
                + (-2 * s3 + 3 * s2) * kt[i + 1] + (s3 - s2) * h * km[i + 1];
        return Math.clamp(value, Math.min(kt[i], kt[i + 1]), Math.max(kt[i], kt[i + 1]));
    }

    private double slope(double v) {
        if (kv.length < 2 || v < 0 || v >= kv[kv.length - 1]) return 0;
        int i = segment(v);
        double h = kv[i + 1] - kv[i];
        double s = (v - kv[i]) / h, s2 = s * s;
        return ((6 * s2 - 6 * s) * kt[i] + (3 * s2 - 4 * s + 1) * h * km[i]
                + (-6 * s2 + 6 * s) * kt[i + 1] + (3 * s2 - 2 * s) * h * km[i + 1]) / h;
    }

    private double worldAt(double v) {
        if (v <= 0) return v;
        if (v >= kv[kv.length - 1]) return kw[kw.length - 1] + (v - kv[kv.length - 1]);
        int i = segment(v);
        double k = (v - kv[i]) / (kv[i + 1] - kv[i]);
        return kw[i] + (kw[i + 1] - kw[i]) * k;
    }

    /** Fritsch-Butland tangents: C1, never overshoots, flat segments stay exactly flat. */
    static double[] tangents(double[] x, double[] y) {
        int n = x.length;
        double[] m = new double[n];
        if (n < 2) return m;
        double[] d = new double[n - 1], h = new double[n - 1];
        for (int i = 0; i < n - 1; i++) { h[i] = x[i + 1] - x[i]; d[i] = (y[i + 1] - y[i]) / h[i]; }
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            if (d[i - 1] * d[i] <= 0) { m[i] = 0; continue; }
            double w1 = 2 * h[i] + h[i - 1], w2 = h[i] + 2 * h[i - 1];
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]);
        }
        // Ease into and out of the act: the first frames accelerate, the last ticks settle.
        m[0] = Math.min(m[0], d[0] * 0.5);
        return m;
    }

    private static double gap(List<double[]> knots, double v, int[] gap, double speed) {
        int ticks = gap[1] - gap[0];
        if (ticks <= 0) return v;
        v += gapSeconds(ticks, speed);
        knots.add(new double[]{v, gap[1]});
        return v;
    }

    private static double gapSeconds(int ticks, double speed) {
        return Math.clamp(ticks / speed, Math.min(0.35, ticks / REAL), 5.5);
    }

    private static double solveSpeed(List<int[]> gaps, double target) {
        double lo = REAL, hi = REAL * 400;
        if (sum(gaps, lo) <= target) return lo;
        for (int i = 0; i < 60; i++) {
            double mid = Math.sqrt(lo * hi);
            if (sum(gaps, mid) > target) lo = mid; else hi = mid;
        }
        return hi;
    }

    private static double sum(List<int[]> gaps, double speed) {
        double total = 0;
        for (int[] g : gaps) if (g[1] > g[0]) total += gapSeconds(g[1] - g[0], speed);
        return total;
    }

    // ------------------------------------------------------------------ beat selection

    private static final class Cluster {
        final int tick;
        LastMatchReplay.Node primary;
        BeatType type;
        int score, streak = 1, kills;
        boolean firstBlood, finalBlow;
        Cluster(int tick) { this.tick = tick; }
    }

    public static BeatType classify(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case KILL -> BeatType.KILL;
            case PLAYER_REVIVAL -> BeatType.REVIVAL;
            case GRENADE_EXPLOSION, BOMB_DETONATE -> BeatType.EXPLOSION;
            case BOMB_DEFUSE -> BeatType.DEFUSE;
            case SHIELD_BROKEN -> BeatType.SHIELD;
            case GUNSHOT -> (node.flags() & LastMatchReplay.HIT) != 0 ? BeatType.HIT : null;
            default -> null;
        };
    }

    /** Kill streak per kill node (identity): n-th kill of the same actor, each within {@link #STREAK_TICKS} of the previous. */
    public static Map<LastMatchReplay.Node, Integer> streaks(LastMatchReplay replay) {
        Map<LastMatchReplay.Node, Integer> result = new java.util.IdentityHashMap<>();
        Map<UUID, int[]> last = new HashMap<>(); // actor -> {tick, streak}
        for (LastMatchReplay.Node node : replay.events()) {
            if (node.kind() != LastMatchReplay.Kind.KILL) continue;
            int streak = 1;
            if (node.actor() != null) {
                int[] prev = last.get(node.actor());
                if (prev != null && node.tick() - prev[0] <= STREAK_TICKS) streak = prev[1] + 1;
                last.put(node.actor(), new int[]{node.tick(), streak});
            }
            result.put(node, streak);
        }
        return result;
    }

    private static List<Cluster> chooseBeats(LastMatchReplay replay, int duration) {
        LastMatchReplay.Node first = replay.firstKill(), lastKill = replay.lastKill();
        long kills = replay.events().stream().filter(e -> e.kind() == LastMatchReplay.Kind.KILL).count();
        Map<LastMatchReplay.Node, Integer> streaks = streaks(replay);
        List<LastMatchReplay.Node> candidates = replay.events().stream()
                .filter(e -> classify(e) != null && e.tick() >= 0 && e.tick() <= duration)
                .sorted(Comparator.comparingInt(LastMatchReplay.Node::tick)).toList();
        List<Cluster> clusters = new ArrayList<>();
        Cluster current = null;
        for (LastMatchReplay.Node node : candidates) {
            if (current == null || node.tick() - current.tick > CLUSTER_TICKS) {
                current = new Cluster(node.tick());
                clusters.add(current);
            }
            BeatType type = classify(node);
            int streak = streaks.getOrDefault(node, 1);
            boolean fb = node == first, fin = node == lastKill && kills >= 2;
            int score = switch (type) {
                case KILL -> 100 + (fb ? 30 : 0) + 20 * (streak - 1) + (fin ? 25 : 0);
                case REVIVAL -> 80;
                case EXPLOSION -> 70;
                case DEFUSE -> 60;
                case SHIELD -> 35;
                case HIT -> 30;
            };
            if (type == BeatType.KILL) {
                current.kills++;
                current.streak = Math.max(current.streak, streak);
                current.firstBlood |= fb;
                current.finalBlow |= fin;
            }
            if (current.primary == null || score > current.score) {
                current.primary = node;
                current.type = type;
                current.score = score;
            }
        }
        // Freeze on the primary event (the kill, not the opening shot that preceded it).
        List<Cluster> best = clusters.stream().map(c -> {
                    if (c.primary.tick() != c.tick) {
                        Cluster moved = new Cluster(c.primary.tick());
                        moved.primary = c.primary; moved.type = c.type; moved.score = c.score; moved.streak = c.streak;
                        moved.kills = c.kills; moved.firstBlood = c.firstBlood; moved.finalBlow = c.finalBlow;
                        return moved;
                    }
                    return c;
                })
                .sorted(Comparator.comparingInt((Cluster c) -> -c.score).thenComparingInt(c -> c.tick))
                .toList();
        // Greedy by score: every featured beat needs room for its approach and aftermath.
        List<Cluster> kept = new ArrayList<>();
        for (Cluster c : best) {
            if (kept.size() == MAX_BEATS) break;
            boolean room = true;
            for (Cluster k : kept) if (Math.abs(k.tick - c.tick) < 24) { room = false; break; }
            if (room) kept.add(c);
        }
        kept.sort(Comparator.comparingInt(c -> c.tick));
        return kept;
    }

    public static boolean feedable(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case DOOR_OPEN, DOOR_CLOSE, BLACKOUT_END -> false;
            default -> true;
        };
    }
}
