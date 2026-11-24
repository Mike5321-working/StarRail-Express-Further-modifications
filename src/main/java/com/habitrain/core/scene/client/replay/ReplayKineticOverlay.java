package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplayCinemaDirector;
import com.habitrain.core.scene.replay.ReplayCinemaDirector.SegmentKind;
import com.habitrain.core.scene.replay.ReplayExportContentOptions;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.habitrain.core.scene.replay.ReplayVideoScript.Act;
import com.habitrain.core.scene.replay.ReplayVideoScript.Beat;
import com.habitrain.core.scene.replay.ReplayVideoScript.BeatType;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.habitrain.core.scene.client.replay.ReplayKineticKit.*;

/**
 * Kinetic-typography (MV) packaging of the exported film. Reads the directed film and the board model through the
 * owning {@link ReplayVideoOverlay} and hands plain data to the per-act scenes; one {@link ReplayKineticTheme} per
 * export mode. Sub-options (POV / follow, 1x/2x/4x/highlights, global / personal MVP) adapt the chosen theme.
 */
final class ReplayKineticOverlay {
    private static final String[] ACT_LABEL = {"DEPARTURE", "ACTION", "OVERVIEW", "VICTORY", "MVP", "FINAL STATION"};
    private final ReplayVideoOverlay o;
    private final ReplayBoardModel model;
    private final ReplayExportContentOptions options;
    final ReplayKineticTheme theme;
    private final int accent;
    private final @Nullable ReplayBoardModel.Actor subject;

    ReplayKineticOverlay(ReplayVideoOverlay owner) {
        this.o = owner;
        this.model = owner.model;
        this.options = owner.options;
        this.theme = ReplayKineticTheme.of(options.mode());
        this.subject = model.participant(owner.focusPlayer());
        boolean personal = theme == ReplayKineticTheme.DOSSIER
                || (theme == ReplayKineticTheme.MUSIC_VIDEO && options.promoScope() == ReplayExportContentOptions.PromoScope.PERSONAL_MVP);
        this.accent = personal && subject != null ? duality(subject.factionColor(), theme.red, theme.blue) : theme.red;
    }

    // ------------------------------------------------------------------ passes

    void background(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s) {
        var p = new ReplayKineticCanvasPen(c, 0);
        switch (s.act()) {
            case REVIEW -> ReplayKineticBackdrops.paper(p, theme, t, "03");
            case VICTORY -> ReplayKineticBackdrops.paper(p, theme, t, "04");
            case MVP -> ReplayKineticBackdrops.paper(p, theme, t, "05");
            default -> ReplayKineticBackdrops.action(p, theme, t);
        }
    }

