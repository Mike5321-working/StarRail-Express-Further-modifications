package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import com.habitrain.core.api.match.replay.ReplayProjection;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Pure, deterministic, side-neutral audibility & spatial mixing solver for replay voice and game sound.
 *
 * <p>Given a {@link LastMatchReplay}, {@link ReplayVoiceData} ({@code HRVC v2}), an optional
 * {@code listenerUuid}, and a timestamp {@code matchMs}, computes:
 * <ul>
 *   <li>Which voice utterances are audible (listener's own centered speech + other players' speech
 *       passing 3D distance, death 150 ms last-words grace, whisper/radio/killer-private/group channels,
 *       Sloth/Mime/Drone mutes, and {@code RoleVoicePolicy} isolation/maxDistance rules) with exact
 *       {@code gain} and stereo {@code pan}.</li>
 *   <li>Which server-recorded {@link ReplayGameSoundData.Cue} game sounds are audible for that listener
 *       (or global/camera viewpoint) with exact {@code gain} and stereo {@code pan}.</li>
 * </ul>
 * For high-frequency streaming export or real-time playback, build a {@link Context} once per replay to
 * keep every query O(log N) and allocation-light.</p>
 */
public final class ReplayVoiceAudibilityResolver {
    public static final long DEATH_GRACE_MS = 150L;
    private static final float MIN_AUDIBLE_GAIN = 0.02f;
    private static final float MAX_STEREO_PAN = 0.85f;

    private ReplayVoiceAudibilityResolver() {}

    /** One resolved voice utterance audible to the listener/camera at the queried instant. */
    public record AudibleVoice(
            ReplayVoiceData.VoiceUtterance utterance,
            float gain,
            float pan,
            boolean isSelf
    ) {}

    public record VoiceSlice(AudibleVoice voice, long startMs, long endMs) {}

    /** One resolved server game sound cue audible to the listener/camera. */
    public record AudibleSound(
            ReplayGameSoundData.Cue cue,
            String soundId,
            float gain,
            float pan
    ) {}

    /** Pre-indexed immutable lookup structure for repeated O(log N) audibility queries on one match. */
    public static final class Context {
        private final LastMatchReplay replay;
        private final ReplayVoiceData voice;
        private final @Nullable ReplayProjection.Layout layout;
        private final Map<UUID, ReplayTrackSampler> samplers;
        private final Map<UUID, LastMatchReplay.Participant> participants;
        private final Map<UUID, List<Integer>> deathTicksByPlayer;
        private final Map<UUID, List<ReplayVoiceData.SpeakerStateSpan>> spansByPlayer;
        private final Map<String, List<ReplayVoiceData.RecordedVoicePolicy>> policiesByRole;

        private Context(LastMatchReplay replay, @Nullable ReplayVoiceData voice) {
            this.replay = Objects.requireNonNull(replay, "replay");
            this.voice = voice == null ? ReplayVoiceData.EMPTY : voice;
            this.layout = ReplayProjection.layout(replay);

            Map<UUID, ReplayTrackSampler> samplerMap = new HashMap<>(replay.participants().size() * 2);
            Map<UUID, LastMatchReplay.Participant> partMap = new HashMap<>(replay.participants().size() * 2);
            for (var p : replay.participants()) {
                partMap.put(p.id(), p);
                samplerMap.put(p.id(), new ReplayTrackSampler(p.track()));
            }
            this.samplers = Map.copyOf(samplerMap);
            this.participants = Map.copyOf(partMap);

            Map<UUID, List<Integer>> deaths = new HashMap<>();
            for (var ev : replay.events()) {
                if (ev.kind() == LastMatchReplay.Kind.KILL && ev.target() != null) {
                    deaths.computeIfAbsent(ev.target(), ignored -> new ArrayList<>()).add(ev.tick());
                }
            }
            deaths.values().forEach(list -> list.sort(Comparator.naturalOrder()));
            this.deathTicksByPlayer = deaths;

            Map<UUID, List<ReplayVoiceData.SpeakerStateSpan>> spans = new HashMap<>();
            for (var span : this.voice.timelineMeta().stateSpans()) {
                spans.computeIfAbsent(span.playerId(), ignored -> new ArrayList<>()).add(span);
            }
            spans.values().forEach(list -> list.sort(Comparator.comparingLong(ReplayVoiceData.SpeakerStateSpan::startMs)));
            this.spansByPlayer = spans;

            Map<String, List<ReplayVoiceData.RecordedVoicePolicy>> pols = new HashMap<>();
            for (var p : this.voice.timelineMeta().policies()) {
                pols.computeIfAbsent(p.roleId(), ignored -> new ArrayList<>()).add(p);
            }
            this.policiesByRole = pols;
        }

