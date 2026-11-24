package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public final class ReplayFirstPersonHands {
    private static final ThreadLocal<RenderPlayer> ACTIVE = new ThreadLocal<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayFirstPersonHands.class);
    private static @Nullable RenderPlayer cachedPlayer;

    private ReplayFirstPersonHands() {}

    public interface VanillaRenderer {
        void habitrain$setRecordedMainHand(ItemStack item);
        void habitrain$setRecordedOffHand(ItemStack item);
        void habitrain$renderArmWithItem(AbstractClientPlayer player, float partialTick, float pitch,
                                         InteractionHand hand, float swing, ItemStack item, float equip,
                                         PoseStack poses, MultiBufferSource buffers, int light);
    }

    public static @Nullable LocalPlayer activePlayer(ItemInHandRenderer renderer) {
        RenderPlayer player = ACTIVE.get();
        return player != null && player.renderer == renderer ? player : null;
    }

    static void reset() {
        cachedPlayer = null;
    }

    static void render(Minecraft client, PoseStack poses, MultiBufferSource buffers,
                       ReplayFullPlayerRenderer.PoseSpec spec, ReplayTrackSampler.Pose recorded,
                       double matchTick, int light, boolean bobView) {
        if (!spec.alive() || client.player == null || client.level == null) return;
        if (cachedPlayer == null || cachedPlayer.level() != client.level) {
            cachedPlayer = new RenderPlayer(client, client.player);
        }
        RenderPlayer player = cachedPlayer;
        player.skin = ReplayHeadSkins.skin(spec.actorId(), spec.actorName());
        player.arm = recorded.hasAction(LastMatchReplay.ACT_LEFT_HANDED) ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        player.hidden = spec.invisible();
        player.main = ReplayFullPlayerRenderer.resolveItem(spec.mainHandItemId());
        player.off = ReplayFullPlayerRenderer.resolveItem(spec.offHandItemId());
        player.usedHand = recorded.hasAction(LastMatchReplay.ACT_USING_OFF_HAND)
                || player.main.isEmpty() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        int recordedRemaining = recorded.actionFlags() >>> LastMatchReplay.ACT_USE_REMAINING_SHIFT;
        player.remaining = 0;
        if (spec.usingItem() && !player.getUseItem().isEmpty()) {
            player.remaining = recordedRemaining > 0 ? recordedRemaining
                    : Math.max(1, player.getUseItem().getUseDuration(player) - 1);
        }

        if (bobView && recorded.hasAction(LastMatchReplay.ACT_ON_GROUND)) {
            float stride = (float) spec.walkPhase();
            float amplitude = (float) Math.min(1.0, spec.speedBlocksPerTick() / 0.18) * 0.24f;
            poses.translate(Mth.sin(stride) * amplitude * 0.5f, -Math.abs(Mth.cos(stride) * amplitude), 0);
            poses.mulPose(Axis.ZP.rotationDegrees(Mth.sin(stride) * amplitude * 3));
            poses.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.cos(stride - 0.2f) * amplitude) * 5));
        }

        VanillaRenderer vanilla = (VanillaRenderer) player.renderer;
        vanilla.habitrain$setRecordedMainHand(player.main);
        vanilla.habitrain$setRecordedOffHand(player.off);
        InteractionHand onlyHand = onlyHand(player);
        float partialTick = (float) (matchTick - Math.floor(matchTick));
        ACTIVE.set(player);
        boolean restoreFirstPerson = false;
        try {
            restoreFirstPerson = FirstPersonModel.suspend();
            if (onlyHand != InteractionHand.OFF_HAND) {
                vanilla.habitrain$renderArmWithItem(player, partialTick, spec.pitchDeg(), InteractionHand.MAIN_HAND,
                        spec.mainSwingProgress(), player.main, 0f, poses, buffers, light);
            }
            if (onlyHand != InteractionHand.MAIN_HAND) {
                vanilla.habitrain$renderArmWithItem(player, partialTick, spec.pitchDeg(), InteractionHand.OFF_HAND,
                        spec.offSwingProgress(), player.off, 0f, poses, buffers, light);
            }
        } finally {
            ACTIVE.remove();
            if (restoreFirstPerson) FirstPersonModel.restore();
        }
    }

    private static @Nullable InteractionHand onlyHand(RenderPlayer player) {
        if (player.isUsingItem()) {
            UseAnim animation = player.getUseItem().getUseAnimation();
            if (animation == UseAnim.BOW || animation == UseAnim.CROSSBOW) return player.getUsedItemHand();
            if (player.getUsedItemHand() == InteractionHand.MAIN_HAND && chargedCrossbow(player.off)) {
                return InteractionHand.MAIN_HAND;
            }
        } else if (chargedCrossbow(player.main)) {
            return InteractionHand.MAIN_HAND;
        }
        return null;
    }

    private static boolean chargedCrossbow(ItemStack item) {
        return item.is(Items.CROSSBOW) && CrossbowItem.isCharged(item);
    }

    private record FirstPersonModel(Method enabled, Method toggle) {
        private static @Nullable FirstPersonModel compatibility = find();

        private static @Nullable FirstPersonModel find() {
            if (!FabricLoader.getInstance().isModLoaded("firstperson")) return null;
            try {
                Class<?> api = Class.forName("dev.tr7zw.firstperson.api.FirstPersonAPI");
                return new FirstPersonModel(api.getMethod("isEnabled"), api.getMethod("setEnabled", boolean.class));
            } catch (ReflectiveOperationException | LinkageError failure) {
                LOGGER.warn("First-person model compatibility is unavailable for replay export", failure);
                return null;
            }
        }

        static boolean suspend() {
            if (compatibility == null) return false;
            try {
                if (!(boolean) compatibility.enabled.invoke(null)) return false;
                compatibility.toggle.invoke(null, false);
                return true;
            } catch (ReflectiveOperationException failure) {
                LOGGER.warn("Could not suspend the first-person model during replay hand rendering", failure);
                compatibility = null;
                return false;
            }
        }

        static void restore() {
            try {
                compatibility.toggle.invoke(null, true);
            } catch (ReflectiveOperationException failure) {
                LOGGER.warn("Could not restore the first-person model after replay hand rendering", failure);
                compatibility = null;
            }
        }
    }

    private static final class RenderPlayer extends LocalPlayer {
        private final ItemInHandRenderer renderer;
        private PlayerSkin skin;
        private HumanoidArm arm = HumanoidArm.RIGHT;
        private InteractionHand usedHand = InteractionHand.MAIN_HAND;
        private ItemStack main = ItemStack.EMPTY;
        private ItemStack off = ItemStack.EMPTY;
        private int remaining;
        private boolean hidden;

        private RenderPlayer(Minecraft client, LocalPlayer current) {
            super(client, current.clientLevel, current.connection, current.getStats(), current.getRecipeBook(), false, false);
            renderer = new ItemInHandRenderer(client, client.getEntityRenderDispatcher(), client.getItemRenderer());
            skin = current.getSkin();
        }

        @Override public PlayerSkin getSkin() { return skin == null ? super.getSkin() : skin; }
        @Override public HumanoidArm getMainArm() { return arm; }
        @Override public ItemStack getMainHandItem() { return main; }
        @Override public ItemStack getOffhandItem() { return off; }
        @Override public boolean isInvisible() { return hidden; }
        @Override public boolean isModelPartShown(PlayerModelPart part) { return true; }
        @Override public boolean isUsingItem() { return remaining > 0; }
        @Override public InteractionHand getUsedItemHand() { return usedHand; }
        @Override public ItemStack getUseItem() { return usedHand == InteractionHand.MAIN_HAND ? main : off; }
        @Override public int getUseItemRemainingTicks() { return remaining; }
    }
}
