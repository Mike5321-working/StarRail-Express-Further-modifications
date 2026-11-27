package com.habitrain.core.scene.client.replay;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.config.ReplayAudioSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Shared Star-Night Express Art Deco client-local volume settings overlay used by both
 * {@link ReplayBoardControlsScreen} and {@link ReplayHistoryScreen}.
 *
 * <p>Controls master volume, player voice volume, game sound volume (0%–100%, default 100%)
 * and one-click mute. All changes apply immediately on the local client and persist to
 * {@code habitrain_core-client.json} without affecting the server or other players.</p>
 */
final class ReplayAudioSettingsOverlay {
    /**
     * Explicit Z elevation for replay modal overlays: clears base-layer widgets,
     * drop-shadow text ({@code Font.SHADOW_OFFSET} = +0.03), and rendered items (+150),
     * while staying below vanilla tooltips (+400).
     */
    static final float OVERLAY_Z = 250.0f;

    private static final int SLIDER_NONE = 0;
    private static final int SLIDER_MASTER = 1;
    private static final int SLIDER_VOICE = 2;
    private static final int SLIDER_SFX = 3;

    private static final int COLOR_VOICE_ACTIVE = 0xFF52C5B9;
    private static final int COLOR_VOICE_DIM = 0xFF2B4E4B;
    private static final int COLOR_SFX_DIM = 0xFF5A4C2C;
    private static final int COLOR_MASTER_DIM = 0xFF5E2B34;

    private boolean open;
    private int activeSlider = SLIDER_NONE;

    private int scrimDrawX, scrimDrawY, scrimDrawW, scrimDrawH;
    private int boxX, boxY, boxW, boxH;
    private int trackX, trackW;
    private int masterY, voiceY, sfxY;
    private int closeX, closeY, closeW, closeH;
    private int btnY, btnH;
    private int muteX, muteW, resetX, resetW, backX, backW;

    boolean isOpen() {
        return open;
    }

    void open() {
        open = true;
        activeSlider = SLIDER_NONE;
    }

    void toggle() {
        if (open) close();
        else open();
    }

    void close() {
        flushPendingDrag();
        open = false;
    }

    void onRemoved() {
        flushPendingDrag();
        open = false;
    }

    private void flushPendingDrag() {
        if (activeSlider != SLIDER_NONE) {
            activeSlider = SLIDER_NONE;
            ClientVisualPreferences.setReplayAudioSettings(ClientVisualPreferences.getReplayAudioSettings());
        }
    }

    // ------------------------------------------------------------------ Shared label & math helpers

    static float snapRatioToVolume(double ratio) {
        double clamped = Math.clamp(Double.isFinite(ratio) ? ratio : 1.0, 0.0, 1.0);
        return (float) (Math.round(clamped * 100.0) / 100.0);
    }

    static float stepVolume(float current, double scrollY) {
        if (!Double.isFinite(scrollY) || scrollY == 0.0) {
            return ReplayAudioSettings.clampUnit(current, ReplayAudioSettings.DEFAULT_VOLUME);
        }
        float delta = scrollY > 0.0 ? 0.05f : -0.05f;
        return snapRatioToVolume(ReplayAudioSettings.clampUnit(current, ReplayAudioSettings.DEFAULT_VOLUME) + delta);
    }

    static Component buttonLabel(ReplayAudioSettings settings) {
        ReplayAudioSettings s = settings == null ? ReplayAudioSettings.DEFAULT : settings;
        if (s.isEffectivelyMuted()) {
            return ReplayBoardTexts.tr("audio.button_muted");
        }
        return ReplayBoardTexts.tr("audio.button_pct", ReplayAudioSettings.toPercent(s.masterVolume()));
    }

    static Component deckButtonLabel(ReplayAudioSettings settings) {
        ReplayAudioSettings s = settings == null ? ReplayAudioSettings.DEFAULT : settings;
        if (s.isEffectivelyMuted()) {
            return ReplayBoardTexts.tr("deck.volume_muted");
        }
        return ReplayBoardTexts.tr("deck.volume_pct", ReplayAudioSettings.toPercent(s.masterVolume()));
    }

