package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.replay.ReplayStagePlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Star-Night Express Art Deco "Tactical Action Toast" HUD renderer for unknown/third-party mod
 * skills &amp; notable item activations.
 *
 * <p>Renders 45°-chamfered deep-night plates trimmed in brushed brass with a faction/role color
 * accent bar, lightning emblem, optional 3D/2D item icon, and localized message:
 * {@code ⚡ [技能/道具触发] 玩家名（职业名） 使用了「技能/物品名称」}. Supports both offscreen
 * {@link ReplayVideoCanvas} video export and live {@link GuiGraphics} overlays (history preview,
 * sandbox playback).</p>
 */
public final class ReplaySkillFallbackHud {
    /** Default on-screen duration of each toast in replay ticks (3.6 seconds at 20 TPS). */
    public static final double TOAST_DURATION_TICKS = 72.0;
    public static final int MAX_VISIBLE_TOASTS = 4;

    private ReplaySkillFallbackHud() {}

    /**
     * One active tactical action toast ready to render.
     *
     * @param id           stable event id for deduplication/ordering
     * @param triggerTick  replay tick when triggered
     * @param actorName    caster player name
     * @param roleName     localized role name (may be empty)
     * @param skillName    localized skill or item display name
     * @param accentColor  ARGB role primary color
     * @param itemId       optional item id for thumbnail rendering
     * @param age01        normalized age in {@code [0, 1]}
     */
    public record ToastEntry(
            int id,
            double triggerTick,
            String actorName,
            String roleName,
            String skillName,
            int accentColor,
            @Nullable String itemId,
            double age01
    ) {
        public double visibility() {
            double in = ReplayStagePlan.smoothstep(0.0, 0.12, age01);
            double out = 1.0 - ReplayStagePlan.smoothstep(0.80, 1.0, age01);
            return Math.max(0.0, Math.min(1.0, in * out));
        }

        public double slideIn() {
            return ReplayStagePlan.easeOutCubic(Math.min(1.0, age01 / 0.14));
        }

        public Component headerText() {
            return Component.translatable("habitrain_core.replay.skill_toast.prefix");
        }

        public Component bodyText() {
            if (roleName == null || roleName.isBlank()) {
                return Component.translatable("habitrain_core.replay.skill_toast.no_role", actorName, skillName);
            }
            return Component.translatable("habitrain_core.replay.skill_toast.line", actorName, roleName, skillName);
        }
    }

    /**
     * Collects active fallback / tactical action toasts at {@code matchTick}.
     * Populated from {@link ReplaySkillEffectRegistry#collectFallbackToasts(ReplayBoardModel, double, Predicate)}.
     */
    public static List<ToastEntry> collectActive(ReplayBoardModel model, double matchTick) {
        return collectActive(model, matchTick, null);
    }

