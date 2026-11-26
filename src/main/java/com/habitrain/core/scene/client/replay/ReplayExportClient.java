package com.habitrain.core.scene.client.replay;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.network.replay.ReplayPackets.RequestVoiceExportC2S;
import com.habitrain.core.network.replay.ReplayPackets.VoiceExportAccessS2C;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.client.ReplayHistoryClient;
import com.habitrain.core.scene.replay.ReplayExportContentOptions;
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
    private static @Nullable Component lastStartError;
    private static long finishedAt;
    private static long nextAccessRequest;
    private static java.util.UUID pinnedRound;
    private static volatile java.util.UUID foregroundId;
    private static boolean liveMatch;
    private static Screen previousScreen;

    public static boolean foreground() { return foregroundId != null; }

    public static void matchNotice(Minecraft client, com.habitrain.core.network.replay.ReplayPackets.ExportMatchS2C packet) {
        if (packet.session().equals(foregroundId)) markMatch(client, packet.active());
    }

    private static void markMatch(Minecraft client, boolean active) {
        if (active && !liveMatch && client.player != null) {
            client.player.displayClientMessage(ReplayBoardTexts.tr("export.match_started"), false);
        }
        if (active) previousScreen = null;
        liveMatch = active;
    }

    public static void tickForeground(Minecraft client) {
        if (!foreground()) return;
        if (video == null || !video.inForeground()) { leaveForeground(client, true); return; }
        if (client.level == null || client.player == null || client.getOverlay() != null) return;
        if (!(client.screen instanceof PlaybackScreen)) {
            if (client.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                    || client.screen instanceof net.minecraft.client.gui.screens.ProgressScreen
                    || client.screen instanceof net.minecraft.client.gui.screens.GenericMessageScreen) {
                return;
            }
            if (client.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
                video.cancel();
                leaveForeground(client, false);
                return;
            }
            if (client.screen != null && client.screen != previousScreen && !isExportScreen(client.screen)
                    && !(client.screen instanceof com.habitrain.core.client.gui.VoteLaunchTransitionScreen)
                    && !(client.screen instanceof com.habitrain.core.client.gui.GameEndTransitionScreen)) {
                video.cancel();
                leaveForeground(client, false);
                return;
            }
            client.setScreen(new PlaybackScreen());
        }
    }

    private static void leaveForeground(Minecraft client, boolean restore) {
        var id = foregroundId;
        if (id == null) return;
        foregroundId = null;
        if (client.getConnection() != null && ClientPlayNetworking.canSend(com.habitrain.core.network.replay.ReplayPackets.ExportPresenceC2S.TYPE))
            ClientPlayNetworking.send(new com.habitrain.core.network.replay.ReplayPackets.ExportPresenceC2S(id, false));
        if (restore && client.screen instanceof PlaybackScreen && client.level != null && client.player != null)
            client.setScreen(liveMatch ? null : previousScreen);
        previousScreen = null;
        liveMatch = false;
    }

    private static final class PlaybackScreen extends Screen {
        PlaybackScreen() { super(ReplayBoardTexts.tr("export.backend_native")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            var message = ReplayBoardTexts.tr(liveMatch ? "export.match_started" : "export.foreground_hint");
            int top = height / 8;
            int textWidth = Math.min(width - 16, font.width(message));
            var lines = font.split(message, Math.max(1, textWidth));
            graphics.fill((width - textWidth) / 2 - 6, top - 4, (width + textWidth) / 2 + 6,
                    top + lines.size() * font.lineHeight + 2, 0xB0000000);
            for (var line : lines) {
                graphics.drawCenteredString(font, line, width / 2, top, liveMatch ? 0xffff92 : 0xffffff);
                top += font.lineHeight;
            }
        }
        @Override public void onClose() { cancelInBackground(minecraft); }
    }

    private ReplayExportClient() {}

    static boolean mapReady(ClientReplayBoard.View view) {
        return view != null && view.replay() != null && view.timeline() != null && view.asset() != null
                && view.totalSections() > 0 && view.presentSections() == view.totalSections();
    }

    static ReplayVideoExport current() { return video; }

    static @Nullable Component lastStartError() { return lastStartError; }

    static void clearStartError() { lastStartError = null; }

    static void acknowledgeFailure(@Nullable ReplayVideoExport seen) {
        if (seen != null && video == seen && seen.failed() && !seen.encoderBusy()) {
            video = null;
            finishedAt = 0;
        }
    }

    static ReplayVideoExport forScreen(ReplayVideoExport shown) {
        return video != null && (video == shown || video.running() || video.failed()) ? video : null;
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
        var opts = ReplayExportContentOptions.defaultsFor(ReplayExportContentOptions.ExportMode.SHORT, focusPlayer)
                .toBuilder()
                .roofCutaway(!fullBody)
                .musicLevel(music == null ? ReplayMusicPlan.Level.OFF : music)
                .build();
        return start(view, opts, resolution, compress, quality, source);
    }

    static @Nullable ReplayVideoExport start(ClientReplayBoard.View view, ReplayExportContentOptions options,
                                             ReplayExportResolution resolution, boolean compress,
                                             ReplayExportQuality quality, Supplier<ClientReplayBoard.View> source) {
        return start(view, options, resolution, compress, quality, source, false);
    }

    static @Nullable ReplayVideoExport start(ClientReplayBoard.View view, ReplayExportContentOptions options,
                                             ReplayExportResolution resolution, boolean compress, ReplayExportQuality quality,
                                             Supplier<ClientReplayBoard.View> source, boolean preferSimple) {
        lastStartError = null;
        if (!canStart()) {
            lastStartError = ReplayBoardTexts.tr("export.start_busy");
            return null;
        }
        if (!mapReady(view)) {
            lastStartError = ReplayBoardTexts.tr("export.start_map_not_ready");
            return null;
        }
        if (!compress && !resolution.aviSupported()) {
            lastStartError = ReplayBoardTexts.tr("export.start_avi_unsupported");
            return null;
        }
        java.nio.file.Path ffmpeg = null;
        if (compress) {
            Minecraft mc = Minecraft.getInstance();
            java.nio.file.Path gameDir = mc != null ? mc.gameDirectory.toPath() : null;
            ffmpeg = ReplayFfmpegManager.peekAvailable(gameDir);
            if (ffmpeg == null) {
                lastStartError = ReplayBoardTexts.tr(
                        ReplayFfmpegManager.isProbing() ? "export.ffmpeg_probing" : "export.start_ffmpeg_missing");
                return null;
            }
        }
        releasePinnedRound();
        video = new ReplayVideoExport(view, options, resolution, compress, quality, ffmpeg,
                ReplayExportBackend.select(options, preferSimple));
        pinnedRound = view.replay().roundId();
        ReplayHistoryClient.pin(pinnedRound);
        voiceSource = view.voiceReady() ? null : source;
        nextAccessRequest = 0;
        if (!ClientPlayNetworking.canSend(RequestVoiceExportC2S.TYPE)) video.setVoiceExportAllowed(false);
        finishedAt = 0;
        if (video.inForeground()) {
            var client = Minecraft.getInstance();
            foregroundId = java.util.UUID.randomUUID();
            previousScreen = client.screen;
            if (ClientPlayNetworking.canSend(com.habitrain.core.network.replay.ReplayPackets.ExportPresenceC2S.TYPE))
                ClientPlayNetworking.send(new com.habitrain.core.network.replay.ReplayPackets.ExportPresenceC2S(foregroundId, true));
            tickForeground(client);
        }
        return video;
    }

    public static void acceptVoiceExportAccess(VoiceExportAccessS2C reply) {
        if (video != null && video.running() && video.roundId().equals(reply.roundId())) {
            video.setVoiceExportAllowed(reply.allowed());
        }
    }

    public static void onGameStartedCleanup() {
        if (video != null && !video.running() && !video.encoderBusy()) {
            video.releaseRenderResources();
            releasePinnedRound();
            video = null;
            voiceSource = null;
            finishedAt = 0;
        }
    }

    static void onGameStarted(Minecraft client) {
        if (foreground()) { markMatch(client, true); return; }
        if (video != null && video.running() && isExportScreen(client.screen)) client.setScreen(null);
        else onGameStartedCleanup();
    }

    static void reset() {
        if (video != null) video.cancel();
        leaveForeground(Minecraft.getInstance(), false);
        releasePinnedRound();
        video = null;
        voiceSource = null;
        lastStartError = null;
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
        if (foreground() && client.level != null && client.player != null) {
            if (video != null && video.running() && !video.finalizing()) video.cancel();
            leaveForeground(client, true);
            return true;
        }
        return video != null && video.running() && canCancelWithEscape(client) && video.cancel();
    }

    public static void drawWorld(Minecraft client, net.minecraft.client.DeltaTracker clock, java.util.function.Consumer<net.minecraft.client.DeltaTracker> original) {
        if (foreground() && video != null && client.getOverlay() == null) video.drawWindow(client, original);
        else original.accept(clock);
    }

    public static void beforeGui(Minecraft client) {
        if (foreground() && video != null && client.level != null && client.player != null && client.getOverlay() == null) {
            advance(client);
            video.preview(client);
        }
    }

    private static void advance(Minecraft client) {
        if (video == null || client.level == null || client.player == null || client.getOverlay() != null) return;
        long now = System.nanoTime();
        if (video.waitingForVoiceAccess() && (nextAccessRequest == 0 || now - nextAccessRequest >= 0)
                && ClientPlayNetworking.canSend(RequestVoiceExportC2S.TYPE)) {
            ClientPlayNetworking.send(new RequestVoiceExportC2S(video.roundId()));
            nextAccessRequest = now + TimeUnit.SECONDS.toNanos(3);
        }
        if (video.running()) video.renderNext(client, voiceSource);
        if (foreground() && !video.inForeground()) leaveForeground(client, true);
        if (!video.waitingForVoice()) voiceSource = null;

        if (!video.running() && finishedAt == 0) {
            video.releaseRenderResources();
            releasePinnedRound();
            finishedAt = now;
        }
    }

    public static void renderFrame(Minecraft client) {
        if (video == null || client.level == null || client.player == null || client.getOverlay() != null) return;
        if (!foreground()) advance(client);
        long now = System.nanoTime();
        if (isExportScreen(client.screen) || (client.options.hideGui && client.screen == null)) return;
        if (finishedAt != 0 && now - finishedAt >= TimeUnit.SECONDS.toNanos(6)) {
            if (!video.running() && !video.encoderBusy() && !video.failed()) {
                video = null;
                finishedAt = 0;
            }
            return;
        }

        GuiGraphics g = new GuiGraphics(client, client.renderBuffers().bufferSource());
        int width = Math.min(180, g.guiWidth() - 16);
        if (width <= 16) return;
        int left = 12, right = left + width;
        int top = 36;
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
