package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayMusicPlan;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Detailed export page opened by the "Export Video" plaque above the replay table or from replay history:
 * match overview, perspective (global or single-player close-up), a collapsible video &amp; quality settings
 * page (quality/file-size tier, resolution, format, train-body view and soundtrack — folded by default so
 * the main page stays concise), the planned film length, live progress and the output folder.
 *
 * The client export task continues when this page closes.
 */
final class ReplayExportScreen extends Screen {
    static final int PANEL_HEIGHT_MAIN = 232;
    static final int PANEL_HEIGHT_SETTINGS = 268;
    static final int PANEL_HEIGHT_PICKER = 334;
    static final int MAIN_PROGRESS_SECTION = 2;
    static final int SETTINGS_SECTION_COUNT = 5;
    private static final int GAP = 4;
    private static final int PICKER_ROW_HEIGHT = 22;

    private final ClientLevel level;
    private final BlockPos pos;
    private final Screen parent;
    private final UUID history;
    private ReplayExportResolution resolution;
    private boolean compress;
    private ReplayExportQuality quality;
    private ReplayMusicPlan.Level music;
    private @Nullable UUID focusPlayer;
    private final Button[] qualityButtons = new Button[ReplayExportQuality.values().length];
    private final Button[] resolutionButtons = new Button[ReplayExportResolution.values().length];
    private final Button[] musicButtons = new Button[ReplayMusicPlan.Level.values().length];
    private final List<Button> mainWidgets = new ArrayList<>();
    private final List<Button> settingsWidgets = new ArrayList<>();
    private Button mp4, avi, roofCut, roofFull, collapseSettingsButton;
    private Button perspectiveGlobal, perspectivePrev, perspectivePlayer, perspectiveNext;
    private Button videoSettingsButton, start, folderOrImport;
    private int left, panelWidth;
    private int mainTop, mainPanelHeight;
    private int settingsTop, settingsPanelHeight;
    private int pickerTop, pickerPanelHeight;
    private ExportLayout mainLayout, settingsLayout;
    private ClientReplayBoard.View view;
    private ReplayVideoExport video;
    private boolean folderFailed;
    private boolean historyPinned;
    private @Nullable LastMatchReplay secondsFor;
    private @Nullable UUID secondsFocus;
    private double filmSeconds;
    private @Nullable LastMatchReplay participantsFor;
    private List<LastMatchReplay.Participant> participants = List.of();
    private boolean settingsOpen;
    private boolean pickerOpen;
    private int pickerScroll;

    ReplayExportScreen(ClientLevel level, BlockPos pos) {
        super(tr("export.title"));
        this.level = level;
        this.parent = null;
        this.history = null;
        this.pos = pos.immutable();
        this.resolution = ReplayExportResolution.fromId(ClientVisualPreferences.getReplayVideoResolution());
        this.compress = ClientVisualPreferences.isReplayVideoCompress() || !this.resolution.aviSupported();
        this.quality = ReplayExportQuality.fromId(ClientVisualPreferences.getReplayVideoQuality());
        this.music = ReplayMusicPlan.Level.fromId(ClientVisualPreferences.getReplayVideoMusic());
    }

    ReplayExportScreen(Screen parent, java.util.UUID history) {
        super(tr("export.title"));
        this.level = null;
        this.pos = null;
        this.parent = parent;
        this.history = history;
        this.resolution = ReplayExportResolution.fromId(ClientVisualPreferences.getReplayVideoResolution());
        this.compress = ClientVisualPreferences.isReplayVideoCompress() || !this.resolution.aviSupported();
        this.quality = ReplayExportQuality.fromId(ClientVisualPreferences.getReplayVideoQuality());
        this.music = ReplayMusicPlan.Level.fromId(ClientVisualPreferences.getReplayVideoMusic());
    }

    @Override public void onClose() {
        if (parent == null) super.onClose();
        else minecraft.setScreen(parent);
    }

    @Override public void removed() {
        if (historyPinned) {
            ReplayHistoryClient.unpin(history);
            historyPinned = false;
        }
        super.removed();
    }

    private static Component tr(String key, Object... args) {
        return ReplayBoardTexts.tr(key, args);
    }

    // ------------------------------------------------------------------ layout & view switching

