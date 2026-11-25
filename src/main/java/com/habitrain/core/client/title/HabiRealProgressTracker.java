package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Real-data loading progress &amp; 5-stage manifest pipeline for:
 * <ul>
 *   <li>Client startup &amp; resource reload ({@link ReloadInstance} + {@link HabiReloadProgressAccess})</li>
 *   <li>World generation / chunk loading ({@link StoringChunkProgressListener})</li>
 *   <li>Entering world / receiving level</li>
 *   <li>On-demand boot sequence replay ({@code R} key) verifying already-loaded subsystems</li>
 * </ul>
 *
 * <p>Preserves all original {@code habitrain_logo_v3} stage codes, titles, and rotating travel tips
 * while binding every stage progress bar and completion checkmark to real Minecraft execution state.</p>
 */
@Environment(EnvType.CLIENT)
public final class HabiRealProgressTracker {

    public record StageDef(String id, String code, String en, String zh) {}

    public record StageState(String id, String code, String en, String zh, double progress, boolean done) {}

    public record TipItem(String code, String zh, String en) {}

    public record LoadSnapshot(
            String state,             // boot | loading | ready | transition | game
            double progress,          // smoothed visual 0..1 tracking rawProgress
            double rawProgress,       // real underlying 0..1
            boolean ready,
            double readyAt,           // NaN if not ready
            double justLocked,        // 0..1 burst right after locking 100%
            @Nullable StageState stageJustCompleted,
            List<StageState> stages,
            int activeIndex,
            StageState activeStage,
            TipItem tip,
            int tipIndex,
            double tipLocalT,
            @Nullable String customLabel,
            String statusCounter      // real counter, e.g. "28/34 TASKS" or "CHUNKS 256/441"
    ) {}

    public static final List<StageDef> BOOT_STAGES = List.of(
            new StageDef("core",    "01", "CORE ENGINE & FONTS",       "字体与渲染管线校准"),
            new StageDef("audio",   "02", "AUDIO STEMS // 100 BPM",    "多轨配乐与节拍时钟同步"),
            new StageDef("track",   "03", "TRACK TOPOLOGY // 1435MM",  "第零站至第一站轨网拓扑"),
            new StageDef("cargo",   "04", "CARRIAGE & APPLE MANIFEST", "苹果车厢模型与贴图装配"),
            new StageDef("handoff", "05", "STATION 01 HANDOFF",        "第一站站台场景预热")
    );

    public static final List<StageDef> WORLD_STAGES = List.of(
            new StageDef("seed",    "01", "WORLD ENGINE & SEED",       "世界维度与种子管线校准"),
            new StageDef("biome",   "02", "TERRAIN & BIOME GRID",      "地形噪声与生物群系采样"),
            new StageDef("chunks",  "03", "SPAWN CHUNK TOPOLOGY",      "出生点区块轨网生成"),
            new StageDef("light",   "04", "LIGHTING & CARRIAGES",      "光照烘焙与车厢实体装配"),
            new StageDef("arrive",  "05", "PLATFORM HANDOFF",          "列车站台场景着陆就绪")
    );

    public static final List<TipItem> DEFAULT_TIPS = List.of(
            new TipItem("TIP 01", "保持连续的习惯节拍，列车会自动在前方铺设下一段铁轨。",
                    "KEEP THE RHYTHM TO LAY THE NEXT TRACK AHEAD"),
            new TipItem("TIP 02", "第零站是发车校准环线，驶出后将无缝衔接至主枢纽 STATION 01。",
                    "STATION 00 CALIBRATES THE LOOP BEFORE STATION 01"),
            new TipItem("TIP 03", "车厢里每一段切开的苹果纹理，都记录着一次未被中断的旅程。",
                    "EVERY APPLE SLICE CARRIES AN UNBROKEN STREAK"),
            new TipItem("TIP 04", "加载完成后登车将按 100 BPM 节拍锁死发车，音乐与镜头全程无缝衔接。",
                    "BOARDING SNAPS TO THE 100 BPM GRID FOR SEAMLESS HANDOFF")
    );

    private final List<StageDef> stageDefs;
    private final boolean[] stageDone;
    private double rawProgress;
    private double displayProgress;
    private double readyAt = Double.NaN;
    private int lastChunkFull;
    private int lastChunkTotal;
    private long lastChunkScanMs;

