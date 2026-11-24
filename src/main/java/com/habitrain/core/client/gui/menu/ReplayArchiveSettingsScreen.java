package com.habitrain.core.client.gui.menu;

import com.habitrain.core.network.replay.ReplayArchivePackets.Settings;
import com.habitrain.core.network.replay.ReplayArchivePackets.VoiceRecordingSettings;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Star-Night Express Art Deco settings screen for server replay archive quota,
 * global multi-track voice + game-sound recording policy, corrupted archive quarantine info,
 * and local client cache cleanup.
 */
final class ReplayArchiveSettingsScreen extends Screen {
    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;
    private static final long KIB = 1L << 10;
    private static final int PANEL_H = 214;
    private static final int CARD_TOP_OFF = 24;
    private static final int CARD_H = 62;

    // Star-Night Express palette (mirrors ReplayPalette / RailArt)
    private static final int NIGHT_0 = 0xF504060D;
    private static final int NIGHT_2 = 0xF2111A33;
    private static final int CARD_BG = 0xAA070B18;
    private static final int BRASS_DIM = 0xFF8A6A35;
    private static final int BRASS = 0xFFD0A658;
    private static final int BRASS_LIGHT = 0xFFF4D894;
    private static final int CHAMPAGNE = 0xFFFFF4DA;
    private static final int TEXT = 0xFFEEE8DA;
    private static final int TEXT_MUTED = 0xFF9EA7BE;
    private static final int TEAL = 0xFF6DCEC0;
    private static final int WARN = 0xFFFFB55E;
    private static final int RUBY = 0xFFFF5A64;

    private final Screen parent;
    private Settings shown, sentAgainst;
    private VoiceRecordingSettings voiceShown, voiceSentAgainst;
    private EditBox limit;
    private Button save, copy, refresh, voiceRecording, clearCache;
    private long sentAt, voiceSentAt;
    private boolean refreshOnOpen;
    private boolean limitConflict;
    private boolean clearedLocalHint;
    private String draft = "";
    private int left, panelWidth, top, panelHeight;

    ReplayArchiveSettingsScreen(Screen parent) {
        super(tr("title"));
        this.parent = parent;
        shown = ReplayHistoryClient.settings();
        voiceShown = ReplayHistoryClient.voiceSettings();
        if (shown != null) draft = size(shown.limit());
        refreshOnOpen = ReplayHistoryClient.supported();
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.habitrain_core.replay_storage." + key, args);
    }

