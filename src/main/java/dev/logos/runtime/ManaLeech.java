package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A mana predator that chooses the richest player or player familiar within the configured radius.
 * Ordinary familiars store mana as health; players and made familiars use their existing mana pools.
 * Inside three blocks it charges for 30 ticks, physically lunges to the face, and latches for 60 ticks.
 * Three one-second pulses take at most ten actual mana each; ordinary familiars receive only that
 * health drain, while players and made familiars also take difficulty-scaled magic damage.
 * Attack grows from two by two per ten mana actually absorbed, even if stored mana is later expelled.
 *
 * <p>Only a naturally spawned mother can split after collecting 60 mana. Both ends of the 40-tick
 * split check that fewer than five living leeches, including the mother, are within sixteen blocks.
 * The two zero-mana children cannot split again; they drop two or three existing tainted shards
 * instead of free mana on death. An original drops one shard and 80% of its remaining stored mana.
 * Bane of Arthropods keeps its vanilla bonus and expels 20% at level one, then ten percentage points
 * per additional level. An effective melee hit from a Knockback-enchanted sword releases the latch.
 *
 * <p>The client uses nine runtime clips and fullbright texture masks. The searchable
 * {@code TODO_MANA_LEECH_DEATH_BURST} hook remains empty for the maintainer's death effect.
 */
public final class ManaLeech extends Monster implements GeoEntity {
    public enum State { SEEK, CHARGE, POUNCE, ATTACHED, FALL, SPLIT }

