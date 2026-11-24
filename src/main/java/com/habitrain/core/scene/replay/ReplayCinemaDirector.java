package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import com.habitrain.core.api.match.replay.ReplaySkillEvent;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.ExportMode;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PersonalPacing;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PersonalView;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PromoScope;
import com.habitrain.core.scene.replay.ReplayHighlightScorer.HighlightTier;
import com.habitrain.core.scene.replay.ReplayHighlightScorer.PersonalClip;
import com.habitrain.core.scene.replay.ReplayHighlightScorer.ScoredHighlight;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Unified pure-Java cinema director orchestrating all 4 replay export modes:
 * <ol>
 *     <li><b>Short Mode</b> ({@link ExportMode#SHORT}): readable cruise speed (&lt;= 5.0x, eliminating the 46x
 *     teleport defect) + 4-degree tilted brass split-screen on kills (left 53.5% diorama bullet-time,
 *     right 46.5% synchronized killer POV starting 1.5s pre-kill).</li>
 *     <li><b>Personal Replay Mode</b> ({@link ExportMode#PERSONAL}): True Player POV or Sandbox Follow, with
 *     1x native audibility, 2x/4x WSOLA pitch-preserving speedup, or Highlights-Only 1x clips with
 *     ticket-flip/brass-sweep transitions.</li>
 *     <li><b>Sandbox Full-Fidelity Mode</b> ({@link ExportMode#SANDBOX_FULL}): full 3D players, dynamic blocks,
 *     entities, mod skills &amp; 3-layer unknown-skill fallback at readable 1.0x..2.5x pacing.</li>
 *     <li><b>Cinematic Promo &amp; Personal MVP Director</b> ({@link ExportMode#CINEMATIC_PROMO}): cruise strictly
 *     capped at &lt;= 3.0x + full 5-tier rarity pyramid choreography (Tier 1 Glimmer spotlight 1.65x/42% dim,
 *     Tier 2 Closeup, Tier 3 Over-Shoulder -&gt; 90..115 deg Hero Side 0.32x, Tier 4 Dual Vision sliding split
 *     expanding to full 1x POV, Tier 5 Apotheosis 4s suction -&gt; 1x POV -&gt; 0.4s apex freeze -&gt; 360 deg
 *     0.4x-&gt;1.0x master encore replay).</li>
 * </ol>
 */
public final class ReplayCinemaDirector {
    public static final double TICKS_PER_SECOND = 20.0;
    public static final double MAX_PROMO_CRUISE_SPEED = 3.0;
    public static final double MIN_PROMO_CRUISE_SPEED = 1.2;
    public static final double MAX_SHORT_CRUISE_SPEED = 5.0;
    public static final double MAX_SANDBOX_CRUISE_SPEED = 2.5;
    public static final double SHORT_SPLIT_LEFT_RATIO = 0.535;   // 52%..55%
    public static final float SPLIT_BEAM_TILT_DEGREES = 4.0f;    // 4-degree Art Deco brass split beam
    public static final double SHORT_POV_LEAD_SECONDS = 1.5;     // killer POV starts 1.5s before kill
    public static final double TIER1_HIGHLIGHT_SCALE = 1.65;     // 1.65x focal actor boost
    public static final float TIER1_DIM_OTHERS_ALPHA = 0.42f;    // 42% dim/desat on non-focal actors
    public static final double TIER3_PRE_SECONDS = 3.0;
    public static final double TIER3_SLOW_RATE = 0.32;
    public static final double TIER4_FREEZE_SECONDS = 0.35;
    public static final double TIER5_PRE_SECONDS = 4.0;
    public static final double TIER5_APEX_FREEZE_SECONDS = 0.40;
    public static final double TIER5_ENCORE_START_RATE = 0.40;
    public static final double TIER5_ENCORE_END_RATE = 1.00;
    private static final double TRANSITION_DURATION = 0.50;
    private static final double DEAD_GAP_SKIP_SHORT_TICKS = 900.0;   // 45s quiet gap -> brass skip in Short mode
    private static final double DEAD_GAP_SKIP_PROMO_TICKS = 720.0;   // 36s quiet gap -> chapter skip in Promo mode
    private static final double DEAD_GAP_SKIP_SANDBOX_TICKS = 1500.0; // 75s quiet gap -> skip in Sandbox Full

    private ReplayCinemaDirector() {}

    /** High-level camera rig directive for each directed segment. */
    public enum CameraDirective {
        DIORAMA_OVERVIEW,
        DIORAMA_FOLLOW,
        DIORAMA_CLOSEUP,
        POV_FIRST_PERSON,
        POV_OVER_SHOULDER,
        POV_HERO_SIDE,
        POV_ORBIT_360,
        SPLIT_DIORAMA_AND_POV,
        SPLIT_HERO_SIDE_AND_POV
    }

    /** Semantic role of one contiguous timeline segment inside the ACTION act. */
    public enum SegmentKind {
        CRUISE,
        TRANSITION_CUT,
        APPROACH,
        FREEZE,
        AFTERMATH_SLOW,
        TIER1_GLIMMER,
        TIER3_OVER_SHOULDER,
        TIER3_HERO_SIDE,
        TIER4_OVER_SHOULDER,
        TIER4_IMPACT_FREEZE,
        TIER4_DUAL_EXECUTION,
        TIER4_POV_EXPAND,
        TIER5_SUCTION_DOLLY,
        TIER5_POV_NATIVE,
        TIER5_APEX_FREEZE,
        TIER5_ORBIT_ENCORE
    }

    /** Transition visual style between discontinuous clips or chapters. */
    public enum CutStyle {
        NONE,
        BRASS_SWEEP,
        TICKET_FLIP,
        CROSS_DISSOLVE
    }

    /** One contiguous directed segment inside the ACTION act. */
    public record DirectedSegment(
            int index,
            SegmentKind kind,
            CameraDirective camera,
            CutStyle cutStyle,
            double videoStart,
            double videoEnd,
            double startTick,
            double endTick,
            double startRate,
            double endRate,
            boolean instantReplay,
            boolean splitScreen,
            double splitLeftRatioStart,
            double splitLeftRatioEnd,
            double splitRightStartTick,
            double splitRightEndTick,
            @Nullable UUID povSubjectUuid,
            @Nullable ScoredHighlight highlight,
            double actorHighlightScale,
            float backgroundDimAlpha,
            double whipAngleDeg,
            double letterboxBoost,
            String stampKey
    ) {
        public double duration() { return Math.max(0.0, videoEnd - videoStart); }

        public double progressAt(double videoTime) {
            double len = duration();
            if (len <= 1e-9) return 1.0;
            return Math.clamp((videoTime - videoStart) / len, 0.0, 1.0);
        }

        /** Match tick for the primary view (left/full screen) at absolute {@code videoTime}. */
        public double tickAt(double videoTime) {
            if (kind == SegmentKind.TRANSITION_CUT) {
                return progressAt(videoTime) >= 0.5 ? endTick : startTick;
            }
            if (kind == SegmentKind.FREEZE || kind == SegmentKind.TIER4_IMPACT_FREEZE
                    || kind == SegmentKind.TIER5_APEX_FREEZE) {
                return startTick;
            }
            double p = progressAt(videoTime);
            double span = endTick - startTick;
            if (Math.abs(span) < 1e-9) return startTick;
            if (Math.abs(startRate - endRate) < 1e-4) {
                return startTick + span * p;
            }
            // Linear rate ramp r(p) = r0 + (r1 - r0)*p -> exact quadratic integral normalized to [0, 1].
            double avg = 0.5 * (startRate + endRate);
            if (avg <= 1e-6) return startTick + span * p;
            double frac = (startRate * p + 0.5 * (endRate - startRate) * p * p) / avg;
            return startTick + span * Math.clamp(frac, 0.0, 1.0);
        }

        /** Instantaneous match playback rate (1.0 = 20 ticks/s) at absolute {@code videoTime}. */
        public double speedAt(double videoTime) {
            if (kind == SegmentKind.FREEZE || kind == SegmentKind.TIER4_IMPACT_FREEZE
                    || kind == SegmentKind.TIER5_APEX_FREEZE || kind == SegmentKind.TRANSITION_CUT) {
                return 0.0;
            }
            double p = progressAt(videoTime);
            return Math.max(0.0, startRate + (endRate - startRate) * p);
        }

        /** Match tick for the right split-screen POV panel at absolute {@code videoTime}. */
        public double splitRightTickAt(double videoTime) {
            if (!splitScreen) return tickAt(videoTime);
            double p = progressAt(videoTime);
            return splitRightStartTick + (splitRightEndTick - splitRightStartTick) * p;
        }

        /** Left panel width fraction (0..1) at absolute {@code videoTime}. */
        public double splitLeftRatioAt(double videoTime) {
            if (!splitScreen) return 1.0;
            double p = progressAt(videoTime);
            double eased = ReplayStagePlan.easeInOutQuart(p);
            return splitLeftRatioStart + (splitLeftRatioEnd - splitLeftRatioStart) * eased;
        }
    }

    /** Complete frame evaluation returned by {@link DirectedFilm#sample(double)}. */
    public record CinemaSample(
            ReplayVideoScript.Sample baseSample,
            @Nullable DirectedSegment segment,
            CameraDirective camera,
            boolean splitScreen,
            double splitLeftRatio,
            double rightPovTick,
            @Nullable UUID povSubjectUuid,
            @Nullable ScoredHighlight activeHighlight,
            double highlightLocalSec,
            double actorHighlightScale,
            float backgroundDimAlpha,
            double whipAngleDeg,
            double letterboxBoost,
            boolean instantReplay,
            CutStyle activeCutStyle,
            double cutProgress,
            String stampKey
    ) {
        public ReplayVideoScript.Act act() { return baseSample.act(); }
        public double time() { return baseSample.time(); }
        public double replayTick() { return baseSample.replayTick(); }
        public double speed() { return baseSample.speed(); }
        public boolean freeze() { return baseSample.freeze(); }
        public boolean slow() { return baseSample.slow(); }
        public double world() { return baseSample.world(); }
    }

    /** Immutable directed film plan for any of the 4 export modes. */
    public static final class DirectedFilm {
        private final LastMatchReplay replay;
        private final ReplayExportContentOptions options;
        private final @Nullable UUID effectiveFocusPlayer;
        private final int trimStartTick;
        private final int trimEndTick;
        private final boolean hasMvpAct;
        private final double titleStart, titleLength;
        private final double actionStart, actionLength;
        private final double reviewStart, reviewLength;
        private final double victoryStart, victoryLength;
        private final double mvpStart, mvpLength;
        private final double outroStart, outroLength;
        private final double total;
        private final double maxCruiseSpeed;
        private final List<DirectedSegment> segments;
        private final List<ScoredHighlight> highlights;
        private final List<ReplayVideoScript.Beat> beats;
        private final List<ReplayVideoScript.Feed> feeds;
        private final List<ReplayVideoScript.SpanWindow> spans;
        private final List<ReplayStreamingAudioMixer.DirectorDuck> directorDucks;
        private final Map<UUID, Integer> firstRevealTickByPlayer;
        private final double[] worldAtSegStart;

        private DirectedFilm(LastMatchReplay replay,
                             ReplayExportContentOptions options,
                             @Nullable UUID effectiveFocusPlayer,
                             int trimStartTick,
                             int trimEndTick,
                             boolean hasMvpAct,
                             double titleLength,
                             double actionLength,
                             double reviewLength,
                             double victoryLength,
                             double mvpLength,
                             double outroLength,
                             double maxCruiseSpeed,
                             List<DirectedSegment> segments,
                             List<ScoredHighlight> highlights,
                             List<ReplayVideoScript.Beat> beats,
                             List<ReplayStreamingAudioMixer.DirectorDuck> directorDucks,
                             Map<UUID, Integer> firstRevealTickByPlayer) {
            this.replay = replay;
            this.options = options;
            this.effectiveFocusPlayer = effectiveFocusPlayer;
            this.trimStartTick = trimStartTick;
            this.trimEndTick = trimEndTick;
            this.hasMvpAct = hasMvpAct;
            this.titleStart = 0.0;
            this.titleLength = titleLength;
            this.actionStart = titleStart + titleLength;
            this.actionLength = actionLength;
            this.reviewStart = actionStart + actionLength;
            this.reviewLength = reviewLength;
            this.victoryStart = reviewStart + reviewLength;
            this.victoryLength = victoryLength;
            this.mvpStart = victoryStart + victoryLength;
            this.mvpLength = mvpLength;
            this.outroStart = mvpStart + mvpLength;
            this.outroLength = outroLength;
            this.total = Math.max(0.5, outroStart + outroLength);
            this.maxCruiseSpeed = maxCruiseSpeed;
            this.segments = List.copyOf(segments);
            this.highlights = List.copyOf(highlights);
            this.beats = List.copyOf(beats);
            this.directorDucks = List.copyOf(directorDucks);
            this.firstRevealTickByPlayer = Map.copyOf(firstRevealTickByPlayer);

            this.worldAtSegStart = new double[this.segments.size() + 1];
            double wClock = actionStart;
            for (int i = 0; i < this.segments.size(); i++) {
                this.worldAtSegStart[i] = wClock;
                DirectedSegment seg = this.segments.get(i);
                double rate = Math.clamp(0.5 * (seg.startRate() + seg.endRate()), 0.04, 1.0);
                wClock += seg.duration() * rate;
            }
            this.worldAtSegStart[this.segments.size()] = wClock;

            // Map non-featured events to feed entries on video clock.
            Set<LastMatchReplay.Node> featured = new HashSet<>();
            for (var b : this.beats) featured.add(b.node());
            List<ReplayVideoScript.Feed> feedList = new ArrayList<>();
            for (LastMatchReplay.Node node : replay.events()) {
                if (!ReplayVideoScript.feedable(node)) continue;
                if (node.tick() < trimStartTick || node.tick() > trimEndTick) continue;
                if (featured.contains(node)) continue;
                double vt = videoTimeOf(node.tick());
                if (vt >= actionStart && vt <= reviewStart) {
                    feedList.add(new ReplayVideoScript.Feed(node, vt));
                }
            }
            feedList.sort(Comparator.comparingDouble(ReplayVideoScript.Feed::time));
            this.feeds = List.copyOf(feedList);

            List<ReplayVideoScript.SpanWindow> spanList = new ArrayList<>();
            for (LastMatchReplay.Span span : replay.spans()) {
                if (span.endTick() <= trimStartTick || span.startTick() >= trimEndTick) continue;
                double s = videoTimeOf(Math.max(trimStartTick, span.startTick()));
                double e = videoTimeOf(Math.min(trimEndTick, span.endTick()));
                if (e > s) spanList.add(new ReplayVideoScript.SpanWindow(span, s, e));
            }
            this.spans = List.copyOf(spanList);
        }

        public LastMatchReplay replay() { return replay; }
        public ReplayExportContentOptions options() { return options; }
        public ExportMode mode() { return options.mode(); }
        public @Nullable UUID focusPlayer() { return effectiveFocusPlayer; }
        public int trimStartTick() { return trimStartTick; }
        public int trimEndTick() { return trimEndTick; }
        public int duration() { return Math.max(0, replay.durationTicks()); }
        public double total() { return total; }
        public int frames(int fps) { return Math.max(1, (int) Math.ceil(total * fps)); }
        public boolean hasMvpAct() { return hasMvpAct; }
        public double maxCruiseSpeed() { return maxCruiseSpeed; }
        public List<DirectedSegment> segments() { return segments; }
        public List<ScoredHighlight> highlights() { return highlights; }
        public List<ReplayVideoScript.Beat> beats() { return beats; }
        public List<ReplayVideoScript.Feed> feeds() { return feeds; }
        public List<ReplayVideoScript.SpanWindow> spans() { return spans; }
        public List<ReplayStreamingAudioMixer.DirectorDuck> directorDucks() { return directorDucks; }

        public int firstRevealTick(UUID actorId) {
            return firstRevealTickByPlayer.getOrDefault(actorId, -1);
        }

        public boolean isRoleRevealedAt(UUID actorId, double matchTick) {
            return options.isRoleRevealedAt(actorId, matchTick, firstRevealTick(actorId));
        }

        public double actStart(ReplayVideoScript.Act act) {
            return switch (act) {
                case TITLE -> titleStart;
                case ACTION -> actionStart;
                case REVIEW -> reviewStart;
                case VICTORY -> victoryStart;
                case MVP -> mvpStart;
                case OUTRO -> outroStart;
            };
        }

        public double actLength(ReplayVideoScript.Act act) {
            return switch (act) {
                case TITLE -> titleLength;
                case ACTION -> actionLength;
                case REVIEW -> reviewLength;
                case VICTORY -> victoryLength;
                case MVP -> mvpLength;
                case OUTRO -> outroLength;
            };
        }

        public ReplayVideoScript.Act actAt(double t) {
            if (titleLength > 0 && t < actionStart) return ReplayVideoScript.Act.TITLE;
            if (t < reviewStart) return ReplayVideoScript.Act.ACTION;
            if (reviewLength > 0 && t < victoryStart) return ReplayVideoScript.Act.REVIEW;
            if (victoryLength > 0 && t < mvpStart) return ReplayVideoScript.Act.VICTORY;
            if (mvpLength > 0 && t < outroStart) return ReplayVideoScript.Act.MVP;
            if (outroLength > 0) return ReplayVideoScript.Act.OUTRO;
            return ReplayVideoScript.Act.ACTION;
        }

        /** First video timestamp at which the primary timeline reaches {@code matchTick}. */
        public double videoTimeOf(double matchTick) {
            if (segments.isEmpty()) return actionStart;
            if (matchTick <= trimStartTick) return actionStart;
            for (DirectedSegment seg : segments) {
                if (seg.instantReplay() || seg.kind() == SegmentKind.TRANSITION_CUT) continue;
                double lo = Math.min(seg.startTick(), seg.endTick());
                double hi = Math.max(seg.startTick(), seg.endTick());
                if (matchTick >= lo - 1e-6 && matchTick <= hi + 1e-6) {
                    if (hi - lo < 1e-6) return seg.videoStart();
                    double loT = seg.videoStart(), hiT = seg.videoEnd();
                    for (int it = 0; it < 24; it++) {
                        double mid = 0.5 * (loT + hiT);
                        if (seg.tickAt(mid) < matchTick) loT = mid; else hiT = mid;
                    }
                    return hiT;
                }
                if (seg.startTick() > matchTick) {
                    return seg.videoStart();
                }
            }
            return reviewStart;
        }

        public double eventTime(LastMatchReplay.Node node) {
            for (ReplayVideoScript.Beat b : beats) {
                if (b.node() == node || (node.eventId() > 0 && b.node().eventId() == node.eventId())) {
                    return b.impact();
                }
            }
            return videoTimeOf(node.tick());
        }

        public CinemaSample sample(double time) {
            double t = Math.clamp(time, 0.0, Math.nextDown(total));
            ReplayVideoScript.Act act = actAt(t);
            double actStartT = actStart(act);
            double actLen = actLength(act);
            double actTime = Math.max(0.0, t - actStartT);
            double wipe = wipeAt(t);

            if (act != ReplayVideoScript.Act.ACTION || segments.isEmpty()) {
                double tick = act == ReplayVideoScript.Act.TITLE ? trimStartTick : trimEndTick;
                double world = act == ReplayVideoScript.Act.TITLE ? t : worldAtSegStart[segments.size()] + (t - reviewStart);
                var base = new ReplayVideoScript.Sample(act, t, actTime, actLen, tick, 0.0, false, false, world, wipe);
                return new CinemaSample(base, null, CameraDirective.DIORAMA_OVERVIEW, false, 1.0, tick,
                        effectiveFocusPlayer, null, -999.0, 1.0, 1.0f, 0.0, 0.0, false, CutStyle.NONE, -1.0, "");
            }

            DirectedSegment seg = segmentAt(t);
            double tick = seg.tickAt(t);
            double speed = seg.speedAt(t);
            double world = worldAt(seg, t);
            boolean freeze = speed < 0.02 && seg.kind() != SegmentKind.TRANSITION_CUT;
            boolean slow = !freeze && speed > 0.02 && speed < 0.75;
            var base = new ReplayVideoScript.Sample(act, t, actTime, actLen, tick, speed, slow, freeze, world, wipe);

            double highlightLocal = -999.0;
            if (seg.highlight() != null) {
                double impactT = videoTimeOfHighlightImpact(seg.highlight());
                highlightLocal = t - impactT;
            }
            double cutProgress = seg.kind() == SegmentKind.TRANSITION_CUT ? seg.progressAt(t) : -1.0;

            return new CinemaSample(
                    base,
                    seg,
                    seg.camera(),
                    seg.splitScreen(),
                    seg.splitLeftRatioAt(t),
                    seg.splitRightTickAt(t),
                    seg.povSubjectUuid(),
                    seg.highlight(),
                    highlightLocal,
                    seg.actorHighlightScale(),
                    seg.backgroundDimAlpha(),
                    seg.whipAngleDeg(),
                    seg.letterboxBoost(),
                    seg.instantReplay(),
                    seg.cutStyle(),
                    cutProgress,
                    seg.stampKey()
            );
        }

        public ReplayStagePlan.Stage stage(ReplayVideoScript.Sample s) {
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

        public ReplayTimeline.Frame frame(ReplayVideoScript.Sample s) {
            return switch (s.act()) {
                case TITLE -> new ReplayTimeline.Frame(ReplayTimeline.Phase.BUILD, s.actProgress(), trimStartTick, false);
                case ACTION -> new ReplayTimeline.Frame(ReplayTimeline.Phase.PLAY, s.actProgress(), s.replayTick(), s.slow() || s.freeze());
                case REVIEW -> new ReplayTimeline.Frame(ReplayTimeline.Phase.REVIEW, s.actProgress(), trimEndTick, false);
                default -> new ReplayTimeline.Frame(ReplayTimeline.Phase.HONORS, s.actProgress(), trimEndTick, false);
            };
        }

        private DirectedSegment segmentAt(double t) {
            int lo = 0, hi = segments.size() - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (segments.get(mid).videoEnd() <= t) lo = mid + 1;
                else hi = mid;
            }
            return segments.get(lo);
        }

        private double worldAt(DirectedSegment seg, double t) {
            double base = worldAtSegStart[seg.index()];
            double rate = Math.clamp(0.5 * (seg.startRate() + seg.endRate()), 0.04, 1.0);
            return base + Math.max(0.0, t - seg.videoStart()) * rate;
        }

        private double videoTimeOfHighlightImpact(ScoredHighlight h) {
            for (ReplayVideoScript.Beat b : beats) {
                if (b.node() == h.primaryNode()) return b.impact();
            }
            return videoTimeOf(h.tick());
        }

        private double wipeAt(double t) {
            double[] bounds = {actionStart, reviewStart, victoryStart, mvpStart, outroStart};
            for (double b : bounds) {
                if (b <= 0.01 || b >= total - 0.01) continue;
                if (Math.abs(t - b) <= ReplayVideoScript.WIPE) {
                    return (t - (b - ReplayVideoScript.WIPE)) / (2.0 * ReplayVideoScript.WIPE);
                }
            }
            return -1.0;
        }
    }

    // =================================================================== public entry points

    /** Directs a complete film for {@code replay} according to {@code options} and audio telemetry. */
    public static DirectedFilm direct(LastMatchReplay replay,
                                      @Nullable ReplayVoiceData voice,
                                      ReplayExportContentOptions options) {
        ReplayExportContentOptions opts = options == null ? ReplayExportContentOptions.defaultsFor(ExportMode.SHORT) : options;
        int duration = Math.max(0, replay.durationTicks());
        ReplayExportContentOptions.TrimRange trim = opts.resolveTrimRange(duration);
        UUID focus = resolveEffectiveFocus(replay, opts);

        int startTick = trim.startTick();
        int endTick = trim.endTick();
        ReplayHighlightScorer.AcousticSignal acoustic = ReplayHighlightScorer.fromReplay(replay, voice);
        boolean includeMvp = opts.includeVictoryMvp()
                && (replay.mvp() != null || replay.participants().stream().anyMatch(LastMatchReplay.Participant::winner));
        double titleLen = opts.includeTitle() ? ReplayVideoScript.TITLE : 0.0;
        double reviewLen = opts.includeReview() ? ReplayVideoScript.REVIEW : 0.0;
        double victoryLen = opts.includeVictoryMvp() ? ReplayVideoScript.VICTORY : 0.0;
        double mvpLen = includeMvp ? ReplayVideoScript.MVP : 0.0;
        int outroPages = Math.max(1, (replay.participants().size() + ReplayVideoScript.OUTRO_PAGE_SIZE - 1) / ReplayVideoScript.OUTRO_PAGE_SIZE);
        double outroLen = !opts.includeOutro() ? 0.0
                : (outroPages <= 1 ? ReplayVideoScript.OUTRO : 1.1 + outroPages * ReplayVideoScript.OUTRO_PAGE_HOLD + 0.9);

        List<DirectedSegment> rawSegments = new ArrayList<>();
        List<ScoredHighlight> highlights = new ArrayList<>();
        List<ReplayVideoScript.Beat> rawBeats = new ArrayList<>();
        List<ReplayStreamingAudioMixer.DirectorDuck> rawDucks = new ArrayList<>();
        double maxCruiseSpeed;

        switch (opts.mode()) {
            case SHORT -> maxCruiseSpeed = buildShortMode(replay, opts, focus, acoustic, startTick, endTick,
                    rawSegments, highlights, rawBeats);
            case PERSONAL -> maxCruiseSpeed = buildPersonalMode(replay, opts, focus, acoustic, startTick, endTick,
                    rawSegments, highlights, rawBeats);
            case SANDBOX_FULL -> maxCruiseSpeed = buildSandboxFullMode(replay, opts, focus, acoustic, startTick, endTick,
                    rawSegments, highlights, rawBeats);
            case CINEMATIC_PROMO -> maxCruiseSpeed = buildCinematicPromoMode(replay, opts, focus, acoustic, startTick, endTick,
                    rawSegments, highlights, rawBeats, rawDucks);
            default -> throw new IllegalStateException("Unexpected mode: " + opts.mode());
        }

        if (rawSegments.isEmpty()) {
            rawSegments.add(cruiseSegment(0, CameraDirective.DIORAMA_OVERVIEW, 0.0, 2.0,
                    startTick, endTick, 1.0, focus));
        }

        // Shift all action-relative times by titleLen (actionStart).
        double actionLen = rawSegments.getLast().videoEnd();
        List<DirectedSegment> shiftedSegments = new ArrayList<>(rawSegments.size());
        for (int i = 0; i < rawSegments.size(); i++) {
            DirectedSegment s = rawSegments.get(i);
            shiftedSegments.add(new DirectedSegment(
                    i, s.kind(), s.camera(), s.cutStyle(),
                    s.videoStart() + titleLen, s.videoEnd() + titleLen,
                    s.startTick(), s.endTick(), s.startRate(), s.endRate(),
                    s.instantReplay(), s.splitScreen(),
                    s.splitLeftRatioStart(), s.splitLeftRatioEnd(),
                    s.splitRightStartTick(), s.splitRightEndTick(),
                    s.povSubjectUuid(), s.highlight(),
                    s.actorHighlightScale(), s.backgroundDimAlpha(),
                    s.whipAngleDeg(), s.letterboxBoost(), s.stampKey()
            ));
        }
        List<ReplayVideoScript.Beat> shiftedBeats = new ArrayList<>(rawBeats.size());
        for (int i = 0; i < rawBeats.size(); i++) {
            ReplayVideoScript.Beat b = rawBeats.get(i);
            shiftedBeats.add(new ReplayVideoScript.Beat(
                    i, b.type(), b.node(), b.tick(),
                    b.approach() + titleLen, b.impact() + titleLen, b.release() + titleLen, b.end() + titleLen,
                    b.streak(), b.firstBlood(), b.finalBlow(), b.kills()
            ));
        }
        List<ReplayStreamingAudioMixer.DirectorDuck> shiftedDucks = new ArrayList<>(rawDucks.size());
        for (var d : rawDucks) {
            shiftedDucks.add(new ReplayStreamingAudioMixer.DirectorDuck(
                    d.startSec() + titleLen, d.endSec() + titleLen, d.targetGain(), d.attackSec(), d.releaseSec()));
        }

        Map<UUID, Integer> revealTicks = computeFirstRevealTicks(replay);
        return new DirectedFilm(
                replay, opts, focus, startTick, endTick, includeMvp,
                titleLen, actionLen, reviewLen, victoryLen, mvpLen, outroLen,
                maxCruiseSpeed, shiftedSegments, highlights, shiftedBeats, shiftedDucks, revealTicks
        );
    }

    /**
     * Builds the sample-accurate streaming {@link ReplayStreamingAudioMixer.MixPlan} for {@code film},
     * integrating {@code HRVC v2} multi-track voice + {@link ReplayVoiceAudibilityResolver},
     * server-recorded {@link ReplayGameSoundData}, WSOLA pitch-preserving time-stretch, and Auto-Ducking.
     */
    public static ReplayStreamingAudioMixer.MixPlan buildAudioMixPlan(
            DirectedFilm film,
            @Nullable ReplayVoiceData voice,
            int totalVideoFrames,
            int fps,
            Predicate<net.minecraft.resources.ResourceLocation> availableRecordedFile) {
        long totalStereoFrames = Math.max(0L, (long) totalVideoFrames * (ReplayStreamingAudioMixer.SAMPLE_RATE / Math.max(1, fps)));
        ReplayExportContentOptions opts = film.options();
        LastMatchReplay replay = film.replay();
        ReplayVoiceData effectiveVoice = voice == null ? ReplayVoiceData.EMPTY : voice;
        ReplayVoiceAudibilityResolver.Context audibility = ReplayVoiceAudibilityResolver.Context.of(replay, effectiveVoice);

        List<ReplayStreamingAudioMixer.SfxEvent> sfxEvents = new ArrayList<>();
        List<ReplayStreamingAudioMixer.VoiceSegment> voiceSegments = new ArrayList<>();

        // Opening horn & victory piano (respecting title/victory toggles and music override).
        if (opts.includeTitle()) {
            sfxEvents.add(new ReplayStreamingAudioMixer.SfxEvent(
                    film.actStart(ReplayVideoScript.Act.TITLE),
                    ReplaySoundCuePlan.VIDEO_MATCH_START_SOUNDS, 0.85f, 1.0f, 0f));
        }
        if (opts.includeVictoryMvp() && !opts.musicEnabled()) {
            sfxEvents.add(new ReplayStreamingAudioMixer.SfxEvent(
                    film.actStart(ReplayVideoScript.Act.VICTORY),
                    ReplaySoundCuePlan.VIDEO_MATCH_END_SOUNDS, 1.05f, 1.0f, 0f));
        }

        boolean hasServerSounds = !replay.gameSounds().isEmpty();
        boolean hasMultitrackVoice = effectiveVoice.hasMultitrack();
        ReplaySoundCuePlan.Schedule legacySchedule = hasServerSounds
                ? null : ReplaySoundCuePlan.forLinearReplay(replay, null, availableRecordedFile);

        // Deduplicate sound cues across contiguous segment boundaries & FREEZE frames (so boundary ticks
        // fire once at exact impact instead of repeating across APPROACH -> FREEZE -> AFTERMATH_SLOW).
        // Intentional instant-replay segments (Tier 4 POV expand, Tier 5 360° encore) use a local dedup set.
        Set<ReplayGameSoundData.Cue> emittedServerSounds = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ReplaySoundCuePlan.SoundCue> emittedLegacyCues = Collections.newSetFromMap(new IdentityHashMap<>());
        double voiceHighWaterTick = film.trimStartTick();

        // Schedule game sounds & multi-track voices segment-by-segment so slow-mo, freeze, 2x/4x,
        // split-screen killer POV, and Tier 4/5 instant replays align with exact sample accuracy!
        for (DirectedSegment seg : film.segments()) {
            if (seg.kind() == SegmentKind.TRANSITION_CUT || seg.duration() <= 0.02) continue;

            // Choose audio listener context for this segment.
            UUID listenerUuid = resolveAudioListenerForSegment(film, seg);
            double segMatchStartTick = audioStartTick(seg);
            double segMatchEndTick = audioEndTick(seg);
            Set<ReplayGameSoundData.Cue> serverDedup = seg.instantReplay()
                    ? Collections.newSetFromMap(new IdentityHashMap<>()) : emittedServerSounds;
            Set<ReplaySoundCuePlan.SoundCue> legacyDedup = seg.instantReplay()
                    ? Collections.newSetFromMap(new IdentityHashMap<>()) : emittedLegacyCues;

            if (seg.kind() == SegmentKind.FREEZE || seg.kind() == SegmentKind.TIER4_IMPACT_FREEZE
                    || seg.kind() == SegmentKind.TIER5_APEX_FREEZE) {
                // During brief freeze frames, emit any exact-impact sound that was NOT already emitted by a
                // preceding approach segment (e.g. when approach was zero-length), preventing double/triple fire.
                if (seg.highlight() != null) {
                    if (hasServerSounds) {
                        int impactMs = Math.max(0, (int) Math.round(seg.highlight().tick() * 50.0));
                        for (var s : audibility.resolveGameSoundsInWindow(listenerUuid, Math.max(0, impactMs - 25), impactMs + 25)) {
                            if (!serverDedup.add(s.cue())) continue;
                            sfxEvents.add(new ReplayStreamingAudioMixer.SfxEvent(
                                    Math.clamp(seg.videoStart(), 0.0, film.total()),
                                    List.of(s.soundId()),
                                    s.gain(),
                                    s.cue().pitch(),
                                    s.pan(),
                                    0f
                            ));
                        }
                    } else {
                        emitFallbackImpactSfxIfNeeded(sfxEvents, legacySchedule, seg, legacyDedup);
                    }
                }
                continue;
            }

            double avgSpeed = Math.max(0.25, 0.5 * (seg.startRate() + seg.endRate()));
            int winStartMs = Math.max(0, (int) Math.floor(segMatchStartTick * 50.0));
            int winEndMs = Math.max(winStartMs, (int) Math.ceil(segMatchEndTick * 50.0));

            // 1. Server-recorded global game sounds (or fallback synthesized cues if v1 archive)
            if (hasServerSounds) {
                List<ReplayVoiceAudibilityResolver.AudibleSound> sounds =
                        audibility.resolveGameSoundsInWindow(listenerUuid, winStartMs, winEndMs);
                double lastMinorSfxVideoSec = -10.0;
                for (var s : sounds) {
                    double relTick = s.cue().matchMs() / 50.0;
                    if (relTick < segMatchStartTick || relTick > segMatchEndTick || !serverDedup.add(s.cue())) continue;
                    double vTime = mapTickToSegmentVideoTime(seg, relTick);
                    if (vTime < seg.videoStart() - 0.02 || vTime > seg.videoEnd() + 0.02) continue;
                    // At 2x..5x speedup, thin dense footsteps/minor clicks while keeping all combat/skills crisp.
                    boolean highPriority = isPrioritySound(s.soundId(), s.cue().volume());
                    if (avgSpeed >= 1.8 && !highPriority && vTime - lastMinorSfxVideoSec < 0.075) {
                        continue;
                    }
                    if (!highPriority) lastMinorSfxVideoSec = vTime;
                    float pitch = s.cue().pitch();
                    if (avgSpeed < 0.65) {
                        // Subtle cinematic pitch weight in slow-motion SFX (while voices stay WSOLA pitch-locked!).
                        pitch = Math.clamp(pitch * (float) (0.76 + 0.24 * avgSpeed), 0.50f, 2.0f);
                    }
                    float maxDur = avgSpeed >= 2.0 ? (float) Math.max(0.25, 1.4 / avgSpeed) : 0f;
                    sfxEvents.add(new ReplayStreamingAudioMixer.SfxEvent(
                            Math.clamp(vTime, 0.0, film.total()),
                            List.of(s.soundId()),
                            s.gain(),
                            pitch,
                            s.pan(),
                            maxDur
                    ));
                }
            } else {
                emitLegacyEventsInSegment(sfxEvents, legacySchedule, seg, legacyDedup);
            }

            // 2. Multi-track HRVC v2 voices (concurrent spatial mixing + WSOLA pitch preservation!)
            if (hasMultitrackVoice) {
                double voiceStartTick = seg.instantReplay()
                        ? segMatchStartTick : Math.max(segMatchStartTick, voiceHighWaterTick);
                int voiceStartMs = Math.max(0, (int) Math.floor(voiceStartTick * 50.0));
                if (segMatchEndTick > voiceStartTick + 1e-3) {
                    var audibleVoices = audibility.resolveVoiceSlices(listenerUuid, voiceStartMs, winEndMs);
                    for (var slice : audibleVoices) {
                        var av = slice.voice();
                        var utt = av.utterance();
                        double uttStartTick = utt.startMs() / 50.0;
                        double clippedStartTick = Math.max(voiceStartTick, slice.startMs() / 50.0);
                        double clippedEndTick = Math.min(segMatchEndTick, slice.endMs() / 50.0);
                        if (clippedEndTick <= clippedStartTick) continue;

                        double vStart = mapTickToSegmentVideoTime(seg, clippedStartTick);
                        double vEnd = mapTickToSegmentVideoTime(seg, clippedEndTick);
                        if (vEnd <= vStart) continue;

                        double sourceOffsetSec = Math.max(0.0, (clippedStartTick - uttStartTick) / TICKS_PER_SECOND);
                        double effectiveSpeechSpeed = ReplayWsolaTimeStretch.clampSpeed(
                                ((clippedEndTick - clippedStartTick) / TICKS_PER_SECOND) / Math.max(0.05, vEnd - vStart));
                        // Cap speech stretch gently so even 5.0x Short cruise stays intelligible (<= 4.0x WSOLA).
                        boolean useWsola = Math.abs(effectiveSpeechSpeed - 1.0) > 0.02;
                        float voiceGain = av.isSelf() ? 1.05f : Math.clamp(av.gain() * 1.05f, 0.05f, 1.25f);
                        float pan = av.isSelf() ? 0.0f : av.pan();
                        int sourceEnd = (int) Math.round((clippedEndTick - uttStartTick) / TICKS_PER_SECOND
                                * ReplayVoiceData.VOICECHAT_SAMPLE_RATE);

                        voiceSegments.add(new ReplayStreamingAudioMixer.VoiceSegment(
                                utt.speakerId(),
                                vStart,
                                vEnd,
                                sourceOffsetSec,
                                effectiveSpeechSpeed,
                                voiceGain,
                                pan,
                                useWsola,
                                () -> {
                                    short[] samples = utt.decode48k();
                                    return sourceEnd < samples.length ? Arrays.copyOf(samples, sourceEnd) : samples;
                                }
                        ));
                    }
                }
            }
            if (!seg.instantReplay()) {
                voiceHighWaterTick = Math.max(voiceHighWaterTick, segMatchEndTick);
            }
        }

        // Fallback legacy HRVC v1 voice clips when v2 utterances are absent.
        if (!hasMultitrackVoice && !effectiveVoice.clips().isEmpty()) {
            addLegacyVoiceClips(voiceSegments, film, effectiveVoice);
        }

        return new ReplayStreamingAudioMixer.MixPlan(
                totalStereoFrames,
                sfxEvents,
                voiceSegments,
                film.directorDucks(),
                opts.voiceVolume(),
                opts.sfxVolume(),
                opts.musicEnabled() ? 1.0f : 0.0f, // music bus already carries level.gain
                opts.autoDucking(),
                ReplayStreamingAudioMixer.DEFAULT_MASTER_GAIN
        );
    }

    // =================================================================== Mode 1: Short Mode (Split Screen)

    private static double buildShortMode(LastMatchReplay replay,
                                         ReplayExportContentOptions opts,
                                         @Nullable UUID focus,
                                         ReplayHighlightScorer.AcousticSignal acoustic,
                                         int startTick,
                                         int endTick,
                                         List<DirectedSegment> outSegs,
                                         List<ScoredHighlight> outHighlights,
                                         List<ReplayVideoScript.Beat> outBeats) {
        // Short Mode uses compact split-screen beats (~33 match ticks each), so cap allocation at Tier 2
        // (75-tick / 3.75s NMS separation) instead of borrowing Promo Tier 3/4/5's 110..200-tick suppression radii.
        var quota = new ReplayHighlightScorer.PyramidQuota(0, 0, 0, 10, 0);
        List<ScoredHighlight> scored = ReplayHighlightScorer.scoreAndAllocate(
                replay, acoustic, focus, startTick, endTick, quota);
        if (scored.isEmpty()) {
            // Fallback for ultra-quiet matches where a single kill scores below Tier 2's bar.
            scored = ReplayHighlightScorer.scoreAndAllocate(
                            replay, acoustic, focus, startTick, endTick,
                            new ReplayHighlightScorer.PyramidQuota(0, 0, 0, 0, 6))
                    .stream()
                    .filter(h -> h.beatType() != ReplayVideoScript.BeatType.HIT)
                    .toList();
        }
        outHighlights.addAll(scored);

        int span = Math.max(0, endTick - startTick);
        // Readable cruise speed <= 5.0x (fixes Defect #1: never 46x!).
        double cruiseSpeed = span <= 0 ? 1.0 : Math.clamp(span / TICKS_PER_SECOND / 28.0, 1.5, MAX_SHORT_CRUISE_SPEED);
        CameraDirective baseCam = focus != null ? CameraDirective.DIORAMA_FOLLOW : CameraDirective.DIORAMA_OVERVIEW;

        double v = 0.0;
        double cursor = startTick;

        for (ScoredHighlight h : scored) {
            int tb = h.tick();
            if (tb < cursor - 1.0) continue;
            boolean splitKill = opts.splitScreenKillPov()
                    && h.beatType() == ReplayVideoScript.BeatType.KILL
                    && h.primaryActor() != null;

            double leadTicks = splitKill ? 24.0 : 14.0;
            double approachStartTick = safeWindowStart(tb - leadTicks, cursor, tb);

            v = appendCruiseWithSmartSkip(outSegs, baseCam, focus, v, cursor, approachStartTick,
                    cruiseSpeed, DEAD_GAP_SKIP_SHORT_TICKS, CutStyle.BRASS_SWEEP);

            // Approach segment
            double approachDur = Math.max(0.55, (tb - approachStartTick) / TICKS_PER_SECOND / 0.85);
            double approachStartV = v;
            if (tb > approachStartTick + 0.5) {
                if (splitKill) {
                    double povStart = Math.max(startTick, tb - SHORT_POV_LEAD_SECONDS * TICKS_PER_SECOND);
                    double povMid = Math.min(endTick, tb);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.APPROACH, CameraDirective.SPLIT_DIORAMA_AND_POV, CutStyle.NONE,
                            v, v + approachDur, approachStartTick, tb, 0.95, 0.45,
                            false, true, SHORT_SPLIT_LEFT_RATIO, SHORT_SPLIT_LEFT_RATIO,
                            povStart, povMid, h.primaryActor(), h, 1.0, 1.0f, 0.0, 0.9, ""
                    ));
                } else {
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.APPROACH, CameraDirective.DIORAMA_CLOSEUP, CutStyle.NONE,
                            v, v + approachDur, approachStartTick, tb, 1.0, 0.50,
                            false, false, 1.0, 1.0, tb, tb, h.primaryActor(), h, 1.0, 1.0f, 0.0, 0.85, ""
                    ));
                }
                v += approachDur;
            }

            // Impact freeze
            double impactV = v;
            double freezeDur = h.beatType().freeze;
            double releaseV = impactV + freezeDur;
            outSegs.add(new DirectedSegment(
                    outSegs.size(), SegmentKind.FREEZE,
                    splitKill ? CameraDirective.SPLIT_DIORAMA_AND_POV : CameraDirective.DIORAMA_CLOSEUP,
                    CutStyle.NONE, impactV, releaseV, tb, tb, 0.0, 0.0,
                    false, splitKill, SHORT_SPLIT_LEFT_RATIO, SHORT_SPLIT_LEFT_RATIO,
                    tb, tb, h.primaryActor(), h, 1.0, 1.0f, 0.0, 1.0, stampKeyFor(h)
            ));
            v = releaseV;

            // Slow-mo aftermath (right panel shows killer follow-through/escape cleanly)
            double slowDur = Math.max(1.1, h.beatType().slow);
            double slowTicks = slowDur * TICKS_PER_SECOND * h.beatType().slowRate;
            double afterEndTick = Math.min(endTick, tb + slowTicks);
            double povEscapeEnd = Math.min(endTick, tb + Math.max(slowTicks, 26.0));
            outSegs.add(new DirectedSegment(
                    outSegs.size(), SegmentKind.AFTERMATH_SLOW,
                    splitKill ? CameraDirective.SPLIT_DIORAMA_AND_POV : CameraDirective.DIORAMA_CLOSEUP,
                    CutStyle.NONE, v, v + slowDur, tb, afterEndTick,
                    h.beatType().slowRate, h.beatType().slowRate,
                    false, splitKill, SHORT_SPLIT_LEFT_RATIO, SHORT_SPLIT_LEFT_RATIO,
                    tb, povEscapeEnd, h.primaryActor(), h, 1.0, 1.0f, 0.0, 0.95, ""
            ));
            v += slowDur;

            outBeats.add(new ReplayVideoScript.Beat(
                    outBeats.size(), h.beatType(), h.primaryNode(), tb,
                    approachStartV, impactV, releaseV, v,
                    h.streak(), h.firstBlood(), h.finalBlow(), h.clusterKills()
            ));
            cursor = afterEndTick;
        }

        v = appendCruiseWithSmartSkip(outSegs, baseCam, focus, v, cursor, endTick,
                cruiseSpeed, DEAD_GAP_SKIP_SHORT_TICKS, CutStyle.BRASS_SWEEP);
        // Short closing settle hold
        outSegs.add(new DirectedSegment(
                outSegs.size(), SegmentKind.FREEZE, baseCam, CutStyle.NONE,
                v, v + 0.8, endTick, endTick, 0.0, 0.0,
                false, false, 1.0, 1.0, endTick, endTick, focus, null, 1.0, 1.0f, 0.0, 0.0, ""
        ));
        return cruiseSpeed;
    }

    // =================================================================== Mode 2: Personal Replay Mode

    private static double buildPersonalMode(LastMatchReplay replay,
                                            ReplayExportContentOptions opts,
                                            @Nullable UUID focus,
                                            ReplayHighlightScorer.AcousticSignal acoustic,
                                            int startTick,
                                            int endTick,
                                            List<DirectedSegment> outSegs,
                                            List<ScoredHighlight> outHighlights,
                                            List<ReplayVideoScript.Beat> outBeats) {
        CameraDirective cam = opts.personalView() == PersonalView.TRUE_POV && focus != null
                ? CameraDirective.POV_FIRST_PERSON : CameraDirective.DIORAMA_FOLLOW;
        PersonalPacing pacing = opts.personalPacing();

        if (pacing == PersonalPacing.HIGHLIGHTS_ONLY && focus != null) {
            List<PersonalClip> clips = ReplayHighlightScorer.selectPersonalHighlights(
                    replay, focus, acoustic, startTick, endTick);
            double v = 0.0;
            for (int i = 0; i < clips.size(); i++) {
                PersonalClip clip = clips.get(i);
                if (i > 0) {
                    // Ticket-flip / brass-sweep transition between 1x personal highlight clips.
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TRANSITION_CUT, cam, CutStyle.TICKET_FLIP,
                            v, v + 0.55, clips.get(i - 1).endTick(), clip.startTick(), 0.0, 0.0,
                            false, false, 1.0, 1.0, clip.startTick(), clip.startTick(), focus,
                            null, 1.0, 1.0f, 0.0, 0.0, ""
                    ));
                    v += 0.55;
                }
                double dur = Math.max(0.5, (clip.endTick() - clip.startTick()) / TICKS_PER_SECOND);
                outSegs.add(new DirectedSegment(
                        outSegs.size(), SegmentKind.CRUISE, cam, CutStyle.NONE,
                        v, v + dur, clip.startTick(), clip.endTick(), 1.0, 1.0,
                        false, false, 1.0, 1.0, clip.startTick(), clip.endTick(), focus,
                        null, 1.0, 1.0f, 0.0, 0.0, ""
                ));
                v += dur;
            }
            return 1.0;
        }

        // Continuous global personal playback at exact 1x, 2x, or 4x speed!
        double speed = Math.clamp(pacing.speedMultiplier, 1.0, 4.0);
        double dur = Math.max(1.0, (endTick - startTick) / TICKS_PER_SECOND / speed);
        outSegs.add(new DirectedSegment(
                0, SegmentKind.CRUISE, cam, CutStyle.NONE,
                0.0, dur, startTick, endTick, speed, speed,
                false, false, 1.0, 1.0, startTick, endTick, focus,
                null, 1.0, 1.0f, 0.0, 0.0, ""
        ));
        return speed;
    }

    // =================================================================== Mode 3: Sandbox Full-Fidelity Mode

    private static double buildSandboxFullMode(LastMatchReplay replay,
                                               ReplayExportContentOptions opts,
                                               @Nullable UUID focus,
                                               ReplayHighlightScorer.AcousticSignal acoustic,
                                               int startTick,
                                               int endTick,
                                               List<DirectedSegment> outSegs,
                                               List<ScoredHighlight> outHighlights,
                                               List<ReplayVideoScript.Beat> outBeats) {
        var baseQuota = ReplayHighlightScorer.PyramidQuota.forMatch(
                Math.max(1, endTick - startTick), replay.participants().size(), focus != null);
        int sandboxMax = Math.clamp(baseQuota.tier5Max() + baseQuota.tier4Max()
                + baseQuota.tier3Max() + baseQuota.tier2Max(), 4, 12);
        var quota = new ReplayHighlightScorer.PyramidQuota(0, 0, 0, sandboxMax, 0);
        List<ScoredHighlight> scored = ReplayHighlightScorer.scoreAndAllocate(
                replay, acoustic, focus, startTick, endTick, quota);
        outHighlights.addAll(scored);

        double spanSec = Math.max(1.0, (endTick - startTick) / TICKS_PER_SECOND);
        double cruiseSpeed = Math.clamp(spanSec / 90.0, 1.0, MAX_SANDBOX_CRUISE_SPEED);
        boolean sandboxPov = opts.personalView() == PersonalView.TRUE_POV && focus != null;
        CameraDirective baseCam = sandboxPov ? CameraDirective.POV_FIRST_PERSON
                : (focus != null ? CameraDirective.DIORAMA_FOLLOW : CameraDirective.DIORAMA_OVERVIEW);
        CameraDirective focusCam = sandboxPov ? CameraDirective.POV_FIRST_PERSON : CameraDirective.DIORAMA_CLOSEUP;

        double v = 0.0;
        double cursor = startTick;
        for (ScoredHighlight h : scored) {
            if (h.tier().level < 2) continue;
            if (h.tick() < cursor - 1.0) continue;
            double focusStart = safeWindowStart(h.tick() - 30.0, cursor, h.tick());
            double focusEnd = Math.min(endTick, h.tick() + 30.0);
            v = appendCruiseWithSmartSkip(outSegs, baseCam, focus, v, cursor, focusStart,
                    cruiseSpeed, DEAD_GAP_SKIP_SANDBOX_TICKS, CutStyle.CROSS_DISSOLVE);

            double approachV = v;
            double preDur = Math.max(0.5, (h.tick() - focusStart) / TICKS_PER_SECOND);
            UUID segSubject = sandboxPov ? focus : h.primaryActor();
            outSegs.add(new DirectedSegment(
                    outSegs.size(), SegmentKind.APPROACH, focusCam, CutStyle.NONE,
                    v, v + preDur, focusStart, h.tick(), 1.0, 0.70,
                    false, false, 1.0, 1.0, focusStart, h.tick(), segSubject, h, 1.0, 1.0f, 0.0, 0.4, ""
            ));
            v += preDur;
            double impactV = v;

            double postDur = Math.max(0.8, (focusEnd - h.tick()) / TICKS_PER_SECOND / 0.75);
            outSegs.add(new DirectedSegment(
                    outSegs.size(), SegmentKind.AFTERMATH_SLOW, focusCam, CutStyle.NONE,
                    v, v + postDur, h.tick(), focusEnd, 0.70, 0.85,
                    false, false, 1.0, 1.0, h.tick(), focusEnd, segSubject, h, 1.0, 1.0f, 0.0, 0.4, ""
            ));
            v += postDur;
            outBeats.add(new ReplayVideoScript.Beat(
                    outBeats.size(), h.beatType(), h.primaryNode(), h.tick(),
                    approachV, impactV, impactV + 0.15, v,
                    h.streak(), h.firstBlood(), h.finalBlow(), h.clusterKills()
            ));
            cursor = focusEnd;
        }
        v = appendCruiseWithSmartSkip(outSegs, baseCam, focus, v, cursor, endTick,
                cruiseSpeed, DEAD_GAP_SKIP_SANDBOX_TICKS, CutStyle.CROSS_DISSOLVE);
        outSegs.add(new DirectedSegment(
                outSegs.size(), SegmentKind.FREEZE, baseCam, CutStyle.NONE,
                v, v + 0.8, endTick, endTick, 0.0, 0.0,
                false, false, 1.0, 1.0, endTick, endTick, focus, null, 1.0, 1.0f, 0.0, 0.0, ""
        ));
        return cruiseSpeed;
    }

    // =================================================================== Mode 4: Cinematic Promo & MVP (5-Tier Pyramid)

    private static double buildCinematicPromoMode(LastMatchReplay replay,
                                                  ReplayExportContentOptions opts,
                                                  @Nullable UUID focus,
                                                  ReplayHighlightScorer.AcousticSignal acoustic,
                                                  int startTick,
                                                  int endTick,
                                                  List<DirectedSegment> outSegs,
                                                  List<ScoredHighlight> outHighlights,
                                                  List<ReplayVideoScript.Beat> outBeats,
                                                  List<ReplayStreamingAudioMixer.DirectorDuck> outDucks) {
        UUID scopePlayer = opts.promoScope() == PromoScope.PERSONAL_MVP ? focus : null;
        var quota = ReplayHighlightScorer.PyramidQuota.forMatch(
                Math.max(1, endTick - startTick), replay.participants().size(), scopePlayer != null);
        List<ScoredHighlight> scored = ReplayHighlightScorer.scoreAndAllocate(
                replay, acoustic, scopePlayer, startTick, endTick, quota);
        outHighlights.addAll(scored);

        double spanSec = Math.max(1.0, (endTick - startTick) / TICKS_PER_SECOND);
        // STRICT PROMO CRUISE CAP: always in [1.2x, 3.0x] — NEVER exceeds 3.0x!
        double cruiseSpeed = Math.clamp(spanSec / 160.0, MIN_PROMO_CRUISE_SPEED, MAX_PROMO_CRUISE_SPEED);
        CameraDirective baseCam = scopePlayer != null ? CameraDirective.DIORAMA_FOLLOW : CameraDirective.DIORAMA_OVERVIEW;

        double v = 0.0;
        double cursor = startTick;

        for (ScoredHighlight h : scored) {
            int tb = h.tick();
            if (tb < cursor - 1.0) continue;
            switch (h.tier()) {
                case TIER_1_GLIMMER -> {
                    // Tier 1: non-intrusive diorama micro-spotlight (1.65x scale + aura, others 42% dim, speed 1.2x).
                    double winStart = safeWindowStart(tb - 24.0, cursor, tb);
                    double winEnd = Math.min(endTick, tb + 32.0);
                    v = appendCruiseWithSmartSkip(outSegs, baseCam, scopePlayer, v, cursor, winStart,
                            cruiseSpeed, DEAD_GAP_SKIP_PROMO_TICKS, CutStyle.BRASS_SWEEP);
                    double segDur = Math.max(1.6, (winEnd - winStart) / TICKS_PER_SECOND / 1.2);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER1_GLIMMER, baseCam, CutStyle.NONE,
                            v, v + segDur, winStart, winEnd, 1.35, 1.10,
                            false, false, 1.0, 1.0, winStart, winEnd, h.primaryActor(), h,
                            TIER1_HIGHLIGHT_SCALE, TIER1_DIM_OTHERS_ALPHA, 0.0, 0.15, ""
                    ));
                    v += segDur;
                    cursor = winEnd;
                }
                case TIER_2_CLOSEUP -> {
                    // Tier 2: classic diorama dive-in close-up (1.0x -> 0.45x) + reticle + duel card.
                    double leadStart = safeWindowStart(tb - 20.0, cursor, tb);
                    double afterEnd = Math.min(endTick, tb + 22.0);
                    v = appendCruiseWithSmartSkip(outSegs, baseCam, scopePlayer, v, cursor, leadStart,
                            cruiseSpeed, DEAD_GAP_SKIP_PROMO_TICKS, CutStyle.BRASS_SWEEP);

                    double approachV = v;
                    double approachDur = Math.max(0.70, (tb - leadStart) / TICKS_PER_SECOND / 0.85);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.APPROACH, CameraDirective.DIORAMA_CLOSEUP, CutStyle.NONE,
                            v, v + approachDur, leadStart, tb, 1.0, 0.45,
                            false, false, 1.0, 1.0, leadStart, tb, h.primaryActor(), h, 1.0, 1.0f, 0.0, 0.9, ""
                    ));
                    v += approachDur;

                    double impactV = v;
                    double freezeDur = 0.28;
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.FREEZE, CameraDirective.DIORAMA_CLOSEUP, CutStyle.NONE,
                            impactV, impactV + freezeDur, tb, tb, 0.0, 0.0,
                            false, false, 1.0, 1.0, tb, tb, h.primaryActor(), h, 1.0, 1.0f, 0.0, 1.0, stampKeyFor(h)
                    ));
                    v = impactV + freezeDur;

                    double slowDur = Math.max(1.2, (afterEnd - tb) / TICKS_PER_SECOND / 0.45);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.AFTERMATH_SLOW, CameraDirective.DIORAMA_CLOSEUP, CutStyle.NONE,
                            v, v + slowDur, tb, afterEnd, 0.45, 0.55,
                            false, false, 1.0, 1.0, tb, afterEnd, h.primaryActor(), h, 1.0, 1.0f, 0.0, 0.9, ""
                    ));
                    v += slowDur;

                    outBeats.add(new ReplayVideoScript.Beat(
                            outBeats.size(), h.beatType(), h.primaryNode(), tb,
                            approachV, impactV, impactV + freezeDur, v,
                            h.streak(), h.firstBlood(), h.finalBlow(), h.clusterKills()
                    ));
                    cursor = afterEnd;
                }
                case TIER_3_SHADOW_SLASH -> {
                    // Tier 3: 3.0s over-the-shoulder chase (1.0x) -> 90..115 deg whip to hero side close-up (0.32x) -> crane back.
                    double preStart = safeWindowStart(tb - TIER3_PRE_SECONDS * TICKS_PER_SECOND, cursor, tb);
                    double afterEnd = Math.min(endTick, tb + 16.0);
                    double whipDeg = 92.0 + Math.floorMod(tb * 7 + h.index() * 13, 24); // [92..115] deg
                    v = appendCruiseWithSmartSkip(outSegs, baseCam, scopePlayer, v, cursor, preStart,
                            cruiseSpeed, DEAD_GAP_SKIP_PROMO_TICKS, CutStyle.BRASS_SWEEP);

                    double approachV = v;
                    double chaseDur = Math.max(1.4, (tb - preStart) / TICKS_PER_SECOND);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER3_OVER_SHOULDER, CameraDirective.POV_OVER_SHOULDER, CutStyle.NONE,
                            v, v + chaseDur, preStart, tb, 1.0, 1.0,
                            false, false, 1.0, 1.0, preStart, tb, h.primaryActor(), h, 1.0, 1.0f, 0.0, 0.95, ""
                    ));
                    v += chaseDur;

                    double impactV = v;
                    double sideDur = Math.max(1.6, (afterEnd - tb) / TICKS_PER_SECOND / TIER3_SLOW_RATE);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER3_HERO_SIDE, CameraDirective.POV_HERO_SIDE, CutStyle.NONE,
                            v, v + sideDur, tb, afterEnd, TIER3_SLOW_RATE, TIER3_SLOW_RATE,
                            false, false, 1.0, 1.0, tb, afterEnd, h.primaryActor(), h, 1.0, 1.0f, whipDeg, 1.0, stampKeyFor(h)
                    ));
                    v += sideDur;

                    outBeats.add(new ReplayVideoScript.Beat(
                            outBeats.size(), h.beatType(), h.primaryNode(), tb,
                            approachV, impactV, impactV + 0.25, v,
                            h.streak(), h.firstBlood(), h.finalBlow(), h.clusterKills()
                    ));
                    cursor = afterEnd;
                }
                case TIER_4_DUAL_VISION -> {
                    // Tier 4: over-shoulder stealth -> 0.35s impact freeze + brass split -> left side execution
                    // -> split glides left expanding right panel to full 1x hero POV replay -> cross-dissolve back!
                    double preStart = safeWindowStart(tb - 50.0, cursor, tb); // 2.5s stealth approach
                    double povStartTick = Math.max(startTick, Math.min(h.comboStartTick(), tb - 35));
                    double povEndTick = Math.min(endTick, Math.max(h.comboEndTick(), tb + 25));
                    double whipDeg = 96.0 + Math.floorMod(tb * 5, 19);

                    v = appendCruiseWithSmartSkip(outSegs, baseCam, scopePlayer, v, cursor, preStart,
                            cruiseSpeed, DEAD_GAP_SKIP_PROMO_TICKS, CutStyle.BRASS_SWEEP);

                    double approachV = v;
                    double approachDur = Math.max(1.2, (tb - preStart) / TICKS_PER_SECOND);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER4_OVER_SHOULDER, CameraDirective.POV_OVER_SHOULDER, CutStyle.NONE,
                            v, v + approachDur, preStart, tb, 1.0, 1.0,
                            false, false, 1.0, 1.0, preStart, tb, h.primaryActor(), h, 1.0, 1.0f, 0.0, 1.0, ""
                    ));
                    v += approachDur;

                    // 0.35s impact freeze + split beam snap
                    double impactV = v;
                    double releaseV = impactV + TIER4_FREEZE_SECONDS;
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER4_IMPACT_FREEZE, CameraDirective.SPLIT_HERO_SIDE_AND_POV, CutStyle.NONE,
                            impactV, releaseV, tb, tb, 0.0, 0.0,
                            false, true, 0.55, 0.55, povStartTick, povStartTick, h.primaryActor(), h,
                            1.0, 1.0f, whipDeg, 1.05, stampKeyFor(h)
                    ));
                    v = releaseV;

                    // Split Phase A: Left = Tier 3 side execution (0.40x), Right = POV starts rolling
                    double dualDur = 1.40;
                    double leftAfterTick = Math.min(endTick, tb + dualDur * TICKS_PER_SECOND * 0.40);
                    double rightMidTick = Math.min(povEndTick, povStartTick + dualDur * TICKS_PER_SECOND * 0.65);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER4_DUAL_EXECUTION, CameraDirective.SPLIT_HERO_SIDE_AND_POV, CutStyle.NONE,
                            v, v + dualDur, tb, leftAfterTick, 0.40, 0.40,
                            false, true, 0.55, 0.48, povStartTick, rightMidTick, h.primaryActor(), h,
                            1.0, 1.0f, whipDeg, 1.05, ""
                    ));
                    v += dualDur;

                    // Split Phase B: Beam slides leftward (0.48 -> 0.0); Right expands to full 1x POV replay!
                    double expandDur = Math.clamp((povEndTick - povStartTick) / TICKS_PER_SECOND, 2.2, 4.0);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER4_POV_EXPAND, CameraDirective.SPLIT_HERO_SIDE_AND_POV, CutStyle.NONE,
                            v, v + expandDur, leftAfterTick, leftAfterTick, 1.0, 1.0,
                            true, true, 0.48, 0.0, povStartTick, povEndTick, h.primaryActor(), h,
                            1.0, 1.0f, whipDeg, 1.05, ""
                    ));
                    v += expandDur;

                    // Cross-dissolve back to diorama
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TRANSITION_CUT, baseCam, CutStyle.CROSS_DISSOLVE,
                            v, v + 0.45, povEndTick, povEndTick, 0.0, 0.0,
                            false, false, 1.0, 1.0, povEndTick, povEndTick, scopePlayer, null, 1.0, 1.0f, 0.0, 0.5, ""
                    ));
                    v += 0.45;

                    outBeats.add(new ReplayVideoScript.Beat(
                            outBeats.size(), h.beatType(), h.primaryNode(), tb,
                            approachV, impactV, releaseV, v,
                            h.streak(), h.firstBlood(), h.finalBlow(), h.clusterKills()
                    ));
                    cursor = Math.max(leftAfterTick, povEndTick);
                }
                case TIER_5_APOTHEOSIS -> {
                    // Tier 5 (0..1x):
                    // 1. 4.0s pre-roll: gilded letterbox presses deeper + music suction duck + Dolly-In into 1st person
                    // 2. Native 1.0x POV full combo execution with raw audio
                    // 3. 0.40s apex freeze + star-trail afterimage + colossal gilded stamp
                    // 4. Violent pull-out into 360 deg 3rd-person Orbit Master Camera + orchestra drop + 0.40x -> 1.0x encore replay!
                    double comboStart = Math.max(startTick, Math.min(h.comboStartTick(), tb - 24));
                    double comboEnd = Math.min(endTick, Math.max(h.comboEndTick(), tb + 26));
                    double suctionStart = safeWindowStart(comboStart - TIER5_PRE_SECONDS * TICKS_PER_SECOND, cursor, Math.max(cursor, comboStart));

                    v = appendCruiseWithSmartSkip(outSegs, baseCam, scopePlayer, v, cursor, suctionStart,
                            cruiseSpeed, DEAD_GAP_SKIP_PROMO_TICKS, CutStyle.BRASS_SWEEP);

                    double approachV = v;
                    double preDur = Math.max(2.0, Math.min(TIER5_PRE_SECONDS, (comboStart - suctionStart) / TICKS_PER_SECOND + 1.0));
                    // Schedule sub-bass music suction during the 4s pre-roll!
                    outDucks.add(new ReplayStreamingAudioMixer.DirectorDuck(v, v + preDur - 0.1, 0.14f, 0.35, 0.10));
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER5_SUCTION_DOLLY, CameraDirective.POV_OVER_SHOULDER, CutStyle.NONE,
                            v, v + preDur, suctionStart, comboStart, 1.0, 1.0,
                            false, false, 1.0, 1.0, suctionStart, comboStart, h.primaryActor(), h,
                            1.0, 1.0f, 0.0, 1.20, ""
                    ));
                    v += preDur;

                    // Pass 1: 1.0x real-time native POV with raw voice & game SFX
                    double nativeDur = Math.clamp((comboEnd - comboStart) / TICKS_PER_SECOND, 2.0, 4.5);
                    double nativeStartV = v;
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER5_POV_NATIVE, CameraDirective.POV_FIRST_PERSON, CutStyle.NONE,
                            v, v + nativeDur, comboStart, comboEnd, 1.0, 1.0,
                            false, false, 1.0, 1.0, comboStart, comboEnd, h.primaryActor(), h,
                            1.0, 1.0f, 0.0, 1.20, ""
                    ));
                    v += nativeDur;

                    // 0.40s Apex Freeze + star-trail + colossal gilded stamp
                    double apexV = v;
                    double apexReleaseV = apexV + TIER5_APEX_FREEZE_SECONDS;
                    outDucks.add(new ReplayStreamingAudioMixer.DirectorDuck(apexV, apexReleaseV - 0.04, 0.05f, 0.04, 0.04));
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER5_APEX_FREEZE, CameraDirective.POV_FIRST_PERSON, CutStyle.NONE,
                            apexV, apexReleaseV, comboEnd, comboEnd, 0.0, 0.0,
                            false, false, 1.0, 1.0, comboEnd, comboEnd, h.primaryActor(), h,
                            1.0, 1.0f, 0.0, 1.25, apotheosisStampKey(h)
                    ));
                    v = apexReleaseV;

                    // Pass 2: 360-degree 3rd-person Orbit Master Encore Replay (0.40x -> 1.0x ramp!)
                    double encoreAvgRate = 0.5 * (TIER5_ENCORE_START_RATE + TIER5_ENCORE_END_RATE); // 0.70x
                    double encoreDur = Math.clamp((comboEnd - comboStart) / TICKS_PER_SECOND / encoreAvgRate, 3.0, 5.2);
                    outSegs.add(new DirectedSegment(
                            outSegs.size(), SegmentKind.TIER5_ORBIT_ENCORE, CameraDirective.POV_ORBIT_360, CutStyle.NONE,
                            v, v + encoreDur, comboStart, comboEnd, TIER5_ENCORE_START_RATE, TIER5_ENCORE_END_RATE,
                            true, false, 1.0, 1.0, comboStart, comboEnd, h.primaryActor(), h,
                            1.0, 1.0f, 360.0, 1.25, apotheosisStampKey(h)
                    ));
                    v += encoreDur;

                    outBeats.add(new ReplayVideoScript.Beat(
                            outBeats.size(), h.beatType(), h.primaryNode(), tb,
                            approachV, apexV, apexReleaseV, v,
                            h.streak(), h.firstBlood(), h.finalBlow(), h.clusterKills()
                    ));
                    cursor = comboEnd;
                }
            }
        }

        v = appendCruiseWithSmartSkip(outSegs, baseCam, scopePlayer, v, cursor, endTick,
                cruiseSpeed, DEAD_GAP_SKIP_PROMO_TICKS, CutStyle.BRASS_SWEEP);
        outSegs.add(new DirectedSegment(
                outSegs.size(), SegmentKind.FREEZE, baseCam, CutStyle.NONE,
                v, v + 0.9, endTick, endTick, 0.0, 0.0,
                false, false, 1.0, 1.0, endTick, endTick, scopePlayer, null, 1.0, 1.0f, 0.0, 0.0, ""
        ));
        return cruiseSpeed;
    }

    // =================================================================== helpers

    private static double appendCruiseWithSmartSkip(List<DirectedSegment> outSegs,
                                                    CameraDirective cam,
                                                    @Nullable UUID focus,
                                                    double v,
                                                    double fromTick,
                                                    double toTick,
                                                    double cruiseSpeed,
                                                    double maxContinuousGapTicks,
                                                    CutStyle skipStyle) {
        double gap = toTick - fromTick;
        if (gap <= 0.5) return v;
        if (gap <= maxContinuousGapTicks) {
            double dur = Math.max(0.25, gap / TICKS_PER_SECOND / cruiseSpeed);
            outSegs.add(cruiseSegment(outSegs.size(), cam, v, v + dur, fromTick, toTick, cruiseSpeed, focus));
            return v + dur;
        }
        // Long dead stretch: show departure tail (~8s match), brass chapter cut, then arrival lead-in (~10s match).
        double tailTicks = Math.min(160.0, gap * 0.25);
        double leadTicks = Math.min(200.0, gap * 0.30);
        double cutFrom = fromTick + tailTicks;
        double cutTo = toTick - leadTicks;

        double dur1 = Math.max(0.4, tailTicks / TICKS_PER_SECOND / cruiseSpeed);
        outSegs.add(cruiseSegment(outSegs.size(), cam, v, v + dur1, fromTick, cutFrom, cruiseSpeed, focus));
        v += dur1;

        outSegs.add(new DirectedSegment(
                outSegs.size(), SegmentKind.TRANSITION_CUT, cam, skipStyle,
                v, v + TRANSITION_DURATION, cutFrom, cutTo, 0.0, 0.0,
                false, false, 1.0, 1.0, cutFrom, cutTo, focus, null, 1.0, 1.0f, 0.0, 0.0, ""
        ));
        v += TRANSITION_DURATION;

        double dur2 = Math.max(0.4, leadTicks / TICKS_PER_SECOND / cruiseSpeed);
        outSegs.add(cruiseSegment(outSegs.size(), cam, v, v + dur2, cutTo, toTick, cruiseSpeed, focus));
        return v + dur2;
    }

    private static DirectedSegment cruiseSegment(int index, CameraDirective cam, double vStart, double vEnd,
                                                 double startTick, double endTick, double speed, @Nullable UUID focus) {
        return new DirectedSegment(
                index, SegmentKind.CRUISE, cam, CutStyle.NONE,
                vStart, vEnd, startTick, endTick, speed, speed,
                false, false, 1.0, 1.0, startTick, endTick, focus, null,
                1.0, 1.0f, 0.0, 0.0, ""
        );
    }

    private static @Nullable UUID resolveEffectiveFocus(LastMatchReplay replay, ReplayExportContentOptions opts) {
        UUID candidate = opts.focusPlayer();
        if (candidate != null) {
            for (var p : replay.participants()) if (candidate.equals(p.id())) return candidate;
        }
        if (opts.mode() == ExportMode.PERSONAL && !replay.participants().isEmpty()) {
            return replay.mvp() != null ? replay.mvp() : replay.participants().getFirst().id();
        }
        if (opts.mode() == ExportMode.CINEMATIC_PROMO && opts.promoScope() == PromoScope.PERSONAL_MVP) {
            if (replay.mvp() != null) return replay.mvp();
            return replay.participants().stream()
                    .max(Comparator.comparingInt(LastMatchReplay.Participant::kills)
                            .thenComparingInt(LastMatchReplay.Participant::score))
                    .map(LastMatchReplay.Participant::id)
                    .orElse(null);
        }
        return null;
    }

    private static Map<UUID, Integer> computeFirstRevealTicks(LastMatchReplay replay) {
        Map<UUID, Integer> map = new HashMap<>();
        for (LastMatchReplay.Node e : replay.events()) {
            switch (e.kind()) {
                case KILL -> {
                    if (e.actor() != null) map.merge(e.actor(), e.tick(), Math::min);
                    if (e.target() != null) map.merge(e.target(), e.tick(), Math::min);
                }
                case GUNSHOT, BOMB_PLANT, BOMB_DEFUSE, SKILL_RELEASE, PSYCHO_STATE_CHANGE, DISGUISE, CHANGE_ROLE -> {
                    if (e.actor() != null) map.merge(e.actor(), e.tick(), Math::min);
                }
                default -> {}
            }
        }
        for (ReplaySkillEvent sk : replay.skillEvents()) {
            if (sk.actorUuid() != null) map.merge(sk.actorUuid(), sk.tick(), Math::min);
        }
        return map;
    }

    private static String stampKeyFor(ScoredHighlight h) {
        if (h.streak() >= 4) return "MULTI_KILL";
        if (h.streak() == 3) return "TRIPLE_KILL";
        if (h.streak() == 2) return "DOUBLE_KILL";
        if (h.firstBlood()) return "FIRST_BLOOD";
        if (h.finalBlow()) return "FINAL_BLOW";
        return switch (h.beatType()) {
            case KILL -> "ELIMINATED";
            case REVIVAL -> "REVIVED";
            case EXPLOSION -> "DETONATION";
            case DEFUSE -> "DEFUSED";
            case SHIELD -> "SHIELD";
            case HIT -> "";
        };
    }

    private static String apotheosisStampKey(ScoredHighlight h) {
        if (h.streak() >= 3) return "MULTI_KILL";
        if (h.finalBlow()) return "FINAL_BLOW";
        return "MVP";
    }

    private static @Nullable UUID resolveAudioListenerForSegment(DirectedFilm film, DirectedSegment seg) {
        if (film.mode() == ExportMode.PERSONAL) return film.focusPlayer();
        if (seg.splitScreen() || seg.camera() == CameraDirective.POV_FIRST_PERSON
                || seg.camera() == CameraDirective.POV_OVER_SHOULDER
                || seg.camera() == CameraDirective.POV_HERO_SIDE
                || seg.camera() == CameraDirective.POV_ORBIT_360) {
            return seg.povSubjectUuid();
        }
        return null;
    }

    private static double safeWindowStart(double desired, double cursor, double targetTick) {
        double hi = Math.max(cursor, targetTick);
        return Math.max(cursor, Math.min(desired, hi));
    }

    private static double audioStartTick(DirectedSegment seg) {
        return seg.splitScreen() ? seg.splitRightStartTick() : seg.startTick();
    }

    private static double audioEndTick(DirectedSegment seg) {
        return seg.splitScreen() ? seg.splitRightEndTick() : seg.endTick();
    }

    private static double mapTickToSegmentVideoTime(DirectedSegment seg, double matchTick) {
        double loT = seg.videoStart();
        double hiT = seg.videoEnd();
        if (seg.splitScreen()) {
            double span = seg.splitRightEndTick() - seg.splitRightStartTick();
            if (span <= 1e-6) return loT;
            double frac = Math.clamp((matchTick - seg.splitRightStartTick()) / span, 0.0, 1.0);
            return loT + (hiT - loT) * frac;
        }
        double sLo = seg.startTick(), sHi = seg.endTick();
        if (Math.abs(sHi - sLo) < 1e-6) return loT;
        if (matchTick <= sLo) return loT;
        if (matchTick >= sHi) return hiT;
        for (int it = 0; it < 20; it++) {
            double mid = 0.5 * (loT + hiT);
            if (seg.tickAt(mid) < matchTick) loT = mid; else hiT = mid;
        }
        return 0.5 * (loT + hiT);
    }

    private static boolean isPrioritySound(String soundId, float volume) {
        if (volume >= 0.85f) return true;
        String low = soundId.toLowerCase(Locale.ROOT);
        return low.contains("shoot") || low.contains("stab") || low.contains("explode")
                || low.contains("grenade") || low.contains("c4") || low.contains("shield")
                || low.contains("totem") || low.contains("horn") || low.contains("piano")
                || low.contains("psycho") || low.contains("alarm") || low.contains("door")
                || low.contains("lockpick") || low.contains("crowbar") || low.contains("bell");
    }

    private static void emitFallbackImpactSfxIfNeeded(List<ReplayStreamingAudioMixer.SfxEvent> out,
                                                      @Nullable ReplaySoundCuePlan.Schedule legacySchedule,
                                                      DirectedSegment seg,
                                                      Set<ReplaySoundCuePlan.SoundCue> emitted) {
        if (legacySchedule == null || seg.highlight() == null) return;
        int targetTick = seg.highlight().tick();
        for (var cue : legacySchedule.sounds()) {
            if (Math.abs(cue.time() - targetTick) <= 1.0 && emitted.add(cue)) {
                List<String> keys = cue.recordedFile() == null ? cue.candidates() : List.of(cue.recordedFile().toString());
                out.add(new ReplayStreamingAudioMixer.SfxEvent(seg.videoStart(), keys, cue.volume(), cue.pitch(), cue.pan()));
            }
        }
    }

    private static void emitLegacyEventsInSegment(List<ReplayStreamingAudioMixer.SfxEvent> out,
                                                  @Nullable ReplaySoundCuePlan.Schedule legacySchedule,
                                                  DirectedSegment seg,
                                                  Set<ReplaySoundCuePlan.SoundCue> emitted) {
        if (legacySchedule == null) return;
        double lo = audioStartTick(seg);
        double hi = audioEndTick(seg);
        for (var cue : legacySchedule.sounds()) {
            if (cue.time() < lo - 1e-6 || cue.time() > hi + 1e-6) continue;
            if (!emitted.add(cue)) continue;
            double vTime = mapTickToSegmentVideoTime(seg, cue.time());
            List<String> keys = cue.recordedFile() == null ? cue.candidates() : List.of(cue.recordedFile().toString());
            out.add(new ReplayStreamingAudioMixer.SfxEvent(vTime, keys, cue.volume(), cue.pitch(), cue.pan()));
        }
    }

    private static void addLegacyVoiceClips(List<ReplayStreamingAudioMixer.VoiceSegment> out,
                                            DirectedFilm film,
                                            ReplayVoiceData voice) {
        for (ReplayVoiceData.Clip clip : voice.clips()) {
            if (clip.sampleCount() <= 0) continue;
            if (clip.tick() < film.trimStartTick() || clip.tick() > film.trimEndTick()) continue;
            double impactV = film.videoTimeOf(clip.tick());
            double start = Math.max(film.actStart(ReplayVideoScript.Act.ACTION), impactV + clip.offsetMs() / 1000.0);
            double end = Math.min(film.actStart(ReplayVideoScript.Act.REVIEW), start + clip.durationSeconds());
            if (end - start >= 0.15) {
                out.add(new ReplayStreamingAudioMixer.VoiceSegment(
                        null, start, end, 0.0, 1.0, 1.05f, 0f, false, clip::decode48k
                ));
            }
        }
    }
}
