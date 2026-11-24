package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.GameModeIds;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplayCinemaDirector;
import com.habitrain.core.scene.replay.ReplayExportContentOptions;
import com.habitrain.core.scene.replay.ReplayHighlightScorer;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.habitrain.core.scene.replay.ReplayVideoScript.Act;
import com.habitrain.core.scene.replay.ReplayVideoScript.Beat;
import com.habitrain.core.scene.replay.ReplayVideoScript.BeatType;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.habitrain.core.scene.client.replay.ReplayPalette.*;
import static com.habitrain.core.scene.client.replay.ReplayVideoCanvas.Tex;

/**
 * Designed 2D layers of the exported video, composited around the 3D diorama:
 * <ol>
 *     <li>background: night gradient, parallax nebula and star field, stage back-light and god rays;</li>
 *     <li>foreground: parallax bokeh, grade, vignette, grain, letterbox, impact flashes and speed lines;</li>
 *     <li>interface: the boarding-ticket title, match HUD, highlight titles and cards, 3D-anchored callouts,
 *     event feed, overview stats, victory banner, MVP showcase, passenger list and act wipes.</li>
 * </ol>
 */
final class ReplayVideoOverlay {
    private static final int MAX_FEED = 5;
    final @Nullable ReplayVideoScript script;
    final @Nullable ReplayCinemaDirector.DirectedFilm film;
    final ReplayExportContentOptions options;
    final ReplayBoardModel model;
    final boolean compress;
    final ReplayExportQuality quality;
    private final Map<LastMatchReplay.Node, ReplayBoardModel.EventMark> marks = new IdentityHashMap<>();
    final List<ReplayBoardModel.Actor> ranking;
    final String date, trainNo;
    final int maxScore, maxKills, maxItems, maxSurvival, objectiveHighlights;
    private float w, h;
    private final @Nullable ReplayKineticOverlay kinetic;

    ReplayVideoOverlay(ReplayVideoScript script, ReplayBoardModel model) {
        this(script, model, false, ReplayExportQuality.DEFAULT);
    }

    ReplayVideoOverlay(ReplayVideoScript script, ReplayBoardModel model, boolean compress) {
        this(script, model, compress, ReplayExportQuality.DEFAULT);
    }

    ReplayVideoOverlay(ReplayVideoScript script, ReplayBoardModel model, boolean compress, ReplayExportQuality quality) {
        this(script, null, ReplayExportContentOptions.defaultsFor(ReplayExportContentOptions.ExportMode.SHORT, script.focusPlayer()),
                model, compress, quality);
    }

    ReplayVideoOverlay(ReplayCinemaDirector.DirectedFilm film, ReplayBoardModel model, boolean compress, ReplayExportQuality quality) {
        this(null, film, film.options(), model, compress, quality);
    }

    private ReplayVideoOverlay(@Nullable ReplayVideoScript script,
                               @Nullable ReplayCinemaDirector.DirectedFilm film,
                               ReplayExportContentOptions options,
                               ReplayBoardModel model, boolean compress, ReplayExportQuality quality) {
        this.script = script;
        this.film = film;
        this.options = options == null ? ReplayExportContentOptions.defaultsFor(ReplayExportContentOptions.ExportMode.SHORT) : options;
        this.model = model;
        this.compress = compress;
        this.quality = quality == null ? ReplayExportQuality.DEFAULT : quality;
        for (var mark : model.events) marks.put(mark.node, mark);
        List<ReplayBoardModel.Actor> rank = new ArrayList<>(List.of(model.actors));
        rank.sort(Comparator.comparing((ReplayBoardModel.Actor a) -> !a.p.winner()).thenComparing(a -> a != model.mvp)
                .thenComparingInt(a -> -a.p.score()).thenComparingInt(a -> -a.p.kills()).thenComparing(a -> a.p.name()));
        ranking = List.copyOf(rank);
        date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        trainNo = model.replay.roundId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        int score = 1, kills = 1, items = 1, survival = 1;
        for (var a : model.actors) {
            score = Math.max(score, a.p.score());
            kills = Math.max(kills, a.p.kills());
            items = Math.max(items, a.p.itemUses());
            survival = Math.max(survival, a.p.survivalSeconds());
        }
        maxScore = score; maxKills = kills; maxItems = items; maxSurvival = survival;
        // Objective mode-invariant highlight count computed from the match replay itself.
        objectiveHighlights = new ReplayVideoScript(model.replay).beats().size();
        kinetic = this.options.kinetic() && film != null ? new ReplayKineticOverlay(this) : null;
    }

    List<Beat> activeBeats() { return film != null ? film.beats() : script.beats(); }
    List<ReplayVideoScript.Feed> activeFeeds() { return film != null ? film.feeds() : script.feeds(); }
    List<ReplayVideoScript.SpanWindow> activeSpans() { return film != null ? film.spans() : script.spans(); }
    double totalSeconds() { return film != null ? film.total() : script.total(); }
    int matchDuration() { return film != null ? film.duration() : script.duration(); }
    int timelineEndTick() { return film != null ? film.trimEndTick() : script.actionEndTick(); }
    int timelineStartTick() { return film != null ? film.trimStartTick() : 0; }
    @Nullable UUID focusPlayer() { return film != null ? film.focusPlayer() : script.focusPlayer(); }
    private double sampleWipe(double t) { return film != null ? film.sample(t).baseSample().wipe() : script.sample(t).wipe(); }
    boolean roleRevealed(UUID actorId, double matchTick) {
        return film != null ? film.isRoleRevealedAt(actorId, matchTick) : options.isRoleRevealedAt(actorId, matchTick, 0);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("habitrain_core.replay_video." + key, args);
    }

    static Component ticketMode(@Nullable String modeId, Component fallback) {
        if (modeId != null && !modeId.isBlank() && GameModeIds.isMurder(modeId.trim().replace('/', ':'))) {
            return tr("ticket.mode.murder");
        }
        return fallback.getString().isBlank() ? Component.literal("-") : fallback;
    }

    // =================================================================== background (behind the model)

    void background(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s, ReplayVideoCamera.Shot shot) {
        background(c, t, s, null, shot);
    }

    void background(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s,
                    @Nullable ReplayCinemaDirector.CinemaSample cs, ReplayVideoCamera.Shot shot) {
        w = c.width; h = c.height;
        if (kinetic != null) {
            kinetic.background(c, t, s);
            return;
        }
        c.rect(0, 0, w, h, 0xFF04060F, 0xFF111936);
        float yaw = (float) Math.atan2(shot.look().x, shot.look().z);
        float pitch = (float) Math.asin(Math.clamp(shot.look().y, -1, 1));
        // Nebula: the slowest layer, wraps horizontally with the camera's heading.
        float nebW = w * 1.7f, nebH = nebW * 0.5f;
        float off = (float) (frac(-yaw / (Math.PI * 2) * 0.8) * nebW);
        float ny = h * 0.42f - pitch * h * 0.5f;
        for (int i = -1; i <= 1; i++) c.sprite(Tex.NEBULA, off + i * nebW, ny, nebW, nebH, 0, argb(0xFFFFFFFF, 0.62), false);
        // Stars in three depth layers.
        for (int i = 0; i < 170; i++) {
            float depth = i % 3 == 0 ? 1f : i % 3 == 1 ? 0.6f : 0.3f;
            float x = (float) frac(ReplayVideoFx.rnd(i, 11) - yaw / (Math.PI * 2) * depth * 1.1) * (w + 40) - 20;
            float y = (float) frac(ReplayVideoFx.rnd(i, 12) + pitch * 0.3 * depth) * h;
            double tw = 0.55 + 0.45 * Math.sin(t * (1.3 + ReplayVideoFx.rnd(i, 13) * 3) + i);
            float size = (1.5f + 3.5f * ReplayVideoFx.rnd(i, 14)) * depth;
            int col = i % 7 == 0 ? 0xFFFFD9A0 : i % 5 == 0 ? 0xFF9FC8FF : 0xFFFFFFFF;
            c.sprite(Tex.GLOW, x, y, size * 3, size * 3, 0, argb(col, 0.7 * tw * depth), true);
            if (size > 4 && i % 4 == 0) {
                c.sprite(Tex.STREAK, x, y, size * 9, size * 1.4f, 0, argb(col, 0.35 * tw), true);
                c.sprite(Tex.STREAK, x, y, size * 9, size * 1.4f, (float) (Math.PI / 2), argb(col, 0.25 * tw), true);
            }
        }
        // Stage back-light behind the tray.
        Vector3f centre = shot.toScreen(new Vector3f(0, ReplayBoardSite.MAP_BASE, 0), w, h);
        if (centre.z > 0) {
            c.sprite(Tex.GLOW, centre.x, centre.y, h * 2.0f, h * 1.5f, 0, argb(BRASS, 0.2), true);
            c.sprite(Tex.GLOW, centre.x + w * 0.25f, centre.y - h * 0.2f, h * 1.2f, h * 1.2f, 0, argb(PASSENGER, 0.07), true);
        }
        // God rays behind the winners / MVP.
        if (s.act() == Act.VICTORY || s.act() == Act.MVP) {
            Vector3f at = centre;
            double a = ReplayStagePlan.easeOutCubic(s.actTime() / 0.8);
            if (s.act() == Act.MVP && model.mvp != null) {
                Vector3f m = actorPoint(model.mvp, matchDuration());
                if (m != null) {
                    Vector3f p = shot.toScreen(m, w, h);
                    if (p.z > 0) at = p;
                }
            }
            if (at.z > 0) {
                int col = s.act() == Act.VICTORY ? model.winColor : BRASS_LIGHT;
                c.sprite(Tex.RAYS, at.x, at.y, h * 2.4f, h * 2.4f, (float) (t * 0.12), argb(col, 0.26 * a), true);
                c.sprite(Tex.RAYS, at.x, at.y, h * 1.6f, h * 1.6f, (float) (-t * 0.08), argb(CHAMPAGNE, 0.12 * a), true);
            }
        }
    }

    // =================================================================== foreground (over the model)

    void foreground(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s, ReplayVideoCamera.Shot shot, int frame,
                    ReplayActorSizing heads) {
        foreground(c, t, s, null, shot, frame, heads);
    }

