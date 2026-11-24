package com.habitrain.core.client.mixin;

import com.habitrain.core.scene.client.replay.ReplayParticleState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.ParticleGroup;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.spongepowered.asm.mixin.*;
import java.util.*;

@Mixin(ParticleEngine.class)
abstract class ReplayParticleMixin implements ReplayParticleState.Exchange {
    @Shadow protected ClientLevel level;
    @Shadow @Final @Mutable private Map<ParticleRenderType,Queue<Particle>> particles;
    @Shadow @Final @Mutable private Queue<TrackingEmitter> trackingEmitters;
    @Shadow @Final @Mutable private Queue<Particle> particlesToAdd;
    @Shadow @Final @Mutable private Object2IntOpenHashMap<ParticleGroup> trackedParticleCounts;
    public void habitrain$swap(ReplayParticleState state) {
        var world = level; level = state.level; state.level = world;
        var batch = particles; particles = state.particles; state.particles = batch;
        var emitters = trackingEmitters; trackingEmitters = state.emitters; state.emitters = emitters;
        var queue = particlesToAdd; particlesToAdd = state.pending; state.pending = queue;
        var counts = trackedParticleCounts; trackedParticleCounts = state.counts; state.counts = counts;
    }
    public void habitrain$clearVisuals() { particles.clear(); particlesToAdd.clear(); trackingEmitters.clear(); trackedParticleCounts.clear(); }
}
