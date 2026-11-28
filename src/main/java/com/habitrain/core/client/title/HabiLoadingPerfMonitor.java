package com.habitrain.core.client.title;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight frame-time, stutter-spike, draw-call, and atlas-baking telemetry recorder for
 * HabiTrain Kinetic loading screens (game startup, standalone resource reloads, and 3-act world entry).
 *
 * <p>Designed for zero per-frame string allocation during active loading: all hot-path samples are
 * stored in primitive arrays and formatted once into a concise {@code [HabiLoadPerf]} summary in
 * {@code latest.log} when the loading session completes.</p>
 */
@Environment(EnvType.CLIENT)
public final class HabiLoadingPerfMonitor {

    public enum SessionKind {
        GAME_BOOT,
        RESOURCE_RELOAD,
        WORLD_ENTRY
    }

    public record SpikeSample(
            double frameGapMs,
            double selfRenderMs,
            double atlasUploadMs,
            int drawCalls,
            String phase,
            double timelineSec,
            int progressPct,
            String context
    ) {}

    public record PhaseSummary(
            String phase,
            int frames,
            double avgFps,
            double p50Ms,
            double p95Ms,
            double maxMs,
            int stutters100Ms
    ) {}

    public record SlowTaskSample(
            double durationMs,
            String taskName
    ) {}

    public record SessionReport(
            SessionKind kind,
            String label,
            String reason,
            double totalWallMs,
            int frames,
            double avgFps,
            double onePercentLowFps,
            double p50FrameMs,
            double p95FrameMs,
            double p99FrameMs,
            double maxFrameMs,
            double firstFrameSelfMs,
            double avgSelfRenderMs,
            double maxSelfRenderMs,
            double totalAtlasUploadMs,
            double avgDrawCalls,
            int maxDrawCalls,
            int stutters50Ms,
            int stutters100Ms,
            int stutters250Ms,
            int stutters500Ms,
            int dynamicTextMisses,
            double dynamicTextBakeMs,
            int taskSliceYields,
            int managedBlockPumps,
            int worldLoadSpinPumps,
            int atlasUploadPumps,
            int shaderReloadPumps,
            long worldLoadSleepSavedMs,
            List<PhaseSummary> phases,
            List<SpikeSample> topSpikes,
            List<SlowTaskSample> topSlowTasks,
            String atlasSummary
    ) {}

    private static final int MAX_RECORDED_FRAMES = 32_768;
    private static final int MAX_TOP_SPIKES = 6;
    private static final int MAX_TOP_SLOW_TASKS = 6;
    private static final HabiLoadingPerfMonitor INSTANCE = new HabiLoadingPerfMonitor();

    public static HabiLoadingPerfMonitor getInstance() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final Map<SessionKind, ActiveSession> activeSessions = new EnumMap<>(SessionKind.class);
    private final Map<String, AtlasStats> atlasStats = new LinkedHashMap<>();
    private volatile boolean anyActiveSession;

    private HabiLoadingPerfMonitor() {}

    public boolean hasAnyActiveSession() {
        return anyActiveSession;
    }

    public void beginSession(SessionKind kind, String label) {
        if (kind == null) return;
        synchronized (lock) {
            activeSessions.put(kind, new ActiveSession(kind, label == null ? kind.name() : label, System.nanoTime()));
            anyActiveSession = !activeSessions.isEmpty();
        }
    }

    public boolean isSessionActive(SessionKind kind) {
        if (kind == null) return false;
        synchronized (lock) {
            return activeSessions.containsKey(kind);
        }
    }

    public void recordFrame(
            SessionKind kind,
            String phase,
            double timelineSec,
            double progress,
            double selfRenderMs,
            double atlasUploadMs,
            int drawCalls,
            @Nullable String context
    ) {
        recordFrameAtNanos(kind, System.nanoTime(), phase, timelineSec, progress, selfRenderMs, atlasUploadMs, drawCalls, context);
    }

    /** Deterministic timestamp entry point (package-private for unit tests). */
    void recordFrameAtNanos(
            SessionKind kind,
            long nowNanos,
            String phase,
            double timelineSec,
            double progress,
            double selfRenderMs,
            double atlasUploadMs,
            int drawCalls,
            @Nullable String context
    ) {
        if (kind == null) return;
        synchronized (lock) {
            ActiveSession s = activeSessions.get(kind);
            if (s == null) return;
            s.record(nowNanos, phase == null ? "UNKNOWN" : phase, timelineSec, progress,
                    Math.max(0.0, selfRenderMs), Math.max(0.0, atlasUploadMs), Math.max(0, drawCalls),
                    context == null ? "" : context);
        }
    }

