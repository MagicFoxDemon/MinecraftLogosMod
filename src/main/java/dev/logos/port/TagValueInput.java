package dev.logos.port;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Stands in for 26.1's {@code net.minecraft.world.level.storage.TagValueInput}: a {@link ValueInput} that reads a
 * compound tag. A missing field, or one of the wrong type, reads as absent; what fails to decode is skipped, as with
 * 26.1's discarding reporter.
 */
public final class TagValueInput implements ValueInput {
    private final HolderLookup.Provider lookup;
    private final DynamicOps<Tag> ops;
    private final CompoundTag input;

    private TagValueInput(HolderLookup.Provider lookup, DynamicOps<Tag> ops, CompoundTag input) {
        this.lookup = lookup;
        this.ops = ops;
        this.input = input;
    }

    public static ValueInput create(ProblemReporter reporter, HolderLookup.Provider lookup, CompoundTag tag) {
        return new TagValueInput(lookup, lookup.createSerializationContext(NbtOps.INSTANCE), tag);
    }

    public static ValueInputList create(ProblemReporter reporter, HolderLookup.Provider lookup, List<CompoundTag> tags) {
        DynamicOps<Tag> ops = lookup.createSerializationContext(NbtOps.INSTANCE);
        return children(tags.stream().map(tag -> (ValueInput) new TagValueInput(lookup, ops, tag)).toList());
    }

    @Override
    public <T> Optional<T> read(String name, Codec<T> codec) {
        Tag tag = input.get(name);
        return tag == null ? Optional.empty() : codec.parse(ops, tag).resultOrPartial(error -> {});
    }

    @Override
    public <T> Optional<T> read(MapCodec<T> codec) {
        return codec.codec().parse(ops, input).resultOrPartial(error -> {});
    }

    private @Nullable Tag typed(String name, byte type) {
        Tag tag = input.get(name);
        return tag != null && tag.getId() == type ? tag : null;
    }

    private @Nullable NumericTag numeric(String name) {
        return input.get(name) instanceof NumericTag n ? n : null;
    }

    @Override
    public Optional<ValueInput> child(String name) {
        return typed(name, Tag.TAG_COMPOUND) instanceof CompoundTag c ? Optional.of(new TagValueInput(lookup, ops, c)) : Optional.empty();
    }

    @Override
    public ValueInput childOrEmpty(String name) {
        return child(name).orElseGet(() -> new TagValueInput(lookup, ops, new CompoundTag()));
    }

    @Override
    public Optional<ValueInputList> childrenList(String name) {
        if (!(typed(name, Tag.TAG_LIST) instanceof ListTag list)) return Optional.empty();
        List<ValueInput> children = list.stream().filter(t -> t instanceof CompoundTag)
                .map(t -> (ValueInput) new TagValueInput(lookup, ops, (CompoundTag) t)).toList();
        return Optional.of(children(children));
    }

    @Override
    public ValueInputList childrenListOrEmpty(String name) {
        return childrenList(name).orElseGet(() -> children(List.of()));
    }

    @Override
    public <T> Optional<TypedInputList<T>> list(String name, Codec<T> codec) {
        if (!(typed(name, Tag.TAG_LIST) instanceof ListTag list)) return Optional.empty();
        List<T> values = list.stream().flatMap(t -> codec.parse(ops, t).resultOrPartial(error -> {}).stream()).toList();
        return Optional.of(typedList(values));
    }

    @Override
    public <T> TypedInputList<T> listOrEmpty(String name, Codec<T> codec) {
        return list(name, codec).orElseGet(() -> typedList(List.of()));
    }

    @Override
    public boolean getBooleanOr(String name, boolean defaultValue) {
        NumericTag n = numeric(name);
        return n != null ? n.getAsByte() != 0 : defaultValue;
    }

    @Override
    public byte getByteOr(String name, byte defaultValue) {
        NumericTag n = numeric(name);
        return n != null ? n.getAsByte() : defaultValue;
    }

    @Override
    public int getShortOr(String name, short defaultValue) {
        NumericTag n = numeric(name);
        return n != null ? n.getAsShort() : defaultValue;
    }

    @Override
    public Optional<Integer> getInt(String name) {
        NumericTag n = numeric(name);
        return n != null ? Optional.of(n.getAsInt()) : Optional.empty();
    }

    @Override
    public int getIntOr(String name, int defaultValue) {
        NumericTag n = numeric(name);
        return n != null ? n.getAsInt() : defaultValue;
    }

    @Override
    public long getLongOr(String name, long defaultValue) {
        NumericTag n = numeric(name);
        return n != null ? n.getAsLong() : defaultValue;
    }

    @Override
    public Optional<Long> getLong(String name) {
        NumericTag n = numeric(name);
        return n != null ? Optional.of(n.getAsLong()) : Optional.empty();
    }

    @Override
    public float getFloatOr(String name, float defaultValue) {
        NumericTag n = numeric(name);
        return n != null ? n.getAsFloat() : defaultValue;
    }

    @Override
    public double getDoubleOr(String name, double defaultValue) {
        NumericTag n = numeric(name);
        return n != null ? n.getAsDouble() : defaultValue;
    }

    @Override
    public Optional<String> getString(String name) {
        return typed(name, Tag.TAG_STRING) instanceof StringTag s ? Optional.of(s.getAsString()) : Optional.empty();
    }

    @Override
    public String getStringOr(String name, String defaultValue) {
        return getString(name).orElse(defaultValue);
    }

    @Override
    public Optional<int[]> getIntArray(String name) {
        return typed(name, Tag.TAG_INT_ARRAY) instanceof IntArrayTag a ? Optional.of(a.getAsIntArray()) : Optional.empty();
    }

    /** The compound it reads. */
    CompoundTag tag() {
        return input;
    }

    @Override
    public HolderLookup.Provider lookup() {
        return lookup;
    }

    private static ValueInputList children(List<ValueInput> children) {
        return new ValueInputList() {
            @Override
            public boolean isEmpty() {
                return children.isEmpty();
            }

            @Override
            public Stream<ValueInput> stream() {
                return children.stream();
            }

            @Override
            public Iterator<ValueInput> iterator() {
                return children.iterator();
            }
        };
    }

    private static <T> TypedInputList<T> typedList(List<T> values) {
        return new TypedInputList<>() {
            @Override
            public boolean isEmpty() {
                return values.isEmpty();
            }

            @Override
            public Stream<T> stream() {
                return values.stream();
            }

            @Override
            public Iterator<T> iterator() {
                return values.iterator();
            }
        };
    }
}
