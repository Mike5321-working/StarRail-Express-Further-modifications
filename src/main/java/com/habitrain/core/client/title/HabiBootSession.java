package com.habitrain.core.client.title;

import com.habitrain.core.client.config.ClientVisualPreferences;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.jetbrains.annotations.Nullable;

/**
 * Shared client-side lifecycle state machine that connects {@code StarRailLoadingOverlay}
 * (Station 00 boot &amp; resource loading) seamlessly into {@code StarRailExpressTitleScreen}
 * (100% READY lock -> beat-quantized 1.20s diagonal split -> Station 01 interactive main menu)
 * with zero black frames and uninterrupted 100 BPM audio phase.
 */
@Environment(EnvType.CLIENT)
public final class HabiBootSession {

    public static final double COVER_FULL = 0.90;  // 1.5 beats: ink curtain covers screen
    public static final double SPLIT_AT = 1.20;    // 2.0 beats: diagonal split opens + menu.wav beat 0
    public static final double EXIT_DUR = 1.88;    // transition curtain completely cleared
    public static final double AUTO_ENTER_DELAY = 0.85;
    public static final double MIN_BOOT_READY_TIME = 2.65;
    /** Max visual timeline step per frame when running on fallback wall clock without audio sync. */
    public static final double MAX_TIMELINE_DT = 0.05;
    /** Hard cap on holding boot frame 0 waiting for audio output to open, so loading never stalls. */
    public static final double AUDIO_SYNC_MAX_WAIT_SEC = 1.5;

    public enum Phase {
        IDLE,
        BOOT_LOADING,
        READY_STANDBY,
        DEPARTING,
        STATION_01
    }

    private static final HabiBootSession INSTANCE = new HabiBootSession();

    public static HabiBootSession getInstance() {
        return INSTANCE;
    }

    private final HabiRealProgressTracker tracker = new HabiRealProgressTracker(HabiRealProgressTracker.BOOT_STAGES);

    private Phase phase = Phase.IDLE;
    private double timelineSec;
    private boolean firstTimelineFramePending;
    private boolean waitingForAudioSync;
    private long audioSyncWaitStartNanos = System.nanoTime();
    private long lastFrameNanos = System.nanoTime();
    private long stationStartNanos = System.nanoTime();
    private double exitAt = Double.POSITIVE_INFINITY;
    private boolean quantizePending;
    private boolean readyChimePlayed;
    private boolean arrivedOnce;
    private boolean replayMode;
    private double replayDuration = 4.6;
    private boolean overlayHandedOff;

    private @Nullable ReloadInstance activeReload;
    private @Nullable HabiRealProgressTracker.LoadSnapshot lastSnapshot;

    // Smoothed normalized pointer parallax [-1, 1]
    private double targetPx, targetPy;
    private double curPx, curPy;

    private HabiBootSession() {}

    /** Starts a fresh initial-boot loading sequence driven by a real Minecraft {@link ReloadInstance}. */
    public void beginInitialBoot(@Nullable ReloadInstance reload) {
        beginInitialBoot(null, reload);
    }

    /** Starts a fresh initial-boot loading sequence, syncing live Minecraft audio volume before starting stems. */
    public void beginInitialBoot(@Nullable Minecraft mc, @Nullable ReloadInstance reload) {
        this.activeReload = reload;
        this.phase = Phase.BOOT_LOADING;
        this.timelineSec = 0.0;
        this.firstTimelineFramePending = true;
        this.lastFrameNanos = System.nanoTime();
        this.exitAt = Double.POSITIVE_INFINITY;
        this.quantizePending = false;
        this.readyChimePlayed = false;
        this.replayMode = false;
        this.overlayHandedOff = false;
        this.tracker.reset();
        this.lastSnapshot = null;
        HabiLoadingPerfMonitor.getInstance().beginSession(
                HabiLoadingPerfMonitor.SessionKind.GAME_BOOT, "InitialBoot");

        HabiBootAudioEngine audio = HabiBootAudioEngine.getInstance();
        audio.preloadAsync();
        if (mc != null) {
            audio.syncMinecraftVolumes(mc);
            if (audio.isSoundEffectivelyEnabled()) {
                audio.prewarmSoundEngine(mc);
            }
        }
        audio.setMenuFocusMode(HabiBootAudioEngine.FocusMode.NORMAL);
        audio.startLoadingSequence(0.0, 0.0);
        this.waitingForAudioSync = audio.shouldWaitForAudibleBootStart();
        this.audioSyncWaitStartNanos = System.nanoTime();
    }

