package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.client.replay.ReplayKineticPen.Face;

import java.util.Locale;

import static com.habitrain.core.scene.client.replay.ReplayKineticKit.*;

/** Opening act (2 bars = 8 beats at 120 BPM) for each kinetic theme. Typography first, then the board is revealed. */
final class ReplayKineticTitles {
    private ReplayKineticTitles() {}

    static final double REVEAL = 2.9, REVEAL_LEN = 0.6;

    /** True while the opening typography fully covers the 3D board. */
    static boolean covers(double k) { return k < REVEAL + REVEAL_LEN; }

    static void title(ReplayKineticPen p, ReplayKineticTheme th, double k, ReplayKineticData.Title d) {
        switch (th) {
            case KINETIC_CUT -> kineticCut(p, th, k, d);
            case DOSSIER -> dossier(p, th, k, d);
            case BLUEPRINT -> blueprint(p, th, k, d);
            case MUSIC_VIDEO -> musicVideo(p, th, k, d);
        }
    }

    /** Camera drift: the whole layout slowly pushes in and tilts so a typographic frame is never static. */
    static void drift(ReplayKineticPen p, double t, double amount) {
        float w = p.width(), h = p.height();
        p.translate(w / 2, h / 2);
        p.rotate((float) (Math.sin(t * 0.45) * 0.012 * amount));
        float s = (float) (1 + 0.012 * t * amount);
        p.scale(s, s);
        p.translate(-w / 2, -h / 2);
    }

    /** Paper cover sliding off to the left with a smeared accent edge, uncovering the board. */
    private static void revealSlide(ReplayKineticPen p, ReplayKineticTheme th, double k, Runnable sheet) {
        float w = p.width(), h = p.height();
        double e = inOutQuart((k - REVEAL) / REVEAL_LEN);
        if (e >= 1) return;
        float off = (float) (-(w + 220) * e);
        float vel = (float) ((inOutQuart((k - REVEAL) / REVEAL_LEN) - inOutQuart((k - REVEAL - 0.033) / REVEAL_LEN)) * -(w + 220));
        p.push();
        p.translate(off, 0);
        sheet.run();
        p.pop();
        float edge = w + off;
        for (int i = 4; i >= 1; i--) rect(p, edge, 0, edge - vel * i * 0.7f + 2, h, a(th.paper, 0.2 / i));
        rect(p, edge, 0, edge + 10, h, th.red);
    }

    private static void lockup(ReplayKineticPen p, ReplayKineticTheme th, double k, String text) {
        double a = smooth(REVEAL + 0.3, REVEAL + 0.6, k);
        if (a <= 0.01) return;
        float h = p.height();
        rect(p, 40, h - 104, 46, h - 70, a(th.red, a));
        micro(p, th.code + ".01", th.title, 56, h - 96, 9, a(th.red, a), a(0xFFFFFFFF, 0.7 * a), -1);
        p.text(lit(text), Face.DISPLAY, 56, h - 78, 26, a(0xFFFFFFFF, a), -1);
    }

    // ------------------------------------------------------------------ KINETIC CUT