        public static Context of(LastMatchReplay replay, @Nullable ReplayVoiceData voice) {
            return new Context(replay, voice);
        }

        public LastMatchReplay replay() { return replay; }
        public ReplayVoiceData voice() { return voice; }

        /** Resolves all audible voices active at {@code matchMs} for {@code listenerUuid} ({@code null} = global sandbox). */
        public List<AudibleVoice> resolveVoices(@Nullable UUID listenerUuid, long matchMs) {
            return resolveVoices(listenerUuid, matchMs, false);
        }

        /** Resolves all audible voices active at {@code matchMs}, optionally including private channels in global mode. */
        public List<AudibleVoice> resolveVoices(@Nullable UUID listenerUuid, long matchMs, boolean includePrivateInGlobal) {
            if (!voice.hasMultitrack()) return List.of();
            List<AudibleVoice> out = new ArrayList<>();
            for (var u : voice.utterancesInWindow(matchMs, matchMs)) {
                AudibleVoice resolved = evaluateUtterance(u, listenerUuid, matchMs, includePrivateInGlobal);
                if (resolved != null) out.add(resolved);
            }
            return out;
        }

        /** Resolves all audible voices overlapping {@code [windowStartMs, windowEndMs]}, evaluated at overlap midpoint. */
        public List<AudibleVoice> resolveVoicesInWindow(@Nullable UUID listenerUuid, long windowStartMs, long windowEndMs) {
            return resolveVoicesInWindow(listenerUuid, windowStartMs, windowEndMs, false);
        }

        public List<AudibleVoice> resolveVoicesInWindow(@Nullable UUID listenerUuid, long windowStartMs, long windowEndMs,
                                                        boolean includePrivateInGlobal) {
            if (!voice.hasMultitrack() || windowEndMs < windowStartMs) return List.of();
            List<AudibleVoice> out = new ArrayList<>();
            for (var u : voice.utterancesInWindow(windowStartMs, windowEndMs)) {
                long uEnd = u.endMs();
                long evalMs = (Math.max(u.startMs(), windowStartMs) + Math.min(uEnd, windowEndMs)) / 2L;
                AudibleVoice resolved = evaluateUtterance(u, listenerUuid, evalMs, includePrivateInGlobal);
                if (resolved != null) out.add(resolved);
            }
            return out;
        }

        public List<VoiceSlice> resolveVoiceSlices(@Nullable UUID listener, long startMs, long endMs) {
            List<VoiceSlice> result = new ArrayList<>();
            for (var utterance : voice.utterancesInWindow(startMs, endMs)) {
                result.addAll(resolveVoiceSlices(utterance, listener, startMs, endMs, false));
            }
            return result;
        }

        public List<VoiceSlice> deliverySlices(ReplayVoiceData.VoiceUtterance utterance, UUID recipient) {
            return resolveVoiceSlices(utterance, recipient, utterance.startMs(), utterance.endMs(), true);
        }

        public @Nullable VoiceSlice playbackSlice(AudibleVoice voice, @Nullable UUID listener, long atMs) {
            var utterance = voice.utterance();
            for (var slice : resolveVoiceSlices(utterance, listener, utterance.startMs(), utterance.endMs(), false)) {
                if (atMs >= slice.startMs() && atMs < slice.endMs()) return new VoiceSlice(voice, slice.startMs(), slice.endMs());
            }
            return null;
        }

