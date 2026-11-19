package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.UUID;

import static com.habitrain.core.network.replay.ReplayArchivePackets.*;

public final class ReplayHistoryScreen extends Screen {
    private final Screen parent;
    private final UUID id;
    private final ReplayPreviewTarget target = new ReplayPreviewTarget();
    private Button play, export, share, retry;
    private Scrubber seek;
    private int left, right, top, bottom;
    private ClientReplayBoard.View view;
    private ReplayBoardModel model;
    private BlockPos nearest;
    private double cursor, yaw = .72, pitch = .61, zoom = 1;
    private boolean playing, previewFailed;

    private ReplayHistoryScreen(Screen parent, UUID id) {
        super(text("title"));
        this.parent = parent;
        this.id = id;
    }

    public static Screen open(Screen parent, String matchId, boolean export) {
        UUID id;
        try { id = UUID.fromString(matchId); }
        catch (IllegalArgumentException | NullPointerException invalid) { id = null; }
        return export && id != null ? new ReplayExportScreen(parent, id) : new ReplayHistoryScreen(parent, id);
    }

    static Component text(String key, Object... args) {
        return Component.translatable("screen.habitrain_core.replay_history." + key, args);
    }

    static Component loadStatus(UUID id, ClientReplayBoard.View view) {
        if (id == null) return text("missing");
        if (!ReplayHistoryClient.supported()) return text("unsupported");
        return switch (ReplayHistoryClient.phase(id)) {
            case LOADING -> text("loading");
            case MAP -> view == null ? text("loading") : text("map", view.presentSections(), view.totalSections());
            case READY -> text("ready");
            case MISSING -> text("missing");
            case FAILED -> text("failed");
        };
    }

