package com.habitrain.core.client.gui;

import com.habitrain.core.client.gui.RoundWelcomeHudRenderer.WelcomeAnimState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoundWelcomeHudRendererTest {

    @Test
    void withAlphaSkipsSubThresholdAndPreservesRgbAboveThreshold() {
        assertEquals(0, RoundWelcomeHudRenderer.withAlpha(0xFFFFFF, -0.5f));
        assertEquals(0, RoundWelcomeHudRenderer.withAlpha(0xFFFFFF, 0f));
        // (int)(0.015f * 255) == 3 < 4 -> must skip to prevent Minecraft Font 0xFF flash
        assertEquals(0, RoundWelcomeHudRenderer.withAlpha(0x123456, 3.9f / 255f));

        // alphaInt == 4 -> safe above Font's 0xFC000000 threshold
        int minVisible = RoundWelcomeHudRenderer.withAlpha(0xAA123456, 4f / 255f);
        assertEquals(0x04123456, minVisible);
        assertNotEquals(0, minVisible & 0xFC000000);

        int opaque = RoundWelcomeHudRenderer.withAlpha(0x36E51B, 1.5f);
        assertEquals(0xFF36E51B, opaque);
    }

    @Test
    void resolveColorsRespectLooseEndsAndRoleAccent() {
        assertEquals(0x9F0000, RoundWelcomeHudRenderer.resolveAccentRgb(true, 0x36E51B));
        assertEquals(0x9F0000, RoundWelcomeHudRenderer.resolveBaseTextRgb(true));

        assertEquals(0x36E51B, RoundWelcomeHudRenderer.resolveAccentRgb(false, 0xFF36E51B));
        assertEquals(0xFFFFFF, RoundWelcomeHudRenderer.resolveAccentRgb(false, 0x000000));
        assertEquals(0xFFFFFF, RoundWelcomeHudRenderer.resolveBaseTextRgb(false));
    }

    @Test
    void inactiveBeforeRiserAndAtZero() {
        assertFalse(RoundWelcomeHudRenderer.computeState(240, 80, 120).hasAnyVisual());
        assertFalse(RoundWelcomeHudRenderer.computeState(201, 80, 120).hasAnyVisual());
        assertFalse(RoundWelcomeHudRenderer.computeState(0, 80, 120).hasAnyVisual());
        assertFalse(RoundWelcomeHudRenderer.computeState(-5, 80, 120).hasAnyVisual());
    }

    @Test
    void stage1BuildupBrightensCenterDotAndConvergesLinesWithoutText() {
        WelcomeAnimState early = RoundWelcomeHudRenderer.computeState(199, 80, 120);
        WelcomeAnimState late = RoundWelcomeHudRenderer.computeState(181, 80, 120);

        assertTrue(early.buildupActive());
        assertFalse(early.titleVisible());
        assertFalse(early.premiseVisible());
        assertFalse(early.goalVisible());
        assertFalse(early.jumpVisible());

        assertTrue(late.buildupActive());
        assertTrue(late.buildupDotAlpha() > early.buildupDotAlpha());
        assertTrue(late.buildupLineAlpha() > early.buildupLineAlpha());
        assertTrue(late.buildupLineInnerGap() < early.buildupLineInnerGap());
    }

    @Test
    void stage2Beat1ConvergesTitleExpandsGhostEchoAndDivider() {
        WelcomeAnimState strike = RoundWelcomeHudRenderer.computeState(179, 80, 120);
        WelcomeAnimState mid = RoundWelcomeHudRenderer.computeState(175, 80, 120);
        WelcomeAnimState settled = RoundWelcomeHudRenderer.computeState(168, 80, 120);

        assertFalse(strike.buildupActive());
        assertTrue(strike.titleVisible());
        assertTrue(strike.ghostVisible());
        assertTrue(strike.dividerVisible());
        assertFalse(strike.premiseVisible());
        assertFalse(strike.jumpVisible());

        // Title converges from 2.75f to 2.45f
        assertTrue(strike.titleScale() < 2.75f && strike.titleScale() > 2.45f);
        assertTrue(mid.titleScale() < strike.titleScale());
        assertEquals(2.45f, settled.titleScale(), 1e-4f);
        assertEquals(1.0f, settled.titleAlpha(), 1e-4f);

        // Ghost echo expands from 2.45f to 2.85f and fades from 0.35 to 0 over 10 ticks
        assertEquals(2.45f, strike.ghostScale(), 1e-4f);
        assertEquals(0.35f, strike.ghostAlpha(), 1e-4f);
        assertTrue(mid.ghostScale() > strike.ghostScale());
        assertTrue(mid.ghostAlpha() < strike.ghostAlpha());
        assertFalse(settled.ghostVisible());

        // Divider expands to max(100, welcomeWidth * 1.6f)
        assertTrue(strike.dividerWidth() > 0f && strike.dividerWidth() < 128f);
        assertEquals(128f, settled.dividerWidth(), 1e-4f);
        assertEquals(100f, RoundWelcomeHudRenderer.computeState(168, 40, 120).dividerWidth(), 1e-4f);
    }

    @Test
    void stage3Beat2SlidesPremisePulsesDividerAndKeepsJumpHidden() {
        WelcomeAnimState strike = RoundWelcomeHudRenderer.computeState(119, 80, 120);
        WelcomeAnimState settled = RoundWelcomeHudRenderer.computeState(112, 80, 120);

        assertTrue(strike.premiseVisible());
        assertTrue(strike.premiseY() > 7f && strike.premiseY() < 11f);
        assertEquals(0.25f, strike.dividerPulseBoost(), 1e-4f);
        assertFalse(strike.goalVisible());
        assertFalse(strike.jumpVisible());

        assertEquals(11f, settled.premiseY(), 1e-4f);
        assertEquals(1.0f, settled.premiseAlpha(), 1e-4f);
        assertEquals(0f, settled.dividerPulseBoost(), 1e-4f);
        assertFalse(settled.jumpVisible());
    }

    @Test
    void stage4Beat3LocksGoalWithBracketsAndDelaysJumpTipBy4Ticks() {
        WelcomeAnimState goalStrike = RoundWelcomeHudRenderer.computeState(59, 80, 120);
        WelcomeAnimState goalMid = RoundWelcomeHudRenderer.computeState(56, 80, 120);
        WelcomeAnimState jumpStrike = RoundWelcomeHudRenderer.computeState(55, 80, 120);
        WelcomeAnimState settled = RoundWelcomeHudRenderer.computeState(48, 80, 120);

        assertTrue(goalStrike.goalVisible());
        assertTrue(goalStrike.goalScale() < 1.12f && goalStrike.goalScale() > 1.0f);
        assertTrue(goalStrike.bracketGap() < 14f && goalStrike.bracketGap() > 6f);
        assertFalse(goalStrike.jumpVisible());
        assertFalse(goalMid.jumpVisible());

        assertTrue(jumpStrike.jumpVisible());
        assertTrue(jumpStrike.jumpY() < 41f && jumpStrike.jumpY() > 38f);

        assertEquals(1.0f, settled.goalScale(), 1e-4f);
        assertEquals(6f, settled.bracketGap(), 1e-4f);
        assertEquals(38f, settled.jumpY(), 1e-4f);
        assertEquals(0.80f, settled.jumpAlpha(), 1e-4f);
    }

    @Test
    void stage5ExitRetractsDividerLiftsTextAndFadesSmoothlyWithoutFlash() {
        WelcomeAnimState start = RoundWelcomeHudRenderer.computeState(18, 80, 120);
        WelcomeAnimState mid = RoundWelcomeHudRenderer.computeState(9, 80, 120);
        WelcomeAnimState end = RoundWelcomeHudRenderer.computeState(1, 80, 120);

        assertEquals(1.0f, start.globalAlpha(), 1e-4f);
        assertEquals(0f, start.exitOffsetY(), 1e-4f);
        assertEquals(128f, start.dividerWidth(), 1e-4f);

        assertEquals(0.5f, mid.globalAlpha(), 1e-4f);
        assertTrue(mid.exitOffsetY() < 0f && mid.exitOffsetY() > -3f);
        assertTrue(mid.dividerWidth() < start.dividerWidth() && mid.dividerWidth() > 0f);

        assertEquals(1f / 18f, end.globalAlpha(), 1e-4f);
        assertTrue(end.exitOffsetY() < -2.9f && end.exitOffsetY() >= -3f);
        assertTrue(end.dividerWidth() < 1f);
        // Ensure final visible frame still has alphaInt >= 4 (no Font 0xFF flash)
        int finalTitleArgb = RoundWelcomeHudRenderer.withAlpha(0xFFFFFF, end.titleAlpha());
        assertNotEquals(0, finalTitleArgb);
        assertEquals(14, (finalTitleArgb >>> 24) & 0xFF);
    }
}
