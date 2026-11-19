package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ClientKillSoundRecorder;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
abstract class ReplayKillSoundMixin {
    @Inject(method = "play", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/sounds/SoundEngine;instanceBySource:Lcom/google/common/collect/Multimap;"))
    private void habitrain$observeSound(SoundInstance sound, CallbackInfo callback,
                                       @Local(ordinal = 0) float rawVolume, @Local(ordinal = 1) float range,
                                       @Local(ordinal = 2) float volume, @Local(ordinal = 3) float pitch) {
        ClientKillSoundRecorder.observe(sound, sound.getSound().getPath(), range, rawVolume, volume, pitch);
    }
}
