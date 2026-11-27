package com.habitrain.core.client.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayAudioSettingsTest {

    @Test
    void defaultsAreFullVolumeAndUnmuted() {
        ReplayAudioSettings s = ReplayAudioSettings.DEFAULT;
        assertFalse(s.muted());
        assertEquals(1.0f, s.masterVolume(), 1e-6f);
        assertEquals(1.0f, s.voiceVolume(), 1e-6f);
        assertEquals(1.0f, s.sfxVolume(), 1e-6f);
        assertEquals(1.0f, s.effectiveVoiceScale(), 1e-6f);
        assertEquals(1.0f, s.effectiveSfxScale(), 1e-6f);
        assertTrue(s.isDefault());
        assertFalse(s.isEffectivelyMuted());
    }

    @Test
    void clampsInvalidValuesIntoUnitRange() {
        ReplayAudioSettings s = new ReplayAudioSettings(false, -0.4f, 2.5f, Float.NaN);
        assertEquals(0.0f, s.masterVolume(), 1e-6f);
        assertEquals(1.0f, s.voiceVolume(), 1e-6f);
        assertEquals(1.0f, s.sfxVolume(), 1e-6f);
        assertEquals(0.0f, s.effectiveVoiceScale(), 1e-6f);
        assertEquals(0.0f, s.effectiveSfxScale(), 1e-6f);
        assertTrue(s.isEffectivelyMuted());
    }

    @Test
    void effectiveScalesMultiplyMasterAndChannelAndRespectMute() {
        ReplayAudioSettings s = new ReplayAudioSettings(false, 0.5f, 0.8f, 0.4f);
        assertEquals(0.4f, s.effectiveVoiceScale(), 1e-6f);
        assertEquals(0.2f, s.effectiveSfxScale(), 1e-6f);
        assertFalse(s.isEffectivelyMuted());
        assertFalse(s.isDefault());

        ReplayAudioSettings muted = s.withMuted(true);
        assertEquals(0.0f, muted.effectiveVoiceScale(), 1e-6f);
        assertEquals(0.0f, muted.effectiveSfxScale(), 1e-6f);
        assertTrue(muted.isEffectivelyMuted());
        assertEquals(0.5f, muted.masterVolume(), 1e-6f);
        assertEquals(0.8f, muted.voiceVolume(), 1e-6f);
        assertEquals(0.4f, muted.sfxVolume(), 1e-6f);
    }

    @Test
    void raisingMasterVolumeClearsActiveMuteAndToggleMuteRestoresAudibleLevels() {
        ReplayAudioSettings muted = new ReplayAudioSettings(true, 0.6f, 0.7f, 0.5f);
        ReplayAudioSettings unmutedByDrag = muted.withMasterVolume(0.75f);
        assertFalse(unmutedByDrag.muted());
        assertEquals(0.75f, unmutedByDrag.masterVolume(), 1e-6f);

        // Toggle mute preserves custom levels and restores them on unmute
        ReplayAudioSettings toggledOff = unmutedByDrag.toggledMute();
        assertTrue(toggledOff.muted());
        assertEquals(0.75f, toggledOff.masterVolume(), 1e-6f);
        ReplayAudioSettings toggledOn = toggledOff.toggledMute();
        assertFalse(toggledOn.muted());
        assertEquals(0.75f, toggledOn.masterVolume(), 1e-6f);
        assertEquals(0.7f, toggledOn.voiceVolume(), 1e-6f);
        assertEquals(0.5f, toggledOn.sfxVolume(), 1e-6f);

        // Unmuting when master was dragged to 0 restores master to 100%
        ReplayAudioSettings zeroMaster = new ReplayAudioSettings(false, 0.0f, 0.7f, 0.5f);
        ReplayAudioSettings restored = zeroMaster.toggledMute();
        assertFalse(restored.muted());
        assertEquals(1.0f, restored.masterVolume(), 1e-6f);
        assertEquals(0.7f, restored.voiceVolume(), 1e-6f);

        // Unmuting when both voice and sfx were dragged to 0 restores both channels
        ReplayAudioSettings zeroChannels = new ReplayAudioSettings(false, 0.8f, 0.0f, 0.0f);
        ReplayAudioSettings restoredChannels = zeroChannels.toggledMute();
        assertFalse(restoredChannels.isEffectivelyMuted());
        assertEquals(0.8f, restoredChannels.masterVolume(), 1e-6f);
        assertEquals(1.0f, restoredChannels.voiceVolume(), 1e-6f);
        assertEquals(1.0f, restoredChannels.sfxVolume(), 1e-6f);
    }

    @Test
    void jsonRoundTripAndCorruptFallback() {
        ReplayAudioSettings original = new ReplayAudioSettings(true, 0.65f, 0.85f, 0.30f);
        JsonObject json = original.toJson();
        ReplayAudioSettings restored = ReplayAudioSettings.fromJson(json);
        assertEquals(original, restored);

        assertEquals(ReplayAudioSettings.DEFAULT, ReplayAudioSettings.fromJson(null));
        assertEquals(ReplayAudioSettings.DEFAULT, ReplayAudioSettings.fromJson(new JsonObject()));

        JsonObject corrupt = new JsonObject();
        corrupt.addProperty("muted", "not-a-bool");
        corrupt.addProperty("masterVolume", "bad-float");
        corrupt.addProperty("voiceVolume", 5.0f);
        corrupt.addProperty("sfxVolume", -2.0f);
        ReplayAudioSettings sanitized = ReplayAudioSettings.fromJson(corrupt);
        assertFalse(sanitized.muted());
        assertEquals(1.0f, sanitized.masterVolume(), 1e-6f);
        assertEquals(1.0f, sanitized.voiceVolume(), 1e-6f);
        assertEquals(0.0f, sanitized.sfxVolume(), 1e-6f);
    }
}
