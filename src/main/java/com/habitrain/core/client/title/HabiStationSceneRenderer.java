package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.habitrain.core.client.title.HabiKineticGfx.*;
import static com.habitrain.core.client.title.HabiStationListTransition.PanelMode;

/**
 * Station 01 interactive 2.5D night platform renderer &amp; left-side kinetic main menu
 * (all 11 real Minecraft/SRE/HabiTrain Core entries + smooth scroll + live dynamic telemetry card),
 * ported faithfully from {@code habitrain_logo_v3/station_scene.js} and {@code index.html}.
 */
@Environment(EnvType.CLIENT)
public final class HabiStationSceneRenderer {

    // ── Palette (matches station_scene.js PAL) ───────────────────────────────
    private static final int PAL_BG = 0xFF0E1017;
    private static final int PAL_PAPER = 0xFFECE9E2;
    private static final int PAL_PAPER_TXT = 0xFFF4F1EA;
    private static final int PAL_INK = 0xFF16171D;
    private static final int PAL_LINE = 0xB8ECE9E2;
    private static final int PAL_LINE_MID = 0x47ECE9E2;
    private static final int PAL_FAINT = 0x17ECE9E2;
    private static final int PAL_MUTE = 0xFF727687;
    private static final int PAL_RED = 0xFFE1243B;
    private static final int PAL_RED_BRIGHT = 0xFFF2354B;
    private static final int PAL_BLUE = 0xFF5B78FF;

    // ── 2.5D Stage layout ────────────────────────────────────────────────────
    private static final double HUB_X = 335.0, HUB_Y = -28.0, HUB_Z = 40.0;
    private static final double TRACK_R = 405.0, CAR_H = 17.0, CAR_GAP = 66.0;
    private static final double SPLIT_ANG = -0.42;

    private static final double[][] OCTA_V = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int[][] OCTA_E = {
            {0, 2}, {0, 3}, {0, 4}, {0, 5},
            {1, 2}, {1, 3}, {1, 4}, {1, 5},
            {2, 4}, {2, 5}, {3, 4}, {3, 5}
    };

    // ── Left interactive menu layout (virtual 1920x1080 space) ───────────────
    private static final double MENU_ITEM_H = 58.0;
    private static final double MENU_ITEM_GAP = 10.0;
    private static final double MENU_STRIDE = MENU_ITEM_H + MENU_ITEM_GAP;
    private static final double SUB_HEADER_H = 34.0;
    private static final double SUB_HEADER_GAP = 10.0;
    private static final double SUB_HEADER_TOTAL = SUB_HEADER_H + SUB_HEADER_GAP;
    private static final double EMPTY_BANNER_H = 42.0;
    private static final double EMPTY_BANNER_TOTAL = EMPTY_BANNER_H + MENU_ITEM_GAP;

    public record MenuItemData(Component text, Runnable action) {}

    public record HitRect(double x, double y, double w, double h) {
        public boolean contains(double vx, double vy) {
            return vx >= x && vx <= x + w && vy >= y && vy <= y + h;
        }
    }

    private static final class PanelScrollState {
        int selectedIndex = 0;
        double scrollOffset = 0.0;
        double targetScroll = 0.0;
        double maxScroll = 0.0;
        float[] hoverAnim = new float[32];

        float stepHover(int idx, boolean selected, double dt) {
            if (idx < 0) return 0f;
            if (idx >= hoverAnim.length) {
                hoverAnim = Arrays.copyOf(hoverAnim, Math.max(idx + 8, hoverAnim.length * 2));
            }
            float target = selected ? 1.0f : 0.0f;
            hoverAnim[idx] += (float) ((target - hoverAnim[idx]) * Math.min(1.0, dt * 15.0));
            return hoverAnim[idx];
        }

        void stepScroll(double dt) {
            targetScroll = clamp(targetScroll, 0.0, maxScroll);
            scrollOffset += (targetScroll - scrollOffset) * Math.min(1.0, dt * 14.0);
            if (Math.abs(targetScroll - scrollOffset) < 0.25) scrollOffset = targetScroll;
        }

        void reset() {
            selectedIndex = 0;
            scrollOffset = 0.0;
            targetScroll = 0.0;
            maxScroll = 0.0;
            Arrays.fill(hoverAnim, 0f);
        }
    }

    private final double[] segScratch = new double[1024];
    private final double[] polyScratch = new double[512];

    // Panel transition & live server/world sub-panel state
    private final HabiStationListTransition transition = new HabiStationListTransition();
    private final HabiTitleSubPanelData subData = new HabiTitleSubPanelData();
    private final PanelScrollState mainScroll = new PanelScrollState();
    private final PanelScrollState mpScroll = new PanelScrollState();
    private final PanelScrollState spScroll = new PanelScrollState();
    private @Nullable PanelMode pendingReturnPanel = null;
    private @Nullable Screen activeTitleScreen = null;
    private boolean screenRemovedSinceInit = true;
    private int lastMainEntryCount = 11;

    // Interactive menu state
    private boolean draggingScrollbar = false;
    private final List<HitRect> itemHits = new ArrayList<>();
    private HitRect menuViewportRect = new HitRect(0, 0, 0, 0);
    private HitRect activeListViewportRect = new HitRect(0, 0, 0, 0);
    private HitRect scrollbarRect = new HitRect(0, 0, 0, 0);
    private HitRect subBackBtnRect = new HitRect(0, 0, 0, 0);
    private HitRect subRefreshBtnRect = new HitRect(0, 0, 0, 0);
    private HitRect soundBtnRect = new HitRect(0, 0, 0, 0);
    private HitRect replayBtnRect = new HitRect(0, 0, 0, 0);
    private double lastMouseVx = -9999.0, lastMouseVy = -9999.0;
    private double uiClockSec = 0.0;
    private long lastUiFrameNanos = System.nanoTime();

    public PanelMode getActivePanel() {
        return transition.getActivePanel();
    }

    public boolean isInSubPanel() {
        return transition.isInSubPanel();
    }

    public void onTitleScreenInit(Minecraft mc, @Nullable Screen titleScreen) {
        boolean isResize = !screenRemovedSinceInit && titleScreen != null && titleScreen == activeTitleScreen;
        this.activeTitleScreen = titleScreen;
        this.screenRemovedSinceInit = false;
        if (isResize) {
            return;
        }
        if (pendingReturnPanel != null && pendingReturnPanel != PanelMode.MAIN) {
            PanelMode restore = pendingReturnPanel;
            pendingReturnPanel = null;
            transition.snapTo(restore);
            if (restore == PanelMode.MULTIPLAYER) {
                subData.beginMultiplayerLoad(mc);
            } else if (restore == PanelMode.SINGLEPLAYER) {
                subData.beginSingleplayerLoad(mc);
            }
            return;
        }
        pendingReturnPanel = null;
        transition.snapTo(PanelMode.MAIN);
        subData.stopMultiplayerNetworking();
    }

    public void onTitleScreenTick(@Nullable Minecraft mc) {
        if (transition.getActivePanel() == PanelMode.MULTIPLAYER
                || transition.getOutgoingPanel() == PanelMode.MULTIPLAYER) {
            subData.tickMultiplayer(mc);
        }
    }

    public void onTitleScreenRemoved() {
        screenRemovedSinceInit = true;
        draggingScrollbar = false;
        subData.stopMultiplayerNetworking();
    }

    public void onWorldJoinedOrShutdown(boolean shutdownExecutor) {
        pendingReturnPanel = null;
        activeTitleScreen = null;
        screenRemovedSinceInit = true;
        draggingScrollbar = false;
        transition.snapTo(PanelMode.MAIN);
        mpScroll.reset();
        spScroll.reset();
        if (shutdownExecutor) {
            subData.shutdown();
        } else {
            subData.stopMultiplayerNetworking();
        }
    }

    public void resetToMainForReplay() {
        pendingReturnPanel = null;
        draggingScrollbar = false;
        transition.snapTo(PanelMode.MAIN);
        subData.stopMultiplayerNetworking();
    }

    private PanelScrollState scrollStateFor(PanelMode mode) {
        return switch (mode) {
            case MAIN -> mainScroll;
            case MULTIPLAYER -> mpScroll;
            case SINGLEPLAYER -> spScroll;
        };
    }

    private int getActiveItemCount(List<MenuItemData> mainEntries) {
        return switch (transition.getActivePanel()) {
            case MAIN -> mainEntries.size();
            case MULTIPLAYER -> subData.getServerEntries().size() + 1; // +1 for bottom "编辑" fallback
            case SINGLEPLAYER -> subData.getWorldEntries().size() + 1; // +1 for bottom "编辑" fallback
        };
    }

    public int getSelectedIndex() {
        return scrollStateFor(transition.getActivePanel()).selectedIndex;
    }

    public void moveSelection(int delta, List<MenuItemData> mainEntries, boolean playSfx) {
        int count = getActiveItemCount(mainEntries);
        if (count <= 0) return;
        PanelMode active = transition.getActivePanel();
        PanelScrollState st = scrollStateFor(active);
        int next = Math.floorMod(st.selectedIndex + delta, count);
        if (next != st.selectedIndex) {
            st.selectedIndex = next;
            if (playSfx) HabiBootAudioEngine.getInstance().playHover(st.selectedIndex);
            ensureSelectedVisible(active, st);
        }
    }

    public void setSelectedIndex(int idx, int count, boolean playSfx) {
        if (count <= 0) return;
        PanelMode active = transition.getActivePanel();
        PanelScrollState st = scrollStateFor(active);
        int next = Math.floorMod(idx, count);
        if (next != st.selectedIndex) {
            st.selectedIndex = next;
            if (playSfx) HabiBootAudioEngine.getInstance().playHover(st.selectedIndex);
            ensureSelectedVisible(active, st);
        }
    }

    private void ensureSelectedVisible(PanelMode mode, PanelScrollState st) {
        double viewH = activeListViewportRect.h();
        if (viewH <= 0) return;
        double topPad = emptyBannerHeightFor(mode);
        double itemTop = topPad + st.selectedIndex * MENU_STRIDE;
        double itemBot = itemTop + MENU_ITEM_H;
        if (itemTop < st.targetScroll) {
            st.targetScroll = itemTop;
        } else if (itemBot > st.targetScroll + viewH) {
            st.targetScroll = itemBot - viewH;
        }
        st.targetScroll = clamp(st.targetScroll, 0.0, st.maxScroll);
    }

    private double emptyBannerHeightFor(PanelMode mode) {
        if (mode == PanelMode.MULTIPLAYER && subData.getServerEntries().isEmpty()) {
            return EMPTY_BANNER_TOTAL;
        }
        if (mode == PanelMode.SINGLEPLAYER && subData.getWorldEntries().isEmpty()) {
            return EMPTY_BANNER_TOTAL;
        }
        return 0.0;
    }

    public boolean handleMouseScroll(double vx, double vy, double verticalAmount) {
        PanelScrollState st = scrollStateFor(transition.getActivePanel());
        if (st.maxScroll <= 0.0) return false;
        if (menuViewportRect.contains(vx, vy) || scrollbarRect.contains(vx, vy)) {
            st.targetScroll = clamp(st.targetScroll - verticalAmount * MENU_STRIDE * 1.15, 0.0, st.maxScroll);
            return true;
        }
        return false;
    }