    static Component buttonTooltip(ReplayAudioSettings settings, boolean for3dDeck) {
        ReplayAudioSettings s = settings == null ? ReplayAudioSettings.DEFAULT : settings;
        if (s.isEffectivelyMuted()) {
            return ReplayBoardTexts.tr(for3dDeck ? "deck.volume_tip_muted" : "audio.button_tip_muted");
        }
        int master = ReplayAudioSettings.toPercent(s.masterVolume());
        int voice = ReplayAudioSettings.toPercent(s.voiceVolume());
        int sfx = ReplayAudioSettings.toPercent(s.sfxVolume());
        return ReplayBoardTexts.tr(for3dDeck ? "deck.volume_tip" : "audio.button_tip", master, voice, sfx);
    }

    // ------------------------------------------------------------------ Layout & rendering

    void updateLayout(int scrimX, int scrimY, int scrimW, int scrimH, int screenW, int screenH) {
        boxH = 190;
        boolean fitsScrim = scrimH >= boxH + 8 && scrimW >= 256;
        scrimDrawX = fitsScrim ? scrimX : 0;
        scrimDrawY = fitsScrim ? scrimY : 0;
        scrimDrawW = fitsScrim ? scrimW : screenW;
        scrimDrawH = fitsScrim ? scrimH : screenH;

        boxW = Math.min(340, Math.max(244, scrimDrawW - 18));
        boxX = scrimDrawX + (scrimDrawW - boxW) / 2;
        boxY = Math.max(4, scrimDrawY + (scrimDrawH - boxH) / 2);

        closeW = 16;
        closeH = 14;
        closeX = boxX + boxW - 21;
        closeY = boxY + 5;

        trackX = boxX + 16;
        trackW = Math.max(80, boxW - 32);
        masterY = boxY + 55;
        voiceY = masterY + 34;
        sfxY = voiceY + 34;

        btnH = 20;
        btnY = boxY + boxH - 29;
        int gap = 6;
        int innerW = boxW - 28;
        int colW = (innerW - gap * 2) / 3;
        muteX = boxX + 14;
        muteW = colW;
        resetX = muteX + muteW + gap;
        resetW = colW;
        backX = resetX + resetW + gap;
        backW = Math.max(40, (boxX + boxW - 14) - backX);
    }

    void render(GuiGraphics g, Font font, int mouseX, int mouseY,
                int scrimX, int scrimY, int scrimW, int scrimH, int screenW, int screenH) {
        if (!open) return;
        updateLayout(scrimX, scrimY, scrimW, scrimH, screenW, screenH);
        ReplayAudioSettings s = ClientVisualPreferences.getReplayAudioSettings();

        // Base-layer drop-shadow text writes depth at z = +0.03 and may remain batched in a
        // separate RenderType from RenderType.gui(). Flush the host layer first, then render
        // the entire modal on an elevated Z plane so underlying text cannot bleed through.
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, OVERLAY_Z);
        try {
            // Dimmed scrim over host surface
            g.fill(scrimDrawX, scrimDrawY, scrimDrawX + scrimDrawW, scrimDrawY + scrimDrawH, 0xA8040710);

            // Star-Night Express modal card with double brass frame & corner accents
            g.fillGradient(boxX, boxY, boxX + boxW, boxY + boxH, 0xFC111A33, 0xFC04060D);
            g.renderOutline(boxX, boxY, boxW, boxH, ReplayPalette.BRASS);
            g.renderOutline(boxX + 2, boxY + 2, boxW - 4, boxH - 4, 0x778A6A35);
            drawCornerAccent(g, boxX + 1, boxY + 1, 1, 1);
            drawCornerAccent(g, boxX + boxW - 2, boxY + 1, -1, 1);
            drawCornerAccent(g, boxX + 1, boxY + boxH - 2, 1, -1);
            drawCornerAccent(g, boxX + boxW - 2, boxY + boxH - 2, -1, -1);

            // Header title with flanking brass rules & diamonds
            int cx = boxX + boxW / 2;
            Component title = ReplayBoardTexts.tr("audio.title");
            int titleW = font.width(title);
            int ruleY = boxY + 13;
            int lx1 = cx - titleW / 2 - 10;
            int rx0 = cx + titleW / 2 + 10;
            if (lx1 > boxX + 16) {
                g.fill(boxX + 14, ruleY, lx1, ruleY + 1, ReplayPalette.BRASS_DIM);
                drawDiamond(g, lx1 + 4, ruleY, ReplayPalette.BRASS_LIGHT);
            }
            if (closeX - 8 > rx0 + 4) {
                g.fill(rx0, ruleY, closeX - 6, ruleY + 1, ReplayPalette.BRASS_DIM);
                drawDiamond(g, rx0 - 4, ruleY, ReplayPalette.BRASS_LIGHT);
            }
            g.drawCenteredString(font, title, cx, boxY + 9, ReplayPalette.BRASS_LIGHT);

            // Top-right close button
            boolean closeHover = inRect(mouseX, mouseY, closeX, closeY, closeW, closeH);
            g.fill(closeX, closeY, closeX + closeW, closeY + closeH, closeHover ? 0xFF4E1E24 : 0xFF18233F);
            g.renderOutline(closeX, closeY, closeW, closeH, closeHover ? ReplayPalette.RUBY : ReplayPalette.BRASS_DIM);
            g.drawCenteredString(font, "×", closeX + closeW / 2, closeY + 3, closeHover ? 0xFFFFFFFF : ReplayPalette.TEXT);

            // Local-only caption + hairline divider
            Component subtitle = ReplayBoardTexts.tr("audio.local_note");
            g.drawCenteredString(font, font.plainSubstrByWidth(subtitle.getString(), boxW - 24),
                    cx, boxY + 23, ReplayPalette.TEXT_MUTED);
            g.fill(boxX + 14, boxY + 36, boxX + boxW - 14, boxY + 37, ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.55));

