package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import com.habitrain.core.api.scene.model.SceneBounds;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Authoritative server-side global match sound recorder (replaces fragile client-polled kill sound capture).
 *
 * <p>Captures {@code ServerLevel.playSeededSound}, {@code ServerLevel.levelEvent},
 * {@code ServerPlayer.playNotifySound}, and direct mod {@code ClientboundSoundPacket} /
 * {@code ClientboundSoundEntityPacket} dispatches within the match play area, deduplicating
 * multi-recipient broadcasts and tagging private vs. global audibility scopes.</p>
 */
public final class GlobalMatchSoundRecorder {
    private static final double PLAY_AREA_MARGIN = 32.0;
    private static final int MAX_RAW_CUES = ReplayGameSoundData.MAX_CUES;
    private static final int FOOTSTEP_RESERVE_THRESHOLD = 24_000;

    private static final ThreadLocal<int[]> SUPPRESS_DIRECT_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile @Nullable ActiveSession active;

    private GlobalMatchSoundRecorder() {}

    public static boolean isRecording() {
        return active != null;
    }

    public static void enterLevelOrNotifySound() {
        if (active == null) return;
        SUPPRESS_DIRECT_DEPTH.get()[0]++;
    }

    public static void exitLevelOrNotifySound() {
        if (active == null) return;
        int[] box = SUPPRESS_DIRECT_DEPTH.get();
        if (box[0] > 0) box[0]--;
    }

    public static boolean isSuppressingDirectPackets() {
        if (active == null) return false;
        return SUPPRESS_DIRECT_DEPTH.get()[0] > 0;
    }

    public static void begin(UUID roundId, ServerLevel level, SceneBounds bounds, long activeStartGameTime) {
        SUPPRESS_DIRECT_DEPTH.get()[0] = 0;
        active = new ActiveSession(roundId, level, bounds, activeStartGameTime);
    }

    /** Test-only initializer without a live ServerLevel. */
    static void beginTest(UUID roundId, SceneBounds bounds) {
        SUPPRESS_DIRECT_DEPTH.get()[0] = 0;
        active = new ActiveSession(roundId, null, bounds, 0L);
    }

    public static void reset() {
        if (active != null) SUPPRESS_DIRECT_DEPTH.get()[0] = 0;
        active = null;
    }

    public static void flushTick(ServerLevel level, int matchTick) {
        ActiveSession s = active;
        if (s == null) return;
        SUPPRESS_DIRECT_DEPTH.get()[0] = 0;
        if (s.level == level) {
            s.flushPendingBatches(matchTick);
        }
    }

    public static ReplayGameSoundData finish(UUID roundId) {
        ActiveSession s = active;
        if (s == null || !s.roundId.equals(roundId)) return ReplayGameSoundData.EMPTY;
        active = null;
        return s.build();
    }

    /** Returns the most recent non-footstep sound ID near {@code actorPos} within ±2 ticks (for fallback skill attribution). */
    public static String recentSoundNear(UUID roundId, int matchTick, @Nullable LastMatchReplay.Position actorPos) {
        ActiveSession s = active;
        if (s == null || !s.roundId.equals(roundId)) return "";
        return s.recentSoundNear(matchTick, actorPos);
    }

    // ===== Server Mixin Entry Points =====

    public static void onPlaySeededSound(ServerLevel level, @Nullable Player except,
                                         double x, double y, double z,
                                         Holder<SoundEvent> sound, SoundSource source,
                                         float volume, float pitch) {
        ActiveSession s = active;
        if (s == null || s.level != level || !level.getServer().isSameThread()) return;
        if (sound == null || volume <= 0.005f) return;
        ResourceLocation id = sound.value().getLocation();
        if (id == null) return;
        s.recordWorldSound(s.currentMatchMs(), id.toString(), categoryByte(source),
                x, y, z, volume, pitch, except == null ? null : except.getUUID(),
                ReplayGameSoundData.AudibilityScope.WORLD_3D);
    }

    public static void onPlaySeededEntitySound(ServerLevel level, @Nullable Player except,
                                               Entity entity, Holder<SoundEvent> sound, SoundSource source,
                                               float volume, float pitch) {
        ActiveSession s = active;
        if (s == null || s.level != level || !level.getServer().isSameThread()) return;
        if (entity == null || sound == null || volume <= 0.005f) return;
        ResourceLocation id = sound.value().getLocation();
        if (id == null) return;
        UUID actor = entity instanceof Player p ? p.getUUID() : (except == null ? null : except.getUUID());
        s.recordWorldSound(s.currentMatchMs(), id.toString(), categoryByte(source),
                entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
                volume, pitch, actor, ReplayGameSoundData.AudibilityScope.WORLD_3D);
    }

