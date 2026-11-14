package dev.logos.gametest;

import dev.logos.runtime.Casting;
import dev.logos.runtime.Incarnation;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 化身: a player's own body becomes a creature's, unlocked by kills, until the time is up or 万咒皆终 reaches it. */
final class IncarnationGameTests {
    private IncarnationGameTests() {}

    /** A survival player on a stone floor in the middle of the structure, with mana to spare and no kills yet. */
    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        FamiliarGameTests.forceAround(h, 1);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.connection.markClientLoaded();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.baseMaxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    private static SpellInstance cast(ServerPlayer p, String src) {
        return Casting.cast(p, Casting.compile(src), null, p.position());
    }

    private static void killed(ServerPlayer p, EntityType<?> type, int n) {
        p.awardStat(Stats.ENTITY_KILLED.get(type), n);
    }

    /**
     * Locked until 49 kills; then the player takes the iron golem's size, health (keeping its share),
     * blow and knockback resistance for max health / 5 × √seconds, and gets their own back when the
     * time is up, health still in proportion.
     */
    static void body(GameTestHelper h) {
        ServerPlayer p = caster(h);
        killed(p, EntityType.IRON_GOLEM, 48);
        SpellInstance locked = cast(p, "(incarnate iron_golem 1)");
        h.assertTrue(locked == null || locked.dead, "48 kills should not be enough");
        h.assertTrue(!Incarnation.active(p), "a locked form should not be taken");

        killed(p, EntityType.IRON_GOLEM, 1);
        p.setHealth(10);
        SpellInstance inst = cast(p, "(incarnate iron_golem 1)");
        h.assertTrue(inst != null && !inst.dead, "with 49 kills the cast should go through");
        // 100 / 5 × √1.
        h.assertTrue(Math.abs(inst.injected - inst.mana - 20) < 1e-6, "it should cost 20, cost " + (inst.injected - inst.mana));
        h.assertTrue(Incarnation.formType(p) == EntityType.IRON_GOLEM, "the player should be an iron golem");
        h.assertTrue(Math.abs(p.getBbWidth() - EntityType.IRON_GOLEM.getWidth()) < 1e-4 && Math.abs(p.getBbHeight() - EntityType.IRON_GOLEM.getHeight()) < 1e-4,
                "the player should have the golem's size, has " + p.getBbWidth() + " × " + p.getBbHeight());
        h.assertTrue(p.getMaxHealth() == 100 && Math.abs(p.getHealth() - 50) < 1e-4, "half of 20 should become half of 100, is " + p.getHealth() + " / " + p.getMaxHealth());
        h.assertTrue(p.getAttributeValue(Attributes.ATTACK_DAMAGE) == 15, "the golem's blow should be the player's, is " + p.getAttributeValue(Attributes.ATTACK_DAMAGE));
        h.assertTrue(p.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) == 1, "a golem should not be knocked back");
        p.setHealth(25);
        h.succeedWhen(() -> {
            h.assertTrue(!Incarnation.active(p), "a second should wear off by itself");
            h.assertTrue(p.getMaxHealth() == 20 && Math.abs(p.getHealth() - 5) < 1e-4, "a quarter of 100 should become a quarter of 20, is " + p.getHealth());
            h.assertTrue(Math.abs(p.getBbHeight() - 1.8f) < 1e-4, "the player should be their own height again, is " + p.getBbHeight());
            h.assertTrue(p.getAttributeValue(Attributes.ATTACK_DAMAGE) == 1, "their own blow should be back");
        });
    }

    /**
     * What a form can do besides its body: a pig has no hands (its blow is its own, whatever it
     * holds, and it cannot use items), a zombie does; a bat flies (the config list), an allay flies
     * (a flying animal), a pig does not; a cod breathes water; a blaze does not burn.
     */
    static void abilities(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        p.setGameMode(GameType.CREATIVE); // no kills needed
        h.assertTrue(Incarnation.start(p, EntityType.ZOMBIE, 30) && Incarnation.form(p).hands(), "a zombie should keep its hands");
        h.assertTrue(Incarnation.start(p, EntityType.ALLAY, 30) && Incarnation.form(p).flies(), "an allay should fly");
        h.assertTrue(Incarnation.start(p, EntityType.BAT, 30) && Incarnation.form(p).flies(), "a bat should fly (listed in the config)");
        h.assertTrue(Incarnation.start(p, EntityType.COD, 30) && Incarnation.form(p).gills(), "a cod should breathe water");
        h.assertTrue(Incarnation.start(p, EntityType.BLAZE, 30), "a blaze form should be taken");
        p.setGameMode(GameType.SURVIVAL); // creative takes no damage at all
        float health = p.getHealth();
        p.hurtServer(level, level.damageSources().inFire(), 4);
        h.assertTrue(p.getHealth() == health, "a blaze should not burn");

        p.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(p, EntityType.PIG, 30), "a pig form should be taken");
        Incarnation.Form pig = Incarnation.form(p);
        h.assertTrue(!pig.hands() && !pig.flies(), "a pig should have neither hands nor wings");
        p.setGameMode(GameType.SURVIVAL);
        health = p.getHealth();
        p.hurtServer(level, level.damageSources().inFire(), 4);
        h.assertTrue(p.getHealth() < health, "a pig should burn");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
        var cow = h.spawn(EntityType.COW, new Vec3(5.5, 2, 4.5));
        float before = cow.getHealth();
        p.attack(cow);
        h.assertTrue(cow.getHealth() < before && before - cow.getHealth() <= 1 + 1e-4,
                "a pig's blow should be its own 1, sword or not, took " + (before - cow.getHealth()));
        var used = net.neoforged.neoforge.common.CommonHooks.onItemRightClick(p, InteractionHand.MAIN_HAND);
        h.assertTrue(used != null, "without hands an item should not be used");
        p.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(p, EntityType.ZOMBIE, 30) && net.neoforged.neoforge.common.CommonHooks.onItemRightClick(p, InteractionHand.MAIN_HAND) == null,
                "with hands it should be");
        h.succeed();
    }

    /** 万咒皆终 gives the caster back their shape for what the form had left + 2; another player's needs :players. */
    static void endAll(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.setGameMode(GameType.CREATIVE);
        @SuppressWarnings("removal")
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        other.snapTo(p.getX() + 2, p.getY(), p.getZ(), 0, 0);
        other.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(p, EntityType.ZOMBIE, 100) && Incarnation.start(other, EntityType.ZOMBIE, 100), "both should become zombies");
        double left = Incarnation.remainingCost(p);
        SpellInstance stopper = cast(p, "(spell :mana 200 (end-all :radius 6))");
        h.assertTrue(!Incarnation.active(p), "the caster's own form should end");
        h.assertTrue(Incarnation.active(other), "without :players another player's form should hold");
        h.assertTrue(Math.abs(stopper.injected - stopper.mana - (2 + left)) < 0.1, "it should cost the form's price for its time left + 2 = " + (2 + left)
                + ", cost " + (stopper.injected - stopper.mana));
        cast(p, "(spell :mana 200 :players true (end-all :radius 6))");
        h.assertTrue(!Incarnation.active(other), "with :players the other player's form should end");
        h.succeed();
    }

    /**
     * A form's skill on the use button: a blaze throws three fireballs of the player's, once per
     * cooldown; an evoker (hands) only with an empty main hand; a creeper's fuse burns 1.5 s, then
     * its blast hurts the cow and spares the player, who is themself again.
     */
    static void skills(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        p.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(p, EntityType.BLAZE, 30) && Incarnation.skillOnUse(p), "a blaze's use button should be its skill");
        Incarnation.skill(p, null);
        Incarnation.skill(p, null);
        var balls = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball.class, p.getBoundingBox().inflate(4));
        h.assertTrue(balls.size() == 3 && balls.stream().allMatch(b -> b.getOwner() == p), "one volley of three fireballs of the player's, found " + balls.size());
        balls.forEach(net.minecraft.world.entity.Entity::discard);

        h.assertTrue(Incarnation.start(p, EntityType.EVOKER, 30), "an evoker form should be taken");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
        h.assertTrue(!Incarnation.skillOnUse(p), "with something in hand an evoker uses it");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        h.assertTrue(Incarnation.skillOnUse(p), "empty-handed, an evoker casts its fangs");
        h.assertTrue(Incarnation.start(p, EntityType.PIG, 30) && !Incarnation.skillOnUse(p), "a pig has no skill");

        h.assertTrue(Incarnation.start(p, EntityType.CREEPER, 30), "a creeper form should be taken");
        p.setGameMode(GameType.SURVIVAL);
        var cow = h.spawn(EntityType.COW, new Vec3(6.5, 2, 4.5));
        float health = p.getHealth(), cowHealth = cow.getHealth();
        Incarnation.skill(p, null);
        h.assertTrue(Incarnation.active(p), "the fuse should burn before the blast");
        h.succeedWhen(() -> {
            h.assertTrue(!Incarnation.active(p), "the blast should end the form");
            h.assertTrue(!cow.isAlive() || cow.getHealth() < cowHealth, "the blast should hurt the cow");
            h.assertTrue(p.getHealth() == health, "the blast should spare the player, health " + p.getHealth() + " of " + health);
        });
    }

    /**
     * What a form is like besides its body, by tags: a cat falls unhurt and a pig does not; a strider
     * stands on lava's surface; a cod out of water runs out of breath a tick at a time.
     */
    static void traits(GameTestHelper h) {
        ServerPlayer p = caster(h);
        ServerLevel level = h.getLevel();
        p.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(p, EntityType.CAT, 30), "a cat form should be taken");
        p.setGameMode(GameType.SURVIVAL);
        float health = p.getHealth();
        p.hurtServer(level, level.damageSources().fall(), 4);
        h.assertTrue(p.getHealth() == health, "a cat should take no fall damage");
        p.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(p, EntityType.PIG, 30), "a pig form should be taken");
        p.setGameMode(GameType.SURVIVAL);
        health = p.getHealth();
        p.hurtServer(level, level.damageSources().fall(), 4);
        h.assertTrue(p.getHealth() < health, "a pig should be hurt by a fall");

        p.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(p, EntityType.STRIDER, 30) && Incarnation.walksOnLava(p), "a strider should walk on lava");
        net.minecraft.core.BlockPos lava = h.absolutePos(new net.minecraft.core.BlockPos(1, 1, 7));
        level.setBlockAndUpdate(lava, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
        p.snapTo(lava.getX() + 0.5, lava.getY() + 1.05, lava.getZ() + 0.5, 0, 0);
        var surface = dev.logos.runtime.WaterWalking.withSurface(level.getBlockState(lava), level, lava,
                net.minecraft.world.phys.shapes.CollisionContext.of(p), net.minecraft.world.phys.shapes.Shapes.empty());
        h.assertTrue(!surface.isEmpty(), "lava should hold a strider up");

        Vec3 home = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        p.snapTo(home.x, home.y, home.z, 0, 0);
        h.assertTrue(Incarnation.start(p, EntityType.COD, 30), "a cod form should be taken");
        p.setGameMode(GameType.SURVIVAL);
        int air = p.getAirSupply();
        // (Water hurting an enderman needs a player whose own tick notices the water: the client scenario logos_incarnate_traits.)
        h.succeedWhen(() -> h.assertTrue(p.getAirSupply() < air - 10, "a cod out of water should run out of breath, air " + p.getAirSupply() + " of " + air));
    }

    /**
     * 解除化身: one's own form, for free; another player's (with :players) for its price less the part
     * used — an iron golem 25 s into 100 s: 100 / 5 × (√100 − √25) = 100.
     */
    static void revert(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(p, EntityType.ZOMBIE, 100), "the caster should become a zombie");
        SpellInstance own = cast(p, "(spell :mana 10 (revert))");
        h.assertTrue(!Incarnation.active(p), "the caster's own form should be undone");
        h.assertTrue(own.injected - own.mana < 1e-6, "undoing one's own form should be free, cost " + (own.injected - own.mana));

        @SuppressWarnings("removal")
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        other.snapTo(p.getX() + 2, p.getY(), p.getZ(), 0, 0);
        other.setGameMode(GameType.CREATIVE);
        h.assertTrue(Incarnation.start(other, EntityType.IRON_GOLEM, 100), "the other player should become an iron golem");
        Incarnation.Form f = Incarnation.form(other);
        long now = h.getLevel().getGameTime();
        other.setData(dev.logos.LogosMod.FORM, new Incarnation.Form(f.type(), now - 25 * 20, now + 75 * 20, f.flies(), f.gills(), f.hands()));
        h.assertTrue(Math.abs(Incarnation.unusedCost(other) - 100) < 1e-6, "the unused part should cost 100, costs " + Incarnation.unusedCost(other));

        cast(p, "(spell :mana 200 (revert (nearest-player :radius 4)))");
        h.assertTrue(Incarnation.active(other), "without :players another player's form should hold");
        SpellInstance undo = cast(p, "(spell :mana 200 :players true (revert (nearest-player :radius 4)))");
        h.assertTrue(!Incarnation.active(other), "with :players the other player's form should be undone");
        double paid = undo.injected - undo.mana;
        h.assertTrue(paid >= 100 - 1e-6 && paid <= 102, "it should cost 100 (and the selector), cost " + paid);
        h.succeed();
    }
}
