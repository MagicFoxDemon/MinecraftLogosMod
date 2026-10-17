package dev.logos.net;

import java.util.List;

import dev.logos.LogosMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: every area weather in one dimension, sent whole whenever one starts, moves or ends. */
public record AreaWeatherPayload(String dimension, List<Zone> zones) implements CustomPacketPayload {
    /** {@code kind} is a {@link dev.logos.lang.Weather} ordinal. */
    public record Zone(int id, int kind, double x, double z, float radius) {
        static final StreamCodec<RegistryFriendlyByteBuf, Zone> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Zone::id, ByteBufCodecs.VAR_INT, Zone::kind, ByteBufCodecs.DOUBLE, Zone::x,
                ByteBufCodecs.DOUBLE, Zone::z, ByteBufCodecs.FLOAT, Zone::radius, Zone::new);
    }

    public static final Type<AreaWeatherPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "area_weather"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AreaWeatherPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AreaWeatherPayload::dimension, Zone.CODEC.apply(ByteBufCodecs.list()), AreaWeatherPayload::zones,
            AreaWeatherPayload::new);

    @Override
    public Type<AreaWeatherPayload> type() {
        return TYPE;
    }
}
