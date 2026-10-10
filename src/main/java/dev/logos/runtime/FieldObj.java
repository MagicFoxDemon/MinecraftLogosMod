package dev.logos.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.logos.lang.Node;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Salt form "field": a lingering region that pulses on-tick and notices entities entering it. */
public final class FieldObj extends SpellObject {
    public static final double UPKEEP = 0.5;

    private final double radius;
    private final int every;
    private final boolean follow;
    private final Set<UUID> inside = new HashSet<>();

    public FieldObj(Node node, Ctx ctx, Vec3 center, double radius, int life, int every, boolean follow) {
        super(node, ctx, center, life);
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

        if (node.events.containsKey("on-enter")) {
            List<LivingEntity> now = Targeting.enemiesNear(inst, level, pos, radius, false);
            Set<UUID> ids = new HashSet<>();
            for (LivingEntity e : now) {
                ids.add(e.getUUID());
                if (inside.add(e.getUUID())) {
                    Interp.fire(node, "on-enter", ctx.on(e, Targeting.center(e)));
                    if (inst.dead) return;
                }
            }
            inside.retainAll(ids);
        }

        if (age % every == 0) {
            if (!inst.spend(UPKEEP, "领域维持")) return;
            Vfx.pulse(level, pos, radius, node.element);
            Interp.fire(node, "on-tick", ctx.at(pos));
        }
        if (age % 4 == 0) Vfx.ring(level, pos, radius, node.element);
    }
}
