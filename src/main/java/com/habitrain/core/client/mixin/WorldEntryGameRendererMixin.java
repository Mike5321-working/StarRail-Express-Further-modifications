package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiLoadingFramePumper;
import com.habitrain.core.client.title.HabiWorldEntrySession;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the Act 3 expanding Apple Portal Iris cutout and champagne-gold arrival bloom
 * directly over the live 3D Minecraft world at the tail of {@link GameRenderer#render}, and
 * applies the subtle landing FOV deceleration ({@code 1.13x -> 1.00x}).
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public abstract class WorldEntryGameRendererMixin {

    @Shadow @Final Minecraft minecraft;

    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private double habitrain$applyWorldEntryLandingFov(double original, Camera camera, float partialTicks, boolean useFovSetting) {
        if (!useFovSetting) return original;
        double mul = HabiWorldEntrySession.getInstance().getLandingFovMultiplier();
        return Math.abs(mul - 1.0) > 1e-4 ? original * mul : original;
    }

    @Inject(
            method = "reloadShaders",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;add(Ljava/lang/Object;)Z",
                    shift = At.Shift.AFTER
            )
    )
    private void habitrain$pumpBootFrameDuringShaderReload(ResourceProvider resourceProvider, CallbackInfo ci) {
        HabiLoadingFramePumper.maybePumpBootShaderFrame();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void habitrain$enterGameRendererRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        HabiLoadingFramePumper.enterGameRendererRender();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void habitrain$renderWorldEntryOverlay(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        try {
            HabiKineticUiController controller = HabiKineticUiController.getInstance();
            controller.renderWorldEntryInWorldOverlay(this.minecraft);
            controller.endGameFrame();
        } finally {
            HabiLoadingFramePumper.exitGameRendererRender();
            HabiLoadingFramePumper.markNormalFrameRendered();
        }
    }
}
