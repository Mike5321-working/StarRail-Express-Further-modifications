package com.habitrain.core.game.sre.role.drone;

import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.api.role.v2.definition.RoleVisibilityProfile;
import com.habitrain.core.role.extension.ManagedSRERole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DroneRulesTest {

    @Test
    void batteryDrainRatesFollowDynamicMultipliersAndNeverRechargeInMidAir() {
        assertEquals(15.0D, DroneRules.computeDrainPerSecond(
                DroneRules.FlightMode.HOVER, DronePayloadCategory.NONE, 10.0D), 1.0E-6D);
        assertEquals(35.0D, DroneRules.computeDrainPerSecond(
                DroneRules.FlightMode.NORMAL, DronePayloadCategory.NONE, 20.0D), 1.0E-6D);
        assertEquals(60.0D, DroneRules.computeDrainPerSecond(
                DroneRules.FlightMode.SPRINT, DronePayloadCategory.NONE, 47.9D), 1.0E-6D);

        // Normal payload x1.15
        assertEquals(35.0D * 1.15D, DroneRules.computeDrainPerSecond(
                DroneRules.FlightMode.NORMAL, DronePayloadCategory.FAKE_BLADE, 15.0D), 1.0E-6D);
        assertEquals(35.0D * 1.15D, DroneRules.computeDrainPerSecond(
                DroneRules.FlightMode.NORMAL, DronePayloadCategory.INFILTRATION_LOGISTICS, 15.0D), 1.0E-6D);

        // Lethal payload x1.35
        assertEquals(60.0D * 1.35D, DroneRules.computeDrainPerSecond(
                DroneRules.FlightMode.SPRINT, DronePayloadCategory.LETHAL_GUN, 12.0D), 1.0E-6D);

        // Weak signal zone (48~72m) x1.5 combined with lethal payload x1.35
        assertEquals(35.0D * 1.35D * 1.5D, DroneRules.computeDrainPerSecond(
                DroneRules.FlightMode.NORMAL, DronePayloadCategory.LETHAL_EXPLOSIVE, 48.0D), 1.0E-6D);

        // Drain is strictly positive across every flight mode and payload
        for (DroneRules.FlightMode mode : DroneRules.FlightMode.values()) {
            for (DronePayloadCategory category : DronePayloadCategory.values()) {
                assertTrue(DroneRules.computeDrainPerSecond(mode, category, 0.0D) > 0.0D);
            }
        }
    }

    @Test
    void tickDrainAccumulatorMatchesExactPerSecondRateAndTriggersZeroBatteryCrashLanding() {
        int battery = DroneRules.MAX_BATTERY; // 16000
        double acc = 0.0D;

        // 20 ticks (1 second) of hover (15/sec) drains exactly 15 units
        for (int i = 0; i < 20; i++) {
            DroneRules.BatteryDrainStep step = DroneRules.stepTickDrain(battery, acc, DroneRules.HOVER_DRAIN_PER_SEC);
            battery = step.nextBattery();
            acc = step.nextAccumulator();
            assertFalse(step.forcedCrashLanding());
        }
        assertEquals(DroneRules.MAX_BATTERY - 15, battery);
        assertEquals(0.0D, acc, 1.0E-6D);

        // Drain final 3 units to 0 -> triggers forced crash-landing
        DroneRules.BatteryDrainStep crash = DroneRules.stepTickDrain(2, 0.5D, 60.0D);
        assertEquals(0, crash.nextBattery());
        assertTrue(crash.forcedCrashLanding());
    }

    @Test
    void speedMultiplierAppliesLethalWeightAndLowBatteryPenalties() {
        assertEquals(1.0D, DroneRules.computeSpeedMultiplier(DronePayloadCategory.NONE, DroneRules.MAX_BATTERY), 1.0E-6D);
        assertEquals(0.85D, DroneRules.computeSpeedMultiplier(DronePayloadCategory.LETHAL_BLADE, DroneRules.LOW_BATTERY_THRESHOLD + 1000), 1.0E-6D);
        assertEquals(0.75D, DroneRules.computeSpeedMultiplier(DronePayloadCategory.NONE, DroneRules.LOW_BATTERY_THRESHOLD), 1.0E-6D);
        assertEquals(0.85D * 0.75D, DroneRules.computeSpeedMultiplier(DronePayloadCategory.LETHAL_GUN, DroneRules.LOW_BATTERY_THRESHOLD), 1.0E-6D);
        assertEquals(0.0D, DroneRules.computeSpeedMultiplier(DronePayloadCategory.NONE, 0), 1.0E-6D);
    }

    @Test
    void maxSignalBoundaryBlocksOutwardAccelerationWhileAllowingReturnAndTangentialFlight() {
        DroneRules.Vec3d atBoundary = new DroneRules.Vec3d(72.0D, 0.0D, 0.0D);

        // Outward +X component is stripped; tangential +Z component is preserved
        DroneRules.Vec3d clamped = DroneRules.clampOutwardVelocity(atBoundary, new DroneRules.Vec3d(0.5D, 0.0D, 0.3D));
        assertEquals(0.0D, clamped.x(), 1.0E-6D);
        assertEquals(0.3D, clamped.z(), 1.0E-6D);

        // Inward -X return flight is untouched
        DroneRules.Vec3d returning = DroneRules.clampOutwardVelocity(atBoundary, new DroneRules.Vec3d(-0.5D, 0.0D, 0.2D));
        assertEquals(-0.5D, returning.x(), 1.0E-6D);
        assertEquals(0.2D, returning.z(), 1.0E-6D);
    }

    @Test
    void recallAndBatterySwapRequireCloseUnobstructedLineOfSightAndPreserveFoldedBattery() {
        assertTrue(DroneRules.canRecallOrSwap(2.5D, true));
        assertFalse(DroneRules.canRecallOrSwap(2.51D, true));
        assertFalse(DroneRules.canRecallOrSwap(1.2D, false));

        // Fold recall strictly inherits exact remaining battery without refilling
        assertEquals(734, DroneRules.preserveBatteryOnRecall(734));
        assertEquals(0, DroneRules.preserveBatteryOnRecall(0));

        // Magnetic swap consumes 1 spare battery and restores to 16000 (100%)
        DroneRules.BatterySwapResult swapped = DroneRules.applyBatterySwap(420, 2);
        assertTrue(swapped.success());
        assertEquals(DroneRules.MAX_BATTERY, swapped.nextBattery());
        assertEquals(1, swapped.remainingSpareBatteries());

        // Cannot swap without spare battery or when already at 100%
        assertFalse(DroneRules.applyBatterySwap(420, 0).success());
        assertFalse(DroneRules.applyBatterySwap(DroneRules.MAX_BATTERY, 2).success());
    }

    @Test
    void pulseScanRequiresAboveLowBatteryThresholdAndNoCooldown() {
        assertEquals(800, DroneRules.PULSE_SCAN_COST);
        assertEquals(25.0D, DroneRules.PULSE_SCAN_RADIUS, 1.0E-6D);
        assertEquals(5, DroneRules.batteryPercent(DroneRules.PULSE_SCAN_COST));
        assertTrue(DroneRules.canPulseScan(DroneRules.LOW_BATTERY_THRESHOLD + DroneRules.PULSE_SCAN_COST, 0));
        assertFalse(DroneRules.canPulseScan(DroneRules.LOW_BATTERY_THRESHOLD, 0), "Scan must be disabled at <=20% low battery");
        assertFalse(DroneRules.canPulseScan(DroneRules.MAX_BATTERY, 1), "Scan must respect 8s cooldown");
    }

    @ParameterizedTest
    @EnumSource(value = DronePayloadCategory.class, names = {
            "LETHAL_GUN", "LETHAL_BLADE", "LETHAL_EXPLOSIVE", "LETHAL_POISON"
    })
    void lethalPayloadsOverloadAndDestroyCurrentDroneAfterSingleTriggerRequiringRepurchase(DronePayloadCategory lethalCat) {
        assertTrue(lethalCat.isLethal());
        assertEquals(DronePayloadCategory.AlertTier.RED_DANGER, lethalCat.alertTier());
        assertTrue(DroneRules.canMountPayload(lethalCat, true, false));

        // Triggering on an active drone executes once and immediately destroys that drone frame
        DroneRules.LethalTriggerResult first = DroneRules.triggerLethalPayload(1600);
        assertTrue(first.allowed());
        assertEquals(0, first.nextBattery());
        assertTrue(first.droneDestroyed());

        // Destroyed frame cannot mount payloads until a 150-coin replacement drone is bought
        assertFalse(DroneRules.canMountPayload(lethalCat, false, false));
        assertTrue(DroneRules.canBuyReplacementDrone(false, false));

        // Once a fresh 150-coin replacement drone is bought, payloads can be mounted again
        assertTrue(DroneRules.canMountPayload(lethalCat, true, false));
    }

    @Test
    void lethalTriggerRequiresPositiveBatteryAndDestroysDroneAcrossAllBatteryLevels() {
        DroneRules.LethalTriggerResult unpowered = DroneRules.triggerLethalPayload(0);
        assertFalse(unpowered.allowed());
        assertEquals(0, unpowered.nextBattery());
        assertFalse(unpowered.droneDestroyed());

        DroneRules.LethalTriggerResult full = DroneRules.triggerLethalPayload(DroneRules.MAX_BATTERY);
        assertTrue(full.allowed());
        assertEquals(0, full.nextBattery());
        assertTrue(full.droneDestroyed());

        DroneRules.LethalTriggerResult low = DroneRules.triggerLethalPayload(450);
        assertTrue(low.allowed());
        assertEquals(0, low.nextBattery());
        assertTrue(low.droneDestroyed());
    }

    @ParameterizedTest
    @EnumSource(value = DronePayloadCategory.class, names = {
            "FAKE_GUN", "FAKE_BLADE", "FAKE_EXPLOSIVE", "CONTROL", "DECOY_SMOKE", "INFILTRATION_LOGISTICS"
    })
    void nonLethalAndFakePayloadsAreMountableOnIntactDroneAndFakeWeaponsMimicRedDanger(DronePayloadCategory nonLethalCat) {
        assertFalse(nonLethalCat.isLethal());
        assertTrue(DroneRules.canMountPayload(nonLethalCat, true, false));
        assertFalse(DroneRules.canMountPayload(nonLethalCat, false, false));
        if (nonLethalCat.isFakeWeapon()) {
            assertEquals(DronePayloadCategory.AlertTier.RED_DANGER, nonLethalCat.alertTier(),
                    "Fake weapons must mimic 1:1 RED_DANGER alert visuals in 3P");
        }
    }

    @ParameterizedTest
    @EnumSource(value = DronePayloadCategory.class, names = {
            "LETHAL_GUN", "LETHAL_BLADE", "LETHAL_EXPLOSIVE", "LETHAL_POISON",
            "FAKE_GUN", "FAKE_BLADE", "FAKE_EXPLOSIVE", "CONTROL", "DECOY_SMOKE", "INFILTRATION_LOGISTICS"
    })
    void unloadingAnyMountedPayloadDestroysDroneFrameAndClearsBattery(DronePayloadCategory category) {
        DroneRules.PayloadUnloadResult result = DroneRules.unloadMountedPayload(category);
        assertTrue(result.unloaded());
        assertFalse(result.hasDroneFrameAfter());
        assertEquals(0, result.nextBattery());
        assertTrue(result.droneDestroyed());

        DroneRules.PayloadUnloadResult empty = DroneRules.unloadMountedPayload(DronePayloadCategory.NONE);
        assertFalse(empty.unloaded());
        assertTrue(empty.hasDroneFrameAfter());
        assertFalse(empty.droneDestroyed());
    }

    @Test
    void onlyDroneOperatorAndPayloadMounterCanUnloadMountedPayload() {
        UUID operator = UUID.randomUUID();
        UUID mounter = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();

        // Collaborator-mounted payload: operator and mounter allowed, third party rejected
        assertTrue(DroneRules.canUnloadMountedPayload(operator, operator, mounter));
        assertTrue(DroneRules.canUnloadMountedPayload(mounter, operator, mounter));
        assertFalse(DroneRules.canUnloadMountedPayload(bystander, operator, mounter));
        assertFalse(DroneRules.canUnloadMountedPayload(null, operator, mounter));

        // Self-mounted by operator (or legacy stowed without separate mounter): only operator allowed
        assertTrue(DroneRules.canUnloadMountedPayload(operator, operator, operator));
        assertTrue(DroneRules.canUnloadMountedPayload(operator, operator, null));
        assertFalse(DroneRules.canUnloadMountedPayload(bystander, operator, operator));
        assertFalse(DroneRules.canUnloadMountedPayload(bystander, operator, null));
    }

    @Test
    void shopEconomySetsDronePrice150BatteryPrice100AndAllowsResupplyEvenWhileOnline() {
        assertEquals(150, DroneRules.REPLACEMENT_DRONE_PRICE);
        assertEquals(100, DroneRules.SPARE_BATTERY_PRICE);
        assertEquals(16000, DroneRules.MAX_BATTERY);
        assertEquals(3, DroneRules.MAX_SPARE_BATTERIES);
        assertTrue(DroneRules.UNBOX_TICKS > 0);
        assertTrue(DroneRules.SCRAP_WRECK_TICKS > 0);

        // Replacement unit can be purchased anytime (no drone, stowed drone, or active world drone — old drone is scrapped)
        assertTrue(DroneRules.canBuyReplacementDrone(false, false));
        assertTrue(DroneRules.canBuyReplacementDrone(true, false));
        assertTrue(DroneRules.canBuyReplacementDrone(false, true));
        assertTrue(DroneRules.canBuyReplacementDrone(true, true));

        // Spare battery cap = 3
        assertTrue(DroneRules.canBuySpareBattery(0));
        assertTrue(DroneRules.canBuySpareBattery(2));
        assertFalse(DroneRules.canBuySpareBattery(3));
    }

    @Test
    void droneOperatorRoleDefinitionCompilesAsKillerAlignedNeutralWithoutRevolverPickup() {
        RoleDefinition def = RoleDefinition.builder(ResourceLocation.parse("habitrain_core:drone_operator"))
                .presentation(RolePresentation.builder()
                        .color(DroneRules.THEME_COLOR)
                        .moodType(SRERole.MoodType.FAKE)
                        .build())
                .faction(RoleFactionProfile.builder()
                        .neutral()
                        .neutralForKiller()
                        .build())
                .spawn(RoleSpawnProfile.builder().defaultMax(1).build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .canSeeCoin()
                        .canPickUpRevolver(false)
                        .build())
                .visibility(RoleVisibilityProfile.builder()
                        .canUseInstinct()
                        .instinctNightVision()
                        .canSeeTeammateKiller()
                        .build())
                .maxSprintTime(Integer.MAX_VALUE)
                .canSeeTime(true)
                .build();

        SRERole compiled = ManagedSRERole.compile(def);
        assertFalse(compiled.isInnocent());
        assertFalse(compiled.canUseKiller());
        assertTrue(compiled.isNeutralForKiller());
        assertFalse(compiled.canPickUpRevolver());
        assertEquals(SRERole.MoodType.FAKE, compiled.getMoodType());
        assertEquals(Integer.MAX_VALUE, compiled.getMaxSprintTime());
        assertTrue(compiled.canSeeTime());
    }
}
