package com.habitrain.core.client.gui.menu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapVoteProfileScreenTest {

    @Test
    void uploadButtonIsActiveWheneverEditableConnectedAndIdle() {
        assertTrue(MapVoteProfileScreen.isUploadButtonActive(true, false, false, true));
        assertFalse(MapVoteProfileScreen.isUploadButtonActive(false, false, false, true));
        assertFalse(MapVoteProfileScreen.isUploadButtonActive(true, true, false, true));
        assertFalse(MapVoteProfileScreen.isUploadButtonActive(true, false, true, true));
        assertFalse(MapVoteProfileScreen.isUploadButtonActive(true, false, false, false));
    }

    @Test
    void uploadHintMatchesDisabledReason() {
        assertEquals("config.habitrain_core.map_profile.read_only",
                MapVoteProfileScreen.resolveUploadHintKey(false, true));
        assertEquals("config.habitrain_core.map_profile.preview_offline",
                MapVoteProfileScreen.resolveUploadHintKey(true, false));
        assertEquals("config.habitrain_core.map_profile.preview_hint",
                MapVoteProfileScreen.resolveUploadHintKey(true, true));
    }
}
