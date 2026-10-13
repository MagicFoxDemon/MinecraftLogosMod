package dev.logos.client;

import dev.logos.LogosMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * What a wand charge looks like, drawn on each client from the synced charge state: motes of the
 * spell's element drawn into the wand head while it charges, a steady glow there once full. Bigger
 * charges add more: a ring round the head (elaborate), a circle on the ground (grand), and a second
 * circle with rising sparks (colossal). A first pass to be replaced by real art, but already shaped
 * by element and tier.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ChargeEffects {
    private ChargeEffects() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        long now = ClientCharges.ticks();
        for (var e : ClientCharges.all().entrySet()) {
            if (!(level.getEntity(e.getKey()) instanceof LivingEntity holder)) continue;
            Vec3 tip = WandTips.sample(e.getKey());
            spawn(level, holder, e.getValue(), tip != null ? tip : guessTip(holder), now);
        }
    }

    /** About where a wand head is when it was not drawn just now: ahead, below the eyes, to the right. */
    static Vec3 guessTip(LivingEntity holder) {
        Vec3 look = holder.getLookAngle();
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? Vec3.ZERO : right.normalize();
        return holder.getEyePosition().add(look.scale(0.9)).add(right.scale(0.4)).add(0, -0.45, 0);
    }

    static void spawn(ClientLevel level, LivingEntity holder, ClientCharges.State s, Vec3 tip, long now) {
        RandomSource r = level.getRandom();
        int rgb = ClientCharges.color(s.element);
        if (!s.full) {
            // Motes gathering into the head from around it, more and from further for bigger charges.
            for (int i = 0; i <= s.tier; i++) {
                Vec3 from = tip.add(randomUnit(r).scale(0.5 + 0.35 * s.tier + r.nextDouble() * 0.3));
                level.addParticle(new TrailParticleOption(tip, rgb, 6 + r.nextInt(6)), from.x, from.y, from.z, 0, 0, 0);
            }
        } else {
            level.addParticle(new DustParticleOptions(rgb, 0.5f + 0.15f * s.tier), tip.x, tip.y, tip.z, 0, 0, 0);
            if (now == s.fullSince) {
                // The moment it fills: a flash thrown outward from the head.
                for (int i = 0; i < 14 + 6 * s.tier; i++) {
                    Vec3 to = tip.add(randomUnit(r).scale(0.6 + 0.2 * s.tier));
                    level.addParticle(new TrailParticleOption(to, rgb, 5 + r.nextInt(4)), tip.x, tip.y, tip.z, 0, 0, 0);
                }
                // A bright knot at the head itself; the vanilla flash particle is screen-sized up close.
                for (int i = 0; i < 4; i++) {
                    Vec3 at = tip.add(randomUnit(r).scale(0.06));
                    level.addParticle(new DustParticleOptions(rgb, 1.6f), at.x, at.y, at.z, 0, 0, 0);
                }
            }
        }
        if (now % 2 != 0) return;
        float spin = now * 0.12f;
        if (s.tier >= 1) ring(level, tip, holder.getLookAngle(), 0.22 + 0.05 * s.tier, 8, spin, rgb, 0.35f);
        Vec3 feet = holder.position().add(0, 0.06, 0);
        if (s.tier >= 2) circle(level, feet, 1.1, 22, spin, rgb, 0.7f);
        if (s.tier >= 3) {
            circle(level, feet, 2.1, 34, -spin * 0.7f, rgb, 0.9f);
            double a = r.nextDouble() * Math.PI * 2;
            level.addParticle(ParticleTypes.END_ROD, feet.x + Math.cos(a) * 2.1, feet.y, feet.z + Math.sin(a) * 2.1, 0, 0.12, 0);
        }
    }

    /** Dots round {@code centre} in the plane facing {@code normal}, turned by {@code spin}. */
    private static void ring(ClientLevel level, Vec3 centre, Vec3 normal, double radius, int dots, float spin, int rgb, float size) {
        Vec3 u = normal.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1e-6) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = normal.cross(u).normalize();
        for (int i = 0; i < dots; i++) {
            double a = spin + 2 * Math.PI * i / dots;
            Vec3 p = centre.add(u.scale(Math.cos(a) * radius)).add(v.scale(Math.sin(a) * radius));
            level.addParticle(new DustParticleOptions(rgb, size), p.x, p.y, p.z, 0, 0, 0);
        }
    }

    /** A flat circle of dots on the ground round {@code centre}. */
    private static void circle(ClientLevel level, Vec3 centre, double radius, int dots, float spin, int rgb, float size) {
        for (int i = 0; i < dots; i++) {
            double a = spin + 2 * Math.PI * i / dots;
            level.addParticle(new DustParticleOptions(rgb, size), centre.x + Math.cos(a) * radius, centre.y, centre.z + Math.sin(a) * radius, 0, 0, 0);
        }
    }

    private static Vec3 randomUnit(RandomSource r) {
        double z = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2, k = Math.sqrt(1 - z * z);
        return new Vec3(Math.cos(a) * k, z, Math.sin(a) * k);
    }
}