            boolean muted = s.muted();

            // Slider 1: Master volume
            renderSliderRow(g, font, mouseX, mouseY, masterY,
                    ReplayBoardTexts.tr("audio.master"), s.masterVolume(),
                    muted, activeSlider == SLIDER_MASTER,
                    muted ? COLOR_MASTER_DIM : ReplayPalette.BRASS,
                    muted ? ReplayBoardTexts.tr("audio.value_muted_pct", ReplayAudioSettings.toPercent(s.masterVolume()))
                            : formatPercentValue(s.masterVolume()),
                    muted ? ReplayPalette.RUBY : (s.masterVolume() <= ReplayAudioSettings.SILENT_EPSILON
                            ? ReplayPalette.TEXT_MUTED : ReplayPalette.CHAMPAGNE));

            // Slider 2: Player voice volume
            boolean voiceDim = muted || s.masterVolume() <= ReplayAudioSettings.SILENT_EPSILON;
            renderSliderRow(g, font, mouseX, mouseY, voiceY,
                    ReplayBoardTexts.tr("audio.voice"), s.voiceVolume(),
                    voiceDim, activeSlider == SLIDER_VOICE,
                    voiceDim ? COLOR_VOICE_DIM : COLOR_VOICE_ACTIVE,
                    formatPercentValue(s.voiceVolume()),
                    voiceDim || s.voiceVolume() <= ReplayAudioSettings.SILENT_EPSILON
                            ? ReplayPalette.TEXT_MUTED : COLOR_VOICE_ACTIVE);

            // Slider 3: Game sounds volume
            boolean sfxDim = muted || s.masterVolume() <= ReplayAudioSettings.SILENT_EPSILON;
            renderSliderRow(g, font, mouseX, mouseY, sfxY,
                    ReplayBoardTexts.tr("audio.sfx"), s.sfxVolume(),
                    sfxDim, activeSlider == SLIDER_SFX,
                    sfxDim ? COLOR_SFX_DIM : ReplayPalette.BRASS_LIGHT,
                    formatPercentValue(s.sfxVolume()),
                    sfxDim || s.sfxVolume() <= ReplayAudioSettings.SILENT_EPSILON
                            ? ReplayPalette.TEXT_MUTED : ReplayPalette.BRASS_LIGHT);

            // Effective output summary line
            Component summary = s.isEffectivelyMuted()
                    ? ReplayBoardTexts.tr("audio.effective_muted")
                    : ReplayBoardTexts.tr("audio.effective_summary",
                            ReplayAudioSettings.toPercent(s.effectiveVoiceScale()),
                            ReplayAudioSettings.toPercent(s.effectiveSfxScale()));
            g.drawCenteredString(font, font.plainSubstrByWidth(summary.getString(), boxW - 28),
                    cx, boxY + 143, s.isEffectivelyMuted() ? ReplayPalette.RUBY : ReplayPalette.TEXT_MUTED);

