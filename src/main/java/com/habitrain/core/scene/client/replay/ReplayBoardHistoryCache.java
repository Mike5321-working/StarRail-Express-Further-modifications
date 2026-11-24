package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Reuses clipped, camera-relative glow vertices while a table holds a settled review. */
final class ReplayBoardHistoryCache {
    private static final long STABLE_VIEW_NS = 200_000_000L;
    // The fixed replay glow pass uses 3 floats and one packed color per vertex.
    private static final int MAX_VERTICES = 96 * 1024;
    private static final Geometry EMPTY = new Geometry(new int[0], 0);
    private static final Geometry TOO_LARGE = new Geometry(new int[0], 0);

    record View(ReplayBoardModel model, double tick, Matrix4fc board, Matrix4fc diorama,
                float scale, float fade, float halfWidth, float halfDepth, boolean detail, double focusZoom,
                ReplayFullPlayerRenderer.HighlightSpec highlight) {}

    /** Consecutive static markers followed by an optional animated kill icon in original draw order. */
    record Markers(int from, int to, int weapon) {}

    private View view;
    private long changedAt;
    private Geometry[] geometry;
    private List<Markers> markers;
    private int reserved;

    boolean prepare(View next, long now) {
        if (!next.equals(view)) {
            view = next;
            changedAt = now;
            geometry = null;
            markers = null;
            reserved = 0;
            return false;
        }
        if (now - changedAt < STABLE_VIEW_NS) return false;
        if (geometry == null) {
            markers = markerRuns(next);
            geometry = new Geometry[next.model().actors.length + markers.size()];
        }
        return true;
    }

    List<Markers> markers() {
        return markers;
    }

    private static List<Markers> markerRuns(View view) {
        var events = view.model().events;
        List<Markers> runs = new ArrayList<>();
        int first = 0;
        if (view.fade() > 0.5f) {
            for (int i = 0; i < events.length; i++) {
                var event = events[i].node;
                if (event.kind() == LastMatchReplay.Kind.KILL && event.tick() <= view.tick()) {
                    runs.add(new Markers(first, i + 1, i));
                    first = i + 1;
                }
            }
        }
        if (first < events.length) runs.add(new Markers(first, events.length, -1));
        return runs;
    }

    boolean emit(ReplayFrame.MapScope scope, int slot) {
        Geometry cached = geometry[slot];
        if (cached == null || cached == TOO_LARGE) return false;
        if (cached != EMPTY) {
            // Finish the preceding clipped primitive before replaying directly into the same layer.
            scope.glow.close();
            cached.emit(scope.glowBuffer);
        }
        return true;
    }

    void record(ReplayFrame.MapScope scope, int slot, Consumer<VertexConsumer> draw) {
        if (geometry[slot] == TOO_LARGE) {
            draw.accept(scope.glow);
            return;
        }
        scope.glow.close();
        var recording = new Recording(scope.glowBuffer);
        try (var clipped = scope.clipGlow(recording)) {
            draw.accept(clipped);
        }
        geometry[slot] = recording.finish();
    }

    private record Geometry(int[] vertices, int size) {
        void emit(VertexConsumer out) {
            for (int i = 0; i < size; i += 4) {
                out.addVertex(Float.intBitsToFloat(vertices[i]), Float.intBitsToFloat(vertices[i + 1]),
                        Float.intBitsToFloat(vertices[i + 2])).setColor(vertices[i + 3]);
            }
        }
    }

    /** Records the output of the normal clip path while submitting this frame's geometry. */
    private final class Recording implements VertexConsumer {
        private final VertexConsumer target;
        private int[] data = EMPTY.vertices();
        private int size;
        private boolean overBudget;

        Recording(VertexConsumer target) {
            this.target = target;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            target.addVertex(x, y, z);
            if (!overBudget && size == data.length) {
                int current = data.length / 4;
                int remaining = MAX_VERTICES - reserved;
                int growth = Math.min(Math.max(64, current), remaining);
                if (growth > 0) {
                    data = Arrays.copyOf(data, data.length + growth * 4);
                    reserved += growth;
                } else {
                    reserved -= current;
                    data = EMPTY.vertices();
                    overBudget = true;
                }
            }
            if (!overBudget) {
                data[size++] = Float.floatToRawIntBits(x);
                data[size++] = Float.floatToRawIntBits(y);
                data[size++] = Float.floatToRawIntBits(z);
                data[size++] = -1;
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            target.setColor(r, g, b, a);
            if (!overBudget) data[size - 1] = a << 24 | r << 16 | g << 8 | b;
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { target.setNormal(x, y, z); return this; }

        Geometry finish() {
            if (overBudget) return TOO_LARGE;
            if (size == 0) return EMPTY;
            return new Geometry(data, size);
        }
    }
}

