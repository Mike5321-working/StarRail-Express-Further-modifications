package com.habitrain.core.scene.client.replay;

import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Full player skin lookup (texture, slim/wide arm model, cape) for 3D replay avatars.
 *
 * <p>Resolution priority: frozen export snapshot -> live {@link PlayerInfo} -> SRE
 * {@code ClientSkinCache} -> LRU resolved cache -> async lookup via Mojang profile cache and
 * {@code SkinManager} (which {@code CustomSkinLoader} hooks seamlessly using both UUID and player
 * name). Until a skin finishes loading, the deterministic vanilla default skin is returned and the
 * next frame picks the real one up automatically. Game thread only.</p>
 */
final class ReplayHeadSkins {
    private static final int MAX_CACHED = 256, MAX_PENDING = 24;
    private static final long RETRY_NS = TimeUnit.MINUTES.toNanos(1);
    private static final LinkedHashMap<UUID, Cached> RESOLVED = new LinkedHashMap<>(32, .75f, true);
    private static final Map<UUID, String> KNOWN_NAMES = new HashMap<>();
    private static final Set<UUID> PENDING = new HashSet<>();
    private static final boolean CUSTOM_SKIN_LOADER = FabricLoader.getInstance().isModLoaded("customskinloader");
    private static long generation;
    private static Map<UUID, ResourceLocation> exportTextures = Map.of();
    private static Map<UUID, PlayerSkin> exportSkins = Map.of();

    private record Cached(PlayerSkin skin, long retryAt) {}

    private ReplayHeadSkins() {}

    static void rememberName(@Nullable UUID id, @Nullable String name) {
        if (id != null && name != null && !name.isBlank()) {
            KNOWN_NAMES.put(id, name);
        }
    }

    static void prefetch(ReplayBoardModel model) {
        if (model == null) return;
        for (var actor : model.actors) {
            rememberName(actor.p.id(), actor.p.name());
            skin(actor.p.id(), actor.p.name());
        }
    }

    /** Returns true while any actor in {@code model} still has an in-flight async skin request. */
    static boolean hasPending(@Nullable ReplayBoardModel model) {
        if (model == null || PENDING.isEmpty()) return false;
        for (var actor : model.actors) {
            if (PENDING.contains(actor.p.id())) return true;
        }
        return false;
    }

    static ResourceLocation texture(UUID id) {
        if (id == null) return DefaultPlayerSkin.getDefaultTexture();
        var frozen = exportTextures.get(id);
        if (frozen != null) return frozen;
        return skin(id).texture();
    }

    static PlayerSkin skin(UUID id) {
        return skin(id, KNOWN_NAMES.get(id));
    }

