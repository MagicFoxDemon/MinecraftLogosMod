package dev.logos.runtime;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** Sulfur: the ways a spell changes the world. */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Effects {
    /** Raises max_absorption so a shield can go past vanilla's limit (0 without the Absorption effect). */
    private static final Identifier SHIELD = Identifier.fromNamespaceAndPath(LogosMod.MODID, "shield");

    /** True while a spell is dealing damage, so passive triggers do not re-enter themselves. */
    private static int spellDepth;

    private Effects() {}

    public static boolean inSpell() {
        return spellDepth > 0;
    }

    /** Applies elemental damage. Returns true if the target died from it. */
    public static boolean damage(SpellInstance inst, LivingEntity t, double amount, Element el) {
        if (!t.isAlive()) return false;
        double amt = amount;
        if (el == Element.FIRE && t.fireImmune()) amt = 0;
        if (el == Element.FROST && t.fireImmune()) amt *= 2;
        if (el == Element.LIGHTNING && t.isInWaterOrRain()) amt *= 1.5;
        DamageSource src;
        if (el == Element.PHYSICAL) {
            src = inst.caster instanceof Player p ? t.damageSources().playerAttack(p) : t.damageSources().mobAttack(inst.caster);
        } else {
            src = t.damageSources().indirectMagic(inst.caster, inst.caster);
        }
        inst.affected.add(t.getUUID());
        ServerLevel level = (ServerLevel) t.level();
        Vfx.burst(level, t, inst.caster.level() == level ? inst.caster.getEyePosition() : null, el, Vfx.Burst.DAMAGE);
        if (amt <= 0) return false;
        t.invulnerableTime = 0;
        spellDepth++;
        try {
            t.hurtServer(level, src, (float) amt);
        } finally {
            spellDepth--;
        }
        return !t.isAlive();
    }

    /** Kills outright (execute). */
    public static boolean kill(SpellInstance inst, LivingEntity t) {
        inst.affected.add(t.getUUID());
        t.invulnerableTime = 0;
        spellDepth++;
        try {
            t.hurtServer((ServerLevel) t.level(), t.damageSources().indirectMagic(inst.caster, inst.caster), Float.MAX_VALUE);
        } finally {
            spellDepth--;
        }
        return !t.isAlive();
    }

    public static void impulse(SpellInstance inst, Entity t, Vec3 dir, double strength) {
        if (dir.lengthSqr() < 1e-6) return;
        Vec3 v = dir.normalize().scale(strength);
        t.setDeltaMovement(t.getDeltaMovement().add(v.x, v.y + 0.1, v.z));
        t.hurtMarked = true;
        inst.affected.add(t.getUUID());
    }

    /**
     * Adds absorption on top of what the target has; only vanilla's max_absorption range (2048) bounds it.
     * The modifier stays (at 0 when no headroom is needed) as the mark of a spell shield, see {@link #shielded}.
     */
    public static void shield(LivingEntity t, double amount) {
        AttributeInstance max = t.getAttribute(Attributes.MAX_ABSORPTION);
        float want = (float) (t.getAbsorptionAmount() + amount);
        if (max != null) {
            AttributeModifier old = max.getModifier(SHIELD);
            double without = max.getValue() - (old == null ? 0 : old.amount());
            max.addOrReplacePermanentModifier(new AttributeModifier(SHIELD, Math.max(0, want - without), AttributeModifier.Operation.ADD_VALUE));
        }
        t.setAbsorptionAmount(want);
    }

    /** Whether {@code t} holds a spell shield. max_absorption is synced, so this also holds on clients. */
    public static boolean shielded(LivingEntity t) {
        AttributeInstance max = t.getAttribute(Attributes.MAX_ABSORPTION);
        return max != null && max.getModifier(SHIELD) != null;
    }

    /** A broken shield gives back its max_absorption headroom. */
    @SubscribeEvent
    static void onDamaged(LivingDamageEvent.Post event) {
        LivingEntity t = event.getEntity();
        if (t.getAbsorptionAmount() > 0) return;
        AttributeInstance max = t.getAttribute(Attributes.MAX_ABSORPTION);
        if (max != null) max.removeModifier(SHIELD);
    }
}
