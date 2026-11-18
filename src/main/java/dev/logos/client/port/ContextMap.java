package dev.logos.client.port;

import net.minecraft.core.HolderLookup;

/** Stands in for 26.1's {@code net.minecraft.util.context.ContextMap}, as recipe displays use it: the registries they resolve with. */
public record ContextMap(HolderLookup.Provider registries) {}