    void foreground(ReplayVideoCanvas c, double t, ReplayVideoScript.Sample s,
                    @Nullable ReplayCinemaDirector.CinemaSample cs, ReplayVideoCamera.Shot shot, int frame,
                    @Nullable ReplayActorSizing heads) {
        var p = new ReplayKineticCanvasPen(c, frame);
        float w = p.width(), h = p.height();
        double k = s.actTime();
        boolean onPaper = switch (s.act()) {
            case TITLE -> ReplayKineticTitles.covers(k) && (theme != ReplayKineticTheme.MUSIC_VIDEO || ((int) (k / 0.5)) % 2 == 0 || k >= 1.0 && k < 2.0);
            case REVIEW, MVP -> true;
            case VICTORY -> !ReplayKineticFinale.victoryCovers(k) || ((int) (k / 0.4)) % 2 == 1;
            default -> false;
        };
        if (s.act() == Act.ACTION) {
            spans(p, t, s);
            if (cs != null && cs.splitScreen() && cs.splitLeftRatio() > 0.015 && cs.splitLeftRatio() < 0.985) {
                float[] seam = ReplayVideoOverlay.splitSeamX(cs.splitLeftRatio(), w, h);
                double fade = ReplayStagePlan.smoothstep(0.015, 0.10, cs.splitLeftRatio());
                var pov = model.participant(cs.povSubjectUuid());
                ReplayKineticAction.splitSeam(p, theme, seam[0], seam[1], pov == null ? null : pov.name, fade);
            }
            impacts(p, t, cs);
            p.vignette(0.45);
        }
        if (o.compress) {
            if (o.quality.mp4GrainAlpha > 0) p.grain(o.quality.mp4GrainAlpha, 0);
        } else p.grain(0.06, frame);
        if (s.act() == Act.ACTION && options.showSkillToasts()) {
            ReplaySkillFallbackHud.renderOnCanvas(c, model, cs != null && cs.splitScreen() ? cs.rightPovTick() : s.replayTick(),
                    w * 0.62f, 0f, w * 0.38f - 10f, h - 120f, true, true, true, 2, 0.94f);
        }
        switch (s.act()) {
            case TITLE -> ReplayKineticTitles.title(p, theme, k, titleData());
            case ACTION -> action(p, t, s, cs, shot, heads);
            case REVIEW -> ReplayKineticFinale.review(p, theme, k, s.actLength(), stats(k), o.film == null ? null
                    : Component.translatable("habitrain_core.replay_video.review.subtitle").getString());
            case VICTORY -> ReplayKineticFinale.victory(p, theme, k, s.actLength(), victoryData(), t);
            case MVP -> {
                if (model.mvp != null) ReplayKineticFinale.mvp(p, theme, k, s.actLength(), mvpData(model.mvp), t);
            }
            case OUTRO -> ReplayKineticFinale.outro(p, theme, k, s.actLength(), outroData(k, s.actLength()), t);
        }
        boolean signOff = s.act() == Act.OUTRO && k > s.actLength() - 1.5;
        if (!signOff) {
            double chromeA = s.act() == Act.TITLE && !ReplayKineticTitles.covers(k) ? 0.6 : 0.85;
            ReplayKineticBackdrops.chrome(p, theme, chrome(t, s), onPaper, chromeA);
        }
        if (cs != null && cs.cutProgress() >= 0.0) cut(p, t, cs);
        if (s.wipe() >= 0) {
            int next = Math.min(Act.values().length - 1, s.act().ordinal() + (s.wipe() < 0.5 ? 1 : 0));
            ReplayKineticBackdrops.polarityWipe(p, theme, s.wipe(), String.format(Locale.ROOT, "%02d", next + 1), ACT_LABEL[next]);
        }
        double total = o.totalSeconds();
        double black = Math.max(1 - t / 0.5, ReplayStagePlan.smoothstep(total - 0.8, total - 0.05, t));
        if (black > 0) rect(p, 0, 0, w, h, a(0xFF000000, black));
    }

    private ReplayKineticData.Chrome chrome(double t, ReplayVideoScript.Sample s) {
        int act = s.act().ordinal();
        String clock = ReplayStagePlan.clock(s.act() == Act.ACTION ? s.replayTick() : model.replay.durationTicks());
        String label = ACT_LABEL[act];
        if (s.act() == Act.ACTION && subject != null && theme == ReplayKineticTheme.DOSSIER) label = label + "  /  " + subject.name.getString();
        return new ReplayKineticData.Chrome(theme.code + String.format(Locale.ROOT, ".%02d", act + 1), label,
                clock + "  //  120 BPM", model.replay.mapName(), t / Math.max(1e-6, o.totalSeconds()), accent);
    }

    /** Clip-to-clip cuts inside the action act, styled per theme. */
    private void cut(ReplayKineticPen p, double t, ReplayCinemaDirector.CinemaSample cs) {
        double pr = cs.cutProgress();
        switch (cs.activeCutStyle()) {
            case NONE -> {}
            case TICKET_FLIP -> {
                int[] chapter = chapterAt(t);
                var hl = upcomingHighlight(t);
                String word = hl == null ? "HIGHLIGHT" : wordFor(hl.primaryNode(), 1, false, false);
                String sub = hl == null ? "" : o.feedLine(hl.primaryNode()).getString();
                ReplayKineticBackdrops.chapterCard(p, theme, pr, chapter[0], chapter[1], word, sub);
            }
            case BRASS_SWEEP -> {
                int[] chapter = chapterAt(t);
                ReplayKineticBackdrops.polarityWipe(p, theme, pr, String.format(Locale.ROOT, "%02d", chapter[0] + 1), "HIGHLIGHT");
            }
            case CROSS_DISSOLVE -> {
                double peak = Math.sin(clamp01(pr) * Math.PI);
                float w = p.width(), h = p.height();
                rect(p, 0, 0, w, h, a(theme.actionBackdrop(), 0.94 * peak));
                crosshair(p, w / 2, h / 2, a(theme.blue, peak), pr * 2);
                ellipse(p, w / 2, h / 2, 46, 46, 0, 1.5f, a(0xFFFFFFFF, peak), 0, 1);
                int[] chapter = chapterAt(t);
                micro(p, "SECTION", String.format(Locale.ROOT, "%02d", chapter[0] + 1), w / 2, h / 2 + 70, 10,
                        a(theme.red, peak), a(0xFFFFFFFF, peak), 0);
            }
        }
    }

