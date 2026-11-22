package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.model.SceneBounds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static com.habitrain.core.scene.replay.ReplayVideoScriptTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayMusicPlanTest {
    private static final double EPS = 1e-6;

    private static ReplayVideoScript busyScript() {
        var events = List.of(node(300, Kind.ITEM_USED, A, null), node(900, Kind.KILL, A, B), node(1300, Kind.KILL, A, C),
                node(2500, Kind.PLAYER_REVIVAL, B, null), node(4000, Kind.GRENADE_EXPLOSION, C, null));
        return new ReplayVideoScript(replay(6000, events, List.of()));
    }

    private static boolean onDownbeat(ReplayMusicPlan.Score score, double time) {
        return score.grid().stream().anyMatch(g -> g.downbeat() && Math.abs(g.time() - time) < EPS);
    }

    @Test void highlightsAndActChangesLandOnDownbeats() {
        var script = busyScript();
        var score = ReplayMusicPlan.compose(script, null);
        assertFalse(script.beats().isEmpty());
        for (var beat : script.beats()) assertTrue(onDownbeat(score, beat.impact()), "impact at " + beat.impact());
        for (var act : List.of(ReplayVideoScript.Act.ACTION, ReplayVideoScript.Act.REVIEW, ReplayVideoScript.Act.VICTORY,
                ReplayVideoScript.Act.MVP, ReplayVideoScript.Act.OUTRO)) {
            assertTrue(onDownbeat(score, script.actStart(act)), act + " at " + script.actStart(act));
        }
        for (var g : score.grid()) {
            assertEquals(ReplayMusicPlan.BEAT, g.duration(), EPS, "one tempo for the whole film, beat at " + g.time());
            double k = g.time() / ReplayMusicPlan.BEAT;
            assertEquals(Math.rint(k), k, 1e-6, "beat off the global grid at " + g.time());
        }
        for (int i = 1; i < score.grid().size(); i++) {
            assertEquals(ReplayMusicPlan.BEAT, score.grid().get(i).time() - score.grid().get(i - 1).time(), EPS,
                    "grid must be continuous at " + score.grid().get(i).time());
        }
    }

    @Test void everyHighlightGetsSilenceThenATypedHit() {
        var script = busyScript();
        var score = ReplayMusicPlan.compose(script, null);
        for (var beat : script.beats()) {
            double t = beat.impact();
            assertEquals(0f, score.gainAt(t - 0.03), 1e-6, "silence right before the impact at " + t);
            assertTrue(score.gainAt(t + 0.01) > 0.5f, "music back on the impact at " + t);
            assertTrue(has(score, ReplayMusicPlan.Voice.KICK, t, t), "kick on the impact at " + t);
            switch (beat.type()) {
                case KILL -> {
                    assertTrue(has(score, ReplayMusicPlan.Voice.SUB_BOOM, t, t));
                    assertTrue(has(score, ReplayMusicPlan.Voice.HEART, t + 0.1, beat.end()), "heartbeat after a kill");
                }
                case REVIVAL -> assertTrue(has(score, ReplayMusicPlan.Voice.BELL, t, t + 0.6), "bells after a revival");
                case EXPLOSION -> assertTrue(has(score, ReplayMusicPlan.Voice.RING, t, t + 0.1), "tinnitus after a blast");
                default -> {}
            }
            assertTrue(score.cutoffAt((beat.impact() + beat.release()) / 2) < 5_000f, "aftermath is muffled");
            assertEquals(ReplayMusicPlan.OPEN_FILTER, score.cutoffAt(beat.end() + 0.01), 1f, "filter reopens after slow motion");
        }
        assertTrue(score.hits().stream().anyMatch(h -> h.kind().equals("replay_start")));
        assertTrue(score.hits().stream().anyMatch(h -> h.kind().equals("replay_end")));
        assertTrue(score.hits().stream().anyMatch(h -> h.kind().contains("first_blood")));
        assertTrue(score.hits().stream().anyMatch(h -> h.kind().contains("final")));
    }

    @Test void sustainedNotesAreCutBeforeEverySilence() {
        var score = ReplayMusicPlan.compose(busyScript(), null);
        for (var duck : score.ducks()) {
            if (duck.depth() > 0) continue;
            for (var note : score.notes()) {
                assertFalse(note.time() < duck.start() - EPS && note.time() + note.length() > duck.start() + 1e-4,
                        note + " rings into the silence at " + duck.start());
                assertFalse(note.time() >= duck.start() + EPS && note.time() < duck.end() - EPS, note + " starts inside a silence");
            }
        }
    }

    @Test void tracksFollowTheMatchAndTheWinner() {
        var script = busyScript();
        var score = ReplayMusicPlan.compose(script, null);
        var sections = score.sections();
        assertEquals(ReplayMusicPlan.Mood.INTRO, sections.getFirst().mood());
        var firstAction = score.sectionAt(script.actStart(ReplayVideoScript.Act.ACTION) + 0.1);
        assertEquals(ReplayMusicPlan.Mood.EXPLORE, firstAction.mood(), "suspense before first blood");
        assertEquals(ReplayMusicPlan.Track.SHADOW_HUNT, firstAction.track());
        var afterFirstKill = score.sectionAt(script.beats().getFirst().impact() + 0.1);
        assertTrue(afterFirstKill.mood().ordinal() > ReplayMusicPlan.Mood.EXPLORE.ordinal());
        assertEquals(ReplayMusicPlan.Track.STATION_DAWN, score.sectionAt(script.actStart(ReplayVideoScript.Act.REVIEW) + 0.1).track());
        assertEquals(ReplayMusicPlan.Track.CRIMSON_FINALE, score.sectionAt(script.actStart(ReplayVideoScript.Act.VICTORY) + 0.1).track(),
                "the killer won");
        assertTrue(score.tracks().size() >= 4, "tracks " + score.tracks());
        ReplayMusicPlan.Mood previous = ReplayMusicPlan.Mood.EXPLORE;
        for (var s : sections) {
            if (s.mood() == ReplayMusicPlan.Mood.REVIEW) break;
            if (s.mood() == ReplayMusicPlan.Mood.INTRO) continue;
            assertTrue(s.mood().ordinal() >= previous.ordinal(), "action intensity only rises");
            previous = s.mood();
        }
    }

    @Test void passengerWinPlaysTheBrightAnthem() {
        var base = replay(3000, List.of(node(900, Kind.KILL, A, B)), List.of());
        List<Participant> people = new ArrayList<>();
        for (var p : base.participants()) {
            boolean passenger = !p.id().equals(A);
            people.add(new Participant(p.id(), p.name(), p.startRole(), p.startColor(), p.endRole(), p.endColor(),
                    passenger ? MatchWinFaction.PASSENGER : MatchWinFaction.KILLER, passenger, p.survivalSeconds(), p.kills(),
                    p.score(), p.itemUses(), p.track()));
        }
        var passengers = new LastMatchReplay(UUID.randomUUID(), 0, 3000, "Probe line", "murder", "PASSENGERS",
                MatchWinKind.INNOCENT, "", "", "", 0, new SceneBounds(0, 0, 0, 26, 7, 7), null, people, base.events(),
                base.spans(), B, 1, false);
        assertEquals(MatchWinFaction.PASSENGER, ReplayMusicPlan.winningFaction(passengers));
        var script = new ReplayVideoScript(passengers);
        var score = ReplayMusicPlan.compose(script, null);
        assertEquals(ReplayMusicPlan.Track.GOLDEN_TERMINAL, score.sectionAt(script.actStart(ReplayVideoScript.Act.VICTORY) + 0.1).track());
    }

    @Test void noWinnerUsesTheReviewTrackInsteadOfAnAnthem() {
        var base = replay(1200, List.of(), List.of());
        var replay = new LastMatchReplay(base.roundId(), 0, 1200, "Probe line", "murder", "NO_PLAYER",
                MatchWinKind.NO_PLAYER, "", "", "", 0, new SceneBounds(0, 0, 0, 26, 7, 7), null,
                List.of(), List.of(), List.of(), null, 0, false);
        var script = new ReplayVideoScript(replay);
        var score = ReplayMusicPlan.compose(script, null);
        assertNull(ReplayMusicPlan.winningFaction(replay));
        assertEquals(ReplayMusicPlan.Track.STATION_DAWN,
                score.sectionAt(script.actStart(ReplayVideoScript.Act.VICTORY) + 0.1).track());
    }

    @Test void earlyLastKillStillResumesTheGrooveBeforeReview() {
        var script = new ReplayVideoScript(replay(6000, List.of(node(300, Kind.KILL, A, B)), List.of()));
        var score = ReplayMusicPlan.compose(script, null);
        var kill = script.beats().getFirst();
        double review = script.actStart(ReplayVideoScript.Act.REVIEW);
        assertTrue(review - kill.end() > 3 * ReplayMusicPlan.BEAT);
        assertTrue(has(score, ReplayMusicPlan.Voice.KICK, kill.end(), review - ReplayMusicPlan.BEAT));
    }

    @Test void bloodbathsSwitchToTheFastTrack() {
        List<Node> events = new ArrayList<>();
        for (int i = 0; i < 12; i++) events.add(node(200 + i * 90, Kind.KILL, i % 2 == 0 ? A : C, B));
        var script = new ReplayVideoScript(replay(1600, events, List.of()));
        var score = ReplayMusicPlan.compose(script, null);
        assertTrue(score.tracks().contains(ReplayMusicPlan.Track.BLOOD_RUSH), "tracks " + score.tracks());
    }

    @Test void voiceAndEffectsDuckTheMusic() {
        var script = busyScript();
        double at = script.actStart(ReplayVideoScript.Act.REVIEW) + 1.5;
        short[] samples = new short[4800];
        var clip = new ReplayVoiceData.Clip(0, 0, 0, samples.length, ReplayVoiceData.encodeAdpcm(samples));
        var voice = new ReplaySoundCuePlan.VoiceCue(clip, at, at, at + 1.0, 0f);
        var schedule = new ReplaySoundCuePlan.Schedule(List.of(), List.of(voice));
        var plain = ReplayMusicPlan.compose(script, null);
        var ducked = ReplayMusicPlan.compose(script, schedule);
        assertEquals(plain.gainAt(at + 0.5) * ReplayMusicPlan.VOICE_DUCK, ducked.gainAt(at + 0.5), 1e-4);
        assertEquals(plain.gainAt(at + 2.0), ducked.gainAt(at + 2.0), 1e-4, "music recovers after the voice");
    }

    @Test void sameReplaySameScoreAndQuietMatchesStillScore() {
        var script = busyScript();
        assertEquals(ReplayMusicPlan.compose(script, null), ReplayMusicPlan.compose(script, null));
        var quiet = new ReplayVideoScript(replay(0, List.of(), List.of()));
        var score = ReplayMusicPlan.compose(quiet, null);
        assertFalse(score.notes().isEmpty());
        assertEquals(0f, score.levelAt(quiet.total()), 1e-6, "fades out at the very end");
    }

    @Test void tensionRisesThroughTheFeelNotTheTempo() {
        List<Node> events = new ArrayList<>();
        for (int i = 0; i < 12; i++) events.add(node(200 + i * 90, Kind.KILL, i % 2 == 0 ? A : C, B));
        var script = new ReplayVideoScript(replay(1600, events, List.of()));
        var score = ReplayMusicPlan.compose(script, null);
        var calm = score.sectionAt(script.actStart(ReplayVideoScript.Act.REVIEW) + 0.1);
        assertEquals(ReplayMusicPlan.Feel.HALF_TIME, calm.feel(), "the review is half-time");
        var climax = score.sections().stream().filter(s -> s.mood() == ReplayMusicPlan.Mood.CLIMAX).findFirst().orElseThrow();
        assertEquals(ReplayMusicPlan.Feel.DOUBLE_TIME, climax.feel());
        // Double time fills the beat with sixteenth hats; half time keeps a quarter-note pulse.
        assertEquals(4, hatsPerBeat(score, climax), "sixteenth hats in the climax");
        var explore = new ReplayVideoScript(replay(6000, List.of(node(4000, Kind.KILL, A, B)), List.of()));
        var quietScore = ReplayMusicPlan.compose(explore, null);
        var search = quietScore.sectionAt(explore.actStart(ReplayVideoScript.Act.ACTION) + 0.1);
        assertEquals(ReplayMusicPlan.Feel.HALF_TIME, search.feel(), "searching before first blood");
        assertEquals(1, hatsPerBeat(quietScore, search), "quarter hats while searching");
    }

    @Test void scriptAnchorsSitOnTheGlobalGrid() {
        var script = busyScript();
        for (var act : ReplayVideoScript.Act.values()) {
            double k = script.actStart(act) / ReplayVideoScript.BEAT;
            assertEquals(Math.rint(k), k, 1e-9, act + " starts off the grid");
        }
        for (var beat : script.beats()) {
            double k = beat.impact() / ReplayVideoScript.BEAT;
            assertEquals(Math.rint(k), k, 1e-9, "impact off the grid");
        }
        assertEquals(8, ReplayVideoScript.fitBeats(3.9, 0));
        assertEquals(8, ReplayVideoScript.fitBeats(4.3, 0), "prefers the whole bar");
        assertEquals(6, ReplayVideoScript.fitBeats(3.05, 0), "half bar when a whole bar is too far");
        assertEquals(5, ReplayVideoScript.fitBeats(1.0, 2.3), "never shorter than the minimum");
    }

    /** Most hi-hat hits found in any single beat of the section. */
    private static long hatsPerBeat(ReplayMusicPlan.Score score, ReplayMusicPlan.Section s) {
        long best = 0;
        for (var g : score.grid()) {
            if (g.time() < s.start() - EPS || g.time() >= s.end() - EPS) continue;
            long count = score.notes().stream().filter(n -> (n.voice() == ReplayMusicPlan.Voice.HAT
                    || n.voice() == ReplayMusicPlan.Voice.OPEN_HAT) && n.time() >= g.time() - EPS
                    && n.time() < g.time() + g.duration() - EPS).count();
            best = Math.max(best, count);
        }
        return best;
    }

    @Test void finalKillIntoReviewCancelsPostCloseupTransitionBgm() {
        for (int lastTick : new int[]{1200, 1195}) {
            var events = List.of(node(500, Kind.KILL, A, B), node(lastTick, Kind.KILL, A, C));
            var script = new ReplayVideoScript(replay(1200, events, List.of()));
            var score = ReplayMusicPlan.compose(script, null);
            assertEquals(2, script.beats().size());
            var firstKill = script.beats().getFirst();
            var lastKill = script.beats().getLast();
            double reviewStart = script.actStart(ReplayVideoScript.Act.REVIEW);

            assertTrue(has(score, ReplayMusicPlan.Voice.CRASH, firstKill.end(), firstKill.end() + ReplayMusicPlan.BEAT),
                    "earlier kill still re-enters the replay groove");
            assertTrue(has(score, ReplayMusicPlan.Voice.KICK, firstKill.end(), lastKill.approach()),
                    "earlier kill still resumes the groove before the next highlight");

            var postFinalNotes = score.notes().stream()
                    .filter(n -> n.time() >= lastKill.end() - EPS && n.time() < reviewStart - EPS
                            && n.voice() != ReplayMusicPlan.Voice.HEART)
                    .toList();
            assertTrue(postFinalNotes.isEmpty(),
                    "last kill at tick " + lastTick + " must not restart transition BGM before review: " + postFinalNotes);
            assertTrue(has(score, ReplayMusicPlan.Voice.KICK, lastKill.impact(), lastKill.impact()));
            assertTrue(has(score, ReplayMusicPlan.Voice.HEART, lastKill.impact() + 0.1, reviewStart));
            assertTrue(onDownbeat(score, reviewStart));
            assertEquals(ReplayMusicPlan.Track.STATION_DAWN, score.sectionAt(reviewStart + 0.1).track());

            for (var duck : score.ducks()) {
                if (duck.depth() > 0) continue;
                for (var note : score.notes()) {
                    assertFalse(note.time() < duck.start() - EPS && note.time() + note.length() > duck.start() + 1e-4,
                            note + " rings into the silence at " + duck.start());
                }
            }
        }
    }

    @Test void singleKillEndingMatchStillCancelsPostCloseupTransitionBgm() {
        var script = new ReplayVideoScript(replay(800, List.of(node(800, Kind.KILL, A, B)), List.of()));
        var score = ReplayMusicPlan.compose(script, null);
        assertEquals(1, script.beats().size());
        var onlyKill = script.beats().getFirst();
        assertFalse(onlyKill.finalBlow(), "single-kill match does not set finalBlow flag");
        double reviewStart = script.actStart(ReplayVideoScript.Act.REVIEW);
        var postNotes = score.notes().stream()
                .filter(n -> n.time() >= onlyKill.end() - EPS && n.time() < reviewStart - EPS
                        && n.voice() != ReplayMusicPlan.Voice.HEART)
                .toList();
        assertTrue(postNotes.isEmpty(), "single-kill match must still cancel post-closeup transition BGM: " + postNotes);
    }

    @Test void lastKillFollowedByLaterHighlightKeepsTransitionBgm() {
        var script = busyScript();
        var score = ReplayMusicPlan.compose(script, null);
        var lastKill = script.beats().stream()
                .filter(b -> b.type() == ReplayVideoScript.BeatType.KILL)
                .reduce((first, second) -> second)
                .orElseThrow();
        var nextBeat = script.beats().get(lastKill.index() + 1);
        assertTrue(has(score, ReplayMusicPlan.Voice.CRASH, lastKill.end(), lastKill.end() + ReplayMusicPlan.BEAT),
                "re-entry cymbal stays when another highlight follows the last kill");
        assertTrue(has(score, ReplayMusicPlan.Voice.KICK, lastKill.end(), nextBeat.approach()),
                "groove stays when another highlight follows the last kill");
    }

    @Test void levelIdsRoundTrip() {
        assertEquals(ReplayMusicPlan.Level.HIGH, ReplayMusicPlan.Level.fromId(" HIGH "));
        assertEquals(ReplayMusicPlan.Level.OFF, ReplayMusicPlan.Level.fromId("off"));
        assertEquals(ReplayMusicPlan.Level.MEDIUM, ReplayMusicPlan.Level.fromId("loud"));
        assertEquals(ReplayMusicPlan.Level.MEDIUM, ReplayMusicPlan.Level.fromId(null));
        assertFalse(ReplayMusicPlan.Level.OFF.enabled());
    }

    private static boolean has(ReplayMusicPlan.Score score, ReplayMusicPlan.Voice voice, double from, double to) {
        return score.notes().stream().anyMatch(n -> n.voice() == voice && n.time() >= from - EPS && n.time() <= to + EPS);
    }
}
