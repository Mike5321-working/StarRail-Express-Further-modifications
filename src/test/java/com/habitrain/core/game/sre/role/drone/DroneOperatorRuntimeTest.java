package com.habitrain.core.game.sre.role.drone;

import com.habitrain.core.game.sre.role.HabiBoundItems;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.sins.item.GreedPouchItem;
import com.habitrain.core.network.DroneInputPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated bootstrapped runtime regression tests for drone payload unloading
 * and {@code DroneOperatorService} message formatting.
 */
class DroneOperatorRuntimeTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void transferUnloadedPayloadCapturesRealItemNameEvenWhenInventoryAddMutatesStackToAir() {
        assertUnloadedNameMatches(Items.IRON_SWORD.getDefaultInstance(), "item.minecraft.iron_sword");
        assertUnloadedNameMatches(Items.BOW.getDefaultInstance(), "item.minecraft.bow");
        assertUnloadedNameMatches(HabiRoleItems.createDroneFakeKnife(), "item.habitrain_core.drone_fake_knife");
        assertUnloadedNameMatches(HabiRoleItems.createPepperSpray(), "item.habitrain_core.pepper_spray");

        ItemStack customNamed = new ItemStack(Items.IRON_SWORD);
        customNamed.set(DataComponents.CUSTOM_NAME,
                Component.literal("\u00A76\u00A7lSharp Blade").withStyle(ChatFormatting.GOLD));
        List<ItemStack> dropped = new ArrayList<>();
        Component capturedCustom = DroneMessages.transferUnloadedPayload(
                customNamed,
                stack -> {
                    // Simulate Inventory.add(ItemStack) consuming the entire stack in place
                    stack.setCount(0);
                    assertTrue(stack.isEmpty());
                    assertEquals("block.minecraft.air",
                            ((TranslatableContents) stack.getHoverName().getContents()).getKey());
                    return true;
                },
                dropped::add
        );
        assertTrue(dropped.isEmpty());
        assertEquals("Sharp Blade", capturedCustom.getString());
        assertNull(capturedCustom.getStyle().getColor());

