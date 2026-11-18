package dev.logos.runtime;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import dev.logos.port.Capabilities;
import dev.logos.port.IndexModifier;
import dev.logos.port.ResourceHandler;
import dev.logos.port.ItemResource;
import dev.logos.port.ResourceHandlerSlot;
import dev.logos.port.Transaction;

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
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.BODY);

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
                // 1.21.1: no saddle slot; a horse's saddle is among what it carries.
                case BODY -> mob instanceof AbstractHorse || mob instanceof Wolf;
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
        return Capabilities.isEquipmentFallback(handler);
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
            var icon = worn.getNoItemIcon();
            // 1.21.1: the empty-slot pictures are item textures, not GUI sprites.
            if (icon != null) s.slotStyle(st -> st.slotOverlay(com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture.of(icon.getSecond().withPath(p -> "textures/" + p + ".png")))
                    .showSlotOverlayOnlyEmpty(true));
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

    /** One equipment slot of a creature as a container of one, for a menu slot. */
    private record EquipmentContainer(Mob mob, EquipmentSlot slot) implements Container {
        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return mob.getItemBySlot(slot).isEmpty();
        }

        @Override
        public ItemStack getItem(int index) {
            return mob.getItemBySlot(slot);
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            ItemStack taken = mob.getItemBySlot(slot).split(count);
            if (!taken.isEmpty()) setChanged();
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            ItemStack was = mob.getItemBySlot(slot);
            mob.setItemSlot(slot, ItemStack.EMPTY);
            return was;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            mob.setItemSlot(slot, stack);
        }

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(Player player) {
            return mob.isAlive();
        }

        @Override
        public void clearContent() {
            mob.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    /** One of the creature's equipment slots: armour slots take only what fits that slot and creature, hands take anything. */
    private static final class Worn extends Slot {
        private final Mob mob;
        private final EquipmentSlot slot;

        Worn(Mob mob, EquipmentSlot slot) {
            super(new EquipmentContainer(mob, slot), 0, 0, 0);
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
        public @Nullable com.mojang.datafixers.util.Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            ResourceLocation icon = switch (slot) {
                case HEAD -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
                case CHEST -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
                case LEGS -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
                case FEET -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
                case OFFHAND -> net.minecraft.world.inventory.InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;
                default -> null;
            };
            return icon == null ? null : com.mojang.datafixers.util.Pair.of(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS, icon);
        }
    }
}
