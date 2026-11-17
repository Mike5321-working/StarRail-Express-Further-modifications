package com.habitrain.core.game.sre.role.drone;

import java.util.Arrays;

/**
 * Pure, deterministic edge-latching state machine for drone FPV and on-foot inputs.
 *
 * <p>Prevents input bleed across perspective/GUI transitions:
 * <ul>
 *   <li>Holding {@code Right-Click} or {@code G} to deploy/enter FPV will not trigger
 *       {@code EXIT_FPV} or {@code PULSE_SCAN} until physically released first.</li>
 *   <li>Pressing {@code Right-Click} to exit FPV (or being knocked out of FPV while holding
 *       {@code Right-Click}) suppresses on-foot {@code keyUse} until physically released,
 *       eliminating enter/exit ping-pong loops.</li>
 *   <li>Clicking GUI buttons (such as "Back to Game" in {@code PauseScreen}) with
 *       {@code Left-Click} will not misfire mounted payloads on the tick the screen closes.</li>
 *   <li>Sub-tick taps recorded from raw GLFW callbacks are preserved without repeating on hold.</li>
 * </ul>
 */
public final class DroneInputLatch {

    public enum Channel {
        SCAN,
        SWAP_BATTERY,
        RECALL_FOLD,
        ATTACK,
        USE
    }

    public record TickActions(
            boolean pulseScan,
            boolean swapBattery,
            boolean recallFold,
            boolean triggerPayload,
            boolean exitFpv
    ) {
        public static final TickActions NONE = new TickActions(false, false, false, false, false);
    }

    private static final int CHANNEL_COUNT = Channel.values().length;

    private final boolean[] prevDown = new boolean[CHANNEL_COUNT];
    private final boolean[] heldSinceBoundary = new boolean[CHANNEL_COUNT];
    private final boolean[] queuedRawPress = new boolean[CHANNEL_COUNT];

    private boolean inFpv;
    private boolean suppressOnFootUseUntilRelease;

    public void reset() {
        Arrays.fill(prevDown, false);
        Arrays.fill(heldSinceBoundary, false);
        Arrays.fill(queuedRawPress, false);
        inFpv = false;
        suppressOnFootUseUntilRelease = false;
    }

    /**
     * Marks on-foot {@code Right-Click} as consumed (e.g. using terminal or spare battery)
     * so holding the mouse button neither re-triggers on-foot use nor immediately exits FPV.
     */
    public void notifyOnFootUseConsumed() {
        int useIdx = Channel.USE.ordinal();
        prevDown[useIdx] = true;
        heldSinceBoundary[useIdx] = true;
        suppressOnFootUseUntilRelease = true;
    }

    /**
     * Marks any currently pressed channels as held across a window-focus boundary so clicking back
     * into an unfocused window grabs the mouse without firing {@code ATTACK} or {@code EXIT_FPV}.
     */
    public void latchFocusBoundary(
            boolean scanDown,
            boolean swapDown,
            boolean recallDown,
            boolean attackDown,
            boolean useDown
    ) {
        boolean[] down = {scanDown, swapDown, recallDown, attackDown, useDown};
        Arrays.fill(queuedRawPress, false);
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            heldSinceBoundary[i] = down[i];
            prevDown[i] = down[i];
        }
    }

    /**
     * Records a discrete {@code GLFW_PRESS} (never {@code GLFW_REPEAT}) captured while actively
     * piloting in FPV with no GUI screen open, so sub-tick taps faster than 50ms are never lost.
     */
    public void onRawPressInFpv(Channel channel) {
        if (channel == null || !inFpv) {
            return;
        }
        int idx = channel.ordinal();
        if (!heldSinceBoundary[idx]) {
            queuedRawPress[idx] = true;
        }
    }

    /**
     * Synchronizes perspective transitions immediately when {@code DroneStatePayload} arrives
     * or at the start of a client tick.
     */
    public void syncFpvState(
            boolean fpvActive,
            boolean scanDown,
            boolean swapDown,
            boolean recallDown,
            boolean attackDown,
            boolean useDown
    ) {
        boolean[] down = {scanDown, swapDown, recallDown, attackDown, useDown};
        if (fpvActive && !inFpv) {
            inFpv = true;
            Arrays.fill(queuedRawPress, false);
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                boolean blocked = down[i] || (i == Channel.USE.ordinal() && suppressOnFootUseUntilRelease);
                heldSinceBoundary[i] = blocked;
                prevDown[i] = down[i];
            }
        } else if (!fpvActive && inFpv) {
            inFpv = false;
            Arrays.fill(queuedRawPress, false);
            if (useDown) {
                suppressOnFootUseUntilRelease = true;
            }
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                heldSinceBoundary[i] = down[i];
                prevDown[i] = down[i];
            }
        }
    }

    /**
     * Returns {@code true} if on-foot {@code startUseItem} must stay suppressed because
     * {@code Right-Click} has not yet been physically released since deploying or exiting FPV.
     */
    public boolean shouldSuppressOnFootUse(boolean useDown) {
        if (!useDown) {
            suppressOnFootUseUntilRelease = false;
            return false;
        }
        return suppressOnFootUseUntilRelease;
    }

    public TickActions stepTick(
            boolean fpvActive,
            boolean screenOpen,
            boolean scanDown,
            boolean swapDown,
            boolean recallDown,
            boolean attackDown,
            boolean useDown
    ) {
        syncFpvState(fpvActive, scanDown, swapDown, recallDown, attackDown, useDown);
        if (!useDown) {
            suppressOnFootUseUntilRelease = false;
        }

        boolean[] down = {scanDown, swapDown, recallDown, attackDown, useDown};
        if (screenOpen) {
            Arrays.fill(queuedRawPress, false);
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                heldSinceBoundary[i] = down[i];
                prevDown[i] = down[i];
            }
            return TickActions.NONE;
        }

        if (inFpv) {
            boolean[] fired = new boolean[CHANNEL_COUNT];
            for (int i = 0; i < CHANNEL_COUNT; i++) {
                boolean rawTap = queuedRawPress[i] && !heldSinceBoundary[i];
                queuedRawPress[i] = false;
                if (!down[i]) {
                    heldSinceBoundary[i] = false;
                }
                boolean risingPoll = !heldSinceBoundary[i] && down[i] && !prevDown[i];
                fired[i] = rawTap || risingPoll;
                prevDown[i] = down[i];
            }
            if (fired[Channel.USE.ordinal()]) {
                suppressOnFootUseUntilRelease = true;
            }
            return new TickActions(
                    fired[Channel.SCAN.ordinal()],
                    fired[Channel.SWAP_BATTERY.ordinal()],
                    fired[Channel.RECALL_FOLD.ordinal()],
                    fired[Channel.ATTACK.ordinal()],
                    fired[Channel.USE.ordinal()]
            );
        }

        Arrays.fill(queuedRawPress, false);
        boolean[] onFootFired = new boolean[CHANNEL_COUNT];
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            if (!down[i]) {
                heldSinceBoundary[i] = false;
            }
            onFootFired[i] = !heldSinceBoundary[i] && down[i] && !prevDown[i];
            prevDown[i] = down[i];
        }
        return new TickActions(
                false,
                onFootFired[Channel.SWAP_BATTERY.ordinal()],
                onFootFired[Channel.RECALL_FOLD.ordinal()],
                false,
                false
        );
    }
}