    /** {index, total} of the cut chapter that is active (or next) at {@code t}. */
    private int[] chapterAt(double t) {
        if (o.film == null) return new int[]{0, 1};
        int total = 0, index = 0;
        for (var seg : o.film.segments()) {
            if (seg.cutStyle() == ReplayCinemaDirector.CutStyle.NONE) continue;
            if (seg.videoStart() <= t) index = total;
            total++;
        }
        return new int[]{index, Math.max(1, total)};
    }

    private @Nullable com.habitrain.core.scene.replay.ReplayHighlightScorer.ScoredHighlight upcomingHighlight(double t) {
        if (o.film == null) return null;
        for (var seg : o.film.segments()) {
            if (seg.videoEnd() >= t && seg.highlight() != null) return seg.highlight();
        }
        return null;
    }

    // ------------------------------------------------------------------ action act

    private void spans(ReplayKineticPen p, double t, ReplayVideoScript.Sample s) {
        float w = p.width(), h = p.height();
        for (var span : o.activeSpans()) {
            double in = ReplayStagePlan.smoothstep(span.start(), span.start() + 0.25, t) * (1 - ReplayStagePlan.smoothstep(span.end(), span.end() + 0.4, t));
            if (in <= 0) continue;
            if (span.span().kind() == LastMatchReplay.SpanKind.BLACKOUT) {
                rect(p, 0, 0, w, h, a(0xFF02040C, 0.45 * in));
                if (t - span.start() < 0.1) p.invert(0, 0, w, h, 0.8);
                micro(p, "BLACKOUT", "POWER LOST", w / 2, h - 110, 10, a(theme.blue, in), a(0xFFFFFFFF, 0.8 * in), 0);
            } else {
                double pulse = 0.6 + 0.4 * Math.sin(t * 6);
                rect(p, 0, 0, 10, h, a(theme.red, 0.8 * in * pulse));
                rect(p, w - 10, 0, w, h, a(theme.red, 0.8 * in * pulse));
            }
        }
    }

    private void impacts(ReplayKineticPen p, double t, @Nullable ReplayCinemaDirector.CinemaSample cs) {
        for (Beat b : o.activeBeats()) {
            double u = b.local(t);
            if (u < 0 || u > 0.4) continue;
            double strength = switch (b.type()) {
                case KILL -> 0.85;
                case EXPLOSION -> 1.0;
                case REVIVAL -> 0.55;
                default -> 0;
            };
            if (strength > 0) ReplayKineticAction.impact(p, theme, u, strength);
        }
        if (cs != null && cs.segment() != null) {
            var kind = cs.segment().kind();
            if (kind == SegmentKind.TIER4_IMPACT_FREEZE || kind == SegmentKind.TIER5_APEX_FREEZE) {
                double u = t - cs.segment().videoStart();
                if (u >= 0 && u < 0.067) p.invert(0, 0, p.width(), p.height(), 1.0);
            }
        }
    }

