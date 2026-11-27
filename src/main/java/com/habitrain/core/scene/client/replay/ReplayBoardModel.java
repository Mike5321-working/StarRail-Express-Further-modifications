package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayEntityTrack;
import com.habitrain.core.api.match.replay.ReplayProjection;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplayDeathIcons;
import com.habitrain.core.scene.replay.ReplayFocusPlan;
import com.habitrain.core.scene.replay.ReplayRoofCutaway;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything derived from one {@link LastMatchReplay} that does not change per frame: the diorama
 * frame, per-player samplers, event anchors and text. Rebuilt only
 * when the client receives a new summary object.
 *
 * <p>"Diorama frame" (D): origin at the centre of the tray surface, +X along the map's long axis
 * (the viewer's right), +Y up, scaled to blocks. The source map is rotated a quarter turn when its
 * Z extent is the longer one, so the train always lies across the plaque.</p>
 */
final class ReplayBoardModel {
    final LastMatchReplay replay;
    final ReplayTimeline timeline;
    final ReplayFocusPlan focus;
    final @Nullable ReplayProjection.Layout layout;
    final SceneBounds source;
    final boolean rotated;
    final float scale, mapW, mapD, mapH;
    final int roofCutLayer;
    final Actor[] actors;
    final List<Drone> drones;
    final List<LastMatchReplay.Span> sortedSpans;
    private final List<LastMatchReplay.Span> blackouts = new ArrayList<>();
    private final Map<UUID, List<LastMatchReplay.Span>> psychoSpans = new HashMap<>();
    private final Map<UUID, List<Integer>> revivals = new HashMap<>();
    final Map<UUID, Actor> byId = new HashMap<>();
    final EventMark[] events;
    final boolean eventsSorted;
    final EventMark[] timelineHighlights;
    final List<EventMark> feedEvents;
    final EntityTrackEntry[] entityTracks;
    final int[] entityTrackMaxEndPrefix;
    private ReplaySkillEffectRegistry.SkillIndex dioramaSkillIndex, worldSkillIndex;
    final double rate, comet;
    final Component title, subtitle, modeLabel;
    final int winColor;
    final List<Actor> winners;
    final @Nullable Actor mvp;
    final @Nullable LastMatchReplay.Node firstKill, lastKill;
    final int maxSurvival;

    ReplayBoardModel(LastMatchReplay replay, ReplayTimeline timeline) {
        this.replay = replay;
        this.timeline = timeline;
        this.focus = new ReplayFocusPlan(replay, timeline);
        this.layout = ReplayProjection.layout(replay);
        this.source = layout == null ? SceneBounds.EMPTY : layout.source();
        this.rotated = source.sizeZ() > source.sizeX();
        this.scale = layout == null ? 0.05f : (float) layout.scale();
        this.mapW = (rotated ? source.sizeZ() : source.sizeX()) * scale;
        this.mapD = (rotated ? source.sizeX() : source.sizeZ()) * scale;
        this.mapH = source.sizeY() * scale;
        this.roofCutLayer = ReplayRoofCutaway.cutLayer(ReplayProjection.roofHint(replay), source.sizeY());
        this.rate = ReplayStagePlan.playbackRate(replay.durationTicks(), timeline.cycleTicks());
        this.comet = ReplayStagePlan.cometTicks(rate);

        List<LastMatchReplay.Participant> participants = replay.participants();
        actors = new Actor[participants.size()];
        float[] tmp = new float[3];
        int survival = 1;
        for (int i = 0; i < participants.size(); i++) {
            var p = participants.get(i);
            var sampler = new ReplayTrackSampler(p.track());
            int fallback = switch (p.faction()) {
                case KILLER -> ReplayPalette.KILLER;
                case NEUTRAL -> ReplayPalette.NEUTRAL;
                default -> ReplayPalette.PASSENGER;
            };
            Actor actor = new Actor(i, p, sampler, ReplayPalette.opaque(p.endColor(), fallback),
                    p.id().equals(replay.mvp()), this, tmp);
            actors[i] = actor;
            byId.put(p.id(), actor);
            survival = Math.max(survival, p.survivalSeconds());
        }
        maxSurvival = survival;
        drones = replay.drones().stream().map(d -> new Drone(new ReplayTrackSampler(d.track()), byId.get(d.owner()))).toList();
        sortedSpans = replay.spans().stream().sorted(Comparator.comparingInt(LastMatchReplay.Span::startTick)).toList();
        for (var span : sortedSpans) {
            if (span.kind() == LastMatchReplay.SpanKind.BLACKOUT) blackouts.add(span);
            else if (span.kind() == LastMatchReplay.SpanKind.PSYCHO && span.actor() != null) {
                psychoSpans.computeIfAbsent(span.actor(), ignored -> new ArrayList<>()).add(span);
            }
        }

        List<EventMark> marks = new ArrayList<>();
        boolean sortedEvents = true;
        int prevEventTick = Integer.MIN_VALUE;
        for (LastMatchReplay.Node node : replay.events()) {
            if (node.from() == null && node.to() == null) continue;
            if (node.tick() < prevEventTick) sortedEvents = false;
            prevEventTick = node.tick();
            marks.add(new EventMark(node, this, tmp));
            if (node.kind() == LastMatchReplay.Kind.PLAYER_REVIVAL) {
                if (node.actor() != null) revivals.computeIfAbsent(node.actor(), ignored -> new ArrayList<>()).add(node.tick());
                if (node.target() != null && !node.target().equals(node.actor())) {
                    revivals.computeIfAbsent(node.target(), ignored -> new ArrayList<>()).add(node.tick());
                }
            }
        }
        events = marks.toArray(EventMark[]::new);
        eventsSorted = sortedEvents;
        timelineHighlights = marks.stream()
                .filter(mark -> {
                    var k = mark.node.kind();
                    return k == LastMatchReplay.Kind.KILL || k == LastMatchReplay.Kind.BOMB_DETONATE
                            || k == LastMatchReplay.Kind.GRENADE_EXPLOSION;
                })
                .sorted(Comparator.comparingInt(mark -> mark.node.tick()))
                .toArray(EventMark[]::new);
        feedEvents = marks.stream().filter(mark -> ReplayEventPainter.isFeedEvent(mark.node))
                .sorted(Comparator.comparingInt(mark -> mark.node.tick())).toList();

        List<ReplayEntityTrack> rawTracks = replay.entityTracks();
        List<EntityTrackEntry> indexedTracks = new ArrayList<>(rawTracks.size());
        for (ReplayEntityTrack track : rawTracks) {
            var samples = track.samples();
            if (samples.isEmpty()) continue;
            int first = samples.getFirst().tick();
            var tail = samples.getLast();
            int end = tail.has(ReplayEntityTrack.FLAG_ACTIVE) ? Integer.MAX_VALUE : tail.tick();
            indexedTracks.add(new EntityTrackEntry(track, first, end));
        }
        indexedTracks.sort(Comparator.comparingInt(EntityTrackEntry::firstTick));
        entityTracks = indexedTracks.toArray(EntityTrackEntry[]::new);
        entityTrackMaxEndPrefix = new int[entityTracks.length];
        int runningMaxEnd = Integer.MIN_VALUE;
        for (int i = 0; i < entityTracks.length; i++) {
            if (entityTracks[i].endTick > runningMaxEnd) runningMaxEnd = entityTracks[i].endTick;
            entityTrackMaxEndPrefix[i] = runningMaxEnd;
        }

        title = ReplayBoardTexts.winTitle(replay);
        subtitle = ReplayBoardTexts.winSubtitle(replay);
        modeLabel = ReplayBoardTexts.mode(replay.modeId());
        winColor = ReplayBoardTexts.winColor(replay);
        List<Actor> won = new ArrayList<>();
        for (Actor actor : actors) if (actor.p.winner()) won.add(actor);
        won.sort(Comparator.comparing((Actor a) -> !a.mvp).thenComparing(a -> -a.p.score()).thenComparing(a -> a.p.name()));
        winners = List.copyOf(won);
        Actor chosen = replay.mvp() == null ? null : byId.get(replay.mvp());
        if (chosen == null && !won.isEmpty()) chosen = won.getFirst();
        mvp = chosen;
        firstKill = replay.firstKill();
        lastKill = replay.lastKill();
    }

    @Nullable Actor participant(@Nullable UUID id) {
        return id == null ? null : byId.get(id);
    }

    @Nullable LastMatchReplay.Participant participantData(@Nullable UUID id) {
        Actor actor = participant(id);
        return actor == null ? null : actor.p;
    }

    /** Origin-relative block coordinates -> diorama frame (before the tray height offset). */
    void project(double bx, double by, double bz, float[] out) {
        if (layout == null) { out[0] = out[1] = out[2] = 0; return; }
        SceneBounds o = layout.origin();
        double x = (o.minX() - source.minX() + bx - source.sizeX() * 0.5) * scale;
        double y = (o.minY() - source.minY() + by) * scale;
        double z = (o.minZ() - source.minZ() + bz - source.sizeZ() * 0.5) * scale;
        if (rotated) {
            out[0] = (float) z;
            out[2] = (float) -x;
        } else {
            out[0] = (float) x;
            out[2] = (float) z;
        }
        out[1] = (float) y;
    }

    boolean blackoutAt(double tick) {
        return spanActive(blackouts, tick);
    }

    boolean psychoAt(UUID actor, double tick) {
        return spanActive(psychoSpans.getOrDefault(actor, List.of()), tick);
    }

    private static boolean spanActive(List<LastMatchReplay.Span> spans, double tick) {
        for (LastMatchReplay.Span span : spans) {
            if (span.startTick() > tick) break;
            if (tick < span.endTick()) return true;
        }
        return false;
    }

    /** Returns true when an actual {@code PLAYER_REVIVAL} event was recorded for {@code actorId} near {@code tick}. */
    boolean hasRevivalNear(@Nullable UUID actorId, double tick) {
        for (int revivalTick : revivals.getOrDefault(actorId, List.of())) {
            if (Math.abs(revivalTick - tick) <= 45.0) return true;
        }
        return false;
    }

    List<ReplaySkillEffectRegistry.SkillSignal> skillSignals(boolean diorama) {
        return skillIndex(diorama).signals();
    }

    ReplaySkillEffectRegistry.SkillIndex skillIndex(boolean diorama) {
        if (diorama) {
            if (dioramaSkillIndex == null) {
                dioramaSkillIndex = ReplaySkillEffectRegistry.SkillIndex.of(ReplaySkillEffectRegistry.buildSignals(this, true));
            }
            return dioramaSkillIndex;
        }
        if (worldSkillIndex == null) {
            worldSkillIndex = ReplaySkillEffectRegistry.SkillIndex.of(ReplaySkillEffectRegistry.buildSignals(this, false));
        }
        return worldSkillIndex;
    }

    /** Number of events with {@code tick <= t} when {@link #eventsSorted} is true, computed in O(log N). */
    int eventCountUpTo(double t) {
        if (!eventsSorted) return events.length;
        int lo = 0, hi = events.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (events[mid].node.tick() <= t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Number of entity tracks with {@code firstTick <= t}, computed in O(log N). */
    int entityTrackCountUpTo(double t) {
        int lo = 0, hi = entityTracks.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (entityTracks[mid].firstTick <= t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    record EntityTrackEntry(ReplayEntityTrack track, int firstTick, int endTick) {}

    record Drone(ReplayTrackSampler track, @Nullable Actor owner) {}

    static final class Actor {
        /** Minimum diorama-unit displacement before a trail vertex is emitted in real-time board view. */
        private static final float TRAIL_MIN_STEP_SQ = 0.014f * 0.014f;
        /** Maximum collinear deviation (diorama units) allowed when merging consecutive straight points. */
        private static final float TRAIL_COLLINEAR_TOL_SQ = 0.0045f * 0.0045f;
        /** Cap single merged span length so comet gradient alpha/color remains smooth. */
        private static final float TRAIL_MAX_SPAN_SQ = 0.28f * 0.28f;

        final int index;
        final LastMatchReplay.Participant p;
        final ReplayTrackSampler track;
        final int[] trailEnds;
        final float[] projX, projY, projZ;
        final int[] visualNext;
        final int color;
        final boolean mvp;
        final Component name, role;

        Actor(int index, LastMatchReplay.Participant p, ReplayTrackSampler track, int color, boolean mvp) {
            this(index, p, track, color, mvp, null, null);
        }

        Actor(int index, LastMatchReplay.Participant p, ReplayTrackSampler track, int color, boolean mvp,
              @Nullable ReplayBoardModel model, float @Nullable [] scratch) {
            this.index = index;
            this.p = p;
            this.track = track;
            int n = track.size();
            trailEnds = new int[n];
            projX = new float[n];
            projY = new float[n];
            projZ = new float[n];
            for (int i = n - 1; i >= 0; i--) {
                trailEnds[i] = track.connects(i + 1) ? trailEnds[i + 1] : i;
            }
            if (model != null && scratch != null) {
                for (int i = 0; i < n; i++) {
                    model.project(track.x(i), track.y(i), track.z(i), scratch);
                    projX[i] = scratch[0];
                    projY[i] = scratch[1];
                    projZ[i] = scratch[2];
                }
            } else {
                for (int i = 0; i < n; i++) {
                    projX[i] = (float) track.x(i);
                    projY[i] = (float) track.y(i);
                    projZ[i] = (float) track.z(i);
                }
            }
            visualNext = buildVisualTrailChain(n, trailEnds, projX, projY, projZ);
            this.color = color;
            this.mvp = mvp;
            this.name = ReplayBoardTexts.name(p);
            this.role = ReplayBoardTexts.role(p.endRole());
        }

        private static int[] buildVisualTrailChain(int n, int[] trailEnds, float[] px, float[] py, float[] pz) {
            int[] next = new int[n];
            int segStart = 0;
            while (segStart < n) {
                int segEnd = trailEnds[segStart];
                int anchor = segStart;
                int prevKept = segStart;
                for (int i = segStart + 1; i <= segEnd; i++) {
                    boolean keep = (i == segEnd);
                    if (!keep) {
                        float dx = px[i] - px[anchor], dy = py[i] - py[anchor], dz = pz[i] - pz[anchor];
                        float distSq = dx * dx + dy * dy + dz * dz;
                        if (distSq >= TRAIL_MIN_STEP_SQ) {
                            if (distSq >= TRAIL_MAX_SPAN_SQ) {
                                keep = true;
                            } else {
                                // Check whether point i bends away from chord anchor -> i+1
                                float cx = px[i + 1] - px[anchor], cy = py[i + 1] - py[anchor], cz = pz[i + 1] - pz[anchor];
                                float chordSq = cx * cx + cy * cy + cz * cz;
                                if (chordSq < 1.0e-8f) {
                                    keep = true;
                                } else {
                                    float crx = dy * cz - dz * cy;
                                    float cry = dz * cx - dx * cz;
                                    float crz = dx * cy - dy * cx;
                                    float crossSq = crx * crx + cry * cry + crz * crz;
                                    if (crossSq > TRAIL_COLLINEAR_TOL_SQ * chordSq) keep = true;
                                }
                            }
                        }
                    }
                    if (keep) {
                        for (int fill = prevKept; fill < i; fill++) next[fill] = i;
                        anchor = i;
                        prevKept = i;
                    }
                }
                next[segEnd] = segEnd + 1;
                segStart = segEnd + 1;
            }
            return next;
        }

        int nextTrailPoint(int current, int recent, int stride) {
            int next = current + 1;
            if (stride <= 1 || next >= recent || next > trailEnds[current]) return next;
            int nextOnStride = current + stride - current % stride;
            return Math.min(recent, Math.min(nextOnStride, trailEnds[current]));
        }

        /**
         * Advances along the pre-simplified trail chain for real-time board rendering, skipping
         * stationary jitter and collinear interior points while preserving segment breaks,
         * corners, the comet boundary and the current tail endpoint.
         */
        int nextVisualTrailPoint(int current, int recent, int last, int stride) {
            int end = Math.min(last, trailEnds[current]);
            if (current >= end) return current + 1;
            int vNext = visualNext[current];
            if (vNext > end) vNext = end;
            if (current < recent) {
                int nextOnStride = current + stride - current % stride;
                int target = Math.max(vNext, nextOnStride);
                return Math.min(recent, Math.min(target, end));
            }
            return vNext;
        }

        int factionColor() {
            return p.faction() == MatchWinFaction.KILLER ? ReplayPalette.KILLER
                    : p.faction() == MatchWinFaction.NEUTRAL ? ReplayPalette.NEUTRAL : ReplayPalette.PASSENGER;
        }
    }

    static final class EventMark {
        final LastMatchReplay.Node node;
        final boolean hasFrom, hasTo;
        final float fx, fy, fz, tx, ty, tz;
        final ReplayDeathIcons.Icon icon;
        private ItemStack item;

        EventMark(LastMatchReplay.Node node, ReplayBoardModel model, float[] tmp) {
            this.node = node;
            hasFrom = node.from() != null;
            hasTo = node.to() != null;
            LastMatchReplay.Position a = hasFrom ? node.from() : node.to();
            LastMatchReplay.Position b = hasTo ? node.to() : node.from();
            model.project(a.blockX(), a.blockY(), a.blockZ(), tmp);
            fx = tmp[0]; fy = tmp[1]; fz = tmp[2];
            model.project(b.blockX(), b.blockY(), b.blockZ(), tmp);
            tx = tmp[0]; ty = tmp[1]; tz = tmp[2];
            icon = node.kind() == LastMatchReplay.Kind.KILL ? ReplayDeathIcons.classify(node.detail()) : ReplayDeathIcons.Icon.SKULL;
        }

        boolean has(int flag) { return (node.flags() & flag) != 0; }

        /** Weapon model for kill / gunshot nodes, resolved lazily against the live item registry. */
        ItemStack item() {
            if (item != null) return item;
            Item resolved = Items.AIR;
            if (node.kind() == LastMatchReplay.Kind.GUNSHOT) resolved = lookup(node.detail());
            if (resolved == Items.AIR) {
                for (String candidate : ReplayDeathIcons.itemCandidates(icon)) {
                    resolved = lookup(candidate);
                    if (resolved != Items.AIR) break;
                }
            }
            item = resolved == Items.AIR ? new ItemStack(Items.SKELETON_SKULL) : new ItemStack(resolved);
            return item;
        }

        private static Item lookup(String id) {
            ResourceLocation key = ResourceLocation.tryParse(id == null ? "" : id);
            if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return Items.AIR;
            return BuiltInRegistries.ITEM.get(key);
        }
    }
}
