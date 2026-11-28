package com.habitrain.core.client.mixin;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.render.CustomTitlePanoramaRenderer;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiConnectScreenAbort;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiWorldEntrySession;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the panorama used by vanilla title-menu child screens, seamlessly bridges
 * {@link GenericMessageScreen} during world entry into {@link HabiWorldEntrySession},
 * and handles ESC cancellation while connecting to a server.
 */
@Environment(EnvType.CLIENT)
@Mixin(Screen.class)
public abstract class ScreenPanoramaMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderWorldEntryGenericMessage(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        if (!(screen instanceof GenericMessageScreen)) return;
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isGameLoadFinished() || mc.getOverlay() != null) return;
        if (HabiWorldEntrySession.isWorldSavingMessage(screen.getTitle())) return;

        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        if (!session.isActive() && HabiWorldEntrySession.isWorldEntryMessage(screen.getTitle())) {
            session.ensureActiveForWorldScreen(mc, "星夜轨网", "CHRONO RAIL // SYNCING");
        }
        if (session.isActive()) {
            session.reportConnectStatus(screen.getTitle());
            HabiKineticUiController.getInstance().renderWorldEntryScreen(mc, mouseX, mouseY);
            ci.cancel();
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void habitrain$handleWorldEntryKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        if (!session.isActive()) return;
        Screen screen = (Screen) (Object) this;
        if (!HabiWorldEntrySession.isAllowedEntryScreen(screen)) return;

        if (keyCode == GLFW.GLFW_KEY_M) {
            HabiBootSession.getInstance().toggleSoundMute();
            cir.setReturnValue(true);
            return;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && screen instanceof HabiConnectScreenAbort abortable) {
            abortable.habitrain$abortConnect();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true, require = 1)
    private void habitrain$renderCustomPanorama(GuiGraphics graphics, float delta, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        Minecraft mc = Minecraft.getInstance();

        if (HabiWorldEntrySession.getInstance().isActive() && HabiWorldEntrySession.isAllowedEntryScreen(screen)) {
            ci.cancel();
            return;
        }

        if (screen instanceof TitleScreen) {
            HabiBootSession.getInstance().onLeaveTitleFlow();
        } else if (HabiKineticUiController.isKineticTitleEnabled() && mc != null && mc.level == null) {
            HabiKineticUiController.getInstance().renderChildScreenBackdrop(mc);
            ci.cancel();
            return;
        }

        if (!ClientVisualPreferences.isCustomTitlePanoramaEnabled()) return;
        CustomTitlePanoramaRenderer.render(graphics, screen.width, screen.height, 1.0F, delta);
        ci.cancel();
    }
}
