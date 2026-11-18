package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.block.ReplayBoardSettings;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayBoardButtonsTest {
    private static final Vec3 RIGHT = new Vec3(1, 0, 0), UP = new Vec3(0, 1, 0);

    @Test
    void crosshairOnPlaqueHitsAtItsDistance() {
        Vec3 eye = new Vec3(0, 1.6, 0), center = new Vec3(0, 1.6, 3);
        double t = ReplayBoardButtons.hitDistance(eye, new Vec3(0, 0, 1), center, RIGHT.reverse(), UP, 0.5, 0.12, 8);
        assertEquals(3.0, t, 1.0e-9);
    }

    @Test
    void rayOutsideThePlaqueMisses() {
        Vec3 eye = new Vec3(0, 1.6, 0), center = new Vec3(0, 1.6, 3);
        Vec3 right = new Vec3(-1, 0, 0);
        assertTrue(ReplayBoardButtons.hitDistance(eye, new Vec3(0.30, 0, 1).normalize(), center, right, UP, 0.5, 0.12, 8) < 0,
                "0.9 blocks to the side of a 0.5 half-width plaque");
        assertTrue(ReplayBoardButtons.hitDistance(eye, new Vec3(0, 0.12, 1).normalize(), center, right, UP, 0.5, 0.12, 8) < 0,
                "0.36 blocks above a 0.12 half-height plaque");
    }

    @Test
    void plaqueBehindTheViewerOrBeyondReachIsIgnored() {
        Vec3 eye = Vec3.ZERO;
        Vec3 right = new Vec3(-1, 0, 0);
        assertTrue(ReplayBoardButtons.hitDistance(eye, new Vec3(0, 0, 1), new Vec3(0, 0, -3), right, UP, 0.5, 0.5, 8) < 0);
        assertTrue(ReplayBoardButtons.hitDistance(eye, new Vec3(0, 0, 1), new Vec3(0, 0, 9), right, UP, 0.5, 0.5, 8) < 0);
    }

    @Test
    void edgeOfThePlaqueStillCounts() {
        Vec3 eye = Vec3.ZERO, center = new Vec3(0, 0, 2);
        Vec3 right = new Vec3(-1, 0, 0);
        Vec3 toEdge = new Vec3(0.49 / 2.0, 0, 1);
        assertTrue(ReplayBoardButtons.hitDistance(eye, toEdge, center, right, UP, 0.5, 0.5, 8) > 0);
    }

    @Test
    void interactReachGrowsWithTheTable() {
        double normal = ReplayBoardSettings.DEFAULT.interactReach();
        assertTrue(normal > 8.0, "must at least cover the old 8 block radius");
        var big = new ReplayBoardSettings(25f, 2f, 4f, 3f, 0f);
        assertTrue(big.interactReach() > normal + 8.0);
    }
}