    public void recordAtlasCpuBake(String atlasName, String tier, double cpuMs, boolean async) {
        if (atlasName == null || tier == null) return;
        String key = atlasName + "." + tier;
        synchronized (lock) {
            AtlasStats st = atlasStats.computeIfAbsent(key, AtlasStats::new);
            st.cpuMs += Math.max(0.0, cpuMs);
            st.cpuAsync = async;
            st.cpuRuns++;
        }
        HabiTrainCore.LOGGER.info(
                "[HabiLoadPerf] Atlas CPU bake: {}.{} took {} ms ({})",
                atlasName, tier, fmt1(cpuMs), async ? "async-bg" : "sync-render"
        );
    }

    public void recordAtlasUpload(String atlasName, String tier, int textureCount, double uploadMs, double syncWaitMs) {
        if (atlasName == null || tier == null) return;
        String key = atlasName + "." + tier;
        synchronized (lock) {
            AtlasStats st = atlasStats.computeIfAbsent(key, AtlasStats::new);
            st.uploadedTextures += Math.max(0, textureCount);
            st.uploadMs += Math.max(0.0, uploadMs);
            st.syncWaitMs += Math.max(0.0, syncWaitMs);
            st.uploadBatches++;
        }
    }

    public void recordDynamicTextBake(double bakeMs) {
        synchronized (lock) {
            for (ActiveSession s : activeSessions.values()) {
                s.dynamicTextMisses++;
                s.dynamicTextBakeMs += Math.max(0.0, bakeMs);
            }
        }
    }

    public void recordTaskSliceYield() {
        synchronized (lock) {
            for (ActiveSession s : activeSessions.values()) {
                s.taskSliceYields++;
            }
        }
    }

    public void recordManagedBlockPump() {
        synchronized (lock) {
            for (ActiveSession s : activeSessions.values()) {
                s.managedBlockPumps++;
            }
        }
    }

    public void recordWorldLoadSpinPump() {
        synchronized (lock) {
            for (ActiveSession s : activeSessions.values()) {
                s.worldLoadSpinPumps++;
            }
        }
    }

    public void recordAtlasUploadPump() {
        synchronized (lock) {
            for (ActiveSession s : activeSessions.values()) {
                s.atlasUploadPumps++;
            }
        }
    }

    public void recordShaderReloadPump() {
        synchronized (lock) {
            for (ActiveSession s : activeSessions.values()) {
                s.shaderReloadPumps++;
            }
        }
    }

    public void recordSlowTask(String taskName, double durationMs) {
        if (durationMs < 40.0 || !anyActiveSession) return;
        String cleanName = shortenTaskName(taskName);
        synchronized (lock) {
            for (ActiveSession s : activeSessions.values()) {
                s.recordSlowTask(cleanName, durationMs);
            }
        }
    }

    public void recordWorldLoadSleepSaved(long savedMs) {
        if (savedMs <= 0L) return;
        synchronized (lock) {
            for (ActiveSession s : activeSessions.values()) {
                s.worldLoadSleepSavedMs += savedMs;
            }
        }
    }

    public @Nullable SessionReport finishSession(SessionKind kind, String reason) {
        return finishSessionAtNanos(kind, System.nanoTime(), reason, true);
    }

    @Nullable SessionReport finishSessionAtNanos(SessionKind kind, long nowNanos, String reason, boolean emitLog) {
        if (kind == null) return null;
        SessionReport report;
        synchronized (lock) {
            ActiveSession s = activeSessions.remove(kind);
            anyActiveSession = !activeSessions.isEmpty();
            if (s == null) return null;
            report = s.buildReport(nowNanos, reason == null ? "completed" : reason, formatAtlasSummaryLocked());
        }
        if (emitLog && report.frames() > 0) {
            logReport(report);
        }
        return report;
    }

    void resetForTest() {
        synchronized (lock) {
            activeSessions.clear();
            atlasStats.clear();
            anyActiveSession = false;
        }
    }

