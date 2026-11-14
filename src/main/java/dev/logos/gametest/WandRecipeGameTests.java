package dev.logos.gametest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.logos.LogosMod;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.item.WandItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipeInput;

/** Loaded survival recipes, their actual prices, component retention and vanilla smithing consumption. */
final class WandRecipeGameTests {
    private WandRecipeGameTests() {}

    private static CraftingInput grid(List<ItemStack> stacks) {
        var slots = new ArrayList<>(stacks);
        while (slots.size() < 9) slots.add(ItemStack.EMPTY);
        return CraftingInput.of(3, 3, slots);
    }

    private static ItemStack craft(GameTestHelper h, List<ItemStack> stacks) {
        var input = grid(stacks);
        return h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
                .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
    }

    private static ItemStack equipped(GameTestHelper h, WandItem item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("My old wand"));
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed(PageTier.BASIC, "(spell :name \"Kept spell\" :mana 5 (heal caster 1))", null),
                new ItemStack(LogosMod.CHANT_CHARM.get()), new ItemStack(LogosMod.VOICE_CHARM.get()))));
        stack.set(LogosMod.SELECTED.get(), 2);
        stack.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(LogosMod.FORMULA_IMMOLATION), 1);
        if (item instanceof StaffItem) StaffItem.store(stack, 73);
        return stack;
    }

    private static void kept(GameTestHelper h, ItemStack before, ItemStack after) {
        h.assertTrue(before.getComponentsPatch().equals(after.getComponentsPatch()), "upgrade lost a page, charm, name, selection, enchantment or stored mana");
        h.assertTrue(after.getCount() == 1, "upgrade duplicated the wand");
        double[] flow = {0};
        after.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(LogosMod.CHANNEL_FLOW)) flow[0] += modifier.amount();
        });
        h.assertTrue(flow[0] > 0, "upgrade discarded the new item's channel attributes");
    }

    static void woodStaffs(GameTestHelper h) {
        for (int i = 0; i < LogosMod.WOOD_WANDS.size(); i++) {
            String wood = LogosMod.WOOD_WANDS.get(i).getId().getPath().replace("_wand", "");
            Item log = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("stripped_" + wood + "_log"));
            ItemStack old = equipped(h, LogosMod.WOOD_WANDS.get(i).get());
            ItemStack result = craft(h, List.of(new ItemStack(Items.GOLD_NUGGET), old, new ItemStack(log), new ItemStack(Items.GOLD_NUGGET)));
            h.assertTrue(result.is(LogosMod.WOOD_STAFFS.get(i).get()), "wrong staff for " + wood);
            kept(h, old, result);
            h.assertTrue(StaffItem.stored(result) == 0, "a newly made wood staff starts charged");
        }
        h.assertTrue(craft(h, List.of(new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()), new ItemStack(Items.SPRUCE_LOG),
                new ItemStack(Items.GOLD_NUGGET), new ItemStack(Items.GOLD_NUGGET))).isEmpty(), "mixed woods should not make a wood staff");
        h.succeed();
    }

    static void chains(GameTestHelper h) {
        Item[] cores = {Items.AMETHYST_SHARD, LogosMod.ECHO_TINCTURE.get(), LogosMod.RAVEN_HEAD.get(), LogosMod.CHALK.get(), LogosMod.FIRMAMENT_STONE.get()};
        Item[] metals = {Items.GOLD_NUGGET, Items.IRON_INGOT, Items.GOLD_INGOT, Items.QUARTZ, Items.DIAMOND};
        for (boolean staff : new boolean[] {false, true}) {
            int[] prices = staff ? new int[] {1, 2, 2, 4, 2} : new int[] {2, 1, 1, 2, 1};
            ItemStack old = equipped(h, staff ? LogosMod.WOOD_STAFFS.get(6).get() : LogosMod.WOOD_WANDS.get(6).get());
            for (int tier = 0; tier < 5; tier++) {
                Item material = staff && tier == 0 ? Items.GOLD_INGOT : metals[tier];
                var ingredients = new ArrayList<ItemStack>();
                ingredients.add(new ItemStack(cores[tier]));
                ingredients.add(old);
                for (int n = 0; n < prices[tier]; n++) ingredients.add(new ItemStack(material));
                // One missing piece, extra filler and a stack in one slot must not pay the recipe's price.
                var missing = new ArrayList<>(ingredients);
                missing.removeLast();
                h.assertTrue(craft(h, missing).isEmpty(), "upgrade accepted too little " + material);
                var noCore = new ArrayList<>(ingredients);
                noCore.removeFirst();
                h.assertTrue(craft(h, noCore).isEmpty(), "upgrade accepted no core");
                var extra = new ArrayList<>(ingredients);
                extra.add(new ItemStack(Items.COBBLESTONE));
                h.assertTrue(craft(h, extra).isEmpty(), "upgrade ignored an extra ingredient");
                if (prices[tier] > 1) h.assertTrue(craft(h, List.of(old, new ItemStack(cores[tier]), new ItemStack(material, prices[tier]))).isEmpty(), "stacked material replaced occupied recipe slots");
                Collections.reverse(ingredients);
                ItemStack result = craft(h, ingredients);
                Item expected = staff ? LogosMod.TIER_STAFFS.get(tier).get() : LogosMod.TIER_WANDS.get(tier).get();
                h.assertTrue(result.is(expected), "wrong result at tier " + (tier + 2));
                kept(h, old, result);
                if (staff) h.assertTrue(StaffItem.stored(result) == 73, "upgrading refilled or lost staff mana");
                var input = grid(ingredients);
                var recipe = h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel()).orElseThrow().value();
                if (tier == 1 || tier == 2) h.assertTrue(recipe.getRemainingItems(input).stream().filter(s -> s.is(Items.GLASS_BOTTLE)).count() == 1, "the alchemical core did not return its bottle");
                old = result;
            }
        }
        h.succeed();
    }

    static void template(GameTestHelper h) {
        ItemStack made = craft(h, List.of(new ItemStack(LogosMod.PHILOSOPHERS_STONE.get()), new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)));
        h.assertTrue(made.is(LogosMod.SAGE_UPGRADE_TEMPLATE.get()) && made.getCount() == 1, "a stone and a netherite template must make exactly one sage template");
        h.assertTrue(craft(h, List.of(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE))).isEmpty(), "sage template did not need a philosopher's stone");
        var duplication = new ArrayList<ItemStack>();
        duplication.add(made);
        duplication.add(new ItemStack(Items.NETHERRACK));
        for (int i = 0; i < 7; i++) duplication.add(new ItemStack(Items.DIAMOND));
        h.assertTrue(craft(h, duplication).isEmpty(), "sage template can be copied without a philosopher's stone");
        h.succeed();
    }

    @SuppressWarnings("removal")
    static void smithing(GameTestHelper h) {
        var player = h.makeMockServerPlayerInLevel();
        for (boolean staff : new boolean[] {false, true}) {
            ItemStack base = equipped(h, staff ? LogosMod.FIRMAMENT_STAFF.get() : LogosMod.MASTER_WAND.get());
            var menu = new SmithingMenu(1, player.getInventory());
            h.assertTrue(menu.getSlot(0).mayPlace(new ItemStack(LogosMod.SAGE_UPGRADE_TEMPLATE.get())), "smithing does not accept the sage template");
            h.assertTrue(menu.getSlot(1).mayPlace(base), "smithing does not accept a firmament staff");
            menu.getSlot(0).set(new ItemStack(LogosMod.SAGE_UPGRADE_TEMPLATE.get(), 2));
            menu.getSlot(1).set(base.copy());
            menu.getSlot(2).set(new ItemStack(Items.NETHERITE_INGOT, 2));
            menu.createResult();
            ItemStack result = menu.getSlot(3).getItem().copy();
            h.assertTrue(result.is(staff ? LogosMod.SAGE_STAFF.get() : LogosMod.SAGE_WAND.get()), "smithing made the wrong sage staff");
            kept(h, base, result);
            menu.getSlot(3).onTake(player, result);
            h.assertTrue(menu.getSlot(0).getItem().getCount() == 1 && menu.getSlot(1).getItem().isEmpty()
                    && menu.getSlot(2).getItem().getCount() == 1, "smithing must consume one template, one base and one ingot");
            h.assertTrue(menu.getSlot(3).getItem().isEmpty(), "smithing left a second free result");
            var vanilla = new SmithingRecipeInput(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), base, new ItemStack(Items.NETHERITE_INGOT));
            h.assertTrue(h.getLevel().recipeAccess().getRecipeFor(RecipeType.SMITHING, vanilla, h.getLevel()).isEmpty(), "vanilla template bypassed the philosopher's stone");
            var missing = new SmithingRecipeInput(ItemStack.EMPTY, base, new ItemStack(Items.NETHERITE_INGOT));
            h.assertTrue(h.getLevel().recipeAccess().getRecipeFor(RecipeType.SMITHING, missing, h.getLevel()).isEmpty(), "smithing needed no template");
        }
        h.succeed();
    }

    static void noShortcuts(GameTestHelper h) {
        h.assertTrue(craft(h, List.of(new ItemStack(LogosMod.WAND.get()), new ItemStack(Items.GOLD_BLOCK))).isEmpty(), "old recipe skips the echo tier");
        h.assertTrue(craft(h, List.of(new ItemStack(LogosMod.ADEPT_WAND.get()), new ItemStack(Items.NETHER_STAR))).isEmpty(), "old recipe skips the chalk tier");
        h.assertTrue(craft(h, List.of(new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()), new ItemStack(LogosMod.ECHO_TINCTURE.get()), new ItemStack(Items.IRON_INGOT))).isEmpty(), "a wood wand skipped crystal");
        for (String id : List.of("principle_wand", "principle_staff")) {
            h.assertTrue(h.getLevel().recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("logos", id))).isEmpty(), "creative tier has a survival recipe");
        }
        h.succeed();
    }
}
