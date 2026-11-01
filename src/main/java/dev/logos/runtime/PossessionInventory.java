package dev.logos.runtime;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.IContainerUIHolder;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.lowdragmc.lowdraglib2.gui.texture.VanillaSpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;

import dev.logos.LogosMod;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.IndexModifier;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.LivingEntityEquipmentWrapper;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * A possessing player's inventory key (see {@link Possession}): the creature's things above the
 * player's own. First its equipment (armour and hands, and body armour or saddle where it can wear
 * them), each slot taking what that slot takes; whatever is put on it there it keeps on death, as
 * a mob keeps what it picked up. Then what it carries: a horse's chest, a villager's, piglin's or
 * allay's pockets, or, for another mod's creature, the item handler it offers (NeoForge's
 * {@code Capabilities.Item.ENTITY}; not the fallback every creature gets, which is only its
 * equipment again), at most {@link #MAX_STORAGE} slots.
 * <p>
 * The client builds the same slots from what {@link #open} sends (which equipment slots, how many
 * carried), carried ones as placeholders the menu fills in, as a vanilla menu's client side does.
 */
public final class PossessionInventory {
    public static final int MAX_STORAGE = 36;
    private static final List<EquipmentSlot> ORDER = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.BODY, EquipmentSlot.SADDLE);

    private PossessionInventory() {}

    /** Opens the inventory of the creature {@code p} possesses, if any. */
    public static void open(ServerPlayer p) {
        if (!(p.getVehicle() instanceof Mob mob) || Possession.possessor(mob) != p) return;
        p.openMenu(new SimpleMenuProvider((id, inv, who) -> menu(id, inv, mob), mob.getDisplayName()), buf -> {
            buf.writeVarInt(mob.getId());
            buf.writeVarInt(equipmentMask(mob));
            buf.writeVarInt(carried(mob).size());
        });
    }

    /** The server's side of the menu: {@code mob}'s real equipment and carried slots. */
    public static ModularUIContainerMenu menu(int id, Inventory inv, Mob mob) {
        return new ModularUIContainerMenu(LogosMod.POSSESSED_INVENTORY.get(), id, inv, new Holder(mob, equipmentMask(mob), carried(mob)));
    }

    /** The client's side of the menu {@link #open} opened. */
    public static ModularUIContainerMenu create(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        Entity e = inv.player.level().getEntity(buf.readVarInt());
        int mask = buf.readVarInt();
        Container placeholders = new SimpleContainer(Math.max(0, Math.min(MAX_STORAGE, buf.readVarInt())));
        List<Slot> carried = new ArrayList<>();
        for (int i = 0; i < placeholders.getContainerSize(); i++) carried.add(new Slot(placeholders, i, 0, 0));
        return new ModularUIContainerMenu(LogosMod.POSSESSED_INVENTORY.get(), id, inv, new Holder(e instanceof Mob m ? m : null, mask, carried));
    }

    private record Holder(@Nullable Mob mob, int mask, List<Slot> carried) implements IContainerUIHolder {
        @Override
        public ModularUI createUI(Player player) {
            return ui(this, player);
        }

        @Override
        public boolean isStillValid(Player player) {
            return mob != null && mob.isAlive() && Possession.possessor(mob) == player;
        }
    }

    /** Which equipment slots it shows: armour and hands always, body armour and saddle on creatures that wear them (or wear them now). */
    static int equipmentMask(Mob mob) {
        int mask = 0;
        for (int i = 0; i < ORDER.size(); i++) {
            EquipmentSlot slot = ORDER.get(i);
            boolean shown = switch (slot) {
                case BODY -> mob.is(EntityTypeTags.CAN_WEAR_HORSE_ARMOR) || mob.is(EntityTypeTags.CAN_WEAR_NAUTILUS_ARMOR)
                        || mob.is(EntityTypeTags.CAN_EQUIP_HARNESS) || mob instanceof Wolf || mob instanceof Llama;
                case SADDLE -> mob.is(EntityTypeTags.CAN_EQUIP_SADDLE);
                default -> true;
            };
            if ((shown || !mob.getItemBySlot(slot).isEmpty()) && mob.canUseSlot(slot)) mask |= 1 << i;
        }
        return mask;
    }

    /** What it carries, as slots: a horse's chest, an inventory carrier's pockets, or another mod's item handler. */
    static List<Slot> carried(Mob mob) {
        List<Slot> slots = new ArrayList<>();
        if (mob instanceof AbstractHorse horse) {
            Container c = horse.getInventory();
            for (int i = 0; i < Math.min(MAX_STORAGE, c.getContainerSize()); i++) slots.add(new Slot(c, i, 0, 0));
        } else if (mob instanceof InventoryCarrier carrier) {
            Container c = carrier.getInventory();
            for (int i = 0; i < Math.min(MAX_STORAGE, c.getContainerSize()); i++) slots.add(new Slot(c, i, 0, 0));
        } else {
            ResourceHandler<ItemResource> handler = mob.getCapability(Capabilities.Item.ENTITY, null);
            // NeoForge's fallback for every creature is its hands and armour, which the equipment row already shows.
            if (handler == null || isFallback(handler)) return slots;
            IndexModifier<ItemResource> modifier = handler instanceof IndexModifier<?> m ? cast(m) : (index, resource, amount) -> replace(handler, index, resource, amount);
            for (int i = 0; i < Math.min(MAX_STORAGE, handler.size()); i++) slots.add(new ResourceHandlerSlot(handler, modifier, i, 0, 0));
        }
        return slots;
    }

    /** NeoForge's fallback item handler for any living entity: its hands and armour, nothing more. */
    private static boolean isFallback(ResourceHandler<ItemResource> handler) {
        Class<?> c = handler.getClass();
        return c == CombinedResourceHandler.class || c == LivingEntityEquipmentWrapper.class;
    }

    @SuppressWarnings("unchecked")
    private static IndexModifier<ItemResource> cast(IndexModifier<?> m) {
        return (IndexModifier<ItemResource>) m;
    }

    /** Sets a slot of a handler that cannot be set directly: takes out what is there and puts the new in, or neither. */
    private static void replace(ResourceHandler<ItemResource> handler, int index, ItemResource resource, int amount) {
        try (var tx = Transaction.openRoot()) {
            ItemResource old = handler.getResource(index);
            int had = handler.getAmountAsInt(index);
            if (!old.isEmpty() && handler.extract(index, old, had, tx) != had) return;
            if (!resource.isEmpty() && handler.insert(index, resource, amount, tx) != amount) return;
            tx.commit();
        }
    }

    private static ModularUI ui(Holder h, Player player) {
        if (h.mob == null) return ModularUI.of(UI.of(new UIElement()), player);
        UIElement equipment = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW));
        for (int i = 0; i < ORDER.size(); i++) {
            if ((h.mask & 1 << i) == 0) continue;
            Worn worn = new Worn(h.mob, ORDER.get(i));
            ItemSlot s = new ItemSlot();
            s.bind(worn);
            Identifier icon = worn.getNoItemIcon();
            if (icon != null) s.slotStyle(st -> st.slotOverlay(VanillaSpriteTexture.of(icon)).showSlotOverlayOnlyEmpty(true));
            equipment.addChild(s);
        }
        UIElement root = new UIElement().addClass("panel_bg").layout(l -> l.paddingAll(7).gapAll(5));
        root.addChildren(new Label().setText(h.mob.getDisplayName()), new Label().setText(Component.translatable("logos.possess.equipment")), equipment);
        if (!h.carried.isEmpty()) {
            root.addChild(new Label().setText(Component.translatable("logos.possess.carried")));
            UIElement row = null;
            for (int i = 0; i < h.carried.size(); i++) {
                if (i % 9 == 0) {
                    row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW));
                    root.addChild(row);
                }
                ItemSlot s = new ItemSlot();
                s.bind(h.carried.get(i));
                row.addChild(s);
            }
        }
        root.addChild(new InventorySlots());
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    /** One of the creature's equipment slots: armour slots take only what fits that slot and creature, hands take anything. */
    private static final class Worn extends Slot {
        private final Mob mob;
        private final EquipmentSlot slot;

        Worn(Mob mob, EquipmentSlot slot) {
            super(mob.createEquipmentSlotContainer(slot), 0, 0, 0);
            this.mob = mob;
            this.slot = slot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return slot.getType() == EquipmentSlot.Type.HAND || stack.canEquip(slot, mob);
        }

        @Override
        public int getMaxStackSize() {
            return slot.getType() == EquipmentSlot.Type.HAND ? super.getMaxStackSize() : 1;
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            mob.onEquipItem(slot, previous, stack);
            super.setByPlayer(stack, previous);
        }

        @Override
        public @Nullable Identifier getNoItemIcon() {
            return switch (slot) {
                case HEAD -> Identifier.withDefaultNamespace("container/slot/helmet");
                case CHEST -> Identifier.withDefaultNamespace("container/slot/chestplate");
                case LEGS -> Identifier.withDefaultNamespace("container/slot/leggings");
                case FEET -> Identifier.withDefaultNamespace("container/slot/boots");
                case MAINHAND -> Identifier.withDefaultNamespace("container/slot/sword");
                case OFFHAND -> Identifier.withDefaultNamespace("container/slot/shield");
                case SADDLE -> Identifier.withDefaultNamespace("container/slot/saddle");
                case BODY -> Identifier.withDefaultNamespace(mob instanceof Llama ? "container/slot/llama_armor" : "container/slot/horse_armor");
                default -> null;
            };
        }
    }
}
