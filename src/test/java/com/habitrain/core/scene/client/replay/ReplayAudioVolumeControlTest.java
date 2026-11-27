package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.client.config.ReplayAudioSettings;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReplayAudioVolumeControlTest {

    @AfterEach
    void tearDown() {
        ReplayAudioPlaybackController.setVolumeSourceForTest(null);
        ReplayBoardButtons.reset();
    }

    @Test
    void liveSoundInstancesTrackVolumeSliderAndMuteChangesEachTick() {
        AtomicReference<ReplayAudioSettings> prefs = new AtomicReference<>(ReplayAudioSettings.DEFAULT);
        ReplayAudioPlaybackController.setVolumeSourceForTest(new ReplayAudioPlaybackController.VolumeSource() {
            @Override
            public float voiceScale() {
                return prefs.get().effectiveVoiceScale();
            }

            @Override
            public float sfxScale() {
                return prefs.get().effectiveSfxScale();
            }
        });

        byte[] dummyPcm = new byte[48_000 * 2];
        var voice = new ReplayAudioPlaybackController.VoiceClipSoundInstance(
                dummyPcm, 0, 1.0f, 0.0, 0.0, 0.0, true);
        var effect = new ReplayAudioPlaybackController.ReplayEffectSound(
                ResourceLocation.fromNamespaceAndPath("minecraft", "entity.experience_orb.pickup"),
                null, 1.0f, 1.0f, Vec3.ZERO, true);

        assertEquals(ReplayAudioPlaybackController.REPLAY_GAIN, voice.rawVolume(), 1e-5f);
        assertEquals(ReplayAudioPlaybackController.REPLAY_GAIN, effect.rawVolume(), 1e-5f);

        // Lower master to 50%, voice to 80% (effective 40%), sfx to 20% (effective 10%)
        prefs.set(new ReplayAudioSettings(false, 0.5f, 0.8f, 0.2f));
        voice.tick();
        effect.tick();
        assertEquals(ReplayAudioPlaybackController.REPLAY_GAIN * 0.4f, voice.rawVolume(), 1e-5f);
        assertEquals(ReplayAudioPlaybackController.REPLAY_GAIN * 0.1f, effect.rawVolume(), 1e-5f);

        // One-click mute immediately drops both active streams to zero
        prefs.set(prefs.get().toggledMute());
        voice.tick();
        effect.tick();
        assertEquals(0.0f, voice.rawVolume(), 1e-6f);
        assertEquals(0.0f, effect.rawVolume(), 1e-6f);

        // Unmuting restores exact per-channel effective volume without restarting
        prefs.set(prefs.get().toggledMute());
        voice.tick();
        effect.tick();
        assertEquals(ReplayAudioPlaybackController.REPLAY_GAIN * 0.4f, voice.rawVolume(), 1e-5f);
        assertEquals(ReplayAudioPlaybackController.REPLAY_GAIN * 0.1f, effect.rawVolume(), 1e-5f);
    }

    @Test
    void overlaySliderSnappingAndWheelSteppingAreExactAndClamped() {
        assertEquals(0.0f, ReplayAudioSettingsOverlay.snapRatioToVolume(-0.3), 1e-6f);
        assertEquals(0.37f, ReplayAudioSettingsOverlay.snapRatioToVolume(0.366), 1e-6f);
        assertEquals(1.0f, ReplayAudioSettingsOverlay.snapRatioToVolume(1.4), 1e-6f);

        assertEquals(0.55f, ReplayAudioSettingsOverlay.stepVolume(0.50f, 1.0), 1e-6f);
        assertEquals(0.45f, ReplayAudioSettingsOverlay.stepVolume(0.50f, -1.0), 1e-6f);
        assertEquals(1.0f, ReplayAudioSettingsOverlay.stepVolume(0.98f, 1.0), 1e-6f);
        assertEquals(0.0f, ReplayAudioSettingsOverlay.stepVolume(0.02f, -1.0), 1e-6f);
    }

    @Test
    void modalOverlayLiftsAboveDropShadowTextAndControlsScreenOwnsBackgroundPass() throws NoSuchMethodException {
        assertTrue(ReplayAudioSettingsOverlay.OVERLAY_Z > 150.0f,
                "Modal overlay Z must clear drop-shadow text (+0.03) and rendered items (+150)");
        assertTrue(ReplayAudioSettingsOverlay.OVERLAY_Z < 400.0f,
                "Modal overlay Z must stay below vanilla tooltips (+400)");
        assertEquals(ReplayBoardControlsScreen.class,
                ReplayBoardControlsScreen.class.getDeclaredMethod(
                        "renderBackground",
                        net.minecraft.client.gui.GuiGraphics.class, int.class, int.class, float.class)
                        .getDeclaringClass(),
                "ReplayBoardControlsScreen must override renderBackground so Screen.render does not blur/darken over panel content");
    }

    @Test
    void sideEdgeDeckIncludesLocalVolumeControlWithoutOverlap() {
        LastMatchReplay replay = new LastMatchReplay(
                UUID.randomUUID(), 0L, 1200, "murder", "TestMap", "PASSENGERS",
                MatchWinKind.INNOCENT, "", "", "", 0,
                new SceneBounds(0, 0, 0, 16, 16, 16), null,
                List.of(), List.of(), List.of(), null, 0, false);
        ReplayBoardModel model = new ReplayBoardModel(replay, new ReplayTimeline(replay));
        ReplayBoardSessionState session = ReplayBoardSessionState.fresh(replay.roundId(), false, 100L);

        // Even when server session controls are unavailable, local VOLUME button stays enabled
        ReplayAudioSettings muted = ReplayAudioSettings.DEFAULT.withMuted(true);
        List<ReplayBoardButtons.DeckWidget> widgets = ReplayBoardButtons.buildDeckWidgets(
                model, session, false, false, false, muted);

        ReplayBoardButtons.DeckWidget vol = widgets.stream()
                .filter(w -> w.action() == ReplayBoardButtons.Action.VOLUME)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected VOLUME widget on 3D side-edge deck"));
        assertTrue(vol.enabled(), "Local volume control must remain enabled even without server control permissions");
        assertTrue(vol.accentRuby(), "Muted local volume button must highlight in ruby");

        // When the 3D volume slider wing is toggled open, the VOLUME button highlights in cyan
        List<ReplayBoardButtons.DeckWidget> openWidgets = ReplayBoardButtons.buildDeckWidgets(
                model, session, false, false, false, ReplayAudioSettings.DEFAULT, true);
        ReplayBoardButtons.DeckWidget openVol = openWidgets.stream()
                .filter(w -> w.action() == ReplayBoardButtons.Action.VOLUME)
                .findFirst()
                .orElseThrow();
        assertTrue(openVol.selected() && openVol.accentCyan(),
                "VOLUME button must show active cyan highlight while 3D volume drawer is open");

        // All deck widgets must stay strictly inside console bounds and never overlap
        for (int i = 0; i < widgets.size(); i++) {
            var a = widgets.get(i);
            assertTrue(a.x0() >= ReplayBoardButtons.DECK_X0 && a.x1() <= ReplayBoardButtons.DECK_X1,
                    "Widget " + a.action() + " exceeds deck X bounds");
            assertTrue(a.y0() >= ReplayBoardButtons.DECK_Y0 && a.y1() <= ReplayBoardButtons.DECK_Y1,
                    "Widget " + a.action() + " exceeds deck Y bounds");
            assertTrue(a.x1() > a.x0() + 0.05f && a.y1() > a.y0() + 0.05f,
                    "Widget " + a.action() + " has degenerate dimensions");
            for (int j = i + 1; j < widgets.size(); j++) {
                var b = widgets.get(j);
                boolean xOverlap = a.x0() < b.x1() + 0.015f && b.x0() < a.x1() + 0.015f;
                boolean yOverlap = a.y0() < b.y1() + 0.015f && b.y0() < a.y1() + 0.015f;
                assertFalse(xOverlap && yOverlap,
                        "Widgets " + a.action() + " and " + b.action() + " overlap or violate gap spacing");
            }
        }
    }

    @Test
    void popOutVolumeSliderMapsSplitRailAndCenterMuteIconCleanly() {
        // Left half of the split rail maps linearly to 0%..50%
        assertEquals(0.00f, ReplayBoardButtons.volumeFromSliderX(ReplayBoardButtons.VOL_RAIL_LEFT_X0 - 0.05f), 1e-6f);
        assertEquals(0.00f, ReplayBoardButtons.volumeFromSliderX(ReplayBoardButtons.VOL_RAIL_LEFT_X0), 1e-6f);
        float leftMid = (ReplayBoardButtons.VOL_RAIL_LEFT_X0 + ReplayBoardButtons.VOL_RAIL_LEFT_X1) * 0.5f;
        assertEquals(0.25f, ReplayBoardButtons.volumeFromSliderX(leftMid), 1e-6f);
        assertEquals(0.50f, ReplayBoardButtons.volumeFromSliderX(ReplayBoardButtons.VOL_RAIL_LEFT_X1), 1e-6f);

        // Center mute medallion gap maps to 50% during continuous slider sweeps
        assertEquals(0.50f, ReplayBoardButtons.volumeFromSliderX(ReplayBoardButtons.VOL_MUTE_CX), 1e-6f);

        // Right half of the split rail maps linearly to 50%..100%
        assertEquals(0.50f, ReplayBoardButtons.volumeFromSliderX(ReplayBoardButtons.VOL_RAIL_RIGHT_X0), 1e-6f);
        float rightMid = (ReplayBoardButtons.VOL_RAIL_RIGHT_X0 + ReplayBoardButtons.VOL_RAIL_RIGHT_X1) * 0.5f;
        assertEquals(0.75f, ReplayBoardButtons.volumeFromSliderX(rightMid), 1e-6f);
        assertEquals(1.00f, ReplayBoardButtons.volumeFromSliderX(ReplayBoardButtons.VOL_RAIL_RIGHT_X1), 1e-6f);
        assertEquals(1.00f, ReplayBoardButtons.volumeFromSliderX(ReplayBoardButtons.VOL_RAIL_RIGHT_X1 + 0.05f), 1e-6f);

        // Round-trip visual playhead coordinate <-> volume across 0%..100%
        for (int pct : new int[]{0, 12, 35, 50, 64, 88, 100}) {
            float v = pct / 100.0f;
            float x = ReplayBoardButtons.sliderXFromVolume(v);
            assertEquals(v, ReplayBoardButtons.volumeFromSliderX(x), 1e-6f,
                    "Round-trip failed for " + pct + "%");
            assertFalse(ReplayBoardButtons.isPointInVolumeMute(x, ReplayBoardButtons.VOL_SLIDER_Y),
                    "Playhead for " + pct + "% must never sit underneath the centered mute medallion");
        }

        // Centered mute medallion hit test is disjoint from slider track hit test
        assertTrue(ReplayBoardButtons.isPointInVolumeMute(
                ReplayBoardButtons.VOL_MUTE_CX, ReplayBoardButtons.VOL_SLIDER_Y));
        assertFalse(ReplayBoardButtons.isPointInVolumeSlider(
                ReplayBoardButtons.VOL_MUTE_CX, ReplayBoardButtons.VOL_SLIDER_Y));
        assertTrue(ReplayBoardButtons.isPointInVolumeSlider(leftMid, ReplayBoardButtons.VOL_SLIDER_Y));
        assertTrue(ReplayBoardButtons.isPointInVolumeSlider(rightMid, ReplayBoardButtons.VOL_SLIDER_Y));
        assertFalse(ReplayBoardButtons.isPointInVolumeMute(leftMid, ReplayBoardButtons.VOL_SLIDER_Y));
        assertFalse(ReplayBoardButtons.isPointInVolumeMute(rightMid, ReplayBoardButtons.VOL_SLIDER_Y));

        // Active slider drag crossing the middle glides past 50% instead of accidentally muting
        assertFalse(ReplayBoardButtons.shouldTreatMuteHitAsSliderDrag(1_000_000_000L, 0L));
        assertTrue(ReplayBoardButtons.shouldTreatMuteHitAsSliderDrag(1_100_000_000L, 1_000_000_000L));
        assertFalse(ReplayBoardButtons.shouldTreatMuteHitAsSliderDrag(1_300_000_000L, 1_000_000_000L));

        // Drawer open/close toggle & reset lifecycle
        BlockPos board = new BlockPos(10, 64, -5);
        assertFalse(ReplayBoardButtons.isVolumeDrawerOpen(board));
        assertTrue(ReplayBoardButtons.toggleVolumeDrawer(board));
        assertTrue(ReplayBoardButtons.isVolumeDrawerOpen(board));
        assertFalse(ReplayBoardButtons.toggleVolumeDrawer(board));
        assertFalse(ReplayBoardButtons.isVolumeDrawerOpen(board));
        ReplayBoardButtons.setVolumeDrawerOpen(board);
        assertTrue(ReplayBoardButtons.isVolumeDrawerOpen(board));
        ReplayBoardButtons.reset();
        assertFalse(ReplayBoardButtons.isVolumeDrawerOpen(board));

        // Volume drawer stays strictly inside wing bounds, strictly right of main deck, and widgets never overlap
        assertTrue(ReplayBoardButtons.VOL_PANEL_X0 > ReplayBoardButtons.DECK_X1);
        List<ReplayBoardButtons.DeckWidget> drawerWidgets = ReplayBoardButtons.buildVolumeDrawerWidgets();
        assertEquals(2, drawerWidgets.size());
        for (int i = 0; i < drawerWidgets.size(); i++) {
            var a = drawerWidgets.get(i);
            assertTrue(a.x0() >= ReplayBoardButtons.VOL_PANEL_X0 && a.x1() <= ReplayBoardButtons.VOL_PANEL_X1);
            assertTrue(a.y0() >= ReplayBoardButtons.VOL_PANEL_Y0 && a.y1() <= ReplayBoardButtons.VOL_SLIDER_Y0,
                    "Bottom drawer widget must sit strictly below the slider zone");
            for (int j = i + 1; j < drawerWidgets.size(); j++) {
                var b = drawerWidgets.get(j);
                boolean xOverlap = a.x0() < b.x1() + 0.015f && b.x0() < a.x1() + 0.015f;
                boolean yOverlap = a.y0() < b.y1() + 0.015f && b.y0() < a.y1() + 0.015f;
                assertFalse(xOverlap && yOverlap);
            }
        }
    }
}
