package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.client.replay.ReplayKineticPen.Face;

import java.util.List;
import java.util.Locale;

import static com.habitrain.core.scene.client.replay.ReplayKineticKit.*;

/** Closing acts: overview, victory chop sequence, MVP and the passenger list with the reticle sign-off. */
final class ReplayKineticFinale {
    private ReplayKineticFinale() {}

    private static double actAlpha(double k, double len) {
        return smooth(0, 0.35, k) * (1 - smooth(len - 0.4, len, k));
    }

    private static String heading(ReplayKineticTheme th, String fallback) {
        return switch (th) {
            case DOSSIER -> "SUBJECT REPORT";
            case BLUEPRINT -> "SECTION REVIEW";
            case MUSIC_VIDEO -> "OVERVIEW";
            default -> fallback;
        };
    }

    // ------------------------------------------------------------------ overview

    static void review(ReplayKineticPen p, ReplayKineticTheme th, double k, double len,
                       List<ReplayKineticData.Stat> stats, String sub) {
        double a = actAlpha(k, len);
        if (a <= 0.01) return;
        float w = p.width(), h = p.height();
        micro(p, th.code + ".03", "OVERVIEW", 60, 70, 10, a(th.red, a), a(th.ink, 0.7 * a), -1);
        String head = heading(th, "MATCH REPORT");
        typeOn(p, head, Face.DISPLAY, 60, 112, 56, a(th.ink, a), a(th.red, a), k / 0.6, true);
        if (sub != null) p.text(lit(sub), Face.TEXT, 60, 158, 16, a(th.ink, 0.7 * a), -1);
        int n = stats.size();
        float sw = 224, gap = 16, sx = (w - (sw * n + gap * (n - 1))) / 2, sy = h - 158;
        int[] fills = {th.ink, th.red, th.blue, th.ink};
        for (int i = 0; i < n; i++) {
            var st = stats.get(i);
            float x0 = sx + i * (sw + gap);
            double show = smooth(0.2 + i * 0.06, 0.35 + i * 0.06, k) * a;
            frame(p, x0, sy, x0 + sw, sy + 76, 1.2f, a(th.hair, show));
            micro(p, String.format(Locale.ROOT, "%02d", i + 1), "SLOT", x0 + 6, sy - 10, 7.5f, a(th.ink, 0.5 * show), a(th.ink, 0.5 * show), -1);
            double age = k - 0.45 - i * 0.14;
            if (age <= 0) continue;
            int fill = th == ReplayKineticTheme.BLUEPRINT && i % 2 == 0 ? th.ink : fills[i % fills.length];
            float slide = (float) ((1 - outExpo(age / 0.45)) * 360);
            smearSlab(p, x0 + slide, sy, x0 + sw + slide, sy + 76, 12, a(fill, a), slideVelocity(age, 0.45, -360));
            p.text(st.label(), Face.TEXT, x0 + 16 + slide, sy + 20, 13, a(on(fill), 0.8 * a), -1);
            p.text(lit(st.value()), Face.DISPLAY, x0 + sw - 16 + slide, sy + 50, 38, a(on(fill), a), 1);
        }
    }

    // ------------------------------------------------------------------ victory

    /** True while the chop sequence covers the frame. */
    static boolean victoryCovers(double k) { return k < 1.6; }

