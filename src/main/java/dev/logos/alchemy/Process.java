package dev.logos.alchemy;

import java.util.Locale;

import com.mojang.serialization.Codec;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/**
 * The alchemical processes an alchemy table works, each with its alchemical sign. The first four are
 * worked by the table's own apparatus; calcination needs a blast furnace beside it.
 */
public enum Process implements StringRepresentable {
    FERMENT("🝞"), DISSOLVE("🝜"), DISTILL("🝚"), SETTLE("🝙"), CALCINE("🜂");

    public static final Codec<Process> CODEC = StringRepresentable.fromEnum(Process::values);

    public final String symbol;

    Process(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the table works it with an apparatus of its own (and a lever); calcination is worked in a blast furnace beside it. */
    public boolean onTable() {
        return this != CALCINE;
    }

    /** 发酵, 溶解, 蒸馏, 沉淀, 煅烧. */
    public Component displayName() {
        return Component.translatable("logos.process." + getSerializedName());
    }
}
