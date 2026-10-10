package dev.logos.gametest;

import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Summoned familiars: unlocking by kills, loyalty, fighting what the master fights, credit, drops and griefing. */
final class FamiliarGameTests {
    private FamiliarGameTests() {}

    private static ServerPlayer master(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        p.setGameMode(GameType.SURVIVAL);
        // A mock connection never reports a loaded client, and such players take no damage at all.
        p.connection.markClientLoaded();
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    private static Mob summon(GameTestHelper h, ServerPlayer p, String creature) {
        var before = new java.util.HashSet<>(Familiars.of(p));
        SpellInstance inst = Casting.cast(p, Casting.compile("(spell :mana 2000 (summon " + creature + " :at (pos-of caster)))"), null, null);
        h.assertTrue(inst != null && !inst.dead, "summon " + creature + " failed: " + (inst == null ? "refused" : inst.endReason()));
        // The familiars are an unordered set: the new one is the one that was not there before.
        var fresh = Familiars.of(p).stream().filter(m -> !before.contains(m)).toList();
        h.assertTrue(fresh.size() == 1, "expected one new familiar after summoning " + creature + ", got " + fresh.size());
        return fresh.getFirst();
    }

    /** Locked until 49 kills; then it comes, bound to its master, and costs max health × 2. */
    static void unlockAndSummon(GameTestHelper h) {
        ServerPlayer p = master(h);
        SpellInstance early = Casting.cast(p, Casting.compile("(spell :mana 2000 (summon zombie))"), null, null);
        h.assertTrue(early != null && early.dead && early.endReason().contains("0 / 49"), "a zombie must be locked before 49 kills: "
                + (early == null ? "refused" : early.endReason()));
        p.getStats().setValue(p, Stats.ENTITY_KILLED.get(EntityType.ZOMBIE), 49);
        Mob z = summon(h, p, "zombie");
        h.assertTrue(z.getType() == EntityType.ZOMBIE && Familiars.isFamiliar(z), "the familiar should be a bound zombie");
        h.assertTrue(z.isPersistenceRequired(), "a familiar must not despawn");
        h.succeed();
    }

    /** It never takes its master (or anyone else) as a target of its own; it takes what attacks its master. */
    static void loyalTargeting(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE); // skips the kill requirement
        Mob z = summon(h, p, "zombie");
        p.setGameMode(GameType.SURVIVAL);
        z.setTarget(p);
        h.assertTrue(z.getTarget() != p, "a familiar must never target its master");
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(5.5, 2, 5.5));
        husk.setNoAi(true);
        z.setTarget(husk);
        h.assertTrue(z.getTarget() == null, "nor pick a target of its own before its master fights");
        // Magic: damage from mob attacks scales with difficulty and is nothing in peaceful.
        p.hurtServer(h.getLevel(), p.damageSources().indirectMagic(husk, husk), 1);
        h.assertTrue(z.getTarget() == husk, "it should turn on what attacked its master, has " + z.getTarget());
        h.succeed();
    }

    /** No friendly fire; its kills count as its master's, like a tamed wolf's. */
    static void friendlyFireAndCredit(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        Mob z = summon(h, p, "zombie");
        p.setGameMode(GameType.SURVIVAL);
        float before = p.getHealth();
        p.hurtServer(h.getLevel(), p.damageSources().indirectMagic(z, z), 6);
        h.assertTrue(p.getHealth() == before, "a familiar must not hurt its master");
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(5.5, 2, 5.5));
        husk.setNoAi(true);
        int total = p.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS));
        int husks = p.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.HUSK));
        husk.hurtServer(h.getLevel(), husk.damageSources().mobAttack(z), 1000);
        h.assertTrue(!husk.isAlive(), "the husk should be dead");
        h.assertTrue(husk.getKillCredit() == p, "the kill should be credited to the master, like a tamed wolf's");
        h.assertTrue(p.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)) == total + 1, "it should count in the master's mob kills");
        // Also like a wolf's: the per-creature kill count (which unlocks summons) only grows by the master's own hand.
        h.assertTrue(p.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.HUSK)) == husks, "a familiar's kill must not advance unlocks");
        h.succeed();
    }

    /** Dying, it drops only what it picked up: not its loot, not its experience; dismissed, the same. */
    static void dropsOnlyPickedUp(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        Mob z = summon(h, p, "zombie");
        z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
        z.setGuaranteedDrop(EquipmentSlot.MAINHAND); // what picking it up does
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // spawn gear: not its to give
        Vec3 at = z.position();
        z.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
        AABB around = new AABB(at, at).inflate(3);
        var items = h.getLevel().getEntitiesOfClass(ItemEntity.class, around);
        h.assertTrue(items.size() == 1 && items.getFirst().getItem().is(Items.DIAMOND),
                "only the picked-up diamond should drop, got " + items.stream().map(i -> i.getItem().toString()).toList());
        h.assertTrue(h.getLevel().getEntitiesOfClass(ExperienceOrb.class, around).isEmpty(), "no experience from a familiar");
        items.forEach(ItemEntity::discard);

        Mob z2 = summon(h, p, "zombie");
        z2.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD));
        z2.setGuaranteedDrop(EquipmentSlot.MAINHAND);
        SpellInstance d = Casting.cast(p, Casting.compile("(dismiss (entities :radius 4 :filter (kind living)))"), null, null);
        h.assertTrue(d != null && !d.dead, "dismiss failed");
        h.assertTrue(!z2.isAlive(), "the familiar should be gone");
        h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(z2.position(), z2.position()).inflate(3)).stream()
                .anyMatch(i -> i.getItem().is(Items.EMERALD)), "dismissed, it should leave the emerald it picked up");
        h.succeed();
    }

    /** The dragon familiar is driven onto its holding pattern around its master; killed, it vanishes with no experience. */
    static void dragonCirclesMaster(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        // Dragons need sky and ticking chunks around the test.
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            BlockPos b = h.absolutePos(new BlockPos(x * 16, 0, z * 16));
            h.getLevel().setChunkForced(b.getX() >> 4, b.getZ() >> 4, true);
        }
        Mob m = summon(h, p, "ender_dragon");
        var dragon = (net.minecraft.world.entity.boss.enderdragon.EnderDragon) m;
        h.runAfterDelay(10, () -> {
            var phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
            h.assertTrue(phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.HOLDING_PATTERN,
                    "a familiar dragon should be circling, is in " + phase);
            h.assertTrue(dragon.getFightOrigin().distSqr(p.blockPosition()) < 32 * 32, "its flight should centre on its master, origin " + dragon.getFightOrigin());
            Vec3 at = dragon.position();
            // Only players (and a few damage types) can hurt a dragon at all, as in vanilla.
            dragon.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(p), Float.MAX_VALUE);
            h.assertTrue(dragon.isRemoved(), "a familiar dragon should vanish at once, not play its death");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(at, at).inflate(16)).isEmpty(), "and give no experience");
            h.succeed();
        });
    }

    /** In the End: no false dragon while the true one lives; the true dragon's return unmakes any false one there. */
    static void dragonThroneRules(GameTestHelper h) {
        ServerPlayer p = master(h);
        var end = h.getLevel().getServer().getLevel(Level.END);
        h.assertTrue(end != null, "the test server has no End");
        var fight = end.getDragonFight();
        if (fight != null && !((dev.logos.mixin.EnderDragonFightAccessor) fight).logos$dragonKilled()) {
            h.assertTrue(dev.logos.runtime.DragonFamiliar.THRONE_TAKEN.equals(dev.logos.runtime.DragonFamiliar.forbidden(end)),
                    "while the true dragon lives, a dragon may not be summoned in the End");
        }
        h.assertTrue(dev.logos.runtime.DragonFamiliar.forbidden(h.getLevel()) == null, "outside the End there is no throne to take");

        // A false dragon in the End, then the true one returns.
        BlockPos spot = new BlockPos(2000, 120, 2000);
        end.setChunkForced(spot.getX() >> 4, spot.getZ() >> 4, true);
        var fake = net.minecraft.world.entity.EntityType.ENDER_DRAGON.create(end, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        h.assertTrue(fake != null, "could not make a dragon");
        fake.snapTo(spot.getX(), spot.getY(), spot.getZ(), 0, 0);
        Familiars.bindForTest(fake, p);
        end.addFreshEntity(fake);
        if (end.getDragonFight() == null) {
            h.succeed(); // no End fight in this world: nothing can reclaim the throne
            return;
        }
        var trueDragon = net.minecraft.world.entity.EntityType.ENDER_DRAGON.create(end, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        trueDragon.snapTo(spot.getX() + 40, spot.getY(), spot.getZ(), 0, 0);
        end.addFreshEntity(trueDragon);
        h.assertTrue(fake.isRemoved(), "when the true dragon returns, the summoned one must be unmade");
        trueDragon.discard();
        h.succeed();
    }

    private static void forceAround(GameTestHelper h, int chunks) {
        for (int x = -chunks; x <= chunks; x++) for (int z = -chunks; z <= chunks; z++) {
            BlockPos b = h.absolutePos(new BlockPos(x * 16, 0, z * 16));
            h.getLevel().setChunkForced(b.getX() >> 4, b.getZ() >> 4, true);
        }
    }

    @SuppressWarnings("removal")
    private static ServerPlayer stranger(GameTestHelper h) {
        ServerPlayer o = h.makeMockServerPlayerInLevel();
        o.connection.markClientLoaded();
        return o;
    }

    /** A villager familiar carries its master, who steers it; nobody else climbs on; a zombie is no mount. */
    static void rideGroundMount(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        Mob v = summon(h, p, "villager");
        Mob z = summon(h, p, "zombie");
        h.assertTrue(!dev.logos.runtime.FamiliarRiding.rideable(z), "a zombie is not in #logos:rideable_familiars");
        ServerPlayer other = stranger(h);
        h.assertTrue(!dev.logos.runtime.FamiliarRiding.mayMount(v, other), "only its master may ride it");
        h.assertTrue(dev.logos.runtime.FamiliarRiding.mayMount(v, p), "its master may ride it");
        h.assertTrue(p.startRiding(v, false, true), "mounting failed");
        h.assertTrue(v.getControllingPassenger() == p, "its master should steer it, controller " + v.getControllingPassenger());
        h.assertTrue(!other.startRiding(v, false, true), "a second rider must not fit");
        p.stopRiding();
        h.succeed();
    }

    /** The dragon takes its master and one companion, who sits behind; a third is turned away. */
    static void dragonTwoSeats(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        forceAround(h, 2);
        var dragon = (net.minecraft.world.entity.boss.enderdragon.EnderDragon) summon(h, p, "ender_dragon");
        ServerPlayer partner = stranger(h), third = stranger(h);
        h.assertTrue(!dev.logos.runtime.FamiliarRiding.mayMount(dragon, partner), "the companion may only board behind its master");
        h.assertTrue(p.startRiding(dragon, false, true), "the master could not mount the dragon");
        h.assertTrue(dev.logos.runtime.FamiliarRiding.mayMount(dragon, partner) && partner.startRiding(dragon, false, true), "the companion could not board");
        h.assertTrue(!dev.logos.runtime.FamiliarRiding.mayMount(dragon, third) && !third.startRiding(dragon, false, true), "a third rider must not fit");
        dragon.positionRider(p);
        dragon.positionRider(partner);
        double apart = p.position().distanceTo(partner.position());
        h.assertTrue(apart > 1 && apart < 2.5, "the two riders should sit one behind the other, " + apart + " apart");
        h.assertTrue(p.position().distanceTo(dragon.position()) < 8, "the riders should sit on the dragon");
        h.succeed();
    }

    /** A baby familiar does not grow up on its own, but the grow spell still ages it. */
    static void ageFrozenButSpellsWork(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        forceAround(h, 1);
        var v = (net.minecraft.world.entity.npc.villager.Villager) summon(h, p, "villager");
        v.setAge(-24000);
        h.runAfterDelay(20, () -> {
            // The freeze takes hold from its next tick: one step at most, not twenty.
            h.assertTrue(v.getAge() <= -23999, "a familiar must not grow up by itself, age " + v.getAge());
            SpellInstance g = Casting.cast(p, Casting.compile("(grow (pos-of caster) 4)"), null, null);
            h.assertTrue(g != null && !g.dead, "grow failed");
            h.assertTrue(v.getAge() >= -18000 && v.getAge() <= -17999, "grow should still age it by 6000, age " + v.getAge());
            h.succeed();
        });
    }

    private static Mob named(GameTestHelper h, ServerPlayer p, String creature, String name) {
        var before = new java.util.HashSet<>(Familiars.of(p));
        SpellInstance inst = Casting.cast(p, Casting.compile("(spell :mana 2000 (summon " + creature + " :at (pos-of caster) :name \"" + name + "\"))"), null, null);
        h.assertTrue(inst != null && !inst.dead, "summon failed: " + (inst == null ? "refused" : inst.endReason()));
        return Familiars.of(p).stream().filter(m -> !before.contains(m)).findFirst().orElseThrow();
    }

    /** Named at summoning, picked out by name; dismiss with no target sends away the last one summoned. */
    static void namesAndDismiss(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        Mob a = named(h, p, "zombie", "甲");
        Mob b = named(h, p, "zombie", "乙");
        h.assertTrue(a.getCustomName() != null && a.getCustomName().getString().equals("甲"), "the familiar should carry its name");
        h.assertTrue(Familiars.latest(p, "甲") == a && Familiars.latest(p, null) == b, "(familiar \"甲\") and (familiar) should pick 甲 and the latest");
        Casting.cast(p, Casting.compile("(dismiss)"), null, null);
        h.assertTrue(!b.isAlive() && a.isAlive(), "dismiss with no target should send away the last summoned (乙) only");
        Casting.cast(p, Casting.compile("(dismiss (familiar \"甲\"))"), null, null);
        h.assertTrue(!a.isAlive(), "dismiss (familiar \"甲\") should send 甲 away");
        h.succeed();
    }

    /** gather walks a familiar to its master; sent to a spot it cannot reach, it tries a while and gives up. */
    static void gatherWalksOrGivesUp(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        forceAround(h, 1);
        // A sealed air pocket nobody can walk into.
        BlockPos pocket = new BlockPos(7, 2, 2);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx != 0 || dy != 0 || dz != 0) h.setBlock(pocket.offset(dx, dy, dz), Blocks.STONE);
        }
        Mob z = named(h, p, "zombie", "跑腿");
        z.snapTo(h.absoluteVec(new Vec3(6.5, 2, 8.5)));
        Casting.cast(p, Casting.compile("(gather)"), null, null);
        h.assertTrue(Familiars.hasOrders(z), "gather should give it an errand");
        h.runAfterDelay(80, () -> {
            h.assertTrue(z.distanceTo(p) < 3.5, "it should have walked to its master, " + z.distanceTo(p) + " away");
            h.assertTrue(!Familiars.hasOrders(z), "arrived by its master, it follows again (no post)");
            Vec3 sealed = Vec3.atBottomCenterOf(h.absolutePos(pocket));
            h.assertTrue(Familiars.sendTo(z, sealed), "the pocket is near enough to try");
            h.runAfterDelay(90, () -> {
                h.assertTrue(!Familiars.hasOrders(z), "it should give up on a spot it cannot reach");
                h.assertTrue(z.position().distanceTo(sealed) > 1, "and not be in the sealed pocket");
                h.succeed();
            });
        });
    }

    /**
     * A warden attacks only what it is furious at, and on its own it listens its way there slowly. As a
     * familiar it turns furious at once on what its master fights, stays on it, and never at its master.
     */
    static void wardenFightsAtOnce(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        forceAround(h, 1);
        var w = (net.minecraft.world.entity.monster.warden.Warden) summon(h, p, "warden");
        p.setGameMode(GameType.SURVIVAL);
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(6.5, 2, 6.5));
        husk.setNoAi(true);
        husk.hurtServer(h.getLevel(), husk.damageSources().playerAttack(p), 1); // the master strikes first
        h.assertTrue(w.getTarget() == husk, "the warden should take its master's foe at once, has " + w.getTarget());
        h.assertTrue(w.getAngerLevel().isAngry(), "and be furious at it at once, not merely listening");
        h.runAfterDelay(40, () -> {
            h.assertTrue(w.getTarget() == husk || !husk.isAlive(), "it should keep after the foe instead of dropping it, has " + w.getTarget());
            h.assertTrue(w.getTarget() != p, "and never turn on its master");
            h.succeed();
        });
    }

    /** Wardens keep their truce, except a familiar warden against the very warden its master fights. */
    static void wardenFightsWildWarden(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        forceAround(h, 1);
        var w = (net.minecraft.world.entity.monster.warden.Warden) summon(h, p, "warden");
        p.setGameMode(GameType.SURVIVAL);
        var wild = h.spawn(EntityType.WARDEN, new Vec3(7.5, 2, 7.5));
        wild.setNoAi(true);
        h.assertTrue(!wild.canTargetEntity(w), "a wild warden still never targets a warden");
        h.assertTrue(!w.canTargetEntity(wild), "nor does a familiar warden pick one by itself");
        wild.hurtServer(h.getLevel(), wild.damageSources().playerAttack(p), 1); // the master strikes it
        h.assertTrue(w.getTarget() == wild && w.getAngerLevel().isAngry(), "the familiar warden should turn furious on the warden its master fights, has "
                + w.getTarget() + " / " + w.getAngerLevel());
        h.runAfterDelay(40, () -> {
            h.assertTrue(w.getTarget() == wild || !wild.isAlive(), "and keep after it, has " + w.getTarget());
            h.succeed();
        });
    }

    /** sic orders the chosen familiar (by name, or the latest) onto a target; never onto its master's side. */
    static void sicOrdersAttack(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        forceAround(h, 1);
        Mob a = named(h, p, "zombie", "甲");
        var b = (net.minecraft.world.entity.monster.warden.Warden) named(h, p, "warden", "乙");
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(6.5, 2, 6.5));
        husk.setNoAi(true);
        // The caster's own familiars are not enemies: nearest finds the husk, not 甲 or 乙.
        Casting.cast(p, Casting.compile("(sic (nearest :radius 12) :familiar (familiar \"甲\"))"), null, null);
        h.assertTrue(a.getTarget() == husk, "甲 should be ordered onto the husk, has " + a.getTarget());
        h.assertTrue(b.getTarget() == null, "乙 was not ordered");
        Casting.cast(p, Casting.compile("(sic (nearest :radius 12))"), null, null);
        h.assertTrue(b.getTarget() == husk && b.getAngerLevel().isAngry(), "with no familiar named, the latest (乙) is ordered, furious at once");
        Casting.cast(p, Casting.compile("(sic (familiar \"乙\") :familiar (familiar \"甲\"))"), null, null);
        h.assertTrue(a.getTarget() == husk, "a familiar must not be ordered onto its master's other familiar");
        h.succeed();
    }

    /** A hunting stance: unordered, it goes after the nearest hostile creature, never the cow nearer by; (kind none) calls it off. */
    static void stanceHunts(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        forceAround(h, 1);
        SpellInstance inst = Casting.cast(p, Casting.compile("(spell :mana 2000 (summon zombie :at (pos-of caster) :name \"猎手\" :hunt (kind hostile) :radius 12))"), null, null);
        h.assertTrue(inst != null && !inst.dead, "summon with :hunt failed: " + (inst == null ? "refused" : inst.endReason()));
        Mob z = Familiars.latest(p, "猎手");
        h.assertTrue(z != null && Familiars.hunts(z), "summoned with :hunt, it should hunt");
        var cow = h.spawn(EntityType.COW, new Vec3(3.5, 2, 3.5));
        cow.setNoAi(true);
        Husk husk = h.spawn(EntityType.HUSK, new Vec3(7.5, 2, 7.5));
        husk.setNoAi(true);
        h.runAfterDelay(25, () -> {
            h.assertTrue(z.getTarget() == husk, "it should go after the husk on its own, has " + z.getTarget());
            Casting.cast(p, Casting.compile("(stance :hunt (kind none))"), null, null);
            h.runAfterDelay(25, () -> {
                h.assertTrue(z.getTarget() == null, "(kind none) should call it off, has " + z.getTarget());
                h.succeed();
            });
        });
    }

    /** A familiar creeper's blast breaks no blocks and spares its master. */
    static void creeperBreaksNothing(GameTestHelper h) {
        ServerPlayer p = master(h);
        p.setGameMode(GameType.CREATIVE);
        Mob c = summon(h, p, "creeper");
        p.setGameMode(GameType.SURVIVAL);
        BlockPos glass = new BlockPos(5, 2, 5);
        h.setBlock(glass, Blocks.GLASS);
        Vec3 at = h.absoluteVec(new Vec3(5.5, 2.5, 4.5));
        float before = p.getHealth();
        h.getLevel().explode(c, at.x, at.y, at.z, 3, Level.ExplosionInteraction.MOB);
        h.assertTrue(h.getBlockState(glass).is(Blocks.GLASS), "the familiar creeper's blast broke a block");
        h.assertTrue(p.getHealth() == before, "and must spare its master");
        h.succeed();
    }
}
