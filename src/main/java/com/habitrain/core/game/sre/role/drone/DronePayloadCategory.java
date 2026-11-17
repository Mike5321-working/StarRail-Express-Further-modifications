package com.habitrain.core.game.sre.role.drone;

/**
 * Belly payload categories for {@code habitrain_core:recon_drone}.
 *
 * <p>Kept free of Minecraft registry bootstrap references so {@link DroneRules}
 * and unit tests can evaluate drain, speed, alert tier, and single-use lethal
 * overload rules in pure JVM tests.
 */
public enum DronePayloadCategory {
    NONE(false, 1.0D, 0, AlertTier.NONE),

    LETHAL_GUN(true, DroneRules.LETHAL_PAYLOAD_DRAIN_MULT, DroneRules.MAX_BATTERY, AlertTier.RED_DANGER),
    LETHAL_BLADE(true, DroneRules.LETHAL_PAYLOAD_DRAIN_MULT, DroneRules.MAX_BATTERY, AlertTier.RED_DANGER),
    LETHAL_EXPLOSIVE(true, DroneRules.LETHAL_PAYLOAD_DRAIN_MULT, DroneRules.MAX_BATTERY, AlertTier.RED_DANGER),
    LETHAL_POISON(true, DroneRules.LETHAL_PAYLOAD_DRAIN_MULT, DroneRules.MAX_BATTERY, AlertTier.RED_DANGER),

    /** 1:1 identical 3P red warning visuals to real lethal weapons, without destroying the drone frame. */
    FAKE_GUN(false, DroneRules.NORMAL_PAYLOAD_DRAIN_MULT, 1600, AlertTier.RED_DANGER),
    FAKE_BLADE(false, DroneRules.NORMAL_PAYLOAD_DRAIN_MULT, 1600, AlertTier.RED_DANGER),
    FAKE_EXPLOSIVE(false, DroneRules.NORMAL_PAYLOAD_DRAIN_MULT, 1600, AlertTier.RED_DANGER),

    CONTROL(false, DroneRules.NORMAL_PAYLOAD_DRAIN_MULT, 2400, AlertTier.AMBER_CONTROL),
    DECOY_SMOKE(false, DroneRules.NORMAL_PAYLOAD_DRAIN_MULT, 1600, AlertTier.AMBER_CONTROL),
    INFILTRATION_LOGISTICS(false, DroneRules.NORMAL_PAYLOAD_DRAIN_MULT, 2400, AlertTier.CYAN_UTILITY);

    public enum AlertTier {
        NONE(0x000000),
        RED_DANGER(0xEF4444),
        AMBER_CONTROL(0xFBBF24),
        CYAN_UTILITY(0x38BDF8);

        private final int rgb;

        AlertTier(int rgb) {
            this.rgb = rgb;
        }

        public int rgb() {
            return rgb;
        }
    }

    private final boolean lethal;
    private final double drainMultiplier;
    private final int defaultTriggerCost;
    private final AlertTier alertTier;

    DronePayloadCategory(boolean lethal, double drainMultiplier, int defaultTriggerCost, AlertTier alertTier) {
        this.lethal = lethal;
        this.drainMultiplier = drainMultiplier;
        this.defaultTriggerCost = defaultTriggerCost;
        this.alertTier = alertTier;
    }

    public boolean isLethal() {
        return lethal;
    }

    public boolean isFakeWeapon() {
        return this == FAKE_GUN || this == FAKE_BLADE || this == FAKE_EXPLOSIVE;
    }

    public boolean hasPayload() {
        return this != NONE;
    }

    public double drainMultiplier() {
        return drainMultiplier;
    }

    public int defaultTriggerCost() {
        return defaultTriggerCost;
    }

    public AlertTier alertTier() {
        return alertTier;
    }

    public static DronePayloadCategory byId(int id) {
        DronePayloadCategory[] values = values();
        return id >= 0 && id < values.length ? values[id] : NONE;
    }
}
