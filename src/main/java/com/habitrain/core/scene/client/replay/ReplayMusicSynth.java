package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.replay.ReplayMusicPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Small deterministic software synthesizer for {@link ReplayMusicPlan} scores: analog-style drums, bass, pads,
 * Karplus-Strong plucks, FM bells and noise risers, a reverb send, an automated low-pass filter and a peak limiter.
 * Everything is computed from the score and fixed per-note seeds, so the same replay always exports the same music.
 */
final class ReplayMusicSynth {
    static final int RATE = ReplayVideoAudioRenderer.SAMPLE_RATE;
    private static final double TWO_PI = Math.PI * 2;
    private static final double MAX_NOTE_SECONDS = 12;
    private static final float LIMIT = 0.9f;
    private static final float LIMIT_RELEASE = (float) Math.exp(-1.0 / (0.08 * RATE));

    private ReplayMusicSynth() {}

    // ------------------------------------------------------------------ bus

    /** Streams a score into consecutive mix windows. Not thread-safe; one instance per render. */
    static final class Bus {
        private final List<ReplayMusicPlan.Note> notes;
        private final float masterGain;
        private final float[] gainCurve, cutoffCurve;
        private final List<Active> active = new ArrayList<>();
        private final Reverb reverb = new Reverb();
        private int next;
        private double l1, l2, r1, r2;
        private float limiterEnv;
        private float[] busL = new float[0], busR = new float[0], sendL = new float[0], sendR = new float[0];

        private record Active(float[] samples, int start, float gainL, float gainR, float send) {}

        Bus(ReplayMusicPlan.Score score, float masterGain, int totalStereoFrames) {
            this.notes = score.notes();
            this.masterGain = masterGain;
            int points = (int) Math.ceil(totalStereoFrames * 1000.0 / RATE) + 2;
            gainCurve = new float[points];
            cutoffCurve = new float[points];
            float[] duck = new float[points];
            java.util.Arrays.fill(duck, 1f);
            for (var d : score.ducks()) {
                int from = Math.max(0, (int) Math.floor((d.start() - d.attack()) * 1000));
                int to = Math.min(points - 1, (int) Math.ceil((d.end() + d.release()) * 1000));
                for (int i = from; i <= to; i++) duck[i] = Math.min(duck[i], d.factor(i / 1000.0));
            }
            float level = score.levelAt(0);
            float smooth = (float) (1 - Math.exp(-1.0 / 20.0)); // ~20 ms at the 1 kHz control rate
            int filterIndex = 0;
            float cutoff = ReplayMusicPlan.OPEN_FILTER;
            var ramps = score.filter();
            for (int i = 0; i < points; i++) {
                double t = i / 1000.0;
                level += (score.levelAt(t) - level) * smooth;
                gainCurve[i] = level * duck[i];
                while (filterIndex < ramps.size() && ramps.get(filterIndex).start() <= t) filterIndex++;
                cutoff = filterIndex == 0 ? ReplayMusicPlan.OPEN_FILTER : ramps.get(filterIndex - 1).at(t);
                cutoffCurve[i] = cutoff;
            }
        }

