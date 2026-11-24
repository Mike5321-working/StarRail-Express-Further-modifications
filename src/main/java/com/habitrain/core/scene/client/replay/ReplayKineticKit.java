package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.client.replay.ReplayKineticPen.Face;
import net.minecraft.network.chat.Component;

/**
 * Motion-graphics primitives of the kinetic-typography (MV) style, learned from a Swiss / HUD style lyric video:
 * paper and ink polarity, smeared colour slabs, slot assembly, per-glyph tiles, drop caps, type-on lines,
 * X stamps, wireframe polyhedra, orbit ellipses, tick dials, nested rotating rectangle tunnels, audio bars,
 * targeting reticles, micro labels and ghost numerals. Everything is pure 2D on a {@link ReplayKineticPen}.
 */
final class ReplayKineticKit {
    private ReplayKineticKit() {}

    // ------------------------------------------------------------------ palette

    static final int PAPER = 0xFFECEAE4, PAPER_SHADE = 0xFFD9D6CE, INK = 0xFF0D1018, INK_2 = 0xFF171B27;
    static final int GRAPHITE = 0xFF5B606C, HAIR = 0xFFB4B1A9, RED = 0xFFE3342B, BLUE = 0xFF1F4FE6;
    static final int BP_PAPER = 0xFFE7EDF2, BP_GRID = 0xFFB7CADC, BP_INK = 0xFF10233F, BP_BLUE = 0xFF2A5BD7;
    static final int BP_DARK = 0xFF0A1729, BP_DARK_GRID = 0xFF1B3556;

    static int a(int rgb, double alpha) {
        int base = (rgb >>> 24) & 0xFF;
        int v = (int) Math.round(Math.clamp(alpha, 0.0, 1.0) * base);
        return (v << 24) | (rgb & 0xFFFFFF);
    }

    static int mix(int c0, int c1, double t) {
        double k = Math.clamp(t, 0.0, 1.0);
        int aa = (int) Math.round(((c0 >>> 24) & 0xFF) * (1 - k) + ((c1 >>> 24) & 0xFF) * k);
        int r = (int) Math.round(((c0 >> 16) & 0xFF) * (1 - k) + ((c1 >> 16) & 0xFF) * k);
        int g = (int) Math.round(((c0 >> 8) & 0xFF) * (1 - k) + ((c1 >> 8) & 0xFF) * k);
        int b = (int) Math.round((c0 & 0xFF) * (1 - k) + (c1 & 0xFF) * k);
        return (aa << 24) | (r << 16) | (g << 8) | b;
    }

    /** Readable foreground for text drawn on top of {@code bg}. */
    static int on(int bg) {
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 150 ? INK : 0xFFFFFFFF;
    }