        private List<VoiceSlice> resolveVoiceSlices(ReplayVoiceData.VoiceUtterance utterance, @Nullable UUID listener,
                                                   long startMs, long endMs, boolean delivery) {
            long start = Math.max(startMs, utterance.startMs());
            long end = Math.min(endMs, utterance.endMs());
            if (start >= end) return List.of();
            var boundaries = new TreeSet<Long>();
            boundaries.add(start);
            boundaries.add(end);
            UUID speaker = utterance.speakerId();
            addVoiceBoundaries(boundaries, speaker, start, end);
            if (listener != null && !listener.equals(speaker)) addVoiceBoundaries(boundaries, listener, start, end);
            boolean proximity = utterance.channelMode() == ReplayVoiceData.ChannelMode.NORMAL_OPEN
                    || utterance.channelMode() == ReplayVoiceData.ChannelMode.WHISPER;
            if (delivery && proximity && !speaker.equals(listener)) {
                for (long tick = start - start % 50 + 50; tick < end; tick += 50) boundaries.add(tick);
            }
            List<VoiceSlice> result = new ArrayList<>();
            for (long stop : boundaries.tailSet(start, false)) {
                long middle = start + (stop - start) / 2;
                AudibleVoice audible = evaluateUtterance(utterance, listener, middle, false);
                if (audible != null && (!delivery || canDeliverSlice(utterance, listener, start, stop, middle))) {
                    if (!result.isEmpty() && result.getLast().endMs() == start) {
                        var previous = result.removeLast();
                        result.add(new VoiceSlice(previous.voice(), previous.startMs(), stop));
                    } else {
                        result.add(new VoiceSlice(audible, start, stop));
                    }
                }
                start = stop;
            }
            return result;
        }

        private boolean canDeliverSlice(ReplayVoiceData.VoiceUtterance utterance, @Nullable UUID listener,
                                        long start, long end, long middle) {
            UUID speaker = utterance.speakerId();
            if (speaker.equals(listener)) return true;
            var speakerState = spanAt(speaker, middle);
            var listenerState = spanAt(listener, middle);
            if (speakerState == null || listenerState == null) return false;
            return switch (utterance.channelMode()) {
                case RADIO -> speakerState.hasState(ReplayVoiceData.STATE_RADIO_CHANNEL);
                case KILLER_PRIVATE -> speakerState.hasState(ReplayVoiceData.STATE_KILLER_CHANNEL);
                case GROUP -> utterance.groupId() != null && utterance.groupId().equals(speakerState.groupId());
                case NORMAL_OPEN, WHISPER -> evaluateUtterance(utterance, listener, start, false) != null
                        && evaluateUtterance(utterance, listener, end - 1, false) != null;
            };
        }

        private void addVoiceBoundaries(NavigableSet<Long> boundaries, UUID player, long start, long end) {
            for (var span : spansByPlayer.getOrDefault(player, List.of())) {
                if (span.startMs() >= end) break;
                if (span.startMs() > start) boundaries.add(span.startMs());
                if (span.endMs() >= start && span.endMs() < end - 1) boundaries.add(span.endMs() + 1);
            }
            for (int death : deathTicksByPlayer.getOrDefault(player, List.of())) {
                long at = death * 50L;
                if (at >= end) break;
                if (at > start) boundaries.add(at);
                long afterGrace = at + DEATH_GRACE_MS + 1;
                if (afterGrace > start && afterGrace < end) boundaries.add(afterGrace);
            }
        }

        /** Resolves audible voices for a free 3D camera (in origin-relative block coordinates). */
        public List<AudibleVoice> resolveVoicesAtCamera(double camX, double camY, double camZ, float camYawDeg,
                                                        double maxDistBlocks, long matchMs) {
            if (!voice.hasMultitrack()) return List.of();
            List<AudibleVoice> out = new ArrayList<>();
            for (var u : voice.utterancesInWindow(matchMs, matchMs)) {
                if (u.channelMode() != ReplayVoiceData.ChannelMode.NORMAL_OPEN
                        && u.channelMode() != ReplayVoiceData.ChannelMode.WHISPER) {
                    continue;
                }
                if (!speakerCanTransmit(u.speakerId(), matchMs)) continue;
                ReplayTrackSampler.Pose spPose = poseAt(u.speakerId(), matchMs / 50.0);
                if (spPose == null || !spPose.visible()) continue;
                double dist = distance3d(camX, camY, camZ, spPose.x(), spPose.y(), spPose.z());
                double limit = Math.min(maxDistBlocks > 0 ? maxDistBlocks : u.baseMaxDist(),
                        Math.max(u.baseMaxDist(), 4.0));
                if (dist > limit) continue;
                float gain = distanceGain(dist, limit);
                if (gain < MIN_AUDIBLE_GAIN) continue;
                float pan = stereoPan(camX, camZ, camYawDeg, spPose.x(), spPose.z());
                out.add(new AudibleVoice(u, gain, pan, false));
            }
            return out;
        }

