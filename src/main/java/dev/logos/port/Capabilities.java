package dev.logos.port;

import javax.annotation.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Stands in for the item and fluid parts of 26.1's {@code net.neoforged.neoforge.capabilities.Capabilities}, which hand
 * out {@link ResourceHandler}s. These are capabilities of the mod's own; {@link #register} bridges them to 1.21.1's
 * item and fluid handlers, so hoppers, pipes and tanks of other mods see the mod's blocks, and the mod sees what other
 * mods give their creatures.
 */
public final class Capabilities {
    private Capabilities() {}

    public static final class Item {
        public static final BlockCapability<ResourceHandler<ItemResource>, Direction> BLOCK =
                BlockCapability.createSided(id("item_resources"), handlerClass());
        public static final EntityCapability<ResourceHandler<ItemResource>, Direction> ENTITY =
                EntityCapability.createSided(id("item_resources"), handlerClass());

        private Item() {}
    }

    public static final class Fluid {
        public static final BlockCapability<ResourceHandler<FluidResource>, Direction> BLOCK =
                BlockCapability.createSided(id("fluid_resources"), handlerClass());

        private Fluid() {}
    }

    /** Bridges, after everything else registered its providers: the mod's blocks out to 1.21.1, every creature's handler in. */
    public static void register(IEventBus modBus) {
        modBus.addListener(EventPriority.LOWEST, Capabilities::bridge);
    }

    private static void bridge(RegisterCapabilitiesEvent event) {
        for (Block block : BuiltInRegistries.BLOCK) {
            if (event.isBlockRegistered(Item.BLOCK, block)) {
                event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, (level, pos, state, be, side) -> {
                    ResourceHandler<ItemResource> handler = level.getCapability(Item.BLOCK, pos, state, be, side);
                    return handler == null ? null : new ItemHandlerView(handler);
                }, block);
            }
            if (event.isBlockRegistered(Fluid.BLOCK, block)) {
                event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, (level, pos, state, be, side) -> {
                    ResourceHandler<FluidResource> handler = level.getCapability(Fluid.BLOCK, pos, state, be, side);
                    return handler == null ? null : new FluidHandlerView(handler);
                }, block);
            }
        }
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            event.registerEntity(Item.ENTITY, type, (entity, side) -> {
                IItemHandler handler = side == null
                        ? entity.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY)
                        : entity.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY_AUTOMATION, side);
                return handler == null ? null : new ItemHandlerResources(handler);
            });
        }
    }

    /** Whether a creature's handler is only NeoForge's fallback for any living entity: its hands and armour. */
    public static boolean isEquipmentFallback(ResourceHandler<ItemResource> handler) {
        return handler instanceof ItemHandlerResources r
                && r.handler().getClass() == net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.class;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("logos", path);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Resource> Class<ResourceHandler<T>> handlerClass() {
        return (Class<ResourceHandler<T>>) (Class<?>) ResourceHandler.class;
    }
}