    private static void kineticCut(ReplayKineticPen p, ReplayKineticTheme th, double k, ReplayKineticData.Title d) {
        float w = p.width(), h = p.height();
        String map = d.map().getString();
        revealSlide(p, th, k, () -> {
            ReplayKineticBackdrops.paper(p, th, k, "01");
            p.push();
            drift(p, k, 1);
            float cx = w / 2, cy = h / 2 - 30;
            crosshair(p, cx, cy, a(th.ink, 0.18), k / 0.6);
            reticle(p, cx, cy, 300, 92, 18, 2f, th.ink, k / 0.5);
            ellipse(p, cx, cy, 150, 150, 0, 2f, a(th.red, 0.8), 0, outCubic(k / 0.9));
            micro(p, "01", "DEPARTURE", cx, cy - 66, 10, th.red, a(th.ink, 0.7), 0);
            float tw = p.textWidth(lit(map), Face.DISPLAY, 72);
            typeOn(p, map, Face.DISPLAY, cx - tw / 2, cy, 72, th.ink, th.red, (k - 0.45) / 0.8, true);
            double sa = smooth(1.0, 1.3, k);
            micro(p, "", d.mode().getString() + "   " + d.date() + "   " + d.players() + " PASSENGERS",
                    cx, cy + 50, 9, a(th.ink, 0.6 * sa), a(th.ink, 0.6 * sa), 0);
            String[][] slots = {{"DURATION", d.duration()}, {"PASSENGERS", String.valueOf(d.players())}, {"TRAIN", d.trainNo()}};
            int[] fills = {th.ink, th.red, th.blue};
            float sw = 230, gap = 18, sx = cx - (sw * 3 + gap * 2) / 2, sy = cy + 96;
            for (int i = 0; i < 3; i++) {
                double show = smooth(1.2 + i * 0.08, 1.35 + i * 0.08, k);
                if (show <= 0) continue;
                float x0 = sx + i * (sw + gap);
                frame(p, x0, sy, x0 + sw, sy + 44, 1.2f, a(th.hair, show));
                micro(p, String.format(Locale.ROOT, "%02d", i + 1), "SLOT", x0 + 8, sy - 10, 7.5f, a(th.ink, 0.5 * show), a(th.ink, 0.5 * show), -1);
                double age = k - 1.55 - i * 0.18;
                if (age > 0) {
                    float slide = (float) ((1 - outExpo(age / 0.45)) * 340);
                    float vel = slideVelocity(age, 0.45, -340);
                    smearSlab(p, x0 + slide, sy, x0 + sw + slide, sy + 44, 10, fills[i], vel);
                    micro(p, "", slots[i][0], x0 + 14 + slide, sy + 14, 7.5f, a(on(fills[i]), 0.7), a(on(fills[i]), 0.7), -1);
                    p.text(lit(slots[i][1]), Face.DISPLAY, x0 + sw - 12 + slide, sy + 27, 22, on(fills[i]), 1);
                }
            }
            p.pop();
        });
        lockup(p, th, k, map);
    }

    // ------------------------------------------------------------------ DOSSIER

    private static void dossier(ReplayKineticPen p, ReplayKineticTheme th, double k, ReplayKineticData.Title d) {
        float w = p.width(), h = p.height();
        var subject = d.subject();
        int accent = subject == null ? th.blue : duality(subject.color(), th.red, th.blue);
        // Folder opening: the sheet splits into an upper and a lower half.
        double e = inOutQuart((k - REVEAL) / REVEAL_LEN);
        if (e < 1) {
            for (int half = 0; half < 2; half++) {
                p.push();
                float y0 = half == 0 ? 0 : h / 2, y1 = half == 0 ? h / 2 : h;
                p.translate(0, (float) ((half == 0 ? -1 : 1) * (h / 2 + 40) * e));
                clip(p, th, y0, y1, k, d, subject, accent);
                p.pop();
            }
        }
        lockup(p, th, k, subject == null ? d.map().getString() : subject.name().getString());
    }

