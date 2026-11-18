package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.wifi.starrailexpress.api.replay.GameReplayData;
import io.wifi.starrailexpress.api.replay.GameReplayManager;
import net.exmo.sre.record.MatchRecord;
import net.exmo.sre.record.MatchRecordService;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MatchRecordService.class, remap = false)
public final class ReplayArchiveMatchIdMixin {
    @WrapOperation(method = "recordFinishedMatch", at = @At(value = "INVOKE",
            target = "Lnet/exmo/sre/record/MatchRecordService;build(Lio/wifi/starrailexpress/api/replay/GameReplayManager;Lio/wifi/starrailexpress/api/replay/GameReplayData;Lnet/minecraft/core/RegistryAccess;)Lnet/exmo/sre/record/MatchRecord;"))
    private static MatchRecord habitrain$link(GameReplayManager manager, GameReplayData data, RegistryAccess registries,
                                              Operation<MatchRecord> original, ServerLevel level) {
        MatchRecord record = original.call(manager, data, registries);
        var id = MatchReplayRecorder.recordId(level);
        if (id != null && record != null) record.matchId = id.toString();
        return record;
    }
}
