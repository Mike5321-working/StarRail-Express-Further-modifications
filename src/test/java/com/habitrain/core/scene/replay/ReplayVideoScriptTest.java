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
import static org.junit.jupiter.api.Assertions.*;

public class ReplayVideoScriptTest {
    public static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    public static LastMatchReplay replay(int duration, List<Node> events, List<Span> spans) {
        var p0 = Position.quantize(2, 1, 3);
        var p1 = Position.quantize(20, 1, 4);
        List<Participant> people = new ArrayList<>();
        for (UUID id : List.of(A, B, C)) {
            people.add(new Participant(id, "P" + id.toString().substring(0, 3), "starrailexpress:civilian", 0xFF33EEFF,
                    "starrailexpress:civilian", 0xFF33EEFF, id == A ? MatchWinFaction.KILLER : MatchWinFaction.PASSENGER,
                    id == A, 30, id == A ? 2 : 0, 5, 1,
                    List.of(new Sample(0, p0, (byte) 0, ALIVE), new Sample(duration, p1, (byte) 0, ALIVE))));
        }
        return new LastMatchReplay(UUID.randomUUID(), 0, duration, "Probe line", "murder", "KILLERS", MatchWinKind.INNOCENT,
                "", "", "", 0, new SceneBounds(0, 0, 0, 26, 7, 7), null, people, events, spans, A, 2, false);
    }

    public static Node node(int tick, Kind kind, UUID actor, UUID target) {
        return new Node(tick, kind, actor, target, Position.quantize(4, 1, 3), Position.quantize(8, 1, 4), "starrailexpress:knife", 0);
    }

    @Test void timeIsMonotoneAndFreezesExactlyOnEveryHighlight() {
        var events = List.of(node(300, Kind.ITEM_USED, A, null), node(900, Kind.KILL, A, B), node(1300, Kind.KILL, A, C),
                node(2500, Kind.PLAYER_REVIVAL, B, null), node(4000, Kind.GRENADE_EXPLOSION, C, null));
        var script = new ReplayVideoScript(replay(6000, events, List.of(new Span(SpanKind.BLACKOUT, null, 3000, 3400))));
        double previous = -1;
        for (int f = 0; f < script.frames(30); f++) {
            var s = script.sample(f / 30.0);
            assertTrue(s.replayTick() >= previous - 1e-9, "match clock went backwards at frame " + f);
            assertTrue(s.replayTick() >= 0 && s.replayTick() <= 6000);
            previous = s.replayTick();
        }
        assertEquals(4, script.beats().size());
        for (var beat : script.beats()) {
            var mid = script.sample((beat.impact() + beat.release()) / 2);
            assertEquals(ReplayVideoScript.Act.ACTION, mid.act());
            assertEquals(beat.tick(), mid.replayTick(), 1e-6);
            assertTrue(mid.freeze());
            assertEquals(beat.impact(), script.videoTimeOf(beat.tick()), 0.02);
            assertTrue(beat.approach() <= beat.impact() && beat.impact() < beat.release() && beat.release() < beat.end());
        }
        assertEquals(1, script.feeds().size(), "non-featured events go to the feed");
        assertEquals(1, script.spans().size());
        assertTrue(script.total() > 25 && script.total() < 90, "film length " + script.total());
    }

    @Test void streaksFirstBloodAndFinalBlowAreTagged() {
        var events = List.of(node(900, Kind.KILL, A, B), node(1300, Kind.KILL, A, C));
        var beats = new ReplayVideoScript(replay(3000, events, List.of())).beats();
        assertTrue(beats.getFirst().firstBlood());
        assertEquals(1, beats.getFirst().streak());
        assertEquals(2, beats.getLast().streak());
        assertTrue(beats.getLast().finalBlow());
    }

    @Test void busyMatchesKeepTenWellSeparatedBeatsAndQuietMatchesStillPlay() {
        List<Node> events = new ArrayList<>();
        for (int i = 0; i < 60; i++) events.add(node(100 + i * 15, i % 2 == 0 ? Kind.KILL : Kind.GUNSHOT, A, B));
        var busy = new ReplayVideoScript(replay(2000, events, List.of()));
        assertTrue(busy.beats().size() <= ReplayVideoScript.MAX_BEATS);
        for (int i = 1; i < busy.beats().size(); i++) {
            assertTrue(busy.beats().get(i).tick() - busy.beats().get(i - 1).tick() >= 24);
            assertTrue(busy.beats().get(i).approach() >= busy.beats().get(i - 1).end() - 1e-9);
        }
        var quiet = new ReplayVideoScript(replay(0, List.of(), List.of()));
        assertTrue(quiet.frames(30) > 0);
        assertEquals(ReplayVideoScript.Act.OUTRO, quiet.sample(quiet.total() - 0.01).act());
    }

