package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import dev.logos.LogosConfig;
import net.minecraft.world.entity.Entity;

/**
 * Entities a selector gathered, remembering the radius it searched. A group effect prices its reach
 * from that radius, so the group keeps it through variables; a list without one (the hit-list) is
 * priced at the reference radius.
 */
public final class Group extends ArrayList<Entity> {
    /** The radius at which a group effect pays exactly its base multiple: {@code enemies}' default. */
    public static final double REFERENCE_RADIUS = 5;

    public final double radius;

    public Group(double radius, Collection<? extends Entity> entities) {
        super(entities);
        this.radius = radius;
    }

    /** A selector's {@code :radius}, held to the configured largest. */
    public static double clampRadius(double radius) {
        return Math.clamp(radius, 0, LogosConfig.maxGroupRadius());
    }

    public static double radiusOf(List<?> list) {
        return list instanceof Group g ? g.radius : REFERENCE_RADIUS;
    }

    /** How many times the single-target price a group effect over {@code radius} pays: the base multiple, growing as (r / 5)^k beyond 5. */
    public static double costMultiplier(double radius) {
        return LogosConfig.groupCostMultiplier() * Math.max(1, Math.pow(radius / REFERENCE_RADIUS, LogosConfig.groupRadiusExponent()));
    }
}