    public static final int CHARGE_TICKS = 30, ATTACH_TICKS = 60, SPLIT_TICKS = 40;
    public static final double CHARGE_RANGE = 3, DRAIN_PER_SECOND = 10, SPLIT_MANA = 60;
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(ManaLeech.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> STATE_TICKS = SynchedEntityData.defineId(ManaLeech.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTACHED_TARGET = SynchedEntityData.defineId(ManaLeech.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> MANA = SynchedEntityData.defineId(ManaLeech.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ABSORBED = SynchedEntityData.defineId(ManaLeech.class, EntityDataSerializers.FLOAT);
    private static final ResourceKey<DamageType> MAGIC = ResourceKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_leech_magic"));
    private static final ResourceKey<DamageType> DRAIN = ResourceKey.create(Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_leech_drain"));
    private static final RawAnimation IDLE = loop("idle"), SWIM = loop("swim"), CHARGE = play("charge"), POUNCE = RawAnimation.begin().thenPlayAndHold("animation.mana_leech.pounce"),
            ATTACHED = RawAnimation.begin().thenPlay("animation.mana_leech.attached").thenLoop("animation.mana_leech.attached_loop"),
            FALL = play("hurt"), SPLIT = play("split_runtime"), DEATH = RawAnimation.begin().thenPlayAndHold("animation.mana_leech.death");
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private double storedMana;
    private double totalAbsorbed;
    private boolean deathManaReleased;
    private boolean naturalOrigin;
    private boolean splitOffspring;

    public ManaLeech(EntityType<? extends ManaLeech> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, LogosConfig.manaLeechHealth())
                .add(Attributes.MOVEMENT_SPEED, 0.28).add(Attributes.FOLLOW_RANGE, 128).add(Attributes.ATTACK_DAMAGE, 2);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, State.SEEK.ordinal());
        builder.define(STATE_TICKS, 0);
        builder.define(ATTACHED_TARGET, -1);
        builder.define(MANA, 0f);
        builder.define(ABSORBED, 0f);
    }

    public State state() { return State.values()[entityData.get(STATE)]; }
    public int stateTicks() { return entityData.get(STATE_TICKS); }
    public double mana() { return level().isClientSide() ? entityData.get(MANA) : storedMana; }
    public double totalAbsorbedMana() { return level().isClientSide() ? entityData.get(ABSORBED) : totalAbsorbed; }
    public double attackDamage() { return 2 + totalAbsorbedMana() / 10 * 2; }
    public float chargeProgress() { return state() == State.CHARGE ? Math.clamp(stateTicks() / 30f, 0, 1) : 0; }
    public float feedProgress() { return state() == State.ATTACHED ? Math.clamp(stateTicks() / 60f, 0, 1) : (float) (mana() / SPLIT_MANA); }
    public boolean isSplitOffspring() { return splitOffspring; }
    public boolean canSplit() { return naturalOrigin && !splitOffspring; }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
            EntitySpawnReason reason, @Nullable SpawnGroupData group) {
        naturalOrigin = reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.CHUNK_GENERATION;
        return super.finalizeSpawn(level, difficulty, reason, group);
    }

    /** Includes this living leech; four becomes five when a mother is replaced by two offspring. */
    public int nearbyLeechCount() {
        return level().getEntitiesOfClass(ManaLeech.class, getBoundingBox().inflate(16),
                e -> e.isAlive() && !e.isDeadOrDying() && distanceToSqr(e) <= 16 * 16).size();
    }

    /** Current pool only; expelling mana does not erase the strength earned from previous feeding. */
    public void setStoredMana(double value) {
        storedMana = Double.isFinite(value) ? Math.max(0, value) : 0;
        entityData.set(MANA, (float) Math.min(storedMana, Float.MAX_VALUE));
    }

    public @Nullable LivingEntity attachedTarget() {
        return level().getEntity(entityData.get(ATTACHED_TARGET)) instanceof LivingEntity living ? living : null;
    }

    private void changeState(State state) {
        entityData.set(STATE, state.ordinal());
        entityData.set(STATE_TICKS, 0);
        if (state != State.ATTACHED) entityData.set(ATTACHED_TARGET, -1);
        noPhysics = state == State.ATTACHED;
        setNoGravity(state == State.ATTACHED || state == State.POUNCE);
        if (state != State.SEEK) getNavigation().stop();
    }

    private static double bounded(double value, double capacity) {
        return Double.isFinite(value) && Double.isFinite(capacity) ? Math.clamp(value, 0, Math.max(0, capacity)) : 0;
    }

    /** Ordinary familiars hold mana in their health; made familiars hold it in their existing mana pool. */
    public static double availableMana(LivingEntity prey) {
        if (!prey.isAlive() || prey.isDeadOrDying()) return 0;
        if (prey instanceof Player player) {
            if (player.isSpectator() || player.getAbilities().instabuild) return 0;
            PlayerMagic magic = PlayerMagic.of(player);
            return bounded(magic.mana, magic.maxMana);
        }
        if (!Familiars.isFamiliar(prey)) return 0;
        if (prey instanceof ManaVessel vessel) return bounded(vessel.mana(), vessel.maxMana());
        return Math.max(0, prey.getHealth());
    }

    /** Highest current mana wins; ties choose the closer prey and then its stable entity id. */
    public @Nullable LivingEntity findPrey() {
        double radius = LogosConfig.manaLeechSearchRadius(), bestMana = 0, bestDistance = Double.MAX_VALUE;
        LivingEntity best = null;
        for (LivingEntity prey : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(radius), e -> e != this)) {
            double distance = distanceToSqr(prey), amount = availableMana(prey);
            if (distance > radius * radius || amount <= 0) continue;
            if (amount > bestMana || amount == bestMana && (distance < bestDistance
                    || distance == bestDistance && (best == null || prey.getId() < best.getId()))) {
                best = prey;
                bestMana = amount;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean valid(@Nullable LivingEntity prey) {
        return prey != null && prey.level() == level() && availableMana(prey) > 0
                && distanceToSqr(prey) <= Math.pow(LogosConfig.manaLeechSearchRadius(), 2);
    }

    @Override
    public void tick() {
        super.tick();
        if (isDeadOrDying()) return;
        if (level().isClientSide()) {
            if (state() == State.ATTACHED && attachedTarget() != null) pinToFace(attachedTarget());
            return;
        }
        if (isNoAi()) return;
        entityData.set(STATE_TICKS, stateTicks() + 1);
        if (mana() >= SPLIT_MANA && canSplit() && nearbyLeechCount() < 5
                && state() != State.SPLIT && state() != State.ATTACHED) {
            setDeltaMovement(Vec3.ZERO);
            changeState(State.SPLIT);
            playSound(SoundEvents.EVOKER_PREPARE_SUMMON, 0.7f, 1.4f);
        }
        ServerLevel level = (ServerLevel) level();
        switch (state()) {
            case SEEK -> seek();
            case CHARGE -> charge(level);
            case POUNCE -> pounce();
            case ATTACHED -> attached(level);
            case FALL -> {
                if (stateTicks() >= 4 && (onGround() || isInWater())) changeState(State.SEEK);
            }
            case SPLIT -> {
                getNavigation().stop();
                setDeltaMovement(new Vec3(0, getDeltaMovement().y, 0));
                if (stateTicks() % 5 == 0) level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + 0.25, getZ(), 3, .25, .15, .25, .01);
                if (stateTicks() >= SPLIT_TICKS) split(level);
            }
        }
    }

    private void seek() {
        if (level().getDifficulty() == Difficulty.PEACEFUL) {
            setTarget(null);
            getNavigation().stop();
            return;
        }
        if (stateTicks() % 10 == 1 || !valid(getTarget())) setTarget(findPrey());
        LivingEntity prey = getTarget();
        if (prey == null) return;
        getLookControl().setLookAt(prey, 35, 35);
        if (distanceToSqr(prey) <= CHARGE_RANGE * CHARGE_RANGE && hasLineOfSight(prey)) {
            setDeltaMovement(Vec3.ZERO);
            changeState(State.CHARGE);
            playSound(SoundEvents.EVOKER_PREPARE_ATTACK, .65f, 1.6f);
        } else if (stateTicks() % 10 == 1 || getNavigation().isDone()) getNavigation().moveTo(prey, 1);
    }

    private void charge(ServerLevel level) {
        LivingEntity prey = getTarget();
        getNavigation().stop();
        setDeltaMovement(new Vec3(0, getDeltaMovement().y, 0));
        if (!valid(prey) || distanceToSqr(prey) > CHARGE_RANGE * CHARGE_RANGE || !hasLineOfSight(prey)) {
            setTarget(null);
            changeState(State.SEEK);
            return;
        }
        getLookControl().setLookAt(prey, 35, 35);
        if (stateTicks() % 5 == 0) level.sendParticles(ParticleTypes.ENCHANT, getX(), getY() + .3, getZ(), 3, .2, .15, .2, .15);
        if (stateTicks() >= CHARGE_TICKS) {
            changeState(State.POUNCE);
            playSound(SoundEvents.SLIME_JUMP, .8f, .8f);
            aimPounce(prey);
        }
    }

    private void aimPounce(LivingEntity prey) {
        Vec3 to = faceOrigin(prey).subtract(position());
        setDeltaMovement(to.length() > .7 ? to.normalize().scale(.7) : to);
        hurtMarked = true;
    }

    private void pounce() {
        LivingEntity prey = getTarget();
        if (!valid(prey) || stateTicks() > 20 || horizontalCollision) { dropOff(); return; }
        if (position().distanceToSqr(faceOrigin(prey)) <= .16) {
            changeState(State.ATTACHED);
            entityData.set(ATTACHED_TARGET, prey.getId());
            setDeltaMovement(Vec3.ZERO);
            pinToFace(prey);
            playSound(SoundEvents.PHANTOM_BITE, .6f, 1.5f);
        } else aimPounce(prey);
    }

    private static float faceYaw(LivingEntity prey) { return prey instanceof Player ? prey.getYRot() : prey.getYHeadRot(); }

    private static Vec3 faceForward(LivingEntity prey) {
        return Vec3.directionFromRotation(prey.getXRot(), faceYaw(prey));
    }

    private static Vec3 faceUp(LivingEntity prey) {
        double yaw = Math.toRadians(faceYaw(prey)), pitch = Math.toRadians(prey.getXRot());
        return new Vec3(-Math.sin(yaw) * Math.sin(pitch), Math.cos(pitch), Math.cos(yaw) * Math.sin(pitch));
    }

    /** Align the exported face_contact point [0,3.65,-13.3346] to the target's front face. */
    private static Vec3 faceOrigin(LivingEntity prey) {
        Vec3 front = faceForward(prey);
        return prey.getEyePosition().add(front.scale(prey.getBbWidth() / 2 + .06 + 13.3346 / 16))
                .add(faceUp(prey).scale(-.10 - 3.65 / 16));
    }

    private void pinToFace(LivingEntity prey) {
        Vec3 origin = faceOrigin(prey);
        setPos(origin.x, origin.y, origin.z);
        float yaw = faceYaw(prey) + 180;
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        setXRot(-prey.getXRot());
        setDeltaMovement(Vec3.ZERO);
    }

    private void attached(ServerLevel level) {
        LivingEntity prey = attachedTarget();
        if (prey == null || !prey.isAlive() || prey.isDeadOrDying() || prey.level() != level
                || prey instanceof Player player && (player.isSpectator() || player.getAbilities().instabuild)
                || !(prey instanceof Player) && !Familiars.isFamiliar(prey)) { dropOff(); return; }
        pinToFace(prey);
        if (stateTicks() % 20 == 0 && stateTicks() <= ATTACH_TICKS) feed(level, prey);
        if (stateTicks() >= ATTACH_TICKS && mana() >= SPLIT_MANA && canSplit() && nearbyLeechCount() < 5) {
            changeState(State.SPLIT);
            playSound(SoundEvents.EVOKER_PREPARE_SUMMON, .7f, 1.4f);
        } else if (stateTicks() >= ATTACH_TICKS || !prey.isAlive()) dropOff();
    }

    private DamageSource damage(ServerLevel level, ResourceKey<DamageType> key) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key), this, this);
    }