    /** Maps any faction/role colour to the theme's red or blue accent by hue. */
    static int duality(int color, int red, int blue) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return r > b + 24 && r > g ? red : blue;
    }

    // ------------------------------------------------------------------ easing

    static double clamp01(double x) { return Math.clamp(x, 0.0, 1.0); }

    static double outCubic(double x) { x = clamp01(x); return 1 - Math.pow(1 - x, 3); }

    static double inCubic(double x) { x = clamp01(x); return x * x * x; }

    static double outExpo(double x) { x = clamp01(x); return x >= 1 ? 1 : 1 - Math.pow(2, -10 * x); }

    static double inOutQuart(double x) {
        x = clamp01(x);
        return x < 0.5 ? 8 * x * x * x * x : 1 - Math.pow(-2 * x + 2, 4) / 2;
    }

    static double outBack(double x) {
        x = clamp01(x);
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    static double smooth(double e0, double e1, double x) {
        double t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    /** 0 → 1 → 0 window: fades in over {@code in}, holds, fades out over {@code out} before {@code end}. */
    static double window(double t, double start, double end, double in, double out) {
        return smooth(start, start + in, t) * (1 - smooth(end - out, end, t));
    }

    static double hash(int i, int salt) {
        long h = i * 0x9E3779B97F4A7C15L + salt * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    // ------------------------------------------------------------------ shapes

    static void rect(ReplayKineticPen p, float x0, float y0, float x1, float y1, int c) {
        p.quad(x0, y0, c, x0, y1, c, x1, y1, c, x1, y0, c);
    }

    static void rectV(ReplayKineticPen p, float x0, float y0, float x1, float y1, int top, int bottom) {
        p.quad(x0, y0, top, x0, y1, bottom, x1, y1, bottom, x1, y0, top);
    }

    static void rectH(ReplayKineticPen p, float x0, float y0, float x1, float y1, int left, int right) {
        p.quad(x0, y0, left, x0, y1, left, x1, y1, right, x1, y0, right);
    }

    static void line(ReplayKineticPen p, float x0, float y0, float x1, float y1, float w, int c) {
        p.polyline(new float[]{x0, y0, x1, y1}, 2, w, c, false);
    }

    /** Partially drawn line from (x0,y0) toward (x1,y1). */
    static void lineGrow(ReplayKineticPen p, float x0, float y0, float x1, float y1, float w, int c, double amount) {
        double k = clamp01(amount);
        if (k <= 0.001) return;
        line(p, x0, y0, (float) (x0 + (x1 - x0) * k), (float) (y0 + (y1 - y0) * k), w, c);
    }

    static void frame(ReplayKineticPen p, float x0, float y0, float x1, float y1, float w, int c) {
        p.polyline(new float[]{x0, y0, x1, y0, x1, y1, x0, y1}, 4, w, c, true);
    }

    /** Slab with the top-left corner cut diagonally (the MV highlight block). */
    static void cutSlab(ReplayKineticPen p, float x0, float y0, float x1, float y1, float cut, int c) {
        cut = Math.min(cut, Math.min(x1 - x0, y1 - y0) * 0.5f);
        p.quad(x0 + cut, y0, c, x0, y0 + cut, c, x0, y1, c, x1, y1, c);
        p.quad(x0 + cut, y0, c, x1, y1, c, x1, y1, c, x1, y0, c);
    }

    /**
     * Slab that slides with a horizontal motion smear: {@code velocity} is the per-frame travel in pixels
     * (signed), echoes trail opposite to the motion.
     */
    static void smearSlab(ReplayKineticPen p, float x0, float y0, float x1, float y1, float cut, int c, float velocity) {
        float v = Math.clamp(velocity, -260f, 260f);
        if (Math.abs(v) > 1.5f) {
            float hgt = y1 - y0;
            for (int i = 4; i >= 1; i--) {
                float off = -v * i * 0.55f;
                float shrink = hgt * 0.06f * i;
                rect(p, Math.min(x0, x0 + off), y0 + shrink, Math.max(x1, x1 + off), y1 - shrink, a(c, 0.32 / i));
            }
            line(p, x0 - v * 3.2f, y0 + hgt * 0.5f, x0, y0 + hgt * 0.5f, 1.5f, a(c, 0.5));
        }
        cutSlab(p, x0, y0, x1, y1, cut, c);
    }

    static void ellipse(ReplayKineticPen p, float cx, float cy, float rx, float ry, float rot, float w, int c,
                        double from, double to) {
        int seg = Math.max(8, (int) (64 * Math.abs(to - from)));
        float[] xy = new float[(seg + 1) * 2];
        float cr = (float) Math.cos(rot), sr = (float) Math.sin(rot);
        for (int i = 0; i <= seg; i++) {
            double ang = (from + (to - from) * i / seg) * Math.PI * 2;
            float ex = (float) Math.cos(ang) * rx, ey = (float) Math.sin(ang) * ry;
            xy[i * 2] = cx + ex * cr - ey * sr;
            xy[i * 2 + 1] = cy + ex * sr + ey * cr;
        }
        p.polyline(xy, seg + 1, w, c, false);
    }

    static float[] ellipsePoint(float cx, float cy, float rx, float ry, float rot, double frac) {
        double ang = frac * Math.PI * 2;
        float ex = (float) Math.cos(ang) * rx, ey = (float) Math.sin(ang) * ry;
        float cr = (float) Math.cos(rot), sr = (float) Math.sin(rot);
        return new float[]{cx + ex * cr - ey * sr, cy + ex * sr + ey * cr};
    }

    static void dot(ReplayKineticPen p, float cx, float cy, float r, int c) {
        int seg = 12;
        for (int i = 0; i < seg; i += 2) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg, a2 = Math.PI * 2 * (i + 2) / seg;
            p.quad(cx, cy, c, cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r, c,
                    cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, c,
                    cx + (float) Math.cos(a2) * r, cy + (float) Math.sin(a2) * r, c);
        }
    }

    static void diamond(ReplayKineticPen p, float cx, float cy, float r, int c) {
        p.quad(cx, cy - r, c, cx - r, cy, c, cx, cy + r, c, cx + r, cy, c);
    }

    static void dotGrid(ReplayKineticPen p, float x, float y, int cols, int rows, float gap, float r, int c) {
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                float px = x + i * gap, py = y + j * gap;
                rect(p, px - r, py - r, px + r, py + r, c);
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private static final float[][] OCTA = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] OCTA_EDGES = {{0, 2}, {0, 3}, {0, 4}, {0, 5}, {1, 2}, {1, 3}, {1, 4}, {1, 5},
            {2, 4}, {4, 3}, {3, 5}, {5, 2}};

    /** Rotating wireframe octahedron (vertically stretched like the MV crystal); every third edge is accented. */
    static void octahedron(ReplayKineticPen p, float cx, float cy, float r, double yaw, double pitch,
                           int base, int accent, float w) {
        float[][] pts = new float[6][2];
        double cyw = Math.cos(yaw), syw = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        for (int i = 0; i < 6; i++) {
            double x = OCTA[i][0], y = OCTA[i][1] * 1.45, z = OCTA[i][2];
            double x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;
            double y1 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
            double persp = 1.0 / (1.0 + z2 * 0.18);
            pts[i][0] = cx + (float) (x1 * r * persp);
            pts[i][1] = cy - (float) (y1 * r * persp);
        }
        for (int e = 0; e < OCTA_EDGES.length; e++) {
            float[] u = pts[OCTA_EDGES[e][0]], v = pts[OCTA_EDGES[e][1]];
            line(p, u[0], u[1], v[0], v[1], w, e % 3 == 0 ? accent : base);
        }
    }

    /** Nested rectangles counter-rotating into depth: the MV "sync pulse" tunnel. */
    static void tunnel(ReplayKineticPen p, float cx, float cy, float w, float h, int layers, double t,
                       int colorA, int colorB, double alpha) {
        for (int i = layers - 1; i >= 0; i--) {
            double depth = (i + frac(t * 0.9)) / layers;
            float s = (float) Math.pow(0.78, depth * layers);
            double rot = (i % 2 == 0 ? 1 : -1) * (0.12 + depth * 0.5) + Math.sin(t * 0.7 + i) * 0.05;
            float hw = w * s / 2, hh = h * s / 2;
            float c = (float) Math.cos(rot), sn = (float) Math.sin(rot);
            float[] xy = new float[8];
            float[][] corner = {{-hw, -hh}, {hw, -hh}, {hw, hh}, {-hw, hh}};
            for (int k = 0; k < 4; k++) {
                xy[k * 2] = cx + corner[k][0] * c - corner[k][1] * sn;
                xy[k * 2 + 1] = cy + corner[k][0] * sn + corner[k][1] * c;
            }
            double fade = alpha * (0.25 + 0.75 * (1 - depth)) * smooth(0.0, 0.15, 1 - depth + 0.02);
            p.polyline(xy, 4, 2f, a(i % 2 == 0 ? colorA : colorB, fade), true);
        }
    }

    /** Tick ring with major ticks every {@code major} and a rotating sweep arc. */
    static void tickDial(ReplayKineticPen p, float cx, float cy, float r, int ticks, int major, double rot,
                         int c, double sweep, int sweepColor) {
        ellipse(p, cx, cy, r, r, 0, 1.5f, c, 0, 1);
        for (int i = 0; i < ticks; i++) {
            double ang = rot + Math.PI * 2 * i / ticks;
            float len = i % major == 0 ? 12 : 5;
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            line(p, cx + ca * r, cy + sa * r, cx + ca * (r - len), cy + sa * (r - len), i % major == 0 ? 2f : 1.2f, c);
        }
        if (sweep > 0) ellipse(p, cx, cy, r * 0.82f, r * 0.82f, (float) rot, 2.5f, sweepColor, 0, Math.min(1, sweep));
    }

    /** Vertical audio bars centred on {@code cy}; the innermost {@code hot} bars use {@code accent}. */
    static void eqBars(ReplayKineticPen p, float x, float cy, int n, float gap, float barW, float maxH, double t,
                       int base, int accent, int hot, boolean mirror) {
        for (int i = 0; i < n; i++) {
            double beat = Math.pow(Math.max(0, Math.sin((t * 2 + i * 0.13) * Math.PI)), 3);
            double h = maxH * (0.18 + 0.82 * (0.45 * beat + 0.55 * hash(i + (int) (t * 8), 7)) * (1 - i * 0.06));
            float bx = mirror ? x - i * gap : x + i * gap;
            rect(p, bx - barW / 2, cy - (float) h / 2, bx + barW / 2, cy + (float) h / 2, i < hot ? accent : base);
        }
    }

    /** Four corner brackets closing in as {@code lock} goes 0 → 1. */
    static void reticle(ReplayKineticPen p, float cx, float cy, float halfW, float halfH, float arm, float w, int c,
                        double lock) {
        float k = (float) (1 + (1 - outCubic(lock)) * 0.9);
        float hx = halfW * k, hy = halfH * k;
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] s : corners) {
            float x = cx + s[0] * hx, y = cy + s[1] * hy;
            p.polyline(new float[]{x, y - s[1] * arm, x, y, x - s[0] * arm, y}, 3, w, c, false);
        }
    }

    /** Hairlines through a point, growing out to the frame edges. */
    static void crosshair(ReplayKineticPen p, float cx, float cy, int c, double grow) {
        double k = outExpo(grow);
        float wv = p.width(), hv = p.height();
        line(p, (float) (cx - cx * k), cy, (float) (cx + (wv - cx) * k), cy, 1f, c);
        line(p, cx, (float) (cy - cy * k), cx, (float) (cy + (hv - cy) * k), 1f, c);
    }

    /** Ruler with ticks and an accent progress bar (the MV bridge-card ruler). */
    static void ruler(ReplayKineticPen p, float x0, float x1, float y, int ticks, double progress, int base, int accent) {
        line(p, x0, y, x1, y, 1.5f, base);
        for (int i = 0; i <= ticks; i++) {
            float x = x0 + (x1 - x0) * i / ticks;
            line(p, x, y - (i % 4 == 0 ? 7 : 4), x, y + (i % 4 == 0 ? 7 : 4), 1.5f, base);
        }
        double k = clamp01(progress);
        if (k > 0) {
            rect(p, x0, y - 2.5f, (float) (x0 + (x1 - x0) * k), y + 2.5f, accent);
            float hx = (float) (x0 + (x1 - x0) * k);
            line(p, hx, y - 10, hx, y + 10, 3f, accent);
        }
    }

    /** Red X drawn stroke by stroke (MV strike-through stamp). */
    static void xStamp(ReplayKineticPen p, float cx, float cy, float r, double progress, int c, float w) {
        double k1 = outExpo(progress / 0.5), k2 = outExpo((progress - 0.5) / 0.5);
        lineGrow(p, cx - r, cy - r, cx + r, cy + r, w, c, k1);
        lineGrow(p, cx + r, cy - r, cx - r, cy + r, w, c, k2);
    }

    static double frac(double v) { return v - Math.floor(v); }

    // ------------------------------------------------------------------ typography

    static Component lit(String s) { return Component.literal(s == null ? "" : s); }

    /** Letter-spaced label (MV micro-type). Returns the drawn width. */
    static float tracked(ReplayKineticPen p, String text, Face face, float x, float y, float px, float tracking,
                         int color, int align) {
        if (text == null || text.isEmpty() || (color >>> 24) < 6) return 0;
        int[] cps = text.codePoints().toArray();
        float[] ws = new float[cps.length];
        float total = 0;
        for (int i = 0; i < cps.length; i++) {
            ws[i] = p.textWidth(lit(new String(Character.toChars(cps[i]))), face, px);
            total += ws[i] + (i < cps.length - 1 ? tracking : 0);
        }
        float cx = align < 0 ? x : align == 0 ? x - total / 2 : x - total;
        for (int i = 0; i < cps.length; i++) {
            if (cps[i] != ' ') p.text(lit(new String(Character.toChars(cps[i]))), face, cx, y, px, color, -1);
            cx += ws[i] + tracking;
        }
        return total;
    }

    static float trackedWidth(ReplayKineticPen p, String text, Face face, float px, float tracking) {
        if (text == null || text.isEmpty()) return 0;
        float total = 0;
        int n = 0;
        for (int cp : text.codePoints().toArray()) {
            total += p.textWidth(lit(new String(Character.toChars(cp))), face, px);
            n++;
        }
        return total + tracking * Math.max(0, n - 1);
    }

    /** {@code CODE // TEXT} micro label with an accent code. */
    static float micro(ReplayKineticPen p, String code, String text, float x, float y, float px, int codeColor,
                       int textColor, int align) {
        String head = code == null || code.isEmpty() ? "" : code + "  //  ";
        float tr = px * 0.28f;
        float wHead = trackedWidth(p, head, Face.MONO, px, tr), wText = trackedWidth(p, text, Face.MONO, px, tr);
        float total = wHead + wText;
        float sx = align < 0 ? x : align == 0 ? x - total / 2 : x - total;
        tracked(p, head, Face.MONO, sx, y, px, tr, codeColor, -1);
        tracked(p, text, Face.MONO, sx + wHead, y, px, tr, textColor, -1);
        return total;
    }

    /** Text with trailing horizontal echoes (motion smear on entry/exit). */
    static void smearText(ReplayKineticPen p, Component text, Face face, float x, float y, float px, int color,
                          int align, float velocity) {
        float v = Math.clamp(velocity, -300f, 300f);
        if (Math.abs(v) > 1.5f) {
            for (int i = 5; i >= 1; i--) p.text(text, face, x - v * i * 0.45f, y, px, a(color, 0.22 / i), align);
        }
        p.text(text, face, x, y, px, color, align);
    }

    /** Huge faint numeral or word behind the composition. */
    static void ghost(ReplayKineticPen p, String text, float x, float y, float px, int color, int align) {
        p.text(lit(text), Face.DISPLAY, x, y, px, color, align);
    }

    /**
     * Per-glyph tiles popping in one after another ({@code age} seconds since the start, {@code stagger} seconds
     * between glyphs). Tiles cycle through {@code fills}; glyph colour is chosen for contrast. Returns total width.
     */
    static float glyphTiles(ReplayKineticPen p, String text, Face face, float x, float y, float px, double age,
                            double stagger, int[] fills, int align, double alpha) {
        if (text == null || text.isEmpty() || alpha <= 0.01) return 0;
        int[] cps = text.codePoints().toArray();
        float tile = px * 1.42f, gap = px * 0.12f;
        float[] ws = new float[cps.length];
        float total = 0;
        for (int i = 0; i < cps.length; i++) {
            float gw = p.textWidth(lit(new String(Character.toChars(cps[i]))), face, px);
            ws[i] = cps[i] == ' ' ? tile * 0.45f : Math.max(tile, gw + px * 0.5f);
            total += ws[i] + (i < cps.length - 1 ? gap : 0);
        }
        float cx = align < 0 ? x : align == 0 ? x - total / 2 : x - total;
        int fillIndex = 0;
        for (int i = 0; i < cps.length; i++) {
            float wv = ws[i];
            if (cps[i] != ' ') {
                double local = (age - i * stagger) / 0.22;
                if (local > 0) {
                    double pop = outBack(local);
                    double fade = clamp01(local * 3) * alpha;
                    int fill = fills[Math.floorMod(fillIndex + (int) (hash(cps[i], 3) * 1.99), fills.length)];
                    float s = (float) (0.4 + 0.6 * pop);
                    float mx = cx + wv / 2;
                    p.push();
                    p.translate(mx, y);
                    p.rotate((float) ((1 - pop) * (hash(i, 9) - 0.5) * 0.6));
                    p.scale(s, s);
                    rect(p, -wv / 2, -tile / 2, wv / 2, tile / 2, a(fill, fade));
                    p.text(lit(new String(Character.toChars(cps[i]))), face, 0, 0, px, a(on(fill), fade), 0);
                    p.pop();
                }
                fillIndex++;
            }
            cx += wv + gap;
        }
        return total;
    }

    /** Type-on line with a block cursor and an underline that grows with the text. */
    static float typeOn(ReplayKineticPen p, String text, Face face, float x, float y, float px, int color,
                        int underline, double progress, boolean cursor) {
        if (text == null || text.isEmpty()) return 0;
        int[] cps = text.codePoints().toArray();
        double k = clamp01(progress);
        int shown = (int) Math.floor(cps.length * k + 1e-6);
        String part = new String(cps, 0, shown);
        float full = p.textWidth(lit(text), face, px);
        float wPart = p.textWidth(lit(part), face, px);
        if (shown > 0) p.text(lit(part), face, x, y, px, color, -1);
        if (cursor && k < 1) rect(p, x + wPart + 2, y - px * 0.45f, x + wPart + 2 + px * 0.45f, y + px * 0.45f, underline);
        if ((underline >>> 24) > 0) rect(p, x, y + px * 0.72f, x + (float) (full * outCubic(k * 1.1)), y + px * 0.72f + 3, underline);
        return full;
    }

    /**
     * Drop-cap composition: the first glyph huge in the accent colour, the rest of the line smaller and sliding
     * in from the right. Returns the total width.
     */
    static float dropCap(ReplayKineticPen p, String text, float x, float y, float bigPx, float smallPx, int accent,
                         int ink, double age) {
        if (text == null || text.isEmpty()) return 0;
        int firstLen = Character.charCount(text.codePointAt(0));
        Component first = lit(text.substring(0, firstLen)), rest = lit(text.substring(firstLen));
        double pop = outBack(age / 0.28);
        float wFirst = p.textWidth(first, Face.TEXT, bigPx);
        p.push();
        p.translate(x + wFirst / 2, y);
        float s = (float) (0.55 + 0.45 * pop);
        p.scale(s, s);
        p.text(first, Face.TEXT, 0, 0, bigPx, a(accent, clamp01(age / 0.08)), 0);
        p.pop();
        double slide = outExpo((age - 0.18) / 0.45);
        if (slide > 0) {
            float rx = x + wFirst + smallPx * 0.35f + (float) ((1 - slide) * 160);
            float vel = (float) ((outExpo((age - 0.18) / 0.45) - outExpo((age - 0.18 - 0.033) / 0.45)) * -160);
            smearText(p, rest, Face.TEXT, rx, y + bigPx * 0.22f, smallPx, a(ink, clamp01(slide * 2)), -1, vel);
        }
        return wFirst + smallPx * 0.35f + p.textWidth(rest, Face.TEXT, smallPx);
    }

    /** Horizontal velocity (pixels per frame at 30 fps) of an outExpo slide over {@code dur} seconds. */
    static float slideVelocity(double age, double dur, float distance) {
        double k0 = outExpo(age / dur), k1 = outExpo((age - 1.0 / 30) / dur);
        return (float) ((k0 - k1) * distance);
    }
}
