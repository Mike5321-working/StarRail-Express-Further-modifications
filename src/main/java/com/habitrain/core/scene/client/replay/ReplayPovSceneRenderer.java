package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.client.render.ClientRenderState;
import com.habitrain.core.scene.client.SceneMeshSet;
import com.habitrain.core.scene.client.SceneShaders;
import com.habitrain.core.scene.replay.ReplayFanCutaway;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;

/**
 * Unified 1:1 world-scale 3D POV / Over-Shoulder / Hero-Side / 360° Master Camera scene renderer.
 *
 * <p>Renders directly into any currently bound framebuffer &amp; arbitrary viewport rectangle
 * {@code (vpX, vpY, vpW, vpH)} with full GL/RenderSystem state isolation (Sodium/Iris safe).
 * Shared directly by Mode 1 split-screen right panel, Mode 2 True Player POV, Mode 3 Sandbox
 * Full-Fidelity POV, and Mode 4 Tiers 3–5 cinematic camera choreography.</p>
 */
public final class ReplayPovSceneRenderer {
    private static final float NEAR = 0.06f;
    private static final float FAR = 180.0f;

    private static MultiBufferSource.BufferSource buffers;
    private static final List<ByteBufferBuilder> OWNED = new ArrayList<>();
    private static final ReplayFanCutaway POV_FANS = new ReplayFanCutaway();
    private static float[] trailPoints = new float[32 * 3], trailWidths = new float[32];
    private static int[] trailColors = new int[32];

    private ReplayPovSceneRenderer() {}

    public enum CameraMode {
        FIRST_PERSON,
        OVER_SHOULDER,
        HERO_SIDE,
        ORBIT_360,
        FREE_WORLD
    }

    /**
     * Explicit camera rig in replay {@code origin}-relative block coordinates.
     */
    public record CameraSpec(
            CameraMode mode,
            @Nullable UUID subjectUuid,
            double eyeX, double eyeY, double eyeZ,
            float yawDeg, float pitchDeg, float rollDeg,
            float fovDeg,
            boolean renderFirstPersonHand,
            float orbitAngleDeg,
            float distanceBlocks
    ) {
        /** True first-person eye camera mounted to {@code subjectUuid}. */
        public static CameraSpec firstPerson(ReplayBoardModel model, UUID subjectUuid, double matchTick,
                                             float fovDeg, boolean renderHand) {
            ReplayTrackSampler.Pose pose = sampleSubject(model, subjectUuid, matchTick);
            if (pose == null) {
                return new CameraSpec(CameraMode.FIRST_PERSON, subjectUuid, 0, 2, 0, 0, 0, 0, fovDeg, false, 0, 0);
            }
            boolean sneak = pose.hasAction(LastMatchReplay.ACT_SNEAK) || pose.has(LastMatchReplay.SNEAK);
            boolean crawl = pose.hasAction(LastMatchReplay.ACT_SWIM_CRAWL);
            Vector3f eye = ReplayFullPlayerRenderer.eyePosition(pose.x(), pose.y(), pose.z(), sneak, crawl);
            return new CameraSpec(CameraMode.FIRST_PERSON, subjectUuid,
                    eye.x, eye.y, eye.z, pose.headYawDegrees(), pose.pitchDegrees(), 0f,
                    fovDeg, renderHand && pose.state() == ReplayTrackSampler.State.ALIVE, 0f, 0f);
        }

        /** Behind-the-shoulder chase camera following {@code subjectUuid}. */
        public static CameraSpec overShoulder(ReplayBoardModel model, UUID subjectUuid, double matchTick,
                                              float backDist, float rightOffset, float upOffset, float fovDeg) {
            ReplayTrackSampler.Pose pose = sampleSubject(model, subjectUuid, matchTick);
            if (pose == null) {
                return new CameraSpec(CameraMode.OVER_SHOULDER, subjectUuid, 0, 3, -3, 0, 10, 0, fovDeg, false, 0, backDist);
            }
            float yaw = pose.headYawDegrees();
            float pitch = Mth.clamp(pose.pitchDegrees() * 0.45f + 10.0f, -35f, 55f);
            double rad = Math.toRadians(yaw);
            // Forward vector in MC: (-sin(yaw), 0, cos(yaw)); Right vector: (-cos(yaw), 0, -sin(yaw))
            double fx = -Math.sin(rad), fz = Math.cos(rad);
            double rx = -Math.cos(rad), rz = -Math.sin(rad);
            double baseY = pose.y() + ReplayFullPlayerRenderer.EYE_HEIGHT_STAND + upOffset;
            double ex = pose.x() - fx * backDist + rx * rightOffset;
            double ey = baseY;
            double ez = pose.z() - fz * backDist + rz * rightOffset;
            return new CameraSpec(CameraMode.OVER_SHOULDER, subjectUuid,
                    ex, ey, ez, yaw, pitch, 0f, fovDeg, false, 0f, backDist);
        }

        /** Dramatic 90°–115° side-angle hero close-up camera for Mode 4 Tier 3 &amp; Tier 4. */
        public static CameraSpec heroSide(ReplayBoardModel model, UUID subjectUuid, double matchTick,
                                          float sideAngleDeg, float distanceBlocks, float fovDeg) {
            ReplayTrackSampler.Pose pose = sampleSubject(model, subjectUuid, matchTick);
            if (pose == null) {
                return new CameraSpec(CameraMode.HERO_SIDE, subjectUuid, 2, 2, 0, 90, 6, 0, fovDeg, false, sideAngleDeg, distanceBlocks);
            }
            float camAzimuth = pose.yaw() + sideAngleDeg;
            double rad = Math.toRadians(camAzimuth);
            double focusX = pose.x(), focusY = pose.y() + 1.25, focusZ = pose.z();
            double ex = focusX - Math.sin(rad) * distanceBlocks;
            double ey = focusY + 0.35;
            double ez = focusZ + Math.cos(rad) * distanceBlocks;
            float[] lookAngles = anglesBetween(ex, ey, ez, focusX, focusY, focusZ);
            return new CameraSpec(CameraMode.HERO_SIDE, subjectUuid,
                    ex, ey, ez, lookAngles[0], lookAngles[1], -3.5f, fovDeg, false, sideAngleDeg, distanceBlocks);
        }

        /** Sweeping 360° cinematic master orbit camera for Mode 4 Tier 5. */
        public static CameraSpec orbit360(double centerX, double centerY, double centerZ,
                                          float orbitAngleDeg, float elevationDeg, float distanceBlocks, float fovDeg) {
            double az = Math.toRadians(orbitAngleDeg);
            double el = Math.toRadians(Mth.clamp(elevationDeg, -20f, 75f));
            double horiz = distanceBlocks * Math.cos(el);
            double ex = centerX + Math.sin(az) * horiz;
            double ey = centerY + Math.sin(el) * distanceBlocks;
            double ez = centerZ + Math.cos(az) * horiz;
            float[] lookAngles = anglesBetween(ex, ey, ez, centerX, centerY, centerZ);
            return new CameraSpec(CameraMode.ORBIT_360, null,
                    ex, ey, ez, lookAngles[0], lookAngles[1], 0f, fovDeg, false, orbitAngleDeg, distanceBlocks);
        }

        private static @Nullable ReplayTrackSampler.Pose sampleSubject(ReplayBoardModel model, @Nullable UUID id, double tick) {
            if (model == null || id == null) return null;
            ReplayBoardModel.Actor actor = model.participant(id);
            if (actor == null) return null;
            ReplayTrackSampler.Pose pose = actor.track.sample(tick);
            return pose.visible() ? pose : null;
        }

        private static float[] anglesBetween(double fromX, double fromY, double fromZ,
                                             double toX, double toY, double toZ) {
            double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
            double horiz = Math.max(1e-5, Math.hypot(dx, dz));
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
            return new float[]{yaw, pitch};
        }
    }

