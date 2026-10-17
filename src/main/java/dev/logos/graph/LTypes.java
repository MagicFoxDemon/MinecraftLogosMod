package dev.logos.graph;

import com.lowdragmc.lowdraglib2.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandleHelpers;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandles;

import dev.logos.lang.ConstructShape;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.lang.Type;
import dev.logos.lang.Weather;

/**
 * Port types for the spell graph. The node graph toolkit only lets a wire connect when the input's
 * Java type is assignable from the output's, so Logos' slot rules are encoded as a class hierarchy:
 * an {@link Entity} <em>is</em> a group of one, so it fits every {@link Entities} slot, while an
 * entity set never fits a single-entity slot.
 */
public final class LTypes {
    /** ◎ A set of entities. */
    public static class Entities {}

    /** ○ One entity. */
    public static class Entity extends Entities {}

    /** ▦ A set of block positions. */
    public static class Blocks {}

    /** ◇ A position: also a block set of one, so it fits every block-set slot. */
    public static class Pos extends Blocks {}

    /** ➚ A direction. */
    public static final class Dir {}

    public static TypeHandle ENTITY;
    public static TypeHandle ENTITIES;
    public static TypeHandle POS;
    public static TypeHandle BLOCKS;
    public static TypeHandle DIR;
    public static TypeHandle NUM;
    public static TypeHandle BOOL;
    public static TypeHandle ELEMENT;
    public static TypeHandle MOTION;
    public static TypeHandle SHAPE;
    public static TypeHandle WEATHER;
    public static TypeHandle STATUS;
    public static TypeHandle FLOW;

    private static boolean initialized;

    private LTypes() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        ENTITY = handle(Entity.class, "○ 实体", 0xFFE0603F, "○");
        ENTITIES = handle(Entities.class, "◎ 实体群", 0xFFE08A3F, "◎");
        POS = handle(Pos.class, "◇ 位置", 0xFF3FA7B5, "◇");
        BLOCKS = handle(Blocks.class, "▦ 方块群", 0xFF5FB55A, "▦");
        DIR = handle(Dir.class, "➚ 方向", 0xFF6FD3E0, "➚");
        ELEMENT = handle(Element.class, "🜂 元素", 0xFFE8B84A, "🜂");
        MOTION = handle(Motion.class, "☿ 运动", 0xFF9FE0EF, "☿");
        TypeHandleHelpers.setCustomDefaultValue(ELEMENT, () -> Element.PHYSICAL);
        TypeHandleHelpers.setCustomDefaultValue(MOTION, () -> Motion.FORWARD);
        SHAPE = handle(ConstructShape.class, "⬡ 形状", 0xFFD9C27A, "⬡");
        TypeHandleHelpers.setCustomDefaultValue(SHAPE, () -> ConstructShape.WALL);
        WEATHER = handle(Weather.class, "☁ 天气", 0xFF8FB8E0, "☁");
        TypeHandleHelpers.setCustomDefaultValue(WEATHER, () -> Weather.RAIN);
        NUM = TypeHandles.FLOAT;
        BOOL = TypeHandles.BOOL;
        STATUS = TypeHandles.STRING;
        FLOW = TypeHandles.EXECUTION_FLOW;
    }

    private static TypeHandle handle(Class<?> cls, String name, int color, String glyph) {
        TypeHandle h = TypeHandleHelpers.fromType(cls, name);
        TypeHandleHelpers.setCustomColorAndIcon(h, color, new TextTexture(glyph, color));
        return h;
    }

    /** The port type for a Logos slot type; {@code broadcast} entity slots also accept entity sets. */
    public static TypeHandle of(Type t, boolean broadcast) {
        init();
        return switch (t) {
            case ENTITY -> broadcast ? ENTITIES : ENTITY;
            case ENTITIES -> ENTITIES;
            case POS -> broadcast ? BLOCKS : POS;
            case BLOCKS -> BLOCKS;
            case DIR -> DIR;
            case BOOL -> BOOL;
            case ELEMENT -> ELEMENT;
            case MOTION -> MOTION;
            case SHAPE -> SHAPE;
            case WEATHER -> WEATHER;
            case STATUS, TEXT, DIMENSION, LOOK, PARTICLE, FILTER, KIND, ANCHOR, CREATURE, MOB_EFFECT -> STATUS;
            case ACTION -> FLOW;
            case NUM, ANY -> NUM;
        };
    }
}
