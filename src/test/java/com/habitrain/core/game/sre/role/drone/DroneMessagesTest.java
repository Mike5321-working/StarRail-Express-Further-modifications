package com.habitrain.core.game.sre.role.drone;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class DroneMessagesTest {

    private static final Path ZH_PATH = Path.of("src", "main", "resources", "assets", "habitrain_core", "lang", "zh_cn.json");
    private static final Path EN_PATH = Path.of("src", "main", "resources", "assets", "habitrain_core", "lang", "en_us.json");

    @Test
    void droneLanguageEntriesContainNoLegacySectionCodesAndAreSymmetric() throws Exception {
        JsonObject zh = readJson(ZH_PATH);
        JsonObject en = readJson(EN_PATH);

        Set<String> zhDroneKeys = new TreeSet<>();
        Set<String> enDroneKeys = new TreeSet<>();

        for (Map.Entry<String, JsonElement> entry : zh.entrySet()) {
            if (entry.getKey().contains(".drone.")) {
                zhDroneKeys.add(entry.getKey());
                String value = entry.getValue().getAsString();
                assertFalse(value.contains("\u00A7"),
                        () -> "zh_cn.json drone entry still contains legacy '§' code: " + entry.getKey() + " -> " + value);
            }
        }
        for (Map.Entry<String, JsonElement> entry : en.entrySet()) {
            if (entry.getKey().contains(".drone.")) {
                enDroneKeys.add(entry.getKey());
                String value = entry.getValue().getAsString();
                assertFalse(value.contains("\u00A7"),
                        () -> "en_us.json drone entry still contains legacy '§' code: " + entry.getKey() + " -> " + value);
            }
        }

        assertEquals(zhDroneKeys, enDroneKeys, "Drone keys must match between zh_cn.json and en_us.json");
        assertTrue(zhDroneKeys.stream().filter(k -> k.startsWith("message.habitrain_core.drone.")).count() >= 40);
    }

    @Test
    void allDroneMessagesRenderWithSingleUniformColorAcrossPlaceholdersInBothLanguages() throws Exception {
        for (Path langPath : List.of(ZH_PATH, EN_PATH)) {
            JsonObject root = readJson(langPath);
            Map<String, String> table = new HashMap<>();
            List<String> messageKeys = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                table.put(entry.getKey(), entry.getValue().getAsString());
                if (entry.getKey().startsWith("message.habitrain_core.drone.")) {
                    messageKeys.add(entry.getKey());
                }
            }
            assertEquals(40, messageKeys.size(), () -> "Unexpected drone message count in " + langPath);

            Language previous = Language.getInstance();
            Language injected = new MapBackedLanguage(table);
            Language.inject(injected);
            try {
                Component styledPlayer = Component.literal("\u00A7aSteve").withStyle(ChatFormatting.WHITE);
                Component styledTranslatableItem = Component.translatable("item.habitrain_core.drone_fake_knife")
                        .withStyle(ChatFormatting.GOLD);
                Component styledCustomItem = Component.literal("\u00A7e[TAG] ")
                        .withStyle(ChatFormatting.BOLD)
                        .append(Component.literal("\u00A7bSpecial Knife").withStyle(ChatFormatting.DARK_GREEN));

                for (String key : messageKeys) {
                    List<Component> variants = switch (key) {
                        case "message.habitrain_core.drone.mounted",
                             "message.habitrain_core.drone.unloaded",
                             "message.habitrain_core.drone.unloaded_destroyed" -> List.of(
                                DroneMessages.create(key, styledTranslatableItem),
                                DroneMessages.create(key, styledCustomItem)
                        );
                        case "message.habitrain_core.drone.ally_mounted",
                             "message.habitrain_core.drone.ally_unloaded_destroyed" -> List.of(
                                DroneMessages.create(key, styledPlayer, styledTranslatableItem),
                                DroneMessages.create(key, styledPlayer, styledCustomItem)
                        );
                        default -> List.of(DroneMessages.create(key));
                    };

                    TextColor expectedColor = TextColor.fromLegacyFormat(DroneMessages.colorForKey(key));
                    assertNotNull(expectedColor, "Missing TextColor for " + key);

                    for (Component msg : variants) {
                        assertUniformColor(langPath.getFileName() + " " + key, msg, expectedColor);
                    }
                }
            } finally {
                Language.inject(previous);
            }
        }
    }

    @Test
    void unstyledCopyPreservesTranslationKeysWhileStrippingStylesAndLegacyCodes() {
        Component nested = Component.translatable(
                "item.custom.nested",
                Component.literal("\u00A7cRedArg").withStyle(ChatFormatting.RED),
                "\u00A7eRawString"
        ).withStyle(ChatFormatting.BLUE)
                .append(Component.literal("\u00A7lSuffix").withStyle(ChatFormatting.GREEN));

        Component cleaned = DroneMessages.unstyledCopy(nested);
        assertEquals(Style.EMPTY, cleaned.getStyle());
        assertTrue(cleaned.getContents() instanceof TranslatableContents);
        TranslatableContents tc = (TranslatableContents) cleaned.getContents();
        assertEquals("item.custom.nested", tc.getKey());
        assertEquals("RedArg", ((Component) tc.getArgs()[0]).getString());
        assertEquals(Style.EMPTY, ((Component) tc.getArgs()[0]).getStyle());
        assertEquals("RawString", tc.getArgs()[1]);
        assertEquals(1, cleaned.getSiblings().size());
        assertEquals("Suffix", cleaned.getSiblings().getFirst().getString());
        assertEquals(Style.EMPTY, cleaned.getSiblings().getFirst().getStyle());

        Component withFallback = Component.translatableWithFallback("missing.key", "\u00A76Fallback \u00A7kName");
        Component cleanedFallback = DroneMessages.unstyledCopy(withFallback);
        assertTrue(cleanedFallback.getContents() instanceof TranslatableContents);
        assertEquals("Fallback Name", ((TranslatableContents) cleanedFallback.getContents()).getFallback());
        assertEquals("", DroneMessages.stripCodes(null));
    }

    private static void assertUniformColor(String label, Component component, TextColor expectedColor) {
        StringBuilder rendered = new StringBuilder();
        List<String> mismatches = new ArrayList<>();

        FormattedCharSequence visual = component.getVisualOrderText();
        visual.accept((index, style, codePoint) -> {
            String ch = new String(Character.toChars(codePoint));
            rendered.append(ch);
            if (!Character.isWhitespace(codePoint)) {
                TextColor actual = style.getColor();
                if (!expectedColor.equals(actual)) {
                    mismatches.add("'" + ch + "'@" + index + "=" + actual);
                }
            }
            return true;
        });

        // Also check direct StringDecomposer traversal without visual reorder
        StringDecomposer.iterateFormatted(component, Style.EMPTY, (index, style, codePoint) -> {
            if (!Character.isWhitespace(codePoint)) {
                TextColor actual = style.getColor();
                if (!expectedColor.equals(actual)) {
                    mismatches.add("raw '" + new String(Character.toChars(codePoint)) + "'@" + index + "=" + actual);
                }
            }
            return true;
        });

        String text = rendered.toString();
        assertFalse(text.isBlank(), label + " rendered blank");
        assertFalse(text.contains("\u00A7"), label + " rendered raw '§': " + text);
        assertTrue(mismatches.isEmpty(),
                () -> label + " expected uniform color " + expectedColor + " in \"" + text + "\" but found: " + mismatches);
    }

    private static JsonObject readJson(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final class MapBackedLanguage extends Language {
        private final Map<String, String> entries;

        private MapBackedLanguage(Map<String, String> entries) {
            this.entries = entries;
        }

        @Override
        public String getOrDefault(String id, String defaultValue) {
            return entries.getOrDefault(id, defaultValue);
        }

        @Override
        public boolean has(String id) {
            return entries.containsKey(id);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return false;
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            return sink -> text.visit(
                    (style, content) -> StringDecomposer.iterateFormatted(content, style, sink)
                            ? java.util.Optional.empty()
                            : FormattedText.STOP_ITERATION,
                    Style.EMPTY
            ).isEmpty();
        }
    }
}
