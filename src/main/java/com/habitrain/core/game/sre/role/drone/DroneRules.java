package com.habitrain.core.game.sre.role.drone;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Pure gameplay rules and numerical constants for {@code habitrain_core:drone_operator}.
 *
 * <p>All formulas here are deterministic and independent of Minecraft server/client
 * singletons so they can be verified directly by JUnit 5 tests.
 */
public final class DroneRules {
    private DroneRules() {}

    // --- Theme & Economy ---
    public static final int THEME_COLOR = 0xFF38BDF8;
    public static final int REPLACEMENT_DRONE_PRICE = 150;
    public static final int SPARE_BATTERY_PRICE = 100;
    public static final int FIRECRACKER_PRICE = 50;
    public static final int FAKE_KNIFE_PRICE = 60;
    public static final int LOCKPICK_PRICE = 90;
    public static final int SMOKE_GRENADE_PRICE = 100;
    public static final int PEPPER_SPRAY_PRICE = 120;
    public static final int MAX_SPARE_BATTERIES = 3;

    // --- Battery & Signal ---
    public static final int MAX_BATTERY = 16000;
    public static final int LOW_BATTERY_THRESHOLD = 3200; // 20%
    public static final double HOVER_DRAIN_PER_SEC = 15.0D;
    public static final double NORMAL_FLIGHT_DRAIN_PER_SEC = 35.0D;
    public static final double SPRINT_FLIGHT_DRAIN_PER_SEC = 60.0D;

    public static final double NORMAL_PAYLOAD_DRAIN_MULT = 1.15D;
    public static final double LETHAL_PAYLOAD_DRAIN_MULT = 1.35D;
    public static final double WEAK_SIGNAL_DRAIN_MULT = 1.5D;

    public static final double WEAK_SIGNAL_DISTANCE = 48.0D;
    public static final double MAX_SIGNAL_DISTANCE = 72.0D;

    // --- Speed & Transitions ---
    public static final double BASE_FLIGHT_SPEED = 0.42D;
    public static final double SPRINT_FLIGHT_SPEED = 0.64D;
    public static final double LETHAL_SPEED_MULT = 0.85D;      // -15%
    public static final double LOW_BATTERY_SPEED_MULT = 0.75D; // -25%

    public static final double RECALL_INTERACT_RANGE = 2.5D;
    public static final double BODY_PROXIMITY_ALERT_RANGE = 4.0D;
    public static final int DEPLOY_TICKS = 10;           // 0.5s
    public static final int RECALL_TICKS = 6;            // 0.3s
    public static final int BATTERY_SWAP_TICKS = 30;     // 1.5s
    public static final int DESTROYED_STATIC_TICKS = 10; // 0.5s
    public static final int UNBOX_TICKS = 28;            // 1.4s take-out & unfold new drone animation
    public static final int SCRAP_WRECK_TICKS = 70;      // 3.5s scrapped old drone wreck before breakup

    // --- Pulse Scan ---
    public static final int PULSE_SCAN_COST = 800;          // 5%
    public static final int PULSE_SCAN_COOLDOWN_TICKS = 160; // 8s
    public static final double PULSE_SCAN_RADIUS = 25.0D;
    public static final int OPERATOR_SCAN_GLOW_TICKS = 120; // 6s
    public static final int KILLER_ALLY_SCAN_GLOW_TICKS = 80; // 4s

    // --- Scan Outline RGBs ---
    public static final int SCAN_COLOR_INNOCENT = 0x38BDF8;
    public static final int SCAN_COLOR_ARMED = 0xFBBF24;
    public static final int SCAN_COLOR_KILLER = 0xEF4444;
    public static final int SCAN_COLOR_CORPSE = 0xA855F7;

    // --- Single-Use Lethal Payload Overload ---
    public static final int LETHAL_WINDUP_TICKS = 10; // 0.5s

    public enum FlightMode {
        HOVER,
        NORMAL,
        SPRINT
    }

    public record BatteryDrainStep(int nextBattery, double nextAccumulator, boolean forcedCrashLanding) {}

