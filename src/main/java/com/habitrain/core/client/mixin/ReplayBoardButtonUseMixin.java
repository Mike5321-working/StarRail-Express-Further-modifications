package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayBoardButtons;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the replay table's floating "start playback" / "export video" plaques take the use-key click before vanilla
 * interacts with whatever block happens to be behind them (including the table itself).
 */
@Mixin(Minecraft.class)
public abstract class ReplayBoardButtonUseMixin {
    @Shadow
    private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void habitrain$replayBoardButtons(CallbackInfo ci) {
        if (ReplayBoardButtons.handleUse((Minecraft) (Object) this)) {
            // Holding the key must not re-trigger the plaque (or vanilla) every tick.
            this.rightClickDelay = 6;
            ci.cancel();
        }
    }
}
