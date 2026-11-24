package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.client.replay.ReplayKineticPen.Face;

import static com.habitrain.core.scene.client.replay.ReplayKineticKit.*;

/** Paper / ink / blueprint backdrops, frame chrome and the kinetic transitions. */
final class ReplayKineticBackdrops {
    private ReplayKineticBackdrops() {}

    /** Off-white paper sheet (or blueprint sheet) with dot grids, hairlines and a ghost word. */
    static void paper(ReplayKineticPen p, ReplayKineticTheme th, double t, String ghost) {
        float w = p.width(), h = p.height();
        rectV(p, 0, 0, w, h, th.paper, mix(th.paper, PAPER_SHADE, 0.45));
        if (th == ReplayKineticTheme.BLUEPRINT) {
            for (float x = 0; x <= w; x += 32) line(p, x, 0, x, h, x % 160 == 0 ? 1.4f : 0.8f, a(th.hair, x % 160 == 0 ? 0.75 : 0.35));
            for (float y = 0; y <= h; y += 32) line(p, 0, y, w, y, y % 160 == 0 ? 1.4f : 0.8f, a(th.hair, y % 160 == 0 ? 0.75 : 0.35));
        } else {
            dotGrid(p, 64, 74, 4, 3, 10, 1.2f, a(th.ink, 0.22));
            dotGrid(p, w - 170, 96, 5, 3, 10, 1.2f, a(th.ink, 0.22));
            dotGrid(p, w - 230, h - 132, 4, 4, 10, 1.2f, a(th.ink, 0.16));
            float drift = (float) Math.sin(t * 0.35) * 6;
            line(p, -20, h * 0.66f + drift, w + 20, h * 0.60f + drift, 1f, a(th.ink, 0.12));
        }
        if (ghost != null && !ghost.isEmpty()) ghost(p, ghost, w * 0.76f, h * 0.52f, 300, a(th.ink, 0.05), 0);
        p.vignette(0.18);
    }

    /** Ink sheet used by typographic scenes. */
    static void ink(ReplayKineticPen p, ReplayKineticTheme th, double t, String ghost) {
        float w = p.width(), h = p.height();
        rectV(p, 0, 0, w, h, th.night, th.ink);
        if (th == ReplayKineticTheme.BLUEPRINT) {
            for (float x = 0; x <= w; x += 40) line(p, x, 0, x, h, 1f, a(th.actionGrid(), 0.6));
            for (float y = 0; y <= h; y += 40) line(p, 0, y, w, y, 1f, a(th.actionGrid(), 0.6));
        } else {
            dotGrid(p, 64, 74, 4, 3, 10, 1.2f, a(0xFFFFFFFF, 0.14));
            dotGrid(p, w - 170, 96, 5, 3, 10, 1.2f, a(0xFFFFFFFF, 0.14));
        }
        if (ghost != null && !ghost.isEmpty()) ghost(p, ghost, w * 0.56f, h * 0.5f, 300, a(0xFFFFFFFF, 0.045), 0);
        p.vignette(0.55);
    }

    /** Dark backdrop behind the live 3D diorama: flat ink with a faint engineering grid and a stage glow. */
    static void action(ReplayKineticPen p, ReplayKineticTheme th, double t) {
        float w = p.width(), h = p.height();
        int bg = th.actionBackdrop();
        rectV(p, 0, 0, w, h, mix(bg, 0xFF000000, 0.25), bg);
        int grid = a(th.actionGrid(), 0.55);
        float off = (float) ((t * 6) % 64);
        for (float x = -64 + off; x <= w; x += 64) line(p, x, 0, x, h, 1f, grid);
        for (float y = 0; y <= h; y += 64) line(p, 0, y, w, y, 1f, grid);
        p.glow(w / 2, h * 0.55f, h * 1.8f, h * 1.3f, a(th == ReplayKineticTheme.BLUEPRINT ? th.blue : 0xFF3A4A6A, 0.22));
        dotGrid(p, 64, 110, 4, 3, 10, 1.2f, a(0xFFFFFFFF, 0.12));
    }

    /** Persistent HUD frame: corner micro labels, progress hairline and timecode. */
    static void chrome(ReplayKineticPen p, ReplayKineticTheme th, ReplayKineticData.Chrome c, boolean onPaper, double alpha) {
        if (alpha <= 0.01) return;
        float w = p.width(), h = p.height();
        int fg = onPaper ? th.ink : 0xFFFFFFFF;
        rect(p, 34, 26, 41, 33, a(c.accent(), alpha));
        micro(p, c.code(), c.label(), 50, 30, 8.5f, a(c.accent(), 0.9 * alpha), a(fg, 0.55 * alpha), -1);
        rect(p, w - 78, 28, w - 34, 31, a(c.accent(), alpha));
        micro(p, th.code, "HABITRAIN", w - 88, 30, 8.5f, a(fg, 0.5 * alpha), a(fg, 0.5 * alpha), 1);
        float py = h - 26;
        line(p, 34, py, 214, py, 1.5f, a(fg, 0.22 * alpha));
        rect(p, 34, py - 1.5f, 34 + (float) (180 * clamp01(c.progress())), py + 1.5f, a(c.accent(), alpha));
        micro(p, "", c.bottomLeft(), 222, py, 8f, a(fg, 0.5 * alpha), a(fg, 0.5 * alpha), -1);
        micro(p, "", c.right(), w - 34, py, 8.5f, a(fg, 0.55 * alpha), a(fg, 0.55 * alpha), 1);
    }

