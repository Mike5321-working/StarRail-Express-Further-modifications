package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayGameSoundData;
import com.habitrain.core.api.match.replay.ReplaySkillEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Multimodal highlight scoring engine, Non-Maximum Suppression (NMS) filter, and 5-tier rarity pyramid
 * allocator designed for matches up to 40 players and 20+ minutes.
 *
 * <p>Scoring weights:
 * <ul>
 *     <li><b>Acoustic Tension (30%)</b>: concurrent voice speakers, vocal energy spikes, combat sound density;</li>
 *     <li><b>Operational Complexity (35%)</b>: weapon/skill execution difficulty, combo chains, range/kinematics;</li>
 *     <li><b>Tactical Stakes (35%)</b>: first blood, final blow, multi-kill streaks, low-survivor clutch factor,
 *     high-value target elimination, turning-point revivals/defuses/blackouts.</li>
 * </ul>
 * </p>
 */
public final class ReplayHighlightScorer {
    public static final double WEIGHT_ACOUSTIC = 0.30;
    public static final double WEIGHT_COMPLEXITY = 0.35;
    public static final double WEIGHT_STAKES = 0.35;

    public static final int CLUSTER_WINDOW_TICKS = 40;      // 2.0 s event clustering
    public static final int COMBO_WINDOW_TICKS = 80;        // 4.0 s multi-action combo window
    public static final int STREAK_WINDOW_TICKS = 600;      // 30.0 s kill-streak window
    public static final double WITNESS_RADIUS_BLOCKS = 18.0;

    private static final double TICKS_PER_SECOND = 20.0;

    private ReplayHighlightScorer() {}

    /** Five-tier cinematic highlight rarity pyramid (ordered from subtle to legendary). */
    public enum HighlightTier {
        /** Tier 1 (8~14x): subtle diorama spotlight (1.65x scale + aura, others dimmed to 42%), no cut. */
        TIER_1_GLIMMER(1, 30.0, 50, 1.25),
        /** Tier 2 (4~6x): classic diorama dive-in close-up (1.0x -> 0.45x) + duel nameplate. */
        TIER_2_CLOSEUP(2, 46.0, 75, 0.45),
        /** Tier 3 (2~3x): 3.0s over-the-shoulder chase -> 90..115 deg whip to hero side close-up (0.32x). */
        TIER_3_SHADOW_SLASH(3, 60.0, 110, 0.32),
        /** Tier 4 (1~2x): over-the-shoulder -> 0.35s freeze + brass split -> right panel expands to 1x full POV. */
        TIER_4_DUAL_VISION(4, 72.0, 140, 0.35),
        /** Tier 5 (0~1x, strict bar): 4.0s gilded suction -> 1x seamless POV -> 0.4s freeze -> 360 deg 0.4x->1x encore. */
        TIER_5_APOTHEOSIS(5, 84.0, 200, 0.40);

        public final int level;
        public final double minScore;
        public final int minSeparationTicks;
        public final double signatureSlowRate;

        HighlightTier(int level, double minScore, int minSeparationTicks, double signatureSlowRate) {
            this.level = level;
            this.minScore = minScore;
            this.minSeparationTicks = minSeparationTicks;
            this.signatureSlowRate = signatureSlowRate;
        }
    }

    /** Dynamic quota bounds per tier for a given match size and duration. */
    public record PyramidQuota(int tier5Max, int tier4Max, int tier3Max, int tier2Max, int tier1Max) {
        public static PyramidQuota standard40p20m() {
            return new PyramidQuota(1, 2, 3, 6, 14);
        }

        public static PyramidQuota forMatch(int durationTicks, int participantCount, boolean personalScope) {
            double minutes = Math.max(1.0, durationTicks / (TICKS_PER_SECOND * 60.0));
            double players = Math.max(2.0, participantCount);
            // Reference point: 20 minutes, 40 players -> scale = 1.0 -> (1, 2, 3, 6, 14)
            double durationFactor = Math.clamp(minutes / 20.0, 0.25, 1.25);
            double crowdFactor = Math.clamp(players / 40.0, 0.30, 1.25);
            double scale = Math.clamp(0.65 * durationFactor + 0.35 * crowdFactor, 0.35, 1.10);
            if (personalScope) {
                scale = Math.clamp(scale * 0.75, 0.35, 0.85);
            }
            int t5 = 1; // Always 0..1, strictly threshold-gated ("宁缺毋滥")
            int t4 = Math.clamp((int) Math.round(2.0 * scale), 1, 2);
            int t3 = Math.clamp((int) Math.round(3.0 * scale), 1, 3);
            int t2 = Math.clamp((int) Math.round(6.0 * scale), 2, 6);
            int t1 = Math.clamp((int) Math.round(14.0 * scale), 4, 14);
            return new PyramidQuota(t5, t4, t3, t2, t1);
        }

        public int maxFor(HighlightTier tier) {
            return switch (tier) {
                case TIER_5_APOTHEOSIS -> tier5Max;
                case TIER_4_DUAL_VISION -> tier4Max;
                case TIER_3_SHADOW_SLASH -> tier3Max;
                case TIER_2_CLOSEUP -> tier2Max;
                case TIER_1_GLIMMER -> tier1Max;
            };
        }
    }

