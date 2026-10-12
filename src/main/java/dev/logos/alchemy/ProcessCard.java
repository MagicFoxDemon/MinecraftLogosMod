package dev.logos.alchemy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import dev.logos.LogosMod;
import dev.logos.block.AlchemyTableBlock;
import dev.logos.block.AlchemyTableBlockEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * A process card laid on an alchemy table: one process, nine input slots and nine output slots.
 * An input slot designates an item (putting something in designates it too); the card then works
 * with what lies in its own slots and with the designated items in the table's storage. Like a
 * furnace it starts by itself whenever some recipe of its process can be made, taking two seconds
 * and one mana per craft and the fewest materials. What it makes lands in its outputs and is passed
 * on along its routes: kept, put in the product area, or handed to another card's inputs. Empty
 * bottles always go back to the raw-material area.
 */
public final class ProcessCard {
    public static final int SLOTS = 9;
    /** Two seconds a craft. */
    public static final int CRAFT_TICKS = 40;
    public static final int MANA_PER_CRAFT = 1;
    /** Where outputs go: left in the card, or put in the table's product area; 0 and up are other cards. */
    public static final int KEEP = -2, PRODUCTS = -1;
    /** Containers given back (empty bottles, buckets) are not routed: they return to the raw-material area. */
    private static final int GIVE_BACK = -3;

