package dev.logos.alchemy;

import java.util.Locale;

import com.mojang.serialization.Codec;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/** The four alchemical processes an alchemy table works, each with its alchemical sign. */
public enum Process implements StringRepresentable {
    FERMENT("🝞"), DISSOLVE("🝜"), DISTILL("🝚"), SETTLE("🝙");

    public static final Codec<Process> CODEC = StringRepresentable.fromEnum(Process::values);

    public final String symbol;

    Process(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 发酵, 溶解, 蒸馏, 沉淀. */
    public Component displayName() {
        return Component.translatable("logos.process." + getSerializedName());
    }
}
