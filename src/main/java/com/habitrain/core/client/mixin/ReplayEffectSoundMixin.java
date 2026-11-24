package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayForegroundWorld;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
abstract class ReplayEffectSoundMixin {
    @Inject(method={"play", "playDelayed"},at=@At("HEAD"),cancellable=true)
    private void habitrain$muteReplaySimulation(CallbackInfo ci) {
        if (ReplayForegroundWorld.rendering() && com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) ci.cancel();
    }
}
