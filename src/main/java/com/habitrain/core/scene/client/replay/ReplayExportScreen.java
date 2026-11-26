package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayCinemaDirector;
import com.habitrain.core.scene.replay.ReplayExportContentOptions;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.ActorOverlayFilter;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.ExportMode;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.IdentityReveal;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PersonalPacing;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PersonalView;
import com.habitrain.core.scene.replay.ReplayExportContentOptions.PromoScope;
import com.habitrain.core.scene.replay.ReplayMusicPlan;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Export settings opened from a replay table or replay history. */
final class ReplayExportScreen extends Screen {
    static final int PANEL_HEIGHT_MAIN = 206;
    static final int PANEL_HEIGHT_SETTINGS = 268;
    static final int PANEL_HEIGHT_CUSTOMIZER = 312;
    static final int PANEL_HEIGHT_PICKER = 334;
    static final int SETTINGS_SECTION_COUNT = 6;
    private static final int GAP = 4;
    private static final int PICKER_ROW_HEIGHT = 22;

    private static final int SLIDER_NONE = 0;
    private static final int SLIDER_VOICE = 1;
    private static final int SLIDER_SFX = 2;
    private static final int SLIDER_TRIM_START = 3;
    private static final int SLIDER_TRIM_END = 4;

    private final ClientLevel level;
    private final BlockPos pos;
    private final Screen parent;
    private final UUID history;
    private ReplayExportResolution resolution;
    private boolean compress;
    private boolean preferSimpleExport;
    private ReplayExportQuality quality;
    private ReplayMusicPlan.Level music;
    private boolean preferredRoofCutaway;
    private @Nullable UUID focusPlayer;
    private ReplayExportContentOptions contentOptions;

    private final Button[] modeButtons = new Button[ExportMode.values().length];
    private final Button[] qualityButtons = new Button[ReplayExportQuality.values().length];
    private final Button[] resolutionButtons = new Button[ReplayExportResolution.values().length];
    private final Button[] musicButtons = new Button[ReplayMusicPlan.Level.values().length];
    private final Button[] customizerTabButtons = new Button[3];
    private final Button[] customizerMusicButtons = new Button[ReplayMusicPlan.Level.values().length];

    private final List<Button> mainWidgets = new ArrayList<>();
    private final List<Button> settingsWidgets = new ArrayList<>();
    private final List<Button> customizerCommonWidgets = new ArrayList<>();
    private final List<Button> customizerTab0Widgets = new ArrayList<>();
    private final List<Button> customizerTab1Widgets = new ArrayList<>();
    private final List<Button> customizerTab2Widgets = new ArrayList<>();

    // Main widgets
    private Button perspectiveGlobal, perspectivePlayer;
    private Button subPrimaryButton;
    private final Button[] pacingButtons = new Button[PersonalPacing.values().length];
    private Button subSecondaryButton;
    private Button customizerButton, videoSettingsButton, styleButton, back, start, folderOrImport;
    private ReplayExportContentOptions.VisualStyle visualStyle = ReplayExportContentOptions.VisualStyle.BRASS;

    // Video & Quality settings widgets
    private Button mp4, avi, roofCut, roofFull, collapseSettingsButton;
    private Button nativeExport, simpleExport;

    // Content Customizer widgets (Tab 0: Structure)
    private Button optIntro, optOverview, optVictoryMvp, optOutro, optLetterbox, optWatermark, optKillSplit, optResetDefaults;
    // Content Customizer widgets (Tab 1: HUD & Fog)
    private Button optTimecode, optAliveMatrix, optKillFeed, optTimeline, optSkillToasts, optIdentity, optTrails, optNameplates;
    // Content Customizer widgets (Tab 2: Audio & Trim)
    private Button optAutoDucking, optTrimFull;
    private Button collapseCustomizerButton;

    private int left, panelWidth;
    private int mainTop, mainPanelHeight;
    private int settingsTop, settingsPanelHeight;
    private int customizerTop, customizerPanelHeight;
    private int pickerTop, pickerPanelHeight;
    private int mainModeY, mainPerspectiveY, mainSubY, mainDrawersY, mainProgressY;
    private int playerButtonX, playerButtonWidth;
    private ExportLayout settingsLayout;

    // Slider geometry in Customizer Tab 2
    private int sliderTrackX, sliderTrackW;
    private int voiceSliderY, sfxSliderY, trimSliderY;
    private int activeSlider = SLIDER_NONE;

    private ClientReplayBoard.View view;
    private ReplayVideoExport video;
    private boolean folderFailed;
    private boolean historyPinned;
    private @Nullable LastMatchReplay secondsFor;
    private @Nullable ReplayExportContentOptions secondsOptions;
    private double filmSeconds;
    private @Nullable LastMatchReplay participantsFor;
    private List<LastMatchReplay.Participant> participants = List.of();
    private boolean settingsOpen;
    private boolean customizerOpen;
    private int customizerTab;
    private boolean pickerOpen;
    private int pickerScroll;
    private int pickerCursor;

    ReplayExportScreen(ClientLevel level, BlockPos pos) {
        this(level, pos, null);
    }

    ReplayExportScreen(ClientLevel level, BlockPos pos, @Nullable UUID initialFocusPlayer) {
        super(tr("export.title"));
        this.level = level;
        this.parent = null;
        this.pos = pos.immutable();
        this.history = ClientReplayBoard.boardHistoryRound(this.pos);
        initPrefs(initialFocusPlayer);
    }

    ReplayExportScreen(Screen parent, UUID history) {
        this(parent, history, null);
    }

    ReplayExportScreen(Screen parent, UUID history, @Nullable UUID initialFocusPlayer) {
        super(tr("export.title"));
        this.level = null;
        this.pos = null;
        this.parent = parent;
        this.history = history;
        initPrefs(initialFocusPlayer);
    }

    private void initPrefs(@Nullable UUID initialFocusPlayer) {
        this.resolution = ReplayExportResolution.fromId(ClientVisualPreferences.getReplayVideoResolution());
        this.compress = ClientVisualPreferences.isReplayVideoCompress() || !this.resolution.aviSupported();
        this.quality = ReplayExportQuality.fromId(ClientVisualPreferences.getReplayVideoQuality());
        this.music = ReplayMusicPlan.Level.fromId(ClientVisualPreferences.getReplayVideoMusic());
        this.preferredRoofCutaway = !ReplayBoardLocalState.fullBody();
        this.visualStyle = ReplayExportContentOptions.VisualStyle.fromId(ClientVisualPreferences.getReplayVideoStyle());
        this.focusPlayer = initialFocusPlayer;
        ExportMode initialMode = initialFocusPlayer != null ? ExportMode.PERSONAL : ExportMode.SHORT;
        this.contentOptions = buildDefaultOptionsForMode(initialMode, initialFocusPlayer);
    }

    private ReplayExportContentOptions buildDefaultOptionsForMode(ExportMode mode, @Nullable UUID player) {
        var base = ReplayExportContentOptions.defaultsFor(mode, player).toBuilder().visualStyle(this.visualStyle).build();
        if (mode == ExportMode.SHORT) {
            return base.toBuilder()
                    .roofCutaway(this.preferredRoofCutaway)
                    .musicLevel(this.music)
                    .build();
        }
        return base;
    }

    @Override public void onClose() {
        if (parent == null) super.onClose();
        else minecraft.setScreen(parent);
    }

