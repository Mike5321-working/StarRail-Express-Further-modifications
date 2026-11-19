package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static com.habitrain.core.scene.client.replay.ReplayPalette.*;

/**
 * Video-only 3D effects: one dedicated, presentation-timed effect per event kind (kill pillar and shards,
 * revival helix and halo, fireball and smoke ring, defuse dome, shield shatter, rune circles ...), the MVP
 * spotlight, a stage light pool and drifting dust that gives the floating diorama depth and parallax.
 *
 * <p>Effects run on video seconds rather than match ticks, so an impact keeps exploding while the match is
 * frozen around it.</p>
 */
final class ReplayVideoFx {
    private final ReplayVideoScript script;
    private final ReplayBoardModel model;
    private final double[] eventTimes;
    private double time;
    private ReplayVideoScript.Sample sample;

    ReplayVideoFx(ReplayVideoScript script, ReplayBoardModel model) {
        this.script = script;
        this.model = model;
        eventTimes = new double[model.events.length];
        for (int i = 0; i < eventTimes.length; i++) eventTimes[i] = script.videoTimeOf(model.events[i].node.tick());
    }

    void at(double time, ReplayVideoScript.Sample sample) {
        this.time = time;
        this.sample = sample;
    }

    // ------------------------------------------------------------------ outside the map scope

    void environment(ReplayFrame f, ReplayBoardSite s) {
        VertexConsumer glow = f.glow();
        Matrix4f b = s.board();
        float half = s.hw();
        float floor = ReplayBoardSite.TRAY_BOTTOM - 0.06f;
        ReplayDraw.disc(glow, b, 0, floor, 0, half * 1.8f, 56, alpha(BRASS, 0.17), alpha(BRASS, 0));
        ReplayDraw.ring(glow, b, 0, floor + 0.001f, 0, half * 1.32f + 0.06f * (float) Math.sin(time * 0.7), 0.035f, 72,
                alpha(BRASS_LIGHT, 0.14), alpha(BRASS_LIGHT, 0));
        ReplayDraw.ring(glow, b, 0, floor + 0.002f, 0, half * 1.62f, 0.012f, 72, alpha(PASSENGER, 0.08), alpha(PASSENGER, 0));
        // Dust motes on slow orbits: depth cues in front of and behind the model.
        for (int i = 0; i < 90; i++) {
            float a = (float) (rnd(i, 1) * Math.PI * 2 + time * 0.03 * (1 + i % 3) * (i % 2 == 0 ? 1 : -1));
            float r = half * (0.55f + 1.25f * rnd(i, 2));
            float y = -0.5f + 3.2f * rnd(i, 3) + 0.12f * (float) Math.sin(time * 0.35 + i);
            Vector3f p = b.transformPosition((float) Math.cos(a) * r, y, (float) Math.sin(a) * r, new Vector3f());
            double twinkle = 0.55 + 0.45 * Math.sin(time * (1.1 + rnd(i, 4) * 2.3) + i * 1.7);
            int c = i % 5 == 0 ? PASSENGER : i % 3 == 0 ? BRASS_LIGHT : CHAMPAGNE;
            float size = 0.01f + 0.022f * rnd(i, 5);
            ReplayDraw.billboard(glow, p, f.right, f.up, size, size, alpha(c, (0.12 + 0.28 * rnd(i, 6)) * twinkle));
        }
    }

    // ------------------------------------------------------------------ inside the map scope

    void map(ReplayFrame f, ReplayBoardSite s, Matrix4f d, ReplayActorSizing heads) {
        if (f.stage == null || f.stage.actors() <= 0.01) return;
        VertexConsumer glow = f.glow(), fill = f.fill();
        for (int i = 0; i < model.events.length; i++) {
            var e = model.events[i];
            double age = time - eventTimes[i];
            if (age < 0 || age > life(e.node.kind())) continue;
            int seed = e.node.tick() * 31 + e.node.kind().ordinal() * 7 + i;
            event(f, d, glow, fill, e, age, seed, heads);
        }
        spotlights(f, d, glow, heads);
    }

    static double life(LastMatchReplay.Kind kind) {
        return switch (kind) {
            case KILL -> 2.4;
            case PLAYER_REVIVAL -> 2.8;
            case GRENADE_EXPLOSION, BOMB_DETONATE -> 2.2;
            case BOMB_PLANT, POISON -> 2.0;
            case BOMB_DEFUSE -> 1.8;
            case BLACKOUT_START, BLACKOUT_END, DISGUISE, CHANGE_ROLE, PSYCHO_STATE_CHANGE -> 1.6;
            case SHIELD_BROKEN, SKILL_RELEASE, TRAP_TRIGGERED -> 1.4;
            case ROPE_PULL, GRENADE_THROWN -> 1.2;
            case GUNSHOT -> 0.7;
            default -> 1.0;
        };
    }