    public static void onLevelEvent(ServerLevel level, @Nullable Player except, int type, BlockPos pos, int data) {
        ActiveSession s = active;
        if (s == null || s.level != level || pos == null || !level.getServer().isSameThread()) return;
        String soundId = resolveLevelEventSound(type, data);
        if (soundId == null) return;
        float volume = (type == 2001) ? 0.85f : 1.0f;
        float pitch = (type == 1005 || type == 1006 || type == 1011 || type == 1012) ? 0.95f : 1.0f;
        s.recordWorldSound(s.currentMatchMs(), soundId, ReplayGameSoundData.CAT_BLOCKS,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                volume, pitch, except == null ? null : except.getUUID(),
                ReplayGameSoundData.AudibilityScope.WORLD_3D);
    }

    public static void onPlayNotifySound(ServerPlayer player, SoundEvent sound, SoundSource source,
                                         float volume, float pitch) {
        ActiveSession s = active;
        if (s == null || player == null || sound == null || volume <= 0.005f) return;
        ServerLevel level = player.serverLevel();
        if (s.level != level || !level.getServer().isSameThread()) return;
        ResourceLocation id = sound.getLocation();
        if (id == null) return;
        s.recordNotifySound(s.currentMatchMs(), player, id.toString(), categoryByte(source), volume, pitch);
    }

    public static void onDirectSoundPacket(ServerPlayer receiver, Packet<?> packet) {
        if (isSuppressingDirectPackets() || receiver == null || packet == null) return;
        ActiveSession s = active;
        if (s == null) return;
        ServerLevel level = receiver.serverLevel();
        if (s.level != level || !level.getServer().isSameThread()) return;

        if (packet instanceof ClientboundSoundPacket sp) {
            if (sp.getSound() == null || sp.getVolume() <= 0.005f) return;
            ResourceLocation id = sp.getSound().value().getLocation();
            if (id == null) return;
            s.recordDirectPacket(s.currentMatchMs(), receiver, id.toString(), categoryByte(sp.getSource()),
                    sp.getX(), sp.getY(), sp.getZ(), sp.getVolume(), sp.getPitch(), null);
        } else if (packet instanceof ClientboundSoundEntityPacket sep) {
            if (sep.getSound() == null || sep.getVolume() <= 0.005f) return;
            ResourceLocation id = sep.getSound().value().getLocation();
            if (id == null) return;
            Entity entity = level.getEntity(sep.getId());
            Vec3 pos = entity != null ? entity.position() : receiver.position();
            UUID actor = entity instanceof Player p ? p.getUUID() : null;
            s.recordDirectPacket(s.currentMatchMs(), receiver, id.toString(), categoryByte(sep.getSource()),
                    pos.x, pos.y, pos.z, sep.getVolume(), sep.getPitch(), actor);
        }
    }

    /** Deterministic injection hook for unit tests. */
    static void recordForTest(UUID roundId, int matchMs, String soundId, byte category,
                              double worldX, double worldY, double worldZ,
                              float volume, float pitch, @Nullable UUID actor,
                              ReplayGameSoundData.AudibilityScope scope) {
        ActiveSession s = active;
        if (s == null || !s.roundId.equals(roundId)) return;
        s.recordWorldSound(matchMs, soundId, category, worldX, worldY, worldZ, volume, pitch, actor, scope);
    }

    private static @Nullable String resolveLevelEventSound(int type, int data) {
        return switch (type) {
            case 1005 -> "minecraft:block.iron_door.open";
            case 1006 -> "minecraft:block.wooden_door.open";
            case 1007 -> "minecraft:block.wooden_trapdoor.open";
            case 1008 -> "minecraft:block.fence_gate.open";
            case 1009 -> "minecraft:block.fire.extinguish";
            case 1011 -> "minecraft:block.iron_door.close";
            case 1012 -> "minecraft:block.wooden_door.close";
            case 1013 -> "minecraft:block.wooden_trapdoor.close";
            case 1014 -> "minecraft:block.fence_gate.close";
            case 1036 -> "minecraft:block.iron_trapdoor.close";
            case 1037 -> "minecraft:block.iron_trapdoor.open";
            case 2001 -> {
                BlockState state = Block.stateById(data);
                ResourceLocation loc = state.getSoundType().getBreakSound().getLocation();
                yield loc == null ? "minecraft:block.stone.break" : loc.toString();
            }
            case 2002, 2007 -> "minecraft:entity.splash_potion.break";
            default -> null;
        };
    }