    /**
     * Acoustic telemetry queried around a match time window {@code [startMs, endMs]}.
     * Allows plugging in v2 multi-track voice + server game sounds without tight coupling.
     */
    public interface AcousticSignal {
        AcousticMetrics sample(long startMs, long endMs, @Nullable UUID focusActor);

        /** Optional list of high-energy personal voice windows {@code [startTick, endTick]} for Mode 2 Highlights Only. */
        default List<int[]> activeVoiceWindows(UUID speakerId) {
            return List.of();
        }

        AcousticSignal NONE = (s, e, a) -> AcousticMetrics.SILENT;
    }

    /**
     * Normalized acoustic intensity features for one window.
     *
     * @param concurrentSpeakers number of distinct players speaking simultaneously in the window
     * @param peakVoiceEnergy    0..1 vocal RMS/peak spike intensity
     * @param soundBurstCount    count of intense combat/ability game sounds in the window
     * @param hasRecordedAudio   false if voice/sound streams are unavailable (triggers fallback scoring)
     */
    public record AcousticMetrics(int concurrentSpeakers, double peakVoiceEnergy, int soundBurstCount,
                                  boolean hasRecordedAudio) {
        public static final AcousticMetrics SILENT = new AcousticMetrics(0, 0.0, 0, false);
    }

    /** One scored and tier-assigned highlight moment ready for {@link ReplayCinemaDirector}. */
    public record ScoredHighlight(
            int index,
            HighlightTier tier,
            LastMatchReplay.Node primaryNode,
            int tick,
            int comboStartTick,
            int comboEndTick,
            @Nullable UUID primaryActor,
            @Nullable UUID secondaryActor,
            double totalScore,
            double acousticScore,
            double complexityScore,
            double stakesScore,
            int streak,
            boolean firstBlood,
            boolean finalBlow,
            int clusterKills,
            ReplayVideoScript.BeatType beatType,
            List<LastMatchReplay.Node> clusterNodes
    ) {
        public ScoredHighlight {
            clusterNodes = List.copyOf(clusterNodes);
        }
    }

    /** One extracted real-time clip window for Personal Highlights Only mode. */
    public record PersonalClip(int startTick, int endTick, int anchorTick, String reason, double score) {}

    /**
     * Full multimodal acoustic adapter leveraging {@code HRVC v2} multi-track {@link ReplayVoiceData.VoiceUtterance}
     * (concurrent speaker count, precomputed RMS energy, personal voice windows), server-recorded
     * {@link ReplayGameSoundData}, and automatic fallback to legacy v1 clips.
     */
    public static AcousticSignal fromReplay(LastMatchReplay replay, @Nullable ReplayVoiceData voice) {
        if (replay == null) return AcousticSignal.NONE;
        boolean hasMultitrack = voice != null && voice.hasMultitrack();
        boolean hasGameSounds = !replay.gameSounds().isEmpty();
        if (!hasMultitrack && !hasGameSounds) {
            return fromLegacy(replay, voice);
        }
        ReplayGameSoundData gameSounds = replay.gameSounds();
        return new AcousticSignal() {
            @Override
            public AcousticMetrics sample(long startMs, long endMs, @Nullable UUID focusActor) {
                int speakers = 0;
                double peakVoice = 0.0;
                if (hasMultitrack) {
                    List<ReplayVoiceData.VoiceUtterance> active = voice.utterancesInWindow(startMs, endMs);
                    if (!active.isEmpty()) {
                        Set<UUID> distinct = new HashSet<>();
                        for (var u : active) {
                            distinct.add(u.speakerId());
                            peakVoice = Math.max(peakVoice, u.rmsEnergy());
                        }
                        speakers = distinct.size();
                    }
                }
                int bursts = 0;
                if (hasGameSounds) {
                    int sMs = (int) Math.clamp(startMs, 0L, Integer.MAX_VALUE);
                    int eMs = (int) Math.clamp(endMs, sMs, Integer.MAX_VALUE);
                    for (var cue : gameSounds.cuesInWindow(sMs, eMs)) {
                        String id = gameSounds.soundId(cue).toLowerCase(Locale.ROOT);
                        if (cue.volume() >= 0.7f || id.contains("shoot") || id.contains("stab") || id.contains("explode")
                                || id.contains("grenade") || id.contains("c4") || id.contains("alarm")
                                || id.contains("horn") || id.contains("shield") || id.contains("psycho")) {
                            bursts++;
                        }
                    }
                } else {
                    int startTick = (int) Math.floor(startMs / 50.0);
                    int endTick = (int) Math.ceil(endMs / 50.0);
                    for (LastMatchReplay.Node n : replay.events()) {
                        if (n.tick() < startTick || n.tick() > endTick) continue;
                        switch (n.kind()) {
                            case KILL, GUNSHOT, GRENADE_EXPLOSION, BOMB_DETONATE, SHIELD_BROKEN, BLACKOUT_START, PSYCHO_STATE_CHANGE -> bursts++;
                            default -> {}
                        }
                    }
                }
                return new AcousticMetrics(speakers, peakVoice, bursts, hasMultitrack || hasGameSounds);
            }

            @Override
            public List<int[]> activeVoiceWindows(UUID speakerId) {
                if (!hasMultitrack || speakerId == null) return List.of();
                List<int[]> windows = new ArrayList<>();
                for (var u : voice.utterances()) {
                    if (!speakerId.equals(u.speakerId())) continue;
                    if (u.rmsEnergy() < 0.12f && u.durationSeconds() < 0.6) continue;
                    int sTick = (int) Math.floor(u.startMs() / 50.0);
                    int eTick = (int) Math.ceil(u.endMs() / 50.0);
                    windows.add(new int[]{sTick, eTick});
                }
                return windows;
            }
        };
    }

