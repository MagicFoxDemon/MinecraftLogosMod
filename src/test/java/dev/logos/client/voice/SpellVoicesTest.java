package dev.logos.client.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpellVoicesTest {
    private static final int RATE = SpeechEmbedding.SAMPLE_RATE;

    /** A second of two tones, one sweeping; the reference vectors came from ONNX Runtime on the original model. */
    private static float[] chirp() {
        float[] x = new float[RATE];
        for (int n = 0; n < x.length; n++) {
            double t = n / (double) RATE;
            x[n] = (float) (0.3 * StrictMath.sin(2 * Math.PI * (300 * t + 400 * t * t)) + 0.1 * StrictMath.sin(2 * Math.PI * 1200 * t));
        }
        return x;
    }

    @Test
    void bundledModelMatchesTheOriginalModel() throws IOException {
        float[][] reference;
        try (InputStream in = SpellVoicesTest.class.getResourceAsStream("chirp_embedding.txt")) {
            reference = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(l -> {
                        String[] parts = l.trim().split(" ");
                        float[] f = new float[parts.length];
                        for (int i = 0; i < f.length; i++) f[i] = Float.parseFloat(parts[i]);
                        return f;
                    }).toArray(float[][]::new);
        }
        float[][] got = SpeechEmbedding.get().embed(chirp());
        assertEquals(reference.length, got.length);
        for (int f = 0; f < got.length; f++) {
            assertEquals(SpeechEmbedding.DIM, got[f].length);
            assertTrue(Utterance.cosine(got[f], reference[f]) < 1e-4, "frame " + f + " drifted from the reference");
        }
    }

    @Test
    void shortAudioIsPaddedToOneVector() throws IOException {
        SpeechEmbedding model = SpeechEmbedding.get();
        assertEquals(1, model.embed(new float[RATE / 4]).length);
        assertEquals(1, model.embed(new float[SpeechEmbedding.MIN_SAMPLES]).length);
        assertEquals(3, model.embed(new float[RATE]).length);
    }

    @Test
    void denseVectorsAreThePlainOnesShiftedBy20Ms() throws IOException {
        SpeechEmbedding model = SpeechEmbedding.get();
        float[] x = chirp();
        float[][] dense = model.embedDense(x);
        float[][] plain = model.embed(x);
        float[][] shifted = model.embed(Arrays.copyOfRange(x, SpeechEmbedding.SHIFT, x.length));
        assertArrayEquals(plain[0], dense[0], 1e-4f);
        assertArrayEquals(shifted[0], dense[1], 1e-4f);
        assertArrayEquals(plain[1], dense[SpeechEmbedding.SHIFTS], 1e-4f);
    }

    @Test
    void warpingDistanceIsZeroForItselfAndSymmetric() throws IOException {
        SpeechEmbedding model = SpeechEmbedding.get();
        Utterance a = Utterance.of(model, word(new double[] {300, 800, 500}, 1, 1));
        Utterance b = Utterance.of(model, word(new double[] {900, 400, 1200}, 1, 2));
        assertEquals(0, a.distance(a), 1e-9);
        assertEquals(a.distance(b), b.distance(a), 1e-9);
        assertTrue(a.distance(b) > 0);
    }

    @Test
    void trimCutsTheSilenceAroundASound() {
        float[] x = new float[RATE * 3 / 2];
        Random r = new Random(1);
        for (int n = 0; n < x.length; n++) x[n] = (float) (r.nextGaussian() * 0.001);
        for (int n = RATE / 2; n < RATE; n++) x[n] += (float) (0.4 * Math.sin(2 * Math.PI * 440 * n / RATE));
        double seconds = Utterance.trim(x).length / (double) RATE;
        assertTrue(seconds >= 0.5 && seconds <= 0.6, "kept " + seconds + " s");
    }

    @Test
    void recognizesEachEnrolledWordAndRanksAnUnknownOneFarther(@TempDir Path dir) throws IOException {
        SpellVoices voices = new SpellVoices(dir, SpeechEmbedding.get());
        double[] a = {300, 800, 500}, b = {900, 400, 1200}, c = {600, 600, 200};
        for (int take = 0; take < 3; take++) {
            assertTrue(voices.enroll("Ignis", word(a, 1 + 0.08 * (take - 1), take)).saved());
            assertTrue(voices.enroll("Glacies", word(b, 1 + 0.08 * (take - 1), 10 + take)).saved());
        }
        SpellVoices.Recognition ra = voices.recognize(word(a, 0.9, 99), null);
        SpellVoices.Recognition rb = voices.recognize(word(b, 1.1, 98), null);
        SpellVoices.Recognition rc = voices.recognize(word(c, 1, 97), null);
        assertNotNull(ra);
        assertEquals("Ignis", ra.name());
        assertEquals("Glacies", ra.runnerUp());
        assertEquals("Glacies", rb.name());
        assertTrue(rc.distance() > Math.max(ra.distance(), rb.distance()), "an unknown word should match nothing as well as a known one");
        assertEquals("Glacies", voices.recognize(word(a, 0.9, 99), List.of("Glacies")).name());
        assertNull(voices.recognize(word(a, 0.9, 99), List.of("Ventus")));
    }

    @Test
    void aLooserMatchCountsOnlyWhenItClearlyLeads() {
        assertTrue(SpellVoices.accepts(0.05, 0.20), "close and alone");
        assertFalse(SpellVoices.accepts(0.05, 0.04), "close, but another recorded name is closer");
        assertTrue(SpellVoices.accepts(0.15, 0.25), "a little off, far ahead of every other name");
        assertFalse(SpellVoices.accepts(0.15, 0.19), "a little off and not clearly ahead: could be either");
        assertFalse(SpellVoices.accepts(0.15, Double.POSITIVE_INFINITY), "a little off with nothing to compare against");
        assertFalse(SpellVoices.accepts(0.19, 0.40), "too far whatever the lead");
    }

    @Test
    void aWordLikeASpellLeftOffTheWandIsNotTakenForOneOnIt(@TempDir Path dir) throws IOException {
        SpellVoices voices = new SpellVoices(dir, SpeechEmbedding.get());
        double[] a = {300, 800, 500}, b = {900, 400, 1200};
        for (int take = 0; take < 3; take++) {
            voices.enroll("Ignis", word(a, 1 + 0.08 * (take - 1), take));
            voices.enroll("Glacies", word(b, 1 + 0.08 * (take - 1), 10 + take));
        }
        SpellVoices.Recognition r = voices.recognize(word(a, 0.95, 99), List.of("Glacies"));
        assertEquals("Glacies", r.name());
        assertEquals("Ignis", r.runnerUp(), "the runner-up counts every recorded name, not just the candidates");
        assertFalse(r.accepted(), "it sounds like Ignis, which is not a candidate");
    }

    @Test
    void silenceIsNotSavedOrRecognized(@TempDir Path dir) throws IOException {
        SpellVoices voices = new SpellVoices(dir, SpeechEmbedding.get());
        SpellVoices.Enrollment e = voices.enroll("Ignis", new float[RATE]);
        assertEquals(SpellVoices.Problem.TOO_QUIET, e.problem());
        assertFalse(e.saved());
        assertEquals(0, voices.count("Ignis"));
        voices.enroll("Ignis", word(new double[] {300, 800, 500}, 1, 0));
        assertNull(voices.recognize(new float[RATE], null));
    }

    @Test
    void recordingsSurviveAReloadAndCanBeRemoved(@TempDir Path dir) throws IOException {
        String odd = "火球/术?*: \"Ignis\"";
        SpellVoices voices = new SpellVoices(dir, SpeechEmbedding.get());
        voices.enroll(odd, word(new double[] {300, 800, 500}, 1, 0));
        voices.enroll(odd, word(new double[] {300, 800, 500}, 1.05, 1));
        voices.enroll("Glacies", word(new double[] {900, 400, 1200}, 1, 2));

        SpellVoices again = new SpellVoices(dir, SpeechEmbedding.get());
        again.load();
        assertEquals(Set.of(odd, "Glacies"), again.names());
        assertEquals(2, again.count(odd));
        assertEquals(odd, again.recognize(word(new double[] {300, 800, 500}, 0.95, 3), null).name());

        assertTrue(again.removeLast(odd));
        assertEquals(1, again.count(odd));
        again.forget("Glacies");
        again.load();
        assertEquals(Set.of(odd), again.names());
        try (var folders = Files.list(dir)) {
            assertEquals(1, folders.count());
        }
    }

    @Test
    void folderNamesAreSafeOnEveryFileSystem() {
        String folder = SpellVoices.folderName("火球/术?*: \"Ignis\" <x>|");
        assertTrue(folder.chars().noneMatch(ch -> "/\\:*?\"<>| ".indexOf(ch) >= 0), folder);
        assertTrue(folder.startsWith("火球_术"));
        assertFalse(SpellVoices.folderName("a/b").equals(SpellVoices.folderName("a_b")));
    }

    /**
     * A made-up "word": three 150 ms tones with a little noise, around silence, at {@code speed}
     * (above 1 is faster). Different takes of the same tones stand in for a player repeating a name.
     */
    private static float[] word(double[] tones, double speed, long seed) {
        Random r = new Random(seed);
        int segment = (int) (RATE * 0.15 / speed), pad = RATE / 5;
        float[] x = new float[pad * 2 + segment * tones.length];
        for (int n = 0; n < x.length; n++) x[n] = (float) (r.nextGaussian() * 0.002);
        double phase = 0;
        for (int s = 0; s < tones.length; s++) {
            double hz = tones[s] * (1 + r.nextGaussian() * 0.01);
            for (int n = 0; n < segment; n++) {
                phase += 2 * Math.PI * hz / RATE;
                x[pad + s * segment + n] += (float) (0.3 * Math.sin(phase) + 0.1 * Math.sin(2 * phase));
            }
        }
        return x;
    }
}
