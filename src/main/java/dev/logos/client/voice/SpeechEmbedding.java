package dev.logos.client.voice;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Google's speech_embedding model (Lin et al. 2020, Apache-2.0) run in plain Java: 16 kHz mono
 * audio in, one 96-number vector per 80 ms out. It is a generic "what does this sound like"
 * encoder, not a speech recogniser, which is why a player's own recordings of a spell name can be
 * matched against what they say later without training anything. No native library, so it runs
 * wherever the game does.
 *
 * <p>Front end: 400-sample periodic Hann window every 160 samples, 512-point FFT magnitude, 32 mel
 * bands, log(x + 0.001). Trunk: a 3×3 convolution with batch norm, then 1×3 / 3×1 convolutions in
 * turn with LeakyReLU(0.2) floored at -0.4 and five max pools. Weights are in
 * {@code assets/logos/voice/speech_embedding.bin} (convolution kernels as float16, the rest
 * float32); the exporter is {@code tools/voice/}.
 */
public final class SpeechEmbedding {
    public static final int SAMPLE_RATE = 16000;
    /** The shortest input that yields one vector; shorter audio is padded to this. */
    public static final int MIN_SAMPLES = 12400;
    public static final int DIM = 96;
    /** {@link #embedDense} runs this many copies, each shifted by {@link #SHIFT} samples (20 ms). */
    public static final int SHIFTS = 4, SHIFT = 320;
    private static final int WIN = 400, HOP = 160, NFFT = 512, BINS = NFFT / 2 + 1, MELS = 32;
    private static final String RESOURCE = "/assets/logos/voice/speech_embedding.bin";
    /** Output channels of the 18 trunk convolutions; even ones run along the mel axis (1×3), odd ones along time (3×1). */
    private static final int[] OUT = {24, 24, 48, 48, 48, 48, 72, 72, 72, 72, 96, 96, 96, 96, 96, 96, 96, 96};

    private static SpeechEmbedding shared;

    private final float[] hann, mel, stemW, bnScale, bnShift, lastW;
    private final float[][] convW = new float[OUT.length][], convB = new float[OUT.length][];
    private final double[] cos = new double[NFFT / 2], sin = new double[NFFT / 2];

    /** The model bundled with the mod, loaded once. */
    public static synchronized SpeechEmbedding get() throws IOException {
        if (shared == null) {
            try (InputStream in = SpeechEmbedding.class.getResourceAsStream(RESOURCE)) {
                if (in == null) throw new IOException("missing " + RESOURCE);
                shared = new SpeechEmbedding(in);
            }
        }
        return shared;
    }

