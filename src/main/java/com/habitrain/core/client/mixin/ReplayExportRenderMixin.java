package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayExportClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class ReplayExportRenderMixin {
    // Draw after the game's GUI buffers are flushed, while its GUI projection is still active.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"))
    private void habitrain$renderReplayExport(CallbackInfo ci) {
        ReplayExportClient.renderFrame(Minecraft.getInstance());
    }
}
