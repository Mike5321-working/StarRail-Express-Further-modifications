package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.List;
import java.util.Set;

/**
 * Duck interface injected onto {@link net.minecraft.server.packs.resources.SimpleReloadInstance}
 * so the kinetic loading UI can read real per-listener preparation &amp; apply completion counts
 * on the render thread without reflective hacks or fake timers.
 */
@Environment(EnvType.CLIENT)
public interface HabiReloadProgressAccess {
    List<PreparableReloadListener> habitrain$getAllListeners();

    Set<PreparableReloadListener> habitrain$getPendingPreparingListeners();

    boolean habitrain$isAllPreparationsDone();

    int habitrain$getStartedReloads();

    int habitrain$getFinishedReloads();

    int habitrain$getStartedTaskCount();

    int habitrain$getDoneTaskCount();

    int habitrain$getListenerCount();
}
