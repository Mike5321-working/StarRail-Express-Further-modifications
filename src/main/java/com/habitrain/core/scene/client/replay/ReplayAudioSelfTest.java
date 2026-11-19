package com.habitrain.core.scene.client.replay;

import com.google.gson.GsonBuilder;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.game.sre.replay.MatchVoiceRecorder;
import com.habitrain.core.game.sre.replay.ReplayBoardService;
import com.habitrain.core.game.sre.replay.ReplayVoicePlugin;
import com.habitrain.core.game.sre.voice.VoiceChatPresence;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.replay.ReplayVideoScript;
import io.wifi.starrailexpress.event.client.OnGameStartedClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import org.jcodec.codecs.aac.ADTSParser;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Automated end-to-end verification harness executed inside a live client launch when
 * {@code -Dhabitrain.replayAudioSelfTest=<reportPath>} is supplied. Completely inert otherwise.
 */
public final class ReplayAudioSelfTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayAudioSelfTest.class);
    private static final String PROP = "habitrain.replayAudioSelfTest";

    private enum Phase {
        WAIT_WORLD,
        SETUP_SERVER,
        WAIT_SYNC_AND_ARCHIVE,
        VERIFY_LIVE_3D_AUDIO,
        EXPORT_MP4,
        EXPORT_AVI,
        CANCEL_EXPORT,
        DONE
    }

    private static boolean initialized;
    private static Path reportPath;
    private static long deadlineNs;
    private static Phase phase = Phase.WAIT_WORLD;

    private static volatile UUID testRoundId;
    private static volatile BlockPos boardPos;
    private static volatile int voiceArchiveBytes;
    private static volatile int opusPacketBytes;
    private static volatile String serverSetupError;

    private static int syncTicks;
    private static int playbackTicks;
    private static boolean observedBoardVoiceActive;
    private static double near440Power;
    private static double far1900Power;
    private static int resolvedModSounds;
    private static Screen lastLoggedScreen;
    private static final Set<Screen> handledScreens = Collections.newSetFromMap(new IdentityHashMap<>());

    private static ReplayVideoExport mp4Export;
    private static ReplayVideoExport aviExport;
    private static ReplayVideoExport cancelledExport;
    private static Set<Path> filesBeforeCancellation;
    private static Path mp4Path;
    private static Path aviPath;
    private static double mp4IntroRms, mp4Beat0Rms, mp4Beat1Rms;
    private static double aviIntroRms, aviBeat0Rms, aviBeat1Rms;
    private static long mp4Size, aviSize;

    private ReplayAudioSelfTest() {}

    public static void initIfRequested() {
        String raw = System.getProperty(PROP);
        if (raw == null || raw.isBlank() || initialized) return;
        initialized = true;
        reportPath = Path.of(raw).toAbsolutePath().normalize();
        deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        LOGGER.info("[ReplayAudioSelfTest] Enabled; report will be written to {}", reportPath);
        ClientTickEvents.END_CLIENT_TICK.register(ReplayAudioSelfTest::onTick);
    }

    private static void onTick(Minecraft mc) {
        if (phase == Phase.DONE) return;
        if (System.nanoTime() - deadlineNs >= 0) {
            fail(mc, "Timed out in phase " + phase);
            return;
        }
        try {
            // Keep integrated singleplayer ticking even when the client window is unattended in background.
            mc.options.pauseOnLostFocus = false;
            if (mc.screen instanceof PauseScreen) mc.setScreen(null);

            switch (phase) {
                case WAIT_WORLD -> tickWaitWorld(mc);
                case SETUP_SERVER -> tickWaitServerSetup(mc);
                case WAIT_SYNC_AND_ARCHIVE -> tickWaitSync(mc);
                case VERIFY_LIVE_3D_AUDIO -> tickVerifyLive3dAudio(mc);
                case EXPORT_MP4 -> tickExportMp4(mc);
                case EXPORT_AVI -> tickExportAvi(mc);
                case CANCEL_EXPORT -> tickCancelExport(mc);
                case DONE -> {}
            }
        } catch (Throwable t) {
            fail(mc, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void tickWaitWorld(Minecraft mc) {
        if (mc.screen != lastLoggedScreen) {
            lastLoggedScreen = mc.screen;
            LOGGER.info("[ReplayAudioSelfTest] WAIT_WORLD screen={} level={} server={} vcApi={}",
                    mc.screen == null ? "null" : mc.screen.getClass().getName(),
                    mc.level != null,
                    mc.getSingleplayerServer() != null,
                    ReplayVoicePlugin.serverApi() != null);
        }
        if (mc.level == null && mc.screen != null && handledScreens.add(mc.screen)) {
            if (mc.screen instanceof ConfirmScreen confirm) {
                for (var child : confirm.children()) {
                    if (child instanceof Button btn) {
                        LOGGER.info("[ReplayAudioSelfTest] Auto-confirming ConfirmScreen button '{}'", btn.getMessage().getString());
                        btn.onPress();
                        break;
                    }
                }
            } else if (mc.screen instanceof BackupConfirmScreen backup) {
                List<Button> buttons = new ArrayList<>();
                for (var child : backup.children()) {
                    if (child instanceof Button btn) buttons.add(btn);
                }
                if (buttons.size() >= 2) {
                    LOGGER.info("[ReplayAudioSelfTest] Auto-confirming BackupConfirmScreen button '{}'", buttons.get(1).getMessage().getString());
                    buttons.get(1).onPress();
                } else if (!buttons.isEmpty()) {
                    buttons.getFirst().onPress();
                }
            }
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        if (!VoiceChatPresence.isLoaded() || ReplayVoicePlugin.serverApi() == null) return;

        MinecraftServer server = mc.getSingleplayerServer();
        UUID playerUuid = mc.player.getUUID();
        phase = Phase.SETUP_SERVER;
        server.execute(() -> runServerSetup(server, playerUuid));
    }

    private static void runServerSetup(MinecraftServer server, UUID realPlayerId) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(realPlayerId);
            if (player == null) {
                serverSetupError = "ServerPlayer missing";
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockPos pPos = player.blockPosition();
            BlockPos targetBoard = pPos.offset(0, 0, -24);
            level.setBlock(pPos.offset(0, -1, -2), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(targetBoard.below(), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(targetBoard, SettlementSummaryBoard.BLOCK.defaultBlockState()
                    .setValue(SettlementSummaryBoard.FACING, Direction.SOUTH), Block.UPDATE_ALL);
            level.setBlock(targetBoard.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(targetBoard.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

            SceneBounds bounds = new SceneBounds(
                    pPos.getX() - 12, Math.max(level.getMinBuildHeight(), pPos.getY() - 4), pPos.getZ() - 12,
                    pPos.getX() + 13, Math.min(level.getMaxBuildHeight(), pPos.getY() + 10), pPos.getZ() + 13);

            ReplayMapCapture capture = new ReplayMapCapture(level, bounds, "selftest_map");
            int guard = 0;
            while (!capture.step() && guard++ < 400) {}
            ReplayMapCapture.Raw rawMap = capture.finish();
            if (rawMap == null) {
                serverSetupError = "ReplayMapCapture returned null";
                return;
            }
            ReplayMapCapture.Result mapResult = ReplayBoardService.encodeMap(rawMap).get(20, TimeUnit.SECONDS);
            if (mapResult == null) {
                serverSetupError = "ReplayBoardService.encodeMap returned null";
                return;
            }

            UUID roundId = UUID.randomUUID();
            UUID killer = UUID.randomUUID();
            UUID victimA = UUID.randomUUID();
            UUID victimB = UUID.randomUUID();
            UUID farSpeaker = UUID.randomUUID();

            List<LastMatchReplay.Sample> killerTrack = new ArrayList<>();
            List<LastMatchReplay.Sample> vATrack = new ArrayList<>();
            List<LastMatchReplay.Sample> vBTrack = new ArrayList<>();

            MatchVoiceRecorder.beginRound(roundId, level, bounds);
            MatchVoiceRecorder.activateRound(roundId);
            for (int t = 0; t <= 240; t += 5) {
                double kx = bounds.minX() + 2.0 + t * 0.04;
                double ky = bounds.minY() + 1.0;
                double kz = bounds.minZ() + 6.0;
                MatchVoiceRecorder.seedSelfTestPosition(roundId, killer, t, kx, ky, kz, true);
                MatchVoiceRecorder.seedSelfTestPosition(roundId, victimA, t, bounds.minX() + 5.0, ky, kz, t < 60);
                MatchVoiceRecorder.seedSelfTestPosition(roundId, victimB, t, bounds.minX() + 10.0, ky, kz, t < 160);
                MatchVoiceRecorder.seedSelfTestPosition(roundId, farSpeaker, t, bounds.minX() + 85.0, ky, kz, true);

                var kp = LastMatchReplay.Position.quantize(2.0 + t * 0.04, 1.0, 6.0);
                if (kp != null) killerTrack.add(new LastMatchReplay.Sample(t, kp, (byte) 0, LastMatchReplay.ALIVE));
                var ap = LastMatchReplay.Position.quantize(5.0, 1.0, 6.0);
                if (ap != null) vATrack.add(new LastMatchReplay.Sample(t, ap, (byte) 64, t < 60 ? LastMatchReplay.ALIVE : 0));
                var bp = LastMatchReplay.Position.quantize(10.0, 1.0, 6.0);
                if (bp != null) vBTrack.add(new LastMatchReplay.Sample(t, bp, (byte) 128, t < 160 ? LastMatchReplay.ALIVE : 0));
            }

            // Encode continuous-phase 48 kHz Opus packets via Simple Voice Chat's live OpusEncoder and feed through ReplayVoicePlugin!
            List<short[]> nearPcmFrames = new ArrayList<>(25);
            List<short[]> farPcmFrames = new ArrayList<>(25);
            for (int k = 0; k < 25; k++) {
                nearPcmFrames.add(sineFrame48k(440.0, 18_000, k * 960L));
                farPcmFrames.add(sineFrame48k(1900.0, 22_000, k * 960L));
            }
            List<byte[]> nearOpusSeq = ReplayVoicePlugin.encodeOpusSequence(nearPcmFrames);
            List<byte[]> farOpusSeq = ReplayVoicePlugin.encodeOpusSequence(farPcmFrames);
            if (nearOpusSeq.size() != 25 || farOpusSeq.size() != 25) {
                serverSetupError = "Simple Voice Chat OpusEncoder produced incomplete packet sequence";
                return;
            }
            int maxOpusLen = 0;
            for (int k = 0; k < 25; k++) {
                byte[] nearOpus = nearOpusSeq.get(k);
                byte[] farOpus = farOpusSeq.get(k);
                maxOpusLen = Math.max(maxOpusLen, nearOpus.length);
                ReplayVoicePlugin.ingestOpusPacketAtMs(roundId, killer, 2700L + k * 20L, false, nearOpus, 48.0f);
                ReplayVoicePlugin.ingestOpusPacketAtMs(roundId, farSpeaker, 2700L + k * 20L, false, farOpus, 48.0f);
            }
            for (int k = 0; k < 25; k++) {
                ReplayVoicePlugin.ingestOpusPacketAtMs(roundId, killer, 7700L + k * 20L, false, nearOpusSeq.get(k), 48.0f);
                ReplayVoicePlugin.ingestOpusPacketAtMs(roundId, farSpeaker, 7700L + k * 20L, false, farOpusSeq.get(k), 48.0f);
            }
            opusPacketBytes = maxOpusLen;

            var posShot = LastMatchReplay.Position.quantize(4.2, 1.0, 6.0);
            var posKillA = LastMatchReplay.Position.quantize(5.0, 1.0, 6.0);
            var posKillB = LastMatchReplay.Position.quantize(10.0, 1.0, 6.0);

            List<LastMatchReplay.Node> events = List.of(
                    new LastMatchReplay.Node(59, LastMatchReplay.Kind.GUNSHOT, killer, victimA,
                            posShot, posKillA, "starrailexpress:revolver", LastMatchReplay.HIT),
                    new LastMatchReplay.Node(60, LastMatchReplay.Kind.KILL, killer, victimA,
                            posShot, posKillA, "starrailexpress:revolver", LastMatchReplay.HIT),
                    new LastMatchReplay.Node(160, LastMatchReplay.Kind.KILL, killer, victimB,
                            posKillB, posKillB, "starrailexpress:knife_stab", 0)
            );
            byte[] voiceBytes = MatchVoiceRecorder.finishRound(roundId, events);
            if (voiceBytes.length < 100) {
                serverSetupError = "MatchVoiceRecorder extracted too little voice data: " + voiceBytes.length;
                return;
            }
            voiceArchiveBytes = voiceBytes.length;

            List<LastMatchReplay.Participant> participants = List.of(
                    new LastMatchReplay.Participant(killer, "KillerTest", "killer", 0xFFCC3333, "killer", 0xFFCC3333,
                            MatchWinFaction.KILLER, true, 12, 2, 120, 2, killerTrack),
                    new LastMatchReplay.Participant(victimA, "VictimATest", "civilian", 0xFF44AAEE, "civilian", 0xFF44AAEE,
                            MatchWinFaction.PASSENGER, false, 3, 0, 10, 0, vATrack),
                    new LastMatchReplay.Participant(victimB, "VictimBTest", "civilian", 0xFF44AAEE, "civilian", 0xFF44AAEE,
                            MatchWinFaction.PASSENGER, false, 8, 0, 10, 0, vBTrack)
            );
            LastMatchReplay replay = new LastMatchReplay(
                    roundId, level.getGameTime(), 240, "AudioTestCar", "classic", "KILLERS",
                    MatchWinKind.KILLER, "", "", "", 0xFFCC3333, bounds, null,
                    participants, events, List.of(), killer, 2, false);

            ReplayBoardService.publish(replay, CompletableFuture.completedFuture(mapResult), voiceBytes);
            boardPos = targetBoard;
            testRoundId = roundId;
        } catch (Throwable t) {
            serverSetupError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void tickWaitServerSetup(Minecraft mc) {
        if (serverSetupError != null) {
            fail(mc, serverSetupError);
            return;
        }
        if (testRoundId != null && boardPos != null) {
            phase = Phase.WAIT_SYNC_AND_ARCHIVE;
        }
    }

    private static void tickWaitSync(Minecraft mc) throws Exception {
        syncTicks++;
        if (mc.player != null && boardPos != null) {
            mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(boardPos.above()));
        }
        ClientReplayBoard.View view = ClientReplayBoard.viewAt(boardPos);
        if (view.replay() == null || !testRoundId.equals(view.replay().roundId())) return;
        if (view.totalSections() <= 0 || view.presentSections() < view.totalSections()) return;
        if (!view.voiceReady() || view.voice().isEmpty() || view.voice().clips().size() < 2) return;

        Path voiceFile = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                .resolve("habitrain").resolve("replay-archive").resolve("voice").resolve(testRoundId + ".voice");
        if (!Files.isRegularFile(voiceFile)) {
            if (syncTicks > 200) throw new IllegalStateException("Archive .voice file missing: " + voiceFile);
            return;
        }
        if (Files.size(voiceFile) != voiceArchiveBytes) {
            throw new IllegalStateException("Archive .voice size mismatch: " + Files.size(voiceFile) + " != " + voiceArchiveBytes);
        }

        // Verify proximity filter on reconstructed voice samples: 440Hz near >> 1900Hz 80m far speaker.
        short[] rec48k = view.voice().clips().getFirst().decode48k();
        near440Power = goertzelPower(rec48k, 440.0, 48_000);
        far1900Power = goertzelPower(rec48k, 1900.0, 48_000);
        if (near440Power <= 1e7 || near440Power < far1900Power * 30.0) {
            throw new IllegalStateException("Voice frequency check failed: near440=" + near440Power + " far1900=" + far1900Power);
        }

        // Verify real mod sound events & .ogg decoding via JOrbisAudioStream on the live ResourceManager.
        if (ReplayAudioPlaybackController.resolveSound(mc, List.of("starrailexpress:item.revolver.shoot")) != null) resolvedModSounds++;
        if (ReplayAudioPlaybackController.resolveSound(mc, List.of("starrailexpress:item.knife.stab")) != null) resolvedModSounds++;
        if (resolvedModSounds < 2) {
            throw new IllegalStateException("Expected starrailexpress revolver & knife sounds resolved on client SoundManager");
        }

        ReplayVideoScript dummyScript = new ReplayVideoScript(view.replay());
        short[] renderedSfxVoice = ReplayVideoAudioRenderer.render(mc, view.replay(), dummyScript, view.voice(), dummyScript.frames(30));
        double checkPeak = maxAbs(renderedSfxVoice);
        if (checkPeak < 2000) {
            throw new IllegalStateException("Live ResourceManager .ogg + voice mix too quiet: peak=" + checkPeak);
        }

        // Start in-world 3D SettlementSummaryBoard playback on the integrated server.
        UUID round = testRoundId;
        int cycle = view.timeline().cycleTicks();
        mc.getSingleplayerServer().execute(() -> {
            ServerLevel sl = mc.getSingleplayerServer().overworld();
            if (mc.player != null) {
                var sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                if (sp != null) sl = sp.serverLevel();
            }
            if (sl.getBlockEntity(boardPos) instanceof SettlementSummaryBoard.Marker marker) {
                marker.startPlayback(round, cycle);
            }
        });
        phase = Phase.VERIFY_LIVE_3D_AUDIO;
    }

    private static void tickVerifyLive3dAudio(Minecraft mc) {
        playbackTicks++;
        if (mc.player != null && boardPos != null) {
            Vec3 eye = mc.player.getEyePosition();
            mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, eye.add(eye.subtract(Vec3.atCenterOf(boardPos.above()))));
        }
        if (ReplayAudioPlaybackController.isBoardVoiceActive(mc, boardPos)) {
            observedBoardVoiceActive = true;
        }
        // Let 3D board animation play well past the first kill zoom (~12 + 50 + ~85 ticks) and once voice active is seen.
        if ((observedBoardVoiceActive && playbackTicks >= 95) || playbackTicks >= 165) {
            if (!observedBoardVoiceActive) throw new IllegalStateException("Offscreen replay table lost its voice");
            mp4Export = startExport(true);
            mc.setScreen(new ReplayExportScreen(mc.level, boardPos));
            pressEscape(mc);
            if (mc.screen != null || !mp4Export.running()) {
                throw new IllegalStateException("Escape did not send the MP4 export to the background");
            }
            phase = Phase.EXPORT_MP4;
        }
    }

    private static void tickExportMp4(Minecraft mc) {
        if (mp4Export.failed() || mp4Export.cancelled()) {
            throw new IllegalStateException("Live MP4 export failed");
        }
        if (mp4Export.saved() != null && ReplayExportClient.canStart()) {
            mp4Path = mp4Export.saved();
            aviExport = startExport(false);
            mc.setScreen(new ReplayBoardControlsScreen(mc.level, boardPos));
            OnGameStartedClient.EVENT.invoker().gameStarted();
            if (mc.screen != null || !aviExport.running()) {
                throw new IllegalStateException("Game start did not send the AVI export to the background");
            }
            phase = Phase.EXPORT_AVI;
        }
    }

    private static void tickExportAvi(Minecraft mc) throws Exception {
        if (aviExport.failed() || aviExport.cancelled()) {
            throw new IllegalStateException("Live AVI export failed");
        }
        if (aviExport.saved() == null || !ReplayExportClient.canStart()) return;
        aviPath = aviExport.saved();
        mc.setScreen(null);
        ReplayAudioPlaybackController.reset();

        ClientReplayBoard.View view = ClientReplayBoard.viewAt(boardPos);
        ReplayVideoScript script = new ReplayVideoScript(view.replay());
        if (script.beats().size() < 2) throw new IllegalStateException("Expected 2 scripted beats");
        double beat0Time = script.beats().get(0).impact();
        double beat1Time = script.beats().get(1).impact();

        mp4Size = Files.size(mp4Path);
        aviSize = Files.size(aviPath);

        // Verify exported MP4 video + AAC audio alignment.
        try (var ch = NIOUtils.readableChannel(mp4Path.toFile())) {
            MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(ch);
            if (demuxer.getVideoTracks().size() != 1 || demuxer.getAudioTracks().size() != 1) {
                throw new IllegalStateException("MP4 missing expected video+audio tracks");
            }
            DemuxerTrack audio = demuxer.getAudioTracks().getFirst();
            byte[] asc = new byte[audio.getMeta().getCodecPrivate().remaining()];
            audio.getMeta().getCodecPrivate().duplicate().get(asc);
            Decoder dec = new Decoder(asc);
            SampleBuffer sb = new SampleBuffer();
            short[] mp4Pcm = new short[(script.frames(30) + 1) * 1600 * 2 + 8192];
            int pos = 0;
            Packet pkt;
            while ((pkt = audio.nextFrame()) != null) {
                ByteBuffer data = pkt.getData();
                if (ADTSParser.read(data) == null) throw new IllegalStateException("Invalid demuxed ADTS");
                byte[] frameBytes = new byte[data.remaining()];
                data.get(frameBytes);
                dec.decodeFrame(frameBytes, sb);
                sb.setBigEndian(false);
                ByteBuffer pcmBuf = ByteBuffer.wrap(sb.getData()).order(ByteOrder.LITTLE_ENDIAN);
                while (pcmBuf.hasRemaining() && pos < mp4Pcm.length) {
                    mp4Pcm[pos++] = pcmBuf.getShort();
                }
            }
            mp4IntroRms = windowRms(mp4Pcm, 0.25, 0.90);
            mp4Beat0Rms = windowRms(mp4Pcm, beat0Time - 0.05, beat0Time + 0.18);
            mp4Beat1Rms = windowRms(mp4Pcm, beat1Time - 0.05, beat1Time + 0.18);
            if (mp4Beat0Rms < 1200 || mp4Beat1Rms < 1200 || mp4IntroRms > 150) {
                throw new IllegalStateException("MP4 audio verification failed: intro=" + mp4IntroRms
                        + " beat0=" + mp4Beat0Rms + " beat1=" + mp4Beat1Rms);
            }
        }

        // Verify exported AVI video + PCM audio alignment.
        short[] aviPcm = extractAviPcm(Files.readAllBytes(aviPath));
        aviIntroRms = windowRms(aviPcm, 0.25, 0.90);
        aviBeat0Rms = windowRms(aviPcm, beat0Time - 0.05, beat0Time + 0.18);
        aviBeat1Rms = windowRms(aviPcm, beat1Time - 0.05, beat1Time + 0.18);
        if (aviBeat0Rms < 1200 || aviBeat1Rms < 1200 || aviIntroRms > 150) {
            throw new IllegalStateException("AVI audio verification failed: intro=" + aviIntroRms
                    + " beat0=" + aviBeat0Rms + " beat1=" + aviBeat1Rms);
        }

        Files.deleteIfExists(mp4Path);
        Files.deleteIfExists(aviPath);
        try (var files = Files.list(ReplayVideoExport.directory(mc))) {
            filesBeforeCancellation = new HashSet<>(files.toList());
        }
        cancelledExport = startExport(false);
        phase = Phase.CANCEL_EXPORT;
    }

    private static ReplayVideoExport startExport(boolean compress) {
        return Objects.requireNonNull(ReplayExportClient.start(ClientReplayBoard.viewAt(boardPos), true,
                ReplayExportResolution.P480, compress, () -> ClientReplayBoard.viewAt(boardPos)));
    }

    private static void pressEscape(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        mc.keyboardHandler.keyPress(window, GLFW.GLFW_KEY_ESCAPE, 0, GLFW.GLFW_PRESS, 0);
        mc.keyboardHandler.keyPress(window, GLFW.GLFW_KEY_ESCAPE, 0, GLFW.GLFW_RELEASE, 0);
    }

    private static void tickCancelExport(Minecraft mc) throws IOException {
        if (!cancelledExport.cancelled()) {
            if (!cancelledExport.running()) throw new IllegalStateException("Third export stopped before cancellation");
            if (cancelledExport.displayProgress() < 0.05) return;
            try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                screenshot.writeToFile(reportPath.resolveSibling("progress.png"));
            }
            pressEscape(mc);
            if (!cancelledExport.cancelled() || mc.screen != null) {
                throw new IllegalStateException("Escape did not cancel the background export");
            }
        }
        if (!ReplayExportClient.canStart()) return;
        try (var files = Files.list(ReplayVideoExport.directory(mc))) {
            if (files.anyMatch(file -> !filesBeforeCancellation.contains(file))) {
                throw new IllegalStateException("Cancellation left a file in the video directory");
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "PASS");
        report.put("voiceChatLoaded", VoiceChatPresence.isLoaded());
        report.put("opusPacketBytes", opusPacketBytes);
        report.put("archiveVoiceBytes", voiceArchiveBytes);
        report.put("near440Power", near440Power);
        report.put("far1900Power", far1900Power);
        report.put("resolvedModSounds", resolvedModSounds);
        report.put("observedBoardVoiceActive", observedBoardVoiceActive);
        report.put("offscreenTableAudio", observedBoardVoiceActive);
        report.put("mp4Size", mp4Size);
        report.put("mp4IntroRms", mp4IntroRms);
        report.put("mp4Beat0Rms", mp4Beat0Rms);
        report.put("mp4Beat1Rms", mp4Beat1Rms);
        report.put("aviSize", aviSize);
        report.put("aviIntroRms", aviIntroRms);
        report.put("aviBeat0Rms", aviBeat0Rms);
        report.put("aviBeat1Rms", aviBeat1Rms);
        report.put("backgroundExportCancelled", true);
        writeReport(report);

        phase = Phase.DONE;
        LOGGER.info("[ReplayAudioSelfTest] PASS: all live client audio checks succeeded!");
        mc.stop();
    }

    private static void fail(Minecraft mc, String reason) {
        phase = Phase.DONE;
        LOGGER.error("[ReplayAudioSelfTest] FAIL: {}", reason);
        try {
            if (mp4Path != null) Files.deleteIfExists(mp4Path);
            if (aviPath != null) Files.deleteIfExists(aviPath);
        } catch (Throwable ignored) {}
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", "FAIL");
            report.put("error", reason);
            report.put("resolvedModSounds", resolvedModSounds);
            report.put("observedBoardVoiceActive", observedBoardVoiceActive);
            writeReport(report);
        } catch (Throwable ignored) {}
        mc.stop();
    }

    private static void writeReport(Map<String, Object> report) throws IOException {
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
    }

    private static short[] sineFrame48k(double freqHz, int amplitude, long sampleOffset) {
        short[] pcm = new short[960];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) Math.round(amplitude * Math.sin(2.0 * Math.PI * freqHz * (sampleOffset + i) / 48_000.0));
        }
        return pcm;
    }

    private static double goertzelPower(short[] pcm, double freqHz, int sr) {
        double omega = 2.0 * Math.PI * freqHz / sr;
        double coeff = 2.0 * Math.cos(omega);
        double q0, q1 = 0, q2 = 0;
        for (short s : pcm) {
            q0 = coeff * q1 - q2 + s;
            q2 = q1;
            q1 = q0;
        }
        return q1 * q1 + q2 * q2 - q1 * q2 * coeff;
    }

    private static double maxAbs(short[] s) {
        double m = 0;
        for (short v : s) m = Math.max(m, Math.abs(v));
        return m;
    }

    private static double windowRms(short[] interleaved, double fromSec, double toSec) {
        int start = Math.max(0, (int) Math.round(fromSec * 48_000));
        int end = Math.min(interleaved.length / 2, (int) Math.round(toSec * 48_000));
        if (end <= start) return 0;
        double sum = 0;
        for (int f = start; f < end; f++) {
            double mono = 0.5 * (interleaved[f * 2] + interleaved[f * 2 + 1]);
            sum += mono * mono;
        }
        return Math.sqrt(sum / (end - start));
    }

    private static short[] extractAviPcm(byte[] aviBytes) {
        ByteBuffer buf = ByteBuffer.wrap(aviBytes).order(ByteOrder.LITTLE_ENDIAN);
        int p = 12, moviEnd = -1;
        while (p + 8 <= aviBytes.length) {
            int id = buf.getInt(p);
            int size = buf.getInt(p + 4);
            if (id == 0x5453494C && p + 12 <= aviBytes.length && buf.getInt(p + 8) == 0x69766F6D) {
                moviEnd = p + 8 + size;
                p += 12;
                break;
            }
            p += 8 + ((size + 1) & ~1);
        }
        if (moviEnd < 0) return new short[0];
        int count = 0;
        for (int s = p; s + 8 <= moviEnd;) {
            int id = buf.getInt(s);
            int size = buf.getInt(s + 4);
            if (id == 0x62773130) count += size / 2;
            s += 8 + ((size + 1) & ~1);
        }
        short[] out = new short[count];
        int idx = 0;
        for (int s = p; s + 8 <= moviEnd;) {
            int id = buf.getInt(s);
            int size = buf.getInt(s + 4);
            if (id == 0x62773130) {
                for (int off = 0; off < size; off += 2) out[idx++] = buf.getShort(s + 8 + off);
            }
            s += 8 + ((size + 1) & ~1);
        }
        return out;
    }

}
