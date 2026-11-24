package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.match.replay.ReplayEntityTrack;
import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.agmas.noellesroles.content.entity.ServerSmokeAreaManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerSmokeAreaManager.class, remap = false)
abstract class ReplaySmokeAreaMixin {
    @Inject(method = "createSmokeArea", at = @At("HEAD"))
    private static void habitrain$captureSmokeZone(ServerLevel world, Vec3 position, double radius, int durationTicks, CallbackInfo ci) {
        MatchReplayRecorder.recordZone(world, ReplayEntityTrack.EntityKind.ZONE_SMOKE,
                "noellesroles:smoke_zone", null, position, (float) radius, durationTicks);
    }
}
