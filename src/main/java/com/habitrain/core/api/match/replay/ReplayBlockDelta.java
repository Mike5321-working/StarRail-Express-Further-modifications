package com.habitrain.core.api.match.replay;

import java.util.Objects;

/**
 * One dynamic world block state change recorded during a match (e.g., door open/close/lock/pry/seal,
 * wall breach, placed obstacle).
 *
 * <p>Coordinates {@code (x, y, z)} are integer block offsets relative to {@code LastMatchReplay.origin().minX/Y/Z()}.
 * When applying onto a {@code SceneAssetCodec.AssetData} whose {@code sourceBounds} may differ from {@code origin},
 * translate via {@code assetX = (origin.minX() - sourceBounds.minX()) + x} (same for Y and Z).</p>
 */
public record ReplayBlockDelta(int tick, int x, int y, int z, String blockState, byte reasonFlags) {
    public static final byte REASON_OTHER       = 0;
    public static final byte REASON_DOOR_TOGGLE = 1;
    public static final byte REASON_DOOR_LOCK   = 2;
    public static final byte REASON_DOOR_PRY    = 3;
    public static final byte REASON_DOOR_SEAL   = 4;
    public static final byte REASON_WALL_BREAK  = 5;
    public static final byte REASON_WALL_PLACE  = 6;

    public static final int MAX_DELTAS = 8192;
    public static final int MAX_STATE_LENGTH = 512;

    public ReplayBlockDelta {
        if (tick < 0 || tick > LastMatchReplay.MAX_TICKS) {
            throw new IllegalArgumentException("Invalid block delta tick: " + tick);
        }
        Objects.requireNonNull(blockState, "blockState");
        if (blockState.isEmpty() || blockState.length() > MAX_STATE_LENGTH) {
            throw new IllegalArgumentException("Invalid block state length: " + blockState.length());
        }
    }

    /** Packs relative coordinates into a deterministic 64-bit lookup key for fast per-position indexing. */
    public long posKey() {
        return (((long) x & 0x1FFFFFL) << 42) | (((long) z & 0x1FFFFFL) << 21) | ((long) y & 0x1FFFFFL);
    }

    public boolean isDoorChange() {
        return reasonFlags == REASON_DOOR_TOGGLE || reasonFlags == REASON_DOOR_LOCK
                || reasonFlags == REASON_DOOR_PRY || reasonFlags == REASON_DOOR_SEAL;
    }

    public boolean isAir() {
        return "minecraft:air".equals(blockState) || "minecraft:cave_air".equals(blockState)
                || "minecraft:void_air".equals(blockState);
    }
}
