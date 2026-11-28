package com.habitrain.core.client.title;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.NoticeWithLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.jetbrains.annotations.Nullable;

import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous data loader, live {@link ServerStatusPinger} / LAN scanner, local world scanner,
 * and join / native-editor launcher for the inline Station 01 Multiplayer &amp; Singleplayer panels.
 */
@Environment(EnvType.CLIENT)
public final class HabiTitleSubPanelData {

    private static final DateTimeFormatter WORLD_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    public record ServerEntry(ServerData data, boolean isLan) {
        public String displayName() {
            String raw = data != null ? sanitizeSingleLine(data.name) : "";
            if (!raw.isEmpty()) return raw;
            String ip = data != null ? sanitizeSingleLine(data.ip) : "";
            return ip.isEmpty() ? "未命名服务器" : ip;
        }

        public String address() {
            return data != null ? sanitizeSingleLine(data.ip) : "";
        }

        public String motdPlain() {
            if (data == null || data.motd == null) return "";
            return sanitizeSingleLine(data.motd.getString());
        }

        public ServerData.State state() {
            if (isLan) return ServerData.State.SUCCESSFUL;
            return data != null ? data.state() : ServerData.State.INITIAL;
        }

        public boolean isCompatible() {
            if (isLan || data == null) return true;
            if (data.state() == ServerData.State.INCOMPATIBLE) return false;
            if (data.state() != ServerData.State.SUCCESSFUL) return true;
            try {
                return data.protocol == SharedConstants.getCurrentVersion().getProtocolVersion();
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    public record WorldEntry(
            LevelSummary summary,
            String levelId,
            String displayName,
            String lastPlayedText,
            String gameModeTag,
            String gameModeCn,
            String versionName,
            boolean hardcore,
            boolean hasCommands,
            boolean playable,
            boolean locked,
            boolean corrupted,
            boolean symlink,
            boolean backupRecommended,
            String statusTag,
            String infoPlain
    ) {}

    public enum ServerPingVisual {
        ONLINE,
        PINGING,
        OFFLINE,
        INCOMPATIBLE,
        LAN
    }

    private final ServerStatusPinger pinger = new ServerStatusPinger();
    private final AtomicInteger serverGeneration = new AtomicInteger(0);
    private final AtomicInteger worldGeneration = new AtomicInteger(0);
    private final List<ServerEntry> savedServers = new CopyOnWriteArrayList<>();
    private final List<ServerEntry> lanServers = new CopyOnWriteArrayList<>();
    private final List<WorldEntry> worlds = new CopyOnWriteArrayList<>();

    private @Nullable ExecutorService pingExecutor;
    private @Nullable LanServerDetection.LanServerList lanServerList;
    private @Nullable LanServerDetection.LanServerDetector lanServerDetector;

    private volatile boolean serversLoading;
    private volatile boolean worldsLoading;
    private volatile boolean serversLoadedOnce;
    private volatile boolean worldsLoadedOnce;
    private volatile @Nullable String worldsErrorMessage;
    private volatile boolean asyncArrivalDirty;

    public boolean isServersLoading() { return serversLoading; }
    public boolean isWorldsLoading() { return worldsLoading; }
    public boolean hasServersLoadedOnce() { return serversLoadedOnce; }
    public boolean hasWorldsLoadedOnce() { return worldsLoadedOnce; }
    public @Nullable String getWorldsErrorMessage() { return worldsErrorMessage; }

    /** Consumes the one-shot flag indicating newly arrived async server/world rows. */
    public boolean consumeAsyncArrivalDirty() {
        boolean v = asyncArrivalDirty;
        asyncArrivalDirty = false;
        return v;
    }

    /** Returns combined snapshot of saved servers followed by live LAN servers. */
    public List<ServerEntry> getServerEntries() {
        int total = savedServers.size() + lanServers.size();
        if (total == 0) return Collections.emptyList();
        List<ServerEntry> out = new ArrayList<>(total);
        out.addAll(savedServers);
        out.addAll(lanServers);
        return out;
    }

    public List<WorldEntry> getWorldEntries() {
        if (worlds.isEmpty()) return Collections.emptyList();
        return List.copyOf(worlds);
    }

    // ── Multiplayer Loading, Live Ping & LAN Discovery ───────────────────────

    public void beginMultiplayerLoad(@Nullable Minecraft mc) {
        stopMultiplayerNetworking();
        if (mc == null) return;

        int gen = serverGeneration.incrementAndGet();
        serversLoading = true;
        lanServers.clear();
        startLanDetection();

        Util.ioPool().execute(() -> {
            List<ServerEntry> loaded = new ArrayList<>();
            try {
                ServerList list = new ServerList(mc);
                list.load();
                for (int i = 0; i < list.size(); i++) {
                    ServerData sd = list.get(i);
                    if (sd != null) {
                        sd.setState(ServerData.State.INITIAL);
                        loaded.add(new ServerEntry(sd, false));
                    }
                }
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Station01] Failed to load servers.dat", t);
            }
            if (serverGeneration.get() != gen) return;

            savedServers.clear();
            savedServers.addAll(loaded);
            serversLoading = false;
            serversLoadedOnce = true;
            asyncArrivalDirty = true;
            HabiTitleTelemetry.updateCachedServerCount(loaded.size());

            // Schedule live ping for each saved server on our bounded daemon pool
            for (ServerEntry entry : loaded) {
                schedulePing(mc, entry.data(), gen);
            }
        });
    }

    private void startLanDetection() {
        try {
            lanServerList = new LanServerDetection.LanServerList();
            lanServerDetector = new LanServerDetection.LanServerDetector(lanServerList);
            lanServerDetector.setDaemon(true);
            lanServerDetector.start();
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.debug("[Station01] LAN detector unavailable: {}", t.getMessage());
            lanServerList = null;
            lanServerDetector = null;
        }
    }

    private synchronized ExecutorService ensurePingExecutor() {
        if (pingExecutor == null || pingExecutor.isShutdown()) {
            AtomicInteger idx = new AtomicInteger(1);
            pingExecutor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "habitrain-server-ping-" + idx.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }
        return pingExecutor;
    }

    private void schedulePing(Minecraft mc, ServerData data, int gen) {
        if (data == null || data.ip == null || data.ip.isBlank()) return;
        data.setState(ServerData.State.PINGING);
        data.ping = -1L;
        data.motd = Component.literal("正在连接状态...");
        data.status = Component.empty();
        try {
            ensurePingExecutor().execute(() -> {
                if (serverGeneration.get() != gen) return;
                try {
                    pinger.pingServer(
                            data,
                            () -> {},
                            () -> {
                                if (serverGeneration.get() != gen) return;
                                if (data.state() == ServerData.State.PINGING) {
                                    data.setState(ServerData.State.SUCCESSFUL);
                                }
                            }
                    );
                } catch (UnknownHostException e) {
                    if (serverGeneration.get() != gen) return;
                    data.ping = -1L;
                    data.setState(ServerData.State.UNREACHABLE);
                    data.motd = Component.translatable("multiplayer.status.cannot_resolve");
                } catch (Throwable e) {
                    if (serverGeneration.get() != gen) return;
                    data.ping = -1L;
                    data.setState(ServerData.State.UNREACHABLE);
                    data.motd = Component.translatable("multiplayer.status.cannot_connect");
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Ticks active server status pingers and polls discovered LAN servers (20Hz client tick). */
    public void tickMultiplayer(@Nullable Minecraft mc) {
        if (mc == null) return;
        try {
            pinger.tick();
        } catch (Throwable ignored) {
        }
        LanServerDetection.LanServerList list = this.lanServerList;
        if (list != null) {
            try {
                List<LanServer> dirty = list.takeDirtyServers();
                if (dirty != null) {
                    List<ServerEntry> updatedLan = new ArrayList<>(dirty.size());
                    for (LanServer lan : dirty) {
                        ServerData sd = new ServerData(lan.getMotd(), lan.getAddress(), ServerData.Type.LAN);
                        sd.setState(ServerData.State.SUCCESSFUL);
                        sd.motd = Component.literal("局域网联机世界");
                        updatedLan.add(new ServerEntry(sd, true));
                    }
                    lanServers.clear();
                    lanServers.addAll(updatedLan);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void stopMultiplayerNetworking() {
        serverGeneration.incrementAndGet();
        serversLoading = false;
        try {
            pinger.removeAll();
        } catch (Throwable ignored) {
        }
        LanServerDetection.LanServerDetector detector = this.lanServerDetector;
        this.lanServerDetector = null;
        this.lanServerList = null;
        if (detector != null) {
            try {
                detector.interrupt();
            } catch (Throwable ignored) {
            }
        }
    }

    public void shutdown() {
        stopMultiplayerNetworking();
        worldGeneration.incrementAndGet();
        worldsLoading = false;
        synchronized (this) {
            if (pingExecutor != null) {
                pingExecutor.shutdownNow();
                pingExecutor = null;
            }
        }
    }

    // ── Singleplayer World Scanning ──────────────────────────────────────────

    public void beginSingleplayerLoad(@Nullable Minecraft mc) {
        if (mc == null || mc.getLevelSource() == null) return;
        int gen = worldGeneration.incrementAndGet();
        worldsLoading = true;
        worldsErrorMessage = null;

        Util.ioPool().execute(() -> {
            try {
                LevelStorageSource source = mc.getLevelSource();
                LevelStorageSource.LevelCandidates candidates = source.findLevelCandidates();
                source.loadLevelSummaries(candidates).whenComplete((summaries, err) -> {
                    if (worldGeneration.get() != gen) return;
                    if (err != null) {
                        HabiTrainCore.LOGGER.warn("[Station01] Failed to load world summaries", err);
                        worldsErrorMessage = "读取本地存档列表失败，请点击底部「编辑」进入原生页面检查";
                        worldsLoading = false;
                        worldsLoadedOnce = true;
                        return;
                    }
                    List<WorldEntry> out = new ArrayList<>();
                    if (summaries != null) {
                        for (LevelSummary s : summaries) {
                            if (s != null) {
                                out.add(fromLevelSummary(s));
                            }
                        }
                    }
                    worlds.clear();
                    worlds.addAll(out);
                    worldsLoading = false;
                    worldsLoadedOnce = true;
                    asyncArrivalDirty = true;
                    HabiTitleTelemetry.updateCachedWorldCount(out.size());
                });
            } catch (Throwable t) {
                if (worldGeneration.get() != gen) return;
                HabiTrainCore.LOGGER.warn("[Station01] Failed to scan level candidates", t);
                worldsErrorMessage = "扫描本地存档目录失败，请点击底部「编辑」进入原生页面检查";
                worldsLoading = false;
                worldsLoadedOnce = true;
            }
        });
    }

    private static WorldEntry fromLevelSummary(LevelSummary s) {
        String id = sanitizeSingleLine(s.getLevelId());
        String name = sanitizeSingleLine(s.getLevelName());
        if (name.isEmpty()) name = id.isEmpty() ? "未命名世界" : id;
        long lastPlayed = s.getLastPlayed();
        String timeStr = lastPlayed > 0L ? WORLD_TIME_FMT.format(Instant.ofEpochMilli(lastPlayed)) : "NEW";
        boolean corrupted = s instanceof LevelSummary.CorruptedLevelSummary;
        boolean symlink = s instanceof LevelSummary.SymlinkLevelSummary;
        boolean locked = s.isLocked();
        boolean playable = s.primaryActionActive();
        boolean hardcore = !corrupted && !symlink && s.isHardcore();
        boolean commands = !corrupted && !symlink && s.hasCommands();
        boolean backup = !corrupted && !symlink && s.shouldBackup();

        String modeTag;
        String modeCn;
        if (corrupted) {
            modeTag = "CORRUPT";
            modeCn = "存档损坏";
        } else if (symlink) {
            modeTag = "SYMLINK";
            modeCn = "符号链接";
        } else if (hardcore) {
            modeTag = "HARDCORE";
            modeCn = "极限模式";
        } else {
            GameType gm = s.getGameMode();
            modeTag = gameModeTag(gm);
            modeCn = gameModeChinese(gm);
        }

        String statusTag;
        if (corrupted) statusTag = "CORRUPT";
        else if (symlink) statusTag = "SYMLINK";
        else if (locked) statusTag = "LOCKED";
        else if (backup) statusTag = "BACKUP";
        else statusTag = modeTag;

        String version = "";
        try {
            Component verComp = s.getWorldVersionName();
            version = verComp != null ? sanitizeSingleLine(verComp.getString()) : "";
        } catch (Throwable ignored) {
        }
        String info = "";
        try {
            Component infoComp = s.getInfo();
            info = infoComp != null ? sanitizeSingleLine(infoComp.getString()) : "";
        } catch (Throwable ignored) {
        }

        return new WorldEntry(
                s, id, name, timeStr, modeTag, modeCn, version,
                hardcore, commands, playable, locked, corrupted, symlink, backup, statusTag, info
        );
    }

    // ── Join & Native Fallback Actions ───────────────────────────────────────

    /** Connects directly to the chosen server using Minecraft's native {@link ConnectScreen}. */
    public boolean joinServer(@Nullable Minecraft mc, @Nullable Screen titleScreen, @Nullable ServerEntry entry) {
        if (mc == null || entry == null || entry.data() == null) return false;
        if (!mc.allowsMultiplayer()) {
            // Fall back to vanilla screen so Minecraft displays its official account restriction notice
            openNativeMultiplayerEditor(mc, titleScreen);
            return true;
        }
        String ip = entry.data().ip;
        if (ip == null || ip.isBlank()) return false;
        stopMultiplayerNetworking();
        ServerAddress addr = ServerAddress.parseString(ip);
        ConnectScreen.startConnecting(titleScreen, mc, addr, entry.data(), false, null);
        return true;
    }

    /** Joins the chosen local singleplayer world using Minecraft's native {@link net.minecraft.client.gui.screens.worldselection.WorldOpenFlows}. */
    public boolean joinWorld(@Nullable Minecraft mc, @Nullable Screen titleScreen, @Nullable WorldEntry entry) {
        if (mc == null || entry == null || entry.summary() == null) return false;
        LevelSummary summary = entry.summary();
        if (!summary.primaryActionActive()) {
            return false;
        }
        if (summary instanceof LevelSummary.SymlinkLevelSummary) {
            mc.setScreen(NoticeWithLinkScreen.createWorldSymlinkWarningScreen(() -> mc.setScreen(titleScreen)));
            return true;
        }
        mc.createWorldOpenFlows().openWorld(summary.getLevelId(), () -> mc.setScreen(titleScreen));
        return true;
    }

    /** Fallback: opens vanilla {@link JoinMultiplayerScreen} to add/edit/delete/reorder/direct-connect servers. */
    public void openNativeMultiplayerEditor(@Nullable Minecraft mc, @Nullable Screen titleScreen) {
        if (mc == null) return;
        stopMultiplayerNetworking();
        mc.setScreen(new JoinMultiplayerScreen(titleScreen));
    }

    /** Fallback: opens vanilla {@link SelectWorldScreen} to create/edit/recreate/delete singleplayer worlds. */
    public void openNativeSingleplayerEditor(@Nullable Minecraft mc, @Nullable Screen titleScreen) {
        if (mc == null) return;
        mc.setScreen(new SelectWorldScreen(titleScreen));
    }

    // ── Formatting & Telemetry Helpers (Package-Private for Unit Testing) ────

    static String sanitizeSingleLine(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String stripped = ChatFormatting.stripFormatting(raw);
        if (stripped == null) stripped = raw;
        StringBuilder sb = new StringBuilder(stripped.length());
        boolean prevSpace = true;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c == ' ') {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else if (!Character.isISOControl(c)) {
                sb.append(c);
                prevSpace = false;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    static boolean isPureAscii(@Nullable String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    static ServerPingVisual classifyServerVisual(ServerData.State state, boolean isLan, boolean compatible) {
        if (isLan) return ServerPingVisual.LAN;
        if (state == ServerData.State.PINGING || state == ServerData.State.INITIAL) return ServerPingVisual.PINGING;
        if (state == ServerData.State.UNREACHABLE) return ServerPingVisual.OFFLINE;
        if (state == ServerData.State.INCOMPATIBLE || !compatible) return ServerPingVisual.INCOMPATIBLE;
        return ServerPingVisual.ONLINE;
    }

    /** Returns 0..4 signal strength bars for a given latency in milliseconds. */
    static int pingBarsForLatency(long pingMs) {
        if (pingMs < 0L) return 0;
        if (pingMs < 80L) return 4;
        if (pingMs < 160L) return 3;
        if (pingMs < 320L) return 2;
        return 1;
    }

    static String formatServerPillTag(ServerEntry entry) {
        ServerPingVisual vis = classifyServerVisual(entry.state(), entry.isLan(), entry.isCompatible());
        return switch (vis) {
            case LAN -> "LAN";
            case PINGING -> "PING...";
            case OFFLINE -> "OFFLINE";
            case INCOMPATIBLE -> "VER ERR";
            case ONLINE -> {
                if (entry.data() != null && entry.data().players != null) {
                    yield entry.data().players.online() + "/" + entry.data().players.max();
                }
                if (entry.data() != null && entry.data().ping >= 0L) {
                    yield entry.data().ping + "MS";
                }
                yield "ONLINE";
            }
        };
    }

    static String formatServerSubLine(ServerEntry entry) {
        String addr = entry.address();
        ServerPingVisual vis = classifyServerVisual(entry.state(), entry.isLan(), entry.isCompatible());
        return switch (vis) {
            case LAN -> "LAN // " + (addr.isEmpty() ? "LOCAL NETWORK" : addr);
            case PINGING -> (addr.isEmpty() ? "SERVER" : addr) + "  //  PROBING";
            case OFFLINE -> (addr.isEmpty() ? "SERVER" : addr) + "  //  UNREACHABLE";
            case INCOMPATIBLE -> (addr.isEmpty() ? "SERVER" : addr) + "  //  INCOMPATIBLE";
            case ONLINE -> {
                String pingPart = (entry.data() != null && entry.data().ping >= 0L)
                        ? (entry.data().ping + "MS") : "READY";
                yield addr.isEmpty() ? pingPart : (addr + "  //  " + pingPart);
            }
        };
    }

    static HabiTitleTelemetry.MenuVisualMeta describeServerForCard(ServerEntry entry) {
        ServerPingVisual vis = classifyServerVisual(entry.state(), entry.isLan(), entry.isCompatible());
        String name = entry.displayName();
        String addr = entry.address();
        String motd = entry.motdPlain();
        String kicker = switch (vis) {
            case LAN -> "LAN DISPATCH // LOCAL NETWORK";
            case PINGING -> "SERVER DISPATCH // PROBING";
            case OFFLINE -> "SERVER DISPATCH // UNREACHABLE";
            case INCOMPATIBLE -> "SERVER DISPATCH // VERSION MISMATCH";
            case ONLINE -> "SERVER DISPATCH // ONLINE";
        };
        String code;
        if (vis == ServerPingVisual.LAN) {
            code = "LAN";
        } else if (vis == ServerPingVisual.ONLINE && entry.data() != null && entry.data().ping >= 0L) {
            code = "PING " + entry.data().ping + "MS";
        } else if (vis == ServerPingVisual.PINGING) {
            code = "SCANNING";
        } else if (vis == ServerPingVisual.INCOMPATIBLE && entry.data() != null && entry.data().version != null) {
            String ver = sanitizeSingleLine(entry.data().version.getString());
            code = ver.isEmpty() ? "INCOMPAT" : ver.toUpperCase(Locale.ROOT);
        } else {
            code = "OFFLINE";
        }

        StringBuilder desc = new StringBuilder();
        if (!motd.isEmpty()) {
            desc.append(motd);
        } else if (vis == ServerPingVisual.OFFLINE) {
            desc.append("无法连接到该服务器，请检查网络或服务器状态。");
        } else if (vis == ServerPingVisual.PINGING) {
            desc.append("正在获取服务器状态、延迟与在线人数，点击可直接尝试接入。");
        } else {
            desc.append("点击立即检票连接至该多人服务器。");
        }
        if (!addr.isEmpty()) {
            desc.append(" · ").append(addr);
        }

        String meterLabel = "PLAYERS";
        String meterRight;
        int meterPct;
        if (vis == ServerPingVisual.ONLINE && entry.data() != null && entry.data().players != null) {
            int online = Math.max(0, entry.data().players.online());
            int max = Math.max(1, entry.data().players.max());
            meterRight = online + " / " + max;
            meterPct = Math.max(8, Math.min(100, (int) Math.round(online * 100.0 / max)));
        } else if (vis == ServerPingVisual.LAN) {
            meterLabel = "CHANNEL";
            meterRight = "LAN READY";
            meterPct = 100;
        } else if (vis == ServerPingVisual.ONLINE) {
            meterLabel = "SIGNAL";
            meterRight = "CONNECTED";
            meterPct = 100;
        } else if (vis == ServerPingVisual.PINGING) {
            meterLabel = "SIGNAL";
            meterRight = "PROBING";
            meterPct = 45;
        } else {
            meterLabel = "SIGNAL";
            meterRight = vis == ServerPingVisual.INCOMPATIBLE ? "MISMATCH" : "OFFLINE";
            meterPct = 12;
        }

        return new HabiTitleTelemetry.MenuVisualMeta(
                "SERVER", formatServerPillTag(entry),
                kicker, code, name, desc.toString(),
                meterLabel, meterRight, meterPct
        );
    }

    static HabiTitleTelemetry.MenuVisualMeta describeWorldForCard(WorldEntry entry) {
        String kicker = "LOCAL ROUTE // " + entry.gameModeTag();
        String code = entry.versionName().isEmpty() ? "MC 1.21.1" : entry.versionName().toUpperCase(Locale.ROOT);
        StringBuilder desc = new StringBuilder();
        desc.append("存档 ").append(entry.levelId())
                .append(" · 上次游玩 ").append(entry.lastPlayedText())
                .append(" · ").append(entry.gameModeCn());
        if (entry.hasCommands()) {
            desc.append(" · 允许命令");
        }
        if (entry.corrupted()) {
            desc.append("。该存档已损坏，请点击底部「编辑」在原生界面处理。");
        } else if (entry.locked()) {
            desc.append("。该存档当前被其他进程占用锁定。");
        } else if (entry.backupRecommended()) {
            desc.append("。跨版本存档，点击进入时将弹出原版备份确认。");
        } else if (entry.playable()) {
            desc.append("。点击直接载入该单人世界。");
        } else if (!entry.infoPlain().isEmpty()) {
            desc.append(" · ").append(entry.infoPlain());
        }

        String meterRight;
        int meterPct;
        if (!entry.playable() || entry.corrupted() || entry.locked()) {
            meterRight = entry.statusTag();
            meterPct = 18;
        } else if (entry.backupRecommended()) {
            meterRight = "BACKUP REQ";
            meterPct = 65;
        } else {
            meterRight = "READY";
            meterPct = 100;
        }

        return new HabiTitleTelemetry.MenuVisualMeta(
                "WORLD", entry.statusTag(),
                kicker, code, entry.displayName(), desc.toString(),
                "ARCHIVE", meterRight, meterPct
        );
    }

    static HabiTitleTelemetry.MenuVisualMeta describeEditFallbackForCard(HabiStationListTransition.PanelMode mode) {
        if (mode == HabiStationListTransition.PanelMode.MULTIPLAYER) {
            return new HabiTitleTelemetry.MenuVisualMeta(
                    "EDIT SERVERS", "NATIVE",
                    "FALLBACK // VANILLA MULTIPLAYER", "MC 1.21.1",
                    "编辑多人服务器列表",
                    "进入 Minecraft 原生多人游戏界面，可添加、修改、删除、排序服务器或使用直接连接。返回后列表将自动刷新。",
                    "EDITOR", "NATIVE UI", 100
            );
        }
        return new HabiTitleTelemetry.MenuVisualMeta(
                "EDIT WORLDS", "NATIVE",
                "FALLBACK // VANILLA SINGLEPLAYER", "MC 1.21.1",
                "编辑单人世界列表",
                "进入 Minecraft 原生单人游戏界面，可创建新世界、编辑世界设置、重建、备份或删除本地存档。返回后列表将自动刷新。",
                "EDITOR", "NATIVE UI", 100
        );
    }

    private static String gameModeTag(@Nullable GameType gm) {
        if (gm == null) return "LOCAL";
        return switch (gm) {
            case SURVIVAL -> "SURVIVAL";
            case CREATIVE -> "CREATIVE";
            case ADVENTURE -> "ADVENTURE";
            case SPECTATOR -> "SPECTATOR";
        };
    }

    private static String gameModeChinese(@Nullable GameType gm) {
        if (gm == null) return "本地存档";
        return switch (gm) {
            case SURVIVAL -> "生存模式";
            case CREATIVE -> "创造模式";
            case ADVENTURE -> "冒险模式";
            case SPECTATOR -> "旁观模式";
        };
    }
}
