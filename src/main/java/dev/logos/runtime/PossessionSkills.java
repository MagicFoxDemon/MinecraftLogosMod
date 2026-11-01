package dev.logos.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A possessed creature's own skill, on its possessor's use button (see {@link Possession#shoot}):
 * the move vanilla gives it in its AI, done where the player looks. Creatures with none shoot as
 * before if they can. Free, but each has a cooldown near vanilla's. Another mod gives its own
 * creatures one with {@link #register}.
 */
public final class PossessionSkills {
    /** One creature's skill: done by {@code mob} for {@code player}, at {@code target} if they aim at one it may hit. */
    @FunctionalInterface
    public interface Skill {
        /** Whether it went off (only then does its cooldown start). */
        boolean use(ServerLevel level, Mob mob, ServerPlayer player, @Nullable LivingEntity target);
    }

    public record Entry(Skill skill, int cooldown) {}

    private static final Map<EntityType<?>, Entry> SKILLS = new HashMap<>();
    /** Rams under way: the goat and the tick its charge is spent. */
    private static final Map<Mob, Long> RAMMING = new WeakHashMap<>();
    private static final int RAM_TICKS = 10;

    private PossessionSkills() {}

    /** Gives every creature of {@code type} a skill, with {@code cooldown} ticks between uses; replaces one it had. */
    public static void register(EntityType<?> type, int cooldown, Skill skill) {
        SKILLS.put(type, new Entry(skill, cooldown));
    }

    public static @Nullable Entry of(Entity mob) {
        return SKILLS.get(mob.getType());
    }

    static {
        // Its fuse, as when it nears a player; the blast ends the possession, without the backlash of being killed.
        register(EntityType.CREEPER, 40, (level, mob, p, target) -> {
            ((Creeper) mob).ignite();
            return true;
        });
        register(EntityType.ENDERMAN, 20, PossessionSkills::teleport);
        register(EntityType.BLAZE, 60, PossessionSkills::blaze);
        register(EntityType.GHAST, 40, PossessionSkills::ghast);
        register(EntityType.BREEZE, 30, (level, mob, p, target) -> {
            Vec3 look = p.getLookAngle();
            Projectile.spawnProjectileUsingShoot(new BreezeWindCharge((Breeze) mob, level), level, ItemStack.EMPTY, look.x, look.y, look.z, 0.7f, 1);
            mob.playSound(SoundEvents.BREEZE_SHOOT, 1.5f, 1);
            return true;
        });
        register(EntityType.EVOKER, 100, PossessionSkills::fangs);
        register(EntityType.SHULKER, 30, (level, mob, p, target) -> {
            if (target == null) return false;
            level.addFreshEntity(new ShulkerBullet(level, mob, target, Direction.Axis.Y));
            mob.playSound(SoundEvents.SHULKER_SHOOT, 2, (mob.getRandom().nextFloat() - mob.getRandom().nextFloat()) * 0.2f + 1);
            return true;
        });
        register(EntityType.WARDEN, 40, PossessionSkills::sonicBoom);
        register(EntityType.RAVAGER, 80, PossessionSkills::roar);
        register(EntityType.GOAT, 40, (level, mob, p, target) -> {
            Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
            mob.setDeltaMovement(look.x * 1.2, mob.getDeltaMovement().y + 0.1, look.z * 1.2);
            mob.hurtMarked = true; // the push reaches the possessor's client, which moves the goat
            RAMMING.put(mob, level.getGameTime() + RAM_TICKS);
            mob.playSound(SoundEvents.GOAT_PREPARE_RAM, 1, 1);
            return true;
        });
    }

    /** Each server tick of a possession: a ramming goat strikes the first creature it runs into. */
    static void tick(ServerPlayer p, Mob mob) {
        Long until = RAMMING.get(mob);
        if (until == null || !(mob.level() instanceof ServerLevel level)) return;
        if (level.getGameTime() > until) {
            RAMMING.remove(mob);
            return;
        }
        for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(0.4), e -> fair(p, mob, e))) {
            RAMMING.remove(mob);
            Vec3 push = hit.position().subtract(mob.position()).multiply(1, 0, 1).normalize();
            hit.hurtServer(level, level.damageSources().mobAttack(mob), (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE));
            double resist = 1 - hit.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            hit.push(push.x * 2.5 * resist, 0.4 * resist, push.z * 2.5 * resist);
            hit.hurtMarked = true;
            mob.playSound(SoundEvents.GOAT_RAM_IMPACT, 1, 1);
            return;
        }
    }

    /** Something a possessed creature's skill may hurt: not itself or its possessor, nor a player PvP would spare. */
    static boolean fair(ServerPlayer p, Mob mob, @Nullable Entity e) {
        return e instanceof LivingEntity t && t != mob && t != p && t.isAlive() && t.level() == mob.level()
                && !(t instanceof Player other && !p.canHarmPlayer(other));
    }

    /** The enderman's: to the block face under the player's gaze, within 32 blocks, as it teleports anywhere (onto ground, never into walls). */
    private static boolean teleport(ServerLevel level, Mob mob, ServerPlayer p, @Nullable LivingEntity target) {
        Vec3 eye = p.getEyePosition();
        HitResult hit = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(32)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos to = block.getBlockPos().relative(block.getDirection());
        Vec3 from = mob.position();
        if (!mob.randomTeleport(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, true)) return false;
        level.gameEvent(GameEvent.TELEPORT, from, GameEvent.Context.of(mob));
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, mob.getSoundSource(), 1, 1);
        mob.playSound(SoundEvents.ENDERMAN_TELEPORT, 1, 1);
        return true;
    }

    /** The blaze's: three small fireballs, a little spread, where the player looks. */
    private static boolean blaze(ServerLevel level, Mob mob, ServerPlayer p, @Nullable LivingEntity target) {
        Vec3 look = p.getLookAngle();
        for (int i = 0; i < 3; i++) {
            var r = mob.getRandom();
            Vec3 dir = look.add(r.triangle(0, 0.08), r.triangle(0, 0.08), r.triangle(0, 0.08)).normalize();
            SmallFireball ball = new SmallFireball(level, mob, dir);
            ball.setPos(ball.getX(), mob.getY(0.5) + 0.5, ball.getZ());
            level.addFreshEntity(ball);
        }
        level.levelEvent(null, 1018, mob.blockPosition(), 0);
        return true;
    }

    /** The ghast's: one fireball of its own blast power, from four blocks before it, as it fires. */
    private static boolean ghast(ServerLevel level, Mob mob, ServerPlayer p, @Nullable LivingEntity target) {
        Vec3 look = p.getLookAngle();
        LargeFireball ball = new LargeFireball(level, mob, look, ((Ghast) mob).getExplosionPower());
        ball.setPos(mob.getX() + look.x * 4, mob.getY(0.5) + 0.5, mob.getZ() + look.z * 4);
        level.addFreshEntity(ball);
        level.levelEvent(null, 1016, mob.blockPosition(), 0);
        return true;
    }

    /** The evoker's: sixteen fangs in a line where the player looks, rising one after another, each on the ground there. */
    private static boolean fangs(ServerLevel level, Mob mob, ServerPlayer p, @Nullable LivingEntity target) {
        Vec3 look = p.getLookAngle();
        float angle = (float) Mth.atan2(look.z, look.x);
        double minY = mob.getY() - 4, maxY = mob.getY() + 1;
        for (int i = 0; i < 16; i++) {
            double reach = 1.25 * (i + 1);
            fang(level, mob, mob.getX() + Mth.cos(angle) * reach, mob.getZ() + Mth.sin(angle) * reach, minY, maxY, angle, i);
        }
        mob.playSound(SoundEvents.EVOKER_CAST_SPELL, 1, 1);
        return true;
    }

    /** One fang on the first solid ground below {@code maxY}, down to {@code minY}; none where there is none (as an evoker's own). */
    private static void fang(ServerLevel level, Mob mob, double x, double z, double minY, double maxY, float angle, int delay) {
        BlockPos pos = BlockPos.containing(x, maxY, z);
        do {
            BlockPos below = pos.below();
            if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                double top = 0;
                if (!level.isEmptyBlock(pos)) {
                    BlockState state = level.getBlockState(pos);
                    VoxelShape shape = state.getCollisionShape(level, pos);
                    if (!shape.isEmpty()) top = shape.max(Direction.Axis.Y);
                }
                level.addFreshEntity(new EvokerFangs(level, x, pos.getY() + top, z, angle, delay, mob));
                level.gameEvent(GameEvent.ENTITY_PLACE, new Vec3(x, pos.getY() + top, z), GameEvent.Context.of(mob));
                return;
            }
            pos = pos.below();
        } while (pos.getY() >= Mth.floor(minY) - 1);
    }

    /** The warden's: its sonic boom at the creature the player aims at, within its reach (15 across, 20 up or down), through anything. */
    private static boolean sonicBoom(ServerLevel level, Mob mob, ServerPlayer p, @Nullable LivingEntity target) {
        if (target == null || !mob.closerThan(target, 15, 20)) return false;
        Vec3 source = mob.getEyePosition().subtract(0, 0.5, 0);
        Vec3 delta = target.getEyePosition().subtract(source);
        Vec3 dir = delta.normalize();
        for (int i = 1; i < Mth.floor(delta.length()) + 7; i++) {
            Vec3 at = source.add(dir.scale(i));
            level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
        mob.playSound(SoundEvents.WARDEN_SONIC_BOOM, 3, 1);
        level.broadcastEntityEvent(mob, (byte) 62);
        if (target.hurtServer(level, level.damageSources().sonicBoom(mob), 10)) {
            double resist = 1 - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            target.push(dir.x * 2.5 * resist, dir.y * 0.5 * resist, dir.z * 2.5 * resist);
            target.hurtMarked = true;
        }
        return true;
    }

    /** The ravager's: a roar that hurts and throws back what stands within four blocks (illagers are spared the hurt, as by its own). */
    private static boolean roar(ServerLevel level, Mob mob, ServerPlayer p, @Nullable LivingEntity target) {
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(4), e -> fair(p, mob, e))) {
            if (!(e instanceof AbstractIllager)) e.hurtServer(level, level.damageSources().mobAttack(mob), 6);
            double dx = e.getX() - mob.getX(), dz = e.getZ() - mob.getZ();
            double d = Math.max(dx * dx + dz * dz, 0.001);
            e.push(dx / d * 4, 0.2, dz / d * 4);
            e.hurtMarked = true;
        }
        mob.playSound(SoundEvents.RAVAGER_ROAR, 1, 1);
        level.gameEvent(mob, GameEvent.ENTITY_ACTION, mob.position());
        level.broadcastEntityEvent(mob, (byte) 69);
        return true;
    }
}
