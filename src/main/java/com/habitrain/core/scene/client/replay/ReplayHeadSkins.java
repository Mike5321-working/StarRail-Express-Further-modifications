package com.habitrain.core.scene.client.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Skin lookup for avatars. Online players use their live {@link PlayerInfo} skin; players who have
 * left are resolved through the vanilla profile cache and {@code SkinManager}. Until a skin
 * arrives the deterministic default skin is returned, and the next frame picks the real one up
 * (hot refresh, no extra bookkeeping in the renderer). Game thread only.
 */
final class ReplayHeadSkins {
    private static final int MAX_CACHED = 256, MAX_PENDING = 16;
    private static final long RETRY_NS = TimeUnit.MINUTES.toNanos(1);
    private static final LinkedHashMap<UUID, Cached> RESOLVED = new LinkedHashMap<>(32, .75f, true);
    private static final Set<UUID> PENDING = new HashSet<>();
    private static long generation;
    private static Map<UUID, ResourceLocation> exportTextures = Map.of();

    private record Cached(ResourceLocation texture, long retryAt) {}

    private ReplayHeadSkins() {}

    static ResourceLocation texture(UUID id) {
        var frozen = exportTextures.get(id);
        if (frozen != null) return frozen;
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(id);
            if (info != null) return info.getSkin().texture();
        }
        Cached cached = RESOLVED.get(id);
        if (cached != null && (cached.retryAt() == 0 || System.nanoTime() - cached.retryAt() < 0)) return cached.texture();
        if (PENDING.size() < MAX_PENDING && PENDING.add(id)) request(client, id);
        return DefaultPlayerSkin.get(id).texture();
    }

    static Map<UUID, ResourceLocation> snapshot(ReplayBoardModel model) {
        var skins = new HashMap<UUID, ResourceLocation>();
        for (var actor : model.actors) skins.put(actor.p.id(), texture(actor.p.id()));
        return Map.copyOf(skins);
    }

    static final class ExportScope implements AutoCloseable {
        private final Map<UUID, ResourceLocation> previous = exportTextures;

        ExportScope(Map<UUID, ResourceLocation> textures) { exportTextures = textures; }

        @Override public void close() { exportTextures = previous; }
    }

    private static void request(Minecraft client, UUID id) {
        long token = generation;
        try {
            SkullBlockEntity.fetchGameProfile(id)
                    .thenComposeAsync(profile -> token == generation && PENDING.contains(id) && profile.isPresent()
                            ? client.getSkinManager().getOrLoad(profile.get())
                            : CompletableFuture.<PlayerSkin>completedFuture(null), client)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .whenComplete((skin, failure) -> client.execute(() -> {
                        if (token != generation) return;
                        finish(id, skin);
                    }));
        } catch (RuntimeException unavailable) {
            finish(id, null);
        }
    }

    private static void finish(UUID id, PlayerSkin skin) {
        PENDING.remove(id);
        var fallback = DefaultPlayerSkin.get(id).texture();
        var texture = skin == null ? fallback : skin.texture();
        RESOLVED.put(id, new Cached(texture, texture.equals(fallback) ? System.nanoTime() + RETRY_NS : 0));
        while (RESOLVED.size() > MAX_CACHED) RESOLVED.pollFirstEntry();
    }

    static void clear() {
        generation++;
        RESOLVED.clear();
        PENDING.clear();
    }
}
