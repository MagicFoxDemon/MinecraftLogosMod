package dev.logos.port;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * Stands in for 26.1's {@code net.minecraft.core.particles.TrailParticleOption}: a mote that flies to {@code target}
 * over {@code duration} ticks, in {@code color}. Its type is {@link PortParticles#TRAIL}.
 */
public record TrailParticleOption(Vec3 target, int color, int duration) implements ParticleOptions {
    public static final MapCodec<TrailParticleOption> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Vec3.CODEC.fieldOf("target").forGetter(TrailParticleOption::target),
            Codec.INT.fieldOf("color").forGetter(TrailParticleOption::color),
            Codec.INT.fieldOf("duration").forGetter(TrailParticleOption::duration)).apply(i, TrailParticleOption::new));
    public static final StreamCodec<ByteBuf, TrailParticleOption> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, o -> o.target().x,
            ByteBufCodecs.DOUBLE, o -> o.target().y,
            ByteBufCodecs.DOUBLE, o -> o.target().z,
            ByteBufCodecs.INT, TrailParticleOption::color,
            ByteBufCodecs.VAR_INT, TrailParticleOption::duration,
            (x, y, z, color, duration) -> new TrailParticleOption(new Vec3(x, y, z), color, duration));

    @Override
    public ParticleType<TrailParticleOption> getType() {
        return PortParticles.TRAIL.get();
    }
}
