package com.habitrain.core.game.sre.replay;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.drone.DroneOperatorService;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.role.capability.ServerVoicePermissions;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
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
 * Optional Simple Voice Chat plugin that captures full-match multi-track voice packets during active
 * SRE matches (open proximity, whisper, radio, killer private channel, and voice groups) and forwards
 * decoded 48 kHz mono PCM frames to {@link MatchVoiceRecorder}.
 *
 * <p>Loaded exclusively through the {@code voicechat} entrypoint so environments without Simple
 * Voice Chat never touch any {@code de.maxhenkel.voicechat.*} class.</p>
 */
public final class ReplayVoicePlugin implements VoicechatPlugin {
    public static final String PLUGIN_ID = "habitrain_core_replay_voice";

    private static final ConcurrentHashMap<UUID, DecoderState> DECODERS = new ConcurrentHashMap<>();
    private static volatile @Nullable VoicechatServerApi serverApi;
    private static volatile boolean acceptingPackets;
    private static volatile float cachedDefaultDist = 48.0f;
    private static volatile float cachedWhisperDist = 16.0f;
    private static volatile long lastDistRefreshNanos;
    private static final long DIST_REFRESH_INTERVAL_NS = 5_000_000_000L;

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
        registration.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
        registration.registerEvent(LeaveGroupEvent.class, this::onLeaveGroup);
        // Voicechat sorts larger priorities first. Run after the upstream SRE plugins so their
        // cancellation decisions are final before a packet enters the replay recorder.
        registration.registerEvent(MicrophonePacketEvent.class, this::onMic, Integer.MIN_VALUE);
        HabiTrainCore.LOGGER.info("[ReplayVoice] Simple Voice Chat HRVC v2 multi-track recorder hook registered");
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
        DecoderState decoder = DECODERS.computeIfAbsent(speaker, ignored -> new DecoderState(api.createDecoder()));
        if (opusData == null || opusData.length == 0) {
            decoder.resetState();
            return;
        }
        short[] pcm48k = decoder.decode(opusData);
        if (pcm48k == null || pcm48k.length == 0) return;
        float maxDist = maxDistOverride > 0 ? maxDistOverride : resolveMaxDistance(api, whispering);
        ReplayVoiceData.ChannelMode mode = whispering
                ? ReplayVoiceData.ChannelMode.WHISPER : ReplayVoiceData.ChannelMode.NORMAL_OPEN;
        MatchVoiceRecorder.recordPcm48kAtMs(roundId, speaker, matchMs, maxDist, mode, null, pcm48k);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        acceptingPackets = true;
        lastDistRefreshNanos = 0L;
        if (serverApi != null) {
            refreshVoiceDistances(serverApi, true);
        }
    }

    private void onServerStopped(VoicechatServerStoppedEvent event) {
        acceptingPackets = false;
        serverApi = null;
        clearDecoders();
    }

    private void onDisconnect(PlayerDisconnectedEvent event) {
        UUID id = event.getPlayerUuid();
        MatchVoiceRecorder.updateLiveGroup(id, null, false);
        DecoderState decoder = DECODERS.remove(id);
        if (decoder != null) decoder.close();
    }

    private void onJoinGroup(JoinGroupEvent event) {
        try {
            VoicechatConnection conn = event.getConnection();
            Group group = event.getGroup();
            if (conn != null && conn.getPlayer() != null && group != null) {
                MatchVoiceRecorder.updateLiveGroup(conn.getPlayer().getUuid(),
                        group.getId(), group.getType() == Group.Type.OPEN);
            }
        } catch (RuntimeException ignored) {}
    }

    private void onLeaveGroup(LeaveGroupEvent event) {
        try {
            VoicechatConnection conn = event.getConnection();
            if (conn != null && conn.getPlayer() != null) {
                MatchVoiceRecorder.updateLiveGroup(conn.getPlayer().getUuid(), null, false);
            }
        } catch (RuntimeException ignored) {}
    }

    private void onMic(MicrophonePacketEvent event) {
        try {
            if (!acceptingPackets || event.isCancelled() || !MatchVoiceRecorder.isRecording()) return;
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

            Group group = sender.getGroup();
            UUID groupId = group == null ? null : group.getId();
            boolean openGroup = group != null && group.getType() == Group.Type.OPEN;

            if (event.isCancelled()) return;

            MicrophonePacket packet = event.getPacket();
            if (packet == null) return;
            byte[] opus = packet.getOpusEncodedData();
            VoicechatServerApi activeApi = api;
            DecoderState decoder = DECODERS.computeIfAbsent(speaker,
                    ignored -> new DecoderState(activeApi.createDecoder()));
            if (decoder.noteGroupChange(groupId, openGroup)) {
                MatchVoiceRecorder.updateLiveGroup(speaker, groupId, openGroup);
            }
            if (opus == null || opus.length == 0) {
                decoder.resetState();
                return;
            }
            short[] pcm48k = decoder.decode(opus);
            if (pcm48k == null || pcm48k.length == 0) return;

            // Classify channel mode for HRVC v2 multi-track routing (offline ReplayVoiceAudibilityResolver
            // enforces strict per-listener access control for RADIO, KILLER_PRIVATE, GROUP, and WHISPER).
            MatchVoiceRecorder.ChannelHint hint = MatchVoiceRecorder.channelHint(speaker);
            ReplayVoiceData.ChannelMode channelMode;
            UUID effectiveGroupId = participant.group();
            if (hint.radio()) {
                channelMode = ReplayVoiceData.ChannelMode.RADIO;
            } else if (hint.killerPrivate()) {
                channelMode = ReplayVoiceData.ChannelMode.KILLER_PRIVATE;
            } else if (permissions.isPrivateChannel(speaker)) {
                channelMode = ReplayVoiceData.ChannelMode.RADIO;
            } else if (group != null && group.getType() != Group.Type.OPEN) {
                channelMode = ReplayVoiceData.ChannelMode.GROUP;
                effectiveGroupId = group.getId();
            } else if (packet.isWhispering()) {
                channelMode = ReplayVoiceData.ChannelMode.WHISPER;
                if (effectiveGroupId == null && group != null) effectiveGroupId = group.getId();
            } else {
                channelMode = ReplayVoiceData.ChannelMode.NORMAL_OPEN;
                if (effectiveGroupId == null && group != null) effectiveGroupId = group.getId();
            }

            refreshVoiceDistances(api, false);
            float maxDist = packet.isWhispering() ? cachedWhisperDist : cachedDefaultDist;
            MatchVoiceRecorder.recordPcm48k(round, speaker, maxDist, channelMode, effectiveGroupId, pcm48k);
        } catch (RuntimeException failure) {
            HabiTrainCore.LOGGER.debug("[ReplayVoice] microphone packet was not captured", failure);
        } catch (Error failure) {
            HabiTrainCore.LOGGER.error("[ReplayVoice] decoder failure while capturing microphone packet", failure);
        }
    }

    private static void refreshVoiceDistances(VoicechatServerApi api, boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastDistRefreshNanos < DIST_REFRESH_INTERVAL_NS) return;
        lastDistRefreshNanos = now;
        float def = resolveMaxDistance(api, false);
        float whisp = resolveMaxDistance(api, true);
        cachedDefaultDist = def;
        cachedWhisperDist = whisp;
        MatchVoiceRecorder.updateVoiceDistances(def, whisp);
    }

    private static float resolveMaxDistance(VoicechatServerApi api, boolean whispering) {
        double base = api.getVoiceChatDistance();
        if (whispering && api.getServerConfig() != null) {
            return (float) api.getServerConfig().getDouble("whisper_distance", base * 0.5);
        }
        return (float) base;
    }

    private static void clearDecoders() {
        for (DecoderState decoder : DECODERS.values()) decoder.close();
        DECODERS.clear();
    }

    private static final class DecoderState {
        private final OpusDecoder decoder;
        private boolean closed;
        private boolean groupInitialized;
        private @Nullable UUID lastGroupId;
        private boolean lastOpenGroup;

        private DecoderState(OpusDecoder decoder) {
            this.decoder = decoder;
        }

        synchronized boolean noteGroupChange(@Nullable UUID groupId, boolean openGroup) {
            if (groupInitialized && java.util.Objects.equals(lastGroupId, groupId) && lastOpenGroup == openGroup) {
                return false;
            }
            groupInitialized = true;
            lastGroupId = groupId;
            lastOpenGroup = openGroup;
            return true;
        }

        synchronized short[] decode(byte[] opus) {
            if (closed) return null;
            return decoder.decode(opus);
        }

        synchronized void resetState() {
            if (!closed) decoder.resetState();
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                decoder.close();
            } catch (RuntimeException failure) {
                HabiTrainCore.LOGGER.debug("[ReplayVoice] could not close an Opus decoder", failure);
            }
        }
    }
}
