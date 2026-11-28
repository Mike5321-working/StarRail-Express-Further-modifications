package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Mixin invoker bridge exposing {@code Minecraft#runTick(boolean)} to {@link HabiLoadingFramePumper}
 * so blocking singleplayer datapack/server startup loops can pump safe render-only frames
 * ({@code runTick(false)}) using the exact same vanilla path as {@code Minecraft#forceSetScreen}.
 */
@Environment(EnvType.CLIENT)
public interface HabiMinecraftRunTickAccess {
    void habitrain$invokeRunTick(boolean renderLevel);
}
