package com.habitrain.core.game.sre.role.drone;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.idiot.IdiotService;
import com.habitrain.core.game.sre.role.sins.ServerAimTargeting;
import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import com.habitrain.core.network.DroneFxPayload;
import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.SREConfig;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerPoisonComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.content.block.LockableButtonBlock;
import io.wifi.starrailexpress.content.block.SmallDoorBlock;
import io.wifi.starrailexpress.content.block_entity.LockableButtonBlockEntity;
import io.wifi.starrailexpress.content.block_entity.SmallDoorBlockEntity;
import io.wifi.starrailexpress.content.entity.FirecrackerEntity;
import io.wifi.starrailexpress.content.entity.GrenadeEntity;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import io.wifi.starrailexpress.content.entity.StickyGrenadeEntity;
import io.wifi.starrailexpress.content.entity.TimedGrenadeEntity;
import io.wifi.starrailexpress.content.item.KnifeItem;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.index.TMMEntities;
import io.wifi.starrailexpress.index.TMMSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.content.entity.ChlorineBombEntity;
import org.agmas.noellesroles.content.entity.DecoyGrenadeEntity;
import org.agmas.noellesroles.content.entity.FlareEntity;
import org.agmas.noellesroles.content.entity.FlashGrenadeEntity;
import org.agmas.noellesroles.content.entity.HallucinationAreaManager;
import org.agmas.noellesroles.content.entity.IncendiaryGrenadeEntity;
import org.agmas.noellesroles.content.entity.PoisonGasTankEntity;
import org.agmas.noellesroles.content.entity.PurifyBombEntity;
import org.agmas.noellesroles.content.entity.SlimeGrenadeEntity;
import org.agmas.noellesroles.content.entity.SmokeGrenadeEntity;
import org.agmas.noellesroles.content.entity.WallBreakGrenadeEntity;
import org.agmas.noellesroles.init.HSRConstants;
import org.agmas.noellesroles.init.ModEntities;
import org.agmas.noellesroles.role.ModRoles;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Classifies compatible items and executes server-authoritative belly payload interactions
 * for {@link ReconDroneEntity}, enforcing the 1-per-round lethal fire-control fuse.
 */
public final class DronePayloadHandler {
    private DronePayloadHandler() {}

    private static final TagKey<Item> SRE_GUNS_TAG = TagKey.create(
            Registries.ITEM, ResourceLocation.fromNamespaceAndPath("starrailexpress", "guns"));

    private static final Set<String> LETHAL_GUN_PATHS = Set.of(
            "revolver", "standard_revolver", "derringer", "sniper_rifle",
            "once_revolver", "bandit_revolver", "short_shotgun", "zero_one_five_gun",
            "sheriff_revolver", "patroller_revolver", "executioner_gun"
    );

    private static final Set<String> LETHAL_BLADE_PATHS = Set.of(
            "knife", "throwing_knife", "ninja_knife", "ninja_shuriken",
            "stalker_knife", "stalker_knife_offhand", "sp_knife",
            "fire_axe", "dream_axe", "scarlet_perception_sword",
            "hunter_weapon", "hunter_hammer", "trident", "nunchuck", "bat"
    );

    private static final Set<String> LETHAL_EXPLOSIVE_PATHS = Set.of(
            "grenade", "sticky_grenade", "timed_grenade", "bomb",
            "incendiary_grenade", "wall_break_grenade"
    );

    private static final Set<String> LETHAL_POISON_PATHS = Set.of(
            "poison_vial", "scorpion", "toxin", "hedinghong",
            "chlorine_bomb", "poison_gas_tank", "toilet_poison"
    );

    public record TriggerOutcome(
            boolean executed,
            boolean startedWindup,
            boolean consumedPayload,
            boolean lethalFuseTriggered,
            boolean destroyDroneSelf,
            int nonLethalBatteryCost,
            @Nullable String rejectTranslationKey
    ) {
        public static TriggerOutcome reject(String key) {
            return new TriggerOutcome(false, false, false, false, false, 0, key);
        }

        public static TriggerOutcome windup() {
            return new TriggerOutcome(true, true, false, false, false, 0, null);
        }

        public static TriggerOutcome lethalDone(boolean destroyDroneSelf) {
            return new TriggerOutcome(true, false, true, true, destroyDroneSelf, 0, null);
        }

        public static TriggerOutcome nonLethal(int batteryCost, boolean consumedPayload) {
            return new TriggerOutcome(true, false, consumedPayload, false, false, batteryCost, null);
        }
    }

