package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.drone.DroneRules;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

@Mixin(Inventory.class)
public abstract class DroneBatteryInventoryMixin {
    @Shadow @Final public Player player;

    @WrapMethod(method = "add(ILnet/minecraft/world/item/ItemStack;)Z")
    private boolean habitrain$capDroneBatteryInsert(int slot, ItemStack stack, Operation<Boolean> original) {
        if (!(player instanceof ServerPlayer) || !HabiRoleItems.isDroneBattery(stack)) {
            return original.call(slot, stack);
        }
        // The carried stack may itself be returned by vanilla when closing a menu.
        int held = HabiRoleItems.countDroneBatteries(player);
        if (player.containerMenu.getCarried() == stack) held -= stack.getCount();
        int room = DroneRules.spareBatterySpace(held);
        if (room == 0) return false;
        if (room >= stack.getCount()) return original.call(slot, stack);

        ItemStack portion = stack.copyWithCount(room);
        boolean added = original.call(slot, portion);
        stack.shrink(room - portion.getCount());
        return added;
    }
}
