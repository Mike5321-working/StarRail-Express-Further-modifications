package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import com.habitrain.core.api.match.replay.ReplaySkillEvent;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplayCinemaDirector;
import com.habitrain.core.scene.replay.ReplayDeathIcons;
import com.habitrain.core.scene.replay.ReplayExportContentOptions;
import com.habitrain.core.scene.replay.ReplayMusicPlan;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import com.habitrain.core.scene.replay.ReplayVideoScriptTest;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guards for issues reported in 回放导出视频问题排查报告-8cbd891f.md.
 */
class ReplayVideoBugfixRegressionTest {

    @Test
    void displayFontWrapperAvoidsTofuOnCjkTextWhilePreservingAsciiRuns() {
        Component ascii = ReplayVideoCanvas.font(Component.literal("01:23"), ReplayVideoCanvas.BIG);
        assertEquals(ReplayVideoCanvas.BIG, ascii.getStyle().getFont());

        Component pureCjk = ReplayVideoCanvas.font(Component.literal("定格"), ReplayVideoCanvas.UI);
        assertEquals("定格", pureCjk.getString());
        pureCjk.visit((style, str) -> {
            if (!str.isEmpty()) {
                assertEquals(Style.DEFAULT_FONT, style.getFont(), "CJK text must keep default font: " + str);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);

        Component mixed = ReplayVideoCanvas.font(Component.literal("慢镜 ×0.56"), ReplayVideoCanvas.UI);
        assertEquals("慢镜 ×0.56", mixed.getString());
        List<String> uiParts = new ArrayList<>();
        List<String> defaultParts = new ArrayList<>();
        mixed.visit((style, str) -> {
            if (!str.isEmpty()) {
                if (ReplayVideoCanvas.UI.equals(style.getFont())) uiParts.add(str);
                else defaultParts.add(str);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        assertTrue(defaultParts.stream().anyMatch(s -> s.contains("慢镜")), "Chinese prefix should stay default font");
        assertTrue(uiParts.stream().anyMatch(s -> s.contains("×0.56")), "Numeric multiplier should use UI display font");
    }

    @Test
    void transitionCutSwitchesMatchTickAtMidpoint() {
        var seg = new ReplayCinemaDirector.DirectedSegment(
                0,
                ReplayCinemaDirector.SegmentKind.TRANSITION_CUT,
                ReplayCinemaDirector.CameraDirective.DIORAMA_OVERVIEW,
                ReplayCinemaDirector.CutStyle.BRASS_SWEEP,
                10.0, 10.6,
                140.0, 640.0,
                0.0, 0.0,
                false, false, 1.0, 1.0,
                140.0, 640.0,
                null, null, 1.0, 1.0f, 0.0, 0.0, ""
        );
        assertEquals(140.0, seg.tickAt(10.0), 1e-6);
        assertEquals(140.0, seg.tickAt(10.29), 1e-6);
        assertEquals(640.0, seg.tickAt(10.30), 1e-6);
        assertEquals(640.0, seg.tickAt(10.60), 1e-6);
    }

    @Test
    void personalDefaultsEnableLowBackgroundMusic() {
        var opts = ReplayExportContentOptions.defaultsFor(ReplayExportContentOptions.ExportMode.PERSONAL);
        assertEquals(ReplayMusicPlan.Level.LOW, opts.musicLevel());
        assertTrue(opts.musicEnabled());
    }

    @Test
    void readableRoleColorLiftsDarkColorsForLegibility() {
        int darkViolet = ReplayPalette.readableRoleColor(0xFF2B1B54);
        int darkCrimson = ReplayPalette.readableRoleColor(0xFF5A1118);
        int darkGuardBlue = ReplayPalette.readableRoleColor(0xFF1D3A6E);
        int darkStalkerGray = ReplayPalette.readableRoleColor(0xFF3E3B39);
        assertTrue(luminance(darkViolet) >= 0.48, "Dark violet should be lifted, lum=" + luminance(darkViolet));
        assertTrue(luminance(darkCrimson) >= 0.48, "Dark crimson should be lifted, lum=" + luminance(darkCrimson));
        assertTrue(luminance(darkGuardBlue) >= 0.48, "Dark guard blue should be lifted, lum=" + luminance(darkGuardBlue));
        assertTrue(luminance(darkStalkerGray) >= 0.48, "Dark stalker gray should be lifted, lum=" + luminance(darkStalkerGray));
    }

    @Test
    void fallDeathIconUsesTrainRelatedItemInsteadOfFeather() {
        assertEquals(ReplayDeathIcons.Icon.FALL, ReplayDeathIcons.classify("starrailexpress:fell_out_of_train"));
        var candidates = ReplayDeathIcons.itemCandidates(ReplayDeathIcons.Icon.FALL);
        assertFalse(candidates.contains("minecraft:feather"));
        assertTrue(candidates.contains("minecraft:minecart"));
    }

    @Test
    void mvpCameraFacesFrontWhenFrontYawProvided() {
        var replay = ReplayVideoScriptTest.replay(1200, List.of(), List.of());
        var script = new ReplayVideoScript(replay);
        var stage = new ReplayVideoCamera.Stage(3.45f, 6.25f, 1.7f, 0.3f, 0.4f, 0.12f);
        double frontYaw = Math.PI * 0.5; // +X front
        var subjects = new ReplayVideoCamera.Subjects() {
            @Override public Vector3f centroid(double tick) { return new Vector3f(0, 0.3f, 0); }
            @Override public @Nullable Vector3f actor(@Nullable UUID id, double tick) { return new Vector3f(0, 0.3f, 0); }
            @Override public Vector3f event(LastMatchReplay.Node node, boolean to) { return new Vector3f(0, 0.3f, 0); }
            @Override public @Nullable Vector3f mvp() { return new Vector3f(0, 0.3f, 0); }
            @Override public UUID mvpId() { return ReplayVideoScriptTest.A; }
            @Override public Double mvpFrontYawRad() { return frontYaw; }
            @Override public Vector3f winners() { return new Vector3f(0, 0.3f, 0); }
        };
        double midMvp = script.actStart(ReplayVideoScript.Act.MVP) + script.actLength(ReplayVideoScript.Act.MVP) * 0.5;
        var shot = ReplayVideoCamera.at(script, subjects, stage, midMvp);
        // Camera eye should be on the +X side of the MVP (in front of them), looking toward -X.
        assertTrue(shot.eye().x > 0.5f, "MVP camera should stand in front of +X facing MVP, eye=" + shot.eye());
        assertTrue(shot.look().x < -0.5f, "MVP camera should look into the MVP's face, look=" + shot.look());
    }

    @Test
    void openingTickZeroPassiveEffectsAreSuppressedFromFallbackToasts() {
        UUID actor = UUID.randomUUID();
        var passiveTick0 = new ReplaySkillEvent(1, 0, "mod:spawn_guard", "开局保护",
                ReplaySkillEvent.CAT_EFFECT, actor, null, "starrailexpress:killer", 0xFFFF4444,
                LastMatchReplay.Position.quantize(5, 2, 5), null, 40, "", true);
        var activeMidMatch = new ReplaySkillEvent(2, 120, "mod:void_blink", "虚空闪烁",
                ReplaySkillEvent.CAT_ROLE_SKILL, actor, null, "starrailexpress:killer", 0xFFFF4444,
                LastMatchReplay.Position.quantize(5, 2, 5), null, 40, "", true);
        var person = new LastMatchReplay.Participant(actor, "Tester", "starrailexpress:killer", 0xFFFF4444,
                "starrailexpress:killer", 0xFFFF4444, MatchWinFaction.KILLER, true, 60, 0, 10, 0,
                List.of(new LastMatchReplay.Sample(0, LastMatchReplay.Position.quantize(5, 2, 5), (byte) 0, LastMatchReplay.ALIVE)));
        var replay = new LastMatchReplay(UUID.randomUUID(), 0, 300, "Express", "murder", "",
                MatchWinKind.KILLER, "", "", "", 0, new SceneBounds(0, 0, 0, 32, 16, 20), null,
                List.of(person), List.of(), List.of(), actor, 0, false)
                .withWorldDynamics(List.of(), List.of(), List.of(passiveTick0, activeMidMatch), ReplayGameSoundData.EMPTY);
        var model = new ReplayBoardModel(replay, new ReplayTimeline(replay));

        assertTrue(ReplaySkillFallbackHud.collectActive(model, 5.0).isEmpty(),
                "Tick-0 passive CAT_EFFECT fallback must be suppressed");
        assertEquals(1, ReplaySkillFallbackHud.collectActive(model, 130.0).size(),
                "Genuine mid-match skill fallback must still appear");
    }

    @Test
    void tier4PovExpandKeepsHeroSideCameraOnLeftPanel() {
        UUID actor = UUID.randomUUID();
        var person = new LastMatchReplay.Participant(actor, "Hero", "starrailexpress:killer", 0xFFFF4444,
                "starrailexpress:killer", 0xFFFF4444, MatchWinFaction.KILLER, true, 60, 1, 100, 1,
                List.of(new LastMatchReplay.Sample(0, LastMatchReplay.Position.quantize(10, 2, 10), (byte) 0, LastMatchReplay.ALIVE),
                        new LastMatchReplay.Sample(200, LastMatchReplay.Position.quantize(12, 2, 10), (byte) 0, LastMatchReplay.ALIVE)));
        var replay = new LastMatchReplay(UUID.randomUUID(), 0, 200, "Express", "murder", "",
                MatchWinKind.KILLER, "", "", "", 0, new SceneBounds(0, 0, 0, 32, 16, 20), null,
                List.of(person), List.of(), List.of(), actor, 1, false);
        var model = new ReplayBoardModel(replay, new ReplayTimeline(replay));
        var film = ReplayCinemaDirector.direct(replay, ReplayVoiceData.EMPTY,
                ReplayExportContentOptions.defaultsFor(ReplayExportContentOptions.ExportMode.CINEMATIC_PROMO, actor));
        var seg = new ReplayCinemaDirector.DirectedSegment(
                0, ReplayCinemaDirector.SegmentKind.TIER4_POV_EXPAND,
                ReplayCinemaDirector.CameraDirective.SPLIT_HERO_SIDE_AND_POV,
                ReplayCinemaDirector.CutStyle.NONE,
                10.0, 13.0, 100.0, 100.0, 1.0, 1.0,
                true, true, 0.48, 0.0, 60.0, 120.0, actor, null,
                1.0, 1.0f, 104.0, 1.05, ""
        );
        var base = new ReplayVideoScript.Sample(ReplayVideoScript.Act.ACTION, 11.0, 11.0, 20.0, 100.0, 1.0, false, false, 11.0, -1.0);
        var cs = new ReplayCinemaDirector.CinemaSample(
                base, seg, seg.camera(), true, 0.30, 80.0, actor, null, 1.0,
                1.0, 1.0f, 104.0, 1.05, true, ReplayCinemaDirector.CutStyle.NONE, -1.0, ""
        );
        var leftCam = ReplayVideoCamera.povCameraAt(film, model, cs, 11.0, false);
        assertNotNull(leftCam);
        assertEquals(ReplayPovSceneRenderer.CameraMode.HERO_SIDE, leftCam.mode());
    }

    private static double luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
    }
}
