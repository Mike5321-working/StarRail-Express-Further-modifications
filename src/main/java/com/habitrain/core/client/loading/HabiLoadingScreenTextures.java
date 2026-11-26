package com.habitrain.core.client.loading;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

/**
 * 哈比列车加载界面纹理定义与注册工具类。
 */
@Environment(EnvType.CLIENT)
public final class HabiLoadingScreenTextures {

    public static final ResourceLocation GAME_LOADING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "textures/gui/loading/game_loading.png");

    public static final ResourceLocation WORLD_LOADING_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "textures/gui/loading/world_loading.png");

    public static final String GAME_LOADING_RESOURCE_PATH =
            "assets/habitrain_core/textures/gui/loading/game_loading.png";

    public static final String WORLD_LOADING_RESOURCE_PATH =
            "assets/habitrain_core/textures/gui/loading/world_loading.png";

    private HabiLoadingScreenTextures() {}

    /**
     * 幂等注册两个加载页面纹理到 Minecraft TextureManager。
     */
    public static synchronized void registerTextures(Minecraft minecraft) {
        registerGameLoadingTexture(minecraft);
        registerWorldLoadingTexture(minecraft);
    }

    /** 仅在旧版静态启动加载分支按需注册 game_loading 纹理。 */
    public static synchronized void registerGameLoadingTexture(Minecraft minecraft) {
        if (minecraft == null) return;
        TextureManager textureManager = minecraft.getTextureManager();
        if (textureManager == null) return;
        if (!(textureManager.getTexture(GAME_LOADING_TEXTURE, null) instanceof HabiLoadingTexture)) {
            textureManager.register(GAME_LOADING_TEXTURE,
                    new HabiLoadingTexture(GAME_LOADING_TEXTURE, GAME_LOADING_RESOURCE_PATH));
        }
    }

    /** 仅在旧版静态切图加载分支按需注册 world_loading 纹理。 */
    public static synchronized void registerWorldLoadingTexture(Minecraft minecraft) {
        if (minecraft == null) return;
        TextureManager textureManager = minecraft.getTextureManager();
        if (textureManager == null) return;
        if (!(textureManager.getTexture(WORLD_LOADING_TEXTURE, null) instanceof HabiLoadingTexture)) {
            textureManager.register(WORLD_LOADING_TEXTURE,
                    new HabiLoadingTexture(WORLD_LOADING_TEXTURE, WORLD_LOADING_RESOURCE_PATH));
        }
    }

    /** 加载完成或进入世界后释放加载纹理显存，避免整个游戏会话常驻。 */
    public static synchronized void releaseTextures(Minecraft minecraft) {
        if (minecraft == null) return;
        TextureManager textureManager = minecraft.getTextureManager();
        if (textureManager == null) return;
        if (textureManager.getTexture(GAME_LOADING_TEXTURE, null) instanceof HabiLoadingTexture) {
            textureManager.release(GAME_LOADING_TEXTURE);
        }
        if (textureManager.getTexture(WORLD_LOADING_TEXTURE, null) instanceof HabiLoadingTexture) {
            textureManager.release(WORLD_LOADING_TEXTURE);
        }
    }
}