    /** Default acoustic adapter using v1 {@link ReplayVoiceData} + {@link LastMatchReplay} event sound proxies. */
    public static AcousticSignal fromLegacy(LastMatchReplay replay, @Nullable ReplayVoiceData voice) {
        Map<Integer, Double> clipEnergyByTick = new HashMap<>();
        boolean hasVoice = voice != null && !voice.isEmpty();
        if (hasVoice) {
            for (ReplayVoiceData.Clip clip : voice.clips()) {
                short[] pcm = clip.decode24k();
                double rms = computeRmsNormalized(pcm);
                clipEnergyByTick.merge(clip.tick(), rms, Math::max);
            }
        }
        return (startMs, endMs, focusActor) -> {
            int startTick = (int) Math.floor(startMs / 50.0);
            int endTick = (int) Math.ceil(endMs / 50.0);
            double peakVoice = 0.0;
            int speakers = 0;
            if (hasVoice) {
                for (var entry : clipEnergyByTick.entrySet()) {
                    int t = entry.getKey();
                    if (t >= startTick && t <= endTick) {
                        speakers++;
                        peakVoice = Math.max(peakVoice, entry.getValue());
                    }
                }
            }
            int bursts = 0;
            for (LastMatchReplay.Node n : replay.events()) {
                if (n.tick() < startTick || n.tick() > endTick) continue;
                switch (n.kind()) {
                    case KILL, GUNSHOT, GRENADE_EXPLOSION, BOMB_DETONATE, SHIELD_BROKEN, BLACKOUT_START, PSYCHO_STATE_CHANGE -> bursts++;
                    default -> {}
                }
            }
            return new AcousticMetrics(speakers, peakVoice, bursts, hasVoice || !replay.killSounds().isEmpty());
        };
    }

    /** Scores, NMS-filters, and assigns 1~5 tier highlights across the requested trim range. */
    public static List<ScoredHighlight> scoreAndAllocate(LastMatchReplay replay,
                                                         @Nullable ReplayVoiceData voice,
                                                         @Nullable UUID focusPlayer,
                                                         int trimStartTick,
                                                         int trimEndTick) {
        return scoreAndAllocate(replay, fromReplay(replay, voice), focusPlayer, trimStartTick, trimEndTick,
                PyramidQuota.forMatch(Math.max(0, trimEndTick - trimStartTick),
                        replay == null ? 0 : replay.participants().size(), focusPlayer != null));
    }

    public static List<ScoredHighlight> scoreAndAllocate(LastMatchReplay replay,
                                                         AcousticSignal acoustic,
                                                         @Nullable UUID focusPlayer,
                                                         int trimStartTick,
                                                         int trimEndTick,
                                                         PyramidQuota quota) {
        if (replay == null || replay.events().isEmpty()) return List.of();
        AcousticSignal signal = acoustic == null ? AcousticSignal.NONE : acoustic;
        int startTick = Math.max(0, trimStartTick);
        int endTick = trimEndTick < 0 ? Math.max(0, replay.durationTicks()) : Math.min(Math.max(0, replay.durationTicks()), trimEndTick);
        if (endTick < startTick) return List.of();

        MatchContext ctx = new MatchContext(replay, focusPlayer);
        List<RawCluster> clusters = buildClusters(replay, ctx, startTick, endTick, focusPlayer);
        if (clusters.isEmpty()) return List.of();

        List<Candidate> scored = new ArrayList<>(clusters.size());
        for (RawCluster cluster : clusters) {
            scored.add(evaluateCluster(replay, ctx, signal, cluster, focusPlayer));
        }

        // Sort highest score first; tie-break by more kills, then finalBlow/firstBlood, then chronological.
        scored.sort(Comparator.comparingDouble((Candidate c) -> -c.totalScore)
                .thenComparingInt(c -> -c.cluster.kills)
                .thenComparing(c -> !c.cluster.finalBlow)
                .thenComparing(c -> !c.cluster.firstBlood)
                .thenComparingInt(c -> c.tick));

        // 5-Tier Pyramid Allocation with strict tier-aware NMS separation.
        Map<HighlightTier, Integer> used = new HashMap<>();
        for (HighlightTier t : HighlightTier.values()) used.put(t, 0);

        List<Assigned> kept = new ArrayList<>();
        for (Candidate cand : scored) {
            HighlightTier assigned = pickTier(cand, quota, used, kept);
            if (assigned == null) continue;
            used.merge(assigned, 1, Integer::sum);
            kept.add(new Assigned(cand, assigned));
        }

        kept.sort(Comparator.comparingInt(a -> a.candidate.tick));
        List<ScoredHighlight> result = new ArrayList<>(kept.size());
        for (int i = 0; i < kept.size(); i++) {
            Assigned a = kept.get(i);
            Candidate c = a.candidate;
            result.add(new ScoredHighlight(
                    i,
                    a.tier,
                    c.primaryNode,
                    c.tick,
                    c.comboStartTick,
                    c.comboEndTick,
                    c.primaryActor,
                    c.secondaryActor,
                    c.totalScore,
                    c.acousticScore,
                    c.complexityScore,
                    c.stakesScore,
                    c.cluster.maxStreak,
                    c.cluster.firstBlood,
                    c.cluster.finalBlow,
                    c.cluster.kills,
                    c.beatType,
                    c.cluster.nodes
            ));
        }
        return List.copyOf(result);
    }