    private void event(ReplayFrame f, Matrix4f d, VertexConsumer glow, VertexConsumer fill, ReplayBoardModel.EventMark e,
                       double k, int seed, ReplayActorSizing heads) {
        float x = e.tx, y = e.ty + 0.004f, z = e.tz;
        double life = life(e.node.kind());
        double fade = 1 - ReplayStagePlan.smoothstep(life * 0.6, life, k);
        switch (e.node.kind()) {
            case KILL -> kill(f, d, glow, fill, e, x, y, z, k, fade, seed, heads.size(e.node.target(), x, e.ty, z));
            case PLAYER_REVIVAL -> revival(f, d, glow, x, y, z, k, fade,
                    heads.size(e.node.target() != null ? e.node.target() : e.node.actor(), x, e.ty, z));
            case GRENADE_EXPLOSION, BOMB_DETONATE -> explosion(f, d, glow, fill, x, y, z, k, fade, seed);
            case BOMB_PLANT -> {
                boolean on = Math.sin(k * 16) > 0;
                Vector3f c = d.transformPosition(x, y + 0.04f, z, new Vector3f());
                soft(glow, f, c, 0.07f, alpha(RUBY, (on ? 0.95 : 0.25) * fade));
                int left = 12 - (int) Math.floor(k / life * 12);
                for (int i = 0; i < left; i++) arc(glow, d, x, y + 0.003f, z, 0.13f, i * 30 + 3, i * 30 + 25, 0.016f, alpha(RUBY, 0.9 * fade));
                ReplayDraw.column(glow, d, x, y, z, 0.35f, 0.012f, alpha(RUBY, 0.6 * fade), alpha(RUBY, 0));
            }
            case BOMB_DEFUSE -> {
                double grow = 1 + 0.45 * ReplayStagePlan.easeOutCubic(k / 0.8);
                float[] heights = {0, 0.06f, 0.12f}, radii = {0.18f, 0.15f, 0.09f};
                for (int i = 0; i < 3; i++) polygon(glow, d, x, y + heights[i], z, (float) (radii[i] * grow), 6, (float) (k * 0.6 + i * 0.3),
                        0.012f, alpha(SHIELD, 0.85 * fade));
                flash(f, d, glow, x, y + 0.06f, z, k, 0.25, 0.26f, SHIELD);
                sparkles(f, d, glow, x, y, z, k, fade, seed, 8, SHIELD, 0.22f);
            }
            case GUNSHOT -> {
                double beam = 1 - ReplayStagePlan.smoothstep(0, 0.55, k);
                boolean hit = e.has(LastMatchReplay.HIT);
                int core = e.has(LastMatchReplay.BACKFIRE) ? EXPLOSION : 0xFFFFFFFF;
                ReplayDraw.segment(glow, d, e.fx, e.fy + 0.06f, e.fz, e.tx, e.ty + 0.06f, e.tz, 0.03f,
                        alpha(LAMP, 0.15 * beam), alpha(core, 0.75 * beam));
                double head = ReplayStagePlan.easeOutCubic(k / 0.14);
                Vector3f p = d.transformPosition(lerp(e.fx, e.tx, head), lerp(e.fy, e.ty, head) + 0.06f, lerp(e.fz, e.tz, head), new Vector3f());
                soft(glow, f, p, 0.04f, alpha(0xFFFFFFFF, beam));
                if (hit) flash(f, d, glow, e.tx, e.ty + 0.06f, e.tz, k, 0.3, 0.16f, RUBY);
            }
            case POISON -> {
                for (int i = 0; i < 11; i++) {
                    double h = (k * 0.22 + rnd(seed, i) * 0.16);
                    float wob = (float) Math.sin(k * 5 + i) * 0.03f;
                    Vector3f p = d.transformPosition(x + (rnd(seed, i + 20) - 0.5f) * 0.18f + wob, y + (float) h,
                            z + (rnd(seed, i + 40) - 0.5f) * 0.18f, new Vector3f());
                    float size = 0.012f + 0.016f * rnd(seed, i + 60);
                    soft(glow, f, p, size * 1.3f, alpha(POISON, 0.8 * fade));
                }
                ReplayDraw.ring(glow, d, x, y, z, (float) (0.06 + 0.16 * ReplayStagePlan.easeOutCubic(k / 1.2)), 0.02f, 28,
                        alpha(POISON, 0.7 * fade), alpha(POISON, 0));
            }
            case SHIELD_BROKEN -> {
                flash(f, d, glow, x, y + 0.08f, z, k, 0.25, 0.22f, SHIELD);
                Vector3f c = d.transformPosition(x, y + 0.08f, z, new Vector3f());
                for (int i = 0; i < 7; i++) {
                    double ang = rnd(seed, i) * Math.PI * 2;
                    float out = (float) (0.04 + 0.32 * ReplayStagePlan.easeOutCubic(k / 0.9));
                    Vector3f p = new Vector3f(c).add(new Vector3f(f.right).mul((float) Math.cos(ang) * out))
                            .add(new Vector3f(f.up).mul((float) Math.sin(ang) * out - (float) (k * k) * 0.08f));
                    screenPolygon(glow, f, p, 0.032f, 6, (float) (k * (3 + i)), 0.008f, alpha(SHIELD, fade));
                }
            }
            case DISGUISE -> {
                for (int i = 0; i < 8; i++) {
                    double ang = rnd(seed, i) * Math.PI * 2 + k * 1.4;
                    float r = (float) (0.04 + 0.16 * ReplayStagePlan.easeOutCubic(k / 1.1));
                    Vector3f p = d.transformPosition(x + (float) Math.cos(ang) * r, y + 0.05f + 0.1f * (float) k, z + (float) Math.sin(ang) * r, new Vector3f());
                    float size = 0.04f + 0.03f * rnd(seed, i + 9);
                    soft(fill, f, p, size * 1.4f, alpha(0xFF5B2E8C, 0.38 * fade));
                }
                sparkles(f, d, glow, x, y, z, k, fade, seed, 7, DISGUISE, 0.18f);
            }
            case CHANGE_ROLE -> {
                int color = actorColor(e.node.actor());
                double up = ReplayStagePlan.easeOutCubic(k / 0.9);
                ReplayDraw.ring(glow, d, x, y + (float) (up * 0.36), z, 0.11f, 0.02f, 28, alpha(color, 0.9 * fade), alpha(color, 0.15 * fade));
                for (int i = 0; i < 8; i++) {
                    double ang = i * Math.PI / 4 + k;
                    float cx = x + (float) Math.cos(ang) * 0.1f, cz = z + (float) Math.sin(ang) * 0.1f;
                    ReplayDraw.segment(glow, d, cx, y, cz, cx, y + (float) (up * 0.3), cz, 0.01f, alpha(color, 0.7 * fade), alpha(color, 0));
                }
            }
            case SKILL_RELEASE -> {
                int color = actorColor(e.node.actor());
                float scale = (float) (0.6 + 0.4 * ReplayStagePlan.easeOutCubic(k / 0.4));
                ReplayDraw.ring(glow, d, x, y + 0.004f, z, 0.15f * scale, 0.012f, 36, alpha(color, 0.9 * fade), alpha(color, 0.2 * fade));
                star(glow, d, x, y + 0.005f, z, 0.14f * scale, 5, (float) (k * 1.2), 0.009f, alpha(color, 0.85 * fade));
                polygon(glow, d, x, y + 0.006f, z, 0.08f * scale, 8, (float) (-k * 2), 0.007f, alpha(CHAMPAGNE, 0.7 * fade));
                flash(f, d, glow, x, y + 0.05f, z, k, 0.25, 0.14f, color);
            }
            case ITEM_USED -> sparkles(f, d, glow, x, y, z, k, fade, seed, 5, BRASS_LIGHT, 0.12f);
            case TRAP_TRIGGERED -> {
                double close = ReplayStagePlan.easeOutCubic(k / 0.18);
                for (int i = 0; i < 8; i++) {
                    double ang = i * Math.PI / 4;
                    float r0 = (float) (0.17 - 0.06 * close), r1 = (float) (0.17 - 0.13 * close);
                    float bx = x + (float) Math.cos(ang) * r0, bz = z + (float) Math.sin(ang) * r0;
                    float tx = x + (float) Math.cos(ang) * r1, tz = z + (float) Math.sin(ang) * r1;
                    ReplayDraw.segment(glow, d, bx, y + 0.01f, bz, tx, y + 0.07f, tz, 0.02f, alpha(BRASS, 0.9 * fade), alpha(RUBY, fade));
                }
                flash(f, d, glow, x, y + 0.05f, z, k, 0.2, 0.18f, RUBY);
            }
            case ROPE_PULL, GRENADE_THROWN -> {
                int color = e.node.kind() == LastMatchReplay.Kind.GRENADE_THROWN ? EXPLOSION : LAMP;
                Vector3f prev = null;
                float dist = (float) Math.hypot(e.tx - e.fx, e.tz - e.fz);
                double travel = ReplayStagePlan.easeOutCubic(k / 0.6);
                for (int i = 0; i <= 16; i++) {
                    float u = i / 16f;
                    float h = 4 * u * (1 - u) * Math.max(0.05f, dist * 0.3f);
                    Vector3f p = d.transformPosition(lerp(e.fx, e.tx, u), lerp(e.fy, e.ty, u) + h + 0.04f, lerp(e.fz, e.tz, u), new Vector3f());
                    if (prev != null && u <= travel + 0.07) ReplayDraw.segment(glow, prev, p, 0.012f, alpha(color, 0.75 * fade), alpha(color, 0.75 * fade));
                    prev = p;
                }
                float u = (float) travel;
                float h = 4 * u * (1 - u) * Math.max(0.05f, dist * 0.3f);
                Vector3f head = d.transformPosition(lerp(e.fx, e.tx, u), lerp(e.fy, e.ty, u) + h + 0.04f, lerp(e.fz, e.tz, u), new Vector3f());
                ReplayDraw.star(glow, head, f.right, f.up, 0.05f, 8, (float) (k * 6), alpha(0xFFFFFFFF, fade), alpha(color, 0));
            }
            case PSYCHO_STATE_CHANGE -> {
                double grow = ReplayStagePlan.easeOutCubic(k / 0.5);
                for (int i = 0; i < 12; i++) {
                    double ang = i * Math.PI / 6 + rnd(seed, i) * 0.3;
                    float r = (float) (0.06 + 0.2 * grow * (0.6 + 0.4 * rnd(seed, i + 12)));
                    ReplayDraw.segment(glow, d, x, y + 0.02f, z, x + (float) Math.cos(ang) * r, y + 0.02f, z + (float) Math.sin(ang) * r,
                            0.016f, alpha(PSYCHO, fade), alpha(RUBY, 0));
                }
                ReplayDraw.column(glow, d, x, y, z, (float) (0.4 * grow), 0.07f, alpha(PSYCHO, 0.6 * fade), alpha(PSYCHO, 0));
                flash(f, d, glow, x, y + 0.08f, z, k, 0.25, 0.2f, PSYCHO);
            }
            case BLACKOUT_START, BLACKOUT_END -> {
                boolean start = e.node.kind() == LastMatchReplay.Kind.BLACKOUT_START;
                double r = 0.1 + 4.5 * ReplayStagePlan.easeOutCubic(k / 1.2);
                ReplayDraw.ring(glow, d, x, y + 0.01f, z, (float) r, 0.06f, 64, alpha(start ? 0xFF8FB0FF : LAMP, 0.55 * fade), alpha(NIGHT_0, 0));
            }
            default -> {
                // Door, lock-pick, pry and seal: a pulsing door frame facing the camera.
                Vector3f c = d.transformPosition(x, y, z, new Vector3f());
                int color = switch (e.node.kind()) {
                    case DOOR_SEAL, DOOR_LOCK -> SHIELD;
                    case DOOR_UNLOCK, DOOR_OPEN -> POISON;
                    default -> LAMP;
                };
                float w = 0.05f, h = 0.13f;
                double pulse = 1 + 0.2 * Math.sin(k * 14);
                Vector3f r = new Vector3f(f.right).mul((float) (w * pulse)), u = new Vector3f(f.up).mul((float) (h * pulse));
                Vector3f bl = new Vector3f(c).sub(r), br = new Vector3f(c).add(r);
                Vector3f tl = new Vector3f(bl).add(u), tr = new Vector3f(br).add(u);
                int col = alpha(color, 0.9 * fade);
                ReplayDraw.segment(glow, bl, tl, 0.01f, col, col);
                ReplayDraw.segment(glow, tl, tr, 0.01f, col, col);
                ReplayDraw.segment(glow, tr, br, 0.01f, col, col);
                if (e.node.kind() == LastMatchReplay.Kind.DOOR_PRY || e.node.kind() == LastMatchReplay.Kind.LOCKPICK_ATTEMPT) {
                    sparkles(f, d, glow, x, y + 0.06f, z, k, fade, seed, 5, LAMP, 0.1f);
                }
            }
        }
    }

