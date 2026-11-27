package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.client.replay.MatchReplayClientApi;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.config.ReplayAudioSettings;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static com.habitrain.core.network.replay.ReplayArchivePackets.*;

/**
 * Interactive 3D match replay preview screen opened from the backpack match-record ticket.
 *
 * <p>Feature-aligned with the lobby replay table: independent linear speeds ({@code 0.5x/1x/2x/4x}),
 * pause/resume, {@code ±10s} step, full Art Deco timeline scrubber (blackout spans &amp; kill diamonds),
 * over-the-shoulder follow-player view with audibility filtering, opt-in kill close-up (off by default),
 * roof cutaway toggle, live top-right Star-Night kill feed, tactical skill fallback toasts, hold-at-end
 * review without auto-reset, Seamless Session Handoff to a nearby table, and a live sync banner when
 * within 16 blocks of an active lobby replay table.</p>
 */
public final class ReplayHistoryScreen extends Screen {
    private static final float[] SPEEDS = ReplayBoardSessionState.ALLOWED_SPEEDS;
    private static final double STEP_TICKS = 200.0; // 10 seconds at 20 TPS
    private static final int MAX_LIVE_FEED = 5;
    private static final double FEED_WINDOW_TICKS = 140.0; // 7 seconds of match time
    private static final int ROSTER_ROW_H = 22;

    private final Screen parent;
    private UUID id;
    private final ReplayPreviewTarget target = new ReplayPreviewTarget();

    // Playback & view state (aligned with ReplayBoardSessionState defaults)
    private double cursor = 0.0;
    private float speed = ReplayBoardSessionState.DEFAULT_SPEED;
    private boolean playing = false;
    private boolean finishedHold = false;
    private boolean userExplicitlyPaused = false;
    private boolean pendingAutoSyncAfterShare = false;
    private @Nullable UUID followPlayerUuid = null;
    private boolean killCloseupEnabled = false;
    private boolean roofCutaway = true;
    private boolean tableSyncActive;

    // Orbit camera state
    private double yaw = 0.72, pitch = 0.61, zoom = 1.0;
    private double followYawOffset = 0.0, followPitch = 0.44, followZoom = 1.0;

    // Runtime view / model / table link
    private ClientReplayBoard.View view;
    private ReplayBoardModel model;
    private @Nullable LastMatchReplay participantsFor;
    private List<LastMatchReplay.Participant> participants = List.of();
    private @Nullable BlockPos nearestBoard;
    private @Nullable MatchReplayClientApi.NearbyTableInfo nearbyActiveTable;
    private boolean previewFailed;
    private boolean roundPinned;
    private boolean rosterOpen;
    private int rosterScroll;
    private boolean scrubbingTimeline;
    private long lastRemoteSeekMs;

    // Layout geometry
    private int left, right, top, bottom;
    private int timelineX, timelineY, timelineW, timelineH;
    private int trackX, trackY, trackW, trackH;
    private int bannerX, bannerY, bannerW, bannerH;

    // Widgets
    private BrassPill stepBackBtn, playBtn, stepFwdBtn;
    private final BrassPill[] speedBtns = new BrassPill[SPEEDS.length];
    private BrassPill globalBtn, followPrevBtn, followPlayerBtn, followNextBtn;
    private BrassPill closeupBtn, roofBtn;
    private BrassPill shareBtn, volumeBtn, exportBtn, backBtn, retryBtn;
    private final List<Button> deckWidgets = new ArrayList<>();
    private final ReplayAudioSettingsOverlay volumeOverlay = new ReplayAudioSettingsOverlay();

    // Sync banner clickable pills
    private record BannerPill(String action, int x, int y, int w, int h, Component label, Component tooltip, boolean accent, boolean selected) {
        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
    private List<BannerPill> bannerPills = List.of();

    private ReplayHistoryScreen(Screen parent, @Nullable UUID id, @Nullable UUID initialFocusPlayer, boolean autoSyncTable) {
        super(text("title"));
        this.parent = parent;
        this.id = id;
        this.followPlayerUuid = initialFocusPlayer;
        this.tableSyncActive = autoSyncTable;
    }

    public static Screen open(Screen parent, String matchId, boolean export) {
        return open(parent, matchId, export, null);
    }

    public static Screen open(Screen parent, String matchId, boolean export, @Nullable UUID initialFocusPlayer) {
        UUID parsed = parseUuid(matchId);
        if (export && parsed != null) {
            return new ReplayExportScreen(parent, parsed, initialFocusPlayer);
        }
        return new ReplayHistoryScreen(parent, parsed, initialFocusPlayer, false);
    }

    public static Screen openSynced(Screen parent, String matchId) {
        UUID parsed = parseUuid(matchId);
        return new ReplayHistoryScreen(parent, parsed, null, true);
    }

