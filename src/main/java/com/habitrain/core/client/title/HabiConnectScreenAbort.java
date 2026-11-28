package com.habitrain.core.client.title;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Mixin duck interface implemented on {@code ConnectScreen} so ESC during the world-entry
 * cutscene can abort a pending server connection cleanly and return to the parent screen.
 */
@Environment(EnvType.CLIENT)
public interface HabiConnectScreenAbort {
    void habitrain$abortConnect();
}
