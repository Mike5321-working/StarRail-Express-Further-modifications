package com.habitrain.core.scene.client.replay;

import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.habitrain.core.scene.client.replay.ReplayBoardSite.BASE_TOP;

/**
 * The two in-world buttons floating in front of the idle cube sculpture: <b>Start Playback</b> and
 * <b>Export Video</b>.
 *
 * <p>Both are camera-facing Art Deco plaques (same night-blue / brass language as the status banner above the
 * cubes) that sit between the viewer and the sculpture, so they swing around the table together with the player.
 * They are pressed with the use key (right-click) while the crosshair is on them; the click is swallowed before
 * vanilla can interact with whatever block lies behind. "Start Playback" asks the server to start the shared
 * playback of this table, which every client tracking the table then follows from the same server start tick.
 * "Export Video" opens {@link ReplayExportScreen}.</p>
 */
public final class ReplayBoardButtons {
    /** Which sculpture-side action a plaque performs. */
    enum Action { PLAY, EXPORT }

    /** What the table is showing, derived from the shared client replay state and the table's synced playback. */
    enum Phase { NO_ROUND, LOADING, READY, STARTING, PLAYING }

    /** One plaque as drawn this frame; absolute world coordinates so it survives until the next click. */
    record Target(Action action, BlockPos board, boolean enabled, Component reason) {}

    private record Plaque(Target target, Matrix4f sign, float halfWidth, float halfHeight,
                          float scale, float textScale, Phase phase, Component label, double fade) {}

    private static final int GOLD_HALO = 0xFFE89E30, GOLD_CORE = 0xFFFFE6A8;
    private static final int CYAN_HALO = 0xFF28A4FF, CYAN_CORE = 0xFFAEE8FF;

    private static final long STALE_NS = 250_000_000L, DEBOUNCE_NS = 350_000_000L, PRESS_NS = 900_000_000L,
            FLASH_NS = 160_000_000L;
    /** Screen-space padding that makes the plaques slightly easier to hit than they look. */
    private static final double HIT_PAD = 0.02;

    private static final Map<BlockPos, Long> PRESSED = new HashMap<>();
    private static final Map<BlockPos, List<Plaque>> PLAQUES = new HashMap<>();
    private static Vec3 frameEye = Vec3.ZERO, frameDir = new Vec3(0, 0, 1), frameCam = Vec3.ZERO;
    private static Target hovered;
    private static double hoveredDistance = -1;
    private static long hoveredStamp, lastTrigger;

    private ReplayBoardButtons() {}

    // ------------------------------------------------------------------ frame lifecycle

    /** Called once per world frame, before any board is painted. */
    static void beginFrame(Minecraft mc, Vec3 camera, float partialTick) {
        hovered = null;
        hoveredDistance = -1;
        PLAQUES.clear();
        frameCam = camera;
        var player = mc.player;
        if (player == null) return;
        frameEye = player.getEyePosition(partialTick);
        frameDir = player.getViewVector(partialTick);
    }

    static void reset() {
        hovered = null;
        hoveredDistance = -1;
        PRESSED.clear();
        PLAQUES.clear();
    }

    // ------------------------------------------------------------------ input