        /** Adds this window of music to {@code mixL/mixR}, whose index 0 is stereo frame {@code offset}. */
        void mix(float[] mixL, float[] mixR, int offset, int count) {
            if (count <= 0) return;
            if (busL.length < count) {
                busL = new float[count];
                busR = new float[count];
                sendL = new float[count];
                sendR = new float[count];
            } else {
                java.util.Arrays.fill(busL, 0, count, 0f);
                java.util.Arrays.fill(busR, 0, count, 0f);
                java.util.Arrays.fill(sendL, 0, count, 0f);
                java.util.Arrays.fill(sendR, 0, count, 0f);
            }
            int end = offset + count;
            while (next < notes.size()) {
                var note = notes.get(next);
                int start = (int) Math.round(note.time() * RATE);
                if (start >= end) break;
                next++;
                float[] samples = render(note);
                if (start + samples.length <= offset) continue;
                double angle = (Math.clamp(note.pan(), -1f, 1f) + 1.0) * (Math.PI * 0.25);
                active.add(new Active(samples, start, (float) (Math.cos(angle) * Math.sqrt(2)),
                        (float) (Math.sin(angle) * Math.sqrt(2)), note.voice().send));
            }
            for (int a = active.size() - 1; a >= 0; a--) {
                Active voice = active.get(a);
                int from = Math.max(offset, voice.start()), to = Math.min(end, voice.start() + voice.samples().length);
                float[] s = voice.samples();
                for (int i = from; i < to; i++) {
                    float v = s[i - voice.start()];
                    int k = i - offset;
                    float l = v * voice.gainL(), r = v * voice.gainR();
                    busL[k] += l;
                    busR[k] += r;
                    if (voice.send() > 0) {
                        sendL[k] += l * voice.send();
                        sendR[k] += r * voice.send();
                    }
                }
                if (voice.start() + s.length <= end) active.remove(a);
            }
            reverb.process(sendL, sendR, busL, busR, count);

            double a = 1;
            for (int k = 0; k < count; k++) {
                int frame = offset + k;
                double ms = frame * 1000.0 / RATE;
                int i0 = Math.min(gainCurve.length - 2, (int) ms);
                float frac = (float) (ms - i0);
                if ((k & 31) == 0) {
                    float cutoff = cutoffCurve[i0] + (cutoffCurve[i0 + 1] - cutoffCurve[i0]) * frac;
                    a = 1 - Math.exp(-TWO_PI * Math.clamp(cutoff, 40f, 20_000f) / RATE);
                    if ((k & 16383) == 0 && Thread.currentThread().isInterrupted()) throw new CancellationException();
                }
                l1 += a * (busL[k] - l1);
                l2 += a * (l1 - l2);
                r1 += a * (busR[k] - r1);
                r2 += a * (r1 - r2);
                float peak = (float) Math.max(Math.abs(l2), Math.abs(r2));
                limiterEnv = peak > limiterEnv ? peak : limiterEnv * LIMIT_RELEASE;
                float limit = limiterEnv > LIMIT ? LIMIT / limiterEnv : 1f;
                float gain = (gainCurve[i0] + (gainCurve[i0 + 1] - gainCurve[i0]) * frac) * limit * masterGain;
                mixL[k] += (float) l2 * gain;
                mixR[k] += (float) r2 * gain;
            }
        }
    }

    /** Freeverb-style stereo room: parallel damped combs into series allpasses. */
    private static final class Reverb {
        private static final int[] COMBS = {1557, 1617, 1491, 1422, 1277, 1356};
        private static final int[] ALLPASSES = {556, 441};
        private static final int SPREAD = 23;
        private static final float FEEDBACK = 0.80f, DAMP = 0.30f, INPUT = 0.02f, WET = 1.0f;
        private final float[][] combL = new float[COMBS.length][], combR = new float[COMBS.length][];
        private final float[] storeL = new float[COMBS.length], storeR = new float[COMBS.length];
        private final int[] combIdx = new int[COMBS.length];
        private final float[][] apL = new float[ALLPASSES.length][], apR = new float[ALLPASSES.length][];
        private final int[] apIdx = new int[ALLPASSES.length];

        Reverb() {
            double scale = RATE / 44_100.0;
            for (int i = 0; i < COMBS.length; i++) {
                combL[i] = new float[(int) (COMBS[i] * scale)];
                combR[i] = new float[(int) ((COMBS[i] + SPREAD) * scale)];
            }
            for (int i = 0; i < ALLPASSES.length; i++) {
                apL[i] = new float[(int) (ALLPASSES[i] * scale)];
                apR[i] = new float[(int) ((ALLPASSES[i] + SPREAD) * scale)];
            }
        }

        void process(float[] inL, float[] inR, float[] outL, float[] outR, int n) {
            for (int k = 0; k < n; k++) {
                float in = (inL[k] + inR[k]) * INPUT;
                float l = 0, r = 0;
                for (int c = 0; c < COMBS.length; c++) {
                    int idx = combIdx[c];
                    float[] bl = combL[c], br = combR[c];
                    float yl = bl[idx % bl.length], yr = br[idx % br.length];
                    storeL[c] = yl * (1 - DAMP) + storeL[c] * DAMP;
                    storeR[c] = yr * (1 - DAMP) + storeR[c] * DAMP;
                    bl[idx % bl.length] = in + storeL[c] * FEEDBACK;
                    br[idx % br.length] = in + storeR[c] * FEEDBACK;
                    combIdx[c] = (idx + 1) % (bl.length * br.length);
                    l += yl;
                    r += yr;
                }
                for (int p = 0; p < ALLPASSES.length; p++) {
                    int idx = apIdx[p];
                    float[] bl = apL[p], br = apR[p];
                    float bufL = bl[idx % bl.length], bufR = br[idx % br.length];
                    bl[idx % bl.length] = l + bufL * 0.5f;
                    br[idx % br.length] = r + bufR * 0.5f;
                    l = bufL - l;
                    r = bufR - r;
                    apIdx[p] = (idx + 1) % (bl.length * br.length);
                }
                outL[k] += l * WET;
                outR[k] += r * WET;
            }
        }
    }

