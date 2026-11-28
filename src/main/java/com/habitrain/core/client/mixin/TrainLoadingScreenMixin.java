package com.habitrain.core.client.mixin;

import com.habitrain.core.client.loading.HabiLoadingScreenTextures;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiWorldEntrySession;
import com.mojang.blaze3d.systems.RenderSystem;
import net.exmo.sre.loading.FrameAnimationRenderer;
import net.exmo.sre.loading.TrainLoadingScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Bridges {@link TrainLoadingScreen} (world generation / spawn chunk loading) seamlessly into
 * the unified {@link HabiWorldEntrySession} Starry Chrono Rail Time Tunnel without black gaps or
 * progress resets.
 */
@Environment(EnvType.CLIENT)
@Mixin(TrainLoadingScreen.class)
public abstract class TrainLoadingScreenMixin extends Screen {

    @Shadow @Final private StoringChunkProgressListener progressListener;
    @Shadow @Final private BooleanSupplier levelReceived;
    @Shadow private long exitStart;

    protected TrainLoadingScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void habitrain$onInit(CallbackInfo ci) {
        if (HabiKineticUiController.isKineticLoadingEnabled()) {
            HabiWorldEntrySession.getInstance().ensureActiveForWorldScreen(
                    this.minecraft, "本地世界", "LOCAL WORLD // SPAWN RAIL");
        } else {
            HabiBootSession.getInstance().onLeaveTitleFlow();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderKineticWorldGen(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return;

        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        session.ensureActiveForWorldScreen(this.minecraft, "本地世界", "LOCAL WORLD // SPAWN RAIL");
        if (this.progressListener != null) {
            session.reportChunkGeneration(this.progressListener);
        }
        if (this.levelReceived != null && this.levelReceived.getAsBoolean()) {
            session.reportReceivingLevel(true, "LOCAL WORLD");
        }

        HabiKineticUiController.getInstance().renderWorldEntryScreen(this.minecraft, mouseX, mouseY);

        // Immediately hand off to SREReceivingLevelScreen / live world once spawn chunks finish
        // (eliminating the legacy 1030ms fade-to-black pause between loading screens).
        if (this.exitStart >= 0L) {
            this.onClose();
        }
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
        HabiLoadingScreenTextures.registerWorldLoadingTexture(this.minecraft);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1.0F, 1.0F, 1.0F, overallAlpha);
        g.blit(HabiLoadingScreenTextures.WORLD_LOADING_TEXTURE, 0, 0, 0.0F, 0.0F, screenWidth, screenHeight, screenWidth, screenHeight);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }
}
