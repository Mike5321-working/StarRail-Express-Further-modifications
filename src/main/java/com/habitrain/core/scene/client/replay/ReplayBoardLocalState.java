package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.block.SettlementSummaryBoard;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
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
            // Creative Shift+Right-Click opens persistent placement & 4-way facing settings (works while holding any item).
            if (player.isCreative() && player.isShiftKeyDown()) {
                if (!player.mayBuild() || !player.isAlive() || !player.canInteractWithBlock(pos, 1.0)) {
                    player.displayClientMessage(ReplayBoardTexts.tr("controls.build_required"), true);
                    return InteractionResult.CONSUME;
                }
                Direction facing = level.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)
                        ? level.getBlockState(pos).getValue(SettlementSummaryBoard.FACING) : Direction.NORTH;
                client.setScreen(new ReplayBoardSettingsScreen(level.dimension().location(), pos, marker.settings(), facing));
                return InteractionResult.CONSUME;
            }
            // Right-clicking the replay table opens the secondary controls & roster panel even when holding
            // a lobby item or train ticket; survival Shift+Right-Click no longer toggles roof implicitly.
            client.setScreen(new ReplayBoardControlsScreen(client.level, pos));
            return InteractionResult.CONSUME;
        });
    }

    static boolean fullBody() { return fullBody; }
    static void setFullBody(boolean value) { fullBody = value; }
    static void toggleRoof() { fullBody = !fullBody; }
    static void reset() { fullBody = false; }
}
