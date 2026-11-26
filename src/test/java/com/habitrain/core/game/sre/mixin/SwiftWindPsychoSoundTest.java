package com.habitrain.core.game.sre.mixin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.client.audio.SwiftWindPsychoAudioBoost;
import net.minecraft.client.sounds.JOrbisAudioStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.sound.sampled.AudioFormat;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SwiftWindPsychoSoundTest {
    @Test void soundBroadcastsGloballyOnlyAfterSuccessfulFrenzyAndRoleChecks() throws Exception {
        var method = method("com/habitrain/core/game/sre/mixin/SwiftWindPsychoMixin", "habitrain$refreshSkills");
        JumpInsnNode successGuard = null;
        JumpInsnNode roleGuard = null;
        boolean iteratedServerPlayers = false;
        boolean usedMasterSource = false;
        boolean usedSwiftWindSound = false;
        Float broadcastVolume = null;
        int notifySounds = 0;
        int positionalSounds = 0;

        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                if (field.name.equals("SWIFT_WIND_PSYCHO_SOUND")) usedSwiftWindSound = true;
                if (field.name.equals("MASTER") && field.owner.equals("net/minecraft/sounds/SoundSource")) {
                    usedMasterSource = true;
                }
            }
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Float f && f > 1.0f) {
                broadcastVolume = f;
            }
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.name.equals("getReturnValueZ")) successGuard = (JumpInsnNode) nextOpcode(call);
            if (call.name.equals("isHabiRole")) roleGuard = (JumpInsnNode) nextOpcode(call);
            if (call.name.equals("players") && call.owner.equals("net/minecraft/server/level/ServerLevel")) {
                iteratedServerPlayers = true;
            }
            if (call.name.equals("playSound")) {
                positionalSounds++;
            }
            if (call.name.equals("playNotifySound")) {
                notifySounds++;
                assertNotNull(successGuard);
                assertNotNull(roleGuard);
                assertEquals(Opcodes.IFEQ, successGuard.getOpcode());
                assertEquals(Opcodes.IFEQ, roleGuard.getOpcode());
                assertSame(successGuard.label, roleGuard.label);
                assertTrue(method.instructions.indexOf(call) < method.instructions.indexOf(successGuard.label));
            }
        }
        assertEquals(0, positionalSounds, "Swift Wind psycho sound must broadcast globally, not positionally");
        assertEquals(1, notifySounds);
        assertTrue(iteratedServerPlayers, "Must iterate ServerLevel#players() for full-map playback");
        assertTrue(usedSwiftWindSound);
        assertTrue(usedMasterSource, "Global frenzy cue should use SoundSource.MASTER");
        assertNotNull(broadcastVolume, "Broadcast volume should exceed 1.0f");
        assertTrue(broadcastVolume >= 2.0f, "Expected boosted broadcast volume, got " + broadcastVolume);
    }

    @Test void soundIsRegisteredAndPackagedAsMonoVorbis() throws Exception {
        var register = method("com/habitrain/core/HabiTrainCore", "registerMoreSounds");
        boolean registered = false;
        for (var instruction : register.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("SWIFT_WIND_PSYCHO_SOUND")) {
                assertEquals("register", ((MethodInsnNode) nextOpcode(field)).name);
                registered = true;
            }
        }
        assertTrue(registered);
        var sound = json("sounds.json").getAsJsonObject("swift_wind_psycho");
        assertEquals("habitrain_core:swift_wind_psycho", sound.getAsJsonArray("sounds").get(0).getAsString());
        String subtitle = sound.get("subtitle").getAsString();
        for (String language : new String[]{"zh_cn", "en_us"}) {
            var translations = json("lang/" + language + ".json");
            assertFalse(translations.get(subtitle).getAsString().isBlank());
            String emptyShop = translations.get("message.habitrain_core.snake_fruit.empty_shop").getAsString();
            assertFalse(emptyShop.startsWith("["));
            assertFalse(translations.has("message.habitrain_core.snake_fruit.used"));
        }
        try (var stream = getClass().getResourceAsStream("/assets/habitrain_core/sounds/swift_wind_psycho.ogg")) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            assertEquals("OggS", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
            int header = 27 + Byte.toUnsignedInt(bytes[26]);
            assertEquals(1, bytes[header]);
            assertEquals("vorbis", new String(bytes, header + 1, 6, StandardCharsets.US_ASCII));
            assertEquals(1, bytes[header + 11], "Source asset remains mono vorbis");
            assertEquals(44100, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(header + 12));
        }
    }

    @Test void clientAudioBoostRaisesRealAssetLoudnessAndUpmixesToStereoWithoutClipping() throws Exception {
        try (var raw = getClass().getResourceAsStream("/assets/habitrain_core/sounds/swift_wind_psycho.ogg")) {
            assertNotNull(raw);
            try (var vorbis = new JOrbisAudioStream(raw)) {
                AudioFormat originalFormat = vorbis.getFormat();
                ByteBuffer originalPcm = vorbis.readAll();
                double originalRms = pcm16Rms(originalPcm);

                var enhanced = SwiftWindPsychoAudioBoost.enhancePcm16(originalPcm, originalFormat);
                assertTrue(enhanced.data().isDirect(), "OpenAL requires a direct ByteBuffer");
                assertEquals(ByteOrder.LITTLE_ENDIAN, enhanced.data().order());
                assertEquals(2, enhanced.format().getChannels(), "Mono cue must upmix to stereo");
                assertEquals(originalFormat.getSampleRate(), enhanced.format().getSampleRate());
                assertEquals(16, enhanced.format().getSampleSizeInBits());
                assertEquals(originalPcm.remaining() * 2, enhanced.data().remaining());

                ByteBuffer stereo = enhanced.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int maxAbs = 0;
                while (stereo.remaining() >= 4) {
                    short left = stereo.getShort();
                    short right = stereo.getShort();
                    assertEquals(left, right, "Upmixed stereo channels must stay phase-aligned");
                    maxAbs = Math.max(maxAbs, Math.abs((int) left));
                }

                double boostedRms = pcm16Rms(enhanced.data());
                double gainDb = 20.0 * Math.log10(boostedRms / originalRms);
                assertTrue(gainDb >= 7.5, "Expected >= +7.5 dB PCM RMS boost, got +" + gainDb + " dB");
                assertTrue(maxAbs < Short.MAX_VALUE, "Soft-knee limiter must prevent hard digital clipping");
            }
        }
    }

    @Test void clientAudioBoostHandlesStereoAndUnsupportedFormatsSafely() {
        AudioFormat stereoFormat = new AudioFormat(44100f, 16, 2, true, false);
        ByteBuffer stereoIn = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
        stereoIn.putShort((short) 4000).putShort((short) -4000)
                .putShort((short) 30000).putShort((short) -30000).flip();
        var enhanced = SwiftWindPsychoAudioBoost.enhancePcm16(stereoIn, stereoFormat);
        assertEquals(2, enhanced.format().getChannels());
        assertEquals(8, enhanced.data().remaining());
        ByteBuffer out = enhanced.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(Math.round(4000 * SwiftWindPsychoAudioBoost.LINEAR_GAIN * 32767.0 / 32768.0), out.getShort(), 1);
        assertEquals(-Math.round(4000 * SwiftWindPsychoAudioBoost.LINEAR_GAIN * 32767.0 / 32768.0), out.getShort(), 1);
        assertTrue(out.getShort() < Short.MAX_VALUE);
        assertTrue(out.getShort() > Short.MIN_VALUE);

        AudioFormat unsupported = new AudioFormat(44100f, 8, 1, false, false);
        ByteBuffer raw = ByteBuffer.allocateDirect(4);
        var untouched = SwiftWindPsychoAudioBoost.enhancePcm16(raw, unsupported);
        assertSame(raw, untouched.data());
        assertSame(unsupported, untouched.format());
    }

    @Test void clientSoundMixinsAreRegisteredAndMatchMinecraftTargets() throws Exception {
        try (var stream = getClass().getResourceAsStream("/habitrain_core.client.mixins.json")) {
            assertNotNull(stream);
            JsonObject config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> clientMixins = new HashSet<>();
            config.getAsJsonArray("client").forEach(el -> clientMixins.add(el.getAsString()));
            assertTrue(clientMixins.contains("SwiftWindPsychoSoundBufferMixin"));
            assertTrue(clientMixins.contains("SwiftWindPsychoClientSoundMixin"));
        }

        var bufferTarget = method("net/minecraft/client/sounds/SoundBufferLibrary", "getCompleteBuffer");
        assertEquals("(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/concurrent/CompletableFuture;",
                bufferTarget.desc);

        var handleSound = method("net/minecraft/client/multiplayer/ClientPacketListener", "handleSoundEvent");
        boolean invokesPlaySeededSound = false;
        for (var insn : handleSound.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/multiplayer/ClientLevel")
                    && call.name.equals("playSeededSound")
                    && call.desc.equals("(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V")) {
                invokesPlaySeededSound = true;
                break;
            }
        }
        assertTrue(invokesPlaySeededSound, "ClientPacketListener#handleSoundEvent must invoke ClientLevel#playSeededSound");
    }

    private static double pcm16Rms(ByteBuffer pcm) {
        ByteBuffer view = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int count = view.remaining() / 2;
        double sumSq = 0.0;
        for (int i = 0; i < count; i++) {
            double v = view.getShort() / 32768.0;
            sumSq += v * v;
        }
        return Math.sqrt(sumSq / Math.max(1, count));
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static MethodNode method(String name, String method) throws Exception {
        try (var stream = SwiftWindPsychoSoundTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        }
    }

    private static JsonObject json(String path) throws Exception {
        try (var stream = SwiftWindPsychoSoundTest.class.getResourceAsStream("/assets/habitrain_core/" + path)) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
