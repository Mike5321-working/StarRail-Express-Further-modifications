package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.habitrain.core.client.title.HabiKineticGfx.*;

/**
 * Complete 2.5D Kinetic Flat 3D renderer for Station 00 (boot calibration, real resource loading,
 * world generation / level entry, 100% lock &amp; boarding flip, and diagonal departure curtain),
 * ported faithfully from {@code habitrain_logo_v3/scene.js}.
 */
@Environment(EnvType.CLIENT)
public final class HabiZeroStationRenderer {

    // ── Timeline constants (100 BPM: 1 beat = 0.60s) ─────────────────────────
    public static final double TL_CROSS = 0.12;
    public static final double TL_POLY = 0.32;
    public static final double TL_LAY = 0.48;
    public static final double TL_WIPE = 1.12;
    public static final double TL_WIPE_DUR = 0.44;
    public static final double TL_BANDS = 1.24;
    public static final double TL_LETTERS = 1.62;
    public static final double TL_LEAF = 1.80;
    public static final double TL_TAGS = 2.02;
    public static final double TL_TSTAMP = 2.08;
    public static final double TL_IMPACT = 2.18;
    public static final double TL_SUB = 2.30;
    public static final double TL_CARDS = 2.30;
    public static final double TL_LABELS = 2.45;
    public static final double TL_PROMPT = 2.55;
    public static final double TL_IDLE = 3.0;
    public static final double TL_LOOP = 4.8;

    // ── Right vertical stamp & brand copy ────────────────────────────────────
    static final String BRAND_CN = "苹果列车";
    static final double RIGHT_STAMP_COL1_INSET = 42.0;
    static final double RIGHT_STAMP_COL2_INSET = 72.0;
    static final double RIGHT_STAMP_BAR_INSET = 50.0;
    private static final double CARD_B_BASE_X = 708.0;
    private static final double CARD_B_MIN_X = 540.0;

    static double computeCardBWorldX(double viewRight) {
        double narrowInset = Math.max(0.0, W - viewRight);
        return Math.max(CARD_B_MIN_X, CARD_B_BASE_X - narrowInset * 0.70);
    }

    // ── World layout constants (1920x1080 virtual pixels) ────────────────────
    private static final double EMB_Y = -182.0;
    private static final double RING_Y = -150.0;
    private static final double RING_R = 316.0;
    private static final double RING_TILT = -1.22;
    private static final double RING_RZ = -0.10;
    private static final double OCTA_Z = 260.0;
    private static final double OCTA_R = 236.0;
    private static final double TITLE_Y = 232.0;
    private static final double TAG_Y = 322.0;
    private static final double SUB_Y = 378.0;
    private static final double PROMPT_Y = 436.0;
    private static final double SLANT = 0.32;
    private static final double TH0 = 2.1;
    private static final double CAR_H = 16.0;
    private static final double CAR_GAP = 62.0;

    private static final double[][] OCTA_V = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int[][] OCTA_E = {
            {0, 2}, {0, 3}, {0, 4}, {0, 5},
            {1, 2}, {1, 3}, {1, 4}, {1, 5},
            {2, 4}, {2, 5}, {3, 4}, {3, 5}
    };

    private static final int C_PAPER_TXT = 0xFFF4F1EA;

    private record Palette(
            boolean paper,
            int bg,
            int ink,
            int line,
            int faint,
            int ghost,
            int mute,
            int red,
            int redDeep,
            int blue,
            int text,
            double shadowA,
            double grainA
    ) {}

    private static final Palette PAPER = new Palette(
            true,
            0xFFECE9E2,
            0xFF16171D,
            0xD116171D,
            0x2116171D,
            0x0B16171D,
            0xFF8C8981,
            0xFFE1243B,
            0xFF8E1323,
            0xFF2D4FE3,
            0xFF16171D,
            0.10,
            0.075
    );

    private static final Palette DARK = new Palette(
            false,
            0xFF0E1017,
            0xFFECE9E2,
            0xC7ECE9E2,
            0x1AECE9E2,
            0x09ECE9E2,
            0xFF5F6271,
            0xFFEE3346,
            0xFF8E1323,
            0xFF5B78FF,
            0xFFECE9E2,
            0.0,
            0.06
    );

    public record RenderParams(
            double t,
            double exitAt,
            double pointerX,
            double pointerY,
            HabiRealProgressTracker.LoadSnapshot load,
            boolean quantizePending,
            boolean autoEnter,
            @Nullable HabiBootAudioEngine.Telemetry audioTel,
            boolean worldMode,
            @Nullable String worldBanner
    ) {}

    public record FrameResult(boolean gameOn, boolean splitOpen, double stationU, double gameK, boolean done) {}

    private static final class FrameState {
        Cam cam;
        double t, e, ti, idleB, lp;
        RenderParams params;

        double E(double a, double b) {
            return e < 0.0 ? 0.0 : sat((e - a) / (b - a));
        }
    }

    private final double[] segScratch = new double[1024];
    private final double[] polyScratch = new double[512];

