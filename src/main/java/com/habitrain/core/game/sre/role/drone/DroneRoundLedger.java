package com.habitrain.core.game.sre.role.drone;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Match data survives despawning the entity or disconnecting its operator. */
final class DroneRoundLedger<W, P> {
    static final class Frame<P> {
        boolean hasDroneFrame = false;
        int stowedBattery = 0;
        boolean everHadDrone = false;
        double drainAccumulator;
        long scanReadyAt;
        @Nullable P stowedPayload = null;
        DronePayloadCategory stowedPayloadCategory = DronePayloadCategory.NONE;
        @Nullable UUID stowedPayloadMounter = null;

        boolean hasStowedPayload() {
            return stowedPayload != null && stowedPayloadCategory != null && stowedPayloadCategory.hasPayload();
        }

        void setStowedPayload(@Nullable P payload, @Nullable DronePayloadCategory category) {
            setStowedPayload(payload, category, null);
        }

        void setStowedPayload(@Nullable P payload, @Nullable DronePayloadCategory category, @Nullable UUID mounter) {
            if (payload == null || category == null || !category.hasPayload()) {
                clearStowedPayload();
            } else {
                this.stowedPayload = payload;
                this.stowedPayloadCategory = category;
                this.stowedPayloadMounter = mounter;
            }
        }

        void clearStowedPayload() {
            this.stowedPayload = null;
            this.stowedPayloadCategory = DronePayloadCategory.NONE;
            this.stowedPayloadMounter = null;
        }

        void destroyFrame() {
            this.hasDroneFrame = false;
            this.stowedBattery = 0;
            this.drainAccumulator = 0.0D;
            clearStowedPayload();
        }
    }

    private final Map<W, Map<UUID, Frame<P>>> rounds = new HashMap<>();

    Frame<P> frameFor(W world, UUID operator) {
        return rounds.computeIfAbsent(world, key -> new HashMap<>())
                .computeIfAbsent(operator, id -> new Frame<>());
    }

    void endRound(W world) {
        rounds.remove(world);
    }

    void clear() {
        rounds.clear();
    }
}
