package dev.logos.runtime;

import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.item.SpellBag;
import dev.logos.item.StaffItem;
import dev.logos.item.WandCharmItem;
import dev.logos.lang.Element;
import dev.logos.lang.Node;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 言出法随: casting by naming a spell aloud. A wand with a 言灵坠 has 言出法随 on its spell wheel; chosen,
 * held right-click listens instead of charging one spell. The player's client hears them and says
 * which page they named; only pages written with voice casting count.
 * <ul>
 * <li>A wand gathers mana while it listens, as a charge does, up to what the dearest voice spell on
 * it injects. Once a spell is named: if enough is in, it goes off at once and what was gathered
 * beyond it goes back to the player's pool; if not, the charge carries on toward it and goes off as
 * soon as it is full.</li>
 * <li>A staff casts the named spell at once from what it holds, or fails if it holds too little.</li>
 * <li>With a chant charm the named spell keeps being cast while right-click stays held; letting go
 * and pressing again listens anew.</li>
 * </ul>
 * Letting go before a spell is named returns what was gathered as free mana, like a broken charge.
 */
public final class VoiceCasting {
    private VoiceCasting() {}

    /** A wand listening: how fast it gathers, how much it may, and what is in so far. A staff gathers nothing. */
    public static final class Listen {
        final boolean staff;
        final Charging.@Nullable Rate rate;
        final double cap;
        int done;
        double injected;

        Listen(boolean staff, Charging.@Nullable Rate rate, double cap) {
            this.staff = staff;
            this.rate = rate;
            this.cap = cap;
        }

        public double injected() {
            return injected;
        }
    }

    /** Whether {@code wand} has 言出法随 chosen on its wheel (and still has the charm that offers it). */
    public static boolean armed(ItemStack wand) {
        return wand.getOrDefault(LogosMod.VOICE_SELECTED.get(), false) && WandCharmItem.on(wand, LogosMod.VOICE_CHARM.get());
    }

    /** The spells 言出法随 can reach from {@code wand}: the active voice pages on it and on an off-hand codex. */
    public static List<SpellBag.Entry> spoken(Player player, ItemStack wand) {
        return SpellBag.entries(player, wand).stream().filter(e -> e.page().has(LogosMod.VOICE.get()) && !e.spell().isPassive()).toList();
    }

    /** {@code page} where it lies now among what {@code wand} reaches, or null if it has gone (moved, burnt, rewritten). */
    static SpellBag.@Nullable Entry find(Player player, ItemStack wand, ItemStack page) {
        for (SpellBag.Entry e : spoken(player, wand)) if (ItemStack.isSameItemSameComponents(e.page(), page)) return e;
        return null;
    }

    public static boolean listening(Player player) {
        return PlayerMagic.of(player).listen != null;
    }

