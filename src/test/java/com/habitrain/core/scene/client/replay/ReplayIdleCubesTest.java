package com.habitrain.core.scene.client.replay;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayIdleCubesTest {

    @Test
    void cubePoseBuildsOrthonormalBasisOutwardNormalsAndNonBowtieQuads() {
        Matrix4f board = new Matrix4f().translate(1.5f, -0.5f, -4.0f).rotateY(0.7f).scale(1.25f);
        ReplayIdleCubes.CubePose pose = new ReplayIdleCubes.CubePose();
        float half = 0.56f;
        pose.set(board, 0.2f, 1.35f, -0.1f, half, 0.615f, 1.12f, 0.785f,
                0xFF120E18, 0xFF5A401D, 0xFF0A0F1F, 0xFF2E2418, ReplayPalette.BRASS, ReplayPalette.CHAMPAGNE, 1.0f);

        Vector3f ux = new Vector3f(pose.uxx, pose.uxy, pose.uxz);
        Vector3f uy = new Vector3f(pose.uyx, pose.uyy, pose.uyz);
        Vector3f uz = new Vector3f(pose.uzx, pose.uzy, pose.uzz);
        assertEquals(1f, ux.length(), 1e-5f);
        assertEquals(1f, uy.length(), 1e-5f);
        assertEquals(1f, uz.length(), 1e-5f);
        assertEquals(0f, ux.dot(uy), 1e-5f);
        assertEquals(0f, uy.dot(uz), 1e-5f);
        assertEquals(0f, uz.dot(ux), 1e-5f);

        float expectedEdge = 2f * half;
        for (int f = 0; f < 6; f++) {
            int[] fv = ReplayIdleCubes.FACES[f];
            float fcx = 0, fcy = 0, fcz = 0;
            for (int k = 0; k < 4; k++) {
                int v0 = fv[k], v1 = fv[(k + 1) & 3];
                float dx = pose.lx[v1] - pose.lx[v0];
                float dy = pose.ly[v1] - pose.ly[v0];
                float dz = pose.lz[v1] - pose.lz[v0];
                float edgeLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                assertEquals(expectedEdge, edgeLen, 1e-4f, "Face " + f + " must follow perimeter, not bowtie diagonal");
                fcx += pose.lx[v0] * 0.25f;
                fcy += pose.ly[v0] * 0.25f;
                fcz += pose.lz[v0] * 0.25f;
            }
            float outDot = (fcx - pose.cx) * pose.localFaceNx[f]
                    + (fcy - pose.cy) * pose.localFaceNy[f]
                    + (fcz - pose.cz) * pose.localFaceNz[f];
            assertEquals(half, outDot, 1e-4f, "Face " + f + " normal must point outward");
            assertTrue(Float.isFinite(pose.faceNdotV[f]) && Math.abs(pose.faceNdotV[f]) <= 1.0001f);
        }
    }

    @Test
    void surfaceIntersectionFindsExactSeamsOnBothOverlappingCubesAcrossRotations() {
        Matrix4f board = new Matrix4f().translate(0, 0, -4);
        ReplayIdleCubes.CubePose a = new ReplayIdleCubes.CubePose();
        ReplayIdleCubes.CubePose b = new ReplayIdleCubes.CubePose();
        float[] qx = new float[4], qy = new float[4], qz = new float[4], out = new float[6];

        int totalSeams = 0;
        for (int step = 0; step < 60; step++) {
            double t = step * 0.17;
            a.set(board, 0f, 1.35f, 0f, 0.56f,
                    (float) (0.615 + 0.16 * Math.sin(t * 0.37)),
                    (float) (t * 0.32),
                    (float) (0.785 + 0.14 * Math.cos(t * 0.29)),
                    0, 0, 0, 0, 0, 0, 1f);
            b.set(board, 0.02f, 1.35f, -0.01f, 0.515f,
                    (float) (-0.72 + t * 0.24),
                    (float) (-t * 0.44 + 1.05),
                    (float) (0.52 - 0.19 * Math.sin(t * 0.47)),
                    0, 0, 0, 0, 0, 0, 1f);

            int frameSeams = 0;
            for (int fb = 0; fb < 6; fb++) {
                int[] bVerts = ReplayIdleCubes.FACES[fb];
                for (int k = 0; k < 4; k++) {
                    int vi = bVerts[k];
                    float dx = b.lx[vi] - a.cx, dy = b.ly[vi] - a.cy, dz = b.lz[vi] - a.cz;
                    qx[k] = dx * a.uxx + dy * a.uxy + dz * a.uxz;
                    qy[k] = dx * a.uyx + dy * a.uyy + dz * a.uyz;
                    qz[k] = dx * a.uzx + dy * a.uzy + dz * a.uzz;
                }
                for (int fa = 0; fa < 6; fa++) {
                    if (!ReplayIdleCubes.intersectQuadWithCubeFace(qx, qy, qz, a.half, fa, out)) continue;
                    frameSeams++;
                    for (int ep = 0; ep < 2; ep++) {
                        float ax = out[ep * 3], ay = out[ep * 3 + 1], az = out[ep * 3 + 2];
                        assertTrue(Float.isFinite(ax) && Float.isFinite(ay) && Float.isFinite(az));
                        // Endpoint must lie on boundary of Cube A in A's local frame
                        float maxCoordA = Math.max(Math.abs(ax), Math.max(Math.abs(ay), Math.abs(az)));
                        assertEquals(a.half, maxCoordA, 1e-4f);

                        // Transform endpoint back to board space and check it also lies on boundary of Cube B
                        Vector3f worldPt = new Vector3f();
                        a.localToWorldCam(new Matrix4f(), ax, ay, az, worldPt);
                        float dbx = worldPt.x - b.cx, dby = worldPt.y - b.cy, dbz = worldPt.z - b.cz;
                        float bx = dbx * b.uxx + dby * b.uxy + dbz * b.uxz;
                        float by = dbx * b.uyx + dby * b.uyy + dbz * b.uyz;
                        float bz = dbx * b.uzx + dby * b.uzy + dbz * b.uzz;
                        float maxCoordB = Math.max(Math.abs(bx), Math.max(Math.abs(by), Math.abs(bz)));
                        assertEquals(b.half, maxCoordB, 1e-3f);
                    }
                }
            }
            assertTrue(frameSeams >= 6, "Interpenetrating cubes must produce visible surface seams at step " + step);
            totalSeams += frameSeams;
        }
        assertTrue(totalSeams > 500);
    }

    @Test
    void disjointAndTangentQuadsProduceNoFalseSeams() {
        float[] out = new float[6];
        // Disjoint quad far outside [-1, 1]^3
        assertFalse(ReplayIdleCubes.intersectQuadWithCubeFace(
                new float[]{2, 3, 3, 2}, new float[]{-0.5f, -0.5f, 0.5f, 0.5f}, new float[]{0, 0, 0, 0},
                1.0f, 1, out));
        // Quad touching plane x = +1 at a single corner (1, 0, 0)
        assertFalse(ReplayIdleCubes.intersectQuadWithCubeFace(
                new float[]{1, 2, 3, 2}, new float[]{0, -1, 0, 1}, new float[]{0, 0, 0, 0},
                1.0f, 1, out));
    }

    @Test
    void largeWorldGameTimeNeverCollapsesCubesOrOrbitalRings() {
        // Pre-built lobby saves (e.g. StarRailExpress lobby) often carry GameTime > 2.7e7 ticks (anim > 1.35e6),
        // where Minecraft's Mth.sin/cos (int)(v * 10430.378f) saturates to Integer.MAX_VALUE / MIN_VALUE and returns 0.
        Matrix4f board = new Matrix4f().translate(0f, 0f, -4f);
        double[] extremeAnims = {1_500_000.0, 50_000_000.0, 5_000_000_000.0, -2_000_000.0};

        for (double rawAnim : extremeAnims) {
            ReplayIdleCubes.buildHeroPoses(board, rawAnim, rawAnim, 1.375f, 1.0f);
            ReplayIdleCubes.buildSatellitePoses(board, rawAnim, rawAnim, 1.375f, 1.0f);

            for (int i = 0; i < ReplayIdleCubes.HERO.length; i++) {
                assertFull3DCubeVolume(ReplayIdleCubes.HERO[i], "HERO[" + i + "] at anim=" + rawAnim);
            }
            for (int s = 0; s < ReplayIdleCubes.SATS.length; s++) {
                assertFull3DCubeVolume(ReplayIdleCubes.SATS[s], "SATS[" + s + "] at anim=" + rawAnim);
            }

            Vector3f ringPt = new Vector3f();
            float maxHorizRadius = 0f;
            for (int k = 0; k < 16; k++) {
                ReplayIdleCubes.orbitPoint(1.21f, (Math.PI * 2.0) * k / 16.0,
                        Math.toRadians(23), 0.0, rawAnim * 0.19, 1.375f, ringPt);
                float dy = ringPt.y - 1.375f;
                float dist = (float) Math.sqrt(ringPt.x * ringPt.x + dy * dy + ringPt.z * ringPt.z);
                assertEquals(1.21f, dist, 1e-4f, "Orbital ring radius must stay invariant at anim=" + rawAnim);
                maxHorizRadius = Math.max(maxHorizRadius, (float) Math.hypot(ringPt.x, ringPt.z));
            }
            assertTrue(maxHorizRadius > 1.15f, "Orbital ring must not collapse onto vertical Y axis at anim=" + rawAnim);
        }
    }

    private static void assertFull3DCubeVolume(ReplayIdleCubes.CubePose pose, String label) {
        Vector3f ux = new Vector3f(pose.uxx, pose.uxy, pose.uxz);
        Vector3f uy = new Vector3f(pose.uyx, pose.uyy, pose.uyz);
        Vector3f uz = new Vector3f(pose.uzx, pose.uzy, pose.uzz);
        assertEquals(1f, ux.length(), 1e-5f, label + " ux length");
        assertEquals(1f, uy.length(), 1e-5f, label + " uy length");
        assertEquals(1f, uz.length(), 1e-5f, label + " uz length");
        assertEquals(0f, ux.dot(uy), 1e-5f, label + " ux.uy");
        assertEquals(0f, uy.dot(uz), 1e-5f, label + " uy.uz");
        assertEquals(0f, uz.dot(ux), 1e-5f, label + " uz.ux");

        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < 8; v++) {
            minX = Math.min(minX, pose.lx[v]); maxX = Math.max(maxX, pose.lx[v]);
            minY = Math.min(minY, pose.ly[v]); maxY = Math.max(maxY, pose.ly[v]);
            minZ = Math.min(minZ, pose.lz[v]); maxZ = Math.max(maxZ, pose.lz[v]);
        }
        // Any rotated cube of half-size h has axis-aligned span >= 2*h on EVERY axis!
        float minSpan = 2f * pose.half - 1e-4f;
        assertTrue(maxX - minX >= minSpan, label + " X span collapsed: " + (maxX - minX));
        assertTrue(maxY - minY >= minSpan, label + " Y span collapsed: " + (maxY - minY));
        assertTrue(maxZ - minZ >= minSpan, label + " Z span collapsed: " + (maxZ - minZ));
    }
}