    public static byte categoryByte(@Nullable SoundSource source) {
        if (source == null) return ReplayGameSoundData.CAT_MASTER;
        return switch (source) {
            case MASTER -> ReplayGameSoundData.CAT_MASTER;
            case MUSIC -> ReplayGameSoundData.CAT_MUSIC;
            case RECORDS -> ReplayGameSoundData.CAT_RECORDS;
            case WEATHER -> ReplayGameSoundData.CAT_WEATHER;
            case BLOCKS -> ReplayGameSoundData.CAT_BLOCKS;
            case HOSTILE -> ReplayGameSoundData.CAT_HOSTILE;
            case NEUTRAL -> ReplayGameSoundData.CAT_NEUTRAL;
            case PLAYERS -> ReplayGameSoundData.CAT_PLAYERS;
            case AMBIENT -> ReplayGameSoundData.CAT_AMBIENT;
            case VOICE -> ReplayGameSoundData.CAT_VOICE;
        };
    }

    private static boolean isLowPriorityFootstep(String soundId) {
        return soundId.endsWith(".step") || soundId.contains("footstep");
    }

    private static boolean isAlwaysGlobalSound(String soundId) {
        return soundId.contains("train.horn") || soundId.contains("piano_win")
                || soundId.contains("blackout") || soundId.contains("psycho");
    }

    private record NotifyKey(int matchMs, String soundId, byte cat, int packedVol, int packedPitch) {}
    private record DirectKey(int matchMs, String soundId, byte cat, int packedVol, int packedPitch) {}

    private static final class DirectBatch {
        final List<ServerPlayer> recipients = new ArrayList<>(4);
        final List<Vec3> positions = new ArrayList<>(4);
        @Nullable UUID actor;
    }

    private static final class ActiveSession {
        final UUID roundId;
        final @Nullable ServerLevel level;
        final SceneBounds bounds;
        final long activeStartGameTime;

        final List<String> dict = new ArrayList<>();
        final Map<String, Integer> dictIndex = new HashMap<>();
        final List<ReplayGameSoundData.Cue> cues = new ArrayList<>();
        final Map<Long, Integer> recentDedup = new HashMap<>();

        final Map<NotifyKey, List<ServerPlayer>> pendingNotify = new LinkedHashMap<>();
        final Map<DirectKey, DirectBatch> pendingDirect = new LinkedHashMap<>();

        ActiveSession(UUID roundId, @Nullable ServerLevel level, SceneBounds bounds, long activeStartGameTime) {
            this.roundId = roundId;
            this.level = level;
            this.bounds = bounds;
            this.activeStartGameTime = activeStartGameTime;
        }

        int currentMatchMs() {
            if (level == null) return 0;
            long ticks = Math.max(0L, level.getGameTime() - activeStartGameTime);
            return (int) Math.min(ticks * 50L, Integer.MAX_VALUE);
        }

        boolean inPlayArea(double x, double y, double z) {
            return x >= bounds.minX() - PLAY_AREA_MARGIN && x <= bounds.maxX() + PLAY_AREA_MARGIN
                    && y >= bounds.minY() - PLAY_AREA_MARGIN && y <= bounds.maxY() + PLAY_AREA_MARGIN
                    && z >= bounds.minZ() - PLAY_AREA_MARGIN && z <= bounds.maxZ() + PLAY_AREA_MARGIN;
        }

        int internSound(String soundId) {
            Integer existing = dictIndex.get(soundId);
            if (existing != null) return existing;
            if (dict.size() >= ReplayGameSoundData.MAX_DICTIONARY) return -1;
            int idx = dict.size();
            dict.add(soundId);
            dictIndex.put(soundId, idx);
            return idx;
        }