    static void victory(ReplayKineticPen p, ReplayKineticTheme th, double k, double len, ReplayKineticData.Victory v, double t) {
        float w = p.width(), h = p.height();
        int accent = duality(v.winColor(), th.red, th.blue);
        if (victoryCovers(k)) {
            int beat = (int) (k / 0.4);
            double bk = k - beat * 0.4;
            boolean ink = beat % 2 == 0;
            if (ink) ReplayKineticBackdrops.ink(p, th, t, "");
            else ReplayKineticBackdrops.paper(p, th, t, "");
            int fg = ink ? 0xFFFFFFFF : th.ink;
            String title = v.title().getString();
            int[] cps = title.codePoints().toArray();
            if (beat == 0) {
                String word = v.subjectWon() == null ? "VICTORY" : v.subjectWon() ? "WIN" : "LOST";
                double s = outBack(bk / 0.2);
                p.push();
                p.translate(w / 2, h / 2);
                float sc = (float) (1.4 - 0.4 * s);
                p.scale(sc, sc);
                p.text(lit(word), Face.DISPLAY, 0, 0, 130, fg, 0);
                p.pop();
                octahedron(p, w / 2, h / 2, 170, t * 1.5, 0.4, a(fg, 0.35), accent, 1.5f);
                rect(p, w / 2 - 220, h / 2 + 74, w / 2 + 220, h / 2 + 78, accent);
                micro(p, th.code + ".04", "OUTRO DROP  //  HIT 01", w / 2, h / 2 + 98, 9, accent, a(fg, 0.7), 0);
            } else {
                int idx = Math.min(beat - 1, Math.max(0, cps.length - 1));
                String glyph = cps.length == 0 ? "!" : new String(Character.toChars(cps[idx]));
                int fill = beat % 3 == 1 ? th.red : th.blue;
                double s = outBack(bk / 0.22);
                float rot = (float) Math.toRadians(beat % 2 == 0 ? 14 : -12);
                p.push();
                p.translate(w / 2, h / 2);
                p.rotate(rot);
                p.polyline(new float[]{-200, -200, 200, -200, 200, 200, -200, 200}, 4, 2f, a(fill, 0.8), true);
                p.rotate(-rot * 0.6f);
                float sc = (float) (0.6 + 0.4 * s);
                p.scale(sc, sc);
                rect(p, -78, -78, 78, 78, fill);
                p.text(lit(glyph), Face.TEXT, 0, 0, 96, 0xFFFFFFFF, 0);
                p.pop();
                ellipse(p, w / 2, h / 2, 230, 230, 0, 1f, a(fg, 0.25), 0, 1);
                dot(p, w / 2 - 200 * (float) Math.cos(rot), h / 2 - 200 * (float) Math.sin(rot) - 30, 3, fg);
            }
            return;
        }
        double kk = k - 1.6;
        double a = smooth(0, 0.2, kk) * (1 - smooth(len - 1.6 - 0.45, len - 1.6, kk));
        if (a <= 0.01) return;
        micro(p, th.code + ".04", "VICTORY", 90, 92, 10, a(accent, a), a(th.ink, 0.7 * a), -1);
        float tw = dropCap(p, v.title().getString(), 90, 160, 110, 48, a(accent, a), a(th.ink, a), kk);
        p.text(v.subtitle(), Face.TEXT, 92, 232, 16, a(th.ink, 0.7 * a), -1);
        if (Boolean.FALSE.equals(v.subjectWon())) {
            xStamp(p, 90 + tw / 2, 160, Math.min(150, tw / 2 + 20), (kk - 0.3) / 0.4, a(th.red, a), 7f);
            micro(p, "VERDICT", "INCORRECT  [ 00 PTS ]", 92, 262, 9, a(th.red, a), a(th.red, a), -1);
        }
        var winners = v.winners();
        int shown = Math.min(8, winners.size());
        if (shown == 0) return;
        float size = 58, spacing = Math.min(132, (w - 240) / shown), x0 = w / 2 - spacing * (shown - 1) / 2, y = h * 0.74f;
        micro(p, "", "WINNERS", w / 2, y - 66, 9, a(th.ink, 0.6 * a), a(th.ink, 0.6 * a), 0);
        for (int i = 0; i < shown; i++) {
            var who = winners.get(i);
            double pop = outBack((kk - 0.35 - i * 0.08) / 0.3);
            double pa = clamp01((kk - 0.35 - i * 0.08) / 0.1) * a;
            if (pa <= 0.01) continue;
            float cx = x0 + i * spacing, cy = y + (float) ((1 - pop) * 26);
            int tile = who.mvp() ? th.red : th.ink;
            rect(p, cx - size / 2 - 4, cy - size / 2 - 4, cx + size / 2 + 4, cy + size / 2 + 4, a(tile, pa));
            if (who.id() != null) p.face(who.id(), cx - size / 2, cy - size / 2, size, a(0xFFFFFFFF, pa));
            p.text(who.name(), Face.TEXT, cx, cy + size / 2 + 16, 13, a(th.ink, pa), 0);
            if (who.mvp()) micro(p, "", "MVP", cx, cy - size / 2 - 14, 8, a(th.red, pa), a(th.red, pa), 0);
        }
    }

    // ------------------------------------------------------------------ MVP

