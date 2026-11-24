package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayForegroundWorld;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.AudioChannel", remap = false)
abstract class ReplayVoiceChannelMixin {
    @ModifyExpressionValue(method = {"run", "writeToSpeaker"}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;", remap = true), require = 0)
    private ClientLevel habitrain$voiceLevel(ClientLevel level) { return ReplayForegroundWorld.voiceLevel(level); }

    @ModifyExpressionValue(method = {"run", "writeToSpeaker"}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;", remap = true), require = 0)
    private LocalPlayer habitrain$voicePlayer(LocalPlayer player) { return ReplayForegroundWorld.voicePlayer(player); }

    @ModifyExpressionValue(method = "writeToSpeaker", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;cameraEntity:Lnet/minecraft/world/entity/Entity;", remap = true), require = 0)
    private Entity habitrain$voiceEntity(Entity entity) { return ReplayForegroundWorld.voiceEntity(entity); }
}
