package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.client.replay.ReplayKineticPen.Face;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

import static com.habitrain.core.scene.client.replay.ReplayKineticKit.*;

/** Graphics layered over the live action: HUD, feed, event headlines, 3D-anchored marks and per-mode chrome. */
final class ReplayKineticAction {
    private ReplayKineticAction() {}

    // ------------------------------------------------------------------ HUD

    static void hud(ReplayKineticPen p, ReplayKineticTheme th, ReplayKineticData.Hud d, double alpha, double t,
                    boolean clock, boolean aliveBlock, boolean timeline) {
        if (alpha <= 0.01) return;
        float w = p.width(), h = p.height();
        int white = a(0xFFFFFFFF, alpha);
        if (clock) {
            float cw = p.textWidth(lit(d.clock()), Face.DISPLAY, 46);
            p.text(lit(d.clock()), Face.DISPLAY, 44, 74, 46, white, -1);
            int chip = d.speedKind() == 2 ? th.red : d.speedKind() == 1 ? th.blue : th.paper;
            float tw = p.textWidth(lit(d.speed()), Face.DISPLAY, 20) + 24;
            float cx = 44 + cw + 14;
            cutSlab(p, cx, 62, cx + tw, 86, 7, a(chip, alpha));
            p.text(lit(d.speed()), Face.DISPLAY, cx + tw / 2, 75, 20, a(on(chip), alpha), 0);
            if (d.speedKind() == 0 && d.speed().length() > 1 && parseSpeed(d.speed()) > 1.6) {
                for (int i = 0; i < 4; i++) {
                    double ph = frac(t * 2.4 - i * 0.2);
                    float px = cx + tw + 14 + i * 11;
                    int c = a(th.red, alpha * (0.25 + 0.75 * ph));
                    p.polyline(new float[]{px, 68, px + 6, 74, px, 80}, 3, 2.5f, c, false);
                }
            }
        }
        if (aliveBlock) {
            micro(p, "ALIVE", "", w - 44, 52, 8.5f, a(th.red, alpha), 0, 1);
            p.text(lit(d.alive() + "/" + d.total()), Face.DISPLAY, w - 44, 78, 34, white, 1);
            int shown = Math.min(d.total(), 24);
            float x0 = w - 44 - shown * 14 + 2;
            for (int i = 0; i < shown; i++) {
                float x = x0 + i * 14, y = 102;
                if (d.states()[i]) rect(p, x, y, x + 11, y + 11, a(d.colors()[i], alpha));
                else {
                    frame(p, x, y, x + 11, y + 11, 1f, a(0xFFFFFFFF, 0.35 * alpha));
                    xStamp(p, x + 5.5f, y + 5.5f, 4.5f, 1, a(th.red, alpha), 1.6f);
                }
            }
        }
        if (timeline) {
            float x0 = 250, x1 = w - 330, y = h - 52;
            line(p, x0, y, x1, y, 1.5f, a(0xFFFFFFFF, 0.3 * alpha));
            float px = x0 + (float) ((x1 - x0) * clamp01(d.progress()));
            rect(p, x0, y - 2, px, y + 2, a(th.red, alpha));
            for (int i = 0; i < d.markers().length; i++) {
                float mx = x0 + (float) ((x1 - x0) * clamp01(d.markers()[i]));
                boolean past = d.markers()[i] <= d.progress();
                line(p, mx, y - (past ? 9 : 6), mx, y + (past ? 9 : 6), 2f, a(d.markerColors()[i], (past ? 1 : 0.5) * alpha));
            }
            diamond(p, px, y, 6, white);
            p.text(d.map(), Face.TEXT, x0 - 14, y, 13, a(0xFFFFFFFF, 0.75 * alpha), 1);
            tracked(p, d.endClock(), Face.MONO, x1 + 14, y, 9, 2, a(0xFFFFFFFF, 0.6 * alpha), -1);
        }
    }