    /**
     * Use-key hook (see the Minecraft mixin). Returns {@code true} when the click belongs to a plaque and must not
     * reach vanilla, even if the plaque is currently disabled or the click was debounced.
     */
    public static boolean handleUse(Minecraft mc) {
        Target target = hovered;
        if (target == null || mc.screen != null || mc.player == null || mc.level == null
                || System.nanoTime() - hoveredStamp > STALE_NS) return false;
        long now = System.nanoTime();
        if (now - lastTrigger < DEBOUNCE_NS) return true;
        lastTrigger = now;
        if (!target.enabled()) {
            mc.player.displayClientMessage(target.reason(), true);
            return true;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        switch (target.action()) {
            case PLAY -> {
                UUID round = ClientReplayBoard.availableRound();
                if (!ReplayPackets.EMPTY.equals(round) && ClientPlayNetworking.canSend(ReplayPackets.StartPlayback.TYPE)) {
                    ClientPlayNetworking.send(new ReplayPackets.StartPlayback(target.board(), round));
                    PRESSED.put(target.board(), now);
                }
            }
            case EXPORT -> mc.setScreen(new ReplayExportScreen(mc.level, target.board()));
        }
        return true;
    }

    // ------------------------------------------------------------------ state

    static Phase phase(ReplayFrame f, ReplayBoardSite s) {
        UUID round = ClientReplayBoard.availableRound();

        if (f.level.getBlockEntity(s.pos()) instanceof SettlementSummaryBoard.Marker marker) {
            var playback = marker.playback();
            if (playback != null && playback.pendingOrPlaying(f.level.getGameTime())) {
                return f.frame != null ? Phase.PLAYING : Phase.STARTING;
            }
        }
        if (ReplayPackets.EMPTY.equals(round)) return Phase.NO_ROUND;
        if (!ClientReplayBoard.replayLoaded()) return Phase.LOADING;
        Long pressed = PRESSED.get(s.pos());
        if (pressed != null) {
            if (System.nanoTime() - pressed < PRESS_NS) return Phase.STARTING;
            PRESSED.remove(s.pos());
        }
        return Phase.READY;
    }

    /** Text of the status banner crowning the sculpture. */
    static Component bannerText(Phase phase) {
        return switch (phase) {
            case NO_ROUND -> ReplayBoardTexts.tr("idle");
            case LOADING, PLAYING -> ReplayBoardTexts.tr("loading");
            case READY -> ReplayBoardTexts.tr("ready");
            case STARTING -> ReplayBoardTexts.tr("starting");
        };
    }

    private static Component playLabel(Phase phase) {
        return ReplayBoardTexts.tr(switch (phase) {
            case NO_ROUND -> "button.no_round";
            case LOADING -> "button.loading";
            case READY -> "button.play";
            case STARTING, PLAYING -> "button.playing";
        });
    }

    private static Component playReason(Phase phase) {
        return switch (phase) {
            case NO_ROUND -> ReplayBoardTexts.tr("controls.no_round");
            case LOADING -> ReplayBoardTexts.tr("controls.loading_round");
            case READY -> ReplayBoardTexts.tr("controls.play_unsupported");
            case STARTING, PLAYING -> ReplayBoardTexts.tr("controls.playing");
        };
    }

    private static Component exportReason(Phase phase) {
        if (phase == Phase.NO_ROUND) return ReplayBoardTexts.tr("controls.no_round");
        if (!ClientReplayBoard.replayLoaded()) return ReplayBoardTexts.tr("controls.loading_round");
        return ReplayBoardTexts.tr("controls.map_unavailable");
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Distance along the ray at which it crosses the camera-facing plaque, or {@code -1} when it misses.
     * {@code right}/{@code up} are the unit axes of the plaque (the camera's right/up vectors), so the plaque
     * normal is {@code right × up}.
     */
    static double hitDistance(Vec3 eye, Vec3 dir, Vec3 center, Vec3 right, Vec3 up,
                              double halfW, double halfH, double reach) {
        Vec3 normal = right.cross(up);
        double denom = dir.dot(normal);
        if (Math.abs(denom) < 1.0e-6) return -1;
        double t = center.subtract(eye).dot(normal) / denom;
        if (!(t > 0.05) || t > reach) return -1;
        Vec3 offset = eye.add(dir.scale(t)).subtract(center);
        return Math.abs(offset.dot(right)) <= halfW && Math.abs(offset.dot(up)) <= halfH ? t : -1;
    }

    // ------------------------------------------------------------------ painting

    /**
     * Paints both plaques in front of the sculpture and updates which one the crosshair is on.
     *
     * @param localScale tray-relative scale of the sculpture ({@code ReplayIdleCubes} local scale)
     * @param fade       distance fade 0..1 of the table
     */
    static void collect(ReplayFrame f, ReplayBoardSite s, Phase phase, float localScale, double fade) {
        if (fade < 0.35) return;
        float k = localScale * s.scale();
        var font = f.mc.font;
        Component playLabel = playLabel(phase);
        Component exportLabel = ReplayBoardTexts.tr("button.export");
        float ts = 0.0090f * k;
        float labelW = Math.max(font.width(playLabel), font.width(exportLabel)) * ts;
        float hw = (labelW + 0.42f * k) * 0.5f;
        float hh = 0.125f * k;
        float gap = 0.10f * k;

        // Row anchor: just above the dial, pulled toward the viewer so the plaques never sit inside the cubes.
        Vector3f anchor = s.board().transformPosition(0f, BASE_TOP + 0.30f * localScale, 0f, new Vector3f());
        float horiz = (float) Math.sqrt(anchor.x * anchor.x + anchor.z * anchor.z);
        float pull = Math.min(0.95f * k, Math.max(0f, horiz - 1.0f));
        if (horiz > 1.0e-3f) {
            anchor.add(-anchor.x / horiz * pull, 0f, -anchor.z / horiz * pull);
        }

        var player = f.mc.player;
        boolean aiming = player != null && !player.isSpectator() && f.mc.screen == null && f.mc.mouseHandler.isMouseGrabbed();
        double reach = 6.0 * Math.max(1.0f, s.scale()) + 2.0;
        float offsetX = hw + gap * 0.5f;

        boolean playEnabled = phase == Phase.READY && ClientPlayNetworking.canSend(ReplayPackets.StartPlayback.TYPE);
        boolean exportEnabled = ClientReplayBoard.replayLoaded() && ClientReplayBoard.mapAvailable();
        List<Plaque> plaques = new ArrayList<>(2);
        PLAQUES.put(s.pos(), plaques);

        for (int side = -1; side <= 1; side += 2) {
            boolean play = side < 0;
            Vector3f center = new Vector3f(anchor).add(f.right.x * offsetX * side, f.right.y * offsetX * side,
                    f.right.z * offsetX * side);
            boolean enabled = play ? playEnabled : exportEnabled;
            Target target = new Target(play ? Action.PLAY : Action.EXPORT, s.pos(), enabled,
                    play ? playReason(phase) : exportReason(phase));

            if (aiming) {
                Vec3 absolute = new Vec3(center.x + frameCam.x, center.y + frameCam.y, center.z + frameCam.z);
                Vec3 right = new Vec3(f.right.x, f.right.y, f.right.z), up = new Vec3(f.up.x, f.up.y, f.up.z);
                double t = hitDistance(frameEye, frameDir, absolute, right, up, hw + HIT_PAD * k, hh + HIT_PAD * k, reach);
                if (t > 0 && !occluded(f, t) && (hoveredDistance < 0 || t < hoveredDistance)) {
                    hovered = target;
                    hoveredDistance = t;
                    hoveredStamp = System.nanoTime();
                }
            }
            Matrix4f sign = new Matrix4f().translate(center.x, center.y, center.z).rotate(f.rotation);
            plaques.add(new Plaque(target, sign, hw, hh, k, ts, phase, play ? playLabel : exportLabel, fade));
        }
    }

    static void paint(ReplayFrame frame, ReplayBoardSite site) {
        for (var plaque : PLAQUES.getOrDefault(site.pos(), List.of())) {
            var target = plaque.target();
            boolean selected = target == hovered;
            drawButton(frame, plaque.sign(), plaque.halfWidth(), plaque.halfHeight(), plaque.scale(), plaque.textScale(),
                    target.action() == Action.PLAY, target.enabled(), selected, plaque.phase(), plaque.label(), plaque.fade());
            if (selected) drawHint(frame, plaque.sign(), plaque.halfHeight(), plaque.scale(), plaque.textScale(),
                    target.enabled(), target.reason(), plaque.fade());
        }
    }

    /** True when a block lies between the eye and the plaque (walls hide the table's buttons too). */
    private static boolean occluded(ReplayFrame f, double distance) {
        var player = f.mc.player;
        if (player == null || distance < 0.3) return false;
        Vec3 to = frameEye.add(frameDir.scale(distance - 0.1));
        return f.level.clip(new ClipContext(frameEye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
                .getType() != HitResult.Type.MISS;
    }

    private static void drawButton(ReplayFrame f, Matrix4f sign, float hw, float hh, float k, float ts,
                                   boolean play, boolean enabled, boolean hover, Phase phase, Component label, double fade) {
        VertexConsumer panel = f.buffers.getBuffer(ReplayDraw.PANEL);
        VertexConsumer glow = f.glow();
        int halo = play ? CYAN_HALO : GOLD_HALO, core = play ? CYAN_CORE : GOLD_CORE;
        float chamfer = 0.045f * k;
        float lineW = 0.009f * k;
        boolean busy = play && (phase == Phase.STARTING || phase == Phase.PLAYING);

        int top, bottom, border;
        if (!enabled && !busy) {
            top = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.80 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.84 * fade);
            border = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.55 * fade);
        } else if (hover) {
            top = ReplayPalette.alpha(ReplayPalette.lerp(ReplayPalette.NIGHT_3, halo, 0.30), 0.96 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.lerp(ReplayPalette.NIGHT_1, halo, 0.16), 0.96 * fade);
            border = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, fade);
        } else {
            top = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.92 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.94 * fade);
            border = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.88 * fade);
        }
        chamferPanel(panel, sign, hw, hh, chamfer, 0f, top, bottom);
        chamferOutline(glow, sign, hw, hh, chamfer, 0.002f, lineW, border);

        double pulse = 0.5 + 0.5 * Math.sin(ReplayIdleCubes.wrapAngleRad(f.anim * 4.0));
        if (hover && enabled) {
            // Mitred halo frame around the plate; the plate's depth writes hide the part behind it.
            float o = 0.06f * k;
            int inner = ReplayPalette.alpha(halo, (0.30 + 0.12 * pulse) * fade), outer = ReplayPalette.alpha(halo, 0);
            ReplayDraw.quad(glow, sign, -hw, hh, -0.004f, hw, hh, -0.004f, hw + o, hh + o, -0.004f, -hw - o, hh + o, -0.004f,
                    inner, inner, outer, outer);
            ReplayDraw.quad(glow, sign, hw, -hh, -0.004f, -hw, -hh, -0.004f, -hw - o, -hh - o, -0.004f, hw + o, -hh - o, -0.004f,
                    inner, inner, outer, outer);
            ReplayDraw.quad(glow, sign, -hw, -hh, -0.004f, -hw, hh, -0.004f, -hw - o, hh + o, -0.004f, -hw - o, -hh - o, -0.004f,
                    inner, inner, outer, outer);
            ReplayDraw.quad(glow, sign, hw, hh, -0.004f, hw, -hh, -0.004f, hw + o, -hh - o, -0.004f, hw + o, hh + o, -0.004f,
                    inner, inner, outer, outer);
        } else if (enabled) {
            // Slow light sheen gliding across an idle, usable plate.
            double phaseT = ReplayIdleCubes.wrapAngleRad(f.anim * 0.9 + (play ? 0 : 1.9)) / (Math.PI * 2.0) + 0.5;
            float x = Mth.lerp((float) phaseT, -hw + chamfer, hw - chamfer - 0.06f * k);
            int sheen = ReplayPalette.alpha(core, 0.13 * Math.sin(phaseT * Math.PI) * fade);
            int none = ReplayPalette.alpha(core, 0);
            ReplayDraw.quad(glow, sign, x, -hh, 0.001f, x + 0.06f * k, -hh, 0.001f, x + 0.10f * k, hh, 0.001f, x + 0.04f * k, hh, 0.001f,
                    none, sheen, sheen, none);
        }
        if (hover && enabled && System.nanoTime() - lastTrigger < FLASH_NS) {
            int flash = ReplayPalette.alpha(0xFFFFFFFF, 0.22 * fade);
            ReplayDraw.rectXY(glow, sign, -hw + chamfer, -hh, hw - chamfer, hh, 0.001f, flash, flash);
        }

        // Icon medallion on the left, separated from the label by a fine brass rule.
        float ix = -hw + 0.16f * k;
        int iconCol = ReplayPalette.alpha(enabled || busy ? core : ReplayPalette.TEXT_FAINT, (enabled || busy ? 0.95 : 0.55) * fade);
        int ringCol = ReplayPalette.alpha(enabled || busy ? halo : ReplayPalette.BRASS_DIM, (hover ? 0.85 : 0.55) * fade);
        ring(glow, sign, ix, 0f, 0.002f, 0.105f * k, 0.007f * k, ringCol);
        if (play) {
            if (busy) {
                float bar = 0.017f * k, bh = 0.055f * k;
                int c = ReplayPalette.alpha(core, (0.65 + 0.35 * pulse) * fade);
                ReplayDraw.rectXY(glow, sign, ix - 0.04f * k, -bh, ix - 0.04f * k + bar * 2, bh, 0.003f, c, c);
                ReplayDraw.rectXY(glow, sign, ix + 0.04f * k - bar * 2, -bh, ix + 0.04f * k, bh, 0.003f, c, c);
            } else {
                ReplayDraw.quad(glow, sign, ix - 0.040f * k, -0.068f * k, 0.003f, ix - 0.040f * k, 0.068f * k, 0.003f,
                        ix + 0.072f * k, 0f, 0.003f, ix + 0.072f * k, 0f, 0.003f, iconCol, iconCol, iconCol, iconCol);
            }
        } else {
            float w = 0.011f * k;
            ReplayDraw.segment(glow, sign, ix, 0.062f * k, 0.003f, ix, -0.030f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix - 0.042f * k, 0.002f * k, 0.003f, ix, -0.040f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix + 0.042f * k, 0.002f * k, 0.003f, ix, -0.040f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix - 0.060f * k, -0.072f * k, 0.003f, ix + 0.060f * k, -0.072f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix - 0.060f * k, -0.072f * k, 0.003f, ix - 0.060f * k, -0.040f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix + 0.060f * k, -0.072f * k, 0.003f, ix + 0.060f * k, -0.040f * k, 0.003f, w, iconCol, iconCol);
        }
        int rule = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.75 * fade);
        float rx = -hw + 0.30f * k;
        ReplayDraw.segment(glow, sign, rx, -hh + 0.035f * k, 0.002f, rx, hh - 0.035f * k, 0.002f, lineW * 0.6f, rule, rule);

