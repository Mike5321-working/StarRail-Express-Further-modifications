package com.habitrain.core.scene.replay;

import java.util.Arrays;
import java.util.function.IntToDoubleFunction;

/**
 * Pure-Java WSOLA (Waveform Similarity Overlap-Add) time-scale modification engine.
 *
 * <p>Changes audio playback speed across {@code 0.25x .. 4.0x} without shifting pitch or losing
 * syllables, using 50%-overlapped Hann synthesis frames (exact COLA unity gain) and coarse-to-fine
 * normalized cross-correlation against each frame's natural continuation.</p>
 *
 * <p>Free of Minecraft client classes so both server/JVM unit tests, the live 3D replay board
 * ({@code ReplayAudioPlaybackController}), and the streaming video exporter
 * ({@link ReplayStreamingAudioMixer}) can share the exact same implementation.</p>
 */
public final class ReplayWsolaTimeStretch {
    public static final double MIN_SPEED = 0.25;
    public static final double MAX_SPEED = 4.0;

    private static final double WINDOW_SECONDS = 0.030;
    private static final double SEARCH_SECONDS = 0.012;
    private static final double UNITY_EPS = 1.0e-4;

    private ReplayWsolaTimeStretch() {}

    /**
     * Time-stretches 16-bit mono PCM by {@code speed} while preserving vocal pitch.
     *
     * @param input      16-bit signed mono PCM samples
     * @param sampleRate audio sample rate in Hz (e.g. 24000 or 48000)
     * @param speed      playback speed multiplier ({@code 2.0} halves duration; {@code 0.5} doubles duration)
     */
    public static short[] stretchMono(short[] input, int sampleRate, double speed) {
        if (input == null || input.length == 0) return new short[0];
        return stretchMonoRange(input, 0, input.length, sampleRate, speed);
    }

    /** Time-stretches a sub-range {@code [offset, offset + length)} of 16-bit mono PCM. */
    public static short[] stretchMonoRange(short[] input, int offset, int length, int sampleRate, double speed) {
        if (input == null || length <= 0 || offset >= input.length) return new short[0];
        int start = Math.max(0, offset);
        int avail = Math.min(length, input.length - start);
        if (avail <= 0) return new short[0];
        double clampedSpeed = clampSpeed(speed);
        if (Math.abs(clampedSpeed - 1.0) <= UNITY_EPS) {
            return Arrays.copyOfRange(input, start, start + avail);
        }
        float[] src = new float[avail];
        for (int i = 0; i < avail; i++) {
            src[i] = input[start + i] / 32768.0f;
        }
        float[] stretched = stretchMonoFloat(src, sampleRate, clampedSpeed);
        return floatToShort(stretched);
    }

    /**
     * Time-stretches little-endian 16-bit signed mono PCM byte buffer (convenient for OpenAL /
     * {@code VoiceClipSoundInstance} playback on the replay table).
     */
    public static byte[] stretchPcm16LeMono(byte[] pcm16Le, int sampleRate, double speed) {
        if (pcm16Le == null || pcm16Le.length < 2) return new byte[0];
        int sampleCount = pcm16Le.length / 2;
        double clampedSpeed = clampSpeed(speed);
        if (Math.abs(clampedSpeed - 1.0) <= UNITY_EPS) {
            return Arrays.copyOf(pcm16Le, sampleCount * 2);
        }
        float[] src = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int lo = pcm16Le[i * 2] & 0xFF;
            int hi = pcm16Le[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            src[i] = s / 32768.0f;
        }
        float[] stretched = stretchMonoFloat(src, sampleRate, clampedSpeed);
        byte[] out = new byte[stretched.length * 2];
        for (int i = 0; i < stretched.length; i++) {
            short s = floatSampleToShort(stretched[i]);
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
        }
        return out;
    }

    /** Time-stretches normalized floating-point mono samples ({@code -1..1}) by constant {@code speed}. */
    public static float[] stretchMonoFloat(float[] input, int sampleRate, double speed) {
        if (input == null || input.length == 0) return new float[0];
        double clampedSpeed = clampSpeed(speed);
        if (Math.abs(clampedSpeed - 1.0) <= UNITY_EPS) {
            return input.clone();
        }
        int outLen = Math.max(1, (int) Math.round(input.length / clampedSpeed));
        return stretchVariableRate(input, sampleRate, outLen, outIndex -> outIndex * clampedSpeed);
    }

