package com.habitrain.core.scene.replay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java binary container for server-recorded proximity voice clips around match highlights.
 *
 * <p>Audio is stored as 24 kHz mono 4-bit IMA ADPCM (~12 KB/s), yielding compact round voice tracks
 * that count directly toward the {@code ReplayArchive} disk quota without any native or voicechat
 * runtime dependencies during playback or video export.</p>
 */
public final class ReplayVoiceData {
    public static final int MAGIC = 0x48525643; // "HRVC"
    public static final int VERSION = 1;
    public static final int SAMPLE_RATE = 24_000;
    public static final int VOICECHAT_SAMPLE_RATE = 48_000;
    public static final int MAX_CLIPS = 32;
    public static final int MAX_CLIP_SAMPLES = SAMPLE_RATE * 6;
    public static final int MAX_VOICE_BYTES = 4 * 1024 * 1024;
    public static final ReplayVoiceData EMPTY = new ReplayVoiceData(List.of());

    private static final int[] INDEX_TABLE = {
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
    };

    private static final int[] STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    /**
     * One recorded proximity voice segment anchored to an event in {@code LastMatchReplay.events()}.
     *
     * @param eventIndex  index into {@code LastMatchReplay.events()}
     * @param tick        match tick of the anchor event (for sanity verification)
     * @param offsetMs    clip start offset relative to {@code tick} in milliseconds (may be negative for lead-in)
     * @param sampleCount number of 24 kHz mono samples encoded in {@code adpcm}
     * @param adpcm       4-byte IMA header (initial predictor + step index) + packed 4-bit ADPCM nibbles
     */
    public record Clip(int eventIndex, int tick, int offsetMs, int sampleCount, byte[] adpcm) {
        public Clip {
            if (eventIndex < 0) throw new IllegalArgumentException("Negative eventIndex");
            if (tick < 0) throw new IllegalArgumentException("Negative tick");
            if (sampleCount < 0 || sampleCount > MAX_CLIP_SAMPLES) {
                throw new IllegalArgumentException("Bad voice sample count: " + sampleCount);
            }
            adpcm = adpcm == null ? new byte[0] : adpcm.clone();
        }

        public byte[] adpcm() {
            return adpcm.clone();
        }

        public double durationSeconds() {
            return sampleCount / (double) SAMPLE_RATE;
        }

        public short[] decode24k() {
            return decodeAdpcm(adpcm, sampleCount);
        }

        public short[] decode48k() {
            return upsample24kTo48k(decodeAdpcm(adpcm, sampleCount));
        }
    }

    private final List<Clip> clips;

    public ReplayVoiceData(List<Clip> clips) {
        if (clips == null || clips.isEmpty()) {
            this.clips = List.of();
        } else if (clips.size() > MAX_CLIPS) {
            this.clips = List.copyOf(clips.subList(0, MAX_CLIPS));
        } else {
            this.clips = List.copyOf(clips);
        }
    }

    public List<Clip> clips() {
        return clips;
    }

    public boolean isEmpty() {
        return clips.isEmpty();
    }

    public byte[] toBytes() {
        if (clips.isEmpty()) return new byte[0];
        int totalSize = 12;
        for (Clip clip : clips) {
            totalSize += 20 + clip.adpcm.length;
        }
        if (totalSize > MAX_VOICE_BYTES) {
            throw new IllegalArgumentException("Voice track exceeds max bytes: " + totalSize);
        }
        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(MAGIC);
        buf.putInt(VERSION);
        buf.putInt(clips.size());
        for (Clip clip : clips) {
            buf.putInt(clip.eventIndex());
            buf.putInt(clip.tick());
            buf.putInt(clip.offsetMs());
            buf.putInt(clip.sampleCount());
            buf.putInt(clip.adpcm.length);
            buf.put(clip.adpcm);
        }
        return buf.array();
    }

    public static ReplayVoiceData fromBytes(byte[] raw) {
        if (raw == null || raw.length == 0) return EMPTY;
        if (raw.length < 12 || raw.length > MAX_VOICE_BYTES) {
            throw new IllegalArgumentException("Invalid voice archive size: " + raw.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        if (buf.getInt() != MAGIC) throw new IllegalArgumentException("Invalid voice magic");
        int version = buf.getInt();
        if (version != VERSION) throw new IllegalArgumentException("Unsupported voice version: " + version);
        int count = buf.getInt();
        if (count < 0 || count > MAX_CLIPS) throw new IllegalArgumentException("Invalid voice clip count: " + count);
        List<Clip> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 20) throw new IllegalArgumentException("Truncated voice clip header");
            int eventIndex = buf.getInt();
            int tick = buf.getInt();
            int offsetMs = buf.getInt();
            int sampleCount = buf.getInt();
            int adpcmLen = buf.getInt();
            if (adpcmLen < 0 || adpcmLen > buf.remaining()) {
                throw new IllegalArgumentException("Invalid ADPCM length: " + adpcmLen);
            }
            if (sampleCount > 0 && adpcmLen < expectedAdpcmBytes(sampleCount)) {
                throw new IllegalArgumentException("Truncated ADPCM data for samples: " + sampleCount);
            }
            byte[] adpcm = new byte[adpcmLen];
            buf.get(adpcm);
            list.add(new Clip(eventIndex, tick, offsetMs, sampleCount, adpcm));
        }
        if (buf.hasRemaining()) throw new IllegalArgumentException("Trailing voice archive bytes");
        return list.isEmpty() ? EMPTY : new ReplayVoiceData(list);
    }

