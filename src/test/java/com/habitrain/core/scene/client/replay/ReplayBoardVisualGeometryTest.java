package com.habitrain.core.scene.client.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Geometric regression tests guarding against z-fighting, cutout texture misuse, missing chamfer
 * corners, protruding halos, and outward winding regressions on the replay table.
 */
class ReplayBoardVisualGeometryTest {

    private record Vertex(float x, float y, float z, float nx, float ny, float nz) {}

    private static final class RecordingConsumer implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>();
        private float x, y, z, nx, ny, nz;
        private boolean open;

        private void flushOpen() {
            if (open) {
                vertices.add(new Vertex(x, y, z, nx, ny, nz));
                open = false;
            }
        }

        List<Vertex> finish() {
            flushOpen();
            return vertices;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            flushOpen();
            this.x = x;
            this.y = y;
            this.z = z;
            this.nx = this.ny = this.nz = 0f;
            this.open = true;
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            return this;
        }
    }

    @Test
    void blockModelUsesSolidBrassAndHasZeroCoplanarExposedFacesOrVolumeOverlap() throws Exception {
        JsonObject root;
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("assets/habitrain_core/models/block/settlement_summary_board.json")) {
            assertNotNull(stream, "Missing settlement_summary_board.json block model");
            root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        JsonObject textures = root.getAsJsonObject("textures");
        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            String tex = entry.getValue().getAsString();
            assertFalse(tex.contains("gold_ledge"), "Transparent cutout texture gold_ledge must not be used on solid model: " + tex);
            assertFalse(tex.contains("gold_ornament_corner"), "Transparent cutout decal gold_ornament_corner must not be used: " + tex);
        }

        record BoxEl(int idx, double x0, double y0, double z0, double x1, double y1, double z1, JsonObject faces) {}
        List<BoxEl> boxes = new ArrayList<>();
        JsonArray elements = root.getAsJsonArray("elements");
        assertTrue(elements.size() >= 18);
        for (int i = 0; i < elements.size(); i++) {
            JsonObject el = elements.get(i).getAsJsonObject();
            JsonArray f = el.getAsJsonArray("from");
            JsonArray t = el.getAsJsonArray("to");
            boxes.add(new BoxEl(i,
                    f.get(0).getAsDouble(), f.get(1).getAsDouble(), f.get(2).getAsDouble(),
                    t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.get(2).getAsDouble(),
                    el.getAsJsonObject("faces")));
        }

        final double eps = 1e-5;
        String[] dirs = {"north", "south", "west", "east", "up", "down"};
        for (int i = 0; i < boxes.size(); i++) {
            BoxEl a = boxes.get(i);
            for (int j = i + 1; j < boxes.size(); j++) {
                BoxEl b = boxes.get(j);
                // Strictly disjoint 3D interior volumes (no interpenetrating boxes)
                boolean volOverlap = a.x0 + eps < b.x1 && b.x0 + eps < a.x1
                        && a.y0 + eps < b.y1 && b.y0 + eps < a.y1
                        && a.z0 + eps < b.z1 && b.z0 + eps < a.z1;
                assertFalse(volOverlap, "Elements " + a.idx + " and " + b.idx + " interpenetrate");

                // Strictly zero coplanar overlapping faces in the same direction
                for (String dir : dirs) {
                    if (!a.faces.has(dir) || !b.faces.has(dir)) continue;
                    boolean samePlane;
                    boolean overlap2d;
                    switch (dir) {
                        case "north" -> {
                            samePlane = Math.abs(a.z0 - b.z0) < eps;
                            overlap2d = a.x0 + eps < b.x1 && b.x0 + eps < a.x1 && a.y0 + eps < b.y1 && b.y0 + eps < a.y1;
                        }
                        case "south" -> {
                            samePlane = Math.abs(a.z1 - b.z1) < eps;
                            overlap2d = a.x0 + eps < b.x1 && b.x0 + eps < a.x1 && a.y0 + eps < b.y1 && b.y0 + eps < a.y1;
                        }
                        case "west" -> {
                            samePlane = Math.abs(a.x0 - b.x0) < eps;
                            overlap2d = a.z0 + eps < b.z1 && b.z0 + eps < a.z1 && a.y0 + eps < b.y1 && b.y0 + eps < a.y1;
                        }
                        case "east" -> {
                            samePlane = Math.abs(a.x1 - b.x1) < eps;
                            overlap2d = a.z0 + eps < b.z1 && b.z0 + eps < a.z1 && a.y0 + eps < b.y1 && b.y0 + eps < a.y1;
                        }
                        case "up" -> {
                            samePlane = Math.abs(a.y1 - b.y1) < eps;
                            overlap2d = a.x0 + eps < b.x1 && b.x0 + eps < a.x1 && a.z0 + eps < b.z1 && b.z0 + eps < a.z1;
                        }
                        default -> {
                            samePlane = Math.abs(a.y0 - b.y0) < eps;
                            overlap2d = a.x0 + eps < b.x1 && b.x0 + eps < a.x1 && a.z0 + eps < b.z1 && b.z0 + eps < a.z1;
                        }
                    }
                    assertFalse(samePlane && overlap2d,
                            "Coplanar z-fighting face '" + dir + "' between elements " + a.idx + " and " + b.idx);
                }
            }
        }
    }

    @Test
    void chamferOctagonFillOutlineAndHaloAreExactFlatAndNonProtruding() {
        Matrix4f id = new Matrix4f();
        float hw = 1.30f, hh = 0.18f, c = 0.036f, z = 0.005f;

        // 1. Filled chamfered octagon must cover exact octagon area (including all 4 diagonal corners)
        RecordingConsumer fillRec = new RecordingConsumer();
        ReplayDraw.chamferRectXY(fillRec, id, -hw, -hh, hw, hh, z, c, 0xFFFFFFFF, 0xFF000000);
        List<Vertex> fillVerts = fillRec.finish();
        assertEquals(12, fillVerts.size(), "3 quads = 12 vertices");
        double totalArea = 0.0;
        for (int i = 0; i < fillVerts.size(); i += 4) {
            Vertex v0 = fillVerts.get(i), v1 = fillVerts.get(i + 1), v2 = fillVerts.get(i + 2), v3 = fillVerts.get(i + 3);
            for (Vertex v : List.of(v0, v1, v2, v3)) {
                assertEquals(z, v.z(), 1e-6f);
                assertTrue(Math.abs(v.x()) <= hw + 1e-5f && Math.abs(v.y()) <= hh + 1e-5f);
                assertTrue(Math.abs(v.x()) + Math.abs(v.y()) <= hw + hh - c + 1e-5f);
            }
            totalArea += quadAreaXY(v0, v1, v2, v3);
        }
        double expectedArea = (2.0 * hw) * (2.0 * hh) - 2.0 * c * c;
        assertEquals(expectedArea, totalArea, 1e-4, "Chamfer fill must fill all 4 diagonal bevel corners without holes");

        // 2. Flat mitered outline stays strictly inside octagon at constant z with uniform thickness
        float stroke = 0.010f;
        RecordingConsumer outRec = new RecordingConsumer();
        ReplayDraw.chamferOutlineXY(outRec, id, hw, hh, c, z, stroke, 0xFFFFD080);
        List<Vertex> outVerts = outRec.finish();
        assertEquals(32, outVerts.size(), "8 mitered border edges = 32 vertices");
        for (Vertex v : outVerts) {
            assertEquals(z, v.z(), 1e-6f);
            assertTrue(Math.abs(v.x()) <= hw + 1e-5f && Math.abs(v.y()) <= hh + 1e-5f);
            assertTrue(Math.abs(v.x()) + Math.abs(v.y()) <= hw + hh - c + 1e-5f);
        }

        // 3. Outward chamfer halo hugs the exact beveled perimeter without rectangular corner ears
        float spread = 0.04f;
        RecordingConsumer haloRec = new RecordingConsumer();
        ReplayDraw.chamferHaloXY(haloRec, id, hw, hh, c, z, spread, 0x80FFFFFF, 0x00FFFFFF);
        List<Vertex> haloVerts = haloRec.finish();
        assertEquals(32, haloVerts.size(), "8 mitered halo trapezoids = 32 vertices");
        double maxDiag = hw + hh - c + spread * Math.sqrt(2.0);
        for (Vertex v : haloVerts) {
            assertEquals(z, v.z(), 1e-6f);
            assertTrue(Math.abs(v.x()) <= hw + spread + 1e-5f);
            assertTrue(Math.abs(v.y()) <= hh + spread + 1e-5f);
            assertTrue(Math.abs(v.x()) + Math.abs(v.y()) <= maxDiag + 1e-4,
                    "Halo corner protruded past beveled perimeter: " + v);
        }
    }

    @Test
    void texturedBoxEmitsOutwardCounterClockwiseWindingForBackfaceCulling() throws Exception {
        // Allocate a dummy sprite with 0..1 UVs via Unsafe (only u0/u1/v0/v1 fields are read by getU/getV)
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Unsafe unsafe = (Unsafe) theUnsafe.get(null);
        TextureAtlasSprite dummy = (TextureAtlasSprite) unsafe.allocateInstance(TextureAtlasSprite.class);

        RecordingConsumer rec = new RecordingConsumer();
        PoseStack stack = new PoseStack();
        ReplayDraw.box(rec, stack.last(), -1f, -2f, -3f, 1f, 2f, 3f, dummy, 0xFFFFFFFF, dummy, 0xFFFFFFFF, 0xF000F0, 10f);
        List<Vertex> verts = rec.finish();
        assertEquals(24, verts.size(), "6 untiled faces = 24 vertices");
        for (int i = 0; i < verts.size(); i += 4) {
            Vertex v0 = verts.get(i), v1 = verts.get(i + 1), v2 = verts.get(i + 2);
            Vector3f e1 = new Vector3f(v1.x() - v0.x(), v1.y() - v0.y(), v1.z() - v0.z());
            Vector3f e2 = new Vector3f(v2.x() - v0.x(), v2.y() - v0.y(), v2.z() - v0.z());
            Vector3f cross = e1.cross(e2, new Vector3f());
            Vector3f normal = new Vector3f(v0.nx(), v0.ny(), v0.nz());
            assertTrue(cross.dot(normal) > 0f,
                    "Face " + (i / 4) + " is wound clockwise (would be culled by entitySolid): cross=" + cross + ", normal=" + normal);
        }
    }

    @Test
    void consoleApronTimelinePlayheadAndVolumeKnobClearAllAdjacentFrames() {
        float ebonyBottom = -(ReplayTrayPainter.PLAQUE_H * 0.5f + ReplayTrayPainter.PLAQUE_EBONY_PAD);
        assertTrue(ReplayBoardButtons.DECK_Y1 < ebonyBottom - 0.008f,
                "Lower console top (" + ReplayBoardButtons.DECK_Y1 + ") must clear upper plaque ebony chassis (" + ebonyBottom + ")");

        float innerGlassHalfW = ReplayTrayPainter.BASE_PLAQUE_W * 0.5f - ReplayTrayPainter.PLAQUE_BEZEL;
        float timelineHaloRadius = 0.046f;
        assertTrue(ReplayBoardButtons.TIMELINE_X0 - timelineHaloRadius >= -innerGlassHalfW,
                "Timeline playhead halo overflows left brass bezel at 0%");
        assertTrue(ReplayBoardButtons.TIMELINE_X1 + timelineHaloRadius <= innerGlassHalfW,
                "Timeline playhead halo overflows right brass bezel at 100%");

        float volHaloRadius = 0.033f;
        float x0 = ReplayBoardButtons.sliderXFromVolume(0.0f);
        float x50 = ReplayBoardButtons.sliderXFromVolume(0.5f);
        float x100 = ReplayBoardButtons.sliderXFromVolume(1.0f);
        assertTrue(x0 - volHaloRadius >= ReplayBoardButtons.VOL_PANEL_X0, "0% volume playhead overflows left wing border");
        assertTrue(x50 + volHaloRadius < ReplayBoardButtons.VOL_MUTE_X0, "50% volume playhead overlaps mute medallion");
        assertTrue(x100 + volHaloRadius <= ReplayBoardButtons.VOL_PANEL_X1, "100% volume playhead overflows right wing border");
    }

    private static double quadAreaXY(Vertex a, Vertex b, Vertex c, Vertex d) {
        double tri1 = 0.5 * Math.abs((b.x() - a.x()) * (c.y() - a.y()) - (c.x() - a.x()) * (b.y() - a.y()));
        double tri2 = 0.5 * Math.abs((c.x() - a.x()) * (d.y() - a.y()) - (d.x() - a.x()) * (c.y() - a.y()));
        return tri1 + tri2;
    }
}
