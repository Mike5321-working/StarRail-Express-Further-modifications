package com.habitrain.core.client.title;

import com.habitrain.core.HabiTrainCore;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * High-DPI vector sprite, font, and dynamic-text texture baker for HabiTrain Kinetic UI v3.
 *
 * <p>Loads {@code bebas.ttf}, {@code noto_sans_sc_bold.ttf}, and {@code DejaVuSansMono-Bold.ttf}
 * directly from the mod classpath so crisp typography and all 2.5D extruded slabs render from
 * frame 0 during early Minecraft boot (before {@code ResourceManager}/{@code FontManager} finish).</p>
 *
 * <p>Fast-changing ASCII readouts (timecodes, percentages, typed strings, giant "00"/"01"/
 * "DEPARTURE") draw from pre-baked ASCII glyph atlases with zero per-frame allocations; CJK
 * strings are rasterized once into a bounded LRU {@link DynamicTexture} cache.</p>
 */
@Environment(EnvType.CLIENT)
public final class HabiKineticAtlas {

    public static final int[][] BANDS = {{-172, -50}, {-40, 6}, {16, 52}, {62, 92}, {102, 126}, {136, 172}};
    public static final int WIN_BAND = 1;

    private static final float BAKE_SCALE = 2.0f;
    private static final int MAX_TEXT_CACHE = 256;
    private static final String FONT_BASE = "assets/habitrain_core/font/replay/";

    public record Slab(
            DynamicTexture faceTex,
            DynamicTexture maskTex,
            double w,
            double h,
            double ax,
            double ay,
            int sideRgb,
            double yc,
            double y0,
            double y1,
            double adv,
            double titleX,
            boolean plate
    ) {
        public int faceTexId() { return faceTex != null ? faceTex.getId() : 0; }
        public int maskTexId() { return maskTex != null ? maskTex.getId() : 0; }
    }

    public record TextTex(DynamicTexture tex, double w, double h) {
        public int texId() { return tex != null ? tex.getId() : 0; }
    }

    private record GlyphCell(float u0, float v0, float u1, float v1, float adv, float drawW, float drawH) {}

    private static final class AsciiAtlas {
        final DynamicTexture tex;
        final float fontPx;
        final float ascent;
        final float descent;
        final GlyphCell[] cells = new GlyphCell[128];

        AsciiAtlas(DynamicTexture tex, float fontPx, float ascent, float descent) {
            this.tex = tex;
            this.fontPx = fontPx;
            this.ascent = ascent;
            this.descent = descent;
        }
    }

    public enum FontFace {
        DISPLAY, SANS_CN, MONO
    }

    private static final HabiKineticAtlas INSTANCE = new HabiKineticAtlas();

    public static HabiKineticAtlas getInstance() {
        return INSTANCE;
    }

    private boolean ready;
    private Font fontDisplay;
    private Font fontSansCn;
    private Font fontMono;

    // ── Station 00 sprites ───────────────────────────────────────────────────
    public final Slab[] zeroBands = new Slab[BANDS.length];
    public Slab zeroStem;
    public Slab zeroLeaf;
    public DynamicTexture zeroOutlineDark;
    public DynamicTexture zeroOutlinePaper;
    public final List<Slab> titleGlyphs = new ArrayList<>();
    public double titleWidth;
    public double titleCapH;

    public Slab tagLead;
    public Slab tagCore;
    public Slab tagPrompt;
    public Slab tagAutoBoard;
    public Slab tagSyncBoard;
    public Slab tagDepart;
    public Slab loadBadge;
    public Slab cursor;
    public Slab zeroCarLead;
    public Slab zeroCar;
    public Slab cardA;
    public Slab cardB;
    public Slab cardC;
    public Slab cardD;

    // ── Station 01 sprites ───────────────────────────────────────────────────
    public final Slab[] stBands = new Slab[BANDS.length];
    public Slab stStem;
    public Slab stLeaf;
    public Slab stCarLead;
    public Slab stCar;
    public Slab plateStation;
    public Slab plateRoute;

    // ── Post-processing textures ─────────────────────────────────────────────
    public DynamicTexture noiseTex;
    public DynamicTexture vigPaperTex;
    public DynamicTexture vigDarkTex;
    public DynamicTexture stationBgTex;

    // ── Zero-allocation ASCII glyph atlases ──────────────────────────────────
    private AsciiAtlas monoAtlas;
    private AsciiAtlas displayAtlas;

    // ── Bounded LRU CJK/mixed text texture cache ─────────────────────────────
    private final Map<String, TextTex> textCache = new LinkedHashMap<>(128, 0.75f, true);
    private final List<DynamicTexture> ownedTextures = new ArrayList<>();
    private final Matrix4f identity = new Matrix4f();

    private HabiKineticAtlas() {}

    public boolean isReady() {
        return ready;
    }

    /** Lazily bakes all vector sprites &amp; glyph atlases on the OpenGL render thread. */
    public void ensurePrepared() {
        if (ready) return;
        loadFonts();
        bakeZeroStationSprites();
        bakeStation01Sprites();
        bakePostTextures();
        monoAtlas = bakeAsciiAtlas(fontMono, 40f);
        displayAtlas = bakeAsciiAtlas(fontDisplay, 140f);
        ready = true;
    }

    private void loadFonts() {
        fontDisplay = loadClasspathFont(FONT_BASE + "bebas.ttf", Font.PLAIN, "SansSerif");
        fontSansCn = loadClasspathFont(FONT_BASE + "noto_sans_sc_bold.ttf", Font.BOLD, "SansSerif");
        fontMono = loadClasspathFont(FONT_BASE + "DejaVuSansMono-Bold.ttf", Font.BOLD, "Monospaced");
    }