    /** Draws the dossier sheet restricted to [y0, y1] by painting it and masking the other half with nothing. */
    private static void clip(ReplayKineticPen p, ReplayKineticTheme th, float y0, float y1, double k,
                             ReplayKineticData.Title d, ReplayKineticData.Person subject, int accent) {
        float w = p.width(), h = p.height();
        // The two halves are drawn as separate sheets; each half only fills its own band of paper.
        rectV(p, 0, y0, w, y1, th.paper, mix(th.paper, PAPER_SHADE, 0.4));
        dotGrid(p, w - 170, y0 + 60, 5, 3, 10, 1.2f, a(th.ink, 0.2));
        line(p, 0, y1 - 1, w, y1 - 1, 1f, a(th.ink, 0.15));
        float fx = w * 0.27f, fy = h / 2 - 98;
        boolean top = y0 < h / 2 - 1;
        if (top) {
            octahedron(p, fx, fy, 92, k * 0.6, 0.35, a(th.ink, 0.25), a(accent, 0.7), 1.3f);
        } else {
            ellipse(p, fx, h / 2 + 70, 140, 26, 0, 1.5f, a(accent, 0.6), 0, outCubic(k / 1.2));
            micro(p, "ID", subject == null ? "UNKNOWN" : subject.name().getString(), fx, h / 2 + 118, 9, accent, a(th.ink, 0.6), 0);
        }
        if (top) {
            double fa = smooth(0.2, 0.5, k);
            float fs = 128;
            if (subject != null && subject.id() != null) {
                rect(p, fx - fs / 2 - 6, fy - fs / 2 - 6, fx + fs / 2 + 6, fy + fs / 2 + 6, a(th.ink, fa));
                p.face(subject.id(), fx - fs / 2, fy - fs / 2, fs, a(0xFFFFFFFF, fa));
            } else {
                ghost(p, "?", fx, fy, 160, a(th.ink, 0.5 * fa), 0);
            }
            reticle(p, fx, fy, fs / 2 + 22, fs / 2 + 22, 16, 2f, accent, k / 0.6);
        }
        float x0 = w * 0.44f;
        if (top) {
            micro(p, "FILE", "SUBJECT 0001", x0, h / 2 - 118, 10, accent, a(th.ink, 0.6), -1);
            String name = subject == null ? d.map().getString() : subject.name().getString();
            dropCap(p, name, x0, h / 2 - 60, 96, 46, accent, th.ink, k - 0.35);
        } else {
            if (subject != null) {
                double ra = (k - 0.9) / 0.4;
                if (ra > 0) {
                    float rw = p.textWidth(subject.role(), Face.TEXT, 20) + 34;
                    float slide = (float) ((1 - outExpo(ra)) * 200);
                    smearSlab(p, x0 + slide, h / 2 + 8, x0 + rw + slide, h / 2 + 42, 9, accent, slideVelocity(k - 0.9, 0.4, -200));
                    p.text(subject.role(), Face.TEXT, x0 + 17 + slide, h / 2 + 25, 20, on(accent), -1);
                }
            }
            String[][] rows = {{"MAP", d.map().getString()}, {"VIEW", d.view()}, {"PACE", d.pace()}, {"DATE", d.date()}};
            for (int i = 0; i < rows.length; i++) {
                double ra = smooth(1.2 + i * 0.15, 1.4 + i * 0.15, k);
                if (ra <= 0) continue;
                float ry = h / 2 + 72 + i * 24;
                micro(p, rows[i][0], "", x0, ry, 9, a(accent, ra), 0, -1);
                typeOn(p, rows[i][1], Face.TEXT, x0 + 70, ry, 15, a(th.ink, ra), 0, (k - 1.2 - i * 0.15) / 0.4, false);
            }
            ruler(p, x0, w - 90, h / 2 + 182, 32, outCubic((k - 1.0) / 1.6), a(th.ink, 0.35), accent);
        }
    }

    // ------------------------------------------------------------------ BLUEPRINT

