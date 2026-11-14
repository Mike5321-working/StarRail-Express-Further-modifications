package com.habitrain.core.scene.client.replay;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

import static com.habitrain.core.scene.client.replay.ReplayBoardSite.BASE_TOP;
import static com.habitrain.core.scene.client.replay.ReplayBoardSite.FRAME;
import static com.habitrain.core.scene.client.replay.ReplayBoardSite.FRAME_TOP;

/**
 * Idle &amp; loading centerpiece for {@link com.habitrain.core.scene.block.SettlementSummaryBoard}:
 * a sculptural compound of overlapping, mutually interpenetrating 3D beveled-crystal cubes
 * levitating above an Art Deco astrolabe dial on the mahogany tray.
 *
 * <p>Each hero cube face is subdivided into an exact beveled-frame + smoked-crystal window grid
 * ({@code 6x6} sub-quads per face) and depth-sorted back-to-front across all cubes so protruding
 * corners occlude cleanly while submerged sections remain visible through tinted crystal glass.
 * Live 3D surface-surface intersection seams ({@code ∂Cube_i ∩ ∂Cube_j}) trace the exact exterior
 * creases where rotating cubes slice through one another.</p>
 */
final class ReplayIdleCubes {
    private ReplayIdleCubes() {}

    /** Cyclic perimeter vertex indices (0..7, bit0=x, bit1=y, bit2=z) for the 6 outward cube faces. */
    static final int[][] FACES = {
            {0, 4, 6, 2}, // 0: -X
            {1, 3, 7, 5}, // 1: +X
            {0, 1, 5, 4}, // 2: -Y
            {2, 6, 7, 3}, // 3: +Y
            {0, 2, 3, 1}, // 4: -Z
            {4, 5, 7, 6}  // 5: +Z
    };

    /** 12 cube edges: {v0, v1, adjacentFaceA, adjacentFaceB}. */
    private static final int[][] EDGES = {
            {0, 1, 2, 4}, {2, 3, 3, 4}, {4, 5, 2, 5}, {6, 7, 3, 5},
            {0, 2, 0, 4}, {1, 3, 1, 4}, {4, 6, 0, 5}, {5, 7, 1, 5},
            {0, 4, 0, 2}, {1, 5, 1, 2}, {2, 6, 0, 3}, {3, 7, 1, 3}
    };

    /**
     * Exact face subdivision stops along [-1, +1]: outer rings [0..1] and [5..6] form the solid
     * beveled metallic/obsidian rim; inner 4x4 cells form the clear smoked-crystal window pane.
     */
    private static final float BEVEL_INNER = 0.80f;
    private static final float[] GRID_STOPS = {-1.0f, -BEVEL_INNER, -0.40f, 0.0f, 0.40f, BEVEL_INNER, 1.0f};
    private static final int GRID_N = GRID_STOPS.length - 1; // 6x6 = 36 sub-quads per hero face

    // Signature Star-Night Express triad: Burnished Gold + Astral Sapphire Cyan + Velvet Ruby-Rose
    private static final int GOLD_HALO = 0xFFE89E30;
    private static final int GOLD_CORE = 0xFFFFE6A8;
    private static final int CYAN_HALO = 0xFF28A4FF;
    private static final int CYAN_CORE = 0xFFAEE8FF;
    private static final int RUBY_HALO = 0xFFFF465E;
    private static final int RUBY_CORE = 0xFFFFB6BD;

    private static final int HERO_CUBES = 4;
    private static final int SAT_CUBES = 3;
    private static final int MAX_SUBQUADS = HERO_CUBES * 6 * GRID_N * GRID_N + SAT_CUBES * 6;
    private static final int TRAIL_STEPS = 18;
    private static final int RING_SEGMENTS = 52;

    // Render-thread scratch buffers (zero per-frame heap allocations in geometry & sorting).
    static final CubePose[] HERO = new CubePose[HERO_CUBES];
    static final CubePose[] SATS = new CubePose[SAT_CUBES];
    private static final float[] SQ_VERTS = new float[MAX_SUBQUADS * 12];
    private static final int[] SQ_COLORS = new int[MAX_SUBQUADS * 4];
    private static final long[] SORT_KEYS = new long[MAX_SUBQUADS];
    private static final float[] RIBBON_XYZ = new float[(RING_SEGMENTS + 1) * 3];
    private static final float[] RIBBON_W = new float[RING_SEGMENTS + 1];
    private static final int[] RIBBON_C = new int[RING_SEGMENTS + 1];
    private static final Vector3f TMP_A = new Vector3f();
    private static final Vector3f TMP_B = new Vector3f();
    private static final Vector3f TMP_C = new Vector3f();
    private static final Vector3f TMP_D = new Vector3f();

    static {
        for (int i = 0; i < HERO_CUBES; i++) HERO[i] = new CubePose();
        for (int i = 0; i < SAT_CUBES; i++) SATS[i] = new CubePose();
    }

    static void paint(ReplayFrame f, ReplayBoardSite s) {
        double fade = s.fade();
        if (fade <= 0.01) return;

        float innerHalf = Math.max(0.8f, s.hw() - FRAME);
        float localScale = Math.min(1.05f, innerHalf / 2.14f);
        float wScale = Math.max(0.18f, s.scale() * localScale);
        boolean detail = s.detail();
        boolean loading = f.hasRound;

        double speedMul = loading ? 1.55 : 1.0;
        double t = f.anim * speedMul;
        float bob = (sinf(f.anim * 1.15) * 0.042f + sinf(f.anim * 2.35 + 0.7) * 0.012f) * localScale;
        float centerY = BASE_TOP + 1.14f * localScale + bob;
        double loadPulse = loading ? 0.84 + 0.16 * Math.sin(wrapAngleRad(f.anim * 5.0)) : 1.0;
        double masterAlpha = fade * loadPulse;

        VertexConsumer fill = f.fill();
        VertexConsumer glow = f.glow();
        Matrix4f board = s.board();

        // 1. Velvet contrast well + Art Deco astrolabe dial on the mahogany floor
        paintFloorAstrolabe(fill, glow, board, f.anim, t, centerY, bob, localScale, wScale, masterAlpha, detail);

        // 2. Construct poses for the 4 overlapping central 3D cubes & 3 orbiting miniature 3D cubes
        buildHeroPoses(board, t, f.anim, centerY, localScale);
        buildSatellitePoses(board, f.anim, t, centerY, localScale);

        // 3. Emit & depth-sort all beveled-frame and smoked-crystal sub-quads back-to-front into FILL,
        //    plus subtle specular reflections into GLOW
        paintSortedSculptedFaces(fill, glow, masterAlpha, detail);

        // 4. Crisp depth-cued glowing outer edges, inner window bevel trims & round vertex jewels
        for (int i = 0; i < HERO_CUBES; i++) {
            paintHeroCubeEdges(f, glow, HERO[i], i, t, wScale, masterAlpha, detail);
        }

        // 5. Exact live 3D exterior intersection seams (∂Cube_i ∩ ∂Cube_j) where cubes slice through each other
        for (int i = 0; i < HERO_CUBES; i++) {
            for (int j = i + 1; j < HERO_CUBES; j++) {
                paintSurfaceIntersections(f, glow, board, HERO[i], HERO[j], wScale, masterAlpha, detail);
            }
        }

        // 6. Smooth circular radiant core heart inside the innermost chronocube
        paintCoreHeart(f, glow, HERO[3].camCenter, f.anim, wScale, masterAlpha);

        // 7. Precessing gyroscope rings, satellite mini-cube edges, comet ribbons & ascending starlight motes
        paintOrbitalField(f, glow, board, f.anim, t, centerY, localScale, wScale, masterAlpha, detail);

        // 8. Active materialization scan wave when replay summary is streaming in
        if (loading) {
            paintLoadingScanWave(glow, board, f.anim, centerY, localScale, wScale, fade);
        }

        // 9. Yaw-tracking Art Deco holographic status banner crowning the sculpture
        paintStatusBanner(f, s, fill, glow, centerY, localScale, wScale, fade, loading);
    }

