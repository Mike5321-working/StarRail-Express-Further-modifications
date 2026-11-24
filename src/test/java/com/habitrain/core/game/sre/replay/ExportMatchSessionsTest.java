package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.MatchPhase;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExportMatchSessionsTest {
    @Test void notifiesOnCountdownAndResetsForTheNextRound() {
        var sessions = new ExportMatchSessions();
        var owner = UUID.randomUUID();
        var token = UUID.randomUUID();
        sessions.presence(owner, token, true);
        assertNull(sessions.transition(owner, MatchPhase.INACTIVE));
        assertTrue(sessions.transition(owner, MatchPhase.STARTING).active());
        assertNull(sessions.transition(owner, MatchPhase.UNKNOWN));
        assertNull(sessions.transition(owner, MatchPhase.ACTIVE));
        assertFalse(sessions.transition(owner, MatchPhase.STOPPING).active());
        assertEquals(token, sessions.transition(owner, MatchPhase.ACTIVE).session());
    }

    @Test void staleCancellationAndOtherPlayersCannotCloseANewerSession() {
        var sessions = new ExportMatchSessions();
        var owner = UUID.randomUUID();
        var old = UUID.randomUUID();
        var current = UUID.randomUUID();
        sessions.presence(owner, old, true);
        sessions.presence(owner, current, true);
        sessions.presence(owner, old, false);
        sessions.presence(UUID.randomUUID(), current, false);
        assertEquals(current, sessions.transition(owner, MatchPhase.ACTIVE).session());
        sessions.disconnect(owner);
        assertNull(sessions.transition(owner, MatchPhase.ACTIVE));
    }
}
