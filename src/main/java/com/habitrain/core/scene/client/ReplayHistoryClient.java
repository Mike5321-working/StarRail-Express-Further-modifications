package com.habitrain.core.scene.client;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.network.replay.ReplayArchiveData;
import com.habitrain.core.network.replay.ReplayArchivePackets;
import com.habitrain.core.network.replay.ReplayArchivePackets.*;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.replay.ReplayVoiceData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static com.habitrain.core.network.replay.ReplayArchivePackets.*;

public final class ReplayHistoryClient {
    private static final long RETRY_NS = 3_000_000_000L, WATCH_NS = 2_000_000_000L;
    private static final Map<UUID, Round> rounds = new LinkedHashMap<>(8, .75f, true);
    private static Settings settings;
    private static VoiceRecordingSettings voiceSettings;
    private static UUID sharing;
    private static int shareResult;
    private static long shareDeadline, nextSend;
    private static final Map<BlockPos, Long> lastReady = new HashMap<>();

    public enum Phase { LOADING, MAP, READY, MISSING, FAILED }
    private static final class Round {
        final UUID id;
        long watched, requested, voiceRequested;
        int attempts;
        Path path, voicePath;
        byte[] cached, voiceBuffer;
        BitSet voiceHave;
        boolean reading = true, readingVoice = true, voiceReady;
        Phase phase = Phase.LOADING;
        LastMatchReplay replay;
        ReplayTimeline timeline;
        ClientReplayBoard.MapData map;
        ReplayVoiceData voice = ReplayVoiceData.EMPTY;
        Round(UUID id) { this.id = id; }
    }
    private ReplayHistoryClient() {}