    private <T extends Button> T addMainWidget(T widget) {
        mainWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private <T extends Button> T addSettingsWidget(T widget) {
        settingsWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private int activeTop() {
        return pickerOpen ? pickerTop : settingsOpen ? settingsTop : mainTop;
    }

    private int activePanelHeight() {
        return pickerOpen ? pickerPanelHeight : settingsOpen ? settingsPanelHeight : mainPanelHeight;
    }

    private void setSettingsOpen(boolean open) {
        boolean next = open && !exporting();
        if (settingsOpen == next && (!next || !pickerOpen)) {
            syncWidgetsVisible();
            return;
        }
        settingsOpen = next;
        if (settingsOpen) {
            pickerOpen = false;
        }
        setFocused(null);
        syncWidgetsVisible();
    }

    private void setPickerOpen(boolean open) {
        boolean next = open && !participants.isEmpty() && !exporting();
        if (pickerOpen == next && (!next || !settingsOpen)) {
            syncWidgetsVisible();
            return;
        }
        pickerOpen = next;
        if (pickerOpen) {
            settingsOpen = false;
            pickerScroll = 0;
        }
        setFocused(null);
        syncWidgetsVisible();
    }

    private void syncWidgetsVisible() {
        boolean showMain = !pickerOpen && !settingsOpen;
        boolean showSettings = settingsOpen && !pickerOpen;
        for (Button widget : mainWidgets) {
            widget.visible = showMain;
        }
        for (Button widget : settingsWidgets) {
            widget.visible = showSettings;
        }
    }

    @Override protected void init() {
        mainWidgets.clear();
        settingsWidgets.clear();
        if (!historyPinned) {
            ReplayHistoryClient.pin(history);
            historyPinned = true;
        }
        panelWidth = Math.min(348, width - 12);
        left = (width - panelWidth) / 2;

        mainPanelHeight = Math.min(PANEL_HEIGHT_MAIN, height - 6);
        mainTop = (height - mainPanelHeight) / 2;
        mainLayout = ExportLayout.forMain(mainPanelHeight);

        settingsPanelHeight = Math.min(PANEL_HEIGHT_SETTINGS, height - 6);
        settingsTop = (height - settingsPanelHeight) / 2;
        settingsLayout = ExportLayout.forSettings(settingsPanelHeight);

        pickerPanelHeight = Math.min(PANEL_HEIGHT_PICKER, height - 6);
        pickerTop = (height - pickerPanelHeight) / 2;

        int inner = panelWidth - 24, x = left + 12;
        int half = (inner - GAP) / 2;
        int otherHalf = inner - half - GAP;

        // ---- Main (collapsed) widgets: Perspective + Folded Video Settings Row + Bottom Actions ----
        int mainOptH = mainLayout.optionHeight();
        int globalW = Math.min(86, inner / 3);
        int arrowW = 18;
        int playerAreaX = x + globalW + GAP;
        int playerAreaW = inner - globalW - GAP;
        int playerMidW = Math.max(40, playerAreaW - (arrowW + GAP) * 2);
        perspectiveGlobal = addMainWidget(new BrassButton(x, mainSectionY(0), globalW, mainOptH,
                tr("export.perspective_global"), b -> {
                    focusPlayer = null;
                    setPickerOpen(false);
                    refresh();
                }));
        perspectiveGlobal.setTooltip(Tooltip.create(tr("export.perspective_global_tip")));
        perspectivePrev = addMainWidget(new BrassButton(playerAreaX, mainSectionY(0), arrowW, mainOptH,
                Component.literal("‹"), b -> stepFocusPlayer(-1)));
        perspectivePrev.setTooltip(Tooltip.create(tr("export.perspective_cycle_tip")));
        perspectivePlayer = addMainWidget(new BrassButton(playerAreaX + arrowW + GAP, mainSectionY(0), playerMidW, mainOptH,
                tr("export.perspective_pick"), b -> {
                    if (!participants.isEmpty()) {
                        setPickerOpen(!pickerOpen);
                    }
                }));
        perspectivePlayer.setTooltip(Tooltip.create(tr("export.perspective_player_tip")));
        perspectiveNext = addMainWidget(new BrassButton(playerAreaX + arrowW + GAP + playerMidW + GAP, mainSectionY(0),
                Math.max(arrowW, x + inner - (playerAreaX + arrowW + GAP + playerMidW + GAP)), mainOptH,
                Component.literal("›"), b -> stepFocusPlayer(1)));
        perspectiveNext.setTooltip(Tooltip.create(tr("export.perspective_cycle_tip")));

        videoSettingsButton = addMainWidget(new BrassButton(x, mainSectionY(1), inner, mainOptH,
                Component.empty(), b -> {
                    setSettingsOpen(true);
                    refresh();
                }));
        videoSettingsButton.setTooltip(Tooltip.create(tr("export.video_settings_tip")));

        int third = (inner - GAP * 2) / 3;
        int mainButtonsY = mainTop + mainLayout.buttonsY();
        start = addMainWidget(new BrassButton(x, mainButtonsY, third, 20, tr("export.start"), b -> toggleExport()));
        folderOrImport = addMainWidget(new BrassButton(x + third + GAP, mainButtonsY, third, 20, tr("controls.folder"), b -> {
            if (needsFfmpegAction()) {
                ReplayFfmpegManager.startManualImport(minecraft.gameDirectory.toPath());
            } else {
                folderFailed = !ReplayVideoExport.openFolder(minecraft);
            }
            refresh();
        }));
        addMainWidget(new BrassButton(x + (third + GAP) * 2, mainButtonsY, inner - (third + GAP) * 2, 20,
                CommonComponents.GUI_DONE, b -> onClose()));

        // ---- Folded Video & Quality Settings widgets ----
        int setOptH = settingsLayout.optionHeight();
        ReplayExportQuality[] qualities = ReplayExportQuality.values();
        int qualW = (inner - GAP * (qualities.length - 1)) / qualities.length;
        for (int i = 0; i < qualities.length; i++) {
            ReplayExportQuality value = qualities[i];
            int bx = x + i * (qualW + GAP);
            int bw = i == qualities.length - 1 ? x + inner - bx : qualW;
            qualityButtons[i] = addSettingsWidget(new BrassButton(bx, settingsSectionY(0), bw, setOptH,
                    value.label(), b -> {
                        quality = value;
                        ClientVisualPreferences.setReplayVideoQuality(value.id);
                        refresh();
                    }));
        }

        ReplayExportResolution[] all = ReplayExportResolution.values();
        int resW = (inner - GAP * (all.length - 1)) / all.length;
        for (int i = 0; i < all.length; i++) {
            ReplayExportResolution value = all[i];
            int bx = x + i * (resW + GAP);
            int bw = i == all.length - 1 ? x + inner - bx : resW;
            resolutionButtons[i] = addSettingsWidget(new BrassButton(bx, settingsSectionY(1), bw, setOptH,
                    value.label(), b -> {
                        resolution = value;
                        if (!resolution.aviSupported()) compress = true;
                        ClientVisualPreferences.setReplayVideoResolution(value.id);
                        if (!resolution.aviSupported()) ClientVisualPreferences.setReplayVideoCompress(true);
                        refresh();
                    }));
            resolutionButtons[i].setTooltip(Tooltip.create(tr("export.res_tooltip", value.dimensions())));
        }

        mp4 = addSettingsWidget(new BrassButton(x, settingsSectionY(2), half, setOptH, tr("export.format_mp4"), b -> {
            compress = true;
            ClientVisualPreferences.setReplayVideoCompress(true);
            refresh();
        }));
        mp4.setTooltip(Tooltip.create(tr("controls.compress_tooltip_on")));
        avi = addSettingsWidget(new BrassButton(x + half + GAP, settingsSectionY(2), otherHalf, setOptH, tr("export.format_avi"), b -> {
            compress = false;
            ClientVisualPreferences.setReplayVideoCompress(false);
            refresh();
        }));
        avi.setTooltip(Tooltip.create(tr("controls.compress_tooltip_off")));

        roofCut = addSettingsWidget(new BrassButton(x, settingsSectionY(3), half, setOptH, tr("export.roof_cut"), b -> {
            if (ReplayBoardLocalState.fullBody()) ReplayBoardLocalState.toggleRoof();
            refresh();
        }));
        roofFull = addSettingsWidget(new BrassButton(x + half + GAP, settingsSectionY(3), otherHalf, setOptH, tr("export.roof_full"), b -> {
            if (!ReplayBoardLocalState.fullBody()) ReplayBoardLocalState.toggleRoof();
            refresh();
        }));

        ReplayMusicPlan.Level[] levels = ReplayMusicPlan.Level.values();
        int musicW = (inner - GAP * (levels.length - 1)) / levels.length;
        for (int i = 0; i < levels.length; i++) {
            ReplayMusicPlan.Level value = levels[i];
            int bx = x + i * (musicW + GAP);
            int bw = i == levels.length - 1 ? x + inner - bx : musicW;
            musicButtons[i] = addSettingsWidget(new BrassButton(bx, settingsSectionY(4), bw, setOptH,
                    tr("export.music_" + value.id), b -> {
                        music = value;
                        ClientVisualPreferences.setReplayVideoMusic(value.id);
                        refresh();
                    }));
            musicButtons[i].setTooltip(Tooltip.create(tr(value.enabled() ? "export.music_tooltip" : "export.music_off_tooltip")));
        }

        collapseSettingsButton = addSettingsWidget(new BrassButton(
                x, settingsTop + settingsLayout.buttonsY(), inner, 20,
                tr("export.settings_collapse"), b -> {
                    setSettingsOpen(false);
                    refresh();
                }));

        refresh();
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pickerOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                setPickerOpen(false);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
                int step = keyCode == GLFW.GLFW_KEY_PAGE_UP
                        ? Math.max(PICKER_ROW_HEIGHT, pickerListBottom() - pickerListTop() - PICKER_ROW_HEIGHT)
                        : PICKER_ROW_HEIGHT;
                pickerScroll = Math.clamp(pickerScroll - step, 0, maxPickerScroll());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
                int step = keyCode == GLFW.GLFW_KEY_PAGE_DOWN
                        ? Math.max(PICKER_ROW_HEIGHT, pickerListBottom() - pickerListTop() - PICKER_ROW_HEIGHT)
                        : PICKER_ROW_HEIGHT;
                pickerScroll = Math.clamp(pickerScroll + step, 0, maxPickerScroll());
                return true;
            }
            return true;
        }
        if (settingsOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            setSettingsOpen(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (pickerOpen) {
            return handlePickerClick(mouseX, mouseY, button);
        }
        if (settingsOpen) {
            int closeX0 = settingsCloseX(), closeY0 = settingsCloseY();
            if (button == 0 && mouseX >= closeX0 && mouseX < closeX0 + 18
                    && mouseY >= closeY0 && mouseY < closeY0 + 16) {
                setSettingsOpen(false);
                if (minecraft != null && collapseSettingsButton != null) {
                    collapseSettingsButton.playDownSound(minecraft.getSoundManager());
                }
                return true;
            }
            if (mouseX < left || mouseX >= left + panelWidth
                    || mouseY < settingsTop || mouseY >= settingsTop + settingsPanelHeight) {
                setSettingsOpen(false);
                return true;
            }
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (!settingsOpen) {
                setFocused(null);
            }
            return handled;
        }
        if (button == 1 && perspectivePlayer != null && perspectivePlayer.active
                && perspectivePlayer.isMouseOver(mouseX, mouseY)) {
            stepFocusPlayer(-1);
            if (minecraft != null) perspectivePlayer.playDownSound(minecraft.getSoundManager());
            return true;
        }
        if (button == 1 && folderOrImport != null && folderOrImport.active
                && folderOrImport.isMouseOver(mouseX, mouseY) && needsFfmpegAction()) {
            folderFailed = !ReplayFfmpegManager.openBinFolder(minecraft.gameDirectory.toPath());
            if (minecraft != null) folderOrImport.playDownSound(minecraft.getSoundManager());
            refresh();
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        // ContainerEventHandler.mouseClicked focuses the clicked button after onPress(); clear it if
        // the click opened a sub-view so no hidden widget keeps focus or queues a phantom tooltip.
        if (pickerOpen || settingsOpen) {
            setFocused(null);
        }
        return handled;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (pickerOpen) {
            int maxScroll = maxPickerScroll();
            if (maxScroll > 0 && vertical != 0) {
                pickerScroll = Math.clamp(pickerScroll - (int) Math.signum(vertical) * PICKER_ROW_HEIGHT, 0, maxScroll);
            }
            return true;
        }
        if (!settingsOpen && !exporting() && !participants.isEmpty() && vertical != 0 && isOverPerspectiveRow(mouseX, mouseY)) {
            stepFocusPlayer(vertical > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private boolean isOverPerspectiveRow(double mouseX, double mouseY) {
        return (perspectivePlayer != null && perspectivePlayer.isMouseOver(mouseX, mouseY))
                || (perspectivePrev != null && perspectivePrev.isMouseOver(mouseX, mouseY))
                || (perspectiveNext != null && perspectiveNext.isMouseOver(mouseX, mouseY));
    }

    private void stepFocusPlayer(int delta) {
        if (exporting() || participants.isEmpty()) return;
        int current = participantIndex(focusPlayer);
        if (current < 0) {
            focusPlayer = delta >= 0 ? participants.getFirst().id() : participants.getLast().id();
        } else {
            int next = Math.floorMod(current + delta, participants.size());
            focusPlayer = participants.get(next).id();
        }
        refresh();
    }

    private int participantIndex(@Nullable UUID id) {
        if (id == null) return -1;
        for (int i = 0; i < participants.size(); i++) {
            if (id.equals(participants.get(i).id())) return i;
        }
        return -1;
    }

    private void syncParticipants(@Nullable LastMatchReplay replay) {
        if (replay == participantsFor) return;
        participantsFor = replay;
        if (replay == null || replay.participants().isEmpty()) {
            participants = List.of();
            if (!exporting()) focusPlayer = null;
            setPickerOpen(false);
            return;
        }
        UUID selfId = minecraft != null && minecraft.player != null ? minecraft.player.getUUID() : null;
        List<LastMatchReplay.Participant> sorted = new ArrayList<>(replay.participants());
        sorted.sort(Comparator.comparing((LastMatchReplay.Participant p) -> !p.id().equals(selfId))
                .thenComparing(p -> !p.winner())
                .thenComparingInt(p -> -p.kills())
                .thenComparingInt(p -> -p.score())
                .thenComparing(LastMatchReplay.Participant::name, String.CASE_INSENSITIVE_ORDER));
        participants = List.copyOf(sorted);
        if (focusPlayer != null && participantIndex(focusPlayer) < 0 && !exporting()) {
            focusPlayer = null;
        }
    }

    private boolean ffmpegReady() {
        return minecraft != null && ReplayFfmpegManager.isReady(minecraft.gameDirectory.toPath());
    }

    private boolean needsFfmpegAction() {
        return !exporting() && compress && !ffmpegReady();
    }

    private void toggleExport() {
        var current = ReplayExportClient.current();
        if (current != null && current.running()) {
            current.cancel();
        } else if (compress && !ffmpegReady()) {
            if (ReplayFfmpegManager.isInstalling()) {
                ReplayFfmpegManager.cancelInstall();
            } else if (!ReplayFfmpegManager.isImporting()) {
                folderFailed = false;
                ReplayFfmpegManager.startInstall(minecraft.gameDirectory.toPath());
            }
        } else {
            folderFailed = false;
            setPickerOpen(false);
            setSettingsOpen(false);
            var id = history;
            var board = pos;
            Supplier<ClientReplayBoard.View> source = id == null
                    ? () -> ClientReplayBoard.viewAt(board) : () -> ReplayHistoryClient.watch(id);
            ReplayExportClient.start(source.get(), ReplayBoardLocalState.fullBody(), resolution, compress,
                    quality, music, focusPlayer, source);
        }
        refresh();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        if (!validTable()) { onClose(); return; }
        refresh();
    }

    private boolean validTable() {
        if (history != null) return minecraft != null && minecraft.level != null;
        if (minecraft == null || minecraft.level != level || level == null || minecraft.player == null
                || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || !level.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)) return false;
        double reach = level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker
                ? marker.settings().interactReach() : 8;
        // A little slack: the player can drift while the page is open.
        double limit = reach + 6;
        return minecraft.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= limit * limit;
    }

    private boolean exporting() { return video != null && video.running(); }

    private void refresh() {
        view = history == null ? ClientReplayBoard.viewAt(pos) : ReplayHistoryClient.watch(history);
        video = ReplayExportClient.forScreen(video);
        syncParticipants(view == null ? null : view.replay());
        boolean exporting = exporting();
        if (exporting) {
            setPickerOpen(false);
            setSettingsOpen(false);
        } else if (participants.isEmpty()) {
            setPickerOpen(false);
        } else {
            syncWidgetsVisible();
        }
        ReplayExportResolution shownRes = exporting ? video.resolution : resolution;
        boolean shownCompress = exporting ? video.compress : compress;
        ReplayExportQuality shownQuality = exporting ? video.quality : quality;
        ReplayMusicPlan.Level shownMusic = exporting ? video.music : music;
        UUID shownFocus = exporting ? video.focusPlayer : focusPlayer;
        boolean full = ReplayBoardLocalState.fullBody();

        ReplayExportQuality[] qualities = ReplayExportQuality.values();
        for (int i = 0; i < qualities.length; i++) {
            ReplayExportQuality candidate = qualities[i];
            qualityButtons[i].active = !exporting && shownCompress;
            ((BrassButton) qualityButtons[i]).selected = shownCompress && candidate == shownQuality;
            qualityButtons[i].setTooltip(Tooltip.create(shownCompress
                    ? candidate.tooltip() : tr("export.quality_avi_disabled")));
        }

        ReplayExportResolution[] all = ReplayExportResolution.values();
        for (int i = 0; i < all.length; i++) {
            resolutionButtons[i].active = !exporting;
            ((BrassButton) resolutionButtons[i]).selected = all[i] == shownRes;
        }
        mp4.active = !exporting;
        avi.active = !exporting && resolution.aviSupported();
        ((BrassButton) mp4).selected = shownCompress;
        ((BrassButton) avi).selected = !shownCompress;
        avi.setTooltip(Tooltip.create(resolution.aviSupported()
                ? tr("controls.compress_tooltip_off") : tr("export.avi_2160")));
        roofCut.active = roofFull.active = !exporting;
        ((BrassButton) roofCut).selected = !full;
        ((BrassButton) roofFull).selected = full;
        ReplayMusicPlan.Level[] levels = ReplayMusicPlan.Level.values();
        for (int i = 0; i < levels.length; i++) {
            musicButtons[i].active = !exporting;
            ((BrassButton) musicButtons[i]).selected = levels[i] == shownMusic;
        }

        Component roofLabel = tr(full ? "export.roof_full" : "export.roof_cut");
        Component musicLabel = tr("export.music_" + shownMusic.id);
        videoSettingsButton.active = !exporting;
        videoSettingsButton.setMessage(shownCompress
                ? tr("export.video_settings_summary_mp4", shownRes.label(), shownQuality.label(), roofLabel, musicLabel)
                : tr("export.video_settings_summary_avi", shownRes.label(), roofLabel, musicLabel));

        boolean hasPlayers = !participants.isEmpty();
        int focusedIdx = participantIndex(shownFocus);
        perspectiveGlobal.active = !exporting;
        ((BrassButton) perspectiveGlobal).selected = focusedIdx < 0;
        perspectivePrev.active = !exporting && hasPlayers;
        perspectiveNext.active = !exporting && hasPlayers;
        perspectivePlayer.active = !exporting && hasPlayers;
        ((BrassButton) perspectivePlayer).selected = focusedIdx >= 0;
        ((BrassButton) perspectivePlayer).focusTint = focusedIdx >= 0;
        if (focusedIdx >= 0) {
            var p = participants.get(focusedIdx);
            perspectivePlayer.setMessage(tr("export.perspective_selected",
                    ReplayBoardTexts.name(p), focusedIdx + 1, participants.size()));
        } else {
            perspectivePlayer.setMessage(tr(hasPlayers ? "export.perspective_pick" : "export.perspective_none"));
        }

        boolean needFfmpeg = needsFfmpegAction();
        boolean installing = ReplayFfmpegManager.isInstalling();
        boolean importing = ReplayFfmpegManager.isImporting();
        if (exporting) {
            start.setMessage(tr("controls.cancel"));
            ((BrassButton) start).accent = true;
            start.active = true;
            start.setTooltip(null);
        } else if (needFfmpeg) {
            start.setMessage(installing ? tr("export.ffmpeg_cancel") : tr("export.ffmpeg_install"));
            ((BrassButton) start).accent = installing;
            start.active = installing || !importing;
            start.setTooltip(Tooltip.create(tr("export.ffmpeg_install_tip")));
        } else {
            start.setMessage(tr("export.start"));
            ((BrassButton) start).accent = false;
            start.active = ReplayExportClient.canStart() && ReplayExportClient.mapReady(view);
            start.setTooltip(null);
        }
        if (needFfmpeg) {
            folderOrImport.setMessage(tr("export.ffmpeg_import"));
            folderOrImport.active = !installing && !importing;
            folderOrImport.setTooltip(Tooltip.create(tr("export.ffmpeg_import_tip")));
        } else {
            folderOrImport.setMessage(tr("controls.folder"));
            folderOrImport.active = true;
            folderOrImport.setTooltip(null);
        }
    }

    // ------------------------------------------------------------------ text

    private double filmSeconds() {
        LastMatchReplay replay = view == null ? null : view.replay();
        UUID shownFocus = exporting() ? video.focusPlayer : focusPlayer;
        if (replay != secondsFor || !Objects.equals(shownFocus, secondsFocus)) {
            secondsFor = replay;
            secondsFocus = shownFocus;
            filmSeconds = 0;
            if (replay != null) {
                try {
                    filmSeconds = new ReplayVideoScript(replay, shownFocus).total();
                } catch (RuntimeException ignored) {
                    filmSeconds = 0;
                }
            }
        }
        return filmSeconds;
    }

    private Component matchStatus() {
        if (view == null || !view.hasRound()) return tr("controls.no_round");
        if (view.replay() == null) return tr("controls.loading_round");
        return tr("controls.round", view.replay().mapName());
    }

    private Component statsLine() {
        var replay = view == null ? null : view.replay();
        if (replay == null) return Component.empty();
        return tr("export.stats", ReplayBoardTexts.clock(replay.durationTicks()), replay.participants().size(),
                replay.killCount());
    }

    private Component resultLine() {
        var replay = view == null ? null : view.replay();
        if (replay == null) return Component.empty();
        return tr("export.result", ReplayBoardTexts.winTitle(replay));
    }

    private Component settingsSubtitle() {
        return compress
                ? tr("export.settings_subtitle_mp4", resolution.dimensions(), quality.label())
                : tr("export.settings_subtitle_avi", resolution.dimensions());
    }

    private Component exportStatus() {
        if (folderFailed) return tr("controls.folder_error");
        if (video == null) {
            if (needsFfmpegAction()) return ReplayFfmpegManager.statusText();
            if (history != null && ReplayHistoryClient.voiceUnavailable(history)) return tr("controls.missing_voice_hint");
            if (history != null && !ReplayExportClient.mapReady(view)) return ReplayHistoryScreen.loadStatus(history, view);
            if (view != null && view.presentSections() < view.totalSections()) {
                return tr("controls.receiving_map", view.presentSections(), view.totalSections());
            }
            if (history == null && view != null && view.replay() != null && !ClientReplayBoard.mapAvailable()) {
                return tr("controls.map_unavailable");
            }
            double seconds = filmSeconds();
            return seconds > 0 ? tr("export.detail", resolution.dimensions(), Math.round(seconds))
                    : tr("export.detail_short", resolution.dimensions());
        }
        return video.status();
    }

    // ------------------------------------------------------------------ rendering

    /** The page plate is drawn here so the widgets (drawn by {@code super.render}) end up on top of it. */
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int top = activeTop();
        int panelHeight = activePanelHeight();
        g.fillGradient(0, 0, width, height, 0x70040810, 0xB0040810);
        g.fillGradient(left, top, left + panelWidth, top + panelHeight, 0xF2111A33, 0xF204060D);
        g.renderOutline(left, top, panelWidth, panelHeight, ReplayPalette.BRASS);
        g.renderOutline(left + 3, top + 3, panelWidth - 6, panelHeight - 6, 0x998A6A35);
        if (pickerOpen) {
            return;
        }
        if (settingsOpen) {
            renderSettingsBackground(g);
            return;
        }

        int cx = left + panelWidth / 2;
        int titleW = font.width(title);
        int ruleY = top + 14;
        int lx0 = left + 14, lx1 = cx - titleW / 2 - 14, rx0 = cx + titleW / 2 + 14, rx1 = left + panelWidth - 14;
        g.fill(lx0, ruleY, Math.max(lx0, lx1), ruleY + 1, ReplayPalette.BRASS_DIM);
        g.fill(Math.min(rx0, rx1), ruleY, rx1, ruleY + 1, ReplayPalette.BRASS_DIM);
        diamond(g, lx1 + 5, ruleY, ReplayPalette.BRASS_LIGHT);
        diamond(g, rx0 - 5, ruleY, ReplayPalette.BRASS_LIGHT);

        // Overview card.
        int cardTop = top + 38, cardBottom = Math.min(top + 74, mainSectionY(0) - 14);
        if (cardBottom > cardTop) g.fill(left + 12, cardTop, left + panelWidth - 12, cardBottom, 0x99070B18);
        if (cardBottom > cardTop) g.renderOutline(left + 12, cardTop, panelWidth - 24, cardBottom - cardTop, 0x668A6A35);

        // Collapsed main page section captions.
        caption(g, tr("export.perspective"), mainSectionY(0) - 10);
        caption(g, tr("export.video_settings"), mainSectionY(1) - 10);

        // Progress bar track.
        int barY = mainSectionY(MAIN_PROGRESS_SECTION) - 5, barX = left + 12, barW = panelWidth - 24;
        g.fill(barX, barY, barX + barW, barY + 7, 0xFF1A2236);
        g.renderOutline(barX - 1, barY - 1, barW + 2, 9, 0x808A6A35);
    }

    private void renderSettingsBackground(GuiGraphics g) {
        String settingsTitle = font.plainSubstrByWidth(
                tr("export.settings_title").getString(), Math.max(40, panelWidth - 64));
        int cx = left + panelWidth / 2;
        int titleW = font.width(settingsTitle);
        int ruleY = settingsTop + 14;
        int closeX0 = settingsCloseX();
        int lx0 = left + 14, lx1 = cx - titleW / 2 - 14;
        int rx0 = cx + titleW / 2 + 14, rx1 = closeX0 - 8;
        if (lx1 > lx0 + 4) {
            g.fill(lx0, ruleY, lx1, ruleY + 1, ReplayPalette.BRASS_DIM);
            diamond(g, lx1 + 5, ruleY, ReplayPalette.BRASS_LIGHT);
        }
        if (rx1 > rx0 + 4) {
            g.fill(rx0, ruleY, rx1, ruleY + 1, ReplayPalette.BRASS_DIM);
            diamond(g, rx0 - 5, ruleY, ReplayPalette.BRASS_LIGHT);
        }

        caption(g, tr("export.quality"), settingsSectionY(0) - 10);
        caption(g, tr("export.resolution"), settingsSectionY(1) - 10);
        caption(g, tr("export.format"), settingsSectionY(2) - 10);
        caption(g, tr("export.roof"), settingsSectionY(3) - 10);
        caption(g, tr("export.music"), settingsSectionY(4) - 10);
    }

    private int mainSectionY(int index) {
        return mainTop + mainLayout.sectionY(index);
    }

    private int settingsSectionY(int index) {
        return settingsTop + settingsLayout.sectionY(index);
    }

    private int settingsCloseX() { return left + panelWidth - 26; }
    private int settingsCloseY() { return settingsTop + 7; }

    record ExportLayout(int firstY, int spacing, int optionHeight, int buttonsY) {
        /** Collapsed main view: 2 option sections (Perspective, Video Settings) + progress bar + bottom buttons. */
        static ExportLayout forMain(int panelHeight) {
            int buttonsY = panelHeight - (panelHeight < 190 ? 26 : panelHeight < 220 ? 28 : 30);
            int progressY = buttonsY - 21;
            int firstY = Math.clamp(panelHeight - 138, 40, 88);
            int spacing = Math.min(36, (progressY - firstY) / MAIN_PROGRESS_SECTION);
            return new ExportLayout(firstY, spacing, Math.clamp(spacing - 12, 8, 20), buttonsY);
        }

        /** Expanded video &amp; quality settings view: 5 option sections + bottom collapse button. */
        static ExportLayout forSettings(int panelHeight) {
            int buttonsY = panelHeight - (panelHeight < 210 ? 24 : 28);
            int firstY = Math.clamp(panelHeight - 216, 36, 48);
            int spacing = Math.min(36, (buttonsY - 2 - firstY) / SETTINGS_SECTION_COUNT);
            return new ExportLayout(firstY, spacing, Math.clamp(spacing - 11, 8, 20), buttonsY);
        }

        int sectionY(int index) { return firstY + index * spacing; }
    }

    private void caption(GuiGraphics g, Component text, int y) {
        g.drawString(font, text, left + 14, y, ReplayPalette.TEXT_MUTED, false);
    }

    private static void diamond(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y - 2, x + 1, y + 3, color);
        g.fill(x - 1, y - 1, x + 2, y + 2, color);
        g.fill(x - 2, y, x + 3, y + 1, color);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        syncWidgetsVisible();
        super.render(graphics, pickerOpen ? -1 : mouseX, pickerOpen ? -1 : mouseY, delta);
        if (pickerOpen) {
            renderPicker(graphics, mouseX, mouseY);
            return;
        }
        if (settingsOpen) {
            renderSettingsForeground(graphics, mouseX, mouseY);
            return;
        }

        int top = mainTop;
        int panelHeight = mainPanelHeight;
        int cx = width / 2;
        int textW = panelWidth - 24;
        graphics.drawCenteredString(font, title, cx, top + 10, ReplayPalette.BRASS_LIGHT);
        if (mainLayout.firstY() >= 46) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(matchStatus().getString(), textW), cx, top + 24, ReplayPalette.TEXT);
        }
        if (mainSectionY(0) >= top + 72) {
            graphics.drawString(font, font.plainSubstrByWidth(statsLine().getString(), textW - 8), left + 18, top + 43, ReplayPalette.TEXT, false);
        }
        if (mainSectionY(0) >= top + 82) {
            graphics.drawString(font, font.plainSubstrByWidth(resultLine().getString(), textW - 8), left + 18, top + 58,
                    view != null && view.replay() != null ? ReplayBoardTexts.winColor(view.replay()) : ReplayPalette.TEXT_MUTED, false);
        }

        int barX = left + 12, barW = panelWidth - 24, barY = mainSectionY(MAIN_PROGRESS_SECTION) - 5;
        if (video != null) {
            graphics.fill(barX, barY, barX + (int) (barW * video.displayProgress()), barY + 7, 0xFF52C5B9);
        } else if (needsFfmpegAction() && ReplayFfmpegManager.isInstalling()) {
            graphics.fill(barX, barY, barX + (int) (barW * ReplayFfmpegManager.progress()), barY + 7, ReplayPalette.BRASS_LIGHT);
        }
        graphics.drawCenteredString(font, font.plainSubstrByWidth(exportStatus().getString(), textW), cx, barY + 12, 0xFFC5D3E2);
        Component note = exporting() ? tr("export.continue_in_background")
                : needsFfmpegAction() ? tr(resolution.aviSupported() ? "export.ffmpeg_fallback_hint" : "export.ffmpeg_bin_hint")
                : resolution.highLoad() ? tr("export.warn_high") : null;
        if (note != null && barY + 24 < top + panelHeight - 40) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(note.getString(), textW), cx, barY + 24,
                    exporting() ? ReplayPalette.TEXT_FAINT : ReplayPalette.LAMP);
        }
    }

    private void renderSettingsForeground(GuiGraphics g, int mouseX, int mouseY) {
        String settingsTitle = font.plainSubstrByWidth(
                tr("export.settings_title").getString(), Math.max(40, panelWidth - 64));
        int cx = left + panelWidth / 2;
        g.drawCenteredString(font, settingsTitle, cx, settingsTop + 10, ReplayPalette.BRASS_LIGHT);
        if (settingsLayout.firstY() >= 46) {
            int textW = panelWidth - 24;
            g.drawCenteredString(font, font.plainSubstrByWidth(settingsSubtitle().getString(), textW),
                    cx, settingsTop + 24, ReplayPalette.CHAMPAGNE);
        }

        int closeX0 = settingsCloseX(), closeY0 = settingsCloseY();
        boolean closeHover = mouseX >= closeX0 && mouseX < closeX0 + 18 && mouseY >= closeY0 && mouseY < closeY0 + 16;
        g.fill(closeX0, closeY0, closeX0 + 18, closeY0 + 16, closeHover ? 0xFF4E1E24 : 0xFF18233F);
        g.renderOutline(closeX0, closeY0, 18, 16, closeHover ? ReplayPalette.RUBY : ReplayPalette.BRASS_DIM);
        g.drawCenteredString(font, "×", closeX0 + 9, closeY0 + 4, closeHover ? 0xFFFFFFFF : ReplayPalette.TEXT);

        int lastRowBottom = settingsSectionY(SETTINGS_SECTION_COUNT - 1) + settingsLayout.optionHeight();
        int collapseTop = settingsTop + settingsLayout.buttonsY();
        if (resolution.highLoad() && lastRowBottom + 14 <= collapseTop) {
            int textW = panelWidth - 24;
            g.drawCenteredString(font, font.plainSubstrByWidth(tr("export.warn_high").getString(), textW),
                    cx, (lastRowBottom + collapseTop - 8) / 2, ReplayPalette.LAMP);
        }
    }

    // ------------------------------------------------------------------ player picker view

    private int pickerCloseX() { return left + panelWidth - 26; }
    private int pickerCloseY() { return pickerTop + 7; }
    private int pickerListLeft() { return left + 12; }
    private int pickerListRight() { return left + panelWidth - 16; }
    private int pickerListTop() { return pickerTop + 30; }
    private int pickerListBottom() { return Math.max(pickerListTop() + 1, pickerTop + pickerPanelHeight - 12); }
    private int pickerTotalRows() { return 1 + participants.size(); }

    private int maxPickerScroll() {
        int contentHeight = pickerTotalRows() * PICKER_ROW_HEIGHT;
        int viewHeight = Math.max(1, pickerListBottom() - pickerListTop());
        return Math.max(0, contentHeight - viewHeight);
    }

    private boolean handlePickerClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            setPickerOpen(false);
            return true;
        }
        if (mouseX < left || mouseX >= left + panelWidth || mouseY < pickerTop || mouseY >= pickerTop + pickerPanelHeight) {
            setPickerOpen(false);
            return true;
        }
        // Close [x] button in top-right of the picker header.
        int closeX0 = pickerCloseX(), closeY0 = pickerCloseY();
        if (mouseX >= closeX0 && mouseX < closeX0 + 18 && mouseY >= closeY0 && mouseY < closeY0 + 16) {
            setPickerOpen(false);
            if (minecraft != null && perspectivePlayer != null) perspectivePlayer.playDownSound(minecraft.getSoundManager());
            return true;
        }
        int listLeft = pickerListLeft(), listRight = pickerListRight();
        int listTop = pickerListTop(), listBottom = pickerListBottom();
        if (mouseX >= listLeft && mouseX < listRight && mouseY >= listTop && mouseY < listBottom) {
            int row = (int) ((mouseY - listTop + pickerScroll) / PICKER_ROW_HEIGHT);
            if (row >= 0 && row < pickerTotalRows()) {
                focusPlayer = row == 0 ? null : participants.get(row - 1).id();
                setPickerOpen(false);
                if (minecraft != null && perspectivePlayer != null) perspectivePlayer.playDownSound(minecraft.getSoundManager());
                refresh();
                return true;
            }
        }
        return true;
    }

    private void renderPicker(GuiGraphics g, int mouseX, int mouseY) {
        String pickerTitle = font.plainSubstrByWidth(
                tr("export.perspective_picker_title").getString(), Math.max(40, panelWidth - 64));
        int cx = left + panelWidth / 2;
        int titleW = font.width(pickerTitle);
        int ruleY = pickerTop + 14;
        int closeX0 = pickerCloseX(), closeY0 = pickerCloseY();
        int lx0 = left + 14, lx1 = cx - titleW / 2 - 14;
        int rx0 = cx + titleW / 2 + 14, rx1 = closeX0 - 8;
        if (lx1 > lx0 + 4) {
            g.fill(lx0, ruleY, lx1, ruleY + 1, ReplayPalette.BRASS_DIM);
            diamond(g, lx1 + 5, ruleY, ReplayPalette.BRASS_LIGHT);
        }
        if (rx1 > rx0 + 4) {
            g.fill(rx0, ruleY, rx1, ruleY + 1, ReplayPalette.BRASS_DIM);
            diamond(g, rx0 - 5, ruleY, ReplayPalette.BRASS_LIGHT);
        }
        g.drawCenteredString(font, pickerTitle, cx, pickerTop + 10, ReplayPalette.BRASS_LIGHT);

        boolean closeHover = mouseX >= closeX0 && mouseX < closeX0 + 18 && mouseY >= closeY0 && mouseY < closeY0 + 16;
        g.fill(closeX0, closeY0, closeX0 + 18, closeY0 + 16, closeHover ? 0xFF4E1E24 : 0xFF18233F);
        g.renderOutline(closeX0, closeY0, 18, 16, closeHover ? ReplayPalette.RUBY : ReplayPalette.BRASS_DIM);
        g.drawCenteredString(font, "×", closeX0 + 9, closeY0 + 4, closeHover ? 0xFFFFFFFF : ReplayPalette.TEXT);

        int listLeft = pickerListLeft(), listRight = pickerListRight();
        int listTop = pickerListTop(), listBottom = pickerListBottom();
        int maxScroll = maxPickerScroll();
        pickerScroll = Math.clamp(pickerScroll, 0, maxScroll);
        g.fill(listLeft, listTop, listRight, listBottom, 0xFF070C1A);
        g.flush();
        g.enableScissor(listLeft, listTop, listRight, listBottom);
        int totalRows = pickerTotalRows();
        // Pass 1: row backgrounds and accent bars (committed before immediate head blits & text).
        for (int i = 0; i < totalRows; i++) {
            int rowTop = listTop + i * PICKER_ROW_HEIGHT - pickerScroll;
            int rowBottom = rowTop + PICKER_ROW_HEIGHT;
            if (rowBottom <= listTop || rowTop >= listBottom) continue;
            boolean hovered = mouseX >= listLeft && mouseX < listRight && mouseY >= Math.max(listTop, rowTop)
                    && mouseY < Math.min(listBottom, rowBottom);
            boolean selected = i == 0 ? focusPlayer == null : participants.get(i - 1).id().equals(focusPlayer);
            int bgTop, bgBot, accent;
            if (selected) {
                bgTop = i == 0 ? 0xFF3A2D14 : 0xFF4A2029;
                bgBot = i == 0 ? 0xFF21190B : 0xFF2A1118;
                accent = i == 0 ? ReplayPalette.BRASS_LIGHT : ReplayPalette.FOCUS_TRAIL;
            } else if (hovered) {
                bgTop = 0xFF233256;
                bgBot = 0xFF151F3B;
                accent = ReplayPalette.BRASS;
            } else {
                bgTop = i % 2 == 0 ? 0xFF10182D : 0xFF0C1324;
                bgBot = bgTop;
                accent = 0;
            }
            g.fillGradient(listLeft + 1, rowTop + 1, listRight - 1, rowBottom - 1, bgTop, bgBot);
            if (accent != 0) {
                g.fill(listLeft + 1, rowTop + 1, listLeft + 4, rowBottom - 1, accent);
            } else if (i > 0) {
                int roleColor = ReplayPalette.opaque(participants.get(i - 1).endColor(), ReplayPalette.PASSENGER);
                g.fill(listLeft + 1, rowTop + 3, listLeft + 3, rowBottom - 3, roleColor);
            }
        }
        g.flush();
        // Pass 2: player faces and row text on top of the flushed backgrounds.
        for (int i = 0; i < totalRows; i++) {
            int rowTop = listTop + i * PICKER_ROW_HEIGHT - pickerScroll;
            int rowBottom = rowTop + PICKER_ROW_HEIGHT;
            if (rowBottom <= listTop || rowTop >= listBottom) continue;
            boolean selected = i == 0 ? focusPlayer == null : participants.get(i - 1).id().equals(focusPlayer);
            if (i == 0) {
                Component globalLabel = tr("export.perspective_global");
                g.drawString(font, globalLabel, listLeft + 10, rowTop + 7,
                        selected ? ReplayPalette.CHAMPAGNE : ReplayPalette.TEXT, false);
                int descX = listLeft + 10 + font.width(globalLabel) + 10;
                int descAvail = listRight - descX - 6;
                if (descAvail > 16) {
                    g.drawString(font, font.plainSubstrByWidth(tr("export.perspective_global_desc").getString(), descAvail),
                            descX, rowTop + 7, ReplayPalette.TEXT_MUTED, false);
                }
            } else {
                LastMatchReplay.Participant p = participants.get(i - 1);
                int roleColor = ReplayPalette.opaque(p.endColor(), ReplayPalette.PASSENGER);
                PlayerFaceRenderer.draw(g, ReplayHeadSkins.texture(p.id()), listLeft + 8, rowTop + 4, 14);
                Component kills = p.kills() > 0 ? tr("export.perspective_kills", p.kills()) : Component.empty();
                int killsW = kills.getString().isEmpty() ? 0 : font.width(kills) + 8;
                int nameX = listLeft + 26;
                int avail = Math.max(40, listRight - nameX - killsW - 8);
                String nameStr = ReplayBoardTexts.name(p).getString();
                String roleStr = ReplayBoardTexts.role(p.endRole()).getString();
                String clippedName = font.plainSubstrByWidth(nameStr, Math.max(24, avail * 58 / 100));
                int nameW = font.width(clippedName);
                g.drawString(font, clippedName,
                        nameX, rowTop + 7, selected ? ReplayPalette.FOCUS_TRAIL_HEAD : ReplayPalette.TEXT, false);
                int roleX = nameX + nameW + 6;
                int roleAvail = listRight - killsW - roleX - 6;
                if (roleAvail > 16) {
                    g.drawString(font, font.plainSubstrByWidth(roleStr, roleAvail), roleX, rowTop + 7, roleColor, false);
                }
                if (killsW > 0) {
                    g.drawString(font, kills, listRight - killsW, rowTop + 7, ReplayPalette.RUBY, false);
                }
            }
        }
        g.flush();
        g.disableScissor();
        g.renderOutline(listLeft, listTop, listRight - listLeft, listBottom - listTop, 0x808A6A35);

        if (maxScroll > 0) {
            int trackX = listRight + 2;
            int viewH = listBottom - listTop;
            int thumbH = Math.max(16, viewH * viewH / (totalRows * PICKER_ROW_HEIGHT));
            int thumbY = listTop + (int) ((viewH - thumbH) * (pickerScroll / (double) maxScroll));
            g.fill(trackX, listTop, trackX + 3, listBottom, 0xFF18223A);
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ReplayPalette.BRASS);
        }
    }

    // ------------------------------------------------------------------ widget

    /** Flat night-blue / brass button matching the table's plaques. */
    private static final class BrassButton extends Button {
        boolean selected;
        boolean accent;
        boolean focusTint;

        BrassButton(int x, int y, int w, int h, Component message, OnPress onPress) {
            super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
        }

        @Override public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            boolean hover = active && isHoveredOrFocused();
            int x0 = getX(), y0 = getY(), x1 = x0 + getWidth(), y1 = y0 + getHeight();
            int top, bottom, border, text;
            if (!active && !selected) {
                top = 0xFF0E1426; bottom = 0xFF080C18; border = 0xFF3B3322; text = ReplayPalette.TEXT_FAINT;
            } else if (accent) {
                top = hover ? 0xFF6A2A30 : 0xFF4E1E24; bottom = 0xFF2A1015; border = ReplayPalette.RUBY; text = 0xFFFFE0E0;
            } else if (selected && focusTint) {
                top = hover ? 0xFF5D2632 : 0xFF4B1E28; bottom = 0xFF271016;
                border = active ? ReplayPalette.FOCUS_TRAIL : ReplayPalette.BRASS_DIM;
                text = active ? ReplayPalette.FOCUS_TRAIL_HEAD : ReplayPalette.BRASS;
            } else if (selected) {
                top = hover ? 0xFF4A3A1A : 0xFF3A2D14; bottom = 0xFF1E170A;
                border = active ? ReplayPalette.BRASS_LIGHT : ReplayPalette.BRASS_DIM;
                text = active ? ReplayPalette.CHAMPAGNE : ReplayPalette.BRASS;
            } else if (hover) {
                top = 0xFF26365C; bottom = 0xFF131D38; border = ReplayPalette.BRASS; text = 0xFFFFFFFF;
            } else {
                top = 0xFF18233F; bottom = 0xFF0C1326; border = ReplayPalette.BRASS_DIM; text = ReplayPalette.TEXT;
            }
            g.fillGradient(x0, y0, x1, y1, top, bottom);
            g.renderOutline(x0, y0, getWidth(), getHeight(), border);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 6);
            g.drawCenteredString(font, label, x0 + getWidth() / 2, y0 + (getHeight() - 8) / 2, text);
        }
    }
}
