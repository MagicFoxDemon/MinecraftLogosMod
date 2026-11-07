package dev.logos.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** A mana vessel whose saved data also goes to clients, for what they draw; collectors and spreaders do not send theirs. */
public abstract class SyncedManaVesselBlockEntity extends ManaVesselBlockEntity {
    protected SyncedManaVesselBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, double maxMana) {
        super(type, pos, state, maxMana);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