    void foreground(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s,
                    @Nullable ReplayCinemaDirector.CinemaSample cs,
                    ReplayVideoCamera.Shot shot, int frame, @Nullable ReplayActorSizing heads) {
        w = c.width; h = c.height;
        if (kinetic != null) {
            kinetic.foreground(c, t, s, cs, shot, frame, heads);
            return;
        }
        float yaw = (float) Math.atan2(shot.look().x, shot.look().z);
        boolean dof = options.includeLetterboxDof();
        if (dof) {
            // Near bokeh moves opposite and faster than the stars: depth of field without blur.
            for (int i = 0; i < 11; i++) {
                float x = (float) frac(ReplayVideoFx.rnd(i, 31) + yaw / (Math.PI * 2) * 1.9 + t * 0.004 * (i % 2 == 0 ? 1 : -1)) * (w + 400) - 200;
                float y = h * (0.1f + 0.85f * ReplayVideoFx.rnd(i, 32)) + (float) Math.sin(t * 0.3 + i) * 14;
                float size = 70 + 170 * ReplayVideoFx.rnd(i, 33);
                int col = i % 3 == 0 ? PASSENGER : BRASS_LIGHT;
                c.sprite(Tex.BOKEH, x, y, size, size, (float) (i * 0.4), argb(col, 0.035 + 0.05 * ReplayVideoFx.rnd(i, 34)), true);
            }
        }
        spans(c, t, s, shot, heads);
        // Grade: cool shadows at the top, warm lift at the bottom, then vignette and grain.
        if (dof) {
            c.rect(0, 0, w, h * 0.45f, argb(0xFF030714, 0.35), argb(0xFF030714, 0));
            c.rect(0, h * 0.6f, w, h, argb(LAMP, 0), argb(LAMP, 0.05));
            c.sprite(Tex.VIGNETTE, w / 2, h / 2, w, h, 0, argb(0xFFFFFFFF, 0.82), false);
        }
        // Diagonal 4-degree Star-Night Express brass split-screen divider & weapon medallion (Mode 1 & Mode 4 Tier 4).
        if (cs != null && cs.splitScreen() && cs.splitLeftRatio() > 0.015 && cs.splitLeftRatio() < 0.985) {
            splitBeamOverlay(c, t, cs);
        }
        impacts(c, t, s, cs, shot);
        if (dof || (cs != null && cs.letterboxBoost() > 0.5)) {
            letterbox(c, t, s, cs);
        }
        if (compress) {
            if (quality.mp4GrainAlpha > 0) {
                c.tile(Tex.GRAIN, 0, 0, w, h, 1.0f, 0.13f, 0.37f, argb(0xFFFFFFFF, quality.mp4GrainAlpha));
            }
        } else {
            c.tile(Tex.GRAIN, 0, 0, w, h, 1.0f, ReplayVideoFx.rnd(frame, 1), ReplayVideoFx.rnd(frame, 2), argb(0xFFFFFFFF, 0.075));
        }

        // Render skill toasts BEFORE action() highlight titles/cards so banners like FIRST BLOOD stay on top.
        if (s.act() == Act.ACTION && options.showSkillToasts()) {
            boolean splitActive = cs != null && cs.splitScreen() && cs.splitLeftRatio() > 0.015 && cs.splitLeftRatio() < 0.985;
            boolean highlightActive = false;
            for (Beat b : activeBeats()) {
                double u = b.local(t);
                if (u >= -0.05 && u <= 2.65) { highlightActive = true; break; }
            }
            double toastTick = cs != null && cs.splitScreen() ? cs.rightPovTick() : s.replayTick();
            if (splitActive || highlightActive) {
                float bottomBar = currentLetterboxHeight(t, s, cs);
                float rightStart = splitActive ? Math.max(w * 0.54f, (float) (w * cs.splitLeftRatio()) + 34f) : w * 0.62f;
                float safeBottom = h - Math.max(bottomBar + (splitActive ? 54f : 22f), 96f);
                ReplaySkillFallbackHud.renderOnCanvas(c, model, toastTick,
                        rightStart, 0f, Math.max(240f, w - rightStart - 10f), safeBottom,
                        true, true, true, 2, 0.94f);
            } else {
                ReplaySkillFallbackHud.renderOnCanvas(c, model, toastTick, 0f, 0f, w, h, false, 1.0f);
            }
        }
        switch (s.act()) {
            case TITLE -> title(c, t, s);
            case ACTION -> action(c, t, s, cs, shot, heads);
            case REVIEW -> review(c, s);
            case VICTORY -> victory(c, t, s);
            case MVP -> mvp(c, t, s, shot, heads);
            case OUTRO -> outro(c, t, s);
        }
        if (options.includeWatermark() && (s.act() == Act.ACTION || s.act() == Act.REVIEW)) {
            c.art(Tex.BRAND, w - 40 - Tex.BRAND.w * 0.16f, h - 48, 0.32f, 0, argb(0xFFFFFFFF, 0.6), false);
        }
        if (cs != null && cs.cutProgress() >= 0.0) {
            segmentTransition(c, cs.activeCutStyle(), cs.cutProgress());
        }
        wipe(c, s);
        // Fade in from and out to black.
        double total = totalSeconds();
        double black = Math.max(1 - t / 0.5, ReplayStagePlan.smoothstep(total - 0.8, total - 0.05, t));
        if (black > 0) c.rect(0, 0, w, h, argb(0xFF000000, black));
    }

    /** Computes the top and bottom X coordinates of the 4-degree tilted split-screen beam in design pixels. */
    static float[] splitSeamX(double leftRatio, float width, float height) {
        float midX = (float) (width * Math.clamp(leftRatio, 0.0, 1.0));
        float dx = (float) (Math.tan(Math.toRadians(ReplayCinemaDirector.SPLIT_BEAM_TILT_DEGREES)) * height * 0.5);
        return new float[]{midX + dx, midX - dx};
    }

    private void splitBeamOverlay(ReplayVideoCanvas c, double t, ReplayCinemaDirector.CinemaSample cs) {
        float[] seam = splitSeamX(cs.splitLeftRatio(), w, h);
        float topX = seam[0], botX = seam[1];
        float midX = 0.5f * (topX + botX), midY = h * 0.5f;
        float angleRad = (float) Math.atan2(botX - topX, h);
        // Edge fade as beam slides out to 0% during Tier 4 POV full-screen expand
        double edgeFade = ReplayStagePlan.smoothstep(0.015, 0.10, cs.splitLeftRatio());
        // Star-Night Express brass split beam
        c.sprite(Tex.SPLIT_BEAM, midX, midY, 84f, h * 1.08f, -angleRad, argb(0xFFFFFFFF, 0.96 * edgeFade), false);
        // Octagonal Art Deco weapon emblem medallion at the center of the beam
        double pulse = 1.0 + 0.03 * Math.sin(t * 7.0);
        float medSize = (float) (112 * pulse);
        c.sprite(Tex.GLOW, midX, midY, 180, 180, 0, argb(BRASS_LIGHT, 0.42 * edgeFade), true);
        c.sprite(Tex.SPLIT_MEDALLION, midX, midY, medSize, medSize, 0, argb(0xFFFFFFFF, 0.98 * edgeFade), false);
        var hBeat = cs.activeHighlight();
        if (hBeat != null) {
            var mark = marks.get(hBeat.primaryNode());
            if (mark != null) c.item(mark.item(), midX, midY, 44);
            else c.icon(ReplayVideoCanvas.CROSSHAIR, midX, midY, 38, argb(RUBY, edgeFade));
        }
        // Subtle right-panel POV badge docked at bottom-right above the letterbox/timeline so it never
        // collides with centered FIRST BLOOD / MULTI KILL banners at top-center.
        if (cs.povSubjectUuid() != null && edgeFade > 0.2) {
            var actor = model.participant(cs.povSubjectUuid());
            Component tag = actor != null
                    ? Component.literal("POV · ").append(actor.name)
                    : Component.literal("KILLER POV");
            float bottomBar = currentLetterboxHeight(t, cs.baseSample(), cs);
            float bw = Math.min(240f, c.width(tag, 1.55f) + 30f);
            float bx = Math.max(botX + 26f, w - 28f - bw);
            float by = h - Math.max(bottomBar + 24f, 88f);
            c.slab(bx, by - 13, bx + bw, by + 13, 8, argb(NIGHT_1, 0.90 * edgeFade), argb(NIGHT_0, 0.94 * edgeFade));
            c.rect(bx, by - 13, bx + 4, by + 13, argb(RUBY, edgeFade));
            c.textFit(tag, bx + 14, by, 1.55f, bw - 22, argb(CHAMPAGNE, edgeFade), -1, true);
        }
    }

    private void segmentTransition(ReplayVideoCanvas c, ReplayCinemaDirector.CutStyle style, double p) {
        if (style == ReplayCinemaDirector.CutStyle.NONE) return;
        double peak = Math.sin(Math.clamp(p, 0.0, 1.0) * Math.PI);
        if (style == ReplayCinemaDirector.CutStyle.TICKET_FLIP) {
            // Star-Night Express ticket-flip & brass light sweep between Personal Highlights clips
            double e = ReplayStagePlan.easeInOutQuart(p);
            float sweepX = (float) (-w * 0.35 + w * 1.7 * e);
            c.rect(0, 0, w, h, argb(NIGHT_0, Math.min(1.0, peak * 1.12)));
            c.slab(sweepX - 180, 0, sweepX + 180, h, -h * 0.25f, argb(NIGHT_2, 0.96 * peak), argb(NIGHT_0, 0.96 * peak));
            c.slab(sweepX - 196, 0, sweepX - 180, h, -h * 0.25f, argb(BRASS_LIGHT, peak), argb(BRASS, peak));
            c.slab(sweepX + 180, 0, sweepX + 194, h, -h * 0.25f, argb(BRASS, peak), argb(BRASS_LIGHT, peak));
            c.sprite(Tex.STREAK, sweepX, h * 0.5f, 520, 140, (float) Math.toRadians(76), argb(BRASS_LIGHT, 0.75 * peak), true);
            c.art(Tex.T_HIGHLIGHTS, w * 0.5f, h * 0.5f, (float) (0.38 + 0.08 * peak), 0, argb(0xFFFFFFFF, peak), false);
        } else if (style == ReplayCinemaDirector.CutStyle.BRASS_SWEEP) {
            // Full-frame opaque Star-Night brass curtain covering [0, w] completely at p = 0.5 when tickAt() switches.
            double e = ReplayStagePlan.easeInOutQuart(p);
            float skew = h * 0.30f;
            float slabW = w * 1.45f;
            float center = (float) (-slabW * 0.55 - skew + (w + slabW * 1.10 + skew * 2.0) * e);
            float left = center - slabW * 0.5f;
            float right = center + slabW * 0.5f;
            c.slab(left - 24, 0, left, h, -skew, argb(BRASS_LIGHT, 1.0), argb(BRASS, 1.0));
            c.slab(left, 0, right, h, -skew, argb(NIGHT_2, 1.0), argb(NIGHT_0, 1.0));
            c.slab(right, 0, right + 18, h, -skew, argb(BRASS, 1.0), argb(BRASS_LIGHT, 1.0));
            c.sprite(Tex.STREAK, center, h * 0.5f, 520, 110, (float) Math.toRadians(72), argb(CHAMPAGNE, 0.70 * peak), true);
        } else if (style == ReplayCinemaDirector.CutStyle.CROSS_DISSOLVE) {
            c.rect(0, 0, w, h, argb(NIGHT_0, Math.min(0.96, 0.96 * peak)));
        }
    }

    /** Chromatic aberration strength for the encoder pass (0..1). */
    double aberration(double t) {
        double a = 0;
        for (Beat b : activeBeats()) {
            double u = b.local(t);
            if (u < 0 || u > 1) continue;
            double k = switch (b.type()) {
                case KILL -> 0.8 + 0.1 * b.streak();
                case EXPLOSION -> 1.0;
                case SHIELD -> 0.6;
                default -> 0.35;
            };
            a = Math.max(a, k * ReplayVideoScript.impulse(u, 6));
        }
        if (film != null) {
            var cs = film.sample(t);
            if (cs.segment() != null) {
                var kind = cs.segment().kind();
                if (kind == ReplayCinemaDirector.SegmentKind.TIER3_HERO_SIDE
                        || kind == ReplayCinemaDirector.SegmentKind.TIER4_IMPACT_FREEZE
                        || kind == ReplayCinemaDirector.SegmentKind.TIER5_APEX_FREEZE) {
                    a = Math.max(a, 0.85 * ReplayVideoScript.impulse(t - cs.segment().videoStart(), 4.5));
                }
            }
        }
        for (var span : activeSpans()) {
            if (span.span().kind() == LastMatchReplay.SpanKind.BLACKOUT) {
                double u = t - span.start();
                if (u >= 0 && u < 0.6) a = Math.max(a, 0.7 * (1 - u / 0.6));
            }
        }
        double wipe = sampleWipe(t);
        if (wipe >= 0) a = Math.max(a, 0.5 * (1 - Math.abs(wipe - 0.5) * 2));
        return Math.min(1, a);
    }

