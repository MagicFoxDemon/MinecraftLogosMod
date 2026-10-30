package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.item.SpellBag;
import dev.logos.lang.Element;
import dev.logos.lang.Node;
import dev.logos.net.ChargePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

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
        /** How it looks: the spell's main element, and a grade by how much it injects. */
        public final Element element;
        public final int tier;

        Charge(SpellBag.Entry entry, int selected, Casting.Injection in, double flow, double surge) {
            this.entry = entry;
            this.selected = selected;
            this.in = in;
            this.flow = flow;
            this.surge = surge;
            this.ticks = ticks(in.mana(), flow, surge);
            this.element = entry.element();
            this.tier = tier(in.mana());
        }

        public float progress() {
            return in.mana() <= 0 ? 1 : (float) Math.min(1, injected / in.mana());
        }

        public boolean full() {
            return injected >= in.mana();
        }

        double after(int k) {
            return injectedAfter(k, in.mana(), flow, surge, ticks);
        }
    }

    /** Charge looks by injection: ordinary under 100, elaborate under 800, grand under 3200, colossal beyond. */
    public static int tier(double mana) {
        return mana < 100 ? 0 : mana < 800 ? 1 : mana < 3200 ? 2 : 3;
    }

    /** Seconds to inject {@code mana} at {@code flow + surge·t} per second, before the minimum length applies. */
    public static double seconds(double mana, double flow, double surge) {
        if (mana <= 0) return 0;
        if (surge <= 0) return flow <= 0 ? Double.POSITIVE_INFINITY : mana / flow;
        return (Math.sqrt(flow * flow + 2 * surge * mana) - flow) / surge;
    }

    /**
     * Mana a charge of {@code mana} taking {@code ticks} has injected after {@code k} ticks; a charge stretched
     * to the minimum length flows proportionally slower.
     */
    public static double injectedAfter(int k, double mana, double flow, double surge, int ticks) {
        if (k >= ticks) return mana;
        double t = k / 20.0 * Math.min(1, seconds(mana, flow, surge) * 20 / ticks);
        return Math.min(mana, gathered(t, flow, surge));
    }

    /** Mana gathered in the first {@code t} seconds at {@code flow + surge·t} per second. */
    public static double gathered(double t, double flow, double surge) {
        return flow * t + surge * t * t / 2;
    }

    /** How fast a player gathers mana through a wand: {@code flow + surge·t} per second. */
    public record Rate(double flow, double surge) {
        /** Mana gathered from the {@code k}-th tick to the next. */
        public double tick(int k) {
            return gathered((k + 1) / 20.0, flow, surge) - gathered(k / 20.0, flow, surge);
        }
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
        SpellBag.Entry entry = castable(player, wand, hand);
        if (entry == null) return false;
        Casting.Injection in = Casting.injection(entry.spell(), entry.tier(), Casting.immolation(player, wand));
        if (!Casting.affordable(player, entry.spell(), in.mana())) return false;
        Rate rate = rate(player, wand);
        if (rate == null) return false;
        PlayerMagic m = PlayerMagic.of(player);
        m.charge = new Charge(entry, wand.getOrDefault(LogosMod.SELECTED.get(), 0), in, rate.flow(), rate.surge());
        sync(player, m.charge);
        Vfx.sound(player.level(), player.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5f, 0.8f);
        return true;
    }

    /**
     * The spell {@code wand} would cast from {@code hand}: its selected page, if the wand is in the main hand
     * and the spell is not passive. Null (and tells the player why) otherwise.
     */
    static SpellBag.@Nullable Entry castable(ServerPlayer player, ItemStack wand, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            PlayerMagic.alert(player, Component.literal("法杖要拿在主手才能施放").withStyle(ChatFormatting.YELLOW));
            return null;
        }
        SpellBag.Entry entry = SpellBag.selected(player, wand);
        if (entry == null) {
            PlayerMagic.alert(player, Component.literal("法杖上没有术式：潜行右键挂上术式载页，或在副手拿法式典籍").withStyle(ChatFormatting.YELLOW));
            return null;
        }
        if (entry.spell().isPassive()) {
            PlayerMagic.alert(player, Component.literal("《" + entry.spell().name() + "》是被动术式，会在"
                    + dev.logos.lang.Spell.triggerZh(entry.spell().trigger()) + "自动触发，无需施放").withStyle(ChatFormatting.YELLOW));
            return null;
        }
        return entry;
    }

    /** How fast {@code player} gathers mana through {@code wand}, or null (and tells them) if it gathers none. */
    static @Nullable Rate rate(ServerPlayer player, ItemStack wand) {
        PlayerMagic m = PlayerMagic.of(player);
        // Channel speed k fills the same charge in 1/k the time: flow scales by k, surge by k².
        double speed = channel(player, SpellModifiers.CHANNEL_SPEED, wand);
        double flow = channel(player, LogosMod.CHANNEL_FLOW, wand) * speed;
        double surge = channel(player, LogosMod.CHANNEL_SURGE, wand) * (1 + m.maxMana / LogosConfig.chargeManaScale()) * speed * speed;
        if (flow <= 0 && surge <= 0) {
            PlayerMagic.alert(player, Component.literal("这根法杖无法汇集魔力").withStyle(ChatFormatting.YELLOW));
            return null;
        }
        return new Rate(flow, surge);
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
                if (c.full()) sync(player, c);
                chime(level, tip, c.done, (float) c.done / c.ticks, c.full());
            }
        }
        if (level.getGameTime() % 2 == 0) sync(player, c);
    }

    /**
     * The sound of mana flowing in at the wand's head, on the {@code k}-th tick paid for: a chime every
     * quarter second, rising as it goes ({@code along}, 0 to 1), and a bright one on the tick it {@code filled}.
     */
    static void chime(ServerLevel level, Vec3 tip, int k, float along, boolean filled) {
        if (filled) {
            Vfx.sound(level, tip, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.6f);
            Vfx.sound(level, tip, SoundEvents.PLAYER_LEVELUP, 0.4f, 1.8f);
        } else if (k % 5 == 0) {
            Vfx.sound(level, tip, SoundEvents.AMETHYST_BLOCK_CHIME, 0.5f, 0.6f + 1.0f * along);
        }
    }

    /** Let go: casts if the charge is full and returns true; otherwise leaves it for {@link #cancel}. */
    public static boolean release(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        Charge c = m.charge;
        if (c == null || !c.full()) return false;
        m.charge = null;
        discharge(player, c.element, c.tier);
        Casting.launch(player, c.entry, c.in, null, null, true);
        return true;
    }

    /** Ends a charge that did not cast: everything injected so far goes back out as free mana beside the player. */
    public static void cancel(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        Charge c = m.charge;
        if (c == null) return;
        m.charge = null;
        hide(player);
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

    /** About where the wand's tip is, for sounds: ahead, below the eyes and to the right. Clients draw the effects at the real tip. */
    static Vec3 tip(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? Vec3.ZERO : right.normalize();
        return player.getEyePosition().add(look.scale(0.9)).add(right.scale(0.4)).add(0, -0.45, 0);
    }

    /** Tells the player and everyone tracking them how the charge stands (the crosshair ring, the charging effects). */
    private static void sync(ServerPlayer player, Charge c) {
        show(player, c.element, c.tier, c.progress(), c.full());
    }

    /** Shows {@code caster} charging (a player's wand, a lifeform's cast): its element, tier and how full it is. */
    public static void show(LivingEntity caster, Element element, int tier, float progress, boolean full) {
        send(caster, new ChargePayload(caster.getId(), element.ordinal(), tier, progress, full));
    }

    /**
     * A charge let go into its spell: clients play its release at the caster's cast point (bigger by
     * tier), and it is heard. Stands in for the plain cast flourish an uncharged cast gets.
     */
    public static void discharge(LivingEntity caster, Element element, int tier) {
        send(caster, ChargePayload.released(caster.getId(), element.ordinal(), tier));
        if (!(caster.level() instanceof ServerLevel level)) return;
        Vec3 at = caster.getEyePosition();
        Vfx.sound(level, at, SoundEvents.EVOKER_CAST_SPELL, 0.6f + 0.1f * tier, 1.4f - 0.15f * tier);
        if (tier >= 1) Vfx.sound(level, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.7f, 1.2f);
        if (tier >= 2) Vfx.sound(level, at, SoundEvents.BEACON_POWER_SELECT, 0.5f, 1.3f);
        if (tier >= 3) Vfx.sound(level, at, SoundEvents.BEACON_DEACTIVATE, 0.8f, 0.6f);
    }

    /** Ends what {@link #show} shows, without casting (clients play a fizzle). */
    public static void hide(LivingEntity caster) {
        send(caster, ChargePayload.ended(caster.getId()));
    }

    /** To players near enough to see it whose connection knows the channel (fake players and modless clients do not). */
    private static void send(LivingEntity caster, ChargePayload payload) {
        if (!(caster.level() instanceof ServerLevel level)) return;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(caster) <= 128 * 128 && p.connection.hasChannel(ChargePayload.TYPE)) PacketDistributor.sendToPlayer(p, payload);
        }
    }
}