    public static List<ToastEntry> collectActive(ReplayBoardModel model, double matchTick,
                                                 @Nullable Predicate<ToastEntry> filter) {
        if (model == null) return List.of();
        List<ToastEntry> raw = ReplaySkillEffectRegistry.collectFallbackToasts(model, matchTick, filter);
        if (raw.size() <= MAX_VISIBLE_TOASTS) return raw;
        List<ToastEntry> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingDouble(ToastEntry::triggerTick).reversed().thenComparingInt(ToastEntry::id));
        return List.copyOf(sorted.subList(0, MAX_VISIBLE_TOASTS));
    }

    /**
     * Renders active tactical action toasts onto a {@link ReplayVideoCanvas} within the specified
     * design-space viewport rectangle (supports full screen or split-screen panels).
     */
    public static void renderOnCanvas(ReplayVideoCanvas canvas, ReplayBoardModel model, double matchTick,
                                      float vpX, float vpY, float vpW, float vpH,
                                      boolean rightAligned, float alpha) {
        renderEntriesOnCanvas(canvas, collectActive(model, matchTick), vpX, vpY, vpW, vpH,
                rightAligned, false, false, MAX_VISIBLE_TOASTS, alpha);
    }

    public static void renderOnCanvas(ReplayVideoCanvas canvas, ReplayBoardModel model, double matchTick,
                                      float vpX, float vpY, float vpW, float vpH,
                                      boolean rightAligned, boolean bottomAligned, boolean compact,
                                      int maxVisible, float alpha) {
        renderEntriesOnCanvas(canvas, collectActive(model, matchTick), vpX, vpY, vpW, vpH,
                rightAligned, bottomAligned, compact, maxVisible, alpha);
    }

    public static void renderEntriesOnCanvas(ReplayVideoCanvas canvas, List<ToastEntry> entries,
                                             float vpX, float vpY, float vpW, float vpH,
                                             boolean rightAligned, float alpha) {
        renderEntriesOnCanvas(canvas, entries, vpX, vpY, vpW, vpH, rightAligned, false, false, MAX_VISIBLE_TOASTS, alpha);
    }

    public static void renderEntriesOnCanvas(ReplayVideoCanvas canvas, List<ToastEntry> entries,
                                             float vpX, float vpY, float vpW, float vpH,
                                             boolean rightAligned, boolean bottomAligned, boolean compact,
                                             int maxVisible, float alpha) {
        if (canvas == null || entries == null || entries.isEmpty() || alpha <= 0.02f || maxVisible <= 0) return;
        int limit = Math.min(MAX_VISIBLE_TOASTS, maxVisible);
        float margin = Math.max(16f, vpW * 0.018f);
        float cardW = compact
                ? Math.min(360f, Math.max(220f, vpW - margin * 2f))
                : Math.min(460f, Math.max(260f, vpW * 0.38f));
        float cardH = compact ? 48f : 60f;
        float gap = compact ? 8f : 10f;
        float cut = compact ? 9f : 12f;
        float startY = vpY + Math.min(vpH * 0.22f, 148f);

        int drawn = 0;
        for (ToastEntry e : entries) {
            if (drawn >= limit) break;
            double vis = e.visibility() * alpha;
            if (vis <= 0.02) continue;

            float slide = (float) (1.0 - e.slideIn()) * 42f;
            float x0 = rightAligned ? (vpX + vpW - margin - cardW + slide) : (vpX + margin - slide);
            float y0 = bottomAligned
                    ? (vpY + vpH - cardH - drawn * (cardH + gap))
                    : (startY + drawn * (cardH + gap));
            float x1 = x0 + cardW;
            float y1 = y0 + cardH;

            int bgTop = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.94 * vis);
            int bgBot = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.96 * vis);
            int border = ReplayPalette.alpha(ReplayPalette.BRASS, 0.94 * vis);
            int headerCol = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.98 * vis);
            int accent = ReplayPalette.alpha(ReplayPalette.opaque(e.accentColor(), ReplayPalette.BRASS_LIGHT), 0.96 * vis);

            // 45°-chamfered plate (top-right & bottom-left cuts)
            canvas.quad(x0, y0, bgTop, x0, y1 - cut, bgBot, x1, y1, bgBot, x1 - cut, y0, bgTop);
            canvas.quad(x0, y1 - cut, bgBot, x0 + cut, y1, bgBot, x1, y1, bgBot, x1, y1 - cut, bgBot);
            canvas.quad(x1 - cut, y0, bgTop, x1 - cut, y0 + cut, bgTop, x1, y0 + cut, bgTop, x1 - cut, y0, bgTop);
            canvas.rect(x0 + 1.5f, y0 + cut * 0.5f, x1 - 1.5f, y1 - cut * 0.5f, bgTop, bgBot);

            // Outer hairline outline with 45° chamfers
            canvas.line(x0, y0, x1 - cut, y0, 1.8f, headerCol, border);
            canvas.line(x1 - cut, y0, x1, y0 + cut, 1.8f, border, headerCol);
            canvas.line(x1, y0 + cut, x1, y1, 1.5f, border, ReplayPalette.fade(border, 0.65));
            canvas.line(x1, y1, x0 + cut, y1, 1.8f, ReplayPalette.fade(border, 0.65), border);
            canvas.line(x0 + cut, y1, x0, y1 - cut, 1.8f, border, headerCol);
            canvas.line(x0, y1 - cut, x0, y0, 1.8f, headerCol, border);

            // Left faction/role accent ribbon + geometric emblem
            canvas.rect(x0 + 3.0f, y0 + 5f, x0 + 8.0f, y1 - cut - 1f, accent, ReplayPalette.fade(accent, 0.65));
            float iconCx = x0 + (compact ? 24f : 27f);
            canvas.diamond(iconCx, y0 + cardH * 0.5f, compact ? 8.5f : 10.5f, ReplayPalette.alpha(e.accentColor(), 0.28 * vis));

            ItemStack stack = ReplayFullPlayerRenderer.resolveItem(e.itemId());
            if (!stack.isEmpty()) {
                canvas.item(stack, iconCx + 1f, y0 + cardH * 0.5f, compact ? 20f : 24f);
            } else {
                canvas.icon(ReplayVideoCanvas.BOLT, iconCx, y0 + cardH * 0.5f, compact ? 18f : 22f,
                        ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.96 * vis));
            }

            float textX = x0 + (compact ? 42f : 50f);
            float textMaxW = cardW - (compact ? 54f : 64f);
            Component header = Component.literal("⚡ [").append(e.headerText()).append("]");
            canvas.textFit(header, textX, y0 + (compact ? 14.5f : 18.0f), compact ? 1.12f : 1.32f,
                    textMaxW, headerCol, -1, true);
            canvas.textFit(e.bodyText(), textX, y0 + (compact ? 33.0f : 41.0f), compact ? 1.32f : 1.55f,
                    textMaxW, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, vis), -1, true);

            drawn++;
        }
    }

    /**
     * Renders active tactical action toasts using standard Minecraft {@link GuiGraphics} (for
     * {@code ReplayHistoryScreen} preview or live in-game sandbox overlay).
     */
    public static void renderInGui(GuiGraphics g, Minecraft mc, ReplayBoardModel model, double matchTick,
                                   int x, int y, int width, int height, float alpha) {
        renderEntriesInGui(g, mc, collectActive(model, matchTick), x, y, width, height, alpha);
    }

    public static void renderEntriesInGui(GuiGraphics g, Minecraft mc, List<ToastEntry> entries,
                                          int x, int y, int width, int height, float alpha) {
        if (g == null || mc == null || entries == null || entries.isEmpty() || alpha <= 0.02f) return;
        Font font = mc.font;
        int cardW = Math.min(230, Math.max(150, (int) (width * 0.42f)));
        int cardH = 30;
        int gap = 5;
        int margin = 8;
        int drawn = 0;

        for (ToastEntry e : entries) {
            if (drawn >= MAX_VISIBLE_TOASTS) break;
            double vis = e.visibility() * alpha;
            if (vis <= 0.02) continue;

            int slide = (int) Math.round((1.0 - e.slideIn()) * 24.0);
            int x0 = x + width - margin - cardW + slide;
            int y0 = y + margin + drawn * (cardH + gap);
            if (y0 + cardH > y + height - margin) break;
            int x1 = x0 + cardW;
            int y1 = y0 + cardH;

            int bgTop = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.88 * vis);
            int bgBot = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.92 * vis);
            int brass = ReplayPalette.alpha(ReplayPalette.BRASS, 0.92 * vis);
            int brassLight = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.95 * vis);
            int accent = ReplayPalette.alpha(ReplayPalette.opaque(e.accentColor(), ReplayPalette.BRASS_LIGHT), 0.95 * vis);

            g.fillGradient(x0, y0, x1, y1, bgTop, bgBot);
            // Brass top/bottom Art Deco bars + accent side bar
            g.fill(x0, y0, x1 - 4, y0 + 1, brassLight);
            g.fill(x0 + 4, y1 - 1, x1, y1, brass);
            g.fill(x0, y0, x0 + 3, y1, accent);
            g.fill(x1 - 1, y0 + 4, x1, y1, ReplayPalette.fade(brass, 0.7));

            ItemStack stack = ReplayFullPlayerRenderer.resolveItem(e.itemId());
            int textX = x0 + 7;
            if (!stack.isEmpty()) {
                g.renderItem(stack, x0 + 5, y0 + 7);
                textX = x0 + 24;
            }
            int maxTextW = Math.max(40, x1 - textX - 5);
            String header = "⚡ [" + e.headerText().getString() + "]";
            String body = e.bodyText().getString();

            g.pose().pushPose();
            float hs = 0.76f;
            g.pose().translate(textX, y0 + 4, 0);
            g.pose().scale(hs, hs, 1f);
            g.drawString(font, font.plainSubstrByWidth(header, (int) (maxTextW / hs)), 0, 0, brassLight, false);
            g.pose().popPose();

            g.pose().pushPose();
            float bs = 0.84f;
            g.pose().translate(textX, y0 + 15, 0);
            g.pose().scale(bs, bs, 1f);
            g.drawString(font, font.plainSubstrByWidth(body, (int) (maxTextW / bs)), 0, 0,
                    ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, vis), true);
            g.pose().popPose();

            drawn++;
        }
    }
}