    private void spans(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s, ReplayVideoCamera.Shot shot, @Nullable ReplayActorSizing heads) {
        if (s.act() != Act.ACTION) return;
        for (var span : activeSpans()) {
            double in = ReplayStagePlan.smoothstep(span.start(), span.start() + 0.25, t) * (1 - ReplayStagePlan.smoothstep(span.end(), span.end() + 0.4, t));
            if (in <= 0) continue;
            if (span.span().kind() == LastMatchReplay.SpanKind.BLACKOUT) {
                double flicker = 0.85 + 0.15 * Math.sin(t * 47) * Math.sin(t * 13);
                c.rect(0, 0, w, h, argb(0xFF02040C, 0.42 * in * flicker));
                c.tile(Tex.SCANLINES, 0, 0, w, h, 1, 0, (float) (t * 0.6), argb(0xFFFFFFFF, 0.35 * in));
            } else {
                double pulse = 0.6 + 0.4 * Math.sin(t * 6);
                c.sprite(Tex.GLOW, 0, h / 2, h * 0.9f, h * 1.6f, 0, argb(PSYCHO, 0.16 * in * pulse), true);
                c.sprite(Tex.GLOW, w, h / 2, h * 0.9f, h * 1.6f, 0, argb(PSYCHO, 0.16 * in * pulse), true);
                ReplayBoardModel.Actor actor = model.participant(span.span().actor());
                if (actor != null && heads != null) {
                    Vector3f p = actorPoint(actor, s.replayTick());
                    if (p != null) {
                        Vector3f sp = shot.toScreen(p.add(0, headSize(heads, actor, p) * 1.6f, 0), w, h);
                        if (onScreen(sp)) callout(c, sp.x, sp.y, Component.literal("PSYCHO · ").append(actor.name), PSYCHO, in, t - span.start());
                    }
                }
            }
        }
    }

    private void impacts(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s,
                         @Nullable ReplayCinemaDirector.CinemaSample cs, ReplayVideoCamera.Shot shot) {
        double bestFlash = 0;
        int bestFlashColor = RUBY;
        for (Beat b : activeBeats()) {
            double u = b.local(t);
            if (u < 0 || u > 1.6) continue;
            int color = beatColor(b);
            double flash = ReplayVideoScript.impulse(u, 11) * switch (b.type()) {
                case EXPLOSION -> 0.48;
                case KILL -> 0.30;
                case REVIVAL -> 0.38;
                default -> 0.24;
            };
            if (flash > bestFlash) {
                bestFlash = flash;
                bestFlashColor = color;
            }
            if (b.type() == BeatType.KILL || b.type() == BeatType.EXPLOSION || b.type() == BeatType.REVIVAL) {
                Vector3f p = subjectPoint(b);
                Vector3f sp = p == null ? new Vector3f(w / 2, h / 2, 1) : shot.toScreen(p, w, h);
                if (sp.z <= 0) sp.set(w / 2, h / 2, 1);
                double lines = ReplayVideoScript.impulse(u, 2.6) * (b.type() == BeatType.REVIVAL ? 0.32 : 0.45);
                int lc = b.type() == BeatType.REVIVAL ? CHAMPAGNE : 0xFFFFFFFF;
                c.sprite(Tex.SPEEDLINES, sp.x, sp.y, w * 1.7f, w * 1.7f, (float) (u * 0.4), argb(lc, lines), b.type() == BeatType.REVIVAL);
            }
        }
        // Tier 5 Apotheosis Apex Freeze: radiant star-trail afterimages & colossal gilded stamp!
        if (cs != null && cs.segment() != null && cs.segment().kind() == ReplayCinemaDirector.SegmentKind.TIER5_APEX_FREEZE) {
            double p = cs.segment().progressAt(t);
            double pulse = 1.0 - 0.25 * p;
            c.sprite(Tex.RAYS, w * 0.5f, h * 0.5f, w * 1.35f, w * 1.35f, (float) (t * 0.6), argb(BRASS_LIGHT, 0.38 * pulse), true);
            c.sprite(Tex.SPEEDLINES, w * 0.5f, h * 0.5f, w * 1.6f, w * 1.6f, (float) (p * 0.25), argb(CHAMPAGNE, 0.50 * pulse), true);
            c.sprite(Tex.SKILL_RUNE, w * 0.5f, h * 0.5f, 440, 440, (float) (t * 0.8), argb(BRASS_LIGHT, 0.32 * pulse), true);
            Tex stamp = switch (cs.stampKey()) {
                case "MULTI_KILL" -> Tex.T_MULTI_KILL;
                case "FINAL_BLOW" -> Tex.T_FINAL_BLOW;
                case "MVP" -> Tex.T_MVP;
                default -> Tex.T_APOTHEOSIS;
            };
            double slam = back(p / 0.35);
            c.art(stamp, w * 0.5f, h * 0.30f, (float) (0.72 * (1.0 + 0.55 * (1.0 - slam))), (float) Math.toRadians(-2.5),
                    argb(0xFFFFFFFF, Math.min(1.0, p / 0.08)), false);
            c.art(Tex.T_APOTHEOSIS, w * 0.5f, h * 0.78f, 0.38f, 0, argb(BRASS_LIGHT, 0.88 * pulse), false);
        }
        if (bestFlash > 0.01) {
            c.rect(0, 0, w, h, argb(bestFlashColor, bestFlash * 0.42));
            c.sprite(Tex.GLOW, w / 2, h / 2, w * 1.5f, h * 1.5f, 0, argb(0xFFFFFFFF, bestFlash * 0.30), true);
        }
    }

    private float currentLetterboxHeight(double t, ReplayVideoScript.Sample s,
                                         @Nullable ReplayCinemaDirector.CinemaSample cs) {
        boolean dof = options.includeLetterboxDof();
        if (!dof && (cs == null || cs.letterboxBoost() <= 0.5)) return 0f;
        double lb = switch (s.act()) {
            case VICTORY -> 0.9;
            case MVP -> 0.55;
            case TITLE -> 0.0;
            default -> 0;
        };
        if (s.act() == Act.ACTION) {
            for (Beat b : activeBeats()) lb = Math.max(lb, ReplayVideoScript.influence(b, t, 0.6, 0.6));
            if (cs != null && cs.letterboxBoost() > 0) {
                lb = Math.max(lb, Math.min(1.0, cs.letterboxBoost()));
            }
        }
        if (lb <= 0.01) return 0f;
        boolean gildedDeep = cs != null && cs.letterboxBoost() > 1.05;
        float ratio = gildedDeep ? 0.165f : (s.act() == Act.ACTION ? 0.148f : 0.095f);
        return (float) (h * ratio * ReplayStagePlan.easeInOutQuart(lb));
    }

    private void letterbox(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s,
                           @Nullable ReplayCinemaDirector.CinemaSample cs) {
        double lb = switch (s.act()) {
            case VICTORY -> 0.9;
            case MVP -> 0.55;
            case TITLE -> 0.0;
            default -> 0;
        };
        if (s.act() == Act.ACTION) {
            for (Beat b : activeBeats()) lb = Math.max(lb, ReplayVideoScript.influence(b, t, 0.6, 0.6));
            if (cs != null && cs.letterboxBoost() > 0) {
                lb = Math.max(lb, Math.min(1.0, cs.letterboxBoost()));
            }
        }
        if (lb <= 0.01) return;
        boolean gildedDeep = cs != null && cs.letterboxBoost() > 1.05;
        float ratio = gildedDeep ? 0.165f : (s.act() == Act.ACTION ? 0.148f : 0.095f);
        float bar = (float) (h * ratio * ReplayStagePlan.easeInOutQuart(lb));
        c.rect(0, 0, w, bar, 0xFF000000);
        c.rect(0, h - bar, w, h, 0xFF000000);
        int rimCol = gildedDeep ? BRASS_LIGHT : BRASS;
        float rimH = gildedDeep ? 3.5f : 2.0f;
        c.rect(0, bar, w, bar + rimH, argb(rimCol, (gildedDeep ? 0.92 : 0.5) * lb), argb(rimCol, 0));
        c.rect(0, h - bar - rimH, w, h - bar, argb(rimCol, 0), argb(rimCol, (gildedDeep ? 0.92 : 0.5) * lb));
    }

    // =================================================================== act one: boarding ticket

    private void title(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s) {
        double k = s.actTime();
        // Brand lockup.
        double brand = ReplayStagePlan.easeOutCubic((k - 0.2) / 0.6) * (1 - ReplayStagePlan.smoothstep(3.0, 3.4, k));
        c.art(Tex.BRAND, 60 + Tex.BRAND.w * 0.21f - (float) (1 - brand) * 40, 62, 0.42f, 0, argb(0xFFFFFFFF, brand), false);
        // Ticket slides up with a slight tilt, gets stamped, then leaves.
        double enter = back((k - 0.45) / 0.7);
        double leave = ReplayStagePlan.easeInOutQuart((k - 3.05) / 0.5);
        if (enter > 0) {
            float tw = 560, scale = tw / 848f, th = Tex.TICKET.h * scale;
            float cx = 70 + tw / 2 - (float) leave * 700, cy = h * 0.58f + (float) (1 - enter) * 420;
            double stampK = (k - 1.75) / 0.22;
            float thump = stampK > 0 && stampK < 1.6 ? (float) (Math.sin(stampK * Math.PI * 3) * Math.exp(-stampK * 3) * 6) : 0;
            c.pose.pushMatrix();
            c.pose.translate(cx, cy + thump, 0).rotateZ((float) Math.toRadians(-3.5 + 2.5 * ReplayStagePlan.easeOutCubic((k - 0.6) / 1.2)));
            c.art(Tex.TICKET, 0, 0, scale, 0, 0xFFFFFFFF, false);
            // Ticket body origin (top-left of the 800x330 body inside the 24 px shadow pad).
            float ox = -tw / 2 + 24 * scale, oy = -th / 2 + 24 * scale;
            int ink = 0xFF1B1A24, inkSoft = 0xFF4A4038;
            c.textFit(ReplayVideoCanvas.font(Component.literal(model.replay.mapName()), ReplayVideoCanvas.BIG),
                    ox + 40 * scale, oy + 140 * scale, 4.6f * scale * 1.6f, 405 * scale, ink, -1, false);
            // (destination sits on the left; the stamp lands on the clean cream paper right of it)
            Component mode = ticketMode(model.replay.modeId(), model.modeLabel);
            c.textFit(mode, ox + 40 * scale, oy + 238 * scale, 2.0f * scale * 1.5f, 190 * scale, inkSoft, -1, false);
            c.textFit(ReplayVideoCanvas.font(Component.literal(date), ReplayVideoCanvas.UI), ox + 250 * scale, oy + 238 * scale, 2.2f * scale * 1.5f,
                    170 * scale, inkSoft, -1, false);
            c.text(ReplayVideoCanvas.font(Component.literal(ReplayStagePlan.clock(model.replay.durationTicks())), ReplayVideoCanvas.UI),
                    ox + 440 * scale, oy + 238 * scale, 2.2f * scale * 1.5f, inkSoft, -1, false);
            c.text(ReplayVideoCanvas.font(Component.literal(String.valueOf(model.actors.length)), ReplayVideoCanvas.BIG),
                    ox + 640 * scale, oy + 150 * scale, 6.5f * scale * 1.5f, 0xFF8A1E2A, -1, false);
            c.text(ReplayVideoCanvas.font(Component.literal(trainNo), ReplayVideoCanvas.UI), ox + 640 * scale, oy + 238 * scale,
                    2.2f * scale * 1.5f, inkSoft, -1, false);
            if (stampK > 0) {
                double st = ReplayStagePlan.easeOutCubic(stampK);
                float ss = (float) (0.4 * scale * (1.8 - 0.8 * st));
                float stampX = ox + 505 * scale, stampY = oy + 146 * scale;
                double sa = Math.min(1, stampK * 2);
                c.pose.pushMatrix();
                c.pose.translate(stampX, stampY, 0).rotateZ((float) Math.toRadians(-16));
                c.art(Tex.STAMP, 0, 0, ss, 0, argb(0xFFC0303C, 0.88 * sa), false);
                c.text(tr("ticket.stamp"), 0, 4 * scale, 2.1f * ss / 0.5f, argb(0xFFC0303C, 0.94 * sa), 0, false);
                c.pose.popMatrix();
            }
            c.pose.popMatrix();
        }
        // Tagline under the model.
        double tag = ReplayStagePlan.easeOutCubic((k - 1.2) / 0.6) * (1 - ReplayStagePlan.smoothstep(3.0, 3.4, k));
        if (tag > 0) {
            float y = h - 70;
            float len = (float) (220 * tag);
            c.line(w * 0.68f - len, y, w * 0.68f - 120, y, 2, argb(BRASS, 0), argb(BRASS, tag));
            c.line(w * 0.68f + 120, y, w * 0.68f + len, y, 2, argb(BRASS, tag), argb(BRASS, 0));
            c.diamond(w * 0.68f, y - 22, 5, argb(BRASS_LIGHT, tag));
            c.text(tr("title.tagline"), w * 0.68f, y, 2f, argb(CHAMPAGNE, tag), 0, true);
        }
    }

