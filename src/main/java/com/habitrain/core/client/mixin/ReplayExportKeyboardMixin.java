package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayExportClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class ReplayExportKeyboardMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique private boolean habitrain$cancelledExportOnEscape;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void habitrain$cancelBackgroundExport(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (key != GLFW.GLFW_KEY_ESCAPE || window != minecraft.getWindow().getWindow()) return;
        if (action == GLFW.GLFW_PRESS) {
            habitrain$cancelledExportOnEscape = ReplayExportClient.cancelInBackground(minecraft);
        } else if (action == GLFW.GLFW_RELEASE) {
            habitrain$cancelledExportOnEscape = false;
        }
        if (habitrain$cancelledExportOnEscape) ci.cancel();
    }
}