    private static double parseSpeed(String s) {
        try {
            return Double.parseDouble(s.replace("\u00d7", "").trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static void feed(ReplayKineticPen p, ReplayKineticTheme th, List<ReplayKineticData.FeedRow> rows, double alpha) {
        float w = p.width();
        float y = 150;
        for (var r : rows) {
            double in = outExpo(r.age() / 0.35), out = 1 - smooth(2.6, 3.2, r.age());
            double a = in * out * alpha;
            if (a <= 0.01) { y += 34; continue; }
            float tw = Math.min(380, p.textWidth(r.line(), Face.TEXT, 14)) + 40;
            float x1 = w - 40 + (float) ((1 - in) * 160), x0 = x1 - tw;
            float vel = slideVelocity(r.age(), 0.35, -160);
            smearSlab(p, x0, y - 14, x1, y + 14, 8, a(th.ink, 0.86 * a), vel);
            rect(p, x1 - 4, y - 14, x1, y + 14, a(r.color(), a));
            p.text(r.line(), Face.TEXT, x0 + 14, y, 14, a(0xFFFFFFFF, a), -1);
            y += 34;
        }
    }

    // ------------------------------------------------------------------ impacts & headlines

    /** Two-frame photographic negative on impact, then a paper flash (MV polarity hit). */
    static void impact(ReplayKineticPen p, ReplayKineticTheme th, double u, double strength) {
        if (u < 0 || u > 0.4) return;
        if (u < 0.067) p.invert(0, 0, p.width(), p.height(), strength);
        else rect(p, 0, 0, p.width(), p.height(), a(th.paper, 0.35 * strength * (1 - smooth(0.067, 0.4, u))));
    }

    /** Headline for one event beat; layout depends on the theme. {@code u} is seconds since the beat. */
    static void headline(ReplayKineticPen p, ReplayKineticTheme th, ReplayKineticData.Kill k, double u) {
        if (u < 0 || u > 2.7) return;
        double out = inCubic((u - 2.2) / 0.45);
        double alpha = 1 - out;
        float shift = (float) (out * 300);
        p.push();
        p.translate(shift, 0);
        switch (th) {
            case KINETIC_CUT, MUSIC_VIDEO -> tiles(p, th, k, u, alpha, out > 0 ? (float) (out * 26) : 0);
            case DOSSIER -> logLine(p, th, k, u, alpha);
            case BLUEPRINT -> annotationCard(p, th, k, u, alpha);
        }
        p.pop();
    }

    private static void tiles(ReplayKineticPen p, ReplayKineticTheme th, ReplayKineticData.Kill k, double u, double alpha, float exitVel) {
        float w = p.width(), h = p.height();
        float y = h * 0.2f;
        int secondary = k.accent() == th.red ? th.blue : th.red;
        int[] fills = {th.paper, k.accent(), th.paper, secondary};
        micro(p, String.format(Locale.ROOT, "K.%02d", k.index() + 1), k.weapon().getString(), w / 2, y - 44, 9,
                a(k.accent(), alpha), a(0xFFFFFFFF, 0.8 * alpha), 0);
        glyphTiles(p, k.word(), Face.DISPLAY, w / 2, y, 34, u, 0.035, fills, 0, alpha);
        double s = (u - 0.25) / 0.4;
        if (s > 0) {
            Component tag = k.tag();
            float tw = p.textWidth(tag, Face.TEXT, 17) + 40;
            float slide = (float) ((1 - outExpo(s)) * -260);
            float vel = exitVel != 0 ? exitVel : slideVelocity(u - 0.25, 0.4, 260);
            float x0 = w / 2 - tw / 2 + slide;
            smearSlab(p, x0, y + 34, x0 + tw, y + 66, 9, a(k.accent(), alpha), vel);
            p.text(tag, Face.TEXT, x0 + tw / 2, y + 50, 17, a(on(k.accent()), alpha), 0);
        }
    }

    private static void logLine(ReplayKineticPen p, ReplayKineticTheme th, ReplayKineticData.Kill k, double u, double alpha) {
        float h = p.height();
        float x = 64, y = h * 0.62f;
        int accent = k.accent();
        micro(p, String.format(Locale.ROOT, "LOG.%02d", k.index() + 1), k.word(), x, y - 46, 9, a(accent, alpha), a(0xFFFFFFFF, 0.7 * alpha), -1);
        String killer = k.killer() == null ? "?" : k.killer().name().getString();
        float kw = typeOn(p, killer, Face.TEXT, x, y - 12, 30, a(0xFFFFFFFF, alpha), a(accent, alpha), u / 0.35, true);
        double s = (u - 0.3) / 0.35;
        if (s > 0) {
            float tw = p.textWidth(k.tag(), Face.TEXT, 16) + 30;
            float sx = x + kw + 18 + (float) ((1 - outExpo(s)) * 120);
            smearSlab(p, sx, y - 27, sx + tw, y + 3, 8, a(accent, alpha), slideVelocity(u - 0.3, 0.35, -120));
            p.text(k.tag(), Face.TEXT, sx + 15, y - 12, 16, a(on(accent), alpha), -1);
        }
        if (k.victim() != null && u > 0.55) {
            String victim = k.victim().name().getString();
            float vw = p.textWidth(lit(victim), Face.TEXT, 22);
            p.text(lit(victim), Face.TEXT, x, y + 30, 22, a(0xFFFFFFFF, 0.75 * alpha), -1);
            lineGrow(p, x - 6, y + 32, x + vw + 6, y + 26, 3f, a(th.red, alpha), outExpo((u - 0.7) / 0.3));
        }
    }

    private static void annotationCard(ReplayKineticPen p, ReplayKineticTheme th, ReplayKineticData.Kill k, double u, double alpha) {
        float x = 60, y = 150;
        int c = a(k.accent(), alpha);
        double open = outExpo(u / 0.35);
        float bw = (float) (360 * open);
        frame(p, x, y, x + bw, y + 92, 1.5f, a(0xFFFFFFFF, 0.6 * alpha));
        rect(p, x, y, x + bw, y + 92, a(th.actionBackdrop(), 0.7 * alpha));
        dot(p, x + 26, y + 28, 15, c);
        p.text(lit(String.format(Locale.ROOT, "E%02d", k.index() + 1)), Face.DISPLAY, x + 26, y + 29, 15, a(0xFFFFFFFF, alpha), 0);
        if (open > 0.9) {
            micro(p, k.word(), "", x + 52, y + 22, 9, c, 0, -1);
            typeOn(p, k.tag().getString(), Face.TEXT, x + 52, y + 44, 16, a(0xFFFFFFFF, alpha), 0, (u - 0.3) / 0.4, false);
            micro(p, "SRC", k.weapon().getString(), x + 52, y + 72, 8, a(0xFFFFFFFF, 0.5 * alpha), a(0xFFFFFFFF, 0.5 * alpha), -1);
        }
    }

    /** 3D-anchored mark: closing reticle, X stamp on eliminations, leader line to a micro label. */
    static void mark(ReplayKineticPen p, ReplayKineticTheme th, float x, float y, String label, int color, double u,
                     boolean elimination, int index, float maxRight) {
        if (u > 2.4) return;
        double a = (u < 0 ? 0.6 : 1) * (1 - smooth(1.9, 2.4, u));
        if (th == ReplayKineticTheme.BLUEPRINT) {
            ellipse(p, x, y, 18, 18, 0, 1.5f, a(color, a), 0, outCubic((u + 0.4) / 0.5));
            line(p, x - 26, y, x + 26, y, 1f, a(color, a));
            line(p, x, y - 26, x, y + 26, 1f, a(color, a));
        } else {
            reticle(p, x, y, 22, 22, 9, 2f, a(color, a), (u + 0.4) / 0.5);
        }
        if (u < 0) return;
        if (elimination) xStamp(p, x, y, 12, u / 0.3, a(th.red, a), 3f);
        double grow = outExpo(u / 0.3);
        boolean right = x + 200 < maxRight;
        float ex = x + (right ? 54 : -54), ey = y - 46;
        lineGrow(p, x, y, ex, ey, 1.5f, a(color, a), grow);
        if (grow > 0.95) {
            float lx1 = right ? ex + 150 : ex - 150;
            lineGrow(p, ex, ey, lx1, ey, 1.5f, a(color, a), outExpo((u - 0.25) / 0.3));
            String code = th == ReplayKineticTheme.BLUEPRINT ? String.format(Locale.ROOT, "E%02d", index + 1) : "";
            micro(p, code, label, right ? ex + 2 : ex - 2, ey - 10, 8.5f, a(color, a), a(0xFFFFFFFF, a), right ? -1 : 1);
        }
    }

    // ------------------------------------------------------------------ split screen

    static void splitSeam(ReplayKineticPen p, ReplayKineticTheme th, float topX, float botX, Component pov, double alpha) {
        if (alpha <= 0.01) return;
        float h = p.height(), w = p.width();
        line(p, topX, 0, botX, h, 6f, a(th.ink, alpha));
        line(p, topX + 5, 0, botX + 5, h, 1.5f, a(th.red, alpha));
        for (int i = 1; i < 18; i++) {
            float f = i / 18f;
            float x = topX + (botX - topX) * f, y = h * f;
            line(p, x - (i % 3 == 0 ? 10 : 5), y, x, y, 1.5f, a(0xFFFFFFFF, 0.6 * alpha));
        }
        if (pov != null) {
            float tw = trackedWidth(p, "POV  //  " + pov.getString(), Face.MONO, 9, 2.5f) + 24;
            float bx = Math.max(botX + 24, w - 36 - tw), by = h - 92;
            rect(p, bx, by - 12, bx + tw, by + 12, a(th.ink, 0.9 * alpha));
            rect(p, bx, by - 12, bx + 4, by + 12, a(th.red, alpha));
            micro(p, "POV", pov.getString(), bx + 12, by, 9, a(th.red, alpha), a(0xFFFFFFFF, alpha), -1);
        }
    }

    // ------------------------------------------------------------------ personal (dossier) chrome

    /** First-person viewfinder: corner brackets, hairline crosshair, REC tag and a scrolling tape ruler. */
    static void viewfinder(ReplayKineticPen p, ReplayKineticTheme th, double t, double matchSeconds, double speed,
                           int accent, double alpha) {
        if (alpha <= 0.01) return;
        float w = p.width(), h = p.height();
        int c = a(0xFFFFFFFF, 0.7 * alpha);
        reticle(p, w / 2, h / 2, w / 2 - 40, h / 2 - 40, 34, 2f, c, 1);
        line(p, w / 2 - 18, h / 2, w / 2 + 18, h / 2, 1.5f, c);
        line(p, w / 2, h / 2 - 18, w / 2, h / 2 + 18, 1.5f, c);
        line(p, 40, h / 2, w / 2 - 40, h / 2, 1f, a(0xFFFFFFFF, 0.1 * alpha));
        line(p, w / 2 + 40, h / 2, w - 40, h / 2, 1f, a(0xFFFFFFFF, 0.1 * alpha));
        boolean blink = (t % 1.0) < 0.6;
        dot(p, 66, h - 112, 5, a(th.red, (blink ? 1 : 0.3) * alpha));
        micro(p, "REC", "POV", 78, h - 112, 9, c, c, -1);
        float x0 = w / 2 - 260, x1 = w / 2 + 260, y = h - 76;
        float off = (float) ((matchSeconds * 20) % 20);
        for (float x = x0 - off; x <= x1; x += 20) {
            if (x < x0) continue;
            int idx = (int) Math.round((x - x0 + off) / 20 + Math.floor(matchSeconds));
            line(p, x, y - (idx % 5 == 0 ? 9 : 4), x, y, 1.2f, a(0xFFFFFFFF, 0.5 * alpha));
        }
        line(p, x0, y, x1, y, 1f, a(0xFFFFFFFF, 0.3 * alpha));
        p.polyline(new float[]{w / 2 - 6, y + 9, w / 2, y + 2, w / 2 + 6, y + 9}, 3, 2f, a(accent, alpha), true);
        if (speed > 1.05) fastForward(p, th, speed, t, alpha);
    }

    /** Tape fast-forward language for 2x / 4x: ghost multiplier and running chevrons. */
    static void fastForward(ReplayKineticPen p, ReplayKineticTheme th, double speed, double t, double alpha) {
        float w = p.width(), h = p.height();
        String label = String.format(Locale.ROOT, "%.0f\u00d7", speed);
        ghost(p, label, w - 60, h - 150, 170, a(0xFFFFFFFF, 0.08 * alpha), 1);
        for (int i = 0; i < 4; i++) {
            double ph = frac(t * speed * 0.9 - i * 0.18);
            float x = w - 250 + i * 22;
            p.polyline(new float[]{x, h - 128, x + 12, h - 116, x, h - 104}, 3, 3f, a(th.red, alpha * (0.2 + 0.8 * ph)), false);
        }
        micro(p, "FFWD", label, w - 60, h - 92, 9, a(th.red, alpha), a(0xFFFFFFFF, 0.7 * alpha), 1);
    }

    /** Orbit ring with a travelling dot around the followed subject plus a leader label. */
    static void orbitMarker(ReplayKineticPen p, ReplayKineticTheme th, float x, float y, Component name, int accent,
                            double t, double alpha) {
        if (alpha <= 0.01) return;
        float rot = (float) Math.toRadians(-8);
        ellipse(p, x, y, 46, 14, rot, 1.5f, a(accent, 0.8 * alpha), 0, 1);
        float[] d = ellipsePoint(x, y, 46, 14, rot, frac(t * 0.6));
        dot(p, d[0], d[1], 3.5f, a(0xFFFFFFFF, alpha));
        line(p, x, y - 16, x + 40, y - 70, 1.2f, a(accent, alpha));
        line(p, x + 40, y - 70, x + 170, y - 70, 1.2f, a(accent, alpha));
        micro(p, "SUBJECT", name.getString(), x + 44, y - 80, 8.5f, a(accent, alpha), a(0xFFFFFFFF, alpha), -1);
    }

    // ------------------------------------------------------------------ auto-edit (music video) tiers

    /** Tier 1: one lyric-like type-on line at the lower left. */
    static void lyricLine(ReplayKineticPen p, ReplayKineticTheme th, Component line, int accent, double prog, double alpha) {
        if (alpha <= 0.01) return;
        float h = p.height();
        micro(p, "T1", "GLIMMER", 64, h - 168, 8.5f, a(accent, alpha), a(0xFFFFFFFF, 0.7 * alpha), -1);
        typeOn(p, line.getString(), Face.TEXT, 64, h - 136, 26, a(0xFFFFFFFF, alpha), a(accent, alpha), prog / 0.6, true);
    }

    /** Tier 3: drop-cap lyric composition, diagonal slash and an orbit ellipse. */
    static void heroLyric(ReplayKineticPen p, ReplayKineticTheme th, String tag, String word, int accent, double age, double alpha) {
        if (alpha <= 0.01) return;
        float w = p.width(), h = p.height();
        lineGrow(p, -40, h * 0.78f, w + 40, h * 0.14f, 2f, a(accent, 0.85 * alpha), outExpo(age / 0.5));
        ellipse(p, w * 0.3f, h * 0.3f, 150, 54, (float) Math.toRadians(-14), 1.5f, a(0xFFFFFFFF, 0.5 * alpha), 0, outCubic(age / 0.8));
        float[] d = ellipsePoint(w * 0.3f, h * 0.3f, 150, 54, (float) Math.toRadians(-14), age * 0.4);
        dot(p, d[0], d[1], 4, a(accent, alpha));
        micro(p, "T3", word, 90, h * 0.3f - 74, 9, a(accent, alpha), a(0xFFFFFFFF, 0.75 * alpha), -1);
        p.push();
        dropCap(p, tag, 90, h * 0.3f, 110, 44, a(accent, alpha), a(0xFFFFFFFF, alpha), age);
        p.pop();
    }

    /** Tier 5: counter-rotating rectangle tunnel, audio bars and a giant word. */
    static void apex(ReplayKineticPen p, ReplayKineticTheme th, String word, double prog, double t, double alpha) {
        if (alpha <= 0.01) return;
        float w = p.width(), h = p.height();
        rect(p, 0, 0, w, h, a(th.ink, 0.35 * alpha));
        tunnel(p, w / 2, h / 2, w * 0.95f, h * 1.1f, 9, t, a(th.red, alpha), a(th.blue, alpha), 0.9 * alpha);
        eqBars(p, w / 2 - 330, h / 2, 9, 9, 4, 120, t, a(0xFFFFFFFF, 0.5 * alpha), a(th.blue, alpha), 2, true);
        eqBars(p, w / 2 + 330, h / 2, 9, 9, 4, 120, t + 0.37, a(0xFFFFFFFF, 0.5 * alpha), a(th.red, alpha), 2, false);
        double slam = outBack(prog / 0.3);
        p.push();
        p.translate(w / 2, h / 2 - 6);
        float s = (float) (1 + 0.5 * (1 - slam));
        p.scale(s, s);
        p.text(lit(word), Face.DISPLAY, 0, 0, 92, a(0xFFFFFFFF, alpha * clamp01(prog / 0.08)), 0);
        p.pop();
        rect(p, w / 2 - 200, h / 2 + 52, w / 2 + 200, h / 2 + 55, a(th.red, alpha));
        micro(p, "SYNC PULSE", "05 / 05", w / 2, h / 2 + 76, 9, a(th.red, alpha), a(0xFFFFFFFF, 0.8 * alpha), 0);
    }
}
