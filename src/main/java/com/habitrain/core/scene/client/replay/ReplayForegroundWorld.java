package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayEntityTrack;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ReplayForegroundWorld {
    public interface RendererFields {
        void habitrain$camera(Camera camera);
        void habitrain$hands(ItemInHandRenderer renderer);
        float habitrain$fov();
        float habitrain$oldFov();
        void habitrain$fov(float fov);
        void habitrain$oldFov(float fov);
    }

    private record Live(ClientLevel level, LocalPlayer player, Entity entity, Camera camera, net.minecraft.world.phys.Vec3 ears) {}
    private static volatile Live live;
    private static volatile Map<UUID,Integer> outlines = Map.of();
    public static java.util.OptionalInt outline(Entity entity) {
        if (entity == null) return java.util.OptionalInt.empty();
        var map = outlines;
        var color = map == null ? null : map.get(entity.getUUID());
        return color == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(color);
    }
    public static boolean rendering() { return live != null; }
    private static volatile int filters;
    public static boolean looseEnd() { return (filters & 2)!=0; }
    private final Map<UUID,net.minecraft.client.multiplayer.PlayerInfo> bodySkins=new HashMap<>();
    private static volatile Map<UUID,net.minecraft.client.multiplayer.PlayerInfo> activeSkins=Map.of();
    public static net.minecraft.client.multiplayer.PlayerInfo bodySkin(UUID id) { return id==null?null:activeSkins.get(id); }
    private void rememberSkin(UUID id) {
        if (id==null || bodySkins.containsKey(id) || bodySkins.size()>=512) return;
        var skin=ReplayHeadSkins.skin(id);
        bodySkins.put(id,new net.minecraft.client.multiplayer.PlayerInfo(new GameProfile(id,"Replay"),false) {
            @Override public PlayerSkin getSkin() { return skin; }
        });
    }
    public static ClientLevel voiceLevel(ClientLevel fallback) { var saved = live; return saved == null ? fallback : saved.level; }
    public static LocalPlayer voicePlayer(LocalPlayer fallback) { var saved = live; return saved == null ? fallback : saved.player; }
    public static Entity voiceEntity(Entity fallback) { var saved = live; return saved == null ? fallback : saved.entity; }
    public static Camera voiceCamera(Camera fallback) { var saved = live; return saved == null ? fallback : saved.camera; }
    public static net.minecraft.world.phys.Vec3 liveVoicePosition() { var saved = live; return saved == null ? null : saved.ears; }

    private final ClientLevel level;
    private final ReplayForegroundChunks chunks;
    private final ReplayBoardModel model;
    private final PlaybackCamera camera = new PlaybackCamera();
    private final ReplayParticleState particleState = new ReplayParticleState();
    private final ReplayVisualDownload visuals;
    private final RecordedEntities worldEntities;
    private RecordedView view;
    private com.habitrain.core.network.replay.VisualFrames.Hud hud;
    static float renderedFov;
    private static final class PlaybackCamera extends Camera {
        com.habitrain.core.network.replay.VisualFrames.Camera recorded;
        @Override public void setup(net.minecraft.world.level.BlockGetter world, Entity entity, boolean detached, boolean mirrored, float delta) {
            super.setup(world, entity, detached, mirrored, delta);
            if (recorded != null) { setPosition(recorded.x(),recorded.y(),recorded.z()); setRotation(recorded.yaw(),recorded.pitch()); }
        }
    }
    private final RecordedPlayer pov;
    private final io.wifi.starrailexpress.cca.SREPlayerMoodComponent observerMood;
    private final ItemInHandRenderer hands;
    private final MultiPlayerGameMode mode;
    private final Map<UUID, RemotePlayer> actors = new HashMap<>();
    private final Map<UUID, Entity> extras = new HashMap<>();
    private ClientLevel boundLiveLevel;
    private long warmingSince;
    private int warmFrames;
    private int lastHandTick = Integer.MIN_VALUE;
    private UUID lastSubject;

    ReplayForegroundWorld(Minecraft client, ReplayBoardModel model, SceneAssetCodec.AssetData asset, ReplayVisualDownload visuals) throws ReflectiveOperationException, java.io.IOException {
        this.model = model;
        this.visuals = visuals;
        var real = client.level;
        int radius = Math.clamp(client.options.getEffectiveRenderDistance() + 1, 4, 33);
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(asset.dimensionId));
        net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType> type = real.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                .getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION_TYPE, key.location()))
                .map(holder -> (net.minecraft.core.Holder<net.minecraft.world.level.dimension.DimensionType>) holder)
                .orElse(real.dimensionTypeRegistration());
        level = new ClientLevel(client.getConnection(), new ClientLevel.ClientLevelData(real.getDifficulty(), false, false),
                key, type, radius + 1, radius, client::getProfiler,
                client.levelRenderer, false, 0L);
        level.setDayTime(6000);
        level.updateSkyBrightness();
        chunks = new ReplayForegroundChunks(level, asset, model.replay.origin(), model.replay.blockDeltas(), radius);
        particleState.level = level;
        chunks.visualEvents(visuals.events(com.habitrain.core.network.replay.VisualTape.WORLD));
        worldEntities=new RecordedEntities(visuals.events(com.habitrain.core.network.replay.VisualTape.WORLD));
        pov = new RecordedPlayer(client, level);
        observerMood = new io.wifi.starrailexpress.cca.SREPlayerMoodComponent(pov) {
            @Override public boolean isLowerThanDepressed() { return (filters & 1)!=0; }
        };
        hands = new ItemInHandRenderer(client, client.getEntityRenderDispatcher(), client.getItemRenderer());
        mode = new MultiPlayerGameMode(client, client.getConnection());
    }

    boolean draw(Minecraft client, UUID subject, double tick, java.util.function.Consumer<net.minecraft.client.DeltaTracker> render) throws ReflectiveOperationException, java.io.IOException {
        var actor = model.participant(subject);
        if (actor == null) throw new IllegalStateException("Missing replay viewpoint");
        var sample = actor.track.sample(tick);
        if (view == null || !subject.equals(lastSubject)) {
            view = new RecordedView(visuals.events(com.habitrain.core.network.replay.VisualTape.WORLD), visuals.events(subject));
            particleState.clear();
        }
        camera.recorded = view.camera(tick);
        hud = view.hud((int)tick);
        outlines = view.outlines((int)tick);
        var body = client.player;
        var real = client.level;
        var oldCamera = client.gameRenderer.getMainCamera();
        var oldEntity = client.cameraEntity;
        var cameraType = client.options.getCameraType();
        var oldMode = client.gameMode;
        var oldMood=io.wifi.starrailexpress.client.SREClient.moodComponent;
        var oldPick = client.hitResult;
        var pickedEntity = client.crosshairPickEntity;
        boolean hidden = client.options.hideGui;
        var oldHands = client.gameRenderer.itemInHandRenderer;
        var fields = (RendererFields) client.gameRenderer;
        float fov = fields.habitrain$fov(), oldFov = fields.habitrain$oldFov();
        oldCamera.setup(real, oldEntity == null ? body : oldEntity, !cameraType.isFirstPerson(), cameraType.isMirrored(), 1f);
        live = new Live(real, body, oldEntity, oldCamera, body.getEyePosition());
        var particleExchange = (ReplayParticleState.Exchange)client.particleEngine;
        particleExchange.habitrain$swap(particleState);
        try {
            filters=camera.recorded==null?0:camera.recorded.filters(); activeSkins=bodySkins;
            io.wifi.starrailexpress.client.SREClient.moodComponent=observerMood;
            client.level = level;
            client.player = pov;
            client.cameraEntity = pov;
            client.gameMode = mode;
            fields.habitrain$camera(camera);
            fields.habitrain$hands(hands);
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.options.hideGui = !sample.visible() || sample.state() != ReplayTrackSampler.State.ALIVE;
            posePlayer(pov, actor, sample, tick);
            pov.spectator = view.body(pov,(int)tick,camera.recorded != null && camera.recorded.spectator());
            if (camera.recorded != null) {
                renderedFov = camera.recorded.fov(); client.options.hideGui = camera.recorded.hideGui() || pov.spectator;
                pov.setPos(camera.recorded.x(),camera.recorded.y()-pov.getEyeHeight(),camera.recorded.z());
                pov.setYRot(camera.recorded.yaw()); pov.setXRot(camera.recorded.pitch()); pov.setOldPosAndRot();
            }
            pov.subject = actor.p;
            var motion = actor.track.limbMotionAt(tick);
            pov.walkDist = pov.walkDistO = (float) motion.distance() * 0.6f;
            pov.oBob = pov.bob = sample.hasAction(LastMatchReplay.ACT_ON_GROUND) ? (float) Math.min(0.1, motion.speed()) : 0f;
            pov.xBob = pov.xBobO = sample.pitch();
            pov.yBob = pov.yBobO = sample.headYaw();
            pov.recordUse(sample);
            mode.setLocalMode(pov.spectator ? GameType.SPECTATOR : GameType.SURVIVAL, null);
            fields.habitrain$fov(pov.getFieldOfViewModifier());
            fields.habitrain$oldFov(pov.getFieldOfViewModifier());
            int handTick = (int) Math.floor(tick);
            if (!subject.equals(lastSubject) || handTick < lastHandTick || handTick > (long)lastHandTick + 8) {
                for (int step = 0; step < 8; step++) hands.tick();
            } else for (int step = lastHandTick; step < handTick; step++) hands.tick();
            if(camera.recorded!=null && camera.recorded.hands()!=null) {
                var saved=camera.recorded.hands(); var target=(com.habitrain.core.client.mixin.ReplayHandHeights)hands;
                target.habitrain$oldMain(saved.oldMain()); target.habitrain$main(saved.main()); target.habitrain$oldOff(saved.oldOff()); target.habitrain$off(saved.off());
                pov.oAttackAnim=saved.oldSwing(); pov.attackAnim=saved.swing(); pov.swingingArm=saved.offSwing()?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
            }
            lastSubject = subject;
            lastHandTick = handTick;
            if (boundLiveLevel != real) {
                boundLiveLevel = real;
                client.levelRenderer.setLevel(level);
                client.gameRenderer.lightTexture().tick();
            }
            if (level.getEntity(pov.getId()) == null) {
                pov.setUUID(UUID.randomUUID());
                level.addEntity(pov);
            }
            camera.setup(level, pov, false, false, 1f);
            if (warmFrames == 0 || handTick != cameraTick) camera.tick();
            cameraTick = handTick;
            view.time(level, handTick);
            boolean loaded = chunks.prepare(new ChunkPos(pov.blockPosition()), tick);
            if (loaded && lastBlockTick != handTick) {
                int steps = lastBlockTick < 0 || handTick<=lastBlockTick ? 1 : Math.min(8,handTick-lastBlockTick);
                for(int step=0;step<steps;step++) ((com.habitrain.core.client.mixin.ReplayBlockTickAccessor)level).habitrain$tickVisualBlocks();
                lastBlockTick=handTick;
            }
            chunks.alignDoorAnimations();
            for (var other : model.actors) {
                var otherPose = other.track.sample(tick);
                if (other == actor || otherPose.state() != ReplayTrackSampler.State.ALIVE) {
                    var previous = actors.remove(other.p.id());
                    if (previous != null) level.removeEntity(previous.getId(), Entity.RemovalReason.DISCARDED);
                    continue;
                }
                var player = actors.get(other.p.id());
                if (player == null) {
                    player = new RemotePlayer(level, new GameProfile(other.p.id(), other.p.name())) {
                        @Override public PlayerSkin getSkin() { return ReplayHeadSkins.skin(other.p.id(), other.p.name()); }
                    };
                    posePlayer(player, other, otherPose, tick);
                    level.addEntity(player);
                    actors.put(other.p.id(), player);
                } else posePlayer(player, other, otherPose, tick);
            }
            for (var track : model.replay.entityTracks()) if (!worldEntities.contains(track.entityId())) updateEntity(track, tick);
            worldEntities.draw(level,tick);
            view.particles(level, client.particleEngine, handTick);
            render.accept(new FrameClock((float)(tick - Math.floor(tick))));
            if (!loaded) { warmFrames = 0; warmingSince = 0; return false; }
            if (warmingSince == 0) warmingSince = System.nanoTime();
            if (++warmFrames < 3) return false;
            if (warmFrames > 3 && client.levelRenderer.hasRenderedAllSections()) return true;
            if (warmFrames > 8 && client.levelRenderer.countRenderedSections() > 0) return true;
            if (System.nanoTime() - warmingSince > 30_000_000_000L) throw new IllegalStateException("Replay terrain did not become visible");
            return false;
        } finally {
            io.wifi.starrailexpress.client.SREClient.moodComponent=oldMood; filters=0; activeSkins=Map.of();
            particleExchange.habitrain$swap(particleState);
            renderedFov = 0;
            outlines = Map.of();
            client.level = real;
            client.player = body;
            client.cameraEntity = oldEntity;
            client.gameMode = oldMode;
            fields.habitrain$camera(oldCamera);
            fields.habitrain$hands(oldHands);
            fields.habitrain$fov(fov);
            fields.habitrain$oldFov(oldFov);
            client.options.setCameraType(cameraType);
            client.options.hideGui = hidden;
            client.hitResult = oldPick;
            client.crosshairPickEntity = pickedEntity;
            live = null;
            client.getEntityRenderDispatcher().prepare(real, oldCamera, pickedEntity);
            client.getBlockEntityRenderDispatcher().prepare(real, oldCamera, oldPick);
        }
    }

    private int cameraTick = Integer.MIN_VALUE;
    private int lastBlockTick = -1;
    void paintHud(Minecraft client) {
        if (hud == null || hud.draws().isEmpty()) return;
        var window = client.getWindow();
        var stack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix(); stack.pushMatrix();
        try {
            com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(new org.joml.Matrix4f().setOrtho(0,window.getGuiScaledWidth(),window.getGuiScaledHeight(),0,1000,21000),com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
            stack.identity().translate(0,0,-11000); com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,Minecraft.ON_OSX);
            ReplayVisualClient.paint(client,new net.minecraft.client.gui.GuiGraphics(client,client.renderBuffers().bufferSource()),hud);
        } finally { stack.popMatrix(); com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix(); com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix(); }
    }
    private record FrameClock(float partial) implements net.minecraft.client.DeltaTracker {
        @Override public float getGameTimeDeltaPartialTick(boolean paused) { return partial; }
        @Override public float getGameTimeDeltaTicks() { return 20f / ReplayVideoExport.FPS; }
        @Override public float getRealtimeDeltaTicks() { return getGameTimeDeltaTicks(); }
    }

    private void posePlayer(Player player, ReplayBoardModel.Actor actor, ReplayTrackSampler.Pose sample, double tick) {
        var origin = model.replay.origin();
        player.setPos(origin.minX() + sample.x(), origin.minY() + sample.y(), origin.minZ() + sample.z());
        player.setXRot(sample.pitch()); player.setYRot(sample.headYaw());
        player.yHeadRot = player.yHeadRotO = sample.headYaw(); player.yBodyRot = player.yBodyRotO = sample.yaw();
        player.setOldPosAndRot(); player.tickCount = (int)tick;
        player.setOnGround(sample.hasAction(LastMatchReplay.ACT_ON_GROUND));
        player.setSprinting(sample.hasAction(LastMatchReplay.ACT_SPRINT));
        player.setShiftKeyDown(sample.hasAction(LastMatchReplay.ACT_SNEAK));
        player.setPose(sample.hasAction(LastMatchReplay.ACT_SWIM_CRAWL) ? Pose.SWIMMING : player.isShiftKeyDown() ? Pose.CROUCHING : Pose.STANDING);
        player.setInvisible(sample.hasStatus(LastMatchReplay.STATUS_INVISIBLE));
        player.deathTime = sample.state() == ReplayTrackSampler.State.DEAD ? Math.min(20, 1 + (int)tick - sample.stateSince()) : 0;
        player.setMainArm(sample.hasAction(LastMatchReplay.ACT_LEFT_HANDED) ? HumanoidArm.LEFT : HumanoidArm.RIGHT);
        player.setItemInHand(InteractionHand.MAIN_HAND, ReplayFullPlayerRenderer.resolveItem(sample.mainHandItemId()));
        player.setItemInHand(InteractionHand.OFF_HAND, ReplayFullPlayerRenderer.resolveItem(sample.offHandItemId()));
        var motion = actor.track.limbMotionAt(tick);
        player.swingingArm = motion.offSwing() > motion.mainSwing() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        player.oAttackAnim = player.attackAnim = Math.max(motion.mainSwing(), motion.offSwing());
        player.walkAnimation.update((float)Math.min(1, motion.speed() * 4), 1f);
    }

    private void updateEntity(ReplayEntityTrack track, double tick) {
        boolean recorded = view.tracksCorpse(track.entityId());
        var appearance = recorded ? view.corpse(track.entityId(),(int)tick) : null;
        var sample = recorded ? null : track.sampleAt(tick);
        var entity = extras.get(track.entityId());
        if (recorded ? appearance == null || appearance.hidden() : sample == null || !sample.active() || sample.has(ReplayEntityTrack.FLAG_HIDDEN)) {
            if (entity != null) { level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED); extras.remove(track.entityId()); }
            return;
        }
        if (entity == null) {
            if (extras.containsKey(track.entityId())) return;
            var key = ResourceLocation.tryParse(track.entityTypeId());
            var type = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
            entity = type == null ? null : type.create(level);
            extras.put(track.entityId(), entity);
            if (entity == null) return;
            entity.setUUID(track.entityId());
            if (entity instanceof io.wifi.starrailexpress.content.entity.PlayerBodyEntity body && track.targetUuid() != null) body.setPlayerUuid(track.targetUuid());
        }
        if (appearance!=null && entity instanceof io.wifi.starrailexpress.content.entity.PlayerBodyEntity body) {
            body.moveTo(appearance.x(),appearance.y(),appearance.z(),appearance.yaw(),appearance.pitch());
            body.yBodyRotO=body.yBodyRot=appearance.bodyYaw(); body.yHeadRotO=body.yHeadRot=appearance.headYaw();
            rememberSkin(appearance.owner()); rememberSkin(appearance.skin());
            if (appearance.owner()!=null) body.setPlayerUuid(appearance.owner());
            body.getComponent().skinUuid=appearance.skin(); body.setCorrupted(appearance.corrupted());
            body.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE).setBaseValue(appearance.scale());
            body.tickCount=(int)Math.clamp((long)(int)tick-appearance.born(),0,Integer.MAX_VALUE);
        } else if (sample!=null) {
            var origin = model.replay.origin();
            entity.moveTo(origin.minX()+sample.x(), origin.minY()+sample.y(), origin.minZ()+sample.z(), sample.yawDeg(), sample.pitchDeg());
            entity.tickCount = Math.max(0,(int)tick-track.samples().getFirst().tick());
            if (entity instanceof io.wifi.starrailexpress.content.entity.PlayerBodyEntity body) {
                body.yBodyRotO=body.yBodyRot=body.yHeadRotO=body.yHeadRot=sample.yawDeg(); body.setCorrupted(sample.has(ReplayEntityTrack.FLAG_CORRUPTED));
            }
        }
        entity.setOldPosAndRot();
        if (level.getEntity(entity.getId()) == null) level.addEntity(entity);
    }

    void detach(Minecraft client) {
        if (boundLiveLevel != null && client.level == boundLiveLevel) {
            client.levelRenderer.setLevel(client.level);
            client.gameRenderer.lightTexture().tick();
        }
        boundLiveLevel = null;
    }

    private static final class RecordedPlayer extends LocalPlayer {
        private LastMatchReplay.Participant subject;
        private InteractionHand used = InteractionHand.MAIN_HAND;
        RecordedPlayer(Minecraft client, ClientLevel level) {
            super(client, level, client.getConnection(), client.player.getStats(), client.player.getRecipeBook(), false, false);
        }
        @Override public PlayerSkin getSkin() { return subject == null ? super.getSkin() : ReplayHeadSkins.skin(subject.id(), subject.name()); }
        @Override public boolean isModelPartShown(PlayerModelPart part) { return true; }
        private boolean spectator;
        @Override public boolean isSpectator() { return spectator; }
        @Override public float getAttackStrengthScale(float partial) { return 1f; }
        @Override public InteractionHand getUsedItemHand() { return used; }
        @Override public boolean isUsingItem() { return useItemRemaining > 0; }
        void recordUse(ReplayTrackSampler.Pose sample) {
            used = sample.hasAction(LastMatchReplay.ACT_USING_OFF_HAND) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            useItem = sample.hasAction(LastMatchReplay.ACT_USING_ITEM) ? getItemInHand(used) : ItemStack.EMPTY;
            useItemRemaining = useItem.isEmpty() ? 0 : Math.max(1, sample.actionFlags() >>> LastMatchReplay.ACT_USE_REMAINING_SHIFT);
        }
    }
}
