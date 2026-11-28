package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.List;

/**
 * Act four: the "Star-night Express · Art Deco" honors screen unfolding above the sunk diorama.
 * Win banner and stamp, a podium of spinning 3D winner heads with names, role badges and the MVP
 * crown, then MVP stats and the match summary (duration, kills, first blood / final kill).
 */
final class ReplayHonorsPainter {
    private ReplayHonorsPainter() {}

    static final float REF_TRAY_HALF = 3.425f;
    static final float BASE_W = 5.4f;
    static final float BASE_H = 2.3f;
    // Include the animated heads and line widths outside the flat panel plane.
    static final float PANEL_RADIUS = (float) Math.sqrt(BASE_W * BASE_W / 4 + BASE_H * BASE_H / 4 + 0.25f);

    static float honorsWorldScale(float siteScale, float trayHw) {
        return siteScale * (trayHw / REF_TRAY_HALF);
    }

    static float anchorHeight(float trayHw) {
        return ReplayBoardSite.FRAME_TOP + (0.38f + BASE_H * 0.5f) * trayHw / REF_TRAY_HALF;
    }

    static Matrix4f honorsMatrix(Matrix4f board, float siteScale, float trayHw, Quaternionfc cameraRotation, double unfold) {
        float worldScale = honorsWorldScale(siteScale, trayHw);
        Vector3f anchor = board.transformPosition(0f, anchorHeight(trayHw), 0f, new Vector3f());
        float unfoldScale = (float) Math.max(0.02, unfold);
        return new Matrix4f().translate(anchor).rotate(cameraRotation).scale(worldScale, worldScale * unfoldScale, worldScale);
    }

    static boolean visible(ReplayFrame frame) {
        return frame.model != null && frame.stage != null && frame.stage.honors() > 0.01;
    }

