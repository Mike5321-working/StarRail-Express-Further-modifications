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
        int delay = ReplayBoardButtons.handleUseWithDelay((Minecraft) (Object) this);
        if (delay >= 0) {
            // Holding right-click on the timeline rail scrubs smoothly (delay = 1);
            // discrete deck/sculpture plaques debounce (delay = 6) and swallow held-item use.
            this.rightClickDelay = delay;
            ci.cancel();
        }
    }
}
