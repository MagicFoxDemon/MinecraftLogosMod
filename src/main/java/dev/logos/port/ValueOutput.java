package dev.logos.port;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

/** Stands in for 26.1's {@code net.minecraft.world.level.storage.ValueOutput}; {@link TagValueOutput} writes it to NBT. */
public interface ValueOutput {
    <T> void store(String name, Codec<T> codec, T value);

    <T> void storeNullable(String name, Codec<T> codec, @Nullable T value);

    <T> void store(MapCodec<T> codec, T value);

    void putBoolean(String name, boolean value);

    void putByte(String name, byte value);

    void putShort(String name, short value);

    void putInt(String name, int value);

    void putLong(String name, long value);

    void putFloat(String name, float value);

    void putDouble(String name, double value);

    void putString(String name, String value);

    void putIntArray(String name, int[] value);

    ValueOutput child(String name);

    ValueOutputList childrenList(String name);

    <T> TypedOutputList<T> list(String name, Codec<T> codec);

    void discard(String name);

    boolean isEmpty();

    interface TypedOutputList<T> {
        void add(T value);

        boolean isEmpty();
    }

    interface ValueOutputList {
        ValueOutput addChild();

        void discardLast();

        boolean isEmpty();
    }
}