    static void mvp(ReplayKineticPen p, ReplayKineticTheme th, double k, double len, ReplayKineticData.Mvp m, double t) {
        double a = actAlpha(k, len);
        if (a <= 0.01) return;
        float w = p.width(), h = p.height();
        int accent = duality(m.who().color(), th.red, th.blue);
        float x0 = w * 0.53f, right = w - 60;
        double open = outExpo(k / 0.5);
        rect(p, (float) (right + 20 - (right + 20 - x0 + 30) * open), 80, right + 20, h - 80, a(th.paper, 0.92 * a));
        line(p, x0 - 30, 80, x0 - 30, h - 80, 2f, a(accent, a));
        micro(p, th.code + ".05", "MOST VALUABLE", x0, 112, 10, a(accent, a), a(th.ink, 0.7 * a), -1);
        float mx = x0 + 70, my = 186;
        ellipse(p, mx + 40, my, 130, 40, (float) Math.toRadians(-10), 1.5f, a(th.ink, 0.35 * a), 0, 1);
        float[] d = ellipsePoint(mx + 40, my, 130, 40, (float) Math.toRadians(-10), t * 0.35);
        dot(p, d[0], d[1], 4.5f, a(th.red, a));
        double slam = outBack((k - 0.15) / 0.3);
        p.push();
        p.translate(mx, my);
        float s = (float) (1 + 0.6 * (1 - slam));
        p.scale(s, s);
        p.text(lit("MVP"), Face.DISPLAY, 0, 0, 104, a(th.ink, a * clamp01((k - 0.15) / 0.08)), 0);
        p.pop();
        float fx = right - 70, fy = 186;
        octahedron(p, fx, fy, 72, t * 0.7, 0.4, a(th.ink, 0.3 * a), a(accent, 0.8 * a), 1.3f);
        if (m.who().id() != null) {
            rect(p, fx - 40, fy - 40, fx + 40, fy + 40, a(th.ink, a));
            p.face(m.who().id(), fx - 36, fy - 36, 72, a(0xFFFFFFFF, a));
        }
        typeOn(p, m.who().name().getString(), Face.TEXT, x0, 272, 38, a(th.ink, a), a(accent, a), (k - 0.4) / 0.5, true);
        double ra = (k - 0.7) / 0.35;
        if (ra > 0) {
            float rw = p.textWidth(m.who().role(), Face.TEXT, 16) + 30;
            float sl = (float) ((1 - outExpo(ra)) * 160);
            smearSlab(p, x0 + sl, 304, x0 + rw + sl, 332, 8, a(accent, a), slideVelocity(k - 0.7, 0.35, -160));
            p.text(m.who().role(), Face.TEXT, x0 + 15 + sl, 318, 16, a(on(accent), a), -1);
        }
        for (int i = 0; i < m.stats().size(); i++) {
            var st = m.stats().get(i);
            double sa = outCubic((k - 0.9 - i * 0.12) / 0.4) * a;
            if (sa <= 0.01) continue;
            float ry = 372 + i * 52;
            p.text(st.label(), Face.TEXT, x0, ry, 14, a(th.ink, 0.7 * sa), -1);
            p.text(lit(st.value()), Face.DISPLAY, right, ry, 30, a(st.color(), sa), 1);
            double ratio = i < m.ratios().length ? m.ratios()[i] : 0;
            ruler(p, x0, right - 90, ry + 20, 20, Math.clamp(ratio, 0.04, 1) * outCubic((k - 0.9 - i * 0.12) / 1.0),
                    a(th.ink, 0.3 * sa), a(st.color(), sa));
        }
    }

    // ------------------------------------------------------------------ passenger list + sign-off

