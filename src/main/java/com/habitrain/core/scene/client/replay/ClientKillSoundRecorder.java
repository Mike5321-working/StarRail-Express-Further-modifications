package com.habitrain.core.scene.client.replay;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * @deprecated Retired client-polled kill-sound recorder. Authoritative global match game sounds are
 * now recorded natively on the server via {@code GlobalMatchSoundRecorder} and stored in
 * {@code LastMatchReplay.gameSounds()}.
 */
@Deprecated
public final class ClientKillSoundRecorder {
    private ClientKillSoundRecorder() {}

    public static void register() {
        // Intentionally retired; server-side GlobalMatchSoundRecorder supersedes client polling.
    }

    public static void observe(SoundInstance instance, ResourceLocation file, float range,
                               float rawVolume, float mixedVolume, float pitch) {
        // Retired no-op.
    }

    static float gainAt(Vec3 source, Vec3 listener, float range) {
        if (!(range > 0)) return 0;
        double distance = source.distanceTo(listener);
        return Double.isFinite(distance) ? (float) Math.max(0, 1 - distance / range) : 0;
    }
}
