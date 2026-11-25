package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Internal hook exposed via {@code SoundEnginePrewarmMixin} so Kinetic Boot UI can initialize
 * Minecraft's native OpenAL {@code SoundEngine} on boot frame 0 and keep it alive seamlessly
 * across the first {@code SoundManager.apply()} reload.
 */
@Environment(EnvType.CLIENT)
public interface HabiSoundEnginePrewarmAccess {
    /**
     * Initializes Minecraft's OpenAL library early if not yet loaded, honoring {@code options.soundDevice}.
     *
     * @return {@code true} if {@code SoundEngine} is loaded and ready to play streams
     */
    boolean habitrain$prewarmForKineticBoot();

    /** Returns whether {@code SoundEngine} currently has OpenAL initialized. */
    boolean habitrain$isSoundEngineLoaded();
}