    private static void blueprint(ReplayKineticPen p, ReplayKineticTheme th, double k, ReplayKineticData.Title d) {
        float w = p.width(), h = p.height();
        double e = inOutQuart((k - REVEAL) / REVEAL_LEN);
        if (e < 1) {
            p.push();
            p.translate(0, (float) (-(h + 60) * e));
            ReplayKineticBackdrops.paper(p, th, k, "");
            float maxW = 560, maxH = 250;
            float mw = Math.max(1, d.mapW()), md = Math.max(1, d.mapD());
            float sc = Math.min(maxW / mw, maxH / md);
            float rw = mw * sc, rh = md * sc, cx = w * 0.42f, cy = h * 0.45f;
            float x0 = cx - rw / 2, y0 = cy - rh / 2, x1 = cx + rw / 2, y1 = cy + rh / 2;
            double draw = outCubic(k / 1.2);
            float[] per = {x0, y0, x1, y0, x1, y1, x0, y1, x0, y0};
            drawPerimeter(p, per, draw, 2.5f, th.ink);
            if (draw > 0.95) {
                for (float hx = x0 + 14; hx < x1; hx += 14) line(p, hx, y0, Math.max(x0, hx - rh * 0.4f), y1, 0.8f, a(th.blue, 0.25));
            }
            double dim = smooth(1.0, 1.4, k);
            dimension(p, x0, y0 - 34, x1, y0 - 34, String.format(Locale.ROOT, "W %.0f", d.mapW()), th, dim);
            dimension(p, x1 + 34, y0, x1 + 34, y1, String.format(Locale.ROOT, "D %.0f", d.mapD()), th, dim);
            octahedron(p, w - 160, 150, 70, k * 0.8, 0.4, a(th.ink, 0.45), a(th.red, 0.8), 1.3f);
            micro(p, "SHEET", "01 / 01", w - 160, 250, 9, th.red, a(th.ink, 0.6), 0);
            // Title block, bottom right.
            float bx0 = w - 420, by0 = h - 190, bx1 = w - 50, by1 = h - 60;
            double ba = smooth(0.8, 1.2, k);
            frame(p, bx0, by0, bx1, by1, 2f, a(th.ink, ba));
            String[][] rows = {{"PROJECT", "HABITRAIN REPLAY"}, {"SHEET", d.map().getString()}, {"MODE", d.mode().getString()},
                    {"DATE", d.date() + "   " + d.duration()}};
            for (int i = 0; i < rows.length; i++) {
                float ry = by0 + 16 + i * 32;
                if (i > 0) line(p, bx0, ry - 16, bx1, ry - 16, 1f, a(th.ink, 0.4 * ba));
                micro(p, rows[i][0], "", bx0 + 12, ry, 8.5f, a(th.red, ba), 0, -1);
                typeOn(p, rows[i][1], Face.TEXT, bx0 + 110, ry, 15, a(th.ink, ba), 0, (k - 1.0 - i * 0.18) / 0.5, false);
            }
            line(p, bx0 + 100, by0, bx0 + 100, by1, 1f, a(th.ink, 0.4 * ba));
            micro(p, "BP.00", "GENERAL ARRANGEMENT", 60, 60, 11, th.red, a(th.ink, 0.75), -1);
            p.text(d.map(), Face.DISPLAY, 60, 100, 52, th.ink, -1);
            p.pop();
            rect(p, 0, (float) (h - (h + 60) * e), w, (float) (h - (h + 60) * e) + 6, th.blue);
        }
        lockup(p, th, k, d.map().getString());
    }

