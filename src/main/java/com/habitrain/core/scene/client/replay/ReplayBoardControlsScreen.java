package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.config.ReplayAudioSettings;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Secondary Star-Night Express Art Deco floating control console &amp; scrollable passenger roster
 * for {@link SettlementSummaryBoard}.
 *
 * <p>Mirrors and extends the world-space Side-Edge Deck Console: provides precision timeline
 * scrubbing (with blackout &amp; kill ticks), linear playback speed pills (0.5x / 1x / 2x / 4x),
 * ±10s step, pause/resume, explicit end-to-idle, kill close-up toggle (off by default),
 * roof/fan cutaway toggle, video export entry, and a scrollable passenger roster for selecting
 * over-the-shoulder follow + automatic per-player audibility filtering.</p>
 */
final class ReplayBoardControlsScreen extends Screen {
    private static final float[] SPEEDS = {0.5f, 1.0f, 2.0f, 4.0f};
    private static final int ROW_H = 22;
    private static final long DRAG_SEEK_INTERVAL_NS = 90_000_000L;

    private final ClientLevel level;
    private final BlockPos pos;

    private int left, top, panelWidth, panelHeight;
    private int timelineX0, timelineY0, timelineX1, timelineY1;
    private int rosterX0, rosterY0, rosterX1, rosterY1;

    private DeckButton startPlayButton;
    private DeckButton stepBackButton, playPauseButton, stepForwardButton, endSessionButton;
    private final DeckButton[] speedButtons = new DeckButton[SPEEDS.length];
    private DeckButton closeupButton, roofButton, volumeButton, exportButton, doneButton;
    private final ReplayAudioSettingsOverlay volumeOverlay = new ReplayAudioSettingsOverlay();

    private ClientReplayBoard.View view;
    private @Nullable ReplayBoardSessionState session;
    private @Nullable LastMatchReplay participantsFor;
    private List<LastMatchReplay.Participant> participants = List.of();
    private final Map<UUID, ReplayTrackSampler> samplers = new HashMap<>();
    private int rosterScroll;

    private boolean draggingTimeline;
    private double dragTick;
    private long lastDragSendNs;

    private @Nullable UUID requestedRound;
    private long playDeadline;
    private boolean playTimedOut;

    ReplayBoardControlsScreen(ClientLevel level, BlockPos pos) {
        this(level, pos, false);
    }

    private ReplayBoardControlsScreen(ClientLevel level, BlockPos pos, boolean openVolumeInitially) {
        super(tr("controls.panel_title"));
        this.level = level;
        this.pos = pos.immutable();
        if (openVolumeInitially) {
            this.volumeOverlay.open();
        }
    }

    static ReplayBoardControlsScreen openVolumeSettings(ClientLevel level, BlockPos pos) {
        return new ReplayBoardControlsScreen(level, pos, true);
    }

    private static Component tr(String key, Object... args) {
        return ReplayBoardTexts.tr(key, args);
    }

