package dev.logos.port;

/** Stands in for 26.1's {@code net.neoforged.neoforge.transfer.IndexModifier}: sets an index of a handler outright, as a menu slot does. */
@FunctionalInterface
public interface IndexModifier<T extends Resource> {
    void set(int index, T resource, int amount);
}