            // Bottom action buttons: Mute/Unmute | Reset | Back
            boolean effMuted = s.isEffectivelyMuted();
            renderActionButton(g, font, muteX, btnY, muteW, btnH,
                    ReplayBoardTexts.tr(effMuted ? "audio.unmute" : "audio.mute"),
                    true, effMuted, false, inRect(mouseX, mouseY, muteX, btnY, muteW, btnH));
            renderActionButton(g, font, resetX, btnY, resetW, btnH,
                    ReplayBoardTexts.tr("audio.reset"),
                    !s.isDefault(), false, false, !s.isDefault() && inRect(mouseX, mouseY, resetX, btnY, resetW, btnH));
            renderActionButton(g, font, backX, btnY, backW, btnH,
                    CommonComponents.GUI_BACK,
                    true, false, true, inRect(mouseX, mouseY, backX, btnY, backW, btnH));
        } finally {
            // Flush while the elevated pose is still active so buffered overlay text keeps OVERLAY_Z.
            g.flush();
            g.pose().popPose();
        }
    }

    private void renderSliderRow(GuiGraphics g, Font font, int mouseX, int mouseY, int y,
                                 Component label, float value, boolean dimmed, boolean dragging,
                                 int fillColor, Component valueText, int valueColor) {
        boolean hover = !dimmed && inSliderHit(mouseX, mouseY, y);
        g.drawString(font, label, trackX, y - 12, dimmed ? ReplayPalette.TEXT_MUTED : ReplayPalette.TEXT, false);
        int valW = font.width(valueText);
        g.drawString(font, valueText, trackX + trackW - valW, y - 12, valueColor, false);

        double r = ReplayAudioSettings.clampUnit(value, ReplayAudioSettings.DEFAULT_VOLUME);
        int thumbX = trackX + (int) Math.round(r * trackW);
        g.fill(trackX, y + 2, trackX + trackW, y + 6, 0xFF141C30);
        g.renderOutline(trackX - 1, y + 1, trackW + 2, 6,
                dragging || hover ? ReplayPalette.BRASS : 0x808A6A35);
        if (thumbX > trackX) {
            g.fill(trackX, y + 2, thumbX, y + 6, fillColor);
        }
        // Subtle 50% midpoint tick
        g.fill(trackX + trackW / 2, y, trackX + trackW / 2 + 1, y + 8, 0x66E5CA8A);
        int thumbColor = dragging ? 0xFFFFFFFF
                : (dimmed ? ReplayPalette.TEXT_MUTED : (hover ? ReplayPalette.CHAMPAGNE : ReplayPalette.BRASS_LIGHT));
        drawDiamond(g, thumbX, y + 4, thumbColor);
    }

    private static Component formatPercentValue(float volume) {
        int pct = ReplayAudioSettings.toPercent(volume);
        if (pct <= 0) {
            return ReplayBoardTexts.tr("audio.value_off");
        }
        return Component.literal(pct + "%");
    }

    private static void renderActionButton(GuiGraphics g, Font font, int x, int y, int w, int h,
                                           Component label, boolean active, boolean danger, boolean gold, boolean hovered) {
        int border, topBg, botBg, textCol;
        if (!active) {
            border = 0xFF3E3830;
            topBg = 0xAA0C1120;
            botBg = 0xAA070A14;
            textCol = ReplayPalette.TEXT_FAINT;
        } else if (danger) {
            border = hovered ? 0xFFFF969D : ReplayPalette.RUBY;
            topBg = hovered ? 0xEE641F2B : 0xDD42141E;
            botBg = hovered ? 0xEE3C1018 : 0xDD270B11;
            textCol = hovered ? 0xFFFFEAEC : 0xFFFFC4C9;
        } else if (gold) {
            border = hovered ? ReplayPalette.CHAMPAGNE : ReplayPalette.BRASS_LIGHT;
            topBg = hovered ? 0xEE46371A : 0xDD332713;
            botBg = hovered ? 0xEE2B2110 : 0xDD1F180B;
            textCol = ReplayPalette.CHAMPAGNE;
        } else {
            border = hovered ? ReplayPalette.BRASS_LIGHT : ReplayPalette.BRASS_DIM;
            topBg = hovered ? 0xEE223254 : 0xDD131D36;
            botBg = hovered ? 0xEE15203A : 0xDD0A1022;
            textCol = hovered ? ReplayPalette.CHAMPAGNE : ReplayPalette.TEXT;
        }
        g.fillGradient(x, y, x + w, y + h, topBg, botBg);
        g.renderOutline(x, y, w, h, border);
        String fit = font.plainSubstrByWidth(label.getString(), Math.max(8, w - 6));
        g.drawCenteredString(font, fit, x + w / 2, y + (h - 8) / 2, textCol);
    }

    // ------------------------------------------------------------------ Input handling

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) return false;
        if (!inRect(mouseX, mouseY, boxX, boxY, boxW, boxH)) {
            close();
            return true;
        }
        if (button != 0) return true;

        if (inRect(mouseX, mouseY, closeX, closeY, closeW, closeH)
                || inRect(mouseX, mouseY, backX, btnY, backW, btnH)) {
            playUiClick();
            close();
            return true;
        }
        if (inRect(mouseX, mouseY, muteX, btnY, muteW, btnH)) {
            ClientVisualPreferences.toggleReplayAudioMute();
            playUiClick();
            return true;
        }
        if (inRect(mouseX, mouseY, resetX, btnY, resetW, btnH)) {
            if (!ClientVisualPreferences.getReplayAudioSettings().isDefault()) {
                ClientVisualPreferences.resetReplayAudioSettings();
                playUiClick();
            }
            return true;
        }
        if (inSliderHit(mouseX, mouseY, masterY)) {
            activeSlider = SLIDER_MASTER;
            applySliderDrag(mouseX, false);
            return true;
        }
        if (inSliderHit(mouseX, mouseY, voiceY)) {
            activeSlider = SLIDER_VOICE;
            applySliderDrag(mouseX, false);
            return true;
        }
        if (inSliderHit(mouseX, mouseY, sfxY)) {
            activeSlider = SLIDER_SFX;
            applySliderDrag(mouseX, false);
            return true;
        }
        return true;
    }

    boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!open) return false;
        if (button == 0 && activeSlider != SLIDER_NONE) {
            applySliderDrag(mouseX, false);
        }
        return true;
    }

    boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!open) return false;
        if (button == 0 && activeSlider != SLIDER_NONE) {
            applySliderDrag(mouseX, true);
            activeSlider = SLIDER_NONE;
            playUiClick();
        }
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!open) return false;
        if (scrollY == 0.0) return true;
        ReplayAudioSettings cur = ClientVisualPreferences.getReplayAudioSettings();
        ReplayAudioSettings next = null;
        if (inSliderHit(mouseX, mouseY, masterY)) {
            next = cur.withMasterVolume(stepVolume(cur.masterVolume(), scrollY));
        } else if (inSliderHit(mouseX, mouseY, voiceY)) {
            next = cur.withVoiceVolume(stepVolume(cur.voiceVolume(), scrollY));
        } else if (inSliderHit(mouseX, mouseY, sfxY)) {
            next = cur.withSfxVolume(stepVolume(cur.sfxVolume(), scrollY));
        }
        if (next != null && !next.equals(cur)) {
            ClientVisualPreferences.setReplayAudioSettings(next);
        }
        return true;
    }

    boolean keyPressed(int keyCode) {
        if (!open) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_M) {
            ClientVisualPreferences.toggleReplayAudioMute();
            playUiClick();
            return true;
        }
        return false;
    }

    private void applySliderDrag(double mouseX, boolean persist) {
        double ratio = (mouseX - trackX) / (double) Math.max(1, trackW);
        float vol = snapRatioToVolume(ratio);
        ReplayAudioSettings cur = ClientVisualPreferences.getReplayAudioSettings();
        ReplayAudioSettings next = switch (activeSlider) {
            case SLIDER_MASTER -> cur.withMasterVolume(vol);
            case SLIDER_VOICE -> cur.withVoiceVolume(vol);
            case SLIDER_SFX -> cur.withSfxVolume(vol);
            default -> cur;
        };
        if (persist) {
            ClientVisualPreferences.setReplayAudioSettings(next);
        } else {
            ClientVisualPreferences.previewReplayAudioSettings(next);
        }
    }

    private boolean inSliderHit(double mx, double my, int sliderY) {
        return mx >= trackX - 6 && mx <= trackX + trackW + 6 && Math.abs(my - (sliderY + 4)) <= 10;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void drawCornerAccent(GuiGraphics g, int x, int y, int dx, int dy) {
        for (int i = 0; i < 4; i++) {
            g.fill(x + dx * i, y + dy * (3 - i), x + dx * i + 1, y + dy * (3 - i) + 1, ReplayPalette.BRASS_LIGHT);
        }
    }

    private static void drawDiamond(GuiGraphics g, int cx, int cy, int color) {
        g.fill(cx, cy - 2, cx + 1, cy + 3, color);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
        g.fill(cx - 2, cy, cx + 3, cy + 1, color);
    }

    private static void playUiClick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
