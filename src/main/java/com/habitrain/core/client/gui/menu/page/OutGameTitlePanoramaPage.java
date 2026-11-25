package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import com.habitrain.core.client.gui.menu.ui.SliderRow;
import com.habitrain.core.client.title.HabiBootAudioEngine;
import com.habitrain.core.client.title.HabiBootSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** 游戏外 · 主菜单：平面立体启动/主页 UI 与旧版全景背景偏好（仅本机生效）。 */
public final class OutGameTitlePanoramaPage implements ConfigPage {
    private static final int PAD = 12;
    private static final ResourceLocation PREVIEW = HabiTrainCore.id(
            "textures/gui/title/panorama/panorama_1.png");

    private final Font font;
    private final SliderRow bgmVolumeSlider = new SliderRow(0.0f, 1.0f, 0.05f);
    private boolean kineticUiEnabled;
    private boolean kineticSoundEnabled;
    private float kineticBgmVolume;
    private boolean kineticAutoEnter;
    private boolean panoramaEnabled;
    private boolean saveFailed;
    private Hit kineticHit = Hit.EMPTY;
    private Hit soundHit = Hit.EMPTY;
    private Hit autoEnterHit = Hit.EMPTY;
    private Hit panoramaHit = Hit.EMPTY;

    private record Hit(int x, int y, int w, int h) {
        private static final Hit EMPTY = new Hit(0, 0, 0, 0);
    }

    public OutGameTitlePanoramaPage(Font font) {
        this.font = font;
        this.kineticUiEnabled = ClientVisualPreferences.isKineticBootUiEnabled();
        this.kineticSoundEnabled = ClientVisualPreferences.isKineticUiSoundEnabled();
        this.kineticBgmVolume = ClientVisualPreferences.getKineticUiBgmVolume();
        this.kineticAutoEnter = ClientVisualPreferences.isKineticUiAutoEnter();
        this.panoramaEnabled = ClientVisualPreferences.isCustomTitlePanoramaEnabled();
    }

    /** These client-local options save immediately and do not use the server save bar. */
    @Override public boolean canSave() { return false; }
    @Override public void save() {}
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        int cardX = x + PAD;
        int cardW = Math.max(1, w - PAD * 2);

        // ── Card 1: Kinetic Flat 3D v3 Boot & Main Menu UI ──────────────────
        int kCardY = y + PAD;
        int kCardH = Math.min(164, Math.max(128, h / 2 + 12));
        MenuTheme.panel(g, cardX, kCardY, cardW, kCardH);
        g.fill(cardX, kCardY, cardX + 2, kCardY + kCardH,
                kineticUiEnabled ? MenuTheme.ACCENT_MINT : MenuTheme.ACCENT_BLUE);

        Component kTitle = Component.translatable("screen.habitrain_core.kinetic_ui.title");
        Component kDesc = Component.translatable("screen.habitrain_core.kinetic_ui.description");
        g.drawString(font, kTitle, cardX + 12, kCardY + 9, MenuTheme.TEXT_PRIMARY, false);

        int textW = Math.max(20, cardW - 24);
        List<net.minecraft.util.FormattedCharSequence> kLines = font.split(kDesc, textW);
        int kMaxLines = kCardH < 150 ? 1 : 2;
        for (int i = 0; i < Math.min(kMaxLines, kLines.size()); i++) {
            g.drawString(font, kLines.get(i), cardX + 12, kCardY + 23 + i * 11, MenuTheme.TEXT_SECONDARY, false);
        }

        int btnH = 20;
        int volRowY = kCardY + kCardH - 21;
        int row2Y = volRowY - btnH - 8;
        int row1Y = row2Y - btnH - 6;
        int halfW = Math.max(80, (cardW - 30) / 2);

        PillToggle.render(g, font, cardX + 12, row1Y, cardW - 24, btnH, kineticUiEnabled,
                Component.translatable("screen.habitrain_core.kinetic_ui.enabled").getString(),
                Component.translatable("screen.habitrain_core.kinetic_ui.disabled").getString());
        kineticHit = new Hit(cardX + 12, row1Y, cardW - 24, btnH);

