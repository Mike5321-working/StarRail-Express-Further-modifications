package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.scene.asset.SceneAssetCodec.AssetData;
import com.habitrain.core.api.scene.asset.SceneAssetCodec.SectionData;
import com.habitrain.core.scene.client.SceneMeshBuilder;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import io.wifi.starrailexpress.content.block.DoorPartBlock;
import io.wifi.starrailexpress.content.block_entity.DoorBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

final class ReplayPovTerrain {
    private static @Nullable ReplayPovTerrain cached;

    private final AssetData asset;
    private final Long2ObjectOpenHashMap<SectionData> sections = new Long2ObjectOpenHashMap<>();
    private @Nullable List<DoorBlockEntity> doors;
    private final boolean recordedLight;

    private ReplayPovTerrain(AssetData asset) {
        this.asset = asset;
        boolean hasLight = false;
        var source = asset.sourceBounds;
        for (SectionData section : asset.sections) {
            int sectionX = source.minSectionX() + section.relX;
            int sectionY = source.minSectionY() + section.relY;
            int sectionZ = source.minSectionZ() + section.relZ;
            sections.putIfAbsent(SectionPos.asLong(sectionX, sectionY, sectionZ), section);
            hasLight |= ReplaySectionGeometry.hasRecordedLight(section);
        }
        recordedLight = hasLight;
    }

    private List<DoorBlockEntity> doors() {
        if (doors != null) return doors;
        var result = new ArrayList<DoorBlockEntity>();
        var source = asset.sourceBounds;
        for (SectionData section : asset.sections) {
            int sectionX = source.minSectionX() + section.relX;
            int sectionY = source.minSectionY() + section.relY;
            int sectionZ = source.minSectionZ() + section.relZ;
            var doorStates = new HashMap<Integer, BlockState>();
            for (int index = 1; index < section.palette.size(); index++) {
                BlockState state = SceneMeshBuilder.parseBlockState(section.palette.get(index));
                if (state.getBlock() instanceof DoorPartBlock) doorStates.put(index, state);
            }
            if (doorStates.isEmpty()) continue;
            for (int cell = 0; cell < section.blockIndices.length; cell++) {
                BlockState state = doorStates.get((int) section.blockIndices[cell]);
                if (state == null) continue;
                var pos = new BlockPos((sectionX << 4) + (cell & 15), (sectionY << 4) + (cell >> 8),
                        (sectionZ << 4) + ((cell >> 4) & 15));
                if (source.contains(pos) && ((DoorPartBlock) state.getBlock()).newBlockEntity(pos, state) instanceof DoorBlockEntity door) {
                    result.add(door);
                }
            }
        }
        doors = result;
        return result;
    }

    static @Nullable ReplayPovTerrain forAsset(@Nullable AssetData asset) {
        if (asset == null) return null;
        if (cached == null || cached.asset != asset) cached = new ReplayPovTerrain(asset);
        return cached;
    }

    static void reset() {
        cached = null;
    }

    boolean hasRecordedLight() { return recordedLight; }

    boolean isSolidVoxel(int x, int y, int z) {
        var source = asset.sourceBounds;
        if (x < 0 || y < 0 || z < 0 || x >= source.sizeX() || y >= source.sizeY() || z >= source.sizeZ()) {
            return false;
        }
        int wx = source.minX() + x, wy = source.minY() + y, wz = source.minZ() + z;
        var section = sections.get(SectionPos.asLong(wx >> 4, wy >> 4, wz >> 4));
        if (section == null) return false;
        int cell = ((wy & 15) << 8) | ((wz & 15) << 4) | (wx & 15);
        int paletteIndex = cell < section.blockIndices.length ? section.blockIndices[cell] & 0xFFFF : 0;
        if (paletteIndex <= 0 || paletteIndex >= section.palette.size()) return false;
        String id = section.palette.get(paletteIndex);
        // Keep the camera and corpse collision rules independent of the live world.
        return !id.contains("air") && !id.contains("glass") && !id.contains("door")
                && !id.contains("torch") && !id.contains("lamp") && !id.contains("sign")
                && !id.contains("carpet") && !id.contains("fence") && !id.contains("bars");
    }

    int lightAt(double x, double y, double z, int fallback) {
        if (!recordedLight) return fallback;
        int blockX = Mth.floor(x);
        int blockY = Mth.floor(y);
        int blockZ = Mth.floor(z);
        var section = sections.get(SectionPos.asLong(blockX >> 4, blockY >> 4, blockZ >> 4));
        if (section == null) return fallback;
        int cell = ((blockY & 15) << 8) | ((blockZ & 15) << 4) | (blockX & 15);
        return LightTexture.pack(lightNibble(section.blockLight, cell), lightNibble(section.skyLight, cell));
    }

    private static int lightNibble(byte[] data, int cell) {
        return (data[cell >> 1] >> ((cell & 1) * 4)) & 15;
    }

    void renderDoors(Minecraft client, Matrix4f sourceModel, MultiBufferSource buffers,
                     @Nullable ReplayDioramaMesh.DynamicWorld dynamicWorld, int fallbackLight) {
        var source = asset.sourceBounds;
        var stack = new PoseStack();
        for (DoorBlockEntity door : doors()) {
            BlockPos pos = door.getBlockPos();
            int localX = pos.getX() - source.minX();
            int localY = pos.getY() - source.minY();
            int localZ = pos.getZ() - source.minZ();
            if (dynamicWorld != null && dynamicWorld.manages(localX, localY, localZ)) continue;
            stack.pushPose();
            stack.mulPose(sourceModel);
            stack.translate(localX, localY, localZ);
            renderDoor(client, door, stack, buffers, doorLight(pos, fallbackLight));
            stack.popPose();
        }
    }

    int doorLight(BlockPos position, int fallbackLight) {
        int light = lightAt(position.getX(), position.getY(), position.getZ(), fallbackLight);
        int above = lightAt(position.getX(), position.getY() + 1, position.getZ(), light);
        return LightTexture.pack(Math.max(LightTexture.block(light), LightTexture.block(above)),
                Math.max(LightTexture.sky(light), LightTexture.sky(above)));
    }

    static boolean renderDoor(Minecraft client, DoorBlockEntity door, PoseStack stack,
                              MultiBufferSource buffers, int light) {
        var renderer = client.getBlockEntityRenderDispatcher().getRenderer(door);
        if (renderer == null) return false;
        renderer.render(door, 0, stack, buffers, light, OverlayTexture.NO_OVERLAY);
        return true;
    }
}
