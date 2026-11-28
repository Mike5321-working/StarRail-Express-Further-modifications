package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiWorldEntrySession;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps {@link ProgressScreen} seamless inside {@link HabiWorldEntrySession} when active
 * during world entry (never hijacking unrelated save/quit progress screens).
 */
@Environment(EnvType.CLIENT)
@Mixin(ProgressScreen.class)
public abstract class WorldEntryProgressScreenMixin extends Screen {

    @Shadow @Nullable private Component header;
    @Shadow @Nullable private Component stage;

    protected WorldEntryProgressScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderWorldEntryProgress(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return;
        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        if (!session.isActive()) return;
        if (HabiWorldEntrySession.isWorldSavingMessage(this.header)) {
            session.abort(this.minecraft, false);
            return;
        }
        session.reportConnectStatus(this.stage != null ? this.stage : this.header);
        HabiKineticUiController.getInstance().renderWorldEntryScreen(this.minecraft, mouseX, mouseY);
        ci.cancel();
    }
}
