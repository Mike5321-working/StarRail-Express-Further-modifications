package com.habitrain.core.scene.block;

import com.habitrain.core.network.replay.ReplayBoardSettingsPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import static com.habitrain.core.network.replay.ReplayBoardSettingsPackets.CheckedResult.Status;

/** Only a nearby creative player who can build may change this loaded table. */
public final class ReplayBoardSettingsService {
    private ReplayBoardSettingsService() {}

    public static void save(ServerPlayer player, ReplayBoardSettingsPackets.Save request) {
        var status = apply(player, request, null, null);
        ServerPlayNetworking.send(player, new ReplayBoardSettingsPackets.Result(request.dimension(), request.pos(), status == Status.SAVED));
    }

    public static void save(ServerPlayer player, ReplayBoardSettingsPackets.CheckedSave request) {
        if (!ServerPlayNetworking.canSend(player, ReplayBoardSettingsPackets.CheckedResult.TYPE)) return;
        var status = apply(player, request.save(), request.expected(), request.expectedFacing());
        ServerPlayNetworking.send(player, new ReplayBoardSettingsPackets.CheckedResult(request.requestId(), status));
    }

    private static Status apply(ServerPlayer player, ReplayBoardSettingsPackets.Save request,
                                @Nullable ReplayBoardSettings expected, @Nullable Direction expectedFacing) {
        var level = player.serverLevel();
        var pos = request.pos();
        if (player.isCreative() && !player.isSpectator() && player.isAlive() && player.mayBuild()
                && level.dimension().location().equals(request.dimension())
                && player.canInteractWithBlock(pos, 1.0)
                && level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()))) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(SettlementSummaryBoard.BLOCK)
                    || !(level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) {
                return Status.REJECTED;
            }
            Direction currentFacing = state.getValue(SettlementSummaryBoard.FACING);
            if ((expected != null && !expected.equals(marker.settings()))
                    || (expectedFacing != null && expectedFacing != currentFacing)) {
                return Status.CONFLICT;
            }
            Direction desiredFacing = request.facing();
            if (desiredFacing != null && desiredFacing.getAxis().isHorizontal() && desiredFacing != currentFacing) {
                level.setBlock(pos, state.setValue(SettlementSummaryBoard.FACING, desiredFacing), Block.UPDATE_ALL);
                if (level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker refreshed) {
                    marker = refreshed;
                }
            }
            marker.setSettings(request.settings());
            return Status.SAVED;
        }
        return Status.REJECTED;
    }
}