        void recordWorldSound(int matchMs, String soundId, byte cat,
                              double worldX, double worldY, double worldZ,
                              float volume, float pitch, @Nullable UUID actor,
                              ReplayGameSoundData.AudibilityScope scope) {
            if (soundId == null || soundId.isEmpty() || cues.size() >= MAX_RAW_CUES) return;
            if (cat == ReplayGameSoundData.CAT_MUSIC && !isAlwaysGlobalSound(soundId)) return;
            if (cues.size() >= FOOTSTEP_RESERVE_THRESHOLD && isLowPriorityFootstep(soundId)) return;
            if (scope != ReplayGameSoundData.AudibilityScope.GLOBAL && !inPlayArea(worldX, worldY, worldZ)) {
                if (isAlwaysGlobalSound(soundId)) {
                    scope = ReplayGameSoundData.AudibilityScope.GLOBAL;
                } else {
                    return;
                }
            }
            LastMatchReplay.Position rel = LastMatchReplay.Position.quantize(
                    worldX - bounds.minX(), worldY - bounds.minY(), worldZ - bounds.minZ());
            if (rel == null) {
                if (scope == ReplayGameSoundData.AudibilityScope.GLOBAL) {
                    rel = new LastMatchReplay.Position((short) 0, (short) 0, (short) 0);
                } else {
                    return;
                }
            }
            int dictId = internSound(soundId);
            if (dictId < 0) return;

            int pVol = ReplayGameSoundData.Cue.packVolume(volume);
            int pPitch = ReplayGameSoundData.Cue.packPitch(pitch);
            if (pVol <= 0) return;

            // Deduplicate identical cues at the same tick & quantized position (within 1 block)
            int tickBucket = matchMs / 50;
            long dedupKey = (((long) dictId & 0xFFFFL) << 44)
                    | (((long) (rel.x() >> 4) & 0x3FFL) << 34)
                    | (((long) (rel.y() >> 4) & 0xFFL) << 26)
                    | (((long) (rel.z() >> 4) & 0x3FFL) << 16)
                    | ((long) scope.ordinal() & 0xFL);
            Integer prevTick = recentDedup.get(dedupKey);
            if (prevTick != null && prevTick == tickBucket) return;
            recentDedup.put(dedupKey, tickBucket);
            if (recentDedup.size() > 4096) recentDedup.clear();

            cues.add(new ReplayGameSoundData.Cue(matchMs, dictId, cat, rel.x(), rel.y(), rel.z(),
                    pVol, pPitch, actor, scope));
        }

        void recordNotifySound(int matchMs, ServerPlayer recipient, String soundId, byte cat,
                               float volume, float pitch) {
            if (cues.size() >= MAX_RAW_CUES) return;
            int pVol = ReplayGameSoundData.Cue.packVolume(volume);
            int pPitch = ReplayGameSoundData.Cue.packPitch(pitch);
            if (pVol <= 0) return;
            NotifyKey key = new NotifyKey(matchMs, soundId, cat, pVol, pPitch);
            pendingNotify.computeIfAbsent(key, ignored -> new ArrayList<>(4)).add(recipient);
        }

        void recordDirectPacket(int matchMs, ServerPlayer recipient, String soundId, byte cat,
                                double x, double y, double z, float volume, float pitch, @Nullable UUID actor) {
            if (cues.size() >= MAX_RAW_CUES) return;
            int pVol = ReplayGameSoundData.Cue.packVolume(volume);
            int pPitch = ReplayGameSoundData.Cue.packPitch(pitch);
            if (pVol <= 0) return;
            DirectKey key = new DirectKey(matchMs, soundId, cat, pVol, pPitch);
            DirectBatch batch = pendingDirect.computeIfAbsent(key, ignored -> new DirectBatch());
            batch.recipients.add(recipient);
            batch.positions.add(new Vec3(x, y, z));
            if (batch.actor == null && actor != null) batch.actor = actor;
        }