        /** Evaluates whether one server-recorded game sound cue is audible to {@code listenerUuid} ({@code null} = global). */
        public @Nullable AudibleSound resolveGameSound(@Nullable UUID listenerUuid, ReplayGameSoundData.Cue cue) {
            if (cue == null) return null;
            String soundId = replay.gameSounds().soundId(cue);
            if (soundId.isEmpty() || cue.volume() <= 0f) return null;

            ReplayGameSoundData.AudibilityScope scope = cue.audibilityScope();
            if (listenerUuid == null) {
                if (scope == ReplayGameSoundData.AudibilityScope.ACTOR_ONLY
                        || scope == ReplayGameSoundData.AudibilityScope.TEAM_KILLER) {
                    return null;
                }
                float pan = scope == ReplayGameSoundData.AudibilityScope.GLOBAL
                        ? 0f : sandboxAxisPan(cue.blockX(), cue.blockY(), cue.blockZ());
                float gain = Math.clamp(cue.volume(), 0f, 1.5f);
                return gain < MIN_AUDIBLE_GAIN ? null : new AudibleSound(cue, soundId, gain, pan);
            }

            // Specific listener POV
            if (scope == ReplayGameSoundData.AudibilityScope.ACTOR_ONLY) {
                if (!listenerUuid.equals(cue.sourceActor())) return null;
                return new AudibleSound(cue, soundId, Math.clamp(cue.volume(), 0f, 1.5f), 0f);
            }
            if (scope == ReplayGameSoundData.AudibilityScope.EXCLUDING_ACTOR
                    && listenerUuid.equals(cue.sourceActor())) {
                return null;
            }
            if (scope == ReplayGameSoundData.AudibilityScope.TEAM_KILLER) {
                var part = participants.get(listenerUuid);
                if (part == null || part.faction() != MatchWinFaction.KILLER) return null;
                return new AudibleSound(cue, soundId, Math.clamp(cue.volume(), 0f, 1.5f), 0f);
            }
            if (scope == ReplayGameSoundData.AudibilityScope.GLOBAL) {
                return new AudibleSound(cue, soundId, Math.clamp(cue.volume(), 0f, 1.5f), 0f);
            }

            // WORLD_3D or EXCLUDING_ACTOR (for non-actor listener)
            ReplayTrackSampler.Pose lp = poseAt(listenerUuid, cue.matchMs() / 50.0);
            if (lp == null || !lp.visible()) return null;
            double dist = distance3d(lp.x(), lp.y() + 1.62, lp.z(), cue.blockX(), cue.blockY(), cue.blockZ());
            double maxDist = cue.maxHearingDistance();
            if (dist > maxDist) return null;
            float atten = dist <= 1.0 ? 1.0f : Math.clamp(1.0f - (float) (dist / maxDist), 0f, 1f);
            float gain = Math.clamp(cue.volume() * atten, 0f, 1.5f);
            if (gain < MIN_AUDIBLE_GAIN) return null;
            float pan = listenerUuid.equals(cue.sourceActor()) ? 0f
                    : stereoPan(lp.x(), lp.z(), lp.headYawDegrees(), cue.blockX(), cue.blockZ());
            return new AudibleSound(cue, soundId, gain, pan);
        }

        /** Resolves all audible game sound cues in {@code [windowStartMs, windowEndMs]} for {@code listenerUuid}. */
        public List<AudibleSound> resolveGameSoundsInWindow(@Nullable UUID listenerUuid, int windowStartMs, int windowEndMs) {
            ReplayGameSoundData data = replay.gameSounds();
            if (data.isEmpty() || windowEndMs < windowStartMs) return List.of();
            List<ReplayGameSoundData.Cue> slice = data.cuesInWindow(windowStartMs, windowEndMs);
            if (slice.isEmpty()) return List.of();
            List<AudibleSound> out = new ArrayList<>(slice.size());
            for (ReplayGameSoundData.Cue cue : slice) {
                AudibleSound resolved = resolveGameSound(listenerUuid, cue);
                if (resolved != null) out.add(resolved);
            }
            return out;
        }

