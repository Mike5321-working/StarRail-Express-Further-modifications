package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.HabiBoundItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class BoundItemDropMixin {
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void habitrain$keepBoundSelectedItem(boolean entire, CallbackInfoReturnable<Boolean> cir) {
        if (HabiBoundItems.isBoundNoDrop(((ServerPlayer) (Object) this).getInventory().getSelected())) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$neverDropBoundItem(ItemStack stack, boolean random, boolean retain, CallbackInfoReturnable<ItemEntity> cir) {
        if (HabiBoundItems.isBoundNoDrop(stack)) {
            cir.setReturnValue(null);
        }
    }
}