    /**
     * Rendering feature flags &amp; highlight controls for a POV scene pass.
     */
    public record RenderOptions(
            boolean roofCutaway,
            boolean fullBodyPlayers,
            boolean showTrails,
            boolean showNameplates,
            boolean showRoles,
            boolean showEntities,
            boolean showSkills,
            boolean blackoutEffect,
            boolean flashEffect,
            boolean disguisePovEffect,
            @Nullable Map<UUID, Float> actorScaleOverrides,
            @Nullable Set<UUID> highlightActors,
            float dimOtherActorsAlpha,
            @Nullable Set<UUID> visibleActorFilter,
            @Nullable Set<UUID> nameplateActorFilter,
            @Nullable Set<UUID> revealedRoleActors
    ) {
        public static final RenderOptions DEFAULT = new RenderOptions(
                false, true, false, true, true, true, true,
                true, true, true, null, null, 1.0f, null, null, null
        );

        public static RenderOptions sandboxPov(boolean showTrails, boolean showNameplates, boolean showRoles) {
            return new RenderOptions(false, true, showTrails, showNameplates, showRoles, true, true,
                    true, true, true, null, null, 1.0f, null, null, null);
        }

        public static RenderOptions promoHighlight(Set<UUID> focusActors, float focusScale, float dimAlpha) {
            Map<UUID, Float> scales = new LinkedHashMap<>();
            if (focusActors != null) {
                for (UUID id : focusActors) scales.put(id, focusScale);
            }
            return new RenderOptions(false, true, true, true, true, true, true,
                    true, true, true, Map.copyOf(scales), focusActors, dimAlpha, null, null, null);
        }

        boolean roofCutawayFor(CameraMode cameraMode) {
            return roofCutaway && cameraMode != CameraMode.FIRST_PERSON;
        }

        boolean showsRole(UUID actor) {
            return showRoles && (revealedRoleActors == null || revealedRoleActors.contains(actor));
        }

        ReplayFullPlayerRenderer.HighlightSpec toHighlightSpec() {
            if ((highlightActors == null || highlightActors.isEmpty())
                    && (actorScaleOverrides == null || actorScaleOverrides.isEmpty())) {
                return ReplayFullPlayerRenderer.HighlightSpec.NONE;
            }
            return new ReplayFullPlayerRenderer.HighlightSpec(
                    highlightActors, 1.65f, dimOtherActorsAlpha,
                    highlightActors != null && !highlightActors.isEmpty(), actorScaleOverrides);
        }
    }