        /** Evaluates one game sound cue relative to a free 3D camera (in origin-relative block coordinates). */
        public @Nullable AudibleSound resolveGameSoundAtCamera(double camX, double camY, double camZ, float camYawDeg,
                                                               ReplayGameSoundData.Cue cue) {
            if (cue == null) return null;
            String soundId = replay.gameSounds().soundId(cue);
            if (soundId.isEmpty() || cue.volume() <= 0f) return null;
            ReplayGameSoundData.AudibilityScope scope = cue.audibilityScope();
            if (scope == ReplayGameSoundData.AudibilityScope.ACTOR_ONLY
                    || scope == ReplayGameSoundData.AudibilityScope.TEAM_KILLER) {
                return null;
            }
            if (scope == ReplayGameSoundData.AudibilityScope.GLOBAL) {
                return new AudibleSound(cue, soundId, Math.clamp(cue.volume(), 0f, 1.5f), 0f);
            }
            double dist = distance3d(camX, camY, camZ, cue.blockX(), cue.blockY(), cue.blockZ());
            double maxDist = Math.max(cue.maxHearingDistance(), 20.0);
            if (dist > maxDist) return null;
            float atten = dist <= 1.5 ? 1.0f : Math.clamp(1.0f - (float) (dist / maxDist), 0f, 1f);
            float gain = Math.clamp(cue.volume() * atten, 0f, 1.5f);
            if (gain < MIN_AUDIBLE_GAIN) return null;
            float pan = stereoPan(camX, camZ, camYawDeg, cue.blockX(), cue.blockZ());
            return new AudibleSound(cue, soundId, gain, pan);
        }