    @Test void firstTickHighlightUsesImpactTimeWithoutMovingMatchClock() {
        var kill = node(0, Kind.KILL, A, B);
        var script = new ReplayVideoScript(replay(1200, List.of(kill), List.of()));
        var beat = script.beats().getFirst();
        assertTrue(beat.impact() > script.videoTimeOf(0));
        assertEquals(beat.impact(), script.eventTime(kill), 1e-9);
        assertEquals(script.videoTimeOf(0), script.eventTime(node(0, Kind.ITEM_USED, A, null)), 1e-9);
    }

    @Test void outroExtendsForMultiPagePassengerLists() {
        var small = new ReplayVideoScript(replay(1200, List.of(), List.of()));
        assertEquals(ReplayVideoScript.OUTRO, small.actLength(ReplayVideoScript.Act.OUTRO), 1e-6);

        var p0 = Position.quantize(2, 1, 3);
        List<Participant> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            UUID id = UUID.randomUUID();
            sixteen.add(new Participant(id, "P" + i, "starrailexpress:civilian", 0xFF33EEFF,
                    "starrailexpress:civilian", 0xFF33EEFF, MatchWinFaction.PASSENGER, i == 0,
                    60, 0, 10 - (i % 5), 1, List.of(new Sample(0, p0, (byte) 0, ALIVE))));
        }
        var crowd = new LastMatchReplay(UUID.randomUUID(), 0, 1200, "Probe line", "murder", "PASSENGERS",
                MatchWinKind.INNOCENT, "", "", "", 0, new SceneBounds(0, 0, 0, 26, 7, 7), null,
                sixteen, List.of(), List.of(), sixteen.getFirst().id(), 0, false);
        var paged = new ReplayVideoScript(crowd);
        assertTrue(paged.actLength(ReplayVideoScript.Act.OUTRO) > ReplayVideoScript.OUTRO,
                "16 players should get extended multi-page outro");
    }

    @Test void singlePlayerFocusFiltersBeatsAndEndsActionEarlyWhenNotRevived() {
        var p0 = Position.quantize(2, 1, 3);
        var p1 = Position.quantize(12, 1, 4);
        var p2 = Position.quantize(20, 1, 4);
        // B dies at tick 900 and is never revived; A and C continue until tick 6000.
        List<Participant> people = List.of(
                new Participant(A, "Alpha", "starrailexpress:killer", 0xFFFF4A52, "starrailexpress:killer", 0xFFFF4A52,
                        MatchWinFaction.KILLER, true, 300, 2, 10, 1,
                        List.of(new Sample(0, p0, (byte) 0, ALIVE), new Sample(6000, p2, (byte) 0, ALIVE))),
                new Participant(B, "Bravo", "starrailexpress:civilian", 0xFF7FD1FF, "starrailexpress:civilian", 0xFF7FD1FF,
                        MatchWinFaction.PASSENGER, false, 45, 0, 2, 0,
                        List.of(new Sample(0, p0, (byte) 0, ALIVE), new Sample(880, p1, (byte) 0, ALIVE),
                                new Sample(900, p1, (byte) 0, (byte) 0))),
                new Participant(C, "Charlie", "starrailexpress:civilian", 0xFF7FD1FF, "starrailexpress:civilian", 0xFF7FD1FF,
                        MatchWinFaction.PASSENGER, false, 200, 0, 3, 1,
                        List.of(new Sample(0, p0, (byte) 0, ALIVE), new Sample(4000, p2, (byte) 0, (byte) 0)))
        );
        var killB = node(900, Kind.KILL, A, B);
        var killC = node(4000, Kind.KILL, A, C);
        var lateItem = node(3500, Kind.ITEM_USED, A, null);
        var replay = new LastMatchReplay(UUID.randomUUID(), 0, 6000, "Probe line", "murder", "KILLERS",
                MatchWinKind.INNOCENT, "", "", "", 0, new SceneBounds(0, 0, 0, 26, 7, 7), null,
                people, List.of(killB, lateItem, killC), List.of(new Span(SpanKind.BLACKOUT, null, 3000, 3400)), A, 2, false);

        var focusB = new ReplayVideoScript(replay, B);
        assertEquals(B, focusB.focusPlayer());
        assertEquals(900 + ReplayVideoScript.ELIMINATION_TAIL_TICKS, focusB.actionEndTick());
        assertEquals(1, focusB.beats().size());
        assertSame(killB, focusB.beats().getFirst().node());
        assertTrue(focusB.feeds().isEmpty(), "events after early cutoff must not leak into feeds");
        assertTrue(focusB.spans().isEmpty(), "spans after early cutoff must not leak into video");
        assertEquals(focusB.actionEndTick(), focusB.sample(focusB.actStart(ReplayVideoScript.Act.REVIEW) - 0.01).replayTick(), 1.0);

        // Focus C: only gets killC (ignores killB which doesn't involve C), cuts off after tick 4000.
        var focusC = new ReplayVideoScript(replay, C);
        assertEquals(4000 + ReplayVideoScript.ELIMINATION_TAIL_TICKS, focusC.actionEndTick());
        assertEquals(1, focusC.beats().size());
        assertSame(killC, focusC.beats().getFirst().node());

        // Focus A (survived whole match): keeps full duration and both kills.
        var focusA = new ReplayVideoScript(replay, A);
        assertEquals(6000, focusA.actionEndTick());
        assertEquals(2, focusA.beats().size());

        // Unknown UUID falls back to global export.
        var fallback = new ReplayVideoScript(replay, UUID.randomUUID());
        assertNull(fallback.focusPlayer());
        assertEquals(6000, fallback.actionEndTick());
    }

    @Test void singlePlayerFocusDoesNotCutOffEarlyWhenRevived() {
        var p0 = Position.quantize(2, 1, 3);
        var p1 = Position.quantize(14, 1, 4);
        List<Participant> people = List.of(
                new Participant(A, "Alpha", "starrailexpress:killer", 0xFFFF4A52, "starrailexpress:killer", 0xFFFF4A52,
                        MatchWinFaction.KILLER, false, 300, 1, 5, 0,
                        List.of(new Sample(0, p0, (byte) 0, ALIVE), new Sample(6000, p1, (byte) 0, ALIVE))),
                new Participant(B, "Bravo", "starrailexpress:civilian", 0xFF7FD1FF, "starrailexpress:civilian", 0xFF7FD1FF,
                        MatchWinFaction.PASSENGER, true, 260, 0, 8, 1,
                        List.of(new Sample(0, p0, (byte) 0, ALIVE), new Sample(900, p0, (byte) 0, (byte) 0),
                                new Sample(1500, p0, (byte) 0, (byte) (ALIVE | BREAK)), new Sample(6000, p1, (byte) 0, ALIVE)))
        );
        var events = List.of(node(900, Kind.KILL, A, B), node(1500, Kind.PLAYER_REVIVAL, B, B));
        var replay = new LastMatchReplay(UUID.randomUUID(), 0, 6000, "Probe line", "murder", "PASSENGERS",
                MatchWinKind.INNOCENT, "", "", "", 0, new SceneBounds(0, 0, 0, 26, 7, 7), null,
                people, events, List.of(), B, 1, false);

        var focusB = new ReplayVideoScript(replay, B);
        assertEquals(6000, focusB.actionEndTick());
        assertEquals(2, focusB.beats().size());
    }

    @Test void singlePlayerFocusWithoutBeatsDoesNotCrushLongMatchIntoSingleFiveSecondGap() {
        var replay = replay(6000, List.of(), List.of());
        var focusB = new ReplayVideoScript(replay, B);
        double actionLen = focusB.actLength(ReplayVideoScript.Act.ACTION);
        assertTrue(actionLen > 16.0 && actionLen < 32.0,
                "5-minute single-player export without personal beats should keep natural hyper-lapse pacing, got " + actionLen + "s");
        assertTrue(focusB.fastSpeed() > 8.0 && focusB.fastSpeed() < 20.0,
                "fastSpeed should match actual pacing (~13x), got " + focusB.fastSpeed());
        assertTrue(focusB.total() > 30.0 && focusB.total() < 75.0,
                "total film length should stay within normal bounds, got " + focusB.total() + "s");
    }
}
