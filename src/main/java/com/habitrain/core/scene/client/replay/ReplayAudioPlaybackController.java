package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.replay.ReplaySoundCuePlan;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plays synchronized weapon/item game sounds and server-recorded proximity voice clips during live
 * 3D {@code SettlementSummaryBoard} playback and {@link ReplayHistoryScreen} linear previewing.
 */
public final class ReplayAudioPlaybackController {
    private static final ResourceLocation BOARD_VOICE_ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "replay_voice");
    private static final double TICKS_PER_SECOND = 20.0;
    private static final int VOICE_RATE = ReplayVoiceData.VOICECHAT_SAMPLE_RATE;

    private static final Map<BlockPos, BoardState> BOARDS = new HashMap<>();
    private static @Nullable HistoryState history;

    private ReplayAudioPlaybackController() {}

    private static final class BoardState {
        UUID roundId;
        ReplayVoiceData voice = ReplayVoiceData.EMPTY;
        ReplaySoundCuePlan.Schedule schedule = ReplaySoundCuePlan.Schedule.EMPTY;
        double prevActionTick = -1.0;
        int nextSoundIdx;
        int nextVoiceIdx;
        @Nullable VoiceClipSoundInstance activeVoice;
    }

    private static final class HistoryState {
        UUID roundId;
        ReplayVoiceData voice = ReplayVoiceData.EMPTY;
        ReplaySoundCuePlan.Schedule schedule = ReplaySoundCuePlan.Schedule.EMPTY;
        double prevCursor = -1.0;
        int nextSoundIdx;
        int nextVoiceIdx;
        @Nullable VoiceClipSoundInstance activeVoice;
    }

    static void updateBoard(Minecraft mc, BlockPos pos, Vec3 cam, ReplayBoardSite site,
                            ReplayBoardModel model, ClientReplayBoard.View view,
                            boolean active, double elapsedTicks) {
        if (mc == null || mc.getSoundManager() == null || model == null || view == null) return;
        double actionLength = model.timeline.cycleTicks() - ReplayTimeline.INTRO - ReplayTimeline.HONORS - ReplayTimeline.REVIEW;
        double actionTick = elapsedTicks - ReplayTimeline.INTRO;
        if (!active || actionLength <= 0 || actionTick < 0 || actionTick >= actionLength) {
            BoardState existing = BOARDS.get(pos);
            if (existing != null) {
                stopActiveVoice(mc, existing.activeVoice);
                existing.activeVoice = null;
                existing.prevActionTick = -1.0;
                existing.nextSoundIdx = 0;
                existing.nextVoiceIdx = 0;
            }
            return;
        }

        BoardState state = BOARDS.computeIfAbsent(pos.immutable(), k -> new BoardState());
        ReplayVoiceData currentVoice = view.voice() == null ? ReplayVoiceData.EMPTY : view.voice();
        boolean newRound = !model.replay.roundId().equals(state.roundId);
        if (newRound || state.voice != currentVoice) {
            state.roundId = model.replay.roundId();
            state.voice = currentVoice;
            state.schedule = ReplaySoundCuePlan.forBoard(model.replay, model.timeline, currentVoice);
            if (newRound) {
                stopActiveVoice(mc, state.activeVoice);
                state.activeVoice = null;
                state.prevActionTick = -1.0;
                state.nextSoundIdx = 0;
                state.nextVoiceIdx = 0;
            } else if (state.prevActionTick >= 0) {
                state.nextSoundIdx = firstSoundAfter(state.schedule.sounds(), state.prevActionTick);
                state.nextVoiceIdx = firstVoiceAtOrAfter(state.schedule.voices(), state.prevActionTick);
            }
        }

        if (state.prevActionTick < 0 || actionTick < state.prevActionTick - 2.0
                || actionTick - state.prevActionTick > 15.0) {
            stopActiveVoice(mc, state.activeVoice);
            state.activeVoice = null;
            state.nextSoundIdx = firstSoundAfter(state.schedule.sounds(), actionTick - 0.5);
            state.nextVoiceIdx = firstVoiceAtOrAfter(state.schedule.voices(), actionTick);
            state.prevActionTick = actionTick;
            return;
        }

        float fade = Math.clamp(site.fade(), 0f, 1f);
        List<ReplaySoundCuePlan.SoundCue> sounds = state.schedule.sounds();
        while (state.nextSoundIdx < sounds.size()) {
            ReplaySoundCuePlan.SoundCue cue = sounds.get(state.nextSoundIdx);
            if (cue.time() > actionTick) break;
            if (cue.time() >= state.prevActionTick - 1.0 && fade > 0.02f) {
                Vec3 emitter = boardEventWorldPos(cam, site, model, cue.eventIndex());
                Vec3 clamped = clampAudibleDistance(cam, emitter, 12.0);
                ResourceLocation resolved = resolveSound(mc, cue.candidates());
                if (resolved != null) {
                    mc.getSoundManager().play(new SimpleSoundInstance(
                            resolved,
                            SoundSource.PLAYERS,
                            Math.min(1.25f, cue.volume() * fade),
                            cue.pitch(),
                            SoundInstance.createUnseededRandom(),
                            false,
                            0,
                            SoundInstance.Attenuation.LINEAR,
                            clamped.x, clamped.y, clamped.z,
                            false));
                }
            }
            state.nextSoundIdx++;
        }

        List<ReplaySoundCuePlan.VoiceCue> voices = state.schedule.voices();
        while (state.nextVoiceIdx < voices.size()) {
            ReplaySoundCuePlan.VoiceCue voiceCue = voices.get(state.nextVoiceIdx);
            if (voiceCue.startTime() > actionTick) break;
            if (actionTick < voiceCue.endTime() - 1.0 && fade > 0.02f) {
                double skipSeconds = Math.max(0.0, (actionTick - voiceCue.startTime()) / TICKS_PER_SECOND);
                double maxDuration = Math.max(0.1, (voiceCue.endTime() - Math.max(actionTick, voiceCue.startTime())) / TICKS_PER_SECOND);
                byte[] pcm = buildPcm48kSlice(voiceCue.clip(), skipSeconds, maxDuration);
                if (pcm.length > 0) {
                    stopActiveVoice(mc, state.activeVoice);
                    Vec3 emitter = boardEventWorldPos(cam, site, model, voiceCue.clip().eventIndex());
                    Vec3 clamped = clampAudibleDistance(cam, emitter, 12.0);
                    VoiceClipSoundInstance instance = new VoiceClipSoundInstance(
                            pcm, fade, clamped.x, clamped.y, clamped.z, false);
                    state.activeVoice = instance;
                    mc.getSoundManager().play(instance);
                }
            }
            state.nextVoiceIdx++;
        }

        state.prevActionTick = actionTick;
    }

    static void endBoardFrame(Minecraft mc, Set<BlockPos> activeBoards) {
        if (BOARDS.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, BoardState>> it = BOARDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BoardState> entry = it.next();
            if (!activeBoards.contains(entry.getKey())) {
                if (mc != null) stopActiveVoice(mc, entry.getValue().activeVoice);
                it.remove();
            }
        }
    }

    static void updateHistory(Minecraft mc, @Nullable UUID roundId, @Nullable LastMatchReplay replay,
                              @Nullable ReplayVoiceData voice, boolean playing, double cursorTicks) {
        if (mc == null || mc.getSoundManager() == null) return;
        if (!playing || roundId == null || replay == null) {
            if (history != null) {
                stopActiveVoice(mc, history.activeVoice);
                history.activeVoice = null;
                history.prevCursor = playing ? cursorTicks : -1.0;
            }
            return;
        }
        ReplayVoiceData currentVoice = voice == null ? ReplayVoiceData.EMPTY : voice;
        if (history == null || !roundId.equals(history.roundId)) {
            if (history != null) stopActiveVoice(mc, history.activeVoice);
            history = new HistoryState();
            history.roundId = roundId;
            history.voice = currentVoice;
            history.schedule = ReplaySoundCuePlan.forLinearReplay(replay, currentVoice);
            history.prevCursor = cursorTicks;
            history.nextSoundIdx = firstSoundAfter(history.schedule.sounds(), cursorTicks - 0.5);
            history.nextVoiceIdx = firstVoiceAtOrAfter(history.schedule.voices(), cursorTicks);
            return;
        }
        if (history.voice != currentVoice) {
            history.voice = currentVoice;
            history.schedule = ReplaySoundCuePlan.forLinearReplay(replay, currentVoice);
            double anchor = history.prevCursor >= 0 ? history.prevCursor : cursorTicks;
            history.nextSoundIdx = firstSoundAfter(history.schedule.sounds(), anchor);
            history.nextVoiceIdx = firstVoiceAtOrAfter(history.schedule.voices(), anchor);
        }

        if (history.prevCursor < 0 || cursorTicks < history.prevCursor - 1.5
                || cursorTicks - history.prevCursor > 8.0) {
            stopActiveVoice(mc, history.activeVoice);
            history.activeVoice = null;
            history.prevCursor = cursorTicks;
            history.nextSoundIdx = firstSoundAfter(history.schedule.sounds(), cursorTicks - 0.5);
            history.nextVoiceIdx = firstVoiceAtOrAfter(history.schedule.voices(), cursorTicks);
            return;
        }

        List<ReplaySoundCuePlan.SoundCue> sounds = history.schedule.sounds();
        while (history.nextSoundIdx < sounds.size()) {
            ReplaySoundCuePlan.SoundCue cue = sounds.get(history.nextSoundIdx);
            if (cue.time() > cursorTicks) break;
            if (cue.time() >= history.prevCursor - 0.5) {
                ResourceLocation resolved = resolveSound(mc, cue.candidates());
                if (resolved != null) {
                    mc.getSoundManager().play(new SimpleSoundInstance(
                            resolved,
                            SoundSource.PLAYERS,
                            Math.min(1.1f, cue.volume() * 0.9f),
                            cue.pitch(),
                            SoundInstance.createUnseededRandom(),
                            false,
                            0,
                            SoundInstance.Attenuation.NONE,
                            0.0, 0.0, 0.0,
                            true));
                }
            }
            history.nextSoundIdx++;
        }

        List<ReplaySoundCuePlan.VoiceCue> voices = history.schedule.voices();
        while (history.nextVoiceIdx < voices.size()) {
            ReplaySoundCuePlan.VoiceCue voiceCue = voices.get(history.nextVoiceIdx);
            if (voiceCue.startTime() > cursorTicks) break;
            if (cursorTicks < voiceCue.endTime() - 1.0) {
                double skipSeconds = Math.max(0.0, (cursorTicks - voiceCue.startTime()) / TICKS_PER_SECOND);
                double maxDuration = Math.max(0.1, (voiceCue.endTime() - Math.max(cursorTicks, voiceCue.startTime())) / TICKS_PER_SECOND);
                byte[] pcm = buildPcm48kSlice(voiceCue.clip(), skipSeconds, maxDuration);
                if (pcm.length > 0) {
                    stopActiveVoice(mc, history.activeVoice);
                    VoiceClipSoundInstance instance = new VoiceClipSoundInstance(pcm, 0.95f, 0.0, 0.0, 0.0, true);
                    history.activeVoice = instance;
                    mc.getSoundManager().play(instance);
                }
            }
            history.nextVoiceIdx++;
        }

        history.prevCursor = cursorTicks;
    }

    static void onHistoryScrub() {
        if (history != null) {
            stopActiveVoice(Minecraft.getInstance(), history.activeVoice);
            history.activeVoice = null;
            history.prevCursor = -1.0;
        }
    }

    static void stopHistory(Minecraft mc) {
        if (history != null) {
            if (mc != null) stopActiveVoice(mc, history.activeVoice);
            history = null;
        }
    }

    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        for (BoardState state : BOARDS.values()) {
            if (mc != null) stopActiveVoice(mc, state.activeVoice);
        }
        BOARDS.clear();
        stopHistory(mc);
    }

    static boolean isBoardVoiceActive(Minecraft mc, BlockPos pos) {
        BoardState state = BOARDS.get(pos);
        return state != null && state.activeVoice != null && mc.getSoundManager().isActive(state.activeVoice);
    }

    static @Nullable ResourceLocation resolveSound(Minecraft mc, List<String> candidates) {
        SoundManager manager = mc.getSoundManager();
        for (String candidate : candidates) {
            ResourceLocation id = ResourceLocation.tryParse(candidate);
            if (id != null && manager.getSoundEvent(id) != null) return id;
        }
        return null;
    }

    static byte[] buildPcm48kSlice(ReplayVoiceData.Clip clip, double skipSeconds, double maxDurationSeconds) {
        short[] samples = clip.decode48k();
        if (samples.length == 0) return new byte[0];
        int start = Math.clamp((int) Math.round(skipSeconds * VOICE_RATE), 0, samples.length);
        int maxCount = Math.clamp((int) Math.round(maxDurationSeconds * VOICE_RATE), 0, samples.length - start);
        if (maxCount < VOICE_RATE / 10) return new byte[0];

        int fadeIn = Math.min(VOICE_RATE / 25, maxCount / 4);   // 40 ms
        int fadeOut = Math.min(VOICE_RATE / 12, maxCount / 3);  // ~83 ms
        byte[] bytes = new byte[maxCount * 2];
        for (int i = 0; i < maxCount; i++) {
            double env = 1.0;
            if (i < fadeIn && fadeIn > 0) {
                env = 0.5 * (1.0 - Math.cos(Math.PI * i / fadeIn));
            } else if (i >= maxCount - fadeOut && fadeOut > 0) {
                int rem = maxCount - 1 - i;
                env = 0.5 * (1.0 - Math.cos(Math.PI * rem / fadeOut));
            }
            short v = (short) Math.clamp(Math.round(samples[start + i] * env), Short.MIN_VALUE, Short.MAX_VALUE);
            bytes[i * 2] = (byte) (v & 0xFF);
            bytes[i * 2 + 1] = (byte) ((v >>> 8) & 0xFF);
        }
        return bytes;
    }

    private static Vec3 boardEventWorldPos(Vec3 cam, ReplayBoardSite site, ReplayBoardModel model, int eventIndex) {
        if (eventIndex >= 0 && eventIndex < model.replay.events().size()) {
            LastMatchReplay.Node ev = model.replay.events().get(eventIndex);
            LastMatchReplay.Position pos = ev.to() != null ? ev.to() : ev.from();
            if (pos != null) {
                float[] projected = new float[3];
                model.project(pos.blockX(), pos.blockY(), pos.blockZ(), projected);
                Vector3f rel = site.board().transformPosition(
                        projected[0], ReplayBoardSite.MAP_BASE + projected[1], projected[2], new Vector3f());
                return new Vec3(cam.x + rel.x, cam.y + rel.y, cam.z + rel.z);
            }
        }
        return new Vec3(site.pos().getX() + 0.5, site.pos().getY() + 1.35, site.pos().getZ() + 0.5);
    }

    private static Vec3 clampAudibleDistance(Vec3 cam, Vec3 emitter, double maxDist) {
        Vec3 delta = emitter.subtract(cam);
        double distSq = delta.lengthSqr();
        if (distSq <= maxDist * maxDist || distSq < 1e-6) return emitter;
        return cam.add(delta.scale(maxDist / Math.sqrt(distSq)));
    }

    private static void stopActiveVoice(@Nullable Minecraft mc, @Nullable VoiceClipSoundInstance instance) {
        if (instance == null) return;
        instance.stopClip();
        if (mc != null && mc.getSoundManager() != null) {
            mc.getSoundManager().stop(instance);
        }
    }

    private static int firstSoundAfter(List<ReplaySoundCuePlan.SoundCue> cues, double tick) {
        for (int i = 0; i < cues.size(); i++) {
            if (cues.get(i).time() >= tick) return i;
        }
        return cues.size();
    }

    private static int firstVoiceAtOrAfter(List<ReplaySoundCuePlan.VoiceCue> cues, double tick) {
        for (int i = 0; i < cues.size(); i++) {
            if (cues.get(i).endTime() > tick + 1.0) return i;
        }
        return cues.size();
    }

    /**
     * Streaming 48 kHz mono 16-bit PCM {@link SoundInstance} backed by Fabric's {@link FabricSoundInstance}
     * hook so server-recorded ADPCM clips play natively through OpenAL without writing temporary .ogg files.
     */
    static final class VoiceClipSoundInstance extends AbstractTickableSoundInstance {
        private final byte[] pcmBytes;
        private final int maxTicks;
        private int ticksAlive;

        VoiceClipSoundInstance(byte[] pcmBytes, float volume, double x, double y, double z, boolean relative) {
            super(SoundEvent.createFixedRangeEvent(BOARD_VOICE_ID, 32.0F), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.pcmBytes = pcmBytes;
            this.volume = Math.clamp(volume, 0f, 1.5f);
            this.pitch = 1.0f;
            this.x = x;
            this.y = y;
            this.z = z;
            this.looping = false;
            this.delay = 0;
            this.relative = relative;
            this.attenuation = relative ? Attenuation.NONE : Attenuation.LINEAR;
            // Convert byte length (96,000 B/s) -> ticks plus generous OpenAL queue margin.
            this.maxTicks = (int) Math.ceil(pcmBytes.length / (VOICE_RATE * 2.0) * TICKS_PER_SECOND) + 30;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            this.sound = new Sound(
                    FabricSoundInstance.EMPTY_SOUND,
                    ConstantFloat.of(1.0F),
                    ConstantFloat.of(1.0F),
                    1,
                    Sound.Type.FILE,
                    true,
                    false,
                    32);
            return new WeighedSoundEvents(this.location, null);
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id,
                                                             boolean repeatInstantly) {
            return CompletableFuture.completedFuture(new PcmByteAudioStream(pcmBytes));
        }

        @Override
        public void tick() {
            if (++ticksAlive >= maxTicks) {
                this.stop();
            }
        }

        void stopClip() {
            this.stop();
        }
    }

    private static final class PcmByteAudioStream implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(VOICE_RATE, 16, 1, true, false);
        private final byte[] pcm;
        private int cursor;

        PcmByteAudioStream(byte[] pcm) {
            this.pcm = pcm;
        }

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public @Nullable ByteBuffer read(int size) throws IOException {
            if (cursor >= pcm.length || size <= 0) {
                return null;
            }
            int aligned = Math.min(size & ~1, pcm.length - cursor);
            if (aligned <= 0) {
                cursor = pcm.length;
                return null;
            }
            ByteBuffer direct = BufferUtils.createByteBuffer(aligned);
            direct.put(pcm, cursor, aligned);
            direct.flip();
            cursor += aligned;
            return direct;
        }

        @Override
        public void close() {}
    }
}
