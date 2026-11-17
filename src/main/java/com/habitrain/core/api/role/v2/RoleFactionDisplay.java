package com.habitrain.core.api.role.v2;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

/**
 * Automatic faction classification for displaying a role's faction in HUDs.
 *
 * <p>Precedence resolves overlapping upstream flags deterministically (for example,
 * Magician sets both {@code innocent=true} and {@code neutralForKiller=true} for
 * killer-instinct spoofing, so innocent takes priority over neutral-for-killer).
 */
public enum RoleFactionDisplay {
    VIGILANTE("hud.habitrain_core.role_faction.vigilante", 0xFF22BBCC),
    INNOCENT("hud.habitrain_core.role_faction.innocent", 0xFF44BB66),
    MAFIA("hud.habitrain_core.role_faction.mafia", 0xFFDA70D6),
    NEUTRAL_FOR_KILLER("hud.habitrain_core.role_faction.neutral_killer", 0xFFAA44CC),
    KILLER("hud.habitrain_core.role_faction.killer", 0xFFCC2233),
    NEUTRAL_FOR_INNOCENT("hud.habitrain_core.role_faction.neutral_innocent", 0xFF66CC99),
    NEUTRAL("hud.habitrain_core.role_faction.neutral", 0xFFCCAA22);

    private final String translationKey;
    private final int color;

    RoleFactionDisplay(String translationKey, int color) {
        this.translationKey = translationKey;
        this.color = color;
    }

    /** Translation key for this faction's display name. */
    public String translationKey() {
        return translationKey;
    }

    /** Opaque ARGB color associated with this faction. */
    public int color() {
        return color;
    }

    /** Colored translatable component for HUD rendering. */
    public MutableComponent displayName() {
        return Component.translatable(translationKey)
                .withStyle(style -> style.withColor(color & 0x00FFFFFF));
    }

    /** Classifies a live {@link SRERole}, or returns {@code null} if {@code role} is null. */
    public static @Nullable RoleFactionDisplay classify(@Nullable SRERole role) {
        if (role == null) {
            return null;
        }
        return classifyFlags(
                role.isInnocent(),
                role.canUseKiller(),
                role.isKillerTeam(),
                role.isVigilanteTeam(),
                role.isMafiaTeam(),
                role.isNeutrals(),
                role.isNeutralForKiller(),
                role.isNeutralForInnocent());
    }

    /** Classifies an {@link EffectiveRoleProfile}, or returns {@code null} if {@code profile} is null. */
    public static @Nullable RoleFactionDisplay classify(@Nullable EffectiveRoleProfile profile) {
        if (profile == null) {
            return null;
        }
        boolean killerTeam = !profile.innocent() && !profile.neutral()
                && !profile.neutralForKiller() && profile.canUseKiller();
        return classifyFlags(
                profile.innocent(),
                profile.canUseKiller(),
                killerTeam,
                profile.vigilanteTeam(),
                profile.mafiaTeam(),
                profile.neutral(),
                profile.neutralForKiller(),
                profile.neutralForInnocent());
    }

    /** Classifies raw role faction flags into a display bucket. */
    public static RoleFactionDisplay classifyFlags(
            boolean innocent,
            boolean canUseKiller,
            boolean killerTeam,
            boolean vigilanteTeam,
            boolean mafiaTeam,
            boolean neutrals,
            boolean neutralForKiller,
            boolean neutralForInnocent) {
        if (vigilanteTeam && !canUseKiller) {
            return VIGILANTE;
        }
        if (innocent && !canUseKiller) {
            return INNOCENT;
        }
        if (mafiaTeam && !canUseKiller) {
            return MAFIA;
        }
        if (neutralForKiller && !canUseKiller) {
            return NEUTRAL_FOR_KILLER;
        }
        if (canUseKiller || killerTeam) {
            return KILLER;
        }
        if (neutralForInnocent) {
            return NEUTRAL_FOR_INNOCENT;
        }
        return NEUTRAL;
    }
}