    /**
     * Variable-rate WSOLA time-stretch driven by a monotonic mapping from output sample index to
     * source sample index. Ideal for slow-motion ramps, bullet-time dips, and speed-ramped video exports.
     */
    public static short[] stretchVariableRate(short[] input, int sampleRate, int outputLength,
                                              IntToDoubleFunction sourceIndexAtOutputSample) {
        if (input == null || input.length == 0 || outputLength <= 0) return new short[0];
        float[] src = new float[input.length];
        for (int i = 0; i < input.length; i++) src[i] = input[i] / 32768.0f;
        return floatToShort(stretchVariableRate(src, sampleRate, outputLength, sourceIndexAtOutputSample));
    }

    /**
     * Variable-rate WSOLA time-stretch on floating-point mono PCM.
     *
     * @param input                     source mono PCM
     * @param sampleRate                sample rate in Hz
     * @param outputLength              target number of output mono samples
     * @param sourceIndexAtOutputSample maps an output sample index to nominal source sample index
     */
    public static float[] stretchVariableRate(float[] input, int sampleRate, int outputLength,
                                              IntToDoubleFunction sourceIndexAtOutputSample) {
        if (input == null || input.length == 0 || outputLength <= 0) return new float[0];
        int sr = Math.max(8_000, sampleRate);
        int rawWindow = ((int) Math.round(sr * WINDOW_SECONDS)) & ~1;
        int windowLen = Math.max(64, rawWindow);

        // Very short inputs cannot hold a full 30 ms analysis window; adapt gracefully.
        if (input.length < windowLen) {
            int shrunk = (input.length / 2) & ~1;
            if (shrunk < 32) {
                return shortFallback(input, outputLength, sourceIndexAtOutputSample);
            }
            windowLen = shrunk;
        }
        int hop = windowLen / 2;
        if (outputLength <= windowLen) {
            return shortWindowCopy(input, outputLength, sourceIndexAtOutputSample);
        }

        int searchRadius = Math.max(16, Math.min(windowLen / 2, (int) Math.round(sr * SEARCH_SECONDS)));
        int maxStart = input.length - windowLen;
        float[] hann = buildHannWindow(windowLen);
        float[] output = new float[outputLength];

        int firstNominal = clampStart(Math.round(sourceIndexAtOutputSample.applyAsDouble(0)), maxStart);
        // Frame 0: flat unity on [0, hop), Hann falling half on [hop, windowLen).
        int firstCopy = Math.min(hop, outputLength);
        System.arraycopy(input, firstNominal, output, 0, firstCopy);
        int firstTailEnd = Math.min(windowLen, outputLength);
        for (int i = hop; i < firstTailEnd; i++) {
            output[i] += input[firstNominal + i] * hann[i];
        }

        int prevChosen = firstNominal;
        int coarseStep = sr >= 40_000 ? 4 : 2;
        int coarseDecim = sr >= 40_000 ? 4 : 2;
        int fineDecim = sr >= 40_000 ? 2 : 1;

        for (int outPos = hop; outPos < outputLength; outPos += hop) {
            boolean isLast = outPos + hop >= outputLength;
            int nominal = clampStart(Math.round(sourceIndexAtOutputSample.applyAsDouble(outPos)), maxStart);
            int natural = prevChosen + hop;
            int chosen;
            if (natural >= 0 && natural <= maxStart) {
                chosen = findBestMatch(input, natural, nominal, searchRadius, windowLen, maxStart,
                        coarseStep, coarseDecim, fineDecim);
            } else {
                chosen = nominal;
            }

            int risingEnd = Math.min(hop, outputLength - outPos);
            for (int i = 0; i < risingEnd; i++) {
                output[outPos + i] += input[chosen + i] * hann[i];
            }
            int remaining = outputLength - outPos;
            if (remaining > hop) {
                int tailEnd = Math.min(windowLen, remaining);
                if (isLast) {
                    for (int i = hop; i < tailEnd; i++) {
                        output[outPos + i] = input[chosen + i];
                    }
                } else {
                    for (int i = hop; i < tailEnd; i++) {
                        output[outPos + i] += input[chosen + i] * hann[i];
                    }
                }
            }
            prevChosen = chosen;
        }
        return output;
    }

    public static double clampSpeed(double speed) {
        if (!Double.isFinite(speed) || speed <= 0.0) return 1.0;
        return Math.clamp(speed, MIN_SPEED, MAX_SPEED);
    }

