package com.habitrain.core.scene.client.replay;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.UUID;

final class ReplayBoardControlsScreen extends Screen {
    private static final int PANEL_HEIGHT = 162;
    private final ClientLevel level;
    private final BlockPos pos;
    private ReplayExportResolution resolution;
    private boolean compress;
    private Button play, export, resolutionButton, compressButton;
    private int left, top, panelWidth;
    private ClientReplayBoard.View view;
    private ReplayVideoExport video;
    private boolean folderFailed;
    private UUID requestedRound;
    private long playDeadline;
    private boolean playTimedOut;

    ReplayBoardControlsScreen(ClientLevel level, BlockPos pos) {
        super(tr("title"));
        this.level = level;
        this.pos = pos.immutable();
        this.resolution = ReplayExportResolution.fromId(ClientVisualPreferences.getReplayVideoResolution());
        this.compress = ClientVisualPreferences.isReplayVideoCompress() || !this.resolution.aviSupported();
    }

    private static Component tr(String key, Object... args) {
        return ReplayBoardTexts.tr("controls." + key, args);
    }

    @Override protected void init() {
        panelWidth = Math.min(316, width - 12);
        left = (width - panelWidth) / 2;
        top = (height - PANEL_HEIGHT) / 2;
        int half = (panelWidth - 30) / 2;
        play = addRenderableWidget(Button.builder(tr("play"), button -> {
            var round = ClientReplayBoard.availableRound();
            if (requestedRound == null && !ReplayPackets.EMPTY.equals(round) && ClientPlayNetworking.canSend(ReplayPackets.StartPlayback.TYPE)) {
                requestedRound = round;
                playDeadline = System.nanoTime() + 5_000_000_000L;
                playTimedOut = false;
                ClientPlayNetworking.send(new ReplayPackets.StartPlayback(pos, round));
                refresh();
            }
        }).bounds(left + 12, top + 42, half, 20).build());
        export = addRenderableWidget(Button.builder(tr("export"), button -> {
            var current = ReplayExportClient.current();
            if (current != null && current.running()) {
                current.cancel();
            } else if (needsFfmpegAction()) {
                if (ReplayFfmpegManager.isInstalling()) {
                    ReplayFfmpegManager.cancelInstall();
                } else if (!ReplayFfmpegManager.isImporting()) {
                    folderFailed = false;
                    ReplayFfmpegManager.startInstall(minecraft.gameDirectory.toPath());
                }
            } else {
                folderFailed = false;
                BlockPos board = pos;
                ReplayExportClient.start(ClientReplayBoard.viewForBoard(board), ReplayBoardLocalState.fullBody(),
                        resolution, compress, () -> ClientReplayBoard.viewForBoard(board));
            }
            refresh();
        }).bounds(left + 18 + half, top + 42, half, 20).build());
        resolutionButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            resolution = resolution.next();
            if (!resolution.aviSupported()) compress = true;
            ClientVisualPreferences.setReplayVideoResolution(resolution.id);
            if (!resolution.aviSupported()) ClientVisualPreferences.setReplayVideoCompress(true);
            refresh();
        }).bounds(left + 12, top + 68, half, 20).build());
        compressButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            compress = !compress;
            ClientVisualPreferences.setReplayVideoCompress(compress);
            refresh();
        }).bounds(left + 18 + half, top + 68, half, 20).build());
        addRenderableWidget(Button.builder(tr("folder"), button -> {
            folderFailed = !ReplayVideoExport.openFolder(minecraft);
        }).bounds(left + 12, top + 130, half, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + 18 + half, top + 130, half, 20).build());
        refresh();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && export != null && export.active && export.isMouseOver(mouseX, mouseY) && needsFfmpegAction()) {
            if (!ReplayFfmpegManager.isBusy()) {
                ReplayFfmpegManager.startManualImport(minecraft.gameDirectory.toPath());
                if (minecraft != null) export.playDownSound(minecraft.getSoundManager());
                refresh();
            }
            return true;
        }
        if (button == 1 && resolutionButton != null && resolutionButton.active && resolutionButton.isMouseOver(mouseX, mouseY)) {
            resolution = resolution.previous();
            if (!resolution.aviSupported()) compress = true;
            ClientVisualPreferences.setReplayVideoResolution(resolution.id);
            if (!resolution.aviSupported()) ClientVisualPreferences.setReplayVideoCompress(true);
            if (minecraft != null) resolutionButton.playDownSound(minecraft.getSoundManager());
            refresh();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        if (!validTable()) { onClose(); return; }
        if (requestedRound != null) {
            var playback = ((SettlementSummaryBoard.Marker) level.getBlockEntity(pos)).playback();
            if (playback != null && requestedRound.equals(playback.roundId()) && !playback.shared()
                    && playback.pendingOrPlaying(level.getGameTime())) {
                onClose();
                return;
            }
            if (System.nanoTime() - playDeadline >= 0) {
                requestedRound = null;
                playTimedOut = true;
            }
        }
        refresh();
    }

    private boolean validTable() {
        if (minecraft == null || minecraft.level != level || level == null || minecraft.player == null
                || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || !(level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) return false;
        double reach = marker.settings().interactReach();
        return minecraft.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= reach * reach;
    }

    private boolean playing() {
        if (level == null || !(level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) return false;
        var playback = marker.playback();
        return playback != null
                && playback.pendingOrPlaying(level.getGameTime());
    }

    private boolean ffmpegReady() {
        return minecraft != null && ReplayFfmpegManager.isReady(minecraft.gameDirectory.toPath());
    }

    private boolean needsFfmpegAction() {
        boolean exporting = video != null && video.running();
        return !exporting && compress && !ffmpegReady();
    }

    private void refresh() {
        view = ClientReplayBoard.viewForBoard(pos);
        video = ReplayExportClient.forScreen(video);
        boolean exporting = video != null && video.running();
        boolean playing = playing();
        play.setMessage(tr(requestedRound != null ? "play_pending" : playing ? "playing" : "play"));
        play.active = view.replay() != null && !playing && !exporting && requestedRound == null
                && ClientPlayNetworking.canSend(ReplayPackets.StartPlayback.TYPE);
        boolean needFfmpeg = !exporting && compress && !ffmpegReady();
        boolean installing = ReplayFfmpegManager.isInstalling();
        boolean importing = ReplayFfmpegManager.isImporting();
        if (exporting) {
            export.setMessage(tr("cancel"));
            export.active = true;
            export.setTooltip(null);
        } else if (needFfmpeg) {
            export.setMessage(ReplayBoardTexts.tr(installing ? "export.ffmpeg_cancel" : "export.ffmpeg_install"));
            export.active = installing || !importing;
            export.setTooltip(Tooltip.create(ReplayBoardTexts.tr("export.ffmpeg_controls_tip")));
        } else {
            export.setMessage(tr("export"));
            export.active = ReplayExportClient.canStart() && ReplayExportClient.mapReady(view);
            export.setTooltip(null);
        }

        ReplayExportResolution shownRes = exporting ? video.resolution : resolution;
        boolean shownCompress = exporting ? video.compress : compress;
        resolutionButton.setMessage(tr("resolution", shownRes.label()));
        resolutionButton.setTooltip(Tooltip.create(tr(
                shownRes.highLoad() ? "resolution_tooltip_high" : "resolution_tooltip",
                shownRes.dimensions())));
        resolutionButton.active = !exporting;

        compressButton.setMessage(tr(shownCompress ? "compress_on" : "compress_off"));
        compressButton.setTooltip(Tooltip.create(!resolution.aviSupported()
                ? tr("export.avi_2160") : tr(shownCompress ? "compress_tooltip_on" : "compress_tooltip_off")));
        compressButton.active = !exporting && resolution.aviSupported();
    }

    private Component matchStatus() {
        if (playTimedOut && !playing()) return tr("play_timeout");
        if (view == null || !view.hasRound()) return tr("no_round");
        if (view.replay() == null) return tr("loading_round");
        return tr("round", view.replay().mapName());
    }

    private Component exportStatus() {
        if (folderFailed) return tr("folder_error");
        if (video != null) return video.status();
        if (needsFfmpegAction()) return ReplayFfmpegManager.statusText();
        if (view != null && view.replay() != null && !ClientReplayBoard.mapAvailable()) return tr("map_unavailable");
        if (view != null && view.presentSections() < view.totalSections()) {
            return tr("receiving_map", view.presentSections(), view.totalSections());
        }
        return tr("export_hint", resolution.label(), tr(compress ? "format_mp4" : "format_avi"));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, left + panelWidth, top + PANEL_HEIGHT, 0xF2141D29);
        graphics.renderOutline(left, top, panelWidth, PANEL_HEIGHT, 0xFFD2A458);
        graphics.drawCenteredString(font, title, width / 2, top + 9, 0xFFFFD896);
        String status = matchStatus().getString();
        graphics.drawCenteredString(font, font.plainSubstrByWidth(status, panelWidth - 24), width / 2, top + 25, 0xFFDDDFE7);
        int x = left + 12, barWidth = panelWidth - 24;
        graphics.fill(x, top + 96, x + barWidth, top + 103, 0xFF313F53);
        if (video != null) {
            graphics.fill(x, top + 96, x + (int) (barWidth * video.displayProgress()), top + 103, 0xFF52C5B9);
        } else if (needsFfmpegAction() && ReplayFfmpegManager.isInstalling()) {
            graphics.fill(x, top + 96, x + (int) (barWidth * ReplayFfmpegManager.progress()), top + 103, ReplayPalette.BRASS_LIGHT);
        }
        graphics.drawCenteredString(font, font.plainSubstrByWidth(exportStatus().getString(), panelWidth - 24),
                width / 2, top + 110, 0xFFC5D3E2);
        super.render(graphics, mouseX, mouseY, delta);
    }

}