    /** Right-click with 言出法随 chosen: starts listening. False (and tells the player why) if there is nothing to name. */
    public static boolean begin(ServerPlayer player, ItemStack wand, InteractionHand hand) {
        Charging.cancel(player);
        cancel(player);
        if (hand != InteractionHand.MAIN_HAND) {
            PlayerMagic.alert(player, Component.literal("法杖要拿在主手才能施放").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        List<SpellBag.Entry> spells = spoken(player, wand);
        if (spells.isEmpty()) {
            PlayerMagic.alert(player, Component.literal("法杖上没有言出法随的术式：在法式编纂台的「言出法随」页录制咒语，再写入载页").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        PlayerMagic m = PlayerMagic.of(player);
        if (wand.getItem() instanceof StaffItem) {
            m.listen = new Listen(true, null, 0);
        } else {
            Charging.Rate rate = Charging.rate(player, wand);
            if (rate == null) return false;
            double cap = 0;
            for (SpellBag.Entry e : spells) cap = Math.max(cap, Casting.injection(e.spell(), e.tier(), Casting.immolation(player, wand)).mana());
            m.listen = new Listen(false, rate, cap);
            Charging.show(player, Element.ARCANE, 0, 0, false);
        }
        Vfx.sound(player.level(), player.getEyePosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, 0.5f, 1.1f);
        return true;
    }

    /** One tick of listening with a wand: gathers the next share of mana, up to the cap. */
    public static void tick(ServerPlayer player, ItemStack wand) {
        PlayerMagic m = PlayerMagic.of(player);
        Listen l = m.listen;
        if (l == null || l.staff || l.rate == null) return;
        if (!armed(wand)) {
            player.stopUsingItem();
            return;
        }
        if (l.injected < l.cap) {
            double share = Math.min(l.cap - l.injected, l.rate.tick(l.done));
            if (m.mana + 1e-9 >= share) {
                m.mana = Math.max(0, m.mana - share);
                l.injected += share;
                l.done++;
                Charging.chime(player.level(), Charging.tip(player), l.done, (float) (l.injected / l.cap), l.injected >= l.cap);
            }
        }
        if (player.level().getGameTime() % 2 == 0)
            Charging.show(player, Element.ARCANE, Charging.tier(l.injected), (float) Math.min(1, l.cap <= 0 ? 1 : l.injected / l.cap), l.injected >= l.cap);
    }

    /**
     * The player's client heard them name the spell on entry {@code index} of what the main-hand wand
     * reaches ({@code name} is that page's voice name, to be sure both sides mean the same page).
     */
    public static void heard(ServerPlayer player, int index, String name) {
        PlayerMagic m = PlayerMagic.of(player);
        Listen l = m.listen;
        ItemStack wand = player.getMainHandItem();
        if (l == null || !player.isUsingItem() || !armed(wand)) return;
        List<SpellBag.Entry> all = SpellBag.entries(player, wand);
        if (index < 0 || index >= all.size()) return;
        SpellBag.Entry entry = all.get(index);
        if (!name.equals(entry.page().get(LogosMod.VOICE.get())) || entry.spell().isPassive()) return;
        // From here the press is spent: whatever happens, it stays down doing nothing more until let go,
        // so a held right-click does not start listening again (a fresh press does).
        m.listen = null;
        boolean chant = WandCharmItem.on(wand, LogosMod.CHANT_CHARM.get());
        if (l.staff) {
            if (StaffCharging.cast(player, wand, entry) && chant) m.chanting = entry.page();
            return;
        }
        Casting.Injection in = Casting.injection(entry.spell(), entry.tier(), Casting.immolation(player, wand));
        if (l.injected + 1e-9 >= in.mana()) {
            if (!Casting.stable(player, entry.spell())) {
                spill(player, l.injected);
                Charging.hide(player);
                return;
            }
            refund(player, l.injected - in.mana());
            Charging.discharge(player, entry.element(), Charging.tier(in.mana()));
            Casting.launch(player, entry, in, null, null, true);
            if (chant) again(player, wand, entry.page());
            return;
        }
        if (!Charging.beginSpoken(player, wand, entry, l.injected, chant)) {
            spill(player, l.injected);
            Charging.hide(player);
        }
    }

    /** With a chant charm: charges the spoken spell on {@code page} again from nothing, if it is still there. */
    static boolean again(ServerPlayer player, ItemStack wand, ItemStack page) {
        SpellBag.Entry entry = find(player, wand, page);
        return entry != null && Charging.beginSpoken(player, wand, entry, 0, true);
    }

    /** The spoken spell a staff with a chant charm keeps casting, if it is still on the staff. */
    static SpellBag.@Nullable Entry chanting(ServerPlayer player, ItemStack staff) {
        ItemStack page = PlayerMagic.of(player).chanting;
        return page == null ? null : find(player, staff, page);
    }

    /** Right-click let go, or listening otherwise ended: what a wand gathered goes back out as free mana. */
    public static void cancel(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        m.chanting = null;
        Listen l = m.listen;
        if (l == null) return;
        m.listen = null;
        if (l.staff) return;
        Charging.hide(player);
        if (l.injected > 0) {
            spill(player, l.injected);
            PlayerMagic.alert(player, Component.literal("没有念出咒语：已注入的 " + Node.fmt(l.injected) + " 魔力散为游离魔力").withStyle(ChatFormatting.GRAY));
        }
    }

    private static void spill(ServerPlayer player, double mana) {
        if (mana > 0) FreeManaEntity.release(player.level(), player.getEyePosition().add(player.getLookAngle().scale(0.8)), mana);
    }

    /** Mana gathered beyond what the named spell injects goes back into the pool; past a full pool it spills free. */
    private static void refund(ServerPlayer player, double extra) {
        if (extra <= 1e-9) return;
        PlayerMagic m = PlayerMagic.of(player);
        double back = Math.min(extra, Math.max(0, m.maxMana - m.mana));
        m.mana += back;
        spill(player, extra - back);
    }
}
