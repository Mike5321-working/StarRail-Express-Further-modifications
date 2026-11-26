package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayEntityTrack;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.match.replay.ReplaySkillEvent;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.replay.ReplayFanCutaway;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.replay.ReplayWsolaTimeStretch;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReplayPerformanceOptimizationsTest {

    @Test
    void cancellingPendingVoiceDoesNotPoisonStartedUtterancesAndSkipStaysAligned() throws Exception {
        ReplayAudioPlaybackController.clearPcmCache();
        var pool = new ReplayAudioPlaybackController.PlaybackPool();
        UUID speaker = UUID.randomUUID();
        short[] tone = new short[ReplayVoiceData.SAMPLE_RATE];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (Math.sin(i * 0.08) * 12000.0);
        }
        var utterance = new ReplayVoiceData.VoiceUtterance(speaker, 1000L, tone.length, 32.0f,
                ReplayVoiceData.ChannelMode.NORMAL_OPEN, null, ReplayVoiceData.encodeAdpcm(tone));

        CountDownLatch gate = new CountDownLatch(1);
        assertTrue(ReplayAudioPlaybackController.prepareVoice(pool, utterance, speaker, 1.0f, false,
                Vec3.ZERO, true, 20.0, 40.0, 1000L, () -> {
                    gate.await(2, TimeUnit.SECONDS);
                    return ReplayAudioPlaybackController.buildUtterancePcm48k(utterance, 0.0, 1.0, 0.5);
                }));
        assertTrue(pool.isPlayingKey(utterance));
        pool.clearPendingVoices();
        gate.countDown();

        assertFalse(pool.isPlayingKey(utterance));
        assertFalse(pool.startedUtterances.containsKey(utterance),
                "Cancelled pending utterance must remain eligible to play once viewer returns");

        assertEquals(0, ReplayAudioPlaybackController.skipBytesForDelay(0.0, 1.0f));
        assertEquals(0, ReplayAudioPlaybackController.skipBytesForDelay(-5.0, 1.0f));
        assertEquals(ReplayVoiceData.VOICECHAT_SAMPLE_RATE * 2,
                ReplayAudioPlaybackController.skipBytesForDelay(20.0, 1.0f));
        assertEquals(ReplayVoiceData.VOICECHAT_SAMPLE_RATE * 4,
                ReplayAudioPlaybackController.skipBytesForDelay(20.0, 0.5f));
        assertEquals(ReplayVoiceData.VOICECHAT_SAMPLE_RATE,
                ReplayAudioPlaybackController.skipBytesForDelay(20.0, 2.0f));
        assertEquals(0, ReplayAudioPlaybackController.skipBytesForDelay(1.37, 0.5f) % 2);

        // Verify LRU cache returns identical PCM reference and clears cleanly
        byte[] first = ReplayAudioPlaybackController.buildUtterancePcm48k(utterance, 0.0, 0.8, 0.5);
        byte[] second = ReplayAudioPlaybackController.buildUtterancePcm48k(utterance, 0.0, 0.8, 0.5);
        assertTrue(first.length > 0);
        assertSame(first, second);
        ReplayAudioPlaybackController.clearPcmCache();
        byte[] afterClear = ReplayAudioPlaybackController.buildUtterancePcm48k(utterance, 0.0, 0.8, 0.5);
        assertNotSame(first, afterClear);
        assertArrayEquals(first, afterClear);
    }

    @Test
    void pcmByteAudioStreamAppliesSmoothResumeFadeInAtNonZeroOffset() throws Exception {
        int totalSamples = 4096;
        byte[] pcm = new byte[totalSamples * 2];
        for (int i = 0; i < totalSamples; i++) {
            short v = 10_000;
            pcm[i * 2] = (byte) (v & 0xFF);
            pcm[i * 2 + 1] = (byte) ((v >>> 8) & 0xFF);
        }
        int offset = 400; // even byte offset
        try (var stream = new ReplayAudioPlaybackController.PcmByteAudioStream(pcm, offset)) {
            ByteBuffer chunk = stream.read(2048);
            assertEquals(2048, chunk.remaining());
            short firstSample = (short) ((chunk.get(0) & 0xFF) | (chunk.get(1) << 8));
            int midIdx = (ReplayAudioPlaybackController.PcmByteAudioStream.RESUME_FADE_BYTES / 2) & ~1;
            short midSample = (short) ((chunk.get(midIdx) & 0xFF) | (chunk.get(midIdx + 1) << 8));
            int afterIdx = ReplayAudioPlaybackController.PcmByteAudioStream.RESUME_FADE_BYTES + 20;
            short afterFade = (short) ((chunk.get(afterIdx) & 0xFF) | (chunk.get(afterIdx + 1) << 8));

            assertEquals(0, firstSample);
            assertTrue(midSample > 3000 && midSample < 7000, "Mid-fade sample should ramp linearly: " + midSample);
            assertEquals(10_000, afterFade);
        }
    }

    @Test
    void historyCacheToleratesSmallAngleChangesAndInvalidatesOnLargeOrbitAroundTable() {
        UUID actorId = UUID.randomUUID();
        var model = minimalModel(actorId, List.of(), List.of());
        var basis = new Matrix4f();
        var view = new ReplayBoardHistoryCache.View(model, 100.0, basis, basis, 1.0f, 1.0f,
                2.0f, 2.0f, true, 1.0, ReplayFullPlayerRenderer.HighlightSpec.NONE);
        var cache = new ReplayBoardHistoryCache();
        long t0 = 1_000_000_000L;
        var northView = new Vector3f(0f, 0.5f, 1.0f).normalize();
        var slightlyShifted = new Vector3f(0.12f, 0.5f, 1.0f).normalize(); // ~6.1 deg shift (< 22.5 deg)
        var southView = new Vector3f(0f, 0.5f, -1.0f).normalize();         // > 120 deg shift

        assertFalse(cache.prepare(view, northView, t0));
        assertTrue(cache.prepare(view, northView, t0 + 250_000_000L));
        // Small player walk/strafe around the same side keeps cache hot
        assertTrue(cache.prepare(view, slightlyShifted, t0 + 300_000_000L));
        // Walking around to the opposite side invalidates stale camera-facing ribbons
        assertFalse(cache.prepare(view, southView, t0 + 350_000_000L));
        // Settles cleanly at the new side after 200ms
        assertTrue(cache.prepare(view, southView, t0 + 600_000_000L));
    }

    @Test
    void viewportBuffersBatchesConsecutiveSameLayerAndFlushesOnLayerSwitchOrMultiLayer() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RenderType typeA = RenderType.create("test_vp_a", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder().createCompositeState(false));
        RenderType typeB = RenderType.create("test_vp_b", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder().createCompositeState(false));

        List<String> flushes = new ArrayList<>();
        var collectorA = new CountingVertexConsumer("A", flushes);
        var collectorB = new CountingVertexConsumer("B", flushes);
        var vp = new ReplayFrame.ViewportBuffers(layer -> new ReplayClippedConsumer(
                () -> layer == typeA ? collectorA : collectorB, new Matrix4f(), 2.0f, 2.0f, 4));

        // Two consecutive single-layer draws on typeA should stay batched without flushing between them
        vp.beginDraw();
        emitQuad(vp.getBuffer(typeA), 0f);
        vp.finishDraw();
        assertTrue(flushes.isEmpty(), "First single-layer quad remains open for consecutive same-layer batching");

        vp.beginDraw();
        emitQuad(vp.getBuffer(typeA), 0.2f);
        vp.finishDraw();
        assertEquals(List.of("A"), flushes, "Second quad flushes only the first quad cleanly into typeA");

        // Switching to typeB flushes typeA's trailing quad BEFORE typeB emits anything
        vp.beginDraw();
        VertexConsumer b = vp.getBuffer(typeB);
        assertEquals(List.of("A", "A"), flushes);
        emitQuad(b, 0.4f);
        vp.finishDraw();
        assertEquals(List.of("A", "A"), flushes);

        // Multi-layer draw (typeB + typeA) flushes immediately on finishDraw in exact layer order
        vp.beginDraw();
        VertexConsumer multiB = vp.getBuffer(typeB);
        VertexConsumer multiA = vp.getBuffer(typeA);
        emitQuad(multiB, 0.5f);
        emitQuad(multiA, 0.6f);
        vp.finishDraw();
        assertEquals(List.of("A", "A", "B", "B", "A"), flushes);

        vp.close();
        assertEquals(12, collectorA.vertices);
        assertEquals(8, collectorB.vertices);
    }

    @Test
    void skillIndexAndEntityPainterFastPathsPreserveExactResults() {
        UUID actorId = UUID.randomUUID();
        var skills = List.of(
                new ReplaySkillEvent(1, 20, "test:other_skill", "Other", ReplaySkillEvent.CAT_ROLE_SKILL,
                        actorId, null, "passenger", 0xFFFFFFFF, null, LastMatchReplay.Position.quantize(5, 1, 5), 40, "", false),
                new ReplaySkillEvent(2, 100, "tacz:flashbang", "Flashbang", ReplaySkillEvent.CAT_ITEM_USE,
                        actorId, null, "killer", 0xFFFFFFFF, null, LastMatchReplay.Position.quantize(5, 1, 5), 55, "", false),
                new ReplaySkillEvent(3, 300, "test:late_skill", "Late", ReplaySkillEvent.CAT_ROLE_SKILL,
                        actorId, null, "passenger", 0xFFFFFFFF, null, LastMatchReplay.Position.quantize(5, 1, 5), 40, "", false)
        );

        UUID corpseId = UUID.randomUUID();
        UUID grenadeId = UUID.randomUUID();
        var entities = List.of(
                new ReplayEntityTrack(corpseId, ReplayEntityTrack.EntityKind.CORPSE, "corpse", null, actorId, 0.5f, List.of(
                        new ReplayEntityTrack.EntitySample(50, LastMatchReplay.Position.quantize(2, 1, 2), (byte) 0, (byte) 0,
                                ReplayEntityTrack.FLAG_ACTIVE)
                )),
                new ReplayEntityTrack(grenadeId, ReplayEntityTrack.EntityKind.GRENADE, "tacz:frag", actorId, null, 0.3f, List.of(
                        new ReplayEntityTrack.EntitySample(60, LastMatchReplay.Position.quantize(0, 1, 0), (byte) 0, (byte) 0,
                                ReplayEntityTrack.FLAG_ACTIVE),
                        new ReplayEntityTrack.EntitySample(80, LastMatchReplay.Position.quantize(4, 1, 0), (byte) 0, (byte) 0,
                                ReplayEntityTrack.FLAG_ACTIVE),
                        new ReplayEntityTrack.EntitySample(81, LastMatchReplay.Position.quantize(4, 1, 0), (byte) 0, (byte) 0, 0)
                ))
        );

        var model = minimalModel(actorId, skills, entities);

        // Flashbang whiteout indexed lookup
        assertEquals(0f, ReplaySkillEffectRegistry.flashIntensityFor(actorId, model, 90.0), 1e-6f);
        float peak = ReplaySkillEffectRegistry.flashIntensityFor(actorId, model, 103.0);
        assertTrue(peak > 0.9f, "Expected strong flashbang peak right at 103 ticks, got " + peak);
        assertEquals(0f, ReplaySkillEffectRegistry.flashIntensityFor(actorId, model, 160.0), 1e-6f);

        // Entity painter O(1) pre-spawn, mid-interpolation, persistent tail (corpse), and despawned tail (grenade)
        assertTrue(ReplayEntityPainter.collectActive(model, 40.0, false).isEmpty());
        var at70 = ReplayEntityPainter.collectActive(model, 70.0, false);
        assertEquals(2, at70.size());
        var grenadeAt70 = at70.stream().filter(e -> e.entityId().equals(grenadeId)).findFirst().orElseThrow();
        assertEquals(2.0f, grenadeAt70.x(), 0.05f);

        var at120 = ReplayEntityPainter.collectActive(model, 120.0, false);
        assertEquals(1, at120.size());
        assertEquals(corpseId, at120.getFirst().entityId());
    }

    @Test
    void thirtyThreeChunkBoundsAndDeferredCaptureEncodeCleanly() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        SceneBounds sixteenChunkView = new SceneBounds(0, 0, 0, 33 * 16, 4 * 16, 33 * 16);
        assertTrue(ReplayMapManifest.supportsBounds(sixteenChunkView));
        assertTrue(sixteenChunkView.totalSections() <= ReplayMapManifest.MAX_SECTIONS);

        // Missing-sections packet codec must accept indices above legacy 2048 up to 4355 (33x33x4 - 1).
        BitSet missing = new BitSet();
        missing.set(0);
        missing.set(2048);
        missing.set(4355);
        var req = new ReplayPackets.ReplayMissingSectionsC2S(BlockPos.ZERO, UUID.randomUUID(), missing);
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ReplayPackets.ReplayMissingSectionsC2S.CODEC.encode(buf, req);
            var decoded = ReplayPackets.ReplayMissingSectionsC2S.CODEC.decode(buf);
            assertEquals(missing, decoded.missing());
        } finally {
            buf.release();
        }

        // Deferred lightweight capture path must produce identical manifest/hash/bytes to synchronous snapshot.
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                states.set(x, 0, z, Blocks.STONE.defaultBlockState());
            }
        }
        SceneBounds clip = new SceneBounds(0, 0, 0, 16, 16, 16);
        byte[] sky = ReplaySectionGeometry.defaultSkyLight();
        byte[] block = new byte[2048];
        var syncSnap = ReplaySectionGeometry.captureFromCopied(0, 0, 0, states, clip, sky, block);
        assertNotNull(syncSnap);
        var syncResult = ReplayMapCapture.encode(
                new ReplayMapCapture.Raw("test_map", "minecraft:overworld", List.of(syncSnap), null), Map.of());
        var deferredResult = ReplayMapCapture.encode(
                new ReplayMapCapture.Raw("test_map", "minecraft:overworld", List.of(), null,
                        List.of(new ReplayMapCapture.CapturedSection(0, 0, 0, states.copy(), sky, block)), clip),
                Map.of());
        assertEquals(syncResult.manifest().hash(), deferredResult.manifest().hash());
        assertEquals(syncResult.manifest().sections(), deferredResult.manifest().sections());
        assertArrayEquals(syncResult.patches().getFirst(), deferredResult.patches().getFirst());
    }

    @Test
    void visualTrailChainCompressesStationaryAndCollinearPointsWhileKeepingCornersAndBreaks() {
        UUID actorId = UUID.randomUUID();
        var bounds = new SceneBounds(0, 0, 0, 64, 16, 64);
        List<LastMatchReplay.Sample> track = new ArrayList<>();
        // 100 ticks stationary at (4, 1, 4)
        for (int t = 0; t < 100; t++) {
            track.add(new LastMatchReplay.Sample(t, LastMatchReplay.Position.quantize(4, 1, 4), (byte) 0, LastMatchReplay.ALIVE));
        }
        // 100 ticks straight line along +X to (44, 1, 4)
        for (int t = 100; t < 200; t++) {
            double x = 4.0 + (t - 100) * 0.4;
            track.add(new LastMatchReplay.Sample(t, LastMatchReplay.Position.quantize(x, 1, 4), (byte) 0, LastMatchReplay.ALIVE));
        }
        // Sharp 90-degree corner along +Z to (44, 1, 44)
        for (int t = 200; t <= 300; t++) {
            double z = 4.0 + (t - 200) * 0.4;
            track.add(new LastMatchReplay.Sample(t, LastMatchReplay.Position.quantize(44, 1, z), (byte) 0, LastMatchReplay.ALIVE));
        }
        var participant = new LastMatchReplay.Participant(actorId, "Runner", "passenger", 0xFF44AAEE,
                "passenger", 0xFF44AAEE, MatchWinFaction.PASSENGER, true, 20, 0, 10, 0, track);
        var replay = new LastMatchReplay(UUID.randomUUID(), 100L, 400, "TestMap", "classic", "PASSENGERS",
                MatchWinKind.INNOCENT, "", "", "", 0xFF44AAEE, bounds, null,
                List.of(participant), List.of(), List.of(), actorId, 0, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                com.habitrain.core.api.match.replay.ReplayGameSoundData.EMPTY);
        var model = new ReplayBoardModel(replay, new ReplayTimeline(replay));
        var actor = model.actors[0];

        int last = actor.track.indexAt(300.0);
        int rawCount = last + 1;
        int visualCount = 0;
        boolean sawCorner = false;
        for (int i = 0; i <= last; i = actor.nextVisualTrailPoint(i, 0, last, 1)) {
            visualCount++;
            if (Math.abs(actor.track.x(i) - 44.0) < 0.5 && Math.abs(actor.track.z(i) - 4.0) < 0.5) {
                sawCorner = true;
            }
        }
        assertTrue(sawCorner, "Simplified visual trail must retain sharp corners");
        assertTrue(visualCount < rawCount / 4,
                "Expected >75% vertex reduction on stationary+straight trail, got " + visualCount + " / " + rawCount);
    }

    @Test
    void clippedConsumerClassifiesBoxesAndClipsRibbonsEquivalently() {
        var rng = new Random(2026);
        for (int trial = 0; trial < 20; trial++) {
            Matrix4f board = new Matrix4f()
                    .translate(rng.nextFloat() * 10 - 5, rng.nextFloat() * 4 - 2, -5 - rng.nextFloat() * 8)
                    .rotateY(rng.nextFloat() * 6.28f)
                    .scale(0.5f + rng.nextFloat() * 3.0f);
            Matrix4f inv = new Matrix4f(board).invert();
            Matrix4f transform = new Matrix4f(board)
                    .translate(rng.nextFloat() * 2 - 1, 0.25f, rng.nextFloat() * 2 - 1)
                    .rotateY(rng.nextFloat() * 6.28f)
                    .scale(0.6f + rng.nextFloat() * 1.4f);
            float hw = 2.0f, hd = 1.5f;
            var out = new CountingVertexConsumer("R", new ArrayList<>());
            try (var clip = new ReplayClippedConsumer(out, inv, hw, hd)) {
                for (int q = 0; q < 20; q++) {
                    float cx = rng.nextFloat() * 6 - 3;
                    float cz = rng.nextFloat() * 6 - 3;
                    float r = 0.05f + rng.nextFloat() * 0.6f;
                    var state = clip.classifyHorizontal(transform, cx, 0.1f, cz, r);
                    float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
                    float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
                    Vector3f tmp = new Vector3f();
                    for (int sx : new int[]{-1, 1}) {
                        for (int sz : new int[]{-1, 1}) {
                            transform.transformPosition(cx + sx * r, 0.1f, cz + sz * r, tmp);
                            inv.transformPosition(tmp);
                            minX = Math.min(minX, tmp.x); maxX = Math.max(maxX, tmp.x);
                            minZ = Math.min(minZ, tmp.z); maxZ = Math.max(maxZ, tmp.z);
                        }
                    }
                    boolean refOutside = maxX < -hw || minX > hw || maxZ < -hd || minZ > hd;
                    boolean refInside = minX > -hw + 0.0001f && maxX < hw - 0.0001f
                            && minZ > -hd + 0.0001f && maxZ < hd - 0.0001f;
                    if (refOutside) assertEquals(ReplayClippedConsumer.ClipState.OUTSIDE, state);
                    else if (refInside) assertEquals(ReplayClippedConsumer.ClipState.INSIDE, state);
                    else assertEquals(ReplayClippedConsumer.ClipState.STRADDLE, state);
                }
            }
        }

        // Ribbon fast path: exterior ribbon emits 0 vertices; interior ribbon writes straight to output; crossing clips cleanly.
        var out = new CountingVertexConsumer("R", new ArrayList<>());
        try (var clip = new ReplayClippedConsumer(out, new Matrix4f(), 1.0f, 1.0f)) {
            float[] outsidePts = {5f, 0f, -5f, 6f, 0f, -5f, 7f, 0f, -5f};
            float[] w = {0.1f, 0.1f, 0.1f};
            int[] c = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
            ReplayDraw.ribbon(clip, outsidePts, 3, w, c);
            assertEquals(0, out.vertices, "Exterior ribbon should emit zero vertices");

            float[] insidePts = {-0.4f, 0.2f, -0.3f, 0.0f, 0.2f, 0.0f, 0.4f, 0.2f, 0.3f};
            ReplayDraw.ribbon(clip, insidePts, 3, w, c);
            assertEquals(8, out.vertices, "Two interior ribbon segments should emit 8 vertices directly");

            float[] crossPts = {-2.0f, 0.2f, 0.0f, 2.0f, 0.2f, 0.0f};
            ReplayDraw.ribbon(clip, crossPts, 2, new float[]{0.1f, 0.1f}, new int[]{0xFFFFFFFF, 0xFFFFFFFF});
            assertTrue(out.vertices > 8 && out.vertices % 4 == 0);
        }
    }

    @Test
    void detailScaleDecaysSublinearlyAndHonorsBlitRectProjectsBounds() {
        assertEquals(1.0, ReplayStagePlan.detailScale(1.0), 1e-6);
        assertEquals(1.0, ReplayStagePlan.detailScale(0.5), 1e-6);
        double giantDetail = ReplayStagePlan.DETAIL_DISTANCE * ReplayStagePlan.detailScale(32.0 / 6.25);
        assertTrue(giantDetail > 50.0 && giantDetail < 60.0, "Giant 32-block detail distance should be ~54.3, got " + giantDetail);

        // Honors sub-rect projection
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 256f);
        int[] centered = ReplayHonorsLayer.projectBlitRect(proj, -1f, -1f, -10f, 1f, 1f, -8f, 1920, 1080, 16);
        assertNotNull(centered);
        assertTrue(centered[0] > 0 && centered[2] < 1920 && centered[1] > 0 && centered[3] < 1080,
                "Distant honors panel should produce a strict sub-rect");
        int[] offscreen = ReplayHonorsLayer.projectBlitRect(proj, 50f, -1f, -10f, 52f, 1f, -8f, 1920, 1080, 16);
        assertNotNull(offscreen);
        assertEquals(0, offscreen[2] - offscreen[0], "Offscreen honors panel should have empty blit rect");
        int[] crossingNear = ReplayHonorsLayer.projectBlitRect(proj, -1f, -1f, -1f, 1f, 1f, 1f, 1920, 1080, 16);
        assertNull(crossingNear, "Box crossing near plane must safely fall back to full-screen blit");
    }

    @Test
    void selectAnchorsIntoMatchesShaderFansAndPrefixWsolaIsFastAndInterruptible() throws Exception {
        var bounds = new SceneBounds(0, 0, 0, 128, 32, 128);
        var cut = new ReplayFanCutaway();
        cut.direction(0.6, 0.8);
        for (int i = 0; i < 24; i++) {
            cut.add(new com.habitrain.core.scene.replay.ReplayTrackSampler.Pose(
                    com.habitrain.core.scene.replay.ReplayTrackSampler.State.ALIVE,
                    i * 5 + 2, 2, (i % 4) * 20 + 10, 0, LastMatchReplay.ALIVE, 0, 0, false), bounds, bounds, 1.5);
        }
        var ref = cut.shaderFans(10, 10, 40, 20, 40);
        float[] copy = new float[ReplayFanCutaway.PACKED_FLOATS];
        int kept = cut.selectAnchorsInto(10, 10, 40, 20, 40, copy);
        assertEquals(ref.count(), kept);
        for (int i = 0; i < kept * 4; i++) {
            assertEquals(ref.anchors()[i], copy[i], 1e-6f);
        }

        // Verify 24kHz native WSOLA produces expected 48kHz stretched length and responds to interrupt
        ReplayAudioPlaybackController.clearPcmCache();
        int sr24 = ReplayVoiceData.SAMPLE_RATE;
        short[] voice24k = new short[sr24 * 3]; // 3 seconds @ 24kHz
        for (int i = 0; i < voice24k.length; i++) {
            voice24k[i] = (short) (Math.sin(2.0 * Math.PI * 220.0 * i / sr24) * 14000.0);
        }
        var utterance = new ReplayVoiceData.VoiceUtterance(UUID.randomUUID(), 0L, voice24k.length, 32f,
                ReplayVoiceData.ChannelMode.NORMAL_OPEN, null, ReplayVoiceData.encodeAdpcm(voice24k));
        long startNs = System.nanoTime();
        byte[] slowPcm = ReplayAudioPlaybackController.buildUtterancePcm48k(utterance, 0.0, 3.0, 0.5);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        // 3s at 0.5x = 6s @ 48kHz * 2 bytes/sample = 576,000 bytes
        assertTrue(Math.abs(slowPcm.length - 576_000) < 4096, "Unexpected 0.5x PCM byte length: " + slowPcm.length);
        assertTrue(elapsedMs < 600, "Single 3s 0.5x native-24k WSOLA should be fast, took " + elapsedMs + " ms");

        // Interrupting a WSOLA stretch must abort cleanly with empty output
        AtomicReference<float[]> interruptedOut = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            interruptedOut.set(ReplayWsolaTimeStretch.stretchMonoFloat(new float[sr24 * 2], sr24, 0.5));
        });
        worker.start();
        worker.join(2000);
        assertNotNull(interruptedOut.get());
        assertEquals(0, interruptedOut.get().length);
    }

    private static ReplayBoardModel minimalModel(UUID actorId, List<ReplaySkillEvent> skills,
                                                 List<ReplayEntityTrack> entities) {
        var bounds = new SceneBounds(0, 0, 0, 32, 16, 32);
        var track = List.of(
                new LastMatchReplay.Sample(0, LastMatchReplay.Position.quantize(5, 1, 5), (byte) 0, (byte) LastMatchReplay.ALIVE),
                new LastMatchReplay.Sample(400, LastMatchReplay.Position.quantize(5, 1, 5), (byte) 0, (byte) LastMatchReplay.ALIVE)
        );
        var participant = new LastMatchReplay.Participant(actorId, "Tester", "passenger", 0xFF44AAEE,
                "passenger", 0xFF44AAEE, MatchWinFaction.PASSENGER, true, 20, 0, 10, 0, track);
        var replay = new LastMatchReplay(UUID.randomUUID(), 100L, 400, "TestMap", "classic", "PASSENGERS",
                MatchWinKind.INNOCENT, "", "", "", 0xFF44AAEE, bounds, null,
                List.of(participant), List.of(), List.of(), actorId, 0, false,
                List.of(), List.of(), List.of(), entities, skills, com.habitrain.core.api.match.replay.ReplayGameSoundData.EMPTY);
        return new ReplayBoardModel(replay, new ReplayTimeline(replay));
    }

    private static void emitQuad(VertexConsumer vc, float x) {
        vc.addVertex(x, 0, 0).setColor(255, 255, 255, 255);
        vc.addVertex(x + 0.1f, 0, 0).setColor(255, 255, 255, 255);
        vc.addVertex(x + 0.1f, 0.1f, 0).setColor(255, 255, 255, 255);
        vc.addVertex(x, 0.1f, 0).setColor(255, 255, 255, 255);
    }

    private static final class CountingVertexConsumer implements VertexConsumer {
        private final String tag;
        private final List<String> flushes;
        int vertices;

        CountingVertexConsumer(String tag, List<String> flushes) {
            this.tag = tag;
            this.flushes = flushes;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (vertices % 4 == 0) flushes.add(tag);
            vertices++;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
