package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Number and proportion as Leonardo's circle knew them, one small picture per rune: Pacioli's
 * p̄ and m̄ for "più" and "meno", the gelosia lattice for multiplying, a merchant's balance for
 * comparing, the scholastics' et / vel / non for joining propositions, dividers for measuring,
 * a tally gate for counting, a die for chance, the spiral for a figure nested in itself, and the
 * anatomist's heart for a creature's life. {@code #} is ink, {@code +} the accent.
 */
final class MathEmblems {
    private static final Map<String, String[]> BY_WORD = new HashMap<>();

    private MathEmblems() {}

    /** The rune's own picture, or null when it has none and takes its family's. */
    static String @Nullable [] of(String word) {
        return BY_WORD.get(word);
    }

    private static void put(String[] picture, String... words) {
        for (String word : words) BY_WORD.put(word, picture);
    }

    private static String[] mirrored(String[] rows) {
        String[] out = new String[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = new StringBuilder(rows[i]).reverse().toString();
        return out;
    }

    private static String[] withFoot(String[] rows, String foot) {
        String[] out = rows.clone();
        out[out.length - 1] = foot;
        return out;
    }

    static {
        // p̄: più.
        put(new String[]{
                ".#######.",
                ".........",
                ".#####...",
                ".#....#..",
                ".#....#..",
                ".#####...",
                ".#.......",
                ".#.......",
                ".#......."}, "+");
        // m̄: meno.
        put(new String[]{
                "#########",
                ".........",
                ".........",
                ".###.###.",
                ".#..#..#.",
                ".#..#..#.",
                ".#..#..#.",
                ".#..#..#.",
                "........."}, "-");
        // Gelosia: the lattice merchants multiplied in.
        put(new String[]{
                "#########",
                "#..##..##",
                "#.#.#.#.#",
                "##..##..#",
                "#########",
                "#..##..##",
                "#.#.#.#.#",
                "##..##..#",
                "#########"}, "*");
        // Partito: one part over another.
        put(new String[]{
                "....#....",
                "...#+#...",
                "....#....",
                ".........",
                "#########",
                ".........",
                "....#....",
                "...#+#...",
                "....#...."}, "/");
        // The least and the most, marked against a rule.
        String[] least = {
                "....#....",
                "....#....",
                "....#....",
                "..#.#.#..",
                "...###...",
                "....#....",
                ".........",
                "#########",
                "........."};
        put(least, "min");
        String[] most = new String[least.length];
        for (int i = 0; i < least.length; i++) most[i] = least[least.length - 1 - i];
        put(most, "max");
        // A balance: the heavier side hangs lower.
        String[] tilted = {
                "....#....",
                "###.#....",
                "#..###...",
                "#...#.###",
                "###.#...#",
                "....#...#",
                "....#.###",
                "..#####..",
                "........."};
        put(tilted, "<");
        put(mirrored(tilted), ">");
        put(withFoot(tilted, "#.#.#.#.#"), "<=");
        put(withFoot(mirrored(tilted), "#.#.#.#.#"), ">=");
        put(new String[]{
                "....#....",
                "#########",
                "#...#...#",
                "#...#...#",
                "###.#.###",
                "....#....",
                "....#....",
                "..#####..",
                "........."}, "=");
        // Et, vel, non.
        put(new String[]{
                "..##.....",
                ".#..#....",
                ".#..#....",
                "..##.....",
                ".#.#..#..",
                "#...#.#..",
                "#....#...",
                ".####.#..",
                "........."}, "and");
        put(new String[]{
                "#.......#",
                "#.......#",
                ".#.....#.",
                ".#.....#.",
                "..#...#..",
                "..#...#..",
                "...#.#...",
                "....#....",
                "........."}, "or");
        put(new String[]{
                "..#####.#",
                ".#.....#.",
                "#.....#.#",
                "#....#..#",
                "#...#...#",
                "#..#....#",
                "#.#.....#",
                ".#.....#.",
                "#.#####.."}, "not");
        // Alea: Fortune's die.
        put(new String[]{
                "#########",
                "#.......#",
                "#.+...+.#",
                "#.......#",
                "#...+...#",
                "#.......#",
                "#.+...+.#",
                "#.......#",
                "#########"}, "random");
        // A stepped rule: a fraction settling onto an integer mark.
        put(new String[]{
                ".......#.",
                ".....###.",
                ".....#...",
                "...###...",
                "...#.....",
                ".###.....",
                ".#.......",
                ".........",
                "+.+.+.+.+"}, "round");
        // A measured span held between two boundary pillars.
        put(new String[]{
                ".##...##.",
                ".#.....#.",
                ".#.....#.",
                ".#..+..#.",
                ".#+++++#.",
                ".#..+..#.",
                ".#.....#.",
                ".#.....#.",
                ".##...##."}, "clamp");
        // Two extremes and the proportion held between them.
        put(new String[]{
                ".........",
                "....#....",
                "...#+#...",
                "....#....",
                "+#######+",
                "....#....",
                "...#+#...",
                "....#....",
                "........."}, "lerp");
        // A point travelling between two compass marks.
        put(new String[]{
                ".......+.",
                "......+#+",
                ".....#.+.",
                "....#....",
                "...#+#...",
                "....#....",
                ".+.#.....",
                "+#+......",
                ".+......."}, "lerp-pos");
        // Dividers pricking two points.
        put(new String[]{
                "....#....",
                "...#.#...",
                "...#.#...",
                "..#...#..",
                "..#...#..",
                ".#.....#.",
                "#.......#",
                "+.......+",
                "........."}, "distance");
        // A tally gate: four strokes and the fifth across.
        put(new String[]{
                ".........",
                "#.#.#.#..",
                "#.#.#.#..",
                "#.#.#.#..",
                "#########",
                "#.#.#.#..",
                "#.#.#.#..",
                "#.#.#.#..",
                "........."}, "count", "block-count");
        // Layers laid one on another.
        put(new String[]{
                ".........",
                "...###...",
                ".........",
                "..#####..",
                ".........",
                ".#######.",
                ".........",
                "#########",
                "........."}, "stacks");
        // The spiral: a figure that holds a smaller copy of itself.
        put(new String[]{
                ".#######.",
                "#.......#",
                "#.#####.#",
                "#.#...#.#",
                "#.#.#.#.#",
                "#.#..##.#",
                "#.#.....#",
                "#..######",
                "........."}, "depth");
        // The glass running down.
        put(new String[]{
                "#########",
                ".#.....#.",
                "..#+++#..",
                "...#+#...",
                "....#....",
                "...#.#...",
                "..#.+.#..",
                ".#+++++#.",
                "#########"}, "mana-left");
        // The anatomist's heart.
        String[] heart = {
                ".##...##.",
                "#++#.#++#",
                "#+++#+++#",
                "#+++++++#",
                ".#+++++#.",
                "..#+++#..",
                "...#+#...",
                "....#....",
                "........."};
        put(heart, "hp", "max-hp", "missing-hp", "alive");
        // Half the heart filled: life as a proportion of itself.
        put(new String[]{
                ".##...##.",
                "#..#.#..#",
                "#...#...#",
                "#+++++++#",
                ".#+++++#.",
                "..#+++#..",
                "...#+#...",
                "....#....",
                "........."}, "hp-ratio");
        // The observing eye, for questions put to the world.
        put(new String[]{
                ".........",
                "..#####..",
                ".#.....#.",
                "#..+++..#",
                "#.++#++.#",
                "#..+++..#",
                ".#.....#.",
                "..#####..",
                "........."}, "has", "is", "block-is", "is-air");
    }
}
