package com.habitrain.core.api.match.replay;

import com.habitrain.core.api.scene.SceneLimits;
import com.habitrain.core.api.scene.model.SceneBounds;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** The stable list index is the section's wire identifier. Offsets are relative to sourceBounds.minSection*. */
public record ReplayMapManifest(String mapKey, String dimension, SceneBounds sourceBounds,
                                String hash, List<Entry> sections) {
    public static final int MAX_SECTIONS = 6144;

    public ReplayMapManifest {
        Objects.requireNonNull(mapKey);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(sourceBounds);
        if (!hash.matches("[0-9a-f]{64}") || sections.isEmpty() || sections.size() > MAX_SECTIONS
                || !supportsBounds(sourceBounds)) {
            throw new IllegalArgumentException("Invalid replay manifest");
        }
        sections = List.copyOf(sections);
        var seen = new HashSet<Section>();
        for (Entry entry : sections) {
            Section p = entry.section();
            if (p.x < 0 || p.x >= sourceBounds.sectionCountX()
                    || p.y < 0 || p.y >= sourceBounds.sectionCountY()
                    || p.z < 0 || p.z >= sourceBounds.sectionCountZ() || !seen.add(p)) {
                throw new IllegalArgumentException("Invalid replay section position");
            }
        }
    }

    public static boolean supportsBounds(SceneBounds bounds) {
        return !bounds.isEmpty() && bounds.totalSections() <= SceneLimits.MAX_MESH_SECTIONS
                && bounds.sizeX() <= SceneLimits.MAX_AXIS_LENGTH && bounds.sizeY() <= SceneLimits.MAX_AXIS_LENGTH
                && bounds.sizeZ() <= SceneLimits.MAX_AXIS_LENGTH;
    }

    public BitSet missing(Function<Section, Integer> cachedFingerprint) {
        BitSet result = new BitSet(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            Entry entry = sections.get(i);
            if (!Objects.equals(entry.crc32(), cachedFingerprint.apply(entry.section()))) result.set(i);
        }
        return result;
    }

    public record Section(int x, int y, int z) {}
    public record Entry(Section section, int crc32) {}
}
