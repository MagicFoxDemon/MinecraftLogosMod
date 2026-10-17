package dev.logos.runtime;

import java.util.Map;
import java.util.WeakHashMap;

import dev.logos.LogosMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent;

/**
 * 落雷: a real vanilla lightning bolt called down by a spell, so it burns, charges creepers and
 * scrubs copper as any bolt does; only its blow spares the spell's own side (the caster, players
 * it may not touch, their familiars and pets).
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Lightning {
    private static final Map<LightningBolt, SpellInstance> CALLED = new WeakHashMap<>();

    public static final double COST = 50, COST_IN_RAIN = 40, COST_IN_STORM = 20;

    private Lightning() {}

    /**
     * A bolt comes cheaper out of a wet sky: 20 under a storm, 40 in rain, 50 otherwise. An area weather
     * zone over the spot decides; elsewhere the world's weather does, where it falls on the open column.
     */
    public static double cost(ServerLevel level, BlockPos pos) {
        dev.logos.lang.Weather zone = AreaWeather.at(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        if (zone != null) return zone == dev.logos.lang.Weather.THUNDER ? COST_IN_STORM : zone == dev.logos.lang.Weather.RAIN ? COST_IN_RAIN : COST;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        if (level.precipitationAt(top) == Biome.Precipitation.NONE) return COST;
        return level.isThundering() ? COST_IN_STORM : COST_IN_RAIN;
    }

    /** Brings a bolt down at {@code at}, credited to the caster. */
    public static void strike(SpellInstance inst, ServerLevel level, Vec3 at) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt == null) return;
        bolt.snapTo(at);
        if (inst.caster instanceof ServerPlayer p) bolt.setCause(p);
        CALLED.put(bolt, inst);
        level.addFreshEntity(bolt);
    }

    @SubscribeEvent
    static void onStruck(EntityStruckByLightningEvent event) {
        SpellInstance inst = CALLED.get(event.getLightning());
        if (inst != null && event.getEntity() instanceof LivingEntity e && !Targeting.isEnemy(inst, e)) event.setCanceled(true);
    }
}
