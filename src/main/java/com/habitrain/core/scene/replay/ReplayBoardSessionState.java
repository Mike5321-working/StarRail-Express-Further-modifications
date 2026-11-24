package com.habitrain.core.scene.replay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Ephemeral single-session playback state for one in-world replay table.
 *
 * <p><strong>Persistence rule:</strong> This state is strictly transient (in-memory + live network /
 * chunk-update sync only). It must NEVER be serialized to persistent block-entity NBT
 * ({@code saveAdditional}). Ending the session or starting a new round resets all parameters to
 * defaults ({@code speed = 1.0x}, {@code killCloseupEnabled = false}, {@code roofCutaway = true},
 * {@code followPlayerUuid = null}).</p>
 */
public record ReplayBoardSessionState(
        UUID roundId,
        boolean shared,
        boolean preparing,
        long anchorGameTick,
        double anchorReplayTick,
        float speed,
        boolean paused,
        @Nullable UUID followPlayerUuid,
        boolean killCloseupEnabled,
        boolean roofCutaway,
        boolean endedToIdle,
        int revision
) {
    public static final float DEFAULT_SPEED = 1.0f;
    public static final float[] ALLOWED_SPEEDS = {0.5f, 1.0f, 2.0f, 4.0f};

    public enum Action {
        PLAY,
        PAUSE,
        TOGGLE_PAUSE,
        SEEK,
        STEP,
        SET_SPEED,
        SET_FOLLOW,
        SET_KILL_CLOSEUP,
        SET_ROOF_CUTAWAY,
        END_TO_IDLE
    }

    public ReplayBoardSessionState {
        Objects.requireNonNull(roundId, "roundId");
        speed = sanitizeSpeed(speed);
        if (!Double.isFinite(anchorReplayTick) || anchorReplayTick < 0.0) anchorReplayTick = 0.0;
        if (revision < 0) revision = 0;
    }

    public static float sanitizeSpeed(float raw) {
        if (!Float.isFinite(raw)) return DEFAULT_SPEED;
        float best = DEFAULT_SPEED;
        float bestDist = Float.MAX_VALUE;
        for (float allowed : ALLOWED_SPEEDS) {
            float d = Math.abs(raw - allowed);
            if (d < bestDist) {
                bestDist = d;
                best = allowed;
            }
        }
        return best;
    }

    public static ReplayBoardSessionState fresh(UUID roundId, boolean shared, long anchorGameTick) {
        return new ReplayBoardSessionState(roundId, shared, false, anchorGameTick, 0.0,
                DEFAULT_SPEED, false, null, false, true, false, 1);
    }

    public static ReplayBoardSessionState preparing(UUID roundId, boolean shared,
                                                    double startTick, float speed, boolean paused,
                                                    @Nullable UUID followPlayerUuid,
                                                    boolean killCloseupEnabled, boolean roofCutaway) {
        return new ReplayBoardSessionState(roundId, shared, true, Long.MAX_VALUE, Math.max(0.0, startTick),
                sanitizeSpeed(speed), paused, followPlayerUuid, killCloseupEnabled, roofCutaway, false, 1);
    }

    public boolean active() {
        return !endedToIdle;
    }

    /**
     * Pure linear playback clock: advances at {@code speed} (0.5x / 1x / 2x / 4x) and holds cleanly at
     * {@code durationTicks} when finished instead of auto-collapsing back to idle.
     */
    public double currentReplayTick(long gameTime, float partialTick, int durationTicks) {
        double maxTick = Math.max(0, durationTicks);
        if (endedToIdle || preparing || paused) {
            return Math.clamp(anchorReplayTick, 0.0, maxTick);
        }
        if (gameTime < anchorGameTick) {
            return Math.clamp(anchorReplayTick, 0.0, maxTick);
        }
        double elapsed = (gameTime - anchorGameTick) + Math.clamp(partialTick, 0.0f, 1.0f);
        return Math.clamp(anchorReplayTick + elapsed * speed, 0.0, maxTick);
    }

    /** Returns {@code true} when playback has reached the end-of-round hold state without ending to idle. */
    public boolean isFinishedHold(long gameTime, float partialTick, int durationTicks) {
        if (preparing || endedToIdle) return false;
        return currentReplayTick(gameTime, partialTick, durationTicks) >= Math.max(0, durationTicks) - 1e-4;
    }

    /** Transitions from {@code preparing} into active playback anchored at {@code startGameTick}. */
    public ReplayBoardSessionState beginAt(long startGameTick, int durationTicks) {
        double clamped = Math.clamp(anchorReplayTick, 0.0, Math.max(0, durationTicks));
        return new ReplayBoardSessionState(roundId, shared, false, startGameTick, clamped,
                speed, paused, followPlayerUuid, killCloseupEnabled, roofCutaway, false, revision + 1);
    }

    /** Applies one player control action deterministically without timeline jumps. */
    public ReplayBoardSessionState applyControl(Action action, long nowGameTick, int durationTicks,
                                                double seekOrStepTick, float requestedSpeed,
                                                @Nullable UUID requestedFollow, boolean flag) {
        double maxTick = Math.max(0, durationTicks);
        double cur = currentReplayTick(nowGameTick, 0.0f, durationTicks);
        int nextRev = revision + 1;
        return switch (action) {
            case PLAY -> {
                // Pressing Play while sitting at the end restarts from 0; otherwise resumes from cur.
                double resumeAt = cur >= maxTick - 1e-4 && maxTick > 0 ? 0.0 : cur;
                yield new ReplayBoardSessionState(roundId, shared, false, nowGameTick, resumeAt,
                        speed, false, followPlayerUuid, killCloseupEnabled, roofCutaway, false, nextRev);
            }
            case PAUSE -> new ReplayBoardSessionState(roundId, shared, false, nowGameTick, cur,
                    speed, true, followPlayerUuid, killCloseupEnabled, roofCutaway, false, nextRev);
            case TOGGLE_PAUSE -> {
                if (paused) {
                    double resumeAt = cur >= maxTick - 1e-4 && maxTick > 0 ? 0.0 : cur;
                    yield new ReplayBoardSessionState(roundId, shared, false, nowGameTick, resumeAt,
                            speed, false, followPlayerUuid, killCloseupEnabled, roofCutaway, false, nextRev);
                }
                yield new ReplayBoardSessionState(roundId, shared, false, nowGameTick, cur,
                        speed, true, followPlayerUuid, killCloseupEnabled, roofCutaway, false, nextRev);
            }
            case SEEK -> {
                double target = Double.isFinite(seekOrStepTick) ? Math.clamp(seekOrStepTick, 0.0, maxTick) : cur;
                yield new ReplayBoardSessionState(roundId, shared, false, nowGameTick, target,
                        speed, paused, followPlayerUuid, killCloseupEnabled, roofCutaway, false, nextRev);
            }
            case STEP -> {
                double delta = Double.isFinite(seekOrStepTick) ? seekOrStepTick : 0.0;
                double target = Math.clamp(cur + delta, 0.0, maxTick);
                yield new ReplayBoardSessionState(roundId, shared, false, nowGameTick, target,
                        speed, paused, followPlayerUuid, killCloseupEnabled, roofCutaway, false, nextRev);
            }
            case SET_SPEED -> new ReplayBoardSessionState(roundId, shared, false, nowGameTick, cur,
                    sanitizeSpeed(requestedSpeed), paused, followPlayerUuid, killCloseupEnabled, roofCutaway, false, nextRev);
            case SET_FOLLOW -> new ReplayBoardSessionState(roundId, shared, preparing, anchorGameTick, anchorReplayTick,
                    speed, paused, requestedFollow, killCloseupEnabled, roofCutaway, false, nextRev);
            case SET_KILL_CLOSEUP -> new ReplayBoardSessionState(roundId, shared, preparing, anchorGameTick, anchorReplayTick,
                    speed, paused, followPlayerUuid, flag, roofCutaway, false, nextRev);
            case SET_ROOF_CUTAWAY -> new ReplayBoardSessionState(roundId, shared, preparing, anchorGameTick, anchorReplayTick,
                    speed, paused, followPlayerUuid, killCloseupEnabled, flag, false, nextRev);
            case END_TO_IDLE -> new ReplayBoardSessionState(roundId, shared, false, nowGameTick, 0.0,
                    DEFAULT_SPEED, true, null, false, true, true, nextRev);
        };
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(roundId);
        int bits = (shared ? 1 : 0) | (preparing ? 2 : 0) | (paused ? 4 : 0)
                | (killCloseupEnabled ? 8 : 0) | (roofCutaway ? 16 : 0)
                | (endedToIdle ? 32 : 0) | (followPlayerUuid != null ? 64 : 0);
        buf.writeByte(bits);
        buf.writeLong(anchorGameTick);
        buf.writeDouble(anchorReplayTick);
        buf.writeFloat(speed);
        if (followPlayerUuid != null) buf.writeUUID(followPlayerUuid);
        buf.writeVarInt(revision);
    }

    public static ReplayBoardSessionState read(FriendlyByteBuf buf) {
        UUID roundId = buf.readUUID();
        int bits = buf.readUnsignedByte();
        long anchorGame = buf.readLong();
        double anchorReplay = buf.readDouble();
        float speed = buf.readFloat();
        UUID follow = (bits & 64) != 0 ? buf.readUUID() : null;
        int rev = buf.readVarInt();
        return new ReplayBoardSessionState(roundId,
                (bits & 1) != 0, (bits & 2) != 0, anchorGame, anchorReplay, speed,
                (bits & 4) != 0, follow, (bits & 8) != 0, (bits & 16) != 0, (bits & 32) != 0, rev);
    }

    /** Transient sync tag for {@code BlockEntity.getUpdateTag} only — never call from {@code saveAdditional}. */
    public CompoundTag writeTransientSyncTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("RoundId", roundId);
        tag.putBoolean("Shared", shared);
        tag.putBoolean("Preparing", preparing);
        tag.putLong("AnchorGame", anchorGameTick);
        tag.putDouble("AnchorReplay", anchorReplayTick);
        tag.putFloat("Speed", speed);
        tag.putBoolean("Paused", paused);
        if (followPlayerUuid != null) tag.putUUID("Follow", followPlayerUuid);
        tag.putBoolean("KillCloseup", killCloseupEnabled);
        tag.putBoolean("RoofCutaway", roofCutaway);
        tag.putBoolean("EndedToIdle", endedToIdle);
        tag.putInt("Revision", revision);
        return tag;
    }

    public static @Nullable ReplayBoardSessionState readTransientSyncTag(CompoundTag tag) {
        if (tag == null || !tag.hasUUID("RoundId")) return null;
        return new ReplayBoardSessionState(
                tag.getUUID("RoundId"),
                tag.getBoolean("Shared"),
                tag.getBoolean("Preparing"),
                tag.getLong("AnchorGame"),
                tag.getDouble("AnchorReplay"),
                tag.contains("Speed") ? tag.getFloat("Speed") : DEFAULT_SPEED,
                tag.getBoolean("Paused"),
                tag.hasUUID("Follow") ? tag.getUUID("Follow") : null,
                tag.getBoolean("KillCloseup"),
                !tag.contains("RoofCutaway") || tag.getBoolean("RoofCutaway"),
                tag.getBoolean("EndedToIdle"),
                tag.getInt("Revision")
        );
    }
}
