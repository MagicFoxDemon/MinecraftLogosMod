package dev.logos.runtime;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The body a possessing player leaves standing (附身), drawn as them with their skin. 26.1 uses its mannequin for this;
 * 1.21.1 has none, so this is a living entity of the mod's own that only stands there: it is hurt, and creatures go for
 * it, and {@link Possession} passes what hurts it on to its owner. Never saved, as a session never is.
 */
public final class PossessionBody extends LivingEntity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(PossessionBody.class, EntityDataSerializers.OPTIONAL_UUID);
    private final NonNullList<ItemStack> armour = NonNullList.withSize(4, ItemStack.EMPTY);
    /** What is put in its hands, as 26.1's mannequin holds it (and drawn posed, see PossessionBodyRenderer). */
    private final NonNullList<ItemStack> hands = NonNullList.withSize(2, ItemStack.EMPTY);

    public PossessionBody(EntityType<? extends PossessionBody> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OWNER, Optional.empty());
    }

    /** The player whose body it is: their skin, on clients. */
    public @Nullable UUID owner() {
        return entityData.get(OWNER).orElse(null);
    }

    public void setOwner(UUID owner) {
        entityData.set(OWNER, Optional.of(owner));
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return armour;
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return switch (slot.getType()) {
            case HUMANOID_ARMOR -> armour.get(slot.getIndex());
            case HAND -> hands.get(slot.getIndex());
            default -> ItemStack.EMPTY;
        };
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) armour.set(slot.getIndex(), stack);
        else if (slot.getType() == EquipmentSlot.Type.HAND) hands.set(slot.getIndex(), stack);
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    /** It stands still: nothing pushes it off where its owner left it. */
    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {}
}