    /**
     * Extracts personal highlight windows for Mode 2 ({@link ReplayExportContentOptions.PersonalPacing#HIGHLIGHTS_ONLY}):
     * combat involvement, item/skill actions, witnessed nearby kills, and active voice utterances.
     */
    public static List<PersonalClip> selectPersonalHighlights(LastMatchReplay replay,
                                                              UUID focusPlayer,
                                                              @Nullable AcousticSignal acoustic,
                                                              int trimStartTick,
                                                              int trimEndTick) {
        if (replay == null || focusPlayer == null) return List.of();
        int dur = Math.max(0, replay.durationTicks());
        int lo = Math.max(0, trimStartTick);
        int hi = trimEndTick < 0 ? dur : Math.min(dur, trimEndTick);
        if (hi <= lo) return List.of();

        ReplayTrackSampler focusTrack = null;
        for (var p : replay.participants()) {
            if (focusPlayer.equals(p.id())) {
                focusTrack = new ReplayTrackSampler(p.track());
                break;
            }
        }

        List<PersonalClip> raw = new ArrayList<>();
        for (LastMatchReplay.Node node : replay.events()) {
            if (node.tick() < lo || node.tick() > hi) continue;
            boolean direct = focusPlayer.equals(node.actor()) || focusPlayer.equals(node.target());
            boolean witnessed = false;
            if (!direct && focusTrack != null && isWitnessWorthy(node)) {
                var pose = focusTrack.sample(node.tick());
                LastMatchReplay.Position evPos = node.to() != null ? node.to() : node.from();
                if (pose.visible() && evPos != null) {
                    double dx = pose.x() - evPos.blockX();
                    double dy = pose.y() - evPos.blockY();
                    double dz = pose.z() - evPos.blockZ();
                    witnessed = dx * dx + dy * dy + dz * dz <= WITNESS_RADIUS_BLOCKS * WITNESS_RADIUS_BLOCKS;
                }
            }
            if (!direct && !witnessed) continue;

            double score = baseEventComplexity(node);
            if (!direct) score *= 0.65;
            if (score < 22.0) continue;

            int lead = node.kind() == LastMatchReplay.Kind.KILL ? 70 : 50; // 3.5s / 2.5s lead-in
            int tail = node.kind() == LastMatchReplay.Kind.KILL ? 60 : 45; // 3.0s / 2.25s tail
            int s = Math.clamp(node.tick() - lead, lo, hi);
            int e = Math.clamp(node.tick() + tail, s, hi);
            String reason = direct ? node.kind().name().toLowerCase(Locale.ROOT) : "witness_" + node.kind().name().toLowerCase(Locale.ROOT);
            raw.add(new PersonalClip(s, e, node.tick(), reason, score));
        }

        if (acoustic != null) {
            for (int[] win : acoustic.activeVoiceWindows(focusPlayer)) {
                if (win == null || win.length < 2) continue;
                int s = Math.clamp(win[0] - 20, lo, hi);
                int e = Math.clamp(win[1] + 20, s, hi);
                if (e - s >= 20) {
                    raw.add(new PersonalClip(s, e, (s + e) / 2, "voice_activity", 38.0));
                }
            }
        }
        for (ReplaySkillEvent skill : replay.skillEvents()) {
            if (skill.tick() < lo || skill.tick() > hi) continue;
            if (!focusPlayer.equals(skill.actorUuid()) && !focusPlayer.equals(skill.targetUuid())) continue;
            int s = Math.clamp(skill.tick() - 40, lo, hi);
            int e = Math.clamp(Math.max(skill.endTick(), skill.tick() + 45), s, hi);
            raw.add(new PersonalClip(s, e, skill.tick(), "skill_" + skill.category(), skill.fallback() ? 34.0 : 40.0));
        }

        if (raw.isEmpty()) {
            // Fallback: opening 4s + closing 4s so the clip compilation is never empty.
            int s1 = lo;
            int e1 = Math.min(hi, lo + 80);
            if (e1 > s1) raw.add(new PersonalClip(s1, e1, s1, "opening", 15.0));
            if (hi - lo > 160) {
                raw.add(new PersonalClip(Math.max(e1, hi - 80), hi, hi, "finale", 15.0));
            }
        }

        raw.sort(Comparator.comparingInt(PersonalClip::startTick).thenComparingInt(PersonalClip::endTick));
        // Merge overlapping or nearly-adjacent clips (gap <= 30 ticks = 1.5s) to prevent choppy jump-cuts.
        List<PersonalClip> merged = new ArrayList<>();
        for (PersonalClip clip : raw) {
            if (merged.isEmpty()) {
                merged.add(clip);
                continue;
            }
            PersonalClip prev = merged.getLast();
            if (clip.startTick() <= prev.endTick() + 30) {
                boolean takeNew = clip.score() > prev.score();
                merged.set(merged.size() - 1, new PersonalClip(
                        prev.startTick(),
                        Math.max(prev.endTick(), clip.endTick()),
                        takeNew ? clip.anchorTick() : prev.anchorTick(),
                        takeNew ? clip.reason() : prev.reason(),
                        Math.max(prev.score(), clip.score())
                ));
            } else {
                merged.add(clip);
            }
        }
        return List.copyOf(merged);
    }

