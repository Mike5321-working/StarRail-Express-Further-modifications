package com.habitrain.core.verification;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ProductionMixinVerifierTest {
    @Test void aClientOnlyDependencyCannotSatisfyTheDedicatedProfile() {
        var failure = assertThrows(IllegalStateException.class, () -> verify(
                "{\"id\":\"starrailexpress\",\"depends\":{\"modmenu\":\"*\"}}",
                "{\"id\":\"modmenu\",\"environment\":\"client\"}"));
        assertTrue(failure.getMessage().contains("requires modmenu"));
    }

    @Test void aServerProfileWithoutTheClientDependencyPassesTheMetadataCheck() {
        assertDoesNotThrow(() -> verify("{\"id\":\"starrailexpress\",\"environment\":\"server\"}"));
    }

    private static void verify(String... metadata) {
        ProductionMixinVerifier.checkDedicatedCompatibility(Arrays.stream(metadata)
                .map(json -> JsonParser.parseString(json).getAsJsonObject()).toList());
    }
}
