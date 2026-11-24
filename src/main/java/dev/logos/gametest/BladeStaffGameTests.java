package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.BladeStaffItem;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.WandItem;
import dev.logos.lang.Element;
import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Charging;
import dev.logos.runtime.Effects;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellModifiers;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.WrapObj;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;

/** 杖剑: a weaker wand that cuts like a sword, takes sword and wand enchantments, and favours mana wraps. */
final class BladeStaffGameTests {
    private BladeStaffGameTests() {}

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 1.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = 1000;
        m.mana = 100000;
        m.trace = false;
        return p;
    }

    /** Puts {@code stack} in the main hand with its modifiers applied, as the next equipment update would. */
    private static void wield(ServerPlayer p, ItemStack stack) {
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.forEachModifier(EquipmentSlot.MAINHAND, (a, modifier) -> p.getAttribute(a).addOrUpdateTransientModifier(modifier));
    }

    private static double modifier(ItemStack stack, Holder<Attribute> attribute) {
        double[] value = {0};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (a, m) -> {
            if (a.equals(attribute)) value[0] += m.amount();
        });
        return value[0];
    }

    private static void near(GameTestHelper h, double actual, double expected, String message) {
        h.assertTrue(Math.abs(actual - expected) < 1e-6, message + ": expected " + expected + ", got " + actual);
    }

    /** The same-tier wand: the oak wand at tier 1, then the tier wands. */
    private static WandItem wandOfTier(int i) {
        return i == 0 ? LogosMod.WOOD_WANDS.getFirst().get() : LogosMod.TIER_WANDS.get(i - 1).get();
    }

    /**
     * Every tier has fewer slots and less flow, surge, mana cap and refill than its wand, sword damage and speed,
     * and charges a same-grade page and casts it on release like a wand.
     */
    static void weakerWandWithAnEdge(GameTestHelper h) {
        h.assertTrue(LogosMod.BLADE_STAFFS.size() == 8, "tiers 1 through 8 must all exist");
        double lastDamage = 0;
        for (int i = 0; i < 8; i++) {
            BladeStaffItem item = LogosMod.BLADE_STAFFS.get(i).get();
            WandItem wand = wandOfTier(i);
            ItemStack stack = new ItemStack(item), wandStack = new ItemStack(wand);
            String tier = "tier " + (i + 1);
            h.assertTrue(item.slots() < wand.slots(), tier + " should hold fewer pages than its wand");
            for (var a : List.of(LogosMod.CHANNEL_FLOW, LogosMod.CHANNEL_SURGE)) {
                h.assertTrue(modifier(stack, a) < modifier(wandStack, a), tier + " should gather slower than its wand");
            }
            double regen = modifier(stack, SpellModifiers.MANA_REGENERATION), wandRegen = modifier(wandStack, SpellModifiers.MANA_REGENERATION);
            h.assertTrue(regen < wandRegen || wandRegen == 0 && regen == 0, tier + " should refill slower than its wand");
            double cap = WandItem.carriedMaxMana(stack), wandCap = WandItem.carriedMaxMana(wandStack);
            h.assertTrue(cap < wandCap || wandCap == 0 && cap == 0, tier + " should raise the cap less than its wand");
            double damage = 1 + modifier(stack, Attributes.ATTACK_DAMAGE);
            h.assertTrue(damage >= 4 && damage > lastDamage, tier + " should cut at least like a wooden sword and better than the tier below, " + damage);
            lastDamage = damage;
            near(h, 4 + modifier(stack, Attributes.ATTACK_SPEED), BladeStaffItem.ATTACK_SPEED, tier + " attack speed");
            h.assertTrue(item.canPerformAction(stack, ItemAbilities.SWORD_SWEEP), tier + " should sweep");
        }

        ServerPlayer p = player(h);
        BladeStaffItem oak = LogosMod.BLADE_STAFFS.getFirst().get();
        ItemStack stack = new ItemStack(oak);
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed(PageTier.BASIC, "(spell :mana 50 (mark caster #blade_staff))", null))));
        wield(p, stack);
        h.assertTrue(oak.use(h.getLevel(), p, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME, "the blade staff refused its page");
        var charge = Charging.of(p);
        h.assertTrue(charge != null, "the blade staff did not start charging");
        for (int tick = 0; tick < charge.ticks; tick++) oak.onUseTick(h.getLevel(), p, stack, p.getUseItemRemainingTicks());
        h.assertTrue(charge.full(), "the blade staff should be fully charged");
        near(h, StatusData.stacks(p, new Status("#blade_staff")), 0, "must wait for release");
        p.releaseUsingItem();
        near(h, StatusData.stacks(p, new Status("#blade_staff")), 1, "the blade staff casts on release");
        h.succeed();
    }

    /** Sword enchantments (from the table as well as books) and the wand's 焚式 go on; durability ones do not. */
    static void swordAndWandEnchantments(GameTestHelper h) {
        var enchantments = h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack stack = new ItemStack(LogosMod.BLADE_STAFFS.get(3).get());
        for (ResourceKey<Enchantment> key : List.of(Enchantments.SHARPNESS, Enchantments.SMITE, Enchantments.BANE_OF_ARTHROPODS,
                Enchantments.KNOCKBACK, Enchantments.FIRE_ASPECT, Enchantments.LOOTING, Enchantments.SWEEPING_EDGE)) {
            Enchantment e = enchantments.getOrThrow(key).value();
            h.assertTrue(e.canEnchant(stack), key.identifier() + " should go on a blade staff");
            h.assertTrue(e.isPrimaryItem(stack), key.identifier() + " should be offered by the enchanting table");
        }
        h.assertTrue(enchantments.getOrThrow(LogosMod.FORMULA_IMMOLATION).value().canEnchant(stack), "焚式 should go on a blade staff");
        h.assertTrue(!enchantments.getOrThrow(Enchantments.UNBREAKING).value().canEnchant(stack), "a blade staff does not wear out");
        h.assertTrue(stack.isEnchantable(), "a blade staff should take enchantments at the table");
        h.succeed();
    }

    /**
     * Cast with a blade staff in the main hand, a wrap costs 30% less (the imbue rune and what its events set off)
     * and its damage runes hit 30% harder; damage outside the wrap is not raised, and a plain wand changes neither.
     */
    static void favoursWraps(GameTestHelper h) {
        ServerPlayer p = player(h);
        wield(p, new ItemStack(LogosMod.BLADE_STAFFS.getFirst().get()));
        near(h, p.getAttributeValue(SpellModifiers.WRAP_COST), 1 - BladeStaffItem.WRAP_DISCOUNT, "wrap cost with a blade staff");
        near(h, p.getAttributeValue(SpellModifiers.WRAP_POWER), 1 + BladeStaffItem.WRAP_BONUS, "wrap power with a blade staff");

        var wrapped = h.spawn(EntityType.HUSK, new Vec3(4.5, 2, 3.5));
        var plain = h.spawn(EntityType.HUSK, new Vec3(1.5, 2, 6.5));
        var hit = h.spawn(EntityType.PIG, new Vec3(4.5, 2, 6.5));
        var control = h.spawn(EntityType.PIG, new Vec3(1.5, 2, 8.5));
        var outside = h.spawn(EntityType.PIG, new Vec3(7.5, 2, 8.5));
        for (var m : List.of(wrapped, plain)) {
            m.setNoAi(true);
            m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        for (var m : List.of(hit, control, outside)) m.setNoAi(true);

        var inst = Casting.cast(p, Casting.compile(
                "(spell :mana 30 (imbue (nearest :radius 2.5) :life 10 (on-hit (damage-all (entities :radius 0.5) 3 arcane))))"), null, null);
        double paid = (1 + WrapObj.DEPLOY) * 0.7;
        h.assertTrue(inst != null && Math.abs(inst.mana - (30 - paid)) < 1e-6, "the wrap should cost " + paid + ", left " + (inst == null ? "-" : inst.mana));
        float out0 = outside.getHealth();
        Effects.damage(inst, outside, 3, Element.ARCANE);
        near(h, out0 - outside.getHealth(), 3, "damage outside the wrap");
        float hit0 = hit.getHealth(), control0 = control.getHealth();
        double before = inst.mana;
        wrapped.doHurtTarget(h.getLevel(), hit);
        plain.doHurtTarget(h.getLevel(), control);

        ServerPlayer withWand = player(h);
        wield(withWand, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
        near(h, withWand.getAttributeValue(SpellModifiers.WRAP_COST), 1, "wrap cost with a plain wand");
        near(h, withWand.getAttributeValue(SpellModifiers.WRAP_POWER), 1, "wrap power with a plain wand");
        h.succeedWhen(() -> {
            float extra = (hit0 - hit.getHealth()) - (control0 - control.getHealth());
            h.assertTrue(Math.abs(extra - 3.9) < 1e-3, "the wrapped group damage should deal 3.9, dealt " + extra);
            // The group damage and the entities it reads cost 21 at full price.
            double cost = 21 * (1 - BladeStaffItem.WRAP_DISCOUNT);
            h.assertTrue(Math.abs((before - inst.mana) - cost) < 1e-6, "the on-hit runes should cost " + cost + ", cost " + (before - inst.mana));
        });
    }

    private static ItemStack craft(GameTestHelper h, List<ItemStack> items) {
        net.minecraft.world.item.crafting.CraftingInput input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3,
                java.util.stream.Stream.concat(items.stream(), java.util.stream.Stream.generate(() -> ItemStack.EMPTY)).limit(9).toList());
        return h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, h.getLevel())
                .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
    }

    private static ItemStack of(net.minecraft.world.level.ItemLike item) {
        return new ItemStack(item);
    }

    /**
     * Every survival blade staff is made two ways: from the staff of its grade, or from the blade staff below
     * with that grade's materials (a wooden one from any wooden staff and a wooden sword). Upgrading keeps
     * the pages and enchantments.
     */
    static void craftedEveryTier(GameTestHelper h) {
        var b = LogosMod.BLADE_STAFFS;
        record Way(ItemStack made, int tier, String how) {}
        List<Way> ways = List.of(
                new Way(craft(h, List.of(of(LogosMod.WOOD_STAFFS.get(3).get()), of(net.minecraft.world.item.Items.WOODEN_SWORD))), 0, "a wooden staff and a wooden sword"),
                new Way(craft(h, List.of(of(LogosMod.CRYSTAL_STAFF.get()), of(net.minecraft.world.item.Items.STONE_SWORD))), 1, "the crystal staff"),
                new Way(craft(h, List.of(of(b.get(0).get()), of(net.minecraft.world.item.Items.AMETHYST_SHARD), of(net.minecraft.world.item.Items.STONE_SWORD))), 1, "the oak blade staff"),
                new Way(craft(h, List.of(of(LogosMod.ECHO_STAFF.get()), of(LogosMod.LIVING_COPPER_INGOT.get()), of(LogosMod.LIVING_COPPER_INGOT.get()))), 2, "the echo staff"),
                new Way(craft(h, List.of(of(b.get(1).get()), of(LogosMod.ECHO_TINCTURE.get()), of(LogosMod.LIVING_COPPER_INGOT.get()), of(LogosMod.LIVING_COPPER_INGOT.get()))), 2, "the crystal blade staff"),
                new Way(craft(h, List.of(of(LogosMod.TRANSMUTED_STAFF.get()), of(LogosMod.LIVING_IRON_INGOT.get()), of(LogosMod.LIVING_IRON_INGOT.get()))), 3, "the transmuted staff"),
                new Way(craft(h, List.of(of(b.get(2).get()), of(LogosMod.RAVEN_HEAD.get()), of(LogosMod.LIVING_IRON_INGOT.get()), of(LogosMod.LIVING_IRON_INGOT.get()))), 3, "the echo blade staff"),
                new Way(craft(h, List.of(of(LogosMod.CHALK_STAFF.get()), of(LogosMod.LIVING_GOLD_INGOT.get()), of(LogosMod.LIVING_GOLD_INGOT.get()))), 4, "the chalk staff"),
                new Way(craft(h, List.of(of(b.get(3).get()), of(LogosMod.CHALK.get()), of(LogosMod.LIVING_GOLD_INGOT.get()), of(LogosMod.LIVING_GOLD_INGOT.get()),
                        of(net.minecraft.world.item.Items.QUARTZ))), 4, "the transmuted blade staff"),
                new Way(craft(h, List.of(of(LogosMod.FIRMAMENT_STAFF.get()), of(LogosMod.LIVING_GOLD_INGOT.get()), of(LogosMod.LIVING_GOLD_INGOT.get()))), 5, "the firmament staff"),
                new Way(craft(h, List.of(of(b.get(4).get()), of(LogosMod.FIRMAMENT_STONE.get()), of(LogosMod.LIVING_GOLD_INGOT.get()), of(LogosMod.LIVING_GOLD_INGOT.get()),
                        of(net.minecraft.world.item.Items.DIAMOND))), 5, "the chalk blade staff"),
                new Way(craft(h, List.of(of(LogosMod.SAGE_STAFF.get()), of(LogosMod.LIVING_NETHERITE_INGOT.get()))), 6, "the sage staff"),
                new Way(craft(h, List.of(of(LogosMod.SAGE_UPGRADE_TEMPLATE.get()), of(b.get(5).get()), of(LogosMod.LIVING_NETHERITE_INGOT.get()),
                        of(LogosMod.LIVING_NETHERITE_INGOT.get()))), 6, "the firmament blade staff"));
        for (Way w : ways) {
            h.assertTrue(w.made.is(b.get(w.tier).get()), "crafting " + w.how + " should make the " + b.get(w.tier).getId().getPath() + ", made " + w.made);
        }

        ItemStack old = of(b.get(2).get());
        ItemStack page = SpellPages.inscribed(PageTier.BASIC, "(spell :mana 50 (mark caster #blade_staff))", null);
        old.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(page, page.copy())));
        old.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 2);
        ItemStack up = craft(h, List.of(old, of(LogosMod.RAVEN_HEAD.get()), of(LogosMod.LIVING_IRON_INGOT.get()), of(LogosMod.LIVING_IRON_INGOT.get())));
        h.assertTrue(up.is(b.get(3).get()) && dev.logos.item.PageContainer.pages(up).size() == 2 && up.isEnchanted(),
                "the upgrade should keep the pages and the enchantment, made " + up);
        h.succeed();
    }

    /**
     * A blade staff has one slot fewer than the staff it is made from: a full oak staff's pages are packed
     * into the oak blade staff's two slots and the third is handed back where the staff lay; the staff's
     * stored mana does not carry over.
     */
    static void staffPageHandedBack(GameTestHelper h) {
        ItemStack staff = of(LogosMod.WOOD_STAFFS.getFirst().get());
        ItemStack first = SpellPages.inscribed(PageTier.BASIC, "(spell :mana 10 (mark caster #a))", null);
        ItemStack second = SpellPages.inscribed(PageTier.BASIC, "(spell :mana 10 (mark caster #b))", null);
        ItemStack third = SpellPages.inscribed(PageTier.BASIC, "(spell :mana 10 (mark caster #c))", null);
        staff.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(first, ItemStack.EMPTY, second, third)));
        staff.set(LogosMod.STORED_MANA.get(), 120.0);
        var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 1, List.of(staff, of(net.minecraft.world.item.Items.WOODEN_SWORD)));
        var recipe = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, h.getLevel()).orElseThrow().value();
        ItemStack made = recipe.assemble(input);
        List<ItemStack> kept = dev.logos.item.PageContainer.pages(made);
        h.assertTrue(made.is(LogosMod.BLADE_STAFFS.getFirst().get()) && kept.size() == 2
                && ItemStack.matches(kept.get(0), first) && ItemStack.matches(kept.get(1), second), "the blade staff should keep the first two pages, kept " + kept);
        h.assertTrue(!made.has(LogosMod.STORED_MANA.get()), "the staff's stored mana should not carry over");
        var left = recipe.getRemainingItems(input);
        h.assertTrue(ItemStack.matches(left.get(0), third), "the third page should come back where the staff lay, got " + left.get(0));
        h.succeed();
    }
}
