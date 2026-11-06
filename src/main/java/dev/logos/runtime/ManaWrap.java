package dev.logos.runtime;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 魔力缠绕 as it sits on a hand: until game time {@code until}, the holder's main hand wears a wrap in {@code element}.
 * It is wound about the hand, not the item: whatever the hand holds wears it, swapping weapons takes it along, and a
 * bare hand wears it on the fist. Kept on the entity as the synced {@code logos:mana_wrap} attachment, so every client
 * sees it (the client's {@code SurfaceAuras} draws it).
 * <p>Two things put one there. The imbue rune winds a spell about the hand ({@link WrapObj}), whose events go off on
 * the hand's blows; it adds no damage of its own ({@code amount} 0) and is not saved, since the spell is not. And
 * {@link #wrap}, for other mods' creatures, winds a plain one that adds {@code amount} damage to every such blow,
 * through the attacker's element power and the target's resistances as a spell's damage does (life too, which
 * players cannot write).
 * <p>A blow counts when it is a melee blow of the main hand charged to {@link #CHARGE} (a mob's, always; for a player
 * only the entity aimed at, not the ones a sweep catches), or when an arrow or trident the holder loosed while
 * wrapped strikes (a bow's only at full draw).
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public record ManaWrap(Element element, double amount, long until) {
    /** The attachment's default, never worn. */
    public static final ManaWrap NONE = new ManaWrap(Element.ARCANE, 0, 0);
    /** How charged a player's blow must be to count: the share of the attack time since the last, as a sweep needs. */
    public static final float CHARGE = 0.9f;

    private static final Codec<Element> ELEMENT = Codec.STRING.xmap(s -> Element.valueOf(s.toUpperCase(Locale.ROOT)), e -> e.name().toLowerCase(Locale.ROOT));
    public static final MapCodec<ManaWrap> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ELEMENT.fieldOf("element").forGetter(ManaWrap::element),
            Codec.DOUBLE.fieldOf("amount").forGetter(ManaWrap::amount),
            Codec.LONG.fieldOf("until").forGetter(ManaWrap::until)).apply(i, ManaWrap::new));
    public static final StreamCodec<ByteBuf, ManaWrap> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> Element.values()[i], Element::ordinal), ManaWrap::element,
            ByteBufCodecs.DOUBLE, ManaWrap::amount, ByteBufCodecs.VAR_LONG, ManaWrap::until, ManaWrap::new);

    /** The wrap {@code t} wears now, or null. Works on clients too (their game time is synced). */
    public static @Nullable ManaWrap of(LivingEntity t) {
        if (!t.hasData(LogosMod.MANA_WRAP)) return null;
        ManaWrap w = t.getData(LogosMod.MANA_WRAP);
        return w.until > t.level().getGameTime() ? w : null;
    }

    /**
     * Winds a plain wrap about {@code t}'s hand, adding {@code amount} damage in {@code element} to each of its blows,
     * replacing any wrap it wore (a spell's ends); no points or no time takes it off.
     */
    public static void wrap(LivingEntity t, Element element, double amount, double seconds) {
        WrapObj spell = WrapObj.on(t);
        if (spell != null) spell.disperse();
        if (amount <= 0 || seconds <= 0) {
            t.removeData(LogosMod.MANA_WRAP);
            return;
        }
        t.setData(LogosMod.MANA_WRAP, new ManaWrap(element, amount, t.level().getGameTime() + (long) Math.ceil(seconds * 20)));
    }

    /** Shows a spell wrap on {@code t}'s hand until {@code until}. */
    static void show(LivingEntity t, Element element, long until) {
        t.setData(LogosMod.MANA_WRAP, new ManaWrap(element, 0, until));
    }

    /** Takes a spell wrap's look off {@code t}'s hand (a plain wrap that replaced it stays). */
    static void hide(LivingEntity t) {
        if (t.hasData(LogosMod.MANA_WRAP) && t.getData(LogosMod.MANA_WRAP).amount <= 0) t.removeData(LogosMod.MANA_WRAP);
    }

    // ---- blows -------------------------------------------------------------------------------------

    /** On the server, the player whose attack is under way, what they aimed at, and how charged the blow was (read before vanilla resets it). */
    private static @Nullable Player swinging;
    private static @Nullable Entity swung;
    private static float charge;

    /** What an arrow or trident loosed by a wrapped hand carries. */
    private record Carried(@Nullable ManaWrap plain, @Nullable WrapObj spell) {}
    private static final Map<Entity, Carried> LOOSED = new WeakHashMap<>();

    @SubscribeEvent
    static void onAttack(AttackEntityEvent event) {
        // A client runs the attack too, on its own thread, but only the server's blow lands.
        if (event.getEntity().level().isClientSide()) return;
        swinging = event.getEntity();
        swung = event.getTarget();
        charge = swinging.getAttackStrengthScale(.5f);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (swinging != event.getEntity()) return;
        swinging = null;
        swung = null;
    }

    /** Forgets every blow under way and every shot in the air, as the runtime is cleared: what they carry holds on to a spell and its world. */
    static void clear() {
        swinging = null;
        swung = null;
        LOOSED.clear();
    }

    @SubscribeEvent
    static void onLoosed(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk() || !(event.getEntity() instanceof AbstractArrow arrow)
                || !(arrow.getOwner() instanceof LivingEntity a)) return;
        // A bow drawn short of full looses no crit arrow; a crossbow's and a trident's always count.
        if (a instanceof Player && !(arrow instanceof ThrownTrident) && !arrow.isCritArrow()) return;
        ManaWrap w = of(a);
        if (w != null) LOOSED.put(arrow, new Carried(w.amount > 0 ? w : null, WrapObj.on(a)));
    }

    @SubscribeEvent
    static void onHurt(LivingDamageEvent.Post event) {
        LivingEntity t = event.getEntity();
        DamageSource src = event.getSource();
        if (Effects.inSpell() || t.level().isClientSide() || !(src.getEntity() instanceof LivingEntity a) || a == t) return;
        Entity direct = src.getDirectEntity();
        if (direct == a) {
            if (!src.is(DamageTypes.PLAYER_ATTACK) && !src.is(DamageTypes.MOB_ATTACK) && !src.is(DamageTypes.MOB_ATTACK_NO_AGGRO)) return;
            if (a instanceof Player p) {
                if (p != swinging || t != swung) return;
                swinging = null;
                swung = null;
                if (charge < CHARGE) return;
            }
            ManaWrap w = of(a);
            if (w == null) return;
            Vec3 here = Targeting.center(t);
            land(a, t, w.amount > 0 ? w : null, WrapObj.on(a), !(a instanceof Player), here, here.subtract(a.getEyePosition()).normalize());
        } else if (direct != null && LOOSED.get(direct) instanceof Carried c) {
            land(a, t, c.plain, c.spell, false, direct.position(), direct.getDeltaMovement().normalize());
        }
    }

    /** After the blow has finished landing, not inside it: its plain damage, then the spell's events. */
    private static void land(LivingEntity a, LivingEntity t, @Nullable ManaWrap plain, @Nullable WrapObj spell, boolean swing, Vec3 here, Vec3 aim) {
        LogosMod.defer(() -> {
            if (plain != null) Effects.followUp(a, t, plain.amount, plain.element);
            if (spell == null) return;
            // A mob is not heard swinging; its blow is its swing.
            if (swing) spell.swing(a.getEyePosition(), a.getLookAngle());
            spell.hit(t, here, aim);
        });
    }

    /** A wrap that has run out comes off, so clients stop drawing it. */
    @SubscribeEvent
    static void onTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity t && !t.level().isClientSide() && t.hasData(LogosMod.MANA_WRAP)
                && t.getData(LogosMod.MANA_WRAP).until <= t.level().getGameTime()) {
            t.removeData(LogosMod.MANA_WRAP);
        }
    }
}