        void flushPendingBatches(int matchTick) {
            if (!pendingNotify.isEmpty()) {
                for (var entry : pendingNotify.entrySet()) {
                    NotifyKey k = entry.getKey();
                    List<ServerPlayer> recs = entry.getValue();
                    if (recs.isEmpty()) continue;
                    float vol = k.packedVol / 64.0f;
                    float pitch = 0.25f + k.packedPitch / 128.0f;
                    if (recs.size() >= 3 || isAlwaysGlobalSound(k.soundId)) {
                        ReplayGameSoundData.AudibilityScope scope = allKillers(recs)
                                ? ReplayGameSoundData.AudibilityScope.TEAM_KILLER
                                : ReplayGameSoundData.AudibilityScope.GLOBAL;
                        Vec3 first = recs.getFirst().position();
                        recordWorldSound(k.matchMs, k.soundId, k.cat, first.x, first.y, first.z,
                                vol, pitch, null, scope);
                    } else if (recs.size() == 2 && allKillers(recs)) {
                        Vec3 first = recs.getFirst().position();
                        recordWorldSound(k.matchMs, k.soundId, k.cat, first.x, first.y, first.z,
                                vol, pitch, null, ReplayGameSoundData.AudibilityScope.TEAM_KILLER);
                    } else {
                        for (ServerPlayer p : recs) {
                            recordWorldSound(k.matchMs, k.soundId, k.cat, p.getX(), p.getY(), p.getZ(),
                                    vol, pitch, p.getUUID(), ReplayGameSoundData.AudibilityScope.ACTOR_ONLY);
                        }
                    }
                }
                pendingNotify.clear();
            }

            if (!pendingDirect.isEmpty()) {
                for (var entry : pendingDirect.entrySet()) {
                    DirectKey k = entry.getKey();
                    DirectBatch batch = entry.getValue();
                    if (batch.recipients.isEmpty()) continue;
                    float vol = k.packedVol / 64.0f;
                    float pitch = 0.25f + k.packedPitch / 128.0f;
                    Vec3 firstPos = batch.positions.getFirst();
                    if (batch.recipients.size() == 1) {
                        ServerPlayer only = batch.recipients.getFirst();
                        boolean atRecipient = only.position().distanceToSqr(firstPos) <= 4.0;
                        ReplayGameSoundData.AudibilityScope scope = atRecipient
                                ? ReplayGameSoundData.AudibilityScope.ACTOR_ONLY
                                : ReplayGameSoundData.AudibilityScope.WORLD_3D;
                        recordWorldSound(k.matchMs, k.soundId, k.cat, firstPos.x, firstPos.y, firstPos.z,
                                vol, pitch, batch.actor != null ? batch.actor : only.getUUID(), scope);
                    } else {
                        // Check if all recipients received the exact same world coordinates -> WORLD_3D broadcast
                        boolean sameCoords = true;
                        for (int i = 1; i < batch.positions.size(); i++) {
                            if (batch.positions.get(i).distanceToSqr(firstPos) > 1.0) {
                                sameCoords = false;
                                break;
                            }
                        }
                        ReplayGameSoundData.AudibilityScope scope = sameCoords
                                ? ReplayGameSoundData.AudibilityScope.WORLD_3D
                                : (allKillers(batch.recipients)
                                        ? ReplayGameSoundData.AudibilityScope.TEAM_KILLER
                                        : ReplayGameSoundData.AudibilityScope.GLOBAL);
                        recordWorldSound(k.matchMs, k.soundId, k.cat, firstPos.x, firstPos.y, firstPos.z,
                                vol, pitch, batch.actor, scope);
                    }
                }
                pendingDirect.clear();
            }
        }

        private boolean allKillers(List<ServerPlayer> players) {
            if (level == null || players.isEmpty()) return false;
            try {
                var game = SREGameWorldComponent.KEY.get(level);
                for (ServerPlayer p : players) {
                    var role = game.getRole(p);
                    if (role == null || !role.canUseKiller()) return false;
                }
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        String recentSoundNear(int matchTick, @Nullable LastMatchReplay.Position actorPos) {
            int targetMs = matchTick * 50;
            for (int i = cues.size() - 1; i >= 0; i--) {
                ReplayGameSoundData.Cue c = cues.get(i);
                if (targetMs - c.matchMs() > 150) break;
                String id = dict.get(c.soundDictId());
                if (isLowPriorityFootstep(id)) continue;
                if (actorPos == null) return id;
                double dx = c.blockX() - actorPos.blockX();
                double dy = c.blockY() - actorPos.blockY();
                double dz = c.blockZ() - actorPos.blockZ();
                if (dx * dx + dy * dy + dz * dz <= 16.0 * 16.0) return id;
            }
            return "";
        }

        ReplayGameSoundData build() {
            flushPendingBatches(0);
            if (cues.isEmpty()) return ReplayGameSoundData.EMPTY;
            ReplayGameSoundData full = new ReplayGameSoundData(dict, cues);
            // Enforce compressed summary budget cleanly by trimming footsteps if ever oversized
            byte[] probe = full.toCompressedBytes();
            if (probe.length <= 200 * 1024) return full;
            List<ReplayGameSoundData.Cue> important = new ArrayList<>(cues.size());
            for (ReplayGameSoundData.Cue c : cues) {
                if (!isLowPriorityFootstep(dict.get(c.soundDictId()))) important.add(c);
            }
            ReplayGameSoundData trimmed = new ReplayGameSoundData(dict, important);
            if (trimmed.toCompressedBytes().length <= 200 * 1024) return trimmed;
            // Final stride decimation if a synthetic stress test spams 30k unique non-footstep cues
            int step = Math.max(2, (important.size() + 7999) / 8000);
            List<ReplayGameSoundData.Cue> decimated = new ArrayList<>();
            for (int i = 0; i < important.size(); i += step) decimated.add(important.get(i));
            return new ReplayGameSoundData(dict, decimated);
        }
    }
}
