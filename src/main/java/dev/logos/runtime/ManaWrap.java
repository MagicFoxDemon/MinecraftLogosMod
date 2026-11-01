package dev.logos.runtime;

import java.util.Locale;

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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 魔力缠绕 (the imbue rune): until game time {@code until}, every melee blow of the holder's main hand carries
 * {@code amount} more damage in {@code element}. It is wound about the hand, not the item: whatever the hand holds
 * wears it, swapping weapons takes it along, and a bare hand wears it on the fist. Kept on the entity as the synced
 * {@code logos:mana_wrap} attachment, so every client sees it (the client's {@code SurfaceAuras} draws it).
 * <p>The extra hit lands right after the blow, through the attacker's element power and the target's resistances, as
 * a spell's damage does. A player's is scaled by the attack's charge, and only the entity aimed at gets it, not the
 * ones a sweep catches.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public record ManaWrap(Element element, double amount, long until) {
    /** The attachment's default, never worn. */
    public static final ManaWrap NONE = new ManaWrap(Element.ARCANE, 0, 0);
    /** Mana a wrap costs is this × the element's cost factor × points^1.2 × seconds^1.1 (4 points for 30 seconds ≈ points × seconds × 0.33). */
    public static final double PRICE = 0.178;

    private static final Codec<Element> ELEMENT = Codec.STRING.xmap(s -> Element.valueOf(s.toUpperCase(Locale.ROOT)), e -> e.name().toLowerCase(Locale.ROOT));
    public static final MapCodec<ManaWrap> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            ELEMENT.fieldOf("element").forGetter(ManaWrap::element),
            Codec.DOUBLE.fieldOf("amount").forGetter(ManaWrap::amount),
            Codec.LONG.fieldOf("until").forGetter(ManaWrap::until)).apply(i, ManaWrap::new));
    public static final StreamCodec<ByteBuf, ManaWrap> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> Element.values()[i], Element::ordinal), ManaWrap::element,
            ByteBufCodecs.DOUBLE, ManaWrap::amount, ByteBufCodecs.VAR_LONG, ManaWrap::until, ManaWrap::new);

    /** The mana for wrapping one target with {@code amount} points for {@code seconds}. */
    public static double cost(Element element, double amount, double seconds) {
        return PRICE * element.costFactor * Math.pow(Math.max(0, amount), 1.2) * Math.pow(Math.max(0, seconds), 1.1);
    }

    /** The wrap {@code t} wears now, or null. Works on clients too (their game time is synced). */
    public static @Nullable ManaWrap of(LivingEntity t) {
        if (!t.hasData(LogosMod.MANA_WRAP)) return null;
        ManaWrap w = t.getData(LogosMod.MANA_WRAP);
        return w.until > t.level().getGameTime() ? w : null;
    }

    /** Winds a wrap about {@code t}'s hand, replacing any it wore; no points or no time takes it off. */
    public static void wrap(LivingEntity t, Element element, double amount, double seconds) {
        if (amount <= 0 || seconds <= 0) {
            t.removeData(LogosMod.MANA_WRAP);
            return;
        }
        t.setData(LogosMod.MANA_WRAP, new ManaWrap(element, amount, t.level().getGameTime() + (long) Math.ceil(seconds * 20)));
    }

    // ---- the extra hit ---------------------------------------------------------------------------

    /** The player whose attack is under way, what they aimed at, and how charged the blow was (read before vanilla resets it). */
    private static @Nullable Player swinging;
    private static @Nullable Entity swung;
    private static float charge;

    @SubscribeEvent
    static void onAttack(AttackEntityEvent event) {
        swinging = event.getEntity();
        swung = event.getTarget();
        charge = swinging.getAttackStrengthScale(.5f);
    }

    @SubscribeEvent
    static void onHurt(LivingDamageEvent.Post event) {
        LivingEntity t = event.getEntity();
        DamageSource src = event.getSource();
        if (Effects.inSpell() || t.level().isClientSide() || !(src.getDirectEntity() instanceof LivingEntity a) || src.getEntity() != a || a == t) return;
        if (!src.is(DamageTypes.PLAYER_ATTACK) && !src.is(DamageTypes.MOB_ATTACK) && !src.is(DamageTypes.MOB_ATTACK_NO_AGGRO)) return;
        ManaWrap w = of(a);
        if (w == null) return;
        double k = 1;
        if (a instanceof Player p) {
            if (p != swinging || t != swung) return;
            k = charge;
            swinging = null;
        }
        double amount = w.amount * k;
        // After the blow has finished landing, not inside it.
        LogosMod.defer(() -> Effects.followUp(a, t, amount, w.element));
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
