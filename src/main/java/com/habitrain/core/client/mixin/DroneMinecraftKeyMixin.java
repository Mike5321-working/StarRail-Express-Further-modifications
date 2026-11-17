package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import com.habitrain.core.client.gui.GameEndTransitionScreen;
import com.habitrain.core.client.gui.VoteLaunchTransitionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts {@code startAttack}, {@code startUseItem}, and {@code setScreen} for the drone operator:
 * prevents on-foot decorative block right-click failures and enter/exit ping-pong loops, blocks
 * gameplay container/chat screens in FPV while preserving {@code PauseScreen -> Options -> KeyBinds}
 * sub-navigation and meeting transitions.
 */
@Mixin(Minecraft.class)
public abstract class DroneMinecraftKeyMixin {
    @Shadow
    @Nullable
    public Screen screen;

    @Shadow
    private int rightClickDelay;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void habitrain$droneAttackTrigger(CallbackInfoReturnable<Boolean> cir) {
        if (DroneOperatorClient.handleFpvAttackClick()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void habitrain$droneRightClickIntercept(CallbackInfo ci) {
        if (DroneOperatorClient.handleStartUseItem((Minecraft) (Object) this)) {
            this.rightClickDelay = 6;
            ci.cancel();
        }
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void habitrain$blockNonSystemScreensDuringFpv(Screen nextScreen, CallbackInfo ci) {
        if (nextScreen == null || !DroneOperatorClient.isPilotingFpv()) {
            return;
        }
        // Allow sub-menus opened from an already-allowed screen (e.g. Pause -> Options -> Controls)
        if (this.screen != null) {
            return;
        }
        if (nextScreen instanceof PauseScreen
                || nextScreen instanceof DeathScreen
                || nextScreen instanceof ReceivingLevelScreen
                || nextScreen instanceof DisconnectedScreen
                || nextScreen instanceof VoteLaunchTransitionScreen
                || nextScreen instanceof GameEndTransitionScreen) {
            return;
        }
        String className = nextScreen.getClass().getName();
        if (className.startsWith("net.exmo.sre.meeting.") || nextScreen.getClass().getSimpleName().contains("Vote")) {
            return;
        }
        ci.cancel();
    }
}
