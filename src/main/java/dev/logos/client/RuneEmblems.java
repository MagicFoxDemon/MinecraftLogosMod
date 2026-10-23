package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The small marginal drawing beside a rune's name, for the families other than number: what a
 * form looks like when cast, what a piece of the spell's machinery does, what an effect does to
 * the world, and what a pointing rune picks out. Runes that act alike share a drawing, so the page
 * reads by kind before it reads by name. {@code #} is ink, {@code +} the accent.
 */
final class RuneEmblems {
    private static final Map<String, String[]> BY_WORD = new HashMap<>();

    private RuneEmblems() {}

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

    private static String[] flipped(String[] rows) {
        String[] out = new String[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = rows[rows.length - 1 - i];
        return out;
    }

    static {
        forms();
        machinery();
        effects();
        pointing();
    }

    /** 🜔 The six shapes a spell can be cast in. */
    private static void forms() {
        // One bright core with a trail behind it.
        put(new String[]{
                ".........",
                ".........",
                "......##.",
                ".+.+.#++#",
                "+.+.+#++#",
                ".+.+.#++#",
                "......##.",
                ".........",
                "........."}, "projectile");
        // An emitter, a thick shaft of light, a radiant head.
        put(new String[]{
                ".........",
                ".......#.",
                "##....#.#",
                "#+++++##.",
                "#+++++++#",
                "#+++++##.",
                "##....#.#",
                ".......#.",
                "........."}, "beam");
        // Stones set into a wall.
        put(new String[]{
                ".........",
                "#########",
                "#...#...#",
                "#########",
                "#.#...#.#",
                "#########",
                "#...#...#",
                "#########",
                "........."}, "construct");
        // A dome standing on its ring, hollow inside.
        put(new String[]{
                ".........",
                "...###...",
                "..#...#..",
                ".#.....#.",
                ".#.....#.",
                "#########",
                "#+.+.+.+#",
                ".#######.",
                "........."}, "field");
        // A thin stroke from star to star.
        put(new String[]{
                ".........",
                ".........",
                ".........",
                ".#.....#.",
                "###+++###",
                ".#.....#.",
                ".........",
                ".........",
                "........."}, "ray");
        // One origin fanning out to three.
        put(new String[]{
                "....+#+..",
                "...#.....",
                "..#......",
                ".#.......",
                "########+",
                ".#.......",
                "..#......",
                "...#.....",
                "....+#+.."}, "split");
    }

    /** ♄ The spell's machinery: order, choice, repetition, time and memory. */
    private static void machinery() {
        // I, II, III: one after another.
        put(new String[]{
                "#........",
                "#........",
                ".........",
                "#.#......",
                "#.#......",
                ".........",
                "#.#.#....",
                "#.#.#....",
                "........."}, "do");
        // The way forks.
        put(new String[]{
                "....#....",
                "....#....",
                "....#....",
                "....#....",
                "...#.#...",
                "..#...#..",
                ".#.....#.",
                "+.......#",
                "........."}, "if");
        // The way forks three ways; one is taken.
        put(new String[]{
                "....#....",
                "....#....",
                "....#....",
                "....#....",
                "..#.#.#..",
                ".#..#..#.",
                "#...#...#",
                "#...+...#",
                "........."}, "switch");
        // A loop drawn round each one in turn.
        put(new String[]{
                ".#######.",
                "#.......#",
                "#.+.+.+.#",
                "#.......#",
                ".#####.#.",
                "......##.",
                ".....###.",
                ".........",
                "........."}, "each");
        put(new String[]{
                ".#######.",
                "#.......#",
                "#.##.##.#",
                "#.##.##.#",
                "#.......#",
                ".#####.#.",
                "......##.",
                ".....###.",
                "........."}, "each-block");
        // Round again.
        put(new String[]{
                "...###...",
                ".##...#..",
                "#......#.",
                "#.....###",
                "#......#.",
                "#........",
                ".#.....#.",
                "..#####..",
                "........."}, "repeat");
        // A sundial's hour lines.
        put(new String[]{
                ".........",
                ".........",
                "#.......#",
                ".#..#..#.",
                "..#.#.#..",
                "...###...",
                "#########",
                ".........",
                "........."}, "delay");
        // A knot tied to remember by.
        put(new String[]{
                "..#...#..",
                "..##.##..",
                "...###...",
                "..#+#+#..",
                "...###...",
                "..#...#..",
                ".#.....#.",
                "#.......#",
                "........."}, "let");
        // A frame within a frame.
        put(new String[]{
                "#########",
                "#.......#",
                "#.#####.#",
                "#.#...#.#",
                "#.#.#.#.#",
                "#.#...#.#",
                "#.#####.#",
                "#.......#",
                "#########"}, "recurse");
        // A barred seal: here it ends.
        put(new String[]{
                "..#####..",
                ".#.....#.",
                "#.......#",
                "#.#####.#",
                "#.#####.#",
                "#.......#",
                ".#.....#.",
                "..#####..",
                "........."}, "stop", "halt");
    }

    /** 🜍 What the spell does to the world, drawn by kind of deed. */
    private static void effects() {
        // A blade, for harm.
        put(new String[]{
                "........#",
                ".......#+",
                "......#+#",
                ".....#+#.",
                "..#.#+#..",
                "...#+#...",
                "..#.#....",
                ".#...#...",
                "#........"}, "damage", "damage-all", "drain", "execute", "disarm");
        // A breastplate receiving a piece of equipment, in the same ink as disarm's blade.
        put(new String[]{
                ".##...##.",
                "#..#.#..#",
                "#...#...#",
                ".#.....#.",
                ".#..+..#.",
                ".#.+++.#.",
                ".#..+..#.",
                ".#.....#.",
                "..#####.."}, "equip");
        // A flame.
        put(new String[]{
                "....#....",
                "...#+#...",
                "...#+#.#.",
                "..#++#+#.",
                ".#+++++#.",
                "#++#+#++#",
                "#+#...#+#",
                ".#+...+#.",
                "..#####.."}, "ignite", "ignite-all", "set-fire");
        // A crystal of frost.
        put(new String[]{
                "....#....",
                ".#..#..#.",
                "..#.#.#..",
                "...###...",
                "#########",
                "...###...",
                "..#.#.#..",
                ".#..#..#.",
                "....#...."}, "freeze", "freeze-all");
        // A clock face, for time itself.
        put(new String[]{
                "..#####..",
                ".#..#..#.",
                "#...#...#",
                "#...#...#",
                "#...###.#",
                "#.......#",
                ".#.....#.",
                "..#####..",
                "........."}, "time-stop", "time-skip");
        // The alchemist's flask.
        put(new String[]{
                "...###...",
                "....#....",
                "....#....",
                "...#.#...",
                "..#+++#..",
                ".#+++++#.",
                "#+++++++#",
                "#+++++++#",
                ".#######."}, "heal", "potion");
        // A drop of mana falling into a bowl that fills: mana given away.
        put(new String[]{
                "....#....",
                "...#+#...",
                "..#+++#..",
                "...###...",
                "....+....",
                "#...+...#",
                "#.+++++.#",
                "#+++++++#",
                ".#######."}, "infuse", "infuse-block");
        put(new String[]{
                "#########",
                "#+++#+++#",
                "#+++#+++#",
                "#########",
                "#+++#+++#",
                ".#++#++#.",
                "..#+#+#..",
                "...###...",
                "....#...."}, "shield", "stance");
        // Bodies set moving.
        String[] push = {
                ".........",
                ".........",
                "......#..",
                "......##.",
                "#.#.#####",
                "......##.",
                "......#..",
                ".........",
                "........."};
        put(push, "push", "move");
        put(mirrored(push), "pull", "gather");
        String[] lift = {
                "....#....",
                "...###...",
                "..#####..",
                "....#....",
                "....#....",
                "..#.#.#..",
                "....#....",
                "..#...#..",
                "........."};
        put(lift, "launch");
        // A doorway with sparks: here, then there.
        put(new String[]{
                "..#####..",
                ".#.....#.",
                "#..+.+..#",
                "#.......#",
                "#..+.+..#",
                "#.......#",
                "#.......#",
                "#.......#",
                "........."}, "blink");
        // A wax seal.
        put(new String[]{
                "..#####..",
                ".#+++++#.",
                "#++#+#++#",
                "#+++#+++#",
                "#++#+#++#",
                ".#+++++#.",
                "..#####..",
                "...#.#...",
                "..#...#.."}, "mark", "unmark");
        put(new String[]{
                "....#....",
                "....#....",
                "...#+#...",
                "##+++++##",
                "...#+#...",
                "....#....",
                "....#....",
                ".........",
                "........."}, "glow");
        // A figure inside a summoning ring.
        put(new String[]{
                ".........",
                "..#####..",
                ".#..#..#.",
                "#..###..#",
                "#...#...#",
                "#..#.#..#",
                ".#.....#.",
                "..#####..",
                "........."}, "summon", "dismiss", "sic");
        put(new String[]{
                "...###...",
                "...#.#...",
                "...###...",
                "....#....",
                ".#######.",
                "....#....",
                "#...#...#",
                ".#..#..#.",
                "..#####.."}, "anchor", "unanchor");
        // Two pages, one laid over the other.
        put(new String[]{
                "#####....",
                "#...#....",
                "#.#####..",
                "#.#...#..",
                "###...#..",
                "..#...#..",
                "..#####..",
                ".........",
                "........."}, "copy");
        put(new String[]{
                "#########",
                "#.......#",
                "#.##.##.#",
                "#.......#",
                ".#.....#.",
                "..#.#.#..",
                "...###...",
                ".........",
                "........."}, "phantasm");
        put(new String[]{
                ".........",
                ".....#...",
                "...#..#..",
                ".#..#..#.",
                ".#..#..#.",
                ".#..#..#.",
                "...#..#..",
                ".....#...",
                "........."}, "echo");
        put(new String[]{
                "......#..",
                ".....#...",
                "....#....",
                "...#.....",
                "..###....",
                ".#####...",
                "#########",
                ".........",
                "........."}, "toggle-state");
        // A sprout, and a skull.
        put(new String[]{
                ".....##..",
                "....#++#.",
                "##..#+#..",
                "#+#.##...",
                ".#+##....",
                "...#.....",
                "...#.....",
                "..###....",
                "#########"}, "grow");
        put(new String[]{
                "..#####..",
                ".#.....#.",
                "#.##.##.#",
                "#.##.##.#",
                "#.......#",
                ".#..#..#.",
                "..#.#.#..",
                "..#####..",
                "........."}, "decay");
        put(new String[]{
                "#########",
                "#...#...#",
                "#..#....#",
                "#...#...#",
                "#..#.#..#",
                "#.#...#.#",
                "#########",
                ".........",
                "........."}, "break-block");
        // Cloud and rain; the bolt.
        put(new String[]{
                "..###....",
                ".#...##..",
                "#......#.",
                "#.......#",
                ".#######.",
                "..+.+.+..",
                ".+.+.+...",
                ".........",
                "........."}, "weather", "world-weather");
        put(new String[]{
                "....###..",
                "...###...",
                "..###....",
                ".#######.",
                "....###..",
                "...###...",
                "..##.....",
                ".#.......",
                "........."}, "lightning");
    }

    /** ☌ What a pointing rune picks out: creatures, places, sieves and bearings. */
    private static void pointing() {
        // A sighting ring, for creatures.
        put(new String[]{
                "....#....",
                "..#####..",
                ".#..#..#.",
                "#...#...#",
                "####+####",
                "#...#...#",
                ".#..#..#.",
                "..#####..",
                "....#...."}, "nearest", "enemies", "players", "nearest-player", "entities", "items", "hit-list", "marked",
                "master", "foe", "familiar", "target");
        // A surveyor's stake, for places.
        put(new String[]{
                "....###..",
                "....#++#.",
                "....###..",
                "....#....",
                "....#....",
                "....#....",
                "..#####..",
                ".#.....#.",
                "#.......#"}, "pos-of", "above", "ahead", "mix-pos", "look-point", "look-block", "in-dimension", "here", "^");
        // The stake set off along a carpenter's square: so far forward, so far up.
        put(new String[]{
                "#....###.",
                "#....#++#",
                "#....###.",
                "#.....#..",
                "#.....#..",
                "#...#####",
                "#........",
                "#........",
                "#########"}, "offset");
        put(new String[]{
                "..####...",
                ".#..##...",
                "####.#...",
                "#..#.#...",
                "#..##....",
                "####.....",
                ".........",
                ".........",
                "........."}, "blocks");
        // A sieve, for what passes and what does not.
        put(new String[]{
                ".........",
                "#########",
                "#.+.+.+.#",
                ".#.+.+.#.",
                "..#.+.#..",
                "...#.#...",
                "....#....",
                "....#....",
                "........."}, "kind", "any-of", "all-of", "except");
        // A compass rose, for bearings.
        put(new String[]{
                "....#....",
                "...#+#...",
                "...#+#...",
                "###+#+###",
                "...#+#...",
                "...#.#...",
                "....#....",
                ".........",
                "........."}, "toward", "random-dir", "aim");
        String[] up = {
                "....#....",
                "...###...",
                "..#.#.#..",
                ".#..#..#.",
                "....#....",
                "....#....",
                "....#....",
                "....#....",
                "........."};
        put(up, "up");
        put(flipped(up), "down");
        // The caster, wand raised; the self, within its own circle.
        put(new String[]{
                "......#..",
                "..##.#...",
                "..##.#...",
                "...##....",
                "..####...",
                "...#.....",
                "..#.#....",
                ".#...#...",
                "........."}, "caster");
        put(new String[]{
                "..#####..",
                ".#..#..#.",
                "#..###..#",
                "#...#...#",
                "#..#.#..#",
                ".#.....#.",
                "..#####..",
                ".........",
                "........."}, "self");
        // i: the count of the loop.
        put(new String[]{
                "....#....",
                ".........",
                "...##....",
                "....#....",
                "....#....",
                "....#....",
                "...###...",
                ".........",
                "........."}, "i");
    }
}
