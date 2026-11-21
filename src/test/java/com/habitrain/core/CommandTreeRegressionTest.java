package com.habitrain.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandTreeRegressionTest {

    @Test
    void roleApiManifestIsDirectChildOfRoleApiNotConfig() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CommandRegistrar.roleApiRootCommand());

        CommandNode<CommandSourceStack> habitrain = dispatcher.getRoot().getChild("habitrain");
        assertNotNull(habitrain, "/habitrain must be registered");

        CommandNode<CommandSourceStack> roleapi = habitrain.getChild("roleapi");
        assertNotNull(roleapi, "/habitrain roleapi must be registered");
        assertNotNull(roleapi.getChild("manifest"),
                "/habitrain roleapi manifest must be directly under roleapi");

        CommandNode<CommandSourceStack> config = roleapi.getChild("config");
        assertNotNull(config, "/habitrain roleapi config must be registered");
        assertNull(config.getChild("manifest"),
                "manifest must not be nested under /habitrain roleapi config");
    }

    @Test
    void voiceGroupUsesStandaloneHabiGroupCommand() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CommandRegistrar.habiGroupCommand());

        CommandNode<CommandSourceStack> habiGroup = dispatcher.getRoot().getChild("habi_group");
        assertNotNull(habiGroup, "/habi_group must be registered");
        assertNotNull(habiGroup.getChild("range"), "/habi_group [range] must accept a range argument");
        assertNull(dispatcher.getRoot().getChild("instantgroup"),
                "habitrain_core must not register /instantgroup");
    }
}
