package dev.logos.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.mixin.MannequinAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 附身: a player leaves their body standing where they were (a mannequin with their skin) and rides
 * inside a creature, unseen, at its eyes. They steer it with their own keys through the same
 * ridden movement as a familiar mount ({@link FamiliarRiding}); its own AI stands idle meanwhile,
 * as a ridden horse's does. Left click is its melee blow and the use button its own skill
 * ({@link PossessionSkills}) or else its ranged attack, if it has one ({@link #strike},
 * {@link #shoot}). Pressing the instinct key, the player lets go for a while ({@link #instinct}):
 * its own AI acts again, at what they aimed at if anything, with its own attacks and animations,
 * which reaches what no generic blow can (another mod's creatures above all).
 * <p>
 * Whatever hurts the body hurts the player (their own armour and totems apply), and nothing else
 * does; creatures that go for the hidden player go for the body instead. It ends when the time is
 * up, when the player sneaks (as off a horse), when the creature strays more than {@link #RANGE}
 * blocks from the body, or when it dies (the player then takes a quarter of their max health); and
 * when the player dies or leaves. Ending puts the player back in their body. Never saved: a
 * session cut short by a crash is undone at the player's next login.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Possession {
    public static final double RANGE = 128;
    public static final double MAX_SECONDS = 600;
    /** The share of the player's max health a possessed creature's death costs them. */
    public static final float BACKLASH = 0.25f;
    /** Ticks between two blows or shots. */
    public static final int ATTACK_INTERVAL = 20;
    /** How long the instinct key lets the creature act on its own, at most. */
    public static final int INSTINCT_TICKS = 200;
    /** The value of {@link LogosMod#POSSESSOR} on a creature nobody possesses. */
    public static final UUID NOBODY = new UUID(0, 0);
    /** Entity types that can never be possessed, besides players and bosses ({@code #c:bosses}). */
    public static final TagKey<EntityType<?>> UNPOSSESSABLE = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(LogosMod.MODID, "unpossessable"));
    public static final ResourceKey<DamageType> BACKLASH_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(LogosMod.MODID, "possession_backlash"));
    /** Keeps the body's chunk ticking, so it stays where it can be hurt. Sessions do not outlive the server, nor do their tickets. */
    public static final TicketController TICKETS = new TicketController(Identifier.fromNamespaceAndPath(LogosMod.MODID, "possession"),
            (level, tickets) -> Set.copyOf(tickets.getEntityTickets().keySet()).forEach(tickets::removeAllTickets));
    /** On a body: its owner's UUID. */
    private static final String BODY_TAG = "logos_possession_body";
    /** On a possessing player: where their body stands, to put them back after a crash. */
    private static final String RETURN_TAG = "logos_possession_return";

    private static final class Session {
        final ServerPlayer player;
        final Mob mob;
        final Mannequin body;
        /** The chunk its ticket holds: where the body stood at the start, wherever it has been pushed since. */
        final ChunkPos ticket;
        final long until;
        long nextAttack;
        long nextSkill;
        /** While its instincts act: when they stop, and what it was set on (null: nothing). */
        long instinctUntil;
        @Nullable LivingEntity instinctTarget;

        Session(ServerPlayer player, Mob mob, Mannequin body, long until) {
            this.player = player;
            this.mob = mob;
            this.body = body;
            this.ticket = body.chunkPosition();
            this.until = until;
        }
    }

    private static final Map<UUID, Session> ACTIVE = new HashMap<>();
    /** Set while damage to a body is passed on to its owner, who takes no other. */
    private static boolean forwarding;

    private Possession() {}

    /** The player possessing {@code e}, on either side: the one riding it whose UUID it carries. */
    public static @Nullable Player possessor(@Nullable Entity e) {
        if (!(e instanceof Mob m) || !m.hasData(LogosMod.POSSESSOR)) return null;
        UUID id = m.getData(LogosMod.POSSESSOR);
        return !id.equals(NOBODY) && m.getFirstPassenger() instanceof Player p && p.getUUID().equals(id) ? p : null;
    }

    /** Whether {@code e} is a player inside a creature, on either side. */
    public static boolean possessing(@Nullable Entity e) {
        return e instanceof Player p && possessor(p.getVehicle()) == p;
    }

    /** Whether the possessed creature {@code e} acts on its own instincts now, on either side. */
    public static boolean instinct(@Nullable Entity e) {
        return e != null && e.hasData(LogosMod.INSTINCT) && e.getData(LogosMod.INSTINCT);
    }

    /** A mob carrying nobody, and neither a boss nor of {@code #logos:unpossessable}. */
    public static boolean possessable(@Nullable Entity e) {
        return e instanceof Mob m && m.isAlive() && !m.isVehicle()
                && !m.getType().builtInRegistryHolder().is(Tags.EntityTypes.BOSSES) && !m.getType().builtInRegistryHolder().is(UNPOSSESSABLE);
    }

    /** Whether {@code p} may go into {@code mob} now: one at a time, in the same world, within {@link #RANGE}. */
    public static boolean canStart(ServerPlayer p, Entity mob) {
        return possessable(mob) && !ACTIVE.containsKey(p.getUUID()) && !Incarnation.active(p) && !p.isSpectator() && !p.isSleeping() && p.isAlive()
                && mob.level() == p.level() && mob.distanceTo(p) <= RANGE;
    }

    /** Current health / 5 × √seconds; free on one's own familiar. */
    public static double cost(LivingEntity target, Entity caster, double seconds) {
        if (caster.getUUID().equals(Familiars.masterOf(target))) return 0;
        return target.getHealth() / 5 * Math.sqrt(Math.max(0, seconds));
    }

    /** Puts {@code p} into {@code mob} for {@code seconds}, leaving their body where they stand; false (nothing done) if they cannot. */
    public static boolean start(ServerPlayer p, Mob mob, double seconds) {
        if (!canStart(p, mob) || !(p.level() instanceof ServerLevel level)) return false;
        Mannequin body = EntityType.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED);
        if (body == null) return false;
        body.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0);
        body.setYHeadRot(p.getYHeadRot());
        body.yBodyRot = p.yBodyRot;
        body.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(p.getGameProfile()));
        body.setCustomName(p.getName());
        ((MannequinAccessor) body).logos$setDescription(Component.translatable("logos.possess.body"));
        body.getPersistentData().putString(BODY_TAG, p.getUUID().toString());

        p.stopRiding();
        mob.stopRiding();
        Session s = new Session(p, mob, body, level.getGameTime() + Math.max(1, Math.round(seconds * 20)));
        ACTIVE.put(p.getUUID(), s);
        if (!level.addFreshEntity(body)) {
            ACTIVE.remove(p.getUUID());
            return false;
        }
        mob.setData(LogosMod.POSSESSOR, p.getUUID());
        if (!p.startRiding(mob, true, true)) {
            ACTIVE.remove(p.getUUID());
            mob.setData(LogosMod.POSSESSOR, NOBODY);
            body.discard();
            return false;
        }
        mob.setTarget(null);
        mob.getNavigation().stop();
        CompoundTag back = new CompoundTag();
        back.putString("dimension", level.dimension().identifier().toString());
        back.putDouble("x", body.getX());
        back.putDouble("y", body.getY());
        back.putDouble("z", body.getZ());
        p.getPersistentData().put(RETURN_TAG, back);
        TICKETS.forceChunk(level, p.getUUID(), s.ticket.x(), s.ticket.z(), true, true);
        // Whatever was after the player is after their body now.
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48), m -> m.getTarget() == p)) m.setTarget(body);
        p.sendOverlayMessage(Component.translatable("logos.possess.start", (int) Math.round(seconds)));
        return true;
    }

    /** Ends {@code p}'s possession, if any, and puts them back in their body; {@code backlash} when the creature died. */
    public static void end(ServerPlayer p, boolean backlash) {
        Session s = ACTIVE.remove(p.getUUID());
        if (s == null) return;
        if (s.mob.hasData(LogosMod.POSSESSOR)) s.mob.setData(LogosMod.POSSESSOR, NOBODY);
        if (instinct(s.mob)) s.mob.setData(LogosMod.INSTINCT, false);
        if (p.getVehicle() == s.mob) p.stopRiding();
        ServerLevel level = (ServerLevel) s.body.level();
        p.teleportTo(level, s.body.getX(), s.body.getY(), s.body.getZ(), Set.of(), s.body.getYRot(), p.getXRot(), true);
        p.resetFallDistance();
        TICKETS.forceChunk(level, p.getUUID(), s.ticket.x(), s.ticket.z(), false, true);
        s.body.discard();
        p.getPersistentData().remove(RETURN_TAG);
        if (backlash) p.hurtServer(level, level.damageSources().source(BACKLASH_DAMAGE, s.mob), p.getMaxHealth() * BACKLASH);
    }

    /**
     * The instinct key: pressed ({@code on}), the player stops steering for up to {@link #INSTINCT_TICKS}
     * and the creature's own AI acts: at {@code target} if it is something it may go for (not the
     * player or itself, nor another player PvP would spare), as a familiar ordered to attack does;
     * otherwise as it would anyway. Pressed again (or once the time is up, or that target is dead or
     * lost to it), its target is dropped and the player steers again.
     */
    public static void instinct(ServerPlayer p, @Nullable Entity target, boolean on) {
        Session s = ACTIVE.get(p.getUUID());
        if (s == null || instinct(s.mob) == on) return;
        s.mob.setData(LogosMod.INSTINCT, on);
        if (!on) {
            s.instinctTarget = null;
            Familiars.aim(s.mob, null);
            p.sendOverlayMessage(Component.translatable("logos.possess.steer"));
            return;
        }
        s.instinctUntil = s.mob.level().getGameTime() + INSTINCT_TICKS;
        s.instinctTarget = null;
        if (target instanceof LivingEntity t && t != s.mob && t != p && t.isAlive() && t.level() == s.mob.level()
                && !(t instanceof Player other && !p.canHarmPlayer(other))) {
            Familiars.aim(s.mob, t);
            s.instinctTarget = t;
        }
        p.sendOverlayMessage(Component.translatable("logos.possess.instinct", INSTINCT_TICKS / 20, Component.keybind("key.logos.instinct")));
    }

    /** Whether the creature's instincts have had their time, or lost what they were set on. */
    private static boolean instinctSpent(Session s) {
        LivingEntity t = s.instinctTarget;
        return s.mob.level().getGameTime() >= s.instinctUntil
                || t != null && (!t.isAlive() || t.level() != s.mob.level() || s.mob.getTarget() != t);
    }

    /** Left click: the creature's own melee blow at {@code target}, if within its reach (otherwise only a swing). */
    public static void strike(ServerPlayer p, @Nullable Entity target) {
        Session s = ACTIVE.get(p.getUUID());
        if (s == null || instinct(s.mob) || !ready(s)) return;
        Mob mob = s.mob;
        mob.swing(InteractionHand.MAIN_HAND);
        if (!(target instanceof LivingEntity t) || t == mob || t == p || !t.isAlive() || t.level() != mob.level()) return;
        if (t instanceof Player other && !p.canHarmPlayer(other)) return;
        if (mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) && mob.isWithinMeleeAttackRange(t)) mob.doHurtTarget((ServerLevel) mob.level(), t);
    }

    /**
     * The use button: the creature's own skill if it has one ({@link PossessionSkills}), else its ranged
     * attack if it has one, at {@code target} or else where the player looks.
     */
    public static void shoot(ServerPlayer p, @Nullable Entity target) {
        Session s = ACTIVE.get(p.getUUID());
        if (s == null || instinct(s.mob)) return;
        PossessionSkills.Entry skill = PossessionSkills.of(s.mob);
        if (skill != null) {
            long now = s.mob.level().getGameTime();
            if (now >= s.nextSkill && skill.skill().use((ServerLevel) s.mob.level(), s.mob, p,
                    PossessionSkills.fair(p, s.mob, target) ? (LivingEntity) target : null)) {
                s.nextSkill = now + skill.cooldown();
            }
            return;
        }
        if (!(s.mob instanceof RangedAttackMob ranged) || !ready(s)) return;
        LivingEntity at = target instanceof LivingEntity t && t != s.mob && t != p && t.isAlive() && t.level() == s.mob.level()
                && s.mob.distanceTo(t) <= 64 && s.mob.hasLineOfSight(t) ? t : aimPoint(s.mob.level(), p);
        ranged.performRangedAttack(at, 1.0f);
    }

    private static boolean ready(Session s) {
        long now = s.mob.level().getGameTime();
        if (now < s.nextAttack) return false;
        s.nextAttack = now + ATTACK_INTERVAL;
        return true;
    }

    /** A stand-in to shoot at, 24 blocks along the player's gaze (their eyes are the creature's); never added to the world. */
    private static LivingEntity aimPoint(Level level, Player p) {
        Vec3 to = p.getEyePosition().add(p.getLookAngle().scale(24));
        return new ArmorStand(level, to.x, to.y - 0.65, to.z);
    }

    // ---- How a possessed creature moves (FamiliarRiding hands these over) ----------------------

    /** Flying or in water, the player swims and flies where they look; on land they walk. */
    private static boolean free(LivingEntity mob) {
        return mob.isNoGravity() || mob.isInWater();
    }

    /** The player's own keys, as if walking: strafing and backing up at full speed. */
    public static Vec3 riddenInput(LivingEntity mob, Player p) {
        if (!free(mob)) return new Vec3(p.xxa, 0, p.zza);
        double pitch = Math.toRadians(p.getXRot());
        return new Vec3(p.xxa, -Math.sin(pitch) * p.zza, Math.cos(pitch) * p.zza);
    }

    /**
     * Its own pace: a mob's move control drives it at its speed attribute squared, so that, kept
     * between 0.06 and 0.15 (a player walks at 0.1). A flier, which has no walking pace, flies at 0.3.
     */
    public static float speed(LivingEntity mob) {
        if (mob.isNoGravity()) return 0.3f;
        return (float) pace(mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
    }

    /** A creature's walking pace in a player's terms, from its movement speed attribute: {@code a²}, kept between 0.06 and 0.15. */
    public static double pace(double movementSpeed) {
        return Math.clamp(movementSpeed * movementSpeed, 0.06, 0.15);
    }

    /** It faces exactly where the player looks (their view is its view) and jumps with their jump key. */
    public static void tickRidden(LivingEntity mob, Player p) {
        mob.setYRot(p.getYRot());
        mob.yRotO = mob.getYRot();
        mob.setXRot(p.getXRot());
        mob.yBodyRot = mob.getYRot();
        mob.yHeadRot = mob.getYRot();
        mob.setJumping(p.isJumping());
    }

    /** Where the player sits: with their eyes at the creature's. */
    public static Vec3 seat(Entity mob, Entity player) {
        return new Vec3(mob.getX(), mob.getEyeY() - player.getEyeHeight(), mob.getZ());
    }

    // ---- Events ---------------------------------------------------------------------------------

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (!ACTIVE.isEmpty()) for (Session s : List.copyOf(ACTIVE.values())) check(s);
    }

    /** Ends a session whose time, creature, body, range or rider is gone; keeps the hidden player from drowning or a fall. */
    private static void check(Session s) {
        ServerPlayer p = s.player;
        if (!s.mob.isAlive()) {
            end(p, s.mob.isDeadOrDying());
            return;
        }
        long left = s.until - p.level().getGameTime();
        if (p.getVehicle() != s.mob || s.body.isRemoved() || left <= 0
                || s.mob.level() != s.body.level() || s.mob.distanceTo(s.body) > RANGE) {
            end(p, false);
            return;
        }
        p.setAirSupply(p.getMaxAirSupply());
        p.resetFallDistance();
        PossessionSkills.tick(p, s.mob);
        if (instinct(s.mob) && instinctSpent(s)) instinct(p, null, false);
        if (left <= 100 && left % 20 == 0) p.sendOverlayMessage(Component.translatable("logos.possess.ending", left / 20));
    }

    /** A possessed creature's death throws its possessor back, hurt; a possessing player's own death puts them back first, so their things drop by their body. */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || ACTIVE.isEmpty()) return;
        if (event.getEntity() instanceof ServerPlayer p) end(p, false);
        else if (possessor(event.getEntity()) instanceof ServerPlayer p) end(p, true);
    }

    /** Damage to a body goes to its owner instead; a possessing player takes no other. */
    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity t = event.getEntity();
        if (!(t.level() instanceof ServerLevel level) || ACTIVE.isEmpty()) return;
        if (t instanceof ServerPlayer p && ACTIVE.containsKey(p.getUUID())) {
            if (!forwarding) event.setCanceled(true);
            return;
        }
        Session s = sessionOfBody(t);
        if (s == null) return;
        event.setCanceled(true);
        level.broadcastDamageEvent(t, event.getSource());
        forwarding = true;
        try {
            s.player.hurtServer(level, event.getSource(), event.getAmount());
        } finally {
            forwarding = false;
        }
    }

    /** Creatures going for a possessing player go for their body instead. */
    @SubscribeEvent
    static void onTarget(LivingChangeTargetEvent event) {
        if (ACTIVE.isEmpty() || !(event.getNewAboutToBeSetTarget() instanceof ServerPlayer p)) return;
        Session s = ACTIVE.get(p.getUUID());
        if (s != null) event.setNewAboutToBeSetTarget(event.getEntity() == s.mob ? null : s.body);
    }

    /** A body left behind by a session that is gone (a crash, a reload) does not come back. */
    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mannequin m) || !m.getPersistentData().contains(BODY_TAG)) return;
        if (sessionOfBody(m) == null) event.setCanceled(true);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) end(p, false);
    }

    /** A player whose session a crash cut short is put back where their body stood. */
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !p.getPersistentData().contains(RETURN_TAG)) return;
        CompoundTag back = p.getPersistentData().getCompoundOrEmpty(RETURN_TAG);
        p.getPersistentData().remove(RETURN_TAG);
        p.stopRiding();
        Identifier dim = Identifier.tryParse(back.getStringOr("dimension", ""));
        ServerLevel level = dim == null ? null : p.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        if (level != null) p.teleportTo(level, back.getDoubleOr("x", p.getX()), back.getDoubleOr("y", p.getY()), back.getDoubleOr("z", p.getZ()),
                Set.of(), p.getYRot(), p.getXRot(), true);
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
    }

    /** Inside a creature, the player's own hands do nothing: its blows go through strike and shoot ({@link PlayerRefusals}). */
    static boolean handsBound(Player p) {
        return !ACTIVE.isEmpty() && possessing(p);
    }

    private static @Nullable Session sessionOfBody(Entity e) {
        if (!(e instanceof Mannequin) || !e.getPersistentData().contains(BODY_TAG)) return null;
        for (Session s : ACTIVE.values()) if (s.body == e) return s;
        return null;
    }
}
