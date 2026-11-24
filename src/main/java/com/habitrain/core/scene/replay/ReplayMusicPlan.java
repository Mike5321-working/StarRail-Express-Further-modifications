package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic procedural soundtrack for exported replay videos.
 *
 * <p>The whole film runs at one tempo, {@link ReplayVideoScript#BPM}, on one unbroken beat grid that starts at the
 * first frame. {@link ReplayVideoScript} already places every act change and every featured highlight (kill,
 * revival, explosion ...) on that grid, so the composer never stretches time: each anchor simply starts a new bar.
 * Between those anchors it picks a track and a mood from the state of the match (first blood, kill share, players
 * still alive, the run-up to the final blow) and switches tracks at the anchors, so a quiet opening, a firefight,
 * the climax, the review and the winning side's anthem each get their own music.</p>
 *
 * <p>Because the tempo never changes, tension is carried by the rhythmic feel instead: half-time (kick on one,
 * backbeat on three, quarter hats) while the match is still searching and during the review, straight time
 * (backbeat on two and four, eighth-note bass) once blood is drawn, and double-time (sixteenth hats, bass and
 * arpeggios, ghost snares, stabs) in the climax.</p>
 *
 * <p>Every highlight gets a build-up (riser and snare roll, kick and bass drop out), a short silence right before the
 * impact, a type-specific hit and a slow-motion aftermath (heartbeat after kills, rising bells after revivals,
 * tinnitus after explosions) under a muffling filter that opens again when the replay resumes. The score also carries
 * the ducking envelope: player voice pulls the music down by 60%, game sound effects briefly by about 30%.</p>
 *
 * <p>Pure and side-effect free: the same replay always yields the same score.</p>
 */
public final class ReplayMusicPlan {
    private static final double MIN_ANCHOR_GAP = 0.3;
    /** The single tempo of the soundtrack. */
    public static final double BPM = ReplayVideoScript.BPM;
    /** Length of one beat in seconds. */
    public static final double BEAT = ReplayVideoScript.BEAT;
    /** Seconds the train horn at the start of the title owns the mix. */
    public static final double TITLE_HORN = 2.4;
    public static final float VOICE_DUCK = 0.40f;
    public static final float SFX_DUCK = 0.72f;
    public static final float OPEN_FILTER = 18_000f;
    private static final double EPS = 1e-6;

    private ReplayMusicPlan() {}

    /** Export option: off or one of three music volumes. */
    public enum Level {
        OFF("off", 0f), LOW("low", 0.30f), MEDIUM("medium", 0.46f), HIGH("high", 0.64f);

        public final String id;
        public final float gain;

        Level(String id, float gain) {
            this.id = id;
            this.gain = gain;
        }

        public boolean enabled() { return this != OFF; }

        public static Level fromId(@Nullable String id) {
            if (id != null) {
                String key = id.trim().toLowerCase(Locale.ROOT);
                for (Level level : values()) if (level.id.equals(key)) return level;
            }
            return MEDIUM;
        }
    }

    /** Synth instruments. {@code send} is the reverb send amount. */
    public enum Voice {
        KICK(0f), HEART(0f), SNARE(0.12f), CLAP(0.14f), HAT(0f), OPEN_HAT(0.05f), CRASH(0.10f), TOM(0.10f),
        BASS(0f), SUB_BOOM(0f), PAD(0.32f), PLUCK(0.28f), ARP(0.22f), STAB(0.18f), BELL(0.42f), LEAD(0.20f),
        RISER(0.15f), REVERSE(0.20f), RING(0.10f);

        public final float send;

        Voice(float send) { this.send = send; }
    }

    /** Sixteen-step drum patterns of one bar. */
    public enum Kit {
        NONE("................", "................", "................", Voice.SNARE, Voice.HAT),
        /** Half-time: kick on one (and a push before three), backbeat only on three, quarter hats. */
        HALF_TIME("x.........x.....", "........x.......", "x...x...x...x...", Voice.SNARE, Voice.HAT),
        TRAIN("x.....x...x.....", "....x.......x...", "x.xxx.xxx.xxx.xx", Voice.SNARE, Voice.HAT),
        FOUR_ON_FLOOR("x...x...x...x...", "....x.......x...", "..x...x...x...x.", Voice.CLAP, Voice.OPEN_HAT),
        BREAKBEAT("x......x..x.....", "....x.......x..x", "x.x.x.x.x.x.x.x.", Voice.SNARE, Voice.HAT),
        ANTHEM("x...x...x...x...", "....x.......x...", "x.x.x.x.x.x.x.x.", Voice.SNARE, Voice.HAT);

        final String kick, snare, hat;
        final Voice snareVoice, hatVoice;

        Kit(String kick, String snare, String hat, Voice snareVoice, Voice hatVoice) {
            this.kick = kick;
            this.snare = snare;
            this.hat = hat;
            this.snareVoice = snareVoice;
            this.hatVoice = hatVoice;
        }

        boolean kick(int step) { return kick.charAt(step & 15) == 'x'; }
        boolean snare(int step) { return snare.charAt(step & 15) == 'x'; }
        boolean hat(int step) { return hat.charAt(step & 15) == 'x'; }
    }

    private static final int[] MINOR = {0, 2, 3, 5, 7, 8, 10};
    private static final int[] HARMONIC_MINOR = {0, 2, 3, 5, 7, 8, 11};
    private static final int[] DORIAN = {0, 2, 3, 5, 7, 9, 10};
    private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};

    /**
     * Rhythmic feel of a section. The tempo is always {@link #BPM}; the feel decides how densely the bar is
     * subdivided and is what makes a section sound calm or tense.
     */
    public enum Feel {
        /** Backbeat on three, quarter-note pulse: searching, reflective. */
        HALF_TIME,
        /** Backbeat on two and four, eighth-note pulse: the hunt is on. */
        STRAIGHT,
        /** Sixteenth-note pulse everywhere: the climax. */
        DOUBLE_TIME
    }

    /**
     * The tracks the soundtrack switches between. They all run at {@link #BPM}; progressions are scale degrees
     * (0 = tonic), one chord per bar.
     */
    public enum Track {
        /** Default action theme: galloping train rhythm. */
        MIDNIGHT_EXPRESS("midnight_express", 50, MINOR, Kit.TRAIN, new int[][]{{0, 5, 2, 6}, {0, 3, 5, 4}}),
        /** Suspense before the first kill and in slow matches. */
        SHADOW_HUNT("shadow_hunt", 48, HARMONIC_MINOR, Kit.HALF_TIME, new int[][]{{0, 0, 5, 4}, {0, 3, 0, 4}}),
        /** Bloodbaths, kill streaks and every climax. */
        BLOOD_RUSH("blood_rush", 52, MINOR, Kit.FOUR_ON_FLOOR, new int[][]{{0, 6, 5, 6}, {0, 5, 3, 4}}),
        /** Gadget matches: bombs, grenades, traps and shields. */
        NEON_HEIST("neon_heist", 45, DORIAN, Kit.BREAKBEAT, new int[][]{{0, 3, 0, 3}, {0, 6, 3, 4}}),
        /** Calm review of the whole match. */
        STATION_DAWN("station_dawn", 53, MAJOR, Kit.NONE, new int[][]{{0, 4, 5, 3}, {5, 3, 0, 4}}),
        /** Passengers win. */
        GOLDEN_TERMINAL("golden_terminal", 48, MAJOR, Kit.ANTHEM, new int[][]{{0, 4, 5, 3}, {3, 4, 0, 0}}),
        /** Killers win. */
        CRIMSON_FINALE("crimson_finale", 50, HARMONIC_MINOR, Kit.ANTHEM, new int[][]{{0, 5, 3, 4}, {0, 3, 5, 4}});

        public final String id;
        public final int root;
        public final Kit kit;
        private final int[] scale;
        private final int[][] progressions;

        Track(String id, int root, int[] scale, Kit kit, int[][] progressions) {
            this.id = id;
            this.root = root;
            this.scale = scale;
            this.kit = kit;
            this.progressions = progressions;
        }

        /** Semitones above the root of scale degree {@code degree} (may be negative or span octaves). */
        public int interval(int degree) {
            return 12 * Math.floorDiv(degree, 7) + scale[Math.floorMod(degree, 7)];
        }

        int[] progression(int variant) { return progressions[Math.floorMod(variant, progressions.length)]; }
    }

    /** Dramatic state of a section: its mix level and the rhythmic feel that carries its tension. */
    public enum Mood {
        INTRO(0.85f, Feel.HALF_TIME), EXPLORE(0.80f, Feel.HALF_TIME), TENSION(0.92f, Feel.STRAIGHT),
        CLIMAX(1.0f, Feel.DOUBLE_TIME), REVIEW(0.75f, Feel.HALF_TIME), VICTORY(0.85f, Feel.STRAIGHT),
        MVP(0.95f, Feel.STRAIGHT), OUTRO(0.90f, Feel.STRAIGHT);

        public final float level;
        public final Feel feel;

        Mood(float level, Feel feel) {
            this.level = level;
            this.feel = feel;
        }
    }

    /**
     * One synth note.
     *
     * @param time     video seconds
     * @param length   total sounding length in seconds, release tail included
     * @param midi     MIDI pitch (ignored by unpitched drums)
     * @param velocity 0..1
     * @param pan      -1 (left) .. +1 (right)
     */
    public record Note(double time, double length, Voice voice, int midi, float velocity, float pan) {}

    /** One beat of the constant-tempo grid. {@code beatInBar == 0} is a downbeat. */
    public record GridBeat(double time, double duration, int beatInBar) {
        public boolean downbeat() { return beatInBar == 0; }
    }

    public record Section(double start, double end, Mood mood, Track track, int transpose) {
        public Feel feel() { return mood.feel; }
    }

    /** A synchronized musical accent, kept for logs and tests. */
    public record Hit(double time, String kind) {}

    /** Gain dip: {@code depth} inside {@code [start, end]}, linear ramps of {@code attack} before and {@code release} after. */
    public record Duck(double start, double end, float depth, double attack, double release) {
        public float factor(double t) {
            if (t < start - attack || t > end + release) return 1f;
            if (t < start) return (float) (1 - (1 - depth) * (t - (start - attack)) / Math.max(EPS, attack));
            if (t <= end) return depth;
            return (float) (depth + (1 - depth) * (t - end) / Math.max(EPS, release));
        }
    }

    /** Exponential low-pass cutoff ramp in Hz over {@code [start, end]}, holding {@code to} afterwards until the next ramp. */
    public record Ramp(double start, double end, float from, float to) {
        public float at(double t) {
            if (t <= start) return from;
            if (t >= end) return to;
            double k = (t - start) / Math.max(EPS, end - start);
            return (float) (from * Math.pow(to / (double) from, k));
        }
    }

    public record Score(double total, List<Note> notes, List<GridBeat> grid, List<Section> sections, List<Hit> hits,
                        List<Duck> ducks, List<Ramp> filter) {
        public static final Score EMPTY = new Score(0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        public Score {
            notes = List.copyOf(notes);
            grid = List.copyOf(grid);
            sections = List.copyOf(sections);
            hits = List.copyOf(hits);
            ducks = List.copyOf(ducks);
            filter = List.copyOf(filter);
        }

        public @Nullable Section sectionAt(double t) {
            for (Section s : sections) if (t >= s.start() - EPS && t < s.end()) return s;
            return sections.isEmpty() ? null : sections.getLast();
        }

        /** Mood level with a short fade-in at the start of the film and a fade-out over its last seconds. */
        public float levelAt(double t) {
            if (t < 0 || t > total) return 0f;
            Section s = sectionAt(t);
            float level = s == null ? 0f : s.mood().level;
            double in = Math.min(1, t / 0.5);
            double out = Math.clamp((total - 0.15 - t) / 2.4, 0.0, 1.0);
            return (float) (level * in * out * out * (3 - 2 * out));
        }

        /** Product of the mood level and the strongest duck at {@code t}. */
        public float gainAt(double t) {
            float duck = 1f;
            for (Duck d : ducks) duck = Math.min(duck, d.factor(t));
            return levelAt(t) * duck;
        }

        public float cutoffAt(double t) {
            float cutoff = OPEN_FILTER;
            for (Ramp r : filter) if (t >= r.start() - EPS) cutoff = r.at(t);
            return cutoff;
        }

        public List<Track> tracks() {
            List<Track> out = new ArrayList<>();
            for (Section s : sections) if (out.isEmpty() || out.getLast() != s.track()) out.add(s.track());
            return out;
        }
    }

    /** Builds the score for {@code script}; {@code schedule} supplies voice and sound-effect cues for ducking. */
    public static Score compose(ReplayVideoScript script, @Nullable ReplaySoundCuePlan.Schedule schedule) {
        if (script == null || script.total() <= 0) return Score.EMPTY;
        return new Composer(ScriptSource.of(script), schedule, List.of()).build();
    }

    /** Builds a mode-aware procedural soundtrack for a {@link ReplayCinemaDirector.DirectedFilm}. */
    public static Score compose(ReplayCinemaDirector.DirectedFilm film) {
        if (film == null || film.total() <= 0) return Score.EMPTY;
        return new Composer(ScriptSource.of(film), null, film.segments()).build();
    }

    private interface ScriptSource {
        LastMatchReplay replay();
        List<ReplayVideoScript.Beat> beats();
        List<ReplayVideoScript.Feed> feeds();
        int duration();
        boolean hasMvpAct();
        double actStart(ReplayVideoScript.Act act);
        double actLength(ReplayVideoScript.Act act);
        double total();
        double replayTickAt(double time);

        static ScriptSource of(ReplayVideoScript s) {
            return new ScriptSource() {
                @Override public LastMatchReplay replay() { return s.replay(); }
                @Override public List<ReplayVideoScript.Beat> beats() { return s.beats(); }
                @Override public List<ReplayVideoScript.Feed> feeds() { return s.feeds(); }
                @Override public int duration() { return s.duration(); }
                @Override public boolean hasMvpAct() { return s.hasMvpAct(); }
                @Override public double actStart(ReplayVideoScript.Act act) { return s.actStart(act); }
                @Override public double actLength(ReplayVideoScript.Act act) { return s.actLength(act); }
                @Override public double total() { return s.total(); }
                @Override public double replayTickAt(double time) { return s.sample(time).replayTick(); }
            };
        }

        static ScriptSource of(ReplayCinemaDirector.DirectedFilm f) {
            return new ScriptSource() {
                @Override public LastMatchReplay replay() { return f.replay(); }
                @Override public List<ReplayVideoScript.Beat> beats() { return f.beats(); }
                @Override public List<ReplayVideoScript.Feed> feeds() { return f.feeds(); }
                @Override public int duration() { return f.duration(); }
                @Override public boolean hasMvpAct() { return f.hasMvpAct(); }
                @Override public double actStart(ReplayVideoScript.Act act) { return f.actStart(act); }
                @Override public double actLength(ReplayVideoScript.Act act) { return f.actLength(act); }
                @Override public double total() { return f.total(); }
                @Override public double replayTickAt(double time) { return f.sample(time).replayTick(); }
            };
        }
    }

    // ------------------------------------------------------------------ composer

    private enum AnchorKind { ACTION, IMPACT, REVIEW, VICTORY, MVP, OUTRO }

    private static final class Anchor {
        final double time;
        final AnchorKind kind;
        final @Nullable ReplayVideoScript.Beat beat;
        double gap;

        Anchor(double time, AnchorKind kind, @Nullable ReplayVideoScript.Beat beat) {
            this.time = time;
            this.kind = kind;
            this.beat = beat;
        }

        boolean drop() { return kind == AnchorKind.ACTION || kind == AnchorKind.IMPACT || kind == AnchorKind.MVP; }
    }

    private record Segment(double start, double end, Mood mood, Track track, int transpose, int variant,
                           @Nullable Anchor from, @Nullable Anchor to, List<GridBeat> beats, int barBase) {
        int root() { return track.root + transpose; }

        /** Bar of beat {@code j}; the title lead-in counts backwards so the bar into the first drop is -1. */
        int bar(int j) { return Math.floorDiv(j + barBase, 4); }
    }

    private static final class Composer {
        private final ScriptSource script;
        private final LastMatchReplay replay;
        private final List<ReplayVideoScript.Beat> beats;
        private final List<ReplayCinemaDirector.DirectedSegment> directedSegments;
        private final @Nullable ReplaySoundCuePlan.Schedule schedule;
        private final int transpose;
        private final int variant;
        private final Track exploreTrack, actionTrack, climaxTrack, reviewTrack, victoryTrack;
        private final int climaxLift;
        private final int[] killTicks, deathTicks, reviveTicks;
        private final int participants, totalKills;
        private final double actionStart, reviewStart, victoryStart, mvpStart, outroStart, total;

        private final List<Note> notes = new ArrayList<>();
        private final List<GridBeat> grid = new ArrayList<>();
        private final List<Section> sections = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();
        private final List<Duck> ducks = new ArrayList<>();
        private final List<Ramp> filter = new ArrayList<>();
        private final List<double[]> gaps = new ArrayList<>();
        private double[] cuts = new double[0];
        private final List<Segment> segments = new ArrayList<>();
        private final List<Anchor> anchors = new ArrayList<>();

        Composer(ScriptSource script, @Nullable ReplaySoundCuePlan.Schedule schedule,
                 List<ReplayCinemaDirector.DirectedSegment> directedSegments) {
            this.script = script;
            this.replay = script.replay();
            this.beats = script.beats();
            this.directedSegments = directedSegments;
            this.schedule = schedule;
            long seed = replay.roundId() == null ? 0 : replay.roundId().getMostSignificantBits() ^ replay.roundId().getLeastSignificantBits();
            seed ^= seed >>> 29;
            this.transpose = (int) Math.floorMod(seed, 5L) - 2;
            this.variant = (int) Math.floorMod(seed >>> 7, 2L);

            List<Integer> kills = new ArrayList<>(), deaths = new ArrayList<>(), revives = new ArrayList<>();
            int gadgets = 0;
            for (LastMatchReplay.Node node : replay.events()) {
                switch (node.kind()) {
                    case KILL -> { kills.add(node.tick()); deaths.add(node.tick()); }
                    case PLAYER_REVIVAL -> revives.add(node.tick());
                    case GRENADE_EXPLOSION, BOMB_DETONATE, BOMB_DEFUSE, BOMB_PLANT, TRAP_TRIGGERED, SHIELD_BROKEN -> gadgets++;
                    default -> {}
                }
            }
            killTicks = sorted(kills);
            deathTicks = sorted(deaths);
            reviveTicks = sorted(revives);
            totalKills = killTicks.length;
            participants = Math.max(1, replay.participants().size());

            int maxStreak = 1;
            for (int streak : ReplayVideoScript.streaks(replay).values()) maxStreak = Math.max(maxStreak, streak);
            double minutes = Math.max(0.5, script.duration() / 1200.0);
            double killsPerMinute = totalKills / minutes;
            if (killsPerMinute >= 2.0 || maxStreak >= 3) actionTrack = Track.BLOOD_RUSH;
            else if (gadgets >= 3) actionTrack = Track.NEON_HEIST;
            else actionTrack = Track.MIDNIGHT_EXPRESS;
            exploreTrack = Track.SHADOW_HUNT;
            climaxTrack = Track.BLOOD_RUSH;
            climaxLift = actionTrack == Track.BLOOD_RUSH ? 2 : 0;
            reviewTrack = Track.STATION_DAWN;
            MatchWinFaction winner = winningFaction(replay);
            victoryTrack = winner == null ? Track.STATION_DAWN : switch (winner) {
                case KILLER -> Track.CRIMSON_FINALE;
                case NEUTRAL -> Track.NEON_HEIST;
                case PASSENGER -> Track.GOLDEN_TERMINAL;
            };

            actionStart = script.actStart(ReplayVideoScript.Act.ACTION);
            reviewStart = script.actStart(ReplayVideoScript.Act.REVIEW);
            victoryStart = script.actStart(ReplayVideoScript.Act.VICTORY);
            mvpStart = script.actStart(ReplayVideoScript.Act.MVP);
            outroStart = script.actStart(ReplayVideoScript.Act.OUTRO);
            total = script.total();
        }

        Score build() {
            planAnchors();
            planSegments();
            planGaps();
            for (Segment s : segments) sections.add(new Section(s.start(), s.end(), s.mood(), s.track(), s.transpose()));
            for (Segment s : segments) {
                if (s.mood() == Mood.INTRO) intro(s);
                else groove(s);
            }
            for (Anchor a : anchors) {
                if (a.drop()) buildUp(a);
                accent(a);
            }
            tier5EncoreDrops();
            feedAccents();
            planDucks();
            notes.sort(Comparator.comparingDouble(Note::time).thenComparing(n -> n.voice().ordinal()));
            hits.sort(Comparator.comparingDouble(Hit::time));
            filter.sort(Comparator.comparingDouble(Ramp::start));
            return new Score(total, notes, grid, sections, hits, ducks, filter);
        }

        // ------------------------------------------------------------ structure

        private void planAnchors() {
            List<Anchor> raw = new ArrayList<>();
            if (script.actLength(ReplayVideoScript.Act.TITLE) > 0) raw.add(new Anchor(actionStart, AnchorKind.ACTION, null));
            for (ReplayVideoScript.Beat beat : beats) raw.add(new Anchor(beat.impact(), AnchorKind.IMPACT, beat));
            if (script.actLength(ReplayVideoScript.Act.REVIEW) > 0) raw.add(new Anchor(reviewStart, AnchorKind.REVIEW, null));
            if (script.actLength(ReplayVideoScript.Act.VICTORY) > 0) raw.add(new Anchor(victoryStart, AnchorKind.VICTORY, null));
            if (script.hasMvpAct() && script.actLength(ReplayVideoScript.Act.MVP) > 0) raw.add(new Anchor(mvpStart, AnchorKind.MVP, null));
            if (script.actLength(ReplayVideoScript.Act.OUTRO) > 0) raw.add(new Anchor(outroStart, AnchorKind.OUTRO, null));
            raw.sort(Comparator.comparingDouble(a -> a.time));
            for (Anchor a : raw) {
                if (a.time <= 0 || a.time >= total) continue;
                if (!anchors.isEmpty() && a.time - anchors.getLast().time < MIN_ANCHOR_GAP) continue;
                anchors.add(a);
            }
        }

        private void planSegments() {
            if (anchors.isEmpty()) {
                addForwardSegment(0, total, Mood.OUTRO, victoryTrack, transpose, null);
                return;
            }
            Mood previous = Mood.EXPLORE;
            List<Mood> moods = new ArrayList<>();
            for (int i = 0; i < anchors.size(); i++) {
                Anchor a = anchors.get(i);
                Mood mood = switch (a.kind) {
                    case ACTION, IMPACT -> actionMood(a, i, previous);
                    case REVIEW -> Mood.REVIEW;
                    case VICTORY -> Mood.VICTORY;
                    case MVP -> Mood.MVP;
                    case OUTRO -> Mood.OUTRO;
                };
                if (a.kind == AnchorKind.ACTION || a.kind == AnchorKind.IMPACT) previous = mood;
                moods.add(mood);
            }
            // Lead-in under the title, counted backwards so the drop lands exactly on the first anchor.
            Anchor first = anchors.getFirst();
            Track introTrack = trackFor(moods.getFirst());
            int introTranspose = transposeFor(moods.getFirst(), introTrack);
            List<GridBeat> lead = new ArrayList<>();
            for (int j = 1; first.time - j * BEAT >= -EPS; j++) {
                double t = Math.max(0, first.time - j * BEAT);
                lead.add(new GridBeat(t, first.time - (j - 1) * BEAT - t, Math.floorMod(-j, 4)));
            }
            lead.sort(Comparator.comparingDouble(GridBeat::time));
            segments.add(new Segment(0, first.time, Mood.INTRO, introTrack, introTranspose, variant, null, first, lead,
                    -lead.size()));
            grid.addAll(lead);

            for (int i = 0; i < anchors.size(); i++) {
                Anchor a = anchors.get(i);
                Mood mood = moods.get(i);
                Track track = trackFor(mood);
                int tr = transposeFor(mood, track);
                int v = variant + (a.beat != null ? a.beat.index() : 0);
                if (i + 1 < anchors.size()) {
                    Anchor b = anchors.get(i + 1);
                    // One constant tempo: bars count forwards from the anchor so it is always a downbeat. The video
                    // script spaces anchors by whole beats (preferring whole bars), so the grid never breaks.
                    List<GridBeat> list = new ArrayList<>();
                    for (int j = 0; a.time + j * BEAT < b.time - EPS; j++) {
                        double t = a.time + j * BEAT;
                        list.add(new GridBeat(t, Math.min(BEAT, b.time - t), j & 3));
                    }
                    segments.add(new Segment(a.time, b.time, mood, track, tr, v, a, b, list, 0));
                    grid.addAll(list);
                } else {
                    addForwardSegment(a.time, total, mood, track, tr, a);
                }
            }
        }

        private void addForwardSegment(double start, double end, Mood mood, Track track, int tr, @Nullable Anchor from) {
            List<GridBeat> list = new ArrayList<>();
            for (int j = 0; start + j * BEAT < end - EPS; j++) list.add(new GridBeat(start + j * BEAT, BEAT, j & 3));
            segments.add(new Segment(start, end, mood, track, tr, variant, from, null, list, 0));
            grid.addAll(list);
        }

        private Mood actionMood(Anchor a, int index, Mood previous) {
            double tick = a.beat != null ? a.beat.tick() : script.replayTickAt(a.time + EPS);
            int killsSoFar = countAtOrBefore(killTicks, tick);
            Mood mood;
            if (killsSoFar == 0) {
                mood = a.beat != null && a.beat.type() == ReplayVideoScript.BeatType.EXPLOSION ? Mood.TENSION : Mood.EXPLORE;
            } else {
                int alive = Math.clamp(participants - countAtOrBefore(deathTicks, tick) + countAtOrBefore(reviveTicks, tick),
                        0, participants);
                double killShare = killsSoFar / (double) Math.max(1, totalKills);
                double dead = 1 - alive / (double) participants;
                double progress = script.duration() <= 0 ? 1 : tick / script.duration();
                double tension = 0.10 + 0.50 * killShare + 0.30 * dead + 0.10 * progress;
                boolean finalRun = a.beat != null && a.beat.finalBlow();
                for (int j = index + 1; j < anchors.size() && !finalRun; j++) {
                    Anchor next = anchors.get(j);
                    if (next.kind != AnchorKind.IMPACT) break;
                    if (next.beat != null && next.beat.type() == ReplayVideoScript.BeatType.KILL) {
                        finalRun = next.beat.finalBlow();
                        break;
                    }
                }
                mood = tension >= 0.62 || finalRun ? Mood.CLIMAX : Mood.TENSION;
            }
            return mood.ordinal() < previous.ordinal() ? previous : mood;
        }

        private Track trackFor(Mood mood) {
            return switch (mood) {
                case INTRO, EXPLORE -> exploreTrack;
                case TENSION -> actionTrack;
                case CLIMAX -> climaxTrack;
                case REVIEW -> reviewTrack;
                case VICTORY, MVP, OUTRO -> victoryTrack;
            };
        }

        private int transposeFor(Mood mood, Track track) {
            return transpose + (mood == Mood.CLIMAX && track == actionTrack ? climaxLift : 0);
        }

        private void planGaps() {
            List<Double> cutList = new ArrayList<>();
            for (Anchor a : anchors) {
                GridBeat before = gridBefore(a.time);
                double d = before == null ? 0.5 : before.duration();
                if (a.drop()) {
                    boolean small = a.beat != null && (a.beat.type() == ReplayVideoScript.BeatType.HIT
                            || a.beat.type() == ReplayVideoScript.BeatType.SHIELD);
                    a.gap = Math.clamp(d * (small ? 0.25 : 0.32), small ? 0.05 : 0.07, small ? 0.08 : 0.16);
                } else if (a.kind == AnchorKind.REVIEW) {
                    a.gap = Math.clamp(d * 0.25, 0.06, 0.12);
                } else {
                    a.gap = 0;
                }
                double cut = a.time - a.gap;
                cutList.add(cut);
                if (a.gap > 0) {
                    gaps.add(new double[]{cut, a.time});
                    ducks.add(new Duck(cut, a.time - 0.003, 0f, 0.006, 0.003));
                }
            }
            cutList.add(total);
            cuts = cutList.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        }

        // ------------------------------------------------------------ note helpers

        private double nextCut(double time) {
            int i = Arrays.binarySearch(cuts, time + EPS);
            if (i < 0) i = -i - 1;
            while (i < cuts.length && cuts[i] <= time + EPS) i++;
            return i < cuts.length ? cuts[i] : total;
        }

        private boolean inGap(double time) {
            for (double[] g : gaps) if (time >= g[0] - EPS && time < g[1] - EPS) return true;
            return false;
        }

        private void add(double time, double length, Voice voice, int midi, float velocity, float pan) {
            if (time < -EPS || time >= total || velocity <= 0 || inGap(time)) return;
            double len = Math.min(length, nextCut(time) - time);
            if (len < 0.012) return;
            notes.add(new Note(Math.max(0, time), len, voice, midi, Math.clamp(velocity, 0f, 1f), Math.clamp(pan, -1f, 1f)));
        }

        private void chord(double time, double length, Voice voice, Segment s, int degree, int octave, float velocity) {
            int base = s.root() + 12 * octave;
            float[] pans = {-0.35f, 0f, 0.35f};
            for (int k = 0; k < 3; k++) {
                add(time, length, voice, base + s.track().interval(degree + 2 * k), velocity, pans[k]);
            }
        }

        private int chordAt(Segment s, int bar) {
            int[] prog = s.track().progression(s.variant());
            return prog[Math.floorMod(bar, prog.length)];
        }

        private @Nullable GridBeat gridBefore(double time) {
            GridBeat best = null;
            for (GridBeat g : grid) {
                if (g.time() < time - EPS) best = g;
                else break;
            }
            return best;
        }

        private @Nullable ReplayVideoScript.Beat aftermathAt(double time) {
            for (ReplayVideoScript.Beat b : beats) if (time >= b.impact() - EPS && time < b.end() - EPS) return b;
            return null;
        }

        /** True inside the last beats before a build-up anchor where kick and bass drop out. */
        private boolean breakdown(double time) {
            for (Anchor a : anchors) {
                if (!a.drop() || time >= a.time) continue;
                GridBeat before = gridBefore(a.time);
                double d = before == null ? 0.5 : before.duration();
                double from = Math.max(buildWindowStart(a), a.time - 2 * d);
                if (time >= from - EPS) return true;
            }
            return false;
        }

        /** True in the bar before a build-up's breakdown, where straight time lifts to sixteenth hats. */
        private boolean liftAhead(double time) {
            for (Anchor a : anchors) {
                if (!a.drop() || time >= a.time) continue;
                if (time >= a.time - 6 * BEAT - EPS) return true;
            }
            return false;
        }

        private double buildWindowStart(Anchor a) {
            return switch (a.kind) {
                case IMPACT -> a.beat == null ? a.time : a.beat.approach();
                case MVP -> victoryStart;
                default -> 0;
            };
        }

        // ------------------------------------------------------------ sections

        private void intro(Segment s) {
            double end = s.end() - (s.to() == null ? 0 : s.to().gap);
            chord(0, end, Voice.PAD, s, 0, 0, 0.42f);
            add(0, end, Voice.BASS, s.root() - 12, 0.30f, 0f);
            filter.add(new Ramp(0, Math.max(0.1, end - 0.4), 420f, 16_000f));
            for (int i = 0; i < s.beats().size(); i++) {
                GridBeat g = s.beats().get(i);
                if (breakdown(g.time())) continue;
                int tone = s.track().interval(2 * (i % 3)) + 12;
                add(g.time(), g.duration() * 1.5, Voice.PLUCK, s.root() + tone, 0.24f, i % 2 == 0 ? -0.25f : 0.25f);
                if (g.downbeat()) add(g.time(), 2.2, Voice.BELL, s.root() + 24, 0.20f, 0.1f);
            }
        }

        private boolean isLastKill(@Nullable ReplayVideoScript.Beat beat) {
            if (beat == null || beat.type() != ReplayVideoScript.BeatType.KILL) return false;
            if (beat.finalBlow()) return true;
            LastMatchReplay.Node lastKill = replay.lastKill();
            return lastKill != null && (beat.node() == lastKill
                    || (killTicks.length > 0 && beat.tick() >= killTicks[killTicks.length - 1]));
        }

        /**
         * True when {@code s} starts on the match's final kill close-up and leads straight into {@link AnchorKind#REVIEW}:
         * skip the post-close-up groove re-entry so it is not abruptly cut off when the match ends on that kill.
         */
        private boolean skipsPostCloseupTransition(Segment s) {
            return s.from() != null && s.from().kind == AnchorKind.IMPACT
                    && s.to() != null && s.to().kind == AnchorKind.REVIEW
                    && isLastKill(s.from().beat)
                    && s.end() - s.from().beat.end() <= 3 * BEAT + EPS;
        }

        private void groove(Segment s) {
            if (skipsPostCloseupTransition(s)) return;
            List<GridBeat> list = s.beats();
            double finalChordAt = -1;
            if (s.mood() == Mood.OUTRO) {
                for (GridBeat g : list) if (g.downbeat() && g.time() <= total - 2.6 && g.time() > s.start() + EPS) finalChordAt = g.time();
                if (finalChordAt < 0 && !list.isEmpty()) finalChordAt = list.getLast().time();
            }
            for (int j = 0; j < list.size(); j++) {
                GridBeat g = list.get(j);
                int bar = s.bar(j);
                if (aftermathAt(g.time()) != null) continue;
                if (s.mood() == Mood.OUTRO && finalChordAt >= 0 && g.time() >= finalChordAt - EPS) {
                    if (Math.abs(g.time() - finalChordAt) < EPS) finale(s, g);
                    continue;
                }
                int degree = chordAt(s, bar);
                switch (s.mood()) {
                    case EXPLORE -> explore(s, g, bar, degree);
                    case TENSION -> drive(s, g, bar, degree, false);
                    case CLIMAX -> drive(s, g, bar, degree, true);
                    case REVIEW -> review(s, g, bar, degree);
                    case VICTORY -> victory(s, g, bar, degree);
                    case MVP -> mvp(s, g, bar, degree);
                    case OUTRO -> outro(s, g, bar, degree);
                    default -> {}
                }
            }
        }

        private double barLength(GridBeat g) { return g.duration() * (4 - g.beatInBar()); }

        /** Half-time feel: kick on one, a soft backbeat on three, quarter hats, whole-bar bass, plucks on half notes. */
        private void explore(Segment s, GridBeat g, int bar, int degree) {
            Kit kit = s.track().kit;
            boolean drop = breakdown(g.time());
            double q = g.duration() / 4;
            if (g.downbeat() || g == s.beats().getFirst()) {
                chord(g.time(), barLength(g), Voice.PAD, s, degree, 0, 0.40f);
                if (!drop) add(g.time(), barLength(g) * 0.95, Voice.BASS, s.root() - 12 + s.track().interval(degree), 0.48f, 0f);
            }
            for (int k = 0; k < 4; k++) {
                int step = g.beatInBar() * 4 + k;
                double t = g.time() + k * q;
                if (!drop && kit.kick(step)) add(t, 0.45, Voice.KICK, 0, step == 0 ? 0.52f : 0.36f, 0f);
                if (!drop && kit.snare(step)) add(t, 0.3, kit.snareVoice, 0, 0.30f, -0.05f);
                if (kit.hat(step)) add(t, 0.06, Voice.HAT, 0, 0.16f, 0.3f);
            }
            int b = g.beatInBar();
            if (b == 0 || b == 2) {
                int tone = s.track().interval(degree + 2 * ((bar * 3 + b) % 3)) + 12;
                add(g.time(), g.duration() * 1.8, Voice.PLUCK, s.root() + tone, 0.30f, b == 0 ? -0.3f : 0.3f);
            } else if (b == 3) {
                int tone = s.track().interval(degree + 4) + 12;
                add(g.time() + 2 * q, g.duration(), Voice.PLUCK, s.root() + tone, 0.22f, 0.2f);
            }
        }

        private void drive(Segment s, GridBeat g, int bar, int degree, boolean climax) {
            Kit kit = s.track().kit;
            boolean drop = breakdown(g.time());
            double q = g.duration() / 4;
            int root = s.root();
            if (g.downbeat() || g == s.beats().getFirst()) {
                chord(g.time(), barLength(g), Voice.PAD, s, degree, 0, climax ? 0.40f : 0.34f);
                if (climax && g.downbeat() && Math.floorMod(bar, 4) == 0) add(g.time(), 2.0, Voice.CRASH, 0, 0.45f, 0.2f);
            }
            int b = g.beatInBar();
            boolean fill = climax && b == 3 && Math.floorMod(bar, 4) == 3;
            // Straight time lifts to sixteenth hats for the bar before a build-up; double time has them throughout.
            boolean lift = !climax && !drop && liftAhead(g.time());
            for (int k = 0; k < 4; k++) {
                int step = b * 4 + k;
                double t = g.time() + k * q;
                if (!drop && kit.kick(step)) add(t, 0.45, Voice.KICK, 0, 0.85f, 0f);
                if (fill) add(t, 0.22, Voice.SNARE, 0, 0.35f + 0.1f * k, -0.1f);
                else if (kit.snare(step)) add(t, 0.3, kit.snareVoice, 0, 0.68f, -0.05f);
                else if (climax && !drop && (step == 7 || step == 10 || step == 15)) {
                    add(t, 0.18, Voice.SNARE, 0, step == 10 ? 0.30f : 0.20f, 0.1f); // double-time ghost notes
                }
                if (kit.hat(step)) add(t, kit.hatVoice == Voice.OPEN_HAT ? 0.3 : 0.06, kit.hatVoice, 0,
                        k == 0 ? 0.36f : 0.28f, 0.3f);
                else if (climax || lift) add(t, 0.05, Voice.HAT, 0, lift ? 0.14f + 0.02f * k : 0.18f, 0.35f);
                int bassNote = root - 12 + s.track().interval(degree);
                if (!drop) {
                    if (climax) {
                        int pitch = step == 14 ? root - 12 + s.track().interval(degree + 4) : bassNote;
                        add(t, q * 0.9, Voice.BASS, pitch, k == 0 ? 0.62f : 0.50f, 0f);
                    } else if (k % 2 == 0) {
                        boolean octave = (kit == Kit.TRAIN || kit == Kit.BREAKBEAT) && k == 2;
                        add(t, q * 1.8, Voice.BASS, bassNote + (octave ? 12 : 0), 0.58f, 0f);
                    }
                }
                if (climax || k % 2 == 0) {
                    int idx = climax ? upDown(step, 8) : (b * 2 + k / 2) % 6;
                    int tone = s.track().interval(degree + 2 * (idx % 3)) + 12 * (1 + idx / 3);
                    add(t, q * (climax ? 1.2 : 2.2), Voice.ARP, root + tone, climax ? 0.30f : 0.25f,
                            (idx % 2 == 0) ? -0.4f : 0.4f);
                }
                if (climax && (step == 0 || step == 6 || step == 10)) {
                    chord(t, 0.38, Voice.STAB, s, degree, 1, 0.24f);
                }
            }
        }

        private static int upDown(int step, int period) {
            int i = Math.floorMod(step, period);
            int half = period / 2;
            return i < half ? i : period - i;
        }

        private void review(Segment s, GridBeat g, int bar, int degree) {
            double q = g.duration() / 4;
            if (g.downbeat() || g == s.beats().getFirst()) {
                chord(g.time(), barLength(g), Voice.PAD, s, degree, 0, 0.48f);
                add(g.time(), barLength(g), Voice.BASS, s.root() - 12 + s.track().interval(degree), 0.34f, 0f);
                if (g.downbeat() && Math.floorMod(bar, 2) == 0) {
                    add(g.time(), 2.4, Voice.BELL, s.root() + 24 + s.track().interval(degree + 4), 0.20f, 0.2f);
                }
            }
            for (int k = 0; k < 4; k += 2) {
                int idx = g.beatInBar() * 2 + k / 2;
                int tone = s.track().interval(degree + 2 * (idx % 3)) + 12 * (1 + (idx / 3) % 2);
                add(g.time() + k * q, g.duration() * 1.6, Voice.PLUCK, s.root() + tone, 0.28f, idx % 2 == 0 ? -0.3f : 0.3f);
            }
        }

        private void victory(Segment s, GridBeat g, int bar, int degree) {
            if (g.downbeat() || g == s.beats().getFirst()) {
                chord(g.time(), barLength(g), Voice.PAD, s, degree, 0, 0.38f);
                add(g.time(), 2.4, Voice.BELL, s.root() + 24, 0.22f, 0f);
            }
            Kit kit = s.track().kit;
            boolean drop = breakdown(g.time());
            double q = g.duration() / 4;
            int b = g.beatInBar();
            for (int k = 0; k < 4; k++) {
                int step = b * 4 + k;
                double t = g.time() + k * q;
                if (!drop && k == 0 && (b == 0 || b == 2)) add(t, 0.45, Voice.KICK, 0, 0.70f, 0f);
                if (k % 2 == 0 && kit.hat(step)) add(t, 0.06, Voice.HAT, 0, 0.24f, 0.3f);
                if (!drop && k % 2 == 0) add(t, q * 1.8, Voice.BASS, s.root() - 12 + s.track().interval(degree), 0.50f, 0f);
            }
        }

        private static final int[] MOTIF_STEPS = {0, 3, 6, 8, 12};
        private static final int[] MOTIF_DEGREES = {4, 2, 0, 2, 4};
        private static final int[] MOTIF_LENGTHS = {3, 3, 2, 4, 4};

        private void mvp(Segment s, GridBeat g, int bar, int degree) {
            drive(s, g, bar, degree, false);
            if (g.downbeat() && Math.floorMod(bar, 4) == 0) add(g.time(), 2.0, Voice.CRASH, 0, 0.40f, -0.2f);
            double q = g.duration() / 4;
            int b = g.beatInBar();
            for (int m = 0; m < MOTIF_STEPS.length; m++) {
                int step = MOTIF_STEPS[m];
                if (step / 4 != b) continue;
                int lift = Math.floorMod(bar, 2) == 1 && m == MOTIF_STEPS.length - 1 ? 1 : 0;
                int pitch = s.root() + 12 + s.track().interval(degree + MOTIF_DEGREES[m] + lift);
                add(g.time() + (step % 4) * q, q * MOTIF_LENGTHS[m], Voice.LEAD, pitch, 0.30f, 0.1f);
            }
        }

        private void outro(Segment s, GridBeat g, int bar, int degree) {
            boolean firstHalf = g.time() < s.start() + (s.end() - s.start()) * 0.5;
            double q = g.duration() / 4;
            if (g.downbeat() || g == s.beats().getFirst()) {
                chord(g.time(), barLength(g), Voice.PAD, s, degree, 0, 0.38f);
                add(g.time(), barLength(g), Voice.BASS, s.root() - 12 + s.track().interval(degree), 0.42f, 0f);
            }
            int b = g.beatInBar();
            if (firstHalf) {
                Kit kit = s.track().kit;
                for (int k = 0; k < 4; k++) {
                    int step = b * 4 + k;
                    double t = g.time() + k * q;
                    if (k == 0 && (b == 0 || b == 2)) add(t, 0.45, Voice.KICK, 0, 0.62f, 0f);
                    if (kit.snare(step)) add(t, 0.3, kit.snareVoice, 0, 0.45f, -0.05f);
                    if (k % 2 == 0) add(t, 0.06, Voice.HAT, 0, 0.20f, 0.3f);
                }
            }
            for (int k = 0; k < 4; k += 2) {
                int idx = b * 2 + k / 2;
                int tone = s.track().interval(degree + 2 * (idx % 3)) + 12;
                add(g.time() + k * q, g.duration() * 1.5, Voice.PLUCK, s.root() + tone, 0.24f, idx % 2 == 0 ? -0.3f : 0.3f);
            }
        }

        private void finale(Segment s, GridBeat g) {
            double length = Math.max(0.5, total - 0.25 - g.time());
            chord(g.time(), length, Voice.PAD, s, 0, 0, 0.46f);
            add(g.time(), length, Voice.BASS, s.root() - 12, 0.40f, 0f);
            add(g.time(), Math.min(length, 3.0), Voice.BELL, s.root() + 24, 0.30f, 0f);
            add(g.time(), Math.min(length, 3.0), Voice.BELL, s.root() + 12 + s.track().interval(4), 0.22f, 0.2f);
            add(g.time(), 1.6, Voice.SUB_BOOM, 0, 0.45f, 0f);
            add(g.time(), 2.2, Voice.CRASH, 0, 0.40f, 0f);
            filter.add(new Ramp(g.time() + 0.4, total, 16_000f, 1_400f));
            hits.add(new Hit(g.time(), "finale"));
        }

        // ------------------------------------------------------------ anchors

        private @Nullable Segment segmentStartingAt(Anchor a) {
            for (Segment s : segments) if (s.from() == a) return s;
            return null;
        }

        private @Nullable Segment segmentEndingAt(Anchor a) {
            for (Segment s : segments) if (s.to() == a) return s;
            return null;
        }

        /** Riser, accelerating snare roll and (for the final blow) a reverse cymbal into {@code a}. */
        private void buildUp(Anchor a) {
            double windowStart = buildWindowStart(a);
            double hitAt = a.time - a.gap;
            if (hitAt - windowStart < 0.25) return;
            Segment before = segmentEndingAt(a);
            int root = before == null ? Track.MIDNIGHT_EXPRESS.root : before.root();
            boolean big = a.beat == null || a.beat.type() == ReplayVideoScript.BeatType.KILL
                    || a.beat.type() == ReplayVideoScript.BeatType.EXPLOSION;
            float intensity = big ? 1f : 0.7f;
            GridBeat last = gridBefore(a.time);
            double d = last == null ? 0.5 : last.duration();
            double riserStart = Math.max(windowStart, hitAt - Math.min(2.4, 4 * d));
            if (hitAt - riserStart >= 0.25) {
                notes.add(new Note(riserStart, hitAt - riserStart, Voice.RISER, root + 12, 0.45f * intensity, 0f));
            }
            if (a.beat != null && a.beat.finalBlow() && hitAt - windowStart >= 0.5) {
                double start = Math.max(windowStart, hitAt - 1.6);
                notes.add(new Note(start, hitAt - start, Voice.REVERSE, 0, 0.55f, 0f));
            }
            // Snare roll on the grid: eighths two beats out, sixteenths (32nds for the final blow) on the last beat.
            for (GridBeat g : grid) {
                if (g.time() >= a.time - EPS) break;
                if (g.time() < windowStart - EPS || g.time() < a.time - 2 * d - 0.01) continue;
                boolean lastBeat = g.time() >= a.time - d - 0.01;
                int div = lastBeat ? (a.beat != null && a.beat.finalBlow() ? 8 : 4) : 2;
                for (int k = 0; k < div; k++) {
                    double t = g.time() + k * g.duration() / div;
                    if (t >= hitAt - 0.01) break;
                    double progress = (t - (a.time - 2 * d)) / (2 * d);
                    float vel = (float) (0.22 + 0.48 * Math.clamp(progress, 0, 1)) * intensity;
                    notes.add(new Note(t, Math.min(0.2, hitAt - t), Voice.SNARE, 0, vel, (k % 2 == 0) ? -0.15f : 0.15f));
                }
            }
        }

        private void accent(Anchor a) {
            Segment after = segmentStartingAt(a);
            if (after == null) return;
            double t = a.time;
            switch (a.kind) {
                case ACTION, MVP -> {
                    add(t, 0.5, Voice.KICK, 0, 1f, 0f);
                    add(t, 2.2, Voice.CRASH, 0, 0.75f, 0f);
                    add(t, 1.4, Voice.SUB_BOOM, 0, a.kind == AnchorKind.ACTION ? 0.55f : 0.65f, 0f);
                    hits.add(new Hit(t, a.kind == AnchorKind.ACTION ? "replay_start" : "mvp"));
                }
                case IMPACT -> impact(a, after);
                case REVIEW -> {
                    add(t, 0.5, Voice.KICK, 0, 0.9f, 0f);
                    add(t, 2.6, Voice.CRASH, 0, 0.70f, 0f);
                    add(t, 1.8, Voice.SUB_BOOM, 0, 0.75f, 0f);
                    chord(t, Math.min(3.0, after.end() - t), Voice.STAB, after, 0, 0, 0.30f);
                    filter.add(new Ramp(t, t + 0.6, 16_000f, 4_500f));
                    filter.add(new Ramp(after.end() - 0.3, after.end(), 4_500f, OPEN_FILTER));
                    hits.add(new Hit(t, "replay_end"));
                }
                case VICTORY -> {
                    add(t, 2.4, Voice.CRASH, 0, 0.55f, 0f);
                    add(t, 1.6, Voice.SUB_BOOM, 0, 0.55f, 0f);
                    hits.add(new Hit(t, "victory"));
                }
                case OUTRO -> {
                    add(t, 1.8, Voice.CRASH, 0, 0.40f, 0f);
                    add(t, 0.45, Voice.KICK, 0, 0.7f, 0f);
                    hits.add(new Hit(t, "outro"));
                }
            }
        }

        private void impact(Anchor a, Segment s) {
            ReplayVideoScript.Beat beat = a.beat;
            if (beat == null) return;
            boolean skipTransition = skipsPostCloseupTransition(s);
            double t = a.time, end = skipTransition ? s.end() : beat.end();
            int root = s.root();
            float muffle;
            String kind = beat.type().name().toLowerCase(Locale.ROOT);
            switch (beat.type()) {
                case KILL -> {
                    muffle = 1_100f;
                    add(t, 0.5, Voice.KICK, 0, 1f, 0f);
                    add(t, 1.6, Voice.SUB_BOOM, 0, beat.finalBlow() ? 1f : 0.85f, 0f);
                    add(t, 2.4, Voice.CRASH, 0, beat.finalBlow() ? 1f : 0.75f, 0f);
                    chord(t, 0.6, Voice.STAB, s, 0, 0, 0.42f);
                    if (beat.streak() >= 2) {
                        chord(t, 0.5, Voice.STAB, s, 0, 1, 0.30f);
                        for (int k = 0; k < Math.min(beat.streak(), 4); k++) {
                            add(t + 0.09 * k, 0.3, Voice.LEAD, root + 12 + s.track().interval(4 + 2 * k), 0.28f, 0.15f);
                        }
                        kind += "_streak" + beat.streak();
                    }
                    if (beat.firstBlood()) {
                        add(t, 2.8, Voice.BELL, root + 12, 0.50f, 0f);
                        add(t, 2.8, Voice.BELL, root + 19, 0.28f, 0.2f);
                        kind += "_first_blood";
                    }
                    if (beat.finalBlow()) {
                        add(t + 0.25, 0.5, Voice.KICK, 0, 0.8f, 0f);
                        chord(t, 1.2, Voice.STAB, s, 0, 2, 0.24f);
                        kind += "_final";
                    }
                    chord(t, end - t, Voice.PAD, s, 0, 0, 0.32f);
                    add(t, end - t, Voice.BASS, root - 12, 0.30f, 0f);
                    double heartLimit = skipTransition ? Math.min(end, nextCut(t)) - 0.35 : end - 0.15;
                    for (double h = t + 0.5; h < heartLimit; h += 0.95) {
                        add(h, 0.3, Voice.HEART, 0, 0.62f, 0f);
                        add(h + 0.2, 0.3, Voice.HEART, 0, 0.45f, 0f);
                    }
                }
                case REVIVAL -> {
                    muffle = 2_600f;
                    add(t, 0.45, Voice.KICK, 0, 0.6f, 0f);
                    add(t, 0.6, Voice.OPEN_HAT, 0, 0.5f, 0.2f);
                    int[] arpeggio = {2, 4, 6, 9};
                    for (int k = 0; k < arpeggio.length; k++) {
                        add(t + 0.18 * k, 2.0, Voice.BELL, root + 24 + s.track().interval(arpeggio[k]), 0.38f, -0.3f + 0.2f * k);
                    }
                    chord(t, end - t, Voice.PAD, s, 2, 0, 0.38f);
                }
                case EXPLOSION -> {
                    muffle = 650f;
                    add(t, 0.5, Voice.KICK, 0, 1f, 0f);
                    add(t, 1.6, Voice.SUB_BOOM, 0, 1f, 0f);
                    add(t, 2.6, Voice.CRASH, 0, 0.9f, 0f);
                    add(t + 0.05, end - t - 0.05, Voice.RING, 0, 0.7f, 0f);
                    chord(t, end - t, Voice.PAD, s, 0, -1, 0.25f);
                }
                case DEFUSE -> {
                    muffle = 2_600f;
                    add(t, 0.45, Voice.KICK, 0, 0.55f, 0f);
                    add(t, 0.6, Voice.OPEN_HAT, 0, 0.4f, 0.2f);
                    chord(t, 2.2, Voice.BELL, s, 2, 2, 0.30f);
                    chord(t, end - t, Voice.PAD, s, 2, 0, 0.34f);
                }
                case SHIELD -> {
                    muffle = 1_800f;
                    add(t, 0.45, Voice.KICK, 0, 0.7f, 0f);
                    add(t, 1.6, Voice.CRASH, 0, 0.45f, 0f);
                    chord(t, 0.5, Voice.STAB, s, 0, 1, 0.40f);
                    chord(t, end - t, Voice.PAD, s, 3, 0, 0.30f);
                }
                default -> {
                    muffle = 2_000f;
                    add(t, 0.45, Voice.KICK, 0, 0.7f, 0f);
                    add(t, 0.5, Voice.OPEN_HAT, 0, 0.35f, 0.2f);
                    chord(t, 0.4, Voice.STAB, s, 0, 1, 0.32f);
                    chord(t, end - t, Voice.PAD, s, 0, 0, 0.28f);
                }
            }
            double settle = Math.min(beat.release(), t + 0.25);
            filter.add(new Ramp(t + 0.04, Math.max(t + 0.08, settle), 16_000f, muffle));
            filter.add(new Ramp(beat.release(), Math.max(beat.release() + 0.05, end), muffle, OPEN_FILTER));
            if (!skipTransition) {
                // The groove re-enters on the first beat after the slow motion with a cymbal.
                for (GridBeat g : grid) {
                    if (g.time() >= end - EPS && g.time() < s.end() - EPS) {
                        if (!breakdown(g.time())) add(g.time(), 1.6, Voice.CRASH, 0, 0.35f, -0.2f);
                        break;
                    }
                }
            }
            hits.add(new Hit(t, kind));
        }

        /** Adds explosive orchestral drop & sub-bass pulse for Mode 4 Tier 5 Apotheosis segments. */
        private void tier5EncoreDrops() {
            for (var ds : directedSegments) {
                if (ds.kind() == ReplayCinemaDirector.SegmentKind.TIER5_SUCTION_DOLLY) {
                    // Deep pulsing sub-bass heartbeat under the 4-second pre-roll
                    for (double t = ds.videoStart(); t < ds.videoEnd() - 0.2; t += 0.75) {
                        notes.add(new Note(t, 0.35, Voice.HEART, 0, 0.65f, 0f));
                    }
                } else if (ds.kind() == ReplayCinemaDirector.SegmentKind.TIER5_ORBIT_ENCORE) {
                    double drop = ds.videoStart();
                    notes.add(new Note(drop, 0.55, Voice.KICK, 0, 1.0f, 0f));
                    notes.add(new Note(drop, 2.2, Voice.SUB_BOOM, 0, 1.0f, 0f));
                    notes.add(new Note(drop, 2.8, Voice.CRASH, 0, 0.95f, 0f));
                    filter.add(new Ramp(Math.max(0, drop - 0.05), drop + 0.15, 1_200f, OPEN_FILTER));
                    hits.add(new Hit(drop, "tier5_apotheosis_encore"));
                }
            }
        }

        /** Unfeatured kills, revivals and explosions get a small accent snapped to the nearest sixteenth. */
        private void feedAccents() {
            for (ReplayVideoScript.Feed feed : script.feeds()) {
                LastMatchReplay.Kind kind = feed.node().kind();
                if (kind != LastMatchReplay.Kind.KILL && kind != LastMatchReplay.Kind.PLAYER_REVIVAL
                        && kind != LastMatchReplay.Kind.GRENADE_EXPLOSION && kind != LastMatchReplay.Kind.BOMB_DETONATE) continue;
                double time = feed.time();
                if (time < actionStart || time >= reviewStart || aftermathAt(time) != null || nearBuild(time)) continue;
                GridBeat g = gridBefore(time + EPS);
                if (g == null) continue;
                double q = g.duration() / 4;
                double snapped = g.time() + Math.round((time - g.time()) / q) * q;
                Segment s = segmentAt(snapped);
                if (s == null || skipsPostCloseupTransition(s)) continue;
                switch (kind) {
                    case KILL -> {
                        add(snapped, 0.4, Voice.TOM, s.root() - 12, 0.55f, -0.2f);
                        add(snapped, 0.5, Voice.OPEN_HAT, 0, 0.35f, 0.3f);
                        chord(snapped, 0.35, Voice.STAB, s, 0, 1, 0.20f);
                        hits.add(new Hit(snapped, "feed_kill"));
                    }
                    case PLAYER_REVIVAL -> {
                        add(snapped, 1.8, Voice.BELL, s.root() + 24 + s.track().interval(2), 0.30f, 0.2f);
                        add(snapped + q, 1.6, Voice.BELL, s.root() + 24 + s.track().interval(4), 0.24f, -0.2f);
                        hits.add(new Hit(snapped, "feed_revival"));
                    }
                    default -> {
                        add(snapped, 1.2, Voice.SUB_BOOM, 0, 0.50f, 0f);
                        add(snapped, 0.6, Voice.OPEN_HAT, 0, 0.35f, 0f);
                        hits.add(new Hit(snapped, "feed_explosion"));
                    }
                }
            }
        }

        private boolean nearBuild(double time) {
            for (ReplayVideoScript.Beat b : beats) if (time >= b.approach() - 0.2 && time < b.end()) return true;
            return false;
        }

        private @Nullable Segment segmentAt(double time) {
            for (Segment s : segments) if (time >= s.start() - EPS && time < s.end() - EPS) return s;
            return null;
        }

        private void planDucks() {
            if (schedule == null) return;
            for (ReplaySoundCuePlan.VoiceCue voice : schedule.voices()) {
                ducks.add(new Duck(voice.startTime(), voice.endTime(), VOICE_DUCK, 0.08, 0.35));
            }
            for (ReplaySoundCuePlan.SoundCue sound : schedule.sounds()) {
                if (sound.eventIndex() >= 0) {
                    ducks.add(new Duck(sound.time(), sound.time() + 0.18, SFX_DUCK, 0.01, 0.25));
                } else {
                    ducks.add(new Duck(sound.time(), sound.time() + TITLE_HORN, 0.55f, 0.02, 0.6));
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static @Nullable MatchWinFaction winningFaction(LastMatchReplay replay) {
        if (replay.participants().stream().noneMatch(LastMatchReplay.Participant::winner)) return null;
        MatchWinKind status = MatchWinKind.fromWinStatusName(replay.winStatus());
        MatchWinKind result = status == MatchWinKind.NONE || status == MatchWinKind.OTHER ? replay.winKind() : status;
        if (result == MatchWinKind.NO_PLAYER) return null;
        if (result == MatchWinKind.KILLER) return MatchWinFaction.KILLER;
        if (result == MatchWinKind.INNOCENT) return MatchWinFaction.PASSENGER;

        MatchWinFaction sharedFaction = null;
        for (LastMatchReplay.Participant p : replay.participants()) {
            if (!p.winner() || p.faction() == null) continue;
            if (sharedFaction != null && sharedFaction != p.faction()) return MatchWinFaction.NEUTRAL;
            sharedFaction = p.faction();
        }
        return sharedFaction;
    }

    private static int[] sorted(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    private static int countAtOrBefore(int[] sortedTicks, double tick) {
        int lo = 0, hi = sortedTicks.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedTicks[mid] <= tick + EPS) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
