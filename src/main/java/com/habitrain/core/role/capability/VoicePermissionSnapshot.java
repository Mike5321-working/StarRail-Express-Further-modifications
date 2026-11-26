package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityContext;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Safely published voice view. Contains no player, world, CCA or config references. */
public final class VoicePermissionSnapshot {
    public static final VoicePermissionSnapshot EMPTY = new VoicePermissionSnapshot(Map.of(), List.of(), Set.of());
    /** Fast-path snapshot reused when no voice policies are enabled and voice recording is inactive. */
    static final VoicePermissionSnapshot PASS_THROUGH = new VoicePermissionSnapshot(true);

    private final Map<UUID, Participant> players;
    private final List<RoleVoicePolicy> policies;
    private final List<RoleVoicePolicy> muteSendPolicies;
    private final Set<UUID> privateChannels;
    private final boolean passThrough;

    private VoicePermissionSnapshot(boolean passThrough) {
        this.players = Map.of();
        this.policies = List.of();
        this.muteSendPolicies = List.of();
        this.privateChannels = Set.of();
        this.passThrough = passThrough;
    }

    public VoicePermissionSnapshot(Map<UUID, Participant> players, List<RoleVoicePolicy> policies) {
        this(players, policies, Set.of());
    }

    public VoicePermissionSnapshot(Map<UUID, Participant> players, List<RoleVoicePolicy> policies,
                                   Set<UUID> privateChannels) {
        this.players = Map.copyOf(players);
        this.policies = List.copyOf(policies);
        this.privateChannels = Set.copyOf(privateChannels);
        this.passThrough = false;
        if (this.policies.isEmpty()) {
            this.muteSendPolicies = List.of();
        } else {
            List<RoleVoicePolicy> sendOnly = null;
            for (RoleVoicePolicy p : this.policies) {
                if (p != null && p.muteSend()) {
                    if (sendOnly == null) sendOnly = new ArrayList<>(this.policies.size());
                    sendOnly.add(p);
                }
            }
            this.muteSendPolicies = sendOnly == null ? List.of() : List.copyOf(sendOnly);
        }
    }

    public Map<UUID, Participant> players() { return players; }
    public List<RoleVoicePolicy> policies() { return policies; }
    public Set<UUID> privateChannels() { return privateChannels; }

    public record Participant(RoleKey role, UUID group, String dimension, double x, double y, double z) {}

    public boolean isPrivateChannel(UUID speaker) {
        return !privateChannels.isEmpty() && privateChannels.contains(speaker);
    }

    public VoiceDecision evaluate(UUID speaker, UUID listener, UUID nativeSpeakerGroup, UUID nativeListenerGroup) {
        if (passThrough) return VoiceDecision.PASS;
        Participant from = players.get(speaker);
        Participant to = listener == null ? null : players.get(listener);
        // An absent view means JOIN/STOPPING or a failed server-side refresh. Retry next packet.
        if (from == null || (listener != null && to == null)) return VoiceDecision.BLOCK;
        // A microphone event has no listener; receiver/isolation rules apply at delivery.
        List<RoleVoicePolicy> applicable = listener == null ? muteSendPolicies : policies;
        if (applicable.isEmpty()) return VoiceDecision.PASS;
        UUID fromGroup = from.group() != null ? from.group() : nativeSpeakerGroup;
        UUID toGroup = to != null && to.group() != null ? to.group() : nativeListenerGroup;
        Double distance = null;
        if (to != null) {
            double dx = from.x() - to.x(), dy = from.y() - to.y(), dz = from.z() - to.z();
            distance = from.dimension().equals(to.dimension()) ? Math.sqrt(dx * dx + dy * dy + dz * dz)
                    : Double.POSITIVE_INFINITY;
        }
        RoleCapabilityContext context = RoleCapabilityContext.of(speaker, from.role(), listener,
                to == null ? null : to.role(), fromGroup, toGroup, distance);
        return CapabilityPolicyEvaluator.voice(applicable, context);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VoicePermissionSnapshot that)) return false;
        return passThrough == that.passThrough
                && Objects.equals(players, that.players)
                && Objects.equals(policies, that.policies)
                && Objects.equals(privateChannels, that.privateChannels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(players, policies, privateChannels, passThrough);
    }

    @Override
    public String toString() {
        return "VoicePermissionSnapshot[players=" + players + ", policies=" + policies
                + ", privateChannels=" + privateChannels + "]";
    }
}
