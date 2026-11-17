package com.habitrain.core.client.mixin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that all drone and unified role-prop client/server mixins are registered in
 * {@code habitrain_core.client.mixins.json} / {@code habitrain_core.mixins.json} and that their
 * {@code @Shadow} fields/methods and {@code @Inject}/{@code @ModifyReturnValue}/{@code @WrapMethod}/{@code @WrapOperation}
 * target selectors resolve against Minecraft 1.21.1 and upstream mod bytecode.
 */
class DroneClientMixinContractTest {

    private static final List<String> DRONE_AND_PROP_CLIENT_MIXINS = List.of(
            "DroneCameraMixin",
            "DroneGameRendererMixin",
            "DroneInputConstantsMixin",
            "DroneInputMixin",
            "DroneKeyboardHandlerMixin",
            "DroneKeyMappingMixin",
            "DroneMinecraftKeyMixin",
            "DroneMouseHandlerMixin",
            "DroneGlowMixin",
            "DroneTurnAndOutlineMixin",
            "DronePlayerModelMixin",
            "DronePlayerRendererMixin",
            "DroneHeldItemLayerMixin",
            "DroneItemRendererMixin",
            "DroneFirstPersonUnboxMixin",
            "DroneHudSuppressMixin",
            "DroneTimeAndStoreHudSuppressMixin",
            "DroneMoodHudSuppressMixin",
            "DroneMiniGameHudSuppressMixin",
            "DroneCommonHudSuppressMixin",
            "UnifiedSkillHudReadyShiftMixin",
            "RolePropKnifeTargetMixin",
            "RolePropGunTargetMixin",
            "RolePropSniperTargetMixin"
    );

    private static final List<String> UNIFIED_SERVER_MIXINS = List.of(
            "BoundItemDropMixin",
            "BoundItemContainerMixin",
            "BoundItemTheftMixin",
            "DroneBatteryInventoryMixin",
            "RoleBodyLockMovementMixin",
            "RoleBodyLockDamageMixin",
            "RoleBodyLockSkillMixin",
            "RolePropKnifeMixin",
            "RolePropGunMixin",
            "RolePropOnceRevolverMixin",
            "RolePropBatMixin",
            "RolePropBanditGunMixin",
            "RolePropSniperMixin",
            "RolePropZeroOneFiveMixin",
            "RolePropShortShotgunMixin"
    );

    @Test
    void droneAndRolePropClientMixinsAreRegisteredAndResolveAllTargetsAndShadows() throws Exception {
        verifyMixinGroup("habitrain_core.client.mixins.json", "client", DRONE_AND_PROP_CLIENT_MIXINS);
    }

    @Test
    void unifiedServerBoundLockAndPropMixinsAreRegisteredAndResolveAllTargetsAndInvokePoints() throws Exception {
        verifyMixinGroup("habitrain_core.mixins.json", "mixins", UNIFIED_SERVER_MIXINS);

        // Explicitly verify ShortShotgunItem#releaseUsing contains the exact Level.playSound INVOKE target
        ClassNode shotgunClass = readClass("org/agmas/noellesroles/content/item/ShortShotgunItem");
        MethodNode releaseUsing = shotgunClass.methods.stream()
                .filter(m -> "releaseUsing".equals(m.name))
                .findFirst()
                .orElseThrow();
        boolean hasPlaySoundInvoke = false;
        for (var insn : releaseUsing.instructions) {
            if (insn instanceof MethodInsnNode call
                    && "net/minecraft/world/level/Level".equals(call.owner)
                    && "playSound".equals(call.name)
                    && "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V".equals(call.desc)) {
                hasPlaySoundInvoke = true;
                break;
            }
        }
        assertTrue(hasPlaySoundInvoke, "ShortShotgunItem#releaseUsing must contain Level#playSound INVOKE target");
    }

