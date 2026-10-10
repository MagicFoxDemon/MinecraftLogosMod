package dev.logos.runtime;

import java.util.EnumSet;
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
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Spell;
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
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 魔力生命体: the most basic alchemical life, mana that has gathered itself into a body. It has no
 * instincts of its own: it follows its master like any familiar, and everything else it does is the
 * spell pages in its core. The first slot holds its attack, an active spell it casts at whatever its
 * master is fighting; from level 2 each level adds a slot for a passive page (hurt / attack / kill,
 * read from its own point of view, see {@link Lifeforms}).
 * <p>
 * It lives on its own pool of mana: idling costs a little, every cast costs what the page injects,
 * and the leftovers of its own spells flow back into it. Run dry, it goes dormant — it falls and
 * lies scattered until fed again. Unlike a familiar it is kept with the world.
 */
public class ManaLifeform extends PathfinderMob implements GeoEntity, ManaVessel {
    public static final int MAX_LEVEL = 5;
    /** The cast animation lasts 2.4 s and lets go at 1.2 s. */
    public static final int CAST_TICKS = 48, RELEASE_AT = 24;
    /** The startup animation, during which the orbit stays still. */
    public static final int STARTUP_TICKS = 76;
    /** Base hitbox at level 1 (render scale 0.4); it grows with the model. */
    private static final EntityDimensions BASE = EntityDimensions.scalable(0.9f, 1.5f);
    private static final int CORE_SLOTS = MAX_LEVEL;

    private static final EntityDataAccessor<Integer> LEVEL = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> MANA = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DORMANT = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> CASTING = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.mana_lifeform.idle");
    private static final RawAnimation WAKE = RawAnimation.begin().thenPlay("animation.mana_lifeform.startup").thenLoop("animation.mana_lifeform.idle");
    private static final RawAnimation SHUTDOWN = RawAnimation.begin().thenPlayAndHold("animation.mana_lifeform.shutdown");
    private static final RawAnimation CAST = RawAnimation.begin().thenPlay("animation.mana_lifeform.cast");
    private static final RawAnimation ORBIT = RawAnimation.begin().thenLoop("animation.mana_lifeform.core_orbit");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** Its core: slot 0 the attack, then one passive slot per level past the first. */
    public final Core core = new Core();
    private final Map<Integer, Long> passiveCooldowns = new HashMap<>();
    /** Client only: when it last woke, so the startup plays once and the orbit waits for it. */
    private int wokeAt = Integer.MIN_VALUE;
    private boolean woke;

