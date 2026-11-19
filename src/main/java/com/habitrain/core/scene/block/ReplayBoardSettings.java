package com.habitrain.core.scene.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import com.habitrain.core.scene.replay.ReplayStagePlan;

/** Per-table display placement. Distances are world blocks, offsets follow the table's facing. */
public record ReplayBoardSettings(float mapSize, float height, float offsetX, float offsetZ, float rotation) {
    public static final float DEFAULT_MAP_SIZE = 6.25f;
    public static final float MIN_SIZE = 1, MAX_SIZE = 32, MAX_HEIGHT = 16, MAX_OFFSET = 16;
    public static final ReplayBoardSettings DEFAULT = new ReplayBoardSettings(DEFAULT_MAP_SIZE, 0.25f, 0, 0, 0);

    public ReplayBoardSettings {
        if (!inRange(mapSize, MIN_SIZE, MAX_SIZE) || !inRange(height, 0, MAX_HEIGHT)
                || !inRange(offsetX, -MAX_OFFSET, MAX_OFFSET) || !inRange(offsetZ, -MAX_OFFSET, MAX_OFFSET)
                || !inRange(rotation, -180, 180)) throw new IllegalArgumentException("Invalid replay table settings");
    }

    public float scale() { return mapSize / DEFAULT_MAP_SIZE; }

    public double viewDistance(BlockPos pos, Direction facing, Vec3 eye) {
        double dx = eye.x - (pos.getX() + .5), dz = eye.z - (pos.getZ() + .5);
        double dy = eye.y - (pos.getY() + 1);
        double displayX = dx - offsetX * facing.getStepZ() - offsetZ * facing.getStepX();
        double displayZ = dz + offsetX * facing.getStepX() - offsetZ * facing.getStepZ();
        return Math.sqrt(Math.min(dx * dx + (dy - .4) * (dy - .4) + dz * dz,
                displayX * displayX + (dy - height) * (dy - height) + displayZ * displayZ));
    }

    public boolean inView(BlockPos pos, Direction facing, Vec3 eye) {
        return viewDistance(pos, facing, eye) <= ReplayStagePlan.FADE_END * Math.max(1f, scale());
    }

    /**
     * Largest player-to-marker distance (blocks) from which this table's in-world buttons may be used.
     * The floating display grows with {@link #scale()} and can sit up to {@link #MAX_OFFSET} blocks away from
     * the marker, so a fixed radius would make large or offset tables unusable.
     */
    public double interactReach() {
        return 8.0 * Math.max(1.0f, scale()) + Math.hypot(offsetX, offsetZ) + height + 2.0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("MapSize", mapSize);
        tag.putFloat("Height", height);
        tag.putFloat("OffsetX", offsetX);
        tag.putFloat("OffsetZ", offsetZ);
        tag.putFloat("Rotation", rotation);
        return tag;
    }

    /** Old markers have no settings; damaged individual values fall back without hiding the table. */
    public static ReplayBoardSettings load(CompoundTag tag) {
        return new ReplayBoardSettings(read(tag, "MapSize", DEFAULT.mapSize, MIN_SIZE, MAX_SIZE),
                read(tag, "Height", DEFAULT.height, 0, MAX_HEIGHT),
                read(tag, "OffsetX", 0, -MAX_OFFSET, MAX_OFFSET),
                read(tag, "OffsetZ", 0, -MAX_OFFSET, MAX_OFFSET), read(tag, "Rotation", 0, -180, 180));
    }

    private static float read(CompoundTag tag, String key, float fallback, float min, float max) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) return fallback;
        float value = tag.getFloat(key);
        return inRange(value, min, max) ? value : fallback;
    }

    private static boolean inRange(float value, float min, float max) {
        return Float.isFinite(value) && value >= min && value <= max;
    }
}