    private void kill(ReplayFrame f, Matrix4f d, VertexConsumer glow, VertexConsumer fill, ReplayBoardModel.EventMark e,
                      float x, float y, float z, double k, double fade, int seed, float headSize) {
        flash(f, d, glow, x, y + 0.07f, z, k, 0.32, 0.3f, RUBY);
        // Pillar of light.
        float h = (float) (0.95 * ReplayStagePlan.easeOutCubic(k / 0.22));
        float r = (float) (0.055 * (1 - ReplayStagePlan.easeOutCubic(k / 1.3)) + 0.008);
        double pa = 1 - ReplayStagePlan.smoothstep(0.5, 1.8, k);
        ReplayDraw.column(glow, d, x, y, z, h, r, alpha(RUBY, 0.75 * pa), alpha(RUBY, 0));
        ReplayDraw.column(glow, d, x, y, z, h * 0.8f, r * 0.35f, alpha(0xFFFFFFFF, 0.85 * pa), alpha(0xFFFFE0E0, 0));
        // Shockwaves.
        for (int i = 0; i < 3; i++) {
            double kk = (k - i * 0.09) / 0.7;
            if (kk <= 0 || kk >= 1) continue;
            ReplayDraw.ring(glow, d, x, y + 0.002f * i, z, (float) (0.04 + 0.55 * ReplayStagePlan.easeOutCubic(kk)),
                    (float) (0.026 * (1 - kk) + 0.006), 36, alpha(i == 0 ? 0xFFFFFFFF : RUBY, (1 - kk) * 0.9), alpha(RUBY, 0));
        }
        // Shards with gravity.
        if (k < 0.95) {
            for (int i = 0; i < 16; i++) {
                double ang = rnd(seed, i) * Math.PI * 2;
                double speed = 0.35 + 0.45 * rnd(seed, i + 16), up = 0.45 + 0.6 * rnd(seed, i + 32);
                double t = k;
                float px = x + (float) (Math.cos(ang) * speed * t), pz = z + (float) (Math.sin(ang) * speed * t);
                float py = y + 0.06f + (float) (up * t - 1.7 * t * t);
                float vx = (float) (Math.cos(ang) * speed), vz = (float) (Math.sin(ang) * speed), vy = (float) (up - 3.4 * t);
                double a = 1 - k / 0.95;
                ReplayDraw.segment(glow, d, px - vx * 0.07f, py - vy * 0.07f, pz - vz * 0.07f, px, py, pz, 0.013f,
                        alpha(RUBY, 0.2 * a), alpha(LAMP, a));
            }
        }
        // Scorch mark grounds the moment.
        double scorch = ReplayStagePlan.easeOutCubic(k / 0.2) * (1 - ReplayStagePlan.smoothstep(1.6, 2.4, k));
        ReplayDraw.disc(fill, d, x, y + 0.002f, z, 0.13f, 20, alpha(NIGHT_0, 0.55 * scorch), alpha(NIGHT_0, 0));
        // Tracer from the killer.
        if (e.hasFrom && k < 0.5) {
            double tk = 1 - k / 0.5;
            ReplayDraw.segment(glow, d, e.fx, e.fy + 0.07f, e.fz, x, y + 0.07f, z, 0.03f, alpha(0xFFFFFFFF, 0.25 * tk), alpha(RUBY, 0.95 * tk));
            double head = ReplayStagePlan.easeOutCubic(k / 0.16);
            Vector3f p = d.transformPosition(lerp(e.fx, x, head), lerp(e.fy, y, head) + 0.07f, lerp(e.fz, z, head), new Vector3f());
            ReplayDraw.star(glow, p, f.right, f.up, 0.07f, 8, (float) (k * 5), alpha(0xFFFFFFFF, tk), alpha(RUBY, 0));
        }
        // Target reticle locking on above the victim.
        double lock = ReplayStagePlan.easeOutCubic(k / 0.25);
        float rr = (float) (1.33 - 0.50 * lock) * headSize;
        Vector3f c = d.transformPosition(x, y + ReplayActorSizing.centerOffset(headSize), z, new Vector3f());
        for (int i = 0; i < 4; i++) {
            double a0 = i * Math.PI / 2 + Math.PI / 4 + k * 0.8;
            Vector3f p0 = new Vector3f(c).add(new Vector3f(f.right).mul((float) Math.cos(a0 - 0.35) * rr)).add(new Vector3f(f.up).mul((float) Math.sin(a0 - 0.35) * rr));
            Vector3f p1 = new Vector3f(c).add(new Vector3f(f.right).mul((float) Math.cos(a0 + 0.35) * rr)).add(new Vector3f(f.up).mul((float) Math.sin(a0 + 0.35) * rr));
            ReplayDraw.segment(glow, p0, p1, 0.012f, alpha(RUBY, 0.9 * fade), alpha(RUBY, 0.9 * fade));
        }
    }

