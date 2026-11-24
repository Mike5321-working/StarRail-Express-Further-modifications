package com.habitrain.core.scene.client.replay;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.ParticleGroup;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.*;

public final class ReplayParticleState {
    public interface Exchange { void habitrain$swap(ReplayParticleState state); void habitrain$clearVisuals(); }
    public ClientLevel level;
    public Map<ParticleRenderType,Queue<Particle>> particles = new IdentityHashMap<>();
    public Queue<TrackingEmitter> emitters = new ArrayDeque<>();
    public Queue<Particle> pending = new ArrayDeque<>();
    public Object2IntOpenHashMap<ParticleGroup> counts = new Object2IntOpenHashMap<>();
    public void clear() { particles.clear(); emitters.clear(); pending.clear(); counts.clear(); }
}
