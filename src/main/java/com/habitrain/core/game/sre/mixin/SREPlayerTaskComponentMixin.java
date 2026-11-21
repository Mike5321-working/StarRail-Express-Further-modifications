package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.sre.task.PerPlayerTaskTicker;
import com.habitrain.core.task.TaskManager;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SREPlayerTaskComponent.class)
public abstract class SREPlayerTaskComponentMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("SREPlayerTaskComponentMixin");

    @Shadow(remap = false)
    public abstract Player getPlayer();

    @Inject(
            method = "init",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onInit(CallbackInfo ci) {
        clearTrackedTasks(getPlayer(), "init");
    }

    @Inject(
            method = "clear",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onClear(CallbackInfo ci) {
        clearTrackedTasks(getPlayer(), "clear");
    }

    /** 清理该玩家的活跃任务，避免旧实例继续 tick/发奖。 */
    private static void clearTrackedTasks(Player p, String reason) {
        if (p == null) return;
        TaskManager mgr = TaskManager.getInstance();
        TaskInstance oldTask = mgr.getActiveTask(p.getUUID());
        if (oldTask != null) {
            LOGGER.debug("[HabiDebug] {}() - clearing activeCustomTask {} for player {}",
                    reason, oldTask.getFullId(), p.getName().getString());
        }
        mgr.cancelAllTrackedTasks(p);
    }

    @Inject(
            method = "serverTick",
            at = @At("HEAD"),
            remap = false
    )
    private void habitrain$onServerTick(CallbackInfo ci) {
        Player player = getPlayer();
        if (player == null) return;

        PerPlayerTaskTicker.tick(player);
    }

}