    private void revival(ReplayFrame f, Matrix4f d, VertexConsumer glow, float x, float y, float z, double k, double fade, float headSize) {
        flash(f, d, glow, x, y + 0.08f, z, k, 0.3, 0.3f, BRASS_LIGHT);
        float h = (float) (0.85 * ReplayStagePlan.easeOutCubic(k / 0.4));
        ReplayDraw.column(glow, d, x, y, z, h, 0.1f, alpha(BRASS_LIGHT, 0.6 * fade), alpha(BRASS_LIGHT, 0));
        ReplayDraw.column(glow, d, x, y, z, h, 0.03f, alpha(CHAMPAGNE, 0.85 * fade), alpha(CHAMPAGNE, 0));
        for (int strand = 0; strand < 2; strand++) {
            for (int j = 0; j < 12; j++) {
                double hh = (k * 0.55 + j / 12.0) % 1.0;
                double ang = hh * Math.PI * 4 + strand * Math.PI + k * 2;
                float rr = (float) (0.11 * (1 - hh * 0.5));
                Vector3f p = d.transformPosition(x + (float) Math.cos(ang) * rr, y + (float) (hh * 0.8), z + (float) Math.sin(ang) * rr, new Vector3f());
                float size = (float) (0.026 * (1 - hh) + 0.006);
                ReplayDraw.star(glow, p, f.right, f.up, size, 4, (float) ang, alpha(strand == 0 ? CHAMPAGNE : BRASS_LIGHT, fade * (1 - hh)),
                        alpha(BRASS, 0));
            }
        }
        double settle = ReplayStagePlan.easeOutCubic(k / 0.8);
        float hy = y + (float) (headSize * 1.35 * settle);
        float rr = (float) (0.32 + (headSize * 0.625f - 0.32) * settle);
        ReplayDraw.ring(glow, d, x, hy, z, rr, 0.014f, 32, alpha(BRASS_LIGHT, fade), alpha(CHAMPAGNE, 0.3 * fade));
        star(glow, d, x, y + 0.004f, z, 0.17f, 8, (float) (k * 0.8), 0.008f, alpha(BRASS, 0.8 * fade));
    }

