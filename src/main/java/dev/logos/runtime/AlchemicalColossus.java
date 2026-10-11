package dev.logos.runtime;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.util.GeckoLibUtil;

import dev.logos.LogosMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 炼金术巨像: a built alchemical colossus. Slow, steady and heavily armoured — it takes a quarter of
 * every blow, as the ender dragon's body does, and shrugs off knockback. It fights with its fists and
 * a palm cannon (see {@link ColossusMind}), carries up to two players on its shoulders, and can be
 * driven by its master.
 * <p>
 * Mana. Its master sees a pool of {@value #DISPLAY_CAP}, but it holds up to {@value #FRENZY_AT}:
 * fed past what it shows (or fed an unstable fuel) it goes into a mana frenzy — the scorched shell,
 * the corrupted gait, attacking whatever is near, its master included, at ten times the mana. It
 * cannot die in a frenzy: a lethal blow, or running dry, burns it out instead. Burnt out it drops a
 * disorder crystal, wakes sane, keeps the scorched look until maintained, holds {@value #TEMPERED_CAP}
 * openly, gains two device slots and never goes berserk again ("tempered").
 * <p>
 * Outside a frenzy it dies like any creature, dropping the devices it carried.
 */
public class AlchemicalColossus extends PathfinderMob implements GeoEntity, ManaVessel, KeptFamiliar {
    public static final double DISPLAY_CAP = 50_000, FRENZY_AT = 75_000, TEMPERED_CAP = 100_000;
    /** Mana it needs before it powers up again after running dry. */
    public static final double WAKE_AT = 1_000;
    /** Idle upkeep per second: 2% of what it shows per in-game day. */
    public static final double IDLE_UPKEEP = DISPLAY_CAP * 0.02 / 1200;
    /** In a frenzy every cost is this many times as much, and the frenzy itself burns this much a second on top. */
    public static final double FRENZY_MULTIPLIER = 10, FRENZY_BURN = 250;
    public static final int DEVICES = 3, TEMPERED_DEVICES = 5;
    /**
     * Driven, it runs at a heavy pace — about 2.2 blocks a second, its run cycle played a third faster —
     * rather than at a horse's (its movement speed would carry it at over 10).
     */
    public static final float DRIVE_SPEED = 0.047f;
    /** How far a planted foot slides back per tick in the run cycle at normal playback (1.4 model units, from the animation). */
    static final double RUN_STRIDE = 1.395 / 16;

    /** Everything it does with its whole body, each one animation; null-free so it can be synced as an ordinal. */
    public enum Act {
        NONE("", 0),
        PUNCH("punch_right", 36),
        GRAB_REACH("grab_reach", 22),
        GRAB_LIFT("grab_lift", 28),
        GRAB_HOLD("grab_hold", -1),
        GRAB_EXECUTE("grab_execute", 32),
        GRAB_SLAM("grab_slam", 40),
        GRAB_MISS("grab_miss", 18),
        CANNON_PREPARE("cannon_prepare", 30),
        CANNON_CHARGE("cannon_charge", 36),
        CANNON_FIRE("cannon_fire_start", -1),
        CANNON_RECOVER("cannon_recover", 40),
        PICKUP_LEFT("player_pickup", 100),
        PICKUP_RIGHT("player_pickup_right", 100),
        PUTDOWN_LEFT("player_put_down", 100),
        PUTDOWN_RIGHT("player_put_down_right", 100),
        STARTUP("startup", 56),
        POWER_DOWN("power_down", 68),
        DORMANT("dormant", -1),
        POWER_UP("power_up", 64),
        FLOWER_OFFER("flower_offer", 72),
        FLOWER_HOLD("flower_hold", -1),
        FLOWER_RETRACT("flower_retract", 64),
        HURT("hurt", 13),
        REMORSE("berserk_remorse", 172);

        public final String anim;
        /** Its length in ticks, or -1 for a loop held until something else is played. */
        public final int ticks;
        final RawAnimation raw;

        Act(String anim, int ticks) {
            this.anim = anim;
            this.ticks = ticks;
            String full = "animation.alchemical_colossus." + anim;
            if (anim.isEmpty()) raw = null;
            else if (anim.equals("cannon_fire_start")) raw = RawAnimation.begin().thenPlay(full).thenLoop("animation.alchemical_colossus.cannon_fire_loop");
            else if (ticks < 0) raw = RawAnimation.begin().thenLoop(full);
            else raw = RawAnimation.begin().thenPlayAndHold(full);
        }
    }

    /** Who rides where: its two shoulder seats, and whatever its hand is holding. */
    public enum Hold { SEAT_LEFT, SEAT_RIGHT, GRIP }

    private static final EntityDataAccessor<Float> MANA = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> BERSERK = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SCORCHED = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> TEMPERED = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DORMANT = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> RUNNING = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DRIVEN = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> ACT = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ACT_SEQ = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> GRIP = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SEAT_LEFT = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SEAT_RIGHT = SynchedEntityData.defineId(AlchemicalColossus.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.alchemical_colossus.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenPlay("animation.alchemical_colossus.walk_start").thenLoop("animation.alchemical_colossus.walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenPlay("animation.alchemical_colossus.run_start").thenLoop("animation.alchemical_colossus.run");
    private static final RawAnimation WALK_STOP = RawAnimation.begin().thenPlay("animation.alchemical_colossus.walk_stop").thenLoop("animation.alchemical_colossus.idle");
    private static final RawAnimation RUN_STOP = RawAnimation.begin().thenPlay("animation.alchemical_colossus.run_stop").thenLoop("animation.alchemical_colossus.idle");
    private static final RawAnimation WALK_CORRUPTED = RawAnimation.begin().thenLoop("animation.alchemical_colossus.walk_corrupted");
    private static final RawAnimation RUN_CORRUPTED = RawAnimation.begin().thenLoop("animation.alchemical_colossus.run_corrupted");
    private static final RawAnimation DEATH = RawAnimation.begin().thenPlayAndHold("animation.alchemical_colossus.death");
    private static final RawAnimation CORE = RawAnimation.begin().thenLoop("animation.alchemical_colossus.core_loop");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    public final SimpleContainer devices = new DeviceSlots();
    /** Its true mana; the synced value is what its master is shown. */
    private double mana;
    private int actTicks;
    /** Set in registerGoals, inside the Mob constructor: no initializer, or it would be wiped. */
    private ColossusMind mind;

    /** Client only: where the model's hands, seats and muzzles were last drawn, for placing riders exactly. */
    private final Map<String, Vec3> bones = new HashMap<>();
    private int bonesAt = -100;
    /** Client only: what the legs were last doing, to pick the start and stop steps. */
    private int lastSeq = -1;
    private Act lastAct = Act.NONE;
    private RawAnimation lastLoco = IDLE;
    /** Client only: how fast the run cycle plays, so its planted feet keep pace with the ground. */
    private float runPace = 1;

    public AlchemicalColossus(EntityType<? extends AlchemicalColossus> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 800)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1)
                .add(Attributes.ATTACK_DAMAGE, 50)
                .add(Attributes.FOLLOW_RANGE, 48)
                .add(Attributes.STEP_HEIGHT, 1.1)
                .add(Attributes.SAFE_FALL_DISTANCE, 12);
    }

    // ---- state --------------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(MANA, 0f);
        b.define(BERSERK, false);
        b.define(SCORCHED, false);
        b.define(TEMPERED, false);
        b.define(DORMANT, false);
        b.define(RUNNING, false);
        b.define(DRIVEN, false);
        b.define(ACT, 0);
        b.define(ACT_SEQ, 0);
        b.define(GRIP, -1);
        b.define(SEAT_LEFT, -1);
        b.define(SEAT_RIGHT, -1);
    }

    public boolean isBerserk() {
        return entityData.get(BERSERK);
    }

    /** The burnt, glowing shell: in a frenzy, and after one until maintained. */
    public boolean isScorched() {
        return entityData.get(SCORCHED);
    }

    /** It has burnt out a frenzy once: larger visible pool, two more device slots, never berserk again. */
    public boolean isTempered() {
        return entityData.get(TEMPERED);
    }

    @Override
    public boolean isDormant() {
        return entityData.get(DORMANT);
    }

    @Override
    public boolean actsAlone() {
        return isDormant() || isBerserk();
    }

    public boolean isRunning() {
        return entityData.get(RUNNING);
    }

    void setRunning(boolean running) {
        if (isRunning() != running) entityData.set(RUNNING, running);
    }

    /** Whether its master has taken the controls (drive mode) rather than just riding along. */
    public boolean isDriven() {
        return entityData.get(DRIVEN);
    }

    public void setDriven(boolean driven) {
        entityData.set(DRIVEN, driven);
    }

    public @Nullable ColossusMind mind() {
        return mind;
    }

    // ---- mana -----------------------------------------------------------------------------------

    /** What its master is shown: never past the visible pool. */
    @Override
    public double mana() {
        return Math.min(mana, maxMana());
    }

    /** The visible pool: {@value #DISPLAY_CAP}, or {@value #TEMPERED_CAP} once tempered. */
    @Override
    public double maxMana() {
        return isTempered() ? TEMPERED_CAP : DISPLAY_CAP;
    }

    /** Its true mana, hidden reserve included. */
    public double trueMana() {
        return mana;
    }

    public void setMana(double value) {
        mana = Mth.clamp(value, 0, isTempered() ? TEMPERED_CAP : FRENZY_AT);
        entityData.set(MANA, (float) mana());
    }

    /** A distributor fills only what it can see: up to the visible pool, never into the hidden reserve. */
    @Override
    public double receiveMana(double amount) {
        double took = Math.max(0, Math.min(amount, maxMana() - mana));
        setMana(mana + took);
        if (isDormant() && mana >= WAKE_AT) wake();
        return took;
    }

    /** Fed by hand: past the visible pool into the hidden reserve — and at its top, into a frenzy. */
    public void feed(double amount, @Nullable Player by) {
        setMana(mana + amount);
        if (!isTempered() && mana >= FRENZY_AT) frenzy(by);
        else if (isDormant() && mana >= WAKE_AT) wake();
    }

    /** Spends {@code amount} (times the frenzy multiplier when berserk); false, and spends nothing, if it lacks it. */
    boolean spend(double amount) {
        double cost = amount * (isBerserk() ? FRENZY_MULTIPLIER : 1);
        if (mana < cost) {
            setMana(0);
            runDry();
            return false;
        }
        setMana(mana - cost);
        return true;
    }

    /** Out of mana: a frenzy burns out, a sane colossus powers down. */
    private void runDry() {
        if (isBerserk()) burnOut();
        else powerDown();
    }

    void powerDown() {
        if (isDormant()) return;
        if (mind != null) mind.abortAll();
        entityData.set(DORMANT, true);
        setRunning(false);
        getNavigation().stop();
        play(Act.POWER_DOWN);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.NEUTRAL, 1.2f, 0.6f);
    }

    void wake() {
        if (!isDormant()) return;
        entityData.set(DORMANT, false);
        play(Act.POWER_UP);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 1.2f, 0.6f);
    }

    /** {@code execute} on a colossus: it does not die, its mana is wiped and it powers down. */
    public void drainAll() {
        setMana(0);
        runDry();
    }

    // ---- frenzy ---------------------------------------------------------------------------------

    /** Into a mana frenzy: riders thrown off, a blast of heat, and the scorched shell. Never once tempered. */
    public void frenzy(@Nullable Player cause) {
        if (isTempered() || isBerserk() || !(level() instanceof ServerLevel level)) return;
        mana = FRENZY_AT;
        entityData.set(MANA, (float) mana());
        if (mind != null) mind.abortAll();
        releaseAll();
        entityData.set(BERSERK, true);
        entityData.set(SCORCHED, true);
        entityData.set(DORMANT, false);
        setDriven(false);
        setTarget(null);
        Vec3 c = position().add(0, getBbHeight() * 0.55, 0);
        level.explode(this, c.x, c.y, c.z, 2.5f, false, Level.ExplosionInteraction.NONE);
        level.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 80, 1.2, 1.4, 1.2, 0.08);
        level.sendParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 24, 1.0, 1.0, 1.0, 0.1);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 40, 1.2, 1.4, 1.2, 0.05);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2f, 0.5f);
        Colossi.witnessFrenzy(this);
    }

    /** A frenzy ends — run dry or struck down: it drops a disorder crystal, powers down, and wakes tempered. */
    void burnOut() {
        if (!isBerserk() || !(level() instanceof ServerLevel level)) return;
        if (mind != null) mind.abortAll();
        releaseAll();
        entityData.set(BERSERK, false);
        entityData.set(TEMPERED, true);
        setMana(0);
        setHealth(Math.max(getHealth(), getMaxHealth() * 0.25f));
        level.addFreshEntity(new ItemEntity(level, getX(), getY() + 1.5, getZ(), new ItemStack(LogosMod.DISORDER_CRYSTAL.get())));
        level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 2, getZ(), 50, 1, 1.2, 1, 0.03);
        powerDown();
        Player master = Familiars.masterOf(this) == null ? null : level.getPlayerByUUID(Familiars.masterOf(this));
        if (master != null) master.sendSystemMessage(Component.literal("⛨ " + getName().getString() + " 的魔力暴走平息了，留下一块失序结晶").withStyle(ChatFormatting.GOLD));
    }

    // ---- body ------------------------------------------------------------------------------------

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount);
        // Like the ender dragon's body: a quarter of the blow, and at least a little of any blow.
        float taken = amount / 4 + Math.min(amount, 1);
        taken = shieldedDamage(source, taken);
        if (taken <= 0) return false;
        if (isBerserk() && taken >= getHealth()) {
            // A frenzy cannot be killed, only burnt out.
            burnOut();
            return true;
        }
        boolean hurt = super.hurtServer(level, source, taken);
        if (hurt && taken >= 15 && mind != null && mind.idleBody()) play(Act.HURT);
        return hurt;
    }

    /** What its devices leave of incoming damage. */
    float shieldedDamage(DamageSource source, float amount) {
        for (int i = 0; i < deviceSlots() && amount > 0; i++) {
            ItemStack device = devices.getItem(i);
            if (device.getItem() instanceof LifeformAugment a) amount = a.absorbDamage(this, device, source, amount);
        }
        return amount;
    }

    public int deviceSlots() {
        return isTempered() ? TEMPERED_DEVICES : DEVICES;
    }

    /** A shield that blocks its fist is battered aside for five seconds. */
    @Override
    public float getSecondsToDisableBlocking() {
        return 5f;
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected void tickDeath() {
        // Its death animation takes three seconds to settle.
        ++deathTime;
        if (deathTime >= 60 && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, (byte) 60);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && level() instanceof ServerLevel) Lifeforms.forget(this);
        super.remove(reason);
    }

    @Override
    protected void registerGoals() {
        mind = new ColossusMind(this);
        goalSelector.addGoal(1, mind);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            double moved = Math.sqrt(Mth.square(getX() - xo) + Mth.square(getZ() - zo));
            runPace = (float) Mth.lerp(0.3, runPace, Mth.clamp(moved / RUN_STRIDE, 0.5, 2.0));
        }
        if (level() instanceof ServerLevel level) {
            // Driven, vanilla leaves its movement to the rider's client and skips its AI on the server
            // altogether; its mind must still run the blows and the cannon its rider asks for.
            if (isClientAuthoritative() && mind != null && mind.canUse()) mind.tick();
            actTicks++;
            Act a = act();
            if (a.ticks > 0 && actTicks >= a.ticks && a != Act.POWER_DOWN && (mind == null || !mind.busy())) {
                // A finished one-shot gives the body back (the power-down holds into the dormant loop). While a
                // sequence runs it owns the body: it moves on to its next act itself, a tick or two after this
                // one ends, and dropping to no act in between would flash the idle pose for a moment.
                entityData.set(ACT, Act.NONE.ordinal());
            } else if (a == Act.POWER_DOWN && actTicks >= a.ticks) {
                entityData.set(ACT, Act.DORMANT.ordinal());
            }
            if (!isDormant() && tickCount % 20 == 0) {
                double rate = IDLE_UPKEEP * (mind == null ? 1 : mind.activityFactor());
                if (isBerserk()) rate = rate * FRENZY_MULTIPLIER + FRENZY_BURN;
                setMana(mana - rate);
                if (mana <= 0) runDry();
            }
            if (!isDormant()) {
                for (int i = 0; i < deviceSlots(); i++) {
                    ItemStack device = devices.getItem(i);
                    if (device.getItem() instanceof LifeformAugment aug) aug.tickAugment(this, device);
                }
            }
            if (isDormant() || act() == Act.CANNON_FIRE) {
                getNavigation().stop();
                setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
            }
            if (isBerserk() && tickCount % 4 == 0) {
                Vec3 c = position().add(0, getBbHeight() * (0.4 + random.nextDouble() * 0.5), 0);
                level.sendParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 2, 0.6, 0.4, 0.6, 0.01);
                if (random.nextInt(4) == 0) level.sendParticles(ParticleTypes.SMALL_FLAME, c.x, c.y, c.z, 1, 0.5, 0.3, 0.5, 0.01);
            } else if (isScorched() && tickCount % 30 == 0) {
                Vec3 c = position().add(0, getBbHeight() * 0.8, 0);
                level.sendParticles(ParticleTypes.SMOKE, c.x, c.y, c.z, 1, 0.4, 0.2, 0.4, 0.005);
            }
        }
    }

    // ---- what it is doing with its body ---------------------------------------------------------

    public Act act() {
        int i = entityData.get(ACT);
        return i >= 0 && i < Act.values().length ? Act.values()[i] : Act.NONE;
    }

    /** Ticks since the current act began (server). */
    public int actTicks() {
        return actTicks;
    }

    /** Plays {@code a} from its start, even if it is already playing. */
    void play(Act a) {
        entityData.set(ACT, a.ordinal());
        entityData.set(ACT_SEQ, entityData.get(ACT_SEQ) + 1);
        actTicks = 0;
    }

    // ---- riders and the hand ----------------------------------------------------------------------

    public @Nullable Hold holdOf(Entity e) {
        int id = e.getId();
        if (entityData.get(SEAT_LEFT) == id) return Hold.SEAT_LEFT;
        if (entityData.get(SEAT_RIGHT) == id) return Hold.SEAT_RIGHT;
        if (entityData.get(GRIP) == id) return Hold.GRIP;
        return null;
    }

    public @Nullable Entity held(Hold h) {
        int id = entityData.get(switch (h) {
            case SEAT_LEFT -> SEAT_LEFT;
            case SEAT_RIGHT -> SEAT_RIGHT;
            case GRIP -> GRIP;
        });
        return id < 0 ? null : level().getEntity(id);
    }

    /** Puts {@code e} at {@code h} (riding it), or clears {@code h} when {@code e} is null. */
    void setHold(Hold h, @Nullable Entity e) {
        EntityDataAccessor<Integer> key = switch (h) {
            case SEAT_LEFT -> SEAT_LEFT;
            case SEAT_RIGHT -> SEAT_RIGHT;
            case GRIP -> GRIP;
        };
        // An entity is in one place at a time.
        if (e != null) for (Hold other : Hold.values()) if (other != h && held(other) == e) setHold(other, null);
        entityData.set(key, e == null ? -1 : e.getId());
        if (e != null && e.getVehicle() != this) {
            e.stopRiding();
            e.startRiding(this, true, false);
        }
    }

    /** Lets go of {@code e} wherever it is, placing it at {@code at} if given. */
    void release(Entity e, @Nullable Vec3 at) {
        Hold h = holdOf(e);
        if (h != null) entityData.set(switch (h) {
            case SEAT_LEFT -> SEAT_LEFT;
            case SEAT_RIGHT -> SEAT_RIGHT;
            case GRIP -> GRIP;
        }, -1);
        Colossi.letGo(e);
        if (e.getVehicle() == this) e.stopRiding();
        if (at != null) e.teleportTo(at.x, at.y, at.z);
        Colossi.stopHolding(e);
    }

    /** Throws off everyone it carries or holds. */
    void releaseAll() {
        for (Entity p : getPassengers().toArray(Entity[]::new)) release(p, null);
        entityData.set(GRIP, -1);
        entityData.set(SEAT_LEFT, -1);
        entityData.set(SEAT_RIGHT, -1);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < 3;
    }

    /**
     * Only its driving master ever steers it. Vanilla would hand the controls to a mob passenger (a
     * skeleton riding a spider), which here would be whatever it holds in its hand — and that would
     * switch off its own mind mid-grab.
     */
    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return driver();
    }

    /** The master driving it from a seat, or null. */
    public @Nullable Player driver() {
        if (!isDriven()) return null;
        for (Hold h : new Hold[] {Hold.SEAT_LEFT, Hold.SEAT_RIGHT}) {
            if (held(h) instanceof Player p && p.getUUID().equals(Familiars.masterOf(this))) return p;
        }
        return null;
    }

    /** Client: the renderer reports where a bone was drawn this frame. */
    public void reportBone(String bone, Vec3 world) {
        bones.put(bone, world);
        bonesAt = tickCount;
    }

    /** Where a bone was last drawn, if recently (client), else null. */
    public @Nullable Vec3 drawnBone(String bone) {
        return level().isClientSide() && tickCount - bonesAt < 5 ? bones.get(bone) : null;
    }

    /** A point given in its own frame: {@code right} to its right, {@code up}, {@code forward} ahead. */
    public Vec3 local(double right, double up, double forward) {
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 r = new Vec3(-f.z, 0, f.x);
        return position().add(r.scale(right)).add(0, up, 0).add(f.scale(forward));
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        Hold h = holdOf(passenger);
        Vec3 at = ColossusMind.holdPoint(this, h == null ? Hold.GRIP : h, passenger);
        move.accept(passenger, at.x, at.y, at.z);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        return local(holdOf(passenger) == Hold.SEAT_RIGHT ? 1.6 : -1.6, 0, 1.2);
    }

    // ---- interaction ------------------------------------------------------------------------------

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        ItemStack held = player.getItemInHand(hand);
        boolean master = player.getUUID().equals(Familiars.masterOf(this));
        if (mind != null && act() == Act.FLOWER_HOLD) {
            mind.takeFlower(player);
            return InteractionResult.SUCCESS;
        }
        Lifeforms.ManaFuel fuel = held.isEmpty() ? null : held.typeHolder().getData(LogosMod.MANA_FUEL);
        if (fuel != null) {
            if (fuel.unstable() && !isTempered()) {
                if (!player.getAbilities().instabuild) held.shrink(1);
                frenzy(player);
                return InteractionResult.SUCCESS;
            }
            if (isBerserk()) return InteractionResult.PASS;
            feed(fuel.mana(), player);
            if (!player.getAbilities().instabuild) held.shrink(1);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1f, 0.7f);
            if (mind != null && !isBerserk()) mind.maybeOfferFlower(player, 0.3);
            player.sendOverlayMessage(status());
            return InteractionResult.SUCCESS;
        }
        if (held.is(LogosMod.MAINTENANCE_KIT.get())) {
            if (isBerserk()) return InteractionResult.PASS;
            heal(200);
            entityData.set(SCORCHED, false);
            if (!player.getAbilities().instabuild) held.shrink(1);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.8f, 0.9f);
            player.sendOverlayMessage(status());
            return InteractionResult.SUCCESS;
        }
        if (isBerserk() || isDormant()) {
            player.sendOverlayMessage(status());
            return InteractionResult.SUCCESS;
        }
        if (master && player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer sp) Colossi.openDevices(sp, this);
            return InteractionResult.SUCCESS;
        }
        if (held.isEmpty() && mind != null && mind.requestBoarding(player)) return InteractionResult.SUCCESS;
        player.sendOverlayMessage(status());
        return InteractionResult.SUCCESS;
    }

    public Component status() {
        String doing = isDormant() ? "停机" : isBerserk() ? "魔力暴走！" : mind == null ? "" : mind.doingZh();
        return Component.literal("⛨ " + getName().getString() + " · 魔力 " + Math.round(mana()) + "/" + Math.round(maxMana())
                + " · 生命 " + Math.round(getHealth()) + "/" + Math.round(getMaxHealth())
                + (isDriven() ? " · 驾驶中" : "") + (doing.isEmpty() ? "" : " · " + doing))
                .withStyle(isBerserk() ? ChatFormatting.RED : isDormant() ? ChatFormatting.DARK_GRAY : ChatFormatting.GOLD);
    }

    // ---- saving -----------------------------------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putDouble("mana", mana);
        out.putBoolean("berserk", isBerserk());
        out.putBoolean("scorched", isScorched());
        out.putBoolean("tempered", isTempered());
        out.putBoolean("dormant", isDormant());
        NonNullList<ItemStack> items = NonNullList.withSize(TEMPERED_DEVICES, ItemStack.EMPTY);
        for (int i = 0; i < TEMPERED_DEVICES; i++) items.set(i, devices.getItem(i));
        ContainerHelper.saveAllItems(out, items);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        entityData.set(BERSERK, in.getBooleanOr("berserk", false));
        entityData.set(SCORCHED, in.getBooleanOr("scorched", false));
        entityData.set(TEMPERED, in.getBooleanOr("tempered", false));
        entityData.set(DORMANT, in.getBooleanOr("dormant", false));
        setMana(in.getDoubleOr("mana", 0));
        if (isDormant()) entityData.set(ACT, Act.DORMANT.ordinal());
        NonNullList<ItemStack> items = NonNullList.withSize(TEMPERED_DEVICES, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in, items);
        for (int i = 0; i < TEMPERED_DEVICES; i++) devices.setItem(i, items.get(i));
    }

    // ---- animation --------------------------------------------------------------------------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<AlchemicalColossus>("body", 5, test -> {
            test.setControllerSpeed(1f);
            if (isDeadOrDying()) return test.setAndContinue(DEATH);
            Act a = act();
            int seq = entityData.get(ACT_SEQ);
            if (a != Act.NONE) {
                // A new act blends in from whatever pose the body is in. Never reset the controller for it:
                // that drops the pose to blend from, and GeckoLib then blends toward the model's rest pose
                // (arms hanging) for the first few frames of every act. Only the same act again (a second
                // punch), which GeckoLib sees as no change, is sent back to its first frame by hand.
                if (seq != lastSeq && a == lastAct) test.controller().setAnimationTime(0);
                lastSeq = seq;
                lastAct = a;
                lastLoco = null;
                return test.setAndContinue(a.raw);
            }
            lastSeq = seq;
            lastAct = Act.NONE;
            RawAnimation loco;
            if (test.isMoving()) {
                // Driven, it always runs.
                boolean running = isRunning() || isDriven();
                if (isBerserk()) loco = running ? RUN_CORRUPTED : WALK_CORRUPTED;
                else loco = running ? RUN : WALK;
                if (running) test.setControllerSpeed(runPace);
            } else {
                loco = lastLoco == RUN ? RUN_STOP : lastLoco == WALK ? WALK_STOP : lastLoco == RUN_STOP || lastLoco == WALK_STOP ? lastLoco : IDLE;
            }
            lastLoco = loco;
            return test.setAndContinue(loco);
        }));
        controllers.add(new AnimationController<AlchemicalColossus>("core", 5, test -> {
            if (isDeadOrDying() || isDormant() || act() == Act.DORMANT || act() == Act.POWER_DOWN) return PlayState.STOP;
            return test.setAndContinue(CORE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    /** Device slots: alchemical devices only, three, or five once tempered. */
    private final class DeviceSlots extends SimpleContainer {
        DeviceSlots() {
            super(TEMPERED_DEVICES);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return slot < deviceSlots() && stack.getItem() instanceof LifeformAugment;
        }

        @Override
        public boolean stillValid(Player player) {
            return isAlive() && player.distanceToSqr(AlchemicalColossus.this) < 100;
        }
    }

    /** A UI slot for device slot {@code index}. */
    public Slot deviceSlot(int index) {
        return new Slot(devices, index, 0, 0) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return devices.canPlaceItem(index, stack);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        };
    }
}
