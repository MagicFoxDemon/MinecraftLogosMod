package dev.logos.runtime;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.item.SpellBag;
import dev.logos.item.StaffItem;
import dev.logos.item.WandCharmItem;
import dev.logos.lang.Element;
import dev.logos.lang.Node;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 储能: a staff holds mana of its own. While left-click is held, mana flows from the player into the
 * staff in their main hand as it flows into a charge ({@code flow + surge·t} per second, see
 * {@link Charging}), until the staff is full; letting go keeps what went in. Right-click then casts the
 * selected spell at once, paid out of the staff. Filling looks like charging the selected spell, its
 * grade by how much the staff holds.
 * <p>A staff carries all its pages, and every write to it sends the whole of it to its holder and to everyone
 * tracking them. So what a fill pays waits in the {@link Fill} and reaches the item once, when the fill ends
 * (or the staff fills up, or is cast from); ask {@link #held} for what a staff holds meanwhile.
 */
public final class StaffCharging {
    private StaffCharging() {}

    /** A fill under way: the very stack it fills, how fast it flows, how many ticks it has paid for, and what those paid that the stack does not show yet. */
    public static final class Fill {
        final ItemStack staff;
        final Charging.Rate rate;
        int done;
        double pending;

        Fill(ItemStack staff, Charging.Rate rate) {
            this.staff = staff;
            this.rate = rate;
        }
    }

    /** The mana {@code staff} holds, counting what {@code player} has paid into it and is not written to it yet. */
    public static double held(ServerPlayer player, ItemStack staff) {
        return StaffItem.stored(staff) + pending(player, staff);
    }

    private static double pending(ServerPlayer player, ItemStack staff) {
        Fill f = PlayerMagic.of(player).fill;
        return f != null && f.staff == staff ? f.pending : 0;
    }

    /**
     * Writes what the fill has paid to its stack if the player still has that very stack; if not (moved as a
     * copy, replaced) it goes back to the pool, so it can neither vanish nor land on any other staff.
     */
    private static void settle(ServerPlayer player, Fill f) {
        if (f.pending <= 0) return;
        if (carries(player, f.staff)) {
            StaffItem.store(f.staff, StaffItem.stored(f.staff) + f.pending);
        } else {
            PlayerMagic.refill(player, f.pending);
        }
        f.pending = 0;
    }

    /** By identity: moving a stack in a menu usually leaves an empty husk of the old one behind and a copy elsewhere. */
    private static boolean carries(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (player.containerMenu.getCarried() == stack) return true;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i) == stack) return true;
        return false;
    }

    /** Left-click went down with a staff in the main hand: starts filling it. */
    public static void start(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        ItemStack staff = player.getMainHandItem();
        if (m.fill != null || !(staff.getItem() instanceof StaffItem)) return;
        Charging.Rate rate = Charging.rate(player, staff);
        if (rate == null) return;
        m.fill = new Fill(staff, rate);
        sync(player, staff);
        Vfx.sound(player.level(), player.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5f, 0.8f);
    }

    /** Left-click let go (or the staff left the hand): what went in stays in the staff. */
    public static void stop(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        if (m.fill == null) return;
        settle(player, m.fill);
        m.fill = null;
        Charging.hide(player);
    }

    /**
     * Each player tick: pays the next share towards the main-hand staff, or stops if it is no longer there.
     * Another staff taken up with the button still down starts a fill of its own.
     */
    public static void tick(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        Fill f = m.fill;
        if (f == null) return;
        ItemStack staff = player.getMainHandItem();
        if (!player.isAlive() || staff != f.staff || !(staff.getItem() instanceof StaffItem item)) {
            stop(player);
            if (player.isAlive()) start(player);
            return;
        }
        ServerLevel level = player.level();
        double stored = held(player, staff);
        if (stored < item.capacity()) {
            double share = Math.min(item.capacity() - stored, f.rate.tick(f.done));
            if (m.mana + 1e-9 >= share) {
                m.mana = Math.max(0, m.mana - share);
                stored += share;
                f.pending += share;
                f.done++;
                boolean full = stored >= item.capacity();
                if (full) settle(player, f);
                if (full) sync(player, staff);
                Charging.chime(level, Charging.tip(player), f.done, (float) (stored / item.capacity()), full);
            }
        }
        if (level.getGameTime() % 2 == 0) sync(player, staff);
    }

    /** Right-click: casts the staff's selected spell at once out of what it holds. False (and tells the player why) if it cannot. */
    public static boolean cast(ServerPlayer player, ItemStack staff, InteractionHand hand) {
        SpellBag.Entry entry = Charging.castable(player, staff, hand);
        return entry != null && cast(player, staff, entry);
    }

    /** Casts {@code entry} at once out of what {@code staff} holds. False (and tells the player why) if it cannot. */
    public static boolean cast(ServerPlayer player, ItemStack staff, SpellBag.Entry entry) {
        if (!Casting.stable(player, entry.spell())) return false;
        Casting.Injection in = Casting.injection(entry.spell(), entry.tier(), Casting.immolation(player, staff));
        Fill f = PlayerMagic.of(player).fill;
        if (f != null && f.staff == staff) settle(player, f);
        double stored = StaffItem.stored(staff);
        if (stored + 1e-9 < in.mana()) {
            PlayerMagic.alert(player, Component.literal("法杖储能不足：《" + entry.spell().name() + "》需要注入 " + Node.fmt(in.mana())
                    + "，杖内 " + Node.fmt(stored) + " · 按住左键充能").withStyle(ChatFormatting.RED));
            return false;
        }
        StaffItem.store(staff, Math.max(0, stored - in.mana()));
        Charging.discharge(player, entry.element(), Charging.tier(in.mana()));
        Casting.launch(player, entry, in, null, null, true);
        return true;
    }

    /**
     * Right-click held, {@code ticks} after the first cast: with a chant charm on the staff it casts again every
     * shortest charge time ({@code chargeMinTicks}), until the staff runs short and is let go.
     */
    public static void repeat(ServerPlayer player, ItemStack staff, int ticks) {
        if (ticks <= 0 || ticks % Math.max(1, LogosConfig.chargeMinTicks()) != 0 || !WandCharmItem.on(staff, LogosMod.CHANT_CHARM.get())) return;
        // With 言出法随 it repeats the spell that was named. While still listening, or once the staff runs
        // short, the press stays down idle until let go, so a held right-click does not listen again.
        if (VoiceCasting.armed(staff)) {
            SpellBag.Entry spoken = VoiceCasting.chanting(player, staff);
            if (spoken != null && !cast(player, staff, spoken)) PlayerMagic.of(player).chanting = null;
            return;
        }
        if (!cast(player, staff, InteractionHand.MAIN_HAND)) player.stopUsingItem();
    }

    /** Shows the fill as a charge: the selected spell's element (arcane with none), graded and filled by what the staff holds. */
    private static void sync(ServerPlayer player, ItemStack staff) {
        SpellBag.Entry entry = SpellBag.selected(player, staff);
        Element element = entry == null ? Element.ARCANE : entry.element();
        double stored = StaffItem.stored(staff), capacity = ((StaffItem) staff.getItem()).capacity();
        stored += pending(player, staff);
        Charging.show(player, element, Charging.tier(stored), (float) Math.min(1, stored / capacity), stored >= capacity);
    }
}
