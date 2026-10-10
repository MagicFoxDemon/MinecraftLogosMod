package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 游离魔力: what a spell leaves of its pool when it ends. A natural end sets all of it free; an
 * extinguished spell leaves its entropic mana, of which only the page's recovery share returns.
 * It hovers where the spell last acted, merges with nearby free mana, drifts to players like
 * experience does, and is gathered at their free-mana efficiency.
 */
public final class FreeManaEntity extends Entity {
    private static final EntityDataAccessor<Float> AMOUNT = SynchedEntityData.defineId(FreeManaEntity.class, EntityDataSerializers.FLOAT);
    /** Five minutes, like experience. */
    private static final int LIFETIME = 6000;
    private static final int PICKUP_DELAY = 10;
    private static final double MERGE_RADIUS = 1.5;
    /** Smaller remainders are not worth an entity. */
    private static final double MIN_RELEASE = 0.05;
    /** A spell that ended farther than this from its caster (or nowhere known) leaves its mana near the caster instead. */
    private static final double KNOWN_END_RANGE = 24;
    private static final int SCATTER_RANGE = 12;

    private int age;
    private @Nullable Player following;

    public FreeManaEntity(EntityType<? extends FreeManaEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    private FreeManaEntity(ServerLevel level, Vec3 pos, double amount) {
        this(LogosMod.FREE_MANA.get(), level);
        setPos(pos.x, pos.y - getBbHeight() / 2, pos.z);
        setAmount(amount);
        RandomSource r = level.getRandom();
        setDeltaMovement((r.nextDouble() - 0.5) * 0.06, r.nextDouble() * 0.04, (r.nextDouble() - 0.5) * 0.06);
    }

    public double amount() {
        return entityData.get(AMOUNT);
    }

    private void setAmount(double amount) {
        entityData.set(AMOUNT, (float) amount);
    }

    /**
     * Sets {@code amount} free where the spell last acted ({@code at}/{@code pos}), or, if that is
     * unknown, in another world, or over 24 blocks from the caster, in an open spot within 12 blocks of the caster.
     */
    static void release(SpellInstance inst, @Nullable ServerLevel at, @Nullable Vec3 pos, double amount) {
        if (amount < MIN_RELEASE) return;
        LivingEntity caster = inst.caster;
        if (!(caster.level() instanceof ServerLevel home)) return;
        ServerLevel level;
        Vec3 spot;
        if (at == home && pos != null && pos.distanceToSqr(caster.position()) <= KNOWN_END_RANGE * KNOWN_END_RANGE) {
            level = at;
            spot = open(at, pos);
        } else {
            level = home;
            spot = scatter(home, caster);
        }
        for (FreeManaEntity e : level.getEntitiesOfClass(FreeManaEntity.class, new AABB(spot, spot).inflate(MERGE_RADIUS), Entity::isAlive)) {
            e.setAmount(e.amount() + amount);
            e.age = 0;
            return;
        }
        level.addFreshEntity(new FreeManaEntity(level, spot, amount));
    }

    /** {@code pos}, or the first open block above it if it lies inside something solid. */
    private static Vec3 open(ServerLevel level, Vec3 pos) {
        BlockPos b = BlockPos.containing(pos);
        for (int up = 0; up <= 3; up++) {
            BlockPos p = b.above(up);
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return up == 0 ? pos : Vec3.atCenterOf(p);
        }
        return pos;
    }

    /** A random empty (air) block within 12 blocks of the caster. */
    private static Vec3 scatter(ServerLevel level, LivingEntity caster) {
        RandomSource r = level.getRandom();
        BlockPos base = caster.blockPosition();
        for (int tries = 0; tries < 32; tries++) {
            BlockPos p = base.offset(r.nextIntBetweenInclusive(-SCATTER_RANGE, SCATTER_RANGE), r.nextIntBetweenInclusive(-3, 4),
                    r.nextIntBetweenInclusive(-SCATTER_RANGE, SCATTER_RANGE));
            if (p.distSqr(base) <= SCATTER_RANGE * SCATTER_RANGE && level.isInWorldBounds(p) && level.isEmptyBlock(p)) return Vec3.atCenterOf(p);
        }
        return caster.getEyePosition().add(0, 1, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (++age >= LIFETIME) {
            discard();
            return;
        }
        if ((tickCount + getId()) % 20 == 0) mergeNearby();
        follow();
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().scale(following != null ? 0.92 : 0.85));
    }

    /** Free mana close together becomes one. */
    private void mergeNearby() {
        for (FreeManaEntity o : level().getEntitiesOfClass(FreeManaEntity.class, getBoundingBox().inflate(MERGE_RADIUS), e -> e != this && e.isAlive())) {
            setAmount(amount() + o.amount());
            age = Math.min(age, o.age);
            o.discard();
        }
    }

    /** Drifts towards the nearest player within their attraction range who has room for more mana. */
    private void follow() {
        if (following == null || !canGather(following) || following.distanceToSqr(this) > sq(range(following))) {
            following = null;
            double best = Double.MAX_VALUE;
            for (Player p : level().players()) {
                double d = p.distanceToSqr(this);
                if (d < best && d <= sq(range(p)) && canGather(p)) {
                    best = d;
                    following = p;
                }
            }
        }
        if (following == null) return;
        double range = range(following);
        Vec3 to = new Vec3(following.getX() - getX(), following.getY() + following.getEyeHeight() / 2 - getY(), following.getZ() - getZ());
        double power = 1 - to.length() / Math.max(range, 1e-3);
        setDeltaMovement(getDeltaMovement().add(to.normalize().scale(power * power * 0.12)));
    }

    private static double range(Player p) {
        return p.getAttributeValue(LogosMod.MANA_ATTRACTION);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static boolean canGather(Player p) {
        if (p.isSpectator() || p.isDeadOrDying() || !(p instanceof ServerPlayer)) return false;
        PlayerMagic m = PlayerMagic.of(p);
        return m.mana < m.maxMana && p.getAttributeValue(LogosMod.MANA_ABSORPTION) > 0;
    }

    /**
     * A vessel (a mana lifeform) draws this in at {@code efficiency}: what fits is taken, the rest
     * stays here. Returns the mana the vessel gained.
     */
    public double drainInto(ManaVessel vessel, double efficiency) {
        if (!isAlive() || efficiency <= 0) return 0;
        double gained = vessel.receiveMana(amount() * efficiency);
        double left = amount() - gained / efficiency;
        if (left <= 1e-4) discard();
        else setAmount(left);
        return gained;
    }

    /** Gathered at the player's efficiency; what does not fit in their pool stays here. */
    @Override
    public void playerTouch(Player player) {
        if (!(player instanceof ServerPlayer sp) || age < PICKUP_DELAY || !canGather(sp)) return;
        PlayerMagic m = PlayerMagic.of(sp);
        double efficiency = sp.getAttributeValue(LogosMod.MANA_ABSORPTION);
        double gain = Math.min(m.maxMana - m.mana, amount() * efficiency);
        m.mana += gain;
        double left = amount() - gain / efficiency;
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.4f,
                1.4f + random.nextFloat() * 0.4f);
        if (left <= 1e-4) discard();
        else setAmount(left);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(AMOUNT, 0f);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        setAmount(input.getFloatOr("amount", 0));
        age = input.getIntOr("age", 0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putFloat("amount", (float) amount());
        output.putInt("age", age);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    protected MovementEmission getMovementEmission() {
        return MovementEmission.NONE;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.AMBIENT;
    }
}