    public record Vec3d(double x, double y, double z) {
        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    public record LethalTriggerResult(
            boolean allowed,
            int nextBattery,
            boolean droneDestroyed
    ) {}

    public record BatterySwapResult(
            boolean success,
            int nextBattery,
            int remainingSpareBatteries
    ) {}

    public record PayloadUnloadResult(
            boolean unloaded,
            boolean hasDroneFrameAfter,
            int nextBattery,
            boolean droneDestroyed
    ) {}

    public static int clampBattery(int battery) {
        return Math.max(0, Math.min(MAX_BATTERY, battery));
    }

    public static int batteryPercent(int battery) {
        if (battery <= 0) return 0;
        if (battery >= MAX_BATTERY) return 100;
        return (int) Math.round(battery * 100.0D / MAX_BATTERY);
    }

    public static boolean isLowBattery(int battery) {
        return battery <= LOW_BATTERY_THRESHOLD;
    }

    public static boolean isWeakSignal(double distanceToOperator) {
        return distanceToOperator >= WEAK_SIGNAL_DISTANCE;
    }

    public static boolean isOutOfRange(double distanceToOperator) {
        return distanceToOperator >= MAX_SIGNAL_DISTANCE;
    }

    /**
     * Computes dynamic battery drain per second. Mid-air recharge is strictly impossible
     * (drain is always strictly positive while airborne).
     */
    public static double computeDrainPerSecond(
            FlightMode flightMode,
            DronePayloadCategory category,
            double distanceToOperator
    ) {
        double base = switch (flightMode == null ? FlightMode.HOVER : flightMode) {
            case HOVER -> HOVER_DRAIN_PER_SEC;
            case NORMAL -> NORMAL_FLIGHT_DRAIN_PER_SEC;
            case SPRINT -> SPRINT_FLIGHT_DRAIN_PER_SEC;
        };
        double payloadMult = category == null ? 1.0D : category.drainMultiplier();
        double signalMult = isWeakSignal(distanceToOperator) ? WEAK_SIGNAL_DRAIN_MULT : 1.0D;
        return base * payloadMult * signalMult;
    }

    /**
     * Advances 1 server tick (1/20s) of battery consumption without rounding drift.
     */
    public static BatteryDrainStep stepTickDrain(int currentBattery, double accumulator, double drainPerSecond) {
        int clamped = clampBattery(currentBattery);
        if (clamped <= 0) {
            return new BatteryDrainStep(0, 0.0D, true);
        }
        double total = Math.max(0.0D, accumulator) + Math.max(0.0D, drainPerSecond) / 20.0D;
        int wholeUnits = (int) Math.floor(total + 1.0E-9D);
        double nextAcc = Math.max(0.0D, total - wholeUnits);
        int next = Math.max(0, clamped - wholeUnits);
        if (next == 0) {
            nextAcc = 0.0D;
        }
        return new BatteryDrainStep(next, nextAcc, next == 0);
    }

    public static double computeSpeedMultiplier(DronePayloadCategory category, int battery) {
        if (battery <= 0) {
            return 0.0D;
        }
        double mult = 1.0D;
        if (category != null && category.isLethal()) {
            mult *= LETHAL_SPEED_MULT;
        }
        if (isLowBattery(battery)) {
            mult *= LOW_BATTERY_SPEED_MULT;
        }
        return mult;
    }

    /**
     * Blocks outward radial acceleration when distance from operator reaches or exceeds 72 blocks,
     * while preserving inward return and tangential motion.
     */
    public static Vec3d clampOutwardVelocity(Vec3d offsetFromOperator, Vec3d desiredVelocity) {
        double dist = offsetFromOperator.length();
        if (dist < MAX_SIGNAL_DISTANCE || dist < 1.0E-6D) {
            return desiredVelocity;
        }
        double nx = offsetFromOperator.x() / dist;
        double ny = offsetFromOperator.y() / dist;
        double nz = offsetFromOperator.z() / dist;
        double radial = desiredVelocity.x() * nx + desiredVelocity.y() * ny + desiredVelocity.z() * nz;
        if (radial <= 0.0D) {
            return desiredVelocity;
        }
        return new Vec3d(
                desiredVelocity.x() - radial * nx,
                desiredVelocity.y() - radial * ny,
                desiredVelocity.z() - radial * nz
        );
    }