    /**
     * Act-to-act wipe: a paper band leads, a full-height ink panel follows; the panel fully covers the frame at
     * {@code progress = 0.5} when the act underneath switches. Carries a ghost code and the next section label.
     */
    static void polarityWipe(ReplayKineticPen p, ReplayKineticTheme th, double progress, String code, String label) {
        if (progress < 0 || progress > 1) return;
        float w = p.width(), h = p.height();
        double e = inOutQuart(progress);
        float skew = h * 0.22f;
        float panel = w * 1.45f;
        float left = (float) (-panel - skew - 140 + (w + panel + skew * 2 + 280) * e);
        float right = left + panel;
        float vel = (float) ((inOutQuart(progress) - inOutQuart(progress - 0.03)) * (w + panel));
        for (int i = 3; i >= 1; i--) {
            float o = vel * i * 0.6f;
            slant(p, right - o, right + 120 - o, h, skew, a(th.paper, 0.18 / i));
        }
        slant(p, right, right + 120, h, skew, th.paper);
        slant(p, right + 120, right + 128, h, skew, th.red);
        slant(p, left, right, h, skew, th.ink);
        double mid = 1 - Math.min(1, Math.abs(progress - 0.5) * 4);
        if (mid > 0) {
            ghost(p, code, w / 2, h / 2, 260, a(0xFFFFFFFF, 0.07 * mid), 0);
            micro(p, "NEXT", label, w / 2, h / 2, 12, a(th.red, mid), a(0xFFFFFFFF, mid), 0);
            ruler(p, w / 2 - 140, w / 2 + 140, h / 2 + 30, 16, progress, a(0xFFFFFFFF, 0.4 * mid), a(th.red, mid));
        }
    }

    private static void slant(ReplayKineticPen p, float x0, float x1, float h, float skew, int c) {
        p.quad(x0 + skew, 0, c, x0 - skew, h, c, x1 - skew, h, c, x1 + skew, 0, c);
    }

    /**
     * Bridge-style chapter card (MV "01 // SHIFT"): covers the frame around the cut, polarity alternating by
     * chapter, with a tilted headline, a progress ruler and a micro sub-line.
     */
    static void chapterCard(ReplayKineticPen p, ReplayKineticTheme th, double progress, int index, int total,
                            String word, String sub) {
        if (progress < 0 || progress > 1) return;
        float w = p.width(), h = p.height();
        double cover = smooth(0.0, 0.22, progress) * (1 - smooth(0.78, 1.0, progress));
        boolean inkSide = index % 2 == 1;
        int bg = inkSide ? th.ink : th.paper, fg = inkSide ? 0xFFFFFFFF : th.ink;
        int accent = index % 3 == 2 ? th.blue : th.red;
        float coverX = (float) ((1 - cover) * w);
        if (progress < 0.5) rect(p, coverX, 0, w, h, bg);
        else rect(p, 0, 0, w - coverX, h, bg);
        if (cover < 0.6) return;
        double in = outExpo((progress - 0.18) / 0.3), out = inCubic((progress - 0.7) / 0.22);
        float dx = (float) ((1 - in) * 420 - out * 520);
        float vel = (float) (slideVelocity(progress - 0.18, 0.3, 420) * -1 + (out > 0 ? -18 * out : 0));
        p.push();
        p.translate(w / 2 + dx, h / 2);
        p.rotate((float) Math.toRadians(-6));
        String head = String.format(java.util.Locale.ROOT, "%02d", index + 1);
        smearText(p, lit(head + " // " + word), Face.DISPLAY, -300, -10, 64, fg, -1, vel);
        ruler(p, -300, 300, 44, 24, (index + 1) / (double) Math.max(1, total), a(fg, 0.55), accent);
        micro(p, "CHAPTER", head + " / " + String.format(java.util.Locale.ROOT, "%02d", total), -300, 72, 9, accent, a(fg, 0.6), -1);
        if (sub != null && !sub.isEmpty()) p.text(lit(sub), Face.TEXT, 300, 72, 15, a(fg, 0.85), 1);
        p.pop();
    }
}
