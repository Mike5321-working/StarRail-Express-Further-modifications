package com.habitrain.core.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientPacketListener;

/** INIT can run before Minecraft exposes the new play listener through getConnection(). */
@Environment(EnvType.CLIENT)
public final class ClientConnectionSession {
    private static volatile ClientPacketListener active;
    private ClientConnectionSession() {}
    public static synchronized void begin(ClientPacketListener handler) { active = handler; }
    public static boolean isCurrent(ClientPacketListener handler) { return active == handler; }
    public static synchronized boolean disconnect(ClientPacketListener handler) {
        if (active != handler) return false;
        active = null;
        return true;
    }
    public static boolean isDisconnected() { return active == null; }

    public static void executeIfConnected(ClientPlayNetworking.Context context, Runnable task) {
        var expected = context.player().connection;
        context.client().execute(() -> {
            if (isCurrent(expected) && context.client().getConnection() == expected
                    && expected.getConnection().isConnected()) task.run();
        });
    }
}
