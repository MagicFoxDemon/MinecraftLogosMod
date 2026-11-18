package dev.logos.client.port;

import dev.logos.port.TrailParticleOption;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Stands in for 26.1's trail particle: a full-bright mote that closes on its target, arriving as it dies. */
public final class TrailParticle extends TextureSheetParticle {
    private final Vec3 target;

    private TrailParticle(ClientLevel level, double x, double y, double z, TrailParticleOption options, SpriteSet sprites) {
        super(level, x, y, z);
        int c = options.color();
        float r = 0.875f + random.nextFloat() * 0.25f, g = 0.875f + random.nextFloat() * 0.25f, b = 0.875f + random.nextFloat() * 0.25f;
        this.rCol = Math.min(1, (c >> 16 & 255) / 255f * r);
        this.gCol = Math.min(1, (c >> 8 & 255) / 255f * g);
        this.bCol = Math.min(1, (c & 255) / 255f * b);
        this.quadSize = 0.26f;
        this.target = options.target();
        this.lifetime = options.duration();
        this.hasPhysics = false;
        this.gravity = 0;
        pickSprite(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        double toGo = 1.0 / (lifetime - age);
        x = Mth.lerp(toGo, x, target.x);
        y = Mth.lerp(toGo, y, target.y);
        z = Mth.lerp(toGo, z, target.z);
    }

    @Override
    public int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    public record Provider(SpriteSet sprites) implements ParticleProvider<TrailParticleOption> {
        @Override
        public Particle createParticle(TrailParticleOption options, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
            return new TrailParticle(level, x, y, z, options, sprites);
        }
    }
}