        int textColor;
        if (!enabled && !busy) textColor = ReplayPalette.alpha(ReplayPalette.TEXT_FAINT, fade);
        else if (hover) textColor = ReplayPalette.alpha(0xFFFFFFFF, fade);
        else textColor = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, fade);
        float zoneCenter = ((-hw + 0.33f * k) + (hw - 0.04f * k)) * 0.5f;
        f.text(sign, label, zoneCenter, 0f, 0.005f, ts, textColor, 0, true, hw * 2f - 0.37f * k);
    }

    /** Small plate under the hovered plaque: the key hint, or why the plaque cannot be pressed yet. */
    private static void drawHint(ReplayFrame f, Matrix4f sign, float hh, float k, float ts, boolean enabled,
                                 Component reason, double fade) {
        Component text = enabled ? ReplayBoardTexts.tr("button.hint") : reason;
        float hs = ts * 0.82f;
        float halfW = f.mc.font.width(text) * hs * 0.5f + 0.07f * k;
        float halfH = 0.060f * k;
        Matrix4f m = new Matrix4f(sign).translate(0f, -(hh + 0.075f * k + halfH), 0f);
        VertexConsumer panel = f.buffers.getBuffer(ReplayDraw.PANEL);
        chamferPanel(panel, m, halfW, halfH, 0.025f * k, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.92 * fade), ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.94 * fade));
        chamferOutline(f.glow(), m, halfW, halfH, 0.025f * k, 0.002f, 0.006f * k,
                ReplayPalette.alpha(enabled ? ReplayPalette.BRASS : ReplayPalette.RUBY, 0.70 * fade));
        f.text(m, text, 0f, 0f, 0.005f, hs, ReplayPalette.alpha(enabled ? ReplayPalette.TEXT : ReplayPalette.RUBY, fade), 0, true, 0);
    }

    // ------------------------------------------------------------------ primitives

    private static void chamferPanel(VertexConsumer vc, Matrix4f m, float hw, float hh, float c, float z, int top, int bottom) {
        ReplayDraw.rectXY(vc, m, -hw + c, -hh, hw - c, hh, z, top, bottom);
        // Gradient colours at the lower / upper end of the two side strips.
        int sideLow = ReplayPalette.lerp(bottom, top, c / (hh * 2)), sideHigh = ReplayPalette.lerp(bottom, top, (hh * 2 - c) / (hh * 2));
        ReplayDraw.quad(vc, m, -hw, -hh + c, z, -hw + c, -hh + c, z, -hw + c, hh - c, z, -hw, hh - c, z,
                sideLow, sideLow, sideHigh, sideHigh);
        ReplayDraw.quad(vc, m, hw - c, -hh + c, z, hw, -hh + c, z, hw, hh - c, z, hw - c, hh - c, z,
                sideLow, sideLow, sideHigh, sideHigh);
    }

    private static void chamferOutline(VertexConsumer glow, Matrix4f m, float hw, float hh, float c, float z, float width, int color) {
        ReplayDraw.segment(glow, m, hw - c, hh, z, -hw + c, hh, z, width, color, color);
        ReplayDraw.segment(glow, m, -hw + c, hh, z, -hw, hh - c, z, width, color, color);
        ReplayDraw.segment(glow, m, -hw, hh - c, z, -hw, -hh + c, z, width, color, color);
        ReplayDraw.segment(glow, m, -hw, -hh + c, z, -hw + c, -hh, z, width, color, color);
        ReplayDraw.segment(glow, m, -hw + c, -hh, z, hw - c, -hh, z, width, color, color);
        ReplayDraw.segment(glow, m, hw - c, -hh, z, hw, -hh + c, z, width, color, color);
        ReplayDraw.segment(glow, m, hw, -hh + c, z, hw, hh - c, z, width, color, color);
        ReplayDraw.segment(glow, m, hw, hh - c, z, hw - c, hh, z, width, color, color);
    }

    /** Ring in the local XY plane (the plaque face). */
    private static void ring(VertexConsumer glow, Matrix4f m, float cx, float cy, float z, float radius, float width, int color) {
        int segments = 18;
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments, a1 = Mth.TWO_PI * (i + 1) / segments;
            ReplayDraw.segment(glow, m, cx + Mth.cos(a0) * radius, cy + Mth.sin(a0) * radius, z,
                    cx + Mth.cos(a1) * radius, cy + Mth.sin(a1) * radius, z, width, color, color);
        }
    }
}
