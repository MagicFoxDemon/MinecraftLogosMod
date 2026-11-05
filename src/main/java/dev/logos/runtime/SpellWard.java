package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Element;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Something that stops spell bodies (projectiles, beams, veins, a colossus's cannon) the way a construct does:
 * a construct itself ({@link ConstructObj}), or an add-on's ward, such as a weapon that guards against spells.
 * Before every step a body asks {@link Constructs#stopSpell}, which asks each construct and each ward near its
 * path; only the nearest one that would stop it pays.
 * <p>Add-ons hand their wards over with {@link Constructs#addWards}, once, at mod construction.
 */
public interface SpellWard {
    /**
     * Where it would stop a body crossing {@code from → to}, or null. {@code inst} is the body's spell (null for a
     * body of no spell, such as a colossus's cannon), {@code damage} what the body's {@code on-hit} would deal
     * ({@link Constructs#hitDamage}).
     */
    @Nullable Vec3 wouldStop(@Nullable SpellInstance inst, Vec3 from, Vec3 to, Element element, double damage);

    /**
     * Stops the body at {@code at}: pays {@code cost} and takes {@code damage} (0 while a beam holds against it
     * between pulses). False if it could not pay, and the body goes on.
     */
    boolean stop(Vec3 at, double cost, double damage);

    /** The wards an add-on has near {@code area}. */
    @FunctionalInterface
    interface Source {
        Iterable<? extends SpellWard> near(ServerLevel level, AABB area);
    }
}
