package dev.logos.port;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Stands in for 26.1's {@code net.minecraft.world.item.ItemStackTemplate}: an immutable recipe of a stack, made into
 * a fresh one by {@link #create}. In JSON it is a stack ({@code {"id": …, "count": …, "components": …}}) or an item id alone.
 */
public record ItemStackTemplate(Holder<Item> item, int count, DataComponentPatch components) {
    public static final Codec<ItemStackTemplate> CODEC = Codec.withAlternative(
            ItemStack.CODEC.xmap(ItemStackTemplate::fromNonEmptyStack, ItemStackTemplate::create),
            ItemStack.ITEM_NON_AIR_CODEC, holder -> new ItemStackTemplate(holder));
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemStackTemplate> STREAM_CODEC =
            ItemStack.STREAM_CODEC.map(ItemStackTemplate::fromNonEmptyStack, ItemStackTemplate::create);

    public ItemStackTemplate(Item item) {
        this(item.builtInRegistryHolder(), 1, DataComponentPatch.EMPTY);
    }

    public ItemStackTemplate(Item item, int count) {
        this(item.builtInRegistryHolder(), count, DataComponentPatch.EMPTY);
    }

    public ItemStackTemplate(Item item, DataComponentPatch patch) {
        this(item.builtInRegistryHolder(), 1, patch);
    }

    public ItemStackTemplate(Item item, int count, DataComponentPatch patch) {
        this(item.builtInRegistryHolder(), count, patch);
    }

    public ItemStackTemplate(Holder<Item> item) {
        this(item, 1, DataComponentPatch.EMPTY);
    }

    public ItemStackTemplate(Holder<Item> item, int count) {
        this(item, count, DataComponentPatch.EMPTY);
    }

    public static ItemStackTemplate fromNonEmptyStack(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Empty stack has no template");
        return new ItemStackTemplate(stack.getItemHolder(), stack.getCount(), stack.getComponentsPatch());
    }

    public ItemStackTemplate withCount(int count) {
        return new ItemStackTemplate(item, count, components);
    }

    public ItemStack create() {
        return new ItemStack(item, count, components);
    }

    public ItemStack apply(DataComponentPatch additionalPatch) {
        return apply(count, additionalPatch);
    }

    public ItemStack apply(int count, DataComponentPatch additionalPatch) {
        ItemStack stack = new ItemStack(item, count, components);
        stack.applyComponents(additionalPatch);
        return stack;
    }

    public Holder<Item> typeHolder() {
        return item;
    }

    public Item asItem() {
        return item.value();
    }

    public @Nullable <T> T get(DataComponentType<? extends T> type) {
        return create().get(type);
    }
}
