package dev.logos.port;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * Stands in for 26.1's {@code net.minecraft.world.level.storage.TagValueOutput}: a {@link ValueOutput} that writes into
 * a compound tag. What fails to encode is left out, as with 26.1's discarding reporter. {@link #of} wraps the tag
 * 1.21.1 hands to {@code saveAdditional}, so the code that fills it stays as it is on 26.1.
 */
public final class TagValueOutput implements ValueOutput {
    private final HolderLookup.Provider lookup;
    private final DynamicOps<Tag> ops;
    private final CompoundTag output;

    private TagValueOutput(HolderLookup.Provider lookup, DynamicOps<Tag> ops, CompoundTag output) {
        this.lookup = lookup;
        this.ops = ops;
        this.output = output;
    }

    /** The registries it encodes with. */
    HolderLookup.Provider lookup() {
        return lookup;
    }

    public static TagValueOutput createWithContext(ProblemReporter reporter, HolderLookup.Provider provider) {
        return of(new CompoundTag(), provider);
    }

    public static TagValueOutput createWithoutContext(ProblemReporter reporter) {
        return new TagValueOutput(net.minecraft.core.RegistryAccess.EMPTY, NbtOps.INSTANCE, new CompoundTag());
    }

    /** Writes into {@code tag}, encoding registry entries with {@code provider}. */
    public static TagValueOutput of(CompoundTag tag, HolderLookup.Provider provider) {
        return new TagValueOutput(provider, provider.createSerializationContext(NbtOps.INSTANCE), tag);
    }

    @Override
    public <T> void store(String name, Codec<T> codec, T value) {
        codec.encodeStart(ops, value).resultOrPartial(error -> {}).ifPresent(tag -> output.put(name, tag));
    }

    @Override
    public <T> void storeNullable(String name, Codec<T> codec, @Nullable T value) {
        if (value != null) store(name, codec, value);
    }

    @Override
    public <T> void store(MapCodec<T> codec, T value) {
        codec.codec().encodeStart(ops, value).resultOrPartial(error -> {}).ifPresent(tag -> {
            if (tag instanceof CompoundTag compound) output.merge(compound);
        });
    }

    @Override
    public void putBoolean(String name, boolean value) {
        output.putBoolean(name, value);
    }

    @Override
    public void putByte(String name, byte value) {
        output.putByte(name, value);
    }

    @Override
    public void putShort(String name, short value) {
        output.putShort(name, value);
    }

    @Override
    public void putInt(String name, int value) {
        output.putInt(name, value);
    }

    @Override
    public void putLong(String name, long value) {
        output.putLong(name, value);
    }

    @Override
    public void putFloat(String name, float value) {
        output.putFloat(name, value);
    }

    @Override
    public void putDouble(String name, double value) {
        output.putDouble(name, value);
    }

    @Override
    public void putString(String name, String value) {
        output.putString(name, value);
    }

    @Override
    public void putIntArray(String name, int[] value) {
        output.putIntArray(name, value);
    }

    @Override
    public ValueOutput child(String name) {
        CompoundTag child = new CompoundTag();
        output.put(name, child);
        return new TagValueOutput(lookup, ops, child);
    }

    @Override
    public ValueOutputList childrenList(String name) {
        ListTag list = new ListTag();
        output.put(name, list);
        return new ValueOutputList() {
            @Override
            public ValueOutput addChild() {
                CompoundTag child = new CompoundTag();
                list.add(child);
                return new TagValueOutput(lookup, ops, child);
            }

            @Override
            public void discardLast() {
                list.remove(list.size() - 1);
            }

            @Override
            public boolean isEmpty() {
                return list.isEmpty();
            }
        };
    }

    @Override
    public <T> TypedOutputList<T> list(String name, Codec<T> codec) {
        ListTag list = new ListTag();
        output.put(name, list);
        return new TypedOutputList<>() {
            @Override
            public void add(T value) {
                codec.encodeStart(ops, value).resultOrPartial(error -> {}).ifPresent(list::add);
            }

            @Override
            public boolean isEmpty() {
                return list.isEmpty();
            }
        };
    }

    @Override
    public void discard(String name) {
        output.remove(name);
    }

    @Override
    public boolean isEmpty() {
        return output.isEmpty();
    }

    public CompoundTag buildResult() {
        return output;
    }
}
