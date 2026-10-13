package dev.logos.runtime;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.util.GeckoLibUtil;

import dev.logos.LogosMod;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * 汲魔蛭: a hostile arthropod that hunts mana. It crawls toward whoever nearby holds the most — a
 * player, or a player's familiar — and from three blocks out coils for a second and a half, leaps at
 * their face and clings there for three seconds, drinking 10 mana and biting each second. Then it drops
 * off and coils again, until it dies. Being struck while it coils, or its prey moving off, breaks the
 * coil; a weapon with Knockback strikes it off a face.
 * <p>
 * Its bite grows with the mana it holds: 2, plus 2 for every 10. A summoned (not split-off) leech that
 * holds 60 splits after two seconds into two empty, unsplitting leeches, unless five are already about.
 * Bane of Arthropods scatters a share of its mana with each blow (20% at level I, 10% more a level),
 * and on death 80% of what it holds is set free; split-off leeches drop more tainted shards.
 * <p>
 * A familiar summoned by spell has no pool: its health stands for its mana, and draining it wounds it
 * as much. A made familiar (lifeform, colossus) is drained from its own pool.
 * <p>
 * Summoned as a familiar itself, it hunts nothing of its own: it fights what its master fights, never
 * its master's side, drinks for its master (into their pool, keeping none) and so never splits.
 */
