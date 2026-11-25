package com.habitrain.core.client.mixin;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.render.CustomTitlePanoramaRenderer;
import com.habitrain.core.client.title.HabiBootSession;
import com.habitrain.core.client.title.HabiKineticUiController;
import com.habitrain.core.client.title.HabiStationSceneRenderer;
import com.habitrain.core.client.title.HabiTitleTelemetry;
import net.exmo.sre.loading.StarRailExpressTitleScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mixin modifying {@link StarRailExpressTitleScreen}:
 * <ol>
 *   <li>Ports the full Kinetic Flat 3D v3 Station 00 READY -> 1.20s diagonal split -> Station 01
 *       2.5D interactive main menu (all 11 buttons + live real-data telemetry card + 100 BPM BGM)</li>
 *   <li>Silences upstream {@code AMBIENT_TRAIN_OUTSIDE} and bypasses duplicate continue prompt</li>
 *   <li>Customizes community entries (removes Discord, updates QQ and Bug Feedback links, adds Train Website,
 *       and falls back Mod Config to {@link ConfigMenuScreen} when ModMenu is absent)</li>
 *   <li>Preserves legacy panorama fallback when Kinetic Boot UI is disabled in client preferences</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = StarRailExpressTitleScreen.class, remap = false)
public class StarRailExpressTitleScreenMixin {

    @Shadow private boolean showChangelog;
    @Shadow private float panoramaFade;
    @Shadow private List<Object> menuEntries;
    @Shadow private int menuBaseX;
    @Shadow private int menuBaseY;
    @Shadow private float menuMaxScroll;
    @Shadow private int menuViewportTop;
    @Shadow private int menuViewportBottom;
    @Shadow @Final private static AtomicBoolean waitingForContinue;
    @Shadow public static SimpleSoundInstance ambient_sound;

    @Unique private static final int HABITRAIN$MENU_SPACING = 26;
    @Unique private static final String HABITRAIN$QQ_GROUP_URL = "https://qm.qq.com/q/SeuOMVpSk8";
    @Unique private static final String HABITRAIN$TRAIN_WEBSITE_URL = "https://www.kgpaly.cloud/train/";
    @Unique private static final String HABITRAIN$FEEDBACK_URL = HabiTitleTelemetry.FEEDBACK_ISSUES_URL;
    @Unique private static final Component HABITRAIN$QQ_ENTRY = Component.translatable("menu.sre.join_qq");
    @Unique private static final Component HABITRAIN$TRAIN_WEBSITE_ENTRY =
            Component.translatable("menu.habitrain_core.train_website");
    @Unique private static final Component HABITRAIN$DISCORD_ENTRY = Component.translatable("menu.sre.join_discord");
    @Unique private static final Component HABITRAIN$FEEDBACK_ENTRY = Component.translatable("menu.sre.feedback");
    @Unique private static final Component HABITRAIN$MOD_CONFIG_ENTRY = Component.translatable("menu.sre.mod_config");
    @Unique private static final Component HABITRAIN$ORIGINAL_ENTRY = Component.translatable("menu.sre.original");
    @Unique private static final Component HABITRAIN$QUIT_ENTRY = Component.translatable("menu.sre.quit");

    @Unique private static Field habitrain$textField;
    @Unique private static Field habitrain$onPressField;

