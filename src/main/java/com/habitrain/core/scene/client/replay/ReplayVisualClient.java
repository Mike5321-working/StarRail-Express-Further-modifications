package com.habitrain.core.scene.client.replay;

import com.habitrain.core.network.replay.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import java.util.*;

public final class ReplayVisualClient {
    private static volatile UUID round;
    private static final byte[] EMPTY = new byte[0];
    private static byte[] previousCamera = EMPTY, previousHud = EMPTY, pendingHud = EMPTY;
    private static List<VisualFrames.Draw> commands;
    private static int width, height;
    private static float opticalFov;
    private static final Map<UUID,Integer> seen = new TreeMap<>();
    private static byte[] previousOutlines = EMPTY;
    private static boolean suspended;
    public static void saw(net.minecraft.world.entity.Entity target, OptionalInt color) {
        if (target == null || color == null || color.isEmpty() || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return;
        if (recording()) recordOutline(target.getUUID(), color.getAsInt());
    }
    static void recordOutline(UUID id, int color) {
        if (id == null) return;
        synchronized (seen) {
            if (seen.size() < 128 || seen.containsKey(id)) seen.put(id, color);
        }
    }
    static byte[] drainOutlines() {
        synchronized (seen) {
            byte[] encoded = VisualTape.bytes(out -> {
                out.writeShort(seen.size());
                for (var entry : seen.entrySet()) {
                    out.writeLong(entry.getKey().getMostSignificantBits());
                    out.writeLong(entry.getKey().getLeastSignificantBits());
                    out.writeInt(entry.getValue());
                }
            });
            seen.clear();
            return encoded;
        }
    }
    static void clearOutlines() {
        synchronized (seen) {
            seen.clear();
        }
    }
    public static double fov(double value, boolean worldLens) {
        if (worldLens && value > 0 && value < 180 && com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            if (ReplayForegroundWorld.rendering() && ReplayForegroundWorld.renderedFov > 0) return ReplayForegroundWorld.renderedFov;
            if (recording()) opticalFov = (float)value;
        }
        return value;
    }
    private ReplayVisualClient() {}
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(VisualPackets.Session.TYPE, (packet, context) -> {
            if (!packet.active() && !packet.round().equals(round)) return;
            reset();
            if (packet.active()) {
                round = packet.round();
                context.player().displayClientMessage(Component.literal("本局第一人称视角和对局界面将在赛后供所有玩家观看"), false);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(VisualPackets.Page.TYPE, (packet, context) -> {
            var video = ReplayExportClient.current();
            if (video != null && video.running() && video.roundId().equals(packet.round())) video.visuals.accept(packet);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.isSameThread()) reset();
            else client.execute(ReplayVisualClient::reset);
        });
        ClientTickEvents.END_CLIENT_TICK.register(ReplayVisualClient::tick);
    }
    private static void reset() { round = null; suspended = false; commands = null; previousCamera = previousHud = pendingHud = previousOutlines = EMPTY; opticalFov = 0; clearOutlines(); }
    public static boolean recording() { return round != null && !ReplayExportClient.foreground() && !ReplayForegroundWorld.rendering(); }
    public static void begin(GuiGraphics graphics) {
        commands = null;
        if (!recording() || !io.wifi.utils.client.betterrender.OptimizedTextRenderer.INSTANCE.isTickDirty()) return;
        var client = Minecraft.getInstance();
        if (client.screen != null || client.options.hideGui) return;
        width = graphics.guiWidth(); height = graphics.guiHeight(); commands = new ArrayList<>();
    }
    public static void draw(GuiGraphics graphics, int kind, float x, float y, float xx, float yy, int color, String text, float[] uv) {
        if (commands == null || commands.size() >= 256 || text.length() > 512) return;
        var matrix = graphics.pose().last().pose();
        var start = matrix.transformPosition(x, y, 0f, new Vector3f());
        var end = matrix.transformPosition(xx, yy, 0f, new Vector3f());
        commands.add(new VisualFrames.Draw(kind, start.x, start.y, end.x, end.y, color, text, uv));
    }
    public static boolean collecting() { return commands != null; }
    public static void end() {
        if (commands == null) return;
        var hud = new VisualFrames.Hud(width, height, commands).encode(); commands = null;
        if (hud.length <= VisualTape.MAX_EVENT) pendingHud = hud;
    }
    private static void tick(Minecraft client) {
        if (round == null || client.player == null || client.level == null || !ClientPlayNetworking.canSend(VisualPackets.Frame.TYPE)) return;
        if (!recording()) {
            if (!suspended) { suspended = true; ClientPlayNetworking.send(new VisualPackets.Frame(round,EMPTY,EMPTY,EMPTY)); previousCamera=previousHud=pendingHud=previousOutlines=EMPTY; clearOutlines(); }
            return;
        }
        suspended=false;
        var camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;
        var pos = camera.getPosition();
        var mood=io.wifi.starrailexpress.client.SREClient.moodComponent;
        int filters=(mood!=null && mood.isLowerThanDepressed()?1:0) | (io.wifi.starrailexpress.client.SREClient.getLooseEndPenalty()?2:0);
        var renderer=(com.habitrain.core.client.mixin.ReplayHandHeights)client.gameRenderer.itemInHandRenderer;
        var hands=new VisualFrames.Hands(renderer.habitrain$oldMain(),renderer.habitrain$main(),renderer.habitrain$oldOff(),renderer.habitrain$off(),client.player.oAttackAnim,client.player.attackAnim,client.player.swingingArm==net.minecraft.world.InteractionHand.OFF_HAND);
        var cameraBytes = new VisualFrames.Camera(pos.x, pos.y, pos.z, camera.getYRot(), camera.getXRot(), opticalFov > 0 ? opticalFov : client.options.fov().get(), client.player.isSpectator(), client.options.hideGui,filters,hands).encode();
        byte[] hud = client.screen == null && !client.options.hideGui ? pendingHud : new VisualFrames.Hud(1, 1, List.of()).encode();
        byte[] viewChange = Arrays.equals(cameraBytes, previousCamera) ? EMPTY : cameraBytes;
        byte[] hudChange = Arrays.equals(hud, previousHud) ? EMPTY : hud;
        byte[] outlines = drainOutlines();
        byte[] colors=Arrays.equals(outlines,previousOutlines)?EMPTY:outlines; previousOutlines=outlines;
        if (viewChange.length > 0 || hudChange.length > 0 || colors.length > 0) ClientPlayNetworking.send(new VisualPackets.Frame(round, viewChange, hudChange,colors));
        previousCamera = cameraBytes; previousHud = hud;
    }
    static void paint(Minecraft client, GuiGraphics graphics, VisualFrames.Hud hud) {
        if (hud == null || hud.draws().isEmpty()) return;
        graphics.pose().pushPose();
        try {
            graphics.pose().scale((float)graphics.guiWidth() / hud.width(), (float)graphics.guiHeight() / hud.height(), 1);
            for (var draw : hud.draws()) {
                int x = Math.round(draw.x()), y = Math.round(draw.y()), xx = Math.round(draw.xx()), yy = Math.round(draw.yy());
                switch (draw.kind()) {
                    case 0, 1 -> graphics.drawString(client.font, draw.text(), x, y, draw.color(), draw.kind() == 1);
                    case 2 -> graphics.fill(x, y, xx, yy, draw.color());
                    case 3 -> {
                        var uv = draw.uv(); int color = draw.color();
                        ((com.habitrain.core.client.mixin.ReplayHudTextureAccessor)graphics).habitrain$drawTexture(ResourceLocation.parse(draw.text()), x, xx, y, yy, 0, uv[0], uv[1], uv[2], uv[3], (color >> 16 & 255)/255f, (color >> 8 & 255)/255f, (color & 255)/255f, (color >>> 24)/255f);
                    }
                    case 4 -> graphics.blitSprite(ResourceLocation.parse(draw.text()), x, y, xx-x, yy-y);
                }
            }
            graphics.flush();
        } finally { graphics.pose().popPose(); }
    }
}
