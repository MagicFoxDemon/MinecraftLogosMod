package dev.logos.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 化身: a player becomes a creature for a while. Unlike possession ({@link Possession}) nothing is
 * borrowed: it is the player's own body that changes, so their inventory, their death and their
 * saving stay theirs, and leaving the game does not end it. Death in that shape is their death.
 * <p>
 * The form is read off the creature in the way every creature, another mod's too, describes
 * itself: its size and eye height from its entity type, its body (health, blow, armour, step,
 * jump, fall, speed — see {@link #BODY}) from its default attributes, fire immunity from its type,
 * breathing water from the creature itself, flight from flying pathfinding, a flying animal, no
 * gravity, or the config list {@code incarnationFliers}. Health keeps its share of the maximum both
 * ways. Only the creatures in the config list {@code incarnationHands} keep the use of hands; any
 * other strikes with its own body and cannot use, place or mine.
 * <p>
 * It ends when the time is up, when 万咒皆终 reaches it, when 解除化身 undoes it, or when the player
 * presses the revert key (V by default; {@link #end}).
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Incarnation {
    public static final double MAX_SECONDS = 600;
    private static final Identifier MODIFIER = Identifier.fromNamespaceAndPath(LogosMod.MODID, "incarnation");

    /**
     * What a body is made of, as attributes a player has too. Only these are taken from the creature:
     * the rest of its attributes serve its AI, or (like the locator bar's ranges) mean something else for a player.
     */
    private static final List<Holder<Attribute>> BODY = List.of(
            Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE, Attributes.ATTACK_KNOCKBACK, Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS,
            Attributes.KNOCKBACK_RESISTANCE, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, Attributes.MOVEMENT_SPEED, Attributes.STEP_HEIGHT,
            Attributes.JUMP_STRENGTH, Attributes.GRAVITY, Attributes.SAFE_FALL_DISTANCE, Attributes.FALL_DAMAGE_MULTIPLIER,
            Attributes.WATER_MOVEMENT_EFFICIENCY, Attributes.OXYGEN_BONUS, Attributes.BURNING_TIME);

    /** The creature a player is, since and until when (game time; {@code since} is {@link #UNKNOWN} if it was saved before it was kept), and what it can do that is not an attribute. */
    public record Form(Identifier type, long since, long until, boolean flies, boolean gills, boolean hands) {
        /** The start of a form saved before its start was kept. */
        public static final long UNKNOWN = Long.MIN_VALUE;
        public static final Form NONE = new Form(Identifier.fromNamespaceAndPath("minecraft", "player"), 0, 0, false, false, true);
        public static final MapCodec<Form> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.fieldOf("type").forGetter(Form::type),
                Codec.LONG.optionalFieldOf("since", UNKNOWN).forGetter(Form::since),
                Codec.LONG.fieldOf("until").forGetter(Form::until),
                Codec.BOOL.fieldOf("flies").forGetter(Form::flies),
                Codec.BOOL.fieldOf("gills").forGetter(Form::gills),
                Codec.BOOL.fieldOf("hands").forGetter(Form::hands)).apply(i, Form::new));
        public static final StreamCodec<ByteBuf, Form> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Form::type, ByteBufCodecs.VAR_LONG, Form::since, ByteBufCodecs.VAR_LONG, Form::until,
                ByteBufCodecs.BOOL, Form::flies, ByteBufCodecs.BOOL, Form::gills, ByteBufCodecs.BOOL, Form::hands, Form::new);
    }

    /**
     * The form each player had last tick, to resize them when it changes: one map per side, since a
     * player and their client copy are equal entities (same id) in a single-player game.
     */
    private static final Map<Player, @Nullable Identifier> SIZED_SERVER = Collections.synchronizedMap(new WeakHashMap<>()),
            SIZED_CLIENT = Collections.synchronizedMap(new WeakHashMap<>());

    private Incarnation() {}

    /** The form {@code e} has taken, on either side; null if it is not an incarnated player. */
    public static @Nullable Form form(@Nullable Entity e) {
        if (!(e instanceof Player p) || !p.hasData(LogosMod.FORM)) return null;
        Form f = p.getData(LogosMod.FORM);
        return f.until() > 0 ? f : null;
    }

    public static boolean active(@Nullable Entity e) {
        return form(e) != null;
    }

    /** The entity type of {@code e}'s form, on either side; null if it has none (or that creature is gone from the game). */
    public static @Nullable EntityType<?> formType(@Nullable Entity e) {
        Form f = form(e);
        return f == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(f.type()).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable AttributeSupplier body(EntityType<?> type) {
        return DefaultAttributes.hasSupplier(type) ? DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type) : null;
    }

    /** {@code type}'s base value of {@code a}, or {@code fallback} if it has none. */
    private static double base(EntityType<?> type, Holder<Attribute> a, double fallback) {
        AttributeSupplier s = body(type);
        return s != null && s.hasAttribute(a) ? s.getBaseValue(a) : fallback;
    }

    /** Its maximum health / 5 × √seconds, as possessing it at full health would cost. */
    public static double cost(EntityType<?> type, double seconds) {
        return base(type, Attributes.MAX_HEALTH, 20) / 5 * Math.sqrt(Math.max(0, seconds));
    }

    /** What ending {@code p}'s form would cost now: its price for the seconds it has left; 0 if they have none. */
    public static double remainingCost(ServerPlayer p) {
        Form f = form(p);
        EntityType<?> type = formType(p);
        return f == null || type == null ? 0 : cost(type, Math.max(0, f.until() - p.level().getGameTime()) / 20.0);
    }

    /**
     * What undoing {@code p}'s form costs another: its whole price less the part already used,
     * max health / 5 × (√total seconds − √seconds gone); 0 if they have none. A form saved before its
     * start was kept counts as begun now, so this is the price for the seconds it has left.
     */
    public static double unusedCost(ServerPlayer p) {
        Form f = form(p);
        EntityType<?> type = formType(p);
        if (f == null || type == null) return 0;
        long now = p.level().getGameTime();
        long since = f.since() == Form.UNKNOWN ? now : Math.min(f.since(), now);
        return Math.max(0, cost(type, (f.until() - since) / 20.0) - cost(type, (now - since) / 20.0));
    }

    /** Why {@code p} cannot become a {@code type} now, or null if they can. */
    public static @Nullable String refuse(ServerPlayer p, @Nullable EntityType<?> type) {
        if (type == null) return "没有这种生物";
        String name = type.getDescription().getString();
        if (Possession.possessing(p)) return "附身时不能化身";
        if (p.isSpectator() || p.isSleeping() || !p.isAlive()) return "现在不能化身";
        if (!(type.create(p.level(), EntitySpawnReason.TRIGGERED) instanceof Mob m) || m instanceof KeptFamiliar) return name + "不是能化身的生物";
        String locked = Familiars.locked(p, type);
        if (locked != null) return "还不能化身为" + name + "：" + locked;
        if (!p.level().noCollision(p, type.getDimensions().makeBoundingBox(p.position()).deflate(1e-7))) return "这里容不下" + name;
        return null;
    }

    /** Turns {@code p} into a {@code type} for {@code seconds} (or into another, if they already are one); false (nothing done) if they cannot. */
    public static boolean start(ServerPlayer p, EntityType<?> type, double seconds) {
        if (refuse(p, type) != null || !(type.create(p.level(), EntitySpawnReason.TRIGGERED) instanceof Mob shell)) return false;
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        boolean flies = shell.isNoGravity() || shell instanceof FlyingAnimal || shell.getNavigation() instanceof FlyingPathNavigation
                || LogosConfig.incarnationFlier(id.toString());
        long now = p.level().getGameTime();
        Form f = new Form(id, now, now + Math.max(1, Math.round(seconds * 20)), flies, shell.canBreatheUnderwater(),
                LogosConfig.incarnationHands(id.toString()));
        float share = p.getHealth() / p.getMaxHealth();
        p.setData(LogosMod.FORM, f);
        shape(p, type);
        p.setHealth(share * p.getMaxHealth());
        p.refreshDimensions();
        fly(p, flies);
        p.sendOverlayMessage(Component.translatable("logos.incarnate.start", type.getDescription(), (int) Math.round(seconds), Component.keybind("key.logos.revert")));
        return true;
    }

    /** Gives {@code p} back their own shape, if they have taken another. */
    public static void end(ServerPlayer p) {
        Form f = form(p);
        if (f == null) return;
        float share = p.getHealth() / p.getMaxHealth();
        p.setData(LogosMod.FORM, Form.NONE);
        shape(p, null);
        p.setHealth(share * p.getMaxHealth());
        p.refreshDimensions();
        if (f.flies()) fly(p, false);
        p.sendOverlayMessage(Component.translatable("logos.incarnate.end"));
    }

    /** Incarnated players within {@code r} of {@code at}, nearest first. */
    public static List<ServerPlayer> near(ServerLevel level, Vec3 at, double r) {
        return level.players().stream().filter(p -> active(p) && p.position().distanceTo(at) <= r)
                .sorted((a, b) -> Double.compare(a.position().distanceToSqr(at), b.position().distanceToSqr(at))).toList();
    }

    /** Sets {@code p}'s body to {@code type}'s, or back to their own (null). Saved with the player, so it outlasts a logout; not kept through death. */
    private static void shape(ServerPlayer p, @Nullable EntityType<?> type) {
        AttributeSupplier body = type == null ? null : body(type);
        for (Holder<Attribute> a : BODY) {
            AttributeInstance own = p.getAttribute(a);
            if (own == null) continue;
            own.removeModifier(MODIFIER);
            if (body == null || !body.hasAttribute(a)) continue;
            double to = body.getBaseValue(a);
            // A creature's speed drives its move control, not a player's legs: in a player's terms it is its pace.
            if (a.value() == Attributes.MOVEMENT_SPEED.value()) to = Possession.pace(to);
            double delta = to - own.getBaseValue();
            if (delta != 0) own.addPermanentModifier(new AttributeModifier(MODIFIER, delta, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** Lets a flying form fly as a player in creative does (twice jump); never takes flight from creative or spectator. */
    private static void fly(ServerPlayer p, boolean on) {
        if (p.isCreative() || p.isSpectator()) return;
        p.getAbilities().mayfly = on;
        if (!on) p.getAbilities().flying = false;
        p.onUpdateAbilities();
    }

    // ---- Events ---------------------------------------------------------------------------------

    /** On both sides, a player is resized when their form changes: a synced form arrives on clients this way. */
    @SubscribeEvent
    static void onTick(PlayerTickEvent.Pre event) {
        Player p = event.getEntity();
        Form f = form(p);
        Identifier now = f == null ? null : f.type();
        if (!Objects.equals((p.level().isClientSide() ? SIZED_CLIENT : SIZED_SERVER).put(p, now), now)) p.refreshDimensions();
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer p : List.copyOf(level.players())) {
                Form f = form(p);
                if (f != null) check(p, f);
            }
        }
    }

    /** Ends a spent form (or one whose creature is gone from the game); keeps what the form can do in force. */
    private static void check(ServerPlayer sp, Form f) {
        EntityType<?> type = formType(sp);
        long left = f.until() - sp.level().getGameTime();
        if (type == null || left <= 0) {
            end(sp);
            return;
        }
        // Changing game mode resets what a player may do; a flying form keeps its wings.
        if (f.flies() && !sp.getAbilities().mayfly) fly(sp, true);
        if (f.gills()) sp.setAirSupply(sp.getMaxAirSupply());
        if (type.fireImmune() && sp.isOnFire()) sp.clearFire();
        if (left <= 100 && left % 20 == 0) sp.sendOverlayMessage(Component.translatable("logos.incarnate.ending", left / 20));
    }

    /** A form that fire cannot touch keeps the player from burning. */
    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getSource().is(DamageTypeTags.IS_FIRE) && formType(event.getEntity()) instanceof EntityType<?> t && t.fireImmune()) event.setCanceled(true);
    }

    /** Without hands, a blow is the creature's own: its attack damage (1 if it has none), whatever the player holds. */
    @SubscribeEvent
    static void onAttack(AttackEntityEvent event) {
        Player p = event.getEntity();
        Form f = form(p);
        if (f == null || f.hands()) return;
        event.setCanceled(true);
        if (!(p instanceof ServerPlayer sp) || !(event.getTarget() instanceof LivingEntity t) || !t.isAttackable() || !t.isAlive()) return;
        EntityType<?> type = formType(sp);
        float strength = sp.getAttackStrengthScale(0.5f);
        sp.resetAttackStrengthTicker();
        if (type != null) t.hurtServer(sp.level(), sp.damageSources().playerAttack(sp), (float) base(type, Attributes.ATTACK_DAMAGE, 1) * strength);
    }

    // Without hands, nothing is used, placed, mined or handled.
    @SubscribeEvent
    static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        handless(event.getEntity(), event);
    }

    @SubscribeEvent
    static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        handless(event.getEntity(), event);
    }

    @SubscribeEvent
    static void onHitBlock(PlayerInteractEvent.LeftClickBlock event) {
        handless(event.getEntity(), event);
    }

    @SubscribeEvent
    static void onUseEntity(PlayerInteractEvent.EntityInteract event) {
        handless(event.getEntity(), event);
    }

    private static void handless(Player p, ICancellableEvent event) {
        Form f = form(p);
        if (f != null && !f.hands()) event.setCanceled(true);
    }
}
