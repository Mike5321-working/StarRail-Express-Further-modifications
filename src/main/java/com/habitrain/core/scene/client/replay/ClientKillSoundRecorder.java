package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.ReplayKillSounds;
import com.habitrain.core.network.replay.ReplayPackets.CaptureKillSoundS2C;
import com.habitrain.core.network.replay.ReplayPackets.KillSoundC2S;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ClientKillSoundRecorder {
    private static final int MAX_RECENT = 256;
    private static final int MAX_PENDING = 8;
    private static final ArrayDeque<HeardSound> recent = new ArrayDeque<>();
    private static final ArrayDeque<Window> pending = new ArrayDeque<>();
    private static ClientLevel level;

    private record HeardSound(long timeMs, ResourceLocation file, float volume, float pitch, Vec3 position, float range) {}
    private record Window(CaptureKillSoundS2C request, long impactMs) {}

    private ClientKillSoundRecorder() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CaptureKillSoundS2C.TYPE, (packet, context) -> {
            observeLevel(context.client().level);
            if (level != null && pending.size() < MAX_PENDING && ClientPlayNetworking.canSend(KillSoundC2S.TYPE)) {
                long tickDelta = Math.clamp(level.getGameTime() - packet.serverGameTime(), -100L, 100L);
                pending.addLast(new Window(packet, nowMs() - tickDelta * 50L));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(ClientKillSoundRecorder::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> observeLevel(null));
    }

    public static void observe(SoundInstance instance, ResourceLocation file, float range, float rawVolume,
                               float mixedVolume, float pitch) {
        if (instance instanceof ReplayAudioPlaybackController.ReplayEffectSound
                || instance instanceof ReplayAudioPlaybackController.VoiceClipSoundInstance
                || instance.isLooping()
                || instance.getSource() == SoundSource.MUSIC || !ReplayKillSounds.validFile(file)
                || !Float.isFinite(rawVolume) || !(mixedVolume > 0) || !Float.isFinite(pitch) || pitch < .5f || pitch > 2f
                || instance.getLocation().getNamespace().equals("minecraft") && instance.getLocation().getPath().startsWith("ui.")) return;
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || client.level == null || client.player == null
                || !ClientPlayNetworking.canSend(KillSoundC2S.TYPE)) return;
        observeLevel(client.level);
        if (pending.isEmpty() && SREGameWorldComponent.KEY.get(level).getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) return;
        Vec3 source = instance.isRelative() || instance.getAttenuation() == SoundInstance.Attenuation.NONE
                ? null : new Vec3(instance.getX(), instance.getY(), instance.getZ());
        Vec3 listener = client.getSoundManager().getListenerTransform().position();
        if (source != null && gainAt(source, listener, range) == 0) return;
        if (!pending.isEmpty() && pending.stream().noneMatch(window ->
                listener.distanceToSqr(window.request().listener()) < 64)
                && SREGameWorldComponent.KEY.get(level).getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) return;
        if (recent.size() == MAX_RECENT) recent.removeFirst();
        recent.addLast(new HeardSound(nowMs(), file, Math.min(1f, rawVolume), pitch, source, range));
    }

    private static void tick(Minecraft client) {
        observeLevel(client.level);
        long now = nowMs();
        while (!pending.isEmpty() && now - pending.getFirst().impactMs() >= ReplayKillSounds.AFTER_MS) {
            Window window = pending.removeFirst();
            if (ClientPlayNetworking.canSend(KillSoundC2S.TYPE)) {
                ClientPlayNetworking.send(new KillSoundC2S(window.request().ticket(), finish(window)));
            }
        }
        while (!recent.isEmpty() && now - recent.getFirst().timeMs() > ReplayKillSounds.BEFORE_MS + ReplayKillSounds.AFTER_MS) {
            recent.removeFirst();
        }
    }

    private static List<ReplayKillSounds.Cue> finish(Window window) {
        List<ReplayKillSounds.Cue> cues = new ArrayList<>();
        for (HeardSound sound : recent) {
            long offset = sound.timeMs() - window.impactMs();
            if (offset < -ReplayKillSounds.BEFORE_MS || offset > ReplayKillSounds.AFTER_MS) continue;
            float gain = sound.position() == null ? 1 : gainAt(sound.position(), window.request().listener(), sound.range());
            float volume = sound.volume() * gain;
            if (volume > 0) {
                cues.add(new ReplayKillSounds.Cue((int) offset, sound.file(), volume, sound.pitch()));
            }
        }
        if (cues.size() <= ReplayKillSounds.MAX_PER_KILL) return cues;
        return cues.stream().sorted(Comparator.comparingInt(cue -> Math.abs(cue.offsetMs())))
                .limit(ReplayKillSounds.MAX_PER_KILL).sorted(Comparator.comparingInt(ReplayKillSounds.Cue::offsetMs)).toList();
    }

    static float gainAt(Vec3 source, Vec3 listener, float range) {
        if (!(range > 0)) return 0;
        double distance = source.distanceTo(listener);
        return Double.isFinite(distance) ? (float) Math.max(0, 1 - distance / range) : 0;
    }

    private static long nowMs() { return System.nanoTime() / 1_000_000; }

    private static void observeLevel(ClientLevel current) {
        if (level == current) return;
        level = current;
        recent.clear();
        pending.clear();
    }
}
