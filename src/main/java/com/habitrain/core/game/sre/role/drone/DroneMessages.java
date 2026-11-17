package com.habitrain.core.game.sre.role.drone;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Formats {@code message.habitrain_core.drone.*} components with uniform
 * {@link ChatFormatting} styles and sanitized interpolation arguments so
 * placeholders never split legacy {@code §} colors or bleed formatting.
 */
public final class DroneMessages {

    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)\\u00A7[0-9A-FK-ORX]");

    private static final Map<String, ChatFormatting> KEY_COLORS = Map.ofEntries(
            Map.entry("message.habitrain_core.drone.resupplied", ChatFormatting.AQUA),
            Map.entry("message.habitrain_core.drone.resupplied_scrapped_old", ChatFormatting.AQUA),
            Map.entry("message.habitrain_core.drone.battery_already_full", ChatFormatting.AQUA),
            Map.entry("message.habitrain_core.drone.mounted", ChatFormatting.AQUA),
            Map.entry("message.habitrain_core.drone.ally_mounted", ChatFormatting.AQUA),

            Map.entry("message.habitrain_core.drone.hand_recharged", ChatFormatting.GREEN),
            Map.entry("message.habitrain_core.drone.swap_complete", ChatFormatting.GREEN),
            Map.entry("message.habitrain_core.drone.shot_down_confirm", ChatFormatting.GREEN),

            Map.entry("message.habitrain_core.drone.silenced", ChatFormatting.DARK_PURPLE),

            Map.entry("message.habitrain_core.drone.charging", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.low_power_payload", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.netted", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.body_hit_exit", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.scrapped_unrecoverable", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.destroyed_need_buy", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.zero_battery_stowed", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.no_spare_battery", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.unloaded_destroyed", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.ally_unloaded_destroyed", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.low_battery_no_scan", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.lethal_overload_crashed", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.zero_crash_landed", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.sympathetic_boom", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.destroyed_alert", ChatFormatting.RED),
            Map.entry("message.habitrain_core.drone.shop_battery_full", ChatFormatting.RED)
    );

    private DroneMessages() {}

    public static String stripCodes(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return LEGACY_CODE.matcher(text).replaceAll("");
    }

    /**
     * Returns an unstyled deep copy of {@code component}, preserving translation keys so
     * clients can still localize item names while preventing embedded styles or legacy
     * {@code §} codes from overriding the enclosing prompt color.
     */
    public static Component unstyledCopy(@Nullable Component component) {
        if (component == null) {
            return Component.empty();
        }
        ComponentContents contents = component.getContents();
        MutableComponent copy;
        if (contents instanceof TranslatableContents translatable) {
            Object[] rawArgs = translatable.getArgs();
            Object[] cleanArgs = new Object[rawArgs.length];
            for (int i = 0; i < rawArgs.length; i++) {
                cleanArgs[i] = sanitizeArg(rawArgs[i]);
            }
            String fallback = translatable.getFallback();
            copy = fallback != null
                    ? Component.translatableWithFallback(translatable.getKey(), stripCodes(fallback), cleanArgs)
                    : Component.translatable(translatable.getKey(), cleanArgs);
        } else if (contents instanceof PlainTextContents plain) {
            copy = Component.literal(stripCodes(plain.text()));
        } else {
            copy = MutableComponent.create(contents);
        }
        for (Component sibling : component.getSiblings()) {
            copy.append(unstyledCopy(sibling));
        }
        return copy;
    }

    /**
     * Captures an item stack's display name before inventory insertion mutates the stack.
     */
    public static Component captureItemName(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Component.empty();
        }
        return unstyledCopy(stack.getHoverName());
    }

    /**
     * Transfers an unloaded payload stack to the receiver's inventory (or drops overflow)
     * while returning the item's hover name captured prior to {@code Inventory.add} mutation.
     */
    public static Component transferUnloadedPayload(
            ItemStack mountedStack,
            java.util.function.Predicate<ItemStack> inventoryAdder,
            java.util.function.Consumer<ItemStack> dropOverflow
    ) {
        ItemStack returned = mountedStack.copy();
        Component itemName = captureItemName(returned);
        if (!inventoryAdder.test(returned) && !returned.isEmpty()) {
            dropOverflow.accept(returned);
        }
        return itemName;
    }

    public static ChatFormatting colorForKey(@Nullable String key) {
        if (key == null) {
            return ChatFormatting.YELLOW;
        }
        return KEY_COLORS.getOrDefault(key, ChatFormatting.YELLOW);
    }

    public static MutableComponent create(String key, Object... args) {
        if (args == null || args.length == 0) {
            return Component.translatable(key).withStyle(colorForKey(key));
        }
        Object[] sanitized = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            sanitized[i] = sanitizeArg(args[i]);
        }
        return Component.translatable(key, sanitized).withStyle(colorForKey(key));
    }

    private static Object sanitizeArg(@Nullable Object arg) {
        if (arg == null) {
            return "";
        }
        if (arg instanceof Component comp) {
            return unstyledCopy(comp);
        }
        if (arg instanceof String str) {
            return stripCodes(str);
        }
        return arg;
    }
}
