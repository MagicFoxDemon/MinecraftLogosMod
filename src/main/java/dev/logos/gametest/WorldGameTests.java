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
        h.succeed();
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

    /** in-dimension maps overworld coordinates into the nether (÷8) and blink lands there, standing. */
    static void netherStep(GameTestHelper h) {
        ServerPlayer p = caster(h);
        p.snapTo(800.5, 70, 1600.5, 0, 0);
        SpellInstance inst = Casting.cast(p, Casting.compile("(spell :mana 200 (blink (in-dimension @the_nether (pos-of caster))))"), null, null);
        h.assertTrue(inst != null && !inst.dead, "blink failed: " + (inst == null ? "refused" : inst.endReason()));
        h.assertTrue(p.level().dimension() == Level.NETHER, "caster should now be in the nether, is in " + p.level().dimension());
        h.assertTrue(Math.abs(p.getX() - 100.5) < 1 && Math.abs(p.getZ() - 200.5) < 1,
                "expected x≈100, z≈200 (overworld ÷ 8), got " + p.getX() + ", " + p.getZ());
        h.succeed();
    }

    /** A wand casts the selected page; a codex in the off hand extends the list. */
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
