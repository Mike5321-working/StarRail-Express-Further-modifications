package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import static com.habitrain.core.client.title.HabiKineticGfx.eInCubic;
import static com.habitrain.core.client.title.HabiKineticGfx.eOutCubic;
import static com.habitrain.core.client.title.HabiKineticGfx.sat;
import static com.habitrain.core.client.title.HabiKineticGfx.sstep;

/**
 * Deterministic top-to-bottom staggered slide transition state machine for the Station 01
 * left selection viewport.
 *
 * <ul>
 *   <li>Forward ({@code MAIN -> MULTIPLAYER/SINGLEPLAYER}): outgoing items whip left out of
 *       the selection box ({@code 0 -> -slideDistance}), while incoming items glide in from
 *       right to left ({@code +slideDistance -> 0}), staggered sequentially from top to bottom.</li>
 *   <li>Backward ({@code MULTIPLAYER/SINGLEPLAYER -> MAIN}): outgoing items whip right out of
 *       the selection box ({@code 0 -> +slideDistance}), while main menu items glide back in
 *       from left to right ({@code -slideDistance -> 0}), staggered sequentially from top to bottom.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class HabiStationListTransition {

    public enum PanelMode {
        MAIN,
        MULTIPLAYER,
        SINGLEPLAYER
    }

    public record ItemMotion(double offsetX, double alpha, boolean visible) {
        public static final ItemMotion SETTLED = new ItemMotion(0.0, 1.0, true);
        public static final ItemMotion HIDDEN = new ItemMotion(0.0, 0.0, false);
    }

    /** Per-row stagger delay (seconds) for outgoing items sliding out from top to bottom. */
    public static final double OUT_STAGGER_SEC = 0.036;
    /** Duration (seconds) for each individual outgoing item to leave the selection box. */
    public static final double OUT_DURATION_SEC = 0.24;
    /** Delay (seconds) before incoming row 0 begins entering so outgoing/incoming overlap crisply. */
    public static final double IN_BASE_DELAY_SEC = 0.13;
    /** Per-row stagger delay (seconds) for incoming items sliding in from top to bottom. */
    public static final double IN_STAGGER_SEC = 0.048;
    /** Duration (seconds) for each individual incoming item to settle into place. */
    public static final double IN_DURATION_SEC = 0.34;
    /** Earliest elapsed time (seconds) before clicks on incoming list items are accepted (prevents accidental double-click joins). */
    public static final double CLICK_GUARD_SEC = 0.28;
    /** Upper cap on staggered visual rows so long scrolled lists still settle crisply. */
    public static final int MAX_STAGGER_RANK = 8;

    private PanelMode activePanel = PanelMode.MAIN;
    private @Nullable PanelMode outgoingPanel = null;
    private int slideDirection = 1; // +1: forward (out left, in from right), -1: back (out right, in from left)
    private double elapsedSec = 0.0;
    private double totalDurationSec = 0.0;
    private boolean transitioning = false;

    /** Optional secondary reveal clock when async world/server entries finish loading after entering the panel. */
    private double asyncRevealElapsedSec = 0.0;
    private double asyncRevealDurationSec = 0.0;
    private boolean asyncRevealing = false;

    public PanelMode getActivePanel() {
        return activePanel;
    }

    public @Nullable PanelMode getOutgoingPanel() {
        return transitioning ? outgoingPanel : null;
    }

    public boolean isTransitioning() {
        return transitioning;
    }

    public boolean isInSubPanel() {
        return activePanel != PanelMode.MAIN;
    }

    public double getElapsedSec() {
        return elapsedSec;
    }

    public int getSlideDirection() {
        return slideDirection;
    }

    /** Returns {@code true} once an entering panel is settled enough to accept primary item clicks safely. */
    public boolean canAcceptItemClick() {
        return !transitioning || elapsedSec >= CLICK_GUARD_SEC;
    }

    /** Starts a staggered transition into {@code targetPanel}. */
    public void transitionTo(PanelMode targetPanel, int outgoingVisibleRows, int incomingVisibleRows) {
        if (targetPanel == null || targetPanel == activePanel) return;
        this.outgoingPanel = this.activePanel;
        this.activePanel = targetPanel;
        this.slideDirection = (targetPanel != PanelMode.MAIN) ? 1 : -1;
        this.elapsedSec = 0.0;
        this.transitioning = true;
        this.asyncRevealing = false;
        this.totalDurationSec = computeTotalDuration(outgoingVisibleRows, incomingVisibleRows);
    }

    /** Extends transition duration if async items arrive mid-transition, or starts a gentle top-to-bottom reveal. */
    public void onAsyncContentArrived(int incomingVisibleRows) {
        int clampedIn = clampVisibleCount(incomingVisibleRows);
        double inEnd = IN_BASE_DELAY_SEC + Math.max(0, clampedIn - 1) * IN_STAGGER_SEC + IN_DURATION_SEC;
        if (transitioning && elapsedSec <= IN_BASE_DELAY_SEC + 0.08) {
            totalDurationSec = Math.max(totalDurationSec, inEnd);
        } else if (!transitioning || elapsedSec > IN_BASE_DELAY_SEC + 0.08) {
            asyncRevealing = true;
            asyncRevealElapsedSec = 0.0;
            asyncRevealDurationSec = Math.max(0, clampedIn - 1) * IN_STAGGER_SEC + IN_DURATION_SEC;
        }
    }

    /** Immediately snaps to {@code targetPanel} with no active transition (e.g. fresh init or return from native edit). */
    public void snapTo(PanelMode targetPanel) {
        this.activePanel = targetPanel == null ? PanelMode.MAIN : targetPanel;
        this.outgoingPanel = null;
        this.transitioning = false;
        this.elapsedSec = 0.0;
        this.totalDurationSec = 0.0;
        this.asyncRevealing = false;
        this.asyncRevealElapsedSec = 0.0;
        this.asyncRevealDurationSec = 0.0;
    }

    /** Advances transition timers by {@code dtSec}. */
    public void step(double dtSec) {
        double dt = Math.max(0.0, Math.min(0.1, dtSec));
        if (transitioning) {
            elapsedSec += dt;
            if (elapsedSec >= totalDurationSec) {
                transitioning = false;
                outgoingPanel = null;
            }
        }
        if (asyncRevealing) {
            asyncRevealElapsedSec += dt;
            if (asyncRevealElapsedSec >= asyncRevealDurationSec) {
                asyncRevealing = false;
            }
        }
    }

    /**
     * Computes horizontal offset and opacity for an outgoing item at {@code visualRank} (0 = top visible row).
     * Forward ({@code slideDirection = +1}): slides left ({@code offsetX <= 0}).
     * Backward ({@code slideDirection = -1}): slides right ({@code offsetX >= 0}).
     */
    public ItemMotion outgoingMotion(int visualRank, double slideDistance) {
        if (!transitioning || outgoingPanel == null) return ItemMotion.HIDDEN;
        int rank = clampRank(visualRank);
        double localT = (elapsedSec - rank * OUT_STAGGER_SEC) / OUT_DURATION_SEC;
        if (localT >= 1.0) return ItemMotion.HIDDEN;
        double p = sat(localT);
        double eased = eInCubic(p);
        double ox = -slideDirection * slideDistance * eased;
        double alpha = 1.0 - sstep(0.60, 1.0, p);
        return new ItemMotion(ox, alpha, alpha > 0.005);
    }

    /**
     * Computes horizontal offset and opacity for an incoming item at {@code visualRank} (0 = top visible row).
     * Forward ({@code slideDirection = +1}): enters from right to left ({@code offsetX >= 0 -> 0}).
     * Backward ({@code slideDirection = -1}): enters from left to right ({@code offsetX <= 0 -> 0}).
     */
    public ItemMotion incomingMotion(int visualRank, double slideDistance) {
        if (transitioning) {
            int rank = clampRank(visualRank);
            double localT = (elapsedSec - (IN_BASE_DELAY_SEC + rank * IN_STAGGER_SEC)) / IN_DURATION_SEC;
            if (localT <= 0.0) return ItemMotion.HIDDEN;
            double p = sat(localT);
            double eased = eOutCubic(p);
            double ox = slideDirection * slideDistance * (1.0 - eased);
            double alpha = sstep(0.0, 0.28, p);
            return new ItemMotion(ox, alpha, alpha > 0.005);
        }
        if (asyncRevealing) {
            int rank = clampRank(visualRank);
            double localT = (asyncRevealElapsedSec - rank * IN_STAGGER_SEC) / IN_DURATION_SEC;
            if (localT <= 0.0) return ItemMotion.HIDDEN;
            double p = sat(localT);
            double eased = eOutCubic(p);
            double ox = slideDistance * (1.0 - eased);
            double alpha = sstep(0.0, 0.28, p);
            return new ItemMotion(ox, alpha, alpha > 0.005);
        }
        return ItemMotion.SETTLED;
    }

    /** Returns normalized glow factor [0..1] for subtle selection-box frame guides during a slide transition. */
    public double transitionFrameGlow() {
        if (!transitioning || totalDurationSec <= 1e-6) return 0.0;
        double p = sat(elapsedSec / totalDurationSec);
        return Math.sin(p * Math.PI);
    }

    /** Computes total transition duration for given visible outgoing and incoming row counts. */
    public static double computeTotalDuration(int outgoingVisibleRows, int incomingVisibleRows) {
        int outCount = clampVisibleCount(outgoingVisibleRows);
        int inCount = clampVisibleCount(incomingVisibleRows);
        double outEnd = Math.max(0, outCount - 1) * OUT_STAGGER_SEC + OUT_DURATION_SEC;
        double inEnd = IN_BASE_DELAY_SEC + Math.max(0, inCount - 1) * IN_STAGGER_SEC + IN_DURATION_SEC;
        return Math.max(outEnd, inEnd) + 0.02;
    }

    /** Maps a row's vertical offset relative to the viewport top to a clamped top-to-bottom stagger rank. */
    public static int visualRankFromOffset(double itemTop, double viewportTop, double stride) {
        if (stride <= 1.0) return 0;
        int rank = (int) Math.round((itemTop - viewportTop) / stride);
        return clampRank(rank);
    }

    private static int clampVisibleCount(int count) {
        return Math.max(1, Math.min(MAX_STAGGER_RANK + 1, count));
    }

    private static int clampRank(int rank) {
        return Math.max(0, Math.min(MAX_STAGGER_RANK, rank));
    }
}
