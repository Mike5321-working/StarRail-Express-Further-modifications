package com.habitrain.core.game.sre.role.drone;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.ServerAimTargeting;
import com.habitrain.core.network.DroneCommandPayload;
import com.habitrain.core.network.DroneFxPayload;
import com.habitrain.core.network.DroneInputPayload;
import com.habitrain.core.network.DroneScanHighlightPayload;
import com.habitrain.core.network.DroneStatePayload;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import io.wifi.starrailexpress.content.item.KnifeItem;
import io.wifi.starrailexpress.index.TMMItems;
import io.wifi.starrailexpress.index.TMMSounds;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.KillerKnifeDurability;
import io.wifi.starrailexpress.index.tag.TMMItemTags;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.exmo.sre.meeting.MeetingManager;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.game.roles.innocence.fool.TarotAssemblyManager;
import org.agmas.noellesroles.init.ModItems;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative server-side lifecycle, battery loop, FPV lock, pulse scan,
 * belly mount collaboration, and 1-per-round lethal fuse manager for
 * {@code habitrain_core:drone_operator}.
 */
public final class DroneOperatorService {

    public static final EntityType<ReconDroneEntity> RECON_DRONE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            HabiTrainCore.id("recon_drone"),
            EntityType.Builder.<ReconDroneEntity>of(ReconDroneEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.35F)
                    .clientTrackingRange(80)
                    .updateInterval(1)
                    .fireImmune()
                    .noSave()
                    .build("habitrain_core:recon_drone")
    );

    private static final TicketType<UUID> DRONE_TICKET =
            TicketType.create("habitrain_drone", Comparator.comparing(UUID::toString));
    private static final ResourceLocation NET_ROOT_JUMP_MODIFIER_ID =
            HabiTrainCore.id("drone_net_root_jump");

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final DroneRoundLedger<ServerLevel, ItemStack> ROUNDS = new DroneRoundLedger<>();
    private static final Map<UUID, Integer> NET_ROOTED_PLAYERS = new HashMap<>();
    private static final List<SilenceZone> SILENCE_ZONES = new ArrayList<>();
    private static final Set<ReconDroneEntity> SCRAPPED_DRONES = new HashSet<>();
    // Read by the voice thread; only the game thread looks at worlds or player roles.
    private static volatile Set<UUID> voiceMuted = Set.of();
    private static boolean initialized;

    private static final class SilenceZone {
        final ServerLevel level;
        final Vec3 center;
        final double radiusSq;
        int remainingTicks;

        SilenceZone(ServerLevel level, Vec3 center, double radius, int ticks) {
            this.level = level;
            this.center = center;
            this.radiusSq = radius * radius;
            this.remainingTicks = ticks;
        }
    }

    private static final class State {
        final ServerPlayer player;
        final ServerLevel matchLevel;
        final DroneRoundLedger.Frame<ItemStack> frame;

        @Nullable ReconDroneEntity drone;
        @Nullable ServerLevel ticketLevel;
        @Nullable ChunkPos ticketChunk;
        @Nullable UUID ticketEntityUuid;

        boolean fpvActive = false;
        @Nullable Vec3 bodyAnchor;
        float bodyYaw;
        float bodyPitch;

        float inForward;
        float inStrafe;
        float inVertical;
        float inYaw;
        float inPitch;
        boolean inSprint;

        float lockedWindupYaw;
        float lockedWindupPitch;

        int destroyedStaticTicks = 0;
        int unboxTicks = 0;
        float lastDrainPerSec = 0.0F;
        long lastActionGameTime = -20L;
        final long[] lastCommandByAction = new long[DroneCommandPayload.Action.values().length];
        long lastResupplyBuyGameTime = -20L;
        @Nullable DroneStatePayload lastSentPayload;

        State(ServerPlayer player) {
            this.player = player;
            this.matchLevel = player.serverLevel();
            this.frame = ROUNDS.frameFor(matchLevel, player.getUUID());
            java.util.Arrays.fill(this.lastCommandByAction, -20L);
        }
    }

    private DroneOperatorService() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        FabricDefaultAttributeRegistry.register(RECON_DRONE, ReconDroneEntity.attributes());

        ServerTickEvents.END_SERVER_TICK.register(DroneOperatorService::tickServer);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            if (isEligibleOperator(player)) {
                HabiRoleItems.ensureDroneTerminal(player);
                sendState(stateFor(player));
            } else {
                HabiRoleItems.reclaimDroneItems(player);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clearPlayer(handler.player));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, from, to) -> clearPlayer(player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                clearNetRoot(p);
            }
            for (State state : List.copyOf(STATES.values())) {
                clearPlayer(state.player);
            }
            discardScrappedDrones(null);
            STATES.clear();
            ROUNDS.clear();
            NET_ROOTED_PLAYERS.clear();
            SILENCE_ZONES.clear();
            voiceMuted = Set.of();
        });

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isLockedInFpv(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                isLockedInFpv(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (isLockedInFpv(player)) {
                return InteractionResult.FAIL;
            }
            ItemStack stack = player.getItemInHand(hand);
            if (HabiRoleItems.isDroneTerminal(stack)) {
                if (level.isClientSide) {
                    return InteractionResult.SUCCESS;
                }
                if (player instanceof ServerPlayer sp) {
                    onUseTerminal(sp);
                }
                return InteractionResult.CONSUME;
            }
            if (HabiRoleItems.isDroneBattery(stack)) {
                if (!level.isClientSide && player instanceof ServerPlayer sp && tryUseSpareBatteryInHand(sp)) {
                    return InteractionResult.CONSUME;
                }
            }
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isLockedInFpv(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (isLockedInFpv(player)) {
                return InteractionResultHolder.fail(stack);
            }
            if (HabiRoleItems.isDroneTerminal(stack)) {
                if (level.isClientSide) {
                    return InteractionResultHolder.success(stack);
                }
                if (player instanceof ServerPlayer sp) {
                    onUseTerminal(sp);
                }
                return InteractionResultHolder.consume(stack);
            }
            if (HabiRoleItems.isDroneBattery(stack)) {
                if (level.isClientSide) {
                    return InteractionResultHolder.pass(stack);
                }
                if (player instanceof ServerPlayer sp && tryUseSpareBatteryInHand(sp)) {
                    return InteractionResultHolder.consume(stack);
                }
            }
            return InteractionResultHolder.pass(stack);
        });

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!isSilencedByField(sender)) return true;
            sender.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.silenced"), true);
            return false;
        });
    }

    private static State stateFor(ServerPlayer player) {
        State existing = STATES.get(player.getUUID());
        if (existing != null && (existing.player != player || existing.matchLevel != player.serverLevel())) {
            clearPlayer(existing.player);
        }
        return STATES.computeIfAbsent(player.getUUID(), id -> new State(player));
    }

    private static boolean meetingActive() {
        return MeetingManager.isActive() || TarotAssemblyManager.havingMeeting;
    }

    private static boolean hasLiveFpv(State state, @Nullable ReconDroneEntity drone) {
        return state.fpvActive && drone != null && !drone.isRemoved()
                && drone.level() == state.player.level() && drone.droneMode() == ReconDroneEntity.MODE_ACTIVE
                && !meetingActive();
    }

    private static int scanCooldownTicks(State state) {
        return (int) Math.max(0L, state.frame.scanReadyAt - state.matchLevel.getGameTime());
    }

    public static boolean isEligibleOperator(@Nullable Player player) {
        return player instanceof ServerPlayer sp
                && ServerAimTargeting.isAliveParticipant(sp)
                && HabiRoles.isHabiRole(sp, HabiRoles.DRONE_OPERATOR_ID);
    }

    public static boolean isLockedInFpv(@Nullable Player player) {
        if (!(player instanceof ServerPlayer sp)) return false;
        State state = STATES.get(sp.getUUID());
        return state != null && state.player == sp && state.fpvActive;
    }

    public static boolean owns(ReconDroneEntity drone) {
        UUID ownerId = drone.ownerId();
        if (ownerId == null) return false;
        State state = STATES.get(ownerId);
        return state != null && state.drone == drone;
    }

    public static boolean isTrackedScrapped(ReconDroneEntity drone) {
        return drone != null && SCRAPPED_DRONES.contains(drone);
    }

    public static void untrackScrapped(ReconDroneEntity drone) {
        if (drone != null) {
            SCRAPPED_DRONES.remove(drone);
        }
    }

    private static void discardScrappedDrones(@Nullable ServerLevel level) {
        for (ReconDroneEntity scrapped : List.copyOf(SCRAPPED_DRONES)) {
            if (level == null || scrapped.level() == level || scrapped.isRemoved()) {
                SCRAPPED_DRONES.remove(scrapped);
                if (!scrapped.isRemoved()) {
                    scrapped.discard();
                }
            }
        }
    }

    public static boolean useLinkSkill(RoleSkill.RoleSkillContext context) {
        ServerPlayer player = context.player();
        if (player == null || !isEligibleOperator(player)) return false;
        onUseTerminal(player);
        return true;
    }

    public static void onRoleAssigned(ServerPlayer player) {
        if (player == null) return;
        clearPlayer(player);
        State state = stateFor(player);
        HabiRoleItems.ensureDroneTerminal(player);
        sendState(state);
    }

    public static void onRoleLost(ServerPlayer player) {
        if (player == null) return;
        clearPlayer(player);
        ROUNDS.frameFor(player.serverLevel(), player.getUUID()).destroyFrame();
        HabiRoleItems.reclaimDroneItems(player);
    }

    public static void onOperatorDeath(ServerPlayer player) {
        if (player == null) return;
        clearPlayer(player);
        ROUNDS.frameFor(player.serverLevel(), player.getUUID()).destroyFrame();
        HabiRoleItems.reclaimDroneItems(player);
    }

    public static void onOperatorDamaged(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state != null && state.fpvActive) {
            exitFpvToBody(state, "message.habitrain_core.drone.body_hit_exit");
        }
    }

    public static void exitFpvQuietly(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state != null && state.fpvActive) {
            exitFpvToBody(state, null);
        }
    }

    public static void onMeetingStart(@Nullable ServerLevel level) {
        if (level == null) return;
        for (State state : List.copyOf(STATES.values())) {
            if (state.matchLevel == level) packUpForMeeting(state);
        }
        discardScrappedDrones(level);
        SILENCE_ZONES.removeIf(z -> z.level == level);
        refreshVoiceMutes();
        clearScanHighlights(level);
    }

    public static void onGameEnd(@Nullable ServerLevel level) {
        if (level == null) return;
        for (State state : List.copyOf(STATES.values())) {
            if (state.matchLevel == level) clearPlayer(state.player);
        }
        discardScrappedDrones(level);
        for (ServerPlayer player : level.players()) {
            HabiRoleItems.reclaimDroneItems(player);
            clearNetRoot(player);
        }
        ROUNDS.endRound(level);
        SILENCE_ZONES.removeIf(z -> z.level == level);
        refreshVoiceMutes();
        clearScanHighlights(level);
    }

    private static void clearScanHighlights(ServerLevel level) {
        var packet = new DroneScanHighlightPayload(false, 0, 0, 0, List.of());
        for (ServerPlayer player : level.players()) {
            if (ServerPlayNetworking.canSend(player, DroneScanHighlightPayload.TYPE)) {
                ServerPlayNetworking.send(player, packet);
            }
        }
    }

    public static boolean canBuyReplacementDrone(Player player) {
        if (player == null) {
            return false;
        }
        if (player.level().isClientSide()) {
            return HabiRoles.isHabiRole(player, HabiRoles.DRONE_OPERATOR_ID) && player.isAlive() && !player.isSpectator();
        }
        if (!(player instanceof ServerPlayer sp) || !isEligibleOperator(sp) || meetingActive()) {
            return false;
        }
        State state = stateFor(sp);
        if (sp.level().getGameTime() - state.lastResupplyBuyGameTime < 6L) {
            return false;
        }
        return DroneRules.canBuyReplacementDrone(state.frame.hasDroneFrame, state.drone != null && !state.drone.isRemoved());
    }

    public static boolean grantReplacementDrone(Player player) {
        if (!(player instanceof ServerPlayer sp) || !canBuyReplacementDrone(sp)) {
            return false;
        }
        State state = stateFor(sp);
        state.lastResupplyBuyGameTime = sp.level().getGameTime();

        boolean scrappedOld = false;
        if (state.drone != null && !state.drone.isRemoved()) {
            scrappedOld = true;
            scrapActiveDrone(state);
        } else if (state.frame.hasDroneFrame) {
            scrappedOld = true;
        }

        state.frame.hasDroneFrame = true;
        state.frame.everHadDrone = true;
        state.frame.stowedBattery = DroneRules.MAX_BATTERY;
        state.frame.drainAccumulator = 0.0D;
        state.frame.clearStowedPayload();
        state.destroyedStaticTicks = 0;
        state.unboxTicks = DroneRules.UNBOX_TICKS;

        HabiRoleItems.ensureDroneTerminal(sp);
        ServerLevel level = sp.serverLevel();
        level.playSound(null, sp.blockPosition(), SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.PLAYERS, 0.9F, 1.35F);
        level.playSound(null, sp.blockPosition(), SoundEvents.ARMOR_EQUIP_NETHERITE.value(), SoundSource.PLAYERS, 1.0F, 1.25F);
        sp.playNotifySound(SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.75F, 1.85F);
        broadcastFx(level, sp.getId(), DroneFxPayload.FX_RESUPPLY_UNBOX,
                sp.position().add(0.0D, 1.05D, 0.0D), sp.getEyePosition());
        sp.displayClientMessage(DroneMessages.create(
                scrappedOld
                        ? "message.habitrain_core.drone.resupplied_scrapped_old"
                        : "message.habitrain_core.drone.resupplied"
        ), true);
        sendState(state);
        return true;
    }

    private static void scrapActiveDrone(State state) {
        ReconDroneEntity old = state.drone;
        if (old == null) return;
        state.fpvActive = false;
        releaseChunkTicket(state);
        state.drone = null;
        if (old.isRemoved()) return;

        old.setOperatorPiloting(false);
        old.setBattery(0);
        old.setChargeTicks(0);
        old.setDroneSprinting(false);
        old.setTilt(0.42F, 0.32F);
        old.setPayload(ItemStack.EMPTY, DronePayloadCategory.NONE);
        old.setDroneMode(ReconDroneEntity.MODE_SCRAPPED, DroneRules.SCRAP_WRECK_TICKS);
        old.setDeltaMovement(new Vec3(0.0D, -0.10D, 0.0D));
        SCRAPPED_DRONES.add(old);

        if (old.level() instanceof ServerLevel sl) {
            sl.playSound(null, old.blockPosition(), SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(),
                    SoundSource.PLAYERS, 0.95F, 0.75F);
            sl.playSound(null, old.blockPosition(), SoundEvents.ITEM_BREAK,
                    SoundSource.PLAYERS, 0.85F, 0.8F);
            broadcastFx(sl, old.getId(), DroneFxPayload.FX_DRONE_SCRAPPED,
                    old.position(), old.position().add(0.0D, -1.0D, 0.0D));
        }
    }

    private static void onUseTerminal(ServerPlayer player) {
        if (!isEligibleOperator(player) || meetingActive()) return;
        State state = stateFor(player);

        long now = player.level().getGameTime();
        if (now - state.lastActionGameTime < 4L) return;
        state.lastActionGameTime = now;

        if (state.drone != null && !state.drone.isRemoved()) {
            if (player.isShiftKeyDown()) {
                tryRecallFold(state);
                return;
            }
            if (state.drone.isCrashed()) {
                if (canInteractAtCloseRange(player, state.drone)) {
                    recoverCrashedDrone(state, player);
                } else {
                    player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.crashed_far"), true);
                }
                return;
            }
            if (state.fpvActive) {
                exitFpvToBody(state, null);
            } else if (state.drone.battery() > 0 && state.drone.droneMode() == ReconDroneEntity.MODE_ACTIVE) {
                enterFpv(state);
                sendState(state);
            }
            return;
        }

        if (!state.frame.hasDroneFrame) {
            player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 0.8F);
            player.displayClientMessage(DroneMessages.create(
                    state.frame.everHadDrone
                            ? "message.habitrain_core.drone.destroyed_need_buy"
                            : "message.habitrain_core.drone.no_initial_drone"
            ), true);
            return;
        }

        state.unboxTicks = 0;

        if (player.isShiftKeyDown() && state.frame.stowedBattery < DroneRules.MAX_BATTERY) {
            if (tryUseSpareBatteryInHand(player)) {
                return;
            }
        }

        if (state.frame.stowedBattery <= 0) {
            if (HabiRoleItems.consumeDroneBattery(player)) {
                state.frame.stowedBattery = DroneRules.MAX_BATTERY;
                state.frame.drainAccumulator = 0.0D;
                player.playNotifySound(SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.8F, 1.5F);
                player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.hand_recharged"), true);
                sendState(state);
                return;
            }
            player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 0.9F);
            player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.zero_battery_stowed"), true);
            return;
        }

        deployDrone(state);
    }

    private static boolean tryUseSpareBatteryInHand(ServerPlayer player) {
        if (!isEligibleOperator(player) || meetingActive()) return false;
        State state = stateFor(player);

        if (state.drone != null && !state.drone.isRemoved()) {
            if (canInteractAtCloseRange(player, state.drone) && state.drone.battery() < DroneRules.MAX_BATTERY) {
                startMagneticBatterySwap(state);
                return true;
            }
            return false;
        }

        if (state.frame.hasDroneFrame && state.frame.stowedBattery < DroneRules.MAX_BATTERY) {
            if (HabiRoleItems.consumeDroneBattery(player)) {
                state.frame.stowedBattery = DroneRules.MAX_BATTERY;
                state.frame.drainAccumulator = 0.0D;
                player.playNotifySound(SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.85F, 1.5F);
                player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.hand_recharged"), true);
                sendState(state);
                return true;
            }
        }
        return false;
    }

    private static void deployDrone(State state) {
        ServerPlayer player = state.player;
        ServerLevel level = player.serverLevel();
        if (meetingActive()) return;

        Vec3 look = player.getLookAngle();
        Vec3 desired = player.getEyePosition().add(look.scale(1.05D)).add(0.0D, 0.1D, 0.0D);
        HitResult clip = level.clip(new ClipContext(
                player.getEyePosition(), desired, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        Vec3 spawnPos = clip.getType() == HitResult.Type.MISS
                ? desired
                : player.getEyePosition().lerp(clip.getLocation(), 0.55D);

        ReconDroneEntity drone = RECON_DRONE.create(level);
        if (drone == null) return;
        drone.moveTo(spawnPos.x, spawnPos.y - 0.15D, spawnPos.z, player.getYRot(), player.getXRot());
        drone.setup(player.getUUID(), state.frame.stowedBattery, player.getYRot(), player.getXRot());
        if (state.frame.hasStowedPayload() && state.frame.stowedPayload != null && !state.frame.stowedPayload.isEmpty()) {
            drone.setPayload(state.frame.stowedPayload.copy(), state.frame.stowedPayloadCategory, state.frame.stowedPayloadMounter);
        }

        if (!level.addFreshEntity(drone)) {
            return;
        }
        state.frame.clearStowedPayload();

        state.drone = drone;
        updateChunkTicket(state, level, new ChunkPos(drone.blockPosition()), drone.getUUID());
        enterFpv(state);
        state.inYaw = player.getYRot();
        state.inPitch = player.getXRot();

        player.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, drone.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.7F, 1.85F);
        level.playSound(null, drone.blockPosition(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 0.8F, 1.4F);
        broadcastFx(level, drone.getId(), DroneFxPayload.FX_DEPLOY_BURST, drone.position(), player.getEyePosition());
        sendState(state);
    }

    private static void enterFpv(State state) {
        ServerPlayer player = state.player;
        player.stopUsingItem();
        player.setDeltaMovement(Vec3.ZERO);
        state.fpvActive = true;
        state.bodyAnchor = player.position();
        state.bodyYaw = player.getYRot();
        state.bodyPitch = 32.0F;
        if (state.drone != null) {
            state.drone.setOperatorPiloting(true);
            state.inYaw = state.drone.getYRot();
            state.inPitch = state.drone.getXRot();
        }
        state.inForward = 0.0F;
        state.inStrafe = 0.0F;
        state.inVertical = 0.0F;
        state.inSprint = false;
    }

    private static void exitFpvToBody(State state, @Nullable String messageKey) {
        if (!state.fpvActive) return;
        state.fpvActive = false;
        state.inForward = 0.0F;
        state.inStrafe = 0.0F;
        state.inVertical = 0.0F;
        state.inSprint = false;
        if (state.drone != null && !state.drone.isRemoved()) {
            state.drone.setOperatorPiloting(false);
            state.drone.setChargeTicks(0);
            if (state.drone.droneMode() == ReconDroneEntity.MODE_DEPLOYING) {
                state.drone.setDroneMode(ReconDroneEntity.MODE_ACTIVE, 0);
            }
            state.drone.setDroneSprinting(false);
            state.drone.setTilt(0.0F, 0.0F);
            if (!state.drone.isCrashed()) {
                state.drone.setDeltaMovement(Vec3.ZERO);
            }
        }
        if (messageKey != null && !state.player.hasDisconnected()) {
            state.player.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.9F, 0.75F);
            state.player.displayClientMessage(DroneMessages.create(messageKey), true);
        }
        sendState(state);
    }

    public static void handleInput(ServerPlayer player, DroneInputPayload payload) {
        if (player == null || payload == null || !player.server.isSameThread()) {
            return;
        }
        State state = STATES.get(player.getUUID());
        if (state == null || state.player != player || !hasLiveFpv(state, state.drone)) {
            return;
        }
        state.inForward = payload.forward();
        state.inStrafe = payload.strafe();
        state.inVertical = payload.vertical();
        state.inYaw = payload.yaw();
        state.inPitch = payload.pitch();
        state.inSprint = payload.sprinting();
    }

    public static void handleCommand(ServerPlayer player, DroneCommandPayload payload) {
        if (player == null || payload == null || payload.action() == null || !player.server.isSameThread()) return;
        if (!isEligibleOperator(player) || meetingActive()) return;
        State state = stateFor(player);
        int actionIdx = payload.action().ordinal();
        long now = player.level().getGameTime();
        if (payload.action() != DroneCommandPayload.Action.EXIT_FPV
                && now - state.lastCommandByAction[actionIdx] < 2L) {
            return;
        }
        state.lastCommandByAction[actionIdx] = now;

        switch (payload.action()) {
            case DEPLOY_OR_TOGGLE -> onUseTerminal(player);
            case EXIT_FPV -> exitFpvToBody(state, null);
            case RECALL_FOLD -> tryRecallFold(state);
            case SWAP_BATTERY -> trySwapBatteryCommand(state);
            case PULSE_SCAN -> tryPulseScan(state);
            case TRIGGER_PAYLOAD -> tryTriggerPayload(state);
            case UNLOAD_PAYLOAD -> {
                if (state.drone != null && !state.drone.isRemoved()
                        && (state.drone.droneMode() == ReconDroneEntity.MODE_ACTIVE || state.drone.isCrashed())
                        && state.drone.payloadCategory().hasPayload()
                        && state.drone.chargeTicks() <= 0
                        && canInteractAtCloseRange(player, state.drone)) {
                    unloadPayloadAndDestroyDrone(state, state.drone, player);
                } else {
                    player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.recall_need_close"), true);
                }
            }
            case USE_SPARE_BATTERY -> tryUseSpareBatteryInHand(player);
        }
    }

    private static void tryRecallFold(State state) {
        ServerPlayer player = state.player;
        ReconDroneEntity drone = state.drone;
        if (drone == null || drone.isRemoved()
                || (drone.droneMode() != ReconDroneEntity.MODE_ACTIVE && !drone.isCrashed())) {
            if (hasNearbyScrappedDrone(player)) {
                player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.85F, 0.7F);
                player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.scrapped_unrecoverable"), true);
            }
            return;
        }
        if (!canInteractAtCloseRange(player, drone)) {
            player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.7F, 1.2F);
            player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.recall_need_close"), true);
            return;
        }
        if (drone.chargeTicks() > 0) return;

        state.fpvActive = false;
        drone.setOperatorPiloting(false);
        drone.setDroneMode(ReconDroneEntity.MODE_RECALLING, DroneRules.RECALL_TICKS);
        player.serverLevel().playSound(null, drone.blockPosition(), SoundEvents.BEACON_DEACTIVATE,
                SoundSource.PLAYERS, 0.75F, 1.75F);
        broadcastFx(player.serverLevel(), drone.getId(), DroneFxPayload.FX_RECALL_STREAM,
                drone.position(), player.getEyePosition().subtract(0.0D, 0.25D, 0.0D));
        sendState(state);
    }

    private static void trySwapBatteryCommand(State state) {
        ServerPlayer player = state.player;
        ReconDroneEntity drone = state.drone;
        if (drone == null || drone.isRemoved()
                || (drone.droneMode() != ReconDroneEntity.MODE_ACTIVE && !drone.isCrashed())) return;
        if (!canInteractAtCloseRange(player, drone)) {
            player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.7F, 1.2F);
            player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.recall_need_close"), true);
            return;
        }
        if (drone.battery() >= DroneRules.MAX_BATTERY) {
            player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.battery_already_full"), true);
            return;
        }
        if (HabiRoleItems.countDroneBatteries(player) <= 0) {
            player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.75F, 0.85F);
            player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.no_spare_battery"), true);
            return;
        }
        startMagneticBatterySwap(state);
    }

    private static void startMagneticBatterySwap(State state) {
        ServerPlayer player = state.player;
        ReconDroneEntity drone = state.drone;
        if (drone == null || drone.isRemoved() || drone.chargeTicks() > 0
                || (drone.droneMode() != ReconDroneEntity.MODE_ACTIVE && !drone.isCrashed())
                || !canInteractAtCloseRange(player, drone)) return;
        if (!HabiRoleItems.consumeDroneBattery(player)) {
            player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.no_spare_battery"), true);
            return;
        }
        state.fpvActive = false;
        drone.setOperatorPiloting(false);
        Vec3 hoverSpot = magneticSwapHoverPos(player);
        drone.moveTo(hoverSpot.x, hoverSpot.y, hoverSpot.z, player.getYRot() + 180.0F, 0.0F);
        drone.setDeltaMovement(Vec3.ZERO);
        drone.setDroneMode(ReconDroneEntity.MODE_SWAPPING, DroneRules.BATTERY_SWAP_TICKS);
        player.serverLevel().playSound(null, drone.blockPosition(), SoundEvents.IRON_TRAPDOOR_OPEN,
                SoundSource.PLAYERS, 0.85F, 1.4F);
        player.serverLevel().sendParticles(ParticleTypes.CLOUD,
                drone.getX(), drone.getY() + 0.2D, drone.getZ(), 8, 0.12D, 0.08D, 0.12D, 0.02D);
        sendState(state);
    }

    private static Vec3 magneticSwapHoverPos(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 forward = Vec3.directionFromRotation(0.0F, player.getYRot()).scale(1.15D);
        Vec3 desired = eye.add(forward).subtract(0.0D, 0.32D, 0.0D);
        HitResult hit = player.level().clip(new ClipContext(
                eye, desired, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        return hit.getType() == HitResult.Type.MISS ? desired : eye.lerp(hit.getLocation(), 0.6D);
    }

    private static void recoverCrashedDrone(State state, ServerPlayer player) {
        ReconDroneEntity drone = state.drone;
        if (drone == null) return;
        if (HabiRoleItems.countDroneBatteries(player) > 0) {
            startMagneticBatterySwap(state);
            return;
        }
        stowDronePayload(state, drone);
        state.frame.stowedBattery = 0;
        state.frame.drainAccumulator = 0.0D;
        removeDroneEntity(state);
        player.playNotifySound(SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.9F, 1.2F);
        player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.crashed_recovered"), true);
        sendState(state);
    }

    private static boolean hasNearbyScrappedDrone(ServerPlayer player) {
        for (ReconDroneEntity scrapped : SCRAPPED_DRONES) {
            if (!scrapped.isRemoved()
                    && scrapped.level() == player.level()
                    && player.getUUID().equals(scrapped.ownerId())
                    && scrapped.distanceToSqr(player) <= 12.25D) {
                return true;
            }
        }
        return false;
    }

    public static InteractionResult handleEntityInteract(ReconDroneEntity drone, ServerPlayer actor) {
        if (!ServerAimTargeting.isAliveParticipant(actor) || drone.isRemoved() || meetingActive()) {
            return InteractionResult.PASS;
        }
        UUID ownerId = drone.ownerId();
        if (ownerId == null) return InteractionResult.PASS;
        boolean isOwner = actor.getUUID().equals(ownerId);

        if (drone.isScrapped()) {
            if (isOwner) {
                actor.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.85F, 0.7F);
                actor.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.scrapped_unrecoverable"), true);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.PASS;
        }

        State state = STATES.get(ownerId);
        if (state == null || state.drone != drone || state.player.level() != drone.level()
                || !isEligibleOperator(state.player)) return InteractionResult.PASS;

        boolean isCollaborator = isKillerTeamCollaborator(actor);
        if (!isOwner && !isCollaborator) {
            return InteractionResult.PASS;
        }

        ItemStack held = actor.getMainHandItem();
        if (isOwner && !actor.isShiftKeyDown() && HabiRoleItems.isDroneTerminal(held)) {
            onUseTerminal(actor);
            return InteractionResult.SUCCESS;
        }

        if (!canInteractAtCloseRange(actor, drone)) {
            actor.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.recall_need_close"), true);
            return InteractionResult.CONSUME;
        }

        boolean canUnload = DroneRules.canUnloadMountedPayload(actor.getUUID(), ownerId, drone.payloadMounterId());

        if (drone.isCrashed()) {
            if (actor.isShiftKeyDown() && drone.payloadCategory().hasPayload() && drone.chargeTicks() <= 0) {
                if (canUnload) {
                    unloadPayloadAndDestroyDrone(state, drone, actor);
                    return InteractionResult.SUCCESS;
                }
                return InteractionResult.PASS;
            }
            if (isOwner) {
                recoverCrashedDrone(state, actor);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }

        if (drone.droneMode() != ReconDroneEntity.MODE_ACTIVE) return InteractionResult.CONSUME;

        // Sneak + right-click: unload mounted payload back to operator or payload mounter and destroy the drone
        if (actor.isShiftKeyDown()) {
            if (drone.payloadCategory().hasPayload() && drone.chargeTicks() <= 0) {
                if (canUnload) {
                    unloadPayloadAndDestroyDrone(state, drone, actor);
                    return InteractionResult.SUCCESS;
                }
                return InteractionResult.PASS;
            }
            if (isOwner) {
                tryRecallFold(state);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }

        if (isOwner && HabiRoleItems.isDroneBattery(held) && drone.battery() < DroneRules.MAX_BATTERY) {
            startMagneticBatterySwap(state);
            return InteractionResult.SUCCESS;
        }

        if (isOwner && held.isEmpty()) {
            tryRecallFold(state);
            return InteractionResult.SUCCESS;
        }

        // Belly payload loading by owner OR living killer ally!
        DronePayloadCategory category = DronePayloadHandler.classify(held);
        if (!category.hasPayload()) {
            return InteractionResult.PASS;
        }
        if (drone.payloadCategory().hasPayload()) {
            actor.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.mount_occupied"), true);
            return InteractionResult.CONSUME;
        }
        if (!DroneRules.canMountPayload(category, state.frame.hasDroneFrame, false)) {
            actor.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.85F, 0.75F);
            actor.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.destroyed_need_buy"), true);
            return InteractionResult.CONSUME;
        }

        ItemStack single = held.copyWithCount(1);
        held.shrink(1);
        drone.setPayload(single, category, actor.getUUID());
        actor.swing(InteractionHand.MAIN_HAND, true);
        actor.serverLevel().playSound(null, drone.blockPosition(), SoundEvents.ARMOR_EQUIP_NETHERITE.value(),
                SoundSource.PLAYERS, 1.0F, 1.35F);
        Component itemName = DroneMessages.captureItemName(single);
        actor.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.mounted", itemName), true);
        if (!isOwner && !state.player.hasDisconnected()) {
            state.player.displayClientMessage(DroneMessages.create(
                    "message.habitrain_core.drone.ally_mounted", actor.getDisplayName(), itemName), true);
        }
        sendState(state);
        return InteractionResult.SUCCESS;
    }

    private static void stowDronePayload(State state, ReconDroneEntity drone) {
        if (drone.payloadCategory().hasPayload() && !drone.payloadStack().isEmpty()) {
            state.frame.setStowedPayload(drone.payloadStack().copy(), drone.payloadCategory(), drone.payloadMounterId());
        } else {
            state.frame.clearStowedPayload();
        }
        drone.setPayload(ItemStack.EMPTY, DronePayloadCategory.NONE);
    }

    private static void unloadPayloadAndDestroyDrone(State state, ReconDroneEntity drone, ServerPlayer receiver) {
        if (!DroneRules.canUnloadMountedPayload(receiver.getUUID(), drone.ownerId(), drone.payloadMounterId())) {
            return;
        }
        ItemStack stack = drone.payloadStack();
        DroneRules.PayloadUnloadResult unloadRule = DroneRules.unloadMountedPayload(drone.payloadCategory());
        if (stack.isEmpty() || !unloadRule.unloaded()) return;
        ItemStack stackCopy = stack.copy();
        drone.setPayload(ItemStack.EMPTY, DronePayloadCategory.NONE);
        Component itemName = DroneMessages.transferUnloadedPayload(
                stackCopy,
                receiver.getInventory()::add,
                leftover -> receiver.drop(leftover, false)
        );

        if (drone.level() instanceof ServerLevel sl) {
            drone.spawnDestructionBurst(sl);
        }
        releaseChunkTicket(state);
        state.drone = null;
        state.frame.destroyFrame();
        if (state.fpvActive) {
            state.destroyedStaticTicks = DroneRules.DESTROYED_STATIC_TICKS;
        }
        state.fpvActive = false;
        drone.setOperatorPiloting(false);
        drone.discard();

        if (receiver == state.player) {
            receiver.playNotifySound(SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 1.0F, 0.65F);
            receiver.displayClientMessage(DroneMessages.create(
                    "message.habitrain_core.drone.unloaded_destroyed", itemName), true);
        } else {
            receiver.playNotifySound(SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.9F, 0.9F);
            receiver.displayClientMessage(DroneMessages.create(
                    "message.habitrain_core.drone.unloaded", itemName), true);
            if (!state.player.hasDisconnected()) {
                state.player.playNotifySound(SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 1.0F, 0.65F);
                state.player.displayClientMessage(DroneMessages.create(
                        "message.habitrain_core.drone.ally_unloaded_destroyed",
                        receiver.getDisplayName(), itemName), true);
            }
        }
        sendState(state);
    }

    private static void tryPulseScan(State state) {
        ServerPlayer operator = state.player;
        ReconDroneEntity drone = state.drone;
        if (drone == null || !hasLiveFpv(state, drone)) return;
        if (DroneRules.isLowBattery(drone.battery())) {
            operator.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 0.8F);
            operator.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.low_battery_no_scan"), true);
            return;
        }
        if (!DroneRules.canPulseScan(drone.battery(), scanCooldownTicks(state))) {
            operator.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.7F, 1.1F);
            operator.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.scan_unavailable"), true);
            return;
        }

        int nextBattery = Math.max(0, drone.battery() - DroneRules.PULSE_SCAN_COST);
        drone.setBattery(nextBattery);
        state.frame.stowedBattery = nextBattery;
        state.frame.scanReadyAt = state.matchLevel.getGameTime() + DroneRules.PULSE_SCAN_COOLDOWN_TICKS;

        ServerLevel level = operator.serverLevel();
        Vec3 center = drone.position();
        double radiusSq = DroneRules.PULSE_SCAN_RADIUS * DroneRules.PULSE_SCAN_RADIUS;

        level.playSound(null, drone.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.5F, 1.6F);
        level.playSound(null, drone.blockPosition(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.PLAYERS, 1.1F, 1.85F);

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        List<DroneScanHighlightPayload.Target> operatorTargets = new ArrayList<>();
        List<DroneScanHighlightPayload.Target> allyTargets = new ArrayList<>();

        for (ServerPlayer candidate : level.players()) {
            if (candidate == operator || !ServerAimTargeting.isAliveParticipant(candidate)) continue;
            if (candidate.distanceToSqr(center) > radiusSq) continue;

            SRERole role = game != null ? game.getRole(candidate) : null;
            boolean killerSide = role != null && (game.isKillerTeamRole(role) || role.canUseKiller() || role.isNeutralForKiller());
            boolean vigilanteOrArmed = !killerSide && (
                    (role != null && role.isVigilanteTeam()) || isCarryingRealGun(candidate)
            );
            int color = killerSide
                    ? DroneRules.SCAN_COLOR_KILLER
                    : (vigilanteOrArmed ? DroneRules.SCAN_COLOR_ARMED : DroneRules.SCAN_COLOR_INNOCENT);

            operatorTargets.add(new DroneScanHighlightPayload.Target(
                    candidate.getUUID(), candidate.getId(), color, DroneRules.OPERATOR_SCAN_GLOW_TICKS
            ));

            if (!killerSide) {
                allyTargets.add(new DroneScanHighlightPayload.Target(
                        candidate.getUUID(), candidate.getId(), color, DroneRules.KILLER_ALLY_SCAN_GLOW_TICKS
                ));
                candidate.playNotifySound(SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 0.36F, 1.85F);
                candidate.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.scanned_warning"), true);
            }
        }

        AABB corpseBox = drone.getBoundingBox().inflate(DroneRules.PULSE_SCAN_RADIUS);
        for (PlayerBodyEntity corpse : level.getEntitiesOfClass(PlayerBodyEntity.class, corpseBox, e -> !e.isRemoved())) {
            if (corpse.distanceToSqr(center) <= radiusSq) {
                operatorTargets.add(new DroneScanHighlightPayload.Target(
                        corpse.getUUID(), corpse.getId(), DroneRules.SCAN_COLOR_CORPSE, DroneRules.OPERATOR_SCAN_GLOW_TICKS
                ));
            }
        }

        ServerPlayNetworking.send(operator, new DroneScanHighlightPayload(
                true, center.x, center.y, center.z, operatorTargets
        ));

        if (!allyTargets.isEmpty() && game != null) {
            DroneScanHighlightPayload sharedPacket = new DroneScanHighlightPayload(
                    false, center.x, center.y, center.z, allyTargets
            );
            for (ServerPlayer viewer : level.players()) {
                if (viewer == operator || !ServerAimTargeting.isAliveParticipant(viewer)) continue;
                SRERole role = game.getRole(viewer);
                if (role != null && !role.isVigilanteTeam() && (game.isKillerTeamRole(role) || role.canUseKiller())) {
                    ServerPlayNetworking.send(viewer, sharedPacket);
                }
            }
        }

        if (nextBattery == 0) {
            triggerZeroBatteryForcedLanding(state, drone);
        } else {
            sendState(state);
        }
    }

    private static void tryTriggerPayload(State state) {
        ServerPlayer operator = state.player;
        ReconDroneEntity drone = state.drone;
        if (drone == null || !hasLiveFpv(state, drone)) return;
        DronePayloadHandler.TriggerOutcome outcome = DronePayloadHandler.trigger(
                operator.serverLevel(), operator, drone
        );
        if (!outcome.executed()) {
            if (outcome.rejectTranslationKey() != null) {
                operator.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.75F, 0.9F);
                operator.displayClientMessage(DroneMessages.create(outcome.rejectTranslationKey()), true);
            }
            return;
        }
        if (outcome.startedWindup()) {
            state.lockedWindupYaw = drone.getYRot();
            state.lockedWindupPitch = drone.getXRot();
            sendState(state);
            return;
        }
        applyTriggerSettlement(state, drone, outcome);
    }

    private static void applyTriggerSettlement(
            State state,
            ReconDroneEntity drone,
            DronePayloadHandler.TriggerOutcome outcome
    ) {
        if (outcome.consumedPayload()) {
            drone.setPayload(ItemStack.EMPTY, DronePayloadCategory.NONE);
        }
        if (outcome.lethalFuseTriggered() || outcome.destroyDroneSelf()) {
            DroneRules.LethalTriggerResult lethalResult = DroneRules.triggerLethalPayload(drone.battery());
            if (!lethalResult.allowed()) {
                triggerZeroBatteryForcedLanding(state, drone);
                return;
            }
            drone.setBattery(lethalResult.nextBattery());
            destroyDroneFromLethalOverload(state, drone);
            return;
        }
        if (outcome.nonLethalBatteryCost() > 0) {
            int next = Math.max(0, drone.battery() - outcome.nonLethalBatteryCost());
            drone.setBattery(next);
            if (next == 0) {
                triggerZeroBatteryForcedLanding(state, drone);
                return;
            }
        }
        sendState(state);
    }

    private static void destroyDroneFromLethalOverload(State state, ReconDroneEntity drone) {
        if (drone.level() instanceof ServerLevel sl) {
            drone.spawnDestructionBurst(sl);
        }
        releaseChunkTicket(state);
        state.drone = null;
        state.frame.destroyFrame();
        if (state.fpvActive) {
            state.destroyedStaticTicks = DroneRules.DESTROYED_STATIC_TICKS;
        }
        state.fpvActive = false;
        drone.setOperatorPiloting(false);
        drone.discard();
        if (!state.player.hasDisconnected()) {
            state.player.playNotifySound(SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 1.0F, 0.65F);
            state.player.displayClientMessage(
                    DroneMessages.create("message.habitrain_core.drone.lethal_overload_crashed"), true);
        }
        sendState(state);
    }

    private static void triggerZeroBatteryForcedLanding(State state, ReconDroneEntity drone) {
        state.fpvActive = false;
        drone.setOperatorPiloting(false);
        state.frame.drainAccumulator = 0.0D;
        drone.setBattery(0);
        drone.setChargeTicks(0);
        drone.setDroneSprinting(false);
        drone.setTilt(0.0F, 0.0F);
        drone.setDroneMode(ReconDroneEntity.MODE_CRASHED, 0);
        drone.setDeltaMovement(new Vec3(0.0D, -0.08D, 0.0D));
        state.player.serverLevel().playSound(null, drone.blockPosition(),
                SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.2F, 0.65F);
        state.player.playNotifySound(SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.PLAYERS, 1.0F, 0.6F);
        state.player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.zero_crash_landed"), true);
        sendState(state);
    }

    private static void tickServer(MinecraftServer server) {
        if (!SCRAPPED_DRONES.isEmpty()) {
            SCRAPPED_DRONES.removeIf(Entity::isRemoved);
        }
        if (!NET_ROOTED_PLAYERS.isEmpty() || !SILENCE_ZONES.isEmpty() || !voiceMuted.isEmpty()) {
            tickRootedAndSilenceZones(server);
        }

        boolean hasActiveOperators = !STATES.isEmpty();
        int tickCount = server.getTickCount();
        // Commands, transfers from other mods and already oversized inventories can bypass vanilla add/click.
        if (hasActiveOperators || tickCount % 20 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if ((hasActiveOperators && STATES.containsKey(player.getUUID())) || tickCount % 10 == 0) {
                    HabiRoleItems.enforceDroneBatteryCap(player);
                }
            }
        }
        if (!hasActiveOperators) {
            return;
        }
        boolean inMeeting = meetingActive();
        for (State state : List.copyOf(STATES.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.player.getUUID());
            if (player != state.player || player.level() != state.matchLevel || !isEligibleOperator(player)) {
                clearPlayer(state.player);
                continue;
            }

            if (inMeeting && state.drone != null) {
                packUpForMeeting(state);
                continue;
            }

            if (state.destroyedStaticTicks > 0) {
                state.destroyedStaticTicks--;
            }
            if (state.unboxTicks > 0) {
                state.unboxTicks--;
            }

            ReconDroneEntity drone = state.drone;
            if (drone != null) {
                if (drone.isRemoved() || drone.level() != player.level()) {
                    releaseChunkTicket(state);
                    state.drone = null;
                    if (state.fpvActive) {
                        state.destroyedStaticTicks = DroneRules.DESTROYED_STATIC_TICKS;
                    }
                    state.fpvActive = false;
                    state.lastDrainPerSec = 0.0F;
                    state.frame.destroyFrame();
                    sendState(state);
                    continue;
                }
                updateChunkTicket(state, player.serverLevel(), new ChunkPos(drone.blockPosition()), drone.getUUID());
                tickActiveDrone(state, player, drone);
            } else {
                state.lastDrainPerSec = 0.0F;
                if (state.fpvActive) {
                    state.fpvActive = false;
                }
            }

            if (state.fpvActive && state.bodyAnchor != null) {
                if (player.isPassenger()) {
                    exitFpvToBody(state, null);
                } else {
                    player.setPos(state.bodyAnchor.x, state.bodyAnchor.y, state.bodyAnchor.z);
                    player.setYRot(state.bodyYaw);
                    player.setYHeadRot(state.bodyYaw);
                    player.setXRot(state.bodyPitch);
                    player.setDeltaMovement(Vec3.ZERO);
                }
            }

            if (player.tickCount % 2 == 0 || state.fpvActive || state.destroyedStaticTicks > 0 || state.unboxTicks > 0) {
                sendState(state);
            }
        }
    }

    private static void tickActiveDrone(State state, ServerPlayer player, ReconDroneEntity drone) {
        byte mode = drone.droneMode();

        if (mode == ReconDroneEntity.MODE_DEPLOYING) {
            int rem = drone.modeTicks() - 1;
            if (rem <= 0) {
                drone.setDroneMode(ReconDroneEntity.MODE_ACTIVE, 0);
            } else {
                drone.setModeTicks(rem);
            }
            state.lastDrainPerSec = (float) DroneRules.HOVER_DRAIN_PER_SEC;
            return;
        }

        if (mode == ReconDroneEntity.MODE_RECALLING) {
            Vec3 handPos = player.getEyePosition().subtract(0.0D, 0.3D, 0.0D);
            Vec3 step = handPos.subtract(drone.position()).scale(0.45D);
            drone.move(MoverType.SELF, step);
            int rem = drone.modeTicks() - 1;
            if (rem <= 0) {
                stowDronePayload(state, drone);
                state.frame.stowedBattery = DroneRules.preserveBatteryOnRecall(drone.battery());
                removeDroneEntity(state);
                player.playNotifySound(SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.85F, 1.35F);
                sendState(state);
            } else {
                drone.setModeTicks(rem);
            }
            state.lastDrainPerSec = 0.0F;
            return;
        }

        if (mode == ReconDroneEntity.MODE_SWAPPING) {
            if (!canInteractAtCloseRange(player, drone) && player.distanceToSqr(drone) > 9.0D) {
                drone.setDroneMode(drone.battery() > 0 ? ReconDroneEntity.MODE_ACTIVE : ReconDroneEntity.MODE_CRASHED, 0);
                return;
            }
            Vec3 hoverSpot = magneticSwapHoverPos(player);
            drone.setPos(hoverSpot.x, hoverSpot.y, hoverSpot.z);
            drone.setYRot(player.getYRot() + 180.0F);
            int rem = drone.modeTicks() - 1;
            if (rem % 5 == 0) {
                player.serverLevel().sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        drone.getX(), drone.getY() + 0.18D, drone.getZ(), 4, 0.14D, 0.08D, 0.14D, 0.03D);
            }
            if (rem <= 0) {
                drone.setBattery(DroneRules.MAX_BATTERY);
                state.frame.stowedBattery = DroneRules.MAX_BATTERY;
                state.frame.drainAccumulator = 0.0D;
                drone.setDroneMode(ReconDroneEntity.MODE_ACTIVE, 0);
                player.serverLevel().playSound(null, drone.blockPosition(),
                        SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.PLAYERS, 0.9F, 1.65F);
                player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.85F, 1.6F);
                player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.swap_complete"), true);
                enterFpv(state);
                sendState(state);
            } else {
                drone.setModeTicks(rem);
            }
            state.lastDrainPerSec = 0.0F;
            return;
        }

        if (mode == ReconDroneEntity.MODE_CRASHED) {
            state.lastDrainPerSec = 0.0F;
            return;
        }

        // MODE_ACTIVE
        if (drone.chargeTicks() > 0 && !hasLiveFpv(state, drone)) {
            drone.setChargeTicks(0);
        }
        boolean chargingWindup = drone.chargeTicks() > 0;
        if (chargingWindup) {
            drone.setYRot(state.lockedWindupYaw);
            drone.setYHeadRot(state.lockedWindupYaw);
            drone.setXRot(state.lockedWindupPitch);
            drone.setDroneSprinting(false);
            drone.setTilt(0.0F, 0.0F);
            drone.setDeltaMovement(Vec3.ZERO);
        } else if (state.fpvActive) {
            drone.setYRot(state.inYaw);
            drone.setYHeadRot(state.inYaw);
            drone.setYBodyRot(state.inYaw);
            drone.setXRot(state.inPitch);

            boolean hasMoveInput = Math.abs(state.inForward) > 0.02F
                    || Math.abs(state.inStrafe) > 0.02F
                    || Math.abs(state.inVertical) > 0.02F;
            boolean sprinting = hasMoveInput && state.inSprint && !DroneRules.isLowBattery(drone.battery());
            drone.setDroneSprinting(sprinting);
            drone.setTilt(state.inStrafe, state.inForward);

            double baseSpeed = sprinting ? DroneRules.SPRINT_FLIGHT_SPEED : DroneRules.BASE_FLIGHT_SPEED;
            double speed = baseSpeed * DroneRules.computeSpeedMultiplier(drone.payloadCategory(), drone.battery());

            float yawRad = state.inYaw * Mth.DEG_TO_RAD;
            double sin = Math.sin(yawRad);
            double cos = Math.cos(yawRad);

            double vx = (-sin * state.inForward + cos * state.inStrafe);
            double vz = (cos * state.inForward + sin * state.inStrafe);
            double vy = state.inVertical * 0.82D;
            Vec3 rawDir = new Vec3(vx, vy, vz);
            if (rawDir.lengthSqr() > 1.0D) {
                rawDir = rawDir.normalize();
            }
            Vec3 desiredVel = rawDir.scale(speed);

            Vec3 offset = drone.position().subtract(player.position());
            DroneRules.Vec3d clamped = DroneRules.clampOutwardVelocity(
                    new DroneRules.Vec3d(offset.x, offset.y, offset.z),
                    new DroneRules.Vec3d(desiredVel.x, desiredVel.y, desiredVel.z)
            );
            Vec3 targetVel = new Vec3(clamped.x(), clamped.y(), clamped.z());
            Vec3 smoothed = drone.getDeltaMovement().scale(0.35D).add(targetVel.scale(0.65D));
            drone.setDeltaMovement(smoothed);
            drone.move(MoverType.SELF, smoothed);
        } else {
            // Hovering while operator is acting on own body
            drone.setDroneSprinting(false);
            drone.setTilt(0.0F, 0.0F);
            drone.setDeltaMovement(Vec3.ZERO);
        }

        boolean moving = !chargingWindup && state.fpvActive && (Math.abs(state.inForward) > 0.02F
                || Math.abs(state.inStrafe) > 0.02F
                || Math.abs(state.inVertical) > 0.02F);
        DroneRules.FlightMode flightMode = !moving
                ? DroneRules.FlightMode.HOVER
                : (drone.isDroneSprinting() ? DroneRules.FlightMode.SPRINT : DroneRules.FlightMode.NORMAL);

        double distToOwner = drone.position().distanceTo(player.position());
        double drainPerSec = DroneRules.computeDrainPerSecond(flightMode, drone.payloadCategory(), distToOwner);
        state.lastDrainPerSec = (float) drainPerSec;

        DroneRules.BatteryDrainStep step =
                DroneRules.stepTickDrain(drone.battery(), state.frame.drainAccumulator, drainPerSec);
        state.frame.drainAccumulator = step.nextAccumulator();
        drone.setBattery(step.nextBattery());
        state.frame.stowedBattery = step.nextBattery();

        if (step.forcedCrashLanding()) {
            triggerZeroBatteryForcedLanding(state, drone);
            return;
        }

        if (chargingWindup) {
            int nextCharge = drone.chargeTicks() - 1;
            drone.setChargeTicks(nextCharge);
            if (nextCharge == 0) {
                DronePayloadHandler.TriggerOutcome outcome = DronePayloadHandler.completeWindup(
                        player.serverLevel(), player, drone, state.lockedWindupYaw, state.lockedWindupPitch
                );
                if (outcome.executed()) {
                    applyTriggerSettlement(state, drone, outcome);
                }
            }
        }
    }

    private static void tickRootedAndSilenceZones(MinecraftServer server) {
        if (!NET_ROOTED_PLAYERS.isEmpty()) {
            Iterator<Map.Entry<UUID, Integer>> it = NET_ROOTED_PLAYERS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Integer> entry = it.next();
                ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
                int left = entry.getValue() - 1;
                if (p == null || !p.isAlive() || left <= 0) {
                    if (p != null) {
                        removeNetJumpLock(p);
                    }
                    it.remove();
                    continue;
                }
                entry.setValue(left);
                Vec3 v = p.getDeltaMovement();
                if (v.y > 0.0D) {
                    p.setDeltaMovement(0.0D, 0.0D, 0.0D);
                    p.hurtMarked = true;
                }
                if (left % 8 == 0) {
                    p.serverLevel().sendParticles(ParticleTypes.CRIT,
                            p.getX(), p.getY() + 0.8D, p.getZ(), 4, 0.25D, 0.3D, 0.25D, 0.01D);
                }
            }
        }

        if (!SILENCE_ZONES.isEmpty()) {
            Iterator<SilenceZone> zit = SILENCE_ZONES.iterator();
            while (zit.hasNext()) {
                SilenceZone zone = zit.next();
                zone.remainingTicks--;
                if (zone.remainingTicks <= 0) {
                    zit.remove();
                    continue;
                }
                if (zone.remainingTicks % 6 == 0) {
                    zone.level.sendParticles(ParticleTypes.SCULK_SOUL,
                            zone.center.x, zone.center.y + 0.25D, zone.center.z, 6, 1.8D, 0.35D, 1.8D, 0.01D);
                }
            }
        }
        refreshVoiceMutes();
    }

    public static void applyNetRoot(ServerPlayer target, int ticks) {
        NET_ROOTED_PLAYERS.put(target.getUUID(), Math.max(ticks, NET_ROOTED_PLAYERS.getOrDefault(target.getUUID(), 0)));
        AttributeInstance jumpAttr = target.getAttribute(Attributes.JUMP_STRENGTH);
        if (jumpAttr != null && !jumpAttr.hasModifier(NET_ROOT_JUMP_MODIFIER_ID)) {
            jumpAttr.addTransientModifier(new AttributeModifier(
                    NET_ROOT_JUMP_MODIFIER_ID, -1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        if (target.getDeltaMovement().y > 0.0D) {
            target.setDeltaMovement(0.0D, 0.0D, 0.0D);
            target.hurtMarked = true;
        }
    }

    private static void clearNetRoot(ServerPlayer player) {
        NET_ROOTED_PLAYERS.remove(player.getUUID());
        removeNetJumpLock(player);
    }

    private static void removeNetJumpLock(ServerPlayer player) {
        AttributeInstance jumpAttr = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (jumpAttr != null) {
            jumpAttr.removeModifier(NET_ROOT_JUMP_MODIFIER_ID);
        }
    }

    public static void spawnSilenceZone(ServerLevel level, Vec3 center, double radius, int ticks) {
        SILENCE_ZONES.add(new SilenceZone(level, center, radius, ticks));
        refreshVoiceMutes();
    }

    public static boolean isVoiceMuted(UUID speaker) {
        return voiceMuted.contains(speaker);
    }

    private static void refreshVoiceMutes() {
        if (SILENCE_ZONES.isEmpty()) {
            voiceMuted = Set.of();
            return;
        }
        Set<UUID> muted = new HashSet<>();
        for (SilenceZone zone : SILENCE_ZONES) {
            for (ServerPlayer player : zone.level.players()) {
                if (isSilencedByField(player)) muted.add(player.getUUID());
            }
        }
        voiceMuted = Set.copyOf(muted);
    }

    private static boolean isSilencedByField(ServerPlayer sender) {
        if (sender == null || !ServerAimTargeting.isAliveParticipant(sender)) return false;
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(sender.level());
        SRERole role = game != null ? game.getRole(sender) : null;
        if (role != null && (game.isKillerTeamRole(role) || role.canUseKiller() || role.isNeutralForKiller())) {
            return false;
        }
        for (SilenceZone zone : SILENCE_ZONES) {
            if (zone.level == sender.level() && sender.position().distanceToSqr(zone.center) <= zone.radiusSq) {
                return true;
            }
        }
        return false;
    }

    public static void destroyDrone(ReconDroneEntity drone, boolean brokenByHit, @Nullable Entity attacker) {
        if (drone == null || drone.isRemoved()) return;
        UUID ownerId = drone.ownerId();
        State state = ownerId != null ? STATES.get(ownerId) : null;
        ServerLevel level = drone.level() instanceof ServerLevel sl ? sl : null;

        boolean hadLiveExplosive = brokenByHit && drone.payloadCategory() == DronePayloadCategory.LETHAL_EXPLOSIVE;
        ServerPlayer ownerPlayer = state != null ? state.player : null;

        if (level != null && brokenByHit) {
            drone.spawnDestructionBurst(level);
            if (hadLiveExplosive) {
                DronePayloadHandler.triggerMidAirSympatheticExplosion(level, ownerPlayer, drone);
            }
        }

        if (state != null && state.drone == drone) {
            releaseChunkTicket(state);
            state.drone = null;
            if (brokenByHit) {
                state.frame.destroyFrame();
                if (state.fpvActive) {
                    state.destroyedStaticTicks = DroneRules.DESTROYED_STATIC_TICKS;
                }
                state.fpvActive = false;
                if (!state.player.hasDisconnected()) {
                    state.player.playNotifySound(SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.PLAYERS, 1.0F, 0.65F);
                    state.player.displayClientMessage(DroneMessages.create(
                            hadLiveExplosive
                                    ? "message.habitrain_core.drone.sympathetic_boom"
                                    : "message.habitrain_core.drone.destroyed_alert"
                    ), true);
                }
            }
        }

        drone.discard();

        if (brokenByHit && attacker instanceof ServerPlayer sp && (ownerId == null || !sp.getUUID().equals(ownerId))) {
            sp.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.85F, 1.35F);
            sp.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.shot_down_confirm"), true);
        }

        if (state != null) {
            sendState(state);
        }
    }

    private static void packUpForMeeting(State state) {
        ReconDroneEntity drone = state.drone;
        if (drone != null && !drone.isRemoved()) {
            if (state.frame.hasDroneFrame) {
                stowDronePayload(state, drone);
                state.frame.stowedBattery = DroneRules.preserveBatteryOnRecall(drone.battery());
            } else {
                drone.setPayload(ItemStack.EMPTY, DronePayloadCategory.NONE);
            }
            removeDroneEntity(state);
        }
        state.fpvActive = false;
        state.destroyedStaticTicks = 0;
        if (!state.player.hasDisconnected()) {
            ServerPlayNetworking.send(state.player, new DroneScanHighlightPayload(false, 0, 0, 0, List.of()));
            sendState(state);
        }
    }

    private static void clearPlayer(ServerPlayer player) {
        if (player == null) return;
        clearNetRoot(player);
        State state = STATES.get(player.getUUID());
        if (state == null || state.player != player) return;
        STATES.remove(player.getUUID());
        ReconDroneEntity drone = state.drone;
        boolean eligible = isEligibleOperator(player);
        if (drone != null && !drone.isRemoved()) {
            if (state.frame.hasDroneFrame && eligible) {
                stowDronePayload(state, drone);
                state.frame.stowedBattery = DroneRules.preserveBatteryOnRecall(drone.battery());
            } else {
                drone.setPayload(ItemStack.EMPTY, DronePayloadCategory.NONE);
                state.frame.destroyFrame();
            }
        } else if (!eligible) {
            state.frame.destroyFrame();
        }
        removeDroneEntity(state);
        state.fpvActive = false;
        if (!player.hasDisconnected()) {
            ServerPlayNetworking.send(player, DroneStatePayload.EMPTY);
            ServerPlayNetworking.send(player, new DroneScanHighlightPayload(false, 0, 0, 0, List.of()));
        }
    }

    private static void removeDroneEntity(State state) {
        releaseChunkTicket(state);
        if (state.drone != null) {
            state.drone.discard();
            state.drone = null;
        }
    }

    private static void updateChunkTicket(State state, ServerLevel level, ChunkPos nextPos, UUID droneUuid) {
        if (state.ticketLevel == level && nextPos.equals(state.ticketChunk) && droneUuid.equals(state.ticketEntityUuid)) {
            return;
        }
        releaseChunkTicket(state);
        level.getChunkSource().addRegionTicket(DRONE_TICKET, nextPos, 2, droneUuid);
        state.ticketLevel = level;
        state.ticketChunk = nextPos;
        state.ticketEntityUuid = droneUuid;
    }

    private static void releaseChunkTicket(State state) {
        if (state.ticketLevel != null && state.ticketChunk != null && state.ticketEntityUuid != null) {
            state.ticketLevel.getChunkSource().removeRegionTicket(DRONE_TICKET, state.ticketChunk, 2, state.ticketEntityUuid);
        }
        state.ticketLevel = null;
        state.ticketChunk = null;
        state.ticketEntityUuid = null;
    }

    private static boolean canInteractAtCloseRange(ServerPlayer player, ReconDroneEntity drone) {
        return isWithinCloseInteractRange(player, drone);
    }

    public static boolean isWithinCloseInteractRange(@Nullable Player player, @Nullable ReconDroneEntity drone) {
        if (player == null || drone == null || player.level() != drone.level()) return false;
        Vec3 target = drone.position().add(0.0D, 0.18D, 0.0D);
        double dist = player.getEyePosition().distanceTo(target);
        double bodyDist = player.position().add(0.0D, 0.9D, 0.0D).distanceTo(target);
        double effectiveDist = Math.min(dist, bodyDist);
        boolean los = player.level().clip(new ClipContext(
                player.getEyePosition(),
                target,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        )).getType() == HitResult.Type.MISS;
        return DroneRules.canRecallOrSwap(effectiveDist, los);
    }

    public static boolean isFriendlyDroneCollaborator(@Nullable Player player) {
        if (player == null || !player.isAlive() || player.isSpectator()) return false;
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.level());
        return game != null
                && game.getGameStatus() == SREGameWorldComponent.GameStatus.ACTIVE
                && game.getRole(player) != null;
    }

    private static boolean isKillerTeamCollaborator(ServerPlayer player) {
        return ServerAimTargeting.isAliveParticipant(player) && isFriendlyDroneCollaborator(player);
    }

    /**
     * Shared client+server check for {@link ReconDroneEntity#mobInteract} and close-range RMB intercept:
     * allows any alive participating player within {@code <= 2.5m} unobstructed LOS to right-click mount
     * compatible items (knives, guns, grenades, poisons, tools, etc.) onto an empty drone mount or
     * sneak+right-click to unload, while passing through outside {@code 2.5m} (or when mount is already
     * occupied) so right-click weapons can still shoot/stab drones normally.
     */
    public static boolean shouldConsumeDirectEntityInteract(@Nullable ReconDroneEntity drone, @Nullable Player actor) {
        if (actor == null || !actor.isAlive() || actor.isSpectator() || drone == null || drone.isRemoved()) {
            return false;
        }
        UUID ownerId = drone.ownerId();
        if (ownerId == null) {
            return false;
        }
        boolean isOwner = actor.getUUID().equals(ownerId);
        if (drone.isScrapped()) {
            return isOwner;
        }
        boolean isCollaborator = isFriendlyDroneCollaborator(actor);
        if (!isOwner && !isCollaborator) {
            return false;
        }
        ItemStack held = actor.getMainHandItem();
        if (isOwner && !actor.isShiftKeyDown() && HabiRoleItems.isDroneTerminal(held)) {
            return true;
        }
        boolean canUnload = DroneRules.canUnloadMountedPayload(actor.getUUID(), ownerId, drone.payloadMounterId());
        if (drone.isCrashed()) {
            if (!isWithinCloseInteractRange(actor, drone)) {
                return false;
            }
            if (actor.isShiftKeyDown() && drone.payloadCategory().hasPayload() && drone.chargeTicks() <= 0) {
                return canUnload;
            }
            return isOwner;
        }
        if (drone.droneMode() != ReconDroneEntity.MODE_ACTIVE) {
            return false;
        }
        if (!isWithinCloseInteractRange(actor, drone)) {
            return false;
        }
        if (actor.isShiftKeyDown()) {
            if (drone.payloadCategory().hasPayload() && drone.chargeTicks() <= 0) {
                return canUnload;
            }
            return isOwner;
        }
        if (isOwner && (held.isEmpty() || HabiRoleItems.isDroneBattery(held))) {
            return true;
        }
        return !drone.payloadCategory().hasPayload() && DronePayloadHandler.classify(held).hasPayload();
    }

    private static boolean isCarryingRealGun(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(st.getItem());
            String path = key != null ? key.getPath() : "";
            if ("fake_revolver".equals(path) || "trapper_net_gun".equals(path)) continue;
            if (st.is(TMMItemTags.GUNS) || "short_shotgun".equals(path) || "once_revolver".equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyDangerToBody(ServerPlayer operator) {
        double rSq = DroneRules.BODY_PROXIMITY_ALERT_RANGE * DroneRules.BODY_PROXIMITY_ALERT_RANGE;
        for (ServerPlayer other : operator.serverLevel().players()) {
            if (other == operator || !ServerAimTargeting.isAliveParticipant(other)) continue;
            if (other.distanceToSqr(operator) <= rSq) {
                return true;
            }
        }
        return false;
    }

    private static void sendState(State state) {
        ServerPlayer player = state.player;
        if (player == null || player.hasDisconnected()) return;

        ReconDroneEntity drone = state.drone;
        int droneId = drone != null && !drone.isRemoved() ? drone.getId() : -1;
        int battery = drone != null && !drone.isRemoved() ? drone.battery() : state.frame.stowedBattery;

        byte transition = DroneStatePayload.TRANSITION_NONE;
        int transitionTicks = 0;
        if (state.destroyedStaticTicks > 0) {
            transition = DroneStatePayload.TRANSITION_DESTROYED_STATIC;
            transitionTicks = state.destroyedStaticTicks;
        } else if (state.unboxTicks > 0) {
            transition = DroneStatePayload.TRANSITION_UNBOXING;
            transitionTicks = state.unboxTicks;
        } else if (drone != null && !drone.isRemoved()) {
            if (drone.droneMode() == ReconDroneEntity.MODE_DEPLOYING) {
                transition = DroneStatePayload.TRANSITION_DEPLOYING;
                transitionTicks = drone.modeTicks();
            } else if (drone.droneMode() == ReconDroneEntity.MODE_RECALLING) {
                transition = DroneStatePayload.TRANSITION_RECALLING;
                transitionTicks = drone.modeTicks();
            } else if (drone.droneMode() == ReconDroneEntity.MODE_SWAPPING) {
                transition = DroneStatePayload.TRANSITION_SWAPPING;
                transitionTicks = drone.modeTicks();
            }
        }

        boolean danger = (state.fpvActive || droneId != -1) && hasNearbyDangerToBody(player);
        int spares = HabiRoleItems.countDroneBatteries(player);

        DroneStatePayload payload = new DroneStatePayload(
                droneId,
                state.frame.hasDroneFrame,
                state.frame.everHadDrone,
                battery,
                state.fpvActive,
                transition,
                transitionTicks,
                scanCooldownTicks(state),
                danger,
                spares,
                state.lastDrainPerSec,
                player.getX(),
                player.getY() + 1.1D,
                player.getZ()
        );
        if (payload.equals(state.lastSentPayload)) {
            return;
        }
        state.lastSentPayload = payload;
        ServerPlayNetworking.send(player, payload);
    }

    public static void broadcastFx(ServerLevel level, int droneEntityId, byte fxType, Vec3 from, Vec3 to) {
        DroneFxPayload packet = new DroneFxPayload(
                droneEntityId, fxType, from.x, from.y, from.z, to.x, to.y, to.z
        );
        for (ServerPlayer viewer : level.players()) {
            if (viewer.position().distanceToSqr(from) <= 96.0D * 96.0D) {
                ServerPlayNetworking.send(viewer, packet);
            }
        }
    }

    // --- Authoritative Weapon Hit Receivers (Knife / Guns / Sniper / 015 / Short Shotgun) ---

    public static void knifeHit(ServerPlayer attacker, ReconDroneEntity drone) {
        if (!ServerAimTargeting.isAliveParticipant(attacker)
                || isLockedInFpv(attacker)
                || !drone.isAttackableProp()
                || !owns(drone)
                || attacker.distanceToSqr(drone) > 16.0D
                || !attacker.hasLineOfSight(drone)) {
            return;
        }
        ItemStack knife = attacker.getMainHandItem();
        if (!(knife.getItem() instanceof KnifeItem)
                || attacker.getCooldowns().isOnCooldown(knife.getItem())
                || KillerKnifeDurability.isDepleted(knife)) {
            return;
        }
        Vec3 start = attacker.getEyePosition();
        if (drone.getBoundingBox().inflate(0.22D).clip(start, start.add(attacker.getLookAngle().scale(4.0D))).isEmpty()) {
            return;
        }
        destroyDrone(drone, true, attacker);
        if (KillerKnifeDurability.isDurabilityModeEnabled(attacker.level()) && KillerKnifeDurability.isMarkedKnife(knife)) {
            KillerKnifeDurability.consumeOne(knife, attacker);
        }
        attacker.swing(InteractionHand.MAIN_HAND);
        if (!attacker.isCreative()) {
            attacker.getCooldowns().addCooldown(knife.getItem(),
                    GameConstants.ITEM_COOLDOWNS.getOrDefault(knife.getItem(), 600));
        }
    }

    public static void gunHit(ServerPlayer attacker, int entityId) {
        ItemStack gun = attacker.getMainHandItem();
        if (!gun.is(TMMItemTags.GUNS) || gun.is(ModItems.FAKE_REVOLVER) || gun.is(ModItems.ONCE_REVOLVER)) return;
        rangedHit(attacker, entityId, 20.0D);
    }

    public static @Nullable ReconDroneEntity onceRevolverTarget(ServerPlayer attacker) {
        if (!ServerAimTargeting.isAliveParticipant(attacker) || isLockedInFpv(attacker)) return null;
        HitResult hit = ProjectileUtil.getHitResultOnViewVector(attacker, entity ->
                entity instanceof ReconDroneEntity drone && drone.isAttackableProp() && owns(drone)
                        || entity instanceof ServerPlayer player && ServerAimTargeting.isAliveParticipant(player), 15.0D);
        return hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof ReconDroneEntity drone
                ? drone : null;
    }

    public static void batHit(ServerPlayer attacker, ReconDroneEntity drone) {
        if (!ServerAimTargeting.isAliveParticipant(attacker) || isLockedInFpv(attacker)
                || !drone.isAttackableProp() || !owns(drone)
                || attacker.getCooldowns().isOnCooldown(TMMItems.BAT)
                || attacker.getAttackStrengthScale(0.75F) < 1.0F
                || !attacker.canInteractWithEntity(drone, 0.0D) || !attacker.hasLineOfSight(drone)) return;
        destroyDrone(drone, true, attacker);
        attacker.level().playSound(null, attacker.blockPosition(), TMMSounds.ITEM_BAT_HIT,
                SoundSource.PLAYERS, 3.0F, 1.0F);
    }

    public static void rangedHit(ServerPlayer attacker, int entityId, double range) {
        if (!(attacker.serverLevel().getEntity(entityId) instanceof ReconDroneEntity drone)
                || !ServerAimTargeting.isAliveParticipant(attacker)
                || isLockedInFpv(attacker)
                || !drone.isAttackableProp()
                || !owns(drone)
                || attacker.distanceToSqr(drone) > range * range
                || !attacker.hasLineOfSight(drone)) {
            return;
        }
        Vec3 eye = attacker.getEyePosition();
        if (drone.getBoundingBox().inflate(0.18D).clip(eye, eye.add(attacker.getLookAngle().scale(range))).isPresent()) {
            destroyDrone(drone, true, attacker);
        }
    }

    public static void shortShotgunFanHit(ServerPlayer attacker, double killRange) {
        if (!ServerAimTargeting.isAliveParticipant(attacker) || isLockedInFpv(attacker)) return;
        ServerLevel level = attacker.serverLevel();
        Vec3 eye = attacker.getEyePosition();
        Vec3 look = attacker.getLookAngle().normalize();
        double cosHalfAngle = Math.cos(Math.toRadians(35.0D));
        AABB box = attacker.getBoundingBox().inflate(killRange + 1.0D);

        for (ReconDroneEntity drone : level.getEntitiesOfClass(ReconDroneEntity.class, box, ReconDroneEntity::isAttackableProp)) {
            Vec3 toDrone = drone.position().add(0.0D, 0.18D, 0.0D).subtract(eye);
            double dist = toDrone.length();
            if (dist <= killRange + 0.5D && dist > 1.0E-4D) {
                if (toDrone.scale(1.0D / dist).dot(look) >= cosHalfAngle && attacker.hasLineOfSight(drone)) {
                    destroyDrone(drone, true, attacker);
                }
            }
        }
    }
}
