package com.habitrain.core.scene.client.replay;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** 16:9 export resolution presets for directed match replay videos. */
enum ReplayExportResolution {
    P480("480p", 854, 480),
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080),
    P1440("1440p", 2560, 1440),
    P2160("2160p", 3840, 2160);

    private static final ReplayExportResolution[] VALUES = values();

    final String id;
    final int width;
    final int height;

    ReplayExportResolution(String id, int width, int height) {
        this.id = id;
        this.width = width;
        this.height = height;
    }

    int frameBytes() {
        return Math.multiplyExact(Math.multiplyExact(width, height), 3);
    }

    boolean highLoad() {
        return width >= 2560;
    }

    /** AVI 1.0 has no large-file index support in the bundled writer. */
    boolean aviSupported() {
        return this != P2160;
    }

    String dimensions() {
        return width + " × " + height;
    }

    Component label() {
        return ReplayBoardTexts.tr("controls.res." + id);
    }

    ReplayExportResolution next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    ReplayExportResolution previous() {
        return VALUES[(ordinal() + VALUES.length - 1) % VALUES.length];
    }

    static ReplayExportResolution fromId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return P720;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ReplayExportResolution candidate : VALUES) {
            if (candidate.id.equals(normalized)) return candidate;
        }
        return P720;
    }
}
