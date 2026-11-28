package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.habitrain.core.scene.client.replay.ReplayBoardSite.*;

/**
 * The wooden, brass-trimmed diorama tray one block above the marker, its Art Deco flip-board
 * plaque, the faint holographic support beams and the stage-wide overlays (scan-in, settlement
 * wave, blackout glow). Delegates the idle / loading sculpture to {@link ReplayIdleCubes}.
 */
final class ReplayTrayPainter {
    private ReplayTrayPainter() {}

    private record Material(TextureAtlasSprite sprite, int tint) {}

    private static Material herringbone, mahogany, ebony, ebonyWall, brass, brassDark, corner;

    static void invalidateSprites() {
        herringbone = mahogany = ebony = ebonyWall = brass = brassDark = corner = null;
    }

    private static Material material(String wathe, String fallback, int fallbackTint) {
        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        TextureAtlasSprite sprite = atlas.apply(ResourceLocation.fromNamespaceAndPath("wathe", wathe));
        if (!MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name())) return new Material(sprite, 0xFFFFFFFF);
        return new Material(atlas.apply(ResourceLocation.withDefaultNamespace(fallback)), fallbackTint);
    }

    private static void ensureSprites() {
        if (herringbone != null) return;
        herringbone = material("block/mahogany_herringbone", "block/dark_oak_planks", 0xFFC27A5E);
        mahogany = material("block/mahogany_planks", "block/dark_oak_planks", 0xFFB06A52);
        ebony = material("block/ebony_planks", "block/stripped_dark_oak_log", 0xFF5E4A44);
        ebonyWall = material("block/ebony_cabinet_wall", "block/stripped_dark_oak_log", 0xFF4E3C38);
        // Solid opaque wathe brass/gold textures (never cutout decals like gold_ledge / gold_ornament_corner).
        brass = material("block/gold", "block/gold_block", 0xFFE6C27A);
        brassDark = material("block/tarnished_gold", "block/gold_block", 0xFFC69E52);
        corner = material("block/gold_pillar_top", "block/gold_block", 0xFFF0CC84);
    }

    // ------------------------------------------------------------------ tray (textured, opaque)

    static void tray(ReplayFrame f, ReplayBoardSite s) {
        tray(f.tray(), s.board(), s.hw(), s.hd(), s.detail(), s.light());
    }

    static void tray(VertexConsumer vc, Matrix4f board, float hw, float hd, boolean detail, int light) {
        ensureSprites();
        PoseStack stack = new PoseStack();
        stack.mulPose(board);
        PoseStack.Pose pose = stack.last();
        float ft = FRAME;
        int l = light;
        // Floor strictly inside ebony frame walls (no interpenetration)
        box(vc, pose, -hw + ft, TRAY_BOTTOM + 0.03f, -hd + ft, hw - ft, BASE_TOP, hd - ft,
                mahogany, herringbone, l, 0.5f);
        // Ebony outer frame (4 strictly abutting non-overlapping bars)
        box(vc, pose, -hw, TRAY_BOTTOM, hd - ft, hw, FRAME_TOP, hd, ebonyWall, ebony, l, 0.5f);
        box(vc, pose, -hw, TRAY_BOTTOM, -hd, hw, FRAME_TOP, -hd + ft, ebonyWall, ebony, l, 0.5f);
        box(vc, pose, -hw, TRAY_BOTTOM, -hd + ft, -hw + ft, FRAME_TOP, hd - ft, ebonyWall, ebony, l, 0.5f);
        box(vc, pose, hw - ft, TRAY_BOTTOM, -hd + ft, hw, FRAME_TOP, hd - ft, ebonyWall, ebony, l, 0.5f);
        // Mahogany inner bevel lip (4 strictly abutting non-overlapping bars)
        float lip = 0.035f, ly = FRAME_TOP - 0.035f;
        box(vc, pose, -hw + ft, ly, hd - ft - lip, hw - ft, FRAME_TOP - 0.01f, hd - ft, mahogany, mahogany, l, 0.5f);
        box(vc, pose, -hw + ft, ly, -hd + ft, hw - ft, FRAME_TOP - 0.01f, -hd + ft + lip, mahogany, mahogany, l, 0.5f);
        box(vc, pose, -hw + ft, ly, -hd + ft + lip, -hw + ft + lip, FRAME_TOP - 0.01f, hd - ft - lip, mahogany, mahogany, l, 0.5f);
        box(vc, pose, hw - ft - lip, ly, -hd + ft + lip, hw - ft, FRAME_TOP - 0.01f, hd - ft - lip, mahogany, mahogany, l, 0.5f);
        // Solid brushed brass inner ledge (4 strictly abutting bars — zero corner overlap)
        float le = 0.028f, top = FRAME_TOP + 0.012f;
        box(vc, pose, -hw + ft - le, FRAME_TOP, hd - ft - 0.004f, hw - ft + le, top, hd - ft + le, brass, brass, l, 0.5f);
        box(vc, pose, -hw + ft - le, FRAME_TOP, -hd + ft - le, hw - ft + le, top, -hd + ft + 0.004f, brass, brass, l, 0.5f);
        box(vc, pose, -hw + ft - le, FRAME_TOP, -hd + ft + 0.004f, -hw + ft + 0.004f, top, hd - ft - 0.004f, brass, brass, l, 0.5f);
        box(vc, pose, hw - ft - 0.004f, FRAME_TOP, -hd + ft + 0.004f, hw - ft + le, top, hd - ft - 0.004f, brass, brass, l, 0.5f);
        // Solid brass corner caps (embossed medallion top + brushed brass sides) with rivet bosses
        float cs = 0.25f;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float cx = sx * (hw - cs * 0.5f + 0.02f), cz = sz * (hd - cs * 0.5f + 0.02f);
                box(vc, pose, cx - cs / 2, FRAME_TOP - 0.02f, cz - cs / 2, cx + cs / 2, FRAME_TOP + 0.035f, cz + cs / 2,
                        brass, corner, l, 0.25f);
                box(vc, pose, cx - 0.03f, FRAME_TOP + 0.035f, cz - 0.03f, cx + 0.03f, FRAME_TOP + 0.06f, cz + 0.03f,
                        brass, brass, l, 0.25f);
            }
        }
        if (detail) {
            // Symmetrically spaced rivets seated directly on FRAME_TOP, clear of corner caps
            float xSpan = hw - 0.42f;
            if (xSpan > 0.05f) {
                int xCount = Math.max(2, Math.round((xSpan * 2f) / 0.44f));
                for (int i = 0; i <= xCount; i++) {
                    float x = Mth.lerp((float) i / xCount, -xSpan, xSpan);
                    rivet(vc, pose, x, FRAME_TOP, hd - ft * 0.5f, l);
                    rivet(vc, pose, x, FRAME_TOP, -hd + ft * 0.5f, l);
                }
            }
            float zSpan = hd - 0.42f;
            if (zSpan > 0.05f) {
                int zCount = Math.max(2, Math.round((zSpan * 2f) / 0.44f));
                for (int i = 0; i <= zCount; i++) {
                    float z = Mth.lerp((float) i / zCount, -zSpan, zSpan);
                    rivet(vc, pose, -hw + ft * 0.5f, FRAME_TOP, z, l);
                    rivet(vc, pose, hw - ft * 0.5f, FRAME_TOP, z, l);
                }
            }
        }
        // Undercarriage: symmetric sleepers and antique-brass miniature double track tucked cleanly under floor
        float sleeperSpan = hw - ft - 0.16f;
        if (sleeperSpan > 0.05f) {
            int sleeperCount = Math.max(2, Math.round((sleeperSpan * 2f) / 0.36f));
            for (int i = 0; i <= sleeperCount; i++) {
                float x = Mth.lerp((float) i / sleeperCount, -sleeperSpan, sleeperSpan);
                box(vc, pose, x - 0.045f, TRAY_BOTTOM - 0.075f, -hd * 0.62f, x + 0.045f, TRAY_BOTTOM - 0.03f, hd * 0.62f,
                        ebony, ebony, l, 0.5f);
            }
        }
        for (int side = -1; side <= 1; side += 2) {
            float z = side * hd * 0.42f;
            box(vc, pose, -hw + ft + 0.04f, TRAY_BOTTOM - 0.03f, z - 0.022f, hw - ft - 0.04f, TRAY_BOTTOM + 0.03f, z + 0.022f,
                    brassDark, brassDark, l, 0.5f);
        }
        plaqueBody(vc, board, hw, hd, light);
    }

    private static void rivet(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, int light) {
        box(vc, pose, x - 0.018f, y, z - 0.018f, x + 0.018f, y + 0.016f, z + 0.018f, brass, brass, light, 0.25f);
    }

    private static void box(VertexConsumer vc, PoseStack.Pose pose, float x0, float y0, float z0, float x1, float y1, float z1,
                            Material side, Material top, int light, float tile) {
        ReplayDraw.box(vc, pose, x0, y0, z0, x1, y1, z1, side.sprite(), side.tint(), top.sprite(), top.tint(), light, tile);
    }

    // ------------------------------------------------------------------ plaque

    static final float REF_TRAY_HALF = 2.3f;
    static final float BASE_PLAQUE_W = 2.6f;
    static final float PLAQUE_H = 0.36f;
    static final float PLAQUE_STANDOFF = 0.07f;
    static final float PLAQUE_BEZEL = 0.036f;
    static final float PLAQUE_EBONY_PAD = 0.035f;

    static float plaqueScale(float edgeHalf) {
        return Mth.clamp(edgeHalf / REF_TRAY_HALF, 0.55f, 2.0f);
    }

    static Matrix4f plaqueFrameMatrix(Matrix4f board, float hw, float hd, int side) {
        boolean alongX = (side & 1) == 0;
        float edgeHalf = alongX ? hw : hd;
        float outward = alongX ? hd : hw;
        float pScale = plaqueScale(edgeHalf);
        float yCenter = FRAME_TOP + 0.03f - (PLAQUE_H * 0.5f + 0.01f) * pScale;
        return new Matrix4f(board)
                .rotateY(side * Mth.HALF_PI)
                .translate(0f, yCenter, outward + PLAQUE_STANDOFF * pScale)
                .rotateX((float) Math.toRadians(-20));
    }

    static Matrix4f plaqueMatrix(ReplayBoardSite s, int side) {
        return plaqueMatrix(s.board(), s.hw(), s.hd(), side);
    }

    private static Matrix4f plaqueMatrix(Matrix4f board, float hw, float hd, int side) {
        float pScale = plaqueScale((side & 1) == 0 ? hw : hd);
        return plaqueFrameMatrix(board, hw, hd, side).scale(pScale);
    }

    /** The camera is at the origin of the render pass. Only draw the readable side of the plaque. */
    static boolean plaqueFacesCamera(Matrix4f m) {
        return m.m20() * m.m30() + m.m21() * m.m31() + m.m22() * m.m32() < 0f;
    }

    private static void plaqueBody(VertexConsumer vc, Matrix4f board, float hw, float hd, int light) {
        float halfW = BASE_PLAQUE_W * 0.5f, halfH = PLAQUE_H * 0.5f;
        float bz = PLAQUE_BEZEL;
        float ep = PLAQUE_EBONY_PAD;
        for (int side = 0; side < 4; side++) {
            PoseStack stack = new PoseStack();
            stack.mulPose(plaqueMatrix(board, hw, hd, side));
            PoseStack.Pose pose = stack.last();
            // Solid ebony chassis backplate (z = -0.045 .. -0.020)
            box(vc, pose, -halfW - ep, -halfH - ep, -0.045f, halfW + ep, halfH + ep, -0.020f,
                    ebonyWall, ebony, light, 0.5f);
            // Hollow 4-piece solid brushed-brass bezel (z = -0.020 .. 0.000) framing an open display well,
            // so the recessed dark display screen never sits coplanar against a solid brass slab.
            box(vc, pose, -halfW, halfH - bz, -0.020f, halfW, halfH, 0f, brass, brass, light, 0.5f);
            box(vc, pose, -halfW, -halfH, -0.020f, halfW, -halfH + bz, 0f, brass, brass, light, 0.5f);
            box(vc, pose, -halfW, -halfH + bz, -0.020f, -halfW + bz, halfH - bz, 0f, brass, brass, light, 0.5f);
            box(vc, pose, halfW - bz, -halfH + bz, -0.020f, halfW, halfH - bz, 0f, brass, brass, light, 0.5f);
            // Stepped solid brass Art Deco end finials mounted cleanly outside the ebony chassis (zero overlap)
            for (int endSide = -1; endSide <= 1; endSide += 2) {
                float xInner = endSide * (halfW + ep);
                float xOuter = endSide * (halfW + 0.095f);
                box(vc, pose, Math.min(xInner, xOuter), -0.068f, -0.040f, Math.max(xInner, xOuter), 0.068f, -0.010f,
                        brassDark, brassDark, light, 0.25f);
                float cInner = endSide * (halfW + 0.045f);
                float cOuter = endSide * (halfW + 0.085f);
                box(vc, pose, Math.min(cInner, cOuter), -0.038f, -0.010f, Math.max(cInner, cOuter), 0.038f, -0.001f,
                        brass, brass, light, 0.25f);
            }
        }
    }

    private static final int MAX_CLOCK_CHARS = 16;

    static void plaque(ReplayFrame f, ReplayBoardSite s) {
        if (!f.video && ReplayBoardButtons.hasActiveDeck(s.pos())) return;
        float pw = BASE_PLAQUE_W, ph = PLAQUE_H;
        float innerHalfW = pw * 0.5f - PLAQUE_BEZEL;
        float innerHalfH = ph * 0.5f - PLAQUE_BEZEL;
        VertexConsumer fill = f.fill();
        float left = -innerHalfW + 0.04f, rightEdge = innerHalfW - 0.04f, z = 0.010f;
        var model = f.model;
        double idle = f.handoff.idlePlaque();
        double standby = s.fade() * ReplayStagePlan.smoothstep(0.42, 1, idle);
        boolean loading = f.loading;
        Component line = standby > 0 ? ReplayBoardTexts.tr(f.meshFailed ? "mesh_failed" : loading ? "loading" : "idle") : Component.empty();
        Component sub = standby > 0 ? ReplayBoardTexts.tr("brand").copy()
                .append(".".repeat(loading ? (int) (f.ambient * 3) % 4 : 0)) : Component.empty();
        Matrix4f[] faces = new Matrix4f[4];
        for (int side = 0; side < 4; side++) {
            Matrix4f m = plaqueMatrix(s, side);
            if (!plaqueFacesCamera(m)) continue;
            faces[side] = m;
            // Recessed dark screen inside the hollow brass bezel well (full 0.018 clear of ebony backplate)
            ReplayDraw.rectXY(fill, m, -innerHalfW, -innerHalfH, innerHalfW, innerHalfH, -0.002f,
                    ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * s.fade()), ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.96 * s.fade()));
            ReplayDraw.rectOutlineXY(fill, m, -innerHalfW + 0.012f, -innerHalfH + 0.012f,
                    innerHalfW - 0.012f, innerHalfH - 0.012f, 0.001f, 0.0045f,
                    ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.50 * s.fade()));
            int engraving = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.72 * s.fade());
            ReplayDraw.rectXY(fill, m, -innerHalfW + 0.025f, 0.005f, innerHalfW - 0.025f, 0.009f, 0.002f, engraving, engraving);
            if (standby <= 0.01) continue;
            f.text(m, line, 0, 0.072f, z, 0.0052f, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, standby), 0, false, pw - 0.18f);
            f.text(m, sub, 0, -0.068f, z, 0.0042f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, standby), 0, false, pw - 0.18f);
        }
        double alpha = s.fade() * (1 - ReplayStagePlan.smoothstep(0, 0.58, idle));
        if (model == null || alpha <= 0.01) return;
        var stage = f.stage;
        double flip = stage == null ? 1 : stage.plaqueFlip();
        float flipScale = (float) Math.max(0.05, flip);
        // Pure stateless flip-clock animation derived directly from f.replayTick so multiple
        // boards on screen and offline video exports never clobber shared static state.
        String clock = ReplayStagePlan.clock(f.replayTick) + "/" + ReplayStagePlan.clock(model.replay.durationTicks());
        int clockLen = Math.min(clock.length(), MAX_CLOCK_CHARS);
        double[] clockFlipProgress = computeClockFlipProgress(clock, clockLen, f, model.rate);
        Component phase = phaseLabel(f);
        int phaseColor = ReplayPalette.alpha(f.frame != null && f.frame.slowMotion()
                ? ReplayPalette.RUBY : ReplayPalette.LAMP, alpha);
        double progress = f.frame == null ? 0 : f.frame.phase() == ReplayTimeline.Phase.BUILD ? 0
                : model.replay.durationTicks() <= 0 ? 1 : Mth.clamp(f.replayTick / model.replay.durationTicks(), 0, 1);
        float barY = -innerHalfH + 0.018f, bx0 = left, bx1 = rightEdge;
        float px = bx0 + (float) ((bx1 - bx0) * progress);
        boolean showSync = f.meshFailed || f.totalSections > 0 && f.presentSections < f.totalSections;
        Component sync = f.meshFailed ? ReplayBoardTexts.tr("mesh_failed")
                : showSync ? ReplayBoardTexts.tr("map_progress", f.presentSections, f.totalSections) : Component.empty();
        VertexConsumer glow = f.glow();
        float tileW = 0.074f, tileH = 0.088f, gap = 0.009f, cy = -0.056f;

        for (Matrix4f m : faces) {
            if (m == null) continue;
            Matrix4f titleM = new Matrix4f(m).translate(0, 0.074f, 0).scale(1, flipScale, 1);
            f.text(titleM, Component.literal(model.replay.mapName()), left, 0, z, 0.0052f,
                    ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, alpha * flip), -1, false, pw * 0.54f);
            f.text(titleM, model.modeLabel, rightEdge, 0, z, 0.0042f,
                    ReplayPalette.alpha(ReplayPalette.TEXT_MUTED, alpha * flip), 1, false, pw * 0.34f);
            // flip-board clock "mm:ss / mm:ss"
            float x = left + tileW / 2;
            for (int i = 0; i < clockLen; i++) {
                char c = clock.charAt(i);
                double p = clockFlipProgress[i];
                float squash = (float) Math.max(0.08, Math.abs(Math.cos(p * Math.PI)));
                boolean digit = Character.isDigit(c);
                float w = digit ? tileW : tileW * 0.55f;
                float cx = x + (w - tileW) / 2;
                if (digit) {
                    ReplayDraw.rectXY(fill, m, cx - w / 2, cy - tileH / 2, cx + w / 2, cy, 0.003f,
                            ReplayPalette.alpha(0xFF0B0F1C, alpha), ReplayPalette.alpha(0xFF070A14, alpha));
                    ReplayDraw.rectXY(fill, m, cx - w / 2, cy, cx + w / 2, cy + tileH / 2, 0.003f,
                            ReplayPalette.alpha(0xFF1A2440, alpha), ReplayPalette.alpha(0xFF101830, alpha));
                    ReplayDraw.rectXY(fill, m, cx - w / 2, cy - 0.0015f, cx + w / 2, cy + 0.0015f, 0.005f,
                            ReplayPalette.alpha(0xFF000000, 0.9 * alpha), ReplayPalette.alpha(0xFF000000, 0.9 * alpha));
                    if (p < 1) {
                        ReplayDraw.rectXY(glow, m, cx - w / 2, cy - 0.003f, cx + w / 2, cy + 0.003f, 0.007f,
                                ReplayPalette.alpha(ReplayPalette.LAMP, (1 - p) * 0.8 * alpha), ReplayPalette.alpha(ReplayPalette.LAMP, (1 - p) * 0.8 * alpha));
                    }
                }
                Matrix4f cm = new Matrix4f(m).translate(cx, cy, 0).scale(1, squash, 1);
                f.text(cm, Component.literal(String.valueOf(c)), 0, 0, z, 0.0086f,
                        ReplayPalette.alpha(digit ? ReplayPalette.BRASS_LIGHT : ReplayPalette.TEXT_MUTED, alpha), 0, false, 0);
                x += w + gap;
            }
            // phase label (or sync status when streaming map sections, kept cleanly out of flip-clock tiles)
            f.text(m, showSync ? sync : phase, rightEdge, cy, z, showSync ? 0.0038f : 0.0046f,
                    showSync ? ReplayPalette.alpha(ReplayPalette.PASSENGER, 0.92 * alpha) : phaseColor, 1, false, pw * 0.38f);
            // progress rail
            ReplayDraw.rectXY(fill, m, bx0, barY - 0.004f, bx1, barY + 0.004f, 0.003f,
                    ReplayPalette.alpha(0xFF050814, alpha), ReplayPalette.alpha(0xFF050814, alpha));
            if (px > bx0 + 0.003f) {
                ReplayDraw.rectXY(glow, m, bx0, barY - 0.003f, px, barY + 0.003f, 0.006f,
                        ReplayPalette.alpha(ReplayPalette.BRASS, 0.85 * alpha), ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.85 * alpha));
            }
        }
    }

    private static double[] computeClockFlipProgress(String clock, int clockLen, ReplayFrame f, double baseRate) {
        double[] progress = new double[clockLen];
        java.util.Arrays.fill(progress, 1.0);
        if (f.frame == null || f.frame.phase() != ReplayTimeline.Phase.PLAY || f.replayTick <= 0.0) {
            return progress;
        }
        int slashIdx = clock.indexOf('/');
        int colonIdx = slashIdx > 0 ? clock.lastIndexOf(':', slashIdx) : -1;
        if (colonIdx < 1 || slashIdx < colonIdx + 3) {
            return progress;
        }
        double ticks = f.replayTick;
        int wholeSec = (int) (ticks / 20.0);
        if (wholeSec <= 0) {
            return progress;
        }
        double effectiveRate = f.frame.slowMotion() ? 0.35 : Math.max(0.25, baseRate);
        double flipWindowTicks = Math.max(0.5, 3.2 * effectiveRate);
        double ticksSinceSec = ticks - wholeSec * 20.0;
        if (ticksSinceSec >= flipWindowTicks) {
            return progress;
        }
        double flipVal = Mth.clamp(ticksSinceSec / flipWindowTicks, 0.0, 1.0);
        int secOnesIdx = slashIdx - 1;
        int secTensIdx = slashIdx - 2;
        int minOnesIdx = colonIdx - 1;
        int minTensIdx = colonIdx - 2;
        if (secOnesIdx < clockLen) {
            progress[secOnesIdx] = flipVal;
        }
        if (secTensIdx >= 0 && secTensIdx < clockLen && wholeSec % 10 == 0) {
            progress[secTensIdx] = flipVal;
        }
        if (minOnesIdx >= 0 && minOnesIdx < clockLen && wholeSec % 60 == 0) {
            progress[minOnesIdx] = flipVal;
        }
        if (minTensIdx >= 0 && minTensIdx < clockLen && wholeSec % 600 == 0) {
            progress[minTensIdx] = flipVal;
        }
        return progress;
    }

    private static Component phaseLabel(ReplayFrame f) {
        if (f.frame == null) return Component.empty();
        return switch (f.frame.phase()) {
            case BUILD -> ReplayBoardTexts.tr("phase.build");
            case PLAY -> ReplayBoardTexts.tr(f.frame.slowMotion() ? "phase.slow" : "phase.play");
            case REVIEW -> ReplayBoardTexts.tr("phase.review");
            case HONORS -> ReplayBoardTexts.tr("phase.honors");
        };
    }

    // ------------------------------------------------------------------ light work

    static void lights(ReplayFrame f, ReplayBoardSite s) {
        double glowLevel = f.stage == null ? 0.75 : f.stage.trayGlow();
        double a = s.fade() * glowLevel;
        if (a <= 0.01) return;
        VertexConsumer glow = f.glow();
        Matrix4f m = s.board();
        float hw = s.hw() - FRAME, hd = s.hd() - FRAME, y = FRAME_TOP + 0.018f;
        // Trim rim light bars strictly between the solid brass corner caps (inner corner edge = half - 0.23)
        float xBar = Math.max(0.1f, s.hw() - 0.245f);
        float zBar = Math.max(0.1f, s.hd() - 0.245f);
        double flicker = 0.92 + 0.08 * Math.sin(f.ambient * 7.3) * Math.sin(f.ambient * 2.1);
        int lamp = ReplayPalette.alpha(ReplayPalette.LAMP, 0.50 * a * flicker);
        float lampW = 0.020f * s.scale();
        ReplayDraw.segment(glow, m, -xBar, y, hd, xBar, y, hd, lampW, lamp, lamp);
        ReplayDraw.segment(glow, m, -xBar, y, -hd, xBar, y, -hd, lampW, lamp, lamp);
        ReplayDraw.segment(glow, m, -hw, y, -zBar, -hw, y, zBar, lampW, lamp, lamp);
        ReplayDraw.segment(glow, m, hw, y, -zBar, hw, y, zBar, lampW, lamp, lamp);
        // Warm wash just inside the frame on all 4 sides (below MAP_BASE & idle floor dial)
        int wash = ReplayPalette.alpha(ReplayPalette.LAMP, 0.10 * a), clear = ReplayPalette.alpha(ReplayPalette.LAMP, 0);
        float fy = BASE_TOP + 0.002f, in = 0.22f;
        ReplayDraw.quad(glow, m, -hw, fy, hd, hw, fy, hd, hw - in, fy, hd - in, -hw + in, fy, hd - in, wash, wash, clear, clear);
        ReplayDraw.quad(glow, m, hw, fy, -hd, -hw, fy, -hd, -hw + in, fy, -hd + in, hw - in, fy, -hd + in, wash, wash, clear, clear);
        ReplayDraw.quad(glow, m, -hw, fy, -hd, -hw, fy, hd, -hw + in, fy, hd - in, -hw + in, fy, -hd + in, wash, wash, clear, clear);
        ReplayDraw.quad(glow, m, hw, fy, hd, hw, fy, -hd, hw - in, fy, -hd + in, hw - in, fy, hd - in, wash, wash, clear, clear);
        if (s.detail()) {
            // Soft circular warm pin-lights symmetrically spaced strictly between the corner caps
            float dotR = 0.024f * s.scale();
            int dotCol = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.65 * a);
            float xDotSpan = s.hw() - 0.38f;
            if (xDotSpan > 0.05f) {
                int nx = Math.max(2, Math.round((xDotSpan * 2f) / 0.52f));
                for (int i = 0; i <= nx; i++) {
                    float x = Mth.lerp((float) i / nx, -xDotSpan, xDotSpan);
                    ReplayDraw.softBillboard(glow, m.transformPosition(x, y + 0.008f, hd, new Vector3f()),
                            f.right, f.up, dotR, dotR, dotCol, 0.15f, 0.45f, 8);
                    ReplayDraw.softBillboard(glow, m.transformPosition(x, y + 0.008f, -hd, new Vector3f()),
                            f.right, f.up, dotR, dotR, dotCol, 0.15f, 0.45f, 8);
                }
            }
            float zDotSpan = s.hd() - 0.38f;
            if (zDotSpan > 0.05f) {
                int nz = Math.max(2, Math.round((zDotSpan * 2f) / 0.52f));
                for (int i = 0; i <= nz; i++) {
                    float z = Mth.lerp((float) i / nz, -zDotSpan, zDotSpan);
                    ReplayDraw.softBillboard(glow, m.transformPosition(-hw, y + 0.008f, z, new Vector3f()),
                            f.right, f.up, dotR, dotR, dotCol, 0.15f, 0.45f, 8);
                    ReplayDraw.softBillboard(glow, m.transformPosition(hw, y + 0.008f, z, new Vector3f()),
                            f.right, f.up, dotR, dotR, dotCol, 0.15f, 0.45f, 8);
                }
            }
        }
        // Holographic support beams keep their feet on the real wooden tabletop, even when the
        // display is scaled, rotated or offset. Endpoints are already in camera-relative space.
        double beam = s.fade() * (0.6 + 0.4 * glowLevel);
        float beamScale = Mth.clamp(s.scale(), 0.4f, 2.0f);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float x0 = sx * 0.36f, z0 = sz * 0.36f;
                float x1 = sx * (s.hw() - 0.24f), z1 = sz * (s.hd() - 0.24f), y1 = TRAY_BOTTOM - 0.075f;
                Vector3f start = s.table().transformPosition(x0, 0.005f, z0, new Vector3f());
                Vector3f end = m.transformPosition(x1, y1, z1, new Vector3f());
                ReplayDraw.segment(glow, start, end, 0.016f * beamScale,
                        ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.30 * beam), ReplayPalette.alpha(ReplayPalette.LAMP, 0.08 * beam));
                double t = (f.ambient * 0.45 + (sx + 2) * 0.17 + (sz + 2) * 0.31) % 1.0;
                Vector3f dot = new Vector3f(start).lerp(end, (float) t);
                ReplayDraw.softBillboard(glow, dot, f.right, f.up, 0.028f * beamScale, 0.028f * beamScale,
                        ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.50 * beam * Math.sin(t * Math.PI)), 0.15f, 0.45f, 8);
            }
        }
    }

    /** Scan-in plane + wireframe, settlement wave and blackout glow over the diorama. */
    static void overlays(ReplayFrame f, ReplayBoardSite s, Matrix4f d, float sink) {
        var model = f.model;
        var stage = f.stage;
        if (model == null || stage == null) return;
        VertexConsumer glow = f.glow();
        float w = model.mapW / 2 + 0.04f, dd = model.mapD / 2 + 0.04f, h = Math.max(0.12f, model.mapH + 0.04f);
        double a = s.fade();
        float elemScale = s.scale() * (float) f.focus.zoom();
        if (stage.scan() < 1 && stage.scan() > 0) {
            float x = -w + (float) (2 * w * stage.scan());
            int gold = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.55 * a), clear = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0);
            ReplayDraw.quad(glow, d, x, 0, -dd, x, 0, dd, x, h, dd, x, h, -dd, gold, gold, clear, clear);
            ReplayDraw.segment(glow, d, x, 0.004f, -dd, x, 0.004f, dd, 0.02f * elemScale, gold, gold);
            ReplayDraw.wireBox(glow, d, -w, 0, -dd, w, h, dd, 0.008f * elemScale,
                    ReplayPalette.alpha(ReplayPalette.BRASS, 0.55 * a * (1 - stage.scan() * 0.5)));
        }
        if (stage.reviewWave() >= 0) {
            double wave = stage.reviewWave();
            double env = Math.sin(Mth.clamp(wave, 0.0, 1.0) * Math.PI);
            if (env > 0.02) {
                float x = -w + (float) (2 * w * wave);
                float bandZ = Math.min(dd, model.mapD * 0.5f + 0.02f);
                int core = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.28 * a * env);
                int trail = ReplayPalette.alpha(ReplayPalette.BRASS, 0.16 * a * env);
                int clear = ReplayPalette.alpha(ReplayPalette.BRASS, 0);
                // Low deck-hugging golden sweep (avoid tall vertical walls that read as diagonal trapezoid beams).
                ReplayDraw.segment(glow, d, x, 0.012f, -bandZ, x, 0.012f, bandZ, 0.018f * elemScale, core, core);
                ReplayDraw.quad(glow, d, x - 0.30f, 0.008f, -bandZ, x, 0.008f, -bandZ, x, 0.008f, bandZ, x - 0.30f, 0.008f, bandZ,
                        clear, trail, trail, clear);
            }
        }
        if (f.blackout) {
            double pulse = 0.55 + 0.45 * Math.sin(f.anim * 5.2);
            int red = ReplayPalette.alpha(ReplayPalette.EMERGENCY, 0.16 * a * pulse);
            ReplayDraw.rectXZ(glow, d, -w, -dd, w, dd, 0.01f, red);
            ReplayDraw.wireBox(glow, d, -w, 0, -dd, w, h, dd, 0.006f * elemScale, ReplayPalette.alpha(ReplayPalette.EMERGENCY, 0.35 * a * pulse));
        }
    }

    // ------------------------------------------------------------------ idle / loading

    static void handoff(ReplayFrame f, ReplayBoardSite s) {
        double intensity = f.handoff.pulse() * s.fade();
        if (intensity <= 0.01) return;
        float radius = (float) Mth.lerp(f.handoff.pulseRadius(), 0.17, (s.hw() - FRAME) * 0.89);
        float y = BASE_TOP + 0.018f;
        VertexConsumer glow = f.glow();
        Matrix4f board = s.board();
        int gold = ReplayPalette.alpha(ReplayPalette.BRASS, 0.6 * intensity);
        int white = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.85 * intensity);
        int clear = ReplayPalette.alpha(ReplayPalette.BRASS, 0);
        ReplayDraw.ring(glow, board, 0, y, 0, radius, 0.035f, 48, white, clear);
        ReplayDraw.ring(glow, board, 0, y, 0, radius * 0.84f, 0.060f, 48, gold, clear);
        ReplayDraw.disc(glow, board, 0, y, 0, Math.min(radius, 0.8f), 28, gold, clear);
    }
}
