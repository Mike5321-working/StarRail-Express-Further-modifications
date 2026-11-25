package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiReloadProgressAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes real per-listener preparation &amp; apply completion telemetry from Minecraft's
 * {@link SimpleReloadInstance} so the HabiTrain Kinetic loading manifest reflects actual reload work.
 */
@Environment(EnvType.CLIENT)
@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin implements HabiReloadProgressAccess {

    @Shadow @Final protected CompletableFuture<Unit> allPreparations;
    @Shadow @Final Set<PreparableReloadListener> preparingListeners;
    @Shadow @Final private int listenerCount;
    @Shadow private int startedReloads;
    @Shadow private int finishedReloads;
    @Shadow @Final private AtomicInteger startedTaskCounter;
    @Shadow @Final private AtomicInteger doneTaskCounter;

    @Unique
    private List<PreparableReloadListener> habitrain$allListeners = List.of();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void habitrain$captureListeners(
            Executor bgExecutor,
            Executor gameExecutor,
            ResourceManager resourceManager,
            List<PreparableReloadListener> listeners,
            @Coerce Object stateFactory,
            CompletableFuture<Unit> initialTask,
            CallbackInfo ci) {
        this.habitrain$allListeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    @Override
    public List<PreparableReloadListener> habitrain$getAllListeners() {
        return habitrain$allListeners;
    }

    @Override
    public Set<PreparableReloadListener> habitrain$getPendingPreparingListeners() {
        return preparingListeners;
    }

    @Override
    public boolean habitrain$isAllPreparationsDone() {
        return allPreparations != null && allPreparations.isDone();
    }

    @Override
    public int habitrain$getStartedReloads() {
        return startedReloads;
    }

    @Override
    public int habitrain$getFinishedReloads() {
        return finishedReloads;
    }

    @Override
    public int habitrain$getStartedTaskCount() {
        return startedTaskCounter != null ? startedTaskCounter.get() : 0;
    }

    @Override
    public int habitrain$getDoneTaskCount() {
        return doneTaskCounter != null ? doneTaskCounter.get() : 0;
    }

    @Override
    public int habitrain$getListenerCount() {
        return listenerCount;
    }
}
