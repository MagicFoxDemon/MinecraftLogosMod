package dev.logos.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** A bounded world-space wake behind the interpolated head, including bends in the reported path. */
final class ProjectilePath {
    private final List<Vec3> history = new ArrayList<>();
    private Vec3 position;

    ProjectilePath(Vec3 position) { this.position = position; }

    void move(Vec3 position) {
        if (this.position.distanceToSqr(position) > 1e-6) {
            history.addFirst(this.position);
            if (history.size() > 14) history.removeLast();
        }
        this.position = position;
    }

    List<Vec3> samples(Vec3 origin, Vec3 heading, double extent, double lead) {
        List<Vec3> samples = new ArrayList<>();
        samples.add(origin.add(heading.scale(lead)));
        Vec3 last = origin;
        double length = 0;
        for (Vec3 p : history) {
            if (p.subtract(origin).dot(heading) > 0) continue;
            double segment = p.distanceTo(last);
            if (segment < .05) continue;
            if (length + segment >= extent) {
                samples.add(last.lerp(p, (extent - length) / segment));
                length = extent;
                break;
            }
            samples.add(p);
            length += segment;
            last = p;
        }
        if (length < extent) samples.add(last.subtract(heading.scale(extent - length)));
        return samples;
    }

    static Vec3 point(List<Vec3> points, float t) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) total += points.get(i).distanceTo(points.get(i - 1));
        double distance = total * t;
        for (int i = 1; i < points.size(); i++) {
            double segment = points.get(i).distanceTo(points.get(i - 1));
            if (distance <= segment) return points.get(i - 1).lerp(points.get(i), segment < 1e-6 ? 0 : distance / segment);
            distance -= segment;
        }
        return points.getLast();
    }
}
