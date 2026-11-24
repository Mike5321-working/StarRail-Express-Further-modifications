package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplaySkillEvent;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static com.habitrain.core.scene.client.replay.ReplayPalette.*;

/**
 * Multi-mod skill, weapon, gadget &amp; item visual effect adapter registry with guaranteed 3-layer
 * fallback for unknown/third-party roles and items.
 *
 * <p>Covers SRE core, habitrain_core (Seven Sins, Recon Drone, Idiot, Doll Tailor, Mime Killer,
 * Flower Girl, Swift Wind, Role API v2), {@code noellesroles}, {@code stupid_express},
 * {@code harpymodloader}, and {@code tacz}. Any unmatched skill/item automatically triggers the
 * 3-layer Star-Night Brass astrolabe fallback + {@link ReplaySkillFallbackHud} tactical toast.</p>
 */
public final class ReplaySkillEffectRegistry {
    private static final int DRONE_CYAN = 0xFF38BDF8;
    private static final int ROSE_GOLD = 0xFFFF8DB8;
    private static final int EMERALD = 0xFF4ADE80;
    private static final int MIME_VIOLET = 0xFF8B5CF6;
    private static final int SLOTH_BLUE = 0xFF93C5FD;
    private static final int FLASH_WHITE = 0xFFFFFFFF;
    private static final int SMOKE_GRAY = 0xFF94A3B8;

    private ReplaySkillEffectRegistry() {}

    /** Normalized skill/event payload consumed by both diorama and 1:1 POV renderers. */
    public record SkillSignal(
            int eventId,
            int tick,
            String id,
            String displayName,
            @Nullable LastMatchReplay.Kind nodeKind,
            @Nullable UUID actorUuid,
            @Nullable UUID targetUuid,
            String actorRoleId,
            int accentColor,
            boolean hasFrom,
            float fromX, float fromY, float fromZ,
            boolean hasTo,
            float toX, float toY, float toZ,
            int durationTicks,
            int flags,
            boolean forceFallback
    ) {}

    /** Render context abstracting diorama scale vs 1:1 POV world scale. */
    public record RenderContext(
            Matrix4f frame,
            VertexConsumer glow,
            VertexConsumer fill,
            Vector3f right,
            Vector3f up,
            double matchTick,
            double animSec,
            float unitScale,
            float lineScale,
            float alpha,
            boolean godView,
            @Nullable ReplayFrame replayFrame,
            @Nullable PoseStack worldStack,
            @Nullable MultiBufferSource worldBuffers,
            @Nullable UUID povSubjectUuid,
            boolean firstPersonPov
    ) {
        public RenderContext(Matrix4f frame, VertexConsumer glow, VertexConsumer fill,
                             Vector3f right, Vector3f up, double matchTick, double animSec,
                             float unitScale, float lineScale, float alpha, boolean godView,
                             @Nullable ReplayFrame replayFrame, @Nullable PoseStack worldStack,
                             @Nullable MultiBufferSource worldBuffers) {
            this(frame, glow, fill, right, up, matchTick, animSec, unitScale, lineScale, alpha, godView,
                    replayFrame, worldStack, worldBuffers, null, false);
        }

        boolean isWorldPov() {
            return unitScale >= 0.5f && !godView;
        }
    }

    @FunctionalInterface
    public interface EffectAdapter {
        boolean render(RenderContext ctx, SkillSignal signal, double ageTicks, double durationTicks);
    }

    private record NamedAdapter(String key, Predicate<SkillSignal> matcher, EffectAdapter adapter) {}

    private static final List<NamedAdapter> ADAPTERS = new ArrayList<>();
    private static final Map<String, EffectAdapter> EXACT_ADAPTERS = new LinkedHashMap<>();

    static {
        registerDefaults();
    }

    /** Allows external/future modules to register dedicated skill visualizers by exact skill/item id. */
    public static void registerExact(String skillOrItemId, EffectAdapter adapter) {
        if (skillOrItemId == null || skillOrItemId.isBlank() || adapter == null) return;
        EXACT_ADAPTERS.put(skillOrItemId.toLowerCase(Locale.ROOT), adapter);
    }

    /** Registers a pattern-matched skill visual adapter. */
    public static void registerMatcher(String key, Predicate<SkillSignal> matcher, EffectAdapter adapter) {
        ADAPTERS.add(new NamedAdapter(key, matcher, adapter));
    }

    /** Returns true if a dedicated native/wildcard visual adapter exists for this signal. */
    public static boolean hasNativeAdapter(SkillSignal signal) {
        if (signal.forceFallback()) return false;
        String lower = signal.id() == null ? "" : signal.id().toLowerCase(Locale.ROOT);
        if (!lower.isEmpty() && EXACT_ADAPTERS.containsKey(lower)) return true;
        for (NamedAdapter entry : ADAPTERS) {
            if (entry.matcher().test(signal)) return true;
        }
        return false;
    }

    /**
     * Paints all active skill/item effects in diorama space (used by replay table, history preview,
     * and video export). When {@code f.video == true}, standard combat nodes already handled by
     * {@code ReplayVideoFx} are skipped while all v2 {@code ReplaySkillEvent}s and 3-layer
     * unknown-mod fallback effects still render cleanly.
     */
    public static void paintDiorama(ReplayFrame f, ReplayBoardSite site, Matrix4f diorama, boolean godView) {
        if (f.model == null || f.stage == null || f.stage.actors() <= 0.01) return;
        f.skillsPainted = true;
        float alpha = (float) (site.fade() * f.stage.actors());
        float elemScale = site.scale() * (float) f.focus.zoom();
        RenderContext ctx = new RenderContext(
                diorama, f.glow(), f.fill(), f.right, f.up,
                f.replayTick, f.anim,
                Math.max(0.05f, f.model.scale),
                Math.max(0.01f, elemScale),
                alpha, godView, f, null, null
        );
        paintSignals(ctx, f.model.skillSignals(true), f.video);
    }

    /**
     * Paints all active skill/item effects in 1:1 POV world space (source-origin block coordinates).
     */
    public static void paintWorld(Matrix4f worldToCam, VertexConsumer glow, VertexConsumer fill,
                                  Vector3f right, Vector3f up, ReplayBoardModel model,
                                  double matchTick, double animSec, float alpha, boolean godView) {
        paintWorld(null, null, worldToCam, glow, fill, right, up, model, matchTick, animSec, alpha, godView);
    }

    public static void paintWorld(@Nullable PoseStack worldStack, @Nullable MultiBufferSource worldBuffers,
                                  Matrix4f worldToCam, VertexConsumer glow, VertexConsumer fill,
                                  Vector3f right, Vector3f up, ReplayBoardModel model,
                                  double matchTick, double animSec, float alpha, boolean godView) {
        paintWorld(worldStack, worldBuffers, worldToCam, glow, fill, right, up, model,
                matchTick, animSec, alpha, godView, null, false);
    }

    public static void paintWorld(@Nullable PoseStack worldStack, @Nullable MultiBufferSource worldBuffers,
                                  Matrix4f worldToCam, VertexConsumer glow, VertexConsumer fill,
                                  Vector3f right, Vector3f up, ReplayBoardModel model,
                                  double matchTick, double animSec, float alpha, boolean godView,
                                  @Nullable UUID povSubjectUuid, boolean firstPersonPov) {
        if (model == null || alpha <= 0.01f) return;
        RenderContext ctx = new RenderContext(
                worldToCam, glow, fill, right, up,
                matchTick, animSec,
                1.0f, 1.0f, alpha, godView, null, worldStack, worldBuffers,
                povSubjectUuid, firstPersonPov
        );
        paintSignals(ctx, model.skillSignals(false), false);
    }

