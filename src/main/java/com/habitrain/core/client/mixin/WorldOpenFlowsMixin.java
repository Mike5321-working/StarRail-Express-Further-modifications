package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiLoadingFramePumper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.server.WorldLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Offloads {@link WorldLoader#load}'s synchronous datapack JAR scan and worldgen/dimension
 * registry JSON parsing onto {@code Util.backgroundExecutor()} while a HabiTrain world-entry
 * cutscene is active, so {@code minecraft.managedBlock(future::isDone)} enters immediately and
 * pumps smooth 60 FPS frames instead of freezing the render thread for ~800 ms.
 */
@Environment(EnvType.CLIENT)
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {

    @Shadow @Final private Minecraft minecraft;

    @Redirect(
            method = "loadWorldDataBlocking",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/WorldLoader;load(Lnet/minecraft/server/WorldLoader$InitConfig;Lnet/minecraft/server/WorldLoader$WorldDataSupplier;Lnet/minecraft/server/WorldLoader$ResultFactory;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private <D, R> CompletableFuture<R> habitrain$offloadWorldLoaderPreamble(
            WorldLoader.InitConfig initConfig,
            WorldLoader.WorldDataSupplier<D> worldDataSupplier,
            WorldLoader.ResultFactory<D, R> resultFactory,
            Executor backgroundExecutor,
            Executor mainThreadExecutor
    ) {
        if (!HabiLoadingFramePumper.shouldOffloadWorldLoader(this.minecraft)) {
            return WorldLoader.load(initConfig, worldDataSupplier, resultFactory, backgroundExecutor, mainThreadExecutor);
        }
        return CompletableFuture.supplyAsync(
                () -> WorldLoader.load(initConfig, worldDataSupplier, resultFactory, backgroundExecutor, mainThreadExecutor),
                backgroundExecutor
        ).thenCompose(Function.identity());
    }
}
