package com.habitrain.core.client.mixin;

import com.habitrain.core.client.loading.HabiLoadingScreenTextures;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiWorldEntrySession;
import com.mojang.blaze3d.systems.RenderSystem;
import net.exmo.sre.loading.FrameAnimationRenderer;
import net.exmo.sre.loading.SREReceivingLevelScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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
 * Bridges {@link SREReceivingLevelScreen} seamlessly into {@link HabiWorldEntrySession}:
 * keeps the Time Tunnel continuous until real terrain arrival, displays the Act 3 gold-foil
 * postmark seal ({@code 0.00..0.48s}), then closes the screen right at {@code END_PORTAL_START}
 * so the expanding Apple Portal Iris reveals the real live 3D Minecraft world underneath.
 */
@Environment(EnvType.CLIENT)
@Mixin(SREReceivingLevelScreen.class)
public abstract class SREReceivingLevelScreenMixin extends Screen {

    @Shadow @Final private BooleanSupplier levelReceived;

    protected SREReceivingLevelScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void habitrain$onInit(CallbackInfo ci) {
        if (HabiKineticUiController.isKineticLoadingEnabled()) {
            String tag = habitrain$resolveRouteTag();
            HabiWorldEntrySession.getInstance().ensureActiveForWorldScreen(
                    this.minecraft, habitrain$resolveRouteName(), tag + " // EXPRESS 01");
        } else {
            HabiBootSession.getInstance().onLeaveTitleFlow();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderKineticReceiving(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return;

        String tag = habitrain$resolveRouteTag();
        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        session.ensureActiveForWorldScreen(this.minecraft, habitrain$resolveRouteName(), tag + " // EXPRESS 01");
        session.reportReceivingLevel(this.levelReceived != null && this.levelReceived.getAsBoolean(), tag);

        HabiKineticUiController.getInstance().renderWorldEntryScreen(this.minecraft, mouseX, mouseY);

        if (session.shouldCloseReceivingScreen() || !session.isActive()) {
            this.onClose();
        }
        ci.cancel();
    }

    @Unique
    private String habitrain$resolveRouteName() {
        if (this.minecraft != null) {
            if (this.minecraft.getCurrentServer() != null && this.minecraft.getCurrentServer().name != null
                    && !this.minecraft.getCurrentServer().name.isBlank()) {
                return this.minecraft.getCurrentServer().name.trim();
            }
            if (this.minecraft.getSingleplayerServer() != null) {
                return this.minecraft.getSingleplayerServer().getWorldData().getLevelName();
            }
        }
        return "星夜轨网";
    }

    @Unique
    private String habitrain$resolveRouteTag() {
        if (this.minecraft != null) {
            if (this.minecraft.getCurrentServer() != null && this.minecraft.getCurrentServer().name != null) {
                String srv = this.minecraft.getCurrentServer().name.trim();
                if (!srv.isEmpty() && srv.length() <= 22 && srv.chars().allMatch(c -> c >= 32 && c < 127)) {
                    return srv.toUpperCase(Locale.ROOT);
                }
                return "REMOTE SERVER";
            }
            if (this.minecraft.getSingleplayerServer() != null) {
                return "LOCAL WORLD";
            }
        }
        return "WORLD TRANSFER";
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
