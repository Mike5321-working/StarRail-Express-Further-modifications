package com.habitrain.core.client.mixin;

import com.habitrain.core.client.audio.SwiftWindPsychoAudioBoost;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Applies {@link SwiftWindPsychoAudioBoost} once when caching Swift Wind's frenzy cue into OpenAL.
 */
@Environment(EnvType.CLIENT)
@Mixin(SoundBufferLibrary.class)
public abstract class SwiftWindPsychoSoundBufferMixin {

    @Shadow @Final private ResourceProvider resourceManager;
    @Shadow @Final private Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache;

    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
    private void habitrain$boostSwiftWindPsychoBuffer(ResourceLocation path,
                                                      CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        if (!SwiftWindPsychoAudioBoost.SOUND_FILE.equals(path)) {
            return;
        }
        cir.setReturnValue(this.cache.computeIfAbsent(path, loc -> CompletableFuture.supplyAsync(() -> {
            try (InputStream is = this.resourceManager.open(loc);
                 FiniteAudioStream stream = new JOrbisAudioStream(is)) {
                ByteBuffer pcm = stream.readAll();
                SwiftWindPsychoAudioBoost.EnhancedPcm enhanced =
                        SwiftWindPsychoAudioBoost.enhancePcm16(pcm, stream.getFormat());
                return new SoundBuffer(enhanced.data(), enhanced.format());
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool())));
    }
}
