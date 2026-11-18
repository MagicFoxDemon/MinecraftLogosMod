package dev.logos.port;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import net.minecraft.core.Holder;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 1.21.1's item register with 26.1's way of giving properties: a function that adjusts fresh ones, rather than the
 * properties themselves.
 */
public final class ItemRegister extends DeferredRegister.Items {
    public ItemRegister(String namespace) {
        super(namespace);
    }

    public <I extends Item> DeferredItem<I> registerItem(String name, Function<Item.Properties, ? extends I> func, UnaryOperator<Item.Properties> props) {
        return registerItem(name, func, props.apply(new Item.Properties()));
    }

    public DeferredItem<Item> registerSimpleItem(String name, UnaryOperator<Item.Properties> props) {
        return registerSimpleItem(name, props.apply(new Item.Properties()));
    }

    public DeferredItem<BlockItem> registerSimpleBlockItem(String name, Supplier<? extends Block> block, UnaryOperator<Item.Properties> props) {
        return registerSimpleBlockItem(name, block, props.apply(new Item.Properties()));
    }

    public DeferredItem<BlockItem> registerSimpleBlockItem(Holder<Block> block, UnaryOperator<Item.Properties> props) {
        return registerSimpleBlockItem(block, props.apply(new Item.Properties()));
    }
}
