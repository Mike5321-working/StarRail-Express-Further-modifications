package com.habitrain.core.client.mixin;

import com.habitrain.core.client.EliminatedRestPromptState;
import com.habitrain.core.client.gui.InventoryShopLayoutAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.Screen;
import org.agmas.noellesroles.client.RoleScreenHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The upstream helper gives all role hints the same center as the relocated hotbar. */
@Mixin(value = RoleScreenHelper.class, remap = false)
public abstract class RoleSelectionShopLayoutMixin {
    @Inject(method = "isRoleActive", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$suppressRoleWidgetsWhileResting(CallbackInfoReturnable<Boolean> cir) {
        if (EliminatedRestPromptState.isResting()) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(method = "onRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/screens/Screen;height:I", remap = true))
    private int habitrain$roleHintCenter(Screen screen, Operation<Integer> original) {
        return original.call(screen) + 2 * InventoryShopLayoutAccess.offset(screen);
    }
}
