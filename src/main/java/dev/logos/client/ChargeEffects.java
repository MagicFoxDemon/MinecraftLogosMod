package dev.logos.client;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
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
 * circle with rising sparks (colossal). Let go into its spell, the charge is released from the head
 * (see {@link #release}); dropped without casting, it fizzles out. A first pass to be replaced by real
 * art, but already shaped by element and tier: an element that draws its own charge
 * ({@link ChargeVisuals#drawsOwn}) is skipped here.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ChargeEffects {
    private ChargeEffects() {}

    /** The last release or fizzle played, for the client scenes. */
    public record Ended(int entityId, Element element, int tier, boolean released, long tick) {}

    private static @Nullable Ended last;

    public static @Nullable Ended last() {
        return last;
    }

    /**
     * A charge let go into its spell, bigger by tier and pointing nowhere in particular (spells go every
     * way): a bright knot and a burst out of the head; the ring round the head flung out (elaborate); the
     * ground circle drawn in to the head (grand); a shockwave over the ground all round and a rising
     * column (colossal). The spell's own effects follow from the same point.
     */
    static void release(int entityId, Element element, int tier) {
        last = new Ended(entityId, element, tier, true, ClientCharges.ticks());
        if (ChargeVisuals.drawsOwn(element)) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(entityId) instanceof LivingEntity holder)) return;
        RandomSource r = level.getRandom();
        int rgb = ClientCharges.color(element);
        Vec3 tip = CastPoints.of(holder, 1);
        for (int i = 0; i < 6; i++) {
            Vec3 at = tip.add(randomUnit(r).scale(0.08));
            level.addParticle(new DustParticleOptions(rgb, (1.4f + 0.3f * tier) * near(holder)), at.x, at.y, at.z, 0, 0, 0);
        }
        for (int i = 0; i < 14 + 8 * tier; i++) {
            Vec3 to = tip.add(randomUnit(r).scale(0.5 + 0.3 * tier));
            level.addParticle(new TrailParticleOption(to, rgb, 4 + r.nextInt(4)), tip.x, tip.y, tip.z, 0, 0, 0);
        }
        if (tier >= 1) {
            Vec3 n = holder.getLookAngle();
            Vec3 u = n.cross(new Vec3(0, 1, 0));
            if (u.lengthSqr() < 1e-6) u = new Vec3(1, 0, 0);
            u = u.normalize();
            Vec3 v = n.cross(u).normalize();
            double radius = 0.9 + 0.3 * tier;
            for (int i = 0; i < 16; i++) {
                double a = 2 * Math.PI * i / 16;
                Vec3 to = tip.add(u.scale(Math.cos(a) * radius)).add(v.scale(Math.sin(a) * radius));
                level.addParticle(new TrailParticleOption(to, rgb, 6), tip.x, tip.y, tip.z, 0, 0, 0);
            }
        }
        Vec3 feet = holder.position().add(0, 0.06, 0);
        if (tier >= 2) {
            for (int i = 0; i < 24; i++) {
                double a = 2 * Math.PI * i / 24;
                level.addParticle(new TrailParticleOption(tip, rgb, 5 + r.nextInt(3)), feet.x + Math.cos(a) * 1.1, feet.y, feet.z + Math.sin(a) * 1.1, 0, 0, 0);
            }
        }
        if (tier >= 3) {
            for (int i = 0; i < 40; i++) {
                double a = 2 * Math.PI * i / 40;
                Vec3 to = feet.add(Math.cos(a) * 3.5, 0, Math.sin(a) * 3.5);
                level.addParticle(new TrailParticleOption(to, rgb, 8 + r.nextInt(3)), feet.x + Math.cos(a) * 0.5, feet.y, feet.z + Math.sin(a) * 0.5, 0, 0, 0);
            }
            for (int i = 0; i < 10; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                level.addParticle(ParticleTypes.END_ROD, feet.x + Math.cos(a) * 1.5, feet.y, feet.z + Math.sin(a) * 1.5, 0, 0.25, 0);
            }
        }
    }

    /** A charge dropped without casting: its motes drift off the head, dimmed, and a wisp of smoke. */
    static void fizzle(int entityId, Element element, int tier) {
        last = new Ended(entityId, element, tier, false, ClientCharges.ticks());
        if (ChargeVisuals.drawsOwn(element)) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(entityId) instanceof LivingEntity holder)) return;
        RandomSource r = level.getRandom();
        int rgb = ClientCharges.color(element);
        int dim = ((rgb >> 16 & 0xFF) * 3 / 5) << 16 | ((rgb >> 8 & 0xFF) * 3 / 5) << 8 | (rgb & 0xFF) * 3 / 5;
        Vec3 tip = CastPoints.of(holder, 1);
        for (int i = 0; i < 10 + 4 * tier; i++) {
            Vec3 to = tip.add(randomUnit(r).scale(0.4 + r.nextDouble() * 0.3));
            level.addParticle(new TrailParticleOption(to, dim, 6 + r.nextInt(4)), tip.x, tip.y, tip.z, 0, 0, 0);
        }
        for (int i = 0; i < 3; i++) level.addParticle(ParticleTypes.SMOKE, tip.x, tip.y, tip.z, 0, 0.02, 0);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        long now = ClientCharges.ticks();
        for (var e : ClientCharges.all().entrySet()) {
            if (ChargeVisuals.drawsOwn(e.getValue().element) || !(level.getEntity(e.getKey()) instanceof LivingEntity holder)) continue;
            spawn(level, holder, e.getValue(), CastPoints.of(holder, 1), now);
        }
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
            level.addParticle(new DustParticleOptions(rgb, (0.5f + 0.15f * s.tier) * near(holder)), tip.x, tip.y, tip.z, 0, 0, 0);
            if (now == s.fullSince) {
                // The moment it fills: a flash thrown outward from the head.
                for (int i = 0; i < 14 + 6 * s.tier; i++) {
                    Vec3 to = tip.add(randomUnit(r).scale(0.6 + 0.2 * s.tier));
                    level.addParticle(new TrailParticleOption(to, rgb, 5 + r.nextInt(4)), tip.x, tip.y, tip.z, 0, 0, 0);
                }
                // A bright knot at the head itself; the vanilla flash particle is screen-sized up close.
                for (int i = 0; i < 4; i++) {
                    Vec3 at = tip.add(randomUnit(r).scale(0.06));
                    level.addParticle(new DustParticleOptions(rgb, 1.6f * near(holder)), at.x, at.y, at.z, 0, 0, 0);
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

    /** Half size for the camera's own wand in first person: a glow that close would fill the view. */
    private static float near(LivingEntity holder) {
        Minecraft mc = Minecraft.getInstance();
        return holder == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson() ? 0.5f : 1f;
    }

    private static Vec3 randomUnit(RandomSource r) {
        double z = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2, k = Math.sqrt(1 - z * z);
        return new Vec3(Math.cos(a) * k, z, Math.sin(a) * k);
    }
}