    private void feed(ServerLevel level, LivingEntity prey) {
        double wanted = Math.min(DRAIN_PER_SECOND, availableMana(prey));
        boolean ordinary = !(prey instanceof Player) && !(prey instanceof ManaVessel);
        if (wanted <= 0) {
            if (!ordinary) prey.hurtServer(level, damage(level, MAGIC), spellDamage(level.getDifficulty()));
            return;
        }
        double took;
        if (prey instanceof Player player) {
            PlayerMagic magic = PlayerMagic.of(player);
            took = wanted;
            magic.mana = Math.max(0, magic.mana - took);
        } else if (prey instanceof ManaLifeform lifeform) {
            double before = lifeform.mana();
            lifeform.setMana(before - wanted);
            took = Math.max(0, before - lifeform.mana());
        } else if (prey instanceof AlchemicalColossus colossus) {
            double before = colossus.trueMana();
            colossus.setMana(before - wanted);
            took = Math.max(0, before - colossus.trueMana());
        } else if (ordinary) {
            float before = prey.getHealth();
            prey.hurtServer(level, damage(level, DRAIN), (float) wanted);
            took = Math.clamp(before - prey.getHealth(), 0, wanted);
        } else return;
        totalAbsorbed += took;
        entityData.set(ABSORBED, (float) totalAbsorbed);
        setStoredMana(mana() + took);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(attackDamage());
        if (!ordinary && took > 0) prey.hurtServer(level, damage(level, MAGIC), spellDamage(level.getDifficulty()));
        if (took > 0) playSound(SoundEvents.SLIME_SQUISH, .45f, 1.3f);
    }

