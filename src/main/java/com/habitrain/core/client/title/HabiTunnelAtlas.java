package com.habitrain.core.client.title;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

import static com.habitrain.core.client.title.HabiKineticGfx.TAU;
import static com.habitrain.core.client.title.HabiKineticGfx.hash;
import static com.habitrain.core.client.title.HabiKineticGfx.lerp;
import static com.habitrain.core.client.title.HabiKineticGfx.lerpRgb;

/**
 * High-DPI vector texture atlas for the HabiTrain 3-act World-Entry Cutscene ({@code START},
 * {@code LOADING}, {@code END}), faithfully porting {@code stamp_assets.js} (12 perforated stamps
 * with 13 3D brass WireModels icons + 4 guilloche boarding passes) and Car 01 / tunnel sprites.
 *
 * <p>Uses a two-tier asynchronous pipeline:
 * <ul>
 *   <li><b>Tier A</b> (17 glow, bokeh, vignette, and Car 01 detail textures): rasterized first on
 *       a background thread while the player is on Station 01, uploaded in ~1–2 ms on Act 1 frame 0.</li>
 *   <li><b>Tier B</b> (12 perforated stamps + 4 guilloche tickets = 32 high-DPI textures): rasterized
 *       in the background right after Tier A and uploaded incrementally on the render thread within
 *       a small per-frame budget (~1.5 ms / up to 2 items per frame) while Act 1 plays before the
 *       tunnel doorway opens.</li>
 * </ul>
 * Every CPU pixel buffer is discarded immediately after GPU upload, and all GPU textures are
 * released when the world-entry cutscene completes.</p>
 */
@Environment(EnvType.CLIENT)
public final class HabiTunnelAtlas {

    private static final float BAKE_SCALE = 2.0f;

    private static final int PAPER_TOP = 0xF6EEDB;
    private static final int PAPER_BOTTOM = 0xE3D4B4;
    private static final Color C_PAPER_TOP = new Color(PAPER_TOP);
    private static final Color C_PAPER_BOT = new Color(PAPER_BOTTOM);
    private static final Color C_INK_LIGHT = new Color(0xF8EEDA);
    private static final Color C_POSTMARK = new Color(0xF1E7CF);
    private static final Color C_BRASS_LIGHT = new Color(0xF4D894);
    private static final Color C_BRASS = new Color(0xD0A658);
    private static final Color C_CHAMPAGNE = new Color(0xFFF4DA);

    private record StampDef(String id, String num, String label, int inkTop, int inkBot,
                            String icon, boolean op, boolean postmark) {}

    private record TicketDef(String id, String title, String sub, String dest, String seat,
                             String serial, String stamped, String stampSub,
                             int paperTop, int paperBot, int band, int bandInk,
                             int ink, int inkSoft, int trim, Color guilloche, int serialCol) {}

    public record StampTex(
            String id,
            String kind,
            String label,
            String num,
            HabiKineticAtlas.StaticGlTexture faceTex,
            HabiKineticAtlas.StaticGlTexture sideTex,
            double w,
            double h,
            double ax,
            double ay
    ) {
        public int faceTexId() { return faceTex != null ? faceTex.getId() : 0; }
        public int sideTexId() { return sideTex != null ? sideTex.getId() : 0; }
    }

    private static final class CpuTierA {
        HabiKineticAtlas.CpuImage softGlowTex;
        HabiKineticAtlas.CpuImage hardGlowTex;
        HabiKineticAtlas.CpuImage anamorphicTex;
        final HabiKineticAtlas.CpuImage[] bokehTex = new HabiKineticAtlas.CpuImage[3];
        HabiKineticAtlas.CpuImage tunnelVigTex;
        HabiKineticAtlas.CpuImage startVigTex;
        HabiKineticAtlas.CpuImage stationAuraTex;
        HabiKineticAtlas.CpuImage carWinUnlitTex;
        HabiKineticAtlas.CpuImage carWinLitTex;
        HabiKineticAtlas.CpuImage doorWinUnlitTex;
        HabiKineticAtlas.CpuImage doorWinLitTex;
        HabiKineticAtlas.CpuImage carPlaqueTex;
        HabiKineticAtlas.CpuImage carBrandTex;
        HabiKineticAtlas.CpuImage stationSignTex;
        HabiKineticAtlas.CpuImage gangwayTex;
    }

    private static final class CpuFloatingItem {
        final String id, kind, label, num;
        HabiKineticAtlas.CpuImage face;
        HabiKineticAtlas.CpuImage side;
        final double w, h, ax, ay;

        CpuFloatingItem(String id, String kind, String label, String num,
                        HabiKineticAtlas.CpuImage face, HabiKineticAtlas.CpuImage side,
                        double w, double h, double ax, double ay) {
            this.id = id;
            this.kind = kind;
            this.label = label;
            this.num = num;
            this.face = face;
            this.side = side;
            this.w = w;
            this.h = h;
            this.ax = ax;
            this.ay = ay;
        }
    }

    private static final StampDef[] STAMP_DEFS = {
            new StampDef("introduction",  "01", "职业介绍",   0x83283C, 0x4A1322, "TOP_HAT",    false, true),
            new StampDef("map_intro",     "02", "地图介绍",   0x2F5394, 0x16294F, "GLOBE",      false, false),
            new StampDef("records",       "03", "对局记录",   0xA3712A, 0x5E3F10, "TROPHY",     false, true),
            new StampDef("skin_manage",   "04", "皮肤管理",   0x74408A, 0x3D1F48, "DRESS_FORM", false, false),
            new StampDef("map_rotation",  "05", "地图轮换",   0x237473, 0x0F3D3E, "TURNTABLE",  true,  true),
            new StampDef("backpack",      "06", "背包",       0x86522F, 0x472812, "SUITCASE",   false, false),
            new StampDef("task_settings", "07", "任务点设置", 0x465672, 0x222A38, "GEAR",       true,  false),
            new StampDef("game_menu",     "08", "游戏管理",   0xB0412F, 0x651D14, "STOPWATCH",  true,  true),
            new StampDef("roster",        "09", "职业轮换",   0x2A7650, 0x113D29, "CARDS",      true,  false),
            new StampDef("role_guess",    "10", "职业猜测",   0x424A98, 0x1E2152, "MAGNIFIER",  false, true),
            new StampDef("loot_screen",   "11", "打开抽卡",   0xA3712A, 0x5E3F10, "GIFT",       false, false),
            new StampDef("mail_manage",   "12", "邮箱管理",   0x2F5394, 0x16294F, "ENVELOPE",   false, true)
    };

    private static final TicketDef[] TICKET_DEFS = {
            new TicketDef("ivory_pass", "哈比列车", "BOARDING PASS", "苹果园站台", "03车 · 06D座",
                    "008406", "对局开始", "MATCH START",
                    0xF8EED3, 0xE9D7AD, 0x1D2A52, 0xF4D894, 0x2A2114, 0x7C6849, 0xB38D47,
                    new Color(168, 136, 72, 71), 0xB3362C),
            new TicketDef("sapphire_pass", "哈比列车", "HABI EXPRESS", "星夜快车", "01车 · 12A座",
                    "143501", "已检票", "CHECKED",
                    0x243D78, 0x132352, 0x0A1330, 0xF4D894, 0xF3EAD3, 0xA3B4DA, 0xD0A658,
                    new Color(143, 176, 255, 51), 0xFF8A80),
            new TicketDef("burgundy_pass", "哈比列车", "HABI EXPRESS", "午夜餐车", "05车 · 08B座",
                    "202611", "已检票", "CHECKED",
                    0x6E2438, 0x471527, 0x260912, 0xF4D894, 0xF8EADA, 0xDDAEB6, 0xD8B064,
                    new Color(255, 180, 196, 56), 0xF4D894),
            new TicketDef("emerald_pass", "哈比列车", "HABI EXPRESS", "林间终点站", "02车 · 04C座",
                    "092418", null, null,
                    0x1F5E4F, 0x113A30, 0x071E18, 0xF4D894, 0xF0F2E2, 0xA3CDBC, 0xD0A658,
                    new Color(156, 255, 216, 51), 0xFF9C88)
    };

    private static final int TOTAL_FLOATING = STAMP_DEFS.length + TICKET_DEFS.length;
    private static final HabiTunnelAtlas INSTANCE = new HabiTunnelAtlas();

    public static HabiTunnelAtlas getInstance() {
        return INSTANCE;
    }