    static String shortenTaskName(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return "unknown";
        int lastDot = raw.lastIndexOf('.');
        String s = lastDot >= 0 && lastDot + 1 < raw.length() ? raw.substring(lastDot + 1) : raw;
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    private String formatAtlasSummaryLocked() {
        if (atlasStats.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (AtlasStats st : atlasStats.values()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(st.key).append(":cpu=").append(fmt1(st.cpuMs)).append("ms(")
                    .append(st.cpuAsync ? "bg" : "sync").append(")")
                    .append(",up=").append(fmt1(st.uploadMs)).append("ms/")
                    .append(st.uploadedTextures).append("tex/")
                    .append(st.uploadBatches).append("f");
            if (st.syncWaitMs >= 0.1) {
                sb.append(",wait=").append(fmt1(st.syncWaitMs)).append("ms");
            }
        }
        return sb.toString();
    }

    private static void logReport(SessionReport r) {
        StringBuilder phaseLine = new StringBuilder();
        for (PhaseSummary p : r.phases()) {
            if (phaseLine.length() > 0) phaseLine.append(" | ");
            phaseLine.append(p.phase()).append("[f=").append(p.frames())
                    .append(",fps=").append(fmt1(p.avgFps()))
                    .append(",p50=").append(fmt1(p.p50Ms()))
                    .append("ms,p95=").append(fmt1(p.p95Ms()))
                    .append("ms,max=").append(fmt1(p.maxMs()))
                    .append("ms,>100ms=").append(p.stutters100Ms()).append("]");
        }

        StringBuilder spikesLine = new StringBuilder();
        int idx = 1;
        for (SpikeSample sp : r.topSpikes()) {
            if (spikesLine.length() > 0) spikesLine.append(" | ");
            spikesLine.append('#').append(idx++).append(' ')
                    .append(fmt1(sp.frameGapMs())).append("ms")
                    .append("(self=").append(fmt1(sp.selfRenderMs())).append("ms");
            if (sp.atlasUploadMs() >= 0.05) {
                spikesLine.append(",up=").append(fmt1(sp.atlasUploadMs())).append("ms");
            }
            spikesLine.append(",dc=").append(sp.drawCalls()).append(")@")
                    .append(sp.phase()).append(" t=").append(fmt2(sp.timelineSec())).append("s ")
                    .append(sp.progressPct()).append('%');
            if (!sp.context().isEmpty()) {
                spikesLine.append('[').append(sp.context()).append(']');
            }
        }

        StringBuilder slowLine = new StringBuilder();
        for (SlowTaskSample st : r.topSlowTasks()) {
            if (slowLine.length() > 0) slowLine.append(" | ");
            slowLine.append(fmt1(st.durationMs())).append("ms[").append(st.taskName()).append(']');
        }

        HabiTrainCore.LOGGER.info(
                "[HabiLoadPerf] === {} ({}) [{}] duration={}ms frames={} avgFPS={} 1%LowFPS={} ===\n"
                        + "  FrameGap(ms): P50={} P95={} P99={} MAX={} | stutters >=50ms:{} >=100ms:{} >=250ms:{} >=500ms:{}\n"
                        + "  SelfRender  : frame0={}ms avg={}ms MAX={}ms | atlasUpload={}ms | drawCalls avg={} MAX={} | textMiss={}({}ms)\n"
                        + "  Pump        : sliceYields={} | managedBlock={} | worldSpin={} | atlasUpload={} | shaderReload={} | sleepSaved={}ms\n"
                        + "  Phases      : {}\n"
                        + "  Atlas       : {}\n"
                        + "  TopSpikes   : {}\n"
                        + "  SlowTasks   : {}",
                r.kind(), r.label(), r.reason(),
                fmt1(r.totalWallMs()), r.frames(), fmt1(r.avgFps()), fmt1(r.onePercentLowFps()),
                fmt1(r.p50FrameMs()), fmt1(r.p95FrameMs()), fmt1(r.p99FrameMs()), fmt1(r.maxFrameMs()),
                r.stutters50Ms(), r.stutters100Ms(), r.stutters250Ms(), r.stutters500Ms(),
                fmt1(r.firstFrameSelfMs()), fmt2(r.avgSelfRenderMs()), fmt1(r.maxSelfRenderMs()),
                fmt1(r.totalAtlasUploadMs()), fmt1(r.avgDrawCalls()), r.maxDrawCalls(),
                r.dynamicTextMisses(), fmt1(r.dynamicTextBakeMs()),
                r.taskSliceYields(), r.managedBlockPumps(), r.worldLoadSpinPumps(),
                r.atlasUploadPumps(), r.shaderReloadPumps(), r.worldLoadSleepSavedMs(),
                phaseLine.isEmpty() ? "none" : phaseLine,
                r.atlasSummary(),
                spikesLine.isEmpty() ? "none" : spikesLine,
                slowLine.isEmpty() ? "none" : slowLine
        );
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ── Internal Accumulators ────────────────────────────────────────────────

    private static final class AtlasStats {
        final String key;
        double cpuMs;
        boolean cpuAsync;
        int cpuRuns;
        int uploadedTextures;
        double uploadMs;
        double syncWaitMs;
        int uploadBatches;

        AtlasStats(String key) {
            this.key = key;
        }
    }

    private static final class PhaseBucket {
        final String phase;
        double[] gaps = new double[128];
        int gapCount;
        int frames;
        double sumGapMs;
        double maxMs;
        int stutters100Ms;

        PhaseBucket(String phase) {
            this.phase = phase;
        }

        void add(boolean hasGap, double gapMs) {
            frames++;
            if (!hasGap) return;
            sumGapMs += gapMs;
            if (gapMs > maxMs) maxMs = gapMs;
            if (gapMs >= 100.0) stutters100Ms++;
            if (gapCount < MAX_RECORDED_FRAMES) {
                if (gapCount == gaps.length) {
                    gaps = Arrays.copyOf(gaps, Math.min(MAX_RECORDED_FRAMES, gaps.length * 2));
                }
                gaps[gapCount++] = gapMs;
            }
        }

        PhaseSummary toSummary() {
            if (gapCount == 0) {
                return new PhaseSummary(phase, frames, 0.0, 0.0, 0.0, 0.0, 0);
            }
            double[] sorted = Arrays.copyOf(gaps, gapCount);
            Arrays.sort(sorted);
            double avgFps = sumGapMs > 0.001 ? (gapCount * 1000.0 / sumGapMs) : 0.0;
            return new PhaseSummary(
                    phase, frames, avgFps,
                    percentile(sorted, 0.50),
                    percentile(sorted, 0.95),
                    maxMs, stutters100Ms
            );
        }
    }

    private static final class ActiveSession {
        final SessionKind kind;
        final String label;
        final long startNanos;
        long firstFrameNanos = -1L;
        long lastFrameNanos = -1L;

        int frames;
        double[] frameGapsMs = new double[256];
        int gapCount;
        double sumGapMs;
        double maxGapMs;

        double firstFrameSelfMs;
        double sumSelfRenderMs;
        double maxSelfRenderMs;
        double totalAtlasUploadMs;
        long sumDrawCalls;
        int maxDrawCalls;

        int stutters50Ms;
        int stutters100Ms;
        int stutters250Ms;
        int stutters500Ms;

        int dynamicTextMisses;
        double dynamicTextBakeMs;

        int taskSliceYields;
        int managedBlockPumps;
        int worldLoadSpinPumps;
        int atlasUploadPumps;
        int shaderReloadPumps;
        long worldLoadSleepSavedMs;

        final Map<String, PhaseBucket> phaseBuckets = new LinkedHashMap<>();
        final List<SpikeSample> topSpikes = new ArrayList<>(MAX_TOP_SPIKES + 1);
        final List<SlowTaskSample> topSlowTasks = new ArrayList<>(MAX_TOP_SLOW_TASKS + 1);

        void recordSlowTask(String taskName, double durationMs) {
            if (topSlowTasks.size() < MAX_TOP_SLOW_TASKS
                    || durationMs > topSlowTasks.get(topSlowTasks.size() - 1).durationMs()) {
                topSlowTasks.add(new SlowTaskSample(durationMs, taskName));
                topSlowTasks.sort(Comparator.comparingDouble(SlowTaskSample::durationMs).reversed());
                if (topSlowTasks.size() > MAX_TOP_SLOW_TASKS) {
                    topSlowTasks.remove(topSlowTasks.size() - 1);
                }
            }
        }

        ActiveSession(SessionKind kind, String label, long startNanos) {
            this.kind = kind;
            this.label = label;
            this.startNanos = startNanos;
        }

        void record(
                long nowNanos,
                String phase,
                double timelineSec,
                double progress,
                double selfRenderMs,
                double atlasUploadMs,
                int drawCalls,
                String context
        ) {
            boolean hasGap = lastFrameNanos >= 0L;
            double gapMs = hasGap ? Math.max(0.0, (nowNanos - lastFrameNanos) / 1_000_000.0) : 0.0;
            if (firstFrameNanos < 0L) {
                firstFrameNanos = nowNanos;
                firstFrameSelfMs = selfRenderMs;
            }
            lastFrameNanos = nowNanos;
            frames++;

            sumSelfRenderMs += selfRenderMs;
            if (selfRenderMs > maxSelfRenderMs) maxSelfRenderMs = selfRenderMs;
            totalAtlasUploadMs += atlasUploadMs;
            sumDrawCalls += drawCalls;
            if (drawCalls > maxDrawCalls) maxDrawCalls = drawCalls;

            PhaseBucket bucket = phaseBuckets.computeIfAbsent(phase, PhaseBucket::new);
            bucket.add(hasGap, gapMs);

            if (!hasGap) return;

            sumGapMs += gapMs;
            if (gapMs > maxGapMs) maxGapMs = gapMs;
            if (gapMs >= 50.0) stutters50Ms++;
            if (gapMs >= 100.0) stutters100Ms++;
            if (gapMs >= 250.0) stutters250Ms++;
            if (gapMs >= 500.0) stutters500Ms++;

            if (gapCount < MAX_RECORDED_FRAMES) {
                if (gapCount == frameGapsMs.length) {
                    frameGapsMs = Arrays.copyOf(frameGapsMs, Math.min(MAX_RECORDED_FRAMES, frameGapsMs.length * 2));
                }
                frameGapsMs[gapCount++] = gapMs;
            }

            if (gapMs >= 25.0 || topSpikes.size() < MAX_TOP_SPIKES) {
                if (topSpikes.size() < MAX_TOP_SPIKES || gapMs > topSpikes.get(topSpikes.size() - 1).frameGapMs()) {
                    int pct = Math.max(0, Math.min(100, (int) Math.round(progress * 100.0)));
                    topSpikes.add(new SpikeSample(gapMs, selfRenderMs, atlasUploadMs, drawCalls, phase, timelineSec, pct, context));
                    topSpikes.sort(Comparator.comparingDouble(SpikeSample::frameGapMs).reversed());
                    if (topSpikes.size() > MAX_TOP_SPIKES) {
                        topSpikes.remove(topSpikes.size() - 1);
                    }
                }
            }
        }

        SessionReport buildReport(long nowNanos, String reason, String atlasSummary) {
            double totalWallMs = Math.max(0.0, (nowNanos - startNanos) / 1_000_000.0);
            double[] sorted = Arrays.copyOf(frameGapsMs, gapCount);
            Arrays.sort(sorted);

            double avgFps = sumGapMs > 0.001 ? (gapCount * 1000.0 / sumGapMs) : 0.0;
            double p50 = percentile(sorted, 0.50);
            double p95 = percentile(sorted, 0.95);
            double p99 = percentile(sorted, 0.99);
            double onePctLowFps = computeOnePercentLowFps(sorted);

            double avgSelf = frames > 0 ? (sumSelfRenderMs / frames) : 0.0;
            double avgDc = frames > 0 ? (sumDrawCalls / (double) frames) : 0.0;

            List<PhaseSummary> phases = new ArrayList<>(phaseBuckets.size());
            for (PhaseBucket pb : phaseBuckets.values()) {
                phases.add(pb.toSummary());
            }
            return new SessionReport(
                    kind, label, reason, totalWallMs, frames,
                    avgFps, onePctLowFps, p50, p95, p99, maxGapMs,
                    firstFrameSelfMs, avgSelf, maxSelfRenderMs, totalAtlasUploadMs,
                    avgDc, maxDrawCalls,
                    stutters50Ms, stutters100Ms, stutters250Ms, stutters500Ms,
                    dynamicTextMisses, dynamicTextBakeMs,
                    taskSliceYields, managedBlockPumps, worldLoadSpinPumps,
                    atlasUploadPumps, shaderReloadPumps, worldLoadSleepSavedMs,
                    List.copyOf(phases), List.copyOf(topSpikes), List.copyOf(topSlowTasks), atlasSummary
            );
        }
    }

    static double percentile(double[] sorted, double p) {
        if (sorted == null || sorted.length == 0) return 0.0;
        double idx = Math.max(0.0, Math.min(1.0, p)) * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(sorted.length - 1, lo + 1);
        double frac = idx - lo;
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac;
    }

    static double computeOnePercentLowFps(double[] sortedGapsMs) {
        if (sortedGapsMs == null || sortedGapsMs.length == 0) return 0.0;
        int worstCount = Math.max(1, (int) Math.ceil(sortedGapsMs.length * 0.01));
        double sum = 0.0;
        for (int i = sortedGapsMs.length - worstCount; i < sortedGapsMs.length; i++) {
            sum += sortedGapsMs[i];
        }
        double avgWorstMs = sum / worstCount;
        return avgWorstMs > 0.001 ? (1000.0 / avgWorstMs) : 0.0;
    }
}