        private @Nullable AudibleVoice evaluateUtterance(ReplayVoiceData.VoiceUtterance u,
                                                         @Nullable UUID listenerUuid,
                                                         long matchMs,
                                                         boolean includePrivateInGlobal) {
            UUID speakerId = u.speakerId();
            ReplayVoiceData.SpeakerStateSpan speakerSpan = spanAt(speakerId, matchMs);

            // Send-mute check (Sloth sleeping, Mime muted, Drone muted, Absent)
            if (speakerSpan != null) {
                if (speakerSpan.hasState(ReplayVoiceData.STATE_ABSENT)
                        || speakerSpan.hasState(ReplayVoiceData.STATE_MUTED_SLOTH)
                        || speakerSpan.hasState(ReplayVoiceData.STATE_MUTED_MIME)
                        || speakerSpan.hasState(ReplayVoiceData.STATE_MUTED_DRONE)) {
                    return null;
                }
            }
            String speakerRole = roleAt(speakerId, speakerSpan);
            if (hasPolicyMuteSend(speakerRole)) return null;

            // Listener == null -> global sandbox overview
            if (listenerUuid == null) {
                if (!includePrivateInGlobal
                        && u.channelMode() != ReplayVoiceData.ChannelMode.NORMAL_OPEN
                        && u.channelMode() != ReplayVoiceData.ChannelMode.WHISPER) {
                    return null;
                }
                if (!speakerAliveOrWithinDeathGrace(speakerId, speakerSpan, matchMs)) return null;
                ReplayTrackSampler.Pose spPose = poseAt(speakerId, matchMs / 50.0);
                float pan = spPose == null ? 0f : sandboxAxisPan(spPose.x(), spPose.y(), spPose.z());
                float gain = u.channelMode() == ReplayVoiceData.ChannelMode.WHISPER ? 0.72f : 0.90f;
                return new AudibleVoice(u, gain, pan, false);
            }

            // Self speech: always centered and full gain unless speaker was absent/muted above
            if (speakerId.equals(listenerUuid)) {
                return new AudibleVoice(u, 1.0f, 0.0f, true);
            }

            // Other speaker heard by specific listener
            ReplayVoiceData.SpeakerStateSpan listenerSpan = spanAt(listenerUuid, matchMs);
            if (listenerSpan != null && listenerSpan.hasState(ReplayVoiceData.STATE_ABSENT)) return null;
            String listenerRole = roleAt(listenerUuid, listenerSpan);
            if (hasPolicyMuteReceive(listenerRole)) return null;

            boolean listenerAlive = playerAliveAt(listenerUuid, listenerSpan, matchMs);
            boolean speakerAlive = speakerAliveOrWithinDeathGrace(speakerId, speakerSpan, matchMs);
            if (listenerAlive && !speakerAlive) {
                // Alive listeners cannot hear dead spectators once the 150ms death-cry grace window expires.
                return null;
            }

            UUID speakerGroup = u.groupId() != null ? u.groupId() : (speakerSpan == null ? null : speakerSpan.groupId());
            UUID listenerGroup = listenerSpan == null ? null : listenerSpan.groupId();

            // Private / routed channels
            if (u.channelMode() == ReplayVoiceData.ChannelMode.RADIO) {
                if (listenerSpan == null || !listenerSpan.hasState(ReplayVoiceData.STATE_RADIO_CHANNEL)) return null;
                if (isBlockedByIsolationPolicy(speakerRole, listenerRole, speakerGroup, listenerGroup)) return null;
                return new AudibleVoice(u, 0.92f, 0.0f, false);
            }
            if (u.channelMode() == ReplayVoiceData.ChannelMode.KILLER_PRIVATE) {
                if (listenerSpan == null || !listenerSpan.hasState(ReplayVoiceData.STATE_KILLER_CHANNEL)) return null;
                if (isBlockedByIsolationPolicy(speakerRole, listenerRole, speakerGroup, listenerGroup)) return null;
                return new AudibleVoice(u, 0.95f, 0.0f, false);
            }
            if (u.channelMode() == ReplayVoiceData.ChannelMode.GROUP) {
                if (speakerGroup == null || !speakerGroup.equals(listenerGroup)) return null;
                return new AudibleVoice(u, 0.95f, 0.0f, false);
            }

            // Proximity channels (NORMAL_OPEN / WHISPER)
            if (isBlockedByIsolationPolicy(speakerRole, listenerRole, speakerGroup, listenerGroup)) {
                return null;
            }
            ReplayTrackSampler.Pose spPose = poseAt(speakerId, matchMs / 50.0);
            ReplayTrackSampler.Pose lsPose = poseAt(listenerUuid, matchMs / 50.0);
            if (spPose == null || lsPose == null || !spPose.visible() || !lsPose.visible()) return null;

            double dist = distance3d(lsPose.x(), lsPose.y(), lsPose.z(), spPose.x(), spPose.y(), spPose.z());
            double maxDist = effectiveMaxDistance(u.baseMaxDist(), speakerRole, listenerRole);
            if (maxDist <= 0.0 || dist > maxDist) return null;

            float gain = distanceGain(dist, maxDist);
            if (gain < MIN_AUDIBLE_GAIN) return null;
            float pan = stereoPan(lsPose.x(), lsPose.z(), lsPose.headYawDegrees(), spPose.x(), spPose.z());
            return new AudibleVoice(u, gain, pan, false);
        }

        private boolean speakerCanTransmit(UUID speakerId, long matchMs) {
            ReplayVoiceData.SpeakerStateSpan span = spanAt(speakerId, matchMs);
            if (span != null && (span.hasState(ReplayVoiceData.STATE_ABSENT)
                    || span.hasState(ReplayVoiceData.STATE_MUTED_SLOTH)
                    || span.hasState(ReplayVoiceData.STATE_MUTED_MIME)
                    || span.hasState(ReplayVoiceData.STATE_MUTED_DRONE))) {
                return false;
            }
            return !hasPolicyMuteSend(roleAt(speakerId, span))
                    && speakerAliveOrWithinDeathGrace(speakerId, span, matchMs);
        }

