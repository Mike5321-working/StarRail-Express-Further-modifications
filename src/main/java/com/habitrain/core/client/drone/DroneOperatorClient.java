package com.habitrain.core.client.drone;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.drone.DroneInputLatch;
import com.habitrain.core.game.sre.role.drone.DroneOperatorService;
import com.habitrain.core.game.sre.role.drone.DronePayloadCategory;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import com.habitrain.core.game.sre.role.drone.ReconDroneEntity;
import com.habitrain.core.network.DroneCommandPayload;
import com.habitrain.core.network.DroneFxPayload;
import com.habitrain.core.network.DroneInputPayload;
import com.habitrain.core.network.DroneScanHighlightPayload;
import com.habitrain.core.network.DroneStatePayload;
import com.mojang.blaze3d.platform.InputConstants;
import io.wifi.starrailexpress.index.TMMItems;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Client runtime coordinator for {@code habitrain_core:drone_operator}:
 * manages FPV camera state, isolated physical flight inputs, non-drone key
 * suppression in FPV, right-click edge latching, scan highlights, 3P ambient
 * payload particles/sounds, and recoil animations.
 */
@Environment(EnvType.CLIENT)
public final class DroneOperatorClient {
    private DroneOperatorClient() {}

    public static final String KEY_DRONE_SCAN = "key.habitrain_core.drone_scan";
    public static final String KEY_DRONE_SWAP_BATTERY = "key.habitrain_core.drone_swap_battery";
    public static final String KEY_DRONE_RECALL_FOLD = "key.habitrain_core.drone_recall_fold";

    public static final int POSE_NONE = -1;
    public static final int POSE_DEPLOY = 0;
    public static final int POSE_PILOTING = 1;
    public static final int POSE_BATTERY_SWAP = 2;
    public static final int POSE_RECALL = 3;
    public static final int POSE_UNBOX = 4;

    public static final class ScanEntry {
        public final UUID uuid;
        public final int entityId;
        public final int rgbColor;
        public volatile int remainingTicks;

        ScanEntry(UUID uuid, int entityId, int rgbColor, int remainingTicks) {
            this.uuid = uuid;
            this.entityId = entityId;
            this.rgbColor = rgbColor;
            this.remainingTicks = remainingTicks;
        }
    }

    public static final class PulseWave {
        public final Vec3 origin;
        public int ageTicks;

        PulseWave(Vec3 origin) {
            this.origin = origin;
        }
    }

    public static final class WorldBurst {
        public final byte fxType;
        public final Vec3 from;
        public final Vec3 to;
        public int ageTicks;
        public final int maxTicks;

        WorldBurst(byte fxType, Vec3 from, Vec3 to, int maxTicks) {
            this.fxType = fxType;
            this.from = from;
            this.to = to;
            this.maxTicks = maxTicks;
        }
    }

    private static final Vector3f CYAN_DUST = new Vector3f(0.22F, 0.74F, 0.97F);
    private static final Vector3f CRIMSON_DUST = new Vector3f(0.95F, 0.18F, 0.26F);
    private static final Vector3f POISON_DUST = new Vector3f(0.18F, 0.92F, 0.42F);

    private static KeyMapping scanKey;
    private static KeyMapping swapBatteryKey;
    private static KeyMapping foldRecallKey;

    private static DroneStatePayload state = DroneStatePayload.EMPTY;
    private static final DroneInputLatch LATCH = new DroneInputLatch();
    private static final IntSet PRESSED_SCANCODES = new IntOpenHashSet();
    private static final Map<UUID, ScanEntry> HIGHLIGHTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<PulseWave> PULSE_WAVES = new ArrayList<>();
    private static final List<WorldBurst> WORLD_BURSTS = new ArrayList<>();
    private static final Map<Integer, Integer> DRONE_RECOIL_TICKS = new HashMap<>();
    private static final Map<Integer, Integer> PLAYER_UNBOX_TICKS = new HashMap<>();
    private static final Map<UUID, ReconDroneEntity> DRONES_BY_OWNER = new HashMap<>();
    private static final Map<Integer, ReconDroneEntity> TRACKED_DRONES = new HashMap<>();

    private static @Nullable ClientLevel lastWorld;
    private static int clientTicks;
    private static float fpvYaw;
    private static float fpvPitch;
    private static float smoothRollDeg;
    private static float smoothFovBoost;
    private static boolean wasFpvActive;
    private static boolean wasUnboxing;
    private static boolean initialized;

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        DroneShaders.register();
        DroneWorldFxRenderer.register();
        EntityRendererRegistry.register(DroneOperatorService.RECON_DRONE, ReconDroneRenderer::new);

        scanKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_DRONE_SCAN,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.habitrain_core.drone"
        ));
        swapBatteryKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_DRONE_SWAP_BATTERY,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.habitrain_core.drone"
        ));
        foldRecallKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_DRONE_RECALL_FOLD,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                "category.habitrain_core.drone"
        ));

        ClientPlayNetworking.registerGlobalReceiver(DroneStatePayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> onReceiveState(payload)));
        ClientPlayNetworking.registerGlobalReceiver(DroneScanHighlightPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> onReceiveScanHighlight(payload)));
        ClientPlayNetworking.registerGlobalReceiver(DroneFxPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> onReceiveFx(payload)));

        ClientEntityEvents.ENTITY_LOAD.register((entity, clientLevel) -> {
            if (entity instanceof ReconDroneEntity drone) {
                TRACKED_DRONES.put(drone.getId(), drone);
            }
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, clientLevel) -> {
            if (entity instanceof ReconDroneEntity drone) {
                TRACKED_DRONES.remove(drone.getId(), drone);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.isSameThread()) reset(true);
            else client.execute(() -> reset(true));
        });
        ClientTickEvents.END_CLIENT_TICK.register(DroneOperatorClient::tickClient);
        HudRenderCallback.EVENT.register((g, delta) ->
                DroneFpvHudRenderer.render(g, delta.getGameTimeDeltaPartialTick(false)));
    }

    private static void reset() {
        reset(false);
    }

    private static void reset(boolean clearTracked) {
        state = DroneStatePayload.EMPTY;
        LATCH.reset();
        PRESSED_SCANCODES.clear();
        HIGHLIGHTS.clear();
        PULSE_WAVES.clear();
        WORLD_BURSTS.clear();
        DRONE_RECOIL_TICKS.clear();
        PLAYER_UNBOX_TICKS.clear();
        DRONES_BY_OWNER.clear();
        if (clearTracked) TRACKED_DRONES.clear();
        wasFpvActive = false;
        wasUnboxing = false;
        smoothRollDeg = 0.0F;
        smoothFovBoost = 1.0F;
        lastWorld = null;
    }

    private static void onReceiveState(DroneStatePayload next) {
        Minecraft mc = Minecraft.getInstance();
        boolean prevFpv = wasFpvActive;
        boolean nextFpv = next.fpvActive();

        if (nextFpv && !prevFpv) {
            ReconDroneEntity drone = findDroneById(mc.level, next.droneEntityId());
            if (drone != null) {
                fpvYaw = drone.getYRot();
                fpvPitch = drone.getXRot();
            } else if (mc.player != null) {
                fpvYaw = mc.player.getYRot();
                fpvPitch = mc.player.getXRot();
            }
            KeyMapping.releaseAll();
        } else if (!nextFpv && prevFpv) {
            KeyMapping.releaseAll();
        }

        boolean nextUnboxing = next.transitionMode() == DroneStatePayload.TRANSITION_UNBOXING
                && next.transitionTicks() > 0;
        if (nextUnboxing && !wasUnboxing && mc.player != null) {
            PLAYER_UNBOX_TICKS.put(mc.player.getId(), next.transitionTicks());
            if (mc.screen instanceof MenuAccess<?>) {
                mc.screen.onClose();
            }
        } else if (!nextUnboxing && wasUnboxing && mc.player != null) {
            PLAYER_UNBOX_TICKS.remove(mc.player.getId());
        }

        wasFpvActive = nextFpv;
        wasUnboxing = nextUnboxing;
        state = next;
        syncLatchWithHardware(mc, isPilotingFpv());
    }

    private static void syncLatchWithHardware(Minecraft mc, boolean fpvActive) {
        LATCH.syncFpvState(
                fpvActive,
                isPhysicallyDownUnscreened(mc, scanKey),
                isPhysicallyDownUnscreened(mc, swapBatteryKey),
                isPhysicallyDownUnscreened(mc, foldRecallKey),
                mc != null && isPhysicallyDownUnscreened(mc, mc.options.keyAttack),
                mc != null && isPhysicallyDownUnscreened(mc, mc.options.keyUse)
        );
    }

    private static void onReceiveScanHighlight(DroneScanHighlightPayload payload) {
        if (!payload.pulseWave() && payload.targets().isEmpty()) {
            HIGHLIGHTS.clear();
            return;
        }
        if (payload.pulseWave()) {
            PULSE_WAVES.add(new PulseWave(new Vec3(payload.originX(), payload.originY(), payload.originZ())));
        }
        for (DroneScanHighlightPayload.Target t : payload.targets()) {
            HIGHLIGHTS.put(t.uuid(), new ScanEntry(t.uuid(), t.entityId(), t.rgbColor(), t.durationTicks()));
        }
    }

    private static void onReceiveFx(DroneFxPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Vec3 from = new Vec3(payload.x(), payload.y(), payload.z());
        Vec3 to = new Vec3(payload.tx(), payload.ty(), payload.tz());

        int duration = switch (payload.fxType()) {
            case DroneFxPayload.FX_GUN_SHOT -> {
                DRONE_RECOIL_TICKS.put(payload.droneEntityId(), 8);
                level.addParticle(ParticleTypes.CRIT, from.x + 0.12D, from.y, from.z, 0.14D, 0.12D, -0.08D);
                level.addParticle(ParticleTypes.SMOKE, from.x, from.y, from.z, 0.0D, 0.04D, 0.0D);
                yield 6;
            }
            case DroneFxPayload.FX_BLADE_SLASH -> {
                level.addParticle(ParticleTypes.SWEEP_ATTACK, from.x, from.y, from.z, 0.0D, 0.0D, 0.0D);
                yield 8;
            }
            case DroneFxPayload.FX_DEPLOY_BURST -> {
                for (int i = 0; i < 14; i++) {
                    double a = i * Math.PI * 2.0D / 14.0D;
                    level.addParticle(ParticleTypes.CLOUD,
                            from.x + Math.cos(a) * 0.35D, from.y - 0.1D, from.z + Math.sin(a) * 0.35D,
                            Math.cos(a) * 0.06D, -0.03D, Math.sin(a) * 0.06D);
                    level.addParticle(new DustParticleOptions(CYAN_DUST, 0.65F),
                            from.x + Math.cos(a) * 0.3D, from.y, from.z + Math.sin(a) * 0.3D,
                            0.0D, 0.02D, 0.0D);
                }
                yield 8;
            }
            case DroneFxPayload.FX_RECALL_STREAM -> {
                for (int i = 0; i < 12; i++) {
                    Vec3 p = from.lerp(to, i / 12.0D);
                    level.addParticle(new DustParticleOptions(CYAN_DUST, 0.55F), p.x, p.y, p.z, 0.0D, 0.01D, 0.0D);
                }
                yield 7;
            }
            case DroneFxPayload.FX_MIST_SPRAY -> {
                for (int i = 0; i < 24; i++) {
                    double ox = (level.random.nextDouble() - 0.5D) * 1.6D;
                    double oz = (level.random.nextDouble() - 0.5D) * 1.6D;
                    level.addParticle(ParticleTypes.CLOUD, from.x + ox * 0.3D, from.y - 0.1D, from.z + oz * 0.3D,
                            ox * 0.12D, -0.22D, oz * 0.12D);
                }
                yield 10;
            }
            case DroneFxPayload.FX_POISON_STING -> {
                for (int i = 0; i < 14; i++) {
                    Vec3 p = from.lerp(to, i / 14.0D);
                    level.addParticle(new DustParticleOptions(POISON_DUST, 0.65F), p.x, p.y, p.z, 0.0D, 0.01D, 0.0D);
                }
                yield 8;
            }
            case DroneFxPayload.FX_RESUPPLY_UNBOX -> {
                PLAYER_UNBOX_TICKS.put(payload.droneEntityId(), DroneRules.UNBOX_TICKS);
                for (int i = 0; i < 12; i++) {
                    double a = i * Math.PI * 2.0D / 12.0D;
                    level.addParticle(new DustParticleOptions(CYAN_DUST, 0.58F),
                            from.x + Math.cos(a) * 0.42D, from.y + 1.05D, from.z + Math.sin(a) * 0.42D,
                            Math.cos(a) * 0.03D, 0.025D, Math.sin(a) * 0.03D);
                }
                yield 10;
            }
            case DroneFxPayload.FX_DRONE_SCRAPPED -> {
                for (int i = 0; i < 16; i++) {
                    double ox = (level.random.nextDouble() - 0.5D) * 0.5D;
                    double oy = (level.random.nextDouble() - 0.5D) * 0.3D;
                    double oz = (level.random.nextDouble() - 0.5D) * 0.5D;
                    level.addParticle(ParticleTypes.LARGE_SMOKE, from.x + ox, from.y + oy, from.z + oz,
                            ox * 0.08D, 0.03D, oz * 0.08D);
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, from.x + ox, from.y + oy, from.z + oz,
                            ox * 0.35D, 0.12D, oz * 0.35D);
                }
                yield 10;
            }
            default -> 10;
        };

        WORLD_BURSTS.add(new WorldBurst(payload.fxType(), from, to, duration));
    }

    private static void tickClient(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            if (lastWorld != null) reset();
            return;
        }
        if (lastWorld != mc.level) {
            reset();
            lastWorld = mc.level;
        }
        clientTicks++;

        // Tick highlights, holo pulse waves, world bursts, and recoil counters
        HIGHLIGHTS.values().removeIf(e -> --e.remainingTicks <= 0);
        PULSE_WAVES.removeIf(w -> ++w.ageTicks > 22);
        WORLD_BURSTS.removeIf(b -> ++b.ageTicks >= b.maxTicks);
        Iterator<Map.Entry<Integer, Integer>> rit = DRONE_RECOIL_TICKS.entrySet().iterator();
        while (rit.hasNext()) {
            Map.Entry<Integer, Integer> entry = rit.next();
            if (entry.getValue() <= 1) rit.remove();
            else entry.setValue(entry.getValue() - 1);
        }
        Iterator<Map.Entry<Integer, Integer>> uit = PLAYER_UNBOX_TICKS.entrySet().iterator();
        while (uit.hasNext()) {
            Map.Entry<Integer, Integer> entry = uit.next();
            if (entry.getValue() <= 1) uit.remove();
            else entry.setValue(entry.getValue() - 1);
        }

        // Ambient 3P particles & distance-attenuated sounds on all visible drones + operators
        tickAmbientWorldVisuals(mc);

        boolean piloting = isPilotingFpv();
        boolean screenOpen = mc.screen != null;
        DroneInputLatch.TickActions actions = LATCH.stepTick(
                piloting,
                screenOpen,
                isPhysicallyDownUnscreened(mc, scanKey),
                isPhysicallyDownUnscreened(mc, swapBatteryKey),
                isPhysicallyDownUnscreened(mc, foldRecallKey),
                isPhysicallyDownUnscreened(mc, mc.options.keyAttack),
                isPhysicallyDownUnscreened(mc, mc.options.keyUse)
        );

        if (piloting) {
            if (actions.exitFpv()) {
                ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.EXIT_FPV));
            } else {
                if (actions.pulseScan()) {
                    ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.PULSE_SCAN));
                }
                if (actions.swapBattery()) {
                    ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.SWAP_BATTERY));
                }
                if (actions.recallFold()) {
                    ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.RECALL_FOLD));
                }
                if (actions.triggerPayload()) {
                    ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.TRIGGER_PAYLOAD));
                }
            }

            // Physical GLFW flight polling bypasses upstream KeyBindingMixin jump/move suppression!
            float forward = (isPhysicallyDown(mc, mc.options.keyUp) ? 1.0F : 0.0F)
                    - (isPhysicallyDown(mc, mc.options.keyDown) ? 1.0F : 0.0F);
            float strafe = (isPhysicallyDown(mc, mc.options.keyLeft) ? 1.0F : 0.0F)
                    - (isPhysicallyDown(mc, mc.options.keyRight) ? 1.0F : 0.0F);
            float vertical = (isPhysicallyDown(mc, mc.options.keyJump) ? 1.0F : 0.0F)
                    - (isPhysicallyDown(mc, mc.options.keyShift) ? 1.0F : 0.0F);
            boolean sprint = isPhysicallyDown(mc, mc.options.keySprint);

            ClientPlayNetworking.send(new DroneInputPayload(forward, strafe, vertical, fpvYaw, fpvPitch, sprint));

            float targetRoll = -strafe * 6.0F;
            smoothRollDeg = Mth.lerp(0.32F, smoothRollDeg, targetRoll);
            smoothFovBoost = Mth.lerp(0.25F, smoothFovBoost,
                    (sprint && (Math.abs(forward) > 0.01F || Math.abs(strafe) > 0.01F)) ? 1.08F : 1.0F);

            if (state.bodyDangerNearby() && clientTicks % 10 == 0) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.45F, 1.9F);
            }
        } else {
            // On-foot [R] / [Z] close-range convenience without clobbering KeyMapping.MAP for guns or zoom!
            if (!screenOpen && state.droneEntityId() != -1
                    && HabiRoles.isHabiRole(mc.player, HabiRoles.DRONE_OPERATOR_ID)) {
                ReconDroneEntity drone = activeDroneEntity();
                if (drone != null && mc.player.distanceToSqr(drone) <= 16.0D) {
                    if (actions.swapBattery() && !mc.player.getMainHandItem().is(TMMItems.SNIPER_RIFLE)) {
                        ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.SWAP_BATTERY));
                    }
                    if (actions.recallFold()) {
                        ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.RECALL_FOLD));
                    }
                }
            }
            smoothRollDeg = Mth.lerp(0.35F, smoothRollDeg, 0.0F);
            smoothFovBoost = Mth.lerp(0.35F, smoothFovBoost, 1.0F);
        }
    }

    private static void tickAmbientWorldVisuals(Minecraft mc) {
        ClientLevel level = mc.level;
        DRONES_BY_OWNER.clear();
        if (level == null || TRACKED_DRONES.isEmpty()) return;
        Iterator<ReconDroneEntity> it = TRACKED_DRONES.values().iterator();
        while (it.hasNext()) {
            ReconDroneEntity drone = it.next();
            if (drone == null || drone.isRemoved() || drone.level() != level) {
                it.remove();
                continue;
            }

            DronePayloadCategory cat = drone.payloadCategory();
            Vec3 pos = drone.position();

            if (drone.isScrapped()) {
                if (clientTicks % 2 == 0) {
                    level.addParticle(ParticleTypes.SMOKE,
                            pos.x + (level.random.nextDouble() - 0.5D) * 0.25D,
                            pos.y + 0.10D,
                            pos.z + (level.random.nextDouble() - 0.5D) * 0.25D,
                            0.0D, 0.03D, 0.0D);
                }
                continue;
            }

            UUID ownerId = drone.ownerId();
            if (ownerId != null && !drone.isRemoved()) {
                DRONES_BY_OWNER.put(ownerId, drone);
                if (drone.droneMode() == ReconDroneEntity.MODE_DEPLOYING) {
                    Player owner = level.getPlayerByUUID(ownerId);
                    if (owner != null) {
                        PLAYER_UNBOX_TICKS.remove(owner.getId());
                    }
                }
            }

            if (!drone.isCrashed() && clientTicks % 5 == 0) {
                level.addParticle(new DustParticleOptions(CYAN_DUST, 0.35F),
                        pos.x + (level.random.nextDouble() - 0.5D) * 0.35D,
                        pos.y + 0.06D,
                        pos.z + (level.random.nextDouble() - 0.5D) * 0.35D,
                        0.0D, -0.03D, 0.0D);
            }

            if (cat == DronePayloadCategory.LETHAL_EXPLOSIVE || cat == DronePayloadCategory.FAKE_EXPLOSIVE) {
                if (clientTicks % 8 == 0) {
                    level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.NOTE_BLOCK_BIT.value(),
                            SoundSource.PLAYERS, 0.32F, 1.95F, false);
                }
            } else if (cat == DronePayloadCategory.LETHAL_POISON && clientTicks % 7 == 0) {
                level.addParticle(new DustParticleOptions(POISON_DUST, 0.48F),
                        pos.x, pos.y - 0.04D, pos.z, 0.0D, -0.04D, 0.0D);
            } else if ((cat == DronePayloadCategory.LETHAL_BLADE || cat == DronePayloadCategory.FAKE_BLADE) && clientTicks % 3 == 0) {
                level.addParticle(new DustParticleOptions(CRIMSON_DUST, 0.42F),
                        pos.x, pos.y + 0.04D, pos.z, 0.0D, 0.0D, 0.0D);
            } else if (cat == DronePayloadCategory.DECOY_SMOKE && clientTicks % 6 == 0) {
                level.addParticle(ParticleTypes.CLOUD, pos.x, pos.y + 0.02D, pos.z, 0.0D, 0.01D, 0.0D);
            }

            if (ownerId != null && !drone.isCrashed() && drone.isOperatorPiloting() && clientTicks % 6 == 0) {
                Player owner = level.getPlayerByUUID(ownerId);
                if (owner != null && owner.isAlive()) {
                    Vec3 screenPos = owner.position()
                            .add(Vec3.directionFromRotation(0.0F, owner.getYRot()).scale(0.50D))
                            .add(0.0D, 0.78D, 0.0D);
                    level.addParticle(new DustParticleOptions(CYAN_DUST, 0.32F),
                            screenPos.x, screenPos.y, screenPos.z, 0.0D, 0.018D, 0.0D);
                }
            }
        }
    }

    // --- Physical Input & Key Isolation Helpers ---

    public static boolean isDroneExclusiveKeyName(@Nullable String name) {
        return KEY_DRONE_SCAN.equals(name)
                || KEY_DRONE_SWAP_BATTERY.equals(name)
                || KEY_DRONE_RECALL_FOLD.equals(name);
    }

    public static boolean isDroneExclusiveKey(@Nullable KeyMapping mapping) {
        if (mapping == null) return false;
        return mapping == scanKey
                || mapping == swapBatteryKey
                || mapping == foldRecallKey
                || isDroneExclusiveKeyName(mapping.getName());
    }

    public static boolean isDroneActiveControlKey(@Nullable KeyMapping mapping) {
        if (mapping == null) return false;
        if (isDroneExclusiveKey(mapping)) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return false;
        return mapping == mc.options.keyUp
                || mapping == mc.options.keyDown
                || mapping == mc.options.keyLeft
                || mapping == mc.options.keyRight
                || mapping == mc.options.keyJump
                || mapping == mc.options.keyShift
                || mapping == mc.options.keySprint
                || mapping == mc.options.keyAttack
                || mapping == mc.options.keyUse;
    }

    /**
     * Prevents false red key-conflict warnings in Controls UI between drone-only hotkeys
     * ({@code G}/{@code R}/{@code Z}) and on-foot keys that are suppressed during FPV,
     * while still highlighting genuine collisions among drone controls themselves.
     */
    public static boolean areContextDisjointKeys(@Nullable KeyMapping a, @Nullable KeyMapping b) {
        if (a == null || b == null || a == b) return false;
        boolean aExcl = isDroneExclusiveKey(a);
        boolean bExcl = isDroneExclusiveKey(b);
        if (aExcl && bExcl) {
            return false;
        }
        if (aExcl) {
            return !isDroneActiveControlKey(b);
        }
        if (bExcl) {
            return !isDroneActiveControlKey(a);
        }
        return false;
    }

    public static boolean isSystemBypassKey(int key) {
        return key == GLFW.GLFW_KEY_ESCAPE
                || key == GLFW.GLFW_KEY_F2
                || key == GLFW.GLFW_KEY_F11;
    }

    public static boolean shouldSuppressVanillaKeyMapping() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.screen == null && isPilotingFpv();
    }

    public static void onRawKeyEvent(int key, int scancode, int action) {
        if (scancode > 0) {
            if (action == GLFW.GLFW_RELEASE) {
                PRESSED_SCANCODES.remove(scancode);
            } else {
                PRESSED_SCANCODES.add(scancode);
            }
        }
        if (action != GLFW.GLFW_PRESS || !shouldSuppressVanillaKeyMapping()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (matchesBoundKey(scanKey, key, scancode)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.SCAN);
        }
        if (matchesBoundKey(swapBatteryKey, key, scancode)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.SWAP_BATTERY);
        }
        if (matchesBoundKey(foldRecallKey, key, scancode)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.RECALL_FOLD);
        }
        if (mc != null && matchesBoundKey(mc.options.keyAttack, key, scancode)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.ATTACK);
        }
        if (mc != null && matchesBoundKey(mc.options.keyUse, key, scancode)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.USE);
        }
    }

    public static void onRawMouseButton(int button, int action) {
        if (action != GLFW.GLFW_PRESS || !shouldSuppressVanillaKeyMapping()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (matchesBoundMouse(scanKey, button)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.SCAN);
        }
        if (matchesBoundMouse(swapBatteryKey, button)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.SWAP_BATTERY);
        }
        if (matchesBoundMouse(foldRecallKey, button)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.RECALL_FOLD);
        }
        if (mc != null && matchesBoundMouse(mc.options.keyAttack, button)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.ATTACK);
        }
        if (mc != null && matchesBoundMouse(mc.options.keyUse, button)) {
            LATCH.onRawPressInFpv(DroneInputLatch.Channel.USE);
        }
    }

    private static boolean matchesBoundKey(@Nullable KeyMapping mapping, int key, int scancode) {
        if (mapping == null || mapping.isUnbound()) return false;
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mapping);
        return switch (bound.getType()) {
            case KEYSYM -> key != InputConstants.UNKNOWN.getValue() && bound.getValue() == key;
            case SCANCODE -> scancode > 0 && bound.getValue() == scancode;
            case MOUSE -> false;
        };
    }

    private static boolean matchesBoundMouse(@Nullable KeyMapping mapping, int button) {
        if (mapping == null || mapping.isUnbound()) return false;
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mapping);
        return bound.getType() == InputConstants.Type.MOUSE && bound.getValue() == button;
    }

    public static void latchCurrentButtonsBeforeRefocus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        LATCH.latchFocusBoundary(
                isPhysicallyDownRaw(mc, scanKey),
                isPhysicallyDownRaw(mc, swapBatteryKey),
                isPhysicallyDownRaw(mc, foldRecallKey),
                isPhysicallyDownRaw(mc, mc.options.keyAttack),
                isPhysicallyDownRaw(mc, mc.options.keyUse)
        );
    }

    public static boolean isPhysicallyDown(Minecraft mc, @Nullable KeyMapping mapping) {
        return mc != null && mc.screen == null && mc.mouseHandler.isMouseGrabbed() && isPhysicallyDownRaw(mc, mapping);
    }

    private static boolean isPhysicallyDownUnscreened(Minecraft mc, @Nullable KeyMapping mapping) {
        if (mc == null) return false;
        if (mc.screen == null && !mc.mouseHandler.isMouseGrabbed()) {
            return false;
        }
        return isPhysicallyDownRaw(mc, mapping);
    }

    private static boolean isPhysicallyDownRaw(Minecraft mc, @Nullable KeyMapping mapping) {
        if (mc == null || mapping == null || mapping.isUnbound()) return false;
        long window = mc.getWindow().getWindow();
        if (window == 0L) return false;
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mapping);
        int code = bound.getValue();
        return switch (bound.getType()) {
            case KEYSYM -> code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST
                    && GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
            case MOUSE -> code >= 0 && code <= GLFW.GLFW_MOUSE_BUTTON_LAST
                    && GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS;
            case SCANCODE -> code > 0 && PRESSED_SCANCODES.contains(code);
        };
    }

    // --- Hooks called by Client Mixins ---

    public static boolean isPilotingFpv() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && mc.player.isAlive() && !mc.player.isSpectator() && state.fpvActive();
    }

    public static boolean isCameraControlledByDrone() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || !mc.player.isAlive() || mc.player.isSpectator()) {
            return false;
        }
        if (state.fpvActive()) return true;
        return state.transitionMode() == DroneStatePayload.TRANSITION_DEPLOYING
                || state.transitionMode() == DroneStatePayload.TRANSITION_RECALLING;
    }

    public static boolean shouldHideOwnDroneMeshInFpv(ReconDroneEntity drone) {
        if (!state.fpvActive() || drone.getId() != state.droneEntityId()) {
            return false;
        }
        if (state.transitionMode() == DroneStatePayload.TRANSITION_DEPLOYING && state.transitionTicks() > 4) {
            return false;
        }
        return true;
    }

    public static void onMouseTurn(double yawDelta, double pitchDelta) {
        fpvYaw = Mth.wrapDegrees(fpvYaw + (float) (yawDelta * 0.15D));
        fpvPitch = Mth.clamp(fpvPitch + (float) (pitchDelta * 0.15D), -88.0F, 88.0F);
    }

    public static boolean handleFpvAttackClick() {
        return isPilotingFpv();
    }

    /**
     * Intercepts {@code Minecraft#startUseItem} for:
     * <ul>
     *   <li>Post-FPV / post-deploy right-click hold suppression (prevents enter/exit ping-pong).</li>
     *   <li>Active FPV right-click blocking (handled cleanly on rising edge by {@link #LATCH}).</li>
     *   <li>Friendly close-range drone interaction & aim-assist (unloading payload, mounting weapons
     *       without shooting own/ally drone on a 1px miss).</li>
     *   <li>Drone terminal & spare battery right-click bypass when looking at disabled decorative blocks.</li>
     * </ul>
     */
    public static boolean handleStartUseItem(Minecraft mc) {
        if (mc == null) return false;
        boolean usePhysicallyDown = isPhysicallyDownUnscreened(mc, mc.options.keyUse);
        if (LATCH.shouldSuppressOnFootUse(usePhysicallyDown)) {
            return true;
        }
        if (isPilotingFpv()) {
            return true;
        }
        if (mc.player == null || mc.level == null || mc.gameMode == null
                || !mc.player.isAlive() || mc.player.isSpectator()) {
            return false;
        }

        // 1. Direct or close-range cushioned friendly drone interaction (unload while sneaking, or mount weapon)
        ReconDroneEntity friendlyTarget = findFriendlyCloseAimedDrone(mc);
        if (friendlyTarget != null && DroneOperatorService.shouldConsumeDirectEntityInteract(friendlyTarget, mc.player)) {
            // If player holds terminal without sneaking, fall through to DEPLOY_OR_TOGGLE below so it toggles FPV cleanly
            boolean unshiftedTerminal = !mc.player.isShiftKeyDown()
                    && HabiRoleItems.isDroneTerminal(mc.player.getMainHandItem());
            if (!unshiftedTerminal) {
                mc.gameMode.interact(mc.player, friendlyTarget, InteractionHand.MAIN_HAND);
                mc.player.swing(InteractionHand.MAIN_HAND);
                LATCH.notifyOnFootUseConsumed();
                return true;
            }
        }

        // 2. Drone Operator terminal & spare battery right-click (bypasses decorative block FAIL cancellation)
        if (HabiRoles.isHabiRole(mc.player, HabiRoles.DRONE_OPERATOR_ID)) {
            ItemStack mainHand = mc.player.getMainHandItem();
            if (HabiRoleItems.isDroneTerminal(mainHand)) {
                ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.DEPLOY_OR_TOGGLE));
                mc.player.swing(InteractionHand.MAIN_HAND);
                LATCH.notifyOnFootUseConsumed();
                return true;
            }
            if (HabiRoleItems.isDroneBattery(mainHand) && canUseSpareBatteryNow(mc.player)) {
                ClientPlayNetworking.send(new DroneCommandPayload(DroneCommandPayload.Action.USE_SPARE_BATTERY));
                mc.player.swing(InteractionHand.MAIN_HAND);
                LATCH.notifyOnFootUseConsumed();
                return true;
            }
        }

        return false;
    }

    private static boolean canUseSpareBatteryNow(Player player) {
        ReconDroneEntity drone = activeDroneEntity();
        if (drone != null && !drone.isRemoved()) {
            return drone.battery() < DroneRules.MAX_BATTERY
                    && DroneOperatorService.isWithinCloseInteractRange(player, drone);
        }
        return state.hasDroneFrame() && state.droneEntityId() == -1 && state.battery() < DroneRules.MAX_BATTERY;
    }

    private static @Nullable ReconDroneEntity findFriendlyCloseAimedDrone(Minecraft mc) {
        Player player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return null;

        if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof ReconDroneEntity direct) {
            return direct;
        }

        boolean isCollaborator = DroneOperatorService.isFriendlyDroneCollaborator(player);
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(3.0D));
        AABB searchBox = player.getBoundingBox().inflate(3.2D);

        ReconDroneEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (ReconDroneEntity drone : level.getEntitiesOfClass(ReconDroneEntity.class, searchBox,
                d -> !d.isRemoved() && !d.isScrapped())) {
            boolean isOwner = player.getUUID().equals(drone.ownerId());
            if (!isOwner && !isCollaborator) continue;
            if (!DroneOperatorService.isWithinCloseInteractRange(player, drone)) continue;
            var hit = drone.getBoundingBox().inflate(0.28D).clip(eye, end);
            if (hit.isPresent()) {
                double dSq = eye.distanceToSqr(hit.get());
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    best = drone;
                }
            }
        }
        return best;
    }

    public static @Nullable ReconDroneEntity activeDroneEntity() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null ? findDroneById(mc.level, state.droneEntityId()) : null;
    }

    private static @Nullable ReconDroneEntity findDroneById(@Nullable ClientLevel level, int entityId) {
        if (level == null || entityId < 0) return null;
        return level.getEntity(entityId) instanceof ReconDroneEntity d ? d : null;
    }

    public static OptionalInt scanOutlineColor(Entity entity) {
        if (entity == null) return OptionalInt.empty();
        ScanEntry entry = HIGHLIGHTS.get(entity.getUUID());
        return entry != null && entry.remainingTicks > 0 ? OptionalInt.of(entry.rgbColor) : OptionalInt.empty();
    }

    /**
     * Returns 3P operator animation mode for any visible player in the level:
     * {@code -1} = none, {@code 0} = deploy toss, {@code 1} = half-squat terminal piloting,
     * {@code 2} = magnetic battery swap, {@code 3} = recall catch, {@code 4} = resupply take-out &amp; unfold.
     */
    public static int getThirdPersonOperatorPose(Player player) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc != null ? mc.level : null;
        if (level == null || player == null || !player.isAlive()) return POSE_NONE;
        ReconDroneEntity drone = DRONES_BY_OWNER.get(player.getUUID());
        if (drone == null && player == mc.player) {
            ReconDroneEntity own = activeDroneEntity();
            if (own != null && !own.isScrapped() && player.getUUID().equals(own.ownerId())) {
                drone = own;
            }
        }
        if (drone != null && !drone.isRemoved() && !drone.isScrapped()) {
            byte m = drone.droneMode();
            if (m == ReconDroneEntity.MODE_DEPLOYING) return POSE_DEPLOY;
            if (m == ReconDroneEntity.MODE_SWAPPING) return POSE_BATTERY_SWAP;
            if (m == ReconDroneEntity.MODE_RECALLING) return POSE_RECALL;
            boolean localPiloting = player == mc.player && isCameraControlledByDrone();
            if (m == ReconDroneEntity.MODE_ACTIVE && (drone.isOperatorPiloting() || localPiloting)) {
                return POSE_PILOTING;
            }
        }
        if (getThirdPersonUnboxProgress(player, 0.0F) >= 0.0F) {
            return POSE_UNBOX;
        }
        return POSE_NONE;
    }

    public static float getFirstPersonUnboxProgress(float partialTick) {
        if (state.transitionMode() != DroneStatePayload.TRANSITION_UNBOXING || state.transitionTicks() <= 0) {
            return -1.0F;
        }
        float rem = Math.max(0.0F, state.transitionTicks() - partialTick);
        return Mth.clamp(1.0F - rem / (float) DroneRules.UNBOX_TICKS, 0.0F, 1.0F);
    }

    public static float getThirdPersonUnboxProgress(Player player, float partialTick) {
        if (player == null) return -1.0F;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && player == mc.player) {
            float local = getFirstPersonUnboxProgress(partialTick);
            if (local >= 0.0F) {
                return local;
            }
        }
        Integer ticks = PLAYER_UNBOX_TICKS.get(player.getId());
        if (ticks == null || ticks <= 0) {
            return -1.0F;
        }
        float rem = Math.max(0.0F, ticks - partialTick);
        return Mth.clamp(1.0F - rem / (float) DroneRules.UNBOX_TICKS, 0.0F, 1.0F);
    }

    public static float getDroneRecoilPitch(int entityId, float partialTick) {
        Integer ticks = DRONE_RECOIL_TICKS.get(entityId);
        if (ticks == null || ticks <= 0) return 0.0F;
        return Mth.clamp((ticks - partialTick) / 8.0F, 0.0F, 1.0F);
    }

    public static String scanKeyLabel() {
        return keyLabel(scanKey, "G");
    }

    public static String swapBatteryKeyLabel() {
        return keyLabel(swapBatteryKey, "R");
    }

    public static String foldRecallKeyLabel() {
        return keyLabel(foldRecallKey, "Z");
    }

    public static String attackKeyLabel() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.options != null ? keyLabel(mc.options.keyAttack, "LMB") : "LMB";
    }

    public static String useKeyLabel() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.options != null ? keyLabel(mc.options.keyUse, "RMB") : "RMB";
    }

    private static String keyLabel(@Nullable KeyMapping mapping, String fallback) {
        if (mapping == null) return fallback;
        String label = mapping.getTranslatedKeyMessage().getString();
        return label == null || label.isBlank() ? fallback : label;
    }

    public static DroneStatePayload state() {
        return state;
    }

    public static int clientTicks() {
        return clientTicks;
    }

    public static float fpvYaw() {
        return fpvYaw;
    }

    public static float fpvPitch() {
        return fpvPitch;
    }

    public static float cameraRollDegrees() {
        return smoothRollDeg;
    }

    public static float fovMultiplier() {
        return smoothFovBoost;
    }

    public static Map<UUID, ScanEntry> activeHighlights() {
        return HIGHLIGHTS;
    }

    public static List<PulseWave> pulseWaves() {
        return PULSE_WAVES;
    }

    public static List<WorldBurst> worldBursts() {
        return WORLD_BURSTS;
    }
}
