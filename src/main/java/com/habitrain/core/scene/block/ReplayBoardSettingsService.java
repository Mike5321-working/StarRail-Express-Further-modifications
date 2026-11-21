package com.habitrain.core.scene.block;

import com.habitrain.core.network.replay.ReplayBoardSettingsPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import static com.habitrain.core.network.replay.ReplayBoardSettingsPackets.CheckedResult.Status;

/** Only a nearby creative player who can build may change this loaded table. */
public final class ReplayBoardSettingsService {
    private ReplayBoardSettingsService() {}

    public static void save(ServerPlayer player, ReplayBoardSettingsPackets.Save request) {
        var status = apply(player, request, null);
        ServerPlayNetworking.send(player, new ReplayBoardSettingsPackets.Result(request.dimension(), request.pos(), status == Status.SAVED));
    }

    public static void save(ServerPlayer player, ReplayBoardSettingsPackets.CheckedSave request) {
        if (!ServerPlayNetworking.canSend(player, ReplayBoardSettingsPackets.CheckedResult.TYPE)) return;
        var status = apply(player, request.save(), request.expected());
        ServerPlayNetworking.send(player, new ReplayBoardSettingsPackets.CheckedResult(request.requestId(), status));
    }

    private static Status apply(ServerPlayer player, ReplayBoardSettingsPackets.Save request, @Nullable ReplayBoardSettings expected) {
        var level = player.serverLevel();
        var pos = request.pos();
        if (player.isCreative() && !player.isSpectator() && player.isAlive() && player.mayBuild()
                && level.dimension().location().equals(request.dimension())
                && player.canInteractWithBlock(pos, 1.0)
                && level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()))
                && level.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)
                && level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker) {
            if (expected != null && !expected.equals(marker.settings())) return Status.CONFLICT;
            marker.setSettings(request.settings());
            return Status.SAVED;
        }
        return Status.REJECTED;
    }
}
