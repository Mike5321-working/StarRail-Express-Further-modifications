package com.habitrain.core.client.title;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated deeply-buffered native OpenAL player for the 3-act World-Entry cutscene soundtrack.
 *
 * <p>Why a dedicated native OpenAL backend instead of routing through Minecraft's {@code SoundEngine}:</p>
 * <ul>
 *   <li><b>Immune to {@code SoundEngine#stopAll()}</b>: Minecraft's {@code doWorldLoad()}, {@code disconnect()},
 *       {@code setLevel()}, and {@code clearClientLevel()} all invoke {@code SoundManager#stop()} -&gt;
 *       {@code SoundEngine#stopAll()}, which flushes {@code SoundEngineExecutor} and destroys all
 *       {@code ChannelAccess} channels mid-loading. OpenAL's context stays alive across {@code stopAll()}, so
 *       sources allocated here keep playing seamlessly with zero cut or restart.</li>
 *   <li><b>3.58 seconds of native C++ OpenAL Soft buffer cushion</b>: queues {@value #TARGET_QUEUED_BUFFERS}
 *       chunks of {@value #CHUNK_MS} ms ({@code 28 * 128 ms = 3,584 ms}) directly in OpenAL Soft's native
 *       mixing queue (after a {@value #FAST_PREFILL_BUFFERS}-chunk &lt;2 ms fast start), completely absorbing
 *       world-load spikes, integrated server spin-up, chunk generation, Sodium/Iris shader compilation, and
 *       JVM Stop-The-World GC pauses without audio stutter or underrun.</li>
 *   <li><b>Underrun self-healing &amp; zero-latency event cues</b>: if an extreme OS stall ever drains the
 *       queue, the high-priority refill thread immediately restarts playback instead of letting
 *       {@code ChannelAccess} permanently delete the channel. Act 1/2/3 one-shot cues and Act 3 breakout
 *       wind/arrival chord play as pre-rendered static OpenAL buffers on a separate pooled source set
 *       (&lt;2 ms trigger latency, zero contention on Minecraft's 8-channel streaming pool, and instant
 *       65 ms crossfade when Act 2 transitions to Act 3).</li>
 *   <li><b>Context-destroy safe</b>: every {@code AL10}/{@code AL11} call runs under {@link #alLock} after
 *       verifying {@link #openAlContextValid}; {@code SoundEnginePrewarmMixin} invokes
 *       {@link #onOpenAlContextDestroying()} right before {@code SoundEngine#destroy()} /
 *       {@code emergencyShutdown()} and {@link #onOpenAlContextReady(boolean)} right after
 *       {@code SoundEngine#loadLibrary()}.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
final class HabiTunnelOpenAlPlayer {

    static final int SAMPLE_RATE = HabiBootAudioEngine.SAMPLE_RATE;
    static final int MIX_BLOCK_FRAMES = 256;
    /** 24 * 256 = 6,144 frames = 128.0 ms per OpenAL buffer chunk (24,576 bytes stereo 16-bit PCM). */
    static final int CHUNK_BLOCKS = 24;
    static final int CHUNK_FRAMES = MIX_BLOCK_FRAMES * CHUNK_BLOCKS;
    static final int CHUNK_BYTES = CHUNK_FRAMES * 4;
    static final int CHUNK_MS = 128;
    /** Initial synchronous prefill before alSourcePlay(): 3 * 128 ms = 384 ms (~1 ms CPU mix time). */
    static final int FAST_PREFILL_BUFFERS = 3;
    /** Steady-state native OpenAL Soft queue depth: 28 * 128 ms = 3,584 ms (> 3.5 s stutter cushion). */
    static final int TARGET_QUEUED_BUFFERS = 28;
    static final double TARGET_BUFFER_SECONDS = (TARGET_QUEUED_BUFFERS * (double) CHUNK_FRAMES) / SAMPLE_RATE;
    private static final long REFILL_INTERVAL_NANOS = 20_000_000L; // 20 ms
    private static final long FADE_INTERVAL_NANOS = 8_000_000L;    // 8 ms during 65 ms breakout crossfade
    private static final double BREAKOUT_STREAM_FADE_SEC = 0.065;
    private static final int MAX_CONCURRENT_ONESHOTS = 12;
    private static final int SPECTRUM_BANDS = 16;
    static final int SPECTRUM_RING_SIZE = 64; // 64 * 128 ms = 8.19 s of sample-indexed EQ history

    @FunctionalInterface
    interface ChunkSupplier {
        /**
         * Mixes one {@value #CHUNK_FRAMES}-frame stereo 16-bit LE PCM chunk into {@code dstPcm}
         * (length {@value #CHUNK_BYTES}) at nominal unit gain (before {@code bgmVolumePref}),
         * writing chunk metadata into {@code metaOut}.
         *
         * @return {@code true} if audio was produced for {@code generation}, {@code false} for clean EOF
         */
        boolean mixChunk(int generation, byte[] dstPcm, ChunkMeta metaOut);
    }

    @FunctionalInterface
    interface CompletionCallback {
        void onPlaybackIdle(int generation);
    }

    static final class ChunkMeta {
        long startSample;
        final float[] bars = new float[SPECTRUM_BANDS];
        double bass;
        double energy;
        boolean hasAudio;
        final float[] bgmL = new float[MIX_BLOCK_FRAMES];
        final float[] bgmR = new float[MIX_BLOCK_FRAMES];
        final float[] sfxL = new float[MIX_BLOCK_FRAMES];
        final float[] sfxR = new float[MIX_BLOCK_FRAMES];
        final byte[] blockPcm = new byte[MIX_BLOCK_FRAMES * 4];
    }

    record SpectrumSnapshot(long startSample, float[] bars, double bass, double energy) {}

    private record PendingOneShot(int generation, byte[] pcm16, boolean useBgmGain, boolean isBreakoutTail) {}

    private record PendingBreakout(int generation, byte[] windSurgePcm16, @Nullable byte[] arrivalChordPcm16) {}

    private static final class ActiveOneShot {
        final int sourceId;
        final int bufferId;
        final boolean useBgmGain;
        final boolean isBreakoutTail;

        ActiveOneShot(int sourceId, int bufferId, boolean useBgmGain, boolean isBreakoutTail) {
            this.sourceId = sourceId;
            this.bufferId = bufferId;
            this.useBgmGain = useBgmGain;
            this.isBreakoutTail = isBreakoutTail;
        }
    }

    private final ChunkSupplier chunkSupplier;
    private final CompletionCallback completionCallback;

    /** Guards all OpenAL context & AL10/AL11 calls. Never nested with HabiBootAudioEngine.stateLock! */
    private final Object alLock = new Object();
    private volatile boolean openAlContextValid;

    // Lifecycle & command state
    private volatile int activeGeneration;
    private volatile boolean streamRequested;
    private volatile boolean stopRequested;
    private volatile boolean shutdownRequested;
    private volatile float targetBgmGain = 1.0f;
    private volatile float targetSfxGain = 1.0f;

    private final ConcurrentLinkedQueue<PendingOneShot> oneShotQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingBreakout> breakoutQueue = new ConcurrentLinkedQueue<>();

    // Worker thread
    private final Object threadLock = new Object();
    private volatile @Nullable Thread workerThread;

    // OpenAL handles (guarded strictly by alLock)
    private int streamSource;
    private int @Nullable [] bufferPool;
    private int @Nullable [] freeBuffers;
    private int freeCount;
    private boolean streamEof;
    private boolean streamFadingOut;
    private long streamFadeStartNanos;
    private long streamStartSample;
    private long completedStreamSamples;
    private long mixedStreamSamples;
    private final List<ActiveOneShot> activeOneShots = new ArrayList<>();

    // Audible clock & sample-locked spectrum delay line (lock-free, JMM-safe read by render thread)
    private volatile boolean streamPlaying;
    private volatile long audibleAnchorSample = -1L;
    private volatile long audibleAnchorNanos;
    private final AtomicReferenceArray<SpectrumSnapshot> spectrumRing = new AtomicReferenceArray<>(SPECTRUM_RING_SIZE);
    private volatile int spectrumWriteCursor;

    // Reusable worker-thread scratch buffers (zero per-chunk heap/off-heap allocation)
    private final byte[] workerPcm = new byte[CHUNK_BYTES];
    private final ChunkMeta workerMeta = new ChunkMeta();
    private final ByteBuffer workerUploadBuf = BufferUtils.createByteBuffer(CHUNK_BYTES);

    HabiTunnelOpenAlPlayer(ChunkSupplier chunkSupplier, CompletionCallback completionCallback) {
        this.chunkSupplier = chunkSupplier;
        this.completionCallback = completionCallback;
    }

    // ── OpenAL Context Lifecycle Hooks (called by SoundEnginePrewarmMixin) ───

    void onOpenAlContextReady(boolean loaded) {
        synchronized (alLock) {
            this.openAlContextValid = loaded;
        }
        if (loaded && (streamRequested || !oneShotQueue.isEmpty() || !breakoutQueue.isEmpty())) {
            ensureWorkerStarted();
            wakeWorker();
        }
    }

    /**
     * Synchronously stops and deletes all dedicated OpenAL sources &amp; buffers BEFORE
     * {@code SoundEngine#destroy()} / {@code emergencyShutdown()} destroys the ALC context.
     */
    void onOpenAlContextDestroying() {
        synchronized (alLock) {
            this.openAlContextValid = false;
            destroyAllOpenAlResourcesLocked(true);
        }
    }

    boolean isOpenAlContextValid() {
        return openAlContextValid;
    }

    // ── Public Playback Controls (called by HabiBootAudioEngine) ─────────────

    void setTargetGains(float bgmGain, float sfxGain) {
        this.targetBgmGain = clamp01(bgmGain);
        this.targetSfxGain = clamp01(sfxGain);
    }

    /**
     * Starts a fresh deeply-buffered tunnel music stream for {@code generation}.
     * Synchronously prefills {@value #FAST_PREFILL_BUFFERS} buffers (384 ms, &lt;1.5 ms CPU) so
     * playback begins immediately, then lets {@code habitrain-tunnel-audio} fill up to
     * {@value #TARGET_QUEUED_BUFFERS} buffers (3.584 s) in the background.
     *
     * @return {@code true} if native OpenAL started immediately; {@code false} if OpenAL is unavailable
     */
    boolean startStream(int generation, float bgmGain, float sfxGain) {
        this.targetBgmGain = clamp01(bgmGain);
        this.targetSfxGain = clamp01(sfxGain);
        this.activeGeneration = generation;
        this.stopRequested = false;
        this.streamRequested = true;
        this.oneShotQueue.clear();
        this.breakoutQueue.clear();
        resetSpectrumHistory();

        if (!canTouchOpenAl()) {
            return false;
        }

        boolean started = false;
        // Fast-path: mix 3 initial chunks (~384 ms) outside alLock, upload & play immediately (<2 ms latency),
        // then wake worker to top up to all 28 buffers (3.584 s) asynchronously.
        synchronized (alLock) {
            destroyAllOpenAlResourcesLocked(false);
            if (!canTouchOpenAlLocked()) {
                return false;
            }
            if (!allocateStreamSourceLocked()) {
                return false;
            }
            this.streamEof = false;
            this.streamFadingOut = false;
            this.completedStreamSamples = 0L;
            this.mixedStreamSamples = 0L;
            this.streamStartSample = -1L;
        }

        // Fill initial fast-start chunks (mixing outside alLock so stateLock & alLock never nest!)
        byte[] initPcm = new byte[CHUNK_BYTES];
        ChunkMeta initMeta = new ChunkMeta();
        ByteBuffer initDirect = BufferUtils.createByteBuffer(CHUNK_BYTES);
        int queued = 0;
        for (int i = 0; i < FAST_PREFILL_BUFFERS; i++) {
            if (this.activeGeneration != generation || this.stopRequested) {
                break;
            }
            if (!chunkSupplier.mixChunk(generation, initPcm, initMeta)) {
                synchronized (alLock) {
                    this.streamEof = true;
                }
                break;
            }
            recordSpectrumSnapshot(initMeta);
            synchronized (alLock) {
                if (!canTouchOpenAlLocked() || streamSource == 0 || this.activeGeneration != generation) {
                    break;
                }
                if (this.streamStartSample < 0L) {
                    this.streamStartSample = initMeta.startSample;
                }
                if (queueChunkLocked(initPcm, initDirect)) {
                    queued++;
                } else {
                    break;
                }
            }
        }

        synchronized (alLock) {
            if (canTouchOpenAlLocked() && streamSource != 0 && queued > 0 && this.activeGeneration == generation) {
                AL10.alSourcef(streamSource, AL10.AL_GAIN, this.targetBgmGain);
                AL10.alSourcePlay(streamSource);
                this.audibleAnchorSample = this.streamStartSample;
                this.audibleAnchorNanos = System.nanoTime();
                this.streamPlaying = true;
                started = true;
            } else if (queued == 0) {
                destroyStreamSourceLocked(false);
            }
        }

        if (started) {
            ensureWorkerStarted();
            wakeWorker();
        }
        return started;
    }

    /** Queues a zero-latency static OpenAL one-shot cue (immune to SoundEngine.stopAll()). */
    void enqueueOneShot(int generation, byte[] pcm16, boolean useBgmGain, boolean isBreakoutTail) {
        if (pcm16 == null || pcm16.length < 4 || !openAlContextValid) return;
        if (generation != activeGeneration) return;
        oneShotQueue.offer(new PendingOneShot(generation, pcm16, useBgmGain, isBreakoutTail));
        ensureWorkerStarted();
        wakeWorker();
    }

    /**
     * Triggers Act 3 breakout: smoothly fades out the deep-buffered Act 2 stream over 65 ms while
     * immediately playing the complete pre-rendered static wind-surge and arrival-chord buffers.
     */
    void triggerBreakout(int generation, byte[] windSurgePcm16, @Nullable byte[] arrivalChordPcm16) {
        if (!openAlContextValid || generation != activeGeneration) return;
        breakoutQueue.offer(new PendingBreakout(generation, windSurgePcm16, arrivalChordPcm16));
        ensureWorkerStarted();
        wakeWorker();
    }

    /** Smoothly fades out the streaming source over 65 ms without spawning breakout tails. */
    void fadeOutStream() {
        synchronized (alLock) {
            streamRequested = false;
            if (streamSource != 0 && !streamFadingOut) {
                streamFadingOut = true;
                streamFadeStartNanos = System.nanoTime();
            }
        }
        wakeWorker();
    }

    /** Immediately stops all tunnel streaming and one-shot sources and releases OpenAL handles. */
    void stopImmediately() {
        this.stopRequested = true;
        this.streamRequested = false;
        this.activeGeneration++;
        this.oneShotQueue.clear();
        this.breakoutQueue.clear();
        synchronized (alLock) {
            destroyAllOpenAlResourcesLocked(false);
        }
        wakeWorker();
    }

    /** Stops and terminates the background worker thread on client shutdown. */
    void shutdown() {
        this.shutdownRequested = true;
        stopImmediately();
        Thread t;
        synchronized (threadLock) {
            t = workerThread;
        }
        if (t != null) {
            LockSupport.unpark(t);
            t.interrupt();
        }
    }

    /** True while either the deep-buffered stream or any breakout/one-shot OpenAL source is active. */
    boolean isActive() {
        if (streamPlaying) return true;
        synchronized (alLock) {
            return streamSource != 0 || !activeOneShots.isEmpty();
        }
    }

    /** True while any Act 3 breakout tail static source is still ringing out naturally. */
    boolean hasActiveBreakoutTail() {
        if (!breakoutQueue.isEmpty()) return true;
        synchronized (alLock) {
            for (ActiveOneShot s : activeOneShots) {
                if (s.isBreakoutTail) return true;
            }
            return false;
        }
    }

    /** Returns the estimated currently audible sample index (lock-free, 1 ms accuracy), or -1 if inactive. */
    long getAudibleSample(long nowNanos) {
        if (!streamPlaying) return -1L;
        long anchorSample = this.audibleAnchorSample;
        if (anchorSample < 0L) return -1L;
        long elapsedSamples = Math.max(0L, Math.round(((nowNanos - this.audibleAnchorNanos) / 1_000_000_000.0) * SAMPLE_RATE));
        long predicted = anchorSample + elapsedSamples;
        long maxSample = this.streamStartSample + this.mixedStreamSamples;
        if (maxSample > this.streamStartSample && predicted > maxSample) {
            return maxSample;
        }
        return predicted;
    }

    /** Looks up the sample-locked spectrum snapshot matching {@code audibleSample} (for accurate 14-bar EQ). */
    @Nullable
    SpectrumSnapshot findSpectrumForSample(long audibleSample) {
        if (audibleSample < 0L) return null;
        return selectSpectrumSnapshot(spectrumRing, audibleSample);
    }

    /** Pure testable selector finding the latest snapshot with {@code startSample <= audibleSample}. */
    static @Nullable SpectrumSnapshot selectSpectrumSnapshot(AtomicReferenceArray<SpectrumSnapshot> ring, long audibleSample) {
        SpectrumSnapshot best = null;
        long bestStart = Long.MIN_VALUE;
        int len = ring.length();
        for (int i = 0; i < len; i++) {
            SpectrumSnapshot snap = ring.get(i);
            if (snap != null) {
                long s = snap.startSample();
                if (s <= audibleSample && s >= bestStart) {
                    bestStart = s;
                    best = snap;
                }
            }
        }
        return best;
    }

    /** Pure testable queue-depth planner returning how many chunks to push to reach {@code targetBuffers}. */
    static int computeChunksToFill(int queuedBuffers, int targetBuffers, int freePoolCount, boolean eof, boolean fadingOut) {
        if (eof || fadingOut || freePoolCount <= 0) return 0;
        int deficit = Math.max(0, targetBuffers - Math.max(0, queuedBuffers));
        return Math.min(deficit, freePoolCount);
    }

    // ── Background Refill & Static Cue Worker Thread ─────────────────────────

    private void ensureWorkerStarted() {
        if (shutdownRequested) return;
        synchronized (threadLock) {
            if (workerThread != null && workerThread.isAlive()) {
                return;
            }
            Thread t = new Thread(this::runWorkerLoop, "habitrain-tunnel-audio");
            t.setDaemon(true);
            t.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 3));
            workerThread = t;
            t.start();
        }
    }

    private void wakeWorker() {
        Thread t = this.workerThread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private void runWorkerLoop() {
        try {
            while (!shutdownRequested) {
                if (stopRequested) {
                    synchronized (alLock) {
                        destroyAllOpenAlResourcesLocked(false);
                    }
                    boolean shouldExit;
                    synchronized (threadLock) {
                        shouldExit = stopRequested && !streamRequested
                                && oneShotQueue.isEmpty() && breakoutQueue.isEmpty();
                        if (shouldExit && workerThread == Thread.currentThread()) {
                            workerThread = null;
                        }
                    }
                    if (shouldExit) {
                        break;
                    }
                    continue;
                }
                if (!openAlContextValid) {
                    boolean shouldExit;
                    synchronized (threadLock) {
                        shouldExit = !streamRequested && oneShotQueue.isEmpty() && breakoutQueue.isEmpty();
                        if (shouldExit && workerThread == Thread.currentThread()) {
                            workerThread = null;
                        }
                    }
                    if (shouldExit) {
                        break;
                    }
                    LockSupport.parkNanos(REFILL_INTERVAL_NANOS);
                    continue;
                }

                int gen = this.activeGeneration;

                // 1) Process any pending Act 3 breakout crossfade immediately
                PendingBreakout breakout;
                while ((breakout = breakoutQueue.poll()) != null) {
                    if (breakout.generation != this.activeGeneration) continue;
                    handleBreakout(breakout);
                }

                // 2) Dispatch any pending zero-latency one-shot SFX cues immediately
                PendingOneShot shot;
                while ((shot = oneShotQueue.poll()) != null) {
                    if (shot.generation != this.activeGeneration) continue;
                    playStaticOneShot(shot.pcm16, shot.useBgmGain, shot.isBreakoutTail);
                }

                // 3) Recover stream if context was just recreated (e.g. audio device switch mid-tunnel)
                if (streamRequested && !stopRequested && this.activeGeneration == gen) {
                    boolean needRecreate;
                    synchronized (alLock) {
                        needRecreate = canTouchOpenAlLocked() && streamSource == 0 && !streamFadingOut && !streamEof;
                    }
                    if (needRecreate) {
                        recreateStreamAfterDeviceSwitch(gen);
                    }
                }

                // 4) Maintain 28-buffer (3.584 s) native OpenAL queue + underrun self-healing + breakout fade
                boolean needFastFadeTick = maintainStreamAndVoices(gen);

                // 5) Exit worker cleanly once both stream and all one-shot/breakout tails have finished
                boolean stillBusy;
                synchronized (alLock) {
                    stillBusy = streamSource != 0 || !activeOneShots.isEmpty();
                }
                if (!stillBusy) {
                    boolean shouldExit;
                    synchronized (threadLock) {
                        shouldExit = !streamRequested && oneShotQueue.isEmpty() && breakoutQueue.isEmpty();
                        if (shouldExit && workerThread == Thread.currentThread()) {
                            workerThread = null;
                        }
                    }
                    if (shouldExit) {
                        completionCallback.onPlaybackIdle(gen);
                        break;
                    }
                }

                LockSupport.parkNanos(needFastFadeTick ? FADE_INTERVAL_NANOS : REFILL_INTERVAL_NANOS);
            }
        } catch (Throwable t) {
            if (!shutdownRequested) {
                HabiTrainCore.LOGGER.warn("[HabiTunnelAudio] Worker loop terminated unexpectedly: {}", t.getMessage());
            }
        } finally {
            synchronized (threadLock) {
                if (workerThread == Thread.currentThread()) {
                    workerThread = null;
                }
            }
        }
    }

    private void handleBreakout(PendingBreakout breakout) {
        // Start static wind-surge & arrival-chord immediately (zero stutter through Act 3 3D world warmup!)
        if (breakout.windSurgePcm16 != null && breakout.windSurgePcm16.length >= 4) {
            playStaticOneShot(breakout.windSurgePcm16, true, true);
        }
        if (breakout.arrivalChordPcm16 != null && breakout.arrivalChordPcm16.length >= 4) {
            playStaticOneShot(breakout.arrivalChordPcm16, false, true);
        }
        // Begin 65 ms smooth fade-out on the deep-buffered Act 2 stream
        synchronized (alLock) {
            if (streamSource != 0 && !streamFadingOut) {
                streamFadingOut = true;
                streamFadeStartNanos = System.nanoTime();
            }
            streamRequested = false;
        }
    }

    private void recreateStreamAfterDeviceSwitch(int generation) {
        synchronized (alLock) {
            if (!canTouchOpenAlLocked() || streamSource != 0 || generation != this.activeGeneration) return;
            if (!allocateStreamSourceLocked()) return;
            this.completedStreamSamples = 0L;
            this.mixedStreamSamples = 0L;
            this.streamStartSample = -1L;
        }
        int queued = 0;
        for (int i = 0; i < FAST_PREFILL_BUFFERS; i++) {
            if (generation != this.activeGeneration || stopRequested) break;
            if (!chunkSupplier.mixChunk(generation, workerPcm, workerMeta)) {
                synchronized (alLock) {
                    this.streamEof = true;
                }
                break;
            }
            recordSpectrumSnapshot(workerMeta);
            synchronized (alLock) {
                if (!canTouchOpenAlLocked() || streamSource == 0 || generation != this.activeGeneration) break;
                if (this.streamStartSample < 0L) this.streamStartSample = workerMeta.startSample;
                if (queueChunkLocked(workerPcm, workerUploadBuf)) {
                    queued++;
                } else {
                    break;
                }
            }
        }
        synchronized (alLock) {
            if (canTouchOpenAlLocked() && streamSource != 0 && queued > 0 && generation == this.activeGeneration) {
                AL10.alSourcef(streamSource, AL10.AL_GAIN, this.targetBgmGain);
                AL10.alSourcePlay(streamSource);
                this.audibleAnchorSample = this.streamStartSample;
                this.audibleAnchorNanos = System.nanoTime();
                this.streamPlaying = true;
            }
        }
    }

    private boolean maintainStreamAndVoices(int generation) {
        int toFill = 0;
        boolean fading = false;

        synchronized (alLock) {
            if (!canTouchOpenAlLocked()) {
                return false;
            }
            reclaimFinishedOneShotsAndUpdateGainLocked();

            if (streamSource != 0) {
                // Unqueue any processed buffers back to our reusable pool
                int processed = AL10.alGetSourcei(streamSource, AL10.AL_BUFFERS_PROCESSED);
                if (processed > 0) {
                    int[] unqueued = new int[processed];
                    AL10.alSourceUnqueueBuffers(streamSource, unqueued);
                    for (int id : unqueued) {
                        if (freeBuffers != null && freeCount < freeBuffers.length) {
                            freeBuffers[freeCount++] = id;
                        }
                        completedStreamSamples += CHUNK_FRAMES;
                    }
                }

                int queued = AL10.alGetSourcei(streamSource, AL10.AL_BUFFERS_QUEUED);
                int alState = AL10.alGetSourcei(streamSource, AL10.AL_SOURCE_STATE);

                if (streamFadingOut) {
                    fading = true;
                    double elapsed = (System.nanoTime() - streamFadeStartNanos) / 1_000_000_000.0;
                    double k = Math.max(0.0, 1.0 - elapsed / BREAKOUT_STREAM_FADE_SEC);
                    if (k <= 0.001 || queued == 0 || alState == AL10.AL_STOPPED) {
                        destroyStreamSourceLocked(false);
                        fading = false;
                    } else {
                        AL10.alSourcef(streamSource, AL10.AL_GAIN, (float) (this.targetBgmGain * k * k));
                    }
                } else {
                    AL10.alSourcef(streamSource, AL10.AL_GAIN, this.targetBgmGain);
                    if (streamEof && queued == 0) {
                        destroyStreamSourceLocked(false);
                        streamRequested = false;
                    } else {
                        // Update sample-accurate audible clock anchor using OpenAL's hardware AL_SAMPLE_OFFSET
                        if (alState == AL10.AL_PLAYING && streamStartSample >= 0L) {
                            int headOffset = Math.max(0, AL10.alGetSourcei(streamSource, AL11.AL_SAMPLE_OFFSET));
                            this.audibleAnchorSample = streamStartSample + completedStreamSamples + headOffset;
                            this.audibleAnchorNanos = System.nanoTime();
                            this.streamPlaying = true;
                        }
                        toFill = computeChunksToFill(queued, TARGET_QUEUED_BUFFERS, freeCount, streamEof, false);
                    }
                }
            }
        }

        // Mix & upload chunks to keep the full 3.584 s native OpenAL queue saturated
        // (mixing happens OUTSIDE alLock so stateLock & alLock NEVER nest!)
        for (int i = 0; i < toFill; i++) {
            if (stopRequested || shutdownRequested || generation != this.activeGeneration) break;
            // Prioritize pending breakout/one-shots immediately between chunks!
            if (!breakoutQueue.isEmpty() || !oneShotQueue.isEmpty()) break;
            if (!chunkSupplier.mixChunk(generation, workerPcm, workerMeta)) {
                synchronized (alLock) {
                    this.streamEof = true;
                }
                break;
            }
            recordSpectrumSnapshot(workerMeta);
            synchronized (alLock) {
                if (!canTouchOpenAlLocked() || streamSource == 0 || streamFadingOut || generation != this.activeGeneration) {
                    break;
                }
                if (this.streamStartSample < 0L) {
                    this.streamStartSample = workerMeta.startSample;
                }
                if (!queueChunkLocked(workerPcm, workerUploadBuf)) {
                    break;
                }
            }
        }

        // Underrun self-healing: if OpenAL ever stopped while buffers are queued, restart immediately!
        synchronized (alLock) {
            if (canTouchOpenAlLocked() && streamSource != 0 && !streamFadingOut && generation == this.activeGeneration) {
                int queued = AL10.alGetSourcei(streamSource, AL10.AL_BUFFERS_QUEUED);
                int alState = AL10.alGetSourcei(streamSource, AL10.AL_SOURCE_STATE);
                if (queued > 0 && alState != AL10.AL_PLAYING) {
                    AL10.alSourcef(streamSource, AL10.AL_GAIN, this.targetBgmGain);
                    AL10.alSourcePlay(streamSource);
                    this.audibleAnchorSample = streamStartSample + completedStreamSamples;
                    this.audibleAnchorNanos = System.nanoTime();
                    this.streamPlaying = true;
                }
            }
        }
        return fading;
    }

    private void playStaticOneShot(byte[] pcm16, boolean useBgmGain, boolean isBreakoutTail) {
        if (pcm16 == null || pcm16.length < 4) return;
        ByteBuffer direct = BufferUtils.createByteBuffer(pcm16.length);
        direct.put(pcm16);
        direct.flip();

        synchronized (alLock) {
            if (!canTouchOpenAlLocked()) return;
            reclaimFinishedOneShotsAndUpdateGainLocked();
            while (activeOneShots.size() >= MAX_CONCURRENT_ONESHOTS) {
                ActiveOneShot oldest = activeOneShots.remove(0);
                deleteOneShotLocked(oldest, false);
            }
            int src = AL10.alGenSources();
            if (src == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                return;
            }
            int buf = AL10.alGenBuffers();
            if (buf == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alDeleteSources(src);
                return;
            }
            AL10.alBufferData(buf, AL10.AL_FORMAT_STEREO16, direct, SAMPLE_RATE);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alDeleteSources(src);
                AL10.alDeleteBuffers(buf);
                return;
            }
            float gain = useBgmGain ? this.targetBgmGain : this.targetSfxGain;
            AL10.alSourcei(src, AL10.AL_BUFFER, buf);
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 0f);
            AL10.alSourcef(src, AL10.AL_PITCH, 1.0f);
            AL10.alSourcef(src, AL10.AL_GAIN, gain);
            AL10.alSourcei(src, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcePlay(src);
            AL10.alGetError();
            activeOneShots.add(new ActiveOneShot(src, buf, useBgmGain, isBreakoutTail));
        }
    }

    // ── Internal OpenAL Resource Management (caller MUST hold alLock) ────────

    private boolean canTouchOpenAl() {
        synchronized (alLock) {
            return canTouchOpenAlLocked();
        }
    }

    private boolean canTouchOpenAlLocked() {
        if (!openAlContextValid) return false;
        try {
            return ALC10.alcGetCurrentContext() != 0L && AL.getCapabilities() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean allocateStreamSourceLocked() {
        AL10.alGetError(); // clear any stale error before creating our handles
        int src = AL10.alGenSources();
        if (src == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) {
            return false;
        }
        int[] pool = new int[TARGET_QUEUED_BUFFERS];
        AL10.alGenBuffers(pool);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteSources(src);
            for (int id : pool) {
                if (id != 0) AL10.alDeleteBuffers(id);
            }
            AL10.alGetError();
            return false;
        }
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
        AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 0f);
        AL10.alSourcef(src, AL10.AL_PITCH, 1.0f);
        AL10.alSourcef(src, AL10.AL_GAIN, this.targetBgmGain);
        AL10.alSourcei(src, AL10.AL_LOOPING, AL10.AL_FALSE);
        AL10.alGetError();

        this.streamSource = src;
        this.bufferPool = pool;
        this.freeBuffers = pool.clone();
        this.freeCount = pool.length;
        return true;
    }

    private boolean queueChunkLocked(byte[] pcm, ByteBuffer upload) {
        if (streamSource == 0 || freeBuffers == null || freeCount <= 0) return false;
        int bufId = freeBuffers[--freeCount];
        upload.clear();
        upload.put(pcm, 0, CHUNK_BYTES);
        upload.flip();
        AL10.alBufferData(bufId, AL10.AL_FORMAT_STEREO16, upload, SAMPLE_RATE);
        AL10.alSourceQueueBuffers(streamSource, bufId);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            freeBuffers[freeCount++] = bufId;
            return false;
        }
        mixedStreamSamples += CHUNK_FRAMES;
        return true;
    }

    private void reclaimFinishedOneShotsAndUpdateGainLocked() {
        if (activeOneShots.isEmpty()) return;
        Iterator<ActiveOneShot> it = activeOneShots.iterator();
        while (it.hasNext()) {
            ActiveOneShot shot = it.next();
            int st = AL10.alGetSourcei(shot.sourceId, AL10.AL_SOURCE_STATE);
            if (st == AL10.AL_STOPPED || st == 0) {
                deleteOneShotLocked(shot, false);
                it.remove();
            } else {
                float gain = shot.useBgmGain ? this.targetBgmGain : this.targetSfxGain;
                AL10.alSourcef(shot.sourceId, AL10.AL_GAIN, gain);
            }
        }
    }

    private void destroyStreamSourceLocked(boolean contextDestroying) {
        boolean hasCtx = contextDestroying ? hasRawOpenAlContext() : canTouchOpenAlLocked();
        if (streamSource != 0) {
            if (hasCtx) {
                try {
                    AL10.alSourceStop(streamSource);
                    AL10.alSourcei(streamSource, AL10.AL_BUFFER, 0);
                    AL10.alDeleteSources(streamSource);
                } catch (Throwable ignored) {
                }
            }
            streamSource = 0;
        }
        if (bufferPool != null) {
            if (hasCtx) {
                try {
                    AL10.alDeleteBuffers(bufferPool);
                    AL10.alGetError();
                } catch (Throwable ignored) {
                }
            }
            bufferPool = null;
        }
        freeBuffers = null;
        freeCount = 0;
        streamPlaying = false;
        streamFadingOut = false;
        audibleAnchorSample = -1L;
    }

    private void destroyAllOpenAlResourcesLocked(boolean contextDestroying) {
        destroyStreamSourceLocked(contextDestroying);
        if (!activeOneShots.isEmpty()) {
            for (ActiveOneShot shot : activeOneShots) {
                deleteOneShotLocked(shot, contextDestroying);
            }
            activeOneShots.clear();
        }
    }

    private void deleteOneShotLocked(ActiveOneShot shot, boolean contextDestroying) {
        boolean hasCtx = contextDestroying ? hasRawOpenAlContext() : canTouchOpenAlLocked();
        if (!hasCtx) return;
        try {
            AL10.alSourceStop(shot.sourceId);
            AL10.alSourcei(shot.sourceId, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(shot.sourceId);
            AL10.alDeleteBuffers(shot.bufferId);
            AL10.alGetError();
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasRawOpenAlContext() {
        try {
            return ALC10.alcGetCurrentContext() != 0L && AL.getCapabilities() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void resetSpectrumHistory() {
        for (int i = 0; i < SPECTRUM_RING_SIZE; i++) {
            spectrumRing.set(i, null);
        }
        spectrumWriteCursor = 0;
    }

    private void recordSpectrumSnapshot(ChunkMeta meta) {
        int slot = Math.floorMod(spectrumWriteCursor++, SPECTRUM_RING_SIZE);
        float[] barsCopy = new float[SPECTRUM_BANDS];
        System.arraycopy(meta.bars, 0, barsCopy, 0, SPECTRUM_BANDS);
        spectrumRing.set(slot, new SpectrumSnapshot(meta.startSample, barsCopy, meta.bass, meta.energy));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
