package com.habitrain.core.client.mixin;

import com.habitrain.core.client.title.HabiBootAudioEngine;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiWorldEntrySession;
import net.exmo.sre.loading.StarRailExpressTitleScreen;
import net.exmo.sre.loading.StarRailLoadingOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses external loading-complete "ding" chimes (e.g. third-party Ding mods playing
 * {@code entity.experience_orb.pickup} or note-block bells), stray upstream title ambience,
 * and vanilla menu music routed through {@link SoundManager} while HabiTrain Kinetic UI
 * owns the client boot &amp; title flow (never active in-world, preserves UI button clicks).
 */
@Environment(EnvType.CLIENT)
@Mixin(SoundManager.class)
public abstract class BootSoundSuppressMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void habitrain$suppressExternalBootSound(SoundInstance sound, CallbackInfo ci) {
        if (habitrain$shouldSuppress(sound)) {
            ci.cancel();
        }
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void habitrain$suppressExternalBootSoundDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (habitrain$shouldSuppress(sound)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean habitrain$shouldSuppress(SoundInstance sound) {
        if (sound == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        if (HabiWorldEntrySession.getInstance().isActive()
                && sound.getSource() == SoundSource.MUSIC) {
            return true;
        }
        if (mc.level != null) return false;
        if (!HabiKineticUiController.isKineticLoadingEnabled()
                && !HabiKineticUiController.isKineticTitleEnabled()) {
            return false;
        }
        boolean inBootOrTitleFlow = !mc.isGameLoadFinished()
                || mc.getOverlay() instanceof StarRailLoadingOverlay
                || mc.screen instanceof StarRailExpressTitleScreen
                || mc.screen instanceof GenericMessageScreen
                || HabiBootSession.getInstance().getPhase() != HabiBootSession.Phase.IDLE;
        if (!inBootOrTitleFlow) return false;
        return HabiBootAudioEngine.shouldSuppressExternalBootSound(
                sound.getLocation(),
                sound.getSource(),
                HabiBootAudioEngine.getInstance().isBlockingVanillaMusic()
        );
    }
}
