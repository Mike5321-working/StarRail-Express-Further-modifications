package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.exmo.sre.loading.SREReceivingLevelScreen;
import net.exmo.sre.loading.StarRailExpressTitleScreen;
import net.exmo.sre.loading.TrainLoadingScreen;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;

import static com.habitrain.core.client.title.HabiKineticGfx.clamp;
import static com.habitrain.core.client.title.HabiKineticGfx.eInCubic;
import static com.habitrain.core.client.title.HabiKineticGfx.eOutCubic;
import static com.habitrain.core.client.title.HabiKineticGfx.eOutExpo;
import static com.habitrain.core.client.title.HabiKineticGfx.lerp;
import static com.habitrain.core.client.title.HabiKineticGfx.sat;

/**
 * Unified 3-act World-Entry Cutscene state machine bridging Station 01 seamlessly through
 * {@link ConnectScreen}, {@link GenericMessageScreen}, {@link ProgressScreen},
 * {@link TrainLoadingScreen}, {@link SREReceivingLevelScreen}, and directly into the live 3D
 * Minecraft world via the expanding Apple Portal Iris (with zero black cuts or progress resets).
 *
 * <ul>
 *   <li><b>Act 1 ({@link Phase#START}, 2.88s)</b>: Exact frame-0 Station 01 handoff -> Apple slice
 *       split -> Red window band morphs into HABITRAIN Car 01 -> Sliding doors open -> Camera
 *       rushes into doorway revealing the Starry Chrono Rail.</li>
 *   <li><b>Act 2 ({@link Phase#LOADING}, real-driven loop)</b>: Starry Chrono Rail Time Tunnel with
 *       monotonic real progress fusion (Connect -> Spawn Chunks -> Receiving Terrain).</li>
 *   <li><b>Act 3 ({@link Phase#END}, 1.66s)</b>: Arrival gold-foil postmark seal -> Expanding Apple
 *       Portal Iris revealing the REAL live 3D Minecraft world -> Champagne-gold bloom &amp; landing
 *       FOV deceleration -> clean handoff to gameplay.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class HabiWorldEntrySession {

    public enum Phase {
        INACTIVE,
        START,
        LOADING,
        END
    }

    public record StageDef(String id, String code, String en, String zh, double weight) {}

    public record StageState(String id, String code, String en, String zh, double progress, boolean done) {}

    public static final StageDef[] WORLD_STAGES = {
            new StageDef("seed",   "01", "WORLD ENGINE & SEED",  "世界维度与种子管线校准", 0.14),
            new StageDef("biome",  "02", "TERRAIN & BIOME GRID", "地形噪声与生物群系采样", 0.20),
            new StageDef("chunks", "03", "SPAWN CHUNK TOPOLOGY", "出生点区块轨网生成",     0.30),
            new StageDef("light",  "04", "LIGHTING & CARRIAGES", "光照烘焙与车厢实体装配", 0.22),
            new StageDef("arrive", "05", "PLATFORM HANDOFF",     "列车站台场景着陆就绪",   0.14)
    };

    // Exact act timings matching habitrain_logo_v3/tunnel_scene.js
    public static final double START_DUR = 2.88;
    public static final double DOORS_AT = 1.94;
    public static final double BOARD_AT = 2.22;
    /** Trigger deferred singleplayer openWorld() right after doorway fills the viewport (zero Act 1 stutter). */
    public static final double DEFERRED_LAUNCH_AT = 2.72;
    /** Arrival seal holds 0.00..0.48s while live 3D world pre-warms invisibly behind it; Apple Portal opens at 0.48s. */
    public static final double END_PORTAL_START = 0.48;
    public static final double END_PORTAL_DUR = 0.60;
    public static final double END_HUD_REVEAL_AT = 1.06;
    public static final double END_TOTAL_DUR = 1.66;
    /** Max extra seal hold while waiting for a smooth (<=55 ms) 3D warmup frame before opening Apple Portal. */
    public static final double END_WARMUP_MAX_HOLD_SEC = 0.50;
    public static final double END_WARMUP_SMOOTH_FRAME_MS = 55.0;
    public static final int END_WARMUP_REQUIRED_FRAMES = 1;
    /** Minimum smooth tunnel cruise on ultra-fast local/LAN joins so the tunnel never flashes for 1 frame. */
    public static final double MIN_TUNNEL_CRUISE_SEC = 1.15;
    public static final double MAX_DT = 0.05;

    public static final double[] START_CUES = {
            0.30, 0.44, 0.52, 0.60, 0.68, 0.79, 0.87, 0.93, 1.16, 1.48
    };

    private static final HabiWorldEntrySession INSTANCE = new HabiWorldEntrySession();

    public static HabiWorldEntrySession getInstance() {
        return INSTANCE;
    }

    private Phase phase = Phase.INACTIVE;
    private double phaseT;
    private double totalT;
    private double tunnelClock;
    private double progress;
    private double rawProgress;
    private boolean directEntry;
    private double directEntryElapsed;

    // Frame-0 Station 01 handoff pose
    private double handoffU0 = 3.0;
    private double handoffPx;
    private double handoffPy;
    private String routeName = "苹果园环线";
    private String routeSub = "ORCHARD LOOP // EXPRESS 01";
    private String statusCounter = "CHUNKS 000/441  ·  1435MM";

    // Deferred singleplayer launch (executes at DEFERRED_LAUNCH_AT when doorway already covers screen)
    private @Nullable Runnable pendingLaunchAction;
    private boolean pendingLaunchFired;
    private boolean audioEnabled = true;

    // Preserved route metadata across BackupConfirmScreen/ConfirmScreen so proceeding keeps real world name & warm atlas
    private @Nullable String preservedConfirmRouteName;
    private @Nullable String preservedConfirmRouteSub;
    private long preservedConfirmNanos;

    // Real world loading signals
    private boolean worldReady;
    private double noScreenInWorldTimer;
    private int lastChunkFull;
    private int lastChunkTotal;
    private long lastChunkScanMs;
    private boolean seenChunkListener;

    // One-shot cue dispatch trackers
    private final boolean[] cueFired = new boolean[START_CUES.length];
    private final boolean[] stageDone = new boolean[WORLD_STAGES.length];
    private boolean doorsSoundFired;
    private boolean eruptSoundFired;
    private boolean breakoutSoundFired;

    // Frame timing & smoothed pointer parallax
    private long lastFrameNanos = System.nanoTime();
    private double lastRawFrameMs;
    private int endInWorldSmoothFrames;
    private double endWarmupHoldSec;
    private boolean endWarmupReleased;
    private boolean forceWarmupGateForTest;
    private double targetPx, targetPy;
    private double curPx, curPy;

    private HabiWorldEntrySession() {}

    // ── Public Lifecycle Entry Points ────────────────────────────────────────

    /**
     * Begins the complete 3-act world-entry cutscene seamlessly from the current Station 01 frame.
     *
     * @param stationU         exact current {@code HabiBootSession.getStationElapsed()}
     * @param pointerX         exact current smoothed pointer X
     * @param pointerY         exact current smoothed pointer Y
     * @param routeName        display destination name (world name or server name)
     * @param routeSub         technical subtitle (e.g. {@code LOCAL WORLD // SURVIVAL} or server address)
     * @param launchAction     actual Minecraft join runnable ({@code ConnectScreen.startConnecting} or {@code openWorld})
     * @param deferUntilDoor   {@code true} for singleplayer (defers synchronous {@code level.dat} read until doorway covers screen);
     *                         {@code false} for multiplayer (async {@code ConnectScreen} starts immediately)
     */
    public void beginFromStation(
            double stationU,
            double pointerX,
            double pointerY,
            @Nullable String routeName,
            @Nullable String routeSub,
            @Nullable Runnable launchAction,
            boolean deferUntilDoor
    ) {
        clearPreservedConfirmRoute();
        resetInternalState();
        this.handoffU0 = Math.max(0.5, stationU);
        this.handoffPx = pointerX;
        this.handoffPy = pointerY;
        this.curPx = pointerX;
        this.curPy = pointerY;
        this.targetPx = pointerX;
        this.targetPy = pointerY;
        this.routeName = sanitizeRouteName(routeName, "苹果园环线");
        this.routeSub = sanitizeRouteSub(routeSub, "ORCHARD LOOP // EXPRESS 01");
        this.statusCounter = "SYNCING ROUTE  ·  1435MM";
        this.directEntry = false;
        this.phase = Phase.START;
        this.lastFrameNanos = System.nanoTime();
        HabiLoadingPerfMonitor.getInstance().beginSession(
                HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, this.routeName + " // " + this.routeSub);

        if (audioEnabled) {
            HabiTunnelAtlas.getInstance().preloadAsync(HabiKineticAtlas.getInstance());
            HabiBootAudioEngine.getInstance().startTunnelEntry(true);
        }

        if (deferUntilDoor) {
            this.pendingLaunchAction = launchAction;
            this.pendingLaunchFired = false;
        } else {
            this.pendingLaunchAction = null;
            this.pendingLaunchFired = true;
            if (launchAction != null) {
                launchAction.run();
            }
        }
    }

    /**
     * Begins directly in Act 2 ({@link Phase#LOADING}) Time Tunnel when entering a world from
     * a native vanilla screen (e.g., Direct Connect / Create World) or during an in-world
     * dimension transfer (Nether/End/server switch).
     */
    public void beginDirectTunnel(@Nullable String routeName, @Nullable String routeSub) {
        String resolvedName = routeName;
        String resolvedSub = routeSub;
        if (preservedConfirmRouteName != null
                && (System.nanoTime() - preservedConfirmNanos) <= 45_000_000_000L
                && (resolvedName == null || resolvedName.isBlank() || "星夜轨网".equals(resolvedName) || "本地世界".equals(resolvedName))) {
            resolvedName = preservedConfirmRouteName;
            if (preservedConfirmRouteSub != null && !preservedConfirmRouteSub.isBlank()) {
                resolvedSub = preservedConfirmRouteSub;
            }
        }
        clearPreservedConfirmRoute();
        resetInternalState();
        this.routeName = sanitizeRouteName(resolvedName, "星夜轨网");
        this.routeSub = sanitizeRouteSub(resolvedSub, "CHRONO RAIL // EXPRESS 01");
        this.statusCounter = "SYNCING TERRAIN  ·  1435MM";
        this.directEntry = true;
        this.directEntryElapsed = 0.0;
        this.phase = Phase.LOADING;
        this.phaseT = 0.0;
        this.totalT = START_DUR;
        this.tunnelClock = 1280.0;
        this.progress = 0.06;
        this.rawProgress = 0.06;
        this.pendingLaunchFired = true;
        this.lastFrameNanos = System.nanoTime();
        HabiLoadingPerfMonitor.getInstance().beginSession(
                HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, "Direct // " + this.routeName);

        if (audioEnabled) {
            HabiTunnelAtlas.getInstance().preloadAsync(HabiKineticAtlas.getInstance());
            HabiBootAudioEngine.getInstance().startTunnelEntry(false);
        }
    }

    /** Immediately aborts an active world-entry cutscene (e.g. disconnect, error, or user cancel). */
    public void abort(@Nullable Minecraft mc, boolean returningToTitleScreen) {
        abortInternal(mc, returningToTitleScreen, false);
    }

    private void abortInternal(@Nullable Minecraft mc, boolean returningToTitleScreen, boolean preserveAtlasForConfirm) {
        if (phase == Phase.INACTIVE) return;
        if (preserveAtlasForConfirm) {
            this.preservedConfirmRouteName = this.routeName;
            this.preservedConfirmRouteSub = this.routeSub;
            this.preservedConfirmNanos = System.nanoTime();
        } else {
            clearPreservedConfirmRoute();
        }
        String reason = preserveAtlasForConfirm
                ? "paused_for_confirm"
                : (returningToTitleScreen ? "aborted_to_title" : "aborted");
        HabiLoadingPerfMonitor.getInstance().finishSession(
                HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, reason);
        resetInternalState();
        if (audioEnabled) {
            HabiBootAudioEngine.getInstance().stopTunnelAudio();
        }
        if (mc != null && mc.isSameThread()) {
            if (!preserveAtlasForConfirm) {
                HabiTunnelAtlas.getInstance().dispose();
            }
            if (returningToTitleScreen) {
                HabiBootSession.getInstance().ensureTitleScreenEntered(mc);
            }
        }
    }

    public boolean hasPreservedConfirmRoute() {
        return preservedConfirmRouteName != null
                && (System.nanoTime() - preservedConfirmNanos) <= 45_000_000_000L;
    }

    private void clearPreservedConfirmRoute() {
        this.preservedConfirmRouteName = null;
        this.preservedConfirmRouteSub = null;
        this.preservedConfirmNanos = 0L;
    }

    /** Called when {@link Minecraft#setScreen(Screen)} switches screens while the cutscene is active. */
    public void onScreenChanged(@Nullable Minecraft mc, @Nullable Screen nextScreen) {
        if (!isActive()) return;
        if (nextScreen == null) {
            // Returning to live world (mc.level != null) -> keep END / LOADING running!
            if (mc != null && mc.level == null && phase != Phase.START) {
                abort(mc, false);
            }
            return;
        }
        if (isAllowedEntryScreen(nextScreen)) {
            // Update subtitle if we received a more specific server/world context
            if (nextScreen instanceof GenericMessageScreen && isWorldSavingMessage(nextScreen.getTitle())) {
                abort(mc, false);
            }
            return;
        }
        if (nextScreen instanceof StarRailExpressTitleScreen) {
            if (phase == Phase.START && !pendingLaunchFired && pendingLaunchAction != null) {
                return; // Waiting on TitleScreen while Act 1 plays before deferred singleplayer launch
            }
            abort(mc, true);
            return;
        }
        if (nextScreen instanceof BackupConfirmScreen || nextScreen instanceof ConfirmScreen) {
            // Pause cutscene so player can interact with backup/experimental prompt, but keep TunnelAtlas & route warm!
            abortInternal(mc, false, true);
            return;
        }
        // Any error / disconnect / native menu screen: abort cleanly so player is never trapped!
        abort(mc, false);
    }

    /** Ensures a direct Time Tunnel starts when entering world-loading screens without Station 01 handoff. */
    public void ensureActiveForWorldScreen(@Nullable Minecraft mc, @Nullable String routeName, @Nullable String routeSub) {
        if (isActive()) {
            if (routeName != null && !routeName.isBlank() && "星夜轨网".equals(this.routeName)) {
                this.routeName = sanitizeRouteName(routeName, this.routeName);
            }
            if (routeSub != null && !routeSub.isBlank() && this.routeSub.startsWith("CHRONO RAIL")) {
                this.routeSub = sanitizeRouteSub(routeSub, this.routeSub);
            }
            return;
        }
        beginDirectTunnel(routeName, routeSub);
    }

    // ── Real Progress Signal Feeds ───────────────────────────────────────────

    public void reportConnectStatus(@Nullable Component status) {
        if (!isActive() || phase == Phase.END) return;
        double floor = seenChunkListener ? rawProgress : Math.min(0.32, rawProgress + 0.002);
        rawProgress = Math.max(rawProgress, Math.max(0.10, floor));
        if (status != null) {
            String s = status.getString().trim();
            if (!s.isEmpty()) {
                statusCounter = formatStatusCounter(s);
            }
        }
    }

    public void reportChunkGeneration(@Nullable StoringChunkProgressListener listener) {
        if (!isActive() || phase == Phase.END) return;
        seenChunkListener = true;
        if (listener == null) {
            rawProgress = Math.max(rawProgress, 0.16);
            return;
        }
        double pct = clamp(listener.getProgress() / 100.0, 0.0, 1.0);
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastChunkScanMs >= 90L || lastChunkTotal == 0) {
            lastChunkScanMs = nowMs;
            int diameter = Math.max(0, listener.getDiameter());
            int total = diameter * diameter;
            int full = 0;
            if (diameter > 0 && diameter <= 65) {
                for (int x = 0; x < diameter; x++) {
                    for (int z = 0; z < diameter; z++) {
                        if (listener.getStatus(x, z) == ChunkStatus.FULL) {
                            full++;
                        }
                    }
                }
            }
            lastChunkFull = full;
            lastChunkTotal = total;
        }
        if (lastChunkTotal > 0) {
            pct = Math.max(pct, lastChunkFull / (double) lastChunkTotal);
            statusCounter = String.format(Locale.ROOT, "CHUNKS %03d/%d  ·  1435MM", lastChunkFull, lastChunkTotal);
        } else {
            statusCounter = String.format(Locale.ROOT, "SPAWN %02d%%  ·  1435MM", listener.getProgress());
        }
        // Monotonically fuse spawn-chunk generation into 0.12 .. 0.80 so ReceivingLevelScreen never resets to 0!
        rawProgress = Math.max(rawProgress, 0.12 + pct * 0.68);
    }

    public void reportReceivingLevel(boolean levelReceived, @Nullable String counterTag) {
        if (!isActive() || phase == Phase.END) return;
        if (levelReceived) {
            worldReady = true;
            if (lastChunkTotal > 0) {
                statusCounter = String.format(Locale.ROOT, "CHUNKS %03d/%d  ·  READY", lastChunkTotal, lastChunkTotal);
            } else {
                statusCounter = (counterTag != null && !counterTag.isBlank())
                        ? ("VERIFIED  ·  " + counterTag)
                        : "TERRAIN LOCKED  ·  1435MM";
            }
        } else {
            // While waiting for server terrain packets, climb smoothly up to 0.90 without stalling
            double minRecv = seenChunkListener ? 0.80 : 0.30;
            rawProgress = Math.max(rawProgress, minRecv);
            if (!seenChunkListener) {
                statusCounter = (counterTag != null && !counterTag.isBlank())
                        ? ("RECEIVING  ·  " + counterTag)
                        : "RECEIVING LEVEL  ·  1435MM";
            }
        }
    }

    // ── Per-Frame Step ───────────────────────────────────────────────────────

    public void updatePointer(double normX, double normY) {
        this.targetPx = clamp(normX, -1.0, 1.0);
        this.targetPy = clamp(normY, -1.0, 1.0);
    }

    /** Advances the state machine by one render frame using wall clock (clamped to {@link #MAX_DT}). */
    public void stepFrame(@Nullable Minecraft mc) {
        if (!isActive()) return;
        long nowNanos = System.nanoTime();
        double rawDt = (nowNanos - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = nowNanos;
        lastRawFrameMs = Math.max(0.0, rawDt * 1000.0);
        double dt = clampDt(rawDt);

        if (audioEnabled) {
            HabiBootAudioEngine.getInstance().syncMinecraftVolumes(mc);
        }

        boolean inWorldNoScreen = mc != null && mc.level != null && mc.screen == null;
        step(dt, inWorldNoScreen, mc);
    }

    /** Deterministic step (package-private for unit testing). */
    void step(double dt, boolean inWorldNoScreen, @Nullable Minecraft mc) {
        if (!isActive()) return;
        dt = clampDt(dt);

        double pLerp = Math.min(1.0, dt * 3.0);
        curPx += (targetPx - curPx) * pLerp;
        curPy += (targetPy - curPy) * pLerp;

        phaseT += dt;
        totalT += dt;
        if (directEntry) {
            directEntryElapsed += dt;
        }

        if (phase == Phase.START) {
            // Act 1 timeline cues (10 seam/split/assembly clicks + ascending Dmaj9 plucks)
            for (int i = 0; i < START_CUES.length; i++) {
                if (!cueFired[i] && phaseT >= START_CUES[i]) {
                    cueFired[i] = true;
                    if (audioEnabled) {
                        HabiBootAudioEngine.getInstance().playLayerAbsorb(i);
                    }
                }
            }
            if (!doorsSoundFired && phaseT >= DOORS_AT) {
                doorsSoundFired = true;
                if (audioEnabled) {
                    HabiBootAudioEngine.getInstance().playSingularityImplode();
                }
            }
            if (!eruptSoundFired && phaseT >= BOARD_AT) {
                eruptSoundFired = true;
                if (audioEnabled) {
                    HabiBootAudioEngine.getInstance().playTunnelErupt();
                }
            }
            if (phaseT > BOARD_AT) {
                double ek = sat((phaseT - BOARD_AT) / (START_DUR - BOARD_AT));
                tunnelClock += dt * lerp(2850.0, 1450.0, eOutCubic(ek));
            }
            double startFloor = sat((phaseT - BOARD_AT) / 0.85) * 0.06;
            rawProgress = Math.max(rawProgress, startFloor);
            progress += (rawProgress - progress) * Math.min(1.0, dt * 6.0);

            // Enqueue deferred singleplayer openWorld() via mc.tell() (NOT mc.execute(), which runs
            // synchronously on the render thread inside GameRenderer.render()!) so managedBlock pumps
            // cleanly from the outer runTick() event loop with zero render re-entrancy!
            if (!pendingLaunchFired && pendingLaunchAction != null && phaseT >= DEFERRED_LAUNCH_AT) {
                pendingLaunchFired = true;
                Runnable action = pendingLaunchAction;
                pendingLaunchAction = null;
                if (mc != null) {
                    mc.tell(action);
                } else {
                    action.run();
                }
                lastFrameNanos = System.nanoTime();
            }

            if (phaseT >= START_DUR) {
                phase = Phase.LOADING;
                phaseT -= START_DUR;
            }
        } else if (phase == Phase.LOADING) {
            double speed = lerp(1420.0, 2050.0, progress);
            tunnelClock += dt * speed;

            // Safety: if Minecraft entered the world without opening ReceivingLevelScreen, finish cleanly
            if (inWorldNoScreen && !worldReady) {
                noScreenInWorldTimer += dt;
                if (noScreenInWorldTimer >= 0.20) {
                    worldReady = true;
                }
            } else {
                noScreenInWorldTimer = 0.0;
            }

            boolean readyToFinish = worldReady && phaseT >= MIN_TUNNEL_CRUISE_SEC;
            if (readyToFinish) {
                rawProgress = 1.0;
            } else if (worldReady) {
                // World is already ready: glide smoothly toward 0.94 across the brief cruise window
                double cruiseFrac = sat(phaseT / MIN_TUNNEL_CRUISE_SEC);
                rawProgress = Math.max(rawProgress, lerp(0.18, 0.94, eOutCubic(cruiseFrac)));
            } else {
                // Asymptotic crawl while waiting on real network / chunk packets (never reaches 100% until real ready!)
                double ceil = seenChunkListener ? 0.92 : 0.86;
                double crawlTarget = ceil * (1.0 - Math.exp(-(phaseT + 0.35) * 0.52));
                rawProgress = Math.max(rawProgress, Math.min(ceil, crawlTarget));
            }

            double follow = readyToFinish ? 10.5 : 5.5;
            progress += (rawProgress - progress) * Math.min(1.0, dt * follow);
            if (readyToFinish && progress > 0.992) {
                progress = 1.0;
            }
            progress = clamp(progress, 0.0, 1.0);

            // Feed real loading progress to tunnel wind filter & 100 BPM arpeggio, and chime on stage completion
            if (audioEnabled) {
                HabiBootAudioEngine.getInstance().updateTunnelProgress(progress);
            }
            double acc = 0.0;
            for (int i = 0; i < WORLD_STAGES.length; i++) {
                acc += WORLD_STAGES[i].weight();
                boolean done = progress >= acc - 0.001 || progress >= 0.999;
                if (done && !stageDone[i]) {
                    stageDone[i] = true;
                    if (audioEnabled) {
                        HabiBootAudioEngine.getInstance().playTunnelStageTick(i);
                    }
                }
            }

            if (readyToFinish && progress >= 0.999) {
                enterEndPhase();
            }
        } else if (phase == Phase.END) {
            // Pre-warm live 3D world behind the 100% opaque Arrival Postmark Seal (0.00..0.46s)
            // strictly BEFORE the Apple Portal opens. Once released, endWarmupReleased latches to true
            // permanently so a mid-portal stutter can NEVER snap phaseT backwards or replay the Apple Portal!
            double holdBarrier = END_PORTAL_START - 0.02;
            if (!endWarmupReleased) {
                if (inWorldNoScreen && lastRawFrameMs > 0.0) {
                    if (lastRawFrameMs <= END_WARMUP_SMOOTH_FRAME_MS) {
                        endInWorldSmoothFrames++;
                    } else {
                        endInWorldSmoothFrames = 0;
                    }
                }
                if (phaseT >= holdBarrier) {
                    boolean shouldHold = (mc != null || forceWarmupGateForTest)
                            && endInWorldSmoothFrames < END_WARMUP_REQUIRED_FRAMES
                            && endWarmupHoldSec < END_WARMUP_MAX_HOLD_SEC;
                    if (shouldHold) {
                        phaseT = holdBarrier;
                        endWarmupHoldSec += dt;
                    } else {
                        endWarmupReleased = true;
                    }
                }
            }

            double surge = phaseT < 0.92
                    ? lerp(2050.0, 4900.0, eInCubic(sat(phaseT / 0.85)))
                    : lerp(4900.0, 920.0, eOutCubic(sat((phaseT - 0.92) / 0.85)));
            tunnelClock += dt * surge;

            if (phaseT >= END_TOTAL_DUR) {
                completeAndCleanup(mc);
            }
        }
    }

    private void enterEndPhase() {
        if (phase == Phase.END) return;
        phase = Phase.END;
        phaseT = 0.0;
        progress = 1.0;
        rawProgress = 1.0;
        endInWorldSmoothFrames = 0;
        endWarmupHoldSec = 0.0;
        endWarmupReleased = false;
        Arrays.fill(stageDone, true);
        if (!breakoutSoundFired) {
            breakoutSoundFired = true;
            if (audioEnabled) {
                HabiBootAudioEngine.getInstance().triggerTunnelBreakout();
            }
        }
    }

    private void completeAndCleanup(@Nullable Minecraft mc) {
        HabiLoadingPerfMonitor.getInstance().finishSession(
                HabiLoadingPerfMonitor.SessionKind.WORLD_ENTRY, "completed");
        resetInternalState();
        if (mc != null && mc.isSameThread()) {
            HabiBootSession.getInstance().onWorldJoined();
            HabiKineticUiController.getInstance().onWorldJoinedOrShutdown(false);
            HabiTunnelAtlas.getInstance().dispose();
            HabiKineticAtlas.getInstance().dispose();
        }
    }

    // ── Queries for Mixins & Renderer ────────────────────────────────────────

    public boolean isActive() {
        return phase != Phase.INACTIVE;
    }

    public Phase getPhase() {
        return phase;
    }

    public double getPhaseT() {
        return phaseT;
    }

    public double getTotalT() {
        return totalT;
    }

    public double getTunnelClock() {
        return tunnelClock;
    }

    public double getProgress() {
        return progress;
    }

    public double getHandoffU0() {
        return handoffU0;
    }

    public double getHandoffPx() {
        return handoffPx;
    }

    public double getHandoffPy() {
        return handoffPy;
    }

    public double getPointerX() {
        return curPx;
    }

    public double getPointerY() {
        return curPy;
    }

    public String getRouteName() {
        return routeName;
    }

    public String getRouteSub() {
        return routeSub;
    }

    public String getStatusCounter() {
        return statusCounter;
    }

    public boolean isDirectEntry() {
        return directEntry;
    }

    public double getDirectEntryFadeIn() {
        return directEntry ? HabiKineticGfx.sstep(0.0, 0.28, directEntryElapsed) : 1.0;
    }

    public double getMenuFade() {
        if (phase == Phase.START) {
            return eInCubic(sat(phaseT / 0.38));
        }
        return isActive() ? 1.0 : 0.0;
    }

    public boolean hasPendingLaunch() {
        return !pendingLaunchFired && pendingLaunchAction != null;
    }

    /**
     * True once {@code SREReceivingLevelScreen} should close. Closes right at the start of Act 3
     * ({@code END t=0.00s}) while the 100% opaque Arrival Postmark Seal covers the viewport so the
     * live 3D world absorbs first-frame Sodium/Iris/SRE shader &amp; chunk warmup invisibly before the
     * Apple Portal Iris opens at {@code t=0.48s}.
     */
    public boolean shouldCloseReceivingScreen() {
        return phase == Phase.END;
    }

    /** True when the live 3D Minecraft world is uncovered inside the expanding Apple Portal or bloom. */
    public boolean shouldRenderInWorldOverlay(@Nullable Minecraft mc) {
        if (!isActive() || mc == null) return false;
        return mc.level != null && mc.screen == null && mc.getOverlay() == null;
    }

    /** Suppresses real 2D gameplay HUD while opaque tunnel or tiny opening Apple Portal covers the screen. */
    public boolean shouldSuppressInGameHud() {
        if (!isActive()) return false;
        return phase != Phase.END || phaseT < END_HUD_REVEAL_AT;
    }

    /**
     * Smooth camera landing deceleration during Act 3 Apple Portal reveal (zooms gently from 1.13x -> 1.00x
     * matching {@code tunnel_scene.js}'s {@code _renderInGameWorld} landing momentum).
     */
    public double getLandingFovMultiplier() {
        if (phase != Phase.END || phaseT < END_PORTAL_START) return 1.0;
        double u = phaseT - END_PORTAL_START;
        double ease = eOutExpo(sat(u / 1.18));
        double zoom = lerp(1.13, 1.0, ease);
        return 1.0 / zoom;
    }

    /** Defers {@code ClientLifecycleHandler}'s JOIN atlas/audio disposal until Act 3 finishes cleanly. */
    public boolean shouldDeferJoinCleanup() {
        return isActive();
    }

    public StageState[] computeStages() {
        StageState[] out = new StageState[WORLD_STAGES.length];
        double acc = 0.0;
        for (int i = 0; i < WORLD_STAGES.length; i++) {
            StageDef d = WORLD_STAGES[i];
            double start = acc;
            acc += d.weight();
            double local = clamp((progress - start) / d.weight(), 0.0, 1.0);
            boolean done = progress >= acc - 0.001 || progress >= 0.999;
            out[i] = new StageState(d.id(), d.code(), d.en(), d.zh(), done ? 1.0 : local, done);
        }
        return out;
    }

    /** True if {@code screen} is part of the continuous world-entry chain. */
    public static boolean isAllowedEntryScreen(@Nullable Screen screen) {
        return screen instanceof ConnectScreen
                || screen instanceof ProgressScreen
                || screen instanceof GenericMessageScreen
                || screen instanceof TrainLoadingScreen
                || screen instanceof SREReceivingLevelScreen
                || screen instanceof LevelLoadingScreen
                || screen instanceof ReceivingLevelScreen;
    }

    /** True if a {@link GenericMessageScreen} is saving/quitting a world rather than entering one. */
    public static boolean isWorldSavingMessage(@Nullable Component title) {
        if (title == null) return false;
        if (title.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            return "menu.savingLevel".equals(key)
                    || "menu.savingChunks".equals(key)
                    || "connect.disconnecting".equals(key);
        }
        String raw = title.getString();
        return raw.contains("Saving") || raw.contains("保存世界") || raw.contains("正在保存");
    }

    /** True if a {@link GenericMessageScreen} is reading world data or negotiating server join. */
    public static boolean isWorldEntryMessage(@Nullable Component title) {
        if (title == null) return false;
        if (title.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            return "selectWorld.data_read".equals(key)
                    || "selectWorld.resource_load".equals(key)
                    || "connect.negotiating".equals(key)
                    || "connect.joining".equals(key)
                    || "multiplayer.downloadingTerrain".equals(key);
        }
        return false;
    }

    // ── Package-private test helpers ─────────────────────────────────────────

    void setAudioEnabledForTest(boolean enabled) {
        this.audioEnabled = enabled;
    }

    void setForceWarmupGateForTest(boolean enabled) {
        this.forceWarmupGateForTest = enabled;
    }

    void stepWithRawFrameMsForTest(double rawFrameMs, boolean inWorldNoScreen) {
        this.lastRawFrameMs = Math.max(0.0, rawFrameMs);
        step(rawFrameMs / 1000.0, inWorldNoScreen, null);
    }

    void resetForTest() {
        clearPreservedConfirmRoute();
        resetInternalState();
        this.audioEnabled = true;
        this.forceWarmupGateForTest = false;
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private void resetInternalState() {
        this.phase = Phase.INACTIVE;
        this.phaseT = 0.0;
        this.totalT = 0.0;
        this.tunnelClock = 0.0;
        this.progress = 0.0;
        this.rawProgress = 0.0;
        this.directEntry = false;
        this.directEntryElapsed = 0.0;
        this.pendingLaunchAction = null;
        this.pendingLaunchFired = false;
        this.worldReady = false;
        this.noScreenInWorldTimer = 0.0;
        this.lastChunkFull = 0;
        this.lastChunkTotal = 0;
        this.lastChunkScanMs = 0L;
        this.seenChunkListener = false;
        Arrays.fill(cueFired, false);
        Arrays.fill(stageDone, false);
        this.doorsSoundFired = false;
        this.eruptSoundFired = false;
        this.breakoutSoundFired = false;
        this.lastRawFrameMs = 0.0;
        this.endInWorldSmoothFrames = 0;
        this.endWarmupHoldSec = 0.0;
        this.endWarmupReleased = false;
    }

    static double clampDt(double rawDt) {
        if (Double.isNaN(rawDt) || rawDt <= 0.0) return 0.0001;
        return Math.min(MAX_DT, rawDt);
    }

    private static String sanitizeRouteName(@Nullable String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String clean = HabiTitleSubPanelData.sanitizeSingleLine(raw);
        if (clean.isEmpty()) return fallback;
        return clean.length() > 22 ? clean.substring(0, 22) : clean;
    }

    private static String sanitizeRouteSub(@Nullable String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String clean = HabiTitleSubPanelData.sanitizeSingleLine(raw).toUpperCase(Locale.ROOT);
        if (clean.isEmpty() || !HabiTitleSubPanelData.isPureAscii(clean)) return fallback;
        return clean.length() > 36 ? clean.substring(0, 36) : clean;
    }

    private static String formatStatusCounter(String statusText) {
        String clean = HabiTitleSubPanelData.sanitizeSingleLine(statusText).toUpperCase(Locale.ROOT);
        if (clean.isEmpty() || !HabiTitleSubPanelData.isPureAscii(clean)) {
            return "LINKING SERVER  ·  1435MM";
        }
        if (clean.length() > 20) clean = clean.substring(0, 20).stripTrailing();
        return clean + "  ·  1435MM";
    }
}
