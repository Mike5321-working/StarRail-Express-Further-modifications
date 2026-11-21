package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Detailed export page opened by the "Export Video" plaque above the replay table: match overview, resolution,
 * output format and train-body view choices, the planned film length, live progress and the output folder.
 *
 * The client export task continues when this page closes.
 */
final class ReplayExportScreen extends Screen {
    private static final int PANEL_HEIGHT = 262;
    private static final int GAP = 4;

    private final ClientLevel level;
    private final BlockPos pos;
    private final Screen parent;
    private final java.util.UUID history;
    private ReplayExportResolution resolution;
    private boolean compress;
    private final Button[] resolutionButtons = new Button[ReplayExportResolution.values().length];
    private Button mp4, avi, roofCut, roofFull, start, folderOrImport;
    private int left, top, panelWidth, panelHeight, spacing;
    private ClientReplayBoard.View view;
    private ReplayVideoExport video;
    private boolean folderFailed;
    private boolean historyPinned;
    private @Nullable LastMatchReplay secondsFor;
    private double filmSeconds;

    ReplayExportScreen(ClientLevel level, BlockPos pos) {
        super(tr("export.title"));
        this.level = level;
        this.parent = null;
        this.history = null;
        this.pos = pos.immutable();
        this.resolution = ReplayExportResolution.fromId(ClientVisualPreferences.getReplayVideoResolution());
        this.compress = ClientVisualPreferences.isReplayVideoCompress() || !this.resolution.aviSupported();
    }