    static PlayerSkin skin(UUID id, @Nullable String name) {
        if (id == null) return DefaultPlayerSkin.get(new UUID(0L, 0L));
        if (name != null && !name.isBlank()) KNOWN_NAMES.put(id, name);
        var frozenSkin = exportSkins.get(id);
        if (frozenSkin != null) return frozenSkin;
        var frozenTex = exportTextures.get(id);
        if (frozenTex != null) {
            PlayerSkin base = DefaultPlayerSkin.get(id);
            return new PlayerSkin(frozenTex, base.textureUrl(), base.capeTexture(), base.elytraTexture(), base.model(), base.secure());
        }
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(id);
            if (info != null) {
                PlayerSkin live = info.getSkin();
                if (live != null) {
                    RESOLVED.put(id, new Cached(live, 0));
                    trimCache();
                    return live;
                }
            }
        }
        PlayerSkin sreCached = lookupSreSkinCache(id);
        if (sreCached != null) {
            RESOLVED.put(id, new Cached(sreCached, 0));
            trimCache();
            return sreCached;
        }
        Cached cached = RESOLVED.get(id);
        if (cached != null && (cached.retryAt() == 0 || System.nanoTime() - cached.retryAt() < 0)) {
            return cached.skin();
        }
        if (PENDING.size() < MAX_PENDING && PENDING.add(id)) {
            request(client, id, KNOWN_NAMES.get(id));
        }
        return DefaultPlayerSkin.get(id);
    }

    static Map<UUID, ResourceLocation> snapshot(ReplayBoardModel model) {
        var skins = new HashMap<UUID, ResourceLocation>();
        for (var actor : model.actors) {
            rememberName(actor.p.id(), actor.p.name());
            skins.put(actor.p.id(), skin(actor.p.id(), actor.p.name()).texture());
        }
        return Map.copyOf(skins);
    }

    static Map<UUID, PlayerSkin> snapshotSkins(ReplayBoardModel model) {
        var skins = new HashMap<UUID, PlayerSkin>();
        for (var actor : model.actors) {
            rememberName(actor.p.id(), actor.p.name());
            skins.put(actor.p.id(), skin(actor.p.id(), actor.p.name()));
        }
        return Map.copyOf(skins);
    }

    static final class ExportScope implements AutoCloseable {
        private final Map<UUID, ResourceLocation> previousTextures = exportTextures;
        private final Map<UUID, PlayerSkin> previousSkins = exportSkins;

        ExportScope(Map<UUID, ResourceLocation> textures) {
            exportTextures = textures == null ? Map.of() : textures;
            if (textures != null && !textures.isEmpty()) {
                var resolved = new HashMap<UUID, PlayerSkin>(textures.size());
                for (var entry : textures.entrySet()) {
                    UUID id = entry.getKey();
                    PlayerSkin cached = lookupBestCached(id);
                    if (cached != null && cached.texture().equals(entry.getValue())) {
                        resolved.put(id, cached);
                    } else {
                        PlayerSkin base = cached != null ? cached : DefaultPlayerSkin.get(id);
                        resolved.put(id, new PlayerSkin(entry.getValue(), base.textureUrl(),
                                base.capeTexture(), base.elytraTexture(), base.model(), base.secure()));
                    }
                }
                exportSkins = Map.copyOf(resolved);
            } else {
                exportSkins = Map.of();
            }
        }

        static ExportScope full(Map<UUID, PlayerSkin> skins) {
            return new ExportScope(skins, true);
        }

        private ExportScope(Map<UUID, PlayerSkin> skins, boolean fullMarker) {
            exportSkins = skins == null ? Map.of() : skins;
            if (skins != null && !skins.isEmpty()) {
                var tex = new HashMap<UUID, ResourceLocation>(skins.size());
                for (var e : skins.entrySet()) tex.put(e.getKey(), e.getValue().texture());
                exportTextures = Map.copyOf(tex);
            } else {
                exportTextures = Map.of();
            }
        }

        @Override public void close() {
            exportTextures = previousTextures;
            exportSkins = previousSkins;
        }
    }

    private static @Nullable PlayerSkin lookupBestCached(UUID id) {
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(id);
            if (info != null && info.getSkin() != null) return info.getSkin();
        }
        PlayerSkin sre = lookupSreSkinCache(id);
        if (sre != null) return sre;
        Cached cached = RESOLVED.get(id);
        return cached == null ? null : cached.skin();
    }

    private static @Nullable PlayerSkin lookupSreSkinCache(UUID id) {
        try {
            PlayerInfo info = io.wifi.starrailexpress.client.util.ClientSkinCache.getCachedPlayerInfo(id);
            return info == null ? null : info.getSkin();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void request(Minecraft client, UUID id, @Nullable String name) {
        long token = generation;
        try {
            CompletableFuture<PlayerSkin> future;
            if (CUSTOM_SKIN_LOADER && name != null && !name.isBlank()) {
                // CustomSkinLoader resolves offline/LittleSkin/BlessingSkin profiles directly via SkinManager using name.
                future = client.getSkinManager().getOrLoad(new GameProfile(id, name));
            } else {
                future = SkullBlockEntity.fetchGameProfile(id)
                        .thenComposeAsync(profile -> {
                            if (token != generation || !PENDING.contains(id)) {
                                return CompletableFuture.completedFuture(null);
                            }
                            if (profile.isPresent()) {
                                GameProfile gp = profile.get();
                                if (gp.getProperties().isEmpty() && name != null && !name.isBlank()) {
                                    gp = new GameProfile(id, name);
                                }
                                return client.getSkinManager().getOrLoad(gp);
                            }
                            if (name != null && !name.isBlank()) {
                                return client.getSkinManager().getOrLoad(new GameProfile(id, name));
                            }
                            return CompletableFuture.completedFuture(null);
                        }, client);
            }
            future.orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((skin, failure) -> client.execute(() -> {
                        if (token != generation) return;
                        finish(id, skin);
                    }));
        } catch (RuntimeException unavailable) {
            finish(id, null);
        }
    }

    private static void finish(UUID id, @Nullable PlayerSkin skin) {
        PENDING.remove(id);
        PlayerSkin fallback = DefaultPlayerSkin.get(id);
        PlayerSkin resolved = skin == null ? fallback : skin;
        boolean isFallback = resolved.texture().equals(fallback.texture());
        RESOLVED.put(id, new Cached(resolved, isFallback ? System.nanoTime() + RETRY_NS : 0));
        trimCache();
    }

    private static void trimCache() {
        while (RESOLVED.size() > MAX_CACHED) RESOLVED.pollFirstEntry();
    }

    static void clear() {
        generation++;
        RESOLVED.clear();
        KNOWN_NAMES.clear();
        PENDING.clear();
    }
}
