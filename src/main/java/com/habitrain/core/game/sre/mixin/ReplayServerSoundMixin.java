package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.replay.GlobalMatchSoundRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ReplayServerSoundMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void habitrain$capturePositionalSoundHead(@Nullable Player except, double x, double y, double z,
                                                      Holder<SoundEvent> sound, SoundSource source,
                                                      float volume, float pitch, long seed, CallbackInfo ci) {
        GlobalMatchSoundRecorder.enterLevelOrNotifySound();
        GlobalMatchSoundRecorder.onPlaySeededSound((ServerLevel) (Object) this, except, x, y, z, sound, source, volume, pitch);
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("RETURN"))
    private void habitrain$capturePositionalSoundReturn(@Nullable Player except, double x, double y, double z,
                                                        Holder<SoundEvent> sound, SoundSource source,
                                                        float volume, float pitch, long seed, CallbackInfo ci) {
        GlobalMatchSoundRecorder.exitLevelOrNotifySound();
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void habitrain$captureEntitySoundHead(@Nullable Player except, Entity entity,
                                                  Holder<SoundEvent> sound, SoundSource source,
                                                  float volume, float pitch, long seed, CallbackInfo ci) {
        GlobalMatchSoundRecorder.enterLevelOrNotifySound();
        GlobalMatchSoundRecorder.onPlaySeededEntitySound((ServerLevel) (Object) this, except, entity, sound, source, volume, pitch);
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("RETURN"))
    private void habitrain$captureEntitySoundReturn(@Nullable Player except, Entity entity,
                                                    Holder<SoundEvent> sound, SoundSource source,
                                                    float volume, float pitch, long seed, CallbackInfo ci) {
        GlobalMatchSoundRecorder.exitLevelOrNotifySound();
    }

    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void habitrain$captureLevelEvent(@Nullable Player except, int type, BlockPos pos, int data, CallbackInfo ci) {
        GlobalMatchSoundRecorder.onLevelEvent((ServerLevel) (Object) this, except, type, pos, data);
    }
}