    ReplayExportScreen(Screen parent, java.util.UUID history) {
        super(tr("export.title"));
        this.level = null;
        this.pos = null;
        this.parent = parent;
        this.history = history;
        this.resolution = ReplayExportResolution.fromId(ClientVisualPreferences.getReplayVideoResolution());
        this.compress = ClientVisualPreferences.isReplayVideoCompress() || !this.resolution.aviSupported();
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

    // ------------------------------------------------------------------ layout

    @Override protected void init() {
        if (!historyPinned) {
            ReplayHistoryClient.pin(history);
            historyPinned = true;
        }
        panelWidth = Math.min(348, width - 12);
        panelHeight = Math.min(PANEL_HEIGHT, height - 6);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        spacing = Math.max(32, Math.min(36, (panelHeight - 100) / 4));
        int inner = panelWidth - 24, x = left + 12;
        int half = (inner - GAP) / 2;

        ReplayExportResolution[] all = ReplayExportResolution.values();
        int resW = (inner - GAP * (all.length - 1)) / all.length;
        for (int i = 0; i < all.length; i++) {
            ReplayExportResolution value = all[i];
            resolutionButtons[i] = addRenderableWidget(new BrassButton(x + i * (resW + GAP), sectionY(0), resW, 20,
                    value.label(), b -> {
                        resolution = value;
                        if (!resolution.aviSupported()) compress = true;
                        ClientVisualPreferences.setReplayVideoResolution(value.id);
                        if (!resolution.aviSupported()) ClientVisualPreferences.setReplayVideoCompress(true);
                        refresh();
                    }));
            resolutionButtons[i].setTooltip(Tooltip.create(tr("export.res_tooltip", value.dimensions())));
        }
        mp4 = addRenderableWidget(new BrassButton(x, sectionY(1), half, 20, tr("export.format_mp4"), b -> {
            compress = true;
            ClientVisualPreferences.setReplayVideoCompress(true);
            refresh();
        }));
        mp4.setTooltip(Tooltip.create(tr("controls.compress_tooltip_on")));
        avi = addRenderableWidget(new BrassButton(x + half + GAP, sectionY(1), half, 20, tr("export.format_avi"), b -> {
            compress = false;
            ClientVisualPreferences.setReplayVideoCompress(false);
            refresh();
        }));
        avi.setTooltip(Tooltip.create(tr("controls.compress_tooltip_off")));
        roofCut = addRenderableWidget(new BrassButton(x, sectionY(2), half, 20, tr("export.roof_cut"), b -> {
            if (ReplayBoardLocalState.fullBody()) ReplayBoardLocalState.toggleRoof();
            refresh();
        }));
        roofFull = addRenderableWidget(new BrassButton(x + half + GAP, sectionY(2), half, 20, tr("export.roof_full"), b -> {
            if (!ReplayBoardLocalState.fullBody()) ReplayBoardLocalState.toggleRoof();
            refresh();
        }));

        int third = (inner - GAP * 2) / 3;
        int buttonsY = top + panelHeight - 32;
        start = addRenderableWidget(new BrassButton(x, buttonsY, third, 20, tr("export.start"), b -> toggleExport()));
        folderOrImport = addRenderableWidget(new BrassButton(x + third + GAP, buttonsY, third, 20, tr("controls.folder"), b -> {
            if (needsFfmpegAction()) {
                ReplayFfmpegManager.startManualImport(minecraft.gameDirectory.toPath());
            } else {
                folderFailed = !ReplayVideoExport.openFolder(minecraft);
            }
            refresh();
        }));
        addRenderableWidget(new BrassButton(x + (third + GAP) * 2, buttonsY, inner - (third + GAP) * 2, 20,
                CommonComponents.GUI_DONE, b -> onClose()));
        refresh();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && folderOrImport != null && folderOrImport.active
                && folderOrImport.isMouseOver(mouseX, mouseY) && needsFfmpegAction()) {
            folderFailed = !ReplayFfmpegManager.openBinFolder(minecraft.gameDirectory.toPath());
            if (minecraft != null) folderOrImport.playDownSound(minecraft.getSoundManager());
            refresh();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
            var id = history;
            var board = pos;
            Supplier<ClientReplayBoard.View> source = id == null
                    ? () -> ClientReplayBoard.viewAt(board) : () -> ReplayHistoryClient.watch(id);
            ReplayExportClient.start(source.get(), ReplayBoardLocalState.fullBody(), resolution, compress, source);
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
        boolean exporting = exporting();
        ReplayExportResolution shownRes = exporting ? video.resolution : resolution;
        boolean shownCompress = exporting ? video.compress : compress;

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
        boolean full = ReplayBoardLocalState.fullBody();
        roofCut.active = roofFull.active = !exporting;
        ((BrassButton) roofCut).selected = !full;
        ((BrassButton) roofFull).selected = full;

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
        if (replay != secondsFor) {
            secondsFor = replay;
            filmSeconds = 0;
            if (replay != null) {
                try {
                    filmSeconds = new ReplayVideoScript(replay).total();
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
        g.fillGradient(0, 0, width, height, 0x70040810, 0xB0040810);
        g.fillGradient(left, top, left + panelWidth, top + panelHeight, 0xF2111A33, 0xF204060D);
        g.renderOutline(left, top, panelWidth, panelHeight, ReplayPalette.BRASS);
        g.renderOutline(left + 3, top + 3, panelWidth - 6, panelHeight - 6, 0x998A6A35);

        int cx = left + panelWidth / 2;
        // Title with brass rules and diamond studs on both sides.
        int titleW = font.width(title);
        int ruleY = top + 14;
        int lx0 = left + 14, lx1 = cx - titleW / 2 - 14, rx0 = cx + titleW / 2 + 14, rx1 = left + panelWidth - 14;
        g.fill(lx0, ruleY, Math.max(lx0, lx1), ruleY + 1, ReplayPalette.BRASS_DIM);
        g.fill(Math.min(rx0, rx1), ruleY, rx1, ruleY + 1, ReplayPalette.BRASS_DIM);
        diamond(g, lx1 + 5, ruleY, ReplayPalette.BRASS_LIGHT);
        diamond(g, rx0 - 5, ruleY, ReplayPalette.BRASS_LIGHT);

        // Overview card.
        int cardTop = top + 38, cardBottom = Math.min(top + 74, sectionY(0) - 14);
        if (cardBottom > cardTop) g.fill(left + 12, cardTop, left + panelWidth - 12, cardBottom, 0x99070B18);
        if (cardBottom > cardTop) g.renderOutline(left + 12, cardTop, panelWidth - 24, cardBottom - cardTop, 0x668A6A35);

        // Section captions.
        caption(g, tr("export.resolution"), sectionY(0) - 10);
        caption(g, tr("export.format"), sectionY(1) - 10);
        caption(g, tr("export.roof"), sectionY(2) - 10);

        // Progress bar track.
        int barY = sectionY(3) - 5, barX = left + 12, barW = panelWidth - 24;
        g.fill(barX, barY, barX + barW, barY + 7, 0xFF1A2236);
        g.renderOutline(barX - 1, barY - 1, barW + 2, 9, 0x808A6A35);
    }

    private int sectionY(int index) {
        return top + Math.max(42, Math.min(88, panelHeight - 172)) + index * spacing;
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
        super.render(graphics, mouseX, mouseY, delta);

        int cx = width / 2;
        int textW = panelWidth - 24;
        graphics.drawCenteredString(font, title, cx, top + 10, ReplayPalette.BRASS_LIGHT);
        graphics.drawCenteredString(font, font.plainSubstrByWidth(matchStatus().getString(), textW), cx, top + 24, ReplayPalette.TEXT);
        if (sectionY(0) >= top + 72) graphics.drawString(font, font.plainSubstrByWidth(statsLine().getString(), textW - 8), left + 18, top + 43, ReplayPalette.TEXT, false);
        if (sectionY(0) >= top + 82) graphics.drawString(font, font.plainSubstrByWidth(resultLine().getString(), textW - 8), left + 18, top + 58,
                view != null && view.replay() != null ? ReplayBoardTexts.winColor(view.replay()) : ReplayPalette.TEXT_MUTED, false);

        int barX = left + 12, barW = panelWidth - 24, barY = sectionY(3) - 5;
        if (video != null) {
            graphics.fill(barX, barY, barX + (int) (barW * video.displayProgress()), barY + 7, 0xFF52C5B9);
        } else if (needsFfmpegAction() && ReplayFfmpegManager.isInstalling()) {
            graphics.fill(barX, barY, barX + (int) (barW * ReplayFfmpegManager.progress()), barY + 7, ReplayPalette.BRASS_LIGHT);
        }
        graphics.drawCenteredString(font, font.plainSubstrByWidth(exportStatus().getString(), textW), cx, barY + 12, 0xFFC5D3E2);
        Component note = exporting() ? tr("export.continue_in_background")
                : needsFfmpegAction() ? tr(resolution.aviSupported() ? "export.ffmpeg_fallback_hint" : "export.ffmpeg_bin_hint")
                : resolution.highLoad() ? tr("export.warn_high") : null;
        if (note != null && barY + 24 < top + panelHeight - 42) {
            graphics.drawCenteredString(font, font.plainSubstrByWidth(note.getString(), textW), cx, barY + 24,
                    exporting() ? ReplayPalette.TEXT_FAINT : ReplayPalette.LAMP);
        }
    }

    // ------------------------------------------------------------------ widget

    /** Flat night-blue / brass button matching the table's plaques. */
    private static final class BrassButton extends Button {
        boolean selected;
        boolean accent;

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
