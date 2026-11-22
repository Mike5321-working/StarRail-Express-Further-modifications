package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.habitrain.core.scene.replay.ReplayVideoScriptTest;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    @Test void singlePlayerPerspectiveStaysInCloseUpEmphasizesPlayerAndRemainsContinuous() {
        var focus = ReplayVideoScriptTest.B;
        var events = List.of(
                ReplayVideoScriptTest.node(900, LastMatchReplay.Kind.KILL, ReplayVideoScriptTest.A, ReplayVideoScriptTest.C),
                ReplayVideoScriptTest.node(2400, LastMatchReplay.Kind.PLAYER_REVIVAL, focus, focus));
        var replay = ReplayVideoScriptTest.replay(6000, events, List.of());
        var globalScript = new ReplayVideoScript(replay);
        var focusScript = new ReplayVideoScript(replay, focus);
        var stage = new ReplayVideoCamera.Stage(3.45f, 6.25f, 1.7f, 0.25f, 0.3f, 0.12f);

        double probeTime = focusScript.actStart(ReplayVideoScript.Act.ACTION) + 1.0;
        var globalShot = ReplayVideoCamera.at(globalScript, SUBJECTS, stage, probeTime);
        var focusShot = ReplayVideoCamera.at(focusScript, SUBJECTS, stage, probeTime);
        var actorPos = SUBJECTS.actor(focus, focusScript.sample(probeTime).replayTick());
        assertNotNull(actorPos);
        assertTrue(focusShot.eye().distance(actorPos) < globalShot.eye().distance(actorPos) * 0.7,
                "single-player camera should be significantly closer to the focused player than global overview");
        assertTrue(focusShot.emphasis(focus) >= 0.7 - 1e-6);
        assertEquals(0.0, focusShot.emphasis(ReplayVideoScriptTest.A), 1e-6);

        Vector3f previous = null;
        for (int f = 0; f < focusScript.frames(30); f++) {
            double time = f / 30.0;
            var shot = ReplayVideoCamera.at(focusScript, SUBJECTS, stage, time);
            var eye = shot.eye();
            assertTrue(Float.isFinite(eye.x) && Float.isFinite(eye.y) && Float.isFinite(eye.z));
            if (previous != null && focusScript.sample(time).wipe() < 0 && focusScript.sample(time - 1 / 30.0).wipe() < 0) {
                assertTrue(eye.distance(previous) < 1.0, "single-player camera jumped at " + time + ": " + eye.distance(previous));
            }
            previous = eye;
        }
    }

    @Test void singlePlayerFollowCameraStaysSmoothDuringTurnsStopsAndJumps() {
        var focus = ReplayVideoScriptTest.B;
        int duration = 3600;
        // Realistic 5-tick recorded player motion: sprint-jumping (vertical bounce + horizontal burst),
        // abrupt pauses and reversals across a 120-block train.
        List<LastMatchReplay.Sample> track = new ArrayList<>();
        double x = 10, y = 2, z = 10, vx = 0, vy = 0, vz = 0;
        for (int t = 0; t <= duration; t++) {
            int phase = (t / 90) % 4;
            if (phase == 0 || phase == 2) {
                double dir = phase == 0 ? 1.0 : -1.0;
                if (y <= 2.0001) {
                    vy = 0.42;
                    vx = dir * 0.40;
                    vz = (phase == 0 ? 0.05 : -0.05);
                } else {
                    vx = vx * 0.91 + dir * 0.026;
                    vz = vz * 0.91;
                }
            } else if (phase == 1) {
                vx *= 0.55;
                vz = vz * 0.55 + (z < 12 ? 0.10 : -0.10);
            } else {
                vx *= 0.5;
                vz *= 0.5;
            }
            x = Math.clamp(x + vx, 2.0, 110.0);
            z = Math.clamp(z + vz, 2.0, 18.0);
            y += vy;
            vy = (vy - 0.08) * 0.98;
            if (y <= 2.0) { y = 2.0; vy = 0.0; }
            if (t % 5 == 0) {
                track.add(new LastMatchReplay.Sample(t, LastMatchReplay.Position.quantize(x, y, z), (byte) 0, LastMatchReplay.ALIVE));
            }
        }
        var sampler = new ReplayTrackSampler(track);
        float scale = 6.25f / 120f;
        ReplayVideoCamera.Subjects movingSubjects = new ReplayVideoCamera.Subjects() {
            @Override public Vector3f centroid(double tick) { return actor(focus, tick); }
            @Override public @Nullable Vector3f actor(@Nullable UUID id, double tick) {
                var pose = sampler.sample(tick);
                if (!pose.visible()) return null;
                return new Vector3f((float) ((pose.x() - 60.0) * scale),
                        ReplayBoardSite.MAP_BASE + (float) (pose.y() * scale),
                        (float) ((pose.z() - 10.0) * scale));
            }
            @Override public Vector3f event(LastMatchReplay.Node node, boolean to) { return actor(focus, node.tick()); }
            @Override public @Nullable Vector3f mvp() { return actor(focus, duration); }
            @Override public Vector3f winners() { return actor(focus, duration); }
        };

        var replay = ReplayVideoScriptTest.replay(duration, List.of(), List.of());
        var script = new ReplayVideoScript(replay, focus);
        var stage = new ReplayVideoCamera.Stage(3.45f, 6.25f, 1.05f, 0.35f, 0.4f, 0.12f);

        double start = script.actStart(ReplayVideoScript.Act.ACTION) + 0.8;
        double end = script.actStart(ReplayVideoScript.Act.REVIEW) - 0.8;
        List<Vector3f> eyes = new ArrayList<>();
        for (int f = (int) Math.ceil(start * 30); f <= (int) Math.floor(end * 30); f++) {
            eyes.add(ReplayVideoCamera.at(script, movingSubjects, stage, f / 30.0).eye());
        }
        assertTrue(eyes.size() > 150, "single-player action should span enough frames for smooth playback");

        double sumAccelSq = 0, sumYAccelSq = 0, sumJerkSq = 0;
        int accCount = 0, jerkCount = 0;
        for (int i = 2; i < eyes.size(); i++) {
            Vector3f v1 = new Vector3f(eyes.get(i)).sub(eyes.get(i - 1));
            Vector3f v0 = new Vector3f(eyes.get(i - 1)).sub(eyes.get(i - 2));
            Vector3f acc = new Vector3f(v1).sub(v0);
            sumAccelSq += acc.lengthSquared();
            sumYAccelSq += acc.y * acc.y;
            accCount++;
            if (i >= 3) {
                Vector3f vPrev = new Vector3f(eyes.get(i - 2)).sub(eyes.get(i - 3));
                Vector3f prevAcc = new Vector3f(v0).sub(vPrev);
                Vector3f jerk = new Vector3f(acc).sub(prevAcc);
                sumJerkSq += jerk.lengthSquared();
                jerkCount++;
            }
        }
        double rmsAccel = Math.sqrt(sumAccelSq / accCount);
        double rmsYAccel = Math.sqrt(sumYAccelSq / accCount);
        double rmsJerk = Math.sqrt(sumJerkSq / jerkCount);
        String metrics = String.format("rmsAccel=%.5f, rmsYAccel=%.5f, rmsJerk=%.5f", rmsAccel, rmsYAccel, rmsJerk);
        // The old sparse 7-tap 0.2s kernel aliased turns and sprint-jumps into 5 Hz stepping (rmsJerk > 0.025, rmsYAccel > 0.008).
        assertTrue(rmsAccel < 0.012, "camera acceleration too jerky (" + metrics + ")");
        assertTrue(rmsYAccel < 0.001, "vertical camera bounce too jerky during sprint-jumps (" + metrics + ")");
        assertTrue(rmsJerk < 0.002, "camera jerk too high / periodic stepping (" + metrics + ")");
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
