package dev.logos.runtime;

import java.util.HashMap;
import java.util.Map;

import dev.logos.LogosMod;
import dev.logos.lang.Status;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Per-entity Logos statuses (transient attachment). Marks are how separate spells communicate:
 * one spell writes {@code #blood}, another proves {@code (>= (stacks target #blood) 3)}.
 */
public final class StatusData {
    public final Map<String, Integer> marks = new HashMap<>();
    public long frozenUntil;

    public static StatusData of(Entity e) {
        return e.getData(LogosMod.STATUS_DATA);
    }

    public static boolean has(Entity e, Status s) {
        if (e == null) return false;
        if (s.equals(Status.BURNING)) return e.isOnFire();
        if (s.equals(Status.FROZEN)) return of(e).frozenUntil > e.level().getGameTime();
        return of(e).marks.getOrDefault(s.name(), 0) > 0;
    }

    public static double stacks(Entity e, Status s) {
        if (e == null) return 0;
        if (s.equals(Status.BURNING)) return Math.ceil(e.getRemainingFireTicks() / 20.0);
        if (s.equals(Status.FROZEN)) return Math.max(0, Math.ceil((of(e).frozenUntil - e.level().getGameTime()) / 20.0));
        return of(e).marks.getOrDefault(s.name(), 0);
    }

    public static void mark(Entity e, Status s, int n) {
        of(e).marks.merge(s.name(), n, Integer::sum);
    }

    public static void unmark(Entity e, Status s) {
        of(e).marks.remove(s.name());
    }

    public static void freeze(LivingEntity e, double seconds) {
        int ticks = (int) Math.round(seconds * 20);
        StatusData d = of(e);
        d.frozenUntil = Math.max(d.frozenUntil, e.level().getGameTime() + ticks);
        e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 5, false, false));
        e.setTicksFrozen(Math.max(e.getTicksFrozen(), e.getTicksRequiredToFreeze() + ticks));
    }
}