    public ManaLifeform(EntityType<? extends ManaLifeform> type, Level level) {
        super(type, level);
        this.moveControl = new FlyingMoveControl(this, 20, true);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, health(1))
                .add(Attributes.FLYING_SPEED, 0.5)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 32);
    }

    // ---- level rules ----------------------------------------------------------------------------

    /** The least mana it can be summoned with (and woken with): 50 × level^1.5. */
    public static double minMana(int level) {
        return 50 * Math.pow(level, 1.5);
    }

    /** Its pool: 1000 × level^1.5. */
    public static double maxMana(int level) {
        return 1000 * Math.pow(level, 1.5);
    }

    public static double health(int level) {
        return 30 + 20 * level;
    }

    /** Idling for a whole in-game day costs 15% of a full pool: a full one lasts about a week of idling. */
    public static double upkeepPerDay(int level) {
        return 0.15 * maxMana(level);
    }

    /** Core slots: the attack slot, plus one per level past the first. */
    public static int slots(int level) {
        return Mth.clamp(level, 1, MAX_LEVEL);
    }

    /** Model scale: 0.4 at level 1 up to 0.6 at level 5 (the model itself is nearly four blocks tall). */
    public static float renderScale(int level) {
        return 0.35f + 0.05f * Mth.clamp(level, 1, MAX_LEVEL);
    }

    // ---- state ----------------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(LEVEL, 1);
        b.define(MANA, 0f);
        b.define(DORMANT, false);
        b.define(CASTING, false);
    }

    public int lifeLevel() {
        return entityData.get(LEVEL);
    }

    /** Sets its level and the health that comes with it, at full health. */
    public void setLifeLevel(int level) {
        int lv = Mth.clamp(level, 1, MAX_LEVEL);
        entityData.set(LEVEL, lv);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health(lv));
        setHealth(getMaxHealth());
        refreshDimensions();
    }

    @Override
    public double mana() {
        return entityData.get(MANA);
    }

    @Override
    public double maxMana() {
        return maxMana(lifeLevel());
    }

    public void setMana(double mana) {
        entityData.set(MANA, (float) Mth.clamp(mana, 0, maxMana()));
    }

    @Override
    public double receiveMana(double amount) {
        double took = Math.max(0, Math.min(amount, maxMana() - mana()));
        setMana(mana() + took);
        if (isDormant() && mana() >= minMana(lifeLevel())) wake();
        return took;
    }

    public boolean isDormant() {
        return entityData.get(DORMANT);
    }

    public boolean isCasting() {
        return entityData.get(CASTING);
    }

    /** Out of mana: it falls and lies still. */
    void goDormant() {
        if (isDormant()) return;
        entityData.set(DORMANT, true);
        entityData.set(CASTING, false);
        // A triggered cast keeps the controller until it is stopped; the shutdown must take over.
        stopTriggeredAnim("main", "cast");
        setNoGravity(false);
        getNavigation().stop();
        setTarget(null);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.NEUTRAL, 0.8f, 1.6f);
    }

    /** Fed back past its minimum: it gathers itself and rises. */
    void wake() {
        if (!isDormant()) return;
        entityData.set(DORMANT, false);
        setNoGravity(true);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.NEUTRAL, 0.8f, 1.6f);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (LEVEL.equals(key)) refreshDimensions();
        if (DORMANT.equals(key) && level().isClientSide()) {
            if (!isDormant() && tickCount > 1) {
                woke = true;
                wokeAt = tickCount;
            } else if (isDormant()) {
                woke = false;
            }
        }
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return BASE.scale(renderScale(lifeLevel()) / renderScale(1));
    }

    // ---- living ---------------------------------------------------------------------------------

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation nav = new FlyingPathNavigation(this, level);
        nav.setCanOpenDoors(false);
        nav.setCanFloat(true);
        return nav;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(2, new CastAtTarget(this));
    }

    @Override
    public boolean causeFallDamage(double distance, float multiplier, DamageSource source) {
        return false; // mana does not break when it falls
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel) {
            if (tickCount % 20 == 0 && !isDormant()) {
                // Idling: a day's upkeep spread over its 1200 seconds.
                setMana(mana() - upkeepPerDay(lifeLevel()) / 1200);
                if (mana() <= 0) goDormant();
            }
            if (isDormant()) getNavigation().stop();
        } else if (!isDormant() && tickCount % 3 == 0) {
            // A loose glitter of mana about its core.
            double r = getBbWidth() * 0.7;
            level().addParticle(ParticleTypes.END_ROD, getX() + (random.nextDouble() - 0.5) * r * 2, getY() + getBbHeight() * (0.3 + random.nextDouble() * 0.5),
                    getZ() + (random.nextDouble() - 0.5) * r * 2, 0, 0.01, 0);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        // Killed or dismissed it no longer counts towards its master's limit; unloaded it still does.
        if (reason.shouldDestroy() && level() instanceof ServerLevel) Lifeforms.forget(this);
        super.remove(reason);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("life_level", lifeLevel());
        out.putDouble("mana", mana());
        out.putBoolean("dormant", isDormant());
        NonNullList<ItemStack> items = NonNullList.withSize(CORE_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < CORE_SLOTS; i++) items.set(i, core.getItem(i));
        ContainerHelper.saveAllItems(out, items);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        entityData.set(LEVEL, Mth.clamp(in.getIntOr("life_level", 1), 1, MAX_LEVEL));
        entityData.set(MANA, (float) in.getDoubleOr("mana", 0));
        boolean dormant = in.getBooleanOr("dormant", false);
        entityData.set(DORMANT, dormant);
        setNoGravity(!dormant);
        NonNullList<ItemStack> items = NonNullList.withSize(CORE_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in, items);
        for (int i = 0; i < CORE_SLOTS; i++) core.setItem(i, items.get(i));
        refreshDimensions();
    }

    // ---- casting ------------------------------------------------------------------------------

    /** The spell on core slot {@code slot}, if it holds a written page within its level. */
    public @Nullable Spell spellIn(int slot) {
        if (slot >= slots(lifeLevel())) return null;
        ItemStack page = core.getItem(slot);
        return SpellPages.isInscribed(page) ? SpellPages.spell(page) : null;
    }

    /**
     * Casts the page in {@code slot} at {@code target}, paid from its own pool: what the page injects
     * (its capacity at most), or all it has left if that is less — so a last cast empties it.
     * Its master's stability bounds what it can speak.
     */
    public boolean castSlot(int slot, @Nullable Entity target) {
        if (isDormant() || !(level() instanceof ServerLevel level)) return false;
        Spell spell = spellIn(slot);
        if (spell == null) return false;
        Player master = Familiars.masterOf(this) == null ? null : level.getPlayerByUUID(Familiars.masterOf(this));
        int stability = master != null ? PlayerMagic.of(master).stability : Limits.DEFAULT_STABILITY;
        if (spell.stability() > stability) return false;
        PageTier tier = SpellPages.tier(core.getItem(slot));
        if (tier == null) tier = PageTier.BASIC;
        double pay = Math.min(Math.min(spell.mana(), tier.capacity()), mana());
        if (pay <= 0) return false;
        setMana(mana() - pay);
        Casting.castAs(this, spell, pay, tier.recovery(), target, null);
        if (mana() <= 0) goDormant();
        return true;
    }

    /** The passive pages bound to {@code trigger}, each at most once a second. */
    public void firePassives(String trigger, @Nullable Entity target) {
        long now = level().getGameTime();
        for (int slot = 1; slot < slots(lifeLevel()); slot++) {
            Spell s = spellIn(slot);
            if (s == null || !s.isPassive() || !s.trigger().equals(trigger)) continue;
            if (passiveCooldowns.getOrDefault(slot, 0L) > now) continue;
            passiveCooldowns.put(slot, now + Limits.PASSIVE_COOLDOWN_TICKS);
            castSlot(slot, target);
        }
    }

    /** Turns body, head and gaze straight at {@code target}, so a spell cast now points there. */
    void face(Entity target) {
        Vec3 d = Targeting.center(target).subtract(getEyePosition());
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
        setYRot(yaw);
        setXRot(pitch);
        yHeadRot = yBodyRot = yaw;
    }

    // ---- interaction ----------------------------------------------------------------------------

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        Lifeforms.ManaFuel fuel = held.isEmpty() ? null : held.typeHolder().getData(LogosMod.MANA_FUEL);
        if (fuel != null) {
            if (level().isClientSide()) return InteractionResult.SUCCESS;
            if (mana() >= maxMana()) {
                player.sendOverlayMessage(Component.literal("魔力已满").withStyle(ChatFormatting.GRAY));
                return InteractionResult.CONSUME;
            }
            receiveMana(fuel.mana());
            if (!player.getAbilities().instabuild) held.shrink(1);
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1f, 1.2f + random.nextFloat() * 0.4f);
            ((ServerLevel) level()).sendParticles(ParticleTypes.END_ROD, getX(), getY() + getBbHeight() / 2, getZ(), 12, 0.3, 0.3, 0.3, 0.05);
            player.sendOverlayMessage(status());
            return InteractionResult.SUCCESS;
        }
        boolean master = player.getUUID().equals(Familiars.masterOf(this));
        if (master && player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer sp) Lifeforms.openCore(sp, this);
            return InteractionResult.SUCCESS;
        }
        if (held.isEmpty()) {
            if (!level().isClientSide()) player.sendOverlayMessage(status());
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    public Component status() {
        int lv = lifeLevel();
        return Component.literal("✦ " + getName().getString() + " · " + lv + " 级 · 魔力 " + Math.round(mana()) + "/" + Math.round(maxMana())
                + " · 生命 " + Math.round(getHealth()) + "/" + Math.round(getMaxHealth()) + (isDormant() ? " · 沉寂" : ""))
                .withStyle(isDormant() ? ChatFormatting.DARK_GRAY : ChatFormatting.AQUA);
    }

    // ---- animation ------------------------------------------------------------------------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<ManaLifeform>("main", 4, test -> {
            if (isDormant()) return test.setAndContinue(SHUTDOWN);
            return test.setAndContinue(woke ? WAKE : IDLE);
        }).triggerableAnim("cast", CAST));
        // The orbit rests while it lies dormant, while it is waking, and while a cast holds its parts.
        controllers.add(new AnimationController<ManaLifeform>("orbit", 4, test -> {
            if (isDormant() || isCasting() || (woke && tickCount - wokeAt < STARTUP_TICKS)) return PlayState.STOP;
            return test.setAndContinue(ORBIT);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ---- the core -----------------------------------------------------------------------------

    /** Core slots: slot 0 takes an active (cast) page, the others passive pages; slots past its level take nothing. */
    public final class Core extends SimpleContainer {
        Core() {
            super(CORE_SLOTS);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return accepts(slot, stack);
        }

        public boolean accepts(int slot, ItemStack stack) {
            if (slot >= slots(lifeLevel()) || !SpellPages.isInscribed(stack)) return false;
            Spell s = SpellPages.spell(stack);
            return s != null && (slot == 0) != s.isPassive();
        }

        @Override
        public boolean stillValid(Player player) {
            return isAlive() && player.distanceToSqr(ManaLifeform.this) < 64;
        }

        /** A UI slot for core slot {@code index}. */
        public Slot slot(int index) {
            return new Slot(this, index, 0, 0) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return accepts(index, stack);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            };
        }
    }

    /**
     * Its fighting: when its master is fighting something (or it was ordered or hunts), it keeps a
     * casting distance, turns to the foe and casts its attack page — the cast animation gathers,
     * lets go at 1.2 s, and settles. With no attack page it only watches.
     */
    static final class CastAtTarget extends Goal {
        private final ManaLifeform mob;
        private int castTimer, cooldown;
        private @Nullable LivingEntity casting;

        CastAtTarget(ManaLifeform mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private @Nullable LivingEntity target() {
            LivingEntity t = Familiars.orderedTarget(mob);
            return t != null && t.isAlive() && t.level() == mob.level() ? t : null;
        }

        @Override
        public boolean canUse() {
            return !mob.isDormant() && target() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return !mob.isDormant() && (target() != null || castTimer > 0);
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = target();
            if (cooldown > 0) cooldown--;
            if (t != null) {
                mob.getLookControl().setLookAt(t, 30, 30);
                double d = mob.distanceTo(t);
                if (d > 10 || !mob.hasLineOfSight(t)) mob.getNavigation().moveTo(t, 1.0);
                else mob.getNavigation().stop();
            }
            if (castTimer > 0) {
                castTimer--;
                if (castTimer == CAST_TICKS - RELEASE_AT && casting != null && casting.isAlive()) {
                    mob.face(casting);
                    mob.castSlot(0, casting);
                }
                if (castTimer == 0) {
                    mob.entityData.set(CASTING, false);
                    mob.stopTriggeredAnim("main", "cast"); // hand the controller back to idle
                    casting = null;
                    cooldown = 10;
                }
                return;
            }
            if (t != null && cooldown == 0 && mob.spellIn(0) != null && mob.distanceTo(t) <= 16 && mob.hasLineOfSight(t)) {
                casting = t;
                castTimer = CAST_TICKS;
                mob.entityData.set(CASTING, true);
                mob.triggerAnim("main", "cast");
                mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1f, 1.3f);
            }
        }

        @Override
        public void stop() {
            if (castTimer > 0) mob.stopTriggeredAnim("main", "cast");
            castTimer = 0;
            casting = null;
            mob.entityData.set(CASTING, false);
            mob.getNavigation().stop();
        }
    }
}
