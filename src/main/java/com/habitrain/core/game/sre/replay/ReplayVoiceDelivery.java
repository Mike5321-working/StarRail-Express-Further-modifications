package com.habitrain.core.game.sre.replay;

import com.habitrain.core.network.replay.ReplayPackets.LastMatchSummaryS2C;
import com.habitrain.core.network.replay.ReplayTracks;
import com.habitrain.core.scene.replay.ReplayVoiceAudibilityResolver;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import com.habitrain.core.scene.replay.ReplayVoiceData.VoiceUtterance;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class ReplayVoiceDelivery {
    private ReplayVoiceDelivery() {}

    static byte[] forRecipient(LastMatchSummaryS2C summary, byte[] stored, UUID recipient, boolean admin) {
        if (stored.length == 0 || admin) return stored;
        if (!ReplayArchiveService.canExportVoice(summary.replay(), recipient, false)) return new byte[0];
        return prepare(summary, stored).forRecipient(recipient);
    }

    static Prepared prepare(LastMatchSummaryS2C summary, byte[] stored) {
        var recording = ReplayVoiceData.fromBytes(stored);
        var context = recording.hasMultitrack()
                ? ReplayVoiceAudibilityResolver.Context.of(ReplayTracks.expand(summary), recording)
                : null;
        return new Prepared(recording, context);
    }

    record Prepared(ReplayVoiceData recording, @Nullable ReplayVoiceAudibilityResolver.Context context) {
        byte[] forRecipient(UUID recipient) {
            if (context == null) return recording.truncated()
                    ? new ReplayVoiceData(List.of(), ReplayVoiceData.VoiceTimelineMeta.EMPTY, List.of(), true).toBytes()
                    : new byte[0];
            return filter(recording, context, recipient);
        }
    }

    private static byte[] filter(ReplayVoiceData recording, ReplayVoiceAudibilityResolver.Context context, UUID recipient) {
        var allowed = new ArrayList<VoiceUtterance>();
        long remaining = ReplayVoiceData.MAX_VOICE_BYTES - 2L * 1024 * 1024;
        boolean truncated = recording.truncated();
        for (var utterance : recording.utterances()) {
            if (Thread.currentThread().isInterrupted()) return new byte[0];
            short[] samples = null;
            for (var slice : context.deliverySlices(utterance, recipient)) {
                int start = Math.toIntExact((slice.startMs() - utterance.startMs()) * 24);
                int end = Math.min(utterance.sampleCount24k(), Math.toIntExact((slice.endMs() - utterance.startMs()) * 24));
                boolean full = start == 0 && slice.endMs() == utterance.endMs();
                if (full) end = utterance.sampleCount24k();
                if (end <= start) continue;
                int cost = ReplayVoiceData.expectedAdpcmBytes(end - start) + 48;
                if (cost > remaining || allowed.size() >= ReplayVoiceData.MAX_UTTERANCES) {
                    truncated = true;
                    continue;
                }
                remaining -= cost;
                if (full) {
                    allowed.add(utterance);
                } else {
                    if (samples == null) samples = utterance.decode24k();
                    var trimmed = Arrays.copyOfRange(samples, start, end);
                    allowed.add(new VoiceUtterance(utterance.speakerId(), slice.startMs(), trimmed.length,
                            utterance.baseMaxDist(), utterance.channelMode(), utterance.groupId(),
                            ReplayVoiceData.encodeAdpcm(trimmed), ReplayVoiceData.computeNormalizedRms(trimmed)));
                }
            }
        }
        return allowed.isEmpty() && !truncated ? new byte[0]
                : new ReplayVoiceData(allowed, recording.timelineMeta(), List.of(), truncated).toBytes();
    }
}
