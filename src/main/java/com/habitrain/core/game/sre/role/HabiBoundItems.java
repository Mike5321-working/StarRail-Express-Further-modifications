package com.habitrain.core.game.sre.role;

import com.habitrain.core.game.sre.role.idiot.IdiotEggItem;
import com.habitrain.core.game.sre.role.idiot.IdiotService;
import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Centralized bound-item policy for role-bound items so drop, external container,
 * vanilla bundle insertion, and theft protections stay consistent in one place.
 */
public final class HabiBoundItems {
    private HabiBoundItems() {}

    /**
     * Items that must never be dropped onto the ground or moved into external containers.
     */
    public static boolean isBoundNoDrop(@Nullable ItemStack stack) {
        return boundMessageKey(stack) != null;
    }

    /**
     * Returns the localization key to display when a bound item transfer/drop is blocked,
     * or {@code null} if the stack is not bound.
     */
    public static @Nullable String boundMessageKey(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (IdiotService.isEgg(stack)) {
            return "item.habitrain_core.idiot_egg.bound";
        }
        if (HabiRoleItems.isDroneTerminal(stack) || HabiRoleItems.isDroneBattery(stack)) {
            return "item.habitrain_core.drone_terminal.bound";
        }
        return null;
    }

    /**
     * Items that must never be placed inside any {@code BundleItem} (vanilla bundle or Greed pouch),
     * preventing carry-cap bypasses and ensuring {@code reclaim*} cleanup always finds them.
     */
    public static boolean isForbiddenInBundle(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() instanceof IdiotEggItem
                || IdiotService.isEgg(stack)
                || GreedPouchItem.isGreedPouch(stack)
                || HabiRoleItems.isDroneTerminal(stack)
                || HabiRoleItems.isDroneBattery(stack)
                || HabiRoleItems.isDroneFakeKnife(stack)
                || HabiRoleItems.isScapegoatKnife(stack);
    }

    /**
     * Items that cannot be stolen by Thief-type skills.
     */
    public static boolean isBoundNoSteal(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() instanceof IdiotEggItem
                || IdiotService.isEgg(stack)
                || HabiRoleItems.isDroneTerminal(stack)
                || HabiRoleItems.isDroneBattery(stack);
    }
}
