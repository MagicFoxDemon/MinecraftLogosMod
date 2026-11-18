package dev.logos.client.port;

import net.minecraft.world.level.Level;

/** Stands in for 26.1's {@code SlotDisplayContext}: what a recipe display resolves with in {@code level}. */
public final class SlotDisplayContext {
    private SlotDisplayContext() {}

    public static ContextMap fromLevel(Level level) {
        return new ContextMap(level.registryAccess());
    }
}