        PillToggle.render(g, font, cardX + 12, row2Y, halfW, btnH, kineticSoundEnabled,
                Component.translatable("screen.habitrain_core.kinetic_ui.sound_on").getString(),
                Component.translatable("screen.habitrain_core.kinetic_ui.sound_off").getString());
        soundHit = new Hit(cardX + 12, row2Y, halfW, btnH);

        int rightX = cardX + 18 + halfW;
        int rightW = Math.max(80, cardW - 24 - halfW - 6);
        PillToggle.render(g, font, rightX, row2Y, rightW, btnH, kineticAutoEnter,
                Component.translatable("screen.habitrain_core.kinetic_ui.auto_enter_on").getString(),
                Component.translatable("screen.habitrain_core.kinetic_ui.auto_enter_off").getString());
        autoEnterHit = new Hit(rightX, row2Y, rightW, btnH);

        Component volLabel = Component.translatable("screen.habitrain_core.kinetic_ui.bgm_volume");
        int volLabelW = font.width(volLabel) + 10;
        g.drawString(font, volLabel, cardX + 12, volRowY + 1, MenuTheme.TEXT_SECONDARY, false);
        int sliderX = cardX + 12 + volLabelW;
        int sliderW = Math.max(60, cardW - 24 - volLabelW - 38);
        bgmVolumeSlider.render(g, font, sliderX, volRowY, sliderW, kineticBgmVolume);

        // ── Card 2: Legacy Custom 3D Rotating Panorama Fallback ─────────────
        int pCardY = kCardY + kCardH + 10;
        int pCardH = Math.max(76, (y + h - PAD) - pCardY);
        if (pCardH < 64) {
            panoramaHit = Hit.EMPTY;
            return;
        }
        MenuTheme.panel(g, cardX, pCardY, cardW, pCardH);
        g.fill(cardX, pCardY, cardX + 2, pCardY + pCardH,
                panoramaEnabled ? MenuTheme.ACCENT_MINT : MenuTheme.ACCENT_BLUE);

        Component title = Component.translatable("screen.habitrain_core.title_panorama.title");
        Component description = Component.translatable("screen.habitrain_core.title_panorama.description");
        g.drawString(font, title, cardX + 12, pCardY + 9, MenuTheme.TEXT_PRIMARY, false);

        List<net.minecraft.util.FormattedCharSequence> lines = font.split(description, textW);
        int descriptionY = pCardY + 23;
        int maxDescriptionLines = pCardH < 130 ? 1 : 2;
        for (int i = 0; i < Math.min(maxDescriptionLines, lines.size()); i++) {
            g.drawString(font, lines.get(i), cardX + 12, descriptionY + i * 11, MenuTheme.TEXT_SECONDARY, false);
        }

        int toggleY = pCardY + pCardH - btnH - 10;
        int previewY = descriptionY + maxDescriptionLines * 11 + 5;
        int previewH = Math.max(0, toggleY - previewY - 6);
        if (previewH >= 24 && cardW >= 80) {
            int previewX = cardX + 12;
            int previewW = cardW - 24;
            g.blit(PREVIEW, previewX, previewY, previewW, previewH,
                    0, 224, 1024, 576, 1024, 1024);
            g.fill(previewX, previewY + previewH - 15, previewX + previewW, previewY + previewH, 0x85000000);
            g.drawString(font,
                    Component.translatable("screen.habitrain_core.title_panorama.preview"),
                    previewX + 6, previewY + previewH - 12, 0xFFF2F4F7, false);
        }

        int toggleW = Math.max(1, Math.min(210, cardW - 24));
        int toggleX = cardX + 12;
        PillToggle.render(g, font, toggleX, toggleY, toggleW, btnH, panoramaEnabled,
                Component.translatable("screen.habitrain_core.title_panorama.enabled").getString(),
                Component.translatable("screen.habitrain_core.title_panorama.disabled").getString());
        panoramaHit = new Hit(toggleX, toggleY, toggleW, btnH);

