package com.habitrain.core.client.mixin;

import com.habitrain.core.client.drone.DroneOperatorClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Isolates drone hotkeys from vanilla's single-value {@code KeyMapping.MAP} on foot,
 * eliminates false red key-conflict warnings against suppressed on-foot keys in Controls UI,
 * and silences all vanilla/modded {@link KeyMapping} queries while piloting the recon drone in FPV.
 */
@Mixin(value = KeyMapping.class, priority = 400)
public abstract class DroneKeyMappingMixin {
    @Shadow
    @Final
    private static Map<String, KeyMapping> ALL;

    @Shadow
    @Final
    private static Map<InputConstants.Key, KeyMapping> MAP;

    @Shadow
    @Final
    private String name;

    @Shadow
    private InputConstants.Key key;

    @Shadow
    private boolean isDown;

    @Shadow
    private int clickCount;

    @Shadow
    public abstract boolean isUnbound();

    @Inject(
            method = "<init>(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILjava/lang/String;)V",
            at = @At("TAIL")
    )
    private void habitrain$preserveNonDroneKeyInMapOnInit(
            String name,
            InputConstants.Type type,
            int keyCode,
            String category,
            CallbackInfo ci
    ) {
        habitrain$reconcileNonDronePriority();
    }

    @Inject(method = "resetMapping", at = @At("TAIL"))
    private static void habitrain$preserveNonDroneKeyInMapOnReset(CallbackInfo ci) {
        habitrain$reconcileNonDronePriority();
    }

    @Unique
    private static void habitrain$reconcileNonDronePriority() {
        for (KeyMapping km : ALL.values()) {
            if (km == null || km.isUnbound() || DroneOperatorClient.isDroneExclusiveKey(km)) {
                continue;
            }
            InputConstants.Key bound = ((DroneKeyMappingMixin) (Object) km).key;
            KeyMapping current = MAP.get(bound);
            if (current == null || DroneOperatorClient.isDroneExclusiveKey(current)) {
                MAP.put(bound, km);
            }
        }
    }

    @Inject(method = "same", at = @At("HEAD"), cancellable = true)
    private void habitrain$isolateDroneOnlyContextInControlsUi(
            KeyMapping other,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (DroneOperatorClient.areContextDisjointKeys((KeyMapping) (Object) this, other)) {
            cir.setReturnValue(false);
        }
    }

    @ModifyReturnValue(method = "isDown", at = @At("RETURN"))
    private boolean habitrain$suppressIsDownDuringFpv(boolean original) {
        if (original && DroneOperatorClient.shouldSuppressVanillaKeyMapping()) {
            return false;
        }
        return original;
    }

    @ModifyReturnValue(method = "consumeClick", at = @At("RETURN"))
    private boolean habitrain$suppressConsumeClickDuringFpv(boolean original) {
        if (DroneOperatorClient.shouldSuppressVanillaKeyMapping()) {
            this.clickCount = 0;
            this.isDown = false;
            return false;
        }
        return original;
    }

    @ModifyReturnValue(method = "matches", at = @At("RETURN"))
    private boolean habitrain$suppressMatchesDuringFpv(boolean original) {
        if (original && DroneOperatorClient.shouldSuppressVanillaKeyMapping()) {
            return false;
        }
        return original;
    }

    @ModifyReturnValue(method = "matchesMouse", at = @At("RETURN"))
    private boolean habitrain$suppressMatchesMouseDuringFpv(boolean original) {
        if (original && DroneOperatorClient.shouldSuppressVanillaKeyMapping()) {
            return false;
        }
        return original;
    }
}
