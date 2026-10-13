package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.item.SpellBag;
import dev.logos.lang.Node;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 蓄力: a wand cast is charged before it goes off. While right-click is held, mana flows from the
 * player into the cast at {@code flow + surge·t} per second: a small spell is ready almost at once, a
 * great one takes long, but not proportionally long. Once full the charge holds until let go, and
 * then casts. Let go early, switch away or die, and what was injected goes back out as free mana.
 * <p>The page still decides how much a cast injects; the wand (and gear, through the channel
 * attributes) decides how fast. A charge only starts if the player could pay all of it now.
 */
public final class Charging {
    private Charging() {}

    /** A cast being charged: what it will inject, how fast, and how far it has got. */
    public static final class Charge {
        final SpellBag.Entry entry;
        final int selected;
        public final Casting.Injection in;
        final double flow, surge;
        /** Ticks the whole charge takes. */
        public final int ticks;
        /** Ticks paid for so far: a tick the pool cannot pay holds the charge back. */
        int done;
        public double injected;

        Charge(SpellBag.Entry entry, int selected, Casting.Injection in, double flow, double surge) {
            this.entry = entry;
            this.selected = selected;
            this.in = in;
            this.flow = flow;
            this.surge = surge;
            this.ticks = ticks(in.mana(), flow, surge);
        }

        public boolean full() {
            return injected >= in.mana();
        }

        /** Mana injected after {@code k} ticks; a charge stretched to the minimum length flows proportionally slower. */
        double after(int k) {
            if (k >= ticks) return in.mana();
            double t = k / 20.0 * Math.min(1, seconds(in.mana(), flow, surge) * 20 / ticks);
            return Math.min(in.mana(), flow * t + surge * t * t / 2);
        }
    }

    /** Seconds to inject {@code mana} at {@code flow + surge·t} per second, before the minimum length applies. */
    public static double seconds(double mana, double flow, double surge) {
        if (mana <= 0) return 0;
        if (surge <= 0) return flow <= 0 ? Double.POSITIVE_INFINITY : mana / flow;
        return (Math.sqrt(flow * flow + 2 * surge * mana) - flow) / surge;
    }

    /** Ticks a charge of {@code mana} takes, never fewer than the configured minimum. */
    public static int ticks(double mana, double flow, double surge) {
        return (int) Math.max(LogosConfig.chargeMinTicks(), Math.ceil(seconds(mana, flow, surge) * 20 - 1e-9));
    }

    /**
     * The player's channel attribute with {@code wand} in the main hand. The wand's own modifiers count
     * straight away, even on the tick it was taken up, before the equipment update applies them.
     */
    static double channel(ServerPlayer player, Holder<Attribute> attribute, ItemStack wand) {
        AttributeInstance held = player.getAttribute(attribute);
        if (held == null) return 0;
        AttributeInstance with = new AttributeInstance(attribute, i -> {});
        with.replaceFrom(held);
        wand.forEachModifier(EquipmentSlot.MAINHAND, (a, modifier) -> {
            if (a.equals(attribute)) with.addOrUpdateTransientModifier(modifier);
        });
        return with.getValue();
    }

    public static @Nullable Charge of(ServerPlayer player) {
        return PlayerMagic.of(player).charge;
    }

