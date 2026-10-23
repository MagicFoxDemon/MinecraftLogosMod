package dev.logos.runtime;

import dev.logos.lang.Node;
import dev.logos.lang.Weather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 区域天气: one zone of {@link AreaWeather}, alive as long as its spell pays. Upkeep is by area, per
 * second; a storm costs twice. It snows and wets the ground faster than vanilla weather does, and a
 * storm throws lightning about, never close to the caster or anyone the spell must spare.
 */
public final class AreaWeatherObj extends SpellObject {
    public static final double SETUP = 10;
    /** Mana per second per square block of radius (so radius 8 costs about 1.3 a second, 16 about 5.1). */
    public static final double UPKEEP_PER_AREA = 0.02;
    /** A storm of radius 16 strikes about once every 4 seconds; a larger one more often, by area. */
    static final double STRIKES_PER_TICK_AT_16 = 1 / 80.0;

    private final Weather kind;
    private final double radius;
    private final boolean follow;
    private Vec3 synced;

    private AreaWeatherObj(Node node, Ctx ctx, Vec3 pos, Weather kind, double radius, int life, boolean follow) {
        super(node, ctx, pos, life);
        this.kind = kind;
        this.radius = radius;
        this.follow = follow;
        this.synced = pos;
    }

    /** Raises a zone of {@code kind} over {@code pos}; the setup cost is the caller's to have paid. */
    public static void start(Node node, Ctx ctx, Vec3 pos, Weather kind, double radius, int life, boolean follow) {
        AreaWeatherObj obj = new AreaWeatherObj(node, ctx, pos, kind, radius, life, follow);
        SpellRuntime.spawn(obj);
        if (!ctx.inst().dead) AreaWeather.add(obj);
    }

    public static double upkeep(Weather kind, double radius) {
        return UPKEEP_PER_AREA * radius * radius * (kind == Weather.THUNDER ? 2 : 1);
    }

    AreaWeather.Zone zone() {
        return new AreaWeather.Zone(id, kind, pos.x, pos.z, radius);
    }

    @Override
    public void tick() {
        if (++age > life) {
            end();
            return;
        }
        if (follow && inst.caster.level() == level) pos = inst.caster.position();
        if ((age - 1) % 20 == 0 && !inst.spend(upkeep(kind, radius), "weather")) {
            dissolve(); // the zone goes at once, not a tick after the spell
            return;
        }
        if (pos.distanceToSqr(synced) > 0.25 && age % 2 == 0) {
            synced = pos;
            AreaWeather.sync(level);
        }
        if (kind == Weather.CLEAR) return;
        double area = Math.PI * radius * radius;
        // Snow piles and puddles wet about once a chunk a tick: well past vanilla's pace, as magic should.
        for (int i = Math.max(1, (int) Math.round(area / 256)); i > 0; i--) {
            BlockPos column = randomColumn();
            if (level.isLoaded(column)) level.tickPrecipitation(column);
        }
        if (kind == Weather.THUNDER && level.getRandom().nextDouble() < STRIKES_PER_TICK_AT_16 * area / (Math.PI * 16 * 16)) strike();
    }

    private BlockPos randomColumn() {
        double r = radius * Math.sqrt(level.getRandom().nextDouble());
        double a = level.getRandom().nextDouble() * Math.PI * 2;
        return BlockPos.containing(pos.x + r * Math.cos(a), level.getMinY(), pos.z + r * Math.sin(a));
    }

    private void strike() {
        BlockPos column = randomColumn();
        if (!level.isLoaded(column)) return;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
        if (!level.isRainingAt(top)) return; // snowing (cold) or roofed over
        AABB reach = new AABB(top).inflate(4, 8, 4);
        if (!level.getEntitiesOfClass(LivingEntity.class, reach, e -> e.isAlive() && !Targeting.isEnemy(inst, e)).isEmpty()) return;
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt == null) return;
        bolt.snapTo(Vec3.atBottomCenterOf(top));
        if (inst.caster instanceof ServerPlayer p) bolt.setCause(p);
        level.addFreshEntity(bolt);
    }

    @Override
    protected void onEnded() {
        AreaWeather.remove(this);
    }
}
