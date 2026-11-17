package com.habitrain.core.client.drone;

import com.habitrain.core.game.sre.role.drone.DronePayloadCategory;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import com.habitrain.core.game.sre.role.drone.ReconDroneEntity;
import com.habitrain.core.network.DroneFxPayload;
import com.habitrain.core.network.DroneStatePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.wifi.starrailexpress.index.TMMItems;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/**
 * 3D world-space holographic renderer for {@code habitrain_core:drone_operator}:
 * <ul>
 *   <li>0 -> 25m expanding spherical cyan holographic grid wave on Pulse Scan;</li>
 *   <li>3D corner lock brackets around scanned players & corpses;</li>
 *   <li>Drop landing prediction ring directly beneath the drone in FPV;</li>
 *   <li>Through-wall {@code [❖ HOME · Xm]} return beacon at the operator's body;</li>
 *   <li>3D world burst beams (gun tracer + cross muzzle flash, crimson slash ribbon, golden door data arc).</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class DroneWorldFxRenderer {
    private static boolean registered;

    private DroneWorldFxRenderer() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        WorldRenderEvents.AFTER_TRANSLUCENT.register(DroneWorldFxRenderer::renderWorld);
    }

    private static void renderWorld(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack pose = ctx.matrixStack();
        if (pose == null) return;
        Camera camera = ctx.camera();
        Vec3 camPos = camera.getPosition();
        float partial = ctx.tickCounter().getGameTimeDeltaPartialTick(false);

        List<DroneOperatorClient.PulseWave> waves = DroneOperatorClient.pulseWaves();
        List<DroneOperatorClient.WorldBurst> bursts = DroneOperatorClient.worldBursts();
        DroneStatePayload state = DroneOperatorClient.state();
        ReconDroneEntity drone = DroneOperatorClient.activeDroneEntity();
        boolean inFpv = state.fpvActive() && drone != null;

        if (waves.isEmpty() && bursts.isEmpty() && !inFpv && DroneOperatorClient.activeHighlights().isEmpty()) {
            return;
        }

        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f worldMat = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // Pass 1: Single batched BufferBuilder for all depth-tested world geometry
        BufferBuilder depthBuf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (DroneOperatorClient.PulseWave wave : waves) {
            float progress = Mth.clamp((wave.ageTicks + partial) / 22.0F, 0.0F, 1.0F);
            float radius = (float) (DroneRules.PULSE_SCAN_RADIUS * (1.0F - (1.0F - progress) * (1.0F - progress)));
            int alpha = (int) ((1.0F - progress) * 185.0F);
            if (alpha > 8) {
                emitHoloSphere(depthBuf, worldMat, wave.origin, radius, (alpha << 24) | 0x38BDF8);
            }
        }
        for (DroneOperatorClient.WorldBurst burst : bursts) {
            float life = 1.0F - Mth.clamp((burst.ageTicks + partial) / burst.maxTicks, 0.0F, 1.0F);
            emitBurst(depthBuf, worldMat, burst, life);
        }
        if (inFpv && shouldShowLandingRing(drone)) {
            Vec3 dPos = drone.getPosition(partial);
            BlockHitResult groundHit = mc.level.clip(new ClipContext(
                    dPos, dPos.add(0.0D, -18.0D, 0.0D), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone
            ));
            if (groundHit.getType() != HitResult.Type.MISS) {
                Vec3 land = groundHit.getLocation().add(0.0D, 0.04D, 0.0D);
                int ringCol = (0xAA << 24) | drone.payloadCategory().alertTier().rgb();
                emitHorizontalRing(depthBuf, worldMat, land, 0.65F, 0.74F, ringCol);
                emitBeam(depthBuf, worldMat, dPos.subtract(0.0D, 0.15D, 0.0D), land, 0.012F, (0x55 << 24) | (ringCol & 0x00FFFFFF));
            }
        }
        flushBuffer(depthBuf);

        // Pass 2: Single batched BufferBuilder for through-wall Scanned Target Brackets & HOME Beacon
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder xrayBuf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (DroneOperatorClient.ScanEntry hl : DroneOperatorClient.activeHighlights().values()) {
            Entity entity = mc.level.getEntity(hl.entityId);
            if (entity == null || entity.isRemoved()) continue;
            Vec3 ePos = entity.getPosition(partial).add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
            emitTargetDiamondMarker(xrayBuf, worldMat, ePos, 0.28F, (0xCC << 24) | (hl.rgbColor & 0x00FFFFFF));
        }

        Vec3 home = null;
        double homeDist = 0.0D;
        int beaconCol = 0xFF38BDF8;
        if (inFpv) {
            home = new Vec3(state.homeX(), state.homeY(), state.homeZ());
            homeDist = drone.getPosition(partial).distanceTo(home);
            boolean lowBat = DroneRules.isLowBattery(drone.battery());
            beaconCol = lowBat
                    ? (((DroneOperatorClient.clientTicks() / 4) & 1) == 0 ? 0xFFFF3B5C : 0xFFFBBF24)
                    : 0xFF38BDF8;
            emitTargetDiamondMarker(xrayBuf, worldMat, home, 0.36F, beaconCol);
            emitTargetDiamondMarker(xrayBuf, worldMat, home, 0.22F, (0xAA << 24) | (beaconCol & 0x00FFFFFF));
        }
        flushBuffer(xrayBuf);

        if (inFpv && home != null) {
            // Floating through-wall [❖ HOME · Xm] label (MC 1.21.1 camera.rotation() requires +scale on X!)
            MultiBufferSource.BufferSource immediate = mc.renderBuffers().bufferSource();
            Font font = mc.font;
            pose.pushPose();
            pose.translate(home.x, home.y + 0.58D, home.z);
            pose.mulPose(camera.rotation());
            float scale = (float) Mth.clamp(0.022D + homeDist * 0.0014D, 0.024D, 0.095D);
            pose.scale(scale, -scale, scale);
            String label = String.format(Locale.ROOT, "❖ HOME · %.1fm", homeDist);
            float tx = -font.width(label) / 2.0F;
            font.drawInBatch(label, tx, 0.0F, beaconCol, false,
                    pose.last().pose(), immediate, Font.DisplayMode.SEE_THROUGH, 0x8805121E, LightTexture.FULL_BRIGHT);
            immediate.endBatch();
            pose.popPose();
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    private static void flushBuffer(BufferBuilder buf) {
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    private static boolean shouldShowLandingRing(ReconDroneEntity drone) {
        DronePayloadCategory cat = drone.payloadCategory();
        ItemStack st = drone.payloadStack();
        if (!cat.hasPayload() || st.isEmpty()) return false;
        if (cat == DronePayloadCategory.LETHAL_EXPLOSIVE || cat == DronePayloadCategory.FAKE_EXPLOSIVE
                || cat == DronePayloadCategory.DECOY_SMOKE) {
            return true;
        }
        if (cat == DronePayloadCategory.CONTROL) {
            return true;
        }
        if (cat == DronePayloadCategory.INFILTRATION_LOGISTICS) {
            return !st.is(TMMItems.LOCKPICK) && !st.is(TMMItems.CROWBAR)
                    && !st.is(TMMItems.MONITOR_BROKEN) && !st.is(TMMItems.BLACKOUT);
        }
        return false;
    }

    private static void emitBurst(BufferBuilder buf, Matrix4f mat, DroneOperatorClient.WorldBurst burst, float life) {
        int alpha = Mth.clamp((int) (life * 235), 10, 245);
        switch (burst.fxType) {
            case DroneFxPayload.FX_GUN_SHOT -> {
                emitBeam(buf, mat, burst.from, burst.to, 0.035F * life, (alpha << 24) | 0xFF3B5C);
                emitCrossStar(buf, mat, burst.from, 0.34F * life, (alpha << 24) | 0xFEF08A);
            }
            case DroneFxPayload.FX_BLADE_SLASH -> {
                emitBeam(buf, mat, burst.from, burst.to, 0.055F * life, (alpha << 24) | 0xEF4444);
                emitCrossStar(buf, mat, burst.to, 0.28F * life, (alpha << 24) | 0xFCA5A5);
            }
            case DroneFxPayload.FX_DOOR_ARC -> {
                Vec3 mid = burst.from.lerp(burst.to, 0.5D).add(0.08D, 0.14D, -0.06D);
                emitBeam(buf, mat, burst.from, mid, 0.026F * life, (alpha << 24) | 0xFBBF24);
                emitBeam(buf, mat, mid, burst.to, 0.026F * life, (alpha << 24) | 0x38BDF8);
            }
            case DroneFxPayload.FX_NET_SHOT, DroneFxPayload.FX_RECALL_STREAM -> {
                emitBeam(buf, mat, burst.from, burst.to, 0.03F * life, (alpha << 24) | 0x38BDF8);
            }
            default -> emitCrossStar(buf, mat, burst.from, 0.22F * life, (alpha << 24) | 0x38BDF8);
        }
    }

    private static void emitHoloSphere(BufferBuilder buf, Matrix4f mat, Vec3 center, float radius, int argb) {
        int segs = 32;
        float thick = Math.max(0.04F, radius * 0.018F);
        for (int i = 0; i < segs; i++) {
            float a0 = (float) (i * Math.PI * 2.0D / segs);
            float a1 = (float) ((i + 1) * Math.PI * 2.0D / segs);
            float c0 = Mth.cos(a0) * radius, s0 = Mth.sin(a0) * radius;
            float c1 = Mth.cos(a1) * radius, s1 = Mth.sin(a1) * radius;

            quad(buf, mat,
                    (float) center.x + c0, (float) center.y - thick, (float) center.z + s0,
                    (float) center.x + c1, (float) center.y - thick, (float) center.z + s1,
                    (float) center.x + c1, (float) center.y + thick, (float) center.z + s1,
                    (float) center.x + c0, (float) center.y + thick, (float) center.z + s0, argb);
            quad(buf, mat,
                    (float) center.x + c0, (float) center.y + s0, (float) center.z - thick,
                    (float) center.x + c1, (float) center.y + s1, (float) center.z - thick,
                    (float) center.x + c1, (float) center.y + s1, (float) center.z + thick,
                    (float) center.x + c0, (float) center.y + s0, (float) center.z + thick, argb);
            quad(buf, mat,
                    (float) center.x - thick, (float) center.y + c0, (float) center.z + s0,
                    (float) center.x - thick, (float) center.y + c1, (float) center.z + s1,
                    (float) center.x + thick, (float) center.y + c1, (float) center.z + s1,
                    (float) center.x + thick, (float) center.y + c0, (float) center.z + s0, argb);
        }
    }

    private static void emitHorizontalRing(BufferBuilder buf, Matrix4f mat, Vec3 center, float r0, float r1, int argb) {
        int segs = 20;
        for (int i = 0; i < segs; i++) {
            if (i % 5 == 4) continue;
            float a0 = (float) (i * Math.PI * 2.0D / segs);
            float a1 = (float) ((i + 0.85D) * Math.PI * 2.0D / segs);
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            quad(buf, mat,
                    (float) center.x + c0 * r0, (float) center.y, (float) center.z + s0 * r0,
                    (float) center.x + c1 * r0, (float) center.y, (float) center.z + s1 * r0,
                    (float) center.x + c1 * r1, (float) center.y, (float) center.z + s1 * r1,
                    (float) center.x + c0 * r1, (float) center.y, (float) center.z + s0 * r1, argb);
        }
    }

    private static void emitTargetDiamondMarker(BufferBuilder buf, Matrix4f mat, Vec3 c, float r, int argb) {
        float t = 0.035F;
        quad(buf, mat, (float) c.x, (float) c.y + r, (float) c.z, (float) c.x + r, (float) c.y, (float) c.z,
                (float) c.x + r - t, (float) c.y, (float) c.z, (float) c.x, (float) c.y + r - t, (float) c.z, argb);
        quad(buf, mat, (float) c.x + r, (float) c.y, (float) c.z, (float) c.x, (float) c.y - r, (float) c.z,
                (float) c.x, (float) c.y - r + t, (float) c.z, (float) c.x + r - t, (float) c.y, (float) c.z, argb);
        quad(buf, mat, (float) c.x, (float) c.y - r, (float) c.z, (float) c.x - r, (float) c.y, (float) c.z,
                (float) c.x - r + t, (float) c.y, (float) c.z, (float) c.x, (float) c.y - r + t, (float) c.z, argb);
        quad(buf, mat, (float) c.x - r, (float) c.y, (float) c.z, (float) c.x, (float) c.y + r, (float) c.z,
                (float) c.x, (float) c.y + r - t, (float) c.z, (float) c.x - r + t, (float) c.y, (float) c.z, argb);
    }

    private static void emitCrossStar(BufferBuilder buf, Matrix4f mat, Vec3 c, float r, int argb) {
        if (r <= 0.005F) return;
        float w = r * 0.22F;
        quad(buf, mat, (float) c.x - r, (float) c.y - w, (float) c.z, (float) c.x + r, (float) c.y - w, (float) c.z,
                (float) c.x + r, (float) c.y + w, (float) c.z, (float) c.x - r, (float) c.y + w, (float) c.z, argb);
        quad(buf, mat, (float) c.x - w, (float) c.y - r, (float) c.z, (float) c.x + w, (float) c.y - r, (float) c.z,
                (float) c.x + w, (float) c.y + r, (float) c.z, (float) c.x - w, (float) c.y + r, (float) c.z, argb);
        quad(buf, mat, (float) c.x, (float) c.y - w, (float) c.z - r, (float) c.x, (float) c.y - w, (float) c.z + r,
                (float) c.x, (float) c.y + w, (float) c.z + r, (float) c.x, (float) c.y + w, (float) c.z - r, argb);
    }

    private static void emitBeam(BufferBuilder buf, Matrix4f mat, Vec3 from, Vec3 to, float halfWidth, int argb) {
        if (from.distanceToSqr(to) < 1.0E-5D || halfWidth <= 0.001F) return;
        quad(buf, mat,
                (float) from.x - halfWidth, (float) from.y, (float) from.z,
                (float) from.x + halfWidth, (float) from.y, (float) from.z,
                (float) to.x + halfWidth, (float) to.y, (float) to.z,
                (float) to.x - halfWidth, (float) to.y, (float) to.z, argb);
        quad(buf, mat,
                (float) from.x, (float) from.y - halfWidth, (float) from.z,
                (float) from.x, (float) from.y + halfWidth, (float) from.z,
                (float) to.x, (float) to.y + halfWidth, (float) to.z,
                (float) to.x, (float) to.y - halfWidth, (float) to.z, argb);
    }

    private static void quad(
            BufferBuilder buf, Matrix4f mat,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            int argb
    ) {
        buf.addVertex(mat, x0, y0, z0).setColor(argb);
        buf.addVertex(mat, x1, y1, z1).setColor(argb);
        buf.addVertex(mat, x2, y2, z2).setColor(argb);
        buf.addVertex(mat, x3, y3, z3).setColor(argb);
    }
}