        private boolean speakerAliveOrWithinDeathGrace(UUID speakerId,
                                                       @Nullable ReplayVoiceData.SpeakerStateSpan span,
                                                       long matchMs) {
            if (playerAliveAt(speakerId, span, matchMs)) return true;
            // Check 150ms death-cry grace window after a recorded death
            List<Integer> deaths = deathTicksByPlayer.get(speakerId);
            if (deaths != null) {
                for (int dt : deaths) {
                    long deathMs = dt * 50L;
                    if (matchMs >= deathMs && matchMs <= deathMs + DEATH_GRACE_MS) {
                        ReplayTrackSampler.Pose before = poseAt(speakerId, Math.max(0.0, dt - 1.0));
                        if (before != null && before.state() == ReplayTrackSampler.State.ALIVE) return true;
                    }
                }
            }
            // Also check state transition to DEAD in track sampler itself
            ReplayTrackSampler.Pose p = poseAt(speakerId, matchMs / 50.0);
            if (p != null && p.state() == ReplayTrackSampler.State.DEAD) {
                long deathMs = p.stateSince() * 50L;
                if (matchMs >= deathMs && matchMs <= deathMs + DEATH_GRACE_MS && p.stateSince() > 0) {
                    ReplayTrackSampler.Pose before = poseAt(speakerId, Math.max(0.0, p.stateSince() - 1.0));
                    if (before != null && before.state() == ReplayTrackSampler.State.ALIVE) return true;
                }
            }
            return false;
        }

        private boolean playerAliveAt(UUID playerId, @Nullable ReplayVoiceData.SpeakerStateSpan span, long matchMs) {
            if (span != null) {
                if (span.hasState(ReplayVoiceData.STATE_ABSENT)) return false;
                if (span.hasState(ReplayVoiceData.STATE_DEAD)) return false;
            }
            ReplayTrackSampler.Pose p = poseAt(playerId, matchMs / 50.0);
            if (p == null) return span != null; // fallback if track missing in minimal unit tests
            return p.state() == ReplayTrackSampler.State.ALIVE;
        }

        private @Nullable ReplayTrackSampler.Pose poseAt(UUID playerId, double matchTick) {
            ReplayTrackSampler sampler = samplers.get(playerId);
            return sampler == null ? null : sampler.sample(matchTick);
        }

        private @Nullable ReplayVoiceData.SpeakerStateSpan spanAt(UUID playerId, long matchMs) {
            List<ReplayVoiceData.SpeakerStateSpan> list = spansByPlayer.get(playerId);
            if (list == null || list.isEmpty()) return null;
            int lo = 0, hi = list.size() - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (list.get(mid).startMs() <= matchMs) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (found < 0) return null;
            ReplayVoiceData.SpeakerStateSpan candidate = list.get(found);
            return candidate.contains(matchMs) ? candidate : null;
        }

        private String roleAt(UUID playerId, @Nullable ReplayVoiceData.SpeakerStateSpan span) {
            if (span != null && !span.roleId().isEmpty()) return span.roleId();
            var part = participants.get(playerId);
            if (part == null) return "";
            return !part.startRole().isEmpty() ? part.startRole() : part.endRole();
        }

        private boolean hasPolicyMuteSend(String roleId) {
            List<ReplayVoiceData.RecordedVoicePolicy> list = policiesByRole.get(roleId);
            if (list == null) return false;
            for (var p : list) if (p.muteSend()) return true;
            return false;
        }

        private boolean hasPolicyMuteReceive(String roleId) {
            List<ReplayVoiceData.RecordedVoicePolicy> list = policiesByRole.get(roleId);
            if (list == null) return false;
            for (var p : list) if (p.muteReceive()) return true;
            return false;
        }

        private double effectiveMaxDistance(float baseMaxDist, String speakerRole, String listenerRole) {
            double max = baseMaxDist > 0f ? baseMaxDist : voice.timelineMeta().defaultVoiceDistance();
            List<ReplayVoiceData.RecordedVoicePolicy> sp = policiesByRole.get(speakerRole);
            if (sp != null) {
                for (var p : sp) if (p.maxDistance() > 0f) max = Math.min(max, p.maxDistance());
            }
            List<ReplayVoiceData.RecordedVoicePolicy> ls = policiesByRole.get(listenerRole);
            if (ls != null) {
                for (var p : ls) if (p.maxDistance() > 0f) max = Math.min(max, p.maxDistance());
            }
            return max;
        }

        private boolean isBlockedByIsolationPolicy(String speakerRole, String listenerRole,
                                                   @Nullable UUID speakerGroup, @Nullable UUID listenerGroup) {
            boolean sameGroup = speakerGroup != null && speakerGroup.equals(listenerGroup);
            if (sameGroup) return false;
            List<ReplayVoiceData.RecordedVoicePolicy> sp = policiesByRole.get(speakerRole);
            if (sp != null) {
                for (var p : sp) if (p.isolateGroup() && !p.hearWorld()) return true;
            }
            List<ReplayVoiceData.RecordedVoicePolicy> ls = policiesByRole.get(listenerRole);
            if (ls != null) {
                for (var p : ls) if (p.isolateGroup() && !p.hearWorld()) return true;
            }
            return false;
        }

