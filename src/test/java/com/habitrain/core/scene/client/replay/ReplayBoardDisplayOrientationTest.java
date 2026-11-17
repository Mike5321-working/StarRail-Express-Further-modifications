package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.block.ReplayBoardSettings;
import com.habitrain.core.scene.replay.ReplayBoardPlacement;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayBoardDisplayOrientationTest {
    private static final float MAP_BASE = ReplayBoardSite.MAP_BASE;

    @Test
    void fourSidedPlaquesFaceOutwardWithoutMirroredTextOrCornerOverlap() {
        float[] traySizes = {1.3f, 2.3f, 3.425f};
        float[][] expectedForwardXZ = {
                {0f, 1f},
                {1f, 0f},
                {0f, -1f},
                {-1f, 0f}
        };
        float[][] expectedRightXZ = {
                {1f, 0f},
                {0f, -1f},
                {-1f, 0f},
                {0f, 1f}
        };
        for (float half : traySizes) {
            float pScale = ReplayTrayPainter.plaqueScale(half);
            // Total plaque width including both end fan ornaments must fit cleanly between corner caps.
            float ornamentReach = (ReplayTrayPainter.BASE_PLAQUE_W * 0.5f + 0.11f) * pScale;
            float cornerCapInnerEdge = half - 0.23f;
            assertTrue(ornamentReach < cornerCapInnerEdge,
                    "Plaque ornaments (" + ornamentReach + ") overlap corner cap (" + cornerCapInnerEdge + ") at half=" + half);

            float expectedDist = half + ReplayTrayPainter.PLAQUE_STANDOFF * pScale;
            float expectedY = ReplayBoardSite.FRAME_TOP + 0.03f - (ReplayTrayPainter.PLAQUE_H * 0.5f + 0.01f) * pScale;
            Matrix4f board = new Matrix4f();

            for (int side = 0; side < 4; side++) {
                Matrix4f frame = ReplayTrayPainter.plaqueFrameMatrix(board, half, half, side);
                Vector3f origin = frame.transformPosition(0, 0, 0, new Vector3f());
                assertEquals(expectedY, origin.y, 1e-5f, "side=" + side);
                assertEquals(expectedForwardXZ[side][0] * expectedDist, origin.x, 1e-5f, "side=" + side);
                assertEquals(expectedForwardXZ[side][1] * expectedDist, origin.z, 1e-5f, "side=" + side);

                Vector3f normal = frame.transformDirection(0, 0, 1, new Vector3f()).normalize();
                Vector3f right = frame.transformDirection(1, 0, 0, new Vector3f()).normalize();
                Vector3f up = frame.transformDirection(0, 1, 0, new Vector3f()).normalize();

                // Outward normal tilts up by 20 degrees toward the standing viewer and faces strictly outward.
                assertEquals((float) Math.sin(Math.toRadians(20)), normal.y, 1e-5f);
                assertEquals(expectedForwardXZ[side][0], Math.signum(normal.x) * (Math.abs(normal.x) > 0.1f ? 1 : 0), 1e-5f);
                assertEquals(expectedForwardXZ[side][1], Math.signum(normal.z) * (Math.abs(normal.z) > 0.1f ? 1 : 0), 1e-5f);
                assertEquals(expectedRightXZ[side][0], right.x, 1e-5f);
                assertEquals(expectedRightXZ[side][1], right.z, 1e-5f);
                assertTrue(right.cross(up, new Vector3f()).dot(normal) > 0.999f, "Right-handed un-mirrored basis on side " + side);

                // Top of plaque stays below the corner boss cap height (FRAME_TOP + 0.06).
                Vector3f topEdge = frame.transformPosition(0, ReplayTrayPainter.PLAQUE_H * 0.5f * pScale, 0, new Vector3f());
                assertTrue(topEdge.y < ReplayBoardSite.FRAME_TOP + 0.03f, "Plaque protrudes above tray rim: " + topEdge.y);
            }
        }
    }

    @Test
    void eachSideShowsItsOwnClockWithoutDrawingTheBacksOfOtherPlaques() {
        var settings = new ReplayBoardSettings(16f, 0.25f, 1f, -2f, 37f);
        Matrix4f display = ReplayBoardPlacement.displayMatrix(new Matrix4f().rotateY(0.9f), settings, MAP_BASE);
        float half = 3.425f;
        for (int facing = 0; facing < 4; facing++) {
            Matrix4f front = ReplayTrayPainter.plaqueFrameMatrix(display, half, half, facing);
            Vector3f camera = front.transformPosition(0, 0.5f, 3, new Vector3f()).negate();
            Matrix4f cameraRelative = new Matrix4f().translation(camera).mul(display);
            for (int side = 0; side < 4; side++) {
                Matrix4f plaque = ReplayTrayPainter.plaqueFrameMatrix(cameraRelative, half, half, side);
                assertEquals(side == facing, ReplayTrayPainter.plaqueFacesCamera(plaque));
            }
        }
    }

    @Test
    void plaquesScaleProportionallyWithTableSizeAndTraySize() {
        Matrix4f table = new Matrix4f().translate(4f, 64f, -8f).rotateY((float) Math.toRadians(-90));
        float[] mapSizes = {1f, 6.25f, 16f, 32f};
        float half = 3.425f;
        float baseWidthAtDefault = measurePlaqueWidth(table, new ReplayBoardSettings(6.25f, 0.25f, 0, 0, 0), half, 0);
        float baseHeightAtDefault = measurePlaqueHeight(table, new ReplayBoardSettings(6.25f, 0.25f, 0, 0, 0), half, 0);

        for (float mapSize : mapSizes) {
            var settings = new ReplayBoardSettings(mapSize, 1.5f, 2f, -1f, 35f);
            float scale = settings.scale();
            for (int side = 0; side < 4; side++) {
                float w = measurePlaqueWidth(table, settings, half, side);
                float h = measurePlaqueHeight(table, settings, half, side);
                assertEquals(baseWidthAtDefault * scale, w, 1e-4f, "Width at mapSize=" + mapSize + ", side=" + side);
                assertEquals(baseHeightAtDefault * scale, h, 1e-4f, "Height at mapSize=" + mapSize + ", side=" + side);
            }
        }

        // Larger diorama tray half-size also scales the plaque proportionally before clamping.
        var defaultSettings = ReplayBoardSettings.DEFAULT;
        float idleW = measurePlaqueWidth(table, defaultSettings, 2.3f, 1);
        float matchW = measurePlaqueWidth(table, defaultSettings, 3.425f, 1);
        assertEquals(3.425f / 2.3f, matchW / idleW, 1e-4f);
    }

    @Test
    void honorsScreenTracksCameraViewAndScalesWithTableSize() {
        float[] yawsDeg = {0f, 45f, 90f, 135f, 180f, -90f, -45f};
        float[] pitchesDeg = {-60f, -15f, 0f, 25f, 60f, 85f};
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Matrix4f table = new Matrix4f().translate(10f, 70f, -5f).rotateY((float) Math.toRadians(-facing.toYRot()));
            var settings = new ReplayBoardSettings(12.5f, 1.0f, 1.5f, -2.0f, 55f);
            Matrix4f board = ReplayBoardPlacement.displayMatrix(table, settings, MAP_BASE);
            float trayHw = 3.425f;

            for (float yaw : yawsDeg) {
                for (float pitch : pitchesDeg) {
                    Quaternionf camRot = new Quaternionf().rotationYXZ(
                            (float) (Math.PI - Math.toRadians(yaw)), (float) Math.toRadians(-pitch), 0f);
                    Matrix4f honors = ReplayHonorsPainter.honorsMatrix(board, settings.scale(), trayHw, camRot, 1.0);

                    Vector3f expectedAnchor = board.transformPosition(0f,
                            ReplayBoardSite.FRAME_TOP + 0.38f + ReplayHonorsPainter.BASE_H * 0.5f, 0f, new Vector3f());
                    Vector3f actualAnchor = honors.transformPosition(0, 0, 0, new Vector3f());
                    assertTrue(expectedAnchor.distance(actualAnchor) < 1e-4f, "Anchor shifted off center");

                    Vector3f expectedRight = camRot.transform(new Vector3f(1, 0, 0));
                    Vector3f expectedUp = camRot.transform(new Vector3f(0, 1, 0));
                    Vector3f expectedNormal = camRot.transform(new Vector3f(0, 0, 1));
                    assertTrue(expectedRight.distance(honors.transformDirection(1, 0, 0, new Vector3f()).normalize()) < 1e-5f);
                    assertTrue(expectedUp.distance(honors.transformDirection(0, 1, 0, new Vector3f()).normalize()) < 1e-5f);
                    assertTrue(expectedNormal.distance(honors.transformDirection(0, 0, 1, new Vector3f()).normalize()) < 1e-5f);

                    Vector3f bottomCenter = honors.transformPosition(0, -ReplayHonorsPainter.BASE_H * 0.5f, 0, new Vector3f());
                    Vector3f trayRimLevel = board.transformPosition(0, ReplayBoardSite.FRAME_TOP, 0, new Vector3f());
                    assertTrue(bottomCenter.y > trayRimLevel.y,
                            "Honors bottom clipped into tray at pitch=" + pitch + ": " + bottomCenter.y + " <= " + trayRimLevel.y);
                }
            }
        }

        // Verify linear scaling of Honors screen dimensions and segment scale across map sizes and tray sizes.
        Quaternionf cam = new Quaternionf().rotationYXZ(0.4f, -0.2f, 0f);
        float baseWorldScale = ReplayHonorsPainter.honorsWorldScale(1.0f, 3.425f);
        assertEquals(1.0f, baseWorldScale, 1e-5f);
        for (float mapSize : new float[]{1f, 6.25f, 18.75f, 32f}) {
            var settings = new ReplayBoardSettings(mapSize, 0.25f, 0, 0, 0);
            Matrix4f board = ReplayBoardPlacement.displayMatrix(new Matrix4f(), settings, MAP_BASE);
            for (float trayHw : new float[]{2.3f, 3.425f, 5.0f}) {
                Matrix4f m = ReplayHonorsPainter.honorsMatrix(board, settings.scale(), trayHw, cam, 1.0);
                float expectedScale = settings.scale() * (trayHw / ReplayHonorsPainter.REF_TRAY_HALF);
                assertEquals(expectedScale, ReplayHonorsPainter.honorsWorldScale(settings.scale(), trayHw), 1e-5f);
                Vector3f left = m.transformPosition(-ReplayHonorsPainter.BASE_W * 0.5f, 0, 0, new Vector3f());
                Vector3f right = m.transformPosition(ReplayHonorsPainter.BASE_W * 0.5f, 0, 0, new Vector3f());
                Vector3f bot = m.transformPosition(0, -ReplayHonorsPainter.BASE_H * 0.5f, 0, new Vector3f());
                Vector3f top = m.transformPosition(0, ReplayHonorsPainter.BASE_H * 0.5f, 0, new Vector3f());
                assertEquals(ReplayHonorsPainter.BASE_W * expectedScale, left.distance(right), 1e-3f);
                assertEquals(ReplayHonorsPainter.BASE_H * expectedScale, bot.distance(top), 1e-3f);
            }
        }
    }

    private static float measurePlaqueWidth(Matrix4f table, ReplayBoardSettings settings, float half, int side) {
        Matrix4f board = ReplayBoardPlacement.displayMatrix(table, settings, MAP_BASE);
        var site = new ReplayBoardSite(net.minecraft.core.BlockPos.ZERO, Direction.NORTH, 4.0, board, table,
                settings.scale(), half, half, 1.0f, true, 0);
        Matrix4f m = ReplayTrayPainter.plaqueMatrix(site, side);
        Vector3f a = m.transformPosition(-ReplayTrayPainter.BASE_PLAQUE_W * 0.5f, 0, 0, new Vector3f());
        Vector3f b = m.transformPosition(ReplayTrayPainter.BASE_PLAQUE_W * 0.5f, 0, 0, new Vector3f());
        return a.distance(b);
    }

    private static float measurePlaqueHeight(Matrix4f table, ReplayBoardSettings settings, float half, int side) {
        Matrix4f board = ReplayBoardPlacement.displayMatrix(table, settings, MAP_BASE);
        var site = new ReplayBoardSite(net.minecraft.core.BlockPos.ZERO, Direction.NORTH, 4.0, board, table,
                settings.scale(), half, half, 1.0f, true, 0);
        Matrix4f m = ReplayTrayPainter.plaqueMatrix(site, side);
        Vector3f a = m.transformPosition(0, -ReplayTrayPainter.PLAQUE_H * 0.5f, 0, new Vector3f());
        Vector3f b = m.transformPosition(0, ReplayTrayPainter.PLAQUE_H * 0.5f, 0, new Vector3f());
        return a.distance(b);
    }
}
