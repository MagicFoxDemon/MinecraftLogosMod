package dev.logos.lang;

/**
 * Slot types of the Logos language. Each has a "shape" glyph so mismatches read like
 * runes that refuse to snap together.
 */
public enum Type {
    ENTITY("○", "实体"),
    ENTITIES("◎", "实体群"),
    POS("◇", "位置"),
    /** A set of block positions; a single position is a set of one. */
    BLOCKS("▦", "方块群"),
    DIMENSION("◐", "维度"),
    DIR("➚", "方向"),
    NUM("□", "数"),
    BOOL("△", "命题"),
    ELEMENT("✦", "元素"),
    STATUS("※", "状态"),
    MOTION("∿", "运动"),
    /** A {@link ConstructShape}: box, wall, plane, disk, dome. */
    SHAPE("⬡", "形状"),
    /** A {@link Weather}: clear, rain, thunder, snow. */
    WEATHER("☁", "天气"),
    /** A registered item id: used as a form's appearance, or as equip's inventory-backed material. */
    LOOK("❖", "外观"),
    /** A registered particle type, by name. Purely cosmetic, never changes behaviour. */
    PARTICLE("✧", "粒子"),
    /** Which entities or blocks are admitted: an id/#tag string, a {@code (kind …)}, or a combination. */
    FILTER("⊙", "筛选"),
    /** A {@link TargetKind} word; only written inside {@code (kind …)}. */
    KIND("◈", "种类"),
    /** A named anchor, {@code ^name}: the caster's saved place. Fits any position slot. */
    ANCHOR("⚓", "锚点"),
    /** A creature type by registry id; only written inside {@code (summon …)}. */
    CREATURE("❦", "生物"),
    /** A mob effect (potion effect) by registry id; vanilla and modded alike. */
    MOB_EFFECT("⚗", "药效"),
    TEXT("¶", "文字"),
    ACTION("▶", "行动"),
    ANY("✱", "任意");

    public final String glyph;
    public final String zh;

    Type(String glyph, String zh) {
        this.glyph = glyph;
        this.zh = zh;
    }

    public String label() {
        return glyph + zh;
    }

    /** Whether a value of type {@code actual} may be plugged into a slot of this type. */
    public boolean accepts(Type actual, boolean broadcast) {
        if (this == actual) return true;
        if (this == ANY) return actual != ACTION;
        if (this == ENTITIES && actual == ENTITY) return true;
        if (this == BLOCKS && actual == POS) return true;
        // A plain "id" or "#tag" string is the simplest filter.
        if (this == FILTER && actual == TEXT) return true;
        // ^name is read as the place it names.
        if ((this == POS || this == BLOCKS) && actual == ANCHOR) return true;
        if (this == POS && broadcast && actual == BLOCKS) return true;
        return this == ENTITY && broadcast && actual == ENTITIES;
    }

    /** Label for a broadcast slot of this type (one, or many of it). */
    public String broadcastLabel() {
        return this == POS ? "◇位置 或 ▦方块群" : "○实体 或 ◎实体群";
    }
}
