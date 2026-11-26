package com.habitrain.core.client.title;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.mixin.SoundManagerAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 5-stem 100 BPM (Dmaj9) seamless music &amp; D-major procedural UI SFX engine ported from
 * {@code habitrain_logo_v3/audio_engine.js}.
 *
 * <p>Uses a sample-accurate 48 kHz stereo PCM software mixer that streams natively through
 * Minecraft's OpenAL {@link SoundManager} ({@link SoundSource#MASTER}) as soon as OpenAL is
 * initialized — automatically honoring Minecraft's selected output device ({@code options.soundDevice})
 * and Master Volume while staying immune to upstream SRE's {@code OptionLocker} mute on
 * {@link SoundSource#MUSIC}. During the earliest boot frames before {@code SoundEngine} starts,
 * an optional {@link SourceDataLine} bridge provides immediate {@code t = 0.0s} playback and hands
 * off sample-accurately to OpenAL with zero double playback.</p>
 */
@Environment(EnvType.CLIENT)
public final class HabiBootAudioEngine {
    public static final int SAMPLE_RATE = 48_000;
    public static final double BPM = 100.0;
    public static final double BEAT = 60.0 / BPM;       // 0.600s
    public static final double EIGHTH = BEAT / 2.0;     // 0.300s
    public static final double INTRO_TO_IDLE = 3.0;     // 5 beats
    public static final double EXIT_HANDOFF = 1.2;      // 2 beats (57,600 samples)
    public static final double IDLE_LOOP = 4.8;         // 8 beats
    public static final double MENU_LOOP = 19.2;        // 32 beats (8 bars)

    private static final int MIX_BLOCK_FRAMES = 256;    // ~5.33 ms control resolution
    private static final int LINE_BUFFER_FRAMES = 4096; // ~85.3 ms buffer for early SourceDataLine fallback
    private static final int OPENAL_CHUNK_BLOCKS = 12;  // 3,072 frames (~64 ms) per OpenAL stream buffer (4x = ~256 ms)
    private static final int OPENAL_CHUNK_FRAMES = MIX_BLOCK_FRAMES * OPENAL_CHUNK_BLOCKS;
    private static final int OPENAL_PREFILL_CHUNKS = 4; // matches Minecraft Channel.attachBufferStream -> pumpBuffers(4)
    private static final long OPENAL_LATENCY_SAMPLES = 3L * OPENAL_CHUNK_FRAMES;
    private static final long OPENAL_PROBE_INTERVAL_NANOS = 50_000_000L;
    private static final int SPECTRUM_BANDS = 16;
    private static final AudioFormat STREAM_AUDIO_FORMAT = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
    private static final ResourceLocation KINETIC_BGM_SOUND_ID =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "kinetic_boot_audio");
    private static final ResourceLocation KINETIC_SFX_SOUND_ID =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "kinetic_boot_sfx");
    private static final Set<String> SUPPRESSED_BOOT_DING_PATHS = Set.of(
            "entity.experience_orb.pickup",
            "entity.player.levelup",
            "block.note_block.bell",
            "block.note_block.chime",
            "block.note_block.pling",
            "block.note_block.harp",
            "block.bell.use",
            "block.bell.resonate",
            "block.amethyst_block.chime",
            "ui.toast.challenge_complete",
            "ui.toast.in",
            "ui.toast.out"
    );
    private static final double[] BAND_FREQS = {
            58, 86, 124, 175, 245, 340, 470, 650,
            900, 1250, 1720, 2380, 3250, 4450, 6100, 8200
    };
    private static final String AUDIO_BASE = "assets/habitrain_core/audio/boot_v3/";

    public enum State {
        STOPPED, INTRO, LOADING, DEPARTING, MENU
    }

    public enum FocusMode {
        NORMAL, MODAL, JOURNEY
    }

    enum OutputMode {
        NONE, EARLY_JAVA_SOUND, OPENAL
    }

    public record Telemetry(
            boolean enabled,
            boolean stemsReady,
            State state,
            int bpm,
            int beatIndex,
            double beatFrac,
            double beatPhase,
            double energy,
            double bass,
            float[] bars,
            boolean quantize
    ) {
        public float bar(int index) {
            if (bars == null || index < 0 || index >= bars.length) return 0.15f;
            return bars[index];
        }
    }

    private static final HabiBootAudioEngine INSTANCE = new HabiBootAudioEngine();

    public static HabiBootAudioEngine getInstance() {
        return INSTANCE;
    }

    // ── Loaded PCM stems (interleaved stereo 16-bit PCM short, 48 kHz) ────────
    private static final float INV_SHORT_SCALE = 1.0f / 32768.0f;
    private volatile short[] stemIntro;
    private volatile short[] stemIdle;
    private volatile short[] stemLoadLayer;
    private volatile short[] stemExit;
    private volatile short[] stemMenu;
    private volatile boolean stemsLoaded;
    private volatile boolean stemsFailed;
    private final AtomicBoolean loadStarted = new AtomicBoolean(false);
    private volatile CompletableFuture<Boolean> loadFuture;
    private boolean releaseStemsOnStop;

    // ── Output backends (Minecraft OpenAL primary + early Java Sound bridge) ──
    private final Object stateLock = new Object();
    private volatile OutputMode outputMode = OutputMode.NONE;
    private volatile @Nullable Minecraft lastMinecraft;
    private volatile @Nullable KineticBgmSoundInstance openAlBgmInstance;
    private volatile boolean openAlObservedReady;
    private volatile long lastOpenAlProbeNanos;

    private Thread earlyMixerThread;
    private volatile boolean earlyMixerRunning;
    private volatile boolean earlyMixerRequested;
    private volatile boolean earlyJavaSoundUnavailable;

    // ── Playback & musical clock state (guarded by stateLock) ────────────────
    private boolean userMuted;
    private boolean quantize = true;
    private boolean activeSession;
    private State state = State.STOPPED;
    private FocusMode focusMode = FocusMode.NORMAL;
    private double loadProgress;

    private long mixerSampleClock;       // monotonic sample index of the mixer
    private long loadingTimelineOriginSample;       // sample index corresponding to visual timeline t = 0.0s (may be < 0 if resynced at clock 0)
    private boolean loadingTimelineAnchored;        // true when loadingTimelineOriginSample & idleOriginSample are valid
    private long loadingSequenceStartSample = -1L;  // first sample index where active loading sequence begins
    private boolean loadingSequenceConsumed;        // true once an output backend starts pulling loading chunks
    private long idleOriginSample;       // sample index where idle beat 0 began
    private long menuOriginSample;       // sample index where menu beat 0 begins/began
    private long departStartSample = -1; // sample index where exit.wav starts

    // Audible playback clock tracking (guarded by stateLock)
    private @Nullable KineticBgmSoundInstance openAlClockOwner;
    private long openAlStreamOriginSample;
    private int openAlChunksPulled;
    private boolean openAlAudiblyPlaying;
    private long openAlAnchorSample;
    private long openAlAnchorNanos;

    private volatile @Nullable SourceDataLine activeEarlyLine;
    private long earlyLineOriginSample;
    private long earlyLineStartFramePos;
    private boolean earlyLineAudiblyPlaying;

    // Stem playback cursors (-1 = inactive)
    private StemChannel introCh;
    private StemChannel idleCh;
    private StemChannel loadCh;
    private StemChannel exitCh;
    private StemChannel menuCh;

    // Pending scheduled start if stems finish decoding right after sequence start
    private boolean pendingStartLoading;
    private double pendingStartTimelineT;
    private boolean pendingStartMenu;
    private double pendingMenuElapsed;

    // Global bus filters & smoothed volumes
    private final StereoLowpass masterBgmFilter = new StereoLowpass(20_000.0, 0.707);
    private double targetBgmFocusCutoff = 18_500.0;
    private double currentBgmFocusCutoff = 18_500.0;
    private double targetBgmFocusGain = 1.0;
    private double currentBgmFocusGain = 1.0;
    private double fadeOutGain = 1.0;
    private boolean fadingOut;

    private volatile float mcMasterVolume = 1.0f;
    private volatile float bgmVolumePref = 1.0f;
    private volatile boolean externalSoundDisabled;
    private volatile boolean loggedEarlyAudioOpen;
    private volatile boolean loggedOpenAlAttach;
    private volatile @Nullable String lastMuteReasonLogged;

    // ── Polyphonic procedural D-major SFX voices (used by early fallback) ────
    private final List<SfxVoice> sfxVoices = new ArrayList<>();

    // ── Real-time spectrum telemetry (written by active mixer, read by render) ─
    private final float[] liveBars = new float[SPECTRUM_BANDS];
    private final float[] goertzelCoeffs = new float[SPECTRUM_BANDS];
    private final float[] monoAnalysisRing = new float[MIX_BLOCK_FRAMES];
    private final float[] hannWindow = new float[MIX_BLOCK_FRAMES];
    private final float[] windowedMono = new float[MIX_BLOCK_FRAMES];
    private volatile double liveBass;
    private volatile double liveEnergy;
    private volatile boolean mixerProducingSound;

    private HabiBootAudioEngine() {
        for (int i = 0; i < SPECTRUM_BANDS; i++) {
            double omega = 2.0 * Math.PI * BAND_FREQS[i] / SAMPLE_RATE;
            goertzelCoeffs[i] = (float) (2.0 * Math.cos(omega));
        }
        for (int i = 0; i < MIX_BLOCK_FRAMES; i++) {
            hannWindow[i] = 0.5f - 0.5f * (float) Math.cos((2.0 * Math.PI * i) / (MIX_BLOCK_FRAMES - 1));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "habitrain-audio-shutdown"));
    }

    /** Starts async WAV decoding on a dedicated thread so it never stalls behind ForkJoinPool reload tasks. */
    public CompletableFuture<Boolean> preloadAsync() {
        if (loadStarted.compareAndSet(false, true)) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            loadFuture = future;
            Thread loader = new Thread(() -> {
                try {
                    short[] intro = decodeWavResourcePcm16(AUDIO_BASE + "intro.wav");
                    if (intro != null) stemIntro = intro;
                    short[] idle = decodeWavResourcePcm16(AUDIO_BASE + "idle.wav");
                    if (idle != null) stemIdle = idle;
                    short[] loadLayer = decodeWavResourcePcm16(AUDIO_BASE + "load_layer.wav");
                    if (loadLayer != null) stemLoadLayer = loadLayer;
                    short[] exit = decodeWavResourcePcm16(AUDIO_BASE + "exit.wav");
                    if (exit != null) stemExit = exit;
                    short[] menu = decodeWavResourcePcm16(AUDIO_BASE + "menu.wav");
                    if (menu != null) stemMenu = menu;
                    if (intro == null || idle == null || loadLayer == null || exit == null || menu == null) {
                        stemsFailed = true;
                        HabiTrainCore.LOGGER.warn("[HabiBootAudio] Missing one or more boot_v3 WAV stems");
                        future.complete(false);
                        return;
                    }
                    stemsLoaded = true;
                    HabiTrainCore.LOGGER.info("[HabiBootAudio] Decoded all 5 boot_v3 WAV stems (PCM16)");
                    Minecraft mcToAttach = null;
                    synchronized (stateLock) {
                        if (activeSession && isSoundEffectivelyEnabled()) {
                            if (pendingStartMenu) {
                                pendingStartMenu = false;
                                startMenuDirectLocked(pendingMenuElapsed);
                            } else if (pendingStartLoading) {
                                pendingStartLoading = false;
                                startLoadingLocked(pendingStartTimelineT, loadProgress);
                            }
                            mcToAttach = lastMinecraft;
                            if ((mcToAttach == null || !isSoundEngineLoaded(mcToAttach))
                                    && !openAlObservedReady && outputMode != OutputMode.OPENAL) {
                                ensureEarlyMixerRunningLocked();
                            }
                        }
                    }
                    if (mcToAttach != null) {
                        final Minecraft targetMc = mcToAttach;
                        try {
                            targetMc.execute(() -> {
                                lastOpenAlProbeNanos = 0L;
                                ensureOutputBackendOnRenderThread(targetMc);
                            });
                        } catch (Throwable ignored) {
                        }
                    }
                    future.complete(true);
                } catch (Throwable t) {
                    stemsFailed = true;
                    HabiTrainCore.LOGGER.warn("[HabiBootAudio] Failed to decode boot_v3 WAV stems", t);
                    future.complete(false);
                }
            }, "habitrain-boot-wav-loader");
            loader.setDaemon(true);
            loader.setPriority(Thread.NORM_PRIORITY + 1);
            loader.start();
        }
        return loadFuture;
    }

    /**
     * Initializes Minecraft's native OpenAL {@link SoundEngine} early on boot frame 0 so the boot
     * soundtrack starts immediately on the user's selected {@code options.soundDevice} without
     * waiting for {@code SoundManager.apply()} at the end of resource reload.
     */
    public boolean prewarmSoundEngine(@Nullable Minecraft mc) {
        if (mc == null || !mc.isSameThread() || mc.getSoundManager() == null) {
            return false;
        }
        lastMinecraft = mc;
        if (!isSoundEffectivelyEnabled()) {
            return false;
        }
        try {
            if (mc.getSoundManager() instanceof SoundManagerAccessor smAccess) {
                SoundEngine engine = smAccess.habitrain$getSoundEngine();
                if (engine instanceof HabiSoundEnginePrewarmAccess prewarmAccess) {
                    boolean loaded = prewarmAccess.habitrain$prewarmForKineticBoot();
                    if (loaded) {
                        openAlObservedReady = true;
                        lastOpenAlProbeNanos = 0L;
                    }
                    return loaded;
                }
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[HabiBootAudio] Failed to pre-warm Minecraft OpenAL SoundEngine: {}", t.getMessage());
        }
        return false;
    }

    private static @Nullable Boolean checkSoundEngineLoaded(@Nullable Minecraft mc) {
        if (mc == null || mc.getSoundManager() == null) {
            return null;
        }
        try {
            if (mc.getSoundManager() instanceof SoundManagerAccessor smAccess) {
                SoundEngine engine = smAccess.habitrain$getSoundEngine();
                if (engine instanceof HabiSoundEnginePrewarmAccess prewarmAccess) {
                    return prewarmAccess.habitrain$isSoundEngineLoaded();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isSoundEngineLoaded(@Nullable Minecraft mc) {
        return Boolean.TRUE.equals(checkSoundEngineLoaded(mc));
    }

    /** True while an OpenAL kinetic boot/title BGM stream is currently active. */
    public boolean isOpenAlStreamActive() {
        synchronized (stateLock) {
            return outputMode == OutputMode.OPENAL
                    && openAlBgmInstance != null
                    && !openAlBgmInstance.isMarkedClosed()
                    && activeSession
                    && state != State.STOPPED;
        }
    }

    public boolean areStemsReady() {
        return stemsLoaded || stemsFailed;
    }

    public boolean areStemsLoaded() {
        return stemsLoaded;
    }

    public float getPreloadProgress() {
        if (stemsLoaded || stemsFailed) return 1.0f;
        int done = 0;
        if (stemIntro != null) done++;
        if (stemIdle != null) done++;
        if (stemLoadLayer != null) done++;
        if (stemExit != null) done++;
        if (stemMenu != null) done++;
        return loadStarted.get() ? (0.15f + 0.85f * (done / 5.0f)) : 0.0f;
    }

    public boolean isSoundEffectivelyEnabled() {
        return !userMuted && !externalSoundDisabled
                && readKineticUiSoundPreference()
                && mcMasterVolume > 0.001f
                && bgmVolumePref > 0.001f;
    }

    public boolean isUserMuted() {
        return userMuted;
    }

    public void setUserMuted(boolean muted, double currentTimelineT, boolean inStationMenu, double stationElapsed) {
        Minecraft mc;
        synchronized (stateLock) {
            this.userMuted = muted;
            mc = this.lastMinecraft;
            if (muted) {
                clearMusicChannelsLocked();
                state = State.STOPPED;
                loadingTimelineAnchored = false;
                loadingSequenceStartSample = -1L;
                loadingSequenceConsumed = false;
                departStartSample = -1L;
                pendingStartLoading = false;
                pendingStartMenu = false;
                earlyMixerRequested = false;
            } else if (activeSession && isSoundEffectivelyEnabled()) {
                if (stemsLoaded) {
                    if (inStationMenu) {
                        startMenuDirectLocked(stationElapsed);
                    } else {
                        startLoadingLocked(currentTimelineT, loadProgress);
                    }
                } else {
                    pendingStartMenu = inStationMenu;
                    pendingStartLoading = !inStationMenu;
                    pendingMenuElapsed = stationElapsed;
                    pendingStartTimelineT = currentTimelineT;
                    state = inStationMenu ? State.MENU : (currentTimelineT < INTRO_TO_IDLE ? State.INTRO : State.LOADING);
                }
                if (mc == null && !openAlObservedReady && outputMode != OutputMode.OPENAL) {
                    ensureEarlyMixerRunningLocked();
                }
            }
        }
        if (muted) {
            stopOpenAlStream(mc);
        } else {
            lastOpenAlProbeNanos = 0L;
            ensureOutputBackendOnRenderThread(mc);
        }
    }

    public void setQuantize(boolean quantize) {
        synchronized (stateLock) {
            this.quantize = quantize;
        }
    }

    public boolean isQuantize() {
        synchronized (stateLock) {
            return quantize;
        }
    }

    /**
     * Refreshes live volume &amp; local preference flags from Minecraft options (called on render thread)
     * and keeps the active audio output backend (Minecraft OpenAL primary, early Java Sound fallback)
     * seamlessly synchronized and self-healing across device switches, reloads, and mute transitions.
     */
    public void syncMinecraftVolumes(@Nullable Minecraft mc) {
        if (mc != null) {
            lastMinecraft = mc;
        }
        if (mc != null && mc.options != null) {
            // Upstream SREClient locks SoundSource.MUSIC to 0.0 (and every other sub-category via
            // Ratatouille OptionLocker), leaving SoundSource.MASTER as the sole user-adjustable slider.
            mcMasterVolume = clamp01(mc.options.getSoundSourceVolume(SoundSource.MASTER));
        }
        // Kinetic UI soundtrack is intentionally decoupled from SREClientConfig.disableTitleScreenSound
        // ("禁音主页烦人的列车声音"), which only exists to mute upstream SRE's noisy rail ambience.
        boolean prefEnabled = readKineticUiSoundPreference();
        bgmVolumePref = readKineticUiBgmVolumePreference();
        externalSoundDisabled = !prefEnabled;

        boolean nowEnabled = isSoundEffectivelyEnabled();
        if (!nowEnabled) {
            boolean shouldStopOpenAl;
            synchronized (stateLock) {
                shouldStopOpenAl = !activeSession || userMuted || externalSoundDisabled;
                if (shouldStopOpenAl) {
                    earlyMixerRequested = false;
                }
                if (activeSession && !userMuted) {
                    String reason = !prefEnabled ? "kineticUiSoundEnabled=false"
                            : (mcMasterVolume <= 0.001f ? "SoundSource.MASTER=0" : "kineticUiBgmVolume=0");
                    if (!reason.equals(lastMuteReasonLogged)) {
                        lastMuteReasonLogged = reason;
                        HabiTrainCore.LOGGER.info("[HabiBootAudio] Kinetic BGM muted ({})", reason);
                    }
                }
            }
            if (shouldStopOpenAl) {
                stopOpenAlStream(mc);
            }
            return;
        }

        lastMuteReasonLogged = null;
        boolean needOutput;
        synchronized (stateLock) {
            if (activeSession && !fadingOut && !userMuted && state != State.STOPPED) {
                if (!stemsLoaded) {
                    if (state == State.MENU || state == State.DEPARTING) {
                        pendingStartMenu = true;
                        pendingStartLoading = false;
                    } else {
                        pendingStartLoading = true;
                        pendingStartMenu = false;
                    }
                } else if (!hasActiveMusicChannelLocked()) {
                    if (state == State.MENU || state == State.DEPARTING) {
                        startMenuDirectLocked(pendingMenuElapsed);
                    } else {
                        startLoadingLocked(pendingStartTimelineT, loadProgress);
                    }
                }
                needOutput = stemsLoaded || pendingStartLoading || pendingStartMenu;
            } else {
                needOutput = false;
            }
        }
        if (needOutput) {
            ensureOutputBackendOnRenderThread(mc);
        }
    }

    /** Computes early-JavaSound fallback BGM gain (includes Master volume because OpenAL isn't in path). */
    static double computeBgmVolume(boolean soundEnabled, float masterVolume) {
        return computeBgmVolume(soundEnabled, masterVolume, 1.0f);
    }

    static double computeBgmVolume(boolean soundEnabled, float masterVolume, float bgmPrefVolume) {
        return soundEnabled ? clamp01(masterVolume) * clamp01(bgmPrefVolume) * 0.85 : 0.0;
    }

    /** Computes OpenAL stream BGM gain (OpenAL Listener already scales by SoundSource.MASTER). */
    static double computeOpenAlBgmGain(boolean soundEnabled, float bgmPrefVolume) {
        return soundEnabled ? clamp01(bgmPrefVolume) * 0.85 : 0.0;
    }

    /** Computes early-JavaSound fallback SFX gain (includes Master volume). */
    static double computeSfxVolume(boolean soundEnabled, float masterVolume) {
        return computeSfxVolume(soundEnabled, masterVolume, 1.0f);
    }

    static double computeSfxVolume(boolean soundEnabled, float masterVolume, float bgmPrefVolume) {
        return soundEnabled ? clamp01(masterVolume) * clamp01(bgmPrefVolume) * 0.85 : 0.0;
    }

    /** Computes OpenAL one-shot SFX gain (OpenAL Listener already scales by SoundSource.MASTER). */
    static double computeOpenAlSfxGain(boolean soundEnabled, float bgmPrefVolume) {
        return soundEnabled ? clamp01(bgmPrefVolume) * 0.85 : 0.0;
    }

    /**
     * Identifies external loading-complete "ding" chimes, upstream train title ambience, or
     * vanilla music injected via {@code SoundManager} during Kinetic boot/title takeover.
     */
    public static boolean shouldSuppressExternalBootSound(
            @Nullable ResourceLocation soundId,
            @Nullable SoundSource source,
            boolean blockingVanillaMusic
    ) {
        if (soundId == null) return false;
        String namespace = soundId.getNamespace();
        String path = soundId.getPath().toLowerCase(Locale.ROOT);
        if ("habitrain_core".equals(namespace)) return false;
        if ("ui.button.click".equals(path)) return false;
        if (source == SoundSource.MUSIC && blockingVanillaMusic) return true;
        if (SUPPRESSED_BOOT_DING_PATHS.contains(path)) return true;
        return path.contains("ambient.train.outside")
                || path.contains("ambient_train_outside")
                || path.contains("loaded_chime")
                || path.equals("ding")
                || path.endsWith(".ding")
                || path.endsWith("_ding")
                || path.contains("load_complete");
    }

    /** True while our kinetic title/boot audio owns the client BGM slot (suppresses vanilla MusicManager). */
    public boolean isBlockingVanillaMusic() {
        synchronized (stateLock) {
            return activeSession && !fadingOut && state != State.STOPPED && isSoundEffectivelyEnabled();
        }
    }

    public void setMenuFocusMode(FocusMode mode) {
        synchronized (stateLock) {
            this.focusMode = mode == null ? FocusMode.NORMAL : mode;
            this.targetBgmFocusCutoff = switch (this.focusMode) {
                case MODAL -> 950.0;
                case JOURNEY -> 20_000.0;
                case NORMAL -> 18_500.0;
            };
            this.targetBgmFocusGain = this.focusMode == FocusMode.MODAL ? 0.62 : 1.0;
        }
    }

    /** Begins or resyncs the loading sequence (intro -> idle + load_layer). */
    public void startLoadingSequence(double timelineT, double progress) {
        preloadAsync();
        // On a fresh t = 0.0 start (initial boot or R replay), flush any stale OpenAL stream
        // first so previously queued menu.wav buffers never delay intro.wav by ~256 ms.
        if (timelineT <= 0.001) {
            Minecraft mcToFlush;
            boolean hasExistingOpenAl;
            synchronized (stateLock) {
                mcToFlush = this.lastMinecraft;
                hasExistingOpenAl = this.openAlBgmInstance != null;
            }
            if (hasExistingOpenAl) {
                stopOpenAlStream(mcToFlush);
            }
        }
        Minecraft mc;
        boolean shouldEnsureOutput = false;
        synchronized (stateLock) {
            mc = this.lastMinecraft;
            activeSession = true;
            fadingOut = false;
            fadeOutGain = 1.0;
            loadProgress = clamp01(progress);
            pendingStartTimelineT = timelineT;
            if (!isSoundEffectivelyEnabled()) {
                clearMusicChannelsLocked();
                loadingTimelineAnchored = false;
                loadingSequenceStartSample = -1L;
                loadingSequenceConsumed = false;
                departStartSample = -1L;
                pendingStartLoading = false;
                pendingStartMenu = false;
                state = timelineT < INTRO_TO_IDLE ? State.INTRO : State.LOADING;
                return;
            }
            if (!stemsLoaded) {
                pendingStartLoading = true;
                pendingStartMenu = false;
                loadingTimelineAnchored = false;
                loadingSequenceStartSample = -1L;
                loadingSequenceConsumed = false;
                departStartSample = -1L;
                state = timelineT < INTRO_TO_IDLE ? State.INTRO : State.LOADING;
                if (mc == null && !openAlObservedReady && outputMode != OutputMode.OPENAL) {
                    ensureEarlyMixerRunningLocked();
                }
                shouldEnsureOutput = true;
            } else {
                startLoadingLocked(timelineT, progress);
                if (mc == null && !openAlObservedReady && outputMode != OutputMode.OPENAL) {
                    ensureEarlyMixerRunningLocked();
                }
                shouldEnsureOutput = true;
            }
        }
        if (shouldEnsureOutput) {
            lastOpenAlProbeNanos = 0L;
            ensureOutputBackendOnRenderThread(mc);
        }
    }

    private void startLoadingLocked(double timelineT, double progress) {
        clearMusicChannelsLocked();
        this.loadProgress = clamp01(progress);
        this.pendingStartLoading = false;
        this.pendingStartMenu = false;
        this.fadingOut = false;
        this.releaseStemsOnStop = false;
        this.fadeOutGain = 1.0;
        this.departStartSample = -1L;

        long leadSamples = (timelineT <= 0.001 || openAlClockOwner == null)
                ? 0L : Math.round(0.015 * SAMPLE_RATE);
        long now = mixerSampleClock + leadSamples;
        long tSamples = Math.max(0L, Math.round(timelineT * SAMPLE_RATE));
        long timelineAnchor = now - tSamples;
        long introToIdleSamples = Math.round(INTRO_TO_IDLE * SAMPLE_RATE);
        this.loadingTimelineOriginSample = timelineAnchor;
        this.loadingTimelineAnchored = true;
        this.loadingSequenceStartSample = now;
        this.loadingSequenceConsumed = false;
        this.idleOriginSample = timelineAnchor + introToIdleSamples;
        this.state = timelineT < INTRO_TO_IDLE ? State.INTRO : State.LOADING;

        // 1) intro.wav
        if (stemIntro != null) {
            long introFrames = stemIntro.length / 2L;
            if (tSamples < introFrames - SAMPLE_RATE / 20) {
                introCh = new StemChannel(stemIntro, false);
                introCh.scheduleStart(now, tSamples, 1.0, 1.0, 0, 20_000.0);
            }
        }

        // 2) idle.wav (starts at exact t = 3.0s, loops seamlessly)
        long idleFrames = stemIdle.length / 2L;
        long waitIdle = Math.max(0L, introToIdleSamples - tSamples);
        long idleStart = now + waitIdle;
        long idleOffset = tSamples > introToIdleSamples
                ? ((tSamples - introToIdleSamples) % idleFrames) : 0L;
        long idleRamp = timelineT <= INTRO_TO_IDLE
                ? Math.round(0.45 * SAMPLE_RATE)
                : Math.round(0.18 * SAMPLE_RATE);
        idleCh = new StemChannel(stemIdle, true);
        idleCh.scheduleStart(idleStart, idleOffset, 0.0001, 1.0, idleRamp, 18_000.0);

        // 3) load_layer.wav (strictly phase-locked to idle.wav)
        if (stemLoadLayer != null) {
            double initGain = calcLoadLayerGain(this.loadProgress);
            double initCutoff = calcLoadLayerCutoff(this.loadProgress);
            loadCh = new StemChannel(stemLoadLayer, true);
            loadCh.scheduleStart(idleStart, idleOffset, 0.0001, initGain,
                    Math.round(0.50 * SAMPLE_RATE), initCutoff);
        }
    }

    /** Continuously updates vertical dynamic mix of {@code load_layer.wav} from real loading progress. */
    public void updateLoadingProgress(double progress, double timelineT) {
        synchronized (stateLock) {
            this.loadProgress = clamp01(progress);
            this.pendingStartTimelineT = timelineT;
            if (state == State.INTRO && timelineT >= INTRO_TO_IDLE) {
                state = State.LOADING;
            }
            if (state != State.INTRO && state != State.LOADING) return;
            if (loadCh != null) {
                loadCh.setSmoothTarget(calcLoadLayerGain(this.loadProgress),
                        calcLoadLayerCutoff(this.loadProgress));
            }
        }
    }

    /** Computes exact delay (in seconds, <= 0.30s) to the next 100 BPM eighth-note grid boundary. */
    public double computeQuantizeDelay(double currentTimelineT) {
        synchronized (stateLock) {
            if (!quantize) return 0.0;
        }
        return computeGridDelayFromRelative(Math.max(0.0, currentTimelineT - INTRO_TO_IDLE));
    }

    private static double computeGridDelayFromRelative(double relSec) {
        double rem = Math.max(0.0, relSec) % EIGHTH;
        if (rem < 0.035) return 0.0;
        return EIGHTH - rem;
    }

    /**
     * Schedules the beat-quantized departure bridge:
     * {@code idle + load_layer} lowpass tunnel fadeout -> {@code exit.wav} ->
     * sample-exact {@code +1.20s} handoff into {@code menu.wav} beat 0!
     *
     * @return audible delay in seconds until {@code exit.wav} reaches the speakers
     *         (including any active OpenAL stream queue latency, so visual {@code exitAt = t + delaySec}
     *         lands on the exact downbeat)
     */
    public double scheduleSeamlessDeparture(double currentTimelineT) {
        double visualDelaySec;
        boolean playLatch;
        Minecraft mc;
        boolean shouldEnsureOutput = false;
        long nowNanos = System.nanoTime();
        synchronized (stateLock) {
            mc = this.lastMinecraft;
            double mixerDelaySec;
            if (!quantize) {
                mixerDelaySec = 0.0;
            } else if (isOutputActiveLocked() && mixerProducingSound && stemsLoaded
                    && loadingTimelineAnchored && mixerSampleClock >= idleOriginSample) {
                double liveRel = (mixerSampleClock - idleOriginSample) / (double) SAMPLE_RATE;
                mixerDelaySec = computeGridDelayFromRelative(liveRel);
            } else {
                mixerDelaySec = computeGridDelayFromRelative(Math.max(0.0, currentTimelineT - INTRO_TO_IDLE));
            }
            activeSession = true;
            fadingOut = false;
            fadeOutGain = 1.0;
            state = State.DEPARTING;
            pendingMenuElapsed = 0.0;
            if (!isSoundEffectivelyEnabled() || !stemsLoaded) {
                pendingStartMenu = true;
                pendingStartLoading = false;
                this.departStartSample = -1L;
                visualDelaySec = mixerDelaySec;
            } else {
                if (mc == null && !openAlObservedReady && outputMode != OutputMode.OPENAL) {
                    ensureEarlyMixerRunningLocked();
                }
                long now = mixerSampleClock;
                long tDepart = now + Math.round((mixerDelaySec + 0.008) * SAMPLE_RATE);
                long tMenuStart = tDepart + Math.round(EXIT_HANDOFF * SAMPLE_RATE);
                this.departStartSample = tDepart;
                this.menuOriginSample = tMenuStart;

                // 1) Tunnel lowpass sweep fade-out on idle & load_layer
                if (idleCh != null) {
                    idleCh.scheduleFadeOut(tDepart, Math.round(0.44 * SAMPLE_RATE), 220.0);
                }
                if (loadCh != null) {
                    loadCh.scheduleFadeOut(tDepart, Math.round(0.34 * SAMPLE_RATE), 320.0);
                }

                // 2) exit.wav at exact tDepart
                if (stemExit != null) {
                    exitCh = new StemChannel(stemExit, false);
                    exitCh.scheduleStart(tDepart, 0, 1.0, 1.0, 0, 20_000.0);
                }

                // 3) menu.wav at exact tMenuStart (= tDepart + 57,600 samples)
                if (stemMenu != null) {
                    menuCh = new StemChannel(stemMenu, true);
                    menuCh.scheduleStart(tMenuStart, 0, 0.0001, 0.95,
                            Math.round(0.32 * SAMPLE_RATE), 2_600.0);
                    menuCh.scheduleCutoffRamp(tMenuStart, Math.round(0.85 * SAMPLE_RATE), 19_500.0);
                }
                long heard = getEstimatedHeardSampleLocked(nowNanos);
                visualDelaySec = heard >= 0L
                        ? Math.max(0.0, (tDepart - heard) / (double) SAMPLE_RATE)
                        : mixerDelaySec;
                shouldEnsureOutput = true;
            }
            playLatch = visualDelaySec > 0.04;
        }
        if (playLatch) {
            playTicketLatch();
        }
        if (shouldEnsureOutput) {
            ensureOutputBackendOnRenderThread(mc);
        }
        return visualDelaySec;
    }

    /**
     * Guarantees Station 01 looping BGM is active upon reaching {@code Phase.STATION_01},
     * seamlessly preserving any already-running departure handoff into {@code menu.wav}.
     */
    public void ensureMenuPlaying(double menuElapsedSec) {
        synchronized (stateLock) {
            if (menuCh != null && menuCh.active && !fadingOut) {
                state = State.MENU;
                return;
            }
        }
        startMenuDirect(menuElapsedSec);
    }

    /** Directly enters Station 01 looping BGM (used when returning to title screen or unmuting). */
    public void startMenuDirect(double menuElapsedSec) {
        preloadAsync();
        Minecraft mc;
        boolean shouldEnsureOutput = false;
        synchronized (stateLock) {
            mc = this.lastMinecraft;
            activeSession = true;
            fadingOut = false;
            releaseStemsOnStop = false;
            fadeOutGain = 1.0;
            pendingMenuElapsed = menuElapsedSec;
            if (!isSoundEffectivelyEnabled()) {
                clearMusicChannelsLocked();
                pendingStartMenu = false;
                pendingStartLoading = false;
                state = State.MENU;
                return;
            }
            if (!stemsLoaded) {
                pendingStartMenu = true;
                pendingStartLoading = false;
                state = State.MENU;
                if (mc == null && !openAlObservedReady && outputMode != OutputMode.OPENAL) {
                    ensureEarlyMixerRunningLocked();
                }
                shouldEnsureOutput = true;
            } else {
                startMenuDirectLocked(menuElapsedSec);
                if (mc == null && !openAlObservedReady && outputMode != OutputMode.OPENAL) {
                    ensureEarlyMixerRunningLocked();
                }
                shouldEnsureOutput = true;
            }
        }
        if (shouldEnsureOutput) {
            lastOpenAlProbeNanos = 0L;
            ensureOutputBackendOnRenderThread(mc);
        }
    }

    private void startMenuDirectLocked(double menuElapsedSec) {
        if (state == State.MENU && menuCh != null && menuCh.active && !fadingOut) {
            return; // already playing Station 01 BGM seamlessly
        }
        clearMusicChannelsLocked();
        this.pendingStartMenu = false;
        this.pendingStartLoading = false;
        this.fadingOut = false;
        this.fadeOutGain = 1.0;
        long now = mixerSampleClock + Math.round(0.015 * SAMPLE_RATE);
        long menuFrames = stemMenu.length / 2L;
        long offset = Math.floorMod(Math.round(menuElapsedSec * SAMPLE_RATE), menuFrames);
        this.menuOriginSample = now - offset;
        this.state = State.MENU;
        menuCh = new StemChannel(stemMenu, true);
        menuCh.scheduleStart(now, offset, 0.0001, 0.95, Math.round(0.45 * SAMPLE_RATE), 19_500.0);
    }

    /** Smoothly fades out and releases session audio (when entering a world or vanilla TitleScreen). */
    public void fadeOutAndStop() {
        synchronized (stateLock) {
            if (!activeSession && state == State.STOPPED) return;
            fadingOut = true;
            pendingStartLoading = false;
            pendingStartMenu = false;
        }
    }

    /** Fades out and drops decoded boot PCM stems once playback finishes so gameplay heap stays lean. */
    public void fadeOutAndReleaseStems() {
        synchronized (stateLock) {
            releaseStemsOnStop = true;
            if (!activeSession && state == State.STOPPED) {
                releaseStemsLocked();
                return;
            }
            fadingOut = true;
            pendingStartLoading = false;
            pendingStartMenu = false;
        }
    }

    private void releaseStemsLocked() {
        clearMusicChannelsLocked();
        stemIntro = null;
        stemIdle = null;
        stemLoadLayer = null;
        stemExit = null;
        stemMenu = null;
        stemsLoaded = false;
        stemsFailed = false;
        loadFuture = null;
        loadStarted.set(false);
        releaseStemsOnStop = false;
    }

    /** Immediately stops all music &amp; SFX. */
    public void stopImmediately() {
        Minecraft mc;
        synchronized (stateLock) {
            mc = this.lastMinecraft;
            activeSession = false;
            fadingOut = false;
            fadeOutGain = 1.0;
            loadingTimelineAnchored = false;
            loadingSequenceStartSample = -1L;
            loadingSequenceConsumed = false;
            departStartSample = -1L;
            pendingStartLoading = false;
            pendingStartMenu = false;
            earlyMixerRequested = false;
            clearMusicChannelsLocked();
            sfxVoices.clear();
            state = State.STOPPED;
        }
        stopOpenAlStream(mc);
    }

    public void shutdown() {
        stopImmediately();
        synchronized (stateLock) {
            releaseStemsLocked();
        }
        earlyMixerRunning = false;
        earlyMixerRequested = false;
        Thread t;
        synchronized (stateLock) {
            t = earlyMixerThread;
            earlyMixerThread = null;
        }
        if (t != null) {
            t.interrupt();
        }
    }

    // ── D-Major procedural UI SFX ────────────────────────────────────────────

    public void playStageTick(int stageIdx) {
        double[] notes = {587.33, 659.25, 739.99, 880.0, 1174.66};
        double f = notes[Math.floorMod(stageIdx, notes.length)];
        playSfxSpecs(List.of(
                new SfxSpec(f, f * 1.005, 0.14, 0.10, Wave.TRIANGLE, (stageIdx % 2 == 1) ? 0.25 : -0.25, 0.0)
        ));
    }

    public void playReadyChime() {
        double[] notes = {880.0, 1174.66, 1479.98};
        List<SfxSpec> specs = new ArrayList<>(6);
        for (int i = 0; i < notes.length; i++) {
            double f = notes[i];
            double delay = i * 0.065;
            double pan = (i - 1) * 0.28;
            specs.add(new SfxSpec(f, f, 0.55, 0.11 - i * 0.015, Wave.SINE, pan, delay));
            specs.add(new SfxSpec(f * 2.0, f * 2.0, 0.24, 0.03, Wave.TRIANGLE, pan, delay));
        }
        playSfxSpecs(specs);
    }

    public void playTicketLatch() {
        playSfxSpecs(List.of(
                new SfxSpec(2400.0, 920.0, 0.045, 0.11, Wave.TRIANGLE, 0.0, 0.0),
                new SfxSpec(1174.66, 1479.98, 0.09, 0.07, Wave.SINE, 0.15, 0.0)
        ));
    }

    public void playHover(int idx) {
        double[] scale = {587.33, 659.25, 739.99, 880.0, 987.77};
        double f = scale[Math.floorMod(idx, scale.length)];
        playSfxSpecs(List.of(
                new SfxSpec(f * 2.0, f * 1.98, 0.055, 0.048, Wave.TRIANGLE, -0.2, 0.0)
        ));
    }

    public void playSelect() {
        playSfxSpecs(List.of(
                new SfxSpec(739.99, 739.99, 0.22, 0.11, Wave.TRIANGLE, -0.15, 0.0),
                new SfxSpec(1174.66, 1174.66, 0.34, 0.12, Wave.SINE, 0.15, 0.055)
        ));
    }

    public void playBack() {
        playSfxSpecs(List.of(
                new SfxSpec(880.0, 587.33, 0.14, 0.08, Wave.TRIANGLE, 0.0, 0.0)
        ));
    }

    private void playSfxSpecs(List<SfxSpec> specs) {
        if (specs.isEmpty() || !isSoundEffectivelyEnabled()) return;

        // Low-latency path: when Minecraft OpenAL is active, dispatch procedural UI SFX directly
        // as a compact one-shot OpenAL SoundInstance (<10 ms latency, exact selected audio device).
        Minecraft mc = lastMinecraft;
        if (openAlObservedReady && mc != null && mc.isSameThread() && mc.getSoundManager() != null) {
            double gain = computeOpenAlSfxGain(true, bgmVolumePref) * 0.92;
            if (gain > 0.0005) {
                byte[] pcm = renderSfxPcm(specs, gain);
                if (pcm.length > 0) {
                    mc.getSoundManager().play(new KineticOneShotSfxSoundInstance(pcm));
                }
            }
            return;
        }

        // Pre-OpenAL early boot path: mix directly into the SourceDataLine stream.
        synchronized (stateLock) {
            if (earlyJavaSoundUnavailable && outputMode != OutputMode.OPENAL) return;
            if (outputMode != OutputMode.OPENAL) {
                ensureEarlyMixerRunningLocked();
            }
            for (SfxSpec spec : specs) {
                if (sfxVoices.size() >= 24) {
                    sfxVoices.remove(0);
                }
                long start = mixerSampleClock + Math.round(spec.delaySec * SAMPLE_RATE);
                long durSamples = Math.max(64L, Math.round(spec.durSec * SAMPLE_RATE));
                sfxVoices.add(new SfxVoice(start, durSamples, spec.f0, spec.f1, spec.amp, spec.wave, spec.pan));
            }
        }
    }

    /** Renders one or more procedural D-major tone specs into stereo 48 kHz 16-bit LE PCM bytes. */
    static byte[] renderSfxPcm(List<SfxSpec> specs, double gain) {
        if (specs == null || specs.isEmpty() || gain <= 0.0005) return new byte[0];
        long totalFrames = 0L;
        List<SfxVoice> voices = new ArrayList<>(specs.size());
        for (SfxSpec spec : specs) {
            long start = Math.max(0L, Math.round(spec.delaySec * SAMPLE_RATE));
            long dur = Math.max(64L, Math.round(spec.durSec * SAMPLE_RATE));
            voices.add(new SfxVoice(start, dur, spec.f0, spec.f1, spec.amp, spec.wave, spec.pan));
            totalFrames = Math.max(totalFrames, start + dur);
        }
        int frames = (int) Math.min(SAMPLE_RATE * 2L, totalFrames);
        if (frames <= 0) return new byte[0];

        int blocks = (frames + MIX_BLOCK_FRAMES - 1) / MIX_BLOCK_FRAMES;
        int paddedFrames = blocks * MIX_BLOCK_FRAMES;
        byte[] pcm = new byte[paddedFrames * 4];
        float[] outL = new float[MIX_BLOCK_FRAMES];
        float[] outR = new float[MIX_BLOCK_FRAMES];

        for (int b = 0; b < blocks; b++) {
            for (int i = 0; i < MIX_BLOCK_FRAMES; i++) {
                outL[i] = 0f;
                outR[i] = 0f;
            }
            long blockStart = (long) b * MIX_BLOCK_FRAMES;
            for (SfxVoice v : voices) {
                v.renderBlock(blockStart, outL, outR);
            }
            int byteBase = b * MIX_BLOCK_FRAMES * 4;
            for (int i = 0; i < MIX_BLOCK_FRAMES; i++) {
                double l = softLimit(outL[i] * gain);
                double r = softLimit(outR[i] * gain);
                short sl = (short) Math.max(-32767, Math.min(32767, Math.round(l * 32767.0)));
                short sr = (short) Math.max(-32767, Math.min(32767, Math.round(r * 32767.0)));
                int p = byteBase + i * 4;
                pcm[p] = (byte) (sl & 0xFF);
                pcm[p + 1] = (byte) ((sl >>> 8) & 0xFF);
                pcm[p + 2] = (byte) (sr & 0xFF);
                pcm[p + 3] = (byte) ((sr >>> 8) & 0xFF);
            }
        }
        return pcm;
    }

    // ── Real-time music, audible clock & spectrum telemetry ──────────────────

    /**
     * Returns {@code true} while the loading sequence expects audio to play and an output backend
     * is expected to start audibly soon (so the visual timeline should hold at {@code t = 0.0s}
     * rather than running ahead of the opening hit in {@code intro.wav}).
     */
    public boolean shouldWaitForAudibleBootStart() {
        long nowNanos = System.nanoTime();
        synchronized (stateLock) {
            if (!activeSession || fadingOut || !isSoundEffectivelyEnabled() || stemsFailed) {
                return false;
            }
            if (state != State.INTRO && state != State.LOADING) {
                return false;
            }
            long heard = getEstimatedHeardSampleLocked(nowNanos);
            if (heard >= 0L && loadingTimelineAnchored && loadingSequenceStartSample >= 0L
                    && heard >= loadingSequenceStartSample) {
                return false;
            }
            if (!stemsLoaded) {
                return true;
            }
            return openAlObservedReady
                    || outputMode == OutputMode.OPENAL
                    || isSoundEngineLoaded(lastMinecraft)
                    || !earlyJavaSoundUnavailable;
        }
    }

    /**
     * Returns the exact audible loading timeline position in seconds (&gt;= 0.0) once the active
     * output backend has actually begun playing the current loading sequence, or {@code -1.0}
     * while audio is muted, decoding, or still filling its initial playback buffer.
     */
    public double getAudibleLoadingTimelineSeconds() {
        long nowNanos = System.nanoTime();
        synchronized (stateLock) {
            if (!activeSession || fadingOut || !isSoundEffectivelyEnabled() || !stemsLoaded) {
                return -1.0;
            }
            if (state != State.INTRO && state != State.LOADING) {
                return -1.0;
            }
            if (!loadingTimelineAnchored || loadingSequenceStartSample < 0L) {
                return -1.0;
            }
            long heard = getEstimatedHeardSampleLocked(nowNanos);
            if (heard < 0L || heard < loadingSequenceStartSample) {
                return -1.0;
            }
            return Math.max(0.0, (heard - loadingTimelineOriginSample) / (double) SAMPLE_RATE);
        }
    }

    /**
     * Returns the exact audible seconds elapsed relative to {@code exit.wav} start (negative during
     * beat quantization wait, {@code 0.0} when {@code exit.wav} hits the speakers, {@code 1.20}
     * when {@code menu.wav} beat 0 hits the speakers), or {@link Double#NaN} if no audible clock
     * is currently active.
     */
    public double getAudibleDepartureElapsedSeconds() {
        long nowNanos = System.nanoTime();
        synchronized (stateLock) {
            if (!activeSession || fadingOut || !isSoundEffectivelyEnabled() || !stemsLoaded) {
                return Double.NaN;
            }
            if (departStartSample < 0L) {
                return Double.NaN;
            }
            long heard = getEstimatedHeardSampleLocked(nowNanos);
            if (heard < 0L) {
                return Double.NaN;
            }
            return (heard - departStartSample) / (double) SAMPLE_RATE;
        }
    }

    /**
     * Estimates the exact sample index currently leaving the active output backend, bounded strictly
     * by the hardware/OpenAL queue so stalls never cause runaway drift. Caller must hold {@link #stateLock}.
     */
    private long getEstimatedHeardSampleLocked(long nowNanos) {
        if (outputMode == OutputMode.OPENAL && openAlAudiblyPlaying) {
            long elapsed = Math.max(0L, Math.round(((nowNanos - openAlAnchorNanos) / 1_000_000_000.0) * SAMPLE_RATE));
            long predicted = openAlAnchorSample + elapsed;
            long minHeard = Math.max(openAlStreamOriginSample,
                    mixerSampleClock - (long) OPENAL_PREFILL_CHUNKS * OPENAL_CHUNK_FRAMES);
            long maxHeard = mixerSampleClock;
            if (predicted < minHeard) {
                openAlAnchorSample = minHeard;
                openAlAnchorNanos = nowNanos;
                predicted = minHeard;
            } else if (predicted > maxHeard) {
                openAlAnchorSample = maxHeard;
                openAlAnchorNanos = nowNanos;
                predicted = maxHeard;
            }
            return predicted;
        }
        if (outputMode == OutputMode.EARLY_JAVA_SOUND && earlyMixerRunning) {
            SourceDataLine line = activeEarlyLine;
            if (line != null && line.isOpen()) {
                long pos = line.getLongFramePosition() - earlyLineStartFramePos;
                if (pos > 0L) {
                    earlyLineAudiblyPlaying = true;
                }
                if (earlyLineAudiblyPlaying) {
                    return Math.min(mixerSampleClock, earlyLineOriginSample + Math.max(0L, pos));
                }
            }
        }
        return -1L;
    }

    public Telemetry getTelemetry(double timelineT, double exitAt) {
        State currentState;
        boolean q;
        boolean enabledNow = isSoundEffectivelyEnabled();
        double beatPhase;
        double energy;
        double bass;
        float[] bars = new float[SPECTRUM_BANDS];
        long nowNanos = System.nanoTime();

        synchronized (stateLock) {
            if (state == State.DEPARTING && menuOriginSample > 0 && mixerSampleClock >= menuOriginSample) {
                state = State.MENU;
            }
            currentState = state;
            q = quantize;
            if (enabledNow && isOutputActiveLocked() && mixerProducingSound && stemsLoaded) {
                long origin = (state == State.MENU) ? menuOriginSample : idleOriginSample;
                long heardSample = getEstimatedHeardSampleLocked(nowNanos);
                if (heardSample < 0L) {
                    heardSample = outputMode == OutputMode.OPENAL
                            ? Math.max(origin, mixerSampleClock - OPENAL_LATENCY_SAMPLES)
                            : mixerSampleClock;
                }
                double elapsed = Math.max(0.0, (heardSample - origin) / (double) SAMPLE_RATE);
                beatPhase = (elapsed / BEAT) % 8.0;
                energy = liveEnergy;
                bass = liveBass;
                System.arraycopy(liveBars, 0, bars, 0, SPECTRUM_BANDS);
            } else {
                // Silent/fallback deterministic 100 BPM beat clock
                double rel = (!Double.isInfinite(exitAt) && timelineT >= exitAt + EXIT_HANDOFF)
                        ? (timelineT - (exitAt + EXIT_HANDOFF))
                        : Math.max(0.0, timelineT - INTRO_TO_IDLE);
                beatPhase = (rel / BEAT) % 8.0;
                double frac = beatPhase % 1.0;
                bass = Math.exp(-frac * 4.5) * (((int) beatPhase) % 2 == 0 ? 0.85 : 0.45);
                energy = 0.25 + 0.40 * Math.exp(-frac * 3.2);
                for (int k = 0; k < SPECTRUM_BANDS; k++) {
                    double wave = 0.32 + 0.32 * Math.sin(rel * 4.2 + k * 0.65)
                            + 0.34 * bass * Math.exp(-Math.abs(k - 4) * 0.25);
                    bars[k] = (float) clamp(wave, 0.08, 1.0);
                }
            }
        }
        return new Telemetry(
                enabledNow, stemsLoaded, currentState, (int) BPM,
                ((int) Math.floor(beatPhase)) & 7,
                beatPhase - Math.floor(beatPhase),
                beatPhase, energy, bass, bars, q
        );
    }

    // ── Output backend orchestration (Minecraft OpenAL + early Java Sound) ───

    private void ensureOutputBackendOnRenderThread(@Nullable Minecraft mc) {
        if (mc == null || !mc.isSameThread() || mc.getSoundManager() == null) {
            return;
        }
        if (!isSoundEffectivelyEnabled()) {
            return;
        }
        boolean sessionActive;
        boolean readyToAttach;
        synchronized (stateLock) {
            sessionActive = activeSession && !userMuted && state != State.STOPPED;
            readyToAttach = sessionActive && (stemsLoaded || hasActiveMusicChannelLocked());
        }
        if (!readyToAttach) {
            return;
        }

        SoundManager soundManager = mc.getSoundManager();
        KineticBgmSoundInstance existing = openAlBgmInstance;
        if (existing != null) {
            if (!existing.isMarkedClosed() && soundManager.isActive(existing)) {
                synchronized (stateLock) {
                    openAlObservedReady = true;
                    outputMode = OutputMode.OPENAL;
                    earlyMixerRequested = false;
                }
                return;
            }
            // Existing OpenAL stream ended (e.g. SoundEngine.reload on device switch / F3+T, or lag stall).
            existing.markClosed();
            synchronized (stateLock) {
                if (openAlBgmInstance == existing) {
                    openAlBgmInstance = null;
                    openAlClockOwner = null;
                    openAlAudiblyPlaying = false;
                    openAlChunksPulled = 0;
                    if (outputMode == OutputMode.OPENAL) {
                        outputMode = OutputMode.NONE;
                    }
                    mixerProducingSound = false;
                }
            }
            lastOpenAlProbeNanos = 0L;
        }

        Boolean engineReadyState = checkSoundEngineLoaded(mc);
        if (Boolean.FALSE.equals(engineReadyState) && !openAlObservedReady) {
            // Fast path: SoundEngine hasn't opened OpenAL yet — avoid allocating probe instances every frame
            if (!earlyJavaSoundUnavailable) {
                synchronized (stateLock) {
                    ensureEarlyMixerRunningLocked();
                }
            }
            return;
        }

        boolean engineReady = Boolean.TRUE.equals(engineReadyState);
        long nowNanos = System.nanoTime();
        if (!engineReady && nowNanos - lastOpenAlProbeNanos < OPENAL_PROBE_INTERVAL_NANOS) {
            if (!openAlObservedReady && !earlyJavaSoundUnavailable) {
                synchronized (stateLock) {
                    ensureEarlyMixerRunningLocked();
                }
            }
            return;
        }
        lastOpenAlProbeNanos = nowNanos;

        KineticBgmSoundInstance candidate = new KineticBgmSoundInstance(this);
        synchronized (stateLock) {
            openAlBgmInstance = candidate;
        }
        try {
            soundManager.play(candidate);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[HabiBootAudio] Failed to submit OpenAL BGM stream: {}", t.getMessage());
        }

        if (soundManager.isActive(candidate)) {
            synchronized (stateLock) {
                openAlObservedReady = true;
                outputMode = OutputMode.OPENAL;
                earlyMixerRequested = false;
            }
            if (!loggedOpenAlAttach) {
                loggedOpenAlAttach = true;
                HabiTrainCore.LOGGER.info(
                        "[HabiBootAudio] Streaming 48kHz stereo BGM via Minecraft OpenAL (SoundSource.MASTER, bgmVol={}%)",
                        Math.round(bgmVolumePref * 100f));
            }
            return;
        }

        // SoundEngine is still initializing during early resource reload: release candidate cleanly
        // and let early Java Sound bridge cover the pre-OpenAL seconds if available.
        candidate.markClosed();
        synchronized (stateLock) {
            if (openAlBgmInstance == candidate) {
                openAlBgmInstance = null;
            }
            if (!openAlObservedReady && !earlyJavaSoundUnavailable) {
                ensureEarlyMixerRunningLocked();
            }
        }
    }

    private void stopOpenAlStream(@Nullable Minecraft mc) {
        KineticBgmSoundInstance current;
        synchronized (stateLock) {
            current = openAlBgmInstance;
            openAlBgmInstance = null;
            openAlClockOwner = null;
            openAlAudiblyPlaying = false;
            openAlChunksPulled = 0;
            if (outputMode == OutputMode.OPENAL) {
                outputMode = OutputMode.NONE;
            }
            mixerProducingSound = false;
        }
        if (current != null) {
            current.markClosed();
            if (mc != null && mc.isSameThread() && mc.getSoundManager() != null) {
                try {
                    mc.getSoundManager().stop(current);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean isOutputActiveLocked() {
        return outputMode == OutputMode.OPENAL || earlyMixerRunning;
    }

    /**
     * Pulls one 64 ms stereo 48 kHz 16-bit LE PCM chunk for Minecraft's OpenAL streaming channel.
     * Returns {@code null} for clean stream EOF when stopped or superseded.
     */
    @Nullable
    ByteBuffer mixOpenAlChunk(@Nullable KineticBgmSoundInstance owner,
                              float[] bgmL, float[] bgmR, float[] sfxL, float[] sfxR, byte[] pcmBlock) {
        return mixOpenAlChunk(owner, bgmL, bgmR, sfxL, sfxR, pcmBlock, null);
    }

    @Nullable
    ByteBuffer mixOpenAlChunk(@Nullable KineticBgmSoundInstance owner,
                              float[] bgmL, float[] bgmR, float[] sfxL, float[] sfxR, byte[] pcmBlock,
                              @Nullable ByteBuffer reusableDirect) {
        synchronized (stateLock) {
            if (owner != null) {
                if (owner != openAlBgmInstance || owner.isMarkedClosed()) {
                    return null;
                }
                if (outputMode != OutputMode.OPENAL) {
                    outputMode = OutputMode.OPENAL;
                    earlyMixerRequested = false;
                }
                if (owner != openAlClockOwner) {
                    // If audio backend attached late after visuals already advanced without sound,
                    // resync stems to the current visual timeline instead of playing intro.wav from 0.0s.
                    if (!loadingSequenceConsumed
                            && (state == State.INTRO || state == State.LOADING)
                            && stemsLoaded
                            && pendingStartTimelineT > 0.05) {
                        startLoadingLocked(pendingStartTimelineT, loadProgress);
                    }
                    openAlClockOwner = owner;
                    openAlStreamOriginSample = mixerSampleClock;
                    openAlChunksPulled = 0;
                    openAlAudiblyPlaying = false;
                }
            }
            if (!activeSession && state == State.STOPPED && !fadingOut) {
                if (owner != null) {
                    owner.markClosed();
                    if (openAlBgmInstance == owner) {
                        openAlBgmInstance = null;
                        openAlClockOwner = null;
                        openAlAudiblyPlaying = false;
                        openAlChunksPulled = 0;
                        outputMode = OutputMode.NONE;
                    }
                }
                mixerProducingSound = false;
                return null;
            }
            if (state == State.INTRO || state == State.LOADING) {
                loadingSequenceConsumed = true;
            }
        }

        ByteBuffer direct = reusableDirect != null ? reusableDirect : BufferUtils.createByteBuffer(OPENAL_CHUNK_FRAMES * 4);
        direct.clear();
        boolean chunkHasAudio = false;
        for (int b = 0; b < OPENAL_CHUNK_BLOCKS; b++) {
            boolean blockActive = mixBlock(bgmL, bgmR, sfxL, sfxR, pcmBlock, true);
            chunkHasAudio |= blockActive;
            direct.put(pcmBlock);
        }
        mixerProducingSound = chunkHasAudio;
        if (owner != null) {
            long nowNanos = System.nanoTime();
            synchronized (stateLock) {
                if (owner == openAlClockOwner && owner == openAlBgmInstance && !owner.isMarkedClosed()) {
                    openAlChunksPulled++;
                    if (openAlChunksPulled == OPENAL_PREFILL_CHUNKS) {
                        // Channel.attachBufferStream pumps 4 chunks and immediately calls alSourcePlay()
                        // on chunk 1 sample 0 (= openAlStreamOriginSample).
                        openAlAnchorSample = openAlStreamOriginSample;
                        openAlAnchorNanos = nowNanos;
                        openAlAudiblyPlaying = true;
                    } else if (openAlChunksPulled > OPENAL_PREFILL_CHUNKS && openAlAudiblyPlaying) {
                        getEstimatedHeardSampleLocked(nowNanos);
                    }
                }
            }
        }
        direct.flip();
        return direct;
    }

    // ── Early-boot SourceDataLine fallback loop (before OpenAL starts) ───────

    private void ensureEarlyMixerRunningLocked() {
        if (earlyJavaSoundUnavailable || outputMode == OutputMode.OPENAL) return;
        earlyMixerRequested = true;
        if (earlyMixerRunning) return;
        earlyMixerRunning = true;
        outputMode = OutputMode.EARLY_JAVA_SOUND;
        earlyMixerThread = new Thread(this::runEarlyMixerLoop, "habitrain-boot-audio");
        earlyMixerThread.setDaemon(true);
        earlyMixerThread.setPriority(Thread.NORM_PRIORITY + 1);
        earlyMixerThread.start();
    }

    private void runEarlyMixerLoop() {
        SourceDataLine line = null;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, STREAM_AUDIO_FORMAT);
            if (AudioSystem.isLineSupported(info)) {
                line = (SourceDataLine) AudioSystem.getLine(info);
            } else {
                line = AudioSystem.getSourceDataLine(STREAM_AUDIO_FORMAT);
            }
            line.open(STREAM_AUDIO_FORMAT, LINE_BUFFER_FRAMES * 4);
            line.start();
            synchronized (stateLock) {
                if (!loadingSequenceConsumed
                        && (state == State.INTRO || state == State.LOADING)
                        && stemsLoaded
                        && pendingStartTimelineT > 0.05) {
                    startLoadingLocked(pendingStartTimelineT, loadProgress);
                }
                earlyLineOriginSample = mixerSampleClock;
                earlyLineStartFramePos = line.getLongFramePosition();
                earlyLineAudiblyPlaying = false;
                activeEarlyLine = line;
            }
            if (!loggedEarlyAudioOpen) {
                loggedEarlyAudioOpen = true;
                HabiTrainCore.LOGGER.info(
                        "[HabiBootAudio] Opened early-boot 48kHz stereo Java Sound bridge (waiting for Minecraft OpenAL)");
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.info(
                    "[HabiBootAudio] Early Java Sound bridge unavailable ({}); will stream via Minecraft OpenAL once SoundEngine starts",
                    t.getMessage());
            earlyJavaSoundUnavailable = true;
            synchronized (stateLock) {
                earlyMixerRunning = false;
                earlyMixerRequested = false;
                earlyMixerThread = null;
                if (outputMode == OutputMode.EARLY_JAVA_SOUND) {
                    outputMode = OutputMode.NONE;
                }
            }
            if (line != null) {
                try { line.close(); } catch (Throwable ignored) {}
            }
            return;
        }

        byte[] pcmOut = new byte[MIX_BLOCK_FRAMES * 4];
        float[] bgmL = new float[MIX_BLOCK_FRAMES];
        float[] bgmR = new float[MIX_BLOCK_FRAMES];
        float[] sfxL = new float[MIX_BLOCK_FRAMES];
        float[] sfxR = new float[MIX_BLOCK_FRAMES];
        int idleBlocks = 0;

        try {
            while (earlyMixerRunning) {
                boolean shouldContinue;
                synchronized (stateLock) {
                    shouldContinue = earlyMixerRequested && outputMode == OutputMode.EARLY_JAVA_SOUND;
                    if (shouldContinue && (state == State.INTRO || state == State.LOADING) && stemsLoaded) {
                        loadingSequenceConsumed = true;
                    }
                }
                if (!shouldContinue) {
                    break;
                }
                boolean hasAudio = mixBlock(bgmL, bgmR, sfxL, sfxR, pcmOut, false);
                mixerProducingSound = hasAudio;
                if (!earlyLineAudiblyPlaying && line.getLongFramePosition() > earlyLineStartFramePos) {
                    synchronized (stateLock) {
                        earlyLineAudiblyPlaying = true;
                    }
                }
                boolean waitingForStems;
                synchronized (stateLock) {
                    waitingForStems = activeSession && !userMuted && (pendingStartLoading || pendingStartMenu);
                }
                if (!hasAudio && !waitingForStems) {
                    idleBlocks++;
                    if (idleBlocks > 380) { // ~2s silent -> park early device cleanly
                        break;
                    }
                } else {
                    idleBlocks = 0;
                }
                line.write(pcmOut, 0, pcmOut.length);
            }
        } catch (Throwable t) {
            if (earlyMixerRunning) {
                HabiTrainCore.LOGGER.debug("[HabiBootAudio] Early mixer thread exited: {}", t.getMessage());
            }
        } finally {
            try { line.stop(); } catch (Throwable ignored) {}
            try { line.flush(); } catch (Throwable ignored) {}
            try { line.close(); } catch (Throwable ignored) {}
            synchronized (stateLock) {
                activeEarlyLine = null;
                earlyLineAudiblyPlaying = false;
                earlyMixerRunning = false;
                earlyMixerThread = null;
                if (outputMode == OutputMode.EARLY_JAVA_SOUND) {
                    outputMode = OutputMode.NONE;
                    mixerProducingSound = false;
                }
            }
        }
    }

    private boolean mixBlock(float[] bgmL, float[] bgmR, float[] sfxL, float[] sfxR,
                             byte[] pcmOut, boolean forOpenAl) {
        for (int i = 0; i < MIX_BLOCK_FRAMES; i++) {
            bgmL[i] = 0f;
            bgmR[i] = 0f;
            sfxL[i] = 0f;
            sfxR[i] = 0f;
        }

        boolean anyActive = false;
        boolean enabledNow = isSoundEffectivelyEnabled();
        double bgmVol = forOpenAl
                ? computeOpenAlBgmGain(enabledNow, bgmVolumePref)
                : computeBgmVolume(enabledNow, mcMasterVolume, bgmVolumePref);
        double sfxVol = forOpenAl
                ? computeOpenAlSfxGain(enabledNow, bgmVolumePref)
                : computeSfxVolume(enabledNow, mcMasterVolume, bgmVolumePref);

        synchronized (stateLock) {
            long startSample = mixerSampleClock;

            if (fadingOut) {
                fadeOutGain -= (MIX_BLOCK_FRAMES / (double) SAMPLE_RATE) / 0.35;
                if (fadeOutGain <= 0.001) {
                    fadeOutGain = 0.0;
                    fadingOut = false;
                    activeSession = false;
                    clearMusicChannelsLocked();
                    state = State.STOPPED;
                    if (releaseStemsOnStop) {
                        releaseStemsLocked();
                    }
                }
            }

            // Smooth master focus filter & gain
            double alphaSmooth = 1.0 - Math.exp(-(MIX_BLOCK_FRAMES / (double) SAMPLE_RATE) / 0.16);
            currentBgmFocusCutoff += (targetBgmFocusCutoff - currentBgmFocusCutoff) * alphaSmooth;
            currentBgmFocusGain += (targetBgmFocusGain - currentBgmFocusGain) * alphaSmooth;
            masterBgmFilter.setCutoff(currentBgmFocusCutoff);

            if (bgmVol > 0.0005 && fadeOutGain > 0.0005) {
                anyActive |= mixStem(introCh, startSample, bgmL, bgmR);
                anyActive |= mixStem(idleCh, startSample, bgmL, bgmR);
                anyActive |= mixStem(loadCh, startSample, bgmL, bgmR);
                anyActive |= mixStem(exitCh, startSample, bgmL, bgmR);
                anyActive |= mixStem(menuCh, startSample, bgmL, bgmR);
            }

            if (state == State.DEPARTING && menuOriginSample > 0 && startSample + MIX_BLOCK_FRAMES >= menuOriginSample) {
                state = State.MENU;
            }

            // Mix procedural D-major UI SFX voices
            if (sfxVol > 0.0005 && !sfxVoices.isEmpty()) {
                Iterator<SfxVoice> it = sfxVoices.iterator();
                while (it.hasNext()) {
                    SfxVoice v = it.next();
                    if (!v.renderBlock(startSample, sfxL, sfxR)) {
                        it.remove();
                    } else {
                        anyActive = true;
                    }
                }
            } else if (sfxVol <= 0.0005) {
                sfxVoices.clear();
            }

            mixerSampleClock += MIX_BLOCK_FRAMES;
        }

        // Apply master BGM lowpass, mix SFX, soft limit, encode 16-bit PCM, & feed spectrum
        double effBgm = bgmVol * currentBgmFocusGain * fadeOutGain * 0.92;
        double effSfx = sfxVol * 0.92;
        double energySq = 0.0;
        for (int i = 0; i < MIX_BLOCK_FRAMES; i++) {
            float bl = bgmL[i];
            float br = bgmR[i];
            if (currentBgmFocusCutoff < 18_000.0) {
                double[] filtered = masterBgmFilter.process(bl, br);
                bl = (float) filtered[0];
                br = (float) filtered[1];
            }
            double l = softLimit(bl * effBgm + sfxL[i] * effSfx);
            double r = softLimit(br * effBgm + sfxR[i] * effSfx);
            float mono = (float) ((l + r) * 0.5);
            monoAnalysisRing[i] = mono;
            energySq += mono * mono;

            short sl = (short) Math.max(-32767, Math.min(32767, Math.round(l * 32767.0)));
            short sr = (short) Math.max(-32767, Math.min(32767, Math.round(r * 32767.0)));
            int p = i * 4;
            pcmOut[p] = (byte) (sl & 0xFF);
            pcmOut[p + 1] = (byte) ((sl >>> 8) & 0xFF);
            pcmOut[p + 2] = (byte) (sr & 0xFF);
            pcmOut[p + 3] = (byte) ((sr >>> 8) & 0xFF);
        }

        // Real 16-band Goertzel spectrum telemetry (precomputed Hann window applied once per block)
        if (anyActive) {
            double rms = Math.sqrt(energySq / MIX_BLOCK_FRAMES);
            for (int i = 0; i < MIX_BLOCK_FRAMES; i++) {
                windowedMono[i] = monoAnalysisRing[i] * hannWindow[i];
            }
            double bassSum = 0.0;
            for (int b = 0; b < SPECTRUM_BANDS; b++) {
                float coeff = goertzelCoeffs[b];
                float s0 = 0f, s1 = 0f, s2 = 0f;
                for (int i = 0; i < MIX_BLOCK_FRAMES; i++) {
                    s0 = windowedMono[i] + coeff * s1 - s2;
                    s2 = s1;
                    s1 = s0;
                }
                double mag = Math.sqrt(Math.max(0.0, s1 * s1 + s2 * s2 - coeff * s1 * s2)) / (MIX_BLOCK_FRAMES * 0.22);
                // Perceptual tilt so highs stay lively
                double tilted = clamp(Math.pow(mag * (1.0 + b * 0.14), 0.72), 0.04, 1.0);
                liveBars[b] = (float) (liveBars[b] * 0.72 + tilted * 0.28);
                if (b < 4) bassSum += liveBars[b];
            }
            liveBass = clamp(bassSum / 4.0, 0.0, 1.0);
            liveEnergy = clamp(rms * 2.6, 0.0, 1.0);
        } else {
            for (int b = 0; b < SPECTRUM_BANDS; b++) liveBars[b] *= 0.85f;
            liveBass *= 0.85;
            liveEnergy *= 0.85;
        }
        return anyActive;
    }

    private static boolean readKineticUiSoundPreference() {
        try {
            return ClientVisualPreferences.isKineticUiSoundEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static float readKineticUiBgmVolumePreference() {
        try {
            return clamp01(ClientVisualPreferences.getKineticUiBgmVolume());
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static boolean mixStem(StemChannel ch, long blockStart, float[] outL, float[] outR) {
        if (ch == null || !ch.active) return false;
        return ch.mixInto(blockStart, outL, outR);
    }

    private boolean hasActiveMusicChannelLocked() {
        return (introCh != null && introCh.active)
                || (idleCh != null && idleCh.active)
                || (loadCh != null && loadCh.active)
                || (exitCh != null && exitCh.active)
                || (menuCh != null && menuCh.active);
    }

    private void clearMusicChannelsLocked() {
        introCh = null;
        idleCh = null;
        loadCh = null;
        exitCh = null;
        menuCh = null;
    }

    // ── Package-private hooks for unit tests ─────────────────────────────────

    State currentStateForTest() {
        synchronized (stateLock) {
            return state;
        }
    }

    boolean hasActiveMusicChannelForTest() {
        synchronized (stateLock) {
            return hasActiveMusicChannelLocked();
        }
    }

    void setTestVolumeOverrides(float masterVolume, float bgmPrefVolume, boolean externalDisabled) {
        this.mcMasterVolume = clamp01(masterVolume);
        this.bgmVolumePref = clamp01(bgmPrefVolume);
        this.externalSoundDisabled = externalDisabled;
    }

    @Nullable
    ByteBuffer pullOpenAlChunkForTest() {
        return mixOpenAlChunk(
                null,
                new float[MIX_BLOCK_FRAMES],
                new float[MIX_BLOCK_FRAMES],
                new float[MIX_BLOCK_FRAMES],
                new float[MIX_BLOCK_FRAMES],
                new byte[MIX_BLOCK_FRAMES * 4]
        );
    }

    KineticBgmSoundInstance attachMockOpenAlStreamForTest() {
        KineticBgmSoundInstance owner = new KineticBgmSoundInstance(this);
        synchronized (stateLock) {
            openAlBgmInstance = owner;
            openAlClockOwner = null;
            openAlAudiblyPlaying = false;
            openAlChunksPulled = 0;
            openAlObservedReady = true;
            outputMode = OutputMode.OPENAL;
            earlyMixerRequested = false;
        }
        return owner;
    }

    @Nullable
    ByteBuffer pullOwnedOpenAlChunkForTest(KineticBgmSoundInstance owner) {
        return mixOpenAlChunk(
                owner,
                new float[MIX_BLOCK_FRAMES],
                new float[MIX_BLOCK_FRAMES],
                new float[MIX_BLOCK_FRAMES],
                new float[MIX_BLOCK_FRAMES],
                new byte[MIX_BLOCK_FRAMES * 4]
        );
    }

    void shiftOpenAlAnchorBackForTest(long elapsedNanos) {
        synchronized (stateLock) {
            openAlAnchorNanos -= elapsedNanos;
        }
    }

    void setEarlyJavaSoundUnavailableForTest(boolean unavailable) {
        this.earlyJavaSoundUnavailable = unavailable;
    }

    public static double calcLoadLayerGain(double progress) {
        if (progress >= 0.999) return 0.34;
        return clamp(0.18 + progress * 0.78, 0.15, 0.96);
    }

    public static double calcLoadLayerCutoff(double progress) {
        if (progress >= 0.999) return 5200.0;
        return 900.0 + Math.pow(clamp01(progress), 1.3) * 9500.0;
    }

    private static double softLimit(double x) {
        if (x > 0.85 || x < -0.85) {
            return Math.tanh(x * 1.1) / Math.tanh(1.1);
        }
        return x;
    }

    // ── Lightweight pure-Java 16-bit PCM WAV decoder ─────────────────────────

    static short[] decodeWavResourcePcm16(String classpathPath) throws IOException {
        InputStream raw = HabiBootAudioEngine.class.getClassLoader().getResourceAsStream(classpathPath);
        if (raw == null) {
            raw = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathPath);
        }
        if (raw == null) return null;
        try (InputStream in = new BufferedInputStream(raw)) {
            byte[] all = in.readAllBytes();
            return decodeWavBytesPcm16(all);
        }
    }

    static short[] decodeWavBytesPcm16(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 44) {
            throw new IOException("WAV too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                || bytes[8] != 'W' || bytes[9] != 'A' || bytes[10] != 'V' || bytes[11] != 'E') {
            throw new IOException("Invalid RIFF/WAVE header");
        }
        int channels = 2;
        int sampleRate = SAMPLE_RATE;
        int bitsPerSample = 16;
        int dataOffset = -1;
        int dataSize = 0;

        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = buf.getInt(pos + 4);
            pos += 8;
            if (size < 0 || pos + size > bytes.length) {
                size = Math.max(0, bytes.length - pos);
            }
            if ("fmt ".equals(id) && size >= 16) {
                channels = buf.getShort(pos + 2) & 0xFFFF;
                sampleRate = buf.getInt(pos + 4);
                bitsPerSample = buf.getShort(pos + 14) & 0xFFFF;
            } else if ("data".equals(id)) {
                dataOffset = pos;
                dataSize = size;
                break;
            }
            pos += size + (size & 1);
        }
        if (dataOffset < 0 || bitsPerSample != 16 || channels < 1 || channels > 2) {
            throw new IOException("Unsupported WAV format: ch=" + channels + " bits=" + bitsPerSample + " sr=" + sampleRate);
        }
        int frameBytes = channels * 2;
        int srcFrames = dataSize / frameBytes;
        short[] stereo = new short[srcFrames * 2];
        int p = dataOffset;
        if (channels == 2) {
            for (int i = 0; i < srcFrames; i++) {
                stereo[i * 2] = buf.getShort(p);
                stereo[i * 2 + 1] = buf.getShort(p + 2);
                p += 4;
            }
        } else {
            for (int i = 0; i < srcFrames; i++) {
                short m = buf.getShort(p);
                p += 2;
                stereo[i * 2] = m;
                stereo[i * 2 + 1] = m;
            }
        }
        if (sampleRate != SAMPLE_RATE && srcFrames > 1) {
            int dstFrames = (int) Math.round(srcFrames * (SAMPLE_RATE / (double) sampleRate));
            short[] resampled = new short[dstFrames * 2];
            for (int i = 0; i < dstFrames; i++) {
                double srcPos = i * (sampleRate / (double) SAMPLE_RATE);
                int idx = Math.min(srcFrames - 2, (int) srcPos);
                float frac = (float) (srcPos - idx);
                float l = stereo[idx * 2] * (1f - frac) + stereo[(idx + 1) * 2] * frac;
                float r = stereo[idx * 2 + 1] * (1f - frac) + stereo[(idx + 1) * 2 + 1] * frac;
                resampled[i * 2] = (short) Math.max(-32768, Math.min(32767, Math.round(l)));
                resampled[i * 2 + 1] = (short) Math.max(-32768, Math.min(32767, Math.round(r)));
            }
            return resampled;
        }
        return stereo;
    }

    static float[] decodeWavResource(String classpathPath) throws IOException {
        short[] pcm16 = decodeWavResourcePcm16(classpathPath);
        if (pcm16 == null) return null;
        float[] out = new float[pcm16.length];
        for (int i = 0; i < pcm16.length; i++) out[i] = pcm16[i] * INV_SHORT_SCALE;
        return out;
    }

    static float[] decodeWavBytes(byte[] bytes) throws IOException {
        short[] pcm16 = decodeWavBytesPcm16(bytes);
        float[] out = new float[pcm16.length];
        for (int i = 0; i < pcm16.length; i++) out[i] = pcm16[i] * INV_SHORT_SCALE;
        return out;
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    // ── Minecraft OpenAL SoundInstances & AudioStreams ───────────────────────

    /**
     * Continuous stereo 48 kHz streaming {@link SoundInstance} routed through Minecraft's OpenAL
     * {@link SoundManager} on {@link SoundSource#MASTER} (immune to SRE's locked {@code MUSIC} = 0).
     */
    static final class KineticBgmSoundInstance extends AbstractTickableSoundInstance {
        private final HabiBootAudioEngine engine;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        KineticBgmSoundInstance(HabiBootAudioEngine engine) {
            super(SoundEvent.createVariableRangeEvent(KINETIC_BGM_SOUND_ID), SoundSource.MASTER,
                    SoundInstance.createUnseededRandom());
            this.engine = engine;
            this.volume = 1.0f;
            this.pitch = 1.0f;
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
            this.looping = false;
            this.delay = 0;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
        }

        void markClosed() {
            closed.set(true);
            this.stop();
        }

        boolean isMarkedClosed() {
            return closed.get() || isStopped();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            this.sound = new Sound(
                    FabricSoundInstance.EMPTY_SOUND,
                    ConstantFloat.of(1.0F),
                    ConstantFloat.of(1.0F),
                    1,
                    Sound.Type.FILE,
                    true,
                    false,
                    16);
            return new WeighedSoundEvents(this.location, null);
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id,
                                                             boolean repeatInstantly) {
            return CompletableFuture.completedFuture(new LiveBgmAudioStream(engine, this));
        }

        @Override
        public void tick() {
            if (closed.get()) {
                this.stop();
            }
        }
    }

    private static final class LiveBgmAudioStream implements AudioStream {
        private final HabiBootAudioEngine engine;
        private final KineticBgmSoundInstance owner;
        private final float[] bgmL = new float[MIX_BLOCK_FRAMES];
        private final float[] bgmR = new float[MIX_BLOCK_FRAMES];
        private final float[] sfxL = new float[MIX_BLOCK_FRAMES];
        private final float[] sfxR = new float[MIX_BLOCK_FRAMES];
        private final byte[] pcmBlock = new byte[MIX_BLOCK_FRAMES * 4];
        private final ByteBuffer reusableDirect = BufferUtils.createByteBuffer(OPENAL_CHUNK_FRAMES * 4);
        private volatile boolean closed;

        LiveBgmAudioStream(HabiBootAudioEngine engine, KineticBgmSoundInstance owner) {
            this.engine = engine;
            this.owner = owner;
        }

        @Override
        public AudioFormat getFormat() {
            return STREAM_AUDIO_FORMAT;
        }

        @Override
        public @Nullable ByteBuffer read(int size) {
            if (closed || owner.isMarkedClosed()) return null;
            return engine.mixOpenAlChunk(owner, bgmL, bgmR, sfxL, sfxR, pcmBlock, reusableDirect);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Low-latency one-shot stereo 48 kHz PCM UI SFX routed through Minecraft's OpenAL. */
    private static final class KineticOneShotSfxSoundInstance extends AbstractSoundInstance {
        private final byte[] pcmBytes;

        KineticOneShotSfxSoundInstance(byte[] pcmBytes) {
            super(SoundEvent.createVariableRangeEvent(KINETIC_SFX_SOUND_ID), SoundSource.MASTER,
                    SoundInstance.createUnseededRandom());
            this.pcmBytes = pcmBytes;
            this.volume = 1.0f;
            this.pitch = 1.0f;
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
            this.looping = false;
            this.delay = 0;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            this.sound = new Sound(
                    FabricSoundInstance.EMPTY_SOUND,
                    ConstantFloat.of(1.0F),
                    ConstantFloat.of(1.0F),
                    1,
                    Sound.Type.FILE,
                    true,
                    false,
                    16);
            return new WeighedSoundEvents(this.location, null);
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id,
                                                             boolean repeatInstantly) {
            return CompletableFuture.completedFuture(new OneShotPcmAudioStream(pcmBytes));
        }
    }

    private static final class OneShotPcmAudioStream implements AudioStream {
        private final byte[] pcm;
        private int cursor;

        OneShotPcmAudioStream(byte[] pcm) {
            this.pcm = pcm;
        }

        @Override
        public AudioFormat getFormat() {
            return STREAM_AUDIO_FORMAT;
        }

        @Override
        public @Nullable ByteBuffer read(int size) {
            if (cursor >= pcm.length || size <= 0) return null;
            int aligned = Math.min(size & ~3, pcm.length - cursor);
            if (aligned <= 0) {
                cursor = pcm.length;
                return null;
            }
            ByteBuffer direct = BufferUtils.createByteBuffer(aligned);
            direct.put(pcm, cursor, aligned);
            direct.flip();
            cursor += aligned;
            return direct;
        }

        @Override
        public void close() {}
    }

    // ── Internal DSP primitives ──────────────────────────────────────────────

    record SfxSpec(double f0, double f1, double durSec, double amp, Wave wave, double pan, double delaySec) {}

    private static final class StemChannel {
        final short[] pcm;
        final int totalFrames;
        final boolean loop;
        boolean active;

        long startSample;
        long cursorFrame;
        double gain = 1.0;
        double gainStart = 1.0;
        double gainTarget = 1.0;
        long gainRampStart;
        long gainRampDur;

        double cutoff = 20_000.0;
        double cutoffStart = 20_000.0;
        double cutoffTarget = 20_000.0;
        long cutoffRampStart;
        long cutoffRampDur;

        boolean smoothTracking;
        double smoothGainTarget = 1.0;
        double smoothCutoffTarget = 20_000.0;

        long fadeOutStart = -1;
        long fadeOutDur;
        double fadeOutStartGain;
        double fadeOutStartCutoff;
        double fadeOutEndCutoff;

        final StereoLowpass filter = new StereoLowpass(20_000.0, 0.707);

        StemChannel(short[] pcm, boolean loop) {
            this.pcm = pcm;
            this.totalFrames = pcm.length / 2;
            this.loop = loop;
        }

        void scheduleStart(long whenSample, long offsetFrame, double g0, double g1, long rampSamples, double initCutoff) {
            this.startSample = whenSample;
            this.cursorFrame = loop ? Math.floorMod(offsetFrame, totalFrames) : Math.max(0, offsetFrame);
            this.gain = g0;
            this.gainStart = g0;
            this.gainTarget = g1;
            this.gainRampStart = whenSample;
            this.gainRampDur = rampSamples;
            this.cutoff = initCutoff;
            this.cutoffStart = initCutoff;
            this.cutoffTarget = initCutoff;
            this.cutoffRampDur = 0;
            this.smoothGainTarget = g1;
            this.smoothCutoffTarget = initCutoff;
            this.filter.setCutoff(initCutoff);
            this.active = true;
        }

        void scheduleCutoffRamp(long whenSample, long durSamples, double targetHz) {
            this.cutoffRampStart = whenSample;
            this.cutoffRampDur = durSamples;
            this.cutoffStart = this.cutoff;
            this.cutoffTarget = targetHz;
        }

        void setSmoothTarget(double targetGain, double targetCutoff) {
            this.smoothTracking = true;
            this.smoothGainTarget = targetGain;
            this.smoothCutoffTarget = targetCutoff;
        }

        void scheduleFadeOut(long whenSample, long durSamples, double endCutoffHz) {
            this.smoothTracking = false;
            this.fadeOutStart = whenSample;
            this.fadeOutDur = Math.max(64L, durSamples);
            this.fadeOutStartGain = this.gain;
            this.fadeOutStartCutoff = this.cutoff;
            this.fadeOutEndCutoff = endCutoffHz;
        }

        boolean mixInto(long blockStart, float[] outL, float[] outR) {
            if (!active || totalFrames <= 0) return false;
            if (blockStart + MIX_BLOCK_FRAMES <= startSample) return true; // scheduled in future

            long mid = blockStart + MIX_BLOCK_FRAMES / 2;
            if (fadeOutStart >= 0 && mid >= fadeOutStart) {
                double u = clamp01((mid - fadeOutStart) / (double) fadeOutDur);
                gain = Math.max(0.0001, fadeOutStartGain * Math.pow(0.0001 / Math.max(0.001, fadeOutStartGain), u));
                cutoff = fadeOutStartCutoff * Math.pow(fadeOutEndCutoff / Math.max(80.0, fadeOutStartCutoff), u);
                if (u >= 1.0) {
                    active = false;
                    return false;
                }
            } else if (gainRampDur > 0 && mid < gainRampStart + gainRampDur) {
                double u = clamp01((mid - gainRampStart) / (double) gainRampDur);
                gain = gainStart + (gainTarget - gainStart) * u;
            } else if (smoothTracking) {
                double aG = 1.0 - Math.exp(-(MIX_BLOCK_FRAMES / (double) SAMPLE_RATE) / 0.14);
                double aF = 1.0 - Math.exp(-(MIX_BLOCK_FRAMES / (double) SAMPLE_RATE) / 0.16);
                gain += (smoothGainTarget - gain) * aG;
                cutoff += (smoothCutoffTarget - cutoff) * aF;
            } else {
                gain = gainTarget;
            }

            if (fadeOutStart < 0 && cutoffRampDur > 0 && mid >= cutoffRampStart) {
                double u = clamp01((mid - cutoffRampStart) / (double) cutoffRampDur);
                cutoff = cutoffStart * Math.pow(cutoffTarget / Math.max(80.0, cutoffStart), u);
                if (u >= 1.0) {
                    cutoff = cutoffTarget;
                    cutoffRampDur = 0;
                }
            }

            boolean useFilter = cutoff < 18_500.0;
            if (useFilter) {
                filter.setCutoff(cutoff);
            }

            int i0 = (int) Math.max(0, startSample - blockStart);
            float g = (float) gain;
            for (int i = i0; i < MIX_BLOCK_FRAMES; i++) {
                if (cursorFrame >= totalFrames) {
                    if (loop) {
                        cursorFrame = 0;
                    } else {
                        active = false;
                        break;
                    }
                }
                int idx = (int) cursorFrame * 2;
                float l = pcm[idx] * INV_SHORT_SCALE;
                float r = pcm[idx + 1] * INV_SHORT_SCALE;
                if (useFilter) {
                    double[] f = filter.process(l, r);
                    l = (float) f[0];
                    r = (float) f[1];
                }
                outL[i] += l * g;
                outR[i] += r * g;
                cursorFrame++;
            }
            return true;
        }
    }

    enum Wave { SINE, TRIANGLE, SAW }

    private static final class SfxVoice {
        final long startSample;
        final long durSamples;
        final double f0, f1, amp, panL, panR;
        final Wave wave;
        double phase;

        SfxVoice(long startSample, long durSamples, double f0, double f1, double amp, Wave wave, double pan) {
            this.startSample = startSample;
            this.durSamples = durSamples;
            this.f0 = f0;
            this.f1 = f1;
            this.amp = amp;
            this.wave = wave;
            double p = clamp(pan, -1.0, 1.0);
            this.panL = Math.cos((p + 1.0) * Math.PI * 0.25);
            this.panR = Math.sin((p + 1.0) * Math.PI * 0.25);
        }

        boolean renderBlock(long blockStart, float[] outL, float[] outR) {
            if (blockStart >= startSample + durSamples) return false;
            if (blockStart + MIX_BLOCK_FRAMES <= startSample) return true;
            int i0 = (int) Math.max(0, startSample - blockStart);
            long attack = Math.round(0.003 * SAMPLE_RATE);
            for (int i = i0; i < MIX_BLOCK_FRAMES; i++) {
                long age = (blockStart + i) - startSample;
                if (age >= durSamples) return false;
                double u = age / (double) durSamples;
                double freq = (f1 == f0) ? f0 : f0 * Math.pow(f1 / f0, u);
                phase += (2.0 * Math.PI * freq) / SAMPLE_RATE;
                if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                double sample = switch (wave) {
                    case SINE -> Math.sin(phase);
                    case TRIANGLE -> (2.0 / Math.PI) * Math.asin(Math.sin(phase));
                    case SAW -> (phase / Math.PI) - 1.0;
                };
                double env = age < attack
                        ? (age / (double) Math.max(1, attack))
                        : Math.pow(0.0005, (age - attack) / (double) Math.max(1, durSamples - attack));
                double v = sample * amp * env;
                outL[i] += (float) (v * panL);
                outR[i] += (float) (v * panR);
            }
            return true;
        }
    }

    /** 2nd-order RBJ biquad stereo lowpass filter. */
    private static final class StereoLowpass {
        private final double q;
        private double lastCutoff = -1.0;
        private double b0, b1, b2, a1, a2;
        private double x1L, x2L, y1L, y2L;
        private double x1R, x2R, y1R, y2R;
        private final double[] out = new double[2];

        StereoLowpass(double cutoffHz, double q) {
            this.q = q;
            setCutoff(cutoffHz);
        }

        void setCutoff(double hz) {
            double clamped = clamp(hz, 60.0, SAMPLE_RATE * 0.45);
            if (Math.abs(clamped - lastCutoff) < 2.0) return;
            lastCutoff = clamped;
            double w0 = 2.0 * Math.PI * clamped / SAMPLE_RATE;
            double cosW = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double a0 = 1.0 + alpha;
            b0 = ((1.0 - cosW) * 0.5) / a0;
            b1 = (1.0 - cosW) / a0;
            b2 = b0;
            a1 = (-2.0 * cosW) / a0;
            a2 = (1.0 - alpha) / a0;
        }

        double[] process(double inL, double inR) {
            double oL = b0 * inL + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L;
            x2L = x1L; x1L = inL; y2L = y1L; y1L = oL;
            double oR = b0 * inR + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R;
            x2R = x1R; x1R = inR; y2R = y1R; y1R = oR;
            out[0] = oL;
            out[1] = oR;
            return out;
        }
    }
}