    /**
     * Computes flashbang whiteout screen intensity {@code [0, 1]} for a POV viewer at {@code matchTick}.
     * Combines direct {@code STATUS_FLASHED} on the viewer's v2 track with nearby flashbang explosions.
     */
    public static float flashIntensityFor(@Nullable UUID viewerUuid, ReplayBoardModel model, double matchTick) {
        if (model == null) return 0f;
        float peak = 0f;
        ReplayBoardModel.Actor viewer = viewerUuid == null ? null : model.participant(viewerUuid);
        var viewerPose = viewer == null ? null : viewer.track.sample(matchTick);
        if (viewerPose != null && viewerPose.visible() && viewerPose.hasStatus(LastMatchReplay.STATUS_FLASHED)) {
            peak = Math.max(peak, 0.86f);
        }
        for (SkillSignal s : model.skillSignals(false)) {
            double age = matchTick - s.tick();
            if (age < 0 || age > 55.0 || !isFlashbang(s)) continue;
            float distFactor = 1.0f;
            if (viewerPose != null && viewerPose.visible() && (s.hasTo() || s.hasFrom())) {
                float fx = s.hasTo() ? s.toX() : s.fromX();
                float fy = s.hasTo() ? s.toY() : s.fromY();
                float fz = s.hasTo() ? s.toZ() : s.fromZ();
                double dist = Math.hypot(Math.hypot(viewerPose.x() - fx, viewerPose.y() - fy), viewerPose.z() - fz);
                if (dist > 14.0) continue;
                distFactor = (float) Mth.clamp(1.0 - dist / 14.0, 0.0, 1.0);
            }
            float env = age < 3.0 ? (float) (age / 3.0) : (float) Math.max(0.0, 1.0 - (age - 3.0) / 52.0);
            peak = Math.max(peak, env * env * distFactor);
        }
        return Mth.clamp(peak, 0f, 1f);
    }

    /**
     * Collects active fallback / tactical toast entries at {@code matchTick} for {@link ReplaySkillFallbackHud}.
     */
    public static List<ReplaySkillFallbackHud.ToastEntry> collectFallbackToasts(
            ReplayBoardModel model, double matchTick, @Nullable Predicate<ReplaySkillFallbackHud.ToastEntry> filter) {
        if (model == null) return List.of();
        List<ReplaySkillFallbackHud.ToastEntry> out = new ArrayList<>();
        for (SkillSignal s : model.skillSignals(false)) {
            double age = matchTick - s.tick();
            if (age < 0 || age > ReplaySkillFallbackHud.TOAST_DURATION_TICKS) continue;
            boolean needsToast = s.forceFallback()
                    || ((s.nodeKind() == null
                    || s.nodeKind() == LastMatchReplay.Kind.SKILL_RELEASE
                    || s.nodeKind() == LastMatchReplay.Kind.ITEM_USED) && !hasNativeAdapter(s));
            if (!needsToast) continue;
            ReplayBoardModel.Actor actor = model.participant(s.actorUuid());
            String actorName = actor != null ? actor.p.name() : "Unknown";
            String roleName = s.actorRoleId() != null && !s.actorRoleId().isBlank()
                    ? ReplayBoardTexts.role(s.actorRoleId()).getString()
                    : (actor != null ? actor.role.getString() : "");
            String skillName = (s.displayName() != null && !s.displayName().isBlank())
                    ? s.displayName() : prettySkillName(s.id());
            var entry = new ReplaySkillFallbackHud.ToastEntry(
                    s.eventId(), s.tick(), actorName, roleName, skillName,
                    s.accentColor(), s.id(), age / ReplaySkillFallbackHud.TOAST_DURATION_TICKS);
            if (filter == null || filter.test(entry)) out.add(entry);
        }
        return out;
    }

    private static void paintSignals(RenderContext ctx, List<SkillSignal> signals, boolean video) {
        for (SkillSignal s : signals) {
            double age = ctx.matchTick() - s.tick();
            double dur = Math.max(12.0, s.durationTicks() > 0 ? s.durationTicks() : defaultDurationTicks(s));
            if (age < 0 || age > dur) continue;

            // ReplayVideoFx already paints standard node effects during export.
            if (video && s.nodeKind() != null && !s.forceFallback()
                    && ((s.nodeKind() != LastMatchReplay.Kind.SKILL_RELEASE
                    && s.nodeKind() != LastMatchReplay.Kind.ITEM_USED) || hasNativeAdapter(s))) continue;

            boolean handled = false;
            if (!s.forceFallback()) {
                String lower = s.id() == null ? "" : s.id().toLowerCase(Locale.ROOT);
                EffectAdapter exact = lower.isEmpty() ? null : EXACT_ADAPTERS.get(lower);
                if (exact != null) {
                    handled = exact.render(ctx, s, age, dur);
                }
                if (!handled) {
                    for (NamedAdapter adapter : ADAPTERS) {
                        if (adapter.matcher().test(s) && adapter.adapter().render(ctx, s, age, dur)) {
                            handled = true;
                            break;
                        }
                    }
                }
            }
            if (!handled) {
                renderThreeLayerFallback(ctx, s, age, dur);
            }
        }
    }

    /**
     * Collects skill/item signals from both v2 {@link ReplaySkillEvent}s and {@link LastMatchReplay.Node}
     * timeline events, deduplicating shared positive {@code eventId}s.
     */
    static List<SkillSignal> buildSignals(ReplayBoardModel model, boolean projectToDiorama) {
        List<SkillSignal> list = new ArrayList<>();
        java.util.HashSet<Integer> seenIds = new java.util.HashSet<>();
        float[] tmp = new float[3];

        // 1. Primary v2 structured ReplaySkillEvent list (includes unknown third-party mod fallback events)
        if (model.replay != null) {
            for (ReplaySkillEvent se : model.replay.skillEvents()) {
                // Ignore opening passive MobEffect fallbacks captured in older replays at match start (tick <= 10).
                if (ReplaySkillEvent.CAT_EFFECT.equals(se.category()) && se.tick() <= 10) continue;
                if (se.eventId() > 0) seenIds.add(se.eventId());
                boolean hasFrom = se.from() != null;
                boolean hasTo = se.to() != null;
                float fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
                if (hasFrom) {
                    if (projectToDiorama) {
                        model.project(se.from().blockX(), se.from().blockY(), se.from().blockZ(), tmp);
                        fx = tmp[0]; fy = tmp[1]; fz = tmp[2];
                    } else {
                        fx = (float) se.from().blockX(); fy = (float) se.from().blockY(); fz = (float) se.from().blockZ();
                    }
                }
                if (hasTo) {
                    if (projectToDiorama) {
                        model.project(se.to().blockX(), se.to().blockY(), se.to().blockZ(), tmp);
                        tx = tmp[0]; ty = tmp[1]; tz = tmp[2];
                    } else {
                        tx = (float) se.to().blockX(); ty = (float) se.to().blockY(); tz = (float) se.to().blockZ();
                    }
                }
                ReplayBoardModel.Actor actor = model.participant(se.actorUuid());
                int color = opaque(se.accentColor(), actor != null ? actor.color : BRASS_LIGHT);
                String roleId = !se.actorRoleId().isBlank() ? se.actorRoleId()
                        : (actor != null ? actor.p.endRole() : "");
                list.add(new SkillSignal(
                        se.eventId(), se.tick(), se.skillOrItemId(), se.displayName(),
                        null, se.actorUuid(), se.targetUuid(), roleId, color,
                        hasFrom, fx, fy, fz, hasTo, tx, ty, tz,
                        se.durationTicks(), 0, se.fallback()
                ));
            }
        }

        // 2. Classic LastMatchReplay.Node events (both v1 archives and discrete combat/door events)
        int syntheticId = 1_000_000;
        for (var mark : model.events) {
            var n = mark.node;
            if (n.eventId() > 0 && seenIds.contains(n.eventId())) continue;
            boolean hasFrom = n.from() != null;
            boolean hasTo = n.to() != null;
            float fx = 0, fy = 0, fz = 0, tx = 0, ty = 0, tz = 0;
            if (hasFrom) {
                if (projectToDiorama) {
                    fx = mark.fx; fy = mark.fy; fz = mark.fz;
                } else {
                    fx = (float) n.from().blockX(); fy = (float) n.from().blockY(); fz = (float) n.from().blockZ();
                }
            }
            if (hasTo) {
                if (projectToDiorama) {
                    tx = mark.tx; ty = mark.ty; tz = mark.tz;
                } else {
                    tx = (float) n.to().blockX(); ty = (float) n.to().blockY(); tz = (float) n.to().blockZ();
                }
            }
            ReplayBoardModel.Actor actor = model.participant(n.actor());
            int color = actor != null ? actor.color : BRASS_LIGHT;
            String roleId = actor != null ? actor.p.endRole() : "";
            int eid = n.eventId() > 0 ? n.eventId() : syntheticId++;
            list.add(new SkillSignal(
                    eid, n.tick(), n.detail() == null ? "" : n.detail(), "",
                    n.kind(), n.actor(), n.target(), roleId, color,
                    hasFrom, fx, fy, fz, hasTo, tx, ty, tz,
                    0, n.flags(), false
            ));
        }
        return List.copyOf(list);
    }