    public static double computeRmsNormalized(@Nullable short[] pcm) {
        if (pcm == null || pcm.length == 0) return 0.0;
        double sumSq = 0.0;
        for (short s : pcm) {
            double v = s / 32768.0;
            sumSq += v * v;
        }
        double rms = Math.sqrt(sumSq / pcm.length);
        // Typical loud speech RMS sits around 0.08..0.28; map smoothly to 0..1.
        return Math.clamp(rms / 0.22, 0.0, 1.0);
    }

    // ------------------------------------------------------------------ internals

    private record Assigned(Candidate candidate, HighlightTier tier) {}

    private static final class RawCluster {
        int anchorTick;
        int minTick;
        int maxTick;
        final List<LastMatchReplay.Node> nodes = new ArrayList<>();
        LastMatchReplay.Node primary;
        double primaryWeight = -1;
        int kills;
        int maxStreak = 1;
        boolean firstBlood;
        boolean finalBlow;

        RawCluster(int tick) {
            this.anchorTick = tick;
            this.minTick = tick;
            this.maxTick = tick;
        }
    }

    private record Candidate(
            RawCluster cluster,
            LastMatchReplay.Node primaryNode,
            int tick,
            int comboStartTick,
            int comboEndTick,
            @Nullable UUID primaryActor,
            @Nullable UUID secondaryActor,
            double totalScore,
            double acousticScore,
            double complexityScore,
            double stakesScore,
            ReplayVideoScript.BeatType beatType
    ) {}

    private static final class MatchContext {
        final LastMatchReplay replay;
        final @Nullable LastMatchReplay.Node firstKill;
        final @Nullable LastMatchReplay.Node lastKill;
        final int totalKills;
        final int totalPlayers;
        final Map<LastMatchReplay.Node, Integer> streaks;
        final Map<UUID, LastMatchReplay.Participant> participantById = new HashMap<>();
        final Map<UUID, ReplayTrackSampler> trackById = new HashMap<>();
        final int[] deathTicks;
        final int[] reviveTicks;

        MatchContext(LastMatchReplay replay, @Nullable UUID focusPlayer) {
            this.replay = replay;
            this.firstKill = replay.firstKill();
            this.lastKill = replay.lastKill();
            this.streaks = ReplayVideoScript.streaks(replay);
            this.totalPlayers = Math.max(1, replay.participants().size());
            for (var p : replay.participants()) {
                participantById.put(p.id(), p);
                trackById.put(p.id(), new ReplayTrackSampler(p.track()));
            }
            List<Integer> deaths = new ArrayList<>();
            List<Integer> revives = new ArrayList<>();
            int kills = 0;
            for (LastMatchReplay.Node n : replay.events()) {
                if (n.kind() == LastMatchReplay.Kind.KILL) {
                    kills++;
                    deaths.add(n.tick());
                } else if (n.kind() == LastMatchReplay.Kind.PLAYER_REVIVAL) {
                    revives.add(n.tick());
                }
            }
            this.totalKills = kills;
            this.deathTicks = deaths.stream().mapToInt(Integer::intValue).sorted().toArray();
            this.reviveTicks = revives.stream().mapToInt(Integer::intValue).sorted().toArray();
        }

        int aliveAt(int tick) {
            int dead = countAtOrBefore(deathTicks, tick);
            int rev = countAtOrBefore(reviveTicks, tick);
            return Math.clamp(totalPlayers - dead + rev, 1, totalPlayers);
        }

        boolean inSpan(LastMatchReplay.SpanKind kind, int tick, @Nullable UUID actor) {
            for (LastMatchReplay.Span span : replay.spans()) {
                if (span.kind() != kind) continue;
                if (tick >= span.startTick() && tick <= span.endTick()) {
                    if (actor == null || span.actor() == null || actor.equals(span.actor())) return true;
                }
            }
            return false;
        }
    }

