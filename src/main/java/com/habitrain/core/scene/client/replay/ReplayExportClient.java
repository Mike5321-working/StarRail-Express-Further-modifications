package com.habitrain.core.scene.client.replay;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.network.replay.ReplayPackets.RequestVoiceExportC2S;
import com.habitrain.core.network.replay.ReplayPackets.VoiceExportAccessS2C;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayMusicPlan;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Owns the local export and its HUD independently of any replay screen or table. */
public final class ReplayExportClient {
    private static ReplayVideoExport video;
    private static Supplier<ClientReplayBoard.View> voiceSource;
    private static long finishedAt;
    private static long nextAccessRequest;
    private static java.util.UUID pinnedRound;

    private ReplayExportClient() {}

    static boolean mapReady(ClientReplayBoard.View view) {
        return view != null && view.replay() != null && view.timeline() != null && view.asset() != null
                && view.totalSections() > 0 && view.presentSections() == view.totalSections();
    }

    static ReplayVideoExport current() { return video; }

    static ReplayVideoExport forScreen(ReplayVideoExport shown) {
        return video != null && (video == shown || video.running()) ? video : null;
    }

    static boolean canStart() {
        return video == null || (!video.running() && !video.encoderBusy());
    }

    static @Nullable ReplayVideoExport start(ClientReplayBoard.View view, boolean fullBody, ReplayExportResolution resolution,
                                             boolean compress, Supplier<ClientReplayBoard.View> source) {
        return start(view, fullBody, resolution, compress,
                ReplayExportQuality.fromId(ClientVisualPreferences.getReplayVideoQuality()),
                ReplayMusicPlan.Level.fromId(ClientVisualPreferences.getReplayVideoMusic()), null, source);
    }

    static @Nullable ReplayVideoExport start(ClientReplayBoard.View view, boolean fullBody, ReplayExportResolution resolution,
                                             boolean compress, ReplayMusicPlan.Level music, Supplier<ClientReplayBoard.View> source) {
        return start(view, fullBody, resolution, compress,
                ReplayExportQuality.fromId(ClientVisualPreferences.getReplayVideoQuality()), music, null, source);
    }

    static @Nullable ReplayVideoExport start(ClientReplayBoard.View view, boolean fullBody, ReplayExportResolution resolution,
                                             boolean compress, ReplayMusicPlan.Level music,
                                             @Nullable java.util.UUID focusPlayer, Supplier<ClientReplayBoard.View> source) {
        return start(view, fullBody, resolution, compress,
                ReplayExportQuality.fromId(ClientVisualPreferences.getReplayVideoQuality()), music, focusPlayer, source);
    }

    static @Nullable ReplayVideoExport start(ClientReplayBoard.View view, boolean fullBody, ReplayExportResolution resolution,
                                             boolean compress, ReplayExportQuality quality, ReplayMusicPlan.Level music,
                                             @Nullable java.util.UUID focusPlayer, Supplier<ClientReplayBoard.View> source) {
        if (!canStart() || !mapReady(view) || (!compress && !resolution.aviSupported())) return null;
        java.nio.file.Path ffmpeg = null;
        if (compress) {
            Minecraft mc = Minecraft.getInstance();
            java.nio.file.Path gameDir = mc != null ? mc.gameDirectory.toPath() : null;
            ffmpeg = ReplayFfmpegManager.resolveAvailable(gameDir);
            if (ffmpeg == null) return null;
        }
        releasePinnedRound();
        video = new ReplayVideoExport(view, fullBody, resolution, compress, quality, ffmpeg, music, focusPlayer);
        pinnedRound = view.replay().roundId();
        ReplayHistoryClient.pin(pinnedRound);
        voiceSource = view.voiceReady() ? null : source;
        nextAccessRequest = 0;
        if (!ClientPlayNetworking.canSend(RequestVoiceExportC2S.TYPE)) video.setVoiceExportAllowed(false);
        finishedAt = 0;
        return video;
    }

