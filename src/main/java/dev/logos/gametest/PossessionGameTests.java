package dev.logos.gametest;

import dev.logos.LogosMod;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Possession;
import dev.logos.runtime.PossessionInventory;
import dev.logos.runtime.SpellInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 附身: riding inside a creature, the body left behind, and every way it ends. */
final class PossessionGameTests {
    private PossessionGameTests() {}

    /** A survival player on a stone floor at the structure's corner, with mana to spare. */
    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        FamiliarGameTests.forceAround(h, 1);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.connection.markClientLoaded();
        Vec3 pos = h.absoluteVec(new Vec3(0.5, 2, 0.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    private static Mannequin bodyNear(GameTestHelper h, Vec3 at) {
        var bodies = h.getLevel().getEntitiesOfClass(Mannequin.class, new AABB(at, at).inflate(1));
        h.assertTrue(bodies.size() == 1, "one body should stand where the player stood, found " + bodies.size());
        return bodies.getFirst();
    }

    /**
     * The rune puts the player inside the pig for current health / 5 × √seconds; the hidden player
     * takes no damage, the body's goes to them, and what went for them goes for the body. Sneaking
     * puts them back in their body, which is gone.
     */
    static void body(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        Vec3 start = p.position();
        BlockPos at = new BlockPos(6, 2, 6);
        var pig = h.spawn(EntityType.PIG, at);
        pig.setHealth(5);
        SpellInstance inst = Casting.cast(p, Casting.compile("(possess (nearest :radius 2) 4)"), null, Vec3.atCenterOf(h.absolutePos(at)));
        h.assertTrue(inst != null && !inst.dead, "the cast should go through");
        // 5 / 5 × √4 = 2, and 1 for nearest.
        h.assertTrue(Math.abs(inst.injected - inst.mana - 3) < 1e-6, "possessing should cost 3 with the selector, cost " + (inst.injected - inst.mana));
        h.assertTrue(p.getVehicle() == pig && Possession.possessing(p) && Possession.possessor(pig) == p, "the player should be inside the pig");
        Mannequin body = bodyNear(h, start);

        float health = p.getHealth();
        p.hurtServer(level, level.damageSources().generic(), 4);
        h.assertTrue(p.getHealth() == health, "the hidden player should take no damage of their own");
        body.hurtServer(level, level.damageSources().generic(), 4);
        h.assertTrue(Math.abs(p.getHealth() - (health - 4)) < 1e-4, "the body's damage should go to the player, health " + p.getHealth());
        h.assertTrue(body.isAlive(), "the body itself never dies");

        // A mock player always counts as creative, which mobs never target; so ask the event directly.
        var wolf = h.spawn(EntityType.WOLF, new BlockPos(2, 2, 6));
        var redirected = net.neoforged.neoforge.common.CommonHooks.onLivingChangeTarget(wolf, p,
                net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent.LivingTargetType.MOB_TARGET).getNewAboutToBeSetTarget();
        h.assertTrue(redirected == body, "a creature going for the player should go for their body, got " + redirected);
        wolf.setTarget(body);
        h.assertTrue(wolf.getTarget() == body, "the body should be a target creatures can keep");

        p.setShiftKeyDown(true);
        h.succeedWhen(() -> {
            h.assertTrue(!Possession.possessing(p) && p.getVehicle() == null, "sneaking should end the possession");
            h.assertTrue(body.isRemoved(), "the body should be gone once the player is back in it");
            h.assertTrue(p.position().distanceTo(start) < 0.01, "the player should be back where their body stood, at " + p.position());
            h.assertTrue(pig.isAlive() && Possession.possessor(pig) == null, "the pig should be free and unharmed");
        });
    }

    /** The possessed creature's death throws the player back into their body, a quarter of their max health the poorer. */
    static void backlash(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        Vec3 start = p.position();
        var pig = h.spawn(EntityType.PIG, new BlockPos(6, 2, 6));
        h.assertTrue(Possession.start(p, pig, 30), "the possession should start");
        Mannequin body = bodyNear(h, start);
        pig.kill(level);
        h.assertTrue(!Possession.possessing(p) && body.isRemoved(), "the pig's death should end the possession");
        h.assertTrue(p.position().distanceTo(start) < 0.01, "the player should be back in their body, at " + p.position());
        float expected = p.getMaxHealth() * (1 - Possession.BACKLASH);
        h.assertTrue(Math.abs(p.getHealth() - expected) < 1e-4, "the player should lose a quarter of their max health, health " + p.getHealth());
        h.succeed();
    }

    /** It wears off by itself; only one at a time, only on mobs within range, never on a player or a boss; free on one's own familiar. */
    static void limits(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        Vec3 start = p.position();
        var pig = h.spawn(EntityType.PIG, new BlockPos(6, 2, 6));
        h.assertTrue(Possession.cost(pig, p, 9) == pig.getHealth() / 5 * 3, "the price should be health / 5 × √seconds");
        pig.setData(LogosMod.FAMILIAR, new Familiars.Bond(p.getUUID(), Long.MAX_VALUE));
        h.assertTrue(Possession.cost(pig, p, 9) == 0, "one's own familiar should be free");

        var far = EntityType.PIG.create(level, EntitySpawnReason.TRIGGERED);
        far.setPos(start.add(Possession.RANGE + 2, 0, 0));
        h.assertTrue(!Possession.canStart(p, far), "a mob beyond 128 blocks should be out of reach");
        var wither = EntityType.WITHER.create(level, EntitySpawnReason.TRIGGERED);
        wither.setPos(start.add(4, 0, 4));
        h.assertTrue(!Possession.canStart(p, wither), "a boss should not be possessable");
        @SuppressWarnings("removal")
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        h.assertTrue(!Possession.canStart(p, other), "a player should not be possessable");

        h.assertTrue(Possession.start(p, pig, 0.5), "the possession should start");
        var cow = h.spawn(EntityType.COW, new BlockPos(2, 2, 6));
        h.assertTrue(!Possession.canStart(p, cow), "one possession at a time");
        h.succeedWhen(() -> {
            h.assertTrue(!Possession.possessing(p), "half a second should wear off by itself");
            h.assertTrue(p.position().distanceTo(start) < 0.01, "the player should be back in their body, at " + p.position());
        });
    }

    /**
     * The inventory key opens the creature's things: its equipment (each slot taking what fits, and
     * what is put on kept on death) and what it carries, a villager's pockets or a donkey's chest.
     * It closes once the possession ends.
     */
    static void inventory(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var villager = h.spawn(EntityType.VILLAGER, new Vec3(4.5, 2, 4.5));
        villager.getInventory().setItem(0, new ItemStack(Items.WHEAT, 5));
        h.assertTrue(Possession.start(p, villager, 30), "the possession should start");
        // A mock player cannot be sent the screen; the server's side of the menu is what holds the items.
        var menu = PossessionInventory.menu(1, p.getInventory(), villager);
        h.assertTrue(menu.stillValid(p), "the menu should be valid while the player is inside");
        long pockets = menu.slots.stream().filter(s -> s.container == villager.getInventory()).count();
        h.assertTrue(pockets == 8, "all eight of the villager's pockets should be there, found " + pockets);
        h.assertTrue(menu.slots.stream().anyMatch(s -> s.container == villager.getInventory() && s.getItem().is(Items.WHEAT)),
                "the wheat in its pocket should show");
        // Equipment first, in order: head, chest, legs, feet, main hand, off hand.
        Slot head = menu.slots.get(0), hand = menu.slots.get(4);
        h.assertTrue(head.mayPlace(new ItemStack(Items.IRON_HELMET)) && !head.mayPlace(new ItemStack(Items.IRON_SWORD)), "the head slot should take only headgear");
        h.assertTrue(hand.mayPlace(new ItemStack(Items.WHEAT)), "a hand should take anything");
        hand.set(new ItemStack(Items.IRON_SWORD));
        h.assertTrue(villager.getMainHandItem().is(Items.IRON_SWORD), "what is put in the hand slot should be in its hand");
        h.assertTrue(villager.getDropChances().isPreserved(EquipmentSlot.MAINHAND), "what is put on it should be kept on its death");
        Possession.end(p, false);
        h.assertTrue(!menu.stillValid(p), "the menu should close once the possession ends");

        // A chest goes on as a player puts it on (that is when the donkey's chest inventory is made).
        var donkey = h.spawn(EntityType.DONKEY, new Vec3(6.5, 2, 2.5));
        donkey.setTamed(true);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHEST));
        donkey.mobInteract(p, InteractionHand.MAIN_HAND);
        h.assertTrue(donkey.hasChest(), "the donkey should take the chest");
        h.assertTrue(Possession.start(p, donkey, 30), "the donkey possession should start");
        var mount = PossessionInventory.menu(2, p.getInventory(), donkey);
        long chest = mount.slots.stream().filter(s -> s.container == donkey.getInventory()).count();
        h.assertTrue(chest == donkey.getInventorySize() && chest > 0, "the donkey's chest should be there, found " + chest + " of " + donkey.getInventorySize());
        Possession.end(p, false);
        h.succeed();
    }

