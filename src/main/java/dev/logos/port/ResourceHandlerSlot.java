package dev.logos.port;

import javax.annotation.Nullable;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.item.ResourceHandlerSlot}: a menu slot showing one index
 * of a {@link ResourceHandler}. The handler hands out copies, so the slot remembers the copy it last gave and writes it
 * back on {@link #setChanged} if the menu changed it in place.
 */
public class ResourceHandlerSlot extends Slot {
    private static final Container NO_CONTAINER = new SimpleContainer(0);

    private final ResourceHandler<ItemResource> handler;
    private final IndexModifier<ItemResource> slotModifier;
    private @Nullable ItemStack handedOut;

    public ResourceHandlerSlot(ResourceHandler<ItemResource> handler, IndexModifier<ItemResource> slotModifier, int handlerSlot, int xPosition, int yPosition) {
        super(NO_CONTAINER, handlerSlot, xPosition, yPosition);
        this.handler = handler;
        this.slotModifier = slotModifier;
    }

    private ItemStack contents() {
        int index = getSlotIndex();
        return handler.getResource(index).toStack(handler.getAmountAsInt(index));
    }

    @Override
    public ItemStack getItem() {
        return handedOut = contents();
    }

    @Override
    public void set(ItemStack stack) {
        slotModifier.set(getSlotIndex(), ItemResource.of(stack), stack.getCount());
        handedOut = stack;
    }

    @Override
    public void setChanged() {
        if (handedOut != null && !ItemStack.matches(handedOut, contents())) set(handedOut);
    }

    @Override
    public ItemStack remove(int amount) {
        ItemStack stack = contents();
        ItemStack taken = stack.split(amount);
        set(stack);
        handedOut = null;
        return taken;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        // Whether it may go here at all, not whether it fits beside what is there: the menu may swap them.
        return !stack.isEmpty() && handler.isValid(getSlotIndex(), ItemResource.of(stack));
    }

    @Override
    public void onQuickCraft(ItemStack oldStack, ItemStack newStack) {}

    @Override
    public int getMaxStackSize() {
        return handler.getCapacityAsInt(getSlotIndex(), ItemResource.EMPTY);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return handler.getCapacityAsInt(getSlotIndex(), ItemResource.of(stack));
    }

    @Override
    public boolean mayPickup(Player player) {
        ItemResource resource = handler.getResource(getSlotIndex());
        if (resource.isEmpty()) return false;
        try (Transaction tx = Transaction.openRoot()) {
            return handler.extract(getSlotIndex(), resource, 1, tx) == 1;
        }
    }

    public ResourceHandler<ItemResource> getResourceHandler() {
        return handler;
    }

    @Override
    public boolean isSameInventory(Slot other) {
        return other instanceof ResourceHandlerSlot slot && slot.handler == handler;
    }
}
