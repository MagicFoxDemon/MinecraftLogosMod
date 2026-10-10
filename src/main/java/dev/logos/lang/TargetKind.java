package dev.logos.lang;

/**
 * What sort of thing a ⊙ filter admits, chosen with {@code (kind …)}. Only {@link #ALL} makes
 * sense for blocks; the rest describe entities. Kinds never widen what a spell may touch: other
 * players still need {@code :players true}.
 */
public enum TargetKind {
    ALL("all", "全部", true),
    ENEMIES("enemies", "敌人", false),
    /** Monsters by the game's own mark (zombies, skeletons, creepers…), not animals or villagers; still only enemies. */
    HOSTILE("hostile", "敌对生物", false),
    PLAYERS("players", "其他玩家", false),
    LIVING("living", "生物", false),
    ITEMS("items", "掉落物", false),
    PROJECTILES("projectiles", "弹射物", false),
    NOT_CASTER("not-caster", "施法者以外", false),
    /** Nothing at all: a construct that stops nobody, only notices what passes (on-pass). */
    NONE("none", "无", true);

    /** How it is written in source, e.g. {@code (kind not-caster)}. */
    public final String word;
    public final String zh;
    /** Whether it can filter blocks too. */
    public final boolean forBlocks;

    TargetKind(String word, String zh, boolean forBlocks) {
        this.word = word;
        this.zh = zh;
        this.forBlocks = forBlocks;
    }

    public static TargetKind parse(String s) {
        for (TargetKind k : values()) if (k.word.equals(s)) return k;
        return null;
    }

    public static String words() {
        StringBuilder sb = new StringBuilder();
        for (TargetKind k : values()) sb.append(sb.isEmpty() ? "" : " / ").append(k.word);
        return sb.toString();
    }
}
