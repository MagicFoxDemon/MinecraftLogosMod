package dev.logos.alchemy;

import java.util.Locale;

import com.mojang.serialization.Codec;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.Block;

/**
 * The alchemical processes an alchemy table works, each with its alchemical sign. The first four are
 * worked by the table's own apparatus; calcination needs a blast furnace beside it, and
 * purification a cauldron.
 */
public enum Process implements StringRepresentable {
    FERMENT("🝞"), DISSOLVE("🝜"), DISTILL("🝚"), SETTLE("🝙"), CALCINE("🜂"), PURIFY("🝣");

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
        return this != CALCINE && this != PURIFY;
    }

    /** Whether {@code block}, beside the table, works this process: any blast furnace calcines, any cauldron purifies. */
    public boolean worksAt(Block block) {
        return switch (this) {
            case CALCINE -> block instanceof BlastFurnaceBlock;
            case PURIFY -> block instanceof AbstractCauldronBlock;
            default -> false;
        };
    }

    /** Why a card of it waits while the block that works it is not beside the table. */
    public Component missingStation() {
        return Component.translatable(this == PURIFY ? "logos.alchemy_card.no_cauldron" : "logos.alchemy_card.no_furnace");
    }

    /** 发酵, 溶解, 蒸馏, 沉淀, 煅烧, 净化. */
    public Component displayName() {
        return Component.translatable("logos.process." + getSerializedName());
    }
}