    static void paint(ReplayFrame f, ReplayBoardSite s) {
        ReplayBoardModel model = f.model;
        ReplayStagePlan.Stage stage = f.stage;
        double unfold = stage.honors();
        double a = s.fade() * Math.min(1, unfold * 1.4);
        float worldScale = honorsWorldScale(s.scale(), s.hw());
        float pw = BASE_W, ph = BASE_H;
        Matrix4f m = honorsMatrix(s.board(), s.scale(), s.hw(), f.rotation, unfold);
        float top = ph / 2, bottom = -ph / 2, hw = pw / 2;
        VertexConsumer fill = f.fill();
        VertexConsumer glow = f.glow();
        double time = stage.honorsTime();

        // body
        ReplayDraw.rectXY(fill, m, -hw, bottom, hw, top, 0, ReplayPalette.alpha(ReplayPalette.NIGHT_2, a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, a));
        // sunburst behind the banner
        int rayC = ReplayPalette.alpha(ReplayPalette.BRASS, 0.16 * a), rayClear = ReplayPalette.alpha(ReplayPalette.BRASS, 0);
        float sx = 0, sy = top - 0.62f;
        for (int i = 0; i < 15; i++) {
            float ang = (float) (Math.PI * (0.08 + 0.84 * i / 14.0));
            float len = (i % 2 == 0 ? 0.58f : 0.42f);
            float ex = sx + Mth.cos(ang) * len * 1.6f, ey = sy + Mth.sin(ang) * len;
            float px = -Mth.sin(ang) * 0.012f, py = Mth.cos(ang) * 0.012f;
            ReplayDraw.quad(glow, m, sx - px, sy - py, 0.001f, sx + px, sy + py, 0.001f, ex + px * 3, ey + py * 3, 0.001f,
                    ex - px * 3, ey - py * 3, 0.001f, rayC, rayC, rayClear, rayClear);
        }
        VertexConsumer ui = f.buffers.getBuffer(ReplayDraw.UI_OVERLAY);
        // double brass border with stepped Art Deco corners (flat in-plane mitered frames)
        frame(ui, m, hw - 0.04f, top - 0.04f, 0.012f, ReplayPalette.alpha(ReplayPalette.BRASS, 0.95 * a));
        frame(ui, m, hw - 0.075f, top - 0.075f, 0.006f, ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.85 * a));
        int brass = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.90 * a);
        for (int cx = -1; cx <= 1; cx += 2) {
            for (int cy = -1; cy <= 1; cy += 2) {
                float x = cx * (hw - 0.04f), y = cy * (top - 0.04f);
                float t = 0.006f;
                for (int step = 1; step <= 3; step++) {
                    float o = step * 0.05f;
                    float vx = x - cx * o;
                    float hy = y - cy * o;
                    ReplayDraw.rectXY(ui, m, vx - t * 0.5f, Math.min(y, hy - cy * t * 0.5f),
                            vx + t * 0.5f, Math.max(y, hy - cy * t * 0.5f), 0.003f, brass, brass);
                    ReplayDraw.rectXY(ui, m, Math.min(x, vx + cx * t * 0.5f), hy - t * 0.5f,
                            Math.max(x, vx + cx * t * 0.5f), hy + t * 0.5f, 0.003f, brass, brass);
                }
            }
        }

        float z = 0.008f;
        double row1 = fadeIn(time, 0.25), row2 = fadeIn(time, 0.55), row3 = fadeIn(time, 1.6), row4 = fadeIn(time, 2.4);
        f.text(m, ReplayBoardTexts.tr("header"), 0, top - 0.14f, z, 0.0056f,
                ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, a * row1), 0, false, pw - 0.4f);
        ReplayDraw.lineXY(ui, m, -hw * 0.55f, top - 0.21f, hw * 0.55f, top - 0.21f, 0.003f, 0.006f,
                ReplayPalette.alpha(ReplayPalette.BRASS, 0.85 * a * row1), ReplayPalette.alpha(ReplayPalette.BRASS, 0.85 * a * row1));
        diamond(ui, m, 0, top - 0.21f, 0.03f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, a * row1));
        // win banner + subtitle
        int win = model.winColor;
        f.text(m, model.title, 0, top - 0.42f, z, 0.019f, ReplayPalette.alpha(win, a * row2), 0, true, pw - 1.1f);
        Component sub = Component.empty().append(model.subtitle);
        if (!model.modeLabel.getString().isBlank() && !model.modeLabel.getString().equals(model.subtitle.getString())) {
            sub = Component.empty().append(model.subtitle).append("  ·  ").append(model.modeLabel);
        }
        f.text(m, sub, 0, top - 0.6f, z, 0.0054f, ReplayPalette.alpha(ReplayPalette.TEXT_MUTED, a * row2), 0, false, pw - 1.0f);
        stamp(f, m, hw - 0.42f, top - 0.45f, win, time, a, worldScale);

        // winners podium
        List<ReplayBoardModel.Actor> winners = model.winners;
        float headY = top - 1.1f;
        if (winners.isEmpty()) {
            f.text(m, ReplayBoardTexts.tr("no_winner"), 0, headY, z, 0.0065f,
                    ReplayPalette.alpha(ReplayPalette.TEXT_MUTED, a * row3), 0, false, pw - 0.4f);
        } else {
            int shown = Math.min(winners.size(), 8);
            boolean more = winners.size() > shown;
            if (more) shown = 7;
            int slots = shown + (more ? 1 : 0);
            float spacing = Math.min(0.58f, (pw - 0.5f) / slots);
            for (int i = 0; i < slots; i++) {
                float x = (i - (slots - 1) / 2f) * spacing;
                double ca = a * fadeIn(time, 1.1 + i * 0.14);
                if (ca <= 0.02) continue;
                if (i == shown) {
                    f.text(m, ReplayBoardTexts.tr("more_winners", winners.size() - shown), x, headY, z, 0.008f,
                            ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, ca), 0, false, spacing);
                    continue;
                }
                ReplayBoardModel.Actor actor = winners.get(i);
                float pop = (float) ReplayStagePlan.easeOutCubic(fadeIn(time, 1.1 + i * 0.14));
                float size = 0.2f * (0.6f + 0.4f * pop);
                float spin = (float) (time * 45 + i * 33);
                ReplayActorPainter.head(f, m, actor, x, headY + 0.02f, 0.12f, spin, size, 0xFFFFFFFF, ca);
                ReplayDraw.ring(glow, m, x, headY - 0.12f, 0.12f, 0.12f, 0.012f, 20,
                        ReplayPalette.alpha(actor.mvp ? ReplayPalette.BRASS_LIGHT : actor.color, 0.7 * ca),
                        ReplayPalette.alpha(actor.color, 0.1 * ca));
                if (actor == model.mvp) crown(glow, m, x, headY + 0.17f, 0.16f, f.anim, ca, worldScale);
                f.text(m, actor.name, x, headY - 0.2f, z, 0.0048f, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, ca), 0, false, spacing - 0.04f);
                f.text(m, actor.role, x, headY - 0.27f, z, 0.0042f, ReplayPalette.alpha(actor.color, ca), 0, false, spacing - 0.04f);
            }
        }

        // stats
        ReplayDraw.lineXY(ui, m, -hw + 0.3f, bottom + 0.56f, hw - 0.3f, bottom + 0.56f, 0.003f, 0.005f,
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.85 * a * row4), ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.85 * a * row4));
        if (model.mvp != null) {
            var p = model.mvp.p;
            Component mvp = ReplayBoardTexts.tr("mvp_line", model.mvp.name, p.score(), p.kills(),
                    ReplayStagePlan.clock(p.survivalSeconds() * 20.0), p.itemUses());
            f.text(m, mvp, 0, bottom + 0.45f, z, 0.010f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, a * row4), 0, false, pw - 0.4f);
        }
        Component summary = ReplayBoardTexts.tr("summary_line", ReplayStagePlan.clock(model.replay.durationTicks()),
                model.replay.killCount(), model.actors.length);
        f.text(m, summary, 0, bottom + 0.32f, z, 0.0075f, ReplayPalette.alpha(ReplayPalette.TEXT, a * row4), 0, false, pw - 0.4f);
        Component kills;
        if (model.firstKill == null) {
            kills = ReplayBoardTexts.tr("no_kill");
        } else if (model.lastKill == null || model.lastKill == model.firstKill) {
            kills = ReplayBoardTexts.killLine("first_kill", model.firstKill, model);
        } else {
            kills = Component.empty().append(ReplayBoardTexts.killLine("first_kill", model.firstKill, model))
                    .append("   ").append(ReplayBoardTexts.killLine("last_kill", model.lastKill, model));
        }
        f.text(m, kills, 0, bottom + 0.2f, z, 0.0042f, ReplayPalette.alpha(ReplayPalette.TEXT_MUTED, a * row4), 0, false, pw - 0.4f);
        if (model.replay.limited()) {
            f.text(m, ReplayBoardTexts.tr("limited"), 0, bottom + 0.1f, z, 0.0034f,
                    ReplayPalette.alpha(ReplayPalette.TEXT_FAINT, a * row4), 0, false, pw - 0.4f);
        }
    }

    private static double fadeIn(double time, double start) {
        return ReplayStagePlan.easeOutCubic((time - start) / 0.45);
    }

    private static void frame(VertexConsumer ui, Matrix4f m, float hw, float hh, float w, int c) {
        ReplayDraw.rectOutlineXY(ui, m, -hw, -hh, hw, hh, 0.003f, w, c);
    }

    private static void diamond(VertexConsumer vc, Matrix4f m, float x, float y, float r, int c) {
        ReplayDraw.quad(vc, m, x, y + r, 0.004f, x + r, y, 0.004f, x, y - r, 0.004f, x - r, y, 0.004f, c, c, c, c);
    }

    private static void stamp(ReplayFrame f, Matrix4f m, float x, float y, int color, double time, double a, float worldScale) {
        double k = ReplayStagePlan.clamp01((time - 0.9) / 0.35);
        if (k <= 0) return;
        float scale = (float) (1 + 0.6 * (1 - ReplayStagePlan.easeOutCubic(k)));
        Matrix4f sm = new Matrix4f(m).translate(x, y, 0.004f).rotateZ((float) Math.toRadians(-14)).scale(scale);
        VertexConsumer glow = f.glow();
        double sa = a * k;
        int c = ReplayPalette.alpha(color, 0.9 * sa);
        float segScale = worldScale * scale;
        int ring = 36;
        for (int i = 0; i < ring; i++) {
            float a0 = Mth.TWO_PI * i / ring, a1 = Mth.TWO_PI * (i + 1) / ring;
            ReplayDraw.segment(glow, sm, Mth.cos(a0) * 0.2f, Mth.sin(a0) * 0.2f, 0, Mth.cos(a1) * 0.2f, Mth.sin(a1) * 0.2f, 0, 0.012f * segScale, c, c);
            ReplayDraw.segment(glow, sm, Mth.cos(a0) * 0.165f, Mth.sin(a0) * 0.165f, 0, Mth.cos(a1) * 0.165f, Mth.sin(a1) * 0.165f, 0, 0.005f * segScale, c, c);
        }
        f.text(sm, ReplayBoardTexts.tr("stamp"), 0, 0.02f, 0.002f, 0.0075f, ReplayPalette.alpha(color, sa), 0, false, 0.3f);
        f.text(sm, Component.literal(ReplayStagePlan.clock(f.model.replay.durationTicks())), 0, -0.07f, 0.002f, 0.0042f,
                ReplayPalette.alpha(color, sa), 0, false, 0.3f);
    }

    private static void crown(VertexConsumer glow, Matrix4f m, float x, float y, float z, double anim, double a, float worldScale) {
        float w = 0.09f, h = 0.06f;
        float bob = (float) Math.sin(anim * 3) * 0.008f;
        Matrix4f cm = new Matrix4f(m).translate(x, y + bob, z);
        int gold = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, a), deep = ReplayPalette.alpha(ReplayPalette.LAMP, 0.6 * a);
        float[] xs = {-w, -w * 0.5f, 0, w * 0.5f, w};
        float[] ys = {h, h * 0.35f, h * 1.15f, h * 0.35f, h};
        ReplayDraw.segment(glow, cm, -w, 0, 0, w, 0, 0, 0.014f * worldScale, gold, gold);
        ReplayDraw.segment(glow, cm, -w, 0, 0, xs[0], ys[0], 0, 0.012f * worldScale, gold, gold);
        ReplayDraw.segment(glow, cm, w, 0, 0, xs[4], ys[4], 0, 0.012f * worldScale, gold, gold);
        for (int i = 0; i < 4; i++) ReplayDraw.segment(glow, cm, xs[i], ys[i], 0, xs[i + 1], ys[i + 1], 0, 0.012f * worldScale, gold, gold);
        ReplayDraw.quad(glow, cm, -w, 0, -0.001f, w, 0, -0.001f, w * 0.8f, h * 0.45f, -0.001f, -w * 0.8f, h * 0.45f, -0.001f,
                deep, deep, ReplayPalette.fade(deep, 0.2), ReplayPalette.fade(deep, 0.2));
        for (int i = 0; i < 5; i += 2) {
            ReplayDraw.quad(glow, cm, xs[i] - 0.012f, ys[i], 0.001f, xs[i], ys[i] + 0.012f, 0.001f, xs[i] + 0.012f, ys[i], 0.001f,
                    xs[i], ys[i] - 0.012f, 0.001f, ReplayPalette.alpha(ReplayPalette.RUBY, a), ReplayPalette.alpha(ReplayPalette.RUBY, a),
                    ReplayPalette.alpha(ReplayPalette.RUBY, a), ReplayPalette.alpha(ReplayPalette.RUBY, a));
        }
    }
}
