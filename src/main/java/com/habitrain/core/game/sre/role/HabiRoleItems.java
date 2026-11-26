package com.habitrain.core.game.sre.role;

import com.habitrain.core.game.sre.role.drone.DroneMessages;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * 用原版/上游物品 + CUSTOM_DATA 标记实现投稿道具，避免新物品注册。
 */
public final class HabiRoleItems {
    private HabiRoleItems() {}

    public static final String TAG_BOUQUET = "habitrain_bouquet";
    public static final String TAG_PEPPER_SPRAY = "habitrain_pepper_spray";
    public static final String TAG_LUST_INCENSE = "habitrain_lust_incense";
    public static final String TAG_RED_APPLE = "habitrain_red_apple";
    public static final String TAG_MELEE_IMMUNE_UNTIL = "habitrain_melee_immune_until";
    /** 凶案替罪羊被动发放的假刀标记，回收时只删带此 tag 的刀。 */
    public static final String TAG_SCAPEGOAT_KNIFE = "habitrain_scapegoat_knife";
    public static final String TAG_DRONE_TERMINAL = "habitrain_drone_terminal";
    public static final String TAG_DRONE_BATTERY = "habitrain_drone_battery";
    public static final String TAG_DRONE_RESUPPLY = "habitrain_drone_resupply";
    public static final String TAG_DRONE_FAKE_KNIFE = "habitrain_drone_fake_knife";

    public static final ResourceLocation ONCE_REVOLVER_ID = ResourceLocation.parse("noellesroles:once_revolver");
    public static final ResourceLocation THROWING_KNIFE_ID = ResourceLocation.parse("noellesroles:throwing_knife");
    public static final ResourceLocation SMOKE_GRENADE_ID = ResourceLocation.parse("noellesroles:smoke_grenade");
    public static final ResourceLocation FALLEN_LEAVES_ID = ResourceLocation.parse("yuushya:average_fallen_leaves");
    public static final ResourceLocation FAKE_KNIFE_ID = ResourceLocation.parse("noellesroles:fake_knife");

    public static ItemStack createBouquet(int count) {
        ItemStack stack = new ItemStack(Items.POPPY, Math.max(1, count));
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.bouquet"));
        putFlag(stack, TAG_BOUQUET, true);
        return stack;
    }