    /**
     * Renders Station 00 (excluding the split curtain if caller needs Station 01 rendered beneath
     * the curtain first — call {@link #renderExitPanels} after rendering Station 01!).
     */
    public FrameResult renderZeroLayer(HabiKineticGfx gfx, HabiKineticAtlas atlas, RenderParams p, boolean drawExitInline) {
        double t = p.t();
        double e = t >= p.exitAt() ? (t - p.exitAt()) : -1.0;

        boolean gameOn = e >= HabiBootSession.COVER_FULL;
        boolean splitOpen = e >= HabiBootSession.SPLIT_AT;
        double stationU = e < HabiBootSession.COVER_FULL ? -1.0 : (e - HabiBootSession.SPLIT_AT);
        double gameK = e < HabiBootSession.SPLIT_AT ? 0.0 : sat((e - (HabiBootSession.SPLIT_AT + 0.05)) / 0.72);
        boolean done = e >= HabiBootSession.EXIT_DUR;

        if (done) {
            return new FrameResult(true, true, stationU, 1.0, true);
        }

        FrameState st = computeState(t, e, p);

        if (e < HabiBootSession.COVER_FULL) {
            double wk = sat((t - TL_WIPE) / TL_WIPE_DUR);
            if (wk <= 0.0) {
                renderScene(gfx, atlas, DARK, st);
            } else if (wk >= 1.0) {
                renderScene(gfx, atlas, PAPER, st);
            } else {
                // Opening slanted red wipe flipping DARK -> PAPER
                double X = lerp(-480.0, 2560.0, eInOutCubic(wk));
                double Xt = X - 430.0;

                gfx.beginDepthClipPolygon(slantPoly(-5000.0, Xt));
                renderScene(gfx, atlas, PAPER, st);
                gfx.endDepthClip();

                gfx.beginDepthClipPolygon(slantPoly(X, 6000.0));
                renderScene(gfx, atlas, DARK, st);
                gfx.endDepthClip();

                gfx.fillConvexPolygon(PAPER.red, slantPoly(Xt, X));
                gfx.fillConvexPolygon(PAPER.ink, slantPoly(Xt - 22.0, Xt - 12.0));
                gfx.fillConvexPolygon(C_PAPER_TXT, slantPoly(X + 26.0, X + 32.0));

                // Slanted rotated kinetic typography inside the red wipe band
                gfx.beginDepthClipPolygon(slantPoly(Xt, X));
                double angle = -(Math.PI * 0.5 - Math.atan(SLANT));
                double bx = X - 215.0;
                double cos = Math.cos(angle), sin = Math.sin(angle);
                double tx1 = -900.0 + wk * 500.0, ty1 = 8.0;
                atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, "HABITRAIN HABITRAIN",
                        bx + tx1 * cos - ty1 * sin, CY + tx1 * sin + ty1 * cos,
                        120.0, 10.0, 0x29F4F1EA, -1, angle);
                double tx2 = -640.0 - wk * 300.0, ty2 = -150.0;
                atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "00 // ASSEMBLE // ASSEMBLE // ASSEMBLE // 00",
                        bx + tx2 * cos - ty2 * sin, CY + tx2 * sin + ty2 * cos,
                        13.0, 5.0, C_PAPER_TXT, -1, angle);
                gfx.endDepthClip();
            }
        }

        if (drawExitInline && e >= 0.0) {
            renderExitPanels(gfx, atlas, e);
        }

        // Opening 0.32s black fade-in
        double fin = 1.0 - sstep(0.0, 0.32, t);
        if (fin > 0.002) {
            gfx.fillRect(gfx.viewLeft() - 10, gfx.viewTop() - 10, gfx.viewWidth() + 20, gfx.viewHeight() + 20,
                    withAlpha(0x000000, fin));
        }

        return new FrameResult(gameOn, splitOpen, stationU, gameK, false);
    }

    /** Renders the red/ink slanted departure curtain &amp; -24° diagonal split panels on top of Station 01. */
    public void renderExitPanels(HabiKineticGfx gfx, HabiKineticAtlas atlas, double e) {
        if (e < 0.0 || e >= HabiBootSession.EXIT_DUR) return;

        if (e < HabiBootSession.COVER_FULL) {
            double Xr = lerp(2380.0, -660.0, eInOutCubic(sat((e - 0.32) / 0.40)));
            double Xi = lerp(2380.0, -660.0, eInOutCubic(sat((e - 0.46) / 0.44)));
            gfx.fillConvexPolygon(PAPER.ink, slantPoly(Xr - 40.0, Xr - 26.0));
            gfx.fillConvexPolygon(PAPER.red, slantPoly(Xr, Xi));
            if (Xi - Xr > 4.0) {
                gfx.beginDepthClipPolygon(slantPoly(Xr, Xi));
                atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, "DEPARTURE",
                        Xr * 0.55 + 380.0, CY + 20.0, 330.0, 12.0, 0xF2F4F1EA, -1, 0.0);
                atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "HABITRAIN // DEPARTURE // STATION 01 >>>>",
                        Xr + 120.0, CY + 200.0, 14.0, 5.0, PAPER.ink, -1, 0.0);
                gfx.endDepthClip();
            }
            gfx.fillConvexPolygon(PAPER.ink, slantPoly(Xi, 7000.0));
            return;
        }

        double preSplit = sat((e - HabiBootSession.COVER_FULL) / (HabiBootSession.SPLIT_AT - HabiBootSession.COVER_FULL));
        double k = e < HabiBootSession.SPLIT_AT ? 0.0 : eOutExpo(sat((e - HabiBootSession.SPLIT_AT) / 0.62));
        double d = e < HabiBootSession.SPLIT_AT ? (2.5 * preSplit * preSplit * preSplit) : (2.5 + 1550.0 * k);
        double ang = -0.42;
        double ux = Math.cos(ang), uy = Math.sin(ang);
        double nx = -Math.sin(ang), ny = Math.cos(ang);
        double kt = sat((e - 0.88) / 0.16);
        double seamLen = 3000.0 * eOutCubic(sat((e - 0.88) / 0.22));
        double lineW = e < HabiBootSession.SPLIT_AT ? (3.0 + 4.0 * preSplit) : 6.0;

        for (int sgn : new int[]{-1, 1}) {
            double cx = CX + nx * d * sgn;
            double cy = CY + ny * d * sgn;
            double[] half = {
                    cx - ux * 3000.0, cy - uy * 3000.0,
                    cx + ux * 3000.0, cy + uy * 3000.0,
                    cx + ux * 3000.0 + nx * 3000.0 * sgn, cy + uy * 3000.0 + ny * 3000.0 * sgn,
                    cx - ux * 3000.0 + nx * 3000.0 * sgn, cy - uy * 3000.0 + ny * 3000.0 * sgn
            };
            gfx.fillConvexPolygon(PAPER.ink, half);
            gfx.beginDepthClipPolygon(half);

            gfx.drawLine(cx - ux * seamLen, cy - uy * seamLen, cx + ux * seamLen, cy + uy * seamLen, lineW, PAPER.red);
            gfx.drawLine(
                    cx - ux * seamLen + nx * 14.0 * sgn, cy - uy * seamLen + ny * 14.0 * sgn,
                    cx + ux * seamLen + nx * 14.0 * sgn, cy + uy * seamLen + ny * 14.0 * sgn,
                    1.5, 0x73F4F1EA);

            if (kt > 0.01) {
                if (sgn < 0) {
                    double px1 = cx + 8.0 * ux - (-58.0) * uy;
                    double py1 = cy + 8.0 * uy + (-58.0) * ux;
                    atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, "欢迎登车",
                            px1, py1, 46, 16f, withAlpha(C_PAPER_TXT, kt), 0, ang, 0);
                    double px2 = cx - (-108.0) * uy;
                    double py2 = cy + (-108.0) * ux;
                    atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO,
                            typed("STATION 01 // ARRIVED // 100 BPM SYNC", kt * 1.2, Math.floor(e * 30.0)),
                            px2, py2, 13.0, 5.0, withAlpha(PAPER.mute, kt), 0, ang);
                } else {
                    double px3 = cx - 42.0 * uy;
                    double py3 = cy + 42.0 * ux;
                    atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO,
                            typed("NEXT // HABIT TERMINAL >>>>", kt * 1.2, Math.floor(e * 30.0) + 5.0),
                            px3, py3, 13.0, 5.0, withAlpha(PAPER.red, kt), 0, ang);
                }
            }
            gfx.endDepthClip();
        }
    }

    private FrameState computeState(double t, double e, RenderParams p) {
        FrameState st = new FrameState();
        st.t = t;
        st.e = e;
        st.params = p;
        st.ti = t - TL_IDLE;
        st.idleB = sstep(2.3, 3.8, t);
        st.lp = t < TL_IDLE ? -1.0 : (((t - TL_IDLE) % TL_LOOP) + TL_LOOP) % TL_LOOP;

        double intro = eInOutCubic(sat(t / 2.7));
        double rx = lerp(0.20, 0.0, intro) + st.idleB * 0.035 * Math.sin(st.ti * 0.33 + 0.6) - p.pointerY() * 0.04;
        double ry = lerp(-0.36, 0.0, intro) + st.idleB * 0.085 * Math.sin(st.ti * 0.42) + p.pointerX() * 0.07;
        double rz = lerp(0.05, 0.0, intro);
        double zoom = lerp(1.16, 1.0, eOutCubic(t / 2.5));
        if (t > TL_IMPACT) zoom += 0.032 * Math.exp(-(t - TL_IMPACT) * 7.0);
        double ex = eInCubic(st.E(0.12, 0.80));
        zoom *= (1.0 - 0.03 * Math.sin(Math.PI * st.E(0.0, 0.22))) * (1.0 + 0.32 * ex);
        rz += -0.07 * ex;
        st.cam = new Cam(rx, ry, rz, zoom);
        return st;
    }

    private void renderScene(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        gfx.fillRect(gfx.viewLeft() - 20, gfx.viewTop() - 20, gfx.viewWidth() + 40, gfx.viewHeight() + 40, th.bg);
        drawBgGrid(gfx, th, st);
        drawGhost00(gfx, atlas, th, st);
        drawCrosshair(gfx, th, st);
        drawBigCircle(gfx, atlas, th, st);
        drawOcta(gfx, th, st);
        drawCards(gfx, atlas, th, st);
        drawRing(gfx, atlas, th, st, false);
        drawEmblem(gfx, atlas, th, st);
        drawRing(gfx, atlas, th, st, true);
        drawLabels(gfx, atlas, th, st);
        drawTitle(gfx, atlas, th, st);
        drawTags(gfx, atlas, th, st);
        drawPromptOrProgress(gfx, atlas, th, st);
        drawHud(gfx, atlas, th, st);
        drawPost(gfx, atlas, th, st);
    }

    private void drawBgGrid(HabiKineticGfx gfx, Palette th, FrameState st) {
        double a = sstep(0.05, 0.6, st.t) * (1.0 - st.E(0.1, 0.5));
        if (a <= 0.005) return;
        int n = 0;
        for (int gx = -6; gx <= 6; gx++) {
            for (int gy = -4; gy <= 4; gy++) {
                double[] c = st.cam.v(gx * 230.0, gy * 230.0, 900.0);
                n = pushSeg(st.cam, c[0] - 7, c[1], c[2], c[0] + 7, c[1], c[2], n);
                n = pushSeg(st.cam, c[0], c[1] - 7, c[2], c[0], c[1] + 7, c[2], n);
            }
        }
        gfx.drawSegments(segScratch, n, 1.0, scaleAlpha(th.faint, a));
    }

    private void drawGhost00(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        double a = sstep(1.3, 2.4, st.t) * (1.0 - st.E(0.0, 0.4));
        if (a <= 0.005) return;
        double drift = st.idleB * 12.0 * Math.sin(st.ti * 0.3);
        double[] M = mm(st.cam.R, RZ(-0.03));
        double[] O = st.cam.v(430.0 + drift, -120.0, 720.0);
        atlas.drawAsciiOnPlane(gfx, st.cam, O, M, HabiKineticAtlas.FontFace.DISPLAY,
                "00", 0, 0, 640.0, 10.0, scaleAlpha(th.ghost, a), 0);
    }

    private void drawCrosshair(HabiKineticGfx gfx, Palette th, FrameState st) {
        double k = eOutCubic(sat((st.t - TL_CROSS) / 0.55));
        if (k <= 0.002) return;
        double a = 1.0 - st.E(0.05, 0.4);
        double Lh = 1700.0 * k, Lv = 900.0 * k;
        int n = 0;
        double[] a1 = st.cam.v(-Lh, EMB_Y, OCTA_Z), b1 = st.cam.v(Lh, EMB_Y, OCTA_Z);
        double[] a2 = st.cam.v(0, EMB_Y - Lv, OCTA_Z), b2 = st.cam.v(0, EMB_Y + Lv, OCTA_Z);
        n = pushSeg(st.cam, a1[0], a1[1], a1[2], b1[0], b1[1], b1[2], n);
        n = pushSeg(st.cam, a2[0], a2[1], a2[2], b2[0], b2[1], b2[2], n);
        gfx.drawSegments(segScratch, n, 1.2, scaleAlpha(th.faint, a));

        double ks = eOutCubic(sat((st.t - 0.62) / 0.5));
        if (ks > 0.002) {
            double[] A = {-1300.0, 420.0, OCTA_Z + 80.0};
            double[] B = {1300.0, -760.0, OCTA_Z + 80.0};
            double[] pa = st.cam.p(st.cam.v(A));
            double[] pb = st.cam.p(st.cam.v(vl(A, B, ks)));
            if (pa != null && pb != null) {
                gfx.drawLine(pa[0], pa[1], pb[0], pb[1], 1.4, withAlpha(th.red, a * 0.55));
            }
        }
    }

    private void drawBigCircle(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        double k = eInOutCubic(sat((st.t - TL_CROSS - 0.05) / 0.7));
        if (k <= 0.002) return;
        double ke = st.E(0.05, 0.55);
        double a = 1.0 - ke;
        double sc = 1.0 + 1.6 * eInCubic(ke);
        double[] C = {0.0, EMB_Y, OCTA_Z};
        double[] R = euler(0.22 * Math.sin(st.t * 0.23) + 0.1, 0.30 * Math.sin(st.t * 0.17 + 1.0), 0.0);
        double r0 = 404.0;
        double a0 = -Math.PI * 0.5;

        // Main calibrated circle
        int steps = (int) Math.floor(120 * k);
        int pn = 0;
        for (int i = 0; i <= steps; i++) {
            double[] pt = st.cam.p(ringPt(st.cam, C, R, a0 + (i / 120.0) * TAU, r0 * sc));
            if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
        }
        gfx.drawPolyline(polyScratch, pn, 1.15, scaleAlpha(th.line, a), false);

        // 72 radial ticks
        int ticks = (int) Math.floor(72 * k);
        int sn = 0;
        for (int i = 0; i < ticks; i++) {
            double ang = a0 + (i / 72.0) * TAU;
            double len = (i % 6 == 0) ? 18.0 : 9.0;
            double[] p1 = ringPt(st.cam, C, R, ang, (r0 + 4.0) * sc);
            double[] p2 = ringPt(st.cam, C, R, ang, (r0 + len) * sc);
            sn = pushSeg(st.cam, p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], sn);
        }
        gfx.drawSegments(segScratch, sn, 1.0, scaleAlpha(th.faint, a));

        // Rotating red radar arc + orbiting dots
        if (k > 0.6) {
            double ka = sstep(0.6, 1.0, k);
            double s0 = st.t * 0.55 + 0.6;
            pn = 0;
            for (int i = 0; i <= 30; i++) {
                double[] pt = st.cam.p(ringPt(st.cam, C, R, s0 + (i / 30.0) * 0.95 * ka, (r0 + 14.0) * sc));
                if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
            }
            gfx.drawPolyline(polyScratch, pn, 3.2, withAlpha(th.red, a), false);

            double[] pd = st.cam.p(ringPt(st.cam, C, R, -st.t * 0.9 + 2.0, r0 * sc));
            if (pd != null) {
                gfx.fillCircle(pd[0], pd[1], 4.5, withAlpha(th.bg, a), 16);
                gfx.strokeCircle(pd[0], pd[1], 4.5, 1.4, scaleAlpha(th.line, a), 16);
            }
            double[] pd2 = st.cam.p(ringPt(st.cam, C, R, st.t * 0.4 + 4.1, r0 * sc));
            if (pd2 != null) {
                gfx.fillCircle(pd2[0], pd2[1], 4.0, withAlpha(th.red, a), 16);
            }
        }

        // Outer REAL loading progress arc (0% -> 100%) + 100% shockwave
        HabiRealProgressTracker.LoadSnapshot ld = st.params.load();
        double lp = ld != null ? sat(ld.progress()) : 1.0;
        if (k > 0.35 && lp > 0.005) {
            double ka = sstep(0.35, 0.85, k);
            double rLoad = r0 + 28.0;
            pn = 0;
            for (int i = 0; i <= 96; i++) {
                double[] pt = st.cam.p(ringPt(st.cam, C, R, a0 + (i / 96.0) * TAU, rLoad * sc));
                if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
            }
            gfx.drawPolyline(polyScratch, pn, 1.2, scaleAlpha(th.faint, a * ka * 0.75), false);

            int segs = Math.max(2, (int) Math.ceil(96 * lp));
            pn = 0;
            for (int i = 0; i <= segs; i++) {
                double ang = Math.min(a0 + lp * TAU, a0 + (i / (double) segs) * lp * TAU);
                double[] pt = st.cam.p(ringPt(st.cam, C, R, ang, rLoad * sc));
                if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
            }
            int arcColor = (ld != null && ld.ready()) ? th.ink : th.red;
            gfx.drawPolyline(polyScratch, pn, 2.6, withAlpha(arcColor, a * ka * 0.92), false);

            if (lp < 0.999) {
                double tipAng = a0 + lp * TAU;
                double[] pt = st.cam.p(ringPt(st.cam, C, R, tipAng, rLoad * sc));
                double[] ptLbl = st.cam.p(ringPt(st.cam, C, R, tipAng, (rLoad + 22.0) * sc));
                if (pt != null) {
                    gfx.fillCircle(pt[0], pt[1], 4.2, withAlpha(th.red, a * ka), 14);
                    if (ptLbl != null) {
                        String pct = String.format(Locale.ROOT, "%03d%%", (int) Math.round(lp * 100.0));
                        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, pct,
                                ptLbl[0], ptLbl[1], 10.0, 2.0, withAlpha(th.red, a * ka), 0, 0.0);
                    }
                }
            }

            if (ld != null && ld.ready() && ld.justLocked() > 0.0 && ld.justLocked() < 1.0) {
                double jk = ld.justLocked();
                double rWave = rLoad + 68.0 * eOutCubic(jk);
                pn = 0;
                for (int i = 0; i <= 96; i++) {
                    double[] pt = st.cam.p(ringPt(st.cam, C, R, a0 + (i / 96.0) * TAU, rWave * sc));
                    if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
                }
                gfx.drawPolyline(polyScratch, pn, 3.2 * (1.0 - jk), withAlpha(th.red, a * (1.0 - jk) * 0.85), false);
            }
        }
    }

    private static double[] ringPt(Cam cam, double[] C, double[] R, double ang, double r) {
        return cam.v(va(C, mv(R, new double[]{Math.cos(ang) * r, Math.sin(ang) * r, 0.0})));
    }

    private void drawOcta(HabiKineticGfx gfx, Palette th, FrameState st) {
        double k0 = sat((st.t - TL_POLY) / 0.9);
        if (k0 <= 0.002) return;
        double ke = st.E(0.0, 0.6);
        double a = 1.0 - sstep(0.3, 1.0, ke);
        if (a <= 0.003) return;
        double sc = lerp(0.55, 1.0, eOutBack(k0, 1.4)) * (1.0 + 2.4 * eInCubic(ke));
        double spin = -5.0 * (1.0 - eOutCubic(sat((st.t - TL_POLY) / 1.7)));
        double[] R = euler(0.42 + 0.05 * Math.sin(st.t * 0.3), 0.30 * st.t + spin, 0.08 * Math.sin(st.t * 0.21));
        double[] C = {0.0, EMB_Y, OCTA_Z};
        double[][] V = new double[OCTA_V.length][];
        for (int i = 0; i < OCTA_V.length; i++) {
            double[] lv = {OCTA_V[i][0] * OCTA_R * sc, OCTA_V[i][1] * OCTA_R * 1.34 * sc, OCTA_V[i][2] * OCTA_R * sc};
            V[i] = st.cam.v(va(C, mv(R, lv)));
        }
        double zc = st.cam.v(C)[2];

        for (int j = 0; j < OCTA_E.length; j++) {
            double p = eOutCubic(sat((st.t - TL_POLY - j * 0.035) / 0.32));
            if (p <= 0.002) continue;
            double[] A = V[OCTA_E[j][0]];
            double[] B = vl(V[OCTA_E[j][0]], V[OCTA_E[j][1]], p);
            double[] pa = st.cam.p(A), pb = st.cam.p(B);
            if (pa == null || pb == null) continue;
            double dz = ((A[2] + B[2]) * 0.5 - zc) / (OCTA_R * sc);
            double edgeA = a * lerp(1.0, 0.32, (dz + 1.0) * 0.5);
            boolean redEdge = (j % 4 == 0);
            gfx.drawLine(pa[0], pa[1], pb[0], pb[1], redEdge ? 2.0 : 1.4,
                    redEdge ? withAlpha(th.red, edgeA) : scaleAlpha(th.line, edgeA));
        }

        for (int i = 0; i < V.length; i++) {
            double[] pt = st.cam.p(V[i]);
            if (pt == null) continue;
            double kp = eOutBack(sat((st.t - TL_POLY - 0.3 - i * 0.04) / 0.25), 2.0);
            if (kp <= 0.01) continue;
            double s = 4.5 * kp;
            if (i == 2 || i == 4) {
                gfx.fillRect(pt[0] - s, pt[1] - s, s * 2.0, s * 2.0, withAlpha(th.red, a));
            } else {
                gfx.fillRect(pt[0] - s, pt[1] - s, s * 2.0, s * 2.0, withAlpha(th.bg, a));
                gfx.strokeRect(pt[0] - s, pt[1] - s, s * 2.0, s * 2.0, 1.3, scaleAlpha(th.line, a));
            }
        }
    }

    private void drawCards(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        record CardDef(HabiKineticAtlas.Slab tx, double[] p, double[] r, double d, int dir, int idx) {}
        record CardItem(CardDef def, double[] O, double[] M, double a) {}

        double cardBX = computeCardBWorldX(gfx.viewRight());
        CardDef[] defs = {
                new CardDef(atlas.cardA, new double[]{-660, -250, 180}, new double[]{0.02, 0.28, -0.05}, 6, -1, 0),
                new CardDef(atlas.cardB, new double[]{cardBX, -40, 120}, new double[]{0.0, -0.32, 0.06}, 10, 1, 1),
                new CardDef(atlas.cardC, new double[]{-720, 128, 330}, new double[]{0.12, 0.36, 0.0}, 0, -1, 2),
                new CardDef(atlas.cardD, new double[]{652, 262, 60}, new double[]{0.0, -0.22, -0.03}, 0, 1, 3)
        };

        List<CardItem> items = new ArrayList<>(4);
        for (CardDef c : defs) {
            double[] O = new double[3], M = new double[9];
            double a = cardPose(st, c.p, c.r, c.dir, c.idx, st.t, O, M);
            if (a > 0.003) items.add(new CardItem(c, O, M, a));
        }
        items.sort((x, y) -> Double.compare(y.O[2], x.O[2]));

        double kFast = sat((st.t - TL_CARDS) / 0.9);
        for (CardItem it : items) {
            if (kFast < 1.0 || st.e > 0.0) {
                double[] gO = new double[3], gM = new double[9];
                for (int g = 3; g >= 1; g--) {
                    double ga = cardPose(st, it.def.p, it.def.r, it.def.dir, it.def.idx, st.t - g * 0.02, gO, gM);
                    if (ga > 0.003) {
                        gfx.drawSlab(st.cam, gO, gM, it.def.tx, 0.0, ga * 0.14, 0.0, true, false);
                    }
                }
            }
            gfx.drawSlab(st.cam, it.O, it.M, it.def.tx, it.def.d, it.a,
                    it.def.d > 0 ? th.shadowA * 0.8 : 0.0, false, false);
        }
    }

    private double cardPose(FrameState st, double[] p, double[] r, int dir, int j, double tt, double[] outO, double[] outM) {
        double k = sat((tt - TL_CARDS - j * 0.07) / 0.6);
        if (k <= 0.0) return 0.0;
        double ee = st.e < 0.0 ? -1.0 : st.e - (st.t - tt);
        double ke = ee < 0.0 ? 0.0 : eInCubic(sat((ee - 0.04 - j * 0.03) / 0.42));
        double pe = eOutExpo(k);
        double fl = sstep(2.6, 3.6, tt);
        double[] pos = {
                p[0] + dir * 380.0 * (1.0 - pe) + dir * 700.0 * ke,
                p[1] + fl * 9.0 * Math.sin(tt * 0.7 + j * 1.7),
                p[2] + 700.0 * (1.0 - pe) - 300.0 * ke
        };
        double[] R = euler(
                r[0] + fl * 0.04 * Math.sin(tt * 0.5 + j),
                r[1] + dir * 1.2 * (1.0 - eOutCubic(k)) + dir * 0.8 * ke,
                r[2] + fl * 0.02 * Math.sin(tt * 0.6 + j * 2.0)
        );
        double[] O = st.cam.v(pos);
        double[] M = mm(st.cam.R, R);
        System.arraycopy(O, 0, outO, 0, 3);
        System.arraycopy(M, 0, outM, 0, 9);
        return sstep(0.0, 0.2, k) * (1.0 - ke);
    }

    private static double trainTheta(double t, double e) {
        double w = TAU / TL_LOOP;
        double d = Math.max(0.0, t - TL_LAY);
        double th = TH0 + w * (d + (14.0 / 3.0) * (1.0 - Math.exp(-3.0 * d)));
        if (e > 0.0) th += w * 6.0 * e * e;
        return th;
    }

    private void drawRing(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st, boolean frontPass) {
        if (st.t < TL_LAY) return;
        double ke = st.E(0.06, 0.72), kx = eInCubic(ke);
        double a = (1.0 - sstep(0.75, 1.0, ke)) * sstep(TL_LAY, TL_LAY + 0.08, st.t);
        if (a <= 0.003) return;

        double r = RING_R * (1.0 + 3.2 * kx);
        double[] R = mm(RZ(RING_RZ * (1.0 - kx)), RX(lerp(RING_TILT, -1.5, kx)));
        double[] C = st.cam.v(0.0, RING_Y + 360.0 * kx, -200.0 * kx);
        double[] M = mm(st.cam.R, R);
        double thT = trainTheta(st.t, st.e);
        double lay = sat((thT - TH0) / TAU);
        double a0 = TH0, a1 = TH0 + lay * TAU;

        // Dual rails
        double step = 0.028;
        int sn = 0;
        for (double rr : new double[]{r - 7.0, r + 7.0}) {
            for (double ang = a0; ang < a1; ang += step) {
                double b = Math.min(ang + step, a1);
                if (isBack(M, (ang + b) * 0.5) == frontPass) continue;
                double[] p0 = ringWorld(C, M, ang, rr, 0);
                double[] p1 = ringWorld(C, M, b, rr, 0);
                sn = pushSeg(st.cam, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], sn);
                if (sn >= 240) { gfx.drawSegments(segScratch, sn, 1.7, scaleAlpha(th.line, a)); sn = 0; }
            }
        }
        if (sn > 0) gfx.drawSegments(segScratch, sn, 1.7, scaleAlpha(th.line, a));

        // Sleepers
        sn = 0;
        for (double ang = a0; ang < a1; ang += 0.075) {
            if (isBack(M, ang) == frontPass) continue;
            double[] p0 = ringWorld(C, M, ang, r - 13.0, 0);
            double[] p1 = ringWorld(C, M, ang, r + 13.0, 0);
            sn = pushSeg(st.cam, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], sn);
        }
        if (sn > 0) gfx.drawSegments(segScratch, sn, 1.3, scaleAlpha(th.line, a * 0.55));

        // Freshly laid red flash
        double fresh = 1.0 - sstep(1.2, 1.7, st.t);
        if (fresh > 0.01) {
            sn = 0;
            for (double ang = Math.max(a0, a1 - 0.9); ang < a1; ang += step) {
                double b = Math.min(ang + step, a1);
                if (isBack(M, ang) == frontPass) continue;
                double[] p0 = ringWorld(C, M, ang, r, 0);
                double[] p1 = ringWorld(C, M, b, r, 0);
                sn = pushSeg(st.cam, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], sn);
            }
            if (sn > 0) gfx.drawSegments(segScratch, sn, 3.0, withAlpha(th.red, a * fresh));
        }

        // Red speed trail
        double speed = (trainTheta(st.t + 0.01, st.e < 0 ? st.e : st.e + 0.01) - thT) / 0.01;
        double trail = clamp(speed * 0.11, 0.35, 2.6);
        double tail0 = thT - (2.0 * CAR_GAP) / r - 0.12;
        for (int i = 0; i < 24; i++) {
            double s0 = tail0 - (i / 24.0) * trail;
            double s1 = tail0 - ((i + 1) / 24.0) * trail;
            if (s1 < a0 || isBack(M, (s0 + s1) * 0.5) == frontPass) continue;
            double[] p0 = st.cam.p(ringWorld(C, M, s0, r, 0));
            double[] p1 = st.cam.p(ringWorld(C, M, s1, r, 0));
            if (p0 != null && p1 != null) {
                gfx.drawLine(p0[0], p0[1], p1[0], p1[1], 3.0, withAlpha(th.red, a * (1.0 - i / 24.0) * 0.9));
            }
        }

        // 3 extruded train cars
        record CarItem(double[] O, double[] Mc, HabiKineticAtlas.Slab tx) {}
        List<CarItem> cars = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) {
            double ang = thT - (c * CAR_GAP) / r;
            if (ang < a0 - 0.01 || isBack(M, ang) == frontPass) continue;
            double ca = Math.cos(ang), sa = Math.sin(ang);
            double[] Rc = {-sa, -ca, 0, ca, -sa, 0, 0, 0, 1};
            double[] Mc = mm(M, Rc);
            double[] O = ringWorld(C, M, ang, r, -CAR_H);
            cars.add(new CarItem(O, Mc, c == 0 ? atlas.zeroCarLead : atlas.zeroCar));
        }
        cars.sort((p, q) -> Double.compare(q.O[2], p.O[2]));
        for (CarItem c : cars) {
            gfx.drawSlab(st.cam, c.O, c.Mc, c.tx, CAR_H, a, 0.0, false, false);
        }
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

    private void drawEmblem(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        double bob = st.idleB * 7.0 * Math.sin(st.ti * 1.2);
        double[] G = {0.0, EMB_Y + bob, 0.0};
        double[] Rg = euler(-0.06 + st.idleB * 0.035 * Math.sin(st.ti * 0.8),
                st.idleB * 0.22 * Math.sin(st.ti * 0.55), 0.0);
        double depth = 44.0;

        // Dashed blueprint outline placeholder
        double bp = sat((st.t - 1.12) / 0.14) * (1.0 - sat((st.t - 1.62) / 0.3));
        if (bp > 0.005 && st.e < 0.0) {
            double[] O = st.cam.v(G);
            double[] M = mm(st.cam.R, Rg);
            int outlineTex = (th.paper ? atlas.zeroOutlinePaper : atlas.zeroOutlineDark).getId();
            gfx.drawPlaneSprite(st.cam, O, M, outlineTex, 180, 180, 360, 360, withAlpha(0xFFFFFF, bp * 0.9), BlendMode.NORMAL);
            // Red blueprint tick marks
            drawPlaneLine(gfx, st.cam, O, M, -200, 0, -176, 0, 1.4, withAlpha(th.red, bp * 0.9));
            drawPlaneLine(gfx, st.cam, O, M, 176, 0, 200, 0, 1.4, withAlpha(th.red, bp * 0.9));
            drawPlaneLine(gfx, st.cam, O, M, 0, 176, 0, 200, 1.4, withAlpha(th.red, bp * 0.9));
        }

        // Stem & Leaf
        for (int which = 0; which <= 1; which++) {
            double kl = sat((st.t - TL_LEAF - (which == 1 ? 0.06 : 0.0)) / 0.42);
            if (kl <= 0.0) continue;
            double ke = exitK(st.e, 0);
            double s = Math.max(0.001, eOutBack(kl, 2.4));
            double sway = sstep(2.4, 3.2, st.t) * 0.07 * Math.sin((st.t - TL_IDLE) * 1.6);
            double[] loc = which == 1 ? new double[]{6, -124, 15} : new double[]{-6, -150, 15};
            double[] lp = va(loc, new double[]{-60 * ke, -300 * ke, -1500 * ke});
            double[] R = which == 1
                    ? euler(0, 0, lerp(-1.1, 0, eOutBack(kl, 1.6)) + sway + 2.0 * ke)
                    : euler(0, 0, -0.2 + ke);
            double[] O = st.cam.v(va(G, mv(Rg, lp)));
            double[] M = mm(st.cam.R, mm(Rg, msc(R, s)));
            gfx.drawSlab(st.cam, O, M, which == 1 ? atlas.zeroLeaf : atlas.zeroStem, 14.0,
                    sstep(0.0, 0.1, kl), th.shadowA, false, false);
        }

        // 6 Apple slice bands
        record BandItem(int idx, double[] O, double[] M, double a, double ke, HabiKineticAtlas.Slab b) {}
        List<BandItem> items = new ArrayList<>(atlas.zeroBands.length);
        for (int i = 0; i < atlas.zeroBands.length; i++) {
            double[] O = new double[3], M = new double[9];
            double[] outKe = new double[1];
            double a = bandPose(st, atlas.zeroBands[i], i, G, Rg, st.t, O, M, outKe);
            if (a > 0.003) items.add(new BandItem(i, O, M, a, outKe[0], atlas.zeroBands[i]));
        }
        items.sort((p, q) -> Double.compare(q.O[2], p.O[2]));

        double scanK = st.lp < 0.0 ? -1.0 : (st.lp - 0.05) / 0.75;
        for (BandItem it : items) {
            boolean fast = (st.t - TL_BANDS - it.idx * 0.05) < 0.5 || it.ke > 0.0;
            if (fast) {
                double[] gO = new double[3], gM = new double[9], dummy = new double[1];
                for (int g = 3; g >= 1; g--) {
                    double ga = bandPose(st, it.b, it.idx, G, Rg, st.t - g * 0.016, gO, gM, dummy);
                    if (ga > 0.003) gfx.drawSlab(st.cam, gO, gM, it.b, 0.0, ga * 0.16, 0.0, true, false);
                }
            }
            gfx.drawSlab(st.cam, it.O, it.M, it.b, depth, it.a, th.shadowA * (1.0 - it.ke), false, false);

            // Idle diagonal specular sheen sweep across apple slices
            if (scanK > 0.0 && scanK < 1.0 && st.e < 0.0) {
                double sx = lerp(-330.0, 330.0, eInOutCubic(scanK));
                double yc = it.b.yc(), hh = it.b.h() * 0.5 + 2.0;
                double sheenA = 0.55 * Math.sin(Math.PI * scanK);
                for (double[] strip : new double[][]{{0.0, 30.0}, {44.0, 10.0}}) {
                    double x0 = sx + strip[0], w = strip[1], sl = 0.42;
                    double[] poly = projectPlanePoly(st.cam, it.O, it.M,
                            x0 + (yc - hh) * -sl, -hh,
                            x0 + w + (yc - hh) * -sl, -hh,
                            x0 + w + (yc + hh) * -sl, hh,
                            x0 + (yc + hh) * -sl, hh);
                    if (poly != null) {
                        gfx.beginDepthClipPolygon(poly);
                        gfx.drawPlaneSprite(st.cam, it.O, it.M, it.b.maskTexId(),
                                it.b.ax(), it.b.ay(), it.b.w(), it.b.h(),
                                withAlpha(0xFFF9F0, sheenA), BlendMode.NORMAL);
                        gfx.endDepthClip();
                    }
                }
            }
        }
    }

    private double bandPose(FrameState st, HabiKineticAtlas.Slab b, int i, double[] G, double[] Rg,
                            double tt, double[] outO, double[] outM, double[] outKe) {
        double k = sat((tt - (TL_BANDS + i * 0.05)) / 0.5);
        if (k <= 0.0) return 0.0;
        double ee = st.e < 0.0 ? -1.0 : st.e - (st.t - tt);
        double ke = exitK(ee, i);
        outKe[0] = ke;
        double p = eOutBack(k, 1.25);
        int dir = (i % 2 == 1) ? 1 : -1;
        double[] lp = {
                dir * 560.0 * (1.0 - p) + dir * 420.0 * ke,
                b.yc() + b.yc() * 1.8 * ke,
                -780.0 * (1.0 - eOutExpo(k)) - 1750.0 * ke
        };
        double[] R = euler((b.yc() / 170.0) * 0.6 * ke, dir * 1.0 * (1.0 - p) + dir * 0.7 * ke, dir * 0.25 * (1.0 - p));
        double[] O = st.cam.v(va(G, mv(Rg, lp)));
        double[] M = mm(st.cam.R, mm(Rg, R));
        System.arraycopy(O, 0, outO, 0, 3);
        System.arraycopy(M, 0, outM, 0, 9);
        return sstep(0.0, 0.12, k);
    }

    private static double exitK(double ee, int i) {
        return ee < 0.0 ? 0.0 : eInCubic(sat((ee - 0.1 - i * 0.035) / 0.5));
    }

    private void drawLabels(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        double k = sat((st.t - TL_LABELS) / 0.55);
        if (k <= 0.002) return;
        double a = 1.0 - st.E(0.0, 0.25);
        if (a <= 0.005) return;
        double[] M = st.cam.R;

        record Lbl(double[] p, double[] dot, String s, int color, int align) {}
        Lbl[] items = {
                new Lbl(new double[]{-300, EMB_Y - 172, 0}, new double[]{-132, EMB_Y - 116, 0}, "CARGO // APPLE-01", th.text, 1),
                new Lbl(new double[]{RING_R + 46, RING_Y + 4, 0}, null, "O 1435 // LOOP", th.red, -1),
                new Lbl(new double[]{-150, EMB_Y - 300, OCTA_Z}, null, "FIG.00 // OCTA-RAIL", th.mute, 1),
                new Lbl(new double[]{250, EMB_Y - 236, 60}, null, "NEXT // APPLE STN.", th.mute, -1)
        };

        for (int j = 0; j < items.length; j++) {
            Lbl it = items[j];
            double kk = sat(k * 1.4 - j * 0.12);
            if (kk <= 0.002) continue;
            double[] O = st.cam.v(it.p);
            String txt = typed(it.s, kk * 1.25, j * 7 + Math.floor(st.t * 30.0));
            atlas.drawAsciiOnPlane(gfx, st.cam, O, M, HabiKineticAtlas.FontFace.MONO,
                    txt, 0, 0, 12.0, 3.0, withAlpha(it.color, a), it.align);
            if (it.dot != null) {
                double[] pa = st.cam.p(va(O, new double[]{12, 0, 0}));
                double[] pb = st.cam.p(st.cam.v(it.dot));
                if (pa != null && pb != null) {
                    double ekk = eOutCubic(kk);
                    double mx = lerp(pa[0], pb[0], ekk), my = lerp(pa[1], pb[1], ekk);
                    gfx.drawLine(pa[0], pa[1], mx, my, 1.0, scaleAlpha(th.line, a));
                    gfx.fillCircle(mx, my, 3.2, withAlpha(th.red, a), 12);
                }
            }
        }
    }

    private double[] titleRg(FrameState st) {
        return euler(0.10, st.idleB * 0.045 * Math.sin(st.ti * 0.42 + 1.0), -0.028);
    }

    private void drawTitle(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        double[] G = {0.0, TITLE_Y, 0.0};
        double[] Rg = titleRg(st);
        int pi = 4;

        record GlyphItem(int i, double[] O, double[] M, double a, HabiKineticAtlas.Slab g, double sx) {}
        List<GlyphItem> items = new ArrayList<>(atlas.titleGlyphs.size());
        for (int i = 0; i < atlas.titleGlyphs.size(); i++) {
            HabiKineticAtlas.Slab g = atlas.titleGlyphs.get(i);
            double[] O = new double[3], M = new double[9];
            double a = titleGlyphPose(st, g, i, pi, G, Rg, st.t, O, M);
            if (a > 0.003) {
                double[] p = st.cam.p(O);
                items.add(new GlyphItem(i, O, M, a, g, p != null ? Math.abs(p[0] - CX) : 0.0));
            }
        }
        items.sort(Comparator.comparingDouble((GlyphItem it) -> it.sx).reversed());

        boolean glitch = st.lp > 2.4 && st.lp < 2.52 && st.e < 0.0;
        for (GlyphItem it : items) {
            double start = (it.i == pi) ? TL_TSTAMP : (TL_LETTERS + (it.i > pi ? it.i - 1 : it.i) * 0.045);
            boolean fast = (st.t - start) < 0.42 || st.e > 0.0;
            if (fast) {
                double[] gO = new double[3], gM = new double[9];
                for (int g = 3; g >= 1; g--) {
                    double ga = titleGlyphPose(st, it.g, it.i, pi, G, Rg, st.t - g * 0.014, gO, gM);
                    if (ga > 0.003) gfx.drawSlab(st.cam, gO, gM, it.g, 0.0, ga * 0.15, 0.0, true, false);
                }
            }
            if (glitch && it.i != pi) {
                gfx.drawSlab(st.cam, va(it.O, new double[]{10, 0, 0}), it.M, it.g, 0.0, 0.5, 0.0, true, true);
            }
            gfx.drawSlab(st.cam, it.O, it.M, it.g, it.i == pi ? 30.0 : 34.0, it.a, th.shadowA, false, false);
        }

        // Corner bracket frame around title
        double kb = eOutCubic(sat((st.t - TL_IMPACT) / 0.4));
        if (kb > 0.005) {
            double a = (1.0 - st.E(0.0, 0.2)) * kb;
            double hw = atlas.titleWidth * 0.5 + 46.0 + 30.0 * (1.0 - kb);
            double top = -atlas.titleCapH - 40.0 - 16.0 * (1.0 - kb);
            double bot = 34.0 + 16.0 * (1.0 - kb);
            double L = 26.0;
            int sn = 0;
            for (int[] s : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                double cx = s[0] * hw, cy = s[1] < 0 ? top : bot;
                double[] p0 = st.cam.v(va(G, mv(Rg, new double[]{cx, cy, 0})));
                double[] px = st.cam.v(va(G, mv(Rg, new double[]{cx - s[0] * L, cy, 0})));
                double[] py = st.cam.v(va(G, mv(Rg, new double[]{cx, cy - s[1] * L, 0})));
                sn = pushSeg(st.cam, p0[0], p0[1], p0[2], px[0], px[1], px[2], sn);
                sn = pushSeg(st.cam, p0[0], p0[1], p0[2], py[0], py[1], py[2], sn);
            }
            gfx.drawSegments(segScratch, sn, 2.0, scaleAlpha(th.line, a));

            double[] pc = st.cam.p(st.cam.v(va(G, mv(Rg, new double[]{-hw, top - 18.0, 0}))));
            if (pc != null) gfx.fillRect(pc[0], pc[1] - 5.0, 10.0, 10.0, withAlpha(th.red, a));

            double[] M = mm(st.cam.R, Rg);
            atlas.drawAsciiOnPlane(gfx, st.cam, st.cam.v(va(G, mv(Rg, new double[]{-hw + 20.0, top - 13.0, 0}))), M,
                    HabiKineticAtlas.FontFace.MONO, "LOGO // 00", 0, 0, 12.0, 3.0, withAlpha(th.mute, a), -1);
            atlas.drawAsciiOnPlane(gfx, st.cam, st.cam.v(va(G, mv(Rg, new double[]{hw, top - 13.0, 0}))), M,
                    HabiKineticAtlas.FontFace.MONO, "HABIT + TRAIN = HABITRAIN", 0, 0, 12.0, 3.0, withAlpha(th.mute, a), 1);
        }
    }

    private double titleGlyphPose(FrameState st, HabiKineticAtlas.Slab g, int i, int pi,
                                  double[] G, double[] Rg, double tt, double[] outO, double[] outM) {
        double ee = st.e < 0.0 ? -1.0 : st.e - (st.t - tt);
        double rx = 0, z = 0, y = 0, x = g.titleX(), s = 1, rz = 0, a = 1;
        if (i == pi) {
            double k = sat((tt - TL_TSTAMP) / 0.36);
            if (k <= 0.0) return 0.0;
            s = lerp(1.9, 1.0, eOutCubic(k));
            rz = lerp(-0.45, 0.0, eOutBack(k, 2.2));
            z = lerp(-280.0, 0.0, eOutCubic(k));
        } else {
            double k = sat((tt - (TL_LETTERS + (i > pi ? i - 1 : i) * 0.045)) / 0.5);
            if (k <= 0.0) return 0.0;
            rx = lerp(-1.62, 0.0, eOutBack(k, 1.9));
            z = lerp(-160.0, 0.0, eOutCubic(k));
            a = sstep(0.0, 0.08, k);
        }
        double lpp = tt < TL_IDLE ? -1.0 : (((tt - TL_IDLE) % TL_LOOP) + TL_LOOP) % TL_LOOP;
        if (lpp >= 0.0) {
            double w = Math.sin(Math.PI * sat((lpp - 0.05 - i * 0.045) / 0.38));
            z -= 30.0 * w;
            rx -= 0.2 * w;
            if (lpp > 2.4 && lpp < 2.52) x += (hash(i * 13.0 + Math.floor(tt * 40.0)) - 0.5) * 16.0;
        }
        if (ee >= 0.0) {
            double ke = sat((ee - 0.04 - Math.abs(i - pi) * 0.03) / 0.34);
            rx += 1.75 * eInBack(ke, 1.4);
            z += 140.0 * ke;
            y -= 30.0 * ke;
            a *= 1.0 - sstep(0.7, 1.0, ke);
        }
        double[] O = st.cam.v(va(G, mv(Rg, new double[]{x, y, z})));
        double[] M = mm(st.cam.R, mm(Rg, msc(euler(rx, 0.0, rz), s)));
        System.arraycopy(O, 0, outO, 0, 3);
        System.arraycopy(M, 0, outM, 0, 9);
        return a;
    }

    private void drawTags(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        double[] Rg = titleRg(st);
        double[] G = {0.0, TAG_Y, 0.0};
        double gap = 14.0;
        double wl = atlas.tagLead.w(), wc = atlas.tagCore.w(), tot = wl + gap + wc;
        HabiKineticAtlas.Slab[] slabs = {atlas.tagLead, atlas.tagCore};
        double[] xs = {-tot * 0.5 + wl * 0.5, tot * 0.5 - wc * 0.5};
        int[] dirs = {-1, 1};

        for (int j = 0; j < 2; j++) {
            double[] O = new double[3], M = new double[9];
            double a = tagPose(st, xs[j], dirs[j], j, G, Rg, st.t, O, M);
            if (a <= 0.003) continue;
            if ((st.t - TL_TAGS - j * 0.08) < 0.45 || st.e > 0.0) {
                double[] gO = new double[3], gM = new double[9];
                for (int g = 3; g >= 1; g--) {
                    double ga = tagPose(st, xs[j], dirs[j], j, G, Rg, st.t - g * 0.018, gO, gM);
                    if (ga > 0.003) gfx.drawSlab(st.cam, gO, gM, slabs[j], 0.0, ga * 0.16, 0.0, true, false);
                }
            }
            gfx.drawSlab(st.cam, O, M, slabs[j], 12.0, a, th.shadowA, false, false);
        }

        double ks = sat((st.t - TL_SUB) / 0.6);
        if (ks > 0.002) {
            double a = 1.0 - st.E(0.0, 0.2);
            String sub = st.params.worldMode() && st.params.worldBanner() != null
                    ? st.params.worldBanner()
                    : "HABIT x TRAIN   //   EP.00   //   KINETIC LAUNCH";
            double sw = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, sub, 14.0, 4.0);
            atlas.drawAsciiOnPlane(gfx, st.cam, st.cam.v(mv(Rg, new double[]{-sw * 0.5, SUB_Y, 0})),
                    mm(st.cam.R, Rg), HabiKineticAtlas.FontFace.MONO,
                    typed(sub, ks * 1.15, Math.floor(st.t * 30.0)), 0, 0, 14.0, 4.0, withAlpha(th.mute, a), -1);
        }
    }

    private double tagPose(FrameState st, double x, int dir, int j, double[] G, double[] Rg,
                           double tt, double[] outO, double[] outM) {
        double k = sat((tt - (TL_TAGS + j * 0.08)) / 0.45);
        if (k <= 0.0) return 0.0;
        double ee = st.e < 0.0 ? -1.0 : st.e - (st.t - tt);
        double ke = ee < 0.0 ? 0.0 : eInCubic(sat((ee - j * 0.04) / 0.3));
        double pe = eOutExpo(k);
        double bump = 0.0;
        double lpp = tt < TL_IDLE ? -1.0 : (((tt - TL_IDLE) % TL_LOOP) + TL_LOOP) % TL_LOOP;
        if (lpp >= 0.0 && j == 1) bump = Math.sin(Math.PI * sat((lpp - 0.5) / 0.3));
        double[] lp = {x + dir * 760.0 * (1.0 - pe) - 1300.0 * ke, -6.0 * bump, -20.0 * bump};
        double[] R = euler(0.0, dir * 0.9 * (1.0 - eOutCubic(k)), dir * 0.02 * bump);
        double[] O = st.cam.v(va(G, mv(Rg, lp)));
        double[] M = mm(st.cam.R, mm(Rg, R));
        System.arraycopy(O, 0, outO, 0, 3);
        System.arraycopy(M, 0, outM, 0, 9);
        return sstep(0.0, 0.15, k) * (1.0 - sstep(0.85, 1.0, ke));
    }

    private void drawPromptOrProgress(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        double k = sat((st.t - TL_PROMPT) / 0.4);
        if (k <= 0.002) return;
        HabiRealProgressTracker.LoadSnapshot ld = st.params.load();
        double ke = st.E(0.18, 0.44);
        double a = sstep(0.0, 0.2, k) * (1.0 - sstep(0.6, 1.0, ke));
        if (a <= 0.003) return;

        double[] G = {0.0, PROMPT_Y, 0.0};
        double[] Rg = euler(0.08, 0.0, 0.0);

        // State A: Real resource/world loading (0% -> 99%)
        if ((ld == null || !ld.ready() || st.params.worldMode()) && st.e < 0.0) {
            double p = ld != null ? sat(ld.progress()) : 0.0;
            int pctNum = ld != null && ld.ready() ? 100 : Math.min(99, (int) Math.floor(p * 100.0));
            String pctStr = String.format(Locale.ROOT, "%02d%%", pctNum);
            double barW = 392.0, badgeW = atlas.loadBadge.w(), gap = 22.0;
            double tot = badgeW + gap + barW;
            double x0 = -tot * 0.5;
            double rx = lerp(-1.45, 0.0, eOutBack(k, 1.5));
            double[] M = mm(st.cam.R, mm(Rg, RX(rx)));

            // Left 3D extruded loading badge
            double[] badgeO = st.cam.v(va(G, new double[]{x0 + badgeW * 0.5, 0.0, 0.0}));
            gfx.drawSlab(st.cam, badgeO, M, atlas.loadBadge, 8.0, a, th.shadowA * 0.8, false, false);
            atlas.drawTextOnPlane(gfx, st.cam, badgeO, M, HabiKineticAtlas.FontFace.SANS_CN,
                    "装载中", -badgeW * 0.5 + 20.0, 1.0, 20, 4f, withAlpha(C_PAPER_TXT, a), -1);
            atlas.drawAsciiOnPlane(gfx, st.cam, badgeO, M, HabiKineticAtlas.FontFace.DISPLAY,
                    pctStr, badgeW * 0.5 - 18.0, 2.0, 32.0, 2.0, withAlpha(PAPER.red, a), 1);

            // Right 3D 24-segment slanted red sleeper progress bar
            double[] barO = st.cam.v(va(G, new double[]{x0 + badgeW + gap + barW * 0.5, -6.0, 0.0}));
            double bx = -barW * 0.5, by = -11.0, bh = 22.0;
            gfx.strokePlaneRect(st.cam, barO, M, bx, by, barW, bh, 1.6, scaleAlpha(th.line, a));
            int segN = 24;
            double pad = 4.0, innerW = barW - pad * 2.0, cellW = innerW / segN;
            double filled = p * segN;
            for (int i = 0; i < segN; i++) {
                double segFill = sat(filled - i);
                double sx = bx + pad + i * cellW + 1.5;
                double sw = cellW - 3.5;
                if (segFill > 0.02) {
                    boolean isHead = (i == (int) Math.floor(filled)) && pctNum < 100;
                    int col = isHead ? th.blue : th.red;
                    double sa = a * (isHead ? 0.95 : 0.92 * segFill);
                    gfx.fillPlaneQuad(st.cam, barO, M,
                            sx + 3.0, by + 4.0,
                            sx - 2.0, by + bh - 4.0,
                            sx + sw - 2.0, by + bh - 4.0,
                            sx + sw + 3.0, by + 4.0,
                            withAlpha(col, sa));
                } else {
                    gfx.fillPlaneRect(st.cam, barO, M, sx, by + 6.0, sw - 1.0, bh - 12.0, scaleAlpha(th.faint, a * 0.7));
                }
            }

            // Stage en + zh subtitle directly under progress bar
            if (ld != null && ld.activeStage() != null) {
                var stg = ld.activeStage();
                String enPart = "STAGE " + stg.code() + "/05 // " + stg.en();
                atlas.drawAsciiOnPlane(gfx, st.cam, barO, M, HabiKineticAtlas.FontFace.MONO,
                        enPart, bx, by + bh + 15.0, 11.0, 1.6, withAlpha(th.text, a * 0.88), -1);
                double enW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, enPart, 11.0, 1.6);
                atlas.drawTextOnPlane(gfx, st.cam, barO, M, HabiKineticAtlas.FontFace.SANS_CN,
                        "//  " + stg.zh(), bx + enW + 10.0, by + bh + 15.0, 11, 1.5f, withAlpha(th.red, a * 0.88), -1);
            }
            return;
        }

        // State B: 100% READY flip -> Press Any Key to Board / Syncing Beat / Departing
        boolean swap = st.e >= 0.0;
        boolean pending = !swap && st.params.quantizePending();
        HabiKineticAtlas.Slab tx = swap ? atlas.tagDepart
                : pending ? atlas.tagSyncBoard
                : st.params.autoEnter() ? atlas.tagAutoBoard
                : atlas.tagPrompt;
        String enText = swap ? "DEPARTING >>>>"
                : pending ? "LOCKING 100 BPM BEAT..."
                : st.params.autoEnter() ? "AUTO BOARDING // SYNC"
                : "PRESS ANY KEY TO BOARD";
        double enW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, enText, 13.0, 4.0);
        double tot = tx.w() + 14.0 + 16.0 + 22.0 + enW;
        double x0 = -tot * 0.5;
        double lockK = ld == null ? 1.0 : sat(ld.justLocked());
        double flipRx = lerp(-1.55, 0.0, eOutBack(lockK, 1.8));
        double flash = swap ? Math.sin(Math.PI * sat(st.e / 0.18))
                : pending ? (0.65 + 0.35 * Math.sin(st.t * 28.0))
                : Math.sin(Math.PI * sat(lockK / 0.65)) * 0.65;
        double rx = lerp(-1.5, 0.0, eOutBack(k, 1.6)) + flipRx + eInBack(ke, 1.5) * 1.6;
        double readyAt = (ld != null && !Double.isNaN(ld.readyAt())) ? ld.readyAt() : TL_PROMPT;
        double breath = (swap || pending) ? 1.0 : (0.72 + 0.28 * Math.sin((st.t - readyAt) * 3.2));
        double[] M = mm(st.cam.R, mm(Rg, msc(RX(rx), 1.0 + 0.08 * flash)));

        gfx.drawSlab(st.cam, st.cam.v(va(G, new double[]{x0 + tx.w() * 0.5, 0, 0})), M, tx, 8.0, a, th.shadowA * 0.8, false, false);
        boolean blink = swap || pending || (((int) Math.floor(st.t * 2.4)) % 2 == 0);
        if (blink) {
            gfx.drawSlab(st.cam, st.cam.v(va(G, new double[]{x0 + tx.w() + 14.0 + 8.0, 0, 0})), M, atlas.cursor, 6.0, a, 0.0, false, false);
        }
        int enCol = (swap || pending || st.params.autoEnter()) ? th.red : th.mute;
        atlas.drawAsciiOnPlane(gfx, st.cam, st.cam.v(va(G, new double[]{x0 + tx.w() + 14.0 + 16.0 + 22.0, 0, 0})), M,
                HabiKineticAtlas.FontFace.MONO, enText, 0, 0, 13.0, 4.0, withAlpha(enCol, a * breath), -1);
    }

    private void drawHud(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        double k = eOutCubic(sat(st.t / 0.5));
        double a = k * (1.0 - st.E(0.0, 0.3));
        if (a <= 0.005) return;

        double L = gfx.viewLeft(), T = gfx.viewTop(), R = gfx.viewRight(), B = gfx.viewBottom();
        double I = 34.0, len = 22.0 * k;
        int lc = scaleAlpha(th.line, a);

        // 4 Corner brackets
        gfx.drawLine(L + I, T + I + len, L + I, T + I, 1.5, lc);
        gfx.drawLine(L + I, T + I, L + I + len, T + I, 1.5, lc);
        gfx.drawLine(R - I - len, T + I, R - I, T + I, 1.5, lc);
        gfx.drawLine(R - I, T + I, R - I, T + I + len, 1.5, lc);
        gfx.drawLine(L + I, B - I - len, L + I, B - I, 1.5, lc);
        gfx.drawLine(L + I, B - I, L + I + len, B - I, 1.5, lc);
        gfx.drawLine(R - I - len, B - I, R - I, B - I, 1.5, lc);
        gfx.drawLine(R - I, B - I, R - I, B - I - len, 1.5, lc);

        // Top-left status bar
        HabiRealProgressTracker.LoadSnapshot ld = st.params.load();
        int pct = ld == null ? 100 : Math.min(100, (int) Math.round(sat(ld.progress()) * 100.0));
        gfx.fillRect(L + 52.0, T + 46.0, 16.0, 3.0, withAlpha(th.red, a));
        String phaseStr = st.e >= 0.0 ? "DEPARTING"
                : st.params.quantizePending() ? "BEAT SYNC LOCK"
                : st.t < 1.2 ? "BOOT SEQUENCE"
                : (ld == null || !ld.ready()) ? String.format(Locale.ROOT, "LOADING %03d%%", pct)
                : "READY // STANDBY";
        String topLeft = "00 // HABITRAIN // " + phaseStr;
        if (ld != null && ld.statusCounter() != null && !ld.statusCounter().isEmpty() && st.t > 1.25) {
            topLeft += "  //  " + ld.statusCounter();
        }
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO,
                typed(topLeft, sat(st.t / 0.7) * 1.2, Math.floor(st.t * 30.0)),
                L + 78.0, T + 48.0, 12.0, 2.8, withAlpha(th.text, a), -1, 0.0);

        // Top-right 16-bar beat/spectrum visualizer
        var tel = st.params.audioTel();
        double lpp = st.lp < 0.0 ? st.t : st.lp;
        int beatBar = ((int) Math.floor(lpp / 0.3)) % 16;
        for (int i = 0; i < 16; i++) {
            boolean on = beatBar == i;
            double bv = (tel != null) ? tel.bar(i) : (on ? 0.9 : 0.35);
            double bh = on ? 18.0 : Math.round(7.0 + bv * 9.0);
            int col = (on || i < 4) ? withAlpha(th.red, a) : scaleAlpha(th.faint, a);
            gfx.fillRect(R - 66.0 - (15 - i) * 7.0, T + 48.0 - bh * 0.5, 2.0, bh, col);
        }

        // Bottom-left 5-stage real MANIFEST
        if (ld != null && ld.stages() != null && !ld.stages().isEmpty() && st.t > 1.35) {
            double km = sstep(1.35, 1.95, st.t) * (1.0 - st.E(0.0, 0.2));
            if (km > 0.01) {
                double ma = a * km;
                atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO,
                        ld.ready() ? "MANIFEST // 100% VERIFIED" : "MANIFEST // LOADING ASSETS",
                        L + 54.0, B - 194.0, 10.0, 2.0, withAlpha(th.mute, ma), -1, 0.0);
                for (int idx = 0; idx < ld.stages().size(); idx++) {
                    var stg = ld.stages().get(idx);
                    double yy = B - 172.0 + idx * 20.0;
                    boolean isAct = !ld.ready() && idx == ld.activeIndex();
                    if (stg.done()) {
                        gfx.fillRect(L + 54.0, yy - 3.5, 7.0, 7.0, withAlpha(th.red, ma));
                        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, stg.code() + " " + stg.en(),
                                L + 69.0, yy, 10.0, 2.0, withAlpha(th.text, ma), -1, 0.0);
                        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "OK",
                                L + 318.0, yy, 10.0, 2.0, withAlpha(th.red, ma), -1, 0.0);
                    } else if (isAct) {
                        boolean pulse = ((int) Math.floor(st.t * 6.0)) % 2 == 0;
                        gfx.fillRect(L + 54.0, yy - 3.5, 7.0, 7.0, withAlpha(pulse ? th.blue : th.red, ma));
                        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, stg.code() + " " + stg.en(),
                                L + 69.0, yy, 10.0, 2.0, withAlpha(th.text, ma), -1, 0.0);
                        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO,
                                String.format(Locale.ROOT, "%02d%%", (int) Math.round(stg.progress() * 100.0)),
                                L + 318.0, yy, 10.0, 2.0, withAlpha(th.blue, ma), -1, 0.0);
                    } else {
                        gfx.strokeRect(L + 54.5, yy - 3.5, 6.0, 6.0, 1.0, scaleAlpha(th.faint, ma));
                        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, stg.code() + " " + stg.en(),
                                L + 69.0, yy, 10.0, 2.0, withAlpha(th.mute, ma), -1, 0.0);
                    }
                }
            }
        }

        // Bottom-left progress rail + HABITRAIN // 苹果列车
        double prog = st.e >= 0.0 ? 1.0 : (ld != null ? sat(ld.progress()) : 1.0);
        gfx.fillRect(L + 52.0, B - 48.0, 150.0, 2.0, scaleAlpha(th.faint, a));
        gfx.fillRect(L + 52.0, B - 48.0, 150.0 * prog, 2.0, withAlpha(th.red, a));
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "HABITRAIN",
                L + 216.0, B - 48.0, 12.0, 3.0, withAlpha(th.text, a), -1, 0.0);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "//",
                L + 336.0, B - 48.0, 12.0, 3.0, withAlpha(th.mute, a), -1, 0.0);
        atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, BRAND_CN,
                L + 366.0, B - 47.0, 14, 2f, withAlpha(th.text, a), -1, 0.0, 0);

        // Bottom-center rotating travel tip
        if (ld != null && ld.tip() != null && st.t > 1.5 && st.e < 0.0) {
            double kt = sstep(1.5, 2.1, st.t) * sstep(0.0, 0.25, ld.tipLocalT());
            atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN,
                    ld.tip().code() + "  //  " + ld.tip().zh(),
                    (L + R) * 0.5 + 20.0, B - 47.0, 12, 2f, withAlpha(th.mute, a * kt * 0.86), 0, 0.0, 0);
        }

        // Bottom-right real timecode
        double tc = Math.max(0.0, st.t);
        String timecode = String.format(Locale.ROOT, "%02d:%02d.%02d   //   GAUGE 1435",
                (int) (tc / 60.0), (int) (tc % 60.0), (int) ((tc % 1.0) * 100.0));
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, timecode,
                R - 54.0, B - 48.0, 12.0, 3.0, withAlpha(th.text, a), 1, 0.0);

        // Right vertical Chinese stamp: 苹果列车 / 第零站
        double kv = sat((st.t - 2.4) / 0.6);
        if (kv > 0.005) {
            String col1 = BRAND_CN, col2 = "第零站";
            for (int i = 0; i < col1.length(); i++) {
                if (i < col1.length() * kv * 1.3) {
                    atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, col1.substring(i, i + 1),
                            R - RIGHT_STAMP_COL1_INSET, T + 430.0 + i * 26.0, 18, 0f, withAlpha(th.mute, a), 0, 0.0, 0);
                }
            }
            for (int i = 0; i < col2.length(); i++) {
                if (i < col2.length() * kv * 1.3) {
                    atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, col2.substring(i, i + 1),
                            R - RIGHT_STAMP_COL2_INSET, T + 456.0 + i * 26.0, 18, 0f, withAlpha(th.red, a), 0, 0.0, 0);
                }
            }
            gfx.fillRect(R - RIGHT_STAMP_BAR_INSET, T + 396.0, 16.0, 2.0, withAlpha(th.red, a));
        }

        // Left rotated vertical monospace code
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO,
                typed("RAIL LOOP // 00 // HT-0000", sat((st.t - 0.3) / 0.8) * 1.2, Math.floor(st.t * 30.0) + 3.0),
                L + 58.0, T + 640.0, 11.0, 4.0, withAlpha(th.mute, a), -1, -Math.PI * 0.5);

        // Dot matrices + blinking cobalt pixel
        int dotCol = scaleAlpha(th.faint, a);
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 4; j++) {
                gfx.fillRect(L + 176.0 + i * 12.0, T + 118.0 + j * 12.0, 2.0, 2.0, dotCol);
                gfx.fillRect(R - 280.0 + i * 12.0, B - 210.0 + j * 12.0, 2.0, 2.0, dotCol);
            }
        }
        if (((int) Math.floor(st.t * 1.7)) % 2 == 0) {
            gfx.fillRect(R - 280.0 + 6 * 12.0 + 6.0, B - 210.0, 6.0, 6.0, withAlpha(th.blue, a));
        }
    }

    private void drawPost(HabiKineticGfx gfx, HabiKineticAtlas atlas, Palette th, FrameState st) {
        int vigId = (th.paper ? atlas.vigPaperTex : atlas.vigDarkTex).getId();
        gfx.drawTexturedRect(vigId, gfx.viewLeft() - 20, gfx.viewTop() - 20,
                gfx.viewWidth() + 40, gfx.viewHeight() + 40, 0xFFFFFFFF, BlendMode.NORMAL);

        double f = Math.floor(st.t * 24.0);
        double uOff = hash(f);
        double vOff = hash(f + 7.3);
        gfx.drawTiledTexture(atlas.noiseTex.getId(),
                gfx.viewLeft() - 10, gfx.viewTop() - 10, gfx.viewWidth() + 20, gfx.viewHeight() + 20,
                256.0, uOff, vOff, withAlpha(0xFFFFFF, th.grainA),
                th.paper ? BlendMode.MULTIPLY : BlendMode.SCREEN);
    }

    // ── Geometry helpers ─────────────────────────────────────────────────────

    private static double[] slantPoly(double x0, double x1) {
        double offTop = (CY - (-80.0)) * SLANT;
        double offBot = (CY - (H + 80.0)) * SLANT;
        return new double[]{
                x0 + offTop, -80.0,
                x1 + offTop, -80.0,
                x1 + offBot, H + 80.0,
                x0 + offBot, H + 80.0
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

    private static void drawPlaneLine(HabiKineticGfx gfx, Cam cam, double[] O, double[] M,
                                      double x0, double y0, double x1, double y1, double w, int argb) {
        double[] pa = cam.project(O[0] + M[0] * x0 + M[1] * y0, O[1] + M[3] * x0 + M[4] * y0, O[2] + M[6] * x0 + M[7] * y0);
        double[] pb = cam.project(O[0] + M[0] * x1 + M[1] * y1, O[1] + M[3] * x1 + M[4] * y1, O[2] + M[6] * x1 + M[7] * y1);
        if (pa != null && pb != null) gfx.drawLine(pa[0], pa[1], pb[0], pb[1], w, argb);
    }

    private static double[] projectPlanePoly(Cam cam, double[] O, double[] M,
                                             double x0, double y0, double x1, double y1,
                                             double x2, double y2, double x3, double y3) {
        double[] p0 = cam.project(O[0] + M[0] * x0 + M[1] * y0, O[1] + M[3] * x0 + M[4] * y0, O[2] + M[6] * x0 + M[7] * y0);
        double[] p1 = cam.project(O[0] + M[0] * x1 + M[1] * y1, O[1] + M[3] * x1 + M[4] * y1, O[2] + M[6] * x1 + M[7] * y1);
        double[] p2 = cam.project(O[0] + M[0] * x2 + M[1] * y2, O[1] + M[3] * x2 + M[4] * y2, O[2] + M[6] * x2 + M[7] * y2);
        double[] p3 = cam.project(O[0] + M[0] * x3 + M[1] * y3, O[1] + M[3] * x3 + M[4] * y3, O[2] + M[6] * x3 + M[7] * y3);
        if (p0 == null || p1 == null || p2 == null || p3 == null) return null;
        return new double[]{p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]};
    }
}
