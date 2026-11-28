package com.habitrain.core.client.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.title.HabiBootAudioEngine;
import com.habitrain.core.client.title.HabiSoundEnginePrewarmAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.Listener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Options;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Allows HabiTrain Kinetic Boot UI to initialize Minecraft's native OpenAL {@link SoundEngine}
 * on boot frame 0 (using the user's configured {@code options.soundDevice}) instead of waiting
 * seconds for {@code SoundManager.apply()} at the end of resource reload, and preserves the
 * active boot soundtrack without interruption when that initial {@code SoundEngine.reload()} runs.
 */
@Environment(EnvType.CLIENT)
@Mixin(SoundEngine.class)
public abstract class SoundEnginePrewarmMixin implements HabiSoundEnginePrewarmAccess {

    @Shadow @Final private Options options;
    @Shadow @Final private Listener listener;
    @Shadow @Final private SoundBufferLibrary soundBuffers;
    @Shadow @Final private List<Sound> preloadQueue;
    @Shadow @Final private ChannelAccess channelAccess;
    @Shadow private boolean loaded;

    @Invoker("loadLibrary")
    protected abstract void habitrain$invokeLoadLibrary();

    @Unique private final Object habitrain$pumpLock = new Object();
    @Unique private volatile boolean habitrain$pumpingAllowed;
    @Unique private boolean habitrain$destroying;
    @Unique private boolean habitrain$earlyPrewarmed;
    @Unique private String habitrain$prewarmedDevice = "";
    @Unique private boolean habitrain$prewarmedDirectional;
    @Unique private boolean habitrain$skipCurrentReloadReinit;

    @Inject(method = "loadLibrary", at = @At("TAIL"))
    private void habitrain$enableKeepaliveAfterLoad(CallbackInfo ci) {
        boolean ready;
        synchronized (this.habitrain$pumpLock) {
            this.habitrain$pumpingAllowed = this.loaded;
            ready = this.loaded;
        }
        HabiBootAudioEngine.getInstance().onOpenAlContextReady(ready);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void habitrain$disableKeepaliveBeforeDestroy(CallbackInfo ci) {
        synchronized (this.habitrain$pumpLock) {
            this.habitrain$destroying = true;
            this.habitrain$pumpingAllowed = false;
        }
        HabiBootAudioEngine.getInstance().onOpenAlContextDestroying();
    }

    @Inject(method = "emergencyShutdown", at = @At("HEAD"))
    private void habitrain$releaseTunnelAudioBeforeEmergencyShutdown(CallbackInfo ci) {
        synchronized (this.habitrain$pumpLock) {
            this.habitrain$destroying = true;
            this.habitrain$pumpingAllowed = false;
        }
        HabiBootAudioEngine.getInstance().onOpenAlContextDestroying();
    }

    @Inject(method = "destroy", at = @At("TAIL"))
    private void habitrain$finishDestroyKeepaliveGuard(CallbackInfo ci) {
        synchronized (this.habitrain$pumpLock) {
            this.habitrain$destroying = false;
            this.habitrain$pumpingAllowed = this.loaded;
        }
    }

    @Inject(method = "stopAll", at = @At("HEAD"))
    private void habitrain$pauseKeepaliveBeforeStopAll(CallbackInfo ci) {
        synchronized (this.habitrain$pumpLock) {
            this.habitrain$pumpingAllowed = false;
        }
    }

    @Inject(method = "stopAll", at = @At("TAIL"))
    private void habitrain$resumeKeepaliveAfterStopAll(CallbackInfo ci) {
        synchronized (this.habitrain$pumpLock) {
            if (!this.habitrain$destroying) {
                this.habitrain$pumpingAllowed = this.loaded;
            }
        }
    }

    @Override
    public void habitrain$pumpOpenAlStreamTick() {
        synchronized (this.habitrain$pumpLock) {
            if (!this.loaded || !this.habitrain$pumpingAllowed || this.habitrain$destroying || this.channelAccess == null) {
                return;
            }
            this.channelAccess.scheduleTick();
        }
    }

    @Override
    public boolean habitrain$prewarmForKineticBoot() {
        if (this.loaded) {
            return true;
        }
        String device = this.options.soundDevice().get();
        boolean directional = this.options.directionalAudio().get();
        this.habitrain$invokeLoadLibrary();
        if (this.loaded) {
            this.habitrain$earlyPrewarmed = true;
            this.habitrain$prewarmedDevice = device == null ? "" : device;
            this.habitrain$prewarmedDirectional = directional;
            HabiTrainCore.LOGGER.info(
                    "[HabiBootAudio] Pre-warmed Minecraft OpenAL SoundEngine on boot frame 0 (device='{}')",
                    this.habitrain$prewarmedDevice.isEmpty() ? "<default>" : this.habitrain$prewarmedDevice);
        }
        return this.loaded;
    }

    @Override
    public boolean habitrain$isSoundEngineLoaded() {
        return this.loaded;
    }

    @WrapOperation(
            method = "reload",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundEngine;destroy()V")
    )
    private void habitrain$preservePrewarmedOpenAlOnInitialReload(SoundEngine instance, Operation<Void> original) {
        this.habitrain$skipCurrentReloadReinit = false;
        if (this.habitrain$earlyPrewarmed) {
            this.habitrain$earlyPrewarmed = false;
            String curDevice = this.options.soundDevice().get();
            String normCur = curDevice == null ? "" : curDevice;
            boolean curDirectional = this.options.directionalAudio().get();
            if (this.loaded
                    && normCur.equals(this.habitrain$prewarmedDevice)
                    && curDirectional == this.habitrain$prewarmedDirectional
                    && HabiBootAudioEngine.getInstance().isOpenAlStreamActive()) {
                this.habitrain$skipCurrentReloadReinit = true;
                this.soundBuffers.clear();
                return;
            }
        }
        original.call(instance);
    }

    @WrapOperation(
            method = "reload",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/SoundEngine;loadLibrary()V")
    )
    private void habitrain$finishPrewarmedInitialReload(SoundEngine instance, Operation<Void> original) {
        if (this.habitrain$skipCurrentReloadReinit) {
            this.habitrain$skipCurrentReloadReinit = false;
            this.listener.setGain(this.options.getSoundSourceVolume(SoundSource.MASTER));
            this.soundBuffers.preload(this.preloadQueue).thenRun(this.preloadQueue::clear);
            HabiTrainCore.LOGGER.info(
                    "[HabiBootAudio] Preserved pre-warmed OpenAL stream seamlessly across initial SoundManager reload");
            return;
        }
        original.call(instance);
    }
}
