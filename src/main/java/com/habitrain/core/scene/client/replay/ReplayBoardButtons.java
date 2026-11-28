package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.config.ReplayAudioSettings;
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
        TOGGLE_CLOSEUP, TOGGLE_ROOF, VOLUME,
        VOLUME_SLIDER, VOLUME_MUTE, VOLUME_DETAILS, VOLUME_CLOSE
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

    record DeckWidget(Action action, float x0, float y0, float x1, float y1,
                      Component label, Component hint, boolean selected, boolean accentRuby,
                      boolean accentGold, boolean accentCyan, boolean enabled,
                      @Nullable UUID avatarPlayer, @Nullable UUID nextFollow, boolean nextFlag) {}

    private record DeckSurface(BlockPos board, Matrix4f matrix, float worldScale, double fade,
                               ReplayBoardModel model, ReplayBoardSessionState session,
                               double displayTick, boolean finishedHold, List<DeckWidget> widgets,
                               boolean volumeDrawerOpen, ReplayAudioSettings audioSettings,
                               List<DeckWidget> volumeWidgets) {}

    static final float TIMELINE_X0 = -1.20f;
    static final float TIMELINE_X1 = 1.20f;
    static final float TIMELINE_Y0 = -0.155f;
    static final float TIMELINE_Y1 = 0.025f;
    static final float DECK_X0 = -1.30f;
    static final float DECK_X1 = 1.30f;
    // Seated cleanly below the upper plaque's ebony chassis bottom (-PLAQUE_H/2 - PLAQUE_EBONY_PAD = -0.215)
    // with an intentional 0.013 reveal groove bridged by brass mounting brackets (zero overlap).
    static final float DECK_Y0 = -0.585f;
    static final float DECK_Y1 = -0.228f;

    // 3D pop-out volume slider wing mounted flush to the right of the lower console apron
    static final float VOL_PANEL_X0 = 1.34f;
    static final float VOL_PANEL_X1 = 2.28f;
    static final float VOL_PANEL_Y0 = DECK_Y0;
    static final float VOL_PANEL_Y1 = DECK_Y1;
    static final float VOL_SLIDER_Y = -0.398f;
    static final float VOL_SLIDER_Y0 = -0.458f;
    static final float VOL_SLIDER_Y1 = -0.338f;
    static final float VOL_RAIL_LEFT_X0 = 1.40f;
    static final float VOL_RAIL_LEFT_X1 = 1.71f;
    static final float VOL_MUTE_CX = 1.81f;
    static final float VOL_MUTE_X0 = 1.750f;
    static final float VOL_MUTE_X1 = 1.870f;
    static final float VOL_MUTE_Y0 = -0.453f;
    static final float VOL_MUTE_Y1 = -0.343f;
    static final float VOL_RAIL_RIGHT_X0 = 1.91f;
    static final float VOL_RAIL_RIGHT_X1 = 2.22f;

    private static final int GOLD_HALO = 0xFFE89E30, GOLD_CORE = 0xFFFFE6A8;
    private static final int CYAN_HALO = 0xFF28A4FF, CYAN_CORE = 0xFFAEE8FF;
    private static final int RUBY_HALO = 0xFFE83A4E, RUBY_CORE = 0xFFFFB2BC;

    private static final long STALE_NS = 250_000_000L, DEBOUNCE_NS = 240_000_000L,
            SEEK_THROTTLE_NS = 90_000_000L, PRESS_NS = 900_000_000L, FLASH_NS = 160_000_000L,
            VOLUME_DRAG_GRACE_NS = 220_000_000L, VOLUME_CLICK_THROTTLE_NS = 140_000_000L;
    private static final double HIT_PAD = 0.02;

    private static final Map<BlockPos, Long> PRESSED = new HashMap<>();
    private static final Map<BlockPos, List<Plaque>> PLAQUES = new HashMap<>();
    private static final Map<BlockPos, List<DeckSurface>> DECKS = new HashMap<>();

    private static Vec3 frameEye = Vec3.ZERO, frameDir = new Vec3(0, 0, 1), frameCam = Vec3.ZERO;
    private static @Nullable Target hovered;
    private static @Nullable DeckSurface hoveredDeckSurface;
    private static @Nullable DeckWidget hoveredDeckWidget;
    private static double hoveredDistance = -1;
    private static long hoveredStamp, lastTrigger, lastSeekSent, lastVolumeDragNs, lastVolumeSoundNs;
    private static @Nullable BlockPos scrubPreviewBoard;
    private static double scrubPreviewTick;
    private static long scrubPreviewUntilNs;
    private static @Nullable BlockPos volumeDrawerBoard;

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
        volumeDrawerBoard = null;
        lastVolumeDragNs = 0;
        lastVolumeSoundNs = 0;
    }

    static boolean isVolumeDrawerOpen(@Nullable BlockPos pos) {
        return pos != null && pos.equals(volumeDrawerBoard);
    }

    static void setVolumeDrawerOpen(@Nullable BlockPos pos) {
        volumeDrawerBoard = pos == null ? null : pos.immutable();
    }

    static boolean toggleVolumeDrawer(BlockPos pos) {
        if (pos.equals(volumeDrawerBoard)) {
            volumeDrawerBoard = null;
            return false;
        }
        volumeDrawerBoard = pos.immutable();
        return true;
    }

    static boolean shouldTreatMuteHitAsSliderDrag(long nowNs, long lastDragNs) {
        return lastDragNs != 0L && nowNs >= lastDragNs && nowNs - lastDragNs < VOLUME_DRAG_GRACE_NS;
    }

    /**
     * Maps a plaque-local X coordinate inside the split volume slider to {@code [0, 1]} snapped to 1%:
     * left rail maps {@code 0%..50%}, right rail maps {@code 50%..100%}, and the centered mute gap maps {@code 50%}
     * so continuous drags glide smoothly across the middle.
     */
    static float volumeFromSliderX(float localX) {
        if (localX <= VOL_RAIL_LEFT_X0) return 0.0f;
        if (localX >= VOL_RAIL_RIGHT_X1) return 1.0f;
        if (localX <= VOL_RAIL_LEFT_X1) {
            double ratio = (localX - VOL_RAIL_LEFT_X0) / (double) (VOL_RAIL_LEFT_X1 - VOL_RAIL_LEFT_X0);
            return ReplayAudioSettingsOverlay.snapRatioToVolume(ratio * 0.5);
        }
        if (localX >= VOL_RAIL_RIGHT_X0) {
            double ratio = (localX - VOL_RAIL_RIGHT_X0) / (double) (VOL_RAIL_RIGHT_X1 - VOL_RAIL_RIGHT_X0);
            return ReplayAudioSettingsOverlay.snapRatioToVolume(0.5 + ratio * 0.5);
        }
        return 0.5f;
    }

    /** Maps a normalized volume {@code [0, 1]} to its visual playhead X coordinate along the split rail. */
    static float sliderXFromVolume(float volume) {
        float v = ReplayAudioSettings.clampUnit(volume, ReplayAudioSettings.DEFAULT_VOLUME);
        if (v <= 0.5f) {
            return Mth.lerp(v * 2.0f, VOL_RAIL_LEFT_X0, VOL_RAIL_LEFT_X1);
        }
        return Mth.lerp((v - 0.5f) * 2.0f, VOL_RAIL_RIGHT_X0, VOL_RAIL_RIGHT_X1);
    }

    static boolean isPointInVolumeMute(float localX, float localY) {
        return localX >= VOL_MUTE_X0 - 0.008f && localX <= VOL_MUTE_X1 + 0.008f
                && localY >= VOL_MUTE_Y0 - 0.008f && localY <= VOL_MUTE_Y1 + 0.008f;
    }

    static boolean isPointInVolumeSlider(float localX, float localY) {
        return localX >= VOL_RAIL_LEFT_X0 - 0.025f && localX <= VOL_RAIL_RIGHT_X1 + 0.025f
                && localY >= VOL_SLIDER_Y0 && localY <= VOL_SLIDER_Y1
                && !isPointInVolumeMute(localX, localY);
    }

    static List<DeckWidget> buildVolumeDrawerWidgets() {
        float btnY0 = -0.558f, btnY1 = -0.478f;
        return List.of(
                new DeckWidget(Action.VOLUME_DETAILS, 1.40f, btnY0, 1.91f, btnY1,
                        ReplayBoardTexts.tr("deck.volume_details"), ReplayBoardTexts.tr("deck.volume_details_tip"),
                        false, false, false, false, true, null, null, false),
                new DeckWidget(Action.VOLUME_CLOSE, 1.94f, btnY0, 2.22f, btnY1,
                        ReplayBoardTexts.tr("deck.volume_close"), ReplayBoardTexts.tr("deck.volume_close_tip"),
                        false, false, true, false, true, null, null, false)
        );
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

        if (target.action() == Action.VOLUME_SLIDER) {
            lastVolumeDragNs = now;
            float vol = ReplayAudioSettingsOverlay.snapRatioToVolume(target.hoverFraction());
            ReplayAudioSettings cur = ClientVisualPreferences.getReplayAudioSettings();
            ReplayAudioSettings next = cur.withMasterVolume(vol);
            boolean changed = !next.equals(cur);
            if (changed) {
                ClientVisualPreferences.setReplayAudioSettings(next);
            }
            if ((changed || now - lastVolumeSoundNs >= VOLUME_CLICK_THROTTLE_NS * 2L)
                    && now - lastVolumeSoundNs >= VOLUME_CLICK_THROTTLE_NS) {
                lastVolumeSoundNs = now;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.85f + vol * 0.55f));
            }
            return 1;
        }

        if (now - lastTrigger < DEBOUNCE_NS) return 6;
        lastTrigger = now;
        if (!target.enabled()) {
            mc.player.displayClientMessage(target.reason(), true);
            return 6;
        }
        if (target.action() != Action.VOLUME_MUTE) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
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
            case END_SESSION -> {
                if (target.board().equals(volumeDrawerBoard)) volumeDrawerBoard = null;
                ClientReplayBoard.endBoardSession(target.board());
            }
            case SPEED_0_5 -> ClientReplayBoard.setBoardSpeed(target.board(), 0.5f);
            case SPEED_1_0 -> ClientReplayBoard.setBoardSpeed(target.board(), 1.0f);
            case SPEED_2_0 -> ClientReplayBoard.setBoardSpeed(target.board(), 2.0f);
            case SPEED_4_0 -> ClientReplayBoard.setBoardSpeed(target.board(), 4.0f);
            case VIEW_GLOBAL -> ClientReplayBoard.setBoardFollow(target.board(), null);
            case FOLLOW_PREV, FOLLOW_NEXT -> ClientReplayBoard.setBoardFollow(target.board(), target.nextFollowTarget());
            case OPEN_ROSTER -> mc.setScreen(new ReplayBoardControlsScreen(mc.level, target.board()));
            case TOGGLE_CLOSEUP -> ClientReplayBoard.setBoardKillCloseup(target.board(), target.nextFlag());
            case TOGGLE_ROOF -> ClientReplayBoard.setBoardRoofCutaway(target.board(), target.nextFlag());
            case VOLUME -> {
                if (mc.player.isShiftKeyDown()) {
                    toggleMuteWithToast(mc, false);
                } else {
                    toggleVolumeDrawer(target.board());
                }
            }
            case VOLUME_MUTE -> toggleMuteWithToast(mc, true);
            case VOLUME_DETAILS -> {
                volumeDrawerBoard = null;
                mc.setScreen(ReplayBoardControlsScreen.openVolumeSettings(mc.level, target.board()));
            }
            case VOLUME_CLOSE -> volumeDrawerBoard = null;
            case TIMELINE, VOLUME_SLIDER -> {}
        }
        return 6;
    }

    private static void toggleMuteWithToast(Minecraft mc, boolean playDistinctClick) {
        ReplayAudioSettings updated = ClientVisualPreferences.toggleReplayAudioMute();
        boolean muted = updated.isEffectivelyMuted();
        if (playDistinctClick) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, muted ? 0.85f : 1.15f));
        }
        if (mc.player != null) {
            Component feedback = muted
                    ? ReplayBoardTexts.tr("audio.toast_muted")
                    : ReplayBoardTexts.tr("audio.toast_unmuted",
                            ReplayAudioSettings.toPercent(updated.masterVolume()));
            mc.player.displayClientMessage(feedback, true);
        }
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

        ReplayAudioSettings audio = ClientVisualPreferences.getReplayAudioSettings();
        boolean volumeOpen = isVolumeDrawerOpen(s.pos());
        List<DeckWidget> widgets = buildDeckWidgets(model, session, finishedHold, canControl, canExport,
                audio, volumeOpen);
        List<DeckWidget> volumeWidgets = volumeOpen ? buildVolumeDrawerWidgets() : List.of();
        List<DeckSurface> surfaces = new ArrayList<>(2);
        DECKS.put(s.pos(), surfaces);

        int duration = Math.max(1, model.replay.durationTicks());
        long now = System.nanoTime();
        Matrix4f[] sideMatrices = new Matrix4f[4];
        int bestVolumeSide = -1;
        float bestFacingScore = -1f;
        for (int side = 0; side < 4; side++) {
            Matrix4f m = ReplayTrayPainter.plaqueMatrix(s, side);
            if (!ReplayTrayPainter.plaqueFacesCamera(m)) continue;
            sideMatrices[side] = m;
            if (volumeOpen) {
                float ox = m.m30(), oy = m.m31(), oz = m.m32();
                float dist = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
                float nx = m.m20(), ny = m.m21(), nz = m.m22();
                float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                float score = dist > 1e-5f && nLen > 1e-5f ? -(nx * ox + ny * oy + nz * oz) / (dist * nLen) : 0f;
                if (score > bestFacingScore) {
                    bestFacingScore = score;
                    bestVolumeSide = side;
                }
            }
        }

        for (int side = 0; side < 4; side++) {
            Matrix4f m = sideMatrices[side];
            if (m == null) continue;
            boolean sideVolumeOpen = volumeOpen && side == bestVolumeSide;
            float pScale = ReplayTrayPainter.plaqueScale((side & 1) == 0 ? s.hw() : s.hd());
            float wScale = s.scale() * pScale;
            DeckSurface surface = new DeckSurface(s.pos(), m, wScale, alpha, model, session,
                    displayTick, finishedHold, widgets, sideVolumeOpen, audio,
                    sideVolumeOpen ? volumeWidgets : List.of());
            surfaces.add(surface);

            if (!aiming) continue;
            // Test entire combined plaque + cantilevered lower console (+ optional volume wing) in one ray-plane hit!
            float maxHitX = sideVolumeOpen ? VOL_PANEL_X1 + 0.03f : DECK_X1 + 0.03f;
            PlaneHit hit = hitPlaquePlane(frameEye, frameDir, m, frameCam,
                    DECK_X0 - 0.03f, DECK_Y0 - 0.03f, maxHitX, ReplayTrayPainter.PLAQUE_H * 0.5f, reach);
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
                hoveredStamp = now;
                continue;
            }

            // 2. Check popped-out 3D volume slider wing (centered mute icon + split 0..50% / 50..100% slider + utility pills)
            if (sideVolumeOpen && hit.localX() >= VOL_PANEL_X0 - 0.03f && hit.localX() <= VOL_PANEL_X1 + 0.03f
                    && hit.localY() >= VOL_PANEL_Y0 - 0.02f && hit.localY() <= VOL_PANEL_Y1 + 0.02f) {
                if (isPointInVolumeMute(hit.localX(), hit.localY())) {
                    if (shouldTreatMuteHitAsSliderDrag(now, lastVolumeDragNs)) {
                        hovered = new Target(Action.VOLUME_SLIDER, s.pos(), true,
                                ReplayBoardTexts.tr("deck.volume_slider_tip", 50), VOL_MUTE_CX, 0.5f, null, false);
                    } else {
                        int restoredPct = ReplayAudioSettings.toPercent(
                                audio.masterVolume() <= ReplayAudioSettings.SILENT_EPSILON
                                        ? ReplayAudioSettings.DEFAULT_VOLUME : audio.masterVolume());
                        Component muteTip = audio.isEffectivelyMuted()
                                ? ReplayBoardTexts.tr("deck.volume_unmute_tip", restoredPct)
                                : ReplayBoardTexts.tr("deck.volume_mute_tip");
                        hovered = new Target(Action.VOLUME_MUTE, s.pos(), true, muteTip);
                    }
                    hoveredDeckSurface = surface;
                    hoveredDeckWidget = null;
                    hoveredDistance = hit.distance();
                    hoveredStamp = now;
                    continue;
                }
                if (isPointInVolumeSlider(hit.localX(), hit.localY())) {
                    float vol = volumeFromSliderX(hit.localX());
                    float clampedX = Mth.clamp(hit.localX(), VOL_RAIL_LEFT_X0, VOL_RAIL_RIGHT_X1);
                    hovered = new Target(Action.VOLUME_SLIDER, s.pos(), true,
                            ReplayBoardTexts.tr("deck.volume_slider_tip", ReplayAudioSettings.toPercent(vol)),
                            clampedX, vol, null, false);
                    hoveredDeckSurface = surface;
                    hoveredDeckWidget = null;
                    hoveredDistance = hit.distance();
                    hoveredStamp = now;
                    continue;
                }
                boolean hitVolBtn = false;
                for (DeckWidget vw : volumeWidgets) {
                    if (hit.localX() >= vw.x0() - 0.01f && hit.localX() <= vw.x1() + 0.01f
                            && hit.localY() >= vw.y0() - 0.01f && hit.localY() <= vw.y1() + 0.01f) {
                        hovered = new Target(vw.action(), s.pos(), vw.enabled(), vw.hint());
                        hoveredDeckSurface = surface;
                        hoveredDeckWidget = vw;
                        hoveredDistance = hit.distance();
                        hoveredStamp = now;
                        hitVolBtn = true;
                        break;
                    }
                }
                if (hitVolBtn) continue;
            }

            // 3. Check lower cantilevered Art Deco control buttons
            for (DeckWidget w : widgets) {
                if (hit.localX() >= w.x0() - 0.01f && hit.localX() <= w.x1() + 0.01f
                        && hit.localY() >= w.y0() - 0.01f && hit.localY() <= w.y1() + 0.01f) {
                    hovered = new Target(w.action(), s.pos(), w.enabled(), w.hint(),
                            0, 0, w.nextFollow(), w.nextFlag());
                    hoveredDeckSurface = surface;
                    hoveredDeckWidget = w;
                    hoveredDistance = hit.distance();
                    hoveredStamp = now;
                    break;
                }
            }
        }
    }

    static List<DeckWidget> buildDeckWidgets(ReplayBoardModel model, ReplayBoardSessionState session,
                                             boolean finishedHold, boolean canControl, boolean canExport,
                                             ReplayAudioSettings audioSettings) {
        return buildDeckWidgets(model, session, finishedHold, canControl, canExport, audioSettings, false);
    }

    static List<DeckWidget> buildDeckWidgets(ReplayBoardModel model, ReplayBoardSessionState session,
                                             boolean finishedHold, boolean canControl, boolean canExport,
                                             ReplayAudioSettings audioSettings, boolean volumeDrawerOpen) {
        List<DeckWidget> out = new ArrayList<>(16);
        float rowAY0 = -0.393f, rowAY1 = -0.253f;
        float rowBY0 = -0.558f, rowBY1 = -0.418f;

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

        // Row B: Perspective follow | View toggles, local volume settings, export
        UUID follow = session.followPlayerUuid();
        ReplayBoardModel.Actor followedActor = model.participant(follow);
        boolean hasActors = model.actors.length > 0;
        UUID prevFollow = cycleFollow(model, follow, -1);
        UUID nextFollow = cycleFollow(model, follow, 1);

        out.add(new DeckWidget(Action.VIEW_GLOBAL, -1.25f, rowBY0, -0.92f, rowBY1,
                ReplayBoardTexts.tr("deck.global"), ReplayBoardTexts.tr("deck.follow_tip"),
                followedActor == null, false, true, false, canControl, null, null, false));
        out.add(new DeckWidget(Action.FOLLOW_PREV, -0.90f, rowBY0, -0.80f, rowBY1,
                Component.literal("‹"), ReplayBoardTexts.tr("deck.follow_prev"),
                false, false, false, false, canControl && hasActors, null, prevFollow, false));
        Component followLabel = followedActor != null
                ? ReplayBoardTexts.tr("deck.follow_prefix", followedActor.name)
                : ReplayBoardTexts.tr("deck.roster");
        out.add(new DeckWidget(Action.OPEN_ROSTER, -0.78f, rowBY0, -0.10f, rowBY1,
                followLabel, ReplayBoardTexts.tr("deck.follow_tip"),
                followedActor != null, false, false, followedActor != null, true,
                followedActor == null ? null : followedActor.p.id(), null, false));
        out.add(new DeckWidget(Action.FOLLOW_NEXT, -0.08f, rowBY0, 0.02f, rowBY1,
                Component.literal("›"), ReplayBoardTexts.tr("deck.follow_next"),
                false, false, false, false, canControl && hasActors, null, nextFollow, false));

        boolean closeup = session.killCloseupEnabled();
        out.add(new DeckWidget(Action.TOGGLE_CLOSEUP, 0.08f, rowBY0, 0.36f, rowBY1,
                ReplayBoardTexts.tr(closeup ? "deck.closeup_on" : "deck.closeup_off"),
                ReplayBoardTexts.tr("deck.closeup_tip"),
                closeup, false, closeup, false, canControl, null, null, !closeup));

        boolean roofCut = session.roofCutaway();
        out.add(new DeckWidget(Action.TOGGLE_ROOF, 0.38f, rowBY0, 0.66f, rowBY1,
                ReplayBoardTexts.tr(roofCut ? "deck.roof_cut" : "deck.roof_full"),
                ReplayBoardTexts.tr("deck.roof_tip"),
                roofCut, false, roofCut, false, canControl, null, null, !roofCut));

        out.add(new DeckWidget(Action.EXPORT, 0.68f, rowBY0, 0.93f, rowBY1,
                ReplayBoardTexts.tr("deck.export"), ReplayBoardTexts.tr("button.export"),
                false, false, true, false, canExport, null, null, false));

        ReplayAudioSettings audio = audioSettings == null ? ReplayAudioSettings.DEFAULT : audioSettings;
        boolean audioMuted = audio.isEffectivelyMuted();
        boolean audioActive = volumeDrawerOpen || (!audioMuted && !audio.isDefault());
        out.add(new DeckWidget(Action.VOLUME, 0.95f, rowBY0, 1.25f, rowBY1,
                ReplayAudioSettingsOverlay.deckButtonLabel(audio),
                ReplayAudioSettingsOverlay.buttonTooltip(audio, true),
                audioActive, audioMuted, false, audioActive, true, null, null, false));
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
        VertexConsumer ui = f.buffers.getBuffer(ReplayDraw.UI_OVERLAY);
        VertexConsumer glow = f.glow();

        for (DeckSurface surf : surfaces) {
            Matrix4f m = surf.matrix();
            double a = surf.fade();
            boolean hoverThisSurface = hoveredDeckSurface == surf && hovered != null;

            // 1. Upper plaque interior: title + live clock + full-width interactive timeline
            paintUpperTimelinePlaque(f, panel, ui, glow, surf, hoverThisSurface);

            // 2. Symmetric brass mounting brackets bridging the 0.013 reveal groove below the ebony chassis
            float chassisBottom = -(ReplayTrayPainter.PLAQUE_H * 0.5f + ReplayTrayPainter.PLAQUE_EBONY_PAD);
            int bracketTop = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.90 * a);
            int bracketBot = ReplayPalette.alpha(ReplayPalette.BRASS, 0.92 * a);
            for (int side = -1; side <= 1; side += 2) {
                float bx = side * 0.88f;
                ReplayDraw.rectXY(ui, m, bx - 0.038f, DECK_Y1 - 0.002f, bx + 0.038f, chassisBottom + 0.001f, 0.001f,
                        bracketTop, bracketBot);
            }

            // 3. Lower cantilevered Art Deco console apron (single depth-writing PANEL slab; overlays use UI_OVERLAY)
            float cw = (DECK_X1 - DECK_X0) * 0.5f;
            float ch = (DECK_Y1 - DECK_Y0) * 0.5f;
            float cy = (DECK_Y0 + DECK_Y1) * 0.5f;
            Matrix4f deckM = new Matrix4f(m).translate(0f, cy, 0.002f);
            chamferPanel(panel, deckM, cw, ch, 0.036f, 0f,
                    ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.95 * a),
                    ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.98 * a));
            // Crisp flat mitered outer brass frame + delicate recessed hairline inner trim
            chamferOutline(ui, deckM, cw, ch, 0.036f, 0.002f, 0.010f,
                    ReplayPalette.alpha(ReplayPalette.BRASS, 0.92 * a));
            chamferOutline(ui, deckM, cw - 0.015f, ch - 0.015f, 0.027f, 0.002f, 0.0035f,
                    ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.45 * a));
            // Vertical Art Deco section dividers with tiny centered brass diamond accents
            int divCol = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.72 * a);
            int divDia = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.80 * a);
            ReplayDraw.rectXY(ui, m, 0.0475f, -0.383f, 0.0525f, -0.263f, 0.005f, divCol, divCol);
            ReplayDraw.rectXY(ui, m, 0.0475f, -0.548f, 0.0525f, -0.428f, 0.005f, divCol, divCol);
            drawDiamond(ui, m, 0.05f, -0.323f, 0.0055f, 0.010f, divDia);
            drawDiamond(ui, m, 0.05f, -0.488f, 0.0055f, 0.010f, divDia);

            // 4. Render every interactive Art Deco button pill into non-depth-writing UI_OVERLAY (zero z-fighting)
            for (DeckWidget w : surf.widgets()) {
                boolean isHover = hoverThisSurface && hoveredDeckWidget == w;
                paintDeckWidget(f, ui, glow, m, w, isHover, a);
            }

            // 5. Optional popped-out 3D volume slider wing (mounted right beside the VOLUME button on primary side)
            if (surf.volumeDrawerOpen()) {
                paintVolumeDrawer(f, panel, ui, glow, surf, hoverThisSurface);
            }

            // 6. Floating hover tooltip strictly clear of the plaque and console
            if (hoverThisSurface) {
                if (hovered.action() == Action.TIMELINE) {
                    paintTimelineHoverTooltip(f, panel, ui, surf, hovered.hoverFraction(), hovered.seekTick());
                } else if (hovered.action() == Action.VOLUME_SLIDER || hovered.action() == Action.VOLUME_MUTE
                        || hovered.action() == Action.VOLUME_DETAILS || hovered.action() == Action.VOLUME_CLOSE) {
                    paintVolumeDrawerTooltip(f, panel, ui, m, hovered.reason(), a);
                } else if (hoveredDeckWidget != null) {
                    paintDeckTooltip(f, panel, ui, m, hoveredDeckWidget.hint(), a);
                }
            }
        }
    }

    private static void paintUpperTimelinePlaque(ReplayFrame f, VertexConsumer panel, VertexConsumer ui,
                                                 VertexConsumer glow, DeckSurface surf, boolean hoverThisSurface) {
        Matrix4f m = surf.matrix();
        double a = surf.fade();
        ReplayBoardModel model = surf.model();
        ReplayBoardSessionState session = surf.session();
        int duration = Math.max(1, model.replay.durationTicks());
        double progress = Mth.clamp(surf.displayTick() / duration, 0.0, 1.0);

        float innerHalfW = ReplayTrayPainter.BASE_PLAQUE_W * 0.5f - ReplayTrayPainter.PLAQUE_BEZEL;
        float innerHalfH = ReplayTrayPainter.PLAQUE_H * 0.5f - ReplayTrayPainter.PLAQUE_BEZEL;
        // Dark starry backdrop seated cleanly inside the hollow brass bezel well (full 0.018 clear of ebony back)
        ReplayDraw.rectXY(panel, m, -innerHalfW, -innerHalfH, innerHalfW, innerHalfH, -0.002f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.98 * a));
        // Delicate inner brass bezel hairline + header separator rule (UI_OVERLAY, no depth write)
        ReplayDraw.rectOutlineXY(ui, m, -innerHalfW + 0.012f, -innerHalfH + 0.012f,
                innerHalfW - 0.012f, innerHalfH - 0.012f, 0.002f, 0.0045f,
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.45 * a));
        ReplayDraw.rectXY(ui, m, TIMELINE_X0, 0.036f, TIMELINE_X1, 0.040f, 0.002f,
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.38 * a),
                ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.38 * a));

        // Top header row (y = +0.090): strictly non-overlapping left / center / right columns
        float zText = 0.013f;
        f.text(m, Component.literal(model.replay.mapName()), -1.20f, 0.090f, zText, 0.0046f,
                ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), -1, true, 0.72f);

        String clockStr = ReplayStagePlan.clock(surf.displayTick()) + " / " + ReplayStagePlan.clock(duration)
                + "  [" + ReplayBoardTexts.speedLabel(session.speed()) + "]";
        Component clockComp = ReplayVideoCanvas.font(Component.literal(clockStr), ReplayVideoCanvas.UI);
        f.text(m, clockComp, 0f, 0.090f, zText, 0.0046f,
                ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, a), 0, true, 0.84f);

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
        f.text(m, rightBadge, 1.20f, 0.090f, zText, 0.0041f,
                ReplayPalette.alpha(badgeColor, a), 1, false, 0.72f);

        // Full-length interactive Art Deco timeline rail (positioned cleanly inside inner glass)
        boolean hoverTimeline = hoverThisSurface && hovered.action() == Action.TIMELINE;
        float barY = -0.036f;
        float barHalfH = hoverTimeline ? 0.030f : 0.023f;
        float x0 = TIMELINE_X0, x1 = TIMELINE_X1;
        float spanW = x1 - x0;

        // Recessed track bed (UI_OVERLAY, no depth write)
        ReplayDraw.rectXY(ui, m, x0, barY - barHalfH, x1, barY + barHalfH, 0.003f,
                ReplayPalette.alpha(0xFF060A16, 0.96 * a), ReplayPalette.alpha(0xFF0C1428, 0.96 * a));

        // Blackout blue-violet spans along the rail (inset inside border)
        float innerPad = 0.0045f;
        for (LastMatchReplay.Span span : model.sortedSpans) {
            if (span.kind() != LastMatchReplay.SpanKind.BLACKOUT) continue;
            float sx = x0 + spanW * Mth.clamp((float) span.startTick() / duration, 0f, 1f);
            float ex = x0 + spanW * Mth.clamp((float) span.endTick() / duration, 0f, 1f);
            if (ex - sx < 0.008f) ex = Math.min(x1, sx + 0.008f);
            ReplayDraw.rectXY(ui, m, sx, barY - barHalfH + innerPad, ex, barY + barHalfH - innerPad, 0.004f,
                    ReplayPalette.alpha(0xFF5674D6, 0.75 * a), ReplayPalette.alpha(0xFF3E55A8, 0.75 * a));
        }

        // Filled progress rail: rich solid metallic brass in UI_OVERLAY + soft warm gleam in GLOW
        float px = x0 + (float) (spanW * progress);
        if (px > x0 + 0.005f) {
            float fillHalfH = Math.max(0.008f, barHalfH - innerPad);
            ReplayDraw.quad(ui, m,
                    x0 + innerPad, barY - fillHalfH, 0.005f,
                    px, barY - fillHalfH, 0.005f,
                    px, barY + fillHalfH, 0.005f,
                    x0 + innerPad, barY + fillHalfH, 0.005f,
                    ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.94 * a),
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.98 * a),
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.98 * a),
                    ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.94 * a));
            ReplayDraw.quad(glow, m,
                    x0 + innerPad, barY - fillHalfH * 0.45f, 0.006f,
                    px, barY - fillHalfH * 0.45f, 0.006f,
                    px, barY + fillHalfH * 0.45f, 0.006f,
                    x0 + innerPad, barY + fillHalfH * 0.45f, 0.006f,
                    ReplayPalette.alpha(ReplayPalette.BRASS, 0.12 * a),
                    ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.28 * a),
                    ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, 0.28 * a),
                    ReplayPalette.alpha(ReplayPalette.BRASS, 0.12 * a));
        }

        // Closed 4-sided mitered brass rail frame (flat in UI_OVERLAY — zero clipping or missing caps)
        int railFrame = ReplayPalette.alpha(
                hoverTimeline ? ReplayPalette.BRASS_LIGHT : ReplayPalette.BRASS_DIM, 0.92 * a);
        ReplayDraw.rectOutlineXY(ui, m, x0, barY - barHalfH, x1, barY + barHalfH, 0.006f, 0.0052f, railFrame);

        // Kill ruby diamond markers & explosion amber diamonds
        for (ReplayBoardModel.EventMark mark : model.timelineHighlights) {
            LastMatchReplay.Kind kind = mark.node.kind();
            float ex = x0 + spanW * Mth.clamp((float) mark.node.tick() / duration, 0f, 1f);
            boolean past = mark.node.tick() <= surf.displayTick();
            boolean isKill = kind == LastMatchReplay.Kind.KILL;
            float r = isKill ? (past ? 0.030f : 0.023f) : 0.019f;
            int rimCol = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.85 * a);
            int col = ReplayPalette.alpha(isKill ? ReplayPalette.RUBY : ReplayPalette.EXPLOSION, (past ? 0.98 : 0.68) * a);
            drawDiamond(ui, m, ex, barY, 0.0075f, r + 0.005f, rimCol);
            drawDiamond(ui, m, ex, barY, 0.0080f, r, col);
            if (isKill && past) {
                drawDiamond(glow, m, ex, barY, 0.009f, r * 1.25f, ReplayPalette.alpha(ReplayPalette.RUBY, 0.22 * a));
            }
        }

        // Current playhead diamond (strictly contained inside ±1.264 inner glass even at 0% / 100%)
        drawDiamond(ui, m, px, barY, 0.0095f, 0.038f, ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.88 * a));
        drawDiamond(ui, m, px, barY, 0.0100f, 0.032f, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a));
        drawDiamond(glow, m, px, barY, 0.0110f, 0.046f, ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.34 * a));

        // Hover cursor ghost marker on the timeline (crisp flat vertical bar)
        if (hoverTimeline) {
            float hx = x0 + spanW * hovered.hoverFraction();
            ReplayDraw.rectXY(ui, m, hx - 0.0045f, barY - 0.044f, hx + 0.0045f, barY + 0.044f, 0.0105f,
                    ReplayPalette.alpha(CYAN_CORE, 0.95 * a), ReplayPalette.alpha(CYAN_CORE, 0.95 * a));
            ReplayDraw.rectXY(glow, m, hx - 0.008f, barY - 0.046f, hx + 0.008f, barY + 0.046f, 0.0112f,
                    ReplayPalette.alpha(CYAN_HALO, 0.26 * a), ReplayPalette.alpha(CYAN_HALO, 0.26 * a));
        }

        // Finished-hold prompt line tucked neatly between timeline and bottom glass edge (-0.144)
        if (surf.finishedHold()) {
            f.text(m, ReplayBoardTexts.tr("deck.review_frozen"), 0f, -0.106f, zText, 0.0033f,
                    ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.92 * a), 0, false, 2.26f);
        }
    }

    private static void paintDeckWidget(ReplayFrame f, VertexConsumer ui, VertexConsumer glow,
                                        Matrix4f m, DeckWidget w, boolean hover, double a) {
        float hw = (w.x1() - w.x0()) * 0.5f;
        float hh = (w.y1() - w.y0()) * 0.5f;
        float cx = (w.x0() + w.x1()) * 0.5f;
        float cy = (w.y0() + w.y1()) * 0.5f;
        float c = Math.min(0.022f, Math.min(hw, hh) * 0.45f);
        Matrix4f wm = new Matrix4f(m).translate(cx, cy, 0.006f);

        int topCol, botCol, borderCol, textCol;
        if (!w.enabled()) {
            topCol = ReplayPalette.alpha(0xFF0C1222, 0.88 * a);
            botCol = ReplayPalette.alpha(0xFF070A14, 0.92 * a);
            borderCol = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.42 * a);
            textCol = ReplayPalette.alpha(ReplayPalette.TEXT_FAINT, a);
        } else if (w.accentRuby()) {
            topCol = ReplayPalette.alpha(hover ? 0xFF702631 : 0xFF4D1A23, 0.96 * a);
            botCol = ReplayPalette.alpha(0xFF280D12, 0.98 * a);
            borderCol = ReplayPalette.alpha(hover ? RUBY_CORE : ReplayPalette.RUBY, a);
            textCol = ReplayPalette.alpha(0xFFFFE6E8, a);
        } else if (w.selected() && w.accentCyan()) {
            topCol = ReplayPalette.alpha(hover ? 0xFF1E517A : 0xFF153E60, 0.96 * a);
            botCol = ReplayPalette.alpha(0xFF0A2036, 0.98 * a);
            borderCol = ReplayPalette.alpha(hover ? CYAN_CORE : CYAN_HALO, a);
            textCol = ReplayPalette.alpha(0xFFE8F8FF, a);
        } else if (w.selected()) {
            topCol = ReplayPalette.alpha(hover ? 0xFF5A461F : 0xFF403116, 0.96 * a);
            botCol = ReplayPalette.alpha(0xFF1E1609, 0.98 * a);
            borderCol = ReplayPalette.alpha(hover ? ReplayPalette.CHAMPAGNE : ReplayPalette.BRASS_LIGHT, a);
            textCol = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a);
        } else if (hover) {
            int tint = w.accentGold() ? GOLD_HALO : w.accentCyan() ? CYAN_HALO : ReplayPalette.BRASS;
            topCol = ReplayPalette.alpha(ReplayPalette.lerp(ReplayPalette.NIGHT_3, tint, 0.30), 0.97 * a);
            botCol = ReplayPalette.alpha(ReplayPalette.NIGHT_1, 0.98 * a);
            borderCol = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a);
            textCol = ReplayPalette.alpha(0xFFFFFFFF, a);
        } else {
            topCol = ReplayPalette.alpha(0xFF182442, 0.94 * a);
            botCol = ReplayPalette.alpha(0xFF0B1224, 0.96 * a);
            borderCol = ReplayPalette.alpha(w.accentGold() ? ReplayPalette.BRASS : ReplayPalette.BRASS_DIM, 0.86 * a);
            textCol = ReplayPalette.alpha(w.accentGold() ? ReplayPalette.BRASS_LIGHT : ReplayPalette.TEXT, a);
        }

        // True 8-sided chamfered octagon fill in UI_OVERLAY (zero corner holes, zero depth-write flicker)
        chamferPanel(ui, wm, hw, hh, c, 0f, topCol, botCol);
        // Subtle top-bevel specular sheen inside enabled buttons for tactile Art Deco depth
        if (w.enabled() && hw - c > 0.012f) {
            int sheen = ReplayPalette.alpha(0xFFFFFFFF, (hover ? 0.16 : 0.08) * a);
            ReplayDraw.rectXY(ui, wm, -hw + c + 0.004f, hh - 0.013f, hw - c - 0.004f, hh - 0.006f, 0.0008f, sheen, sheen);
        }
        // Crisp flat mitered octagon border
        float stroke = hover ? 0.0085f : 0.0062f;
        chamferOutline(ui, wm, hw, hh, c, 0.0015f, stroke, borderCol);
        if (hover && w.enabled()) {
            int halo = w.accentRuby() ? RUBY_HALO : w.accentCyan() ? CYAN_HALO : ReplayPalette.BRASS_LIGHT;
            ReplayDraw.chamferHaloXY(glow, wm, hw, hh, c, 0.0022f, 0.014f,
                    ReplayPalette.alpha(halo, 0.30 * a), ReplayPalette.alpha(halo, 0));
        }

        float textScale = 0.0045f;
        if (w.avatarPlayer() != null) {
            float faceSize = Math.min(hh * 1.42f, 0.100f);
            float faceX = -hw + faceSize * 0.5f + 0.030f;
            ReplayEventPainter.drawFeedAvatar(f, wm, w.avatarPlayer(), faceX, 0f, 0.004f, faceSize, 0xFFFFFFFF, a);
            float textLeft = faceX + faceSize * 0.5f + 0.025f;
            float avail = Math.max(0.10f, hw - textLeft - 0.025f);
            f.text(wm, w.label(), textLeft, 0f, 0.007f, textScale, textCol, -1, true, avail);
        } else if (w.action() == Action.VOLUME) {
            float iconX = -hw + 0.044f;
            int iconCol = w.accentRuby() ? ReplayPalette.alpha(0xFFFFD4D8, a) : textCol;
            int slashCol = ReplayPalette.alpha(RUBY_CORE, a);
            drawSpeakerIcon(ui, wm, iconX, 0f, 0.0035f, 0.021f, w.accentRuby(), iconCol, slashCol);
            float zoneLeft = iconX + 0.028f;
            float zoneRight = hw - 0.018f;
            f.text(wm, w.label(), (zoneLeft + zoneRight) * 0.5f, 0f, 0.007f, textScale, textCol, 0, true,
                    Math.max(0.08f, zoneRight - zoneLeft));
        } else {
            f.text(wm, w.label(), 0f, 0f, 0.007f, textScale, textCol, 0, true, Math.max(0.06f, hw * 2f - 0.04f));
        }
    }

    private static void paintVolumeDrawer(ReplayFrame f, VertexConsumer panel, VertexConsumer ui, VertexConsumer glow,
                                          DeckSurface surf, boolean hoverThisSurface) {
        Matrix4f m = surf.matrix();
        double a = surf.fade();
        ReplayAudioSettings audio = surf.audioSettings() == null ? ReplayAudioSettings.DEFAULT : surf.audioSettings();
        boolean muted = audio.isEffectivelyMuted();
        float vol = audio.masterVolume();

        // Crisp flat brass hinge tabs connecting the console apron's VOLUME slot to the wing
        int bridgeCol = ReplayPalette.alpha(ReplayPalette.BRASS, 0.90 * a);
        ReplayDraw.rectXY(ui, m, DECK_X1 - 0.001f, -0.459f, VOL_PANEL_X0 + 0.001f, -0.451f, 0.004f, bridgeCol, bridgeCol);
        ReplayDraw.rectXY(ui, m, DECK_X1 - 0.001f, -0.524f, VOL_PANEL_X0 + 0.001f, -0.516f, 0.004f, bridgeCol, bridgeCol);

        // Main chamfered wing plate (single depth-writing PANEL slab; inner elements use UI_OVERLAY)
        float cw = (VOL_PANEL_X1 - VOL_PANEL_X0) * 0.5f;
        float ch = (VOL_PANEL_Y1 - VOL_PANEL_Y0) * 0.5f;
        float cx = (VOL_PANEL_X0 + VOL_PANEL_X1) * 0.5f;
        float cy = (VOL_PANEL_Y0 + VOL_PANEL_Y1) * 0.5f;
        Matrix4f vm = new Matrix4f(m).translate(cx, cy, 0.002f);
        chamferPanel(panel, vm, cw, ch, 0.032f, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.98 * a));
        chamferOutline(ui, vm, cw, ch, 0.032f, 0.002f, 0.0095f,
                ReplayPalette.alpha(muted ? ReplayPalette.RUBY : ReplayPalette.BRASS_LIGHT, 0.94 * a));
        chamferOutline(ui, vm, cw - 0.015f, ch - 0.015f, 0.023f, 0.002f, 0.0035f,
                ReplayPalette.alpha(muted ? 0xFF7D3B46 : ReplayPalette.BRASS_DIM, 0.45 * a));

        // Header row: Local Volume title | Live % or Muted badge
        float headerY = -0.278f;
        f.text(m, ReplayBoardTexts.tr("deck.volume_panel_title"), VOL_RAIL_LEFT_X0, headerY, 0.013f, 0.0043f,
                ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), -1, true, 0.44f);
        Component valueComp = muted
                ? ReplayBoardTexts.tr("deck.volume_status_muted")
                : ReplayVideoCanvas.font(Component.literal(ReplayAudioSettings.toPercent(vol) + "%"), ReplayVideoCanvas.UI);
        int valueCol = muted ? ReplayPalette.RUBY : ReplayPalette.BRASS_LIGHT;
        f.text(m, valueComp, VOL_RAIL_RIGHT_X1, headerY, 0.013f, 0.0044f,
                ReplayPalette.alpha(valueCol, a), 1, true, 0.36f);

        int ruleCol = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.55 * a);
        ReplayDraw.rectXY(ui, m, VOL_RAIL_LEFT_X0, -0.322f, VOL_RAIL_RIGHT_X1, -0.318f, 0.005f, ruleCol, ruleCol);

        // Split horizontal slider track: left rail (0%..50%) + centered one-click mute medallion + right rail (50%..100%)
        boolean hoverSlider = hoverThisSurface && hovered.action() == Action.VOLUME_SLIDER;
        boolean hoverMute = hoverThisSurface && hovered.action() == Action.VOLUME_MUTE;
        float barY = VOL_SLIDER_Y;
        float barHalfH = hoverSlider ? 0.027f : 0.021f;

        int bedTop = ReplayPalette.alpha(0xFF060A16, 0.96 * a);
        int bedBot = ReplayPalette.alpha(0xFF0C1428, 0.96 * a);
        int railFrame = ReplayPalette.alpha(
                hoverSlider ? ReplayPalette.BRASS_LIGHT : (muted ? 0xFF7D3B46 : ReplayPalette.BRASS_DIM), 0.92 * a);

        // Recessed left & right rail beds (UI_OVERLAY)
        ReplayDraw.rectXY(ui, m, VOL_RAIL_LEFT_X0, barY - barHalfH, VOL_RAIL_LEFT_X1, barY + barHalfH, 0.005f, bedTop, bedBot);
        ReplayDraw.rectXY(ui, m, VOL_RAIL_RIGHT_X0, barY - barHalfH, VOL_RAIL_RIGHT_X1, barY + barHalfH, 0.005f, bedTop, bedBot);

        // Subtle flat stubs joining both rail halves cleanly to the centered mute medallion
        ReplayDraw.rectXY(ui, m, VOL_RAIL_LEFT_X1, barY - 0.003f, VOL_MUTE_X0, barY + 0.003f, 0.0055f, railFrame, railFrame);
        ReplayDraw.rectXY(ui, m, VOL_MUTE_X1, barY - 0.003f, VOL_RAIL_RIGHT_X0, barY + 0.003f, 0.0055f, railFrame, railFrame);

        // Filled progress along left half (0%..50%) and right half (50%..100%)
        int fillStart = ReplayPalette.alpha(muted ? 0xFF5A2530 : ReplayPalette.BRASS_DIM, (muted ? 0.78 : 0.94) * a);
        int fillMid = ReplayPalette.alpha(muted ? 0xFF8C3646 : ReplayPalette.BRASS_LIGHT, (muted ? 0.84 : 0.97) * a);
        int fillEnd = ReplayPalette.alpha(muted ? 0xFFAA4252 : ReplayPalette.CHAMPAGNE, (muted ? 0.86 : 0.98) * a);
        float fillInset = 0.0045f;
        float fillHalfH = Math.max(0.007f, barHalfH - fillInset);

        float leftEndX = vol >= 0.5f ? VOL_RAIL_LEFT_X1 - fillInset
                : Mth.lerp(vol * 2.0f, VOL_RAIL_LEFT_X0 + fillInset, VOL_RAIL_LEFT_X1 - fillInset);
        if (vol > 0.005f && leftEndX > VOL_RAIL_LEFT_X0 + fillInset + 0.002f) {
            ReplayDraw.quad(ui, m,
                    VOL_RAIL_LEFT_X0 + fillInset, barY - fillHalfH, 0.006f,
                    leftEndX, barY - fillHalfH, 0.006f,
                    leftEndX, barY + fillHalfH, 0.006f,
                    VOL_RAIL_LEFT_X0 + fillInset, barY + fillHalfH, 0.006f,
                    fillStart, fillMid, fillMid, fillStart);
        }
        if (vol > 0.505f) {
            float rightEndX = Mth.lerp((vol - 0.5f) * 2.0f, VOL_RAIL_RIGHT_X0 + fillInset, VOL_RAIL_RIGHT_X1 - fillInset);
            if (rightEndX > VOL_RAIL_RIGHT_X0 + fillInset + 0.002f) {
                ReplayDraw.quad(ui, m,
                        VOL_RAIL_RIGHT_X0 + fillInset, barY - fillHalfH, 0.006f,
                        rightEndX, barY - fillHalfH, 0.006f,
                        rightEndX, barY + fillHalfH, 0.006f,
                        VOL_RAIL_RIGHT_X0 + fillInset, barY + fillHalfH, 0.006f,
                        fillMid, fillEnd, fillEnd, fillMid);
            }
        }

        // Closed 4-sided mitered rail frames (zero corner blobs or missing end caps)
        ReplayDraw.rectOutlineXY(ui, m, VOL_RAIL_LEFT_X0, barY - barHalfH, VOL_RAIL_LEFT_X1, barY + barHalfH, 0.007f, 0.0048f, railFrame);
        ReplayDraw.rectOutlineXY(ui, m, VOL_RAIL_RIGHT_X0, barY - barHalfH, VOL_RAIL_RIGHT_X1, barY + barHalfH, 0.007f, 0.0048f, railFrame);

        // Playhead diamond knob (sized to stay strictly clear of mute medallion even at 50% and edges at 0%/100%)
        float thumbX = sliderXFromVolume(vol);
        drawDiamond(ui, m, thumbX, barY, 0.0085f, 0.028f, ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.88 * a));
        drawDiamond(ui, m, thumbX, barY, 0.0090f, 0.023f,
                ReplayPalette.alpha(muted ? 0xFFFFB2BC : ReplayPalette.CHAMPAGNE, a));
        drawDiamond(glow, m, thumbX, barY, 0.0100f, 0.033f,
                ReplayPalette.alpha(muted ? ReplayPalette.RUBY : ReplayPalette.BRASS_LIGHT, 0.36 * a));

        // Hover ghost preview marker on slider rail
        if (hoverSlider) {
            float hx = Mth.clamp((float) hovered.seekTick(), VOL_RAIL_LEFT_X0, VOL_RAIL_RIGHT_X1);
            if (!isPointInVolumeMute(hx, barY)) {
                ReplayDraw.rectXY(ui, m, hx - 0.004f, barY - 0.038f, hx + 0.004f, barY + 0.038f, 0.0095f,
                        ReplayPalette.alpha(CYAN_CORE, 0.94 * a), ReplayPalette.alpha(CYAN_CORE, 0.94 * a));
                ReplayDraw.rectXY(glow, m, hx - 0.007f, barY - 0.040f, hx + 0.007f, barY + 0.040f, 0.0105f,
                        ReplayPalette.alpha(CYAN_HALO, 0.25 * a), ReplayPalette.alpha(CYAN_HALO, 0.25 * a));
            }
        }

        // Centered One-Click Mute Medallion (drawn in UI_OVERLAY — zero depth-write fighting with wing or stubs)
        float mhw = (VOL_MUTE_X1 - VOL_MUTE_X0) * 0.5f;
        float mhh = (VOL_MUTE_Y1 - VOL_MUTE_Y0) * 0.5f;
        float mcChamfer = 0.022f;
        Matrix4f mm = new Matrix4f(m).translate(VOL_MUTE_CX, barY, 0.008f);
        int muteTop, muteBot, muteBorder, iconCol, slashCol;
        if (muted) {
            muteTop = ReplayPalette.alpha(hoverMute ? 0xFF7A2835 : 0xFF531A24, 0.97 * a);
            muteBot = ReplayPalette.alpha(0xFF2B0D13, 0.98 * a);
            muteBorder = ReplayPalette.alpha(hoverMute ? RUBY_CORE : ReplayPalette.RUBY, a);
            iconCol = ReplayPalette.alpha(0xFFFFE2E5, a);
            slashCol = ReplayPalette.alpha(hoverMute ? 0xFFFFFFFF : RUBY_CORE, a);
        } else if (hoverMute) {
            muteTop = ReplayPalette.alpha(0xFF224268, 0.97 * a);
            muteBot = ReplayPalette.alpha(0xFF0E1D34, 0.98 * a);
            muteBorder = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a);
            iconCol = ReplayPalette.alpha(0xFFFFFFFF, a);
            slashCol = ReplayPalette.alpha(RUBY_CORE, a);
        } else {
            muteTop = ReplayPalette.alpha(0xFF1A2847, 0.96 * a);
            muteBot = ReplayPalette.alpha(0xFF0B1224, 0.98 * a);
            muteBorder = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.92 * a);
            iconCol = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a);
            slashCol = ReplayPalette.alpha(RUBY_CORE, a);
        }
        chamferPanel(ui, mm, mhw, mhh, mcChamfer, 0f, muteTop, muteBot);
        chamferOutline(ui, mm, mhw, mhh, mcChamfer, 0.001f, hoverMute ? 0.0082f : 0.0060f, muteBorder);
        if (hoverMute) {
            int mh = muted ? RUBY_HALO : ReplayPalette.BRASS_LIGHT;
            ReplayDraw.chamferHaloXY(glow, mm, mhw, mhh, mcChamfer, 0.0015f, 0.014f,
                    ReplayPalette.alpha(mh, 0.32 * a), ReplayPalette.alpha(mh, 0));
        }
        drawSpeakerIcon(ui, mm, 0f, 0f, 0.002f, 0.032f, muted, iconCol, slashCol);

        // Bottom utility pills (Detailed Mixer | Close)
        for (DeckWidget vw : surf.volumeWidgets()) {
            boolean isHover = hoverThisSurface && hoveredDeckWidget == vw;
            paintDeckWidget(f, ui, glow, m, vw, isHover, a);
        }
    }

    private static void paintVolumeDrawerTooltip(ReplayFrame f, VertexConsumer panel, VertexConsumer ui,
                                                 Matrix4f m, Component hint, double a) {
        float ts = 0.0040f;
        float halfW = Math.min(0.66f, Math.max(0.28f, f.mc.font.width(hint) * ts * 0.5f + 0.07f));
        float halfH = 0.048f;
        // Positioned cleanly below the volume drawer so it never collides with the upper plaque or main deck
        float tx = Math.max(VOL_PANEL_X0 + halfW - 0.04f, VOL_MUTE_CX);
        Matrix4f tm = new Matrix4f(m).translate(tx, VOL_PANEL_Y0 - 0.072f, 0.020f);
        chamferPanel(panel, tm, halfW, halfH, 0.018f, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.98 * a));
        chamferOutline(ui, tm, halfW, halfH, 0.018f, 0.003f, 0.0070f,
                ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.92 * a));
        f.text(tm, hint, 0f, 0f, 0.006f, ts, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), 0, true, halfW * 2f - 0.08f);
    }

    /** Crisp pure-geometry flat Art Deco speaker glyph (concentric arcs when active, clean X when muted). */
    private static void drawSpeakerIcon(VertexConsumer ui, Matrix4f m, float cx, float cy, float z,
                                        float r, boolean muted, int color, int slashColor) {
        // Rear speaker cabinet
        ReplayDraw.rectXY(ui, m, cx - 0.82f * r, cy - 0.34f * r, cx - 0.36f * r, cy + 0.34f * r, z, color, color);
        // Flared speaker cone
        ReplayDraw.quad(ui, m,
                cx - 0.36f * r, cy - 0.34f * r, z,
                cx + 0.14f * r, cy - 0.78f * r, z,
                cx + 0.14f * r, cy + 0.78f * r, z,
                cx - 0.36f * r, cy + 0.34f * r, z,
                color, color, color, color);
        float stroke = Math.max(0.0040f, r * 0.18f);
        if (muted) {
            // Crisp flat symmetric X beside the speaker cone
            ReplayDraw.lineXY(ui, m, cx + 0.32f * r, cy + 0.30f * r, cx + 0.86f * r, cy - 0.30f * r, z + 0.0005f,
                    stroke * 1.25f, slashColor, slashColor);
            ReplayDraw.lineXY(ui, m, cx + 0.32f * r, cy - 0.30f * r, cx + 0.86f * r, cy + 0.30f * r, z + 0.0005f,
                    stroke * 1.25f, slashColor, slashColor);
        } else {
            // Smooth concentric sound-wave arcs strictly within ±r
            ReplayDraw.arcXY(ui, m, cx + 0.10f * r, cy, z, 0.44f * r, stroke, -0.78f, 0.78f, 6, color);
            ReplayDraw.arcXY(ui, m, cx + 0.10f * r, cy, z, 0.76f * r, stroke, -0.84f, 0.84f, 8, color);
        }
    }

    private static void paintTimelineHoverTooltip(ReplayFrame f, VertexConsumer panel, VertexConsumer ui,
                                                  DeckSurface surf, float frac, double targetTick) {
        Matrix4f m = surf.matrix();
        double a = surf.fade();
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
        float halfH = 0.054f;
        float clampedX = Mth.clamp(hx, -1.25f + halfW, 1.25f - halfW);
        // Sits strictly above the upper ebony chassis top (+0.215): 0.280 - 0.054 = +0.226 > +0.215
        Matrix4f tm = new Matrix4f(m).translate(clampedX, 0.280f, 0.020f);
        chamferPanel(panel, tm, halfW, halfH, 0.020f, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.98 * a));
        chamferOutline(ui, tm, halfW, halfH, 0.020f, 0.003f, 0.0072f,
                ReplayPalette.alpha(nearest != null ? ReplayPalette.RUBY : ReplayPalette.BRASS_LIGHT, 0.95 * a));
        f.text(tm, label, 0f, 0f, 0.006f, ts,
                ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), 0, true, halfW * 2f - 0.08f);
    }

    private static void paintDeckTooltip(ReplayFrame f, VertexConsumer panel, VertexConsumer ui,
                                         Matrix4f m, Component hint, double a) {
        float ts = 0.0042f;
        float halfW = Math.min(1.25f, Math.max(0.28f, f.mc.font.width(hint) * ts * 0.5f + 0.08f));
        float halfH = 0.050f;
        Matrix4f tm = new Matrix4f(m).translate(0f, DECK_Y0 - 0.072f, 0.020f);
        chamferPanel(panel, tm, halfW, halfH, 0.018f, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.96 * a),
                ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.98 * a));
        chamferOutline(ui, tm, halfW, halfH, 0.018f, 0.003f, 0.0068f,
                ReplayPalette.alpha(ReplayPalette.BRASS, 0.90 * a));
        f.text(tm, hint, 0f, 0f, 0.006f, ts, ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, a), 0, true, halfW * 2f - 0.08f);
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
        VertexConsumer ui = f.buffers.getBuffer(ReplayDraw.UI_OVERLAY);
        VertexConsumer glow = f.glow();
        int halo = play ? CYAN_HALO : GOLD_HALO, core = play ? CYAN_CORE : GOLD_CORE;
        float chamfer = 0.045f * k;
        float lineW = 0.011f * k;
        boolean busy = play && (phase == Phase.STARTING || phase == Phase.PLAYING);

        int top, bottom, border;
        if (!enabled && !busy) {
            top = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.84 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.88 * fade);
            border = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.58 * fade);
        } else if (hover) {
            top = ReplayPalette.alpha(ReplayPalette.lerp(ReplayPalette.NIGHT_3, halo, 0.30), 0.97 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.lerp(ReplayPalette.NIGHT_1, halo, 0.16), 0.97 * fade);
            border = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, fade);
        } else {
            top = ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.94 * fade);
            bottom = ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.96 * fade);
            border = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.92 * fade);
        }
        // True 8-sided chamfered octagon base (PANEL) + crisp mitered flat border & inner hairline (UI_OVERLAY)
        chamferPanel(panel, sign, hw, hh, chamfer, 0f, top, bottom);
        chamferOutline(ui, sign, hw, hh, chamfer, 0.004f * k, lineW, border);
        chamferOutline(ui, sign, hw - 0.018f * k, hh - 0.018f * k, Math.max(0f, chamfer - 0.010f * k),
                0.004f * k, lineW * 0.40f, ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.42 * fade));

        double pulse = 0.5 + 0.5 * Math.sin(ReplayIdleCubes.wrapAngleRad(f.anim * 4.0));
        if (hover && enabled) {
            // Exact 8-sided chamfered outer halo hugging the beveled edge (zero protruding square corners)
            float o = 0.055f * k;
            int inner = ReplayPalette.alpha(halo, (0.30 + 0.12 * pulse) * fade), outer = ReplayPalette.alpha(halo, 0);
            ReplayDraw.chamferHaloXY(glow, sign, hw, hh, chamfer, 0.001f * k, o, inner, outer);
        } else if (enabled) {
            // Clamped sheen sweep strictly inside [-hw + chamfer, hw - chamfer] so top corners never overshoot
            float sheenW = 0.06f * k, sheenSkew = 0.04f * k;
            float minX = -hw + chamfer;
            float maxX = Math.max(minX, hw - chamfer - sheenW - sheenSkew);
            double phaseT = ReplayIdleCubes.wrapAngleRad(f.anim * 0.9 + (play ? 0 : 1.9)) / (Math.PI * 2.0) + 0.5;
            float x = Mth.lerp((float) phaseT, minX, maxX);
            int sheen = ReplayPalette.alpha(core, 0.13 * Math.sin(phaseT * Math.PI) * fade);
            int none = ReplayPalette.alpha(core, 0);
            ReplayDraw.quad(glow, sign, x, -hh + lineW, 0.003f * k, x + sheenW, -hh + lineW, 0.003f * k,
                    x + sheenW + sheenSkew, hh - lineW, 0.003f * k, x + sheenSkew, hh - lineW, 0.003f * k,
                    none, sheen, sheen, none);
        }
        if (hover && enabled && System.nanoTime() - lastTrigger < FLASH_NS) {
            int flash = ReplayPalette.alpha(0xFFFFFFFF, 0.22 * fade);
            chamferPanel(glow, sign, hw - lineW, hh - lineW, Math.max(0f, chamfer - lineW * 0.6f), 0.003f * k, flash, flash);
        }

        float ix = -hw + 0.16f * k;
        float iconZ = 0.005f * k;
        int iconCol = ReplayPalette.alpha(enabled || busy ? core : ReplayPalette.TEXT_FAINT, (enabled || busy ? 0.96 : 0.58) * fade);
        int ringCol = ReplayPalette.alpha(enabled || busy ? halo : ReplayPalette.BRASS_DIM, (hover ? 0.90 : 0.65) * fade);
        ring(ui, sign, ix, 0f, iconZ, 0.098f * k, 0.009f * k, ringCol);
        if (play) {
            if (busy) {
                float bar = 0.017f * k, bh = 0.055f * k;
                int c = ReplayPalette.alpha(core, (0.65 + 0.35 * pulse) * fade);
                ReplayDraw.rectXY(ui, sign, ix - 0.04f * k, -bh, ix - 0.04f * k + bar * 2, bh, iconZ + 0.001f * k, c, c);
                ReplayDraw.rectXY(ui, sign, ix + 0.04f * k - bar * 2, -bh, ix + 0.04f * k, bh, iconZ + 0.001f * k, c, c);
            } else {
                ReplayDraw.quad(ui, sign, ix - 0.036f * k, -0.062f * k, iconZ + 0.001f * k,
                        ix - 0.036f * k, 0.062f * k, iconZ + 0.001f * k,
                        ix + 0.066f * k, 0f, iconZ + 0.001f * k,
                        ix + 0.066f * k, 0f, iconZ + 0.001f * k, iconCol, iconCol, iconCol, iconCol);
            }
        } else {
            float w = 0.013f * k;
            float iz = iconZ + 0.001f * k;
            // Crisp flat download arrow + tray strictly inside the circular badge
            ReplayDraw.rectXY(ui, sign, ix - w * 0.5f, -0.022f * k, ix + w * 0.5f, 0.056f * k, iz, iconCol, iconCol);
            ReplayDraw.quad(ui, sign, ix - 0.044f * k, 0.002f * k, iz, ix + 0.044f * k, 0.002f * k, iz,
                    ix, -0.042f * k, iz, ix, -0.042f * k, iz, iconCol, iconCol, iconCol, iconCol);
            ReplayDraw.rectXY(ui, sign, ix - 0.056f * k, -0.066f * k, ix + 0.056f * k, -0.066f * k + w, iz, iconCol, iconCol);
            ReplayDraw.rectXY(ui, sign, ix - 0.056f * k, -0.066f * k + w, ix - 0.056f * k + w, -0.036f * k, iz, iconCol, iconCol);
            ReplayDraw.rectXY(ui, sign, ix + 0.056f * k - w, -0.066f * k + w, ix + 0.056f * k, -0.036f * k, iz, iconCol, iconCol);
        }
        int rule = ReplayPalette.alpha(ReplayPalette.BRASS_DIM, 0.78 * fade);
        float rx = -hw + 0.30f * k;
        ReplayDraw.rectXY(ui, sign, rx - lineW * 0.35f, -hh + 0.035f * k, rx + lineW * 0.35f, hh - 0.035f * k, iconZ, rule, rule);

        int textColor;
        if (!enabled && !busy) textColor = ReplayPalette.alpha(ReplayPalette.TEXT_FAINT, fade);
        else if (hover) textColor = ReplayPalette.alpha(0xFFFFFFFF, fade);
        else textColor = ReplayPalette.alpha(ReplayPalette.CHAMPAGNE, fade);
        float zoneCenter = ((-hw + 0.33f * k) + (hw - 0.04f * k)) * 0.5f;
        f.text(sign, label, zoneCenter, 0f, 0.009f * k, ts, textColor, 0, true, hw * 2f - 0.37f * k);
    }

    private static void drawHint(ReplayFrame f, Matrix4f sign, float hh, float k, float ts, boolean enabled,
                                 Component reason, double fade) {
        Component text = enabled ? ReplayBoardTexts.tr("button.hint") : reason;
        float hs = ts * 0.82f;
        float halfW = f.mc.font.width(text) * hs * 0.5f + 0.07f * k;
        float halfH = 0.060f * k;
        Matrix4f m = new Matrix4f(sign).translate(0f, -(hh + 0.075f * k + halfH), 0f);
        VertexConsumer panel = f.buffers.getBuffer(ReplayDraw.PANEL);
        VertexConsumer ui = f.buffers.getBuffer(ReplayDraw.UI_OVERLAY);
        chamferPanel(panel, m, halfW, halfH, 0.025f * k, 0f,
                ReplayPalette.alpha(ReplayPalette.NIGHT_2, 0.94 * fade), ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.96 * fade));
        chamferOutline(ui, m, halfW, halfH, 0.025f * k, 0.004f * k, 0.008f * k,
                ReplayPalette.alpha(enabled ? ReplayPalette.BRASS : ReplayPalette.RUBY, 0.86 * fade));
        f.text(m, text, 0f, 0f, 0.009f * k, hs, ReplayPalette.alpha(enabled ? ReplayPalette.TEXT : ReplayPalette.RUBY, fade), 0, true, 0);
    }

    // ------------------------------------------------------------------ Art Deco primitives

    private static void drawDiamond(VertexConsumer vc, Matrix4f m, float cx, float cy, float z, float r, int color) {
        ReplayDraw.quad(vc, m, cx, cy + r, z, cx + r, cy, z, cx, cy - r, z, cx - r, cy, z, color, color, color, color);
    }

    private static void chamferPanel(VertexConsumer vc, Matrix4f m, float hw, float hh, float c, float z, int top, int bottom) {
        ReplayDraw.chamferRectXY(vc, m, -hw, -hh, hw, hh, z, c, top, bottom);
    }

    private static void chamferOutline(VertexConsumer vc, Matrix4f m, float hw, float hh, float c, float z, float width, int color) {
        ReplayDraw.chamferOutlineXY(vc, m, hw, hh, c, z, width, color);
    }

    private static void ring(VertexConsumer vc, Matrix4f m, float cx, float cy, float z, float radius, float width, int color) {
        ReplayDraw.ringXY(vc, m, cx, cy, z, radius, width, 24, color);
    }
}
