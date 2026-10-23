package dev.logos.runtime;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Even random points in simple shapes, for 择位 (spot): around a place, in a field, and (through
 * {@link ConstructGeometry#sample}) in a construct. Shapes are centred on the origin.
 */
public final class Spots {
    private Spots() {}

    /** Around {@code at}: in or on a ball, a level disk (its surface is the rim) or a cube of half-edge {@code r}. */
    public static Vec3 around(RandomSource rand, Vec3 at, double r, String shape, boolean surface) {
        return at.add(switch (shape) {
            case "disk" -> inDisk(rand, r, surface);
            case "cube" -> surface ? onBox(rand, 2 * r, 2 * r, 2 * r) : inBox(rand, 2 * r, 2 * r, 2 * r);
            default -> surface ? onSphere(rand, r) : inBall(rand, r);
        });
    }

    /** In a form: a construct's own shape, a field's sphere (its surface is the circle it is drawn as), else where it is. */
    public static Vec3 in(RandomSource rand, SpellObject form, boolean surface) {
        if (form instanceof ConstructObj c) return c.geometry().sample(rand, surface);
        if (form instanceof FieldObj f) return f.pos().add(surface ? inDisk(rand, f.radius(), false) : inBall(rand, f.radius()));
        return form.pos();
    }

    /** Uniform in [-extent/2, extent/2]. */
    static double span(RandomSource r, double extent) {
        return (r.nextDouble() - 0.5) * extent;
    }

    /** -1 or 1. */
    static int side(RandomSource r) {
        return r.nextBoolean() ? 1 : -1;
    }

    static Vec3 onSphere(RandomSource r, double radius) {
        double y = 2 * r.nextDouble() - 1, a = 2 * Math.PI * r.nextDouble(), s = Math.sqrt(1 - y * y);
        return new Vec3(s * Math.cos(a), y, s * Math.sin(a)).scale(radius);
    }

    static Vec3 inBall(RandomSource r, double radius) {
        return onSphere(r, radius * Math.cbrt(r.nextDouble()));
    }

    /** A level disk (x, z), or its rim. */
    static Vec3 inDisk(RandomSource r, double radius, boolean rim) {
        double d = rim ? radius : radius * Math.sqrt(r.nextDouble()), a = 2 * Math.PI * r.nextDouble();
        return new Vec3(d * Math.cos(a), 0, d * Math.sin(a));
    }

    static Vec3 inBox(RandomSource r, double w, double h, double d) {
        return new Vec3(span(r, w), span(r, h), span(r, d));
    }

    /** On a box's faces, each face as likely as its area. */
    static Vec3 onBox(RandomSource r, double w, double h, double d) {
        double ax = h * d, ay = w * d, az = w * h, pick = r.nextDouble() * (ax + ay + az);
        if (pick < ax) return new Vec3(side(r) * w / 2, span(r, h), span(r, d));
        if (pick < ax + ay) return new Vec3(span(r, w), side(r) * h / 2, span(r, d));
        return new Vec3(span(r, w), span(r, h), side(r) * d / 2);
    }
}