    public float spellDamage(Difficulty difficulty) {
        return (float) (attackDamage() * (difficulty == Difficulty.PEACEFUL ? 0 : difficulty == Difficulty.HARD ? 1.5 : 1));
    }

    private void dropOff() {
        Vec3 away = getLookAngle().scale(-.18);
        changeState(State.FALL);
        setDeltaMovement(away.x, -.12, away.z);
        hurtMarked = true;
    }

    private void split(ServerLevel level) {
        if (!canSplit() || mana() < SPLIT_MANA || nearbyLeechCount() >= 5) {
            changeState(State.SEEK);
            return;
        }
        ManaLeech first = LogosMod.MANA_LEECH.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        ManaLeech second = LogosMod.MANA_LEECH.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (first == null || second == null) return;
        first.snapTo(getX() - .35, getY(), getZ(), getYRot(), 0);
        second.snapTo(getX() + .35, getY(), getZ(), getYRot(), 0);
        first.setStoredMana(0);
        second.setStoredMana(0);
        first.splitOffspring = true;
        second.splitOffspring = true;
        if (!level.addFreshEntity(first) || !level.addFreshEntity(second)) {
            first.discard();
            second.discard();
            changeState(State.SEEK);
            return;
        }
        level.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1, 1.4f);
        discard();
    }

    @Override
    public void travel(Vec3 input) {
        if (state() == State.ATTACHED) { setDeltaMovement(Vec3.ZERO); return; }
        if (state() == State.POUNCE) { move(MoverType.SELF, getDeltaMovement()); return; }
        super.travel(state() == State.CHARGE || state() == State.SPLIT ? Vec3.ZERO : input);
    }

    /** A Knockback sword breaks the latch; arrows and spells cannot borrow a held sword's enchantment. */
    private void detachForSwordHit(DamageSource source) {
        if (state() != State.ATTACHED || !source.isDirect()
                || !(source.getDirectEntity() instanceof LivingEntity attacker)) return;
        if (!source.is(DamageTypeTags.IS_PLAYER_ATTACK) && !source.is(DamageTypes.MOB_ATTACK)
                && !source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)) return;
        ItemStack weapon = source.getWeaponItem();
        if (weapon == null || !weapon.is(ItemTags.SWORDS)) return;
        var knockback = level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK);
        if (weapon.getEnchantmentLevel(knockback) <= 0) return;
        Vec3 away = position().subtract(attacker.position()).multiply(1, 0, 1);
        if (away.horizontalDistanceSqr() < .0001) away = attacker.getLookAngle().multiply(1, 0, 1);
        if (away.horizontalDistanceSqr() < .0001) away = new Vec3(0, 0, 1);
        changeState(State.FALL);
        setTarget(null);
        // Vanilla adds its hit and enchantment knockback after actuallyHurt; FALL no longer pins or clears it.
        setDeltaMovement(away.normalize().scale(.35).add(0, .3, 0));
        hurtMarked = true;
    }

    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float amount) {
        float before = getHealth(), absorption = getAbsorptionAmount();
        super.actuallyHurt(level, source, amount);
        if (getHealth() < before || getAbsorptionAmount() < absorption) {
            ManaLeechBane.disperse(this, level, ManaLeechBane.level(source));
            detachForSwordHit(source);
            if (state() == State.CHARGE || state() == State.SPLIT && mana() < SPLIT_MANA) {
                setTarget(null);
                changeState(State.SEEK);
            }
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!dead || deathManaReleased || !(level() instanceof ServerLevel level)) return;
        deathManaReleased = true;
        noPhysics = false;
        setNoGravity(false);
        entityData.set(ATTACHED_TARGET, -1);
        if (!splitOffspring) FreeManaEntity.releaseAt(level, getBoundingBox().getCenter(), mana() * .8);
        setStoredMana(0);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        spawnAtLocation(level, new net.minecraft.world.item.ItemStack(LogosMod.TAINTED_MANA_SHARD.get(),
                splitOffspring ? 2 + random.nextInt(2) : 1));
    }

    @Override
    protected void tickDeath() {
        ++deathTime;
        if (level().isClientSide() && deathTime == 18) onDeathBurst(getBoundingBox().getCenter());
        if (deathTime >= 32 && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    /** User-owned death effect; called once at 0.9 seconds on the client. */
    private void onDeathBurst(Vec3 worldPosition) {
        // TODO_MANA_LEECH_DEATH_BURST: add the death spray here; intentionally empty.
    }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putDouble("mana", mana());
        out.putDouble("absorbed_mana", totalAbsorbed);
        out.putBoolean("death_mana_released", deathManaReleased);
        out.putBoolean("natural_origin", naturalOrigin);
        out.putBoolean("split_offspring", splitOffspring);
        out.putInt("split_ticks", state() == State.SPLIT ? stateTicks() : -1);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        setStoredMana(in.getDoubleOr("mana", 0));
        totalAbsorbed = Math.max(0, in.getDoubleOr("absorbed_mana", mana()));
        if (!Double.isFinite(totalAbsorbed)) totalAbsorbed = mana();
        entityData.set(ABSORBED, (float) totalAbsorbed);
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(attackDamage());
        deathManaReleased = in.getBooleanOr("death_mana_released", false);
        naturalOrigin = in.getBooleanOr("natural_origin", false);
        splitOffspring = in.getBooleanOr("split_offspring", false);
        int splitTicks = in.getIntOr("split_ticks", -1);
        changeState(splitTicks >= 0 && canSplit() && mana() >= SPLIT_MANA ? State.SPLIT : State.SEEK);
        if (state() == State.SPLIT) entityData.set(STATE_TICKS, Math.clamp(splitTicks, 0, SPLIT_TICKS - 1));
        setTarget(null);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<ManaLeech>("main", 2, test -> {
            if (isDeadOrDying()) return test.setAndContinue(DEATH);
            return test.setAndContinue(switch (state()) {
                case CHARGE -> CHARGE;
                case POUNCE -> POUNCE;
                case ATTACHED -> ATTACHED;
                case FALL -> FALL;
                case SPLIT -> SPLIT;
                case SEEK -> getDeltaMovement().horizontalDistanceSqr() > .0001 ? SWIM : IDLE;
            });
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return geoCache; }
    private static RawAnimation loop(String action) { return RawAnimation.begin().thenLoop("animation.mana_leech." + action); }
    private static RawAnimation play(String action) { return RawAnimation.begin().thenPlay("animation.mana_leech." + action); }
}
