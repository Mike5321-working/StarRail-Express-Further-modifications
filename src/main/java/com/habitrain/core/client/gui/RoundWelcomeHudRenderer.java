package com.habitrain.core.client.gui;

import io.wifi.utils.client.betterrender.FakeGuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * 美化上游 {@code RoundTextRenderer} 对局开始欢迎报幕（{@code welcomeTime > 0}）。
 *
 * <p>保持无底框、无视野遮挡的通透视觉，严格卡点上游钢琴音效时间轴：
 * {@code 200 (UI_RISER)} → {@code 180 (UI_PIANO 1.25f)} →
 * {@code 120 (UI_PIANO 1.5f)} → {@code 60 (UI_PIANO 1.75f)} →
 * {@code 1 (UI_PIANO_STINGER)}。</p>
 *
 * <p>几何装饰全部通过 {@link FakeGuiGraphics#fill(int, int, int, int, int)} 绘制，
 * 因为上游 {@code OptimizedTextRenderer} 仅对 {@code FillAction} 与 {@code TextAction}
 * 捕获当前 {@code PoseStack} 矩阵（{@code hLine/vLine} 不保存矩阵）。</p>
 */
public final class RoundWelcomeHudRenderer {

    public static final int BUILDUP_START_TICK = 200;
    public static final int BEAT_TITLE_TICK = 180;
    public static final int BEAT_PREMISE_TICK = 120;
    public static final int BEAT_GOAL_TICK = 60;
    public static final int BEAT_JUMP_TICK = 56;
    public static final int EXIT_START_TICK = 18;

    public static final float VERTICAL_ANCHOR_OFFSET = -28f;
    public static final float TITLE_CENTER_Y = -14f;
    public static final int DIVIDER_LINE_Y = 3;
    public static final int CENTER_DOT_Y = 2;
    public static final float PREMISE_START_Y = 7f;
    public static final float PREMISE_END_Y = 11f;
    public static final float GOAL_TOP_Y = 25f;
    public static final float JUMP_START_Y = 41f;
    public static final float JUMP_END_Y = 38f;
    public static final float EXIT_LIFT_PX = 3f;

    public static final float TITLE_START_SCALE = 2.75f;
    public static final float TITLE_END_SCALE = 2.45f;
    public static final float GHOST_START_SCALE = 2.45f;
    public static final float GHOST_END_SCALE = 2.85f;
    public static final float GHOST_MAX_ALPHA = 0.35f;
    public static final float PREMISE_SCALE = 1.2f;
    public static final float GOAL_START_SCALE = 1.12f;
    public static final float GOAL_END_SCALE = 1.0f;
    public static final float JUMP_SCALE = 0.9f;
    private static final float CREDIT_TOP_Y = 51f;
    public static final float JUMP_MAX_ALPHA = 0.80f;

    public static final float DIVIDER_MIN_WIDTH = 100f;
    public static final float DIVIDER_WIDTH_MULTIPLIER = 1.6f;
    public static final float DIVIDER_CENTER_GAP_HALF = 3f;
    public static final float DIVIDER_CENTER_ALPHA = 0.55f;
    public static final float DIVIDER_EDGE_ALPHA = 0.15f;
    public static final float DIVIDER_PULSE_BOOST = 0.25f;
    private static final int DIVIDER_SEGMENTS_PER_SIDE = 5;

    public static final int BRACKET_LENGTH = 6;
    public static final float BRACKET_START_GAP = 14f;
    public static final float BRACKET_END_GAP = 6f;
    public static final float BRACKET_MAX_ALPHA = 0.80f;

    public static final int LOOSE_ENDS_RGB = 0x9F0000;
    public static final int DEFAULT_TEXT_RGB = 0xFFFFFF;

    private RoundWelcomeHudRenderer() {
    }

    /**
     * 合成带 Alpha 保护的 ARGB 颜色。
     *
     * <p>Minecraft 原生 {@link Font} 渲染时若高 6 位全为 0（{@code alphaInt < 4}），
     * 会强制将其视为完全不透明（{@code 0xFF}），造成淡入起点或淡出终点闪烁。
     * 因此当 {@code alphaInt < 4} 时返回 {@code 0} 作为跳过绘制哨兵值。</p>
     */
    public static int withAlpha(int rgb, float alpha) {
        int alphaInt = (int) (clamp01(alpha) * 255f);
        if (alphaInt < 4) {
            return 0;
        }
        return (alphaInt << 24) | (rgb & 0x00FFFFFF);
    }

    public static float clamp01(float value) {
        if (value <= 0f) {
            return 0f;
        }
        return Math.min(value, 1f);
    }

    public static float easeOutCubic(float t) {
        float clamped = clamp01(t);
        float inv = 1f - clamped;
        return 1f - inv * inv * inv;
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static int resolveAccentRgb(boolean isLooseEnds, int roleColour) {
        if (isLooseEnds) {
            return LOOSE_ENDS_RGB;
        }
        int masked = roleColour & 0x00FFFFFF;
        return masked != 0 ? masked : DEFAULT_TEXT_RGB;
    }

    public static int resolveBaseTextRgb(boolean isLooseEnds) {
        return isLooseEnds ? LOOSE_ENDS_RGB : DEFAULT_TEXT_RGB;
    }

    /**
     * 按当前 {@code welcomeTime} 计算本 tick 的完整动效状态（纯函数，便于单测）。
     *
     * <p>注意上游 {@code RoundTextRenderer#tick()} 会在播放音效后立刻执行 {@code welcomeTime--}：
     * 例如 {@code case 180} 播放第一声钢琴后，渲染帧看到的 {@code welcomeTime} 为 {@code 179}。
     * 因此蓄力阶段覆盖 {@code [200, 180]}，第一拍自 {@code < 180} 起步，与听觉节拍严丝合缝。</p>
     */
    public static WelcomeAnimState computeState(int welcomeTime, int welcomeWidth, int goalWidth) {
        if (welcomeTime <= 0 || welcomeTime > BUILDUP_START_TICK) {
            return WelcomeAnimState.INACTIVE;
        }

        float outProgress = welcomeTime <= EXIT_START_TICK
                ? clamp01(1f - welcomeTime / (float) EXIT_START_TICK)
                : 0f;
        float outEase = easeOutCubic(outProgress);
        float globalAlpha = 1f - outProgress;
        float exitOffsetY = -EXIT_LIFT_PX * outEase;

        // 1. 前奏蓄力（200 -> 181，并平滑衔接 180 落键前一 tick）
        if (welcomeTime >= BEAT_TITLE_TICK) {
            float buildupProgress = clamp01((BUILDUP_START_TICK - welcomeTime) / 20f);
            float buildupEase = easeOutCubic(buildupProgress);
            float dotAlpha = 0.95f * buildupEase;
            float lineAlpha = 0.45f * buildupEase;
            float lineInnerGap = lerp(42f, 4f, buildupEase);
            float lineLength = lerp(18f, 8f, buildupEase);
            return new WelcomeAnimState(
                    globalAlpha,
                    exitOffsetY,
                    true, dotAlpha, lineAlpha, lineInnerGap, lineLength,
                    false, TITLE_START_SCALE, 0f,
                    false, GHOST_START_SCALE, 0f,
                    false, 0f, 0f, 0f,
                    false, PREMISE_START_Y, 0f,
                    false, GOAL_START_SCALE, 0f, BRACKET_START_GAP, 0f,
                    false, JUMP_START_Y, 0f
            );
        }

        // 2. 第一拍：主标题落键（180 -> 172, 8 ticks）、残影（180 -> 170, 10 ticks）、细线展开（180 -> 168, 12 ticks）
        float titleT = clamp01((BEAT_TITLE_TICK - welcomeTime) / 8f);
        float titleEase = easeOutCubic(titleT);
        float titleScale = lerp(TITLE_START_SCALE, TITLE_END_SCALE, titleEase);
        float titleAlpha = titleEase * globalAlpha;

        boolean ghostVisible = welcomeTime >= 170;
        float ghostScale = GHOST_END_SCALE;
        float ghostAlpha = 0f;
        if (ghostVisible) {
            float ghostT = clamp01((179f - welcomeTime) / 10f);
            float ghostEase = easeOutCubic(ghostT);
            ghostScale = lerp(GHOST_START_SCALE, GHOST_END_SCALE, ghostEase);
            ghostAlpha = GHOST_MAX_ALPHA * (1f - ghostT) * globalAlpha;
        }

        float dividerExpandT = clamp01((BEAT_TITLE_TICK - welcomeTime) / 12f);
        float dividerExpandEase = easeOutCubic(dividerExpandT);
        float dividerRetractFactor = 1f - outEase;
        float fullDividerWidth = Math.max(DIVIDER_MIN_WIDTH, welcomeWidth * DIVIDER_WIDTH_MULTIPLIER);
        float dividerWidth = fullDividerWidth * dividerExpandEase * dividerRetractFactor;

        // 第二拍触发 6 ticks 微光脉冲（119 -> 114）
        float dividerPulseBoost = 0f;
        if (welcomeTime < BEAT_PREMISE_TICK && welcomeTime >= BEAT_PREMISE_TICK - 6) {
            float pulseT = clamp01((119f - welcomeTime) / 6f);
            dividerPulseBoost = DIVIDER_PULSE_BOOST * (1f - pulseT);
        }
        float dividerDotAlpha = clamp01(0.92f + dividerPulseBoost) * globalAlpha;

        // 3. 第二拍：杀手数量切入（120 -> 112, 8 ticks）
        boolean premiseVisible = welcomeTime < BEAT_PREMISE_TICK;
        float premiseY = PREMISE_START_Y;
        float premiseAlpha = 0f;
        if (premiseVisible) {
            float premiseT = clamp01((BEAT_PREMISE_TICK - welcomeTime) / 8f);
            float premiseEase = easeOutCubic(premiseT);
            premiseY = lerp(PREMISE_START_Y, PREMISE_END_Y, premiseEase);
            premiseAlpha = premiseEase * globalAlpha;
        }

        // 4a. 第三拍：职业目标与两侧极简短线（60 -> 52, 8 ticks）
        boolean goalVisible = welcomeTime < BEAT_GOAL_TICK;
        float goalScale = GOAL_START_SCALE;
        float goalAlpha = 0f;
        float bracketGap = BRACKET_START_GAP;
        float bracketAlpha = 0f;
        if (goalVisible) {
            float goalT = clamp01((BEAT_GOAL_TICK - welcomeTime) / 8f);
            float goalEase = easeOutCubic(goalT);
            goalScale = lerp(GOAL_START_SCALE, GOAL_END_SCALE, goalEase);
            goalAlpha = goalEase * globalAlpha;
            bracketGap = lerp(BRACKET_START_GAP, BRACKET_END_GAP, goalEase);
            bracketAlpha = goalWidth > 0 ? BRACKET_MAX_ALPHA * goalEase * globalAlpha : 0f;
        }

        // 4b. 延迟 4 ticks 的地图跳跃提示（56 -> 48, 8 ticks）
        boolean jumpVisible = welcomeTime < BEAT_JUMP_TICK;
        float jumpY = JUMP_START_Y;
        float jumpAlpha = 0f;
        if (jumpVisible) {
            float jumpT = clamp01((BEAT_JUMP_TICK - welcomeTime) / 8f);
            float jumpEase = easeOutCubic(jumpT);
            jumpY = lerp(JUMP_START_Y, JUMP_END_Y, jumpEase);
            jumpAlpha = JUMP_MAX_ALPHA * jumpEase * globalAlpha;
        }

        return new WelcomeAnimState(
                globalAlpha,
                exitOffsetY,
                false, 0f, 0f, 0f, 0f,
                true, titleScale, titleAlpha,
                ghostVisible, ghostScale, ghostAlpha,
                true, dividerWidth, dividerDotAlpha, dividerPulseBoost,
                premiseVisible, premiseY, premiseAlpha,
                goalVisible, goalScale, goalAlpha, bracketGap, bracketAlpha,
                jumpVisible, jumpY, jumpAlpha
        );
    }

    public static void renderWelcomeHud(
            Font renderer,
            FakeGuiGraphics context,
            int welcomeTime,
            Component welcomeText,
            int welcomeWidth,
            Component premiseText,
            int premiseWidth,
            Component goalText,
            int goalWidth,
            Component canJumpTip,
            int canJumpWidth,
            Component credit,
            int creditWidth,
            int accentRgb,
            int baseTextRgb
    ) {
        WelcomeAnimState state = computeState(welcomeTime, welcomeWidth, goalWidth);
        if (!state.hasAnyVisual()) {
            return;
        }

        float centerX = context.guiWidth() / 2f;
        float centerY = context.guiHeight() / 2f + VERTICAL_ANCHOR_OFFSET + state.exitOffsetY();

        context.pose().pushPose();
        context.pose().translate(centerX, centerY, 0f);

        if (state.buildupActive()) {
            renderBuildup(context, state, accentRgb);
        } else {
            if (state.dividerVisible()) {
                float maxAllowedWidth = Math.max(0f, context.guiWidth() - 32f);
                float clampedWidth = Math.min(state.dividerWidth(), maxAllowedWidth);
                renderDivider(context, clampedWidth, state.dividerDotAlpha(), state.dividerPulseBoost(),
                        state.globalAlpha(), accentRgb, baseTextRgb);
            }

            if (state.ghostVisible() && welcomeText != null) {
                renderCenteredScaledText(renderer, context, welcomeText, welcomeWidth,
                        TITLE_CENTER_Y, state.ghostScale(), baseTextRgb, state.ghostAlpha(), false);
            }

            if (state.titleVisible() && welcomeText != null) {
                renderCenteredScaledText(renderer, context, welcomeText, welcomeWidth,
                        TITLE_CENTER_Y, state.titleScale(), baseTextRgb, state.titleAlpha(), true);
            }

            if (state.premiseVisible() && premiseText != null) {
                int premiseArgb = withAlpha(baseTextRgb, state.premiseAlpha());
                if (premiseArgb != 0) {
                    context.pose().pushPose();
                    context.pose().translate(0f, state.premiseY(), 0f);
                    context.pose().scale(PREMISE_SCALE, PREMISE_SCALE, 1f);
                    context.drawString(renderer, premiseText, -premiseWidth / 2, 0, premiseArgb);
                    context.pose().popPose();
                }
            }

            if (state.goalVisible() && goalText != null) {
                float halfLineH = renderer.lineHeight / 2f;
                renderCenteredScaledText(renderer, context, goalText, goalWidth,
                        GOAL_TOP_Y + halfLineH, state.goalScale(), baseTextRgb, state.goalAlpha(), true);
                renderGoalBrackets(context, renderer, goalWidth, state.bracketGap(), state.bracketAlpha(), accentRgb);
            }

            if (state.jumpVisible() && canJumpTip != null) {
                int jumpArgb = withAlpha(baseTextRgb, state.jumpAlpha());
                if (jumpArgb != 0) {
                    context.pose().pushPose();
                    context.pose().translate(0f, state.jumpY(), 0f);
                    context.pose().scale(JUMP_SCALE, JUMP_SCALE, 1f);
                    context.drawString(renderer, canJumpTip, -canJumpWidth / 2, 0, jumpArgb);
                    context.pose().popPose();
                }
            }
            if (state.premiseVisible() && credit != null) {
                renderCenteredScaledText(renderer, context, credit, creditWidth,
                        CREDIT_TOP_Y + renderer.lineHeight / 2f, 0.9f, baseTextRgb, state.premiseAlpha(), false);
            }
        }

        context.pose().popPose();
    }

    private static void renderBuildup(FakeGuiGraphics context, WelcomeAnimState state, int accentRgb) {
        int dotArgb = withAlpha(accentRgb, state.buildupDotAlpha());
        if (dotArgb != 0) {
            context.fill(-1, CENTER_DOT_Y, 1, CENTER_DOT_Y + 2, dotArgb);
        }

        int lineArgb = withAlpha(accentRgb, state.buildupLineAlpha());
        if (lineArgb != 0) {
            int inner = Math.round(state.buildupLineInnerGap());
            int outer = Math.round(state.buildupLineInnerGap() + state.buildupLineLength());
            if (outer > inner) {
                context.fill(-outer, DIVIDER_LINE_Y, -inner, DIVIDER_LINE_Y + 1, lineArgb);
                context.fill(inner, DIVIDER_LINE_Y, outer, DIVIDER_LINE_Y + 1, lineArgb);
            }
        }
    }

    private static void renderDivider(
            FakeGuiGraphics context,
            float totalWidth,
            float dotAlpha,
            float pulseBoost,
            float globalAlpha,
            int accentRgb,
            int lineRgb
    ) {
        int dotArgb = withAlpha(accentRgb, dotAlpha);
        if (dotArgb != 0) {
            context.fill(-1, CENTER_DOT_Y, 1, CENTER_DOT_Y + 2, dotArgb);
        }

        float armLength = totalWidth * 0.5f - DIVIDER_CENTER_GAP_HALF;
        if (armLength <= 0.5f) {
            return;
        }

        for (int i = 0; i < DIVIDER_SEGMENTS_PER_SIDE; i++) {
            float frac0 = i / (float) DIVIDER_SEGMENTS_PER_SIDE;
            float frac1 = (i + 1) / (float) DIVIDER_SEGMENTS_PER_SIDE;
            float midFrac = (i + 0.5f) / (float) DIVIDER_SEGMENTS_PER_SIDE;

            float baseAlpha = lerp(DIVIDER_CENTER_ALPHA, DIVIDER_EDGE_ALPHA, midFrac);
            float pulseFactor = lerp(1f, 0.4f, midFrac);
            float segAlpha = clamp01(baseAlpha + pulseBoost * pulseFactor) * globalAlpha;
            int segArgb = withAlpha(lineRgb, segAlpha);
            if (segArgb == 0) {
                continue;
            }

            int rightX1 = Math.round(DIVIDER_CENTER_GAP_HALF + armLength * frac0);
            int rightX2 = Math.round(DIVIDER_CENTER_GAP_HALF + armLength * frac1);
            if (rightX2 > rightX1) {
                context.fill(rightX1, DIVIDER_LINE_Y, rightX2, DIVIDER_LINE_Y + 1, segArgb);
                context.fill(-rightX2, DIVIDER_LINE_Y, -rightX1, DIVIDER_LINE_Y + 1, segArgb);
            }
        }
    }

    private static void renderGoalBrackets(
            FakeGuiGraphics context,
            Font renderer,
            int goalWidth,
            float bracketGap,
            float bracketAlpha,
            int accentRgb
    ) {
        if (goalWidth <= 0) {
            return;
        }
        int bracketArgb = withAlpha(accentRgb, bracketAlpha);
        if (bracketArgb == 0) {
            return;
        }

        int bracketY = (int) GOAL_TOP_Y + renderer.lineHeight / 2;
        float halfGoalW = goalWidth * 0.5f;
        int leftX2 = Math.round(-(halfGoalW + bracketGap));
        int leftX1 = leftX2 - BRACKET_LENGTH;
        int rightX1 = Math.round(halfGoalW + bracketGap);
        int rightX2 = rightX1 + BRACKET_LENGTH;

        context.fill(leftX1, bracketY, leftX2, bracketY + 1, bracketArgb);
        context.fill(rightX1, bracketY, rightX2, bracketY + 1, bracketArgb);
    }

    private static void renderCenteredScaledText(
            Font renderer,
            FakeGuiGraphics context,
            Component text,
            int textWidth,
            float centerY,
            float scale,
            int rgb,
            float alpha,
            boolean shadow
    ) {
        int argb = withAlpha(rgb, alpha);
        if (argb == 0) {
            return;
        }
        context.pose().pushPose();
        context.pose().translate(0f, centerY, 0f);
        context.pose().scale(scale, scale, 1f);
        context.pose().translate(-textWidth / 2f, -renderer.lineHeight / 2f, 0f);
        context.drawString(renderer, text, 0, 0, argb, shadow);
        context.pose().popPose();
    }

    public record WelcomeAnimState(
            float globalAlpha,
            float exitOffsetY,
            boolean buildupActive,
            float buildupDotAlpha,
            float buildupLineAlpha,
            float buildupLineInnerGap,
            float buildupLineLength,
            boolean titleVisible,
            float titleScale,
            float titleAlpha,
            boolean ghostVisible,
            float ghostScale,
            float ghostAlpha,
            boolean dividerVisible,
            float dividerWidth,
            float dividerDotAlpha,
            float dividerPulseBoost,
            boolean premiseVisible,
            float premiseY,
            float premiseAlpha,
            boolean goalVisible,
            float goalScale,
            float goalAlpha,
            float bracketGap,
            float bracketAlpha,
            boolean jumpVisible,
            float jumpY,
            float jumpAlpha
    ) {
        public static final WelcomeAnimState INACTIVE = new WelcomeAnimState(
                0f, 0f,
                false, 0f, 0f, 0f, 0f,
                false, TITLE_START_SCALE, 0f,
                false, GHOST_START_SCALE, 0f,
                false, 0f, 0f, 0f,
                false, PREMISE_START_Y, 0f,
                false, GOAL_START_SCALE, 0f, BRACKET_START_GAP, 0f,
                false, JUMP_START_Y, 0f
        );

        public boolean hasAnyVisual() {
            return buildupActive || titleVisible || ghostVisible || dividerVisible
                    || premiseVisible || goalVisible || jumpVisible;
        }
    }
}
