package dev.logos.client.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import dev.logos.sage.SageSettings;

/**
 * The player's recordings of their spell names, and recognition against them. Each spell name
 * keeps a few recordings of the player saying it; what they say later is matched to the closest
 * one. Recordings are the player's own voice, so they stay in the user's Logos folder outside the
 * game instance (next to the sage's settings) rather than travelling with a shared modpack, one
 * folder per name holding 16 kHz WAV files and the name itself.
 *
 * <p>Embedding takes tens of milliseconds per utterance: call {@link #load}, {@link #enroll} and
 * {@link #recognize} off the render thread.
 */
public final class SpellVoices {
    /** A match closer than this is taken as the spell. From one player's tests in three languages: matches 0.015–0.10, other speech above 0.14. */
    public static final double ACCEPT = 0.12;
    /**
     * A looser match is still taken when it stands out: closer than {@code LOOSE} and ahead of every
     * other recorded name by {@code LEAD}. This forgives a name said a little off without letting in
     * a word that merely sits between two names; on the same player's recordings it caught one more
     * of 57 takes and let in no more unrecorded words than {@link #ACCEPT} alone.
     */
    public static final double LOOSE = 0.18, LEAD = 0.06;
    /** Recordings with less speech than this are rejected as accidental. */
    public static final double MIN_SECONDS = 0.2;

    private record Sample(Path file, Utterance utterance) {}

    private final Path dir;
    private final SpeechEmbedding model;
    private final Map<String, Path> folders = new LinkedHashMap<>();
    private final Map<String, List<Sample>> samples = new LinkedHashMap<>();

    public SpellVoices(Path dir, SpeechEmbedding model) {
        this.dir = dir;
        this.model = model;
    }

    /** The store in the user's Logos folder with the bundled model; not yet {@link #load loaded}. */
    public static SpellVoices open() throws IOException {
        return new SpellVoices(SageSettings.dir.resolve("voice"), SpeechEmbedding.get());
    }

