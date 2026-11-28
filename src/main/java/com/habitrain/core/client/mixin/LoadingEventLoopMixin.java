package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiLoadingFramePumper;
import com.habitrain.core.client.title.HabiLoadingPerfMonitor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smooths client loading screens without touching server loops or live gameplay:
 * <ul>
 *   <li>Time-slices {@link BlockableEventLoop#runAllTasks()} to ~10 ms/frame (always >=1 task)
 *       during {@code StarRailLoadingOverlay} and {@code HabiWorldEntrySession} loading screens.</li>
 *   <li>Pumps render-only frames (~60 Hz) during {@link BlockableEventLoop#managedBlock} while
 *       singleplayer datapacks load in {@code WorldOpenFlows}.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
@Mixin(BlockableEventLoop.class)
public abstract class LoadingEventLoopMixin {

    @Unique private long habitrain$sliceBudgetNanos;
    @Unique private long habitrain$sliceStartNanos;
    @Unique private int habitrain$sliceTasksRun;
    @Unique private final long[] habitrain$taskStartStack = new long[8];
    @Unique private int habitrain$taskDepth;

    @Inject(method = "doRunTask", at = @At("HEAD"))
    private void habitrain$beginProfileTask(Runnable task, CallbackInfo ci) {
        boolean active = HabiLoadingPerfMonitor.getInstance().hasAnyActiveSession();
        if (!active && this.habitrain$taskDepth == 0) return;
        if (!((Object) this instanceof Minecraft)) return;
        int d = this.habitrain$taskDepth++;
        if (d < this.habitrain$taskStartStack.length) {
            this.habitrain$taskStartStack[d] = active ? System.nanoTime() : 0L;
        }
    }

    @Inject(method = "doRunTask", at = @At("RETURN"))
    private void habitrain$endProfileTask(Runnable task, CallbackInfo ci) {
        if (this.habitrain$taskDepth <= 0) return;
        int d = --this.habitrain$taskDepth;
        if (d < this.habitrain$taskStartStack.length) {
            long start = this.habitrain$taskStartStack[d];
            if (start > 0L) {
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                if (ms >= 40.0 && task != null) {
                    HabiLoadingPerfMonitor.getInstance().recordSlowTask(task.getClass().getName(), ms);
                }
            }
        }
    }

    @Inject(method = "runAllTasks", at = @At("HEAD"))
    private void habitrain$beginRunAllTasksSlice(CallbackInfo ci) {
        long budget = HabiLoadingFramePumper.getTaskSliceBudgetNanos((BlockableEventLoop<?>) (Object) this);
        this.habitrain$sliceBudgetNanos = budget;
        if (budget > 0L) {
            this.habitrain$sliceStartNanos = System.nanoTime();
            this.habitrain$sliceTasksRun = 0;
        }
    }

    @Inject(method = "runAllTasks", at = @At("RETURN"))
    private void habitrain$endRunAllTasksSlice(CallbackInfo ci) {
        this.habitrain$sliceBudgetNanos = 0L;
        this.habitrain$sliceTasksRun = 0;
    }

    @Redirect(
            method = "runAllTasks",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/BlockableEventLoop;pollTask()Z")
    )
    private boolean habitrain$pollTaskWithinLoadingBudget(BlockableEventLoop<?> self) {
        long budget = this.habitrain$sliceBudgetNanos;
        if (budget > 0L && this.habitrain$sliceTasksRun > 0) {
            if ((System.nanoTime() - this.habitrain$sliceStartNanos) >= budget) {
                HabiLoadingFramePumper.noteTaskSliceYielded(self);
                return false;
            }
        }
        boolean ran = self.pollTask();
        if (ran && budget > 0L) {
            this.habitrain$sliceTasksRun++;
        }
        return ran;
    }

    @Redirect(
            method = "managedBlock",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/BlockableEventLoop;pollTask()Z")
    )
    private boolean habitrain$pollTaskAndPumpDuringManagedBlock(BlockableEventLoop<?> self) {
        HabiLoadingFramePumper.maybePumpManagedBlockFrame(self);
        return self.pollTask();
    }

    @Redirect(
            method = "managedBlock",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/BlockableEventLoop;waitForTasks()V")
    )
    private void habitrain$waitOrPumpDuringManagedBlock(BlockableEventLoop<?> self) {
        if (HabiLoadingFramePumper.maybePumpManagedBlockFrame(self)) {
            return;
        }
        self.waitForTasks();
    }
}
