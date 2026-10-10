package dev.logos.block;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Holds the one spell page lying on an inscription table; synced to clients so the editor can open it. */
public class InscriptionTableBlockEntity extends BlockEntity {
    private ItemStack page = ItemStack.EMPTY;

    public InscriptionTableBlockEntity(BlockPos pos, BlockState state) {
        super(LogosMod.INSCRIPTION_TABLE_BE.get(), pos, state);
    }

    public ItemStack page() {
        return page;
    }

    public void setPage(ItemStack stack) {
        this.page = stack;
        setChanged();
        if (level == null) return;
        BlockState state = getBlockState();
        BlockState shown = state.hasProperty(InscriptionTableBlock.PAGE) ? state.setValue(InscriptionTableBlock.PAGE, InscriptionTableBlock.Page.of(stack)) : state;
        if (shown != state && !level.isClientSide()) level.setBlock(worldPosition, shown, Block.UPDATE_ALL);
        else level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!page.isEmpty()) output.store("page", ItemStack.CODEC, page);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        page = input.read("page", ItemStack.CODEC).orElse(ItemStack.EMPTY);
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
        if (level != null && !page.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, page);
        page = ItemStack.EMPTY;
    }
}
