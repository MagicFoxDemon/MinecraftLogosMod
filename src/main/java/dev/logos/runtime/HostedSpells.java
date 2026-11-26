package dev.logos.runtime;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.lang.Node;
import dev.logos.lang.Runes;
import dev.logos.lang.Spell;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Runs a spell written around a hosted form (寄托形体, {@link Runes#hosted}) for the thing that carries it: another
 * mod's round, arrow and the like. Such a spell is not cast: its mana was injected when the thing was made, and the
 * thing sets off the form's events where it strikes. One {@link Carried} is one such thing on its way. All its events
 * draw on the same pool, the shooter is the caster, and whatever is left when it is done goes free where it last acted,
 * as a cast spell's does. Checking the spell (that it is one, its stability, what the player may write) is the host's
 * business when the thing is made.
 */
public final class HostedSpells {
    private HostedSpells() {}

    /** The hosted form {@code spell} is written around (its one statement), or null if it is not such a spell. */
    public static @Nullable Node form(Spell spell) {
        if (spell.body().size() != 1) return null;
        Node n = spell.body().getFirst();
        return n.kind == Node.Kind.CALL && n.rune != null && n.rune.hosted ? n : null;
    }

    /**
     * Sends the thing carrying {@code spell} on its way: from now on its events may go off, paid from {@code mana}.
     * {@code recovery} is the share of what is left that comes back if the spell is put out rather than ending on its
     * own, as a page's grade sets it for a cast.
     *
     * @throws IllegalArgumentException if {@code spell} is not written around a hosted form
     */
    public static Carried carry(Spell spell, LivingEntity caster, double mana, double recovery) {
        Node form = form(spell);
        if (form == null) throw new IllegalArgumentException("spell '" + spell.name() + "' is not written around a hosted form");
        return new Carried(new SpellInstance(spell, caster, (ServerLevel) caster.level(), mana, recovery), form);
    }

    /** One thing carrying a hosted spell, from the moment it is sent off until {@link #end}. */
    public static final class Carried {
        private final SpellInstance inst;
        private final Node form;
        private boolean ended;

        private Carried(SpellInstance inst, Node form) {
            this.inst = inst;
            this.form = form;
            // Kept going until the host is done with it, however many of its events go off meanwhile.
            inst.hold();
        }

        /** The rune it carries, such as {@code rifle-round}. */
        public String word() {
            return form.rune.name;
        }

        /** The running spell: its pool, what it has affected, why it ended. */
        public SpellInstance instance() {
            return inst;
        }

        /**
         * Sets off {@code event} at {@code here}, the thing travelling along {@code aim}; an event that binds a target
         * gets {@code target} ({@code here} stays where it struck). Does nothing once the spell is out of mana or ended.
         *
         * @return whether the spell is still going
         */
        public boolean fire(String event, ServerLevel level, Vec3 here, Vec3 aim, @Nullable Entity target) {
            if (ended || inst.dead) return false;
            Ctx at = new Ctx(inst, level, here, aim.lengthSqr() < 1e-12 ? Ctx.UP : aim.normalize(), null, Map.of(), 0, 0);
            Ctx ctx = target == null ? at : at.on(target, here);
            inst.touch(ctx.level(), here);
            SpellRuntime.guard(inst, () -> Interp.fire(form, event, ctx));
            return !inst.dead;
        }

        /** The thing is done (it struck, or flew out): the spell ends once what its events started has run its course. */
        public void end() {
            if (ended) return;
            ended = true;
            inst.release();
        }
    }
}
