package dev.logos.client;

import dev.logos.block.ManaCollectorBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * A mote of mana in the air: a faint pale-cyan speck that fades in, drifts and fades out, lit by
 * itself. Given no motion it wanders slowly about (the air of a chunk holding mana, a spreader's
 * lid); given one, it keeps it to the end, as a mote drawn in to a collector does.
 */
public class ManaMote extends SingleQuadParticle {
    private static final float R = 0.45f, G = 0.9f, B = 0.86f, PEAK = 0.9f;

    ManaMote(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites, RandomSource random) {
        super(level, x, y, z, sprites.get(random));
        hasPhysics = false;
        quadSize = 0.05f + random.nextFloat() * 0.03f;
        setColor(R, G, B);
        setAlpha(0);
        if (xd == 0 && yd == 0 && zd == 0) {
            // Wandering: a slow drift that slows a little more.
            friction = 0.985f;
            this.xd = (random.nextDouble() - 0.5) * 0.01;
            this.yd = (random.nextDouble() - 0.3) * 0.008;
            this.zd = (random.nextDouble() - 0.5) * 0.01;
            lifetime = 60 + random.nextInt(60);
        } else {
            friction = 1;
            this.xd = xd;
            this.yd = yd;
            this.zd = zd;
            lifetime = yd > 0 && xd == 0 && zd == 0 ? 50 + random.nextInt(30) : ManaCollectorBlock.DRAWN_MOTE_LIFE;
        }
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    /** Fades in over its first quarter and out over its last. */
    @Override
    public void tick() {
        super.tick();
        float t = (float) age / lifetime;
        setAlpha(PEAK * Math.min(1, Math.min(t * 4, (1 - t) * 4)));
    }

    /** Lit by itself, as faint as it is. */
    @Override
    protected int getLightCoords(float partialTick) {
        return 0xF000F0;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z,
                double xd, double yd, double zd, RandomSource random) {
            return new ManaMote(level, x, y, z, xd, yd, zd, sprites, random);
        }
    }
}
