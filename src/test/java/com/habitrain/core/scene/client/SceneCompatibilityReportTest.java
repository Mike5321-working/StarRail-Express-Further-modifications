package com.habitrain.core.scene.client;

import com.habitrain.core.api.client.scene.compat.SceneMaterialKey;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SceneCompatibilityReportTest {
    @Test
    void sequentialReplayDiagnosticsCountEachPositionOnceAndRetainOnlyIssues() {
        var report = new SceneCompatibilityReport(false, true);
        var first = new BlockPos(0, 0, 0);
        report.recordNormalBlock(first, false);
        report.recordNormalBlock(first, false);
        report.addEntry(new SceneCompatibilityReport.Entry(first, null, null, null, 0, false, false,
                SceneCompatibilityReport.IssueType.MISSING_TEXTURE, "", 0, null, 0, 0));
        report.recordNormalBlock(new BlockPos(1, 0, 0), true);
        report.recordNormalBlock(new BlockPos(2, 0, 0), false);
        report.freeze();
        assertEquals(3, report.totalBlocks());
        assertEquals(1, report.normalBlocks());
        assertEquals(1, report.adaptedBlocks());
        assertEquals(1, report.severeBlocks());
        assertEquals(1, report.getEntries().size());
    }

    @Test
    void mutableBlockPosDoesNotAliasAcrossSequentialOrHashedDiagnostics() {
        var sequential = new SceneCompatibilityReport(false, true);
        var hashed = new SceneCompatibilityReport(false, false);
        var cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 5; x++) {
            cursor.set(x, 2, 3);
            sequential.recordNormalBlock(cursor, x % 2 == 0);
            hashed.recordNormalBlock(cursor, x % 2 == 0);
        }
        cursor.set(9, 9, 9);
        sequential.addEntry(new SceneCompatibilityReport.Entry(cursor, null, null, null, 0, false, false,
                SceneCompatibilityReport.IssueType.MISSING_TEXTURE, "missing", 0, null, 0, 0));
        cursor.set(0, 0, 0);
        sequential.freeze();
        hashed.freeze();

        assertEquals(6, sequential.totalBlocks());
        assertEquals(3, sequential.adaptedBlocks());
        assertEquals(2, sequential.normalBlocks());
        assertEquals(1, sequential.severeBlocks());
        assertEquals(new BlockPos(9, 9, 9), sequential.getEntries().getFirst().localPos());
        assertEquals(5, hashed.totalBlocks());
        assertEquals(3, hashed.adaptedBlocks());
        assertEquals(2, hashed.normalBlocks());
    }

    @Test
    void unclippedShaderAllowsAxisScanPlanesAndRejectsVerticalOrNegativePlanes() {
        assertTrue(SceneShaders.supportsUnclippedClipPlane(new float[]{0f, 0f, 0f, 1f}));
        assertTrue(SceneShaders.supportsUnclippedClipPlane(new float[]{1f, 0f, 0f, 12f}));
        assertTrue(SceneShaders.supportsUnclippedClipPlane(new float[]{0f, 0f, 1f, 12f}));
        assertFalse(SceneShaders.supportsUnclippedClipPlane(new float[]{0f, 1f, 0f, 12f}));
        assertFalse(SceneShaders.supportsUnclippedClipPlane(new float[]{-1f, 0f, 0f, 12f}));
        assertFalse(SceneShaders.supportsUnclippedClipPlane(new float[]{0f, 0f, -1f, 12f}));
    }

    @Test
    void replayOccluderAllowsOpaqueFullCubesAndRejectsNonCubesCutoutAndBlockEntities() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var pos = BlockPos.ZERO;
        var empty = EmptyBlockGetter.INSTANCE;
        assertTrue(SceneMeshBuilder.isReplayOccluderCandidate(Blocks.STONE.defaultBlockState()));
        assertTrue(Blocks.STONE.defaultBlockState().isSolidRender(empty, pos));
        assertTrue(net.minecraft.world.level.block.Block.isShapeFullBlock(Blocks.STONE.defaultBlockState().getShape(empty, pos)));

        assertFalse(SceneMeshBuilder.isReplayOccluderCandidate(Blocks.GLASS.defaultBlockState()));
        assertFalse(SceneMeshBuilder.isReplayOccluderCandidate(Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse(SceneMeshBuilder.isReplayOccluderCandidate(Blocks.CHEST.defaultBlockState()));
        assertFalse(SceneMeshBuilder.isReplayOccluderCandidate(Blocks.WATER.defaultBlockState()));
        assertFalse(Blocks.OAK_STAIRS.defaultBlockState().isSolidRender(empty, pos));
        assertFalse(SceneMeshBuilder.isReplayOccluderCandidate(Blocks.AIR.defaultBlockState()));
    }


    @Test
    public void testEmptyReportIsCompatible() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        assertTrue(report.isCompatible());
        assertFalse(report.hasBlockingIssues());
        assertEquals(0, report.totalBlocks());
        assertEquals(0, report.adaptedBlocks());
        assertTrue(report.getIssues().isEmpty());
    }

    @Test
    public void testReportSuccessfulAdaptation() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        ResourceLocation blockId = ResourceLocation.parse("mymod:custom_block");
        ResourceLocation adapterId = ResourceLocation.parse("mymod:custom_adapter");

        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(1, 2, 3),
                blockId,
                "CustomBlockModel",
                adapterId,
                1,
                true,
                true,
                SceneCompatibilityReport.IssueType.NONE,
                "Success",
                24,
                SceneMaterialKey.SOLID,
                0,
                15
        ));

        assertTrue(report.isCompatible());
        assertFalse(report.hasBlockingIssues());
        assertEquals(1, report.totalBlocks());
        assertEquals(1, report.adaptedBlocks());
        assertEquals(0, report.getIssues().size());
    }

    @Test
    public void testReportBlockingIssues() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();

        // Add non-blocking skipped entry
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(0, 0, 0),
                ResourceLocation.parse("test:skipped_block"),
                "SkippedModel",
                null, 0, false, false,
                SceneCompatibilityReport.IssueType.SKIPPED,
                "Skipped intentionally", 0, null, 0, 15
        ));
        assertFalse(report.hasBlockingIssues());
        assertTrue(report.isCompatible(), "SKIPPED is not considered an error issue");

        // Add missing texture blocking issue
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(1, 1, 1),
                ResourceLocation.parse("test:missing_texture_block"),
                "MissingTexModel",
                null, 0, false, false,
                SceneCompatibilityReport.IssueType.MISSING_TEXTURE,
                "Missing texture", 0, null, 0, 15
        ));
        assertFalse(report.isCompatible());
        assertTrue(report.hasBlockingIssues());
        assertEquals(1, report.getIssues().size());
        assertEquals(1, report.getIssuesByType(SceneCompatibilityReport.IssueType.MISSING_TEXTURE).size());

        // Add missing adapter blocking issue
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(2, 2, 2),
                ResourceLocation.parse("test:tile_entity_block"),
                "TileEntityModel",
                null, 0, true, false,
                SceneCompatibilityReport.IssueType.MISSING_ADAPTER,
                "Missing adapter for block entity", 0, null, 0, 15
        ));
        assertEquals(2, report.getIssues().size());
        assertEquals(2, report.getBlockingIssues().size());
        assertEquals(1, report.getIssuesByType(SceneCompatibilityReport.IssueType.MISSING_ADAPTER).size());

        // Check summary string format
        String summary = report.toSummaryString();
        assertTrue(summary.contains("issues=2"));
        assertTrue(summary.contains("blocking=2"));
        assertTrue(summary.contains("compatible=false"));
    }
}
