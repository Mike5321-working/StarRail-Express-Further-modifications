package com.habitrain.core.client.audio;

import net.minecraft.resources.ResourceLocation;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Enhances Swift Wind's frenzy activation cue when decoded into an OpenAL {@code SoundBuffer}.
 *
 * <p>Minecraft's {@code SoundEngine.calculateVolume} clamps final source gain to {@code [0, 1]}
 * regardless of the packet's volume argument, and {@code swift_wind_psycho.ogg} is a relatively
 * quiet mono track (-15.1 dBFS RMS) that loses another 3 dB to mono center-panning against the
 * full-map stereo vanilla psycho drone. Applying a soft-limited PCM boost and upmixing to stereo
 * raises perceived loudness cleanly without hard clipping while still respecting the player's
 * Master Volume slider.</p>
 */
public final class SwiftWindPsychoAudioBoost {
    public static final ResourceLocation SOUND_ID =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "swift_wind_psycho");
    public static final ResourceLocation SOUND_FILE =
            ResourceLocation.fromNamespaceAndPath("habitrain_core", "sounds/swift_wind_psycho.ogg");

    public static final double LINEAR_GAIN = 2.8;
    public static final double SOFT_KNEE = 0.72;
    public static final double CEILING = 0.98;

    private SwiftWindPsychoAudioBoost() {}

    public record EnhancedPcm(ByteBuffer data, AudioFormat format) {}

    /**
     * Applies linear gain with a smooth {@code tanh} soft-knee limiter above {@link #SOFT_KNEE}.
     */
    public static double shapeSample(double normalizedSample) {
        double boosted = normalizedSample * LINEAR_GAIN;
        double magnitude = Math.abs(boosted);
        if (magnitude <= SOFT_KNEE) {
            return boosted;
        }
        double headroom = CEILING - SOFT_KNEE;
        double shaped = SOFT_KNEE + headroom * Math.tanh((magnitude - SOFT_KNEE) / headroom);
        return Math.copySign(shaped, boosted);
    }

    /**
     * Boosts 16-bit signed little-endian PCM and upmixes mono buffers to stereo for non-attenuated
     * full-map playback. Returns the original buffer/format unchanged if the input format is not
     * 16-bit signed little-endian mono/stereo PCM.
     */
    public static EnhancedPcm enhancePcm16(ByteBuffer pcm, AudioFormat format) {
        if (pcm == null || format == null) {
            return new EnhancedPcm(pcm, format);
        }
        int channels = format.getChannels();
        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                || format.getSampleSizeInBits() != 16
                || format.isBigEndian()
                || (channels != 1 && channels != 2)) {
            return new EnhancedPcm(pcm, format);
        }

        ByteBuffer src = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int sampleCount = src.remaining() / 2;
        if (sampleCount <= 0) {
            return new EnhancedPcm(pcm, format);
        }

        if (channels == 1) {
            ByteBuffer dst = ByteBuffer.allocateDirect(sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < sampleCount; i++) {
                short boosted = boostShort(src.getShort());
                dst.putShort(boosted);
                dst.putShort(boosted);
            }
            dst.flip();
            AudioFormat stereoFormat = new AudioFormat(format.getSampleRate(), 16, 2, true, false);
            return new EnhancedPcm(dst, stereoFormat);
        }

        ByteBuffer dst = ByteBuffer.allocateDirect(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            dst.putShort(boostShort(src.getShort()));
        }
        dst.flip();
        return new EnhancedPcm(dst, format);
    }

    private static short boostShort(short sample) {
        double normalized = sample / 32768.0;
        double shaped = shapeSample(normalized);
        long quantized = Math.round(shaped * 32767.0);
        return (short) Math.clamp(quantized, Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