        private float sandboxAxisPan(double x, double y, double z) {
            if (layout == null) return 0f;
            var q = LastMatchReplay.Position.quantize(x, y, z);
            if (q == null) return 0f;
            var pt = layout.project(q);
            float along = layout.source().sizeZ() > layout.source().sizeX() ? (float) pt.z() : (float) pt.x();
            return Math.clamp(along * 0.22f, -0.55f, 0.55f);
        }
    }

    // ===== Static Convenience Facades =====

    public static List<AudibleVoice> resolveVoices(LastMatchReplay replay, @Nullable ReplayVoiceData voice,
                                                   @Nullable UUID listenerUuid, long matchMs) {
        return Context.of(replay, voice).resolveVoices(listenerUuid, matchMs);
    }

    public static List<AudibleVoice> resolveVoicesInWindow(LastMatchReplay replay, @Nullable ReplayVoiceData voice,
                                                           @Nullable UUID listenerUuid, long windowStartMs, long windowEndMs) {
        return Context.of(replay, voice).resolveVoicesInWindow(listenerUuid, windowStartMs, windowEndMs);
    }

    public static List<AudibleVoice> resolveVoicesAtCamera(LastMatchReplay replay, @Nullable ReplayVoiceData voice,
                                                           double camX, double camY, double camZ, float camYawDeg,
                                                           double maxDistBlocks, long matchMs) {
        return Context.of(replay, voice).resolveVoicesAtCamera(camX, camY, camZ, camYawDeg, maxDistBlocks, matchMs);
    }

    public static @Nullable AudibleSound resolveGameSound(LastMatchReplay replay, @Nullable UUID listenerUuid,
                                                          ReplayGameSoundData.Cue cue) {
        return Context.of(replay, null).resolveGameSound(listenerUuid, cue);
    }

    public static List<AudibleSound> resolveGameSoundsInWindow(LastMatchReplay replay, @Nullable UUID listenerUuid,
                                                               int windowStartMs, int windowEndMs) {
        return Context.of(replay, null).resolveGameSoundsInWindow(listenerUuid, windowStartMs, windowEndMs);
    }

    public static @Nullable AudibleSound resolveGameSoundAtCamera(LastMatchReplay replay,
                                                                  double camX, double camY, double camZ,
                                                                  float camYawDeg, ReplayGameSoundData.Cue cue) {
        return Context.of(replay, null).resolveGameSoundAtCamera(camX, camY, camZ, camYawDeg, cue);
    }

    // ===== Pure Spatial Math Helpers =====

    public static double distance3d(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static float distanceGain(double dist, double maxDist) {
        if (maxDist <= 0.0 || dist >= maxDist) return 0f;
        if (dist <= 2.0) return 1.0f;
        return Math.clamp(1.0f - (float) (dist / maxDist), 0.0f, 1.0f);
    }

    /**
     * Computes stereo pan in {@code [-0.85, +0.85]} (negative = left ear, positive = right ear)
     * given listener position and Minecraft yaw in degrees ({@code 0 = +Z South, 90 = -X West}).
     */
    public static float stereoPan(double listenerX, double listenerZ, float listenerYawDeg,
                                  double sourceX, double sourceZ) {
        double dx = sourceX - listenerX;
        double dz = sourceZ - listenerZ;
        double horiz = Math.hypot(dx, dz);
        if (horiz < 0.25) return 0f;
        double yawRad = Math.toRadians(listenerYawDeg);
        // Minecraft forward = (-sin, +cos) => listener's right ear = (-cos, -sin)
        double rx = -Math.cos(yawRad);
        double rz = -Math.sin(yawRad);
        double dotRight = (dx * rx + dz * rz) / horiz;
        double nearFade = Math.min(1.0, horiz / 3.5);
        return Math.clamp((float) (dotRight * nearFade * MAX_STEREO_PAN), -MAX_STEREO_PAN, MAX_STEREO_PAN);
    }
}
