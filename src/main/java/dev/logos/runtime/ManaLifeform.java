package dev.logos.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import dev.logos.lang.Intent;
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
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
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
 * 魔力生命体: the most basic alchemical life, mana that has gathered itself into a body. What it
 * does is decided by its mind ({@link LifeformMind}, a utility AI) from what its master orders and
 * from the pages in its core, read by their intent:
 * <ul>
 * <li>active pages (one slot, a second from level 3): the cheaper is its attack, and when it has
 * mana to spare it looses the dearer one with it; heal and ward pages it uses to look after its master;</li>
 * <li>passive pages (one slot per level past the first): hurt / attack / kill, read from its own side;</li>
 * <li>alchemical devices (one slot from level 2, a second at level 5), see {@link LifeformAugment}.</li>
 * </ul>
 * Level 4 quickens it: it casts, moves and recovers faster. A cast charges before it lets go, at a
 * speed set by its level (see {@link #channelFlow()}).
 * <p>
 * It lives on its own pool of mana: idling costs a little, every cast costs what the page injects,
 * and the leftovers of its own spells flow back into it. Run dry, it goes dormant — it falls and
 * lies scattered until fed again. Unlike a familiar it is kept with the world.
 */
public class ManaLifeform extends PathfinderMob implements GeoEntity, ManaVessel, KeptFamiliar, AimsAtCastTarget {
    public static final int MAX_LEVEL = 5;
    /** The cast animation lasts 2.4 s and lets go at 1.2 s; quickened (level 4+) it plays at 1.5×. */
    public static final int CAST_TICKS = 48, RELEASE_AT = 24;
    public static final float QUICK = 1.5f;
    /** The startup animation, during which the orbit stays still. */
    public static final int STARTUP_TICKS = 76;
    /** A second active page joins the cast only if this share of the pool is still left after both. */
    public static final double COMBO_RESERVE = 0.3;
    /** Hitbox at level 1, about an allay's; it grows with the model. */
    private static final EntityDimensions BASE = EntityDimensions.scalable(0.4f, 0.5f);

    /** Core layout: two active slots, four passive, two devices. Which are open depends on its level. */
    public static final int ACTIVE_FIRST = 0, ACTIVES = 2, PASSIVE_FIRST = 2, PASSIVES = 4, DEVICE_FIRST = 6, DEVICES = 2;
    public static final int CORE_SLOTS = ACTIVES + PASSIVES + DEVICES;
    /** Save layout version: before 2, slot 0 was the attack and 1–4 the passives. */
    private static final int CORE_LAYOUT = 2;

    public enum SlotKind { ACTIVE, PASSIVE, DEVICE }

    /**
     * How it weighs its choices, set by its master with {@code (stance :role …)}. AUTO is the
     * balanced default for a master who never touches it.
     */
    public enum Role {
        AUTO("auto", "自然", 1, 1, 1),
        STRIKER("striker", "进攻", 1.3, 0.7, 0.9),
        GUARD("guard", "护卫", 0.9, 1.0, 1.4),
        SUPPORT("support", "辅助", 0.8, 1.4, 1.1);

        public final String word, zh;
        public final double engage, support, protect;

        Role(String word, String zh, double engage, double support, double protect) {
            this.word = word;
            this.zh = zh;
            this.engage = engage;
            this.support = support;
            this.protect = protect;
        }

        public static @Nullable Role parse(String s) {
            for (Role r : values()) if (r.word.equals(s) || r.zh.equals(s)) return r;
            return null;
        }
    }

    private static final EntityDataAccessor<Integer> LEVEL = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> MANA = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DORMANT = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> CASTING = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.BOOLEAN);
    /** Mid-cast, holding the cast pose while mana flows in. */
    private static final EntityDataAccessor<Boolean> CHARGING = SynchedEntityData.defineId(ManaLifeform.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.mana_lifeform.idle");
    private static final RawAnimation WAKE = RawAnimation.begin().thenPlay("animation.mana_lifeform.startup").thenLoop("animation.mana_lifeform.idle");
    private static final RawAnimation SHUTDOWN = RawAnimation.begin().thenPlayAndHold("animation.mana_lifeform.shutdown");
    private static final RawAnimation CAST = RawAnimation.begin().thenPlay("animation.mana_lifeform.cast");
    private static final RawAnimation ORBIT = RawAnimation.begin().thenLoop("animation.mana_lifeform.core_orbit");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    public final Core core = new Core();
    private final Map<Integer, Long> passiveCooldowns = new HashMap<>();
    /** Set in registerGoals, which runs inside the Mob constructor: no initializer, or it would be wiped. */
    private LifeformMind mind;
    private Role role = Role.AUTO;
    /** Its temperament, rolled at birth (0.7–1.3 each): how readily it fights, wanders, keeps close. */
    private float bravery = 1, curiosity = 1, loyalty = 1;
    private boolean temperamentRolled;
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
                .add(Attributes.FLYING_SPEED, flyingSpeed(1))
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

    static double flyingSpeed(int level) {
        return level >= 4 ? 0.45 : 0.35;
    }

    /** Idling for a whole in-game day costs 15% of a full pool: a full one lasts about a week of idling. */
    public static double upkeepPerDay(int level) {
        return 0.15 * maxMana(level);
    }

    public static int activeSlots(int level) {
        return level >= 3 ? 2 : 1;
    }

    public static int passiveSlots(int level) {
        return Mth.clamp(level - 1, 0, PASSIVES);
    }

    public static int deviceSlots(int level) {
        return level >= 5 ? 2 : level >= 2 ? 1 : 0;
    }

    /** Level 4 and up: faster casting, movement and recovery. */
    public static boolean quick(int level) {
        return level >= 4;
    }

    public static SlotKind kind(int slot) {
        return slot < PASSIVE_FIRST ? SlotKind.ACTIVE : slot < DEVICE_FIRST ? SlotKind.PASSIVE : SlotKind.DEVICE;
    }

    /** Whether core slot {@code slot} is open at {@code level}. */
    public static boolean open(int slot, int level) {
        return switch (kind(slot)) {
            case ACTIVE -> slot - ACTIVE_FIRST < activeSlots(level);
            case PASSIVE -> slot - PASSIVE_FIRST < passiveSlots(level);
            case DEVICE -> slot - DEVICE_FIRST < deviceSlots(level);
        };
    }

    /**
     * Model scale. The model is built nearly four blocks tall for its detail; the creature itself is
     * small, like an allay: half a block tall at level 1 (0.13), growing to about 0.8 blocks at level 5 (0.21).
     */
    public static float renderScale(int level) {
        return 0.11f + 0.02f * Mth.clamp(level, 1, MAX_LEVEL);
    }

    // ---- state ----------------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(LEVEL, 1);
        b.define(MANA, 0f);
        b.define(DORMANT, false);
        b.define(CASTING, false);
        b.define(CHARGING, false);
    }

    public int lifeLevel() {
        return entityData.get(LEVEL);
    }

    /** Sets its level and what comes with it (health, at full; speed), and rolls its temperament if it has none yet. */
    public void setLifeLevel(int level) {
        int lv = Mth.clamp(level, 1, MAX_LEVEL);
        entityData.set(LEVEL, lv);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health(lv));
        getAttribute(Attributes.FLYING_SPEED).setBaseValue(flyingSpeed(lv));
        setHealth(getMaxHealth());
        refreshDimensions();
        if (!temperamentRolled) {
            bravery = 0.7f + random.nextFloat() * 0.6f;
            curiosity = 0.7f + random.nextFloat() * 0.6f;
            loyalty = 0.7f + random.nextFloat() * 0.6f;
            temperamentRolled = true;
        }
    }

    public boolean quick() {
        return quick(lifeLevel());
    }

    public int castTicks() {
        return quick() ? Math.round(CAST_TICKS / QUICK) : CAST_TICKS;
    }

    public int releaseAt() {
        return quick() ? Math.round(RELEASE_AT / QUICK) : RELEASE_AT;
    }

    @Override
    public double mana() {
        return entityData.get(MANA);
    }

    @Override
    public double maxMana() {
        return maxMana(lifeLevel());
    }

    public double manaFraction() {
        return mana() / maxMana();
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

    /** Plays (or ends) the cast animation; the orbit rests while it plays. */
    public void setCasting(boolean casting) {
        entityData.set(CASTING, casting);
        if (!casting) entityData.set(CHARGING, false);
    }

    public boolean isCharging() {
        return entityData.get(CHARGING);
    }

    /** Holds the cast animation where it stands (just short of letting go) while the cast charges. */
    public void setCharging(boolean charging) {
        entityData.set(CHARGING, charging);
    }

    /** Charging speed by level, the same as wand tiers 1 to 5: mana per second at first, and its growth per second. */
    private static final double[] FLOW = {40, 55, 70, 90, 110}, SURGE = {20, 60, 130, 184, 282};

    public double channelFlow() {
        return FLOW[Mth.clamp(lifeLevel(), 1, FLOW.length) - 1];
    }

    public double channelSurge() {
        return SURGE[Mth.clamp(lifeLevel(), 1, SURGE.length) - 1];
    }

    public Role role() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public float bravery() {
        return bravery;
    }

    public float curiosity() {
        return curiosity;
    }

    public float loyalty() {
        return loyalty;
    }

    public @Nullable LifeformMind mind() {
        return mind;
    }

    /** Out of mana: it falls and lies still. */
    void goDormant() {
        if (isDormant()) return;
        entityData.set(DORMANT, true);
        entityData.set(CASTING, false);
        entityData.set(CHARGING, false);
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
        mind = new LifeformMind(this);
        goalSelector.addGoal(1, mind);
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
            if (!isDormant()) {
                for (int i = 0; i < DEVICES; i++) {
                    ItemStack device = core.getItem(DEVICE_FIRST + i);
                    if (open(DEVICE_FIRST + i, lifeLevel()) && device.getItem() instanceof LifeformAugment a) a.tickAugment(this, device);
                }
            }
            if (tickCount % 20 == 0 && !isDormant()) {
                // Idling: a day's upkeep spread over its 1200 seconds.
                setMana(mana() - upkeepPerDay(lifeLevel()) / 1200);
                if (mana() <= 0) goDormant();
            }
            if (isDormant()) {
                // Lying still: no flight left in it. Its flying move control turns gravity back off
                // whenever it carries out a move it was still given, so drop that move and keep gravity on.
                getNavigation().stop();
                getMoveControl().setWait();
                if (isNoGravity()) setNoGravity(false);
            }
        } else if (!isDormant() && tickCount % 5 == 0) {
            // Small glyphs drifting into its core (an enchanting particle flies from pos + velocity to pos).
            Vec3 c = position().add(0, getBbHeight() * 0.5, 0);
            double r = getBbWidth() * 1.5;
            level().addParticle(ParticleTypes.ENCHANT, c.x, c.y, c.z,
                    (random.nextDouble() - 0.5) * r, random.nextDouble() * r * 0.5, (random.nextDouble() - 0.5) * r);
        }
    }

    /** What its devices leave of incoming damage. */
    float shieldedDamage(DamageSource source, float amount) {
        for (int i = 0; i < DEVICES && amount > 0; i++) {
            ItemStack device = core.getItem(DEVICE_FIRST + i);
            if (open(DEVICE_FIRST + i, lifeLevel()) && device.getItem() instanceof LifeformAugment a) amount = a.absorbDamage(this, device, source, amount);
        }
        return amount;
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
        out.putString("role", role.word);
        out.putFloat("bravery", bravery);
        out.putFloat("curiosity", curiosity);
        out.putFloat("loyalty", loyalty);
        out.putInt("core_layout", CORE_LAYOUT);
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
        Role r = Role.parse(in.getStringOr("role", "auto"));
        role = r == null ? Role.AUTO : r;
        bravery = in.getFloatOr("bravery", 1);
        curiosity = in.getFloatOr("curiosity", 1);
        loyalty = in.getFloatOr("loyalty", 1);
        temperamentRolled = true;
        NonNullList<ItemStack> items = NonNullList.withSize(CORE_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in, items);
        if (in.getIntOr("core_layout", 1) < 2) {
            // The first layout: slot 0 the attack, 1–4 the passives.
            NonNullList<ItemStack> moved = NonNullList.withSize(CORE_SLOTS, ItemStack.EMPTY);
            moved.set(0, items.get(0));
            for (int i = 1; i <= 4; i++) moved.set(PASSIVE_FIRST + i - 1, items.get(i));
            items = moved;
        }
        for (int i = 0; i < CORE_SLOTS; i++) core.setItem(i, items.get(i));
        refreshDimensions();
    }

    // ---- casting ------------------------------------------------------------------------------

    /** The spell on core slot {@code slot}, if it is open and holds a written page. */
    public @Nullable Spell spellIn(int slot) {
        if (slot < 0 || slot >= CORE_SLOTS || kind(slot) == SlotKind.DEVICE || !open(slot, lifeLevel())) return null;
        ItemStack page = core.getItem(slot);
        return SpellPages.isInscribed(page) ? SpellPages.spell(page) : null;
    }

    /** What casting slot {@code slot} would take from its pool: the page's capacity at most. */
    public double injection(int slot) {
        Spell s = spellIn(slot);
        if (s == null) return 0;
        PageTier tier = SpellPages.tier(core.getItem(slot));
        return Math.min(s.mana(), (tier == null ? PageTier.BASIC : tier).capacity());
    }

    /** Its open active slots holding a page filed under one of {@code intents}, cheapest first. */
    public List<Integer> activeWith(Intent... intents) {
        List<Integer> out = new ArrayList<>();
        for (int slot = ACTIVE_FIRST; slot < ACTIVE_FIRST + ACTIVES; slot++) {
            Spell s = spellIn(slot);
            if (s == null || s.isPassive()) continue;
            for (Intent i : intents) {
                if (s.profile().primary() == i) {
                    out.add(slot);
                    break;
                }
            }
        }
        out.sort((a, b) -> Double.compare(injection(a), injection(b)));
        return out;
    }

    /** Its attack pages: active pages meant for enemies (or of no particular intent), cheapest first. */
    public List<Integer> attackSlots() {
        return activeWith(Intent.STRIKE, Intent.AREA, Intent.CONTROL, Intent.UTILITY);
    }

    /** Roughly how far its attack reaches: it keeps about this far from its foe. */
    public double attackRange() {
        double r = 0;
        for (int slot : attackSlots()) r = Math.max(r, spellIn(slot).profile().range());
        return r <= 0 ? 8 : Mth.clamp(r, 4, 10);
    }

    /** Whether, after casting both, it would still hold {@value #COMBO_RESERVE} of its pool. */
    public boolean comboAffordable(int basic, int strong) {
        return mana() - injection(basic) - injection(strong) >= COMBO_RESERVE * maxMana();
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
        double pay = Math.min(injection(slot), mana());
        if (pay <= 0) return false;
        setMana(mana() - pay);
        Casting.castAs(this, spell, pay, tier.recovery(), target, null);
        if (mana() <= 0) goDormant();
        return true;
    }

    /** Whether its master's stability bears the page in {@code slot}. */
    public boolean canSpeak(int slot) {
        Spell spell = spellIn(slot);
        if (spell == null || !(level() instanceof ServerLevel level)) return false;
        Player master = Familiars.masterOf(this) == null ? null : level.getPlayerByUUID(Familiars.masterOf(this));
        int stability = master != null ? PlayerMagic.of(master).stability : Limits.DEFAULT_STABILITY;
        return spell.stability() <= stability;
    }

    /** Casts the page in {@code slot} at {@code target} with {@code paid}, already drawn from its pool by a charge. */
    public void castPaid(int slot, @Nullable Entity target, double paid) {
        Spell spell = spellIn(slot);
        if (spell == null || paid <= 0) return;
        PageTier tier = SpellPages.tier(core.getItem(slot));
        Casting.castAs(this, spell, paid, (tier == null ? PageTier.BASIC : tier).recovery(), target, null, true);
    }

    /** The passive pages bound to {@code trigger}, each at most once a second. */
    public void firePassives(String trigger, @Nullable Entity target) {
        long now = level().getGameTime();
        for (int slot = PASSIVE_FIRST; slot < PASSIVE_FIRST + PASSIVES; slot++) {
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
            LifeformMind.emote(this, LifeformMind.Emote.HAPPY);
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
        String doing = isDormant() ? "沉寂" : mind == null ? "" : mind.doingZh();
        return Component.literal("✦ " + getName().getString() + " · " + lv + " 级 · 魔力 " + Math.round(mana()) + "/" + Math.round(maxMana())
                + " · 生命 " + Math.round(getHealth()) + "/" + Math.round(getMaxHealth()) + " · " + role.zh + (doing.isEmpty() ? "" : " · " + doing))
                .withStyle(isDormant() ? ChatFormatting.DARK_GRAY : ChatFormatting.AQUA);
    }

    /** Its temperament in words, for the status and the debug view. */
    public String temperamentZh() {
        return "勇敢 " + Math.round(bravery * 100) + "% · 好奇 " + Math.round(curiosity * 100) + "% · 黏人 " + Math.round(loyalty * 100) + "%";
    }

    // ---- animation ------------------------------------------------------------------------------

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // The cast is a state, not a triggered animation: it follows the synced flag, and quickened it plays faster.
        controllers.add(new AnimationController<ManaLifeform>("main", 4, test -> {
            if (isDormant()) {
                test.setControllerSpeed(1f);
                return test.setAndContinue(SHUTDOWN);
            }
            if (isCasting()) {
                test.setControllerSpeed(isCharging() ? 0f : quick() ? QUICK : 1f);
                return test.setAndContinue(CAST);
            }
            test.setControllerSpeed(1f);
            return test.setAndContinue(woke ? WAKE : IDLE);
        }));
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

    /** Core slots: active pages, passive pages, alchemical devices; closed slots take nothing. */
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
            if (slot < 0 || slot >= CORE_SLOTS || !open(slot, lifeLevel())) return false;
            if (kind(slot) == SlotKind.DEVICE) return stack.getItem() instanceof LifeformAugment;
            if (!SpellPages.isInscribed(stack)) return false;
            Spell s = SpellPages.spell(stack);
            return s != null && (kind(slot) == SlotKind.PASSIVE) == s.isPassive();
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
}
