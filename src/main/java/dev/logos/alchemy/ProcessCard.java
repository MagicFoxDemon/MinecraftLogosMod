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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
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
 * with what lies in its own slots and with the designated items in the table's storage. A
 * designation is exact: a potion of swiftness, not just any potion. Like a
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
    /** What each input slot is set aside for, exactly (item and data, so one potion is not another); empty for nothing. */
    private final ItemResource[] designated = new ItemResource[SLOTS];
    private final ItemStacksResourceHandler inputs = new ItemStacksResourceHandler(SLOTS) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return designated[index].isEmpty() || resource.equals(designated[index]);
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previous) {
            // Putting something in an undesignated slot designates it.
            ItemResource now = getResource(index);
            if (!now.isEmpty() && designated[index].isEmpty()) designated[index] = now;
            changed.run();
        }
    };
    private final ItemStacksResourceHandler outputs = new ItemStacksResourceHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int index, ItemStack previous) {
            changed.run();
        }
    };
    /** Where each product goes, by its exact kind (a potion of healing and an awkward potion are wired apart). */
    private final Map<ItemResource, Integer> routes = new HashMap<>();
    private final Set<ItemResource> made = new java.util.LinkedHashSet<>();
    private int defaultRoute = KEEP;
    private int progress;
    private boolean working;
    private Component status = Component.empty();
    /** Where the card lies on the table screen's canvas, in screen units from its top left. */
    private int x, y;
    private Runnable changed = () -> {};

    public ProcessCard(Process process) {
        this.process = process;
        java.util.Arrays.fill(designated, ItemResource.EMPTY);
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

    /** What input slot {@code slot} is set aside for; empty if nothing. */
    public ItemResource designated(int slot) {
        return designated[slot];
    }

    /** Designates exactly {@code what} for an input slot (empty clears it); refused while the slot holds something else. */
    public boolean designate(int slot, ItemResource what) {
        ItemResource held = inputs.getResource(slot);
        if (!held.isEmpty() && !held.equals(what)) return false;
        designated[slot] = what;
        changed.run();
        return true;
    }

    /** Designates {@code item} as it plainly comes (no data) for an input slot; null clears it. */
    public boolean designate(int slot, @Nullable Item item) {
        return designate(slot, item == null ? ItemResource.EMPTY : ItemResource.of(item));
    }

    /** Where {@code product} goes once made: {@link #KEEP}, {@link #PRODUCTS} or another card's index. */
    public int route(ItemResource product) {
        return routes.getOrDefault(product, defaultRoute);
    }

    /** Where {@code item}, as it plainly comes (no data), goes once made. */
    public int route(Item item) {
        return route(ItemResource.of(item));
    }

    public void setRoute(Item item, int target) {
        setRoute(ItemResource.of(item), target);
    }

    public void setRoute(ItemResource product, int target) {
        routes.put(product, target);
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

    /** Why the card is not working, for its panel; empty while it works. */
    public Component status() {
        return status;
    }

    /** Whether the card worked on a craft last tick: it had the mana and the materials for one. */
    public boolean working() {
        return working;
    }

    /** A tick the table holds its cards still: no work, the craft so far kept, and {@code why} shown. */
    public void pause(Component why) {
        working = false;
        if (!why.equals(status)) {
            status = why;
            changed.run();
        }
    }

    /** One tick: work on the craft if it can be made (else stop), then pass outputs along. True when a craft was finished. */
    public boolean tick(AlchemyTableBlockEntity table) {
        boolean finished = false;
        working = table.mana() >= MANA_PER_CRAFT && craft(table, false) != null;
        // While it cannot work, say why (looked into once a second; it is not needed every tick).
        Component why = working ? Component.empty() : table.getLevel().getGameTime() % 20 == 0 ? diagnose(table) : status;
        if (!why.equals(status)) {
            status = why;
            changed.run();
        }
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
        List<Source> sources = sources(table);
        try (Transaction tx = Transaction.openRoot()) {
            for (AlchemyRecipe recipe : recipes(table, sources)) {
                try (Transaction attempt = Transaction.open(tx)) {
                    if (!make(recipe, table, sources, attempt, null)) continue;
                    attempt.commit();
                }
                if (commit) {
                    tx.commit();
                    remember(recipe);
                }
                return recipe;
            }
        }
        return null;
    }

    /** Notes the products {@code recipe} made, so the card can show them (and they can be wired). Given-back bottles are not products. */
    private void remember(AlchemyRecipe recipe) {
        boolean added = false;
        for (ItemStackTemplate result : recipe.results()) {
            ItemResource product = ItemResource.of(result);
            if (!isContainer(product)) added |= made.add(product);
        }
        if (added) changed.run();
    }

    /** What the card has made so far, in the order first made: the products its panel lists and that can be wired on. */
    public List<ItemResource> made() {
        return List.copyOf(made);
    }

    /** This process's recipes, richest first: the data-written ones and those made up for what {@code sources} hold ({@link SpecialRecipes}). */
    private List<AlchemyRecipe> recipes(AlchemyTableBlockEntity table, List<Source> sources) {
        List<AlchemyRecipe> recipes = new ArrayList<>();
        if (!(table.getLevel() instanceof ServerLevel level)) return recipes;
        for (RecipeHolder<AlchemyRecipe> h : level.recipeAccess().recipeMap().byType(LogosMod.ALCHEMY_RECIPE.get())) {
            if (h.value().process() == process) recipes.add(h.value());
        }
        recipes.addAll(SpecialRecipes.forThings(process, sources.stream().map(Source::resource).toList(), level));
        recipes.sort(Comparator.comparingInt(AlchemyRecipe::kinds).reversed());
        return recipes;
    }

    /** A slot the card may take from: its own inputs first, then the table's storage holding designated items. */
    private record Source(ResourceHandler<ItemResource> handler, int index) {
        ItemResource resource() {
            return handler.getResource(index);
        }
    }

    private List<Source> sources(AlchemyTableBlockEntity table) {
        List<Source> out = new ArrayList<>();
        Set<ItemResource> wanted = new HashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            out.add(new Source(inputs, i));
            if (!designated[i].isEmpty()) wanted.add(designated[i]);
        }
        for (int i = 0; i < table.items().size(); i++) {
            ItemResource r = table.items().getResource(i);
            if (!r.isEmpty() && wanted.contains(r)) out.add(new Source(table.items(), i));
        }
        return out;
    }

    private static boolean isWaterBottle(ItemResource r) {
        return r.is(Items.POTION) && r.getComponents().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER);
    }

    /**
     * Takes one craft's materials and puts its results in the outputs, inside {@code tx}. A fluid is
     * poured from water bottles first, a bottle each (any excess goes into the tank), so the glass
     * bottles they free can hold the product; the tank makes up the rest. Freed bottles the recipe
     * does not need go back to the raw-material area. With {@code missing} given, it does not stop at
     * the first shortfall but notes each one there (for the card's status) and fails at the end.
     */
    private boolean make(AlchemyRecipe recipe, AlchemyTableBlockEntity table, List<Source> sources, TransactionContext tx, @Nullable List<Component> missing) {
        int freed = 0;
        boolean ok = true;
        if (recipe.fluid().isPresent()) {
            var need = recipe.fluid().get();
            int left = need.amount();
            if (need.ingredient().test(new FluidStack(Fluids.WATER, AlchemyTableBlock.BOTTLE))) {
                for (Source s : sources) {
                    while (left > 0 && isWaterBottle(s.resource()) && s.handler().extract(s.index(), s.resource(), 1, tx) == 1) {
                        freed++;
                        left -= AlchemyTableBlock.BOTTLE;
                    }
                }
                if (left < 0 && table.tank().insert(FluidResource.of(Fluids.WATER), -left, tx) != -left) return false;
            }
            FluidStack inTank = table.fluid();
            if (left > 0 && !inTank.isEmpty() && need.ingredient().test(inTank)) {
                left -= table.tank().extract(0, table.tank().getResource(0), Math.min(left, inTank.getAmount()), tx);
            }
            if (left > 0) {
                if (missing == null) return false;
                ok = false;
                var fluid = need.ingredient().fluids().stream().findFirst();
                Component name = fluid.isPresent() ? new FluidStack(fluid.get(), 1).getHoverName() : Component.translatable("logos.alchemy_card.fluid");
                missing.add(Component.translatable("logos.alchemy_card.missing_fluid", name, left));
            }
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
            if (left > 0) {
                if (missing == null) return false;
                ok = false;
                Component name = need.ingredient().items().findFirst().map(i -> new ItemStack(i).getHoverName()).orElse(Component.literal("?"));
                missing.add(Component.translatable("logos.alchemy_card.missing_item", name, left));
            }
        }
        if (!ok) return false;
        for (ItemStackTemplate result : recipe.results()) {
            ItemStack product = result.create();
            if (outputs.insert(ItemResource.of(product), product.getCount(), tx) != product.getCount()) {
                if (missing != null) missing.add(Component.translatable("logos.alchemy_card.output_full"));
                return false;
            }
        }
        if (freed > 0) {
            freed -= table.rawArea().insert(ItemResource.of(Items.GLASS_BOTTLE), freed, tx);
            if (freed > 0 && outputs.insert(ItemResource.of(Items.GLASS_BOTTLE), freed, tx) != freed) {
                if (missing != null) missing.add(Component.translatable("logos.alchemy_card.output_full"));
                return false;
            }
        }
        return true;
    }

    /**
     * Why the card cannot work now: no mana; nothing to work with; outputs too full for a craft whose
     * materials are all there; or else what the recipe it seems meant for lacks. That recipe is the one
     * using the most of what the card designates or holds (then the fewest shortfalls, then the
     * richest), so a card set up for sugar, mushrooms and wheat that runs out of them says so, rather
     * than asking for some other recipe's ingredients.
     */
    private Component diagnose(AlchemyTableBlockEntity table) {
        if (table.mana() < MANA_PER_CRAFT) return Component.translatable("logos.alchemy_card.no_mana");
        boolean anything = false;
        for (int i = 0; i < SLOTS; i++) anything |= !designated[i].isEmpty();
        if (!anything) return Component.translatable("logos.alchemy_card.idle");
        Component outputFull = Component.translatable("logos.alchemy_card.output_full");
        List<Component> best = null;
        int bestUses = -1;
        List<Source> sources = sources(table);
        for (AlchemyRecipe recipe : recipes(table, sources)) {
            List<Component> missing = new ArrayList<>();
            try (Transaction tx = Transaction.openRoot()) {
                make(recipe, table, sources, tx, missing);
            }
            // Everything is there but the results have nowhere to go: that is the reason.
            if (missing.equals(List.of(outputFull))) return outputFull;
            int uses = uses(recipe);
            if (best == null || uses > bestUses || uses == bestUses && missing.size() < best.size()) {
                best = missing;
                bestUses = uses;
            }
        }
        if (best == null || best.isEmpty()) return Component.empty();
        MutableComponent out = Component.translatable("logos.alchemy_card.missing");
        for (int i = 0; i < best.size(); i++) out.append(i == 0 ? Component.empty() : Component.literal("、")).append(best.get(i));
        return out;
    }

    /** How many of {@code recipe}'s ingredients the card designates or holds. */
    private int uses(AlchemyRecipe recipe) {
        int n = 0;
        for (SizedIngredient need : recipe.ingredients()) {
            for (int i = 0; i < SLOTS; i++) {
                ItemResource what = designated[i].isEmpty() ? inputs.getResource(i) : designated[i];
                if (!what.isEmpty() && need.ingredient().test(what.toStack())) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    static boolean isContainer(ItemResource r) {
        return r.is(Items.GLASS_BOTTLE) || r.is(Items.BUCKET);
    }

    /** Passes each output on along its route, as much as fits. */
    private void deliver(AlchemyTableBlockEntity table) {
        for (int i = 0; i < SLOTS; i++) {
            ItemResource r = outputs.getResource(i);
            if (r.isEmpty()) continue;
            int route = isContainer(r) ? GIVE_BACK : route(r);
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
        for (int i = 0; i < SLOTS && in < amount; i++) if (r.equals(designated[i])) in += inputs.insert(i, r, amount - in, tx);
        for (int i = 0; i < SLOTS && in < amount; i++) if (designated[i].isEmpty()) in += inputs.insert(i, r, amount - in, tx);
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

    /** Routes as saved before products were told apart exactly: item id to target. */
    private static final Codec<Map<Item, Integer>> OLD_ROUTES = Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT);

    private record Route(ItemResource product, int target) {
        static final Codec<Route> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
                ItemResource.CODEC.fieldOf("product").forGetter(Route::product), Codec.INT.fieldOf("target").forGetter(Route::target)).apply(i, Route::new));
    }

    public void save(ValueOutput out) {
        out.store("process", Process.CODEC, process);
        for (int i = 0; i < SLOTS; i++) {
            if (!designated[i].isEmpty()) out.store("designation" + i, ItemResource.CODEC, designated[i]);
        }
        inputs.serialize(out.child("inputs"));
        outputs.serialize(out.child("outputs"));
        out.store("wires", Route.CODEC.listOf(), routes.entrySet().stream().map(e -> new Route(e.getKey(), e.getValue())).toList());
        out.store("products", ItemResource.CODEC.listOf(), List.copyOf(made));
        out.putInt("default_route", defaultRoute);
        out.putInt("progress", progress);
        out.store("status", ComponentSerialization.CODEC, status);
        out.putInt("x", x);
        out.putInt("y", y);
    }

    public static @Nullable ProcessCard load(ValueInput in) {
        Process process = in.read("process", Process.CODEC).orElse(null);
        if (process == null) return null;
        ProcessCard card = new ProcessCard(process);
        for (int i = 0; i < SLOTS; i++) {
            int slot = i;
            // Tables saved before designations were exact kept just the item's id.
            in.read("designation" + i, ItemResource.CODEC).or(() -> in.getString("designated" + slot)
                            .flatMap(id -> BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(id))).map(ItemResource::of))
                    .ifPresent(what -> card.designated[slot] = what);
        }
        in.child("inputs").ifPresent(card.inputs::deserialize);
        in.child("outputs").ifPresent(card.outputs::deserialize);
        // Tables saved before products were told apart exactly kept them by item id.
        in.read("wires", Route.CODEC.listOf()).ifPresentOrElse(list -> list.forEach(r -> card.routes.put(r.product(), r.target())),
                () -> in.read("routes", OLD_ROUTES).ifPresent(m -> m.forEach((item, target) -> card.routes.put(ItemResource.of(item), target))));
        in.read("products", ItemResource.CODEC.listOf()).ifPresentOrElse(card.made::addAll,
                () -> in.read("made", BuiltInRegistries.ITEM.byNameCodec().listOf()).ifPresent(list -> list.forEach(item -> card.made.add(ItemResource.of(item)))));
        card.defaultRoute = in.getIntOr("default_route", KEEP);
        card.progress = in.getIntOr("progress", 0);
        card.status = in.read("status", ComponentSerialization.CODEC).orElse(Component.empty());
        card.x = in.getIntOr("x", 0);
        card.y = in.getIntOr("y", 0);
        return card;
    }
}
