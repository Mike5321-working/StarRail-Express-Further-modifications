package com.habitrain.core.scene.client.replay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReplayVideoTicketModeTest {

    @Test
    void murderModeIdsUseDedicatedVideoTicketTranslationKey() {
        Component fallback = Component.literal("经典列车谋杀案");
        for (String id : new String[]{
                "sre:murder",
                "habitrain_core:sre:murder",
                "habitrain_core:sre/murder",
                "sre/murder",
                "murder",
                "  SRE:MURDER  "
        }) {
            Component mode = ReplayVideoOverlay.ticketMode(id, fallback);
            TranslatableContents contents = assertInstanceOf(TranslatableContents.class, mode.getContents());
            assertEquals("habitrain_core.replay_video.ticket.mode.murder", contents.getKey(), "modeId=" + id);
        }
    }

    @Test
    void nonMurderAndBlankModesPreserveFallbackBehavior() {
        Component repair = Component.literal("修复逃脱模式");
        assertSame(repair, ReplayVideoOverlay.ticketMode("sre:repair", repair));
        assertSame(repair, ReplayVideoOverlay.ticketMode("habitrain:blackout", repair));
        assertEquals("-", ReplayVideoOverlay.ticketMode(null, Component.empty()).getString());
        assertEquals("-", ReplayVideoOverlay.ticketMode("sre:repair", Component.literal("   ")).getString());
    }

    @Test
    void languageFilesOverrideOnlyTicketMurderLabel() throws Exception {
        JsonObject zh = JsonParser.parseString(Files.readString(
                Path.of("src", "main", "resources", "assets", "habitrain_core", "lang", "zh_cn.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject en = JsonParser.parseString(Files.readString(
                Path.of("src", "main", "resources", "assets", "habitrain_core", "lang", "en_us.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();

        assertEquals("苹果列车谋杀案", zh.get("habitrain_core.replay_video.ticket.mode.murder").getAsString());
        assertEquals("经典列车谋杀案", zh.get("vote.habitrain_core.option.habitrain_core.sre.murder").getAsString());
        assertEquals("Apple Train Murder", en.get("habitrain_core.replay_video.ticket.mode.murder").getAsString());
        assertEquals("Classic Train Murder", en.get("vote.habitrain_core.option.habitrain_core.sre.murder").getAsString());
    }
}