    // =================================================================== act two: the match

    private void action(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s,
                        @Nullable ReplayCinemaDirector.CinemaSample cs,
                        ReplayVideoCamera.Shot shot, @Nullable ReplayActorSizing heads) {
        double k = s.actTime();
        double hud = ReplayStagePlan.easeOutCubic((k - 0.2) / 0.5) * (1 - ReplayStagePlan.smoothstep(s.actLength() - 0.4, s.actLength(), k));
        double cinematic = 0;
        for (Beat b : activeBeats()) cinematic = Math.max(cinematic, ReplayVideoScript.influence(b, t, 0.6, 0.6));
        double hudA = hud * (1 - 0.12 * cinematic);
        if (options.showTimecode()) hudClock(c, t, s, cs, hudA, hud);
        if (options.showAliveMatrix()) alive(c, s, hudA);
        if (options.showTimeline()) timeline(c, s, hudA);
        if (options.showKillFeed()) feed(c, t, s, hud);
        if (options.includeTitle()) departure(c, k);
        boolean isTier5Freeze = cs != null && cs.segment() != null
                && cs.segment().kind() == ReplayCinemaDirector.SegmentKind.TIER5_APEX_FREEZE;
        for (Beat b : activeBeats()) {
            double u = b.local(t);
            if (u < -0.05 || u > 3.2) continue;
            if (heads != null && (cs == null || cs.camera() == ReplayCinemaDirector.CameraDirective.DIORAMA_OVERVIEW
                    || cs.camera() == ReplayCinemaDirector.CameraDirective.DIORAMA_CLOSEUP
                    || cs.camera() == ReplayCinemaDirector.CameraDirective.DIORAMA_FOLLOW
                    || cs.camera() == ReplayCinemaDirector.CameraDirective.SPLIT_DIORAMA_AND_POV)) {
                float splitRightBound = (cs != null && cs.splitScreen() && cs.splitLeftRatio() < 0.985)
                        ? (float) (w * cs.splitLeftRatio()) - 82f : w - 24f;
                callout(c, t, b, shot, u, heads, splitRightBound);
            }
            if (!isTier5Freeze) badge(c, b, u);
            card(c, b, u, s.replayTick());
        }
        // Tier 1 Glimmer subtle badge in top-right when active
        if (cs != null && cs.segment() != null && cs.segment().kind() == ReplayCinemaDirector.SegmentKind.TIER1_GLIMMER
                && cs.activeHighlight() != null) {
            tier1GlimmerToast(c, cs, t);
        }
    }

    private void tier1GlimmerToast(ReplayVideoCanvas c, ReplayCinemaDirector.CinemaSample cs, double t) {
        var h = cs.activeHighlight();
        double p = cs.segment().progressAt(t);
        double a = ReplayStagePlan.smoothstep(0.0, 0.15, p) * (1.0 - ReplayStagePlan.smoothstep(0.82, 1.0, p));
        if (a <= 0.02) return;
        Component line = feedLine(h.primaryNode());
        int col = feedColor(h.primaryNode());
        float tw = Math.min(380, c.width(line, 1.65f)) + 74;
        float x1 = w - 40, x0 = x1 - tw, y = 122;
        c.slab(x0, y - 16, x1, y + 16, -8, argb(NIGHT_2, 0.90 * a), argb(NIGHT_0, 0.94 * a));
        c.rect(x1 - 5, y - 16, x1, y + 16, argb(col, a));
        c.diamond(x0 + 18, y, 6f, argb(BRASS_LIGHT, a));
        c.textFit(line, x0 + 34, y, 1.65f, tw - 48, argb(CHAMPAGNE, a), -1, true);
    }

    private void hudClock(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s,
                          @Nullable ReplayCinemaDirector.CinemaSample cs, double a, double chipAlpha) {
        if (a <= 0.01) return;
        float x = 44, y = 26;
        boolean blink = (t % 1.0) < 0.6;
        String tagStr = cs != null && cs.instantReplay() ? "ENCORE" : "REPLAY";
        Component replayTag = ReplayVideoCanvas.font(Component.literal(tagStr), ReplayVideoCanvas.UI);
        float tagW = c.width(replayTag, 2f);
        var focusActor = model.participant(focusPlayer());
        Component povLabel = focusActor != null ? tr("hud.pov", focusActor.name) : null;
        float pillRight = x + 20 + tagW + 14;
        if (povLabel != null) {
            pillRight = x + 32 + tagW + 16 + Math.min(340f, c.width(povLabel, 1.8f)) + 16;
        }
        // Subtle dark Art Deco backing strip so top-left REPLAY/POV status stays legible over bright carriages.
        c.slab(x - 10, y - 13, pillRight, y + 13, 8, argb(NIGHT_0, 0.72 * a), argb(NIGHT_1, 0.56 * a));
        c.disc(x + 6, y, 6, argb(RUBY, (blink ? 1 : 0.35) * a), argb(RUBY, (blink ? 0.6 : 0.2) * a), 16);
        c.text(replayTag, x + 20, y, 2f, argb(CHAMPAGNE, 0.96 * a), -1, true);
        if (focusActor != null && povLabel != null) {
            float px = x + 32 + tagW;
            c.disc(px + 4, y, 4.5f, argb(FOCUS_TRAIL, 0.95 * a), argb(FOCUS_TRAIL_GLOW, 0.4 * a), 12);
            c.textFit(povLabel, px + 16, y, 1.8f, 340, argb(FOCUS_TRAIL_HEAD, 0.98 * a), -1, true);
        }
        boolean useRightPovTick = cs != null && cs.segment() != null
                && (cs.segment().kind() == ReplayCinemaDirector.SegmentKind.TIER4_POV_EXPAND
                || (cs.instantReplay() && cs.splitScreen() && cs.splitLeftRatio() < 0.5));
        double clockTick = useRightPovTick ? cs.rightPovTick() : s.replayTick();
        String clock = ReplayStagePlan.clock(clockTick);
        c.text(ReplayVideoCanvas.font(Component.literal(clock), ReplayVideoCanvas.BIG), x - 2, y + 40, 4.0f, argb(0xFFFFFFFF, a), -1, true);
        float cw = c.width(ReplayVideoCanvas.font(Component.literal(clock), ReplayVideoCanvas.BIG), 4.0f);
        Component chip;
        int chipColor;
        if (s.freeze()) { chip = tr("hud.freeze"); chipColor = RUBY; }
        else if (s.slow()) { chip = Component.empty().append(tr("hud.slow")).append(String.format(Locale.ROOT, " ×%.2f", s.speed())); chipColor = LAMP; }
        else { chip = Component.literal(String.format(Locale.ROOT, "×%.1f", s.speed())); chipColor = BRASS; }
        Component chipText = ReplayVideoCanvas.font(chip, ReplayVideoCanvas.UI);
        float tw = c.width(chipText, 1.8f) + 26;
        float cx = x + cw + 16, cy = y + 40;
        double ca = s.freeze() || s.slow() ? chipAlpha : a;
        c.slab(cx, cy - 13, cx + tw, cy + 13, 8, argb(chipColor, 0.96 * ca), argb(scale(chipColor, 0.72), 0.96 * ca));
        c.text(chipText, cx + tw / 2, cy, 1.8f, argb(s.freeze() ? 0xFFFFFFFF : NIGHT_0, ca), 0, false);
        if (!s.slow() && !s.freeze() && s.speed() > 3) {
            // Hyper-lapse chevrons.
            for (int i = 0; i < 3; i++) {
                double ph = frac(t * 2.2 - i * 0.18);
                float px = cx + tw + 18 + i * 12;
                c.line(px, cy - 7, px + 7, cy, 3, argb(BRASS_LIGHT, a * (0.3 + 0.7 * ph)), argb(BRASS_LIGHT, a * (0.3 + 0.7 * ph)));
                c.line(px + 7, cy, px, cy + 7, 3, argb(BRASS_LIGHT, a * (0.3 + 0.7 * ph)), argb(BRASS_LIGHT, a * (0.3 + 0.7 * ph)));
            }
        }
    }

    private void alive(ReplayVideoCanvas c, ReplayVideoScript.Sample s, double a) {
        if (a <= 0.01) return;
        int alive = 0, total = model.actors.length;
        boolean[] states = new boolean[total];
        for (int i = 0; i < total; i++) {
            states[i] = model.actors[i].track.sample(s.replayTick()).state() == ReplayTrackSampler.State.ALIVE;
            if (states[i]) alive++;
        }
        float x = w - 44, y = 22;
        c.text(tr("hud.alive"), x, y, 1.8f, argb(CHAMPAGNE, a), 1, true);
        c.text(ReplayVideoCanvas.font(Component.literal(alive + "/" + total), ReplayVideoCanvas.BIG), x, y + 32, 3.4f, argb(0xFFFFFFFF, a), 1, true);
        int shown = Math.min(total, 24);
        float px = x - shown * 13 + 3;
        for (int i = 0; i < shown; i++) {
            var actor = model.actors[i];
            float xx = px + i * 13, yy = y + 58;
            // Anti-spoiler suspense fog: hide unrevealed faction colors behind neutral brass-slate until revealed!
            int col = roleRevealed(actor.p.id(), s.replayTick()) ? actor.factionColor() : TEXT_MUTED;
            if (states[i]) c.rect(xx, yy, xx + 10, yy + 10, argb(col, a), argb(scale(col, 0.7), a));
            else {
                c.rect(xx, yy, xx + 10, yy + 10, argb(0xFF2A2F3E, 0.8 * a));
                c.line(xx + 1, yy + 1, xx + 9, yy + 9, 2, argb(RUBY, a), argb(RUBY, a));
                c.line(xx + 9, yy + 1, xx + 1, yy + 9, 2, argb(RUBY, a), argb(RUBY, a));
            }
        }
    }

    private void timeline(ReplayVideoCanvas c, ReplayVideoScript.Sample s, double a) {
        if (a <= 0.01) return;
        float x0 = 230, x1 = w - 360, y = h - 46;
        int startT = timelineStartTick();
        int endT = Math.max(startT + 1, timelineEndTick());
        int span = endT - startT;
        c.rect(x0, y - 3, x1, y + 3, argb(0xFF070B18, 0.75 * a));
        for (var spanWin : activeSpans()) {
            if (spanWin.span().kind() != LastMatchReplay.SpanKind.BLACKOUT) continue;
            float sx = x0 + (x1 - x0) * (float) Math.clamp((spanWin.span().startTick() - startT) / (double) span, 0, 1);
            float ex = x0 + (x1 - x0) * (float) Math.clamp((spanWin.span().endTick() - startT) / (double) span, 0, 1);
            c.rect(sx, y - 3, ex, y + 3, argb(0xFF4C6BB0, 0.6 * a));
        }
        float px = x0 + (float) ((x1 - x0) * Math.clamp((s.replayTick() - startT) / span, 0, 1));
        c.rectH(x0, y - 2, px, y + 2, argb(BRASS_DIM, a), argb(BRASS_LIGHT, a));
        for (Beat b : activeBeats()) {
            float bx = x0 + (x1 - x0) * (float) Math.clamp((b.tick() - startT) / (double) span, 0, 1);
            boolean past = b.tick() <= s.replayTick();
            c.diamond(bx, y, past ? 7 : 5, argb(beatColor(b), (past ? 1 : 0.55) * a));
        }
        c.sprite(Tex.GLOW, px, y, 46, 46, 0, argb(BRASS_LIGHT, 0.9 * a), true);
        c.disc(px, y, 5, argb(0xFFFFFFFF, a), argb(CHAMPAGNE, a), 16);
        c.textFit(Component.literal(model.replay.mapName()), x0 - 18, y, 1.6f, 170, argb(CHAMPAGNE, 0.9 * a), 1, true);
        c.text(ReplayVideoCanvas.font(Component.literal(ReplayStagePlan.clock(endT)), ReplayVideoCanvas.UI), x1 + 18, y, 1.8f,
                argb(TEXT_MUTED, a), -1, false);
    }