public class ManaLeech extends Monster implements GeoEntity {
    public static final double SEARCH_RANGE = 16, CHARGE_RANGE = 3, SPLIT_AT = 60, SWELL_AT = 30;
    /** It gives up a coil when its prey gets this far away. */
    private static final double BREAK_RANGE = 4.5;
    public static final int CHARGE_TICKS = 30, LATCH_TICKS = 60, SPLIT_TICKS = 40, DRAIN_EVERY = 20;
    public static final double DRAIN = 10;
    private static final int POUNCE_TICKS = 10, MAX_NEARBY = 5;
    private static final double POUNCE_SPEED = 0.8;
    /** Where its mouth (the model's face_contact bone) sits from its feet, in blocks: ahead and up. */
    private static final double MOUTH_AHEAD = 13.08 / 16, MOUTH_UP = 3.65 / 16;
    /** How far in front of the eyes its mouth clings. */
    private static final double FACE_GAP = 0.3;
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_leech"));
    private static final ResourceKey<LootTable> OFFSPRING_LOOT = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath(LogosMod.MODID, "entities/mana_leech_offspring"));

    /** What it is doing; synced so its animation follows. */
    public enum State { SEEK, CHARGE, POUNCE, LATCHED, FALL, SPLIT }

    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(ManaLeech.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> MANA = SynchedEntityData.defineId(ManaLeech.class, EntityDataSerializers.FLOAT);
    /** The entity it clings to, or -1; the client puts it on that face itself, every tick. */
    private static final EntityDataAccessor<Integer> LATCHED_ON = SynchedEntityData.defineId(ManaLeech.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.mana_leech.idle");
    private static final RawAnimation SWIM = RawAnimation.begin().thenLoop("animation.mana_leech.swim");
    private static final RawAnimation CHARGE = RawAnimation.begin().thenPlayAndHold("animation.mana_leech.charge");
    private static final RawAnimation POUNCE = RawAnimation.begin().thenPlayAndHold("animation.mana_leech.pounce");
    private static final RawAnimation FEED_LOOP = RawAnimation.begin().thenLoop("animation.mana_leech.feed_loop");
    private static final RawAnimation HURT = RawAnimation.begin().thenPlay("animation.mana_leech.hurt");
    private static final RawAnimation SPLIT = RawAnimation.begin().thenPlayAndHold("animation.mana_leech.split");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("animation.mana_leech.death");
    /** Its middle swelling with what it has drunk. */
    private static final RawAnimation FEED = RawAnimation.begin().thenPlayAndHold("animation.mana_leech.feed");
    /** The split animation's own length; it is slowed to fill the two seconds of splitting. */
    private static final float SPLIT_SECONDS = 1.3f;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** Split off another leech: it starts empty, never splits and drops more shards. */
    private boolean offspring;
    private @Nullable LivingEntity prey;
    /** Ticks into the current state. */
    private int stateTicks;
    private Vec3 pounceVelocity = Vec3.ZERO;

    public ManaLeech(EntityType<? extends ManaLeech> type, Level level) {
        super(type, level);
        xpReward = 5;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2)
                .add(Attributes.FOLLOW_RANGE, SEARCH_RANGE);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new FloatGoal(this));
        // Wanders only with nothing to hunt; the hunt itself is driven from tick().
        goalSelector.addGoal(5, new RandomStrollGoal(this, 0.8) {
            @Override
            public boolean canUse() {
                return state() == State.SEEK && prey == null && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return state() == State.SEEK && prey == null && super.canContinueToUse();
            }
        });
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(STATE, State.SEEK.ordinal());
        b.define(MANA, 0f);
        b.define(LATCHED_ON, -1);
    }

    // ---- state ------------------------------------------------------------------------------------

    public State state() {
        return State.values()[entityData.get(STATE)];
    }

    private void setState(State s) {
        entityData.set(STATE, s.ordinal());
        stateTicks = 0;
    }

    public double mana() {
        return entityData.get(MANA);
    }

    public void setMana(double mana) {
        entityData.set(MANA, (float) Math.max(0, mana));
    }

    public boolean isOffspring() {
        return offspring;
    }

    public void setOffspring(boolean offspring) {
        this.offspring = offspring;
    }

    /** Its bite: 2, and 2 more for every 10 mana it holds. */
    public double attack() {
        return 2 + Math.floor(mana() / 10) * 2;
    }

    public @Nullable LivingEntity prey() {
        return prey;
    }

    /** What it clings to, on either side; null if it is not latched. */
    public @Nullable Entity latchedOn() {
        int id = entityData.get(LATCHED_ON);
        return id < 0 ? null : level().getEntity(id);
    }

    /** How much mana {@code e} has for it to smell: a player's pool, a made familiar's pool, a summoned familiar's health. */
    public static double manaOf(LivingEntity e) {
        if (e instanceof Player p) return PlayerMagic.of(p).mana;
        if (e instanceof ManaVessel v) return v.mana();
        return e.getHealth();
    }

    /**
     * Whether it may go after {@code e}. Wild, it hunts players and their familiars; a familiar itself,
     * anything but its master's side. Never itself, nor a player who cannot be hurt.
     */
    private boolean canHunt(LivingEntity e) {
        if (e == this || !e.isAlive() || e.isSpectator() || e.level() != level()) return false;
        if (e instanceof Player p && p.getAbilities().invulnerable) return false;
        UUID master = Familiars.masterOf(this);
        if (master != null) return !Familiars.friendly(master, e);
        return e instanceof Player || Familiars.isFamiliar(e);
    }

    // ---- the hunt ---------------------------------------------------------------------------------

    @Override
    public void tick() {
        State s = state();
        // Clinging it is carried, not moved; leaping it flies straight.
        noPhysics = s == State.LATCHED;
        setNoGravity(s == State.LATCHED || s == State.POUNCE);
        super.tick();
        s = state();
        if (s == State.LATCHED && latchedOn() instanceof LivingEntity on) cling(on);
        if (level() instanceof ServerLevel level && !isDeadOrDying() && !isNoAi()) serverTick(level);
        else if (level().isClientSide() && (s == State.CHARGE || s == State.SPLIT) && tickCount % 4 == 0) {
            level().addParticle(ParticleTypes.WITCH, getRandomX(0.6), getRandomY(), getRandomZ(0.6), 0, 0.02, 0);
        }
    }

    private void serverTick(ServerLevel level) {
        stateTicks++;
        if (prey != null && !canHunt(prey)) {
            if (state() == State.LATCHED) dropOff(level);
            prey = null;
        }
        switch (state()) {
            case SEEK -> seek(level);
            case CHARGE -> charge(level);
            case POUNCE -> pounce(level);
            case LATCHED -> latched(level);
            case FALL -> {
                if (onGround() || isInWater() || stateTicks > 60) land(level);
            }
            case SPLIT -> {
                getNavigation().stop();
                if (stateTicks >= SPLIT_TICKS) split(level);
            }
        }
    }

    /** The most mana within its search range that it can see: a player, or a player's familiar. Ties go to the nearest. */
    private @Nullable LivingEntity smell(ServerLevel level) {
        LivingEntity best = null;
        double bestMana = -1, bestDist = Double.MAX_VALUE;
        java.util.List<LivingEntity> near = new java.util.ArrayList<>(level.getEntitiesOfClass(Mob.class,
                getBoundingBox().inflate(SEARCH_RANGE), Familiars::isFamiliar));
        near.addAll(level.players());
        for (LivingEntity e : near) {
            double d = distanceToSqr(e);
            if (d > SEARCH_RANGE * SEARCH_RANGE || !canHunt(e) || !hasLineOfSight(e)) continue;
            double m = manaOf(e);
            if (m > bestMana || (m == bestMana && d < bestDist)) {
                best = e;
                bestMana = m;
                bestDist = d;
            }
        }
        return best;
    }

    private void seek(ServerLevel level) {
        if (canSplit(level)) {
            startSplit();
            return;
        }
        if (Familiars.isFamiliar(this)) {
            // A familiar picks no prey of its own: it fights what its master fights (the bond's order), and otherwise follows.
            LivingEntity ordered = Familiars.orderedTarget(this);
            prey = ordered != null && canHunt(ordered) ? ordered : null;
        } else if (prey == null || stateTicks % 20 == 0) {
            LivingEntity found = smell(level);
            // It keeps after its prey while it is still within reach, unless something richer turns up.
            if (found != null || prey == null || distanceToSqr(prey) > SEARCH_RANGE * SEARCH_RANGE * 2) prey = found;
        }
        setTarget(prey);
        if (prey == null) return;
        getLookControl().setLookAt(prey, 30, 30);
        if (distanceTo(prey) <= CHARGE_RANGE && onGround() && hasLineOfSight(prey)) {
            startCharge();
        } else if (stateTicks % 10 == 1 || getNavigation().isDone()) {
            getNavigation().moveTo(prey, 1.0);
        }
    }

    private void startCharge() {
        getNavigation().stop();
        setState(State.CHARGE);
        playSound(SoundEvents.SILVERFISH_AMBIENT, 1f, 0.5f);
    }

    /** Coiled and still, swelling; a blow or its prey moving off breaks the coil. */
    private void charge(ServerLevel level) {
        getNavigation().stop();
        if (prey == null || distanceTo(prey) > BREAK_RANGE) {
            setState(State.SEEK);
            return;
        }
        getLookControl().setLookAt(prey, 30, 30);
        faceToward(prey.getEyePosition());
        if (stateTicks >= CHARGE_TICKS) {
            Vec3 aim = prey.getEyePosition().subtract(mouth());
            pounceVelocity = aim.lengthSqr() < 1e-4 ? Vec3.ZERO : aim.normalize().scale(POUNCE_SPEED);
            setState(State.POUNCE);
            setDeltaMovement(pounceVelocity);
            playSound(SoundEvents.SLIME_JUMP_SMALL, 1f, 0.7f);
        }
    }

    /** Straight at where the face was when it leapt: a quick step aside makes it miss. */
    private void pounce(ServerLevel level) {
        setDeltaMovement(pounceVelocity);
        needsSync = true;
        if (prey != null && (mouth().distanceTo(prey.getEyePosition()) < 1.0 || getBoundingBox().inflate(0.2).intersects(prey.getBoundingBox()))) {
            latch(level, prey);
        } else if (stateTicks >= POUNCE_TICKS || (stateTicks > 2 && (horizontalCollision || verticalCollision))) {
            setState(State.FALL);
            setDeltaMovement(getDeltaMovement().scale(0.3));
        }
    }

    private void latch(ServerLevel level, LivingEntity on) {
        setState(State.LATCHED);
        entityData.set(LATCHED_ON, on.getId());
        setDeltaMovement(Vec3.ZERO);
        cling(on);
        playSound(SoundEvents.SLIME_SQUISH_SMALL, 1f, 0.6f);
        if (on instanceof Player p) blind(p);
    }

    private void latched(ServerLevel level) {
        if (!(latchedOn() instanceof LivingEntity on) || on != prey) {
            dropOff(level);
            return;
        }
        if (stateTicks % DRAIN_EVERY == 0) drain(level, on);
        if (isAlive() && state() == State.LATCHED && stateTicks >= LATCH_TICKS) dropOff(level);
    }

    /** Sits with its mouth on {@code on}'s face, its body sticking out ahead of it. */
    private void cling(LivingEntity on) {
        Vec3 look = Vec3.directionFromRotation(0, on.getYHeadRot());
        // The mouth stays in the middle of their gaze, up or down; the body itself stays level.
        Vec3 face = on.getEyePosition().add(Vec3.directionFromRotation(on.getXRot(), on.getYHeadRot()).scale(FACE_GAP));
        Vec3 at = face.add(look.scale(MOUTH_AHEAD)).subtract(0, MOUTH_UP, 0);
        float yaw = Mth.wrapDegrees(on.getYHeadRot() + 180);
        setPos(at.x, at.y, at.z);
        setDeltaMovement(Vec3.ZERO);
        setYRot(yaw);
        setYHeadRot(yaw);
        yBodyRot = yaw;
        setXRot(0);
    }

    /** One draw: 10 mana (or what is left), then a bite of its attack as magic damage. */
    private void drain(ServerLevel level, LivingEntity on) {
        double took = Math.min(DRAIN, Math.max(0, manaOf(on)));
        float wound = 0;
        if (on instanceof Player p) {
            PlayerMagic.of(p).mana -= took;
            blind(p);
        } else if (on instanceof ManaLifeform lf) {
            lf.setMana(lf.mana() - took);
        } else if (on instanceof AlchemicalColossus c) {
            c.setMana(c.trueMana() - took);
        } else {
            wound = (float) took; // a summoned familiar's (or any other creature's) health is its mana
        }
        // A familiar drinks for its master: what fits in their pool goes there, and it stays empty.
        UUID masterId = Familiars.masterOf(this);
        Player master = masterId == null ? null : level.getPlayerByUUID(masterId);
        if (master != null) {
            PlayerMagic m = PlayerMagic.of(master);
            m.mana = Math.min(m.maxMana, m.mana + took);
        } else if (masterId == null) {
            setMana(mana() + took);
        }
        float bite = (float) (attack() * (level.getDifficulty() == Difficulty.HARD ? 1.5 : 1));
        if (level.getDifficulty() == Difficulty.PEACEFUL) bite = 0;
        on.hurtServer(level, damageSources().source(DAMAGE, this), bite + wound);
        playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f, 0.6f);
        level.sendParticles(ParticleTypes.ENCHANT, on.getX(), on.getEyeY(), on.getZ(), 8, 0.3, 0.3, 0.3, 0.5);
    }

    private static void blind(Player p) {
        p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DRAIN_EVERY * 2, 0, false, false));
    }

    /** Lets go of the face and falls off. */
    private void dropOff(ServerLevel level) {
        Entity on = latchedOn();
        entityData.set(LATCHED_ON, -1);
        noPhysics = false;
        setNoGravity(false);
        // Clinging, it may have hung inside a wall the face was turned to: then it falls at its prey's feet.
        if (on != null && !level.noCollision(this)) setPos(on.getX(), on.getY(), on.getZ());
        setState(State.FALL);
    }

    /** Down again: it splits if it is ready, else coils again if its prey is near, else hunts on. */
    private void land(ServerLevel level) {
        setState(State.SEEK);
        if (canSplit(level)) startSplit();
        else if (prey != null && distanceTo(prey) <= CHARGE_RANGE && hasLineOfSight(prey)) startCharge();
    }

    private void faceToward(Vec3 at) {
        double dx = at.x - getX(), dz = at.z - getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
        setYRot(yaw);
        yBodyRot = yaw;
        setYHeadRot(yaw);
    }

    /** Its mouth in the world. */
    private Vec3 mouth() {
        return position().add(Vec3.directionFromRotation(0, getYRot()).scale(MOUTH_AHEAD)).add(0, MOUTH_UP, 0);
    }

    // ---- splitting ----------------------------------------------------------------------------------

    private boolean canSplit(ServerLevel level) {
        return !offspring && mana() >= SPLIT_AT && !Familiars.isFamiliar(this)
                && level.getEntitiesOfClass(ManaLeech.class, getBoundingBox().inflate(SEARCH_RANGE), Entity::isAlive).size() < MAX_NEARBY;
    }

    private void startSplit() {
        getNavigation().stop();
        setState(State.SPLIT);
        playSound(SoundEvents.SLIME_SQUISH, 1f, 0.5f);
    }

    /** Two empty leeches in its place; the mana it held goes into making them. */
    private void split(ServerLevel level) {
        for (int i = 0; i < 2; i++) {
            ManaLeech child = LogosMod.MANA_LEECH.get().create(level, EntitySpawnReason.TRIGGERED);
            if (child == null) continue;
            float yaw = getYRot() + (i == 0 ? 90 : -90);
            child.snapTo(getX(), getY(), getZ(), yaw, 0);
            child.setOffspring(true);
            child.prey = prey;
            child.setDeltaMovement(Vec3.directionFromRotation(0, yaw).scale(0.25).add(0, 0.2, 0));
            if (isPersistenceRequired()) child.setPersistenceRequired();
            level.addFreshEntity(child);
        }
        level.sendParticles(ParticleTypes.WITCH, getX(), getY() + 0.2, getZ(), 20, 0.4, 0.2, 0.4, 0.05);
        playSound(SoundEvents.SLIME_SQUISH_SMALL, 1f, 1.2f);
        discard();
    }

    // ---- blows and death --------------------------------------------------------------------------

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        boolean hurt = super.hurtServer(level, source, damage);
        if (!hurt || isDeadOrDying()) return hurt;
        ItemStack weapon = source.getWeaponItem();
        if (weapon == null && source.getEntity() instanceof LivingEntity attacker) weapon = attacker.getMainHandItem();
        if (weapon != null && !weapon.isEmpty()) {
            int bane = enchantment(level, Enchantments.BANE_OF_ARTHROPODS, weapon);
            if (bane > 0 && mana() > 0) {
                double scattered = mana() * Math.min(1, 0.1 + 0.1 * bane);
                setMana(mana() - scattered);
                FreeManaEntity.release(level, position().add(0, getBbHeight() / 2, 0), scattered);
            }
            int knock = enchantment(level, Enchantments.KNOCKBACK, weapon);
            if (knock > 0 && state() == State.LATCHED && latchedOn() instanceof LivingEntity on) {
                // Struck off the face, away from it.
                dropOff(level);
                Vec3 away = position().subtract(on.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, getYRot() + 180) : away.normalize();
                setDeltaMovement(away.scale(0.5 + 0.4 * knock).add(0, 0.3, 0));
                needsSync = true;
                return hurt;
            }
        }
        // A blow breaks its coil; it sizes things up again.
        if (state() == State.CHARGE) setState(State.SEEK);
        return hurt;
    }

    private static int enchantment(ServerLevel level, ResourceKey<Enchantment> key, ItemStack stack) {
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key)
                .map((Holder<Enchantment> h) -> EnchantmentHelper.getItemEnchantmentLevel(h, stack)).orElse(0);
    }

    @Override
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel level && !isRemoved() && !dead) {
            if (state() == State.LATCHED) dropOff(level);
            FreeManaEntity.release(level, position().add(0, getBbHeight() / 2, 0), mana() * 0.8);
            setMana(0);
        }
        super.die(source);
    }

    @Override
    protected void dropFromLootTable(ServerLevel level, DamageSource source, boolean playerKilled) {
        if (offspring) dropFromLootTable(level, source, playerKilled, OFFSPRING_LOOT);
        else super.dropFromLootTable(level, source, playerKilled);
    }

    @Override
    protected void tickDeath() {
        // Its death animation runs 1.6 seconds.
        ++deathTime;
        if (deathTime >= 32 && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public boolean isPushable() {
        return state() != State.LATCHED && super.isPushable();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SILVERFISH_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SILVERFISH_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SILVERFISH_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.6f;
    }

    // ---- saving -----------------------------------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putDouble("mana", mana());
        out.putBoolean("offspring", offspring);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        setMana(in.getDoubleOr("mana", 0));
        offspring = in.getBooleanOr("offspring", false);
    }

    // ---- animation --------------------------------------------------------------------------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<ManaLeech>("body", 3, test -> {
            test.setControllerSpeed(1f);
            if (isDeadOrDying()) return test.setAndContinue(DEATH);
            switch (state()) {
                case SPLIT -> {
                    test.setControllerSpeed(SPLIT_SECONDS * 20 / SPLIT_TICKS);
                    return test.setAndContinue(SPLIT);
                }
                case LATCHED -> {
                    return test.setAndContinue(FEED_LOOP);
                }
                case POUNCE -> {
                    return test.setAndContinue(POUNCE);
                }
                case CHARGE -> {
                    return test.setAndContinue(CHARGE);
                }
                default -> {
                }
            }
            if (hurtTime > 0) return test.setAndContinue(HURT);
            return test.setAndContinue(test.isMoving() ? SWIM : IDLE);
        }));
        // Registered after the body so its swelling wins on the flesh bones it scales.
        controllers.add(new AnimationController<ManaLeech>("swell", 5, test -> {
            State s = state();
            if (isDeadOrDying() || s == State.LATCHED || s == State.SPLIT || mana() < SWELL_AT) return PlayState.STOP;
            return test.setAndContinue(FEED);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    /** How brightly its glands glow, 0 to 3: by the mana it holds, and full while it coils or splits. */
    public int glowStage() {
        State s = state();
        if (s == State.CHARGE || s == State.SPLIT) return 3;
        return (int) Math.min(3, mana() / 15);
    }
}
