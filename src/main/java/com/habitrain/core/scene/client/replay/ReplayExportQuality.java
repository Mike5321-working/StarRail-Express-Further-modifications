package com.habitrain.core.scene.client.replay;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * MP4 (H.264 + AAC) quality and file-size presets for directed match replay videos.
 *
 * <ul>
 *     <li>{@link #LIGHT}: compact sharing tier (~15–20 MB/min at 1080p30) with CRF 25, dark-scene AQ,
 *     tight VBV ceiling, clean grain-free frames and 128 kbps AAC audio.</li>
 *     <li>{@link #BALANCED} (default): high-definition balanced tier (~22–30 MB/min at 1080p30) with
 *     CRF 23, dark-scene AQ, VBV spike control, clean grain-free frames and 160 kbps AAC audio.</li>
 *     <li>{@link #ORIGINAL}: archival high-bitrate tier (~80 MB/min at 1080p30) preserving the original
 *     CRF 18 uncapped encode, film grain overlay and 192 kbps AAC audio.</li>
 * </ul>
 */
enum ReplayExportQuality {
    LIGHT("light", 25, 3_000, 128, 0.0, true),
    BALANCED("balanced", 23, 4_500, 160, 0.0, true),
    ORIGINAL("original", 18, 0, 192, 0.028, false);

    static final ReplayExportQuality DEFAULT = BALANCED;
    private static final ReplayExportQuality[] VALUES = values();
    private static final double BASE_1080P_PIXELS = 1920.0 * 1080.0;

    final String id;
    final int crf;
    final int base1080pMaxrateKbps;
    final int audioBitrateKbps;
    final double mp4GrainAlpha;
    final boolean darkSceneAq;

    ReplayExportQuality(String id, int crf, int base1080pMaxrateKbps, int audioBitrateKbps,
                        double mp4GrainAlpha, boolean darkSceneAq) {
        this.id = id;
        this.crf = crf;
        this.base1080pMaxrateKbps = base1080pMaxrateKbps;
        this.audioBitrateKbps = audioBitrateKbps;
        this.mp4GrainAlpha = mp4GrainAlpha;
        this.darkSceneAq = darkSceneAq;
    }

    /**
     * Resolution-scaled VBV maxrate in kbps, or {@code 0} when uncapped ({@link #ORIGINAL}).
     * Uses sublinear pixel-area scaling ({@code ratio^0.85}) so lower resolutions retain crisp HUD
     * edges while 2K/4K exports stay bounded during rapid camera whips and particle bursts.
     */
    int maxrateKbps(int width, int height) {
        if (base1080pMaxrateKbps <= 0 || width <= 0 || height <= 0) return 0;
        double pixelRatio = ((double) width * (double) height) / BASE_1080P_PIXELS;
        double scaled = base1080pMaxrateKbps * Math.pow(pixelRatio, 0.85);
        int rounded = (int) (Math.round(scaled / 50.0) * 50L);
        return Math.max(400, rounded);
    }

    int bufsizeKbps(int width, int height) {
        return maxrateKbps(width, height) * 2;
    }

    Component label() {
        return ReplayBoardTexts.tr("export.quality_" + id);
    }

    Component tooltip() {
        return ReplayBoardTexts.tr("export.quality_tip_" + id);
    }

    ReplayExportQuality next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    ReplayExportQuality previous() {
        return VALUES[(ordinal() + VALUES.length - 1) % VALUES.length];
    }

    static ReplayExportQuality fromId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ReplayExportQuality candidate : VALUES) {
            if (candidate.id.equals(normalized)) return candidate;
        }
        return DEFAULT;
    }
}