    private void departure(ReplayVideoCanvas c, double k) {
        if (k > 1.25) return;
        double in = back(k / 0.22), out = 1 - ReplayStagePlan.smoothstep(0.85, 1.22, k);
        double a = Math.min(1, k / 0.1) * out;
        if (a <= 0.01) return;
        float y = h * 0.2f;
        float len = (float) (w * 0.32 * ReplayStagePlan.easeOutCubic(k / 0.45));
        c.line(w / 2 - 190 - len, y, w / 2 - 190, y, 3, argb(BRASS, 0), argb(BRASS_LIGHT, a));
        c.line(w / 2 + 190, y, w / 2 + 190 + len, y, 3, argb(BRASS_LIGHT, a), argb(BRASS, 0));
        c.art(Tex.T_DEPARTURE, w / 2, y, (float) (0.5 * (1 + 0.6 * (1 - in))), 0, argb(0xFFFFFFFF, a), false);
        c.text(Component.empty().append(tr("depart")).append("  ·  ").append(model.replay.mapName()), w / 2, y + 62, 2f,
                argb(CHAMPAGNE, a), 0, true);
    }

    private void feed(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s, double hud) {
        if (hud <= 0.01) return;
        List<ReplayVideoScript.Feed> live = new ArrayList<>();
        for (var f : activeFeeds()) {
            if (f.time() > t) break;
            if (t - f.time() < 3.2) live.add(f);
        }
        int from = Math.max(0, live.size() - MAX_FEED);
        float y = 150;
        for (int i = live.size() - 1; i >= from; i--) {
            var f = live.get(i);
            double age = t - f.time();
            double in = ReplayStagePlan.easeOutCubic(age / 0.3), out = 1 - ReplayStagePlan.smoothstep(2.6, 3.2, age);
            double a = in * out * hud;
            Component line = feedLine(f.node());
            int color = feedColor(f.node());
            float tw = Math.min(380, c.width(line, 1.6f)) + 60;
            float x1 = w - 40 + (float) (1 - in) * 120, x0 = x1 - tw;
            c.slab(x0, y - 15, x1, y + 15, -6, argb(NIGHT_1, 0.0), argb(NIGHT_1, 0.88 * a));
            c.rect(x1 - 4, y - 15, x1, y + 15, argb(color, a));
            c.icon(feedIcon(f.node()), x0 + 26, y, 26, argb(color, a));
            c.textFit(line, x0 + 46, y, 1.6f, tw - 60, argb(TEXT, a), -1, true);
            y += 36;
        }
    }

    // ------------------------------------------------------------------ highlight graphics

    private void badge(ReplayVideoCanvas c, Beat b, double u) {
        Tex title = badgeTex(b);
        if (title == null || u < 0) return;
        double slam = back(u / 0.24), out = ReplayStagePlan.easeInOutQuart((u - 2.15) / 0.45);
        double a = Math.min(1, u / 0.06) * (1 - out);
        if (a <= 0.01) return;
        float y = h * 0.24f - (float) out * 40;
        int color = beatColor(b);
        float scale = (float) (0.56 * (1 + 0.9 * (1 - slam)));
        // Brush banner wipes in behind the title.
        c.reveal(Tex.BRUSH, w / 2 - 440, y + 4, 880, 150, (float) ReplayStagePlan.easeOutCubic(u / 0.3),
                argb(scale(color, 0.42), 0.85 * a), false);
        c.art(title, w / 2, y, scale, (float) Math.toRadians(-2), argb(0xFFFFFFFF, a), false);
        // Light sweep across the lettering.
        double sweep = (u - 0.25) / 0.6;
        if (sweep > 0 && sweep < 1) {
            c.sprite(Tex.STREAK, w / 2 - 380 + (float) sweep * 760, y, 360, 110, (float) Math.toRadians(70),
                    argb(0xFFFFFFFF, 0.55 * a * Math.sin(sweep * Math.PI)), true);
        }
        Component sub = badgeSubtitle(b);
        if (sub != null) {
            double sa = a * ReplayStagePlan.easeOutCubic((u - 0.2) / 0.3);
            if (sa > 0.01) {
                float subY = y + 78;
                float sw = Math.min(w * 0.65f, c.width(sub, 2f) + 46f);
                c.slab(w / 2 - sw / 2, subY - 15, w / 2 + sw / 2, subY + 15, 10,
                        argb(NIGHT_0, 0.88 * sa), argb(NIGHT_1, 0.88 * sa));
                c.line(w / 2 - sw / 2 + 10, subY - 15, w / 2 + sw / 2 + 10, subY - 15, 1.5f,
                        argb(BRASS_LIGHT, 0.65 * sa), argb(BRASS, 0.65 * sa));
                c.text(sub, w / 2, subY, 2f, argb(CHAMPAGNE, sa), 0, true);
            }
        }
    }

    private void card(ReplayVideoCanvas c, Beat b, double u, double matchTick) {
        if (u < 0 || b.type() == BeatType.HIT) return;
        double in = back((u - 0.12) / 0.38), out = ReplayStagePlan.easeInOutQuart((u - 2.6) / 0.45);
        double a = Math.min(1, (u - 0.1) / 0.1) * (1 - out);
        if (a <= 0.01) return;
        float cw = 528, ch = 88;
        float x = 44 - (float) (1 - in) * 600 - (float) out * 600, y = h - 216;
        int accent = beatColor(b);
        var node = b.node();
        c.slab(x, y, x + cw, y + ch, 14, argb(NIGHT_2, 0.95 * a), argb(NIGHT_0, 0.95 * a));
        c.slab(x - 6, y, x + 6, y + ch, 14, argb(accent, a), argb(accent, a));
        c.line(x + 14, y + 1, x + cw + 14, y + 1, 2, argb(BRASS, 0.85 * a), argb(BRASS, 0.15 * a));
        float cy = y + ch / 2;
        int subText = lerp(TEXT_MUTED, TEXT, 0.55);
        if (b.type() == BeatType.KILL) {
            ReplayBoardModel.Actor killer = model.participant(node.actor()), victim = model.participant(node.target());
            float fx = x + 26;
            if (killer != null) {
                boolean kr = roleRevealed(killer.p.id(), matchTick);
                c.face(killer.p.id(), fx, cy - 22, 44, argb(0xFFFFFFFF, a));
                c.textFit(killer.name, fx + 54, cy - 11, 2f, 145, argb(kr ? readableRoleColor(killer.color) : TEXT, a), -1, true);
                c.textFit(kr ? killer.role : Component.literal("???"), fx + 54, cy + 14, 1.45f, 145, argb(subText, a), -1, true);
            } else {
                c.icon(ReplayVideoCanvas.SKULL, fx + 22, cy, 40, argb(subText, a));
                c.textFit(ReplayBoardTexts.tr("environment"), fx + 54, cy, 2f, 145, argb(subText, a), -1, true);
            }
            float wx = x + cw / 2 + 20, wy = cy - 6;
            c.sprite(Tex.GLOW, wx, wy, 88, 88, 0, argb(accent, 0.5 * a), true);
            c.disc(wx, wy, 23, argb(0xFFFFFFFF, 0.18 * a), argb(0xFFFFFFFF, 0.02 * a), 24);
            var mark = marks.get(node);
            if (mark != null) c.item(mark.item(), wx, wy, 38);
            else c.icon(ReplayVideoCanvas.CROSSHAIR, wx, wy, 34, argb(accent, a));
            c.textFit(ReplayBoardTexts.weapon(node), wx, cy + 28, 1.35f, 128, argb(CHAMPAGNE, 0.92 * a), 0, true);
            if (victim != null) {
                boolean vr = roleRevealed(victim.p.id(), matchTick);
                float vx = x + cw - 70;
                c.face(victim.p.id(), vx, cy - 22, 44, argb(0xFFB06060, a));
                c.line(vx + 4, cy - 18, vx + 40, cy + 18, 4, argb(RUBY, a), argb(RUBY, a));
                c.line(vx + 40, cy - 18, vx + 4, cy + 18, 4, argb(RUBY, a), argb(RUBY, a));
                c.textFit(victim.name, vx - 10, cy - 11, 2f, 125, argb(lerp(TEXT, TEXT_MUTED, 0.22), a), 1, true);
                c.textFit(vr ? victim.role : Component.literal("???"), vx - 10, cy + 14, 1.45f, 125, argb(vr ? readableRoleColor(victim.color) : subText, a), 1, true);
            }
        } else {
            ReplayBoardModel.Actor subject = model.participant(b.type() == BeatType.SHIELD && node.target() != null ? node.target()
                    : node.actor() != null ? node.actor() : node.target());
            int icon = switch (b.type()) {
                case REVIVAL -> ReplayVideoCanvas.REVIVE;
                case EXPLOSION -> ReplayVideoCanvas.BOMB;
                case DEFUSE, SHIELD -> ReplayVideoCanvas.SHIELD;
                default -> ReplayVideoCanvas.CROSSHAIR;
            };
            float fx = x + 26;
            if (subject != null) c.face(subject.p.id(), fx, cy - 22, 44, argb(0xFFFFFFFF, a));
            else c.icon(icon, fx + 22, cy, 40, argb(accent, a));
            Component name = subject == null ? ReplayBoardTexts.tr("unknown_player") : subject.name;
            Component line = switch (b.type()) {
                case REVIVAL -> tr("card.revived", name);
                case EXPLOSION -> ReplayBoardTexts.tr("brief.explosion", name);
                case DEFUSE -> tr("card.defused", name);
                case SHIELD -> tr("card.shield", name);
                default -> name;
            };
            c.textFit(line, fx + 56, cy - 10, 2f, cw - 150, argb(TEXT, a), -1, true);
            if (subject != null) {
                boolean sr = roleRevealed(subject.p.id(), matchTick);
                c.textFit(sr ? subject.role : Component.literal("???"), fx + 56, cy + 16, 1.45f, cw - 150,
                        argb(sr ? readableRoleColor(subject.color) : subText, a), -1, true);
            }
            c.sprite(Tex.GLOW, x + cw - 50, cy, 80, 80, 0, argb(accent, 0.5 * a), true);
            c.icon(icon, x + cw - 50, cy, 38, argb(accent, a));
        }
    }

    private void callout(ReplayVideoCanvas c, double t, Beat b, ReplayVideoCamera.Shot shot, double u,
                         ReplayActorSizing heads, float maxRightX) {
        if (u > 2.4) return;
        Vector3f p = subjectPoint(b);
        if (p == null) return;
        Vector3f sp = shot.toScreen(p.add(0, headSize(heads, subjectActor(b), p) * 0.9f, 0), w, h);
        if (!onScreen(sp) || sp.x > maxRightX + 20f) return;
        int color = beatColor(b);
        double a = (u < 0 ? 0.6 : 1) * (1 - ReplayStagePlan.smoothstep(1.9, 2.4, u));
        // Reticle brackets close in on the subject.
        double lock = ReplayStagePlan.easeOutCubic((u + 0.4) / 0.5);
        float r = (float) (70 - 34 * lock);
        float spin = (float) (u < 0 ? u * 1.5 : 0);
        for (int i = 0; i < 4; i++) {
            double ang = spin + i * Math.PI / 2 + Math.PI / 4;
            float cx = sp.x + (float) Math.cos(ang) * r, cy = sp.y + (float) Math.sin(ang) * r;
            float dx = (float) -Math.cos(ang) * 12, dy = (float) -Math.sin(ang) * 12;
            c.line(cx, cy, cx + dx, cy, 3, argb(color, a), argb(color, a));
            c.line(cx, cy, cx, cy + dy, 3, argb(color, a), argb(color, a));
        }
        if (u < 0) return;
        ReplayBoardModel.Actor subject = subjectActor(b);
        Component label = switch (b.type()) {
            case KILL -> tr("callout.eliminated", subject == null ? ReplayBoardTexts.tr("unknown_player") : subject.name);
            case REVIVAL -> tr("callout.revived", subject == null ? ReplayBoardTexts.tr("unknown_player") : subject.name);
            case EXPLOSION -> tr("callout.detonation");
            case DEFUSE -> tr("callout.defused");
            case SHIELD -> tr("callout.shield");
            case HIT -> tr("callout.hit", subject == null ? ReplayBoardTexts.tr("unknown_player") : subject.name);
        };
        callout(c, sp.x, sp.y, label, color, a, u, maxRightX);
    }

