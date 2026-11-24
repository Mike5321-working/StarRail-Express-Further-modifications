package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.replay.GlobalMatchSoundRecorder;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ReplayDirectSoundPacketMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"))
    private void habitrain$captureDirectSoundPacket(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        if ((packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEventPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundLevelEventPacket || packet instanceof net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket) && (Object) this instanceof ServerGamePacketListenerImpl game) {
            com.habitrain.core.game.sre.replay.MatchReplayRecorder.visualPacket(game.player, packet);
        }
        if ((packet instanceof ClientboundSoundPacket || packet instanceof ClientboundSoundEntityPacket)
                && !GlobalMatchSoundRecorder.isSuppressingDirectPackets()
                && (Object) this instanceof ServerGamePacketListenerImpl game) {
            GlobalMatchSoundRecorder.onDirectSoundPacket(game.player, packet);
        }
    }
}
