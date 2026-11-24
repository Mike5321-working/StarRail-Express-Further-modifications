package com.habitrain.core.game.sre.replay;

import com.habitrain.core.api.match.MatchEvents;
import com.habitrain.core.api.match.MatchSettlement;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.replay.*;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.game.sre.match.MatchEventBridge;
import com.habitrain.core.game.sre.match.MvpScoreTracker;
import com.habitrain.core.game.sre.mode.RepairModeManager;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.drone.ReconDroneEntity;
import com.habitrain.core.game.sre.role.idiot.IdiotChickEntity;
import com.habitrain.core.game.sre.role.idiot.IdiotEggEntity;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import com.habitrain.core.network.replay.ReplayTracks;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import com.habitrain.core.scene.server.SceneCaptureService;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.api.replay.ReplayTimelineEvent;
import io.wifi.starrailexpress.api.replay.event.ReplayEventRecordedCallback;
import io.wifi.starrailexpress.cca.*;
import io.wifi.starrailexpress.content.block.SmallDoorBlock;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import io.wifi.starrailexpress.event.OnGameTrueStarted;
import io.wifi.starrailexpress.event.OnPlayerDeathWithKiller;
import io.wifi.starrailexpress.event.OnRevolverUsed;
import io.wifi.starrailexpress.event.OnShieldBroken;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.index.TMMItems;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.cca.C4BackComponent;
import org.agmas.noellesroles.game.roles.killer.morphling.MorphlingPlayerComponent;
import org.agmas.noellesroles.init.ModEffects;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

/** Lives on the server thread; upstream callbacks record high-rate v2 tracks, world deltas, entities, skills, and audio. */
public final class MatchReplayRecorder {
    private static final int DEFAULT_GOLD_COLOR = 0xFFD4AF37;
    private static final Set<String> KNOWN_ADAPTER_TOKENS = Set.of(
            "revolver", "derringer", "sniper", "shotgun", "knife", "throwing_knife", "shuriken", "bat",
            "poison", "syringe", "grenade", "flash_grenade", "smoke_grenade", "incendiary_grenade",
            "slime_grenade", "decoy_grenade", "wall_break_grenade", "timed_grenade", "sticky_grenade",
            "c4", "lockpick", "crowbar", "blackout", "psycho", "disguise", "morph",
            "sloth", "gluttony", "greed", "lust", "envy", "wrath", "pride",
            "drone", "idiot_egg", "tailor", "doll", "mime", "bouquet", "pepper_spray",
            "trap", "rope", "tacz"
    );

    private static Round current;
    private static PendingFinish pendingFinish;
    private MatchReplayRecorder() {}

