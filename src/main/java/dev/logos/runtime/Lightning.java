package dev.logos.runtime;

import java.util.Map;
import java.util.WeakHashMap;

import dev.logos.LogosMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
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

    private Lightning() {}

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
