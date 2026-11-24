package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayExportClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class ReplayExportRenderMixin implements com.habitrain.core.scene.client.replay.ReplayForegroundWorld.RendererFields {
    @org.spongepowered.asm.mixin.Mutable @org.spongepowered.asm.mixin.gen.Accessor("mainCamera") public abstract void habitrain$camera(net.minecraft.client.Camera camera);
    @org.spongepowered.asm.mixin.Mutable @org.spongepowered.asm.mixin.gen.Accessor("itemInHandRenderer") public abstract void habitrain$hands(net.minecraft.client.renderer.ItemInHandRenderer hands);
    @org.spongepowered.asm.mixin.gen.Accessor("fov") public abstract float habitrain$fov();
    @org.spongepowered.asm.mixin.gen.Accessor("oldFov") public abstract float habitrain$oldFov();
    @org.spongepowered.asm.mixin.gen.Accessor("fov") public abstract void habitrain$fov(float fov);
    @org.spongepowered.asm.mixin.gen.Accessor("oldFov") public abstract void habitrain$oldFov(float fov);

    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "getMainCamera", at = @At("RETURN"))
    private net.minecraft.client.Camera habitrain$audioListener(net.minecraft.client.Camera camera) {
        return com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread() ? camera
                : com.habitrain.core.scene.client.replay.ReplayForegroundWorld.voiceCamera(camera);
    }

    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private double habitrain$replayLens(double value, net.minecraft.client.Camera camera, float partial, boolean worldLens) {
        return com.habitrain.core.scene.client.replay.ReplayVisualClient.fov(value, worldLens);
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void habitrain$world(GameRenderer renderer, net.minecraft.client.DeltaTracker clock,
                                com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        ReplayExportClient.drawWorld(Minecraft.getInstance(), clock, playbackClock -> original.call(renderer, playbackClock));
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", ordinal = 0))
    private void habitrain$beforeReplayHud(CallbackInfo ci) { ReplayExportClient.beforeGui(Minecraft.getInstance()); }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
    private boolean habitrain$liveHud(net.minecraft.client.gui.Gui gui, net.minecraft.client.gui.GuiGraphics graphics, net.minecraft.client.DeltaTracker clock) {
        return !ReplayExportClient.foreground();
    }
    // Draw after the game's GUI buffers are flushed, while its GUI projection is still active.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"))
    private void habitrain$renderReplayExport(CallbackInfo ci) {
        ReplayExportClient.renderFrame(Minecraft.getInstance());
    }
}
