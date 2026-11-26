package dev.logos.lang;

import static dev.logos.runtime.Interp.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.logos.LogosMod;
import dev.logos.runtime.Ctx;
import dev.logos.runtime.Targeting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The rune lexicon. Deliberately orthogonal: there is no "fireball" or "chain lightning" here,
 * only forms, motions, selectors, effects, logic and values that players compose.
 *
 * <p>This class holds the rune table and the helpers the families share. The runes themselves are defined family by
 * family in {@link FormRunes}, {@link BindingRunes}, {@link EffectRunes} (with {@link BlockEffectRunes}),
 * {@link LogicRunes} and {@link ValueRunes}, in that order, which is the order {@link #all} lists them in.
 */
public final class Runes {
    private static final Map<String, Rune> RUNES = new LinkedHashMap<>();
    /** What one particle of {@code phantasm} costs: a little decoration stays cheap, a stream of them does not. */
    public static final double PHANTASM_PARTICLE = 0.02;

    private Runes() {}

    public static Rune get(String name) {
        return RUNES.get(name);
    }

    public static Collection<Rune> all() {
        return RUNES.values();
    }

    static Rune def(String name, Category c, Type ret) {
        Rune r = new Rune(name, c, ret);
        RUNES.put(name, r);
        return r;
    }

    /** What a hosted form adds to a spell's complexity: less than a form that moves on its own. */
    public static final double HOSTED_STABILITY = 2;

    /**
     * Registers a hosted form (寄托形体) for another mod: a 🜔 form that is not cast but carried by something that mod
     * makes, such as a round from a gun or an arrow from a bow. A spell is written around it as
     * {@code (spell … (rifle-round (on-hit …) (on-impact …)))}; the mod keeps the spell with the thing and, when the
     * thing hits, sets off the events itself ({@code runtime.HostedSpells}). It takes no arguments and is not a word
     * a player learns (any player may write it, and books never teach it). Cast on its own, from a wand, it only ends
     * the spell. Call this from the mod's constructor, before any spell is compiled; the editor node and the names
     * ({@code logos.rune.<name>}, the events' {@code logos.event.*}) are the mod's to add.
     *
     * @param name   the word, lower case with hyphens, not already a rune
     * @param doc    what it is and when each event goes off, as the rune files write theirs
     * @param events its events: each {@code on-…}, whether it binds a target, and when it goes off
     */
    public static synchronized Rune hosted(String name, String doc, Rune.EventSpec... events) {
        if (!name.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("hosted form name '" + name + "' must be lower case with hyphens");
        if (RUNES.containsKey(name)) throw new IllegalArgumentException("'" + name + "' is already a rune");
        if (events.length == 0) throw new IllegalArgumentException("hosted form '" + name + "' has no events");
        for (Rune.EventSpec e : events) {
            if (!e.name().matches("on-[a-z][a-z0-9-]*")) throw new IllegalArgumentException("event '" + e.name() + "' must be on- and a lower-case word");
        }
        Rune r = def(name, Category.FORM, Type.ACTION).stab(HOSTED_STABILITY).doc(doc);
        for (Rune.EventSpec e : events) r.on(e.name(), e.bindsTarget(), e.doc());
        r.hosted = true;
        // Cast by hand there is nothing to carry it: the spell ends, and the caster is told why.
        r.exec((n, c) -> {
            c.inst().terminate(net.minecraft.network.chat.Component.translatable("logos.spell.end.hosted",
                    net.minecraft.network.chat.Component.translatable("logos.rune." + name)));
            return null;
        });
        return r;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Living targets the spell may touch (other players only if the spell allows players). */
    static List<LivingEntity> living(Node n, Ctx c) {
        return living(entities(n, c), c);
    }

    static List<LivingEntity> living(List<Entity> es, Ctx c) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : es) {
            if (e instanceof LivingEntity le && le.isAlive() && Targeting.mayAffect(c.inst(), e)) out.add(le);
        }
        return out;
    }

    /** Any targets the spell may touch, including non-living ones such as dropped items. */
    static List<Entity> anyEntities(Node n, Ctx c) {
        return anyEntities(entities(n, c), c);
    }

    static List<Entity> anyEntities(List<Entity> es, Ctx c) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : es) if (e.isAlive() && Targeting.mayAffect(c.inst(), e)) out.add(e);
        return out;
    }

    static ServerLevel lv(Entity e) {
        return (ServerLevel) e.level();
    }

    static {
        FormRunes.forms();
        BindingRunes.bindings();
        // Also registers BlockEffectRunes, part-way through, where its runes sit in the table.
        EffectRunes.effects();
        LogicRunes.logic();
        ValueRunes.values();
    }

    /** Two blocks of room with something solid underfoot. */
    static boolean standable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
    }

    /** The nearest standable spot in the same column (searching up and down), so a blink never lands inside rock;
     * falls back to {@code at} when the column has none. Public so addon movement (e.g. a cat step) can reuse it. */
    public static Vec3 safeSpot(ServerLevel level, Vec3 at) {
        BlockPos start = BlockPos.containing(at.x, Math.max(level.getMinY() + 1, Math.min(level.getMaxY() - 2, at.y)), at.z);
        for (int d = 0; d < level.getHeight(); d++) {
            for (int sign : new int[] {1, -1}) {
                BlockPos p = start.offset(0, d * sign, 0);
                if (p.getY() <= level.getMinY() || p.getY() >= level.getMaxY() - 1) continue;
                if (standable(level, p)) return Vec3.atBottomCenterOf(p);
            }
        }
        return at;
    }

    /** Small plants that decay destroys outright: flowers, saplings and grasses by default; packs and addons may add their own. */
    public static final TagKey<Block> FRAGILE_PLANTS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath(LogosMod.MODID, "fragile_plants"));

    /** Rounded to the nearest whole number, halves away from zero (2.5 → 3, −2.5 → −3). */
    static double nearest(double x) {
        return Math.signum(x) * Math.floor(Math.abs(x) + 0.5);
    }

    /**
     * Which of {@code count} choices an index picks, counting from 0: wrapped round, or -1 when it names none.
     */
    static int branch(double index, int count, boolean wrap) {
        if (Double.isNaN(index)) return wrap ? 0 : -1;
        long k = (long) nearest(index);
        if (wrap) return (int) Math.floorMod(k, (long) count);
        return k < 0 || k >= count ? -1 : (int) k;
    }
}
