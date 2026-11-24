package com.habitrain.core.network.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import com.habitrain.core.scene.replay.ReplayTrackSampler;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayPovRecordingTest {
    @Test
    void legacySectionChecksumIsUnchangedButRecordedLightAffectsIt() {
        var palette = List.of("minecraft:air", "minecraft:stone");
        var states = new short[4096];
        states[0] = 1;
        var sky = new byte[2048];
        Arrays.fill(sky, (byte) 0xff);
        var block = new byte[2048];
        var section = new SceneAssetCodec.SectionData(0, 0, 0, palette, states, sky, block);
        CRC32 legacy = new CRC32();
        for (String state : palette) {
            legacy.update(state.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            legacy.update(0);
        }
        for (short state : states) {
            legacy.update(state & 255);
            legacy.update(state >>> 8);
        }
        assertFalse(ReplaySectionGeometry.hasRecordedLight(section));
        assertEquals((int) legacy.getValue(), ReplaySectionGeometry.crc(section));
        block[128] = 12;
        assertTrue(ReplaySectionGeometry.hasRecordedLight(section));
        assertNotEquals((int) legacy.getValue(), ReplaySectionGeometry.crc(section));
        block[128] = 0;
        sky[128] = 0;
        assertTrue(ReplaySectionGeometry.hasRecordedLight(section));
        assertNotEquals((int) legacy.getValue(), ReplaySectionGeometry.crc(section));
    }

    @Test
    void playbackRetainsRecordedHandAndUseTimer() {
        int flags = ACT_LEFT_HANDED | ACT_USING_ITEM | ACT_USING_OFF_HAND | (72_000 << ACT_USE_REMAINING_SHIFT);
        var position = new LastMatchReplay.Position((short) 0, (short) 0, (short) 0);
        var sample = new LastMatchReplay.Sample(10, position, (byte) 0, ALIVE, (byte) 0, (byte) 0,
                flags, 0, "", "minecraft:bow", null);
        var wire = ReplayTracks.encodeV2(List.of(List.of(sample)));
        var track = new ReplayTrackSampler(ReplayTracks.decode(wire, 1, 10).getFirst());
        var pose = track.sample(10);
        assertTrue(pose.hasAction(ACT_LEFT_HANDED));
        assertTrue(pose.hasAction(ACT_USING_OFF_HAND));
        assertEquals(72_000, pose.actionFlags() >>> ACT_USE_REMAINING_SHIFT);
        assertEquals("minecraft:bow", pose.offHandItemId());
    }
}
