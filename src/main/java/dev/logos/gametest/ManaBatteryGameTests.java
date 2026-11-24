package dev.logos.gametest;

import java.util.List;
import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlockEntity;
import dev.logos.block.ManaStorageTankBlockEntity;
import dev.logos.item.ManaBatteryItem;
import dev.logos.item.ManaBatteryRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** 魔力电池: burnt in a tank it leaves its casing one use more worn; sealing keeps the wear; salvage gives back less; every battery pays for itself without any ingredient that gives mana back. */
final class ManaBatteryGameTests {
    private ManaBatteryGameTests() {}

    private static ItemStack battery(Item item, int wear) {
        ItemStack stack = new ItemStack(item);
        stack.set(LogosMod.CASING_WEAR.get(), wear);
        return stack;
    }

    private static ItemStack craft(GameTestHelper h, CraftingInput input) {
        return h.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel())
                .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
    }

    /**
     * A basic battery whose casing had two uses gives the tank 1000 and leaves the casing at 3 of 4 in the output;
     * the next waits while the output is full; one on its casing's last use leaves nothing.
     */
    static void tankLeavesCasing(GameTestHelper h) {
        BlockPos at = new BlockPos(1, 2, 1);
        h.setBlock(at, LogosMod.MANA_STORAGE_TANK.get());
        ManaStorageTankBlockEntity tank = (ManaStorageTankBlockEntity) h.getBlockEntity(at, ManaStorageTankBlockEntity.class);
        tank.input().set(0, ItemResource.of(battery(LogosMod.BASIC_MANA_BATTERY.get(), 2)), 1);
        tank.work(h.getLevel(), h.absolutePos(at));
        ItemStack shell = tank.output().getResource(0).toStack(tank.output().getAmountAsInt(0));
        h.assertTrue(tank.mana() == 1000, "a basic battery should give 1000, the tank holds " + tank.mana());
        h.assertTrue(shell.is(LogosMod.BASIC_MANA_CASING.get()) && shell.getDamageValue() == 3, "the empty casing should be left 3 worn, got " + shell);

        tank.input().set(0, ItemResource.of(battery(LogosMod.BASIC_MANA_BATTERY.get(), 3)), 1);
        tank.work(h.getLevel(), h.absolutePos(at));
        h.assertTrue(tank.mana() == 1000 && tank.input().getAmountAsInt(0) == 1, "with the output full the battery should wait");
        tank.output().set(0, ItemResource.EMPTY, 0);
        tank.work(h.getLevel(), h.absolutePos(at));
        h.assertTrue(tank.mana() == 2000, "the battery should burn once the output is free, the tank holds " + tank.mana());
        h.assertTrue(tank.output().getAmountAsInt(0) == 0, "a casing on its last use should be used up with the battery");
        h.succeed();
    }

    /** Sealed in a casing worn once, the battery remembers it; salvaging gives 2 copper new, 1 when nearly worn out. */
    static void craftingKeepsWear(GameTestHelper h) {
        ItemStack casing = new ItemStack(LogosMod.BASIC_MANA_CASING.get());
        casing.setDamageValue(1);
        List<ItemStack> in = new java.util.ArrayList<>(List.of(casing, new ItemStack(Items.BLAZE_POWDER)));
        for (int i = 0; i < 3; i++) {
            in.add(new ItemStack(Items.AMETHYST_SHARD));
            in.add(new ItemStack(Items.LAPIS_LAZULI));
        }
        ItemStack out = craft(h, CraftingInput.of(3, 3, in));
        h.assertTrue(out.is(LogosMod.BASIC_MANA_BATTERY.get()) && out.getOrDefault(LogosMod.CASING_WEAR.get(), 0) == 1,
                "the battery should carry its casing's wear of 1, got " + out);

        ItemStack fresh = craft(h, CraftingInput.of(1, 1, List.of(new ItemStack(LogosMod.BASIC_MANA_CASING.get()))));
        h.assertTrue(fresh.is(Items.COPPER_INGOT) && fresh.getCount() == 2, "a new basic casing should salvage to 2 copper, got " + fresh);
        ItemStack worn = new ItemStack(LogosMod.BASIC_MANA_CASING.get());
        worn.setDamageValue(3);
        ItemStack back = craft(h, CraftingInput.of(1, 1, List.of(worn)));
        h.assertTrue(back.is(Items.COPPER_INGOT) && back.getCount() == 1, "a casing with one use left should salvage to 1 copper, got " + back);
        h.succeed();
    }

    /**
     * Every battery recipe: the mana fuel its ingredients would give burnt as they are is under half the battery's,
     * and no ingredient is solid mana, an elixir or a battery, which the mana it gives could make again.
     */
    static void paysWithoutLoop(GameTestHelper h) {
        Set<Item> givesBack = new java.util.HashSet<>(Set.of(LogosMod.TAINTED_MANA_SHARD.get(), LogosMod.MANA_SHARD.get(),
                LogosMod.MANA_CRYSTAL.get(), LogosMod.MANA_GEMSTONE.get()));
        LogosMod.MANA_ELIXIRS.values().forEach(e -> givesBack.add(e.get()));
        LogosMod.ANCIENT_MANA_ELIXIRS.values().forEach(e -> givesBack.add(e.get()));
        int found = 0;
        for (var holder : h.getLevel().recipeAccess().recipeMap().byType(RecipeType.CRAFTING)) {
            if (!(holder.value() instanceof ManaBatteryRecipe r)) continue;
            found++;
            ItemStack battery = r.result().create();
            int cost = 0;
            for (var ing : r.ingredients()) {
                Item item = ing.items().findFirst().orElseThrow().value();
                h.assertTrue(!givesBack.contains(item) && !(item instanceof ManaBatteryItem), battery + " must not take " + item);
                cost += ing.items().mapToInt(i -> AlchemyTableBlockEntity.fuelValue(new ItemStack(i))).max().orElse(0);
            }
            int value = AlchemyTableBlockEntity.fuelValue(battery);
            h.assertTrue(value > 0 && cost * 2 < value, battery + " gives " + value + " for ingredients worth " + cost);
        }
        h.assertTrue(found == 3, "expected three battery recipes, found " + found);
        h.succeed();
    }
}