    /**
     * Renders a complete 1:1 world-scale replay POV frame into the currently bound OpenGL framebuffer,
     * strictly scoped to {@code (vpX, vpY, vpW, vpH)} in framebuffer pixel coordinates.
     */
    public static void renderToCurrentTarget(Minecraft mc, ReplayBoardModel model,
                                             @Nullable SceneAssetCodec.AssetData baseAsset,
                                             @Nullable SceneMeshSet fallbackMesh,
                                             double matchTick, double animSeconds,
                                             int vpX, int vpY, int vpW, int vpH,
                                             CameraSpec camera, @Nullable RenderOptions options) {
        renderToCurrentTarget(mc, model, baseAsset, fallbackMesh, matchTick, animSeconds,
                vpX, vpY, vpW, vpH, camera, options, null);
    }

    static void renderToCurrentTarget(Minecraft mc, ReplayBoardModel model,
                                      @Nullable SceneAssetCodec.AssetData baseAsset,
                                      @Nullable SceneMeshSet fallbackMesh,
                                      double matchTick, double animSeconds,
                                      int vpX, int vpY, int vpW, int vpH,
                                      CameraSpec camera, @Nullable RenderOptions options,
                                      @Nullable ReplayNativeCamera nativeView) {
        if (mc == null || model == null || vpW <= 0 || vpH <= 0 || camera == null) return;
        RenderOptions opts = options == null ? RenderOptions.DEFAULT : options;
        SceneBounds origin = model.replay.origin();
        SceneBounds source = model.source;
        ReplayPovTerrain mapTerrain = ReplayPovTerrain.forAsset(baseAsset);
        ReplayPovTerrain povTerrain = camera.mode() == CameraMode.FIRST_PERSON ? mapTerrain : null;
        var poses = new ReplayTrackSampler.Pose[model.actors.length];
        var subject = model.participant(camera.subjectUuid());
        var subjectPose = subject == null ? null : sample(poses, subject, matchTick);

        // Prepare dynamic blocks (doors/windows/broken walls) at matchTick
        ReplayDioramaMesh.DynamicWorld dynamicWorld = ReplayDioramaMesh.prepareDynamic(
                baseAsset, origin, model.replay.blockDeltas());
        SceneMeshSet terrainMesh = fallbackMesh;
        if (dynamicWorld != null && dynamicWorld.hasDynamics()) {
            var staticAsset = dynamicWorld.staticAsset();
            ReplayDioramaMesh.update(staticAsset, staticAsset.sections.size(), staticAsset.sections.size());
            if (ReplayDioramaMesh.matches(staticAsset)) {
                SceneMeshSet ready = ReplayDioramaMesh.current();
                if (ready != null) terrainMesh = ready;
            }
        }

        // Resolve wall-collision-safe camera eye for non-first-person cameras
        Vector3f resolvedEye = resolveCameraEye(camera, subjectPose, mapTerrain, origin, source);
        Vector3f lookDir = lookVector(camera.yawDeg(), camera.pitchDeg());
        Vector3f upVec = new Vector3f(0, 1, 0);
        if (Math.abs(camera.rollDeg()) > 1e-3f) {
            upVec.rotateAxis((float) Math.toRadians(camera.rollDeg()), lookDir.x, lookDir.y, lookDir.z);
        }
        Matrix4f view = new Matrix4f().lookAlong(lookDir, upVec);
        Quaternionf camRot = view.getUnnormalizedRotation(new Quaternionf()).conjugate().normalize();
        Vector3f camRight = camRot.transform(new Vector3f(1, 0, 0));
        Vector3f camUp = camRot.transform(new Vector3f(0, 1, 0));
        float aspect = (float) vpW / (float) vpH;
        float fov = Mth.clamp(camera.fovDeg() > 5f ? camera.fovDeg() : 72f, 20f, 115f);
        boolean handBob = mc.options.bobView().get();
        if (nativeView != null && camera.mode() == CameraMode.FIRST_PERSON && camera.subjectUuid() != null) {
            handBob = nativeView.bobView();
            if (subjectPose != null && subjectPose.state() == ReplayTrackSampler.State.ALIVE) {
                var motion = subject.track.limbMotionAt(matchTick);
                view = nativeView.view(view, motion.distance(), motion.speed(), subjectPose.hasAction(LastMatchReplay.ACT_ON_GROUND));
                fov = nativeView.fov(subjectPose.hasAction(LastMatchReplay.ACT_SPRINT) || subjectPose.has(LastMatchReplay.SPRINT));
            } else {
                fov = nativeView.fov(false);
            }
        }
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, NEAR, FAR);