    static void outro(ReplayKineticPen p, ReplayKineticTheme th, double k, double len, ReplayKineticData.Outro o, double t) {
        float w = p.width(), h = p.height();
        double a = smooth(0, 0.35, k);
        float x0 = w * 0.40f, x1 = w - 44, y0 = 64, y1 = h - 96;
        double open = outExpo(k / 0.45);
        float px1 = x0 + (x1 - x0) * (float) open;
        rect(p, x0, y0, px1, y1, a(th.night, 0.94 * a));
        rect(p, x0, y0, x0 + 4, y1, a(th.red, a));
        if (open > 0.97) {
            micro(p, th.code + ".06", "PASSENGER LIST", x0 + 28, y0 + 28, 9.5f, a(th.red, a), a(0xFFFFFFFF, 0.7 * a), -1);
            p.text(o.heading(), Face.TEXT, x1 - 26, y0 + 28, 18, a(0xFFFFFFFF, a), 1);
            float[] cols = {x0 + 44, x0 + 80, x0 + 300, x1 - 210, x1 - 130, x1 - 30};
            float hy = y0 + 62;
            line(p, x0 + 24, hy, x1 - 20, hy, 1f, a(0xFFFFFFFF, 0.25 * a));
            int rowsN = Math.max(1, o.rows().size());
            float rh = Math.min(42, (y1 - hy - 70) / rowsN);
            for (int i = 0; i < o.rows().size(); i++) {
                var r = o.rows().get(i);
                double enter = o.initialEntry() ? outExpo((k - 0.5 - i * 0.06) / 0.35) : 1;
                double ra = enter * o.pageAlpha() * a;
                if (ra <= 0.01) continue;
                float y = hy + 14 + rh * (i + 0.5f);
                float off = (float) ((1 - enter) * 80) + o.slide();
                if (r.focus()) cutSlab(p, x0 + 20 + off, y - rh / 2 + 3, x1 - 20, y + rh / 2 - 3, 8, a(th.blue, 0.55 * ra));
                int tile = r.rank() == 1 ? th.red : 0xFF2A2F3D;
                rect(p, cols[0] - 15 + off, y - 15, cols[0] + 15 + off, y + 15, a(tile, ra));
                p.text(lit(String.valueOf(r.rank())), Face.DISPLAY, cols[0] + off, y, 22, a(0xFFFFFFFF, ra), 0);
                float fs = Math.min(28, rh - 8);
                if (r.who().id() != null) p.face(r.who().id(), cols[1] + off, y - fs / 2, fs, a(r.who().winner() ? 0xFFFFFFFF : 0xFFAAAAAA, ra));
                p.text(r.who().name(), Face.TEXT, cols[1] + fs + 10 + off, y, 15, a(0xFFFFFFFF, ra), -1);
                p.text(r.who().role(), Face.TEXT, cols[2] + off, y, 12, a(mix(r.who().color(), 0xFFFFFFFF, 0.35), ra), -1);
                p.text(lit(String.valueOf(r.kills())), Face.DISPLAY, cols[3] + off, y, 22, a(th.red, ra), 0);
                p.text(lit(String.valueOf(r.score())), Face.DISPLAY, cols[4] + off, y, 22, a(0xFFFFFFFF, ra), 0);
                tracked(p, r.survival(), Face.MONO, cols[5] + off, y, 10, 1.5f, a(0xFFFFFFFF, 0.7 * ra), 1);
            }
            if (o.pages() > 1) {
                ruler(p, (x0 + x1) / 2 - 80, (x0 + x1) / 2 + 80, y1 - 46, o.pages() * 2, (o.page() + 1) / (double) o.pages(),
                        a(0xFFFFFFFF, 0.4 * a), a(th.red, a));
            }
            double fa = smooth(1.2, 1.7, k) * a;
            p.text(o.killsLine(), Face.TEXT, (x0 + x1) / 2, y1 - 20, 12, a(0xFFFFFFFF, 0.65 * fa), 0);
        }
        p.text(o.footer(), Face.TEXT, 60, h - 34, 11, a(0xFFFFFFFF, 0.5 * a), -1);
        signOff(p, th, k - (len - 1.5), t);
    }

    /** Final reticle lock (the MV ending): ink cover, circle draw-on, title locks into the brackets. */
    static void signOff(ReplayKineticPen p, ReplayKineticTheme th, double k, double t) {
        if (k <= 0) return;
        float w = p.width(), h = p.height();
        rect(p, 0, 0, w, h, a(th.ink, smooth(0, 0.3, k)));
        if (k < 0.2) return;
        double kk = k - 0.2;
        crosshair(p, w / 2, h / 2, a(0xFFFFFFFF, 0.12), kk / 0.4);
        ellipse(p, w / 2, h / 2, 120, 120, 0, 1.2f, a(0xFFFFFFFF, 0.3), 0, 1);
        ellipse(p, w / 2, h / 2, 104, 104, (float) (-Math.PI / 2), 2f, th.red, 0, outCubic(kk / 0.6) * 0.92);
        reticle(p, w / 2, h / 2, 190, 50, 14, 2f, a(0xFFFFFFFF, 0.8), kk / 0.4);
        double ta = smooth(0.25, 0.45, kk);
        p.text(lit("HABITRAIN"), Face.DISPLAY, w / 2, h / 2, 54, a(0xFFFFFFFF, ta), 0);
        rect(p, w / 2 - 120, h / 2 + 30, w / 2 + 120, h / 2 + 33, a(th.red, ta));
        micro(p, "END", "FINAL STATION  //  LOCKING", w / 2, h / 2 + 50, 8.5f, a(th.red, ta), a(0xFFFFFFFF, 0.7 * ta), 0);
        dot(p, w / 2 - 150, h / 2 - 40, 3, a(th.red, ta));
    }
}
