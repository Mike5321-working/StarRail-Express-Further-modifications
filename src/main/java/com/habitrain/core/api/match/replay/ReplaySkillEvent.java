package com.habitrain.core.api.match.replay;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Structured skill, item, or generic third-party mod ability event captured on the server.
 *
 * <p>Serves both dedicated mod adapters (SRE / habitrain_core / noellesroles / stupid_express / TACZ)
 * and the three-tier fallback pipeline ({@code fallback == true}) for unknown roles, items, or third-party
 * mod abilities.</p>
 */
public record ReplaySkillEvent(
        int eventId,
        int tick,
        String skillOrItemId,
        String displayName,
        String category,
        @Nullable UUID actorUuid,
        @Nullable UUID targetUuid,
        String actorRoleId,
        int accentColor,
        @Nullable LastMatchReplay.Position from,
        @Nullable LastMatchReplay.Position to,
        int durationTicks,
        String soundId,
        boolean fallback
) {
    public static final int MAX_SKILL_EVENTS = 1024;
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_CATEGORY_LENGTH = 32;

    public static final String CAT_ROLE_SKILL = "role_skill";
    public static final String CAT_ITEM_USE   = "item_use";
    public static final String CAT_EFFECT     = "effect";
    public static final String CAT_CUSTOM     = "custom";

    public ReplaySkillEvent {
        if (tick < 0 || tick > LastMatchReplay.MAX_TICKS) {
            throw new IllegalArgumentException("Invalid skill event tick: " + tick);
        }
        skillOrItemId = trim(skillOrItemId, MAX_ID_LENGTH);
        displayName = trim(displayName, MAX_NAME_LENGTH);
        category = trim(category, MAX_CATEGORY_LENGTH);
        actorRoleId = trim(actorRoleId, MAX_ID_LENGTH);
        soundId = trim(soundId, MAX_ID_LENGTH);
        durationTicks = Math.clamp(durationTicks, 0, LastMatchReplay.MAX_TICKS);
    }

    public int endTick() {
        return tick + Math.max(1, durationTicks);
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