    /**
     * The instinct key lets the wolf's own AI act: it goes for the cow the player aimed at and bites
     * it, never its possessor; the player's clicks do nothing meanwhile. Pressed again it takes the
     * reins back; with the cow dead, the wolf gives them back by itself and drops its target.
     */
    static void instinct(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var wolf = h.spawn(EntityType.WOLF, new Vec3(2.5, 2, 2.5));
        var cow = h.spawn(EntityType.COW, new Vec3(6.5, 2, 6.5));
        h.assertTrue(Possession.start(p, wolf, 30), "the possession should start");
        h.assertTrue(wolf.getControllingPassenger() == p, "the player should steer the wolf");
        Possession.instinct(p, p, true);
        h.assertTrue(Possession.instinct(wolf) && wolf.getTarget() == null, "the wolf should never be set on its possessor");
        Possession.instinct(p, null, false);
        h.assertTrue(!Possession.instinct(wolf) && wolf.getControllingPassenger() == p, "pressing again should give the reins back");

        float full = cow.getHealth();
        Possession.instinct(p, cow, true);
        h.assertTrue(wolf.getControllingPassenger() == null, "nobody should steer the wolf while its instincts act");
        h.assertTrue(wolf.getTarget() == cow, "the wolf should go for the cow the player aimed at, got " + wolf.getTarget());
        Possession.strike(p, cow);
        h.assertTrue(cow.getHealth() == full, "the player's own blows should do nothing while its instincts act");
        h.succeedWhen(() -> {
            h.assertTrue(!cow.isAlive(), "the wolf should run to the cow and bite it to death on its own");
            h.assertTrue(!Possession.instinct(wolf) && wolf.getControllingPassenger() == p && wolf.getTarget() == null,
                    "with the cow dead the wolf should give the reins back and drop its target");
            h.assertTrue(Possession.possessing(p), "the player should still be inside the wolf");
        });
    }

