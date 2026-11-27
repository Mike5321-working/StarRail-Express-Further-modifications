package com.habitrain.core.client.config;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Client-local replay playback audio settings (master volume, player voice volume,
 * game sound volume, and one-click mute). Never synchronized with the server.
 */
public record ReplayAudioSettings(
        boolean muted,
        float masterVolume,
        float voiceVolume,
        float sfxVolume
) {
    public static final float DEFAULT_VOLUME = 1.0f;
    public static final float SILENT_EPSILON = 0.001f;
    public static final ReplayAudioSettings DEFAULT = new ReplayAudioSettings(
            false, DEFAULT_VOLUME, DEFAULT_VOLUME, DEFAULT_VOLUME);

    public ReplayAudioSettings {
        masterVolume = clampUnit(masterVolume, DEFAULT_VOLUME);
        voiceVolume = clampUnit(voiceVolume, DEFAULT_VOLUME);
        sfxVolume = clampUnit(sfxVolume, DEFAULT_VOLUME);
    }

    public static float clampUnit(float value, float fallback) {
        if (!Float.isFinite(value)) return fallback;
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public static int toPercent(float volume) {
        return Math.round(clampUnit(volume, DEFAULT_VOLUME) * 100.0f);
    }

    public float effectiveVoiceScale() {
        return muted ? 0.0f : masterVolume * voiceVolume;
    }

    public float effectiveSfxScale() {
        return muted ? 0.0f : masterVolume * sfxVolume;
    }

    public boolean isEffectivelyMuted() {
        return muted
                || masterVolume <= SILENT_EPSILON
                || (voiceVolume <= SILENT_EPSILON && sfxVolume <= SILENT_EPSILON);
    }

    public boolean isDefault() {
        return !muted
                && Math.abs(masterVolume - DEFAULT_VOLUME) <= SILENT_EPSILON
                && Math.abs(voiceVolume - DEFAULT_VOLUME) <= SILENT_EPSILON
                && Math.abs(sfxVolume - DEFAULT_VOLUME) <= SILENT_EPSILON;
    }

    public ReplayAudioSettings withMuted(boolean nextMuted) {
        return new ReplayAudioSettings(nextMuted, masterVolume, voiceVolume, sfxVolume);
    }

    /**
     * Adjusts master volume; dragging above zero automatically clears an active mute state.
     */
    public ReplayAudioSettings withMasterVolume(float nextMaster) {
        float clamped = clampUnit(nextMaster, DEFAULT_VOLUME);
        boolean nextMuted = muted && clamped <= SILENT_EPSILON;
        return new ReplayAudioSettings(nextMuted, clamped, voiceVolume, sfxVolume);
    }

    public ReplayAudioSettings withVoiceVolume(float nextVoice) {
        return new ReplayAudioSettings(muted, masterVolume, clampUnit(nextVoice, DEFAULT_VOLUME), sfxVolume);
    }

    public ReplayAudioSettings withSfxVolume(float nextSfx) {
        return new ReplayAudioSettings(muted, masterVolume, voiceVolume, clampUnit(nextSfx, DEFAULT_VOLUME));
    }

    /**
     * Toggles mute while preserving individual slider levels; unmuting when effective volume
     * would still be silent restores the zeroed channel(s) to 100% so sound reliably resumes.
     */
    public ReplayAudioSettings toggledMute() {
        if (muted || masterVolume <= SILENT_EPSILON || (voiceVolume <= SILENT_EPSILON && sfxVolume <= SILENT_EPSILON)) {
            float restoredMaster = masterVolume <= SILENT_EPSILON ? DEFAULT_VOLUME : masterVolume;
            if (voiceVolume <= SILENT_EPSILON && sfxVolume <= SILENT_EPSILON) {
                return new ReplayAudioSettings(false, restoredMaster, DEFAULT_VOLUME, DEFAULT_VOLUME);
            }
            return new ReplayAudioSettings(false, restoredMaster, voiceVolume, sfxVolume);
        }
        return new ReplayAudioSettings(true, masterVolume, voiceVolume, sfxVolume);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("muted", muted);
        obj.addProperty("masterVolume", masterVolume);
        obj.addProperty("voiceVolume", voiceVolume);
        obj.addProperty("sfxVolume", sfxVolume);
        return obj;
    }

    public static ReplayAudioSettings fromJson(@Nullable JsonObject obj) {
        if (obj == null) return DEFAULT;
        boolean muted = readBoolean(obj, "muted", false);
        float master = readUnitFloat(obj, "masterVolume", DEFAULT_VOLUME);
        float voice = readUnitFloat(obj, "voiceVolume", DEFAULT_VOLUME);
        float sfx = readUnitFloat(obj, "sfxVolume", DEFAULT_VOLUME);
        return new ReplayAudioSettings(muted, master, voice, sfx);
    }

    private static boolean readBoolean(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return fallback;
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static float readUnitFloat(JsonObject obj, String key, float fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return fallback;
        try {
            return clampUnit(obj.get(key).getAsFloat(), fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
