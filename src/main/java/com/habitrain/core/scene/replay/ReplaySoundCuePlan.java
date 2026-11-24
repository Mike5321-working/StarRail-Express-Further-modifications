package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayKillSounds;
import com.habitrain.core.api.match.replay.ReplayProjection;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import org.jetbrains.annotations.Nullable;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.IntToDoubleFunction;
import java.util.function.Predicate;

/**
 * Shared deterministic audio cue planner for both exported replay videos and live 3D board / history
 * playback.
 *
 * <p>Every weapon/item cue and proximity voice clip is anchored to the exact impact timestamp
 * ({@code beat.impact()} in video export and {@code timeline.actionTickAt(event.tick())} in 3D board
 * close-ups) so visual cuts and sound effects strike on the exact same frame.</p>
 */
public final class ReplaySoundCuePlan {
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double MIN_VOICE_GAP_SECONDS = 0.08;
    private static final double RECORDED_CORE_WINDOW_SECONDS = 0.75;
    public static final List<String> VIDEO_MATCH_START_SOUNDS = List.of(
            "starrailexpress:ambient.train.horn", "wathe:ambient.train.horn");
    public static final List<String> VIDEO_MATCH_END_SOUNDS = List.of(
            "starrailexpress:ui.piano_win", "wathe:ui.piano_win");
    private static final float VIDEO_MATCH_START_VOLUME = 0.85f;
    private static final float VIDEO_MATCH_END_VOLUME = 1.50f;

    private ReplaySoundCuePlan() {}

    /**
     * One game sound event cue scheduled along a timeline.
     *
     * @param time        video seconds (for {@link #forVideo}) or presentation/match ticks (for {@link #forBoard} / {@link #forLinearReplay})
     * @param eventIndex  index into {@code replay.events()}, or -1 for synthetic cues
     * @param candidates  ordered sound event resource IDs (first available mod sound, then vanilla fallback)
     * @param volume      relative gain (0..1.5)
     * @param pitch       playback pitch multiplier
     * @param pan         stereo pan (-1 left .. +1 right, 0 center)
     */
    public record SoundCue(double time, int eventIndex, List<String> candidates, float volume, float pitch, float pan,
                           @Nullable ResourceLocation recordedFile) {
        public SoundCue {
            candidates = List.copyOf(candidates);
        }

        public SoundCue(double time, int eventIndex, List<String> candidates, float volume, float pitch, float pan) {
            this(time, eventIndex, candidates, volume, pitch, pan, null);
        }
    }

    /**
     * One recorded proximity voice segment anchored to a close-up/highlight.
     *
     * @param clip       recorded voice data
     * @param startTime  audible start after trimming (video seconds or presentation/match ticks)
     * @param impactTime timeline impact instant (where clip offset {@code 0ms} lands)
     * @param endTime    maximum allowed end time before cutting or fading out
     * @param pan        stereo pan (-1 .. +1)
     */
    public record VoiceCue(ReplayVoiceData.Clip clip, double startTime, double impactTime, double endTime, float pan) {
        public double sampleStartTime(double unitsPerSecond) {
            return impactTime + clip.offsetMs() * unitsPerSecond / 1000.0;
        }
    }

    public record Schedule(List<SoundCue> sounds, List<VoiceCue> voices) {
        public static final Schedule EMPTY = new Schedule(List.of(), List.of());
    }

    private record RelativeSound(double offsetSeconds, List<String> candidates, float volume, float pitch) {}

    /**
     * Selects event indices in {@code replay.events()} eligible for server voice capture around close-ups
     * and highlights. Prioritizes kills and explosions while capping at {@link ReplayVoiceData#MAX_CLIPS}.
     */
    public static List<Integer> selectCloseUpEventIndices(LastMatchReplay replay) {
        return replay == null ? List.of() : selectCloseUpEventIndices(replay.events());
    }

