package com.habitrain.core.client;

import com.habitrain.core.api.role.v2.RoleFactionDisplay;
import com.habitrain.core.game.sre.role.SreRoleResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.event.client.OnRenderRoleName;
import io.wifi.utils.client.betterrender.FakeGuiGraphics;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Renders the target player's auto-detected faction below the spectator
 * crosshair role name in {@link io.wifi.starrailexpress.client.gui.RoleNameRenderer}.
 */
@Environment(EnvType.CLIENT)
public final class SpectatorRoleFactionHud {
    private static boolean registered;

    private SpectatorRoleFactionHud() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        OnRenderRoleName.RENDER_PLAYER_EXTRA.register(SpectatorRoleFactionHud::renderExtra);
    }

    private static void renderExtra(Player self, Player target, FakeGuiGraphics context,
                                    DeltaTracker tickCounter, Font font) {
        if (self == null || target == null || context == null || font == null) {
            return;
        }
        if (SREClient.gameComponent == null
                || !SREClient.gameComponent.isRunning()
                || !SREClient.isPlayerSpectatingOrCreative()) {
            return;
        }
        SRERole rawRole = SREClient.gameComponent.getRole(target);
        if (rawRole == null) {
            return;
        }
        SRERole resolved = SreRoleResolver.resolve(rawRole);
        RoleFactionDisplay faction = RoleFactionDisplay.classify(resolved != null ? resolved : rawRole);
        if (faction == null) {
            return;
        }
        Component text = Component.translatable(faction.translationKey());
        context.pose().translate(0, 12, 0);
        context.drawString(font, text, -font.width(text) / 2, 0, faction.color());
    }
}
