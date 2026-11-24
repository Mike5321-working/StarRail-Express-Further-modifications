package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayFirstPersonHands;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandRenderer.class)
public abstract class ReplayItemInHandRendererMixin implements ReplayFirstPersonHands.VanillaRenderer {
    @Override
    @Accessor("mainHandItem")
    public abstract void habitrain$setRecordedMainHand(ItemStack item);

    @Override
    @Accessor("offHandItem")
    public abstract void habitrain$setRecordedOffHand(ItemStack item);

    @Override
    @Invoker("renderArmWithItem")
    public abstract void habitrain$renderArmWithItem(AbstractClientPlayer player, float partialTick, float pitch,
                                                     InteractionHand hand, float swing, ItemStack item, float equip,
                                                     PoseStack poses, MultiBufferSource buffers, int light);

    @WrapOperation(method = {"renderPlayerArm", "renderMapHand", "renderTwoHandedMap", "renderOneHandedMap"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer habitrain$useRecordedArmPlayer(Minecraft client, Operation<LocalPlayer> original) {
        LocalPlayer replayPlayer = ReplayFirstPersonHands.activePlayer((ItemInHandRenderer) (Object) this);
        return replayPlayer != null ? replayPlayer : original.call(client);
    }

}