    private void action(ReplayKineticPen p, double t, ReplayVideoScript.Sample s,
                        @Nullable ReplayCinemaDirector.CinemaSample cs, ReplayVideoCamera.Shot shot,
                        @Nullable ReplayActorSizing heads) {
        float w = p.width(), h = p.height();
        double k = s.actTime();
        double hud = outCubic((k - 0.2) / 0.5) * (1 - smooth(s.actLength() - 0.4, s.actLength(), k));
        var camera = cs == null ? ReplayCinemaDirector.CameraDirective.DIORAMA_OVERVIEW : cs.camera();
        boolean diorama = camera == ReplayCinemaDirector.CameraDirective.DIORAMA_OVERVIEW
                || camera == ReplayCinemaDirector.CameraDirective.DIORAMA_CLOSEUP
                || camera == ReplayCinemaDirector.CameraDirective.DIORAMA_FOLLOW
                || camera == ReplayCinemaDirector.CameraDirective.SPLIT_DIORAMA_AND_POV;
        double speedMul = options.mode() == ReplayExportContentOptions.ExportMode.PERSONAL ? options.personalPacing().speedMultiplier : 1;
        // Mode chrome under the HUD.
        if (theme == ReplayKineticTheme.DOSSIER) {
            if (camera == ReplayCinemaDirector.CameraDirective.POV_FIRST_PERSON) {
                ReplayKineticAction.viewfinder(p, theme, t, s.replayTick() / 20.0, speedMul, accent, hud);
            } else {
                if (diorama && heads != null && subject != null) {
                    Vector3f pt = o.actorPoint(subject, s.replayTick());
                    if (pt != null) {
                        Vector3f sp = shot.toScreen(pt.add(0, ReplayVideoOverlay.headSize(heads, subject, pt) * 1.1f, 0), w, h);
                        if (o.onScreen(sp)) ReplayKineticAction.orbitMarker(p, theme, sp.x, sp.y, subject.name, accent, t, hud);
                    }
                }
                if (speedMul > 1.05) ReplayKineticAction.fastForward(p, theme, speedMul, t, hud);
            }
        }
        ReplayKineticAction.hud(p, theme, hudData(s, cs), hud, t, options.showTimecode(), options.showAliveMatrix(), options.showTimeline());
        if (options.showKillFeed()) ReplayKineticAction.feed(p, theme, feedRows(t), hud);
        // Auto-edit tiers.
        boolean apexActive = false;
        if (theme == ReplayKineticTheme.MUSIC_VIDEO && cs != null && cs.segment() != null && cs.activeHighlight() != null) {
            var seg = cs.segment();
            double pr = seg.progressAt(t), age = t - seg.videoStart();
            double win = smooth(0, 0.1, pr) * (1 - smooth(0.85, 1.0, pr));
            var node = cs.activeHighlight().primaryNode();
            switch (seg.kind()) {
                case TIER1_GLIMMER -> ReplayKineticAction.lyricLine(p, theme, o.feedLine(node), accent, pr * 1.6, win);
                case TIER3_OVER_SHOULDER, TIER3_HERO_SIDE -> ReplayKineticAction.heroLyric(p, theme,
                        tagFor(node), wordFor(node, 1, false, false), accent, age, win);
                case TIER5_SUCTION_DOLLY, TIER5_APEX_FREEZE -> {
                    apexActive = true;
                    ReplayKineticAction.apex(p, theme, stampWord(cs.stampKey()), age, t, win);
                }
                default -> {}
            }
        }
        // Event headlines and 3D marks.
        var beats = o.activeBeats();
        for (int i = 0; i < beats.size(); i++) {
            Beat b = beats.get(i);
            double u = b.local(t);
            if (u < -0.4 || u > 2.7) continue;
            if (diorama && heads != null) {
                Vector3f pt = o.subjectPoint(b);
                if (pt != null) {
                    Vector3f sp = shot.toScreen(pt.add(0, ReplayVideoOverlay.headSize(heads, o.subjectActor(b), pt) * 0.9f, 0), w, h);
                    float maxRight = cs != null && cs.splitScreen() && cs.splitLeftRatio() < 0.985 ? (float) (w * cs.splitLeftRatio()) - 82f : w - 24f;
                    if (o.onScreen(sp) && sp.x < maxRight + 20) {
                        var who = o.subjectActor(b);
                        String label = (who == null ? "?" : who.name.getString());
                        ReplayKineticAction.mark(p, theme, sp.x, sp.y, label, beatAccent(b), u, b.type() == BeatType.KILL, i, maxRight);
                    }
                }
            }
            if (b.type() != BeatType.HIT && !apexActive) ReplayKineticAction.headline(p, theme, killData(b, i, s.replayTick()), u);
        }
    }

