package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiConnectScreenAbort;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiWorldEntrySession;
import io.netty.channel.ChannelFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders {@link ConnectScreen} seamlessly inside the unified {@link HabiWorldEntrySession}
 * cutscene (both when launched from Station 01 Multiplayer and when launched from native
 * Direct Connect / Edit Server screens) while supporting clean ESC cancellation.
 */
@Environment(EnvType.CLIENT)
@Mixin(ConnectScreen.class)
public abstract class WorldEntryConnectScreenMixin extends Screen implements HabiConnectScreenAbort {

    @Shadow @Final public static Component ABORT_CONNECTION;
    @Shadow volatile @Nullable Connection connection;
    @Shadow @Nullable ChannelFuture channelFuture;
    @Shadow volatile boolean aborted;
    @Shadow @Final Screen parent;
    @Shadow private Component status;

    protected WorldEntryConnectScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$renderWorldEntryConnect(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return;

        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        session.ensureActiveForWorldScreen(this.minecraft, "远程服务器", "REMOTE SERVER // LINKING");
        session.reportConnectStatus(this.status);
        HabiKineticUiController.getInstance().renderWorldEntryScreen(this.minecraft, mouseX, mouseY);
        ci.cancel();
    }

    @Override
    public void habitrain$abortConnect() {
        this.aborted = true;
        if (this.channelFuture != null) {
            this.channelFuture.cancel(true);
            this.channelFuture = null;
        }
        if (this.connection != null) {
            this.connection.disconnect(ABORT_CONNECTION);
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
