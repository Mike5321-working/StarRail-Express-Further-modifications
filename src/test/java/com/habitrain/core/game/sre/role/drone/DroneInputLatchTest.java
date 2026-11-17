package com.habitrain.core.game.sre.role.drone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DroneInputLatchTest {

    @Test
    void holdingRightClickToDeployTerminalDoesNotAutoExitFpvUntilReleasedAndPressedAgain() {
        DroneInputLatch latch = new DroneInputLatch();

        // Tick 0: On foot, player presses Right-Click on terminal -> consumed
        latch.notifyOnFootUseConsumed();
        assertTrue(latch.shouldSuppressOnFootUse(true), "Holding RMB on foot after terminal use must stay suppressed");

        // Tick 1..5: Server enters FPV while player is STILL holding Right-Click (e.g. >0.2s hold)
        for (int i = 0; i < 6; i++) {
            DroneInputLatch.TickActions actions = latch.stepTick(true, false, false, false, false, false, true);
            assertFalse(actions.exitFpv(), "Holding RMB across FPV entry must NEVER auto-exit FPV");
        }

        // Tick 6: Player releases Right-Click in FPV
        DroneInputLatch.TickActions releaseTick = latch.stepTick(true, false, false, false, false, false, false);
        assertFalse(releaseTick.exitFpv());

        // Tick 7: Player presses Right-Click in FPV -> now exits FPV cleanly once!
        DroneInputLatch.TickActions exitTick = latch.stepTick(true, false, false, false, false, false, true);
        assertTrue(exitTick.exitFpv(), "Fresh RMB press in FPV must trigger EXIT_FPV");

        // Tick 8..10: Back on foot while player's finger is STILL holding Right-Click -> must NOT re-enter FPV!
        DroneInputLatch.TickActions backOnFoot = latch.stepTick(false, false, false, false, false, false, true);
        assertFalse(backOnFoot.exitFpv());
        assertTrue(latch.shouldSuppressOnFootUse(true), "Post-FPV RMB hold must stay suppressed to prevent ping-pong");

        // Tick 11: Player releases Right-Click on foot -> suppression clears
        assertFalse(latch.shouldSuppressOnFootUse(false));
    }

    @Test
    void holdingGSkillKeyToEnterFpvDoesNotFirePulseScanUntilReleased() {
        DroneInputLatch latch = new DroneInputLatch();

        // Player pressed [G] (role skill) on foot to deploy/link -> enters FPV while [G] is still held
        for (int tick = 0; tick < 5; tick++) {
            // Even if GLFW fires raw press/repeat while held across boundary, scan must stay latched
            latch.onRawPressInFpv(DroneInputLatch.Channel.SCAN);
            DroneInputLatch.TickActions actions = latch.stepTick(true, false, true, false, false, false, false);
            assertFalse(actions.pulseScan(), "Holding [G] from role skill entry must not waste 5% battery on scan");
        }

        // Release [G]
        assertFalse(latch.stepTick(true, false, false, false, false, false, false).pulseScan());

        // Press [G] again inside FPV -> fires Pulse Scan exactly once (holding further does not repeat)
        assertTrue(latch.stepTick(true, false, true, false, false, false, false).pulseScan());
        assertFalse(latch.stepTick(true, false, true, false, false, false, false).pulseScan());
    }

    @Test
    void clickingBackToGameInPauseScreenDoesNotMisfireMountedLethalPayload() {
        DroneInputLatch latch = new DroneInputLatch();

        // Enter FPV cleanly with no buttons held
        latch.stepTick(true, false, false, false, false, false, false);

        // Open PauseScreen (ESC) and hold Left-Click on "Back to Game" button
        DroneInputLatch.TickActions inMenuClick = latch.stepTick(true, true, false, false, false, true, false);
        assertEquals(DroneInputLatch.TickActions.NONE, inMenuClick);

        // Next tick: PauseScreen closed (screenOpen = false), Left-Click still held from clicking button
        DroneInputLatch.TickActions afterCloseHeld = latch.stepTick(true, false, false, false, false, true, false);
        assertFalse(afterCloseHeld.triggerPayload(), "Closing PauseScreen with LMB held must never misfire payload");

        // Release Left-Click, then click intentionally -> fires payload
        assertFalse(latch.stepTick(true, false, false, false, false, false, false).triggerPayload());
        assertTrue(latch.stepTick(true, false, false, false, false, true, false).triggerPayload());
    }

    @Test
    void subTickQuickTapBetweenClientTicksStillFiresDiscreteCommandOnce() {
        DroneInputLatch latch = new DroneInputLatch();
        latch.stepTick(true, false, false, false, false, false, false);

        // Sub-tick <50ms press + release before next client tick: physical state is back to false at tick boundary
        latch.onRawPressInFpv(DroneInputLatch.Channel.SCAN);
        latch.onRawPressInFpv(DroneInputLatch.Channel.ATTACK);

        DroneInputLatch.TickActions actions = latch.stepTick(true, false, false, false, false, false, false);
        assertTrue(actions.pulseScan());
        assertTrue(actions.triggerPayload());

        // Subsequent idle tick does not repeat
        DroneInputLatch.TickActions next = latch.stepTick(true, false, false, false, false, false, false);
        assertEquals(DroneInputLatch.TickActions.NONE, next);
    }

    @Test
    void onFootExposesOnlySwapBatteryAndRecallFoldEdgesWhileBlockingScanAndAttack() {
        DroneInputLatch latch = new DroneInputLatch();

        DroneInputLatch.TickActions pressAllOnFoot = latch.stepTick(false, false, true, true, true, true, false);
        assertFalse(pressAllOnFoot.pulseScan());
        assertFalse(pressAllOnFoot.triggerPayload());
        assertFalse(pressAllOnFoot.exitFpv());
        assertTrue(pressAllOnFoot.swapBattery());
        assertTrue(pressAllOnFoot.recallFold());

        // Holding [R]/[Z] on foot does not spam every tick
        DroneInputLatch.TickActions heldOnFoot = latch.stepTick(false, false, true, true, true, true, false);
        assertFalse(heldOnFoot.swapBattery());
        assertFalse(heldOnFoot.recallFold());
    }
}
