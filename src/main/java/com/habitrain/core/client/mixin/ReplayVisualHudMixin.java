package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayVisualClient;
import io.wifi.utils.client.betterrender.OptimizedTextRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OptimizedTextRenderer.class, remap = false)
abstract class ReplayVisualHudMixin {
    @Inject(method="beginFrame", at=@At("RETURN"))
    private void habitrain$begin(GuiGraphics graphics, CallbackInfo ci) { ReplayVisualClient.begin(graphics); }
    @Inject(method="endFrame", at=@At("HEAD"))
    private void habitrain$end(CallbackInfo ci) { ReplayVisualClient.end(); }
    @Inject(method="enqueue", at=@At("HEAD"))
    private void habitrain$text(GuiGraphics graphics, Component text, float x, float y, int color, boolean shadow, CallbackInfo ci) {
        if (ReplayVisualClient.collecting()) ReplayVisualClient.draw(graphics, shadow ? 1 : 0, x,y,x,y,color,text.getString(),null);
    }
    @Inject(method="enqueueSeq", at=@At("HEAD"))
    private void habitrain$sequence(GuiGraphics graphics, FormattedCharSequence text, float x, float y, int color, boolean shadow, CallbackInfo ci) {
        if (!ReplayVisualClient.collecting()) return;
        var value = new StringBuilder(); text.accept((index,style,code) -> { value.appendCodePoint(code); return value.length() < 512; });
        ReplayVisualClient.draw(graphics, shadow ? 1 : 0, x,y,x,y,color,value.toString(),null);
    }
    @Inject(method="enqueueFill", at=@At("HEAD"))
    private void habitrain$fill(GuiGraphics graphics,int x,int y,int xx,int yy,int z,int color,CallbackInfo ci) {
        ReplayVisualClient.draw(graphics,2,x,y,xx,yy,color,"",null);
    }
    @Inject(method="enqueueBlit", at=@At("HEAD"))
    private void habitrain$texture(GuiGraphics graphics, ResourceLocation texture,int x,int xx,int y,int yy,int z,float u,float uu,float v,float vv,float red,float green,float blue,float alpha,CallbackInfo ci) {
        if (ReplayVisualClient.collecting()) ReplayVisualClient.draw(graphics,3,x,y,xx,yy,net.minecraft.util.FastColor.ARGB32.color((int)(255*alpha),(int)(255*red),(int)(255*green),(int)(255*blue)),texture.toString(),new float[]{u,uu,v,vv});
    }
    @Inject(method="enqueueBlitSprite", at=@At("HEAD"))
    private void habitrain$sprite(GuiGraphics graphics,ResourceLocation sprite,int x,int y,int width,int height,CallbackInfo ci) {
        if (ReplayVisualClient.collecting()) ReplayVisualClient.draw(graphics,4,x,y,x+width,y+height,-1,sprite.toString(),null);
    }
}
