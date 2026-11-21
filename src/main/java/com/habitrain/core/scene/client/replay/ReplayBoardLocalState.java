package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.block.SettlementSummaryBoard;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Local viewer preferences and interaction with the replay table. */
final class ReplayBoardLocalState {
    private static boolean fullBody;

    private ReplayBoardLocalState() {}

    static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
            var pos = hit.getBlockPos();
            if (!(level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) return InteractionResult.PASS;
            Minecraft client = Minecraft.getInstance();
            if (player != client.player) return InteractionResult.PASS;
            // Creative configuration works even while holding a block or tool.
            if (player.isCreative() && player.isShiftKeyDown()) {
                if (!player.mayBuild() || !player.isAlive() || !player.canInteractWithBlock(pos, 1.0)) {
                    player.displayClientMessage(ReplayBoardTexts.tr("controls.build_required"), true);
                    return InteractionResult.CONSUME;
                }
                client.setScreen(new ReplayBoardSettingsScreen(level.dimension().location(), pos, marker.settings()));
                return InteractionResult.CONSUME;
            }
            if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) {
                toggleRoof();
                player.displayClientMessage(ReplayBoardTexts.tr(fullBody ? "toggle.roof_full" : "toggle.roof_cut"), true);
            } else {
                client.setScreen(new ReplayBoardControlsScreen(client.level, pos));
            }
            return InteractionResult.CONSUME;
        });
    }

    static boolean fullBody() { return fullBody; }
    static void toggleRoof() { fullBody = !fullBody; }
    static void reset() { fullBody = false; }
}
