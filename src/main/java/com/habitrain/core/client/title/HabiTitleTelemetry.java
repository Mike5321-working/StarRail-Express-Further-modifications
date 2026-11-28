package com.habitrain.core.client.title;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.config.ClientVisualPreferences;
import io.wifi.starrailexpress.SRE;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.client.SREClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.sounds.SoundSource;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects real, live Minecraft / Fabric / SRE / HabiTrain Core telemetry for the
 * Station 01 main menu buttons and bottom-left dynamic telemetry card.
 *
 * <p>Disk I/O (reading {@code servers.dat} and scanning save folders) is executed once
 * asynchronously on a background worker and refreshed safely when reopening the title screen,
 * so the title screen render loop never stalls.</p>
 */
@Environment(EnvType.CLIENT)
public final class HabiTitleTelemetry {

    public record MenuVisualMeta(
            String enSub,
            String tag,
            String cardKicker,
            String cardCode,
            String cardTitle,
            String cardDesc,
            String meterLabel,
            String meterRight,
            int meterPercent
    ) {}

    public static final String FEEDBACK_ISSUES_URL =
            "https://github.com/Mike5321-working/StarRail-Express-Further-modifications/issues";

    private static final long CLIENT_START_MS = System.currentTimeMillis();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);

    private static volatile int savedServerCount = -1;
    private static volatile int localWorldCount = -1;
    private static volatile long lastDiskRefreshMs = 0L;

    private HabiTitleTelemetry() {}

    /** Pushes a freshly loaded saved-server count so main-menu telemetry updates immediately. */
    public static void updateCachedServerCount(int count) {
        if (count >= 0) {
            savedServerCount = count;
            lastDiskRefreshMs = System.currentTimeMillis();
        }
    }

    /** Pushes a freshly loaded local-world count so main-menu telemetry updates immediately. */
    public static void updateCachedWorldCount(int count) {
        if (count >= 0) {
            localWorldCount = count;
            lastDiskRefreshMs = System.currentTimeMillis();
        }
    }

    /** Triggers a non-blocking refresh of disk-backed counts (saved servers + local worlds). */
    public static void refreshAsync(Minecraft mc) {
        if (mc == null) return;
        long now = System.currentTimeMillis();
        if (now - lastDiskRefreshMs < 4_000L && savedServerCount >= 0 && localWorldCount >= 0) {
            return;
        }
        if (!REFRESHING.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                try {
                    ServerList list = new ServerList(mc);
                    list.load();
                    savedServerCount = list.size();
                } catch (Throwable t) {
                    if (savedServerCount < 0) savedServerCount = 0;
                }
                try {
                    if (mc.getLevelSource() != null) {
                        localWorldCount = mc.getLevelSource().findLevelCandidates().levels().size();
                    }
                } catch (Throwable t) {
                    if (localWorldCount < 0) localWorldCount = 0;
                }
                lastDiskRefreshMs = System.currentTimeMillis();
            } finally {
                REFRESHING.set(false);
            }
        }, net.minecraft.Util.ioPool());
    }

    public static int getLoadedModCount() {
        try {
            return FabricLoader.getInstance().getAllMods().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int getRegisteredRoleCount() {
        try {
            return TMMRoles.ROLES.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String getCoreVersion() {
        try {
            return FabricLoader.getInstance().getModContainer(HabiTrainCore.MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("2.0");
        } catch (Throwable t) {
            return "2.0";
        }
    }

    public static String getSreVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("starrailexpress")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("4.3.0");
        } catch (Throwable t) {
            return "4.3.0";
        }
    }

    public static String getFooterVersionLine() {
        return "SRE " + getSreVersion() + " / " + SRE.modPacketVersion
                + "  //  HABITRAIN CORE " + getCoreVersion();
    }

    public static boolean hasCustomSkinLoaderWarning() {
        try {
            return SREClient.hasCustomSkinLoaderAndNeedToWarn;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int getHeapUsagePercent() {
        Runtime rt = Runtime.getRuntime();
        long max = Math.max(1L, rt.maxMemory());
        long used = Math.max(0L, rt.totalMemory() - rt.freeMemory());
        return (int) Math.max(1, Math.min(100, Math.round(used * 100.0 / max)));
    }

    public static long getHeapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return Math.max(0L, (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L));
    }

    public static long getHeapMaxMb() {
        return Math.max(1L, Runtime.getRuntime().maxMemory() / (1024L * 1024L));
    }

    public static String getUptimeFormatted() {
        long sec = Math.max(0L, (System.currentTimeMillis() - CLIENT_START_MS) / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", sec / 60L, sec % 60L);
    }

    /** Builds live, real-data metadata &amp; telemetry card contents for any title menu entry. */
    public static MenuVisualMeta describeEntry(Minecraft mc, Component entryText) {
        String key = (entryText != null && entryText.getContents() instanceof TranslatableContents tc)
                ? tc.getKey() : "";
        String label = entryText == null ? "" : entryText.getString();
        String player = (mc != null && mc.getUser() != null) ? mc.getUser().getName() : "PASSENGER";
        int mods = getLoadedModCount();
        int servers = Math.max(0, savedServerCount);
        int worlds = Math.max(0, localWorldCount);
        int heapPct = getHeapUsagePercent();
        long heapUsed = getHeapUsedMb();
        long heapMax = getHeapMaxMb();

        if ("menu.sre.multiplayer".equals(key) || label.contains("多人")) {
            String srvTag = savedServerCount < 0 ? "SCANNING" : (servers + " SERVERS");
            int pct = Math.min(100, Math.max(18, servers * 20));
            return new MenuVisualMeta(
                    "MULTIPLAYER", srvTag,
                    "ACTIVE CARGO // SERVER DISPATCH", "ID: " + player.toUpperCase(Locale.ROOT),
                    "多人联机大厅 · 站台检票登车",
                    "当前乘客账号 " + player + "，本地已登记 " + servers + " 台联机服务器。选择目标车次即可接入对局。",
                    "SERVERS", servers + " READY", pct
            );
        }
        if ("menu.sre.singleplayer".equals(key) || label.contains("单人")) {
            String wTag = localWorldCount < 0 ? "SCANNING" : (worlds + " WORLDS");
            int pct = Math.min(100, Math.max(16, worlds * 15));
            return new MenuVisualMeta(
                    "SINGLEPLAYER", wTag,
                    "LOCAL ROUTE // ARCHIVE", worlds + " SAVES",
                    "单人轨道存档 · 本地世界",
                    "本地存档库检测到 " + worlds + " 个单人世界（Minecraft 1.21.1）。可创建新线路或继续既有旅程。",
                    "WORLDS", worlds + " LOCAL", pct
            );
        }
        if ("menu.sre.join_qq".equals(key) || label.contains("QQ")) {
            return new MenuVisualMeta(
                    "QQ COMMUNITY", "GROUP",
                    "SIGNAL // COMMUNITY LINK", "QM.QQ.COM",
                    "加入哈比列车玩家 QQ 群",
                    "点击在浏览器打开官方 QQ 群邀请链接（qm.qq.com/q/SeuOMVpSk8），获取更新通知与联机车队。",
                    "CHANNEL", "ONLINE", 100
            );
        }
        if ("menu.habitrain_core.train_website".equals(key) || label.contains("网站")) {
            return new MenuVisualMeta(
                    "TRAIN WEBSITE", "PORTAL",
                    "PORTAL // WEB TERMINAL", "KGPALY.CLOUD",
                    "访问哈比列车小网站",
                    "点击打开 https://www.kgpaly.cloud/train/ 查看公告、指南与哈比列车在线服务。",
                    "PORTAL", "HTTPS", 100
            );
        }
        if ("menu.sre.feedback".equals(key) || label.contains("反馈")) {
            return new MenuVisualMeta(
                    "ISSUE REPORT", "GITHUB",
                    "DIAGNOSTICS // FEEDBACK", "SRE " + getSreVersion(),
                    "反馈 BUG 与改进建议",
                    "前往哈比列车 Issues 仓库（Mike5321-working/StarRail-Express-Further-modifications）提交问题报告；当前核心版本 Core "
                            + getCoreVersion() + "。",
                    "TRACKER", "GITHUB", 100
            );
        }
        if ("menu.sre.role_introduction".equals(key) || label.contains("职业")) {
            int roles = getRegisteredRoleCount();
            return new MenuVisualMeta(
                    "ROLES CODEX", roles + " ROLES",
                    "PASSENGER ROSTER // CODEX", roles + " LOADED",
                    "列车职业档案 · 全阵营图鉴",
                    "当前客户端已真实注册 " + roles + " 个可用职业（含上游星穹列车与哈比列车扩展职业），支持按模式筛选查看。",
                    "CODEX", roles + " ROLES", Math.min(100, Math.max(30, roles * 2))
            );
        }
        if ("menu.sre.options".equals(key) || label.contains("选项")) {
            int winW = mc != null && mc.getWindow() != null ? mc.getWindow().getWidth() : 1920;
            int winH = mc != null && mc.getWindow() != null ? mc.getWindow().getHeight() : 1080;
            int packs = mc != null && mc.getResourcePackRepository() != null
                    ? mc.getResourcePackRepository().getSelectedPacks().size() : 0;
            int masterPct = mc != null && mc.options != null
                    ? Math.round(mc.options.getSoundSourceVolume(SoundSource.MASTER) * 100f) : 100;
            String lang = mc != null && mc.getLanguageManager() != null
                    ? mc.getLanguageManager().getSelected().toUpperCase(Locale.ROOT) : "ZH_CN";
            return new MenuVisualMeta(
                    "MC OPTIONS", winW + "x" + winH,
                    "ENGINE // CLIENT OPTIONS", lang,
                    "Minecraft 图像、音频与按键选项",
                    "当前窗口 " + winW + "×" + winH + " · 已启用 " + packs + " 个资源包 · 主音量 " + masterPct + "% · 实时帧率 "
                            + (mc != null ? mc.getFps() : 60) + " FPS。",
                    "MASTER VOL", masterPct + "%", masterPct
            );
        }
        if ("menu.sre.train_options".equals(key) || label.contains("列车模组")) {
            return new MenuVisualMeta(
                    "TRAIN CONFIG", "SRE " + getSreVersion(),
                    "LOCOMOTIVE // SRE CLIENT", "PACKET " + SRE.modPacketVersion,
                    "星穹列车客户端专项设置",
                    "调整 HUD 偏移、耐力条样式、性能模式及主界面动画开关。当前加载版本 StarRailExpress " + getSreVersion() + "。",
                    "LOCOMOTIVE", "READY", 100
            );
        }
        if ("menu.sre.mod_config".equals(key) || label.contains("模组设置")) {
            boolean hasModMenu = FabricLoader.getInstance().isModLoaded("modmenu");
            return new MenuVisualMeta(
                    hasModMenu ? "MOD MENU" : "CORE CONSOLE", mods + " MODS",
                    "CARGO MANIFEST // FABRIC", "HEAP " + heapUsed + "/" + heapMax + "MB",
                    hasModMenu ? "模组列表与哈比核心控制台" : "哈比列车核心控制台",
                    "Fabric Loader 已装载 " + mods + " 个模组（含 HabiTrain Core " + getCoreVersion()
                            + "）。可在「游戏外→主菜单」调整平面立体 UI 与自动进站偏好。",
                    "JVM HEAP", heapPct + "%", heapPct
            );
        }
        if ("menu.sre.original".equals(key) || label.contains("原版")) {
            return new MenuVisualMeta(
                    "VANILLA MENU", "LEGACY",
                    "FALLBACK // VANILLA SCREEN", "MC 1.21.1",
                    "返回原版 Minecraft 标题界面",
                    "临时切回原版标题菜单（不影响配置；重新回到标题页后仍遵循主菜单偏好设置）。",
                    "AUTO-ENTER", ClientVisualPreferences.isKineticUiAutoEnter() ? "ON" : "OFF",
                    ClientVisualPreferences.isKineticUiAutoEnter() ? 100 : 35
            );
        }
        if ("menu.sre.quit".equals(key) || label.contains("退出")) {
            String up = getUptimeFormatted();
            return new MenuVisualMeta(
                    "QUIT GAME", "EXIT",
                    "TERMINAL // SHUTDOWN", "UPTIME " + up,
                    "安全断开并退出游戏",
                    "本次客户端已连续运行 " + up + "，当前 JVM 占用 " + heapUsed + " MB / " + heapMax + " MB。点击安全释放音频与显存后退出。",
                    "JVM HEAP", heapPct + "%", heapPct
            );
        }

        // Generic fallback for any extra mod-added menu button
        return new MenuVisualMeta(
                "STATION ACTION", "MODULE",
                "TERMINAL // CUSTOM ENTRY", mods + " MODS",
                label.isEmpty() ? "站台扩展入口" : label,
                "由已安装模组注册的主菜单扩展入口，点击立即执行对应功能。",
                "STATUS", "READY", 100
        );
    }
}
