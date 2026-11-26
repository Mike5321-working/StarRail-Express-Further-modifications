package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleNameRenderRule;
import com.habitrain.core.api.role.v2.client.RoleRenderPhase;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Physical-client adapter for v2 {@link RoleNameRenderRule} (audit P1-2 / P1-5).
 *
 * <p>Currently supports the {@link RoleRenderPhase#NAMEPLATE} phase: hides a
 * role's nameplate when the rule says {@code hide()}, and applies the rule
 * color by re-styling the vanilla nameplate component. The mixin in
 * {@code client.mixin.EntityRendererMixin} consumes this helper.
 */
@Environment(EnvType.CLIENT)
public final class RoleNameRenderHelper {

    private RoleNameRenderHelper() {}

    private static final java.util.Map<RoleKey, java.util.Optional<RoleNameRenderRule>> RULE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long cachedRegistryRevision = -1L;
    private static volatile @Nullable Object cachedSnapshotRef;
    private static volatile @Nullable Object cachedPayloadRef;

    public static void clearCache() {
        RULE_CACHE.clear();
        cachedRegistryRevision = -1L;
        cachedSnapshotRef = null;
        cachedPayloadRef = null;
    }

    public static @Nullable RoleNameRenderRule findNameplateRule(Player player) {
        if (player == null || player.level() == null || !player.level().isClientSide) {
            return null;
        }
        RoleClientExtensionApi api = RoleClientExtensionApi.instance();
        long rev = -1L;
        if (api instanceof com.habitrain.core.role.client.RoleClientExtensionRegistry reg) {
            if (!reg.hasNameRenders()) {
                return null;
            }
            rev = reg.revision();
        }
        RoleKey currentRole = RoleClientExtensionHooks.currentRole(player);
        if (currentRole == null) {
            return null;
        }
        Object snap = com.habitrain.core.role.snapshot.RoleSnapshotManager.INSTANCE.current();
        Object payload = null;
        try {
            payload = RoleSnapshotState.INSTANCE.get();
        } catch (Throwable ignored) {
        }
        if (rev != cachedRegistryRevision || snap != cachedSnapshotRef || payload != cachedPayloadRef) {
            RULE_CACHE.clear();
            cachedRegistryRevision = rev;
            cachedSnapshotRef = snap;
            cachedPayloadRef = payload;
        }
        java.util.Optional<RoleNameRenderRule> cached = RULE_CACHE.get(currentRole);
        if (cached != null) {
            return cached.orElse(null);
        }
        RoleNameRenderRule computed = computeNameplateRule(api, currentRole);
        RULE_CACHE.put(currentRole, java.util.Optional.ofNullable(computed));
        return computed;
    }

    private static @Nullable RoleNameRenderRule computeNameplateRule(RoleClientExtensionApi api, @Nullable RoleKey role) {
        if (role == null) {
            return null;
        }
        for (RoleNameRenderRule rule : api.nameRendersFor(role)) {
            if (rule.phase() == RoleRenderPhase.NAMEPLATE) {
                return rule;
            }
        }
        return null;
    }

    /** Whether the rule wants the nameplate completely hidden. */
    public static boolean shouldHide(@Nullable RoleNameRenderRule rule) {
        return rule != null && rule.hide();
    }

    /**
     * Applies the rule's color to the vanilla nameplate component. Returns the
     * original component when there is no color override.
     */
    public static Component applyColor(@Nullable RoleNameRenderRule rule, Component original) {
        if (rule == null || rule.color() == null || original == null) {
            return original;
        }
        MutableComponent copy = original.copy();
        return copy.setStyle(copy.getStyle().withColor(rule.color()));
    }
}
