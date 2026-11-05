package dev.logos.runtime;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.lang.Element;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent;

/** Sulfur: the ways a spell changes the world. */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Effects {
    /** Raises max_absorption so a shield can go past vanilla's limit (0 without the Absorption effect). */
    private static final Identifier SHIELD = Identifier.fromNamespaceAndPath(LogosMod.MODID, "shield");

    /**
     * Fire damage: vanilla fire (tagged {@code minecraft:is_fire}, not armour-piercing), so armour, protection,
     * fire protection, fire resistance and fire immunity all turn it aside, credited to the attacker.
     */
    public static final ResourceKey<DamageType> FIRE = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(LogosMod.MODID, "spell_fire"));

    /**
     * Void damage: true damage, close to falling out of the world. Armour, shields, protection, the resistance effect,
     * spell resistance, a creature's own damage cuts and (by config) absorption do not touch it, and it does not knock
     * back. It leaves out {@code bypasses_invulnerability}, so creative players, invulnerable entities and invulnerable
     * states (a charging wither, a time stop) are still safe, and a totem of undying still works unless configured off.
     */
    public static final ResourceKey<DamageType> VOID = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(LogosMod.MODID, "spell_void"));

    /** True while a spell is dealing damage, so passive triggers do not re-enter themselves. */
    private static int spellDepth;
    /** The element of the damage being dealt right now; see {@link #dealing}. */
    private static @org.jspecify.annotations.Nullable Element dealing;

    private Effects() {}

    public static boolean inSpell() {
        return spellDepth > 0;
    }

    /**
     * The element of the elemental damage being dealt right now (a spell's, or a creature's through {@link #damage}),
     * for code running inside the hurt, such as a shield-block event: every element but physical and fire is vanilla
     * magic, so the damage type alone does not tell them apart. Null outside such a hurt.
     */
    public static @org.jspecify.annotations.Nullable Element dealing() {
        return dealing;
    }

    /** Applies elemental damage. Returns true if the target died from it. */
    public static boolean damage(SpellInstance inst, LivingEntity t, double amount, Element el) {
        if (!t.isAlive()) return false;
        inst.affected.add(t.getUUID());
        double power = inst.modifiers.damage(el) * (inst.inWrap() ? inst.modifiers.wrapPower() : 1);
        return hurt(inst.caster, t, amount * power, el, true);
    }

    /**
     * Elemental damage dealt by a creature rather than a spell: the entry for other mods' monsters, such as
     * a dryad's life damage. The attacker's element power and the target's resistances apply and the hit
     * shows the element's damage burst, as for a spell; it keeps vanilla's hurt cooldown, and passive
     * triggers see it as ordinary damage. Returns true if the target died from it.
     */
    public static boolean damage(LivingEntity attacker, LivingEntity t, double amount, Element el) {
        if (!t.isAlive()) return false;
        return hurt(attacker, t, amount * SpellModifiers.of(attacker).damage(el), el, false);
    }

    /**
     * A second hit riding on a melee blow that has just landed (a {@link ManaWrap}): the attacker's element power and
     * the target's resistances apply, and like a spell's damage it ignores the hurt cooldown the blow set and is not
     * seen by passive triggers. Returns true if the target died from it.
     */
    public static boolean followUp(LivingEntity attacker, LivingEntity t, double amount, Element el) {
        if (!t.isAlive() || !attacker.isAlive() || attacker.level() != t.level()) return false;
        return hurt(attacker, t, amount * SpellModifiers.of(attacker).damage(el), el, true);
    }

    private static boolean hurt(LivingEntity attacker, LivingEntity t, double amount, Element el, boolean spell) {
        double amt = amount;
        if (el == Element.FIRE && t.fireImmune()) amt = 0;
        if (el == Element.FROST && t.fireImmune()) amt *= 2;
        if (el == Element.LIGHTNING && t.isInWaterOrRain()) amt *= 1.5;
        amt *= SpellModifiers.taken(t, el);
        // Physical is a melee hit, fire is vanilla fire, void is true damage, and the rest are vanilla magic, which armour does not stop.
        DamageSource src;
        if (el == Element.PHYSICAL) {
            src = attacker instanceof Player p ? t.damageSources().playerAttack(p) : t.damageSources().mobAttack(attacker);
        } else if (el == Element.FIRE) {
            src = t.damageSources().source(FIRE, attacker, attacker);
        } else if (el == Element.VOID) {
            src = voidHit(attacker, t, (float) amt);
        } else {
            src = t.damageSources().indirectMagic(attacker, attacker);
        }
        ServerLevel level = (ServerLevel) t.level();
        Vfx.burst(level, t, attacker.level() == level ? attacker.getEyePosition() : null, el, Vfx.Burst.DAMAGE);
        if (amt <= 0) return false;
        Element outer = dealing;
        dealing = el;
        try {
            if (!spell) return t.hurtServer(level, src, (float) amt) && !t.isAlive();
            t.invulnerableTime = 0;
            spellDepth++;
            try {
                t.hurtServer(level, src, (float) amt);
            } finally {
                spellDepth--;
            }
            return !t.isAlive();
        } finally {
            dealing = outer;
        }
    }

    private static DamageSource voidHit(LivingEntity attacker, LivingEntity t, float amount) {
        return new VoidHit(t.damageSources().source(VOID, attacker, attacker).typeHolder(), attacker, amount);
    }

    /** A void hit that remembers its full amount, so a cut made on its way to the health bar can be undone. */
    private static final class VoidHit extends DamageSource {
        final float amount;

        VoidHit(Holder<DamageType> type, LivingEntity attacker, float amount) {
            super(type, attacker, attacker);
            this.amount = amount;
        }
    }

    /**
     * Last in line, so it undoes whatever a creature's hurtServer (the ender dragon's body) or another handler (a
     * colossus's seat) cut from a void hit on its way here. By config the hit also goes past absorption, which it leaves intact.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onVoidIncoming(LivingIncomingDamageEvent event) {
        if (!event.getSource().is(VOID)) return;
        if (event.getSource() instanceof VoidHit hit && event.getAmount() < hit.amount) event.setAmount(hit.amount);
        if (LogosConfig.voidIgnoresAbsorption()) event.addReductionModifier(DamageContainer.Reduction.ABSORPTION, (container, absorbed) -> 0);
    }

    /** By config, a totem of undying does not save its holder from void damage. */
    @SubscribeEvent
    static void onUseTotem(LivingUseTotemEvent event) {
        if (event.getSource().is(VOID) && LogosConfig.voidIgnoresTotem()) event.setCanceled(true);
    }

    /** Kills outright (execute): a void hit past all counting, so only what stops void damage stops it. */
    public static boolean kill(SpellInstance inst, LivingEntity t) {
        inst.affected.add(t.getUUID());
        t.invulnerableTime = 0;
        spellDepth++;
        try {
            t.hurtServer((ServerLevel) t.level(), voidHit(inst.caster, t, Float.MAX_VALUE), Float.MAX_VALUE);
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