    // ------------------------------------------------------------------ voices

    static float[] render(ReplayMusicPlan.Note note) {
        double seconds = Math.min(MAX_NOTE_SECONDS, Math.max(0, note.length()));
        int len = Math.max(1, (int) Math.ceil(seconds * RATE));
        float[] out = new float[len];
        Rng rng = new Rng(seed(note));
        float v = note.velocity();
        double f = hz(note.midi());
        switch (note.voice()) {
            case KICK -> kick(out, v, rng, 46, 120, 30, 6.5);
            case HEART -> kick(out, v * 0.8f, null, 40, 45, 20, 9);
            case SNARE -> snare(out, v, rng);
            case CLAP -> clap(out, v, rng);
            case HAT -> hat(out, v * 0.45f, rng, 55);
            case OPEN_HAT -> hat(out, v * 0.38f, rng, 7);
            case CRASH -> crash(out, v, rng);
            case TOM -> tom(out, v, f);
            case BASS -> bass(out, v, f, seconds);
            case SUB_BOOM -> boom(out, v);
            case PAD -> pad(out, v, f, rng, seconds);
            case PLUCK -> pluck(out, v, f, rng);
            case ARP -> arp(out, v, f);
            case STAB -> stab(out, v, f, rng);
            case BELL -> bell(out, v, f);
            case LEAD -> lead(out, v, f, seconds);
            case RISER -> riser(out, v, f, rng);
            case REVERSE -> reverse(out, v, rng);
            case RING -> ring(out, v);
        }
        fadeEdges(out);
        return out;
    }

