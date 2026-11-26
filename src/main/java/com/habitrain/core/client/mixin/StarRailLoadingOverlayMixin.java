package com.habitrain.core.client.mixin;

import com.habitrain.core.client.loading.HabiLoadingScreenTextures;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiKineticAtlas;
import com.habitrain.core.client.title.HabiKineticGfx;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiRealProgressTracker;
import net.exmo.sre.loading.FrameAnimationRenderer;
import net.exmo.sre.loading.StarRailExpressTitleScreen;
import net.exmo.sre.loading.StarRailLoadingOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Replaces {@link StarRailLoadingOverlay} with the HabiTrain Kinetic Flat 3D Station 00 loader
 * driven by real Minecraft {@link ReloadInstance} progress, seamlessly handing off to
 * {@link StarRailExpressTitleScreen} at 100% with zero cut frames.
 *
 * <p>When Kinetic Boot UI is disabled in client preferences, preserves the legacy static
 * {@link HabiLoadingScreenTextures#GAME_LOADING_TEXTURE} replacement.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(StarRailLoadingOverlay.class)
public class StarRailLoadingOverlayMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow @Final private Consumer<Optional<Throwable>> onFinish;
    @Shadow @Final private boolean fadeIn;
    @Shadow private long startMillis;
    @Shadow private long completeMillis;
    @Shadow private boolean finished;

    @Unique private boolean habitrain$initialized;
    @Unique private boolean habitrain$initialBoot;
    @Unique private long habitrain$startNanos;
    @Unique private long habitrain$lastFrameNanos;
    @Unique private HabiRealProgressTracker habitrain$standaloneTracker;

    @Inject(method = "registerTextures", at = @At("TAIL"))
    private static void habitrain$registerLoadingTextures(Minecraft minecraft, CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) {
            HabiLoadingScreenTextures.registerGameLoadingTexture(minecraft);
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderKineticOrPrepareLegacy(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) {
            HabiLoadingScreenTextures.registerGameLoadingTexture(this.minecraft);
            return;
        }

        long now = Util.getMillis();
        long nowNanos = System.nanoTime();
        if (this.startMillis < 0L) this.startMillis = now;

        if (!this.habitrain$initialized) {
            this.habitrain$initialized = true;
            this.habitrain$initialBoot = HabiKineticUiController.isInitialBootOverlay(this.minecraft, this.fadeIn);
            // Prepare fonts & sprites BEFORE starting the boot clock and intro.wav so frame 0 renders at t = 0.0s
            HabiKineticAtlas.getInstance().ensurePrepared();
            nowNanos = System.nanoTime();
            this.habitrain$startNanos = nowNanos;
            this.habitrain$lastFrameNanos = nowNanos;
            if (this.habitrain$initialBoot) {
                HabiBootSession.getInstance().beginInitialBoot(this.minecraft, this.reload);
            } else {
                this.habitrain$standaloneTracker = new HabiRealProgressTracker(HabiRealProgressTracker.BOOT_STAGES);
            }
        }

        // Complete reload & notify Minecraft onFinish exactly once
        if (!this.finished && this.reload.isDone()) {
            this.finished = true;
            this.completeMillis = now;
            try {
                this.reload.checkExceptions();
                this.onFinish.accept(Optional.empty());
            } catch (Throwable t) {
                this.onFinish.accept(Optional.of(t));
            }
            if (this.minecraft.screen != null) {
                this.minecraft.screen.init(this.minecraft, g.guiWidth(), g.guiHeight());
            }
        }

        if (this.habitrain$initialBoot) {
            boolean readyToHandoff = HabiKineticUiController.getInstance()
                    .renderInitialBootOverlay(this.minecraft, this.reload, mouseX, mouseY);
            if (this.finished && readyToHandoff) {
                if (this.minecraft.screen instanceof StarRailExpressTitleScreen
                        && HabiKineticUiController.isKineticTitleEnabled()) {
                    // Seamless zero-frame handoff into TitleScreen READY_STANDBY (native input enabled!)
                    HabiBootSession.getInstance().markOverlayHandedOff();
                    this.minecraft.setOverlay(null);
                } else if (this.completeMillis >= 0L && (now - this.completeMillis) >= 950L) {
                    HabiBootSession.getInstance().onLeaveTitleFlow();
                    this.minecraft.setOverlay(null);
                }
            }
            ci.cancel();
            return;
        }

        // Standalone reload (e.g. F3+T in-game or from a sub-screen): auto-dismiss smoothly on completion
        double dt = Math.min(0.1, Math.max(0.001, (nowNanos - this.habitrain$lastFrameNanos) / 1_000_000_000.0));
        this.habitrain$lastFrameNanos = nowNanos;
        double elapsedSec = Math.max(0.0, (nowNanos - this.habitrain$startNanos) / 1_000_000_000.0);

        double fadeAlpha = 1.0;
        if (this.finished && this.completeMillis >= 0L) {
            long exitElapsed = now - (this.completeMillis + 420L);
            if (exitElapsed >= 0L) {
                fadeAlpha = 1.0 - HabiKineticGfx.sstep(0.0, 1.0, exitElapsed / 450.0);
                if (exitElapsed >= 450L) {
                    this.minecraft.setOverlay(null);
                    ci.cancel();
                    return;
                }
            }
        }
        HabiKineticUiController.getInstance().renderStandaloneReloadOverlay(
                this.minecraft, this.habitrain$standaloneTracker, this.reload,
                this.finished, elapsedSec, dt, fadeAlpha);
        ci.cancel();
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/exmo/sre/loading/FrameAnimationRenderer;hasFrames()Z")
    )
    private boolean habitrain$disableVideoAnim(FrameAnimationRenderer instance) {
        return false;
    }

    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"
            ),
            index = 0
    )
    private ResourceLocation habitrain$replaceStartupLoadingTexture(ResourceLocation original) {
        return HabiLoadingScreenTextures.GAME_LOADING_TEXTURE;
    }
}