    /** Reads every saved recording. A folder that cannot be read is skipped so one bad file does not lose the rest. */
    public synchronized void load() throws IOException {
        folders.clear();
        samples.clear();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> list = Files.list(dir)) {
            for (Path folder : list.filter(Files::isDirectory).sorted().toList()) {
                Path nameFile = folder.resolve("name.txt");
                if (!Files.isRegularFile(nameFile)) continue;
                String name = Files.readString(nameFile, StandardCharsets.UTF_8);
                List<Sample> own = new ArrayList<>();
                try (Stream<Path> wavs = Files.list(folder)) {
                    for (Path f : wavs.filter(p -> p.toString().endsWith(".wav")).sorted().toList())
                        own.add(new Sample(f, Utterance.of(model, readWav(f))));
                } catch (IOException e) {
                    dev.logos.LogosMod.LOGGER.debug("Skipping unreadable voice recordings in {}", folder.getFileName(), e);
                    continue;
                }
                folders.put(name, folder);
                samples.put(name, own);
            }
        }
    }

    public synchronized Set<String> names() {
        return Set.copyOf(samples.keySet());
    }

    public synchronized int count(String name) {
        List<Sample> own = samples.get(name);
        return own == null ? 0 : own.size();
    }

    /** Why an enrollment is worth a second look. */
    public enum Problem {
        NONE,
        /** Not saved: no speech, or too little to be a spell name. */
        TOO_QUIET,
        TOO_SHORT,
        /** Saved, but it does not sound like this name's other recordings; usually a bad take worth redoing. */
        INCONSISTENT,
        /** Saved, but it sounds like another spell's name, so the two may be mistaken for each other. */
        CONFUSABLE
    }

    /**
     * What one more recording of a name came to: how many the name now has, the farthest of its
     * other recordings ({@code NaN} for the first), and the closest other spell.
     */
    public record Enrollment(Problem problem, int count, double spread, @Nullable String nearest, double nearestDistance) {
        public boolean saved() {
            return problem != Problem.TOO_QUIET && problem != Problem.TOO_SHORT;
        }
    }

    /** Adds a recording of the player saying {@code name}, unless it holds no usable speech. */
    public Enrollment enroll(String name, float[] raw) throws IOException {
        if (Utterance.peak(raw) < Utterance.QUIET) return new Enrollment(Problem.TOO_QUIET, count(name), Double.NaN, null, Double.NaN);
        Utterance u = Utterance.of(model, raw);
        if (u.seconds() < MIN_SECONDS) return new Enrollment(Problem.TOO_SHORT, count(name), Double.NaN, null, Double.NaN);
        synchronized (this) {
            double spread = Double.NaN;
            for (Sample s : samples.getOrDefault(name, List.of())) {
                double d = u.distance(s.utterance);
                spread = Double.isNaN(spread) ? d : Math.max(spread, d);
            }
            Ranked nearest = closestOther(name, u);
            double nearestDistance = nearest == null ? Double.NaN : nearest.distance();
            int count = save(name, raw, u);
            Problem problem = spread > ACCEPT ? Problem.INCONSISTENT : nearestDistance < ACCEPT ? Problem.CONFUSABLE : Problem.NONE;
            return new Enrollment(problem, count, spread, nearest == null ? null : nearest.name(), nearestDistance);
        }
    }

    /** {@code raw} trimmed and embedded, ready to compare; null when it holds no speech. */
    public @Nullable Utterance hear(float[] raw) {
        if (Utterance.peak(raw) < Utterance.QUIET) return null;
        Utterance u = Utterance.of(model, raw);
        return u.seconds() < MIN_SECONDS ? null : u;
    }

    /** A recorded name and how far it is from something said. */
    public record Ranked(String name, double distance) {}

    /** The recorded name other than {@code name} that {@code u} sounds most like, or null when there is none. */
    public synchronized @Nullable Ranked closestOther(String name, Utterance u) {
        Ranked best = null;
        for (var e : samples.entrySet()) {
            if (e.getKey().equals(name)) continue;
            for (Sample s : e.getValue()) {
                double d = u.distance(s.utterance);
                if (best == null || d < best.distance()) best = new Ranked(e.getKey(), d);
            }
        }
        return best;
    }

    /** Swaps every recording of {@code name} for {@code takes}, as one step: a redone set replaces the old one whole. */
    public void replace(String name, List<float[]> takes) throws IOException {
        List<Utterance> heard = new ArrayList<>();
        for (float[] raw : takes) heard.add(Utterance.of(model, raw));
        synchronized (this) {
            forget(name);
            for (int i = 0; i < takes.size(); i++) save(name, takes.get(i), heard.get(i));
        }
    }

    private int save(String name, float[] raw, Utterance u) throws IOException {
        Path folder = folders.get(name);
        if (folder == null) {
            folder = dir.resolve(folderName(name));
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("name.txt"), name, StandardCharsets.UTF_8);
            folders.put(name, folder);
        }
        Path file = folder.resolve(System.currentTimeMillis() + ".wav");
        for (int i = 1; Files.exists(file); i++) file = folder.resolve(System.currentTimeMillis() + "-" + i + ".wav");
        writeWav(file, raw);
        List<Sample> own = samples.computeIfAbsent(name, k -> new ArrayList<>());
        own.add(new Sample(file, u));
        return own.size();
    }

    /** Deletes the newest recording of {@code name}, for redoing a bad take. */
    public synchronized boolean removeLast(String name) throws IOException {
        List<Sample> own = samples.get(name);
        if (own == null || own.isEmpty()) return false;
        Files.deleteIfExists(own.removeLast().file);
        return true;
    }

    /** Deletes every recording of {@code name}. */
    public synchronized void forget(String name) throws IOException {
        samples.remove(name);
        Path folder = folders.remove(name);
        if (folder == null) return;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(folder);
    }

    /**
     * The closest recorded name to what the player said, and the runner-up. {@code candidates}
     * narrows the search (for example to the spells on the held wand); null searches every name.
     * Null when no candidate has a recording, or the audio holds no speech.
     */
    public @Nullable Recognition recognize(float[] raw, @Nullable Collection<String> candidates) {
        if (Utterance.peak(raw) < Utterance.QUIET) return null;
        Utterance u = Utterance.of(model, raw);
        Map<String, Double> near = new LinkedHashMap<>();
        synchronized (this) {
            for (var e : samples.entrySet()) {
                double d = Double.POSITIVE_INFINITY;
                for (Sample s : e.getValue()) d = Math.min(d, u.distance(s.utterance));
                near.put(e.getKey(), d);
            }
        }
        String best = null;
        for (var e : near.entrySet())
            if ((candidates == null || candidates.contains(e.getKey())) && (best == null || e.getValue() < near.get(best))) best = e.getKey();
        if (best == null || near.get(best) == Double.POSITIVE_INFINITY) return null;
        // The runner-up is any other recorded name, on the wand or not: a word that sounds more like a
        // spell left at home than like the one on the wand is not taken for the one on the wand.
        String second = null;
        for (var e : near.entrySet())
            if (!e.getKey().equals(best) && (second == null || e.getValue() < near.get(second))) second = e.getKey();
        return new Recognition(best, near.get(best), second, second == null ? Double.POSITIVE_INFINITY : near.get(second));
    }

    /**
     * Whether something {@code distance} from a name, with the closest other recorded name at
     * {@code runnerUp} (infinite when there is none), is taken as that name: close, and no other name
     * closer; or a little further but clearly ahead of every other (which needs another to compare with).
     */
    public static boolean accepts(double distance, double runnerUp) {
        return distance < ACCEPT && distance <= runnerUp
                || distance < LOOSE && runnerUp != Double.POSITIVE_INFINITY && runnerUp - distance >= LEAD;
    }

    /** The best match among the candidates, and the closest other recorded name (null when there is none). */
    public record Recognition(String name, double distance, @Nullable String runnerUp, double runnerUpDistance) {
        public boolean accepted() {
            return accepts(distance, runnerUpDistance);
        }
    }

    /** A folder name that is safe on every file system and still readable; the hash keeps names that clean up alike apart. */
    static String folderName(String name) {
        StringBuilder sb = new StringBuilder();
        name.codePoints().limit(32).forEach(c -> sb.appendCodePoint(Character.isLetterOrDigit(c) ? c : '_'));
        return sb + "-" + Integer.toHexString(name.hashCode());
    }

    static float[] readWav(Path file) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 12) throw new IOException("not a WAV file: " + file);
        b.position(12);
        while (b.remaining() >= 8) {
            byte[] id = new byte[4];
            b.get(id);
            int size = b.getInt();
            if (size < 0 || size > b.remaining()) break;
            if (new String(id, StandardCharsets.US_ASCII).equals("data")) {
                float[] s = new float[size / 2];
                for (int i = 0; i < s.length; i++) s[i] = b.getShort() / 32768f;
                return s;
            }
            b.position(b.position() + size + (size & 1));
        }
        throw new IOException("no audio in " + file);
    }

    /** 16-bit mono PCM at {@link SpeechEmbedding#SAMPLE_RATE}. */
    static void writeWav(Path file, float[] x) throws IOException {
        int rate = SpeechEmbedding.SAMPLE_RATE;
        ByteBuffer b = ByteBuffer.allocate(44 + x.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + x.length * 2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(x.length * 2);
        for (float v : x) b.putShort((short) Math.round(Math.max(-1, Math.min(1, v)) * 32767));
        Files.write(file, b.array());
    }
}
