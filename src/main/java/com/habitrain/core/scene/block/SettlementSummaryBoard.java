package com.habitrain.core.scene.block;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Persisted world marker; replay data stays in the server service, not in per-board NBT.
 *
 * <p>A level mahogany tabletop on four wooden legs supports the floating replay display. Only
 * display settings are stored per table; the last match and its mesh remain shared.</p>
 */
public final class SettlementSummaryBoard extends BaseEntityBlock {
    public static final ResourceLocation ID = HabiTrainCore.id("settlement_summary_board");
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final MapCodec<SettlementSummaryBoard> CODEC = simpleCodec(SettlementSummaryBoard::new);
    /** North-facing (front at z = 0) boxes in model pixels; other facings are rotated copies. */
    private static final double[][] NORTH_BOXES = {
            {0, 14, 0, 16, 16, 16},   // flat tabletop
            {1, 0, 1, 4, 14, 4},      // four legs, open space underneath
            {12, 0, 1, 15, 14, 4},
            {1, 0, 12, 4, 14, 15},
            {12, 0, 12, 15, 14, 15},
            {2, 11, 2, 14, 14, 3},    // apron around the tabletop
            {2, 11, 13, 14, 14, 14},
            {2, 11, 3, 3, 14, 13},
            {13, 11, 3, 14, 14, 13}
    };
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction direction : Direction.Plane.HORIZONTAL) SHAPES.put(direction, shapeFor(direction));
    }

    public static final SettlementSummaryBoard BLOCK = new SettlementSummaryBoard(
            BlockBehaviour.Properties.of().strength(2.5f).sound(net.minecraft.world.level.block.SoundType.WOOD).noOcclusion());
    public static final BlockItem ITEM = new BlockItem(BLOCK, new Item.Properties());
    private static BlockEntityType<Marker> markerType;

    public SettlementSummaryBoard(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, ID, BLOCK);
        Registry.register(BuiltInRegistries.ITEM, ID, ITEM);
        markerType = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID,
                BlockEntityType.Builder.of(Marker::new, BLOCK).build(null));
    }

    /** Rotates the north-facing pixel boxes about the block centre (matches the blockstate's y rotation). */
    static VoxelShape shapeFor(Direction facing) {
        VoxelShape shape = Shapes.empty();
        for (double[] b : NORTH_BOXES) {
            double[] r = BoardShapeMath.rotate(b, facing);
            shape = Shapes.or(shape, Block.box(r[0], r[1], r[2], r[3], r[4], r[5]));
        }
        return shape.optimize();
    }

    @Override protected MapCodec<SettlementSummaryBoard> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(FACING), SHAPES.get(Direction.NORTH));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                         BlockHitResult hit) {
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                        Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isSpectator()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new Marker(pos, state); }

    public static final class Marker extends BlockEntity {
        private ReplayBoardSettings settings = ReplayBoardSettings.DEFAULT;
        private Playback playback;
        /** Transient per-session playback state; NEVER written into persistent {@link #saveAdditional} NBT. */
        private @Nullable ReplayBoardSessionState session;

        public record Playback(java.util.UUID roundId, long startTick, long endTick, boolean shared) {
            public Playback(java.util.UUID roundId, long startTick, long endTick) {
                this(roundId, startTick, endTick, false);
            }
            public boolean preparing() { return startTick == Long.MAX_VALUE; }
            public boolean pendingOrPlaying(long gameTick) { return gameTick < endTick; }
            public boolean playing(long gameTick) { return gameTick >= startTick && gameTick < endTick; }
        }

        public Playback playback() { return playback; }

        public @Nullable ReplayBoardSessionState session() { return session; }

        /**
         * Updates the table's transient in-memory playback session and notifies tracking clients.
         * Never marks the chunk dirty or writes session settings into persistent block-entity NBT.
         */
        public void setSession(@Nullable ReplayBoardSessionState next) {
            session = (next != null && next.endedToIdle()) ? null : next;
            if (session != null) {
                playback = null;
            }
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        /** Client-only direct update when a live {@code BoardSessionSyncS2C} packet arrives. */
        public void applyClientSession(@Nullable ReplayBoardSessionState next) {
            if (next != null && next.endedToIdle()) {
                session = null;
                playback = null;
                return;
            }
            if (next != null && session != null && next.roundId().equals(session.roundId())
                    && next.revision() < session.revision()) {
                return;
            }
            session = next;
            if (next != null) playback = null;
        }

        public boolean startPlayback(java.util.UUID round, int duration) {
            return startPlayback(round, duration, false);
        }

        private boolean startPlayback(java.util.UUID round, int duration, boolean shared) {
            if (level == null || level.isClientSide) return false;
            long now = level.getGameTime();
            long start = now + 8;
            session = ReplayBoardSessionState.fresh(round, shared, start);
            playback = new Playback(round, start, start + Math.max(duration, 20), shared);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            return true;
        }

        public boolean preparePlayback(java.util.UUID round) {
            if (level == null || level.isClientSide) return false;
            session = ReplayBoardSessionState.preparing(round, true, 0.0, ReplayBoardSessionState.DEFAULT_SPEED,
                    false, null, false, true);
            playback = new Playback(round, Long.MAX_VALUE, Long.MAX_VALUE, true);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            return true;
        }

        public boolean beginPreparedPlayback(java.util.UUID round, int duration) {
            if (level == null || level.isClientSide) return false;
            if (session != null && session.preparing() && session.roundId().equals(round)) {
                long start = level.getGameTime() + 8;
                session = session.beginAt(start, Math.max(0, duration));
                playback = null;
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
                return true;
            }
            if (playback == null || !playback.preparing() || !playback.roundId().equals(round)) return false;
            playback = null;
            return startPlayback(round, duration, true);
        }

        public Marker(BlockPos pos, BlockState state) { super(markerType, pos, state); }

        public ReplayBoardSettings settings() { return settings; }

        public void setSettings(ReplayBoardSettings value) {
            if (settings.equals(value)) return;
            settings = value;
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            // Only persistent display geometry is saved; playback sessions are strictly transient.
            tag.put("Display", settings.save());
        }

        @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            settings = ReplayBoardSettings.load(tag.getCompound("Display"));
            session = tag.contains("Session", Tag.TAG_COMPOUND)
                    ? ReplayBoardSessionState.readTransientSyncTag(tag.getCompound("Session"))
                    : null;
            if (session != null && session.endedToIdle()) session = null;
            playback = tag.hasUUID("PlaybackRound")
                    ? new Playback(tag.getUUID("PlaybackRound"), tag.getLong("PlaybackStart"), tag.getLong("PlaybackEnd"), tag.getBoolean("PlaybackShared"))
                    : null;
        }

        @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            CompoundTag tag = saveCustomOnly(registries);
            if (session != null && session.active()) {
                tag.put("Session", session.writeTransientSyncTag());
            } else if (level != null && playback != null && playback.pendingOrPlaying(level.getGameTime())) {
                tag.putUUID("PlaybackRound", playback.roundId());
                tag.putLong("PlaybackStart", playback.startTick());
                tag.putLong("PlaybackEnd", playback.endTick());
                tag.putBoolean("PlaybackShared", playback.shared());
            }
            return tag;
        }

        @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
            return ClientboundBlockEntityDataPacket.create(this);
        }
    }
}
