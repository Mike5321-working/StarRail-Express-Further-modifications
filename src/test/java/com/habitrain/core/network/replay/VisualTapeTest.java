package com.habitrain.core.network.replay;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VisualTapeTest {
    @Test void viewpointIncludesBothHandsWithinWireLimit() throws IOException {
        var camera=new VisualFrames.Camera(0,64,2,90,18,72,false,false,3,new VisualFrames.Hands(1,.8f,.1f,.2f,.3f,.6f,true));
        assertEquals(64,camera.encode().length); assertEquals(camera,VisualFrames.Camera.read(new DataInputStream(new ByteArrayInputStream(camera.encode()))));
    }
    @Test void publicVisualArchiveOnlyContainsSettledParticipantsAndWorld() throws IOException {
        var tape=new VisualTape.Builder(); var player=UUID.randomUUID(); var other=UUID.randomUUID();
        tape.append(VisualTape.WORLD,0,VisualTape.CLOCK,new byte[0]); tape.append(player,0,VisualTape.CAMERA,new byte[0]); tape.append(other,0,VisualTape.CAMERA,new byte[0]);
        assertEquals(Set.of(VisualTape.WORLD,player),tape.compress(Set.of(player)).keySet());
    }
    @Test void persistsObserverVisibleCorpseAndRemovalWithoutCopyingInventory() throws IOException {
        var id=UUID.randomUUID(); var owner=UUID.randomUUID(); var disguise=UUID.randomUUID();
        var body=new CorpseAppearance(id,owner,disguise,-37,8,64,-9,90,-35,91,88,1.25f,false,true);
        var tape=new VisualTape.Builder(); tape.append(VisualTape.WORLD,3,VisualTape.CORPSE,body.encode());
        tape.append(VisualTape.WORLD,21,VisualTape.CORPSE,CorpseAppearance.removal(id));
        var events=VisualTape.decode(tape.compress().get(VisualTape.WORLD));
        var created=events.getFirst().input(); assertEquals(body,CorpseAppearance.read(CorpseAppearance.uuid(created),created));
        var removed=events.getLast().input(); assertEquals(id,CorpseAppearance.uuid(removed)); assertNull(CorpseAppearance.read(id,removed));
        assertThrows(IOException.class,()->CorpseAppearance.read(id,new DataInputStream(new ByteArrayInputStream(new byte[]{1,2}))));
    }
    @Test void sparseChangesSurviveSeekAndASecondKeyframe() throws Exception {
        byte[] old = new byte[300], next=old.clone(); next[1]=8; next[299]=3;
        var tape=new VisualTape.Builder(); var id=UUID.randomUUID();
        tape.append(id,0,VisualTape.HUD,old); tape.append(id,2,VisualTape.HUD,next); tape.append(id,101,VisualTape.HUD,next);
        var events=VisualTape.decode(tape.compress().get(id));
        assertEquals(List.of(5,9,5),events.stream().map(VisualTape.Event::kind).toList());
        assertArrayEquals(next,VisualTape.applyDifference(old,events.get(1).input()));
        assertArrayEquals(next,events.get(2).data());
        assertThrows(IOException.class,()->VisualTape.decode(new byte[]{1,2,3}));
    }
    @Test void rejectsNonfiniteCameraMalformedHudAndOutOfBoundsDelta() {
        byte[] camera=new VisualFrames.Camera(Double.NaN,0,0,0,0,90,true,false).encode();
        assertFalse(VisualFrames.valid(camera,new byte[0]));
        assertFalse(VisualFrames.valid(new byte[0],new byte[]{1,2,3}));
        byte[] invalid={0,2,0,1,0,3,0,0,0};
        assertThrows(IOException.class,()->VisualTape.applyDifference(new byte[0],new DataInputStream(new ByteArrayInputStream(invalid))));
    }
}