    /** Replays the full Station 00 -> Station 01 boot sequence on demand ({@code R} key). */
    public void beginReplay() {
        this.activeReload = null;
        this.phase = Phase.BOOT_LOADING;
        this.timelineSec = 0.0;
        this.firstTimelineFramePending = true;
        this.lastFrameNanos = System.nanoTime();
        this.exitAt = Double.POSITIVE_INFINITY;
        this.quantizePending = false;
        this.readyChimePlayed = false;
        this.replayMode = true;
        this.replayDuration = 4.6;
        this.overlayHandedOff = true;
        this.tracker.reset();
        this.lastSnapshot = null;

        HabiBootAudioEngine audio = HabiBootAudioEngine.getInstance();
        Minecraft mc = Minecraft.getInstance();
        audio.syncMinecraftVolumes(mc);
        if (audio.isSoundEffectivelyEnabled()) {
            audio.prewarmSoundEngine(mc);
        }
        audio.setMenuFocusMode(HabiBootAudioEngine.FocusMode.NORMAL);
        audio.startLoadingSequence(0.0, 0.0);
        this.waitingForAudioSync = audio.shouldWaitForAudibleBootStart();
        this.audioSyncWaitStartNanos = System.nanoTime();
    }

    /**
     * Called when {@code StarRailExpressTitleScreen} opens.
     * If we already arrived at Station 01 earlier (e.g. returning from Multiplayer/Options or leaving a world),
     * jump directly to {@link Phase#STATION_01} and resume Station 01 BGM cleanly.
     */
    public void ensureTitleScreenEntered() {
        ensureTitleScreenEntered(null);
    }

    public void ensureTitleScreenEntered(@Nullable Minecraft mc) {
        HabiBootAudioEngine audio = HabiBootAudioEngine.getInstance();
        if (mc != null) {
            audio.syncMinecraftVolumes(mc);
        }
        audio.setMenuFocusMode(HabiBootAudioEngine.FocusMode.NORMAL);

        if (phase == Phase.IDLE) {
            if (arrivedOnce) {
                phase = Phase.STATION_01;
                stationStartNanos = System.nanoTime() - 2_000_000_000L; // already settled
                audio.startMenuDirect(getStationElapsed());
            } else {
                // Edge case: TitleScreen opened without StarRailLoadingOverlay (e.g. reload skipped)
                phase = Phase.READY_STANDBY;
                timelineSec = 3.2;
                firstTimelineFramePending = true;
                lastFrameNanos = System.nanoTime();
                exitAt = Double.POSITIVE_INFINITY;
                quantizePending = false;
                readyChimePlayed = true;
                overlayHandedOff = true;
                tracker.markAlreadyCompleted(3.2);
                audio.startLoadingSequence(3.2, 1.0);
            }
        } else if (phase == Phase.STATION_01) {
            audio.startMenuDirect(getStationElapsed());
        }
    }

    /** Called by {@code StarRailLoadingOverlay} right before calling {@code minecraft.setOverlay(null)}. */
    public void markOverlayHandedOff() {
        this.overlayHandedOff = true;
    }

    public boolean isOverlayHandedOff() {
        return overlayHandedOff;
    }

    public boolean hasArrivedOnce() {
        return arrivedOnce;
    }

    public Phase getPhase() {
        return phase;
    }

    public boolean isStationInteractive() {
        return phase == Phase.STATION_01;
    }

    public boolean isReadyStandby() {
        return phase == Phase.READY_STANDBY;
    }

    public boolean isDeparting() {
        return phase == Phase.DEPARTING;
    }

    public boolean isQuantizePending() {
        return quantizePending;
    }

    public double getTimelineSeconds() {
        return Math.max(0.0, timelineSec);
    }

    static double clampTimelineDelta(double rawDtSec) {
        if (Double.isNaN(rawDtSec) || rawDtSec <= 0.0) return 0.0;
        return Math.min(MAX_TIMELINE_DT, rawDtSec);
    }

    /**
     * Locks visual timeline monotonically to the audible audio playback clock so visuals never jump
     * backward on tiny clock jitter and never drift out of phase with {@code intro.wav}/{@code exit.wav}.
     */
    static double resolveSyncedTimeline(double currentT, double audibleT) {
        if (Double.isNaN(audibleT) || audibleT < 0.0) {
            return currentT;
        }
        return Math.max(currentT, audibleT);
    }

