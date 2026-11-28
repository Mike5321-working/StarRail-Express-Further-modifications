package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiLoadingFramePumper;
import com.habitrain.core.client.title.HabiMinecraftRunTickAccess;
import com.habitrain.core.client.title.HabiWorldEntrySession;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes screen transitions on {@link Minecraft} so {@link HabiWorldEntrySession} stays
 * seamlessly alive across the world-entry screen chain, exposes {@code runTick(boolean)} for
 * loading frame pumping, and smooths {@code doWorldLoad}'s blind spin and double sleep.
 */
@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public abstract class WorldEntryMinecraftMixin implements HabiMinecraftRunTickAccess {

    @Invoker("runTick")
    @Override
    public abstract void habitrain$invokeRunTick(boolean renderLevel);

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void habitrain$onSetScreen(@Nullable Screen screen, CallbackInfo ci) {
        HabiWorldEntrySession.getInstance().onScreenChanged((Minecraft) (Object) this, screen);
    }

    @Inject(method = "forceSetScreen", at = @At("HEAD"))
    private void habitrain$onForceSetScreen(@Nullable Screen screen, CallbackInfo ci) {
        HabiWorldEntrySession.getInstance().onScreenChanged((Minecraft) (Object) this, screen);
    }

    @Redirect(
            method = "runTick",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I")
    )
    private int habitrain$capLoadingCatchupTicks(int a, int b) {
        return HabiLoadingFramePumper.capCatchupTicks((Minecraft) (Object) this, a, b);
    }

    @Inject(method = "doWorldLoad", at = @At("HEAD"))
    private void habitrain$resumeTunnelAfterConfirmPrompt(CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticLoadingEnabled()) return;
        HabiWorldEntrySession session = HabiWorldEntrySession.getInstance();
        if (!session.isActive() && session.hasPreservedConfirmRoute()) {
            session.beginDirectTunnel(null, null);
        }
    }

    @Redirect(
            method = "doWorldLoad",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V")
    )
    private void habitrain$pumpDuringDoWorldLoadSpin() {
        HabiLoadingFramePumper.onDoWorldLoadProgressYield((Minecraft) (Object) this);
    }

    @Inject(
            method = "doWorldLoad",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V")
    )
    private void habitrain$markDoWorldLoadFrameStart(CallbackInfo ci) {
        HabiLoadingFramePumper.markDoWorldLoadFrameStart();
    }

    @Redirect(
            method = "doWorldLoad",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V")
    )
    private void habitrain$adaptiveDoWorldLoadSleep(long millis) throws InterruptedException {
        HabiLoadingFramePumper.onDoWorldLoadSleep((Minecraft) (Object) this, millis);
    }
}
