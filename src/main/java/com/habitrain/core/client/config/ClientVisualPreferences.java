package com.habitrain.core.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.persist.AtomicJsonFiles;
import com.habitrain.core.scene.replay.ReplayMusicPlan;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Client-only visual preferences that must not be synchronized with a server.
 *
 * <p>The main {@code habitrain_core.json} is server-authoritative and participates in
 * config synchronization. Title-screen visuals are a local choice, so they live in a
 * separate file and are applied immediately.</p>
 *
 * <p>场景传输/性能项放在同一个文件的 {@code scenePerformance} 子对象里，而不是另起一个
 * 配置类：两个类同时读写同一个 JSON 文件会在读-改-写之间互相覆盖。</p>
 *
 * <p>All getters read an immutable {@code volatile Snapshot} with zero lock contention on
 * the render thread; setters publish the new snapshot immediately and coalesce disk writes
 * asynchronously on a daemon worker (no {@code fsync} stall on UI sliders or toggles).</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientVisualPreferences {
    private static final String FILE_NAME = "habitrain_core-client.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED = true;
    private static final boolean DEFAULT_KINETIC_BOOT_UI_ENABLED = true;
    private static final boolean DEFAULT_KINETIC_UI_SOUND_ENABLED = true;
    private static final float DEFAULT_KINETIC_UI_BGM_VOLUME = 1.0f;
    private static final boolean DEFAULT_KINETIC_UI_AUTO_ENTER = false;
    private static final String SCENE_PERFORMANCE = "scenePerformance";
    private static final String REPLAY_VIDEO = "replayVideo";
    private static final String REPLAY_AUDIO = "replayAudio";
    private static final String DEFAULT_REPLAY_RESOLUTION = "720p";
    private static final boolean DEFAULT_REPLAY_COMPRESS = true;
    private static final String DEFAULT_REPLAY_QUALITY = "balanced";
    private static final String DEFAULT_REPLAY_MUSIC = ReplayMusicPlan.Level.MEDIUM.id;
    private static final String DEFAULT_REPLAY_STYLE = "brass";
    private static final long SAVE_DEBOUNCE_MS = 150L;

    private record Snapshot(
            boolean customTitlePanoramaEnabled,
            boolean kineticBootUiEnabled,
            boolean kineticUiSoundEnabled,
            float kineticUiBgmVolume,
            boolean kineticUiAutoEnter,
            boolean lightweightInventory,
            boolean decoratedShopEnabled,
            boolean idiotFaceOverlay,
            boolean idiotLargeJackpot,
            int idiotParticles,
            String replayVideoResolution,
            boolean replayVideoCompress,
            String replayVideoQuality,
            String replayVideoMusic,
            String replayVideoStyle,
            String replayVideoFfmpegPath,
            ReplayAudioSettings replayAudio,
            int requestTimeoutMs,
            int maxChunkRetries,
            int diskCacheQuotaMiB,
            boolean partialResumeEnabled,
            int partialCacheQuotaMiB,
            int partialExpiryHours,
            int transferWindowChunks,
            int meshBatchMinSections,
            int meshBuildBudgetMsPerFrame,
            int memoryCacheQuotaMiB,
            int meshCacheQuotaMiB
    ) {
        static Snapshot defaults() {
            return new Snapshot(
                    DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED,
                    DEFAULT_KINETIC_BOOT_UI_ENABLED,
                    DEFAULT_KINETIC_UI_SOUND_ENABLED,
                    DEFAULT_KINETIC_UI_BGM_VOLUME,
                    DEFAULT_KINETIC_UI_AUTO_ENTER,
                    false,
                    true,
                    true,
                    true,
                    2,
                    DEFAULT_REPLAY_RESOLUTION,
                    DEFAULT_REPLAY_COMPRESS,
                    DEFAULT_REPLAY_QUALITY,
                    DEFAULT_REPLAY_MUSIC,
                    DEFAULT_REPLAY_STYLE,
                    "",
                    ReplayAudioSettings.DEFAULT,
                    SceneClientPerformanceRules.DEFAULT_REQUEST_TIMEOUT_MS,
                    SceneClientPerformanceRules.DEFAULT_MAX_CHUNK_RETRIES,
                    SceneClientPerformanceRules.DEFAULT_DISK_CACHE_QUOTA_MIB,
                    SceneClientPerformanceRules.DEFAULT_PARTIAL_RESUME_ENABLED,
                    SceneClientPerformanceRules.DEFAULT_PARTIAL_CACHE_QUOTA_MIB,
                    SceneClientPerformanceRules.DEFAULT_PARTIAL_EXPIRY_HOURS,
                    SceneClientPerformanceRules.DEFAULT_TRANSFER_WINDOW_CHUNKS,
                    SceneClientPerformanceRules.DEFAULT_MESH_BATCH_MIN_SECTIONS,
                    SceneClientPerformanceRules.DEFAULT_MESH_BUILD_BUDGET_MS_PER_FRAME,
                    SceneClientPerformanceRules.DEFAULT_MEMORY_CACHE_QUOTA_MIB,
                    SceneClientPerformanceRules.DEFAULT_MESH_CACHE_QUOTA_MIB
            );
        }

        Snapshot withCustomTitlePanoramaEnabled(boolean v) {
            return new Snapshot(v, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withKineticBootUiEnabled(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, v, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withKineticUiSoundEnabled(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, v, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withKineticUiBgmVolume(float v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, v, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withKineticUiAutoEnter(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, v,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withLightweightInventory(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    v, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withDecoratedShopEnabled(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, v, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withIdiotFaceOverlay(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, v, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withIdiotLargeJackpot(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, v, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withIdiotParticles(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, v,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withReplayVideoResolution(String v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    v, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withReplayVideoCompress(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, v, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withReplayVideoQuality(String v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, v, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withReplayVideoMusic(String v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, v, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withReplayVideoStyle(String v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, v,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withReplayVideoFfmpegPath(String v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    v, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withReplayAudio(ReplayAudioSettings v) {
            ReplayAudioSettings safe = v == null ? ReplayAudioSettings.DEFAULT : v;
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, safe, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withRequestTimeoutMs(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, v, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withMaxChunkRetries(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, v, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withDiskCacheQuotaMiB(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, v, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withPartialResumeEnabled(boolean v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, v,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withPartialCacheQuotaMiB(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    v, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withPartialExpiryHours(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, v, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withTransferWindowChunks(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, v, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withMeshBuildBudgetMsPerFrame(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    v, memoryCacheQuotaMiB, meshCacheQuotaMiB);
        }

        Snapshot withMemoryCacheQuotaMiB(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, v, meshCacheQuotaMiB);
        }

        Snapshot withMeshCacheQuotaMiB(int v) {
            return new Snapshot(customTitlePanoramaEnabled, kineticBootUiEnabled, kineticUiSoundEnabled, kineticUiBgmVolume, kineticUiAutoEnter,
                    lightweightInventory, decoratedShopEnabled, idiotFaceOverlay, idiotLargeJackpot, idiotParticles,
                    replayVideoResolution, replayVideoCompress, replayVideoQuality, replayVideoMusic, replayVideoStyle,
                    replayVideoFfmpegPath, replayAudio, requestTimeoutMs, maxChunkRetries, diskCacheQuotaMiB, partialResumeEnabled,
                    partialCacheQuotaMiB, partialExpiryHours, transferWindowChunks, meshBatchMinSections,
                    meshBuildBudgetMsPerFrame, memoryCacheQuotaMiB, v);
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("idiotFaceOverlay", idiotFaceOverlay);
            root.addProperty("idiotLargeJackpot", idiotLargeJackpot);
            root.addProperty("idiotParticles", idiotParticles);
            root.addProperty("customTitlePanoramaEnabled", customTitlePanoramaEnabled);
            root.addProperty("kineticBootUiEnabled", kineticBootUiEnabled);
            root.addProperty("kineticUiSoundEnabled", kineticUiSoundEnabled);
            root.addProperty("kineticUiBgmVolume", kineticUiBgmVolume);
            root.addProperty("kineticUiAutoEnter", kineticUiAutoEnter);
            root.addProperty("lightweightInventory", lightweightInventory);
            root.addProperty("decoratedShopEnabled", decoratedShopEnabled);
            JsonObject performance = new JsonObject();
            performance.addProperty("requestTimeoutMs", requestTimeoutMs);
            performance.addProperty("maxChunkRetries", maxChunkRetries);
            performance.addProperty("diskCacheQuotaMiB", diskCacheQuotaMiB);
            performance.addProperty("partialResumeEnabled", partialResumeEnabled);
            performance.addProperty("partialCacheQuotaMiB", partialCacheQuotaMiB);
            performance.addProperty("partialExpiryHours", partialExpiryHours);
            performance.addProperty("transferWindowChunks", transferWindowChunks);
            performance.addProperty("meshBuildBudgetMsPerFrame", meshBuildBudgetMsPerFrame);
            performance.addProperty("meshBatchMinSections", meshBatchMinSections);
            performance.addProperty("memoryCacheQuotaMiB", memoryCacheQuotaMiB);
            performance.addProperty("meshCacheQuotaMiB", meshCacheQuotaMiB);
            root.add(SCENE_PERFORMANCE, performance);
            JsonObject replayVideo = new JsonObject();
            replayVideo.addProperty("resolution", replayVideoResolution);
            replayVideo.addProperty("compress", replayVideoCompress);
            replayVideo.addProperty("quality", replayVideoQuality);
            replayVideo.addProperty("music", replayVideoMusic);
            replayVideo.addProperty("style", replayVideoStyle);
            if (!replayVideoFfmpegPath.isBlank()) {
                replayVideo.addProperty("ffmpegPath", replayVideoFfmpegPath);
            }
            root.add(REPLAY_VIDEO, replayVideo);
            root.add(REPLAY_AUDIO, (replayAudio == null ? ReplayAudioSettings.DEFAULT : replayAudio).toJson());
            return root;
        }
    }

    private static final Object WRITE_LOCK = new Object();
    private static volatile @Nullable Snapshot snapshot;
    private static @Nullable ScheduledExecutorService saveExecutor;
    private static @Nullable ScheduledFuture<?> pendingSave;
    private static boolean dirty;
    private static boolean replayAudioPreviewPending;
    private static boolean shuttingDown;

    private ClientVisualPreferences() {
    }

    private static Snapshot ensureLoaded() {
        Snapshot current = snapshot;
        if (current != null) return current;
        load();
        Snapshot loaded = snapshot;
        return loaded != null ? loaded : Snapshot.defaults();
    }

    public static void load() {
        if (snapshot != null) return;
        synchronized (WRITE_LOCK) {
            if (snapshot != null) return;
            AtomicJsonFiles.JsonLoad<JsonObject> result = AtomicJsonFiles.readJson(
                    file(), JsonObject.class, GSON);
            if (!result.ok()) {
                if (result.corrupt()) {
                    HabiTrainCore.LOGGER.warn("客户端视觉配置损坏，已回退为默认设置");
                }
                snapshot = Snapshot.defaults();
                return;
            }
            JsonObject root = result.value();
            boolean lightweight = readBoolean(root, "lightweightInventory", false);
            boolean decoratedShop = readBoolean(root, "decoratedShopEnabled", true);
            boolean idiotFace = readBoolean(root, "idiotFaceOverlay", true);
            boolean idiotJackpot = readBoolean(root, "idiotLargeJackpot", true);
            int idiotPart = readInt(root, "idiotParticles", 2, value -> Math.clamp(value, 0, 2));
            boolean customTitlePanorama = !root.has("customTitlePanoramaEnabled")
                    ? DEFAULT_CUSTOM_TITLE_PANORAMA_ENABLED
                    : root.get("customTitlePanoramaEnabled").isJsonPrimitive()
                            && root.get("customTitlePanoramaEnabled").getAsBoolean();
            boolean kineticBoot = readBoolean(root, "kineticBootUiEnabled", DEFAULT_KINETIC_BOOT_UI_ENABLED);
            boolean kineticSound = readBoolean(root, "kineticUiSoundEnabled", DEFAULT_KINETIC_UI_SOUND_ENABLED);
            float kineticBgm = readUnitFloat(root, "kineticUiBgmVolume", DEFAULT_KINETIC_UI_BGM_VOLUME);
            boolean kineticAutoEnter = readBoolean(root, "kineticUiAutoEnter", DEFAULT_KINETIC_UI_AUTO_ENTER);

            JsonObject performance = root.has(SCENE_PERFORMANCE) && root.get(SCENE_PERFORMANCE).isJsonObject()
                    ? root.getAsJsonObject(SCENE_PERFORMANCE)
                    : new JsonObject();
            int reqTimeout = readInt(performance, "requestTimeoutMs",
                    SceneClientPerformanceRules.DEFAULT_REQUEST_TIMEOUT_MS,
                    SceneClientPerformanceRules::clampRequestTimeoutMs);
            int maxRetries = readInt(performance, "maxChunkRetries",
                    SceneClientPerformanceRules.DEFAULT_MAX_CHUNK_RETRIES,
                    SceneClientPerformanceRules::clampMaxChunkRetries);
            int diskQuota = readInt(performance, "diskCacheQuotaMiB",
                    SceneClientPerformanceRules.DEFAULT_DISK_CACHE_QUOTA_MIB,
                    SceneClientPerformanceRules::clampDiskCacheQuotaMiB);
            boolean partialResume = readBoolean(performance, "partialResumeEnabled",
                    SceneClientPerformanceRules.DEFAULT_PARTIAL_RESUME_ENABLED);
            int partialQuota = readInt(performance, "partialCacheQuotaMiB",
                    SceneClientPerformanceRules.DEFAULT_PARTIAL_CACHE_QUOTA_MIB,
                    SceneClientPerformanceRules::clampPartialCacheQuotaMiB);
            int partialExpiry = readInt(performance, "partialExpiryHours",
                    SceneClientPerformanceRules.DEFAULT_PARTIAL_EXPIRY_HOURS,
                    SceneClientPerformanceRules::clampPartialExpiryHours);
            int transferWindow = readInt(performance, "transferWindowChunks",
                    SceneClientPerformanceRules.DEFAULT_TRANSFER_WINDOW_CHUNKS,
                    SceneClientPerformanceRules::clampTransferWindowChunks);
            int buildBudget = readInt(performance, "meshBuildBudgetMsPerFrame",
                    SceneClientPerformanceRules.DEFAULT_MESH_BUILD_BUDGET_MS_PER_FRAME,
                    SceneClientPerformanceRules::clampMeshBuildBudgetMsPerFrame);
            int memQuota = readInt(performance, "memoryCacheQuotaMiB",
                    SceneClientPerformanceRules.DEFAULT_MEMORY_CACHE_QUOTA_MIB,
                    SceneClientPerformanceRules::clampMemoryCacheQuotaMiB);
            int meshQuota = readInt(performance, "meshCacheQuotaMiB",
                    SceneClientPerformanceRules.DEFAULT_MESH_CACHE_QUOTA_MIB,
                    SceneClientPerformanceRules::clampMeshCacheQuotaMiB);
            int batchMin = readInt(performance, "meshBatchMinSections",
                    SceneClientPerformanceRules.DEFAULT_MESH_BATCH_MIN_SECTIONS,
                    SceneClientPerformanceRules::clampMeshBatchMinSections);

            JsonObject replayVideo = root.has(REPLAY_VIDEO) && root.get(REPLAY_VIDEO).isJsonObject()
                    ? root.getAsJsonObject(REPLAY_VIDEO)
                    : new JsonObject();
            String rvRes = normalizeReplayResolution(readString(replayVideo, "resolution", DEFAULT_REPLAY_RESOLUTION));
            boolean rvCompress = readBoolean(replayVideo, "compress", DEFAULT_REPLAY_COMPRESS);
            String rvQuality = normalizeReplayQuality(readString(replayVideo, "quality", DEFAULT_REPLAY_QUALITY));
            String rvMusic = ReplayMusicPlan.Level.fromId(readString(replayVideo, "music", DEFAULT_REPLAY_MUSIC)).id;
            String rvStyle = com.habitrain.core.scene.replay.ReplayExportContentOptions.VisualStyle
                    .fromId(readString(replayVideo, "style", DEFAULT_REPLAY_STYLE)).id;
            String rvFfmpeg = readString(replayVideo, "ffmpegPath", "").trim();

            JsonObject replayAudioObj = root.has(REPLAY_AUDIO) && root.get(REPLAY_AUDIO).isJsonObject()
                    ? root.getAsJsonObject(REPLAY_AUDIO)
                    : null;
            ReplayAudioSettings replayAudio = ReplayAudioSettings.fromJson(replayAudioObj);

            snapshot = new Snapshot(
                    customTitlePanorama, kineticBoot, kineticSound, kineticBgm, kineticAutoEnter,
                    lightweight, decoratedShop, idiotFace, idiotJackpot, idiotPart,
                    rvRes, rvCompress, rvQuality, rvMusic, rvStyle, rvFfmpeg, replayAudio,
                    reqTimeout, maxRetries, diskQuota, partialResume, partialQuota, partialExpiry,
                    transferWindow, batchMin, buildBudget, memQuota, meshQuota
            );
        }
    }

    private static boolean update(UnaryOperator<Snapshot> updater) {
        synchronized (WRITE_LOCK) {
            Snapshot current = ensureLoaded();
            Snapshot next = updater.apply(current);
            if (next.equals(current)) {
                return true;
            }
            snapshot = next;
            dirty = true;
            scheduleSaveLocked();
            return true;
        }
    }

    private static void scheduleSaveLocked() {
        if (shuttingDown) {
            flushNowLocked();
            return;
        }
        if (saveExecutor == null || saveExecutor.isShutdown()) {
            saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "habitrain-client-prefs-io");
                t.setDaemon(true);
                return t;
            });
        }
        if (pendingSave != null && !pendingSave.isDone()) {
            pendingSave.cancel(false);
        }
        pendingSave = saveExecutor.schedule(ClientVisualPreferences::flushScheduled, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private static void flushScheduled() {
        Snapshot toWrite;
        synchronized (WRITE_LOCK) {
            if (!dirty) return;
            toWrite = snapshot;
            dirty = false;
            pendingSave = null;
        }
        if (toWrite != null) {
            writeSnapshot(toWrite);
        }
    }

    private static void flushNowLocked() {
        if (!dirty || snapshot == null) return;
        Snapshot toWrite = snapshot;
        dirty = false;
        if (pendingSave != null) {
            pendingSave.cancel(false);
            pendingSave = null;
        }
        writeSnapshot(toWrite);
    }

    private static boolean writeSnapshot(Snapshot s) {
        // Non-authoritative local client preferences: skip fsync to avoid blocking UI/IO.
        boolean saved = AtomicJsonFiles.writeJson(file(), s.toJson(), GSON, false, false);
        if (!saved) {
            HabiTrainCore.LOGGER.error("无法保存客户端视觉配置 {}", file());
        }
        return saved;
    }

    /** Flushes any pending debounced preferences and stops the background writer thread. */
    public static void flushAndShutdown() {
        ScheduledExecutorService exec;
        Snapshot toWrite = null;
        synchronized (WRITE_LOCK) {
            shuttingDown = true;
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
            if ((dirty || replayAudioPreviewPending) && snapshot != null) {
                toWrite = snapshot;
                dirty = false;
                replayAudioPreviewPending = false;
            }
            exec = saveExecutor;
            saveExecutor = null;
        }
        if (exec != null) {
            exec.shutdown();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (toWrite != null) {
            writeSnapshot(toWrite);
        }
    }

    private static String readString(JsonObject root, String key, String fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        try {
            String value = root.get(key).getAsString();
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String normalizeReplayResolution(String raw) {
        if (raw == null) return DEFAULT_REPLAY_RESOLUTION;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "480p", "720p", "1080p", "1440p", "2160p" -> raw.trim().toLowerCase(java.util.Locale.ROOT);
            default -> DEFAULT_REPLAY_RESOLUTION;
        };
    }

    private static String normalizeReplayQuality(String raw) {
        if (raw == null) return DEFAULT_REPLAY_QUALITY;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "light", "balanced", "original" -> raw.trim().toLowerCase(java.util.Locale.ROOT);
            default -> DEFAULT_REPLAY_QUALITY;
        };
    }

    private static int readInt(JsonObject root, String key, int fallback,
                               java.util.function.IntUnaryOperator clamp) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        try {
            return clamp.applyAsInt(root.get(key).getAsInt());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        try {
            return root.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static float readUnitFloat(JsonObject root, String key, float fallback) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) return fallback;
        try {
            float v = root.get(key).getAsFloat();
            if (!Float.isFinite(v)) return fallback;
            return Math.max(0.0f, Math.min(1.0f, v));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static boolean isCustomTitlePanoramaEnabled() {
        return ensureLoaded().customTitlePanoramaEnabled();
    }

    public static boolean setCustomTitlePanoramaEnabled(boolean enabled) {
        return update(s -> s.withCustomTitlePanoramaEnabled(enabled));
    }

    public static boolean isKineticBootUiEnabled() {
        return ensureLoaded().kineticBootUiEnabled();
    }

    public static boolean setKineticBootUiEnabled(boolean enabled) {
        return update(s -> s.withKineticBootUiEnabled(enabled));
    }

    public static boolean isKineticUiSoundEnabled() {
        return ensureLoaded().kineticUiSoundEnabled();
    }

    public static boolean setKineticUiSoundEnabled(boolean enabled) {
        return update(s -> s.withKineticUiSoundEnabled(enabled));
    }

    public static float getKineticUiBgmVolume() {
        return ensureLoaded().kineticUiBgmVolume();
    }

    /** Updates in-memory BGM volume immediately during slider drag without spamming disk writes. */
    public static void previewKineticUiBgmVolume(float volume) {
        if (!Float.isFinite(volume)) return;
        float clamped = Math.max(0.0f, Math.min(1.0f, volume));
        synchronized (WRITE_LOCK) {
            Snapshot current = ensureLoaded();
            if (current.kineticUiBgmVolume() != clamped) {
                snapshot = current.withKineticUiBgmVolume(clamped);
            }
        }
    }

    public static boolean setKineticUiBgmVolume(float volume) {
        float clamped = Float.isFinite(volume) ? Math.max(0.0f, Math.min(1.0f, volume)) : DEFAULT_KINETIC_UI_BGM_VOLUME;
        return update(s -> s.withKineticUiBgmVolume(clamped));
    }

    public static boolean isKineticUiAutoEnter() {
        return ensureLoaded().kineticUiAutoEnter();
    }

    public static boolean setKineticUiAutoEnter(boolean enabled) {
        return update(s -> s.withKineticUiAutoEnter(enabled));
    }

    public static boolean isLightweightInventory() {
        return ensureLoaded().lightweightInventory();
    }

    /** Local only. */
    public static boolean setLightweightInventory(boolean enabled) {
        return update(s -> s.withLightweightInventory(enabled));
    }

    public static boolean isDecoratedShopEnabled() {
        return ensureLoaded().decoratedShopEnabled();
    }

    /** Shop appearance is independent of the stamp album and its performance mode. */
    public static boolean setDecoratedShopEnabled(boolean enabled) {
        return update(s -> s.withDecoratedShopEnabled(enabled));
    }

    public static int getRequestTimeoutMs() {
        return ensureLoaded().requestTimeoutMs();
    }

    /** 单次分片请求超时（毫秒），超时后按退避重试。 */
    public static boolean setRequestTimeoutMs(int millis) {
        int clamped = SceneClientPerformanceRules.clampRequestTimeoutMs(millis);
        return update(s -> s.withRequestTimeoutMs(clamped));
    }

    public static int getMaxChunkRetries() {
        return ensureLoaded().maxChunkRetries();
    }

    /** 同一分片的最大重试次数；0 表示不重试。 */
    public static boolean setMaxChunkRetries(int retries) {
        int clamped = SceneClientPerformanceRules.clampMaxChunkRetries(retries);
        return update(s -> s.withMaxChunkRetries(clamped));
    }

    /** 已完成资产（.hscene）的磁盘缓存配额上限（MiB）。 */
    public static int getDiskCacheQuotaMiB() {
        return ensureLoaded().diskCacheQuotaMiB();
    }

    public static boolean setDiskCacheQuotaMiB(int quotaMiB) {
        int clamped = SceneClientPerformanceRules.clampDiskCacheQuotaMiB(quotaMiB);
        return update(s -> s.withDiskCacheQuotaMiB(clamped));
    }

    /** 是否保留未完成的分片、断线重连后只补缺失部分。 */
    public static boolean isPartialResumeEnabled() {
        return ensureLoaded().partialResumeEnabled();
    }

    public static boolean setPartialResumeEnabled(boolean enabled) {
        return update(s -> s.withPartialResumeEnabled(enabled));
    }

    /** 未完成分片文件（.part）的总配额上限（MiB），与 .hscene 配额相互独立。 */
    public static int getPartialCacheQuotaMiB() {
        return ensureLoaded().partialCacheQuotaMiB();
    }

    public static boolean setPartialCacheQuotaMiB(int quotaMiB) {
        int clamped = SceneClientPerformanceRules.clampPartialCacheQuotaMiB(quotaMiB);
        return update(s -> s.withPartialCacheQuotaMiB(clamped));
    }

    /** 未完成分片的保留时长（小时），超期在下次启动时清理。 */
    public static int getPartialExpiryHours() {
        return ensureLoaded().partialExpiryHours();
    }

    public static boolean setPartialExpiryHours(int hours) {
        int clamped = SceneClientPerformanceRules.clampPartialExpiryHours(hours);
        return update(s -> s.withPartialExpiryHours(clamped));
    }

    /** 同时在途的分片请求数；1 等于此前的停等式传输。 */
    public static int getTransferWindowChunks() {
        return ensureLoaded().transferWindowChunks();
    }

    public static boolean setTransferWindowChunks(int chunks) {
        int clamped = SceneClientPerformanceRules.clampTransferWindowChunks(chunks);
        return update(s -> s.withTransferWindowChunks(clamped));
    }

    /** 每多少个 Section 才把网格分批；0 = 关闭分批（保持单份网格）。 */
    public static int getMeshBatchMinSections() {
        return ensureLoaded().meshBatchMinSections();
    }

    /** 每帧用于推进场景网格构建的时间预算（毫秒），所有背景共享。 */
    public static int getMeshBuildBudgetMsPerFrame() {
        return ensureLoaded().meshBuildBudgetMsPerFrame();
    }

    public static boolean setMeshBuildBudgetMsPerFrame(int millis) {
        int clamped = SceneClientPerformanceRules.clampMeshBuildBudgetMsPerFrame(millis);
        return update(s -> s.withMeshBuildBudgetMsPerFrame(clamped));
    }

    /** 已解码场景资产驻留内存的配额（MiB）。 */
    public static int getMemoryCacheQuotaMiB() {
        return ensureLoaded().memoryCacheQuotaMiB();
    }

    public static boolean setMemoryCacheQuotaMiB(int quotaMiB) {
        int clamped = SceneClientPerformanceRules.clampMemoryCacheQuotaMiB(quotaMiB);
        return update(s -> s.withMemoryCacheQuotaMiB(clamped));
    }

    /** 场景网格顶点缓冲合计的估算配额（MiB）。 */
    public static int getMeshCacheQuotaMiB() {
        return ensureLoaded().meshCacheQuotaMiB();
    }

    public static boolean setMeshCacheQuotaMiB(int quotaMiB) {
        int clamped = SceneClientPerformanceRules.clampMeshCacheQuotaMiB(quotaMiB);
        return update(s -> s.withMeshCacheQuotaMiB(clamped));
    }

    public static boolean isIdiotFaceOverlay() { return ensureLoaded().idiotFaceOverlay(); }
    public static boolean isIdiotLargeJackpot() { return ensureLoaded().idiotLargeJackpot(); }
    public static int getIdiotParticles() { return ensureLoaded().idiotParticles(); }

    public static boolean setIdiotFaceOverlay(boolean value) {
        return update(s -> s.withIdiotFaceOverlay(value));
    }

    public static boolean setIdiotLargeJackpot(boolean value) {
        return update(s -> s.withIdiotLargeJackpot(value));
    }

    public static boolean setIdiotParticles(int value) {
        int clamped = Math.clamp(value, 0, 2);
        return update(s -> s.withIdiotParticles(clamped));
    }

    public static String getReplayVideoResolution() {
        return ensureLoaded().replayVideoResolution();
    }

    public static boolean setReplayVideoResolution(String resolutionId) {
        String normalized = normalizeReplayResolution(resolutionId);
        return update(s -> s.withReplayVideoResolution(normalized));
    }

    public static boolean isReplayVideoCompress() {
        return ensureLoaded().replayVideoCompress();
    }

    public static boolean setReplayVideoCompress(boolean compress) {
        return update(s -> s.withReplayVideoCompress(compress));
    }

    /** Replay video MP4 quality preset id: {@code light}, {@code balanced} or {@code original}. */
    public static String getReplayVideoQuality() {
        return ensureLoaded().replayVideoQuality();
    }

    public static boolean setReplayVideoQuality(String qualityId) {
        String normalized = normalizeReplayQuality(qualityId);
        return update(s -> s.withReplayVideoQuality(normalized));
    }

    /** Replay video soundtrack level id: {@code off}, {@code low}, {@code medium} or {@code high}. */
    public static String getReplayVideoMusic() {
        return ensureLoaded().replayVideoMusic();
    }

    public static boolean setReplayVideoMusic(String levelId) {
        String normalized = ReplayMusicPlan.Level.fromId(levelId).id;
        return update(s -> s.withReplayVideoMusic(normalized));
    }

    /** Replay video packaging style id: {@code brass} (default) or {@code kinetic}. */
    public static String getReplayVideoStyle() {
        return ensureLoaded().replayVideoStyle();
    }

    public static boolean setReplayVideoStyle(String styleId) {
        String normalized = com.habitrain.core.scene.replay.ReplayExportContentOptions.VisualStyle.fromId(styleId).id;
        return update(s -> s.withReplayVideoStyle(normalized));
    }

    public static String getReplayVideoFfmpegPath() {
        return ensureLoaded().replayVideoFfmpegPath();
    }

    public static boolean setReplayVideoFfmpegPath(String path) {
        String normalized = path == null ? "" : path.trim();
        return update(s -> s.withReplayVideoFfmpegPath(normalized));
    }

    /** Client-local replay audio preferences (table + 3D history playback). */
    public static ReplayAudioSettings getReplayAudioSettings() {
        ReplayAudioSettings audio = ensureLoaded().replayAudio();
        return audio == null ? ReplayAudioSettings.DEFAULT : audio;
    }

    public static float getEffectiveReplayVoiceVolume() {
        return getReplayAudioSettings().effectiveVoiceScale();
    }

    public static float getEffectiveReplaySfxVolume() {
        return getReplayAudioSettings().effectiveSfxScale();
    }

    /** Updates in-memory replay audio immediately during slider drag without spamming disk writes. */
    public static void previewReplayAudioSettings(ReplayAudioSettings settings) {
        ReplayAudioSettings safe = settings == null ? ReplayAudioSettings.DEFAULT : settings;
        synchronized (WRITE_LOCK) {
            Snapshot current = ensureLoaded();
            if (!safe.equals(current.replayAudio())) {
                snapshot = current.withReplayAudio(safe);
                replayAudioPreviewPending = true;
            }
        }
    }

    public static boolean setReplayAudioSettings(ReplayAudioSettings settings) {
        ReplayAudioSettings safe = settings == null ? ReplayAudioSettings.DEFAULT : settings;
        synchronized (WRITE_LOCK) {
            Snapshot current = ensureLoaded();
            boolean changed = !safe.equals(current.replayAudio());
            if (!changed && !replayAudioPreviewPending) {
                return true;
            }
            if (changed) {
                snapshot = current.withReplayAudio(safe);
            }
            replayAudioPreviewPending = false;
            dirty = true;
            scheduleSaveLocked();
            return true;
        }
    }

    public static ReplayAudioSettings toggleReplayAudioMute() {
        synchronized (WRITE_LOCK) {
            Snapshot current = ensureLoaded();
            ReplayAudioSettings cur = current.replayAudio() == null ? ReplayAudioSettings.DEFAULT : current.replayAudio();
            ReplayAudioSettings next = cur.toggledMute();
            snapshot = current.withReplayAudio(next);
            replayAudioPreviewPending = false;
            dirty = true;
            scheduleSaveLocked();
            return next;
        }
    }

    public static boolean resetReplayAudioSettings() {
        return setReplayAudioSettings(ReplayAudioSettings.DEFAULT);
    }
}
