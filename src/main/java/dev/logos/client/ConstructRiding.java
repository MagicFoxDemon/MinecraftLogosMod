package dev.logos.client;

import java.util.Map;
import java.util.WeakHashMap;

import dev.logos.runtime.ConstructEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Players move on their own client, so only the client can carry its player on a moving
 * construct. Before anything ticks, each construct's movement since the last tick is applied to
 * the local player if they stood on it, so a rising floor lifts them instead of rising into them.
 * Constructs that follow their caster are not ridden: they move with the caster already.
 */
final class ConstructRiding {
    private static final Map<ConstructEntity, Vec3> LAST = new WeakHashMap<>();

    private ConstructRiding() {}

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !ConstructEntity.any()) {
            LAST.clear();
            return;
        }
        for (ConstructEntity c : ConstructEntity.near(mc.level, new AABB(player.position(), player.position()).inflate(64))) {
            Vec3 now = c.position();
            Vec3 before = LAST.put(c, now);
            if (before == null || c.follows()) continue;
            Vec3 delta = now.subtract(before);
            if (delta.lengthSqr() < 1e-10 || player.isPassenger() || player.getAbilities().flying) continue;
            if (!ConstructEntity.standsOn(player, c.geometry().translated(delta.reverse()))) continue;
            player.setPos(player.position().add(delta));
            // Shift last tick's position too, so the frame interpolation does not show a jump.
            player.xo += delta.x;
            player.yo += delta.y;
            player.zo += delta.z;
            player.xOld += delta.x;
            player.yOld += delta.y;
            player.zOld += delta.z;
        }
    }
}