    private static List<RawCluster> buildClusters(LastMatchReplay replay, MatchContext ctx,
                                                  int startTick, int endTick, @Nullable UUID focusPlayer) {
        List<LastMatchReplay.Node> candidates = replay.events().stream()
                .filter(n -> n.tick() >= startTick && n.tick() <= endTick && isScoreable(n))
                .filter(n -> focusPlayer == null || involvesOrNear(ctx, n, focusPlayer))
                .sorted(Comparator.comparingInt(LastMatchReplay.Node::tick))
                .toList();

        List<RawCluster> clusters = new ArrayList<>();
        RawCluster current = null;
        for (LastMatchReplay.Node node : candidates) {
            if (current == null || node.tick() - current.maxTick > CLUSTER_WINDOW_TICKS) {
                current = new RawCluster(node.tick());
                clusters.add(current);
            }
            current.nodes.add(node);
            current.minTick = Math.min(current.minTick, node.tick());
            current.maxTick = Math.max(current.maxTick, node.tick());

            double weight = baseEventComplexity(node);
            int streak = ctx.streaks.getOrDefault(node, 1);
            boolean fb = node == ctx.firstKill;
            boolean fin = node == ctx.lastKill && ctx.totalKills >= 2;
            if (node.kind() == LastMatchReplay.Kind.KILL) {
                current.kills++;
                current.maxStreak = Math.max(current.maxStreak, streak);
                current.firstBlood |= fb;
                current.finalBlow |= fin;
                weight += 30.0 + (fb ? 15.0 : 0.0) + (fin ? 18.0 : 0.0) + 12.0 * (streak - 1);
            }
            if (focusPlayer != null && (focusPlayer.equals(node.actor()) || focusPlayer.equals(node.target()))) {
                weight += 22.0;
            }
            if (current.primary == null || weight > current.primaryWeight) {
                current.primary = node;
                current.primaryWeight = weight;
                current.anchorTick = node.tick();
            }
        }
        return clusters;
    }

    private static Candidate evaluateCluster(LastMatchReplay replay, MatchContext ctx,
                                             AcousticSignal signal, RawCluster cluster,
                                             @Nullable UUID focusPlayer) {
        LastMatchReplay.Node primary = cluster.primary;
        int tick = primary.tick();
        UUID primaryActor = primary.actor() != null ? primary.actor() : primary.target();
        UUID secondaryActor = primary.target() != null && !primary.target().equals(primaryActor) ? primary.target() : null;

        // ---- 1. Acoustic Tension (0..100, weight 30%)
        long windowStartMs = Math.max(0L, Math.round((tick - 50) * 50.0)); // -2.5s
        long windowEndMs = Math.round((tick + 40) * 50.0);                 // +2.0s
        AcousticMetrics metrics = signal.sample(windowStartMs, windowEndMs, primaryActor);
        double acousticScore = scoreAcoustic(metrics, cluster);

        // ---- 2. Operational Complexity (0..100, weight 35%)
        double complexityScore = scoreComplexity(replay, ctx, cluster, primary, primaryActor);

        // ---- 3. Tactical Stakes (0..100, weight 35%)
        double stakesScore = scoreStakes(ctx, cluster, primary, primaryActor, secondaryActor, focusPlayer);

        double total = WEIGHT_ACOUSTIC * acousticScore
                + WEIGHT_COMPLEXITY * complexityScore
                + WEIGHT_STAKES * stakesScore;

        // If no recorded audio exists at all, softly re-balance toward complexity & stakes so silent archives still reach Tier 4/5.
        if (!metrics.hasRecordedAudio()) {
            double proxyAcoustic = Math.min(100.0, 0.50 * complexityScore + 0.50 * stakesScore);
            total = Math.max(total, WEIGHT_ACOUSTIC * proxyAcoustic + WEIGHT_COMPLEXITY * complexityScore + WEIGHT_STAKES * stakesScore);
        }
        total = Math.clamp(total, 0.0, 100.0);

        int comboStart = Math.max(0, Math.min(cluster.minTick - 30, tick - 40));
        int comboEnd = Math.min(Math.max(0, replay.durationTicks()), Math.max(cluster.maxTick + 25, tick + 30));
        ReplayVideoScript.BeatType beatType = ReplayVideoScript.classify(primary);
        if (beatType == null) beatType = ReplayVideoScript.BeatType.HIT;

        return new Candidate(cluster, primary, tick, comboStart, comboEnd, primaryActor, secondaryActor,
                total, acousticScore, complexityScore, stakesScore, beatType);
    }

    private static double scoreAcoustic(AcousticMetrics m, RawCluster cluster) {
        double speakerPts = Math.min(48.0, m.concurrentSpeakers() * 16.0); // 3+ speakers = 48
        double energyPts = Math.clamp(m.peakVoiceEnergy(), 0.0, 1.0) * 34.0;
        double burstPts = Math.min(28.0, m.soundBurstCount() * 9.0 + cluster.kills * 7.0);
        return Math.clamp(speakerPts + energyPts + burstPts, 0.0, 100.0);
    }

    private static double scoreComplexity(LastMatchReplay replay, MatchContext ctx, RawCluster cluster,
                                          LastMatchReplay.Node primary, @Nullable UUID primaryActor) {
        double score = baseEventComplexity(primary);

        // Multi-action combo density within +-4s by the same actor or inside the cluster.
        int comboActions = 0;
        Set<LastMatchReplay.Kind> distinctKinds = new HashSet<>();
        for (LastMatchReplay.Node n : replay.events()) {
            if (Math.abs(n.tick() - primary.tick()) > COMBO_WINDOW_TICKS) continue;
            if (primaryActor != null && primaryActor.equals(n.actor())) {
                comboActions++;
                distinctKinds.add(n.kind());
            }
        }
        int skillBurst = 0;
        for (ReplaySkillEvent sk : replay.skillEvents()) {
            if (Math.abs(sk.tick() - primary.tick()) <= COMBO_WINDOW_TICKS
                    && (primaryActor == null || primaryActor.equals(sk.actorUuid()))) {
                skillBurst++;
            }
        }
        score += Math.min(24.0, Math.max(0, comboActions - 1) * 5.5 + Math.max(0, distinctKinds.size() - 1) * 4.5 + skillBurst * 4.0);

        // Multi-kill inside a single 2s cluster.
        if (cluster.kills >= 2) {
            score += Math.min(28.0, (cluster.kills - 1) * 16.0);
        }

        // Precision distance: long-range marksmanship or razor-close stealth kill.
        if (primary.from() != null && primary.to() != null) {
            double dx = primary.to().blockX() - primary.from().blockX();
            double dy = primary.to().blockY() - primary.from().blockY();
            double dz = primary.to().blockZ() - primary.from().blockZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist >= 14.0) score += Math.min(16.0, (dist - 12.0) * 1.2);
            else if (dist <= 3.2 && primary.kind() == LastMatchReplay.Kind.KILL) score += 7.0;
        }