    private void callout(ReplayVideoCanvas c, float x, float y, Component label, int color, double a, double age) {
        callout(c, x, y, label, color, a, age, w - 24f);
    }

    /** Leader line from a 3D-anchored point to a label plate, constrained left of {@code maxRightX}. */
    private void callout(ReplayVideoCanvas c, float x, float y, Component label, int color, double a, double age,
                         float maxRightX) {
        double grow = ReplayStagePlan.easeOutCubic(age / 0.3);
        float tw = c.width(label, 1.8f) + 24;
        boolean right = x < Math.min(w * 0.62f, maxRightX - tw - 68f);
        if (!right && x - 60f - tw < 24f) {
            right = (x + 60f + tw) <= maxRightX;
        }
        float dir = right ? 1 : -1;
        float ex = Math.clamp(x + dir * 60f, right ? 24f : 24f + tw, right ? Math.max(24f, maxRightX - tw) : maxRightX);
        float ey = Math.max(150f, y - 60f);
        float jx = x + (ex - x) * (float) grow, jy = y + (ey - y) * (float) grow;
        c.disc(x, y, 5, argb(0xFFFFFFFF, a), argb(color, a), 12);
        c.line(x, y, jx, jy, 2.5f, argb(color, a), argb(color, a));
        if (grow < 0.95) return;
        float lx0 = right ? ex : ex - tw, lx1 = right ? ex + tw : ex;
        double reveal = ReplayStagePlan.easeOutCubic((age - 0.25) / 0.25);
        float rx1 = right ? lx0 + (lx1 - lx0) * (float) reveal : lx1, rx0 = right ? lx0 : lx1 - (lx1 - lx0) * (float) reveal;
        c.line(ex, ey, right ? lx1 : lx0, ey, 2.5f, argb(color, a), argb(color, a * 0.3));
        c.rect(rx0, ey - 30, rx1, ey - 3, argb(NIGHT_0, 0.86 * a));
        c.rect(right ? rx0 : rx1 - 4, ey - 30, right ? rx0 + 4 : rx1, ey - 3, argb(color, a));
        if (reveal > 0.9) c.text(label, right ? lx0 + 12 : lx1 - 12, ey - 16, 1.8f, argb(0xFFFFFFFF, a), right ? -1 : 1, true);
    }

    // =================================================================== act three: overview

    private void review(ReplayVideoCanvas c, ReplayVideoScript.Sample s) {
        double k = s.actTime();
        double a = ReplayStagePlan.easeOutCubic(k / 0.5) * (1 - ReplayStagePlan.smoothstep(s.actLength() - 0.4, s.actLength(), k));
        float y = 92;
        c.art(Tex.T_OVERVIEW, w / 2, y, (float) (0.4 + 0.08 * (1 - ReplayStagePlan.easeOutCubic(k / 0.4))), 0, argb(0xFFFFFFFF, a), false);
        c.text(tr("review.subtitle"), w / 2, y + 48, 2f, argb(CHAMPAGNE, a), 0, true);
        String[] labels = {"stat.duration", "stat.kills", "stat.players", "stat.highlights"};
        int kills = model.replay.killCount();
        double count = ReplayStagePlan.easeOutCubic((k - 0.4) / 1.4);
        String[] values = {
                ReplayStagePlan.clock(model.replay.durationTicks() * count),
                String.valueOf((int) Math.round(kills * count)),
                String.valueOf((int) Math.round(model.actors.length * count)),
                String.valueOf((int) Math.round(objectiveHighlights * count))};
        int[] colors = {BRASS_LIGHT, RUBY, PASSENGER, LAMP};
        float bw = 184, gap = 14, total = bw * 4 + gap * 3, x = (w - total) / 2f;
        float by = options.includeWatermark() ? h - 162 : h - 144;
        for (int i = 0; i < 4; i++) {
            double ia = back((k - 0.3 - i * 0.12) / 0.45);
            double ba = Math.min(1, Math.max(0, (k - 0.3 - i * 0.12) / 0.15)) * a;
            if (ba <= 0.01) continue;
            float bx = x + i * (bw + gap), oy = (float) (1 - ia) * 40;
            c.slab(bx, by + oy, bx + bw, by + 84 + oy, 10, argb(NIGHT_2, 0.9 * ba), argb(NIGHT_0, 0.9 * ba));
            c.rect(bx, by + oy, bx + bw + 10, by + 3 + oy, argb(colors[i], ba));
            c.text(tr(labels[i]), bx + bw / 2 + 5, by + 20 + oy, 1.6f, argb(lerp(TEXT_MUTED, TEXT, 0.45), ba), 0, true);
            c.text(ReplayVideoCanvas.font(Component.literal(values[i]), ReplayVideoCanvas.BIG), bx + bw / 2 + 5, by + 56 + oy, 4f,
                    argb(colors[i], ba), 0, true);
        }
    }

    // =================================================================== act four: victory

    private void victory(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s) {
        double k = s.actTime();
        double out = ReplayStagePlan.smoothstep(s.actLength() - 0.45, s.actLength(), k);
        double a = Math.min(1, k / 0.15) * (1 - out);
        int win = model.winColor;
        confetti(c, k, win, 1 - out);
        float y = h * 0.43f;
        // Deep feathered night-blue backdrop band behind victory lettering so bright train roofs never wash out text.
        double bandReveal = ReplayStagePlan.easeOutCubic((k - 0.08) / 0.40);
        float bandHalfW = (float) (560.0 * bandReveal);
        if (bandHalfW > 4f) {
            c.rect(w / 2 - bandHalfW, y - 62, w / 2 + bandHalfW, y + 66, argb(NIGHT_0, 0.78 * a), argb(NIGHT_1, 0.82 * a));
            c.rect(w / 2 - bandHalfW, y - 62, w / 2 + bandHalfW, y - 60, argb(BRASS_LIGHT, 0.65 * a));
            c.rect(w / 2 - bandHalfW, y + 64, w / 2 + bandHalfW, y + 66, argb(win, 0.75 * a));
        }
        c.reveal(Tex.BRUSH, w / 2 - 560, y, 1120, 190, (float) ReplayStagePlan.easeOutCubic((k - 0.1) / 0.45), argb(scale(win, 0.34), 0.72 * a), false);
        c.reveal(Tex.BRUSH, w / 2 - 520, y + 6, 1040, 120, (float) ReplayStagePlan.easeOutCubic((k - 0.2) / 0.45), argb(win, 0.18 * a), true);
        double slam = back((k - 0.25) / 0.3);
        if (k > 0.25) c.art(Tex.T_VICTORY, w / 2, h * 0.22f, (float) (0.62 * (1 + 0.8 * (1 - slam))), (float) Math.toRadians(-2),
                argb(0xFFFFFFFF, a * Math.min(1, (k - 0.25) / 0.08)), false);
        double ta = ReplayStagePlan.easeOutCubic((k - 0.45) / 0.4) * a;
        c.textFit(model.title, w / 2, y - 10, 4f, w - 220, argb(lerp(readableRoleColor(win), 0xFFFFFFFF, 0.45), ta), 0, true);
        Component sub = Component.empty().append(model.subtitle);
        if (!model.modeLabel.getString().isBlank() && !model.modeLabel.getString().equals(model.subtitle.getString())) {
            sub = Component.empty().append(model.subtitle).append("  ·  ").append(model.modeLabel);
        }
        c.textFit(sub, w / 2, y + 36, 2f, w - 300, argb(CHAMPAGNE, ta), 0, true);
        // Winners line up.
        List<ReplayBoardModel.Actor> winners = model.winners;
        if (winners.isEmpty()) {
            c.text(ReplayBoardTexts.tr("no_winner"), w / 2, h * 0.7f, 2f, argb(TEXT_MUTED, ta), 0, true);
            return;
        }
        int shown = Math.min(8, winners.size());
        float spacing = Math.min(130, (w - 200) / shown), x0 = w / 2 - spacing * (shown - 1) / 2;
        c.text(tr("victory.winners"), w / 2, h * 0.6f, 1.6f, argb(BRASS_LIGHT, ta), 0, false);
        for (int i = 0; i < shown; i++) {
            var actor = winners.get(i);
            double pop = back((k - 0.8 - i * 0.1) / 0.35);
            double pa = Math.min(1, Math.max(0, (k - 0.8 - i * 0.1) / 0.12)) * (1 - out);
            if (pa <= 0.01) continue;
            float cx = x0 + i * spacing, cy = h * 0.69f + (float) (1 - pop) * 30;
            float size = 52;
            c.sprite(Tex.GLOW, cx, cy, 120, 120, 0, argb(actor == model.mvp ? BRASS_LIGHT : actor.color, 0.45 * pa), true);
            c.rect(cx - size / 2 - 3, cy - size / 2 - 3, cx + size / 2 + 3, cy + size / 2 + 3, argb(actor == model.mvp ? BRASS_LIGHT : actor.color, pa));
            c.face(actor.p.id(), cx - size / 2, cy - size / 2, size, argb(0xFFFFFFFF, pa));
            if (actor == model.mvp) c.art(Tex.CROWN, cx, cy - size / 2 - 18, 0.24f, (float) Math.sin(t * 2.4) * 0.08f, argb(0xFFFFFFFF, pa), false);
            c.textFit(actor.name, cx, cy + size / 2 + 16, 1.6f, spacing - 10, argb(CHAMPAGNE, pa), 0, true);
            c.textFit(actor.role, cx, cy + size / 2 + 34, 1.3f, spacing - 10, argb(readableRoleColor(actor.color), pa), 0, true);
        }
    }

    private void confetti(ReplayVideoCanvas c, double k, int win, double a) {
        int[] palette = {BRASS_LIGHT, RUBY, PASSENGER, CHAMPAGNE, win, LAMP};
        for (int i = 0; i < 150; i++) {
            double delay = ReplayVideoFx.rnd(i, 51) * 0.9;
            double tt = k - 0.2 - delay;
            if (tt <= 0) continue;
            float speed = 140 + 200 * ReplayVideoFx.rnd(i, 52);
            float x = w * ReplayVideoFx.rnd(i, 53) + (float) Math.sin(tt * (1.5 + ReplayVideoFx.rnd(i, 54) * 2) + i) * 40;
            float y = -30 + (float) tt * speed;
            if (y > h + 30) continue;
            float cw = 6 + 7 * ReplayVideoFx.rnd(i, 55), ch = 3 + 4 * ReplayVideoFx.rnd(i, 56);
            double flip = Math.abs(Math.cos(tt * (4 + 6 * ReplayVideoFx.rnd(i, 57))));
            c.pose.pushMatrix();
            c.pose.translate(x, y, 0).rotateZ((float) (tt * (2 + 4 * ReplayVideoFx.rnd(i, 58)) + i));
            int col = palette[i % palette.length];
            c.rect(-cw / 2, (float) (-ch / 2 * flip), cw / 2, (float) (ch / 2 * flip), argb(col, a), argb(scale(col, 0.7), a));
            c.pose.popMatrix();
        }
    }

    // =================================================================== act five: MVP

