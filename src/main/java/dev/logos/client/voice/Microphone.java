package dev.logos.client.voice;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALUtil;

/**
 * Microphone capture through the game's own OpenAL, so it needs no extra library and works on
 * every platform the game does. While open it hands each new piece of audio, as 16 kHz mono
 * floats, to its {@link #listen listener} (an {@link Endpointer}, usually) on its own thread. Use
 * it only after the game's sound engine has started (OpenAL must be loaded).
 */
public final class Microphone implements AutoCloseable {
    private static final int RATE = SpeechEmbedding.SAMPLE_RATE;

    private final long device;
    private final int factor;
    private volatile boolean closed;
    private volatile float level;
    private volatile @Nullable Consumer<float[]> listener;

    /** Capture device names, the first being the system default. */
    public static List<String> devices() {
        List<String> all = ALUtil.getStringList(0, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
        return all == null ? List.of() : all;
    }

    /** Opens {@code device}, or the system default when null, and starts capturing. */
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

    /** Where captured audio goes from now on; null drops it. */
    public void listen(@Nullable Consumer<float[]> listener) {
        this.listener = listener;
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
                float[] chunk = new float[n / factor];
                double sum = 0;
                for (int i = 0; i < chunk.length; i++) {
                    float v = 0;
                    for (int k = 0; k < factor; k++) v += buf[i * factor + k];
                    chunk[i] = v / (factor * 32768f);
                    sum += chunk[i] * chunk[i];
                }
                level = (float) Math.sqrt(sum / chunk.length);
                Consumer<float[]> l = listener;
                if (l != null) l.accept(chunk);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Recent loudness, 0 to 1 (RMS), for a level meter. */
    public float level() {
        return level;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        listener = null;
        ALC11.alcCaptureStop(device);
        ALC11.alcCaptureCloseDevice(device);
    }
}
