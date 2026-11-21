package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.task.CustomTaskBlockScanner;
import io.wifi.starrailexpress.game.MapManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MapManager.class, remap = false)
public class MapManagerTaskIndexMixin {
    @Inject(method = "loadMap", at = @At("HEAD"), remap = false)
    private static void habitrain$invalidateTaskIndex(ServerLevel level, String mapName, CallbackInfoReturnable<Boolean> callback) {
        CustomTaskBlockScanner.beginMapLoad(level);
    }
}
