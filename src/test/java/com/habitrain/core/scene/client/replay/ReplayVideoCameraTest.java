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
        @Override public Vector3f winners() { return new Vector3f(1, 0.3f, 0); }
    };

    @Test void nearPlaneClearsTheMapAndShotsStayContinuousOutsideWipes() {
        var events = List.of(ReplayVideoScriptTest.node(900, LastMatchReplay.Kind.KILL, ReplayVideoScriptTest.A, ReplayVideoScriptTest.B),
                ReplayVideoScriptTest.node(2500, LastMatchReplay.Kind.GRENADE_EXPLOSION, ReplayVideoScriptTest.B, null));
        var script = new ReplayVideoScript(ReplayVideoScriptTest.replay(6000, events, List.of()));
        for (float height : new float[]{0.2f, 1.5f, 4f}) {
            var stage = new ReplayVideoCamera.Stage(3.45f, 6.25f, 1.7f, height, height + 0.1f);
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
}
