package dev.logos.client.voice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cuts a stream of microphone audio into the things said in it, so the player does not have to say
 * when they have finished: speech starts once it stands clearly above the room's own noise for
 * 60 ms, and ends after 0.4 s of quiet. Each piece goes out with a quarter second of what came
 * before (soft first sounds) and a tenth of a second after. The noise level is learnt as it goes.
 */
public final class Endpointer {
    /** 20 ms at {@link SpeechEmbedding#SAMPLE_RATE}. */
    static final int FRAME = SpeechEmbedding.SAMPLE_RATE / 50;
    private static final int START_FRAMES = 3, END_FRAMES = 20, PREROLL_FRAMES = 12, TAIL_FRAMES = 5, MAX_FRAMES = 250;
    /** Frames ignored after opening: some devices click as they start. */
    private static final int WARMUP_FRAMES = 5;

    private final Consumer<float[]> out;
    private final float[] frame = new float[FRAME];
    private int filled;
    private final ArrayDeque<float[]> before = new ArrayDeque<>();
    private List<float[]> speech;
    private int loud, quiet, warmup = WARMUP_FRAMES;
    private double floor = 0.003;

    /** {@code out} gets each finished utterance, on whatever thread feeds {@link #accept}. */
    public Endpointer(Consumer<float[]> out) {
        this.out = out;
    }

    /** True while the player is in the middle of saying something. */
    public synchronized boolean speaking() {
        return speech != null;
    }

    public void accept(float[] samples) {
        accept(samples, samples.length);
    }

    public synchronized void accept(float[] samples, int length) {
        for (int i = 0; i < length; i++) {
            frame[filled++] = samples[i];
            if (filled == FRAME) {
                frame(frame.clone());
                filled = 0;
            }
        }
    }

    /** Forgets anything half said; the learnt noise level stays. */
    public synchronized void reset() {
        speech = null;
        before.clear();
        loud = quiet = filled = 0;
    }

    private void frame(float[] f) {
        double sum = 0;
        for (float v : f) sum += v * v;
        double rms = Math.sqrt(sum / f.length);
        if (warmup > 0) {
            warmup--;
            return;
        }
        if (speech == null) {
            before.addLast(f);
            if (before.size() > PREROLL_FRAMES) before.removeFirst();
            // The floor follows the quiet quickly and loud noise slowly, so speech does not raise it much.
            floor += (rms - floor) * (rms < floor ? 0.2 : 0.01);
            loud = rms > Math.max(0.01, floor * 4) ? loud + 1 : 0;
            if (loud >= START_FRAMES) {
                speech = new ArrayList<>(before);
                quiet = 0;
            }
            return;
        }
        speech.add(f);
        quiet = rms < Math.max(0.006, floor * 2.5) ? quiet + 1 : 0;
        if (quiet >= END_FRAMES) finish(speech.size() - quiet + TAIL_FRAMES);
        else if (speech.size() >= MAX_FRAMES) finish(speech.size());
    }

    private void finish(int frames) {
        float[] audio = new float[frames * FRAME];
        for (int i = 0; i < frames; i++) System.arraycopy(speech.get(i), 0, audio, i * FRAME, FRAME);
        speech = null;
        before.clear();
        loud = quiet = 0;
        out.accept(audio);
    }
}