    private static @Nullable UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw.trim()); }
        catch (IllegalArgumentException invalid) { return null; }
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
        deckWidgets.clear();
        if (!roundPinned && id != null) {
            ReplayHistoryClient.pin(id);
            roundPinned = true;
        }

        int w = Math.max(300, Math.min(720, width - 16));
        left = (width - w) / 2;
        right = left + w;

        bannerX = left;
        bannerY = 25;
        bannerW = w;
        bannerH = 20;

        timelineX = left;
        timelineW = w;
        timelineH = 18;
        timelineY = height - 74;
        updateViewportBounds();

        int row1Y = height - 52;
        int row2Y = height - 30;
        int btnH = 18;
        int gap = w >= 500 ? 3 : 2;
        int groupGap = w >= 500 ? 4 : 2;

        // ---- Row 1: Playback | Speeds | Follow | Toggles ----
        int x = left;
        int stepW = Math.max(w >= 500 ? 28 : 27, Math.max(font.width(text("step_back")), font.width(text("step_forward"))) + 7);
        int playW = Math.max(w >= 500 ? 42 : 34, Math.max(font.width(text("play")), font.width(text("pause"))) + 8);
        stepBackBtn = addDeck(new BrassPill(x, row1Y, stepW, btnH, text("step_back"), b -> stepBy(-STEP_TICKS)));
        x += stepW + gap;
        playBtn = addDeck(new BrassPill(x, row1Y, playW, btnH, text("play"), b -> togglePlayPause()));
        x += playW + gap;
        stepFwdBtn = addDeck(new BrassPill(x, row1Y, stepW, btnH, text("step_forward"), b -> stepBy(STEP_TICKS)));
        x += stepW + gap + groupGap;

        int minSpdW = w >= 520 ? 30 : 20;
        for (int i = 0; i < SPEEDS.length; i++) {
            final float spd = SPEEDS[i];
            String spdLabel = formatSpeed(spd);
            int spdW = Math.max(minSpdW, font.width(spdLabel) + 7);
            speedBtns[i] = addDeck(new BrassPill(x, row1Y, spdW, btnH, Component.literal(spdLabel), b -> setSpeed(spd)));
            speedBtns[i].setTooltip(Tooltip.create(ReplayBoardTexts.tr("deck.speed_tip", spdLabel)));
            x += spdW + 2;
        }
        x += groupGap;

        // Right-aligned toggles on Row 1 sized dynamically to avoid clipping labels
        int toggleTextW = Math.max(
                Math.max(font.width(text("closeup_off")), font.width(text("closeup_on"))),
                Math.max(font.width(text("roof_cut")), font.width(text("roof_full"))));
        int toggleW = w >= 560 ? Math.max(50, toggleTextW + 10) : Math.max(36, toggleTextW + 8);
        int roofX = right - toggleW;
        int closeupX = roofX - gap - toggleW;
        closeupBtn = addDeck(new BrassPill(closeupX, row1Y, toggleW, btnH, text("closeup_off"), b -> toggleKillCloseup()));
        closeupBtn.setTooltip(Tooltip.create(text("closeup_tip")));
        roofBtn = addDeck(new BrassPill(roofX, row1Y, toggleW, btnH, text("roof_cut"), b -> toggleRoofCutaway()));
        roofBtn.setTooltip(Tooltip.create(text("roof_tip")));

        // Follow player strip fills remaining space between speeds and toggles
        int followRight = closeupX - (w >= 500 ? 5 : 3);
        int availFollow = Math.max(96, followRight - x);
        Component globalLabel = text("follow_global");
        Component pickLabel = text("follow_pick");
        int arrowW = w >= 500 ? 14 : 13;
        int desiredGlobalW = Math.max(42, font.width(globalLabel) + 10);
        int desiredMidW = Math.max(42, font.width(pickLabel) + 10);
        int maxGlobalW = Math.max(desiredGlobalW, availFollow - arrowW * 2 - gap * 3 - desiredMidW);
        int globalW = Math.min(desiredGlobalW, Math.max(38, maxGlobalW));
        int midW = Math.max(36, availFollow - globalW - arrowW * 2 - gap * 3);
        globalBtn = addDeck(new BrassPill(x, row1Y, globalW, btnH, globalLabel, b -> setFollowPlayer(null)));
        int fx = x + globalW + gap;
        followPrevBtn = addDeck(new BrassPill(fx, row1Y, arrowW, btnH, Component.literal("‹"), b -> stepFollowPlayer(-1)));
        fx += arrowW + gap;
        followPlayerBtn = addDeck(new BrassPill(fx, row1Y, midW, btnH, pickLabel, b -> {
            if (!participants.isEmpty()) {
                rosterOpen = !rosterOpen;
                if (rosterOpen) volumeOverlay.close();
                rosterScroll = 0;
            }
        }));
        followPlayerBtn.setTooltip(Tooltip.create(text("follow_tip")));
        fx += midW + gap;
        int nextW = Math.max(arrowW, followRight - fx);
        followNextBtn = addDeck(new BrassPill(fx, row1Y, nextW, btnH, Component.literal("›"), b -> stepFollowPlayer(1)));

        // ---- Row 2: Seamless Session Handoff | Local Volume | Export | Back ----
        int actionGap = 4;
        int volumeW = Math.min(118, Math.max(66, (w - actionGap * 3) / 4));
        int remW = Math.max(120, w - volumeW - actionGap * 3);
        int actionW = remW / 3;
        int rx2 = left;
        shareBtn = addDeck(new BrassPill(rx2, row2Y, actionW, btnH, text("share_handoff"), b -> triggerSessionHandoff()));
        rx2 += actionW + actionGap;
        volumeBtn = addDeck(new BrassPill(rx2, row2Y, volumeW, btnH, Component.empty(), b -> {
            rosterOpen = false;
            volumeOverlay.toggle();
            refresh();
        }));
        rx2 += volumeW + actionGap;
        exportBtn = addDeck(new BrassPill(rx2, row2Y, actionW, btnH, text("export"), b -> openExport()));
        exportBtn.setTooltip(Tooltip.create(text("export_tip")));
        rx2 += actionW + actionGap;
        backBtn = addDeck(new BrassPill(rx2, row2Y, Math.max(40, right - rx2), btnH,
                CommonComponents.GUI_BACK, b -> onClose()));

        retryBtn = addRenderableWidget(new BrassPill(width / 2 - 48, (top + bottom) / 2 + 8, 96, 20, text("retry"), b -> {
            if (view != null && view.asset() != null) {
                ReplayDioramaMesh.retry(view.asset().fingerprint);
                if (model != null && model.layout != null && !model.replay.blockDeltas().isEmpty()) {
                    var dw = ReplayDioramaMesh.prepareDynamic(view.asset(), model.layout.origin(), model.replay.blockDeltas());
                    if (dw != null) ReplayDioramaMesh.retry(dw.staticAsset().fingerprint);
                }
            }
            if (id != null && ReplayHistoryClient.phase(id) != ReplayHistoryClient.Phase.READY) ReplayHistoryClient.retry(id);
            previewFailed = false;
        }));

        refresh();
    }

    private <T extends Button> T addDeck(T widget) {
        deckWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private void updateViewportBounds() {
        top = nearbyActiveTable != null ? 49 : 28;
        bottom = Math.max(top + 48, timelineY - 5);
        int clockW = Math.max(36, font.width("00:00") + 10);
        trackX = timelineX + clockW;
        trackW = Math.max(40, timelineW - clockW * 2);
        trackY = timelineY + 4;
        trackH = 10;
    }

    private int duration() {
        return view == null || view.replay() == null ? 0 : view.replay().durationTicks();
    }

    private boolean isSyncedToNearbySameRound() {
        return tableSyncActive && nearbyActiveTable != null && id != null
                && id.toString().equalsIgnoreCase(nearbyActiveTable.roundId());
    }

    // ------------------------------------------------------------------ Actions (Local + Table Sync)

    private void togglePlayPause() {
        if (isSyncedToNearbySameRound()) {
            ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "toggle_pause", 0.0, null, false);
            return;
        }
        int dur = duration();
        if (dur <= 0) return;
        if (!playing && (finishedHold || cursor >= dur - 0.5)) {
            cursor = 0.0;
            finishedHold = false;
            playing = true;
            userExplicitlyPaused = false;
            ReplayAudioPlaybackController.onHistoryScrub();
        } else {
            playing = !playing;
            userExplicitlyPaused = !playing;
        }
        refresh();
    }

    private void stepBy(double deltaTicks) {
        if (isSyncedToNearbySameRound()) {
            ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "step", deltaTicks, null, false);
            return;
        }
        int dur = duration();
        if (dur <= 0) return;
        cursor = Math.clamp(cursor + deltaTicks, 0.0, dur);
        finishedHold = cursor >= dur - 1e-4;
        if (finishedHold) playing = false;
        ReplayAudioPlaybackController.onHistoryScrub();
        refresh();
    }

    private void seekTo(double targetTick, boolean forceRemote) {
        int dur = duration();
        if (dur <= 0) return;
        double clamped = Math.clamp(targetTick, 0.0, dur);
        cursor = clamped;
        finishedHold = clamped >= dur - 1e-4;
        if (finishedHold) playing = false;
        ReplayAudioPlaybackController.onHistoryScrub();
        if (isSyncedToNearbySameRound()) {
            long now = Util.getMillis();
            if (forceRemote || now - lastRemoteSeekMs >= 80L) {
                lastRemoteSeekMs = now;
                ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "seek", clamped, null, false);
            }
        }
    }

    private void setSpeed(float newSpeed) {
        float sanitized = ReplayBoardSessionState.sanitizeSpeed(newSpeed);
        if (isSyncedToNearbySameRound()) {
            ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "speed", sanitized, null, false);
            return;
        }
        speed = sanitized;
        refresh();
    }

    private void setFollowPlayer(@Nullable UUID targetUuid) {
        if (isSyncedToNearbySameRound()) {
            ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "follow", 0.0, targetUuid, false);
            return;
        }
        if (!Objects.equals(followPlayerUuid, targetUuid)) {
            followPlayerUuid = targetUuid;
            followYawOffset = 0.0;
            ReplayAudioPlaybackController.onHistoryScrub();
        }
        rosterOpen = false;
        refresh();
    }

    private void stepFollowPlayer(int delta) {
        if (participants.isEmpty()) return;
        int cur = participantIndex(followPlayerUuid);
        UUID next;
        if (cur < 0) {
            next = delta >= 0 ? participants.getFirst().id() : participants.getLast().id();
        } else {
            int idx = cur + delta;
            next = (idx < 0 || idx >= participants.size()) ? null : participants.get(idx).id();
        }
        setFollowPlayer(next);
    }

    private void toggleKillCloseup() {
        boolean next = !killCloseupEnabled;
        if (isSyncedToNearbySameRound()) {
            ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "set_closeup", 0.0, null, next);
            return;
        }
        killCloseupEnabled = next;
        refresh();
    }

    private void toggleRoofCutaway() {
        boolean next = !roofCutaway;
        if (isSyncedToNearbySameRound()) {
            ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "set_roof", 0.0, null, next);
            return;
        }
        roofCutaway = next;
        refresh();
    }

    private void triggerSessionHandoff() {
        if (id == null || nearestBoard == null) return;
        boolean handoffPaused = !playing && (cursor > 0.5 || userExplicitlyPaused || finishedHold);
        pendingAutoSyncAfterShare = true;
        playing = false;
        ReplayAudioPlaybackController.stopHistory(minecraft);
        ReplayHistoryClient.shareSession(
                id,
                nearestBoard,
                cursor,
                speed,
                handoffPaused,
                followPlayerUuid,
                killCloseupEnabled,
                roofCutaway);
        refresh();
    }

    private void openExport() {
        if (id != null && minecraft != null) {
            minecraft.setScreen(new ReplayExportScreen(this, id, followPlayerUuid));
        }
    }

    private void switchToRound(UUID nextId, boolean syncWithTable) {
        if (nextId == null) return;
        if (roundPinned && id != null && !id.equals(nextId)) {
            ReplayHistoryClient.unpin(id);
            roundPinned = false;
        }
        id = nextId;
        if (!roundPinned) {
            ReplayHistoryClient.pin(id);
            roundPinned = true;
        }
        model = null;
        participantsFor = null;
        participants = List.of();
        cursor = 0.0;
        playing = false;
        finishedHold = false;
        userExplicitlyPaused = false;
        pendingAutoSyncAfterShare = false;
        previewFailed = false;
        rosterOpen = false;
        tableSyncActive = syncWithTable;
        target.resetCamera();
        ReplayAudioPlaybackController.stopHistory(minecraft);
        refresh();
    }

    // ------------------------------------------------------------------ Tick & Refresh

    private void syncParticipants(@Nullable LastMatchReplay replay) {
        if (replay == participantsFor) return;
        participantsFor = replay;
        if (replay == null || replay.participants().isEmpty()) {
            participants = List.of();
            followPlayerUuid = null;
            rosterOpen = false;
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
        if (followPlayerUuid != null && participantIndex(followPlayerUuid) < 0) {
            followPlayerUuid = null;
        }
    }

    private int participantIndex(@Nullable UUID targetUuid) {
        if (targetUuid == null) return -1;
        for (int i = 0; i < participants.size(); i++) {
            if (targetUuid.equals(participants.get(i).id())) return i;
        }
        return -1;
    }

    private void refresh() {
        view = id == null ? new ClientReplayBoard.View(null, null, null, 0, 0, false) : ReplayHistoryClient.watch(id);
        syncParticipants(view == null ? null : view.replay());
        nearbyActiveTable = ReplayHistoryClient.nearestActiveTable(MatchReplayClientApi.DEFAULT_NEARBY_RANGE);
        nearestBoard = ReplayHistoryClient.nearestBoardPos(MatchReplayClientApi.DEFAULT_NEARBY_RANGE);
        if (pendingAutoSyncAfterShare && id != null) {
            int st = ReplayHistoryClient.shareStatus(id);
            if (st == SHARED && nearbyActiveTable != null && id.toString().equalsIgnoreCase(nearbyActiveTable.roundId())) {
                tableSyncActive = true;
                pendingAutoSyncAfterShare = false;
                ReplayAudioPlaybackController.stopHistory(minecraft);
            } else if (st == NO_TABLE || st == TABLE_BUSY || st == ERROR || st == SHARE_ERROR) {
                pendingAutoSyncAfterShare = false;
            }
        }
        updateViewportBounds();

        if (isSyncedToNearbySameRound()) {
            // Mirror live lobby table state so external table adjustments appear immediately
            if (!scrubbingTimeline) {
                cursor = nearbyActiveTable.currentTick();
            }
            speed = ReplayBoardSessionState.sanitizeSpeed(nearbyActiveTable.speed());
            finishedHold = nearbyActiveTable.finishedHold();
            playing = !nearbyActiveTable.paused() && !nearbyActiveTable.preparing() && !finishedHold;
            followPlayerUuid = nearbyActiveTable.followPlayerUuid();
            killCloseupEnabled = nearbyActiveTable.killCloseupEnabled();
            roofCutaway = nearbyActiveTable.roofCutaway();
        } else if (nearbyActiveTable == null) {
            tableSyncActive = false;
        }

        boolean ready = id != null && ReplayHistoryClient.phase(id) == ReplayHistoryClient.Phase.READY;
        boolean hasDur = ready && duration() > 0;
        boolean showRetry = id != null && (previewFailed
                || ReplayHistoryClient.phase(id) == ReplayHistoryClient.Phase.FAILED
                || ReplayHistoryClient.phase(id) == ReplayHistoryClient.Phase.MISSING);
        if (retryBtn != null) {
            retryBtn.visible = showRetry;
            retryBtn.setY((top + bottom) / 2 + 8);
        }

        if (playBtn == null) return;
        stepBackBtn.active = hasDur;
        stepFwdBtn.active = hasDur;
        playBtn.active = hasDur;
        playBtn.setMessage(Component.literal(playing ? "⏸ " : "▶ ").append(text(playing ? "pause" : "play")));
        playBtn.selected = playing;

        for (int i = 0; i < SPEEDS.length; i++) {
            speedBtns[i].active = hasDur;
            speedBtns[i].selected = Math.abs(speed - SPEEDS[i]) < 0.05f;
        }

        boolean hasPlayers = !participants.isEmpty();
        int idx = participantIndex(followPlayerUuid);
        globalBtn.active = hasDur;
        globalBtn.selected = idx < 0;
        followPrevBtn.active = hasDur && hasPlayers;
        followNextBtn.active = hasDur && hasPlayers;
        followPlayerBtn.active = hasDur && hasPlayers;
        followPlayerBtn.selected = idx >= 0;
        followPlayerBtn.focusTint = idx >= 0;
        if (idx >= 0) {
            String name = ReplayBoardTexts.name(participants.get(idx)).getString();
            followPlayerBtn.setMessage(text("follow_prefix", name));
        } else {
            followPlayerBtn.setMessage(text("follow_pick"));
        }

        closeupBtn.active = hasDur;
        closeupBtn.selected = killCloseupEnabled;
        closeupBtn.setMessage(text(killCloseupEnabled ? "closeup_on" : "closeup_off"));

        roofBtn.active = hasDur;
        roofBtn.selected = roofCutaway;
        roofBtn.setMessage(text(roofCutaway ? "roof_cut" : "roof_full"));

        exportBtn.active = ready;
        ReplayAudioSettings audio = ClientVisualPreferences.getReplayAudioSettings();
        volumeBtn.active = true;
        volumeBtn.accent = audio.isEffectivelyMuted();
        volumeBtn.selected = volumeOverlay.isOpen() || (!audio.isEffectivelyMuted() && !audio.isDefault());
        volumeBtn.setMessage(ReplayAudioSettingsOverlay.buttonLabel(audio));
        volumeBtn.setTooltip(Tooltip.create(ReplayAudioSettingsOverlay.buttonTooltip(audio, false)));
        int sharingStatus = id == null ? -1 : ReplayHistoryClient.shareStatus(id);
        shareBtn.active = ready && nearestBoard != null && sharingStatus != READY;
        String viewName = idx >= 0
                ? ReplayBoardTexts.name(participants.get(idx)).getString()
                : text("follow_global").getString();
        shareBtn.setTooltip(Tooltip.create(nearestBoard == null
                ? text("nearby")
                : text("share_handoff_tip", ReplayStagePlan.clock(cursor), formatSpeed(speed), viewName)));

        rebuildBannerPills();
    }

    private void rebuildBannerPills() {
        if (nearbyActiveTable == null) {
            bannerPills = List.of();
            return;
        }
        boolean sameRound = id != null && id.toString().equalsIgnoreCase(nearbyActiveTable.roundId());
        List<BannerPill> list = new ArrayList<>();
        int rx = bannerX + bannerW - 4;
        int py = bannerY + 3;
        int ph = bannerH - 6;

        // Rightmost: End Table
        rx = pushBannerPill(list, "end", rx, py, ph, text("table_end"), text("table_end_tip"), true, false);
        // Export Table Video
        rx = pushBannerPill(list, "export_table", rx, py, ph, text("table_export"), text("export_tip"), false, false);
        // Play/Pause quick button
        Component ppLabel = Component.literal(nearbyActiveTable.paused() || nearbyActiveTable.finishedHold() ? "▶" : "⏸");
        rx = pushBannerPill(list, "toggle_pause", rx, py, ph, ppLabel,
                ReplayBoardTexts.tr(nearbyActiveTable.paused() ? "deck.play" : "deck.pause"), false, !nearbyActiveTable.paused());
        // Sync toggle (same round) or Switch to table round (different round)
        if (sameRound) {
            pushBannerPill(list, "toggle_sync", rx, py, ph,
                    text(tableSyncActive ? "table_sync_on" : "table_sync_off"),
                    text("table_sync_tip"), false, tableSyncActive);
        } else {
            pushBannerPill(list, "switch_table", rx, py, ph,
                    text("table_switch"), text("table_sync_tip"), false, true);
        }
        bannerPills = list;
    }

    private int pushBannerPill(List<BannerPill> out, String action, int rightEdge, int y, int h,
                               Component label, Component tip, boolean accent, boolean selected) {
        int pw = Math.max(18, font.width(label) + 8);
        int x = rightEdge - pw;
        out.add(new BannerPill(action, x, y, pw, h, label, tip, accent, selected));
        return x - 3;
    }

    @Override public void tick() {
        boolean synced = isSyncedToNearbySameRound();
        boolean audioPlaying = playing && !synced && !scrubbingTimeline;
        if (playing && !synced && !scrubbingTimeline) {
            int dur = duration();
            if (dur > 0) {
                cursor = Math.min(dur, cursor + speed);
                if (cursor >= dur) {
                    cursor = dur;
                    playing = false;
                    finishedHold = true;
                }
            }
        }
        refresh();
        // Mute local history audio while live-synced with the nearby in-world table to prevent double echo.
        ReplayAudioPlaybackController.updateHistory(
                minecraft,
                id,
                view == null ? null : view.replay(),
                view == null ? ReplayVoiceData.EMPTY : view.voice(),
                audioPlaying,
                cursor,
                speed,
                followPlayerUuid);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public void removed() {
        volumeOverlay.onRemoved();
        if (roundPinned && id != null) {
            ReplayHistoryClient.unpin(id);
            roundPinned = false;
        }
        ReplayAudioPlaybackController.stopHistory(minecraft);
        target.close();
        super.removed();
    }

    // ------------------------------------------------------------------ Input Handling

    private boolean inPreview(double x, double y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    private boolean inTimelineTrack(double x, double y) {
        return x >= trackX - 3 && x <= trackX + trackW + 3
                && y >= timelineY && y <= timelineY + timelineH;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (volumeOverlay.isOpen() && volumeOverlay.keyPressed(keyCode)) {
            refresh();
            return true;
        }
        if (rosterOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                rosterOpen = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                rosterScroll = Math.max(0, rosterScroll - ROSTER_ROW_H);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                rosterScroll = Math.min(maxRosterScroll(), rosterScroll + ROSTER_ROW_H);
                return true;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            togglePlayPause();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            stepBy(-STEP_TICKS);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            stepBy(STEP_TICKS);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET) {
            stepFollowPlayer(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) {
            stepFollowPlayer(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_1) { setSpeed(0.5f); return true; }
        if (keyCode == GLFW.GLFW_KEY_2) { setSpeed(1.0f); return true; }
        if (keyCode == GLFW.GLFW_KEY_3) { setSpeed(2.0f); return true; }
        if (keyCode == GLFW.GLFW_KEY_4) { setSpeed(4.0f); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (volumeOverlay.isOpen()) {
            boolean handled = volumeOverlay.mouseClicked(x, y, button);
            refresh();
            return handled;
        }
        if (rosterOpen) {
            return handleRosterClick(x, y, button);
        }
        if (button == 1 && volumeBtn != null && volumeBtn.active && volumeBtn.isMouseOver(x, y)) {
            ClientVisualPreferences.toggleReplayAudioMute();
            playUiClick();
            refresh();
            return true;
        }
        if (nearbyActiveTable != null && button == 0) {
            for (BannerPill pill : bannerPills) {
                if (pill.hit(x, y)) {
                    handleBannerPill(pill.action());
                    playUiClick();
                    return true;
                }
            }
        }
        if (button == 1 && followPlayerBtn != null && followPlayerBtn.active && followPlayerBtn.isMouseOver(x, y)) {
            setFollowPlayer(null);
            playUiClick();
            return true;
        }
        if (button == 0 && duration() > 0 && inTimelineTrack(x, y)) {
            scrubbingTimeline = true;
            double frac = Math.clamp((x - trackX) / (double) Math.max(1, trackW), 0.0, 1.0);
            seekTo(frac * duration(), true);
            playUiClick();
            return true;
        }
        boolean handled = super.mouseClicked(x, y, button);
        if (rosterOpen || volumeOverlay.isOpen()) {
            setFocused(null);
        }
        return handled;
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (volumeOverlay.isOpen()) {
            boolean handled = volumeOverlay.mouseDragged(x, y, button);
            refresh();
            return handled;
        }
        if (rosterOpen) return true;
        if (scrubbingTimeline && button == 0 && duration() > 0) {
            double frac = Math.clamp((x - trackX) / (double) Math.max(1, trackW), 0.0, 1.0);
            seekTo(frac * duration(), false);
            return true;
        }
        if (button == 0 && inPreview(x - dx, y - dy) && model != null) {
            if (followPlayerUuid != null) {
                followYawOffset = (followYawOffset - dx * 0.014) % (Math.PI * 2);
                followPitch = Math.clamp(followPitch - dy * 0.012, 0.16, 1.32);
            } else {
                yaw = (yaw - dx * 0.014) % (Math.PI * 2);
                pitch = Math.clamp(pitch - dy * 0.012, 0.22, 1.48);
            }
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (volumeOverlay.isOpen()) {
            boolean handled = volumeOverlay.mouseReleased(x, y, button);
            refresh();
            return handled;
        }
        if (scrubbingTimeline) {
            if (duration() > 0) {
                double frac = Math.clamp((x - trackX) / (double) Math.max(1, trackW), 0.0, 1.0);
                seekTo(frac * duration(), true);
            }
            scrubbingTimeline = false;
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (volumeOverlay.isOpen()) {
            boolean handled = volumeOverlay.mouseScrolled(x, y, sy);
            refresh();
            return handled;
        }
        if (rosterOpen) {
            if (sy != 0) {
                rosterScroll = Math.clamp(rosterScroll - (int) Math.signum(sy) * ROSTER_ROW_H, 0, maxRosterScroll());
            }
            return true;
        }
        if (inPreview(x, y)) {
            if (followPlayerUuid != null) {
                followZoom = Math.clamp(followZoom * Math.pow(1.13, sy), 0.5, 2.2);
            } else {
                zoom = Math.clamp(zoom * Math.pow(1.13, sy), 0.6, 2.1);
            }
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    private void handleBannerPill(String action) {
        if (nearbyActiveTable == null) return;
        switch (action) {
            case "toggle_sync" -> {
                tableSyncActive = !tableSyncActive;
                if (tableSyncActive) ReplayAudioPlaybackController.stopHistory(minecraft);
                refresh();
            }
            case "switch_table" -> {
                UUID tableRound = parseUuid(nearbyActiveTable.roundId());
                if (tableRound != null) switchToRound(tableRound, true);
            }
            case "toggle_pause" -> ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "toggle_pause", 0.0, null, false);
            case "export_table" -> {
                UUID tableRound = parseUuid(nearbyActiveTable.roundId());
                if (tableRound != null && minecraft != null) {
                    minecraft.setScreen(new ReplayExportScreen(this, tableRound, nearbyActiveTable.followPlayerUuid()));
                }
            }
            case "end" -> {
                ReplayHistoryClient.controlTable(nearbyActiveTable.pos(), "end", 0.0, null, false);
                tableSyncActive = false;
                refresh();
            }
        }
    }

    // ------------------------------------------------------------------ Rendering

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xF010192D, 0xFA04070F);
        g.fill(left, top, right, bottom, 0xFF060B16);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        boolean modalOpen = rosterOpen || volumeOverlay.isOpen();
        if (modalOpen && getFocused() != null) {
            setFocused(null);
        }
        int bgMx = modalOpen ? -9999 : mx;
        int bgMy = modalOpen ? -9999 : my;
        super.render(g, bgMx, bgMy, delta);
        var data = view;
        if (data != null && data.replay() != null && (model == null || model.replay != data.replay())) {
            model = new ReplayBoardModel(data.replay(), data.timeline());
            ReplayHeadSkins.prefetch(model);
        }

        // Header with Art Deco brass wings
        renderHeader(g, data);

        // Nearby active lobby replay table sync banner (<= 16 blocks)
        Component bannerTooltip = renderNearbyTableBanner(g, bgMx, bgMy);

        // 3D Diorama Preview FBO
        Component status = loadStatus(id, data);
        boolean shown = false;
        double renderTick = duration() <= 0 ? 0.0
                : Math.min(duration(), cursor + (playing && !scrubbingTimeline && !isSyncedToNearbySameRound() ? delta * speed : 0.0));
        if (!previewFailed && model != null && data.asset() != null && (id == null ||
                ReplayHistoryClient.phase(id) != ReplayHistoryClient.Phase.MISSING
                        && ReplayHistoryClient.phase(id) != ReplayHistoryClient.Phase.FAILED)) {
            SceneAssetCodec.AssetData meshAsset = data.asset();
            ReplayDioramaMesh.DynamicWorld dynamicWorld = null;
            if (model.layout != null && !model.replay.blockDeltas().isEmpty()) {
                dynamicWorld = ReplayDioramaMesh.prepareDynamic(
                        data.asset(), model.layout.origin(), model.replay.blockDeltas());
                if (dynamicWorld != null) {
                    meshAsset = dynamicWorld.staticAsset();
                }
            }
            ReplayDioramaMesh.update(meshAsset, data.presentSections(), data.totalSections());
            if (ReplayDioramaMesh.failed(new ReplayDioramaMesh.Key(meshAsset.fingerprint, data.presentSections()))) {
                previewFailed = true;
            }
            var mesh = ReplayDioramaMesh.matches(meshAsset) ? ReplayDioramaMesh.current() : null;
            if (mesh != null && !previewFailed) {
                int dur = Math.max(1, duration());
                var frame = new ReplayTimeline.Frame(
                        finishedHold ? ReplayTimeline.Phase.REVIEW : ReplayTimeline.Phase.PLAY,
                        renderTick / dur, renderTick, false);
                var stage = ReplayStagePlan.linearStage(renderTick, dur, 1.0, finishedHold ? 1.0 : 0.0, 0.0);
                try {
                    double camYaw = followPlayerUuid != null ? followYawOffset : yaw;
                    double camPitch = followPlayerUuid != null ? followPitch : pitch;
                    double camZoom = followPlayerUuid != null ? followZoom : zoom;
                    target.draw(g, minecraft, model, frame, stage, mesh, dynamicWorld,
                            left, top, right - left, bottom - top,
                            camYaw, camPitch, camZoom,
                            followPlayerUuid, killCloseupEnabled, roofCutaway);
                    shown = true;
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(ReplayHistoryScreen.class).warn("Could not render archive preview", failure);
                    previewFailed = true;
                }
            } else if (data.presentSections() == data.totalSections()) {
                status = text("building");
            }
        }
        if (previewFailed) status = text("preview_failed");

        // Viewport Art Deco frame & overlays
        g.renderOutline(left, top, right - left, bottom - top, ReplayPalette.BRASS);
        g.renderOutline(left + 2, top + 2, right - left - 4, bottom - top - 4, 0x558A6A35);
        drawCornerAccent(g, left + 1, top + 1, 1, 1);
        drawCornerAccent(g, right - 2, top + 1, -1, 1);
        drawCornerAccent(g, left + 1, bottom - 2, 1, -1);
        drawCornerAccent(g, right - 2, bottom - 2, -1, -1);

        if (shown && model != null) {
            renderViewportStatusHud(g, renderTick);
            int feedBottomY = renderLiveKillFeed(g, renderTick);
            if (!modalOpen && bottom - feedBottomY > 40) {
                ReplaySkillFallbackHud.renderInGui(g, minecraft, model, renderTick,
                        left + 4, feedBottomY + 2, right - left - 8, bottom - feedBottomY - 6, 1.0f);
            }
        } else {
            g.drawCenteredString(font, font.plainSubstrByWidth(status.getString(), right - left - 12),
                    width / 2, (top + bottom) / 2 - 8, ReplayPalette.TEXT);
        }

        // Interactive Art Deco timeline scrubber
        Component timelineTooltip = renderTimeline(g, bgMx, bgMy, renderTick);

        // Footer hint / share feedback
        int sharing = id == null ? -1 : ReplayHistoryClient.shareStatus(id);
        Component hint = switch (sharing) {
            case READY -> text("sharing");
            case SHARED -> text("shared");
            case NO_TABLE -> text("nearby");
            case TABLE_BUSY -> text("occupied");
            case ERROR, SHARE_ERROR -> text("share_failed");
            default -> shown
                    ? (ReplayHistoryClient.phase(id) == ReplayHistoryClient.Phase.MAP
                        ? status
                        : (followPlayerUuid != null ? text("follow_orbit") : text("orbit_v2")))
                    : Component.empty();
        };
        int hintColor = sharing == SHARED ? 0xFF6DCEC0 : (sharing == ERROR || sharing == SHARE_ERROR ? ReplayPalette.RUBY : ReplayPalette.TEXT_MUTED);
        g.drawCenteredString(font, font.plainSubstrByWidth(hint.getString(), right - left), width / 2, height - 10, hintColor);

        // Modal overlays (drawn on top of viewport)
        if (volumeOverlay.isOpen()) {
            volumeOverlay.render(g, font, mx, my, left, top, right - left, bottom - top, width, height);
        } else if (rosterOpen) {
            renderRosterOverlay(g, mx, my, renderTick);
        } else if (bannerTooltip != null) {
            g.renderTooltip(font, bannerTooltip, mx, my);
        } else if (timelineTooltip != null) {
            g.renderTooltip(font, timelineTooltip, mx, my);
        }
    }

    private void renderHeader(GuiGraphics g, @Nullable ClientReplayBoard.View data) {
        String titleStr = title.getString();
        if (data != null && data.replay() != null && !data.replay().mapName().isBlank()) {
            titleStr = titleStr + " · " + data.replay().mapName();
        }
        String clipped = font.plainSubstrByWidth(titleStr, Math.max(80, right - left - 60));
        int cx = width / 2;
        int tw = font.width(clipped);
        int ruleY = 12;
        int lx1 = cx - tw / 2 - 12;
        int rx0 = cx + tw / 2 + 12;
        if (lx1 > left + 8) {
            g.fill(left, ruleY, lx1, ruleY + 1, ReplayPalette.BRASS_DIM);
            drawDiamond(g, lx1 + 4, ruleY, ReplayPalette.BRASS_LIGHT);
        }
        if (right > rx0 + 8) {
            g.fill(rx0, ruleY, right, ruleY + 1, ReplayPalette.BRASS_DIM);
            drawDiamond(g, rx0 - 4, ruleY, ReplayPalette.BRASS_LIGHT);
        }
        g.drawCenteredString(font, clipped, cx, 8, ReplayPalette.BRASS_LIGHT);
    }

    private @Nullable Component renderNearbyTableBanner(GuiGraphics g, int mx, int my) {
        if (nearbyActiveTable == null) return null;
        g.fillGradient(bannerX, bannerY, bannerX + bannerW, bannerY + bannerH, 0xF0152240, 0xF4080E1E);
        g.renderOutline(bannerX, bannerY, bannerW, bannerH, ReplayPalette.BRASS);
        g.fill(bannerX + 1, bannerY + 1, bannerX + bannerW - 1, bannerY + 2, 0x33F4D894);

        double pulse = 0.65 + 0.35 * Math.sin(Util.getMillis() / 300.0);
        int dotColor = ReplayPalette.alpha(nearbyActiveTable.paused() ? ReplayPalette.LAMP : 0xFF6DCEC0, pulse);
        g.fill(bannerX + 6, bannerY + 7, bannerX + 11, bannerY + 12, dotColor);

        int pillsLeft = bannerX + bannerW - 6;
        for (BannerPill pill : bannerPills) {
            pillsLeft = Math.min(pillsLeft, pill.x());
        }

        String map = nearbyActiveTable.mapName().isEmpty()
                ? nearbyActiveTable.roundId().substring(0, Math.min(8, nearbyActiveTable.roundId().length()))
                : nearbyActiveTable.mapName();
        String clock = nearbyActiveTable.durationTicks() > 0
                ? ReplayStagePlan.clock(nearbyActiveTable.currentTick()) + "/" + ReplayStagePlan.clock(nearbyActiveTable.durationTicks())
                : "--:--";
        String info = text("table_linked").getString() + " · " + map + "  " + clock + "  " + formatSpeed(nearbyActiveTable.speed());
        int textX = bannerX + 15;
        int availW = Math.max(30, pillsLeft - textX - 6);
        g.drawString(font, font.plainSubstrByWidth(info, availW), textX, bannerY + 6, ReplayPalette.CHAMPAGNE, false);

        Component tip = null;
        for (BannerPill pill : bannerPills) {
            boolean hovered = !rosterOpen && pill.hit(mx, my);
            int border = hovered ? ReplayPalette.BRASS_LIGHT
                    : (pill.accent() ? ReplayPalette.RUBY : (pill.selected() ? ReplayPalette.BRASS : ReplayPalette.BRASS_DIM));
            int topBg = pill.accent() ? (hovered ? 0xFF5A1D26 : 0xFF3E141C)
                    : (pill.selected() ? (hovered ? 0xFF47371A : 0xFF362912) : (hovered ? 0xFF283A63 : 0xFF15203C));
            int botBg = pill.accent() ? (hovered ? 0xFF3A1018 : 0xFF260B10)
                    : (pill.selected() ? (hovered ? 0xFF2A200E : 0xFF20180A) : (hovered ? 0xFF182545 : 0xFF0C1326));
            g.fill(pill.x(), pill.y(), pill.x() + pill.w(), pill.y() + pill.h(), border);
            g.fillGradient(pill.x() + 1, pill.y() + 1, pill.x() + pill.w() - 1, pill.y() + pill.h() - 1, topBg, botBg);
            int tc = hovered || pill.selected() ? ReplayPalette.CHAMPAGNE : (pill.accent() ? 0xFFFFB8BC : ReplayPalette.TEXT);
            g.drawCenteredString(font, pill.label(), pill.x() + pill.w() / 2, pill.y() + (pill.h() - font.lineHeight) / 2, tc);
            if (hovered) tip = pill.tooltip();
        }
        return tip;
    }

    private void renderViewportStatusHud(GuiGraphics g, double renderTick) {
        int hx = left + 8;
        int hy = top + 8;
        boolean blink = playing && (Util.getMillis() % 1000L) < 600L;
        int dotCol = finishedHold ? ReplayPalette.BRASS_LIGHT : (playing ? (blink ? ReplayPalette.RUBY : 0x88FF5A64) : ReplayPalette.LAMP);
        String clockStr = ReplayStagePlan.clock(renderTick) + "  " + formatSpeed(speed);
        String stateSuffix = finishedHold ? " · " + text("finished_hold").getString()
                : (!playing ? " · " + text("paused_badge").getString() : "");
        String chipText = clockStr + stateSuffix;
        int chipW = font.width(chipText) + 20;
        g.fill(hx, hy, hx + chipW, hy + 15, 0xC8080E1E);
        g.renderOutline(hx, hy, chipW, 15, finishedHold ? ReplayPalette.BRASS_LIGHT : ReplayPalette.BRASS_DIM);
        g.fill(hx + 5, hy + 5, hx + 10, hy + 10, dotCol);
        g.drawString(font, chipText, hx + 14, hy + 4, ReplayPalette.CHAMPAGNE, false);

        // Audio POV & voice stream pill
        int ay = hy + 18;
        Component audioText;
        int audioBorder = ReplayPalette.BRASS_DIM;
        if (isSyncedToNearbySameRound()) {
            audioText = text("audio_table_muted");
            audioBorder = 0xFF6DCEC0;
        } else if (followPlayerUuid != null) {
            ReplayBoardModel.Actor actor = model.participant(followPlayerUuid);
            String name = actor == null ? "?" : actor.name.getString();
            audioText = text("audio_pov", name);
            audioBorder = ReplayPalette.FOCUS_TRAIL;
        } else {
            audioText = text("audio_global");
        }
        String audioLine = audioText.getString();
        if (ClientVisualPreferences.getReplayAudioSettings().isEffectivelyMuted()) {
            audioLine = audioLine + " · " + ReplayBoardTexts.tr("audio.badge_muted").getString();
            audioBorder = ReplayPalette.RUBY;
        }
        float vp = id == null ? -1f : ReplayHistoryClient.voiceProgress(id);
        if (vp >= 0f && vp < 0.999f) {
            audioLine = audioLine + " · " + text("voice_streaming", Math.round(vp * 100f)).getString();
        }
        int aw = Math.min(right - left - 16, font.width(audioLine) + (followPlayerUuid != null ? 24 : 12));
        g.fill(hx, ay, hx + aw, ay + 15, 0xBA080E1E);
        g.renderOutline(hx, ay, aw, 15, audioBorder);
        int textStart = hx + 6;
        if (followPlayerUuid != null) {
            PlayerFaceRenderer.draw(g, ReplayHeadSkins.texture(followPlayerUuid), hx + 3, ay + 2, 11);
            textStart = hx + 17;
        }
        g.drawString(font, font.plainSubstrByWidth(audioLine, Math.max(16, aw - (textStart - hx) - 4)),
                textStart, ay + 4, ReplayPalette.TEXT, false);
    }

    /** Renders the top-right Star-Night Art Deco live kill & event feed inside the 3D viewport. Returns bottom Y. */
    private int renderLiveKillFeed(GuiGraphics g, double renderTick) {
        List<LastMatchReplay.Node> recent = new ArrayList<>();
        for (LastMatchReplay.Node ev : model.replay.events()) {
            if (ev.tick() > renderTick) break;
            if (renderTick - ev.tick() > FEED_WINDOW_TICKS) continue;
            if (isFeedEvent(ev.kind())) recent.add(ev);
        }
        if (recent.isEmpty()) return top + 6;

        int from = Math.max(0, recent.size() - MAX_LIVE_FEED);
        int rowH = 18;
        int gap = 3;
        int y = top + 8;
        int maxW = Math.min(248, (right - left) / 2);

        for (int i = recent.size() - 1; i >= from; i--) {
            LastMatchReplay.Node ev = recent.get(i);
            double age = renderTick - ev.tick();
            double in = ReplayStagePlan.easeOutCubic(Math.min(1.0, age / 6.0));
            double out = 1.0 - ReplayStagePlan.smoothstep(FEED_WINDOW_TICKS - 24.0, FEED_WINDOW_TICKS, age);
            double alpha = Math.clamp(in * out, 0.0, 1.0);
            if (alpha <= 0.04) continue;

            int accent = feedAccent(ev);
            int slide = (int) Math.round((1.0 - in) * 28.0);
            int x1 = right - 8 + slide;

            if (ev.kind() == LastMatchReplay.Kind.KILL) {
                ReplayBoardModel.Actor killer = model.participant(ev.actor());
                ReplayBoardModel.Actor victim = model.participant(ev.target());
                String kName = killer == null ? ReplayBoardTexts.tr("environment").getString() : killer.name.getString();
                String wName = "[" + ReplayBoardTexts.weapon(ev).getString() + "]";
                String vName = victim == null ? ReplayBoardTexts.tr("unknown_player").getString() : victim.name.getString();

                int kW = font.width(kName);
                int wW = font.width(wName);
                int vW = font.width(vName);
                int headW = killer != null ? 14 : 0;
                int plateW = Math.min(maxW, headW + kW + wW + vW + 24);
                int x0 = x1 - plateW;

                g.fill(x0, y, x1, y + rowH, ReplayPalette.alpha(ReplayPalette.NIGHT_1, 0.88 * alpha));
                g.fill(x0, y, x1 - 3, y + 1, ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.7 * alpha));
                g.fill(x1 - 3, y, x1, y + rowH, ReplayPalette.alpha(accent, alpha));

                int cx = x0 + 5;
                if (killer != null) {
                    PlayerFaceRenderer.draw(g, ReplayHeadSkins.texture(killer.p.id()), cx, y + 3, 12);
                    cx += 15;
                }
                int kColor = killer == null ? ReplayPalette.TEXT_MUTED : ReplayPalette.lerp(killer.color, 0xFFFFFFFF, 0.18);
                int avail = Math.max(20, x1 - 6 - cx);
                if (kW + wW + vW + 8 <= avail) {
                    g.drawString(font, kName, cx, y + 5, ReplayPalette.alpha(kColor, alpha), true);
                    cx += kW + 4;
                    g.drawString(font, wName, cx, y + 5, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, alpha), false);
                    cx += wW + 4;
                    g.drawString(font, vName, cx, y + 5, ReplayPalette.alpha(ReplayPalette.TEXT, alpha), true);
                    // Ruby strikethrough on victim name
                    g.fill(cx, y + 9, cx + vW, y + 10, ReplayPalette.alpha(ReplayPalette.RUBY, 0.92 * alpha));
                } else {
                    String compact = font.plainSubstrByWidth(kName + " " + wName + " " + vName, avail);
                    g.drawString(font, compact, cx, y + 5, ReplayPalette.alpha(ReplayPalette.TEXT, alpha), true);
                }
            } else {
                String line = feedSimpleText(ev);
                int plateW = Math.min(maxW, font.width(line) + 18);
                int x0 = x1 - plateW;
                g.fill(x0, y, x1, y + rowH, ReplayPalette.alpha(ReplayPalette.NIGHT_1, 0.86 * alpha));
                g.fill(x0, y, x1 - 3, y + 1, ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.65 * alpha));
                g.fill(x1 - 3, y, x1, y + rowH, ReplayPalette.alpha(accent, alpha));
                g.drawString(font, font.plainSubstrByWidth(line, plateW - 12), x0 + 6, y + 5,
                        ReplayPalette.alpha(ReplayPalette.TEXT, alpha), true);
            }
            y += rowH + gap;
            if (y + rowH > bottom - 8) break;
        }
        return y;
    }

    private static boolean isFeedEvent(LastMatchReplay.Kind kind) {
        return kind == LastMatchReplay.Kind.KILL
                || kind == LastMatchReplay.Kind.BOMB_DETONATE
                || kind == LastMatchReplay.Kind.GRENADE_EXPLOSION
                || kind == LastMatchReplay.Kind.PLAYER_REVIVAL
                || kind == LastMatchReplay.Kind.BLACKOUT_START
                || kind == LastMatchReplay.Kind.SHIELD_BROKEN
                || kind == LastMatchReplay.Kind.PSYCHO_STATE_CHANGE;
    }

    private int feedAccent(LastMatchReplay.Node ev) {
        return switch (ev.kind()) {
            case KILL -> {
                ReplayBoardModel.Actor killer = model.participant(ev.actor());
                yield killer != null ? killer.factionColor() : ReplayPalette.RUBY;
            }
            case BOMB_DETONATE, GRENADE_EXPLOSION -> ReplayPalette.EXPLOSION;
            case PLAYER_REVIVAL -> ReplayPalette.BRASS_LIGHT;
            case BLACKOUT_START -> 0xFF4C6BB0;
            case SHIELD_BROKEN -> ReplayPalette.SHIELD;
            case PSYCHO_STATE_CHANGE -> ReplayPalette.PSYCHO;
            default -> ReplayPalette.BRASS;
        };
    }

    private String feedSimpleText(LastMatchReplay.Node ev) {
        ReplayBoardModel.Actor actor = model.participant(ev.actor() != null ? ev.actor() : ev.target());
        String name = actor == null ? ReplayBoardTexts.tr("unknown_player").getString() : actor.name.getString();
        return switch (ev.kind()) {
            case BOMB_DETONATE -> Component.translatable("habitrain_core.replay_video.feed.bomb_detonate", name).getString();
            case GRENADE_EXPLOSION -> Component.translatable("habitrain_core.replay_video.feed.grenade_explosion", name).getString();
            case PLAYER_REVIVAL -> Component.translatable("habitrain_core.replay_video.feed.player_revival", name).getString();
            case BLACKOUT_START -> Component.translatable("habitrain_core.replay_video.feed.blackout_start").getString();
            case SHIELD_BROKEN -> Component.translatable("habitrain_core.replay_video.feed.shield_broken", name).getString();
            case PSYCHO_STATE_CHANGE -> Component.translatable("habitrain_core.replay_video.feed.psycho_state_change", name).getString();
            default -> name;
        };
    }

    private @Nullable Component renderTimeline(GuiGraphics g, int mx, int my, double renderTick) {
        int dur = duration();
        g.fill(timelineX, timelineY, timelineX + timelineW, timelineY + timelineH, 0xD4080E1E);
        g.renderOutline(timelineX, timelineY, timelineW, timelineH, ReplayPalette.BRASS_DIM);

        String curStr = ReplayStagePlan.clock(renderTick);
        String durStr = ReplayStagePlan.clock(dur);
        g.drawString(font, curStr, timelineX + 5, timelineY + 5, ReplayPalette.CHAMPAGNE, false);
        g.drawString(font, durStr, timelineX + timelineW - font.width(durStr) - 5, timelineY + 5, ReplayPalette.TEXT_MUTED, false);

        // Recessed track
        g.fill(trackX, trackY, trackX + trackW, trackY + trackH, 0xFF0B1328);
        g.renderOutline(trackX - 1, trackY - 1, trackW + 2, trackH + 2, 0x778A6A35);

        if (dur > 0 && model != null) {
            // Blackout & Psycho spans
            for (LastMatchReplay.Span span : model.sortedSpans) {
                int sx = trackX + (int) Math.round(trackW * Math.clamp(span.startTick() / (double) dur, 0.0, 1.0));
                int ex = trackX + (int) Math.round(trackW * Math.clamp(span.endTick() / (double) dur, 0.0, 1.0));
                if (ex <= sx) ex = sx + 1;
                if (span.kind() == LastMatchReplay.SpanKind.BLACKOUT) {
                    g.fill(sx, trackY + 1, ex, trackY + trackH - 1, 0xAA4C6BB0);
                } else if (span.kind() == LastMatchReplay.SpanKind.PSYCHO) {
                    g.fill(sx, trackY + trackH - 3, ex, trackY + trackH - 1, 0xCCFF4A52);
                }
            }

            // Played progress bar
            double frac = Math.clamp(renderTick / (double) dur, 0.0, 1.0);
            int playX = trackX + (int) Math.round(trackW * frac);
            if (playX > trackX) {
                g.fill(trackX, trackY + 3, playX, trackY + trackH - 3, ReplayPalette.BRASS);
                g.fill(trackX, trackY + 3, playX, trackY + 4, ReplayPalette.BRASS_LIGHT);
            }

            // Kill / Explosion / Revival diamonds
            for (LastMatchReplay.Node ev : model.replay.events()) {
                int col = switch (ev.kind()) {
                    case KILL -> ReplayPalette.RUBY;
                    case BOMB_DETONATE, GRENADE_EXPLOSION -> ReplayPalette.EXPLOSION;
                    case PLAYER_REVIVAL -> ReplayPalette.BRASS_LIGHT;
                    default -> 0;
                };
                if (col == 0) continue;
                int ex = trackX + (int) Math.round(trackW * Math.clamp(ev.tick() / (double) dur, 0.0, 1.0));
                boolean past = ev.tick() <= renderTick;
                drawDiamond(g, ex, trackY + trackH / 2, past ? col : ReplayPalette.alpha(col, 0.6));
            }

            // Playhead marker
            int px = Math.clamp(playX, trackX, trackX + trackW);
            g.fill(px - 1, trackY - 2, px + 2, trackY + trackH + 2, ReplayPalette.CHAMPAGNE);
            drawDiamond(g, px, trackY - 2, ReplayPalette.BRASS_LIGHT);

            // Hover preview tooltip
            if (!rosterOpen && (scrubbingTimeline || inTimelineTrack(mx, my))) {
                double hoverFrac = Math.clamp((mx - trackX) / (double) Math.max(1, trackW), 0.0, 1.0);
                double hoverTick = hoverFrac * dur;
                int hx = trackX + (int) Math.round(trackW * hoverFrac);
                g.fill(hx, trackY - 1, hx + 1, trackY + trackH + 1, 0xAAFFF4DA);
                String nearDesc = describeEventNear(hoverTick);
                if (nearDesc != null) {
                    return text("timeline_hover_event", ReplayStagePlan.clock(hoverTick), nearDesc);
                }
                return Component.literal(ReplayStagePlan.clock(hoverTick) + " / " + durStr);
            }
        }
        return null;
    }

    private @Nullable String describeEventNear(double targetTick) {
        if (model == null) return null;
        LastMatchReplay.Node closest = null;
        double bestDist = 35.0;
        for (LastMatchReplay.Node ev : model.replay.events()) {
            if (!isFeedEvent(ev.kind())) continue;
            double d = Math.abs(ev.tick() - targetTick);
            if (d < bestDist) {
                bestDist = d;
                closest = ev;
            }
        }
        if (closest == null) return null;
        if (closest.kind() == LastMatchReplay.Kind.KILL) {
            ReplayBoardModel.Actor k = model.participant(closest.actor());
            ReplayBoardModel.Actor v = model.participant(closest.target());
            String kn = k == null ? ReplayBoardTexts.tr("environment").getString() : k.name.getString();
            String vn = v == null ? ReplayBoardTexts.tr("unknown_player").getString() : v.name.getString();
            return kn + " → " + vn + " (" + ReplayBoardTexts.weapon(closest).getString() + ")";
        }
        return feedSimpleText(closest);
    }

    // ------------------------------------------------------------------ Passenger Roster Overlay

    private int rosterW() { return Math.min(310, Math.max(200, right - left - 24)); }
    private int rosterH() { return Math.min(240, Math.max(110, bottom - top - 20)); }
    private int rosterX() { return left + (right - left - rosterW()) / 2; }
    private int rosterY() { return top + (bottom - top - rosterH()) / 2; }
    private int rosterListTop() { return rosterY() + 26; }
    private int rosterListBottom() { return rosterY() + rosterH() - 16; }

    private int maxRosterScroll() {
        int totalH = (1 + participants.size()) * ROSTER_ROW_H;
        return Math.max(0, totalH - Math.max(1, rosterListBottom() - rosterListTop()));
    }

    private boolean handleRosterClick(double mx, double my, int button) {
        int rx = rosterX(), ry = rosterY(), rw = rosterW(), rh = rosterH();
        if (button != 0 || mx < rx || mx >= rx + rw || my < ry || my >= ry + rh) {
            rosterOpen = false;
            return true;
        }
        int closeX = rx + rw - 20, closeY = ry + 5;
        if (mx >= closeX && mx < closeX + 16 && my >= closeY && my < closeY + 14) {
            rosterOpen = false;
            playUiClick();
            return true;
        }
        int listTop = rosterListTop(), listBot = rosterListBottom();
        if (mx >= rx + 8 && mx < rx + rw - 8 && my >= listTop && my < listBot) {
            int row = (int) ((my - listTop + rosterScroll) / ROSTER_ROW_H);
            if (row == 0) {
                setFollowPlayer(null);
                playUiClick();
                return true;
            }
            if (row >= 1 && row <= participants.size()) {
                setFollowPlayer(participants.get(row - 1).id());
                playUiClick();
                return true;
            }
        }
        return true;
    }

    private void renderRosterOverlay(GuiGraphics g, int mx, int my, double renderTick) {
        // Flush base-layer widgets and drop-shadow viewport text (+0.03 Z) before lifting
        // the roster modal onto its own Z plane so underlying glyphs cannot bleed through.
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, ReplayAudioSettingsOverlay.OVERLAY_Z);
        try {
            g.fill(left, top, right, bottom, 0x99040710);
            int rx = rosterX(), ry = rosterY(), rw = rosterW(), rh = rosterH();
            g.fillGradient(rx, ry, rx + rw, ry + rh, 0xFA111A33, 0xFA04060D);
            g.renderOutline(rx, ry, rw, rh, ReplayPalette.BRASS);
            g.renderOutline(rx + 2, ry + 2, rw - 4, rh - 4, 0x778A6A35);

            Component titleTxt = text("roster_title", participants.size());
            g.drawCenteredString(font, font.plainSubstrByWidth(titleTxt.getString(), rw - 44),
                    rx + rw / 2, ry + 8, ReplayPalette.BRASS_LIGHT);

            int closeX = rx + rw - 20, closeY = ry + 5;
            boolean closeHover = mx >= closeX && mx < closeX + 16 && my >= closeY && my < closeY + 14;
            g.fill(closeX, closeY, closeX + 16, closeY + 14, closeHover ? 0xFF4E1E24 : 0xFF18233F);
            g.renderOutline(closeX, closeY, 16, 14, closeHover ? ReplayPalette.RUBY : ReplayPalette.BRASS_DIM);
            g.drawCenteredString(font, "×", closeX + 8, closeY + 3, closeHover ? 0xFFFFFFFF : ReplayPalette.TEXT);

            int listLeft = rx + 8, listRight = rx + rw - 8;
            int listTop = rosterListTop(), listBot = rosterListBottom();
            rosterScroll = Math.clamp(rosterScroll, 0, maxRosterScroll());
            g.fill(listLeft, listTop, listRight, listBot, 0xFF070C1A);
            g.flush();
            g.enableScissor(listLeft, listTop, listRight, listBot);
            try {
                int totalRows = 1 + participants.size();
                for (int i = 0; i < totalRows; i++) {
                    int rowTop = listTop + i * ROSTER_ROW_H - rosterScroll;
                    int rowBot = rowTop + ROSTER_ROW_H;
                    if (rowBot <= listTop || rowTop >= listBot) continue;
                    boolean hovered = mx >= listLeft && mx < listRight && my >= Math.max(listTop, rowTop) && my < Math.min(listBot, rowBot);
                    boolean selected = i == 0 ? followPlayerUuid == null : participants.get(i - 1).id().equals(followPlayerUuid);
                    int bgTop = selected ? (i == 0 ? 0xFF3A2D14 : 0xFF4A2029)
                            : (hovered ? 0xFF233256 : (i % 2 == 0 ? 0xFF10182D : 0xFF0C1324));
                    int bgBot = selected ? (i == 0 ? 0xFF21190B : 0xFF2A1118)
                            : (hovered ? 0xFF151F3B : bgTop);
                    g.fillGradient(listLeft + 1, rowTop + 1, listRight - 1, rowBot - 1, bgTop, bgBot);

                    if (i == 0) {
                        g.fill(listLeft + 1, rowTop + 1, listLeft + 4, rowBot - 1, ReplayPalette.BRASS_LIGHT);
                        g.drawString(font, font.plainSubstrByWidth(text("roster_global").getString(), listRight - listLeft - 14),
                                listLeft + 10, rowTop + 7, selected ? ReplayPalette.CHAMPAGNE : ReplayPalette.TEXT, false);
                    } else {
                        LastMatchReplay.Participant p = participants.get(i - 1);
                        int roleColor = ReplayPalette.opaque(p.endColor(), ReplayPalette.PASSENGER);
                        g.fill(listLeft + 1, rowTop + 1, listLeft + 4, rowBot - 1, selected ? ReplayPalette.FOCUS_TRAIL : roleColor);
                        PlayerFaceRenderer.draw(g, ReplayHeadSkins.texture(p.id()), listLeft + 7, rowTop + 4, 14);

                        boolean aliveNow = true;
                        if (model != null) {
                            ReplayBoardModel.Actor actor = model.participant(p.id());
                            if (actor != null) {
                                aliveNow = actor.track.sample(renderTick).state() == ReplayTrackSampler.State.ALIVE;
                            }
                        }
                        Component statusTag = ReplayBoardTexts.tr(aliveNow ? "controls.roster_alive" : "controls.roster_dead");
                        int tagW = font.width(statusTag) + 8;
                        g.drawString(font, statusTag, listRight - tagW, rowTop + 7, aliveNow ? 0xFF6DCEC0 : ReplayPalette.RUBY, false);

                        String nameStr = ReplayBoardTexts.name(p).getString();
                        String roleStr = ReplayBoardTexts.role(p.endRole()).getString();
                        int nameX = listLeft + 25;
                        int avail = Math.max(30, listRight - tagW - nameX - 6);
                        String combined = nameStr + " · " + roleStr + (p.kills() > 0 ? " (" + p.kills() + "K)" : "");
                        g.drawString(font, font.plainSubstrByWidth(combined, avail), nameX, rowTop + 7,
                                selected ? ReplayPalette.FOCUS_TRAIL_HEAD : ReplayPalette.TEXT, false);
                    }
                }
            } finally {
                g.flush();
                g.disableScissor();
            }
            g.renderOutline(listLeft, listTop, listRight - listLeft, listBot - listTop, 0x668A6A35);
            g.drawCenteredString(font, font.plainSubstrByWidth(text("roster_hint").getString(), rw - 16),
                    rx + rw / 2, ry + rh - 12, ReplayPalette.TEXT_MUTED);
        } finally {
            g.flush();
            g.pose().popPose();
        }
    }

    // ------------------------------------------------------------------ Helpers & Art Deco Pill

    private void playUiClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static String formatSpeed(float spd) {
        if (Math.abs(spd - Math.round(spd)) < 0.05f) return Math.round(spd) + "x";
        return String.format(Locale.ROOT, "%.1fx", spd);
    }

    private static void drawCornerAccent(GuiGraphics g, int x, int y, int dx, int dy) {
        for (int i = 0; i < 4; i++) {
            g.fill(x + dx * i, y + dy * (3 - i), x + dx * i + 1, y + dy * (3 - i) + 1, ReplayPalette.BRASS_LIGHT);
        }
    }

    private static void drawDiamond(GuiGraphics g, int cx, int cy, int color) {
        g.fill(cx, cy - 2, cx + 1, cy + 3, color);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
        g.fill(cx - 2, cy, cx + 3, cy + 1, color);
    }

    private static final class BrassPill extends Button {
        boolean selected;
        boolean focusTint;
        boolean accent;

        BrassPill(int x, int y, int w, int h, Component message, OnPress onPress) {
            super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
        }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
            boolean hovered = active && isHoveredOrFocused();
            int border;
            int topBg;
            int botBg;
            if (!active) {
                border = 0xFF3E4659;
                topBg = 0xFF101624;
                botBg = 0xFF0A0F1A;
            } else if (accent) {
                border = hovered ? 0xFFFF888E : ReplayPalette.RUBY;
                topBg = hovered ? 0xFF5A1D26 : 0xFF3E141C;
                botBg = hovered ? 0xFF3A1018 : 0xFF260B10;
            } else if (focusTint && selected) {
                border = hovered ? ReplayPalette.FOCUS_TRAIL_HEAD : ReplayPalette.FOCUS_TRAIL;
                topBg = hovered ? 0xFF5B2532 : 0xFF461D28;
                botBg = hovered ? 0xFF36141D : 0xFF281018;
            } else if (selected) {
                border = hovered ? ReplayPalette.CHAMPAGNE : ReplayPalette.BRASS_LIGHT;
                topBg = hovered ? 0xFF4A391C : 0xFF3A2C14;
                botBg = hovered ? 0xFF2B200F : 0xFF21190B;
            } else {
                border = hovered ? ReplayPalette.BRASS_LIGHT : ReplayPalette.BRASS_DIM;
                topBg = hovered ? 0xFF25355C : 0xFF131D36;
                botBg = hovered ? 0xFF162140 : 0xFF0A1022;
            }
            g.fill(getX(), getY(), getX() + width, getY() + height, border);
            g.fillGradient(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, topBg, botBg);
            if (active) {
                g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + 2,
                        selected ? 0x44FFF4DA : 0x1AFFFFFF);
            }
            int textColor = !active ? ReplayPalette.TEXT_FAINT
                    : (selected || hovered ? ReplayPalette.CHAMPAGNE : ReplayPalette.TEXT);
            var font = Minecraft.getInstance().font;
            String clipped = font.plainSubstrByWidth(getMessage().getString(), Math.max(8, width - 6));
            g.drawCenteredString(font, clipped, getX() + width / 2, getY() + (height - font.lineHeight) / 2, textColor);
        }
    }
}
