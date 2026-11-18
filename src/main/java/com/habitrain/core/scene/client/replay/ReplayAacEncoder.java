package com.habitrain.core.scene.client.replay;

import net.sourceforge.jaad.aac.huffman.Huffman;
import org.jcodec.codecs.aac.ADTSParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Pure-Java ISO/IEC 14496-3 MPEG-4 AAC-LC stereo 48 kHz encoder producing ADTS frames compatible with
 * {@link ADTSParser} and JCodec's {@code Codec.AAC} MP4 muxer ({@code mp4a} + {@code esds}).
 */
final class ReplayAacEncoder {
    static final int SAMPLE_RATE = 48_000;
    static final int FRAME_LEN = 1024;
    private static final int WINDOW_LEN = 2048;
    private static final int HALF_WINDOW = 1024;
    private static final int FFT_LEN = 512;
    private static final int MAX_QUANT = 8191;

    /** ISO 14496-3 {@code SWB_OFFSET_1024_48}: 49 scale-factor bands (50 boundary offsets). */
    private static final int[] SWB_OFFSETS = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80,
            88, 96, 108, 120, 132, 144, 160, 176, 196, 216, 240, 264, 292, 320, 352, 384,
            416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768, 800, 832, 864, 896,
            928, 1024
    };
    private static final int MAX_SFB = SWB_OFFSETS.length - 1; // 49

    private static final double[] SINE_WINDOW = new double[WINDOW_LEN];
    private static final int[] SF_CODE = new int[Huffman.HCB_SF.length];
    private static final int[] SF_LEN = new int[Huffman.HCB_SF.length];
    private static final int[][] HCB11_CODE = new int[17][17];
    private static final int[][] HCB11_LEN = new int[17][17];
    private static final double[] GAIN_POW2 = new double[256];
    private static final double MAX_QUANT_SPEC = Math.pow(MAX_QUANT, 4.0 / 3.0);

    static {
        for (int n = 0; n < WINDOW_LEN; n++) {
            SINE_WINDOW[n] = Math.sin(Math.PI / WINDOW_LEN * (n + 0.5));
        }
        for (int[] row : Huffman.HCB_SF) {
            int val = row[2];
            SF_LEN[val] = row[0];
            SF_CODE[val] = row[1];
        }
        for (int[] row : Huffman.HCB11) {
            int u0 = row[2];
            int u1 = row[3];
            HCB11_LEN[u0][u1] = row[0];
            HCB11_CODE[u0][u1] = row[1];
        }
        for (int sf = 0; sf < 256; sf++) {
            GAIN_POW2[sf] = Math.pow(2.0, 0.25 * (sf - 100));
        }
    }

    @FunctionalInterface
    interface PacketConsumer {
        void accept(ByteBuffer adtsFrame) throws IOException;
    }

    private final PacketConsumer consumer;
    private final float[] prevL = new float[FRAME_LEN];
    private final float[] prevR = new float[FRAME_LEN];
    private final float[] pendingL = new float[FRAME_LEN];
    private final float[] pendingR = new float[FRAME_LEN];
    private int pendingCount;
    private boolean havePrevBlock;
    private int framesEmitted;

    // Reusable per-frame DSP scratch buffers (encoder runs on single muxer thread).
    private final double[] mdctTime = new double[WINDOW_LEN];
    private final double[] foldW = new double[HALF_WINDOW];
    private final double[] fftRe = new double[FFT_LEN];
    private final double[] fftIm = new double[FFT_LEN];
    private final double[] specL = new double[FRAME_LEN];
    private final double[] specR = new double[FRAME_LEN];
    private final BitBuffer bitBuffer = new BitBuffer(8192);
    private final byte[] adtsScratch = new byte[7];

    ReplayAacEncoder(PacketConsumer consumer) {
        this.consumer = consumer;
    }

    void push(short[] interleavedStereo48k, int stereoFrameOffset, int stereoFrames) throws IOException {
        if (interleavedStereo48k == null || stereoFrames <= 0) return;
        int avail = Math.min(stereoFrames, Math.max(0, interleavedStereo48k.length / 2 - stereoFrameOffset));
        int pos = 0;
        while (pos < avail) {
            int take = Math.min(FRAME_LEN - pendingCount, avail - pos);
            for (int i = 0; i < take; i++) {
                int idx = (stereoFrameOffset + pos + i) * 2;
                pendingL[pendingCount + i] = interleavedStereo48k[idx];
                pendingR[pendingCount + i] = interleavedStereo48k[idx + 1];
            }
            pendingCount += take;
            pos += take;
            if (pendingCount == FRAME_LEN) {
                if (!havePrevBlock) {
                    System.arraycopy(pendingL, 0, prevL, 0, FRAME_LEN);
                    System.arraycopy(pendingR, 0, prevR, 0, FRAME_LEN);
                    havePrevBlock = true;
                } else {
                    encodeBlockPair(prevL, prevR, pendingL, pendingR);
                    System.arraycopy(pendingL, 0, prevL, 0, FRAME_LEN);
                    System.arraycopy(pendingR, 0, prevR, 0, FRAME_LEN);
                }
                pendingCount = 0;
            }
        }
    }

    void finish() throws IOException {
        if (!havePrevBlock && pendingCount == 0 && framesEmitted == 0) {
            Arrays.fill(pendingL, 0f);
            Arrays.fill(pendingR, 0f);
            encodeBlockPair(prevL, prevR, pendingL, pendingR);
            return;
        }
        if (pendingCount > 0) {
            Arrays.fill(pendingL, pendingCount, FRAME_LEN, 0f);
            Arrays.fill(pendingR, pendingCount, FRAME_LEN, 0f);
            if (!havePrevBlock) {
                System.arraycopy(pendingL, 0, prevL, 0, FRAME_LEN);
                System.arraycopy(pendingR, 0, prevR, 0, FRAME_LEN);
                havePrevBlock = true;
            } else {
                encodeBlockPair(prevL, prevR, pendingL, pendingR);
                System.arraycopy(pendingL, 0, prevL, 0, FRAME_LEN);
                System.arraycopy(pendingR, 0, prevR, 0, FRAME_LEN);
            }
            pendingCount = 0;
        }
        if (havePrevBlock) {
            Arrays.fill(pendingL, 0f);
            Arrays.fill(pendingR, 0f);
            encodeBlockPair(prevL, prevR, pendingL, pendingR);
            havePrevBlock = false;
        }
    }

    private void encodeBlockPair(float[] firstL, float[] firstR, float[] secondL, float[] secondR) throws IOException {
        mdctChannel(firstL, secondL, specL);
        mdctChannel(firstR, secondR, specR);

        ChannelQuant qL = quantizeChannel(specL);
        ChannelQuant qR = quantizeChannel(specR);

        bitBuffer.reset();
        // ID_CPE (0x1, 3 bits) + element_instance_tag (0, 4 bits) + common_window (1, 1 bit)
        bitBuffer.writeBits(1, 3);
        bitBuffer.writeBits(0, 4);
        bitBuffer.writeBits(1, 1);

        int maxSfb = Math.max(qL.activeSfbCount, qR.activeSfbCount);
        // ICSInfo: ics_reserved_bit (0,1b), window_sequence (0,2b), window_shape (0,1b), max_sfb (6b), predictor_data_present (0,1b)
        bitBuffer.writeBits(0, 1);
        bitBuffer.writeBits(0, 2);
        bitBuffer.writeBits(0, 1);
        bitBuffer.writeBits(maxSfb, 6);
        bitBuffer.writeBits(0, 1);

        // ms_mask_present = 0 (no M/S joint stereo; preserves L/R panning verbatim)
        bitBuffer.writeBits(0, 2);

        writeIcsChannel(qL, maxSfb);
        writeIcsChannel(qR, maxSfb);

        // ID_END (0x7, 3 bits) + byte alignment
        bitBuffer.writeBits(7, 3);
        bitBuffer.alignToByte();

        byte[] payload = bitBuffer.toByteArray();
        int frameBytes = 7 + payload.length;
        ByteBuffer adts = ByteBuffer.wrap(adtsScratch);
        ADTSParser.Header header = new ADTSParser.Header(2, 2, 1, 1, 3, frameBytes);
        ADTSParser.write(header, adts);

        ByteBuffer fullFrame = ByteBuffer.allocate(frameBytes);
        fullFrame.put(adtsScratch);
        fullFrame.put(payload);
        fullFrame.flip();

        consumer.accept(fullFrame);
        framesEmitted++;
    }

    private void mdctChannel(float[] first, float[] second, double[] outSpec) {
        for (int n = 0; n < FRAME_LEN; n++) {
            mdctTime[n] = first[n] * SINE_WINDOW[n];
            mdctTime[FRAME_LEN + n] = second[n] * SINE_WINDOW[FRAME_LEN + n];
        }
        Arrays.fill(foldW, 0.0);
        for (int n = 0; n < WINDOW_LEN; n++) {
            int p = n + 512;
            if (p < HALF_WINDOW) {
                foldW[p] += mdctTime[n];
            } else if (p < WINDOW_LEN) {
                foldW[WINDOW_LEN - 1 - p] -= mdctTime[n];
            } else {
                foldW[p - WINDOW_LEN] -= mdctTime[n];
            }
        }
        for (int m = 0; m < FFT_LEN; m++) {
            double a = foldW[2 * m];
            double b = foldW[HALF_WINDOW - 1 - 2 * m];
            double ang = Math.PI * (m + 0.25) / HALF_WINDOW;
            double c = Math.cos(ang);
            double s = Math.sin(ang);
            fftRe[m] = a * c + b * s;
            fftIm[m] = -a * s + b * c;
        }
        fft512(fftRe, fftIm);
        for (int q = 0; q < FFT_LEN; q++) {
            double ang = Math.PI * q / HALF_WINDOW;
            double c = Math.cos(ang);
            double s = Math.sin(ang);
            double pr = fftRe[q] * c + fftIm[q] * s;
            double pi = fftIm[q] * c - fftRe[q] * s;
            outSpec[2 * q] = 2.0 * pr;
            outSpec[HALF_WINDOW - 1 - 2 * q] = -2.0 * pi;
        }
    }

    private static void fft512(double[] re, double[] im) {
        for (int i = 1, j = 0; i < FFT_LEN; i++) {
            int bit = FFT_LEN >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= FFT_LEN; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < FFT_LEN; i += len) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int j = 0; j < half; j++) {
                    int u = i + j;
                    int v = u + half;
                    double tRe = re[v] * curRe - im[v] * curIm;
                    double tIm = re[v] * curIm + im[v] * curRe;
                    re[v] = re[u] - tRe;
                    im[v] = im[u] - tIm;
                    re[u] += tRe;
                    im[u] += tIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }

    private static final class ChannelQuant {
        int globalGain;
        int activeSfbCount;
        final int[] codebook = new int[MAX_SFB];
        final int[] sf = new int[MAX_SFB];
        final int[] q = new int[FRAME_LEN];
    }

    private ChannelQuant quantizeChannel(double[] spec) {
        ChannelQuant out = new ChannelQuant();
        double maxPeak = 0.0;
        double[] bandPeaks = new double[MAX_SFB];
        for (int b = 0; b < MAX_SFB; b++) {
            double peak = 0.0;
            int lo = SWB_OFFSETS[b];
            int hi = SWB_OFFSETS[b + 1];
            for (int k = lo; k < hi; k++) {
                double a = Math.abs(spec[k]);
                if (a > peak) peak = a;
            }
            bandPeaks[b] = peak;
            if (peak > maxPeak) maxPeak = peak;
        }
        if (maxPeak < 0.75) {
            return out; // pure silence frame -> 0 active bands
        }

        // First compute ideal scale factor per non-silent band:
        // Target peak quantized coefficient ~ 160 -> excellent SNR without escaping very often.
        boolean[] wantActive = new boolean[MAX_SFB];
        int[] targetSf = new int[MAX_SFB];
        for (int b = 0; b < MAX_SFB; b++) {
            if (bandPeaks[b] < 0.75) continue;
            wantActive[b] = true;
            double idealSf = 100.0 + 4.0 * (Math.log(Math.max(1e-6, bandPeaks[b] / 160.0)) / Math.log(2.0));
            int minSfForOverflow = (int) Math.ceil(100.0 + 4.0 * (Math.log(Math.max(1e-9, bandPeaks[b] / MAX_QUANT_SPEC)) / Math.log(2.0)));
            targetSf[b] = Math.clamp((int) Math.round(Math.max(idealSf, minSfForOverflow)), 0, 255);
        }

        // Smooth differential scale factors so every step |sf[b] - prevSf| <= 60 (required by HCB_SF).
        int lastActive = -1;
        for (int b = 0; b < MAX_SFB; b++) {
            if (!wantActive[b]) continue;
            if (lastActive < 0) {
                out.globalGain = targetSf[b];
                out.sf[b] = targetSf[b];
            } else {
                int minSfForOverflow = (int) Math.ceil(100.0 + 4.0 * (Math.log(Math.max(1e-9, bandPeaks[b] / MAX_QUANT_SPEC)) / Math.log(2.0)));
                int raw = targetSf[b];
                int clampedDelta = Math.clamp(raw - lastActive, -60, 60);
                int candidate = Math.clamp(lastActive + clampedDelta, minSfForOverflow, 255);
                if (Math.abs(candidate - lastActive) > 60) {
                    candidate = Math.clamp(lastActive + (candidate > lastActive ? 60 : -60), 0, 255);
                }
                out.sf[b] = candidate;
            }
            lastActive = out.sf[b];
        }

        // Quantize each active band and drop any band whose coefficients all round to zero.
        int highestNonZeroBand = 0;
        for (int b = 0; b < MAX_SFB; b++) {
            if (!wantActive[b]) continue;
            double invGain = 1.0 / GAIN_POW2[out.sf[b]];
            int lo = SWB_OFFSETS[b];
            int hi = SWB_OFFSETS[b + 1];
            boolean anyNonZero = false;
            for (int k = lo; k < hi; k++) {
                double val = spec[k];
                double mag = Math.abs(val) * invGain;
                int qMag = (int) Math.min(MAX_QUANT, Math.floor(Math.pow(mag, 0.75) + 0.4054));
                if (qMag != 0) anyNonZero = true;
                out.q[k] = val < 0 ? -qMag : qMag;
            }
            if (anyNonZero) {
                out.codebook[b] = 11; // HCB11 (supports signs + escapes up to 8191)
                highestNonZeroBand = b + 1;
            }
        }
        out.activeSfbCount = highestNonZeroBand;

        // Re-verify globalGain & delta constraints after dropping zeroed bands.
        int runningSf = -1;
        for (int b = 0; b < out.activeSfbCount; b++) {
            if (out.codebook[b] == 0) continue;
            if (runningSf < 0) {
                out.globalGain = out.sf[b];
            } else if (Math.abs(out.sf[b] - runningSf) > 60) {
                int clamped = Math.clamp(runningSf + (out.sf[b] > runningSf ? 60 : -60), 0, 255);
                out.sf[b] = clamped;
                double invGain = 1.0 / GAIN_POW2[clamped];
                for (int k = SWB_OFFSETS[b]; k < SWB_OFFSETS[b + 1]; k++) {
                    double val = spec[k];
                    int qMag = (int) Math.min(MAX_QUANT, Math.floor(Math.pow(Math.abs(val) * invGain, 0.75) + 0.4054));
                    out.q[k] = val < 0 ? -qMag : qMag;
                }
            }
            runningSf = out.sf[b];
        }
        return out;
    }

    private void writeIcsChannel(ChannelQuant ch, int maxSfb) {
        bitBuffer.writeBits(ch.globalGain, 8);
        if (maxSfb == 0) {
            // pulse_data_present, tns_data_present, gain_control_data_present = 0
            bitBuffer.writeBits(0, 3);
            return;
        }

        // Section data: run-length encode codebook[0 .. maxSfb - 1]
        int b = 0;
        while (b < maxSfb) {
            int cb = ch.codebook[b];
            int len = 1;
            while (b + len < maxSfb && ch.codebook[b + len] == cb) len++;
            bitBuffer.writeBits(cb, 4);
            int rem = len;
            while (rem >= 31) {
                bitBuffer.writeBits(31, 5);
                rem -= 31;
            }
            bitBuffer.writeBits(rem, 5);
            b += len;
        }

        // Scale factor data: differential Huffman HCB_SF relative to globalGain
        int prevSf = ch.globalGain;
        for (int sfb = 0; sfb < maxSfb; sfb++) {
            if (ch.codebook[sfb] == 0) continue;
            int delta = Math.clamp(ch.sf[sfb] - prevSf, -60, 60);
            int idx = delta + 60;
            bitBuffer.writeBits(SF_CODE[idx], SF_LEN[idx]);
            prevSf = ch.sf[sfb];
        }

        // pulse_data_present (0), tns_data_present (0), gain_control_data_present (0)
        bitBuffer.writeBits(0, 3);

        // Spectral data: HCB11 pairs
        for (int sfb = 0; sfb < maxSfb; sfb++) {
            if (ch.codebook[sfb] == 0) continue;
            int lo = SWB_OFFSETS[sfb];
            int hi = SWB_OFFSETS[sfb + 1];
            for (int k = lo; k < hi; k += 2) {
                int q0 = ch.q[k];
                int q1 = ch.q[k + 1];
                int a0 = Math.abs(q0);
                int a1 = Math.abs(q1);
                int u0 = Math.min(16, a0);
                int u1 = Math.min(16, a1);
                bitBuffer.writeBits(HCB11_CODE[u0][u1], HCB11_LEN[u0][u1]);
                if (u0 != 0) bitBuffer.writeBits(q0 < 0 ? 1 : 0, 1);
                if (u1 != 0) bitBuffer.writeBits(q1 < 0 ? 1 : 0, 1);
                if (u0 == 16) writeEscape(a0);
                if (u1 == 16) writeEscape(a1);
            }
        }
    }

    private void writeEscape(int mag) {
        int bitLen = 32 - Integer.numberOfLeadingZeros(mag); // >= 5 for mag >= 16
        int shift = bitLen - 1;
        for (int i = 4; i < shift; i++) {
            bitBuffer.writeBits(1, 1);
        }
        bitBuffer.writeBits(0, 1);
        bitBuffer.writeBits(mag & ((1 << shift) - 1), shift);
    }

    private static final class BitBuffer {
        private final ByteArrayOutputStream out;
        private int current;
        private int bits;

        BitBuffer(int initialCapacity) {
            this.out = new ByteArrayOutputStream(initialCapacity);
        }

        void reset() {
            out.reset();
            current = 0;
            bits = 0;
        }

        void writeBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                current = (current << 1) | ((value >>> i) & 1);
                bits++;
                if (bits == 8) {
                    out.write(current);
                    current = 0;
                    bits = 0;
                }
            }
        }

        void alignToByte() {
            if (bits > 0) {
                out.write(current << (8 - bits));
                current = 0;
                bits = 0;
            }
        }

        byte[] toByteArray() {
            alignToByte();
            return out.toByteArray();
        }
    }
}