    private ReplayKineticData.Hud hudData(ReplayVideoScript.Sample s, @Nullable ReplayCinemaDirector.CinemaSample cs) {
        boolean right = cs != null && cs.segment() != null && (cs.segment().kind() == SegmentKind.TIER4_POV_EXPAND
                || (cs.instantReplay() && cs.splitScreen() && cs.splitLeftRatio() < 0.5));
        double tick = right ? cs.rightPovTick() : s.replayTick();
        String speed;
        int kind;
        if (s.freeze()) { speed = Component.translatable("habitrain_core.replay_video.hud.freeze").getString(); kind = 2; }
        else if (s.slow()) { speed = String.format(Locale.ROOT, "\u00d7%.2f", s.speed()); kind = 1; }
        else { speed = String.format(Locale.ROOT, "\u00d7%.1f", s.speed()); kind = 0; }
        int total = model.actors.length, alive = 0;
        int[] colors = new int[total];
        boolean[] states = new boolean[total];
        for (int i = 0; i < total; i++) {
            var actor = model.actors[i];
            states[i] = actor.track.sample(s.replayTick()).state() == ReplayTrackSampler.State.ALIVE;
            if (states[i]) alive++;
            colors[i] = o.roleRevealed(actor.p.id(), s.replayTick()) ? actor.factionColor() : ReplayPalette.TEXT_MUTED;
        }
        int startT = o.timelineStartTick(), endT = Math.max(startT + 1, o.timelineEndTick());
        var beats = o.activeBeats();
        double[] markers = new double[beats.size()];
        int[] markerColors = new int[beats.size()];
        for (int i = 0; i < beats.size(); i++) {
            markers[i] = (beats.get(i).tick() - startT) / (double) (endT - startT);
            markerColors[i] = beatAccent(beats.get(i));
        }
        return new ReplayKineticData.Hud(ReplayStagePlan.clock(tick), speed, kind, alive, total, colors, states,
                (s.replayTick() - startT) / (endT - startT), markers, markerColors,
                Component.literal(model.replay.mapName()), ReplayStagePlan.clock(endT), person(subject, s.replayTick()));
    }

    private List<ReplayKineticData.FeedRow> feedRows(double t) {
        List<ReplayVideoScript.Feed> live = new ArrayList<>();
        for (var f : o.activeFeeds()) {
            if (f.time() > t) break;
            if (t - f.time() < 3.2) live.add(f);
        }
        List<ReplayKineticData.FeedRow> rows = new ArrayList<>();
        for (int i = live.size() - 1; i >= Math.max(0, live.size() - 5); i--) {
            var f = live.get(i);
            rows.add(new ReplayKineticData.FeedRow(o.feedLine(f.node()), o.feedColor(f.node()), t - f.time()));
        }
        return rows;
    }

    private int beatAccent(Beat b) {
        return switch (b.type()) {
            case KILL, EXPLOSION -> theme.red;
            case REVIVAL, DEFUSE, SHIELD -> theme.blue;
            case HIT -> ReplayPalette.LAMP;
        };
    }

    static String wordFor(LastMatchReplay.Node node, int streak, boolean firstBlood, boolean finalBlow) {
        return switch (node.kind()) {
            case KILL -> streak >= 4 ? "MULTI KILL" : streak == 3 ? "TRIPLE KILL" : streak == 2 ? "DOUBLE KILL"
                    : firstBlood ? "FIRST BLOOD" : finalBlow ? "FINAL BLOW" : "ELIMINATED";
            case PLAYER_REVIVAL -> "REVIVED";
            case GRENADE_EXPLOSION, BOMB_DETONATE -> "DETONATION";
            case BOMB_DEFUSE -> "DEFUSED";
            case SHIELD_BROKEN -> "SHIELD BREAK";
            default -> "HIGHLIGHT";
        };
    }

    private static String wordFor(Beat b) {
        return switch (b.type()) {
            case KILL -> wordFor(b.node(), b.streak(), b.firstBlood(), b.finalBlow());
            case REVIVAL -> "REVIVED";
            case EXPLOSION -> "DETONATION";
            case DEFUSE -> "DEFUSED";
            case SHIELD -> "SHIELD BREAK";
            case HIT -> "HIT";
        };
    }