        // Kinematic agility: fast movement / jump / weave around the moment.
        if (primaryActor != null) {
            ReplayTrackSampler track = ctx.trackById.get(primaryActor);
            if (track != null) {
                var before = track.sample(Math.max(0, primary.tick() - 20));
                var at = track.sample(primary.tick());
                var after = track.sample(primary.tick() + 20);
                if (before.visible() && at.visible()) {
                    double move = Math.hypot(at.x() - before.x(), at.z() - before.z());
                    if (after.visible()) move += Math.hypot(after.x() - at.x(), after.z() - at.z());
                    if (move > 6.0) score += Math.min(10.0, (move - 5.0) * 1.1);
                }
            }
        }
        return Math.clamp(score, 0.0, 100.0);
    }

    private static double scoreStakes(MatchContext ctx, RawCluster cluster, LastMatchReplay.Node primary,
                                      @Nullable UUID primaryActor, @Nullable UUID secondaryActor,
                                      @Nullable UUID focusPlayer) {
        double score = switch (primary.kind()) {
            case KILL -> 34.0;
            case PLAYER_REVIVAL -> 32.0;
            case BOMB_DEFUSE -> 36.0;
            case BOMB_DETONATE, GRENADE_EXPLOSION -> 28.0;
            case SHIELD_BROKEN -> 24.0;
            case PSYCHO_STATE_CHANGE, BLACKOUT_START -> 26.0;
            case SKILL_RELEASE, CHANGE_ROLE, DISGUISE -> 22.0;
            case TRAP_TRIGGERED, ROPE_PULL, POISON -> 20.0;
            default -> 14.0;
        };

        if (cluster.firstBlood) score += 18.0;
        if (cluster.finalBlow) score += 28.0;
        if (cluster.maxStreak >= 2) {
            score += Math.min(36.0, 14.0 * (cluster.maxStreak - 1));
        }

        // Late-game clutch tension: fewer remaining survivors raises every action's stakes.
        int alive = ctx.aliveAt(primary.tick());
        double survivalRatio = alive / (double) ctx.totalPlayers;
        if (alive <= 4 || survivalRatio <= 0.22) {
            score += 18.0;
        } else if (survivalRatio <= 0.45) {
            score += 9.0;
        }

        // High-value target elimination (killing a killer, MVP, or top killer).
        if (primary.kind() == LastMatchReplay.Kind.KILL && secondaryActor != null) {
            var victim = ctx.participantById.get(secondaryActor);
            if (victim != null) {
                if (victim.faction() == MatchWinFaction.KILLER) score += 14.0;
                if (victim.id().equals(ctx.replay.mvp()) || victim.kills() >= 2) score += 10.0;
            }
        }

        // Blackout / Psycho dramatic state multiplier.
        if (ctx.inSpan(LastMatchReplay.SpanKind.BLACKOUT, primary.tick(), null)) score += 9.0;
        if (primaryActor != null && ctx.inSpan(LastMatchReplay.SpanKind.PSYCHO, primary.tick(), primaryActor)) score += 10.0;

        // MVP / Focus-player emphasis.
        if (focusPlayer != null) {
            if (focusPlayer.equals(primaryActor) || focusPlayer.equals(secondaryActor)) score += 14.0;
            else score -= 18.0;
        } else if (primaryActor != null && primaryActor.equals(ctx.replay.mvp())) {
            score += 6.0;
        }

        return Math.clamp(score, 0.0, 100.0);
    }

    private static @Nullable HighlightTier pickTier(Candidate cand, PyramidQuota quota,
                                                    Map<HighlightTier, Integer> used,
                                                    List<Assigned> kept) {
        // Evaluate from Tier 5 down to Tier 1.
        for (int level = 5; level >= 1; level--) {
            HighlightTier tier = switch (level) {
                case 5 -> HighlightTier.TIER_5_APOTHEOSIS;
                case 4 -> HighlightTier.TIER_4_DUAL_VISION;
                case 3 -> HighlightTier.TIER_3_SHADOW_SLASH;
                case 2 -> HighlightTier.TIER_2_CLOSEUP;
                default -> HighlightTier.TIER_1_GLIMMER;
            };
            if (used.get(tier) >= quota.maxFor(tier)) continue;
            if (!qualifiesForTier(cand, tier)) continue;
            if (collidesNms(cand.tick, tier, kept)) continue;
            return tier;
        }
        return null;
    }

    private static boolean qualifiesForTier(Candidate cand, HighlightTier tier) {
        return switch (tier) {
            case TIER_5_APOTHEOSIS -> {
                // Strictly gated ("宁缺毋滥"): requires an unmistakable legendary play.
                boolean killOrBlast = cand.beatType == ReplayVideoScript.BeatType.KILL
                        || cand.beatType == ReplayVideoScript.BeatType.EXPLOSION
                        || cand.beatType == ReplayVideoScript.BeatType.DEFUSE;
                if (!killOrBlast || cand.primaryActor == null) yield false;
                yield cand.totalScore >= HighlightTier.TIER_5_APOTHEOSIS.minScore
                        || (cand.cluster.maxStreak >= 3 && cand.totalScore >= 76.0)
                        || (cand.cluster.finalBlow && cand.totalScore >= 78.0);
            }
            case TIER_4_DUAL_VISION -> cand.primaryActor != null
                    && (cand.beatType == ReplayVideoScript.BeatType.KILL
                    || cand.beatType == ReplayVideoScript.BeatType.EXPLOSION
                    || cand.beatType == ReplayVideoScript.BeatType.DEFUSE
                    || cand.beatType == ReplayVideoScript.BeatType.REVIVAL)
                    && cand.totalScore >= HighlightTier.TIER_4_DUAL_VISION.minScore;
            case TIER_3_SHADOW_SLASH -> cand.primaryActor != null
                    && cand.totalScore >= HighlightTier.TIER_3_SHADOW_SLASH.minScore;
            case TIER_2_CLOSEUP -> cand.totalScore >= HighlightTier.TIER_2_CLOSEUP.minScore;
            case TIER_1_GLIMMER -> cand.totalScore >= HighlightTier.TIER_1_GLIMMER.minScore;
        };
    }

    private static boolean collidesNms(int tick, HighlightTier tier, List<Assigned> kept) {
        for (Assigned existing : kept) {
            int requiredGap = Math.max(tier.minSeparationTicks, existing.tier.minSeparationTicks);
            if (Math.abs(tick - existing.candidate.tick) < requiredGap) {
                return true;
            }
        }
        return false;
    }

    private static boolean isScoreable(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case DOOR_OPEN, DOOR_CLOSE, BLACKOUT_END -> false;
            case GUNSHOT -> (node.flags() & (LastMatchReplay.HIT | LastMatchReplay.BACKFIRE | LastMatchReplay.GUN_DROP)) != 0;
            default -> true;
        };
    }

    private static boolean isWitnessWorthy(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case KILL, GRENADE_EXPLOSION, BOMB_DETONATE, BOMB_DEFUSE, PLAYER_REVIVAL, PSYCHO_STATE_CHANGE -> true;
            default -> false;
        };
    }

    private static boolean involvesOrNear(MatchContext ctx, LastMatchReplay.Node node, UUID focusPlayer) {
        if (focusPlayer.equals(node.actor()) || focusPlayer.equals(node.target())) return true;
        if (!isWitnessWorthy(node)) return false;
        var track = ctx.trackById.get(focusPlayer);
        LastMatchReplay.Position pos = node.to() != null ? node.to() : node.from();
        if (track == null || pos == null) return false;
        var pose = track.sample(node.tick());
        if (!pose.visible()) return false;
        double dx = pose.x() - pos.blockX();
        double dy = pose.y() - pos.blockY();
        double dz = pose.z() - pos.blockZ();
        return dx * dx + dy * dy + dz * dz <= WITNESS_RADIUS_BLOCKS * WITNESS_RADIUS_BLOCKS;
    }

    private static double baseEventComplexity(LastMatchReplay.Node node) {
        return switch (node.kind()) {
            case KILL -> {
                ReplayDeathIcons.Icon icon = ReplayDeathIcons.classify(node.detail());
                yield switch (icon) {
                    case SNIPER, THROWING_KNIFE, SHURIKEN -> 56.0;
                    case GRENADE, EXPLOSION, POISON -> 50.0;
                    case KNIFE, BAT -> 48.0;
                    case BACKFIRE -> 52.0;
                    default -> 44.0;
                };
            }
            case BOMB_DEFUSE -> 54.0;
            case PLAYER_REVIVAL -> 48.0;
            case BOMB_DETONATE, GRENADE_EXPLOSION -> 46.0;
            case SHIELD_BROKEN -> 40.0;
            case SKILL_RELEASE -> 42.0;
            case TRAP_TRIGGERED, ROPE_PULL -> 38.0;
            case DISGUISE, CHANGE_ROLE, PSYCHO_STATE_CHANGE -> 36.0;
            case GUNSHOT -> (node.flags() & LastMatchReplay.BACKFIRE) != 0 ? 44.0
                    : (node.flags() & LastMatchReplay.HIT) != 0 ? 36.0 : 22.0;
            case BOMB_PLANT, POISON, BLACKOUT_START -> 34.0;
            case DOOR_PRY, LOCKPICK_ATTEMPT, DOOR_SEAL -> 28.0;
            case ITEM_USED -> 26.0;
            default -> 16.0;
        };
    }

    private static int countAtOrBefore(int[] sorted, int tick) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] <= tick) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}
