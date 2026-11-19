package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.habitrain.core.scene.replay.ReplayVideoScriptTest;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayVideoCameraTest {
    private static final ReplayVideoCamera.Subjects SUBJECTS = new ReplayVideoCamera.Subjects() {
        @Override public Vector3f centroid(double tick) { return new Vector3f((float) Math.sin(tick / 300) * 2.5f, 0.3f, 0.2f); }
        @Override public @Nullable Vector3f actor(@Nullable UUID id, double tick) { return new Vector3f(-1, 0.3f, 0.1f); }
        @Override public Vector3f event(LastMatchReplay.Node node, boolean to) { return to ? new Vector3f(1.2f, 0.3f, 0.2f) : new Vector3f(-0.8f, 0.3f, -0.1f); }
        @Override public @Nullable Vector3f mvp() { return new Vector3f(2, 0.3f, 0); }
        @Override public UUID mvpId() { return ReplayVideoScriptTest.A; }
        @Override public Vector3f winners() { return new Vector3f(1, 0.3f, 0); }
    };

    @Test void nearPlaneClearsTheMapAndShotsStayContinuousOutsideWipes() {
        var events = List.of(ReplayVideoScriptTest.node(900, LastMatchReplay.Kind.KILL, ReplayVideoScriptTest.A, ReplayVideoScriptTest.B),
                ReplayVideoScriptTest.node(2500, LastMatchReplay.Kind.GRENADE_EXPLOSION, ReplayVideoScriptTest.B, null));
        var script = new ReplayVideoScript(ReplayVideoScriptTest.replay(6000, events, List.of()));
        for (float height : new float[]{0.2f, 1.5f, 4f}) {
            var stage = new ReplayVideoCamera.Stage(3.45f, 6.25f, 1.7f, height, height + 0.1f, 0.12f);
            Vector3f previous = null;
            for (int f = 0; f < script.frames(30); f++) {
                double time = f / 30.0;
                var shot = ReplayVideoCamera.at(script, SUBJECTS, stage, time);
                var eye = shot.eye();
                assertTrue(Float.isFinite(eye.x) && Float.isFinite(eye.y) && Float.isFinite(eye.z));
                assertTrue(eye.y >= ReplayBoardSite.FRAME_TOP + 0.1f - 1e-4f);
                boolean above = Math.abs(eye.x) < stage.mapW() / 2 && Math.abs(eye.z) < stage.mapD() / 2;
                if (above) assertTrue(eye.y - ReplayVideoCamera.NEAR > ReplayVideoCamera.ceiling(stage.geometryTop()) + 0.2f, "near plane inside map at " + time);
                var intoView = shot.view().transformDirection(new Vector3f(shot.look()));
                assertEquals(-1, intoView.z, 1e-4);
                if (previous != null && script.sample(time).wipe() < 0 && script.sample(time - 1 / 30.0).wipe() < 0) {
                    assertTrue(eye.distance(previous) < 1.0, "camera jumped at " + time + ": " + eye.distance(previous));
                }
                previous = eye;
            }
        }
    }

    @Test void renderedHeadSizeFollowsTheDirectedPushAndPullAndOnlyHighlightsParticipants() {
        var actor = ReplayVideoScriptTest.A;
        var target = ReplayVideoScriptTest.B;
        var bystander = new UUID(0, 99);
        var event = ReplayVideoScriptTest.node(900, LastMatchReplay.Kind.KILL, actor, target);
        var script = new ReplayVideoScript(ReplayVideoScriptTest.replay(6000, List.of(event), List.of()));
        var stage = new ReplayVideoCamera.Stage(3.45f, 6.25f, 1.7f, 0.25f, 0.3f, 0.12f);
        var beat = script.beats().getFirst();
        var start = ReplayVideoCamera.at(script, SUBJECTS, stage, beat.approach() - 0.2);
        var close = ReplayVideoCamera.at(script, SUBJECTS, stage, beat.impact());
        var end = ReplayVideoCamera.at(script, SUBJECTS, stage, beat.end() + 1.0);
        assertEquals(0, start.emphasis(actor));
        assertEquals(1, close.emphasis(actor), 1e-6);
        assertEquals(1, close.emphasis(target), 1e-6);
        assertEquals(0, close.emphasis(bystander));
        assertEquals(0, end.emphasis(actor));
        assertTrue(screenHeight(close, actor) > screenHeight(close, bystander) * 1.25);
        assertTrue(screenHeight(close, actor) > screenHeight(start, actor) * 1.5);
        assertTrue(screenHeight(close, actor) > screenHeight(end, actor) * 1.5);
        var mvp = ReplayVideoCamera.at(script, SUBJECTS, stage, script.actStart(ReplayVideoScript.Act.MVP) + 2);
        assertEquals(1, mvp.emphasis(actor));
        assertEquals(0, mvp.emphasis(target));
    }

    private static float screenHeight(ReplayVideoCamera.Shot shot, UUID actor) {
        var point = new Vector3f(0, 0.3f, 0);
        var relative = new org.joml.Matrix4f().translation(new Vector3f(shot.eye()).negate());
        var sizing = new ReplayActorSizing(0.0625f, 1, relative, shot.view(), shot.projection(), shot::emphasis, true);
        float size = sizing.size(actor, point.x, point.y, point.z);
        var center = point.add(0, ReplayActorSizing.centerOffset(size), 0);
        var cameraUp = shot.rotation().transform(new Vector3f(0, size / 2, 0));
        return shot.toScreen(new Vector3f(center).sub(cameraUp), 1280, 720).y
                - shot.toScreen(new Vector3f(center).add(cameraUp), 1280, 720).y;
    }
}
