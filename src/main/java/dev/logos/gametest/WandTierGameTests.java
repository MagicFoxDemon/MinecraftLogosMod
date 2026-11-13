package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.item.WandItem;
import dev.logos.lang.Status;
import dev.logos.runtime.Charging;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StaffCharging;
import dev.logos.runtime.StatusData;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.core.component.DataComponents;

/** The registered tier items use real equipment attributes, page slots and the normal casting paths. */
final class WandTierGameTests {
    private static final double[] FLOW = {55, 70, 90, 110, 135, 160, 300};
    private static final double[] SURGE = {60, 130, 184, 282, 445, 720, 6100};
    private static final int[] SLOTS = {3, 5, 6, 8, 9, 11, 12};

    private WandTierGameTests() {}

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        var pos = h.absoluteVec(new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        var m = PlayerMagic.of(p);
        m.maxMana = 1000;
        m.mana = 100000;
        m.trace = false;
        return p;
    }

    private static void near(GameTestHelper h, double actual, double expected, String message) {
        h.assertTrue(Math.abs(actual - expected) < 1e-6, message + ": expected " + expected + ", got " + actual);
    }

    private static double channel(ItemStack stack, Holder<Attribute> attribute) {
        double[] value = {0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (a, modifier) -> {
            if (a.equals(attribute)) value[0] += modifier.amount();
        });
        return value[0];
    }

    private static ItemStack carrying(WandItem item, PageTier tier, String mark) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed(tier, "(spell :mana " + tier.capacity() + " (mark caster #" + mark + "))", null))));
        return stack;
    }

    /** Each new wand charges a full same-grade page at its specified rate, then casts only on release. */
    static void wandsChargeAndCast(GameTestHelper h) {
        h.assertTrue(LogosMod.TIER_WANDS.size() == 7, "tiers 2 through 8 must all exist");
        for (int i = 0; i < 7; i++) {
            ServerPlayer p = player(h);
            var tier = PageTier.values()[i + 1];
            var item = LogosMod.TIER_WANDS.get(i).get();
            h.assertTrue(item.slots() == SLOTS[i], "wrong slot count for tier " + (i + 2));
            ItemStack stack = carrying(item, tier, "tier_wand");
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            near(h, channel(stack, LogosMod.CHANNEL_FLOW), FLOW[i], "tier " + (i + 2) + " flow");
            near(h, channel(stack, LogosMod.CHANNEL_SURGE), SURGE[i], "tier " + (i + 2) + " surge");
            h.assertTrue(item.use(h.getLevel(), p, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME, "tier " + (i + 2) + " refused its page");
            var charge = Charging.of(p);
            h.assertTrue(charge != null, "the new wand did not start charging");
            int expected = Charging.ticks(tier.capacity(), FLOW[i], SURGE[i] * (1 + 1000 / LogosConfig.chargeManaScale()));
            h.assertTrue(charge.ticks == expected, "tier " + (i + 2) + " charged at a different rate");
            for (int tick = 0; tick < expected; tick++) item.onUseTick(h.getLevel(), p, stack, p.getUseItemRemainingTicks());
            h.assertTrue(charge.full(), "tier " + (i + 2) + " should be fully charged");
            near(h, charge.injected, tier.capacity(), "same-grade injection");
            near(h, StatusData.stacks(p, new Status("#tier_wand")), 0, "must wait for release");
            p.releaseUsingItem();
            near(h, StatusData.stacks(p, new Status("#tier_wand")), 1, "new wand casts on release");
            near(h, PlayerMagic.of(p).mana, 100000 - tier.capacity(), "the pool pays exactly the page's injection");
        }
        h.succeed();
    }

    /** Each new staff stores its own mana and pays for an immediate cast without charging the player again. */
    static void staffsFillAndCast(GameTestHelper h) {
        h.assertTrue(LogosMod.TIER_STAFFS.size() == 7, "tiers 2 through 8 must all exist");
        for (int i = 0; i < 7; i++) {
            ServerPlayer p = player(h);
            var tier = PageTier.values()[i + 1];
            StaffItem item = LogosMod.TIER_STAFFS.get(i).get();
            h.assertTrue(item.slots() == SLOTS[i], "staff and wand slots must match at tier " + (i + 2));
            ItemStack stack = carrying(item, tier, "tier_staff");
            near(h, item.capacity(), tier.capacity() * 3, "staff stores three same-grade injections");
            double flow = channel(stack, LogosMod.CHANNEL_FLOW);
            double surge = channel(stack, LogosMod.CHANNEL_SURGE);
            h.assertTrue(flow < FLOW[i] && surge < SURGE[i], "staff gathering must be slower than the same-grade wand");
            double fillSeconds = Charging.seconds(item.capacity(), flow, surge);
            double threeCharges = 3 * Charging.seconds(tier.capacity(), FLOW[i], SURGE[i]);
            h.assertTrue(Math.abs(fillSeconds - threeCharges) < 0.05, "staff fill should match three separate wand charges: " + fillSeconds + " vs " + threeCharges);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            h.assertTrue(item.use(h.getLevel(), p, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "an empty staff cast");
            StaffCharging.start(p);
            for (int tick = 0; tick < 400 && StaffCharging.held(p, stack) < item.capacity(); tick++) StaffCharging.tick(p);
            StaffCharging.stop(p);
            near(h, StaffItem.stored(stack), item.capacity(), "the staff fills to its own capacity");
            h.assertTrue(item.isBarVisible(stack) && item.getBarWidth(stack) == 13, "full staff must show a full mana bar");
            double paid = PlayerMagic.of(p).mana;
            near(h, paid, 100000 - item.capacity(), "filling pays exactly the stored mana");
            h.assertTrue(item.use(h.getLevel(), p, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME, "a full tier staff refused its page");
            near(h, StatusData.stacks(p, new Status("#tier_staff")), 1, "staff casts immediately");
            near(h, StaffItem.stored(stack), item.capacity() - tier.capacity(), "staff supplies the injection");
            near(h, PlayerMagic.of(p).mana, paid, "casting does not take from the player's pool again");
            p.releaseUsingItem();
        }
        h.succeed();
    }

    /** The last slot survives closing and reopening, including tiers with more than one UI row. */
    static void lastSlotPersists(GameTestHelper h) {
        var items = new java.util.ArrayList<WandItem>();
        LogosMod.TIER_WANDS.forEach(w -> items.add(w.get()));
        LogosMod.TIER_STAFFS.forEach(s -> items.add(s.get()));
        for (WandItem item : items) {
            ItemStack stack = new ItemStack(item);
            int last = item.slots() - 1;
            var container = new PageContainer(stack, item.slots(), true);
            container.setItem(last, new ItemStack(LogosMod.CHANT_CHARM.get()));
            h.assertTrue(PageContainer.get(stack, last).is(LogosMod.CHANT_CHARM.get()), "the final slot was not saved");
            var reopened = new PageContainer(stack, item.slots(), true);
            h.assertTrue(reopened.getContainerSize() == item.slots() && reopened.getItem(last).is(LogosMod.CHANT_CHARM.get()), "the last slot disappeared on reopen");
        }
        h.succeed();
    }
}