    // ------------------------------------------------------------------ 1. floor astrolabe

    private static void paintFloorAstrolabe(VertexConsumer fill, VertexConsumer glow, Matrix4f board,
                                            double rawAnim, double t, float centerY, float bob,
                                            float localScale, float wScale, double a, boolean detail) {
        float yWell = BASE_TOP + 0.003f;
        float yDial = BASE_TOP + 0.006f;
        float rOuter = 1.68f * localScale;

        // Deep midnight-velvet projection well for rich contrast in both night and bright daylight
        ReplayDraw.disc(fill, board, 0, yWell, 0, rOuter, 36,
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.68 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.0));
        ReplayDraw.disc(fill, board, 0, yWell + 0.001f, 0, rOuter * 0.65f, 28,
                ReplayPalette.alpha(ReplayPalette.NIGHT_1, 0.42 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_1, 0.0));

        double prox = 1.0 - Mth.clamp(bob / (0.06f * localScale), -1f, 1f) * 0.18;
        ReplayDraw.disc(glow, board, 0, yDial, 0, 1.05f * localScale, 28,
                ReplayPalette.alpha(GOLD_HALO, 0.12 * a * prox),
                ReplayPalette.alpha(GOLD_HALO, 0.0));
        ReplayDraw.disc(glow, board, 0, yDial + 0.001f, 0, 0.48f * localScale, 22,
                ReplayPalette.alpha(CYAN_HALO, 0.11 * a * prox),
                ReplayPalette.alpha(RUBY_HALO, 0.0));