        // Save all OpenGL / RenderSystem state
        int[] prevVp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevVp);
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] prevScissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevScissorBox);
        float prevFogStart = RenderSystem.getShaderFogStart();
        float prevFogEnd = RenderSystem.getShaderFogEnd();
        int prevLightTex = RenderSystem.getShaderTexture(2);
        Matrix4f prevProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        float[] prevColor = RenderSystem.getShaderColor().clone();
        var modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();

        try (var stateGuard = ClientRenderState.capture()) {
            RenderSystem.viewport(vpX, vpY, vpW, vpH);
            RenderSystem.enableScissor(vpX, vpY, vpW, vpH);
            boolean blackout = opts.blackoutEffect() && model.blackoutAt(matchTick);

            // Clear viewport background (Star-Night sky or deep blackout darkness)
            RenderSystem.clearColor(blackout ? 0.012f : 0.025f, blackout ? 0.012f : 0.042f,
                    blackout ? 0.024f : 0.095f, 1.0f);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

            modelViewStack.set(view);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            FogRenderer.setupNoFog();
            mc.gameRenderer.lightTexture().turnOnLightLayer();

            // 1. Render 1:1 world-scale terrain mesh + dynamic doors/walls
            float srcOffX = (float) ((source.minX() - origin.minX()) - resolvedEye.x);
            float srcOffY = (float) ((source.minY() - origin.minY()) - resolvedEye.y);
            float srcOffZ = (float) ((source.minZ() - origin.minZ()) - resolvedEye.z);
            Matrix4f sourceModel = new Matrix4f().translation(srcOffX, srcOffY, srcOffZ);
            Matrix4f sourceModelView = new Matrix4f(view).mul(sourceModel);

            float dimR = blackout ? 0.30f : 1.0f;
            float dimG = blackout ? 0.25f : 1.0f;
            float dimB = blackout ? 0.36f : 1.0f;

            // A first-person eye must see the same walls and roof that the recorded player saw.
            boolean roofCutaway = opts.roofCutawayFor(camera.mode());
            POV_FANS.clear();
            if (roofCutaway && subjectPose != null && subjectPose.visible()) {
                POV_FANS.faceViewer(sourceModel, lookDir, source);
                POV_FANS.add(subjectPose, origin, source, 2.2);
            }

            if (terrainMesh != null && !terrainMesh.isClosed() && !terrainMesh.isEmpty()) {
                int minLight = povTerrain != null && povTerrain.hasRecordedLight() ? 0
                        : (blackout ? LightTexture.pack(4, 3) : LightTexture.pack(11, 13));
                try (var modelScope = SceneShaders.model(sourceModel, minLight);
                     var boundsScope = SceneShaders.bounds(-Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
                     var clipScope = SceneShaders.clip(0, 0, 0, 1)) {
                    RenderSystem.setShaderColor(dimR, dimG, dimB, 1.0f);
                    ReplayTerrainRenderer.draw(terrainMesh, sourceModelView, projection, POV_FANS,
                            -Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -1, 0);
                } finally {
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                }
            }

            MultiBufferSource.BufferSource bufSource = buffers();
            int worldLight = blackout ? LightTexture.pack(5, 4) : LightTexture.FULL_BRIGHT;

            // Dynamic blocks (doors opened/closed/locked, broken walls) at matchTick
            if (dynamicWorld != null && dynamicWorld.hasDynamics()) {
                RenderSystem.setShaderColor(dimR, dimG, dimB, 1.0f);
                dynamicWorld.render(mc, sourceModel, bufSource, matchTick,
                        roofCutaway ? model.roofCutLayer : -1,
                        POV_FANS.count() > 0 ? POV_FANS : null, worldLight, povTerrain);
                bufSource.endBatch();
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            }
            if (povTerrain != null) {
                RenderSystem.setShaderColor(dimR, dimG, dimB, 1.0f);
                povTerrain.renderDoors(mc, sourceModel, bufSource, dynamicWorld, worldLight);
                bufSource.endBatch();
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            }

            // 2. Render full 3D players, drones, entities, skills, trails, and nameplates in origin-relative space
            Matrix4f worldToCam = new Matrix4f().translation(-resolvedEye.x, -resolvedEye.y, -resolvedEye.z);
            var actorFrustum = new FrustumIntersection(new Matrix4f(projection).mul(view).mul(worldToCam));
            VertexConsumer fill = bufSource.getBuffer(ReplayDraw.FILL);
            VertexConsumer glow = bufSource.getBuffer(ReplayDraw.GLOW);
            PoseStack entityStack = new PoseStack();
            ReplayFullPlayerRenderer.HighlightSpec highlight = opts.toHighlightSpec();
            boolean godView = camera.mode() == CameraMode.ORBIT_360 || camera.mode() == CameraMode.FREE_WORLD;

            ReplayFullPlayerRenderer.PoseSpec firstPersonSpec = null;
            for (ReplayBoardModel.Actor actor : model.actors) {
                if (opts.visibleActorFilter() != null && !opts.visibleActorFilter().contains(actor.p.id())) continue;
                ReplayTrackSampler.Pose pose = sample(poses, actor, matchTick);
                boolean isSubject = actor == subject;
                if (!pose.visible()) continue;

                if (isSubject && camera.mode() == CameraMode.FIRST_PERSON) {
                    firstPersonSpec = ReplayFullPlayerRenderer.poseFrom(model, actor, pose, matchTick);
                    continue; // Do not clip self body inside first-person eye camera
                }

                float selfFade = 1.0f;
                if (isSubject && camera.mode() == CameraMode.OVER_SHOULDER) {
                    float camDist = resolvedEye.distance((float) pose.x(), (float) pose.y() + 1.45f, (float) pose.z());
                    if (camDist < 0.72f) continue; // Camera pushed right against subject by tight wall
                    if (camDist < 1.35f) {
                        selfFade = (float) com.habitrain.core.scene.replay.ReplayStagePlan.smoothstep(0.72, 1.35, camDist);
                    }
                }

                float bright = highlight.brightnessFor(actor.p.id()) * (blackout ? 0.65f : 1.0f);
                float actorScale = ReplayFullPlayerRenderer.WORLD_PLAYER_SCALE * highlight.scaleFor(actor.p.id());
                float baseActorAlpha = (highlight.hasFocusSet() && !highlight.isFocused(actor.p.id())
                        ? opts.dimOtherActorsAlpha() : 1.0f) * selfFade;
                int tint = ReplayPalette.alpha(ReplayPalette.scale(0xFFFFFFFF, bright), baseActorAlpha);

                float x = (float) pose.x(), y = (float) pose.y(), z = (float) pose.z();
                float margin = 0.25f + actorScale * 2.6f;
                float minY = y - margin - (pose.state() == ReplayTrackSampler.State.ALIVE ? 0f : 2.6f);
                if (actorFrustum.testAab(x - margin, minY, z - margin, x + margin, y + margin, z + margin)) {
                    ReplayHeadSkins.rememberName(actor.p.id(), actor.p.name());
                    var spec = ReplayFullPlayerRenderer.poseFrom(model, actor, pose, matchTick);
                    float renderY = spec.alive() || mapTerrain == null ? y
                            : settleCorpseY(mapTerrain, origin, source, x, y, z, spec.deathAgeSeconds());
                    entityStack.pushPose();
                    entityStack.mulPose(worldToCam);
                    entityStack.translate(x, renderY, z);
                    int actorLight = povTerrain == null ? worldLight : povTerrain.lightAt(
                            origin.minX() + pose.x(), origin.minY() + renderY + 1, origin.minZ() + pose.z(), worldLight);
                    ReplayFullPlayerRenderer.renderCharacter(entityStack, bufSource, spec, actorScale, 0f,
                            animSeconds, tint, actorLight, 1.0f, godView);
                    entityStack.popPose();
                }

                if (highlight.isFocused(actor.p.id()) && highlight.spotlightRing()) {
                    ReplayFullPlayerRenderer.emitSpotlightAura(glow, worldToCam,
                            (float) pose.x(), (float) pose.y(), (float) pose.z(),
                            0.95f * highlight.scaleFor(actor.p.id()), 2.6f, actor.factionColor(), animSeconds, 1.0);
                }

                if (opts.showTrails()) {
                    renderWorldTrail(glow, worldToCam, actor, matchTick,
                            highlight.isFocused(actor.p.id()) ? 0.9f : 0.45f * bright);
                }
            }

            // 3. Drones (legacy track list) & all v2 non-player entities / persistent zones
            for (var drone : model.drones) {
                var dp = drone.track().sample(matchTick);
                if (dp.state() != ReplayTrackSampler.State.ALIVE) continue;
                entityStack.pushPose();
                entityStack.mulPose(worldToCam);
                ReplayDronePainter.renderWorldDrone(entityStack, bufSource, glow, worldToCam,
                        (float) dp.x(), (float) dp.y() + 0.25f, (float) dp.z(),
                        dp.yaw(), dp.pitchDegrees(), animSeconds,
                        dp.has(LastMatchReplay.SPRINT), dp.has(LastMatchReplay.Drone.PILOTED),
                        dp.has(LastMatchReplay.Drone.CRASHED), dp.has(LastMatchReplay.Drone.SCRAPPED),
                        1.0, worldLight);
                entityStack.popPose();
            }

            if (opts.showEntities()) {
                entityStack.pushPose();
                entityStack.mulPose(worldToCam);
                ReplayEntityPainter.paintWorld(entityStack, bufSource, glow, fill, worldToCam,
                        camRight, camUp, model, matchTick, animSeconds, 1.0f, godView);
                entityStack.popPose();
            }

            // 4. All-mod skills & 3-layer unknown-skill astrolabe pulse fallback
            if (opts.showSkills()) {
                ReplaySkillEffectRegistry.paintWorld(entityStack, bufSource, worldToCam, glow, fill, camRight, camUp,
                        model, matchTick, animSeconds, 1.0f, godView,
                        camera.subjectUuid(), camera.mode() == CameraMode.FIRST_PERSON);
            }

            // Flush opaque/translucent models, flat fills, and additive glow passes
            bufSource.endBatch(ReplayDraw.FILL);
            bufSource.endBatch();
            bufSource.endBatch(ReplayDraw.GLOW);

            // 5. Floating world-space nameplates & role tags (optional)
            if (opts.showNameplates()) {
                Set<UUID> npFilter = opts.nameplateActorFilter() != null ? opts.nameplateActorFilter() : opts.visibleActorFilter();
                UUID hideSelfPlate = (camera.mode() == CameraMode.FIRST_PERSON || camera.mode() == CameraMode.OVER_SHOULDER)
                        ? camera.subjectUuid() : null;
                renderWorldNameplates(mc, bufSource, model, poses, actorFrustum, worldToCam, camRot, matchTick,
                        hideSelfPlate, opts, npFilter);
                bufSource.endBatch();
            }

            // 6. First-person hand + 3D held weapon pass
            if (camera.mode() == CameraMode.FIRST_PERSON && camera.renderFirstPersonHand()
                    && firstPersonSpec != null && subjectPose != null) {
                RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
                modelViewStack.identity();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(
                        new Matrix4f().perspective((float) Math.toRadians(70.0), aspect, 0.05f, 32.0f),
                        VertexSorting.DISTANCE_TO_ORIGIN);
                Lighting.setupFor3DItems();
                PoseStack handPose = new PoseStack();
                int handLight = povTerrain == null ? worldLight : povTerrain.lightAt(
                        origin.minX() + resolvedEye.x, origin.minY() + resolvedEye.y, origin.minZ() + resolvedEye.z, worldLight);
                ReplayFirstPersonHands.render(mc, handPose, bufSource, firstPersonSpec, subjectPose, matchTick, handLight, handBob);
                bufSource.endBatch();
            }

            // 7. POV atmospheric screen overlays (Flashbang whiteout, Blackout dark vignette, Blindness, Disguise rim)
            float flashAlpha = opts.flashEffect()
                    ? ReplaySkillEffectRegistry.flashIntensityFor(camera.subjectUuid(), model, matchTick) : 0f;
            boolean blind = subjectPose != null && subjectPose.hasStatus(LastMatchReplay.STATUS_BLIND);
            boolean disguisedPov = opts.disguisePovEffect() && subjectPose != null
                    && (subjectPose.has(LastMatchReplay.DISGUISED) || subjectPose.hasStatus(LastMatchReplay.STATUS_DISGUISED));
            if (flashAlpha > 0.01f || blackout || blind || disguisedPov) {
                renderPovOverlays(flashAlpha, blackout, blind, disguisedPov, animSeconds);
            }
        } finally {
            if (prevScissor) {
                RenderSystem.enableScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
            } else {
                RenderSystem.disableScissor();
            }
            RenderSystem.viewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(prevProj, prevSorting);
            RenderSystem.setShaderFogStart(prevFogStart);
            RenderSystem.setShaderFogEnd(prevFogEnd);
            RenderSystem.setShaderTexture(2, prevLightTex);
            RenderSystem.setShaderColor(prevColor[0], prevColor[1], prevColor[2], prevColor[3]);
        }
    }

    /** Releases reusable offscreen buffers on client disconnect/shutdown. */
    static void releaseBuffers() {
        buffers = null;
        for (ByteBufferBuilder b : OWNED) b.close();
        OWNED.clear();
    }

    // ------------------------------------------------------------------ internals

    private static Vector3f lookVector(float yawDeg, float pitchDeg) {
        double yr = Math.toRadians(yawDeg);
        double pr = Math.toRadians(Mth.clamp(pitchDeg, -89.5f, 89.5f));
        double cp = Math.cos(pr);
        return new Vector3f((float) (-Math.sin(yr) * cp), (float) (-Math.sin(pr)), (float) (Math.cos(yr) * cp)).normalize();
    }

    /**
     * Pulls third-person cameras cleanly inside solid walls using voxel ray-marching against
     * the snapshot so tight train corridors stay visible.
     */
    private static Vector3f resolveCameraEye(CameraSpec cam, @Nullable ReplayTrackSampler.Pose pose,
                                             @Nullable ReplayPovTerrain terrain, SceneBounds origin, SceneBounds source) {
        Vector3f desired = new Vector3f((float) cam.eyeX(), (float) cam.eyeY(), (float) cam.eyeZ());
        if (cam.mode() == CameraMode.FIRST_PERSON || cam.mode() == CameraMode.FREE_WORLD
                || terrain == null || pose == null || !pose.visible()) {
            return desired;
        }
        Vector3f anchor = new Vector3f((float) pose.x(), (float) pose.y() + 1.45f, (float) pose.z());
        Vector3f delta = new Vector3f(desired).sub(anchor);
        float totalDist = delta.length();
        if (totalDist < 0.35f) return desired;

        int steps = Math.min(28, Math.max(6, (int) Math.ceil(totalDist / 0.22f)));
        int offX = origin.minX() - source.minX();
        int offY = origin.minY() - source.minY();
        int offZ = origin.minZ() - source.minZ();

        for (int i = 2; i <= steps; i++) {
            float t = (float) i / steps;
            float px = anchor.x + delta.x * t;
            float py = anchor.y + delta.y * t;
            float pz = anchor.z + delta.z * t;
            int lx = Mth.floor(px) + offX;
            int ly = Mth.floor(py) + offY;
            int lz = Mth.floor(pz) + offZ;
            if (terrain.isSolidVoxel(lx, ly, lz)) {
                float safeT = Math.max(0.22f, ((i - 1.55f) / steps));
                return new Vector3f(anchor).add(delta.mul(safeT));
            }
        }
        return desired;
    }

    /**
     * Smoothly settles a freshly killed actor onto the nearest solid deck voxel below (within 2.5 blocks)
     * so players eliminated mid-jump or knockback don't hang tilted in mid-air.
     */
    private static float settleCorpseY(ReplayPovTerrain terrain, SceneBounds origin, SceneBounds source,
                                       float x, float y, float z, float deathAgeSeconds) {
        int offX = origin.minX() - source.minX();
        int offY = origin.minY() - source.minY();
        int offZ = origin.minZ() - source.minZ();
        int lx = Mth.floor(x) + offX;
        int lz = Mth.floor(z) + offZ;
        int startLy = Mth.floor(y - 0.05f) + offY;
        float floorY = y;
        for (int dy = 0; dy <= 3; dy++) {
            int checkLy = startLy - dy;
            if (terrain.isSolidVoxel(lx, checkLy, lz)) {
                floorY = (checkLy + 1) - offY;
                break;
            }
        }
        if (floorY >= y - 0.02f || y - floorY > 2.6f) return y;
        float t = Mth.clamp(deathAgeSeconds / 0.32f, 0f, 1f);
        float dropEase = t * t;
        return Mth.lerp(dropEase, y, floorY);
    }

    private static void renderWorldTrail(VertexConsumer glow, Matrix4f worldToCam, ReplayBoardModel.Actor actor,
                                         double matchTick, float alpha) {
        ReplayTrackSampler track = actor.track;
        int last = track.indexAt(matchTick);
        if (last < 1) return;
        int first = Math.max(0, track.indexAt(matchTick - 60.0));
        int count = last - first + 1;
        if (count < 2) return;
        if (count > trailWidths.length) {
            int capacity = Math.max(count, trailWidths.length * 2);
            trailPoints = new float[capacity * 3];
            trailWidths = new float[capacity];
            trailColors = new int[capacity];
        }
        int n = 0;
        for (int i = first; i <= last; i++) {
            if (i > first && !track.connects(i)) {
                if (n >= 2) ReplayDraw.ribbon(glow, trailPoints, n, trailWidths, trailColors);
                n = 0;
            }
            double u = Mth.clamp((track.tick(i) - (matchTick - 60.0)) / 60.0, 0.0, 1.0);
            trailPoints[n * 3] = (float) track.x(i) + worldToCam.m30();
            trailPoints[n * 3 + 1] = (float) track.y(i) + 0.08f + worldToCam.m31();
            trailPoints[n * 3 + 2] = (float) track.z(i) + worldToCam.m32();
            trailWidths[n] = (float) (0.04 + 0.08 * u);
            trailColors[n] = ReplayPalette.alpha(actor.color, u * u * 0.72 * alpha);
            n++;
        }
        if (n >= 2) ReplayDraw.ribbon(glow, trailPoints, n, trailWidths, trailColors);
    }

    private static ReplayTrackSampler.Pose sample(ReplayTrackSampler.Pose[] poses, ReplayBoardModel.Actor actor, double tick) {
        var pose = poses[actor.index];
        return pose == null ? (poses[actor.index] = actor.track.sample(tick)) : pose;
    }

    private static void renderWorldNameplates(Minecraft mc, MultiBufferSource.BufferSource buffers,
                                              ReplayBoardModel model, ReplayTrackSampler.Pose[] poses,
                                              FrustumIntersection frustum, Matrix4f worldToCam, Quaternionf camRot,
                                              double matchTick, @Nullable UUID skipUuid,
                                              RenderOptions options, @Nullable Set<UUID> filter) {
        Font font = mc.font;
        Vector3f pos = new Vector3f();
        for (ReplayBoardModel.Actor actor : model.actors) {
            if (skipUuid != null && skipUuid.equals(actor.p.id())) continue;
            if (filter != null && !filter.contains(actor.p.id())) continue;
            var pose = sample(poses, actor, matchTick);
            if (!pose.visible() || pose.hasStatus(LastMatchReplay.STATUS_INVISIBLE)) continue;
            boolean alive = pose.state() == ReplayTrackSampler.State.ALIVE;
            float x = (float) pose.x(), y = (float) pose.y() + (alive ? 2.18f : 0.85f), z = (float) pose.z();
            worldToCam.transformPosition(x, y, z, pos);
            float dist = pos.length();
            if (dist > 28f || dist < 0.85f) continue;
            float nearFade = (float) com.habitrain.core.scene.replay.ReplayStagePlan.smoothstep(0.85, 1.85, dist);
            float farFade = (float) (1.0 - Math.max(0.0, (dist - 18.0) / 10.0));
            float fade = nearFade * farFade;
            if (fade <= 0.02f) continue;
            // Clamp near-distance angular growth so adjacent players' nameplates never fill the screen.
            float plateScale = 0.022f * Math.min(1.0f, dist / 2.6f);
            float nw = font.width(actor.name);
            boolean roleVisible = alive && options.showsRole(actor.p.id());
            float rw = roleVisible ? font.width(actor.role) : 0f;
            if (!frustum.testSphere(x, y, z, (Math.max(nw, rw) * 0.5f + 24f) * plateScale)) continue;
            Matrix4f m = new Matrix4f().translate(pos).rotate(camRot).scale(plateScale, -plateScale, plateScale);
            font.drawInBatch(actor.name, -nw * 0.5f, 0f,
                    ReplayPalette.alpha(alive ? ReplayPalette.CHAMPAGNE : ReplayPalette.GHOST, fade),
                    false, m, buffers, Font.DisplayMode.NORMAL, ReplayPalette.alpha(ReplayPalette.NIGHT_0, 0.55 * fade),
                    LightTexture.FULL_BRIGHT);
            if (roleVisible) {
                int roleCol = ReplayPalette.readableRoleColor(actor.color);
                font.drawInBatch(actor.role, -rw * 0.5f, 10.5f,
                        ReplayPalette.alpha(roleCol, fade),
                        false, m, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            }
        }
    }

    private static void renderPovOverlays(float flashAlpha, boolean blackout, boolean blind,
                                          boolean disguised, double animSec) {
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, 1, 1, 0, -1, 1), VertexSorting.ORTHOGRAPHIC_Z);
        var mv = RenderSystem.getModelViewStack();
        mv.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        if (blackout || blind) {
            float dark = blind ? 0.72f : (float) (0.34 + 0.06 * Math.sin(animSec * 4.5));
            drawEdgeVignette(ReplayPalette.alpha(ReplayPalette.NIGHT_0, dark), 0.28f);
        }
        if (disguised) {
            float shimmer = (float) (0.20 + 0.08 * Math.sin(animSec * 3.8));
            drawEdgeVignette(ReplayPalette.alpha(ReplayPalette.DISGUISE, shimmer), 0.14f);
        }
        if (flashAlpha > 0.01f) {
            int c = ReplayPalette.alpha(0xFFFFF8EE, Math.min(0.58, flashAlpha * 0.65));
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            b.addVertex(0, 0, 0).setColor(c);
            b.addVertex(0, 1, 0).setColor(c);
            b.addVertex(1, 1, 0).setColor(c);
            b.addVertex(1, 0, 0).setColor(c);
            BufferUploader.drawWithShader(b.buildOrThrow());
        }
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static void drawEdgeVignette(int edgeColor, float border) {
        int clear = ReplayPalette.alpha(edgeColor, 0);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // Top
        b.addVertex(0, 0, 0).setColor(edgeColor); b.addVertex(0, border, 0).setColor(clear);
        b.addVertex(1, border, 0).setColor(clear); b.addVertex(1, 0, 0).setColor(edgeColor);
        // Bottom
        b.addVertex(0, 1 - border, 0).setColor(clear); b.addVertex(0, 1, 0).setColor(edgeColor);
        b.addVertex(1, 1, 0).setColor(edgeColor); b.addVertex(1, 1 - border, 0).setColor(clear);
        // Left
        b.addVertex(0, 0, 0).setColor(edgeColor); b.addVertex(0, 1, 0).setColor(edgeColor);
        b.addVertex(border, 1, 0).setColor(clear); b.addVertex(border, 0, 0).setColor(clear);
        // Right
        b.addVertex(1 - border, 0, 0).setColor(clear); b.addVertex(1 - border, 1, 0).setColor(clear);
        b.addVertex(1, 1, 0).setColor(edgeColor); b.addVertex(1, 0, 0).setColor(edgeColor);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    private static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            SequencedMap<RenderType, ByteBufferBuilder> fixed = new LinkedHashMap<>();
            fixed.put(ReplayDraw.FILL, own(1 << 17));
            fixed.put(ReplayDraw.GLOW, own(1 << 17));
            for (var type : ReplayFrame.glintTypes()) fixed.put(type, own(4096));
            buffers = MultiBufferSource.immediateWithBuffers(fixed, own(1 << 17));
        }
        return buffers;
    }

    private static ByteBufferBuilder own(int size) {
        ByteBufferBuilder builder = new ByteBufferBuilder(size);
        OWNED.add(builder);
        return builder;
    }
}