    private static int findBestMatch(float[] src, int natural, int nominal, int radius, int windowLen, int maxStart,
                                     int coarseStep, int coarseDecim, int fineDecim) {
        int lo = Math.max(0, nominal - radius);
        int hi = Math.min(maxStart, nominal + radius);
        if (lo >= hi) return lo;

        // If the natural continuation is already inside the search range and equals nominal, fast path.
        double refEnergyCoarse = energy(src, natural, windowLen, coarseDecim);
        if (refEnergyCoarse < 1.0e-7) {
            return Math.clamp(nominal, lo, hi);
        }

        int bestCand = Math.clamp(nominal, lo, hi);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int cand = lo; cand <= hi; cand += coarseStep) {
            double score = normalizedCrossCorrelation(src, natural, cand, windowLen, coarseDecim, refEnergyCoarse);
            if (score > bestScore) {
                bestScore = score;
                bestCand = cand;
            }
        }
        if ((hi - lo) % coarseStep != 0) {
            double score = normalizedCrossCorrelation(src, natural, hi, windowLen, coarseDecim, refEnergyCoarse);
            if (score > bestScore) {
                bestCand = hi;
            }
        }

        int fineLo = Math.max(lo, bestCand - coarseStep);
        int fineHi = Math.min(hi, bestCand + coarseStep);
        double refEnergyFine = fineDecim == coarseDecim ? refEnergyCoarse : energy(src, natural, windowLen, fineDecim);
        if (refEnergyFine < 1.0e-7) return bestCand;

        int exactBest = bestCand;
        double exactScore = Double.NEGATIVE_INFINITY;
        for (int cand = fineLo; cand <= fineHi; cand++) {
            double score = normalizedCrossCorrelation(src, natural, cand, windowLen, fineDecim, refEnergyFine);
            if (score > exactScore) {
                exactScore = score;
                exactBest = cand;
            }
        }
        return exactBest;
    }

    private static double energy(float[] src, int start, int len, int decim) {
        double sum = 0.0;
        for (int i = 0; i < len; i += decim) {
            double v = src[start + i];
            sum += v * v;
        }
        return sum;
    }

    private static double normalizedCrossCorrelation(float[] src, int refStart, int candStart,
                                                     int len, int decim, double refEnergy) {
        double dot = 0.0;
        double candEnergy = 0.0;
        for (int i = 0; i < len; i += decim) {
            double r = src[refStart + i];
            double c = src[candStart + i];
            dot += r * c;
            candEnergy += c * c;
        }
        if (candEnergy < 1.0e-9) return -2.0;
        return dot / Math.sqrt(refEnergy * candEnergy);
    }

    private static float[] buildHannWindow(int n) {
        float[] w = new float[n];
        double scale = 2.0 * Math.PI / n;
        for (int i = 0; i < n; i++) {
            w[i] = (float) (0.5 * (1.0 - Math.cos(scale * i)));
        }
        return w;
    }

    private static int clampStart(long pos, int maxStart) {
        if (pos <= 0) return 0;
        if (pos >= maxStart) return maxStart;
        return (int) pos;
    }

    private static float[] shortWindowCopy(float[] input, int outputLength, IntToDoubleFunction map) {
        int start = clampStart(Math.round(map.applyAsDouble(0)), Math.max(0, input.length - outputLength));
        float[] out = new float[outputLength];
        int avail = Math.min(outputLength, input.length - start);
        if (avail > 0) System.arraycopy(input, start, out, 0, avail);
        return out;
    }

    private static float[] shortFallback(float[] input, int outputLength, IntToDoubleFunction map) {
        float[] out = new float[outputLength];
        int last = input.length - 1;
        for (int i = 0; i < outputLength; i++) {
            double pos = Math.clamp(map.applyAsDouble(i), 0.0, last);
            int i0 = (int) pos;
            int i1 = Math.min(last, i0 + 1);
            float frac = (float) (pos - i0);
            out[i] = input[i0] + (input[i1] - input[i0]) * frac;
        }
        return out;
    }

    private static short[] floatToShort(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = floatSampleToShort(samples[i]);
        }
        return out;
    }

    private static short floatSampleToShort(float sample) {
        float clamped = Math.clamp(sample, -1.0f, 1.0f);
        return (short) Math.clamp(Math.round(clamped * 32767.0f), Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