    public static boolean isForbiddenMountItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        return stack.is(Items.BARRIER)
                || HabiRoleItems.isDroneTerminal(stack)
                || HabiRoleItems.isDroneBattery(stack)
                || HabiRoleItems.hasFlag(stack, HabiRoleItems.TAG_DRONE_RESUPPLY)
                || IdiotService.isEgg(stack)
                || GreedPouchItem.isGreedPouch(stack);
    }

    public static DronePayloadCategory classify(ItemStack stack) {
        if (isForbiddenMountItem(stack)) {
            return DronePayloadCategory.NONE;
        }
        Item item = stack.getItem();
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        String path = key != null ? key.getPath() : "";

        // 1. Deception / fake weapons FIRST so they never match lethal weapon tags
        if (HabiRoleItems.isDroneFakeKnife(stack) || "fake_knife".equals(path)) {
            return DronePayloadCategory.FAKE_BLADE;
        }
        if ("fake_revolver".equals(path)) {
            return DronePayloadCategory.FAKE_GUN;
        }
        if ("fake_grenade".equals(path)) {
            return DronePayloadCategory.FAKE_EXPLOSIVE;
        }

        // 2. Non-lethal control & acoustic decoys (check before TMMItemTags.GUNS for trapper_net_gun)
        if (HabiRoleItems.isPepperSpray(stack)
                || stack.is(Items.WIND_CHARGE)
                || "wind_charge".equals(path)
                || "flash_grenade".equals(path)
                || "slime_grenade".equals(path)
                || "trapper_net_gun".equals(path)
                || "silence_totem".equals(path)
                || "hallucination_bottle".equals(path)
                || "purify_bomb".equals(path)) {
            return DronePayloadCategory.CONTROL;
        }
        if ("firecracker".equals(path)
                || "smoke_grenade".equals(path)
                || "smoke_pellet".equals(path)
                || "youlu_smoke".equals(path)
                || "decoy_grenade".equals(path)
                || "flare".equals(path)) {
            return DronePayloadCategory.DECOY_SMOKE;
        }

        // 3. Lethal weapons (1-per-round fire-control fuse)
        if (LETHAL_GUN_PATHS.contains(path) || stack.is(SRE_GUNS_TAG)) {
            return DronePayloadCategory.LETHAL_GUN;
        }
        if (item instanceof KnifeItem || LETHAL_BLADE_PATHS.contains(path)) {
            return DronePayloadCategory.LETHAL_BLADE;
        }
        if (LETHAL_EXPLOSIVE_PATHS.contains(path)) {
            return DronePayloadCategory.LETHAL_EXPLOSIVE;
        }
        if (LETHAL_POISON_PATHS.contains(path)) {
            return DronePayloadCategory.LETHAL_POISON;
        }

        // 4. Infiltration tools & general small logistics items (keys, potions, etc.)
        return DronePayloadCategory.INFILTRATION_LOGISTICS;
    }

    public static int nonLethalBatteryCost(ItemStack stack, DronePayloadCategory category) {
        if (stack == null || stack.isEmpty()) return 0;
        String path = itemPath(stack);
        return switch (category) {
            case FAKE_GUN, FAKE_BLADE, FAKE_EXPLOSIVE, DECOY_SMOKE -> 1600; // 10%
            case CONTROL -> ("trapper_net_gun".equals(path) || "silence_totem".equals(path)) ? 3200 : 2400; // 15%~20%
            case INFILTRATION_LOGISTICS -> {
                if ("blackout".equals(path) || "dream_blackout".equals(path)) yield 4000; // 25%
                if ("crowbar".equals(path) || "repair_crowbar".equals(path) || "repair_bolt_cutter".equals(path)
                        || "monitor_broken".equals(path) || "body_bag".equals(path)) {
                    yield 3200; // 20%
                }
                yield 2400; // 15% (lockpick & precision airdrop)
            }
            default -> category.defaultTriggerCost();
        };
    }

    public static TriggerOutcome trigger(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone
    ) {
        ItemStack payload = drone.payloadStack();
        DronePayloadCategory category = drone.payloadCategory();
        if (payload.isEmpty() || !category.hasPayload()) {
            return TriggerOutcome.reject("message.habitrain_core.drone.no_payload");
        }
        if (drone.chargeTicks() > 0) {
            return TriggerOutcome.reject("message.habitrain_core.drone.charging");
        }
        String path = itemPath(payload);

        if (category.isLethal()) {
            if (!DroneRules.triggerLethalPayload(drone.battery()).allowed()) {
                return TriggerOutcome.reject("message.habitrain_core.drone.low_power_payload");
            }
            return switch (category) {
                case LETHAL_GUN -> {
                    drone.setChargeTicks(DroneRules.LETHAL_WINDUP_TICKS);
                    level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_REVOLVER_CLICK,
                            SoundSource.PLAYERS, 1.8F, 1.15F);
                    level.playSound(null, drone.blockPosition(), SoundEvents.DISPENSER_FAIL,
                            SoundSource.PLAYERS, 1.2F, 1.8F);
                    yield TriggerOutcome.windup();
                }
                case LETHAL_BLADE -> {
                    if ("throwing_knife".equals(path) || "ninja_shuriken".equals(path)) {
                        executeThrowingKnifeShot(level, operator, drone);
                        yield TriggerOutcome.lethalDone(true);
                    }
                    drone.setChargeTicks(DroneRules.LETHAL_WINDUP_TICKS);
                    level.playSound(null, drone.blockPosition(), SoundEvents.BEACON_ACTIVATE,
                            SoundSource.PLAYERS, 1.4F, 1.95F);
                    level.playSound(null, drone.blockPosition(), SoundEvents.GRINDSTONE_USE,
                            SoundSource.PLAYERS, 1.0F, 1.75F);
                    yield TriggerOutcome.windup();
                }
                case LETHAL_EXPLOSIVE -> executeLethalExplosive(level, operator, drone, path);
                case LETHAL_POISON -> executeLethalPoison(level, operator, drone, path);
                default -> TriggerOutcome.reject("message.habitrain_core.drone.no_payload");
            };
        }

        int cost = nonLethalBatteryCost(payload, category);
        if (drone.battery() < cost) {
            return TriggerOutcome.reject("message.habitrain_core.drone.low_power_payload");
        }

        return switch (category) {
            case FAKE_GUN -> {
                Vec3 look = lookVector(drone);
                Vec3 end = drone.position().add(0.0D, 0.16D, 0.0D).add(look.scale(8.0D));
                level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_REVOLVER_SHOOT,
                        SoundSource.PLAYERS, 3.2F, 1.02F);
                DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_GUN_SHOT,
                        drone.position().add(0.0D, 0.16D, 0.0D), end);
                yield TriggerOutcome.nonLethal(cost, false);
            }
            case FAKE_BLADE -> {
                Vec3 look = lookVector(drone);
                Vec3 dash = look.scale(0.95D);
                drone.move(MoverType.SELF, dash);
                level.playSound(null, drone.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP,
                        SoundSource.PLAYERS, 1.6F, 1.25F);
                DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_BLADE_SLASH,
                        drone.position().add(0.0D, 0.16D, 0.0D), drone.position().add(look));
                yield TriggerOutcome.nonLethal(cost, false);
            }
            case FAKE_EXPLOSIVE -> {
                level.playSound(null, drone.blockPosition(), SoundEvents.TNT_PRIMED,
                        SoundSource.PLAYERS, 1.8F, 1.35F);
                level.sendParticles(ParticleTypes.POOF, drone.getX(), drone.getY() + 0.1D, drone.getZ(),
                        24, 0.35D, 0.25D, 0.35D, 0.06D);
                DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_EXPLOSIVE_DROP,
                        drone.position(), drone.position().add(0.0D, -1.5D, 0.0D));
                yield TriggerOutcome.nonLethal(cost, true);
            }
            case CONTROL -> executeControlPayload(level, operator, drone, payload, path, cost);
            case DECOY_SMOKE -> executeDecoyOrSmoke(level, operator, drone, payload, path, cost);
            case INFILTRATION_LOGISTICS -> executeInfiltrationOrLogistics(level, operator, drone, payload, path, cost);
            default -> TriggerOutcome.reject("message.habitrain_core.drone.no_payload");
        };
    }

    /**
     * Completes the 0.5s (10-tick) windup for lethal guns or melee blades.
     */
    public static TriggerOutcome completeWindup(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone,
            float lockedYaw,
            float lockedPitch
    ) {
        if (!DroneRules.triggerLethalPayload(drone.battery()).allowed()) {
            return TriggerOutcome.reject("message.habitrain_core.drone.low_power_payload");
        }
        DronePayloadCategory category = drone.payloadCategory();
        if (category == DronePayloadCategory.LETHAL_GUN) {
            String gunPath = itemPath(drone.payloadStack());
            double maxRange = ("sniper_rifle".equals(gunPath) || "zero_one_five_gun".equals(gunPath))
                    ? 30.0D : ("short_shotgun".equals(gunPath) ? 10.0D : 18.0D);
            double inflate = "short_shotgun".equals(gunPath) ? 0.75D : 0.32D;
            Vec3 origin = drone.position().add(0.0D, 0.16D, 0.0D);
            Vec3 look = Vec3.directionFromRotation(lockedPitch, lockedYaw).normalize();
            HitTarget hit = raycastLivingTarget(level, operator, drone, origin, look, maxRange, inflate);
            Vec3 tracerEnd = hit != null ? hit.hitPos() : blockClipEnd(level, drone, origin, look, maxRange);

            level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_REVOLVER_SHOOT,
                    SoundSource.PLAYERS, 4.0F, 0.96F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_GUN_SHOT, origin, tracerEnd);

            if (hit != null) {
                GameUtils.killPlayer(hit.player(), true, operator, GameConstants.DeathReasons.REVOLVER);
            }

            // Strong 2.5-block backward recoil kick before overload crash
            Vec3 recoil = look.scale(-2.5D);
            Vec3 clippedRecoil = clipMotion(level, drone, origin, recoil);
            drone.move(MoverType.SELF, clippedRecoil);
            return TriggerOutcome.lethalDone(true);
        }

        if (category == DronePayloadCategory.LETHAL_BLADE) {
            Vec3 origin = drone.position().add(0.0D, 0.16D, 0.0D);
            Vec3 look = Vec3.directionFromRotation(lockedPitch, lockedYaw).normalize();
            HitTarget hit = raycastLivingTarget(level, operator, drone, origin, look, 4.5D, 0.48D);
            Vec3 lungeTarget = hit != null
                    ? hit.hitPos().subtract(origin).scale(0.82D)
                    : clipMotion(level, drone, origin, look.scale(2.8D));
            drone.move(MoverType.SELF, lungeTarget);

            level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_KNIFE_STAB,
                    SoundSource.PLAYERS, 2.0F, 1.05F);
            level.playSound(null, drone.blockPosition(), SoundEvents.ITEM_BREAK,
                    SoundSource.PLAYERS, 1.1F, 0.85F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_BLADE_SLASH,
                    drone.position().add(0.0D, 0.18D, 0.0D), origin.add(look.scale(2.5D)));

            if (hit != null) {
                GameUtils.killPlayer(hit.player(), true, operator, GameConstants.DeathReasons.KNIFE);
            }
            return TriggerOutcome.lethalDone(true);
        }

        return TriggerOutcome.reject("message.habitrain_core.drone.no_payload");
    }

    /**
     * Triggered when a player shoots or strikes a drone carrying a live lethal explosive in mid-air:
     * triggers an immediate mid-air sympathetic detonation and permanently fuses fire-control!
     */
    public static void triggerMidAirSympatheticExplosion(
            ServerLevel level,
            @Nullable ServerPlayer operator,
            ReconDroneEntity drone
    ) {
        spawnInstantOrdinanceBlast(level, operator, drone.position());
    }

    private static void executeThrowingKnifeShot(ServerLevel level, ServerPlayer operator, ReconDroneEntity drone) {
        Vec3 origin = drone.position().add(0.0D, 0.16D, 0.0D);
        Vec3 look = lookVector(drone);
        HitTarget hit = raycastLivingTarget(level, operator, drone, origin, look, 10.0D, 0.35D);
        Vec3 end = hit != null ? hit.hitPos() : blockClipEnd(level, drone, origin, look, 10.0D);

        level.playSound(null, drone.blockPosition(), SoundEvents.TRIDENT_THROW.value(),
                SoundSource.PLAYERS, 1.8F, 1.45F);
        level.playSound(null, drone.blockPosition(), SoundEvents.ITEM_BREAK,
                SoundSource.PLAYERS, 0.9F, 0.9F);
        DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_BLADE_SLASH, origin, end);

        if (hit != null) {
            GameUtils.killPlayer(hit.player(), true, operator, HabiRoleItems.THROWING_KNIFE_ID);
        }
    }

    private static TriggerOutcome executeLethalExplosive(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone,
            String path
    ) {
        Vec3 dropPos = drone.position().add(0.0D, -0.15D, 0.0D);
        if ("bomb".equals(path)) {
            // Kamikaze self-destruct: instant blast + drone destroyed (must pay 150 coins to replace)
            spawnInstantOrdinanceBlast(level, operator, drone.position());
            return TriggerOutcome.lethalDone(true);
        }

        level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_GRENADE_THROW,
                SoundSource.PLAYERS, 1.4F, 1.1F);
        DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_EXPLOSIVE_DROP,
                dropPos, dropPos.add(0.0D, -2.0D, 0.0D));

        if ("sticky_grenade".equals(path)) {
            StickyGrenadeEntity sticky = new StickyGrenadeEntity(TMMEntities.STICKY_GRENADE, level);
            sticky.setOwner(operator);
            sticky.setPos(dropPos.x, dropPos.y, dropPos.z);
            sticky.shoot(0.0D, -1.0D, 0.0D, 0.65F, 0.0F);
            level.addFreshEntity(sticky);
        } else if ("incendiary_grenade".equals(path)) {
            IncendiaryGrenadeEntity incendiary = new IncendiaryGrenadeEntity(ModEntities.INCENDIARY_GRENADE, level);
            incendiary.setOwner(operator);
            incendiary.setPos(dropPos.x, dropPos.y, dropPos.z);
            incendiary.shoot(0.0D, -1.0D, 0.0D, 0.55F, 0.0F);
            level.addFreshEntity(incendiary);
        } else if ("wall_break_grenade".equals(path)) {
            WallBreakGrenadeEntity wallBreak = new WallBreakGrenadeEntity(ModEntities.WALL_BREAK_GRENADE, level);
            wallBreak.setOwner(operator);
            wallBreak.setPos(dropPos.x, dropPos.y, dropPos.z);
            wallBreak.shoot(0.0D, -1.0D, 0.0D, 0.65F, 0.0F);
            level.addFreshEntity(wallBreak);
        } else if ("timed_grenade".equals(path)) {
            TimedGrenadeEntity timed = new TimedGrenadeEntity(TMMEntities.TIMED_GRENADE, level);
            timed.setOwner(operator);
            timed.setFuseTicks(15); // Short aerial fuse (0.75s)
            timed.setPos(dropPos.x, dropPos.y, dropPos.z);
            timed.shoot(0.0D, -1.0D, 0.0D, 0.45F, 0.0F);
            level.addFreshEntity(timed);
        } else {
            GrenadeEntity grenade = new GrenadeEntity(TMMEntities.GRENADE, level);
            grenade.setOwner(operator);
            grenade.setPos(dropPos.x, dropPos.y, dropPos.z);
            grenade.shoot(0.0D, -1.0D, 0.0D, 0.55F, 0.0F);
            level.addFreshEntity(grenade);
        }
        return TriggerOutcome.lethalDone(true);
    }

    private static void spawnInstantOrdinanceBlast(
            ServerLevel level,
            @Nullable ServerPlayer operator,
            Vec3 pos
    ) {
        TimedGrenadeEntity blast = new TimedGrenadeEntity(TMMEntities.TIMED_GRENADE, level);
        if (operator != null) {
            blast.setOwner(operator);
        }
        blast.setPos(pos.x, pos.y, pos.z);
        blast.setNoGravity(true);
        blast.setDeltaMovement(Vec3.ZERO);
        blast.setFuseTicks(1);
        // Resolve on the next world tick, after the drone has committed its fuse and destruction.
        level.addFreshEntity(blast);
    }

    private static TriggerOutcome executeLethalPoison(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone,
            String path
    ) {
        if ("chlorine_bomb".equals(path)) {
            Vec3 dropPos = drone.position().add(0.0D, -0.15D, 0.0D);
            ChlorineBombEntity chlorine = new ChlorineBombEntity(ModEntities.CHLORINE_BOMB, level);
            chlorine.setOwner(operator);
            chlorine.setPos(dropPos.x, dropPos.y, dropPos.z);
            chlorine.shoot(0.0D, -1.0D, 0.0D, 0.5F, 0.0F);
            level.addFreshEntity(chlorine);
            level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_GRENADE_THROW,
                    SoundSource.PLAYERS, 1.0F, 1.15F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_POISON_STING,
                    dropPos, dropPos.add(0.0D, -1.5D, 0.0D));
            return TriggerOutcome.lethalDone(true);
        }

        if ("poison_gas_tank".equals(path)) {
            Vec3 dropPos = drone.position().add(0.0D, -0.15D, 0.0D);
            PoisonGasTankEntity gasTank = new PoisonGasTankEntity(ModEntities.POISON_GAS_TANK_ENTITY, level);
            gasTank.setOwner(operator);
            gasTank.setPos(dropPos.x, dropPos.y, dropPos.z);
            gasTank.shoot(0.0D, -1.0D, 0.0D, 0.5F, 0.0F);
            level.addFreshEntity(gasTank);
            level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_GRENADE_THROW,
                    SoundSource.PLAYERS, 1.0F, 1.05F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_POISON_STING,
                    dropPos, dropPos.add(0.0D, -1.5D, 0.0D));
            return TriggerOutcome.lethalDone(true);
        }

        if (!DroneRules.isHoveringForPoison(drone.getDeltaMovement().lengthSqr())) {
            return TriggerOutcome.reject("message.habitrain_core.drone.poison_hover");
        }
        ServerPlayer victim = findClosestTargetInRadius(level, operator, drone, drone.position(), 2.2D);
        if (victim == null) {
            return TriggerOutcome.reject("message.habitrain_core.drone.poison_out_of_range");
        }
        SREPlayerPoisonComponent.KEY.get(victim).setPoisonTicks(HSRConstants.toxinPoisonTime, operator.getUUID());
        level.playSound(null, victim.blockPosition(), SoundEvents.PUFFER_FISH_STING,
                SoundSource.PLAYERS, 0.5F, 1.65F);
        DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_POISON_STING,
                drone.position(), victim.getEyePosition().subtract(0.0D, 0.35D, 0.0D));

        // Preserve upstream Doctor nearby notification
        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game != null) {
            for (ServerPlayer nearby : level.players()) {
                if (nearby.distanceToSqr(victim) <= 100.0D && game.isRole(nearby, ModRoles.DOCTOR)) {
                    nearby.displayClientMessage(Component.translatable("message.noellesroles.doctor.someone_toxin")
                            .withStyle(ChatFormatting.YELLOW), true);
                }
            }
        }
        return TriggerOutcome.lethalDone(true);
    }

    private static TriggerOutcome executeControlPayload(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone,
            ItemStack payload,
            String path,
            int cost
    ) {
        Vec3 pos = drone.position();
        if (HabiRoleItems.isPepperSpray(payload)) {
            level.playSound(null, drone.blockPosition(), SoundEvents.FIRE_EXTINGUISH,
                    SoundSource.PLAYERS, 1.3F, 1.35F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_MIST_SPRAY,
                    pos, pos.add(0.0D, -4.5D, 0.0D));
            AABB coneBox = new AABB(pos.x - 2.6D, pos.y - 4.5D, pos.z - 2.6D, pos.x + 2.6D, pos.y + 0.8D, pos.z + 2.6D);
            for (ServerPlayer target : level.getEntitiesOfClass(ServerPlayer.class, coneBox, ServerAimTargeting::isAliveParticipant)) {
                if (target == operator) continue;
                if (hasSegmentLos(level, drone, pos, target.getEyePosition())) {
                    target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 65, 0, false, true, true));
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 70, 2, false, true, true));
                }
            }
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("flash_grenade".equals(path)) {
            FlashGrenadeEntity flash = new FlashGrenadeEntity(ModEntities.FLASH_GRENADE, level);
            flash.setOwner(operator);
            flash.setPos(pos.x, pos.y - 0.15D, pos.z);
            flash.shoot(0.0D, -1.0D, 0.0D, 0.55F, 0.0F);
            level.addFreshEntity(flash);
            level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_GRENADE_THROW, SoundSource.PLAYERS, 1.0F, 1.2F);
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("slime_grenade".equals(path)) {
            SlimeGrenadeEntity slime = new SlimeGrenadeEntity(ModEntities.SLIME_GRENADE, level);
            slime.setOwner(operator);
            slime.setPos(pos.x, pos.y - 0.15D, pos.z);
            slime.shoot(0.0D, -1.0D, 0.0D, 0.55F, 0.0F);
            level.addFreshEntity(slime);
            level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_GRENADE_THROW, SoundSource.PLAYERS, 1.0F, 1.0F);
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("trapper_net_gun".equals(path)) {
            Vec3 origin = pos.add(0.0D, 0.15D, 0.0D);
            Vec3 look = lookVector(drone);
            HitTarget hit = raycastLivingTarget(level, operator, drone, origin, look, 9.0D, 0.45D);
            Vec3 end = hit != null ? hit.hitPos() : blockClipEnd(level, drone, origin, look, 9.0D);
            level.playSound(null, drone.blockPosition(), SoundEvents.LEASH_KNOT_PLACE, SoundSource.PLAYERS, 1.5F, 0.9F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_NET_SHOT, origin, end);
            if (hit != null) {
                hit.player().addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 9, false, true, true));
                DroneOperatorService.applyNetRoot(hit.player(), 50);
                hit.player().displayClientMessage(DroneMessages.create("message.habitrain_core.drone.netted"), true);
            }
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("silence_totem".equals(path)) {
            Vec3 ground = blockClipEnd(level, drone, pos, new Vec3(0.0D, -1.0D, 0.0D), 8.0D);
            DroneOperatorService.spawnSilenceZone(level, ground, 5.5D, 120);
            level.playSound(null, BlockPos.containing(ground), SoundEvents.SCULK_CATALYST_BLOOM,
                    SoundSource.PLAYERS, 1.6F, 0.85F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_SILENCE_FIELD, pos, ground);
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("hallucination_bottle".equals(path)) {
            level.playSound(null, drone.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2F, 0.85F);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y, pos.z, 48, 2.2D, 0.9D, 2.2D, 0.03D);
            HallucinationAreaManager.createHallucinationArea(level, pos, 14.0D, 60, 20, operator);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_MIST_SPRAY, pos, pos.add(0.0D, -3.5D, 0.0D));
            return TriggerOutcome.nonLethal(cost, true);
        }

        if (payload.is(Items.WIND_CHARGE) || "wind_charge".equals(path)) {
            WindCharge wind = new WindCharge(EntityType.WIND_CHARGE, level);
            wind.setOwner(operator);
            wind.setPos(pos.x, pos.y - 0.12D, pos.z);
            Vec3 look = lookVector(drone);
            wind.shoot(look.x, look.y, look.z, 1.35F, 0.0F);
            level.addFreshEntity(wind);
            level.playSound(null, drone.blockPosition(), SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 1.2F, 1.2F);
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("purify_bomb".equals(path)) {
            PurifyBombEntity purify = new PurifyBombEntity(ModEntities.PURIFY_BOMB, level);
            purify.setOwner(operator);
            purify.setPos(pos.x, pos.y - 0.15D, pos.z);
            purify.shoot(0.0D, -1.0D, 0.0D, 0.55F, 0.0F);
            level.addFreshEntity(purify);
            level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_GRENADE_THROW, SoundSource.PLAYERS, 1.0F, 1.25F);
            return TriggerOutcome.nonLethal(cost, true);
        }

        return TriggerOutcome.reject("message.habitrain_core.drone.no_payload");
    }

    private static TriggerOutcome executeDecoyOrSmoke(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone,
            ItemStack payload,
            String path,
            int cost
    ) {
        Vec3 pos = drone.position();
        if ("smoke_grenade".equals(path) || "smoke_pellet".equals(path) || "youlu_smoke".equals(path)) {
            SmokeGrenadeEntity smoke = new SmokeGrenadeEntity(ModEntities.SMOKE_GRENADE, level);
            smoke.setOwner(operator);
            smoke.setPos(pos.x, pos.y - 0.1D, pos.z);
            smoke.shoot(0.0D, -1.0D, 0.0D, 0.4F, 0.0F);
            level.addFreshEntity(smoke);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y, pos.z, 36, 1.3D, 0.8D, 1.3D, 0.02D);
            level.playSound(null, drone.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.3F, 0.85F);
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("flare".equals(path)) {
            FlareEntity flare = new FlareEntity(ModEntities.FLARE, level);
            flare.setOwner(operator);
            flare.setPos(pos.x, pos.y - 0.15D, pos.z);
            flare.shoot(0.0D, -1.0D, 0.0D, 0.65F, 0.0F);
            level.addFreshEntity(flare);
            level.playSound(null, drone.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.1F, 1.2F);
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("firecracker".equals(path)) {
            FirecrackerEntity firecracker = TMMEntities.FIRECRACKER.create(level);
            if (firecracker != null) {
                Vec3 land = blockClipEnd(level, drone, pos, new Vec3(0.0D, -1.0D, 0.0D), 12.0D);
                firecracker.setPos(land.x, land.y + 0.05D, land.z);
                firecracker.setYRot(drone.getYRot());
                level.addFreshEntity(firecracker);
                level.playSound(null, drone.blockPosition(), SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 0.9F, 1.5F);
                if (SRE.REPLAY_MANAGER != null) {
                    SRE.REPLAY_MANAGER.recordItemUse(operator.getUUID(), BuiltInRegistries.ITEM.getKey(payload.getItem()));
                }
            }
            return TriggerOutcome.nonLethal(cost, true);
        }

        DecoyGrenadeEntity decoy = new DecoyGrenadeEntity(ModEntities.DECOY_GRENADE, level);
        decoy.setOwner(operator);
        decoy.setPos(pos.x, pos.y - 0.15D, pos.z);
        decoy.shoot(0.0D, -1.0D, 0.0D, 0.5F, 0.0F);
        level.addFreshEntity(decoy);
        level.playSound(null, drone.blockPosition(), TMMSounds.ITEM_GRENADE_THROW, SoundSource.PLAYERS, 1.0F, 1.1F);
        return TriggerOutcome.nonLethal(cost, true);
    }

    private static TriggerOutcome executeInfiltrationOrLogistics(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone,
            ItemStack payload,
            String path,
            int cost
    ) {
        Vec3 pos = drone.position();

        if ("lockpick".equals(path)
                || "inferior_lockpick".equals(path) || "repair_lockpick".equals(path)) {
            BlockPos doorPos = findNearbyDoorOrButton(level, pos, lookVector(drone), 2.2D);
            if (doorPos == null) {
                return TriggerOutcome.reject("message.habitrain_core.drone.no_door_nearby");
            }
            BlockState state = level.getBlockState(doorPos);
            if (state.getBlock() instanceof SmallDoorBlock) {
                BlockPos lowerPos = state.getValue(SmallDoorBlock.HALF) == DoubleBlockHalf.LOWER ? doorPos : doorPos.below();
                BlockState lowerState = level.getBlockState(lowerPos);
                if (level.getBlockEntity(lowerPos) instanceof SmallDoorBlockEntity door && lowerState.getBlock() instanceof SmallDoorBlock) {
                    door.setJammed(0);
                    SmallDoorBlock.toggleDoorStatic(lowerState, level, door, lowerPos);
                    door.recordTamper((byte) 2);
                    level.playSound(null, lowerPos, TMMSounds.ITEM_LOCKPICK_DOOR, SoundSource.BLOCKS, 1.2F, 1.15F);
                    DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_DOOR_ARC,
                            pos, Vec3.atCenterOf(lowerPos.above()));
                    return TriggerOutcome.nonLethal(cost, true);
                }
            } else if (state.getBlock() instanceof LockableButtonBlock
                    && level.getBlockEntity(doorPos) instanceof LockableButtonBlockEntity btn) {
                btn.setJammed(0);
                level.playSound(null, doorPos, TMMSounds.ITEM_LOCKPICK_DOOR, SoundSource.BLOCKS, 1.2F, 1.15F);
                DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_DOOR_ARC,
                        pos, Vec3.atCenterOf(doorPos));
                return TriggerOutcome.nonLethal(cost, true);
            }
            return TriggerOutcome.reject("message.habitrain_core.drone.no_door_nearby");
        }

        if ("crowbar".equals(path)
                || "repair_crowbar".equals(path) || "repair_bolt_cutter".equals(path)) {
            BlockPos doorPos = findNearbyDoorOrButton(level, pos, lookVector(drone), 2.2D);
            if (doorPos == null) {
                return TriggerOutcome.reject("message.habitrain_core.drone.no_door_nearby");
            }
            BlockState state = level.getBlockState(doorPos);
            if (state.getBlock() instanceof SmallDoorBlock) {
                BlockPos lowerPos = state.getValue(SmallDoorBlock.HALF) == DoubleBlockHalf.LOWER ? doorPos : doorPos.below();
                BlockState lowerState = level.getBlockState(lowerPos);
                if (level.getBlockEntity(lowerPos) instanceof SmallDoorBlockEntity door
                        && !door.isBlasted()
                        && lowerState.getBlock() instanceof SmallDoorBlock sb) {
                    if (!sb.isOpen(lowerState)) {
                        sb.open(lowerState, level, door, lowerPos);
                    }
                    door.blast();
                    level.playSound(null, lowerPos, TMMSounds.ITEM_CROWBAR_PRY, SoundSource.BLOCKS, 2.2F, 1.0F);
                    if (SRE.REPLAY_MANAGER != null) {
                        SRE.REPLAY_MANAGER.recordDoorPry(operator.getUUID(), lowerPos);
                    }
                    DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_DOOR_ARC,
                            pos, Vec3.atCenterOf(lowerPos.above()));
                    return TriggerOutcome.nonLethal(cost, true);
                }
            }
            return TriggerOutcome.reject("message.habitrain_core.drone.no_door_nearby");
        }

        if ("monitor_broken".equals(path)) {
            if (!SREPlayerShopComponent.useMonitorBroken(operator, SREConfig.instance().monitorBrokenDuration * 20)) {
                return TriggerOutcome.reject("message.habitrain_core.drone.device_cooldown");
            }
            level.playSound(null, drone.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.2F, 1.4F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_DOOR_ARC, pos, pos.add(0.0D, 0.8D, 0.0D));
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("blackout".equals(path) || "dream_blackout".equals(path)) {
            if (!SREPlayerShopComponent.useBlackout(operator)) {
                return TriggerOutcome.reject("message.habitrain_core.drone.device_cooldown");
            }
            level.playSound(null, drone.blockPosition(), SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(),
                    SoundSource.PLAYERS, 1.4F, 0.75F);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_DOOR_ARC, pos, pos.add(0.0D, 0.8D, 0.0D));
            return TriggerOutcome.nonLethal(cost, true);
        }

        if ("body_bag".equals(path)) {
            AABB box = drone.getBoundingBox().inflate(2.5D);
            List<PlayerBodyEntity> bodies = level.getEntitiesOfClass(PlayerBodyEntity.class, box, e -> !e.isRemoved());
            if (bodies.isEmpty()) {
                return TriggerOutcome.reject("message.habitrain_core.drone.no_corpse_nearby");
            }
            PlayerBodyEntity closest = bodies.stream()
                    .min(Comparator.comparingDouble(b -> b.distanceToSqr(drone)))
                    .orElse(null);
            if (closest == null) {
                return TriggerOutcome.reject("message.habitrain_core.drone.no_corpse_nearby");
            }
            Vec3 bodyPos = closest.position();
            closest.discard();
            level.playSound(null, BlockPos.containing(bodyPos), SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 1.3F, 0.85F);
            level.sendParticles(ParticleTypes.SMOKE, bodyPos.x, bodyPos.y + 0.3D, bodyPos.z, 26, 0.35D, 0.2D, 0.35D, 0.02D);
            DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_SILENCE_FIELD, pos, bodyPos);
            return TriggerOutcome.nonLethal(cost, true);
        }

        // Precision tactical airdrop (keys, potions, general small items)
        ItemStack droppedCopy = payload.copy();
        ItemEntity drop = new ItemEntity(level, pos.x, pos.y - 0.15D, pos.z, droppedCopy);
        drop.setDeltaMovement(0.0D, -0.08D, 0.0D);
        drop.setPickUpDelay(5);
        level.addFreshEntity(drop);
        level.playSound(null, drone.blockPosition(), SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.PLAYERS, 0.9F, 1.55F);
        DroneOperatorService.broadcastFx(level, drone.getId(), DroneFxPayload.FX_AIRDROP,
                pos, blockClipEnd(level, drone, pos, new Vec3(0.0D, -1.0D, 0.0D), 8.0D));
        return TriggerOutcome.nonLethal(cost, true);
    }

    private record HitTarget(ServerPlayer player, Vec3 hitPos) {}

    private static @Nullable HitTarget raycastLivingTarget(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone,
            Vec3 origin,
            Vec3 look,
            double maxRange,
            double inflate
    ) {
        Vec3 blockEnd = blockClipEnd(level, drone, origin, look, maxRange);
        double maxClipDistSq = origin.distanceToSqr(blockEnd);
        ServerPlayer best = null;
        Vec3 bestPos = null;
        double bestDistSq = maxClipDistSq + 1.0E-4D;

        for (ServerPlayer candidate : level.players()) {
            if (candidate == operator || !ServerAimTargeting.isAliveParticipant(candidate)) {
                continue;
            }
            var hit = candidate.getBoundingBox().inflate(inflate).clip(origin, blockEnd);
            if (hit.isPresent()) {
                double dSq = origin.distanceToSqr(hit.get());
                if (dSq <= bestDistSq) {
                    bestDistSq = dSq;
                    best = candidate;
                    bestPos = hit.get();
                }
            }
        }
        return best == null ? null : new HitTarget(best, bestPos);
    }

    private static @Nullable ServerPlayer findClosestTargetInRadius(
            ServerLevel level,
            ServerPlayer operator,
            ReconDroneEntity drone,
            Vec3 center,
            double radius
    ) {
        double rSq = radius * radius;
        return level.players().stream()
                .filter(p -> p != operator && ServerAimTargeting.isAliveParticipant(p))
                .filter(p -> p.getBoundingBox().distanceToSqr(center) <= rSq)
                .filter(p -> {
                    Vec3 facing = Vec3.directionFromRotation(0.0F, p.getYRot());
                    return DroneRules.isBehind(p.getX(), p.getZ(), center.x, center.z, facing.x, facing.z);
                })
                .filter(p -> level.clip(new ClipContext(
                        center, p.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone
                )).getType() == HitResult.Type.MISS)
                .min(Comparator.comparingDouble(p -> p.distanceToSqr(center)))
                .orElse(null);
    }

    private static @Nullable BlockPos findNearbyDoorOrButton(
            ServerLevel level,
            Vec3 origin,
            Vec3 look,
            double range
    ) {
        BlockHitResult ray = traceToolTarget(level, origin, origin.add(look.scale(range)));
        if (ray.getType() == HitResult.Type.BLOCK) {
            BlockState hitState = level.getBlockState(ray.getBlockPos());
            if (hitState.getBlock() instanceof SmallDoorBlock || hitState.getBlock() instanceof LockableButtonBlock) {
                return ray.getBlockPos();
            }
        }
        BlockPos center = BlockPos.containing(origin);
        BlockPos best = null;
        double bestSq = range * range;
        for (BlockPos candidate : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
            BlockState st = level.getBlockState(candidate);
            if (st.getBlock() instanceof SmallDoorBlock || st.getBlock() instanceof LockableButtonBlock) {
                Vec3 aim = Vec3.atCenterOf(candidate);
                double dSq = aim.distanceToSqr(origin);
                if (dSq <= bestSq) {
                    BlockHitResult line = traceToolTarget(level, origin, aim);
                    if (line.getType() == HitResult.Type.BLOCK && !line.getBlockPos().equals(candidate)) continue;
                    bestSq = dSq;
                    best = candidate.immutable();
                }
            }
        }
        return best;
    }

    private static BlockHitResult traceToolTarget(ServerLevel level, Vec3 origin, Vec3 end) {
        return level.clip(new ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
    }

    private static boolean hasSegmentLos(ServerLevel level, ReconDroneEntity drone, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone))
                .getType() == HitResult.Type.MISS;
    }

    private static Vec3 blockClipEnd(ServerLevel level, net.minecraft.world.entity.Entity ctxEntity, Vec3 origin, Vec3 dir, double range) {
        Vec3 target = origin.add(dir.scale(range));
        BlockHitResult hit = level.clip(new ClipContext(origin, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctxEntity));
        return hit.getType() == HitResult.Type.MISS ? target : hit.getLocation();
    }

    private static Vec3 clipMotion(ServerLevel level, ReconDroneEntity drone, Vec3 origin, Vec3 delta) {
        BlockHitResult hit = level.clip(new ClipContext(
                origin, origin.add(delta), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone
        ));
        if (hit.getType() == HitResult.Type.MISS) {
            return delta;
        }
        return hit.getLocation().subtract(origin).scale(0.8D);
    }

    private static Vec3 lookVector(ReconDroneEntity drone) {
        return Vec3.directionFromRotation(drone.getXRot(), drone.getYRot()).normalize();
    }

    private static String itemPath(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }
}
