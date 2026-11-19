package com.habitrain.core.game.sre.replay;

import io.wifi.starrailexpress.api.replay.ReplayEventTypes.EventType;
import io.wifi.starrailexpress.api.replay.ReplayTimelineEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchReplayRecorderTest {
    @Test void outOfRangeLeavesOnceAndReentryDoesNotInterpolate() {
        var track = new java.util.ArrayList<Sample>();
        track.add(new Sample(0, Position.quantize(0, 0, 0), (byte) 0, ALIVE));
        assertNull(Position.quantize(3000, 0, 0));
        MatchReplayRecorder.markAbsent(track, 5);
        MatchReplayRecorder.markAbsent(track, 10);
        track.add(new Sample(20, Position.quantize(2, 0, 0), (byte) 0, (byte) (ALIVE | BREAK)));
        var sampler = new com.habitrain.core.scene.replay.ReplayTrackSampler(track);
        assertEquals(3, track.size());
        assertFalse(sampler.sample(15).visible());
        assertTrue(sampler.sample(20).visible());
        assertFalse(sampler.connects(2));
    }
    @Test void onlyPublicUpstreamEventsEnterTheBoardTimeline() {
        for (EventType type : new EventType[]{EventType.ITEM_USED, EventType.TRAP_TRIGGERED}) {
            assertEquals(Kind.valueOf(type.name()), MatchReplayRecorder.publicEventKind(event(type, false)));
            assertNull(MatchReplayRecorder.publicEventKind(event(type, true)));
        }
        assertNull(MatchReplayRecorder.publicEventKind(event(EventType.PLAYER_KILL, false)),
                "A public attempt does not prove the victim was eliminated");
    }

    @Test void backfireMovesTheImpactToTheShooterAndRemovesTheHitHighlight() {
        UUID shooter = new UUID(1, 2), aimedAt = new UUID(3, 4);
        Position muzzle = Position.quantize(2, 4, 6);
        Position originalTarget = Position.quantize(20, 4, 6);
        Position impact = Position.quantize(2, 2, 6);
        Node recorded = new Node(12, Kind.GUNSHOT, shooter, aimedAt, muzzle, originalTarget, "sre:revolver", HIT | GUN_DROP);
        assertTrue(recorded.highlight());

        Node backfire = MatchReplayRecorder.backfireShot(recorded, impact);
        assertEquals(shooter, backfire.target());
        assertEquals(impact, backfire.to());
        assertEquals(muzzle, backfire.from());
        assertEquals(BACKFIRE | GUN_DROP, backfire.flags());
        assertFalse(backfire.highlight());
        assertEquals(muzzle, MatchReplayRecorder.backfireShot(recorded, null).to());
    }

    private static ReplayTimelineEvent event(EventType type, boolean hidden) {
        return new ReplayTimelineEvent(new UUID(0, 1), type, 100, 10, null, null,
                Component.literal("event"), hidden, Map.of());
    }
}