    private static void kick(float[] out, float v, Rng rng, double base, double sweep, double sweepRate, double decay) {
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            phase += TWO_PI * (base + sweep * Math.exp(-t * sweepRate)) / RATE;
            double s = Math.sin(phase) * Math.exp(-t * decay);
            if (rng != null && t < 0.004) s += rng.noise() * 0.3 * (1 - t / 0.004);
            out[i] = (float) (Math.tanh(s * 1.5) * v * 0.9);
        }
    }

    private static void snare(float[] out, float v, Rng rng) {
        OnePole low = new OnePole(1500);
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            float n = rng.noise();
            double body = Math.sin(TWO_PI * 185 * t) * Math.exp(-t * 25) * 0.5;
            double noise = (n - low.process(n)) * Math.exp(-t * 14) * 0.8;
            out[i] = (float) ((body + noise) * v * 0.7);
        }
    }

    private static void clap(float[] out, float v, Rng rng) {
        OnePole high = new OnePole(900), low = new OnePole(2400);
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            float n = rng.noise();
            float band = low.process(n - high.process(n));
            double env = 0;
            for (int k = 0; k < 3; k++) if (t >= k * 0.011) env += Math.exp(-(t - k * 0.011) * 80) * 0.6;
            if (t >= 0.022) env += Math.exp(-(t - 0.022) * 13) * 0.7;
            out[i] = (float) (band * env * v * 1.6);
        }
    }

    private static void hat(float[] out, float amp, Rng rng, double decay) {
        OnePole a = new OnePole(7000), b = new OnePole(7000);
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            float n = rng.noise();
            float h1 = n - a.process(n);
            float h2 = h1 - b.process(h1);
            out[i] = (float) (h2 * Math.exp(-t * decay) * amp * 1.4);
        }
    }

    private static void crash(float[] out, float v, Rng rng) {
        OnePole a = new OnePole(4500);
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            float n = rng.noise();
            float h = n - a.process(n);
            double shimmer = h * Math.sin(TWO_PI * 6200 * t) * 0.3 * Math.exp(-t * 3);
            out[i] = (float) ((h * 0.5 * Math.exp(-t * 1.9) + shimmer) * v * (t < 0.002 ? t / 0.002 : 1));
        }
    }

    private static void tom(float[] out, float v, double f) {
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            phase += TWO_PI * f * (1 + 0.6 * Math.exp(-t * 18)) / RATE;
            out[i] = (float) (Math.sin(phase) * Math.exp(-t * 7) * v * 0.8);
        }
    }

    private static void bass(float[] out, float v, double f, double seconds) {
        double phase = 0, lp1 = 0, lp2 = 0, a = 0;
        double release = Math.min(0.06, seconds * 0.3);
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            if ((i & 15) == 0) a = coefficient(160 + 1500 * v * Math.exp(-t * 8));
            phase += f / RATE;
            double frac = phase - Math.floor(phase);
            double saw = 2 * frac - 1;
            lp1 += a * (saw - lp1);
            lp2 += a * (lp1 - lp2);
            double s = lp2 * 0.65 + Math.sin(TWO_PI * phase) * 0.55;
            out[i] = (float) (s * envelope(t, 0.004, release, seconds) * v * 0.75);
        }
    }

    private static void boom(float[] out, float v) {
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            phase += TWO_PI * (32 + 40 * Math.exp(-t * 3)) / RATE;
            double amp = Math.exp(-t * 1.8) * Math.min(1, t / 0.005);
            out[i] = (float) (Math.tanh(Math.sin(phase) * amp * 1.4) * v * 0.85);
        }
    }

    private static void pad(float[] out, float v, double f, Rng rng, double seconds) {
        double[] detune = {Math.pow(2, -9 / 1200.0), 1, Math.pow(2, 9 / 1200.0), 0.5};
        double[] weight = {1, 1, 1, 0.3};
        double[] phase = new double[detune.length];
        for (int k = 0; k < phase.length; k++) phase[k] = (rng.noise() + 1) * 0.5;
        double a = coefficient(1300 + 900 * v), lp1 = 0, lp2 = 0;
        double attack = Math.min(0.35, seconds * 0.3), release = Math.min(0.7, seconds * 0.4);
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE, s = 0;
            for (int k = 0; k < detune.length; k++) {
                phase[k] += f * detune[k] / RATE;
                s += (2 * (phase[k] - Math.floor(phase[k])) - 1) * weight[k];
            }
            lp1 += a * (s - lp1);
            lp2 += a * (lp1 - lp2);
            out[i] = (float) (lp2 * envelope(t, attack, release, seconds) * v * 0.2);
        }
    }

    private static void pluck(float[] out, float v, double f, Rng rng) {
        int period = Math.max(2, (int) Math.round(RATE / f));
        float[] buf = new float[period];
        float smooth = 0;
        for (int i = 0; i < period; i++) {
            smooth += 0.5f * (rng.noise() - smooth);
            buf[i] = smooth;
        }
        int idx = 0;
        for (int i = 0; i < out.length; i++) {
            float cur = buf[idx];
            float nxt = buf[(idx + 1) % period];
            buf[idx] = (cur + nxt) * 0.5f * 0.996f;
            out[i] = cur * v * 0.9f;
            idx = (idx + 1) % period;
        }
    }

    private static void arp(float[] out, float v, double f) {
        double phase = 0, lp = 0, a = coefficient(3500);
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            phase += f / RATE;
            double frac = phase - Math.floor(phase);
            double square = frac < 0.5 ? 1 : -1;
            double tri = 1 - 4 * Math.abs(frac - 0.5);
            lp += a * ((square * 0.5 + tri * 0.6) - lp);
            out[i] = (float) (lp * Math.exp(-t * 8) * Math.min(1, t / 0.002) * v * 0.32);
        }
    }

    private static void stab(float[] out, float v, double f, Rng rng) {
        double[] detune = {Math.pow(2, -10 / 1200.0), Math.pow(2, 10 / 1200.0)};
        double[] phase = {(rng.noise() + 1) * 0.5, (rng.noise() + 1) * 0.5};
        double lp1 = 0, lp2 = 0, a = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE, s = 0;
            if ((i & 15) == 0) a = coefficient(500 + 3500 * Math.exp(-t * 9));
            for (int k = 0; k < 2; k++) {
                phase[k] += f * detune[k] / RATE;
                s += 2 * (phase[k] - Math.floor(phase[k])) - 1;
            }
            lp1 += a * (s - lp1);
            lp2 += a * (lp1 - lp2);
            out[i] = (float) (lp2 * Math.exp(-t * 5) * Math.min(1, t / 0.003) * v * 0.28);
        }
    }

    private static void bell(float[] out, float v, double f) {
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            double index = 2.0 * Math.exp(-t * 2.5);
            double carrier = Math.sin(TWO_PI * f * t + index * Math.sin(TWO_PI * 3.5 * f * t));
            double partial = Math.sin(TWO_PI * 2.01 * f * t) * 0.2 * Math.exp(-t * 4);
            out[i] = (float) ((carrier * Math.exp(-t * 2.3) + partial) * Math.min(1, t / 0.002) * v * 0.32);
        }
    }

    private static void lead(float[] out, float v, double f, double seconds) {
        double phase = 0, lp = 0, a = coefficient(2400);
        double release = Math.min(0.08, seconds * 0.3);
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE;
            double depth = Math.clamp((t - 0.2) / 0.3, 0.0, 1.0);
            double freq = f * Math.pow(2, 0.2 / 12 * Math.sin(TWO_PI * 5.5 * t) * depth);
            phase += freq / RATE;
            double frac = phase - Math.floor(phase);
            double s = (2 * frac - 1) * 0.6 + (frac < 0.5 ? 0.4 : -0.4);
            lp += a * (s - lp);
            out[i] = (float) (lp * envelope(t, 0.015, release, seconds) * v * 0.22);
        }
    }

    private static void riser(float[] out, float v, double f, Rng rng) {
        double lp1 = 0, lp2 = 0, a = 0, phase = 0;
        for (int i = 0; i < out.length; i++) {
            double tn = i / (double) out.length;
            if ((i & 15) == 0) a = coefficient(300 * Math.pow(30, tn));
            float n = rng.noise();
            lp1 += a * (n - lp1);
            lp2 += a * (lp1 - lp2);
            phase += TWO_PI * f * Math.pow(4, tn) / RATE;
            out[i] = (float) ((lp2 * 1.2 + Math.sin(phase) * 0.25) * tn * tn * v * 0.9);
        }
    }

    private static void reverse(float[] out, float v, Rng rng) {
        OnePole low = new OnePole(3000);
        for (int i = 0; i < out.length; i++) {
            double tn = i / (double) out.length;
            float n = rng.noise();
            out[i] = (float) ((n - low.process(n)) * Math.exp((tn - 1) * 5) * v * 0.5);
        }
    }

    private static void ring(float[] out, float v) {
        for (int i = 0; i < out.length; i++) {
            double t = i / (double) RATE, tn = i / (double) out.length;
            out[i] = (float) (Math.sin(TWO_PI * 3600 * t) * Math.min(1, t / 0.05) * (1 - tn) * (1 - tn) * v * 0.06);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static double envelope(double t, double attack, double release, double total) {
        if (attack > 0 && t < attack) return t / attack;
        if (release > 0 && t > total - release) return Math.max(0, (total - t) / release);
        return 1;
    }

    /** Short fade at the end so notes cut by the score never click. */
    private static void fadeEdges(float[] out) {
        int fade = Math.min(144, out.length / 4);
        for (int i = 0; i < fade; i++) out[out.length - 1 - i] *= i / (float) fade;
    }

    private static double coefficient(double cutoff) {
        return 1 - Math.exp(-TWO_PI * Math.clamp(cutoff, 20.0, 20_000.0) / RATE);
    }

    static double hz(int midi) {
        return 440.0 * Math.pow(2, (midi - 69) / 12.0);
    }

    private static long seed(ReplayMusicPlan.Note note) {
        long h = Double.doubleToLongBits(note.time()) * 0x9E3779B97F4A7C15L;
        h ^= (note.voice().ordinal() + 1L) * 0xBF58476D1CE4E5B9L;
        h ^= (note.midi() + 129L) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h == 0 ? 0x2545F4914F6CDD1DL : h;
    }

    private static final class Rng {
        private long state;

        Rng(long seed) { state = seed; }

        float noise() {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            return (state >>> 40) / (float) (1 << 24) * 2f - 1f;
        }
    }

    private static final class OnePole {
        private final float a;
        private float y;

        OnePole(double cutoff) { a = (float) coefficient(cutoff); }

        float process(float x) {
            y += a * (x - y);
            return y;
        }
    }
}