    // ------------------------------------------------------------------ 3-layer unknown skill fallback

    /**
     * 3-Layer Star-Night Brass Unknown-Mod Fallback:
     * <ol>
     *   <li>Dual rotating brass astrolabe rune rings in the caster's role primary color;</li>
     *   <li>Parabolic energy arc toward target/destination + expanding hexagonal impact ripple;</li>
     *   <li>Overhead 3D item icon or 8-point brass star emblem above the caster (+ HUD toast).</li>
     * </ol>
     */
    private static double nearCameraFade(RenderContext ctx, float x, float y, float z, float cullDist, float fullDist) {
        if (!ctx.isWorldPov()) return 1.0;
        Vector3f cam = ctx.frame().transformPosition(x, y, z, new Vector3f());
        float dist = cam.length();
        if (dist <= cullDist) return 0.0;
        if (dist >= fullDist) return 1.0;
        return ReplayStagePlan.smoothstep(cullDist, fullDist, dist);
    }

    private static void renderThreeLayerFallback(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.smoothstep(0.62, 1.0, k)) * ctx.alpha();
        if (fade <= 0.02) return;

        float u = ctx.unitScale();
        float lw = Math.max(0.012f, 0.032f * u) * ctx.lineScale();
        float cx = s.hasFrom() ? s.fromX() : s.toX();
        float cy = (s.hasFrom() ? s.fromY() : s.toY()) + 0.03f * u;
        float cz = s.hasFrom() ? s.fromZ() : s.toZ();
        boolean selfFirstPerson = ctx.firstPersonPov() && ctx.povSubjectUuid() != null
                && ctx.povSubjectUuid().equals(s.actorUuid());
        double casterFade = fade * (selfFirstPerson ? 0.0 : nearCameraFade(ctx, cx, cy + 0.5f * u, cz, 0.9f * u, 2.2f * u));
        int roleCol = alpha(opaque(s.accentColor(), BRASS_LIGHT), 0.92 * casterFade);
        int brassCol = alpha(BRASS_LIGHT, 0.90 * casterFade);

        // Layer 1: Dual counter-rotating Star-Night Brass astrolabe runes around caster
        if (casterFade > 0.02) {
            float grow = (float) ReplayStagePlan.easeOutCubic(Math.min(1.0, k / 0.25));
            float r1 = (0.75f + 0.65f * grow) * u;
            float spin = (float) (ctx.animSec() * 2.2 + s.eventId() * 0.7);
            ReplayDraw.astrolabeRune(ctx.glow(), ctx.frame(), cx, cy, cz, r1, spin, lw, roleCol, brassCol);
            ReplayDraw.astrolabeRune(ctx.glow(), ctx.frame(), cx, cy + 0.85f * u, cz, r1 * 0.68f, -spin * 1.4f,
                    lw * 0.75f, brassCol, roleCol);
            ReplayDraw.column(ctx.glow(), ctx.frame(), cx, cy, cz, 2.2f * u * grow, 0.22f * u,
                    alpha(opaque(s.accentColor(), BRASS), 0.35 * casterFade), alpha(BRASS_LIGHT, 0));
        }

        // Layer 2: Directional energy arc + hexagonal ripple at target/destination
        if (s.hasFrom() && s.hasTo()) {
            float dx = s.toX() - s.fromX(), dy = s.toY() - s.fromY(), dz = s.toZ() - s.fromZ();
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            double targetFade = fade * nearCameraFade(ctx, s.toX(), s.toY() + 0.4f * u, s.toZ(), 0.85f * u, 2.0f * u);
            if (dist > 0.15f * u && targetFade > 0.02) {
                float travel = (float) ReplayStagePlan.easeOutCubic(Math.min(1.0, k / 0.45));
                if (!selfFirstPerson) {
                    ReplayDraw.parabolicArc(ctx.glow(), ctx.frame(),
                            cx, cy + 0.95f * u, cz, s.toX(), s.toY() + 0.55f * u, s.toZ(),
                            Math.max(0.5f * u, dist * 0.32f), 14, lw * 1.35f,
                            alpha(BRASS_LIGHT, 0.90 * targetFade), alpha(opaque(s.accentColor(), BRASS_LIGHT), 0.92 * targetFade), travel);
                }
                if (k > 0.22) {
                    double rk = Mth.clamp((k - 0.22) / 0.78, 0.0, 1.0);
                    float hexR = (float) (0.35 + 1.15 * ReplayStagePlan.easeOutCubic(rk)) * u;
                    ReplayDraw.polygonXZ(ctx.glow(), ctx.frame(), s.toX(), s.toY() + 0.04f * u, s.toZ(),
                            hexR, 6, (float) (rk * 1.6), lw, alpha(opaque(s.accentColor(), BRASS_LIGHT), (1.0 - rk) * targetFade));
                    ReplayDraw.polygonXZ(ctx.glow(), ctx.frame(), s.toX(), s.toY() + 0.06f * u, s.toZ(),
                            hexR * 0.65f, 6, (float) (-rk * 2.1), lw * 0.8f, alpha(BRASS_LIGHT, (1.0 - rk) * 0.85 * targetFade));
                }
            }
        }