    private static void drawPerimeter(ReplayKineticPen p, float[] pts, double amount, float lw, int c) {
        int segs = pts.length / 2 - 1;
        double total = 0;
        double[] len = new double[segs];
        for (int i = 0; i < segs; i++) {
            len[i] = Math.hypot(pts[i * 2 + 2] - pts[i * 2], pts[i * 2 + 3] - pts[i * 2 + 1]);
            total += len[i];
        }
        double left = total * clamp01(amount);
        for (int i = 0; i < segs && left > 0; i++) {
            double f = Math.min(1, left / Math.max(1e-6, len[i]));
            lineGrow(p, pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3], lw, c, f);
            left -= len[i];
        }
    }

    private static void dimension(ReplayKineticPen p, float x0, float y0, float x1, float y1, String label,
                                  ReplayKineticTheme th, double a) {
        if (a <= 0.01) return;
        int c = a(th.blue, a);
        line(p, x0, y0, x1, y1, 1.2f, c);
        boolean horizontal = Math.abs(y1 - y0) < Math.abs(x1 - x0);
        float nx = horizontal ? 0 : 7, ny = horizontal ? 7 : 0;
        line(p, x0 - nx, y0 - ny, x0 + nx, y0 + ny, 1.2f, c);
        line(p, x1 - nx, y1 - ny, x1 + nx, y1 + ny, 1.2f, c);
        float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
        float lw = trackedWidth(p, label, Face.MONO, 9, 2.5f) + 12;
        rect(p, mx - lw / 2, my - 8, mx + lw / 2, my + 8, a(th.paper, a));
        tracked(p, label, Face.MONO, mx, my, 9, 2.5f, c, 0);
    }

    // ------------------------------------------------------------------ MUSIC VIDEO

    private static void musicVideo(ReplayKineticPen p, ReplayKineticTheme th, double k, ReplayKineticData.Title d) {
        float w = p.width(), h = p.height();
        String map = d.map().getString();
        double beat = k / 0.5;
        if (k < REVEAL + REVEAL_LEN) {
            if (beat < 1) {
                ReplayKineticBackdrops.paper(p, th, k, "");
                reticle(p, w / 2, h / 2, 120, 70, 16, 2f, th.ink, beat);
                ellipse(p, w / 2, h / 2 + 6, 90, 90, 0, 2f, a(th.red, 0.7), 0.1, 0.1 + beat * 0.8);
                micro(p, "00", "OPENING GATE", w / 2, h / 2 - 96, 9, th.red, a(th.ink, 0.6), 0);
            } else if (beat < 2) {
                double b = beat - 1;
                ReplayKineticBackdrops.ink(p, th, k, "");
                float spread = (float) (outExpo(b / 0.6) * 60);
                card(p, w / 2 - 110 - spread, h / 2, 190, 230, 0xFFEFEDE8, th.red, "01", "GATE // L", true, (float) Math.toRadians(-4));
                card(p, w / 2 + 110 + spread, h / 2, 190, 230, th.red, 0xFFFFFFFF, "02", "GATE // R", false, (float) Math.toRadians(3));
                octahedron(p, w / 2, h / 2, 70, b * 2.4, 0.5, a(0xFFFFFFFF, 0.5), th.red, 1.4f);
            } else if (beat < 4) {
                double b = k - 1.0;
                ReplayKineticBackdrops.paper(p, th, k, "01");
                p.push();
                drift(p, b, 1.5);
                int[] cps = map.codePoints().toArray();
                int split = map.indexOf(' ') > 0 ? map.substring(0, map.indexOf(' ')).codePointCount(0, map.indexOf(' ')) : Math.max(1, cps.length / 2);
                String first = new String(cps, 0, Math.min(split, cps.length)).trim();
                String second = new String(cps, Math.min(split, cps.length), cps.length - Math.min(split, cps.length)).trim();
                float x = 200, y = h / 2 - 50;
                rect(p, x, y - 112, x + 46, y - 96, th.red);
                micro(p, "S01", "ARCHIVE  //  VOL.01", x + 56, y - 104, 9, a(th.ink, 0.7), a(th.ink, 0.7), -1);
                lineGrow(p, x, y - 84, x + 820, y - 92, 3f, th.red, outExpo(b / 0.5));
                double in1 = outExpo(b / 0.35), in2 = outExpo((b - 0.12) / 0.35);
                smearText(p, lit(first), Face.DISPLAY, x + (float) ((1 - in1) * 220), y - 20, 104, th.red, -1, slideVelocity(b, 0.35, -220));
                if (!second.isEmpty()) smearText(p, lit(second), Face.DISPLAY, x + (float) ((1 - in2) * 220), y + 76, 104, th.ink, -1, slideVelocity(b - 0.12, 0.35, -220));
                lineGrow(p, x, y + 140, x + 820, y + 132, 1.5f, a(th.ink, 0.5), outExpo((b - 0.2) / 0.6));
                micro(p, "", d.mode().getString() + "  //  " + d.players() + " PASSENGERS", x, y + 160, 9, a(th.ink, 0.7), a(th.ink, 0.7), -1);
                micro(p, "", d.duration() + "  //  120 BPM", x + 820, y + 160, 9, th.red, th.red, 1);
                float bx = x + 700, by = y - 10;
                frame(p, bx, by - 40, bx + 80, by + 40, 2f, th.ink);
                rect(p, bx + 10, by - 30, bx + 70, by + 30, th.ink);
                line(p, bx + 40, by + 40, bx - 20, by + 130, 1.2f, a(th.ink, 0.5));
                dot(p, bx - 20, by + 130, 4, th.red);
                p.pop();
            } else {
                double b = k - 2.0;
                ReplayKineticBackdrops.ink(p, th, k, "");
                double out = inOutQuart((k - REVEAL) / REVEAL_LEN);
                p.push();
                p.translate((float) (-out * (w + 200)), 0);
                float x = w / 2 - 330, y = h / 2 - 10;
                lineGrow(p, x - 30, y - 140, x - 30, y + 150, 3f, th.red, outExpo(b / 0.4));
                micro(p, "SEC.02", "CREDITS", x, y - 110, 9, th.red, a(0xFFFFFFFF, 0.7), -1);
                double ti = outExpo(b / 0.4);
                var credit = lit(d.mode().getString() + "  \u00d7  " + d.players() + "P");
                float maxW = 520, cpx = 66;
                float cw = p.textWidth(credit, Face.DISPLAY, cpx);
                if (cw > maxW) cpx *= maxW / cw;
                p.text(credit, Face.DISPLAY, x + (float) ((1 - ti) * 80), y - 50, cpx, a(0xFFFFFFFF, ti), -1);
                micro(p, "", "HABITRAIN  -  REPLAY ARRANGEMENT", x, y, 8.5f, a(0xFFFFFFFF, 0.6), a(0xFFFFFFFF, 0.6), -1);
                String[][] cr = {{"MAP", map}, {"DATE", d.date()}, {"TIME", d.duration()}, {"TRAIN", d.trainNo()}};
                for (int i = 0; i < cr.length; i++) {
                    double ca = smooth(0.2 + i * 0.1, 0.4 + i * 0.1, b);
                    float cx = x + (i % 2) * 260, cy = y + 50 + (i / 2) * 52;
                    rect(p, cx, cy - 12, cx + 3, cy + 22, a(th.red, ca));
                    micro(p, cr[i][0], "", cx + 12, cy - 4, 8, a(th.red, ca), 0, -1);
                    p.text(lit(cr[i][1]), Face.TEXT, cx + 12, cy + 14, 17, a(0xFFFFFFFF, ca), -1);
                }
                lineGrow(p, x + 580, y - 140, x + 580, y + 150, 2f, a(th.red, 0.8), outExpo((b - 0.1) / 0.5));
                double va = smooth(0.3, 0.6, b);
                String[] vert = {"\u54c8", "\u6bd4", "\u5217", "\u8f66"};
                for (int i = 0; i < vert.length; i++) p.text(lit(vert[i]), Face.TEXT, x + 640, y - 90 + i * 40, 30, a(0xFFFFFFFF, va), 0);
                micro(p, "", "REPLAY", x + 690, y + 90, 8, a(th.red, va), a(th.red, va), 0);
                p.pop();
                if (out > 0) rect(p, (float) (w - out * (w + 200)), 0, (float) (w - out * (w + 200)) + 12, h, th.red);
            }
        }
        lockup(p, th, k, map);
    }

    private static void card(ReplayKineticPen p, float cx, float cy, float cw, float ch, int fill, int accent,
                             String num, String label, boolean hatch, float rot) {
        p.push();
        p.translate(cx, cy);
        p.rotate(rot);
        rect(p, -cw / 2 + 8, -ch / 2 + 10, cw / 2 + 8, ch / 2 + 10, a(0xFF000000, 0.35));
        rect(p, -cw / 2, -ch / 2, cw / 2, ch / 2, fill);
        if (hatch) {
            for (float y = -ch / 2 + 12; y < ch / 2 - 46; y += 8) line(p, -cw / 2 + 12, y, cw / 2 - 12, y + 10, 1f, a(accent, 0.35));
        }
        p.text(lit(num), Face.DISPLAY, -cw / 2 + 14, ch / 2 - 22, 30, on(fill), -1);
        micro(p, "", label, cw / 2 - 12, ch / 2 - 20, 7.5f, a(on(fill), 0.7), a(on(fill), 0.7), 1);
        p.pop();
    }
}