    private void mvp(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s, ReplayVideoCamera.Shot shot, ReplayActorSizing heads) {
        ReplayBoardModel.Actor mvp = model.mvp;
        if (mvp == null) return;
        double k = s.actTime();
        double out = ReplayStagePlan.smoothstep(s.actLength() - 0.45, s.actLength(), k);
        double a = Math.min(1, k / 0.2) * (1 - out);
        // Seamless full-width gradient scrim (0 -> w) so no hard vertical boundary appears behind the 3D MVP.
        c.rectH(0, 0, w * 0.34f, h, argb(NIGHT_0, 0), argb(NIGHT_0, 0.12 * a));
        c.rectH(w * 0.34f, 0, w * 0.54f, h, argb(NIGHT_0, 0.12 * a), argb(NIGHT_0, 0.62 * a));
        c.rectH(w * 0.54f, 0, w, h, argb(NIGHT_0, 0.62 * a), argb(NIGHT_0, 0.88 * a));
        float cardX0 = w * 0.46f, cardX1 = w - 34, cardY0 = 96, cardY1 = h - 96;
        double panelOpen = ReplayStagePlan.easeOutCubic(k / 0.38);
        float panelLeft = cardX1 - (cardX1 - cardX0) * (float) panelOpen;
        c.rect(panelLeft, cardY0, cardX1, cardY1, argb(NIGHT_1, 0.86 * a), argb(NIGHT_0, 0.92 * a));
        c.rect(panelLeft, cardY0, cardX1, cardY0 + 2, argb(BRASS, 0.9 * a));
        c.rect(panelLeft, cardY1 - 2, cardX1, cardY1, argb(BRASS, 0.9 * a));
        corners(c, panelLeft, cardY0, cardX1, cardY1, argb(BRASS_LIGHT, 0.9 * a));

        float px = w * 0.59f, py = h * 0.46f;
        // Portrait medallion.
        double pop = back((k - 0.15) / 0.45);
        float ps = (float) (0.92 + 0.22 * (1 - pop));
        c.sprite(Tex.RAYS, px, py, 400 * ps, 400 * ps, (float) (t * 0.25), argb(BRASS_LIGHT, 0.42 * a), true);
        c.sprite(Tex.GLOW, px, py, 340 * ps, 340 * ps, 0, argb(BRASS, 0.46 * a), true);
        c.art(Tex.LAUREL, px, py + 22 * ps, 0.82f * ps, 0, argb(0xFFFFFFFF, a), false);
        float face = 100 * ps;
        c.rect(px - face / 2 - 4, py - face / 2 - 4, px + face / 2 + 4, py + face / 2 + 4, argb(NIGHT_0, a));
        c.face(mvp.p.id(), px - face / 2, py - face / 2, face, argb(0xFFFFFFFF, a));
        c.art(Tex.HEX_FRAME, px, py, 0.77f * ps, (float) Math.toRadians(0), argb(0xFFFFFFFF, a), false);
        c.art(Tex.CROWN, px, py - 122 * ps + (float) Math.sin(t * 2.2) * 4, 0.52f * ps, (float) Math.sin(t * 1.7) * 0.05f, argb(0xFFFFFFFF, a), false);
        for (int i = 0; i < 12; i++) {
            double ang = t * 0.6 + i * Math.PI / 6;
            float rr = 144 + 14 * (float) Math.sin(t * 2 + i);
            double tw = 0.5 + 0.5 * Math.sin(t * 4 + i * 1.3);
            c.sprite(Tex.GLOW, px + (float) Math.cos(ang) * rr, py + (float) Math.sin(ang) * rr * 0.8f, 18, 18, 0, argb(CHAMPAGNE, tw * a), true);
        }
        // Title and stats column.
        float tx = w * 0.59f + 160;
        float colRight = cardX1 - 24;
        double ta = back((k - 0.35) / 0.35);
        c.art(Tex.T_MVP, (tx + colRight) * 0.5f, h * 0.24f, (float) (0.58 * (1 + 0.6 * (1 - ta))), (float) Math.toRadians(-3),
                argb(0xFFFFFFFF, a * Math.min(1, Math.max(0, (k - 0.35) / 0.1))), false);
        double na = ReplayStagePlan.easeOutCubic((k - 0.6) / 0.4) * a;
        c.text(tr("mvp.subtitle"), tx, h * 0.34f, 1.6f, argb(BRASS_LIGHT, na), -1, true);
        c.textFit(mvp.name, tx, h * 0.40f, 3f, colRight - tx, argb(CHAMPAGNE, na), -1, true);
        c.textFit(mvp.role, tx, h * 0.46f, 1.8f, colRight - tx, argb(readableRoleColor(mvp.color), na), -1, true);
        c.rect(tx, h * 0.495f, colRight, h * 0.495f + 1.5f, argb(BRASS_DIM, 0.75 * na));
        var p = mvp.p;
        String[] labels = {"mvp.score", "mvp.kills", "mvp.survival", "mvp.items"};
        double[] ratios = {p.score() / (double) maxScore, p.kills() / (double) maxKills, p.survivalSeconds() / (double) maxSurvival,
                p.itemUses() / (double) maxItems};
        int[] colors = {BRASS_LIGHT, RUBY, PASSENGER, LAMP};
        int labelCol = lerp(TEXT_MUTED, TEXT, 0.55);
        for (int i = 0; i < 4; i++) {
            double ra = ReplayStagePlan.easeOutCubic((k - 0.9 - i * 0.12) / 0.4) * a;
            if (ra <= 0.01) continue;
            double count = ReplayStagePlan.easeOutCubic((k - 0.9 - i * 0.12) / 1.0);
            String value = switch (i) {
                case 0 -> String.valueOf((int) Math.round(p.score() * count));
                case 1 -> String.valueOf((int) Math.round(p.kills() * count));
                case 2 -> ReplayStagePlan.clock(p.survivalSeconds() * 20.0 * count);
                default -> String.valueOf((int) Math.round(p.itemUses() * count));
            };
            float ry = h * 0.545f + i * 48;
            c.text(tr(labels[i]), tx, ry, 1.65f, argb(labelCol, ra), -1, true);
            c.text(ReplayVideoCanvas.font(Component.literal(value), ReplayVideoCanvas.BIG), colRight, ry, 3f, argb(colors[i], ra), 1, true);
            float bx0 = tx, bx1 = colRight - 82;
            c.rect(bx0, ry + 15, bx1, ry + 20, argb(0xFF1A2440, 0.95 * ra));
            c.rectH(bx0, ry + 15, bx0 + (bx1 - bx0) * (float) (Math.clamp(ratios[i], 0.04, 1) * count), ry + 20, argb(scale(colors[i], 0.65), ra), argb(colors[i], ra));
        }
    }

    // =================================================================== act six: passenger list

    private void outro(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s) {
        double k = s.actTime();
        double a = ReplayStagePlan.easeOutCubic(k / 0.4);
        float x0 = w * 0.42f, x1 = w - 44, y0 = 64, y1 = h - 96;
        double open = ReplayStagePlan.easeOutCubic(k / 0.45);
        float px1 = x0 + (x1 - x0) * (float) open;
        c.rect(x0, y0, px1, y1, argb(NIGHT_1, 0.9 * a), argb(NIGHT_0, 0.92 * a));
        c.rect(x0, y0, px1, y0 + 2, argb(BRASS, a));
        c.rect(x0, y1 - 2, px1, y1, argb(BRASS, a));
        corners(c, x0, y0, px1, y1, argb(BRASS_LIGHT, a));
        if (open < 0.98) return;
        c.art(Tex.T_FINAL_STATION, x0 + 30 + Tex.T_FINAL_STATION.w * 0.17f, y0 + 40, 0.34f, 0, argb(0xFFFFFFFF, a), false);
        c.text(tr("outro.title"), x1 - 28, y0 + 42, 2f, argb(CHAMPAGNE, a), 1, true);
        float[] cols = {x0 + 34, x0 + 64, x0 + 280, x1 - 210, x1 - 130, x1 - 36};
        float hy = y0 + 86;
        String[] heads = {"outro.col.rank", "outro.col.player", "outro.col.role", "outro.col.kills", "outro.col.score", "outro.col.survival"};
        int[] align = {0, -1, -1, 0, 0, 1};
        for (int i = 0; i < heads.length; i++) c.text(tr(heads[i]), cols[i], hy, 1.4f, argb(TEXT_MUTED, a), align[i], false);
        c.rect(x0 + 20, hy + 12, x1 - 20, hy + 13, argb(BRASS_DIM, 0.8 * a));

        int pageSize = ReplayVideoScript.OUTRO_PAGE_SIZE;
        int totalPages = Math.max(1, (ranking.size() + pageSize - 1) / pageSize);
        int rowsPerLayout = Math.min(ranking.size(), pageSize);
        float rh = Math.min(42, (y1 - hy - 74) / Math.max(1, rowsPerLayout));

        int page = 0;
        double pageAlpha = 1.0;
        float pageSlideX = 0f;
        boolean initialEntry = true;
        if (totalPages > 1) {
            // Let page 1 settle and hold so viewers can read it comfortably before flipping pages.
            double contentStart = 0.45;
            double usable = Math.max(2.0, s.actLength() - 0.85 - contentStart);
            double slot = usable / totalPages;
            double elapsed = Math.max(0, k - contentStart);
            int rawPage = Math.min(totalPages - 1, (int) (elapsed / slot));
            double inSlot = elapsed - rawPage * slot;
            double flipDur = Math.min(0.40, slot * 0.16);
            double halfFlip = flipDur * 0.5;
            page = rawPage;
            initialEntry = rawPage == 0;
            if (rawPage > 0 && inSlot < halfFlip) {
                double pIn = ReplayStagePlan.easeOutCubic(inSlot / halfFlip);
                pageAlpha = pIn;
                pageSlideX = (float) ((1 - pIn) * 44);
            } else if (rawPage < totalPages - 1 && inSlot > slot - halfFlip) {
                double pOut = ReplayStagePlan.easeInOutQuart((inSlot - (slot - halfFlip)) / halfFlip);
                pageAlpha = 1 - pOut;
                pageSlideX = (float) (-pOut * 44);
            }
        }

        int startIdx = page * pageSize;
        int endIdx = Math.min(ranking.size(), startIdx + pageSize);
        for (int slotIdx = 0; slotIdx < endIdx - startIdx; slotIdx++) {
            int rankIdx = startIdx + slotIdx;
            var actor = ranking.get(rankIdx);
            double rowEnter = initialEntry ? ReplayStagePlan.easeOutCubic((k - 0.5 - slotIdx * 0.06) / 0.28) : 1.0;
            double ra = rowEnter * pageAlpha * a;
            if (ra <= 0.01) continue;
            float y = hy + 22 + rh * (slotIdx + 0.5f);
            float off = (float) ((1 - rowEnter) * 56) + pageSlideX;
            boolean isFocus = focusPlayer() != null && focusPlayer().equals(actor.p.id());
            if (isFocus) {
                c.rectH(x0 + 20 + off, y - rh / 2 + 2, x1 - 20, y + rh / 2 - 2, argb(FOCUS_TRAIL, 0.22 * ra), argb(FOCUS_TRAIL, 0.03 * ra));
                c.rect(x0 + 20 + off, y - rh / 2 + 2, x0 + 24 + off, y + rh / 2 - 2, argb(FOCUS_TRAIL, ra));
            } else if (actor.p.winner()) {
                c.rectH(x0 + 20 + off, y - rh / 2 + 2, x1 - 20, y + rh / 2 - 2, argb(BRASS, 0.18 * ra), argb(BRASS, 0.02 * ra));
                c.rect(x0 + 20 + off, y - rh / 2 + 2, x0 + 23 + off, y + rh / 2 - 2, argb(BRASS_LIGHT, ra));
            }
            c.text(ReplayVideoCanvas.font(Component.literal(String.valueOf(rankIdx + 1)), ReplayVideoCanvas.BIG), cols[0] + off, y, 2.4f,
                    argb(rankIdx == 0 ? BRASS_LIGHT : TEXT_MUTED, ra), 0, false);
            float fs = Math.min(28, rh - 8);
            c.face(actor.p.id(), cols[1] + off, y - fs / 2, fs, argb(actor.p.winner() ? 0xFFFFFFFF : 0xFFAAAAAA, ra));
            c.textFit(actor.name, cols[1] + fs + 10 + off, y, 1.6f, cols[2] - cols[1] - fs - 24, argb(actor.p.winner() ? CHAMPAGNE : TEXT, ra), -1, true);
            if (actor == model.mvp) c.art(Tex.CROWN, cols[1] + fs / 2 + off, y - fs / 2 - 6, 0.12f, 0, argb(0xFFFFFFFF, ra), false);
            c.textFit(actor.role, cols[2] + off, y, 1.4f, cols[3] - cols[2] - 40, argb(readableRoleColor(actor.color), ra), -1, true);
            c.text(ReplayVideoCanvas.font(Component.literal(String.valueOf(actor.p.kills())), ReplayVideoCanvas.BIG), cols[3] + off, y, 2.4f, argb(RUBY, ra), 0, false);
            c.text(ReplayVideoCanvas.font(Component.literal(String.valueOf(actor.p.score())), ReplayVideoCanvas.BIG), cols[4] + off, y, 2.4f, argb(BRASS_LIGHT, ra), 0, false);
            c.text(ReplayVideoCanvas.font(Component.literal(ReplayStagePlan.clock(actor.p.survivalSeconds() * 20.0)), ReplayVideoCanvas.UI),
                    cols[5] + off, y, 1.6f, argb(TEXT, ra), 1, false);
        }
        if (totalPages > 1) {
            float midX = (x0 + x1) / 2, indY = y1 - 50;
            float dotGap = 16;
            float dotsStart = midX - (totalPages - 1) * dotGap * 0.5f;
            for (int p = 0; p < totalPages; p++) {
                boolean active = p == page;
                float dx = dotsStart + p * dotGap;
                c.diamond(dx, indY, active ? 5.5f : 3.5f, argb(active ? BRASS_LIGHT : TEXT_MUTED, (active ? 1.0 : 0.55) * a));
            }
            c.text(ReplayVideoCanvas.font(Component.literal((page + 1) + " / " + totalPages), ReplayVideoCanvas.UI),
                    dotsStart + (totalPages - 1) * dotGap + 24, indY, 1.45f, argb(CHAMPAGNE, 0.85 * a), -1, false);
        }
        Component kills;
        if (model.firstKill == null) kills = ReplayBoardTexts.tr("no_kill");
        else if (model.lastKill == null || model.lastKill == model.firstKill) kills = ReplayBoardTexts.killLine("first_kill", model.firstKill, model);
        else kills = Component.empty().append(ReplayBoardTexts.killLine("first_kill", model.firstKill, model)).append("   ")
                    .append(ReplayBoardTexts.killLine("last_kill", model.lastKill, model));
        double fa = ReplayStagePlan.easeOutCubic((k - 1.2) / 0.5) * a;
        c.textFit(kills, (x0 + x1) / 2, y1 - 24, 1.35f, x1 - x0 - 40, argb(lerp(TEXT_MUTED, TEXT, 0.35), fa), 0, false);
        // Brand sign-off bottom left.
        double ba = ReplayStagePlan.easeOutCubic((k - 0.8) / 0.6);
        c.art(Tex.BRAND, 60 + Tex.BRAND.w * 0.2f, h - 70, 0.4f, 0, argb(0xFFFFFFFF, ba), false);
        c.text(tr("outro.footer", date), 60, h - 30, 1.3f, argb(TEXT_FAINT, ba), -1, false);
    }

