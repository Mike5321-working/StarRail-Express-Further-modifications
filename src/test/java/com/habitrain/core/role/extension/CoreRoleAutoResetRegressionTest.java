package com.habitrain.core.role.extension;

import com.habitrain.core.client.mixin.InstinctCacheFixMixin;
import com.habitrain.core.game.sre.mixin.SRERoleItemsMixin;
import com.habitrain.core.game.sre.mixin.SRERoleNameMixin;
import com.habitrain.core.game.sre.mixin.SRERoleShopMixin;
import com.habitrain.core.game.sre.mixin.ShopContentRoleExtensionMixin;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRoleAutoResetRegressionTest {

    @Test
    void disableUpstreamAutoResetTurnsOffAutoResetOnDefaultRoles() {
        SRERole greedLike = new NormalRole(
                ResourceLocation.parse("habitrain_core:test_greed"),
                0xFFC8A014, false, false, SRERole.MoodType.REAL, 100, true);
        assertTrue(greedLike.isAutoReset(), "SRERole defaults autoReset to true");

        CoreRoleExtensionProvider.disableUpstreamAutoReset(greedLike, null);

        assertFalse(greedLike.isAutoReset(),
                "Roles initialized by v2 onAssigned hooks must disable upstream autoReset to avoid double init()");
    }

    @Test
    void sreRoleMixinsDoNotDefineConflictingGetServerMethods() {
        for (Class<?> mixinClass : new Class<?>[]{
                SRERoleNameMixin.class,
                SRERoleShopMixin.class,
                SRERoleItemsMixin.class,
                ShopContentRoleExtensionMixin.class
        }) {
            for (Method method : mixinClass.getDeclaredMethods()) {
                assertFalse("getServer".equals(method.getName()),
                        mixinClass.getSimpleName() + " must not declare getServer() (causes Mixin overwrite conflict)");
            }
        }
    }

    @Test
    void instinctCacheFixRedirectHasNoRedundantUniqueAnnotation() throws Exception {
        String resource = InstinctCacheFixMixin.class.getName().replace('.', '/') + ".class";
        AtomicBoolean foundMethod = new AtomicBoolean(false);
        AtomicBoolean hasUnique = new AtomicBoolean(false);
        try (InputStream in = InstinctCacheFixMixin.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "Compiled InstinctCacheFixMixin.class must be on test classpath");
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!"habitrain$snapshotEntrySet".equals(name)) {
                        return null;
                    }
                    foundMethod.set(true);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if ("Lorg/spongepowered/asm/mixin/Unique;".equals(desc)) {
                                hasUnique.set(true);
                            }
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(foundMethod.get(), "habitrain$snapshotEntrySet must exist");
        assertFalse(hasUnique.get(),
                "Injector method habitrain$snapshotEntrySet must not carry redundant @Unique");
    }
}
