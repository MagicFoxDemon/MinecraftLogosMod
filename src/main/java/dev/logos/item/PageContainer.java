package dev.logos.item;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Page slots stored on an item (wand or codex) in its {@code minecraft:container} component.
 * Every change is written straight back to the item, and only spell pages are accepted (and, on a
 * wand, charms).
 */
public final class PageContainer extends SimpleContainer {
    private final ItemStack holder;
    private final boolean charms;

    public PageContainer(ItemStack holder, int size) {
        this(holder, size, false);
    }

    /** {@code charms}: the slots also take {@link WandCharmItem}s (a wand's do, a codex's do not). */
    public PageContainer(ItemStack holder, int size, boolean charms) {
        super(size);
        this.holder = holder;
        this.charms = charms;
        NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
        holder.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        for (int i = 0; i < size; i++) setItem(i, items.get(i));
    }

    @Override
    public void setChanged() {
        super.setChanged();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < getContainerSize(); i++) items.add(getItem(i));
        holder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return accepts(stack);
    }

    private boolean accepts(ItemStack stack) {
        return SpellPages.isHoldable(stack) || charms && stack.getItem() instanceof WandCharmItem;
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    /** A UI slot for page {@code index}: pages (and charms, where taken) only, one per slot. */
    public Slot slot(int index) {
        return new Slot(this, index, 0, 0) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return accepts(stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
    }

    /** The item in slot {@code slot} of {@code holder} (a copy), or empty. */
    public static ItemStack get(ItemStack holder, int slot) {
        ItemContainerContents c = holder.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        return slot < c.getSlots() ? c.getStackInSlot(slot) : ItemStack.EMPTY;
    }

    /** Replaces slot {@code slot} of {@code holder} (a held wand or codex) with {@code stack}. */
    public static void set(ItemStack holder, int slot, ItemStack stack) {
        ItemContainerContents c = holder.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        NonNullList<ItemStack> items = NonNullList.withSize(Math.max(c.getSlots(), slot + 1), ItemStack.EMPTY);
        c.copyInto(items);
        items.set(slot, stack);
        holder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }

    /** How many slots {@code holder}'s contents span (empty ones included). */
    public static int size(ItemStack holder) {
        return holder.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).getSlots();
    }

    /** The pages stored on {@code holder}, without opening a container. */
    public static List<ItemStack> pages(ItemStack holder) {
        List<ItemStack> out = new ArrayList<>();
        holder.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().forEach(out::add);
        return out;
    }
}
