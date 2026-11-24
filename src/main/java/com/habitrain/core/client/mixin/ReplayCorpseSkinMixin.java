package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayForegroundWorld;
import io.wifi.starrailexpress.client.util.ClientSkinCache;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;

@Mixin(value=ClientSkinCache.class,remap=false)
abstract class ReplayCorpseSkinMixin {
    @Inject(method="getCachedPlayerInfo",at=@At("HEAD"),cancellable=true)
    private static void habitrain$recordedSkin(UUID id, CallbackInfoReturnable<PlayerInfo> info) {
        if (!ReplayForegroundWorld.rendering()) return;
        var skin=ReplayForegroundWorld.bodySkin(id);
        if (skin!=null) info.setReturnValue(skin);
    }
}