    public HabiRealProgressTracker(List<StageDef> stageDefs) {
        this.stageDefs = stageDefs;
        this.stageDone = new boolean[stageDefs.size()];
    }

    public void reset() {
        rawProgress = 0.0;
        displayProgress = 0.0;
        readyAt = Double.NaN;
        lastChunkFull = 0;
        lastChunkTotal = 0;
        lastChunkScanMs = 0L;
        for (int i = 0; i < stageDone.length; i++) stageDone[i] = false;
    }

    /**
     * Marks all stages &amp; progress already completed without emitting a spurious
     * {@code stageJustCompleted} event on the next frame (used for fallback TitleScreen entry).
     */
    public void markAlreadyCompleted(double atTimeSec) {
        rawProgress = 1.0;
        displayProgress = 1.0;
        readyAt = atTimeSec;
        for (int i = 0; i < stageDone.length; i++) stageDone[i] = true;
    }

    /**
     * Evaluates real Minecraft client resource-reload progress from {@link ReloadInstance},
     * {@link HabiBootAudioEngine}, and {@link HabiKineticAtlas}.
     */
    public LoadSnapshot updateResourceReload(
            double t,
            double dt,
            @Nullable ReloadInstance reload,
            boolean forceComplete,
            boolean atlasReady,
            float audioPreload,
            double minIntroReadyTime,
            boolean departing,
            boolean arrived
    ) {
        boolean reloadDone = forceComplete || reload == null || reload.isDone();
        double actual = reloadDone ? 1.0 : clamp01(reload.getActualProgress());

        double[] bucketProg = new double[5];
        boolean[] bucketDone = new boolean[5];
        String activeListenerLabel = null;
        String counter = "";

        if (reload instanceof HabiReloadProgressAccess access) {
            List<PreparableReloadListener> all = access.habitrain$getAllListeners();
            Set<PreparableReloadListener> pending = access.habitrain$getPendingPreparingListeners();
            int totalListeners = Math.max(1, access.habitrain$getListenerCount());
            int finishedReloads = access.habitrain$getFinishedReloads();
            int doneTasks = access.habitrain$getDoneTaskCount();
            int startedTasks = Math.max(1, access.habitrain$getStartedTaskCount());
            boolean allPrepDone = access.habitrain$isAllPreparationsDone();
            double applyRatio = clamp01(finishedReloads / (double) totalListeners);

            int[] bTotal = new int[5];
            int[] bPrepDone = new int[5];
            String[] bActiveName = new String[5];
            for (PreparableReloadListener listener : all) {
                int bucket = classifyReloadListener(listener);
                bTotal[bucket]++;
                if (!pending.contains(listener)) {
                    bPrepDone[bucket]++;
                } else if (bActiveName[bucket] == null) {
                    bActiveName[bucket] = friendlyListenerName(listener);
                }
            }

            // Stage 01: Core fonts + Language/Font/Splash listeners
            double coreMc = bucketProgress(bPrepDone[0], bTotal[0], actual, 0.0, 0.22);
            bucketProg[0] = reloadDone ? 1.0 : clamp01((atlasReady ? 0.35 : 0.15) + 0.65 * coreMc);

            // Stage 02: 5-stem audio decode + Minecraft SoundManager
            double soundMc = bucketProgress(bPrepDone[1], bTotal[1], actual, 0.15, 0.42);
            bucketProg[1] = reloadDone ? 1.0 : clamp01(0.45 * clamp01(audioPreload) + 0.55 * soundMc);

            // Stage 03: Textures, sprites, atlases
            bucketProg[2] = reloadDone ? 1.0 : bucketProgress(bPrepDone[2], bTotal[2], actual, 0.25, 0.68);

            // Stage 04: Models, entity/block renderers, shaders, particles
            bucketProg[3] = reloadDone ? 1.0 : bucketProgress(bPrepDone[3], bTotal[3], actual, 0.45, 0.88);

            // Stage 05: Mod reload listeners + final apply phase + reload.isDone()
            double modPrep = bucketProgress(bPrepDone[4], bTotal[4], actual, 0.65, 0.94);
            bucketProg[4] = reloadDone ? 1.0
                    : clamp01(0.40 * modPrep + 0.45 * (allPrepDone ? applyRatio : applyRatio * 0.5) + 0.15 * actual);

            for (int i = 0; i < 5; i++) {
                bucketDone[i] = reloadDone || (bucketProg[i] >= 0.995 && (i < 4 || allPrepDone));
                if (!bucketDone[i] && activeListenerLabel == null && bActiveName[i] != null) {
                    activeListenerLabel = bActiveName[i];
                }
            }
            if (reloadDone) {
                counter = totalListeners + "/" + totalListeners + " MODULES";
            } else {
                counter = doneTasks + "/" + startedTasks + " TASKS · " + finishedReloads + "/" + totalListeners + " APPLIED";
            }
        } else {
            double[] weights = {0.16, 0.22, 0.22, 0.22, 0.18};
            double acc = 0.0;
            for (int i = 0; i < 5; i++) {
                double w = weights[i];
                bucketProg[i] = reloadDone ? 1.0 : clamp01((actual - acc) / w);
                bucketDone[i] = reloadDone || actual >= acc + w - 0.001;
                acc += w;
            }
            counter = reloadDone ? "VERIFIED" : String.format(Locale.ROOT, "%02d%% LOADED", (int) Math.round(actual * 100));
        }

        // Real gate: never hit 100% until reload is genuinely done AND atlas is ready
        double realCap = (reloadDone && atlasReady) ? 1.0 : Math.min(0.98, actual);
        rawProgress = reloadDone && atlasReady ? 1.0 : clamp01(Math.min(actual, realCap));

        // Smooth visual catch-up (critically damped, never stalls, never fakes ahead of realCap)
        double rate = rawProgress >= 0.999 ? 10.0 : 7.5;
        displayProgress += (rawProgress - displayProgress) * Math.min(1.0, Math.max(0.016, dt) * rate);
        if (rawProgress >= 0.999 && displayProgress > 0.992 && t >= minIntroReadyTime) {
            displayProgress = 1.0;
        }
        displayProgress = clamp01(displayProgress);

        // Lock ready only when real reload is done AND visual progress reached 100% AND intro settled
        boolean canReady = reloadDone && atlasReady && displayProgress >= 0.999 && t >= minIntroReadyTime;
        if (Double.isNaN(readyAt) && canReady) {
            readyAt = t;
            displayProgress = 1.0;
            for (int i = 0; i < 5; i++) {
                bucketProg[i] = 1.0;
                bucketDone[i] = true;
            }
        }

        return buildSnapshot(t, bucketProg, bucketDone, activeListenerLabel, counter, departing, arrived);
    }

