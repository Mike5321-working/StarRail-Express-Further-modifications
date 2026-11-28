package com.habitrain.core.client.title;

import com.habitrain.core.HabiTrainCore;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryUtil;

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
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * High-DPI vector sprite, font, and dynamic-text texture baker for HabiTrain Kinetic UI v3.
 *
 * <p>Decouples Java2D CPU rasterization (fonts, Station 00 sprites, Station 01 sprites, post
 * textures, and ASCII glyph atlases) onto a dedicated background thread started during client
 * initialization so Boot Frame 0 never stalls waiting for font decoding or software rendering.
 * OpenGL uploads run on the render thread via bulk off-heap buffer transfer (eliminating millions
 * of per-pixel JNI calls), with Station 01 textures uploaded incrementally across idle frames.</p>
 */
@Environment(EnvType.CLIENT)
public final class HabiKineticAtlas {

    public static final int[][] BANDS = {{-172, -50}, {-40, 6}, {16, 52}, {62, 92}, {102, 126}, {136, 172}};
    public static final int WIN_BAND = 1;

    private static final float BAKE_SCALE = 2.0f;
    private static final int MAX_TEXT_CACHE = 256;
    private static final String FONT_BASE = "assets/habitrain_core/font/replay/";
    private static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    // Reusable render-thread direct upload scratch buffer (avoids per-texture malloc/free)
    private static @Nullable ByteBuffer sharedUploadScratch;

    /** Off-thread CPU-rasterized RGBA image ready for bulk OpenGL upload. */
    static final class CpuImage {
        final int width;
        final int height;
        final int[] packedRgba; // packed for native byte order so bytes in memory are [R, G, B, A]
        final boolean repeat;

        CpuImage(int width, int height, int[] packedRgba, boolean repeat) {
            this.width = width;
            this.height = height;
            this.packedRgba = packedRgba;
            this.repeat = repeat;
        }

        static CpuImage fromBufferedImage(BufferedImage img, boolean repeat) {
            int w = img.getWidth();
            int h = img.getHeight();
            int[] src = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int[] dst = new int[src.length];
            if (IS_LITTLE_ENDIAN) {
                for (int i = 0; i < src.length; i++) {
                    int argb = src[i];
                    dst[i] = (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >>> 16) | ((argb & 0x000000FF) << 16);
                }
            } else {
                for (int i = 0; i < src.length; i++) {
                    int argb = src[i];
                    dst[i] = ((argb & 0x00FFFFFF) << 8) | ((argb >>> 24) & 0xFF);
                }
            }
            return new CpuImage(w, h, dst, repeat);
        }

        static CpuImage silhouetteFrom(CpuImage src, int rgb) {
            int[] srcPx = src.packedRgba;
            int[] dst = new int[srcPx.length];
            if (IS_LITTLE_ENDIAN) {
                int bgr = ((rgb & 0x00FF0000) >>> 16) | (rgb & 0x0000FF00) | ((rgb & 0x000000FF) << 16);
                for (int i = 0; i < srcPx.length; i++) {
                    dst[i] = (srcPx[i] & 0xFF000000) | bgr;
                }
            } else {
                int rgbShifted = (rgb & 0x00FFFFFF) << 8;
                for (int i = 0; i < srcPx.length; i++) {
                    dst[i] = rgbShifted | (srcPx[i] & 0xFF);
                }
            }
            return new CpuImage(src.width, src.height, dst, false);
        }
    }

    private static final class CpuSlab {
        CpuImage face;
        @Nullable CpuImage mask;
        final double w, h, ax, ay, yc, y0, y1, adv, titleX;
        final int sideRgb;
        final boolean plate;

        CpuSlab(CpuImage face, @Nullable CpuImage mask, double w, double h, double ax, double ay,
                int sideRgb, double yc, double y0, double y1, double adv, double titleX, boolean plate) {
            this.face = face;
            this.mask = mask;
            this.w = w;
            this.h = h;
            this.ax = ax;
            this.ay = ay;
            this.sideRgb = sideRgb;
            this.yc = yc;
            this.y0 = y0;
            this.y1 = y1;
            this.adv = adv;
            this.titleX = titleX;
            this.plate = plate;
        }
    }

    private static final class CpuAsciiAtlas {
        CpuImage image;
        final float fontPx, ascent, descent;
        final GlyphCell[] cells;

        CpuAsciiAtlas(CpuImage image, float fontPx, float ascent, float descent, GlyphCell[] cells) {
            this.image = image;
            this.fontPx = fontPx;
            this.ascent = ascent;
            this.descent = descent;
            this.cells = cells;
        }
    }

    private static final class CpuPrebakedText {
        final String key;
        CpuImage image;
        final double w, h;

        CpuPrebakedText(String key, CpuImage image, double w, double h) {
            this.key = key;
            this.image = image;
            this.w = w;
            this.h = h;
        }
    }

    private static final class CpuZeroPack {
        final CpuSlab[] zeroBands = new CpuSlab[BANDS.length];
        CpuSlab zeroStem, zeroLeaf;
        CpuImage zeroOutlineDark, zeroOutlinePaper;
        final List<CpuSlab> titleGlyphs = new ArrayList<>();
        double titleWidth, titleCapH;
        CpuSlab tagLead, tagCore, tagPrompt, tagAutoBoard, tagSyncBoard, tagDepart;
        CpuSlab loadBadge, cursor, zeroCarLead, zeroCar;
        CpuSlab cardA, cardB, cardC, cardD;
        CpuImage noiseTex, vigPaperTex, vigDarkTex;
        CpuAsciiAtlas monoAtlas, displayAtlas;
        final List<CpuPrebakedText> prebakedTexts = new ArrayList<>();
    }

    private static final class CpuStation01Pack {
        final CpuSlab[] stBands = new CpuSlab[BANDS.length];
        CpuSlab stStem, stLeaf, stCarLead, stCar, plateStation, plateRoute;
        CpuImage stationBgTex, stationAuraTex;
        final List<CpuPrebakedText> prebakedTexts = new ArrayList<>();
    }

    /** Static GPU-only texture uploaded via bulk off-heap memory transfer on the render thread. */
    public static final class StaticGlTexture extends AbstractTexture {
        StaticGlTexture(CpuImage cpu) {
            int texId = this.getId();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            TextureUtil.prepareImage(texId, cpu.width, cpu.height);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            int wrap = cpu.repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);

            int byteLen = cpu.packedRgba.length * 4;
            ByteBuffer scratch = acquireUploadScratch(byteLen);
            scratch.asIntBuffer().put(cpu.packedRgba);
            GlStateManager._texSubImage2D(
                    GL11.GL_TEXTURE_2D, 0, 0, 0, cpu.width, cpu.height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(scratch)
            );
        }

        @Override
        public void load(ResourceManager manager) {}

