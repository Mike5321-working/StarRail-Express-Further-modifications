package com.habitrain.core.game.sre.replay;

import io.wifi.starrailexpress.api.replay.ReplayEventTypes.EventType;
import io.wifi.starrailexpress.api.replay.ReplayTimelineEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.VisualTape;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchReplayRecorderTest {
    @Test void fortyRecipientsShareEncodingButKeepIndependentEventStreams() throws Exception {
        var recorder = new MatchVisualRecorder(new UUID(5, 1), new SceneBounds(0, 0, 0, 16, 16, 16));
        var broadcast = new ClientboundLevelEventPacket(2001, BlockPos.ZERO, 1, false);
        var individual = new ClientboundLevelEventPacket(2001, BlockPos.ZERO, 2, false);
        var encodes = new AtomicInteger();
        Supplier<byte[]> encode = () -> new byte[]{(byte) encodes.incrementAndGet()};
        for (int index = 0; index < 40; index++) {
            recorder.appendRecipient(new UUID(1, index), 10, VisualTape.LEVEL_EVENT, broadcast, encode);
        }
        assertEquals(1, encodes.get());
        var first = new UUID(1, 0);
        recorder.appendRecipient(first, 11, VisualTape.LEVEL_EVENT, broadcast, encode);
        recorder.appendRecipient(first, 11, VisualTape.LEVEL_EVENT, individual, encode);
        var tapes = recorder.tape.compress();
        assertEquals(40, tapes.size());
        for (var entry : tapes.entrySet()) {
            var events = VisualTape.decode(entry.getValue());
            assertEquals(entry.getKey().equals(first) ? 3 : 1, events.size());
            assertArrayEquals(new byte[]{1}, events.getFirst().data());
            if (entry.getKey().equals(first)) {
                assertArrayEquals(new byte[]{2}, events.get(1).data());
                assertArrayEquals(new byte[]{3}, events.get(2).data());
            }
        }
    }

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