    /**
     * Evaluates real world-generation / spawn-chunk progress from {@link StoringChunkProgressListener}.
     */
    public LoadSnapshot updateWorldGeneration(
            double t,
            double dt,
            @Nullable StoringChunkProgressListener listener,
            boolean resolving,
            double resolveProgress
    ) {
        double chunkProg = 0.0;
        String counter = "SYNCING CHUNKS";

        if (listener != null) {
            // Note: StoringChunkProgressListener.getProgress() returns integer percent 0..100!
            chunkProg = clamp01(listener.getProgress() / 100.0);
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastChunkScanMs >= 90L || lastChunkTotal == 0) {
                lastChunkScanMs = nowMs;
                int diameter = Math.max(0, listener.getDiameter());
                int total = diameter * diameter;
                int full = 0;
                if (diameter > 0 && diameter <= 65) {
                    for (int x = 0; x < diameter; x++) {
                        for (int z = 0; z < diameter; z++) {
                            ChunkStatus st = listener.getStatus(x, z);
                            if (st == ChunkStatus.FULL) {
                                full++;
                            }
                        }
                    }
                }
                lastChunkFull = full;
                lastChunkTotal = total;
            }
            if (lastChunkTotal > 0) {
                double gridRatio = lastChunkFull / (double) lastChunkTotal;
                chunkProg = Math.max(chunkProg, gridRatio);
                counter = "CHUNKS " + lastChunkFull + "/" + lastChunkTotal;
            } else {
                counter = String.format(Locale.ROOT, "SPAWN %02d%%", listener.getProgress());
            }
        }

        rawProgress = resolving ? Math.max(chunkProg, clamp01(resolveProgress)) : chunkProg;
        double rate = resolving ? 11.0 : 8.0;
        displayProgress += (rawProgress - displayProgress) * Math.min(1.0, Math.max(0.016, dt) * rate);
        if (Math.abs(rawProgress - displayProgress) < 0.002) displayProgress = rawProgress;
        displayProgress = clamp01(displayProgress);