    private void explosion(ReplayFrame f, Matrix4f d, VertexConsumer glow, VertexConsumer fill, float x, float y, float z,
                           double k, double fade, int seed) {
        double grow = ReplayStagePlan.easeOutCubic(k / 0.25), burn = 1 - ReplayStagePlan.smoothstep(0.25, 0.95, k);
        int[] colors = {0xFFFFFFFF, LAMP, EXPLOSION, EXPLOSION, 0xFFD9481B};
        for (int i = 4; i >= 0; i--) {
            float size = (float) ((0.07 + 0.065 * i) * grow);
            Vector3f c = d.transformPosition(x + (rnd(seed, i) - 0.5f) * 0.05f, y + 0.07f + (float) k * 0.16f + i * 0.012f,
                    z + (rnd(seed, i + 5) - 0.5f) * 0.05f, new Vector3f());
            soft(glow, f, c, size * 1.4f, alpha(colors[i], (0.6 - i * 0.07) * burn));
        }
        double smoke = ReplayStagePlan.easeOutCubic(k / 1.6);
        ReplayDraw.ring(fill, d, x, y + 0.03f + (float) smoke * 0.05f, z, (float) (0.1 + 0.42 * smoke), 0.08f, 36,
                alpha(0xFF1A1410, 0.5 * fade), alpha(0xFF1A1410, 0));
        double kk = k / 0.5;
        if (kk < 1) ReplayDraw.ring(glow, d, x, y + 0.004f, z, (float) (0.05 + 0.95 * ReplayStagePlan.easeOutCubic(kk)), 0.03f, 48,
                alpha(LAMP, (1 - kk) * 0.9), alpha(EXPLOSION, 0));
        if (k < 1.2) {
            for (int i = 0; i < 20; i++) {
                double ang = rnd(seed, i + 50) * Math.PI * 2;
                double speed = 0.4 + 0.7 * rnd(seed, i + 70), up = 0.5 + 0.9 * rnd(seed, i + 90);
                float px = x + (float) (Math.cos(ang) * speed * k), pz = z + (float) (Math.sin(ang) * speed * k);
                float py = y + 0.05f + (float) (up * k - 1.9 * k * k);
                if (py < y) continue;
                Vector3f p = d.transformPosition(px, py, pz, new Vector3f());
                float size = (float) (0.016 * (1 - k / 1.2));
                ReplayDraw.billboard(glow, p, f.right, f.up, size, size, alpha(i % 3 == 0 ? 0xFFFFFFFF : LAMP, 1 - k / 1.2));
            }
        }
        ReplayDraw.disc(fill, d, x, y + 0.002f, z, 0.2f, 20, alpha(NIGHT_0, 0.6 * fade * grow), alpha(NIGHT_0, 0));
    }