    // ==================== 遗留回退路径 ====================

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
                    ordinal = 0),
            index = 1)
    private static String habitrain$appendApiText(String v) {
        return v + " | 哈比列车api加载中";
    }

    @Inject(
            method = "renderPanorama",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/SREClientConfig;instance()Lio/wifi/starrailexpress/SREClientConfig;",
                    shift = At.Shift.BEFORE),
            cancellable = true,
            require = 1)
    private void habitrain$renderCustomPanorama(GuiGraphics graphics, float delta, CallbackInfo ci) {
        if (!ClientVisualPreferences.isCustomTitlePanoramaEnabled()) return;

        StarRailExpressTitleScreen screen = (StarRailExpressTitleScreen) (Object) this;
        CustomTitlePanoramaRenderer.render(
                graphics, screen.width, screen.height, this.panoramaFade, delta);
        ci.cancel();
    }

    // ==================== 生命周期与 Kinetic v3 主页接管 ====================

    @Inject(method = "init", at = @At("TAIL"))
    private void habitrain$onInit(CallbackInfo ci) {
        habitrain$customizeCommunityEntries();
        if (HabiKineticUiController.isKineticTitleEnabled()) {
            waitingForContinue.set(false);
            StarRailExpressTitleScreen.voiceFadeInDuration = 0;
            HabiKineticUiController.getInstance().onTitleScreenInit(Minecraft.getInstance());
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void habitrain$onTick(CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;
        StarRailExpressTitleScreen.voiceFadeInDuration = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getSoundManager() != null && ambient_sound != null) {
            if (mc.getSoundManager().isActive(ambient_sound)) {
                mc.getSoundManager().stop(ambient_sound);
            }
        }
        ci.cancel();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void habitrain$onRender(GuiGraphics g, int mx, int my, float delta, CallbackInfo ci) {
        this.showChangelog = false;
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        // While StarRailLoadingOverlay is still covering the screen, avoid double-rendering & double-stepping
        if (mc.getOverlay() != null) {
            ci.cancel();
            return;
        }
        HabiKineticUiController.getInstance().renderTitleScreen(mc, habitrain$extractMenuItems(), mx, my);
        ci.cancel();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void habitrain$onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;
        if (HabiKineticUiController.getInstance().onTitleKeyPressed(keyCode, habitrain$extractMenuItems())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void habitrain$onCharTyped(char codePoint, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;
        if (HabiKineticUiController.getInstance().onTitleCharTyped(codePoint)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void habitrain$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;
        boolean handled = HabiKineticUiController.getInstance().onTitleMouseClicked(
                Minecraft.getInstance(), mouseX, mouseY, button, habitrain$extractMenuItems());
        cir.setReturnValue(handled);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void habitrain$onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;
        boolean handled = HabiKineticUiController.getInstance().onTitleMouseScrolled(
                Minecraft.getInstance(), mouseX, mouseY, verticalAmount);
        cir.setReturnValue(handled);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void habitrain$onMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;
        boolean handled = HabiKineticUiController.getInstance().onTitleMouseDragged(
                Minecraft.getInstance(), button, dragY);
        cir.setReturnValue(handled);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void habitrain$onMouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;
        HabiKineticUiController.getInstance().onTitleMouseReleased(button);
        cir.setReturnValue(true);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void habitrain$onRemoved(CallbackInfo ci) {
        if (!HabiKineticUiController.isKineticTitleEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getSoundManager() != null && ambient_sound != null) {
            mc.getSoundManager().stop(ambient_sound);
        }
        HabiBootSession.getInstance().onEnterSubScreen();
    }

    // ==================== 社群与扩展入口定制（菜单列表） ====================

    @Unique
    private void habitrain$customizeCommunityEntries() {
        try {
            Constructor<?> ctor = habitrain$findMenuEntryCtor();
            if (ctor == null) {
                HabiTrainCore.LOGGER.warn("[主页菜单] 找不到 MenuEntry 构造函数");
                return;
            }
            habitrain$ensureEntryFields(ctor.getDeclaringClass());

            StarRailExpressTitleScreen self = (StarRailExpressTitleScreen) (Object) this;
            boolean replacedQq = false;
            boolean removedDiscord = false;
            boolean replacedFeedback = false;
            boolean websitePresent = false;
            int qqEntryIndex = -1;

            for (int i = 0; i < menuEntries.size(); i++) {
                Object entry = menuEntries.get(i);
                Component text = (Component) habitrain$textField.get(entry);
                Runnable origPress = (Runnable) habitrain$onPressField.get(entry);

                if (HABITRAIN$DISCORD_ENTRY.equals(text)) {
                    menuEntries.remove(i--);
                    removedDiscord = true;
                    continue;
                }
                if (HABITRAIN$TRAIN_WEBSITE_ENTRY.equals(text)) {
                    websitePresent = true;
                    continue;
                }
                if (HABITRAIN$QQ_ENTRY.equals(text)) {
                    menuEntries.set(i, ctor.newInstance(
                            text,
                            (Runnable) () -> Util.getPlatform().openUri(HABITRAIN$QQ_GROUP_URL)));
                    replacedQq = true;
                    qqEntryIndex = i;
                    continue;
                }
                if (HABITRAIN$FEEDBACK_ENTRY.equals(text)) {
                    menuEntries.set(i, ctor.newInstance(
                            text,
                            (Runnable) () -> Util.getPlatform().openUri(HABITRAIN$FEEDBACK_URL)));
                    replacedFeedback = true;
                    continue;
                }
                if (HABITRAIN$MOD_CONFIG_ENTRY.equals(text) && !FabricLoader.getInstance().isModLoaded("modmenu")) {
                    menuEntries.set(i, ctor.newInstance(
                            text,
                            (Runnable) () -> Minecraft.getInstance().setScreen(new ConfigMenuScreen(self))));
                    continue;
                }
                if (HABITRAIN$ORIGINAL_ENTRY.equals(text) || HABITRAIN$QUIT_ENTRY.equals(text)) {
                    Runnable wrapped = () -> {
                        HabiBootSession.getInstance().onLeaveTitleFlow();
                        if (origPress != null) origPress.run();
                    };
                    menuEntries.set(i, ctor.newInstance(text, wrapped));
                }
            }

            if (qqEntryIndex >= 0 && !websitePresent) {
                menuEntries.add(qqEntryIndex + 1, ctor.newInstance(
                        HABITRAIN$TRAIN_WEBSITE_ENTRY,
                        (Runnable) () -> Util.getPlatform().openUri(HABITRAIN$TRAIN_WEBSITE_URL)));
            }

            for (int i = 0; i < menuEntries.size(); i++) {
                habitrain$setEntryPos(menuEntries.get(i), i, menuBaseX, menuBaseY);
            }

            int totalH = menuEntries.size() * HABITRAIN$MENU_SPACING;
            int viewH = menuViewportBottom - menuViewportTop;
            menuMaxScroll = Math.max(0, totalH - viewH);

            if (!replacedQq) {
                HabiTrainCore.LOGGER.warn("[主页菜单] 未找到 QQ 群菜单项，链接未替换且网站入口未添加");
            }
            if (!removedDiscord) {
                HabiTrainCore.LOGGER.warn("[主页菜单] 未找到 Discord 菜单项");
            }
            if (!replacedFeedback) {
                HabiTrainCore.LOGGER.warn("[主页菜单] 未找到反馈 BUG 菜单项，反馈链接未替换");
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("[主页菜单] 修改社群入口失败", e);
        }
    }

    @Unique
    private List<HabiStationSceneRenderer.MenuItemData> habitrain$extractMenuItems() {
        List<HabiStationSceneRenderer.MenuItemData> out = new ArrayList<>(menuEntries.size());
        if (menuEntries.isEmpty()) return out;
        try {
            habitrain$ensureEntryFields(menuEntries.get(0).getClass());
            for (Object entry : menuEntries) {
                Component text = (Component) habitrain$textField.get(entry);
                Runnable onPress = (Runnable) habitrain$onPressField.get(entry);
                if (text != null && onPress != null) {
                    out.add(new HabiStationSceneRenderer.MenuItemData(text, onPress));
                }
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("[主页菜单] 读取 MenuEntry 列表失败", e);
        }
        return out;
    }

    // ==================== 反射工具 ====================

    @Unique
    private static void habitrain$ensureEntryFields(Class<?> entryClass) throws NoSuchFieldException {
        if (habitrain$textField == null) {
            Field tf = entryClass.getDeclaredField("text");
            tf.setAccessible(true);
            habitrain$textField = tf;
        }
        if (habitrain$onPressField == null) {
            Field pf = entryClass.getDeclaredField("onPress");
            pf.setAccessible(true);
            habitrain$onPressField = pf;
        }
    }

    @Unique
    private static Constructor<?> habitrain$findMenuEntryCtor() {
        for (Class<?> nested : StarRailExpressTitleScreen.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("MenuEntry")) {
                try {
                    Constructor<?> ctor = nested.getDeclaredConstructor(Component.class, Runnable.class);
                    ctor.setAccessible(true);
                    return ctor;
                } catch (NoSuchMethodException e) {
                    HabiTrainCore.LOGGER.error("[主页菜单] MenuEntry 构造函数不存在", e);
                }
            }
        }
        return null;
    }

    @Unique
    private static void habitrain$setEntryPos(Object entry, int index, int baseX, int baseY) {
        try {
            Class<?> c = entry.getClass();
            habitrain$setInt(c, entry, "x", baseX);
            habitrain$setInt(c, entry, "y", baseY + index * HABITRAIN$MENU_SPACING);
            habitrain$setInt(c, entry, "index", index);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("[主页菜单] 设置条目坐标失败 index={}", index, e);
        }
    }

    @Unique
    private static void habitrain$setInt(Class<?> clz, Object obj, String name, int val) throws Exception {
        Field f = clz.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(obj, val);
    }
}
