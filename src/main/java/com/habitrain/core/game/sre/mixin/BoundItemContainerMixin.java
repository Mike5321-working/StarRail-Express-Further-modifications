package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiBoundItems;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class BoundItemContainerMixin {
    @Shadow public abstract ItemStack getCarried();

    @Inject(method = "clicked", at = @At("RETURN"))
    private void habitrain$capTransferredDroneBatteries(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            HabiRoleItems.enforceDroneBatteryCap(serverPlayer);
        }
    }

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void habitrain$blockBoundItemExport(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        String carriedKey = HabiBoundItems.boundMessageKey(getCarried());
        Slot slot = slotId >= 0 && slotId < menu.slots.size() ? menu.slots.get(slotId) : null;
        String slotKey = slot != null ? HabiBoundItems.boundMessageKey(slot.getItem()) : null;
        boolean external = slot != null && slot.container != player.getInventory();
        String swappedKey = (type == ClickType.SWAP && (button >= 0 && button < 9 || button == 40))
                ? HabiBoundItems.boundMessageKey(player.getInventory().getItem(button))
                : null;

        String blockedKey = null;
        if (external) {
            if (carriedKey != null) blockedKey = carriedKey;
            else if (slotKey != null) blockedKey = slotKey;
            else if (swappedKey != null) blockedKey = swappedKey;
        }
        if (blockedKey == null && carriedKey != null && (slotId < 0 || type == ClickType.QUICK_CRAFT)) {
            blockedKey = carriedKey;
        }
        if (blockedKey == null && type == ClickType.THROW && slotKey != null) {
            blockedKey = slotKey;
        }
        if (blockedKey == null && type == ClickType.QUICK_MOVE && slotKey != null
                && menu.slots.stream().anyMatch(s -> s.container != player.getInventory())) {
            blockedKey = slotKey;
        }
        if (blockedKey == null) return;

        ci.cancel();
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(Component.translatable(blockedKey), true);
            serverPlayer.containerMenu.broadcastChanges();
        }
    }
}
