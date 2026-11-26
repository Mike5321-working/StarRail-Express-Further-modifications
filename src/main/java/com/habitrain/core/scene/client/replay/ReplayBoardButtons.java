package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.network.replay.ReplayPackets;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import com.habitrain.core.scene.client.ClientReplayBoard;
import com.habitrain.core.scene.replay.ReplayBoardSessionState;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.habitrain.core.scene.client.replay.ReplayBoardSite.BASE_TOP;

/**
 * World-space interactive Art Deco controls for {@link SettlementSummaryBoard}:
 * <ul>
 *   <li><b>Idle mode</b>: floating "Start Playback" and "Export Video" plaques in front of the
 *       crystal cube sculpture.</li>
 *   <li><b>Active session mode (Side-Edge Deck Console)</b>: full interactive Art Deco control bar
 *       and timeline scrubber mounted directly onto the table's {@code -20°} tilted brass-and-ebony
 *       edge plaques facing the player. Aiming at any deck button or timeline position and right-clicking
 *       (even while holding a lobby item or train ticket) fires the action or smoothly scrubs the timeline.</li>
 * </ul>
 */
public final class ReplayBoardButtons {
    enum Action {
        PLAY, EXPORT,
        TIMELINE,
        STEP_BACK, TOGGLE_PAUSE, STEP_FORWARD, END_SESSION,
        SPEED_0_5, SPEED_1_0, SPEED_2_0, SPEED_4_0,
        VIEW_GLOBAL, FOLLOW_PREV, OPEN_ROSTER, FOLLOW_NEXT,
        TOGGLE_CLOSEUP, TOGGLE_ROOF
    }

    /** What the table is showing when idle, derived from shared client replay state and synced session. */
    enum Phase { NO_ROUND, LOADING, READY, STARTING, PLAYING }

    /** Pure ray-plane hit result in plaque-local coordinates. */
    record PlaneHit(double distance, float localX, float localY) {}

    record Target(Action action, BlockPos board, boolean enabled, Component reason,
                  double seekTick, float hoverFraction, @Nullable UUID nextFollowTarget, boolean nextFlag) {
        Target(Action action, BlockPos board, boolean enabled, Component reason) {
            this(action, board, enabled, reason, 0, 0, null, false);
        }
    }

    private record Plaque(Target target, Matrix4f sign, float halfWidth, float halfHeight,
                          float scale, float textScale, Phase phase, Component label, double fade) {}

    private record DeckWidget(Action action, float x0, float y0, float x1, float y1,
                              Component label, Component hint, boolean selected, boolean accentRuby,
                              boolean accentGold, boolean accentCyan, boolean enabled,
                              @Nullable UUID avatarPlayer, @Nullable UUID nextFollow, boolean nextFlag) {}

    private record DeckSurface(BlockPos board, Matrix4f matrix, float worldScale, double fade,
                               ReplayBoardModel model, ReplayBoardSessionState session,
                               double displayTick, boolean finishedHold, List<DeckWidget> widgets) {}

    static final float TIMELINE_X0 = -1.22f;
    static final float TIMELINE_X1 = 1.22f;
    static final float TIMELINE_Y0 = -0.155f;
    static final float TIMELINE_Y1 = 0.025f;
    static final float DECK_X0 = -1.30f;
    static final float DECK_X1 = 1.30f;
    static final float DECK_Y0 = -0.57f;
    static final float DECK_Y1 = -0.195f;

    private static final int GOLD_HALO = 0xFFE89E30, GOLD_CORE = 0xFFFFE6A8;
    private static final int CYAN_HALO = 0xFF28A4FF, CYAN_CORE = 0xFFAEE8FF;
    private static final int RUBY_HALO = 0xFFE83A4E, RUBY_CORE = 0xFFFFB2BC;

    private static final long STALE_NS = 250_000_000L, DEBOUNCE_NS = 240_000_000L,
            SEEK_THROTTLE_NS = 90_000_000L, PRESS_NS = 900_000_000L, FLASH_NS = 160_000_000L;
    private static final double HIT_PAD = 0.02;

    private static final Map<BlockPos, Long> PRESSED = new HashMap<>();
    private static final Map<BlockPos, List<Plaque>> PLAQUES = new HashMap<>();
    private static final Map<BlockPos, List<DeckSurface>> DECKS = new HashMap<>();

    private static Vec3 frameEye = Vec3.ZERO, frameDir = new Vec3(0, 0, 1), frameCam = Vec3.ZERO;
    private static @Nullable Target hovered;
    private static @Nullable DeckSurface hoveredDeckSurface;
    private static @Nullable DeckWidget hoveredDeckWidget;
    private static double hoveredDistance = -1;
    private static long hoveredStamp, lastTrigger, lastSeekSent;
    private static @Nullable BlockPos scrubPreviewBoard;
    private static double scrubPreviewTick;
    private static long scrubPreviewUntilNs;

    private ReplayBoardButtons() {}

    // ------------------------------------------------------------------ frame lifecycle

    static void beginFrame(Minecraft mc, Vec3 camera, float partialTick) {
        hovered = null;
        hoveredDeckSurface = null;
        hoveredDeckWidget = null;
        hoveredDistance = -1;
        PLAQUES.clear();
        DECKS.clear();
        frameCam = camera;
        var player = mc.player;
        if (player == null) return;
        frameEye = player.getEyePosition(partialTick);
        frameDir = player.getViewVector(partialTick);
    }

    static void reset() {
        hovered = null;
        hoveredDeckSurface = null;
        hoveredDeckWidget = null;
        hoveredDistance = -1;
        PRESSED.clear();
        PLAQUES.clear();
        DECKS.clear();
        scrubPreviewBoard = null;
        scrubPreviewUntilNs = 0;
    }

    /** Returns an optimistic local tick preview briefly after right-click timeline scrubbing. */
    static double previewTickFor(BlockPos pos, double fallbackTick) {
        if (pos.equals(scrubPreviewBoard) && System.nanoTime() < scrubPreviewUntilNs) {
            return scrubPreviewTick;
        }
        return fallbackTick;
    }

    // ------------------------------------------------------------------ input

    public static boolean handleUse(Minecraft mc) {
        return handleUseWithDelay(mc) >= 0;
    }