        @Override
        public void close() {
            this.releaseId();
        }
    }

    private static ByteBuffer acquireUploadScratch(int byteSize) {
        if (sharedUploadScratch == null || sharedUploadScratch.capacity() < byteSize) {
            if (sharedUploadScratch != null) {
                MemoryUtil.memFree(sharedUploadScratch);
            }
            int alloc = Math.max(byteSize, 512 * 512 * 4);
            sharedUploadScratch = MemoryUtil.memAlloc(alloc);
        }
        sharedUploadScratch.clear();
        return sharedUploadScratch;
    }

    static void releaseUploadScratch() {
        if (sharedUploadScratch != null) {
            MemoryUtil.memFree(sharedUploadScratch);
            sharedUploadScratch = null;
        }
    }

    public record Slab(
            StaticGlTexture faceTex,
            StaticGlTexture maskTex,
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

    public record TextTex(StaticGlTexture tex, double w, double h) {
        public int texId() { return tex != null ? tex.getId() : 0; }
    }

    private record GlyphCell(float u0, float v0, float u1, float v1, float adv, float drawW, float drawH) {}

    private static final class AsciiAtlas {
        final StaticGlTexture tex;
        final float fontPx;
        final float ascent;
        final float descent;
        final GlyphCell[] cells = new GlyphCell[128];

        AsciiAtlas(StaticGlTexture tex, float fontPx, float ascent, float descent) {
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

    private final Object fontLock = new Object();
    private final Object preloadLock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);

    private volatile Font fontDisplay;
    private volatile Font fontSansCn;
    private volatile Font fontMono;

    private volatile boolean zeroReady;
    private volatile boolean station01Ready;
    private volatile boolean shuttingDown;
    private int preloadStartedGen = -1;
    private @Nullable CompletableFuture<CpuZeroPack> zeroCpuFuture;
    private @Nullable CompletableFuture<CpuStation01Pack> st01CpuFuture;
    private @Nullable CpuStation01Pack uploadingSt01Pack;
    private int uploadingSt01Cursor;

    // ── Station 00 sprites ───────────────────────────────────────────────────
    public final Slab[] zeroBands = new Slab[BANDS.length];
    public Slab zeroStem;
    public Slab zeroLeaf;
    public StaticGlTexture zeroOutlineDark;
    public StaticGlTexture zeroOutlinePaper;
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
    public StaticGlTexture noiseTex;
    public StaticGlTexture vigPaperTex;
    public StaticGlTexture vigDarkTex;
    public StaticGlTexture stationBgTex;
    public StaticGlTexture stationAuraTex;

    // ── Zero-allocation ASCII glyph atlases ──────────────────────────────────
    private AsciiAtlas monoAtlas;
    private AsciiAtlas displayAtlas;

    // ── Bounded LRU CJK/mixed text texture cache ─────────────────────────────
    private final Map<String, TextTex> textCache = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<Long, FontMetrics> metricsCache = new LinkedHashMap<>(16, 0.75f, false);
    private final List<StaticGlTexture> ownedTextures = new ArrayList<>();

    private HabiKineticAtlas() {}

    public boolean isReady() {
        return zeroReady;
    }

    public boolean isStation01Ready() {
        return zeroReady && station01Ready;
    }

    /**
     * Kicks off background-thread font loading and CPU rasterization for Station 00 followed by
     * Station 01. Safe to call from any thread (including {@code ClientModInitializer}).
     */
    public void preloadAsync() {
        if (shuttingDown || (zeroReady && station01Ready)) return;
        final int gen;
        final CompletableFuture<CpuZeroPack> zFuture;
        final CompletableFuture<CpuStation01Pack> sFuture;
        synchronized (preloadLock) {
            gen = generation.get();
            if (preloadStartedGen == gen) {
                return;
            }
            preloadStartedGen = gen;
            zFuture = new CompletableFuture<>();
            sFuture = new CompletableFuture<>();
            zeroCpuFuture = zFuture;
            st01CpuFuture = sFuture;
        }

        Thread baker = new Thread(() -> {
            try {
                long t0 = System.nanoTime();
                ensureFontsLoaded();
                if (generation.get() != gen) {
                    zFuture.cancel(false);
                    sFuture.cancel(false);
                    return;
                }
                CpuZeroPack zeroPack = bakeZeroStationCpu();
                double zeroMs = (System.nanoTime() - t0) / 1_000_000.0;
                HabiLoadingPerfMonitor.getInstance().recordAtlasCpuBake("KineticAtlas", "zero", zeroMs, true);
                zFuture.complete(zeroPack);

                if (generation.get() != gen) {
                    sFuture.cancel(false);
                    return;
                }
                long t1 = System.nanoTime();
                CpuStation01Pack st01Pack = bakeStation01Cpu();
                double st01Ms = (System.nanoTime() - t1) / 1_000_000.0;
                HabiLoadingPerfMonitor.getInstance().recordAtlasCpuBake("KineticAtlas", "station01", st01Ms, true);
                sFuture.complete(st01Pack);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[HabiKineticAtlas] Background CPU rasterization failed", t);
                zFuture.completeExceptionally(t);
                sFuture.completeExceptionally(t);
            }
        }, "habitrain-kinetic-atlas-baker");
        baker.setDaemon(true);
        baker.setPriority(Thread.NORM_PRIORITY + 1);
        baker.start();
    }

    /** Ensures Station 00 sprites, post textures, and ASCII glyph atlases are uploaded on the render thread. */
    public double ensureZeroPrepared() {
        if (shuttingDown || zeroReady) return 0.0;
        preloadAsync();

        CompletableFuture<CpuZeroPack> future;
        synchronized (preloadLock) {
            future = zeroCpuFuture;
        }
        CpuZeroPack pack = null;
        double syncWaitMs = 0.0;
        if (future != null) {
            boolean doneAlready = future.isDone();
            long waitStart = doneAlready ? 0L : System.nanoTime();
            try {
                pack = future.join();
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[HabiKineticAtlas] Falling back to synchronous Station 00 bake", t);
            }
            if (!doneAlready) {
                syncWaitMs = (System.nanoTime() - waitStart) / 1_000_000.0;
            }
        }
        if (pack == null) {
            long cpuStart = System.nanoTime();
            ensureFontsLoaded();
            pack = bakeZeroStationCpu();
            HabiLoadingPerfMonitor.getInstance().recordAtlasCpuBake(
                    "KineticAtlas", "zero", (System.nanoTime() - cpuStart) / 1_000_000.0, false);
        }

        long upStart = System.nanoTime();
        int uploaded = uploadZeroPack(pack);
        double upMs = (System.nanoTime() - upStart) / 1_000_000.0;
        synchronized (preloadLock) {
            zeroCpuFuture = null;
        }
        zeroReady = true;
        HabiLoadingPerfMonitor.getInstance().recordAtlasUpload("KineticAtlas", "zero", uploaded, upMs, syncWaitMs);
        return upMs + syncWaitMs;
    }

    /**
     * Incrementally uploads Station 01 textures on the render thread within {@code budgetMs} once
     * background CPU baking has finished (zero blocking on the render thread).
     */
    public double pumpStation01Uploads(double budgetMs) {
        if (shuttingDown || station01Ready) return 0.0;
        if (uploadingSt01Pack == null) {
            CompletableFuture<CpuStation01Pack> future;
            synchronized (preloadLock) {
                future = st01CpuFuture;
            }
            if (future == null || !future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
                return 0.0;
            }
            uploadingSt01Pack = future.join();
            uploadingSt01Cursor = 0;
            synchronized (preloadLock) {
                st01CpuFuture = null;
            }
        }
        return uploadStation01Steps(budgetMs, false);
    }

    /** Ensures both Station 00 and Station 01 atlases are completely uploaded on the render thread. */
    public double ensureStation01Prepared() {
        if (shuttingDown) return 0.0;
        double totalMs = ensureZeroPrepared();
        if (station01Ready) return totalMs;

        if (uploadingSt01Pack == null) {
            preloadAsync();
            CompletableFuture<CpuStation01Pack> future;
            synchronized (preloadLock) {
                future = st01CpuFuture;
            }
            double syncWaitMs = 0.0;
            CpuStation01Pack pack = null;
            if (future != null) {
                boolean doneAlready = future.isDone();
                long waitStart = doneAlready ? 0L : System.nanoTime();
                try {
                    pack = future.join();
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[HabiKineticAtlas] Falling back to synchronous Station 01 bake", t);
                }
                if (!doneAlready) {
                    syncWaitMs = (System.nanoTime() - waitStart) / 1_000_000.0;
                }
            }
            if (pack == null) {
                long cpuStart = System.nanoTime();
                ensureFontsLoaded();
                pack = bakeStation01Cpu();
                HabiLoadingPerfMonitor.getInstance().recordAtlasCpuBake(
                        "KineticAtlas", "station01", (System.nanoTime() - cpuStart) / 1_000_000.0, false);
            }
            uploadingSt01Pack = pack;
            uploadingSt01Cursor = 0;
            synchronized (preloadLock) {
                st01CpuFuture = null;
            }
            if (syncWaitMs > 0.0) {
                HabiLoadingPerfMonitor.getInstance().recordAtlasUpload("KineticAtlas", "station01", 0, 0.0, syncWaitMs);
                totalMs += syncWaitMs;
            }
        }
        totalMs += uploadStation01Steps(Double.POSITIVE_INFINITY, true);
        return totalMs;
    }

    /** Backward-compatible full preparation entry point (Station 00 + Station 01). */
    public void ensurePrepared() {
        ensureStation01Prepared();
    }

    /** Package-private headless CPU baking entry point for unit testing & benchmarking. */
    void prebakeCpuOnlyForTest() {
        ensureFontsLoaded();
        bakeZeroStationCpu();
        bakeStation01Cpu();
    }

    void ensureFontsLoaded() {
        if (fontDisplay != null && fontSansCn != null && fontMono != null) return;
        synchronized (fontLock) {
            if (fontDisplay == null) {
                fontDisplay = loadClasspathFont(FONT_BASE + "bebas.ttf", Font.PLAIN, "SansSerif");
            }
            if (fontSansCn == null) {
                fontSansCn = loadClasspathFont(FONT_BASE + "noto_sans_sc_bold.ttf", Font.BOLD, "SansSerif");
            }
            if (fontMono == null) {
                fontMono = loadClasspathFont(FONT_BASE + "DejaVuSansMono-Bold.ttf", Font.BOLD, "Monospaced");
            }
        }
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

    // ── GPU Upload Routines (Render Thread Only) ─────────────────────────────

    private int uploadZeroPack(CpuZeroPack p) {
        int count = 0;
        for (int i = 0; i < BANDS.length; i++) {
            zeroBands[i] = uploadCpuSlab(p.zeroBands[i]);
            count += zeroBands[i].maskTex() != null ? 2 : 1;
            p.zeroBands[i] = null;
        }
        zeroStem = uploadCpuSlab(p.zeroStem); count += 2; p.zeroStem = null;
        zeroLeaf = uploadCpuSlab(p.zeroLeaf); count += 2; p.zeroLeaf = null;
        zeroOutlinePaper = uploadOwnedCpu(p.zeroOutlinePaper); count++; p.zeroOutlinePaper = null;
        zeroOutlineDark = uploadOwnedCpu(p.zeroOutlineDark); count++; p.zeroOutlineDark = null;

        titleGlyphs.clear();
        for (CpuSlab g : p.titleGlyphs) {
            Slab uploaded = uploadCpuSlab(g);
            titleGlyphs.add(uploaded);
            count += uploaded.maskTex() != null ? 2 : 1;
        }
        p.titleGlyphs.clear();
        this.titleWidth = p.titleWidth;
        this.titleCapH = p.titleCapH;

        tagLead = uploadCpuSlab(p.tagLead); count += 2; p.tagLead = null;
        tagCore = uploadCpuSlab(p.tagCore); count += 2; p.tagCore = null;
        tagPrompt = uploadCpuSlab(p.tagPrompt); count += 2; p.tagPrompt = null;
        tagAutoBoard = uploadCpuSlab(p.tagAutoBoard); count += 2; p.tagAutoBoard = null;
        tagSyncBoard = uploadCpuSlab(p.tagSyncBoard); count += 2; p.tagSyncBoard = null;
        tagDepart = uploadCpuSlab(p.tagDepart); count += 2; p.tagDepart = null;

        loadBadge = uploadCpuSlab(p.loadBadge); count += 2; p.loadBadge = null;
        cursor = uploadCpuSlab(p.cursor); count += 2; p.cursor = null;
        zeroCarLead = uploadCpuSlab(p.zeroCarLead); count += 2; p.zeroCarLead = null;
        zeroCar = uploadCpuSlab(p.zeroCar); count += 2; p.zeroCar = null;

        cardA = uploadCpuSlab(p.cardA); count += 2; p.cardA = null;
        cardB = uploadCpuSlab(p.cardB); count += 2; p.cardB = null;
        cardC = uploadCpuSlab(p.cardC); count++; p.cardC = null;
        cardD = uploadCpuSlab(p.cardD); count++; p.cardD = null;

        noiseTex = uploadOwnedCpu(p.noiseTex); count++; p.noiseTex = null;
        vigPaperTex = uploadOwnedCpu(p.vigPaperTex); count++; p.vigPaperTex = null;
        vigDarkTex = uploadOwnedCpu(p.vigDarkTex); count++; p.vigDarkTex = null;

        monoAtlas = uploadCpuAsciiAtlas(p.monoAtlas); count++; p.monoAtlas = null;
        displayAtlas = uploadCpuAsciiAtlas(p.displayAtlas); count++; p.displayAtlas = null;
        count += uploadPrebakedTexts(p.prebakedTexts);
        return count;
    }

    private int uploadPrebakedTexts(List<CpuPrebakedText> texts) {
        int count = 0;
        for (CpuPrebakedText pt : texts) {
            if (pt == null || pt.image == null) continue;
            if (!textCache.containsKey(pt.key)) {
                StaticGlTexture dt = uploadCpuImage(pt.image);
                textCache.put(pt.key, new TextTex(dt, pt.w, pt.h));
                count++;
            }
            pt.image = null;
        }
        texts.clear();
        return count;
    }

    private double uploadStation01Steps(double budgetMs, boolean finishAll) {
        CpuStation01Pack p = uploadingSt01Pack;
        if (p == null) return 0.0;
        long startNanos = System.nanoTime();
        long deadlineNanos = Double.isInfinite(budgetMs)
                ? Long.MAX_VALUE
                : startNanos + Math.round(Math.max(0.2, budgetMs) * 1_000_000.0);
        int texCount = 0;
        int stepsThisCall = 0;
        final int totalSteps = BANDS.length + 9;

        while (uploadingSt01Cursor < totalSteps) {
            if (!finishAll && stepsThisCall > 0 && System.nanoTime() >= deadlineNanos) {
                break;
            }
            int step = uploadingSt01Cursor++;
            if (step < BANDS.length) {
                stBands[step] = uploadCpuSlab(p.stBands[step]);
                texCount += 2;
                p.stBands[step] = null;
            } else {
                switch (step - BANDS.length) {
                    case 0 -> { stStem = uploadCpuSlab(p.stStem); texCount += 2; p.stStem = null; }
                    case 1 -> { stLeaf = uploadCpuSlab(p.stLeaf); texCount += 2; p.stLeaf = null; }
                    case 2 -> { stCarLead = uploadCpuSlab(p.stCarLead); texCount += 2; p.stCarLead = null; }
                    case 3 -> { stCar = uploadCpuSlab(p.stCar); texCount += 2; p.stCar = null; }
                    case 4 -> { plateStation = uploadCpuSlab(p.plateStation); texCount += 2; p.plateStation = null; }
                    case 5 -> { plateRoute = uploadCpuSlab(p.plateRoute); texCount += 2; p.plateRoute = null; }
                    case 6 -> { stationBgTex = uploadOwnedCpu(p.stationBgTex); texCount++; p.stationBgTex = null; }
                    case 7 -> { stationAuraTex = uploadOwnedCpu(p.stationAuraTex); texCount++; p.stationAuraTex = null; }
                    case 8 -> { texCount += uploadPrebakedTexts(p.prebakedTexts); }
                    default -> {}
                }
            }
            stepsThisCall++;
        }

        if (uploadingSt01Cursor >= totalSteps) {
            uploadingSt01Pack = null;
            station01Ready = true;
        }
        double upMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        if (texCount > 0) {
            HabiLoadingPerfMonitor.getInstance().recordAtlasUpload("KineticAtlas", "station01", texCount, upMs, 0.0);
        }
        return upMs;
    }

    private Slab uploadCpuSlab(CpuSlab c) {
        StaticGlTexture faceTex = uploadOwnedCpu(c.face);
        c.face = null;
        StaticGlTexture maskTex = c.mask != null ? uploadOwnedCpu(c.mask) : null;
        c.mask = null;
        return new Slab(faceTex, maskTex, c.w, c.h, c.ax, c.ay, c.sideRgb, c.yc, c.y0, c.y1, c.adv, c.titleX, c.plate);
    }

    private AsciiAtlas uploadCpuAsciiAtlas(CpuAsciiAtlas c) {
        StaticGlTexture dt = uploadOwnedCpu(c.image);
        c.image = null;
        AsciiAtlas finished = new AsciiAtlas(dt, c.fontPx, c.ascent, c.descent);
        System.arraycopy(c.cells, 0, finished.cells, 0, 128);
        return finished;
    }

    private StaticGlTexture uploadOwnedCpu(CpuImage cpu) {
        StaticGlTexture dt = uploadCpuImage(cpu);
        ownedTextures.add(dt);
        return dt;
    }

    static StaticGlTexture uploadCpuImage(CpuImage cpu) {
        return new StaticGlTexture(cpu);
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

    // ── Off-Thread CPU Rasterization (Station 00) ────────────────────────────

    private CpuZeroPack bakeZeroStationCpu() {
        CpuZeroPack out = new CpuZeroPack();
        final Color cInk = new Color(0x16171D);
        final Color cRed = new Color(0xE1243B);
        final Color cRedShade = new Color(0xC41A30);
        final Color cPaperTxt = new Color(0xF4F1EA);
        final Color cWhiteHi = new Color(255, 249, 242, 242);
        final Color cMute = new Color(0x8C8981);

        // 1) Sliced apple bands (6 bands)
        Path2D.Double apple = createApplePath();
        for (int i = 0; i < BANDS.length; i++) {
            final int y0 = BANDS[i][0], y1 = BANDS[i][1];
            final double yc = (y0 + y1) * 0.5;
            final int w = 344, h = (y1 - y0) + 4;
            final boolean isWin = (i == WIN_BAND);
            out.zeroBands[i] = bakeCpuSlab(w, h, w * 0.5, h * 0.5, isWin ? 0x050508 : 0x8E1323,
                    yc, y0, y1, w, 0, false, true, g -> {
                        g.translate(w * 0.5, -y0 + 2.0);
                        g.clipRect(-w / 2, y0, w, y1 - y0);
                        g.setColor(isWin ? cInk : cRed);
                        g.fill(apple);
                        g.clip(apple);
                        if (!isWin) {
                            g.setColor(cRedShade);
                            var prev = g.getTransform();
                            g.translate(176, 26);
                            g.rotate(-0.18);
                            g.fill(new Ellipse2D.Double(-112, -230, 224, 460));
                            g.setTransform(prev);
                            g.setColor(cWhiteHi);
                            g.translate(-96, -96);
                            g.rotate(-0.72);
                            g.fill(new Ellipse2D.Double(-34, -14, 68, 28));
                            g.setTransform(prev);
                            g.fill(new Ellipse2D.Double(-56 - 6.5, -125 - 6.5, 13.0, 13.0));
                        } else {
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
        out.zeroStem = bakeCpuSlab(18, 66, 9, 33, 0x000000, 0, 0, 0, 18, 0, false, true, g -> {
            g.setColor(cInk);
            g.fill(new RoundRectangle2D.Double(2, 0, 14, 66, 14, 14));
        });
        out.zeroLeaf = bakeCpuSlab(120, 64, 4, 54, 0x000000, 0, 0, 0, 120, 0, false, true, g -> {
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
        out.zeroOutlinePaper = bakeCpuTexture(360, 360, BAKE_SCALE, g -> drawBlueprintOutline(g, cInk));
        out.zeroOutlineDark = bakeCpuTexture(360, 360, BAKE_SCALE, g -> drawBlueprintOutline(g, new Color(0xECE9E2)));

        // 4) Extruded Title Glyphs: H A B I [T] R A I N
        String codeName = "HABITRAIN";
        int plateIndex = 4;
        Font titleFont = fontDisplay.deriveFont(Font.PLAIN, 214f);
        BufferedImage probe = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        pg.setFont(titleFont);
        FontMetrics fm = pg.getFontMetrics();
        double capH = Math.ceil(fm.getAscent() * 0.80);
        out.titleCapH = capH;

        List<CpuSlab> rawGlyphs = new ArrayList<>();
        for (int i = 0; i < codeName.length(); i++) {
            final String ch = codeName.substring(i, i + 1);
            final double adv = fm.stringWidth(ch);
            if (i == plateIndex) {
                final int pw = (int) Math.ceil(adv + 46);
                final int ph = (int) Math.ceil(capH + 44);
                rawGlyphs.add(bakeCpuSlab(pw, ph, pw * 0.5, ph - 22.0, 0x16171D,
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
                rawGlyphs.add(bakeCpuSlab(gw, gh, gw * 0.5, gh - 5.0, 0xE1243B,
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
        for (CpuSlab s : rawGlyphs) total += s.adv;
        total += gap * (rawGlyphs.size() - 1);
        double curX = -total * 0.5;
        for (CpuSlab s : rawGlyphs) {
            out.titleGlyphs.add(new CpuSlab(
                    s.face, s.mask, s.w, s.h, s.ax, s.ay,
                    s.sideRgb, s.yc, s.y0, s.y1, s.adv, curX + s.adv * 0.5, s.plate
            ));
            curX += s.adv + gap;
        }
        out.titleWidth = total;

        // 5) Chinese chamfered tags
        out.tagLead = bakeCpuTag("直到列车变成", fontSansCn.deriveFont(Font.BOLD, 40f), 7f, 26, 66, cInk, cPaperTxt, 0xE1243B, "bl");
        out.tagCore = bakeCpuTag("一颗苹果", fontSansCn.deriveFont(Font.BOLD, 40f), 7f, 26, 66, cRed, cPaperTxt, 0x16171D, "tr");
        out.tagPrompt = bakeCpuTag("按任意键登车", fontSansCn.deriveFont(Font.BOLD, 26f), 10f, 22, 50, cInk, cPaperTxt, 0xE1243B, "bl");
        out.tagAutoBoard = bakeCpuTag("自动进站中", fontSansCn.deriveFont(Font.BOLD, 26f), 10f, 22, 50, cRed, cPaperTxt, 0x16171D, "bl");
        out.tagSyncBoard = bakeCpuTag("节拍校准中", fontSansCn.deriveFont(Font.BOLD, 26f), 10f, 22, 50, cRed, cPaperTxt, 0x16171D, "bl");
        out.tagDepart = bakeCpuTag("列车出发", fontSansCn.deriveFont(Font.BOLD, 26f), 10f, 22, 50, cRed, cPaperTxt, 0x16171D, "bl");

        out.loadBadge = bakeCpuSlab(210, 50, 105, 25, 0xE1243B, 0, 0, 0, 210, 0, false, true, g -> {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(0, 0); p.lineTo(210, 0); p.lineTo(210, 50); p.lineTo(14, 50); p.lineTo(0, 36); p.closePath();
            g.setColor(cInk);
            g.fill(p);
            g.setColor(cRed);
            g.fillRect(0, 0, 6, 36);
        });
        out.cursor = bakeCpuSlab(16, 30, 8, 15, 0x8E1323, 0, 0, 0, 16, 0, false, true, g -> {
            g.setColor(cRed);
            g.fillRect(0, 0, 16, 30);
        });

        // 6) 3-car red HABITRAIN locomotive & carriages
        out.zeroCarLead = bakeCpuTrainCar(58, 22, true, cRed, cInk, cPaperTxt, 0x8E1323);
        out.zeroCar = bakeCpuTrainCar(58, 22, false, cRed, cInk, cPaperTxt, 0x8E1323);

        // 7) 4 Floating parallax cards
        Font fDisp58 = fontDisplay.deriveFont(Font.PLAIN, 58f);
        Font fDisp62 = fontDisplay.deriveFont(Font.PLAIN, 62f);
        Font fMono10 = fontMono.deriveFont(Font.BOLD, 10f);

        out.cardA = bakeCpuSlab(224, 140, 112, 70, 0x16171D, 0, 0, 0, 224, 0, false, true, g -> {
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

        out.cardB = bakeCpuSlab(152, 200, 76, 100, 0x8E1323, 0, 0, 0, 152, 0, false, true, g -> {
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

        out.cardC = bakeCpuSlab(128, 128, 64, 64, 0x16171D, 0, 0, 0, 128, 0, false, false, g -> {
            g.setColor(cInk);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(2, 2, 124, 124);
            g.setStroke(new BasicStroke(1.2f));
            g.drawRect(22, 22, 84, 84);
            g.setColor(cRed);
            g.fillRect(100, 100, 8, 8);
        });

        out.cardD = bakeCpuSlab(184, 50, 92, 25, 0x16171D, 0, 0, 0, 184, 0, false, false, g -> {
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

        // 8) Film grain + Station 00 radial vignettes
        int[] nPixels = new int[256 * 256];
        int seed = 77;
        for (int i = 0; i < nPixels.length; i++) {
            seed = nextMulberry(seed);
            int v = (int) (toUnit(seed) * 255.0);
            nPixels[i] = IS_LITTLE_ENDIAN
                    ? ((0xFF << 24) | (v << 16) | (v << 8) | v)
                    : ((v << 24) | (v << 16) | (v << 8) | 0xFF);
        }
        out.noiseTex = new CpuImage(256, 256, nPixels, true);

        out.vigPaperTex = bakeCpuTexture(512, 288, 1f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(
                    256f, 144f, 315f,
                    new float[]{0.0f, 0.34f, 1.0f},
                    new Color[]{new Color(70, 55, 40, 0), new Color(70, 55, 40, 0), new Color(70, 55, 40, 42)}
            );
            g.setPaint(p);
            g.fillRect(0, 0, 512, 288);
        });
        out.vigDarkTex = bakeCpuTexture(512, 288, 1f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(
                    256f, 144f, 315f,
                    new float[]{0.0f, 0.34f, 1.0f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), new Color(0, 0, 0, 118)}
            );
            g.setPaint(p);
            g.fillRect(0, 0, 512, 288);
        });

        // 9) Zero-allocation ASCII glyph atlases
        out.monoAtlas = bakeCpuAsciiAtlas(fontMono, 40f);
        out.displayAtlas = bakeCpuAsciiAtlas(fontDisplay, 96f);

        // 10) Pre-bake static Chinese HUD labels off-thread so Boot frame 0 has 0 dynamic text misses
        prebakeZeroTexts(out.prebakedTexts);
        return out;
    }

    // ── Off-Thread CPU Rasterization (Station 01) ────────────────────────────

    private CpuStation01Pack bakeStation01Cpu() {
        CpuStation01Pack out = new CpuStation01Pack();
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
            out.stBands[i] = bakeCpuSlab(w, h, w * 0.5, h * 0.5, isWin ? 0x7E101E : 0x33394D,
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

        out.stStem = bakeCpuSlab(18, 66, 9, 33, 0x252936, 0, 0, 0, 18, 0, false, true, g -> {
            g.setColor(cPaper);
            g.fill(new RoundRectangle2D.Double(2, 0, 14, 66, 14, 14));
        });
        out.stLeaf = bakeCpuSlab(120, 64, 4, 54, 0x7E101E, 0, 0, 0, 120, 0, false, true, g -> {
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

        out.stCarLead = bakeCpuTrainCar(60, 23, true, cRedBright, cInk, new Color(0xFFF7E8), 0x7E101E);
        out.stCar = bakeCpuTrainCar(60, 23, false, cRedBright, cInk, new Color(0xFFF7E8), 0x7E101E);

        Font fMono10 = fontMono.deriveFont(Font.BOLD, 10f);
        Font fDisp54 = fontDisplay.deriveFont(Font.PLAIN, 54f);
        Font fDisp40 = fontDisplay.deriveFont(Font.PLAIN, 40f);
        Font fCn14 = fontSansCn.deriveFont(Font.BOLD, 14f);
        Font fCn20 = fontSansCn.deriveFont(Font.BOLD, 20f);

        out.plateStation = bakeCpuSlab(236, 126, 118, 63, 0x7E101E, 0, 0, 0, 236, 0, false, true, g -> {
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

        out.plateRoute = bakeCpuSlab(210, 112, 105, 56, 0x06070B, 0, 0, 0, 210, 0, false, true, g -> {
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

        out.stationBgTex = bakeCpuTexture(512, 288, 1f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(
                    256f + 85f, 144f - 10f, 340f,
                    new float[]{0.0f, 0.45f, 1.0f},
                    new Color[]{new Color(0x1D2233), new Color(0x12151F), new Color(0x0B0D13)}
            );
            g.setPaint(p);
            g.fillRect(0, 0, 512, 288);
        });

        out.stationAuraTex = bakeCpuTexture(128, 128, 1f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(
                    64f, 64f, 62f,
                    new float[]{0.0f, 0.45f, 1.0f},
                    new Color[]{new Color(225, 36, 59, 180), new Color(214, 178, 110, 72), new Color(0, 0, 0, 0)}
            );
            g.setPaint(p);
            g.fillRect(0, 0, 128, 128);
        });

        // Pre-bake static Chinese World-Entry HUD/Act labels off-thread
        prebakeWorldEntryTexts(out.prebakedTexts);
        return out;
    }

    private void prebakeZeroTexts(List<CpuPrebakedText> dst) {
        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        try {
            addPrebakedText(dst, pg, FontFace.SANS_CN, "装载中", 20, 4f, 0);
            addPrebakedText(dst, pg, FontFace.SANS_CN, HabiZeroStationRenderer.BRAND_CN, 14, 2f, 0);
            addPrebakedText(dst, pg, FontFace.SANS_CN, "欢迎登车", 46, 16f, 0);
            for (String ch : new String[]{"苹", "果", "列", "车", "第", "零", "站"}) {
                addPrebakedText(dst, pg, FontFace.SANS_CN, ch, 18, 0f, 0);
            }
            for (HabiRealProgressTracker.StageDef stg : HabiRealProgressTracker.BOOT_STAGES) {
                addPrebakedText(dst, pg, FontFace.SANS_CN, "//  " + stg.zh(), 11, 1.5f, 0);
            }
            for (HabiRealProgressTracker.TipItem tip : HabiRealProgressTracker.DEFAULT_TIPS) {
                addPrebakedText(dst, pg, FontFace.SANS_CN, tip.code() + "  //  " + tip.zh(), 12, 2f, 0);
            }
        } finally {
            pg.dispose();
        }
    }

    private void prebakeWorldEntryTexts(List<CpuPrebakedText> dst) {
        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        try {
            addPrebakedText(dst, pg, FontFace.SANS_CN, "检票通过 · 即将抵达", 30, 14.0f, 0);
            addPrebakedText(dst, pg, FontFace.SANS_CN, "世界维度同步就绪", 13, 3.5f, 0);
            for (HabiWorldEntrySession.StageDef stg : HabiWorldEntrySession.WORLD_STAGES) {
                addPrebakedText(dst, pg, FontFace.SANS_CN, stg.zh(), 13, 3.5f, 0);
            }
            for (String phaseCap : new String[]{
                    "苹果沿切缝自上而下分离",
                    "车窗带展开   ·   装配一号车厢",
                    "车门开启   ·   请上车",
                    "列车发车   ·   驶入星夜轨网"
            }) {
                addPrebakedText(dst, pg, FontFace.SANS_CN, phaseCap, 12, 4.5f, 0);
            }
            for (String tip : new String[]{
                    "一号车厢已检票发车，正沿星夜轨网穿越维度隧道。",
                    "深空中自由浮掠的齿孔邮票记录着列车各站台的乘车凭证与职能印记。",
                    "正在同步出生点区块与世界光照，即将按 100 BPM 拍点丝滑着陆。",
                    "已检票的登车凭证将随苹果视窗光幕展开，无缝驶入真实世界。"
            }) {
                addPrebakedText(dst, pg, FontFace.SANS_CN, tip, 11, 2.6f, 0);
            }
            for (String ch : new String[]{"星", "夜", "轨", "网", "开", "门", "登", "驶", "入"}) {
                addPrebakedText(dst, pg, FontFace.SANS_CN, ch, 18, 0f, 0);
            }
        } finally {
            pg.dispose();
        }
    }

    private void addPrebakedText(List<CpuPrebakedText> dst, Graphics2D probeG,
                                 FontFace face, String text, int sizePx, float letterSpacing, int maxWrapW) {
        if (text == null || text.isEmpty()) return;
        Font base = switch (face) {
            case DISPLAY -> fontDisplay;
            case SANS_CN -> fontSansCn;
            case MONO -> fontMono;
        };
        Font f = base.deriveFont(face == FontFace.DISPLAY ? Font.PLAIN : Font.BOLD, (float) sizePx);
        FontMetrics fm = probeG.getFontMetrics(f);
        CpuPrebakedText entry = bakeCpuText(makeTextCacheKey(face, text, sizePx, letterSpacing, maxWrapW),
                f, fm, text, sizePx, letterSpacing, maxWrapW);
        dst.add(entry);
    }

    private static CpuPrebakedText bakeCpuText(String key, Font f, FontMetrics fm,
                                               String text, int sizePx, float letterSpacing, int maxWrapW) {
        List<String> lines = maxWrapW > 0 ? wrapText(fm, text, letterSpacing, maxWrapW) : List.of(text);
        double maxW = 1.0;
        for (String line : lines) {
            maxW = Math.max(maxW, measureSpaced(fm, line, letterSpacing));
        }
        int lineH = Math.max(sizePx + 4, (int) Math.ceil((fm.getAscent() + fm.getDescent()) * 1.28));
        int logW = Math.max(2, (int) Math.ceil(maxW + 8));
        int logH = Math.max(2, lines.size() == 1 ? (fm.getAscent() + fm.getDescent() + 8) : (lines.size() * lineH + 6));
        int ascent = fm.getAscent();

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
        return new CpuPrebakedText(key, CpuImage.fromBufferedImage(img, false), logW, logH);
    }

    private static String makeTextCacheKey(FontFace face, String text, int sizePx, float letterSpacing, int maxWrapW) {
        return face.ordinal() + "|" + sizePx + "|" + Math.round(letterSpacing * 10f) + "|" + maxWrapW + "|" + text;
    }

    private void drawBlueprintOutline(Graphics2D g, Color color) {
        g.translate(180, 180);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{7f, 6f}, 0f));
        g.draw(createApplePath());
    }

    private CpuSlab bakeCpuTrainCar(int w, int h, boolean lead, Color body, Color roof, Color win, int sideRgb) {
        return bakeCpuSlab(w, h, w * 0.5, h * 0.5, sideRgb, 0, 0, 0, w, 0, false, true, g -> {
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

    private CpuSlab bakeCpuTag(String text, Font font, float ls, int padX, int h,
                               Color bg, Color fg, int sideRgb, String cut) {
        BufferedImage probe = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        pg.setFont(font);
        double tw = measureSpaced(pg, text, ls);
        pg.dispose();

        final int w = (int) Math.ceil(tw + padX * 2);
        return bakeCpuSlab(w, h, w * 0.5, h * 0.5, sideRgb, 0, 0, 0, w, 0, false, true, g -> {
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

    private CpuSlab bakeCpuSlab(int w, int h, double ax, double ay, int sideRgb,
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

        CpuImage faceCpu = CpuImage.fromBufferedImage(faceImg, false);
        CpuImage maskCpu = makeMask ? CpuImage.silhouetteFrom(faceCpu, 0xFFFFFF) : null;
        return new CpuSlab(faceCpu, maskCpu, w, h, ax, ay, sideRgb, yc, y0, y1, adv, titleX, plate);
    }

    private CpuImage bakeCpuTexture(int w, int h, float scale, Consumer<Graphics2D> painter) {
        int pw = Math.max(1, Math.round(w * scale));
        int ph = Math.max(1, Math.round(h * scale));
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        setupHints(g);
        g.scale(scale, scale);
        painter.accept(g);
        g.dispose();
        return CpuImage.fromBufferedImage(img, false);
    }

    // ── Zero-Allocation ASCII Glyph Atlas Rendering ──────────────────────────

    private CpuAsciiAtlas bakeCpuAsciiAtlas(Font baseFont, float fontPx) {
        Font derived = baseFont.deriveFont(baseFont == fontDisplay ? Font.PLAIN : Font.BOLD, fontPx);
        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        pg.setFont(derived);
        FontMetrics fm = pg.getFontMetrics();
        float asc = fm.getAscent();
        float desc = fm.getDescent();
        float padX = 4f;
        int cellH = Math.max(8, (int) Math.ceil(asc + desc + 10f));
        float[] advs = new float[128];
        int[] widths = new int[128];
        int maxRowW = fontPx >= 64f ? 1024 : 512;
        int curX = 0, curY = 0, usedW = 16;
        int[] gx = new int[128];
        int[] gy = new int[128];
        for (int ch = 32; ch <= 126; ch++) {
            float adv = fm.stringWidth(String.valueOf((char) ch));
            int gw = Math.max(8, (int) Math.ceil(adv + padX * 2f + 2f));
            advs[ch] = adv;
            widths[ch] = gw;
            if (curX + gw > maxRowW && curX > 0) {
                curX = 0;
                curY += cellH;
            }
            gx[ch] = curX;
            gy[ch] = curY;
            curX += gw;
            if (curX > usedW) usedW = curX;
        }
        pg.dispose();

        int texW = Math.max(16, usedW);
        int texH = Math.max(16, curY + cellH);
        BufferedImage img = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        setupHints(g);
        g.setFont(derived);
        g.setColor(Color.WHITE);

        GlyphCell[] cells = new GlyphCell[128];
        float gh = asc + desc + 8f;
        for (int ch = 32; ch <= 126; ch++) {
            int cx = gx[ch];
            int cy = gy[ch];
            float adv = advs[ch];
            float gw = Math.max(adv + padX * 2f, 8f);
            g.drawString(String.valueOf((char) ch), cx + padX, cy + asc + 4f);
            cells[ch] = new GlyphCell(
                    cx / (float) texW,
                    cy / (float) texH,
                    (cx + gw) / (float) texW,
                    (cy + gh) / (float) texH,
                    adv, gw, gh
            );
        }
        g.dispose();
        return new CpuAsciiAtlas(CpuImage.fromBufferedImage(img, false), fontPx, asc, desc, cells);
    }

    /** Truncates an ASCII string cleanly with "..." using the pre-baked glyph atlas (zero GPU uploads). */
    public String truncateAscii(FontFace face, String text, double sizePx, double letterSpacing, double maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0.0) return "";
        if (measureAscii(face, text, sizePx, letterSpacing) <= maxWidth) return text;
        String ellipsis = "...";
        double ellW = measureAscii(face, ellipsis, sizePx, letterSpacing);
        if (ellW >= maxWidth) return ellipsis;
        int end = text.length();
        while (end > 0 && measureAscii(face, text.substring(0, end), sizePx, letterSpacing) + ellW > maxWidth) {
            end--;
        }
        return end <= 0 ? ellipsis : text.substring(0, end).stripTrailing() + ellipsis;
    }

    /** Truncates any CJK/mixed string cleanly with "..." using cached Java2D FontMetrics (zero GPU uploads). */
    public String truncateText(FontFace face, String text, int sizePx, float letterSpacing, double maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0.0) return "";
        FontMetrics fm = getCachedFontMetrics(face, sizePx);
        if (fm == null) return text;
        if (measureSpaced(fm, text, letterSpacing) <= maxWidth) return text;
        String ellipsis = "...";
        double ellW = measureSpaced(fm, ellipsis, letterSpacing);
        if (ellW >= maxWidth) return ellipsis;
        double acc = 0.0;
        int cut = 0;
        for (int i = 0; i < text.length(); i++) {
            double cw = fm.charWidth(text.charAt(i)) + (i > 0 ? letterSpacing : 0.0);
            if (acc + cw + ellW > maxWidth) break;
            acc += cw;
            cut = i + 1;
        }
        return cut <= 0 ? ellipsis : text.substring(0, cut).stripTrailing() + ellipsis;
    }

    private FontMetrics getCachedFontMetrics(FontFace face, int sizePx) {
        long key = (((long) face.ordinal()) << 32) | (sizePx & 0xFFFFFFFFL);
        FontMetrics cached = metricsCache.get(key);
        if (cached != null) return cached;
        ensureFontsLoaded();
        Font base = switch (face) {
            case DISPLAY -> fontDisplay;
            case SANS_CN -> fontSansCn;
            case MONO -> fontMono;
        };
        if (base == null) return null;
        Font f = base.deriveFont(face == FontFace.DISPLAY ? Font.PLAIN : Font.BOLD, (float) sizePx);
        BufferedImage probe = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        setupHints(pg);
        pg.setFont(f);
        FontMetrics fm = pg.getFontMetrics();
        pg.dispose();
        metricsCache.put(key, fm);
        return fm;
    }

    private static double measureSpaced(FontMetrics fm, String text, float ls) {
        if (Math.abs(ls) < 0.01f) return fm.stringWidth(text);
        double w = 0.0;
        for (int i = 0; i < text.length(); i++) {
            w += fm.charWidth(text.charAt(i));
            if (i + 1 < text.length()) w += ls;
        }
        return w;
    }

    /** Measures ASCII string width in virtual pixels using the pre-baked glyph atlas. */
    public double measureAscii(FontFace face, String text, double sizePx, double letterSpacing) {
        if (text == null || text.isEmpty()) return 0.0;
        AsciiAtlas atlas = (face == FontFace.DISPLAY) ? displayAtlas : monoAtlas;
        if (atlas == null) return 0.0;
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
     * Draws an ASCII string directly from the pre-baked atlas (0 allocations, coalesced into
     * {@link HabiKineticGfx}'s active textured-quad batch).
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
        float z = gfx.currentDrawZ();

        BufferBuilder b = gfx.acquireTexturedQuads(atlas.tex.getId(), HabiKineticGfx.BlendMode.NORMAL);
        double penX = startX;
        int added = 0;
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

                b.addVertex((float) sx0, (float) sy0, z).setUv(cell.u0(), cell.v0()).setColor(argb);
                b.addVertex((float) sx1, (float) sy1, z).setUv(cell.u0(), cell.v1()).setColor(argb);
                b.addVertex((float) sx2, (float) sy2, z).setUv(cell.u1(), cell.v1()).setColor(argb);
                b.addVertex((float) sx3, (float) sy3, z).setUv(cell.u1(), cell.v0()).setColor(argb);
                added++;
            }
            penX += cell.adv() * scale + letterSpacing;
        }
        gfx.noteQuadsAdded(added);
    }

    /** Draws an ASCII string projected onto a 3D plane (O, M), coalesced into the active batch. */
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
        float z = gfx.currentDrawZ();

        BufferBuilder b = gfx.acquireTexturedQuads(atlas.tex.getId(), HabiKineticGfx.BlendMode.NORMAL);
        double penX = startX;
        int added = 0;
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
                    b.addVertex((float) p0[0], (float) p0[1], z).setUv(cell.u0(), cell.v0()).setColor(argb);
                    b.addVertex((float) p1[0], (float) p1[1], z).setUv(cell.u0(), cell.v1()).setColor(argb);
                    b.addVertex((float) p2[0], (float) p2[1], z).setUv(cell.u1(), cell.v1()).setColor(argb);
                    b.addVertex((float) p3[0], (float) p3[1], z).setUv(cell.u1(), cell.v0()).setColor(argb);
                    added++;
                }
            }
            penX += cell.adv() * scale + letterSpacing;
        }
        gfx.noteQuadsAdded(added);
    }

    // ── LRU Dynamic Text Rendering (CJK & Mixed Typography) ──────────────────

    /** Gets or rasterizes a white-tinted crisp text texture (color applied via OpenGL tint!). */
    public TextTex getCachedText(FontFace face, String text, int sizePx, float letterSpacing, int maxWrapW) {
        if (shuttingDown || text == null || text.isEmpty()) return new TextTex(null, 0, 0);
        String key = makeTextCacheKey(face, text, sizePx, letterSpacing, maxWrapW);
        TextTex cached = textCache.get(key);
        if (cached != null) return cached;

        long startNanos = System.nanoTime();
        ensureFontsLoaded();
        Font base = switch (face) {
            case DISPLAY -> fontDisplay;
            case SANS_CN -> fontSansCn;
            case MONO -> fontMono;
        };
        Font f = base.deriveFont(face == FontFace.DISPLAY ? Font.PLAIN : Font.BOLD, (float) sizePx);
        FontMetrics fm = getCachedFontMetrics(face, sizePx);

        CpuPrebakedText baked = bakeCpuText(key, f, fm, text, sizePx, letterSpacing, maxWrapW);
        StaticGlTexture dt = uploadCpuImage(baked.image);
        TextTex tt = new TextTex(dt, baked.w, baked.h);
        textCache.put(key, tt);
        if (textCache.size() > MAX_TEXT_CACHE) {
            Iterator<Map.Entry<String, TextTex>> it = textCache.entrySet().iterator();
            if (it.hasNext()) {
                TextTex evicted = it.next().getValue();
                it.remove();
                if (evicted.tex() != null) evicted.tex().close();
            }
        }
        HabiLoadingPerfMonitor.getInstance().recordDynamicTextBake((System.nanoTime() - startNanos) / 1_000_000.0);
        return tt;
    }

    /**
     * Draws a cached CJK/mixed string (align: -1 left, 0 center, 1 right; {@code y} is vertical center
     * for single line, or top-aligned when {@code maxWrapW > 0}).
     */
    public TextTex drawText(HabiKineticGfx gfx, FontFace face, String text,
                            double x, double y, int sizePx, float letterSpacing,
                            int argb, int align, double angleRad, int maxWrapW) {
        return drawTextScaled(gfx, face, text, x, y, sizePx, letterSpacing, 1.0, argb, align, angleRad, maxWrapW);
    }

    /**
     * Draws a cached CJK/mixed string rasterized once at a fixed {@code baseSizePx}/{@code baseLetterSpacing}
     * and scaled smoothly on the GPU by {@code scale} — prevents per-frame cache misses on animated text.
     */
    public TextTex drawTextScaled(HabiKineticGfx gfx, FontFace face, String text,
                                  double x, double y, int baseSizePx, float baseLetterSpacing, double scale,
                                  int argb, int align, double angleRad, int maxWrapW) {
        TextTex tt = getCachedText(face, text, baseSizePx, baseLetterSpacing, maxWrapW);
        if (tt.texId() <= 0 || (argb >>> 24) < 2 || scale <= 1e-4) return tt;
        double drawW = tt.w() * scale;
        double drawH = tt.h() * scale;
        double dx = align < 0 ? x : (align == 0 ? x - drawW * 0.5 : x - drawW);
        double dy = maxWrapW > 0 ? y : (y - drawH * 0.5);
        if (Math.abs(angleRad) < 1e-4) {
            gfx.drawTexturedRect(tt.texId(), dx, dy, drawW, drawH, argb, HabiKineticGfx.BlendMode.NORMAL);
        } else {
            double cx = dx + drawW * 0.5;
            double cy = dy + drawH * 0.5;
            double rx = cx - x, ry = cy - y;
            double c = Math.cos(angleRad), s = Math.sin(angleRad);
            gfx.drawTexturedRectRotated(tt.texId(), x + rx * c - ry * s, y + rx * s + ry * c,
                    drawW, drawH, angleRad, argb, HabiKineticGfx.BlendMode.NORMAL);
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

    Font getFont(FontFace face) {
        ensureFontsLoaded();
        return switch (face) {
            case DISPLAY -> fontDisplay;
            case SANS_CN -> fontSansCn;
            case MONO -> fontMono;
        };
    }

    static StaticGlTexture uploadUnmanaged(BufferedImage img, boolean repeat) {
        return uploadCpuImage(CpuImage.fromBufferedImage(img, repeat));
    }

    static void setupHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    static double measureSpaced(Graphics2D g, String text, float ls) {
        return measureSpaced(g.getFontMetrics(), text, ls);
    }

    static void drawSpaced(Graphics2D g, String text, float x, float baselineY, float ls) {
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

    private static List<String> wrapText(FontMetrics fm, String text, float ls, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        double curW = 0.0;
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

    public void shutdown() {
        shuttingDown = true;
        dispose();
    }

    public void dispose() {
        generation.incrementAndGet();
        synchronized (preloadLock) {
            preloadStartedGen = -1;
            if (zeroCpuFuture != null) {
                zeroCpuFuture.cancel(false);
                zeroCpuFuture = null;
            }
            if (st01CpuFuture != null) {
                st01CpuFuture.cancel(false);
                st01CpuFuture = null;
            }
        }
        uploadingSt01Pack = null;
        uploadingSt01Cursor = 0;

        for (StaticGlTexture dt : ownedTextures) {
            if (dt != null) dt.close();
        }
        ownedTextures.clear();
        for (TextTex tt : textCache.values()) {
            if (tt.tex() != null) tt.tex().close();
        }
        textCache.clear();
        metricsCache.clear();
        Arrays.fill(zeroBands, null);
        Arrays.fill(stBands, null);
        titleGlyphs.clear();
        zeroStem = zeroLeaf = tagLead = tagCore = tagPrompt = tagAutoBoard = tagSyncBoard = tagDepart = null;
        loadBadge = cursor = zeroCarLead = zeroCar = cardA = cardB = cardC = cardD = null;
        stStem = stLeaf = stCarLead = stCar = plateStation = plateRoute = null;
        zeroOutlineDark = zeroOutlinePaper = noiseTex = vigPaperTex = vigDarkTex = stationBgTex = stationAuraTex = null;
        monoAtlas = null;
        displayAtlas = null;
        zeroReady = false;
        station01Ready = false;
        releaseUploadScratch();
    }
}