    public boolean handleMouseDrag(double dragVirtualY) {
        PanelScrollState st = scrollStateFor(transition.getActivePanel());
        if (!draggingScrollbar || st.maxScroll <= 0.0 || scrollbarRect.h() <= 20.0) return false;
        double ratio = dragVirtualY / Math.max(1.0, scrollbarRect.h() - 40.0);
        st.targetScroll = clamp(st.targetScroll + ratio * st.maxScroll, 0.0, st.maxScroll);
        st.scrollOffset = st.targetScroll;
        return true;
    }

    public void handleMouseRelease() {
        draggingScrollbar = false;
    }

    /** Returns from a Multiplayer/Singleplayer sub-panel back to the main menu with reverse slide animation. */
    public boolean returnToMain() {
        if (!transition.isInSubPanel()) return false;
        PanelMode leaving = transition.getActivePanel();
        int outRows = leaving == PanelMode.MULTIPLAYER
                ? Math.max(2, subData.getServerEntries().size() + 2)
                : Math.max(2, subData.getWorldEntries().size() + 2);
        int inRows = Math.max(1, lastMainEntryCount);
        subData.stopMultiplayerNetworking();
        draggingScrollbar = false;
        transition.transitionTo(PanelMode.MAIN, outRows, inRows);
        HabiBootAudioEngine.getInstance().playBack();
        return true;
    }

    /** Refreshes the currently active Multiplayer or Singleplayer sub-panel asynchronously. */
    public boolean refreshActiveSubPanel(@Nullable Minecraft mc) {
        PanelMode active = transition.getActivePanel();
        if (active == PanelMode.MULTIPLAYER) {
            subData.beginMultiplayerLoad(mc);
            HabiBootAudioEngine.getInstance().playHover(4);
            return true;
        }
        if (active == PanelMode.SINGLEPLAYER) {
            subData.beginSingleplayerLoad(mc);
            HabiBootAudioEngine.getInstance().playHover(4);
            return true;
        }
        return false;
    }

    private void enterSubPanel(@Nullable Minecraft mc, PanelMode target, int mainEntryCount) {
        if (target == PanelMode.MAIN) return;
        this.lastMainEntryCount = Math.max(1, mainEntryCount);
        int outRows = Math.max(1, Math.min(8, mainEntryCount));
        int inRows;
        if (target == PanelMode.MULTIPLAYER) {
            mpScroll.reset();
            subData.beginMultiplayerLoad(mc);
            inRows = Math.max(3, Math.min(8, subData.getServerEntries().size() + 2));
        } else {
            spScroll.reset();
            subData.beginSingleplayerLoad(mc);
            inRows = Math.max(3, Math.min(8, subData.getWorldEntries().size() + 2));
        }
        draggingScrollbar = false;
        transition.transitionTo(target, outRows, inRows);
    }

    /**
     * Handles a mouse click in virtual 1920x1080 coordinates.
     *
     * @return {@code true} if consumed by a menu button, sub-panel control, scrollbar, or toolbar control
     */
    public boolean handleMouseClick(@Nullable Minecraft mc, double vx, double vy, int button, List<MenuItemData> entries) {
        // Mouse side Back button returns to main menu from sub-panels
        if (button == 3 && transition.isInSubPanel()) {
            return returnToMain();
        }
        if (button != 0) return false;

        if (soundBtnRect.contains(vx, vy)) {
            HabiBootSession.getInstance().toggleSoundMute();
            HabiBootAudioEngine.getInstance().playHover(2);
            return true;
        }
        if (replayBtnRect.contains(vx, vy)) {
            resetToMainForReplay();
            HabiBootAudioEngine.getInstance().playSelect();
            HabiBootSession.getInstance().beginReplay();
            return true;
        }
        if (transition.isInSubPanel()) {
            if (subBackBtnRect.contains(vx, vy)) {
                return returnToMain();
            }
            if (subRefreshBtnRect.contains(vx, vy)) {
                return refreshActiveSubPanel(mc);
            }
        }
        PanelScrollState activeScroll = scrollStateFor(transition.getActivePanel());
        if (activeScroll.maxScroll > 0.0 && scrollbarRect.contains(vx, vy)) {
            double prog = sat((vy - scrollbarRect.y()) / Math.max(1.0, scrollbarRect.h()));
            activeScroll.targetScroll = prog * activeScroll.maxScroll;
            draggingScrollbar = true;
            return true;
        }
        if (transition.canAcceptItemClick() && activeListViewportRect.contains(vx, vy)) {
            int count = getActiveItemCount(entries);
            for (int i = 0; i < Math.min(itemHits.size(), count); i++) {
                if (itemHits.get(i).contains(vx, vy)) {
                    activeScroll.selectedIndex = i;
                    triggerActiveItem(mc, entries, i);
                    return true;
                }
            }
        }
        return false;
    }

    public void triggerSelected(@Nullable Minecraft mc, List<MenuItemData> entries) {
        if (!transition.canAcceptItemClick()) return;
        int count = getActiveItemCount(entries);
        if (count <= 0) return;
        PanelScrollState activeScroll = scrollStateFor(transition.getActivePanel());
        int idx = Math.max(0, Math.min(count - 1, activeScroll.selectedIndex));
        triggerActiveItem(mc, entries, idx);
    }

    private void triggerActiveItem(@Nullable Minecraft mc, List<MenuItemData> entries, int idx) {
        PanelMode active = transition.getActivePanel();
        if (active == PanelMode.MAIN) {
            if (idx < 0 || idx >= entries.size()) return;
            MenuItemData item = entries.get(idx);
            HabiBootAudioEngine.getInstance().playSelect();
            if (isMultiplayerEntry(item.text())) {
                enterSubPanel(mc, PanelMode.MULTIPLAYER, entries.size());
            } else if (isSingleplayerEntry(item.text())) {
                enterSubPanel(mc, PanelMode.SINGLEPLAYER, entries.size());
            } else {
                item.action().run();
            }
            return;
        }
        if (active == PanelMode.MULTIPLAYER) {
            List<HabiTitleSubPanelData.ServerEntry> servers = subData.getServerEntries();
            if (idx >= 0 && idx < servers.size()) {
                HabiTitleSubPanelData.ServerEntry server = servers.get(idx);
                HabiBootAudioEngine.getInstance().playSelect();
                pendingReturnPanel = PanelMode.MULTIPLAYER;
                if (HabiKineticUiController.isKineticLoadingEnabled()
                        && mc != null && mc.allowsMultiplayer()
                        && server.data() != null && server.data().ip != null && !server.data().ip.isBlank()) {
                    HabiBootSession boot = HabiBootSession.getInstance();
                    String routeSub = server.isLan()
                            ? ("LAN EXPRESS // " + server.address())
                            : ("MULTIPLAYER // " + server.address());
                    HabiWorldEntrySession.getInstance().beginFromStation(
                            boot.getStationElapsed(), boot.getPointerX(), boot.getPointerY(),
                            server.displayName(), routeSub,
                            () -> {
                                if (!subData.joinServer(mc, activeTitleScreen, server)) {
                                    pendingReturnPanel = null;
                                    HabiWorldEntrySession.getInstance().abort(mc, true);
                                    HabiBootAudioEngine.getInstance().playBack();
                                }
                            },
                            false
                    );
                } else if (!subData.joinServer(mc, activeTitleScreen, server)) {
                    pendingReturnPanel = null;
                    HabiBootAudioEngine.getInstance().playBack();
                }
            } else {
                // Bottom fallback "编辑" button -> open native JoinMultiplayerScreen
                HabiBootAudioEngine.getInstance().playSelect();
                pendingReturnPanel = PanelMode.MULTIPLAYER;
                subData.openNativeMultiplayerEditor(mc, activeTitleScreen);
            }
            return;
        }
        if (active == PanelMode.SINGLEPLAYER) {
            List<HabiTitleSubPanelData.WorldEntry> worlds = subData.getWorldEntries();
            if (idx >= 0 && idx < worlds.size()) {
                HabiTitleSubPanelData.WorldEntry world = worlds.get(idx);
                if (!world.playable()) {
                    HabiBootAudioEngine.getInstance().playBack();
                    return;
                }
                HabiBootAudioEngine.getInstance().playSelect();
                pendingReturnPanel = PanelMode.SINGLEPLAYER;
                if (HabiKineticUiController.isKineticLoadingEnabled()
                        && mc != null && !world.symlink() && !world.backupRecommended()) {
                    HabiBootSession boot = HabiBootSession.getInstance();
                    HabiWorldEntrySession.getInstance().beginFromStation(
                            boot.getStationElapsed(), boot.getPointerX(), boot.getPointerY(),
                            world.displayName(), "LOCAL WORLD // " + world.gameModeTag(),
                            () -> {
                                if (!subData.joinWorld(mc, activeTitleScreen, world)) {
                                    pendingReturnPanel = null;
                                    HabiWorldEntrySession.getInstance().abort(mc, true);
                                    HabiBootAudioEngine.getInstance().playBack();
                                }
                            },
                            true
                    );
                } else if (!subData.joinWorld(mc, activeTitleScreen, world)) {
                    pendingReturnPanel = null;
                    HabiBootAudioEngine.getInstance().playBack();
                }
            } else {
                // Bottom fallback "编辑" button -> open native SelectWorldScreen
                HabiBootAudioEngine.getInstance().playSelect();
                pendingReturnPanel = PanelMode.SINGLEPLAYER;
                subData.openNativeSingleplayerEditor(mc, activeTitleScreen);
            }
        }
    }

    static boolean isMultiplayerEntry(@Nullable Component text) {
        if (text == null) return false;
        if (text.getContents() instanceof TranslatableContents tc && "menu.sre.multiplayer".equals(tc.getKey())) {
            return true;
        }
        return text.getString().contains("多人");
    }

    static boolean isSingleplayerEntry(@Nullable Component text) {
        if (text == null) return false;
        if (text.getContents() instanceof TranslatableContents tc && "menu.sre.singleplayer".equals(tc.getKey())) {
            return true;
        }
        return text.getString().contains("单人");
    }

    // ── 2.5D Station 01 Background Canvas (station_scene.js 1:1 port) ────────

