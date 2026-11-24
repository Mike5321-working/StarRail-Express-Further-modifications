package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.*;
import io.wifi.starrailexpress.client.SREClient;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.OptionalInt;

@Mixin(value=SREClient.class, remap=false)
abstract class ReplayInstinctMixin {
    @Inject(method="getLooseEndPenalty",at=@At("HEAD"),cancellable=true)
    private static void habitrain$recordedPenalty(CallbackInfoReturnable<Boolean> ci) {
        if (ReplayForegroundWorld.rendering()) ci.setReturnValue(ReplayForegroundWorld.looseEnd());
    }
    @Inject(method="getCachedInstinctHighlight",at=@At("HEAD"),cancellable=true)
    private static void habitrain$read(Entity target, CallbackInfoReturnable<OptionalInt> ci) {
        if (ReplayForegroundWorld.rendering()) ci.setReturnValue(ReplayForegroundWorld.outline(target));
    }
    @Inject(method="getCachedInstinctHighlight",at=@At("RETURN"))
    private static void habitrain$record(Entity target, CallbackInfoReturnable<OptionalInt> ci) { ReplayVisualClient.saw(target,ci.getReturnValue()); }
}
