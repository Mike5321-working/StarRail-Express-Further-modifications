package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayForegroundWorld;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.integration.freecam.FreecamUtil", remap = false)
abstract class ReplayVoiceDistanceMixin {
    @Inject(method = "getReferencePoint", at = @At("HEAD"), cancellable = true, require = 0)
    private static void habitrain$liveVoicePosition(CallbackInfoReturnable<Vec3> callback) {
        var position = ReplayForegroundWorld.liveVoicePosition();
        if (position != null) callback.setReturnValue(position);
    }
}
