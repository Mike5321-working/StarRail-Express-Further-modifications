package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiBootAudioEngine;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents vanilla Minecraft menu music from playing simultaneously on top of the
 * 100 BPM HabiTrain Kinetic boot &amp; Station 01 soundtrack.
 */
@Environment(EnvType.CLIENT)
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Nullable private SoundInstance currentMusic;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void habitrain$suppressVanillaTitleMusic(CallbackInfo ci) {
        if (!HabiBootAudioEngine.getInstance().isBlockingVanillaMusic()) return;
        if (this.currentMusic != null && this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().stop(this.currentMusic);
            this.currentMusic = null;
        }
        ci.cancel();
    }
}
