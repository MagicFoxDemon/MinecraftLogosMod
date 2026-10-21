package dev.logos.alchemy;

import java.util.Locale;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

/**
 * The alchemical processes an alchemy table works, each with its alchemical sign. The first four are
 * worked by the table's own apparatus; calcination needs a blast furnace beside it, purification a
 * cauldron, and sublimation a lit beacon, whose beam carries the vapour up to the sky.
 */
public enum Process implements StringRepresentable {
    FERMENT("🝤"), DISSOLVE("🝡"), DISTILL("🝠"), SETTLE("🝟"), CALCINE("🜂"), PURIFY("🝣"), SUBLIME("🝞");

    public static final Codec<Process> CODEC = StringRepresentable.fromEnum(Process::values);

    public final String symbol;

    Process(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the table works it with an apparatus of its own (and a lever); the others are worked in a block beside it. */
    public boolean onTable() {
        return this != CALCINE && this != PURIFY && this != SUBLIME;
    }

    /**
     * Whether the block at {@code pos}, beside the table, works this process: any blast furnace calcines,
     * any cauldron purifies, and a beacon sublimes while it is lit (a pyramid under it, its beam open to the sky).
     */
    public boolean worksAt(Level level, BlockPos pos) {
        return switch (this) {
            case CALCINE -> level.getBlockState(pos).getBlock() instanceof BlastFurnaceBlock;
            case PURIFY -> level.getBlockState(pos).getBlock() instanceof AbstractCauldronBlock;
            case SUBLIME -> level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon
                    && ((dev.logos.mixin.BeaconAccessor) beacon).logos$levels() > 0 && !beacon.getBeamSections().isEmpty();
            default -> false;
        };
    }

    /** Why a card of it waits while the block that works it is not beside the table. */
    public Component missingStation() {
        return Component.translatable(switch (this) {
            case PURIFY -> "logos.alchemy_card.no_cauldron";
            case SUBLIME -> "logos.alchemy_card.no_beacon";
            default -> "logos.alchemy_card.no_furnace";
        });
    }

    /** 发酵, 溶解, 蒸馏, 沉淀, 煅烧, 净化, 升华. */
    public Component displayName() {
        return Component.translatable("logos.process." + getSerializedName());
    }
}