    /** With nothing aimed at, the creature acts on its own for ten seconds, then the player steers again. */
    static void instinctTime(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var pig = h.spawn(EntityType.PIG, new Vec3(4.5, 2, 4.5));
        h.assertTrue(Possession.start(p, pig, 60), "the possession should start");
        Possession.instinct(p, null, true);
        h.assertTrue(Possession.instinct(pig) && pig.getControllingPassenger() == null, "its instincts should act with nothing aimed at");
        long from = h.getLevel().getGameTime();
        h.succeedWhen(() -> {
            h.assertTrue(!Possession.instinct(pig) && pig.getControllingPassenger() == p, "the instinct should run out by itself");
            long took = h.getLevel().getGameTime() - from;
            h.assertTrue(took >= Possession.INSTINCT_TICKS, "the instinct should last ten seconds, ran out after " + took + " ticks");
            h.assertTrue(Possession.possessing(p), "the player should still be inside the pig");
        });
    }

    private static int around(GameTestHelper h, Class<? extends net.minecraft.world.entity.Entity> type, net.minecraft.world.entity.Entity at) {
        return h.getLevel().getEntitiesOfClass(type, at.getBoundingBox().inflate(24)).size();
    }

    /**
     * The use button is a creature's own skill when it has one: the blaze's three fireballs (then a
     * cooldown), the ghast's fireball, the breeze's wind charge, the evoker's line of fangs, the
     * shulker's bullet (only with something aimed at, and no cooldown spent without).
     */
    static void skillShots(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var blaze = h.spawn(EntityType.BLAZE, new Vec3(4.5, 2, 4.5));
        h.assertTrue(Possession.start(p, blaze, 30), "the blaze possession should start");
        Possession.shoot(p, null);
        h.assertTrue(around(h, SmallFireball.class, blaze) == 3, "the blaze should throw three small fireballs, got " + around(h, SmallFireball.class, blaze));
        Possession.shoot(p, null);
        h.assertTrue(around(h, SmallFireball.class, blaze) == 3, "a second throw should wait for the cooldown");
        Possession.end(p, false);

        var ghast = h.spawn(EntityType.GHAST, new Vec3(4.5, 4, 4.5));
        h.assertTrue(Possession.start(p, ghast, 30), "the ghast possession should start");
        Possession.shoot(p, null);
        h.assertTrue(around(h, LargeFireball.class, ghast) == 1, "the ghast should fire one fireball");
        Possession.end(p, false);

        var breeze = h.spawn(EntityType.BREEZE, new Vec3(2.5, 2, 6.5));
        h.assertTrue(Possession.start(p, breeze, 30), "the breeze possession should start");
        Possession.shoot(p, null);
        h.assertTrue(around(h, BreezeWindCharge.class, breeze) == 1, "the breeze should fire a wind charge");
        Possession.end(p, false);

        var evoker = h.spawn(EntityType.EVOKER, new Vec3(1.5, 2, 1.5));
        h.assertTrue(Possession.start(p, evoker, 30), "the evoker possession should start");
        Possession.shoot(p, null);
        // Looking along +z from the floor's corner: the fangs over the floor rise, those past its edge find no ground.
        h.assertTrue(around(h, EvokerFangs.class, evoker) >= 5, "the evoker should raise a line of fangs, got " + around(h, EvokerFangs.class, evoker));
        Possession.end(p, false);

        var shulker = h.spawn(EntityType.SHULKER, new BlockPos(7, 2, 1));
        var cow = h.spawn(EntityType.COW, new Vec3(7.5, 2, 6.5));
        h.assertTrue(Possession.start(p, shulker, 30), "the shulker possession should start");
        Possession.shoot(p, null);
        h.assertTrue(around(h, ShulkerBullet.class, shulker) == 0, "a shulker should not shoot at nothing");
        Possession.shoot(p, cow);
        h.assertTrue(around(h, ShulkerBullet.class, shulker) == 1, "a shulker should shoot at what is aimed at, even right after shooting at nothing");
        Possession.end(p, false);
        h.succeed();
    }