    public final Process process;
    private final Item[] designated = new Item[SLOTS];
    private final ItemStacksResourceHandler inputs = new ItemStacksResourceHandler(SLOTS) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return designated[index] == null || resource.is(designated[index]);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previous) {
            // Putting something in an undesignated slot designates it.
            ItemResource now = getResource(index);
            if (!now.isEmpty() && designated[index] == null) designated[index] = now.getItem();
            changed.run();
        }
    };
    private final ItemStacksResourceHandler outputs = new ItemStacksResourceHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int index, ItemStack previous) {
            changed.run();
        }
    };
    private final Map<Item, Integer> routes = new HashMap<>();
    private int defaultRoute = KEEP;
    private int progress;
    private boolean working;
    /** Where the card lies on the table screen's canvas, in screen units from its top left. */
    private int x, y;
    private Runnable changed = () -> {};

    public ProcessCard(Process process) {
        this.process = process;
    }

    /** Called whenever what the card holds or how it is set up changes, so the table is saved. */
    public void onChange(Runnable changed) {
        this.changed = changed;
    }

    public ItemStacksResourceHandler inputs() {
        return inputs;
    }

    public ItemStacksResourceHandler outputs() {
        return outputs;
    }

    public @Nullable Item designated(int slot) {
        return designated[slot];
    }

    /** Designates {@code item} for an input slot (null clears it); refused while the slot holds something else. */
    public boolean designate(int slot, @Nullable Item item) {
        ItemResource held = inputs.getResource(slot);
        if (!held.isEmpty() && (item == null || !held.is(item))) return false;
        designated[slot] = item;
        changed.run();
        return true;
    }

    /** Where {@code item} goes once made: {@link #KEEP}, {@link #PRODUCTS} or another card's index. */
    public int route(Item item) {
        return routes.getOrDefault(item, defaultRoute);
    }

    public void setRoute(Item item, int target) {
        routes.put(item, target);
        changed.run();
    }

    public int defaultRoute() {
        return defaultRoute;
    }

    public void setDefaultRoute(int target) {
        defaultRoute = target;
        changed.run();
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
        changed.run();
    }

    /** Ticks into the current craft, 0 while idle. */
    public int progress() {
        return progress;
    }

    /** After card {@code removed} is taken off the table: routes to it fall back to keeping, later cards move down one. */
    public void cardRemoved(int removed) {
        routes.replaceAll((item, target) -> shift(target, removed));
        defaultRoute = shift(defaultRoute, removed);
    }

    private static int shift(int target, int removed) {
        return target == removed ? KEEP : target > removed ? target - 1 : target;
    }

    // ---- working ----------------------------------------------------------------------------------

    /** Whether the card worked on a craft last tick: it had the mana and the materials for one. */
    public boolean working() {
        return working;
    }

    /** One tick: work on the craft if it can be made (else stop), then pass outputs along. True when a craft was finished. */
    public boolean tick(AlchemyTableBlockEntity table) {
        boolean finished = false;
        working = table.mana() >= MANA_PER_CRAFT && craft(table, false) != null;
        if (!working) {
            progress = 0;
        } else if (++progress >= CRAFT_TICKS) {
            finished = craft(table, true) != null;
            if (finished) table.setMana(table.mana() - MANA_PER_CRAFT);
            progress = 0;
        }
        deliver(table);
        return finished;
    }

    /**
     * Makes one of the richest recipe of this process that the card can make now, and returns it;
     * with {@code commit} false nothing changes and this only tells whether one could be made.
     */
    public @Nullable AlchemyRecipe craft(AlchemyTableBlockEntity table, boolean commit) {
        if (!(table.getLevel() instanceof ServerLevel level)) return null;
        List<AlchemyRecipe> recipes = new ArrayList<>();
        for (RecipeHolder<AlchemyRecipe> h : level.recipeAccess().recipeMap().byType(LogosMod.ALCHEMY_RECIPE.get())) {
            if (h.value().process() == process) recipes.add(h.value());
        }
        recipes.sort(Comparator.comparingInt(AlchemyRecipe::kinds).reversed());
        List<Source> sources = sources(table);
        try (Transaction tx = Transaction.openRoot()) {
            for (AlchemyRecipe recipe : recipes) {
                try (Transaction attempt = Transaction.open(tx)) {
                    if (!make(recipe, table, sources, attempt)) continue;
                    attempt.commit();
                }
                if (commit) tx.commit();
                return recipe;
            }
        }
        return null;
    }

    /** A slot the card may take from: its own inputs first, then the table's storage holding designated items. */
    private record Source(ResourceHandler<ItemResource> handler, int index) {
        ItemResource resource() {
            return handler.getResource(index);
        }
    }

    private List<Source> sources(AlchemyTableBlockEntity table) {
        List<Source> out = new ArrayList<>();
        Set<Item> wanted = new HashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            out.add(new Source(inputs, i));
            if (designated[i] != null) wanted.add(designated[i]);
        }
        for (int i = 0; i < table.items().size(); i++) {
            ItemResource r = table.items().getResource(i);
            if (!r.isEmpty() && wanted.contains(r.getItem())) out.add(new Source(table.items(), i));
        }
        return out;
    }

    private static boolean isWaterBottle(ItemResource r) {
        return r.is(Items.POTION) && r.getComponents().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER);
    }

    /**
     * Takes one craft's materials and puts its results in the outputs, inside {@code tx}. The fluid
     * comes from the tank, or else from water bottles, which pour a bottle each (any excess goes into
     * the tank) and free their glass bottles for the recipe's own bottles; freed bottles it does not
     * need go back to the raw-material area.
     */
    private boolean make(AlchemyRecipe recipe, AlchemyTableBlockEntity table, List<Source> sources, TransactionContext tx) {
        int freed = 0;
        if (recipe.fluid().isPresent()) {
            var need = recipe.fluid().get();
            int left = need.amount();
            FluidStack inTank = table.fluid();
            if (!inTank.isEmpty() && need.ingredient().test(inTank)) {
                left -= table.tank().extract(0, table.tank().getResource(0), Math.min(left, inTank.getAmount()), tx);
            }
            if (left > 0 && need.ingredient().test(new FluidStack(Fluids.WATER, AlchemyTableBlock.BOTTLE))) {
                for (Source s : sources) {
                    while (left > 0 && isWaterBottle(s.resource()) && s.handler().extract(s.index(), s.resource(), 1, tx) == 1) {
                        freed++;
                        left -= AlchemyTableBlock.BOTTLE;
                    }
                }
                if (left < 0 && table.tank().insert(FluidResource.of(Fluids.WATER), -left, tx) != -left) return false;
            }
            if (left > 0) return false;
        }
        for (SizedIngredient need : recipe.ingredients()) {
            int left = need.count();
            if (freed > 0 && need.ingredient().test(new ItemStack(Items.GLASS_BOTTLE))) {
                int t = Math.min(left, freed);
                freed -= t;
                left -= t;
            }
            for (Source s : sources) {
                if (left == 0) break;
                ItemResource r = s.resource();
                if (!r.isEmpty() && need.ingredient().test(r.toStack())) left -= s.handler().extract(s.index(), r, left, tx);
            }
            if (left > 0) return false;
        }
        for (ItemStackTemplate result : recipe.results()) {
            ItemStack made = result.create();
            if (outputs.insert(ItemResource.of(made), made.getCount(), tx) != made.getCount()) return false;
        }
        if (freed > 0) {
            freed -= table.rawArea().insert(ItemResource.of(Items.GLASS_BOTTLE), freed, tx);
            if (freed > 0 && outputs.insert(ItemResource.of(Items.GLASS_BOTTLE), freed, tx) != freed) return false;
        }
        return true;
    }

    static boolean isContainer(ItemResource r) {
        return r.is(Items.GLASS_BOTTLE) || r.is(Items.BUCKET);
    }

    /** Passes each output on along its route, as much as fits. */
    private void deliver(AlchemyTableBlockEntity table) {
        for (int i = 0; i < SLOTS; i++) {
            ItemResource r = outputs.getResource(i);
            if (r.isEmpty()) continue;
            int route = isContainer(r) ? GIVE_BACK : route(r.getItem());
            ProcessCard next = route >= 0 && route < table.cards().size() && table.cards().get(route) != this ? table.cards().get(route) : null;
            if (route == KEEP || route >= 0 && next == null) continue;
            try (Transaction tx = Transaction.openRoot()) {
                int amount = outputs.getAmountAsInt(i);
                int moved = route == GIVE_BACK ? table.rawArea().insert(r, amount, tx)
                        : route == PRODUCTS ? table.productArea().insert(r, amount, tx)
                        : next.receive(r, amount, tx);
                if (moved > 0 && outputs.extract(i, r, moved, tx) == moved) tx.commit();
            }
        }
    }

    /** Takes {@code amount} of {@code r} into the inputs: slots designating it first, then free undesignated ones. */
    int receive(ItemResource r, int amount, TransactionContext tx) {
        int in = 0;
        for (int i = 0; i < SLOTS && in < amount; i++) if (designated[i] != null && r.is(designated[i])) in += inputs.insert(i, r, amount - in, tx);
        for (int i = 0; i < SLOTS && in < amount; i++) if (designated[i] == null) in += inputs.insert(i, r, amount - in, tx);
        return in;
    }

    /** Everything the card holds, for when it is taken off or the table is broken. */
    public List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        for (var handler : List.of(inputs, outputs)) {
            for (int i = 0; i < SLOTS; i++) {
                if (!handler.getResource(i).isEmpty()) out.add(handler.getResource(i).toStack(handler.getAmountAsInt(i)));
            }
        }
        return out;
    }

    // ---- saving -----------------------------------------------------------------------------------

    private static final Codec<Map<Item, Integer>> ROUTES = Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT);

    public void save(ValueOutput out) {
        out.store("process", Process.CODEC, process);
        for (int i = 0; i < SLOTS; i++) {
            if (designated[i] != null) out.putString("designated" + i, BuiltInRegistries.ITEM.getKey(designated[i]).toString());
        }
        inputs.serialize(out.child("inputs"));
        outputs.serialize(out.child("outputs"));
        out.store("routes", ROUTES, routes);
        out.putInt("default_route", defaultRoute);
        out.putInt("progress", progress);
        out.putInt("x", x);
        out.putInt("y", y);
    }

    public static @Nullable ProcessCard load(ValueInput in) {
        Process process = in.read("process", Process.CODEC).orElse(null);
        if (process == null) return null;
        ProcessCard card = new ProcessCard(process);
        for (int i = 0; i < SLOTS; i++) {
            int slot = i;
            in.getString("designated" + i).flatMap(id -> BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(id)))
                    .ifPresent(item -> card.designated[slot] = item);
        }
        in.child("inputs").ifPresent(card.inputs::deserialize);
        in.child("outputs").ifPresent(card.outputs::deserialize);
        in.read("routes", ROUTES).ifPresent(card.routes::putAll);
        card.defaultRoute = in.getIntOr("default_route", KEEP);
        card.progress = in.getIntOr("progress", 0);
        card.x = in.getIntOr("x", 0);
        card.y = in.getIntOr("y", 0);
        return card;
    }
}