        if (saveFailed) {
            String error = Component.translatable("screen.habitrain_core.title_panorama.save_failed").getString();
            int errorX = Math.min(cardX + cardW - font.width(error) - 12, toggleX + toggleW + 10);
            if (errorX > toggleX + toggleW) {
                g.drawString(font, error, errorX, toggleY + 6, MenuTheme.DANGER, false);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (btn != 0) return false;
        if (MenuTheme.inBounds(mx, my, kineticHit.x(), kineticHit.y(), kineticHit.w(), kineticHit.h())) {
            boolean next = !kineticUiEnabled;
            saveFailed = !ClientVisualPreferences.setKineticBootUiEnabled(next);
            if (!saveFailed) {
                kineticUiEnabled = next;
                if (!next) HabiBootAudioEngine.getInstance().fadeOutAndStop();
            }
            MenuSounds.playClick();
            return true;
        }
        if (MenuTheme.inBounds(mx, my, soundHit.x(), soundHit.y(), soundHit.w(), soundHit.h())) {
            boolean next = !kineticSoundEnabled;
            saveFailed = !ClientVisualPreferences.setKineticUiSoundEnabled(next);
            if (!saveFailed) {
                kineticSoundEnabled = next;
                HabiBootAudioEngine audio = HabiBootAudioEngine.getInstance();
                if (!next) {
                    audio.stopImmediately();
                } else {
                    audio.syncMinecraftVolumes(Minecraft.getInstance());
                    HabiBootSession session = HabiBootSession.getInstance();
                    if (session.getPhase() == HabiBootSession.Phase.STATION_01) {
                        audio.startMenuDirect(session.getStationElapsed());
                    }
                }
            }
            MenuSounds.playClick();
            return true;
        }
        if (bgmVolumeSlider.mouseClicked(mx, my)) {
            float next = bgmVolumeSlider.valueFromMouse(mx);
            kineticBgmVolume = next;
            ClientVisualPreferences.previewKineticUiBgmVolume(next);
            HabiBootAudioEngine.getInstance().syncMinecraftVolumes(Minecraft.getInstance());
            return true;
        }
        if (MenuTheme.inBounds(mx, my, autoEnterHit.x(), autoEnterHit.y(), autoEnterHit.w(), autoEnterHit.h())) {
            boolean next = !kineticAutoEnter;
            saveFailed = !ClientVisualPreferences.setKineticUiAutoEnter(next);
            if (!saveFailed) kineticAutoEnter = next;
            MenuSounds.playClick();
            return true;
        }
        if (MenuTheme.inBounds(mx, my, panoramaHit.x(), panoramaHit.y(), panoramaHit.w(), panoramaHit.h())) {
            boolean next = !panoramaEnabled;
            saveFailed = !ClientVisualPreferences.setCustomTitlePanoramaEnabled(next);
            if (!saveFailed) panoramaEnabled = next;
            MenuSounds.playClick();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy,
                                int x, int y, int w, int h) {
        if (btn == 0 && bgmVolumeSlider.mouseDragged()) {
            float next = bgmVolumeSlider.valueFromMouse(mx);
            if (Math.abs(next - kineticBgmVolume) >= 0.001f) {
                kineticBgmVolume = next;
                ClientVisualPreferences.previewKineticUiBgmVolume(next);
                HabiBootAudioEngine.getInstance().syncMinecraftVolumes(Minecraft.getInstance());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (btn == 0 && bgmVolumeSlider.mouseReleased()) {
            saveFailed = !ClientVisualPreferences.setKineticUiBgmVolume(kineticBgmVolume);
            if (saveFailed) {
                kineticBgmVolume = ClientVisualPreferences.getKineticUiBgmVolume();
            }
            HabiBootAudioEngine.getInstance().syncMinecraftVolumes(Minecraft.getInstance());
            MenuSounds.playClick();
            return true;
        }
        return false;
    }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy,
                                            int x, int y, int w, int h) { return false; }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (key == 257 || key == 335 || key == 32) {
            boolean next = !kineticUiEnabled;
            saveFailed = !ClientVisualPreferences.setKineticBootUiEnabled(next);
            if (!saveFailed) {
                kineticUiEnabled = next;
                if (!next) HabiBootAudioEngine.getInstance().fadeOutAndStop();
            }
            MenuSounds.playClick();
            return true;
        }
        return false;
    }

    @Override public boolean charTyped(char ch, int mod) { return false; }
}
