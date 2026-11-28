package com.habitrain.core.client.title;

import com.mojang.blaze3d.platform.GlStateManager;
import net.exmo.sre.loading.StarRailLoadingOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.thread.BlockableEventLoop;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL13;

import java.util.concurrent.locks.LockSupport;

/**
 * Stage 2 &amp; Stage 3 loading smoothness coordinator (single-thread OpenGL, 100% Sodium/Iris/Ixeris safe):
 * <ol>
 *   <li><b>Event-loop task time-slicing</b>: caps {@code BlockableEventLoop#runAllTasks()} to
 *       {@link #TASK_SLICE_BUDGET_NANOS} (6 ms) per frame (always running >=1 task) while a Kinetic
 *       loading overlay/screen is active, preserving 60 Hz VSync headroom.</li>
 *   <li><b>Catch-up tick-storm cap</b>: caps catch-up {@code clientTick()} iterations per frame to 1
 *       during loading screens so a single heavy load call never causes 10 cascading aftershock ticks.</li>
 *   <li><b>Off-thread {@code WorldLoader.load} + {@code managedBlock} pumping</b>: runs datapack scan &amp;
 *       worldgen registry parsing on {@code Util.backgroundExecutor()} while pumping ~60 Hz
 *       render-only {@code runTick(false)} frames.</li>
 *   <li><b>Integrated server startup &amp; chunk-load pacing</b>: pumps frames during {@code doWorldLoad}'s
 *       {@code progressListener == null} wait and replaces double {@code Thread.sleep(16L)} with
 *       adaptive pacing.</li>
 *   <li><b>Boot atlas &amp; shader loop frame pumping</b>: pumps smooth loading frames mid-loop inside
 *       {@code TextureAtlas#upload} (restoring {@code GL_TEXTURE0} + bound atlas ID) and
 *       {@code GameRenderer#reloadShaders} during initial boot ({@code mc.level == null}).</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public final class HabiLoadingFramePumper {

    /** Per-frame task budget (6 ms) while a Kinetic loading screen/overlay is visible (leaves ~10 ms for 60 Hz VSync). */
    public static final long TASK_SLICE_BUDGET_NANOS = 6_000_000L;
    /** Target interval (~64 Hz) between pumped frames inside blocking singleplayer load loops. */
    public static final long PUMP_INTERVAL_NANOS = 15_500_000L;
    /** Target interval (~68 Hz) between pumped frames inside boot atlas upload &amp; shader compile loops. */
    public static final long BOOT_LOOP_PUMP_INTERVAL_NANOS = 14_500_000L;
    /** Minimum frame duration (ms) during {@code doWorldLoad} when VSync is disabled so server thread keeps CPU. */
    public static final long WORLD_LOAD_MIN_FRAME_MS = 11L;

    private static int gameRendererRenderDepth;
    private static boolean inFramePump;
    private static long lastPumpNanos;
    private static long doWorldLoadFrameStartNanos;

    private HabiLoadingFramePumper() {}

    // ── Re-entrancy Tracking ─────────────────────────────────────────────────

    public static void enterGameRendererRender() {
        gameRendererRenderDepth++;
    }

    public static void exitGameRendererRender() {
        if (gameRendererRenderDepth > 0) {
            gameRendererRenderDepth--;
        }
    }

    public static boolean isInGameRendererRender() {
        return gameRendererRenderDepth > 0;
    }

    static void resetRenderDepthForTest() {
        gameRendererRenderDepth = 0;
    }

    public static boolean isInFramePump() {
        return inFramePump;
    }

    // ── Event-Loop Time-Slicing & Tick-Storm Cap ─────────────────────────────

    /**
     * Returns the time-slice budget in nanoseconds for {@code BlockableEventLoop#runAllTasks()},
     * or {@code 0L} when task execution must remain completely uncapped.
     */
    public static long getTaskSliceBudgetNanos(@Nullable BlockableEventLoop<?> loop) {
        if (!(loop instanceof Minecraft mc)) return 0L;
        if (!mc.isSameThread() || !mc.isRunning() || inFramePump || isInGameRendererRender()) return 0L;
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return 0L;

        boolean bootOverlay = mc.getOverlay() instanceof StarRailLoadingOverlay;
        boolean worldEntryScreen = isActiveWorldEntryScreen(mc.screen);
        return shouldTimeSliceForState(true, bootOverlay, worldEntryScreen) ? TASK_SLICE_BUDGET_NANOS : 0L;
    }

    public static void noteTaskSliceYielded(@Nullable BlockableEventLoop<?> loop) {
        if (loop instanceof Minecraft) {
            HabiLoadingPerfMonitor.getInstance().recordTaskSliceYield();
        }
    }

    /** Caps catch-up {@code clientTick()} loops from 10 to 1 while a Kinetic loading screen/overlay is visible. */
    public static int capCatchupTicks(@Nullable Minecraft mc, int vanillaMax, int pendingTicks) {
        if (mc != null && mc.isSameThread() && HabiKineticUiController.isKineticLoadingEnabled()) {
            boolean bootOverlay = mc.getOverlay() instanceof StarRailLoadingOverlay;
            boolean worldEntryScreen = isActiveWorldEntryScreen(mc.screen);
            if (shouldCapCatchupTicksForState(true, bootOverlay, worldEntryScreen)) {
                return Math.min(1, pendingTicks);
            }
        }
        return Math.min(vanillaMax, pendingTicks);
    }

    // ── Off-Thread WorldLoader & managedBlock Frame Pumping ──────────────────

    public static boolean shouldOffloadWorldLoader(@Nullable Minecraft mc) {
        return mc != null
                && mc.isSameThread()
                && mc.isRunning()
                && HabiKineticUiController.isKineticLoadingEnabled()
                && HabiWorldEntrySession.getInstance().isActive();
    }

    /**
     * Pumps at most one safe {@code runTick(false)} frame inside {@code BlockableEventLoop#managedBlock}
     * when at least {@link #PUMP_INTERVAL_NANOS} have elapsed since the previous rendered frame.
     */
    public static boolean maybePumpManagedBlockFrame(@Nullable BlockableEventLoop<?> loop) {
        if (!(loop instanceof Minecraft mc)) return false;
        if (!canPumpWorldEntry(mc)) return false;

        long now = System.nanoTime();
        if (!shouldPumpNow(now, lastPumpNanos, PUMP_INTERVAL_NANOS)) return false;

        if (pumpRenderOnlyFrame(mc)) {
            HabiLoadingPerfMonitor.getInstance().recordManagedBlockPump();
            return true;
        }
        return false;
    }

    // ── Boot TextureAtlas#upload & GameRenderer#reloadShaders Pumping ────────

    /**
     * Pumps a smooth Station 00 boot frame mid-loop while uploading massive atlases (e.g. 8192x4096 blocks.png),
     * then immediately restores {@code GL_TEXTURE0} and {@code atlasTexId} for subsequent {@code uploadFirstFrame()} calls.
     * Strictly restricted to initial boot ({@code mc.level == null}) so in-world F3+T reloads never sample partial atlases.
     */
    public static boolean maybePumpBootAtlasFrame(int atlasTexId) {
        Minecraft mc = Minecraft.getInstance();
        if (!canPumpInitialBootLoop(mc)) return false;

        long now = System.nanoTime();
        if (!shouldPumpNow(now, lastPumpNanos, BOOT_LOOP_PUMP_INTERVAL_NANOS)) return false;

        boolean pumped = false;
        try {
            pumped = pumpRenderOnlyFrame(mc);
            if (pumped) {
                HabiLoadingPerfMonitor.getInstance().recordAtlasUploadPump();
            }
        } finally {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(atlasTexId);
        }
        return pumped;
    }

    /**
     * Pumps a smooth Station 00 boot frame between core shader compilations in {@code GameRenderer#reloadShaders}
     * (strictly while existing {@code positionColorShader}/{@code positionTexColorShader} are still alive and
     * {@code mc.level == null}).
     */
    public static boolean maybePumpBootShaderFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (!canPumpInitialBootLoop(mc)) return false;
        if (GameRenderer.getPositionColorShader() == null || GameRenderer.getPositionTexColorShader() == null) {
            return false;
        }

        long now = System.nanoTime();
        if (!shouldPumpNow(now, lastPumpNanos, BOOT_LOOP_PUMP_INTERVAL_NANOS)) return false;

        if (pumpRenderOnlyFrame(mc)) {
            HabiLoadingPerfMonitor.getInstance().recordShaderReloadPump();
            return true;
        }
        return false;
    }

    // ── Minecraft#doWorldLoad Spin & Double-Sleep Fixes ──────────────────────

    /** Replaces {@code Thread.yield()} in {@code Minecraft#doWorldLoad}'s {@code progressListener == null} loop. */
    public static void onDoWorldLoadProgressYield(@Nullable Minecraft mc) {
        if (mc != null && canPumpWorldEntry(mc)) {
            long now = System.nanoTime();
            if (shouldPumpNow(now, lastPumpNanos, PUMP_INTERVAL_NANOS)) {
                if (pumpRenderOnlyFrame(mc)) {
                    HabiLoadingPerfMonitor.getInstance().recordWorldLoadSpinPump();
                    return;
                }
            }
            LockSupport.parkNanos(1_000_000L);
            return;
        }
        Thread.yield();
    }

    /** Records the start timestamp of {@code runTick(false)} inside {@code Minecraft#doWorldLoad}. */
    public static void markDoWorldLoadFrameStart() {
        doWorldLoadFrameStartNanos = System.nanoTime();
    }

    /**
     * Replaces the unconditional {@code Thread.sleep(16L)} inside {@code Minecraft#doWorldLoad},
     * avoiding double-waiting on VSync / FPS-limited frames while still pacing uncapped frames.
     */
    public static void onDoWorldLoadSleep(@Nullable Minecraft mc, long requestedMs) throws InterruptedException {
        if (mc != null && mc.isSameThread() && mc.isRunning()
                && HabiKineticUiController.isKineticLoadingEnabled()
                && HabiWorldEntrySession.getInstance().isActive()) {
            long start = doWorldLoadFrameStartNanos;
            long now = System.nanoTime();
            long elapsedMs = start > 0L ? Math.max(0L, (now - start) / 1_000_000L) : requestedMs;
            long remainingMs = computeRemainingWorldLoadSleepMs(requestedMs, elapsedMs, WORLD_LOAD_MIN_FRAME_MS);
            long savedMs = Math.max(0L, requestedMs - remainingMs);
            if (savedMs > 0L) {
                HabiLoadingPerfMonitor.getInstance().recordWorldLoadSleepSaved(savedMs);
            }
            if (remainingMs > 0L) {
                Thread.sleep(remainingMs);
            }
            return;
        }
        Thread.sleep(requestedMs);
    }

    /** Notes when a normal frame finished rendering so pumping waits a full frame interval. */
    public static void markNormalFrameRendered() {
        lastPumpNanos = System.nanoTime();
    }

    // ── Pure Testable Policy Helpers ─────────────────────────────────────────

    static boolean shouldTimeSliceForState(boolean kineticEnabled, boolean bootOverlayActive, boolean worldEntryScreenActive) {
        return kineticEnabled && (bootOverlayActive || worldEntryScreenActive);
    }

    static boolean shouldCapCatchupTicksForState(boolean kineticEnabled, boolean bootOverlayActive, boolean worldEntryScreenActive) {
        return kineticEnabled && (bootOverlayActive || worldEntryScreenActive);
    }

    static boolean shouldPumpBootLoopForState(boolean kineticEnabled, boolean bootOverlayActive, boolean inWorldLevelNull,
                                              boolean inFramePumpState, boolean inGameRendererState) {
        return kineticEnabled && bootOverlayActive && inWorldLevelNull && !inFramePumpState && !inGameRendererState;
    }

    static boolean shouldPumpNow(long nowNanos, long prevPumpNanos, long intervalNanos) {
        return prevPumpNanos == 0L || (nowNanos - prevPumpNanos) >= intervalNanos;
    }

    static long computeRemainingWorldLoadSleepMs(long requestedMs, long frameElapsedMs, long minFrameMs) {
        if (requestedMs <= 0L) return 0L;
        long target = Math.min(requestedMs, Math.max(1L, minFrameMs));
        return Math.max(0L, target - Math.max(0L, frameElapsedMs));
    }

    // ── Internal Helpers ─────────────────────────────────────────────────────

    private static boolean isActiveWorldEntryScreen(@Nullable Screen screen) {
        return screen != null
                && HabiWorldEntrySession.getInstance().isActive()
                && HabiWorldEntrySession.isAllowedEntryScreen(screen);
    }

    private static boolean canPumpWorldEntry(Minecraft mc) {
        return mc.isSameThread()
                && mc.isRunning()
                && !inFramePump
                && !isInGameRendererRender()
                && HabiKineticUiController.isKineticLoadingEnabled()
                && isActiveWorldEntryScreen(mc.screen);
    }

    private static boolean canPumpInitialBootLoop(@Nullable Minecraft mc) {
        if (mc == null || !mc.isSameThread() || !mc.isRunning()) return false;
        return shouldPumpBootLoopForState(
                HabiKineticUiController.isKineticLoadingEnabled(),
                mc.getOverlay() instanceof StarRailLoadingOverlay,
                mc.level == null,
                inFramePump,
                isInGameRendererRender()
        );
    }

    private static boolean pumpRenderOnlyFrame(Minecraft mc) {
        if (!(mc instanceof HabiMinecraftRunTickAccess runTickAccess)) return false;
        inFramePump = true;
        try {
            runTickAccess.habitrain$invokeRunTick(false);
            return true;
        } finally {
            inFramePump = false;
            lastPumpNanos = System.nanoTime();
        }
    }
}
