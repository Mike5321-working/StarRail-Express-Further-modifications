package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiLoadingFramePumper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pumps smooth Station 00 boot frames every ~14.5 ms inside {@link TextureAtlas#upload} while
 * huge modded block/item atlases (e.g. 8192x4096) upload thousands of sprites via
 * {@code uploadFirstFrame()}, immediately restoring {@code GL_TEXTURE0} and the atlas texture ID.
 * Uses non-conflicting {@code @Inject} and activates strictly during initial boot ({@code mc.level == null}).
 */
@Environment(EnvType.CLIENT)
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasUploadMixin extends AbstractTexture {

    @Unique private int habitrain$uploadSpriteCounter;

    @Inject(method = "upload", at = @At("HEAD"))
    private void habitrain$resetAtlasUploadPumpCounter(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        this.habitrain$uploadSpriteCounter = 0;
    }

    @Inject(
            method = "upload",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;uploadFirstFrame()V"
            )
    )
    private void habitrain$pumpBootFrameDuringAtlasUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if ((++this.habitrain$uploadSpriteCounter & 15) == 0) {
            HabiLoadingFramePumper.maybePumpBootAtlasFrame(this.getId());
        }
    }
}