    public void renderStationScene(HabiKineticGfx gfx, HabiKineticAtlas atlas, double u,
                                   double px, double py, @Nullable HabiBootAudioEngine.Telemetry tel) {
        double uPos = Math.max(0.0, u);
        double beatFrac = tel != null ? tel.beatFrac() : ((uPos / 0.6) % 1.0);
        double bass = tel != null ? tel.bass() : 0.35;

        double settle = eOutExpo(sat(uPos / 1.15));
        double beatPulse = Math.exp(-beatFrac * 5.2) * Math.max(0.15, bass);
        double rx = lerp(0.14, 0.04, settle) + 0.024 * Math.sin(uPos * 0.34 + 0.5) - py * 0.045;
        double ry = lerp(-0.24, -0.06, settle) + 0.065 * Math.sin(uPos * 0.40) + px * 0.075;
        double rz = lerp(-0.055, 0.0, settle);
        double zoom = lerp(1.26, 1.0, settle) + 0.008 * beatPulse;
        Cam cam = new Cam(rx, ry, rz, zoom);

        // 1) Deep-space ink-blue ambient background
        gfx.drawTexturedRect(atlas.stationBgTex.getId(),
                gfx.viewLeft() - 20, gfx.viewTop() - 20, gfx.viewWidth() + 40, gfx.viewHeight() + 40,
                0xFFFFFFFF, BlendMode.NORMAL);

        // 2) Far crosshair grid + giant ghost "01"
        drawBgGrid(gfx, cam);
        drawGhost01(gfx, atlas, cam, uPos, settle);

        // 3) Seamless diagonal red continuity beam (matches -0.42 rad split seam)
        drawSeamContinuityRails(gfx, uPos, settle, beatPulse);

        // 4) Station 01 star-ring + 3D wireframe octahedron beacon
        drawStationRingAndOcta(gfx, cam, uPos, settle, beatPulse);

        // 5) Elevated 2.5D viaduct back -> apple beacon -> viaduct front + 3-car train
        double w = TAU / 4.8;
        double thT = -0.75 + w * (uPos + (3.5 / 2.6) * (1.0 - Math.exp(-2.6 * uPos)));
        double speedMul = 1.0 + 3.5 * Math.exp(-2.6 * uPos);

        drawViaduct(gfx, atlas, cam, uPos, settle, thT, speedMul, false);
        drawAppleBeacon(gfx, atlas, cam, uPos, settle, beatPulse);
        drawViaduct(gfx, atlas, cam, uPos, settle, thT, speedMul, true);

        // 6) Floating extruded station plates (STN.01 习惯中枢 / ROUTE 苹果园环线)
        drawFloatingPlates(gfx, atlas, cam, uPos);

        // 7) Station 01 HUD chrome (4 corners, top-right 100 BPM EQ, bottom-right clock, right vertical stamp)
        drawStationHud(gfx, atlas, uPos, settle, tel);

        // 8) Subtle film grain
        double f = Math.floor(uPos * 24.0);
        gfx.drawTiledTexture(atlas.noiseTex.getId(),
                gfx.viewLeft() - 10, gfx.viewTop() - 10, gfx.viewWidth() + 20, gfx.viewHeight() + 20,
                256.0, hash(f), hash(f + 5.1), withAlpha(0xFFFFFF, 0.05), BlendMode.SCREEN);
    }

    /** Renders the live Station 01 2.5D scene behind child screens (Multiplayer/Options/Worlds) with a scrim. */
    public void renderChildBackdrop(HabiKineticGfx gfx, HabiKineticAtlas atlas, double u,
                                    @Nullable HabiBootAudioEngine.Telemetry tel) {
        renderStationScene(gfx, atlas, u, 0.0, 0.0, tel);
        gfx.fillRect(gfx.viewLeft() - 10, gfx.viewTop() - 10, gfx.viewWidth() + 20, gfx.viewHeight() + 20, 0x7A0B0D14);
    }

    // ── Interactive Left Main Menu & Real-Data Telemetry Card ────────────────

    public void renderMainMenuOverlay(HabiKineticGfx gfx, HabiKineticAtlas atlas, Minecraft mc,
                                      List<MenuItemData> entries, double gameK, double mouseVx, double mouseVy,
                                      boolean interactive) {
        renderMainMenuOverlay(gfx, atlas, mc, entries, gameK, mouseVx, mouseVy, interactive, 0.0);
    }

