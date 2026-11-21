package com.habitrain.core.game.sre.task;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomTaskBlockScannerTest {
    @Test void loadedAreaMustStillMatchThePublishedMapAndBounds() {
        var source = new CustomTaskBlockScanKey("minecraft:overworld", "station", 10, 20, 30, 110, 70, 130);
        var original = new BoundingBox(10, 20, 30, 110, 70, 130);
        assertTrue(CustomTaskBlockScanner.matchesArea(source, "station", original));
        assertFalse(CustomTaskBlockScanner.matchesArea(source, "station2", original));
        assertFalse(CustomTaskBlockScanner.matchesArea(source, "station", new BoundingBox(11, 20, 30, 111, 70, 130)));
        assertFalse(CustomTaskBlockScanner.matchesArea(source, "station", null));
    }
}
