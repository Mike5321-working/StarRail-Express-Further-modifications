package com.habitrain.core.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies client rest-area lobby-inventory state transitions and upstream mixin target contracts.
 */
class EliminatedRestLobbyInventoryTest {

    @BeforeEach
    @AfterEach
    void resetState() {
        EliminatedRestPromptState.clear();
    }

    @Test
    void restingStateDistinguishesLobbyRestFromSpectatingAndIdle() {
        assertFalse(EliminatedRestPromptState.isResting(), "initial state must not be resting");

        // Eliminated spectator who can enter rest area: visible=true, canToggle=true
        EliminatedRestPromptState.update(true, true);
        assertTrue(EliminatedRestPromptState.isVisible());
        assertTrue(EliminatedRestPromptState.canToggle());
        assertFalse(EliminatedRestPromptState.isResting(), "spectator prompt visible means not yet in lobby rest");

        // Entered lobby rest area: visible=false, canToggle=true
        EliminatedRestPromptState.update(false, true);
        assertFalse(EliminatedRestPromptState.isVisible());
        assertTrue(EliminatedRestPromptState.canToggle());
        assertTrue(EliminatedRestPromptState.isResting(), "canToggle && !visible identifies lobby rest");

        // Cleared on game end / revival / disconnect
        EliminatedRestPromptState.clear();
        assertFalse(EliminatedRestPromptState.isVisible());
        assertFalse(EliminatedRestPromptState.canToggle());
        assertFalse(EliminatedRestPromptState.isResting());
    }

    @Test
    void closePolicyClosesOnlyWhenLeavingRestDuringActiveMatch() {
        assertFalse(EliminatedRestPromptState.shouldCloseRestingInventory(true, true, true),
                "keep open while still resting in lobby");
        assertTrue(EliminatedRestPromptState.shouldCloseRestingInventory(true, false, true),
                "close when revived or returned to spectator while match still runs");
        assertFalse(EliminatedRestPromptState.shouldCloseRestingInventory(true, false, false),
                "keep open seamlessly when match ends into normal lobby");
        assertFalse(EliminatedRestPromptState.shouldCloseRestingInventory(false, false, true),
                "do not close normal in-game inventory");
        assertFalse(EliminatedRestPromptState.shouldCloseRestingInventory(false, true, true),
                "do not close screen not opened while resting");
    }

    @Test
    void restLobbyInventoryMixinTargetsExistInUpstreamBytecode() throws Exception {
        // Verify mixins are registered
        try (var in = getClass().getClassLoader().getResourceAsStream("habitrain_core.client.mixins.json")) {
            assertNotNull(in, "habitrain_core.client.mixins.json missing");
            var root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> registered = new HashSet<>();
            root.getAsJsonArray("client").forEach(e -> registered.add(e.getAsString()));
            assertTrue(registered.contains("LimitedInventoryTicketMixin"));
            assertTrue(registered.contains("RoleSelectionShopLayoutMixin"));
            assertTrue(registered.contains("LustBackpackMixin"));
            assertTrue(registered.contains("SlothBackpackSleepMixin"));
            assertTrue(registered.contains("DollTailorBackpackMixin"));
        }

        ClassNode inventoryScreen = readClass("io/wifi/starrailexpress/client/gui/screen/ingame/LimitedInventoryScreen");
        assertTrue(hasMethod(inventoryScreen, "isGameActive", "()Z"),
                "LimitedInventoryScreen#isGameActive()Z must exist");
        assertTrue(hasMethod(inventoryScreen, "getShopEntries", "()Ljava/util/List;"),
                "LimitedInventoryScreen#getShopEntries() must exist");

        MethodNode renderMethod = inventoryScreen.methods.stream()
                .filter(m -> "render".equals(m.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("LimitedInventoryScreen#render missing"));
        boolean invokesStoreHud = false;
        for (var insn : renderMethod.instructions) {
            if (insn instanceof MethodInsnNode call
                    && "io/wifi/starrailexpress/client/gui/StoreRenderer".equals(call.owner)
                    && "renderHud".equals(call.name)) {
                invokesStoreHud = true;
                break;
            }
        }
        assertTrue(invokesStoreHud, "LimitedInventoryScreen#render must invoke StoreRenderer.renderHud");

        ClassNode roleScreenHelper = readClass("org/agmas/noellesroles/client/RoleScreenHelper");
        assertTrue(hasMethod(roleScreenHelper, "isRoleActive", "()Z"),
                "RoleScreenHelper#isRoleActive()Z must exist");

        ClassNode onOpenInventory = readClass("io/wifi/starrailexpress/event/OnOpenInventory");
        assertTrue(onOpenInventory.methods.stream().anyMatch(m -> "needOpenLimittedInventory".equals(m.name)),
                "OnOpenInventory#needOpenLimittedInventory must exist");
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> name.equals(m.name) && desc.equals(m.desc));
    }

    private static ClassNode readClass(String internalName) throws Exception {
        try (var in = EliminatedRestLobbyInventoryTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "Missing class on classpath: " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
