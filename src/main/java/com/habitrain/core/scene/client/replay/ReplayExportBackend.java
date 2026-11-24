package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.replay.ReplayExportContentOptions;

enum ReplayExportBackend {
    NATIVE, SIMPLE;

    static boolean supportsNative(ReplayExportContentOptions options) {
        return options.mode() == ReplayExportContentOptions.ExportMode.PERSONAL
                && options.personalView() == ReplayExportContentOptions.PersonalView.TRUE_POV;
    }

    static ReplayExportBackend select(ReplayExportContentOptions options, boolean preferSimple) {
        return !preferSimple && supportsNative(options) ? NATIVE : SIMPLE;
    }
}
