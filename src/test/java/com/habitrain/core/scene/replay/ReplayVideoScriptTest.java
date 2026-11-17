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
}
