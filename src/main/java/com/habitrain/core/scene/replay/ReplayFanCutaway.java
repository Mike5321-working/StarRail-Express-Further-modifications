package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.model.SceneBounds;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Per-frame, mesh-local fan openings shared by the renderer and geometry tests. */
public final class ReplayFanCutaway {
    public static final int MAX_FANS = LastMatchReplay.MAX_PLAYERS;
    public static final float HALF_ANGLE_TANGENT = (float) Math.tan(Math.toRadians(40));
    public static final float EDGE_WIDTH = 0.55f;
    // Four vec4 anchors per mat4: Minecraft's uniform loader supports matrix arrays directly.
    public static final int PACKED_FLOATS = MAX_FANS * 4;

    private final float[] anchors = new float[PACKED_FLOATS];
    private final float[] projected = new float[PACKED_FLOATS];
    private final float[] selected = new float[PACKED_FLOATS];
    private int count;
    private float directionX, directionZ = 1;
    private boolean projectionDirty = true;

    /** Shader upload view. Consume immediately, before selecting another batch. */
    public record ShaderFans(float[] anchors, int count, float directionX, float directionZ) {}

    public void clear() { count = 0; }
    public int count() { return count; }
    public float[] anchors() { return anchors; }
    public float directionX() { return directionX; }
    public float directionZ() { return directionZ; }

    /** Camera-relative source transform, including table facing, source rotation and display edits. */
    public void faceViewer(Matrix4f model, Vector3f look, SceneBounds source) {
        Matrix4f inverse = new Matrix4f(model).invert();
        Vector3f towards = inverse.transformDirection(new Vector3f(look).negate());
        if (Math.hypot(towards.x, towards.z) < towards.length() * 1.0e-4) {
            // Looking straight down has no horizontal facing; use the camera's map side.
            towards = inverse.transformPosition(new Vector3f());
            towards.x -= source.sizeX() * 0.5f;
            towards.z -= source.sizeZ() * 0.5f;
        }
        direction(towards.x, towards.z);
    }

    /** Direction from the replay avatars towards the viewer, expressed in source-map axes. */
    public void direction(double x, double z) {
        projectionDirty = true;
        double length = Math.hypot(x, z);
        if (!Double.isFinite(length) || length < 1.0e-5) {
            directionX = 0;
            directionZ = 1;
        } else {
            directionX = (float) (x / length);
            directionZ = (float) (z / length);
        }
    }

    /** Adds both living heads and visible death markers; departed/unsampled players do not cut. */
    public void add(ReplayTrackSampler.Pose pose, SceneBounds origin, SceneBounds source, double coreRadius) {
        if (!pose.visible() || count >= MAX_FANS || !Double.isFinite(coreRadius) || coreRadius <= 0) return;
        double x = origin.minX() - source.minX() + pose.x();
        double y = origin.minY() - source.minY() + pose.y();
        double z = origin.minZ() - source.minZ() + pose.z();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        int offset = count++ * 4;
        projectionDirty = true;
        anchors[offset] = (float) x;
        anchors[offset + 1] = (float) y;
        anchors[offset + 2] = (float) z;
        anchors[offset + 3] = (float) coreRadius;
    }

    public ShaderFans shaderFans() {
        projectAnchors();
        return new ShaderFans(projected, count, directionX, directionZ);
    }

    /** Conservatively reject openings which cannot reach any fragment in this batch. */
    public ShaderFans shaderFans(double minX, double minZ, double maxX, double maxY, double maxZ) {
        int kept = selectAnchorsInto(minX, minZ, maxX, maxY, maxZ, selected);
        return new ShaderFans(selected, kept, directionX, directionZ);
    }

    /**
     * Conservatively selects fan anchors affecting the given AABB into caller-provided {@code dest}
     * (length &ge; {@link #PACKED_FLOATS}) and returns the kept fan count.
     */
    public int selectAnchorsInto(double minX, double minZ, double maxX, double maxY, double maxZ, float[] dest) {
        if (count == 0) return 0;
        projectAnchors();
        // The fragment shader moves its XZ sample halfway towards the owning voxel centre.
        double cx = (minX + maxX) * 0.5, cz = (minZ + maxZ) * 0.5;
        double hx = (maxX - minX) * 0.5 + 0.26, hz = (maxZ - minZ) * 0.5 + 0.26;
        double forwardMax = cx * directionX + cz * directionZ + hx * Math.abs(directionX) + hz * Math.abs(directionZ);
        double side = cx * directionZ - cz * directionX;
        double sideExtent = hx * Math.abs(directionZ) + hz * Math.abs(directionX);
        int kept = 0;
        for (int i = 0; i < count * 4; i += 4) {
            if (maxY + 0.51 <= projected[i + 1] + 0.01) continue;
            double radius = projected[i + 3] + EDGE_WIDTH;
            double forward = forwardMax - projected[i];
            double sideways = Math.abs(side - projected[i + 2]) - sideExtent;
            if (forward < -radius || sideways > radius + Math.max(0, forward) * HALF_ANGLE_TANGENT) continue;
            System.arraycopy(projected, i, dest, kept++ * 4, 4);
        }
        return kept;
    }

    private void projectAnchors() {
        if (!projectionDirty) return;
        for (int i = 0; i < count * 4; i += 4) {
            float x = anchors[i], z = anchors[i + 2];
            projected[i] = x * directionX + z * directionZ;
            projected[i + 1] = anchors[i + 1];
            projected[i + 2] = x * directionZ - z * directionX;
            projected[i + 3] = anchors[i + 3];
        }
        projectionDirty = false;
    }

    /** Voxel-centre cutoff used by the separate dynamic block model pass. */
    public boolean removes(double x, double y, double z) {
        for (int i = 0; i < count; i++) {
            int offset = i * 4;
            if (y <= anchors[offset + 1] + 0.01) continue;
            double dx = x - anchors[offset], dz = z - anchors[offset + 2];
            double radius = anchors[offset + 3];
            if (dx * dx + dz * dz <= radius * radius) return true;
            double forward = dx * directionX + dz * directionZ;
            double sideways = Math.abs(dx * directionZ - dz * directionX);
            if (forward >= 0 && sideways <= radius + forward * HALF_ANGLE_TANGENT) return true;
        }
        return false;
    }
}
