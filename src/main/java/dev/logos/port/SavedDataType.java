package dev.logos.port;

import java.util.function.Supplier;

import com.mojang.serialization.Codec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Stands in for 26.1's {@code net.minecraft.world.level.saveddata.SavedDataType}: saved data kept with the world under
 * {@code id}, written with {@code codec}. 26.1's {@code server.getDataStorage().computeIfAbsent(type)} is
 * {@link #computeIfAbsent} here, kept in the overworld's storage as 26.1 keeps it in the server's; the data's own
 * {@code save} hands the work to {@link #save}.
 */
public record SavedDataType<T extends SavedData>(ResourceLocation id, Supplier<T> constructor, Codec<T> codec) {
    public T computeIfAbsent(MinecraftServer server) {
        SavedData.Factory<T> factory = new SavedData.Factory<>(constructor, this::load, null);
        return server.overworld().getDataStorage().computeIfAbsent(factory, id.getNamespace() + "_" + id.getPath().replace('/', '_'));
    }

    private T load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("data")) return constructor.get();
        return codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get("data")).resultOrPartial(error -> {}).orElseGet(constructor);
    }

    public CompoundTag save(T data, CompoundTag tag, HolderLookup.Provider registries) {
        codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), data).resultOrPartial(error -> {}).ifPresent(t -> tag.put("data", t));
        return tag;
    }
}
