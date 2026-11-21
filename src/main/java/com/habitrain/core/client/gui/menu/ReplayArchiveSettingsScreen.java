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

final class ReplayArchiveSettingsScreen extends Screen {
    private static final long GIB = 1L << 30;
    private final Screen parent;
    private Settings shown, sentAgainst;
    private VoiceRecordingSettings voiceShown, voiceSentAgainst;
    private EditBox limit;
    private Button save, copy, refresh, voiceRecording;
    private long sentAt, voiceSentAt;
    private boolean refreshOnOpen;
    private boolean limitConflict;
    private String draft = "";
    private int left, panelWidth, top;

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
        panelWidth = Math.max(1, Math.min(width - 20, 440));
        left = (width - panelWidth) / 2;
        top = Math.max(8, (height - 190) / 2);
        int w = (panelWidth - 12) / 3;
        refresh = addRenderableWidget(Button.builder(tr("refresh"), b -> submit(0))
                .bounds(left, top + 148, w, 20).build());
        copy = addRenderableWidget(Button.builder(tr("copy"), b -> minecraft.keyboardHandler.setClipboard(shown.path()))
                .bounds(left + w + 6, top + 148, w, 20).build());
        copy.setTooltip(Tooltip.create(tr("copy_tip")));
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose())
                .bounds(left + w * 2 + 12, top + 148, w, 20).build());
        limit = addRenderableWidget(new EditBox(font, left, top + 84, panelWidth - w - 6, 20, tr("limit")));
        limit.setMaxLength(15);
        limit.setFilter(s -> s.matches("[0-9]{0,4}([.,][0-9]{0,10})?"));
        limit.setValue(draft);
        limit.setResponder(s -> draft = s);
        save = addRenderableWidget(Button.builder(tr("save"), b -> submit(bytes(draft)))
                .bounds(left + panelWidth - w, top + 84, w, 20).build());
        voiceRecording = addRenderableWidget(Button.builder(tr("voice_loading"), b -> {
            if (voiceShown == null || !ReplayHistoryClient.setVoiceRecording(!voiceShown.enabled())) return;
            voiceSentAgainst = voiceShown;
            voiceSentAt = System.nanoTime();
            tick();
        }).bounds(left, top + 122, panelWidth - w - 6, 20).build());
        var clearCache = addRenderableWidget(Button.builder(tr("clear_local"), b -> ReplayHistoryClient.clearDiskCache())
                .bounds(left + panelWidth - w, top + 122, w, 20).build());
        clearCache.setTooltip(Tooltip.create(tr("local_policy")));
        voiceRecording.setTooltip(Tooltip.create(tr("voice_tip")));
        setInitialFocus(limit);
        tick();
    }

    private static long bytes(String input) {
        try {
            long n = new BigDecimal(input.replace(',', '.')).multiply(BigDecimal.valueOf(GIB))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            return n >= (1L << 20) && n <= 1024L * GIB ? n : -1;
        } catch (ArithmeticException | NumberFormatException ignored) { return -1; }
    }

    private static String size(long value) {
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(GIB), 10, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
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
        else voiceKey = voiceShown.enabled() ? "voice_enabled" : "voice_disabled";
        voiceRecording.setMessage(tr(voiceKey));
        refresh.active = ReplayHistoryClient.canRequestSettings(0) && !pending() && !voicePending;
        copy.active = shown != null && !shown.path().isEmpty();
        limit.setEditable(shown != null && shown.editable() && !pending());
        long value = bytes(draft);
        save.active = shown != null && shown.editable() && !pending() && !voicePending && !limitConflict
                && value > 0 && value != shown.limit() && ReplayHistoryClient.canRequestSettings(value);
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fillGradient(0, 0, width, height, 0xF2182233, 0xF60B101A);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        g.drawCenteredString(font, title, width / 2, top, 0xFFF2D399);
        if (shown != null) {
            g.drawString(font, tr("usage", size(shown.used()), size(shown.limit())), left, top + 18, 0xFFEAEEF5);
            int fill = (int) (panelWidth * Math.clamp(shown.used() / (double) Math.max(1L, shown.limit()), 0, 1));
            g.fill(left, top + 31, left + panelWidth, top + 35, 0xFF323E51);
            g.fill(left, top + 31, left + fill, top + 35, 0xFF6DCEC0);
            g.drawString(font, tr("location"), left, top + 42, 0xFFB5C2D3);
            String path = shown.path();
            String visible = font.width(path) <= panelWidth ? path : "…" + font.plainSubstrByWidth(path, panelWidth - font.width("…"), true);
            g.drawString(font, visible, left, top + 54, 0xFFEAEEF5);
            if (mx >= left && mx < left + panelWidth && my >= top + 52 && my < top + 66 && !path.isEmpty()) {
                g.renderTooltip(font, Component.literal(path), mx, my);
            }
        }
        g.drawString(font, tr("limit"), left, top + 72, 0xFFEAEEF5);
        Component status;
        if (!ReplayHistoryClient.supported()) status = tr("connect");
        else if (pending() || shown == null && sentAt == 0) status = tr("loading");
        else if (limitConflict) status = tr("conflict");
        else if (shown == null || sentAt != 0 || !shown.ok()) status = tr("failed");
        else if (!shown.editable()) status = tr("admin");
        else if (bytes(draft) < 0) status = tr("range");
        else status = tr("policy");
        g.drawString(font, font.plainSubstrByWidth(status.getString(), panelWidth), left, top + 109, 0xFFB5C2D3);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
