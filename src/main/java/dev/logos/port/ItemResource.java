package dev.logos.port;

import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.item.ItemResource}: an item with its components, without
 * a count. Two resources are equal when their items and components are.
 */
public final class ItemResource implements Resource {
    public static final ItemResource EMPTY = new ItemResource(ItemStack.EMPTY);
    public static final Codec<ItemResource> CODEC = ItemStack.SINGLE_ITEM_CODEC.xmap(ItemResource::of, ItemResource::toStack);
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemResource> STREAM_CODEC =
            ItemStack.OPTIONAL_STREAM_CODEC.map(ItemResource::of, ItemResource::toStack);

    /** A stack of one, never handed out: copies leave here. */
    private final ItemStack stack;

    private ItemResource(ItemStack stack) {
        this.stack = stack;
    }

    public static ItemResource of(ItemStack stack) {
        return stack.isEmpty() ? EMPTY : new ItemResource(stack.copyWithCount(1));
    }

    public static ItemResource of(ItemStackTemplate template) {
        return template == null ? EMPTY : of(template.create());
    }

    public static ItemResource of(ItemLike item) {
        return of(new ItemStack(item));
    }

    public static ItemResource of(ItemLike item, DataComponentPatch patch) {
        return of(new ItemStack(item.asItem().builtInRegistryHolder(), 1, patch));
    }

    public static ItemResource of(Holder<Item> holder) {
        return of(new ItemStack(holder));
    }

    public static ItemResource of(Holder<Item> holder, DataComponentPatch patch) {
        return of(new ItemStack(holder, 1, patch));
    }

    public Item value() {
        return stack.getItem();
    }

    public Item getItem() {
        return stack.getItem();
    }

    public Holder<Item> typeHolder() {
        return stack.getItemHolder();
    }

    @Override
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public boolean matches(ItemStack other) {
        return ItemStack.isSameItemSameComponents(stack, other);
    }

    public boolean is(ItemLike item) {
        return stack.is(item.asItem());
    }

    public boolean test(Predicate<ItemStack> predicate) {
        return predicate.test(stack);
    }

    public boolean isComponentsPatchEmpty() {
        return stack.getComponentsPatch().isEmpty();
    }

    public ItemResource withMergedPatch(DataComponentPatch patch) {
        if (isEmpty()) return EMPTY;
        ItemStack copy = stack.copy();
        copy.applyComponents(patch);
        return new ItemResource(copy);
    }

    public <D> ItemResource with(DataComponentType<D> type, @Nullable D data) {
        if (isEmpty()) return EMPTY;
        ItemStack copy = stack.copy();
        copy.set(type, data);
        return new ItemResource(copy);
    }

    public <D> ItemResource with(Supplier<? extends DataComponentType<D>> type, @Nullable D data) {
        return with(type.get(), data);
    }

    public ItemResource without(DataComponentType<?> type) {
        if (isEmpty()) return EMPTY;
        ItemStack copy = stack.copy();
        copy.remove(type);
        return new ItemResource(copy);
    }

    public ItemResource without(Supplier<? extends DataComponentType<?>> type) {
        return without(type.get());
    }

    public <D> D get(DataComponentType<? extends D> type) {
        return stack.get(type);
    }

    public <D> D get(Supplier<? extends DataComponentType<? extends D>> type) {
        return stack.get(type.get());
    }

    public <D> D getOrDefault(DataComponentType<? extends D> type, D fallback) {
        return stack.getOrDefault(type, fallback);
    }

    public boolean has(DataComponentType<?> type) {
        return stack.has(type);
    }

    public DataComponentMap getComponents() {
        return stack.getComponents();
    }

    public DataComponentPatch getComponentsPatch() {
        return stack.getComponentsPatch();
    }

    public ItemStack toStack(int count) {
        return isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(count);
    }

    public ItemStack toStack() {
        return toStack(1);
    }

    public net.minecraft.network.chat.Component getHoverName() {
        return stack.getHoverName();
    }

    public int getMaxStackSize() {
        return stack.getMaxStackSize();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemResource other && ItemStack.isSameItemSameComponents(stack, other.stack);
    }

    @Override
    public int hashCode() {
        return ItemStack.hashItemAndComponents(stack);
    }

    @Override
    public String toString() {
        return isEmpty() ? "ItemResource[EMPTY]" : "ItemResource[" + stack.getItem() + " " + stack.getComponentsPatch() + "]";
    }
}
