package dev.logos.runtime;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The evaluation context: values every event provides (caster, here, aim, target) plus
 * let-bound variables. {@code level} is the world {@code here} lies in. Immutable; handlers derive
 * new contexts.
 */
public record Ctx(SpellInstance inst, ServerLevel level, Vec3 here, Vec3 aim, @Nullable Entity target, Map<String, Object> vars,
                  double index, int depth) {
    public static final Vec3 UP = new Vec3(0, 1, 0);
    public static final Vec3 DOWN = new Vec3(0, -1, 0);

    public Object lookup(String name) {
        return switch (name) {
            case "caster" -> inst.caster;
            case "target" -> target;
            case "here" -> new Place(level, here);
            case "aim" -> aim;
            case "up" -> UP;
            case "down" -> DOWN;
            case "i" -> index;
            case "self" -> vars.get("self");
            default -> null;
        };
    }

    public Place place() {
        return new Place(level, here);
    }

    public Ctx at(Vec3 here) {
        return new Ctx(inst, level, here, aim, target, vars, index, depth);
    }

    /** Moves {@code here}, possibly into another dimension. */
    public Ctx at(Place place) {
        return new Ctx(inst, place.level(), place.pos(), aim, target, vars, index, depth);
    }

    public Ctx aiming(Vec3 aim) {
        return new Ctx(inst, level, here, aim, target, vars, index, depth);
    }

    /** Binds a new target; {@code here} moves to it (and into its world). */
    public Ctx on(Entity target, Vec3 here) {
        ServerLevel l = target.level() instanceof ServerLevel sl ? sl : level;
        return new Ctx(inst, l, here, aim, target, vars, index, depth);
    }

    public Ctx withVar(String name, Object value) {
        Map<String, Object> v = new HashMap<>(vars);
        v.put(name, value);
        return new Ctx(inst, level, here, aim, target, v, index, depth);
    }

    public Ctx withIndex(double i) {
        return new Ctx(inst, level, here, aim, target, vars, i, depth);
    }

    public Ctx deeper() {
        return new Ctx(inst, level, here, aim, target, vars, index, depth + 1);
    }
}