    /** Localized short tag used for drop-cap lyric compositions. */
    private String tagFor(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case KILL -> Component.translatable("habitrain_core.replay_video.tag.kill").getString() + " \u00b7 "
                    + ReplayBoardTexts.name(model.participantData(node.target())).getString();
            default -> o.feedLine(node).getString();
        };
    }

    private static String stampWord(String key) {
        return switch (key == null ? "" : key) {
            case "MULTI_KILL" -> "MULTI KILL";
            case "FINAL_BLOW" -> "FINAL BLOW";
            case "MVP" -> "MVP";
            default -> "APOTHEOSIS";
        };
    }

    private ReplayKineticData.Kill killData(Beat b, int index, double tick) {
        var node = b.node();
        Component tag;
        Component weapon;
        if (b.type() == BeatType.KILL) {
            Component sub = o.badgeSubtitle(b);
            tag = sub == null ? Component.translatable("habitrain_core.replay_video.tag.kill") : sub;
            weapon = ReplayBoardTexts.weapon(node);
        } else {
            tag = o.feedLine(node);
            weapon = Component.literal(node.kind().name().replace('_', ' '));
        }
        boolean involved = subject != null && (subject.p.id().equals(node.actor()) || subject.p.id().equals(node.target()));
        int acc = theme == ReplayKineticTheme.DOSSIER && involved ? accent : beatAccent(b);
        return new ReplayKineticData.Kill(wordFor(b), tag, person(model.participant(node.actor()), tick),
                person(model.participant(node.target()), tick), weapon, acc, index, b.streak(), involved);
    }

    private @Nullable ReplayKineticData.Person person(@Nullable ReplayBoardModel.Actor a, double tick) {
        if (a == null) return null;
        boolean revealed = o.roleRevealed(a.p.id(), tick);
        return new ReplayKineticData.Person(a.p.id(), a.name, revealed ? a.role : Component.literal("???"),
                revealed ? a.factionColor() : ReplayPalette.TEXT_MUTED, a == model.mvp, a.p.winner());
    }

    private ReplayKineticData.Person fullPerson(ReplayBoardModel.Actor a) {
        return new ReplayKineticData.Person(a.p.id(), a.name, a.role, a.factionColor(), a == model.mvp, a.p.winner());
    }

    // ------------------------------------------------------------------ other acts

    private ReplayKineticData.Title titleData() {
        String view = options.mode() == ReplayExportContentOptions.ExportMode.PERSONAL
                ? (options.personalView() == ReplayExportContentOptions.PersonalView.TRUE_POV ? "FIRST PERSON" : "FOLLOW")
                : options.mode() == ReplayExportContentOptions.ExportMode.CINEMATIC_PROMO ? "AUTO EDIT" : "DIORAMA";
        String pace = options.mode() == ReplayExportContentOptions.ExportMode.PERSONAL
                ? options.personalPacing().id.toUpperCase(Locale.ROOT) : "AUTO";
        return new ReplayKineticData.Title(Component.literal(model.replay.mapName()),
                ReplayVideoOverlay.ticketMode(model.replay.modeId(), model.modeLabel), o.date,
                ReplayStagePlan.clock(model.replay.durationTicks()), model.actors.length, o.trainNo,
                subject == null ? null : fullPerson(subject), view, pace, model.mapW, model.mapD, model.mapH);
    }

    private List<ReplayKineticData.Stat> stats(double k) {
        double count = ReplayStagePlan.easeOutCubic((k - 0.4) / 1.4);
        if (theme == ReplayKineticTheme.DOSSIER && subject != null) {
            var sp = subject.p;
            return List.of(
                    stat("mvp.score", String.valueOf((int) Math.round(sp.score() * count)), theme.ink),
                    stat("mvp.kills", String.valueOf((int) Math.round(sp.kills() * count)), theme.red),
                    stat("mvp.survival", ReplayStagePlan.clock(sp.survivalSeconds() * 20.0 * count), theme.blue),
                    stat("mvp.items", String.valueOf((int) Math.round(sp.itemUses() * count)), theme.ink));
        }
        return List.of(
                stat("stat.duration", ReplayStagePlan.clock(model.replay.durationTicks() * count), theme.ink),
                stat("stat.kills", String.valueOf((int) Math.round(model.replay.killCount() * count)), theme.red),
                stat("stat.players", String.valueOf((int) Math.round(model.actors.length * count)), theme.blue),
                stat("stat.highlights", String.valueOf((int) Math.round(o.objectiveHighlights * count)), theme.ink));
    }

    private static ReplayKineticData.Stat stat(String key, String value, int color) {
        return new ReplayKineticData.Stat(Component.translatable("habitrain_core.replay_video." + key), value, color);
    }

    private ReplayKineticData.Victory victoryData() {
        List<ReplayKineticData.Person> winners = new ArrayList<>();
        for (var a : model.winners) winners.add(fullPerson(a));
        Component sub = model.subtitle;
        if (!model.modeLabel.getString().isBlank() && !model.modeLabel.getString().equals(model.subtitle.getString())) {
            sub = Component.empty().append(model.subtitle).append("  \u00b7  ").append(model.modeLabel);
        }
        boolean personal = theme == ReplayKineticTheme.DOSSIER && subject != null;
        return new ReplayKineticData.Victory(model.title, sub, model.winColor, winners,
                personal ? fullPerson(subject) : null, personal ? subject.p.winner() : null);
    }

    private ReplayKineticData.Mvp mvpData(ReplayBoardModel.Actor m) {
        var sp = m.p;
        List<ReplayKineticData.Stat> st = List.of(
                stat("mvp.score", String.valueOf(sp.score()), theme.ink),
                stat("mvp.kills", String.valueOf(sp.kills()), theme.red),
                stat("mvp.survival", ReplayStagePlan.clock(sp.survivalSeconds() * 20.0), theme.blue),
                stat("mvp.items", String.valueOf(sp.itemUses()), theme.ink));
        double[] ratios = {sp.score() / (double) o.maxScore, sp.kills() / (double) o.maxKills,
                sp.survivalSeconds() / (double) o.maxSurvival, sp.itemUses() / (double) o.maxItems};
        return new ReplayKineticData.Mvp(fullPerson(m), st, ratios);
    }

    private ReplayKineticData.Outro outroData(double k, double len) {
        var ranking = o.ranking;
        int pageSize = ReplayVideoScript.OUTRO_PAGE_SIZE;
        int pages = Math.max(1, (ranking.size() + pageSize - 1) / pageSize);
        int page = 0;
        double alpha = 1;
        float slide = 0;
        if (pages > 1) {
            double start = 0.45, usable = Math.max(2.0, len - 1.5 - start), slot = usable / pages;
            double elapsed = Math.max(0, k - start);
            page = Math.min(pages - 1, (int) (elapsed / slot));
            double in = elapsed - page * slot, half = Math.min(0.2, slot * 0.08);
            if (page > 0 && in < half) { double e = outCubic(in / half); alpha = e; slide = (float) ((1 - e) * 44); }
            else if (page < pages - 1 && in > slot - half) { double e = inOutQuart((in - (slot - half)) / half); alpha = 1 - e; slide = (float) (-e * 44); }
        }
        List<ReplayKineticData.Row> rows = new ArrayList<>();
        UUID focus = o.focusPlayer();
        for (int i = page * pageSize; i < Math.min(ranking.size(), (page + 1) * pageSize); i++) {
            var a = ranking.get(i);
            rows.add(new ReplayKineticData.Row(i + 1, fullPerson(a), a.p.kills(), a.p.score(),
                    ReplayStagePlan.clock(a.p.survivalSeconds() * 20.0), focus != null && focus.equals(a.p.id())));
        }
        Component kills;
        if (model.firstKill == null) kills = ReplayBoardTexts.tr("no_kill");
        else if (model.lastKill == null || model.lastKill == model.firstKill) kills = ReplayBoardTexts.killLine("first_kill", model.firstKill, model);
        else kills = Component.empty().append(ReplayBoardTexts.killLine("first_kill", model.firstKill, model)).append("   ")
                    .append(ReplayBoardTexts.killLine("last_kill", model.lastKill, model));
        return new ReplayKineticData.Outro(rows, page, pages, alpha, slide, page == 0, kills,
                Component.translatable("habitrain_core.replay_video.outro.footer", o.date),
                Component.translatable("habitrain_core.replay_video.outro.title"));
    }
}
