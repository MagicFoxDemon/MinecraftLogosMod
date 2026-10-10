package dev.logos.net;

import java.util.Collection;
import java.util.List;

import dev.logos.LogosMod;
import dev.logos.runtime.Anchors;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: the player's anchors, for the editor's ⚓ list, the anchor screen and the in-world markers. */
public record AnchorsPayload(List<Entry> anchors) implements CustomPacketPayload {
    public record Entry(String name, String dimension, double x, double y, double z) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Entry::name, ByteBufCodecs.STRING_UTF8, Entry::dimension,
                ByteBufCodecs.DOUBLE, Entry::x, ByteBufCodecs.DOUBLE, Entry::y, ByteBufCodecs.DOUBLE, Entry::z, Entry::new);
    }

    public static final Type<AnchorsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "anchors"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AnchorsPayload> CODEC = StreamCodec.composite(
            Entry.CODEC.apply(ByteBufCodecs.list()), AnchorsPayload::anchors, AnchorsPayload::new);

    public static AnchorsPayload of(Collection<Anchors.Anchor> all) {
        return new AnchorsPayload(all.stream()
                .map(a -> new Entry(a.name(), a.dimension().identifier().toString(), a.pos().x, a.pos().y, a.pos().z)).toList());
    }

    @Override
    public Type<AnchorsPayload> type() {
        return TYPE;
    }
}
