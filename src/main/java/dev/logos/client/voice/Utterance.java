package dev.logos.client.voice;

import java.util.Arrays;

/**
 * One thing a player said: the audio with its leading and trailing silence cut, and its
 * {@link SpeechEmbedding#embedDense dense} feature vectors. Two utterances are compared by dynamic
 * time warping, so saying a spell faster or slower than when it was recorded still matches.
 */
public record Utterance(float[] audio, float[][] features) {
    /** Peak level below which a recording is treated as silence (full scale is 1). */
    public static final float QUIET = 0.02f;

    public static Utterance of(SpeechEmbedding model, float[] raw) {
        float[] trimmed = trim(raw);
        return new Utterance(trimmed, model.embedDense(trimmed));
    }

    public double seconds() {
        return audio.length / (double) SpeechEmbedding.SAMPLE_RATE;
    }

    public static float peak(float[] x) {
        float p = 0;
        for (float v : x) p = Math.max(p, Math.abs(v));
        return p;
    }

    /**
     * Cuts silence by 20 ms energy: the threshold is 5% of the loudest frame or three times the
     * noise floor (the 10th-percentile frame), whichever is higher, and 40 ms is kept on each side
     * so soft consonants at the edges survive.
     */
    public static float[] trim(float[] x) {
        int f = SpeechEmbedding.SAMPLE_RATE / 50, frames = x.length / f;
        if (frames < 3) return x;
        double[] e = new double[frames];
        double peak = 0;
        for (int i = 0; i < frames; i++) {
            double s = 0;
            for (int k = 0; k < f; k++) s += x[i * f + k] * x[i * f + k];
            e[i] = Math.sqrt(s / f);
            peak = Math.max(peak, e[i]);
        }
        double[] sorted = e.clone();
        Arrays.sort(sorted);
        double threshold = Math.max(peak * 0.05, sorted[frames / 10] * 3);
        int a = 0, b = frames - 1;
        while (a < frames && e[a] < threshold) a++;
        while (b > a && e[b] < threshold) b--;
        a = Math.max(0, a - 2);
        b = Math.min(frames - 1, b + 2);
        return Arrays.copyOfRange(x, a * f, Math.min(x.length, (b + 1) * f));
    }

    /** How different two utterances sound: 0 is identical; the same word by the same speaker is usually under 0.1. */
    public double distance(Utterance other) {
        return dtw(features, other.features);
    }

    /** Dynamic time warping over cosine distances, divided by the summed lengths so long and short words compare fairly. */
    static double dtw(float[][] x, float[][] y) {
        int n = x.length, m = y.length;
        if (n == 0 || m == 0) return Double.POSITIVE_INFINITY;
        double[] prev = new double[m + 1], cur = new double[m + 1];
        Arrays.fill(prev, Double.POSITIVE_INFINITY);
        prev[0] = 0;
        for (int i = 1; i <= n; i++) {
            cur[0] = Double.POSITIVE_INFINITY;
            for (int j = 1; j <= m; j++) cur[j] = cosine(x[i - 1], y[j - 1]) + Math.min(prev[j - 1], Math.min(prev[j], cur[j - 1]));
            double[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m] / (n + m);
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return 1 - dot / Math.sqrt(na * nb + 1e-12);
    }
}
