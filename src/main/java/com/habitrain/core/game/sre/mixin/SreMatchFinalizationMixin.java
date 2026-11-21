package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.match.MatchEventBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameUtils.class, remap = false)
public abstract class SreMatchFinalizationMixin {
    @WrapMethod(method = "finalizeGame")
    private static void habitrain$finalizeNativeRound(ServerLevel level, Operation<Void> original) {
        MatchEventBridge.finalizeNativeRound(level, () -> original.call(level));
    }

    @Inject(method = "finalizeGame", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lio/wifi/starrailexpress/api/GameMode;recordWinStats(Lnet/minecraft/server/level/ServerLevel;Lio/wifi/starrailexpress/cca/SREGameRoundEndComponent;Lio/wifi/starrailexpress/cca/SREGameWorldComponent;)V"))
    private static void habitrain$afterRecordWinStats(ServerLevel level, CallbackInfo callback) {
        MatchEventBridge.onAfterRecordWinStats(level);
    }
}
