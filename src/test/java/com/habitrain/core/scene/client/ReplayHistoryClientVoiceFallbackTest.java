package com.habitrain.core.scene.client;

import com.habitrain.core.network.replay.ReplayArchivePackets;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayHistoryClientVoiceFallbackTest {

    @Test
    void missingOrFailedArchiveReplyAlsoMarksVoiceUnavailable() {
        var missingRound = new ReplayHistoryClient.Round(UUID.randomUUID());
        assertEquals(ReplayHistoryClient.Phase.LOADING, missingRound.phase);
        assertFalse(missingRound.voiceUnavailable);

        ReplayHistoryClient.applyReply(missingRound, ReplayArchivePackets.MISSING);
        assertEquals(ReplayHistoryClient.Phase.MISSING, missingRound.phase);
        assertTrue(missingRound.voiceUnavailable, "MISSING archive round must mark voiceUnavailable so export does not hang");

        var errorRound = new ReplayHistoryClient.Round(UUID.randomUUID());
        ReplayHistoryClient.applyReply(errorRound, ReplayArchivePackets.ERROR);
        assertEquals(ReplayHistoryClient.Phase.FAILED, errorRound.phase);
        assertTrue(errorRound.voiceUnavailable, "ERROR archive round must mark voiceUnavailable so export does not hang");
    }
}
