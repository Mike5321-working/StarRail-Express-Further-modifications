package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.RoundWelcomeHudRenderer;
import io.wifi.starrailexpress.api.GameMode;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.client.gui.MapDetailsRenderer;
import io.wifi.starrailexpress.client.gui.RoleAnnouncementTexts;
import io.wifi.starrailexpress.client.gui.RoundTextRenderer;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.utils.client.betterrender.FakeGuiGraphics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管并美化上游 {@link RoundTextRenderer#renderHud} 对局开始中心欢迎报幕（{@code welcomeTime > 0}）。
 *
 * <p>严格保留上游 {@code OptimizedTextRenderer.INSTANCE.isTickDirty()} 节流、
 * {@link MapDetailsRenderer#renderHud} 调用时机、{@code isLooseEnds} 分支，
 * 以及对局结束（{@code endTime > 0}）全部原有逻辑。</p>
 */
@Mixin(value = RoundTextRenderer.class, remap = false)
public abstract class RoundWelcomeHudMixin {

    @Shadow
    @Final
    private static int WELCOME_DURATION;

    @Shadow
    private static RoleAnnouncementTexts.RoleAnnouncementText roleTexts;

    @Shadow
    public static int welcomeTime;

    @Shadow
    public static int killers;

    @Shadow
    public static int targets;

    @Shadow
    private static Component cachedWelcomeText;

    @Shadow
    private static Component cachedPremiseText;

    @Shadow
    private static Component cachedGoalText;

    @Shadow
    private static Component cachedCanJumpTip;

    @Shadow
    private static int cachedWelcomeWidth;

    @Shadow
    private static int cachedPremiseWidth;

    @Shadow
    private static int cachedGoalWidth;

    @Shadow
    private static int cachedCanJumpWidth;

    @Shadow
    private static int lastKillers;

    @Shadow
    private static int lastTargets;

    @Shadow
    private static boolean lastCanJump;

    @Shadow
    private static Component copyright;

    @Shadow
    private static int copyrightWidth;

    @Inject(
            method = "renderHud",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/api/GameMode;isLooseEndMode()Z"
            ),
            remap = false
    )
    private static void habitrain$renderBeautifiedWelcomeHud(
            Font renderer,
            Minecraft client,
            LocalPlayer player,
            FakeGuiGraphics context,
            float partialTicks,
            CallbackInfo ci
    ) {
        if (welcomeTime <= 0 || roleTexts == null || player == null) {
            return;
        }

        if (welcomeTime <= WELCOME_DURATION - GameConstants.FADE_TIME + 15) {
            MapDetailsRenderer.renderHud(renderer, player, context, partialTicks);
        }

        GameMode gamemode = SREGameWorldComponent.KEY.get(player.level()).getGameMode();
        boolean isLooseEnds = gamemode.isLooseEndMode();

        if (lastKillers != killers || lastTargets != targets || cachedWelcomeText == null) {
            cachedWelcomeText = isLooseEnds
                    ? Component.translatable("announcement.star.loose_ends.welcome")
                    : roleTexts.welcomeText;
            cachedPremiseText = isLooseEnds
                    ? Component.translatable("announcement.star.loose_ends.premise")
                    : roleTexts.premiseText.apply(killers);
            cachedGoalText = isLooseEnds
                    ? Component.translatable("announcement.star.loose_ends.goal")
                    : roleTexts.goalText.apply(targets);
            cachedWelcomeWidth = renderer.width(cachedWelcomeText);
            cachedPremiseWidth = renderer.width(cachedPremiseText);
            cachedGoalWidth = renderer.width(cachedGoalText);
            lastKillers = killers;
            lastTargets = targets;
        }

        boolean canJump = SREClient.gameComponent != null && SREClient.gameComponent.isJumpAvailable();
        if (lastCanJump != canJump || cachedCanJumpTip == null) {
            cachedCanJumpTip = canJump
                    ? Component.translatable("announcement.star.tip.can_jump").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("announcement.star.tip.cant_jump").withStyle(ChatFormatting.YELLOW);
            cachedCanJumpWidth = renderer.width(cachedCanJumpTip);
            lastCanJump = canJump;
        }

        int accentRgb = RoundWelcomeHudRenderer.resolveAccentRgb(isLooseEnds, roleTexts.colour);
        int baseTextRgb = RoundWelcomeHudRenderer.resolveBaseTextRgb(isLooseEnds);

        RoundWelcomeHudRenderer.renderWelcomeHud(
                renderer,
                context,
                welcomeTime,
                cachedWelcomeText,
                cachedWelcomeWidth,
                cachedPremiseText,
                cachedPremiseWidth,
                cachedGoalText,
                cachedGoalWidth,
                cachedCanJumpTip,
                cachedCanJumpWidth,
                copyright,
                copyrightWidth,
                accentRgb,
                baseTextRgb
        );
    }

    @Redirect(
            method = "renderHud",
            at = @At(
                    value = "FIELD",
                    target = "Lio/wifi/starrailexpress/client/gui/RoundTextRenderer;welcomeTime:I",
                    opcode = Opcodes.GETSTATIC,
                    ordinal = 0
            ),
            remap = false
    )
    private static int habitrain$skipOriginalWelcomeBranch() {
        return 0;
    }
}