    public static boolean canRecallOrSwap(double distanceToOperator, boolean unobstructedLineOfSight) {
        return distanceToOperator <= RECALL_INTERACT_RANGE && unobstructedLineOfSight;
    }

    public static boolean isHoveringForPoison(double speedSquared) {
        return speedSquared <= 0.05D * 0.05D;
    }

    public static boolean isBehind(double targetX, double targetZ, double droneX, double droneZ,
                                   double forwardX, double forwardZ) {
        double dx = droneX - targetX;
        double dz = droneZ - targetZ;
        double length = Math.hypot(dx, dz);
        return length > 1.0E-6D && (dx * forwardX + dz * forwardZ) / length < -0.5D;
    }

    /**
     * Fold-recall strictly preserves remaining battery (no free refill).
     */
    public static int preserveBatteryOnRecall(int currentDroneBattery) {
        return clampBattery(currentDroneBattery);
    }

    public static BatterySwapResult applyBatterySwap(int currentBattery, int spareBatteriesInInventory) {
        if (spareBatteriesInInventory <= 0 || currentBattery >= MAX_BATTERY) {
            return new BatterySwapResult(false, clampBattery(currentBattery), Math.max(0, spareBatteriesInInventory));
        }
        return new BatterySwapResult(true, MAX_BATTERY, spareBatteriesInInventory - 1);
    }

    public static boolean canPulseScan(int battery, int cooldownTicks) {
        return !isLowBattery(battery) && battery >= PULSE_SCAN_COST && cooldownTicks <= 0;
    }

    public static boolean canMountPayload(
            DronePayloadCategory category,
            boolean hasIntactDroneFrame,
            boolean mountOccupied
    ) {
        return hasIntactDroneFrame && category != null && category.hasPayload() && !mountOccupied;
    }

    /**
     * Evaluates triggering a lethal payload:
     * <ul>
     *   <li>Requires an active powered drone ({@code currentBattery > 0}).</li>
     *   <li>Executes once and immediately overloads/destroys that drone ({@code droneDestroyed = true}),
     *       requiring the operator to buy a replacement drone (150 coins) in the shop.</li>
     * </ul>
     */
    public static LethalTriggerResult triggerLethalPayload(int currentBattery) {
        if (currentBattery <= 0) {
            return new LethalTriggerResult(false, 0, false);
        }
        return new LethalTriggerResult(true, 0, true);
    }

    /**
     * Evaluates manually unloading/removing a mounted payload from the drone belly rack:
     * returns the item to the player and immediately destroys the drone frame.
     */
    public static PayloadUnloadResult unloadMountedPayload(DronePayloadCategory mountedCategory) {
        if (mountedCategory == null || !mountedCategory.hasPayload()) {
            return new PayloadUnloadResult(false, true, 0, false);
        }
        return new PayloadUnloadResult(true, false, 0, true);
    }

    /**
     * Only the drone operator (owner) and the player who mounted the current payload
     * are permitted to unload the payload from the drone.
     */
    public static boolean canUnloadMountedPayload(
            @Nullable UUID actorId,
            @Nullable UUID ownerId,
            @Nullable UUID mounterId
    ) {
        if (actorId == null) {
            return false;
        }
        return actorId.equals(ownerId) || actorId.equals(mounterId);
    }

    /**
     * Whole-unit resupply (150 coins) is always purchasable — even when a drone is currently
     * stowed or online in the world. Purchasing a new drone while an old drone exists immediately
     * scraps the old drone and renders it permanently unrecoverable.
     */
    public static boolean canBuyReplacementDrone(boolean hasStowedFrame, boolean hasWorldDroneEntity) {
        return true;
    }

    public static boolean canBuySpareBattery(int currentBatteryItemCount) {
        return spareBatterySpace(currentBatteryItemCount) > 0;
    }

    public static int spareBatterySpace(int currentBatteryItemCount) {
        return Math.max(0, MAX_SPARE_BATTERIES - Math.max(0, currentBatteryItemCount));
    }
}