    public static int expectedAdpcmBytes(int sampleCount) {
        if (sampleCount <= 0) return 0;
        if (sampleCount == 1) return 4;
        return 4 + (sampleCount / 2);
    }

    /** Encodes 24 kHz mono 16-bit PCM into 4-byte header + 4-bit IMA ADPCM nibbles. */
    public static byte[] encodeAdpcm(short[] pcm) {
        if (pcm == null || pcm.length == 0) return new byte[0];
        int n = pcm.length;
        int nibbleBytes = n <= 1 ? 0 : (n / 2);
        byte[] out = new byte[4 + nibbleBytes];
        int predictor = pcm[0];
        int index = 0;
        out[0] = (byte) (predictor & 0xFF);
        out[1] = (byte) ((predictor >>> 8) & 0xFF);
        out[2] = (byte) index;
        out[3] = 0;

        int outPos = 4;
        int packedByte = 0;
        boolean highNibble = false;
        for (int i = 1; i < n; i++) {
            int step = STEP_TABLE[index];
            int diff = pcm[i] - predictor;
            int nibble = 0;
            if (diff < 0) {
                nibble = 8;
                diff = -diff;
            }
            int vpdiff = step >> 3;
            if (diff >= step) {
                nibble |= 4;
                diff -= step;
                vpdiff += step;
            }
            step >>= 1;
            if (diff >= step) {
                nibble |= 2;
                diff -= step;
                vpdiff += step;
            }
            step >>= 1;
            if (diff >= step) {
                nibble |= 1;
                vpdiff += step;
            }
            if ((nibble & 8) != 0) predictor -= vpdiff;
            else predictor += vpdiff;
            predictor = Math.clamp(predictor, Short.MIN_VALUE, Short.MAX_VALUE);
            index = Math.clamp(index + INDEX_TABLE[nibble], 0, STEP_TABLE.length - 1);

            if (!highNibble) {
                packedByte = nibble & 0x0F;
                highNibble = true;
            } else {
                out[outPos++] = (byte) (packedByte | ((nibble & 0x0F) << 4));
                highNibble = false;
            }
        }
        if (highNibble && outPos < out.length) {
            out[outPos] = (byte) packedByte;
        }
        return out;
    }

    /** Decodes 4-byte header + IMA ADPCM nibbles into {@code sampleCount} 24 kHz mono samples. */
    public static short[] decodeAdpcm(byte[] adpcm, int sampleCount) {
        if (sampleCount <= 0 || adpcm == null || adpcm.length < 4) return new short[0];
        short[] out = new short[sampleCount];
        int predictor = (short) ((adpcm[0] & 0xFF) | ((adpcm[1] & 0xFF) << 8));
        int index = Math.clamp(adpcm[2] & 0xFF, 0, STEP_TABLE.length - 1);
        out[0] = (short) predictor;

        int byteIndex = 4;
        boolean highNibble = false;
        for (int i = 1; i < sampleCount; i++) {
            if (byteIndex >= adpcm.length) break;
            int nibble = highNibble
                    ? ((adpcm[byteIndex++] >>> 4) & 0x0F)
                    : (adpcm[byteIndex] & 0x0F);
            highNibble = !highNibble;

            int step = STEP_TABLE[index];
            int vpdiff = step >> 3;
            if ((nibble & 4) != 0) vpdiff += step;
            if ((nibble & 2) != 0) vpdiff += step >> 1;
            if ((nibble & 1) != 0) vpdiff += step >> 2;
            if ((nibble & 8) != 0) predictor -= vpdiff;
            else predictor += vpdiff;
            predictor = Math.clamp(predictor, Short.MIN_VALUE, Short.MAX_VALUE);
            index = Math.clamp(index + INDEX_TABLE[nibble], 0, STEP_TABLE.length - 1);
            out[i] = (short) predictor;
        }
        return out;
    }

    /** 3-tap [0.25, 0.5, 0.25] anti-aliased decimation from 48 kHz to 24 kHz. */
    public static short[] downsample48kTo24k(short[] pcm48k) {
        if (pcm48k == null || pcm48k.length == 0) return new short[0];
        int outLen = pcm48k.length / 2;
        if (outLen == 0) return Arrays.copyOf(pcm48k, 1);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            int center = i * 2;
            int prev = center > 0 ? pcm48k[center - 1] : pcm48k[center];
            int curr = pcm48k[center];
            int next = center + 1 < pcm48k.length ? pcm48k[center + 1] : curr;
            out[i] = (short) Math.clamp((prev + 2 * curr + next + 2) >> 2, Short.MIN_VALUE, Short.MAX_VALUE);
        }
        return out;
    }

    /** Linear 2x upsampling from 24 kHz to 48 kHz. */
    public static short[] upsample24kTo48k(short[] pcm24k) {
        if (pcm24k == null || pcm24k.length == 0) return new short[0];
        short[] out = new short[pcm24k.length * 2];
        for (int i = 0; i < pcm24k.length; i++) {
            short cur = pcm24k[i];
            short next = i + 1 < pcm24k.length ? pcm24k[i + 1] : cur;
            out[i * 2] = cur;
            out[i * 2 + 1] = (short) ((cur + next) >> 1);
        }
        return out;
    }
}