    /** Starts charging the wand's selected spell. Returns false (and tells the player why) if it cannot. */
    public static boolean begin(ServerPlayer player, ItemStack wand, InteractionHand hand) {
        cancel(player);
        if (hand != InteractionHand.MAIN_HAND) {
            PlayerMagic.alert(player, Component.literal("法杖要拿在主手才能蓄力施放").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        SpellBag.Entry entry = SpellBag.selected(player, wand);
        if (entry == null) {
            PlayerMagic.alert(player, Component.literal("法杖上没有术式：潜行右键挂上术式载页，或在副手拿法式典籍").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        if (entry.spell().isPassive()) {
            PlayerMagic.alert(player, Component.literal("《" + entry.spell().name() + "》是被动术式，会在"
                    + dev.logos.lang.Spell.triggerZh(entry.spell().trigger()) + "自动触发，无需施放").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        Casting.Injection in = Casting.injection(entry.spell(), entry.tier(), Casting.immolation(player, wand));
        if (!Casting.affordable(player, entry.spell(), in.mana())) return false;
        PlayerMagic m = PlayerMagic.of(player);
        double flow = channel(player, LogosMod.CHANNEL_FLOW, wand);
        double surge = channel(player, LogosMod.CHANNEL_SURGE, wand) * (1 + m.maxMana / LogosConfig.chargeManaScale());
        if (flow <= 0 && surge <= 0) {
            PlayerMagic.alert(player, Component.literal("这根法杖无法汇集魔力").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        m.charge = new Charge(entry, wand.getOrDefault(LogosMod.SELECTED.get(), 0), in, flow, surge);
        Vfx.sound(player.level(), player.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5f, 0.8f);
        return true;
    }

    /** One tick of charging: pays the next share of mana, or stops the charge if the selected spell changed. */
    public static void tick(ServerPlayer player, ItemStack wand) {
        Charge c = of(player);
        if (c == null) return;
        SpellBag.Entry now = SpellBag.selected(player, wand);
        if (now == null || wand.getOrDefault(LogosMod.SELECTED.get(), 0) != c.selected
                || !ItemStack.isSameItemSameComponents(now.page(), c.entry.page())) {
            player.stopUsingItem();
            return;
        }
        ServerLevel level = player.level();
        Vec3 tip = tip(player);
        if (!c.full()) {
            PlayerMagic m = PlayerMagic.of(player);
            double share = c.after(c.done + 1) - c.injected;
            if (m.mana + 1e-9 >= share) {
                m.mana = Math.max(0, m.mana - share);
                c.injected += share;
                c.done++;
                if (c.full()) {
                    Vfx.sound(level, tip, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.6f);
                    Vfx.sound(level, tip, SoundEvents.PLAYER_LEVELUP, 0.4f, 1.8f);
                    level.sendParticles(ParticleTypes.END_ROD, tip.x, tip.y, tip.z, 12, 0.15, 0.15, 0.15, 0.05);
                } else if (c.done % 5 == 0) {
                    Vfx.sound(level, tip, SoundEvents.AMETHYST_BLOCK_CHIME, 0.5f, 0.6f + 1.0f * c.done / c.ticks);
                }
            }
        }
        // Placeholder look until the element-and-tier charging effects exist: motes drawn in while charging, a steady glow once full.
        if (c.full()) {
            if (level.getGameTime() % 3 == 0) level.sendParticles(ParticleTypes.END_ROD, tip.x, tip.y, tip.z, 1, 0.05, 0.05, 0.05, 0.005);
        } else {
            level.sendParticles(ParticleTypes.ENCHANT, tip.x, tip.y, tip.z, 3, 0.4, 0.4, 0.4, 0.6);
        }
        if (level.getGameTime() % 2 == 0) progress(player, c);
    }

    /** Let go: casts if the charge is full and returns true; otherwise leaves it for {@link #cancel}. */
    public static boolean release(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        Charge c = m.charge;
        if (c == null || !c.full()) return false;
        m.charge = null;
        Casting.launch(player, c.entry, c.in, null, null);
        return true;
    }

    /** Ends a charge that did not cast: everything injected so far goes back out as free mana beside the player. */
    public static void cancel(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        Charge c = m.charge;
        if (c == null) return;
        m.charge = null;
        if (c.injected <= 0) return;
        FreeManaEntity.release(player.level(), player.getEyePosition().add(player.getLookAngle().scale(0.8)), c.injected);
        Vfx.sound(player.level(), player.getEyePosition(), SoundEvents.FIRE_EXTINGUISH, 0.4f, 1.6f);
        PlayerMagic.alert(player, Component.literal("蓄力中断：已注入的 " + Node.fmt(c.injected) + " 魔力散为游离魔力").withStyle(ChatFormatting.GRAY));
    }

    /** Stops the player using the wand, and with it the charge (death, logout, changing dimension). */
    public static void interrupt(ServerPlayer player) {
        if (player.isUsingItem()) player.stopUsingItem();
        cancel(player);
    }

    /** About where the wand's tip is: ahead, below the eyes and to the right, clear of the player's view. */
    private static Vec3 tip(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? Vec3.ZERO : right.normalize();
        return player.getEyePosition().add(look.scale(0.9)).add(right.scale(0.4)).add(0, -0.45, 0);
    }

    /** Placeholder progress readout on the action bar, until the HUD ring exists. */
    private static void progress(ServerPlayer player, Charge c) {
        int filled = (int) Math.floor(20 * c.injected / Math.max(1e-9, c.in.mana()));
        Component bar = Component.literal("《" + c.entry.spell().name() + "》 ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("▮".repeat(filled)).withStyle(c.full() ? ChatFormatting.GOLD : ChatFormatting.AQUA))
                .append(Component.literal("▮".repeat(20 - filled)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(c.full() ? " 蓄力完成 · 松开施放" : " " + Node.fmt(c.injected) + "/" + Node.fmt(c.in.mana()))
                        .withStyle(c.full() ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        PlayerMagic.of(player).hudQuietUntil = player.level().getGameTime() + 10;
        player.sendOverlayMessage(bar);
    }
}
