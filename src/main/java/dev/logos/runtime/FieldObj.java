package dev.logos.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.logos.lang.Node;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Salt form "field": a lingering region that pulses on-tick and notices entities entering it. Clients are
 * told it exists every few ticks ({@link Vfx#field}), when it pulses and when someone enters, and when it ends.
 */
public final class FieldObj extends SpellObject {
    public static final double UPKEEP = 0.5;

    private final double radius;
    private final int every;
    private final boolean follow;
    private final Set<UUID> inside = new HashSet<>();
    /** Whether its on-enter only heals or wards, so it may greet its own side. */
    private final boolean helps = Targeting.helpsOnHit(node);
    /** How often clients are reminded the field is there; a client that stops hearing of it lets it go. */
    private static final int HOLD_EVERY = 4;

    public FieldObj(Node node, Ctx ctx, Vec3 center, double radius, int life, int every, boolean follow) {
        super(node, ctx.withVar(Ctx.IN_FIELD, true), center, life);
        this.radius = radius;
        this.every = Math.max(1, every);
        this.follow = follow;
    }

    @Override
    public void tick() {
        if (++age > life) {
            end();
            return;
        }
        if (follow && inst.caster.level() == level) pos = inst.caster.position().add(0, 1, 0);
        if (age == 1 || age % HOLD_EVERY == 0) hold();

        if (node.events.containsKey("on-enter")) {
            // A field that only heals or wards on entry greets its own side too, its caster as well if the spell affects players.
            List<Entity> now = helps
                    ? Targeting.withCaster(ctx, Targeting.near(inst, level, pos, radius, false, e -> Targeting.mayHit(inst, e, true)),
                            new Place(level, pos), radius, e -> true)
                    : Targeting.near(inst, level, pos, radius, false, e -> Targeting.isEnemy(inst, e));
            Set<UUID> ids = new HashSet<>();
            for (Entity e : now) {
                ids.add(e.getUUID());
                if (inside.add(e.getUUID())) {
                    Vfx.fieldEnter(level, id, Targeting.center(e), node.element, e);
                    Interp.fire(node, "on-enter", ctx.on(e, Targeting.center(e)));
                    if (inst.dead) return;
                }
            }
            inside.retainAll(ids);
        }

        if (age % every == 0) {
            if (!inst.spend(UPKEEP, "领域维持")) return;
            Vfx.fieldPulse(level, id, pos, radius, node.element);
            Interp.fire(node, "on-tick", ctx.at(pos));
        }
    }

    private void hold() {
        int caster = follow && inst.caster.level() == level ? inst.caster.getId() : Vfx.NONE;
        Vfx.field(level, id, pos, radius, node.element, age, life, every, caster);
    }

    @Override
    protected void onEnded() {
        Vfx.fieldEnd(level, id, pos, node.element);
    }
}
