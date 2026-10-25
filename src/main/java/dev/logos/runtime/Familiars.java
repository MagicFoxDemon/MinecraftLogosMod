package dev.logos.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/**
 * The familiar bond: a creature that answers to a player. It follows them, never turns on them, on
 * their villagers or on their other familiars, and fights exactly what its master fights — what
 * attacks the master or what the master attacks. Everything else about it stays its own: a zombie
 * still punches, a skeleton still shoots, and both still burn at noon.
 * <p>
 * Out of combat it is called back past 12 blocks, in combat past 32; past 96 it tries a few times to
 * come back and otherwise fades. It is never kept: a familiar saved with its chunk is gone when the
 * chunk loads again, and one whose master leaves goes with them. It drops only what it picked up.
 */
public final class Familiars {
    public static final int NORMAL_KILLS = 49, BOSS_KILLS = 7;
    /** One in-game day. */
    public static final int LIFETIME = 24000;
    private static final double CALM_LEASH = 12, COMBAT_LEASH = 32, LOST = 96;
    private static final int RESCUE_TRIES = 3;
    /** Default and largest stance range: hunting further would only see it called back by the combat leash. */
    public static final double HUNT_RADIUS = 24, MAX_HUNT_RADIUS = COMBAT_LEASH;

    /** Who it belongs to and when the bond ends. Saved only so a reloaded familiar can be recognised and removed. */
    public record Bond(UUID owner, long expiresAt) {
        public static final Codec<Bond> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Bond::owner),
                Codec.LONG.fieldOf("expires_at").forGetter(Bond::expiresAt)
        ).apply(i, Bond::new));
        public static final Bond NONE = new Bond(new UUID(0, 0), 0);
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Bond> STREAM_CODEC =
                net.minecraft.network.codec.StreamCodec.composite(UUIDUtil.STREAM_CODEC, Bond::owner,
                        net.minecraft.network.codec.ByteBufCodecs.VAR_LONG, Bond::expiresAt, Bond::new);
    }

    /** What it is fighting for its master right now, and how often calling it back has failed. */
    private static final class State {
        @Nullable LivingEntity target;
        int failedRescues;
        /** Order of summoning, to tell the latest apart even within one tick. */
        long seq;
        /** Its age last tick, to undo natural growing up. */
        @Nullable Integer age;
        /** An order to go somewhere ({@code gather}), and how it is going: given up when it stops getting closer. */
        @Nullable Vec3 errand;
        long errandSince, lastProgress;
        double bestDistance = Double.MAX_VALUE;
        /** Where it stands guard after arriving somewhere away from its master. */
        @Nullable Vec3 post;
        /** Its stance: what it hunts on its own when no fight is ordered (null: nothing), and how far around its master or post. */
        @Nullable Filter hunt;
        @Nullable SpellInstance huntBy;
        double huntRadius = HUNT_RADIUS;
        /** Whether its current target is one it found itself (then it lets go once the prey leaves its range). */
        boolean hunting;
    }

    private static final Set<Mob> LIVE = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Mob, State> STATE = new WeakHashMap<>();
    private static long summoned;

    private Familiars() {}

    public static @Nullable Bond bondOf(@Nullable Entity e) {
        if (!(e instanceof Mob m) || !m.hasData(LogosMod.FAMILIAR)) return null;
        Bond b = m.getData(LogosMod.FAMILIAR);
        return b == Bond.NONE ? null : b;
    }

    public static boolean isFamiliar(@Nullable Entity e) {
        return bondOf(e) != null;
    }

    /** The master of {@code e} if it is a familiar, or of the familiar that shot it; null otherwise. */
    public static @Nullable UUID masterOf(@Nullable Entity e) {
        if (e instanceof Projectile p) e = p.getOwner();
        Bond b = bondOf(e);
        return b == null ? null : b.owner();
    }

    public static List<Mob> of(Player owner) {
        List<Mob> out = new ArrayList<>();
        for (Mob m : LIVE) {
            Bond b = bondOf(m);
            if (b != null && b.owner().equals(owner.getUUID()) && m.isAlive()) out.add(m);
        }
        return out;
    }

    /** {@code owner}'s most recently summoned familiar, or the most recent one called {@code name}; null if none. */
    public static @Nullable Mob latest(Player owner, @Nullable String name) {
        Mob best = null;
        for (Mob m : of(owner)) {
            if (name != null && !name.isBlank() && (m.getCustomName() == null || !m.getCustomName().getString().equals(name.strip()))) continue;
            if (best == null || seq(m) > seq(best)) best = m;
        }
        return best;
    }

    /**
     * Sends a familiar to {@code dest}: it drops what it was fighting and walks (or flies) there. Arriving
     * by its master it follows again; arriving elsewhere it stands guard there. Returns false if {@code dest}
     * is too far from its master for any familiar to go.
     */
    public static boolean sendTo(Mob m, Vec3 dest) {
        Bond b = bondOf(m);
        Player owner = b == null ? null : m.level().getPlayerByUUID(b.owner());
        if (owner == null || dest.distanceTo(owner.position()) > LOST) return false;
        State s = STATE.computeIfAbsent(m, k -> new State());
        s.target = null;
        s.hunting = false;
        aim(m, null);
        s.errand = dest;
        s.post = null;
        s.errandSince = s.lastProgress = m.level().getGameTime();
        s.bestDistance = Double.MAX_VALUE;
        return true;
    }

    /** Where it was sent ({@code gather}) or stands guard, or null if it is with its master. */
    public static @Nullable Vec3 orderPoint(Mob m) {
        State s = STATE.get(m);
        return s == null ? null : s.errand != null ? s.errand : s.post;
    }

    /** Whether it is on an errand (not just standing guard). */
    public static boolean onErrand(Mob m) {
        State s = STATE.get(m);
        return s != null && s.errand != null;
    }

    /** Whether its current target is one it found itself by its stance, rather than its master's fight or an order. */
    public static boolean isHunting(Mob m) {
        State s = STATE.get(m);
        return s != null && s.hunting;
    }

    /** Whether it is on an errand or standing guard: then it does not walk after its master. */
    public static boolean hasOrders(Mob m) {
        State s = STATE.get(m);
        return s != null && (s.errand != null || s.post != null);
    }

    /** Kills of {@code type} needed before it can be summoned: 49, or 7 for a boss. */
    public static int killsNeeded(EntityType<?> type) {
        return type.builtInRegistryHolder().is(Tags.EntityTypes.BOSSES) ? BOSS_KILLS : NORMAL_KILLS;
    }

    public static int kills(ServerPlayer p, EntityType<?> type) {
        return p.getStats().getValue(Stats.ENTITY_KILLED.get(type));
    }

    /**
     * Summons a {@code type} bound to {@code owner} at {@code at}, named {@code nickname} if given. Costs its maximum health × 2 from
     * the spell. Returns null on success, or why not.
     */
    public static @Nullable String summon(SpellInstance inst, ServerPlayer owner, EntityType<?> type, ServerLevel level, Vec3 at, @Nullable String nickname,
            @Nullable Filter hunt, @Nullable Double huntRadius) {
        String name = type.getDescription().getString();
        if (type == EntityType.ENDER_DRAGON) {
            String why = DragonFamiliar.forbidden(level);
            if (why != null) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(why).withStyle(net.minecraft.ChatFormatting.RED));
                return why;
            }
        }
        if (!owner.getAbilities().instabuild && kills(owner, type) < killsNeeded(type)) {
            return "还不能召唤" + name + "：击杀 " + kills(owner, type) + " / " + killsNeeded(type);
        }
        if (of(owner).stream().filter(f -> !(f instanceof KeptFamiliar)).count() >= LogosConfig.maxFamiliars()) return "使魔已达上限 " + LogosConfig.maxFamiliars() + " 只（配置 maxFamiliars）";
        if (!(type.create(level, EntitySpawnReason.MOB_SUMMONED) instanceof Mob m)) return name + "不是能召唤的生物";
        m.snapTo(at.x, at.y, at.z, owner.getYRot(), 0);
        if (!inst.spend(m.getMaxHealth() * 2, "召唤" + name, dev.logos.lang.Category.EFFECT)) return null; // the spell itself reports running dry
        try {
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), EntitySpawnReason.MOB_SUMMONED, null);
        } catch (RuntimeException ignored) {
            // A modded mob that cannot finish spawning this way still works as a plain body.
        }
        if (nickname != null && !nickname.isBlank()) {
            // Named, it can be picked out later: (familiar "name").
            m.setCustomName(net.minecraft.network.chat.Component.literal(nickname.strip()));
            m.setCustomNameVisible(true);
        }
        bind(m, owner, level.getGameTime() + LIFETIME);
        if (hunt != null || huntRadius != null) setStance(m, hunt, inst, huntRadius);
        level.addFreshEntity(m);
        Vfx.burst(level, m.position().add(0, m.getBbHeight() / 2, 0), dev.logos.lang.Element.ARCANE, 30);
        Vfx.sound(level, at, SoundEvents.EVOKER_PREPARE_SUMMON, 1f, 1.1f);
        return null;
    }

    static void bind(Mob m, ServerPlayer owner, long expiresAt) {
        m.setData(LogosMod.FAMILIAR, new Bond(owner.getUUID(), expiresAt));
        m.setPersistenceRequired();
        m.setTarget(null);
        // A made familiar has a mind of its own (LifeformMind, ColossusMind) that does its following.
        if (m instanceof PathfinderMob pm && !(m instanceof KeptFamiliar)) m.goalSelector.addGoal(1, new FollowMaster(pm));
        STATE.computeIfAbsent(m, k -> new State()).seq = ++summoned;
        LIVE.add(m);
    }

    /** A kept familiar (a mana lifeform) loaded again with its chunk: it answers to its master as before. */
    static void rebind(Mob m) {
        // A lifeform has a mind of its own (LifeformMind) that does its following.
        if (m instanceof PathfinderMob pm && !(m instanceof KeptFamiliar)) m.goalSelector.addGoal(1, new FollowMaster(pm));
        STATE.computeIfAbsent(m, k -> new State()).seq = ++summoned;
        LIVE.add(m);
    }

    /** Kept with the world rather than unmade: mana lifeforms and alchemical colossi. */
    static boolean kept(Mob m) {
        return m instanceof KeptFamiliar;
    }

    private static long seq(Mob m) {
        State s = STATE.get(m);
        return s == null ? 0 : s.seq;
    }

    /** Tests only: binds {@code m} to {@code owner} as if summoned, without the summoning rules. */
    public static void bindForTest(Mob m, ServerPlayer owner) {
        bind(m, owner, m.level().getGameTime() + LIFETIME);
    }

    /** Sends a familiar away: it drops what it picked up and vanishes. */
    public static void dismiss(Mob m) {
        if (m.level() instanceof ServerLevel level) {
            for (ItemStack s : pickedUp(m)) level.addFreshEntity(new ItemEntity(level, m.getX(), m.getY() + 0.5, m.getZ(), s));
            Vfx.burst(level, m.position().add(0, m.getBbHeight() / 2, 0), dev.logos.lang.Element.ARCANE, 20);
        }
        LIVE.remove(m);
        m.discard();
    }

    /** What it picked up itself: picking up marks an equipment slot as always dropped; spawn gear is not. */
    static List<ItemStack> pickedUp(Mob m) {
        List<ItemStack> out = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = m.getItemBySlot(slot);
            if (!s.isEmpty() && m.getDropChances().isPreserved(slot)) out.add(s.copy());
        }
        // A lifeform's core pages are its master's.
        if (m instanceof ManaLifeform lf) {
            for (int i = 0; i < lf.core.getContainerSize(); i++) if (!lf.core.getItem(i).isEmpty()) out.add(lf.core.getItem(i).copy());
        }
        // So are a colossus's devices.
        if (m instanceof AlchemicalColossus col) {
            for (int i = 0; i < col.devices.getContainerSize(); i++) if (!col.devices.getItem(i).isEmpty()) out.add(col.devices.getItem(i).copy());
        }
        return out;
    }

    // ---- what it fights -----------------------------------------------------------------------

    /** Whether {@code target} is on the master's side: the master or one of their familiars. */
    static boolean friendly(UUID master, @Nullable Entity target) {
        if (target == null) return false;
        if (target.getUUID().equals(master)) return true;
        return master.equals(masterOf(target));
    }

    /** The master fights {@code target} (or {@code target} fights the master): all their familiars join in. */
    static void rally(ServerPlayer master, LivingEntity target) {
        if (friendly(master.getUUID(), target) || !target.isAlive()) return;
        for (Mob m : of(master)) {
            if (m == target) continue;
            State s = STATE.computeIfAbsent(m, k -> new State());
            // A fight calls it off any errand or post.
            s.target = target;
            s.hunting = false;
            s.errand = null;
            s.post = null;
            aim(m, target);
        }
    }

    /** Orders one familiar onto {@code target}, as if its master had struck it. False if {@code target} is on its master's side. */
    public static boolean order(Mob m, LivingEntity target) {
        Bond b = bondOf(m);
        if (b == null || m == target || friendly(b.owner(), target) || !target.isAlive() || target.level() != m.level()) return false;
        State s = STATE.computeIfAbsent(m, k -> new State());
        s.target = target;
        s.hunting = false;
        s.errand = null;
        s.post = null;
        aim(m, target);
        return true;
    }

    /**
     * Sets its stance: with {@code hunt} it goes after the nearest creature that filter admits within
     * {@code radius} of its master (or its post) whenever it has no fight of its master's; a null filter
     * leaves the filter as it was, a null radius likewise. The filter is judged as written in {@code by}.
     */
    public static void setStance(Mob m, @Nullable Filter hunt, SpellInstance by, @Nullable Double radius) {
        State s = STATE.computeIfAbsent(m, k -> new State());
        if (hunt != null) {
            s.hunt = hunt;
            s.huntBy = by;
            if (s.hunting) {
                s.target = null; // the old stance's prey; the new one picks again
                s.hunting = false;
            }
        }
        if (radius != null) s.huntRadius = Math.max(1, Math.min(radius, MAX_HUNT_RADIUS));
    }

    /** Whether it hunts on its own (tests and tooling). */
    public static boolean hunts(Mob m) {
        State s = STATE.get(m);
        return s != null && s.hunt != null;
    }

    /** No fight of its master's: the nearest prey its stance admits, seen by it or its master, around its post or master. */
    private static void hunt(Mob m, Player owner, State s, ServerLevel level) {
        Vec3 centre = s.post != null ? s.post : owner.position();
        if (s.hunting && s.target != null && s.target.position().distanceTo(centre) > s.huntRadius + 8) {
            s.target = null; // the prey got away
            s.hunting = false;
        }
        if (s.target != null || s.errand != null || s.hunt == null || s.huntBy == null || level.getGameTime() % 10 != 0) return;
        SpellInstance by = s.huntBy;
        UUID master = owner.getUUID();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : level.getEntities(m, new net.minecraft.world.phys.AABB(centre, centre).inflate(s.huntRadius))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || friendly(master, e) || e.isSpectator()
                    || e instanceof net.minecraft.world.entity.decoration.ArmorStand || le.position().distanceTo(centre) > s.huntRadius) continue;
            // Never its master's side, pets or allies; other players only if the stance was written to touch players.
            if (e instanceof Player && !by.spell.affectsPlayers()) continue;
            if (e instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwner() != null && master.equals(o.getOwner().getUUID())) continue;
            if (owner.isAlliedTo(e) || !s.hunt.test(by, e)) continue;
            if (!m.hasLineOfSight(e) && !owner.hasLineOfSight(e)) continue;
            double d = m.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = le;
            }
        }
        if (best != null) {
            s.target = best;
            s.hunting = true;
        }
    }

    /** The only target a familiar may take: what its master is fighting. */
    public static @Nullable LivingEntity orderedTarget(Mob m) {
        State s = STATE.get(m);
        return s == null ? null : s.target;
    }

    private static void aim(Mob m, @Nullable LivingEntity target) {
        m.setTarget(target);
        // A warden attacks only what it is furious at, and otherwise listens and sniffs its way there slowly:
        // its master's fight makes it furious at once.
        if (target != null && m instanceof net.minecraft.world.entity.monster.warden.Warden w) {
            w.increaseAngerAt(target, net.minecraft.world.entity.monster.warden.AngerLevel.ANGRY.getMinimumAnger() + 20, false);
        }
        // Brain-driven mobs (piglins, hoglins, wardens…) keep their target in memory instead.
        if (m.getBrain().checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
            if (target == null) m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            else m.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        }
    }

    // ---- staying close --------------------------------------------------------------------------

    static void tick(Mob m) {
        Bond b = bondOf(m);
        if (b == null || !(m.level() instanceof ServerLevel level)) return;
        // Looked up in its own world: a master who left (or went to another dimension) is not found, and it fades.
        Player owner = level.getPlayerByUUID(b.owner());
        // A made familiar waits where it is for a master who is away, and acts on its own while dormant or berserk.
        if (kept(m) && (owner == null || ((KeptFamiliar) m).actsAlone())) {
            State st = STATE.get(m);
            if (st != null) {
                st.target = null;
                st.hunting = false;
                st.errand = null;
            }
            return;
        }
        if (owner == null || level.getGameTime() >= b.expiresAt()) {
            fade(m);
            return;
        }
        State s = STATE.computeIfAbsent(m, k -> new State());
        // It does not grow up (or count down) by itself: undo the one-tick step; a spell's grow/decay is a bigger jump and stays.
        if (m instanceof net.minecraft.world.entity.AgeableMob a) {
            if (s.age != null && Math.abs(a.getAge() - s.age) == 1) a.setAge(s.age);
            s.age = a.getAge();
        }
        if (m instanceof net.minecraft.world.entity.monster.warden.Warden w) {
            // Left alone, a warden digs back into the ground and is gone: keep its dig cooldown up.
            if (level.getGameTime() % 100 == 0) {
                w.getBrain().setMemoryWithExpiry(net.minecraft.world.entity.ai.memory.MemoryModuleType.DIG_COOLDOWN, net.minecraft.util.Unit.INSTANCE, 1200);
            }
            // Its master's steps and blows are vibrations too: it never grows angry at its own side.
            w.clearAnger(owner);
            for (Mob other : of(owner)) if (other != w) w.clearAnger(other);
            // Anger fades; while its master fights, keep it furious at the foe.
            if (s.target != null && level.getGameTime() % 20 == 0) w.increaseAngerAt(s.target, 20, false);
        }
        if (s.target != null && (!s.target.isAlive() || s.target.level() != level || s.target.distanceTo(owner) > LOST)) {
            s.target = null;
            s.hunting = false;
        }
        hunt(m, owner, s, level);
        LivingEntity current = m.getTarget();
        if (current != s.target) aim(m, s.target);

        boolean dragon = m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon;
        if (s.errand != null) runErrand(m, owner, s, level.getGameTime(), dragon);
        else if (s.post != null && s.target == null && !dragon && !kept(m) && m.position().distanceTo(s.post) > 4 && level.getGameTime() % 40 == 0) {
            m.getNavigation().moveTo(s.post.x, s.post.y, s.post.z, 1.0); // back to its post
        }
        if (dragon) DragonFamiliar.tick((net.minecraft.world.entity.boss.enderdragon.EnderDragon) m, owner, s.target, s.errand, s.post);
        double d = m.distanceTo(owner);
        double lost = dragon ? DragonFamiliar.LOST : LOST;
        // On an errand or a post it may be far from its master; only losing them altogether calls it back.
        // A colossus walks after its master in its own time: it is only fetched when it has lost them altogether.
        double leash = s.errand != null || s.post != null || m instanceof AlchemicalColossus ? lost
                : dragon ? DragonFamiliar.CALM_LEASH : s.target != null ? COMBAT_LEASH : CALM_LEASH;
        if (d > lost) {
            s.errand = null;
            s.post = null;
        }
        if (d > lost) {
            if (teleportNear(m, owner)) s.failedRescues = 0;
            else if (++s.failedRescues >= RESCUE_TRIES && !kept(m)) fade(m);
        } else if (d > leash) {
            teleportNear(m, owner);
        }
    }

    /** One step of a {@code gather} order: walk (or fly) there, give up when it stops getting closer or takes too long. */
    private static void runErrand(Mob m, Player owner, State s, long now, boolean dragon) {
        Vec3 dest = s.errand;
        double dist = m.position().distanceTo(dest);
        if (dist < (dragon ? 12 : 2)) {
            s.errand = null;
            m.getNavigation().stop();
            // Called to its master it follows again; sent anywhere else it stands guard there.
            if (dest.distanceTo(owner.position()) > 6) s.post = dest;
            return;
        }
        if (dist < s.bestDistance - 0.5) {
            s.bestDistance = dist;
            s.lastProgress = now;
        }
        boolean stuck = now - s.lastProgress > 100 || now - s.errandSince > 600;
        if (!dragon && !kept(m) && !stuck && (now - s.errandSince) % 20 == 0 && !m.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.2)) {
            stuck = now - s.errandSince >= 40; // no path at all: a few tries, then give up
        }
        if (stuck) {
            s.errand = null;
            m.getNavigation().stop();
            owner.sendOverlayMessage(net.minecraft.network.chat.Component.literal("⚔ " + m.getName().getString() + " 到不了那里，放弃了")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    private static void fade(Mob m) {
        if (m.level() instanceof ServerLevel level) {
            Vfx.burst(level, m.position().add(0, m.getBbHeight() / 2, 0), dev.logos.lang.Element.ARCANE, 15);
        }
        dismiss(m);
    }

    /** Like a tamed wolf: a free, safe spot 2–3 blocks from the master. */
    static boolean teleportNear(Mob m, Player owner) {
        if (m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon d) return DragonFamiliar.teleportNear(d, owner);
        BlockPos base = owner.blockPosition();
        var r = m.getRandom();
        for (int attempt = 0; attempt < 10; attempt++) {
            int dx = r.nextIntBetweenInclusive(-3, 3), dz = r.nextIntBetweenInclusive(-3, 3);
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
            BlockPos at = base.offset(dx, r.nextIntBetweenInclusive(-1, 1), dz);
            boolean flies = m.isNoGravity();
            if (!flies && !m.level().getBlockState(at.below()).isFaceSturdy(m.level(), at.below(), net.minecraft.core.Direction.UP)) continue;
            if (!m.level().noCollision(m, m.getBoundingBox().move(Vec3.atBottomCenterOf(at).subtract(m.position())))) continue;
            m.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, m.getYRot(), m.getXRot());
            m.getNavigation().stop();
            return true;
        }
        return false;
    }

    /** Walks after the master when there is nothing to fight. */
    private static final class FollowMaster extends Goal {
        private final PathfinderMob mob;
        private int repath;

        FollowMaster(PathfinderMob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private @Nullable Player master() {
            Bond b = bondOf(mob);
            return b == null ? null : mob.level().getPlayerByUUID(b.owner());
        }

        @Override
        public boolean canUse() {
            Player p = master();
            return p != null && orderedTarget(mob) == null && !hasOrders(mob) && !dormant() && mob.distanceToSqr(p) > 6 * 6;
        }

        @Override
        public boolean canContinueToUse() {
            Player p = master();
            return p != null && orderedTarget(mob) == null && !hasOrders(mob) && !dormant() && mob.distanceToSqr(p) > 2.5 * 2.5;
        }

        private boolean dormant() {
            return mob instanceof KeptFamiliar k && k.isDormant();
        }

        @Override
        public void tick() {
            Player p = master();
            if (p == null) return;
            mob.getLookControl().setLookAt(p, 10, mob.getMaxHeadXRot());
            if (--repath <= 0) {
                repath = 10;
                mob.getNavigation().moveTo(p, 1.2);
            }
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }
    }

    static void forget(Mob m) {
        LIVE.remove(m);
        STATE.remove(m);
    }

    static Set<Mob> live() {
        return LIVE;
    }
}