        Component message = DroneMessages.create("message.habitrain_core.drone.unloaded_destroyed", capturedCustom);
        assertEquals(ChatFormatting.RED.getColor(), message.getStyle().getColor().getValue());
    }

    @Test
    void transferUnloadedPayloadDropsLeftoverWhenInventoryIsFullWhilePreservingName() {
        ItemStack stack = Items.IRON_SWORD.getDefaultInstance();
        List<ItemStack> dropped = new ArrayList<>();

        Component captured = DroneMessages.transferUnloadedPayload(
                stack,
                rejected -> false,
                dropped::add
        );

        assertEquals(1, dropped.size());
        assertTrue(dropped.getFirst().is(Items.IRON_SWORD));
        assertTrue(captured.getContents() instanceof TranslatableContents);
        assertEquals("item.minecraft.iron_sword", ((TranslatableContents) captured.getContents()).getKey());
    }

    @Test
    void unloadPayloadAndDestroyDroneDoesNotInvokeGetHoverNameDirectlyAndAllMessagesUseDroneMessages() throws Exception {
        ClassNode node = new ClassNode();
        try (var source = DroneOperatorRuntimeTest.class.getClassLoader().getResourceAsStream(
                "com/habitrain/core/game/sre/role/drone/DroneOperatorService.class")) {
            assertNotNull(source);
            new ClassReader(source).accept(node, 0);
        }

        MethodNode unloadMethod = node.methods.stream()
                .filter(m -> m.name.equals("unloadPayloadAndDestroyDrone"))
                .findFirst()
                .orElseThrow();

        List<String> unloadCalls = methodCalls(unloadMethod);
        assertTrue(unloadCalls.stream().anyMatch(c ->
                        c.startsWith("com/habitrain/core/game/sre/role/drone/DroneMessages.transferUnloadedPayload(")),
                "unloadPayloadAndDestroyDrone must delegate inventory add & name capture to DroneMessages.transferUnloadedPayload");
        assertTrue(unloadCalls.stream().anyMatch(c ->
                        c.startsWith("com/habitrain/core/game/sre/role/drone/DroneMessages.create(")),
                "unloadPayloadAndDestroyDrone must format messages with DroneMessages.create");
        assertTrue(unloadCalls.stream().noneMatch(c -> c.endsWith(".getHoverName()Lnet/minecraft/network/chat/Component;")),
                "unloadPayloadAndDestroyDrone must not read getHoverName() after Inventory.add");

        for (MethodNode method : node.methods) {
            List<String> calls = methodCalls(method);
            assertTrue(calls.stream().noneMatch(c ->
                            c.startsWith("net/minecraft/network/chat/Component.translatable(")),
                    () -> "DroneOperatorService." + method.name + " must use DroneMessages.create instead of Component.translatable");
        }
    }

    @Test
    void classifyAndBoundItemGuardsHandleForbiddenFakeControlAndLogisticsStacks() {
        ItemStack terminal = HabiRoleItems.createDroneTerminal();
        ItemStack battery = HabiRoleItems.createDroneBattery(2);
        ItemStack resupply = HabiRoleItems.createDroneResupplyIcon();
        ItemStack fakeKnife = HabiRoleItems.createDroneFakeKnife();
        ItemStack pepperSpray = HabiRoleItems.createPepperSpray();

        assertEquals(DronePayloadCategory.NONE, DronePayloadHandler.classify(ItemStack.EMPTY));
        assertEquals(DronePayloadCategory.NONE, DronePayloadHandler.classify(terminal));
        assertEquals(DronePayloadCategory.NONE, DronePayloadHandler.classify(battery));
        assertEquals(DronePayloadCategory.NONE, DronePayloadHandler.classify(resupply));
        assertEquals(DronePayloadCategory.NONE, DronePayloadHandler.classify(Items.BARRIER.getDefaultInstance()));

        assertEquals(DronePayloadCategory.FAKE_BLADE, DronePayloadHandler.classify(fakeKnife));
        assertEquals(DronePayloadCategory.CONTROL, DronePayloadHandler.classify(pepperSpray));
        assertEquals(DronePayloadCategory.CONTROL, DronePayloadHandler.classify(Items.WIND_CHARGE.getDefaultInstance()));
        assertEquals(DronePayloadCategory.LETHAL_BLADE, DronePayloadHandler.classify(Items.TRIDENT.getDefaultInstance()));
        assertEquals(DronePayloadCategory.INFILTRATION_LOGISTICS, DronePayloadHandler.classify(Items.GOLD_INGOT.getDefaultInstance()));

        // Bound item protections cover DroneTerminal, DroneBattery, and DroneFakeKnife
        assertTrue(HabiBoundItems.isBoundNoDrop(terminal));
        assertTrue(HabiBoundItems.isBoundNoDrop(battery));
        assertFalse(HabiBoundItems.isBoundNoDrop(fakeKnife));
        assertTrue(HabiBoundItems.isForbiddenInBundle(terminal));
        assertTrue(HabiBoundItems.isForbiddenInBundle(battery));
        assertTrue(HabiBoundItems.isForbiddenInBundle(fakeKnife));
        assertFalse(GreedPouchItem.canStore(terminal));
        assertFalse(GreedPouchItem.canStore(battery));
        assertFalse(GreedPouchItem.canStore(fakeKnife));
    }

    @Test
    void droneInputPayloadSanitizesNonFiniteFloatsAndClampsAxes() {
        DroneInputPayload nanPayload = new DroneInputPayload(
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, Float.POSITIVE_INFINITY, true
        );
        assertEquals(0.0F, nanPayload.forward());
        assertEquals(0.0F, nanPayload.strafe());
        assertEquals(0.0F, nanPayload.vertical());
        assertEquals(0.0F, nanPayload.yaw());
        assertEquals(0.0F, nanPayload.pitch());
        assertTrue(nanPayload.sprinting());

        DroneInputPayload outOfBounds = new DroneInputPayload(4.5F, -9.0F, 2.2F, 540.0F, -135.0F, false);
        assertEquals(1.0F, outOfBounds.forward());
        assertEquals(-1.0F, outOfBounds.strafe());
        assertEquals(1.0F, outOfBounds.vertical());
        assertTrue(outOfBounds.yaw() >= -180.0F && outOfBounds.yaw() < 180.0F);
        assertEquals(-89.0F, outOfBounds.pitch());
    }

    private static void assertUnloadedNameMatches(ItemStack stack, String expectedKey) {
        Component captured = DroneMessages.transferUnloadedPayload(
                stack,
                added -> {
                    added.setCount(0);
                    assertTrue(added.isEmpty());
                    assertEquals("block.minecraft.air",
                            ((TranslatableContents) added.getHoverName().getContents()).getKey());
                    return true;
                },
                leftover -> fail("Unexpected overflow drop for " + leftover)
        );
        assertTrue(captured.getContents() instanceof TranslatableContents,
                "Expected TranslatableContents for " + stack + ", got " + captured);
        String actualKey = ((TranslatableContents) captured.getContents()).getKey();
        assertNotEquals("block.minecraft.air", actualKey, "Unloaded name fell back to Air!");
        assertEquals(expectedKey, actualKey);
    }

    private static List<String> methodCalls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