    public SpeechEmbedding(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(source));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("LSE2")) throw new IOException("not a speech embedding file");
        hann = read(in);
        mel = read(in);
        stemW = read(in);
        float[] gamma = read(in), beta = read(in), mean = read(in), var = read(in);
        bnScale = new float[gamma.length];
        bnShift = new float[gamma.length];
        for (int c = 0; c < gamma.length; c++) {
            bnScale[c] = (float) (gamma[c] / Math.sqrt(var[c] + 0.001));
            bnShift[c] = beta[c] - mean[c] * bnScale[c];
        }
        for (int i = 0; i < OUT.length; i++) {
            convW[i] = read(in);
            convB[i] = read(in);
        }
        lastW = read(in);
        for (int k = 0; k < NFFT / 2; k++) {
            cos[k] = Math.cos(2 * Math.PI * k / NFFT);
            sin[k] = -Math.sin(2 * Math.PI * k / NFFT);
        }
    }

    /** One tensor: a little-endian count, its bit width (16 or 32), then the values. */
    private static float[] read(DataInputStream in) throws IOException {
        int n = Integer.reverseBytes(in.readInt());
        boolean half = in.readByte() == 16;
        byte[] raw = new byte[n * (half ? 2 : 4)];
        in.readFully(raw);
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] t = new float[n];
        for (int i = 0; i < n; i++) t[i] = half ? Float.float16ToFloat(b.getShort()) : b.getFloat();
        return t;
    }

    /** One vector per 80 ms of {@code audio}, padded to {@link #MIN_SAMPLES} when shorter. */
    public float[][] embed(float[] audio) {
        float[] x = pad(audio, MIN_SAMPLES);
        int frames = 1 + (x.length - WIN) / HOP;
        return trunk(logMel(x, frames), 0, frames);
    }

    /**
     * One vector per 20 ms: the model run on the audio shifted by 0, 20, 40 and 60 ms, interleaved
     * in time. Short spell names give only one or two plain vectors, too few for the timing of a
     * word to count; this gives the matcher four times as many. A 20 ms shift is exactly two
     * front-end frames, so the spectrogram is computed once and only the trunk runs four times.
     */
    public float[][] embedDense(float[] audio) {
        float[] x = pad(audio, MIN_SAMPLES + SHIFT * (SHIFTS - 1));
        int frames = 1 + (x.length - WIN) / HOP, step = SHIFT / HOP;
        float[] spec = logMel(x, frames);
        float[][][] parts = new float[SHIFTS][][];
        for (int k = 0; k < SHIFTS; k++) parts[k] = trunk(spec, k * step, frames - k * step);
        float[][] out = new float[Arrays.stream(parts).mapToInt(p -> p.length).sum()][];
        int i = 0;
        for (int n = 0; i < out.length; n++)
            for (float[][] p : parts) if (n < p.length) out[i++] = p[n];
        return out;
    }

    static float[] pad(float[] x, int min) {
        if (x.length >= min) return x;
        float[] p = new float[min];
        System.arraycopy(x, 0, p, (min - x.length) / 2, x.length);
        return p;
    }

    private float[] logMel(float[] x, int frames) {
        float[] out = new float[frames * MELS];
        double[] re = new double[NFFT], im = new double[NFFT];
        float[] mag = new float[BINS];
        for (int f = 0; f < frames; f++) {
            Arrays.fill(re, 0);
            Arrays.fill(im, 0);
            for (int n = 0; n < WIN; n++) re[n] = x[f * HOP + n] * hann[n];
            fft(re, im);
            for (int k = 0; k < BINS; k++) mag[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            for (int m = 0; m < MELS; m++) {
                double s = 0;
                for (int k = 0; k < BINS; k++) s += mag[k] * mel[k * MELS + m];
                out[f * MELS + m] = (float) Math.log(s + 0.001);
            }
        }
        return out;
    }

    /** In-place iterative radix-2 FFT. */
    private void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < len / 2; k++) {
                    double wr = cos[k * step], wi = sin[k * step];
                    int u = i + k, v = u + len / 2;
                    double xr = re[v] * wr - im[v] * wi, xi = re[v] * wi + im[v] * wr;
                    re[v] = re[u] - xr;
                    im[v] = im[u] - xi;
                    re[u] += xr;
                    im[u] += xi;
                }
            }
        }
    }

    /** The convolutional trunk over {@code frames} spectrogram rows starting at row {@code from}. Tensors are [channel][time][mel]. */
    private float[][] trunk(float[] spec, int from, int frames) {
        int t = frames, w = MELS, c = 24;
        float[] a = stem(spec, from, t, w);
        t -= 2;
        for (int i = 0; i < OUT.length; i++) {
            boolean alongMel = i % 2 == 0;
            a = conv(a, c, t, w, convW[i], convB[i], OUT[i], alongMel);
            c = OUT[i];
            if (!alongMel) t -= 2;
            activate(a);
            if (i == 1 || i == 9 || i == 17) {
                a = pool(a, c, t, w, true);
                t /= 2;
                w /= 2;
            } else if (i == 5 || i == 13) {
                a = pool(a, c, t, w, false);
                w = (w + 1) / 2;
            }
        }
        a = conv(a, c, t, w, lastW, null, DIM, false);
        t -= 2;
        float[][] out = new float[Math.max(0, t)][DIM];
        for (int f = 0; f < t; f++) for (int k = 0; k < DIM; k++) out[f][k] = a[k * t + f];
        return out;
    }

    /** First layer: 3×3 convolution (no time padding, one mel bin each side), ReLU, batch norm, activation. */
    private float[] stem(float[] spec, int from, int t, int w) {
        int to = Math.max(0, t - 2);
        float[] out = new float[24 * to * w];
        for (int oc = 0; oc < 24; oc++) {
            for (int y = 0; y < to; y++) {
                for (int x = 0; x < w; x++) {
                    double s = 0;
                    for (int ky = 0; ky < 3; ky++) {
                        int row = (from + y + ky) * w;
                        for (int kx = 0; kx < 3; kx++) {
                            int xx = x + kx - 1;
                            if (xx >= 0 && xx < w) s += stemW[oc * 9 + ky * 3 + kx] * spec[row + xx];
                        }
                    }
                    out[(oc * to + y) * w + x] = (float) Math.max(0, s) * bnScale[oc] + bnShift[oc];
                }
            }
        }
        activate(out);
        return out;
    }

    /** A 1×3 convolution along mel (padded one each side) or a 3×1 along time (unpadded); weights are [out][in][3]. */
    private static float[] conv(float[] in, int ci, int t, int w, float[] wt, float[] bias, int co, boolean alongMel) {
        int to = alongMel ? t : t - 2;
        if (to <= 0) return new float[0];
        int plane = to * w;
        float[] out = new float[co * plane];
        for (int oc = 0; oc < co; oc++) {
            int ob = oc * plane;
            if (bias != null) Arrays.fill(out, ob, ob + plane, bias[oc]);
            for (int ic = 0; ic < ci; ic++) {
                int ib = ic * t * w, wb = (oc * ci + ic) * 3;
                float k0 = wt[wb], k1 = wt[wb + 1], k2 = wt[wb + 2];
                for (int y = 0; y < to; y++) {
                    int r = ib + y * w, o = ob + y * w;
                    if (alongMel) {
                        for (int x = 0; x < w; x++) {
                            float s = k1 * in[r + x];
                            if (x > 0) s += k0 * in[r + x - 1];
                            if (x < w - 1) s += k2 * in[r + x + 1];
                            out[o + x] += s;
                        }
                    } else {
                        for (int x = 0; x < w; x++) out[o + x] += k0 * in[r + x] + k1 * in[r + w + x] + k2 * in[r + 2 * w + x];
                    }
                }
            }
        }
        return out;
    }

    private static void activate(float[] a) {
        for (int i = 0; i < a.length; i++) a[i] = Math.max(a[i] < 0 ? a[i] * 0.2f : a[i], -0.4f);
    }

    /** 2×2 max pool with stride 2, or (alongTimeToo false) 1×2 along mel only, an odd last column pooling alone. */
    private static float[] pool(float[] in, int c, int t, int w, boolean alongTimeToo) {
        int kt = alongTimeToo ? 2 : 1, to = t / kt, wo = alongTimeToo ? w / 2 : (w + 1) / 2;
        float[] out = new float[c * to * wo];
        for (int ch = 0; ch < c; ch++) {
            for (int y = 0; y < to; y++) {
                for (int x = 0; x < wo; x++) {
                    float m = Float.NEGATIVE_INFINITY;
                    for (int dy = 0; dy < kt; dy++)
                        for (int dx = 0; dx < 2; dx++)
                            if (x * 2 + dx < w) m = Math.max(m, in[(ch * t + y * kt + dy) * w + x * 2 + dx]);
                    out[(ch * to + y) * wo + x] = m;
                }
            }
        }
        return out;
    }
}
