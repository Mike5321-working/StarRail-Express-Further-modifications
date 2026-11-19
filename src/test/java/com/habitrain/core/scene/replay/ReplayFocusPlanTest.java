package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayFocusPlanTest {
    private static final SceneBounds BOUNDS = new SceneBounds(0, 0, 0, 100, 12, 20);

    @Test void sourceToPresentationTimeInvertsBothFastAndSlowPlayback() {
        for (int duration : new int[]{0, 3, 200, 8000}) {
            var timeline = new ReplayTimeline(100, duration, List.of(0, duration / 2, duration));
            int action = action(timeline);
            if (duration == 0) continue;
            for (int i = 0; i < action * 4; i++) {
                var frame = timeline.at(100 + ReplayTimeline.INTRO + i / 4, (i % 4) / 4f);
                // Very short replays hold their final source tick; that held tail has no unique inverse.
                if (frame.replayTick() >= duration) continue;
                assertEquals(i / 4.0, timeline.actionTickAt(frame.replayTick()), 1e-7);
            }
        }
    }

    @Test void killPushesInOvershootsRecoilsHoldsAndReleasesOnThePresentationClock() {
        var replay = replay(BOUNDS, List.of(node(3000, Kind.KILL, 70, 72, 0)));
        var timeline = new ReplayTimeline(replay);
        var plan = new ReplayFocusPlan(replay, timeline);
        double at = timeline.actionTickAt(3000);
        assertEquals(ReplayFocusPlan.WIDE, pose(plan, timeline, at - 0.1));
        assertEquals(1, pose(plan, timeline, at).zoom(), 1e-9);
        double peak = pose(plan, timeline, at + 3.5).zoom();
        double held = pose(plan, timeline, at + ReplayFocusPlan.ATTACK).zoom();
        assertTrue(peak > held && held > 2, "visible overshoot and recoil");
        assertEquals(held, pose(plan, timeline, at + 12).zoom(), 1e-9);
        assertTrue(pose(plan, timeline, at + 23).zoom() < held);
        assertEquals(ReplayFocusPlan.WIDE, pose(plan, timeline, at + ReplayFocusPlan.LENGTH));
        assertEquals(0, ReplayFocusPlan.impact(0), 1e-9);
        assertEquals(1, ReplayFocusPlan.impact(1), 1e-9);
    }

    @Test void weakerActionsCannotStealAKillAndRetargetingStartsWithoutASnap() {
        var kill = node(3000, Kind.KILL, 70, 72, 0);
        var incidental = node(3001, Kind.ITEM_USED, 10, 10, 0);
        var firstReplay = replay(BOUNDS, List.of(kill));
        var replay = replay(BOUNDS, List.of(kill, incidental));
        var timeline = new ReplayTimeline(replay);
        var reference = new ReplayFocusPlan(firstReplay, timeline);
        var plan = new ReplayFocusPlan(replay, timeline);
        double time = timeline.actionTickAt(3001) + 2;
        assertEquals(pose(reference, timeline, time), pose(plan, timeline, time));

        var second = node(3060, Kind.KILL, 20, 22, 0);
        var twoReplay = replay(BOUNDS, List.of(kill, second));
        var twoTime = new ReplayTimeline(twoReplay);
        var twoPlan = new ReplayFocusPlan(twoReplay, twoTime);
        var onePlan = new ReplayFocusPlan(firstReplay, twoTime);
        double switchAt = twoTime.actionTickAt(second.tick());
        assertEquals(pose(onePlan, twoTime, switchAt), pose(twoPlan, twoTime, switchAt));
    }

    @Test void rotatedMapsUseTheSameAnchorAsActorsAndPreserveTheEventFloorHeight() {
        var rotated = new SceneBounds(0, 0, 0, 20, 12, 100);
        var event = new Node(3000, Kind.KILL, null, null, Position.quantize(8, 6, 70),
                Position.quantize(10, 6, 72), "", 0);
        var replay = replay(rotated, List.of(event));
        var timeline = new ReplayTimeline(replay);
        var plan = new ReplayFocusPlan(replay, timeline);
        var pose = pose(plan, timeline, timeline.actionTickAt(3000) + 8);
        // Project midpoint (9,6,71) through the automatic +90-degree map turn.
        Vector3f midpoint = new Vector3f((71 - 50) * 0.0625f, 6 * 0.0625f, -(9 - 10) * 0.0625f);
        Vector3f focused = pose.apply(new Matrix4f()).transformPosition(midpoint);
        assertEquals(0, focused.x, 1e-5);
        assertEquals(0, focused.z, 1e-5);
        assertEquals(midpoint.y, focused.y, 1e-5);
    }

    @Test void highlightingTracksTheSelectedCueAndBlendsWhenTheCameraRetargets() {
        UUID attacker = new UUID(0, 1), victim = new UUID(0, 2), nextVictim = new UUID(0, 3), bystander = new UUID(0, 4);
        var first = new Node(3000, Kind.KILL, attacker, victim, Position.quantize(70, 2, 10),
                Position.quantize(72, 2, 10), "", 0);
        var second = new Node(3060, Kind.KILL, attacker, nextVictim, Position.quantize(20, 2, 10),
                Position.quantize(22, 2, 10), "", 0);
        var replay = replay(BOUNDS, List.of(first, second));
        var timeline = new ReplayTimeline(replay);
        var plan = new ReplayFocusPlan(replay, timeline);
        double start = timeline.actionTickAt(first.tick());
        double next = timeline.actionTickAt(second.tick());
        var previousOnly = new ReplayFocusPlan(replay(BOUNDS, List.of(first)), timeline);
        double previousWeight = sample(previousOnly, timeline, next).emphasis(victim);
        assertEquals(0, sample(plan, timeline, start - 0.01).emphasis(attacker));
        assertEquals(0, sample(plan, timeline, start).emphasis(victim), 1e-6);
        var switching = sample(plan, timeline, next);
        assertEquals(previousWeight, switching.emphasis(victim), 1e-6);
        assertEquals(0, switching.emphasis(nextVictim), 1e-6);
        var midpoint = sample(plan, timeline, next + ReplayFocusPlan.ATTACK / 2);
        assertEquals(0.5 + previousWeight * 0.5, midpoint.emphasis(attacker), 1e-6);
        assertEquals(previousWeight * 0.5, midpoint.emphasis(victim), 1e-6);
        assertEquals(0.5, midpoint.emphasis(nextVictim), 1e-6);
        assertEquals(0, midpoint.emphasis(bystander));
        assertEquals(0, sample(plan, timeline, next + ReplayFocusPlan.LENGTH).emphasis(attacker), 1e-6);
        assertEquals(0, plan.sample(new ReplayTimeline.Frame(ReplayTimeline.Phase.REVIEW, 0, 8000, false)).emphasis(nextVictim));
    }

    @Test void distantAttackersAndVictimsBothFitAndAllNonPlayActsStayWide() {
        var replay = replay(BOUNDS, List.of(node(3000, Kind.KILL, 5, 95, 0), node(7999, Kind.KILL, 70, 72, 0)));
        var timeline = new ReplayTimeline(replay);
        var plan = new ReplayFocusPlan(replay, timeline);
        var pose = pose(plan, timeline, timeline.actionTickAt(3000) + 8);
        assertTrue(pose.zoom() < 1.2, "wide shot for long-range engagement");
        for (var phase : List.of(ReplayTimeline.Phase.BUILD, ReplayTimeline.Phase.REVIEW, ReplayTimeline.Phase.HONORS)) {
            assertEquals(ReplayFocusPlan.WIDE, plan.at(new ReplayTimeline.Frame(phase, 0.2, 3000, false)));
        }
        var last = pose(plan, timeline, action(timeline) - 0.0001);
        assertEquals(1, last.zoom(), 1e-6);
        assertEquals(0, last.x(), 1e-6);
        assertEquals(ReplayFocusPlan.WIDE, new ReplayFocusPlan(replay(BOUNDS, List.of()), timeline)
                .at(new ReplayTimeline.Frame(ReplayTimeline.Phase.PLAY, 0.5, 4000, false)));
    }

    @Test void viewportRejectsOversizedMapGeometryForBothAutomaticOrientations() {
        for (int rotation : new int[]{0, 90}) {
            Matrix4f sourceToTray = new Matrix4f().translate(-1.7f, -0.2f, 0.6f).scale(2.4f)
                    .rotateY((float) Math.toRadians(rotation)).scale(0.0625f).translate(-50, 0, -10);
            var bounds = ReplayMapViewport.sourceBounds(sourceToTray, 3.2f, 3.2f);
            for (int x = 0; x < 100; x++) {
                for (int z = 0; z < 20; z++) {
                    var p = sourceToTray.transformPosition(x + 0.37f, 10, z + 0.23f, new Vector3f());
                    assertEquals(Math.abs(p.x) <= 3.2f && Math.abs(p.z) <= 3.2f,
                            bounds.contains(x + 0.37f, z + 0.23f));
                }
            }
        }
    }

    private static ReplayFocusPlan.Pose pose(ReplayFocusPlan plan, ReplayTimeline timeline, double tick) {
        return sample(plan, timeline, tick).pose();
    }
    private static ReplayFocusPlan.Sample sample(ReplayFocusPlan plan, ReplayTimeline timeline, double tick) {
        return plan.sample(new ReplayTimeline.Frame(ReplayTimeline.Phase.PLAY, tick / action(timeline), 0, false));
    }
    private static int action(ReplayTimeline timeline) {
        return timeline.cycleTicks() - ReplayTimeline.INTRO - ReplayTimeline.REVIEW - ReplayTimeline.HONORS;
    }
    private static Node node(int tick, Kind kind, double x, double tx, int flags) {
        return new Node(tick, kind, null, null, Position.quantize(x, 2, 10), Position.quantize(tx, 2, 10), "", flags);
    }
    private static LastMatchReplay replay(SceneBounds bounds, List<Node> events) {
        return new LastMatchReplay(new UUID(1, 2), 0, 8000, "train", "murder", "", MatchWinKind.NONE,
                "", "", "", 0, bounds, null, List.of(), events, List.of(), null, 0, false);
    }
}