        float rRing1 = 1.50f * localScale;
        float rRing2 = 1.39f * localScale;
        float rInner = 0.54f * localScale;
        ReplayDraw.ring(glow, board, 0, yDial + 0.001f, 0, rRing1, 0.017f * localScale, 44,
                ReplayPalette.alpha(ReplayPalette.BRASS, 0.50 * a),
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.06 * a));
        ReplayDraw.ring(glow, board, 0, yDial + 0.001f, 0, rRing2, 0.008f * localScale, 40,
                ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.32 * a),
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.0));
        ReplayDraw.ring(glow, board, 0, yDial + 0.002f, 0, rInner, 0.014f * localScale, 32,
                ReplayPalette.alpha(CYAN_HALO, 0.36 * a),
                ReplayPalette.alpha(ReplayPalette.BRASS, 0.04 * a));

        // Counter-rotating octagram floor squares mirroring the 3D cubes above
        float sqHalf = 0.93f * localScale;
        double angA = wrapAngleRad(t * 0.18);
        double angB = wrapAngleRad(-t * 0.18 + Math.PI * 0.25);
        drawFloorRotatedSquare(glow, board, yDial + 0.002f, sqHalf, angA, 0.012f * wScale,
                ReplayPalette.alpha(GOLD_CORE, 0.42 * a));
        drawFloorRotatedSquare(glow, board, yDial + 0.002f, sqHalf, angB, 0.010f * wScale,
                ReplayPalette.alpha(CYAN_CORE, 0.34 * a));

        if (detail) {
            int tickCol = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.50 * a);
            int minorCol = ReplayPalette.alpha(ReplayPalette.BRASS, 0.28 * a);
            for (int i = 0; i < 16; i++) {
                double ang = (Math.PI * 2.0) * i / 16.0 + rawAnim * 0.05;
                float c = cosf(ang), sn = sinf(ang);
                boolean cardinal = (i & 3) == 0;
                float r0 = cardinal ? rRing2 - 0.055f * localScale : rRing2;
                float r1 = cardinal ? rRing1 + 0.055f * localScale : rRing1;
                int col = cardinal ? tickCol : minorCol;
                ReplayDraw.segment(glow, board, c * r0, yDial + 0.003f, sn * r0, c * r1, yDial + 0.003f, sn * r1,
                        (cardinal ? 0.013f : 0.0065f) * wScale, col, col);
            }
        }

        // Subtle upward light veil anchoring the floating cube compound to the dial
        float beamHeight = Math.max(0.35f, centerY - yDial - 0.22f * localScale);
        ReplayDraw.column(glow, board, 0, yDial, 0, beamHeight, 0.22f * localScale,
                ReplayPalette.alpha(GOLD_HALO, 0.065 * a),
                ReplayPalette.alpha(CYAN_HALO, 0.0));
        int baseBeam = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.18 * a);
        int topBeam = ReplayPalette.alpha(CYAN_HALO, 0.0);
        for (int i = 0; i < 4; i++) {
            double a0 = angA + i * (Math.PI * 0.5) + Math.PI * 0.25;
            float x0 = cosf(a0) * sqHalf * 0.82f, z0 = sinf(a0) * sqHalf * 0.82f;
            ReplayDraw.segment(glow, board, x0, yDial + 0.004f, z0, x0 * 0.34f, centerY - 0.24f * localScale, z0 * 0.34f,
                    0.010f * wScale, baseBeam, topBeam);
        }
    }

    private static void drawFloorRotatedSquare(VertexConsumer glow, Matrix4f board, float y, float half,
                                               double angle, float width, int color) {
        float c = cosf(angle) * half, s = sinf(angle) * half;
        float x0 = c - s, z0 = s + c;
        float x1 = -c - s, z1 = -s + c;
        float x2 = -c + s, z2 = -s - c;
        float x3 = c + s, z3 = s - c;
        ReplayDraw.segment(glow, board, x0, y, z0, x1, y, z1, width, color, color);
        ReplayDraw.segment(glow, board, x1, y, z1, x2, y, z2, width, color, color);
        ReplayDraw.segment(glow, board, x2, y, z2, x3, y, z3, width, color, color);
        ReplayDraw.segment(glow, board, x3, y, z3, x0, y, z0, width, color, color);
    }

    // ------------------------------------------------------------------ 2. hero & satellite poses

    static void buildHeroPoses(Matrix4f board, double t, double rawAnim, float centerY, float localScale) {
        float breath = 1.0f + 0.05f * sinf(rawAnim * 2.3);
        // Gentle 120°-phased 3D orbital weave so the 3 outer cubes visibly jut past each other while ~80% overlapped
        float weaveR = 0.115f * localScale;
        float weaveY = 0.055f * localScale;
        double wPhase = t * 0.36;

        // Cube 0: Sovereign Burnished-Brass Cube
        HERO[0].set(board,
                weaveR * 0.75f * cosf(wPhase),
                centerY + weaveY * sinf(wPhase * 1.3),
                weaveR * 0.75f * sinf(wPhase),
                0.55f * localScale,
                0.615 + 0.18 * Math.sin(wrapAngleRad(t * 0.35)),
                t * 0.33,
                0.785 + 0.15 * Math.cos(wrapAngleRad(t * 0.28)),
                0xFF120E18, 0xFF5A401D, 0xFF0A0F1F, 0xFF2E2418,
                GOLD_HALO, GOLD_CORE, 1.0f);

        // Cube 1: Counter-Rotating Astral Sapphire Cube
        HERO[1].set(board,
                weaveR * cosf(wPhase + 2.094395),
                centerY - weaveY * cosf(wPhase * 1.1),
                weaveR * sinf(wPhase + 2.094395),
                0.495f * localScale,
                -0.68 + t * 0.26,
                -t * 0.45 + 1.10,
                0.54 - 0.20 * Math.sin(wrapAngleRad(t * 0.44)),
                0xFF071528, 0xFF16528E, 0xFF071930, 0xFF153E6E,
                CYAN_HALO, CYAN_CORE, 0.98f);

        // Cube 2: Orthogonal Velvet-Ruby & Rose-Amber Cube
        HERO[2].set(board,
                weaveR * cosf(wPhase + 4.188790),
                centerY + weaveY * cosf(wPhase * 1.4 + 0.8),
                weaveR * sinf(wPhase + 4.188790),
                0.44f * localScale,
                t * 0.52 + 2.10,
                t * 0.21 - 0.65,
                -t * 0.39 + 0.95,
                0xFF200A14, 0xFF7A2236, 0xFF1A0B18, 0xFF4C1928,
                RUBY_HALO, RUBY_CORE, 0.95f);

        // Cube 3: Inner Sanctum Chronocube suspended at the shared triple-cube heart
        HERO[3].set(board,
                0f, centerY, 0f,
                0.22f * localScale * breath,
                -t * 0.78 + 0.4,
                t * 1.05,
                t * 0.52 - 0.3,
                0xFF1C2438, 0xFF78653E, 0xFF182644, 0xFF524A36,
                ReplayPalette.BRASS_LIGHT, ReplayPalette.CHAMPAGNE, 1.08f);
    }

    static void buildSatellitePoses(Matrix4f board, double rawAnim, double t, float centerY, float localScale) {
        int[] frameShad = {0xFF161118, 0xFF08182C, 0xFF220B15};
        int[] frameLit = {0xFF684B22, 0xFF1B5DA0, 0xFF88283E};
        int[] halos = {GOLD_HALO, CYAN_HALO, RUBY_HALO};
        int[] cores = {GOLD_CORE, CYAN_CORE, RUBY_CORE};
        float orbitR = 1.22f * localScale;

        for (int s = 0; s < SAT_CUBES; s++) {
            double tiltX = Math.toRadians(23 * (s == 1 ? -1 : 1));
            double tiltZ = Math.toRadians(s == 2 ? 27 : -15);
            double baseYaw = s * (Math.PI * 2.0 / 3.0) + rawAnim * 0.11;
            double headAngle = t * (0.70 + s * 0.08) + s * 2.094395;
            orbitPoint(orbitR, headAngle, tiltX, tiltZ, baseYaw, centerY, TMP_C);

            SATS[s].set(board, TMP_C.x, TMP_C.y, TMP_C.z, 0.074f * localScale,
                    t * 1.35 + s, -t * 1.65 + s * 2, t * 0.92,
                    frameShad[s], frameLit[s], frameShad[s], frameLit[s],
                    halos[s], cores[s], 0.96f);
        }
    }

    // ------------------------------------------------------------------ 3. depth-sorted sculpted beveled crystal faces

    private static void paintSortedSculptedFaces(VertexConsumer fill, VertexConsumer glow,
                                                 double masterAlpha, boolean detail) {
        int quadCount = 0;
        int steps = detail ? GRID_N : 1; // Far LOD: single quad per face

        for (int ci = 0; ci < HERO_CUBES; ci++) {
            CubePose cube = HERO[ci];
            boolean coreCube = (ci == 3);
            for (int fi = 0; fi < 6; fi++) {
                float ndotv = cube.faceNdotV[fi];
                float lnx = cube.localFaceNx[fi], lny = cube.localFaceNy[fi], lnz = cube.localFaceNz[fi];
                // 3D directional lighting: key light from upper-front-right + warm floor bounce
                float keyDot = Math.max(0f, lnx * 0.38f + lny * 0.82f + lnz * 0.42f);
                float rimBack = Math.max(0f, -lnx * 0.45f + lny * 0.30f - lnz * 0.65f) * 0.28f;
                float bounce = Math.max(0f, -lny) * 0.32f;
                double shade = Mth.clamp(0.16 + 0.72 * keyDot + rimBack + bounce, 0.0, 1.0);

                int frameCol = ReplayPalette.lerp(cube.frameShadowColor, cube.frameLitColor, shade);
                int glassCol = ReplayPalette.lerp(cube.glassShadowColor, cube.glassLitColor, shade);
                // Subtle colored edge gleam on front-angled bevels
                if (ndotv > 0f) {
                    double fresnel = Math.pow(1.0 - ndotv, 2.0);
                    frameCol = ReplayPalette.lerp(frameCol, cube.edgeHaloColor, 0.26 * fresnel + 0.14 * keyDot);
                    glassCol = ReplayPalette.lerp(glassCol, cube.edgeHaloColor, 0.22 * fresnel);
                }

                // Continuous opacity across ndotv = 0 (projected area is 0 at ndotv = 0, so zero flip pop)
                double frameAlpha, glassAlpha;
                if (ndotv <= 0f) {
                    frameAlpha = Mth.lerp(-ndotv, 0.52, 0.26);
                    glassAlpha = Mth.lerp(-ndotv, 0.22, 0.09);
                } else {
                    double fresnel = Math.pow(1.0 - ndotv, 1.8);
                    frameAlpha = 0.62 + 0.16 * fresnel;
                    glassAlpha = (coreCube ? 0.34 : 0.17) + 0.14 * fresnel;
                }
                frameAlpha *= masterAlpha;
                glassAlpha *= masterAlpha;

                int[] fv = FACES[fi];
                float x00 = cube.vx[fv[0]], y00 = cube.vy[fv[0]], z00 = cube.vz[fv[0]];
                float x10 = cube.vx[fv[1]], y10 = cube.vy[fv[1]], z10 = cube.vz[fv[1]];
                float x11 = cube.vx[fv[2]], y11 = cube.vy[fv[2]], z11 = cube.vz[fv[2]];
                float x01 = cube.vx[fv[3]], y01 = cube.vy[fv[3]], z01 = cube.vz[fv[3]];

                if (steps == 1) {
                    int c = ReplayPalette.alpha(frameCol, glassAlpha * 1.4);
                    quadCount = pushSubQuad(quadCount, x00, y00, z00, x10, y10, z10, x11, y11, z11, x01, y01, z01, c, c, c, c);
                } else {
                    int cFrame = ReplayPalette.alpha(frameCol, frameAlpha);
                    for (int iu = 0; iu < GRID_N; iu++) {
                        float u0 = (GRID_STOPS[iu] + 1f) * 0.5f, u1 = (GRID_STOPS[iu + 1] + 1f) * 0.5f;
                        boolean rimU = (iu == 0 || iu == GRID_N - 1);
                        for (int iv = 0; iv < GRID_N; iv++) {
                            float v0 = (GRID_STOPS[iv] + 1f) * 0.5f, v1 = (GRID_STOPS[iv + 1] + 1f) * 0.5f;
                            boolean isFrame = rimU || iv == 0 || iv == GRID_N - 1;
                            int col;
                            if (isFrame) {
                                col = cFrame;
                            } else {
                                // Slight radial vignette from clear window center toward beveled rim
                                float mu = 0.5f * (GRID_STOPS[iu] + GRID_STOPS[iu + 1]);
                                float mv = 0.5f * (GRID_STOPS[iv] + GRID_STOPS[iv + 1]);
                                float edgeProx = Math.max(Math.abs(mu), Math.abs(mv)) / BEVEL_INNER;
                                col = ReplayPalette.alpha(glassCol, glassAlpha * (0.78 + 0.34 * edgeProx * edgeProx));
                            }
                            quadCount = pushBilinearPatch(quadCount,
                                    x00, y00, z00, x10, y10, z10, x11, y11, z11, x01, y01, z01,
                                    u0, v0, u1, v1, col);
                        }
                    }
                }

                // Polished crystal specular flash when face catches the chandelier reflection angle
                if (ndotv > 0.10f) {
                    float specDot = lnx * 0.24f + lny * 0.77f + lnz * 0.59f;
                    if (specDot > 0.62f) {
                        double spec = Math.pow((specDot - 0.62) / 0.38, 3.0) * ndotv * 0.14 * masterAlpha;
                        if (spec > 0.008) {
                            int hi = ReplayPalette.alpha(cube.edgeCoreColor, spec);
                            int lo = ReplayPalette.alpha(cube.edgeHaloColor, spec * 0.2);
                            glow.addVertex(x00, y00, z00).setColor(lo);
                            glow.addVertex(x10, y10, z10).setColor(hi);
                            glow.addVertex(x11, y11, z11).setColor(hi);
                            glow.addVertex(x01, y01, z01).setColor(lo);
                        }
                    }
                }
            }
        }

        // Also include the 3 orbiting 3D jewel mini-cubes in the same sorted translucent pass!
        for (int s = 0; s < SAT_CUBES; s++) {
            CubePose sat = SATS[s];
            for (int fi = 0; fi < 6; fi++) {
                float ndotv = sat.faceNdotV[fi];
                float keyDot = Math.max(0f, sat.localFaceNx[fi] * 0.38f + sat.localFaceNy[fi] * 0.82f + sat.localFaceNz[fi] * 0.42f);
                int fCol = ReplayPalette.lerp(sat.frameShadowColor, sat.frameLitColor, 0.25 + 0.75 * keyDot);
                double fa = (ndotv > 0 ? 0.56 : 0.28) * masterAlpha;
                int c = ReplayPalette.alpha(fCol, fa);
                int[] fv = FACES[fi];
                quadCount = pushSubQuad(quadCount,
                        sat.vx[fv[0]], sat.vy[fv[0]], sat.vz[fv[0]],
                        sat.vx[fv[1]], sat.vy[fv[1]], sat.vz[fv[1]],
                        sat.vx[fv[2]], sat.vy[fv[2]], sat.vz[fv[2]],
                        sat.vx[fv[3]], sat.vy[fv[3]], sat.vz[fv[3]], c, c, c, c);
            }
        }

        Arrays.sort(SORT_KEYS, 0, quadCount);
        for (int k = quadCount - 1; k >= 0; k--) {
            int idx = (int) SORT_KEYS[k];
            int vo = idx * 12, co = idx * 4;
            fill.addVertex(SQ_VERTS[vo], SQ_VERTS[vo + 1], SQ_VERTS[vo + 2]).setColor(SQ_COLORS[co]);
            fill.addVertex(SQ_VERTS[vo + 3], SQ_VERTS[vo + 4], SQ_VERTS[vo + 5]).setColor(SQ_COLORS[co + 1]);
            fill.addVertex(SQ_VERTS[vo + 6], SQ_VERTS[vo + 7], SQ_VERTS[vo + 8]).setColor(SQ_COLORS[co + 2]);
            fill.addVertex(SQ_VERTS[vo + 9], SQ_VERTS[vo + 10], SQ_VERTS[vo + 11]).setColor(SQ_COLORS[co + 3]);
        }
    }

    private static int pushBilinearPatch(int idx,
                                         float x00, float y00, float z00,
                                         float x10, float y10, float z10,
                                         float x11, float y11, float z11,
                                         float x01, float y01, float z01,
                                         float u0, float v0, float u1, float v1, int color) {
        float ax = bilerp(x00, x10, x11, x01, u0, v0), ay = bilerp(y00, y10, y11, y01, u0, v0), az = bilerp(z00, z10, z11, z01, u0, v0);
        float bx = bilerp(x00, x10, x11, x01, u1, v0), by = bilerp(y00, y10, y11, y01, u1, v0), bz = bilerp(z00, z10, z11, z01, u1, v0);
        float cx = bilerp(x00, x10, x11, x01, u1, v1), cy = bilerp(y00, y10, y11, y01, u1, v1), cz = bilerp(z00, z10, z11, z01, u1, v1);
        float dx = bilerp(x00, x10, x11, x01, u0, v1), dy = bilerp(y00, y10, y11, y01, u0, v1), dz = bilerp(z00, z10, z11, z01, u0, v1);
        return pushSubQuad(idx, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, color, color, color, color);
    }

    private static float bilerp(float p00, float p10, float p11, float p01, float u, float v) {
        float bot = p00 + (p10 - p00) * u;
        float top = p01 + (p11 - p01) * u;
        return bot + (top - bot) * v;
    }

    private static int pushSubQuad(int idx,
                                   float x0, float y0, float z0, float x1, float y1, float z1,
                                   float x2, float y2, float z2, float x3, float y3, float z3,
                                   int c0, int c1, int c2, int c3) {
        if (idx >= MAX_SUBQUADS) return idx;
        int vo = idx * 12, co = idx * 4;
        SQ_VERTS[vo] = x0; SQ_VERTS[vo + 1] = y0; SQ_VERTS[vo + 2] = z0;
        SQ_VERTS[vo + 3] = x1; SQ_VERTS[vo + 4] = y1; SQ_VERTS[vo + 5] = z1;
        SQ_VERTS[vo + 6] = x2; SQ_VERTS[vo + 7] = y2; SQ_VERTS[vo + 8] = z2;
        SQ_VERTS[vo + 9] = x3; SQ_VERTS[vo + 10] = y3; SQ_VERTS[vo + 11] = z3;
        SQ_COLORS[co] = c0; SQ_COLORS[co + 1] = c1; SQ_COLORS[co + 2] = c2; SQ_COLORS[co + 3] = c3;

        float mx = 0.25f * (x0 + x1 + x2 + x3);
        float my = 0.25f * (y0 + y1 + y2 + y3);
        float mz = 0.25f * (z0 + z1 + z2 + z3);
        float distSq = mx * mx + my * my + mz * mz;
        SORT_KEYS[idx] = (((long) Float.floatToRawIntBits(distSq)) << 32) | (idx & 0xFFFFFFFFL);
        return idx + 1;
    }

    // ------------------------------------------------------------------ 4. edges, inner bevel rims & corner jewels

    private static void paintHeroCubeEdges(ReplayFrame f, VertexConsumer glow, CubePose cube, int cubeIndex,
                                           double t, float wScale, double masterAlpha, boolean detail) {
        float haloWidth = (cubeIndex == 0 ? 0.032f : cubeIndex <= 2 ? 0.027f : 0.022f) * wScale;
        float coreWidth = (cubeIndex == 0 ? 0.011f : cubeIndex <= 2 ? 0.0095f : 0.0080f) * wScale;

        for (int ei = 0; ei < EDGES.length; ei++) {
            int[] e = EDGES[ei];
            int i0 = e[0], i1 = e[1], fA = e[2], fB = e[3];
            float sA = smoothstep(-0.14f, 0.14f, cube.faceNdotV[fA]);
            float sB = smoothstep(-0.14f, 0.14f, cube.faceNdotV[fB]);
            float frontVis = Math.max(sA, sB);
            float silhouette = 4f * sA * (1f - sB) + 4f * sB * (1f - sA);
            // Back edges stay gently muted (0.18) so front faces & silhouette read as unmistakable 3D volume
            double vis = (0.18 + 0.72 * frontVis + 0.16 * Math.min(1f, silhouette)) * cube.brightness * masterAlpha;

            TMP_A.set(cube.vx[i0], cube.vy[i0], cube.vz[i0]);
            TMP_B.set(cube.vx[i1], cube.vy[i1], cube.vz[i1]);
            int cHalo = ReplayPalette.alpha(cube.edgeHaloColor, Math.min(1.0, 0.42 * vis));
            int cCore = ReplayPalette.alpha(cube.edgeCoreColor, Math.min(1.0, 0.86 * vis));
            ReplayDraw.segment(glow, TMP_A, TMP_B, haloWidth, cHalo, cHalo);
            ReplayDraw.segment(glow, TMP_A, TMP_B, coreWidth, cCore, cCore);

            // Subtle traveling energy spark along front-facing outer edges
            if (detail && cubeIndex <= 2 && frontVis > 0.35f) {
                double phase = fract(t * (0.48 + cubeIndex * 0.14) + ei * 0.31 + cubeIndex * 0.47);
                TMP_C.set(TMP_A).lerp(TMP_B, (float) Math.max(0.0, phase - 0.11));
                TMP_D.set(TMP_A).lerp(TMP_B, (float) Math.min(1.0, phase + 0.11));
                double env = Math.sin(phase * Math.PI) * frontVis * masterAlpha;
                ReplayDraw.segment(glow, TMP_C, TMP_D, coreWidth * 1.35f,
                        ReplayPalette.alpha(cube.edgeHaloColor, 0.0),
                        ReplayPalette.alpha(0xFFFFFFFF, Math.min(1.0, 0.62 * env)));
            }
        }

        // Delicate inner bevel-window highlight along the 0.80 rim on front-facing faces only
        if (detail && cubeIndex <= 2) {
            float rimW = 0.0052f * wScale;
            for (int fi = 0; fi < 6; fi++) {
                float fVis = smoothstep(0.02f, 0.32f, cube.faceNdotV[fi]);
                if (fVis <= 0.02f) continue;
                int[] fv = FACES[fi];
                float cx = 0.25f * (cube.vx[fv[0]] + cube.vx[fv[1]] + cube.vx[fv[2]] + cube.vx[fv[3]]);
                float cy = 0.25f * (cube.vy[fv[0]] + cube.vy[fv[1]] + cube.vy[fv[2]] + cube.vy[fv[3]]);
                float cz = 0.25f * (cube.vz[fv[0]] + cube.vz[fv[1]] + cube.vz[fv[2]] + cube.vz[fv[3]]);
                int rimCol = ReplayPalette.alpha(cube.edgeHaloColor, 0.32 * fVis * masterAlpha);
                for (int k = 0; k < 4; k++) {
                    int v0 = fv[k], v1 = fv[(k + 1) & 3];
                    TMP_A.set(Mth.lerp(BEVEL_INNER, cx, cube.vx[v0]), Mth.lerp(BEVEL_INNER, cy, cube.vy[v0]), Mth.lerp(BEVEL_INNER, cz, cube.vz[v0]));
                    TMP_B.set(Mth.lerp(BEVEL_INNER, cx, cube.vx[v1]), Mth.lerp(BEVEL_INNER, cy, cube.vy[v1]), Mth.lerp(BEVEL_INNER, cz, cube.vz[v1]));
                    ReplayDraw.segment(glow, TMP_A, TMP_B, rimW, rimCol, rimCol);
                }
            }
        }

        // Round radial corner jewels (no square billboard corners)
        float jewelR = (cubeIndex == 0 ? 0.030f : cubeIndex <= 2 ? 0.025f : 0.020f) * wScale;
        for (int vi = 0; vi < 8; vi++) {
            float vVis = vertexFrontFactor(cube, vi);
            if (vVis <= 0.08f) continue;
            double ja = vVis * masterAlpha * cube.brightness;
            TMP_A.set(cube.vx[vi], cube.vy[vi], cube.vz[vi]);
            cameraDisc(glow, TMP_A, f.right, f.up, jewelR, 8,
                    ReplayPalette.alpha(cube.edgeCoreColor, Math.min(1.0, 0.72 * ja)),
                    ReplayPalette.alpha(cube.edgeHaloColor, 0.0));
        }
    }

    private static float vertexFrontFactor(CubePose cube, int vi) {
        float fx = (vi & 1) != 0 ? cube.faceNdotV[1] : cube.faceNdotV[0];
        float fy = (vi & 2) != 0 ? cube.faceNdotV[3] : cube.faceNdotV[2];
        float fz = (vi & 4) != 0 ? cube.faceNdotV[5] : cube.faceNdotV[4];
        return smoothstep(-0.08f, 0.22f, Math.max(fx, Math.max(fy, fz)));
    }

    // ------------------------------------------------------------------ 5. exact 3D exterior intersection seams

    private static final float[] QX = new float[4], QY = new float[4], QZ = new float[4], QD = new float[4];
    private static final float[] HIT_X = new float[4], HIT_Y = new float[4], HIT_Z = new float[4];
    private static final float[] CLIP_OUT = new float[6];

    private static void paintSurfaceIntersections(ReplayFrame f, VertexConsumer glow, Matrix4f board,
                                                  CubePose a, CubePose b, float wScale,
                                                  double masterAlpha, boolean detail) {
        int blendHalo = ReplayPalette.lerp(a.edgeHaloColor, b.edgeHaloColor, 0.5);
        int blendCore = ReplayPalette.lerp(blendHalo, 0xFFFFFFFF, 0.62);
        float hA = a.half;

        for (int fb = 0; fb < 6; fb++) {
            float bVis = smoothstep(-0.06f, 0.20f, b.faceNdotV[fb]);
            if (bVis <= 0.02f) continue;

            int[] bVerts = FACES[fb];
            for (int k = 0; k < 4; k++) {
                int vi = bVerts[k];
                float dx = b.lx[vi] - a.cx, dy = b.ly[vi] - a.cy, dz = b.lz[vi] - a.cz;
                QX[k] = dx * a.uxx + dy * a.uxy + dz * a.uxz;
                QY[k] = dx * a.uyx + dy * a.uyy + dz * a.uyz;
                QZ[k] = dx * a.uzx + dy * a.uzy + dz * a.uzz;
            }

            for (int fa = 0; fa < 6; fa++) {
                float aVis = smoothstep(-0.06f, 0.20f, a.faceNdotV[fa]);
                if (aVis <= 0.02f) continue;
                if (!intersectQuadWithCubeFace(QX, QY, QZ, hA, fa, CLIP_OUT)) continue;

                // Exterior CSG crease visibility: shines sharply where two front-visible faces slice together
                double seamVis = aVis * bVis * masterAlpha;
                if (seamVis <= 0.02) continue;

                a.localToWorldCam(board, CLIP_OUT[0], CLIP_OUT[1], CLIP_OUT[2], TMP_A);
                a.localToWorldCam(board, CLIP_OUT[3], CLIP_OUT[4], CLIP_OUT[5], TMP_B);

                int cHalo = ReplayPalette.alpha(blendHalo, Math.min(1.0, 0.45 * seamVis));
                int cCore = ReplayPalette.alpha(blendCore, Math.min(1.0, 0.90 * seamVis));
                ReplayDraw.segment(glow, TMP_A, TMP_B, 0.024f * wScale, cHalo, cHalo);
                ReplayDraw.segment(glow, TMP_A, TMP_B, 0.0075f * wScale, cCore, cCore);

                if (detail && seamVis > 0.35) {
                    float sparkR = 0.022f * wScale;
                    int sCenter = ReplayPalette.alpha(0xFFFFFFFF, Math.min(1.0, 0.70 * seamVis));
                    int sEdge = ReplayPalette.alpha(blendHalo, 0.0);
                    cameraDisc(glow, TMP_A, f.right, f.up, sparkR, 8, sCenter, sEdge);
                    cameraDisc(glow, TMP_B, f.right, f.up, sparkR, 8, sCenter, sEdge);
                }
            }
        }
    }

    /**
     * Pure geometric solver: clips a convex 3D quad {@code (qx, qy, qz)[0..3]} (expressed in Cube A's
     * local orthonormal coordinates) against face {@code faceIdx} ({@code 0..5}) of {@code [-h, +h]^3}.
     * Writes {@code (x0, y0, z0, x1, y1, z1)} into {@code out} and returns {@code true} if non-degenerate.
     */
    static boolean intersectQuadWithCubeFace(float[] qx, float[] qy, float[] qz, float h, int faceIdx, float[] out) {
        int axis = faceIdx >> 1;
        float plane = (faceIdx & 1) == 0 ? -h : h;
        for (int k = 0; k < 4; k++) {
            float coord = axis == 0 ? qx[k] : axis == 1 ? qy[k] : qz[k];
            QD[k] = coord - plane;
        }
        int hits = 0;
        for (int k = 0; k < 4; k++) {
            int next = (k + 1) & 3;
            float d0 = QD[k], d1 = QD[next];
            if ((d0 < 0f && d1 >= 0f) || (d0 >= 0f && d1 < 0f)) {
                float denom = d0 - d1;
                if (Math.abs(denom) < 1.0e-6f) continue;
                float u = d0 / denom;
                if (hits < 4) {
                    HIT_X[hits] = Mth.lerp(u, qx[k], qx[next]);
                    HIT_Y[hits] = Mth.lerp(u, qy[k], qy[next]);
                    HIT_Z[hits] = Mth.lerp(u, qz[k], qz[next]);
                    hits++;
                }
            }
        }
        if (hits < 2) return false;
        float x0 = HIT_X[0], y0 = HIT_Y[0], z0 = HIT_Z[0];
        float dx = HIT_X[1] - x0, dy = HIT_Y[1] - y0, dz = HIT_Z[1] - z0;

        float t0 = 0f, t1 = 1f;
        for (int a = 0; a < 3; a++) {
            if (a == axis) continue;
            float p0 = a == 0 ? x0 : a == 1 ? y0 : z0;
            float dp = a == 0 ? dx : a == 1 ? dy : dz;
            if (Math.abs(dp) < 1.0e-7f) {
                if (p0 < -h || p0 > h) return false;
            } else {
                float inv = 1f / dp;
                float ta = (-h - p0) * inv, tb = (h - p0) * inv;
                if (ta > tb) { float tmp = ta; ta = tb; tb = tmp; }
                if (ta > t0) t0 = ta;
                if (tb < t1) t1 = tb;
                if (t0 >= t1) return false;
            }
        }
        float ax = x0 + dx * t0, ay = y0 + dy * t0, az = z0 + dz * t0;
        float bx = x0 + dx * t1, by = y0 + dy * t1, bz = z0 + dz * t1;
        if (axis == 0) ax = bx = plane;
        else if (axis == 1) ay = by = plane;
        else az = bz = plane;

        float lenSq = (bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az);
        if (lenSq < 1.0e-6f) return false;
        out[0] = ax; out[1] = ay; out[2] = az;
        out[3] = bx; out[4] = by; out[5] = bz;
        return true;
    }

    // ------------------------------------------------------------------ 6. circular radiant core heart

    private static void paintCoreHeart(ReplayFrame f, VertexConsumer glow, Vector3f center,
                                       double rawAnim, float wScale, double a) {
        float pulse = 1.0f + 0.10f * sinf(rawAnim * 3.6);
        cameraDisc(glow, center, f.right, f.up, 0.24f * wScale * pulse, 20,
                ReplayPalette.alpha(GOLD_HALO, 0.22 * a),
                ReplayPalette.alpha(GOLD_HALO, 0.0));
        cameraDisc(glow, center, f.right, f.up, 0.13f * wScale * pulse, 16,
                ReplayPalette.alpha(CYAN_CORE, 0.34 * a),
                ReplayPalette.alpha(CYAN_HALO, 0.0));
        cameraDisc(glow, center, f.right, f.up, 0.055f * wScale, 12,
                ReplayPalette.alpha(0xFFFFFFFF, 0.90 * a),
                ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.0));
        ReplayDraw.star(glow, center, f.right, f.up, 0.15f * wScale * pulse, 4, (float) wrapAngleRad(rawAnim * 0.42),
                ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.60 * a),
                ReplayPalette.alpha(GOLD_HALO, 0.0));
    }

    private static void cameraDisc(VertexConsumer vc, Vector3f c, Vector3f right, Vector3f up,
                                   float radius, int segments, int centerColor, int edgeColor) {
        for (int i = 0; i < segments; i++) {
            double a0 = (Math.PI * 2.0) * i / segments, a1 = (Math.PI * 2.0) * (i + 1) / segments;
            float c0 = (float) Math.cos(a0) * radius, s0 = (float) Math.sin(a0) * radius;
            float c1 = (float) Math.cos(a1) * radius, s1 = (float) Math.sin(a1) * radius;
            vc.addVertex(c.x, c.y, c.z).setColor(centerColor);
            vc.addVertex(c.x, c.y, c.z).setColor(centerColor);
            vc.addVertex(c.x + right.x * c1 + up.x * s1, c.y + right.y * c1 + up.y * s1, c.z + right.z * c1 + up.z * s1).setColor(edgeColor);
            vc.addVertex(c.x + right.x * c0 + up.x * s0, c.y + right.y * c0 + up.y * s0, c.z + right.z * c0 + up.z * s0).setColor(edgeColor);
        }
    }

    // ------------------------------------------------------------------ 7. orbital rings, satellite edges & motes

    private static void paintOrbitalField(ReplayFrame f, VertexConsumer glow, Matrix4f board,
                                          double rawAnim, double t, float centerY,
                                          float localScale, float wScale, double a, boolean detail) {
        drawTiltedRing(glow, board, centerY, 1.21f * localScale,
                Math.toRadians(23), rawAnim * 0.19, 0.0080f * wScale,
                ReplayPalette.alpha(GOLD_CORE, 0.25 * a));
        drawTiltedRing(glow, board, centerY, 1.29f * localScale,
                Math.toRadians(-26), -rawAnim * 0.15 + 1.2, 0.0070f * wScale,
                ReplayPalette.alpha(CYAN_CORE, 0.22 * a));

        float orbitR = 1.22f * localScale;
        for (int s = 0; s < SAT_CUBES; s++) {
            CubePose sat = SATS[s];
            for (int[] e : EDGES) {
                float sA = smoothstep(-0.14f, 0.14f, sat.faceNdotV[e[2]]);
                float sB = smoothstep(-0.14f, 0.14f, sat.faceNdotV[e[3]]);
                float frontVis = Math.max(sA, sB);
                float silhouette = 4f * sA * (1f - sB) + 4f * sB * (1f - sA);
                double vis = (0.20 + 0.72 * frontVis + 0.16 * Math.min(1f, silhouette)) * sat.brightness * a;
                int mHalo = ReplayPalette.alpha(sat.edgeHaloColor, Math.min(1.0, 0.48 * vis));
                int mCore = ReplayPalette.alpha(sat.edgeCoreColor, Math.min(1.0, 0.94 * vis));
                TMP_A.set(sat.vx[e[0]], sat.vy[e[0]], sat.vz[e[0]]);
                TMP_B.set(sat.vx[e[1]], sat.vy[e[1]], sat.vz[e[1]]);
                ReplayDraw.segment(glow, TMP_A, TMP_B, 0.017f * wScale, mHalo, mHalo);
                ReplayDraw.segment(glow, TMP_A, TMP_B, 0.0062f * wScale, mCore, mCore);
            }

            if (detail) {
                double tiltX = Math.toRadians(23 * (s == 1 ? -1 : 1));
                double tiltZ = Math.toRadians(s == 2 ? 27 : -15);
                double baseYaw = s * (Math.PI * 2.0 / 3.0) + rawAnim * 0.11;
                double headAngle = t * (0.70 + s * 0.08) + s * 2.094395;
                for (int step = 0; step < TRAIL_STEPS; step++) {
                    float frac = step / (float) (TRAIL_STEPS - 1);
                    orbitPoint(orbitR, headAngle - frac * 0.92, tiltX, tiltZ, baseYaw, centerY, TMP_C);
                    board.transformPosition(TMP_C.x, TMP_C.y, TMP_C.z, TMP_D);
                    int o = step * 3;
                    RIBBON_XYZ[o] = TMP_D.x;
                    RIBBON_XYZ[o + 1] = TMP_D.y;
                    RIBBON_XYZ[o + 2] = TMP_D.z;
                    RIBBON_W[step] = (1f - frac * 0.90f) * 0.020f * wScale;
                    double ta = Math.pow(1.0 - frac, 1.75) * 0.52 * a;
                    RIBBON_C[step] = ReplayPalette.alpha(step < 3 ? sat.edgeCoreColor : sat.edgeHaloColor, ta);
                }
                ReplayDraw.ribbon(glow, RIBBON_XYZ, TRAIL_STEPS, RIBBON_W, RIBBON_C);
            }
        }

        if (detail) {
            int[] motePalette = {GOLD_CORE, CYAN_CORE, RUBY_CORE};
            for (int m = 0; m < 12; m++) {
                double cycle = fract(rawAnim * (0.15 + (m % 3) * 0.04) + m * 0.137);
                float y = BASE_TOP + 0.08f + (float) cycle * 2.10f * localScale;
                float rad = (0.48f + 0.88f * ((m * 37) % 10) / 10f) * localScale;
                double ang = rawAnim * (m % 2 == 0 ? 0.34 : -0.27) + m * 1.91;
                board.transformPosition(cosf(ang) * rad, y, sinf(ang) * rad, TMP_A);
                double moteAlpha = Math.sin(cycle * Math.PI) * 0.65 * a;
                int mCol = motePalette[m % 3];
                cameraDisc(glow, TMP_A, f.right, f.up, 0.022f * wScale, 8,
                        ReplayPalette.alpha(mCol, moteAlpha), ReplayPalette.alpha(mCol, 0.0));
            }
        }
    }

    static void orbitPoint(float radius, double angle, double tiltX, double tiltZ, double yaw,
                           float centerY, Vector3f out) {
        float x = cosf(angle) * radius;
        float z = sinf(angle) * radius;
        float cx = cosf(tiltX), sx = sinf(tiltX);
        float y1 = -z * sx, z1 = z * cx;
        float cz = cosf(tiltZ), sz = sinf(tiltZ);
        float x2 = x * cz - y1 * sz, y2 = x * sz + y1 * cz;
        float cy = cosf(yaw), sy = sinf(yaw);
        out.set(x2 * cy + z1 * sy, centerY + y2, -x2 * sy + z1 * cy);
    }

    private static void drawTiltedRing(VertexConsumer glow, Matrix4f board, float centerY, float radius,
                                       double tiltX, double yaw, float width, int color) {
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double ang = (Math.PI * 2.0) * (i % RING_SEGMENTS) / RING_SEGMENTS;
            orbitPoint(radius, ang, tiltX, 0.0, yaw, centerY, TMP_C);
            board.transformPosition(TMP_C.x, TMP_C.y, TMP_C.z, TMP_D);
            int o = i * 3;
            RIBBON_XYZ[o] = TMP_D.x;
            RIBBON_XYZ[o + 1] = TMP_D.y;
            RIBBON_XYZ[o + 2] = TMP_D.z;
            RIBBON_W[i] = width;
            RIBBON_C[i] = color;
        }
        ReplayDraw.ribbon(glow, RIBBON_XYZ, RING_SEGMENTS + 1, RIBBON_W, RIBBON_C);
    }

    // ------------------------------------------------------------------ 8. loading scan wave

    private static void paintLoadingScanWave(VertexConsumer glow, Matrix4f board, double rawAnim,
                                             float centerY, float localScale, float wScale, double fade) {
        double sweep = fract(rawAnim * 0.55);
        float span = 1.90f * localScale;
        float y = centerY - span * 0.5f + (float) sweep * span;
        double env = Math.sin(sweep * Math.PI) * fade;
        float hw = 0.92f * localScale;
        ReplayDraw.rectXZ(glow, board, -hw, -hw, hw, hw, y, ReplayPalette.alpha(CYAN_HALO, 0.16 * env));
        drawFloorRotatedSquare(glow, board, y, hw, rawAnim * 0.45, 0.015f * wScale,
                ReplayPalette.alpha(CYAN_CORE, 0.78 * env));
    }

    // ------------------------------------------------------------------ 9. yaw-tracking Art Deco status banner

    private static void paintStatusBanner(ReplayFrame f, ReplayBoardSite s, VertexConsumer fill, VertexConsumer glow,
                                          float centerY, float localScale, float wScale, double fade, boolean loading) {
        float bannerY = Math.max(FRAME_TOP + 1.62f, centerY + 1.30f * localScale);
        Component text = loading ? ReplayBoardTexts.tr("loading") : ReplayBoardTexts.tr("idle");
        float textScale = 0.0082f * localScale * s.scale();
        float halfW = (Minecraft.getInstance().font.width(text) * 0.0082f * localScale + 0.38f * localScale) * 0.5f * s.scale();
        float halfH = 0.098f * localScale * s.scale();

        // Anchor in camera-relative world space and yaw-face the viewer horizontally so text is legible 360°
        s.board().transformPosition(0f, bannerY, 0f, TMP_A);
        float yawToCam = (float) Math.atan2(-TMP_A.x, -TMP_A.z);
        Matrix4f sign = new Matrix4f().translate(TMP_A.x, TMP_A.y, TMP_A.z).rotateY(yawToCam);

        int bgTop = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.90 * fade);
        int bgBot = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.92 * fade);
        ReplayDraw.rectXY(fill, sign, -halfW, -halfH, halfW, halfH, 0f, bgTop, bgBot);

        int brass = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.85 * fade);
        int brassDim = ReplayPalette.alpha(ReplayPalette.BRASS, 0.52 * fade);
        float borderW = 0.0085f * wScale;
        ReplayDraw.segment(glow, sign, -halfW, halfH, 0.002f, halfW, halfH, 0.002f, borderW, brass, brass);
        ReplayDraw.segment(glow, sign, -halfW, -halfH, 0.002f, halfW, -halfH, 0.002f, borderW, brass, brass);
        ReplayDraw.segment(glow, sign, -halfW, -halfH, 0.002f, -halfW, halfH, 0.002f, borderW * 0.75f, brassDim, brassDim);
        ReplayDraw.segment(glow, sign, halfW, -halfH, 0.002f, halfW, halfH, 0.002f, borderW * 0.75f, brassDim, brassDim);

        float dr = 0.025f * wScale;
        int diaCol = ReplayPalette.alpha(loading ? CYAN_CORE : ReplayPalette.CHAMPAGNE, 0.90 * fade);
        for (int side = -1; side <= 1; side += 2) {
            float dx = side * (halfW + 0.034f * wScale);
            ReplayDraw.quad(glow, sign, dx, dr, 0.002f, dx + dr, 0, 0.002f, dx, -dr, 0.002f, dx - dr, 0, 0.002f,
                    diaCol, diaCol, diaCol, diaCol);
        }

        f.text(sign, text, 0, 0, 0.005f, textScale, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, fade), 0, true, 0);
    }

    /**
     * Wraps any unbounded radian angle in 64-bit double into {@code [-π, +π]} BEFORE trig evaluation.
     * Prevents Minecraft {@code Mth.sin/cos} {@code (int)(v * 10430.378f)} overflow saturation on worlds
     * with large {@code level.getGameTime()} (e.g. pre-built lobby saves with tens of millions of ticks).
     */
    static double wrapAngleRad(double radians) {
        if (!Double.isFinite(radians)) return 0.0;
        return Math.IEEEremainder(radians, Math.PI * 2.0);
    }

    private static float cosf(double radians) {
        return (float) Math.cos(wrapAngleRad(radians));
    }

    private static float sinf(double radians) {
        return (float) Math.sin(wrapAngleRad(radians));
    }

    private static double fract(double value) {
        if (!Double.isFinite(value)) return 0.0;
        double r = value % 1.0;
        return r < 0.0 ? r + 1.0 : r;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // ------------------------------------------------------------------ cube pose state

    static final class CubePose {
        float cx, cy, cz, half;
        float uxx, uxy, uxz;
        float uyx, uyy, uyz;
        float uzx, uzy, uzz;
        final float[] lx = new float[8], ly = new float[8], lz = new float[8];
        final float[] vx = new float[8], vy = new float[8], vz = new float[8];
        final float[] localFaceNx = new float[6], localFaceNy = new float[6], localFaceNz = new float[6];
        final float[] faceNdotV = new float[6], faceDistSq = new float[6];
        final Vector3f camCenter = new Vector3f();
        int frameShadowColor, frameLitColor, glassShadowColor, glassLitColor;
        int edgeHaloColor, edgeCoreColor;
        float brightness;

        void set(Matrix4f board, float cx, float cy, float cz, float half,
                 double rx, double ry, double rz,
                 int frameShadowColor, int frameLitColor, int glassShadowColor, int glassLitColor,
                 int edgeHaloColor, int edgeCoreColor, float brightness) {
            this.cx = cx; this.cy = cy; this.cz = cz; this.half = half;
            this.frameShadowColor = frameShadowColor;
            this.frameLitColor = frameLitColor;
            this.glassShadowColor = glassShadowColor;
            this.glassLitColor = glassLitColor;
            this.edgeHaloColor = edgeHaloColor;
            this.edgeCoreColor = edgeCoreColor;
            this.brightness = brightness;

            float cxr = cosf(rx), sxr = sinf(rx);
            float cyr = cosf(ry), syr = sinf(ry);
            float czr = cosf(rz), szr = sinf(rz);

            uxx = cyr * czr + syr * sxr * szr;
            uxy = cxr * szr;
            uxz = -syr * czr + cyr * sxr * szr;

            uyx = -cyr * szr + syr * sxr * czr;
            uyy = cxr * czr;
            uyz = syr * szr + cyr * sxr * czr;

            uzx = syr * cxr;
            uzy = -sxr;
            uzz = cyr * cxr;

            board.transformPosition(cx, cy, cz, camCenter);

            for (int i = 0; i < 8; i++) {
                float sx = (i & 1) != 0 ? half : -half;
                float sy = (i & 2) != 0 ? half : -half;
                float sz = (i & 4) != 0 ? half : -half;
                float bx = cx + uxx * sx + uyx * sy + uzx * sz;
                float by = cy + uxy * sx + uyy * sy + uzy * sz;
                float bz = cz + uxz * sx + uyz * sy + uzz * sz;
                lx[i] = bx; ly[i] = by; lz[i] = bz;
                board.transformPosition(bx, by, bz, TMP_A);
                vx[i] = TMP_A.x; vy[i] = TMP_A.y; vz[i] = TMP_A.z;
            }

            localFaceNx[0] = -uxx; localFaceNy[0] = -uxy; localFaceNz[0] = -uxz;
            localFaceNx[1] = uxx;  localFaceNy[1] = uxy;  localFaceNz[1] = uxz;
            localFaceNx[2] = -uyx; localFaceNy[2] = -uyy; localFaceNz[2] = -uyz;
            localFaceNx[3] = uyx;  localFaceNy[3] = uyy;  localFaceNz[3] = uyz;
            localFaceNx[4] = -uzx; localFaceNy[4] = -uzy; localFaceNz[4] = -uzz;
            localFaceNx[5] = uzx;  localFaceNy[5] = uzy;  localFaceNz[5] = uzz;

            for (int f = 0; f < 6; f++) {
                int[] fv = FACES[f];
                float fcx = 0.25f * (vx[fv[0]] + vx[fv[1]] + vx[fv[2]] + vx[fv[3]]);
                float fcy = 0.25f * (vy[fv[0]] + vy[fv[1]] + vy[fv[2]] + vy[fv[3]]);
                float fcz = 0.25f * (vz[fv[0]] + vz[fv[1]] + vz[fv[2]] + vz[fv[3]]);
                float dSq = fcx * fcx + fcy * fcy + fcz * fcz;
                faceDistSq[f] = dSq;

                board.transformDirection(localFaceNx[f], localFaceNy[f], localFaceNz[f], TMP_A);
                float nLen = TMP_A.length();
                float dLen = (float) Math.sqrt(dSq);
                if (nLen > 1.0e-6f && dLen > 1.0e-6f) {
                    faceNdotV[f] = -(TMP_A.x * fcx + TMP_A.y * fcy + TMP_A.z * fcz) / (nLen * dLen);
                } else {
                    faceNdotV[f] = 1f;
                }
            }
        }

        void localToWorldCam(Matrix4f board, float sx, float sy, float sz, Vector3f out) {
            float bx = cx + uxx * sx + uyx * sy + uzx * sz;
            float by = cy + uxy * sx + uyy * sy + uzy * sz;
            float bz = cz + uxz * sx + uyz * sy + uzz * sz;
            board.transformPosition(bx, by, bz, out);
        }
    }
}
