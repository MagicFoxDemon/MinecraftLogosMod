package dev.logos.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.logos.runtime.Ctx;

/**
 * A rune is one word of the Logos language. It only declares its typed input slots, the events it
 * can emit and how it runs; every spell is a typed composition of runes.
 */
public final class Rune {
    @FunctionalInterface
    public interface Exec {
        Object run(Node node, Ctx ctx);
    }

    /** Positional slot. {@code defaultSrc == null} means required. */
    public record Param(String name, Type type, String defaultSrc, boolean broadcast, boolean rest) {}

    /**
     * Keyword slot. {@code defaultSrc == null} means absent unless written. Flags take no value. A choice takes
     * one of {@code choices}, written bare (:mode up); the first is its default.
     */
    public record Kw(String name, Type type, String defaultSrc, boolean flag, List<String> choices) {
        public boolean choice() {
            return !choices.isEmpty();
        }
    }

    /** An event this rune emits; its handler is written as {@code (on-xxx stmt...)}. */
    public record EventSpec(String name, boolean bindsTarget, String doc) {}

    public final String name;
    public final Category category;
    public final Type returns;
    public final List<Param> params = new ArrayList<>();
    public final Map<String, Kw> keywords = new LinkedHashMap<>();
    public final Map<String, EventSpec> events = new LinkedHashMap<>();
    public boolean body;
    public boolean bodyBindsTarget;
    public double stability;
    public String doc = "";
    public Exec exec;

    public Rune(String name, Category category, Type returns) {
        this.name = name;
        this.category = category;
        this.returns = returns;
    }

    public Rune p(String name, Type type) {
        params.add(new Param(name, type, null, false, false));
        return this;
    }

    /** Entity slot that also accepts an entity set, applying the rune to each member. */
    public Rune each(String name) {
        params.add(new Param(name, Type.ENTITY, null, true, false));
        return this;
    }

    /** Like {@link #each}, but may be left out: then {@code def} (source) fills it. */
    public Rune eachOr(String name, String def) {
        params.add(new Param(name, Type.ENTITY, def, true, false));
        return this;
    }

    /** Position slot that also accepts a block set, applying the rune to each block. */
    public Rune eachBlock(String name) {
        params.add(new Param(name, Type.POS, null, true, false));
        return this;
    }

    public Rune opt(String name, Type type, String def) {
        params.add(new Param(name, type, def, false, false));
        return this;
    }

    public Rune rest(String name, Type type) {
        params.add(new Param(name, type, null, false, true));
        return this;
    }

    public Rune kw(String name, Type type, String def) {
        keywords.put(name, new Kw(name, type, def, false, List.of()));
        return this;
    }

    public Rune flag(String name) {
        keywords.put(name, new Kw(name, Type.BOOL, null, true, List.of()));
        return this;
    }

    /** A keyword picked from a fixed list of words, shown as a dropdown on the node; the first word is the default. */
    public Rune choice(String name, String... words) {
        keywords.put(name, new Kw(name, Type.TEXT, words[0], false, List.of(words)));
        return this;
    }

    public Rune on(String event, boolean bindsTarget, String doc) {
        events.put(event, new EventSpec(event, bindsTarget, doc));
        return this;
    }

    public Rune body(boolean bindsTarget) {
        this.body = true;
        this.bodyBindsTarget = bindsTarget;
        return this;
    }

    public Rune stab(double s) {
        this.stability = s;
        return this;
    }

    public Rune doc(String doc) {
        this.doc = doc;
        return this;
    }

    public Rune exec(Exec exec) {
        this.exec = exec;
        return this;
    }

    public boolean hasRequiredParams() {
        return params.stream().anyMatch(p -> p.defaultSrc() == null);
    }

    /** Human-readable signature, e.g. {@code (damage ○target □amount [✦element]) → ▶行动}. */
    public String signature() {
        StringBuilder sb = new StringBuilder("(").append(name);
        for (Param p : params) {
            sb.append(' ');
            String t = (p.broadcast() ? (p.type() == Type.POS ? "◇/▦" : "○/◎") : p.type().glyph) + p.name() + (p.rest() ? "…" : "");
            sb.append(p.defaultSrc() == null ? t : "[" + t + "]");
        }
        for (Kw k : keywords.values()) {
            sb.append(" [:").append(k.name()).append(k.flag() ? "" : k.choice() ? " " + String.join("|", k.choices()) : " " + k.type().glyph).append(']');
        }
        for (EventSpec e : events.values()) sb.append(" (").append(e.name()).append(" …)");
        if (body) sb.append(" 行动…");
        return sb.append(") → ").append(returns.label()).toString();
    }
}
