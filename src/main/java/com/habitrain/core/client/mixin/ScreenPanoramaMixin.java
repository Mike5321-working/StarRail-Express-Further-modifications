package com.habitrain.core.client.mixin;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.render.CustomTitlePanoramaRenderer;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiKineticUiController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the panorama used by vanilla title-menu child screens.
 *
 * <p>When Kinetic Boot UI v3 is enabled, child screens (Multiplayer, Singleplayer, Options, etc.)
 * share the live Station 01 2.5D background with a subtle dark scrim for visual continuity;
 * when the player explicitly switches back to vanilla {@link TitleScreen}, stops the kinetic
 * soundtrack and falls back to the optional 3D cube-map panorama or default panorama.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(Screen.class)
public abstract class ScreenPanoramaMixin {

    @Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true, require = 1)
    private void habitrain$renderCustomPanorama(GuiGraphics graphics, float delta, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        Minecraft mc = Minecraft.getInstance();

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
