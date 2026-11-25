package com.habitrain.core.client;

import io.wifi.starrailexpress.event.OnOpenInventory;
import io.wifi.starrailexpress.event.client.OnGameFinishedClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Client-side copy of the server-owned eliminated-player rest-area eligibility. */
@Environment(EnvType.CLIENT)
public final class EliminatedRestPromptState {
    private static volatile boolean visible;
    private static volatile boolean canToggle;
    private static boolean lifecycleRegistered;

    private EliminatedRestPromptState() {
    }

    public static boolean isVisible() {
        return visible;
    }

    public static boolean canToggle() {
        return canToggle;
    }

    /**
     * True while the server considers the local player to be physically resting in the lobby
     * after elimination during an ongoing match.
     *
     * <p>In {@code EliminatedRestAreaService#syncPrompt}, {@code visible} is
     * {@code canEnterRestArea(player)} (false when already resting) and {@code canToggle} is
     * {@code visible || isResting(player)}, so {@code canToggle && !visible} uniquely identifies
     * the resting-in-lobby state without changing the S2C payload wire format.</p>
     */
    public static boolean isResting() {
        return canToggle && !visible;
    }

    /**
     * Pure policy for whether a {@code LimitedInventoryScreen} opened in resting-lobby mode
     * should close automatically on tick: close when leaving rest while the match is still
     * running (e.g. revival or returning to spectator), but stay open when the match finishes
     * naturally into the normal lobby.
     */
    public static boolean shouldCloseRestingInventory(boolean openedWhileResting,
                                                      boolean currentlyResting,
                                                      boolean gameRunning) {
        return openedWhileResting && !currentlyResting && gameRunning;
    }

    public static void update(boolean visible, boolean canToggle) {
        EliminatedRestPromptState.visible = visible;
        EliminatedRestPromptState.canToggle = canToggle;
    }

    public static void clear() {
        update(false, false);
    }

    public static void registerLifecycle() {
        if (lifecycleRegistered) {
            return;
        }
        lifecycleRegistered = true;

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(EliminatedRestPromptState::clear));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.isSameThread()) {
                clear();
            } else {
                client.execute(EliminatedRestPromptState::clear);
            }
        });
        OnGameFinishedClient.EVENT.register(EliminatedRestPromptState::clear);
        OnOpenInventory.EVENT.register((player, screen) ->
                player != null && !player.isCreative() && isResting());
    }
}