    @Override public void removed() {
        if (video != null && video.failed()) {
            ReplayExportClient.acknowledgeFailure(video);
        }
        ReplayExportClient.clearStartError();
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

    private <T extends Button> T addCustomizerCommon(T widget) {
        customizerCommonWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private <T extends Button> T addCustomizerTabWidget(int tab, T widget) {
        switch (tab) {
            case 0 -> customizerTab0Widgets.add(widget);
            case 1 -> customizerTab1Widgets.add(widget);
            default -> customizerTab2Widgets.add(widget);
        }
        return addRenderableWidget(widget);
    }

    private int activeTop() {
        return pickerOpen ? pickerTop : customizerOpen ? customizerTop : settingsOpen ? settingsTop : mainTop;
    }

    private int activePanelHeight() {
        return pickerOpen ? pickerPanelHeight : customizerOpen ? customizerPanelHeight : settingsOpen ? settingsPanelHeight : mainPanelHeight;
    }

    private void setSettingsOpen(boolean open) {
        boolean next = open && !exporting();
        if (settingsOpen == next && (!next || (!pickerOpen && !customizerOpen))) {
            syncWidgetsVisible();
            return;
        }
        settingsOpen = next;
        if (settingsOpen) {
            pickerOpen = false;
            customizerOpen = false;
            activeSlider = SLIDER_NONE;
        }
        setFocused(null);
        syncWidgetsVisible();
    }

    private void setCustomizerOpen(boolean open) {
        boolean next = open && !exporting();
        if (customizerOpen == next && (!next || (!pickerOpen && !settingsOpen))) {
            syncWidgetsVisible();
            return;
        }
        customizerOpen = next;
        if (customizerOpen) {
            pickerOpen = false;
            settingsOpen = false;
        } else {
            activeSlider = SLIDER_NONE;
        }
        setFocused(null);
        syncWidgetsVisible();
    }

    private void setPickerOpen(boolean open) {
        boolean next = open && !participants.isEmpty() && !exporting();
        if (pickerOpen == next && (!next || (!settingsOpen && !customizerOpen))) {
            syncWidgetsVisible();
            return;
        }
        pickerOpen = next;
        if (pickerOpen) {
            settingsOpen = false;
            customizerOpen = false;
            activeSlider = SLIDER_NONE;
            pickerScroll = 0;
            movePickerCursor(Math.max(0, participantIndex(focusPlayer)));
        }
        syncWidgetsVisible();
        setFocused(pickerOpen || exporting() ? null : perspectivePlayer);
    }

    private void syncWidgetsVisible() {
        boolean showMain = !pickerOpen && !settingsOpen && !customizerOpen;
        boolean showSettings = settingsOpen && !pickerOpen && !customizerOpen;
        boolean showCustomizer = customizerOpen && !pickerOpen && !settingsOpen;
        for (Button widget : mainWidgets) {
            widget.visible = showMain;
        }
        if (showMain) {
            ExportMode mode = shownOptions().mode();
            boolean personal = mode == ExportMode.PERSONAL;
            boolean autoEdit = mode == ExportMode.CINEMATIC_PROMO;
            perspectiveGlobal.visible = !personal;
            subPrimaryButton.visible = !autoEdit;
            subSecondaryButton.visible = !personal && !autoEdit;
            for (Button b : pacingButtons) b.visible = personal;
        }
        for (Button widget : settingsWidgets) {
            widget.visible = showSettings;
        }
        for (Button widget : customizerCommonWidgets) {
            widget.visible = showCustomizer;
        }
        for (Button widget : customizerTab0Widgets) {
            widget.visible = showCustomizer && customizerTab == 0;
        }
        for (Button widget : customizerTab1Widgets) {
            widget.visible = showCustomizer && customizerTab == 1;
        }
        for (Button widget : customizerTab2Widgets) {
            widget.visible = showCustomizer && customizerTab == 2;
        }
    }

    private void updateOptions(ReplayExportContentOptions updated) {
        ReplayExportClient.clearStartError();
        this.contentOptions = updated;
        this.focusPlayer = updated.focusPlayer();
        this.music = updated.musicLevel();
        refresh();
    }

    private void selectMode(ExportMode mode) {
        if (exporting()) return;
        ReplayExportClient.clearStartError();
        UUID subject = focusPlayer;
        if (mode == ExportMode.PERSONAL && subject == null && !participants.isEmpty()) {
            subject = participants.getFirst().id();
        }
        this.focusPlayer = subject;
        this.contentOptions = buildDefaultOptionsForMode(mode, subject);
        this.music = this.contentOptions.musicLevel();
        refresh();
    }

    @Override protected void init() {
        mainWidgets.clear();
        settingsWidgets.clear();
        customizerCommonWidgets.clear();
        customizerTab0Widgets.clear();
        customizerTab1Widgets.clear();
        customizerTab2Widgets.clear();
        if (!historyPinned) {
            ReplayHistoryClient.pin(history);
            historyPinned = true;
        }
        panelWidth = Math.min(396, width - 12);
        left = (width - panelWidth) / 2;

        mainPanelHeight = Math.min(PANEL_HEIGHT_MAIN, height - 6);
        mainTop = (height - mainPanelHeight) / 2;

        settingsPanelHeight = Math.min(PANEL_HEIGHT_SETTINGS, height - 6);
        settingsTop = (height - settingsPanelHeight) / 2;
        settingsLayout = ExportLayout.forSettings(settingsPanelHeight);

        customizerPanelHeight = Math.min(PANEL_HEIGHT_CUSTOMIZER, height - 6);
        customizerTop = (height - customizerPanelHeight) / 2;

        pickerPanelHeight = Math.min(PANEL_HEIGHT_PICKER, height - 6);
        pickerTop = (height - pickerPanelHeight) / 2;

        MainLayout mainLayout = MainLayout.compute(mainPanelHeight);
        int optH = mainLayout.optionHeight();
        mainModeY = mainTop + mainLayout.modeY();
        mainPerspectiveY = mainTop + mainLayout.perspectiveY();
        mainSubY = mainTop + mainLayout.subY();
        mainDrawersY = mainTop + mainLayout.drawersY();
        mainProgressY = mainTop + mainLayout.progressY();
        int mainButtonsY = mainTop + mainLayout.buttonsY();

        int inner = panelWidth - 24, x = left + 12;
        int half = (inner - GAP) / 2;
        int otherHalf = inner - half - GAP;

        ExportMode[] modes = ExportMode.values();
        int modeW = (inner - GAP * (modes.length - 1)) / modes.length;
        for (int i = 0; i < modes.length; i++) {
            ExportMode mode = modes[i];
            int bx = x + i * (modeW + GAP);
            int bw = i == modes.length - 1 ? x + inner - bx : modeW;
            modeButtons[i] = addMainWidget(new BrassButton(bx, mainModeY, bw, optH,
                    tr("export.mode_" + mode.id), b -> selectMode(mode)));
            modeButtons[i].setTooltip(Tooltip.create(tr("export.mode_" + mode.id + "_tip")));
        }

        int globalW = inner / 3;
        playerButtonX = x + globalW + GAP;
        playerButtonWidth = inner - globalW - GAP;
        perspectiveGlobal = addMainWidget(new BrassButton(x, mainPerspectiveY, globalW, optH,
                tr("export.perspective_global"), b -> {
                    setFocusPlayer(null);
                    refresh();
                }));
        perspectivePlayer = addMainWidget(new BrassButton(playerButtonX, mainPerspectiveY, playerButtonWidth, optH,
                tr("export.perspective_pick"), b -> setPickerOpen(true)));
        perspectivePlayer.setTooltip(Tooltip.create(tr("export.perspective_player_tip")));

        int subPrimaryW = Math.min(138, inner * 40 / 100);
        subPrimaryButton = addMainWidget(new BrassButton(x, mainSubY, subPrimaryW, optH, Component.empty(), b -> onSubPrimaryClick()));
        int subRightX = x + subPrimaryW + GAP;
        int subRightW = inner - subPrimaryW - GAP;
        subSecondaryButton = addMainWidget(new BrassButton(subRightX, mainSubY, subRightW, optH, Component.empty(), b -> onSubSecondaryClick()));

        PersonalPacing[] pacings = PersonalPacing.values();
        int paceW = (subRightW - GAP * (pacings.length - 1)) / pacings.length;
        for (int i = 0; i < pacings.length; i++) {
            PersonalPacing pacing = pacings[i];
            int bx = subRightX + i * (paceW + GAP);
            int bw = i == pacings.length - 1 ? subRightX + subRightW - bx : paceW;
            pacingButtons[i] = addMainWidget(new BrassButton(bx, mainSubY, bw, optH,
                    tr("export.pacing_" + pacing.id), b -> updateOptions(
                            contentOptions.toBuilder().personalPacing(pacing).build())));
            if (pacing == PersonalPacing.HIGHLIGHTS_ONLY) {
                pacingButtons[i].setTooltip(Tooltip.create(tr("export.pacing_highlights_tip")));
            }
        }

        int drawerW = (inner - GAP * 2) / 3;
        customizerButton = addMainWidget(new BrassButton(x, mainDrawersY, drawerW, optH, tr("export.customizer_btn"), b -> {
            setCustomizerOpen(true);
            refresh();
        }));
        customizerButton.setTooltip(Tooltip.create(tr("export.customizer_btn_tip")));

        videoSettingsButton = addMainWidget(new BrassButton(x + drawerW + GAP, mainDrawersY, drawerW, optH, tr("export.video_settings"), b -> {
            setSettingsOpen(true);
            refresh();
        }));
        videoSettingsButton.setTooltip(Tooltip.create(tr("export.video_settings_tip")));

        int styleX = x + (drawerW + GAP) * 2;
        styleButton = addMainWidget(new BrassButton(styleX, mainDrawersY, x + inner - styleX, optH, Component.empty(), b -> {
            if (exporting()) return;
            visualStyle = visualStyle.toggle();
            ClientVisualPreferences.setReplayVideoStyle(visualStyle.id);
            updateOptions(contentOptions.toBuilder().visualStyle(visualStyle).build());
        }));

        int third = (inner - GAP * 2) / 3;
        back = addMainWidget(new BrassButton(x, mainButtonsY, third, 20, tr("export.back"), b -> onClose()));
        folderOrImport = addMainWidget(new BrassButton(x + third + GAP, mainButtonsY, third, 20, tr("export.open_folder"), b -> {
            if (needsFfmpegAction()) {
                ReplayFfmpegManager.startManualImport(minecraft.gameDirectory.toPath());
            } else {
                folderFailed = !ReplayVideoExport.openFolder(minecraft);
            }
            refresh();
        }));
        BrassButton startButton = addMainWidget(new BrassButton(x + (third + GAP) * 2, mainButtonsY,
                inner - (third + GAP) * 2, 20, tr("export.start"), b -> toggleExport()));
        startButton.primary = true;
        start = startButton;

        // ---- Folded Content Customizer Sub-View (3 Star-Night Tabs) ----
        initCustomizerWidgets(x, inner, half, otherHalf);

        // ---- Folded Video & Quality Settings Sub-View (5 sections, test-verified layout) ----
        initSettingsWidgets(x, inner, half, otherHalf);

        refresh();
    }

    private void initCustomizerWidgets(int x, int inner, int half, int otherHalf) {
        int tabY = customizerTop + 26;
        int tabH = 18;
        int tabW = (inner - GAP * 2) / 3;
        String[] tabKeys = {"export.customizer_tab_pack", "export.customizer_tab_hud", "export.customizer_tab_audio"};
        for (int i = 0; i < 3; i++) {
            int tabIdx = i;
            int bx = x + i * (tabW + GAP);
            int bw = i == 2 ? x + inner - bx : tabW;
            customizerTabButtons[i] = addCustomizerCommon(new BrassButton(bx, tabY, bw, tabH, tr(tabKeys[i]), b -> {
                customizerTab = tabIdx;
                activeSlider = SLIDER_NONE;
                syncWidgetsVisible();
                refresh();
            }));
        }

        int bodyTop = tabY + tabH + 10;
        int closeBtnY = customizerTop + customizerPanelHeight - 26;
        int bodyH = Math.max(120, closeBtnY - bodyTop - 6);
        int rowStep = Math.min(28, bodyH / 5);
        int btnH = Math.clamp(rowStep - 6, 16, 20);

        // Tab 0: Structure & Cinema Packaging (6 toggles + Kill Split + Reset)
        optIntro = addCustomizerTabWidget(0, new BrassButton(x, bodyTop, half, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().includeTitle(!contentOptions.includeTitle()).build())));
        optOverview = addCustomizerTabWidget(0, new BrassButton(x + half + GAP, bodyTop, otherHalf, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().includeReview(!contentOptions.includeReview()).build())));
        optVictoryMvp = addCustomizerTabWidget(0, new BrassButton(x, bodyTop + rowStep, half, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().includeVictoryMvp(!contentOptions.includeVictoryMvp()).build())));
        optOutro = addCustomizerTabWidget(0, new BrassButton(x + half + GAP, bodyTop + rowStep, otherHalf, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().includeOutro(!contentOptions.includeOutro()).build())));
        optLetterbox = addCustomizerTabWidget(0, new BrassButton(x, bodyTop + rowStep * 2, half, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().includeLetterboxDof(!contentOptions.includeLetterboxDof()).build())));
        optWatermark = addCustomizerTabWidget(0, new BrassButton(x + half + GAP, bodyTop + rowStep * 2, otherHalf, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().includeWatermark(!contentOptions.includeWatermark()).build())));
        optKillSplit = addCustomizerTabWidget(0, new BrassButton(x, bodyTop + rowStep * 3, half, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().splitScreenKillPov(!contentOptions.splitScreenKillPov()).build())));
        optResetDefaults = addCustomizerTabWidget(0, new BrassButton(x + half + GAP, bodyTop + rowStep * 3, otherHalf, btnH,
                tr("export.customizer_reset"), b -> {
                    contentOptions = buildDefaultOptionsForMode(contentOptions.mode(), focusPlayer);
                    music = contentOptions.musicLevel();
                    refresh();
                }));

        // Tab 1: Tactical HUD & Anti-Spoiler Fog (8 controls)
        optTimecode = addCustomizerTabWidget(1, new BrassButton(x, bodyTop, half, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().showTimecode(!contentOptions.showTimecode()).build())));
        optAliveMatrix = addCustomizerTabWidget(1, new BrassButton(x + half + GAP, bodyTop, otherHalf, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().showAliveMatrix(!contentOptions.showAliveMatrix()).build())));
        optKillFeed = addCustomizerTabWidget(1, new BrassButton(x, bodyTop + rowStep, half, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().showKillFeed(!contentOptions.showKillFeed()).build())));
        optTimeline = addCustomizerTabWidget(1, new BrassButton(x + half + GAP, bodyTop + rowStep, otherHalf, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().showTimeline(!contentOptions.showTimeline()).build())));
        optSkillToasts = addCustomizerTabWidget(1, new BrassButton(x, bodyTop + rowStep * 2, half, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().showSkillToasts(!contentOptions.showSkillToasts()).build())));
        optIdentity = addCustomizerTabWidget(1, new BrassButton(x + half + GAP, bodyTop + rowStep * 2, otherHalf, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().identityReveal(nextIdentity(contentOptions.identityReveal())).build())));
        optTrails = addCustomizerTabWidget(1, new BrassButton(x, bodyTop + rowStep * 3, half, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().trailFilter(nextFilter(contentOptions.trailFilter())).build())));
        optNameplates = addCustomizerTabWidget(1, new BrassButton(x + half + GAP, bodyTop + rowStep * 3, otherHalf, btnH, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().nameplateFilter(nextFilter(contentOptions.nameplateFilter())).build())));

        // Tab 2: Multi-Track Audio Sliders + Dynamic Music + Auto-Ducking + Dual-Handle Clip Trimmer
        sliderTrackX = x + 4;
        sliderTrackW = inner - 8;
        voiceSliderY = bodyTop + 14;
        sfxSliderY = voiceSliderY + 34;

        int musicRowY = sfxSliderY + 26;
        ReplayMusicPlan.Level[] levels = ReplayMusicPlan.Level.values();
        int duckW = Math.min(118, inner * 36 / 100);
        int musicSpanW = inner - duckW - GAP;
        int mw = (musicSpanW - GAP * (levels.length - 1)) / levels.length;
        for (int i = 0; i < levels.length; i++) {
            ReplayMusicPlan.Level lvl = levels[i];
            int bx = x + i * (mw + GAP);
            int bw = i == levels.length - 1 ? x + musicSpanW - bx : mw;
            customizerMusicButtons[i] = addCustomizerTabWidget(2, new BrassButton(bx, musicRowY, bw, 18,
                    tr("export.music_" + lvl.id), b -> {
                        music = lvl;
                        ClientVisualPreferences.setReplayVideoMusic(lvl.id);
                        updateOptions(contentOptions.toBuilder().musicLevel(lvl).build());
                    }));
        }
        optAutoDucking = addCustomizerTabWidget(2, new BrassButton(x + musicSpanW + GAP, musicRowY, duckW, 18, Component.empty(),
                b -> updateOptions(contentOptions.toBuilder().autoDucking(!contentOptions.autoDucking()).build())));

        trimSliderY = Math.min(closeBtnY - 34, musicRowY + 42);
        optTrimFull = addCustomizerTabWidget(2, new BrassButton(x + inner - 48, trimSliderY - 17, 48, 14,
                tr("export.trim_full"), b -> updateOptions(contentOptions.toBuilder().trimRange(0, -1).build())));

        collapseCustomizerButton = addCustomizerCommon(new BrassButton(x, closeBtnY, inner, 20,
                tr("export.customizer_collapse"), b -> {
                    setCustomizerOpen(false);
                    refresh();
                }));
    }

    private void initSettingsWidgets(int x, int inner, int half, int otherHalf) {
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
            preferredRoofCutaway = true;
            updateOptions(contentOptions.toBuilder().roofCutaway(true).build());
        }));
        roofFull = addSettingsWidget(new BrassButton(x + half + GAP, settingsSectionY(3), otherHalf, setOptH, tr("export.roof_full"), b -> {
            preferredRoofCutaway = false;
            updateOptions(contentOptions.toBuilder().roofCutaway(false).build());
        }));

        nativeExport = addSettingsWidget(new BrassButton(x, settingsSectionY(5), half, setOptH, tr("export.backend_native"), b -> {
            preferSimpleExport = false;
            refresh();
        }));
        nativeExport.setTooltip(Tooltip.create(tr("export.backend_native_tip")));
        simpleExport = addSettingsWidget(new BrassButton(x + half + GAP, settingsSectionY(5), otherHalf, setOptH,
                tr("export.backend_simple"), b -> {
                    preferSimpleExport = true;
                    refresh();
                }));
        simpleExport.setTooltip(Tooltip.create(tr("export.backend_simple_tip")));

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
                        updateOptions(contentOptions.toBuilder().musicLevel(value).build());
                    }));
            musicButtons[i].setTooltip(Tooltip.create(tr(value.enabled() ? "export.music_tooltip" : "export.music_off_tooltip")));
        }

        collapseSettingsButton = addSettingsWidget(new BrassButton(
                x, settingsTop + settingsLayout.buttonsY(), inner, 20,
                tr("export.settings_collapse"), b -> {
                    setSettingsOpen(false);
                    refresh();
                }));
    }

    private void onSubPrimaryClick() {
        if (exporting()) return;
        switch (contentOptions.mode()) {
            case SHORT -> updateOptions(contentOptions.toBuilder()
                    .splitScreenKillPov(!contentOptions.splitScreenKillPov()).build());
            case PERSONAL -> {
                PersonalView next = contentOptions.personalView().toggle();
                updateOptions(contentOptions.toBuilder().personalView(next).build());
            }
            case SANDBOX_FULL -> {
                PersonalView next = contentOptions.personalView().toggle();
                UUID subject = focusPlayer;
                if (next == PersonalView.TRUE_POV && subject == null && !participants.isEmpty()) {
                    subject = participants.getFirst().id();
                }
                updateOptions(contentOptions.toBuilder().personalView(next).focusPlayer(subject).build());
            }
            case CINEMATIC_PROMO -> {}
        }
    }

    private void onSubSecondaryClick() {
        if (exporting()) return;
        switch (contentOptions.mode()) {
            case SHORT -> updateOptions(contentOptions.toBuilder()
                    .identityReveal(nextIdentity(contentOptions.identityReveal())).build());
            case SANDBOX_FULL -> updateOptions(contentOptions.toBuilder()
                    .showSkillToasts(!contentOptions.showSkillToasts()).build());
            case PERSONAL, CINEMATIC_PROMO -> {}
        }
    }

    private static IdentityReveal nextIdentity(IdentityReveal cur) {
        return cur.next();
    }

    private static ActorOverlayFilter nextFilter(ActorOverlayFilter cur) {
        return cur.next();
    }

    // ------------------------------------------------------------------ input handling

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pickerOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                setPickerOpen(false);
                return true;
            }
            int page = Math.max(1, (pickerListBottom() - pickerListTop()) / PICKER_ROW_HEIGHT - 1);
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> movePickerCursor(pickerCursor - 1);
                case GLFW.GLFW_KEY_DOWN -> movePickerCursor(pickerCursor + 1);
                case GLFW.GLFW_KEY_PAGE_UP -> movePickerCursor(pickerCursor - page);
                case GLFW.GLFW_KEY_PAGE_DOWN -> movePickerCursor(pickerCursor + page);
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> choosePlayer(pickerCursor);
                default -> {}
            }
            return true;
        }
        if (customizerOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            setCustomizerOpen(false);
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
        if (customizerOpen) {
            int closeX0 = customizerCloseX(), closeY0 = customizerCloseY();
            if (button == 0 && mouseX >= closeX0 && mouseX < closeX0 + 18
                    && mouseY >= closeY0 && mouseY < closeY0 + 16) {
                setCustomizerOpen(false);
                if (minecraft != null && collapseCustomizerButton != null) {
                    collapseCustomizerButton.playDownSound(minecraft.getSoundManager());
                }
                return true;
            }
            if (mouseX < left || mouseX >= left + panelWidth
                    || mouseY < customizerTop || mouseY >= customizerTop + customizerPanelHeight) {
                setCustomizerOpen(false);
                return true;
            }
            if (button == 0 && customizerTab == 2 && handleSliderClick(mouseX, mouseY)) {
                return true;
            }
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (!customizerOpen) setFocused(null);
            return handled;
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
            if (!settingsOpen) setFocused(null);
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
        if (pickerOpen || settingsOpen || customizerOpen) {
            setFocused(null);
        }
        return handled;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (customizerOpen && customizerTab == 2 && activeSlider != SLIDER_NONE && button == 0) {
            applySliderDrag(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (activeSlider != SLIDER_NONE) {
            activeSlider = SLIDER_NONE;
            refresh();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private int matchDurationTicks() {
        return view != null && view.replay() != null ? Math.max(1, view.replay().durationTicks()) : 1;
    }

    private boolean handleSliderClick(double mouseX, double mouseY) {
        if (mouseX < sliderTrackX - 6 || mouseX > sliderTrackX + sliderTrackW + 6) return false;
        if (Math.abs(mouseY - (voiceSliderY + 4)) <= 9) {
            activeSlider = SLIDER_VOICE;
            applySliderDrag(mouseX);
            return true;
        }
        if (Math.abs(mouseY - (sfxSliderY + 4)) <= 9) {
            activeSlider = SLIDER_SFX;
            applySliderDrag(mouseX);
            return true;
        }
        if (Math.abs(mouseY - (trimSliderY + 4)) <= 10) {
            int dur = matchDurationTicks();
            var trim = contentOptions.resolveTrimRange(dur);
            double curStartR = trim.startTick() / (double) dur;
            double curEndR = trim.endTick() / (double) dur;
            double ratio = Math.clamp((mouseX - sliderTrackX) / (double) Math.max(1, sliderTrackW), 0.0, 1.0);
            double dStart = Math.abs(ratio - curStartR);
            double dEnd = Math.abs(ratio - curEndR);
            activeSlider = dStart <= dEnd ? SLIDER_TRIM_START : SLIDER_TRIM_END;
            applySliderDrag(mouseX);
            return true;
        }
        return false;
    }

    private void applySliderDrag(double mouseX) {
        double ratio = Math.clamp((mouseX - sliderTrackX) / (double) Math.max(1, sliderTrackW), 0.0, 1.0);
        switch (activeSlider) {
            case SLIDER_VOICE -> {
                float vol = Math.round(ratio * 40.0) * 0.05f; // 5% steps in [0.0, 2.0]
                contentOptions = contentOptions.toBuilder().voiceVolume(vol).build();
            }
            case SLIDER_SFX -> {
                float vol = Math.round(ratio * 40.0) * 0.05f;
                contentOptions = contentOptions.toBuilder().sfxVolume(vol).build();
            }
            case SLIDER_TRIM_START -> {
                int dur = matchDurationTicks();
                var trim = contentOptions.resolveTrimRange(dur);
                int minGap = Math.max(20, dur / 50);
                int endTick = trim.endTick();
                int startTick = Math.clamp((int) Math.round(ratio * dur), 0, Math.max(0, endTick - minGap));
                int storedEnd = endTick >= dur ? -1 : endTick;
                contentOptions = contentOptions.toBuilder().trimRange(startTick, storedEnd).build();
            }
            case SLIDER_TRIM_END -> {
                int dur = matchDurationTicks();
                var trim = contentOptions.resolveTrimRange(dur);
                int minGap = Math.max(20, dur / 50);
                int startTick = trim.startTick();
                int endTick = Math.clamp((int) Math.round(ratio * dur), Math.min(dur, startTick + minGap), dur);
                int storedEnd = endTick >= dur ? -1 : endTick;
                contentOptions = contentOptions.toBuilder().trimRange(startTick, storedEnd).build();
            }
            default -> {}
        }
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (pickerOpen) {
            int maxScroll = maxPickerScroll();
            if (maxScroll > 0 && vertical != 0) {
                pickerScroll = Math.clamp(pickerScroll - (int) Math.signum(vertical) * PICKER_ROW_HEIGHT, 0, maxScroll);
            }
            return true;
        }
        if (perspectivePlayer != null && perspectivePlayer.active && vertical != 0
                && perspectivePlayer.isMouseOver(mouseX, mouseY)) {
            stepFocusPlayer(vertical > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private void stepFocusPlayer(int delta) {
        if (exporting() || participants.isEmpty()) return;
        int current = participantIndex(focusPlayer);
        int next = current < 0 ? (delta >= 0 ? 0 : participants.size() - 1)
                : Math.floorMod(current + delta, participants.size());
        setFocusPlayer(participants.get(next).id());
        refresh();
    }

    private void setFocusPlayer(@Nullable UUID player) {
        focusPlayer = player;
        var builder = contentOptions.toBuilder().focusPlayer(player);
        if (contentOptions.mode() == ExportMode.CINEMATIC_PROMO) {
            builder.promoScope(player == null ? PromoScope.GLOBAL_ENSEMBLE : PromoScope.PERSONAL_MVP);
        } else if (contentOptions.mode() == ExportMode.SANDBOX_FULL && player == null) {
            builder.personalView(PersonalView.SANDBOX_FOLLOW);
        }
        contentOptions = builder.build();
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
            if (!exporting()) {
                setFocusPlayer(null);
            }
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
        if (contentOptions.mode() == ExportMode.PERSONAL && focusPlayer == null && !exporting()) {
            focusPlayer = participants.getFirst().id();
        }
        if (!exporting()) {
            setFocusPlayer(focusPlayer);
        }
    }

    private boolean cachedFfmpegReady;
    private boolean cachedFfmpegProbing;

    private void refreshFfmpegState() {
        cachedFfmpegReady = minecraft != null && ReplayFfmpegManager.isReady(minecraft.gameDirectory.toPath());
        cachedFfmpegProbing = ReplayFfmpegManager.isProbing();
    }

    private boolean ffmpegReady() {
        return cachedFfmpegReady;
    }

    private boolean probingFfmpeg() {
        return !exporting() && compress && !cachedFfmpegReady && cachedFfmpegProbing;
    }

    private boolean needsFfmpegAction() {
        return !exporting() && compress && !cachedFfmpegReady && !cachedFfmpegProbing;
    }

    private void toggleExport() {
        ReplayExportClient.clearStartError();
        var current = ReplayExportClient.current();
        if (current != null && current.running()) {
            current.cancel();
        } else if (probingFfmpeg()) {
            // Wait for the initial asynchronous FFmpeg detection pass instead of triggering a duplicate download.
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
            setCustomizerOpen(false);
            var id = history;
            var board = pos;
            Supplier<ClientReplayBoard.View> source = id != null
                    ? () -> ReplayHistoryClient.watch(id) : () -> ClientReplayBoard.viewForBoard(board);
            ReplayExportContentOptions launchOpts = contentOptions.toBuilder()
                    .focusPlayer(focusPlayer)
                    .musicLevel(music)
                    .build();
            ReplayExportClient.start(source.get(), launchOpts, resolution, compress, quality, source, preferSimpleExport);
        }
        refresh();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        if (!validTable()) { onClose(); return; }
        refresh();
    }

    private boolean validTable() {
        if (pos == null) return minecraft != null && minecraft.level != null;
        if (minecraft == null || minecraft.level != level || level == null || minecraft.player == null
                || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || !level.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)) return false;
        double reach = level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker
                ? marker.settings().interactReach() : 8;
        double limit = reach + 6;
        return minecraft.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= limit * limit;
    }

    private boolean exporting() { return video != null && video.running(); }

    private ReplayExportContentOptions shownOptions() {
        return exporting() ? video.options : contentOptions;
    }

    private void refresh() {
        refreshFfmpegState();
        view = history != null ? ReplayHistoryClient.watch(history) : ClientReplayBoard.viewForBoard(pos);
        video = ReplayExportClient.forScreen(video);
        syncParticipants(view == null ? null : view.replay());
        boolean exporting = exporting();
        if (exporting) {
            setPickerOpen(false);
            setSettingsOpen(false);
            setCustomizerOpen(false);
        } else if (participants.isEmpty()) {
            setPickerOpen(false);
        }
        syncWidgetsVisible();

        ReplayExportContentOptions shownOpts = shownOptions();
        ReplayExportResolution shownRes = exporting ? video.resolution : resolution;
        boolean shownCompress = exporting ? video.compress : compress;
        ReplayExportQuality shownQuality = exporting ? video.quality : quality;
        ReplayMusicPlan.Level shownMusic = shownOpts.musicLevel();
        UUID shownFocus = shownOpts.focusPlayer();
        boolean full = !shownOpts.roofCutaway();

        // Mode tabs
        ExportMode[] modes = ExportMode.values();
        for (int i = 0; i < modes.length; i++) {
            modeButtons[i].active = !exporting;
            ((BrassButton) modeButtons[i]).selected = modes[i] == shownOpts.mode();
        }

        // Sub-controls row
        subPrimaryButton.active = !exporting;
        subSecondaryButton.active = !exporting;
        ((BrassButton) subPrimaryButton).selected = false;
        ((BrassButton) subSecondaryButton).selected = false;
        switch (shownOpts.mode()) {
            case SHORT -> {
                boolean split = shownOpts.splitScreenKillPov();
                subPrimaryButton.setMessage(toggleLabel("export.opt_kill_split", split));
                ((BrassButton) subPrimaryButton).selected = split;
                subSecondaryButton.setMessage(tr("export.opt_identity", tr("export.identity_" + shownOpts.identityReveal().id)));
            }
            case PERSONAL -> {
                boolean pov = shownOpts.personalView() == PersonalView.TRUE_POV;
                subPrimaryButton.setMessage(tr(pov ? "export.personal_view_pov" : "export.personal_view_sandbox"));
                ((BrassButton) subPrimaryButton).selected = pov;
                PersonalPacing[] pacings = PersonalPacing.values();
                for (int i = 0; i < pacings.length; i++) {
                    pacingButtons[i].active = !exporting;
                    ((BrassButton) pacingButtons[i]).selected = pacings[i] == shownOpts.personalPacing();
                }
            }
            case SANDBOX_FULL -> {
                boolean pov = shownOpts.personalView() == PersonalView.TRUE_POV;
                subPrimaryButton.setMessage(tr(pov ? "export.personal_view_pov"
                        : shownFocus != null ? "export.personal_view_sandbox" : "export.sandbox_view_diorama"));
                ((BrassButton) subPrimaryButton).selected = pov;
                boolean toasts = shownOpts.showSkillToasts();
                subSecondaryButton.setMessage(toggleLabel("export.opt_skill_toasts", toasts));
                ((BrassButton) subSecondaryButton).selected = toasts;
            }
            case CINEMATIC_PROMO -> {}
        }

        int settingsY = shownOpts.mode() == ExportMode.CINEMATIC_PROMO
                ? (mainSubY + mainDrawersY) / 2 : mainDrawersY;
        customizerButton.active = !exporting;
        customizerButton.setY(settingsY);
        videoSettingsButton.active = !exporting;
        videoSettingsButton.setY(settingsY);
        boolean kineticStyle = shownOpts.visualStyle() == ReplayExportContentOptions.VisualStyle.KINETIC;
        styleButton.active = !exporting;
        styleButton.setY(settingsY);
        styleButton.setMessage(tr("export.style", tr(kineticStyle ? "export.style_kinetic" : "export.style_brass")));
        styleButton.setTooltip(Tooltip.create(tr(kineticStyle ? "export.style_kinetic_tip" : "export.style_brass_tip")));
        ((BrassButton) styleButton).selected = kineticStyle;

        // Customizer sub-view states
        for (int i = 0; i < 3; i++) {
            customizerTabButtons[i].active = !exporting;
            ((BrassButton) customizerTabButtons[i]).selected = customizerTab == i;
        }
        refreshToggle(optIntro, "export.opt_intro", shownOpts.includeTitle(), exporting);
        refreshToggle(optOverview, "export.opt_overview", shownOpts.includeReview(), exporting);
        refreshToggle(optVictoryMvp, "export.opt_victory_mvp", shownOpts.includeVictoryMvp(), exporting);
        refreshToggle(optOutro, "export.opt_outro", shownOpts.includeOutro(), exporting);
        refreshToggle(optLetterbox, "export.opt_letterbox_dof", shownOpts.includeLetterboxDof(), exporting);
        refreshToggle(optWatermark, "export.opt_watermark", shownOpts.includeWatermark(), exporting);
        refreshToggle(optKillSplit, "export.opt_kill_split", shownOpts.splitScreenKillPov(), exporting);
        optResetDefaults.active = !exporting;

        refreshToggle(optTimecode, "export.opt_timecode", shownOpts.showTimecode(), exporting);
        refreshToggle(optAliveMatrix, "export.opt_alive_matrix", shownOpts.showAliveMatrix(), exporting);
        refreshToggle(optKillFeed, "export.opt_kill_feed", shownOpts.showKillFeed(), exporting);
        refreshToggle(optTimeline, "export.opt_timeline", shownOpts.showTimeline(), exporting);
        refreshToggle(optSkillToasts, "export.opt_skill_toasts", shownOpts.showSkillToasts(), exporting);
        optIdentity.active = !exporting;
        optIdentity.setMessage(tr("export.opt_identity", tr("export.identity_" + shownOpts.identityReveal().id)));
        ((BrassButton) optIdentity).selected = shownOpts.identityReveal() != IdentityReveal.HIDDEN;
        optTrails.active = !exporting;
        optTrails.setMessage(tr("export.opt_trails", tr("export.filter_" + shownOpts.trailFilter().id)));
        ((BrassButton) optTrails).selected = shownOpts.trailFilter() != ActorOverlayFilter.NONE;
        optNameplates.active = !exporting;
        optNameplates.setMessage(tr("export.opt_nameplates", tr("export.filter_" + shownOpts.nameplateFilter().id)));
        ((BrassButton) optNameplates).selected = shownOpts.nameplateFilter() != ActorOverlayFilter.NONE;

        ReplayMusicPlan.Level[] levels = ReplayMusicPlan.Level.values();
        for (int i = 0; i < levels.length; i++) {
            customizerMusicButtons[i].active = !exporting;
            ((BrassButton) customizerMusicButtons[i]).selected = levels[i] == shownMusic;
        }
        refreshToggle(optAutoDucking, "export.audio_ducking", shownOpts.autoDucking(), exporting);
        int dur = view != null && view.replay() != null ? view.replay().durationTicks() : 0;
        optTrimFull.active = !exporting && dur > 0 && shownOpts.resolveTrimRange(dur).isTrimmed(dur);

        // Video & Quality settings buttons
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
        boolean canUseNative = ReplayExportBackend.supportsNative(shownOpts);
        nativeExport.active = simpleExport.active = canUseNative && !exporting;
        var backend = exporting ? video.backend : ReplayExportBackend.select(shownOpts, preferSimpleExport);
        ((BrassButton) nativeExport).selected = canUseNative && backend == ReplayExportBackend.NATIVE;
        ((BrassButton) simpleExport).selected = canUseNative && backend == ReplayExportBackend.SIMPLE;
        ((BrassButton) roofCut).selected = !full;
        ((BrassButton) roofFull).selected = full;
        for (int i = 0; i < levels.length; i++) {
            musicButtons[i].active = !exporting;
            ((BrassButton) musicButtons[i]).selected = levels[i] == shownMusic;
        }

        // Perspective row
        boolean hasPlayers = !participants.isEmpty();
        int focusedIdx = participantIndex(shownFocus);
        boolean personal = shownOpts.mode() == ExportMode.PERSONAL;
        perspectiveGlobal.active = !exporting && !personal;
        ((BrassButton) perspectiveGlobal).selected = focusedIdx < 0;
        perspectivePlayer.active = !exporting && hasPlayers;
        ((BrassButton) perspectivePlayer).selected = focusedIdx >= 0;
        perspectivePlayer.setX(personal ? left + 12 : playerButtonX);
        perspectivePlayer.setWidth(personal ? panelWidth - 24 : playerButtonWidth);
        if (focusedIdx >= 0) {
            perspectivePlayer.setMessage(ReplayBoardTexts.name(participants.get(focusedIdx)));
        } else {
            perspectivePlayer.setMessage(tr(hasPlayers ? "export.perspective_pick" : "export.perspective_none"));
        }

        // Start / Cancel & Folder / FFmpeg buttons
        boolean probing = probingFfmpeg();
        boolean needFfmpeg = needsFfmpegAction();
        boolean installing = ReplayFfmpegManager.isInstalling();
        boolean importing = ReplayFfmpegManager.isImporting();
        back.setTooltip(exporting ? Tooltip.create(tr("export.continue_in_background")) : null);
        if (exporting) {
            start.setMessage(tr("controls.cancel"));
            ((BrassButton) start).accent = true;
            start.active = true;
            start.setTooltip(null);
        } else if (probing) {
            start.setMessage(tr("export.ffmpeg_probing"));
            ((BrassButton) start).accent = false;
            start.active = false;
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
            folderOrImport.setMessage(tr("export.open_folder"));
            folderOrImport.active = true;
            folderOrImport.setTooltip(null);
        }
    }

    private void refreshToggle(Button button, String key, boolean on, boolean exporting) {
        button.active = !exporting;
        button.setMessage(toggleLabel(key, on));
        ((BrassButton) button).selected = on;
    }

    private static Component toggleLabel(String key, boolean on) {
        return Component.literal(on ? "✓ " : "✕ ").append(tr(key));
    }

    // ------------------------------------------------------------------ text

    private double filmSeconds() {
        LastMatchReplay replay = view == null ? null : view.replay();
        ReplayExportContentOptions shownOpts = shownOptions();
        if (activeSlider == SLIDER_NONE && (replay != secondsFor || !Objects.equals(shownOpts, secondsOptions))) {
            secondsFor = replay;
            secondsOptions = shownOpts;
            filmSeconds = 0;
            if (replay != null) {
                try {
                    filmSeconds = ReplayCinemaDirector.direct(replay, view.voice(), shownOpts).total();
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
        var replay = view.replay();
        return tr("export.match", replay.mapName(), ReplayBoardTexts.clock(replay.durationTicks()));
    }

    private Component settingsSubtitle() {
        return compress
                ? tr("export.settings_subtitle_mp4", resolution.dimensions(), quality.label())
                : tr("export.settings_subtitle_avi", resolution.dimensions());
    }

    private Component exportStatus() {
        if (folderFailed) return tr("controls.folder_error");
        if (video == null) {
            Component startError = ReplayExportClient.lastStartError();
            if (startError != null) return startError;
            if (probingFfmpeg()) return tr("export.ffmpeg_probing");
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
            Component format = tr(compress ? "export.format_mp4" : "export.format_avi");
            return seconds > 0 ? tr("export.detail", format, resolution.dimensions(), Math.round(seconds))
                    : tr("export.detail_short", format, resolution.dimensions());
        }
        return video.status();
    }

    // ------------------------------------------------------------------ rendering

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int top = activeTop();
        int panelHeight = activePanelHeight();
        g.fillGradient(0, 0, width, height, 0x70040810, 0xB0040810);
        g.fillGradient(left, top, left + panelWidth, top + panelHeight, 0xF2111A33, 0xF204060D);
        g.renderOutline(left, top, panelWidth, panelHeight, ReplayPalette.BRASS);
        g.renderOutline(left + 3, top + 3, panelWidth - 6, panelHeight - 6, 0x998A6A35);
        if (pickerOpen) return;
        if (customizerOpen) {
            renderCustomizerBackground(g);
            return;
        }
        if (settingsOpen) {
            renderSettingsBackground(g);
            return;
        }

        caption(g, tr("export.mode_section"), mainModeY - 10);
        caption(g, tr("export.perspective"), mainPerspectiveY - 10);
    }

    private void renderCustomizerBackground(GuiGraphics g) {
        String header = font.plainSubstrByWidth(
                tr("export.customizer_title").getString(), Math.max(40, panelWidth - 64));
        int cx = left + panelWidth / 2;
        int titleW = font.width(header);
        int ruleY = customizerTop + 14;
        int closeX0 = customizerCloseX();
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
        caption(g, tr("export.backend"), settingsSectionY(5) - 10);
    }

    private int settingsSectionY(int index) {
        return settingsTop + settingsLayout.sectionY(index);
    }

    private int settingsCloseX() { return left + panelWidth - 26; }
    private int settingsCloseY() { return settingsTop + 7; }
    private int customizerCloseX() { return left + panelWidth - 26; }
    private int customizerCloseY() { return customizerTop + 7; }

    /**
     * Adaptive vertical layout for the main export view. Guarantees zero overlap at tight GUI scales
     * (e.g. 1280x720 at guiScale=4 -> panelHeight=174) and avoids excessive empty vertical gaps at
     * standard scales (e.g. panelHeight=234) by allocating card height and row gaps proportionally.
     */
    record MainLayout(int modeY, int perspectiveY, int subY, int drawersY,
                      int progressY, int buttonsY, int optionHeight) {
        static MainLayout compute(int panelHeight) {
            int optH = panelHeight < 183 ? 14 : panelHeight < 198 ? 16 : 18;
            int buttonsY = panelHeight - 25;
            int progressY = buttonsY - 22;
            int modeY = 42;
            int perspectiveY = modeY + optH + 13;
            int subY = perspectiveY + optH + 4;
            int drawersY = progressY - optH - 7;
            return new MainLayout(modeY, perspectiveY, subY, drawersY, progressY, buttonsY, optH);
        }
    }

    record ExportLayout(int firstY, int spacing, int optionHeight, int buttonsY) {
        /** Expanded video &amp; quality settings view. */
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
        if (customizerOpen) {
            renderCustomizerForeground(graphics, mouseX, mouseY);
            return;
        }
        if (settingsOpen) {
            renderSettingsForeground(graphics, mouseX, mouseY);
            return;
        }

        int top = mainTop;
        int cx = width / 2;
        int textW = panelWidth - 24;
        graphics.drawCenteredString(font, title, cx, top + 10, ReplayPalette.BRASS_LIGHT);
        graphics.drawCenteredString(font, font.plainSubstrByWidth(matchStatus().getString(), textW), cx, top + 22, ReplayPalette.TEXT);

        boolean exporting = exporting();
        boolean installing = needsFfmpegAction() && ReplayFfmpegManager.isInstalling();
        if (exporting || installing) {
            int x = left + 12, y = mainProgressY;
            double progress = exporting ? video.displayProgress() : ReplayFfmpegManager.progress();
            graphics.fill(x, y, x + textW, y + 6, 0xFF1A2236);
            graphics.fill(x, y, x + (int) (textW * progress), y + 6,
                    exporting ? 0xFF52C5B9 : ReplayPalette.BRASS_LIGHT);
        }
        var statusLines = font.split(exportStatus(), textW);
        int maxLines = exporting || installing ? 1 : 2;
        int statusY = mainProgressY + (maxLines == 1 ? 9 : statusLines.size() > 1 ? 2 : 5);
        for (int i = 0; i < Math.min(maxLines, statusLines.size()); i++) {
            graphics.drawCenteredString(font, statusLines.get(i), cx, statusY + i * 9, 0xFFC5D3E2);
        }
    }

    private void renderCustomizerForeground(GuiGraphics g, int mouseX, int mouseY) {
        String header = font.plainSubstrByWidth(
                tr("export.customizer_title").getString(), Math.max(40, panelWidth - 64));
        int cx = left + panelWidth / 2;
        g.drawCenteredString(font, header, cx, customizerTop + 10, ReplayPalette.BRASS_LIGHT);

        int closeX0 = customizerCloseX(), closeY0 = customizerCloseY();
        boolean closeHover = mouseX >= closeX0 && mouseX < closeX0 + 18 && mouseY >= closeY0 && mouseY < closeY0 + 16;
        g.fill(closeX0, closeY0, closeX0 + 18, closeY0 + 16, closeHover ? 0xFF4E1E24 : 0xFF18233F);
        g.renderOutline(closeX0, closeY0, 18, 16, closeHover ? ReplayPalette.RUBY : ReplayPalette.BRASS_DIM);
        g.drawCenteredString(font, "×", closeX0 + 9, closeY0 + 4, closeHover ? 0xFFFFFFFF : ReplayPalette.TEXT);

        if (customizerTab == 2) {
            renderAudioAndTrimSliders(g);
        }
    }

    private void renderAudioAndTrimSliders(GuiGraphics g) {
        int voicePct = Math.round(contentOptions.voiceVolume() * 100f);
        g.drawString(font, tr("export.audio_voice", voicePct), sliderTrackX, voiceSliderY - 12, ReplayPalette.TEXT, false);
        drawSingleSlider(g, sliderTrackX, voiceSliderY, sliderTrackW, contentOptions.voiceVolume() / 2.0,
                activeSlider == SLIDER_VOICE, 0xFF52C5B9);

        int sfxPct = Math.round(contentOptions.sfxVolume() * 100f);
        g.drawString(font, tr("export.audio_sfx", sfxPct), sliderTrackX, sfxSliderY - 12, ReplayPalette.TEXT, false);
        drawSingleSlider(g, sliderTrackX, sfxSliderY, sliderTrackW, contentOptions.sfxVolume() / 2.0,
                activeSlider == SLIDER_SFX, ReplayPalette.BRASS_LIGHT);

        g.drawString(font, tr("export.audio_music"), sliderTrackX, sfxSliderY + 15, ReplayPalette.TEXT_MUTED, false);

        int durationTicks = matchDurationTicks();
        var trim = contentOptions.resolveTrimRange(durationTicks);
        g.drawString(font, tr("export.trim_range", ReplayBoardTexts.clock(trim.startTick()), ReplayBoardTexts.clock(trim.endTick())),
                sliderTrackX, trimSliderY - 14, ReplayPalette.CHAMPAGNE, false);
        drawDualRangeSlider(g, sliderTrackX, trimSliderY, sliderTrackW,
                trim.startTick() / (double) durationTicks, trim.endTick() / (double) durationTicks,
                activeSlider == SLIDER_TRIM_START, activeSlider == SLIDER_TRIM_END);
    }

    private static void drawSingleSlider(GuiGraphics g, int x, int y, int w, double ratio, boolean dragging, int fillColor) {
        double r = Math.clamp(ratio, 0.0, 1.0);
        int thumbX = x + (int) Math.round(r * w);
        g.fill(x, y + 2, x + w, y + 6, 0xFF1A2236);
        g.renderOutline(x - 1, y + 1, w + 2, 6, 0x808A6A35);
        g.fill(x, y + 2, thumbX, y + 6, fillColor);
        // 100% midpoint tick
        g.fill(x + w / 2, y, x + w / 2 + 1, y + 8, 0x88E5CA8A);
        diamond(g, thumbX, y + 4, dragging ? 0xFFFFFFFF : ReplayPalette.CHAMPAGNE);
    }

    private static void drawDualRangeSlider(GuiGraphics g, int x, int y, int w, double startR, double endR,
                                            boolean dragStart, boolean dragEnd) {
        int x0 = x + (int) Math.round(Math.clamp(startR, 0.0, 1.0) * w);
        int x1 = x + (int) Math.round(Math.clamp(endR, 0.0, 1.0) * w);
        g.fill(x, y + 2, x + w, y + 6, 0xFF141B2E);
        g.renderOutline(x - 1, y + 1, w + 2, 6, 0x808A6A35);
        g.fill(x0, y + 2, Math.max(x0 + 2, x1), y + 6, ReplayPalette.BRASS);
        diamond(g, x0, y + 4, dragStart ? 0xFFFFFFFF : ReplayPalette.BRASS_LIGHT);
        diamond(g, x1, y + 4, dragEnd ? 0xFFFFFFFF : ReplayPalette.BRASS_LIGHT);
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
    private int pickerListBottom() {
        int rawHeight = Math.max(PICKER_ROW_HEIGHT, (pickerTop + pickerPanelHeight - 12) - pickerListTop());
        int fullRows = Math.max(1, rawHeight / PICKER_ROW_HEIGHT);
        return pickerListTop() + fullRows * PICKER_ROW_HEIGHT;
    }
    private int maxPickerScroll() {
        int contentHeight = participants.size() * PICKER_ROW_HEIGHT;
        int viewHeight = Math.max(1, pickerListBottom() - pickerListTop());
        return Math.max(0, contentHeight - viewHeight);
    }

    private void movePickerCursor(int index) {
        pickerCursor = Math.clamp(index, 0, Math.max(0, participants.size() - 1));
        int rowTop = pickerCursor * PICKER_ROW_HEIGHT;
        int viewHeight = pickerListBottom() - pickerListTop();
        pickerScroll = Math.clamp(pickerScroll, Math.max(0, rowTop + PICKER_ROW_HEIGHT - viewHeight), rowTop);
    }

    private void choosePlayer(int index) {
        if (index < 0 || index >= participants.size()) return;
        setFocusPlayer(participants.get(index).id());
        setPickerOpen(false);
        if (minecraft != null) perspectivePlayer.playDownSound(minecraft.getSoundManager());
        refresh();
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
            choosePlayer(row);
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
        int totalRows = participants.size();
        for (int i = 0; i < totalRows; i++) {
            int rowTop = listTop + i * PICKER_ROW_HEIGHT - pickerScroll;
            int rowBottom = rowTop + PICKER_ROW_HEIGHT;
            if (rowBottom <= listTop || rowTop >= listBottom) continue;
            boolean hovered = mouseX >= listLeft && mouseX < listRight && mouseY >= Math.max(listTop, rowTop)
                    && mouseY < Math.min(listBottom, rowBottom);
            var participant = participants.get(i);
            boolean selected = participant.id().equals(focusPlayer);
            int bgTop, bgBot, accent;
            if (selected) {
                bgTop = 0xFF3A2D14;
                bgBot = 0xFF21190B;
                accent = ReplayPalette.BRASS_LIGHT;
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
            } else {
                int roleColor = ReplayPalette.opaque(participant.endColor(), ReplayPalette.PASSENGER);
                g.fill(listLeft + 1, rowTop + 3, listLeft + 3, rowBottom - 3, roleColor);
            }
            if (i == pickerCursor) {
                g.renderOutline(listLeft + 1, rowTop + 1, listRight - listLeft - 2, PICKER_ROW_HEIGHT - 2,
                        ReplayPalette.TEXT_MUTED);
            }
        }
        g.flush();
        for (int i = 0; i < totalRows; i++) {
            int rowTop = listTop + i * PICKER_ROW_HEIGHT - pickerScroll;
            int rowBottom = rowTop + PICKER_ROW_HEIGHT;
            if (rowBottom <= listTop || rowTop >= listBottom) continue;
            LastMatchReplay.Participant p = participants.get(i);
            boolean selected = p.id().equals(focusPlayer);
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
                    nameX, rowTop + 7, selected ? ReplayPalette.CHAMPAGNE : ReplayPalette.TEXT, false);
            int roleX = nameX + nameW + 6;
            int roleAvail = listRight - killsW - roleX - 6;
            if (roleAvail > 16) {
                g.drawString(font, font.plainSubstrByWidth(roleStr, roleAvail), roleX, rowTop + 7, roleColor, false);
            }
            if (killsW > 0) {
                g.drawString(font, kills, listRight - killsW, rowTop + 7, ReplayPalette.RUBY, false);
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
        boolean primary;

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
            } else if (primary) {
                top = hover ? 0xFF27565B : 0xFF1D4147; bottom = 0xFF12282E;
                border = hover ? 0xFF91D6D0 : 0xFF61A39F; text = 0xFFF0FAF8;
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
            g.renderOutline(x0, y0, getWidth(), getHeight(), isFocused() && active ? 0xFFE3E9EF : border);
            if (selected) {
                g.fill(x0 + 1, y1 - 2, x1 - 1, y1 - 1, border);
            }
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String label = getMessage().getString();
            int available = getWidth() - 6;
            if (font.width(label) > available) {
                label = font.plainSubstrByWidth(label, Math.max(0, available - font.width("…"))) + "…";
            }
            g.drawCenteredString(font, label, x0 + getWidth() / 2, y0 + (getHeight() - 8) / 2, text);
        }
    }
}
