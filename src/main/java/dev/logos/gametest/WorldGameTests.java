package dev.logos.gametest;

import java.util.List;

import dev.logos.LogosMod;
import dev.logos.block.InscriptionTableBlock;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.item.SpellPages;
import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.StatusData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** In-world tests for spells acting on blocks, items, players, dimensions, and for wands/pages/tables. */
final class WorldGameTests {
    private WorldGameTests() {}

    private static ServerPlayer caster(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(0.5, 2, 0.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    /** Casts with {@code here} set to the centre of relative block {@code at}. */
    private static SpellInstance castAt(GameTestHelper h, ServerPlayer p, String src, BlockPos at) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, Vec3.atCenterOf(h.absolutePos(at)));
        h.assertTrue(inst != null, "cast refused: " + src);
        return inst;
    }

    /** toggle-state behaves like the debug stick: cycles the property, no block update. */
    static void toggleState(GameTestHelper h) {
        ServerPlayer p = caster(h);
        BlockPos at = new BlockPos(4, 2, 4);
        h.setBlock(at, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        castAt(h, p, "(toggle-state here :property \"facing\")", at);
        BlockState after = h.getBlockState(at);
        h.assertTrue(after.getValue(StairBlock.FACING) == Direction.SOUTH, "facing should cycle NORTH → SOUTH, got " + after.getValue(StairBlock.FACING));
        castAt(h, p, "(toggle-state here)", at);
        h.assertTrue(h.getBlockState(at).getBlock() == Blocks.OAK_STAIRS, "the block must stay a stair");
        h.succeed();
    }

    /** use-block clicks levers, buttons and doors, and opens no screen on a chest. */
    static void useBlock(GameTestHelper h) {
        ServerPlayer p = caster(h);
        BlockPos lever = new BlockPos(2, 2, 4), button = new BlockPos(4, 2, 2), door = new BlockPos(6, 2, 4), chest = new BlockPos(6, 2, 6);
        var floor = net.minecraft.world.level.block.state.properties.AttachFace.FLOOR;
        h.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE, floor));
        h.setBlock(button, Blocks.STONE_BUTTON.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ATTACH_FACE, floor));
        var lower = Blocks.OAK_DOOR.defaultBlockState();
        h.setBlock(door, lower);
        h.setBlock(door.above(), lower.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        h.setBlock(chest, Blocks.CHEST);
        castAt(h, p, "(use-block here)", lever);
        castAt(h, p, "(use-block here)", button);
        castAt(h, p, "(use-block here)", door.above());
        castAt(h, p, "(use-block here)", chest);
        h.assertTrue(h.getBlockState(lever).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED), "the lever should be pulled");
        h.assertTrue(h.getBlockState(button).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED), "the button should be pressed");
        h.assertTrue(h.getBlockState(door).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN), "the door should open, clicked on its top half");
        h.assertTrue(p.containerMenu == p.inventoryMenu, "the chest must not open a screen for the caster");
        h.succeed();
    }

    /** With :item it uses the caster's own stack: bone meal is spent, a bucket comes back full. */
    static void useBlockWithItem(GameTestHelper h) {
        ServerPlayer p = caster(h);
        BlockPos wheat = new BlockPos(2, 2, 2), cauldron = new BlockPos(6, 2, 6);
        h.setBlock(wheat.below(), Blocks.FARMLAND);
        h.setBlock(wheat, Blocks.WHEAT);
        h.setBlock(cauldron, Blocks.WATER_CAULDRON.defaultBlockState().setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
        p.getInventory().add(new ItemStack(Items.BONE_MEAL, 2));
        p.getInventory().add(new ItemStack(Items.BUCKET));
        castAt(h, p, "(use-block here :item &minecraft:bone_meal)", wheat);
        castAt(h, p, "(use-block here :item &minecraft:bucket)", cauldron);
        h.assertTrue(h.getBlockState(wheat).getValue(CropBlock.AGE) > 0, "bone meal should grow the wheat");
        h.assertTrue(count(p, Items.BONE_MEAL) == 1, "one bone meal should be spent, left " + count(p, Items.BONE_MEAL));
        h.assertTrue(count(p, Items.WATER_BUCKET) == 1 && count(p, Items.BUCKET) == 0, "the bucket should come back full");
        h.assertTrue(h.getBlockState(cauldron).is(Blocks.CAULDRON), "the cauldron should be emptied");
        h.succeed();
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(item)) n += p.getInventory().getItem(i).getCount();
        }
        return n;
    }

    /** grow ages crops; decay rolls them back, drops leaves and destroys flowers. */
    static void growAndDecay(GameTestHelper h) {
        ServerPlayer p = caster(h);
        BlockPos farm = new BlockPos(2, 1, 2);
        BlockPos wheat = farm.above();
        h.setBlock(farm, Blocks.FARMLAND);
        h.setBlock(wheat, Blocks.WHEAT.defaultBlockState());
        castAt(h, p, "(grow here 0)", wheat);
        h.assertTrue(h.getBlockState(wheat).getValue(CropBlock.AGE) == 1, "wheat should be age 1 after grow");
        castAt(h, p, "(decay here 0)", wheat);
        h.assertTrue(h.getBlockState(wheat).getValue(CropBlock.AGE) == 0, "wheat should be back to age 0 after decay");

        BlockPos leaves = new BlockPos(6, 3, 6);
        h.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        BlockPos flower = new BlockPos(6, 2, 2);
        h.setBlock(flower, Blocks.POPPY);
        castAt(h, p, "(decay here 0)", leaves);
        castAt(h, p, "(decay here 0)", flower);
        h.assertTrue(h.getBlockState(leaves).isAir(), "leaves should decay away");
        h.assertTrue(h.getBlockState(flower).isAir(), "the flower should wither away");
        BlockPos fern = new BlockPos(2, 2, 6);
        h.setBlock(fern, Blocks.FERN);
        castAt(h, p, "(decay here 0)", fern);
        h.assertTrue(h.getBlockState(fern).isAir(), "a fern in logos:fragile_plants should wither away");
        h.succeed();
    }

    /** switch runs only the branch its index names, counted from 0; any other index runs on-other. */
    static void switchBranches(GameTestHelper h) {
        ServerPlayer p = caster(h);
        String spell = "(switch %s (mark caster #a) (mark caster #b) (on-other (mark caster #o)))";
        castAt(h, p, String.format(spell, "1"), new BlockPos(0, 2, 0));
        castAt(h, p, String.format(spell, "0.4"), new BlockPos(0, 2, 0));
        castAt(h, p, String.format(spell, "2"), new BlockPos(0, 2, 0));
        castAt(h, p, String.format(spell, "-1"), new BlockPos(0, 2, 0));
        double a = StatusData.stacks(p, new Status("#a")), b = StatusData.stacks(p, new Status("#b")), o = StatusData.stacks(p, new Status("#o"));
        h.assertTrue(a == 1 && b == 1 && o == 2, "expected #a 1 (index 0.4), #b 1 (index 1), #o 2 (2 and -1); got " + a + ", " + b + ", " + o);
        SpellInstance none = castAt(h, p, "(spell :mana 10 (switch 3 (mark caster #a)))", new BlockPos(0, 2, 0));
        h.assertTrue(Math.abs(none.injected - none.mana - 0.5) < 1e-9, "a switch costs 0.5 even when nothing runs, cost " + (none.injected - none.mana));
        h.succeed();
    }

    /**
     * life-of reads a form's life through the form's own output (kept by let) and, inside its events, through form;
     * summon hands back the familiar it called.
     */
    static void formRefs(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Status half = new Status("#half"), done = new Status("#done"), end = new Status("#end");
        castAt(h, p, "(spell :mana 100 (let $f (field :life 2 :radius 1)"
                + " (delay 1 (if (and (> (life-of $f) 0.4) (< (life-of $f) 0.6)) (mark caster #half)))"
                + " (delay 3 (if (= (life-of $f) 1) (mark caster #done)))))", new BlockPos(6, 2, 6));
        castAt(h, p, "(spell :mana 100 (projectile :dir up :speed 0.05 :life 0.5 (on-expire (if (= (life-of form) 1) (mark caster #end)))))",
                new BlockPos(6, 3, 2));
        castAt(h, p, "(spell :mana 500 (let $m (summon mana_lifeform :at (pos-of caster) :mana 60) (glow $m 5)))", new BlockPos(0, 2, 0));
        var lf = dev.logos.runtime.Familiars.latest(p, null);
        h.assertTrue(lf != null && lf.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "summon's output should be the familiar it called, and glow");
        lf.discard();
        FamiliarGameTests.forceAround(h, 1);
        h.succeedWhen(() -> {
            h.assertTrue(StatusData.stacks(p, end) == 1, "the projectile's on-expire should read its own life as 1");
            h.assertTrue(StatusData.stacks(p, half) == 1, "halfway through, the field's life should read about 0.5");
            h.assertTrue(StatusData.stacks(p, done) == 1, "after it ended, the field's life should read 1");
        });
    }

    /** Without :players, spells skip other players; with it, they reach them. */
    static void playersFlag(GameTestHelper h) {
        ServerPlayer p = caster(h);
        @SuppressWarnings("removal")
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        Vec3 near = h.absoluteVec(new Vec3(2.5, 2, 2.5));
        other.snapTo(near.x, near.y, near.z, 0, 0);
        Status mark = new Status("#seen");
        castAt(h, p, "(mark (enemies :radius 6) #seen)", new BlockPos(0, 2, 0));
        h.assertTrue(StatusData.stacks(other, mark) == 0, "a spell without :players must not touch other players");
        castAt(h, p, "(spell :players true (mark (enemies :radius 6) #seen))", new BlockPos(0, 2, 0));
        h.assertTrue(StatusData.stacks(other, mark) == 1, "with :players true the other player should be marked");
        castAt(h, p, "(spell :players true (mark (players :radius 6) #seen))", new BlockPos(0, 2, 0));
        h.assertTrue(StatusData.stacks(other, mark) == 2, "the players selector should find the other player");
        h.succeed();
    }

    /** nearest-player picks the closest other player, and is empty (and free) without :players. */
    static void nearestPlayer(GameTestHelper h) {
        ServerPlayer p = caster(h);
        @SuppressWarnings("removal")
        ServerPlayer near = h.makeMockServerPlayerInLevel();
        @SuppressWarnings("removal")
        ServerPlayer far = h.makeMockServerPlayerInLevel();
        Vec3 a = h.absoluteVec(new Vec3(2.5, 2, 0.5)), b = h.absoluteVec(new Vec3(6.5, 2, 0.5));
        near.snapTo(a.x, a.y, a.z, 0, 0);
        far.snapTo(b.x, b.y, b.z, 0, 0);
        Status mark = new Status("#seen");
        SpellInstance off = castAt(h, p, "(spell :mana 20 (mark (nearest-player :radius 8) #seen))", new BlockPos(0, 2, 0));
        h.assertTrue(StatusData.stacks(near, mark) == 0 && StatusData.stacks(far, mark) == 0, "without :players nearest-player must be empty");
        h.assertTrue(off.injected - off.mana < 1e-6, "an empty nearest-player without :players should cost nothing, cost " + (off.injected - off.mana));
        SpellInstance on = castAt(h, p, "(spell :mana 20 :players true (mark (nearest-player :radius 8) #seen))", new BlockPos(0, 2, 0));
        h.assertTrue(StatusData.stacks(near, mark) == 1, "the nearer player should be marked");
        h.assertTrue(StatusData.stacks(far, mark) == 0, "only the nearest player should be marked");
        h.assertTrue(on.injected - on.mana >= 1 - 1e-6, "the search should cost 1, cost " + (on.injected - on.mana));
        castAt(h, p, "(spell :mana 20 :players true (mark (nearest-player :radius 1) #seen))", new BlockPos(0, 2, 0));
        h.assertTrue(StatusData.stacks(near, mark) == 1, "nobody within radius 1: the caster is never picked and nothing else is marked");
        h.succeed();
    }

    /** items selects dropped items, and pull works on them (a magnet). */
    static void itemsMagnet(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Vec3 at = h.absoluteVec(new Vec3(5.5, 2.2, 5.5));
        ItemEntity item = new ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.DIAMOND));
        item.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(item);
        ItemEntity log = new ItemEntity(h.getLevel(), at.x, at.y, at.z + 1, new ItemStack(Items.OAK_LOG));
        log.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(log);
        castAt(h, p, "(pull (items :radius 10 :filter \"#minecraft:logs\") 2 :to (pos-of caster))", new BlockPos(0, 2, 0));
        h.assertTrue(log.getDeltaMovement().length() > 0.3, "the log should be pulled");
        h.assertTrue(item.getDeltaMovement().length() < 0.01, "the diamond does not match #minecraft:logs and must stay");
        h.succeed();
    }

    /** ⊙ (kind projectiles) picks a flying arrow out of the crowd; the husk beside it is not a projectile. */
    static void filterKinds(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Vec3 at = h.absoluteVec(new Vec3(5.5, 2.5, 5.5));
        var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.ARROW), null);
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(arrow);
        var husk = h.spawn(net.minecraft.world.entity.EntityType.HUSK, new Vec3(5.5, 2, 6.5));
        husk.setNoAi(true);
        castAt(h, p, "(push (entities :radius 10 :filter (kind projectiles)) 2 :from (pos-of caster))", new BlockPos(0, 2, 0));
        h.assertTrue(arrow.getDeltaMovement().length() > 0.3, "the arrow should be pushed");
        h.assertTrue(husk.getDeltaMovement().horizontalDistance() < 0.01, "a husk is not a projectile and must stay");
        h.succeed();
    }

    /**
     * ⚓ (anchor ^name pos) spends one anchor nail from the inventory and saves the place; ^name then
     * reads as that place (blink goes there); a missing anchor or an empty nail pouch extinguishes the spell.
     */
    static void anchors(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.getInventory().clearContent();
        p.getInventory().add(new ItemStack(LogosMod.ANCHOR_NAIL.get(), 1));
        SpellInstance set = castAt(h, p, "(anchor ^测试锚 here)", new BlockPos(6, 2, 6));
        h.assertTrue(!set.dead, "setting the anchor failed: " + set.endReason());
        h.assertTrue(!p.getInventory().contains(new ItemStack(LogosMod.ANCHOR_NAIL.get())), "the nail should have been spent");
        var a = dev.logos.runtime.Anchors.of(p).get("测试锚");
        h.assertTrue(a != null && a.pos().distanceTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(6, 2, 6)))) < 0.01, "anchor not saved where it was set");

        SpellInstance go = castAt(h, p, "(blink ^测试锚)", new BlockPos(1, 2, 1));
        h.assertTrue(!go.dead, "blink to the anchor failed: " + go.endReason());
        h.assertTrue(p.position().distanceTo(a.pos()) < 2, "blink should land at the anchor, landed " + p.position() + " vs " + a.pos());

        SpellInstance missing = Casting.cast(p, Casting.compile("(blink ^不存在)"), null, null);
        h.assertTrue(missing != null && missing.dead && missing.endReason().contains("不存在"), "a missing anchor must extinguish the spell");

        SpellInstance noNail = Casting.cast(p, Casting.compile("(anchor ^第二个 here)"), null, null);
        h.assertTrue(noNail != null && noNail.dead && noNail.endReason().contains("锚钉"), "without a nail the anchor must fail");
        h.assertTrue(dev.logos.runtime.Anchors.of(p).get("第二个") == null, "and nothing must be saved");
        h.succeed();
    }

    /**
     * blink with a target moves that creature (or each one of a group) instead of the caster, charged by its own
     * distance; another player stays put unless the spell has :players true.
     */
    static void blinkTarget(GameTestHelper h) {
        ServerPlayer p = caster(h);
        Vec3 start = p.position();
        var cow = h.spawn(net.minecraft.world.entity.EntityType.COW, new BlockPos(2, 2, 0));
        Vec3 dest = Vec3.atCenterOf(h.absolutePos(new BlockPos(6, 2, 6)));
        double dist = cow.position().distanceTo(dest);
        SpellInstance one = castAt(h, p, "(spell :mana 50 (blink here (nearest :at (pos-of caster) :radius 4)))", new BlockPos(6, 2, 6));
        h.assertTrue(cow.position().distanceTo(dest) < 0.5, "the cow should land at the place, is at " + cow.position() + " vs " + dest);
        h.assertTrue(p.position().distanceTo(start) < 1e-6, "the caster must stay put when blink has a target");
        double spent = one.injected - one.mana;
        h.assertTrue(Math.abs(spent - (1 + 2 + 0.5 * dist)) < 1e-3, "expected nearest 1 + 2 + 0.5 × " + dist + ", spent " + spent);

        var pig = h.spawn(net.minecraft.world.entity.EntityType.PIG, new BlockPos(1, 2, 2));
        Vec3 corner = Vec3.atCenterOf(h.absolutePos(new BlockPos(0, 2, 8)));
        castAt(h, p, "(spell :mana 200 (blink here (enemies :radius 12)))", new BlockPos(0, 2, 8));
        h.assertTrue(cow.position().distanceTo(corner) < 0.5 && pig.position().distanceTo(corner) < 0.5, "every member of the group should be moved");

        @SuppressWarnings("removal")
        ServerPlayer other = h.makeMockServerPlayerInLevel();
        Vec3 at = h.absoluteVec(new Vec3(4.5, 2, 2.5));
        other.snapTo(at.x, at.y, at.z, 0, 0);
        Vec3 far = Vec3.atCenterOf(h.absolutePos(new BlockPos(8, 2, 0)));
        castAt(h, p, "(spell :mana 50 (blink here (entities :at (pos-of caster) :radius 6 :filter (kind all))))", new BlockPos(8, 2, 0));
        h.assertTrue(other.position().distanceTo(at) < 1e-6, "without :players another player must not be moved");
        castAt(h, p, "(spell :mana 50 :players true (blink here (players :at (pos-of caster) :radius 6)))", new BlockPos(8, 2, 0));
        h.assertTrue(other.position().distanceTo(far) < 0.5, "with :players the other player should be moved, is at " + other.position());
        h.succeed();
    }

    /**
     * in-dimension refuses a dimension the caster has never been to (free of charge) unless the config
     * allows it; once visited, it maps overworld coordinates into the nether (÷8) and blink lands there, standing.
     */
    static void netherStep(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.snapTo(800.5, 70, 1600.5, 0, 0);
        String spell = "(spell :mana 200 (blink (in-dimension @the_nether (pos-of caster))))";
        SpellInstance refused = Casting.cast(p, Casting.compile(spell), null, null);
        h.assertTrue(refused != null && refused.dead && refused.endReason().contains("没有到过"),
                "an unvisited nether must extinguish the spell, got " + (refused == null ? "refused" : refused.endReason()));
        h.assertTrue(p.level().dimension() == Level.OVERWORLD, "the caster must stay put");
        h.assertTrue(refused.mana >= refused.injected - 1e-6, "the refused rune must not charge, pool " + refused.mana);

        dev.logos.LogosConfig.TRAVEL_UNVISITED_DIMENSIONS.set(true);
        boolean free;
        try {
            free = dev.logos.runtime.Attunement.reaches(p, h.getLevel().getServer().getLevel(Level.NETHER));
        } finally {
            dev.logos.LogosConfig.TRAVEL_UNVISITED_DIMENSIONS.set(false);
        }
        h.assertTrue(free, "travelUnvisitedDimensions must lift the check");

        dev.logos.runtime.Attunement.visit(p, Level.NETHER);
        SpellInstance inst = Casting.cast(p, Casting.compile(spell), null, null);
        h.assertTrue(inst != null && !inst.dead, "blink failed: " + (inst == null ? "refused" : inst.endReason()));
        h.assertTrue(p.level().dimension() == Level.NETHER, "caster should now be in the nether, is in " + p.level().dimension());
        h.assertTrue(Math.abs(p.getX() - 100.5) < 1 && Math.abs(p.getZ() - 200.5) < 1,
                "expected x≈100, z≈200 (overworld ÷ 8), got " + p.getX() + ", " + p.getZ());
        h.succeed();
    }

    /**
     * world-weather sets the server's weather for the given time and pays once per change; snow is refused
     * for free. All in one tick, the weather put back before it can rain or strike other tests.
     */
    static void worldWeather(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var server = h.getLevel().getServer();
        var data = server.getWeatherData();
        int clear = data.getClearWeatherTime(), rain = data.getRainTime(), thunder = data.getThunderTime();
        boolean raining = data.isRaining(), thundering = data.isThundering();
        try {
            SpellInstance storm = Casting.cast(p, Casting.compile("(spell :mana 300 (world-weather thunder 30))"), null, null);
            h.assertTrue(!storm.dead && storm.injected - storm.mana == 200, "a storm should cost 200, cost " + (storm.injected - storm.mana) + " (" + storm.endReason() + ")");
            h.assertTrue(data.isRaining() && data.isThundering() && data.getRainTime() == 600, "it should storm for 600 ticks, rain time " + data.getRainTime());
            SpellInstance sun = Casting.cast(p, Casting.compile("(spell :mana 300 (world-weather clear))"), null, null);
            h.assertTrue(!sun.dead && sun.injected - sun.mana == 100, "clearing should cost 100, cost " + (sun.injected - sun.mana));
            h.assertTrue(!data.isRaining() && !data.isThundering() && data.getClearWeatherTime() == 12000, "it should clear for the default 600 s, clear time " + data.getClearWeatherTime());
            SpellInstance snow = Casting.cast(p, Casting.compile("(spell :mana 300 (world-weather snow))"), null, null);
            h.assertTrue(snow.dead && snow.endReason().contains("不能下雪") && snow.mana == snow.injected, "snow should be refused for free, got " + snow.endReason());
        } finally {
            data.setClearWeatherTime(clear);
            data.setRainTime(rain);
            data.setThunderTime(thunder);
            data.setRaining(raining);
            data.setThundering(thundering);
        }
        h.succeed();
    }

    /**
     * lightning brings a real bolt down: the cow under it is struck, the caster beside it is spared. It costs
     * 50 under a dry sky, 40 in rain, 20 in a storm; area zones set each sky, whatever the world's weather.
     */
    static void lightningStrike(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.connection.markClientLoaded();
        FamiliarGameTests.forceAround(h, 1);
        Vec3 beside = h.absoluteVec(new Vec3(3.0, 2, 5.5)); // inside the bolt's reach, clear of the fire it lights
        p.snapTo(beside.x, beside.y, beside.z, 0, 0);
        var cow = h.spawn(EntityType.COW, new Vec3(5.5, 2, 5.5));
        cow.setNoAi(true);
        float full = p.getHealth();
        // A clear zone over the cow: the dry-sky price, whatever the world is doing. Its upkeep starts next tick.
        SpellInstance inst = castAt(h, p, "(spell :mana 100 (weather clear :radius 2 :life 1) (lightning here))", new BlockPos(5, 2, 5));
        h.assertTrue(!inst.dead && inst.injected - inst.mana == 10 + 50, "a bolt under a clear sky should cost 50, spell cost " + (inst.injected - inst.mana));
        BlockPos wet = new BlockPos(1, 2, 15), stormy = new BlockPos(8, 2, 15);
        castAt(h, p, "(spell :mana 100 (weather rain :radius 1.5 :life 1))", wet);
        castAt(h, p, "(spell :mana 100 (weather thunder :radius 1.5 :life 1))", stormy);
        double inRain = dev.logos.runtime.Lightning.cost(h.getLevel(), h.absolutePos(wet));
        double inStorm = dev.logos.runtime.Lightning.cost(h.getLevel(), h.absolutePos(stormy));
        h.assertTrue(inRain == 40 && inStorm == 20, "a bolt should cost 40 in rain and 20 in a storm, costs " + inRain + " and " + inStorm);
        h.succeedWhen(() -> {
            h.assertTrue(cow.getHealth() < cow.getMaxHealth() || !cow.isAlive(), "the cow under the bolt should be struck");
            h.assertTrue(p.getHealth() == full && !p.isOnFire(), "the caster beside it should be spared, health " + p.getHealth());
        });
    }

    /**
     * Area weather: rain falls inside the zone and nowhere else on its account, a clear zone keeps the rain
     * off whatever the world does, a snow zone snows and freezes water in any biome, upkeep is paid by area
     * each second, a zone that cannot pay goes at once, and a finished zone leaves nothing behind.
     */
    static void areaWeather(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var level = h.getLevel();
        BlockPos rainAt = new BlockPos(2, 2, 2), clearAt = new BlockPos(6, 2, 6), snowAt = new BlockPos(2, 2, 7);
        h.setBlock(snowAt.below(), Blocks.WATER);
        BlockPos stone = snowAt.east(); // stone under air, next to the water
        Vec3 rainCenter = Vec3.atCenterOf(h.absolutePos(rainAt));
        SpellInstance rain = castAt(h, p, "(spell :mana 100 (weather rain :radius 1.5 :life 2))", rainAt);
        SpellInstance clear = castAt(h, p, "(spell :mana 100 (weather clear :radius 1.5 :life 2))", clearAt);
        SpellInstance snow = castAt(h, p, "(spell :mana 100 (weather snow :radius 1.5 :life 2))", snowAt);
        h.assertTrue(dev.logos.runtime.AreaWeather.at(level, rainCenter.x, rainCenter.z) == dev.logos.lang.Weather.RAIN, "the rain zone should stand over its center");
        h.assertTrue(level.precipitationAt(h.absolutePos(rainAt)) != net.minecraft.world.level.biome.Biome.Precipitation.NONE, "it should rain (or snow, if cold) in the zone");
        h.assertTrue(dev.logos.runtime.AreaWeather.at(level, rainCenter.x + 30, rainCenter.z) == null, "30 blocks off no zone should stand");
        h.assertTrue(level.precipitationAt(h.absolutePos(clearAt)) == net.minecraft.world.level.biome.Biome.Precipitation.NONE, "nothing should fall in the clear zone");
        for (int i = 0; i < 4; i++) {
            level.tickPrecipitation(h.absolutePos(snowAt));
            level.tickPrecipitation(h.absolutePos(stone));
        }
        h.assertTrue(h.getBlockState(snowAt.below()).is(Blocks.ICE), "a snow zone should freeze still water in any biome");
        h.assertTrue(h.getBlockState(stone).is(Blocks.SNOW), "a snow zone should lay snow in any biome");
        SpellInstance poor = castAt(h, p, "(spell :mana 11 (weather thunder :radius 10 :life 30))", new BlockPos(4, 2, 4));
        h.succeedWhen(() -> {
            h.assertTrue(poor.dead && poor.endReason().contains("魔力耗尽"), "a storm it cannot keep up should end, got " + poor.endReason());
            h.assertTrue(rain.finished() && clear.finished() && snow.finished(), "the zones are still up");
            // 10 to raise, then 0.02 × 1.5² a second for 2 seconds.
            double spent = rain.injected - rain.mana;
            h.assertTrue(!rain.dead && Math.abs(spent - (10 + 0.02 * 2.25 * 2)) < 1e-6, "rain should cost 10.09, cost " + spent + " (" + rain.endReason() + ")");
            h.assertTrue(dev.logos.runtime.AreaWeather.at(level, rainCenter.x, rainCenter.z) == null, "a finished zone should be gone");
        });
    }

    /**
     * time-skip pays 4 per second as it pushes the overworld clock and speeds random ticks there; it stops
     * when its mana runs dry, refuses a second skip on the same clock, and gives the clock its rate back.
     * In the nether, which has no clock, only random ticks speed up. One test, as skips share the clock.
     */
    static void timeSkip(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var server = h.getLevel().getServer();
        var clock = h.getLevel().dimensionType().defaultClock().orElseThrow();
        float rate = server.clockManager().getRate(clock);
        var nether = server.getLevel(Level.NETHER);
        SpellInstance dry = Casting.cast(p, Casting.compile("(spell :mana 10 (time-skip 60))"), null, null);
        SpellInstance[] run = new SpellInstance[2];
        long[] before = new long[1];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(dry.finished(), "the dry skip is still running"))
                .thenExecute(() -> {
                    h.assertTrue(dry.dead && dry.endReason().contains("魔力耗尽"), "it should stop when its mana runs dry, got " + dry.endReason());
                    h.assertTrue(server.clockManager().getRate(clock) == rate, "a stopped skip should give the clock its rate back");
                    before[0] = server.clockManager().getTotalTicks(clock);
                    run[0] = Casting.cast(p, Casting.compile("(spell :mana 100 (time-skip 10))"), null, null);
                    SpellInstance second = Casting.cast(p, Casting.compile("(spell :mana 100 (time-skip 10))"), null, null);
                    h.assertTrue(second.dead && second.endReason().contains("时间已在加速") && second.mana == second.injected,
                            "a second skip on the same clock should be refused for free, got " + second.endReason());
                })
                .thenExecuteAfter(5, () -> h.assertTrue(dev.logos.runtime.TimeSkipObj.randomTickSpeed(h.getLevel(), 3) > 3, "random ticks should speed up"))
                .thenWaitUntil(() -> h.assertTrue(run[0].finished(), "the skip is still running"))
                .thenExecute(() -> {
                    double spent = run[0].injected - run[0].mana;
                    h.assertTrue(!run[0].dead && Math.abs(spent - 40) < 1e-6, "10 seconds should cost 40, cost " + spent + " (" + run[0].endReason() + ")");
                    long pushed = server.clockManager().getTotalTicks(clock) - before[0];
                    h.assertTrue(pushed >= 200, "the clock should move at least 200 ticks, moved " + pushed);
                    h.assertTrue(server.clockManager().getRate(clock) == rate, "the clock should get its rate back");
                    h.assertTrue(dev.logos.runtime.TimeSkipObj.randomTickSpeed(h.getLevel(), 3) == 3, "random ticks should be back to normal");
                    dev.logos.runtime.Attunement.visit(p, Level.NETHER);
                    Casting.cast(p, Casting.compile("(spell :mana 200 (blink (in-dimension @the_nether (pos-of caster))))"), null, null);
                    h.assertTrue(p.level() == nether, "the caster should be in the nether");
                    run[1] = Casting.cast(p, Casting.compile("(spell :mana 100 (time-skip 5))"), null, null);
                })
                .thenExecuteAfter(5, () -> {
                    h.assertTrue(dev.logos.runtime.TimeSkipObj.randomTickSpeed(nether, 3) > 3, "random ticks in the nether should speed up");
                    h.assertTrue(dev.logos.runtime.TimeSkipObj.randomTickSpeed(h.getLevel(), 3) == 3, "the overworld's random ticks should not");
                    h.assertTrue(server.clockManager().getRate(clock) == rate, "a skip in the nether should leave the overworld clock alone");
                })
                .thenWaitUntil(() -> h.assertTrue(run[1].finished() && !run[1].dead, "the nether skip should finish, " + run[1].endReason()))
                .thenSucceed();
    }

    /** A wand charges and casts the selected page; a codex in the off hand extends the list. */
    static void wandCastsSelectedPage(GameTestHelper h) {
        ServerPlayer p = caster(h);
        var husk = h.spawn(EntityType.HUSK, new Vec3(4.5, 2, 4.5), EntitySpawnReason.COMMAND);
        husk.setNoAi(true);
        ItemStack wand = new ItemStack(LogosMod.WAND.get());
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed("(spell :name \"a\" :mana 10 (mark (enemies :radius 8) #a))", null))));
        ItemStack codex = new ItemStack(LogosMod.SPELL_CODEX.get());
        codex.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed("(spell :name \"b\" :mana 10 (mark (enemies :radius 8) #b))", null))));
        p.setItemInHand(InteractionHand.MAIN_HAND, wand);
        p.setItemInHand(InteractionHand.OFF_HAND, codex);
        wand.set(LogosMod.SELECTED.get(), 1); // the codex page comes after the wand's pages
        wand.getItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        for (int i = 0; i < 20 && !dev.logos.runtime.Charging.of(p).full(); i++) wand.getItem().onUseTick(h.getLevel(), p, wand, p.getUseItemRemainingTicks());
        p.releaseUsingItem();
        h.assertTrue(StatusData.stacks(husk, new Status("#b")) == 1, "the selected (codex) spell should have been cast");
        h.assertTrue(StatusData.stacks(husk, new Status("#a")) == 0, "only the selected spell should be cast");
        h.succeed();
    }

    /** Each wood's log, planks and a gold nugget, laid diagonally, craft that wood's three-page wand. */
    static void woodWandsCraft(GameTestHelper h) {
        for (var wand : LogosMod.WOOD_WANDS) {
            String wood = wand.getId().getPath().replace("_wand", "");
            var log = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(wood + "_log"));
            var planks = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(wood + "_planks"));
            var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, List.of(
                    ItemStack.EMPTY, ItemStack.EMPTY, new ItemStack(Items.GOLD_NUGGET),
                    ItemStack.EMPTY, new ItemStack(planks), ItemStack.EMPTY,
                    new ItemStack(log), ItemStack.EMPTY, ItemStack.EMPTY));
            ItemStack out = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, h.getLevel())
                    .map(r -> r.value().assemble(input)).orElse(ItemStack.EMPTY);
            h.assertTrue(out.is(wand.get()), wood + " log and planks made " + out);
            h.assertTrue(wand.get().slots() == 3, wood + " wand holds " + wand.get().slots() + " pages");
        }
        h.succeed();
    }

    /** A crystal page needs adhesive too: paper, a gold nugget and amethyst alone make nothing; a written basic page with amethyst and adhesive becomes one and keeps its spell. */
    static void crystalPageNeedsAdhesive(GameTestHelper h) {
        var bare = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(Items.PAPER), new ItemStack(Items.GOLD_NUGGET), new ItemStack(Items.AMETHYST_SHARD)));
        h.assertTrue(h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, bare, h.getLevel()).isEmpty(),
                "a crystal page was made without adhesive");
        var blank = net.minecraft.world.item.crafting.CraftingInput.of(2, 2, List.of(
                new ItemStack(Items.PAPER), new ItemStack(Items.GOLD_NUGGET), new ItemStack(Items.AMETHYST_SHARD), new ItemStack(LogosMod.ADHESIVE.get())));
        ItemStack made = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, blank, h.getLevel())
                .map(r -> r.value().assemble(blank)).orElse(ItemStack.EMPTY);
        h.assertTrue(made.is(dev.logos.item.PageTier.CRYSTAL.blank()), "paper, gold nugget, amethyst and adhesive made " + made);
        ItemStack basic = SpellPages.inscribed(dev.logos.item.PageTier.BASIC, "(spell :name \"晶辉\" :mana 5 (heal caster 1))", null);
        var upgrade = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(Items.AMETHYST_SHARD), basic, new ItemStack(LogosMod.ADHESIVE.get())));
        ItemStack out = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, upgrade, h.getLevel())
                .map(r -> r.value().assemble(upgrade)).orElse(ItemStack.EMPTY);
        h.assertTrue(out.is(dev.logos.item.PageTier.CRYSTAL.inscribed()), "the written basic page became " + out);
        h.assertTrue(SpellPages.spell(out) != null && SpellPages.spell(out).name().equals("晶辉"), "the crystal page should keep the spell");
        h.succeed();
    }

    /** Paper, echo tincture and adhesive make an echo page and give the bottle back; a written crystal page with the same two becomes one and keeps its spell. */
    static void echoPageUpgradeKeepsSpell(GameTestHelper h) {
        var blank = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(Items.PAPER), new ItemStack(LogosMod.ECHO_TINCTURE.get()), new ItemStack(LogosMod.ADHESIVE.get())));
        var recipe = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, blank, h.getLevel());
        ItemStack made = recipe.map(r -> r.value().assemble(blank)).orElse(ItemStack.EMPTY);
        h.assertTrue(made.is(dev.logos.item.PageTier.ECHO.blank()), "paper, echo tincture and adhesive made " + made);
        h.assertTrue(recipe.get().value().getRemainingItems(blank).stream().anyMatch(st -> st.is(Items.GLASS_BOTTLE)), "the tincture's bottle was not given back");
        ItemStack crystal = SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, "(spell :name \"回响\" :mana 5 (heal caster 1))", null);
        var upgrade = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(LogosMod.ADHESIVE.get()), crystal, new ItemStack(LogosMod.ECHO_TINCTURE.get())));
        ItemStack out = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, upgrade, h.getLevel())
                .map(r -> r.value().assemble(upgrade)).orElse(ItemStack.EMPTY);
        h.assertTrue(out.is(dev.logos.item.PageTier.ECHO.inscribed()), "the written crystal page became " + out);
        h.assertTrue(SpellPages.spell(out) != null && SpellPages.spell(out).name().equals("回响"), "the echo page should keep the spell");
        h.succeed();
    }

    /** Paper, raven's head and adhesive make a transmuted page and give the bottle back; a written echo page with the same two becomes one and keeps its spell. */
    static void transmutedPageUpgradeKeepsSpell(GameTestHelper h) {
        var blank = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(Items.PAPER), new ItemStack(LogosMod.RAVEN_HEAD.get()), new ItemStack(LogosMod.ADHESIVE.get())));
        var recipe = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, blank, h.getLevel());
        ItemStack made = recipe.map(r -> r.value().assemble(blank)).orElse(ItemStack.EMPTY);
        h.assertTrue(made.is(dev.logos.item.PageTier.TRANSMUTED.blank()), "paper, raven's head and adhesive made " + made);
        h.assertTrue(recipe.get().value().getRemainingItems(blank).stream().anyMatch(st -> st.is(Items.GLASS_BOTTLE)), "the raven's head's bottle was not given back");
        ItemStack echo = SpellPages.inscribed(dev.logos.item.PageTier.ECHO, "(spell :name \"炼成\" :mana 5 (heal caster 1))", null);
        var upgrade = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(LogosMod.RAVEN_HEAD.get()), echo, new ItemStack(LogosMod.ADHESIVE.get())));
        ItemStack out = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, upgrade, h.getLevel())
                .map(r -> r.value().assemble(upgrade)).orElse(ItemStack.EMPTY);
        h.assertTrue(out.is(dev.logos.item.PageTier.TRANSMUTED.inscribed()), "the written echo page became " + out);
        h.assertTrue(SpellPages.spell(out) != null && SpellPages.spell(out).name().equals("炼成"), "the transmuted page should keep the spell");
        h.succeed();
    }

    /** Paper, chalk and adhesive make a chalk page; a written transmuted page with the same two becomes one and keeps its spell. */
    static void chalkPageUpgradeKeepsSpell(GameTestHelper h) {
        var blank = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(Items.PAPER), new ItemStack(LogosMod.CHALK.get()), new ItemStack(LogosMod.ADHESIVE.get())));
        ItemStack made = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, blank, h.getLevel())
                .map(r -> r.value().assemble(blank)).orElse(ItemStack.EMPTY);
        h.assertTrue(made.is(dev.logos.item.PageTier.CHALK.blank()), "paper, chalk and adhesive made " + made);
        ItemStack transmuted = SpellPages.inscribed(dev.logos.item.PageTier.TRANSMUTED, "(spell :name \"白垩\" :mana 5 (heal caster 1))", null);
        var upgrade = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(LogosMod.CHALK.get()), transmuted, new ItemStack(LogosMod.ADHESIVE.get())));
        ItemStack out = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, upgrade, h.getLevel())
                .map(r -> r.value().assemble(upgrade)).orElse(ItemStack.EMPTY);
        h.assertTrue(out.is(dev.logos.item.PageTier.CHALK.inscribed()), "the written transmuted page became " + out);
        h.assertTrue(SpellPages.spell(out) != null && SpellPages.spell(out).name().equals("白垩"), "the chalk page should keep the spell");
        h.succeed();
    }

    /** Paper, the firmament stone and adhesive make a firmament page; a written chalk page with the same two becomes one and keeps its spell. */
    static void firmamentPageUpgradeKeepsSpell(GameTestHelper h) {
        var blank = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(Items.PAPER), new ItemStack(LogosMod.FIRMAMENT_STONE.get()), new ItemStack(LogosMod.ADHESIVE.get())));
        ItemStack made = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, blank, h.getLevel())
                .map(r -> r.value().assemble(blank)).orElse(ItemStack.EMPTY);
        h.assertTrue(made.is(dev.logos.item.PageTier.FIRMAMENT.blank()), "paper, the firmament stone and adhesive made " + made);
        ItemStack chalk = SpellPages.inscribed(dev.logos.item.PageTier.CHALK, "(spell :name \"天穹\" :mana 5 (heal caster 1))", null);
        var upgrade = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(
                new ItemStack(LogosMod.FIRMAMENT_STONE.get()), chalk, new ItemStack(LogosMod.ADHESIVE.get())));
        ItemStack out = h.getLevel().recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, upgrade, h.getLevel())
                .map(r -> r.value().assemble(upgrade)).orElse(ItemStack.EMPTY);
        h.assertTrue(out.is(dev.logos.item.PageTier.FIRMAMENT.inscribed()), "the written chalk page became " + out);
        h.assertTrue(SpellPages.spell(out) != null && SpellPages.spell(out).name().equals("天穹"), "the firmament page should keep the spell");
        h.succeed();
    }

    /** The inscription table writes a spell (and its drawing) onto the page lying on it. */
    static void tableInscribes(GameTestHelper h) {
        ServerPlayer p = caster(h);
        BlockPos rel = new BlockPos(1, 2, 1);
        h.setBlock(rel, LogosMod.INSCRIPTION_TABLE.get());
        BlockPos pos = h.absolutePos(rel);
        var table = (InscriptionTableBlockEntity) h.getLevel().getBlockEntity(pos);
        table.setPage(new ItemStack(LogosMod.SPELL_PAGE.get()));
        h.assertTrue(h.getBlockState(rel).getValue(InscriptionTableBlock.PAGE) == InscriptionTableBlock.Page.BLANK, "the board should show a blank sheet");
        CompoundTag graph = new CompoundTag();
        graph.putString("marker", "drawn");
        boolean ok = InscriptionTableBlock.inscribe(p, pos, "(spell :name \"刻\" :mana 5 (heal caster 1))", graph) != null;
        h.assertTrue(ok, "inscribing should succeed");
        h.assertTrue(SpellPages.isInscribed(table.page()), "the page should now be inscribed");
        h.assertTrue(SpellPages.spell(table.page()).name().equals("刻"), "the page should carry the spell");
        h.assertTrue(table.page().has(LogosMod.SPELL_GRAPH.get()), "the page should keep the drawing");
        h.assertTrue(h.getBlockState(rel).getValue(InscriptionTableBlock.PAGE) == InscriptionTableBlock.Page.INSCRIBED, "the board should show the written sheet");
        table.setPage(ItemStack.EMPTY);
        h.assertTrue(h.getBlockState(rel).getValue(InscriptionTableBlock.PAGE) == InscriptionTableBlock.Page.NONE, "the board should be bare once the page is taken");
        h.succeed();
    }
}
