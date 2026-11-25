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
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        atlas.ensurePrepared();
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
        return session.isReadyStandby();
    }

    /** Renders a standalone resource-reload overlay (e.g. F3+T mid-game) with real progress and fade-out. */
    public void renderStandaloneReloadOverlay(Minecraft mc, HabiRealProgressTracker tracker,
                                              ReloadInstance reload, boolean forceComplete,
                                              double elapsedSec, double dt, double fadeAlpha) {
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        atlas.ensurePrepared();

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
    }

    // ── Title Screen (Station 00 READY -> 1.20s Split -> Station 01 Menu) ────

    public void onTitleScreenInit(Minecraft mc) {
        HabiTitleTelemetry.refreshAsync(mc);
        HabiBootSession.getInstance().ensureTitleScreenEntered(mc);
    }

    public void renderTitleScreen(Minecraft mc, List<HabiStationSceneRenderer.MenuItemData> entries,
                                  int mouseX, int mouseY) {
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        atlas.ensurePrepared();
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
        atlas.ensurePrepared();
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

    // ── World Generation & Receiving Level Unified Kinetic Screens ───────────

    public void renderWorldGenerationScreen(Minecraft mc, HabiRealProgressTracker tracker,
                                            @Nullable StoringChunkProgressListener listener,
                                            boolean resolving, double resolveT,
                                            double elapsedSec, double dt, double alpha) {
        HabiKineticAtlas atlas = HabiKineticAtlas.getInstance();
        atlas.ensurePrepared();

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
        atlas.ensurePrepared();

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

    public boolean onTitleKeyPressed(int keyCode, List<HabiStationSceneRenderer.MenuItemData> entries) {
        HabiBootSession session = HabiBootSession.getInstance();
        if (keyCode == GLFW.GLFW_KEY_M) {
            session.toggleSoundMute();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
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
                stationRenderer.setSelectedIndex(stationRenderer.getSelectedIndex() - 1, entries.size(), true);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_S) {
                stationRenderer.setSelectedIndex(stationRenderer.getSelectedIndex() + 1, entries.size(), true);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                stationRenderer.triggerSelected(entries);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                return true;
            }
        }
        return false;
    }

    public boolean onTitleCharTyped(char codePoint) {
        if (codePoint == 'm' || codePoint == 'M' || codePoint == 'r' || codePoint == 'R') {
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
        HabiBootSession session = HabiBootSession.getInstance();
        if (session.isReadyStandby()) {
            session.triggerBoarding();
            return true;
        }
        if (!session.isStationInteractive()) {
            return true;
        }
        if (button != 0) return false;
        gfx.updateViewport(mc.getWindow());
        double vx = gfx.guiToVirtualX(mc, mouseX);
        double vy = gfx.guiToVirtualY(mc, mouseY);
        return stationRenderer.handleMouseClick(vx, vy, entries);
    }

    public boolean onTitleMouseScrolled(Minecraft mc, double mouseX, double mouseY, double verticalAmount) {
        if (!HabiBootSession.getInstance().isStationInteractive()) return true;
        gfx.updateViewport(mc.getWindow());
        double vx = gfx.guiToVirtualX(mc, mouseX);
        double vy = gfx.guiToVirtualY(mc, mouseY);
        return stationRenderer.handleMouseScroll(vx, vy, verticalAmount);
    }

    public boolean onTitleMouseDragged(Minecraft mc, int button, double dragY) {
        if (button != 0 || !HabiBootSession.getInstance().isStationInteractive()) return false;
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
}