        // Layer 3: Overhead 3D item icon or 8-point brass star emblem above caster
        if (casterFade <= 0.02) return;
        float emblemY = cy + 2.35f * u + (float) Math.sin(ctx.animSec() * 4.0) * 0.08f * u;
        Vector3f top = ctx.frame().transformPosition(cx, emblemY, cz, new Vector3f());
        if (!ctx.isWorldPov() || top.length() > 0.85f * u) {
            ReplayDraw.star(ctx.glow(), top, ctx.right(), ctx.up(), 0.38f * u * ctx.lineScale(), 8,
                    (float) (ctx.animSec() * 1.8), alpha(CHAMPAGNE, casterFade), alpha(opaque(s.accentColor(), BRASS), 0));
        }
        ItemStack item = ReplayFullPlayerRenderer.resolveItem(s.id());
        if (!item.isEmpty()) {
            if (ctx.replayFrame() != null) {
                Matrix4f im = new Matrix4f(ctx.frame()).translate(cx, emblemY, cz)
                        .rotateY((float) (ctx.animSec() * 2.4)).scale(0.55f * u);
                ctx.replayFrame().model(im, pose -> Minecraft.getInstance().getItemRenderer().renderStatic(
                        item, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        pose, ctx.replayFrame().modelBuffers(), ctx.replayFrame().level, 0));
            } else if (ctx.worldStack() != null && ctx.worldBuffers() != null) {
                PoseStack ws = ctx.worldStack();
                ws.pushPose();
                ws.mulPose(ctx.frame());
                ws.translate(cx, emblemY, cz);
                ws.mulPose(Axis.YP.rotation((float) (ctx.animSec() * 2.4)));
                ws.scale(0.55f * u, 0.55f * u, 0.55f * u);
                Minecraft.getInstance().getItemRenderer().renderStatic(
                        item, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        ws, ctx.worldBuffers(), Minecraft.getInstance().level, 0);
                ws.popPose();
            }
        }
    }

    // ------------------------------------------------------------------ native & wildcard adapters

    private static void registerDefaults() {
        // 1. Firearms / TACZ / Revolver / Sniper / Shotgun
        registerMatcher("firearm",
                s -> s.nodeKind() == LastMatchReplay.Kind.GUNSHOT
                        || containsAny(s.id(), "tacz:", "revolver", "derringer", "sniper", "shotgun", "gun_shot"),
                ReplaySkillEffectRegistry::renderFirearm);

        // 2. Knife / Melee backstab slash
        registerMatcher("melee_slash",
                s -> (s.nodeKind() == LastMatchReplay.Kind.KILL && containsAny(s.id(), "knife", "stab", "axe", "bat", "blade", "sword"))
                        || containsAny(s.id(), "fire_axe", "knife_slash", "backstab"),
                ReplaySkillEffectRegistry::renderMeleeSlash);

        // 3. Poison vial / toxic gas / chlorine
        registerMatcher("poison",
                s -> s.nodeKind() == LastMatchReplay.Kind.POISON || containsAny(s.id(), "poison", "chlorine", "toxic"),
                ReplaySkillEffectRegistry::renderPoison);

        // 4. Shield / Armor shatter
        registerMatcher("shield",
                s -> s.nodeKind() == LastMatchReplay.Kind.SHIELD_BROKEN || containsAny(s.id(), "shield", "armor_break"),
                ReplaySkillEffectRegistry::renderShieldBreak);

        // 5. Lockpick & Crowbar Pry sparks
        registerMatcher("lockpick_pry",
                s -> s.nodeKind() == LastMatchReplay.Kind.LOCKPICK_ATTEMPT
                        || s.nodeKind() == LastMatchReplay.Kind.DOOR_PRY
                        || containsAny(s.id(), "lockpick", "crowbar"),
                ReplaySkillEffectRegistry::renderLockpickSparks);

        // 6. Door lock / seal / open / close
        registerMatcher("door_action",
                s -> s.nodeKind() == LastMatchReplay.Kind.DOOR_SEAL
                        || s.nodeKind() == LastMatchReplay.Kind.DOOR_LOCK
                        || s.nodeKind() == LastMatchReplay.Kind.DOOR_UNLOCK
                        || s.nodeKind() == LastMatchReplay.Kind.DOOR_OPEN
                        || s.nodeKind() == LastMatchReplay.Kind.DOOR_CLOSE,
                ReplaySkillEffectRegistry::renderDoorAction);

        // 7. C4 plant / defuse / detonation
        registerMatcher("c4_bomb",
                s -> s.nodeKind() == LastMatchReplay.Kind.BOMB_PLANT
                        || s.nodeKind() == LastMatchReplay.Kind.BOMB_DEFUSE
                        || s.nodeKind() == LastMatchReplay.Kind.BOMB_DETONATE
                        || containsAny(s.id(), "c4", "detonat"),
                ReplaySkillEffectRegistry::renderBombC4);

        // 8. Grenades (Flash, Smoke, Incendiary, Slime, Decoy, WallBreak, Frag)
        registerMatcher("grenade",
                s -> s.nodeKind() == LastMatchReplay.Kind.GRENADE_THROWN
                        || s.nodeKind() == LastMatchReplay.Kind.GRENADE_EXPLOSION
                        || containsAny(s.id(), "grenade", "smoke_pellet", "youlu_smoke", "firecracker", "molotov", "purify_bomb"),
                ReplaySkillEffectRegistry::renderGrenade);

        // 9. Blackout EMP wave
        registerMatcher("blackout",
                s -> s.nodeKind() == LastMatchReplay.Kind.BLACKOUT_START
                        || s.nodeKind() == LastMatchReplay.Kind.BLACKOUT_END
                        || containsAny(s.id(), "blackout"),
                ReplaySkillEffectRegistry::renderBlackout);

        // 10. Psycho crimson rage
        registerMatcher("psycho",
                s -> s.nodeKind() == LastMatchReplay.Kind.PSYCHO_STATE_CHANGE || containsAny(s.id(), "psycho"),
                ReplaySkillEffectRegistry::renderPsycho);

        // 11. Seven Sins (Wrath / Lust / Envy / Greed / Pride / Gluttony / Sloth)
        registerMatcher("seven_sins",
                s -> containsAny(s.id(), "sin_", "wrath", "lust", "envy", "greed", "pride", "gluttony", "sloth"),
                ReplaySkillEffectRegistry::renderSevenSins);

        // 12. Recon Drone pulse scan & deploy
        registerMatcher("drone_scan",
                s -> containsAny(s.id(), "drone", "pulse_scan", "recon"),
                ReplaySkillEffectRegistry::renderDroneScan);

        // 13. Idiot chick / egg confetti
        registerMatcher("idiot_party",
                s -> containsAny(s.id(), "idiot", "chick", "egg"),
                ReplaySkillEffectRegistry::renderIdiotParty);

        // 14. Doll Tailor stitch / swap
        registerMatcher("tailor_doll",
                s -> containsAny(s.id(), "tailor", "doll"),
                ReplaySkillEffectRegistry::renderTailorSwap);

        // 15. Mime Killer silent ripples
        registerMatcher("mime_mute",
                s -> containsAny(s.id(), "mime"),
                ReplaySkillEffectRegistry::renderMimeRipple);

        // 16. Flower Girl gift bouquet / pepper spray
        registerMatcher("flower_girl",
                s -> containsAny(s.id(), "flower", "bouquet", "pepper_spray"),
                ReplaySkillEffectRegistry::renderFlowerGirl);

        // 17. Revival golden pillar (noellesroles / stupid_express / core)
        registerMatcher("revival",
                s -> s.nodeKind() == LastMatchReplay.Kind.PLAYER_REVIVAL || containsAny(s.id(), "reviv", "resurrect"),
                ReplaySkillEffectRegistry::renderRevival);

        // 18. Teleport / Blink / Dash afterimage (swift_wind, noellesroles, harpymodloader)
        registerMatcher("teleport_dash",
                s -> containsAny(s.id(), "teleport", "blink", "warp", "dash", "swift_wind", "recall", "swap"),
                ReplaySkillEffectRegistry::renderTeleportStreak);

        // 19. Traps (alarm, tripwire, mud, mine, bear trap)
        registerMatcher("trap",
                s -> s.nodeKind() == LastMatchReplay.Kind.TRAP_TRIGGERED
                        || containsAny(s.id(), "trap", "tripwire", "mine"),
                ReplaySkillEffectRegistry::renderTrap);

        // 20. Rope pull / grapple / trapper net
        registerMatcher("rope_net",
                s -> s.nodeKind() == LastMatchReplay.Kind.ROPE_PULL || containsAny(s.id(), "rope", "grapple", "net"),
                ReplaySkillEffectRegistry::renderRopePull);

        // 21. Disguise smoke veil
        registerMatcher("disguise",
                s -> s.nodeKind() == LastMatchReplay.Kind.DISGUISE || containsAny(s.id(), "disguise", "morph"),
                ReplaySkillEffectRegistry::renderDisguise);

        // 22. Generic Kill burst (when not already matched as melee)
        registerMatcher("kill_burst",
                s -> s.nodeKind() == LastMatchReplay.Kind.KILL,
                ReplaySkillEffectRegistry::renderKillBurst);

        // 23. Role Change ring
        registerMatcher("change_role",
                s -> s.nodeKind() == LastMatchReplay.Kind.CHANGE_ROLE,
                ReplaySkillEffectRegistry::renderRoleChange);
    }

    private static boolean renderFirearm(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.045f * u) * ctx.lineScale();
        boolean backfire = (s.flags() & LastMatchReplay.BACKFIRE) != 0;
        boolean hit = (s.flags() & LastMatchReplay.HIT) != 0;
        boolean tacz = containsAny(s.id(), "tacz");
        int core = backfire ? EXPLOSION : (tacz ? 0xFFFFF2B0 : 0xFFFFFFFF);
        int halo = backfire ? EXPLOSION : (tacz ? LAMP : BRASS_LIGHT);
        boolean selfFirstPerson = ctx.firstPersonPov() && ctx.povSubjectUuid() != null
                && ctx.povSubjectUuid().equals(s.actorUuid());
        float sx = s.fromX(), sy = s.fromY(), sz = s.fromZ();
        boolean skipTracer = false;
        if (s.hasFrom() && s.hasTo()) {
            float dx = s.toX() - sx, dy = s.toY() - sy, dz = s.toZ() - sz;
            float shotLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            Vector3f camFrom = ctx.frame().transformPosition(sx, sy, sz, new Vector3f());
            float fromDist = camFrom.length();
            // Push first-person/near-lens firearm tracers past the near clip plane so they never blow up into white triangles.
            if (ctx.isWorldPov() && (selfFirstPerson || fromDist < 0.85f * u)) {
                float push = Math.max(0.90f * u, 0.95f * u - fromDist);
                if (shotLen <= push + 0.15f * u) {
                    skipTracer = true;
                } else {
                    float inv = push / shotLen;
                    sx += dx * inv;
                    sy += dy * inv - (selfFirstPerson ? 0.07f * u : 0f);
                    sz += dz * inv;
                }
            }
            double beam = k < 0.72 ? 1.0 - k / 0.72 : 0.0;
            if (!skipTracer && beam > 0) {
                float povWidthScale = ctx.isWorldPov() ? 0.72f : 1.0f;
                ReplayDraw.segment(ctx.glow(), ctx.frame(),
                        sx, sy, sz, s.toX(), s.toY(), s.toZ(),
                        lw * (tacz ? 1.6f : 1.25f) * povWidthScale, alpha(halo, 0.42 * beam * ctx.alpha()), alpha(halo, 0.14 * beam * ctx.alpha()));
                ReplayDraw.segment(ctx.glow(), ctx.frame(),
                        sx, sy, sz, s.toX(), s.toY(), s.toZ(),
                        lw * 0.48f * povWidthScale, alpha(core, 0.96 * beam * ctx.alpha()), alpha(core, 0.65 * beam * ctx.alpha()));
            }
        }
        if (s.hasFrom() && !selfFirstPerson) {
            Vector3f muzzle = ctx.frame().transformPosition(s.fromX(), s.fromY(), s.fromZ(), new Vector3f());
            float muzzleDist = muzzle.length();
            double nearFade = ctx.isWorldPov()
                    ? (muzzleDist < 0.70f * u ? 0.0 : ReplayStagePlan.smoothstep(0.70f * u, 1.45f * u, muzzleDist))
                    : 1.0;
            if (nearFade > 0.02) {
                ReplayDraw.star(ctx.glow(), muzzle, ctx.right(), ctx.up(),
                        (float) ((tacz ? 0.46 : 0.32) * u * (1.0 - k)) * ctx.lineScale(),
                        tacz ? 10 : 8, (float) (age * 0.4), alpha(core, fade * nearFade), alpha(halo, 0));
            }
        }
        if (s.hasTo() && hit) {
            double hitFade = fade * nearCameraFade(ctx, s.toX(), s.toY(), s.toZ(), 0.65f * u, 1.4f * u);
            if (hitFade > 0.02) {
                ReplayDraw.ring(ctx.glow(), ctx.frame(), s.toX(), s.toY(), s.toZ(),
                        (float) (0.2 + 0.7 * k) * u, 0.09f * u, 20, alpha(RUBY, hitFade), alpha(RUBY, 0));
            }
        }
        return true;
    }

    private static boolean renderMeleeSlash(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.smoothstep(0.4, 1.0, k)) * ctx.alpha();
        float u = ctx.unitScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.95f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        fade *= nearCameraFade(ctx, x, y, z, 0.95f * u, 2.0f * u);
        if (fade <= 0.02) return true;
        float sweep = (float) ReplayStagePlan.easeOutCubic(Math.min(1.0, k / 0.35));
        float start = -1.2f + s.eventId() * 0.5f;
        ReplayDraw.arcXZ(ctx.glow(), ctx.frame(), x, y, z, 1.15f * u, 0.22f * u,
                start, start + sweep * 2.4f, 14, alpha(0xFFFFFFFF, 0.95 * fade), alpha(RUBY, 0.2 * fade));
        ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y - 0.9f * u, z,
                (float) (0.35 + 1.3 * ReplayStagePlan.easeOutCubic(k)) * u, 0.12f * u, 24,
                alpha(RUBY, 0.85 * fade), alpha(RUBY, 0));
        return true;
    }

    private static boolean renderPoison(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.smoothstep(0.55, 1.0, k)) * ctx.alpha();
        float u = ctx.unitScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.05f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        double nearFade = nearCameraFade(ctx, x, y + 0.3f * u, z, 0.80f * u, 1.9f * u);
        if (nearFade <= 0.02) return true;
        fade *= nearFade;
        for (int i = 0; i < 3; i++) {
            double rk = k * 1.4 - i * 0.22;
            if (rk <= 0 || rk >= 1) continue;
            ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y + (float) (rk * 0.25 * u), z,
                    (float) (0.3 + 1.2 * rk) * u, 0.12f * u, 24,
                    alpha(POISON, (1.0 - rk) * 0.82 * ctx.alpha() * nearFade), alpha(POISON, 0));
        }
        for (int i = 0; i < 6; i++) {
            double a = i * Mth.TWO_PI / 6.0 + k * 2.2;
            float r = (0.35f + 0.45f * (float) k) * u;
            float py = y + (float) ((k * 1.4 + (i % 3) * 0.25) * u);
            Vector3f p = ctx.frame().transformPosition(x + Mth.cos((float) a) * r, py, z + Mth.sin((float) a) * r, new Vector3f());
            float pr = 0.20f * u * ctx.lineScale();
            ReplayDraw.softBillboard(ctx.glow(), p, ctx.right(), ctx.up(), pr, pr,
                    alpha(POISON, 0.62 * fade), 0.70f * u, 1.85f * u);
        }
        return true;
    }

    private static boolean renderShieldBreak(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.easeOutCubic(k)) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.04f * u) * ctx.lineScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 1.0f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        ReplayDraw.polygonXZ(ctx.glow(), ctx.frame(), x, y, z, (float) (0.5 + 1.1 * k) * u, 6, (float) (k * 2.0), lw, alpha(SHIELD, fade));
        for (int i = 0; i < 8; i++) {
            float a = i * Mth.TWO_PI / 8f;
            float r0 = (float) (0.4 + 1.2 * k) * u, r1 = r0 + 0.35f * u;
            float dy = (float) (0.4 * k - 0.9 * k * k) * u;
            ReplayDraw.segment(ctx.glow(), ctx.frame(),
                    x + Mth.cos(a) * r0, y + dy, z + Mth.sin(a) * r0,
                    x + Mth.cos(a + 0.35f) * r1, y + dy + 0.15f * u, z + Mth.sin(a + 0.35f) * r1,
                    lw, alpha(0xFFFFFFFF, fade), alpha(SHIELD, 0.3 * fade));
        }
        return true;
    }

    private static boolean renderLockpickSparks(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.012f, 0.03f * u) * ctx.lineScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.85f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        ReplayDraw.wireBox(ctx.glow(), ctx.frame(), x - 0.45f * u, y - 0.8f * u, z - 0.45f * u,
                x + 0.45f * u, y + 1.1f * u, z + 0.45f * u, lw, alpha(LAMP, 0.85 * fade));
        for (int i = 0; i < 6; i++) {
            float a = (float) (i * 1.05 + ctx.animSec() * 8.0);
            Vector3f p = ctx.frame().transformPosition(
                    x + Mth.cos(a) * (0.25f + 0.45f * (float) k) * u,
                    y + (float) (0.3 * Math.sin(k * Math.PI) - 0.4 * k * k) * u,
                    z + Mth.sin(a) * (0.25f + 0.45f * (float) k) * u, new Vector3f());
            ReplayDraw.star(ctx.glow(), p, ctx.right(), ctx.up(), 0.14f * u * ctx.lineScale(), 4, a,
                    alpha(CHAMPAGNE, fade), alpha(LAMP, 0));
        }
        return true;
    }

    private static boolean renderDoorAction(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.012f, 0.035f * u) * ctx.lineScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = s.hasTo() ? s.toY() : s.fromY();
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        boolean sealing = s.nodeKind() == LastMatchReplay.Kind.DOOR_SEAL || s.nodeKind() == LastMatchReplay.Kind.DOOR_LOCK;
        int col = alpha(sealing ? SHIELD : (s.nodeKind() == LastMatchReplay.Kind.DOOR_OPEN ? POISON : LAMP), 0.9 * fade);
        ReplayDraw.wireBox(ctx.glow(), ctx.frame(), x - 0.52f * u, y, z - 0.52f * u,
                x + 0.52f * u, y + 2.0f * u, z + 0.52f * u, lw, col);
        if (sealing) {
            // Sliding brass lock cross-bars
            float bar = (float) ReplayStagePlan.easeOutCubic(Math.min(1.0, k / 0.35)) * 0.48f * u;
            ReplayDraw.segment(ctx.glow(), ctx.frame(), x - bar, y + 1.0f * u, z - bar, x + bar, y + 1.0f * u, z + bar, lw * 1.4f, col, col);
            ReplayDraw.segment(ctx.glow(), ctx.frame(), x - bar, y + 1.0f * u, z + bar, x + bar, y + 1.0f * u, z - bar, lw * 1.4f, col, col);
        }
        return true;
    }

    private static boolean renderBombC4(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.smoothstep(0.6, 1.0, k)) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.04f * u) * ctx.lineScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.05f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        if (s.nodeKind() == LastMatchReplay.Kind.BOMB_PLANT) {
            boolean blink = Math.sin(ctx.animSec() * 14.0) > 0;
            Vector3f c = ctx.frame().transformPosition(x, y + 0.2f * u, z, new Vector3f());
            float lr = 0.28f * u * ctx.lineScale();
            ReplayDraw.softBillboard(ctx.glow(), c, ctx.right(), ctx.up(), lr, lr,
                    alpha(RUBY, (blink ? 0.95 : 0.22) * fade), 0.45f * u, 1.3f * u);
            ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y, z, 0.75f * u, 0.09f * u, 24, alpha(RUBY, 0.85 * fade), alpha(RUBY, 0));
        } else if (s.nodeKind() == LastMatchReplay.Kind.BOMB_DEFUSE) {
            ReplayDraw.polygonXZ(ctx.glow(), ctx.frame(), x, y + 0.1f * u, z, (float) (0.7 + 0.6 * k) * u, 6, (float) k, lw, alpha(SHIELD, fade));
            ReplayDraw.sphereRings(ctx.glow(), ctx.frame(), x, y + 0.4f * u, z, (float) (0.6 + 0.5 * k) * u, lw * 0.8f, 16, alpha(SHIELD, 0.65 * fade));
        } else {
            // Detonation shockwave — attenuated in 1:1 POV so additive glow never saturates corridors to pure white.
            boolean worldPov = ctx.isWorldPov();
            double nearFade = nearCameraFade(ctx, x, y + 0.5f * u, z, 0.90f * u, 2.6f * u);
            double ringGain = worldPov ? 0.42 * nearFade : 0.95;
            float ringThick = (worldPov ? 0.14f : 0.24f) * u;
            int ringCount = worldPov ? 2 : 3;
            for (int r = 0; r < ringCount; r++) {
                double rk = Mth.clamp(k * 1.8 - r * 0.22, 0.0, 1.0);
                if (rk <= 0 || ringGain <= 0.02) continue;
                int ringCol = (r == 0 && !worldPov) ? 0xFFFFFFFF : (r == 0 ? LAMP : EXPLOSION);
                ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y + r * 0.06f * u, z,
                        (float) (0.4 + (worldPov ? 2.6 : 3.8) * ReplayStagePlan.easeOutCubic(rk)) * u, ringThick, 32,
                        alpha(ringCol, (1.0 - rk) * ringGain * ctx.alpha()), alpha(LAMP, 0));
            }
            Vector3f c = ctx.frame().transformPosition(x, y + 0.7f * u, z, new Vector3f());
            float flash = (worldPov ? Math.min(0.95f, (float) (0.90 * (1.0 - k))) : (float) (1.9 * (1.0 - k))) * u * ctx.lineScale();
            int flashCol = worldPov ? EXPLOSION : LAMP;
            double flashAlpha = (worldPov ? 0.28 : 0.62) * fade;
            float nearCull = (worldPov ? 1.45f : 0.70f) * u;
            float nearFull = (worldPov ? 3.40f : 1.85f) * u;
            ReplayDraw.softBillboard(ctx.glow(), c, ctx.right(), ctx.up(), flash, flash,
                    alpha(flashCol, flashAlpha), nearCull, nearFull);
        }
        return true;
    }

    private static boolean renderGrenade(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.smoothstep(0.55, 1.0, k)) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.012f, 0.036f * u) * ctx.lineScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.08f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        String id = s.id() == null ? "" : s.id().toLowerCase(Locale.ROOT);

        if (s.nodeKind() == LastMatchReplay.Kind.GRENADE_THROWN && s.hasFrom() && s.hasTo()) {
            int col = id.contains("smoke") ? SMOKE_GRAY : id.contains("flash") ? FLASH_WHITE
                    : id.contains("slime") ? EMERALD : id.contains("decoy") ? DRONE_CYAN : EXPLOSION;
            float dist = (float) Math.hypot(s.toX() - s.fromX(), s.toZ() - s.fromZ());
            ReplayDraw.parabolicArc(ctx.glow(), ctx.frame(), s.fromX(), s.fromY() + 1.1f * u, s.fromZ(),
                    x, y, z, Math.max(0.6f * u, dist * 0.34f), 14, lw, alpha(col, 0.85 * fade), alpha(col, 0.65 * fade),
                    (float) Math.min(1.0, k * 2.2));
            return true;
        }
        if (id.contains("flash")) {
            boolean worldPov = ctx.isWorldPov();
            float rad = (float) (0.5 + (worldPov ? 2.2 : 3.2) * ReplayStagePlan.easeOutCubic(Math.min(1.0, k * 2.4))) * u;
            double nearFade = nearCameraFade(ctx, x, y + 0.5f * u, z, 1.0f * u, 2.8f * u);
            if (nearFade > 0.02) {
                ReplayDraw.sphereRings(ctx.glow(), ctx.frame(), x, y + 0.5f * u, z, rad, lw * (worldPov ? 0.9f : 1.4f),
                        18, alpha(FLASH_WHITE, (worldPov ? 0.36 : 1.0) * fade * nearFade));
            }
            Vector3f c = ctx.frame().transformPosition(x, y + 0.6f * u, z, new Vector3f());
            float fr = (worldPov ? Math.min(1.1f * u, rad * 0.55f) : rad * 1.05f) * ctx.lineScale();
            ReplayDraw.softBillboard(ctx.glow(), c, ctx.right(), ctx.up(), fr, fr,
                    alpha(FLASH_WHITE, (worldPov ? 0.26 : 0.65) * (1.0 - k) * ctx.alpha()),
                    (worldPov ? 1.50f : 0.70f) * u, (worldPov ? 3.80f : 1.85f) * u);
        } else if (id.contains("smoke")) {
            float rad = (float) (0.7 + 2.4 * ReplayStagePlan.easeOutCubic(Math.min(1.0, k * 1.5))) * u;
            for (int i = 0; i < 8; i++) {
                float a = i * Mth.TWO_PI / 8f + (float) (k * 0.5);
                Vector3f p = ctx.frame().transformPosition(x + Mth.cos(a) * rad * 0.55f, y + (0.4f + (i % 3) * 0.45f) * u,
                        z + Mth.sin(a) * rad * 0.55f, new Vector3f());
                float sr = rad * 0.52f * ctx.lineScale();
                ReplayDraw.softBillboard(ctx.fill(), p, ctx.right(), ctx.up(), sr, sr,
                        alpha(SMOKE_GRAY, 0.40 * fade), 0.80f * u, 2.1f * u);
            }
        } else if (id.contains("incendiary") || id.contains("molotov") || id.contains("fire")) {
            float rad = (float) (0.6 + 2.0 * ReplayStagePlan.easeOutCubic(Math.min(1.0, k * 1.8))) * u;
            ReplayDraw.disc(ctx.glow(), ctx.frame(), x, y, z, rad, 20, alpha(EXPLOSION, 0.42 * fade), alpha(LAMP, 0));
            for (int i = 0; i < 9; i++) {
                float a = i * Mth.TWO_PI / 9f;
                float h = (float) (0.55 + 0.45 * Math.sin(ctx.animSec() * 11.0 + i * 1.7)) * u;
                Vector3f p = ctx.frame().transformPosition(x + Mth.cos(a) * rad * 0.6f, y + h * 0.5f,
                        z + Mth.sin(a) * rad * 0.6f, new Vector3f());
                ReplayDraw.softBillboard(ctx.glow(), p, ctx.right(), ctx.up(), 0.30f * u * ctx.lineScale(), h * 1.1f * ctx.lineScale(),
                        alpha(i % 2 == 0 ? EXPLOSION : LAMP, 0.62 * fade), 0.70f * u, 1.85f * u);
            }
        } else if (id.contains("slime")) {
            float rad = (float) (0.6 + 2.2 * ReplayStagePlan.easeOutCubic(Math.min(1.0, k * 2.0))) * u;
            ReplayDraw.disc(ctx.fill(), ctx.frame(), x, y, z, rad, 22, alpha(EMERALD, 0.45 * fade), alpha(POISON, 0.08 * fade));
            ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y + 0.02f * u, z, rad, 0.14f * u, 24, alpha(EMERALD, 0.85 * fade), alpha(EMERALD, 0));
        } else if (id.contains("decoy") || id.contains("firecracker")) {
            for (int r = 0; r < 3; r++) {
                float rk = (float) ((ctx.animSec() * 1.8 + r * 0.33) % 1.0);
                ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y + 0.2f * u, z, (0.3f + rk * 2.2f) * u, 0.08f * u, 20,
                        alpha(DRONE_CYAN, (1f - rk) * 0.8 * fade), alpha(DRONE_CYAN, 0));
            }
        } else if (id.contains("wall") || id.contains("breach")) {
            float rad = (float) (0.5 + 3.0 * ReplayStagePlan.easeOutCubic(k)) * u;
            ReplayDraw.polygonXZ(ctx.glow(), ctx.frame(), x, y + 0.4f * u, z, rad, 8, (float) k, lw * 1.5f, alpha(LAMP, fade));
            ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y, z, rad, 0.24f * u, 24, alpha(EXPLOSION, 0.9 * fade), alpha(EXPLOSION, 0));
        } else {
            return renderBombC4(ctx, s, age, dur);
        }
        return true;
    }

    private static boolean renderBlackout(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        boolean start = s.nodeKind() != LastMatchReplay.Kind.BLACKOUT_END;
        float x = s.hasFrom() ? s.fromX() : s.toX();
        float y = (s.hasFrom() ? s.fromY() : s.toY()) + 0.1f * u;
        float z = s.hasFrom() ? s.fromZ() : s.toZ();
        float r = (float) (0.5 + 18.0 * ReplayStagePlan.easeOutCubic(k)) * u;
        ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y, z, r, 0.45f * u, 48,
                alpha(start ? 0xFF8FB0FF : LAMP, 0.72 * fade), alpha(NIGHT_0, 0));
        return true;
    }

    private static boolean renderPsycho(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.015f, 0.045f * u) * ctx.lineScale();
        float x = s.hasFrom() ? s.fromX() : s.toX();
        float y = s.hasFrom() ? s.fromY() : s.toY();
        float z = s.hasFrom() ? s.fromZ() : s.toZ();
        ReplayDraw.column(ctx.glow(), ctx.frame(), x, y, z, (float) (3.2 * ReplayStagePlan.easeOutCubic(k)) * u, 0.45f * u,
                alpha(PSYCHO, 0.72 * fade), alpha(RUBY, 0));
        for (int i = 0; i < 10; i++) {
            float a = i * Mth.TWO_PI / 10f + (float) (k * 0.4);
            float r = (float) (0.4 + 1.8 * k) * u;
            ReplayDraw.segment(ctx.glow(), ctx.frame(), x, y + 0.2f * u, z,
                    x + Mth.cos(a) * r, y + 0.2f * u, z + Mth.sin(a) * r, lw, alpha(PSYCHO, fade), alpha(RUBY, 0));
        }
        return true;
    }

    private static boolean renderSevenSins(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.smoothstep(0.6, 1.0, k)) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.036f * u) * ctx.lineScale();
        String id = s.id().toLowerCase(Locale.ROOT);
        float x = s.hasFrom() ? s.fromX() : s.toX();
        float y = (s.hasFrom() ? s.fromY() : s.toY()) + 0.05f * u;
        float z = s.hasFrom() ? s.fromZ() : s.toZ();
        fade *= nearCameraFade(ctx, x, y + 0.4f * u, z, 0.90f * u, 2.2f * u);
        if (fade <= 0.02) return true;
        int col = id.contains("wrath") ? RUBY : id.contains("lust") ? ROSE_GOLD
                : id.contains("envy") ? EMERALD : id.contains("greed") ? BRASS_LIGHT
                : id.contains("pride") ? CHAMPAGNE : id.contains("sloth") ? SLOTH_BLUE : 0xFF9333EA;

        ReplayDraw.astrolabeRune(ctx.glow(), ctx.frame(), x, y, z, (float) (0.7 + 0.9 * ReplayStagePlan.easeOutCubic(k)) * u,
                (float) (ctx.animSec() * 2.0), lw, alpha(col, 0.92 * fade), alpha(BRASS, 0.78 * fade));
        if (id.contains("sloth") && ctx.replayFrame() != null) {
            Vector3f top = ctx.frame().transformPosition(x, y + (2.0f + (float) k * 0.5f) * u, z, new Vector3f());
            ctx.replayFrame().billboardText(top, Component.literal("z Z z"), 0.018f * u * ctx.lineScale(), alpha(SLOTH_BLUE, fade));
        } else if (s.hasFrom() && s.hasTo()) {
            ReplayDraw.parabolicArc(ctx.glow(), ctx.frame(), x, y + 1.0f * u, z,
                    s.toX(), s.toY() + 0.8f * u, s.toZ(), 0.7f * u, 12, lw * 1.2f,
                    alpha(col, fade), alpha(BRASS_LIGHT, fade), (float) Math.min(1.0, k * 2.0));
        }
        return true;
    }

    private static boolean renderDroneScan(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.04f * u) * ctx.lineScale();
        float x = s.hasFrom() ? s.fromX() : s.toX();
        float y = (s.hasFrom() ? s.fromY() : s.toY()) + 0.8f * u;
        float z = s.hasFrom() ? s.fromZ() : s.toZ();
        float rad = (float) (0.8 + 14.0 * (1.0 - (1.0 - k) * (1.0 - k))) * u;
        ReplayDraw.sphereRings(ctx.glow(), ctx.frame(), x, y, z, rad, lw, 24, alpha(DRONE_CYAN, 0.78 * fade));
        return true;
    }

    private static boolean renderIdiotParty(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.6f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        int[] cols = {BRASS_LIGHT, ROSE_GOLD, DRONE_CYAN, EMERALD};
        for (int i = 0; i < 8; i++) {
            float a = i * Mth.TWO_PI / 8f + (float) (k * 3.0);
            float r = (float) (0.2 + 1.1 * k) * u;
            float py = y + (float) (1.1 * k - 1.3 * k * k) * u;
            Vector3f p = ctx.frame().transformPosition(x + Mth.cos(a) * r, py, z + Mth.sin(a) * r, new Vector3f());
            ReplayDraw.star(ctx.glow(), p, ctx.right(), ctx.up(), 0.18f * u * ctx.lineScale(), 4, a,
                    alpha(cols[i & 3], fade), alpha(cols[i & 3], 0));
        }
        return true;
    }

    private static boolean renderTailorSwap(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.04f * u) * ctx.lineScale();
        if (s.hasFrom() && s.hasTo()) {
            ReplayDraw.parabolicArc(ctx.glow(), ctx.frame(),
                    s.fromX(), s.fromY() + 1.0f * u, s.fromZ(),
                    s.toX(), s.toY() + 1.0f * u, s.toZ(),
                    1.2f * u, 16, lw * 1.4f, alpha(NEUTRAL, fade), alpha(CHAMPAGNE, fade), 1.0f);
        }
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.1f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y, z, (float) (0.4 + 1.2 * k) * u, 0.12f * u, 24,
                alpha(CHAMPAGNE, fade), alpha(NEUTRAL, 0));
        return true;
    }

    private static boolean renderMimeRipple(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 1.1f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        for (int i = 0; i < 3; i++) {
            double rk = Mth.clamp(k * 1.4 - i * 0.25, 0.0, 1.0);
            if (rk <= 0) continue;
            ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y, z, (float) (0.35 + 1.4 * rk) * u, 0.10f * u, 24,
                    alpha(MIME_VIOLET, (1.0 - rk) * 0.9 * ctx.alpha()), alpha(MIME_VIOLET, 0));
        }
        if (s.hasFrom() && s.hasTo()) {
            ReplayDraw.segment(ctx.glow(), ctx.frame(), s.fromX(), s.fromY() + 1.2f * u, s.fromZ(),
                    x, y, z, Math.max(0.012f, 0.03f * u) * ctx.lineScale(), alpha(MIME_VIOLET, fade), alpha(CHAMPAGNE, fade));
        }
        return true;
    }

    private static boolean renderFlowerGirl(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.036f * u) * ctx.lineScale();
        boolean spray = containsAny(s.id(), "pepper");
        int col = spray ? LAMP : ROSE_GOLD;
        if (s.hasFrom() && s.hasTo()) {
            ReplayDraw.parabolicArc(ctx.glow(), ctx.frame(),
                    s.fromX(), s.fromY() + 1.1f * u, s.fromZ(),
                    s.toX(), s.toY() + 1.1f * u, s.toZ(),
                    0.8f * u, 14, lw * 1.25f, alpha(col, fade), alpha(CHAMPAGNE, fade), (float) Math.min(1.0, k * 1.8));
        }
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.1f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y, z, (float) (0.4 + 1.0 * k) * u, 0.12f * u, 24,
                alpha(col, 0.88 * fade), alpha(CHAMPAGNE, 0));
        return true;
    }

    private static boolean renderRevival(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.smoothstep(0.6, 1.0, k)) * ctx.alpha();
        float u = ctx.unitScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = s.hasTo() ? s.toY() : s.fromY();
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        float h = (float) (4.5 * ReplayStagePlan.easeOutCubic(Math.min(1.0, k * 2.0))) * u;
        ReplayDraw.column(ctx.glow(), ctx.frame(), x, y, z, h, 0.42f * u, alpha(BRASS_LIGHT, 0.65 * fade), alpha(BRASS_LIGHT, 0));
        ReplayDraw.column(ctx.glow(), ctx.frame(), x, y, z, h, 0.14f * u, alpha(CHAMPAGNE, 0.9 * fade), alpha(CHAMPAGNE, 0));
        ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y + 0.04f * u, z, (float) (0.4 + 1.6 * k) * u, 0.14f * u, 28,
                alpha(CHAMPAGNE, fade), alpha(BRASS, 0));
        return true;
    }

    private static boolean renderTeleportStreak(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.015f, 0.05f * u) * ctx.lineScale();
        int col = opaque(s.accentColor(), DRONE_CYAN);
        if (s.hasFrom() && s.hasTo()) {
            ReplayDraw.segment(ctx.glow(), ctx.frame(),
                    s.fromX(), s.fromY() + 1.0f * u, s.fromZ(),
                    s.toX(), s.toY() + 1.0f * u, s.toZ(),
                    lw, alpha(col, 0.35 * fade), alpha(CHAMPAGNE, 0.95 * fade));
            ReplayDraw.ring(ctx.glow(), ctx.frame(), s.fromX(), s.fromY() + 0.05f * u, s.fromZ(),
                    (float) (0.7 * (1.0 - k * 0.5)) * u, 0.10f * u, 20, alpha(col, 0.7 * fade), alpha(col, 0));
            ReplayDraw.ring(ctx.glow(), ctx.frame(), s.toX(), s.toY() + 0.05f * u, s.toZ(),
                    (float) (0.3 + 1.1 * k) * u, 0.12f * u, 20, alpha(CHAMPAGNE, 0.9 * fade), alpha(col, 0));
            return true;
        }
        return false;
    }

    private static boolean renderTrap(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.04f * u) * ctx.lineScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.04f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        float snap = (float) ReplayStagePlan.easeOutCubic(Math.min(1.0, k / 0.2));
        for (int i = 0; i < 8; i++) {
            float a = i * Mth.TWO_PI / 8f;
            float r0 = (0.75f - 0.25f * snap) * u, r1 = (0.75f - 0.58f * snap) * u;
            ReplayDraw.segment(ctx.glow(), ctx.frame(),
                    x + Mth.cos(a) * r0, y, z + Mth.sin(a) * r0,
                    x + Mth.cos(a) * r1, y + 0.38f * u, z + Mth.sin(a) * r1,
                    lw, alpha(BRASS, 0.9 * fade), alpha(RUBY, fade));
        }
        return true;
    }

    private static boolean renderRopePull(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float lw = Math.max(0.014f, 0.036f * u) * ctx.lineScale();
        if (s.hasFrom() && s.hasTo()) {
            ReplayDraw.segment(ctx.glow(), ctx.frame(),
                    s.fromX(), s.fromY() + 1.0f * u, s.fromZ(),
                    s.toX(), s.toY() + 1.0f * u, s.toZ(),
                    lw, alpha(LAMP, 0.9 * fade), alpha(BRASS_LIGHT, 0.9 * fade));
            return true;
        }
        return false;
    }

    private static boolean renderDisguise(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float x = s.hasFrom() ? s.fromX() : s.toX();
        float y = (s.hasFrom() ? s.fromY() : s.toY()) + 0.5f * u;
        float z = s.hasFrom() ? s.fromZ() : s.toZ();
        for (int i = 0; i < 6; i++) {
            float a = i * Mth.TWO_PI / 6f + (float) (k * 2.0);
            float r = (float) (0.3 + 0.85 * k) * u;
            Vector3f p = ctx.frame().transformPosition(x + Mth.cos(a) * r, y + (float) (0.5 * k * u), z + Mth.sin(a) * r, new Vector3f());
            float dr = 0.34f * u * ctx.lineScale();
            ReplayDraw.softBillboard(ctx.fill(), p, ctx.right(), ctx.up(), dr, dr,
                    alpha(DISGUISE, 0.45 * fade), 0.70f * u, 1.85f * u);
        }
        ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y - 0.45f * u, z, (float) (0.4 + 0.9 * k) * u, 0.12f * u, 24,
                alpha(DISGUISE, 0.85 * fade), alpha(DISGUISE, 0));
        return true;
    }

    private static boolean renderKillBurst(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - ReplayStagePlan.smoothstep(0.5, 1.0, k)) * ctx.alpha();
        float u = ctx.unitScale();
        float x = s.hasTo() ? s.toX() : s.fromX();
        float y = (s.hasTo() ? s.toY() : s.fromY()) + 0.04f * u;
        float z = s.hasTo() ? s.toZ() : s.fromZ();
        fade *= nearCameraFade(ctx, x, y + 0.4f * u, z, 0.90f * u, 2.2f * u);
        if (fade <= 0.02) return true;
        double povGain = ctx.isWorldPov() ? 0.55 : 1.0;
        ReplayDraw.column(ctx.glow(), ctx.frame(), x, y, z, 2.8f * u, 0.25f * u, alpha(RUBY, 0.55 * povGain * fade), alpha(RUBY, 0));
        ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y, z, (float) (0.3 + 1.8 * ReplayStagePlan.easeOutCubic(k)) * u, 0.14f * u, 28,
                alpha(RUBY, 0.85 * povGain * fade), alpha(RUBY, 0));
        return true;
    }

    private static boolean renderRoleChange(RenderContext ctx, SkillSignal s, double age, double dur) {
        double k = Mth.clamp(age / dur, 0.0, 1.0);
        double fade = (1.0 - k) * ctx.alpha();
        float u = ctx.unitScale();
        float x = s.hasFrom() ? s.fromX() : s.toX();
        float y = s.hasFrom() ? s.fromY() : s.toY();
        float z = s.hasFrom() ? s.fromZ() : s.toZ();
        int col = opaque(s.accentColor(), BRASS_LIGHT);
        ReplayDraw.ring(ctx.glow(), ctx.frame(), x, y + (float) (2.1 * k) * u, z, 0.65f * u, 0.12f * u, 24,
                alpha(col, 0.9 * fade), alpha(col, 0.1 * fade));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isFlashbang(SkillSignal s) {
        return containsAny(s.id(), "flash_grenade", "flashbang", "flash");
    }

    private static boolean containsAny(@Nullable String raw, String... tokens) {
        if (raw == null || raw.isBlank()) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String tok : tokens) {
            if (lower.contains(tok)) return true;
        }
        return false;
    }

    private static double defaultDurationTicks(SkillSignal s) {
        if (s.nodeKind() == null) return 36.0;
        return switch (s.nodeKind()) {
            case KILL -> 44.0;
            case GUNSHOT -> 14.0;
            case GRENADE_EXPLOSION, BOMB_DETONATE -> 42.0;
            case POISON, SHIELD_BROKEN, PLAYER_REVIVAL -> 36.0;
            case SKILL_RELEASE, ITEM_USED -> 40.0;
            default -> 24.0;
        };
    }

    static String prettySkillName(@Nullable String rawId) {
        if (rawId == null || rawId.isBlank()) return "Unknown Skill";
        String id = rawId.trim();
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(hash + 1);
        String[] parts = path.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.isEmpty() ? id : sb.toString();
    }
}