    /**
     * Use-key hook (see {@link com.habitrain.core.client.mixin.ReplayBoardButtonUseMixin}).
     * Returns {@code -1} if the crosshair is not on a replay control; otherwise returns the desired
     * {@code rightClickDelay} ({@code 1} for smooth continuous timeline scrubbing, {@code 6} for buttons).
     */
    public static int handleUseWithDelay(Minecraft mc) {
        Target target = hovered;
        if (target == null || mc.screen != null || mc.player == null || mc.level == null
                || System.nanoTime() - hoveredStamp > STALE_NS) {
            return -1;
        }
        long now = System.nanoTime();
        if (target.action() == Action.TIMELINE) {
            if (!target.enabled()) {
                if (now - lastTrigger >= DEBOUNCE_NS) {
                    lastTrigger = now;
                    mc.player.displayClientMessage(target.reason(), true);
                }
                return 6;
            }
            scrubPreviewBoard = target.board().immutable();
            scrubPreviewTick = target.seekTick();
            scrubPreviewUntilNs = now + 260_000_000L;
            if (now - lastSeekSent >= SEEK_THROTTLE_NS) {
                lastSeekSent = now;
                ClientReplayBoard.seekBoard(target.board(), target.seekTick());
                if (now - lastTrigger >= 220_000_000L) {
                    lastTrigger = now;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.25f));
                }
            }
            return 1;
        }

        if (now - lastTrigger < DEBOUNCE_NS) return 6;
        lastTrigger = now;
        if (!target.enabled()) {
            mc.player.displayClientMessage(target.reason(), true);
            return 6;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        switch (target.action()) {
            case PLAY -> {
                UUID round = ClientReplayBoard.availableRound();
                if (!ReplayPackets.EMPTY.equals(round) && ClientReplayBoard.startBoardPlayback(target.board(), round)) {
                    PRESSED.put(target.board(), now);
                }
            }
            case EXPORT -> mc.setScreen(new ReplayExportScreen(mc.level, target.board()));
            case STEP_BACK -> ClientReplayBoard.stepBoard(target.board(), -200.0);
            case TOGGLE_PAUSE -> {
                if (target.nextFlag()) ClientReplayBoard.setPaused(target.board(), false);
                else ClientReplayBoard.togglePause(target.board());
            }
            case STEP_FORWARD -> ClientReplayBoard.stepBoard(target.board(), 200.0);
            case END_SESSION -> ClientReplayBoard.endBoardSession(target.board());
            case SPEED_0_5 -> ClientReplayBoard.setBoardSpeed(target.board(), 0.5f);
            case SPEED_1_0 -> ClientReplayBoard.setBoardSpeed(target.board(), 1.0f);
            case SPEED_2_0 -> ClientReplayBoard.setBoardSpeed(target.board(), 2.0f);
            case SPEED_4_0 -> ClientReplayBoard.setBoardSpeed(target.board(), 4.0f);
            case VIEW_GLOBAL -> ClientReplayBoard.setBoardFollow(target.board(), null);
            case FOLLOW_PREV, FOLLOW_NEXT -> ClientReplayBoard.setBoardFollow(target.board(), target.nextFollowTarget());
            case OPEN_ROSTER -> mc.setScreen(new ReplayBoardControlsScreen(mc.level, target.board()));
            case TOGGLE_CLOSEUP -> ClientReplayBoard.setBoardKillCloseup(target.board(), target.nextFlag());
            case TOGGLE_ROOF -> ClientReplayBoard.setBoardRoofCutaway(target.board(), target.nextFlag());
            case TIMELINE -> {}
        }
        return 6;
    }

    // ------------------------------------------------------------------ idle state helpers

    static Phase phase(ReplayFrame f, ReplayBoardSite s) {
        UUID round = ClientReplayBoard.availableRound();
        ReplayBoardSessionState session = ClientReplayBoard.boardSession(s.pos());
        if (session != null && session.active()) {
            return session.preparing() || f.model == null ? Phase.STARTING : Phase.PLAYING;
        }
        if (f.level.getBlockEntity(s.pos()) instanceof SettlementSummaryBoard.Marker marker) {
            var playback = marker.playback();
            if (playback != null && playback.pendingOrPlaying(f.level.getGameTime())) {
                return f.frame != null ? Phase.PLAYING : Phase.STARTING;
            }
        }
        if (ReplayPackets.EMPTY.equals(round)) return Phase.NO_ROUND;
        if (!ClientReplayBoard.replayLoaded()) return Phase.LOADING;
        Long pressed = PRESSED.get(s.pos());
        if (pressed != null) {
            if (System.nanoTime() - pressed < PRESS_NS) return Phase.STARTING;
            PRESSED.remove(s.pos());
        }
        return Phase.READY;
    }

    static Component bannerText(Phase phase) {
        return switch (phase) {
            case NO_ROUND -> ReplayBoardTexts.tr("idle");
            case LOADING, PLAYING -> ReplayBoardTexts.tr("loading");
            case READY -> ReplayBoardTexts.tr("ready");
            case STARTING -> ReplayBoardTexts.tr("starting");
        };
    }

    private static Component playLabel(Phase phase) {
        return ReplayBoardTexts.tr(switch (phase) {
            case NO_ROUND -> "button.no_round";
            case LOADING -> "button.loading";
            case READY -> "button.play";
            case STARTING, PLAYING -> "button.playing";
        });
    }

    private static Component playReason(Phase phase) {
        return switch (phase) {
            case NO_ROUND -> ReplayBoardTexts.tr("controls.no_round");
            case LOADING -> ReplayBoardTexts.tr("controls.loading_round");
            case READY -> ReplayBoardTexts.tr("controls.play_unsupported");
            case STARTING, PLAYING -> ReplayBoardTexts.tr("controls.playing");
        };
    }

    private static Component exportReason(Phase phase) {
        if (phase == Phase.NO_ROUND) return ReplayBoardTexts.tr("controls.no_round");
        if (!ClientReplayBoard.replayLoaded()) return ReplayBoardTexts.tr("controls.loading_round");
        return ReplayBoardTexts.tr("controls.map_unavailable");
    }

    // ------------------------------------------------------------------ ray-plane geometry

    /**
     * Distance along the ray at which it crosses a camera-facing plaque, or {@code -1} when it misses.
     */
    static double hitDistance(Vec3 eye, Vec3 dir, Vec3 center, Vec3 right, Vec3 up,
                              double halfW, double halfH, double reach) {
        Vec3 normal = right.cross(up);
        double denom = dir.dot(normal);
        if (Math.abs(denom) < 1.0e-6) return -1;
        double t = center.subtract(eye).dot(normal) / denom;
        if (!(t > 0.05) || t > reach) return -1;
        Vec3 offset = eye.add(dir.scale(t)).subtract(center);
        return Math.abs(offset.dot(right)) <= halfW && Math.abs(offset.dot(up)) <= halfH ? t : -1;
    }

    /**
     * Intersects the viewer's ray with a world-space tilted plaque plane defined by camera-relative
     * transform {@code plaqueCamRel}, returning local {@code (x, y)} coordinates inside the plaque.
     */
    static @Nullable PlaneHit hitPlaquePlane(Vec3 eye, Vec3 dir, Matrix4f plaqueCamRel, Vec3 cam,
                                             float minX, float minY, float maxX, float maxY, double reach) {
        Vector3f oRel = plaqueCamRel.transformPosition(0f, 0f, 0f, new Vector3f());
        Vector3f uVec = plaqueCamRel.transformDirection(1f, 0f, 0f, new Vector3f());
        Vector3f vVec = plaqueCamRel.transformDirection(0f, 1f, 0f, new Vector3f());
        Vec3 origin = new Vec3(oRel.x + cam.x, oRel.y + cam.y, oRel.z + cam.z);
        Vec3 u = new Vec3(uVec.x, uVec.y, uVec.z);
        Vec3 v = new Vec3(vVec.x, vVec.y, vVec.z);
        double uLenSq = u.lengthSqr();
        double vLenSq = v.lengthSqr();
        if (uLenSq < 1e-8 || vLenSq < 1e-8) return null;
        Vec3 normal = u.cross(v);
        double denom = dir.dot(normal);
        if (denom >= -1e-6) return null; // Back-face or parallel
        double t = origin.subtract(eye).dot(normal) / denom;
        if (!(t > 0.05) || t > reach) return null;
        Vec3 offset = eye.add(dir.scale(t)).subtract(origin);
        float lx = (float) (offset.dot(u) / uLenSq);
        float ly = (float) (offset.dot(v) / vLenSq);
        if (lx < minX || lx > maxX || ly < minY || ly > maxY) return null;
        return new PlaneHit(t, lx, ly);
    }

    // ------------------------------------------------------------------ idle sculpture plaques

    static void collect(ReplayFrame f, ReplayBoardSite s, Phase phase, float localScale, double fade) {
        if (fade < 0.35) return;
        float k = localScale * s.scale();
        var font = f.mc.font;
        Component playLabel = playLabel(phase);
        Component exportLabel = ReplayBoardTexts.tr("button.export");
        float ts = 0.0090f * k;
        float labelW = Math.max(font.width(playLabel), font.width(exportLabel)) * ts;
        float hw = (labelW + 0.42f * k) * 0.5f;
        float hh = 0.125f * k;
        float gap = 0.10f * k;

        Vector3f anchor = s.board().transformPosition(0f, BASE_TOP + 0.30f * localScale, 0f, new Vector3f());
        float horiz = (float) Math.sqrt(anchor.x * anchor.x + anchor.z * anchor.z);
        float pull = Math.min(0.95f * k, Math.max(0f, horiz - 1.0f));
        if (horiz > 1.0e-3f) {
            anchor.add(-anchor.x / horiz * pull, 0f, -anchor.z / horiz * pull);
        }

        var player = f.mc.player;
        boolean aiming = player != null && !player.isSpectator() && f.mc.screen == null && f.mc.mouseHandler.isMouseGrabbed();
        double reach = 6.0 * Math.max(1.0f, s.scale()) + 2.0;
        float offsetX = hw + gap * 0.5f;

        boolean playEnabled = phase == Phase.READY && ClientPlayNetworking.canSend(ReplayPackets.StartPlayback.TYPE);
        boolean exportEnabled = ClientReplayBoard.replayLoaded() && ClientReplayBoard.mapAvailable();
        List<Plaque> plaques = new ArrayList<>(2);
        PLAQUES.put(s.pos(), plaques);

        for (int side = -1; side <= 1; side += 2) {
            boolean play = side < 0;
            Vector3f center = new Vector3f(anchor).add(f.right.x * offsetX * side, f.right.y * offsetX * side,
                    f.right.z * offsetX * side);
            boolean enabled = play ? playEnabled : exportEnabled;
            Target target = new Target(play ? Action.PLAY : Action.EXPORT, s.pos(), enabled,
                    play ? playReason(phase) : exportReason(phase));

            if (aiming) {
                Vec3 absolute = new Vec3(center.x + frameCam.x, center.y + frameCam.y, center.z + frameCam.z);
                Vec3 right = new Vec3(f.right.x, f.right.y, f.right.z), up = new Vec3(f.up.x, f.up.y, f.up.z);
                double t = hitDistance(frameEye, frameDir, absolute, right, up, hw + HIT_PAD * k, hh + HIT_PAD * k, reach);
                if (t > 0 && !occluded(f, t) && (hoveredDistance < 0 || t < hoveredDistance)) {
                    hovered = target;
                    hoveredDeckSurface = null;
                    hoveredDeckWidget = null;
                    hoveredDistance = t;
                    hoveredStamp = System.nanoTime();
                }
            }
            Matrix4f sign = new Matrix4f().translate(center.x, center.y, center.z).rotate(f.rotation);
            plaques.add(new Plaque(target, sign, hw, hh, k, ts, phase, play ? playLabel : exportLabel, fade));
        }
    }

    static void paint(ReplayFrame frame, ReplayBoardSite site) {
        for (var plaque : PLAQUES.getOrDefault(site.pos(), List.of())) {
            var target = plaque.target();
            boolean selected = target == hovered;
            drawButton(frame, plaque.sign(), plaque.halfWidth(), plaque.halfHeight(), plaque.scale(), plaque.textScale(),
                    target.action() == Action.PLAY, target.enabled(), selected, plaque.phase(), plaque.label(), plaque.fade());
            if (selected) drawHint(frame, plaque.sign(), plaque.halfHeight(), plaque.scale(), plaque.textScale(),
                    target.enabled(), target.reason(), plaque.fade());
        }
    }

    // ------------------------------------------------------------------ Side-Edge Deck Console (active session)

    /**
     * Collects and raycasts the interactive Art Deco Side-Edge Deck Console mounted along every
     * camera-facing {@code -20°} edge plaque of an active replay table.
     */
    static void collectDeck(ReplayFrame f, ReplayBoardSite s, ReplayBoardModel model,
                            ReplayBoardSessionState session, double displayTick, boolean finishedHold) {
        double alpha = s.fade() * (1.0 - ReplayStagePlan.smoothstep(0, 0.58, f.handoff.idlePlaque()));
        if (alpha < 0.20 || model == null || session == null || !session.active() || session.preparing()) return;

        var player = f.mc.player;
        boolean aiming = player != null && !player.isSpectator() && f.mc.screen == null && f.mc.mouseHandler.isMouseGrabbed();
        double reach = 7.5 * Math.max(1.0f, s.scale()) + 2.5;
        boolean canControl = ClientPlayNetworking.canSend(ReplayPackets.BoardSessionControlC2S.TYPE);
        boolean canExport = ClientReplayBoard.mapAvailable() || session.shared();

        List<DeckWidget> widgets = buildDeckWidgets(model, session, finishedHold, canControl, canExport);
        List<DeckSurface> surfaces = new ArrayList<>(2);
        DECKS.put(s.pos(), surfaces);

        int duration = Math.max(1, model.replay.durationTicks());
        for (int side = 0; side < 4; side++) {
            Matrix4f m = ReplayTrayPainter.plaqueMatrix(s, side);
            if (!ReplayTrayPainter.plaqueFacesCamera(m)) continue;
            float pScale = ReplayTrayPainter.plaqueScale((side & 1) == 0 ? s.hw() : s.hd());
            float wScale = s.scale() * pScale;
            DeckSurface surface = new DeckSurface(s.pos(), m, wScale, alpha, model, session,
                    displayTick, finishedHold, widgets);
            surfaces.add(surface);

            if (!aiming) continue;
            // Test entire combined plaque + cantilevered lower console region in one ray-plane hit!
            PlaneHit hit = hitPlaquePlane(frameEye, frameDir, m, frameCam,
                    DECK_X0 - 0.03f, DECK_Y0 - 0.03f, DECK_X1 + 0.03f, ReplayTrayPainter.PLAQUE_H * 0.5f, reach);
            if (hit == null || occluded(f, hit.distance())) continue;
            if (hoveredDistance >= 0 && hit.distance() >= hoveredDistance) continue;

            // 1. Check full-length interactive timeline rail inside upper plaque
            if (hit.localX() >= TIMELINE_X0 - 0.03f && hit.localX() <= TIMELINE_X1 + 0.03f
                    && hit.localY() >= TIMELINE_Y0 - 0.02f && hit.localY() <= TIMELINE_Y1 + 0.02f) {
                float frac = Mth.clamp((hit.localX() - TIMELINE_X0) / (TIMELINE_X1 - TIMELINE_X0), 0f, 1f);
                double targetTick = frac * duration;
                hovered = new Target(Action.TIMELINE, s.pos(), canControl,
                        ReplayBoardTexts.tr("deck.timeline_hint"), targetTick, frac, null, false);
                hoveredDeckSurface = surface;
                hoveredDeckWidget = null;
                hoveredDistance = hit.distance();
                hoveredStamp = System.nanoTime();
                continue;
            }

            // 2. Check lower cantilevered Art Deco control buttons
            for (DeckWidget w : widgets) {
                if (hit.localX() >= w.x0() - 0.01f && hit.localX() <= w.x1() + 0.01f
                        && hit.localY() >= w.y0() - 0.01f && hit.localY() <= w.y1() + 0.01f) {
                    hovered = new Target(w.action(), s.pos(), w.enabled(), w.hint(),
                            0, 0, w.nextFollow(), w.nextFlag());
                    hoveredDeckSurface = surface;
                    hoveredDeckWidget = w;
                    hoveredDistance = hit.distance();
                    hoveredStamp = System.nanoTime();
                    break;
                }
            }
        }
    }

    private static List<DeckWidget> buildDeckWidgets(ReplayBoardModel model, ReplayBoardSessionState session,
                                                     boolean finishedHold, boolean canControl, boolean canExport) {
        List<DeckWidget> out = new ArrayList<>(15);
        float rowAY0 = -0.365f, rowAY1 = -0.220f;
        float rowBY0 = -0.535f, rowBY1 = -0.390f;

        boolean pausedOrEnd = session.paused() || finishedHold;
        out.add(new DeckWidget(Action.STEP_BACK, -1.25f, rowAY0, -0.96f, rowAY1,
                Component.literal("⏮ -10s"), ReplayBoardTexts.tr("deck.step_back"),
                false, false, false, false, canControl, null, null, false));
        out.add(new DeckWidget(Action.TOGGLE_PAUSE, -0.94f, rowAY0, -0.62f, rowAY1,
                pausedOrEnd ? Component.literal("▶ ").append(ReplayBoardTexts.tr("deck.play"))
                        : Component.literal("⏸ ").append(ReplayBoardTexts.tr("deck.pause")),
                ReplayBoardTexts.tr(pausedOrEnd ? "deck.play" : "deck.pause"),
                !pausedOrEnd, false, false, true, canControl, null, null, finishedHold));
        out.add(new DeckWidget(Action.STEP_FORWARD, -0.60f, rowAY0, -0.31f, rowAY1,
                Component.literal("+10s ⏭"), ReplayBoardTexts.tr("deck.step_forward"),
                false, false, false, false, canControl, null, null, false));
        out.add(new DeckWidget(Action.END_SESSION, -0.29f, rowAY0, 0.02f, rowAY1,
                Component.literal("⏹ ").append(ReplayBoardTexts.tr("deck.end")),
                ReplayBoardTexts.tr("deck.end_tip"),
                false, true, false, false, canControl, null, null, false));

        float curSpeed = session.speed();
        out.add(new DeckWidget(Action.SPEED_0_5, 0.08f, rowAY0, 0.34f, rowAY1,
                Component.literal("0.5x"), ReplayBoardTexts.tr("deck.speed_tip", "0.5x"),
                Math.abs(curSpeed - 0.5f) < 0.05f, false, true, false, canControl, null, null, false));
        out.add(new DeckWidget(Action.SPEED_1_0, 0.36f, rowAY0, 0.62f, rowAY1,
                Component.literal("1x"), ReplayBoardTexts.tr("deck.speed_tip", "1x"),
                Math.abs(curSpeed - 1.0f) < 0.05f, false, true, false, canControl, null, null, false));
        out.add(new DeckWidget(Action.SPEED_2_0, 0.64f, rowAY0, 0.90f, rowAY1,
                Component.literal("2x"), ReplayBoardTexts.tr("deck.speed_tip", "2x"),
                Math.abs(curSpeed - 2.0f) < 0.05f, false, true, false, canControl, null, null, false));
        out.add(new DeckWidget(Action.SPEED_4_0, 0.92f, rowAY0, 1.25f, rowAY1,
                Component.literal("4x"), ReplayBoardTexts.tr("deck.speed_tip", "4x"),
                Math.abs(curSpeed - 4.0f) < 0.05f, false, true, false, canControl, null, null, false));

        // Row B: Perspective follow, toggles, export
        UUID follow = session.followPlayerUuid();
        ReplayBoardModel.Actor followedActor = model.participant(follow);
        boolean hasActors = model.actors.length > 0;
        UUID prevFollow = cycleFollow(model, follow, -1);
        UUID nextFollow = cycleFollow(model, follow, 1);

        out.add(new DeckWidget(Action.VIEW_GLOBAL, -1.25f, rowBY0, -0.88f, rowBY1,
                ReplayBoardTexts.tr("deck.global"), ReplayBoardTexts.tr("deck.follow_tip"),
                followedActor == null, false, true, false, canControl, null, null, false));
        out.add(new DeckWidget(Action.FOLLOW_PREV, -0.86f, rowBY0, -0.74f, rowBY1,
                Component.literal("‹"), ReplayBoardTexts.tr("deck.follow_prev"),
                false, false, false, false, canControl && hasActors, null, prevFollow, false));
        Component followLabel = followedActor != null
                ? ReplayBoardTexts.tr("deck.follow_prefix", followedActor.name)
                : ReplayBoardTexts.tr("deck.roster");
        out.add(new DeckWidget(Action.OPEN_ROSTER, -0.72f, rowBY0, 0.04f, rowBY1,
                followLabel, ReplayBoardTexts.tr("deck.follow_tip"),
                followedActor != null, false, false, followedActor != null, true,
                followedActor == null ? null : followedActor.p.id(), null, false));
        out.add(new DeckWidget(Action.FOLLOW_NEXT, 0.06f, rowBY0, 0.18f, rowBY1,
                Component.literal("›"), ReplayBoardTexts.tr("deck.follow_next"),
                false, false, false, false, canControl && hasActors, null, nextFollow, false));

        boolean closeup = session.killCloseupEnabled();
        out.add(new DeckWidget(Action.TOGGLE_CLOSEUP, 0.24f, rowBY0, 0.58f, rowBY1,
                ReplayBoardTexts.tr(closeup ? "deck.closeup_on" : "deck.closeup_off"),
                ReplayBoardTexts.tr("deck.closeup_tip"),
                closeup, false, closeup, false, canControl, null, null, !closeup));

        boolean roofCut = session.roofCutaway();
        out.add(new DeckWidget(Action.TOGGLE_ROOF, 0.60f, rowBY0, 0.92f, rowBY1,
                ReplayBoardTexts.tr(roofCut ? "deck.roof_cut" : "deck.roof_full"),
                ReplayBoardTexts.tr("deck.roof_tip"),
                roofCut, false, roofCut, false, canControl, null, null, !roofCut));

        out.add(new DeckWidget(Action.EXPORT, 0.94f, rowBY0, 1.25f, rowBY1,
                ReplayBoardTexts.tr("deck.export"), ReplayBoardTexts.tr("button.export"),
                false, false, true, false, canExport, null, null, false));
        return out;
    }

    private static @Nullable UUID cycleFollow(ReplayBoardModel model, @Nullable UUID current, int dir) {
        if (model.actors.length == 0) return null;
        if (current == null) return dir >= 0 ? model.actors[0].p.id() : model.actors[model.actors.length - 1].p.id();
        int idx = -1;
        for (int i = 0; i < model.actors.length; i++) {
            if (model.actors[i].p.id().equals(current)) { idx = i; break; }
        }
        if (idx < 0) return model.actors[0].p.id();
        int next = (idx + dir + model.actors.length) % model.actors.length;
        return model.actors[next].p.id();
    }

    static boolean hasActiveDeck(BlockPos pos) {
        List<DeckSurface> surfaces = DECKS.get(pos);
        return surfaces != null && !surfaces.isEmpty();
    }

    /** Paints the interactive Side-Edge Deck Console and interactive timeline onto facing plaques. */
    static void paintDeck(ReplayFrame f, ReplayBoardSite s) {
        List<DeckSurface> surfaces = DECKS.get(s.pos());
        if (surfaces == null || surfaces.isEmpty()) return;
        VertexConsumer panel = f.buffers.getBuffer(ReplayDraw.PANEL);
        VertexConsumer glow = f.glow();

        for (DeckSurface surf : surfaces) {
            Matrix4f m = surf.matrix();
            double a = surf.fade();
            float ws = surf.worldScale();
            boolean hoverThisSurface = hoveredDeckSurface == surf && hovered != null;

            // 1. Upper plaque interior: title + live clock + full-width interactive timeline
            paintUpperTimelinePlaque(f, panel, panel, glow, surf, hoverThisSurface);

            // 2. Lower cantilevered Art Deco console apron (depth-writing night-blue chamfered plate)
            float cw = (DECK_X1 - DECK_X0) * 0.5f;
            float ch = (DECK_Y1 - DECK_Y0) * 0.5f;
            float cy = (DECK_Y0 + DECK_Y1) * 0.5f;
            Matrix4f deckM = new Matrix4f(m).translate(0f, cy, 0.002f);
            chamferPanel(panel, deckM, cw, ch, 0.038f, 0f,
                    ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.94 * a),
                    ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.97 * a));
            chamferOutline(glow, deckM, cw, ch, 0.038f, 0.002f, 0.007f * ws,
                    ReplayPalette.alpha(ReplayPalette.BRASS, 0.88 * a));
            // Vertical Art Deco section dividers
            int divCol = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.70 * a);
            ReplayDraw.segment(glow, m, 0.05f, -0.355f, 0.005f, 0.05f, -0.230f, 0.005f, 0.005f * ws, divCol, divCol);
            ReplayDraw.segment(glow, m, 0.21f, -0.525f, 0.005f, 0.21f, -0.400f, 0.005f, 0.005f * ws, divCol, divCol);

            // 3. Render every interactive Art Deco button pill into the same depth-writing PANEL pass
            for (DeckWidget w : surf.widgets()) {
                boolean isHover = hoverThisSurface && hoveredDeckWidget == w;
                paintDeckWidget(f, panel, glow, m, w, isHover, ws, a);
            }

            // 4. Floating hover tooltip below the console (or above the timeline bar)
            if (hoverThisSurface) {
                if (hovered.action() == Action.TIMELINE) {
                    paintTimelineHoverTooltip(f, panel, glow, surf, hovered.hoverFraction(), hovered.seekTick());
                } else if (hoveredDeckWidget != null) {
                    paintDeckTooltip(f, panel, glow, m, hoveredDeckWidget.hint(), ws, a);
                }
            }
        }
    }

    private static void paintUpperTimelinePlaque(ReplayFrame f, VertexConsumer panel, VertexConsumer fill,
                                                 VertexConsumer glow, DeckSurface surf, boolean hoverThisSurface) {
        Matrix4f m = surf.matrix();
        double a = surf.fade();
        float ws = surf.worldScale();
        ReplayBoardModel model = surf.model();
        ReplayBoardSessionState session = surf.session();
        int duration = Math.max(1, model.replay.durationTicks());
        double progress = Mth.clamp(surf.displayTick() / duration, 0.0, 1.0);

        float pw = ReplayTrayPainter.BASE_PLAQUE_W;
        float ph = ReplayTrayPainter.PLAQUE_H;
        // Dark starry backdrop inside the brass plaque bezel
        ReplayDraw.rectXY(panel, m, -pw / 2 + 0.035f, -ph / 2 + 0.035f, pw / 2 - 0.035f, ph / 2 - 0.035f, 0.002f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.96 * a));

        // Top header row (y = +0.105): Map Name | Live Clock + Speed | Status / Audio POV
        float zText = 0.008f;
        f.text(m, Component.literal(model.replay.mapName()), -1.22f, 0.105f, zText, 0.0048f,
                ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), -1, true, 0.82f);

        String clockStr = ReplayStagePlan.clock(surf.displayTick()) + " / " + ReplayStagePlan.clock(duration)
                + "  [" + ReplayBoardTexts.speedLabel(session.speed()) + "]";
        Component clockComp = ReplayVideoCanvas.font(Component.literal(clockStr), ReplayVideoCanvas.UI);
        f.text(m, clockComp, 0f, 0.105f, zText, 0.0048f,
                ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, a), 0, true, 0.95f);

        Component rightBadge;
        int badgeColor;
        ReplayBoardModel.Actor followed = model.participant(session.followPlayerUuid());
        if (surf.finishedHold()) {
            rightBadge = ReplayBoardTexts.tr("phase.review");
            badgeColor = ReplayPalette.BRASS_LIGHT;
        } else if (session.paused()) {
            rightBadge = ReplayBoardTexts.tr("deck.paused_badge");
            badgeColor = ReplayPalette.LAMP;
        } else if (followed != null) {
            rightBadge = ReplayBoardTexts.tr("deck.audio_filtered", followed.name);
            badgeColor = ReplayPalette.PASSENGER;
        } else {
            rightBadge = model.modeLabel;
            badgeColor = ReplayPalette.TEXT_MUTED;
        }
        f.text(m, rightBadge, 1.22f, 0.105f, zText, 0.0042f,
                ReplayPalette.alpha(badgeColor, a), 1, false, 0.78f);

        // Full-length interactive Art Deco timeline rail
        float barY = -0.065f;
        float barHalfH = hoverThisSurface && hovered.action() == Action.TIMELINE ? 0.032f : 0.024f;
        float x0 = TIMELINE_X0, x1 = TIMELINE_X1;
        float spanW = x1 - x0;

        // Recessed track bed
        ReplayDraw.rectXY(fill, m, x0, barY - barHalfH, x1, barY + barHalfH, 0.004f,
                ReplayPalette.alpha(0xFF060A16, 0.95 * a), ReplayPalette.alpha(0xFF0C1428, 0.95 * a));
        int railFrame = ReplayPalette.alpha(
                hoverThisSurface && hovered.action() == Action.TIMELINE ? ReplayPalette.BRASS_LIGHT : ReplayPalette.BRASS_DIM,
                0.85 * a);
        ReplayDraw.segment(glow, m, x0, barY + barHalfH, 0.005f, x1, barY + barHalfH, 0.005f, 0.0045f * ws, railFrame, railFrame);
        ReplayDraw.segment(glow, m, x0, barY - barHalfH, 0.005f, x1, barY - barHalfH, 0.005f, 0.0045f * ws, railFrame, railFrame);

        // Blackout blue-violet spans along the rail
        for (LastMatchReplay.Span span : model.sortedSpans) {
            if (span.kind() != LastMatchReplay.SpanKind.BLACKOUT) continue;
            float sx = x0 + spanW * Mth.clamp((float) span.startTick() / duration, 0f, 1f);
            float ex = x0 + spanW * Mth.clamp((float) span.endTick() / duration, 0f, 1f);
            if (ex - sx < 0.006f) ex = Math.min(x1, sx + 0.006f);
            ReplayDraw.rectXY(fill, m, sx, barY - barHalfH + 0.003f, ex, barY + barHalfH - 0.003f, 0.005f,
                    ReplayPalette.alpha(0xFF5674D6, 0.72 * a), ReplayPalette.alpha(0xFF3E55A8, 0.72 * a));
        }

        // Filled progress rail (brass gold gradient)
        float px = x0 + (float) (spanW * progress);
        if (px > x0 + 0.004f) {
            ReplayDraw.quad(glow, m,
                    x0, barY - barHalfH * 0.70f, 0.006f,
                    px, barY - barHalfH * 0.70f, 0.006f,
                    px, barY + barHalfH * 0.70f, 0.006f,
                    x0, barY + barHalfH * 0.70f, 0.006f,
                    ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.90 * a),
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.95 * a),
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.95 * a),
                    ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.90 * a));
        }

        // Kill ruby diamond markers & explosion amber diamonds (pre-filtered highlight array)
        for (ReplayBoardModel.EventMark mark : model.timelineHighlights) {
            LastMatchReplay.Kind kind = mark.node.kind();
            float ex = x0 + spanW * Mth.clamp((float) mark.node.tick() / duration, 0f, 1f);
            boolean past = mark.node.tick() <= surf.displayTick();
            boolean isKill = kind == LastMatchReplay.Kind.KILL;
            float r = isKill ? (past ? 0.036f : 0.028f) : 0.022f;
            int col = ReplayPalette.alpha(isKill ? ReplayPalette.RUBY : ReplayPalette.EXPLOSION, (past ? 0.98 : 0.62) * a);
            drawDiamond(glow, m, ex, barY, 0.008f, r, col);
        }

        // Current playhead diamond + luminous halo
        drawDiamond(glow, m, px, barY, 0.010f, 0.044f, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a));
        drawDiamond(glow, m, px, barY, 0.009f, 0.064f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.40 * a));

        // Hover cursor ghost marker on the timeline
        if (hoverThisSurface && hovered.action() == Action.TIMELINE) {
            float hx = x0 + spanW * hovered.hoverFraction();
            int curCol = ReplayPalette.alpha(CYAN_CORE, 0.92 * a);
            ReplayDraw.segment(glow, m, hx, barY - 0.055f, 0.010f, hx, barY + 0.055f, 0.010f, 0.006f * ws, curCol, curCol);
        }

        // Finished-hold prompt line tucked neatly below rail
        if (surf.finishedHold()) {
            f.text(m, ReplayBoardTexts.tr("deck.review_frozen"), 0f, -0.140f, zText, 0.0033f,
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.92 * a), 0, false, 2.30f);
        }
    }

    private static void paintDeckWidget(ReplayFrame f, VertexConsumer fill, VertexConsumer glow,
                                        Matrix4f m, DeckWidget w, boolean hover, float ws, double a) {
        float hw = (w.x1() - w.x0()) * 0.5f;
        float hh = (w.y1() - w.y0()) * 0.5f;
        float cx = (w.x0() + w.x1()) * 0.5f;
        float cy = (w.y0() + w.y1()) * 0.5f;
        float c = Math.min(0.022f, Math.min(hw, hh) * 0.45f);
        Matrix4f wm = new Matrix4f(m).translate(cx, cy, 0.004f);

        int topCol, botCol, borderCol, textCol;
        if (!w.enabled()) {
            topCol = ReplayPalette.alpha(0xFF0C1222, 0.85 * a);
            botCol = ReplayPalette.alpha(0xFF070A14, 0.88 * a);
            borderCol = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.40 * a);
            textCol = ReplayPalette.alpha(ReplayPalette.TEXT_FAINT, a);
        } else if (w.accentRuby()) {
            topCol = ReplayPalette.alpha(hover ? 0xFF6B242E : 0xFF4B1922, 0.95 * a);
            botCol = ReplayPalette.alpha(0xFF280D12, 0.96 * a);
            borderCol = ReplayPalette.alpha(hover ? RUBY_CORE : ReplayPalette.RUBY, a);
            textCol = ReplayPalette.alpha(0xFFFFE6E8, a);
        } else if (w.selected() && w.accentCyan()) {
            topCol = ReplayPalette.alpha(hover ? 0xFF1D4D74 : 0xFF143B5C, 0.95 * a);
            botCol = ReplayPalette.alpha(0xFF0A2036, 0.96 * a);
            borderCol = ReplayPalette.alpha(hover ? CYAN_CORE : CYAN_HALO, a);
            textCol = ReplayPalette.alpha(0xFFE8F8FF, a);
        } else if (w.selected()) {
            topCol = ReplayPalette.alpha(hover ? 0xFF56431D : 0xFF3E3015, 0.95 * a);
            botCol = ReplayPalette.alpha(0xFF1E1609, 0.96 * a);
            borderCol = ReplayPalette.alpha(hover ? ReplayPalette.CHAMPAGNE : ReplayPalette.BRASS_LIGHT, a);
            textCol = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a);
        } else if (hover) {
            int tint = w.accentGold() ? GOLD_HALO : w.accentCyan() ? CYAN_HALO : ReplayPalette.BRASS;
            topCol = ReplayPalette.alpha(ReplayPalette.lerp(ReplayPalette.NIGHT_3, tint, 0.28), 0.96 * a);
            botCol = ReplayPalette.alpha(ReplayPalette.NIGHT_1, 0.96 * a);
            borderCol = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a);
            textCol = ReplayPalette.alpha(0xFFFFFFFF, a);
        } else {
            topCol = ReplayPalette.alpha(0xFF17223D, 0.90 * a);
            botCol = ReplayPalette.alpha(0xFF0B1224, 0.93 * a);
            borderCol = ReplayPalette.alpha(w.accentGold() ? ReplayPalette.BRASS : ReplayPalette.BRASS_DIM, 0.80 * a);
            textCol = ReplayPalette.alpha(w.accentGold() ? ReplayPalette.BRASS_LIGHT : ReplayPalette.TEXT, a);
        }

        chamferPanel(fill, wm, hw, hh, c, 0f, topCol, botCol);
        chamferOutline(glow, wm, hw, hh, c, 0.002f, (hover ? 0.0065f : 0.0045f) * ws, borderCol);

        float textScale = 0.0045f;
        if (w.avatarPlayer() != null) {
            float faceSize = Math.min(hh * 1.45f, 0.105f);
            float faceX = -hw + faceSize * 0.5f + 0.032f;
            ReplayEventPainter.drawFeedAvatar(f, wm, w.avatarPlayer(), faceX, 0f, 0.004f, faceSize, 0xFFFFFFFF, a);
            float textLeft = faceX + faceSize * 0.5f + 0.025f;
            float avail = Math.max(0.10f, hw - textLeft - 0.025f);
            f.text(wm, w.label(), textLeft, 0f, 0.006f, textScale, textCol, -1, true, avail);
        } else {
            f.text(wm, w.label(), 0f, 0f, 0.006f, textScale, textCol, 0, true, Math.max(0.06f, hw * 2f - 0.04f));
        }
    }

    private static void paintTimelineHoverTooltip(ReplayFrame f, VertexConsumer panel, VertexConsumer glow,
                                                  DeckSurface surf, float frac, double targetTick) {
        Matrix4f m = surf.matrix();
        double a = surf.fade();
        float ws = surf.worldScale();
        float hx = Mth.lerp(frac, TIMELINE_X0, TIMELINE_X1);
        int duration = Math.max(1, surf.model().replay.durationTicks());

        Component label = Component.literal(ReplayStagePlan.clock(targetTick));
        // Find nearest kill/major highlight within 3.5% of the bar via O(log K) binary search
        double bestDist = Math.max(30.0, duration * 0.035);
        LastMatchReplay.Node nearest = null;
        ReplayBoardModel.EventMark[] highlights = surf.model().timelineHighlights;
        if (highlights.length > 0) {
            int lo = 0, hi = highlights.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (highlights[mid].node.tick() < targetTick) lo = mid + 1;
                else hi = mid;
            }
            if (lo > 0) {
                LastMatchReplay.Node left = highlights[lo - 1].node;
                double d = Math.abs(left.tick() - targetTick);
                if (d < bestDist) { bestDist = d; nearest = left; }
            }
            if (lo < highlights.length) {
                LastMatchReplay.Node right = highlights[lo].node;
                double d = Math.abs(right.tick() - targetTick);
                if (d < bestDist) { nearest = right; }
            }
        }
        if (nearest != null) {
            Component actor = nearest.actor() == null ? ReplayBoardTexts.tr("environment")
                    : ReplayBoardTexts.name(surf.model().participantData(nearest.actor()));
            Component victim = ReplayBoardTexts.name(surf.model().participantData(nearest.target()));
            label = nearest.kind() == LastMatchReplay.Kind.KILL
                    ? Component.literal(ReplayStagePlan.clock(nearest.tick()) + " · ")
                        .append(actor).append(" → ").append(victim)
                    : Component.literal(ReplayStagePlan.clock(nearest.tick()) + " · ")
                        .append(ReplayBoardTexts.tr("brief.explosion", actor));
        }

        float ts = 0.0044f;
        float halfW = Math.min(1.15f, Math.max(0.24f, f.mc.font.width(label) * ts * 0.5f + 0.07f));
        float halfH = 0.056f;
        float clampedX = Mth.clamp(hx, -1.25f + halfW, 1.25f - halfW);
        Matrix4f tm = new Matrix4f(m).translate(clampedX, 0.255f, 0.012f);
        chamferPanel(panel, tm, halfW, halfH, 0.020f, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.97 * a));
        chamferOutline(glow, tm, halfW, halfH, 0.020f, 0.002f, 0.0055f * ws,
                ReplayPalette.alpha(nearest != null ? ReplayPalette.RUBY : ReplayPalette.BRASS_LIGHT, 0.92 * a));
        f.text(tm, label, 0f, 0f, 0.005f, ts,
                ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), 0, true, halfW * 2f - 0.08f);
    }

    private static void paintDeckTooltip(ReplayFrame f, VertexConsumer panel, VertexConsumer glow,
                                         Matrix4f m, Component hint, float ws, double a) {
        float ts = 0.0042f;
        float halfW = Math.min(1.25f, Math.max(0.28f, f.mc.font.width(hint) * ts * 0.5f + 0.08f));
        float halfH = 0.052f;
        Matrix4f tm = new Matrix4f(m).translate(0f, DECK_Y0 - 0.075f, 0.010f);
        chamferPanel(panel, tm, halfW, halfH, 0.018f, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.94 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.96 * a));
        chamferOutline(glow, tm, halfW, halfH, 0.018f, 0.002f, 0.005f * ws,
                ReplayPalette.alpha(ReplayPalette.BRASS, 0.85 * a));
        f.text(tm, hint, 0f, 0f, 0.005f, ts, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), 0, true, halfW * 2f - 0.08f);
    }

    private static boolean occluded(ReplayFrame f, double distance) {
        var player = f.mc.player;
        if (player == null || distance < 0.3) return false;
        Vec3 to = frameEye.add(frameDir.scale(distance - 0.1));
        return f.level.clip(new ClipContext(frameEye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
                .getType() != HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ idle button visuals

    private static void drawButton(ReplayFrame f, Matrix4f sign, float hw, float hh, float k, float ts,
                                   boolean play, boolean enabled, boolean hover, Phase phase, Component label, double fade) {
        VertexConsumer panel = f.buffers.getBuffer(ReplayDraw.PANEL);
        VertexConsumer glow = f.glow();
        int halo = play ? CYAN_HALO : GOLD_HALO, core = play ? CYAN_CORE : GOLD_CORE;
        float chamfer = 0.045f * k;
        float lineW = 0.009f * k;
        boolean busy = play && (phase == Phase.STARTING || phase == Phase.PLAYING);

        int top, bottom, border;
        if (!enabled && !busy) {
            top = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.80 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.84 * fade);
            border = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.55 * fade);
        } else if (hover) {
            top = ReplayPalette.alpha(ReplayPalette.lerp(ReplayPalette.NIGHT_3, halo, 0.30), 0.96 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.lerp(ReplayPalette.NIGHT_1, halo, 0.16), 0.96 * fade);
            border = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, fade);
        } else {
            top = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.92 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.94 * fade);
            border = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.88 * fade);
        }
        chamferPanel(panel, sign, hw, hh, chamfer, 0f, top, bottom);
        chamferOutline(glow, sign, hw, hh, chamfer, 0.002f, lineW, border);

        double pulse = 0.5 + 0.5 * Math.sin(ReplayIdleCubes.wrapAngleRad(f.anim * 4.0));
        if (hover && enabled) {
            float o = 0.06f * k;
            int inner = ReplayPalette.alpha(halo, (0.30 + 0.12 * pulse) * fade), outer = ReplayPalette.alpha(halo, 0);
            ReplayDraw.quad(glow, sign, -hw, hh, -0.004f, hw, hh, -0.004f, hw + o, hh + o, -0.004f, -hw - o, hh + o, -0.004f,
                    inner, inner, outer, outer);
            ReplayDraw.quad(glow, sign, hw, -hh, -0.004f, -hw, -hh, -0.004f, -hw - o, -hh - o, -0.004f, hw + o, -hh - o, -0.004f,
                    inner, inner, outer, outer);
            ReplayDraw.quad(glow, sign, -hw, -hh, -0.004f, -hw, hh, -0.004f, -hw - o, hh + o, -0.004f, -hw - o, -hh - o, -0.004f,
                    inner, inner, outer, outer);
            ReplayDraw.quad(glow, sign, hw, hh, -0.004f, hw, -hh, -0.004f, hw + o, -hh - o, -0.004f, hw + o, hh + o, -0.004f,
                    inner, inner, outer, outer);
        } else if (enabled) {
            double phaseT = ReplayIdleCubes.wrapAngleRad(f.anim * 0.9 + (play ? 0 : 1.9)) / (Math.PI * 2.0) + 0.5;
            float x = Mth.lerp((float) phaseT, -hw + chamfer, hw - chamfer - 0.06f * k);
            int sheen = ReplayPalette.alpha(core, 0.13 * Math.sin(phaseT * Math.PI) * fade);
            int none = ReplayPalette.alpha(core, 0);
            ReplayDraw.quad(glow, sign, x, -hh, 0.001f, x + 0.06f * k, -hh, 0.001f, x + 0.10f * k, hh, 0.001f, x + 0.04f * k, hh, 0.001f,
                    none, sheen, sheen, none);
        }
        if (hover && enabled && System.nanoTime() - lastTrigger < FLASH_NS) {
            int flash = ReplayPalette.alpha(0xFFFFFFFF, 0.22 * fade);
            ReplayDraw.rectXY(glow, sign, -hw + chamfer, -hh, hw - chamfer, hh, 0.001f, flash, flash);
        }

        float ix = -hw + 0.16f * k;
        int iconCol = ReplayPalette.alpha(enabled || busy ? core : ReplayPalette.TEXT_FAINT, (enabled || busy ? 0.95 : 0.55) * fade);
        int ringCol = ReplayPalette.alpha(enabled || busy ? halo : ReplayPalette.BRASS_DIM, (hover ? 0.85 : 0.55) * fade);
        ring(glow, sign, ix, 0f, 0.002f, 0.105f * k, 0.007f * k, ringCol);
        if (play) {
            if (busy) {
                float bar = 0.017f * k, bh = 0.055f * k;
                int c = ReplayPalette.alpha(core, (0.65 + 0.35 * pulse) * fade);
                ReplayDraw.rectXY(glow, sign, ix - 0.04f * k, -bh, ix - 0.04f * k + bar * 2, bh, 0.003f, c, c);
                ReplayDraw.rectXY(glow, sign, ix + 0.04f * k - bar * 2, -bh, ix + 0.04f * k, bh, 0.003f, c, c);
            } else {
                ReplayDraw.quad(glow, sign, ix - 0.040f * k, -0.068f * k, 0.003f, ix - 0.040f * k, 0.068f * k, 0.003f,
                        ix + 0.072f * k, 0f, 0.003f, ix + 0.072f * k, 0f, 0.003f, iconCol, iconCol, iconCol, iconCol);
            }
        } else {
            float w = 0.011f * k;
            ReplayDraw.segment(glow, sign, ix, 0.062f * k, 0.003f, ix, -0.030f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix - 0.042f * k, 0.002f * k, 0.003f, ix, -0.040f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix + 0.042f * k, 0.002f * k, 0.003f, ix, -0.040f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix - 0.060f * k, -0.072f * k, 0.003f, ix + 0.060f * k, -0.072f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix - 0.060f * k, -0.072f * k, 0.003f, ix - 0.060f * k, -0.040f * k, 0.003f, w, iconCol, iconCol);
            ReplayDraw.segment(glow, sign, ix + 0.060f * k, -0.072f * k, 0.003f, ix + 0.060f * k, -0.040f * k, 0.003f, w, iconCol, iconCol);
        }
        int rule = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.75 * fade);
        float rx = -hw + 0.30f * k;
        ReplayDraw.segment(glow, sign, rx, -hh + 0.035f * k, 0.002f, rx, hh - 0.035f * k, 0.002f, lineW * 0.6f, rule, rule);

        int textColor;
        if (!enabled && !busy) textColor = ReplayPalette.alpha(ReplayPalette.TEXT_FAINT, fade);
        else if (hover) textColor = ReplayPalette.alpha(0xFFFFFFFF, fade);
        else textColor = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, fade);
        float zoneCenter = ((-hw + 0.33f * k) + (hw - 0.04f * k)) * 0.5f;
        f.text(sign, label, zoneCenter, 0f, 0.005f, ts, textColor, 0, true, hw * 2f - 0.37f * k);
    }

    private static void drawHint(ReplayFrame f, Matrix4f sign, float hh, float k, float ts, boolean enabled,
                                 Component reason, double fade) {
        Component text = enabled ? ReplayBoardTexts.tr("button.hint") : reason;
        float hs = ts * 0.82f;
        float halfW = f.mc.font.width(text) * hs * 0.5f + 0.07f * k;
        float halfH = 0.060f * k;
        Matrix4f m = new Matrix4f(sign).translate(0f, -(hh + 0.075f * k + halfH), 0f);
        VertexConsumer panel = f.buffers.getBuffer(ReplayDraw.PANEL);
        chamferPanel(panel, m, halfW, halfH, 0.025f * k, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.92 * fade), ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.94 * fade));
        chamferOutline(f.glow(), m, halfW, halfH, 0.025f * k, 0.002f, 0.006f * k,
                ReplayPalette.alpha(enabled ? ReplayPalette.BRASS : ReplayPalette.RUBY, 0.70 * fade));
        f.text(m, text, 0f, 0f, 0.005f, hs, ReplayPalette.alpha(enabled ? ReplayPalette.TEXT : ReplayPalette.RUBY, fade), 0, true, 0);
    }

    // ------------------------------------------------------------------ Art Deco primitives

    private static void drawDiamond(VertexConsumer vc, Matrix4f m, float cx, float cy, float z, float r, int color) {
        ReplayDraw.quad(vc, m, cx, cy + r, z, cx + r, cy, z, cx, cy - r, z, cx - r, cy, z, color, color, color, color);
    }

    private static void chamferPanel(VertexConsumer vc, Matrix4f m, float hw, float hh, float c, float z, int top, int bottom) {
        ReplayDraw.rectXY(vc, m, -hw + c, -hh, hw - c, hh, z, top, bottom);
        int sideLow = ReplayPalette.lerp(bottom, top, c / (hh * 2)), sideHigh = ReplayPalette.lerp(bottom, top, (hh * 2 - c) / (hh * 2));
        ReplayDraw.quad(vc, m, -hw, -hh + c, z, -hw + c, -hh + c, z, -hw + c, hh - c, z, -hw, hh - c, z,
                sideLow, sideLow, sideHigh, sideHigh);
        ReplayDraw.quad(vc, m, hw - c, -hh + c, z, hw, -hh + c, z, hw, hh - c, z, hw - c, hh - c, z,
                sideLow, sideLow, sideHigh, sideHigh);
    }

    private static void chamferOutline(VertexConsumer glow, Matrix4f m, float hw, float hh, float c, float z, float width, int color) {
        ReplayDraw.segment(glow, m, hw - c, hh, z, -hw + c, hh, z, width, color, color);
        ReplayDraw.segment(glow, m, -hw + c, hh, z, -hw, hh - c, z, width, color, color);
        ReplayDraw.segment(glow, m, -hw, hh - c, z, -hw, -hh + c, z, width, color, color);
        ReplayDraw.segment(glow, m, -hw, -hh + c, z, -hw + c, -hh, z, width, color, color);
        ReplayDraw.segment(glow, m, -hw + c, -hh, z, hw - c, -hh, z, width, color, color);
        ReplayDraw.segment(glow, m, hw - c, -hh, z, hw, -hh + c, z, width, color, color);
        ReplayDraw.segment(glow, m, hw, -hh + c, z, hw, hh - c, z, width, color, color);
        ReplayDraw.segment(glow, m, hw, hh - c, z, hw - c, hh, z, width, color, color);
    }

    private static void ring(VertexConsumer glow, Matrix4f m, float cx, float cy, float z, float radius, float width, int color) {
        int segments = 18;
        for (int i = 0; i < segments; i++) {
            float a0 = Mth.TWO_PI * i / segments, a1 = Mth.TWO_PI * (i + 1) / segments;
            ReplayDraw.segment(glow, m, cx + Mth.cos(a0) * radius, cy + Mth.sin(a0) * radius, z,
                    cx + Mth.cos(a1) * radius, cy + Mth.sin(a1) * radius, z, width, color, color);
        }
    }
}