    public static ItemStack createPepperSpray() {
        ItemStack stack = new ItemStack(Items.HONEY_BOTTLE, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.pepper_spray"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("item.habitrain_core.pepper_spray.desc"),
                Component.translatable("item.habitrain_core.pepper_spray.cooldown")
        )));
        putFlag(stack, TAG_PEPPER_SPRAY, true);
        return stack;
    }

    public static ItemStack createRedApple() {
        ItemStack stack = new ItemStack(Items.APPLE);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.red_apple"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("item.habitrain_core.red_apple.desc"))));
        putFlag(stack, TAG_RED_APPLE, true);
        return stack;
    }

    public static boolean isRedApple(ItemStack stack) {
        return stack != null && stack.is(Items.APPLE) && hasFlag(stack, TAG_RED_APPLE);
    }

    public static ItemStack createLustIncense() {
        ItemStack stack = new ItemStack(Items.BLAZE_POWDER);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.lust_incense"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("item.habitrain_core.lust_incense.desc"))));
        putFlag(stack, TAG_LUST_INCENSE, true);
        return stack;
    }

    /** 凶案替罪羊被动：假刀（noellesroles:fake_knife）+ 回收标记。 */
    public static ItemStack createScapegoatKnife() {
        ItemStack stack = lookupItem(FAKE_KNIFE_ID, 1);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.scapegoat_knife"));
        putFlag(stack, TAG_SCAPEGOAT_KNIFE, true);
        return stack;
    }

    public static boolean isScapegoatKnife(ItemStack stack) {
        return hasFlag(stack, TAG_SCAPEGOAT_KNIFE);
    }

    /** 无人机操控者初始绑定装备：战术图传终端（不可丢弃）。 */
    public static ItemStack createDroneTerminal() {
        ItemStack stack = new ItemStack(Items.RECOVERY_COMPASS, 1);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.drone_terminal"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("item.habitrain_core.drone_terminal.desc"),
                Component.translatable("item.habitrain_core.drone_terminal.bound")
        )));
        putFlag(stack, TAG_DRONE_TERMINAL, true);
        return stack;
    }

    /** 无人机操控者消耗品：高能备用电池（堆叠与携带上限 3 块）。 */
    public static ItemStack createDroneBattery(int count) {
        int clamped = Math.max(1, Math.min(DroneRules.MAX_SPARE_BATTERIES, count));
        ItemStack stack = new ItemStack(Items.ECHO_SHARD, clamped);
        stack.set(DataComponents.MAX_STACK_SIZE, DroneRules.MAX_SPARE_BATTERIES);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.drone_battery"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("item.habitrain_core.drone_battery.desc"),
                Component.translatable("item.habitrain_core.drone_battery.limit")
        )));
        putFlag(stack, TAG_DRONE_BATTERY, true);
        return stack;
    }

    /** 专属商店展示图标：侦察无人机整机补给（150 金币，可随时购买，旧机直接报废不可回收）。 */
    public static ItemStack createDroneResupplyIcon() {
        ItemStack stack = new ItemStack(Items.RECOVERY_COMPASS, 1);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.drone_resupply"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("item.habitrain_core.drone_resupply.desc"),
                Component.translatable("item.habitrain_core.drone_resupply.rule")
        )));
        putFlag(stack, TAG_DRONE_RESUPPLY, true);
        return stack;
    }

    /** 专属商店欺诈道具：全息拟真假刀（带回收标记的 noellesroles:fake_knife）。 */
    public static ItemStack createDroneFakeKnife() {
        ItemStack stack = lookupItem(FAKE_KNIFE_ID, 1);
        if (stack.isEmpty()) {
            stack = new ItemStack(Items.IRON_SWORD, 1);
        }
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.habitrain_core.drone_fake_knife"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("item.habitrain_core.drone_fake_knife.desc")
        )));
        putFlag(stack, TAG_DRONE_FAKE_KNIFE, true);
        return stack;
    }

    public static boolean isDroneTerminal(ItemStack stack) {
        return stack != null && stack.is(Items.RECOVERY_COMPASS) && hasFlag(stack, TAG_DRONE_TERMINAL);
    }

    public static boolean isDroneBattery(ItemStack stack) {
        return stack != null && stack.is(Items.ECHO_SHARD) && hasFlag(stack, TAG_DRONE_BATTERY);
    }

    public static boolean isDroneFakeKnife(ItemStack stack) {
        return hasFlag(stack, TAG_DRONE_FAKE_KNIFE);
    }

    public static int countDroneBatteries(Player player) {
        if (player == null) return 0;
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isDroneBattery(stack)) {
                total += stack.getCount();
            }
        }
        if (player.containerMenu != null && isDroneBattery(player.containerMenu.getCarried())) {
            total += player.containerMenu.getCarried().getCount();
        }
        return total;
    }

    /** Reconciles menu and modded transfers that don't use Inventory.add. Excess bound batteries are trimmed directly. */
    public static boolean enforceDroneBatteryCap(net.minecraft.server.level.ServerPlayer player) {
        int excess = countDroneBatteries(player) - DroneRules.MAX_SPARE_BATTERIES;
        if (excess <= 0) return false;
        ItemStack carried = player.containerMenu.getCarried();
        if (isDroneBattery(carried)) excess -= trimExcessBatteries(carried, excess);
        for (int i = player.getInventory().getContainerSize() - 1; i >= 0 && excess > 0; i--) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isDroneBattery(stack)) excess -= trimExcessBatteries(stack, excess);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastFullState();
        player.displayClientMessage(DroneMessages.create("message.habitrain_core.drone.shop_battery_full"), true);
        return true;
    }

    private static int trimExcessBatteries(ItemStack stack, int excess) {
        int count = Math.min(stack.getCount(), excess);
        stack.shrink(count);
        return count;
    }

    public static boolean consumeDroneBattery(Player player) {
        if (player == null) return false;
        if (isDroneBattery(player.getMainHandItem())) {
            player.getMainHandItem().shrink(1);
            return true;
        }
        if (isDroneBattery(player.getOffhandItem())) {
            player.getOffhandItem().shrink(1);
            return true;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isDroneBattery(stack)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    public static boolean ensureDroneTerminal(Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isDroneTerminal(player.getInventory().getItem(i))) {
                return true;
            }
        }
        return player.getInventory().add(createDroneTerminal());
    }

    /** 清理操控者绑定终端、备用电池与专属拟真假刀。 */
    public static void reclaimDroneItems(Player player) {
        if (player == null) return;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isDroneTerminal(stack) || isDroneBattery(stack) || isDroneFakeKnife(stack)) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        if (player.containerMenu != null) {
            ItemStack carried = player.containerMenu.getCarried();
            if (isDroneTerminal(carried) || isDroneBattery(carried) || isDroneFakeKnife(carried)) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
            }
        }
    }

    /** 收回替罪羊被动发放的假刀（只删带 tag 的，含背包与鼠标游标）。 */
    public static void reclaimScapegoatKnives(Player player) {
        if (player == null) return;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isScapegoatKnife(stack)) {
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        if (player.containerMenu != null && isScapegoatKnife(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    public static boolean isBouquet(ItemStack stack) {
        return stack != null && stack.is(Items.POPPY) && hasFlag(stack, TAG_BOUQUET);
    }

    public static boolean isPepperSpray(ItemStack stack) {
        return stack != null && stack.is(Items.HONEY_BOTTLE) && hasFlag(stack, TAG_PEPPER_SPRAY);
    }

    public static boolean playerHasBouquet(net.minecraft.world.entity.player.Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isBouquet(player.getInventory().getItem(i))) return true;
        }
        return false;
    }

    public static boolean consumeBouquet(net.minecraft.world.entity.player.Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isBouquet(stack)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    public static ItemStack lookupItem(ResourceLocation id, int count) {
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, Math.max(1, count));
    }

    public static void putFlag(ItemStack stack, String key, boolean value) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean(key, value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @SuppressWarnings("deprecation")
    public static boolean hasFlag(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        return data.getUnsafe().getBoolean(key);
    }
}
