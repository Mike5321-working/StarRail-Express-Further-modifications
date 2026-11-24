package com.habitrain.core.api.match.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.scene.model.SceneBounds;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Immutable view of the last completed SRE match. All positions are relative to origin bounds' minimum. */
public record LastMatchReplay(UUID roundId, long playbackAnchorTick, int durationTicks,
                              String mapName, String modeId, String winStatus, MatchWinKind winKind,
                              String customWinId, String customTitleJson, String customSubtitleJson, int customColor,
                              SceneBounds origin, @Nullable ReplayMapManifest manifest,
                              List<Participant> participants, List<Node> events, List<Span> spans,
                              @Nullable UUID mvp, int killCount, boolean limited, List<Drone> drones,
                              List<ReplayKillSounds.Window> killSounds,
                              List<ReplayBlockDelta> blockDeltas,
                              List<ReplayEntityTrack> entityTracks,
                              List<ReplaySkillEvent> skillEvents,
                              ReplayGameSoundData gameSounds) {
    public static final int MAX_PLAYERS = 128;
    public static final int MAX_DRONES = 64;
    public static final int MAX_EVENTS = 768;
    public static final int MAX_SPANS = 384;
    public static final int MAX_TICKS = 24 * 60 * 60 * 20;

    public LastMatchReplay {
        participants = List.copyOf(participants);
        events = List.copyOf(events);
        spans = List.copyOf(spans);
        drones = List.copyOf(drones);
        killSounds = List.copyOf(killSounds);
        blockDeltas = blockDeltas == null ? List.of() : List.copyOf(blockDeltas);
        entityTracks = entityTracks == null ? List.of() : List.copyOf(entityTracks);
        skillEvents = skillEvents == null ? List.of() : List.copyOf(skillEvents);
        gameSounds = gameSounds == null ? ReplayGameSoundData.EMPTY : gameSounds;
    }

    /** Compatibility constructor (21 args, v1 with killSounds). */
    public LastMatchReplay(UUID roundId, long playbackAnchorTick, int durationTicks,
                           String mapName, String modeId, String winStatus, MatchWinKind winKind,
                           String customWinId, String customTitleJson, String customSubtitleJson, int customColor,
                           SceneBounds origin, @Nullable ReplayMapManifest manifest,
                           List<Participant> participants, List<Node> events, List<Span> spans,
                           @Nullable UUID mvp, int killCount, boolean limited, List<Drone> drones,
                           List<ReplayKillSounds.Window> killSounds) {
        this(roundId, playbackAnchorTick, durationTicks, mapName, modeId, winStatus, winKind, customWinId,
                customTitleJson, customSubtitleJson, customColor, origin, manifest, participants, events, spans,
                mvp, killCount, limited, drones, killSounds, List.of(), List.of(), List.of(), ReplayGameSoundData.EMPTY);
    }

    /** Compatibility constructor (20 args, v1 with drones). */
    public LastMatchReplay(UUID roundId, long playbackAnchorTick, int durationTicks,
                           String mapName, String modeId, String winStatus, MatchWinKind winKind,
                           String customWinId, String customTitleJson, String customSubtitleJson, int customColor,
                           SceneBounds origin, @Nullable ReplayMapManifest manifest,
                           List<Participant> participants, List<Node> events, List<Span> spans,
                           @Nullable UUID mvp, int killCount, boolean limited, List<Drone> drones) {
        this(roundId, playbackAnchorTick, durationTicks, mapName, modeId, winStatus, winKind, customWinId,
                customTitleJson, customSubtitleJson, customColor, origin, manifest, participants, events, spans,
                mvp, killCount, limited, drones, List.of(), List.of(), List.of(), List.of(), ReplayGameSoundData.EMPTY);
    }

    /** Compatibility constructor (19 args, v1 players only). */
    public LastMatchReplay(UUID roundId, long playbackAnchorTick, int durationTicks,
                           String mapName, String modeId, String winStatus, MatchWinKind winKind,
                           String customWinId, String customTitleJson, String customSubtitleJson, int customColor,
                           SceneBounds origin, @Nullable ReplayMapManifest manifest,
                           List<Participant> participants, List<Node> events, List<Span> spans,
                           @Nullable UUID mvp, int killCount, boolean limited) {
        this(roundId, playbackAnchorTick, durationTicks, mapName, modeId, winStatus, winKind, customWinId,
                customTitleJson, customSubtitleJson, customColor, origin, manifest, participants, events, spans,
                mvp, killCount, limited, List.of(), List.of(), List.of(), List.of(), List.of(), ReplayGameSoundData.EMPTY);
    }

    public LastMatchReplay withKillSounds(List<ReplayKillSounds.Window> sounds) {
        return new LastMatchReplay(roundId, playbackAnchorTick, durationTicks, mapName, modeId, winStatus, winKind,
                customWinId, customTitleJson, customSubtitleJson, customColor, origin, manifest, participants, events,
                spans, mvp, killCount, limited, drones, sounds, blockDeltas, entityTracks, skillEvents, gameSounds);
    }

    public LastMatchReplay withWorldDynamics(List<ReplayBlockDelta> deltas,
                                             List<ReplayEntityTrack> entities,
                                             List<ReplaySkillEvent> skills,
                                             ReplayGameSoundData sounds) {
        return new LastMatchReplay(roundId, playbackAnchorTick, durationTicks, mapName, modeId, winStatus, winKind,
                customWinId, customTitleJson, customSubtitleJson, customColor, origin, manifest, participants, events,
                spans, mvp, killCount, limited, drones, killSounds, deltas, entities, skills, sounds);
    }

    public LastMatchReplay withGameSounds(ReplayGameSoundData sounds) {
        return new LastMatchReplay(roundId, playbackAnchorTick, durationTicks, mapName, modeId, winStatus, winKind,
                customWinId, customTitleJson, customSubtitleJson, customColor, origin, manifest, participants, events,
                spans, mvp, killCount, limited, drones, killSounds, blockDeltas, entityTracks, skillEvents, sounds);
    }

    /** Looks up an event by its stable monotonic {@code eventId} (returns {@code null} if not found). */
    public @Nullable Node eventById(int eventId) {
        if (eventId < 0) return null;
        for (Node n : events) {
            if (n.eventId() == eventId) return n;
        }
        return null;
    }

    /** Returns the list index of the event with the given {@code eventId}, or -1 if absent. */
    public int indexOfEventId(int eventId) {
        if (eventId < 0) return -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).eventId() == eventId) return i;
        }
        return -1;
    }

    /** One deployed chassis; it never occupies the operator's player track or settlement ranking. */
    public record Drone(UUID id, UUID owner, List<Sample> track) {
        public static final byte CRASHED = SNEAK, SCRAPPED = DISGUISED, PILOTED = PSYCHO;
        public Drone { track = List.copyOf(track); }
    }

    public record Participant(UUID id, String name, String startRole, int startColor, String endRole, int endColor,
                              MatchWinFaction faction, boolean winner, int survivalSeconds, int kills,
                              int score, int itemUses, List<Sample> track) {
        public Participant { track = List.copyOf(track); }
    }

    // Legacy 8-bit sample flags (preserved for v1 compatibility)
    public static final byte ALIVE = 1, SPRINT = 2, SNEAK = 4, DISGUISED = 8, PSYCHO = 16, ASLEEP = 32,
            /** A player disappeared, or jumped too far to interpolate. */ BREAK = 64;

    // v2 Action bitmask
    public static final int ACT_SNEAK      = 1 << 0;
    public static final int ACT_SPRINT     = 1 << 1;
    public static final int ACT_SWIM_CRAWL = 1 << 2;
    public static final int ACT_ON_GROUND  = 1 << 3;
    public static final int ACT_USING_ITEM = 1 << 4;
    public static final int ACT_SWING_MAIN = 1 << 5;
    public static final int ACT_SWING_OFF  = 1 << 6;
    public static final int ACT_HURT_FLASH = 1 << 7;
    public static final int ACT_LEFT_HANDED = 1 << 8;
    public static final int ACT_USING_OFF_HAND = 1 << 9;
    public static final int ACT_USE_REMAINING_SHIFT = 10;

    // v2 Visible status bitmask
    public static final int STATUS_INVISIBLE   = 1 << 0;
    public static final int STATUS_SHIELDED    = 1 << 1;
    public static final int STATUS_POISONED    = 1 << 2;
    public static final int STATUS_BLIND       = 1 << 3;
    public static final int STATUS_FLASHED     = 1 << 4;
    public static final int STATUS_SPEED_BOOST = 1 << 5;
    public static final int STATUS_PSYCHO      = 1 << 6;
    public static final int STATUS_ASLEEP      = 1 << 7;
    public static final int STATUS_DISGUISED   = 1 << 8;
    public static final int STATUS_MUTED       = 1 << 9;
    public static final int STATUS_ROOTED      = 1 << 10;
    public static final int STATUS_GLOWING     = 1 << 11;
    public static final int STATUS_C4_BACK     = 1 << 12;

    public record Position(short x, short y, short z) {
        public static @Nullable Position quantize(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            long qx = Math.round(x * 16), qy = Math.round(y * 16), qz = Math.round(z * 16);
            if (outside(qx) || outside(qy) || outside(qz)) return null;
            return new Position((short) qx, (short) qy, (short) qz);
        }

        private static boolean outside(long v) { return v < Short.MIN_VALUE || v > Short.MAX_VALUE; }
        public double blockX() { return x / 16.0; }
        public double blockY() { return y / 16.0; }
        public double blockZ() { return z / 16.0; }
    }

    public record Sample(int tick, Position position, byte yaw, byte flags,
                         byte pitch, byte headYaw, int actionFlags, int statusFlags,
                         String mainHandItemId, String offHandItemId, @Nullable UUID disguiseTargetUuid) {
        public Sample {
            mainHandItemId = mainHandItemId == null ? "" : mainHandItemId;
            offHandItemId = offHandItemId == null ? "" : offHandItemId;
        }

        /** Backward-compatible v1 sample constructor. */
        public Sample(int tick, Position position, byte yaw, byte flags) {
            this(tick, position, yaw, flags, (byte) 0, yaw,
                    deriveLegacyActionFlags(flags), deriveLegacyStatusFlags(flags), "", "", null);
        }

        public float yawDegrees() { return Byte.toUnsignedInt(yaw) * 360.0f / 256.0f; }
        public float pitchDegrees() { return Math.clamp(pitch * (90.0f / 127.0f), -90.0f, 90.0f); }
        public float headYawDegrees() { return Byte.toUnsignedInt(headYaw) * 360.0f / 256.0f; }
        public boolean hasAction(int actionFlag) { return (actionFlags & actionFlag) != 0; }
        public boolean hasStatus(int statusFlag) { return (statusFlags & statusFlag) != 0; }

        public Sample withFlags(byte newFlags) {
            return new Sample(tick, position, yaw, newFlags, pitch, headYaw, actionFlags, statusFlags,
                    mainHandItemId, offHandItemId, disguiseTargetUuid);
        }

        public static byte quantizeYaw(float yawDeg) {
            return (byte) Math.round(yawDeg * 256.0f / 360.0f);
        }

        public static byte quantizePitch(float pitchDeg) {
            return (byte) Math.clamp(Math.round(pitchDeg * (127.0f / 90.0f)), -127, 127);
        }

        public static int deriveLegacyActionFlags(byte flags) {
            int act = ACT_ON_GROUND;
            if ((flags & SNEAK) != 0) act |= ACT_SNEAK;
            if ((flags & SPRINT) != 0) act |= ACT_SPRINT;
            return act;
        }

        public static int deriveLegacyStatusFlags(byte flags) {
            int st = 0;
            if ((flags & DISGUISED) != 0) st |= STATUS_DISGUISED;
            if ((flags & PSYCHO) != 0) st |= STATUS_PSYCHO;
            if ((flags & ASLEEP) != 0) st |= STATUS_ASLEEP;
            return st;
        }
    }

    public enum Kind {
        KILL, GUNSHOT, POISON, GRENADE_THROWN, GRENADE_EXPLOSION, BOMB_PLANT, BOMB_DEFUSE, BOMB_DETONATE,
        SHIELD_BROKEN, LOCKPICK_ATTEMPT, DOOR_PRY, DOOR_SEAL, ITEM_USED, SKILL_RELEASE, DISGUISE,
        PLAYER_REVIVAL, CHANGE_ROLE, BLACKOUT_START, BLACKOUT_END, PSYCHO_STATE_CHANGE,
        TRAP_TRIGGERED, ROPE_PULL, DOOR_LOCK, DOOR_UNLOCK, DOOR_OPEN, DOOR_CLOSE
    }

    public static final int HIT = 1, BACKFIRE = 2, GUN_DROP = 4, SUCCESS = 8, APPROXIMATE = 16;

    /**
     * One discrete timeline event.
     *
     * @param eventId stable monotonic identifier assigned at record time (>= 1), or -1 on legacy v1 replays
     */
    public record Node(int eventId, int tick, Kind kind, @Nullable UUID actor, @Nullable UUID target,
                       @Nullable Position from, @Nullable Position to, String detail, int flags) {
        /** Backward-compatible v1 constructor (unassigned {@code eventId = -1}). */
        public Node(int tick, Kind kind, @Nullable UUID actor, @Nullable UUID target,
                    @Nullable Position from, @Nullable Position to, String detail, int flags) {
            this(-1, tick, kind, actor, target, from, to, detail, flags);
        }

        public Node withEventId(int id) {
            return new Node(id, tick, kind, actor, target, from, to, detail, flags);
        }

        public boolean highlight() {
            return kind == Kind.KILL || kind == Kind.BOMB_DETONATE || kind == Kind.GRENADE_EXPLOSION
                    || kind == Kind.GUNSHOT && (flags & HIT) != 0;
        }
    }

    public enum SpanKind { BLACKOUT, PSYCHO }
    public record Span(SpanKind kind, @Nullable UUID actor, int startTick, int endTick) {}

    public @Nullable Node firstKill() {
        return events.stream().filter(e -> e.kind() == Kind.KILL).findFirst().orElse(null);
    }

    public @Nullable Node lastKill() {
        for (int i = events.size() - 1; i >= 0; i--) if (events.get(i).kind() == Kind.KILL) return events.get(i);
        return null;
    }
}
