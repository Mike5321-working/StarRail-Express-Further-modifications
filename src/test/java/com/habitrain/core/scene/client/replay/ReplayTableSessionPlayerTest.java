package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayBlockDelta;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.ReplayBoardSettingsPackets;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayFocusPlan;
import com.habitrain.core.scene.replay.ReplayMapViewport;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive verification suite for the interactive 3D replay table session player:
 * linear 0.5x/1x(default)/2x/4x speed, pause/seek/step, end-of-round hold without auto-idle
 * collapse (Defect #8), anti-occlusion ceiling envelope up to 2.4x zoom (Defect #9),
 * side-edge -20 deg deck ray-hit, held-item right-click compatibility & 4-way facing (Defect #10),
 * multi-speaker concurrent spatial audio pool (Defect #11), and seek-safe dynamic block deltas.
 */
class ReplayTableSessionPlayerTest {

    @Test void fortyDenseTrailsReduceOldGeometryAndPreserveSeeksBreaksAndLiveTails() {
        int recent = 960;
        int last = 1000;
        int crowdStride = ReplayActorPainter.historyStride(recent, 40, false);
        int focusedStride = ReplayActorPainter.historyStride(recent, 40, true);
        assertEquals(ReplayActorPainter.historyStride(recent, 1, false), focusedStride);
        int oldPoints = 0;
        int crowdPoints = 0;
        for (int player = 0; player < 40; player++) {
            var samples = new ArrayList<LastMatchReplay.Sample>();
            for (int tick = 0; tick <= last; tick++) {
                int flags = LastMatchReplay.ALIVE | (tick == 483 ? LastMatchReplay.BREAK : 0);
                samples.add(new LastMatchReplay.Sample(tick, LastMatchReplay.Position.quantize(tick % 63, player, tick % 2),
                        (byte) 0, (byte) flags));
            }
            var participant = new LastMatchReplay.Participant(new UUID(1, player), "Runner", "", 0, "", 0,
                    MatchWinFaction.PASSENGER, false, 0, 0, 0, 0, samples);
            var actor = new ReplayBoardModel.Actor(player, participant, new ReplayTrackSampler(samples), 0, false);
            for (int point = 0; point < recent; point = actor.nextVisualTrailPoint(point, recent, last, focusedStride)) oldPoints++;
            var visited = new java.util.BitSet();
            for (int point = 0; point <= last; point = actor.nextVisualTrailPoint(point, recent, last, crowdStride)) visited.set(point);
            crowdPoints += visited.get(0, recent).cardinality();
            assertTrue(visited.get(482) && visited.get(483));
            assertTrue(visited.nextClearBit(recent) > last);
            assertEquals(214, actor.nextVisualTrailPoint(213, 200, 214, crowdStride));
        }
        assertTrue(crowdPoints < oldPoints * 0.30, crowdPoints + " / " + oldPoints);
    }

    @Test
    void trailStridePreservesSegmentEndpointsAndRecentSamplesDuringSeeks() {
        var samples = new ArrayList<LastMatchReplay.Sample>();
        for (int i = 0; i < 400; i++) {
            int flags = i == 75 ? 0 : LastMatchReplay.ALIVE;
            if (i == 214 || i == 215) flags |= LastMatchReplay.BREAK;
            samples.add(new LastMatchReplay.Sample(i * 2, LastMatchReplay.Position.quantize(i / 10.0, 1, 0),
                    (byte) 0, (byte) flags));
        }
        var track = new ReplayTrackSampler(samples);
        var participant = new LastMatchReplay.Participant(UUID.randomUUID(), "Passenger", "", 0, "", 0,
                MatchWinFaction.PASSENGER, false, 0, 0, 0, 0, samples);
        var actor = new ReplayBoardModel.Actor(0, participant, track, 0, false);

        for (int recent : new int[]{300, 214, 39, 360}) {
            int last = recent + 23;
            for (int stride : new int[]{1, 3, 11}) {
                var reference = new ArrayList<Integer>();
                for (int i = 0; i <= last; i++) {
                    if (i == 0 || !track.connects(i) || i >= recent || i % stride == 0 || !track.connects(i + 1)) reference.add(i);
                }
                var actual = new ArrayList<Integer>();
                for (int i = 0; i <= last; i = actor.nextTrailPoint(i, recent, stride)) actual.add(i);
                assertEquals(reference, actual);
            }
        }
    }

    @Test
    void linearSpeedControlsPauseSeekStepHoldAtEndAndResetOnEndSession() {
        UUID round = UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        int duration = 1200; // 60 seconds

        // Fresh session defaults: 1.0x speed, unpaused, killCloseup=false, roofCutaway=true, no follow
        ReplayBoardSessionState s = ReplayBoardSessionState.fresh(round, false, 100L);
        assertEquals(1.0f, s.speed());
        assertFalse(s.paused());
        assertFalse(s.killCloseupEnabled());
        assertTrue(s.roofCutaway());
        assertNull(s.followPlayerUuid());
        assertTrue(s.active());
        assertFalse(s.endedToIdle());

        // Advances linearly at 1.0x
        assertEquals(0.0, s.currentReplayTick(100L, 0.0f, duration), 1e-6);
        assertEquals(50.5, s.currentReplayTick(150L, 0.5f, duration), 1e-6);

        // Switch to 2.0x at gameTick=150 (currentReplayTick=50) without timeline jump
        s = s.applyControl(ReplayBoardSessionState.Action.SET_SPEED, 150L, duration, 0, 2.0f, null, false);
        assertEquals(2.0f, s.speed());
        assertEquals(50.0, s.currentReplayTick(150L, 0.0f, duration), 1e-6);
        assertEquals(150.0, s.currentReplayTick(200L, 0.0f, duration), 1e-6);

        // Switch to 0.5x at gameTick=200 (currentReplayTick=150)
        s = s.applyControl(ReplayBoardSessionState.Action.SET_SPEED, 200L, duration, 0, 0.5f, null, false);
        assertEquals(0.5f, s.speed());
        assertEquals(170.0, s.currentReplayTick(240L, 0.0f, duration), 1e-6);

        // Switch to 4.0x and test pause -> tick freezes cleanly
        s = s.applyControl(ReplayBoardSessionState.Action.SET_SPEED, 240L, duration, 0, 4.0f, null, false);
        assertEquals(4.0f, s.speed());
        assertEquals(250.0, s.currentReplayTick(260L, 0.0f, duration), 1e-6);

        s = s.applyControl(ReplayBoardSessionState.Action.PAUSE, 260L, duration, 0, 1.0f, null, false);
        assertTrue(s.paused());
        assertEquals(250.0, s.currentReplayTick(500L, 0.5f, duration), 1e-6);

        // Arbitrary seek & +/-10s step (200 ticks) while paused
        s = s.applyControl(ReplayBoardSessionState.Action.SEEK, 500L, duration, 600.0, 1.0f, null, false);
        assertEquals(600.0, s.currentReplayTick(520L, 0.0f, duration), 1e-6);
        s = s.applyControl(ReplayBoardSessionState.Action.STEP, 520L, duration, -200.0, 1.0f, null, false);
        assertEquals(400.0, s.currentReplayTick(520L, 0.0f, duration), 1e-6);
        s = s.applyControl(ReplayBoardSessionState.Action.STEP, 520L, duration, 200.0, 1.0f, null, false);
        assertEquals(600.0, s.currentReplayTick(520L, 0.0f, duration), 1e-6);

        // Set ephemeral follow / closeup / roof toggles
        s = s.applyControl(ReplayBoardSessionState.Action.SET_FOLLOW, 520L, duration, 0, 1.0f, playerA, false);
        s = s.applyControl(ReplayBoardSessionState.Action.SET_KILL_CLOSEUP, 520L, duration, 0, 1.0f, null, true);
        s = s.applyControl(ReplayBoardSessionState.Action.SET_ROOF_CUTAWAY, 520L, duration, 0, 1.0f, null, false);
        assertEquals(playerA, s.followPlayerUuid());
        assertTrue(s.killCloseupEnabled());
        assertFalse(s.roofCutaway());

        // Resume at 4.0x and advance well past match end -> holds at final frame, NEVER auto-folds to idle (Defect #8)
        s = s.applyControl(ReplayBoardSessionState.Action.PLAY, 520L, duration, 0, 1.0f, null, false);
        assertFalse(s.paused());
        assertEquals(duration, s.currentReplayTick(2000L, 0.0f, duration), 1e-6);
        assertTrue(s.isFinishedHold(2000L, 0.0f, duration));
        assertTrue(s.active(), "Reached end of match must stay active in final review hold");
        assertFalse(s.endedToIdle());

        // Linear stage choreography at end stays fully built & visible (no idle sculpture takeover)
        var holdStage = ReplayStagePlan.linearStage(duration, duration, 1.0, 1.0, 0.0);
        var holdHandoff = ReplayStagePlan.linearHandoff(1.0, 0.0);
        assertEquals(1.0, holdStage.scan(), 1e-9);
        assertEquals(1.0, holdStage.actors(), 1e-9);
        assertEquals(0.0, holdStage.mapSink(), 1e-9);
        assertEquals(0.0, holdStage.honors(), 1e-9);
        assertTrue(holdStage.history() >= 0.99);
        assertEquals(ReplayStagePlan.REPLAY, holdHandoff);

        // Spectators can still seek backwards out of final hold
        s = s.applyControl(ReplayBoardSessionState.Action.SEEK, 2000L, duration, 300.0, 1.0f, null, false);
        assertFalse(s.isFinishedHold(2000L, 0.0f, duration));
        assertEquals(300.0, s.currentReplayTick(2000L, 0.0f, duration), 1e-6);

        // Explicit END_TO_IDLE resets all session-only preferences cleanly
        ReplayBoardSessionState ended = s.applyControl(ReplayBoardSessionState.Action.END_TO_IDLE, 2010L, duration, 0, 1.0f, null, false);
        assertTrue(ended.endedToIdle());
        assertFalse(ended.active());
        assertEquals(1.0f, ended.speed());
        assertNull(ended.followPlayerUuid());
        assertFalse(ended.killCloseupEnabled());
        assertTrue(ended.roofCutaway());

        // Smooth exit handoff only activates when exitProgress > 0
        var exitEndHandoff = ReplayStagePlan.linearHandoff(1.0, 1.0);
        var exitEndStage = ReplayStagePlan.linearStage(duration, duration, 1.0, 1.0, 1.0);
        assertEquals(1.0, exitEndHandoff.idle(), 1e-9);
        assertEquals(0.0, exitEndStage.scan(), 1e-9);
        assertEquals(0.0, exitEndStage.actors(), 1e-9);

        // Wire & transient sync tag round-trips
        CompoundTag syncTag = s.writeTransientSyncTag();
        assertEquals(s, ReplayBoardSessionState.readTransientSyncTag(syncTag));
        // Persistent ReplayBoardSettings NBT must never contain session keys
        CompoundTag persistentDisplay = ReplayBoardSettings.DEFAULT.save();
        assertFalse(persistentDisplay.contains("Session"));
        assertFalse(persistentDisplay.contains("RoundId"));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var ctrl = ReplayPackets.BoardSessionControlC2S.setFollow(new BlockPos(3, 64, -7), playerA);
            ReplayPackets.BoardSessionControlC2S.CODEC.encode(buf, ctrl);
            assertEquals(ctrl, ReplayPackets.BoardSessionControlC2S.CODEC.decode(buf));
            buf.clear();

            var sync = new ReplayPackets.BoardSessionSyncS2C(new BlockPos(3, 64, -7), s);
            ReplayPackets.BoardSessionSyncS2C.CODEC.encode(buf, sync);
            assertEquals(sync, ReplayPackets.BoardSessionSyncS2C.CODEC.decode(buf));
            buf.clear();

            var share = new ReplayPackets.ShareSessionC2S(new BlockPos(3, 64, -7), round, 412.5, 2.0f, true, playerA, false, true);
            ReplayPackets.ShareSessionC2S.CODEC.encode(buf, share);
            assertEquals(share, ReplayPackets.ShareSessionC2S.CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void killCloseupDefaultsOffFollowTransformAlignsForwardAndDiagonalClipStaysInsideTray() {
        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        var kPos = LastMatchReplay.Position.quantize(10, 2, 8);
        var vPos = LastMatchReplay.Position.quantize(12, 2, 8);
        var kill = new LastMatchReplay.Node(1, 200, LastMatchReplay.Kind.KILL, killer, victim, kPos, vPos, "starrailexpress:knife", 0);
        var replay = new LastMatchReplay(UUID.randomUUID(), 0, 600, "Station", "murder", "", MatchWinKind.KILLER,
                "", "", "", 0, new SceneBounds(0, 0, 0, 60, 16, 24), null,
                List.of(new LastMatchReplay.Participant(killer, "K", "starrailexpress:killer", 0xFFFF0000,
                        "starrailexpress:killer", 0xFFFF0000, MatchWinFaction.KILLER, true, 30, 1, 10, 1, List.of())),
                List.of(kill), List.of(), killer, 1, false);
        ReplayFocusPlan focus = new ReplayFocusPlan(replay, new ReplayTimeline(replay));

        // Kill close-up disabled -> strictly UNFOCUSED right on top of the kill tick
        assertEquals(ReplayFocusPlan.UNFOCUSED, focus.sampleLinear(200.0, 1.0, false));
        assertEquals(ReplayFocusPlan.UNFOCUSED, focus.sampleLinear(208.0, 1.0, false));
        // Kill close-up enabled -> zooms in smoothly above 1.0x
        var active = focus.sampleLinear(208.0, 1.0, true);
        assertTrue(active.pose().zoom() > 1.2 && active.pose().zoom() <= ReplayFocusPlan.MAX_ZOOM + 0.35);

        // Over-the-shoulder follow transform aligns player heading along -Z into the tray
        for (float yawDeg : new float[]{0f, 45f, 90f, 135f, 180f, 270f, 315f}) {
            float counterYaw = ReplayFocusPlan.followCounterYawDeg(yawDeg, false);
            Matrix4f m = ReplayFocusPlan.applyFollowTransform(new Matrix4f(), 1.5f, 0.2f, -0.8f, counterYaw, 1.5f, 0.5f);
            // Minecraft forward = (-sin(yaw), 0, +cos(yaw))
            float fx = (float) -Math.sin(Math.toRadians(yawDeg));
            float fz = (float) Math.cos(Math.toRadians(yawDeg));
            Vector3f dir = m.transformDirection(fx, 0f, fz, new Vector3f()).normalize();
            assertEquals(0f, dir.x, 1e-4f, "yaw=" + yawDeg);
            assertEquals(-1f, dir.z, 1e-4f, "Followed player forward should point into -Z at yaw=" + yawDeg);
        }
        assertEquals(20f, ReplayFocusPlan.shortestAngleDeltaDeg(350f, 10f), 1e-4f);
        assertEquals(-20f, ReplayFocusPlan.shortestAngleDeltaDeg(10f, 350f), 1e-4f);

        // Diagonal non-cardinal rotation clip bounds never protrude past tray inner rim
        float innerHw = 3.1f, innerHd = 3.1f;
        Matrix4f sourceToTray = new Matrix4f()
                .rotateY((float) Math.toRadians(45f))
                .scale(0.12f)
                .translate(-20f, 0f, -15f);
        ReplayMapViewport.Bounds safe = ReplayBoardRenderer.safeSourceBounds(sourceToTray, innerHw, innerHd, true);
        float[][] corners = {
                {safe.minX(), safe.minZ()}, {safe.minX(), safe.maxZ()},
                {safe.maxX(), safe.minZ()}, {safe.maxX(), safe.maxZ()}
        };
        for (float[] c : corners) {
            Vector3f trayPt = sourceToTray.transformPosition(c[0], 0f, c[1], new Vector3f());
            assertTrue(Math.abs(trayPt.x) <= innerHw + 1e-4f && Math.abs(trayPt.z) <= innerHd + 1e-4f,
                    "Corner protruded outside tray rim: " + trayPt);
        }
    }

    @Test
    void antiOcclusionCeilingEnvelopeClearsTallBuildingsAndMaxFocusZoom() {
        float mapBase = ReplayBoardSite.MAP_BASE;
        float clearance = ReplayStagePlan.DEFAULT_FEED_CLEARANCE;

        // Single-deck train at 1.0x zoom
        float singleDeck = ReplayStagePlan.effectiveFeedTop(mapBase, 0.9f, 10.0, 0.08f, 1.0, clearance);
        assertTrue(singleDeck >= mapBase + 0.9f + clearance - 1e-5f);

        // Tall multi-story station where actual mesh ceiling (38 blocks * 0.08 = 3.04) exceeds nominal mapH (1.2)
        float tallAt1x = ReplayStagePlan.effectiveFeedTop(mapBase, 1.2f, 38.0, 0.08f, 1.0, clearance);
        assertEquals(mapBase + 38.0f * 0.08f + clearance, tallAt1x, 1e-4f);

        // At 2.4x focus zoom (Defect #9 boundary), feed height must strictly clear scaled ceiling + safety headroom
        float tallAtMaxZoom = ReplayStagePlan.effectiveFeedTop(mapBase, 1.2f, 38.0, 0.08f, ReplayFocusPlan.MAX_ZOOM, clearance);
        float highestVertexAtZoom = mapBase + (float) (38.0 * 0.08 * ReplayFocusPlan.MAX_ZOOM);
        assertTrue(tallAtMaxZoom >= highestVertexAtZoom + clearance - 1e-4f,
                "Feed top (" + tallAtMaxZoom + ") must clear 2.4x scaled ceiling (" + highestVertexAtZoom + ")");
        assertTrue(tallAtMaxZoom > tallAt1x * 2.0f);
    }

    @Test
    void sideEdgeTiltedDeckRayHitHeldItemCompatibilityAndFourWayFacingPackets() throws Exception {
        // Side 0 tilted -20 deg plaque plane
        Matrix4f board = new Matrix4f();
        Matrix4f plaque = ReplayTrayPainter.plaqueFrameMatrix(board, 3.425f, 3.425f, 0);
        Vector3f origin = plaque.transformPosition(0f, 0f, 0f, new Vector3f());
        Vector3f normal = plaque.transformDirection(0f, 0f, 1f, new Vector3f()).normalize();
        Vector3f targetTimeline = plaque.transformPosition(0.61f, -0.06f, 0f, new Vector3f());

        // Standing viewer in front of side 0 looking straight at (0.61, -0.06) on timeline bar
        Vec3 cam = Vec3.ZERO;
        Vec3 eye = new Vec3(origin.x + normal.x * 2.2, origin.y + normal.y * 2.2, origin.z + normal.z * 2.2);
        Vec3 dir = new Vec3(targetTimeline.x - eye.x, targetTimeline.y - eye.y, targetTimeline.z - eye.z).normalize();

        ReplayBoardButtons.PlaneHit hit = ReplayBoardButtons.hitPlaquePlane(
                eye, dir, plaque, cam,
                ReplayBoardButtons.TIMELINE_X0, ReplayBoardButtons.TIMELINE_Y0,
                ReplayBoardButtons.TIMELINE_X1, ReplayBoardButtons.TIMELINE_Y1, 10.0);
        assertNotNull(hit, "Front ray must hit tilted -20 deg side-edge timeline");
        assertEquals(0.61f, hit.localX(), 1e-3f);
        assertEquals(-0.06f, hit.localY(), 1e-3f);

        // Back-face ray from behind the plaque must be rejected
        Vec3 backEye = new Vec3(origin.x - normal.x * 2.0, origin.y - normal.y * 2.0, origin.z - normal.z * 2.0);
        Vec3 backDir = new Vec3(origin.x - backEye.x, origin.y - backEye.y, origin.z - backEye.z).normalize();
        assertNull(ReplayBoardButtons.hitPlaquePlane(
                backEye, backDir, plaque, cam, -2f, -2f, 2f, 2f, 10.0));

        // 4-way facing settings packets round-trip all horizontal facings and reject vertical ones (Defect #10)
        ResourceLocation dim = ResourceLocation.withDefaultNamespace("overworld");
        BlockPos pos = new BlockPos(12, 70, -8);
        for (Direction dirFacing : Direction.Plane.HORIZONTAL) {
            var save = new ReplayBoardSettingsPackets.Save(dim, pos, ReplayBoardSettings.DEFAULT, dirFacing);
            var checked = new ReplayBoardSettingsPackets.CheckedSave(UUID.randomUUID(), ReplayBoardSettings.DEFAULT,
                    dirFacing.getClockWise(), save);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ReplayBoardSettingsPackets.Save.CODEC.encode(buf, save);
                assertEquals(save, ReplayBoardSettingsPackets.Save.CODEC.decode(buf));
                buf.clear();
                ReplayBoardSettingsPackets.CheckedSave.CODEC.encode(buf, checked);
                assertEquals(checked, ReplayBoardSettingsPackets.CheckedSave.CODEC.decode(buf));
            } finally {
                buf.release();
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayBoardSettingsPackets.Save(dim, pos, ReplayBoardSettings.DEFAULT, Direction.UP));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayBoardSettingsPackets.Save(dim, pos, ReplayBoardSettings.DEFAULT, Direction.DOWN));

        // Held-item right-click regression guard (Defect #10): ReplayBoardLocalState must no longer call getMainHandItem
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("com/habitrain/core/scene/client/replay/ReplayBoardLocalState.class")) {
            assertNotNull(stream);
            String cp = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(cp.contains("getMainHandItem"),
                    "ReplayBoardLocalState must not pass/ignore right-click when holding an item");
        }
    }

    @Test
    void multiChannelConcurrentVoicePoolNeverTruncatesUnderCapProtectsSelfAndTimeStretchesCleanly() {
        assertEquals(12, ReplayAudioPlaybackController.MAX_CONCURRENT_VOICES);

        // Under capacity (11 active speakers): incoming 12th speaker is accepted (-2) without cutting anyone (Defect #11)
        float[] gains11 = new float[11];
        boolean[] self11 = new boolean[11];
        for (int i = 0; i < 11; i++) gains11[i] = 0.8f;
        assertEquals(-2, ReplayAudioPlaybackController.selectVoiceEvictionIndex(gains11, self11, 0.25f, false, 12));

        // At full capacity (12 active, slot 0 is self with lower gain 0.2f, slot 5 is weakest peer 0.3f):
        // Incoming peer (0.6f) evicts slot 5, NEVER self slot 0!
        float[] gains12 = {0.2f, 0.8f, 0.7f, 0.9f, 0.65f, 0.3f, 0.75f, 0.85f, 0.55f, 0.50f, 0.95f, 0.45f};
        boolean[] self12 = new boolean[12];
        self12[0] = true;
        assertEquals(5, ReplayAudioPlaybackController.selectVoiceEvictionIndex(gains12, self12, 0.60f, false, 12));

        // Incoming peer quieter than every active peer (0.25f <= 0.30f) is dropped (-1)
        assertEquals(-1, ReplayAudioPlaybackController.selectVoiceEvictionIndex(gains12, self12, 0.25f, false, 12));

        // Incoming self voice always gets a slot even if quieter than active peers
        assertEquals(5, ReplayAudioPlaybackController.selectVoiceEvictionIndex(gains12, self12, 0.10f, true, 12));

        // WSOLA PCM slice builder across 0.5x / 1.0x / 2.0x / 4.0x produces expected duration ratios
        short[] tone24k = new short[ReplayVoiceData.SAMPLE_RATE]; // 1.0 s @ 24 kHz
        for (int i = 0; i < tone24k.length; i++) {
            tone24k[i] = (short) Math.round(14_000 * Math.sin(2.0 * Math.PI * 360.0 * i / ReplayVoiceData.SAMPLE_RATE));
        }
        var utt = new ReplayVoiceData.VoiceUtterance(UUID.randomUUID(), 0L, tone24k.length, 48f,
                ReplayVoiceData.ChannelMode.NORMAL_OPEN, null, ReplayVoiceData.encodeAdpcm(tone24k), 0.4f);

        byte[] pcm1x = ReplayAudioPlaybackController.buildUtterancePcm48k(utt, 0.0, 2.0, 1.0);
        byte[] pcm2x = ReplayAudioPlaybackController.buildUtterancePcm48k(utt, 0.0, 2.0, 2.0);
        byte[] pcm05x = ReplayAudioPlaybackController.buildUtterancePcm48k(utt, 0.0, 2.0, 0.5);
        byte[] pcm4x = ReplayAudioPlaybackController.buildUtterancePcm48k(utt, 0.0, 2.0, 4.0);

        assertEquals(48_000 * 2, pcm1x.length);
        assertEquals(pcm1x.length / 2.0, pcm2x.length, 1024);
        assertEquals(pcm1x.length * 2.0, pcm05x.length, 2048);
        assertEquals(pcm1x.length / 4.0, pcm4x.length, 1024);
    }

    @Test
    void dynamicWorldBlockDeltasResolveAccuratelyAcrossForwardAndBackwardSeek() {
        SceneBounds bounds = new SceneBounds(0, 0, 0, 16, 16, 16);
        short[] cells = new short[4096];
        int doorCell = (2 << 8) | (3 << 4) | 4; // (x=4, y=2, z=3)
        cells[doorCell] = 1;
        var palette = List.of(SceneAssetCodec.AIR_BLOCK_ID, "starrailexpress:small_door[facing=north,half=lower,open=false]");
        var section = new SceneAssetCodec.SectionData(0, 0, 0, palette, cells, null, null);
        var asset = new SceneAssetCodec.AssetData(1, "minecraft:overworld", bounds, "b".repeat(64), List.of(section));

        List<ReplayBlockDelta> deltas = List.of(
                new ReplayBlockDelta(120, 4, 2, 3, "minecraft:stone", ReplayBlockDelta.REASON_WALL_PLACE),
                new ReplayBlockDelta(40, 5, 2, 3, "minecraft:glass", ReplayBlockDelta.REASON_WALL_PLACE),
                new ReplayBlockDelta(50, 4, 2, 3, "starrailexpress:small_door[facing=north,half=lower,open=true]", ReplayBlockDelta.REASON_DOOR_TOGGLE),
                new ReplayBlockDelta(120, 4, 2, 3, "minecraft:air", ReplayBlockDelta.REASON_WALL_BREAK)
        );
        ReplayDioramaMesh.DynamicWorld dyn = ReplayDioramaMesh.prepareDynamic(asset, bounds, deltas);
        assertNotNull(dyn);
        assertTrue(dyn.hasDynamics());
        // Source asset must remain untouched while staticAsset masks out the dynamic cell
        assertEquals(1, section.blockIndices[doorCell]);
        assertEquals(0, dyn.staticAsset().sections.getFirst().blockIndices[doorCell]);

        long key = deltas.getFirst().posKey();
        assertEquals("starrailexpress:small_door[facing=north,half=lower,open=false]", dyn.resolveAt(10).get(key).stateString());
        assertEquals("starrailexpress:small_door[facing=north,half=lower,open=true]", dyn.resolveAt(80).get(key).stateString());
        assertEquals("minecraft:air", dyn.resolveAt(150).get(key).stateString());
        long wall = deltas.get(1).posKey();
        assertEquals("minecraft:glass", dyn.resolveAt(150).get(wall).stateString());
        assertEquals("minecraft:air", dyn.resolveAt(39).get(wall).stateString());
        assertEquals("minecraft:glass", dyn.resolveAt(40).get(wall).stateString());
        // Seek backward to tick 60 and tick 0 -> restores exact historical states
        assertEquals("starrailexpress:small_door[facing=north,half=lower,open=true]", dyn.resolveAt(60).get(key).stateString());
        assertEquals("starrailexpress:small_door[facing=north,half=lower,open=false]", dyn.resolveAt(0).get(key).stateString());
    }

    @Test
    void matchReplayClientApiExposesCrossModNearbyTableReflectionContract() throws Exception {
        Class<?> api = com.habitrain.core.api.client.replay.MatchReplayClientApi.class;
        assertNotNull(api.getMethod("open", net.minecraft.client.gui.screens.Screen.class, String.class, boolean.class));
        assertNotNull(api.getMethod("nearbyTableSnapshot"));
        assertNotNull(api.getMethod("controlNearbyTable", String.class, double.class, String.class, boolean.class));
        assertNotNull(api.getMethod("openNearbyTableExport", net.minecraft.client.gui.screens.Screen.class));
        assertNotNull(api.getMethod("openNearbyTablePreview", net.minecraft.client.gui.screens.Screen.class));
    }
}