    @Override protected void init() {
        panelWidth = Math.max(240, Math.min(width - 20, 468));
        panelHeight = PANEL_H;
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - panelHeight) / 2);
        int innerX = left + 14;
        int innerW = panelWidth - 28;
        int colW = (innerW - 12) / 3;
        int limitY = top + CARD_TOP_OFF + CARD_H + 17;
        int audioY = limitY + 48;
        int bottomY = audioY + 28;

        limit = addRenderableWidget(new EditBox(font, innerX, limitY, innerW - colW - 6, 20, tr("limit")));
        limit.setMaxLength(15);
        limit.setFilter(s -> s.matches("[0-9]{0,4}([.,][0-9]{0,10})?"));
        limit.setValue(draft);
        limit.setResponder(s -> draft = s);
        save = addRenderableWidget(Button.builder(tr("save"), b -> submit(bytes(draft)))
                .bounds(innerX + innerW - colW, limitY, colW, 20).build());

        voiceRecording = addRenderableWidget(Button.builder(tr("voice_loading"), b -> {
            if (voiceShown == null || !ReplayHistoryClient.setVoiceRecording(!voiceShown.enabled())) return;
            voiceSentAgainst = voiceShown;
            voiceSentAt = System.nanoTime();
            tick();
        }).bounds(innerX, audioY, innerW - colW - 6, 20).build());
        voiceRecording.setTooltip(Tooltip.create(tr("voice_tip_v2")));

        clearCache = addRenderableWidget(Button.builder(tr("clear_local"), b -> {
            ReplayHistoryClient.clearDiskCache();
            clearedLocalHint = true;
        }).bounds(innerX + innerW - colW, audioY, colW, 20).build());
        clearCache.setTooltip(Tooltip.create(tr("local_policy_v2")));

        refresh = addRenderableWidget(Button.builder(tr("refresh"), b -> {
            clearedLocalHint = false;
            submit(0);
        }).bounds(innerX, bottomY, colW, 20).build());
        copy = addRenderableWidget(Button.builder(tr("copy"), b -> {
            if (shown != null && !shown.path().isEmpty()) {
                minecraft.keyboardHandler.setClipboard(shown.path());
            }
        }).bounds(innerX + colW + 6, bottomY, colW, 20).build());
        copy.setTooltip(Tooltip.create(tr("copy_tip")));
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(innerX + (colW + 6) * 2, bottomY, innerW - (colW + 6) * 2, 20).build());

        setInitialFocus(limit);
        tick();
    }

    private static long bytes(String input) {
        try {
            long n = new BigDecimal(input.replace(',', '.')).multiply(BigDecimal.valueOf(GIB))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            return n >= MIB && n <= 1024L * GIB ? n : -1;
        } catch (ArithmeticException | NumberFormatException ignored) { return -1; }
    }

    private static String size(long value) {
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(GIB), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String formatHumanBytes(long value) {
        if (value >= GIB) return String.format(Locale.ROOT, "%.2f GiB", value / (double) GIB);
        if (value >= MIB) return String.format(Locale.ROOT, "%.1f MiB", value / (double) MIB);
        if (value >= KIB) return String.format(Locale.ROOT, "%.1f KiB", value / (double) KIB);
        return value + " B";
    }

    private void submit(long value) {
        if (value < 0 || value > 0 && (shown == null || limitConflict)
                || !ReplayHistoryClient.requestSettings(value, shown == null ? 0 : shown.limit())) return;
        sentAgainst = shown;
        sentAt = System.nanoTime();
    }

    private boolean pending() {
        return refreshOnOpen || sentAt != 0 && System.nanoTime() - sentAt < 5_000_000_000L && sentAgainst == shown;
    }

    @Override public void tick() {
        Settings incoming = ReplayHistoryClient.settings();
        if (shown != incoming) {
            if (incoming != null && (incoming.ok() || !incoming.path().isEmpty())) {
                boolean dirty = shown != null && !draft.equals(size(shown.limit()));
                if (!dirty || bytes(draft) == incoming.limit()) {
                    limit.setValue(size(incoming.limit()));
                    limitConflict = false;
                } else if (shown.limit() != incoming.limit()) {
                    limitConflict = true;
                }
            }
            shown = incoming;
            sentAt = 0;
        }
        VoiceRecordingSettings incomingVoice = ReplayHistoryClient.voiceSettings();
        if (voiceShown != incomingVoice) {
            voiceShown = incomingVoice;
            voiceSentAt = 0;
        }
        if (refreshOnOpen && ReplayHistoryClient.canRequestSettings(0)) {
            refreshOnOpen = false;
            submit(0);
        }
        boolean voicePending = voiceSentAt != 0 && System.nanoTime() - voiceSentAt < 5_000_000_000L
                && voiceSentAgainst == voiceShown;
        boolean voiceSupported = ReplayHistoryClient.canSetVoiceRecording();
        voiceRecording.active = ReplayHistoryClient.canRequestVoiceRecording() && voiceShown != null
                && voiceShown.editable() && !voicePending && !pending();
        String voiceKey;
        if (!voiceSupported) voiceKey = "voice_unavailable";
        else if (voicePending || pending()) voiceKey = "voice_loading";
        else if (voiceShown == null || voiceSentAt != 0 || !voiceShown.ok()) voiceKey = "voice_failed";
        else voiceKey = voiceShown.enabled() ? "voice_enabled_v2" : "voice_disabled_v2";
        voiceRecording.setMessage(tr(voiceKey));
        refresh.active = ReplayHistoryClient.canRequestSettings(0) && !pending() && !voicePending;
        copy.active = shown != null && !shown.path().isEmpty();
        limit.setEditable(shown != null && shown.editable() && !pending());
        long value = bytes(draft);
        save.active = shown != null && shown.editable() && !pending() && !voicePending && !limitConflict
                && value > 0 && value != shown.limit() && ReplayHistoryClient.canRequestSettings(value);
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0x90040810, 0xCC040810);
        g.fillGradient(left, top, left + panelWidth, top + panelHeight, NIGHT_2, NIGHT_0);
        g.renderOutline(left, top, panelWidth, panelHeight, BRASS);
        g.renderOutline(left + 3, top + 3, panelWidth - 6, panelHeight - 6, 0x888A6A35);
        // Art Deco corner chamfers
        drawCornerAccent(g, left + 1, top + 1, 1, 1);
        drawCornerAccent(g, left + panelWidth - 2, top + 1, -1, 1);
        drawCornerAccent(g, left + 1, top + panelHeight - 2, 1, -1);
        drawCornerAccent(g, left + panelWidth - 2, top + panelHeight - 2, -1, -1);
    }

    private static void drawCornerAccent(GuiGraphics g, int x, int y, int dx, int dy) {
        for (int i = 0; i < 5; i++) {
            g.fill(x + dx * i, y + dy * (4 - i), x + dx * i + 1, y + dy * (4 - i) + 1, BRASS_LIGHT);
        }
    }

    private static void drawDiamond(GuiGraphics g, int cx, int cy, int color) {
        g.fill(cx, cy - 2, cx + 1, cy + 3, color);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
        g.fill(cx - 2, cy, cx + 3, cy + 1, color);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        int innerX = left + 14;
        int innerW = panelWidth - 28;
        int cx = left + panelWidth / 2;
        int titleW = font.width(title);
        int ruleY = top + 14;
        int lx1 = cx - titleW / 2 - 12;
        int rx0 = cx + titleW / 2 + 12;
        if (lx1 > innerX + 6) {
            g.fill(innerX, ruleY, lx1, ruleY + 1, BRASS_DIM);
            drawDiamond(g, lx1 + 4, ruleY, BRASS_LIGHT);
        }
        if (innerX + innerW > rx0 + 6) {
            g.fill(rx0, ruleY, innerX + innerW, ruleY + 1, BRASS_DIM);
            drawDiamond(g, rx0 - 4, ruleY, BRASS_LIGHT);
        }
        g.drawCenteredString(font, title, cx, top + 10, BRASS_LIGHT);

        // Quota & Quarantine Card
        int cardTop = top + CARD_TOP_OFF;
        int cardH = CARD_H;
        g.fill(innerX, cardTop, innerX + innerW, cardTop + cardH, CARD_BG);
        g.renderOutline(innerX, cardTop, innerW, cardH, 0x778A6A35);

        int padX = innerX + 8;
        int contentW = innerW - 16;
        if (shown != null) {
            long totalLimit = Math.max(1L, shown.limit());
            double ratio = Math.clamp(shown.used() / (double) totalLimit, 0.0, 1.0);
            int pct = (int) Math.round(ratio * 100.0);
            Component usageText = tr("usage_pct", size(shown.used()), size(shown.limit()), pct);
            g.drawString(font, usageText, padX, cardTop + 5, TEXT, false);

            Component countText = tr("round_count", shown.roundCount());
            int countW = font.width(countText);
            g.drawString(font, countText, padX + contentW - countW, cardTop + 5, CHAMPAGNE, false);

            // Segmented quota bar: healthy bytes + quarantined bytes
            int barY = cardTop + 17;
            int barH = 6;
            g.fill(padX, barY, padX + contentW, barY + barH, 0xFF1A2748);
            g.renderOutline(padX - 1, barY - 1, contentW + 2, barH + 2, 0x708A6A35);
            long qBytes = Math.max(0L, Math.min(shown.used(), shown.quarantinedBytes()));
            long healthyBytes = Math.max(0L, shown.used() - qBytes);
            int healthyW = (int) Math.round(contentW * Math.clamp(healthyBytes / (double) totalLimit, 0.0, 1.0));
            int totalFillW = (int) Math.round(contentW * ratio);
            int healthyColor = ratio >= 0.90 ? WARN : TEAL;
            if (healthyW > 0) {
                g.fill(padX, barY, padX + healthyW, barY + barH, healthyColor);
            }
            if (totalFillW > healthyW) {
                g.fill(padX + healthyW, barY, padX + totalFillW, barY + barH, RUBY);
            }

            // Quarantine status row
            int qY = cardTop + 29;
            if (shown.quarantinedCount() > 0) {
                Component qText = tr("quarantined_warn", shown.quarantinedCount(), formatHumanBytes(shown.quarantinedBytes()));
                g.drawString(font, font.plainSubstrByWidth(qText.getString(), contentW), padX, qY, RUBY, false);
                if (mx >= padX && mx < padX + contentW && my >= qY - 1 && my < qY + 11) {
                    g.renderTooltip(font, tr("quarantined_tip"), mx, my);
                }
            } else {
                Component healthy = tr("quarantined_clean");
                g.drawString(font, font.plainSubstrByWidth(healthy.getString(), contentW), padX, qY, TEAL, false);
            }

            // Server directory path row
            int locY = cardTop + 44;
            Component locPrefix = tr("location_inline");
            int prefixW = font.width(locPrefix) + 4;
            g.drawString(font, locPrefix, padX, locY, TEXT_MUTED, false);
            String path = shown.path();
            int availPathW = Math.max(24, contentW - prefixW);
            String visible = font.width(path) <= availPathW
                    ? path : "…" + font.plainSubstrByWidth(path, Math.max(8, availPathW - font.width("…")), true);
            g.drawString(font, visible, padX + prefixW, locY, TEXT, false);
            if (mx >= padX && mx < padX + contentW && my >= locY - 1 && my < locY + 12 && !path.isEmpty()) {
                g.renderTooltip(font, Component.literal(path), mx, my);
            }
        } else {
            g.drawCenteredString(font, tr(ReplayHistoryClient.supported() ? "loading" : "connect"),
                    cx, cardTop + cardH / 2 - 4, TEXT_MUTED);
        }

        // Limit section label & status
        int limitY = limit.getY();
        g.drawString(font, tr("limit"), innerX, limitY - 11, BRASS_LIGHT, false);

        Component status;
        int statusColor = TEXT_MUTED;
        if (!ReplayHistoryClient.supported()) status = tr("connect");
        else if (pending() || shown == null && sentAt == 0) status = tr("loading");
        else if (limitConflict) { status = tr("conflict"); statusColor = WARN; }
        else if (shown == null || sentAt != 0 || !shown.ok()) { status = tr("failed"); statusColor = RUBY; }
        else if (!shown.editable()) status = tr("admin");
        else if (bytes(draft) < 0) { status = tr("range"); statusColor = WARN; }
        else if (clearedLocalHint) { status = tr("local_cleared"); statusColor = TEAL; }
        else status = tr("policy");
        g.drawString(font, font.plainSubstrByWidth(status.getString(), innerW), innerX, limitY + 24, statusColor, false);

        // Audio section caption
        g.drawString(font, tr("audio_section"), innerX, voiceRecording.getY() - 10, BRASS_LIGHT, false);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
