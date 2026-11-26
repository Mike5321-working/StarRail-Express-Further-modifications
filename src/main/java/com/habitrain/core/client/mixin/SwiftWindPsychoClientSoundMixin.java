package com.habitrain.core.client.mixin;

import com.habitrain.core.client.audio.SwiftWindPsychoAudioBoost;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Plays Swift Wind's frenzy cue without positional attenuation or directional falloff so every
 * player across the map hears it at full volume even while moving or dashing.
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientPacketListener.class)
public abstract class SwiftWindPsychoClientSoundMixin {

    @WrapOperation(
            method = "handleSoundEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V"
            )
    )
    private void habitrain$playSwiftWindPsychoNonPositional(ClientLevel level,
                                                            Player player,
                                                            double x,
                                                            double y,
                                                            double z,
                                                            Holder<SoundEvent> sound,
                                                            SoundSource source,
                                                            float volume,
                                                            float pitch,
                                                            long seed,
                                                            Operation<Void> original) {
        if (sound != null && SwiftWindPsychoAudioBoost.SOUND_ID.equals(sound.value().getLocation())) {
            Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                    SwiftWindPsychoAudioBoost.SOUND_ID,
                    source,
                    volume,
                    pitch,
                    RandomSource.create(seed),
                    false,
                    0,
                    SoundInstance.Attenuation.NONE,
                    0.0,
                    0.0,
                    0.0,
                    true
            ));
            return;
        }
        original.call(level, player, x, y, z, sound, source, volume, pitch, seed);
    }
}
