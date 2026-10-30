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
import net.minecraft.world.item.ItemStack;

/**
 * 储能: a staff holds mana of its own. While left-click is held, mana flows from the player into the
 * staff in their main hand as it flows into a charge ({@code flow + surge·t} per second, see
 * {@link Charging}), until the staff is full; letting go keeps what went in. Right-click then casts the
 * selected spell at once, paid out of the staff. Filling looks like charging the selected spell, its
 * grade by how much the staff holds.
 */
public final class StaffCharging {
    private StaffCharging() {}

    /** A fill under way: how fast it flows, and how many ticks it has paid for. */
    public static final class Fill {
        final Charging.Rate rate;
        int done;

        Fill(Charging.Rate rate) {
            this.rate = rate;
        }
    }

    /** Left-click went down with a staff in the main hand: starts filling it. */
    public static void start(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        ItemStack staff = player.getMainHandItem();
        if (m.fill != null || !(staff.getItem() instanceof StaffItem)) return;
        Charging.Rate rate = Charging.rate(player, staff);
        if (rate == null) return;
        m.fill = new Fill(rate);
        sync(player, staff);
        Vfx.sound(player.level(), player.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5f, 0.8f);
    }

    /** Left-click let go (or the staff left the hand): what went in stays in the staff. */
    public static void stop(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        if (m.fill == null) return;
        m.fill = null;
        Charging.hide(player);
    }

    /** Each player tick: pays the next share into the main-hand staff, or stops if it is no longer there. */
    public static void tick(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        Fill f = m.fill;
        if (f == null) return;
        ItemStack staff = player.getMainHandItem();
        if (!player.isAlive() || !(staff.getItem() instanceof StaffItem item)) {
            stop(player);
            return;
        }
        ServerLevel level = player.level();
        double stored = StaffItem.stored(staff);
        if (stored < item.capacity()) {
            double share = Math.min(item.capacity() - stored, f.rate.tick(f.done));
            if (m.mana + 1e-9 >= share) {
                m.mana = Math.max(0, m.mana - share);
                stored += share;
                StaffItem.store(staff, stored);
                f.done++;
                boolean full = stored >= item.capacity();
                if (full) sync(player, staff);
                Charging.chime(level, Charging.tip(player), f.done, (float) (stored / item.capacity()), full);
            }
        }
        if (level.getGameTime() % 2 == 0) sync(player, staff);
    }

    /** Right-click: casts the staff's selected spell at once out of what it holds. False (and tells the player why) if it cannot. */
    public static boolean cast(ServerPlayer player, ItemStack staff, InteractionHand hand) {
        SpellBag.Entry entry = Charging.castable(player, staff, hand);
        if (entry == null || !Casting.stable(player, entry.spell())) return false;
        Casting.Injection in = Casting.injection(entry.spell(), entry.tier(), Casting.immolation(player, staff));
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
        if (!cast(player, staff, InteractionHand.MAIN_HAND)) player.stopUsingItem();
    }

    /** Shows the fill as a charge: the selected spell's element (arcane with none), graded and filled by what the staff holds. */
    private static void sync(ServerPlayer player, ItemStack staff) {
        SpellBag.Entry entry = SpellBag.selected(player, staff);
        Element element = entry == null ? Element.ARCANE : entry.element();
        double stored = StaffItem.stored(staff), capacity = ((StaffItem) staff.getItem()).capacity();
        Charging.show(player, element, Charging.tier(stored), (float) Math.min(1, stored / capacity), stored >= capacity);
    }
}