    private static void corners(ReplayVideoCanvas c, float x0, float y0, float x1, float y1, int color) {
        float[][] pts = {{x0, y0, 1, 1}, {x1, y0, -1, 1}, {x0, y1, 1, -1}, {x1, y1, -1, -1}};
        for (float[] p : pts) {
            for (int s = 1; s <= 3; s++) {
                float o = s * 8;
                c.line(p[0] + p[2] * o, p[1], p[0] + p[2] * o, p[1] + p[3] * o, 2, color, color);
                c.line(p[0] + p[2] * o, p[1] + p[3] * o, p[0], p[1] + p[3] * o, 2, color, color);
            }
        }
    }

    // =================================================================== transitions

    private void wipe(ReplayVideoCanvas c, ReplayVideoScript.Sample s) {
        double p = s.wipe();
        if (p < 0) return;
        double e = ReplayStagePlan.easeInOutQuart(p);
        float skew = h * 0.35f;
        float left = (float) (-w * 1.6 - skew + (w * 3.2 + skew * 2) * e), right = left + w * 1.45f;
        c.slab(left - 46, 0, left - 30, h, -skew, argb(RUBY, 1), argb(RUBY, 1));
        c.slab(left - 26, 0, left, h, -skew, argb(BRASS_LIGHT, 1), argb(BRASS, 1));
        c.slab(left, 0, right, h, -skew, argb(NIGHT_2, 1), argb(NIGHT_0, 1));
        c.slab(right, 0, right + 18, h, -skew, argb(BRASS, 1), argb(BRASS_LIGHT, 1));
        double logo = 1 - Math.min(1, Math.abs(p - 0.5) * 5);
        if (logo > 0) c.art(Tex.BRAND, w / 2, h / 2, 0.55f, 0, argb(0xFFFFFFFF, logo), false);
    }

    // =================================================================== helpers

    Component feedLine(LastMatchReplay.Node node) {
        Component actor = ReplayBoardTexts.name(model.participantData(node.actor()));
        Component target = ReplayBoardTexts.name(model.participantData(node.target()));
        return switch (node.kind()) {
            case KILL -> node.actor() == null ? ReplayBoardTexts.tr("brief.death", target, ReplayBoardTexts.weapon(node))
                    : ReplayBoardTexts.tr("brief.kill", actor, ReplayBoardTexts.weapon(node), target);
            case GUNSHOT -> (node.flags() & LastMatchReplay.HIT) != 0
                    ? ReplayBoardTexts.tr("brief.shot", actor, ReplayBoardTexts.weapon(node), target) : tr("feed.gunshot", actor);
            case CHANGE_ROLE -> tr("feed.change_role", actor, ReplayBoardTexts.role(node.detail()));
            case POISON -> node.target() != null && node.actor() != null ? tr("feed.poison", actor, target) : tr("feed.poisoned", node.actor() == null ? target : actor);
            default -> tr("feed." + node.kind().name().toLowerCase(Locale.ROOT), node.actor() == null ? target : actor);
        };
    }

    int feedColor(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case KILL, TRAP_TRIGGERED, BOMB_PLANT -> RUBY;
            case GUNSHOT, GRENADE_THROWN, GRENADE_EXPLOSION, BOMB_DETONATE -> EXPLOSION;
            case POISON -> POISON;
            case SHIELD_BROKEN, BOMB_DEFUSE, DOOR_SEAL, DOOR_LOCK -> SHIELD;
            case DISGUISE -> DISGUISE;
            case PLAYER_REVIVAL -> BRASS_LIGHT;
            case PSYCHO_STATE_CHANGE -> PSYCHO;
            case BLACKOUT_START -> 0xFF8FB0FF;
            case CHANGE_ROLE, SKILL_RELEASE -> {
                var actor = model.participant(node.actor());
                yield actor == null ? LAMP : actor.color;
            }
            default -> LAMP;
        };
    }

    private static int feedIcon(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case KILL -> ReplayVideoCanvas.SKULL;
            case GUNSHOT -> ReplayVideoCanvas.CROSSHAIR;
            case POISON -> ReplayVideoCanvas.POISON;
            case GRENADE_THROWN, GRENADE_EXPLOSION -> ReplayVideoCanvas.GRENADE;
            case BOMB_PLANT, BOMB_DEFUSE, BOMB_DETONATE -> ReplayVideoCanvas.BOMB;
            case SHIELD_BROKEN -> ReplayVideoCanvas.SHIELD;
            case LOCKPICK_ATTEMPT, DOOR_PRY, DOOR_SEAL, DOOR_LOCK, DOOR_UNLOCK, DOOR_OPEN, DOOR_CLOSE -> ReplayVideoCanvas.DOOR;
            case ITEM_USED -> ReplayVideoCanvas.POTION;
            case SKILL_RELEASE -> ReplayVideoCanvas.BOLT;
            case DISGUISE -> ReplayVideoCanvas.MASK;
            case PLAYER_REVIVAL -> ReplayVideoCanvas.REVIVE;
            case CHANGE_ROLE -> ReplayVideoCanvas.SWAP;
            case BLACKOUT_START, BLACKOUT_END -> ReplayVideoCanvas.BULB;
            case PSYCHO_STATE_CHANGE -> ReplayVideoCanvas.FLAME;
            case TRAP_TRIGGERED -> ReplayVideoCanvas.TRAP;
            case ROPE_PULL -> ReplayVideoCanvas.ROPE;
        };
    }

    private static @Nullable Tex badgeTex(Beat b) {
        return switch (b.type()) {
            case KILL -> b.streak() >= 4 ? Tex.T_MULTI_KILL : b.streak() == 3 ? Tex.T_TRIPLE_KILL : b.streak() == 2 ? Tex.T_DOUBLE_KILL
                    : b.firstBlood() ? Tex.T_FIRST_BLOOD : b.finalBlow() ? Tex.T_FINAL_BLOW : Tex.T_ELIMINATED;
            case REVIVAL -> Tex.T_REVIVED;
            case EXPLOSION -> Tex.T_DETONATION;
            case DEFUSE -> Tex.T_DEFUSED;
            case SHIELD -> Tex.T_SHIELD;
            case HIT -> null;
        };
    }

    @Nullable Component badgeSubtitle(Beat b) {
        if (b.type() != BeatType.KILL) return null;
        Component killer = b.node().actor() == null ? ReplayBoardTexts.tr("environment") : ReplayBoardTexts.name(model.participantData(b.node().actor()));
        Component tag = b.streak() >= 2 ? tr("tag.streak", b.streak()) : b.firstBlood() ? tr("tag.first_blood")
                : b.finalBlow() ? tr("tag.final_blow") : tr("tag.kill");
        Component line = Component.empty().append(tag).append("  ·  ").append(killer);
        if (b.streak() >= 2 && b.finalBlow()) line = Component.empty().append(line).append("  ·  ").append(tr("tag.final_blow"));
        return line;
    }

    static int beatColor(Beat b) {
        return switch (b.type()) {
            case KILL -> b.finalBlow() && b.streak() < 2 ? LAMP : RUBY;
            case REVIVAL -> BRASS_LIGHT;
            case EXPLOSION -> EXPLOSION;
            case DEFUSE, SHIELD -> SHIELD;
            case HIT -> LAMP;
        };
    }

    @Nullable ReplayBoardModel.Actor subjectActor(Beat b) {
        var node = b.node();
        return switch (b.type()) {
            case KILL, SHIELD, HIT -> model.participant(node.target() != null ? node.target() : node.actor());
            default -> model.participant(node.actor() != null ? node.actor() : node.target());
        };
    }

    @Nullable Vector3f subjectPoint(Beat b) {
        var mark = marks.get(b.node());
        if (mark != null) return new Vector3f(mark.tx, ReplayBoardSite.MAP_BASE + mark.ty, mark.tz);
        var actor = subjectActor(b);
        return actor == null ? null : actorPoint(actor, b.tick());
    }

    @Nullable Vector3f actorPoint(ReplayBoardModel.Actor actor, double tick) {
        var pose = actor.track.sample(tick);
        if (!pose.visible()) return null;
        float[] p = new float[3];
        model.project(pose.x(), pose.y(), pose.z(), p);
        return new Vector3f(p[0], ReplayBoardSite.MAP_BASE + p[1], p[2]);
    }

    static float headSize(ReplayActorSizing heads, @Nullable ReplayBoardModel.Actor actor, Vector3f point) {
        return heads.size(actor == null ? null : actor.p.id(), point.x, point.y - ReplayBoardSite.MAP_BASE, point.z);
    }

    boolean onScreen(Vector3f p) {
        return p.z > 0 && p.x > -40 && p.x < w + 40 && p.y > -40 && p.y < h + 40;
    }

    private static double back(double x) {
        x = ReplayStagePlan.clamp01(x);
        double c1 = 1.70158, c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    private static double frac(double v) { return v - Math.floor(v); }

    private static int argb(int rgb, double a) { return ReplayPalette.alpha(rgb, a); }
}
