package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import org.jetbrains.annotations.Nullable;

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
 *
 * <p>The whole film sits on one musical grid of {@link #BPM}: every act starts on a beat, every act except the
 * last lasts whole bars, every highlight impact lands on a beat and every slow-motion aftermath lasts whole
 * beats. The flexible fast-forward stretches and approaches absorb the rounding, preferring whole bars between
 * consecutive highlights, so the soundtrack never has to change its tempo.</p>
 */
public final class ReplayVideoScript {
    /** Tempo of the film. One beat is half a second, one bar two seconds. */
    public static final double BPM = 120;
    public static final double BEAT = 60.0 / BPM, BAR = 4 * BEAT;
    public static final double TITLE = 2 * BAR, REVIEW = 2 * BAR, VICTORY = 2 * BAR, MVP = 3 * BAR, OUTRO = 6.4, TAIL = 0.9;
    public static final int OUTRO_PAGE_SIZE = 9;
    public static final double OUTRO_PAGE_HOLD = 3.1;
    /** Half length of the slab wipe around each act boundary. */
    public static final double WIPE = 0.3;
    public static final int MAX_BEATS = 10;
    /** Kills by one player within this many ticks count as one streak (double / triple / multi kill). */
    public static final int STREAK_TICKS = 600;
    private static final int CLUSTER_TICKS = 40;
    private static final double REAL = 20;
    private static final double MIN_TAIL = 0.5;
    /** Match ticks kept after a focused player's final elimination so the death aftermath finishes cleanly. */
    public static final int ELIMINATION_TAIL_TICKS = 20;

    public enum Act { TITLE, ACTION, REVIEW, VICTORY, MVP, OUTRO }

    /**
     * Highlight kinds. {@code freeze + slow} always equals {@code beats} grid beats, so the groove re-enters on the
     * grid when the aftermath ends.
     */
    public enum BeatType {
        KILL(0.5, 4, 0.30), REVIVAL(0.42, 4, 0.32), EXPLOSION(0.45, 4, 0.32),
        DEFUSE(0.34, 3, 0.35), SHIELD(0.26, 2, 0.38), HIT(0.22, 2, 0.4);
        public final double freeze, slow, slowRate;
        public final int beats;
        BeatType(double freeze, int beats, double slowRate) {
            this.freeze = freeze;
            this.beats = beats;
            this.slow = beats * BEAT - freeze;
            this.slowRate = slowRate;
        }

        /** Freeze plus slow motion, exactly {@link #beats} beats. */
        public double aftermath() { return beats * BEAT; }
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
    private final @Nullable UUID focusPlayer;
    private final int duration;
    private final int actionEndTick;
    private final boolean mvpAct;
    private final double actionStart, actionLength, reviewStart, victoryStart, mvpStart, outroStart, outroLength, total;
    private final double[] kv, kt, km, kw;
    private final List<Beat> beats;
    private final List<Feed> feeds;
    private final List<SpanWindow> spans;
    private final double fastSpeed;

    public ReplayVideoScript(LastMatchReplay replay) {
        this(replay, null);
    }

    public ReplayVideoScript(LastMatchReplay replay, @Nullable UUID focusPlayer) {
        this.replay = replay;
        this.focusPlayer = resolveFocusPlayer(replay, focusPlayer);
        this.duration = Math.max(0, replay.durationTicks());
        this.actionEndTick = resolveActionEndTick(replay, duration, this.focusPlayer);
        this.mvpAct = replay.mvp() != null || replay.participants().stream().anyMatch(LastMatchReplay.Participant::winner);

        List<Cluster> chosen = chooseBeats(replay, actionEndTick, this.focusPlayer);
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
            int end = Math.min(actionEndTick, tb + slowTicks);
            gaps.add(new int[]{cursor, start});
            plan.add(new double[]{start, end});
            cursor = end;
        }
        gaps.add(new int[]{cursor, actionEndTick});
        double fastTarget = Math.clamp(actionEndTick / REAL / 13.0, 7, 26);
        // Single-player perspectives filter out other players' highlights and often have only 0-1 beats;
        // keeping the global 5.5 s per-gap cap would crush a 4-minute match into a single 5.5 s ultra-fast stretch.
        // Leaving maxGap unbounded for single-player lets solveSpeed pick a single uniform hyper-lapse rate matching fastTarget.
        double maxGap = this.focusPlayer != null ? Double.POSITIVE_INFINITY : 5.5;
        double speed = solveSpeed(gaps, fastTarget, maxGap);
        this.fastSpeed = speed / REAL;

        // v: video seconds since the action start; anchor: the last on-grid time (action start or previous impact).
        double v = 0, anchor = 0;
        List<Beat> built = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Cluster c = chosen.get(i);
            int[] gap = gaps.get(i);
            int gapTicks = Math.max(0, gap[1] - gap[0]);
            double g = gapTicks > 0 ? gapSeconds(gapTicks, speed, maxGap) : 0;
            double gMin = gapTicks > 0 ? Math.min(0.35, gapTicks / REAL) : 0;
            int start = (int) plan.get(i)[0], end = (int) plan.get(i)[1];
            int approachTicks = c.tick - start;
            // A highlight on the very first tick still gets a short establishing beat.
            boolean establish = approachTicks <= 0 && built.isEmpty() && v == 0;
            double a = approachTicks > 0 ? Math.max(0.45, 1.15 * approachTicks / 13.0) : establish ? 0.6 : 0;
            double aMin = approachTicks > 0 ? 0.45 : establish ? 0.4 : 0;

            // Snap the impact onto the beat grid; the fast-forward (or else the approach) absorbs the difference.
            double fixed = v - anchor;
            double impact = anchor + fitBeats(fixed + g + a, fixed + gMin + aMin) * BEAT;
            double extra = impact - (v + g + a);
            if (extra >= 0) {
                if (gapTicks > 0) g += extra;
                else a += extra;
            } else {
                double cut = Math.min(-extra, g - gMin);
                g -= cut;
                a = Math.max(0, a - (-extra - cut));
            }
            if (gapTicks > 0) {
                v += g;
                knots.add(new double[]{v, gap[1]});
            }
            double approachAt = v;
            if (a > 1e-9 && impact - v > 1e-9) knots.add(new double[]{impact, c.tick});
            double release = impact + c.type.freeze;
            knots.add(new double[]{release, c.tick});
            v = release;
            if (end > c.tick) {
                v = impact + c.type.aftermath();
                knots.add(new double[]{v, end});
            }
            built.add(new Beat(built.size(), c.type, c.primary, c.tick, Math.min(approachAt, impact), impact, release, v,
                    c.streak, c.firstBlood, c.finalBlow, c.kills));
            anchor = impact;
        }
        // The review starts on the grid too; the closing fast-forward and the tail hold absorb the rounding.
        int[] last = gaps.getLast();
        int lastTicks = Math.max(0, last[1] - last[0]);
        double g = lastTicks > 0 ? gapSeconds(lastTicks, speed, maxGap) : 0;
        double gMin = lastTicks > 0 ? Math.min(0.35, lastTicks / REAL) : 0;
        double tail = TAIL;
        double fixed = v - anchor;
        double finish = anchor + fitBeats(fixed + g + tail, fixed + gMin + MIN_TAIL) * BEAT;
        double extra = finish - (v + g + tail);
        if (extra >= 0) {
            tail += extra;
        } else {
            double cut = Math.min(-extra, tail - MIN_TAIL);
            tail -= cut;
            g = Math.max(gMin, g - (-extra - cut));
        }
        if (lastTicks > 0) {
            v += g;
            knots.add(new double[]{v, last[1]});
        }
        knots.add(new double[]{finish, actionEndTick});

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
        int outroPages = Math.max(1, (replay.participants().size() + OUTRO_PAGE_SIZE - 1) / OUTRO_PAGE_SIZE);
        outroLength = outroPages <= 1 ? OUTRO : 1.1 + outroPages * OUTRO_PAGE_HOLD + 0.9;
        total = outroStart + outroLength;

        List<Beat> shifted = new ArrayList<>(built.size());
        for (Beat b : built) {
            shifted.add(new Beat(b.index(), b.type(), b.node(), b.tick(), b.approach() + actionStart, b.impact() + actionStart,
                    b.release() + actionStart, b.end() + actionStart, b.streak(), b.firstBlood(), b.finalBlow(), b.kills()));
        }
        beats = List.copyOf(shifted);

        List<Feed> feed = new ArrayList<>();
        for (LastMatchReplay.Node node : replay.events()) {
            if (!feedable(node) || node.tick() > actionEndTick) continue;
            boolean featured = false;
            for (Beat b : beats) if (b.node() == node) { featured = true; break; }
            if (featured) continue;
            feed.add(new Feed(node, videoTimeOf(node.tick())));
        }
        feeds = List.copyOf(feed);
        List<SpanWindow> windows = new ArrayList<>();
        for (LastMatchReplay.Span span : replay.spans()) {
            if (span.startTick() >= actionEndTick) continue;
            double s = videoTimeOf(span.startTick()), e = videoTimeOf(Math.min(span.endTick(), actionEndTick));
            if (e > s) windows.add(new SpanWindow(span, s, e));
        }
        spans = List.copyOf(windows);
    }

    // ------------------------------------------------------------------ public queries

    public LastMatchReplay replay() { return replay; }
    public @Nullable UUID focusPlayer() { return focusPlayer; }
    public double total() { return total; }
    public int frames(int fps) { return (int) Math.ceil(total * fps); }
    public List<Beat> beats() { return beats; }
    public List<Feed> feeds() { return feeds; }
    public List<SpanWindow> spans() { return spans; }
    public boolean hasMvpAct() { return mvpAct; }
    public double fastSpeed() { return fastSpeed; }
    public int duration() { return duration; }
    public int actionEndTick() { return actionEndTick; }

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
            case OUTRO -> outroLength;
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

    /** Featured events use the cut's impact, even when the match clock is already on their tick. */
    public double eventTime(LastMatchReplay.Node node) {
        for (Beat beat : beats) {
            if (beat.node() == node) return beat.impact();
        }
        return videoTimeOf(node.tick());
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

    /**
     * Number of grid beats for a span of {@code raw} seconds that must last at least {@code min} seconds: close to
     * {@code raw}, preferring whole bars, then half bars, so the music between two anchors rarely ends on a broken bar.
     */
    static int fitBeats(double raw, double min) {
        int lo = Math.max(1, (int) Math.ceil(min / BEAT - 1e-9));
        int hi = Math.max(lo, (int) Math.ceil(raw / BEAT)) + 4;
        int best = lo;
        double bestCost = Double.MAX_VALUE;
        for (int n = lo; n <= hi; n++) {
            double cost = Math.abs(n * BEAT - raw) + (n % 4 == 0 ? 0 : n % 2 == 0 ? 0.35 : 0.6);
            if (cost < bestCost - 1e-9) {
                bestCost = cost;
                best = n;
            }
        }
        return best;
    }

    private static double gapSeconds(int ticks, double speed, double maxGap) {
        return Math.clamp(ticks / speed, Math.min(0.35, ticks / REAL), maxGap);
    }

    private static double solveSpeed(List<int[]> gaps, double target, double maxGap) {
        double lo = REAL, hi = REAL * 400;
        if (sum(gaps, lo, maxGap) <= target) return lo;
        for (int i = 0; i < 60; i++) {
            double mid = Math.sqrt(lo * hi);
            if (sum(gaps, mid, maxGap) > target) lo = mid; else hi = mid;
        }
        return hi;
    }

    private static double sum(List<int[]> gaps, double speed, double maxGap) {
        double total = 0;
        for (int[] g : gaps) if (g[1] > g[0]) total += gapSeconds(g[1] - g[0], speed, maxGap);
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

    private static @Nullable UUID resolveFocusPlayer(LastMatchReplay replay, @Nullable UUID focusPlayer) {
        if (focusPlayer == null) return null;
        for (var p : replay.participants()) {
            if (focusPlayer.equals(p.id())) return focusPlayer;
        }
        return null;
    }

    /**
     * When exporting a single player's perspective, cuts the ACTION act shortly after their final elimination
     * (or departure) if they are never revived afterwards.
     */
    private static int resolveActionEndTick(LastMatchReplay replay, int duration, @Nullable UUID focusPlayer) {
        if (focusPlayer == null || duration <= 0) return duration;
        LastMatchReplay.Participant target = null;
        for (var p : replay.participants()) {
            if (focusPlayer.equals(p.id())) {
                target = p;
                break;
            }
        }
        if (target == null) return duration;
        List<LastMatchReplay.Sample> track = target.track();
        if (!track.isEmpty()) {
            LastMatchReplay.Sample last = track.getLast();
            if ((last.flags() & LastMatchReplay.ALIVE) != 0) return duration;
            int runStart = track.size() - 1;
            while (runStart > 0 && (track.get(runStart - 1).flags() & LastMatchReplay.ALIVE) == 0) {
                runStart--;
            }
            int finalInactiveTick = track.get(runStart).tick();
            int lastAliveTick = runStart > 0 ? track.get(runStart - 1).tick() : -1;
            int endTick = finalInactiveTick;
            for (LastMatchReplay.Node e : replay.events()) {
                if (e.kind() == LastMatchReplay.Kind.KILL && focusPlayer.equals(e.target()) && e.tick() >= lastAliveTick) {
                    endTick = Math.max(endTick, e.tick());
                }
            }
            return Math.clamp(endTick + ELIMINATION_TAIL_TICKS, 0, duration);
        }
        int lastDeath = -1;
        int lastRevive = -1;
        for (LastMatchReplay.Node e : replay.events()) {
            if (e.kind() == LastMatchReplay.Kind.KILL && focusPlayer.equals(e.target())) {
                lastDeath = Math.max(lastDeath, e.tick());
            } else if (e.kind() == LastMatchReplay.Kind.PLAYER_REVIVAL
                    && (focusPlayer.equals(e.target()) || focusPlayer.equals(e.actor()))) {
                lastRevive = Math.max(lastRevive, e.tick());
            }
        }
        if (lastDeath >= 0 && lastDeath > lastRevive) {
            return Math.clamp(lastDeath + ELIMINATION_TAIL_TICKS, 0, duration);
        }
        return duration;
    }

    private static boolean involves(LastMatchReplay.Node node, UUID player) {
        return player.equals(node.actor()) || player.equals(node.target());
    }

    private static List<Cluster> chooseBeats(LastMatchReplay replay, int actionEndTick, @Nullable UUID focusPlayer) {
        LastMatchReplay.Node first = replay.firstKill(), lastKill = replay.lastKill();
        long kills = replay.events().stream().filter(e -> e.kind() == LastMatchReplay.Kind.KILL).count();
        Map<LastMatchReplay.Node, Integer> streaks = streaks(replay);
        List<LastMatchReplay.Node> candidates = replay.events().stream()
                .filter(e -> classify(e) != null && e.tick() >= 0 && e.tick() <= actionEndTick
                        && (focusPlayer == null || involves(e, focusPlayer)))
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