    public static void acceptVoiceExportAccess(VoiceExportAccessS2C reply) {
        if (video != null && video.running() && video.roundId().equals(reply.roundId())) {
            video.setVoiceExportAllowed(reply.allowed());
        }
    }

    static void onGameStarted(Minecraft client) {
        if (video != null && video.running() && isExportScreen(client.screen)) client.setScreen(null);
    }

    static void reset() {
        if (video != null) video.cancel();
        releasePinnedRound();
        video = null;
        voiceSource = null;
        finishedAt = 0;
    }

    private static void releasePinnedRound() {
        if (pinnedRound != null) ReplayHistoryClient.unpin(pinnedRound);
        pinnedRound = null;
    }

    static void shutdown() {
        ReplayFfmpegManager.cancelInstall();
        if (video != null) video.shutdown();
    }

    private static boolean isExportScreen(Screen screen) {
        return screen instanceof ReplayBoardControlsScreen || screen instanceof ReplayExportScreen;
    }

    private static boolean canCancelWithEscape(Minecraft client) {
        return client.level != null && client.player != null && client.getOverlay() == null
                && client.screen == null && client.isWindowActive() && client.mouseHandler.isMouseGrabbed();
    }

    public static boolean cancelInBackground(Minecraft client) {
        return video != null && video.running() && canCancelWithEscape(client) && video.cancel();
    }

    public static void renderFrame(Minecraft client) {
        if (video == null || client.level == null || client.player == null || client.getOverlay() != null) return;
        long now = System.nanoTime();
        if (video.waitingForVoiceAccess() && (nextAccessRequest == 0 || now - nextAccessRequest >= 0)
                && ClientPlayNetworking.canSend(RequestVoiceExportC2S.TYPE)) {
            ClientPlayNetworking.send(new RequestVoiceExportC2S(video.roundId()));
            nextAccessRequest = now + TimeUnit.SECONDS.toNanos(3);
        }
        if (video.running()) video.renderNext(client, voiceSource);
        if (!video.waitingForVoice()) voiceSource = null;

        if (!video.running() && finishedAt == 0) {
            video.releaseRenderResources();
            releasePinnedRound();
            finishedAt = now;
        }
        if (isExportScreen(client.screen) || (client.options.hideGui && client.screen == null)
                || (finishedAt != 0 && now - finishedAt >= TimeUnit.SECONDS.toNanos(6))) return;

        GuiGraphics g = new GuiGraphics(client, client.renderBuffers().bufferSource());
        int width = Math.min(180, g.guiWidth() - 16);
        if (width <= 16) return;
        int left = 12, right = left + width;
        int top = Math.max(8, g.guiHeight() * 2 / 3 - 24);
        var font = client.font;
        Component status = video.saved() != null && !video.voiceExportRestricted()
                ? ReplayBoardTexts.tr("export.hud_saved") : video.status();
        var lines = font.split(status, width - 16);
        int barY = top + 22 + lines.size() * font.lineHeight;
        boolean showCancelHint = video.running() && canCancelWithEscape(client);
        int bottom = barY + (showCancelHint ? 24 : 10);

        g.fill(left, top, right, bottom, 0xEA0D1727);
        g.renderOutline(left, top, width, bottom - top, ReplayPalette.BRASS_DIM);
        g.drawString(font, ReplayBoardTexts.tr("export.hud_title"), left + 8, top + 7, ReplayPalette.BRASS_LIGHT, false);
        int lineY = top + 20;
        for (var line : lines) {
            g.drawString(font, line, left + 8, lineY, ReplayPalette.TEXT, false);
            lineY += font.lineHeight;
        }
        g.fill(left + 8, barY, right - 8, barY + 4, 0xFF313F53);
        int filled = (int) ((width - 16) * video.displayProgress());
        g.fill(left + 8, barY, left + 8 + filled, barY + 4, 0xFF52C5B9);
        if (showCancelHint) {
            g.drawString(font, ReplayBoardTexts.tr("export.hud_cancel"), left + 8, barY + 10, ReplayPalette.TEXT_MUTED, false);
        }
        g.flush();
    }
}
