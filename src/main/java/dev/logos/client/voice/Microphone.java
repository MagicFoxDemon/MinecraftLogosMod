package dev.logos.client.voice;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALUtil;

/**
 * Microphone capture through the game's own OpenAL, so it needs no extra library and works on
 * every platform the game does. While open it keeps the last {@link #PREROLL_SECONDS} of audio, so
 * {@link #begin} also catches a word the player started a moment before pressing. Audio comes out
 * as 16 kHz mono floats, ready for {@link SpellVoices}. Use it only after the game's sound engine
 * has started (OpenAL must be loaded).
 */
public final class Microphone implements AutoCloseable {
    public static final double PREROLL_SECONDS = 0.3;
    /** Longest single take kept; anything longer is cut, so a stuck key cannot grow memory without end. */
    public static final double MAX_SECONDS = 10;
    private static final int RATE = SpeechEmbedding.SAMPLE_RATE;

    private final long device;
    private final int factor;
    private final float[] ring = new float[(int) (RATE * PREROLL_SECONDS)];
    private int ringPos;
    private float[] take = new float[0];
    private int takeLength;
    private boolean recording;
    private volatile boolean closed;
    private volatile float level;

    /** Capture device names, the first being the system default. */
    public static List<String> devices() {
        List<String> all = ALUtil.getStringList(0, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
        return all == null ? List.of() : all;
    }

    /** Opens {@code device}, or the system default when null, and starts listening. */
    public static Microphone open(@Nullable String device) throws IOException {
        for (int factor : new int[] {1, 3}) {
            long handle = ALC11.alcCaptureOpenDevice(device, RATE * factor, AL10.AL_FORMAT_MONO16, RATE * factor / 2);
            if (handle != 0) return new Microphone(handle, factor);
        }
        throw new IOException("could not open microphone " + (device == null ? "(default)" : device));
    }

    private Microphone(long device, int factor) {
        this.device = device;
        this.factor = factor;
        ALC11.alcCaptureStart(device);
        Thread t = new Thread(this::pump, "Logos microphone");
        t.setDaemon(true);
        t.start();
    }

    /** Polls the device every 10 ms; a 48 kHz device is brought down to 16 kHz by averaging each three samples. */
    private void pump() {
        short[] buf = new short[RATE * factor / 10];
        while (!closed) {
            int n;
            synchronized (this) {
                if (closed) return;
                n = Math.min(ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES), buf.length);
                n -= n % factor;
                if (n > 0) ALC11.alcCaptureSamples(device, buf, n);
            }
            if (n > 0) {
                double sum = 0;
                synchronized (this) {
                    for (int i = 0; i < n; i += factor) {
                        float v = 0;
                        for (int k = 0; k < factor; k++) v += buf[i + k];
                        v /= factor * 32768f;
                        sum += v * v;
                        push(v);
                    }
                }
                level = (float) Math.sqrt(sum / (n / factor));
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void push(float v) {
        if (!recording) {
            ring[ringPos] = v;
            ringPos = (ringPos + 1) % ring.length;
            return;
        }
        if (takeLength >= RATE * MAX_SECONDS) return;
        if (takeLength == take.length) take = Arrays.copyOf(take, Math.max(RATE, take.length * 2));
        take[takeLength++] = v;
    }

    /** Starts a take, beginning with the pre-roll. */
    public synchronized void begin() {
        take = new float[RATE * 2];
        takeLength = 0;
        recording = true;
        for (int i = 0; i < ring.length; i++) push(ring[(ringPos + i) % ring.length]);
    }

    /** Ends the take and returns it. Callers that want the tail of a word said while letting go should wait a moment (about 0.2 s) first. */
    public synchronized float[] end() {
        recording = false;
        float[] out = Arrays.copyOf(take, takeLength);
        take = new float[0];
        takeLength = 0;
        return out;
    }

    public synchronized boolean recording() {
        return recording;
    }

    /** Recent loudness, 0 to 1 (RMS), for a level meter. */
    public float level() {
        return level;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        ALC11.alcCaptureStop(device);
        ALC11.alcCaptureCloseDevice(device);
    }
}