    /**
     * The warden's sonic boom hits what is aimed at within its reach; the ravager's roar hurts and
     * throws back what stands near; a ramming goat strikes what it runs into; the enderman goes where
     * the player looks; the creeper blows itself up, ending the possession without the backlash.
     */
    static void skillMoves(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Vec3 start = p.position();
        var warden = h.spawn(EntityType.WARDEN, new Vec3(2.5, 2, 2.5));
        var calf = h.spawn(EntityType.COW, new Vec3(2.5, 2, 8.5));
        h.assertTrue(Possession.start(p, warden, 30), "the warden possession should start");
        Possession.shoot(p, calf);
        h.assertTrue(calf.getHealth() < calf.getMaxHealth(), "the sonic boom should hit the cow");
        Possession.end(p, false);
        warden.discard();

        var ravager = h.spawn(EntityType.RAVAGER, new Vec3(6.5, 2, 2.5));
        var near = h.spawn(EntityType.COW, new Vec3(6.5, 2, 5.5));
        h.assertTrue(Possession.start(p, ravager, 30), "the ravager possession should start");
        Possession.shoot(p, null);
        h.assertTrue(near.getHealth() < near.getMaxHealth() && near.getDeltaMovement().z > 0.5, "the roar should hurt the cow and throw it back, motion " + near.getDeltaMovement());
        Possession.end(p, false);
        ravager.discard();

        var goat = h.spawn(EntityType.GOAT, new Vec3(4.5, 2, 4.5));
        var butt = h.spawn(EntityType.COW, new Vec3(4.5, 2, 5.4));
        h.assertTrue(Possession.start(p, goat, 30), "the goat possession should start");
        Possession.shoot(p, null);
        h.assertTrue(goat.getDeltaMovement().horizontalDistance() > 1, "the goat should charge forward");

        var enderman = h.spawn(EntityType.ENDERMAN, new Vec3(1.5, 2, 1.5));
        var creeper = h.spawn(EntityType.CREEPER, new Vec3(8.5, 2, 8.5));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(butt.getHealth() < butt.getMaxHealth(), "the ramming goat should strike the cow in front of it"))
                .thenExecute(() -> {
                    Possession.end(p, false);
                    goat.discard();
                    h.assertTrue(Possession.start(p, enderman, 30), "the enderman possession should start");
                    // Looking down the floor ahead (+z), it lands about four blocks on.
                    p.setYRot(0);
                    p.setXRot(30);
                    Vec3 from = enderman.position();
                    Possession.shoot(p, null);
                    h.assertTrue(enderman.position().distanceTo(from) > 2, "the enderman should teleport where the player looks, moved " + enderman.position().distanceTo(from));
                    Possession.end(p, false);
                    p.setXRot(0);
                    h.assertTrue(Possession.start(p, creeper, 30), "the creeper possession should start");
                    Possession.shoot(p, null);
                    h.assertTrue(creeper.isIgnited(), "the creeper's fuse should be lit");
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(!Possession.possessing(p) && creeper.isRemoved(), "the blast should end the possession");
                    h.assertTrue(p.position().distanceTo(start) < 0.01, "the player should be back in their body");
                    h.assertTrue(p.getHealth() == p.getMaxHealth(), "blowing itself up should cost no backlash, health " + p.getHealth());
                })
                .thenSucceed();
    }

    /** Left click is the creature's own blow, once a second; the use button its shot, if it has one. */
    static void attacks(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var wolf = h.spawn(EntityType.WOLF, new Vec3(4.5, 2, 4.5));
        var cow = h.spawn(EntityType.COW, new Vec3(4.5, 2, 5.6));
        h.assertTrue(Possession.start(p, wolf, 30), "the possession should start");
        float full = cow.getHealth();
        Possession.strike(p, cow);
        float hit = cow.getHealth();
        h.assertTrue(hit < full, "the wolf's bite should hurt the cow");
        Possession.strike(p, cow);
        h.assertTrue(cow.getHealth() == hit, "a second blow within a second should not land");
        Possession.end(p, false);

        var golem = h.spawn(EntityType.SNOW_GOLEM, new Vec3(6.5, 2, 2.5));
        h.assertTrue(Possession.start(p, golem, 30), "the snow golem possession should start");
        Possession.shoot(p, null);
        h.assertTrue(!h.getLevel().getEntitiesOfClass(Snowball.class, golem.getBoundingBox().inflate(3)).isEmpty(),
                "the snow golem should throw a snowball where the player looks");
        Possession.end(p, false);
        h.succeed();
    }
}