    @Override protected void init() {
        int w = Math.max(1, Math.min(600, width - 20));
        left = (width - w) / 2;
        right = left + w;
        top = 32;
        bottom = Math.max(top + 8, height - 83);
        seek = addRenderableWidget(new Scrubber(left, bottom + 5, w));
        int y = height - 48;
        int gap = 4;
        int a = (w - gap * 3) / 4;
        play = addRenderableWidget(Button.builder(text("play"), b -> {
            if (cursor >= duration()) cursor = 0;
            playing = !playing;
            refresh();
        }).bounds(left, y, a, 20).build());
        export = addRenderableWidget(Button.builder(text("export"), b ->
                minecraft.setScreen(new ReplayExportScreen(this, id))).bounds(left + a + gap, y, a, 20).build());
        share = addRenderableWidget(Button.builder(text("share"), b -> {
            if (nearest != null) ReplayHistoryClient.share(id, nearest);
        }).bounds(left + (a + gap) * 2, y, a, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(left + (a + gap) * 3, y, w - (a + gap) * 3, 20).build());
        retry = addRenderableWidget(Button.builder(text("retry"), b -> {
            if (view != null && view.asset() != null) ReplayDioramaMesh.retry(view.asset().fingerprint);
            if (ReplayHistoryClient.phase(id) != ReplayHistoryClient.Phase.READY) ReplayHistoryClient.retry(id);
            previewFailed = false;
        }).bounds(width / 2 - 45, (bottom + top) / 2 + 8, 90, 20).build());
        refresh();
    }

    private int duration() { return view == null || view.replay() == null ? 0 : view.replay().durationTicks(); }

    private void refresh() {
        view = id == null ? new ClientReplayBoard.View(null, null, null, 0, 0, false) : ReplayHistoryClient.watch(id);
        boolean ready = id != null && ReplayHistoryClient.phase(id) == ReplayHistoryClient.Phase.READY;
        retry.visible = id != null && (previewFailed || ReplayHistoryClient.phase(id) == ReplayHistoryClient.Phase.FAILED
                || ReplayHistoryClient.phase(id) == ReplayHistoryClient.Phase.MISSING);
        play.active = seek.active = ready && duration() > 0;
        play.setMessage(text(playing ? "pause" : "play"));
        export.active = ready;
        nearest = findBoard();
        int sharing = id == null ? -1 : ReplayHistoryClient.shareStatus(id);
        share.active = ready && nearest != null && sharing != READY;
        share.setTooltip(Tooltip.create(text(nearest == null ? "nearby" : "share_tip")));
        seek.updatePosition();
    }

    private BlockPos findBoard() {
        if (minecraft == null || minecraft.player == null || minecraft.player.isSpectator() || minecraft.level == null) return null;
        BlockPos found = null;
        double closest = 16 * 16;
        for (var pos : ClientReplayBoard.loadedBoards()) {
            if (!(minecraft.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker)) continue;
            double d = minecraft.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            if (d <= closest) { closest = d; found = pos; }
        }
        return found;
    }

    @Override public void tick() {
        boolean audioPlaying = playing;
        if (playing) {
            double end = duration() + (view != null && view.replay() != null && view.replay().lastKill() != null
                    && view.replay().lastKill().tick() >= duration() - 40 ? 50 : 0);
            cursor = Math.min(end, cursor + 1);
            if (cursor >= end) playing = false;
        }
        refresh();
        ReplayAudioPlaybackController.updateHistory(
                minecraft,
                id,
                view == null ? null : view.replay(),
                view == null ? ReplayVoiceData.EMPTY : view.voice(),
                audioPlaying,
                cursor);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() {
        ReplayAudioPlaybackController.stopHistory(minecraft);
        target.close();
        super.removed();
    }

    private boolean inPreview(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && inPreview(x - dx, y - dy) && model != null) {
            yaw = (yaw - dx * .014) % (Math.PI * 2);
            pitch = Math.clamp(pitch - dy * .012, .22, 1.48);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (inPreview(x, y)) {
            zoom = Math.clamp(zoom * Math.pow(1.13, sy), .6, 1.95);
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xED141E30, 0xF7060C16);
        g.fill(left, top, right, bottom, 0xFF080D18);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        var data = view;
        if (data != null && data.replay() != null && (model == null || model.replay != data.replay())) {
            model = new ReplayBoardModel(data.replay(), data.timeline());
        }
        Component status = loadStatus(id, data);
        boolean shown = false;
        if (!previewFailed && model != null && data.asset() != null && (id == null ||
                ReplayHistoryClient.phase(id) != ReplayHistoryClient.Phase.MISSING && ReplayHistoryClient.phase(id) != ReplayHistoryClient.Phase.FAILED)) {
            ReplayDioramaMesh.update(data.asset(), data.presentSections(), data.totalSections());
            if (ReplayDioramaMesh.failed(new ReplayDioramaMesh.Key(data.asset().fingerprint, data.totalSections()))) previewFailed = true;
            var mesh = ReplayDioramaMesh.matches(data.asset()) ? ReplayDioramaMesh.current() : null;
            if (mesh != null && !previewFailed) {
                double at = Math.min(duration(), cursor + (playing ? delta : 0));
                var frame = new ReplayTimeline.Frame(ReplayTimeline.Phase.PLAY, at / Math.max(1, duration()), at, false);
                try {
                    target.draw(g, minecraft, model, frame, ReplayStagePlan.stage(frame), mesh,
                            left, top, right - left, bottom - top, yaw, pitch, zoom);
                    shown = true;
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(ReplayHistoryScreen.class).warn("Could not render archive preview", failure);
                    previewFailed = true;
                }
            } else if (data.presentSections() == data.totalSections()) status = text("building");
        }
        if (previewFailed) status = text("preview_failed");
        g.renderOutline(left, top, right - left, bottom - top, ReplayPalette.BRASS_DIM);
        g.drawCenteredString(font, title, width / 2, 7, ReplayPalette.BRASS_LIGHT);
        if (data != null && data.replay() != null) {
            g.drawCenteredString(font, font.plainSubstrByWidth(data.replay().mapName(), right - left), width / 2, 19, ReplayPalette.TEXT);
        }
        if (!shown) g.drawCenteredString(font, font.plainSubstrByWidth(status.getString(), right - left - 8),
                width / 2, (top + bottom) / 2 - 8, ReplayPalette.TEXT);
        int sharing = id == null ? -1 : ReplayHistoryClient.shareStatus(id);
        var hint = switch (sharing) {
            case READY -> text("sharing");
            case SHARED -> text("shared");
            case NO_TABLE -> text("nearby");
            case TABLE_BUSY -> text("occupied");
            case ERROR, SHARE_ERROR -> text("share_failed");
            default -> shown ? (ReplayHistoryClient.phase(id) == ReplayHistoryClient.Phase.MAP ? status : text("orbit")) : Component.empty();
        };
        g.drawCenteredString(font, font.plainSubstrByWidth(hint.getString(), right - left), width / 2, height - 19, ReplayPalette.TEXT_MUTED);
    }

    private final class Scrubber extends AbstractSliderButton {
        Scrubber(int x, int y, int w) { super(x, y, w, 20, Component.empty(), 0); }
        void updatePosition() {
            value = Math.min(cursor, duration()) / Math.max(1, duration());
            updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Component.literal(ReplayStagePlan.clock(Math.min(cursor, duration())) + " / " + ReplayStagePlan.clock(duration())));
        }
        @Override protected void applyValue() {
            cursor = value * duration();
            ReplayAudioPlaybackController.onHistoryScrub();
        }
    }
}
