package dev.logos.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.item.PageTier;
import dev.logos.item.SpellBag;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import dev.logos.lang.Compiler;
import dev.logos.lang.LogosError;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Entry points: compile (cached), pay, and start a spell instance. */
public final class Casting {
    private static final Map<String, Spell> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Spell> e) {
            return size() > 128;
        }
    };

    private Casting() {}

    /** @throws LogosError on syntax/type errors */
    public static Spell compile(String src) {
        Spell s = CACHE.get(src);
        if (s == null) {
            s = Compiler.compile(src);
            CACHE.put(src, s);
        }
        return s;
    }

    /**
     * Casts without a page (commands, tests): the whole written injection, no page cap. Pays it from
     * the player's pool and starts the spell. Returns null if refused.
     */
    public static @Nullable SpellInstance cast(ServerPlayer player, Spell spell, @Nullable Entity target, @Nullable Vec3 here) {
        if (!pay(player, spell, spell.mana())) return null;
        return run(player, spell, spell.mana(), PageTier.BASIC.recovery(), target, here);
    }

    /** What a cast from a page injects, and whether it burns the page doing so. */
    public record Injection(double mana, boolean burns, double multiplier) {}

    /**
     * A page injects at most its capacity. Past that, a wand with Formula Immolation multiplies the
     * capacity (if the spell allows it) and the page burns.
     */
    public static Injection injection(Spell spell, PageTier tier, int immolation) {
        int cap = tier.capacity();
        if (spell.mana() <= cap) return new Injection(spell.mana(), false, 1);
        if (immolation <= 0 || !spell.immolate()) return new Injection(cap, false, 1);
        double mult = dev.logos.LogosConfig.immolationMultiplier(immolation);
        return new Injection(Math.min(spell.mana(), cap * mult), true, mult);
    }

    /** The Formula Immolation level on {@code wand} (0 if none). A codex held without a wand has none. */
    public static int immolation(ServerPlayer player, @Nullable ItemStack wand) {
        if (wand == null) return 0;
        return player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(LogosMod.FORMULA_IMMOLATION)
                .map(h -> EnchantmentHelper.getItemEnchantmentLevel(h, wand)).orElse(0);
    }

    /** Casts the spell written on a page held by {@code wand} (or the off-hand codex). Returns null if refused. */
    public static @Nullable SpellInstance cast(ServerPlayer player, SpellBag.Entry entry, @Nullable ItemStack wand, @Nullable Entity target, @Nullable Vec3 here) {
        Spell spell = entry.spell();
        PageTier tier = entry.tier();
        Injection in = injection(spell, tier, immolation(player, wand));
        if (!pay(player, spell, in.mana())) return null;
        SpellInstance inst = run(player, spell, in.mana(), tier.recovery(), target, here);
        if (in.burns()) {
            entry.burn();
            ServerLevel level = player.level();
            Vfx.sound(level, player.getEyePosition(), SoundEvents.FIRECHARGE_USE, 0.8f, 0.8f);
            Vfx.burst(level, player.getEyePosition().add(player.getLookAngle().scale(0.6)), dev.logos.lang.Element.FIRE, 18);
            PlayerMagic.alert(player, Component.literal("🜂 焚式：《" + spell.name() + "》注入 " + Node.fmt(in.mana()) + "（载页承载 " + tier.capacity()
                    + " ×" + Node.fmt(in.multiplier()) + "），载页化为术式余烬").withStyle(ChatFormatting.GOLD));
        }
        return inst;
    }

    /** Checks stability and takes {@code mana} from the player's pool. */
    private static boolean pay(ServerPlayer player, Spell spell, double mana) {
        PlayerMagic m = PlayerMagic.of(player);
        if (spell.stability() > m.stability) {
            PlayerMagic.alert(player, Component.literal("法则不稳定：《" + spell.name() + "》复杂度 " + Node.fmt(spell.stability())
                    + " 超过你的稳定度上限 " + m.stability).withStyle(ChatFormatting.RED));
            return false;
        }
        if (m.mana < mana) {
            PlayerMagic.alert(player, Component.literal("魔力不足：《" + spell.name() + "》需要注入 " + Node.fmt(mana)
                    + "，当前 " + Node.fmt(m.mana)).withStyle(ChatFormatting.RED));
            return false;
        }
        m.mana -= mana;
        return true;
    }

    private static SpellInstance run(ServerPlayer caster, Spell spell, double mana, double recovery, @Nullable Entity target, @Nullable Vec3 here) {
        ServerLevel level = caster.level();
        SpellInstance inst = new SpellInstance(spell, caster, level, mana, recovery);
        Vec3 origin = here != null ? here : caster.getEyePosition();
        Ctx ctx = new Ctx(inst, level, origin, caster.getLookAngle(), target, Map.of(), 0, 0);
        Vfx.sound(level, origin, SoundEvents.EVOKER_CAST_SPELL, 0.6f, 1.4f);
        Vfx.cast(level, caster.position().add(0, 0.1, 0), dev.logos.lang.Element.ARCANE);
        inst.hold();
        try {
            Interp.run(spell.body(), ctx);
        } catch (StackOverflowError e) {
            inst.terminate(SpellRuntime.STACK_OVERFLOW);
        } catch (RuntimeException e) {
            LogosMod.LOGGER.error("Spell {} crashed", spell.name(), e);
            inst.terminate("运行错误: " + e);
        }
        inst.release();
        return inst;
    }

    /** Fires the passive spells bound to {@code trigger} on every wand or codex held in either hand. */
    public static void firePassives(ServerPlayer player, String trigger, @Nullable Entity target, Vec3 here) {
        PlayerMagic m = PlayerMagic.of(player);
        long now = player.level().getGameTime();
        // Formula Immolation comes from a wand in either hand, if there is one; a codex on its own has none.
        ItemStack wand = SpellBag.heldWand(player);
        for (SpellBag.Entry entry : SpellBag.passives(player)) {
            Spell spell = entry.spell();
            if (!spell.trigger().equals(trigger)) continue;
            if (m.cooldowns.getOrDefault(spell.name(), 0L) > now) continue;
            m.cooldowns.put(spell.name(), now + Limits.PASSIVE_COOLDOWN_TICKS);
            cast(player, entry, wand, target, here);
        }
    }

    public static void message(ServerPlayer p, LogosError e) {
        p.sendSystemMessage(Component.literal("✗ 术式无法成立 — " + e.describe()).withStyle(ChatFormatting.RED));
    }
}