    private final Object preloadLock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);
    private int preloadStartedGen = -1;

    private volatile boolean tierAReady;
    private volatile boolean ready;
    private volatile boolean shuttingDown;
    private int uploadedFloatingCount;

    private @Nullable CompletableFuture<CpuTierA> tierACpuFuture;
    private @Nullable CompletableFuture<Void> tierBCpuFuture;
    private final AtomicReferenceArray<CpuFloatingItem> pendingFloating = new AtomicReferenceArray<>(TOTAL_FLOATING);

    private final List<HabiKineticAtlas.StaticGlTexture> owned = new ArrayList<>();

    // ── Stamps & Boarding Passes ─────────────────────────────────────────────
    public final StampTex[] stamps = new StampTex[STAMP_DEFS.length];
    public final StampTex[] tickets = new StampTex[TICKET_DEFS.length];

    // ── Atmospheric / Glow / Bokeh / Vignette Textures ───────────────────────
    public HabiKineticAtlas.StaticGlTexture softGlowTex;
    public HabiKineticAtlas.StaticGlTexture hardGlowTex;
    public HabiKineticAtlas.StaticGlTexture anamorphicTex;
    public final HabiKineticAtlas.StaticGlTexture[] bokehTex = new HabiKineticAtlas.StaticGlTexture[3];
    public HabiKineticAtlas.StaticGlTexture tunnelVigTex;
    public HabiKineticAtlas.StaticGlTexture startVigTex;
    public HabiKineticAtlas.StaticGlTexture stationAuraTex;

    // ── Car 01 & Station Act 1 High-DPI Detail Sprites ───────────────────────
    public HabiKineticAtlas.StaticGlTexture carWinUnlitTex;
    public HabiKineticAtlas.StaticGlTexture carWinLitTex;
    public HabiKineticAtlas.StaticGlTexture doorWinUnlitTex;
    public HabiKineticAtlas.StaticGlTexture doorWinLitTex;
    public HabiKineticAtlas.StaticGlTexture carPlaqueTex;
    public HabiKineticAtlas.StaticGlTexture carBrandTex;
    public HabiKineticAtlas.StaticGlTexture stationSignTex;
    public HabiKineticAtlas.StaticGlTexture gangwayTex;

    private HabiTunnelAtlas() {}

    public boolean isReady() {
        return ready;
    }

    public boolean isTierAReady() {
        return tierAReady;
    }

    public boolean hasAnyFloatingAssets() {
        return uploadedFloatingCount > 0;
    }

    /**
     * Starts off-thread CPU rasterization of Tier A (glows + Car 01) followed by Tier B (12 stamps
     * + 4 tickets). Safe to call early while the player is on Station 01 so world entry frame 0
     * never blocks on Java2D rasterization.
     */
    public void preloadAsync(HabiKineticAtlas baseAtlas) {
        if (shuttingDown || ready) return;
        final int gen;
        final CompletableFuture<CpuTierA> aFuture;
        final CompletableFuture<Void> bFuture;
        synchronized (preloadLock) {
            gen = generation.get();
            if (preloadStartedGen == gen) {
                return;
            }
            preloadStartedGen = gen;
            aFuture = new CompletableFuture<>();
            bFuture = new CompletableFuture<>();
            tierACpuFuture = aFuture;
            tierBCpuFuture = bFuture;
            for (int i = 0; i < TOTAL_FLOATING; i++) {
                pendingFloating.set(i, null);
            }
        }

        Thread baker = new Thread(() -> {
            try {
                baseAtlas.ensureFontsLoaded();
                if (generation.get() != gen) {
                    aFuture.cancel(false);
                    bFuture.cancel(false);
                    return;
                }
                Font fontDisplay = baseAtlas.getFont(HabiKineticAtlas.FontFace.DISPLAY);
                Font fontSansCn = baseAtlas.getFont(HabiKineticAtlas.FontFace.SANS_CN);
                Font fontMono = baseAtlas.getFont(HabiKineticAtlas.FontFace.MONO);

                long t0 = System.nanoTime();
                CpuTierA tierA = bakeTierACpu(fontDisplay, fontSansCn, fontMono);
                double tierAMs = (System.nanoTime() - t0) / 1_000_000.0;
                HabiLoadingPerfMonitor.getInstance().recordAtlasCpuBake("TunnelAtlas", "tierA", tierAMs, true);
                aFuture.complete(tierA);

                long t1 = System.nanoTime();
                for (int i = 0; i < STAMP_DEFS.length; i++) {
                    if (generation.get() != gen) {
                        bFuture.cancel(false);
                        return;
                    }
                    CpuFloatingItem item = buildStampCpu(STAMP_DEFS[i], i, fontSansCn, fontMono);
                    if (generation.get() == gen) {
                        pendingFloating.set(i, item);
                    }
                }
                for (int i = 0; i < TICKET_DEFS.length; i++) {
                    if (generation.get() != gen) {
                        bFuture.cancel(false);
                        return;
                    }
                    CpuFloatingItem item = buildTicketCpu(TICKET_DEFS[i], i, fontSansCn, fontMono);
                    if (generation.get() == gen) {
                        pendingFloating.set(STAMP_DEFS.length + i, item);
                    }
                }
                double tierBMs = (System.nanoTime() - t1) / 1_000_000.0;
                HabiLoadingPerfMonitor.getInstance().recordAtlasCpuBake("TunnelAtlas", "tierB", tierBMs, true);
                bFuture.complete(null);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[HabiTunnelAtlas] Background CPU rasterization failed", t);
                aFuture.completeExceptionally(t);
                bFuture.completeExceptionally(t);
            }
        }, "habitrain-tunnel-atlas-baker");
        baker.setDaemon(true);
        baker.setPriority(Thread.NORM_PRIORITY - 1);
        baker.start();
    }

    /**
     * Ensures Tier A (glows/bokeh/vignettes/Car 01) is uploaded on the render thread, then uploads
     * up to {@code maxItemsPerFrame} ready Tier B floating stamps/tickets within {@code tierBBudgetMs}.
     *
     * @return total milliseconds spent on the render thread (upload + any synchronous wait)
     */
    public double ensureTierAAndPumpTierB(HabiKineticAtlas baseAtlas, double tierBBudgetMs, int maxItemsPerFrame) {
        if (shuttingDown) return 0.0;
        double totalMs = 0.0;
        if (!tierAReady) {
            preloadAsync(baseAtlas);
            CompletableFuture<CpuTierA> future;
            synchronized (preloadLock) {
                future = tierACpuFuture;
            }
            CpuTierA tierA = null;
            double syncWaitMs = 0.0;
            if (future != null) {
                boolean doneAlready = future.isDone();
                long waitStart = doneAlready ? 0L : System.nanoTime();
                try {
                    tierA = future.join();
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.warn("[HabiTunnelAtlas] Falling back to synchronous Tier A bake", t);
                }
                if (!doneAlready) {
                    syncWaitMs = (System.nanoTime() - waitStart) / 1_000_000.0;
                }
            }
            if (tierA == null) {
                long cpuStart = System.nanoTime();
                baseAtlas.ensureFontsLoaded();
                tierA = bakeTierACpu(
                        baseAtlas.getFont(HabiKineticAtlas.FontFace.DISPLAY),
                        baseAtlas.getFont(HabiKineticAtlas.FontFace.SANS_CN),
                        baseAtlas.getFont(HabiKineticAtlas.FontFace.MONO)
                );
                HabiLoadingPerfMonitor.getInstance().recordAtlasCpuBake(
                        "TunnelAtlas", "tierA", (System.nanoTime() - cpuStart) / 1_000_000.0, false);
            }

            long upStart = System.nanoTime();
            int uploaded = uploadTierA(tierA);
            double upMs = (System.nanoTime() - upStart) / 1_000_000.0;
            synchronized (preloadLock) {
                tierACpuFuture = null;
            }
            tierAReady = true;
            HabiLoadingPerfMonitor.getInstance().recordAtlasUpload("TunnelAtlas", "tierA", uploaded, upMs, syncWaitMs);
            totalMs += upMs + syncWaitMs;
        }

        if (!ready) {
            totalMs += pumpReadyTierB(tierBBudgetMs, maxItemsPerFrame);
        }
        return totalMs;
    }

    /** Backward-compatible synchronous preparation of both Tier A and Tier B. */
    public void ensurePrepared(HabiKineticAtlas baseAtlas) {
        if (ready) return;
        baseAtlas.ensureStation01Prepared();
        ensureTierAAndPumpTierB(baseAtlas, Double.POSITIVE_INFINITY, TOTAL_FLOATING);
        if (!ready) {
            CompletableFuture<Void> bFuture;
            synchronized (preloadLock) {
                bFuture = tierBCpuFuture;
            }
            if (bFuture != null) {
                try {
                    bFuture.join();
                } catch (Throwable ignored) {
                }
            }
            pumpReadyTierB(Double.POSITIVE_INFINITY, TOTAL_FLOATING);
        }
    }

    /** Package-private headless CPU baking entry point for unit testing & benchmarking. */
    void prebakeCpuOnlyForTest(HabiKineticAtlas baseAtlas) {
        baseAtlas.ensureFontsLoaded();
        Font fontDisplay = baseAtlas.getFont(HabiKineticAtlas.FontFace.DISPLAY);
        Font fontSansCn = baseAtlas.getFont(HabiKineticAtlas.FontFace.SANS_CN);
        Font fontMono = baseAtlas.getFont(HabiKineticAtlas.FontFace.MONO);
        bakeTierACpu(fontDisplay, fontSansCn, fontMono);
        for (int i = 0; i < STAMP_DEFS.length; i++) {
            buildStampCpu(STAMP_DEFS[i], i, fontSansCn, fontMono);
        }
        for (int i = 0; i < TICKET_DEFS.length; i++) {
            buildTicketCpu(TICKET_DEFS[i], i, fontSansCn, fontMono);
        }
    }

    private int uploadTierA(CpuTierA a) {
        int count = 0;
        softGlowTex = uploadOwnedCpu(a.softGlowTex); count++; a.softGlowTex = null;
        hardGlowTex = uploadOwnedCpu(a.hardGlowTex); count++; a.hardGlowTex = null;
        anamorphicTex = uploadOwnedCpu(a.anamorphicTex); count++; a.anamorphicTex = null;
        for (int i = 0; i < 3; i++) {
            bokehTex[i] = uploadOwnedCpu(a.bokehTex[i]); count++; a.bokehTex[i] = null;
        }
        tunnelVigTex = uploadOwnedCpu(a.tunnelVigTex); count++; a.tunnelVigTex = null;
        startVigTex = uploadOwnedCpu(a.startVigTex); count++; a.startVigTex = null;
        stationAuraTex = uploadOwnedCpu(a.stationAuraTex); count++; a.stationAuraTex = null;

        carWinUnlitTex = uploadOwnedCpu(a.carWinUnlitTex); count++; a.carWinUnlitTex = null;
        carWinLitTex = uploadOwnedCpu(a.carWinLitTex); count++; a.carWinLitTex = null;
        doorWinUnlitTex = uploadOwnedCpu(a.doorWinUnlitTex); count++; a.doorWinUnlitTex = null;
        doorWinLitTex = uploadOwnedCpu(a.doorWinLitTex); count++; a.doorWinLitTex = null;
        carPlaqueTex = uploadOwnedCpu(a.carPlaqueTex); count++; a.carPlaqueTex = null;
        carBrandTex = uploadOwnedCpu(a.carBrandTex); count++; a.carBrandTex = null;
        stationSignTex = uploadOwnedCpu(a.stationSignTex); count++; a.stationSignTex = null;
        gangwayTex = uploadOwnedCpu(a.gangwayTex); count++; a.gangwayTex = null;
        return count;
    }

    private double pumpReadyTierB(double budgetMs, int maxItems) {
        if (ready || maxItems <= 0) return 0.0;
        long startNanos = System.nanoTime();
        long deadlineNanos = Double.isInfinite(budgetMs)
                ? Long.MAX_VALUE
                : startNanos + Math.round(Math.max(0.2, budgetMs) * 1_000_000.0);

        int itemsUploaded = 0;
        for (int i = 0; i < TOTAL_FLOATING && itemsUploaded < maxItems; i++) {
            if (itemsUploaded > 0 && System.nanoTime() >= deadlineNanos) {
                break;
            }
            boolean alreadyLoaded = (i < STAMP_DEFS.length)
                    ? (stamps[i] != null)
                    : (tickets[i - STAMP_DEFS.length] != null);
            if (alreadyLoaded) continue;

            CpuFloatingItem cpu = pendingFloating.getAndSet(i, null);
            if (cpu == null) continue;

            HabiKineticAtlas.StaticGlTexture faceTex = uploadOwnedCpu(cpu.face);
            cpu.face = null;
            HabiKineticAtlas.StaticGlTexture sideTex = uploadOwnedCpu(cpu.side);
            cpu.side = null;
            StampTex tex = new StampTex(cpu.id, cpu.kind, cpu.label, cpu.num,
                    faceTex, sideTex, cpu.w, cpu.h, cpu.ax, cpu.ay);
            if (i < STAMP_DEFS.length) {
                stamps[i] = tex;
            } else {
                tickets[i - STAMP_DEFS.length] = tex;
            }
            uploadedFloatingCount++;
            itemsUploaded++;
        }

        if (uploadedFloatingCount >= TOTAL_FLOATING) {
            ready = true;
            synchronized (preloadLock) {
                tierBCpuFuture = null;
            }
        }
        double upMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        if (itemsUploaded > 0) {
            HabiLoadingPerfMonitor.getInstance().recordAtlasUpload(
                    "TunnelAtlas", "tierB", itemsUploaded * 2, upMs, 0.0);
        }
        return upMs;
    }

    public void shutdown() {
        shuttingDown = true;
        dispose();
    }

    public void dispose() {
        generation.incrementAndGet();
        synchronized (preloadLock) {
            preloadStartedGen = -1;
            if (tierACpuFuture != null) {
                tierACpuFuture.cancel(false);
                tierACpuFuture = null;
            }
            if (tierBCpuFuture != null) {
                tierBCpuFuture.cancel(false);
                tierBCpuFuture = null;
            }
        }
        for (int i = 0; i < TOTAL_FLOATING; i++) {
            pendingFloating.set(i, null);
        }
        for (HabiKineticAtlas.StaticGlTexture tex : owned) {
            if (tex != null) tex.close();
        }
        owned.clear();
        Arrays.fill(stamps, null);
        Arrays.fill(tickets, null);
        Arrays.fill(bokehTex, null);
        softGlowTex = hardGlowTex = anamorphicTex = null;
        tunnelVigTex = startVigTex = stationAuraTex = null;
        carWinUnlitTex = carWinLitTex = doorWinUnlitTex = doorWinLitTex = null;
        carPlaqueTex = carBrandTex = stationSignTex = gangwayTex = null;
        uploadedFloatingCount = 0;
        tierAReady = false;
        ready = false;
    }

    // ── Tier A CPU Baking (Glows, Bokeh, Vignettes, Car 01 Details) ──────────

    private CpuTierA bakeTierACpu(Font fontDisplay, Font fontSansCn, Font fontMono) {
        CpuTierA a = new CpuTierA();
        a.softGlowTex = bakeCpuTex(128, 128, 1.0f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(64f, 64f, 62f,
                    new float[]{0.0f, 0.35f, 0.70f, 1.0f},
                    new Color[]{
                            new Color(255, 255, 255, 255),
                            new Color(255, 255, 255, 130),
                            new Color(255, 255, 255, 34),
                            new Color(255, 255, 255, 0)
                    });
            g.setPaint(p);
            g.fillRect(0, 0, 128, 128);
        });

        a.hardGlowTex = bakeCpuTex(128, 128, 1.0f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(64f, 64f, 62f,
                    new float[]{0.0f, 0.22f, 0.55f, 1.0f},
                    new Color[]{
                            new Color(255, 255, 255, 255),
                            new Color(255, 255, 255, 210),
                            new Color(255, 255, 255, 60),
                            new Color(255, 255, 255, 0)
                    });
            g.setPaint(p);
            g.fillRect(0, 0, 128, 128);
        });

        a.anamorphicTex = bakeCpuTex(256, 32, 1.0f, g -> {
            g.scale(1.0, 0.125);
            RadialGradientPaint p = new RadialGradientPaint(128f, 128f, 126f,
                    new float[]{0.0f, 0.40f, 1.0f},
                    new Color[]{
                            new Color(255, 246, 226, 255),
                            new Color(238, 214, 156, 76),
                            new Color(238, 214, 156, 0)
                    });
            g.setPaint(p);
            g.fillRect(0, 0, 256, 256);
        });

        int[][] bokehRgb = {{255, 214, 150}, {242, 53, 75}, {138, 166, 255}};
        for (int i = 0; i < 3; i++) {
            final int r = bokehRgb[i][0], gr = bokehRgb[i][1], b = bokehRgb[i][2];
            a.bokehTex[i] = bakeCpuTex(128, 128, 1.0f, g -> {
                RadialGradientPaint p = new RadialGradientPaint(64f, 64f, 62f,
                        new float[]{0.0f, 0.62f, 0.86f, 0.94f, 1.0f},
                        new Color[]{
                                new Color(r, gr, b, 140),
                                new Color(r, gr, b, 107),
                                new Color(r, gr, b, 158),
                                new Color(r, gr, b, 51),
                                new Color(r, gr, b, 0)
                        });
                g.setPaint(p);
                g.fillRect(0, 0, 128, 128);
            });
        }

        a.tunnelVigTex = bakeCpuTex(512, 288, 1.0f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(256f, 144f, 315f,
                    new float[]{0.0f, 0.38f, 1.0f},
                    new Color[]{new Color(2, 4, 9, 0), new Color(2, 4, 9, 0), new Color(2, 4, 9, 179)});
            g.setPaint(p);
            g.fillRect(0, 0, 512, 288);
        });

        a.startVigTex = bakeCpuTex(512, 288, 1.0f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(256f, 139f, 320f,
                    new float[]{0.0f, 0.33f, 1.0f},
                    new Color[]{new Color(2, 3, 8, 0), new Color(2, 3, 8, 0), new Color(2, 3, 8, 158)});
            g.setPaint(p);
            g.fillRect(0, 0, 512, 288);
        });

        a.stationAuraTex = bakeCpuTex(128, 128, 1.0f, g -> {
            RadialGradientPaint p = new RadialGradientPaint(64f, 64f, 62f,
                    new float[]{0.0f, 0.45f, 1.0f},
                    new Color[]{
                            new Color(225, 36, 59, 180),
                            new Color(214, 178, 110, 72),
                            new Color(0, 0, 0, 0)
                    });
            g.setPaint(p);
            g.fillRect(0, 0, 128, 128);
        });

        // Car 01 high-DPI details
        a.carWinUnlitTex = bakeCarWindowCpu(88, 70, 9.0, 0.0, false);
        a.carWinLitTex = bakeCarWindowCpu(88, 70, 9.0, 1.0, false);
        a.doorWinUnlitTex = bakeCarWindowCpu(30, 92, 6.5, 0.0, true);
        a.doorWinLitTex = bakeCarWindowCpu(30, 92, 6.5, 1.0, true);

        a.carPlaqueTex = bakeCpuTex(76, 71, BAKE_SCALE, g -> {
            double pqX = 3.0, pqY = 3.0, pqW = 68.0, pqH = 63.0, ch = 4.5;
            g.setColor(new Color(28, 4, 10, 133));
            g.fill(chamferRect(pqX + 0.7, pqY + 1.1, pqW + 1.0, pqH + 1.0, ch + 0.3));
            g.setPaint(new LinearGradientPaint(0f, (float) pqY, 0f, (float) (pqY + pqH),
                    new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(0x1A1E2B), new Color(0x11141E), new Color(0x0B0D14)}));
            Shape outer = chamferRect(pqX, pqY, pqW, pqH, ch);
            g.fill(outer);
            g.setColor(new Color(0xD6B067));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(outer);
            g.setColor(new Color(238, 214, 156, 66));
            g.setStroke(new BasicStroke(0.8f));
            g.draw(chamferRect(pqX + 3.2, pqY + 3.2, pqW - 6.4, pqH - 6.4, Math.max(1.0, ch - 1.9)));
            g.setColor(C_BRASS_LIGHT);
            double[][] rivets = {
                    {pqX + 6.5, pqY + 6.5}, {pqX + pqW - 6.5, pqY + 6.5},
                    {pqX + 6.5, pqY + pqH - 6.5}, {pqX + pqW - 6.5, pqY + pqH - 6.5}
            };
            for (double[] rv : rivets) {
                g.fill(new Ellipse2D.Double(rv[0] - 1.0, rv[1] - 1.0, 2.0, 2.0));
            }
            double pqCx = pqX + pqW * 0.5;
            g.setFont(fontMono.deriveFont(Font.BOLD, 8.5f));
            g.setColor(new Color(238, 214, 156, 224));
            drawSpacedCentered(g, "CAR", (float) (pqCx + 1.0), (float) (pqY + 15.0), 2.4f);
            g.setColor(new Color(238, 214, 156, 87));
            g.fillRect((int) Math.round(pqCx - 18), (int) Math.round(pqY + 19.5), 36, 1);
            g.setColor(new Color(0xF2354B));
            g.fill(new RoundRectangle2D.Double(pqCx - 2, pqY + 19.0, 4, 1.9, 0.5, 0.5));
            g.setFont(fontDisplay.deriveFont(Font.PLAIN, 37f));
            g.setColor(new Color(0xF6F3EC));
            drawSpacedCentered(g, "01", (float) (pqCx + 0.5), (float) (pqY + 54.0), 1.2f);
        });

        a.carBrandTex = bakeCpuTex(210, 64, BAKE_SCALE, g -> {
            double bx = 4.0, subY = 14.0, ruleY = 21.0, mainY = 53.0;
            drawDiamond(g, bx + 3.0, subY - 3.5, 3.0, 3.0, C_BRASS_LIGHT);
            g.setFont(fontMono.deriveFont(Font.BOLD, 9.5f));
            g.setColor(new Color(246, 243, 236, 209));
            HabiKineticAtlas.drawSpaced(g, "EXPRESS // 1435", (float) (bx + 11.0), (float) subY, 2.6f);
            g.setPaint(new LinearGradientPaint((float) bx, 0f, (float) (bx + 168.0), 0f,
                    new float[]{0f, 0.75f, 1f},
                    new Color[]{new Color(238, 214, 156, 173), new Color(238, 214, 156, 71), new Color(238, 214, 156, 0)}));
            g.fillRect((int) bx, (int) ruleY, 168, 1);
            g.setFont(fontDisplay.deriveFont(Font.PLAIN, 34f));
            g.setColor(new Color(38, 4, 10, 133));
            HabiKineticAtlas.drawSpaced(g, "HABITRAIN", (float) (bx + 1.2), (float) (mainY + 1.4), 5.2f);
            g.setColor(new Color(0xF6F3EC));
            HabiKineticAtlas.drawSpaced(g, "HABITRAIN", (float) bx, (float) mainY, 5.2f);
        });

        a.stationSignTex = bakeCpuTex(304, 110, BAKE_SCALE, g -> {
            g.setColor(new Color(238, 234, 226, 56));
            g.fillRect(52, 4, 2, 52);
            g.fillRect(250, 4, 2, 52);
            double x0 = 2.0, y0 = 56.0, sw = 300.0, sh = 52.0;
            Shape box = new RoundRectangle2D.Double(x0, y0, sw, sh, 8, 8);
            g.setColor(new Color(0x0E111B));
            g.fill(box);
            g.setColor(new Color(201, 160, 85, 115));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(box);
            g.setColor(new Color(0xE1243B));
            g.fillRect((int) x0, (int) y0, 8, (int) sh);
            g.setFont(fontSansCn.deriveFont(Font.BOLD, 22f));
            g.setColor(new Color(246, 243, 236, 199));
            drawSpacedCenteredMiddle(g, "苹果园站", 156f, (float) (y0 + 22.0), 6.0f);
            g.setFont(fontMono.deriveFont(Font.BOLD, 9f));
            g.setColor(new Color(238, 214, 156, 158));
            drawSpacedCenteredMiddle(g, "ORCHARD // PLATFORM 01", 156f, (float) (y0 + 42.0), 3.0f);
        });

        a.gangwayTex = bakeCpuTex(40, 264, BAKE_SCALE, g -> {
            double gx = 3.0, gyTop = 0.0, gh = 264.0, skirtLocal = 238.0;
            g.setColor(new Color(0x07080D));
            g.fillRect((int) (gx - 1), 4, 36, (int) (gh - 8));
            g.setColor(new Color(0x161924));
            g.fill(new RoundRectangle2D.Double(gx + 1, gyTop, 32, 12, 6, 6));
            g.setColor(new Color(238, 234, 226, 41));
            g.fillRect((int) (gx + 3), 2, 28, 1);

            int folds = 5;
            double fw = 32.0 / folds;
            for (int f = 0; f < folds; f++) {
                double fx = gx + 1.0 + f * fw;
                g.setPaint(new LinearGradientPaint((float) fx, 0f, (float) (fx + fw), 0f,
                        new float[]{0f, 0.42f, 0.62f, 1f},
                        new Color[]{new Color(0x0A0C13), new Color(0x1D2130), new Color(0x252A3B), new Color(0x090B11)}));
                g.fill(new RoundRectangle2D.Double(fx, gyTop + 8, fw - 0.4, gh - 20, 0, 0));
                g.setColor(new Color(238, 214, 156, 23));
                g.fill(new RoundRectangle2D.Double(fx + fw * 0.48, gyTop + 10, 0.9, gh - 24, 0, 0));
            }
            g.setColor(new Color(0x12151F));
            g.fillRect((int) gx, 6, 3, (int) (gh - 14));
            g.fillRect((int) (gx + 31), 6, 3, (int) (gh - 14));
            g.setColor(new Color(0x0D0F16));
            g.fillRect((int) (gx - 2), (int) (skirtLocal - 6), 38, 14);
            g.setColor(new Color(201, 160, 85, 87));
            g.fillRect((int) (gx + 4), (int) (skirtLocal - 5), 26, 1);
        });
        return a;
    }

    private HabiKineticAtlas.CpuImage bakeCarWindowCpu(double ww, double wh, double r,
                                                       double lit, boolean isDoorWin) {
        int pad = 6;
        int cw = (int) Math.ceil(ww + pad * 2);
        int ch = (int) Math.ceil(wh + pad * 2);
        return bakeCpuTex(cw, ch, BAKE_SCALE, g -> {
            double x = cw * 0.5, y = ch * 0.5;
            double bz = 1.0;

            double bpad = isDoorWin ? 1.8 : 2.5;
            g.setColor(new Color(34, 4, 10, 133));
            g.fill(new RoundRectangle2D.Double(x - ww * 0.5 - bpad, y - wh * 0.5 - bpad - 0.5,
                    ww + bpad * 2, wh + bpad * 2 + 0.5, (r + bpad * 0.8) * 2, (r + bpad * 0.8) * 2));
            g.setColor(new Color(255, 228, 212, 71));
            g.fill(new RoundRectangle2D.Double(x - ww * 0.5 + r * 0.6, y + wh * 0.5 + bpad - 0.4,
                    Math.max(0, ww - r * 1.2), 1.1, 0, 0));
            g.setColor(new Color(20, 22, 31, 224));
            g.fill(new RoundRectangle2D.Double(x - ww * 0.5 - 1.3, y - wh * 0.5 - 1.3,
                    ww + 2.6, wh + 2.6, (r + 1.1) * 2, (r + 1.1) * 2));

            Shape winShape = new RoundRectangle2D.Double(x - ww * 0.5, y - wh * 0.5, ww, wh, r * 2, r * 2);
            int cTop = lerpRgb(0xF4F1EA, 0xFFF9EC, lit);
            int cMid = lerpRgb(0xF4F1EA, isDoorWin ? 0xF5D293 : 0xF7D99C, lit);
            int cBot = lerpRgb(0xF4F1EA, isDoorWin ? 0xCE9050 : 0xDDA25C, lit);
            g.setPaint(new LinearGradientPaint(0f, (float) (y - wh * 0.5), 0f, (float) (y + wh * 0.5),
                    new float[]{0f, 0.52f, 1f},
                    new Color[]{new Color(cTop), new Color(cMid), new Color(cBot)}));
            g.fill(winShape);

            Shape prevClip = g.getClip();
            g.clip(winShape);

            if (!isDoorWin && lit > 0.04) {
                double drapeW = ww * 0.12;
                g.setPaint(new GradientPaint((float) (x - ww * 0.5), 0f, new Color(92, 14, 28, 87),
                        (float) (x - ww * 0.5 + drapeW), 0f, new Color(92, 14, 28, 0)));
                g.fillRect((int) Math.floor(x - ww * 0.5), (int) Math.floor(y - wh * 0.5), (int) Math.ceil(drapeW + 1), (int) Math.ceil(wh + 2));
                g.setPaint(new GradientPaint((float) (x + ww * 0.5), 0f, new Color(92, 14, 28, 87),
                        (float) (x + ww * 0.5 - drapeW), 0f, new Color(92, 14, 28, 0)));
                g.fillRect((int) Math.floor(x + ww * 0.5 - drapeW - 1), (int) Math.floor(y - wh * 0.5), (int) Math.ceil(drapeW + 2), (int) Math.ceil(wh + 2));

                double transY = y - wh * 0.23;
                g.setColor(new Color(142, 88, 38, 61));
                g.fill(new RoundRectangle2D.Double(x - ww * 0.5, transY, ww, 1.1, 0, 0));
                g.setColor(new Color(255, 248, 232, 82));
                g.fill(new RoundRectangle2D.Double(x - ww * 0.5, transY + 1.1, ww, 0.8, 0, 0));

                double seatTop = y + wh * 0.05, armTop = y + wh * 0.25, botY = y + wh * 0.5 + 2;
                double backW = ww * 0.19, seatW = ww * 0.30;
                g.setColor(new Color(108, 18, 34, 163));
                Path2D.Double seatL = new Path2D.Double();
                seatL.moveTo(x - ww * 0.5 - 1, seatTop);
                seatL.lineTo(x - ww * 0.5 + backW - 4, seatTop);
                seatL.quadTo(x - ww * 0.5 + backW, seatTop, x - ww * 0.5 + backW + 1, armTop);
                seatL.lineTo(x - ww * 0.5 + seatW - 3, armTop + 1);
                seatL.quadTo(x - ww * 0.5 + seatW, armTop + 2, x - ww * 0.5 + seatW, botY);
                seatL.lineTo(x - ww * 0.5 - 1, botY);
                seatL.closePath();
                g.fill(seatL);

                Path2D.Double seatR = new Path2D.Double();
                seatR.moveTo(x + ww * 0.5 + 1, seatTop);
                seatR.lineTo(x + ww * 0.5 - backW + 4, seatTop);
                seatR.quadTo(x + ww * 0.5 - backW, seatTop, x + ww * 0.5 - backW - 1, armTop);
                seatR.lineTo(x + ww * 0.5 - seatW + 3, armTop + 1);
                seatR.quadTo(x + ww * 0.5 - seatW, armTop + 2, x + ww * 0.5 - seatW, botY);
                seatR.lineTo(x + ww * 0.5 + 1, botY);
                seatR.closePath();
                g.fill(seatR);

                double ly = y + wh * 0.25;
                float glowR = (float) (ww * 0.24);
                g.setPaint(new RadialGradientPaint((float) x, (float) (ly + 2), glowR,
                        new float[]{0f, 0.55f, 1f},
                        new Color[]{new Color(255, 250, 232, 184), new Color(255, 214, 132, 71), new Color(255, 214, 132, 0)}));
                g.fill(new Ellipse2D.Double(x - glowR, ly + 2 - glowR, glowR * 2, glowR * 2));

                Color lampCol = new Color(116, 50, 24, 163);
                double tblY = y + wh * 0.5 - 4.2;
                g.setColor(lampCol);
                g.fill(new RoundRectangle2D.Double(x - ww * 0.19, tblY, ww * 0.38, 4.5, 0, 0));
                g.setColor(new Color(244, 212, 142, 133));
                g.fill(new RoundRectangle2D.Double(x - ww * 0.19, tblY, ww * 0.38, 0.9, 0, 0));
                g.setColor(lampCol);
                g.fill(new RoundRectangle2D.Double(x - 0.8, ly + 1.5, 1.6, Math.max(2, tblY - (ly + 1.5)), 0, 0));
                g.fill(new RoundRectangle2D.Double(x - 4.2, tblY - 1.3, 8.4, 1.4, 0, 0));
                g.fill(new Arc2D.Double(x - 6.2, ly + 2.5 - 6.2, 12.4, 12.4, 0, 180, Arc2D.CHORD));
                g.setColor(new Color(255, 248, 224, 204));
                g.fill(new RoundRectangle2D.Double(x - 5.0, ly + 2.0, 10.0, 1.2, 0, 0));
            } else if (isDoorWin && lit > 0.04) {
                g.setColor(new Color(76, 22, 30, 56));
                g.fill(new RoundRectangle2D.Double(x - ww * 0.5, y - wh * 0.5, 4.5, wh, 0, 0));
                g.setColor(new Color(168, 112, 48, 66));
                g.fill(new RoundRectangle2D.Double(x - ww * 0.5 + 6, y + wh * 0.06, ww - 12, 1.6, 0, 0));
            }

            g.setColor(new Color(255, 253, 246, (int) Math.round(61 * bz)));
            Path2D.Double gl1 = new Path2D.Double();
            gl1.moveTo(x - ww * 0.02, y - wh * 0.5);
            gl1.lineTo(x + ww * 0.22, y - wh * 0.5);
            gl1.lineTo(x - ww * 0.16, y + wh * 0.5);
            gl1.lineTo(x - ww * 0.40, y + wh * 0.5);
            gl1.closePath();
            g.fill(gl1);

            g.setColor(new Color(255, 253, 246, (int) Math.round(33 * bz)));
            Path2D.Double gl2 = new Path2D.Double();
            gl2.moveTo(x + ww * 0.30, y - wh * 0.5);
            gl2.lineTo(x + ww * 0.40, y - wh * 0.5);
            gl2.lineTo(x + ww * 0.02, y + wh * 0.5);
            gl2.lineTo(x - ww * 0.08, y + wh * 0.5);
            gl2.closePath();
            g.fill(gl2);

            g.setPaint(new GradientPaint(0f, (float) (y - wh * 0.5), new Color(22, 12, 18, 92),
                    0f, (float) (y - wh * 0.5 + 5.5), new Color(22, 12, 18, 0)));
            g.fillRect((int) Math.floor(x - ww * 0.5), (int) Math.floor(y - wh * 0.5), (int) Math.ceil(ww + 1), 6);
            g.setClip(prevClip);

            g.setColor(new Color(238, 210, 152, 163));
            g.setStroke(new BasicStroke(1.05f));
            double rIn = Math.max(2.0, r - 0.5);
            g.draw(new RoundRectangle2D.Double(x - ww * 0.5 + 0.5, y - wh * 0.5 + 0.5, ww - 1.0, wh - 1.0, rIn * 2, rIn * 2));
            g.setColor(new Color(255, 248, 230, 140));
            g.fill(new RoundRectangle2D.Double(x - ww * 0.5 + r * 0.65, y + wh * 0.5 - 1.1, Math.max(0, ww - r * 1.3), 1.0, 0, 0));
        });
    }

    // ── Tier B CPU Baking: Perforated Stamps & Boarding Passes ───────────────

    private CpuFloatingItem buildStampCpu(StampDef def, int idx, Font fontSansCn, Font fontMono) {
        final double w = 192.0, h = 240.0, pad = 16.0;
        final int cw = (int) (w + pad * 2), ch = (int) (h + pad * 2);
        final WireMesh mesh = buildWireIcon(def.icon());
        final double yaw = 0.42 + idx * 0.48;

        HabiKineticAtlas.CpuImage faceCpu = bakeCpuTex(cw, ch, BAKE_SCALE, g -> {
            g.translate(pad, pad);

            double tooth = 5.4;
            g.setPaint(new GradientPaint(0f, 0f, C_PAPER_TOP, 0f, (float) h, C_PAPER_BOT));
            g.fill(new RoundRectangle2D.Double(tooth, tooth, w - tooth * 2, h - tooth * 2, 0, 0));

            double pitch = tooth * 2.65;
            int nx = Math.max(4, (int) Math.round((w - tooth * 2) / pitch));
            int ny = Math.max(4, (int) Math.round((h - tooth * 2) / pitch));
            for (int i = 0; i <= nx; i++) {
                double x = tooth + ((w - tooth * 2) * i) / nx;
                g.setColor(C_PAPER_TOP);
                g.fill(new Ellipse2D.Double(x - tooth, 0, tooth * 2, tooth * 2));
                g.setColor(C_PAPER_BOT);
                g.fill(new Ellipse2D.Double(x - tooth, h - tooth * 2, tooth * 2, tooth * 2));
            }
            for (int i = 1; i < ny; i++) {
                double ty = i / (double) ny;
                double y = tooth + (h - tooth * 2) * ty;
                g.setColor(new Color(lerpRgb(PAPER_TOP, PAPER_BOTTOM, ty)));
                g.fill(new Ellipse2D.Double(0, y - tooth, tooth * 2, tooth * 2));
                g.fill(new Ellipse2D.Double(w - tooth * 2, y - tooth, tooth * 2, tooth * 2));
            }

            g.setColor(new Color(117, 90, 48, 64));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Double(tooth + 1.5, tooth + 1.5, w - tooth * 2 - 3, h - tooth * 2 - 3, 0, 0));

            double inset = 16.5;
            double px0 = inset, py0 = inset, px1 = w - inset, py1 = h - inset;
            double pw = px1 - px0, ph = py1 - py0;
            g.setPaint(new GradientPaint(0f, (float) py0, new Color(def.inkTop()), 0f, (float) py1, new Color(def.inkBot())));
            g.fill(new RoundRectangle2D.Double(px0, py0, pw, ph, 0, 0));

            g.setColor(new Color(255, 255, 255, 17));
            for (double y = py0 + 4; y < py1; y += 5.5) {
                g.fill(new RoundRectangle2D.Double(px0, y, pw, 1.3, 0, 0));
            }

            double bandH = 44.0;
            g.setColor(new Color(0, 0, 0, 87));
            g.fill(new RoundRectangle2D.Double(px0, py0, pw, bandH, 0, 0));

            Color frameCol = new Color(248, 238, 218, 199);
            g.setColor(frameCol);
            g.setStroke(new BasicStroke(1.7f));
            g.draw(chamferRect(px0 + 5, py0 + 5, pw - 10, ph - 10, 4));

            double iconCx = w * 0.5;
            double iconCy = (py0 + bandH + py1) * 0.5 - 3.0;
            double ovalRx = pw * 0.37;
            double ovalRy = Math.min(ovalRx * 1.12, (py1 - (py0 + bandH) - 24) * 0.46);

            g.setPaint(new RadialGradientPaint((float) iconCx, (float) iconCy, (float) ovalRy,
                    new float[]{0f, 1f}, new Color[]{new Color(0, 0, 0, 71), new Color(0, 0, 0, 15)}));
            g.fill(new Ellipse2D.Double(iconCx - ovalRx, iconCy - ovalRy, ovalRx * 2, ovalRy * 2));

            g.setColor(frameCol);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(new Ellipse2D.Double(iconCx - ovalRx, iconCy - ovalRy, ovalRx * 2, ovalRy * 2));

            float glowR = (float) (ovalRy * 1.25);
            g.setPaint(new RadialGradientPaint((float) iconCx, (float) iconCy, glowR,
                    new float[]{0f, 1f}, new Color[]{new Color(244, 216, 148, 46), new Color(244, 216, 148, 0)}));
            g.fill(new Ellipse2D.Double(iconCx - glowR, iconCy - glowR, glowR * 2, glowR * 2));

            for (int i = 0; i < 4; i++) {
                double dx = (i & 1) == 0 ? px0 + 13 : px1 - 13;
                double dy = (i & 2) == 0 ? py0 + bandH + 13 : py1 - 28;
                drawDiamond(g, dx, dy, 4.0, 4.0, frameCol);
            }

            drawWireMesh2D(g, mesh, iconCx, iconCy, Math.min(ovalRx, ovalRy) * 0.74, yaw, -0.40, C_BRASS_LIGHT);

            float fs = def.label().length() >= 5 ? 21f : 24f;
            float ls = def.label().length() <= 2 ? 6f : 2f;
            g.setFont(fontSansCn.deriveFont(Font.BOLD, fs));
            g.setColor(C_INK_LIGHT);
            drawSpacedCenteredMiddle(g, def.label(), (float) (w * 0.5), (float) (py0 + bandH * 0.5 + 1.0), ls);

            g.setFont(fontMono.deriveFont(Font.BOLD, 13f));
            FontMetrics fmMono = g.getFontMetrics();
            float numBaseline = (float) (py1 - 14.0 + (fmMono.getAscent() - fmMono.getDescent()) * 0.5);
            g.setColor(new Color(248, 238, 218, 230));
            g.drawString(def.num(), (float) (px0 + 10.0), numBaseline);
            g.setColor(new Color(248, 238, 218, 148));
            int habiW = fmMono.stringWidth("HABI");
            g.drawString("HABI", (float) (px1 - 10.0 - habiW), numBaseline);

            if (def.postmark()) {
                drawPostmark(g, w * 0.34, h * 0.62, 34.0, "26.11", -0.24 + (idx % 3) * 0.08,
                        C_POSTMARK, 0.72f, idx * 37 + 5, fontMono);
            }

            if (def.op()) {
                double ox = w - 10.0, oy = 10.0, or = 17.0;
                g.setPaint(new GradientPaint((float) ox, (float) (oy - or), new Color(0xE04A3C),
                        (float) ox, (float) (oy + or), new Color(0xA82A22)));
                g.fill(new Ellipse2D.Double(ox - or, oy - or, or * 2, or * 2));
                g.setColor(C_PAPER_TOP);
                g.setStroke(new BasicStroke(2.2f));
                g.draw(new Ellipse2D.Double(ox - or, oy - or, or * 2, or * 2));
                g.setFont(fontMono.deriveFont(Font.BOLD, 13f));
                drawSpacedCenteredMiddle(g, "OP", (float) (ox + 0.5), (float) (oy + 0.5), 0f);
            }
        });

        HabiKineticAtlas.CpuImage sideCpu = HabiKineticAtlas.CpuImage.silhouetteFrom(faceCpu, 0x8E7A56);
        return new CpuFloatingItem(def.id(), "stamp", def.label(), def.num(), faceCpu, sideCpu, cw, ch, cw * 0.5, ch * 0.5);
    }

    private CpuFloatingItem buildTicketCpu(TicketDef def, int idx, Font fontSansCn, Font fontMono) {
        final double w = 310.0, h = 152.0, pad = 12.0;
        final int cw = (int) (w + pad * 2), ch = (int) (h + pad * 2);

        HabiKineticAtlas.CpuImage faceCpu = bakeCpuTex(cw, ch, BAKE_SCALE, g -> {
            g.translate(pad, pad);
            double stubX = Math.round(w * 0.76);
            double notch = 9.5, c = 8.0, band = 26.0, ins = 6.0;

            Path2D.Double ticketShape = buildTicketPath(w, h, stubX, notch, c);
            Shape prevClip = g.getClip();
            g.clip(ticketShape);

            g.setPaint(new GradientPaint(0f, 0f, new Color(def.paperTop()), 0f, (float) h, new Color(def.paperBot())));
            g.fillRect(0, 0, (int) w, (int) h);

            g.setColor(new Color(def.band()));
            g.fillRect(0, 0, (int) w, (int) band);

            g.setColor(def.guilloche());
            g.setStroke(new BasicStroke(1.1f));
            for (int l = 0; l < 5; l++) {
                double by = band + 14 + l * ((h - band - 24) / 4.0);
                double ph = hash(idx * 11 + l) * TAU;
                Path2D.Double wave = new Path2D.Double();
                boolean first = true;
                for (double x = ins + 4; x <= stubX - notch - 4; x += 4.0) {
                    double y = by + Math.sin(x * 0.08 + ph) * 3.5 + Math.sin(x * 0.032 + ph * 1.7) * 2.0;
                    if (first) { wave.moveTo(x, y); first = false; } else wave.lineTo(x, y);
                }
                g.draw(wave);
            }

            g.setColor(new Color(def.trim()));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(chamferRect(ins, ins, stubX - notch - ins * 2, h - ins * 2, 5));
            g.draw(chamferRect(stubX + notch + ins, ins, w - (stubX + notch + ins) - ins, h - ins * 2, 5));

            g.setColor(new Color(def.inkSoft()));
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g.draw(new Line2D.Double(stubX, notch + 3, stubX, h - notch - 3));
            g.setStroke(new BasicStroke(1.2f));

            drawWingedWheel(g, ins + 26, band * 0.5, 6.5);
            g.setColor(new Color(def.bandInk()));
            g.setFont(fontSansCn.deriveFont(Font.BOLD, 13f));
            FontMetrics fmBand = g.getFontMetrics();
            g.drawString(def.title(), (float) (ins + 52), (float) (band * 0.5 + 1.0 + (fmBand.getAscent() - fmBand.getDescent()) * 0.5));
            g.setFont(fontMono.deriveFont(Font.BOLD, 9f));
            FontMetrics fmSer = g.getFontMetrics();
            String noStr = "No." + def.serial();
            g.drawString(noStr, (float) (stubX - notch - ins - 6 - fmSer.stringWidth(noStr)),
                    (float) (band * 0.5 + 1.0 + (fmSer.getAscent() - fmSer.getDescent()) * 0.5));

            g.setColor(new Color(def.inkSoft()));
            g.setFont(fontMono.deriveFont(Font.BOLD, 9f));
            FontMetrics fmD = g.getFontMetrics();
            float dy = (float) (band + 20.0 + (fmD.getAscent() - fmD.getDescent()) * 0.5);
            g.drawString("DESTINATION // ", (float) (ins + 12), dy);
            int destEnW = fmD.stringWidth("DESTINATION // ");
            g.setFont(fontSansCn.deriveFont(Font.BOLD, 9f));
            g.drawString("终点站", (float) (ins + 12 + destEnW), dy);

            g.setColor(new Color(def.ink()));
            g.setFont(fontSansCn.deriveFont(Font.BOLD, 26f));
            FontMetrics fmDest = g.getFontMetrics();
            g.drawString(def.dest(), (float) (ins + 12), (float) (band + 50.0 + (fmDest.getAscent() - fmDest.getDescent()) * 0.5));

            Color trimC = new Color(def.trim());
            g.setPaint(new GradientPaint((float) (ins + 12), 0f, trimC, (float) (stubX - 36), 0f,
                    new Color(trimC.getRed(), trimC.getGreen(), trimC.getBlue(), 0)));
            g.fill(new RoundRectangle2D.Double(ins + 12, band + 70, stubX - 54, 1.5, 0, 0));

            g.setColor(new Color(def.inkSoft()));
            g.setFont(fontSansCn.deriveFont(Font.BOLD, 11f));
            FontMetrics fmSeat = g.getFontMetrics();
            g.drawString(def.seat() + "   ·   " + def.sub(), (float) (ins + 12),
                    (float) (h - ins - 18.0 + (fmSeat.getAscent() - fmSeat.getDescent()) * 0.5));

            double stubCx = (stubX + notch + ins + w - ins) * 0.5;
            g.setColor(new Color(def.serialCol()));
            g.setFont(fontMono.deriveFont(Font.BOLD, 10f));
            drawSpacedCenteredMiddle(g, def.serial(), (float) stubCx, (float) (band + 16), 0f);

            AffineTransform savedTx = g.getTransform();
            g.translate(stubCx, band + 56);
            g.rotate(Math.PI * 0.5);
            g.setColor(new Color(def.ink()));
            g.setFont(fontMono.deriveFont(Font.BOLD, 9f));
            drawSpacedCenteredMiddle(g, "ADMIT ONE", 0f, 0f, 2.0f);
            g.setTransform(savedTx);

            double bx = stubX + notch + ins + 5.0;
            double bMax = w - ins - 5.0;
            g.setColor(new Color(def.ink()));
            int bi = 0;
            while (bx < bMax) {
                double bw = 1.4 + Math.floor(hash(idx * 53 + bi) * 3.0);
                if (hash(idx * 31 + bi * 7) > 0.36) {
                    g.fill(new RoundRectangle2D.Double(bx, h - ins - 28, Math.min(bw, bMax - bx), 20, 0, 0));
                }
                bx += bw + 1.5;
                bi++;
            }

            if (def.stamped() != null) {
                AffineTransform stTx = g.getTransform();
                g.translate(stubX * 0.64, band + 68);
                g.rotate(-0.18);
                double bw = 96.0, bh = 38.0;
                g.setColor(new Color(216, 56, 52, 224));
                g.setStroke(new BasicStroke(2.4f));
                g.draw(chamferRect(-bw * 0.5, -bh * 0.5, bw, bh, 5));
                g.setStroke(new BasicStroke(1.0f));
                g.draw(chamferRect(-bw * 0.5 + 4, -bh * 0.5 + 4, bw - 8, bh - 8, 3));
                g.setColor(new Color(216, 56, 52, 230));
                g.setFont(fontSansCn.deriveFont(Font.BOLD, 15f));
                drawSpacedCenteredMiddle(g, def.stamped(), 0f, -5f, 0f);
                if (def.stampSub() != null) {
                    g.setFont(fontMono.deriveFont(Font.BOLD, 8f));
                    drawSpacedCenteredMiddle(g, def.stampSub(), 0f, 10f, 1.5f);
                }
                g.setTransform(stTx);
            }

            g.setClip(prevClip);
            g.setColor(new Color(def.trim()));
            g.setStroke(new BasicStroke(1.3f));
            g.draw(ticketShape);
        });

        HabiKineticAtlas.CpuImage sideCpu = HabiKineticAtlas.CpuImage.silhouetteFrom(faceCpu, 0x5A4A2E);
        return new CpuFloatingItem(def.id(), "ticket", def.dest(), def.serial(), faceCpu, sideCpu, cw, ch, cw * 0.5, ch * 0.5);
    }

    private static Path2D.Double buildTicketPath(double w, double h, double stubX, double notch, double c) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(c, 0);
        p.lineTo(stubX - notch, 0);
        for (int i = 1; i <= 16; i++) {
            double ang = lerp(Math.PI, 0.0, i / 16.0);
            p.lineTo(stubX + Math.cos(ang) * notch, Math.sin(ang) * notch);
        }
        p.lineTo(w - c, 0);
        p.lineTo(w, c);
        p.lineTo(w, h - c);
        p.lineTo(w - c, h);
        p.lineTo(stubX + notch, h);
        for (int i = 1; i <= 16; i++) {
            double ang = lerp(0.0, -Math.PI, i / 16.0);
            p.lineTo(stubX + Math.cos(ang) * notch, h + Math.sin(ang) * notch);
        }
        p.lineTo(c, h);
        p.lineTo(0, h - c);
        p.lineTo(0, c);
        p.closePath();
        return p;
    }

    // ── Vector Emblem & WireModels Helpers ───────────────────────────────────

    private static final class WireMesh {
        final List<double[]> segs = new ArrayList<>();

        WireMesh line(double x0, double y0, double z0, double x1, double y1, double z1) {
            segs.add(new double[]{x0, y0, z0, x1, y1, z1});
            return this;
        }

        WireMesh path(boolean closed, double... pts) {
            int n = pts.length / 3;
            for (int i = 0; i + 1 < n; i++) {
                line(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2],
                        pts[i * 3 + 3], pts[i * 3 + 4], pts[i * 3 + 5]);
            }
            if (closed && n > 2) {
                line(pts[n * 3 - 3], pts[n * 3 - 2], pts[n * 3 - 1], pts[0], pts[1], pts[2]);
            }
            return this;
        }

        WireMesh arc(double cx, double cy, double cz, double r,
                     double ux, double uy, double uz, double vx, double vy, double vz,
                     double a0, double a1, int sides) {
            double step = (a1 - a0) / sides;
            double pc = Math.cos(a0), ps = Math.sin(a0);
            for (int i = 1; i <= sides; i++) {
                double a = a0 + step * i, c = Math.cos(a), s = Math.sin(a);
                line(cx + (ux * pc + vx * ps) * r, cy + (uy * pc + vy * ps) * r, cz + (uz * pc + vz * ps) * r,
                        cx + (ux * c + vx * s) * r, cy + (uy * c + vy * s) * r, cz + (uz * c + vz * s) * r);
                pc = c;
                ps = s;
            }
            return this;
        }

        WireMesh circle(double cx, double cy, double cz, double r,
                        double ux, double uy, double uz, double vx, double vy, double vz, int sides) {
            return arc(cx, cy, cz, r, ux, uy, uz, vx, vy, vz, 0, TAU, sides);
        }

        WireMesh circleY(double cx, double cy, double cz, double r, int sides) {
            return circle(cx, cy, cz, r, 1, 0, 0, 0, 0, 1, sides);
        }

        WireMesh circleZ(double cx, double cy, double cz, double r, int sides) {
            return circle(cx, cy, cz, r, 1, 0, 0, 0, 1, 0, sides);
        }

        WireMesh box(double x0, double y0, double z0, double x1, double y1, double z1) {
            line(x0, y0, z0, x1, y0, z0).line(x1, y0, z0, x1, y0, z1).line(x1, y0, z1, x0, y0, z1).line(x0, y0, z1, x0, y0, z0);
            line(x0, y1, z0, x1, y1, z0).line(x1, y1, z0, x1, y1, z1).line(x1, y1, z1, x0, y1, z1).line(x0, y1, z1, x0, y1, z0);
            line(x0, y0, z0, x0, y1, z0).line(x1, y0, z0, x1, y1, z0).line(x1, y0, z1, x1, y1, z1).line(x0, y0, z1, x0, y1, z1);
            return this;
        }

        WireMesh cylinderZ(double z0, double z1, double cx, double cy, double r, int sides, int gen) {
            circleZ(cx, cy, z0, r, sides);
            circleZ(cx, cy, z1, r, sides);
            for (int i = 0; i < gen; i++) {
                double a = (TAU * i) / gen + 0.2;
                double x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r;
                line(x, y, z0, x, y, z1);
            }
            return this;
        }

        WireMesh lathe(double cx, double cy, double cz, double[] profile, int sides, int meridians) {
            int n = profile.length / 2;
            for (int i = 0; i < n; i++) {
                double r = profile[i * 2];
                if (r > 0.001) circleY(cx, cy + profile[i * 2 + 1], cz, r, sides);
            }
            for (int m = 0; m < meridians; m++) {
                double a = (TAU * m) / meridians + 0.25, c = Math.cos(a), s = Math.sin(a);
                for (int i = 0; i + 1 < n; i++) {
                    double r0 = profile[i * 2], y0 = profile[i * 2 + 1];
                    double r1 = profile[i * 2 + 2], y1 = profile[i * 2 + 3];
                    line(cx + c * r0, cy + y0, cz + s * r0, cx + c * r1, cy + y1, cz + s * r1);
                }
            }
            return this;
        }

        WireMesh prism(double[] xy, double z0, double z1) {
            int n = xy.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                line(xy[i * 2], xy[i * 2 + 1], z0, xy[j * 2], xy[j * 2 + 1], z0);
                line(xy[i * 2], xy[i * 2 + 1], z1, xy[j * 2], xy[j * 2 + 1], z1);
                line(xy[i * 2], xy[i * 2 + 1], z0, xy[i * 2], xy[i * 2 + 1], z1);
            }
            return this;
        }
    }

    private static WireMesh buildWireIcon(String name) {
        WireMesh m = new WireMesh();
        switch (name) {
            case "TOP_HAT" -> {
                m.lathe(0, -0.62, 0, new double[]{1.0, 0.0, 0.6, 0.1, 0.55, 1.0, 0.6, 1.12, 0.0, 1.12}, 18, 8);
                m.circleY(0, -0.3, 0, 0.575, 18);
            }
            case "GLOBE" -> {
                double tilt = 0.4, ax = Math.sin(tilt), ay = Math.cos(tilt);
                for (int k = -1; k <= 1; k++) {
                    double h = k * 0.5, rr = Math.sqrt(1 - h * h) * 0.85;
                    m.circle(ax * h * 0.85, ay * h * 0.85, 0, rr, Math.cos(tilt), -Math.sin(tilt), 0, 0, 0, 1, 20);
                }
                for (int i = 0; i < 4; i++) {
                    double a = (Math.PI * i) / 4.0;
                    m.circle(0, 0, 0, 0.85, Math.cos(tilt) * Math.cos(a), -Math.sin(tilt) * Math.cos(a), Math.sin(a), ax, ay, 0, 22);
                }
                m.line(-ax * 1.1, -ay * 1.1, 0, ax * 1.1, ay * 1.1, 0);
                m.arc(0, 0, 0, 1.02, Math.cos(tilt), -Math.sin(tilt), 0, ax, ay, 0, -0.2, Math.PI + 0.2, 16);
            }
            case "TURNTABLE" -> {
                m.circleY(0, 0, 0, 1.0, 26);
                m.circleY(0, -0.18, 0, 0.88, 26);
                m.circleY(0, -0.18, 0, 0.12, 8);
                for (int side : new int[]{-1, 1}) m.line(-0.88, -0.05, side * 0.14, 0.88, -0.05, side * 0.14);
                for (int i = -2; i <= 2; i++) m.line(i * 0.35, -0.08, -0.24, i * 0.35, -0.08, 0.24);
                for (int i = 0; i < 3; i++) {
                    double a = Math.PI * 0.35 + i * 0.55, c = Math.cos(a), s = Math.sin(a);
                    for (int side : new int[]{-1, 1}) {
                        double ox = -s * side * 0.14, oz = c * side * 0.14;
                        m.line(c * 1.0 + ox, 0, s * 1.0 + oz, c * 1.35 + ox, 0, s * 1.35 + oz);
                    }
                }
            }
            case "TROPHY" -> {
                m.lathe(0, -1.0, 0, new double[]{0.55, 0.0, 0.55, 0.14, 0.2, 0.24, 0.1, 0.62, 0.26, 0.78, 0.6, 1.12, 0.66, 1.62}, 16, 8);
                for (int side : new int[]{-1, 1}) {
                    m.arc(side * 0.66, 0.38, 0, 0.3, side, 0, 0, 0, 1, 0, -Math.PI * 0.55, Math.PI * 0.55, 10);
                }
            }
            case "DRESS_FORM" -> m.lathe(0, -1.05, 0,
                    new double[]{0.5, 0.0, 0.06, 0.1, 0.06, 0.62, 0.44, 0.72, 0.36, 1.05, 0.5, 1.4, 0.42, 1.72, 0.13, 1.82, 0.1, 2.0, 0.0, 2.04}, 16, 8);
            case "SUITCASE" -> {
                m.box(-1.0, -0.62, -0.32, 1.0, 0.62, 0.32);
                m.path(false, -0.32, 0.62, 0, -0.28, 0.92, 0, 0.28, 0.92, 0, 0.32, 0.62, 0);
                for (int side : new int[]{-1, 1}) {
                    double x = side * 0.55;
                    m.path(true, x, -0.62, -0.33, x, 0.62, -0.33, x, 0.62, 0.33, x, -0.62, 0.33);
                }
                m.line(-1.0, 0, -0.33, 1.0, 0, -0.33);
            }
            case "GEAR" -> {
                int teeth = 9;
                double[] outline = new double[teeth * 8];
                int p = 0;
                double w = TAU / teeth;
                double[] rads = {0.72, 1.0, 1.0, 0.72};
                for (int i = 0; i < teeth; i++) {
                    double a = (TAU * i) / teeth;
                    double[] angs = {a, a + w * 0.18, a + w * 0.32, a + w * 0.68};
                    for (int k = 0; k < 4; k++) {
                        outline[p++] = Math.cos(angs[k] + w * 0.1) * rads[k];
                        outline[p++] = Math.sin(angs[k] + w * 0.1) * rads[k];
                    }
                }
                m.prism(outline, -0.22, 0.22);
                m.circleZ(0, 0, -0.22, 0.32, 12);
                m.circleZ(0, 0, 0.22, 0.32, 12);
                for (int i = 0; i < 4; i++) {
                    double a = (Math.PI / 2.0) * i + 0.4;
                    m.line(Math.cos(a) * 0.32, Math.sin(a) * 0.32, -0.22, Math.cos(a) * 0.32, Math.sin(a) * 0.32, 0.22);
                }
            }
            case "STOPWATCH" -> {
                m.cylinderZ(-0.2, 0.2, 0, -0.08, 0.88, 24, 8);
                m.circleZ(0, -0.08, -0.22, 0.72, 24);
                for (int i = 0; i < 12; i++) {
                    double a = (TAU * i) / 12.0, inn = (i % 3 == 0) ? 0.56 : 0.64;
                    m.line(Math.cos(a) * inn, -0.08 + Math.sin(a) * inn, -0.22, Math.cos(a) * 0.72, -0.08 + Math.sin(a) * 0.72, -0.22);
                }
                m.line(0, -0.08, -0.24, 0, 0.46, -0.24);
                m.line(0, -0.08, -0.24, 0.36, 0.12, -0.24);
                m.box(-0.12, 0.8, -0.12, 0.12, 1.02, 0.12);
                m.box(-0.2, 1.02, -0.15, 0.2, 1.1, 0.15);
            }
            case "CARDS" -> {
                double[] corners = {-0.55, -0.8, 0.55, -0.8, 0.55, 0.8, -0.55, 0.8};
                for (int i = -1; i <= 1; i++) {
                    double a = i * 0.32, c = Math.cos(a), s = Math.sin(a);
                    double[] rot = new double[8];
                    for (int k = 0; k < 4; k++) {
                        double x = corners[k * 2], y = corners[k * 2 + 1] + 0.15;
                        rot[k * 2] = x * c - y * s + i * 0.18;
                        rot[k * 2 + 1] = x * s + y * c - 0.15;
                    }
                    m.prism(rot, i * 0.14 - 0.02, i * 0.14 + 0.02);
                }
            }
            case "MAGNIFIER" -> {
                m.circleZ(-0.25, 0.25, -0.08, 0.62, 22);
                m.circleZ(-0.25, 0.25, 0.08, 0.62, 22);
                m.circleZ(-0.25, 0.25, 0.0, 0.5, 20);
                double hx = -0.25 + 0.62 * 0.707, hy = 0.25 - 0.62 * 0.707;
                m.line(hx, hy, -0.06, hx + 0.62, hy - 0.62, -0.06);
                m.line(hx, hy, 0.06, hx + 0.62, hy - 0.62, 0.06);
                m.line(hx + 0.62, hy - 0.62, -0.06, hx + 0.62, hy - 0.62, 0.06);
            }
            case "GIFT" -> {
                m.box(-0.8, -0.8, -0.8, 0.8, 0.4, 0.8);
                m.box(-0.9, 0.4, -0.9, 0.9, 0.62, 0.9);
                m.line(0, -0.8, -0.81, 0, 0.62, -0.81);
                m.line(-0.81, -0.8, 0, -0.81, 0.62, 0);
                m.circle(-0.28, 0.82, 0, 0.26, 1, 0, 0, 0, 1, 0.4, 10);
                m.circle(0.28, 0.82, 0, 0.26, 1, 0, 0, 0, 1, -0.4, 10);
            }
            case "ENVELOPE" -> {
                m.box(-1.0, -0.62, -0.06, 1.0, 0.62, 0.06);
                m.path(false, -1.0, 0.62, -0.07, 0.0, -0.05, -0.07, 1.0, 0.62, -0.07);
                m.line(-1.0, -0.62, -0.07, -0.3, 0, -0.07);
                m.line(1.0, -0.62, -0.07, 0.3, 0, -0.07);
            }
            default -> {
                double h = 1.1, r = 0.62;
                double[][] ring = {{r, 0}, {0, r}, {-r, 0}, {0, -r}};
                for (int i = 0; i < 4; i++) {
                    double[] a = ring[i], b = ring[(i + 1) % 4];
                    m.line(a[0], 0.15, a[1], b[0], 0.15, b[1]);
                    m.line(a[0], 0.15, a[1], 0, h, 0);
                    m.line(a[0], 0.15, a[1], 0, -h * 0.9, 0);
                }
                m.circleY(0, 0.15, 0, r * 0.72, 12);
            }
        }
        return m;
    }

    private static void drawWireMesh2D(Graphics2D g, WireMesh mesh, double cx, double cy,
                                       double scale, double yaw, double pitch, Color color) {
        double cyw = Math.cos(yaw), syw = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double dist = 5.6;

        Composite savedComp = g.getComposite();
        g.setColor(color);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.24f));
        g.setStroke(new BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double halo = new Path2D.Double();
        for (double[] s : mesh.segs) {
            double[] a = projWire(s[0], s[1], s[2], cx, cy, scale, cyw, syw, cp, sp, dist);
            double[] b = projWire(s[3], s[4], s[5], cx, cy, scale, cyw, syw, cp, sp, dist);
            halo.moveTo(a[0], a[1]);
            halo.lineTo(b[0], b[1]);
        }
        g.draw(halo);

        g.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double[] s : mesh.segs) {
            double[] a = projWire(s[0], s[1], s[2], cx, cy, scale, cyw, syw, cp, sp, dist);
            double[] b = projWire(s[3], s[4], s[5], cx, cy, scale, cyw, syw, cp, sp, dist);
            double zm = (a[2] + b[2]) * 0.5;
            double depth = 1.0 - 0.46 * Math.min(1.0, Math.max(0.0, (zm + 1.2) / 2.4));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (0.95 * depth)));
            g.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
        }
        g.setComposite(savedComp);
    }

    private static double[] projWire(double x, double y, double z, double cx, double cy, double scale,
                                     double cyw, double syw, double cp, double sp, double dist) {
        double rx = cyw * x + syw * z;
        double ry = sp * syw * x + cp * y - sp * cyw * z;
        double rz = -cp * syw * x + sp * y + cp * cyw * z;
        double f = dist / Math.max(0.25, dist + rz);
        return new double[]{cx + rx * f * scale, cy - ry * f * scale, rz};
    }

    private static void drawPostmark(Graphics2D g, double cx, double cy, double r, String dateStr,
                                     double rotRad, Color color, float alpha, int seed, Font fontMono) {
        AffineTransform prevTx = g.getTransform();
        Composite prevComp = g.getComposite();
        g.translate(cx, cy);
        g.rotate(rotRad);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.setColor(color);

        g.setStroke(new BasicStroke((float) (r * 0.08)));
        g.draw(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
        g.setStroke(new BasicStroke((float) (r * 0.045)));
        g.draw(new Ellipse2D.Double(-r * 0.64, -r * 0.64, r * 1.28, r * 1.28));

        g.setStroke(new BasicStroke((float) (r * 0.062), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 4; i++) {
            double y = (i - 1.5) * r * 0.36;
            double x0 = r * 1.08;
            Path2D.Double wave = new Path2D.Double();
            for (int s = 0; s <= 18; s++) {
                double x = x0 + (s / 18.0) * r * 1.9;
                double yy = y + Math.sin(s * 0.65 + i * 0.9) * r * 0.09;
                if (s == 0) wave.moveTo(x, yy); else wave.lineTo(x, yy);
            }
            g.draw(wave);
        }

        g.setFont(fontMono.deriveFont(Font.BOLD, (float) Math.round(r * 0.34)));
        drawSpacedCenteredMiddle(g, dateStr, 0f, 1f, 0f);
        g.setFont(fontMono.deriveFont(Font.BOLD, (float) Math.round(r * 0.26)));
        drawSpacedCenteredMiddle(g, "HABI", 0f, (float) (-r * 0.80), 0f);
        drawSpacedCenteredMiddle(g, "EXPRESS", 0f, (float) (r * 0.81), 0f);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * 0.28f));
        for (int i = 0; i < 12; i++) {
            double ang = hash(seed * 7 + i) * TAU;
            double d = r * (0.72 + hash(seed * 13 + i * 5) * 0.32);
            double sz = r * (0.025 + hash(seed + i * 3) * 0.025);
            g.fill(new Ellipse2D.Double(Math.cos(ang) * d - sz, Math.sin(ang) * d - sz, sz * 2, sz * 2));
        }

        g.setComposite(prevComp);
        g.setTransform(prevTx);
    }

    private static void drawWingedWheel(Graphics2D g, double cx, double cy, double r) {
        for (int side : new int[]{-1, 1}) {
            for (int k = 0; k < 4; k++) {
                double rx = cx + side * r * 0.82;
                double ry = cy - r * 0.54 + k * r * 0.32;
                double len = r * (2.15 - k * 0.38);
                double lift = -r * (0.34 - k * 0.07);
                g.setColor(k == 0 ? C_CHAMPAGNE : C_BRASS_LIGHT);
                Path2D.Double wing = new Path2D.Double();
                wing.moveTo(rx, ry - r * 0.13);
                wing.lineTo(rx + side * len, ry + lift - r * 0.06);
                wing.lineTo(rx + side * (len - r * 0.2), ry + lift + r * 0.1);
                wing.lineTo(rx, ry + r * 0.13);
                wing.closePath();
                g.fill(wing);
            }
        }
        g.setColor(C_BRASS_LIGHT);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.setColor(new Color(0x111A33));
        g.fill(new Ellipse2D.Double(cx - r * 0.82, cy - r * 0.82, r * 1.64, r * 1.64));
        g.setColor(C_BRASS);
        g.setStroke(new BasicStroke((float) Math.max(1.2, r * 0.12)));
        g.draw(new Ellipse2D.Double(cx - r * 0.62, cy - r * 0.62, r * 1.24, r * 1.24));
        for (int i = 0; i < 8; i++) {
            double a = (i / 8.0) * TAU;
            g.draw(new Line2D.Double(cx + Math.cos(a) * r * 0.2, cy + Math.sin(a) * r * 0.2,
                    cx + Math.cos(a) * r * 0.64, cy + Math.sin(a) * r * 0.64));
        }
        g.setColor(C_CHAMPAGNE);
        g.fill(new Ellipse2D.Double(cx - r * 0.22, cy - r * 0.22, r * 0.44, r * 0.44));
    }

    private static Path2D.Double chamferRect(double x, double y, double w, double h, double c) {
        double k = Math.min(c, Math.min(w, h) * 0.5);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x + k, y);
        p.lineTo(x + w - k, y);
        p.lineTo(x + w, y + k);
        p.lineTo(x + w, y + h - k);
        p.lineTo(x + w - k, y + h);
        p.lineTo(x + k, y + h);
        p.lineTo(x, y + h - k);
        p.lineTo(x, y + k);
        p.closePath();
        return p;
    }

    private static void drawDiamond(Graphics2D g, double cx, double cy, double rx, double ry, Color col) {
        g.setColor(col);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx, cy - ry);
        p.lineTo(cx + rx, cy);
        p.lineTo(cx, cy + ry);
        p.lineTo(cx - rx, cy);
        p.closePath();
        g.fill(p);
    }

    private static void drawSpacedCentered(Graphics2D g, String text, float cx, float baselineY, float ls) {
        double tw = HabiKineticAtlas.measureSpaced(g, text, ls);
        HabiKineticAtlas.drawSpaced(g, text, (float) (cx - tw * 0.5), baselineY, ls);
    }

    private static void drawSpacedCenteredMiddle(Graphics2D g, String text, float cx, float cy, float ls) {
        FontMetrics fm = g.getFontMetrics();
        double tw = HabiKineticAtlas.measureSpaced(g, text, ls);
        float baseline = cy + (fm.getAscent() - fm.getDescent()) * 0.5f;
        HabiKineticAtlas.drawSpaced(g, text, (float) (cx - tw * 0.5), baseline, ls);
    }

    private static HabiKineticAtlas.CpuImage bakeCpuTex(int w, int h, float scale, Consumer<Graphics2D> painter) {
        int pw = Math.max(1, Math.round(w * scale));
        int ph = Math.max(1, Math.round(h * scale));
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        HabiKineticAtlas.setupHints(g);
        g.scale(scale, scale);
        painter.accept(g);
        g.dispose();
        return HabiKineticAtlas.CpuImage.fromBufferedImage(img, false);
    }

    private HabiKineticAtlas.StaticGlTexture uploadOwnedCpu(HabiKineticAtlas.CpuImage cpu) {
        HabiKineticAtlas.StaticGlTexture tex = HabiKineticAtlas.uploadCpuImage(cpu);
        owned.add(tex);
        return tex;
    }
}
