package com.habitrain.core.game.sre.role.drone;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DroneRoundLedgerTest {
    @Test
    void operatorStartsRoundWithoutDroneByDefaultAndPreservesPurchasedOrDestroyedStateWithinRound() {
        DroneRoundLedger<String, String> ledger = new DroneRoundLedger<>();
        UUID operator = UUID.randomUUID();

        // Match start: no drone frame by default
        var initial = ledger.frameFor("train", operator);
        assertFalse(initial.hasDroneFrame);
        assertFalse(initial.everHadDrone);
        assertEquals(0, initial.stowedBattery);
        assertTrue(DroneRules.canBuyReplacementDrone(initial.hasDroneFrame, false));
        assertFalse(DroneRules.canMountPayload(DronePayloadCategory.LETHAL_GUN,
                initial.hasDroneFrame, false));

        // Simulate buying a 150-coin drone resupply -> stowed full battery
        initial.hasDroneFrame = true;
        initial.everHadDrone = true;
        initial.stowedBattery = DroneRules.MAX_BATTERY;

        var reconnectedAfterBuy = ledger.frameFor("train", operator);
        assertTrue(reconnectedAfterBuy.hasDroneFrame);
        assertTrue(reconnectedAfterBuy.everHadDrone);
        assertEquals(DroneRules.MAX_BATTERY, reconnectedAfterBuy.stowedBattery);
        // Can still buy a new drone even when holding one
        assertTrue(DroneRules.canBuyReplacementDrone(reconnectedAfterBuy.hasDroneFrame, true));

        // Simulate drone destroyed -> reconnect still remembers everHadDrone = true and hasDroneFrame = false
        reconnectedAfterBuy.hasDroneFrame = false;
        reconnectedAfterBuy.stowedBattery = 0;
        var reconnectedAfterDestroy = ledger.frameFor("train", operator);
        assertFalse(reconnectedAfterDestroy.hasDroneFrame);
        assertTrue(reconnectedAfterDestroy.everHadDrone);
        assertEquals(0, reconnectedAfterDestroy.stowedBattery);

        // Separate level / next round starts cleanly with no drone again
        assertFalse(ledger.frameFor("second-train", operator).hasDroneFrame);
        ledger.endRound("train");
        var nextRound = ledger.frameFor("train", operator);
        assertFalse(nextRound.hasDroneFrame);
        assertFalse(nextRound.everHadDrone);
        assertEquals(0, nextRound.stowedBattery);
    }

    @Test
    void poisonRequiresAStationaryApproachFromTheTargetsRear() {
        assertTrue(DroneRules.isBehind(0, 0, 0.4, -1, 0, 1));
        assertFalse(DroneRules.isBehind(0, 0, 0, 1, 0, 1));
        assertFalse(DroneRules.isBehind(0, 0, 1, 0, 0, 1));
        assertFalse(DroneRules.isBehind(0, 0, 0, 0, 0, 1));
        assertTrue(DroneRules.isHoveringForPoison(0.04 * 0.04));
        assertFalse(DroneRules.isHoveringForPoison(0.2 * 0.2));
    }

    @Test
    void stowedPayloadPersistsAcrossFoldRecallOrReconnectAndClearsWhenFrameIsDestroyed() {
        DroneRoundLedger<String, String> ledger = new DroneRoundLedger<>();
        UUID operator = UUID.randomUUID();

        UUID mounter = UUID.randomUUID();
        var frame = ledger.frameFor("train", operator);
        frame.hasDroneFrame = true;
        frame.everHadDrone = true;
        frame.stowedBattery = 1234;
        frame.setStowedPayload("knife", DronePayloadCategory.LETHAL_BLADE, mounter);

        var reconnected = ledger.frameFor("train", operator);
        assertTrue(reconnected.hasStowedPayload());
        assertEquals("knife", reconnected.stowedPayload);
        assertEquals(DronePayloadCategory.LETHAL_BLADE, reconnected.stowedPayloadCategory);
        assertEquals(mounter, reconnected.stowedPayloadMounter);

        // Redeploying transfers the payload back to the entity and clears the stowed slot
        reconnected.clearStowedPayload();
        assertFalse(reconnected.hasStowedPayload());
        assertNull(reconnected.stowedPayload);
        assertEquals(DronePayloadCategory.NONE, reconnected.stowedPayloadCategory);
        assertNull(reconnected.stowedPayloadMounter);

        // Self-destruction from unloading an item resets frame, battery, and stowed payload
        reconnected.setStowedPayload("lockpick", DronePayloadCategory.INFILTRATION_LOGISTICS, operator);
        reconnected.destroyFrame();
        assertFalse(reconnected.hasDroneFrame);
        assertTrue(reconnected.everHadDrone);
        assertEquals(0, reconnected.stowedBattery);
        assertFalse(reconnected.hasStowedPayload());
        assertNull(reconnected.stowedPayload);
        assertEquals(DronePayloadCategory.NONE, reconnected.stowedPayloadCategory);
        assertNull(reconnected.stowedPayloadMounter);
    }
}
