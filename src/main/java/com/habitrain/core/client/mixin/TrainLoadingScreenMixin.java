package com.habitrain.core.client.mixin;

import com.habitrain.core.client.loading.HabiLoadingScreenTextures;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiKineticGfx;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiRealProgressTracker;
import com.mojang.blaze3d.systems.RenderSystem;
import net.exmo.sre.loading.FrameAnimationRenderer;
import net.exmo.sre.loading.TrainLoadingScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Unifies {@link TrainLoadingScreen} (world generation / spawn chunk loading) with the
 * HabiTrain Kinetic Flat 3D visual system using real {@link StoringChunkProgressListener} chunk counts.
 */
@Environment(EnvType.CLIENT)
@Mixin(TrainLoadingScreen.class)
public abstract class TrainLoadingScreenMixin extends Screen {

    @Shadow @Final private StoringChunkProgressListener progressListener;
    @Shadow @Final private long createdAt;
    @Shadow private long exitStart;

    @Unique private HabiRealProgressTracker habitrain$worldTracker;
    @Unique private long habitrain$startNanos;
    @Unique private long habitrain$lastFrameNanos;

    protected TrainLoadingScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void habitrain$onInit(CallbackInfo ci) {
        HabiLoadingScreenTextures.registerTextures(this.minecraft);
        HabiBootSession.getInstance().onLeaveTitleFlow();
        if (this.habitrain$worldTracker == null) {
            this.habitrain$worldTracker = new HabiRealProgressTracker(HabiRealProgressTracker.WORLD_STAGES);
            this.habitrain$startNanos = System.nanoTime();
            this.habitrain$lastFrameNanos = this.habitrain$startNanos;
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderKineticWorldGen(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return;

        long now = Util.getMillis();
        long nowNanos = System.nanoTime();
        if (this.habitrain$worldTracker == null) {
            this.habitrain$worldTracker = new HabiRealProgressTracker(HabiRealProgressTracker.WORLD_STAGES);
            this.habitrain$startNanos = nowNanos;
            this.habitrain$lastFrameNanos = nowNanos;
        }
        double dt = Math.min(0.1, Math.max(0.001, (nowNanos - this.habitrain$lastFrameNanos) / 1_000_000_000.0));
        this.habitrain$lastFrameNanos = nowNanos;
        double elapsedSec = Math.max(0.0, (nowNanos - this.habitrain$startNanos) / 1_000_000_000.0);

        double enterAlpha = HabiKineticGfx.sstep(0.0, 1.0, (now - this.createdAt) / 480.0);
        double exitAlpha = 1.0;
        double resolveT = 0.0;
        boolean resolving = this.exitStart >= 0L;
        if (resolving) {
            long since = now - this.exitStart;
            resolveT = HabiKineticGfx.sstep(0.0, 1.0, since / 550.0);
            if (since >= 550L) {
                long ex = since - 550L;
                exitAlpha = 1.0 - HabiKineticGfx.sstep(0.0, 1.0, ex / 480.0);
                if (ex >= 480L) {
                    this.onClose();
                    ci.cancel();
                    return;
                }
            }
        }

        HabiKineticUiController.getInstance().renderWorldGenerationScreen(
                this.minecraft, this.habitrain$worldTracker, this.progressListener,
                resolving, resolveT, elapsedSec, dt, enterAlpha * exitAlpha);
        ci.cancel();
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/exmo/sre/loading/FrameAnimationRenderer;hasFrames()Z")
    )
    private boolean habitrain$forceHasFrames(FrameAnimationRenderer instance) {
        return true;
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/exmo/sre/loading/FrameAnimationRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;IIFF)V"
            )
    )
    private void habitrain$renderWorldLoadingBackground(FrameAnimationRenderer instance, GuiGraphics g, int screenWidth, int screenHeight, float delta, float overallAlpha) {
        HabiLoadingScreenTextures.registerTextures(this.minecraft);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1.0F, 1.0F, 1.0F, overallAlpha);
        g.blit(HabiLoadingScreenTextures.WORLD_LOADING_TEXTURE, 0, 0, 0.0F, 0.0F, screenWidth, screenHeight, screenWidth, screenHeight);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }
}