    public static void register() {
        MatchEvents.STARTED.register(MatchReplayRecorder::begin);
        OnGameTrueStarted.EVENT.register(level -> {
            if (current == null || current.level != level) begin(level);
        });
        MatchEventBridge.WINNERS_FINALIZED.register(MatchReplayRecorder::finish);
        MatchEventBridge.CLEANUP.register((level, published) -> {
            if (current != null && current.level == level && pendingFinish == null) reset();
        });
        ServerTickEvents.END_WORLD_TICK.register(MatchReplayRecorder::tick);
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, previous, next) -> leave(player, previous));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> leave(handler.player, handler.player.serverLevel()));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Round r = current;
            if (r == null || r.level != level) return;
            if (entity instanceof ReconDroneEntity drone) r.removeDrone(drone);
            if(r.start>=0 && r.activeEnd<0) r.visual.entities.remove(entity,r.now());
            r.untrackEntity(entity);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            Round r = running(level);
            if (r == null) return;
            if (entity instanceof ReconDroneEntity drone) {
                r.addDrone(drone);
            }
            r.maybeTrackEntity(entity);
            r.visual.entities.observe(entity,r.now());
            if (entity.tickCount == 0 && entity instanceof Projectile projectile
                    && projectile.getOwner() instanceof ServerPlayer thrower) {
                var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                if ((type.getNamespace().equals("starrailexpress") || type.getNamespace().equals("noellesroles"))
                        && type.getPath().contains("grenade")) {
                    spatial(level, Kind.GRENADE_THROWN, thrower.getUUID(), null, entity.position(), null, type.toString(), 0);
                }
            }
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world instanceof ServerLevel level && !player.isSpectator() && player.getItemInHand(hand).is(TMMItems.LOCKPICK)) {
                BlockPos pos = hit.getBlockPos();
                var state = level.getBlockState(pos);
                if (state.getBlock() instanceof SmallDoorBlock && !player.isShiftKeyDown()) {
                    if (state.getValue(SmallDoorBlock.HALF) == DoubleBlockHalf.UPPER) pos = pos.below();
                    spatial(level, Kind.LOCKPICK_ATTEMPT, player.getUUID(), null, player.position(),
                            Vec3.atCenterOf(pos), "attempt", 0);
                }
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world instanceof ServerLevel level && player instanceof ServerPlayer sp && !sp.isSpectator()) {
                ItemStack stack = sp.getItemInHand(hand);
                if (!stack.isEmpty()) captureGenericItemUse(level, sp, null, stack);
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world instanceof ServerLevel level && player instanceof ServerPlayer sp && !sp.isSpectator()) {
                ItemStack stack = sp.getItemInHand(hand);
                if (!stack.isEmpty()) {
                    UUID targetId = entity instanceof Player tp ? tp.getUUID() : null;
                    captureGenericItemUse(level, sp, targetId, stack);
                }
            }
            return InteractionResult.PASS;
        });
        OnPlayerDeathWithKiller.EVENT.register((victim, killer, reason) -> {
            try {
                death(victim, killer, reason);
            } catch (Throwable failure) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(failure);
                LoggerFactory.getLogger(MatchReplayRecorder.class).warn("Could not record replay death", failure);
            }
        });
        OnRevolverUsed.EVENT.register((player, target) -> shot(player, target, 30, true));
        OnShieldBroken.EVENT.register((victim, attacker) -> {
            if (victim.level() instanceof ServerLevel level) spatial(level, Kind.SHIELD_BROKEN, id(attacker), id(victim),
                    attacker == null ? null : attacker.position(), victim.position(), "", SUCCESS);
        });
        ReplayEventRecordedCallback.EVENT.register((event, unused) -> fromUpstream(event));
    }

    public static void beforeBlockWrite(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk, BlockPos pos) {
        Round r = current;
        if (r != null && r.level == level && !r.captureDone && level.getServer().isSameThread()) r.capture.beforeWrite(chunk, pos);
    }

    /** Captures dynamic world state transitions (door open/close/lock/pry/seal, wall breaches) after match ACTIVE start. */
    public static void afterBlockWrite(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        Round r = running(level);
        if (r == null || pos == null || oldState == null || newState == null || oldState.equals(newState)) return;
        r.recordBlockDelta(pos, oldState, newState);
    }

    /** Records a persistent area-of-effect zone (smoke/fire/slime/poison/fog) as a {@link ReplayEntityTrack}. */
    public static void recordZone(ServerLevel level, ReplayEntityTrack.EntityKind kind, String typeId,
                                  @Nullable UUID ownerUuid, Vec3 center, float radius, int durationTicks) {
        Round r = running(level);
        if (r == null || center == null || kind == null) return;
        r.recordZoneTrack(kind, typeId, ownerUuid, center, radius, durationTicks);
    }

    /** Records a structured skill / item / third-party fallback event with role color and nearest recorded sound. */
    public static void recordSkillEvent(ServerLevel level, ServerPlayer actor, @Nullable UUID targetUuid,
                                        String skillOrItemId, String displayName, String category,
                                        Vec3 from, @Nullable Vec3 to, int durationTicks) {
        Round r = running(level);
        if (r == null || actor == null || !r.eligible(actor.getUUID())) return;
        r.addSkillEvent(actor, targetUuid, skillOrItemId, displayName, category, from, to, durationTicks);
    }

    public static void reset() {
        Round round = current;
        if (round != null) round.visual.stop(round.level, round.now());
        if (round != null) round.capture.cancel();
        finishPendingNow();
        current = null;
        MatchVoiceRecorder.reset();
        GlobalMatchSoundRecorder.reset();
    }

    /** Flushes any pending post-match voice tail synchronously before server shutdown drains the archive I/O queue. */
    public static void flushPendingBeforeStop() {
        finishPendingNow();
    }

    public static void stopVoice(ServerLevel level) {
        MatchVoiceRecorder.stop(level);
    }

    private static void leave(ServerPlayer player, ServerLevel level) {
        Round r = current;
        if (r != null && r.level == level && r.start >= 0) r.visual.leave(player, r.now());
        if (r != null && r.level == level && r.start >= 0) MatchVoiceRecorder.leave(level, player.getUUID(), r.now());
    }

    private static void begin(ServerLevel level) {
        var game = SREGameWorldComponent.KEY.get(level);
        if (game == null || game.getGameStatus() == SREGameWorldComponent.GameStatus.INACTIVE) return;
        boolean shouldRecord = game.gameMode == SREGameModes.MURDER;
        if (shouldRecord && current != null && current.level == level && current.started == level.getGameTime()) return;
        reset();
        if (!shouldRecord) return;
        var areas = AreasWorldComponent.KEY.get(level);
        SceneBounds bounds = ReplaySectionGeometry.playBounds(areas.getPlayArea(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        String mapKey = bounded(areas.mapName, 128);
        String label = bounded(areas.mapDisplayName, 128);
        current = new Round(level, bounds, mapKey, label.isBlank() ? mapKey : label);
        if (ReplayArchiveService.shouldRecordVoice(level.getServer()) && FabricLoader.getInstance().isModLoaded("voicechat")) {
            MatchVoiceRecorder.beginRound(current.id, level, bounds);
        }
        if (game.getGameStatus() == SREGameWorldComponent.GameStatus.ACTIVE) current.activate(game);
    }

    private static void tick(ServerLevel level) {
        Round r = current;
        if (r == null) {
            if (pendingFinish != null && pendingFinish.level() == level) tickPending(level);
            return;
        }
        if (r.level != level) return;
        var game = SREGameWorldComponent.KEY.get(level);
        if (game.getGameStatus() == SREGameWorldComponent.GameStatus.INACTIVE) {
            if (pendingFinish != null && pendingFinish.level() == level) {
                tickPending(level);
                return;
            }
            reset();
            return;
        }
        if (!r.captureDone) {
            try { if (r.capture.step()) r.finishMap(); }
            catch (RuntimeException failure) { r.failMap(failure); }
        }
        if (game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) {
            if (r.start >= 0 && r.activeEnd < 0) {
                r.activeEnd = level.getGameTime();
            }
            if (pendingFinish != null && pendingFinish.level() == level) tickPending(level);
            return;
        }
        if (r.start < 0) r.activate(game);
        int at = r.now();
        GlobalMatchSoundRecorder.flushTick(level, at);
        r.visual.clock(level, at);
        boolean dark = SREWorldBlackoutComponent.KEY.get(level).isBlackoutActive();
        r.environment(SpanKind.BLACKOUT, null, dark, at, null);
        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            if (!r.eligible(id)) continue;
            boolean psycho = GameUtils.isPlayerAliveAndSurvival(player)
                    && SREPlayerPsychoComponent.KEY.get(player).getPsychoTicks() > 0;
            r.environment(SpanKind.PSYCHO, id, psycho, at, player.position());
        }
        if (MatchVoiceRecorder.isRecording()) {
            Set<UUID> deathsAtTick = new HashSet<>();
            for (var entry : r.deaths.entrySet()) {
                if (entry.getValue() == at) deathsAtTick.add(entry.getKey());
            }
            MatchVoiceRecorder.tick(level, at, deathsAtTick, r.events);
        }
        if (at != r.lastSample) {
            r.sample(game, at);
        }
    }

    private static void finish(ServerLevel level, MatchSettlement settlement) {
        Round r = current;
        if (r == null || r.level != level) return;
        if (pendingFinish != null && pendingFinish.level() == level) return;
        r.visual.stop(level, r.now());
        if (r.start < 0 || settlement.participants().isEmpty()) {
            r.capture.cancel();
            MatchVoiceRecorder.reset();
            GlobalMatchSoundRecorder.reset();
            current = null;
            return;
        }
        if (!r.captureDone) {
            try { r.finishMap(); }
            catch (RuntimeException failure) { r.failMap(failure); }
        }
        var game = SREGameWorldComponent.KEY.get(level);
        var cca = SREGameRoundEndComponent.KEY.get(level);
        var scores = MvpScoreTracker.snapshot(level);
        Map<UUID, String> names = new HashMap<>();
        if (cca.players != null) for (var p : cca.players) {
            if (p != null && p.player != null) names.put(p.player.getId(), p.player.getName());
        }
        List<UUID> ids = settlement.participants().stream().filter(id -> !RepairModeManager.isRepairer(id))
                .filter(id -> {
                    SRERole role = game.getRole(id);
                    return role == null || !excluded(role);
                })
                .sorted(Comparator.<UUID, Boolean>comparing(settlement.winners()::contains).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
        int at = r.now();
        List<Participant> people = new ArrayList<>();
        for (UUID id : ids) {
            if (people.size() >= MAX_PLAYERS) { r.limited = true; break; }
            Person person = r.people.get(id);
            var score = scores.get(id);
            Role end = role(game.getRole(id));
            if (end.id.isEmpty() && person != null) end = person.lastRole;
            Role start = person != null ? person.startRole : end;
            String name = person != null ? person.name : names.getOrDefault(id, score == null ? "" : score.playerName());
            people.add(new Participant(id, bounded(name, 64), start.id, start.color, end.id, end.color,
                    settlement.factionOfOrEmpty(id).orElse(MatchWinFaction.PASSENGER), settlement.winners().contains(id),
                    score == null ? 0 : score.survivalSeconds(), score == null ? 0 : score.kills(),
                    score == null ? 0 : score.score(), score == null ? 0 : score.itemUses(),
                    person == null ? List.of() : person.track));
        }
        if (people.isEmpty()) {
            MatchVoiceRecorder.reset();
            GlobalMatchSoundRecorder.reset();
            return;
        }
        for (var entry : r.open.entrySet()) r.close(entry.getKey(), entry.getValue(), at);
        UUID mvp = people.stream().filter(p -> p.winner() && scores.containsKey(p.id())).min(
                Comparator.comparingInt(Participant::score).reversed()
                        .thenComparing(Comparator.comparingInt(Participant::kills).reversed())
                        .thenComparing(Comparator.comparingInt(Participant::survivalSeconds).reversed())
                        .thenComparing(Comparator.comparingInt(Participant::itemUses).reversed())
                        .thenComparing(Participant::id)).map(Participant::id).orElse(null);
        long anchor = level.getServer().overworld().getGameTime();
        var win = cca.getWinStatus();
        r.events.sort(Comparator.comparingInt(Node::tick).thenComparingInt(Node::eventId));
        r.spans.sort(Comparator.comparingInt(Span::startTick));
        ReplayGameSoundData gameSounds = GlobalMatchSoundRecorder.finish(r.id);
        var summary = new LastMatchReplay(r.id, anchor, at, r.label, bounded(settlement.modeId(), 128),
                win == null ? "NONE" : win.name(), settlement.winKind(),
                bounded(cca.CustomWinnerID, 128), title(cca.CustomWinnerTitle, level), title(cca.CustomWinnerSubtitle, level),
                cca.CustomWinnerColor, r.bounds, null, people, r.events, r.spans, mvp, r.kills, r.limited || r.visual.tape.limited(),
                r.droneTracks(ids), List.of(),
                r.finalizeBlockDeltas(), r.finalizeEntityTracks(at), r.finalizeSkillEvents(), gameSounds);
        if (MatchVoiceRecorder.isRecording()) {
            r.activeEnd = Math.max(r.activeEnd, level.getGameTime());
            MatchVoiceRecorder.freezePostEventPositions(r.id);
            pendingFinish = new PendingFinish(level, r, summary,
                    level.getGameTime() + MatchVoiceRecorder.postEventTicks());
            current = null;
            return;
        }
        publishFinished(level, r, summary, new byte[0]);
    }

    private static void tickPending(ServerLevel level) {
        PendingFinish pending = pendingFinish;
        if (pending == null || pending.level() != level) return;
        Round round = pending.round();
        if (round.activeEnd < 0) round.activeEnd = level.getGameTime();
        if (MatchVoiceRecorder.isRecording()
                && level.getGameTime() - round.activeEnd <= MatchVoiceRecorder.postEventTicks()) {
            int voiceTick = (int) Math.clamp(level.getGameTime() - round.start, 0L, MAX_TICKS);
            MatchVoiceRecorder.tick(level, voiceTick, Set.of(), round.events);
        }
        finishPending(level);
    }

    private static void finishPending(ServerLevel level) {
        PendingFinish pending = pendingFinish;
        if (pending == null || pending.level() != level || level.getGameTime() < pending.voiceDeadline()) return;
        finishPendingNow();
    }

    private static void finishPendingNow() {
        PendingFinish pending = pendingFinish;
        if (pending == null) return;
        pendingFinish = null;
        MatchVoiceRecorder.stop(pending.level());
        byte[] voice = MatchVoiceRecorder.finishRound(pending.round().id, pending.round().events);
        publishFinished(pending.level(), pending.round(), pending.summary(), voice);
    }

    private static void publishFinished(ServerLevel level, Round round, LastMatchReplay summary, byte[] voice) {
        if (current == round) current = null;
        if (pendingFinish != null && pendingFinish.round() == round) pendingFinish = null;
        ReplayBoardService.publish(summary, round.map, voice, round.visual.tape);
    }

    public static void visualPacket(ServerPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        Round round = current;
        if (round == null || round.level != player.level() || !round.level.getServer().isSameThread() || round.start < 0 || round.activeEnd >= 0) return;
        round.visual.packet(player, packet, round.now());
    }

    static void visualFrame(ServerPlayer player, com.habitrain.core.network.replay.VisualPackets.Frame frame) {
        Round round = current;
        if (round != null && round.level == player.level() && round.start >= 0 && round.activeEnd < 0) round.visual.frame(player, round.now(), frame);
    }

    private static String title(Component c, ServerLevel level) {
        if (c == null) return "";
        String json = Component.Serializer.toJson(c, level.registryAccess());
        return json.length() <= 2048 ? json : Component.Serializer.toJson(
                Component.literal(bounded(c.getString(), 256)), level.registryAccess());
    }

    private static void captureGenericItemUse(ServerLevel level, ServerPlayer player,
                                              @Nullable UUID targetId, ItemStack stack) {
        Round r = running(level);
        if (r == null || !r.eligible(player.getUUID()) || !GameUtils.isPlayerAliveAndSurvival(player)) return;
        String itemId = resolveItemId(stack);
        if (itemId.isEmpty() || isIgnoredGenericItem(itemId)) return;
        Entity target = targetId == null ? null : level.getPlayerByUUID(targetId);
        String name = stack.getHoverName().getString();
        r.addSkillEvent(player, targetId, itemId, name, ReplaySkillEvent.CAT_ITEM_USE,
                player.position(), target == null ? null : target.position(), 35);
    }

    private static boolean isIgnoredGenericItem(String itemId) {
        // Skip routine food/air/key/ticket items that don't represent active combat or role abilities
        if (itemId.equals("minecraft:air") || itemId.equals("minecraft:bread")
                || itemId.equals("minecraft:cooked_beef") || itemId.equals("minecraft:water_bucket")
                || itemId.equals("minecraft:potion")) {
            return true;
        }
        return itemId.contains("ticket") || itemId.contains("key") || itemId.contains("food")
                || itemId.contains("drink") || itemId.contains("letter") || itemId.contains("note");
    }

    static boolean isKnownAdapterSkillOrItem(String id) {
        if (id == null || id.isBlank()) return false;
        String lower = id.toLowerCase(Locale.ROOT);
        for (String token : KNOWN_ADAPTER_TOKENS) {
            if (lower.contains(token)) return true;
        }
        return false;
    }

    static String resolveItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        if (HabiRoleItems.isDroneTerminal(stack)) return "habitrain_core:drone_terminal";
        if (HabiRoleItems.isDroneBattery(stack)) return "habitrain_core:drone_battery";
        if (HabiRoleItems.isBouquet(stack)) return "habitrain_core:bouquet";
        if (HabiRoleItems.isPepperSpray(stack)) return "habitrain_core:pepper_spray";
        if (HabiRoleItems.isRedApple(stack)) return "habitrain_core:red_apple";
        if (HabiRoleItems.isScapegoatKnife(stack)) return "habitrain_core:scapegoat_knife";
        if (HabiRoleItems.isDroneFakeKnife(stack)) return "habitrain_core:drone_fake_knife";
        if (HabiRoleItems.hasFlag(stack, HabiRoleItems.TAG_LUST_INCENSE)) return "habitrain_core:lust_incense";

        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String base = rl == null ? "" : rl.toString();
        if (base.startsWith("tacz:")) {
            CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            if (custom != null) {
                CompoundTag tag = custom.copyTag();
                if (tag.contains("GunId")) {
                    String gunId = tag.getString("GunId");
                    if (!gunId.isBlank()) return bounded(base + "#" + gunId, 128);
                }
            }
        }
        return bounded(base, 128);
    }

    private static void death(Player victim, @Nullable Player killer, ResourceLocation reason) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        Round r = running(level);
        if (r == null || !r.eligible(victim.getUUID())) return;
        String key = reason == null ? "" : reason.toString();
        if (r.deaths.getOrDefault(victim.getUUID(), -1) != r.now()) {
            if (r.node(Kind.KILL, id(killer), victim.getUUID(), killer == null ? null : killer.position(), victim.position(), key, 0)) {
                r.kills++;
            }
            r.deaths.put(victim.getUUID(), r.now());
        }
        r.environment(SpanKind.PSYCHO, victim.getUUID(), false, r.now(), victim.position());
        if (reason != null && reason.getPath().contains("backfire")) {
            r.recordBackfire(victim.getUUID(), r.pos(victim.position()));
        }
        if (reason != null && reason.getPath().equals("poison")) {
            r.node(Kind.POISON, id(killer), victim.getUUID(), null, victim.position(), "triggered", SUCCESS);
        }
        Person p = r.people.get(victim.getUUID());
        Position at = r.pos(victim.position());
        if (p != null && at != null) {
            ReplayTracks.appendV2(p.track, new Sample(r.now(), at, yaw(victim), (byte) 0));
        }
    }

    public static void shot(ServerPlayer shooter, @Nullable Entity hit, double range, boolean authoritative) {
        Round r = running(shooter.serverLevel());
        if (r == null || !r.eligible(shooter.getUUID())) return;
        UUID who = shooter.getUUID();
        int at = r.now();
        Shot previous = r.shots.get(who);
        if (previous != null && previous.tick == at && previous.authoritative && !authoritative) return;
        Vec3 from = shooter.getEyePosition();
        Backfire backfire = r.backfires.get(who);
        boolean misfired = backfire != null && backfire.tick == at;
        Vec3 to;
        if (misfired) {
            hit = null;
            to = null;
        } else if (hit != null && hit.distanceToSqr(shooter) <= range * range) {
            to = hit.getBoundingBox().getCenter();
        } else {
            hit = null;
            Vec3 end = from.add(shooter.getViewVector(1).scale(range));
            to = shooter.serverLevel().clip(new ClipContext(from, end, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, shooter)).getLocation();
        }
        int flags = hit == null ? 0 : HIT;
        int eventId = r.allocateEventId();
        Node node = new Node(eventId, at, Kind.GUNSHOT, who, hit instanceof Player p ? p.getUUID() : null,
                r.pos(from), r.pos(to), bounded(resolveItemId(shooter.getMainHandItem()), 128), flags);
        if (misfired) node = backfireShot(node, backfire.impact);
        if (r.add(node) >= 0) r.shots.put(who, new Shot(at, eventId, authoritative));
    }

    public static void gunDrop(ServerPlayer shooter) {
        Round r = running(shooter.serverLevel());
        if (r != null) r.markShot(shooter.getUUID(), GUN_DROP);
    }

    public static void spatial(ServerLevel level, Kind kind, UUID actor, UUID target, Vec3 from, Vec3 to,
                               String detail, int flags) {
        Round r = running(level);
        if (r == null || !level.getServer().isSameThread()
                || (actor != null || target != null) && !r.eligible(actor) && !r.eligible(target)) return;
        r.node(kind, actor, target, from, to, detail, flags);
    }

    static @Nullable Kind publicEventKind(ReplayTimelineEvent e) {
        if (e.hidden()) return null;
        return switch (e.type().name()) {
            case "PLAYER_KILL" -> null;
            case "PLAYER_POISONED" -> Kind.POISON;
            case "ARMOR_BREAK" -> Kind.SHIELD_BROKEN;
            case "BLACKOUT_START", "BLACKOUT_END", "PSYCHO_STATE_CHANGE" -> null;
            case "BOMB_DETONATE" -> null;
            default -> {
                try { yield Kind.valueOf(e.type().name()); }
                catch (IllegalArgumentException ignored) { yield null; }
            }
        };
    }

    private static void fromUpstream(ReplayTimelineEvent e) {
        Round r = current;
        if (r == null || !r.level.getServer().isSameThread()) return;
        Kind kind = publicEventKind(e);
        if (kind == null) return;
        if (running(r.level) == null) return;
        UUID actor = e.actor() == null ? null : e.actor().uuid(), target = e.target() == null ? null : e.target().uuid();
        if ((actor != null || target != null) && !r.eligible(actor) && !r.eligible(target)) return;
        Map<String, String> data = e.data();
        String item = data.getOrDefault("itemUsed", "");
        String message = data.getOrDefault("message", "");
        Vec3 from = r.position(actor), to = r.position(target);
        String detail = item.isBlank() ? message : item;
        int flags = 0;
        switch (kind) {
            case PLAYER_REVIVAL -> {
                detail = message;
                r.deaths.remove(actor);
            }
            case CHANGE_ROLE -> detail = message;
            case DOOR_PRY, DOOR_SEAL, LOCKPICK_ATTEMPT, DOOR_LOCK, DOOR_UNLOCK, DOOR_OPEN, DOOR_CLOSE, GRENADE_THROWN -> {
                try { to = Vec3.atCenterOf(BlockPos.of(Long.parseLong(item))); }
                catch (NumberFormatException ignored) { flags |= APPROXIMATE; }
                if ("true".equalsIgnoreCase(message)) flags |= SUCCESS;
            }
            case BOMB_DEFUSE -> {
                if (target == null) return;
                if (to == null) flags |= APPROXIMATE;
                else flags |= SUCCESS;
            }
            case POISON -> detail = "applied";
            default -> {}
        }
        r.node(kind, actor, target, from, to, detail, flags);
    }

    private static @Nullable Round running(ServerLevel level) {
        Round r = current;
        if (r == null || r.level != level || r.activeEnd >= 0 || !level.getServer().isSameThread()) return null;
        if (r.start < 0) {
            var game = SREGameWorldComponent.KEY.get(level);
            if (game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) return null;
            r.activate(game);
        }
        return r;
    }

    private static @Nullable UUID id(@Nullable Entity entity) { return entity == null ? null : entity.getUUID(); }
    private static byte yaw(Entity entity) { return Sample.quantizeYaw(entity.getYRot()); }
    private static byte pitch(Entity entity) { return Sample.quantizePitch(entity.getXRot()); }
    private static boolean excluded(SRERole role) {
        return role == null || TMMRoles.DISCOVERY_CIVILIAN.identifier().equals(role.identifier());
    }

    private record Role(String id, int color) {}
    private record PendingFinish(ServerLevel level, Round round, LastMatchReplay summary, long voiceDeadline) {}

    private static Role role(@Nullable SRERole raw) {
        if (raw == null || raw.identifier() == null) return new Role("", 0);
        var snapshot = RoleSnapshotManager.INSTANCE.current();
        SRERole value = snapshot == null ? raw : snapshot.find(RoleKey.of(raw.identifier())).map(EffectiveRole::role).orElse(raw);
        return new Role(bounded(value.identifier().toString(), 128), value.color());
    }

    private static String bounded(String text, int max) {
        if (text == null) return "";
        int end = Math.min(text.length(), max);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static String humanizeId(String id) {
        if (id == null || id.isBlank()) return "Skill";
        int hash = id.indexOf('#');
        String clean = hash >= 0 ? id.substring(hash + 1) : id;
        int colon = clean.indexOf(':');
        if (colon >= 0) clean = clean.substring(colon + 1);
        return clean.replace('_', ' ').replace('/', ' ');
    }

    private static final class Person {
        final String name;
        final Role startRole;
        Role lastRole;
        final List<Sample> track = new ArrayList<>();
        final Set<String> knownEffects = new HashSet<>();
        boolean effectsInitialized;
        boolean present;
        int lastSkillTick = -100;
        Person(String name, Role role) { this.name = name; this.startRole = role; this.lastRole = role; }
    }

    private static final class TrackedEntity {
        final UUID id;
        final ReplayEntityTrack.EntityKind kind;
        final String typeId;
        final @Nullable UUID ownerUuid;
        final @Nullable UUID targetUuid;
        final float radius;
        final boolean highRate;
        final List<ReplayEntityTrack.EntitySample> samples = new ArrayList<>();
        @Nullable Entity liveRef;

        TrackedEntity(UUID id, ReplayEntityTrack.EntityKind kind, String typeId,
                      @Nullable UUID ownerUuid, @Nullable UUID targetUuid, float radius,
                      boolean highRate, @Nullable Entity liveRef) {
            this.id = id;
            this.kind = kind;
            this.typeId = typeId;
            this.ownerUuid = ownerUuid;
            this.targetUuid = targetUuid;
            this.radius = radius;
            this.highRate = highRate;
            this.liveRef = liveRef;
        }
    }

    private record SpanKey(SpanKind kind, @Nullable UUID actor) {}
    private record DroneTrack(UUID id, UUID owner, List<Sample> track) {}
    private record Shot(int tick, int eventId, boolean authoritative) {}
    private record Backfire(int tick, @Nullable Position impact) {}

    static void markAbsent(List<Sample> track, int tick) {
        if (track.isEmpty()) return;
        Sample last = track.getLast();
        if (last.flags() != BREAK) ReplayTracks.appendV2(track, new Sample(tick, last.position(), last.yaw(), BREAK));
    }

    static Node backfireShot(Node shot, @Nullable Position impact) {
        return new Node(shot.eventId(), shot.tick(), shot.kind(), shot.actor(), shot.actor(), shot.from(),
                impact == null ? shot.from() : impact, shot.detail(), (shot.flags() & ~HIT) | BACKFIRE);
    }

    public static UUID recordId(ServerLevel level) {
        Round r = current;
        return r != null && r.level == level && r.start >= 0 ? r.id : null;
    }

    private static final class Round {
        final UUID id = UUID.randomUUID();
        final MatchVisualRecorder visual;
        final ServerLevel level;
        final long started;
        final SceneBounds bounds;
        final String label;
        final ReplayMapCapture capture;
        final Map<UUID, Person> people = new LinkedHashMap<>();
        final Map<UUID, DroneTrack> drones = new LinkedHashMap<>();
        final Map<UUID, ReconDroneEntity> deployed = new LinkedHashMap<>();
        final Map<UUID, TrackedEntity> trackedEntities = new LinkedHashMap<>();
        final Map<UUID, Integer> deaths = new HashMap<>();
        final Map<UUID, Shot> shots = new HashMap<>();
        final Map<UUID, Backfire> backfires = new HashMap<>();
        final Map<SpanKey, Integer> open = new HashMap<>();
        final List<Node> events = new ArrayList<>();
        final List<Span> spans = new ArrayList<>();
        final List<ReplayBlockDelta> blockDeltas = new ArrayList<>();
        final List<ReplaySkillEvent> skillEvents = new ArrayList<>();
        final Map<String, Integer> skillThrottle = new HashMap<>();
        int nextEventId = 1;
        long start = -1, activeEnd = -1;
        int kills, lastSample = -1;
        boolean limited, captureDone;
        java.util.concurrent.CompletableFuture<ReplayMapCapture.Result> map;

        Round(ServerLevel level, SceneBounds bounds, String key, String label) {
            this.level = level; this.started = level.getGameTime(); this.bounds = bounds; this.label = label;
            visual = new MatchVisualRecorder(id, bounds);
            this.capture = new ReplayMapCapture(level, bounds, key, (chunk, section) -> visual.initial(chunk, section, start < 0 ? 0 : now()));
        }

        int allocateEventId() {
            return nextEventId++;
        }

        void finishMap() {
            captureDone = true;
            var raw = capture.finish();
            limited |= raw == null || raw.partialBounds() != null;
            if (raw != null) map = ReplayBoardService.encodeMap(raw);
        }

        void failMap(RuntimeException failure) {
            captureDone = true;
            limited = true;
            capture.cancel();
            LoggerFactory.getLogger(MatchReplayRecorder.class).warn("Replay geometry capture failed", failure);
        }

        int now() { return (int) Math.clamp((activeEnd >= 0 ? activeEnd : level.getGameTime()) - start, 0L, MAX_TICKS); }

        void activate(SREGameWorldComponent game) {
            start = level.getGameTime();
            for (var body : level.getEntitiesOfClass(PlayerBodyEntity.class, new net.minecraft.world.phys.AABB(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX()+1,bounds.maxY()+1,bounds.maxZ()+1))) maybeTrackEntity(body);
            visual.clock(level, 0);
            GlobalMatchSoundRecorder.begin(id, level, bounds, start);
            MatchVoiceRecorder.activateRound(id);
            MatchVoiceRecorder.tick(level, 0, Set.of(), events);
            sample(game, 0);
        }

        boolean eligible(UUID id) {
            return id != null && !RepairModeManager.isRepairer(id)
                    && (people.containsKey(id) || !excluded(SREGameWorldComponent.KEY.get(level).getRole(id)));
        }

        boolean inBounds(BlockPos pos) {
            return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                    && pos.getY() >= bounds.minY() && pos.getY() <= bounds.maxY()
                    && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
        }

        void recordBlockDelta(BlockPos pos, BlockState oldState, BlockState newState) {
            if (!inBounds(pos)) return;
            Block oldBlock = oldState.getBlock();
            Block newBlock = newState.getBlock();
            boolean isDoor = oldBlock instanceof SmallDoorBlock || newBlock instanceof SmallDoorBlock
                    || oldBlock instanceof DoorBlock || newBlock instanceof DoorBlock
                    || oldBlock instanceof TrapDoorBlock || newBlock instanceof TrapDoorBlock
                    || oldBlock instanceof FenceGateBlock || newBlock instanceof FenceGateBlock;

            String visualState = SceneCaptureService.serializeBlockState(newState);
            if (visualState.length() <= ReplayBlockDelta.MAX_STATE_LENGTH) visual.tape.append(com.habitrain.core.network.replay.VisualTape.WORLD, now(), com.habitrain.core.network.replay.VisualTape.STATE,
                    com.habitrain.core.network.replay.VisualTape.bytes(out -> { out.writeLong(pos.asLong()); out.writeUTF(visualState); }));
            if (oldBlock == newBlock && !isDoor) {
                String blockKey = BuiltInRegistries.BLOCK.getKey(newBlock).getPath();
                if (!blockKey.contains("door") && !blockKey.contains("gate") && !blockKey.contains("lamp") && !blockKey.contains("light") && !blockKey.contains("lever") && !blockKey.contains("button")) return;
            }

            byte reason = ReplayBlockDelta.REASON_OTHER;
            if (isDoor) {
                boolean openChanged = oldState.hasProperty(BlockStateProperties.OPEN)
                        && newState.hasProperty(BlockStateProperties.OPEN)
                        && !Objects.equals(oldState.getValue(BlockStateProperties.OPEN), newState.getValue(BlockStateProperties.OPEN));
                reason = openChanged ? ReplayBlockDelta.REASON_DOOR_TOGGLE : ReplayBlockDelta.REASON_DOOR_LOCK;
            } else if (newState.isAir() && !oldState.isAir()) {
                reason = ReplayBlockDelta.REASON_WALL_BREAK;
            } else if (!newState.isAir() && oldState.isAir()) {
                reason = ReplayBlockDelta.REASON_WALL_PLACE;
            }

            int tick = now();
            int rx = pos.getX() - bounds.minX();
            int ry = pos.getY() - bounds.minY();
            int rz = pos.getZ() - bounds.minZ();
            String stateStr = bounded(SceneCaptureService.serializeBlockState(newState), ReplayBlockDelta.MAX_STATE_LENGTH);
            if (stateStr.isEmpty()) return;

            ReplayBlockDelta delta = new ReplayBlockDelta(tick, rx, ry, rz, stateStr, reason);
            // Collapse same-tick writes on the same coordinate
            for (int i = blockDeltas.size() - 1; i >= Math.max(0, blockDeltas.size() - 16); i--) {
                ReplayBlockDelta prev = blockDeltas.get(i);
                if (prev.tick() != tick) break;
                if (prev.x() == rx && prev.y() == ry && prev.z() == rz) {
                    blockDeltas.set(i, delta);
                    return;
                }
            }
            if (blockDeltas.size() < ReplayBlockDelta.MAX_DELTAS) {
                blockDeltas.add(delta);
            } else {
                limited = true;
            }
        }

        void maybeTrackEntity(Entity entity) {
            if (entity == null || entity instanceof Player || trackedEntities.containsKey(entity.getUUID())) return;
            if (trackedEntities.size() >= ReplayEntityTrack.MAX_TRACKS) return;
            Position p = pos(entity.position());
            if (p == null) return;

            ReplayEntityTrack.EntityKind kind = null;
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            UUID ownerUuid = null;
            UUID targetUuid = null;
            float radius = 0.5f;
            boolean highRate = false;

            if (entity instanceof ReconDroneEntity drone) {
                kind = ReplayEntityTrack.EntityKind.DRONE;
                ownerUuid = drone.ownerId();
                highRate = true;
            } else if (entity instanceof IdiotChickEntity chick) {
                kind = ReplayEntityTrack.EntityKind.IDIOT_CHICK;
                ownerUuid = chick.owner();
            } else if (entity instanceof IdiotEggEntity egg) {
                kind = ReplayEntityTrack.EntityKind.IDIOT_EGG;
                ownerUuid = id(egg.getOwner());
                highRate = true;
            } else if (entity instanceof TailorDollEntity doll) {
                kind = ReplayEntityTrack.EntityKind.TAILOR_DOLL;
                ownerUuid = doll.ownerId();
                targetUuid = doll.targetId();
            } else if (entity instanceof PlayerBodyEntity body) {
                kind = ReplayEntityTrack.EntityKind.CORPSE;
                targetUuid = body.getPlayerUuid();
            } else if (entity instanceof Projectile proj) {
                String path = typeId.toLowerCase(Locale.ROOT);
                if (path.contains("grenade") || path.contains("smoke_ball")) {
                    kind = ReplayEntityTrack.EntityKind.GRENADE;
                    ownerUuid = id(proj.getOwner());
                    highRate = true;
                } else if (path.contains("knife") || path.contains("shuriken") || path.contains("net")
                        || path.contains("arrow") || path.contains("bullet") || path.contains("dart")
                        || path.contains("spear") || path.contains("snowball") || path.contains("wave")) {
                    kind = ReplayEntityTrack.EntityKind.PROJECTILE;
                    ownerUuid = id(proj.getOwner());
                    highRate = true;
                }
            } else if (entity instanceof ItemEntity itemEntity) {
                String itemId = resolveItemId(itemEntity.getItem());
                String lower = itemId.toLowerCase(Locale.ROOT);
                if (lower.contains("c4")) {
                    kind = ReplayEntityTrack.EntityKind.PLACED_C4;
                    typeId = itemId;
                    ownerUuid = itemEntity.getOwner() == null ? null : itemEntity.getOwner().getUUID();
                } else if (lower.contains("revolver") || lower.contains("gun") || lower.contains("sniper")
                        || lower.contains("shotgun") || lower.contains("derringer") || lower.contains("knife")
                        || lower.contains("bat") || lower.contains("grenade") || lower.startsWith("tacz:")) {
                    kind = ReplayEntityTrack.EntityKind.DROPPED_ITEM;
                    typeId = itemId;
                }
            } else if (entity instanceof AreaEffectCloud cloud) {
                kind = ReplayEntityTrack.EntityKind.ZONE_POISON;
                radius = Math.clamp(cloud.getRadius(), 0.5f, 32f);
                ownerUuid = cloud.getOwner() == null ? null : cloud.getOwner().getUUID();
            } else {
                String lower = typeId.toLowerCase(Locale.ROOT);
                if (lower.contains("trap") || lower.contains("mine")) {
                    kind = ReplayEntityTrack.EntityKind.TRAP;
                } else if (lower.contains("smoke") || lower.contains("fog")) {
                    kind = ReplayEntityTrack.EntityKind.ZONE_FOG;
                    radius = 4.0f;
                }
            }

            if (kind == null) return;
            TrackedEntity te = new TrackedEntity(entity.getUUID(), kind, bounded(typeId, ReplayEntityTrack.MAX_TYPE_ID_LENGTH),
                    ownerUuid, targetUuid, radius, highRate, entity);
            trackedEntities.put(te.id, te);
            sampleTrackedEntity(te, now());
        }

        void untrackEntity(Entity entity) {
            if (entity == null) return;
            TrackedEntity te = trackedEntities.get(entity.getUUID());
            if (te == null || te.liveRef != entity) return;
            if (entity instanceof PlayerBodyEntity) visual.removeCorpse(entity.getUUID(),now());
            te.liveRef = null;
            if (!te.samples.isEmpty() && te.samples.size() < ReplayEntityTrack.MAX_SAMPLES_PER_TRACK) {
                var last = te.samples.getLast();
                te.samples.add(new ReplayEntityTrack.EntitySample(now(), last.position(), last.yaw(), last.pitch(),
                        ReplayEntityTrack.FLAG_BREAK));
            }
        }

        void recordZoneTrack(ReplayEntityTrack.EntityKind kind, String typeId, @Nullable UUID owner,
                             Vec3 center, float radius, int durationTicks) {
            if (trackedEntities.size() >= ReplayEntityTrack.MAX_TRACKS) return;
            Position p = pos(center);
            if (p == null) return;
            int startTick = now();
            int endTick = Math.min(MAX_TICKS, startTick + Math.max(1, durationTicks));
            UUID zoneId = UUID.randomUUID();
            TrackedEntity te = new TrackedEntity(zoneId, kind, bounded(typeId, ReplayEntityTrack.MAX_TYPE_ID_LENGTH),
                    owner, null, Math.clamp(radius, 0.5f, 32f), false, null);
            te.samples.add(new ReplayEntityTrack.EntitySample(startTick, p, (byte) 0, (byte) 0, ReplayEntityTrack.FLAG_ACTIVE));
            te.samples.add(new ReplayEntityTrack.EntitySample(endTick, p, (byte) 0, (byte) 0, ReplayEntityTrack.FLAG_BREAK));
            trackedEntities.put(zoneId, te);
        }

        void sampleTrackedEntity(TrackedEntity te, int tick) {
            Entity e = te.liveRef;
            if (e == null || e.isRemoved()) {
                te.liveRef = null;
                return;
            }
            if (e instanceof PlayerBodyEntity body) visual.corpse(body,tick);
            if (te.samples.size() >= ReplayEntityTrack.MAX_SAMPLES_PER_TRACK) return;
            Position p = pos(e.position());
            if (p == null) return;
            int flags = ReplayEntityTrack.FLAG_ACTIVE;
            if (e instanceof PlayerBodyEntity body) {
                if (body.isCorrupted()) flags |= ReplayEntityTrack.FLAG_CORRUPTED;
                if (body.getDeltaMovement().horizontalDistanceSqr() > 0.002 || body.isPassenger()) {
                    flags |= ReplayEntityTrack.FLAG_DRAGGED;
                }
            } else if (e instanceof TailorDollEntity doll) {
                if (doll.complete()) flags |= ReplayEntityTrack.FLAG_COMPLETE;
            } else if (te.kind == ReplayEntityTrack.EntityKind.PLACED_C4) {
                flags |= ReplayEntityTrack.FLAG_ARMED;
            }
            if (e.isInvisible()) flags |= ReplayEntityTrack.FLAG_HIDDEN;

            if (!te.samples.isEmpty()) {
                var last = te.samples.getLast();
                if (last.tick() == tick) return;
                // Skip stationary unchanged samples unless state changed
                if (last.flags() == flags && last.position().equals(p) && last.yaw() == yaw(e)) {
                    return;
                }
            }
            te.samples.add(new ReplayEntityTrack.EntitySample(tick, p, yaw(e), pitch(e), flags));
        }

        void addSkillEvent(ServerPlayer actor, @Nullable UUID targetUuid, String skillOrItemId,
                           String displayName, String category, Vec3 from, @Nullable Vec3 to, int durationTicks) {
            if (skillEvents.size() >= ReplaySkillEvent.MAX_SKILL_EVENTS) return;
            int tick = now();
            String id = bounded(skillOrItemId, ReplaySkillEvent.MAX_ID_LENGTH);
            if (id.isEmpty()) return;
            String throttleKey = actor.getUUID() + "|" + id;
            Integer prevTick = skillThrottle.get(throttleKey);
            if (prevTick != null && tick - prevTick < 8) return;
            skillThrottle.put(throttleKey, tick);

            var game = SREGameWorldComponent.KEY.get(level);
            Role r = role(game.getRole(actor));
            int accent = r.color != 0 ? (0xFF000000 | r.color) : DEFAULT_GOLD_COLOR;
            Position fromPos = pos(from);
            Position toPos = pos(to);
            String soundId = GlobalMatchSoundRecorder.recentSoundNear(this.id, tick, fromPos);
            boolean fallback = !isKnownAdapterSkillOrItem(id);
            String labelName = (displayName == null || displayName.isBlank()) ? humanizeId(id) : displayName;
            Person person = people.get(actor.getUUID());
            if (person != null) person.lastSkillTick = tick;

            skillEvents.add(new ReplaySkillEvent(allocateEventId(), tick, id, labelName, category,
                    actor.getUUID(), targetUuid, r.id, accent, fromPos, toPos,
                    Math.max(10, durationTicks), soundId, fallback));
        }

        void addDrone(ReconDroneEntity entity) {
            UUID drone = entity.getUUID(), owner = entity.ownerId();
            if (owner == null || !eligible(owner)) return;
            if (!drones.containsKey(drone) && drones.size() >= MAX_DRONES) { limited = true; return; }
            drones.computeIfAbsent(drone, id -> new DroneTrack(id, owner, new ArrayList<>()));
            deployed.put(drone, entity);
            sampleDrone(entity, now());
        }

        void removeDrone(ReconDroneEntity entity) {
            UUID id = entity.getUUID();
            if (!deployed.remove(id, entity)) return;
            var points = drones.get(id).track();
            if (points.isEmpty()) return;
            Sample last = points.getLast();
            ReplayTracks.appendV2(points, new Sample(now(), last.position(), last.yaw(), BREAK));
        }

        void sampleDrone(ReconDroneEntity entity, int tick) {
            var points = drones.get(entity.getUUID()).track();
            Position position = pos(entity.position());
            if (position == null) {
                if (!points.isEmpty() && (points.getLast().flags() & ALIVE) != 0) {
                    var last = points.getLast();
                    ReplayTracks.appendV2(points, new Sample(tick, last.position(), last.yaw(), BREAK));
                }
                return;
            }
            int flags = ALIVE;
            if (entity.isCrashed()) flags |= Drone.CRASHED;
            if (entity.isScrapped()) flags |= Drone.SCRAPPED;
            if (entity.isOperatorPiloting()) flags |= Drone.PILOTED;
            if (entity.isDroneSprinting()) flags |= SPRINT;
            if (!points.isEmpty()) {
                var last = points.getLast();
                var prior = last.position();
                if ((last.flags() & ALIVE) == 0 || Math.abs(prior.x() - position.x()) > 128
                        || Math.abs(prior.y() - position.y()) > 128 || Math.abs(prior.z() - position.z()) > 128) flags |= BREAK;
            }
            if (points.size() >= ReplayTracks.MAX_TRACK_V2) limited = true;
            ReplayTracks.appendV2(points, new Sample(tick, position, yaw(entity), (byte) flags,
                    pitch(entity), yaw(entity), 0, 0, "", "", null));
        }

        List<Drone> droneTracks(List<UUID> participants) {
            return drones.values().stream().filter(d -> !d.track().isEmpty() && participants.contains(d.owner()))
                    .map(d -> new Drone(d.id(), d.owner(), d.track())).toList();
        }

        List<ReplayBlockDelta> finalizeBlockDeltas() {
            if (blockDeltas.isEmpty()) return List.of();
            blockDeltas.sort(Comparator.comparingInt(ReplayBlockDelta::tick));
            return List.copyOf(blockDeltas);
        }

        List<ReplayEntityTrack> finalizeEntityTracks(int duration) {
            if (trackedEntities.isEmpty()) return List.of();
            List<ReplayEntityTrack> out = new ArrayList<>(trackedEntities.size());
            for (TrackedEntity te : trackedEntities.values()) {
                if (te.samples.isEmpty()) continue;
                List<ReplayEntityTrack.EntitySample> clamped = new ArrayList<>(te.samples.size());
                int prevT = 0;
                for (var s : te.samples) {
                    int t = Math.clamp(s.tick(), prevT, Math.max(prevT, duration));
                    prevT = t;
                    clamped.add(t == s.tick() ? s
                            : new ReplayEntityTrack.EntitySample(t, s.position(), s.yaw(), s.pitch(), s.flags()));
                }
                out.add(new ReplayEntityTrack(te.id, te.kind, te.typeId, te.ownerUuid, te.targetUuid, te.radius, clamped));
            }
            return List.copyOf(out);
        }

        List<ReplaySkillEvent> finalizeSkillEvents() {
            if (skillEvents.isEmpty()) return List.of();
            skillEvents.sort(Comparator.comparingInt(ReplaySkillEvent::tick).thenComparingInt(ReplaySkillEvent::eventId));
            return List.copyOf(skillEvents);
        }

        void sample(SREGameWorldComponent game, int tick) {
            lastSample = tick;
            visual.entities.tick(level,tick);
            boolean regularPlayerTick = (tick % 2 == 0);
            if (regularPlayerTick) {
                deployed.values().forEach(entity -> sampleDrone(entity, tick));
            }
            for (TrackedEntity te : trackedEntities.values()) {
                if (te.liveRef != null && (te.liveRef instanceof PlayerBodyEntity || (te.highRate ? (tick % 2 == 0) : (tick % 4 == 0)))) {
                    sampleTrackedEntity(te, tick);
                }
            }

            var seen = new HashSet<UUID>();
            for (ServerPlayer player : level.players()) {
                UUID id = player.getUUID();
                SRERole raw = game.getRole(player);
                if (excluded(raw) || RepairModeManager.isRepairer(id)) continue;
                if (!people.containsKey(id) && people.size() >= MAX_PLAYERS) { limited = true; continue; }
                visual.join(player);
                visual.body(player,tick);
                Role role = role(raw);
                Person p = people.computeIfAbsent(id, k -> new Person(player.getGameProfile().getName(), role));
                seen.add(id);

                // Creative mode is only used by OPs/testers, never by SRE eliminations; keep alive unless actually killed.
                boolean alive = (GameUtils.isPlayerAliveAndSurvival(player) || (player.isCreative() && !deaths.containsKey(id)))
                        && deaths.getOrDefault(id, -1) != tick;
                boolean impulseTick = alive && (player.swinging || player.hurtTime > 0);
                if (!regularPlayerTick && !impulseTick && !p.track.isEmpty() && p.lastRole.equals(role)) {
                    continue;
                }

                boolean psycho = alive && SREPlayerPsychoComponent.KEY.get(player).getPsychoTicks() > 0;
                Vec3 position = player.position();
                if (!p.lastRole.equals(role)) {
                    node(Kind.CHANGE_ROLE, id, null, position, null, p.lastRole.id + "===" + role.id, 0);
                    p.lastRole = role;
                }
                Position fixed = pos(position);
                if (fixed == null) {
                    if (p.present) markAbsent(p.track, tick);
                    p.present = false;
                    continue;
                }
                Sample last = p.track.isEmpty() ? null : p.track.getLast();
                if (!alive) {
                    if (last != null && (last.flags() & ALIVE) != 0) {
                        ReplayTracks.appendV2(p.track, new Sample(tick, fixed, yaw(player), (byte) 0));
                    }
                } else {
                    UUID disguiseTarget = resolveDisguiseTarget(player);
                    boolean disguised = player.hasEffect(ModEffects.DISGUISE) || disguiseTarget != null;

                    int flags = ALIVE;
                    if (player.isSprinting()) flags |= SPRINT;
                    if (player.isShiftKeyDown()) flags |= SNEAK;
                    if (disguised) flags |= DISGUISED;
                    if (last != null && ((last.flags() ^ flags) & DISGUISED) != 0) {
                        node(Kind.DISGUISE, id, disguiseTarget, position, null, (flags & DISGUISED) == 0 ? "end" : "start", 0);
                    }
                    if (psycho) flags |= PSYCHO;
                    boolean asleep = player.isSleeping() || SlothComponent.isSleepingSloth(player);
                    if (asleep) flags |= ASLEEP;
                    if (last != null && ((last.flags() & ALIVE) == 0 || !p.present
                            || Math.abs(last.position().x() - fixed.x()) > 128
                            || Math.abs(last.position().y() - fixed.y()) > 128
                            || Math.abs(last.position().z() - fixed.z()) > 128)) flags |= BREAK;
                    if (last != null && (last.flags() & ALIVE) == 0 && deaths.remove(id) != null) {
                        node(Kind.PLAYER_REVIVAL, id, null, position, null, role.id, 0);
                    }

                    int actionFlags = 0;
                    if (player.isShiftKeyDown()) actionFlags |= ACT_SNEAK;
                    if (player.isSprinting()) actionFlags |= ACT_SPRINT;
                    if (player.isSwimming() || player.isVisuallyCrawling()) actionFlags |= ACT_SWIM_CRAWL;
                    if (player.onGround()) actionFlags |= ACT_ON_GROUND;
                    if (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT) actionFlags |= ACT_LEFT_HANDED;
                    if (player.isUsingItem()) {
                        actionFlags |= ACT_USING_ITEM;
                        if (player.getUsedItemHand() == InteractionHand.OFF_HAND) actionFlags |= ACT_USING_OFF_HAND;
                        actionFlags |= Math.clamp(player.getUseItemRemainingTicks(), 0, 72_000) << ACT_USE_REMAINING_SHIFT;
                    }
                    if (player.swinging) {
                        actionFlags |= (player.swingingArm == InteractionHand.OFF_HAND) ? ACT_SWING_OFF : ACT_SWING_MAIN;
                    }
                    if (player.hurtTime > 0) actionFlags |= ACT_HURT_FLASH;

                    int statusFlags = computeStatusFlags(player, psycho, asleep, disguised);
                    String mainHand = resolveItemId(player.getMainHandItem());
                    String offHand = resolveItemId(player.getOffhandItem());

                    if (p.track.size() >= ReplayTracks.MAX_TRACK_V2) limited = true;
                    ReplayTracks.appendV2(p.track, new Sample(tick, fixed, yaw(player), (byte) flags,
                            pitch(player), Sample.quantizeYaw(player.getYHeadRot()),
                            actionFlags, statusFlags, mainHand, offHand, disguiseTarget));

                    // Detect newly added third-party/unknown mod MobEffects as fallback skill events
                    if (tick % 4 == 0) {
                        detectUnknownEffectFallback(player, p, tick);
                    }
                }
                p.present = true;
            }
            if (regularPlayerTick) {
                for (var entry : people.entrySet()) {
                    Person p = entry.getValue();
                    if (!seen.contains(entry.getKey()) && p.present && !p.track.isEmpty()) {
                        p.present = false;
                        markAbsent(p.track, tick);
                        environment(SpanKind.PSYCHO, entry.getKey(), false, tick, null);
                    }
                }
            }
        }

        private @Nullable UUID resolveDisguiseTarget(ServerPlayer player) {
            try {
                var morph = MorphlingPlayerComponent.KEY.get(player);
                if (morph != null && morph.morphTicks > 0 && morph.disguise != null) {
                    return morph.disguise;
                }
            } catch (RuntimeException ignored) {}
            return null;
        }

        private int computeStatusFlags(ServerPlayer player, boolean psycho, boolean asleep, boolean disguised) {
            int st = 0;
            if (player.isInvisible() || player.hasEffect(MobEffects.INVISIBILITY)) st |= STATUS_INVISIBLE;
            try {
                if (SREArmorPlayerComponent.KEY.get(player).getArmor() > 0) st |= STATUS_SHIELDED;
            } catch (RuntimeException ignored) {}
            try {
                if (SREPlayerPoisonComponent.KEY.get(player).getPoisonTicks() > 0 || player.hasEffect(MobEffects.POISON)) {
                    st |= STATUS_POISONED;
                }
            } catch (RuntimeException ignored) {}
            if (player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS)) st |= STATUS_BLIND;
            if (player.hasEffect(MobEffects.RAID_OMEN)) st |= STATUS_FLASHED;
            if (player.hasEffect(MobEffects.MOVEMENT_SPEED)) st |= STATUS_SPEED_BOOST;
            if (psycho) st |= STATUS_PSYCHO;
            if (asleep) st |= STATUS_ASLEEP;
            if (disguised) st |= STATUS_DISGUISED;
            if (MimeKillerComponent.isMuted(player) || player.hasEffect(ModEffects.VOICE_SILENCE)) st |= STATUS_MUTED;
            if (player.hasEffect(ModEffects.MOVE_BANED)) st |= STATUS_ROOTED;
            if (player.hasGlowingTag() || player.hasEffect(MobEffects.GLOWING)) st |= STATUS_GLOWING;
            try {
                if (C4BackComponent.hasC4(player)) st |= STATUS_C4_BACK;
            } catch (RuntimeException ignored) {}
            return st;
        }

        private void detectUnknownEffectFallback(ServerPlayer player, Person p, int tick) {
            Set<String> currentIds = new HashSet<>();
            // Skip emitting fallback toasts during the opening grace window (tick <= 10) or on first sample
            // so initial spawn/role passive buffs do not flood tick 0 with spurious skill cards.
            boolean emitAllowed = p.effectsInitialized && tick > 10;
            for (MobEffectInstance inst : player.getActiveEffects()) {
                MobEffect eff = inst.getEffect().value();
                ResourceLocation key = BuiltInRegistries.MOB_EFFECT.getKey(eff);
                if (key == null) continue;
                String id = key.toString();
                currentIds.add(id);
                if (emitAllowed && !p.knownEffects.contains(id) && !key.getNamespace().equals("minecraft")
                        && !inst.isAmbient() && !inst.isInfiniteDuration() && inst.getDuration() < 6000
                        && !isKnownAdapterSkillOrItem(id) && tick - p.lastSkillTick > 4) {
                    String display = eff.getDisplayName().getString();
                    addSkillEvent(player, null, id, display, ReplaySkillEvent.CAT_EFFECT,
                            player.position(), null, Math.clamp(inst.getDuration(), 20, 120));
                }
            }
            p.effectsInitialized = true;
            p.knownEffects.clear();
            p.knownEffects.addAll(currentIds);
        }

        void environment(SpanKind kind, UUID actor, boolean active, int at, Vec3 pos) {
            SpanKey key = new SpanKey(kind, actor);
            if (active && !open.containsKey(key)) {
                open.put(key, at);
                node(kind == SpanKind.BLACKOUT ? Kind.BLACKOUT_START : Kind.PSYCHO_STATE_CHANGE, actor, null,
                        pos, null, "start", SUCCESS);
            } else if (!active) {
                Integer previous = open.remove(key);
                if (previous != null) {
                    close(key, previous, at);
                    node(kind == SpanKind.BLACKOUT ? Kind.BLACKOUT_END : Kind.PSYCHO_STATE_CHANGE, actor, null,
                            pos, null, "end", 0);
                }
            }
        }

        void close(SpanKey key, int previous, int at) {
            if (spans.size() < MAX_SPANS) spans.add(new Span(key.kind, key.actor, previous, at));
            else limited = true;
        }

        Position pos(Vec3 point) {
            if (point == null) return null;
            return Position.quantize(point.x - bounds.minX(), point.y - bounds.minY(), point.z - bounds.minZ());
        }

        Vec3 position(UUID id) {
            if (id == null) return null;
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            return p != null && p.serverLevel() == level ? p.position() : null;
        }

        boolean node(Kind kind, UUID actor, UUID target, Vec3 from, Vec3 to, String detail, int flags) {
            int tick = now();
            Position fPos = pos(from);
            Position tPos = pos(to);
            String cleanDetail = bounded(detail, 128);
            for (int i = events.size() - 1; i >= Math.max(0, events.size() - 32); i--) {
                Node e = events.get(i);
                if (tick - e.tick() > (kind == Kind.PLAYER_REVIVAL || kind == Kind.CHANGE_ROLE || kind == Kind.DISGUISE ? 5 : 0)) break;
                if (e.kind() != kind || !Objects.equals(e.target(), target)
                        || !Objects.equals(e.actor(), actor) && kind != Kind.SHIELD_BROKEN && kind != Kind.KILL) continue;
                if (kind == Kind.LOCKPICK_ATTEMPT && Objects.equals(e.to(), tPos)) return false;
                if (kind == Kind.SKILL_RELEASE && e.detail().equals(cleanDetail) && tick == e.tick()) return false;
                if (kind == Kind.KILL || kind == Kind.POISON || kind == Kind.SHIELD_BROKEN
                        || kind == Kind.CHANGE_ROLE || kind == Kind.PLAYER_REVIVAL || kind == Kind.DISGUISE) {
                    if ((kind == Kind.POISON || kind == Kind.DISGUISE) && !e.detail().equals(cleanDetail)) {
                        if (kind != Kind.DISGUISE || !e.detail().isBlank()) continue;
                    }
                    if ((e.flags() & APPROXIMATE) != 0 || e.to() == null && tPos != null) {
                        // Preserve stable eventId on in-place upgrade (Defect #5 fix)
                        events.set(i, new Node(e.eventId(), tick, kind, actor, target, fPos, tPos, cleanDetail, flags));
                    }
                    return false;
                }
            }
            Node node = new Node(allocateEventId(), tick, kind, actor, target, fPos, tPos, cleanDetail, flags);
            return add(node) >= 0;
        }

        int add(Node node) {
            if (events.size() == MAX_EVENTS) {
                limited = true;
                if (!node.highlight()) return -1;
                int i = 1;
                while (i < events.size() && events.get(i).highlight()) i++;
                if (i == events.size()) i = 1;
                events.remove(i);
            }
            events.add(node);
            return events.size() - 1;
        }

        int findEventIndex(int eventId) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).eventId() == eventId) return i;
            }
            return -1;
        }

        void recordBackfire(UUID actor, @Nullable Position impact) {
            int tick = now();
            backfires.put(actor, new Backfire(tick, impact));
            Shot shot = shots.get(actor);
            if (shot != null && shot.tick == tick) {
                int idx = findEventIndex(shot.eventId);
                if (idx >= 0) events.set(idx, backfireShot(events.get(idx), impact));
            }
        }

        void markShot(UUID actor, int flag) {
            Shot shot = shots.get(actor);
            if (shot == null || now() - shot.tick > 2) return;
            int idx = findEventIndex(shot.eventId);
            if (idx < 0) return;
            Node node = events.get(idx);
            events.set(idx, new Node(node.eventId(), node.tick(), node.kind(), node.actor(), node.target(),
                    node.from(), node.to(), node.detail(), node.flags() | flag));
        }
    }
}
