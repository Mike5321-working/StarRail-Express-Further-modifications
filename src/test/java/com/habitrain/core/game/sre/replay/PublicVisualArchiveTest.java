package com.habitrain.core.game.sre.replay;

import com.habitrain.core.network.replay.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicVisualArchiveTest {
    @TempDir Path root;
    @Test void settledPerspectiveAndWorldAreIndependentlyReadableWithoutVoiceAcrossRestart() throws Exception {
        var store = new ReplayArchive(root);
        UUID round = UUID.randomUUID(), player = UUID.randomUUID();
        var query = new VisualPackets.Request(round,player,0);
        assertEquals(-1,store.visualPage(query).total());
        var tape = new VisualTape.Builder();
        tape.append(VisualTape.WORLD,0,VisualTape.CLOCK,new byte[]{7});
        var camera = new VisualFrames.Camera(1,2,3,20,30,90,true,false);
        tape.append(player,5,VisualTape.CAMERA,camera.encode());
        byte[] summary = {9,8};
        assertTrue(store.save(round,1,"f".repeat(64),summary,List.of(new byte[]{2}),new byte[]{99},tape.compress()));
        var reopened = new ReplayArchive(root);
        assertArrayEquals(summary,reopened.summary(round)); assertEquals(store.used(),reopened.used());
        assertEquals(camera,VisualFrames.Camera.read(VisualTape.decode(reopened.visualPage(query).data()).getFirst().input()));
        assertEquals(VisualTape.CLOCK,VisualTape.decode(reopened.visualPage(new VisualPackets.Request(round,VisualTape.WORLD,0)).data()).getFirst().kind());
        assertEquals(0,reopened.visualPage(new VisualPackets.Request(round,UUID.randomUUID(),0)).total());
        assertArrayEquals(new byte[]{99},reopened.voice(round));
    }
    @Test void legacyAndQuotaEvictionCannotExposeAStaleVisualPage() throws Exception {
        var store = new ReplayArchive(root); var legacy=UUID.randomUUID();
        store.save(legacy,0,"f".repeat(64),new byte[]{1},List.of(new byte[]{2}));
        assertEquals(0,store.visualPage(new VisualPackets.Request(legacy,VisualTape.WORLD,0)).total());
        var id=UUID.randomUUID(); var user=UUID.randomUUID(); var packed=new byte[VisualTape.PAGE*20+4]; new Random(7).nextBytes(packed);
        store.save(id,1,"f".repeat(64),new byte[]{3},List.of(new byte[]{2}),new byte[0],Map.of(user,packed));
        assertThrows(java.io.IOException.class,()->store.visualPage(new VisualPackets.Request(id,user,1)));
        int offset=VisualTape.PAGE*20;
        assertArrayEquals(Arrays.copyOfRange(packed,offset,packed.length),store.visualPage(new VisualPackets.Request(id,user,offset)).data());
        store.setLimit(1<<20); assertEquals(-1,store.visualPage(new VisualPackets.Request(id,user,0)).total());
    }
}