    public static List<Integer> selectCloseUpEventIndices(List<LastMatchReplay.Node> events) {
        if (events.isEmpty()) return List.of();
        record Candidate(int index, int score, int tick) {}
        List<Candidate> ranked = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            LastMatchReplay.Node node = events.get(i);
            if (node.from() == null && node.to() == null) continue;
            int score = switch (node.kind()) {
                case KILL -> 100;
                case BOMB_DETONATE, GRENADE_EXPLOSION -> 80;
                case PLAYER_REVIVAL -> 70;
                case BOMB_DEFUSE -> 60;
                case SHIELD_BROKEN -> 40;
                case GUNSHOT -> (node.flags() & (LastMatchReplay.HIT | LastMatchReplay.BACKFIRE)) != 0 ? 35 : 0;
                default -> 0;
            };
            if (score > 0) ranked.add(new Candidate(i, score, node.tick()));
        }
        ranked.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(Candidate::tick));
        List<Integer> selected = new ArrayList<>();
        for (Candidate c : ranked) {
            if (selected.size() >= ReplayVoiceData.MAX_CLIPS) break;
            selected.add(c.index());
        }
        selected.sort(Comparator.naturalOrder());
        return List.copyOf(selected);
    }

    /** Builds the sample-synchronized audio schedule for an exported video ({@code time} in video seconds). */
    public static Schedule forVideo(LastMatchReplay replay, ReplayVideoScript script, @Nullable ReplayVoiceData voice) {
        return forVideo(replay, script, voice, file -> true);
    }

    public static Schedule forVideo(LastMatchReplay replay, ReplayVideoScript script, @Nullable ReplayVoiceData voice,
                                    Predicate<ResourceLocation> availableSound) {
        return forVideo(replay, script, voice, availableSound, true);
    }

    public static Schedule forVideo(LastMatchReplay replay, ReplayVideoScript script, @Nullable ReplayVoiceData voice,
                                    Predicate<ResourceLocation> availableSound, boolean includeVictoryCue) {
        if (replay == null || script == null) return Schedule.EMPTY;
        List<LastMatchReplay.Node> events = replay.events();
        Map<Integer, Integer> idMap = stableEventIndices(events);
        Map<LastMatchReplay.Node, Integer> indexMap = identityIndices(events);
        ReplayProjection.Layout layout = ReplayProjection.layout(replay);

        Map<Integer, LastMatchReplay.Node> pairedGunshotToKill = findPairedGunshots(events);
        Set<Integer> suppressedGunshots = pairedGunshotToKill.keySet();
        Map<Integer, ReplayVoiceData.Clip> clipByEvent = indexVoiceClips(voice);
        var recorded = indexKillSounds(replay, availableSound);

        List<SoundCue> soundCues = new ArrayList<>();
        List<VoiceCue> voiceCues = new ArrayList<>();
        double titleStart = script.actStart(ReplayVideoScript.Act.TITLE);
        double actionStart = script.actStart(ReplayVideoScript.Act.ACTION);
        double reviewStart = script.actStart(ReplayVideoScript.Act.REVIEW);
        double soundEnd = Math.max(actionStart, reviewStart - 0.05);
        double lastVoiceEnd = actionStart;

        soundCues.add(new SoundCue(titleStart, -1, VIDEO_MATCH_START_SOUNDS, VIDEO_MATCH_START_VOLUME, 1.0f, 0f));
        if (includeVictoryCue) {
            soundCues.add(new SoundCue(script.actStart(ReplayVideoScript.Act.VICTORY), -1,
                    VIDEO_MATCH_END_SOUNDS, VIDEO_MATCH_END_VOLUME, 1.0f, 0f));
        }

        List<ReplayVideoScript.Beat> beats = script.beats();
        Set<Integer> handledEvents = new HashSet<>();

        for (int b = 0; b < beats.size(); b++) {
            ReplayVideoScript.Beat beat = beats.get(b);
            int primaryIdx = eventIndexOf(beat.node(), idMap, indexMap);
            if (primaryIdx >= 0) handledEvents.add(primaryIdx);
            float pan = panFor(layout, beat.node());

            LastMatchReplay.Node pairedShot = findPairedShotForKill(events, primaryIdx, beat.node());
            if (pairedShot != null) {
                int shotIdx = eventIndexOf(pairedShot, idMap, indexMap);
                if (shotIdx >= 0) handledEvents.add(shotIdx);
            }

            addSounds(soundCues, recorded, primaryIdx, beat.node(), pairedShot, beat.impact(), 1.0,
                    actionStart,
                    soundEnd, soundEnd, 1f, pan);

            ReplayVoiceData.Clip clip = findClipForBeat(primaryIdx, pairedShot == null ? -1 : eventIndexOf(pairedShot, idMap, indexMap), clipByEvent);
            if (clip != null && clip.sampleCount() > 0) {
                double nextBeatStart = (b + 1 < beats.size()) ? beats.get(b + 1).approach() - 0.15 : reviewStart;
                double rawStart = beat.impact() + clip.offsetMs() / 1000.0;
                double start = Math.max(Math.max(actionStart, beat.approach() - 0.35), lastVoiceEnd + MIN_VOICE_GAP_SECONDS);
                double anchorShift = Math.max(0.0, start - rawStart);
                // Keep alignment exact when there is room; only clip lead-in if an earlier voice is still finishing.
                double effectiveStart = Math.max(start, rawStart);
                double maxEnd = Math.min(reviewStart, Math.min(nextBeatStart, beat.end() + 0.55));
                double desiredEnd = rawStart + clip.durationSeconds();
                double effectiveEnd = Math.min(maxEnd, desiredEnd);
                if (effectiveEnd - effectiveStart >= 0.18 && anchorShift < clip.durationSeconds() - 0.18) {
                    voiceCues.add(new VoiceCue(clip, effectiveStart, beat.impact(), effectiveEnd, pan));
                    lastVoiceEnd = effectiveEnd;
                }
            }
        }

        // Also add non-featured kill/explosion/shot weapon cues along the spline clock so secondary kills never go silent.
        int actionEndTick = script.actionEndTick();
        for (int i = 0; i < events.size(); i++) {
            if (handledEvents.contains(i) || suppressedGunshots.contains(i)) continue;
            LastMatchReplay.Node node = events.get(i);
            if (node.tick() > actionEndTick) continue;
            ReplayVideoScript.BeatType type = ReplayVideoScript.classify(node);
            if (type == null) continue;
            if (node.kind() == LastMatchReplay.Kind.GUNSHOT && (node.flags() & (LastMatchReplay.HIT | LastMatchReplay.BACKFIRE)) == 0) {
                continue;
            }
            double impactTime = script.videoTimeOf(node.tick());
            float pan = panFor(layout, node);
            LastMatchReplay.Node pairedShot = findPairedShotForKill(events, i, node);
            addSounds(soundCues, recorded, i, node, pairedShot, impactTime, 1.0,
                    actionStart, soundEnd, soundEnd, .72f, pan);
        }

        addUnfeaturedKillVoices(voiceCues, replay, clipByEvent, script::videoTimeOf, 1.0, actionStart, reviewStart, actionEndTick);
        soundCues.sort(Comparator.comparingDouble(SoundCue::time));
        voiceCues.sort(Comparator.comparingDouble(VoiceCue::startTime));
        return new Schedule(List.copyOf(soundCues), List.copyOf(voiceCues));
    }

    /**
     * Builds the audio schedule for the in-world 3D {@code SettlementSummaryBoard} diorama
     * ({@code time} in presentation ticks since {@link ReplayTimeline.Phase#PLAY}, with an effects tail in review).
     */
    public static Schedule forBoard(LastMatchReplay replay, ReplayTimeline timeline, @Nullable ReplayVoiceData voice) {
        return forBoard(replay, timeline, voice, file -> true);
    }

    public static Schedule forBoard(LastMatchReplay replay, ReplayTimeline timeline, @Nullable ReplayVoiceData voice,
                                    Predicate<ResourceLocation> availableSound) {
        if (replay == null || timeline == null) return Schedule.EMPTY;
        List<LastMatchReplay.Node> events = replay.events();
        Map<LastMatchReplay.Node, Integer> indexMap = identityIndices(events);
        Map<Integer, LastMatchReplay.Node> pairedGunshotToKill = findPairedGunshots(events);
        Set<Integer> suppressedGunshots = pairedGunshotToKill.keySet();
        Map<Integer, ReplayVoiceData.Clip> clipByEvent = indexVoiceClips(voice);
        var recorded = indexKillSounds(replay, availableSound);

        double actionLength = timeline.cycleTicks() - ReplayTimeline.INTRO - ReplayTimeline.REVIEW - ReplayTimeline.HONORS;
        if (actionLength <= 0) return Schedule.EMPTY;
        double recordedEnd = actionLength + ReplayKillSounds.AFTER_MS * TICKS_PER_SECOND / 1000.0;

        // Replicate ReplayFocusPlan close-up selection so sound cues lock directly onto every close-up zoom.
        List<LastMatchReplay.Node> sorted = events.stream()
                .filter(e -> ReplayFocusPlan.priority(e) > 0 && (e.from() != null || e.to() != null))
                .sorted(Comparator.comparingInt(LastMatchReplay.Node::tick)
                        .thenComparing(Comparator.comparingInt(ReplayFocusPlan::priority).reversed()))
                .toList();

        List<Double> cueTicks = new ArrayList<>();
        List<LastMatchReplay.Node> cueEvents = new ArrayList<>();
        for (var event : sorted) {
            double at = timeline.actionTickAt(event.tick());
            if (!cueTicks.isEmpty()) {
                double prevTick = cueTicks.getLast();
                int prevPriority = ReplayFocusPlan.priority(cueEvents.getLast());
                int priority = ReplayFocusPlan.priority(event);
                if (at - prevTick < ReplayFocusPlan.ATTACK
                        || at - prevTick < ReplayFocusPlan.HOLD && priority < prevPriority) {
                    continue;
                }
            }
            cueTicks.add(at);
            cueEvents.add(event);
        }

        List<SoundCue> soundCues = new ArrayList<>();
        List<VoiceCue> voiceCues = new ArrayList<>();
        Set<Integer> handledEvents = new HashSet<>();
        double lastVoiceEndTick = 0;

        for (int c = 0; c < cueEvents.size(); c++) {
            LastMatchReplay.Node cueNode = cueEvents.get(c);
            double impactTick = cueTicks.get(c);
            int idx = indexMap.getOrDefault(cueNode, -1);

            // If ReplayFocusPlan focused on a GUNSHOT that preceded a KILL by 0..2 ticks, upgrade the cue
            // to the KILL's full weapon + voice pair at the close-up punch tick so audio & camera agree.
            LastMatchReplay.Node effectiveNode = cueNode;
            LastMatchReplay.Node pairedShot = null;
            int effectiveIdx = idx;
            if (cueNode.kind() == LastMatchReplay.Kind.GUNSHOT && pairedGunshotToKill.containsKey(idx)) {
                effectiveNode = pairedGunshotToKill.get(idx);
                effectiveIdx = indexMap.getOrDefault(effectiveNode, idx);
                pairedShot = cueNode;
                handledEvents.add(idx);
            } else if (cueNode.kind() == LastMatchReplay.Kind.KILL) {
                pairedShot = findPairedShotForKill(events, idx, cueNode);
                if (pairedShot != null) handledEvents.add(indexMap.getOrDefault(pairedShot, -1));
            }
            if (effectiveIdx >= 0) handledEvents.add(effectiveIdx);

            int prio = ReplayFocusPlan.priority(effectiveNode);
            if (prio >= 2 || effectiveNode.kind() == LastMatchReplay.Kind.GUNSHOT) {
                float gain = prio >= 3 ? 1.0f : 0.7f;
                addSounds(soundCues, recorded, effectiveIdx, effectiveNode, pairedShot, impactTick, TICKS_PER_SECOND,
                        0, actionLength - 1.0, recordedEnd, gain, 0f);
            }

            ReplayVoiceData.Clip clip = findClipForBeat(effectiveIdx, idx, clipByEvent);
            if (clip != null && clip.sampleCount() > 0 && prio >= 2) {
                double clipStartTick = impactTick + clip.offsetMs() / 50.0;
                double clipEndTick = clipStartTick + clip.durationSeconds() * TICKS_PER_SECOND;
                double nextCueTick = (c + 1 < cueTicks.size()) ? cueTicks.get(c + 1) - 1.0 : actionLength;
                double clampedStart = Math.max(0.0, Math.max(clipStartTick, lastVoiceEndTick + 1.0));
                double clampedEnd = Math.min(actionLength, Math.min(nextCueTick, clipEndTick));
                if (clampedEnd - clampedStart >= 3.5) {
                    voiceCues.add(new VoiceCue(clip, clampedStart, impactTick, clampedEnd, 0f));
                    lastVoiceEndTick = clampedEnd;
                }
            }
        }

        // Non-close-up highlights (e.g. a kill during cooldown of another kill) still emit quieter SFX at their actionTick.
        for (int i = 0; i < events.size(); i++) {
            if (handledEvents.contains(i) || suppressedGunshots.contains(i)) continue;
            LastMatchReplay.Node node = events.get(i);
            int prio = ReplayFocusPlan.priority(node);
            if (prio < 3 && (node.kind() != LastMatchReplay.Kind.GUNSHOT || (node.flags() & LastMatchReplay.HIT) == 0)) {
                continue;
            }
            double impactTick = timeline.actionTickAt(node.tick());
            LastMatchReplay.Node pairedShot = findPairedShotForKill(events, i, node);
            addSounds(soundCues, recorded, i, node, pairedShot, impactTick, TICKS_PER_SECOND,
                    0, actionLength - 1.0, recordedEnd, .65f, 0f);
        }

        addUnfeaturedKillVoices(voiceCues, replay, clipByEvent, timeline::actionTickAt, TICKS_PER_SECOND, 0, actionLength);
        soundCues.sort(Comparator.comparingDouble(SoundCue::time));
        voiceCues.sort(Comparator.comparingDouble(VoiceCue::startTime));
        return new Schedule(List.copyOf(soundCues), List.copyOf(voiceCues));
    }

    /**
     * Builds a 1.0x linear match-tick audio schedule for {@code ReplayHistoryScreen} scrubbing/playback
     * ({@code time} in match ticks {@code 0 .. durationTicks}).
     */
    public static Schedule forLinearReplay(LastMatchReplay replay, @Nullable ReplayVoiceData voice) {
        return forLinearReplay(replay, voice, file -> true);
    }

    public static Schedule forLinearReplay(LastMatchReplay replay, @Nullable ReplayVoiceData voice,
                                           Predicate<ResourceLocation> availableSound) {
        if (replay == null) return Schedule.EMPTY;
        List<LastMatchReplay.Node> events = replay.events();
        Map<LastMatchReplay.Node, Integer> indexMap = identityIndices(events);
        Map<Integer, LastMatchReplay.Node> pairedGunshots = findPairedGunshots(events);
        Map<Integer, ReplayVoiceData.Clip> clipByEvent = indexVoiceClips(voice);
        var recorded = indexKillSounds(replay, availableSound);

        List<SoundCue> soundCues = new ArrayList<>();
        List<VoiceCue> voiceCues = new ArrayList<>();
        int duration = Math.max(0, replay.durationTicks());
        double lastVoiceEnd = 0;

        for (int i = 0; i < events.size(); i++) {
            if (pairedGunshots.containsKey(i)) continue;
            LastMatchReplay.Node node = events.get(i);
            int prio = ReplayFocusPlan.priority(node);
            if (prio < 2 && node.kind() != LastMatchReplay.Kind.GUNSHOT) continue;
            if (node.kind() == LastMatchReplay.Kind.GUNSHOT && (node.flags() & (LastMatchReplay.HIT | LastMatchReplay.BACKFIRE)) == 0) {
                continue;
            }
            LastMatchReplay.Node pairedShot = findPairedShotForKill(events, i, node);
            addSounds(soundCues, recorded, i, node, pairedShot, node.tick(), TICKS_PER_SECOND,
                    0, duration, duration + ReplayKillSounds.AFTER_MS / 50.0, 1f, 0f);
            ReplayVoiceData.Clip clip = findClipForBeat(i,
                    pairedShot == null ? -1 : indexMap.getOrDefault(pairedShot, -1), clipByEvent);
            if (clip != null && clip.sampleCount() > 0) {
                double rawStart = node.tick() + clip.offsetMs() / 50.0;
                double rawEnd = rawStart + clip.durationSeconds() * TICKS_PER_SECOND;
                double start = Math.max(0.0, Math.max(rawStart, lastVoiceEnd + 1.0));
                double end = Math.min(duration + 40.0, rawEnd);
                if (end - start >= 3.5) {
                    voiceCues.add(new VoiceCue(clip, start, node.tick(), end, 0f));
                    lastVoiceEnd = end;
                }
            }
        }
        soundCues.sort(Comparator.comparingDouble(SoundCue::time));
        voiceCues.sort(Comparator.comparingDouble(VoiceCue::startTime));
        return new Schedule(List.copyOf(soundCues), List.copyOf(voiceCues));
    }

    private static Map<Integer, List<ReplayKillSounds.Cue>> indexKillSounds(LastMatchReplay replay,
                                                                          Predicate<ResourceLocation> availableSound) {
        Map<Integer, List<ReplayKillSounds.Cue>> windows = new HashMap<>();
        Map<ResourceLocation, Boolean> availability = new HashMap<>();
        for (var window : replay.killSounds()) {
            var playable = window.cues().stream().filter(cue ->
                    availability.computeIfAbsent(cue.file(), availableSound::test)).toList();
            if (!playable.isEmpty()) windows.put(window.eventIndex(), playable);
        }
        return windows;
    }

    private static void addSounds(List<SoundCue> output, Map<Integer, List<ReplayKillSounds.Cue>> recorded, int index,
                                  LastMatchReplay.Node node, @Nullable LastMatchReplay.Node pairedShot,
                                  double impact, double unitsPerSecond, double start, double end, double recordedEnd,
                                  float fallbackGain, float pan) {
        var window = recorded.get(index);
        boolean hasRecordedCoreCue = false;
        if (window != null) {
            for (var cue : window) {
                double at = impact + cue.offsetMs() * unitsPerSecond / 1000.0;
                if (at >= start && at <= recordedEnd + 1e-6) {
                    output.add(new SoundCue(at, index, List.of(), cue.volume(), cue.pitch(), pan, cue.file()));
                }
                if (at >= start && at <= recordedEnd + 1e-6
                        && Math.abs(at - impact) <= RECORDED_CORE_WINDOW_SECONDS * unitsPerSecond) {
                    hasRecordedCoreCue = true;
                }
            }
        }
        if (!hasRecordedCoreCue) {
            for (var cue : resolveEventSounds(node, pairedShot)) {
                double at = Math.clamp(impact + cue.offsetSeconds() * unitsPerSecond, start, end);
                output.add(new SoundCue(at, index, cue.candidates(), cue.volume() * fallbackGain, cue.pitch(), pan));
            }
        }
    }

    private static void addUnfeaturedKillVoices(List<VoiceCue> output, LastMatchReplay replay,
                                                 Map<Integer, ReplayVoiceData.Clip> clips,
                                                 IntToDoubleFunction timeline,
                                                 double unitsPerSecond, double start, double end) {
        addUnfeaturedKillVoices(output, replay, clips, timeline, unitsPerSecond, start, end, Integer.MAX_VALUE);
    }

    private static void addUnfeaturedKillVoices(List<VoiceCue> output, LastMatchReplay replay,
                                                 Map<Integer, ReplayVoiceData.Clip> clips,
                                                 IntToDoubleFunction timeline,
                                                 double unitsPerSecond, double start, double end, int maxTick) {
        if (clips.isEmpty()) return;
        var byStart = Comparator.comparingDouble(VoiceCue::startTime);
        output.sort(byStart);
        double gap = MIN_VOICE_GAP_SECONDS * unitsPerSecond;
        for (int index = 0; index < replay.events().size(); index++) {
            var node = replay.events().get(index);
            var clip = clips.get(index);
            if (clip == null || node.kind() != LastMatchReplay.Kind.KILL || node.tick() > maxTick
                    || output.stream().anyMatch(cue -> cue.clip() == clip)) continue;
            double impact = timeline.applyAsDouble(node.tick());
            double clipStart = impact + clip.offsetMs() * unitsPerSecond / 1000.0;
            double clipEnd = Math.min(end, clipStart + clip.durationSeconds() * unitsPerSecond);
            double freeStart = Math.max(start, clipStart);
            double bestStart = freeStart;
            double bestEnd = freeStart;
            for (var occupied : output) {
                double freeEnd = Math.min(clipEnd, occupied.startTime() - gap);
                if (freeEnd - freeStart > bestEnd - bestStart) {
                    bestStart = freeStart;
                    bestEnd = freeEnd;
                }
                freeStart = Math.max(freeStart, occupied.endTime() + gap);
                if (freeStart >= clipEnd) break;
            }
            if (clipEnd - freeStart > bestEnd - bestStart) {
                bestStart = freeStart;
                bestEnd = clipEnd;
            }
            if (bestEnd - bestStart >= .18 * unitsPerSecond) {
                output.add(new VoiceCue(clip, bestStart, impact, bestEnd, 0f));
                output.sort(byStart);
            }
        }
    }

    private static int eventIndexOf(LastMatchReplay.Node node, Map<Integer, Integer> idIndex,
                                    Map<LastMatchReplay.Node, Integer> fallbackIdentity) {
        if (node.eventId() >= 0) {
            Integer byId = idIndex.get(node.eventId());
            if (byId != null) return byId;
        }
        return fallbackIdentity.getOrDefault(node, -1);
    }

    private static Map<Integer, Integer> stableEventIndices(List<LastMatchReplay.Node> events) {
        Map<Integer, Integer> map = new HashMap<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            int id = events.get(i).eventId();
            if (id >= 0) map.putIfAbsent(id, i);
        }
        return map;
    }

    private static Map<LastMatchReplay.Node, Integer> identityIndices(List<LastMatchReplay.Node> events) {
        Map<LastMatchReplay.Node, Integer> map = new IdentityHashMap<>(events.size());
        for (int i = 0; i < events.size(); i++) map.put(events.get(i), i);
        return map;
    }

    private static Map<Integer, ReplayVoiceData.Clip> indexVoiceClips(@Nullable ReplayVoiceData voice) {
        if (voice == null || voice.isEmpty()) return Map.of();
        Map<Integer, ReplayVoiceData.Clip> map = new HashMap<>();
        for (ReplayVoiceData.Clip clip : voice.clips()) {
            map.putIfAbsent(clip.eventIndex(), clip);
        }
        return map;
    }

    private static @Nullable ReplayVoiceData.Clip findClipForBeat(int primaryIdx, int secondaryIdx,
                                                                   Map<Integer, ReplayVoiceData.Clip> clipByEvent) {
        if (primaryIdx >= 0) {
            ReplayVoiceData.Clip clip = clipByEvent.get(primaryIdx);
            if (clip != null) return clip;
        }
        if (secondaryIdx >= 0) {
            return clipByEvent.get(secondaryIdx);
        }
        return null;
    }

    private static Map<Integer, LastMatchReplay.Node> findPairedGunshots(List<LastMatchReplay.Node> events) {
        Map<Integer, LastMatchReplay.Node> paired = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            LastMatchReplay.Node shot = events.get(i);
            if (shot.kind() != LastMatchReplay.Kind.GUNSHOT) continue;
            for (int j = Math.max(0, i - 4); j < Math.min(events.size(), i + 8); j++) {
                LastMatchReplay.Node kill = events.get(j);
                if (kill.kind() != LastMatchReplay.Kind.KILL) continue;
                int dt = kill.tick() - shot.tick();
                if (dt < 0 || dt > 2) continue;
                if (Objects.equals(shot.actor(), kill.actor())) {
                    paired.put(i, kill);
                    break;
                }
            }
        }
        return paired;
    }

    private static @Nullable LastMatchReplay.Node findPairedShotForKill(List<LastMatchReplay.Node> events,
                                                                         int killIndex,
                                                                         LastMatchReplay.Node kill) {
        if (kill.kind() != LastMatchReplay.Kind.KILL || killIndex < 0) return null;
        for (int i = Math.max(0, killIndex - 8); i < Math.min(events.size(), killIndex + 4); i++) {
            LastMatchReplay.Node candidate = events.get(i);
            if (candidate.kind() != LastMatchReplay.Kind.GUNSHOT) continue;
            int dt = kill.tick() - candidate.tick();
            if (dt >= 0 && dt <= 2 && Objects.equals(candidate.actor(), kill.actor())) {
                return candidate;
            }
        }
        return null;
    }

    private static List<RelativeSound> resolveEventSounds(LastMatchReplay.Node node,
                                                            @Nullable LastMatchReplay.Node pairedShot) {
        List<RelativeSound> out = new ArrayList<>(3);
        switch (node.kind()) {
            case KILL -> {
                String detail = node.detail();
                if (pairedShot != null && !pairedShot.detail().isBlank()
                        && (detail == null || detail.isBlank() || ReplayDeathIcons.classify(detail) == ReplayDeathIcons.Icon.SKULL)) {
                    detail = pairedShot.detail();
                }
                ReplayDeathIcons.Icon icon = ReplayDeathIcons.classify(detail);
                if (icon == ReplayDeathIcons.Icon.SKULL && pairedShot != null) {
                    icon = ReplayDeathIcons.classify(pairedShot.detail());
                }
                switch (icon) {
                    case KNIFE -> {
                        out.add(new RelativeSound(-0.18, List.of(
                                "starrailexpress:item.knife.prepare", "wathe:item.knife.prepare"), 0.45f, 1.0f));
                        out.add(new RelativeSound(0.0, List.of(
                                "starrailexpress:item.knife.stab", "wathe:item.knife.stab",
                                "minecraft:entity.player.attack.crit"), 1.0f, 1.0f));
                    }
                    case THROWING_KNIFE, SHURIKEN -> out.add(new RelativeSound(0.0, List.of(
                            "starrailexpress:item.knife.stab", "wathe:item.knife.stab",
                            "minecraft:entity.trident.hit"), 0.95f, 1.15f));
                    case SNIPER -> {
                        out.add(new RelativeSound(0.0, List.of(
                                "starrailexpress:item.sniper_rifle.shoot", "starrailexpress:item.revolver.shoot",
                                "wathe:item.revolver.shoot", "minecraft:entity.generic.explode"), 1.0f, 1.0f));
                        out.add(new RelativeSound(0.42, List.of(
                                "starrailexpress:item.sniper_rifle.reload"), 0.50f, 1.0f));
                    }
                    case SHOTGUN -> {
                        out.add(new RelativeSound(0.0, List.of(
                                "noellesroles:noellesroles.shotgun_fire", "starrailexpress:item.revolver.shoot",
                                "wathe:item.revolver.shoot", "minecraft:entity.generic.explode"), 1.0f, 1.0f));
                        out.add(new RelativeSound(0.38, List.of(
                                "noellesroles:noellesroles.shotgun_cock"), 0.55f, 1.0f));
                    }
                    case REVOLVER, DERRINGER -> out.add(new RelativeSound(0.0, List.of(
                            "starrailexpress:item.revolver.shoot", "wathe:item.revolver.shoot",
                            "minecraft:entity.generic.explode"), 1.0f, icon == ReplayDeathIcons.Icon.DERRINGER ? 1.12f : 1.0f));
                    case BACKFIRE -> {
                        out.add(new RelativeSound(-0.08, List.of(
                                "starrailexpress:item.revolver.click", "wathe:item.revolver.click"), 0.70f, 1.0f));
                        out.add(new RelativeSound(0.0, List.of(
                                "starrailexpress:item.revolver.shoot", "wathe:item.revolver.shoot",
                                "minecraft:entity.generic.explode"), 0.95f, 0.85f));
                    }
                    case BAT -> out.add(new RelativeSound(0.0, List.of(
                            "starrailexpress:item.bat.hit", "wathe:item.bat.hit",
                            "minecraft:entity.player.attack.knockback"), 1.0f, 1.0f));
                    case GRENADE, EXPLOSION -> {
                        if (detail != null && (detail.contains("c4") || detail.contains("bomb"))) {
                            out.add(new RelativeSound(-0.22, List.of(
                                    "noellesroles:noellesroles.c4_beep", "minecraft:block.note_block.hat"), 0.55f, 1.0f));
                        }
                        out.add(new RelativeSound(0.0, List.of(
                                "starrailexpress:item.grenade.explode", "wathe:item.grenade.explode",
                                "minecraft:entity.generic.explode"), 1.0f, 1.0f));
                    }
                    case POISON -> {
                        if (detail != null && detail.toLowerCase(Locale.ROOT).contains("syringe")) {
                            out.add(new RelativeSound(0.0, List.of(
                                    "noellesroles:noellesroles.syringe_stab", "starrailexpress:item.knife.stab",
                                    "wathe:item.knife.stab"), 0.90f, 1.0f));
                        } else {
                            out.add(new RelativeSound(0.0, List.of(
                                    "minecraft:entity.witch.drink", "minecraft:block.glass.break"), 0.85f, 1.0f));
                        }
                    }
                    case ARROW -> out.add(new RelativeSound(0.0, List.of(
                            "minecraft:entity.arrow.hit_player", "minecraft:entity.arrow.hit"), 0.95f, 1.0f));
                    case FALL -> out.add(new RelativeSound(0.0, List.of(
                            "minecraft:entity.player.big_fall"), 0.90f, 1.0f));
                    default -> out.add(new RelativeSound(0.0, List.of(
                            "starrailexpress:item.knife.stab", "wathe:item.knife.stab",
                            "minecraft:entity.player.attack.crit"), 0.90f, 0.95f));
                }
            }
            case GUNSHOT -> {
                ReplayDeathIcons.Icon gun = ReplayDeathIcons.classify(node.detail());
                if ((node.flags() & LastMatchReplay.BACKFIRE) != 0) {
                    out.add(new RelativeSound(-0.08, List.of(
                            "starrailexpress:item.revolver.click", "wathe:item.revolver.click"), 0.65f, 1.0f));
                }
                if (gun == ReplayDeathIcons.Icon.SNIPER) {
                    out.add(new RelativeSound(0.0, List.of(
                            "starrailexpress:item.sniper_rifle.shoot", "starrailexpress:item.revolver.shoot",
                            "wathe:item.revolver.shoot", "minecraft:entity.generic.explode"), 0.90f, 1.0f));
                } else if (gun == ReplayDeathIcons.Icon.SHOTGUN) {
                    out.add(new RelativeSound(0.0, List.of(
                            "noellesroles:noellesroles.shotgun_fire", "starrailexpress:item.revolver.shoot",
                            "wathe:item.revolver.shoot", "minecraft:entity.generic.explode"), 0.90f, 1.0f));
                } else {
                    out.add(new RelativeSound(0.0, List.of(
                            "starrailexpress:item.revolver.shoot", "wathe:item.revolver.shoot",
                            "minecraft:entity.generic.explode"), 0.85f, gun == ReplayDeathIcons.Icon.DERRINGER ? 1.12f : 1.0f));
                }
            }
            case GRENADE_EXPLOSION, BOMB_DETONATE -> {
                if (node.kind() == LastMatchReplay.Kind.BOMB_DETONATE) {
                    out.add(new RelativeSound(-0.22, List.of(
                            "noellesroles:noellesroles.c4_beep", "minecraft:block.note_block.hat"), 0.55f, 1.0f));
                }
                out.add(new RelativeSound(0.0, List.of(
                        "starrailexpress:item.grenade.explode", "wathe:item.grenade.explode",
                        "minecraft:entity.generic.explode"), 0.95f, 1.0f));
            }
            case SHIELD_BROKEN -> out.add(new RelativeSound(0.0, List.of(
                    "starrailexpress:item.psycho.armour", "wathe:item.psycho.armour",
                    "minecraft:item.shield.break"), 0.85f, 1.0f));
            case BOMB_DEFUSE -> out.add(new RelativeSound(0.0, List.of(
                    "minecraft:entity.experience_orb.pickup", "minecraft:block.amethyst_block.chime"), 0.75f, 1.05f));
            case PLAYER_REVIVAL -> out.add(new RelativeSound(0.0, List.of(
                    "minecraft:item.totem.use", "minecraft:entity.player.levelup"), 0.65f, 1.15f));
            case POISON -> out.add(new RelativeSound(0.0, List.of(
                    "minecraft:entity.witch.drink", "minecraft:block.glass.break"), 0.70f, 1.0f));
            case GRENADE_THROWN -> out.add(new RelativeSound(0.0, List.of(
                    "starrailexpress:item.grenade.throw", "wathe:item.grenade.throw",
                    "minecraft:entity.snowball.throw"), 0.60f, 1.0f));
            case LOCKPICK_ATTEMPT -> out.add(new RelativeSound(0.0, List.of(
                    "starrailexpress:item.lockpick.door", "wathe:item.lockpick.door"), 0.50f, 1.0f));
            case DOOR_PRY -> out.add(new RelativeSound(0.0, List.of(
                    "starrailexpress:item.crowbar.pry", "wathe:item.crowbar.pry"), 0.55f, 1.0f));
            default -> {}
        }
        return out;
    }

    private static float panFor(@Nullable ReplayProjection.Layout layout, LastMatchReplay.Node node) {
        if (layout == null) return 0f;
        LastMatchReplay.Position pos = node.to() != null ? node.to() : node.from();
        if (pos == null) return 0f;
        var p = layout.project(pos);
        float along = layout.source().sizeZ() > layout.source().sizeX() ? (float) p.z() : (float) p.x();
        return Math.clamp(along * 0.22f, -0.55f, 0.55f);
    }
}
