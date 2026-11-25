package com.habitrain.core.client.mixin;

import com.habitrain.core.client.loading.HabiLoadingScreenTextures;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiKineticGfx;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiRealProgressTracker;
import com.mojang.blaze3d.systems.RenderSystem;
import net.exmo.sre.loading.FrameAnimationRenderer;
import net.exmo.sre.loading.SREReceivingLevelScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Unifies {@link SREReceivingLevelScreen} (entering world / receiving chunks) with the
 * HabiTrain Kinetic Flat 3D visual system driven by real level-received state &amp; connection info.
 */
@Environment(EnvType.CLIENT)
@Mixin(SREReceivingLevelScreen.class)
public abstract class SREReceivingLevelScreenMixin extends Screen {

    @Shadow @Final private BooleanSupplier levelReceived;
    @Shadow @Final private long createdAt;
    @Shadow private long receivedAt;

    @Unique private HabiRealProgressTracker habitrain$recvTracker;
    @Unique private long habitrain$startNanos;
    @Unique private long habitrain$lastFrameNanos;

    protected SREReceivingLevelScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void habitrain$onInit(CallbackInfo ci) {
        HabiLoadingScreenTextures.registerTextures(this.minecraft);
        HabiBootSession.getInstance().onLeaveTitleFlow();
        if (this.habitrain$recvTracker == null) {
            this.habitrain$recvTracker = new HabiRealProgressTracker(HabiRealProgressTracker.WORLD_STAGES);
            this.habitrain$startNanos = System.nanoTime();
            this.habitrain$lastFrameNanos = this.habitrain$startNanos;
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderKineticReceiving(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return;

        long now = Util.getMillis();
        long nowNanos = System.nanoTime();
        if (this.habitrain$recvTracker == null) {
            this.habitrain$recvTracker = new HabiRealProgressTracker(HabiRealProgressTracker.WORLD_STAGES);
            this.habitrain$startNanos = nowNanos;
            this.habitrain$lastFrameNanos = nowNanos;
        }
        double dt = Math.min(0.1, Math.max(0.001, (nowNanos - this.habitrain$lastFrameNanos) / 1_000_000_000.0));
        this.habitrain$lastFrameNanos = nowNanos;
        double elapsedSec = Math.max(0.0, (nowNanos - this.habitrain$startNanos) / 1_000_000_000.0);

        double enterAlpha = HabiKineticGfx.sstep(0.0, 1.0, (now - this.createdAt) / 480.0);
        if (this.receivedAt < 0L && this.levelReceived.getAsBoolean() && (now - this.createdAt) >= 650L) {
            this.receivedAt = now;
        }

        double exitAlpha = 1.0;
        double arriveT = 0.0;
        boolean arriving = this.receivedAt >= 0L;
        if (arriving) {
            long since = now - this.receivedAt;
            arriveT = HabiKineticGfx.sstep(0.0, 1.0, since / 800.0);
            if (since >= 800L) {
                long ex = since - 800L;
                exitAlpha = 1.0 - HabiKineticGfx.sstep(0.0, 1.0, ex / 500.0);
                if (ex >= 500L) {
                    this.onClose();
                    ci.cancel();
                    return;
                }
            }
        }

        String tag = "WORLD TRANSFER";
        if (this.minecraft != null) {
            if (this.minecraft.getCurrentServer() != null && this.minecraft.getCurrentServer().name != null) {
                String srv = this.minecraft.getCurrentServer().name.trim();
                if (!srv.isEmpty() && srv.length() <= 22 && srv.chars().allMatch(c -> c >= 32 && c < 127)) {
                    tag = srv.toUpperCase(Locale.ROOT);
                } else {
                    tag = "REMOTE SERVER";
                }
            } else if (this.minecraft.getSingleplayerServer() != null) {
                tag = "LOCAL WORLD";
            }
        }

        HabiKineticUiController.getInstance().renderReceivingLevelScreen(
                this.minecraft, this.habitrain$recvTracker, arriving, arriveT,
                tag, elapsedSec, dt, enterAlpha * exitAlpha);
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