    public void renderMainMenuOverlay(HabiKineticGfx gfx, HabiKineticAtlas atlas, Minecraft mc,
                                      List<MenuItemData> entries, double gameK, double mouseVx, double mouseVy,
                                      boolean interactive, double exitFadeOut) {
        double exitMul = 1.0 - sat(exitFadeOut);
        if (gameK <= 0.002 || exitMul <= 0.004) return;
        long nowNanos = System.nanoTime();
        double dt = Math.min(0.1, Math.max(0.001, (nowNanos - lastUiFrameNanos) / 1_000_000_000.0));
        lastUiFrameNanos = nowNanos;
        uiClockSec += dt;
        transition.step(dt);
        this.lastMainEntryCount = Math.max(1, entries.size());

        // Snapshot sub-panel lists once per frame so hitboxes, rendering, and telemetry stay 100% consistent
        List<HabiTitleSubPanelData.ServerEntry> servers = subData.getServerEntries();
        List<HabiTitleSubPanelData.WorldEntry> worlds = subData.getWorldEntries();
        if (subData.consumeAsyncArrivalDirty() && transition.isInSubPanel()) {
            int arriving = transition.getActivePanel() == PanelMode.MULTIPLAYER
                    ? (servers.size() + 2) : (worlds.size() + 2);
            transition.onAsyncContentArrived(arriving);
        }

        double exitSlideX = -130.0 * sat(exitFadeOut);
        double exitSlideY = 34.0 * sat(exitFadeOut);
        double L = gfx.viewLeft(), T = gfx.viewTop(), R = gfx.viewRight(), B = gfx.viewBottom();
        double leftX = L + Math.max(76.0, gfx.viewWidth() * 0.055) + exitSlideX;
        double panelW = Math.min(500.0, gfx.viewWidth() * 0.40);

        // ── 1) Header: 01 // STATION TERMINAL + HABI[T]RAIN + subtitle ───────
        double hdrK = staggerEase(gameK, 0) * exitMul;
        double hdrOffX = -58.0 * (1.0 - staggerEase(gameK, 0)), hdrOffY = 18.0 * (1.0 - staggerEase(gameK, 0)) + exitSlideY;
        double hx = leftX + hdrOffX, hy = T + Math.max(62.0, gfx.viewHeight() * 0.075) + hdrOffY;

        gfx.fillRect(hx, hy + 6.0, 14.0, 3.0, withAlpha(PAL_RED, hdrK));
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "01 // STATION TERMINAL",
                hx + 26.0, hy + 8.0, 11.0, 3.0, withAlpha(0x8C8981, hdrK), -1, 0.0);
        double badgeX = hx + 26.0 + atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, "01 // STATION TERMINAL", 11.0, 3.0) + 14.0;
        String liveBadge = "100 BPM // SEAMLESS HANDOFF";
        double badgeW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, liveBadge, 10.0, 2.0) + 16.0;
        gfx.strokeRect(badgeX, hy - 2.0, badgeW, 19.0, 1.0, withAlpha(PAL_PAPER, hdrK * 0.28));
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, liveBadge,
                badgeX + 8.0, hy + 7.5, 10.0, 2.0, withAlpha(PAL_PAPER, hdrK), -1, 0.0);

        // HABI [T] RAIN display mark
        double logoY = hy + 62.0;
        double lx = hx;
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, "HABI",
                lx + 4.0, logoY + 4.0, 74.0, 6.0, withAlpha(PAL_RED, hdrK * 0.22), -1, 0.0);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, "HABI",
                lx, logoY, 74.0, 6.0, withAlpha(PAL_PAPER, hdrK), -1, 0.0);
        lx += atlas.measureAscii(HabiKineticAtlas.FontFace.DISPLAY, "HABI", 74.0, 6.0) + 10.0;

        double tW = atlas.measureAscii(HabiKineticAtlas.FontFace.DISPLAY, "T", 74.0, 0.0) + 28.0;
        double tH = 68.0;
        gfx.fillChamferRect(lx, logoY - tH * 0.52, tW, tH, 10.0, 0, 0, 0, withAlpha(PAL_RED, hdrK));
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, "T",
                lx + tW * 0.5, logoY, 74.0, 0.0, withAlpha(PAL_PAPER_TXT, hdrK), 0, 0.0);
        lx += tW + 10.0;

        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, "RAIN",
                lx + 4.0, logoY + 4.0, 74.0, 6.0, withAlpha(PAL_RED, hdrK * 0.22), -1, 0.0);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.DISPLAY, "RAIN",
                lx, logoY, 74.0, 6.0, withAlpha(PAL_PAPER, hdrK), -1, 0.0);

        // Subline: 直到列车变成一颗苹果 // HABIT TERMINAL 01
        double subY = logoY + 52.0;
        var zhSub = atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, "直到列车变成一颗苹果",
                hx, subY, 13, 3.2f, withAlpha(0xB4B8C6, hdrK), -1, 0.0, 0);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "//",
                hx + zhSub.w() + 10.0, subY, 12.0, 2.0, withAlpha(PAL_RED, hdrK), -1, 0.0);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "HABIT TERMINAL 01",
                hx + zhSub.w() + 38.0, subY, 12.0, 3.0, withAlpha(0x7E8294, hdrK), -1, 0.0);

        // Optional real CustomSkinLoader crash warning (respecting SRE's safety notice)
        double warnExtraY = 0.0;
        if (HabiTitleTelemetry.hasCustomSkinLoaderWarning()) {
            double wy = subY + 20.0;
            gfx.fillRect(hx, wy, panelW, 24.0, withAlpha(0x521B0A, hdrK * 0.85));
            gfx.fillRect(hx, wy, 3.0, 24.0, withAlpha(0xFFAA22, hdrK));
            atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN,
                    "警告：检测到 CustomSkinLoader，与星穹列车同装可能导致客户端崩溃，建议移除",
                    hx + 10.0, wy + 12.0, 11, 0.6f, withAlpha(0xFFD57A, hdrK), -1, 0.0, 0);
            warnExtraY = 30.0;
        }

        // ── 2) Scrollable Selection Box (Main Menu <-> Multiplayer / Singleplayer Slide) ──
        double listTop = subY + 34.0 + warnExtraY;
        double cardH = 166.0;
        double cardBottom = B - 96.0;
        double cardTop = cardBottom - cardH;
        double listBottom = Math.max(listTop + MENU_STRIDE * 2.0, cardTop - 22.0);
        double listViewH = listBottom - listTop;
        double subListTop = listTop + SUB_HEADER_TOTAL;
        double subListViewH = Math.max(MENU_ITEM_H, listBottom - subListTop);
        menuViewportRect = new HitRect(leftX, listTop, panelW, listViewH);

        PanelMode activePanel = transition.getActivePanel();
        PanelMode outgoingPanel = transition.getOutgoingPanel();
        activeListViewportRect = activePanel == PanelMode.MAIN
                ? menuViewportRect
                : new HitRect(leftX, subListTop, panelW, subListViewH);

        // Update scroll bounds and smooth scroll for main & sub panels
        updatePanelScroll(mainScroll, entries.size(), 0.0, listViewH, dt);
        updatePanelScroll(mpScroll, servers.size() + 1, servers.isEmpty() ? EMPTY_BANNER_TOTAL : 0.0, subListViewH, dt);
        updatePanelScroll(spScroll, worlds.size() + 1, worlds.isEmpty() ? EMPTY_BANNER_TOTAL : 0.0, subListViewH, dt);

        boolean pointerMoved = Math.hypot(mouseVx - lastMouseVx, mouseVy - lastMouseVy) > 0.75;
        lastMouseVx = mouseVx;
        lastMouseVy = mouseVy;

        // Precompute hit rects & pointer hover selection for the active panel only
        itemHits.clear();
        PanelScrollState activeScroll = scrollStateFor(activePanel);
        int activeCount = activePanel == PanelMode.MAIN
                ? entries.size()
                : (activePanel == PanelMode.MULTIPLAYER ? servers.size() + 1 : worlds.size() + 1);
        double activeRowW = activeScroll.maxScroll > 0.0 ? (panelW - 14.0) : panelW;
        double activeTop = activePanel == PanelMode.MAIN ? listTop : subListTop;
        double activeEmptyPad = emptyBannerHeightFor(activePanel);
        for (int i = 0; i < activeCount; i++) {
            double iy = activeTop + activeEmptyPad + i * MENU_STRIDE - activeScroll.scrollOffset;
            HitRect hit = new HitRect(leftX, iy, activeRowW, MENU_ITEM_H);
            itemHits.add(hit);
            if (interactive && pointerMoved && transition.canAcceptItemClick()
                    && activeListViewportRect.contains(mouseVx, mouseVy) && hit.contains(mouseVx, mouseVy)) {
                if (activeScroll.selectedIndex != i) {
                    activeScroll.selectedIndex = i;
                    HabiBootAudioEngine.getInstance().playHover(i);
                }
            }
        }
        if (activeCount > 0 && activeScroll.selectedIndex >= activeCount) {
            activeScroll.selectedIndex = activeCount - 1;
        }

        // Update sub-panel header button hitboxes (Back / Refresh)
        if (activePanel != PanelMode.MAIN) {
            subBackBtnRect = new HitRect(leftX, listTop, 96.0, SUB_HEADER_H);
            subRefreshBtnRect = new HitRect(leftX + panelW - 78.0, listTop + 4.0, 72.0, SUB_HEADER_H - 8.0);
        } else {
            subBackBtnRect = new HitRect(0, 0, 0, 0);
            subRefreshBtnRect = new HitRect(0, 0, 0, 0);
        }

        double slideDist = panelW + 44.0;
        double frameGlow = transition.transitionFrameGlow();
        if (frameGlow > 0.01) {
            drawSelectionFrameGuides(gfx, leftX, listTop, panelW, listViewH, frameGlow * hdrK);
        }

        // Pass A: Sub-panel header bar(s) clipped cleanly to the header strip
        if (outgoingPanel != null && outgoingPanel != PanelMode.MAIN) {
            gfx.enableVirtualScissor(leftX - 1.0, listTop - 2.0, leftX + panelW + 2.0, listTop + SUB_HEADER_H + 2.0);
            try {
                HabiStationListTransition.ItemMotion m = transition.outgoingMotion(0, slideDist);
                if (m.visible()) {
                    drawSubPanelHeader(gfx, atlas, outgoingPanel, leftX + m.offsetX(), listTop, panelW,
                            outgoingPanel == PanelMode.MULTIPLAYER ? servers.size() : worlds.size(),
                            m.alpha() * hdrK, false, mouseVx, mouseVy);
                }
            } finally {
                gfx.disableVirtualScissor();
            }
        }
        if (activePanel != PanelMode.MAIN) {
            gfx.enableVirtualScissor(leftX - 1.0, listTop - 2.0, leftX + panelW + 2.0, listTop + SUB_HEADER_H + 2.0);
            try {
                HabiStationListTransition.ItemMotion m = transition.incomingMotion(0, slideDist);
                if (m.visible()) {
                    drawSubPanelHeader(gfx, atlas, activePanel, leftX + m.offsetX(), listTop, panelW,
                            activePanel == PanelMode.MULTIPLAYER ? servers.size() : worlds.size(),
                            m.alpha() * hdrK, interactive, mouseVx, mouseVy);
                }
            } finally {
                gfx.disableVirtualScissor();
            }
        }

        // Pass B: Outgoing panel list items sliding out of the selection box
        if (outgoingPanel != null) {
            double outClipTop = outgoingPanel == PanelMode.MAIN ? listTop : subListTop;
            gfx.enableVirtualScissor(leftX - 1.0, outClipTop - 2.0, leftX + panelW + 2.0, listBottom + 2.0);
            try {
                renderPanelList(gfx, atlas, mc, outgoingPanel, entries, servers, worlds,
                        leftX, outClipTop, listBottom, panelW, gameK, exitMul, dt, slideDist, true);
            } finally {
                gfx.disableVirtualScissor();
            }
        }

        // Pass C: Active panel list items (initial Station 01 stagger or right-to-left slide-in)
        double activeClipTop = activePanel == PanelMode.MAIN ? listTop : subListTop;
        double activeScissorLeft = (!transition.isTransitioning() && gameK < 0.999) ? (leftX - 80.0) : (leftX - 1.0);
        double activeScissorRight = (!transition.isTransitioning() && gameK < 0.999) ? (leftX + panelW + 24.0) : (leftX + panelW + 2.0);
        gfx.enableVirtualScissor(activeScissorLeft, activeClipTop - 2.0, activeScissorRight, listBottom + 2.0);
        try {
            renderPanelList(gfx, atlas, mc, activePanel, entries, servers, worlds,
                    leftX, activeClipTop, listBottom, panelW, gameK, exitMul, dt, slideDist, false);
        } finally {
            gfx.disableVirtualScissor();
        }

        // Sleek scrollbar along right edge of active list viewport
        double sbAlpha = hdrK * (1.0 - 0.55 * frameGlow);
        if (activeScroll.maxScroll > 0.0 && sbAlpha > 0.02) {
            double sbTop = activeClipTop;
            double sbViewH = listBottom - sbTop;
            double totalH = Math.max(sbViewH + 1.0, sbViewH + activeScroll.maxScroll);
            double sbX = leftX + panelW - 5.0;
            scrollbarRect = new HitRect(sbX - 5.0, sbTop, 14.0, sbViewH);
            gfx.fillRect(sbX, sbTop, 4.0, sbViewH, withAlpha(PAL_PAPER, 0.12 * sbAlpha));
            double thumbH = Math.max(36.0, sbViewH * (sbViewH / totalH));
            double thumbY = sbTop + (activeScroll.scrollOffset / activeScroll.maxScroll) * (sbViewH - thumbH);
            gfx.fillRect(sbX, thumbY, 4.0, thumbH, withAlpha(draggingScrollbar ? PAL_RED_BRIGHT : PAL_RED, 0.92 * sbAlpha));
        } else {
            scrollbarRect = new HitRect(0, 0, 0, 0);
        }

        // ── 3) Bottom-Left Dynamic Real-Data Telemetry Card (g-card) ─────────
        double cardK = staggerEase(gameK, 6) * exitMul;
        double cx = leftX - 58.0 * (1.0 - staggerEase(gameK, 6));
        double cy = cardTop + 18.0 * (1.0 - staggerEase(gameK, 6)) + exitSlideY;
        HabiTitleTelemetry.MenuVisualMeta activeMeta = resolveTelemetryMeta(mc, activePanel, activeScroll.selectedIndex, entries, servers, worlds);

        gfx.fillChamferRect(cx, cy, panelW, cardH, 0, 0, 0, 14.0, withAlpha(0x131620, 0.82 * cardK));
        gfx.strokeRect(cx, cy, panelW, cardH, 1.0, withAlpha(PAL_PAPER, 0.16 * cardK));
        gfx.fillRect(cx, cy, panelW, 2.5, withAlpha(PAL_RED, cardK));

        drawMonoOrCn(gfx, atlas, activeMeta.cardKicker(),
                cx + 20.0, cy + 20.0, 10.0, 2.2, withAlpha(PAL_RED, cardK), -1);
        drawMonoOrCn(gfx, atlas, activeMeta.cardCode(),
                cx + panelW - 20.0, cy + 20.0, 10.0, 2.0, withAlpha(0x7C8092, cardK), 1);

        String cardTitleFit = atlas.truncateText(HabiKineticAtlas.FontFace.SANS_CN, activeMeta.cardTitle(), 16, 2.2f, panelW - 40.0);
        atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, cardTitleFit,
                cx + 20.0, cy + 47.0, 16, 2.2f, withAlpha(PAL_PAPER_TXT, cardK), -1, 0.0, 0);
        String cardDescTrim = trimCardDescription(activeMeta.cardDesc(), 98);
        gfx.enableVirtualScissor(cx + 16.0, cy + 64.0, cx + panelW - 16.0, cy + cardH - 34.0);
        try {
            atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, cardDescTrim,
                    cx + 20.0, cy + 68.0, 12, 0.6f, withAlpha(0x9A9EAE, cardK), -1, 0.0, (int) (panelW - 40.0));
        } finally {
            gfx.disableVirtualScissor();
        }

        // Meter row at bottom of card
        double my = cy + cardH - 22.0;
        double mlW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, activeMeta.meterLabel(), 10.0, 2.0);
        double mrW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, activeMeta.meterRight(), 10.0, 2.0);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, activeMeta.meterLabel(),
                cx + 20.0, my, 10.0, 2.0, withAlpha(0x8C8981, cardK), -1, 0.0);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, activeMeta.meterRight(),
                cx + panelW - 20.0, my, 10.0, 2.0, withAlpha(0x8C8981, cardK), 1, 0.0);
        double barX = cx + 20.0 + mlW + 12.0;
        double barW = Math.max(40.0, (cx + panelW - 20.0 - mrW - 12.0) - barX);
        gfx.fillRect(barX, my - 2.0, barW, 4.0, withAlpha(PAL_PAPER, 0.12 * cardK));
        gfx.fillRect(barX, my - 2.0, barW * sat(activeMeta.meterPercent() / 100.0), 4.0, withAlpha(PAL_RED, cardK));

        // ── 4) Bottom-Left Key Hints & Real Version Readout ──────────────────
        double fk = staggerEase(gameK, 7) * exitMul;
        double fy = B - 47.0 + 14.0 * (1.0 - staggerEase(gameK, 7)) + exitSlideY;
        double fx = leftX - 40.0 * (1.0 - staggerEase(gameK, 7));
        String keyHint = switch (activePanel) {
            case MAIN -> "↑/↓ 切换站台    ENTER 确认检票    M 音乐开关    R 重播完整加载";
            case MULTIPLAYER -> "↑/↓ 选择车次    ENTER 加入服务器    ESC 返回主页    F5 刷新列表";
            case SINGLEPLAYER -> "↑/↓ 选择存档    ENTER 进入世界    ESC 返回主页    F5 刷新列表";
        };
        atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN,
                keyHint,
                fx, fy - 12.0, 11, 1.4f, withAlpha(0x8A8E9F, fk), -1, 0.0, 0);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, HabiTitleTelemetry.getFooterVersionLine(),
                fx, fy + 10.0, 10.0, 2.0, withAlpha(0x676B7C, fk * 0.9), -1, 0.0);

        // ── 5) Bottom-Right Compact Toolbar Pills (SOUND ON/OFF + R // REPLAY)
        double tbY = B - 104.0;
        boolean soundOn = HabiBootAudioEngine.getInstance().isSoundEffectivelyEnabled();
        String sLabel = soundOn ? "SOUND ON" : "SOUND OFF";
        String rLabel = "R // REPLAY";
        double rW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, rLabel, 11.0, 2.0) + 26.0;
        double sW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, sLabel, 11.0, 2.0) + 38.0;
        double tbH = 30.0;
        double rX = R - 54.0 - rW;
        double sX = rX - 10.0 - sW;
        replayBtnRect = new HitRect(rX, tbY, rW, tbH);
        soundBtnRect = new HitRect(sX, tbY, sW, tbH);

        drawToolbarButton(gfx, atlas, soundBtnRect, sLabel, soundOn, interactive && soundBtnRect.contains(mouseVx, mouseVy), true, fk);
        drawToolbarButton(gfx, atlas, replayBtnRect, rLabel, false, interactive && replayBtnRect.contains(mouseVx, mouseVy), false, fk);
    }

    // ── Inline Selection Box Rendering Helpers ───────────────────────────────

    private void updatePanelScroll(PanelScrollState st, int itemCount, double topPad, double viewH, double dt) {
        double totalListH = topPad + Math.max(0.0, itemCount * MENU_STRIDE - MENU_ITEM_GAP);
        st.maxScroll = Math.max(0.0, totalListH - viewH);
        st.stepScroll(dt);
    }

    private void drawSelectionFrameGuides(HabiKineticGfx gfx, double x, double y, double w, double h, double glow) {
        int railCol = withAlpha(PAL_PAPER, 0.18 * glow);
        int tickCol = withAlpha(PAL_RED, 0.85 * glow);
        gfx.fillRect(x, y, 1.0, h, railCol);
        gfx.fillRect(x + w - 1.0, y, 1.0, h, railCol);
        double tk = 14.0;
        gfx.fillRect(x, y, tk, 2.0, tickCol);
        gfx.fillRect(x, y + h - 2.0, tk, 2.0, tickCol);
        gfx.fillRect(x + w - tk, y, tk, 2.0, tickCol);
        gfx.fillRect(x + w - tk, y + h - 2.0, tk, 2.0, tickCol);
    }

    private void drawSubPanelHeader(HabiKineticGfx gfx, HabiKineticAtlas atlas, PanelMode mode,
                                    double rx, double ry, double panelW, int itemCount, double alpha,
                                    boolean interactive, double mouseVx, double mouseVy) {
        if (alpha <= 0.01) return;
        gfx.fillChamferRect(rx, ry, panelW, SUB_HEADER_H, 0, 0, 8.0, 0, withAlpha(0x131620, 0.78 * alpha));
        gfx.fillRect(rx, ry + SUB_HEADER_H - 1.0, panelW - 8.0, 1.0, withAlpha(PAL_PAPER, 0.16 * alpha));

        // Left Back pill button (← 返回)
        double backW = 96.0;
        boolean backHover = interactive && subBackBtnRect.contains(mouseVx, mouseVy);
        int backBg = backHover ? withAlpha(PAL_RED, 0.94 * alpha) : withAlpha(0x1B2030, 0.85 * alpha);
        gfx.fillChamferRect(rx, ry, backW, SUB_HEADER_H, 0, 0, 0, backHover ? 10.0 : 0.0, backBg);
        gfx.fillRect(rx, ry, 3.0, SUB_HEADER_H - (backHover ? 10.0 : 0.0),
                withAlpha(backHover ? PAL_PAPER_TXT : PAL_RED, alpha));
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, "<",
                rx + 15.0, ry + SUB_HEADER_H * 0.5, 12.0, 1.0,
                withAlpha(backHover ? PAL_PAPER_TXT : PAL_RED_BRIGHT, alpha), -1, 0.0);
        atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, "返回",
                rx + 32.0, ry + SUB_HEADER_H * 0.5, 13, 2.0f,
                withAlpha(backHover ? PAL_PAPER_TXT : 0xD7D4CC, alpha), -1, 0.0, 0);

        // Center breadcrumb (多人服务器 / 单人世界 + live count)
        double titleX = rx + backW + 16.0;
        gfx.fillRect(titleX, ry + SUB_HEADER_H * 0.5 - 3.0, 6.0, 6.0, withAlpha(PAL_RED, alpha));
        String titleCn = mode == PanelMode.MULTIPLAYER ? "多人服务器" : "单人世界";
        var cnTex = atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, titleCn,
                titleX + 14.0, ry + SUB_HEADER_H * 0.5, 13, 2.0f, withAlpha(PAL_PAPER_TXT, alpha), -1, 0.0, 0);
        boolean loading = mode == PanelMode.MULTIPLAYER ? subData.isServersLoading() : subData.isWorldsLoading();
        String countTag = loading ? "// SCAN" : ("// " + itemCount);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, countTag,
                titleX + 24.0 + cnTex.w(), ry + SUB_HEADER_H * 0.5 + 0.5, 10.0, 1.8,
                withAlpha(0x8A8E9F, alpha), -1, 0.0);

        // Right compact Refresh pill (刷新)
        double refW = 72.0;
        double refH = SUB_HEADER_H - 8.0;
        double refX = rx + panelW - refW - 6.0;
        double refY = ry + 4.0;
        boolean refHover = interactive && subRefreshBtnRect.contains(mouseVx, mouseVy);
        gfx.fillRect(refX, refY, refW, refH, withAlpha(refHover ? PAL_RED : 0x0E1017, (refHover ? 0.90 : 0.65) * alpha));
        gfx.strokeRect(refX, refY, refW, refH, 1.0,
                withAlpha(refHover ? PAL_RED_BRIGHT : 0x8C8981, (refHover ? 0.95 : 0.45) * alpha));
        atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, "刷新",
                refX + refW * 0.5, refY + refH * 0.5, 11, 2.0f,
                withAlpha(refHover ? PAL_PAPER_TXT : 0xC6C9D4, alpha), 0, 0.0, 0);
    }

    private void renderPanelList(HabiKineticGfx gfx, HabiKineticAtlas atlas, Minecraft mc, PanelMode mode,
                                 List<MenuItemData> mainEntries,
                                 List<HabiTitleSubPanelData.ServerEntry> servers,
                                 List<HabiTitleSubPanelData.WorldEntry> worlds,
                                 double leftX, double clipTop, double clipBottom, double panelW,
                                 double gameK, double exitMul, double dt, double slideDist, boolean isOutgoing) {
        PanelScrollState scroll = scrollStateFor(mode);
        double rowW = scroll.maxScroll > 0.0 ? (panelW - 14.0) : panelW;
        int rankBase = (mode == PanelMode.MAIN) ? 0 : 1; // sub-header occupies rank 0

        if (mode == PanelMode.MAIN) {
            for (int i = 0; i < mainEntries.size(); i++) {
                double iy = clipTop + i * MENU_STRIDE - scroll.scrollOffset;
                if (iy + MENU_ITEM_H < clipTop - 40.0 || iy > clipBottom + 40.0) continue;

                double ox, oy, alpha;
                if (isOutgoing) {
                    int rank = HabiStationListTransition.visualRankFromOffset(iy, clipTop, MENU_STRIDE);
                    var m = transition.outgoingMotion(rank, slideDist);
                    if (!m.visible()) continue;
                    ox = m.offsetX();
                    oy = 0.0;
                    alpha = m.alpha() * exitMul;
                } else if (transition.isTransitioning()) {
                    int rank = HabiStationListTransition.visualRankFromOffset(iy, clipTop, MENU_STRIDE);
                    var m = transition.incomingMotion(rank, slideDist);
                    if (!m.visible()) continue;
                    ox = m.offsetX();
                    oy = 0.0;
                    alpha = m.alpha() * exitMul;
                } else {
                    double ik = staggerEase(gameK, Math.min(6, i + 1));
                    ox = -58.0 * (1.0 - ik);
                    oy = 18.0 * (1.0 - ik);
                    alpha = ik * exitMul;
                }
                double rx = leftX + ox, ry = iy + oy;
                boolean on = (i == scroll.selectedIndex);
                float hv = isOutgoing ? scroll.stepHover(i, on, 0.0) : scroll.stepHover(i, on, dt);
                drawMainMenuCard(gfx, atlas, mc, mainEntries.get(i), rx, ry, rowW, on, hv, alpha);
            }
            return;
        }

        // Sub-panel: optional empty / scanning notice banner above the bottom "编辑" card
        boolean isMp = (mode == PanelMode.MULTIPLAYER);
        int dataCount = isMp ? servers.size() : worlds.size();
        double emptyPad = (dataCount == 0) ? EMPTY_BANNER_TOTAL : 0.0;
        if (dataCount == 0) {
            double by = clipTop - scroll.scrollOffset;
            if (by + EMPTY_BANNER_H >= clipTop - 40.0 && by <= clipBottom + 40.0) {
                var m = isOutgoing ? transition.outgoingMotion(rankBase, slideDist)
                        : transition.incomingMotion(rankBase, slideDist);
                if (m.visible()) {
                    drawEmptyNoticeStrip(gfx, atlas, mode, leftX + m.offsetX(), by, rowW, m.alpha() * exitMul);
                }
            }
        }

        int totalRows = dataCount + 1; // data rows + bottom "编辑" fallback card
        for (int i = 0; i < totalRows; i++) {
            double iy = clipTop + emptyPad + i * MENU_STRIDE - scroll.scrollOffset;
            if (iy + MENU_ITEM_H < clipTop - 40.0 || iy > clipBottom + 40.0) continue;

            int rank = rankBase + (dataCount == 0 ? 1 : 0)
                    + HabiStationListTransition.visualRankFromOffset(iy, clipTop + emptyPad, MENU_STRIDE);
            var m = isOutgoing ? transition.outgoingMotion(rank, slideDist)
                    : transition.incomingMotion(rank, slideDist);
            if (!m.visible()) continue;

            double rx = leftX + m.offsetX();
            double ry = iy;
            boolean on = (i == scroll.selectedIndex);
            float hv = isOutgoing ? scroll.stepHover(i, on, 0.0) : scroll.stepHover(i, on, dt);

            if (i < dataCount) {
                if (isMp) {
                    drawServerCard(gfx, atlas, servers.get(i), rx, ry, rowW, on, hv, m.alpha() * exitMul);
                } else {
                    drawWorldCard(gfx, atlas, worlds.get(i), rx, ry, rowW, on, hv, m.alpha() * exitMul);
                }
            } else {
                drawEditFallbackCard(gfx, atlas, mode, rx, ry, rowW, on, hv, m.alpha() * exitMul);
            }
        }
    }

    private void drawKineticCardBase(HabiKineticGfx gfx, double rx, double ry, double rowW,
                                     boolean on, float hv, double alpha,
                                     int accentRgb, double accentUnselectedAlpha) {
        int bgRgb = lerpRgb(0x161924, 0xE1243B, hv);
        double bgAlpha = lerp(0.62, 0.96, hv) * alpha;
        if (on || hv > 0.45f) {
            gfx.fillChamferRect(rx, ry, rowW, MENU_ITEM_H, 0, 0, 0, 14.0, withAlpha(bgRgb, bgAlpha));
        } else {
            gfx.fillChamferRect(rx, ry, rowW, MENU_ITEM_H, 0, 0, 11.0, 0, withAlpha(bgRgb, bgAlpha));
        }
        int barCol = lerpRgb(accentRgb, 0xF4F1EA, hv);
        double barAlpha = lerp(accentUnselectedAlpha, 1.0, hv) * alpha;
        gfx.fillRect(rx, ry, 3.5, MENU_ITEM_H - (on ? 14.0 : 0.0), withAlpha(barCol, barAlpha));
    }

    private double drawRightPillTag(HabiKineticGfx gfx, HabiKineticAtlas atlas, String tag,
                                    double rx, double ry, double rowW, float hv, double alpha) {
        double tagW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, tag, 10.0, 1.8) + 14.0;
        double tagH = 22.0;
        double tagX = rx + rowW - 18.0 - tagW;
        double tagY = ry + (MENU_ITEM_H - tagH) * 0.5;
        if (hv > 0.35f) {
            gfx.fillRect(tagX, tagY, tagW, tagH, withAlpha(0x0E1017, 0.22 * hv * alpha));
        }
        gfx.strokeRect(tagX, tagY, tagW, tagH, 1.0,
                withAlpha(lerpRgb(0xECE9E2, 0xF4F1EA, hv), lerp(0.22, 0.65, hv) * alpha));
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, tag,
                tagX + tagW * 0.5, tagY + tagH * 0.5, 10.0, 1.8,
                withAlpha(lerpRgb(0x9DA1B3, 0xF4F1EA, hv), alpha), 0, 0.0);
        return tagX;
    }

    private void drawMainMenuCard(HabiKineticGfx gfx, HabiKineticAtlas atlas, Minecraft mc, MenuItemData item,
                                  double rx, double ry, double rowW, boolean on, float hv, double alpha) {
        drawKineticCardBase(gfx, rx, ry, rowW, on, hv, alpha, 0xECE9E2, 0.24);
        HabiTitleTelemetry.MenuVisualMeta meta = HabiTitleTelemetry.describeEntry(mc, item.text());

        double padL = 20.0 + 9.0 * hv;
        int mainCol = withAlpha(lerpRgb(0xD7D4CC, 0xF4F1EA, hv), alpha);
        var zhTex = atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, item.text().getString(),
                rx + padL, ry + MENU_ITEM_H * 0.5, 22, 4.0f, mainCol, -1, 0.0, 0);

        double tagX = drawRightPillTag(gfx, atlas, meta.tag(), rx, ry, rowW, hv, alpha);

        double enX = rx + padL + zhTex.w() + 12.0;
        double enW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, meta.enSub(), 10.5, 2.0);
        if (enX + enW < tagX - 10.0) {
            int enCol = withAlpha(lerpRgb(0x8A8E9F, 0xF4F1EA, hv), alpha * lerp(1.0, 0.85, hv));
            atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, meta.enSub(),
                    enX, ry + MENU_ITEM_H * 0.5 + 2.0, 10.5, 2.0, enCol, -1, 0.0);
        }
    }

    private void drawServerCard(HabiKineticGfx gfx, HabiKineticAtlas atlas, HabiTitleSubPanelData.ServerEntry entry,
                                double rx, double ry, double rowW, boolean on, float hv, double alpha) {
        HabiTitleSubPanelData.ServerPingVisual vis =
                HabiTitleSubPanelData.classifyServerVisual(entry.state(), entry.isLan(), entry.isCompatible());
        int accentRgb = switch (vis) {
            case ONLINE -> 0x4ADE80;
            case LAN -> PAL_BLUE;
            case PINGING -> 0xECE9E2;
            case OFFLINE, INCOMPATIBLE -> 0x727687;
        };
        double accentAlpha = (vis == HabiTitleSubPanelData.ServerPingVisual.ONLINE
                || vis == HabiTitleSubPanelData.ServerPingVisual.LAN) ? 0.72 : 0.30;
        drawKineticCardBase(gfx, rx, ry, rowW, on, hv, alpha, accentRgb, accentAlpha);

        String pillTag = HabiTitleSubPanelData.formatServerPillTag(entry);
        double tagX = drawRightPillTag(gfx, atlas, pillTag, rx, ry, rowW, hv, alpha);

        // 4-bar geometric signal indicator matching Station 01's spectrum bar language
        double barsW = 16.0;
        double barsX = tagX - 10.0 - barsW;
        drawSignalBars(gfx, barsX, ry + MENU_ITEM_H * 0.5 + 7.0, vis,
                entry.data() != null ? entry.data().ping : -1L, hv, alpha);

        double padL = 20.0 + 9.0 * hv;
        double availW = Math.max(60.0, (barsX - 12.0) - (rx + padL));
        int mainCol = withAlpha(lerpRgb(0xDCD9D2, 0xF4F1EA, hv), alpha);
        String nameFit = atlas.truncateText(HabiKineticAtlas.FontFace.SANS_CN, entry.displayName(), 18, 1.4f, availW);
        atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, nameFit,
                rx + padL, ry + 20.5, 18, 1.4f, mainCol, -1, 0.0, 0);

        String subLine = HabiTitleSubPanelData.formatServerSubLine(entry);
        int subCol = withAlpha(lerpRgb(0x8A8E9F, 0xF4F1EA, hv), alpha * lerp(1.0, 0.88, hv));
        drawTruncatedSubLine(gfx, atlas, subLine, rx + padL, ry + 42.5, availW, subCol);
    }

    private void drawSignalBars(HabiKineticGfx gfx, double x, double baselineY,
                                HabiTitleSubPanelData.ServerPingVisual vis, long pingMs, float hv, double alpha) {
        int litBars = switch (vis) {
            case ONLINE -> Math.max(1, HabiTitleSubPanelData.pingBarsForLatency(pingMs));
            case LAN -> 4;
            case PINGING, OFFLINE, INCOMPATIBLE -> 0;
        };
        int scanIdx = (int) Math.floor(uiClockSec * 7.0) % 4;
        for (int b = 0; b < 4; b++) {
            double bh = 4.0 + b * 3.0;
            double bx = x + b * 4.5;
            int col;
            if (vis == HabiTitleSubPanelData.ServerPingVisual.PINGING) {
                col = (b == scanIdx)
                        ? withAlpha(lerpRgb(0xFBBF24, 0xF4F1EA, hv), 0.95 * alpha)
                        : withAlpha(PAL_PAPER, 0.22 * alpha);
            } else if (vis == HabiTitleSubPanelData.ServerPingVisual.OFFLINE
                    || vis == HabiTitleSubPanelData.ServerPingVisual.INCOMPATIBLE) {
                col = withAlpha(lerpRgb(0x727687, 0xF4F1EA, hv), 0.35 * alpha);
            } else if (b < litBars) {
                int base = vis == HabiTitleSubPanelData.ServerPingVisual.LAN ? PAL_BLUE : 0x4ADE80;
                col = withAlpha(lerpRgb(base, 0xF4F1EA, hv), 0.92 * alpha);
            } else {
                col = withAlpha(PAL_PAPER, 0.18 * alpha);
            }
            gfx.fillRect(bx, baselineY - bh, 2.5, bh, col);
        }
    }

    private void drawWorldCard(HabiKineticGfx gfx, HabiKineticAtlas atlas, HabiTitleSubPanelData.WorldEntry entry,
                               double rx, double ry, double rowW, boolean on, float hv, double alpha) {
        int accentRgb = !entry.playable() ? 0x727687 : (entry.hardcore() ? PAL_RED_BRIGHT : 0xECE9E2);
        double accentAlpha = entry.hardcore() ? 0.78 : (entry.playable() ? 0.34 : 0.20);
        drawKineticCardBase(gfx, rx, ry, rowW, on, hv, alpha, accentRgb, accentAlpha);

        double tagX = drawRightPillTag(gfx, atlas, entry.statusTag(), rx, ry, rowW, hv, alpha);

        double padL = 20.0 + 9.0 * hv;
        double availW = Math.max(60.0, (tagX - 14.0) - (rx + padL));
        int baseNameRgb = entry.playable() ? 0xDCD9D2 : 0x8C90A0;
        int mainCol = withAlpha(lerpRgb(baseNameRgb, 0xF4F1EA, hv), alpha);
        String nameFit = atlas.truncateText(HabiKineticAtlas.FontFace.SANS_CN, entry.displayName(), 18, 1.4f, availW);
        atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, nameFit,
                rx + padL, ry + 20.5, 18, 1.4f, mainCol, -1, 0.0, 0);

        String verPart = entry.versionName().isEmpty() ? entry.levelId() : entry.versionName();
        String subLine = entry.lastPlayedText() + "  //  " + verPart;
        int subCol = withAlpha(lerpRgb(0x8A8E9F, 0xF4F1EA, hv), alpha * lerp(1.0, 0.88, hv));
        drawTruncatedSubLine(gfx, atlas, subLine, rx + padL, ry + 42.5, availW, subCol);
    }

    private void drawEditFallbackCard(HabiKineticGfx gfx, HabiKineticAtlas atlas, PanelMode mode,
                                      double rx, double ry, double rowW, boolean on, float hv, double alpha) {
        drawKineticCardBase(gfx, rx, ry, rowW, on, hv, alpha, PAL_RED, 0.72);
        double tagX = drawRightPillTag(gfx, atlas, "NATIVE", rx, ry, rowW, hv, alpha);

        double padL = 20.0 + 9.0 * hv;
        int mainCol = withAlpha(lerpRgb(0xD7D4CC, 0xF4F1EA, hv), alpha);
        var zhTex = atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, "编辑",
                rx + padL, ry + MENU_ITEM_H * 0.5, 22, 4.0f, mainCol, -1, 0.0, 0);

        String enSub = (mode == PanelMode.MULTIPLAYER) ? "EDIT SERVERS" : "EDIT WORLDS";
        double enX = rx + padL + zhTex.w() + 12.0;
        double enW = atlas.measureAscii(HabiKineticAtlas.FontFace.MONO, enSub, 10.5, 2.0);
        if (enX + enW < tagX - 10.0) {
            int enCol = withAlpha(lerpRgb(0x8A8E9F, 0xF4F1EA, hv), alpha * lerp(1.0, 0.85, hv));
            atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, enSub,
                    enX, ry + MENU_ITEM_H * 0.5 + 2.0, 10.5, 2.0, enCol, -1, 0.0);
        }
    }

    private void drawEmptyNoticeStrip(HabiKineticGfx gfx, HabiKineticAtlas atlas, PanelMode mode,
                                      double rx, double ry, double rowW, double alpha) {
        gfx.fillRect(rx, ry, rowW, EMPTY_BANNER_H, withAlpha(0x131620, 0.55 * alpha));
        gfx.strokeRect(rx, ry, rowW, EMPTY_BANNER_H, 1.0, withAlpha(PAL_PAPER, 0.16 * alpha));
        String msg;
        if (mode == PanelMode.MULTIPLAYER) {
            msg = subData.isServersLoading()
                    ? "正在读取多人服务器列表..."
                    : "暂无已保存服务器 · 点击下方「编辑」添加或直连";
        } else {
            String err = subData.getWorldsErrorMessage();
            if (err != null) {
                msg = err;
            } else {
                msg = subData.isWorldsLoading()
                        ? "正在扫描本地单人世界存档..."
                        : "暂无本地单人世界 · 点击下方「编辑」创建新世界";
            }
        }
        String fit = atlas.truncateText(HabiKineticAtlas.FontFace.SANS_CN, msg, 12, 1.0f, rowW - 28.0);
        atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, fit,
                rx + 14.0, ry + EMPTY_BANNER_H * 0.5, 12, 1.0f, withAlpha(0x9A9EAE, alpha), -1, 0.0, 0);
    }

    private void drawTruncatedSubLine(HabiKineticGfx gfx, HabiKineticAtlas atlas, String text,
                                      double x, double y, double maxW, int argb) {
        if (text == null || text.isEmpty()) return;
        if (HabiTitleSubPanelData.isPureAscii(text)) {
            String fit = atlas.truncateAscii(HabiKineticAtlas.FontFace.MONO, text, 10.0, 1.4, maxW);
            atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, fit, x, y, 10.0, 1.4, argb, -1, 0.0);
        } else {
            String fit = atlas.truncateText(HabiKineticAtlas.FontFace.SANS_CN, text, 11, 0.6f, maxW);
            atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, fit, x, y, 11, 0.6f, argb, -1, 0.0, 0);
        }
    }

    private void drawMonoOrCn(HabiKineticGfx gfx, HabiKineticAtlas atlas, String text,
                              double x, double y, double sizePx, double letterSpacing, int argb, int align) {
        if (text == null || text.isEmpty()) return;
        if (HabiTitleSubPanelData.isPureAscii(text)) {
            atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, text, x, y, sizePx, letterSpacing, argb, align, 0.0);
        } else {
            atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, text, x, y,
                    Math.max(10, (int) Math.round(sizePx + 1.0)), (float) (letterSpacing * 0.6), argb, align, 0.0, 0);
        }
    }

    private HabiTitleTelemetry.MenuVisualMeta resolveTelemetryMeta(
            Minecraft mc, PanelMode activePanel, int selectedIdx,
            List<MenuItemData> mainEntries,
            List<HabiTitleSubPanelData.ServerEntry> servers,
            List<HabiTitleSubPanelData.WorldEntry> worlds
    ) {
        if (activePanel == PanelMode.MULTIPLAYER) {
            if (selectedIdx >= 0 && selectedIdx < servers.size()) {
                return HabiTitleSubPanelData.describeServerForCard(servers.get(selectedIdx));
            }
            return HabiTitleSubPanelData.describeEditFallbackForCard(PanelMode.MULTIPLAYER);
        }
        if (activePanel == PanelMode.SINGLEPLAYER) {
            if (selectedIdx >= 0 && selectedIdx < worlds.size()) {
                return HabiTitleSubPanelData.describeWorldForCard(worlds.get(selectedIdx));
            }
            return HabiTitleSubPanelData.describeEditFallbackForCard(PanelMode.SINGLEPLAYER);
        }
        if (!mainEntries.isEmpty()) {
            int safeIdx = Math.max(0, Math.min(selectedIdx, mainEntries.size() - 1));
            return HabiTitleTelemetry.describeEntry(mc, mainEntries.get(safeIdx).text());
        }
        return HabiTitleTelemetry.describeEntry(mc, Component.literal("HABITRAIN"));
    }

    static String trimCardDescription(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text == null ? "" : text;
        return text.substring(0, Math.max(1, maxChars - 3)).stripTrailing() + "...";
    }

    private void drawToolbarButton(HabiKineticGfx gfx, HabiKineticAtlas atlas, HitRect r, String label,
                                   boolean active, boolean hovered, boolean withDot, double alpha) {
        int bg = active ? withAlpha(PAL_RED, 0.88 * alpha) : withAlpha(0x0E1017, 0.76 * alpha);
        int border = (active || hovered) ? withAlpha(PAL_RED, alpha) : withAlpha(0x8C8981, 0.45 * alpha);
        int txtCol = (active || hovered) ? withAlpha(PAL_PAPER_TXT, alpha) : withAlpha(0x8C8981, alpha);
        gfx.fillRect(r.x(), r.y(), r.w(), r.h(), bg);
        gfx.strokeRect(r.x(), r.y(), r.w(), r.h(), 1.0, border);
        if (withDot) {
            gfx.fillCircle(r.x() + 14.0, r.y() + r.h() * 0.5, 3.2, txtCol, 10);
            atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, label,
                    r.x() + 24.0, r.y() + r.h() * 0.5, 11.0, 2.0, txtCol, -1, 0.0);
        } else {
            atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, label,
                    r.x() + r.w() * 0.5, r.y() + r.h() * 0.5, 11.0, 2.0, txtCol, 0, 0.0);
        }
    }

    private static double staggerEase(double gameK, int idx) {
        double k = sat(gameK * 1.65 - idx * 0.11);
        return 1.0 - Math.pow(1.0 - k, 3.0);
    }

    // ── Station 01 2.5D Layer Subroutines ────────────────────────────────────

    private void drawBgGrid(HabiKineticGfx gfx, Cam cam) {
        int n = 0;
        for (int gx = -6; gx <= 6; gx++) {
            for (int gy = -4; gy <= 4; gy++) {
                double[] c = cam.v(gx * 240.0, gy * 240.0, 880.0);
                n = pushSeg(cam, c[0] - 7, c[1], c[2], c[0] + 7, c[1], c[2], n);
                n = pushSeg(cam, c[0], c[1] - 7, c[2], c[0], c[1] + 7, c[2], n);
            }
        }
        gfx.drawSegments(segScratch, n, 1.0, PAL_FAINT);
    }

    private void drawGhost01(HabiKineticGfx gfx, HabiKineticAtlas atlas, Cam cam, double u, double settle) {
        double drift = 14.0 * Math.sin(u * 0.28);
        double[] M = mm(cam.R, RZ(-0.025));
        double[] O = cam.v(HUB_X + 260.0 + drift, -40.0, 680.0);
        atlas.drawAsciiOnPlane(gfx, cam, O, M, HabiKineticAtlas.FontFace.DISPLAY,
                "01", 0, 0, 720.0, 10.0, withAlpha(PAL_PAPER, 0.042 * settle), 0);
    }

    private void drawSeamContinuityRails(HabiKineticGfx gfx, double u, double settle, double beatPulse) {
        double ang = lerp(SPLIT_ANG, -0.24, eOutCubic(sat(u / 1.1)));
        double ux = Math.cos(ang), uy = Math.sin(ang);
        double nx = -uy, ny = ux;
        double cx = lerp(CX, CX + 220.0, settle);
        double cy = lerp(CY, CY + 60.0, settle);

        double flash = Math.exp(-u * 2.8);
        if (flash > 0.01) {
            gfx.drawLine(cx - ux * 2400, cy - uy * 2400, cx + ux * 2400, cy + uy * 2400,
                    4.0 + 26.0 * flash, withAlpha(PAL_RED_BRIGHT, 0.45 * flash));
        }

        gfx.drawLine(cx - ux * 2400, cy - uy * 2400, cx + ux * 2400, cy + uy * 2400,
                1.6 + beatPulse * 1.1, withAlpha(PAL_RED, 0.65));

        int guideCol = scaleAlpha(PAL_LINE_MID, 0.45 * settle);
        for (double off : new double[]{28.0, 44.0, -180.0}) {
            gfx.drawLine(cx + nx * off - ux * 2400, cy + ny * off - uy * 2400,
                    cx + nx * off + ux * 2400, cy + ny * off + uy * 2400, 1.0, guideCol);
        }
    }

    private void drawStationRingAndOcta(HabiKineticGfx gfx, Cam cam, double u, double settle, double beatPulse) {
        double[] C = {HUB_X, HUB_Y, HUB_Z + 220.0};
        double[] R = euler(0.16 * Math.sin(u * 0.22) + 0.08, 0.25 * Math.sin(u * 0.16 + 0.8), 0.0);
        double r0 = 435.0 * lerp(1.32, 1.0, settle);

        int pn = 0;
        for (int i = 0; i <= 120; i++) {
            double[] pt = cam.p(ringPt(cam, C, R, (i / 120.0) * TAU, r0));
            if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
        }
        gfx.drawPolyline(polyScratch, pn, 1.1, scaleAlpha(PAL_LINE_MID, 0.82 * settle), false);

        int sn = 0;
        for (int i = 0; i < 72; i++) {
            double ang = (i / 72.0) * TAU;
            double len = (i % 6 == 0) ? 20.0 : 10.0;
            double[] p1 = ringPt(cam, C, R, ang, r0 + 5.0);
            double[] p2 = ringPt(cam, C, R, ang, r0 + len);
            sn = pushSeg(cam, p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], sn);
        }
        gfx.drawSegments(segScratch, sn, 1.0, scaleAlpha(PAL_FAINT, 0.82 * settle));

        double s0 = u * 0.45 + 0.4;
        pn = 0;
        for (int i = 0; i <= 32; i++) {
            double[] pt = cam.p(ringPt(cam, C, R, s0 + (i / 32.0) * 1.15, r0 + 15.0));
            if (pt != null) { polyScratch[pn * 2] = pt[0]; polyScratch[pn * 2 + 1] = pt[1]; pn++; }
        }
        gfx.drawPolyline(polyScratch, pn, 3.2 + beatPulse * 1.4, withAlpha(PAL_RED_BRIGHT, 0.9 * settle), false);

        double[] pb = cam.p(ringPt(cam, C, R, -u * 0.75 + 2.4, r0));
        if (pb != null) gfx.fillCircle(pb[0], pb[1], 4.5, withAlpha(PAL_BLUE, settle), 14);

        // Wireframe octahedron beacon
        double sc = lerp(1.6, 1.0, settle) + beatPulse * 0.03;
        double[] Ro = euler(0.45 + 0.05 * Math.sin(u * 0.3), 0.28 * u + 1.2, 0.07 * Math.sin(u * 0.2));
        double oR = 265.0 * sc;
        double[][] V = new double[OCTA_V.length][];
        for (int i = 0; i < OCTA_V.length; i++) {
            V[i] = cam.v(va(C, mv(Ro, new double[]{OCTA_V[i][0] * oR, OCTA_V[i][1] * oR * 1.3, OCTA_V[i][2] * oR})));
        }
        double zc = cam.v(C)[2];
        for (int j = 0; j < OCTA_E.length; j++) {
            double[] A = V[OCTA_E[j][0]], B = V[OCTA_E[j][1]];
            double[] pa = cam.p(A), pb2 = cam.p(B);
            if (pa == null || pb2 == null) continue;
            double dz = ((A[2] + B[2]) * 0.5 - zc) / oR;
            double ea = settle * lerp(0.85, 0.22, (dz + 1.0) * 0.5);
            boolean red = (j % 4 == 0);
            gfx.drawLine(pa[0], pa[1], pb2[0], pb2[1], red ? 1.8 : 1.1,
                    red ? withAlpha(PAL_RED, ea) : scaleAlpha(PAL_LINE, ea));
        }
    }

    private void drawViaduct(HabiKineticGfx gfx, HabiKineticAtlas atlas, Cam cam, double u,
                             double settle, double thT, double speedMul, boolean frontPass) {
        double r = TRACK_R * lerp(1.38, 1.0, settle);
        double[] R = mm(RZ(-0.22), RX(-1.16));
        double[] C = cam.v(HUB_X, HUB_Y + 34.0, HUB_Z);
        double[] M = mm(cam.R, R);
        double a = 0.92 * settle;

        double step = 0.035;
        int sn = 0;
        for (double rr : new double[]{r - 8.0, r + 8.0}) {
            for (double ang = 0.0; ang < TAU; ang += step) {
                double b = Math.min(ang + step, TAU);
                if (isBack(M, (ang + b) * 0.5) == frontPass) continue;
                double[] p0 = ringWorld(C, M, ang, rr, 0);
                double[] p1 = ringWorld(C, M, b, rr, 0);
                sn = pushSeg(cam, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], sn);
                if (sn >= 240) { gfx.drawSegments(segScratch, sn, 1.8, scaleAlpha(PAL_LINE, a)); sn = 0; }
            }
        }
        if (sn > 0) gfx.drawSegments(segScratch, sn, 1.8, scaleAlpha(PAL_LINE, a));

        sn = 0;
        for (double ang = 0.0; ang < TAU; ang += 0.075) {
            if (isBack(M, ang) == frontPass) continue;
            double[] p0 = ringWorld(C, M, ang, r - 14.0, 0);
            double[] p1 = ringWorld(C, M, ang, r + 14.0, 0);
            sn = pushSeg(cam, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], sn);
        }
        if (sn > 0) gfx.drawSegments(segScratch, sn, 1.2, scaleAlpha(PAL_LINE_MID, a));

        // Red braking trail
        double trailLen = clamp(0.42 * speedMul, 0.45, 2.2);
        double tail0 = thT - (2.0 * CAR_GAP) / r - 0.12;
        for (int i = 0; i < 24; i++) {
            double s0 = tail0 - (i / 24.0) * trailLen;
            double s1 = tail0 - ((i + 1) / 24.0) * trailLen;
            if (isBack(M, (s0 + s1) * 0.5) == frontPass) continue;
            double[] p0 = cam.p(ringWorld(C, M, s0, r, 0));
            double[] p1 = cam.p(ringWorld(C, M, s1, r, 0));
            if (p0 != null && p1 != null) {
                gfx.drawLine(p0[0], p0[1], p1[0], p1[1], 3.2, withAlpha(PAL_RED_BRIGHT, settle * (1.0 - i / 24.0) * 0.9));
            }
        }

        // 3 extruded red train cars
        record CarItem(double[] O, double[] Mc, HabiKineticAtlas.Slab tx) {}
        List<CarItem> cars = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) {
            double ang = thT - (c * CAR_GAP) / r;
            if (isBack(M, ang) == frontPass) continue;
            double ca = Math.cos(ang), sa = Math.sin(ang);
            double[] Rc = {-sa, -ca, 0, ca, -sa, 0, 0, 0, 1};
            double[] Mc = mm(M, Rc);
            double[] O = ringWorld(C, M, ang, r, -CAR_H);
            cars.add(new CarItem(O, Mc, c == 0 ? atlas.stCarLead : atlas.stCar));
        }
        cars.sort((p, q) -> Double.compare(q.O[2], p.O[2]));
        double carA = sat(u / 0.25 + 0.3);
        for (CarItem c : cars) gfx.drawSlab(cam, c.O, c.Mc, c.tx, CAR_H, carA, 0.0, false, false);
    }

    private void drawAppleBeacon(HabiKineticGfx gfx, HabiKineticAtlas atlas, Cam cam, double u, double settle, double beatPulse) {
        double bob = 9.0 * Math.sin(u * 1.15);
        double[] G = {HUB_X, HUB_Y - 12.0 + bob, HUB_Z};
        double[] Rg = euler(-0.08 + 0.03 * Math.sin(u * 0.7), -0.18 + 0.20 * Math.sin(u * 0.48), 0.0);
        double scaleIn = lerp(0.84, 1.0, eOutBack(sat(u / 0.85), 1.2));

        // Warm crimson/gold radial aura behind the Apple Beacon (matches station_scene.js & Act 1 frame 0)
        if (atlas.stationAuraTex != null) {
            double[] pHub = cam.p(cam.v(G));
            if (pHub != null) {
                double auraR = (255.0 + 24.0 * beatPulse) * pHub[2];
                gfx.drawGlow(atlas.stationAuraTex.getId(), pHub[0], pHub[1], auraR,
                        withAlpha(0xFFFFFF, 0.24 * settle), BlendMode.ADDITIVE);
            }
        }

        for (int which = 0; which <= 1; which++) {
            double[] loc = which == 1 ? new double[]{6, -124, 14} : new double[]{-6, -150, 14};
            double[] R = which == 1 ? euler(0, 0, 0.06 * Math.sin(u * 1.5)) : euler(0, 0, -0.2);
            double[] O = cam.v(va(G, mv(Rg, vs(loc, scaleIn))));
            double[] M = mm(cam.R, mm(Rg, msc(R, scaleIn)));
            gfx.drawSlab(cam, O, M, which == 1 ? atlas.stLeaf : atlas.stStem, 14.0, settle, 0.0, false, false);
        }

        double spread = 1.0 - eOutExpo(sat(u / 0.78));
        record BandItem(HabiKineticAtlas.Slab b, double[] O, double[] M) {}
        List<BandItem> items = new ArrayList<>(atlas.stBands.length);
        for (int i = 0; i < atlas.stBands.length; i++) {
            HabiKineticAtlas.Slab b = atlas.stBands[i];
            int dir = (i % 2 == 1) ? 1 : -1;
            double beatExpand = (i == 1) ? (-14.0 * beatPulse) : 0.0;
            double[] lp = {dir * 110.0 * spread, b.yc() * (1.0 + 0.35 * spread), -180.0 * spread + beatExpand};
            double[] R = euler(0, dir * 0.35 * spread, 0);
            double[] O = cam.v(va(G, mv(Rg, vs(lp, scaleIn))));
            double[] M = mm(cam.R, mm(Rg, msc(R, scaleIn)));
            items.add(new BandItem(b, O, M));
        }
        items.sort((p, q) -> Double.compare(q.O[2], p.O[2]));
        double bandA = sat(u / 0.25 + 0.25);
        for (BandItem it : items) gfx.drawSlab(cam, it.O, it.M, it.b, 38.0, bandA, 0.0, false, false);
    }

    private void drawFloatingPlates(HabiKineticGfx gfx, HabiKineticAtlas atlas, Cam cam, double u) {
        record Plate(HabiKineticAtlas.Slab tx, double[] p, double[] r, double d, double delay, int idx) {}
        Plate[] defs = {
                new Plate(atlas.plateStation, new double[]{HUB_X + 385, -245, 70}, new double[]{0.03, -0.28, 0.03}, 10, 0.14, 0),
                new Plate(atlas.plateRoute, new double[]{HUB_X + 345, 245, 110}, new double[]{-0.02, -0.24, -0.02}, 8, 0.26, 1)
        };
        for (Plate c : defs) {
            double k = eOutExpo(sat((u - c.delay) / 0.65));
            if (k <= 0.003) continue;
            double[] pos = {
                    c.p[0] + 220.0 * (1.0 - k),
                    c.p[1] + 8.0 * Math.sin(u * 0.8 + c.idx * 1.9),
                    c.p[2] + 260.0 * (1.0 - k)
            };
            double[] R = euler(c.r[0], c.r[1] - 0.55 * (1.0 - k), c.r[2]);
            gfx.drawSlab(cam, cam.v(pos), mm(cam.R, R), c.tx, c.d, k, 0.0, false, false);
        }
    }

    private void drawStationHud(HabiKineticGfx gfx, HabiKineticAtlas atlas, double u, double settle,
                                @Nullable HabiBootAudioEngine.Telemetry tel) {
        double a = settle * 0.9;
        if (a <= 0.005) return;
        double L = gfx.viewLeft(), T = gfx.viewTop(), R = gfx.viewRight(), B = gfx.viewBottom();
        double I = 34.0, len = 22.0;
        int lc = scaleAlpha(PAL_LINE_MID, a);

        gfx.drawLine(L + I, T + I + len, L + I, T + I, 1.5, lc);
        gfx.drawLine(L + I, T + I, L + I + len, T + I, 1.5, lc);
        gfx.drawLine(R - I - len, T + I, R - I, T + I, 1.5, lc);
        gfx.drawLine(R - I, T + I, R - I, T + I + len, 1.5, lc);
        gfx.drawLine(L + I, B - I - len, L + I, B - I, 1.5, lc);
        gfx.drawLine(L + I, B - I, L + I + len, B - I, 1.5, lc);
        gfx.drawLine(R - I - len, B - I, R - I, B - I, 1.5, lc);
        gfx.drawLine(R - I, B - I, R - I, B - I - len, 1.5, lc);

        // Top-right 16-bar real-time spectrum + BPM beat readout
        double bx = R - 64.0;
        int beatIdx = tel != null ? tel.beatIndex() : 0;
        for (int i = 0; i < 16; i++) {
            double v = tel != null ? tel.bar(i) : 0.25;
            double bh = 4.0 + v * 22.0;
            boolean isBeat = (beatIdx % 8) == (i / 2);
            int col = isBeat ? withAlpha(PAL_RED_BRIGHT, a)
                    : (i < 4 ? withAlpha(PAL_RED, a) : withAlpha(PAL_PAPER, 0.32 * a));
            gfx.fillRect(bx - (15 - i) * 8.0, T + 56.0 - bh, 3.0, bh, col);
        }
        String bgmStr = String.format(Locale.ROOT, "BGM // STATION 01 // %d BPM // BEAT 0%d",
                tel != null ? tel.bpm() : 100, beatIdx + 1);
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, bgmStr,
                bx, T + 76.0, 11.0, 3.0, withAlpha(PAL_MUTE, a), 1, 0.0);

        // Bottom-right live station clock
        String clock = String.format(Locale.ROOT, "ONLINE %02d:%02d.%02d   //   STATION 01   //   GAUGE 1435",
                (int) (u / 60.0), (int) (u % 60.0), (int) ((u % 1.0) * 100.0));
        atlas.drawAscii(gfx, HabiKineticAtlas.FontFace.MONO, clock,
                R - 54.0, B - 48.0, 11.0, 3.0, withAlpha(PAL_PAPER, a), 1, 0.0);

        // Right vertical Chinese stamp: 苹果列车 / 第一站
        String col1 = HabiZeroStationRenderer.BRAND_CN, col2 = "第一站";
        for (int i = 0; i < col1.length(); i++) {
            atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, col1.substring(i, i + 1),
                    R - HabiZeroStationRenderer.RIGHT_STAMP_COL1_INSET, T + 420.0 + i * 26.0, 18, 0f, withAlpha(PAL_MUTE, a), 0, 0.0, 0);
        }
        for (int i = 0; i < col2.length(); i++) {
            atlas.drawText(gfx, HabiKineticAtlas.FontFace.SANS_CN, col2.substring(i, i + 1),
                    R - HabiZeroStationRenderer.RIGHT_STAMP_COL2_INSET, T + 446.0 + i * 26.0, 18, 0f, withAlpha(PAL_RED_BRIGHT, a), 0, 0.0, 0);
        }
        gfx.fillRect(R - HabiZeroStationRenderer.RIGHT_STAMP_BAR_INSET, T + 386.0, 16.0, 2.0, withAlpha(PAL_RED_BRIGHT, a));
    }

    private static double[] ringPt(Cam cam, double[] C, double[] R, double ang, double r) {
        return cam.v(va(C, mv(R, new double[]{Math.cos(ang) * r, Math.sin(ang) * r, 0.0})));
    }

    private static boolean isBack(double[] M, double ang) {
        return (M[6] * Math.cos(ang) + M[7] * Math.sin(ang)) > 0.0;
    }

    private static double[] ringWorld(double[] C, double[] M, double ang, double rr, double hz) {
        double lx = rr * Math.cos(ang), ly = rr * Math.sin(ang);
        return new double[]{
                C[0] + M[0] * lx + M[1] * ly + M[2] * hz,
                C[1] + M[3] * lx + M[4] * ly + M[5] * hz,
                C[2] + M[6] * lx + M[7] * ly + M[8] * hz
        };
    }

    private int pushSeg(Cam cam, double ax, double ay, double az, double bx, double by, double bz, int n) {
        if (n * 4 + 4 > segScratch.length) return n;
        double[] pa = cam.project(ax, ay, az);
        double[] pb = cam.project(bx, by, bz);
        if (pa == null || pb == null) return n;
        int p = n * 4;
        segScratch[p] = pa[0];
        segScratch[p + 1] = pa[1];
        segScratch[p + 2] = pb[0];
        segScratch[p + 3] = pb[1];
        return n + 1;
    }
}
