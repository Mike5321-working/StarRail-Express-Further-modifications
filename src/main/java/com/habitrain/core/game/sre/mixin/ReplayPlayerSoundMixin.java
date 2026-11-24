package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.replay.GlobalMatchSoundRecorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
abstract class ReplayPlayerSoundMixin {
    @Inject(method = "playNotifySound", at = @At("HEAD"))
    private void habitrain$captureNotifySoundHead(SoundEvent sound, SoundSource source, float volume, float pitch, CallbackInfo ci) {
        GlobalMatchSoundRecorder.enterLevelOrNotifySound();
        GlobalMatchSoundRecorder.onPlayNotifySound((ServerPlayer) (Object) this, sound, source, volume, pitch);
    }

    @Inject(method = "playNotifySound", at = @At("RETURN"))
    private void habitrain$captureNotifySoundReturn(SoundEvent sound, SoundSource source, float volume, float pitch, CallbackInfo ci) {
        GlobalMatchSoundRecorder.exitLevelOrNotifySound();
    }
}
