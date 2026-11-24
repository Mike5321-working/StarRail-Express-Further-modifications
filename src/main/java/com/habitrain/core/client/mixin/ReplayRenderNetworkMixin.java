package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayForegroundWorld;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ReplayRenderNetworkMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void habitrain$localOnly(Packet<?> packet, CallbackInfo callback) {
        if (ReplayForegroundWorld.rendering() && RenderSystem.isOnRenderThread()) callback.cancel();
    }
}
