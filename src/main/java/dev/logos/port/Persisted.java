package dev.logos.port;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;

/**
 * LDLib2's persisted objects (node graphs) save through 26.1's value IO there and through NBT here: 26.1's
 * {@code target.serialize(output)} and {@code target.deserialize(input)} are these two, given the stand-ins.
 */
public final class Persisted {
    private Persisted() {}

    public static void serialize(IPersistedSerializable target, ValueOutput output) {
        TagValueOutput out = (TagValueOutput) output;
        out.buildResult().merge(target.serializeNBT(out.lookup()));
    }

    public static void deserialize(IPersistedSerializable target, ValueInput input) {
        TagValueInput in = (TagValueInput) input;
        target.deserializeNBT(in.lookup(), in.tag());
    }
}