    @Override protected void init() {
        panelWidth = Math.min(404, width - 16);
        panelHeight = Math.min(346, height - 12);
        left = width > 660 ? width - panelWidth - 16 : (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;

        int pad = 12;
        int innerW = panelWidth - pad * 2;
        timelineX0 = left + pad;
        timelineX1 = left + panelWidth - pad;
        timelineY0 = top + 50;
        timelineY1 = timelineY0 + 18;

        int row1Y = top + 74;
        int gap = 4;

        // Idle mode primary play button (shares row 1 space when idle)
        int startW = innerW - 116 - gap;
        startPlayButton = addRenderableWidget(new DeckButton(left + pad, row1Y, startW, 20,
                tr("controls.play"), b -> startPlayback(), false));

        // Active mode transport + linear speed row
        int backW = 46, playW = 54, fwdW = 46, endW = 48;
        int x = left + pad;
        stepBackButton = addRenderableWidget(new DeckButton(x, row1Y, backW, 20,
                Component.literal("⏮ -10s"), b -> ClientReplayBoard.stepBoard(pos, -200.0), false));
        stepBackButton.setTooltip(Tooltip.create(tr("deck.step_back")));
        x += backW + gap;

        playPauseButton = addRenderableWidget(new DeckButton(x, row1Y, playW, 20,
                Component.empty(), b -> {
            if (isFinishedHold()) {
                ClientReplayBoard.setPaused(pos, false);
            } else {
                ClientReplayBoard.togglePause(pos);
            }
        }, false));
        x += playW + gap;

        stepForwardButton = addRenderableWidget(new DeckButton(x, row1Y, fwdW, 20,
                Component.literal("+10s ⏭"), b -> ClientReplayBoard.stepBoard(pos, 200.0), false));
        stepForwardButton.setTooltip(Tooltip.create(tr("deck.step_forward")));
        x += fwdW + gap;

        endSessionButton = addRenderableWidget(new DeckButton(x, row1Y, endW, 20,
                Component.literal("⏹ ").append(tr("deck.end")), b -> {
            ClientReplayBoard.endBoardSession(pos);
            refresh();
        }, true));
        endSessionButton.setTooltip(Tooltip.create(tr("deck.end_tip")));
        x += endW + gap + 4;

        int speedAreaW = (left + panelWidth - pad) - x;
        int speedW = Math.max(28, (speedAreaW - gap * (SPEEDS.length - 1)) / SPEEDS.length);
        for (int i = 0; i < SPEEDS.length; i++) {
            float sp = SPEEDS[i];
            int sx = x + i * (speedW + gap);
            int sw = i == SPEEDS.length - 1 ? (left + panelWidth - pad) - sx : speedW;
            String label = ReplayBoardTexts.speedLabel(sp);
            DeckButton btn = addRenderableWidget(new DeckButton(sx, row1Y, sw, 20,
                    Component.literal(label), b -> ClientReplayBoard.setBoardSpeed(pos, sp), false));
            btn.setTooltip(Tooltip.create(tr("deck.speed_tip", label)));
            speedButtons[i] = btn;
        }

        // Row 2: view toggles + local volume settings + export
        int row2Y = top + 98;
        int row2Usable = innerW - gap * 3;
        int closeupW = Math.max(50, row2Usable * 20 / 100);
        int roofW = Math.max(50, row2Usable * 20 / 100);
        int volumeW = Math.max(76, row2Usable * 32 / 100);
        int row2X = left + pad;

        closeupButton = addRenderableWidget(new DeckButton(row2X, row2Y, closeupW, 20,
                Component.empty(), b -> {
            if (session != null) ClientReplayBoard.setBoardKillCloseup(pos, !session.killCloseupEnabled());
        }, false));
        closeupButton.setTooltip(Tooltip.create(tr("deck.closeup_tip")));
        row2X += closeupW + gap;

        roofButton = addRenderableWidget(new DeckButton(row2X, row2Y, roofW, 20,
                Component.empty(), b -> {
            if (session != null) ClientReplayBoard.setBoardRoofCutaway(pos, !session.roofCutaway());
        }, false));
        roofButton.setTooltip(Tooltip.create(tr("deck.roof_tip")));
        row2X += roofW + gap;

        volumeButton = addRenderableWidget(new DeckButton(row2X, row2Y, volumeW, 20,
                Component.empty(), b -> {
            volumeOverlay.toggle();
            refresh();
        }, false));
        row2X += volumeW + gap;

        exportButton = addRenderableWidget(new DeckButton(row2X, row2Y, Math.max(54, (left + panelWidth - pad) - row2X), 20,
                tr("controls.open_export"), b -> {
            if (minecraft != null && level != null) {
                minecraft.setScreen(new ReplayExportScreen(level, pos));
            }
        }, false));

        // Roster bounds
        rosterX0 = left + pad;
        rosterX1 = left + panelWidth - pad;
        rosterY0 = top + 136;
        rosterY1 = top + panelHeight - 40;

        // Done button at bottom-right
        int doneW = 92;
        doneButton = addRenderableWidget(new DeckButton(left + panelWidth - pad - doneW, top + panelHeight - 25,
                doneW, 19, CommonComponents.GUI_DONE, b -> onClose(), false));

        refresh();
    }

    private void startPlayback() {
        UUID round = ClientReplayBoard.availableRound();
        if (requestedRound == null && !ReplayPackets.EMPTY.equals(round)
                && ClientReplayBoard.startBoardPlayback(pos, round)) {
            requestedRound = round;
            playDeadline = System.nanoTime() + 5_000_000_000L;
            playTimedOut = false;
            refresh();
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        if (!validTable()) {
            onClose();
            return;
        }
        if (requestedRound != null) {
            var current = ClientReplayBoard.boardSession(pos);
            if (current != null && current.active() && requestedRound.equals(current.roundId())) {
                requestedRound = null;
                playTimedOut = false;
            } else if (System.nanoTime() - playDeadline >= 0) {
                requestedRound = null;
                playTimedOut = true;
            }
        }
        refresh();
    }

    private boolean validTable() {
        if (minecraft == null || minecraft.level != level || level == null || minecraft.player == null
                || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || !(level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) {
            return false;
        }
        double reach = Math.max(8.0, marker.settings().interactReach());
        return minecraft.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= reach * reach;
    }

    private boolean isSessionActive() {
        return session != null && session.active();
    }

    private boolean isFinishedHold() {
        if (!isSessionActive() || view == null || view.replay() == null || level == null) return false;
        return session.isFinishedHold(level.getGameTime(), 0f, view.replay().durationTicks());
    }

    private double currentDisplayTick(float partialTick) {
        if (draggingTimeline) return dragTick;
        if (!isSessionActive() || view == null || view.replay() == null || level == null) return 0.0;
        return session.currentReplayTick(level.getGameTime(), partialTick, view.replay().durationTicks());
    }

    private void refresh() {
        view = ClientReplayBoard.viewForBoard(pos);
        session = ClientReplayBoard.boardSession(pos);
        LastMatchReplay replay = view == null ? null : view.replay();
        if (replay != participantsFor) {
            participantsFor = replay;
            rebuildParticipants(replay);
        }

        boolean active = isSessionActive();
        boolean hasReplay = replay != null;
        boolean canControl = ClientPlayNetworking.canSend(ReplayPackets.BoardSessionControlC2S.TYPE);
        boolean canStart = hasReplay && !active && requestedRound == null
                && ClientPlayNetworking.canSend(ReplayPackets.StartPlayback.TYPE);

        startPlayButton.visible = !active;
        startPlayButton.active = canStart;
        startPlayButton.highlighted = canStart;
        startPlayButton.setMessage(tr(requestedRound != null ? "controls.play_pending" : "controls.play"));

        stepBackButton.visible = active;
        playPauseButton.visible = active;
        stepForwardButton.visible = active;
        endSessionButton.visible = active;
        for (DeckButton b : speedButtons) b.visible = active;

        if (active) {
            boolean pausedOrEnd = session.paused() || isFinishedHold();
            playPauseButton.setMessage(pausedOrEnd
                    ? Component.literal("▶ ").append(tr("deck.play"))
                    : Component.literal("⏸ ").append(tr("deck.pause")));
            playPauseButton.highlighted = !pausedOrEnd;
            playPauseButton.setTooltip(Tooltip.create(tr(pausedOrEnd ? "deck.play" : "deck.pause")));

            stepBackButton.active = canControl && !session.preparing();
            playPauseButton.active = canControl && !session.preparing();
            stepForwardButton.active = canControl && !session.preparing();
            endSessionButton.active = canControl;

            float speed = session.speed();
            for (int i = 0; i < SPEEDS.length; i++) {
                speedButtons[i].active = canControl && !session.preparing();
                speedButtons[i].highlighted = Math.abs(speed - SPEEDS[i]) < 0.05f;
            }
        }

        closeupButton.active = active && canControl && !session.preparing();
        boolean closeupOn = active && session.killCloseupEnabled();
        closeupButton.setMessage(tr(closeupOn ? "deck.closeup_on" : "deck.closeup_off"));
        closeupButton.highlighted = closeupOn;

        roofButton.active = active && canControl && !session.preparing();
        boolean roofOn = !active || session.roofCutaway();
        roofButton.setMessage(tr(roofOn ? "deck.roof_cut" : "deck.roof_full"));
        roofButton.highlighted = active && roofOn;

        ReplayAudioSettings audio = ClientVisualPreferences.getReplayAudioSettings();
        volumeButton.active = true;
        volumeButton.danger = audio.isEffectivelyMuted();
        volumeButton.highlighted = volumeOverlay.isOpen() || (!audio.isEffectivelyMuted() && !audio.isDefault());
        volumeButton.setMessage(ReplayAudioSettingsOverlay.buttonLabel(audio));
        volumeButton.setTooltip(Tooltip.create(ReplayAudioSettingsOverlay.buttonTooltip(audio, false)));

        exportButton.active = hasReplay && (ClientReplayBoard.mapAvailable() || (active && session.shared()));
    }

    private void rebuildParticipants(@Nullable LastMatchReplay replay) {
        samplers.clear();
        rosterScroll = 0;
        if (replay == null || replay.participants().isEmpty()) {
            participants = List.of();
            return;
        }
        List<LastMatchReplay.Participant> sorted = new ArrayList<>(replay.participants());
        sorted.sort(Comparator
                .comparing((LastMatchReplay.Participant p) -> p.faction() == MatchWinFaction.KILLER ? 0
                        : p.faction() == MatchWinFaction.NEUTRAL ? 1 : 2)
                .thenComparingInt(p -> -p.kills())
                .thenComparing(p -> !p.winner())
                .thenComparing(LastMatchReplay.Participant::name, String.CASE_INSENSITIVE_ORDER));
        for (var p : sorted) {
            ReplayHeadSkins.rememberName(p.id(), p.name());
            samplers.put(p.id(), new ReplayTrackSampler(p.track()));
        }
        participants = List.copyOf(sorted);
    }

    // ------------------------------------------------------------------ input handling

    @Override public void removed() {
        volumeOverlay.onRemoved();
        super.removed();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (volumeOverlay.isOpen()) {
            boolean handled = volumeOverlay.mouseClicked(mouseX, mouseY, button);
            refresh();
            return handled;
        }
        if (button == 1 && volumeButton != null && volumeButton.active && volumeButton.isMouseOver(mouseX, mouseY)) {
            ClientVisualPreferences.toggleReplayAudioMute();
            if (minecraft != null) doneButton.playDownSound(minecraft.getSoundManager());
            refresh();
            return true;
        }
        if (button == 0) {
            // 1. Timeline click -> start scrub
            if (isSessionActive() && !session.preparing() && view != null && view.replay() != null
                    && mouseX >= timelineX0 && mouseX <= timelineX1
                    && mouseY >= timelineY0 - 2 && mouseY <= timelineY1 + 2) {
                draggingTimeline = true;
                updateDragSeek(mouseX, true);
                return true;
            }
            // 2. Roster click -> select Global or Follow player
            if (mouseX >= rosterX0 && mouseX <= rosterX1 && mouseY >= rosterY0 && mouseY <= rosterY1) {
                int totalRows = 1 + participants.size();
                int idx = ((int) (mouseY - rosterY0) + rosterScroll) / ROW_H;
                if (idx >= 0 && idx < totalRows && isSessionActive() && !session.preparing()) {
                    UUID nextFollow = idx == 0 ? null : participants.get(idx - 1).id();
                    ClientReplayBoard.setBoardFollow(pos, nextFollow);
                    if (minecraft != null) doneButton.playDownSound(minecraft.getSoundManager());
                    refresh();
                    return true;
                }
            }
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (volumeOverlay.isOpen()) {
            draggingTimeline = false;
            setFocused(null);
        }
        return handled;
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (volumeOverlay.isOpen()) {
            boolean handled = volumeOverlay.mouseDragged(mouseX, mouseY, button);
            refresh();
            return handled;
        }
        if (button == 0 && draggingTimeline) {
            updateDragSeek(mouseX, false);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (volumeOverlay.isOpen()) {
            boolean handled = volumeOverlay.mouseReleased(mouseX, mouseY, button);
            refresh();
            return handled;
        }
        if (button == 0 && draggingTimeline) {
            updateDragSeek(mouseX, true);
            draggingTimeline = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (volumeOverlay.isOpen()) {
            boolean handled = volumeOverlay.mouseScrolled(mouseX, mouseY, scrollY);
            refresh();
            return handled;
        }
        if (mouseX >= rosterX0 && mouseX <= rosterX1 && mouseY >= rosterY0 && mouseY <= rosterY1) {
            int maxScroll = maxRosterScroll();
            if (maxScroll > 0) {
                rosterScroll = Mth.clamp(rosterScroll - (int) Math.round(scrollY * ROW_H), 0, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (volumeOverlay.isOpen() && volumeOverlay.keyPressed(keyCode)) {
            refresh();
            return true;
        }
        if (isSessionActive() && !session.preparing()) {
            if (keyCode == GLFW.GLFW_KEY_SPACE) {
                if (isFinishedHold()) {
                    ClientReplayBoard.setPaused(pos, false);
                } else {
                    ClientReplayBoard.togglePause(pos);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                ClientReplayBoard.stepBoard(pos, -200.0);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                ClientReplayBoard.stepBoard(pos, 200.0);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void updateDragSeek(double mouseX, boolean immediate) {
        if (view == null || view.replay() == null) return;
        int duration = Math.max(1, view.replay().durationTicks());
        double frac = Mth.clamp((mouseX - timelineX0) / Math.max(1.0, timelineX1 - timelineX0), 0.0, 1.0);
        dragTick = frac * duration;
        long now = System.nanoTime();
        if (immediate || now - lastDragSendNs >= DRAG_SEEK_INTERVAL_NS) {
            lastDragSendNs = now;
            ClientReplayBoard.seekBoard(pos, dragTick);
        }
    }

    private int maxRosterScroll() {
        int contentH = (1 + participants.size()) * ROW_H;
        return Math.max(0, contentH - (rosterY1 - rosterY0));
    }

    // ------------------------------------------------------------------ Star-Night Express rendering

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // 1. Star-Night Express midnight panel body + double brass chamfered border
        g.fillGradient(left, top, left + panelWidth, top + panelHeight,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.95),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.96));
        g.renderOutline(left, top, panelWidth, panelHeight, ReplayPalette.BRASS);
        g.renderOutline(left + 2, top + 2, panelWidth - 4, panelHeight - 4,
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.85));
        // 4 brass corner ornaments
        int cs = 6;
        g.fill(left, top, left + cs, top + 2, ReplayPalette.BRASS_LIGHT);
        g.fill(left, top, left + 2, top + cs, ReplayPalette.BRASS_LIGHT);
        g.fill(left + panelWidth - cs, top, left + panelWidth, top + 2, ReplayPalette.BRASS_LIGHT);
        g.fill(left + panelWidth - 2, top, left + panelWidth, top + cs, ReplayPalette.BRASS_LIGHT);
        g.fill(left, top + panelHeight - 2, left + cs, top + panelHeight, ReplayPalette.BRASS_LIGHT);
        g.fill(left, top + panelHeight - cs, left + 2, top + panelHeight, ReplayPalette.BRASS_LIGHT);
        g.fill(left + panelWidth - cs, top + panelHeight - 2, left + panelWidth, top + panelHeight, ReplayPalette.BRASS_LIGHT);
        g.fill(left + panelWidth - 2, top + panelHeight - cs, left + panelWidth, top + panelHeight, ReplayPalette.BRASS_LIGHT);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        boolean modalOpen = volumeOverlay.isOpen();
        if (modalOpen && getFocused() != null) {
            setFocused(null);
        }
        int bgMouseX = modalOpen ? -9999 : mouseX;
        int bgMouseY = modalOpen ? -9999 : mouseY;

        // Render panel background (via renderBackground) + DeckButton widgets first so
        // Screen.renderBackground never draws over custom header/timeline/roster content.
        super.render(g, bgMouseX, bgMouseY, delta);

        // 2. Art Deco header with flanking brass rules
        int centerX = left + panelWidth / 2;
        g.drawCenteredString(font, title, centerX, top + 10, ReplayPalette.CHAMPAGNE);
        int titleW = font.width(title);
        int ruleY = top + 14;
        if (centerX - titleW / 2 - 12 > left + 12) {
            g.fill(left + 12, ruleY, centerX - titleW / 2 - 8, ruleY + 1, ReplayPalette.BRASS_DIM);
            g.fill(centerX + titleW / 2 + 8, ruleY, left + panelWidth - 12, ruleY + 1, ReplayPalette.BRASS_DIM);
        }

        // 3. Round subtitle & live clock / audio POV status
        double tick = currentDisplayTick(delta);
        renderSubtitle(g, tick);

        // 4. Interactive Art Deco timeline scrubber
        renderTimeline(g, bgMouseX, bgMouseY, tick);

        // 5. Passenger roster section header + scrollable list
        renderRoster(g, bgMouseX, bgMouseY, tick);

        // 6. Session-only isolation note at footer
        Component tempNote = tr("controls.session_temp_note");
        int noteMaxW = Math.max(80, panelWidth - 124);
        g.drawString(font, font.plainSubstrByWidth(tempNote.getString(), noteMaxW),
                left + 12, top + panelHeight - 19, ReplayPalette.TEXT_MUTED, false);

        // 7. Client-local volume settings overlay (rendered above all widgets and base text)
        if (modalOpen) {
            volumeOverlay.render(g, font, mouseX, mouseY, left, top, panelWidth, panelHeight, width, height);
        }
    }

    private void renderSubtitle(GuiGraphics g, double tick) {
        int pad = 12;
        int y = top + 28;
        int availW = panelWidth - pad * 2;
        Component leftText;
        int leftColor = ReplayPalette.TEXT;
        if (playTimedOut && !isSessionActive()) {
            leftText = tr("controls.play_timeout");
            leftColor = ReplayPalette.RUBY;
        } else if (view == null || !view.hasRound()) {
            leftText = tr("controls.no_round");
            leftColor = ReplayPalette.TEXT_MUTED;
        } else if (view.replay() == null) {
            leftText = tr("controls.loading_round");
            leftColor = ReplayPalette.BRASS_LIGHT;
        } else if (!isSessionActive()) {
            leftText = tr("controls.not_playing_hint");
            leftColor = ReplayPalette.BRASS_LIGHT;
        } else {
            String map = view.replay().mapName();
            String clk = ReplayStagePlan.clock(tick) + " / " + ReplayStagePlan.clock(view.replay().durationTicks());
            String sp = ReplayBoardTexts.speedLabel(session.speed());
            leftText = Component.literal(map + "  ·  " + clk + "  [" + sp + "]");
            leftColor = ReplayPalette.CHAMPAGNE;
        }

        String rightBadge = "";
        int rightColor = ReplayPalette.BRASS_LIGHT;
        if (isSessionActive() && view != null && view.replay() != null) {
            if (isFinishedHold()) {
                rightBadge = tr("deck.review_frozen").getString();
                rightColor = ReplayPalette.LAMP;
            } else if (session.paused()) {
                rightBadge = tr("deck.paused_badge").getString();
                rightColor = ReplayPalette.LAMP;
            } else if (session.followPlayerUuid() != null) {
                String name = findName(session.followPlayerUuid());
                rightBadge = tr("deck.audio_filtered", name).getString();
                rightColor = ReplayPalette.PASSENGER;
            }
        }
        int rightW = rightBadge.isEmpty() ? 0 : Math.min(availW / 2, font.width(rightBadge) + 8);
        g.drawString(font, font.plainSubstrByWidth(leftText.getString(), Math.max(40, availW - rightW)),
                left + pad, y, leftColor, false);
        if (!rightBadge.isEmpty()) {
            String clipped = font.plainSubstrByWidth(rightBadge, rightW);
            g.drawString(font, clipped, left + panelWidth - pad - font.width(clipped), y, rightColor, false);
        }
        g.fill(left + pad, top + 43, left + panelWidth - pad, top + 44,
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.55));
    }

    private void renderTimeline(GuiGraphics g, int mouseX, int mouseY, double tick) {
        int x0 = timelineX0, y0 = timelineY0, x1 = timelineX1, y1 = timelineY1;
        int w = x1 - x0;
        int midY = (y0 + y1) / 2;

        // Inset rail background
        g.fill(x0, y0, x1, y1, 0xCC070B18);
        g.renderOutline(x0, y0, w, y1 - y0,
                isSessionActive() ? ReplayPalette.BRASS_DIM : ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.45));

        LastMatchReplay replay = view == null ? null : view.replay();
        if (replay == null || replay.durationTicks() <= 0) return;
        int duration = replay.durationTicks();

        // Track groove
        int railY0 = midY - 2, railY1 = midY + 2;
        g.fill(x0 + 4, railY0, x1 - 4, railY1, 0xFF16213B);

        // Blackout spans
        int trackW = Math.max(1, w - 8);
        for (var span : replay.spans()) {
            if (span.kind() != LastMatchReplay.SpanKind.BLACKOUT) continue;
            int sx0 = x0 + 4 + (int) Math.round(Mth.clamp(span.startTick() / (double) duration, 0, 1) * trackW);
            int sx1 = x0 + 4 + (int) Math.round(Mth.clamp(span.endTick() / (double) duration, 0, 1) * trackW);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            g.fill(sx0, y0 + 2, sx1, y1 - 2, 0x88424D9C);
        }

        // Filled progress
        double frac = isSessionActive() ? Mth.clamp(tick / duration, 0.0, 1.0) : 0.0;
        int px = x0 + 4 + (int) Math.round(frac * trackW);
        if (px > x0 + 4) {
            g.fill(x0 + 4, railY0, px, railY1, ReplayPalette.BRASS_LIGHT);
        }

        // Kill markers (ruby diamonds / vertical ticks)
        for (var node : replay.events()) {
            if (node.kind() != LastMatchReplay.Kind.KILL) continue;
            int kx = x0 + 4 + (int) Math.round(Mth.clamp(node.tick() / (double) duration, 0, 1) * trackW);
            g.fill(kx - 1, midY - 5, kx + 2, midY + 5, ReplayPalette.RUBY);
            g.fill(kx, midY - 6, kx + 1, midY + 6, 0xFFFFB4B4);
        }

        // Playhead knob
        if (isSessionActive()) {
            g.fill(px - 3, y0 + 2, px + 4, y1 - 2, ReplayPalette.BRASS);
            g.fill(px - 2, y0 + 3, px + 3, y1 - 3, ReplayPalette.CHAMPAGNE);
        }

        // Hover preview tooltip
        boolean hoverTimeline = isSessionActive() && !session.preparing()
                && mouseX >= x0 && mouseX <= x1 && mouseY >= y0 - 2 && mouseY <= y1 + 2;
        if (hoverTimeline || draggingTimeline) {
            double hoverFrac = Mth.clamp((mouseX - x0 - 4.0) / Math.max(1.0, trackW), 0.0, 1.0);
            double hoverTick = draggingTimeline ? dragTick : hoverFrac * duration;
            String label = ReplayStagePlan.clock(hoverTick);
            int tw = font.width(label) + 8;
            int tx = Mth.clamp(mouseX - tw / 2, x0, x1 - tw);
            int ty = y0 - 13;
            g.fill(tx, ty, tx + tw, ty + 11, 0xEE0B1022);
            g.renderOutline(tx, ty, tw, 11, ReplayPalette.BRASS);
            g.drawCenteredString(font, label, tx + tw / 2, ty + 2, ReplayPalette.CHAMPAGNE);
        }
    }

    private void renderRoster(GuiGraphics g, int mouseX, int mouseY, double tick) {
        g.fill(rosterX0, top + 123, rosterX1, top + 124, ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.55));
        g.drawString(font, tr("controls.roster_title"), rosterX0, top + 126, ReplayPalette.BRASS_LIGHT, false);
        String countText = participants.isEmpty() ? "" : "(" + participants.size() + ")";
        if (!countText.isEmpty()) {
            g.drawString(font, countText, rosterX1 - font.width(countText), top + 126, ReplayPalette.TEXT_MUTED, false);
        }

        // Outer roster frame
        g.fill(rosterX0, rosterY0, rosterX1, rosterY1, 0xAA070B16);
        g.renderOutline(rosterX0, rosterY0, rosterX1 - rosterX0, rosterY1 - rosterY0, ReplayPalette.BRASS_DIM);

        UUID currentFollow = isSessionActive() ? session.followPlayerUuid() : null;
        int totalRows = 1 + participants.size();
        int maxScroll = maxRosterScroll();
        int rowW = (rosterX1 - rosterX0) - (maxScroll > 0 ? 8 : 2);

        g.flush();
        g.enableScissor(rosterX0 + 1, rosterY0 + 1, rosterX1 - 1, rosterY1 - 1);
        try {
            for (int i = 0; i < totalRows; i++) {
                int ry = rosterY0 + 1 + i * ROW_H - rosterScroll;
                if (ry + ROW_H < rosterY0 || ry > rosterY1) continue;
                boolean hovered = mouseX >= rosterX0 + 1 && mouseX <= rosterX0 + rowW
                        && mouseY >= Math.max(rosterY0, ry) && mouseY < Math.min(rosterY1, ry + ROW_H);
                if (i == 0) {
                    boolean selected = isSessionActive() && currentFollow == null;
                    renderGlobalRow(g, rosterX0 + 1, ry, rowW, selected, hovered);
                } else {
                    var p = participants.get(i - 1);
                    boolean selected = isSessionActive() && Objects.equals(currentFollow, p.id());
                    renderParticipantRow(g, rosterX0 + 1, ry, rowW, p, tick, selected, hovered);
                }
            }
        } finally {
            g.flush();
            g.disableScissor();
        }

        // Scrollbar if needed
        if (maxScroll > 0) {
            int barX0 = rosterX1 - 6, barX1 = rosterX1 - 2;
            int barH = rosterY1 - rosterY0 - 4;
            int contentH = totalRows * ROW_H;
            int thumbH = Math.max(16, (int) ((double) barH * barH / contentH));
            int thumbY = rosterY0 + 2 + (int) ((double) (barH - thumbH) * rosterScroll / maxScroll);
            g.fill(barX0, rosterY0 + 2, barX1, rosterY1 - 2, 0x55121B32);
            g.fill(barX0, thumbY, barX1, thumbY + thumbH, ReplayPalette.BRASS);
        }
    }

    private void renderGlobalRow(GuiGraphics g, int x, int y, int w, boolean selected, boolean hovered) {
        int bg = selected ? 0xDD1A2A48 : hovered ? 0xBB142038 : 0x880D1528;
        g.fill(x, y, x + w, y + ROW_H - 1, bg);
        g.fill(x, y, x + 3, y + ROW_H - 1, ReplayPalette.BRASS_LIGHT);
        if (selected) {
            g.renderOutline(x, y, w, ROW_H - 1, ReplayPalette.BRASS_LIGHT);
        }
        Component label = tr("controls.roster_global");
        g.drawString(font, font.plainSubstrByWidth(label.getString(), w - 76),
                x + 8, y + 7, selected ? ReplayPalette.CHAMPAGNE : ReplayPalette.TEXT, false);
        if (selected) {
            String badge = "★ " + tr("deck.global").getString();
            g.drawString(font, badge, x + w - font.width(badge) - 6, y + 7, ReplayPalette.LAMP, false);
        }
    }

    private void renderParticipantRow(GuiGraphics g, int x, int y, int w, LastMatchReplay.Participant p,
                                      double tick, boolean selected, boolean hovered) {
        int bg = selected ? 0xDD1D2D4C : hovered ? 0xBB142038 : 0x770B1222;
        g.fill(x, y, x + w, y + ROW_H - 1, bg);
        int factionCol = p.faction() == MatchWinFaction.KILLER ? ReplayPalette.KILLER
                : p.faction() == MatchWinFaction.NEUTRAL ? ReplayPalette.NEUTRAL : ReplayPalette.PASSENGER;
        g.fill(x, y, x + 3, y + ROW_H - 1, factionCol);
        if (selected) {
            g.renderOutline(x, y, w, ROW_H - 1, ReplayPalette.BRASS_LIGHT);
        }

        // Player 14x14 skin face
        PlayerFaceRenderer.draw(g, ReplayHeadSkins.texture(p.id()), x + 7, y + 4, 14);

        boolean aliveNow = isAliveAt(p.id(), tick);
        int roleCol = ReplayPalette.opaque(p.endColor(), factionCol);
        int nameCol = aliveNow ? (selected ? ReplayPalette.CHAMPAGNE : roleCol) : ReplayPalette.GHOST;

        // Right-aligned badges
        int rightCursor = x + w - 6;
        if (selected) {
            String followTag = tr("controls.roster_following").getString();
            int fw = font.width(followTag) + 6;
            g.fill(rightCursor - fw, y + 4, rightCursor, y + ROW_H - 5, 0xCC3C2B0E);
            g.renderOutline(rightCursor - fw, y + 4, fw, ROW_H - 9, ReplayPalette.BRASS_LIGHT);
            g.drawString(font, followTag, rightCursor - fw + 3, y + 7, ReplayPalette.CHAMPAGNE, false);
            rightCursor -= fw + 5;
        }

        String statusTag = tr(aliveNow ? "controls.roster_alive" : "controls.roster_dead").getString();
        int statusCol = aliveNow ? ReplayPalette.POISON : ReplayPalette.RUBY;
        g.drawString(font, statusTag, rightCursor - font.width(statusTag), y + 7, statusCol, false);
        rightCursor -= font.width(statusTag) + 6;

        if (p.kills() > 0) {
            String killsTag = "⚔" + p.kills();
            g.drawString(font, killsTag, rightCursor - font.width(killsTag), y + 7, ReplayPalette.BRASS_LIGHT, false);
            rightCursor -= font.width(killsTag) + 6;
        }

        // Player name + role on the left
        int textX = x + 25;
        int avail = Math.max(30, rightCursor - textX);
        String roleStr = ReplayBoardTexts.role(p.endRole()).getString();
        String combined = p.name() + " · " + roleStr;
        String shown = font.plainSubstrByWidth(combined, avail);
        g.drawString(font, shown, textX, y + 7, nameCol, false);
        if (!aliveNow) {
            int strikeW = Math.min(avail, font.width(p.name()));
            g.fill(textX, y + 10, textX + strikeW, y + 11, ReplayPalette.alpha(ReplayPalette.RUBY, 0.72));
        }
    }

    private boolean isAliveAt(UUID id, double tick) {
        if (!isSessionActive()) return true;
        ReplayTrackSampler sampler = samplers.get(id);
        if (sampler == null) return true;
        var pose = sampler.sample(tick);
        return pose.state() == ReplayTrackSampler.State.ALIVE;
    }

    private String findName(UUID id) {
        for (var p : participants) {
            if (p.id().equals(id)) return p.name();
        }
        return id.toString().substring(0, 6);
    }

    // ------------------------------------------------------------------ Art Deco button widget

    private static final class DeckButton extends Button {
        boolean danger;
        boolean highlighted;

        DeckButton(int x, int y, int width, int height, Component message, OnPress onPress, boolean danger) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.danger = danger;
        }

        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            int x0 = getX(), y0 = getY(), x1 = x0 + width, y1 = y0 + height;
            boolean hov = active && isHoveredOrFocused();
            int bgTop, bgBot, border, text;
            if (!active) {
                bgTop = 0xAA0C1120;
                bgBot = 0xAA070A14;
                border = 0xFF3E3830;
                text = 0xFF6E7484;
            } else if (danger) {
                bgTop = hov ? 0xEE5C1B26 : 0xDD3C121C;
                bgBot = hov ? 0xEE380E16 : 0xDD240A10;
                border = hov ? ReplayPalette.RUBY : 0xFFAA424F;
                text = hov ? 0xFFFFE5E8 : 0xFFF3BCC2;
            } else if (highlighted) {
                bgTop = hov ? 0xEE382D18 : 0xDD2B2212;
                bgBot = hov ? 0xEE221A0D : 0xDD1A140A;
                border = ReplayPalette.BRASS_LIGHT;
                text = ReplayPalette.CHAMPAGNE;
            } else {
                bgTop = hov ? 0xEE1F2D4B : 0xDD121B32;
                bgBot = hov ? 0xEE141E36 : 0xDD0A1020;
                border = hov ? ReplayPalette.BRASS_LIGHT : ReplayPalette.BRASS_DIM;
                text = hov ? ReplayPalette.CHAMPAGNE : ReplayPalette.TEXT;
            }
            g.fillGradient(x0, y0, x1, y1, bgTop, bgBot);
            g.renderOutline(x0, y0, width, height, border);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String raw = getMessage().getString();
            String fit = font.plainSubstrByWidth(raw, Math.max(8, width - 6));
            g.drawCenteredString(font, fit, x0 + width / 2, y0 + (height - 8) / 2, text);
        }
    }
}
