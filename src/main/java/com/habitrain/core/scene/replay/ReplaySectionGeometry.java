package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.server.SceneCaptureService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.zip.CRC32;

/** Side-neutral snapshot used for both server patches and local cache matching. */
public final class ReplaySectionGeometry {
    private ReplaySectionGeometry() {}

    public static SceneBounds playBounds(@Nullable AABB area, int bottom, int top) {
        if (area == null || area.getXsize() <= 0 || area.getYsize() <= 0 || area.getZsize() <= 0) return SceneBounds.EMPTY;
        int y = Math.max(bottom, Mth.floor(area.minY)), yy = Math.min(top, Mth.ceil(area.maxY));
        if (y >= yy) return SceneBounds.EMPTY;
        return new SceneBounds(Mth.floor(area.minX), y, Mth.floor(area.minZ), Mth.ceil(area.maxX), yy, Mth.ceil(area.maxZ));
    }

    public static boolean supported(SceneBounds bounds) {
        return ReplayMapManifest.supportsBounds(bounds);
    }

    public record Snapshot(SceneAssetCodec.SectionData data, int crc, SceneBounds occupied) {}

    public static @Nullable Snapshot capture(LevelChunk chunk, int sy, SceneBounds clip) {
        int index = chunk.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSections().length) return null;
        LevelChunkSection section = chunk.getSections()[index];
        if (section.hasOnlyAir()) return null;
        return capture(chunk.getPos().x, sy, chunk.getPos().z, section.getStates(), clip, chunk.getLevel());
    }

    public static @Nullable Snapshot capture(int sx, int sy, int sz, PalettedContainer<BlockState> states, SceneBounds clip) {
        return capture(sx, sy, sz, states, clip, null);
    }

    public static @Nullable Snapshot capture(int sx, int sy, int sz, PalettedContainer<BlockState> states,
                                             SceneBounds clip, @Nullable Level level) {
        byte[] sky;
        byte[] block;
        if (level == null) {
            sky = defaultSkyLight();
            block = new byte[2048];
        } else {
            SectionPos sectionPos = SectionPos.of(sx, sy, sz);
            sky = captureLightBytes(level, LightLayer.SKY, sectionPos);
            block = captureLightBytes(level, LightLayer.BLOCK, sectionPos);
        }
        return captureFromCopied(sx, sy, sz, states, clip, sky, block);
    }

    /**
     * Pure off-thread-safe geometry extraction from an already-copied {@link PalettedContainer} and
     * pre-captured light byte arrays. Never touches {@link Level} or live chunks.
     */
    public static @Nullable Snapshot captureFromCopied(int sx, int sy, int sz, PalettedContainer<BlockState> states,
                                                       SceneBounds clip, byte[] sky, byte[] block) {
        int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
        int yStart = Math.max(0, clip.minY() - y0), yEnd = Math.min(16, clip.maxY() - y0);
        int zStart = Math.max(0, clip.minZ() - z0), zEnd = Math.min(16, clip.maxZ() - z0);
        int xStart = Math.max(0, clip.minX() - x0), xEnd = Math.min(16, clip.maxX() - x0);
        if (yStart >= yEnd || zStart >= zEnd || xStart >= xEnd) return null;
        int xMin = 16, yMin = 16, zMin = 16, xMax = 0, yMax = 0, zMax = 0;
        var ids = new IdentityHashMap<BlockState, Short>();
        var palette = new ArrayList<String>();
        palette.add(SceneAssetCodec.AIR_BLOCK_ID);
        short[] cells = new short[4096];
        for (int y = yStart; y < yEnd; y++) {
            for (int z = zStart; z < zEnd; z++) {
                int row = (y << 8) | (z << 4);
                for (int x = xStart; x < xEnd; x++) {
                    BlockState state = states.get(x, y, z);
                    if (state.isAir()) continue;
                    Short id = ids.get(state);
                    if (id == null) {
                        id = (short) palette.size();
                        ids.put(state, id);
                        palette.add(SceneCaptureService.serializeBlockState(state));
                    }
                    cells[row | x] = id;
                    if (x < xMin) xMin = x;
                    if (y < yMin) yMin = y;
                    if (z < zMin) zMin = z;
                    if (x + 1 > xMax) xMax = x + 1;
                    if (y + 1 > yMax) yMax = y + 1;
                    if (z + 1 > zMax) zMax = z + 1;
                }
            }
        }
        if (ids.isEmpty()) return null;
        byte[] safeSky = sky != null && sky.length == 2048 ? sky : defaultSkyLight();
        byte[] safeBlock = block != null && block.length == 2048 ? block : new byte[2048];
        var data = new SceneAssetCodec.SectionData(sx, sy, sz, List.copyOf(palette), cells, safeSky, safeBlock);
        return new Snapshot(data, crc(data), new SceneBounds(x0 + xMin, y0 + yMin, z0 + zMin, x0 + xMax, y0 + yMax, z0 + zMax));
    }

    public static byte[] defaultSkyLight() {
        byte[] sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xff);
        return sky;
    }

    public static byte[] captureLightBytes(Level level, LightLayer layer, SectionPos section) {
        var data = level.getLightEngine().getLayerListener(layer).getDataLayerData(section);
        if (data != null) return data.copy().getData();

        byte[] values = new byte[2048];
        if (layer == LightLayer.SKY && level.dimensionType().hasSkyLight()) {
            var position = new BlockPos.MutableBlockPos();
            for (int cell = 0; cell < 4096; cell++) {
                position.set(section.minBlockX() + (cell & 15), section.minBlockY() + (cell >> 8),
                        section.minBlockZ() + ((cell >> 4) & 15));
                values[cell >> 1] |= (byte) ((level.getBrightness(layer, position) & 15) << ((cell & 1) * 4));
            }
        }
        return values;
    }

    public static boolean hasRecordedLight(SceneAssetCodec.SectionData section) {
        if (section.skyLight.length != 2048) return false;
        for (byte sky : section.skyLight) if (sky != (byte) 0xff) return true;
        for (byte block : section.blockLight) if (block != 0) return true;
        return false;
    }

    public static int crc(SceneAssetCodec.SectionData data) {
        CRC32 crc = new CRC32();
        for (String state : data.palette) {
            crc.update(state.getBytes(StandardCharsets.UTF_8));
            crc.update(0);
        }
        for (short cell : data.blockIndices) {
            crc.update(cell & 255);
            crc.update((cell >>> 8) & 255);
        }
        if (hasRecordedLight(data)) {
            crc.update(data.skyLight);
            crc.update(data.blockLight);
        }
        return (int) crc.getValue();
    }

    public static SceneAssetCodec.SectionData relative(SceneAssetCodec.SectionData absolute, SceneBounds bounds) {
        return new SceneAssetCodec.SectionData(absolute.relX - bounds.minSectionX(),
                absolute.relY - bounds.minSectionY(), absolute.relZ - bounds.minSectionZ(),
                absolute.palette, absolute.blockIndices, absolute.skyLight, absolute.blockLight);
    }

    public static SceneAssetCodec.AssetData asset(ReplayMapManifest manifest, List<SceneAssetCodec.SectionData> parts) {
        return new SceneAssetCodec.AssetData(SharedConstants.getCurrentVersion().getDataVersion().getVersion(),
                manifest.dimension(), manifest.sourceBounds(), manifest.hash(), List.copyOf(parts));
    }
}