    private void verifyMixinGroup(String configResource, String arrayKey, List<String> expectedMixins) throws Exception {
        Set<String> configured = new HashSet<>();
        String pkg;
        try (var in = getClass().getClassLoader().getResourceAsStream(configResource)) {
            assertNotNull(in, configResource + " missing");
            var json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            pkg = json.get("package").getAsString().replace('.', '/');
            for (var el : json.getAsJsonArray(arrayKey)) {
                configured.add(el.getAsString());
            }
        }

        for (String name : expectedMixins) {
            assertTrue(configured.contains(name), "Missing from " + configResource + ": " + name);
            ClassNode mixin = readClass(pkg + "/" + name);
            List<String> targets = extractTargets(mixin);
            assertFalse(targets.isEmpty(), "No @Mixin target on " + name);

            for (String target : targets) {
                ClassNode targetNode = readClass(target);

                for (FieldNode field : mixin.fields) {
                    if (hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations,
                            "Lorg/spongepowered/asm/mixin/Shadow;")) {
                        assertTrue(hasField(targetNode, field.name, field.desc),
                                name + " @Shadow field missing in " + target + ": " + field.name + " " + field.desc);
                    }
                }

                for (MethodNode method : mixin.methods) {
                    if (hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations,
                            "Lorg/spongepowered/asm/mixin/Shadow;")) {
                        assertTrue(hasMethod(targetNode, method.name + method.desc),
                                name + " @Shadow method missing in " + target + ": " + method.name + method.desc);
                    }
                    for (AnnotationNode ann : allAnnotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                        if ("Lorg/spongepowered/asm/mixin/injection/Inject;".equals(ann.desc)
                                || "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;".equals(ann.desc)
                                || "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;".equals(ann.desc)
                                || "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;".equals(ann.desc)) {
                            @SuppressWarnings("unchecked")
                            List<String> selectors = (List<String>) annotationValue(ann, "method");
                            assertNotNull(selectors, name + "#" + method.name + " missing method selector");
                            for (String selector : selectors) {
                                assertTrue(hasMethod(targetNode, selector),
                                        name + "#" + method.name + " target missing in " + target + ": " + selector);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean hasField(ClassNode node, String name, String desc) throws Exception {
        for (FieldNode f : node.fields) {
            if (name.equals(f.name) && desc.equals(f.desc)) return true;
        }
        if (node.superName != null && !"java/lang/Object".equals(node.superName)) {
            return hasField(readClass(node.superName), name, desc);
        }
        return false;
    }

    private static boolean hasMethod(ClassNode node, String selector) throws Exception {
        for (MethodNode m : node.methods) {
            if (selector.equals(m.name) || selector.equals(m.name + m.desc)) return true;
        }
        if (selector.startsWith("<init>")) {
            return false;
        }
        if (node.superName != null && !"java/lang/Object".equals(node.superName)) {
            return hasMethod(readClass(node.superName), selector);
        }
        return false;
    }

    private static List<String> extractTargets(ClassNode mixin) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode ann : allAnnotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc)) continue;
            if (annotationValue(ann, "value") instanceof List<?> types) {
                for (Object t : types) targets.add(((Type) t).getInternalName());
            }
            if (annotationValue(ann, "targets") instanceof List<?> names) {
                for (Object n : names) targets.add(n.toString().replace('.', '/'));
            }
        }
        return targets;
    }

    private static boolean hasAnnotation(List<AnnotationNode> vis, List<AnnotationNode> invis, String desc) {
        return allAnnotations(vis, invis).stream().anyMatch(a -> desc.equals(a.desc));
    }

    private static List<AnnotationNode> allAnnotations(List<AnnotationNode> vis, List<AnnotationNode> invis) {
        List<AnnotationNode> out = new ArrayList<>();
        if (vis != null) out.addAll(vis);
        if (invis != null) out.addAll(invis);
        return out;
    }

    private static Object annotationValue(AnnotationNode ann, String key) {
        if (ann.values != null) {
            for (int i = 0; i + 1 < ann.values.size(); i += 2) {
                if (key.equals(ann.values.get(i))) return ann.values.get(i + 1);
            }
        }
        return null;
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (var in = DroneClientMixinContractTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "Class not found: " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