    static void register() {
        com.habitrain.core.api.client.replay.MatchReplayClientApi.install(
                com.habitrain.core.scene.client.replay.ReplayHistoryScreen::open);
        ClientPlayNetworking.registerGlobalReceiver(Reply.TYPE, (r, ctx) -> ctx.client().execute(() -> onReply(r)));
        ClientPlayNetworking.registerGlobalReceiver(Summary.TYPE, (r, ctx) -> ctx.client().execute(() -> onSummary(r.data())));
        ClientPlayNetworking.registerGlobalReceiver(Section.TYPE, (r, ctx) -> ctx.client().execute(() -> {
            var round = rounds.get(r.data().roundId());
            if (round != null && round.map != null) round.map.accept(r.data());
        }));
        ClientPlayNetworking.registerGlobalReceiver(Voice.TYPE, (r, ctx) -> ctx.client().execute(() -> onVoice(r.data())));
        ClientPlayNetworking.registerGlobalReceiver(Settings.TYPE, (r, ctx) -> ctx.client().execute(() -> settings = r));
        ClientPlayNetworking.registerGlobalReceiver(VoiceRecordingSettings.TYPE, (r, ctx) -> ctx.client().execute(() -> voiceSettings = r));
        ClientTickEvents.END_CLIENT_TICK.register(ReplayHistoryClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            rounds.clear();
            lastReady.clear();
            settings = null;
            voiceSettings = null;
            sharing = null;
            nextSend = 0;
        });
    }

    public static boolean supported() { return ClientPlayNetworking.canSend(Request.TYPE); }
    public static Settings settings() { return settings; }
    public static VoiceRecordingSettings voiceSettings() { return voiceSettings; }
    public static boolean canSetVoiceRecording() { return ClientPlayNetworking.canSend(SetVoiceRecording.TYPE); }
    public static void setVoiceRecording(boolean enabled) {
        if (canSetVoiceRecording()) ClientPlayNetworking.send(new SetVoiceRecording(enabled));
    }
    public static void requestSettings(long value) {
        if (supported()) ClientPlayNetworking.send(Request.settings(value == 0 ? STATS : LIMIT, value));
    }

    public static void retry(UUID id) { rounds.remove(id); }
    public static Phase phase(UUID id) {
        var r = rounds.get(id);
        return r == null ? Phase.LOADING : r.phase;
    }

    public static ClientReplayBoard.View watch(UUID id) {
        Round r = rounds.get(id);
        if (r == null && supported()) {
            while (rounds.size() >= 6) rounds.remove(rounds.keySet().iterator().next());
            r = new Round(id);
            rounds.put(id, r);
            load(r);
        }
        if (r == null) return new ClientReplayBoard.View(null, null, null, 0, 0, false);
        r.watched = System.nanoTime();
        var map = r.map;
        return new ClientReplayBoard.View(r.replay, r.timeline, map == null ? null : map.asset(),
                map == null ? 0 : map.parts.size(), map == null ? 0 : map.manifest.sections().size(), true,
                r.voice, r.voiceReady);
    }

    private static void load(Round r) {
        Minecraft mc = Minecraft.getInstance();
        Path dir = mc.gameDirectory.toPath().resolve("habitrain-cache").resolve("replay-rounds-v1");
        r.path = dir.resolve(r.id + ".bin");
        r.voicePath = dir.resolve(r.id + ".voice");
        Util.ioPool().execute(() -> {
            byte[] contents = null;
            try {
                if (Files.isRegularFile(r.path, LinkOption.NOFOLLOW_LINKS)) {
                    try (var input = Files.newInputStream(r.path, LinkOption.NOFOLLOW_LINKS)) {
                        contents = input.readNBytes(ReplayPackets.MAX_SUMMARY + 1);
                        if (contents.length > ReplayPackets.MAX_SUMMARY) contents = null;
                    }
                }
            } catch (IOException ignored) { }
            ReplayVoiceData cachedVoice = null;
            boolean hasVoiceCache = false;
            try {
                if (Files.isRegularFile(r.voicePath, LinkOption.NOFOLLOW_LINKS)) {
                    try (var input = Files.newInputStream(r.voicePath, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] raw = input.readNBytes(ReplayVoiceData.MAX_VOICE_BYTES + 1);
                        if (raw.length == 0 || (raw.length >= 12 && raw.length <= ReplayVoiceData.MAX_VOICE_BYTES)) {
                            cachedVoice = ReplayVoiceData.fromBytes(raw);
                            hasVoiceCache = true;
                        }
                    } catch (RuntimeException ignored) { }
                }
            } catch (IOException ignored) { }
            byte[] found = contents;
            ReplayVoiceData voiceData = cachedVoice;
            boolean voiceOk = hasVoiceCache;
            mc.execute(() -> {
                r.cached = found;
                r.reading = false;
                if (voiceOk && voiceData != null) {
                    r.voice = voiceData;
                    r.voiceReady = true;
                }
                r.readingVoice = false;
            });
        });
    }

    private static void saveVoice(Round r, byte[] data) {
        if (r.voicePath == null || data == null) return;
        Util.ioPool().execute(() -> {
            Path tmp = null;
            try {
                Files.createDirectories(r.voicePath.getParent());
                tmp = Files.createTempFile(r.voicePath.getParent(), "voice-", ".part");
                Files.write(tmp, data);
                Files.move(tmp, r.voicePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException ignored) {
                // A read-only client directory should not prevent playback.
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        });
    }

    private static void save(Round r, ReplayPackets.LastMatchSummaryS2C data) {
        Util.ioPool().execute(() -> {
            Path tmp = null;
            try {
                Files.createDirectories(r.path.getParent());
                tmp = Files.createTempFile(r.path.getParent(), "replay-", ".part");
                Files.write(tmp, ReplayArchiveData.encode(data));
                Files.move(tmp, r.path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException ignored) {
                // A read-only client directory should not prevent playback.
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        });
    }

    private static void onVoice(ReplayPackets.ReplayVoiceChunkS2C chunk) {
        Round r = rounds.get(chunk.roundId());
        if (r == null || r.voiceReady) return;
        r.voiceRequested = System.nanoTime();
        if (chunk.total() == 0) {
            r.voice = ReplayVoiceData.EMPTY;
            r.voiceReady = true;
            r.voiceBuffer = null;
            r.voiceHave = null;
            saveVoice(r, ReplayVoiceData.EMPTY.toBytes());
            return;
        }
        if (r.voiceBuffer == null || r.voiceBuffer.length != chunk.total()) {
            if (chunk.offset() != 0) return;
            r.voiceBuffer = new byte[chunk.total()];
            r.voiceHave = new BitSet();
        }
        int part = chunk.offset() / ReplayPackets.FRAGMENT_BYTES;
        if (r.voiceHave.get(part)) return;
        System.arraycopy(chunk.bytes(), 0, r.voiceBuffer, chunk.offset(), chunk.bytes().length);
        r.voiceHave.set(part);
        int totalParts = (chunk.total() + ReplayPackets.FRAGMENT_BYTES - 1) / ReplayPackets.FRAGMENT_BYTES;
        if (r.voiceHave.cardinality() != totalParts) return;
        byte[] assembled = r.voiceBuffer;
        r.voiceBuffer = null;
        r.voiceHave = null;
        try {
            r.voice = ReplayVoiceData.fromBytes(assembled);
            saveVoice(r, assembled);
            r.voiceReady = true;
        } catch (RuntimeException malformed) {
            r.voice = ReplayVoiceData.EMPTY;
        }
    }

    private static void onReply(Reply result) {
        if (result.round().equals(sharing) && (result.status() == SHARED || result.status() == NO_TABLE || result.status() == TABLE_BUSY || result.status() == SHARE_ERROR)) {
            shareResult = result.status();
            return;
        }
        Round r = rounds.get(result.round());
        if (r == null) return;
        if (r.phase == Phase.READY) return;
        switch (result.status()) {
            case CACHED -> {
                try { install(r, ReplayArchiveData.decode(r.cached)); }
                catch (RuntimeException invalid) { r.cached = null; r.requested = 0; r.attempts = 0; }
            }
            case MISSING -> r.phase = Phase.MISSING;
            case ERROR -> r.phase = Phase.FAILED;
        }
    }

    private static void onSummary(ReplayPackets.LastMatchSummaryS2C data) {
        Round r = rounds.get(data.replay().roundId());
        if (r == null || r.replay != null) return;
        try {
            install(r, data);
            save(r, data);
        } catch (RuntimeException malformed) {
            r.phase = Phase.FAILED;
        }
    }

    private static void install(Round r, ReplayPackets.LastMatchSummaryS2C data) {
        if (!r.id.equals(data.replay().roundId()) || data.replay().manifest() == null) {
            throw new IllegalArgumentException("Unexpected replay archive round");
        }
        r.replay = ClientReplayBoard.expand(data);
        r.timeline = new ReplayTimeline(r.replay);
        r.map = ClientReplayBoard.loadMap(r.replay.manifest());
        r.cached = null;
        r.phase = Phase.MAP;
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || !supported()) return;
        long now = System.nanoTime();
        watchPreparingBoards(mc, now);
        // Most recently watched view first. One request at a time avoids trampling a map batch
        // with another table's summary request while several boards are visible.
        for (Round r : List.copyOf(rounds.values()).reversed()) {
            if (now - r.watched > WATCH_NS) continue;
            if (r.phase == Phase.LOADING && !r.reading && (r.requested == 0 || now - r.requested >= RETRY_NS)) {
                if (r.attempts >= 12) { r.phase = Phase.FAILED; continue; }
                if (send(Request.lookup(r.id, r.cached == null ? "" : ReplayArchiveData.digest(r.cached)), now)) {
                    r.requested = now;
                    r.attempts++;
                }
            } else if (r.phase == Phase.MAP) {
                if (r.map.complete()) {
                    r.phase = Phase.READY;
                    r.map.step(now, bits -> false);
                } else {
                    r.map.step(now, bits -> send(Request.sections(r.id, bits), now));
                }
            } else if (r.phase == Phase.READY && !r.voiceReady && !r.readingVoice
                    && (r.voiceRequested == 0 || now - r.voiceRequested >= RETRY_NS)) {
                if (send(Request.voice(r.id), now)) {
                    r.voiceRequested = now;
                }
            }
        }
    }

    private static void watchPreparingBoards(Minecraft mc, long now) {
        if (mc.player == null) return;
        lastReady.entrySet().removeIf(e -> now - e.getValue() > 3_000_000_000L);
        for (BlockPos pos : ClientReplayBoard.loadedBoards()) {
            if (mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 48 * 48
                    || !(mc.level.getBlockEntity(pos) instanceof com.habitrain.core.scene.block.SettlementSummaryBoard.Marker marker)) continue;
            var playback = marker.playback();
            if (playback == null || !playback.preparing()) continue;
            watch(playback.roundId());
            if (phase(playback.roundId()) == Phase.READY && !lastReady.containsKey(pos)) {
                ClientPlayNetworking.send(Request.boardReady(playback.roundId(), pos));
                lastReady.put(pos, now);
            }
        }
    }

    private static boolean send(Request request, long now) {
        if (nextSend != 0 && now - nextSend < 0) return false;
        ClientPlayNetworking.send(request);
        nextSend = now + 250_000_000L;
        return true;
    }

    public static void share(UUID id, BlockPos board) {
        if (!supported()) return;
        sharing = id;
        shareResult = READY; // waiting
        shareDeadline = System.nanoTime() + 4_000_000_000L;
        ClientPlayNetworking.send(Request.share(id, board));
    }

    public static int shareStatus(UUID id) {
        if (!id.equals(sharing)) return -1;
        if (shareResult == READY && System.nanoTime() - shareDeadline >= 0) return ERROR;
        return shareResult;
    }
}
