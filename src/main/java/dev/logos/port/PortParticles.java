package dev.logos.port;

import java.util.function.Supplier;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Particle types 26.1 has and 1.21.1 does not, registered under the mod's namespace. */
public final class PortParticles {
    private static final DeferredRegister<ParticleType<?>> TYPES = DeferredRegister.create(Registries.PARTICLE_TYPE, "logos");

    public static final Supplier<ParticleType<TrailParticleOption>> TRAIL = TYPES.register("trail", () -> new ParticleType<TrailParticleOption>(false) {
        @Override
        public MapCodec<TrailParticleOption> codec() {
            return TrailParticleOption.CODEC;
        }

        @Override
        public StreamCodec<? super RegistryFriendlyByteBuf, TrailParticleOption> streamCodec() {
            return TrailParticleOption.STREAM_CODEC;
        }
    });

    private PortParticles() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }

}
