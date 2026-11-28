package com.habitrain.core.client.title;

import com.habitrain.core.client.config.ClientVisualPreferences;
import io.wifi.starrailexpress.SREClientConfig;
import net.exmo.sre.loading.StarRailExpressTitleScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Render-thread controller coordinating the HabiTrain Kinetic UI v3 across:
 * <ul>
 *   <li>{@code StarRailLoadingOverlay} (client startup &amp; resource pack reload)</li>
 *   <li>{@code StarRailExpressTitleScreen} (100% READY lock -> 1.20s split -> Station 01 11-button menu)</li>
 *   <li>{@code TrainLoadingScreen} &amp; {@code SREReceivingLevelScreen} (world generation &amp; terrain entry)</li>
 *   <li>Title child screens (cohesive Station 01 2.5D background)</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class HabiKineticUiController {

    private static final HabiKineticUiController INSTANCE = new HabiKineticUiController();

    public static HabiKineticUiController getInstance() {
        return INSTANCE;
    }

    private final HabiKineticGfx gfx = new HabiKineticGfx();
    private final HabiZeroStationRenderer zeroRenderer = new HabiZeroStationRenderer();
    private final HabiStationSceneRenderer stationRenderer = new HabiStationSceneRenderer();
    private final HabiWorldEntryRenderer worldEntryRenderer = new HabiWorldEntryRenderer();
    private List<HabiStationSceneRenderer.MenuItemData> cachedTitleEntries = List.of();
    private boolean worldEntryRenderedThisFrame;

    private HabiKineticUiController() {}

    public static boolean isKineticLoadingEnabled() {
        if (!ClientVisualPreferences.isKineticBootUiEnabled()) return false;
        try {
            return !SREClientConfig.instance().disableCustomLoadingScreen;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isKineticTitleEnabled() {
        if (!ClientVisualPreferences.isKineticBootUiEnabled()) return false;
        try {
            return !SREClientConfig.instance().disableCustomTitleScreen;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isInitialBootOverlay(Minecraft mc) {
        return isInitialBootOverlay(mc, false);
    }

    public static boolean isInitialBootOverlay(Minecraft mc, boolean overlayFadeIn) {
        if (mc == null) return false;
        Screen s = mc.screen;
        boolean bootScreenMatch = s == null
                || s instanceof GenericMessageScreen
                || s instanceof StarRailExpressTitleScreen
                || s instanceof TitleScreen;
        return shouldTreatAsInitialBoot(
                mc.level != null,
                HabiBootSession.getInstance().hasArrivedOnce(),
                overlayFadeIn,
                mc.isGameLoadFinished(),
                bootScreenMatch
        );
    }

    static boolean shouldTreatAsInitialBoot(
            boolean inWorld,
            boolean arrivedOnce,
            boolean overlayFadeIn,
            boolean gameLoadFinished,
            boolean bootScreenMatch
    ) {
        if (inWorld || arrivedOnce) return false;
        if (!gameLoadFinished) return true;
        return !overlayFadeIn && bootScreenMatch;
    }

    // ── Startup / Resource Reload Overlay ────────────────────────────────────

    /**
     * Renders the initial-boot Station 00 overlay driven by {@link HabiBootSession}.
     *
     * @return {@code true} once the overlay is 100% ready and can seamlessly hand off to TitleScreen
     */
    public boolean renderInitialBootOverlay(Minecraft mc, ReloadInstance reload, int mouseX, int mouseY) {
        long frameStartNanos = System.nanoTime();
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        double uploadMs = atlas.ensureZeroPrepared();
        uploadMs += atlas.pumpStation01Uploads(1.2);
        HabiBootSession session = HabiBootSession.getInstance();

        updateSessionPointer(mc, mouseX, mouseY);
        HabiRealProgressTracker.LoadSnapshot snap = session.stepFrame(mc, atlas.isReady());
        double t = session.getTimelineSeconds();
        HabiBootAudioEngine.Telemetry tel = HabiBootAudioEngine.getInstance().getTelemetry(t, session.getExitAt());

        gfx.beginFrame(mc);
        try {
            var params = new HabiZeroStationRenderer.RenderParams(
                    t, session.getExitAt(), session.getPointerX(), session.getPointerY(),
                    snap, session.isQuantizePending(), ClientVisualPreferences.isKineticUiAutoEnter(),
                    tel, false, null
            );
            zeroRenderer.renderZeroLayer(gfx, atlas, params, true);
        } finally {
            gfx.endFrame();
        }
        double selfRenderMs = (System.nanoTime() - frameStartNanos) / 1_000_000.0;
        HabiLoadingPerfMonitor.getInstance().recordFrame(
                HabiLoadingPerfMonitor.SessionKind.GAME_BOOT,
                session.getPhase().name(),
                t,
                snap.progress(),
                selfRenderMs,
                uploadMs,
                gfx.getLastFrameDrawCalls(),
                snap.statusCounter()
        );
        return session.isReadyStandby();
    }

    /** Renders a standalone resource-reload overlay (e.g. F3+T mid-game) with real progress and fade-out. */
    public void renderStandaloneReloadOverlay(Minecraft mc, HabiRealProgressTracker tracker,
                                              ReloadInstance reload, boolean forceComplete,
                                              double elapsedSec, double dt, double fadeAlpha) {
        long frameStartNanos = System.nanoTime();
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        double uploadMs = atlas.ensureZeroPrepared();

        // Advance visual timeline starting past initial boot fade so F3+T feels snappy
        double visualT = elapsedSec + 1.20;
        HabiRealProgressTracker.LoadSnapshot snap = tracker.updateResourceReload(
                visualT, dt, reload, forceComplete, atlas.isReady(), 1.0f, 1.8, false, false);

        gfx.beginFrame(mc);
        try {
            var params = new HabiZeroStationRenderer.RenderParams(
                    visualT, Double.POSITIVE_INFINITY, 0.0, 0.0,
                    snap, false, false, null, true,
                    "RESOURCE RELOAD   //   SYNCHRONIZING PACKS"
            );
            zeroRenderer.renderZeroLayer(gfx, atlas, params, false);
            if (fadeAlpha < 0.999) {
                gfx.fillRect(gfx.viewLeft() - 10, gfx.viewTop() - 10, gfx.viewWidth() + 20, gfx.viewHeight() + 20,
                        HabiKineticGfx.withAlpha(0x0E1017, 1.0 - fadeAlpha));
            }
        } finally {
            gfx.endFrame();
        }
        double selfRenderMs = (System.nanoTime() - frameStartNanos) / 1_000_000.0;
        HabiLoadingPerfMonitor.getInstance().recordFrame(
                HabiLoadingPerfMonitor.SessionKind.RESOURCE_RELOAD,
                forceComplete ? "FADE_OUT" : "RELOADING",
                elapsedSec,
                snap.progress(),
                selfRenderMs,
                uploadMs,
                gfx.getLastFrameDrawCalls(),
                snap.statusCounter()
        );
    }

    // ── Title Screen (Station 00 READY -> 1.20s Split -> Station 01 Menu) ────

    public void onTitleScreenInit(Minecraft mc, @Nullable Screen titleScreen) {
        HabiTitleTelemetry.refreshAsync(mc);
        HabiBootSession.getInstance().ensureTitleScreenEntered(mc);
        stationRenderer.onTitleScreenInit(mc, titleScreen);
        if (isKineticLoadingEnabled()) {
            // Quietly CPU-bake Act 1..3 tunnel sprites while idle on Station 01 so world entry never blocks frame 0
            HabiTunnelAtlas.getInstance().preloadAsync(HabiKineticAtlas.getInstance());
        }
    }

    public void onTitleScreenTick(Minecraft mc) {
        stationRenderer.onTitleScreenTick(mc);
    }

    public void onTitleScreenRemoved() {
        stationRenderer.onTitleScreenRemoved();
    }

    public void onWorldJoinedOrShutdown(boolean shutdownExecutor) {
        stationRenderer.onWorldJoinedOrShutdown(shutdownExecutor);
    }

    public void renderTitleScreen(Minecraft mc, List<HabiStationSceneRenderer.MenuItemData> entries,
                                  int mouseX, int mouseY) {
        this.cachedTitleEntries = entries != null ? entries : List.of();
        if (HabiWorldEntrySession.getInstance().isActive()) {
            renderWorldEntryFrame(mc, mouseX, mouseY, this.cachedTitleEntries, true);
            return;
        }
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        atlas.ensureStation01Prepared();
        HabiBootSession session = HabiBootSession.getInstance();

        updateSessionPointer(mc, mouseX, mouseY);
        HabiRealProgressTracker.LoadSnapshot snap = session.stepFrame(mc, atlas.isReady());
        double t = session.getTimelineSeconds();
        double exitAt = session.getExitAt();
        HabiBootAudioEngine.Telemetry tel = HabiBootAudioEngine.getInstance().getTelemetry(t, exitAt);

        gfx.beginFrame(mc);
        try {
            double mouseVx = gfx.guiToVirtualX(mc, mouseX);
            double mouseVy = gfx.guiToVirtualY(mc, mouseY);

            if (session.getPhase() == HabiBootSession.Phase.STATION_01) {
                double u = session.getStationElapsed();
                stationRenderer.renderStationScene(gfx, atlas, u, session.getPointerX(), session.getPointerY(), tel);
                stationRenderer.renderMainMenuOverlay(gfx, atlas, mc, entries, 1.0, mouseVx, mouseVy, true);
                return;
            }

            // BOOT_LOADING (replay), READY_STANDBY, or DEPARTING
            double e = t >= exitAt ? (t - exitAt) : -1.0;
            var params = new HabiZeroStationRenderer.RenderParams(
                    t, exitAt, session.getPointerX(), session.getPointerY(),
                    snap, session.isQuantizePending(), ClientVisualPreferences.isKineticUiAutoEnter(),
                    tel, false, null
            );

            if (e >= HabiBootSession.COVER_FULL) {
                // Curtain has covered screen: mount & animate live Station 01 underneath, then draw split curtain on top!
                double stationU = e - HabiBootSession.SPLIT_AT;
                double gameK = e < HabiBootSession.SPLIT_AT ? 0.0
                        : HabiKineticGfx.sat((e - (HabiBootSession.SPLIT_AT + 0.05)) / 0.72);
                stationRenderer.renderStationScene(gfx, atlas, stationU, session.getPointerX(), session.getPointerY(), tel);
                stationRenderer.renderMainMenuOverlay(gfx, atlas, mc, entries, gameK, mouseVx, mouseVy, false);
                zeroRenderer.renderExitPanels(gfx, atlas, e);
            } else {
                zeroRenderer.renderZeroLayer(gfx, atlas, params, true);
            }
        } finally {
            gfx.endFrame();
        }
    }

    /** Renders the live Station 01 2.5D background behind title child screens (Multiplayer/Singleplayer/Options). */
    public void renderChildScreenBackdrop(Minecraft mc) {
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        atlas.ensureStation01Prepared();
        HabiBootSession session = HabiBootSession.getInstance();
        HabiBootAudioEngine.getInstance().syncMinecraftVolumes(mc);
        double u = Math.max(2.0, session.getStationElapsed());
        HabiBootAudioEngine.Telemetry tel = HabiBootAudioEngine.getInstance()
                .getTelemetry(session.getTimelineSeconds(), session.getExitAt());

        gfx.beginFrame(mc);
        try {
            stationRenderer.renderChildBackdrop(gfx, atlas, u, tel);
        } finally {
            gfx.endFrame();
        }
    }

    // ── Unified 3-Act World-Entry Cutscene (START -> LOADING -> END) ─────────

    /** Renders the active world-entry cutscene on a loading/connect screen (marks screen pass done for this frame). */
    public void renderWorldEntryScreen(Minecraft mc, int mouseX, int mouseY) {
        renderWorldEntryFrame(mc, mouseX, mouseY, cachedTitleEntries, true);
    }

    /**
     * Renders the Act 3 Apple Portal Iris cutout &amp; arrival bloom directly over the live 3D Minecraft world
     * at the end of {@code GameRenderer.render}. Safe against double-rendering on the exact frame a loading
     * screen calls {@code onClose()}.
     */
    public void renderWorldEntryInWorldOverlay(Minecraft mc) {
        if (worldEntryRenderedThisFrame) {
            worldEntryRenderedThisFrame = false;
            return;
        }
        if (!HabiWorldEntrySession.getInstance().shouldRenderInWorldOverlay(mc)) return;
        int mouseX = 0;
        int mouseY = 0;
        if (mc != null && mc.getWindow() != null && mc.mouseHandler != null) {
            double sw = Math.max(1.0, mc.getWindow().getScreenWidth());
            double sh = Math.max(1.0, mc.getWindow().getScreenHeight());
            mouseX = (int) Math.round(mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / sw);
            mouseY = (int) Math.round(mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / sh);
        }
        renderWorldEntryFrame(mc, mouseX, mouseY, List.of(), false);
    }

    /** Clears the per-frame screen-render guard at the tail of {@code GameRenderer.render}. */
    public void endGameFrame() {
        worldEntryRenderedThisFrame = false;
    }

    private void renderWorldEntryFrame(Minecraft mc, int mouseX, int mouseY,
                                       List<HabiStationSceneRenderer.MenuItemData> menuEntries,
                                       boolean fromScreenPass) {
        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        if (!session.isActive() || mc == null) return;

        long frameStartNanos = System.nanoTime();
        updateWorldEntryPointer(mc, mouseX, mouseY);
        session.stepFrame(mc);
        if (!session.isActive()) {
            // Act 3 just completed & disposed atlases cleanly; exit immediately without re-creating atlases.
            if (fromScreenPass) worldEntryRenderedThisFrame = true;
            return;
        }

        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        double uploadMs = atlas.ensureStation01Prepared();
        HabiTunnelAtlas tunnelAtlas = HabiTunnelAtlas.getInstance();
        uploadMs += tunnelAtlas.ensureTierAAndPumpTierB(atlas, 1.5, 2);

        HabiBootSession boot = HabiBootSession.getInstance();
        HabiBootAudioEngine.Telemetry tel = HabiBootAudioEngine.getInstance()
                .getTelemetry(boot.getTimelineSeconds(), boot.getExitAt());

        gfx.beginFrame(mc);
        try {
            worldEntryRenderer.render(gfx, atlas, tunnelAtlas, session, tel);
            // Smoothly slide Station 01 left menu out across 0.00..0.38s of Act 1
            if (session.getPhase() == HabiWorldEntrySession.Phase.START && !menuEntries.isEmpty()) {
                double menuFade = session.getMenuFade();
                if (menuFade < 0.995) {
                    double mouseVx = gfx.guiToVirtualX(mc, mouseX);
                    double mouseVy = gfx.guiToVirtualY(mc, mouseY);
                    stationRenderer.renderMainMenuOverlay(gfx, atlas, mc, menuEntries, 1.0, mouseVx, mouseVy, false, menuFade);
                }
            }
        } finally {
            gfx.endFrame();
        }
        double selfRenderMs = (System.nanoTime() - frameStartNanos) / 1_000_000.0;
        String ctx = mc.screen != null ? mc.screen.getClass().getSimpleName() : "InWorld";
        HabiLoadingPerfMonitor.getInstance().recordFrame(
                HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY,
                session.getPhase().name(),
                session.getPhaseT(),
                session.getProgress(),
                selfRenderMs,
                uploadMs,
                gfx.getLastFrameDrawCalls(),
                ctx
        );
        if (fromScreenPass) {
            worldEntryRenderedThisFrame = true;
        }
    }

    // ── World Generation & Receiving Level Unified Kinetic Screens ───────────

    public void renderWorldGenerationScreen(Minecraft mc, HabiRealProgressTracker tracker,
                                            @Nullable StoringChunkProgressListener listener,
                                            boolean resolving, double resolveT,
                                            double elapsedSec, double dt, double alpha) {
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        atlas.ensureZeroPrepared();

        double visualT = elapsedSec + 1.25;
        HabiRealProgressTracker.LoadSnapshot snap = tracker.updateWorldGeneration(
                visualT, dt, listener, resolving, resolveT);

        gfx.beginFrame(mc);
        try {
            var params = new HabiZeroStationRenderer.RenderParams(
                    visualT, Double.POSITIVE_INFINITY, 0.0, 0.0,
                    snap, false, false, null, true,
                    "WORLD GENERATION   //   BUILDING SPAWN RAIL"
            );
            zeroRenderer.renderZeroLayer(gfx, atlas, params, false);
            if (alpha < 0.999) {
                gfx.fillRect(gfx.viewLeft() - 10, gfx.viewTop() - 10, gfx.viewWidth() + 20, gfx.viewHeight() + 20,
                        HabiKineticGfx.withAlpha(0x0E1017, 1.0 - alpha));
            }
        } finally {
            gfx.endFrame();
        }
    }

    public void renderReceivingLevelScreen(Minecraft mc, HabiRealProgressTracker tracker,
                                           boolean levelReceived, double arriveT, String reasonTag,
                                           double elapsedSec, double dt, double alpha) {
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        atlas.ensureZeroPrepared();

        double visualT = elapsedSec + 1.25;
        HabiRealProgressTracker.LoadSnapshot snap = tracker.updateReceivingLevel(
                visualT, dt, levelReceived, arriveT, reasonTag);

        gfx.beginFrame(mc);
        try {
            var params = new HabiZeroStationRenderer.RenderParams(
                    visualT, Double.POSITIVE_INFINITY, 0.0, 0.0,
                    snap, false, false, null, true,
                    "STATION ARRIVAL   //   " + reasonTag
            );
            zeroRenderer.renderZeroLayer(gfx, atlas, params, false);
            if (alpha < 0.999) {
                gfx.fillRect(gfx.viewLeft() - 10, gfx.viewTop() - 10, gfx.viewWidth() + 20, gfx.viewHeight() + 20,
                        HabiKineticGfx.withAlpha(0x0E1017, 1.0 - alpha));
            }
        } finally {
            gfx.endFrame();
        }
    }

    // ── Title Screen Input Handling ──────────────────────────────────────────

    public boolean onTitleKeyPressed(Minecraft mc, int keyCode, List<HabiStationSceneRenderer.MenuItemData> entries) {
        HabiBootSession session = HabiBootSession.getInstance();
        if (keyCode == GLFW.GLFW_KEY_M) {
            session.toggleSoundMute();
            return true;
        }
        if (HabiWorldEntrySession.getInstance().isActive()) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
            stationRenderer.resetToMainForReplay();
            session.beginReplay();
            return true;
        }
        if (session.isReadyStandby()) {
            session.triggerBoarding();
            return true;
        }
        if (session.isDeparting() || session.getPhase() == HabiBootSession.Phase.BOOT_LOADING) {
            return true;
        }
        if (session.isStationInteractive()) {
            if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_W) {
                stationRenderer.moveSelection(-1, entries, true);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_S) {
                stationRenderer.moveSelection(1, entries, true);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                stationRenderer.triggerSelected(mc, entries);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_F5 && stationRenderer.isInSubPanel()) {
                stationRenderer.refreshActiveSubPanel(mc);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE
                    || ((keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_LEFT) && stationRenderer.isInSubPanel())) {
                if (stationRenderer.isInSubPanel()) {
                    stationRenderer.returnToMain();
                }
                return true;
            }
        }
        return false;
    }

    public boolean onTitleCharTyped(char codePoint) {
        if (codePoint == 'm' || codePoint == 'M' || codePoint == 'r' || codePoint == 'R') {
            return true;
        }
        if (HabiWorldEntrySession.getInstance().isActive()) {
            return true;
        }
        HabiBootSession session = HabiBootSession.getInstance();
        if (session.isReadyStandby()) {
            session.triggerBoarding();
            return true;
        }
        return !session.isStationInteractive();
    }

    public boolean onTitleMouseClicked(Minecraft mc, double mouseX, double mouseY, int button,
                                       List<HabiStationSceneRenderer.MenuItemData> entries) {
        if (HabiWorldEntrySession.getInstance().isActive()) {
            return true;
        }
        HabiBootSession session = HabiBootSession.getInstance();
        if (session.isReadyStandby()) {
            session.triggerBoarding();
            return true;
        }
        if (!session.isStationInteractive()) {
            return true;
        }
        if (button != 0 && button != 3) return false;
        gfx.updateViewport(mc.getWindow());
        double vx = gfx.guiToVirtualX(mc, mouseX);
        double vy = gfx.guiToVirtualY(mc, mouseY);
        return stationRenderer.handleMouseClick(mc, vx, vy, button, entries);
    }

    public boolean onTitleMouseScrolled(Minecraft mc, double mouseX, double mouseY, double verticalAmount) {
        if (HabiWorldEntrySession.getInstance().isActive() || !HabiBootSession.getInstance().isStationInteractive()) return true;
        gfx.updateViewport(mc.getWindow());
        double vx = gfx.guiToVirtualX(mc, mouseX);
        double vy = gfx.guiToVirtualY(mc, mouseY);
        return stationRenderer.handleMouseScroll(vx, vy, verticalAmount);
    }

    public boolean onTitleMouseDragged(Minecraft mc, int button, double dragY) {
        if (button != 0 || HabiWorldEntrySession.getInstance().isActive()
                || !HabiBootSession.getInstance().isStationInteractive()) return false;
        gfx.updateViewport(mc.getWindow());
        double guiH = Math.max(1.0, mc.getWindow().getGuiScaledHeight());
        double dragVirtualY = (dragY / guiH) * gfx.viewHeight();
        return stationRenderer.handleMouseDrag(dragVirtualY);
    }

    public void onTitleMouseReleased(int button) {
        if (button == 0) {
            stationRenderer.handleMouseRelease();
        }
    }

    private void updateSessionPointer(Minecraft mc, int mouseX, int mouseY) {
        if (mc == null || mc.getWindow() == null) return;
        double guiW = Math.max(1.0, mc.getWindow().getGuiScaledWidth());
        double guiH = Math.max(1.0, mc.getWindow().getGuiScaledHeight());
        double nx = (mouseX / guiW) * 2.0 - 1.0;
        double ny = -((mouseY / guiH) * 2.0 - 1.0);
        HabiBootSession.getInstance().updatePointer(nx, ny);
    }

    private void updateWorldEntryPointer(Minecraft mc, int mouseX, int mouseY) {
        if (mc == null || mc.getWindow() == null) return;
        double guiW = Math.max(1.0, mc.getWindow().getGuiScaledWidth());
        double guiH = Math.max(1.0, mc.getWindow().getGuiScaledHeight());
        double nx = (mouseX / guiW) * 2.0 - 1.0;
        double ny = -((mouseY / guiH) * 2.0 - 1.0);
        HabiWorldEntrySession.getInstance().updatePointer(nx, ny);
    }
}
