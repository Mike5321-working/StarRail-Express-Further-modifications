package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.drone.DroneEquipmentModels;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * High-fidelity 3D full-body player renderer for replay dioramas and POV scenes.
 *
 * <p>Renders authentic vanilla wide/slim two-layer skins (head+hat, body+jacket, arms+sleeves,
 * legs+pants) plus player capes, procedural walk/sprint/sneak/crawl/sleep/corpse/aim/swing/hurt
 * animations, 3D main-hand &amp; off-hand held items (including custom Drone Equipment and TACZ
 * firearms), disguise appearance swapping with god-view violet phantom echoes, first-person viewmodel
 * hands, and Promo Tier-1 spotlight/dimming emphasis.</p>
 */
public final class ReplayFullPlayerRenderer {
    /** Canonical humanoid player scale in world blocks (matches vanilla LivingEntityRenderer). */
    public static final float WORLD_PLAYER_SCALE = 0.9375f;
    public static final float EYE_HEIGHT_STAND = 1.62f;
    public static final float EYE_HEIGHT_SNEAK = 1.27f;
    public static final float EYE_HEIGHT_CRAWL = 0.40f;

    private static final int ITEM_CACHE_LIMIT = 256;
    private static final LinkedHashMap<String, ItemStack> ITEM_CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
            return size() > ITEM_CACHE_LIMIT;
        }
    };

    private static @Nullable PlayerModel<LivingEntity> wideModel;
    private static @Nullable PlayerModel<LivingEntity> slimModel;

    private ReplayFullPlayerRenderer() {}

    /**
     * Promo Tier-1 micro-highlight and dimming specification.
     *
     * @param focusActors       actors to emphasize (if null or empty, no dimming is applied)
     * @param focusScale        smooth scale multiplier for emphasized actors (e.g. 1.65f)
     * @param dimOthersAlpha    brightness/opacity multiplier for non-focused actors (e.g. 0.42f)
     * @param spotlightRing     whether to emit faction breathing halo + subtle spotlight cone
     * @param actorScales       optional per-actor explicit scale overrides (blends smoothly)
     */
    public record HighlightSpec(@Nullable Set<UUID> focusActors, float focusScale, float dimOthersAlpha,
                                boolean spotlightRing, @Nullable Map<UUID, Float> actorScales) {
        public static final HighlightSpec NONE = new HighlightSpec(null, 1.0f, 1.0f, false, null);

        public static HighlightSpec tier1(Set<UUID> focusActors, float focusScale, float dimAlpha) {
            return new HighlightSpec(focusActors, focusScale, dimAlpha, true, null);
        }

        public boolean hasFocusSet() {
            return focusActors != null && !focusActors.isEmpty();
        }

        public boolean isFocused(UUID id) {
            return focusActors != null && id != null && focusActors.contains(id);
        }

        public float scaleFor(UUID id) {
            float s = 1.0f;
            if (actorScales != null && id != null) {
                Float override = actorScales.get(id);
                if (override != null && Float.isFinite(override) && override > 0.05f) s = override;
            }
            if (isFocused(id)) s = Math.max(s, focusScale);
            return s;
        }

        public float brightnessFor(UUID id) {
            if (!hasFocusSet() || isFocused(id)) return 1.0f;
            return Mth.clamp(dimOthersAlpha, 0.1f, 1.0f);
        }
    }

    /**
     * Normalized render state for one player frame, decoupled from wire formats so both v1 and v2
     * tracks, diorama boards, and 1:1 POV scenes share the exact same character animation rig.
     */
    public record PoseSpec(
            UUID actorId,
            String actorName,
            int factionColor,
            boolean alive,
            float bodyYawDeg,
            float headYawDeg,
            float pitchDeg,
            double speedBlocksPerTick,
            double walkPhase,
            boolean sprinting,
            boolean sneaking,
            boolean crawling,
            boolean sleeping,
            boolean usingItem,
            float mainSwingProgress,
            float offSwingProgress,
            float hurtFlash,
            boolean invisible,
            boolean shielded,
            boolean poisoned,
            boolean psycho,
            boolean c4OnBack,
            boolean disguised,
            @Nullable UUID disguiseTargetId,
            @Nullable String disguiseTargetName,
            @Nullable String mainHandItemId,
            @Nullable String offHandItemId,
            float deathAgeSeconds
    ) {
        public float eyeHeight() {
            if (crawling) return EYE_HEIGHT_CRAWL;
            if (sneaking) return EYE_HEIGHT_SNEAK;
            return EYE_HEIGHT_STAND;
        }
    }

    private static PlayerModel<LivingEntity> rig(boolean slim) {
        if (slim) {
            if (slimModel == null) {
                slimModel = new PlayerModel<>(
                        LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, true), 64, 64).bakeRoot(),
                        true);
                slimModel.young = false;
            }
            return slimModel;
        }
        if (wideModel == null) {
            wideModel = new PlayerModel<>(
                    LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot(),
                    false);
            wideModel.young = false;
        }
        return wideModel;
    }

    /**
     * Resolves an item string id (including HabiRoleItems logical tags and TACZ {@code item#gunId})
     * into a cached renderable {@link ItemStack}.
     */
    public static ItemStack resolveItem(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) return ItemStack.EMPTY;
        ItemStack cached = ITEM_CACHE.get(itemId);
        if (cached != null) return cached;

        ItemStack stack = buildItemStack(itemId);
        ITEM_CACHE.put(itemId, stack);
        return stack;
    }

    private static ItemStack buildItemStack(String raw) {
        String id = raw.trim();
        String lower = id.toLowerCase(Locale.ROOT);
        // Logical tags for HabiRoleItems (custom 3D models & distinct role props)
        if (lower.equals("habitrain_core:drone_terminal") || lower.endsWith("#drone_terminal")) {
            return HabiRoleItems.createDroneTerminal();
        }
        if (lower.equals("habitrain_core:drone_battery") || lower.endsWith("#drone_battery")) {
            return HabiRoleItems.createDroneBattery(1);
        }
        if (lower.equals("habitrain_core:drone_resupply") || lower.endsWith("#drone_resupply")) {
            return HabiRoleItems.createDroneResupplyIcon();
        }
        if (lower.equals("habitrain_core:bouquet") || lower.endsWith("#bouquet")) {
            return HabiRoleItems.createBouquet(1);
        }
        if (lower.equals("habitrain_core:pepper_spray") || lower.endsWith("#pepper_spray")) {
            return HabiRoleItems.createPepperSpray();
        }
        if (lower.equals("habitrain_core:lust_incense") || lower.endsWith("#lust_incense")) {
            return HabiRoleItems.createLustIncense();
        }
        if (lower.equals("habitrain_core:red_apple") || lower.endsWith("#red_apple")) {
            return HabiRoleItems.createRedApple();
        }
        if (lower.equals("habitrain_core:scapegoat_knife") || lower.equals("habitrain_core:drone_fake_knife")) {
            ItemStack knife = HabiRoleItems.createScapegoatKnife();
            return knife.isEmpty() ? new ItemStack(Items.IRON_SWORD) : knife;
        }

        String baseId = id;
        String variant = null;
        int hash = id.indexOf('#');
        if (hash > 0) {
            baseId = id.substring(0, hash);
            variant = id.substring(hash + 1);
        }
        ResourceLocation rl = ResourceLocation.tryParse(baseId);
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != Items.AIR) {
                ItemStack stack = new ItemStack(item);
                if (variant != null && !variant.isBlank() && lower.contains("tacz")) {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("GunId", variant);
                    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                }
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean isFirearm(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String s = itemId.toLowerCase(Locale.ROOT);
        return s.contains("revolver") || s.contains("derringer") || s.contains("sniper") || s.contains("shotgun")
                || s.contains("gun") || s.contains("rifle") || s.contains("pistol") || s.contains("tacz")
                || s.contains("crossbow") || s.contains("bow") || s.contains("net_gun");
    }

    public static boolean isMelee(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String s = itemId.toLowerCase(Locale.ROOT);
        return s.contains("knife") || s.contains("sword") || s.contains("axe") || s.contains("bat")
                || s.contains("crowbar") || s.contains("dagger") || s.contains("blade");
    }

    public static boolean isTwoHandedDevice(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        String s = itemId.toLowerCase(Locale.ROOT);
        return s.contains("drone_terminal") || s.contains("sniper") || s.contains("shotgun") || s.contains("rifle")
                || s.contains("tacz:modern_kinetic_gun");
    }

    /**
     * Renders a complete 3D double-layer player character (plus held items, cape, and optional
     * disguise phantom) into {@code stack} positioned at the player's feet.
     *
     * @param stack       pose stack anchored at the character's feet (local Y up)
     * @param buffers     target buffer source (clipped or direct)
     * @param spec        character pose &amp; equipment state
     * @param modelScale  scale relative to canonical Minecraft humanoid (0.9375f for 1:1 world)
     * @param yawOffset   extra Y rotation (e.g. +90° for rotated dioramas, 0° for 1:1 world)
     * @param animSec     continuous animation clock in seconds
     * @param tintArgb    color &amp; alpha tint (ARGB; brightness and opacity already applied)
     * @param light       packed block/sky light
     * @param headBoost   optional head scale emphasis (>1.0 on miniature dioramas for facial legibility; 1.0 in POV)
     * @param godView     true on diorama / god-camera (shows purple disguise phantom &amp; translucent invisible players)
     */
    public static void renderCharacter(PoseStack stack, MultiBufferSource buffers, PoseSpec spec,
                                       float modelScale, float yawOffset, double animSec,
                                       int tintArgb, int light, float headBoost, boolean godView) {
        int baseAlpha = (tintArgb >>> 24) & 0xFF;
        if (baseAlpha <= 4) return;
        if (spec.invisible() && !godView) return;

        // Determine apparent skin (disguise target if disguised)
        UUID apparentId = (spec.disguised() && spec.disguiseTargetId() != null)
                ? spec.disguiseTargetId() : spec.actorId();
        String apparentName = (spec.disguised() && spec.disguiseTargetId() != null)
                ? spec.disguiseTargetName() : spec.actorName();
        PlayerSkin skin = ReplayHeadSkins.skin(apparentId, apparentName);
        boolean slim = skin.model() == PlayerSkin.Model.SLIM;
        PlayerModel<LivingEntity> model = rig(slim);

        int effectiveTint = tintArgb;
        if (spec.invisible()) {
            // In god/diorama view, invisible players appear as translucent ghostly silhouettes
            effectiveTint = ReplayPalette.fade(effectiveTint, 0.34);
        }
        if (spec.hurtFlash() > 0.01f) {
            effectiveTint = ReplayPalette.lerp(effectiveTint, 0xFFFF5555, Math.min(0.65f, spec.hurtFlash() * 0.7f));
        } else if (spec.poisoned()) {
            effectiveTint = ReplayPalette.lerp(effectiveTint, 0xFFB8FFA8, 0.25);
        }

        int overlay = spec.hurtFlash() > 0.15f
                ? OverlayTexture.pack(OverlayTexture.u(0.0f), OverlayTexture.v(true))
                : OverlayTexture.NO_OVERLAY;

        ItemStack mainStack = resolveItem(spec.mainHandItemId());
        ItemStack offStack = resolveItem(spec.offHandItemId());

        stack.pushPose();
        // Rotate to facing: PlayerModel front is -Z; rotateY(yawOffset + 180 - bodyYaw) aligns with MC yaw
        stack.mulPose(Axis.YP.rotationDegrees(yawOffset + 180.0f - spec.bodyYawDeg()));

        // Hurt tilt / knockback roll
        if (spec.hurtFlash() > 0.01f && spec.alive()) {
            float kick = Mth.sin(spec.hurtFlash() * Mth.PI) * 14.0f;
            stack.mulPose(Axis.ZP.rotationDegrees(kick));
            stack.mulPose(Axis.XP.rotationDegrees(-kick * 0.4f));
        }

        // Posture transforms at root
        if (!spec.alive()) {
            // Fallen corpse lying flat on ground centered around its hip pivot so it stays inside narrow corridors
            float fall = Mth.clamp(spec.deathAgeSeconds() / 0.34f, 0f, 1f);
            float ease = 1f - (1f - fall) * (1f - fall) * (1f - fall);
            float hip = modelScale * 0.78f;
            stack.translate(0f, Mth.lerp(ease, hip, modelScale * 0.16f), 0f);
            stack.mulPose(Axis.ZP.rotationDegrees(90.0f * ease));
            stack.translate(0f, -hip, 0f);
        } else if (spec.sleeping()) {
            // Sloth / bed horizontal sleeping posture
            stack.translate(0f, modelScale * 0.18f, 0f);
            stack.mulPose(Axis.XP.rotationDegrees(-90.0f));
            stack.translate(0f, -modelScale * 0.65f, 0f);
        } else if (spec.crawling()) {
            // Swim / crawl prone posture
            stack.translate(0f, modelScale * 0.22f, 0f);
            stack.mulPose(Axis.XP.rotationDegrees(90.0f));
            stack.translate(0f, -modelScale * 0.62f, 0f);
        } else if (spec.sneaking()) {
            stack.translate(0f, -modelScale * 0.125f, 0f);
        }

        stack.scale(modelScale, modelScale, modelScale);
        // Flip Y and X into Minecraft ModelPart space (feet at y=1.501)
        stack.scale(-1.0f, -1.0f, 1.0f);
        stack.translate(0.0f, -1.501f, 0.0f);

        poseModel(model, spec, mainStack, offStack, animSec, headBoost);

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(skin.texture()));
        model.renderToBuffer(stack, vc, light, overlay, effectiveTint);

        // Cape rendering (if player has a cape and is alive/upright)
        ResourceLocation cape = skin.capeTexture();
        if (cape != null && spec.alive() && !spec.invisible()) {
            renderCape(stack, buffers, model, cape, spec, animSec, effectiveTint, light, overlay);
        }

        // C4 mounted on back (NoellesRoles C4BackComponent visual)
        if (spec.c4OnBack() && spec.alive()) {
            ItemStack c4 = resolveItem("noellesroles:c4");
            if (c4.isEmpty()) c4 = new ItemStack(Items.TNT);
            stack.pushPose();
            model.body.translateAndRotate(stack);
            stack.translate(0.0f, 0.32f, 0.24f);
            stack.scale(0.45f, 0.45f, 0.45f);
            Minecraft.getInstance().getItemRenderer().renderStatic(c4, ItemDisplayContext.FIXED,
                    light, OverlayTexture.NO_OVERLAY, stack, buffers, Minecraft.getInstance().level, 0);
            stack.popPose();
        }

        // Held 3D items (main hand & off hand)
        if (spec.alive()) {
            if (!mainStack.isEmpty()) {
                renderHeldItem(stack, buffers, model, HumanoidArm.RIGHT, mainStack, spec.mainHandItemId(),
                        light, animSec);
            }
            if (!offStack.isEmpty()) {
                renderHeldItem(stack, buffers, model, HumanoidArm.LEFT, offStack, spec.offHandItemId(),
                        light, animSec);
            }
        }
        stack.popPose();

        // God/Diorama View violet phantom of the TRUE identity when disguised
        if (godView && spec.disguised() && spec.alive()) {
            PlayerSkin realSkin = ReplayHeadSkins.skin(spec.actorId(), spec.actorName());
            PlayerModel<LivingEntity> ghostModel = rig(realSkin.model() == PlayerSkin.Model.SLIM);
            float wob = (float) Math.sin(animSec * 3.2 + (spec.actorId().hashCode() & 31)) * modelScale * 0.08f;
            int phantomTint = ReplayPalette.alpha(ReplayPalette.DISGUISE, (baseAlpha / 255.0) * 0.42);
            stack.pushPose();
            stack.translate(modelScale * 0.34f + wob, modelScale * 0.14f, -modelScale * 0.22f);
            stack.mulPose(Axis.YP.rotationDegrees(yawOffset + 180.0f - spec.bodyYawDeg() + 14.0f));
            stack.scale(modelScale * 0.94f, modelScale * 0.94f, modelScale * 0.94f);
            stack.scale(-1.0f, -1.0f, 1.0f);
            stack.translate(0.0f, -1.501f, 0.0f);
            poseModel(ghostModel, spec, ItemStack.EMPTY, ItemStack.EMPTY, animSec, headBoost);
            VertexConsumer gvc = buffers.getBuffer(RenderType.entityTranslucent(realSkin.texture()));
            ghostModel.renderToBuffer(stack, gvc, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, phantomTint);
            stack.popPose();
        }
    }

    private static void poseModel(PlayerModel<LivingEntity> m, PoseSpec s, ItemStack mainStack, ItemStack offStack,
                                  double animSec, float headBoost) {
        m.young = false;
        m.setAllVisible(true);
        m.head.resetPose();
        m.hat.resetPose();
        m.body.resetPose();
        m.jacket.resetPose();
        m.rightArm.resetPose();
        m.rightSleeve.resetPose();
        m.leftArm.resetPose();
        m.leftSleeve.resetPose();
        m.rightLeg.resetPose();
        m.rightPants.resetPose();
        m.leftLeg.resetPose();
        m.leftPants.resetPose();

        // Relative head yaw & pitch
        float relHeadYaw = Mth.wrapDegrees(s.headYawDeg() - s.bodyYawDeg());
        relHeadYaw = Mth.clamp(relHeadYaw, -75.0f, 75.0f);
        float pitchRad = Mth.clamp(s.pitchDeg(), -88.0f, 88.0f) * Mth.DEG_TO_RAD;
        m.head.yRot = relHeadYaw * Mth.DEG_TO_RAD;
        m.head.xRot = s.alive() ? pitchRad : 0.0f;
        if (headBoost > 1.001f) {
            m.head.xScale = m.head.yScale = m.head.zScale = headBoost;
        }

        if (!s.alive() || s.sleeping()) {
            // Relaxed limbs for corpse or sleeping Sloth
            m.rightArm.zRot = 0.14f;
            m.leftArm.zRot = -0.14f;
            m.rightArm.xRot = -0.10f;
            m.leftArm.xRot = -0.10f;
            m.rightLeg.zRot = 0.06f;
            m.leftLeg.zRot = -0.06f;
            copyOuterLayers(m);
            return;
        }

        // Walk / sprint limb swing
        float moveAmp = (float) Math.min(1.0, s.speedBlocksPerTick() / (s.sprinting() ? 0.22 : 0.15));
        float stride = (float) s.walkPhase();
        float legSwing = Mth.cos(stride) * (s.sprinting() ? 1.12f : 0.82f) * moveAmp;
        float armSwing = Mth.cos(stride + Mth.PI) * (s.sprinting() ? 0.95f : 0.65f) * moveAmp;

        m.rightLeg.xRot = legSwing;
        m.leftLeg.xRot = -legSwing;
        m.rightArm.xRot = armSwing;
        m.leftArm.xRot = -armSwing;

        // Subtle idle breathing sway
        float idleBreath = Mth.sin((float) (animSec * 2.2 + (s.actorId().hashCode() & 15))) * 0.04f;
        m.rightArm.zRot = 0.05f + idleBreath;
        m.leftArm.zRot = -0.05f - idleBreath;

        // Sneak crouch posture (exact vanilla offsets)
        if (s.sneaking()) {
            m.body.xRot = 0.5f;
            m.rightArm.xRot += 0.4f;
            m.leftArm.xRot += 0.4f;
            m.rightLeg.z = 4.0f;
            m.leftLeg.z = 4.0f;
            m.rightLeg.y = 12.2f;
            m.leftLeg.y = 12.2f;
            m.head.y = 4.2f;
            m.body.y = 3.2f;
            m.leftArm.y = 5.2f;
            m.rightArm.y = 5.2f;
        }

        // Held item & aiming poses
        boolean mainGun = isFirearm(s.mainHandItemId());
        boolean twoHand = isTwoHandedDevice(s.mainHandItemId());
        boolean mainMelee = isMelee(s.mainHandItemId());
        if (!mainStack.isEmpty()) {
            if (mainGun && (s.usingItem() || twoHand || s.psycho())) {
                // Aiming firearm forward along head pitch
                m.rightArm.yRot = -0.1f + m.head.yRot;
                m.rightArm.xRot = -Mth.HALF_PI + m.head.xRot;
                if (twoHand) {
                    m.leftArm.yRot = 0.42f + m.head.yRot;
                    m.leftArm.xRot = -Mth.HALF_PI + m.head.xRot + 0.12f;
                }
            } else if (mainGun) {
                // Low-ready firearm carry
                m.rightArm.xRot = m.rightArm.xRot * 0.35f - 0.72f;
                m.rightArm.yRot = -0.12f;
            } else if (s.usingItem()) {
                m.rightArm.xRot = -0.95f + m.head.xRot * 0.5f;
                m.rightArm.yRot = -0.25f;
            } else if (mainMelee) {
                m.rightArm.xRot = m.rightArm.xRot * 0.45f - 0.42f;
            } else {
                m.rightArm.xRot = m.rightArm.xRot * 0.5f - 0.314f;
            }
        }
        if (!offStack.isEmpty() && !twoHand) {
            m.leftArm.xRot = m.leftArm.xRot * 0.5f - 0.314f;
        }

        // Attack / swing cleave animation (main & off hand)
        if (s.mainSwingProgress() > 0.001f) {
            float atk = Mth.clamp(s.mainSwingProgress(), 0f, 1f);
            float bodyTwist = Mth.sin(Mth.sqrt(atk) * Mth.TWO_PI) * 0.22f;
            m.body.yRot += bodyTwist;
            m.rightArm.yRot += bodyTwist;
            float f1 = 1.0f - atk;
            f1 = 1.0f - f1 * f1 * f1;
            float f2 = Mth.sin(f1 * Mth.PI);
            float f3 = Mth.sin(atk * Mth.PI) * -(m.head.xRot - 0.7f) * 0.75f;
            m.rightArm.xRot -= f2 * 1.35f + f3;
            m.rightArm.yRot += bodyTwist * 2.0f;
            m.rightArm.zRot += Mth.sin(atk * Mth.PI) * -0.45f;
        }
        if (s.offSwingProgress() > 0.001f) {
            float atk = Mth.clamp(s.offSwingProgress(), 0f, 1f);
            float f2 = Mth.sin(atk * Mth.PI);
            m.leftArm.xRot -= f2 * 1.15f;
            m.leftArm.zRot += f2 * 0.35f;
        }

        copyOuterLayers(m);
    }

    private static void copyOuterLayers(PlayerModel<LivingEntity> m) {
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
    }

    private static void renderCape(PoseStack stack, MultiBufferSource buffers, PlayerModel<LivingEntity> model,
                                   ResourceLocation capeTexture, PoseSpec spec, double animSec,
                                   int tintArgb, int light, int overlay) {
        stack.pushPose();
        stack.translate(0.0f, spec.sneaking() ? 0.125f : 0.0f, 0.125f);
        float speedLift = (float) Math.min(48.0, spec.speedBlocksPerTick() * 220.0);
        float flutter = Mth.sin((float) (animSec * 6.0)) * (1.8f + speedLift * 0.08f);
        stack.mulPose(Axis.XP.rotationDegrees(6.0f + speedLift + flutter));
        stack.mulPose(Axis.YP.rotationDegrees(180.0f));
        VertexConsumer raw = buffers.getBuffer(RenderType.entityTranslucent(capeTexture));
        VertexConsumer cvc = tintArgb == 0xFFFFFFFF ? raw : new TintedVertexConsumer(raw, tintArgb);
        model.renderCloak(stack, cvc, light, overlay);
        stack.popPose();
    }

    /** Multiplies emitted vertex colors by an ARGB tint so capes obey Promo Tier-1 dimming &amp; distance fades. */
    private static final class TintedVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final int tr, tg, tb, ta;

        TintedVertexConsumer(VertexConsumer delegate, int argb) {
            this.delegate = delegate;
            this.ta = (argb >>> 24) & 0xFF;
            this.tr = (argb >> 16) & 0xFF;
            this.tg = (argb >> 8) & 0xFF;
            this.tb = argb & 0xFF;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor((r * tr) / 255, (g * tg) / 255, (b * tb) / 255, (a * ta) / 255);
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
    }

    private static void renderHeldItem(PoseStack stack, MultiBufferSource buffers, PlayerModel<LivingEntity> model,
                                       HumanoidArm arm, ItemStack itemStack, @Nullable String itemId,
                                       int light, double animSec) {
        boolean left = arm == HumanoidArm.LEFT;
        stack.pushPose();
        model.translateToHand(arm, stack);
        stack.mulPose(Axis.XP.rotationDegrees(-90.0f));
        stack.mulPose(Axis.YP.rotationDegrees(180.0f));
        stack.translate((left ? -1.0f : 1.0f) / 16.0f, 0.125f, -0.625f);
        ItemDisplayContext ctx = left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        if (!DroneEquipmentModels.renderCustomDroneItem(itemStack, ctx, left, stack, buffers, light)) {
            Minecraft.getInstance().getItemRenderer().renderStatic(itemStack, ctx,
                    light, OverlayTexture.NO_OVERLAY, stack, buffers, Minecraft.getInstance().level, 0);
        }
        stack.popPose();
    }

    /**
     * Emits the Promo Tier-1 spotlight aura (breathing faction ring + vertical light cone) around an
     * emphasized actor on the diorama or in world space.
     */
    public static void emitSpotlightAura(VertexConsumer glow, Matrix4f frame, float x, float y, float z,
                                         float radius, float height, int factionColor, double animSec, double alpha) {
        if (alpha <= 0.02) return;
        double breathe = 0.78 + 0.22 * Math.sin(animSec * 4.2);
        int inner = ReplayPalette.alpha(factionColor, 0.92 * breathe * alpha);
        int outer = ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.18 * alpha);
        ReplayDraw.ring(glow, frame, x, y + 0.004f, z, radius * (float) (0.94 + 0.08 * breathe),
                radius * 0.18f, 28, inner, outer);
        ReplayDraw.arcXZ(glow, frame, x, y + 0.006f, z, radius * 1.18f, radius * 0.07f,
                (float) (animSec * 1.4), (float) (animSec * 1.4 + Mth.PI * 1.35f), 18,
                ReplayPalette.alpha(ReplayPalette.BRASS_LIGHT, 0.85 * alpha),
                ReplayPalette.alpha(ReplayPalette.BRASS, 0.1 * alpha));
        ReplayDraw.cone(glow, frame, x, y + height, z, x, y + 0.005f, z, radius * 0.92f, 20,
                ReplayPalette.alpha(factionColor, 0.03 * alpha),
                ReplayPalette.alpha(factionColor, 0.22 * breathe * alpha));
    }

    /** Computes eye position in source-relative block coordinates for a pose. */
    public static Vector3f eyePosition(double x, double y, double z, boolean sneaking, boolean crawling) {
        float h = crawling ? EYE_HEIGHT_CRAWL : sneaking ? EYE_HEIGHT_SNEAK : EYE_HEIGHT_STAND;
        return new Vector3f((float) x, (float) y + h, (float) z);
    }

    /**
     * Converts a v1 or v2 {@link ReplayTrackSampler.Pose} into a render-ready {@link PoseSpec},
     * using the sampled limb motion and resolving disguise target names.
     */
    public static PoseSpec poseFrom(ReplayBoardModel model, ReplayBoardModel.Actor actor,
                                    ReplayTrackSampler.Pose pose, double matchTick) {
        boolean alive = pose.state() == ReplayTrackSampler.State.ALIVE;
        boolean sprinting = pose.hasAction(LastMatchReplay.ACT_SPRINT) || pose.has(LastMatchReplay.SPRINT);
        boolean sneaking = pose.hasAction(LastMatchReplay.ACT_SNEAK) || pose.has(LastMatchReplay.SNEAK);
        boolean crawling = pose.hasAction(LastMatchReplay.ACT_SWIM_CRAWL);
        boolean sleeping = pose.hasStatus(LastMatchReplay.STATUS_ASLEEP) || pose.has(LastMatchReplay.ASLEEP);
        boolean usingItem = pose.hasAction(LastMatchReplay.ACT_USING_ITEM);

        boolean invisible = pose.hasStatus(LastMatchReplay.STATUS_INVISIBLE);
        boolean shielded = pose.hasStatus(LastMatchReplay.STATUS_SHIELDED);
        boolean poisoned = pose.hasStatus(LastMatchReplay.STATUS_POISONED);
        boolean psycho = pose.hasStatus(LastMatchReplay.STATUS_PSYCHO) || pose.has(LastMatchReplay.PSYCHO)
                || (model != null && model.psychoAt(actor.p.id(), matchTick));
        boolean c4Back = pose.hasStatus(LastMatchReplay.STATUS_C4_BACK);
        boolean disguised = pose.hasStatus(LastMatchReplay.STATUS_DISGUISED) || pose.has(LastMatchReplay.DISGUISED);

        UUID disguiseTargetId = pose.disguiseTargetUuid();
        String disguiseTargetName = null;
        if (disguiseTargetId != null && model != null) {
            ReplayBoardModel.Actor disguisedAs = model.participant(disguiseTargetId);
            if (disguisedAs != null) disguiseTargetName = disguisedAs.p.name();
        }

        ReplayTrackSampler.LimbMotion motion = actor.track.limbMotionAt(matchTick);
        // Advance stride by distance so switching sprint on or off cannot jump the phase.
        double walkPhase = motion.distance() * 4.0 + actor.index * 1.7;
        float deathAgeSec = alive ? 0.0f : (float) Math.max(0.0, (matchTick - pose.stateSince()) / 20.0);

        return new PoseSpec(
                actor.p.id(), actor.p.name(), actor.color, alive,
                pose.yaw(), pose.headYawDegrees(), pose.pitchDegrees(),
                motion.speed(), walkPhase, sprinting, sneaking, crawling, sleeping,
                usingItem, motion.mainSwing(), motion.offSwing(), motion.hurtFlash(),
                invisible, shielded, poisoned, psycho, c4Back,
                disguised, disguiseTargetId, disguiseTargetName,
                pose.mainHandItemId(), pose.offHandItemId(), deathAgeSec
        );
    }
}
