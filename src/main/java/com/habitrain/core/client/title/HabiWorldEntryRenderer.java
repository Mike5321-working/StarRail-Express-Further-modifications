package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.habitrain.core.client.title.HabiKineticGfx.*;

/**
 * Complete 1:1 OpenGL port of the 3-act World-Entry Cutscene from {@code habitrain_logo_v3/tunnel_scene.js}:
 * <ul>
 *   <li><b>Act 1 ({@code START}, 2.88s)</b>: Station 01 seamless handoff -> Apple slice split ->
 *       HABITRAIN Car 01 assembly + adjacent half-cars + night platform + swinging canopy lamps ->
 *       Sliding doors open -> Camera rushes into the doorway revealing the Starry Chrono Rail.</li>
 *   <li><b>Act 2 ({@code LOADING}, real-driven loop)</b>: Deep-space nebulae + 14 astrolabe rings +
 *       vanishing portal dial + 6 preset 3D rail tracks (strictly 1 active track + 1 3-car train) +
 *       76 stars + sparse flexing 3D perforated stamps &amp; boarding passes + real-data Tunnel HUD.</li>
 *   <li><b>Act 3 ({@code END}, 1.66s)</b>: Arrival gold-foil postmark seal -> Expanding Apple Portal
 *       Iris (depth-buffer inverted star cutout) revealing the REAL live 3D Minecraft world ->
 *       4-layer glowing crimson/brass/white rim -> Warm champagne-gold bloom.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class HabiWorldEntryRenderer {

    // ── Shared Station 01 & Tunnel Constants ─────────────────────────────────
    private static final double HUB_X = 335.0, HUB_Y = -28.0, HUB_Z = 40.0;
    private static final double TRACK_R = 405.0, CAR_H = 17.0, CAR_GAP = 66.0;

    private static final double[][] OCTA_V = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int[][] OCTA_E = {
            {0, 2}, {0, 3}, {0, 4}, {0, 5},
            {1, 2}, {1, 3}, {1, 4}, {1, 5},
            {2, 4}, {2, 5}, {3, 4}, {3, 5}
    };

    // Palette
    private static final int PAL_PAPER = 0xECE9E2;
    private static final int PAL_PAPER_TXT = 0xF6F3EC;
    private static final int PAL_INK = 0x14161E;
    private static final int PAL_MUTE = 0x7E8498;
    private static final int PAL_RED = 0xE1243B;
    private static final int PAL_RED_BRIGHT = 0xF2354B;
    private static final int PAL_BLUE = 0x6E88FF;
    private static final int PAL_BRASS = 0xC9A055;
    private static final int PAL_BRASS_LIGHT = 0xEED69C;
    private static final int PAL_CHAMPAGNE = 0xFFF5DF;

    // Act 1 Start timings & geometry
    private static final double START_STEM = 0.30;
    private static final double START_CROWN = 0.52;
    private static final double[] START_BANDS = {0.68, 0.79, 0.87, 0.93};
    private static final double START_MORPH = 1.06;
    private static final double STRETCH_X0 = 1.10, STRETCH_XD = 0.42;
    private static final double STRETCH_Y0 = 1.28, STRETCH_YD = 0.46;

    private record SeamDef(double y, double t, int u, int l) {}
    private static final SeamDef[] START_SEAMS = {
            new SeamDef(-45, 0.44, 0, 1),
            new SeamDef(11,  0.60, 1, 2),
            new SeamDef(57,  0.71, 2, 3),
            new SeamDef(97,  0.79, 3, 4),
            new SeamDef(131, 0.85, 4, 5)
    };

    private static final double CAR_W = 940.0, CAR_TOP = -158.0, CAR_BOT = 128.0, CAR_DEPTH = 56.0;
    private static final double CAR_DOOR_W = 136.0, CAR_DOOR_H = 206.0;
    private static final double[] CAR_WIN_X = {-390, -270, -150, 150, 270, 390};
    private static final double CAR_WIN_Y = -44.0, CAR_WIN_W = 88.0, CAR_WIN_H = 70.0;
    private static final double CAR_FLOOR_Y = 110.0;
    private static final double[] BAND_WIN_X = {-112, -40, 32, 104};

    private static final String[] TUNNEL_TIPS_ZH = {
            "一号车厢已检票发车，正沿星夜轨网穿越维度隧道。",
            "深空中自由浮掠的齿孔邮票记录着列车各站台的乘车凭证与职能印记。",
            "正在同步出生点区块与世界光照，即将按 100 BPM 拍点丝滑着陆。",
            "已检票的登车凭证将随苹果视窗光幕展开，无缝驶入真实世界。"
    };

    // Pre-sampled unit apple contour (6 cubic Bezier segments x 16 = 96 vertices)
    private static final int APPLE_PTS = 96;
    private final double[] appleBaseXY = new double[APPLE_PTS * 2];
    private final double[] appleScrXY = new double[APPLE_PTS * 2];

    // Deterministic particle & preset pools (identical seed & ordering to tunnel_scene.js)
    private record StarPt(double zOff, double ang, double rad, double speedMul, double size, boolean glint, int rgb) {}
    private record FloatingDef(int idx, boolean isTicket, int assetIdx, double zOff, double ang0, double angDrift,
                               double rFar, double rNear, double driftAmpX, double driftAmpY,
                               double yaw0, double pitch0, double roll0, double yawSweep, double pitchSweep,
                               double tumbleSpd, double scale, int curlDir, double flexAmp, double camberAmp,
                               double waveFreq, double phase) {}
    private record BokehPt(double x, double y, double z, double r, double a, int sprite, double drift, double ph, double tOn) {}
    private record DustPt(double x, double y, double z, double r, double sp, double ph) {}
    private record RailPreset(boolean isBezier,
                              double z0, double z1, double zPow, double ang0, double angSweep, double angWave,
                              double r0, double rPow, double rBulge, double ax, double ay, double yOff,
                              double[] p0, double[] p1, double[] p2, double[] p3) {}
    private record RailFrame(double[] pos, double[] tangent, double[] side, double[] down, double dsPerParam) {}
    private record TrackSample(double s, RailFrame fr, double arc) {}
    private record RailTransitState(RailPreset preset, int cycleIdx, double tau, double sFront, double sErase,
                                    double sHead, double trainRaw, boolean trainActive, double trainAlpha,
                                    double trackAlpha, double portalPulse, double envAlpha) {}

    private final List<StarPt> stars = new ArrayList<>(76);
    private final List<FloatingDef> tunnelItems = new ArrayList<>(14);
    private final List<BokehPt> startBokeh = new ArrayList<>(36);
    private final List<DustPt> startDust = new ArrayList<>(70);
    private final List<RailPreset> railPresets = new ArrayList<>(6);
    private double stampSpanZ;

    // Reusable scratch buffers
    private final double[] segScratch = new double[1024];
    private final double[] polyScratch = new double[512];

    public HabiWorldEntryRenderer() {
        initAppleContour();
        initPools();
        initRailPresets();
    }

    // ── Main Dispatch ────────────────────────────────────────────────────────

    public void render(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas,
                       HabiWorldEntrySession session, @Nullable HabiBootAudioEngine.Telemetry tel) {
        if (!session.isActive()) return;
        double px = session.getPointerX();
        double py = session.getPointerY();
        double totalT = session.getTotalT();
        double beatFrac = tel != null ? tel.beatFrac() : ((totalT / 0.6) % 1.0);
        double bass = tel != null ? Math.max(0.15, tel.bass()) : 0.35;
        double beatPulse = Math.exp(-beatFrac * 5.2) * bass;

        switch (session.getPhase()) {
            case START -> {
                renderStartSequence(gfx, baseAtlas, tunnelAtlas, session, session.getPhaseT(), px, py, tel, beatPulse);
                renderPostGrain(gfx, baseAtlas, totalT, 0.042);
            }
            case LOADING -> {
                double fadeIn = session.isDirectEntry() ? session.getDirectEntryFadeIn() : 1.0;
                double hudIn = sstep(0.0, 0.42, session.getPhaseT()) * fadeIn;
                renderTimeTunnel(gfx, baseAtlas, tunnelAtlas, session, fadeIn, 0.0, px, py, beatPulse, null);
                renderTunnelHud(gfx, baseAtlas, tunnelAtlas, session, hudIn, 0.0, tel);
                renderPostGrain(gfx, baseAtlas, totalT, 0.042 * fadeIn);
            }
            case END -> {
                double t = session.getPhaseT();
                renderEndBreakout(gfx, baseAtlas, tunnelAtlas, session, t, px, py, tel, beatPulse);
                double grainA = 0.042 * (1.0 - sstep(1.04, 1.56, t));
                if (grainA > 0.002) {
                    renderPostGrain(gfx, baseAtlas, totalT, grainA);
                }
            }
            case INACTIVE -> {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ACT 1: START (0.00s -> 2.88s)
    // ════════════════════════════════════════════════════════════════════════

    private void renderStartSequence(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas,
                                     HabiWorldEntrySession session, double t, double px, double py,
                                     @Nullable HabiBootAudioEngine.Telemetry tel, double beatPulse) {
        double u0 = session.getHandoffU0();
        double uLive = u0 + t;

        double centerK = eInOutCubic(sat(t / 0.62));
        double wK = eInOutCubic(sat((t - STRETCH_X0) / STRETCH_XD));
        double hK = Math.max(0.0, eOutBack(sat((t - STRETCH_Y0) / STRETCH_YD), 1.1));
        double detailK = sstep(1.40, 1.82, t);
        double litK = sat((t - 1.50) / 0.40);
        double doorK = eInOutCubic(sat((t - HabiWorldEntrySession.DOORS_AT) / 0.36));
        double rushK = sat((t - HabiWorldEntrySession.BOARD_AT) / (HabiWorldEntrySession.START_DUR - HabiWorldEntrySession.BOARD_AT));
        double rushE = sstep(0.0, 0.84, rushK);
        double rushD = FOCAL * Math.pow(0.068, rushE);
        double carZ = rushD - FOCAL;
        double doorW = CAR_DOOR_W * lerp(1.0, 1.54, eInCubic(sat((rushE - 0.54) / 0.46)));
        double camFree = 1.0 - eInOutCubic(sat(rushK / 0.55));
        boolean carOn = t >= START_MORPH - 0.02;

        // Camera: Frame 0 identical to Station 01 -> centers -> subtle assembly tilt -> straightens for rush
        double rx0 = 0.04 + 0.024 * Math.sin(u0 * 0.34 + 0.5) - session.getHandoffPy() * 0.045;
        double ry0 = -0.06 + 0.065 * Math.sin(u0 * 0.40) + session.getHandoffPx() * 0.075;
        double asmTilt = sstep(0.90, 1.55, t) * (1.0 - 0.55 * doorK);
        double rxT = 0.03 + 0.055 * asmTilt - py * 0.03;
        double ryT = -0.11 * asmTilt + px * 0.045;
        double rx = lerp(rx0, rxT, centerK) * camFree;
        double ry = lerp(ry0, ryT, centerK) * camFree;
        double rz = -0.016 * Math.sin(sat((t - 0.3) / 1.3) * Math.PI) + 0.028 * Math.sin(rushK * Math.PI);
        double zoom = 1.0 + 0.008 * beatPulse * (1.0 - centerK)
                + 0.12 * sstep(0.15, 0.85, t) * (1.0 - sstep(1.02, 1.60, t))
                + 0.05 * doorK * camFree;
        Cam cam = new Cam(rx, ry, rz, zoom);

        double[] G = {
                lerp(HUB_X, 0.0, centerK),
                lerp(HUB_Y - 12.0 + 9.0 * Math.sin(uLive * 1.15), 17.0, centerK),
                lerp(HUB_Z, 0.0, centerK)
        };
        double[] Rg = euler(
                lerp(-0.08 + 0.03 * Math.sin(uLive * 0.7), 0.0, centerK),
                lerp(-0.18 + 0.20 * Math.sin(uLive * 0.48), 0.0, centerK),
                0.0
        );

        double[] quad = doorK > 0.001 ? doorQuad(cam, carZ, doorW) : null;
        boolean covered = false;
        if (doorK > 0.001) {
            if (quad != null) {
                double minX = Math.min(Math.min(quad[0], quad[2]), Math.min(quad[4], quad[6]));
                double maxX = Math.max(Math.max(quad[0], quad[2]), Math.max(quad[4], quad[6]));
                double minY = Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
                double maxY = Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
                covered = minX < gfx.viewLeft() - 6 && maxX > gfx.viewRight() + 6
                        && minY < gfx.viewTop() - 6 && maxY > gfx.viewBottom() + 6;
            } else if (rushK > 0.4) {
                covered = true;
            }
        }
        double frameFade = 1.0 - sstep(0.76, 0.94, rushE);
        if (doorK > 0.001 && frameFade <= 0.004) covered = true;

        double doorCx = quad != null ? (quad[0] + quad[4]) * 0.5 : CX;
        double doorCy = quad != null ? (quad[1] + quad[5]) * 0.5 : CY;

        if (covered) {
            renderTimeTunnel(gfx, baseAtlas, tunnelAtlas, session, 1.0, 0.0, px, py, beatPulse, rushK);
            drawStartRushFx(gfx, tunnelAtlas, doorCx, doorCy, t, rushK, doorK, null, true);
            drawStartCinemaGrade(gfx, tunnelAtlas, t, rushK, 0.0, true);
            renderStartHud(gfx, baseAtlas, session, t);
            return;
        }

        // 1) Background: Station 01 ambient deep-space -> darker night platform
        double darkK = sstep(0.10, 1.40, t);
        gfx.drawTexturedRect(baseAtlas.stationBgTex.getId(),
                gfx.viewLeft() - 20, gfx.viewTop() - 20, gfx.viewWidth() + 40, gfx.viewHeight() + 40,
                0xFFFFFFFF, BlendMode.NORMAL);
        if (darkK > 0.004) {
            gfx.fillRect(gfx.viewLeft() - 20, gfx.viewTop() - 20, gfx.viewWidth() + 40, gfx.viewHeight() + 40,
                    withAlpha(0x05060A, 0.62 * darkK));
            double bgCx = lerp(CX + 320.0, CX, centerK);
            double bgCy = lerp(CY - 40.0, CY - 60.0, centerK);
            gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), bgCx, bgCy, 920.0,
                    withAlpha(0x1F1C2A, 0.55 * darkK), BlendMode.ADDITIVE);
        }

        // 2) Station 01 distant backdrop (grid, ghost 01, expanding ring & octahedron, apple aura)
        double stA = 1.0 - sstep(0.0, 0.55, t);
        if (stA > 0.004) {
            drawStationBackdrop(gfx, baseAtlas, tunnelAtlas, cam, uLive, stA, t, beatPulse);
        }

        // 2b) Distant bokeh orbs
        double bokA = sstep(0.30, 1.10, t) * (1.0 - rushE);
        if (bokA > 0.004) drawStartBokeh(gfx, tunnelAtlas, cam, carZ, t, bokA);

        // 2c) Opposite-platform colonnade & hanging station signs
        double colA = sstep(1.20, 1.75, t) * frameFade;
        if (colA > 0.004) drawStartColonnade(gfx, tunnelAtlas, cam, carZ, colA);

        // 3) Warm crimson/brass halo behind Car 01
        double coreA = 0.55 * sstep(0.40, 0.95, t);
        double haloA = Math.max(coreA, sstep(1.20, 1.80, t)) * (1.0 - rushE);
        if (haloA > 0.004) {
            double[] pc = cam.p(cam.v(0, -10, 260 + carZ));
            if (pc != null) {
                double hr = 760.0 * pc[2];
                gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), pc[0], pc[1], hr,
                        withAlpha(PAL_RED, 0.16 * haloA), BlendMode.ADDITIVE);
                gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), pc[0], pc[1], hr * 0.72,
                        withAlpha(0xD6AA68, 0.07 * haloA), BlendMode.ADDITIVE);
            }
        }

        // 4) Expanding viaduct (back pass)
        double viaA = 1.0 - sstep(0.22, 0.78, t);
        double[] viaC = {lerp(HUB_X, 0.0, centerK), lerp(HUB_Y, 0.0, centerK) + 34.0, lerp(HUB_Z, 0.0, centerK)};
        double viaR = 1.0 + 0.95 * eInCubic(sat(t / 0.78));
        double w8 = TAU / 4.8;
        double trainTh = -0.75 + w8 * (uLive + (3.5 / 2.6) * (1.0 - Math.exp(-2.6 * uLive))) + w8 * (1.5 * t * t + 2.4 * t * t * t);
        double trainSpd = 1.0 + 3.5 * Math.exp(-2.6 * uLive) + 3.0 * t + 7.2 * t * t;
        if (viaA > 0.004) drawStartViaduct(gfx, baseAtlas, cam, viaC, viaR, viaA, trainTh, trainSpd, false);

        // 5) Doorway interior: Starry Chrono Rail Time Tunnel clipped cleanly to the opening doorway!
        if (quad != null) {
            if (frameFade < 0.995) {
                renderTimeTunnel(gfx, baseAtlas, tunnelAtlas, session, 1.0, 0.0, px, py, beatPulse, rushK);
            } else {
                gfx.beginDepthClipPolygon(quad);
                try {
                    renderTimeTunnel(gfx, baseAtlas, tunnelAtlas, session, 1.0, 0.0, px, py, beatPulse, rushK);
                    double veil = (1.0 - 0.70 * doorK) * (1.0 - sstep(0.0, 0.50, rushK));
                    if (veil > 0.004) {
                        double minY = Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
                        double maxY = Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
                        double vr = Math.max(40.0, (maxY - minY) * 0.75);
                        gfx.fillRect(gfx.viewLeft() - 20, gfx.viewTop() - 20, gfx.viewWidth() + 40, gfx.viewHeight() + 40,
                                withAlpha(0xD6A060, veil * 0.55));
                        gfx.drawGlow(tunnelAtlas.hardGlowTex.getId(), doorCx, doorCy, vr,
                                withAlpha(0xFFFAEE, veil), BlendMode.NORMAL);
                    }
                } finally {
                    gfx.endDepthClip();
                }
            }
        }

        // 6) Seam glows + Splitting Apple + Adjacent Half-Cars + HABITRAIN Car 01
        if (t > 0.36 && t < 1.25) drawSeamGlows(gfx, baseAtlas, cam, G, Rg, t);
        if (t < START_MORPH + 0.25) drawStartApple(gfx, baseAtlas, cam, G, Rg, t, uLive, carZ);

        double adjK = sstep(1.42, 1.86, t);
        if (carOn && adjK > 0.004) {
            drawAdjacentCars(gfx, tunnelAtlas, cam, carZ,
                    adjK * frameFade * (1.0 - sstep(0.0, 0.40, rushK)), litK, 1.0 - eOutCubic(adjK));
        }
        if (carOn) {
            double carAlpha = sstep(START_MORPH - 0.02, START_MORPH + 0.06, t) * frameFade;
            drawBoardingCar(gfx, baseAtlas, tunnelAtlas, session, cam, carZ,
                    wK, hK, detailK, litK, doorK, doorW, t, carAlpha, lerp(38.0, CAR_DEPTH, wK));
        }

        // 8) Expanding viaduct (front pass)
        if (viaA > 0.004) drawStartViaduct(gfx, baseAtlas, cam, viaC, viaR, viaA, trainTh, trainSpd, true);

        // 9) Platform floor, swinging canopy lamps, and rotating edge LED line
        double platA = sstep(1.18, 1.70, t) * frameFade;
        if (platA > 0.004) {
            drawStartPlatform(gfx, cam, carZ, platA, doorK, rushK, litK, wK);
            drawStartCanopy(gfx, tunnelAtlas, cam, carZ, platA * (1.0 - rushE * 0.3), t);
        }
        if (frameFade > 0.004) drawStartEdgeLine(gfx, cam, carZ, t, beatPulse, frameFade);

        // 10) Retreating Station 01 plates + horizontal band stretch flare + rush darken outside doorway
        if (t < 0.54) drawRetreatingStationPlates(gfx, baseAtlas, cam, uLive, t);
        drawBandFlare(gfx, tunnelAtlas, cam, carZ, t);
        if (quad != null) {
            double[] backQuad = doorQuad(cam, carZ + CAR_DEPTH, doorW);
            double darkAmt = 0.86 * sstep(0.0, 0.50, rushK) * frameFade;
            if (darkAmt > 0.004) {
                gfx.beginInvertedDepthClipPolygon(backQuad != null ? backQuad : quad);
                try {
                    gfx.fillRect(gfx.viewLeft() - 20, gfx.viewTop() - 20, gfx.viewWidth() + 40, gfx.viewHeight() + 40,
                            withAlpha(0x030409, darkAmt));
                } finally {
                    gfx.endDepthClip();
                }
            }
        }

        // 11) Doorway warm glow, departure flash, speed lines, cinema grade & letterbox
        drawStartRushFx(gfx, tunnelAtlas, doorCx, doorCy, t, rushK, doorK, quad, false);
        drawStartCinemaGrade(gfx, tunnelAtlas, t, rushK, frameFade, false);

        // 12) Station 01 HUD fadeout + Start HUD
        double hudOut = 1.0 - sstep(0.0, 0.32, t);
        if (hudOut > 0.004) drawStationHudFade(gfx, baseAtlas, hudOut, tel, uLive);
        renderStartHud(gfx, baseAtlas, session, t);
    }

    private void drawStationBackdrop(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas,
                                     Cam cam, double u, double alpha, double t, double beatPulse) {
        int sn = 0;
        for (int gx = -6; gx <= 6; gx++) {
            for (int gy = -4; gy <= 4; gy++) {
                double[] c = cam.v(gx * 240.0, gy * 240.0, 880.0);
                sn = pushSeg(cam, c[0] - 7, c[1], c[2], c[0] + 7, c[1], c[2], sn);
                sn = pushSeg(cam, c[0], c[1] - 7, c[2], c[0], c[1] + 7, c[2], sn);
            }
        }
        gfx.drawSegments(segScratch, sn, 1.0, withAlpha(PAL_PAPER, 0.09 * alpha));

        double drift = 14.0 * Math.sin(u * 0.28);
        baseAtlas.drawAsciiOnPlane(gfx, cam, cam.v(HUB_X + 260.0 + drift, -40.0, 680.0), mm(cam.R, RZ(-0.025)),
                HabiKineticAtlas.FontFace.DISPLAY, "01", 0, 0, 720.0, 10.0, withAlpha(PAL_PAPER, 0.042 * alpha), 0);

        double expand = 1.0 + 0.55 * eInCubic(sat(t / 0.55));
        double[] C = {HUB_X, HUB_Y, HUB_Z + 220.0};
        double[] R = euler(0.16 * Math.sin(u * 0.22) + 0.08, 0.25 * Math.sin(u * 0.16 + 0.8), 0.0);
        double r0 = 435.0 * expand;

        int pn = 0;
        for (int i = 0; i <= 120; i++) {
            double[] pt = cam.p(ringPt(cam, C, R, (i / 120.0) * TAU, r0));
            if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
        }
        gfx.drawPolyline(polyScratch, pn, 1.1, withAlpha(PAL_PAPER, 0.28 * 0.82 * alpha), false);

        sn = 0;
        for (int i = 0; i < 72; i++) {
            double ang = (i / 72.0) * TAU;
            double len = (i % 6 == 0) ? 20.0 : 10.0;
            double[] p1 = ringPt(cam, C, R, ang, r0 + 5.0);
            double[] p2 = ringPt(cam, C, R, ang, r0 + len);
            sn = pushSeg(cam, p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], sn);
        }
        gfx.drawSegments(segScratch, sn, 1.0, withAlpha(PAL_PAPER, 0.09 * 0.82 * alpha));

        double s0 = u * 0.45 + 0.4;
        pn = 0;
        for (int i = 0; i <= 32; i++) {
            double[] pt = cam.p(ringPt(cam, C, R, s0 + (i / 32.0) * 1.15, r0 + 15.0));
            if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
        }
        gfx.drawPolyline(polyScratch, pn, 3.2 + beatPulse * 1.4, withAlpha(PAL_RED_BRIGHT, 0.82 * alpha), false);

        double[] pb = cam.p(ringPt(cam, C, R, -u * 0.75 + 2.4, r0));
        if (pb != null) gfx.fillCircle(pb[0], pb[1], 4.5, withAlpha(0x5B78FF, 0.82 * alpha), 14);

        double[] Ro = euler(0.45 + 0.05 * Math.sin(u * 0.3), 0.28 * u + 1.2, 0.07 * Math.sin(u * 0.2));
        double oR = 265.0 * expand;
        double[][] V = new double[OCTA_V.length][];
        for (int i = 0; i < OCTA_V.length; i++) {
            V[i] = cam.v(va(C, mv(Ro, new double[]{OCTA_V[i][0] * oR, OCTA_V[i][1] * oR * 1.3, OCTA_V[i][2] * oR})));
        }
        double zc = cam.v(C)[2];
        for (int j = 0; j < OCTA_E.length; j++) {
            double[] A = V[OCTA_E[j][0]], B = V[OCTA_E[j][1]];
            double[] pa = cam.p(A), pb2 = cam.p(B);
            if (pa == null || pb2 == null) continue;
            double dz = ((A[2] + B[2]) * 0.5 - zc) / oR;
            double ea = alpha * lerp(0.85, 0.22, (dz + 1.0) * 0.5);
            boolean red = (j % 4 == 0);
            gfx.drawLine(pa[0], pa[1], pb2[0], pb2[1], red ? 1.8 : 1.1,
                    red ? withAlpha(PAL_RED, ea) : withAlpha(PAL_PAPER, 0.72 * ea));
        }

        double[] pHub = cam.p(cam.v(HUB_X, HUB_Y - 12.0 + 9.0 * Math.sin(u * 1.15), HUB_Z));
        if (pHub != null) {
            double auraR = (255.0 + 24.0 * beatPulse) * pHub[2];
            gfx.drawGlow(tunnelAtlas.stationAuraTex.getId(), pHub[0], pHub[1], auraR,
                    withAlpha(0xFFFFFF, 0.24 * alpha), BlendMode.ADDITIVE);
        }
    }

    private void drawStartBokeh(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, Cam cam, double carZ, double t, double alpha) {
        double L = gfx.viewLeft(), T = gfx.viewTop(), R = gfx.viewRight(), B = gfx.viewBottom();
        for (BokehPt b : startBokeh) {
            double on = sstep(b.tOn, b.tOn + 0.45, t);
            if (on <= 0.004) continue;
            double[] p = cam.p(cam.v(b.x + b.drift * t, b.y + 6.0 * Math.sin(t * 0.9 + b.ph), b.z + carZ * 0.22));
            if (p == null) continue;
            double r = b.r * (0.75 + 0.5 * p[2]);
            if (p[0] < L - r || p[0] > R + r || p[1] < T - r || p[1] > B + r) continue;
            double a = alpha * on * b.a * (0.78 + 0.22 * Math.sin(t * 1.7 + b.ph));
            gfx.drawGlow(tunnelAtlas.bokehTex[b.sprite].getId(), p[0], p[1], r, withAlpha(0xFFFFFF, a), BlendMode.ADDITIVE);
        }
    }

    private void drawStartColonnade(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, Cam cam, double carZ, double alpha) {
        double Z = 720.0 + carZ;
        // Canopy overhead beam
        double[] beam = projNearPoly(cam, cam.v(-3400, -600, Z), cam.v(3400, -600, Z), cam.v(3400, -548, Z), cam.v(-3400, -548, Z));
        if (beam != null) gfx.fillConvexPolygonCount(withAlpha(0x0B0E17, alpha), BlendMode.NORMAL, beam, beam.length / 2);
        drawNearSeg(gfx, cam, cam.v(-3400, -548, Z), cam.v(3400, -548, Z), 1.0, withAlpha(PAL_BRASS, 0.30 * alpha));

        // Pillars + capitals + brass edge + warm wall sconces
        for (int x = -2700; x <= 2700; x += 600) {
            double[] q = projNearPoly(cam, cam.v(x - 30, -600, Z), cam.v(x + 30, -600, Z), cam.v(x + 30, 150, Z), cam.v(x - 30, 150, Z));
            if (q == null) continue;
            gfx.fillConvexPolygonCount(withAlpha(0x0A0D15, alpha), BlendMode.NORMAL, q, q.length / 2);
            double[] cap = projNearPoly(cam, cam.v(x - 46, -548, Z), cam.v(x + 46, -548, Z), cam.v(x + 46, -532, Z), cam.v(x - 46, -532, Z));
            if (cap != null) gfx.fillConvexPolygonCount(withAlpha(0x151926, alpha), BlendMode.NORMAL, cap, cap.length / 2);
            drawNearSeg(gfx, cam, cam.v(x - 30, -532, Z), cam.v(x - 30, 150, Z), 1.0, withAlpha(PAL_BRASS_LIGHT, 0.20 * alpha));

            double[] pl = cam.p(cam.v(x + 34, -180, Z));
            if (pl != null) {
                double gr = 70.0 * pl[2];
                gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), pl[0], pl[1], gr,
                        withAlpha(0xFFD696, 0.32 * alpha), BlendMode.ADDITIVE);
                gfx.fillRect(pl[0] - 1.5 * pl[2], pl[1] - 4.0 * pl[2], 3.0 * pl[2], 8.0 * pl[2],
                        withAlpha(0xFFE7B8, alpha));
            }
        }

        // Far platform edge & red safety lamps
        drawNearSeg(gfx, cam, cam.v(-3400, 150, Z), cam.v(3400, 150, Z), 1.0, withAlpha(PAL_PAPER, 0.20 * alpha));
        int redDot = withAlpha(PAL_RED_BRIGHT, 0.55 * alpha);
        for (int x = -3300; x <= 3300; x += 150) {
            double[] p = cam.p(cam.v(x, 146, Z));
            if (p != null) gfx.fillRect(p[0] - 1.2, p[1] - 1.2, 2.4, 2.4, redDot);
        }

        // Two hanging station nameplates (苹果园站)
        for (double sx : new double[]{-280.0, 600.0}) {
            gfx.drawPlaneSprite(cam, cam.v(sx, -470, Z), cam.R, tunnelAtlas.stationSignTex.getId(),
                    152.0, 82.0, 304.0, 110.0, withAlpha(0xFFFFFF, alpha), BlendMode.NORMAL);
        }
    }

    private void drawStartViaduct(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, Cam cam, double[] C3,
                                  double rScale, double alpha, double th, double speedMul, boolean frontPass) {
        double r = TRACK_R * rScale;
        double[] R = mm(RZ(-0.22), RX(-1.16));
        double[] C = cam.v(C3);
        double[] M = mm(cam.R, R);
        double a = 0.92 * alpha;

        double step = 0.035;
        int sn = 0;
        for (double rr : new double[]{r - 8.0, r + 8.0}) {
            for (double ang = 0.0; ang < TAU; ang += step) {
                double b = Math.min(ang + step, TAU);
                if (isBack(M, (ang + b) * 0.5) == frontPass) continue;
                double[] p0 = ringWorld(C, M, ang, rr, 0);
                double[] p1 = ringWorld(C, M, b, rr, 0);
                sn = pushSeg(cam, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], sn);
                if (sn >= 240) { gfx.drawSegments(segScratch, sn, 1.8, withAlpha(PAL_PAPER, 0.72 * a)); sn = 0; }
            }
        }
        if (sn > 0) gfx.drawSegments(segScratch, sn, 1.8, withAlpha(PAL_PAPER, 0.72 * a));

        sn = 0;
        for (double ang = 0.0; ang < TAU; ang += 0.075) {
            if (isBack(M, ang) == frontPass) continue;
            double[] p0 = ringWorld(C, M, ang, r - 14.0, 0);
            double[] p1 = ringWorld(C, M, ang, r + 14.0, 0);
            sn = pushSeg(cam, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], sn);
        }
        if (sn > 0) gfx.drawSegments(segScratch, sn, 1.2, withAlpha(PAL_PAPER, 0.28 * a));

        double trailLen = clamp(0.42 * speedMul, 0.45, 2.6);
        double tail0 = th - (2.0 * CAR_GAP) / r - 0.12;
        for (int i = 0; i < 24; i++) {
            double s0 = tail0 - (i / 24.0) * trailLen;
            double s1 = tail0 - ((i + 1) / 24.0) * trailLen;
            if (isBack(M, (s0 + s1) * 0.5) == frontPass) continue;
            double[] p0 = cam.p(ringWorld(C, M, s0, r, 0));
            double[] p1 = cam.p(ringWorld(C, M, s1, r, 0));
            if (p0 != null && p1 != null) {
                gfx.drawLine(p0[0], p0[1], p1[0], p1[1], 3.2, withAlpha(PAL_RED_BRIGHT, alpha * (1.0 - i / 24.0) * 0.9));
            }
        }

        record CarItem(double[] O, double[] Mc, HabiKineticAtlas.Slab tx) {}
        List<CarItem> cars = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) {
            double ang = th - (c * CAR_GAP) / r;
            if (isBack(M, ang) == frontPass) continue;
            double ca = Math.cos(ang), sa = Math.sin(ang);
            double[] Mc = mm(M, new double[]{-sa, -ca, 0, ca, -sa, 0, 0, 0, 1});
            cars.add(new CarItem(ringWorld(C, M, ang, r, -CAR_H), Mc, c == 0 ? baseAtlas.stCarLead : baseAtlas.stCar));
        }
        cars.sort((p, q) -> Double.compare(q.O[2], p.O[2]));
        for (CarItem c : cars) {
            gfx.drawSlab(cam, c.O, c.Mc, c.tx, CAR_H, alpha, 0.0, false, false);
        }
    }

    private record BandState(double[] lp, @Nullable double[] R, double a) {}

    private BandState startBandState(HabiKineticAtlas.Slab b, int i, double t) {
        double[] lp = {0.0, b.yc(), 0.0};
        double[] R = null;
        double a = 1.0;
        if (i == 0) {
            double dt = t - START_CROWN;
            if (dt < 0.0 && dt > -0.08) {
                double tr = sstep(-0.08, 0.0, dt);
                lp = new double[]{Math.sin(t * 115.0) * 2.8 * tr, b.yc() - 2.5 * tr, 0.0};
                R = euler(0, 0, Math.sin(t * 95.0) * 0.014 * tr);
            } else if (dt >= 0.0) {
                double k = eOutCubic(sat(dt / 0.56));
                lp = new double[]{0.0, b.yc() - 320.0 * k, 160.0 * k};
                R = euler(-0.95 * k, 0.12 * k, -0.06 * k);
                a = 1.0 - sstep(0.28, 0.90, k);
            }
        } else if (i == 1) {
            a = 1.0 - sstep(START_MORPH, START_MORPH + 0.10, t);
        } else {
            double tDrop = START_BANDS[i - 2];
            double dt = t - tDrop;
            int dir = (i % 2 == 1) ? 1 : -1;
            if (dt < 0.0 && dt > -0.08) {
                double tr = sstep(-0.08, 0.0, dt);
                lp = new double[]{Math.sin(t * 120.0 + i * 2.1) * 3.2 * tr, b.yc() + 1.8 * tr, 0.0};
                R = euler(0, 0, Math.sin(t * 100.0 + i) * 0.016 * tr);
            } else if (dt >= 0.0) {
                double k = sat(dt / 0.42);
                double fall = k * k;
                lp = new double[]{dir * (38.0 + 20.0 * i) * eOutCubic(k), b.yc() + 360.0 * fall + 34.0 * k, -65.0 * k};
                R = euler(0.95 * fall, dir * 0.30 * k, dir * 0.20 * k);
                a = 1.0 - sstep(0.18, 0.56, k);
            }
        }
        return new BandState(lp, R, a);
    }

    private void drawStartApple(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, Cam cam,
                                double[] G, double[] Rg, double t, double uLive, double carZ) {
        double kS = sat((t - START_STEM) / 0.52);
        if (kS < 1.0) {
            double a = 1.0 - sstep(0.32, 0.95, kS);
            double lift = eOutCubic(kS);
            for (int which = 0; which <= 1; which++) {
                double[] loc = which == 1
                        ? new double[]{6 + 120 * lift, -124 - 200 * lift, 14 - 60 * lift}
                        : new double[]{-6 - 70 * lift, -150 - 250 * lift, 14 - 30 * lift};
                double[] R = which == 1
                        ? euler(0.5 * lift, 0, 0.06 * Math.sin(uLive * 1.5) + 1.2 * lift)
                        : euler(0, 0, -0.2 - 0.9 * lift);
                gfx.drawSlab(cam, cam.v(va(G, mv(Rg, loc))), mm(cam.R, mm(Rg, R)),
                        which == 1 ? baseAtlas.stLeaf : baseAtlas.stStem, 14.0, a, 0.0, false, false);
            }
        }

        record BandDraw(HabiKineticAtlas.Slab b, int i, double[] O, double[] M, double a) {}
        List<BandDraw> items = new ArrayList<>(baseAtlas.stBands.length);
        for (int i = 0; i < baseAtlas.stBands.length; i++) {
            HabiKineticAtlas.Slab b = baseAtlas.stBands[i];
            BandState st = startBandState(b, i, t);
            if (st.a <= 0.004) continue;
            double[] O = cam.v(va(G, mv(Rg, st.lp)));
            double[] M = st.R != null ? mm(cam.R, mm(Rg, st.R)) : mm(cam.R, Rg);
            items.add(new BandDraw(b, i, O, M, st.a));
        }
        items.sort((p, q) -> Double.compare(q.O[2], p.O[2]));

        boolean clipFloor = t >= 1.10;
        double floorYScr = H + 20.0;
        if (clipFloor) {
            double[] pf = cam.p(cam.v(0, CAR_FLOOR_Y, carZ - 4.0));
            if (pf != null) floorYScr = pf[1];
        }
        for (BandDraw it : items) {
            if (clipFloor && it.i >= 2) {
                gfx.enableVirtualScissor(gfx.viewLeft() - 40, gfx.viewTop() - 40,
                        gfx.viewRight() + 40, Math.max(gfx.viewTop(), floorYScr));
                try {
                    gfx.drawSlab(cam, it.O, it.M, it.b, 38.0, it.a, 0.0, false, false);
                } finally {
                    gfx.disableVirtualScissor();
                }
            } else {
                gfx.drawSlab(cam, it.O, it.M, it.b, 38.0, it.a, 0.0, false, false);
            }
        }
    }

    private void drawSeamGlows(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, Cam cam,
                               double[] G, double[] Rg, double t) {
        double[] M = mm(cam.R, Rg);
        for (int s = 0; s < START_SEAMS.length; s++) {
            SeamDef sm = START_SEAMS[s];
            double dt = t - sm.t;
            if (dt < 0.0 || dt > 0.28) continue;
            double inten = sstep(0.0, 0.045, dt) * (1.0 - sstep(0.085, 0.28, dt));
            if (inten <= 0.004) continue;

            HabiKineticAtlas.Slab bu = baseAtlas.stBands[sm.u];
            HabiKineticAtlas.Slab bl = baseAtlas.stBands[sm.l];
            BandState su = startBandState(bu, sm.u, t);
            BandState sl = startBandState(bl, sm.l, t);
            double yUEdge = su.lp[1] + (bu.y1() - bu.yc());
            double yLEdge = sl.lp[1] + (bl.y0() - bl.yc());
            double[] gapLoc = {
                    (su.lp[0] + sl.lp[0]) * 0.5,
                    (yUEdge + yLEdge) * 0.5,
                    (su.lp[2] + sl.lp[2]) * 0.5 + 14.0
            };
            double[] O = cam.v(va(G, mv(Rg, gapLoc)));
            double hw = appleHalfWidthAtY(sm.y) + 18.0;
            double grow = eOutCubic(sat(dt / 0.07));
            double shrink = 1.0 - 0.58 * sstep(0.085, 0.28, dt);
            double ex = hw * grow * shrink;

            // Soft outer halo + hot center beam
            int edge0 = withAlpha(PAL_RED_BRIGHT, 0.0);
            int midRed = withAlpha(PAL_RED_BRIGHT, 0.80 * inten * 0.36);
            int centerWhite = withAlpha(0xFFFAEE, inten * 0.36);
            gfx.fillPlaneGradientH(cam, O, M, -ex, -9.0, ex * 0.14, 18.0, edge0, midRed, BlendMode.ADDITIVE);
            gfx.fillPlaneGradientH(cam, O, M, -ex * 0.86, -9.0, ex * 0.86, 18.0, midRed, centerWhite, BlendMode.ADDITIVE);
            gfx.fillPlaneGradientH(cam, O, M, 0.0, -9.0, ex * 0.86, 18.0, centerWhite, midRed, BlendMode.ADDITIVE);
            gfx.fillPlaneGradientH(cam, O, M, ex * 0.86, -9.0, ex * 0.14, 18.0, midRed, edge0, BlendMode.ADDITIVE);

            int midRedCore = withAlpha(PAL_RED_BRIGHT, 0.80 * inten);
            int centerCore = withAlpha(0xFFFAEE, inten);
            gfx.fillPlaneGradientH(cam, O, M, -ex, -1.6, ex * 0.14, 3.2, edge0, midRedCore, BlendMode.ADDITIVE);
            gfx.fillPlaneGradientH(cam, O, M, -ex * 0.86, -1.6, ex * 0.86, 3.2, midRedCore, centerCore, BlendMode.ADDITIVE);
            gfx.fillPlaneGradientH(cam, O, M, 0.0, -1.6, ex * 0.86, 3.2, centerCore, midRedCore, BlendMode.ADDITIVE);
            gfx.fillPlaneGradientH(cam, O, M, ex * 0.86, -1.6, ex * 0.14, 3.2, midRedCore, edge0, BlendMode.ADDITIVE);

            // Sparks flying off seam ends
            for (int k = 0; k < 8; k++) {
                int side = (k % 2 == 1) ? 1 : -1;
                double hr = hash(s * 13.1 + k * 3.7);
                double d = hw * grow + dt * (200.0 + 280.0 * hr);
                double sy = (hash(s * 5.3 + k * 9.1) - 0.5) * 32.0 * dt * 6.0 + 60.0 * dt * dt * 4.0;
                double[] ps = cam.p(va(O, mv(M, new double[]{side * d, sy, 0.0})));
                if (ps != null) {
                    int col = withAlpha(k % 3 == 0 ? PAL_RED_BRIGHT : PAL_CHAMPAGNE, inten * (0.5 + 0.5 * hr));
                    gfx.fillCircle(ps[0], ps[1], Math.max(1.0, (1.2 + 1.6 * hr) * ps[2]), col, 8, BlendMode.ADDITIVE);
                }
            }
        }
    }

    private void drawAdjacentCars(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, Cam cam,
                                  double carZ, double alpha, double litK, double slide) {
        if (alpha <= 0.004) return;
        double[] O = cam.v(0, 0, carZ + 2.0);
        double[] M = cam.R;
        double half = CAR_W * 0.5, span = 840.0;
        double top = CAR_TOP, bot = CAR_BOT;
        double roofH = 28.0, skirtTop = bot - 34.0;
        double push = 140.0 * slide;
        double beltY = CAR_WIN_Y + CAR_WIN_H * 0.5 + 13.0;

        for (int side : new int[]{-1, 1}) {
            double gap = 34.0 + push;
            double x0 = side * (half + gap);
            double x1 = side * (half + gap + span);
            double xl = Math.min(x0, x1), xw = Math.abs(x1 - x0);

            // Dark-red adjacent car body + roof + skirt + champagne beltlines
            gfx.fillPlaneGradientV(cam, O, M, xl, top + roofH, xw, (skirtTop - (top + roofH)) * 0.55,
                    withAlpha(0x881626, alpha), withAlpha(0x831424, alpha), BlendMode.NORMAL);
            gfx.fillPlaneGradientV(cam, O, M, xl, top + roofH + (skirtTop - (top + roofH)) * 0.55, xw,
                    (skirtTop - (top + roofH)) * 0.45,
                    withAlpha(0x8E1728, alpha), withAlpha(0x540B15, alpha), BlendMode.NORMAL);

            gfx.fillPlaneGradientV(cam, O, M, xl, top, xw, roofH,
                    withAlpha(0xE8E4DC, 0.68 * alpha), withAlpha(0xB6B0A6, 0.52 * alpha), BlendMode.NORMAL);
            gfx.fillPlaneRect(cam, O, M, xl, top + roofH - 1.5, xw, 1.5, withAlpha(PAL_BRASS, 0.42 * alpha));
            gfx.fillPlaneRect(cam, O, M, xl, top + roofH, xw, 3.0, withAlpha(0x0E1018, 0.88 * alpha));

            gfx.fillPlaneRect(cam, O, M, xl, skirtTop, xw, bot - skirtTop, withAlpha(0x0E1018, 0.96 * alpha));
            gfx.fillPlaneRect(cam, O, M, xl, skirtTop - 3.0, xw, 1.8, withAlpha(PAL_BRASS, 0.55 * alpha));
            gfx.fillPlaneRect(cam, O, M, xl, beltY, xw, 2.2, withAlpha(PAL_BRASS_LIGHT, 0.62 * alpha));
            gfx.fillPlaneRect(cam, O, M, xl, beltY + 5.2, xw, 1.0, withAlpha(PAL_BRASS_LIGHT, 0.40 * alpha));

            // 5 receding windows per adjacent car
            for (int k = 0; k < 5; k++) {
                double wx = x0 + side * (96.0 + k * 146.0);
                double fall = Math.pow(1.0 - k / 5.0, 2.0);
                double lit = sat(litK * 1.35 - 0.18 - k * 0.12) * fall;
                int tint = withAlpha(lerpRgb(0x5A3640, 0xE8C894, lit), alpha * (0.20 + 0.65 * fall));
                gfx.drawPlaneSprite(cam, O, M, tunnelAtlas.carWinLitTex.getId(),
                        50.0 - wx, 41.0 - CAR_WIN_Y, 100.0, 82.0, tint, BlendMode.NORMAL);
            }

            // Night distance fade mask melting outer car ends into the station darkness
            if (side > 0) {
                gfx.fillPlaneGradientH(cam, O, M, xl, top - 1, xw * 0.52, bot - top + 2,
                        withAlpha(0x070910, 0.12 * alpha), withAlpha(0x070910, 0.48 * alpha), BlendMode.NORMAL);
                gfx.fillPlaneGradientH(cam, O, M, xl + xw * 0.52, top - 1, xw * 0.48, bot - top + 2,
                        withAlpha(0x070910, 0.48 * alpha), withAlpha(0x070910, alpha), BlendMode.NORMAL);
            } else {
                gfx.fillPlaneGradientH(cam, O, M, xl, top - 1, xw * 0.48, bot - top + 2,
                        withAlpha(0x070910, alpha), withAlpha(0x070910, 0.48 * alpha), BlendMode.NORMAL);
                gfx.fillPlaneGradientH(cam, O, M, xl + xw * 0.48, top - 1, xw * 0.52, bot - top + 2,
                        withAlpha(0x070910, 0.48 * alpha), withAlpha(0x070910, 0.12 * alpha), BlendMode.NORMAL);
            }

            // 5-fold accordion gangway between Car 01 and adjacent cars
            double gwA = 1.0 - sat(slide * 3.0);
            if (gwA > 0.004) {
                double gx = side < 0 ? (-half - 34.0) : half;
                gfx.drawPlaneSprite(cam, O, M, tunnelAtlas.gangwayTex.getId(),
                        3.0 - gx, -(top + 14.0), 40.0, 264.0, withAlpha(0xFFFFFF, alpha * gwA), BlendMode.NORMAL);
            }
        }
    }

    private void drawBoardingCar(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas,
                                 HabiWorldEntrySession session, Cam cam, double carZ,
                                 double wK, double hK, double detailK, double litK, double doorK,
                                 double doorW, double t, double alpha, double depth) {
        if (alpha <= 0.004) return;
        double hKs = sat(hK);
        double w = lerp(344.0, CAR_W, wK);
        double top = lerp(-23.0, CAR_TOP, hK);
        double bot = lerp(23.0, CAR_BOT, hK);
        double roofH = 28.0 * hKs;
        double hw = doorW * 0.5;
        double doorTop = Math.max(top + roofH + 5.0, -CAR_DOOR_H * 0.5);
        double doorBot = Math.min(bot - 8.0, CAR_DOOR_H * 0.5);
        double doorSpanH = Math.max(8.0, doorBot - doorTop);
        boolean hole = doorK > 0.001;

        double[] O = cam.v(0, 0, carZ);
        double[] M = cam.R;
        double[] zc = {M[2], M[5], M[8]};
        boolean front = -(zc[0] * O[0] + zc[1] * O[1] + zc[2] * (O[2] + FOCAL)) < 0.0;

        double[] p0 = cam.p(O);
        double[] p1 = cam.p(va(O, vs(zc, depth)));
        if (p0 == null || p1 == null) return;
        double len = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
        int n = (int) clamp(Math.ceil(len / 1.5) + 1.0, 2.0, 26.0);

        double[] frontHoleQuad = hole ? projectPlaneRect(cam, O, M, -hw, doorTop, doorW, doorSpanH) : null;
        double[] backO = va(O, vs(zc, depth));
        double[] backHoleQuad = hole ? projectPlaneRect(cam, backO, M, -hw, doorTop, doorW, doorSpanH) : null;

        // 1) Extruded 3D dark-red body depth slices + recessed doorway metallic jamb walls
        if (backHoleQuad != null) gfx.beginInvertedDepthClipPolygon(backHoleQuad);
        try {
            for (int j = 0; j < n; j++) {
                int i = front ? (n - j) : j;
                if (i == 0) continue;
                double depthFrac = i / (double) n;
                double[] sliceO = va(O, vs(zc, depth * depthFrac));
                int col = withAlpha(i == n ? 0x380710 : lerpRgb(0x8B1222, 0x4C0913, depthFrac), alpha);
                gfx.fillPlaneRect(cam, sliceO, M, -w * 0.5, top, w, bot - top, col);
                if (hole && i > 1 && i < n) {
                    int jambCol = withAlpha(depthFrac < 0.28 ? 0x222634 : (depthFrac > 0.80 ? 0x181B26 : 0x5E1120), alpha);
                    gfx.strokePlaneRect(cam, sliceO, M, -hw - 0.5, doorTop - 0.5, doorW + 1.0, doorSpanH + 1.0, 2.6, jambCol);
                    if (depthFrac > 0.32 && depthFrac < 0.72) {
                        gfx.strokePlaneRect(cam, sliceO, M, -hw, doorTop, doorW, doorSpanH, 1.0,
                                withAlpha(PAL_BRASS_LIGHT, 0.16 * doorK * alpha));
                    }
                }
            }
        } finally {
            if (backHoleQuad != null) gfx.endDepthClip();
        }

        // 2) Front Car 01 facade (clipped outside doorway hole when doors are opening!)
        double skirtTop = lerp(bot - 6.0, bot - 34.0, hKs);
        double skirtBot = lerp(bot - 3.5, bot, hKs);
        double beltY = CAR_WIN_Y + CAR_WIN_H * 0.5 + 13.0;

        if (frontHoleQuad != null) gfx.beginInvertedDepthClipPolygon(frontHoleQuad);
        try {
            // Base crimson lacquer
            gfx.fillPlaneRect(cam, O, M, -w * 0.5, top, w, bot - top, withAlpha(PAL_RED, alpha));

            // Cylindrical vertical metallic red lacquer shading
            if (hKs > 0.02) {
                double bodyY0 = top + roofH, bodyH = Math.max(1.0, skirtTop - bodyY0);
                double la = alpha * hKs;
                gfx.fillPlaneGradientV(cam, O, M, -w * 0.5, bodyY0, w, bodyH * 0.14,
                        withAlpha(0xA91629, la), withAlpha(0xEE2D45, la), BlendMode.NORMAL);
                gfx.fillPlaneGradientV(cam, O, M, -w * 0.5, bodyY0 + bodyH * 0.14, w, bodyH * 0.44,
                        withAlpha(0xEE2D45, la), withAlpha(0xD31C33, la), BlendMode.NORMAL);
                gfx.fillPlaneGradientV(cam, O, M, -w * 0.5, bodyY0 + bodyH * 0.58, w, bodyH * 0.10,
                        withAlpha(0xD31C33, la), withAlpha(0xE72940, la), BlendMode.NORMAL);
                gfx.fillPlaneGradientV(cam, O, M, -w * 0.5, bodyY0 + bodyH * 0.68, w, bodyH * 0.32,
                        withAlpha(0xE72940, la), withAlpha(0x890F20, la), BlendMode.NORMAL);

                // Subtle left/right end curvature vignette
                gfx.fillPlaneGradientH(cam, O, M, -w * 0.5, bodyY0, w * 0.14, bodyH,
                        withAlpha(0x12040A, 0.28 * la), withAlpha(0x12040A, 0.02 * la), BlendMode.NORMAL);
                gfx.fillPlaneGradientH(cam, O, M, w * 0.36, bodyY0, w * 0.14, bodyH,
                        withAlpha(0x12040A, 0.02 * la), withAlpha(0x12040A, 0.28 * la), BlendMode.NORMAL);
            }

            // Curved paper-white roof + crown highlight + panel ribs + brass drip rail
            if (roofH > 0.4) {
                gfx.fillPlaneGradientV(cam, O, M, -w * 0.5, top, w, roofH * 0.32,
                        withAlpha(0xE7E3DA, alpha), withAlpha(0xFCFBF7, alpha), BlendMode.NORMAL);
                gfx.fillPlaneGradientV(cam, O, M, -w * 0.5, top + roofH * 0.32, w, roofH * 0.68,
                        withAlpha(0xFCFBF7, alpha), withAlpha(0xCFCAC0, alpha), BlendMode.NORMAL);
                gfx.fillPlaneRect(cam, O, M, -w * 0.46, top + 3.2, w * 0.92, 2.4, withAlpha(0xFFFFFF, 0.72 * alpha));

                if (detailK > 0.01) {
                    double ra = alpha * detailK * 0.55;
                    for (double sx : new double[]{-330, -210, -90, 90, 210, 330}) {
                        double x = (sx / (CAR_W * 0.5)) * (w * 0.5);
                        gfx.fillPlaneRect(cam, O, M, x - 0.7, top + 1.5, 1.1, Math.max(0, roofH - 2.5), withAlpha(0x605C54, 0.34 * ra));
                        gfx.fillPlaneRect(cam, O, M, x + 0.5, top + 1.5, 0.9, Math.max(0, roofH - 2.5), withAlpha(0xFFFFFF, 0.45 * ra));
                    }
                }
                gfx.fillPlaneRect(cam, O, M, -w * 0.5, top + roofH - 1.8 * hKs, w, 1.8 * hKs,
                        withAlpha(lerpRgb(0xECE8DF, 0xD8B46E, detailK), alpha));
                gfx.fillPlaneRect(cam, O, M, -w * 0.5, top + roofH, w, 3.0 * hKs, withAlpha(PAL_INK, alpha));
            }

            // Gunmetal lower skirt
            double skH = Math.max(2.5, skirtBot - skirtTop);
            if (hKs < 0.15) {
                gfx.fillPlaneRect(cam, O, M, -w * 0.5, skirtTop, w, skH, withAlpha(PAL_INK, alpha));
            } else {
                gfx.fillPlaneGradientV(cam, O, M, -w * 0.5, skirtTop, w, skH,
                        withAlpha(0x1D202C, alpha), withAlpha(0x090A10, alpha), BlendMode.NORMAL);
            }

            // Detail pass: inspection hatches, brass skirt trim, vertical skin seams, rivets, double champagne beltlines
            if (detailK > 0.004) {
                double da = alpha * detailK;
                for (double bx : new double[]{-390, -250, 250, 390}) {
                    double x = (bx / (CAR_W * 0.5)) * (w * 0.5);
                    gfx.strokePlaneRect(cam, O, M, x - 42, skirtTop + 4, 84, 12, 1.0, withAlpha(PAL_PAPER, 0.10 * da));
                    gfx.fillPlaneRect(cam, O, M, x - 36, skirtTop + 9, 8, 1.8, withAlpha(PAL_BRASS, 0.42 * da));
                }
                gfx.fillPlaneRect(cam, O, M, -w * 0.5, skirtTop - 1.2, w, 1.2, withAlpha(0x1C040A, 0.48 * da));
                gfx.fillPlaneRect(cam, O, M, -w * 0.5, skirtTop - 3.2, w, 2.0, withAlpha(PAL_BRASS, da));
                gfx.fillPlaneRect(cam, O, M, -w * 0.5, skirtTop - 3.2, w, 0.7, withAlpha(0xFFF6DC, 0.42 * da));

                double panelTop = top + roofH + 3.0, panelH = Math.max(0.0, skirtTop - 3.2 - panelTop);
                for (double sx : new double[]{-330, -210, -90, 90, 210, 330}) {
                    double x = (sx / (CAR_W * 0.5)) * (w * 0.5);
                    if (Math.abs(x) < hw + 10.0) continue;
                    gfx.fillPlaneRect(cam, O, M, x - 0.7, panelTop, 1.1, panelH, withAlpha(0x30040C, 0.30 * da));
                    gfx.fillPlaneRect(cam, O, M, x + 0.5, panelTop, 0.8, panelH, withAlpha(0xFFE8DC, 0.16 * da));
                }

                // Symmetrical eave rivets
                double rivY = top + roofH + 9.5;
                for (double d = 116.0; d <= w * 0.5 - 20.0; d += 24.0) {
                    boolean nearSeam = false;
                    for (double sx : new double[]{90, 210, 330}) {
                        if (Math.abs(d - (sx / (CAR_W * 0.5)) * (w * 0.5)) < 5.5) { nearSeam = true; break; }
                    }
                    if (nearSeam) continue;
                    for (int side : new int[]{-1, 1}) {
                        double rxp = side * d;
                        gfx.fillPlaneRect(cam, O, M, rxp - 1.1, rivY - 1.1, 2.2, 2.2, withAlpha(0xFFEEDC, 0.40 * da));
                    }
                }

                // Double champagne gold beltlines stopping cleanly before the door surround
                double doorMargin = hw + 8.0;
                double[][] segs = {{-w * 0.5 + 6.0, -doorMargin}, {doorMargin, w * 0.5 - 6.0}};
                for (double[] sg : segs) {
                    double segW = sg[1] - sg[0];
                    if (segW <= 4.0) continue;
                    gfx.fillPlaneRect(cam, O, M, sg[0], beltY + 2.3, segW, 1.2, withAlpha(0x2C040C, 0.42 * da));
                    gfx.fillPlaneRect(cam, O, M, sg[0], beltY, segW, 2.3, withAlpha(0xCFA65A, da));
                    gfx.fillPlaneRect(cam, O, M, sg[0], beltY, segW, 0.85, withAlpha(0xFFF4D8, da));
                    gfx.fillPlaneRect(cam, O, M, sg[0] + 2, beltY + 5.2, segW - 4, 1.1, withAlpha(PAL_BRASS_LIGHT, 0.82 * da));
                    double capX = sg[0] < 0 ? sg[1] - 1.6 : sg[0];
                    gfx.fillPlaneRect(cam, O, M, capX, beltY, 1.6, 6.3, withAlpha(PAL_BRASS_LIGHT, da));
                }

                // Outer edge sheen border
                gfx.strokePlaneRect(cam, O, M, -w * 0.5 + 0.8, top + 0.8, w - 1.6, bot - top - 1.6, 1.2,
                        withAlpha(0xFFECE0, 0.16 * da));
            }

            // Factory paint diagonal specular sweep (1.62s .. 2.04s)
            double swK = sat((t - 1.62) / 0.42);
            if (swK > 0.0 && swK < 1.0) {
                double sx = lerp(-w * 0.5 - 220.0, w * 0.5 + 220.0, eInOutCubic(swK));
                double sa = Math.sin(swK * Math.PI) * 0.30 * alpha;
                double skew = -0.42 * (bot - top);
                int c0 = withAlpha(0xFFF0E2, 0.0), c1 = withAlpha(0xFFF0E2, sa);
                gfx.fillPlaneQuadColored(cam, O, M,
                        sx - 110, top, c0, sx - 110 + skew, bot, c0,
                        sx + skew, bot, c1, sx, top, c1, BlendMode.ADDITIVE);
                gfx.fillPlaneQuadColored(cam, O, M,
                        sx, top, c1, sx + skew, bot, c1,
                        sx + 110 + skew, bot, c0, sx + 110, top, c0, BlendMode.ADDITIVE);
            }
        } finally {
            if (frontHoleQuad != null) gfx.endDepthClip();
        }

        // 3) 6 Cabin Windows morphing from the 4 Apple Band windows -> 6 lit Art-Deco windows
        int[] order = {2, 1, 0, 0, 1, 2};
        for (int i = 0; i < 6; i++) {
            double xF = CAR_WIN_X[i];
            double x, wa = 1.0;
            if (i == 0 || i == 5) {
                x = (xF / (CAR_W * 0.5)) * (w * 0.5);
                wa = sstep(0.72, 1.0, wK);
            } else {
                x = lerp(BAND_WIN_X[i - 1], xF, wK);
            }
            if (wa <= 0.004) continue;
            double ww = lerp(46.0, CAR_WIN_W, wK);
            double wh = lerp(19.0, CAR_WIN_H, hKs);
            double wy = lerp(-3.5, CAR_WIN_Y, hKs);
            double lit = sat(litK * 1.7 - order[i] * 0.3);
            double a = wa * alpha;

            // Warm additive window bloom
            if (lit > 0.01) {
                double[] pw = cam.p(va(O, mv(M, new double[]{x, wy, 0})));
                if (pw != null) {
                    gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), pw[0], pw[1],
                            Math.max(ww, wh) * 0.98 * pw[2], withAlpha(0xFFD890, a * lit * 0.36), BlendMode.ADDITIVE);
                }
            }
            // Crossfade smoothly from paper-white unlit window sprite to detailed lit interior window sprite
            double drawW = ww * (100.0 / 88.0);
            double drawH = wh * (82.0 / 70.0);
            if (lit < 0.995) {
                gfx.drawPlaneSprite(cam, O, M, tunnelAtlas.carWinUnlitTex.getId(),
                        drawW * 0.5 - x, drawH * 0.5 - wy, drawW, drawH, withAlpha(0xFFFFFF, a * (1.0 - lit * 0.9)), BlendMode.NORMAL);
            }
            if (lit > 0.01) {
                gfx.drawPlaneSprite(cam, O, M, tunnelAtlas.carWinLitTex.getId(),
                        drawW * 0.5 - x, drawH * 0.5 - wy, drawW, drawH, withAlpha(0xFFFFFF, a * lit), BlendMode.NORMAL);
            }
        }

        // 4) Sliding Vestibule Double Doors + Gunmetal/Brass Door Surround + Cantilevered Welcome Step
        double doorA = sstep(0.55, 1.0, hKs) * alpha;
        if (doorA > 0.004) {
            double panelW = CAR_DOOR_W * 0.5;
            double slide = doorK * (panelW + 5.0);
            double[] clipDoorQuad = projectPlaneRect(cam, O, M, -hw, doorTop, doorW, doorSpanH);
            if (clipDoorQuad != null) {
                gfx.beginDepthClipPolygon(clipDoorQuad);
                try {
                    for (int side : new int[]{-1, 1}) {
                        double x0 = side < 0 ? (-hw - slide) : (hw - panelW + slide);
                        // Recessed door leaf red lacquer
                        gfx.fillPlaneGradientV(cam, O, M, x0, doorTop, panelW, doorSpanH * 0.62,
                                withAlpha(0xC61D33, doorA), withAlpha(0xBE1A30, doorA), BlendMode.NORMAL);
                        gfx.fillPlaneGradientV(cam, O, M, x0, doorTop + doorSpanH * 0.62, panelW, doorSpanH * 0.38,
                                withAlpha(0xBE1A30, doorA), withAlpha(0x8A1020, doorA), BlendMode.NORMAL);
                        // Kick plate + brass hairline
                        double kickH = Math.min(34.0, doorSpanH * 0.17);
                        gfx.fillPlaneGradientV(cam, O, M, x0, doorBot - kickH, panelW, kickH,
                                withAlpha(0x180A10, 0.34 * doorA), withAlpha(0x0C080E, 0.56 * doorA), BlendMode.NORMAL);
                        gfx.fillPlaneRect(cam, O, M, x0 + 2, doorBot - kickH, panelW - 4, 1.0, withAlpha(PAL_BRASS_LIGHT, 0.42 * doorA));
                        if (kickH > 18.0 && detailK > 0.02) {
                            gfx.strokePlaneRect(cam, O, M, x0 + 6.5, doorBot - kickH + 5.5, panelW - 13.0, kickH - 11.0,
                                    0.9, withAlpha(PAL_BRASS_LIGHT, 0.28 * detailK * doorA));
                        }
                        // Top rail shadow + center anti-pinch rubber gasket
                        gfx.fillPlaneRect(cam, O, M, x0, doorTop, panelW, 4.5, withAlpha(0x0A060C, 0.38 * doorA));
                        double innerEdgeX = side < 0 ? (x0 + panelW - 2.6) : x0;
                        gfx.fillPlaneRect(cam, O, M, innerEdgeX, doorTop, 2.6, doorSpanH, withAlpha(0x141620, doorA));

                        // Narrow vestibule door window
                        double dWinY = lerp(0.0, -35.0, hKs);
                        double dWinH = Math.min(92.0, doorSpanH * 0.46);
                        double dWinX = x0 + panelW * 0.5 + (side < 0 ? -1.5 : 1.5);
                        double dLit = sat(litK * 1.7);
                        double dwW = 30.0 * (42.0 / 30.0), dwH = dWinH * (104.0 / 92.0);
                        if (dLit < 0.995) {
                            gfx.drawPlaneSprite(cam, O, M, tunnelAtlas.doorWinUnlitTex.getId(),
                                    dwW * 0.5 - dWinX, dwH * 0.5 - dWinY, dwW, dwH, withAlpha(0xFFFFFF, doorA * (1.0 - dLit * 0.9)), BlendMode.NORMAL);
                        }
                        if (dLit > 0.01) {
                            gfx.drawPlaneSprite(cam, O, M, tunnelAtlas.doorWinLitTex.getId(),
                                    dwW * 0.5 - dWinX, dwH * 0.5 - dWinY, dwW, dwH, withAlpha(0xFFFFFF, doorA * dLit), BlendMode.NORMAL);
                        }

                        // Vertical brass grab handle
                        if (detailK > 0.01) {
                            double ha = doorA * detailK;
                            double hx = side < 0 ? (x0 + panelW - 10.5) : (x0 + 7.5);
                            double hhLen = Math.min(50.0, doorSpanH * 0.26);
                            double hy0 = lerp(-8.0, -14.0, hKs);
                            gfx.fillPlaneRect(cam, O, M, hx + 1.2, hy0 + 1.0, 3.2, hhLen, withAlpha(0x18040A, 0.48 * ha));
                            gfx.fillPlaneRect(cam, O, M, hx - 1.0, hy0, 5.0, 3.2, withAlpha(0x9E7838, ha));
                            gfx.fillPlaneRect(cam, O, M, hx - 1.0, hy0 + hhLen - 3.2, 5.0, 3.2, withAlpha(0x9E7838, ha));
                            gfx.fillPlaneRect(cam, O, M, hx, hy0 + 1.5, 3.0, hhLen - 3.0, withAlpha(PAL_BRASS, ha));
                            gfx.fillPlaneRect(cam, O, M, hx + 0.5, hy0 + 2.0, 1.1, hhLen - 4.0, withAlpha(0xFFF6DE, ha));
                        }
                    }

                    // Lintel & sliding pocket inner depth shadows
                    gfx.fillPlaneGradientV(cam, O, M, -hw, doorTop, doorW, 9.0,
                            withAlpha(0x08050A, 0.58 * doorA), withAlpha(0x08050A, 0.0), BlendMode.NORMAL);
                    if (doorK > 0.01) {
                        gfx.fillPlaneGradientH(cam, O, M, -hw, doorTop, 9.0, doorSpanH,
                                withAlpha(0x08040A, 0.62 * doorA), withAlpha(0x08040A, 0.0), BlendMode.NORMAL);
                        gfx.fillPlaneGradientH(cam, O, M, hw - 9.0, doorTop, 9.0, doorSpanH,
                                withAlpha(0x08040A, 0.0), withAlpha(0x08040A, 0.62 * doorA), BlendMode.NORMAL);
                    }
                } finally {
                    gfx.endDepthClip();
                }
            }

            // Gunmetal & brass door surround + cantilevered welcome threshold step
            double pilW = 5.6;
            gfx.strokePlaneRect(cam, O, M, -hw - pilW - 0.7, doorTop - 4.2, doorW + (pilW + 0.7) * 2, doorSpanH + 5.2,
                    1.4, withAlpha(0x20040A, 0.52 * doorA));
            gfx.fillPlaneRect(cam, O, M, -hw - pilW, doorTop - 3.8, pilW, doorSpanH + 4.2, withAlpha(0x171A25, doorA));
            gfx.fillPlaneRect(cam, O, M, hw, doorTop - 3.8, pilW, doorSpanH + 4.2, withAlpha(0x171A25, doorA));
            gfx.fillPlaneRect(cam, O, M, -hw - pilW, doorTop - 3.8, doorW + pilW * 2, 3.8, withAlpha(0x171A25, doorA));
            gfx.fillPlaneRect(cam, O, M, -hw - pilW + 2.0, doorTop - 1.5, 1.5, doorSpanH + 1.5, withAlpha(0xDAB46C, 0.78 * doorA));
            gfx.fillPlaneRect(cam, O, M, hw + pilW - 3.5, doorTop - 1.5, 1.5, doorSpanH + 1.5, withAlpha(0xDAB46C, 0.78 * doorA));
            gfx.strokePlaneRect(cam, O, M, -hw - 0.6, doorTop - 0.6, doorW + 1.2, doorSpanH + 1.2,
                    1.25, withAlpha(0xF4ECDC, 0.78 * doorA));

            double thX = -hw - pilW - 1.5, thW = doorW + (pilW + 1.5) * 2;
            if (doorK > 0.01) {
                gfx.fillPlaneGradientV(cam, O, M, thX + 4, doorBot + 4, thW - 8, 11.0,
                        withAlpha(0xFFD88E, 0.45 * doorK * doorA), withAlpha(0xFFD88E, 0.0), BlendMode.ADDITIVE);
            }
            gfx.fillPlaneRect(cam, O, M, thX, doorBot + 3.2, thW, 2.2, withAlpha(0x563D1A, doorA));
            gfx.fillPlaneRect(cam, O, M, thX, doorBot + 0.4, thW, 3.0, withAlpha(0xCFA458, doorA));
            gfx.fillPlaneRect(cam, O, M, thX + 1, doorBot + 0.4, thW - 2, 1.0, withAlpha(0xFFF4D6, doorA));
            for (double txp = thX + 8; txp < thX + thW - 6; txp += 8.0) {
                gfx.fillPlaneRect(cam, O, M, txp, doorBot + 1.5, 3.5, 1.2, withAlpha(0x3E280E, 0.55 * doorA));
            }
        }

        // 5) Destination LED Board + HABITRAIN Brand + CAR 01 Chamfered Enamel Plaque
        if (detailK > 0.004) {
            double da = alpha * detailK;
            double signW = 204.0, signH = 19.5;
            double sy = doorTop - 3.8 - signH - 0.8;
            gfx.fillPlaneRect(cam, O, M, -signW * 0.5 - 1.5, sy - 1.0, signW + 3.0, signH + 2.6, withAlpha(0x18040A, 0.52 * da));
            gfx.fillPlaneGradientV(cam, O, M, -signW * 0.5 - 0.8, sy - 0.6, signW + 1.6, signH + 1.2,
                    withAlpha(0xDFBE78, da), withAlpha(0x785925, da), BlendMode.NORMAL);
            gfx.fillPlaneRect(cam, O, M, -hw - 3.6, sy + signH, 4.2, 2.2, withAlpha(0x9C7738, da));
            gfx.fillPlaneRect(cam, O, M, hw - 0.6, sy + signH, 4.2, 2.2, withAlpha(0x9C7738, da));
            gfx.fillPlaneRect(cam, O, M, -signW * 0.5 + 0.6, sy + 0.6, signW - 1.2, signH - 1.2, withAlpha(0x0B0D14, da));
            gfx.fillPlaneRect(cam, O, M, -signW * 0.5 + 2.0, sy + 1.2, signW - 4.0, signH * 0.42, withAlpha(0xFFFAEE, 0.08 * da));

            // Signal red LED dot
            double dotX = -signW * 0.5 + 13.5, dotY = sy + signH * 0.5 + 0.2;
            double[] pDot = cam.p(va(O, mv(M, new double[]{dotX, dotY, 0})));
            if (pDot != null) {
                gfx.strokeCircle(pDot[0], pDot[1], Math.max(1.5, 4.1 * pDot[2]), 0.8, withAlpha(PAL_BRASS_LIGHT, 0.45 * da), 12);
                gfx.fillCircle(pDot[0], pDot[1], Math.max(1.2, 2.6 * pDot[2]), withAlpha(PAL_RED_BRIGHT, da), 12);
            }
            String destText = baseAtlas.truncateText(HabiKineticAtlas.FontFace.SANS_CN,
                    session.getRouteName() + " ▸ 星夜轨网", 11, 2.2f, signW - 34.0);
            baseAtlas.drawTextOnPlane(gfx, cam, O, M, HabiKineticAtlas.FontFace.SANS_CN, destText,
                    7.0, dotY + 0.3, 11, 2.2f, withAlpha(PAL_CHAMPAGNE, da), 0);

            // Bottom-left HABITRAIN brand block
            gfx.drawPlaneSprite(cam, O, M, tunnelAtlas.carBrandTex.getId(),
                    4.0 - (-w * 0.5 + 34.0), 14.0 - (bot - 88.0), 210.0, 64.0, withAlpha(0xFFFFFF, da), BlendMode.NORMAL);

            // Bottom-right CAR 01 enamel plaque
            gfx.drawPlaneSprite(cam, O, M, tunnelAtlas.carPlaqueTex.getId(),
                    3.0 - (w * 0.5 - 104.0), 3.0 - 19.0, 76.0, 71.0, withAlpha(0xFFFFFF, da), BlendMode.NORMAL);
        }
    }

    private void drawStartPlatform(HabiKineticGfx gfx, Cam cam, double carZ, double alpha,
                                   double doorK, double rushK, double litK, double wK) {
        double Y = CAR_FLOOR_Y;
        // Rail gap trench
        double[] gapPoly = projNearPoly(cam, cam.v(-3000, Y, 4 + carZ), cam.v(3000, Y, 4 + carZ),
                cam.v(3000, Y, -22 + carZ), cam.v(-3000, Y, -22 + carZ));
        if (gapPoly != null) gfx.fillConvexPolygonCount(withAlpha(0x04050A, alpha), BlendMode.NORMAL, gapPoly, gapPoly.length / 2);

        // Main platform floor surface (near-clipped 3-band depth gradient)
        double[] pA = projNearPoly(cam, cam.v(-3000, Y, -20 + carZ), cam.v(3000, Y, -20 + carZ),
                cam.v(3000, Y, -260 + carZ), cam.v(-3000, Y, -260 + carZ));
        if (pA != null) gfx.fillConvexPolygonCount(withAlpha(0x1C202E, alpha), BlendMode.NORMAL, pA, pA.length / 2);
        double[] pB = projNearPoly(cam, cam.v(-3000, Y, -260 + carZ), cam.v(3000, Y, -260 + carZ),
                cam.v(3000, Y, -1800 + carZ), cam.v(-3000, Y, -1800 + carZ));
        if (pB != null) gfx.fillConvexPolygonCount(withAlpha(0x0E1019, alpha), BlendMode.NORMAL, pB, pB.length / 2);

        // Granite platform curb
        double[] curb = projNearPoly(cam, cam.v(-3000, Y, -20 + carZ), cam.v(3000, Y, -20 + carZ),
                cam.v(3000, Y, -34 + carZ), cam.v(-3000, Y, -34 + carZ));
        if (curb != null) gfx.fillConvexPolygonCount(withAlpha(PAL_PAPER, 0.12 * alpha), BlendMode.NORMAL, curb, curb.length / 2);

        // Polished floor window & crimson car body reflections
        if (litK > 0.01) {
            for (double x : CAR_WIN_X) {
                fillNearGradQuad(gfx, cam,
                        cam.v(x - 36, Y, -6 + carZ), cam.v(x + 36, Y, -6 + carZ),
                        cam.v(x + 48, Y, -320 + carZ), cam.v(x - 48, Y, -320 + carZ),
                        withAlpha(0xFFC882, 0.13 * litK * alpha), withAlpha(0xFFCE8C, 0.0), BlendMode.ADDITIVE);
            }
            double half = CAR_W * 0.5;
            fillNearGradQuad(gfx, cam,
                    cam.v(-half, Y, -6 + carZ), cam.v(half, Y, -6 + carZ),
                    cam.v(half, Y, -260 + carZ), cam.v(-half, Y, -260 + carZ),
                    withAlpha(PAL_RED, 0.19 * sat(wK) * alpha), withAlpha(PAL_RED, 0.0), BlendMode.ADDITIVE);
        }

        // Tile grid hairlines
        int tileCol = withAlpha(PAL_PAPER, 0.055 * alpha);
        for (int x = -2800; x <= 2800; x += 200) {
            drawNearSeg(gfx, cam, cam.v(x, Y, -112 + carZ), cam.v(x, Y, -1800 + carZ), 1.0, tileCol);
        }
        for (double z : new double[]{-112, -260, -460, -720, -1050, -1450}) {
            drawNearSeg(gfx, cam, cam.v(-3000, Y, z + carZ), cam.v(3000, Y, z + carZ), 1.0, tileCol);
        }

        // Brass tactile paving blocks
        int tacCol = withAlpha(PAL_BRASS, 0.50 * alpha);
        for (int x = -2800; x < 2800; x += 64) {
            double[] q = projNearPoly(cam, cam.v(x, Y, -52 + carZ), cam.v(x + 38, Y, -52 + carZ),
                    cam.v(x + 38, Y, -86 + carZ), cam.v(x, Y, -86 + carZ));
            if (q != null) gfx.fillConvexPolygonCount(tacCol, BlendMode.NORMAL, q, q.length / 2);
        }

        // White safety curb line
        drawNearSeg(gfx, cam, cam.v(-3000, Y, -20 + carZ), cam.v(3000, Y, -20 + carZ), 1.3, withAlpha(PAL_PAPER, 0.52 * alpha));

        // Warm doorway welcome light spill onto platform floor
        if (doorK > 0.001) {
            double la = 0.34 * doorK * (1.0 - sstep(0.2, 0.7, rushK)) * alpha;
            if (la > 0.004) {
                double hw = CAR_DOOR_W * 0.5;
                fillNearGradQuad(gfx, cam,
                        cam.v(-hw, Y, -5 + carZ), cam.v(hw, Y, -5 + carZ),
                        cam.v(hw + 230, Y, -950 + carZ), cam.v(-hw - 230, Y, -950 + carZ),
                        withAlpha(0xFFE4AA, la), withAlpha(0xF0BE78, 0.0), BlendMode.ADDITIVE);
            }
        }
    }

    private void drawStartCanopy(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, Cam cam,
                                 double carZ, double alpha, double t) {
        double Z = -230.0 + carZ, YL = -318.0, YT = -900.0;
        for (int x = -1500; x <= 1500; x += 500) {
            double sw = 4.0 * Math.sin(t * 1.6 + x * 0.01);
            double[] lamp = cam.v(x + sw, YL, Z);
            double[] top = cam.v(x, YT, Z);
            if (lamp[2] < -FOCAL + 80.0) continue;
            double[] pl = cam.p(lamp), pt = cam.p(top);
            if (pl == null || pt == null) continue;
            double s = pl[2];

            // Hanging wire
            gfx.drawLine(pt[0], pt[1], pl[0], pl[1], 1.0, withAlpha(PAL_PAPER, 0.35 * 0.55 * alpha));

            // Warm downward light cone
            double coneH = 300.0 * s, coneW = 150.0 * s;
            int coneTop = withAlpha(0xFFDEA0, 0.11 * alpha), coneBot = withAlpha(0xFFDEA0, 0.0);
            gfx.fillQuadColored(pl[0] - 14 * s, pl[1], coneTop,
                    pl[0] - coneW, pl[1] + coneH, coneBot,
                    pl[0] + coneW, pl[1] + coneH, coneBot,
                    pl[0] + 14 * s, pl[1], coneTop, BlendMode.ADDITIVE);

            // Warm bulb glow
            gfx.drawGlow(tunnelAtlas.hardGlowTex.getId(), pl[0], pl[1] + 6.0 * s, 46.0 * s,
                    withAlpha(0xFFECC4, 0.75 * alpha), BlendMode.ADDITIVE);

            // Brass lampshade trapezoid + warm emitter lip
            gfx.fillQuad(pl[0] - 5 * s, pl[1] - 10 * s, pl[0] - 16 * s, pl[1] + 2 * s,
                    pl[0] + 16 * s, pl[1] + 2 * s, pl[0] + 5 * s, pl[1] - 10 * s, withAlpha(PAL_BRASS, alpha));
            gfx.fillRect(pl[0] - 9 * s, pl[1] + 2 * s, 18 * s, 2.2 * s, withAlpha(0xFFF4DC, alpha));
        }

        // Illuminated floating dust motes under lamp cones
        for (DustPt d : startDust) {
            double x = d.x + 9.0 * Math.sin(t * 0.7 + d.ph);
            double y = d.y + ((t * d.sp + d.ph * 30.0) % 120.0) - 60.0;
            double near = Math.abs((((x % 500.0) + 750.0) % 500.0) - 250.0);
            double lit = 1.0 - sstep(40.0, 150.0, near + Math.max(0.0, -y - 200.0) * 0.4);
            if (lit <= 0.02) continue;
            double[] p = cam.p(cam.v(x, y, Z + d.z));
            if (p == null || p[2] > 3.2) continue;
            double da = alpha * lit * (0.35 + 0.35 * Math.sin(t * 2.3 + d.ph));
            gfx.fillCircle(p[0], p[1], Math.max(0.8, d.r * p[2]), withAlpha(0xFFE9C4, da), 8, BlendMode.ADDITIVE);
        }
    }

    private void drawStartEdgeLine(HabiKineticGfx gfx, Cam cam, double carZ, double t, double beatPulse, double fm) {
        double lineK = eInOutCubic(sat((t - 0.40) / 0.90));
        double ang = -0.24, ux = Math.cos(ang), uy = Math.sin(ang);
        double cx = CX + 220.0, cy = CY + 60.0;
        double ax0 = cx - ux * 2400.0, ay0 = cy - uy * 2400.0;
        double bx0 = cx + ux * 2400.0, by0 = cy + uy * 2400.0;
        double[][] clipped = clipNearSeg(cam.v(-3400, CAR_FLOOR_Y, -30 + carZ), cam.v(3400, CAR_FLOOR_Y, -30 + carZ));
        double[] pa = clipped != null ? cam.p(clipped[0]) : null;
        double[] pb = clipped != null ? cam.p(clipped[1]) : null;
        double ax = lerp(ax0, pa != null ? pa[0] : ax0, lineK);
        double ay = lerp(ay0, pa != null ? pa[1] : ay0, lineK);
        double bx = lerp(bx0, pb != null ? pb[0] : bx0, lineK);
        double by = lerp(by0, pb != null ? pb[1] : by0, lineK);

        double parA = 0.45 * (1.0 - sstep(0.0, 0.40, t)) * fm;
        if (parA > 0.004) {
            double nx = -uy, ny = ux;
            int guideCol = withAlpha(PAL_PAPER, 0.28 * parA);
            for (double off : new double[]{28.0, 44.0, -180.0}) {
                gfx.drawLine(ax0 + nx * off, ay0 + ny * off, bx0 + nx * off, by0 + ny * off, 1.0, guideCol);
            }
        }
        if (lineK > 0.02) {
            gfx.drawLine(ax, ay, bx, by, 9.0, withAlpha(PAL_RED_BRIGHT, 0.20 * lineK * fm), BlendMode.ADDITIVE);
        }
        gfx.drawLine(ax, ay, bx, by, lerp(1.6 + beatPulse * 1.1, 2.4, lineK),
                withAlpha(PAL_RED, lerp(0.65, 0.92, lineK) * fm));
    }

    private void drawRetreatingStationPlates(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, Cam cam, double u, double t) {
        double kOut = eInCubic(sat(t / 0.48));
        double alpha = 1.0 - sstep(0.16, 0.50, t);
        if (alpha <= 0.005) return;
        HabiKineticAtlas.Slab[] slabs = {baseAtlas.plateStation, baseAtlas.plateRoute};
        double[][] ps = {{HUB_X + 385, -245, 70}, {HUB_X + 345, 245, 110}};
        double[][] rs = {{0.03, -0.28, 0.03}, {-0.02, -0.24, -0.02}};
        double[] ds = {10.0, 8.0};
        for (int idx = 0; idx < 2; idx++) {
            double[] pos = {
                    ps[idx][0] + 540.0 * kOut,
                    ps[idx][1] + 8.0 * Math.sin(u * 0.8 + idx * 1.9) - 90.0 * kOut,
                    ps[idx][2] - 280.0 * kOut
            };
            double[] R = euler(rs[idx][0], rs[idx][1] - 0.7 * kOut, rs[idx][2]);
            gfx.drawSlab(cam, cam.v(pos), mm(cam.R, R), slabs[idx], ds[idx], alpha, 0.0, false, false);
        }
    }

    private void drawBandFlare(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, Cam cam, double carZ, double t) {
        double k = sstep(0.60, 0.95, t) * (1.0 - sstep(1.18, 1.58, t));
        if (k <= 0.004) return;
        double[] p = cam.p(cam.v(0, 0, carZ));
        if (p == null) return;
        double stretch = sstep(1.05, 1.50, t);
        double len = (380.0 + 520.0 * k + 700.0 * stretch) * p[2];

        int c0 = withAlpha(PAL_RED_BRIGHT, 0.0);
        int cMid = withAlpha(PAL_RED_BRIGHT, 0.22 * k);
        int cCtr = withAlpha(0xFFF4E4, 0.55 * k);
        gfx.fillGradientRectH(p[0] - len, p[1] - 1.2, len * 0.60, 2.4, c0, cMid, BlendMode.ADDITIVE);
        gfx.fillGradientRectH(p[0] - len * 0.40, p[1] - 1.2, len * 0.40, 2.4, cMid, cCtr, BlendMode.ADDITIVE);
        gfx.fillGradientRectH(p[0], p[1] - 1.2, len * 0.40, 2.4, cCtr, cMid, BlendMode.ADDITIVE);
        gfx.fillGradientRectH(p[0] + len * 0.40, p[1] - 1.2, len * 0.60, 2.4, cMid, c0, BlendMode.ADDITIVE);

        gfx.drawGlowEllipse(tunnelAtlas.anamorphicTex.getId(), p[0], p[1], len, 14.0,
                withAlpha(0xFFF0DC, 0.35 * k), BlendMode.ADDITIVE);
        gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), p[0], p[1], 210.0 * p[2],
                withAlpha(0xFFECDC, 0.20 * k), BlendMode.ADDITIVE);
    }

    private void drawStartRushFx(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, double cx, double cy,
                                 double t, double rushK, double doorK, @Nullable double[] quad, boolean covered) {
        if (doorK > 0.001) {
            double a = 0.26 * doorK * (1.0 - sstep(0.3, 0.95, rushK));
            if (a > 0.004) {
                double rr = 380.0 + 1100.0 * rushK;
                gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), cx, cy, rr, withAlpha(0xFFF0D4, a), BlendMode.ADDITIVE);
            }
        }
        double dt = t - HabiWorldEntrySession.BOARD_AT;
        if (dt > 0.0) {
            double f = 0.42 * Math.exp(-dt * 6.5);
            if (f > 0.004) {
                gfx.drawGlow(tunnelAtlas.hardGlowTex.getId(), cx, cy, 900.0, withAlpha(0xFFFCF4, f), BlendMode.ADDITIVE);
                gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), cx, cy, 650.0, withAlpha(PAL_RED_BRIGHT, f * 0.25), BlendMode.ADDITIVE);
            }
        }
        double sl = Math.sin(sat(rushK / 0.94) * Math.PI);
        if (sl > 0.01 && (covered || quad != null)) {
            boolean clipped = !covered && quad != null;
            if (clipped) gfx.beginDepthClipPolygon(quad);
            try {
                for (int i = 0; i < 52; i++) {
                    double h1 = hash(i * 3.1 + 1), h2 = hash(i * 7.7 + 2), h3 = hash(i * 1.3 + 5);
                    double ang = (i / 52.0) * TAU + h1 * 0.11;
                    double r0 = 150.0 + h2 * 340.0 + 1180.0 * rushK * (0.55 + h3);
                    double len = (90.0 + 360.0 * h3) * (0.4 + 1.5 * rushK);
                    double ca = Math.cos(ang), sa = Math.sin(ang);
                    int col = (i % 6 == 0)
                            ? withAlpha(PAL_RED_BRIGHT, 0.55 * sl)
                            : withAlpha(0xFFEED2, (0.18 + 0.26 * h2) * sl);
                    gfx.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * (r0 + len), cy + sa * (r0 + len),
                            0.8 + 1.8 * h1, col, BlendMode.ADDITIVE);
                }
            } finally {
                if (clipped) gfx.endDepthClip();
            }
        }
    }

    private void drawStartCinemaGrade(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas,
                                      double t, double rushK, double frameFade, boolean barsOnly) {
        double L = gfx.viewLeft(), T = gfx.viewTop(), Wv = gfx.viewWidth(), Hv = gfx.viewHeight(), B = T + Hv;
        if (!barsOnly) {
            double vigK = sstep(0.05, 0.70, t) * frameFade;
            if (vigK > 0.004) {
                gfx.drawTexturedRect(tunnelAtlas.startVigTex.getId(), L - 20, T - 20, Wv + 40, Hv + 40,
                        withAlpha(0xFFFFFF, vigK), BlendMode.NORMAL);
                gfx.fillGradientRectV(L - 20, T - 20, Wv + 40, Hv * 0.45 + 20,
                        withAlpha(0x283A78, 0.10 * vigK), withAlpha(0x283A78, 0.0), BlendMode.ADDITIVE);
                double warmK = vigK * sstep(1.20, 1.80, t);
                if (warmK > 0.004) {
                    gfx.fillGradientRectV(L - 20, T + Hv * 0.55, Wv + 40, Hv * 0.45 + 20,
                            withAlpha(0x783C1E, 0.0), withAlpha(0x783C1E, 0.10 * warmK), BlendMode.ADDITIVE);
                }
            }
        }
        double barK = eInOutCubic(sat((t - 0.10) / 0.52)) * (1.0 - eInOutCubic(sat((rushK - 0.45) / 0.55)));
        if (barK > 0.004) {
            double bh = 34.0 * barK;
            gfx.fillRect(L - 20, T - 20, Wv + 40, bh + 20, 0xFF020307);
            gfx.fillRect(L - 20, B - bh, Wv + 40, bh + 20, 0xFF020307);
            int lineC = withAlpha(PAL_BRASS, 0.28 * barK);
            gfx.fillRect(L, T + bh, Wv, 1.0, lineC);
            gfx.fillRect(L, B - bh - 1.0, Wv, 1.0, lineC);
        }
    }

    private void drawStationHudFade(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas,
                                    double alpha, @Nullable HabiBootAudioEngine.Telemetry tel, double u) {
        double a = alpha * 0.9;
        double L = gfx.viewLeft(), T = gfx.viewTop(), R = gfx.viewRight(), B = gfx.viewBottom();
        drawCornerBrackets(gfx, L, T, R, B, 34.0, 22.0, 1.5, withAlpha(PAL_PAPER, 0.28 * a));

        double bx = R - 64.0;
        int beatIdx = tel != null ? tel.beatIndex() : 0;
        for (int i = 0; i < 16; i++) {
            double v = tel != null ? tel.bar(i) : 0.25;
            double bh = 4.0 + v * 22.0;
            boolean isBeat = (beatIdx % 8) == (i / 2);
            int col = isBeat ? withAlpha(PAL_RED_BRIGHT, a)
                    : (i < 4 ? withAlpha(PAL_RED, a) : withAlpha(PAL_PAPER, 0.32 * a));
            gfx.fillRect(bx - (15 - i) * 8.0, T + 56.0 - bh, 3.0, bh, col);
        }
        String bgmStr = String.format(Locale.ROOT, "BGM // STATION 01 // %d BPM // BEAT 0%d",
                tel != null ? tel.bpm() : 100, beatIdx + 1);
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, bgmStr,
                bx, T + 76.0, 11.0, 3.0, withAlpha(0x727687, a), 1, 0.0);

        String clock = String.format(Locale.ROOT, "ONLINE %02d:%02d.%02d   //   STATION 01   //   GAUGE 1435",
                (int) (u / 60.0), (int) (u % 60.0), (int) ((u % 1.0) * 100.0));
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, clock,
                R - 54.0, B - 48.0, 11.0, 3.0, withAlpha(PAL_PAPER, a), 1, 0.0);

        drawRightVerticalStamp(gfx, baseAtlas, R, T, "第一站", withAlpha(0x727687, a),
                withAlpha(PAL_RED_BRIGHT, a), withAlpha(PAL_RED_BRIGHT, a));
    }

    private void renderStartHud(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiWorldEntrySession session, double t) {
        double inA = sstep(0.36, 0.62, t) * (1.0 - sstep(2.48, 2.84, t));
        if (inA <= 0.004) return;
        double L = gfx.viewLeft(), T = gfx.viewTop(), R = gfx.viewRight(), B = gfx.viewBottom();

        gfx.fillGradientRectV(L - 10, T - 10, gfx.viewWidth() + 20, 125.0,
                withAlpha(0x04060C, 0.55 * inA), withAlpha(0x04060C, 0.0), BlendMode.NORMAL);
        gfx.fillGradientRectV(L - 10, B - 120.0, gfx.viewWidth() + 20, 130.0,
                withAlpha(0x04060C, 0.0), withAlpha(0x04060C, 0.50 * inA), BlendMode.NORMAL);

        drawCornerBrackets(gfx, L, T, R, B, 38.0, 18.0, 1.0, withAlpha(PAL_BRASS_LIGHT, 0.42 * 0.88 * inA));

        int phase = t < START_STEM ? 0 : t < START_MORPH ? 1 : t < HabiWorldEntrySession.DOORS_AT ? 2
                : t < HabiWorldEntrySession.BOARD_AT ? 3 : 4;
        double dx = L + 58.0, dy = T + 52.0;
        int pipCol = withAlpha(phase >= 4 ? PAL_CHAMPAGNE : (phase >= 2 ? PAL_BRASS_LIGHT : PAL_RED_BRIGHT), inA * 0.88);
        gfx.fillDiamond(dx, dy, 3.5, 3.5, pipCol, BlendMode.NORMAL);

        int seamN = 0;
        for (SeamDef sm : START_SEAMS) if (t >= sm.t) seamN++;
        int asmPct = (int) Math.round(100.0 * sat((t - START_MORPH) / (HabiWorldEntrySession.DOORS_AT - START_MORPH)));
        String status = switch (phase) {
            case 0 -> "SIGNAL LOCK";
            case 1 -> "SEAM SPLIT  0" + Math.max(1, seamN) + "/05";
            case 2 -> String.format(Locale.ROOT, "CAR 01 ASSEMBLY  %3d%%", asmPct);
            case 3 -> "DOORS OPENING";
            default -> "BOARDING  //  CHRONO RAIL";
        };
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "02  //  BOARDING SEQUENCE  //  " + status,
                L + 72.0, dy, 10.0, 3.2, withAlpha(0xEEEAE2, 0.82 * 0.88 * inA), -1, 0.0);

        for (int i = 0; i < 5; i++) {
            int tc = i < phase ? withAlpha(PAL_BRASS_LIGHT, 0.75 * inA)
                    : (i == phase ? withAlpha(PAL_RED_BRIGHT, inA) : withAlpha(0xEEEAE2, 0.16 * inA));
            gfx.fillRect(L + 72.0 + i * 30.0, dy + 14.0, 24.0, 2.0, tc);
        }

        String zh = switch (phase) {
            case 0 -> session.getRouteName() + "   ·   信标锁定";
            case 1 -> "苹果沿切缝自上而下分离";
            case 2 -> "车窗带展开   ·   装配一号车厢";
            case 3 -> "车门开启   ·   请上车";
            default -> "列车发车   ·   驶入星夜轨网";
        };
        baseAtlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, zh,
                (L + R) * 0.5, B - 50.0, 12, 4.5f,
                withAlpha(phase >= 3 ? PAL_CHAMPAGNE : 0xEEEAE2, (phase >= 3 ? 1.0 : 0.86) * inA), 0, 0.0, 0);

        drawRightVerticalStamp(gfx, baseAtlas, R, T, phase >= 4 ? "驶入轨网" : "开门登车",
                withAlpha(0xEEEAE2, 0.44 * 0.78 * inA),
                withAlpha(phase >= 4 ? PAL_BRASS_LIGHT : PAL_RED_BRIGHT, 0.78 * inA),
                withAlpha(PAL_BRASS_LIGHT, 0.48 * 0.78 * inA));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ACT 2: TIME TUNNEL (Starry Chrono Rail)
    // ════════════════════════════════════════════════════════════════════════

    private void renderTimeTunnel(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas,
                                  HabiWorldEntrySession session, double masterAlpha, double endSurge,
                                  double px, double py, double beatPulse, @Nullable Double eruptBoost) {
        if (masterAlpha <= 0.004) return;
        double u = session.getTotalT();
        double travel = session.getTunnelClock();
        double blastStraighten = eruptBoost != null ? (1.0 - eInOutCubic(sat(eruptBoost))) : 0.0;
        double straighten = sat(Math.max(endSurge * 1.4, blastStraighten));

        double rx = 0.020 * Math.sin(u * 0.36) * (1.0 - straighten) - py * 0.040;
        double ry = 0.034 * Math.cos(u * 0.31) * (1.0 - straighten) + px * 0.056;
        double rz = 0.032 * Math.sin(u * 0.27) * (1.0 - straighten) + endSurge * 0.05;
        double zoom = 1.0 + 0.008 * beatPulse + 0.26 * eInCubic(endSurge);
        Cam cam = new Cam(rx, ry, rz, zoom);

        double[] vpSpine = spine(3200.0, u, px, py, straighten);
        double[] vpProj = cam.p(cam.v(vpSpine[0], vpSpine[1], 3200.0));
        double vpX = vpProj != null ? vpProj[0] : CX;
        double vpY = vpProj != null ? vpProj[1] : CY;

        // 1) Deep royal-navy velvet sky + dual drifting nebulae + 5 dust clouds + anamorphic gold ribbon + portal halo
        gfx.fillRect(gfx.viewLeft() - 20, gfx.viewTop() - 20, gfx.viewWidth() + 40, gfx.viewHeight() + 40,
                withAlpha(0x03050C, masterAlpha));
        gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), vpX, vpY, 1380.0,
                withAlpha(endSurge > 0.2 ? 0x261326 : 0x151930, 0.92 * masterAlpha), BlendMode.ADDITIVE);

        // Two drifting nebula clouds (indigo + rose crimson)
        double n1x = vpX - 240.0 + Math.sin(u * 0.22) * 45.0;
        double n1y = vpY - 120.0 + Math.cos(u * 0.19) * 30.0;
        gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), n1x, n1y, 620.0,
                withAlpha(0x3E5CB8, 0.095 * masterAlpha), BlendMode.ADDITIVE);
        double n2x = vpX + 230.0 + Math.cos(u * 0.20) * 45.0;
        double n2y = vpY + 105.0 + Math.sin(u * 0.24) * 30.0;
        gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), n2x, n2y, 580.0,
                withAlpha(0xBA2A48, 0.085 * masterAlpha), BlendMode.ADDITIVE);

        // 5 oncoming volumetric nebula dust clouds
        int[] dustCols = {0x6280E6, 0xD64668, 0xECC88C, 0x7896FF, 0xC83C60};
        for (int k = 0; k < 5; k++) {
            double ph = (u * 0.085 + k / 5.0 + travel * 0.000018) % 1.0;
            double sc = 0.25 + ph * ph * 3.2;
            double ang = k * 2.39 + 0.6;
            double dist = 260.0 * sc * (0.35 + ph);
            double dx = vpX + Math.cos(ang) * dist;
            double dy = vpY + Math.sin(ang) * dist * 0.72;
            double rr = 230.0 * sc;
            double da = Math.sin(ph * Math.PI) * 0.055 * masterAlpha;
            gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), dx, dy, rr, withAlpha(dustCols[k], da), BlendMode.ADDITIVE);
        }

        // Thin horizontal anamorphic golden ribbon
        double anamR = 490.0 + 210.0 * endSurge;
        gfx.drawGlowEllipse(tunnelAtlas.anamorphicTex.getId(), vpX, vpY, anamR, anamR * 0.034,
                withAlpha(0xFFF6E2, (0.30 + 0.18 * endSurge) * masterAlpha), BlendMode.ADDITIVE);

        // Distant star-gate soft halo
        double coreR = (235.0 + 28.0 * beatPulse) * (1.0 + 1.3 * endSurge);
        gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), vpX, vpY, coreR,
                withAlpha(PAL_BRASS, (0.14 + 0.08 * session.getProgress()) * masterAlpha), BlendMode.ADDITIVE);
        gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), vpX, vpY, coreR * 0.68,
                withAlpha(0xD9263E, (0.18 + 0.14 * beatPulse) * masterAlpha), BlendMode.ADDITIVE);
        gfx.drawGlow(tunnelAtlas.hardGlowTex.getId(), vpX, vpY, coreR * 0.42,
                withAlpha(0xFFF6E2, (0.34 + 0.28 * endSurge) * masterAlpha), BlendMode.ADDITIVE);

        // 2) Astrolabe skeleton (6 meridians + 14 astronomical clockwork rings)
        drawTunnelArchitecture(gfx, cam, u, travel, px, py, straighten, masterAlpha, endSurge, beatPulse);

        // 3) Single active preset 3D rail track & 3-car train state
        RailTransitState railState = computeRailTransitState(u, endSurge, eruptBoost);
        TrackSample[] sampledTrack = railState != null
                ? samplePresetTrack(railState.preset, u, px, py, straighten, 72) : null;
        double portalPulse = 0.0;
        double portalEntryAng = 0.0;
        if (railState != null && sampledTrack != null && railState.portalPulse > 0.005) {
            TrackSample entry = sampledTrack[(int) (sampledTrack.length * 0.84)];
            double[] pEntry = cam.p(cam.v(entry.fr.pos));
            portalPulse = railState.portalPulse;
            if (pEntry != null) portalEntryAng = Math.atan2(pEntry[1] - vpY, pEntry[0] - vpX);
        }

        // 4) Distant vanishing portal dial
        drawVanishingPortalDial(gfx, tunnelAtlas, session, vpX, vpY, u, masterAlpha, endSurge, beatPulse, portalPulse, portalEntryAng);

        // 5) Active preset 1435mm elevated twin-rail track
        if (railState != null && sampledTrack != null) {
            drawActivePresetTrack(gfx, tunnelAtlas, cam, railState, sampledTrack, u, masterAlpha);
        }

        // 6) Active 3-car red HABITRAIN locomotive + curved 8-segment golden headlight beam + slipstream
        if (railState != null && railState.trainActive && railState.trainAlpha > 0.008) {
            drawTunnelTrain(gfx, baseAtlas, tunnelAtlas, cam, railState, u, px, py, straighten, masterAlpha);
        }

        // 7) 76 streak stars + 8 flowing wind wisps
        drawTunnelStars(gfx, cam, u, travel, px, py, straighten, masterAlpha, endSurge, beatPulse);

        // 8) Sparse free-floating 3D flexing/curling perforated stamps & boarding passes (1-2 on screen)
        drawTunnelStampsAndTickets(gfx, tunnelAtlas, cam, u, travel, px, py, straighten, masterAlpha, endSurge, eruptBoost, beatPulse);

        // 9) Cinema tunnel vignette
        gfx.drawTexturedRect(tunnelAtlas.tunnelVigTex.getId(),
                gfx.viewLeft() - 20, gfx.viewTop() - 20, gfx.viewWidth() + 40, gfx.viewHeight() + 40,
                withAlpha(0xFFFFFF, masterAlpha * 0.72), BlendMode.NORMAL);
    }

    private void drawTunnelArchitecture(HabiKineticGfx gfx, Cam cam, double u, double travel, double px, double py,
                                        double straighten, double masterAlpha, double endSurge, double beatPulse) {
        final int RING_COUNT = 14;
        final double Z_MIN = -1050.0, Z_MAX = 3550.0, Z_SPAN = Z_MAX - Z_MIN;
        final double STEP = Z_SPAN / RING_COUNT;
        final double R_BASE = 795.0;

        // A) 6 thin champagne-brass & platinum meridians
        double[] meridianAngles = {-0.28, -1.05, -2.09, -2.86, 2.52, 0.62};
        for (int ri = 0; ri < meridianAngles.length; ri++) {
            boolean isBrass = (ri % 2 == 0);
            int col = isBrass ? withAlpha(PAL_BRASS, 0.24 * masterAlpha) : withAlpha(0xEEEAE2, 0.12 * masterAlpha);
            double lw = isBrass ? 0.95 : 0.75;
            int pn = 0;
            for (int s = 0; s <= 28; s++) {
                double z = lerp(Z_MAX, Z_MIN + 180.0, s / 28.0);
                double[] sp = spine(z, u, px, py, straighten);
                double tw = meridianAngles[ri] + wallTwist(z, u);
                double[] p = cam.p(cam.v(sp[0] + Math.cos(tw) * R_BASE, sp[1] + Math.sin(tw) * R_BASE, z));
                if (p == null) {
                    if (pn >= 2) gfx.drawPolyline(polyScratch, pn, lw, col, false);
                    pn = 0;
                } else {
                    polyScratch[pn * 2] = p[0];
                    polyScratch[pn * 2 + 1] = p[1];
                    pn++;
                }
            }
            if (pn >= 2) gfx.drawPolyline(polyScratch, pn, lw, col, false);
        }

        // B) 14 astronomical clockwork astrolabe rings (sorted back to front)
        record RingDef(double z, double sx, double sy, double fog, int ringId) {}
        List<RingDef> rings = new ArrayList<>(RING_COUNT);
        for (int i = 0; i < RING_COUNT; i++) {
            double raw = (((i * STEP - (travel % Z_SPAN)) % Z_SPAN) + Z_SPAN) % Z_SPAN;
            double z = Z_MIN + raw;
            double fog = sstep(Z_MAX, 2150.0, z) * sstep(Z_MIN, -580.0, z);
            if (fog <= 0.005) continue;
            double[] sp = spine(z, u, px, py, straighten);
            int ringId = (int) Math.floor((travel + z - Z_MIN) / STEP);
            rings.add(new RingDef(z, sp[0], sp[1], fog, ringId));
        }
        rings.sort((a, b) -> Double.compare(b.z, a.z));

        for (RingDef rg : rings) {
            double z = rg.z;
            double tw = wallTwist(z, u);
            int kind = Math.floorMod(rg.ringId, 4);
            double rad = R_BASE * (1.0 + 0.08 * endSurge);
            double a = masterAlpha * rg.fog * lerp(0.42, 1.0, sstep(-700.0, 1500.0, z));
            double[] pc = cam.p(cam.v(rg.sx, rg.sy, z));
            double wS = pc != null ? clamp(Math.sqrt(pc[2]), 0.7, 1.7) : 1.0;

            int pn = 0;
            for (int k = 0; k <= 56; k++) {
                double ang = tw + (k / 56.0) * TAU;
                double[] p = cam.p(cam.v(rg.sx + Math.cos(ang) * rad, rg.sy + Math.sin(ang) * rad, z));
                if (p != null) { polyScratch[pn * 2] = p[0]; polyScratch[pn * 2 + 1] = p[1]; pn++; }
            }
            int ringCol = kind == 0 ? withAlpha(PAL_BRASS_LIGHT, 0.36 * a) : withAlpha(0xEEEAE2, 0.14 * a);
            gfx.drawPolyline(polyScratch, pn, (kind == 0 ? 1.3 : 0.85) * wS, ringCol, false);

            if (kind == 0) {
                int sn = 0;
                for (int ti = 0; ti < 24; ti++) {
                    double ang = tw + (ti / 24.0) * TAU;
                    double len = (ti % 6 == 0) ? 24.0 : 10.0;
                    double[] a3 = cam.v(rg.sx + Math.cos(ang) * (rad - 4), rg.sy + Math.sin(ang) * (rad - 4), z);
                    double[] b3 = cam.v(rg.sx + Math.cos(ang) * (rad + len), rg.sy + Math.sin(ang) * (rad + len), z);
                    sn = pushSeg(cam, a3[0], a3[1], a3[2], b3[0], b3[1], b3[2], sn);
                }
                gfx.drawSegments(segScratch, sn, 0.95, withAlpha(PAL_BRASS_LIGHT, 0.38 * 0.85 * a));

                double arcA = -tw * 1.4 + rg.ringId * 0.85;
                pn = 0;
                for (int k = 0; k <= 12; k++) {
                    double ang = arcA + (k / 12.0) * 0.46;
                    double[] p = cam.p(cam.v(rg.sx + Math.cos(ang) * (rad + 13), rg.sy + Math.sin(ang) * (rad + 13), z));
                    if (p != null) { polyScratch[pn * 2] = p[0]; polyScratch[pn * 2 + 1] = p[1]; pn++; }
                }
                gfx.drawPolyline(polyScratch, pn, 2.0 + beatPulse * 0.7, withAlpha(PAL_RED_BRIGHT, 0.68 * a), false);

                for (int d = 0; d < 4; d++) {
                    double ang = tw + (d * Math.PI) * 0.5;
                    double[] p = cam.p(cam.v(rg.sx + Math.cos(ang) * rad, rg.sy + Math.sin(ang) * rad, z));
                    if (p == null) continue;
                    double ds = Math.max(1.8, 4.6 * p[2]);
                    gfx.fillDiamond(p[0], p[1], ds, ds, withAlpha(d == 0 ? PAL_RED_BRIGHT : PAL_BRASS_LIGHT, 0.86 * a), BlendMode.NORMAL);
                }
            } else if (kind == 2) {
                for (double sideBase : new double[]{-0.35, Math.PI - 0.45}) {
                    pn = 0;
                    for (int k = 0; k <= 12; k++) {
                        double ang = tw + sideBase + (k / 12.0) * 0.8;
                        double[] p = cam.p(cam.v(rg.sx + Math.cos(ang) * (rad - 14), rg.sy + Math.sin(ang) * (rad - 14), z));
                        if (p != null) { polyScratch[pn * 2] = p[0]; polyScratch[pn * 2 + 1] = p[1]; pn++; }
                    }
                    gfx.drawPolyline(polyScratch, pn, 1.0, withAlpha(PAL_BRASS, 0.28 * 0.78 * a), false);
                }
            }
        }
    }

    private void drawVanishingPortalDial(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, HabiWorldEntrySession session,
                                         double vpX, double vpY, double u, double masterAlpha, double endSurge,
                                         double beatPulse, double pPulse, double entryAng) {
        double r = 72.0 * (1.0 + 0.08 * beatPulse + 0.045 * pPulse) * (1.0 + 2.4 * eInCubic(endSurge));
        double a = masterAlpha * (1.0 - sstep(0.45, 0.85, endSurge));
        if (a <= 0.01) return;

        // Wormhole abyss core
        gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), vpX, vpY, r * 0.58, withAlpha(0x040610, 0.84 * a), BlendMode.NORMAL);

        // Directional horizon ripple when the red train dives into the portal
        if (pPulse > 0.01) {
            double rippleR = r * lerp(0.76, 1.72, pPulse);
            gfx.strokeArc(vpX, vpY, rippleR, 0.0, TAU, lerp(2.2, 0.7, pPulse),
                    withAlpha(PAL_BRASS_LIGHT, a * Math.sin(pPulse * Math.PI) * 0.44), 40, BlendMode.ADDITIVE);
            double arcSpread = lerp(0.55, 1.15, pPulse);
            gfx.strokeArc(vpX, vpY, r * 0.64, entryAng - arcSpread * 0.5, entryAng + arcSpread * 0.5,
                    2.6, withAlpha(PAL_CHAMPAGNE, a * pPulse * 0.68), 18, BlendMode.ADDITIVE);
            double fx = vpX + Math.cos(entryAng) * r * 0.54;
            double fy = vpY + Math.sin(entryAng) * r * 0.54;
            gfx.drawGlow(tunnelAtlas.hardGlowTex.getId(), fx, fy, r * 0.55,
                    withAlpha(0xFFF6E0, 0.55 * pPulse * a), BlendMode.ADDITIVE);
            gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), fx, fy, r * 0.55,
                    withAlpha(PAL_RED, 0.28 * pPulse * a), BlendMode.ADDITIVE);
        }

        // Outer ring & counter-rotating dashed inner escapement ring
        gfx.strokeCircle(vpX, vpY, r, 1.0, withAlpha(0xEEEAE2, 0.22 * a), 48);
        int dashCol = withAlpha(PAL_BRASS_LIGHT, 0.32 * a);
        for (int i = 0; i < 24; i++) {
            double a0 = -u * 0.28 + (i / 24.0) * TAU;
            gfx.strokeArc(vpX, vpY, r - 9.0, a0, a0 + 0.11, 0.9, dashCol, 3, BlendMode.NORMAL);
        }

        // 4 cardinal ticks
        int cardCol = withAlpha(PAL_BRASS_LIGHT, 0.34 * a);
        for (int i = 0; i < 4; i++) {
            double ang = i * Math.PI * 0.5;
            gfx.drawLine(vpX + Math.cos(ang) * (r + 11), vpY + Math.sin(ang) * (r + 11),
                    vpX + Math.cos(ang) * (r + 23), vpY + Math.sin(ang) * (r + 23), 0.85, cardCol);
        }

        // Real-progress circular arc + champagne tip
        double lp = sat(session.getProgress());
        if (lp > 0.005) {
            int arcCol = withAlpha(lp >= 0.995 ? PAL_BRASS_LIGHT : PAL_RED_BRIGHT, 0.94 * a);
            gfx.strokeArc(vpX, vpY, r + 4.8, -Math.PI * 0.5, -Math.PI * 0.5 + lp * TAU, 2.2, arcCol, 48, BlendMode.NORMAL);
            if (lp < 0.995) {
                double tipA = -Math.PI * 0.5 + lp * TAU;
                gfx.fillCircle(vpX + Math.cos(tipA) * (r + 4.8), vpY + Math.sin(tipA) * (r + 4.8),
                        2.5, withAlpha(PAL_CHAMPAGNE, a), 10);
            }
        }

        // 12 rotating clock ticks
        int tickCol = withAlpha(PAL_BRASS_LIGHT, 0.48 * 0.68 * a);
        for (int i = 0; i < 12; i++) {
            double ang = u * 0.38 + (i / 12.0) * TAU;
            gfx.drawLine(vpX + Math.cos(ang) * (r - 12), vpY + Math.sin(ang) * (r - 12),
                    vpX + Math.cos(ang) * (r - 17), vpY + Math.sin(ang) * (r - 17), 0.9, tickCol);
        }

        // Miniature pulsing apple emblem
        double ms = 0.145 * (1.0 + 0.05 * Math.sin(u * 3.2) + 0.08 * pPulse);
        double rot = 0.06 * Math.sin(u * 1.2);
        double cs = Math.cos(rot), sn = Math.sin(rot);
        for (int i = 0; i < APPLE_PTS; i++) {
            double ax = appleBaseXY[i * 2] * ms, ay = appleBaseXY[i * 2 + 1] * ms;
            polyScratch[i * 2] = vpX + ax * cs - ay * sn;
            polyScratch[i * 2 + 1] = vpY + ax * sn + ay * cs;
        }
        gfx.drawPolyline(polyScratch, APPLE_PTS, (6.2 + 2.2 * pPulse) * ms,
                withAlpha(PAL_CHAMPAGNE, a * clamp(0.68 + 0.28 * pPulse, 0.0, 1.0)), true);
    }

    private void drawActivePresetTrack(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, Cam cam,
                                       RailTransitState st, TrackSample[] samples, double u, double masterAlpha) {
        double trackA = masterAlpha * st.trackAlpha;
        if (trackA <= 0.005) return;
        int N = samples.length - 1;
        final double RAIL_HALF_GAUGE = 34.0;
        final double SLEEPER_HALF = 52.0;

        // 1) Blueprint guide dashes ahead of the rail forging front
        if (st.sFront < 0.94 && st.tau < 0.54) {
            double guideA = trackA * (1.0 - sstep(0.34, 0.52, st.tau)) * 0.36;
            if (guideA > 0.005) {
                for (int i = 0; i < N; i += 2) {
                    TrackSample a0 = samples[i], a1 = samples[i + 1];
                    double sm = (a0.s + a1.s) * 0.5;
                    if (sm < st.sFront - 0.03 || sm > st.sFront + 0.18) continue;
                    double env = sstep(st.sFront - 0.03, st.sFront + 0.02, sm)
                            * (1.0 - sstep(st.sFront + 0.06, st.sFront + 0.18, sm))
                            * sstep(0.95, 0.84, sm);
                    if (env <= 0.01) continue;
                    double[] p0 = cam.p(cam.v(a0.fr.pos)), p1 = cam.p(cam.v(a1.fr.pos));
                    if (p0 != null && p1 != null) {
                        gfx.drawLine(p0[0], p0[1], p1[0], p1[1], 0.95, withAlpha(PAL_BRASS_LIGHT, guideA * env));
                    }
                }
            }
        }

        // 2) Seamless additive rail-bed glow ribbon
        for (int i = 0; i < N; i++) {
            TrackSample a0 = samples[i], a1 = samples[i + 1];
            double sm = (a0.s + a1.s) * 0.5;
            double pres = trackPresenceAt(sm, st.sFront, st.sErase);
            if (pres <= 0.012) continue;
            double hw0 = (RAIL_HALF_GAUGE + 10.0) * gaugeScaleAt(a0.s);
            double hw1 = (RAIL_HALF_GAUGE + 10.0) * gaugeScaleAt(a1.s);
            double[] p0L = cam.p(cam.v(va(a0.fr.pos, vs(a0.fr.side, -hw0))));
            double[] p0R = cam.p(cam.v(va(a0.fr.pos, vs(a0.fr.side, hw0))));
            double[] p1L = cam.p(cam.v(va(a1.fr.pos, vs(a1.fr.side, -hw1))));
            double[] p1R = cam.p(cam.v(va(a1.fr.pos, vs(a1.fr.side, hw1))));
            if (p0L == null || p0R == null || p1L == null || p1R == null) continue;
            double beam = beamLiftAt(sm, st);
            boolean postFade = st.tau > 0.72;
            int rgb = beam > 0.18 ? 0xFFF2CE : (postFade ? PAL_BRASS_LIGHT : PAL_BRASS);
            double baseAlpha = beam > 0.18 ? 0.16 : (postFade ? 0.13 : 0.10);
            gfx.fillQuad(p0L[0], p0L[1], p1L[0], p1L[1], p1R[0], p1R[1], p0R[0], p0R[1],
                    withAlpha(rgb, baseAlpha * trackA * pres * (0.42 + 0.58 * beam)), BlendMode.ADDITIVE);
        }

        // 3) True-arc-length evenly spaced sleepers (blooming ahead, shrinking behind)
        double totalArc = samples[N].arc;
        final double SLEEPER_STEP = 96.0;
        int sleeperCount = Math.max(1, (int) Math.floor(totalArc / SLEEPER_STEP));
        int cursor = 0;
        for (int k = 1; k < sleeperCount; k++) {
            double targetArc = k * SLEEPER_STEP;
            while (cursor < N - 1 && samples[cursor + 1].arc < targetArc) cursor++;
            TrackSample s0 = samples[cursor], s1 = samples[cursor + 1];
            double segArc = Math.max(1e-4, s1.arc - s0.arc);
            double f = sat((targetArc - s0.arc) / segArc);
            double s = lerp(s0.s, s1.s, f);
            double pres = trackPresenceAt(s, st.sFront, st.sErase);
            if (pres <= 0.012) continue;

            double[] pos = vl(s0.fr.pos, s1.fr.pos, f);
            double[] side = vl(s0.fr.side, s1.fr.side, f);
            double sLen = Math.sqrt(side[0] * side[0] + side[1] * side[1] + side[2] * side[2]);
            if (sLen > 1e-6) { side[0] /= sLen; side[1] /= sLen; side[2] /= sLen; }

            double bloom = eOutCubic(sat((st.sFront - s) / 0.09));
            double wakeShrink = sstep(st.sErase - 0.02, st.sErase + 0.14, s);
            double halfW = SLEEPER_HALF * gaugeScaleAt(s) * lerp(0.28, 1.0, bloom * wakeShrink);
            double[] p0 = cam.p(cam.v(va(pos, vs(side, -halfW))));
            double[] p1 = cam.p(cam.v(va(pos, vs(side, halfW))));
            if (p0 == null || p1 == null) continue;

            double beam = beamLiftAt(s, st);
            boolean isMajor = (k % 4 == 0);
            boolean dissolving = wakeShrink < 0.85 || st.tau > 0.76;
            int rgb;
            double baseA;
            if (beam > 0.25) { rgb = PAL_CHAMPAGNE; baseA = 0.72; }
            else if (dissolving) { rgb = PAL_BRASS_LIGHT; baseA = 0.42; }
            else if (isMajor) { rgb = PAL_BRASS_LIGHT; baseA = 0.56; }
            else { rgb = PAL_RED; baseA = 0.40; }
            double lw = (isMajor ? 1.75 : 1.25) * clamp(p0[2] * 1.45, 0.65, 1.35);
            gfx.drawLine(p0[0], p0[1], p1[0], p1[1], lw, withAlpha(rgb, baseA * trackA * pres * (0.62 + 0.28 * beam)));
        }

        // 4) Dual 1435mm golden rails
        for (int sideSign : new int[]{-1, 1}) {
            for (int i = 0; i < N; i++) {
                TrackSample a0 = samples[i], a1 = samples[i + 1];
                double sm = (a0.s + a1.s) * 0.5;
                double pres = trackPresenceAt(sm, st.sFront, st.sErase);
                if (pres <= 0.01) continue;
                double g0 = RAIL_HALF_GAUGE * gaugeScaleAt(a0.s) * sideSign;
                double g1 = RAIL_HALF_GAUGE * gaugeScaleAt(a1.s) * sideSign;
                double[] p0 = cam.p(cam.v(va(a0.fr.pos, vs(a0.fr.side, g0))));
                double[] p1 = cam.p(cam.v(va(a1.fr.pos, vs(a1.fr.side, g1))));
                if (p0 == null || p1 == null) continue;
                double beam = beamLiftAt(sm, st);
                double scW = clamp(((p0[2] + p1[2]) * 0.5) * 1.5, 0.7, 1.35);
                int rgb = beam > 0.18 ? PAL_CHAMPAGNE : PAL_BRASS_LIGHT;
                gfx.drawLine(p0[0], p0[1], p1[0], p1[1], (1.85 + 0.85 * beam) * scW,
                        withAlpha(rgb, trackA * pres * clamp(0.76 + 0.24 * beam, 0.0, 1.0)));
            }
        }

        // 5) Forging light-front star tips on both rails
        if (st.sFront > 0.02 && st.sFront < 0.97 && st.tau < 0.50) {
            double idxF = clamp(st.sFront * N, 0.0, N - 0.001);
            int i0 = (int) Math.floor(idxF);
            double f = idxF - i0;
            TrackSample a0 = samples[i0], a1 = samples[Math.min(N, i0 + 1)];
            double[] pos = vl(a0.fr.pos, a1.fr.pos, f);
            double[] side = vl(a0.fr.side, a1.fr.side, f);
            double sLen = Math.sqrt(side[0] * side[0] + side[1] * side[1] + side[2] * side[2]);
            if (sLen > 1e-6) { side[0] /= sLen; side[1] /= sLen; side[2] /= sLen; }
            double tipA = trackA * sstep(0.01, 0.08, st.sFront) * sstep(0.97, 0.82, st.sFront);
            if (tipA > 0.01) {
                double g = RAIL_HALF_GAUGE * gaugeScaleAt(st.sFront);
                for (int sideSign : new int[]{-1, 1}) {
                    double[] pt = cam.p(cam.v(va(pos, vs(side, g * sideSign))));
                    if (pt == null) continue;
                    double rGlow = Math.max(7.0, 22.0 * pt[2]);
                    gfx.drawGlow(tunnelAtlas.hardGlowTex.getId(), pt[0], pt[1], rGlow,
                            withAlpha(0xFFFAEC, 0.90 * tipA), BlendMode.ADDITIVE);
                    double ds = Math.max(2.5, 6.5 * pt[2]);
                    gfx.fillDiamond(pt[0], pt[1], ds * 0.65, ds, withAlpha(PAL_CHAMPAGNE, 0.92 * tipA), BlendMode.ADDITIVE);
                }
            }
        }

        // 6) 14 dissolving wake embers
        if ((st.sErase > 0.01 && st.sErase < 1.02) || st.tau > 0.72) {
            double postK = sat((st.tau - 0.72) / 0.22);
            for (int e = 0; e < 14; e++) {
                double seed = st.cycleIdx * 37.0 + e * 7.13;
                double sEmber = postK > 0.05
                        ? lerp(Math.max(0.05, st.sErase - 0.04), 0.90, hash(seed + 0.3))
                        : (st.sErase + (hash(seed) - 0.25) * 0.16);
                if (sEmber <= 0.03 || sEmber >= 0.92 || sEmber > st.sFront) continue;
                double idxE = clamp(sEmber * N, 0.0, N - 0.001);
                int i0 = (int) Math.floor(idxE);
                double f = idxE - i0;
                TrackSample a0 = samples[i0], a1 = samples[Math.min(N, i0 + 1)];
                double[] pos = vl(a0.fr.pos, a1.fr.pos, f);
                double[] side = vl(a0.fr.side, a1.fr.side, f);
                double[] down = vl(a0.fr.down, a1.fr.down, f);
                double age = postK > 0.05 ? postK : sat((st.sErase + 0.14 - sEmber) / 0.18);
                double driftLat = (hash(seed + 1.7) - 0.5) * 110.0 * (0.35 + age);
                double driftUp = (14.0 + hash(seed + 3.1) * 52.0) * age;
                double[] wPos = va(va(pos, vs(side, driftLat)), vs(down, -driftUp));
                double[] pe = cam.p(cam.v(wPos));
                if (pe == null) continue;
                double ea = masterAlpha * st.envAlpha * Math.sin(age * Math.PI) * (1.0 - sstep(0.86, 0.945, st.tau)) * 0.76;
                if (ea <= 0.01) continue;
                double rDot = Math.max(1.0, (1.5 + hash(seed + 5.3) * 2.1) * pe[2]);
                gfx.fillCircle(pe[0], pe[1], rDot, withAlpha(e % 3 == 0 ? PAL_RED_BRIGHT : PAL_CHAMPAGNE, ea), 8, BlendMode.ADDITIVE);
            }
        }
    }

    private void drawTunnelTrain(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas,
                                 Cam cam, RailTransitState st, double u, double px, double py,
                                 double straighten, double masterAlpha) {
        double a = masterAlpha * st.trainAlpha * 0.96;
        if (a <= 0.008) return;

        final double BASE_SCALE = 1.88, BASE_GAP = 120.0, BASE_ELEV = 22.0, SLAB_DEPTH = 26.0;
        double[] carSs = new double[3];
        carSs[0] = st.sHead;
        for (int c = 1; c < 3; c++) {
            double prevS = carSs[c - 1];
            double gScale = gravScaleAt(prevS);
            double gap = BASE_GAP * lerp(1.0, gScale, 0.75);
            RailFrame fr0 = railFrame(st.preset, prevS, u, px, py, straighten);
            double ds1 = gap / Math.max(400.0, fr0.dsPerParam);
            RailFrame frMid = railFrame(st.preset, prevS - ds1 * 0.5, u, px, py, straighten);
            double ds2 = gap / Math.max(400.0, frMid.dsPerParam);
            carSs[c] = prevS - ds2;
        }

        // 1) 8-segment curved golden headlight cone
        if (st.sHead > -0.02 && st.sHead < 0.93) {
            double beamSpan = Math.min(0.145, Math.max(0.02, Math.min(st.sFront, 0.95) - st.sHead));
            double headG = gravScaleAt(st.sHead);
            double beamA = a * sstep(-0.01, 0.08, st.sHead) * sstep(0.93, 0.81, st.sHead);
            if (beamA > 0.01 && beamSpan > 0.025) {
                final int SEG = 8;
                double[][] ptsL = new double[SEG + 1][];
                double[][] ptsR = new double[SEG + 1][];
                for (int b = 0; b <= SEG; b++) {
                    double f = b / (double) SEG;
                    double sb = st.sHead + 0.018 + beamSpan * f;
                    RailFrame fr = railFrame(st.preset, sb, u, px, py, straighten);
                    double gb = gravScaleAt(sb);
                    double wb = lerp(11.0, 74.0, Math.pow(f, 0.88)) * gb;
                    double eb = lerp(BASE_ELEV * headG * 0.85, 6.0 * gb, f);
                    double[] cb = va(fr.pos, vs(fr.down, -eb));
                    ptsL[b] = cam.p(cam.v(va(cb, vs(fr.side, -wb))));
                    ptsR[b] = cam.p(cam.v(va(cb, vs(fr.side, wb))));
                }
                for (int b = 0; b < SEG; b++) {
                    double[] p0L = ptsL[b], p0R = ptsR[b], p1L = ptsL[b + 1], p1R = ptsR[b + 1];
                    if (p0L == null || p0R == null || p1L == null || p1R == null) continue;
                    double k0 = Math.pow(1.0 - b / (double) SEG, 1.75);
                    double k1 = Math.pow(1.0 - (b + 1) / (double) SEG, 1.75);
                    int c0 = withAlpha(0xFFF6DA, 0.28 * beamA * k0);
                    int c1 = withAlpha(PAL_BRASS_LIGHT, 0.28 * beamA * k1);
                    gfx.fillQuadColored(p0L[0], p0L[1], c0, p1L[0], p1L[1], c1,
                            p1R[0], p1R[1], c1, p0R[0], p0R[1], c0, BlendMode.ADDITIVE);
                }
                if (ptsL[0] != null && ptsR[0] != null) {
                    double hx = (ptsL[0][0] + ptsR[0][0]) * 0.5;
                    double hy = (ptsL[0][1] + ptsR[0][1]) * 0.5;
                    double hr = Math.max(7.0, 22.0 * ptsL[0][2] * headG);
                    gfx.drawGlow(tunnelAtlas.hardGlowTex.getId(), hx, hy, hr,
                            withAlpha(0xFFFAE8, 0.62 * beamA), BlendMode.ADDITIVE);
                }
            }
        }

        // 2) Dual crimson-champagne curved slipstream trails behind car 3
        double tailRefS = carSs[2];
        if (tailRefS > -0.02 && tailRefS < 0.96) {
            RailFrame frTail = railFrame(st.preset, tailRefS, u, px, py, straighten);
            double sTailStart = tailRefS - 44.0 / Math.max(400.0, frTail.dsPerParam);
            for (double sideOff : new double[]{-22.0, 22.0}) {
                double sCur = sTailStart;
                for (int i = 0; i < 16; i++) {
                    RailFrame f0 = railFrame(st.preset, sCur, u, px, py, straighten);
                    double sNext = sCur - 36.0 / Math.max(400.0, f0.dsPerParam);
                    if (sNext < -0.02 || sCur > 0.96) { sCur = sNext; continue; }
                    RailFrame f1 = railFrame(st.preset, sNext, u, px, py, straighten);
                    double sm = (sCur + sNext) * 0.5;
                    double gM = gravScaleAt(sm);
                    double[] w0 = va(va(f0.pos, vs(f0.down, -10.0 * gM)), vs(f0.side, sideOff * gM));
                    double[] w1 = va(va(f1.pos, vs(f1.down, -10.0 * gM)), vs(f1.side, sideOff * gM));
                    double[] p0 = cam.p(cam.v(w0)), p1 = cam.p(cam.v(w1));
                    sCur = sNext;
                    if (p0 == null || p1 == null) continue;
                    double tailMask = sstep(-0.01, 0.09, sm) * sstep(0.955, 0.835, sm) * sstep(st.sErase - 0.04, st.sErase + 0.14, sm);
                    if (tailMask <= 0.01) continue;
                    double lw = lerp(2.8, 0.85, i / 16.0) * clamp(p0[2] * 1.4, 0.55, 1.35);
                    gfx.drawLine(p0[0], p0[1], p1[0], p1[1], lw,
                            withAlpha(i < 4 ? PAL_CHAMPAGNE : PAL_RED_BRIGHT, a * tailMask * (1.0 - i / 16.0) * 0.74),
                            BlendMode.ADDITIVE);
                }
            }
        }

        // 3) 3 extruded red train cars (bothFaces=true for crisp left & right travel!)
        record CarDraw(double[] O, double[] M, HabiKineticAtlas.Slab tx, double alpha) {}
        List<CarDraw> cars = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) {
            double sc = carSs[c];
            if (sc < -0.03 || sc > 0.96) continue;
            RailFrame fr = railFrame(st.preset, sc, u, px, py, straighten);
            double carFog = sstep(-0.02, 0.075, sc) * sstep(0.96, 0.835, sc);
            if (carFog <= 0.01) continue;
            double gScale = gravScaleAt(sc);
            double carScale = BASE_SCALE * gScale;
            double[] Rcar = {
                    fr.tangent[0], fr.down[0], fr.side[0],
                    fr.tangent[1], fr.down[1], fr.side[1],
                    fr.tangent[2], fr.down[2], fr.side[2]
            };
            double[] carPos = va(va(fr.pos, vs(fr.down, -BASE_ELEV * gScale)), vs(fr.side, -SLAB_DEPTH * 0.5 * carScale));
            cars.add(new CarDraw(cam.v(carPos), mm(cam.R, msc(Rcar, carScale)),
                    c == 0 ? baseAtlas.stCarLead : baseAtlas.stCar, a * carFog));
        }
        cars.sort((p, q) -> Double.compare(q.O[2], p.O[2]));
        for (CarDraw ci : cars) {
            gfx.drawSlab(cam, ci.O, ci.M, ci.tx, SLAB_DEPTH, ci.alpha, 0.0, false, false, true);
        }
    }

    private void drawTunnelStars(HabiKineticGfx gfx, Cam cam, double u, double travel, double px, double py,
                                 double straighten, double masterAlpha, double endSurge, double beatPulse) {
        final double Z_MIN = -1050.0, Z_MAX = 3350.0, Z_SPAN = Z_MAX - Z_MIN;
        double streakZ = 80.0 + 320.0 * endSurge + 32.0 * beatPulse;

        for (StarPt st : stars) {
            double raw = (((st.zOff - travel * st.speedMul) % Z_SPAN) + Z_SPAN) % Z_SPAN;
            double z = Z_MIN + raw;
            double fog = sstep(Z_MAX, 2000.0, z) * sstep(Z_MIN, -650.0, z);
            if (fog <= 0.01) continue;
            double[] sp0 = spine(z, u, px, py, straighten);
            double[] sp1 = spine(z + streakZ, u, px, py, straighten);
            double ang = st.ang + u * 0.05 + (z / 3600.0) * 0.35;
            double[] p0 = cam.p(cam.v(sp0[0] + Math.cos(ang) * st.rad, sp0[1] + Math.sin(ang) * st.rad, z));
            double[] p1 = cam.p(cam.v(sp1[0] + Math.cos(ang) * st.rad, sp1[1] + Math.sin(ang) * st.rad, z + streakZ));
            if (p0 == null || p1 == null) continue;

            gfx.drawLine(p0[0], p0[1], p1[0], p1[1], Math.max(0.65, st.size * p0[2] * 0.52),
                    withAlpha(st.rgb, masterAlpha * fog * (0.28 + 0.32 * endSurge)), BlendMode.ADDITIVE);
            double rDot = Math.max(0.75, st.size * p0[2] * 0.70);
            gfx.fillCircle(p0[0], p0[1], rDot, withAlpha(st.rgb, masterAlpha * fog * (0.58 + 0.30 * endSurge)), 8, BlendMode.ADDITIVE);

            if (st.glint && z < 1450.0) {
                double gLen = rDot * 4.2;
                int gCol = withAlpha(PAL_CHAMPAGNE, masterAlpha * fog * 0.45);
                gfx.drawLine(p0[0] - gLen, p0[1], p0[0] + gLen, p0[1], 0.75, gCol, BlendMode.ADDITIVE);
                gfx.drawLine(p0[0], p0[1] - gLen, p0[0], p0[1] + gLen, 0.75, gCol, BlendMode.ADDITIVE);
            }
        }

        // 8 curved flowing wind wisps
        for (int i = 0; i < 8; i++) {
            double wSpan = 4200.0;
            double raw = (((i * (wSpan / 8.0) - travel * 1.18) % wSpan) + wSpan) % wSpan;
            double z0 = -850.0 + raw;
            double zLen = 240.0 + 220.0 * endSurge + (i % 3) * 70.0;
            double fog = sstep(2950.0, 1750.0, z0) * sstep(-850.0, -420.0, z0);
            if (fog <= 0.01) continue;
            double baseA = (i * 2.39996) % TAU;
            double rAir = 280.0 + ((i * 67) % 280) + Math.sin(u * 1.4 + i) * 22.0;
            int col = withAlpha(i % 2 == 0 ? PAL_BRASS_LIGHT : 0xD7E4FF,
                    (i % 2 == 0 ? 0.14 : 0.11) * masterAlpha * fog * (0.42 + 0.32 * endSurge));
            int pn = 0;
            for (int s = 0; s <= 6; s++) {
                double z = z0 + (s / 6.0) * zLen;
                double[] sp = spine(z, u, px, py, straighten);
                double ang = baseA + (z / 3800.0) * 0.20 + Math.sin(u * 2.2 + z * 0.008 + i) * 0.04;
                double[] p = cam.p(cam.v(sp[0] * 0.6 + Math.cos(ang) * rAir, sp[1] * 0.6 + Math.sin(ang) * rAir * 0.82, z));
                if (p != null) { polyScratch[pn * 2] = p[0]; polyScratch[pn * 2 + 1] = p[1]; pn++; }
            }
            if (pn >= 2) gfx.drawPolyline(polyScratch, pn, 0.8 + 0.6 * endSurge, col, false, BlendMode.ADDITIVE);
        }
    }

    private void drawTunnelStampsAndTickets(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, Cam cam,
                                            double u, double travel, double px, double py, double straighten,
                                            double masterAlpha, double endSurge, @Nullable Double eruptBoost, double beatPulse) {
        if (!tunnelAtlas.hasAnyFloatingAssets()) return;
        final double Z_NEAR = -860.0, Z_FAR = 2360.0, Z_WIN = Z_FAR - Z_NEAR;
        double span = stampSpanZ > 100.0 ? stampSpanZ : 29100.0;
        double stampTravel = travel * 0.72;
        double eruptScale = eruptBoost != null ? eOutBack(sat(eruptBoost * 1.2), 1.05) : 1.0;
        double doorReveal = eruptBoost != null ? sstep(0.18, 0.72, eruptBoost) : 1.0;
        if (doorReveal <= 0.005) return;

        record FloatRender(FloatingDef it, HabiTunnelAtlas.StampTex tx, double z, double life, double alpha) {}
        List<FloatRender> list = new ArrayList<>(3);
        for (FloatingDef it : tunnelItems) {
            double raw = (((it.zOff - (stampTravel % span)) % span) + span) % span;
            if (raw > Z_WIN) continue;
            double z = Z_NEAR + raw;
            double life = sat((Z_FAR - z) / Z_WIN);
            double fogIn = sstep(Z_FAR, 1620.0, z);
            double fogOut = sstep(Z_NEAR, -220.0, z);
            double alpha = masterAlpha * doorReveal * fogIn * fogOut * (1.0 - sstep(0.28, 0.50, endSurge));
            if (alpha <= 0.008) continue;

            HabiTunnelAtlas.StampTex tx = it.isTicket
                    ? tunnelAtlas.tickets[it.assetIdx % tunnelAtlas.tickets.length]
                    : tunnelAtlas.stamps[it.assetIdx % tunnelAtlas.stamps.length];
            if (tx != null) list.add(new FloatRender(it, tx, z, life, alpha));
        }

        list.sort((a, b) -> Double.compare(b.z, a.z));
        for (FloatRender item : list) {
            drawFloatingItem(gfx, tunnelAtlas, cam, item.it, item.tx, item.z, item.life, item.alpha,
                    u, px, py, straighten, endSurge, eruptScale, beatPulse);
        }
    }

    private void drawFloatingItem(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, Cam cam,
                                  FloatingDef it, HabiTunnelAtlas.StampTex tx, double z, double life, double alpha,
                                  double u, double px, double py, double straighten,
                                  double endSurge, double eruptScale, double beatPulse) {
        double[] sp = spine(z, u, px, py, straighten);
        double radialPart = 1.0 + 1.85 * Math.pow(endSurge, 1.15);
        double r = lerp(it.rFar, it.rNear, eInOutCubic(life)) * radialPart * lerp(0.58, 1.0, sat(eruptScale));
        double ang = it.ang0 + it.angDrift * (life - 0.35) + 0.09 * Math.sin(u * 0.46 + it.phase);
        double driftX = Math.sin(u * 0.60 + it.phase) * 26.0 + Math.sin(life * Math.PI + it.phase * 1.6) * it.driftAmpX;
        double driftY = Math.cos(u * 0.52 + it.phase * 1.3) * 20.0 + Math.cos(life * Math.PI * 0.85 + it.phase) * it.driftAmpY;

        double[] centerW = {
                sp[0] * 0.50 + Math.cos(ang) * r * 1.18 + driftX,
                sp[1] * 0.50 + Math.sin(ang) * r * 0.78 + driftY,
                z + Math.sin(u * 0.72 + it.phase * 2.1) * 28.0
        };

        double yaw = clamp(it.yaw0 + 0.24 * Math.sin(u * it.tumbleSpd + it.phase) + it.yawSweep * (life - 0.45), -0.68, 0.68);
        double pitch = clamp(it.pitch0 + 0.18 * Math.cos(u * it.tumbleSpd * 0.82 + it.phase * 1.4) + it.pitchSweep * (life - 0.45), -0.50, 0.50);
        double roll = it.roll0 + 0.15 * Math.sin(u * it.tumbleSpd * 0.62 + it.phase * 2.2);
        double[] Ritem = euler(pitch, yaw, roll);

        double sc = it.scale * lerp(0.68, 1.0, sat(eruptScale));
        double worldW = tx.w() * sc;
        double worldH = tx.h() * sc;

        // Soft ambient halo + 2 orbiting gold dust motes
        double[] pCen = cam.p(cam.v(centerW));
        if (pCen != null) {
            double haloR = Math.max(30.0, Math.max(worldW, worldH) * pCen[2] * 0.82);
            gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), pCen[0], pCen[1], haloR,
                    withAlpha(PAL_BRASS_LIGHT, 0.14 * alpha), BlendMode.ADDITIVE);
            for (int m = 0; m < 2; m++) {
                double mPhase = u * 0.9 + it.phase + m * 2.4;
                double[] mw = va(centerW, new double[]{
                        Math.cos(mPhase) * (worldW * 0.58 + 18.0 * m),
                        Math.sin(mPhase * 1.2) * (worldH * 0.46 + 14.0 * m),
                        42.0 + m * 38.0
                });
                double[] mp = cam.p(cam.v(mw));
                if (mp != null) {
                    double ma = alpha * (0.45 - m * 0.14) * (0.6 + 0.4 * Math.sin(u * 3.0 + m + it.idx));
                    gfx.fillCircle(mp[0], mp[1], Math.max(0.9, 2.0 * mp[2]), withAlpha(PAL_BRASS_LIGHT, ma), 8, BlendMode.ADDITIVE);
                }
            }
        }

        // Build curved flexing/curling 3D mesh
        int nx = z < 1100.0 ? (it.isTicket ? 6 : 5) : 4;
        int ny = z < 1100.0 ? (it.isTicket ? 4 : 6) : 4;
        int cols = nx + 1, rows = ny + 1;
        double[][] P = new double[rows * cols][];
        double backK = sstep(1450.0, 620.0, z);
        double[][] Pback = backK > 0.04 ? new double[rows * cols][] : null;
        int[] faceColors = new int[rows * cols];
        int[] foilColors = new int[rows * cols];

        double wavePh = u * it.waveFreq * (1.0 + 0.75 * endSurge) + it.phase;
        double gust = 1.0 + 0.10 * beatPulse + 0.45 * endSurge;
        double edgeThick = (it.isTicket ? 3.4 : 2.8) * backK;
        double depthHaze = sat((z - 980.0) / 1450.0) * 0.44;
        double shadeMax = clamp(0.14 * (1.0 - depthHaze * 0.5), 0.0, 0.20);
        double hiFade = sstep(1550.0, 320.0, z);
        double foilSweep = ((u * 0.55 + it.phase * 0.3) % 2.4) - 0.7;

        boolean anyVisible = false;
        for (int j = 0; j <= ny; j++) {
            double vFrac = j / (double) ny;
            double tv = vFrac - 0.5;
            double ly = tv * worldH;
            for (int i = 0; i <= nx; i++) {
                double uFrac = i / (double) nx;
                double tu = uFrac - 0.5;
                double lx = tu * worldW;
                int idx = j * cols + i;

                double sCurve = Math.sin(tv * 2.55 + tu * 1.15 * it.curlDir - wavePh) * it.flexAmp;
                double diagCurl = (tu * tu * 0.85 + tv * tv) * it.camberAmp * Math.cos(wavePh * 0.58 + tu * 1.7 * it.curlDir);
                double lz = (sCurve + diagCurl) * gust;

                double[] pScr = cam.p(cam.v(va(centerW, mv(Ritem, new double[]{lx, ly, lz}))));
                P[idx] = pScr;
                if (pScr != null) anyVisible = true;
                if (Pback != null) {
                    Pback[idx] = cam.p(cam.v(va(centerW, mv(Ritem, new double[]{lx, ly, lz + edgeThick}))));
                }

                // Per-vertex depth haze & soft curved shading
                double diag = (uFrac + vFrac) * 0.5;
                double darken = sat(depthHaze + shadeMax * diag);
                int tintRgb = lerpRgb(0xFFFFFF, 0x04060E, darken);
                faceColors[idx] = withAlpha(tintRgb, alpha);

                // Per-vertex diagonal gold-foil sweep highlight
                if (hiFade > 0.02) {
                    double band = Math.max(0.0, 1.0 - Math.abs(diag - foilSweep) / 0.24);
                    double fa = (0.04 * (1.0 - diag) + 0.18 * band * band) * hiFade * alpha;
                    foilColors[idx] = withAlpha(0xFFF2D2, fa);
                } else {
                    foilColors[idx] = 0;
                }
            }
        }
        if (!anyVisible) return;

        // Back paper thickness layer
        if (Pback != null) {
            int[] sideColors = new int[rows * cols];
            int scCol = withAlpha(lerpRgb(0xFFFFFF, 0x04060E, depthHaze), alpha);
            for (int k = 0; k < sideColors.length; k++) sideColors[k] = scCol;
            gfx.drawCurvedTexturedMesh(tx.sideTexId(), Pback, nx, ny, sideColors, BlendMode.NORMAL);
        }

        // Front curved stamp/ticket mesh (1 GPU draw call, zero seams!)
        gfx.drawCurvedTexturedMesh(tx.faceTexId(), P, nx, ny, faceColors, BlendMode.NORMAL);

        // Diagonal gold-foil additive sheen clipped to exact perforated outline via sideTex silhouette
        if (hiFade > 0.02) {
            gfx.drawCurvedTexturedMesh(tx.sideTexId(), P, nx, ny, foilColors, BlendMode.ADDITIVE);
        }
    }

    private void renderTunnelHud(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas,
                                 HabiWorldEntrySession session, double masterAlpha, double endFadeOut,
                                 @Nullable HabiBootAudioEngine.Telemetry tel) {
        double a = masterAlpha * (1.0 - endFadeOut);
        if (a <= 0.005) return;
        double L = gfx.viewLeft(), T = gfx.viewTop(), R = gfx.viewRight(), B = gfx.viewBottom();
        double totalT = session.getTotalT();
        double progress = session.getProgress();
        HabiWorldEntrySession.StageState[] stages = session.computeStages();
        int activeIdx = stages.length - 1;
        for (int i = 0; i < stages.length; i++) {
            if (!stages[i].done()) { activeIdx = i; break; }
        }
        HabiWorldEntrySession.StageState activeStg = stages[activeIdx];
        int pct = Math.min(100, (int) Math.round(progress * 100.0));

        // Top & bottom atmospheric scrims
        gfx.fillGradientRectV(L - 10, T - 10, gfx.viewWidth() + 20, 160.0,
                withAlpha(0x04060C, 0.76 * a), withAlpha(0x04060C, 0.0), BlendMode.NORMAL);
        gfx.fillGradientRectV(L - 10, B - 240.0, gfx.viewWidth() + 20, 250.0,
                withAlpha(0x04060C, 0.0), withAlpha(0x04060C, 0.86 * a), BlendMode.NORMAL);

        drawCornerBrackets(gfx, L, T, R, B, 38.0, 18.0, 1.0, withAlpha(0xEEEAE2, 0.22 * a));

        // Top-left route header
        double dx = L + 58.0, dy = T + 52.0;
        gfx.fillDiamond(dx, dy, 3.5, 3.5, withAlpha(PAL_BRASS_LIGHT, a), BlendMode.NORMAL);
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "02  .  " + session.getRouteSub(),
                L + 72.0, dy, 10.0, 3.2, withAlpha(PAL_BRASS_LIGHT, 0.78 * a), -1, 0.0);
        baseAtlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, session.getRouteName(),
                L + 72.0, T + 78.0, 16, 4.5f, withAlpha(PAL_PAPER_TXT, a), -1, 0.0, 0);

        // Top-right 14-bar live audio EQ + 100 BPM
        double bx = R - 58.0;
        int beatIdx = tel != null ? tel.beatIndex() : 0;
        for (int i = 0; i < 14; i++) {
            double v = tel != null ? tel.bar(i) : (0.24 + 0.30 * Math.sin(totalT * 4.5 + i));
            double bh = 3.0 + v * 14.0;
            boolean isBeat = (beatIdx % 7) == (i / 2);
            int col = isBeat ? withAlpha(PAL_BRASS_LIGHT, a)
                    : (i < 3 ? withAlpha(PAL_RED_BRIGHT, a) : withAlpha(0xEEEAE2, 0.22 * a));
            gfx.fillRect(bx - (13 - i) * 6.5, T + 56.0 - bh * 0.5, 1.6, bh, col);
        }
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO,
                String.format(Locale.ROOT, "CHRONO RAIL  .  %d BPM", tel != null ? tel.bpm() : 100),
                bx + 2.0, T + 76.0, 9.5, 2.8, withAlpha(0xEEEAE2, 0.44 * a), 1, 0.0);

        // Bottom-left 5-stage manifest
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO,
                pct >= 100 ? "MANIFEST  .  VERIFIED" : "MANIFEST  .  SYNCING",
                L + 58.0, B - 196.0, 9.5, 2.6, withAlpha(PAL_BRASS_LIGHT, 0.58 * a), -1, 0.0);

        for (int idx = 0; idx < stages.length; idx++) {
            HabiWorldEntrySession.StageState stg = stages[idx];
            double yy = B - 172.0 + idx * 19.0;
            boolean isAct = pct < 100 && idx == activeIdx;
            if (stg.done()) {
                gfx.fillDiamond(L + 61.0, yy, 3.0, 3.0, withAlpha(PAL_BRASS_LIGHT, a), BlendMode.NORMAL);
                baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, stg.code() + "  " + stg.en(),
                        L + 72.0, yy, 9.5, 2.6, withAlpha(0xEEEAE2, 0.66 * a), -1, 0.0);
            } else if (isAct) {
                double breath = 0.55 + 0.45 * Math.sin(totalT * 5.2);
                gfx.fillCircle(L + 61.0, yy, 3.0, withAlpha(PAL_RED_BRIGHT, a * breath), 10);
                baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, stg.code() + "  " + stg.en(),
                        L + 72.0, yy, 9.5, 2.6, withAlpha(PAL_PAPER_TXT, a), -1, 0.0);
            } else {
                gfx.fillCircle(L + 61.0, yy, 1.6, withAlpha(0xEEEAE2, 0.18 * a), 8);
                baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, stg.code() + "  " + stg.en(),
                        L + 72.0, yy, 9.5, 2.6, withAlpha(PAL_MUTE, 0.58 * a), -1, 0.0);
            }
        }

        // Bottom-center rail progress track
        double trackW = Math.min(640.0, gfx.viewWidth() * 0.42);
        double tx0 = (L + R) * 0.5 - trackW * 0.5;
        double tx1 = tx0 + trackW;
        double lineY = B - 92.0;

        String zhLabel = pct >= 100 ? "世界维度同步就绪" : activeStg.zh();
        var zhTex = baseAtlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, zhLabel,
                tx0, lineY - 22.0, 13, 3.5f, withAlpha(PAL_PAPER_TXT, a), -1, 0.0, 0);
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, activeStg.code() + "/05  .  " + activeStg.en(),
                tx0 + zhTex.w() + 16.0, lineY - 21.0, 9.5, 2.2, withAlpha(PAL_BRASS_LIGHT, 0.62 * a), -1, 0.0);
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, String.format(Locale.ROOT, "%02d%%", pct),
                tx1, lineY - 24.0, 34.0, 2.5, withAlpha(pct >= 100 ? PAL_BRASS_LIGHT : PAL_PAPER_TXT, a), 1, 0.0);

        int railBg = withAlpha(0xEEEAE2, 0.18 * a);
        gfx.drawLine(tx0, lineY, tx1, lineY, 1.2, railBg);
        gfx.drawLine(tx0, lineY - 4.0, tx0, lineY + 4.0, 1.2, railBg);
        gfx.drawLine(tx1, lineY - 4.0, tx1, lineY + 4.0, 1.2, railBg);

        double fillW = trackW * sat(progress);
        if (fillW > 1.0) {
            double splitW = fillW * 0.72;
            gfx.fillGradientRectH(tx0, lineY - 1.1, splitW, 2.2,
                    withAlpha(PAL_RED, a), withAlpha(PAL_RED_BRIGHT, a), BlendMode.NORMAL);
            gfx.fillGradientRectH(tx0 + splitW, lineY - 1.1, fillW - splitW, 2.2,
                    withAlpha(PAL_RED_BRIGHT, a), withAlpha(PAL_BRASS_LIGHT, a), BlendMode.NORMAL);
            double headX = tx0 + fillW;
            gfx.drawGlow(tunnelAtlas.hardGlowTex.getId(), headX, lineY, 16.0,
                    withAlpha(PAL_CHAMPAGNE, 0.92 * a), BlendMode.ADDITIVE);
            gfx.fillDiamond(headX, lineY, 4.2, 4.2, withAlpha(PAL_CHAMPAGNE, a), BlendMode.NORMAL);
        }

        double wAcc = 0.0;
        for (int i = 0; i < HabiWorldEntrySession.WORLD_STAGES.length - 1; i++) {
            wAcc += HabiWorldEntrySession.WORLD_STAGES[i].weight();
            double nx = tx0 + trackW * wAcc;
            boolean passed = progress >= wAcc - 0.002;
            gfx.fillDiamond(nx, lineY, 3.0, 3.0, withAlpha(passed ? PAL_BRASS_LIGHT : 0x161B2C, a), BlendMode.NORMAL);
        }

        // Rotating travel tips
        double tipPeriod = 4.2;
        int tipIdx = Math.floorMod((int) Math.floor(totalT / tipPeriod), TUNNEL_TIPS_ZH.length);
        double tipLocal = totalT % tipPeriod;
        double tipFade = sstep(0.0, 0.45, tipLocal) * sstep(tipPeriod, tipPeriod - 0.45, tipLocal);
        baseAtlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, TUNNEL_TIPS_ZH[tipIdx],
                (L + R) * 0.5, B - 50.0 + (1.0 - tipFade) * 3.0, 11, 2.6f,
                withAlpha(PAL_PAPER, 0.58 * tipFade * a), 0, 0.0, 0);

        // Bottom-right real chunk / connection counter
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, session.getStatusCounter().replace('·', '.'),
                R - 58.0, B - 50.0, 10.0, 2.8, withAlpha(0xEEEAE2, 0.50 * a), 1, 0.0);

        drawRightVerticalStamp(gfx, baseAtlas, R, T, "星夜轨网",
                withAlpha(0xEEEAE2, 0.42 * 0.62 * a),
                withAlpha(PAL_BRASS_LIGHT, 0.62 * a),
                withAlpha(PAL_BRASS_LIGHT, 0.44 * 0.62 * a));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ACT 3: END BREAKOUT (0.00s -> 1.66s) — Apple Portal Over REAL Minecraft World!
    // ════════════════════════════════════════════════════════════════════════

    private void renderEndBreakout(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas,
                                   HabiWorldEntrySession session, double t, double px, double py,
                                   @Nullable HabiBootAudioEngine.Telemetry tel, double beatPulse) {
        double surgeK = sat(t / 1.02);
        double portalK = sat((t - HabiWorldEntrySession.END_PORTAL_START) / HabiWorldEntrySession.END_PORTAL_DUR);
        boolean fullInGame = portalK >= 0.995;

        if (!fullInGame) {
            double asc = portalK > 0.004 ? lerp(0.14, 10.8, Math.pow(portalK, 2.35)) : 0.0;
            boolean cutoutActive = portalK > 0.004;

            if (cutoutActive) {
                for (int i = 0; i < APPLE_PTS; i++) {
                    appleScrXY[i * 2] = CX + appleBaseXY[i * 2] * asc;
                    appleScrXY[i * 2 + 1] = CY + appleBaseXY[i * 2 + 1] * asc;
                }
                // Inverted depth cutout: EVERYTHING below draws ONLY OUTSIDE the expanding Apple,
                // revealing the REAL live 3D Minecraft world cleanly through the Apple Portal Iris!
                gfx.beginInvertedDepthClipStar(CX, CY, appleScrXY, APPLE_PTS);
            }
            try {
                renderTimeTunnel(gfx, baseAtlas, tunnelAtlas, session, 1.0, surgeK, px, py, beatPulse, null);
                if (t < 0.44) {
                    renderTunnelHud(gfx, baseAtlas, tunnelAtlas, session, 1.0, eOutCubic(sat(t / 0.40)), tel);
                }
                if (cutoutActive) {
                    drawArrivalExitStamps(gfx, tunnelAtlas, portalK, asc);
                    drawApplePortalIrisRays(gfx, portalK, asc);
                }
            } finally {
                if (cutoutActive) {
                    gfx.endDepthClip();
                }
            }

            // Glowing 4-layer Apple Portal Iris Rim right on the cutout boundary
            if (cutoutActive) {
                drawApplePortalIrisRim(gfx, portalK);
            }

            // Gold Arrival Postmark Seal (0.00..0.47s steady hold, 0.47..0.56s bursts open!)
            if (t < 0.60) {
                drawArrivalPostmarkSeal(gfx, baseAtlas, tunnelAtlas, t);
            }
        } else {
            // Real 3D Minecraft world + real HUD are 100% visible: warm champagne-gold arrival bloom fades cleanly!
            double bloom = Math.pow(1.0 - sat((t - 1.04) / 0.52), 2.2);
            if (bloom > 0.005) {
                gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), CX, CY, 1280.0,
                        withAlpha(PAL_BRASS_LIGHT, 0.14 * bloom), BlendMode.ADDITIVE);
                gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), CX, CY, 760.0,
                        withAlpha(0xFFF6E0, 0.22 * bloom), BlendMode.ADDITIVE);
            }
        }
    }

    private void drawApplePortalIrisRays(HabiKineticGfx gfx, double portalK, double asc) {
        double rimFade = Math.sin(Math.pow(portalK, 0.65) * Math.PI);
        double baseR = 148.0 * asc;
        if (rimFade <= 0.01 || baseR >= 1350.0) return;

        for (int i = 0; i < 16; i++) {
            double ang = (i / 16.0) * TAU + portalK * 0.22;
            double ca = Math.cos(ang), sa = Math.sin(ang);
            double nx = -sa, ny = ca;
            double r0 = Math.max(12.0, baseR * 0.68);
            double r1 = baseR * 1.04 + 240.0 + 260.0 * portalK;
            double rm = lerp(r0, r1, 0.35);
            double hw = (10.0 + (i % 3) * 5.0) * rimFade;
            int colMid = (i % 3 == 0)
                    ? withAlpha(PAL_RED_BRIGHT, 0.40 * rimFade)
                    : withAlpha(0xFFF6E0, 0.44 * rimFade);
            int col0 = withAlpha(i % 3 == 0 ? PAL_RED_BRIGHT : 0xFFF6E0, 0.0);

            gfx.fillQuadColored(
                    CX + ca * r0, CY + sa * r0, col0,
                    CX + ca * rm + nx * hw, CY + sa * rm + ny * hw, colMid,
                    CX + ca * r1, CY + sa * r1, col0,
                    CX + ca * rm - nx * hw, CY + sa * rm - ny * hw, colMid,
                    BlendMode.ADDITIVE);
        }
    }

    private void drawApplePortalIrisRim(HabiKineticGfx gfx, double portalK) {
        double rimFade = Math.sin(Math.pow(portalK, 0.65) * Math.PI);
        if (rimFade <= 0.01) return;
        gfx.drawPolyline(appleScrXY, APPLE_PTS, 36.0, withAlpha(PAL_RED_BRIGHT, 0.42 * 0.60 * rimFade), true, BlendMode.ADDITIVE);
        gfx.drawPolyline(appleScrXY, APPLE_PTS, 20.0, withAlpha(PAL_RED_BRIGHT, 0.46 * rimFade), true, BlendMode.ADDITIVE);
        gfx.drawPolyline(appleScrXY, APPLE_PTS, 11.0, withAlpha(PAL_BRASS_LIGHT, 0.78 * rimFade), true, BlendMode.ADDITIVE);
        gfx.drawPolyline(appleScrXY, APPLE_PTS, 3.4, withAlpha(0xFFFDF8, 0.96 * rimFade), true, BlendMode.ADDITIVE);
    }

    private void drawArrivalPostmarkSeal(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, HabiTunnelAtlas tunnelAtlas, double t) {
        double slam = sat(t / 0.22);
        double burstK = sat((t - 0.47) / 0.09);
        double sc = lerp(1.38, 1.0, eOutCubic(slam)) * (1.0 + 1.05 * eOutCubic(burstK));
        double fade = sstep(0.0, 0.06, t) * Math.pow(1.0 - burstK, 1.7);
        if (fade <= 0.01) return;

        double ox = CX, oy = CY - 8.0;
        double cySeal = oy - 14.0 * sc;

        // Dark radial backing halo for crisp contrast
        gfx.drawGlow(tunnelAtlas.softGlowTex.getId(), ox, oy, 230.0 * sc,
                withAlpha(0x080B16, 0.68 * fade), BlendMode.NORMAL);

        // Expanding brass shockwave ring
        double waveK = sat((t - 0.03) / 0.50);
        if (waveK > 0.0 && waveK < 1.0) {
            gfx.strokeCircle(ox, cySeal, (92.0 + 170.0 * eOutCubic(waveK)) * sc,
                    Math.max(0.7, 2.2 * (1.0 - waveK) * sc), withAlpha(PAL_BRASS_LIGHT, fade * (1.0 - waveK) * 0.88), 48);
        }

        // Precision double brass postmark rings + 36 rotating compass ticks
        gfx.strokeCircle(ox, cySeal, 86.0 * sc, 2.1 * sc, withAlpha(PAL_BRASS_LIGHT, 0.94 * fade), 48);
        gfx.strokeCircle(ox, cySeal, 74.0 * sc, 1.0 * sc, withAlpha(PAL_BRASS_LIGHT, 0.52 * 0.94 * fade), 48);

        int sn = 0;
        for (int i = 0; i < 36; i++) {
            double ang = (i / 36.0) * TAU + t * 0.25;
            double r0 = 76.0 * sc, r1 = (i % 3 == 0 ? 83.0 : 80.0) * sc;
            int p = sn * 4;
            segScratch[p] = ox + Math.cos(ang) * r0;
            segScratch[p + 1] = cySeal + Math.sin(ang) * r0;
            segScratch[p + 2] = ox + Math.cos(ang) * r1;
            segScratch[p + 3] = cySeal + Math.sin(ang) * r1;
            sn++;
        }
        gfx.drawSegments(segScratch, sn, 1.0 * sc, withAlpha(PAL_BRASS_LIGHT, 0.52 * 0.94 * fade));

        // 3 wavy cancellation lines extending left & right
        int waveCol = withAlpha(PAL_BRASS_LIGHT, 0.58 * 0.94 * fade);
        for (int side : new int[]{-1, 1}) {
            for (int w = -1; w <= 1; w++) {
                double wy = cySeal + w * 13.0 * sc;
                for (int s = 0; s <= 14; s++) {
                    double wx = ox + side * (98.0 + (s / 14.0) * 94.0) * sc;
                    double yy = wy + Math.sin(s * 0.7 + w) * 3.2 * sc;
                    polyScratch[s * 2] = wx;
                    polyScratch[s * 2 + 1] = yy;
                }
                gfx.drawPolyline(polyScratch, 15, 1.35 * sc, waveCol, false);
            }
        }

        // Typography: HABITRAIN / ARRIVAL / VERIFIED / 检票通过 · 即将抵达
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "HABITRAIN",
                ox + 2.0 * sc, oy - 62.0 * sc, 10.0 * sc, 3.5 * sc, withAlpha(PAL_BRASS_LIGHT, 0.94 * fade), 0, 0.0);
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, "ARRIVAL",
                ox + 3.0 * sc, oy - 13.0 * sc, 32.0 * sc, 5.0 * sc, withAlpha(PAL_CHAMPAGNE, 0.94 * fade), 0, 0.0);
        baseAtlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "VERIFIED",
                ox + 2.0 * sc, oy + 34.0 * sc, 9.0 * sc, 3.0 * sc, withAlpha(PAL_BRASS_LIGHT, 0.80 * 0.94 * fade), 0, 0.0);

        baseAtlas.drawTextScaled(gfx, HabiKineticAtlas.FontFace.SANS_CN, "检票通过 · 即将抵达",
                ox + 4.0 * sc, oy + 110.0 * sc, 30, 14.0f, 0.5 * sc,
                withAlpha(PAL_CHAMPAGNE, fade), 0, 0.0, 0);
        gfx.fillRect(ox - 132.0 * sc, oy + 109.0 * sc, 18.0 * sc, 1.6 * sc, withAlpha(PAL_RED_BRIGHT, fade));
        gfx.fillRect(ox + 114.0 * sc, oy + 109.0 * sc, 18.0 * sc, 1.6 * sc, withAlpha(PAL_RED_BRIGHT, fade));
    }

    private void drawArrivalExitStamps(HabiKineticGfx gfx, HabiTunnelAtlas tunnelAtlas, double portalK, double asc) {
        if (!tunnelAtlas.hasAnyFloatingAssets() || portalK <= 0.004 || portalK >= 0.95) return;
        double a = sstep(0.0, 0.14, portalK) * (1.0 - sstep(0.52, 0.92, portalK)) * 0.84;
        if (a <= 0.008) return;
        double k = eOutCubic(portalK);
        double rimR = 168.0 * asc;

        int[] idxs = {0, 4};
        double[] dxs = {-1.0, 1.0};
        double[] dys = {-0.44, 0.42};
        double[] rots = {-0.24, 0.22};
        double[] sc0s = {0.76, 0.82};

        for (int p = 0; p < 2; p++) {
            HabiTunnelAtlas.StampTex tx = tunnelAtlas.stamps[idxs[p] % tunnelAtlas.stamps.length];
            if (tx == null) continue;
            double dx = dxs[p], dy = dys[p];
            double rad = Math.max(rimR * 1.10 + 92.0, 235.0 + 460.0 * k) + 340.0 * portalK * portalK;
            double x = CX + dx * rad * 0.95;
            double y = CY + dy * rad * 0.62;
            double sc = sc0s[p] + 0.42 * k;

            // Shockwave golden/red light wake
            double bx = CX + dx * Math.max(40.0, rad * 0.70) * 0.95;
            double by = CY + dy * Math.max(24.0, rad * 0.70) * 0.62;
            double vx = x - bx, vy = y - by, vl = Math.hypot(vx, vy);
            if (vl > 1.0) {
                double hw = 21.0 * sc * (1.0 - 0.45 * portalK);
                double nx = (-vy / vl) * hw, ny = (vx / vl) * hw;
                gfx.fillQuadColored(
                        bx + nx, by + ny, withAlpha(PAL_BRASS_LIGHT, 0.0),
                        bx - nx, by - ny, withAlpha(PAL_BRASS_LIGHT, 0.0),
                        x - nx, y - ny, withAlpha(PAL_RED_BRIGHT, 0.22 * a),
                        x + nx, y + ny, withAlpha(PAL_BRASS_LIGHT, 0.22 * a),
                        BlendMode.ADDITIVE);
            }

            gfx.drawTexturedRectRotated(tx.faceTexId(), x, y, tx.w() * sc, tx.h() * sc,
                    rots[p] + dx * 0.46 * k, withAlpha(0xFFFFFF, a), BlendMode.NORMAL);
        }
    }

    // ── Shared HUD & Post-FX Helpers ─────────────────────────────────────────

    private void drawCornerBrackets(HabiKineticGfx gfx, double L, double T, double R, double B,
                                    double inset, double len, double lw, int col) {
        gfx.drawLine(L + inset, T + inset + len, L + inset, T + inset, lw, col);
        gfx.drawLine(L + inset, T + inset, L + inset + len, T + inset, lw, col);
        gfx.drawLine(R - inset - len, T + inset, R - inset, T + inset, lw, col);
        gfx.drawLine(R - inset, T + inset, R - inset, T + inset + len, lw, col);
        gfx.drawLine(L + inset, B - inset - len, L + inset, B - inset, lw, col);
        gfx.drawLine(L + inset, B - inset, L + inset + len, B - inset, lw, col);
        gfx.drawLine(R - inset - len, B - inset, R - inset, B - inset, lw, col);
        gfx.drawLine(R - inset, B - inset, R - inset, B - inset - len, lw, col);
    }

    private void drawRightVerticalStamp(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, double R, double T,
                                        String col2, int col1Argb, int col2Argb, int barArgb) {
        String col1 = HabiZeroStationRenderer.BRAND_CN;
        for (int i = 0; i < col1.length(); i++) {
            baseAtlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, col1.substring(i, i + 1),
                    R - HabiZeroStationRenderer.RIGHT_STAMP_COL1_INSET, T + 420.0 + i * 26.0, 18, 0f, col1Argb, 0, 0.0, 0);
        }
        for (int i = 0; i < col2.length(); i++) {
            baseAtlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, col2.substring(i, i + 1),
                    R - HabiZeroStationRenderer.RIGHT_STAMP_COL2_INSET, T + 446.0 + i * 26.0, 18, 0f, col2Argb, 0, 0.0, 0);
        }
        gfx.fillRect(R - HabiZeroStationRenderer.RIGHT_STAMP_BAR_INSET, T + 386.0, 16.0, 2.0, barArgb);
    }

    private void renderPostGrain(HabiKineticGfx gfx, HabiKineticAtlas baseAtlas, double t, double alpha) {
        double f = Math.floor(t * 24.0);
        gfx.drawTiledTexture(baseAtlas.noiseTex.getId(),
                gfx.viewLeft() - 10, gfx.viewTop() - 10, gfx.viewWidth() + 20, gfx.viewHeight() + 20,
                256.0, hash(f), hash(f + 7.1), withAlpha(0xFFFFFF, alpha), BlendMode.SCREEN);
    }

    // ── 3D Rail Track & Geometry Math ────────────────────────────────────────

    private RailTransitState computeRailTransitState(double u, double endSurge, @Nullable Double eruptBoost) {
        final double CYCLE_DUR = 4.20;
        double doorReveal = eruptBoost != null ? sstep(0.14, 0.72, eruptBoost) : 1.0;
        double endFade = 1.0 - sstep(0.24, 0.62, endSurge);
        double envAlpha = doorReveal * endFade;
        if (envAlpha <= 0.004) return null;

        double routeClock = Math.max(0.0, u - HabiWorldEntrySession.BOARD_AT);
        int cycleIdx = (int) Math.floor(routeClock / CYCLE_DUR);
        double tau = (routeClock - cycleIdx * CYCLE_DUR) / CYCLE_DUR;
        if (tau >= 0.945) return null;

        RailPreset preset = railPresets.get(Math.floorMod(cycleIdx, railPresets.size()));
        double sFront = lerp(0.0, 1.02, eOutCubic(sat(tau / 0.42)));

        final double TRAIN_START = 0.075, TRAIN_END = 0.745;
        double trainRaw = (tau - TRAIN_START) / (TRAIN_END - TRAIN_START);
        boolean trainActive = trainRaw >= -0.02 && trainRaw <= 1.04;
        double tp = sat(trainRaw);
        double sHead = lerp(-0.04, 1.04, tp * 0.84 + Math.pow(tp, 1.85) * 0.16);
        double trainAlpha = trainActive
                ? sstep(-0.01, 0.11, trainRaw) * (1.0 - sstep(0.86, 1.01, trainRaw)) : 0.0;

        double wakeWhileRunning = sat((tau - 0.46) / 0.28) * 0.24;
        double wakePostTrain = Math.pow(sat((tau - 0.70) / 0.24), 1.08) * 0.90;
        double sErase = -0.14 + wakeWhileRunning + wakePostTrain;

        double trackAlpha = envAlpha * sstep(0.0, 0.075, tau) * (1.0 - sstep(0.72, 0.94, tau));
        if (trackAlpha <= 0.004 && trainAlpha <= 0.004) return null;

        double portalPulse = trainActive ? Math.sin(sat((sHead - 0.80) / 0.24) * Math.PI) * envAlpha : 0.0;
        return new RailTransitState(preset, cycleIdx, tau, sFront, sErase, sHead, trainRaw, trainActive,
                trainAlpha * envAlpha, trackAlpha, portalPulse, envAlpha);
    }

    private static double[] spine(double z, double u, double px, double py, double straighten) {
        double d = Math.max(0.0, z) / 2900.0;
        double k = 1.0 - straighten;
        double sx = (Math.sin(u * 0.44) * 195.0 * d * d + Math.cos(u * 0.25) * 78.0 * d) * k + px * 115.0 * d * k;
        double sy = (Math.cos(u * 0.38) * 128.0 * d * d + Math.sin(u * 0.22) * 52.0 * d) * k - py * 82.0 * d * k;
        return new double[]{sx, sy};
    }

    private static double wallTwist(double z, double u) {
        return (z / 3400.0) * 0.26 + 0.06 * Math.sin(u * 0.30);
    }

    private static double[] evalPresetCore(RailPreset p, double s, double u, double px, double py, double straighten) {
        double k = 1.0 - straighten * 0.50;
        double t = sat(s);
        if (p.isBezier) {
            double it = 1.0 - t;
            double b0 = it * it * it, b1 = 3 * it * it * t, b2 = 3 * it * t * t, b3 = t * t * t;
            double z = b0 * p.p0[2] + b1 * p.p1[2] + b2 * p.p2[2] + b3 * p.p3[2];
            double[] sp = spine(z, u, px, py, straighten);
            double breath = Math.sin(t * Math.PI) * Math.sin(u * 0.42 + (p.p0[0] > 0 ? 1.2 : 0.0)) * 22.0 * k;
            double x = sp[0] + (b0 * p.p0[0] + b1 * p.p1[0] + b2 * p.p2[0] + b3 * p.p3[0]) * k + breath;
            double y = sp[1] + (b0 * p.p0[1] + b1 * p.p1[1] + b2 * p.p2[1] + b3 * p.p3[1]) * k;
            return new double[]{x, y, z};
        }
        double z = lerp(p.z0, p.z1, Math.pow(t, p.zPow));
        double[] sp = spine(z, u, px, py, straighten);
        double ang = p.ang0 + p.angSweep * t + p.angWave * Math.sin(t * Math.PI) + 0.04 * Math.sin(u * 0.34) * (1.0 - t);
        double r = (p.r0 * Math.pow(1.0 - t, p.rPow) + p.rBulge * Math.sin(t * Math.PI) * (1.0 - t)) * k;
        double x = sp[0] + Math.cos(ang) * r * p.ax;
        double y = sp[1] + Math.sin(ang) * r * p.ay + p.yOff * (1.0 - t) * k;
        return new double[]{x, y, z};
    }

    private static double[] railPoint(RailPreset p, double s, double u, double px, double py, double straighten) {
        if (s < 0.0) {
            double eps = 0.006;
            double[] p0 = evalPresetCore(p, 0.0, u, px, py, straighten);
            double[] p1 = evalPresetCore(p, eps, u, px, py, straighten);
            double sc = s / eps;
            return new double[]{p0[0] + (p1[0] - p0[0]) * sc, p0[1] + (p1[1] - p0[1]) * sc, p0[2] + (p1[2] - p0[2]) * sc};
        }
        if (s > 1.0) {
            double eps = 0.006;
            double[] p1 = evalPresetCore(p, 1.0, u, px, py, straighten);
            double[] p0 = evalPresetCore(p, 1.0 - eps, u, px, py, straighten);
            double sc = (s - 1.0) / eps;
            return new double[]{p1[0] + (p1[0] - p0[0]) * sc, p1[1] + (p1[1] - p0[1]) * sc, p1[2] + (p1[2] - p0[2]) * sc};
        }
        return evalPresetCore(p, s, u, px, py, straighten);
    }

    private static RailFrame railFrame(RailPreset p, double s, double u, double px, double py, double straighten) {
        double h = 0.008;
        double[] pos = railPoint(p, s, u, px, py, straighten);
        double[] p0 = railPoint(p, s - h, u, px, py, straighten);
        double[] p1 = railPoint(p, s + h, u, px, py, straighten);
        double dx = p1[0] - p0[0], dy = p1[1] - p0[1], dz = p1[2] - p0[2];
        double dLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dLen < 1e-6) dLen = 1.0;
        double[] tangent = {dx / dLen, dy / dLen, dz / dLen};
        double ddx = (p1[0] - 2.0 * pos[0] + p0[0]) / (h * h);
        double bankX = clamp(-ddx * 0.00013, -0.27, 0.27);
        double sx = -tangent[2];
        double sy = tangent[2] * bankX;
        double sz = tangent[0] - tangent[1] * bankX;
        double sLen = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sLen < 1e-6) sLen = 1.0;
        double[] side = {sx / sLen, sy / sLen, sz / sLen};
        double[] down = {
                side[1] * tangent[2] - side[2] * tangent[1],
                side[2] * tangent[0] - side[0] * tangent[2],
                side[0] * tangent[1] - side[1] * tangent[0]
        };
        return new RailFrame(pos, tangent, side, down, dLen / (2.0 * h));
    }

    private static TrackSample[] samplePresetTrack(RailPreset preset, double u, double px, double py,
                                                   double straighten, int segs) {
        TrackSample[] samples = new TrackSample[segs + 1];
        double accArc = 0.0;
        for (int i = 0; i <= segs; i++) {
            double s = i / (double) segs;
            RailFrame fr = railFrame(preset, s, u, px, py, straighten);
            if (i > 0) {
                double[] prev = samples[i - 1].fr.pos;
                double ddx = fr.pos[0] - prev[0], ddy = fr.pos[1] - prev[1], ddz = fr.pos[2] - prev[2];
                accArc += Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            }
            samples[i] = new TrackSample(s, fr, accArc);
        }
        return samples;
    }

    private static double trackPresenceAt(double s, double sFront, double sErase) {
        return (1.0 - sstep(sFront - 0.10, sFront + 0.015, s))
                * sstep(sErase - 0.02, sErase + 0.18, s)
                * sstep(0.0, 0.11, s)
                * sstep(0.955, 0.835, s);
    }

    private static double gaugeScaleAt(double s) {
        return lerp(1.0, 0.34, eInCubic(sat((s - 0.72) / 0.24)));
    }

    private static double gravScaleAt(double s) {
        return lerp(1.0, 0.34, eInCubic(sat((s - 0.74) / 0.22)));
    }

    private static double beamLiftAt(double s, RailTransitState st) {
        if (!st.trainActive || st.trainAlpha <= 0.01) return 0.0;
        double d = s - st.sHead;
        if (d < -0.05 || d > 0.22) return 0.0;
        return sstep(-0.05, 0.02, d) * (1.0 - sstep(0.04, 0.22, d)) * st.trainAlpha;
    }

    private static double appleHalfWidthAtY(double y) {
        if (y <= -166.0 || y >= 170.0) return 26.0;
        if (y < -40.0) return lerp(58.0, 168.0, Math.pow(sat((y + 168.0) / 128.0), 0.52));
        return lerp(168.0, 48.0, Math.pow(sat((y + 40.0) / 210.0), 1.35));
    }

    // ── Near-Plane Clipping & Projection Helpers ─────────────────────────────

    private static double @Nullable [] doorQuad(Cam cam, double carZ, double doorW) {
        double hw = doorW * 0.5, hh = CAR_DOOR_H * 0.5;
        double[] p0 = cam.p(cam.v(-hw, -hh, carZ));
        double[] p1 = cam.p(cam.v(hw, -hh, carZ));
        double[] p2 = cam.p(cam.v(hw, hh, carZ));
        double[] p3 = cam.p(cam.v(-hw, hh, carZ));
        if (p0 == null || p1 == null || p2 == null || p3 == null) return null;
        return new double[]{p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]};
    }

    private static double @Nullable [] projectPlaneRect(Cam cam, double[] O, double[] M,
                                                        double x, double y, double w, double h) {
        double ux0 = M[0], ux1 = M[3], ux2 = M[6];
        double uy0 = M[1], uy1 = M[4], uy2 = M[7];
        double[] p0 = cam.project(O[0] + ux0 * x + uy0 * y, O[1] + ux1 * x + uy1 * y, O[2] + ux2 * x + uy2 * y);
        double[] p1 = cam.project(O[0] + ux0 * (x + w) + uy0 * y, O[1] + ux1 * (x + w) + uy1 * y, O[2] + ux2 * (x + w) + uy2 * y);
        double[] p2 = cam.project(O[0] + ux0 * (x + w) + uy0 * (y + h), O[1] + ux1 * (x + w) + uy1 * (y + h), O[2] + ux2 * (x + w) + uy2 * (y + h));
        double[] p3 = cam.project(O[0] + ux0 * x + uy0 * (y + h), O[1] + ux1 * x + uy1 * (y + h), O[2] + ux2 * x + uy2 * (y + h));
        if (p0 == null || p1 == null || p2 == null || p3 == null) return null;
        return new double[]{p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]};
    }

    private static double[] @Nullable [] clipNearSeg(double[] a, double[] b) {
        double zn = -FOCAL + 60.0;
        if (a[2] < zn && b[2] < zn) return null;
        if (a[2] >= zn && b[2] >= zn) return new double[][]{a, b};
        double k = (zn - a[2]) / (b[2] - a[2]);
        double[] m = {lerp(a[0], b[0], k), lerp(a[1], b[1], k), zn};
        return a[2] < zn ? new double[][]{m, b} : new double[][]{a, m};
    }

    private static void drawNearSeg(HabiKineticGfx gfx, Cam cam, double[] a, double[] b, double lw, int argb) {
        double[][] s = clipNearSeg(a, b);
        if (s == null) return;
        double[] pa = cam.p(s[0]), pb = cam.p(s[1]);
        if (pa != null && pb != null) gfx.drawLine(pa[0], pa[1], pb[0], pb[1], lw, argb);
    }

    private static double @Nullable [] projNearPoly(Cam cam, double[]... pts) {
        double zn = -FOCAL + 60.0;
        List<double[]> out = new ArrayList<>(pts.length + 2);
        for (int i = 0; i < pts.length; i++) {
            double[] a = pts[i], b = pts[(i + 1) % pts.length];
            boolean ina = a[2] >= zn, inb = b[2] >= zn;
            if (ina) out.add(a);
            if (ina != inb) {
                double k = (zn - a[2]) / (b[2] - a[2]);
                out.add(new double[]{lerp(a[0], b[0], k), lerp(a[1], b[1], k), zn});
            }
        }
        if (out.size() < 3) return null;
        double[] scr = new double[out.size() * 2];
        for (int i = 0; i < out.size(); i++) {
            double[] p = cam.p(out.get(i));
            if (p == null) return null;
            scr[i * 2] = p[0];
            scr[i * 2 + 1] = p[1];
        }
        return scr;
    }

    private static void fillNearGradQuad(HabiKineticGfx gfx, Cam cam, double[] a0, double[] a1, double[] b1, double[] b0,
                                         int colA, int colB, BlendMode mode) {
        double[][] sL = clipNearSeg(a0, b0);
        double[][] sR = clipNearSeg(a1, b1);
        if (sL == null || sR == null) return;
        double[] p0 = cam.p(sL[0]), p1 = cam.p(sL[1]), p2 = cam.p(sR[1]), p3 = cam.p(sR[0]);
        if (p0 == null || p1 == null || p2 == null || p3 == null) return;
        gfx.fillQuadColored(p0[0], p0[1], colA, p1[0], p1[1], colB, p2[0], p2[1], colB, p3[0], p3[1], colA, mode);
    }

    private static double[] ringPt(Cam cam, double[] C, double[] R, double ang, double r) {
        return cam.v(va(C, mv(R, new double[]{Math.cos(ang) * r, Math.sin(ang) * r, 0.0})));
    }

    private static boolean isBack(double[] M, double ang) {
        return (M[6] * Math.cos(ang) + M[7] * Math.sin(ang)) > 0.0;
    }

    private static double[] ringWorld(double[] C, double[] M, double ang, double rr, double hz) {
        double lx = rr * Math.cos(ang), ly = rr * Math.sin(ang);
        return new double[]{
                C[0] + M[0] * lx + M[1] * ly + M[2] * hz,
                C[1] + M[3] * lx + M[4] * ly + M[5] * hz,
                C[2] + M[6] * lx + M[7] * ly + M[8] * hz
        };
    }

    private int pushSeg(Cam cam, double ax, double ay, double az, double bx, double by, double bz, int n) {
        if (n * 4 + 4 > segScratch.length) return n;
        double[] pa = cam.project(ax, ay, az);
        double[] pb = cam.project(bx, by, bz);
        if (pa == null || pb == null) return n;
        int p = n * 4;
        segScratch[p] = pa[0];
        segScratch[p + 1] = pa[1];
        segScratch[p + 2] = pb[0];
        segScratch[p + 3] = pb[1];
        return n + 1;
    }

    // ── Initialization of Apple Contour, Particle Pools & Rail Presets ───────

    private void initAppleContour() {
        double[][] segs = {
                {0, -118, 38, -160, 168, -162, 168, -28},
                {168, -28, 168, 92, 96, 172, 44, 166},
                {44, 166, 22, 164, 12, 154, 0, 154},
                {0, 154, -12, 154, -22, 164, -44, 166},
                {-44, 166, -96, 172, -168, 92, -168, -28},
                {-168, -28, -168, -162, -38, -160, 0, -118}
        };
        int perSeg = APPLE_PTS / 6;
        int idx = 0;
        for (double[] s : segs) {
            for (int k = 0; k < perSeg; k++) {
                double t = k / (double) perSeg;
                double it = 1.0 - t;
                double b0 = it * it * it, b1 = 3 * it * it * t, b2 = 3 * it * t * t, b3 = t * t * t;
                appleBaseXY[idx * 2] = b0 * s[0] + b1 * s[2] + b2 * s[4] + b3 * s[6];
                appleBaseXY[idx * 2 + 1] = b0 * s[1] + b1 * s[3] + b2 * s[5] + b3 * s[7];
                idx++;
            }
        }
    }

    private void initPools() {
        int[] seed1 = {20261126};
        for (int i = 0; i < 76; i++) {
            int col = (i % 9 == 0) ? PAL_RED_BRIGHT : (i % 3 == 0 ? PAL_BRASS_LIGHT : (i % 5 == 0 ? 0xC8DCFF : PAL_CHAMPAGNE));
            stars.add(new StarPt(
                    nextRnd(seed1) * 4400.0,
                    nextRnd(seed1) * TAU,
                    175.0 + nextRnd(seed1) * 820.0,
                    0.68 + nextRnd(seed1) * 0.62,
                    0.85 + nextRnd(seed1) * 2.1,
                    i % 7 == 0,
                    col
            ));
        }

        int[][] seq = {
                {0, 0}, {0, 4}, {0, 2}, {0, 9}, {1, 0}, {0, 7}, {0, 1},
                {0, 8}, {0, 3}, {0, 10}, {0, 6}, {1, 1}, {0, 5}, {0, 11}
        };
        double[] gaps = {1420, 2380, 2120, 1340, 2520, 2240, 1480, 2460, 2180, 1360, 2560, 2280, 2440, 2320};
        double[] sectors = {-2.62, -0.22, 2.82, -0.72, -2.12, 0.24, -2.96, -0.46, 2.68, -1.04, -2.42, 0.12, -1.96, -0.62};
        double accZ = 3360.0;
        for (int i = 0; i < seq.length; i++) {
            boolean isTicket = seq[i][0] == 1;
            double ang0 = sectors[i % sectors.length] + (nextRnd(seed1) - 0.5) * 0.16;
            double rFar = 225.0 + nextRnd(seed1) * 130.0;
            double rNear = 320.0 + nextRnd(seed1) * 165.0;
            tunnelItems.add(new FloatingDef(
                    i, isTicket, seq[i][1], accZ, ang0,
                    (i % 2 == 0 ? 1 : -1) * (0.26 + nextRnd(seed1) * 0.28),
                    rFar, rNear,
                    (nextRnd(seed1) - 0.5) * 105.0,
                    (nextRnd(seed1) - 0.5) * 72.0,
                    (0.42 + nextRnd(seed1) * 0.26) * (i % 2 == 0 ? 1 : -1),
                    (0.22 + nextRnd(seed1) * 0.20) * (i % 3 == 0 ? -1 : 1),
                    (nextRnd(seed1) - 0.5) * 0.52,
                    (i % 2 == 0 ? -1 : 1) * (0.24 + nextRnd(seed1) * 0.22),
                    (nextRnd(seed1) - 0.5) * 0.30,
                    0.46 + nextRnd(seed1) * 0.30,
                    isTicket ? (0.76 + nextRnd(seed1) * 0.12) : (0.80 + nextRnd(seed1) * 0.16),
                    i % 2 == 0 ? 1 : -1,
                    isTicket ? (14.0 + nextRnd(seed1) * 7.0) : (18.0 + nextRnd(seed1) * 9.0),
                    isTicket ? (14.0 + nextRnd(seed1) * 7.0) : (17.0 + nextRnd(seed1) * 9.0),
                    1.65 + nextRnd(seed1) * 0.85,
                    nextRnd(seed1) * TAU
            ));
            accZ += gaps[i];
        }
        this.stampSpanZ = accZ;

        int[] seed2 = {20261127};
        for (int i = 0; i < 36; i++) {
            double tone = nextRnd(seed2);
            startBokeh.add(new BokehPt(
                    (nextRnd(seed2) - 0.5) * 5600.0,
                    -980.0 + nextRnd(seed2) * 1180.0,
                    1250.0 + nextRnd(seed2) * 1800.0,
                    12.0 + nextRnd(seed2) * 34.0,
                    0.05 + nextRnd(seed2) * 0.11,
                    tone < 0.58 ? 0 : (tone < 0.80 ? 1 : 2),
                    (nextRnd(seed2) - 0.5) * 26.0,
                    nextRnd(seed2) * TAU,
                    0.36 + nextRnd(seed2) * 0.80
            ));
        }
        for (int i = 0; i < 70; i++) {
            startDust.add(new DustPt(
                    (nextRnd(seed2) - 0.5) * 3000.0,
                    -330.0 + nextRnd(seed2) * 430.0,
                    -420.0 + nextRnd(seed2) * 360.0,
                    0.6 + nextRnd(seed2) * 1.5,
                    5.0 + nextRnd(seed2) * 14.0,
                    nextRnd(seed2) * TAU
            ));
        }
    }

    private void initRailPresets() {
        railPresets.add(new RailPreset(false, 560, 3260, 1.12, 2.36, -3.28, -0.18, 660, 0.96, 165, 1.18, 0.68, -12, null, null, null, null));
        railPresets.add(new RailPreset(true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new double[]{830, -310, 560}, new double[]{410, -470, 1280}, new double[]{-95, -245, 2320}, new double[]{0, 0, 3260}));
        railPresets.add(new RailPreset(false, 560, 3260, 1.12, 0.78, 3.24, 0.18, 660, 0.96, 160, 1.18, 0.68, -12, null, null, null, null));
        railPresets.add(new RailPreset(false, 560, 3260, 1.12, -2.40, 3.18, 0.16, 670, 0.96, 155, 1.20, 0.66, 18, null, null, null, null));
        railPresets.add(new RailPreset(true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new double[]{-840, 210, 560}, new double[]{-420, 425, 1280}, new double[]{105, 215, 2320}, new double[]{0, 0, 3260}));
        railPresets.add(new RailPreset(false, 560, 3260, 1.12, -0.22, -3.45, -0.14, 685, 0.96, 150, 1.22, 0.68, 14, null, null, null, null));
    }

    private static double nextRnd(int[] state) {
        int s = state[0] + 0x6D2B79F5;
        state[0] = s;
        int x = (s ^ (s >>> 15)) * (1 | s);
        x = (x + (x ^ (x >>> 7)) * (61 | x)) ^ x;
        return ((x ^ (x >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }
}
