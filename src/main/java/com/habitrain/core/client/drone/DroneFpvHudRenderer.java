package com.habitrain.core.client.drone;

import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.drone.DroneMessages;
import com.habitrain.core.game.sre.role.drone.DronePayloadCategory;
import com.habitrain.core.game.sre.role.drone.DronePayloadHandler;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import com.habitrain.core.game.sre.role.drone.ReconDroneEntity;
import com.habitrain.core.network.DroneStatePayload;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * Renders the overhauled AERO-LINK OS UAV Tactical Cockpit HUD and the
 * on-foot right-side circular quadcopter status dial for {@code habitrain_core:drone_operator}:
 * <ul>
 *   <li>Right-side circular drone dial with quadcopter silhouette, rotor rings, 24-segment
 *       circular battery arc, and 3 spare battery pips (zero hotbar item-name collision);</li>
 *   <li>Panoramic sci-fi compass with live digital heading box, degree scale, {@code ❖ HOME}
 *       azimuth tracker pip, and integrated RF signal &amp; range gauge;</li>
 *   <li>Artificial horizon pitch/roll ladder + adaptive center gimbal viewfinder;</li>
 *   <li>Left flight avionics &amp; sonar scanner wing + right segmented power core wing;</li>
 *   <li>Bottom sleek payload &amp; interactive keycap console (with single-use lethal crash warning).</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class DroneFpvHudRenderer {
    private DroneFpvHudRenderer() {}

    private static final String[] CARDINALS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] COMPASS_HEADINGS = new String[24];
    private static final int CIRCLE_SEGS = 24;
    private static final float[] CIRCLE_COS = new float[CIRCLE_SEGS + 1];
    private static final float[] CIRCLE_SIN = new float[CIRCLE_SEGS + 1];
    private static final int ARC_STEPS = 48;
    private static final float[] ARC_COS = new float[ARC_STEPS + 1];
    private static final float[] ARC_SIN = new float[ARC_STEPS + 1];
    private static final int DIAL_SEGMENTS = 24;
    private static final int[] DIAL_DX = new int[DIAL_SEGMENTS];
    private static final int[] DIAL_DY = new int[DIAL_SEGMENTS];

    static {
        for (int i = 0; i < COMPASS_HEADINGS.length; i++) {
            COMPASS_HEADINGS[i] = String.format(Locale.ROOT, "%03d", i * 15);
        }
        for (int i = 0; i <= CIRCLE_SEGS; i++) {
            double ang = i * Math.PI * 2.0D / CIRCLE_SEGS;
            CIRCLE_COS[i] = (float) Math.cos(ang);
            CIRCLE_SIN[i] = (float) Math.sin(ang);
        }
        for (int i = 0; i <= ARC_STEPS; i++) {
            double ang = i * Math.PI * 2.0D / ARC_STEPS - Math.PI / 2.0D;
            ARC_COS[i] = (float) Math.cos(ang);
            ARC_SIN[i] = (float) Math.sin(ang);
        }
        for (int i = 0; i < DIAL_SEGMENTS; i++) {
            double rad = (120.0D + (i + 0.5D) * (300.0D / DIAL_SEGMENTS)) * (Math.PI / 180.0D);
            DIAL_DX[i] = (int) Math.round(Math.cos(rad) * 21.0D);
            DIAL_DY[i] = (int) Math.round(Math.sin(rad) * 21.0D);
        }
    }

    public static void render(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isSpectator() || !mc.player.isAlive()) {
            return;
        }
        if (!HabiRoles.isHabiRole(mc.player, HabiRoles.DRONE_OPERATOR_ID)) {
            return;
        }

        DroneStatePayload state = DroneOperatorClient.state();
        float timeSec = (DroneOperatorClient.clientTicks() + partialTick) / 20.0F;
        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2;
        int cy = h / 2;
        Font font = mc.font;

        // 1. Destroyed TV Static / Overload Crash Banner (0.5s SIGNAL LOST)
        if (state.transitionMode() == DroneStatePayload.TRANSITION_DESTROYED_STATIC) {
            renderSignalLostScreen(g, font, cx, cy, timeSec);
            return;
        }

        // 2. Recall Laser-Iris Collapse Transition
        if (state.transitionMode() == DroneStatePayload.TRANSITION_RECALLING) {
            float collapse = Mth.clamp((state.transitionTicks() - partialTick) / DroneRules.RECALL_TICKS, 0.05F, 1.0F);
            DroneShaders.renderFpvOverlay(g, timeSec, 0.12F, 0.0F, collapse);
            drawChamferedPanel(g, cx - 108, cy - 13, 216, 26, 0xCC04111D, 0xEE38BDF8);
            g.drawCenteredString(font, Component.translatable("hud.habitrain_core.drone.link_closed"), cx, cy - 4, 0xFF7DD3FC);
            return;
        }

        // 3. Magnetic 1.5s Battery Swap Progress Ring (1P front hover)
        if (state.transitionMode() == DroneStatePayload.TRANSITION_SWAPPING) {
            renderBatterySwapHud(g, font, cx, cy, state, partialTick, timeSec);
            return;
        }

        // 3b. Resupply Take-Out & Unfold Calibration Banner (keeps center screen clear for 3D unfolding drone!)
        if (state.transitionMode() == DroneStatePayload.TRANSITION_UNBOXING && state.transitionTicks() > 0) {
            renderUnboxingHud(g, font, cx, h, state, partialTick);
        }

        ReconDroneEntity drone = DroneOperatorClient.activeDroneEntity();

        // 4. Active AERO-LINK OS FPV Tactical Cockpit (including 0.5s boot sequence)
        if (state.fpvActive() || state.transitionMode() == DroneStatePayload.TRANSITION_DEPLOYING) {
            float bootProgress = 1.0F;
            if (state.transitionMode() == DroneStatePayload.TRANSITION_DEPLOYING) {
                float rem = Math.max(0.0F, state.transitionTicks() - partialTick);
                bootProgress = Mth.clamp(1.0F - rem / DroneRules.DEPLOY_TICKS, 0.08F, 1.0F);
            }

            Vec3 homeVec = new Vec3(state.homeX(), state.homeY(), state.homeZ());
            Vec3 dronePos = drone != null ? drone.getPosition(partialTick) : homeVec;
            double distToHome = drone != null ? dronePos.distanceTo(homeVec) : 0.0D;
            int battery = drone != null ? drone.battery() : state.battery();
            boolean lowBat = DroneRules.isLowBattery(battery);
            boolean weakSig = DroneRules.isWeakSignal(distToHome);
            boolean closeHome = drone != null && distToHome <= DroneRules.RECALL_INTERACT_RANGE + 0.3D;

            float noise = 0.0F;
            if (weakSig) {
                noise += (float) Mth.clamp((distToHome - DroneRules.WEAK_SIGNAL_DISTANCE) / 24.0D, 0.15D, 0.65D);
            }
            if (lowBat) {
                noise = Math.max(noise, 0.26F);
            }
            float dangerPulse = state.bodyDangerNearby() ? (0.55F + 0.45F * Mth.sin(timeSec * 12.0F)) : 0.0F;

            DroneShaders.renderFpvOverlay(g, timeSec, Mth.clamp(noise, 0.0F, 1.0F), dangerPulse, bootProgress);

            if (bootProgress < 0.94F) {
                renderBootSequenceOverlay(g, font, cx, cy, bootProgress, timeSec);
            }

            renderSciFiCompass(g, font, cx, DroneOperatorClient.fpvYaw(), dronePos, homeVec, distToHome, weakSig, timeSec);
            renderCenterGimbalAndHorizon(g, font, cx, cy, drone, partialTick, timeSec);
            renderLeftAvionicsPanel(g, font, cy, drone, state, battery, timeSec);
            renderRightPowerPanel(g, font, w, cy, battery, state.drainPerSec(), state.spareBatteries(), weakSig, timeSec);
            renderBottomTacticalConsole(g, font, cx, h, drone, state, battery, closeHome, timeSec);

            if (state.bodyDangerNearby()) {
                renderBodyProximityAlert(g, font, w, h, cx, timeSec);
            }
            if (lowBat && drone != null) {
                renderLowBatteryReturnGuide(g, font, cx, cy, drone, state, timeSec);
            }
            return;
        }

        // 5. On-Foot Right-Side Circular Drone Status Dial (replaces bottom-center pill)
        renderRightSideDroneDial(g, font, w, cy, state, drone, timeSec);
    }

    // =========================================================================
    // 1. RIGHT-SIDE CIRCULAR DRONE STATUS DIAL (ON-FOOT)
    // =========================================================================

    private static void renderRightSideDroneDial(
            GuiGraphics g,
            Font font,
            int w,
            int cy,
            DroneStatePayload state,
            ReconDroneEntity drone,
            float timeSec
    ) {
        int dialCx = w - 38;
        int dialCy = cy + 16;
        int outerR = 25;

        boolean missingFrame = !state.hasDroneFrame();
        boolean neverHad = missingFrame && !state.everHadDrone();
        boolean crashed = !missingFrame && drone != null && drone.isCrashed();
        boolean airborne = !missingFrame && !crashed && state.droneEntityId() != -1;
        int pct = missingFrame ? 0 : DroneRules.batteryPercent(state.battery());
        boolean lowBat = !missingFrame && DroneRules.isLowBattery(state.battery());

        int themeCol;
        Component statusLabel;
        if (neverHad) {
            themeCol = 0xFFF59E0B;
            statusLabel = Component.translatable("hud.habitrain_core.drone.dial_no_unit");
        } else if (missingFrame) {
            themeCol = 0xFFFF3B5C;
            statusLabel = Component.translatable("hud.habitrain_core.drone.dial_destroyed");
        } else if (crashed) {
            themeCol = 0xFFFBBF24;
            statusLabel = Component.translatable("hud.habitrain_core.drone.dial_crashed");
        } else if (airborne) {
            themeCol = lowBat ? 0xFFFF3B5C : 0xFF38BDF8;
            statusLabel = Component.translatable("hud.habitrain_core.drone.dial_hovering");
        } else {
            themeCol = lowBat ? 0xFFFBBF24 : 0xFF7DD3FC;
            statusLabel = Component.translatable("hud.habitrain_core.drone.dial_stowed");
        }

        // Circular dark-glass radar disc + subtle outer rim glow
        fillCircle(g, dialCx, dialCy, outerR + 1, (0x66 << 24) | (themeCol & 0x00FFFFFF));
        fillCircle(g, dialCx, dialCy, outerR, 0xD805111D);
        fillCircle(g, dialCx, dialCy, 17, 0xE6081A2B);

        // Outer cardinal bezel ticks (N / E / S / W)
        g.fill(dialCx - 1, dialCy - outerR - 2, dialCx + 2, dialCy - outerR + 1, themeCol);
        g.fill(dialCx - outerR - 2, dialCy - 1, dialCx - outerR + 1, dialCy + 2, 0x9964748B);
        g.fill(dialCx + outerR, dialCy - 1, dialCx + outerR + 3, dialCy + 2, 0x9964748B);

        // Rotating radar sweep inside disc when drone is hovering in the world
        if (airborne) {
            float sweepAng = timeSec * 2.8F;
            for (int r = 3; r <= 15; r += 2) {
                int sx = dialCx + Math.round(Mth.cos(sweepAng) * r);
                int sy = dialCy + Math.round(Mth.sin(sweepAng) * r);
                g.fill(sx, sy, sx + 1, sy + 1, 0x6638BDF8);
            }
        }

        // 24-segment circular battery gauge wrapping 300° (120° clockwise to 420°)
        int litSegments = missingFrame ? 0 : Math.round(pct * DIAL_SEGMENTS / 100.0F);
        int arcColor = missingFrame ? 0xFF475569 : (lowBat ? 0xFFFF2E4C : (pct <= 50 ? 0xFFFBBF24 : 0xFF38BDF8));
        for (int i = 0; i < DIAL_SEGMENTS; i++) {
            int px = dialCx + DIAL_DX[i];
            int py = dialCy + DIAL_DY[i];
            boolean lit = i < litSegments;
            g.fill(px - 1, py - 1, px + 2, py + 2, lit ? arcColor : 0x44334155);
        }

        // Custom Quadcopter Silhouette in the center of the circular dial
        renderMiniDroneIconInDial(g, dialCx, dialCy - 1, airborne, crashed, missingFrame, !neverHad && missingFrame, themeCol, timeSec);

        // 3 Spare Battery Cell Pips nestled in the bottom 60° opening of the battery ring
        int spares = state.spareBatteries();
        for (int b = 0; b < DroneRules.MAX_SPARE_BATTERIES; b++) {
            int bx = dialCx - 8 + b * 6;
            int by = dialCy + 19;
            boolean hasCell = b < spares;
            g.fill(bx, by, bx + 4, by + 3, hasCell ? 0xFF34D399 : 0x55334155);
            if (hasCell) {
                g.fill(bx + 1, by - 1, bx + 3, by, 0xFFA7F3D0);
            }
        }

        // Crisp percentage tag at top-inner lip + status pill right below the dial
        String batStr = neverHad ? "NONE" : (missingFrame ? "OFF" : (pct + "%"));
        int batTextCol = neverHad ? 0xFFFCD34D : (missingFrame ? 0xFFFF6B81 : 0xFFE2E8F0);
        poseScaledCenteredString(g, font, batStr, dialCx, dialCy - 14, 0.68F, batTextCol);

        int tagW = Math.max(46, Math.round(font.width(statusLabel) * 0.78F) + 10);
        int tagX = dialCx - tagW / 2;
        int tagY = dialCy + outerR + 4;
        drawChamferedPanel(g, tagX, tagY, tagW, 12, 0xCC05111D, (0xAA << 24) | (themeCol & 0x00FFFFFF));
        poseScaledCenteredString(g, font, statusLabel.getString(), dialCx, tagY + 3, 0.78F, themeCol);
    }

    private static void renderMiniDroneIconInDial(
            GuiGraphics g,
            int cx,
            int cy,
            boolean airborne,
            boolean crashed,
            boolean missingFrame,
            boolean showDestroyedSlash,
            int col,
            float timeSec
    ) {
        int armReach = airborne ? 8 : 6;
        int armCol = missingFrame ? 0x8864748B : 0xCC94A3B8;
        int rotorCol = missingFrame ? 0x55475569 : (crashed ? 0xCCFBBF24 : col);

        // 4 diagonal carbon X-arms from center chassis to rotor pods
        for (int sx : new int[]{-1, 1}) {
            for (int sy : new int[]{-1, 1}) {
                for (int step = 2; step <= armReach; step++) {
                    int ax = cx + sx * step;
                    int ay = cy + sy * step;
                    g.fill(ax, ay, ax + 1, ay + 1, armCol);
                }
                int rx = cx + sx * (armReach + 1);
                int ry = cy + sy * (armReach + 1);
                // Rotor shroud ring
                drawTinyRotorRing(g, rx, ry, rotorCol, airborne, timeSec + (sx * sy * 0.6F));
            }
        }

        // Central quadcopter carbon fuselage + forward cyan gimbal nose pip
        int bodyCol = missingFrame ? 0xFF475569 : 0xFFE2E8F0;
        g.fill(cx - 2, cy - 3, cx + 3, cy + 4, 0xFF0F172A);
        g.fill(cx - 1, cy - 2, cx + 2, cy + 3, bodyCol);
        g.fill(cx, cy - 4, cx + 1, cy - 2, showDestroyedSlash ? 0xFFFF3B5C : (missingFrame ? 0xFFF59E0B : 0xFF38BDF8));

        if (showDestroyedSlash) {
            // Crisp crimson X slash across destroyed frame
            for (int d = -5; d <= 5; d++) {
                g.fill(cx + d, cy + d, cx + d + 2, cy + d + 1, 0xFFFF2E4C);
                g.fill(cx + d, cy - d, cx + d + 2, cy - d + 1, 0xFFFF2E4C);
            }
        }
    }

    private static void drawTinyRotorRing(GuiGraphics g, int rx, int ry, int col, boolean spinning, float phase) {
        int dim = (0x88 << 24) | (col & 0x00FFFFFF);
        g.fill(rx - 2, ry - 3, rx + 3, ry - 2, dim);
        g.fill(rx - 2, ry + 3, rx + 3, ry + 4, dim);
        g.fill(rx - 3, ry - 2, rx - 2, ry + 3, dim);
        g.fill(rx + 3, ry - 2, rx + 4, ry + 3, dim);
        g.fill(rx, ry, rx + 1, ry + 1, col);
        if (spinning) {
            boolean diag = ((int) (phase * 14.0F) & 1) == 0;
            if (diag) {
                g.fill(rx - 2, ry, rx + 3, ry + 1, 0xEEBAE6FD);
            } else {
                g.fill(rx, ry - 2, rx + 1, ry + 3, 0xEEBAE6FD);
            }
        }
    }

    // =========================================================================
    // 2. TOP SCI-FI PANORAMIC UAV COMPASS + HOME BEARING + SIGNAL LINK BAR
    // =========================================================================

    private static void renderSciFiCompass(
            GuiGraphics g,
            Font font,
            int cx,
            float yaw,
            Vec3 dronePos,
            Vec3 homePos,
            double distHome,
            boolean weakSignal,
            float timeSec
    ) {
        int tapeW = 248;
        int x0 = cx - tapeW / 2;
        int y0 = 6;
        int tapeH = 22;

        int borderCol = distHome >= 70.0D ? 0xEEFF2E4C : (weakSignal ? 0xEEFBBF24 : 0xCC38BDF8);
        drawChamferedPanel(g, x0, y0, tapeW, tapeH, 0xCC04101C, borderCol);
        // Luminous top accent rail
        g.fill(x0 + 18, y0, x0 + tapeW - 18, y0 + 1, 0xFF7DD3FC);
        g.fill(x0 + 4, y0 + tapeH - 3, x0 + tapeW - 4, y0 + tapeH - 2, 0x3338BDF8);

        float normYaw = Mth.wrapDegrees(yaw);
        float pxPerDeg = (tapeW - 16) / 160.0F;

        // Degree ticks (every 5° micro, 15° medium, 45° cardinal)
        for (int deg = -180; deg < 180; deg += 5) {
            float diff = Mth.wrapDegrees(deg - normYaw);
            if (Math.abs(diff) > 76.0F) continue;
            int px = cx + Math.round(diff * pxPerDeg);
            boolean cardinal = Math.floorMod(deg, 45) == 0;
            boolean medium = Math.floorMod(deg, 15) == 0;

            if (cardinal) {
                int idx = Math.floorMod(deg / 45, 8);
                boolean isNorth = idx == 4;
                g.fill(px, y0 + 2, px + 1, y0 + 9, isNorth ? 0xFFFDE047 : 0xFF38BDF8);
                poseScaledCenteredString(g, font, CARDINALS[idx], px, y0 + 11, 0.85F,
                        isNorth ? 0xFFFDE047 : 0xFFF1F5F9);
            } else if (medium) {
                g.fill(px, y0 + 3, px + 1, y0 + 8, 0x997DD3FC);
                int headingIdx = Math.floorMod(deg + 180, 360) / 15;
                poseScaledCenteredString(g, font, COMPASS_HEADINGS[headingIdx],
                        px, y0 + 12, 0.58F, 0x8894A3B8);
            } else {
                g.fill(px, y0 + 4, px + 1, y0 + 6, 0x4464748B);
            }
        }

        // Dynamic [❖ HOME] bearing pip right on the compass tape!
        double dx = homePos.x - dronePos.x;
        double dz = homePos.z - dronePos.z;
        if (dx * dx + dz * dz > 0.04D) {
            float homeYaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
            float homeDiff = Mth.wrapDegrees(homeYaw - normYaw);
            if (Math.abs(homeDiff) <= 75.0F) {
                int hx = cx + Math.round(homeDiff * pxPerDeg);
                g.fill(hx - 2, y0 + tapeH - 5, hx + 3, y0 + tapeH - 1, 0xFF22D3EE);
                g.fill(hx - 1, y0 + tapeH - 6, hx + 2, y0 + tapeH, 0xFFBAE6FD);
            } else {
                boolean left = homeDiff < 0.0F;
                int edgeX = left ? (x0 + 14) : (x0 + tapeW - 14);
                int blink = ((int) (timeSec * 6.0F) & 1) == 0 ? 0xFF38BDF8 : 0xFFBAE6FD;
                poseScaledCenteredString(g, font, left ? "◀H" : "H▶", edgeX, y0 + 8, 0.72F, blink);
            }
        }

        // Center digital heading readout box + crimson lubber pointer
        int headingDeg = Math.floorMod(Math.round(normYaw) + 180, 360);
        String dirName = CARDINALS[Math.floorMod(Math.round(normYaw / 45.0F), 8)];
        String hdgBox = String.format(Locale.ROOT, "%03d° %s", headingDeg, dirName);
        g.fill(cx - 22, y0 - 1, cx + 22, y0 + 9, 0xEE07192B);
        drawBorder(g, cx - 22, y0 - 1, 44, 10, 0xEE38BDF8);
        poseScaledCenteredString(g, font, hdgBox, cx, y0 + 1, 0.72F, 0xFFBAE6FD);
        // Lubber needle below heading box
        g.fill(cx - 2, y0 + 9, cx + 3, y0 + 10, 0xFFFF3B5C);
        g.fill(cx - 1, y0 + 10, cx + 2, y0 + 12, 0xFFFF3B5C);

        // Integrated Signal Link & Range Bar directly beneath compass (y = 30)
        int barW = 188;
        int bx = cx - barW / 2;
        int by = y0 + tapeH + 2;
        g.fill(bx, by, bx + barW, by + 11, 0xAA04101C);
        drawBorder(g, bx, by, barW, 11, 0x6638BDF8);

        // 5-bar RF signal icon on left
        int sigQuality = Mth.clamp(100 - (int) Math.round((distHome / DroneRules.MAX_SIGNAL_DISTANCE) * 75.0D), 5, 100);
        int activeBars = distHome >= 68.0D ? 1 : (weakSignal ? 2 : (distHome >= 30.0D ? 4 : 5));
        int sigCol = distHome >= 70.0D ? 0xFFFF3B5C : (weakSignal ? 0xFFFBBF24 : 0xFF38BDF8);
        for (int b = 0; b < 5; b++) {
            int bh = 2 + b;
            g.fill(bx + 5 + b * 3, by + 8 - bh, bx + 7 + b * 3, by + 8, b < activeBars ? sigCol : 0x44475569);
        }

        // Range progress track (0..72m) with 48m weak-zone marker
        int trackX = bx + 24;
        int trackW = 62;
        g.fill(trackX, by + 4, trackX + trackW, by + 7, 0x551E293B);
        int fillW = Mth.clamp((int) Math.round((distHome / DroneRules.MAX_SIGNAL_DISTANCE) * trackW), 1, trackW);
        g.fill(trackX, by + 4, trackX + fillW, by + 7, sigCol);
        int weakMarkX = trackX + Math.round((float) (DroneRules.WEAK_SIGNAL_DISTANCE / DroneRules.MAX_SIGNAL_DISTANCE) * trackW);
        g.fill(weakMarkX, by + 3, weakMarkX + 1, by + 8, 0xCCFBBF24);

        String telemetry = String.format(Locale.ROOT, "LINK %d%% · HOME %.1fm / 72m", sigQuality, distHome);
        poseScaledString(g, font, telemetry, trackX + trackW + 5, by + 3, 0.68F, sigCol);

        if (weakSignal) {
            g.drawCenteredString(font, Component.translatable("hud.habitrain_core.drone.weak_signal"),
                    cx, by + 13, 0xFFFBBF24);
        }
    }

    // =========================================================================
    // 3. CENTER GIMBAL VIEWFINDER & ARTIFICIAL HORIZON PITCH/ROLL LADDER
    // =========================================================================

    private static void renderCenterGimbalAndHorizon(
            GuiGraphics g,
            Font font,
            int cx,
            int cy,
            ReconDroneEntity drone,
            float partialTick,
            float timeSec
    ) {
        boolean charging = drone != null && drone.chargeTicks() > 0;
        boolean sprinting = drone != null && drone.isDroneSprinting();
        int mainCol = charging ? 0xEEFF2442 : (sprinting ? 0xCC7DD3FC : 0xAA38BDF8);

        float pitch = DroneOperatorClient.fpvPitch();
        float rollRad = -DroneOperatorClient.cameraRollDegrees() * Mth.DEG_TO_RAD;
        float cosR = Mth.cos(rollRad);
        float sinR = Mth.sin(rollRad);

        // Artificial Horizon Line & Pitch Rungs (-30, -15, 0, +15, +30) on left & right of reticle
        for (int rungDeg : new int[]{-30, -15, 0, 15, 30}) {
            float relDeg = rungDeg - pitch;
            if (Math.abs(relDeg) > 26.0F) continue;
            float offsetY = -relDeg * 1.45F;
            int innerR = rungDeg == 0 ? 58 : 64;
            int outerR = rungDeg == 0 ? 88 : 78;
            int rungCol = rungDeg == 0 ? 0x9938BDF8 : 0x557DD3FC;

            for (int side : new int[]{-1, 1}) {
                for (int r = innerR; r <= outerR; r += (rungDeg < 0 ? 4 : 2)) {
                    int px = cx + Math.round(side * r * cosR - offsetY * sinR);
                    int py = cy + Math.round(side * r * sinR + offsetY * cosR);
                    g.fill(px, py, px + 2, py + 1, rungCol);
                }
            }
        }
        poseScaledString(g, font, String.format(Locale.ROOT, "%+03d°", Math.round(-pitch)),
                cx + 60, cy - 3, 0.65F, 0x997DD3FC);

        // Dynamic Gimbal Corner Brackets
        int bx = charging ? 42 : (sprinting ? 56 : 50);
        int by = charging ? 26 : (sprinting ? 36 : 32);
        int len = 11;

        g.fill(cx - bx, cy - by, cx - bx + len, cy - by + 1, mainCol);
        g.fill(cx - bx, cy - by, cx - bx + 1, cy - by + len, mainCol);
        g.fill(cx + bx - len, cy - by, cx + bx, cy - by + 1, mainCol);
        g.fill(cx + bx - 1, cy - by, cx + bx, cy - by + len, mainCol);
        g.fill(cx - bx, cy + by - 1, cx - bx + len, cy + by, mainCol);
        g.fill(cx - bx, cy + by - len, cx - bx + 1, cy + by, mainCol);
        g.fill(cx + bx - len, cy + by - 1, cx + bx, cy + by, mainCol);
        g.fill(cx + bx - 1, cy + by - len, cx + bx, cy + by, mainCol);

        // Precision mil-dot crosshairs (open center pixel for unobstructed aiming)
        g.fill(cx - 8, cy, cx - 3, cy + 1, mainCol);
        g.fill(cx + 4, cy, cx + 9, cy + 1, mainCol);
        g.fill(cx, cy - 8, cx + 1, cy - 3, mainCol);
        g.fill(cx, cy + 4, cx + 1, cy + 9, mainCol);
        g.fill(cx - 16, cy, cx - 15, cy + 1, 0x667DD3FC);
        g.fill(cx + 16, cy, cx + 17, cy + 1, 0x667DD3FC);

        if (charging && drone != null) {
            float lockProg = 1.0F - Mth.clamp((drone.chargeTicks() - partialTick) / DroneRules.LETHAL_WINDUP_TICKS, 0.0F, 1.0F);
            drawSmoothArc(g, cx, cy, 16, 18, lockProg, 0xFFFF2442, 0x447F1D1D);
            g.drawCenteredString(font, Component.translatable("hud.habitrain_core.drone.locking"),
                    cx, cy - by - 13, 0xFFFF3B5C);
        }
    }

    // =========================================================================
    // 4. LEFT WING — FLIGHT AVIONICS & SONAR PULSE SCANNER
    // =========================================================================

    private static void renderLeftAvionicsPanel(
            GuiGraphics g,
            Font font,
            int cy,
            ReconDroneEntity drone,
            DroneStatePayload state,
            int battery,
            float timeSec
    ) {
        int x0 = 10;
        int y0 = cy - 44;
        int pw = 74;
        int ph = 88;

        drawChamferedPanel(g, x0, y0, pw, ph, 0xB804101C, 0x8838BDF8);
        g.fill(x0, y0 + 11, x0 + pw, y0 + 12, 0x4438BDF8);
        poseScaledString(g, font, "SYS // AVIONICS", x0 + 5, y0 + 3, 0.65F, 0xFF7DD3FC);

        double speedBps = drone != null ? drone.getDeltaMovement().length() * 20.0D : 0.0D;
        boolean sprinting = drone != null && drone.isDroneSprinting();
        String modeTag = speedBps < 0.35D ? "◈ HOVER" : (sprinting ? "⚡ BOOST" : "▸ CRUISE");
        int modeCol = sprinting ? 0xFFFDE047 : (speedBps < 0.35D ? 0xFF94A3B8 : 0xFF38BDF8);
        poseScaledString(g, font, modeTag, x0 + 5, y0 + 16, 0.80F, modeCol);

        double relAlt = drone != null ? (drone.getY() - (state.homeY() - 1.1D)) : 0.0D;
        poseScaledString(g, font, String.format(Locale.ROOT, "SPD  %.1f m/s", speedBps), x0 + 5, y0 + 28, 0.68F, 0xFFE2E8F0);
        poseScaledString(g, font, String.format(Locale.ROOT, "ALT  %+.1f m", relAlt), x0 + 5, y0 + 38, 0.68F, 0xFFCBD5E1);

        // Sonar Pulse Scan [G] Tactical Pod
        g.fill(x0 + 4, y0 + 50, x0 + pw - 4, y0 + 51, 0x3338BDF8);
        int radarCx = x0 + 16;
        int radarCy = y0 + 68;
        boolean lowBat = DroneRules.isLowBattery(battery);
        int cd = state.scanCooldownTicks();
        boolean ready = !lowBat && battery >= DroneRules.PULSE_SCAN_COST && cd <= 0;

        int sonarCol = ready ? 0xFF34D399 : (lowBat ? 0xFFFF3B5C : 0xFFFBBF24);
        fillCircle(g, radarCx, radarCy, 9, 0xDD07192B);
        drawSmoothArc(g, radarCx, radarCy, 8, 9,
                cd > 0 ? (1.0F - (float) cd / DroneRules.PULSE_SCAN_COOLDOWN_TICKS) : 1.0F,
                sonarCol, 0x44334155);
        if (ready) {
            float a = timeSec * 3.4F;
            for (int r = 1; r <= 7; r++) {
                g.fill(radarCx + Math.round(Mth.cos(a) * r), radarCy + Math.round(Mth.sin(a) * r),
                        radarCx + Math.round(Mth.cos(a) * r) + 1, radarCy + Math.round(Mth.sin(a) * r) + 1, 0xFF6EE7B7);
            }
        }

        String scanKey = "[" + DroneOperatorClient.scanKeyLabel() + "]";
        poseScaledString(g, font, "SONAR " + scanKey, x0 + 29, y0 + 57, 0.66F, 0xFFE2E8F0);
        String sonarState = lowBat
                ? Component.translatable("hud.habitrain_core.drone.sonar_low").getString()
                : (cd > 0 ? String.format(Locale.ROOT, "CD %.1fs", cd / 20.0F)
                : Component.translatable("hud.habitrain_core.drone.sonar_ready").getString());
        poseScaledString(g, font, sonarState, x0 + 29, y0 + 68, 0.68F, sonarCol);
    }

    // =========================================================================
    // 5. RIGHT WING — SEGMENTED POWER CORE & SPARE BATTERY CELLS
    // =========================================================================

    private static void renderRightPowerPanel(
            GuiGraphics g,
            Font font,
            int w,
            int cy,
            int battery,
            float drainPerSec,
            int spares,
            boolean weakSig,
            float timeSec
    ) {
        int pw = 74;
        int ph = 88;
        int x0 = w - pw - 10;
        int y0 = cy - 44;

        int pct = DroneRules.batteryPercent(battery);
        boolean low = DroneRules.isLowBattery(battery);
        int borderCol = low && ((int) (timeSec * 6.0F) & 1) == 0 ? 0xEEFF2E4C : 0x8838BDF8;
        drawChamferedPanel(g, x0, y0, pw, ph, 0xB804101C, borderCol);
        g.fill(x0, y0 + 11, x0 + pw, y0 + 12, 0x4438BDF8);
        poseScaledString(g, font, "PWR // ENERGY", x0 + 5, y0 + 3, 0.65F, 0xFF7DD3FC);

        int barColor = low ? 0xFFFF2E4C : (pct <= 50 ? 0xFFFBBF24 : 0xFF38BDF8);

        // Vertical 12-segment energy column on right side of panel
        int colX = x0 + pw - 17;
        int colY = y0 + 16;
        for (int seg = 0; seg < 12; seg++) {
            int sy = colY + (11 - seg) * 4;
            boolean lit = pct >= (seg * 100 / 12 + 4);
            g.fill(colX, sy, colX + 11, sy + 3, lit ? barColor : 0x44334155);
        }

        // Crisp percentage + drain + endurance readout on left of column
        poseScaledString(g, font, pct + "%", x0 + 6, y0 + 16, 1.05F, barColor);
        String est = drainPerSec > 0.1F
                ? String.format(Locale.ROOT, "EST %02ds", Math.max(0, Math.round(battery / drainPerSec)))
                : "EST --";
        poseScaledString(g, font, est, x0 + 6, y0 + 30, 0.66F, 0xFFE2E8F0);
        String drainTag = String.format(Locale.ROOT, "-%.0f/s%s", drainPerSec, weakSig ? " RF!" : "");
        poseScaledString(g, font, drainTag, x0 + 6, y0 + 40, 0.64F, weakSig ? 0xFFFBBF24 : 0xFF94A3B8);

        // 3 Visual Spare LiPo Battery Cells at bottom of power wing
        g.fill(x0 + 4, y0 + 66, x0 + pw - 4, y0 + 67, 0x3338BDF8);
        poseScaledString(g, font, "CELLS", x0 + 6, y0 + 72, 0.64F, 0xFF94A3B8);
        for (int i = 0; i < DroneRules.MAX_SPARE_BATTERIES; i++) {
            int cxCell = x0 + 35 + i * 11;
            int cyCell = y0 + 71;
            boolean filled = i < spares;
            g.fill(cxCell + 2, cyCell - 1, cxCell + 6, cyCell, filled ? 0xFFFDE047 : 0x44475569);
            g.fill(cxCell, cyCell, cxCell + 8, cyCell + 11, filled ? 0xCC064E3B : 0x441E293B);
            drawBorder(g, cxCell, cyCell, 8, 11, filled ? 0xFF34D399 : 0x55475569);
            if (filled) {
                g.fill(cxCell + 2, cyCell + 2, cxCell + 6, cyCell + 9, 0xFF34D399);
            }
        }
    }

    // =========================================================================
    // 6. BOTTOM TACTICAL PAYLOAD & INTERACTIVE KEYCAP CONSOLE (NO "致命火控")
    // =========================================================================

    private static void renderBottomTacticalConsole(
            GuiGraphics g,
            Font font,
            int cx,
            int h,
            ReconDroneEntity drone,
            DroneStatePayload state,
            int battery,
            boolean closeHome,
            float timeSec
    ) {
        int cardW = 276;
        int cardH = 30;
        int x0 = cx - cardW / 2;
        int y0 = h - 50;

        DronePayloadCategory cat = drone != null ? drone.payloadCategory() : DronePayloadCategory.NONE;
        ItemStack payload = drone != null ? drone.payloadStack() : ItemStack.EMPTY;
        boolean hasItem = cat.hasPayload() && !payload.isEmpty();
        boolean isLethal = cat.isLethal();

        int accentCol = hasItem ? (0xEE000000 | cat.alertTier().rgb()) : 0x9938BDF8;
        if (isLethal && ((int) (timeSec * 5.0F) & 1) == 0) {
            accentCol = 0xFFFF2E4C;
        }
        drawChamferedPanel(g, x0, y0, cardW, cardH, 0xCC04101C, accentCol);

        // Left bevel item bay box (24x24)
        int slotX = x0 + 5;
        int slotY = y0 + 3;
        g.fill(slotX, slotY, slotX + 24, slotY + 24, hasItem ? 0xDD0A1E32 : 0x88081523);
        drawBorder(g, slotX, slotY, 24, 24, accentCol);

        if (hasItem) {
            g.renderItem(payload, slotX + 4, slotY + 4);
            String itemLabel = DroneMessages.stripCodes(payload.getHoverName().getString());
            if (isLethal) {
                // Single-use lethal weapon: warns clearly that 1 trigger crashes/destroys the drone!
                Component title = Component.translatable("hud.habitrain_core.drone.payload_lethal_title", itemLabel);
                Component warn = Component.translatable("hud.habitrain_core.drone.payload_lethal_sub");
                poseScaledString(g, font, title.getString(), x0 + 34, y0 + 5, 0.80F, 0xFFFF6B81);
                poseScaledString(g, font, warn.getString(), x0 + 34, y0 + 17, 0.70F, 0xFFFECACA);
            } else {
                int costPct = DroneRules.batteryPercent(DronePayloadHandler.nonLethalBatteryCost(payload, cat));
                Component line1 = Component.translatable("hud.habitrain_core.drone.payload_slot", itemLabel, costPct);
                Component line2 = Component.translatable("hud.habitrain_core.drone.payload_reusable");
                poseScaledString(g, font, line1.getString(), x0 + 34, y0 + 5, 0.80F, 0xFFF8FAFC);
                poseScaledString(g, font, line2.getString(), x0 + 34, y0 + 17, 0.70F, 0xFF7DD3FC);
            }
        } else {
            poseScaledCenteredString(g, font, "❖", slotX + 12, slotY + 8, 0.9F, 0x7738BDF8);
            poseScaledString(g, font, Component.translatable("hud.habitrain_core.drone.payload_empty").getString(),
                    x0 + 34, y0 + 5, 0.80F, 0xFFCBD5E1);
            poseScaledString(g, font, Component.translatable("hud.habitrain_core.drone.payload_empty_sub").getString(),
                    x0 + 34, y0 + 17, 0.68F, 0xFF64748B);
        }

        // Interactive Tactical Keycap Strip below console (y = h - 16)
        boolean scanReady = !DroneRules.isLowBattery(battery)
                && battery >= DroneRules.PULSE_SCAN_COST && state.scanCooldownTicks() <= 0;
        boolean swapReady = closeHome && state.spareBatteries() > 0 && battery < DroneRules.MAX_BATTERY;

        String[] keys = {
                DroneOperatorClient.attackKeyLabel(),
                DroneOperatorClient.useKeyLabel(),
                DroneOperatorClient.scanKeyLabel(),
                DroneOperatorClient.swapBatteryKeyLabel(),
                DroneOperatorClient.foldRecallKeyLabel()
        };
        String[] actions = {
                Component.translatable("hud.habitrain_core.drone.key_fire").getString(),
                Component.translatable("hud.habitrain_core.drone.key_body").getString(),
                Component.translatable("hud.habitrain_core.drone.key_scan").getString(),
                Component.translatable("hud.habitrain_core.drone.key_swap").getString(),
                Component.translatable("hud.habitrain_core.drone.key_recall").getString()
        };
        int[] statesCol = {
                hasItem ? (isLethal ? 0xFFFF3B5C : 0xFF38BDF8) : 0xFF64748B,
                0xFF7DD3FC,
                scanReady ? 0xFF34D399 : 0xFF64748B,
                swapReady ? 0xFF34D399 : 0xFF64748B,
                closeHome ? 0xFF38BDF8 : 0xFF64748B
        };

        int totalW = 0;
        int[] pillWidths = new int[5];
        for (int i = 0; i < 5; i++) {
            pillWidths[i] = Math.round((font.width(keys[i]) + font.width(actions[i])) * 0.72F) + 14;
            totalW += pillWidths[i] + (i > 0 ? 5 : 0);
        }

        int kx = cx - totalW / 2;
        int ky = h - 16;
        for (int i = 0; i < 5; i++) {
            drawKeycapBadge(g, font, kx, ky, pillWidths[i], keys[i], actions[i], statesCol[i]);
            kx += pillWidths[i] + 5;
        }
    }

    private static void drawKeycapBadge(
            GuiGraphics g, Font font, int x, int y, int w, String key, String action, int accent
    ) {
        g.fill(x, y, x + w, y + 12, 0xCC05111D);
        drawBorder(g, x, y, w, 12, (0x88 << 24) | (accent & 0x00FFFFFF));
        int kw = Math.round(font.width(key) * 0.72F) + 6;
        g.fill(x + 1, y + 1, x + kw, y + 11, (0x44 << 24) | (accent & 0x00FFFFFF));
        poseScaledString(g, font, key, x + 3, y + 3, 0.72F, 0xFFF8FAFC);
        poseScaledString(g, font, action, x + kw + 3, y + 3, 0.72F, accent);
    }

    // =========================================================================
    // 7. TRANSITION & ALERT OVERLAYS
    // =========================================================================

    private static void renderBootSequenceOverlay(GuiGraphics g, Font font, int cx, int cy, float bootProgress, float timeSec) {
        drawSmoothArc(g, cx, cy, 26, 28, bootProgress, 0xEE38BDF8, 0x3338BDF8);
        drawSmoothArc(g, cx, cy, 32, 33, 1.0F - bootProgress * 0.5F, 0x887DD3FC, 0x00000000);
        poseScaledCenteredString(g, font,
                Component.translatable("hud.habitrain_core.drone.booting").getString(),
                cx, cy - 46, 0.85F, 0xFF7DD3FC);
        poseScaledCenteredString(g, font,
                String.format(Locale.ROOT, "AERO-LINK OS v4.2 // SYNC %d%%", Math.round(bootProgress * 100.0F)),
                cx, cy + 38, 0.68F, 0xAA38BDF8);
    }

    private static void renderUnboxingHud(
            GuiGraphics g, Font font, int cx, int h, DroneStatePayload state, float partialTick
    ) {
        float remTicks = Math.max(0.0F, state.transitionTicks() - partialTick);
        float progress = Mth.clamp(1.0F - remTicks / (float) DroneRules.UNBOX_TICKS, 0.0F, 1.0F);

        int pw = 204;
        int ph = 22;
        int x0 = cx - pw / 2;
        int y0 = h - 68;
        drawChamferedPanel(g, x0, y0, pw, ph, 0xCC04121E, 0xDD38BDF8);
        poseScaledCenteredString(g, font,
                Component.translatable("hud.habitrain_core.drone.unboxing", Math.round(progress * 100.0F)).getString(),
                cx, y0 + 4, 0.78F, 0xFF7DD3FC);
        int barX = x0 + 12;
        int barW = pw - 24;
        g.fill(barX, y0 + 15, barX + barW, y0 + 18, 0x551E293B);
        g.fill(barX, y0 + 15, barX + Math.round(barW * progress), y0 + 18, 0xFF34D399);
    }

    private static void renderBatterySwapHud(
            GuiGraphics g, Font font, int cx, int cy, DroneStatePayload state, float partialTick, float timeSec
    ) {
        float remTicks = Math.max(0.0F, state.transitionTicks() - partialTick);
        float progress = Mth.clamp(1.0F - remTicks / DroneRules.BATTERY_SWAP_TICKS, 0.0F, 1.0F);
        fillCircle(g, cx, cy, 24, 0xAA04121E);
        drawSmoothArc(g, cx, cy, 21, 24, progress, 0xFF34D399, 0x441E293B);
        poseScaledCenteredString(g, font, Math.round(progress * 100.0F) + "%", cx, cy - 3, 0.92F, 0xFF6EE7B7);

        drawChamferedPanel(g, cx - 78, cy + 30, 156, 16, 0xCC04121E, 0xCC34D399);
        g.drawCenteredString(font, Component.translatable("hud.habitrain_core.drone.swapping",
                String.format(Locale.ROOT, "%.1f", remTicks / 20.0F)), cx, cy + 34, 0xFF86EFAC);
    }

    private static void renderSignalLostScreen(GuiGraphics g, Font font, int cx, int cy, float timeSec) {
        DroneShaders.renderFpvOverlay(g, timeSec, 1.0F, 0.92F, 1.0F);
        int pw = 286;
        int ph = 52;
        drawChamferedPanel(g, cx - pw / 2, cy - ph / 2, pw, ph, 0xDD1F050A, 0xFFFF2E4C);
        g.fill(cx - pw / 2 + 12, cy - ph / 2, cx + pw / 2 - 12, cy - ph / 2 + 2, 0xFFFF3B5C);
        g.drawCenteredString(font, Component.translatable("hud.habitrain_core.drone.signal_lost"), cx, cy - 15, 0xFFFF3B5C);
        g.drawCenteredString(font, Component.translatable("hud.habitrain_core.drone.signal_lost_sub"), cx, cy + 4, 0xFFFECACA);
    }

    private static void renderBodyProximityAlert(GuiGraphics g, Font font, int w, int h, int cx, float timeSec) {
        int alpha = 120 + (int) (95 * Math.abs(Mth.sin(timeSec * 9.0F)));
        int red = (alpha << 24) | 0xFF1E38;
        g.fill(0, 0, w, 3, red);
        g.fill(0, h - 3, w, h, red);
        g.fill(0, 3, 3, h - 3, red);
        g.fill(w - 3, 3, w, h - 3, red);
        drawChamferedPanel(g, cx - 104, 46, 208, 15, 0xCC28060D, 0xFFFF3B5C);
        g.drawCenteredString(font, Component.translatable("hud.habitrain_core.drone.body_danger"), cx, 50, 0xFFFF6B81);
    }

    private static void renderLowBatteryReturnGuide(
            GuiGraphics g, Font font, int cx, int cy,
            ReconDroneEntity drone, DroneStatePayload state, float timeSec
    ) {
        double dx = state.homeX() - drone.getX();
        double dz = state.homeZ() - drone.getZ();
        float targetYaw = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
        float relAngleRad = Mth.wrapDegrees(targetYaw - DroneOperatorClient.fpvYaw()) * Mth.DEG_TO_RAD;

        int radiusX = Math.min(cx - 88, 112);
        int radiusY = Math.min(cy - 54, 72);
        int ax = cx + Math.round(Mth.sin(relAngleRad) * radiusX);
        int ay = cy - Math.round(Mth.cos(relAngleRad) * radiusY);
        int col = ((int) (timeSec * 8.0F) & 1) == 0 ? 0xFFFF3B5C : 0xFFFBBF24;
        g.drawCenteredString(font, Component.translatable("hud.habitrain_core.drone.low_bat_return"), ax, ay, col);
    }

    // =========================================================================
    // DRAWING PRIMITIVES
    // =========================================================================

    private static void fillCircle(GuiGraphics g, int cx, int cy, int r, int argb) {
        if ((argb >>> 24) == 0 || r <= 0) return;
        Matrix4f mat = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        float rf = r + 0.5F;
        for (int i = 0; i < CIRCLE_SEGS; i += 2) {
            vc.addVertex(mat, cx, cy, 0.0F).setColor(argb);
            vc.addVertex(mat, cx + CIRCLE_COS[i] * rf, cy + CIRCLE_SIN[i] * rf, 0.0F).setColor(argb);
            vc.addVertex(mat, cx + CIRCLE_COS[i + 1] * rf, cy + CIRCLE_SIN[i + 1] * rf, 0.0F).setColor(argb);
            vc.addVertex(mat, cx + CIRCLE_COS[i + 2] * rf, cy + CIRCLE_SIN[i + 2] * rf, 0.0F).setColor(argb);
        }
    }

    private static void drawSmoothArc(GuiGraphics g, int cx, int cy, int rInner, int rOuter, float progress, int activeCol, int dimCol) {
        int lit = Math.round(Mth.clamp(progress, 0.0F, 1.0F) * ARC_STEPS);
        Matrix4f mat = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        float r0 = Math.max(0.0F, rInner);
        float r1 = rOuter + 1.0F;
        for (int i = 0; i < ARC_STEPS; i++) {
            int col = i < lit ? activeCol : dimCol;
            if ((col >>> 24) == 0) continue;
            float c0 = ARC_COS[i], s0 = ARC_SIN[i];
            float c1 = ARC_COS[i + 1], s1 = ARC_SIN[i + 1];
            vc.addVertex(mat, cx + c0 * r0, cy + s0 * r0, 0.0F).setColor(col);
            vc.addVertex(mat, cx + c0 * r1, cy + s0 * r1, 0.0F).setColor(col);
            vc.addVertex(mat, cx + c1 * r1, cy + s1 * r1, 0.0F).setColor(col);
            vc.addVertex(mat, cx + c1 * r0, cy + s1 * r0, 0.0F).setColor(col);
        }
    }

    private static void drawChamferedPanel(GuiGraphics g, int x, int y, int w, int h, int bgCol, int borderCol) {
        g.fill(x + 2, y, x + w - 2, y + h, bgCol);
        g.fill(x, y + 2, x + 2, y + h - 2, bgCol);
        g.fill(x + w - 2, y + 2, x + w, y + h - 2, bgCol);

        g.fill(x + 2, y, x + w - 2, y + 1, borderCol);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, borderCol);
        g.fill(x, y + 2, x + 1, y + h - 2, borderCol);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, borderCol);
        // 4 corner bevel pixels
        g.fill(x + 1, y + 1, x + 2, y + 2, borderCol);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, borderCol);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, borderCol);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, borderCol);
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int col) {
        g.fill(x, y, x + w, y + 1, col);
        g.fill(x, y + h - 1, x + w, y + h, col);
        g.fill(x, y, x + 1, y + h, col);
        g.fill(x + w - 1, y, x + w, y + h, col);
    }

    private static void poseScaledString(GuiGraphics g, Font font, String text, int x, int y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, text, 0, 0, color, true);
        g.pose().popPose();
    }

    private static void poseScaledCenteredString(GuiGraphics g, Font font, String text, int cx, int y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, text, Math.round(-font.width(text) / 2.0F), 0, color, true);
        g.pose().popPose();
    }
}
