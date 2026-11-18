package com.habitrain.core.game.sre.replay;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.drone.DroneOperatorService;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.role.capability.ServerVoicePermissions;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional Simple Voice Chat plugin that captures proximity voice packets during active SRE
 * matches and forwards decoded 48 kHz mono PCM frames to {@link MatchVoiceRecorder}.
 *
 * <p>Loaded exclusively through the {@code voicechat} entrypoint so environments without Simple
 * Voice Chat never touch any {@code de.maxhenkel.voicechat.*} class.</p>
 */
public final class ReplayVoicePlugin implements VoicechatPlugin {
    public static final String PLUGIN_ID = "habitrain_core_replay_voice";

    private static final ConcurrentHashMap<UUID, OpusDecoder> DECODERS = new ConcurrentHashMap<>();
    private static volatile @Nullable VoicechatServerApi serverApi;

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // Server API becomes available in VoicechatServerStartedEvent.
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onDisconnect);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMic);
        HabiTrainCore.LOGGER.info("[ReplayVoice] Simple Voice Chat replay recorder hook registered");
    }

    public static @Nullable VoicechatServerApi serverApi() {
        return serverApi;
    }

    /** Encodes a contiguous sequence of 20 ms 48 kHz mono frames through a stateful SV Opus encoder (self-test only). */
    public static List<byte[]> encodeOpusSequence(List<short[]> frames48k) {
        VoicechatServerApi api = serverApi;
        if (api == null || frames48k == null || frames48k.isEmpty()) return List.of();
        OpusEncoder encoder = api.createEncoder();
        try {
            List<byte[]> packets = new ArrayList<>(frames48k.size());
            for (short[] frame : frames48k) {
                byte[] opus = frame == null || frame.length == 0 ? new byte[0] : encoder.encode(frame);
                if (opus == null || opus.length == 0) return List.of();
                packets.add(opus);
            }
            return packets;
        } finally {
            try {
                encoder.close();
            } catch (Throwable ignored) {}
        }
    }

    /** Timestamped self-test entry point so 20 ms Opus packets land at exact 20 ms match offsets. */
    public static void ingestOpusPacketAtMs(UUID roundId, UUID speaker, long matchMs, boolean whispering,
                                            byte[] opusData, float maxDistOverride) {
        VoicechatServerApi api = serverApi;
        if (api == null || speaker == null || roundId == null || !MatchVoiceRecorder.isRecording()) return;
        OpusDecoder decoder = DECODERS.computeIfAbsent(speaker, ignored -> api.createDecoder());
        if (opusData == null || opusData.length == 0) {
            decoder.resetState();
            return;
        }
        short[] pcm48k = decoder.decode(opusData);
        if (pcm48k == null || pcm48k.length == 0) return;
        float maxDist = maxDistOverride > 0 ? maxDistOverride : resolveMaxDistance(api, whispering);
        MatchVoiceRecorder.recordPcm48kAtMs(roundId, speaker, matchMs, maxDist, pcm48k);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        serverApi = null;
        clearDecoders();
    }

    private void onDisconnect(PlayerDisconnectedEvent event) {
        OpusDecoder decoder = DECODERS.remove(event.getPlayerUuid());
        if (decoder != null) {
            try {
                decoder.close();
            } catch (Throwable ignored) {}
        }
    }

    private void onMic(MicrophonePacketEvent event) {
        try {
            if (event.isCancelled() || !MatchVoiceRecorder.isRecording()) return;
            VoicechatServerApi api = serverApi;
            if (api == null) api = event.getVoicechat();
            VoicechatConnection sender = event.getSenderConnection();
            if (api == null || sender == null || sender.getPlayer() == null) return;
            UUID speaker = sender.getPlayer().getUuid();
            if (speaker == null) return;
            var permissions = ServerVoicePermissions.current();
            var participant = permissions.players().get(speaker);
            if (participant == null) return;
            UUID round = MatchVoiceRecorder.recordingRound(speaker, participant.dimension());
            if (round == null) return;

            if (SlothComponent.isSleepingSloth(speaker)
                    || MimeKillerComponent.isMuted(speaker)
                    || DroneOperatorService.isVoiceMuted(speaker)
                    || permissions.evaluate(speaker, null, null, null) == VoiceDecision.BLOCK) {
                return;
            }

            // Mirror Simple Voice Chat's Server.processMicPacket: closed/normal groups do not emit proximity voice.
            Group group = sender.getGroup();
            if (group != null && group.getType() != Group.Type.OPEN) {
                return;
            }

            MicrophonePacket packet = event.getPacket();
            if (packet == null) return;
            byte[] opus = packet.getOpusEncodedData();
            VoicechatServerApi activeApi = api;
            OpusDecoder decoder = DECODERS.computeIfAbsent(speaker, ignored -> activeApi.createDecoder());
            if (opus == null || opus.length == 0) {
                decoder.resetState();
                return;
            }
            short[] pcm48k = decoder.decode(opus);
            if (pcm48k == null || pcm48k.length == 0) return;
            float maxDist = resolveMaxDistance(api, packet.isWhispering());
            MatchVoiceRecorder.recordPcm48k(round, speaker, maxDist, pcm48k);
        } catch (Throwable ignored) {
            // Soft-fail: never break live voicechat transmission.
        }
    }

    private static float resolveMaxDistance(VoicechatServerApi api, boolean whispering) {
        double base = api.getVoiceChatDistance();
        if (whispering && api.getServerConfig() != null) {
            return (float) api.getServerConfig().getDouble("whisper_distance", base * 0.5);
        }
        return (float) base;
    }

    private static void clearDecoders() {
        for (OpusDecoder decoder : DECODERS.values()) {
            try {
                decoder.close();
            } catch (Throwable ignored) {}
        }
        DECODERS.clear();
    }
}
