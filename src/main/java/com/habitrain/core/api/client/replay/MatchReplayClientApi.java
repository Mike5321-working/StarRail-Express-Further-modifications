package com.habitrain.core.api.client.replay;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;

import java.util.Objects;

/** Client entry point for a caller that already has the server's match record ID. */
@Environment(EnvType.CLIENT)
public final class MatchReplayClientApi {
    @FunctionalInterface public interface ScreenFactory {
        Screen open(Screen parent, String matchId, boolean export);
    }

    private static ScreenFactory factory = (parent, matchId, export) -> parent;

    private MatchReplayClientApi() {}

    public static void install(ScreenFactory clientFactory) {
        factory = Objects.requireNonNull(clientFactory);
    }

    public static Screen open(Screen parent, String matchId, boolean export) {
        return factory.open(parent, matchId, export);
    }
}
