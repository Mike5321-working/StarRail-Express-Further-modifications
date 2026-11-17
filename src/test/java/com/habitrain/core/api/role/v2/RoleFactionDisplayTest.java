package com.habitrain.core.api.role.v2;

import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoleFactionDisplayTest {

    private static NormalRole role(String path, boolean innocent, boolean canUseKiller) {
        return new NormalRole(
                ResourceLocation.fromNamespaceAndPath("test", path),
                0xFFFFFF,
                innocent,
                canUseKiller,
                SRERole.MoodType.REAL,
                200,
                false);
    }

    @Test
    void returnsNullForNullInputs() {
        assertNull(RoleFactionDisplay.classify((SRERole) null));
        assertNull(RoleFactionDisplay.classify((EffectiveRoleProfile) null));
    }

    @Test
    void classifiesStandardAndOverlappingSreRoles() {
        SRERole civilian = role("civilian", true, false);
        assertEquals(RoleFactionDisplay.INNOCENT, RoleFactionDisplay.classify(civilian));

        SRERole vigilante = role("vigilante", true, false).setVigilanteTeam(true);
        assertEquals(RoleFactionDisplay.VIGILANTE, RoleFactionDisplay.classify(vigilante));

        SRERole killer = role("killer", false, true);
        assertEquals(RoleFactionDisplay.KILLER, RoleFactionDisplay.classify(killer));

        // Magician spoofs killer instinct via neutralForKiller=true while remaining innocent.
        SRERole magician = role("magician", true, false).setNeutralForKiller(true).setNeutrals(false);
        assertEquals(RoleFactionDisplay.INNOCENT, RoleFactionDisplay.classify(magician));

        SRERole killerNeutral = role("drone_operator", false, false).setNeutrals(true).setNeutralForKiller(true);
        assertEquals(RoleFactionDisplay.NEUTRAL_FOR_KILLER, RoleFactionDisplay.classify(killerNeutral));

        SRERole innocentNeutral = role("amnesiac", false, false).setNeutrals(true).setNeutralForInnocent(true);
        assertEquals(RoleFactionDisplay.NEUTRAL_FOR_INNOCENT, RoleFactionDisplay.classify(innocentNeutral));

        SRERole mafia = role("godfather", false, false).setMafiaTeam(true).setNeutrals(true);
        assertEquals(RoleFactionDisplay.MAFIA, RoleFactionDisplay.classify(mafia));

        SRERole independentNeutral = role("arsonist", false, false).setNeutrals(true);
        assertEquals(RoleFactionDisplay.NEUTRAL, RoleFactionDisplay.classify(independentNeutral));

        SRERole unflaggedNeutral = role("loose_end", false, false);
        assertEquals(RoleFactionDisplay.NEUTRAL, RoleFactionDisplay.classify(unflaggedNeutral));
    }

    @Test
    void matchesEffectiveRoleProfileParity() {
        SRERole killerNeutral = role("wrath", false, false).setNeutrals(true).setNeutralForKiller(true);
        EffectiveRoleProfile profile = EffectiveRoleProfile.from(
                RoleKey.of("test", "wrath"), killerNeutral, EffectiveRole.Source.ADDED);
        assertEquals(RoleFactionDisplay.NEUTRAL_FOR_KILLER, RoleFactionDisplay.classify(profile));

        EffectiveRoleProfile killerProfile = profile.toBuilder()
                .neutral(false)
                .neutralForKiller(false)
                .canUseKiller(true)
                .build();
        assertEquals(RoleFactionDisplay.KILLER, RoleFactionDisplay.classify(killerProfile));
    }
}
