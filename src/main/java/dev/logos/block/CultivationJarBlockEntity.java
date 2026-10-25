package dev.logos.block;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.alchemy.CultivationMedium;
import dev.logos.alchemy.CultivationRecipe;
import dev.logos.runtime.ManaVessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

/**
 * 培养罐: the seed planted in the jar's soul humus, the medium poured on it, and the harvests waiting
 * to be taken. What grows is a {@link CultivationRecipe}: any crop of farmland yields its own loot, and
 * the jar's own seeds (and some media) yield what their recipe says. A ripe crop is harvested into the
 * output slots at the next random tick, or by hand; hoppers feed seeds and media in and take harvests out.
 * It holds mana, as collectors and the infusing rune give it, for the crops whose stages cost some.
 */
public class CultivationJarBlockEntity extends BlockEntity implements ManaVessel {
    public static final int OUTPUTS = 3;
    public static final double MAX_MANA = 500;

    private final ItemStacksResourceHandler seed = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            // Clients know no recipes; the server decides.
            return level == null || level.isClientSide() || CultivationRecipe.find(level, resource.toStack(), medium) != null;
        }

        @Override
        protected int getCapacity(int index, ItemResource resource) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            if (!ItemStack.isSameItemSameComponents(previousContents, getResource(0).toStack())) age = 0;
            refresh();
        }
    };
    /** Media waiting their turn; the next is poured when the one in the jar runs out. */
    private final ItemStacksResourceHandler reserve = new ItemStacksResourceHandler(1) {
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return CultivationMedium.of(resource.toStack()) != null;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            pourNext();
            setChanged();
        }
    };
    private final ItemStacksResourceHandler outputs = new ItemStacksResourceHandler(OUTPUTS) {
        /** Only harvests and empty bottles go here. */
        @Override
        public boolean isValid(int index, ItemResource resource) {
            return false;
        }

        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    /** What hoppers and pipes see: seeds and media in, harvests out. */
    private final ResourceHandler<ItemResource> automation = new CombinedResourceHandler<>(
            AlchemyTableBlockEntity.OneWay.in(seed, 0, 1), AlchemyTableBlockEntity.OneWay.in(reserve, 0, 1),
            AlchemyTableBlockEntity.OneWay.out(outputs, 0, OUTPUTS));

    /** The medium on the soil (one bottle's worth, the bottle given back) and the harvests it has left. */
    private ItemStack medium = ItemStack.EMPTY;
    private int mediumUses;
    private int age;
    private double mana;
    /** The crop drawn in the jar, worked out on the server and sent to clients with the rest. */
    private @Nullable CropBlock plant;
    private @Nullable CultivationRecipe recipe;
    private boolean recipeKnown;
    /** The screen's yield line, and the recipe it was worked out for. */
    private @Nullable CultivationRecipe yieldFor;
    private Component yieldLine = Component.empty();

    public CultivationJarBlockEntity(BlockPos pos, BlockState state) {
        super(LogosMod.CULTIVATION_JAR_BE.get(), pos, state);
    }

    public ItemStacksResourceHandler seedSlot() {
        return seed;
    }

    public ItemStacksResourceHandler reserve() {
        return reserve;
    }

    public ItemStacksResourceHandler outputs() {
        return outputs;
    }

    public ResourceHandler<ItemResource> automation() {
        return automation;
    }

    public ItemStack seed() {
        return seed.getResource(0).toStack(seed.getAmountAsInt(0));
    }

    public ItemStack medium() {
        return medium;
    }

    public int mediumUses() {
        return mediumUses;
    }

    public int age() {
        return age;
    }

    public int maxAge() {
        return plant == null ? 0 : plant.getMaxAge();
    }

    /** What grows here now, or null if nothing does (no seed, or one that does not grow in a jar). */
    public @Nullable CultivationRecipe recipe() {
        if (!recipeKnown && level != null && !level.isClientSide()) {
            recipe = CultivationRecipe.find(level, seed(), medium);
            recipeKnown = true;
        }
        return recipe;
    }

    /** The planted crop as it now stands, or null while nothing grows. */
    public @Nullable BlockState plant() {
        return plant == null ? null : plant.getStateForAge(Math.min(age, plant.getMaxAge()));
    }

    public boolean isEmpty() {
        return plant == null;
    }

    public boolean isRipe() {
        return plant != null && age >= plant.getMaxAge();
    }

    /** Puts {@code stack} in as the seed (one of it); false if a seed is planted or it does not grow here. */
    public boolean plant(ItemStack stack) {
        if (!seed.getResource(0).isEmpty() || !seed.isValid(0, ItemResource.of(stack))) return false;
        seed.set(0, ItemResource.of(stack), 1);
        return true;
    }

    /** Takes the seed back out, unripe crop and all. */
    public ItemStack uproot() {
        ItemStack out = seed();
        seed.set(0, ItemResource.EMPTY, 0);
        return out;
    }

    /**
     * Pours a bottle of medium: onto the soil if the jar has none (the empty bottle is given back to
     * {@code player}), otherwise into the reserve. False if it is no medium or there is no room.
     */
    public boolean pour(ItemStack stack, Player player) {
        CultivationMedium kind = CultivationMedium.of(stack);
        if (kind == null) return false;
        if (medium.isEmpty()) {
            setMedium(stack.copyWithCount(1), kind.uses());
            stack.consume(1, player);
            if (!player.hasInfiniteMaterials()) player.getInventory().placeItemBackInInventory(new ItemStack(Items.GLASS_BOTTLE));
            return true;
        }
        ItemResource res = ItemResource.of(stack);
        int held = reserve.getAmountAsInt(0);
        if (held > 0 && !reserve.getResource(0).equals(res) || held >= stack.getMaxStackSize()) return false;
        reserve.set(0, res, held + 1);
        stack.consume(1, player);
        return true;
    }

    /** At a random tick: a ripe crop is harvested into the outputs if they have room; an unripe one may grow. */
    public void randomTick(ServerLevel level, RandomSource random) {
        CultivationRecipe r = recipe();
        if (r == null || plant == null) return;
        if (isRipe()) {
            Harvest h = reap(level);
            if (fits(h.items)) {
                h.items.forEach(this::stash);
                afterHarvest(h);
            }
            return;
        }
        if (random.nextFloat() < r.growth() * mediumGrowth()) grow();
    }

    /** Grows a stage, paying the recipe's mana; false if it cannot (nothing growing, ripe, or too little mana). */
    public boolean grow() {
        CultivationRecipe r = recipe();
        if (r == null || plant == null || isRipe() || mana < r.mana()) return false;
        mana -= r.mana();
        age++;
        changed();
        return true;
    }

    /** Whether a stage could be grown now: something unripe grows and the jar holds its mana. */
    public boolean canGrow() {
        CultivationRecipe r = recipe();
        return r != null && plant != null && !isRipe() && mana >= r.mana();
    }

    /** Harvests the ripe crop by hand: its yield, for the caller to drop. Empty if it is not ripe. */
    public List<ItemStack> harvest(ServerLevel level) {
        if (!isRipe() || recipe() == null) return List.of();
        Harvest h = reap(level);
        afterHarvest(h);
        return h.items;
    }

    /** A harvest's yield, and whether the seed stays planted. */
    private record Harvest(List<ItemStack> items, boolean replanted) {}

    /**
     * What the ripe crop yields, without changing the jar: the recipe's harvest (the seed stays), or the
     * crop's loot less one seed to replant, as one would replant a field (no seed in the loot: none stays).
     */
    private Harvest reap(ServerLevel level) {
        CultivationRecipe r = recipe();
        if (!r.harvest().isEmpty()) return new Harvest(r.harvest().stream().map(t -> t.create()).toList(), true);
        ItemStack planted = seed();
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(plant(), level, worldPosition, null));
        boolean replanted = false;
        for (ItemStack drop : drops) {
            if (ItemStack.isSameItemSameComponents(drop, planted)) {
                drop.shrink(1);
                replanted = true;
                break;
            }
        }
        drops.removeIf(ItemStack::isEmpty);
        return new Harvest(drops, replanted);
    }

    private void afterHarvest(Harvest h) {
        CultivationRecipe r = recipe();
        age = 0;
        if (!h.replanted) seed.set(0, ItemResource.EMPTY, 0);
        // The medium is spent only where it did something: sped the crop, or made the recipe.
        if (!medium.isEmpty() && r != null && (r.medium().isPresent() || mediumGrowth() > 1) && --mediumUses <= 0) {
            setMedium(ItemStack.EMPTY, 0);
            pourNext();
        }
        changed();
    }

    private float mediumGrowth() {
        CultivationMedium kind = CultivationMedium.of(medium);
        return kind == null ? 1 : kind.growth();
    }

    /** If the soil has no medium, pours the next from the reserve; its bottle goes to the outputs. */
    private void pourNext() {
        if (!medium.isEmpty() || reserve.getAmountAsInt(0) == 0) return;
        ItemResource next = reserve.getResource(0);
        CultivationMedium kind = CultivationMedium.of(next.toStack());
        if (kind == null) return;
        setMedium(next.toStack(), kind.uses());
        reserve.set(0, next, reserve.getAmountAsInt(0) - 1);
        stash(new ItemStack(Items.GLASS_BOTTLE));
    }

    private void setMedium(ItemStack stack, int uses) {
        medium = stack;
        mediumUses = uses;
        refresh();
    }

    /** Whether all of {@code items} would fit in the outputs together. */
    private boolean fits(List<ItemStack> items) {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < OUTPUTS; i++) slots.add(outputs.getResource(i).toStack(outputs.getAmountAsInt(i)));
        for (ItemStack item : items) {
            int left = item.getCount();
            for (int i = 0; i < OUTPUTS && left > 0; i++) {
                ItemStack s = slots.get(i);
                if (s.isEmpty()) {
                    slots.set(i, item.copyWithCount(Math.min(left, item.getMaxStackSize())));
                    left -= slots.get(i).getCount();
                } else if (ItemStack.isSameItemSameComponents(s, item)) {
                    int add = Math.min(left, s.getMaxStackSize() - s.getCount());
                    s.grow(add);
                    left -= add;
                }
            }
            if (left > 0) return false;
        }
        return true;
    }

    /** Puts {@code item} in the outputs, as far as it goes; the rest is dropped on the jar. */
    private void stash(ItemStack item) {
        int left = item.getCount();
        ItemResource res = ItemResource.of(item);
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < OUTPUTS && left > 0; i++) {
                int held = outputs.getAmountAsInt(i);
                boolean same = held > 0 && outputs.getResource(i).equals(res);
                if (pass == 0 ? !same : held > 0) continue;
                int add = Math.min(left, item.getMaxStackSize() - held);
                if (add <= 0) continue;
                outputs.set(i, res, held + add);
                left -= add;
            }
        }
        if (left > 0 && level != null) Block.popResource(level, worldPosition.above(), item.copyWithCount(left));
    }

    /** The seed or medium changed: what grows is worked out again. */
    private void refresh() {
        if (level == null || level.isClientSide()) return;
        recipeKnown = false;
        CultivationRecipe r = recipe();
        plant = r == null ? null : r.crop(seed());
        if (plant != null) age = Math.min(age, plant.getMaxAge());
        changed();
    }

    private void changed() {
        setChanged();
        if (level == null || level.isClientSide()) return;
        BlockState state = getBlockState();
        boolean wet = !medium.isEmpty();
        if (state.hasProperty(CultivationJarBlock.MEDIUM) && state.getValue(CultivationJarBlock.MEDIUM) != wet) {
            level.setBlock(worldPosition, state.setValue(CultivationJarBlock.MEDIUM, wet), Block.UPDATE_ALL);
        } else {
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    /** "Yields: …" for the screen: the recipe's harvest, or what the crop's loot gives (sampled once per crop). */
    public Component yieldLine() {
        CultivationRecipe r = recipe();
        if (r == null || plant == null || !(level instanceof ServerLevel server)) return Component.empty();
        if (yieldFor != r) {
            yieldFor = r;
            List<ItemStack> items = new ArrayList<>();
            if (!r.harvest().isEmpty()) r.harvest().forEach(t -> items.add(t.create()));
            else {
                BlockState ripe = plant.getStateForAge(plant.getMaxAge());
                for (int i = 0; i < 8; i++) {
                    for (ItemStack drop : Block.getDrops(ripe, server, worldPosition, null)) {
                        if (items.stream().noneMatch(s -> ItemStack.isSameItemSameComponents(s, drop))) items.add(drop.copyWithCount(1));
                    }
                }
            }
            MutableComponent line = Component.empty();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) line.append(Component.literal("、"));
                line.append(items.get(i).getHoverName());
                if (!r.harvest().isEmpty() && items.get(i).getCount() > 1) line.append(Component.literal(" ×" + items.get(i).getCount()));
            }
            yieldLine = Component.translatable(r.harvest().isEmpty() ? "logos.cultivation_jar.yield_loot" : "logos.cultivation_jar.yield", line);
        }
        return yieldLine;
    }

    /** What the jar is doing, for the screen. */
    public Component status() {
        CultivationRecipe r = recipe();
        if (r == null || plant == null) return Component.translatable("logos.cultivation_jar.empty");
        if (isRipe()) {
            boolean full = level instanceof ServerLevel server && !fits(reap(server).items);
            return Component.translatable(full ? "logos.cultivation_jar.full" : "logos.cultivation_jar.ripe");
        }
        if (mana < r.mana()) return Component.translatable("logos.cultivation_jar.no_mana", r.mana());
        return Component.translatable("logos.cultivation_jar.growing", age, plant.getMaxAge());
    }

    /** The colour of the medium on the soil, for the liquid layer drawn over it. */
    public int mediumColor() {
        CultivationMedium kind = CultivationMedium.of(medium);
        return kind == null ? -1 : kind.color();
    }

    @Override
    public double mana() {
        return mana;
    }

    @Override
    public double maxMana() {
        return MAX_MANA;
    }

    @Override
    public double receiveMana(double amount) {
        double taken = Math.clamp(amount, 0, MAX_MANA - mana);
        if (taken > 0) {
            mana += taken;
            setChanged();
        }
        return taken;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) refresh();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        seed.serialize(output.child("seed"));
        reserve.serialize(output.child("reserve"));
        outputs.serialize(output.child("outputs"));
        if (!medium.isEmpty()) output.store("medium", ItemStack.CODEC, medium);
        output.putInt("medium_uses", mediumUses);
        output.putInt("age", age);
        output.putDouble("mana", mana);
        if (plant != null) output.putString("plant", BuiltInRegistries.BLOCK.getKey(plant).toString());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        int oldColor = mediumColor();
        input.child("seed").ifPresent(seed::deserialize);
        input.child("reserve").ifPresent(reserve::deserialize);
        input.child("outputs").ifPresent(outputs::deserialize);
        medium = input.read("medium", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        mediumUses = input.getIntOr("medium_uses", 0);
        age = input.getIntOr("age", 0);
        mana = input.getDoubleOr("mana", 0);
        plant = input.getString("plant").map(Identifier::tryParse).map(BuiltInRegistries.BLOCK::getValue)
                .filter(b -> b instanceof CropBlock).map(b -> (CropBlock) b).orElse(null);
        recipeKnown = false;
        // A new medium colour is tinted into the jar's own model: have the client draw it again.
        if (level != null && level.isClientSide() && oldColor != mediumColor()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null) return;
        for (ItemStacksResourceHandler h : List.of(seed, reserve, outputs)) {
            for (int i = 0; i < h.size(); i++) {
                ItemStack s = h.getResource(i).toStack(h.getAmountAsInt(i));
                if (!s.isEmpty()) Block.popResource(level, pos, s);
            }
        }
    }
}