        double[] weights = {0.12, 0.22, 0.34, 0.20, 0.12};
        double[] bProg = new double[5];
        boolean[] bDone = new boolean[5];
        double acc = 0.0;
        for (int i = 0; i < 5; i++) {
            double w = weights[i];
            bProg[i] = displayProgress >= 0.999 ? 1.0 : clamp01((displayProgress - acc) / w);
            bDone[i] = displayProgress >= acc + w - 0.001 || displayProgress >= 0.999;
            acc += w;
        }
        if (Double.isNaN(readyAt) && resolving && displayProgress >= 0.999) {
            readyAt = t;
        }
        return buildSnapshot(t, bProg, bDone, null, counter, resolving && resolveProgress > 0.65, false);
    }

    /**
     * Evaluates real entering-world / receiving-level progress.
     */
    public LoadSnapshot updateReceivingLevel(
            double t,
            double dt,
            boolean levelReceived,
            double arriveProgress,
            String reasonTag
    ) {
        // Real non-fake progression: while waiting for chunks/packets from server, asymptotic to 0.86;
        // once levelReceived is true, advances smoothly to 1.0!
        double waitCurve = 0.86 * (1.0 - Math.exp(-t * 1.1));
        rawProgress = levelReceived ? Math.max(waitCurve, clamp01(arriveProgress)) : waitCurve;
        double rate = levelReceived ? 12.0 : 6.0;
        displayProgress += (rawProgress - displayProgress) * Math.min(1.0, Math.max(0.016, dt) * rate);
        if (levelReceived && arriveProgress >= 0.995 && displayProgress > 0.99) displayProgress = 1.0;
        displayProgress = clamp01(displayProgress);

        double[] weights = {0.18, 0.20, 0.24, 0.22, 0.16};
        double[] bProg = new double[5];
        boolean[] bDone = new boolean[5];
        double acc = 0.0;
        for (int i = 0; i < 5; i++) {
            double w = weights[i];
            bProg[i] = displayProgress >= 0.999 ? 1.0 : clamp01((displayProgress - acc) / w);
            bDone[i] = displayProgress >= acc + w - 0.001 || displayProgress >= 0.999;
            acc += w;
        }
        if (Double.isNaN(readyAt) && levelReceived && displayProgress >= 0.999) {
            readyAt = t;
        }
        String counter = levelReceived ? ("TERRAIN LOCKED // " + reasonTag) : ("RECEIVING LEVEL // " + reasonTag);
        return buildSnapshot(t, bProg, bDone, null, counter, levelReceived && arriveProgress > 0.65, false);
    }

    /**
     * On-demand full boot sequence replay ({@code R} key): walks through real already-loaded
     * client subsystem checks cleanly over {@code replayDuration} seconds.
     */
    public LoadSnapshot updateReplaySequence(
            double t,
            double dt,
            double replayDuration,
            boolean departing,
            boolean arrived
    ) {
        double u = clamp01(t / Math.max(2.8, replayDuration));
        // Same non-linear pacing curve as habitrain_logo_v3 loader.js
        double p;
        if (u < 0.22) {
            double k = u / 0.22;
            p = 0.22 * (k * k * (3.0 - 2.0 * k));
        } else if (u < 0.52) {
            double k = (u - 0.22) / 0.30;
            p = 0.22 + 0.34 * k + 0.015 * Math.sin(k * Math.PI * 2.0);
        } else if (u < 0.90) {
            double k = (u - 0.52) / 0.38;
            p = 0.56 + 0.38 * Math.pow(k, 0.92);
        } else {
            double k = (u - 0.90) / 0.10;
            p = 0.94 + 0.06 * (1.0 - Math.pow(1.0 - k, 2.5));
        }
        rawProgress = t >= replayDuration ? 1.0 : clamp01(p);
        displayProgress += (rawProgress - displayProgress) * Math.min(1.0, Math.max(0.016, dt) * 10.0);
        if (t >= replayDuration) displayProgress = 1.0;
        displayProgress = clamp01(displayProgress);

        double[] weights = {0.16, 0.22, 0.22, 0.22, 0.18};
        double[] bProg = new double[5];
        boolean[] bDone = new boolean[5];
        double acc = 0.0;
        for (int i = 0; i < 5; i++) {
            double w = weights[i];
            bProg[i] = displayProgress >= 0.999 ? 1.0 : clamp01((displayProgress - acc) / w);
            bDone[i] = displayProgress >= acc + w - 0.001 || displayProgress >= 0.999;
            acc += w;
        }
        if (Double.isNaN(readyAt) && displayProgress >= 0.999 && t >= replayDuration) {
            readyAt = t;
        }
        return buildSnapshot(t, bProg, bDone, null, "REPLAY VERIFY", departing, arrived);
    }

    private LoadSnapshot buildSnapshot(
            double t,
            double[] bucketProg,
            boolean[] bucketDone,
            @Nullable String activeDetail,
            String counter,
            boolean departing,
            boolean arrived
    ) {
        List<StageState> stages = new ArrayList<>(stageDefs.size());
        StageState justCompleted = null;
        int activeIdx = -1;
        for (int i = 0; i < stageDefs.size(); i++) {
            StageDef d = stageDefs.get(i);
            boolean done = bucketDone[i];
            double prog = done ? 1.0 : clamp01(bucketProg[i]);
            StageState st = new StageState(d.id(), d.code(), d.en(), d.zh(), prog, done);
            stages.add(st);
            if (done && !stageDone[i]) {
                stageDone[i] = true;
                justCompleted = st;
            }
            if (!done && activeIdx < 0) {
                activeIdx = i;
            }
        }
        if (activeIdx < 0) activeIdx = stages.size() - 1;
        StageState activeStage = stages.get(activeIdx);

        boolean isReady = !Double.isNaN(readyAt);
        double justLocked = isReady ? clamp01((t - readyAt) / 0.45) : 0.0;
        String state = arrived ? "game"
                : departing ? "transition"
                : isReady ? "ready"
                : t < 1.18 ? "boot" : "loading";

        double tipElapsed = Math.max(0.0, t - 1.2);
        int tipIdx = ((int) Math.floor(tipElapsed / 3.4)) % DEFAULT_TIPS.size();
        double tipLocalT = tipElapsed % 3.4;

        String customLabel = (activeDetail != null && !activeDetail.isBlank() && !isReady)
                ? ("STAGE " + activeStage.code() + "/05 // " + activeDetail.toUpperCase(Locale.ROOT))
                : null;

        return new LoadSnapshot(
                state, displayProgress, rawProgress, isReady, readyAt, justLocked,
                justCompleted, stages, activeIdx, activeStage,
                DEFAULT_TIPS.get(tipIdx), tipIdx, tipLocalT, customLabel, counter
        );
    }

    /** Classifies any Minecraft / Fabric / Mod reload listener into one of the 5 manifest stages. */
    public static int classifyReloadListener(@Nullable PreparableReloadListener listener) {
        if (listener == null) return 4;
        String name = (listener.getName() + " " + listener.getClass().getName()).toLowerCase(Locale.ROOT);
        if (name.contains("language") || name.contains("font") || name.contains("splash") || name.contains("search")) {
            return 0;
        }
        if (name.contains("sound") || name.contains("audio") || name.contains("music")) {
            return 1;
        }
        if (name.contains("texture") || name.contains("sprite") || name.contains("atlas")
                || name.contains("grasscolor") || name.contains("foliagecolor") || name.contains("painting")
                || name.contains("mobeffect") || name.contains("skin")) {
            return 2;
        }
        if (name.contains("model") || name.contains("render") || name.contains("shader")
                || name.contains("particle") || name.contains("gpu") || name.contains("blockentity")) {
            return 3;
        }
        return 4;
    }

    static String friendlyListenerName(PreparableReloadListener listener) {
        String raw = listener.getName();
        if (raw == null || raw.isBlank()) {
            raw = listener.getClass().getSimpleName();
        }
        int dot = raw.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < raw.length()) raw = raw.substring(dot + 1);
        int dollar = raw.indexOf('$');
        if (dollar > 0) raw = raw.substring(0, dollar);
        if (raw.length() > 28) raw = raw.substring(0, 28);
        return raw;
    }

    private static double bucketProgress(int done, int total, double fallbackActual, double sliceStart, double sliceEnd) {
        if (total > 0) {
            return clamp01(done / (double) total);
        }
        return clamp01((fallbackActual - sliceStart) / Math.max(0.01, sliceEnd - sliceStart));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
