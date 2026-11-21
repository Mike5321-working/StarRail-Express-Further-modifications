package com.habitrain.core.game.sre.task;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import java.util.Map;
import java.util.Set;
/** Index owned by the server session. */
public class CustomTaskBlockCache {
    public static final int MAX_ENTRIES = TaskBlockIndex.MAX_ENTRIES;
    private final TaskBlockIndex index = new TaskBlockIndex();
    public CustomTaskBlockCache() {}

    public boolean put(BlockPos pos, int typeId) { return index.put(pos, typeId); }
    public boolean put(BlockPos pos, int typeId, Block block) { return index.put(pos, typeId, block); }
    public Set<Integer> get(BlockPos pos) { return index.get(pos); }
    public Block getBlockAt(BlockPos pos) { return index.getBlockAt(pos); }
    public Set<BlockPos> positionsForType(int typeId) { return index.positionsForType(typeId); }
    public Set<BlockPos> positionsForTypes(int... ids) { return index.positionsForTypes(ids); }
    public void clear() { index.clear(); }
    public boolean isEmpty() { return index.isEmpty(); }
    public int size() { return index.size(); }
    public Set<BlockPos> keySet() { return index.keySet(); }
    public Map<BlockPos, Set<Integer>> snapshot() { return index.snapshot(); }
    public void loadFromSnapshot(Map<BlockPos, Set<Integer>> data) { index.loadFromSnapshot(data); }

    Map<BlockPos, Set<Integer>> immutableSnapshot() {
        var data = snapshot();
        data.replaceAll((pos, types) -> Set.copyOf(types));
        return Map.copyOf(data);
    }
}
