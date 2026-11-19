package com.habitrain.core.api.match.replay;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public final class ReplayKillSounds {
    public static final int BEFORE_MS = 2000;
    public static final int AFTER_MS = 1000;
    public static final int MAX_FILE_LENGTH = 128;
    public static final int MAX_PER_KILL = 48;
    public static final int MAX_WINDOWS = 96;
    public static final int MAX_PER_ROUND = 1024;

    private ReplayKillSounds() {}

    public record Cue(int offsetMs, ResourceLocation file, float volume, float pitch) {
        public Cue {
            if (offsetMs < -BEFORE_MS || offsetMs > AFTER_MS || !validFile(file)
                    || !Float.isFinite(volume) || volume <= 0 || volume > 1
                    || !Float.isFinite(pitch) || pitch < 0.5f || pitch > 2f) {
                throw new IllegalArgumentException("Invalid recorded replay sound");
            }
        }
    }

    public record Window(int eventIndex, List<Cue> cues) {
        public Window {
            cues = List.copyOf(cues);
            if (eventIndex < 0 || eventIndex >= LastMatchReplay.MAX_EVENTS || cues.size() > MAX_PER_KILL) {
                throw new IllegalArgumentException("Invalid kill sound window");
            }
        }
    }

    public static boolean validFile(ResourceLocation file) {
        if (file == null || file.toString().length() > MAX_FILE_LENGTH) return false;
        String path = file.getPath();
        return path.startsWith("sounds/") && path.endsWith(".ogg")
                && !path.contains("..") && !path.contains("//");
    }
}