    public double getExitAt() {
        return exitAt;
    }

    public double getStationElapsed() {
        if (phase == Phase.DEPARTING && !Double.isInfinite(exitAt)) {
            double e = getTimelineSeconds() - exitAt;
            return e < COVER_FULL ? -1.0 : (e - SPLIT_AT);
        }
        return Math.max(0.0, (System.nanoTime() - stationStartNanos) / 1_000_000_000.0);
    }

    public void updatePointer(double normX, double normY) {
        this.targetPx = Math.max(-1.0, Math.min(1.0, normX));
        this.targetPy = Math.max(-1.0, Math.min(1.0, normY));
    }

    public double getPointerX() { return curPx; }
    public double getPointerY() { return curPy; }

    /**
     * Advances the state machine one render frame and returns the current real loading snapshot.
     * Safe to call from either {@code StarRailLoadingOverlay} or {@code StarRailExpressTitleScreen}.
     */
    public HabiRealProgressTracker.LoadSnapshot stepFrame(Minecraft mc, boolean atlasReady) {
        long nowNanos = System.nanoTime();
        double rawDt = (nowNanos - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = nowNanos;

        HabiBootAudioEngine audio = HabiBootAudioEngine.getInstance();
        audio.syncMinecraftVolumes(mc);

        double dt;
        if (firstTimelineFramePending) {
            firstTimelineFramePending = false;
            dt = 0.0;
        } else if (waitingForAudioSync && phase == Phase.BOOT_LOADING) {
            double audibleT = audio.getAudibleLoadingTimelineSeconds();
            double waitElapsed = Math.max(0.0, (nowNanos - audioSyncWaitStartNanos) / 1_000_000_000.0);
            if (audibleT >= 0.0) {
                waitingForAudioSync = false;
                double prev = timelineSec;
                timelineSec = resolveSyncedTimeline(prev, audibleT);
                dt = Math.max(0.0, timelineSec - prev);
            } else if (!audio.shouldWaitForAudibleBootStart() || waitElapsed >= AUDIO_SYNC_MAX_WAIT_SEC) {
                waitingForAudioSync = false;
                dt = clampTimelineDelta(rawDt);
                timelineSec += dt;
            } else {
                // Hold opening frame at t = 0.0s until audio backend actually starts outputting intro.wav
                dt = 0.0;
            }
        } else {
            double wallDt = clampTimelineDelta(rawDt);
            if (phase == Phase.BOOT_LOADING || phase == Phase.READY_STANDBY) {
                double audibleT = audio.getAudibleLoadingTimelineSeconds();
                if (audibleT >= 0.0) {
                    double prev = timelineSec;
                    timelineSec = resolveSyncedTimeline(prev, audibleT);
                    dt = Math.max(0.0, timelineSec - prev);
                } else {
                    dt = wallDt;
                    timelineSec += dt;
                }
            } else if (phase == Phase.DEPARTING) {
                double audibleE = audio.getAudibleDepartureElapsedSeconds();
                if (!Double.isNaN(audibleE) && !Double.isInfinite(exitAt)) {
                    double prev = timelineSec;
                    timelineSec = resolveSyncedTimeline(prev, exitAt + audibleE);
                    dt = Math.max(0.0, timelineSec - prev);
                } else {
                    dt = wallDt;
                    timelineSec += dt;
                }
            } else {
                dt = wallDt;
            }
        }

        double pLerp = Math.min(1.0, Math.max(0.0, dt) * 2.5);
        curPx += (targetPx - curPx) * pLerp;
        curPy += (targetPy - curPy) * pLerp;

        double t = getTimelineSeconds();
        boolean departing = phase == Phase.DEPARTING;
        boolean arrived = phase == Phase.STATION_01;

        HabiRealProgressTracker.LoadSnapshot snap;
        if (replayMode) {
            snap = tracker.updateReplaySequence(t, dt, replayDuration, departing, arrived);
        } else {
            boolean forceDone = (phase != Phase.BOOT_LOADING) || activeReload == null || activeReload.isDone();
            snap = tracker.updateResourceReload(
                    t, dt, activeReload, forceDone, atlasReady,
                    audio.getPreloadProgress(), MIN_BOOT_READY_TIME, departing, arrived
            );
        }
        lastSnapshot = snap;

        if (phase == Phase.BOOT_LOADING || phase == Phase.READY_STANDBY) {
            if (phase == Phase.BOOT_LOADING
                    && !waitingForAudioSync
                    && snap.stageJustCompleted() != null
                    && !snap.ready()
                    && audio.isSoundEffectivelyEnabled()) {
                audio.playStageTick(snap.activeIndex());
            }
            if (snap.ready()) {
                if (phase == Phase.BOOT_LOADING) {
                    phase = Phase.READY_STANDBY;
                }
                if (!readyChimePlayed) {
                    readyChimePlayed = true;
                    audio.playReadyChime();
                }
                if (ClientVisualPreferences.isKineticUiAutoEnter()
                        && overlayHandedOff
                        && !Double.isNaN(snap.readyAt())
                        && (t - snap.readyAt()) >= AUTO_ENTER_DELAY) {
                    triggerBoarding();
                }
            }
            audio.updateLoadingProgress(snap.progress(), t);
        } else if (phase == Phase.DEPARTING) {
            if (quantizePending && t >= exitAt) {
                quantizePending = false;
            }
            double e = t - exitAt;
            if (e >= EXIT_DUR) {
                phase = Phase.STATION_01;
                arrivedOnce = true;
                replayMode = false;
                stationStartNanos = nowNanos - Math.round((e - SPLIT_AT) * 1_000_000_000.0);
                audio.ensureMenuPlaying(getStationElapsed());
            }
        }
        return snap;
    }

    /** Triggers beat-quantized departure from Station 00 into Station 01 (only allowed once 100% READY). */
    public boolean triggerBoarding() {
        if (phase != Phase.READY_STANDBY) return false;
        double tNow = getTimelineSeconds();
        HabiBootAudioEngine audio = HabiBootAudioEngine.getInstance();
        double delaySec = audio.scheduleSeamlessDeparture(tNow);
        this.quantizePending = delaySec > 0.025;
        this.exitAt = tNow + delaySec;
        this.phase = Phase.DEPARTING;
        return true;
    }

    /** Toggles audio mute (`M` key or toolbar button) while staying synced to current phase. */
    public void toggleSoundMute() {
        HabiBootAudioEngine audio = HabiBootAudioEngine.getInstance();
        Minecraft mc = Minecraft.getInstance();
        boolean currentlyOn = audio.isSoundEffectivelyEnabled();
        if (!currentlyOn) {
            if (!ClientVisualPreferences.isKineticUiSoundEnabled()) {
                ClientVisualPreferences.setKineticUiSoundEnabled(true);
            }
            if (ClientVisualPreferences.getKineticUiBgmVolume() <= 0.001f) {
                ClientVisualPreferences.setKineticUiBgmVolume(1.0f);
            }
            audio.syncMinecraftVolumes(mc);
            audio.setUserMuted(false, getTimelineSeconds(), phase == Phase.STATION_01, getStationElapsed());
            audio.syncMinecraftVolumes(mc);
        } else {
            audio.setUserMuted(true, getTimelineSeconds(), phase == Phase.STATION_01, getStationElapsed());
            audio.syncMinecraftVolumes(mc);
        }
    }

    /** Called when opening a child screen from Station 01 (Multiplayer, Options, Role Codex, etc.). */
    public void onEnterSubScreen() {
        if (phase == Phase.STATION_01) {
            HabiBootAudioEngine.getInstance().setMenuFocusMode(HabiBootAudioEngine.FocusMode.MODAL);
        }
    }

    /** Called when leaving the title screen flow entirely (entering a world or switching to vanilla TitleScreen). */
    public void onLeaveTitleFlow() {
        HabiLoadingPerfMonitor.getInstance().finishSession(
                HabiLoadingPerfMonitor.SessionKind.GAME_BOOT, "left_title_flow");
        HabiBootAudioEngine.getInstance().fadeOutAndReleaseStems();
    }

    /** Resets transient reload references when entering a game world. */
    public void onWorldJoined() {
        this.activeReload = null;
        this.arrivedOnce = true;
        if (phase != Phase.STATION_01) {
            phase = Phase.IDLE;
        }
        HabiLoadingPerfMonitor.getInstance().finishSession(
                HabiLoadingPerfMonitor.SessionKind.GAME_BOOT, "world_joined");
        HabiBootAudioEngine.getInstance().fadeOutAndReleaseStems();
    }

    public @Nullable HabiRealProgressTracker.LoadSnapshot getLastSnapshot() {
        return lastSnapshot;
    }
}
