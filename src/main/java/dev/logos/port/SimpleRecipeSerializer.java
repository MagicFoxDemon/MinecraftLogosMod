package dev.logos.port;

import com.mojang.serialization.MapCodec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;

/** Stands in for 26.1's {@code new RecipeSerializer<>(codec, streamCodec)}: on 1.21.1 a recipe serializer is an interface. */
public record SimpleRecipeSerializer<T extends Recipe<?>>(MapCodec<T> codec, StreamCodec<RegistryFriendlyByteBuf, T> streamCodec)
        implements RecipeSerializer<T> {}
