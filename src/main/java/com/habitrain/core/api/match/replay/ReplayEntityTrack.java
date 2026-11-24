package com.habitrain.core.api.match.replay;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Recorded lifecycle and motion track of one non-player gameplay entity or persistent area-of-effect zone
 * (grenades, throwing knives, drones, idiot chicks/eggs, tailor dolls, corpses & dragging, dropped guns,
 * placed C4, traps, smoke/fire/slime/poison/fog zones).
 *
 * <p>Positions are relative to {@code LastMatchReplay.origin().minX/Y/Z()}, matching player tracks.</p>
 */
public record ReplayEntityTrack(
        UUID entityId,
        EntityKind kind,
        String entityTypeId,
        @Nullable UUID ownerUuid,
        @Nullable UUID targetUuid,
        float radius,
        List<EntitySample> samples
) {
    public static final int MAX_TRACKS = 512;
    public static final int MAX_SAMPLES_PER_TRACK = 1200;
    public static final int MAX_TYPE_ID_LENGTH = 128;

    public enum EntityKind {
        GRENADE,
        PROJECTILE,
        DRONE,
        IDIOT_CHICK,
        IDIOT_EGG,
        TAILOR_DOLL,
        CORPSE,
        DROPPED_ITEM,
        PLACED_C4,
        TRAP,
        ZONE_SMOKE,
        ZONE_FIRE,
        ZONE_SLIME,
        ZONE_POISON,
        ZONE_FOG
    }

    public static final int FLAG_ACTIVE    = 1 << 0;
    public static final int FLAG_BREAK     = 1 << 1;
    public static final int FLAG_DRAGGED   = 1 << 2;
    public static final int FLAG_CORRUPTED = 1 << 3;
    public static final int FLAG_HIDDEN    = 1 << 4;
    public static final int FLAG_COMPLETE  = 1 << 5;
    public static final int FLAG_ARMED     = 1 << 6;

    public record EntitySample(int tick, LastMatchReplay.Position position, byte yaw, byte pitch, int flags) {
        public EntitySample {
            if (tick < 0 || tick > LastMatchReplay.MAX_TICKS) {
                throw new IllegalArgumentException("Invalid entity sample tick: " + tick);
            }
            Objects.requireNonNull(position, "position");
        }

        public float yawDegrees() {
            return Byte.toUnsignedInt(yaw) * 360.0f / 256.0f;
        }

        public float pitchDegrees() {
            return Math.clamp(pitch * (90.0f / 127.0f), -90.0f, 90.0f);
        }

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    /** Interpolated snapshot of this entity at a fractional match tick. */
    public record Sampled(double x, double y, double z, float yawDeg, float pitchDeg, int flags, boolean active) {
        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    public ReplayEntityTrack {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(kind, "kind");
        entityTypeId = entityTypeId == null ? "" : entityTypeId;
        if (entityTypeId.length() > MAX_TYPE_ID_LENGTH) {
            throw new IllegalArgumentException("Entity type id too long: " + entityTypeId.length());
        }
        if (!Float.isFinite(radius) || radius < 0f || radius > 128f) {
            throw new IllegalArgumentException("Invalid entity radius: " + radius);
        }
        samples = samples == null ? List.of() : List.copyOf(samples);
        if (samples.size() > MAX_SAMPLES_PER_TRACK) {
            throw new IllegalArgumentException("Too many entity samples: " + samples.size());
        }
    }

    public int firstTick() {
        return samples.isEmpty() ? -1 : samples.getFirst().tick();
    }

    public int lastTick() {
        return samples.isEmpty() ? -1 : samples.getLast().tick();
    }

    /** O(log N) pose query at any fractional match tick; returns {@code null} when entity is not yet spawned or despawned. */
    public @Nullable Sampled sampleAt(double tick) {
        int n = samples.size();
        if (n == 0 || tick < samples.getFirst().tick()) return null;
        int lo = 0, hi = n - 1, idx = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).tick() <= tick) {
                idx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        EntitySample a = samples.get(idx);
        if (!a.has(FLAG_ACTIVE)) return null;
        int next = idx + 1;
        if (next >= n) {
            return new Sampled(a.position().blockX(), a.position().blockY(), a.position().blockZ(),
                    a.yawDegrees(), a.pitchDegrees(), a.flags(), true);
        }
        EntitySample b = samples.get(next);
        if (b.has(FLAG_BREAK) || !b.has(FLAG_ACTIVE) || b.tick() <= a.tick()) {
            return new Sampled(a.position().blockX(), a.position().blockY(), a.position().blockZ(),
                    a.yawDegrees(), a.pitchDegrees(), a.flags(), true);
        }
        double u = Math.clamp((tick - a.tick()) / (double) (b.tick() - a.tick()), 0.0, 1.0);
        double x = a.position().blockX() + (b.position().blockX() - a.position().blockX()) * u;
        double y = a.position().blockY() + (b.position().blockY() - a.position().blockY()) * u;
        double z = a.position().blockZ() + (b.position().blockZ() - a.position().blockZ()) * u;
        float yaw = lerpAngle(a.yawDegrees(), b.yawDegrees(), (float) u);
        float pitch = a.pitchDegrees() + (b.pitchDegrees() - a.pitchDegrees()) * (float) u;
        return new Sampled(x, y, z, yaw, pitch, a.flags(), true);
    }

    private static float lerpAngle(float from, float to, float t) {
        float delta = ((to - from) % 360f + 540f) % 360f - 180f;
        float r = (from + delta * t) % 360f;
        return r < 0 ? r + 360f : r;
    }
}