    private static Font loadClasspathFont(String path, int fallbackStyle, String fallbackName) {
        try {
            InputStream in = HabiKineticAtlas.class.getClassLoader().getResourceAsStream(path);
            if (in == null) in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
            if (in != null) {
                try (InputStream stream = in) {
                    return Font.createFont(Font.TRUETYPE_FONT, stream);
                }
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[HabiKineticAtlas] Failed to load font {}, using fallback", path, t);
        }
        return new Font(fallbackName, fallbackStyle, 24);
    }

    // ── Shared Apple Path (exact Bezier control points from scene.js) ────────

    private static Path2D.Double createApplePath() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, -118);
        p.curveTo(38, -160, 168, -162, 168, -28);
        p.curveTo(168, 92, 96, 172, 44, 166);
        p.curveTo(22, 164, 12, 154, 0, 154);
        p.curveTo(-12, 154, -22, 164, -44, 166);
        p.curveTo(-96, 172, -168, 92, -168, -28);
        p.curveTo(-168, -162, -38, -160, 0, -118);
        p.closePath();
        return p;
    }

    private void bakeZeroStationSprites() {
        final Color cInk = new Color(0x16171D);
        final Color cRed = new Color(0xE1243B);
        final Color cRedShade = new Color(0xC41A30);
        final Color cPaperTxt = new Color(0xF4F1EA);
        final Color cWhiteHi = new Color(255, 249, 242, 242);
        final Color cMute = new Color(0x8C8981);

        // 1) Sliced apple bands (6 bands)
        Path2D.Double apple = createApplePath();
        for (int i = 0; i < BANDS.length; i++) {
            final int idx = i;
            final int y0 = BANDS[i][0], y1 = BANDS[i][1];
            final double yc = (y0 + y1) * 0.5;
            final int w = 344, h = (y1 - y0) + 4;
            final boolean isWin = (i == WIN_BAND);
            zeroBands[i] = bakeSlab(w, h, w * 0.5, h * 0.5, isWin ? 0x050508 : 0x8E1323,
                    yc, y0, y1, w, 0, false, true, g -> {
                        g.translate(w * 0.5, -y0 + 2.0);
                        g.clipRect(-w / 2, y0, w, y1 - y0);
                        g.setColor(isWin ? cInk : cRed);
                        g.fill(apple);
                        g.clip(apple);
                        if (!isWin) {
                            // Flat right crescent shade
                            g.setColor(cRedShade);
                            var prev = g.getTransform();
                            g.translate(176, 26);
                            g.rotate(-0.18);
                            g.fill(new Ellipse2D.Double(-112, -230, 224, 460));
                            g.setTransform(prev);
                            // Two paper-white highlights
                            g.setColor(cWhiteHi);
                            g.translate(-96, -96);
                            g.rotate(-0.72);
                            g.fill(new Ellipse2D.Double(-34, -14, 68, 28));
                            g.setTransform(prev);
                            g.fill(new Ellipse2D.Double(-56 - 6.5, -125 - 6.5, 13.0, 13.0));
                        } else {
                            // Carriage window strip
                            g.setColor(cPaperTxt);
                            int[] xs = {-112, -40, 32, 104};
                            for (int wx : xs) {
                                g.fill(new RoundRectangle2D.Double(wx - 23, yc - 13, 46, 19, 8, 8));
                            }
                            g.setColor(cRed);
                            g.fillRect(-170, y1 - 7, 340, 3);
                        }
                    });
        }

        // 2) Stem & Leaf
        zeroStem = bakeSlab(18, 66, 9, 33, 0x000000, 0, 0, 0, 18, 0, false, true, g -> {
            g.setColor(cInk);
            g.fill(new RoundRectangle2D.Double(2, 0, 14, 66, 14, 14));
        });
        zeroLeaf = bakeSlab(120, 64, 4, 54, 0x000000, 0, 0, 0, 120, 0, false, true, g -> {
            g.translate(4, 54);
            Path2D.Double lf = new Path2D.Double();
            lf.moveTo(0, 0);
            lf.curveTo(18, -36, 70, -52, 112, -44);
            lf.curveTo(88, -6, 42, 8, 0, 0);
            lf.closePath();
            g.setColor(cInk);
            g.fill(lf);
            g.setColor(cPaperTxt);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double vein = new Path2D.Double();
            vein.moveTo(10, -4);
            vein.quadTo(56, -20, 98, -40);
            g.draw(vein);
        });

        // 3) Dashed blueprint apple outline
        zeroOutlinePaper = bakeTexture(360, 360, BAKE_SCALE, g -> drawBlueprintOutline(g, cInk));
        zeroOutlineDark = bakeTexture(360, 360, BAKE_SCALE, g -> drawBlueprintOutline(g, new Color(0xECE9E2)));

        // 4) Extruded Title Glyphs: H A B I [T] R A I N
        titleGlyphs.clear();
        String codeName = "HABITRAIN";
        int plateIndex = 4;
        Font titleFont = fontDisplay.deriveFont(Font.PLAIN, 214f);
        BufferedImage probe = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        pg.setFont(titleFont);
        FontMetrics fm = pg.getFontMetrics();
        double capH = Math.ceil(fm.getAscent() * 0.80);
        this.titleCapH = capH;

        List<Slab> rawGlyphs = new ArrayList<>();
        for (int i = 0; i < codeName.length(); i++) {
            final String ch = codeName.substring(i, i + 1);
            final double adv = fm.stringWidth(ch);
            if (i == plateIndex) {
                final int pw = (int) Math.ceil(adv + 46);
                final int ph = (int) Math.ceil(capH + 44);
                rawGlyphs.add(bakeSlab(pw, ph, pw * 0.5, ph - 22.0, 0x16171D,
                        0, 0, 0, pw, 0, true, true, g -> {
                            Path2D.Double poly = new Path2D.Double();
                            poly.moveTo(16, 0); poly.lineTo(pw, 0); poly.lineTo(pw, ph);
                            poly.lineTo(0, ph); poly.lineTo(0, 16); poly.closePath();
                            g.setColor(cRed);
                            g.fill(poly);
                            g.setFont(titleFont);
                            g.setColor(cPaperTxt);
                            g.drawString(ch, (float) ((pw - adv) * 0.5), (float) (ph - 22));
                        }));
            } else {
                final int gw = (int) Math.ceil(adv + 10);
                final int gh = (int) Math.ceil(capH + 14);
                rawGlyphs.add(bakeSlab(gw, gh, gw * 0.5, gh - 5.0, 0xE1243B,
                        0, 0, 0, adv, 0, false, true, g -> {
                            g.setFont(titleFont);
                            g.setColor(cInk);
                            g.drawString(ch, 5f, (float) (gh - 5));
                        }));
            }
        }
        pg.dispose();

        double gap = 12.0;
        double total = 0.0;
        for (Slab s : rawGlyphs) total += s.adv();
        total += gap * (rawGlyphs.size() - 1);
        double curX = -total * 0.5;
        for (Slab s : rawGlyphs) {
            titleGlyphs.add(new Slab(
                    s.faceTex(), s.maskTex(), s.w(), s.h(), s.ax(), s.ay(),
                    s.sideRgb(), s.yc(), s.y0(), s.y1(), s.adv(), curX + s.adv() * 0.5, s.plate()
            ));
            curX += s.adv() + gap;
        }
        this.titleWidth = total;

        // 5) Chinese chamfered tags
        tagLead = bakeTag("直到列车变成", fontSansCn.deriveFont(Font.BOLD, 40f), 7f, 26, 66, cInk, cPaperTxt, 0xE1243B, "bl");
        tagCore = bakeTag("一颗苹果", fontSansCn.deriveFont(Font.BOLD, 40f), 7f, 26, 66, cRed, cPaperTxt, 0x16171D, "tr");
        tagPrompt = bakeTag("按任意键登车", fontSansCn.deriveFont(Font.BOLD, 26f), 10f, 22, 50, cInk, cPaperTxt, 0xE1243B, "bl");
        tagAutoBoard = bakeTag("自动进站中", fontSansCn.deriveFont(Font.BOLD, 26f), 10f, 22, 50, cRed, cPaperTxt, 0x16171D, "bl");
        tagSyncBoard = bakeTag("节拍校准中", fontSansCn.deriveFont(Font.BOLD, 26f), 10f, 22, 50, cRed, cPaperTxt, 0x16171D, "bl");
        tagDepart = bakeTag("列车出发", fontSansCn.deriveFont(Font.BOLD, 26f), 10f, 22, 50, cRed, cPaperTxt, 0x16171D, "bl");

        loadBadge = bakeSlab(210, 50, 105, 25, 0xE1243B, 0, 0, 0, 210, 0, false, true, g -> {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(0, 0); p.lineTo(210, 0); p.lineTo(210, 50); p.lineTo(14, 50); p.lineTo(0, 36); p.closePath();
            g.setColor(cInk);
            g.fill(p);
            g.setColor(cRed);
            g.fillRect(0, 0, 6, 36);
        });
        cursor = bakeSlab(16, 30, 8, 15, 0x8E1323, 0, 0, 0, 16, 0, false, true, g -> {
            g.setColor(cRed);
            g.fillRect(0, 0, 16, 30);
        });

        // 6) 3-car red HABITRAIN locomotive & carriages
        zeroCarLead = bakeTrainCar(58, 22, true, cRed, cInk, cPaperTxt, 0x8E1323);
        zeroCar = bakeTrainCar(58, 22, false, cRed, cInk, cPaperTxt, 0x8E1323);

        // 7) 4 Floating parallax cards
        Font fDisp58 = fontDisplay.deriveFont(Font.PLAIN, 58f);
        Font fDisp62 = fontDisplay.deriveFont(Font.PLAIN, 62f);
        Font fMono10 = fontMono.deriveFont(Font.BOLD, 10f);

        cardA = bakeSlab(224, 140, 112, 70, 0x16171D, 0, 0, 0, 224, 0, false, true, g -> {
            g.setColor(new Color(0xF6F4EF));
            g.fillRect(0, 0, 224, 140);
            g.setColor(cInk);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(1, 1, 222, 138);
            Shape oldClip = g.getClip();
            g.clipRect(14, 24, 92, 102);
            g.setColor(cRed);
            for (int i = -120; i < 140; i += 8) g.drawLine(14 + i, 24, 14 + i + 102, 126);
            g.setClip(oldClip);
            g.setColor(cInk);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(14, 24, 92, 102);
            g.setFont(fDisp58);
            g.drawString("01", 120, 82);
            g.setFont(fMono10);
            drawSpaced(g, "RAIL // GAUGE", 120, 100, 2f);
            g.setColor(cRed);
            drawSpaced(g, "1435 MM", 120, 118, 2f);
            g.setColor(cMute);
            drawSpaced(g, "FIG.A", 14, 16, 2f);
        });

        cardB = bakeSlab(152, 200, 76, 100, 0x8E1323, 0, 0, 0, 152, 0, false, true, g -> {
            g.setColor(cRed);
            g.fillRect(0, 0, 152, 200);
            Shape oldClip = g.getClip();
            g.clipRect(0, 96, 152, 104);
            g.setColor(new Color(255, 245, 240, 108));
            g.setStroke(new BasicStroke(2f));
            for (int i = -200; i < 200; i += 9) g.drawLine(i, 96, i + 104, 200);
            g.setClip(oldClip);
            g.setColor(cPaperTxt);
            g.setFont(fDisp62);
            g.drawString("02", 86, 70);
            g.fillRect(14, 14, 14, 14);
            g.setFont(fMono10);
            drawSpaced(g, "CARGO // 02", 14, 88, 2f);
        });

        cardC = bakeSlab(128, 128, 64, 64, 0x16171D, 0, 0, 0, 128, 0, false, false, g -> {
            g.setColor(cInk);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(2, 2, 124, 124);
            g.setStroke(new BasicStroke(1.2f));
            g.drawRect(22, 22, 84, 84);
            g.setColor(cRed);
            g.fillRect(100, 100, 8, 8);
        });

        cardD = bakeSlab(184, 50, 92, 25, 0x16171D, 0, 0, 0, 184, 0, false, false, g -> {
            int seed = 1435;
            int x = 0;
            g.setColor(cInk);
            while (x < 180) {
                seed = nextMulberry(seed);
                int w = 1 + (int) ((toUnit(seed)) * 4.0);
                seed = nextMulberry(seed);
                if (toUnit(seed) > 0.35) g.fillRect(x, 0, w, 30);
                seed = nextMulberry(seed);
                x += w + 1 + (int) (toUnit(seed) * 3.0);
            }
            g.setFont(fMono10);
            drawSpaced(g, "HT-00 // 2026", 0, 46, 2f);
        });
    }

    private void bakeStation01Sprites() {
        final Color cPaper = new Color(0xECE9E2);
        final Color cPaperTxt = new Color(0xF4F1EA);
        final Color cInk = new Color(0x16171D);
        final Color cRed = new Color(0xE1243B);
        final Color cRedBright = new Color(0xF2354B);
        final Color cMute = new Color(0x727687);

        Path2D.Double apple = createApplePath();
        for (int i = 0; i < BANDS.length; i++) {
            final int idx = i;
            final int y0 = BANDS[i][0], y1 = BANDS[i][1];
            final double yc = (y0 + y1) * 0.5;
            final int w = 344, h = (y1 - y0) + 4;
            final boolean isWin = (i == 1);
            stBands[i] = bakeSlab(w, h, w * 0.5, h * 0.5, isWin ? 0x7E101E : 0x33394D,
                    yc, y0, y1, w, 0, false, true, g -> {
                        g.translate(w * 0.5, -y0 + 2.0);
                        g.clipRect(-w / 2, y0, w, y1 - y0);
                        g.setColor(isWin ? cRed : (idx == 0 ? cPaper : new Color(0x282E42)));
                        g.fill(apple);
                        g.clip(apple);
                        if (isWin) {
                            g.setColor(cPaperTxt);
                            int[] xs = {-112, -40, 32, 104};
                            for (int wx : xs) {
                                g.fill(new RoundRectangle2D.Double(wx - 23, yc - 13, 46, 19, 8, 8));
                            }
                            g.setColor(cInk);
                            g.fillRect(-170, y1 - 6, 340, 3);
                        } else if (idx == 0) {
                            g.setColor(new Color(0xD8D4CB));
                            var prev = g.getTransform();
                            g.translate(176, 26);
                            g.rotate(-0.18);
                            g.fill(new Ellipse2D.Double(-112, -230, 224, 460));
                            g.setTransform(prev);
                            g.setColor(cRed);
                            g.translate(-96, -96);
                            g.rotate(-0.72);
                            g.fill(new Ellipse2D.Double(-34, -14, 68, 28));
                            g.setTransform(prev);
                            g.fill(new Ellipse2D.Double(-56 - 6.5, -125 - 6.5, 13.0, 13.0));
                        } else {
                            g.setColor(new Color(0x1B2030));
                            var prev = g.getTransform();
                            g.translate(174, 24);
                            g.rotate(-0.18);
                            g.fill(new Ellipse2D.Double(-108, -224, 216, 448));
                            g.setTransform(prev);
                            g.setColor(new Color(236, 233, 226, 42));
                            g.fillRect(-172, y0, 344, 2);
                            g.setColor(new Color(236, 233, 226, 160));
                            g.setStroke(new BasicStroke(2.4f));
                            g.draw(apple);
                            g.setColor(cRedBright);
                            g.fillRect(-168, y0 + 5, 22, 4);
                        }
                    });
        }

        stStem = bakeSlab(18, 66, 9, 33, 0x252936, 0, 0, 0, 18, 0, false, true, g -> {
            g.setColor(cPaper);
            g.fill(new RoundRectangle2D.Double(2, 0, 14, 66, 14, 14));
        });
        stLeaf = bakeSlab(120, 64, 4, 54, 0x7E101E, 0, 0, 0, 120, 0, false, true, g -> {
            g.translate(4, 54);
            Path2D.Double lf = new Path2D.Double();
            lf.moveTo(0, 0);
            lf.curveTo(18, -36, 70, -52, 112, -44);
            lf.curveTo(88, -6, 42, 8, 0, 0);
            lf.closePath();
            g.setColor(cRed);
            g.fill(lf);
            g.setColor(cPaperTxt);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double vein = new Path2D.Double();
            vein.moveTo(10, -4);
            vein.quadTo(56, -20, 98, -40);
            g.draw(vein);
        });

        stCarLead = bakeTrainCar(60, 23, true, cRedBright, cInk, new Color(0xFFF7E8), 0x7E101E);
        stCar = bakeTrainCar(60, 23, false, cRedBright, cInk, new Color(0xFFF7E8), 0x7E101E);

        // Floating station plates (exact original copy: STN.01 习惯中枢 & 苹果园环线)
        Font fMono10 = fontMono.deriveFont(Font.BOLD, 10f);
        Font fDisp54 = fontDisplay.deriveFont(Font.PLAIN, 54f);
        Font fDisp40 = fontDisplay.deriveFont(Font.PLAIN, 40f);
        Font fCn14 = fontSansCn.deriveFont(Font.BOLD, 14f);
        Font fCn20 = fontSansCn.deriveFont(Font.BOLD, 20f);

        plateStation = bakeSlab(236, 126, 118, 63, 0x7E101E, 0, 0, 0, 236, 0, false, true, g -> {
            Path2D.Double poly = new Path2D.Double();
            poly.moveTo(0, 0); poly.lineTo(236, 0); poly.lineTo(236, 110);
            poly.lineTo(220, 126); poly.lineTo(0, 126); poly.closePath();
            g.setColor(cPaper);
            g.fill(poly);
            g.setColor(cRed);
            g.fillRect(0, 0, 236, 26);
            g.setColor(cPaperTxt);
            g.setFont(fMono10);
            drawSpaced(g, "ARRIVED // PLATFORM 01", 12, 16, 2f);
            g.setColor(cInk);
            g.setFont(fDisp54);
            drawSpaced(g, "STN.01", 14, 82, 2f);
            g.setFont(fCn14);
            drawSpaced(g, "习惯中枢", 14, 108, 4f);
            g.setColor(cRed);
            g.fillRect(156, 44, 62, 62);
            g.setColor(cPaperTxt);
            g.setFont(fDisp40);
            g.drawString("01", 170, 90);
        });

        plateRoute = bakeSlab(210, 112, 105, 56, 0x06070B, 0, 0, 0, 210, 0, false, true, g -> {
            g.setColor(new Color(0x161924));
            g.fillRect(0, 0, 210, 112);
            g.setColor(new Color(236, 233, 226, 166));
            g.setStroke(new BasicStroke(1.6f));
            g.drawRect(1, 1, 208, 110);
            g.setColor(cRed);
            g.fillRect(12, 12, 10, 10);
            g.setColor(cPaper);
            g.setFont(fMono10);
            drawSpaced(g, "ROUTE // ACTIVE", 30, 21, 2f);
            g.setFont(fCn20);
            drawSpaced(g, "苹果园环线", 14, 60, 3f);
            g.setColor(cMute);
            g.setFont(fMono10);
            drawSpaced(g, "NEXT: ORCHARD // 100 BPM", 14, 92, 1.5f);
        });
    }

    private void bakePostTextures() {
        // 256x256 tileable film grain
        BufferedImage nImg = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        int seed = 77;
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                seed = nextMulberry(seed);
                int v = (int) (toUnit(seed) * 255.0);
                nImg.setRGB(x, y, (0xFF << 24) | (v << 16) | (v << 8) | v);
            }
        }
        noiseTex = uploadImage(nImg);

        // Radial vignettes
        vigPaperTex = bakeTexture(512, 288, 1f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(
                    256f, 144f, 315f,
                    new float[]{0.0f, 0.34f, 1.0f},
                    new Color[]{new Color(70, 55, 40, 0), new Color(70, 55, 40, 0), new Color(70, 55, 40, 42)}
            );
            g.setPaint(p);
            g.fillRect(0, 0, 512, 288);
        });
        vigDarkTex = bakeTexture(512, 288, 1f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(
                    256f, 144f, 315f,
                    new float[]{0.0f, 0.34f, 1.0f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), new Color(0, 0, 0, 118)}
            );
            g.setPaint(p);
            g.fillRect(0, 0, 512, 288);
        });

        // Station 01 ambient radial deep-space gradient
        stationBgTex = bakeTexture(512, 288, 1f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(
                    256f + 85f, 144f - 10f, 340f,
                    new float[]{0.0f, 0.45f, 1.0f},
                    new Color[]{new Color(0x1D2233), new Color(0x12151F), new Color(0x0B0D13)}
            );
            g.setPaint(p);
            g.fillRect(0, 0, 512, 288);
        });
    }

    private void drawBlueprintOutline(Graphics2D g, Color color) {
        g.translate(180, 180);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{7f, 6f}, 0f));
        g.draw(createApplePath());
    }

    private Slab bakeTrainCar(int w, int h, boolean lead, Color body, Color roof, Color win, int sideRgb) {
        return bakeSlab(w, h, w * 0.5, h * 0.5, sideRgb, 0, 0, 0, w, 0, false, true, g -> {
            g.setColor(body);
            g.fill(new RoundRectangle2D.Double(0, 0, w, h, 12, 12));
            g.setColor(roof);
            g.fillRect(lead ? 8 : 6, 6, lead ? (w - 24) : (w - 12), h - 12);
            g.setColor(win);
            int n = lead ? 2 : 3;
            for (int i = 0; i < n; i++) {
                double wx = (lead ? 11 : 9) + i * (lead ? 16.2 : 14.8);
                g.fillRect((int) Math.round(wx), 8, lead ? 11 : 10, 6);
            }
            if (lead) {
                g.setColor(roof);
                g.fillRect(w - 10, 2, 6, h - 4);
                g.setColor(new Color(0xFFF4D8));
                g.fill(new Arc2D.Double(w - 5.6, h * 0.5 - 2.8, 5.6, 5.6, 0, 360, Arc2D.CHORD));
            }
        });
    }

    private Slab bakeTag(String text, Font font, float ls, int padX, int h,
                         Color bg, Color fg, int sideRgb, String cut) {
        BufferedImage probe = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        pg.setFont(font);
        double tw = measureSpaced(pg, text, ls);
        pg.dispose();

        final int w = (int) Math.ceil(tw + padX * 2);
        return bakeSlab(w, h, w * 0.5, h * 0.5, sideRgb, 0, 0, 0, w, 0, false, true, g -> {
            Path2D.Double poly = new Path2D.Double();
            if ("bl".equals(cut)) {
                poly.moveTo(0, 0); poly.lineTo(w, 0); poly.lineTo(w, h); poly.lineTo(14, h); poly.lineTo(0, h - 14);
            } else if ("tr".equals(cut)) {
                poly.moveTo(0, 0); poly.lineTo(w - 14, 0); poly.lineTo(w, 14); poly.lineTo(w, h); poly.lineTo(0, h);
            } else {
                poly.moveTo(0, 0); poly.lineTo(w, 0); poly.lineTo(w, h); poly.lineTo(0, h);
            }
            poly.closePath();
            g.setColor(bg);
            g.fill(poly);
            g.setFont(font);
            g.setColor(fg);
            FontMetrics fm = g.getFontMetrics();
            float baseline = (h + fm.getAscent() - fm.getDescent()) * 0.5f;
            drawSpaced(g, text, padX, baseline, ls);
        });
    }

    private Slab bakeSlab(int w, int h, double ax, double ay, int sideRgb,
                          double yc, double y0, double y1, double adv, double titleX,
                          boolean plate, boolean makeMask, Consumer<Graphics2D> painter) {
        int pw = Math.max(1, Math.round(w * BAKE_SCALE));
        int ph = Math.max(1, Math.round(h * BAKE_SCALE));
        BufferedImage faceImg = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = faceImg.createGraphics();
        setupHints(g);
        g.scale(BAKE_SCALE, BAKE_SCALE);
        painter.accept(g);
        g.dispose();

        DynamicTexture faceTex = uploadImage(faceImg);
        DynamicTexture maskTex = null;
        if (makeMask) {
            BufferedImage maskImg = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
            for (int py = 0; py < ph; py++) {
                for (int px = 0; px < pw; px++) {
                    int a = (faceImg.getRGB(px, py) >>> 24) & 0xFF;
                    maskImg.setRGB(px, py, (a << 24) | 0x00FFFFFF);
                }
            }
            maskTex = uploadImage(maskImg);
        }
        return new Slab(faceTex, maskTex, w, h, ax, ay, sideRgb, yc, y0, y1, adv, titleX, plate);
    }

    private DynamicTexture bakeTexture(int w, int h, float scale, Consumer<Graphics2D> painter) {
        int pw = Math.max(1, Math.round(w * scale));
        int ph = Math.max(1, Math.round(h * scale));
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        setupHints(g);
        g.scale(scale, scale);
        painter.accept(g);
        g.dispose();
        return uploadImage(img);
    }

    // ── Zero-Allocation ASCII Glyph Atlas Rendering ──────────────────────────

    private AsciiAtlas bakeAsciiAtlas(Font baseFont, float fontPx) {
        Font derived = baseFont.deriveFont(baseFont == fontDisplay ? Font.PLAIN : Font.BOLD, fontPx);
        int cols = 12, rows = 9;
        int cellW = (int) Math.ceil(fontPx * 1.05f);
        int cellH = (int) Math.ceil(fontPx * 1.45f);
        int texW = cols * cellW;
        int texH = rows * cellH;
        BufferedImage img = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        setupHints(g);
        g.setFont(derived);
        g.setColor(Color.WHITE);
        FontMetrics fm = g.getFontMetrics();
        float asc = fm.getAscent();
        float desc = fm.getDescent();

        AsciiAtlas atlas = new AsciiAtlas(null, fontPx, asc, desc);
        for (int ch = 32; ch <= 126; ch++) {
            int idx = ch - 32;
            int cx = (idx % cols) * cellW;
            int cy = (idx / cols) * cellH;
            String s = String.valueOf((char) ch);
            float adv = fm.stringWidth(s);
            float padX = 4f;
            float baseY = cy + asc + 4f;
            g.drawString(s, cx + padX, baseY);
            float gw = Math.min(cellW - 2f, Math.max(adv + padX * 2f, 8f));
            float gh = Math.min(cellH - 2f, asc + desc + 8f);
            atlas.cells[ch] = new GlyphCell(
                    cx / (float) texW,
                    cy / (float) texH,
                    (cx + gw) / (float) texW,
                    (cy + gh) / (float) texH,
                    adv, gw, gh
            );
        }
        g.dispose();
        DynamicTexture dt = uploadImage(img);
        AsciiAtlas finished = new AsciiAtlas(dt, fontPx, asc, desc);
        System.arraycopy(atlas.cells, 0, finished.cells, 0, 128);
        return finished;
    }

    /** Measures ASCII string width in virtual pixels using the pre-baked glyph atlas. */
    public double measureAscii(FontFace face, String text, double sizePx, double letterSpacing) {
        if (text == null || text.isEmpty()) return 0.0;
        AsciiAtlas atlas = (face == FontFace.DISPLAY) ? displayAtlas : monoAtlas;
        double scale = sizePx / atlas.fontPx;
        double total = 0.0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int code = (c >= 32 && c <= 126) ? c : ' ';
            GlyphCell cell = atlas.cells[code];
            if (cell != null) {
                total += cell.adv() * scale;
                if (i + 1 < text.length()) total += letterSpacing;
            }
        }
        return total;
    }

    /**
     * Draws an ASCII string directly from the pre-baked atlas (0 allocations, 1 GPU draw call).
     * {@code align}: -1 left, 0 center, 1 right. {@code y} is vertical center.
     */
    public void drawAscii(HabiKineticGfx gfx, FontFace face, String text,
                          double x, double y, double sizePx, double letterSpacing,
                          int argb, int align, double angleRad) {
        if (text == null || text.isEmpty() || (argb >>> 24) < 2) return;
        AsciiAtlas atlas = (face == FontFace.DISPLAY) ? displayAtlas : monoAtlas;
        if (atlas == null || atlas.tex == null) return;

        double scale = sizePx / atlas.fontPx;
        double totalW = measureAscii(face, text, sizePx, letterSpacing);
        double startX = align < 0 ? 0.0 : (align == 0 ? -totalW * 0.5 : -totalW);
        double topOff = -(atlas.ascent + atlas.descent + 8.0) * 0.5 * scale;
        double cos = angleRad == 0.0 ? 1.0 : Math.cos(angleRad);
        double sin = angleRad == 0.0 ? 0.0 : Math.sin(angleRad);

        gfx.bindTexture(atlas.tex.getId(), false, HabiKineticGfx.BlendMode.NORMAL);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        double penX = startX;
        boolean added = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int code = (c >= 32 && c <= 126) ? c : ' ';
            GlyphCell cell = atlas.cells[code];
            if (cell == null) continue;
            if (code != ' ') {
                double lx0 = penX - 4.0 * scale;
                double ly0 = topOff;
                double lx1 = lx0 + cell.drawW() * scale;
                double ly1 = ly0 + cell.drawH() * scale;

                double sx0 = x + lx0 * cos - ly0 * sin, sy0 = y + lx0 * sin + ly0 * cos;
                double sx1 = x + lx0 * cos - ly1 * sin, sy1 = y + lx0 * sin + ly1 * cos;
                double sx2 = x + lx1 * cos - ly1 * sin, sy2 = y + lx1 * sin + ly1 * cos;
                double sx3 = x + lx1 * cos - ly0 * sin, sy3 = y + lx1 * sin + ly0 * cos;

                b.addVertex(identity, (float) sx0, (float) sy0, 0f).setUv(cell.u0(), cell.v0()).setColor(argb);
                b.addVertex(identity, (float) sx1, (float) sy1, 0f).setUv(cell.u0(), cell.v1()).setColor(argb);
                b.addVertex(identity, (float) sx2, (float) sy2, 0f).setUv(cell.u1(), cell.v1()).setColor(argb);
                b.addVertex(identity, (float) sx3, (float) sy3, 0f).setUv(cell.u1(), cell.v0()).setColor(argb);
                added = true;
            }
            penX += cell.adv() * scale + letterSpacing;
        }
        if (added) {
            var mesh = b.build();
            if (mesh != null) BufferUploader.drawWithShader(mesh);
        }
    }

    /** Draws an ASCII string projected onto a 3D plane (O, M). */
    public void drawAsciiOnPlane(HabiKineticGfx gfx, HabiKineticGfx.Cam cam, double[] O, double[] M,
                                 FontFace face, String text, double localX, double localY,
                                 double sizePx, double letterSpacing, int argb, int align) {
        if (text == null || text.isEmpty() || (argb >>> 24) < 2) return;
        AsciiAtlas atlas = (face == FontFace.DISPLAY) ? displayAtlas : monoAtlas;
        if (atlas == null || atlas.tex == null) return;

        double scale = sizePx / atlas.fontPx;
        double totalW = measureAscii(face, text, sizePx, letterSpacing);
        double startX = localX + (align < 0 ? 0.0 : (align == 0 ? -totalW * 0.5 : -totalW));
        double topOff = localY - (atlas.ascent + atlas.descent + 8.0) * 0.5 * scale;
        double ux0 = M[0], ux1 = M[3], ux2 = M[6];
        double uy0 = M[1], uy1 = M[4], uy2 = M[7];

        gfx.bindTexture(atlas.tex.getId(), false, HabiKineticGfx.BlendMode.NORMAL);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        double penX = startX;
        boolean added = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int code = (c >= 32 && c <= 126) ? c : ' ';
            GlyphCell cell = atlas.cells[code];
            if (cell == null) continue;
            if (code != ' ') {
                double lx0 = penX - 4.0 * scale;
                double ly0 = topOff;
                double lx1 = lx0 + cell.drawW() * scale;
                double ly1 = ly0 + cell.drawH() * scale;

                double[] p0 = cam.project(O[0] + ux0 * lx0 + uy0 * ly0, O[1] + ux1 * lx0 + uy1 * ly0, O[2] + ux2 * lx0 + uy2 * ly0);
                double[] p1 = cam.project(O[0] + ux0 * lx0 + uy0 * ly1, O[1] + ux1 * lx0 + uy1 * ly1, O[2] + ux2 * lx0 + uy2 * ly1);
                double[] p2 = cam.project(O[0] + ux0 * lx1 + uy0 * ly1, O[1] + ux1 * lx1 + uy1 * ly1, O[2] + ux2 * lx1 + uy2 * ly1);
                double[] p3 = cam.project(O[0] + ux0 * lx1 + uy0 * ly0, O[1] + ux1 * lx1 + uy1 * ly0, O[2] + ux2 * lx1 + uy2 * ly0);
                if (p0 != null && p1 != null && p2 != null && p3 != null) {
                    b.addVertex(identity, (float) p0[0], (float) p0[1], 0f).setUv(cell.u0(), cell.v0()).setColor(argb);
                    b.addVertex(identity, (float) p1[0], (float) p1[1], 0f).setUv(cell.u0(), cell.v1()).setColor(argb);
                    b.addVertex(identity, (float) p2[0], (float) p2[1], 0f).setUv(cell.u1(), cell.v1()).setColor(argb);
                    b.addVertex(identity, (float) p3[0], (float) p3[1], 0f).setUv(cell.u1(), cell.v0()).setColor(argb);
                    added = true;
                }
            }
            penX += cell.adv() * scale + letterSpacing;
        }
        if (added) {
            var mesh = b.build();
            if (mesh != null) BufferUploader.drawWithShader(mesh);
        }
    }

    // ── LRU Dynamic Text Rendering (CJK & Mixed Typography) ──────────────────

    /** Gets or rasterizes a white-tinted crisp text texture (color applied via OpenGL tint!). */
    public TextTex getCachedText(FontFace face, String text, int sizePx, float letterSpacing, int maxWrapW) {
        if (text == null || text.isEmpty()) return new TextTex(null, 0, 0);
        String key = face.ordinal() + "|" + sizePx + "|" + Math.round(letterSpacing * 10f) + "|" + maxWrapW + "|" + text;
        TextTex cached = textCache.get(key);
        if (cached != null) return cached;

        Font base = switch (face) {
            case DISPLAY -> fontDisplay;
            case SANS_CN -> fontSansCn;
            case MONO -> fontMono;
        };
        Font f = base.deriveFont(face == FontFace.DISPLAY ? Font.PLAIN : Font.BOLD, (float) sizePx);

        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        pg.setFont(f);
        FontMetrics fm = pg.getFontMetrics();
        List<String> lines = maxWrapW > 0 ? wrapText(pg, text, letterSpacing, maxWrapW) : List.of(text);
        double maxW = 1.0;
        for (String line : lines) {
            maxW = Math.max(maxW, measureSpaced(pg, line, letterSpacing));
        }
        int lineH = Math.max(sizePx + 4, (int) Math.ceil((fm.getAscent() + fm.getDescent()) * 1.28));
        int logW = Math.max(2, (int) Math.ceil(maxW + 8));
        int logH = Math.max(2, lines.size() == 1 ? (fm.getAscent() + fm.getDescent() + 8) : (lines.size() * lineH + 6));
        int ascent = fm.getAscent();
        pg.dispose();

        float scale = 2.0f;
        int pw = Math.max(2, Math.round(logW * scale));
        int ph = Math.max(2, Math.round(logH * scale));
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        setupHints(g);
        g.scale(scale, scale);
        g.setFont(f);
        g.setColor(Color.WHITE);
        for (int i = 0; i < lines.size(); i++) {
            float by = (lines.size() == 1) ? (ascent + 4f) : (ascent + 3f + i * lineH);
            drawSpaced(g, lines.get(i), 4f, by, letterSpacing);
        }
        g.dispose();

        DynamicTexture dt = uploadUnmanaged(img);
        TextTex tt = new TextTex(dt, logW, logH);
        textCache.put(key, tt);
        if (textCache.size() > MAX_TEXT_CACHE) {
            Iterator<Map.Entry<String, TextTex>> it = textCache.entrySet().iterator();
            if (it.hasNext()) {
                TextTex evicted = it.next().getValue();
                it.remove();
                if (evicted.tex() != null) evicted.tex().close();
            }
        }
        return tt;
    }

    /**
     * Draws a cached CJK/mixed string (align: -1 left, 0 center, 1 right; {@code y} is vertical center
     * for single line, or top-aligned when {@code maxWrapW > 0}).
     */
    public TextTex drawText(HabiKineticGfx gfx, FontFace face, String text,
                            double x, double y, int sizePx, float letterSpacing,
                            int argb, int align, double angleRad, int maxWrapW) {
        TextTex tt = getCachedText(face, text, sizePx, letterSpacing, maxWrapW);
        if (tt.texId() <= 0 || (argb >>> 24) < 2) return tt;
        double dx = align < 0 ? x : (align == 0 ? x - tt.w() * 0.5 : x - tt.w());
        double dy = maxWrapW > 0 ? y : (y - tt.h() * 0.5);
        if (Math.abs(angleRad) < 1e-4) {
            gfx.drawTexturedRect(tt.texId(), dx, dy, tt.w(), tt.h(), argb, HabiKineticGfx.BlendMode.NORMAL);
        } else {
            double cx = dx + tt.w() * 0.5;
            double cy = dy + tt.h() * 0.5;
            // Rotate around (x, y) anchor
            double rx = cx - x, ry = cy - y;
            double c = Math.cos(angleRad), s = Math.sin(angleRad);
            gfx.drawTexturedRectRotated(tt.texId(), x + rx * c - ry * s, y + rx * s + ry * c,
                    tt.w(), tt.h(), angleRad, argb, HabiKineticGfx.BlendMode.NORMAL);
        }
        return tt;
    }

    public void drawTextOnPlane(HabiKineticGfx gfx, HabiKineticGfx.Cam cam, double[] O, double[] M,
                                FontFace face, String text, double localX, double localY,
                                int sizePx, float letterSpacing, int argb, int align) {
        TextTex tt = getCachedText(face, text, sizePx, letterSpacing, 0);
        if (tt.texId() <= 0 || (argb >>> 24) < 2) return;
        double ax = align < 0 ? -localX : (align == 0 ? tt.w() * 0.5 - localX : tt.w() - localX);
        double ay = tt.h() * 0.5 - localY;
        gfx.drawPlaneSprite(cam, O, M, tt.texId(), ax, ay, tt.w(), tt.h(), argb, HabiKineticGfx.BlendMode.NORMAL);
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private DynamicTexture uploadImage(BufferedImage img) {
        DynamicTexture dt = uploadUnmanaged(img);
        ownedTextures.add(dt);
        return dt;
    }

    private static DynamicTexture uploadUnmanaged(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        NativeImage nativeImg = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                // NativeImage ABGR packed int
                nativeImg.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        DynamicTexture dt = new DynamicTexture(nativeImg);
        dt.setFilter(true, false);
        return dt;
    }

    private static void setupHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static double measureSpaced(Graphics2D g, String text, float ls) {
        FontMetrics fm = g.getFontMetrics();
        if (Math.abs(ls) < 0.01f) return fm.stringWidth(text);
        double w = 0.0;
        for (int i = 0; i < text.length(); i++) {
            w += fm.charWidth(text.charAt(i));
            if (i + 1 < text.length()) w += ls;
        }
        return w;
    }

    private static void drawSpaced(Graphics2D g, String text, float x, float baselineY, float ls) {
        if (Math.abs(ls) < 0.01f) {
            g.drawString(text, x, baselineY);
            return;
        }
        FontMetrics fm = g.getFontMetrics();
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            g.drawString(ch, cx, baselineY);
            cx += fm.stringWidth(ch) + ls;
        }
    }

    private static List<String> wrapText(Graphics2D g, String text, float ls, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        double curW = 0.0;
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(cur.toString());
                cur.setLength(0);
                curW = 0.0;
                continue;
            }
            double cw = fm.charWidth(c) + (cur.length() > 0 ? ls : 0f);
            if (cur.length() > 0 && curW + cw > maxWidth) {
                lines.add(cur.toString());
                cur.setLength(0);
                curW = 0.0;
            }
            cur.append(c);
            curW += cw;
        }
        if (cur.length() > 0 || lines.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    private static int nextMulberry(int seed) {
        return seed + 0x6D2B79F5;
    }

    private static double toUnit(int seed) {
        int x = (seed ^ (seed >>> 15)) * (1 | seed);
        x = (x + (x ^ (x >>> 7)) * (61 | x)) ^ x;
        return ((x ^ (x >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    public void dispose() {
        for (DynamicTexture dt : ownedTextures) {
            if (dt != null) dt.close();
        }
        ownedTextures.clear();
        for (TextTex tt : textCache.values()) {
            if (tt.tex() != null) tt.tex().close();
        }
        textCache.clear();
        ready = false;
    }
}