    private void spotlights(ReplayFrame f, Matrix4f d, VertexConsumer glow, ReplayActorSizing heads) {
        if (sample == null) return;
        double weight = switch (sample.act()) {
            case VICTORY -> 0.5 * ReplayStagePlan.easeOutCubic(sample.actTime() / 0.8);
            case MVP -> 0.5 + 0.5 * ReplayStagePlan.easeOutCubic(sample.actTime() / 0.6);
            case OUTRO -> 0.7;
            default -> 0;
        };
        if (sample.act() == ReplayVideoScript.Act.VICTORY) {
            for (var actor : model.winners) {
                var pose = actor.track.sample(script.duration());
                if (pose.state() != ReplayTrackSampler.State.ALIVE) continue;
                float[] p = new float[3];
                model.project(pose.x(), pose.y(), pose.z(), p);
                double rise = ReplayStagePlan.easeOutCubic(sample.actTime() / 0.9);
                ReplayDraw.column(glow, d, p[0], p[1], p[2], (float) (0.7 * rise), 0.045f, alpha(BRASS_LIGHT, 0.5), alpha(BRASS_LIGHT, 0));
            }
        }
        if (weight <= 0.01 || model.mvp == null) return;
        var pose = model.mvp.track.sample(script.duration());
        if (!pose.visible()) return;
        float[] p = new float[3];
        model.project(pose.x(), pose.y(), pose.z(), p);
        float x = p[0], y = p[1] + 0.004f, z = p[2];
        float top = 1.7f;
        int segments = 18;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            ReplayDraw.quad(glow, d, x + c0 * 0.035f, y + top, z + s0 * 0.035f, x + c1 * 0.035f, y + top, z + s1 * 0.035f,
                    x + c1 * 0.24f, y, z + s1 * 0.24f, x + c0 * 0.24f, y, z + s0 * 0.24f,
                    alpha(CHAMPAGNE, 0.02 * weight), alpha(CHAMPAGNE, 0.02 * weight), alpha(CHAMPAGNE, 0.11 * weight), alpha(CHAMPAGNE, 0.11 * weight));
        }
        ReplayDraw.disc(glow, d, x, y, z, 0.26f, 32, alpha(BRASS_LIGHT, 0.45 * weight), alpha(BRASS_LIGHT, 0));
        float spin = (float) time;
        for (int i = 0; i < 3; i++) arc(glow, d, x, y + 0.003f, z, 0.19f, i * 120 + spin * 40, i * 120 + 80 + spin * 40, 0.012f, alpha(BRASS_LIGHT, 0.85 * weight));
        for (int i = 0; i < 4; i++) arc(glow, d, x, y + 0.004f, z, 0.23f, i * 90 - spin * 60, i * 90 + 40 - spin * 60, 0.007f, alpha(CHAMPAGNE, 0.6 * weight));
        for (int i = 0; i < 10; i++) {
            double ang = time * 0.9 + i * Math.PI / 5;
            float yy = y + 0.05f + 0.3f * (float) ((i * 0.37 + time * 0.12) % 1.0);
            Vector3f s = d.transformPosition(x + (float) Math.cos(ang) * 0.15f, yy, z + (float) Math.sin(ang) * 0.15f, new Vector3f());
            ReplayDraw.star(glow, s, f.right, f.up, 0.022f, 4, (float) ang, alpha(CHAMPAGNE, 0.9 * weight), alpha(BRASS, 0));
        }
        // A crown floats above the champion.
        float bob = (float) Math.sin(time * 2.6) * 0.012f;
        float headSize = heads.size(model.mvp.p.id(), x, p[1], z);
        Vector3f c = d.transformPosition(x, y + headSize * 2.6f + bob, z, new Vector3f());
        crown(glow, f, c, 0.045f, alpha(BRASS_LIGHT, weight));
    }

    // ------------------------------------------------------------------ primitives

    private static void flash(ReplayFrame f, Matrix4f d, VertexConsumer glow, float x, float y, float z, double k, double length,
                              float size, int color) {
        if (k >= length) return;
        double a = 1 - k / length;
        Vector3f c = d.transformPosition(x, y, z, new Vector3f());
        soft(glow, f, c, size * (float) (0.6 + 0.4 * a), alpha(color, 0.6 * a));
        soft(glow, f, c, size * 0.35f, alpha(0xFFFFFFFF, 0.9 * a));
        ReplayDraw.star(glow, c, f.right, f.up, size * 1.2f * (float) a, 8, (float) k, alpha(0xFFFFFFFF, 0.7 * a), alpha(color, 0));
    }

    /** Camera-facing round glow: bright centre fading to nothing, no hard quad edges. */
    private static void soft(VertexConsumer vc, ReplayFrame f, Vector3f c, float r, int color) {
        if ((color >>> 24) == 0 || r <= 0) return;
        int edge = alpha(color, 0);
        for (int i = 0; i < 16; i++) {
            double a0 = Math.PI * 2 * i / 16, a1 = Math.PI * 2 * (i + 1) / 16;
            Vector3f p0 = at(f, c, (float) Math.cos(a0) * r, (float) Math.sin(a0) * r);
            Vector3f p1 = at(f, c, (float) Math.cos(a1) * r, (float) Math.sin(a1) * r);
            vc.addVertex(c.x, c.y, c.z).setColor(color);
            vc.addVertex(c.x, c.y, c.z).setColor(color);
            vc.addVertex(p1.x, p1.y, p1.z).setColor(edge);
            vc.addVertex(p0.x, p0.y, p0.z).setColor(edge);
        }
    }

    private static void sparkles(ReplayFrame f, Matrix4f d, VertexConsumer glow, float x, float y, float z, double k, double fade,
                                 int seed, int count, int color, float spread) {
        for (int i = 0; i < count; i++) {
            double delay = rnd(seed, i + 100) * 0.3;
            double kk = (k - delay) / 0.7;
            if (kk <= 0 || kk >= 1) continue;
            Vector3f p = d.transformPosition(x + (rnd(seed, i + 110) - 0.5f) * spread, y + 0.04f + (float) kk * 0.12f,
                    z + (rnd(seed, i + 120) - 0.5f) * spread, new Vector3f());
            float size = (float) (0.035 * Math.sin(kk * Math.PI));
            ReplayDraw.star(glow, p, f.right, f.up, size, 4, (float) (kk * 2), alpha(0xFFFFFFFF, fade), alpha(color, 0));
        }
    }

    private static void arc(VertexConsumer glow, Matrix4f d, float x, float y, float z, float r, double fromDeg, double toDeg,
                            float width, int color) {
        int steps = Math.max(2, (int) Math.ceil((toDeg - fromDeg) / 8));
        for (int i = 0; i < steps; i++) {
            double a0 = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / steps), a1 = Math.toRadians(fromDeg + (toDeg - fromDeg) * (i + 1) / steps);
            ReplayDraw.segment(glow, d, x + (float) Math.cos(a0) * r, y, z + (float) Math.sin(a0) * r,
                    x + (float) Math.cos(a1) * r, y, z + (float) Math.sin(a1) * r, width, color, color);
        }
    }

    private static void polygon(VertexConsumer glow, Matrix4f d, float x, float y, float z, float r, int sides, float spin,
                                float width, int color) {
        for (int i = 0; i < sides; i++) {
            double a0 = spin + Math.PI * 2 * i / sides, a1 = spin + Math.PI * 2 * (i + 1) / sides;
            ReplayDraw.segment(glow, d, x + (float) Math.cos(a0) * r, y, z + (float) Math.sin(a0) * r,
                    x + (float) Math.cos(a1) * r, y, z + (float) Math.sin(a1) * r, width, color, color);
        }
    }

    /** Star polygon (pentagram for 5) in the XZ plane. */
    private static void star(VertexConsumer glow, Matrix4f d, float x, float y, float z, float r, int points, float spin,
                             float width, int color) {
        int step = points == 5 ? 2 : 3;
        for (int i = 0; i < points; i++) {
            double a0 = spin + Math.PI * 2 * i / points, a1 = spin + Math.PI * 2 * ((i + step) % points) / points;
            ReplayDraw.segment(glow, d, x + (float) Math.cos(a0) * r, y, z + (float) Math.sin(a0) * r,
                    x + (float) Math.cos(a1) * r, y, z + (float) Math.sin(a1) * r, width, color, color);
        }
    }

    private static void screenPolygon(VertexConsumer glow, ReplayFrame f, Vector3f c, float r, int sides, float spin, float width, int color) {
        Vector3f prev = null;
        for (int i = 0; i <= sides; i++) {
            double a = spin + Math.PI * 2 * i / sides;
            Vector3f p = new Vector3f(c).add(new Vector3f(f.right).mul((float) Math.cos(a) * r)).add(new Vector3f(f.up).mul((float) Math.sin(a) * r));
            if (prev != null) ReplayDraw.segment(glow, prev, p, width, color, color);
            prev = p;
        }
    }

    private static void crown(VertexConsumer glow, ReplayFrame f, Vector3f c, float w, int color) {
        float h = w * 0.7f;
        float[] xs = {-w, -w * 0.5f, 0, w * 0.5f, w};
        float[] ys = {h, h * 0.35f, h * 1.2f, h * 0.35f, h};
        Vector3f base0 = at(f, c, -w, 0), base1 = at(f, c, w, 0);
        ReplayDraw.segment(glow, base0, base1, w * 0.18f, color, color);
        ReplayDraw.segment(glow, base0, at(f, c, xs[0], ys[0]), w * 0.15f, color, color);
        ReplayDraw.segment(glow, base1, at(f, c, xs[4], ys[4]), w * 0.15f, color, color);
        for (int i = 0; i < 4; i++) ReplayDraw.segment(glow, at(f, c, xs[i], ys[i]), at(f, c, xs[i + 1], ys[i + 1]), w * 0.15f, color, color);
        for (int i = 0; i < 5; i += 2) ReplayDraw.billboard(glow, at(f, c, xs[i], ys[i] + w * 0.12f), f.right, f.up, w * 0.13f, w * 0.13f,
                fade(RUBY, ((color >>> 24) & 0xFF) / 255.0));
    }

    private static Vector3f at(ReplayFrame f, Vector3f c, float x, float y) {
        return new Vector3f(c).add(new Vector3f(f.right).mul(x)).add(new Vector3f(f.up).mul(y));
    }

    private int actorColor(java.util.UUID id) {
        var actor = model.participant(id);
        return actor == null ? BRASS_LIGHT : actor.color;
    }

    private static float lerp(float a, float b, double t) { return (float) (a + (b - a) * t); }

    static float rnd(int seed, int i) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }
}
