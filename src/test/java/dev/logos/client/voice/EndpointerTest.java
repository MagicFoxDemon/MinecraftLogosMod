package dev.logos.client.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class EndpointerTest {
    private static final int RATE = SpeechEmbedding.SAMPLE_RATE;

    /** Room noise, with a tone standing for speech over each [start, end) second span. */
    private static float[] audio(double seconds, double... spans) {
        Random r = new Random(3);
        float[] x = new float[(int) (seconds * RATE)];
        for (int n = 0; n < x.length; n++) x[n] = (float) (r.nextGaussian() * 0.002);
        for (int s = 0; s < spans.length; s += 2)
            for (int n = (int) (spans[s] * RATE); n < (int) (spans[s + 1] * RATE); n++) x[n] += (float) (0.3 * Math.sin(2 * Math.PI * 300 * n / RATE));
        return x;
    }

    /** Feeds {@code x} in 10 ms pieces, as the microphone does. */
    private static List<float[]> cut(float[] x) {
        List<float[]> out = new ArrayList<>();
        Endpointer e = new Endpointer(out::add);
        for (int i = 0; i < x.length; i += RATE / 100) {
            float[] chunk = java.util.Arrays.copyOfRange(x, i, Math.min(x.length, i + RATE / 100));
            e.accept(chunk);
        }
        return out;
    }

    @Test
    void oneWordComesOutWithAShortLeadAndTail() {
        List<float[]> said = cut(audio(3, 1.0, 1.6));
        assertEquals(1, said.size());
        double seconds = said.getFirst().length / (double) RATE;
        // 0.6 s of speech, about a quarter second before and a tenth after.
        assertTrue(seconds > 0.85 && seconds < 1.05, "cut " + seconds + " s");
    }

    @Test
    void aPauseSeparatesTwoWords() {
        assertEquals(2, cut(audio(4, 0.8, 1.3, 2.0, 2.6)).size());
    }

    @Test
    void aShortBreathInsideAWordDoesNotSplitIt() {
        assertEquals(1, cut(audio(3, 0.8, 1.2, 1.4, 1.8)).size());
    }

    @Test
    void quietRoomNoiseIsNothing() {
        assertEquals(0, cut(audio(3)).size());
    }

    @Test
    void speechStillGoingWhenTheStreamStopsIsNotCutYet() {
        assertEquals(0, cut(audio(2, 1.0, 2.0)).size());
    }
}
