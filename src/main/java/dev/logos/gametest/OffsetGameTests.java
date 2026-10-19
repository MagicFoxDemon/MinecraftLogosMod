package dev.logos.gametest;

import dev.logos.LogosMod;
import dev.logos.block.SpellEmitterBlock;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.graph.SpecialNodes;
import dev.logos.item.DividersItem;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The offset rune and the dividers that measure for it, both in the emitter's terms: forward is where it faces. */
final class OffsetGameTests {
    private OffsetGameTests() {}

    private static SpellEmitterBlockEntity emitter(GameTestHelper h, BlockPos at, Direction facing) {
        h.setBlock(at, LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, facing));
        return (SpellEmitterBlockEntity) h.getBlockEntity(at, SpellEmitterBlockEntity.class);
    }

    /** An emitter facing {@code facing} that gives 10 mana to {@code (offset f u r)} as soon as it is charged. */
    private static void caster(GameTestHelper h, BlockPos at, Direction facing, String offset) {
        SpellEmitterBlockEntity e = emitter(h, at, facing);
        e.setPage(SpellPages.inscribed(PageTier.CRYSTAL, "(spell :mana 30 (infuse-block (offset " + offset + ") 10))", null), null);
        e.edit(e.page(), SpecialNodes.EmitterMode.FULL, 1, h.makeMockPlayer(GameType.CREATIVE));
        e.receiveMana(32);
    }

    private static double mana(GameTestHelper h, BlockPos at) {
        return ((SpellEmitterBlockEntity) h.getBlockEntity(at, SpellEmitterBlockEntity.class)).mana();
    }

    /**
     * Facing east, (offset 2 1 1) is two east, one up and one south (east's right hand); facing up,
     * forward is south, so (offset 2 0 -1) is two south and one east (south's left hand).
     */
    static void offsetFollowsTheFacing(GameTestHelper h) {
        BlockPos level = new BlockPos(1, 2, 1), up = new BlockPos(5, 2, 1);
        BlockPos levelTarget = level.east(2).above().south(), upTarget = up.south(2).east();
        emitter(h, levelTarget, Direction.UP);
        emitter(h, upTarget, Direction.UP);
        caster(h, level, Direction.EAST, "2 1 1");
        caster(h, up, Direction.UP, "2 0 -1");
        h.succeedWhen(() -> {
            h.assertTrue(Math.abs(mana(h, levelTarget) - 10) < 1e-6, "east-facing: the block at forward 2, up 1, right 1 got " + mana(h, levelTarget));
            h.assertTrue(Math.abs(mana(h, upTarget) - 10) < 1e-6, "up-facing: the block at forward 2, left 1 got " + mana(h, upTarget));
        });
    }

    private static void use(Player p, GameTestHelper h, BlockPos at) {
        ItemStack held = p.getItemInHand(InteractionHand.MAIN_HAND);
        BlockPos abs = h.absolutePos(at);
        held.getItem().onItemUseFirst(held, new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(abs.getCenter(), Direction.UP, abs, false)));
    }

    /**
     * The dividers set on a west-facing emitter and then on a block measure it as forward, up and right
     * of the emitter, the numbers that offset needs; sneaking folds them up.
     */
    static void dividersMeasureFromTheEmitter(GameTestHelper h) {
        BlockPos from = new BlockPos(4, 2, 2);
        emitter(h, from, Direction.WEST);
        BlockState stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        // West-facing: forward is west, right is north.
        BlockPos to = from.west(3).above(1).north(2);
        h.setBlock(to, stone);
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.DIVIDERS_ITEM.get()));
        use(p, h, from);
        use(p, h, to);
        DividersItem.Measure m = DividersItem.measured(p.getMainHandItem());
        h.assertTrue(m != null && m.f() == 3 && m.u() == 1 && m.r() == 2, "measured " + m);
        h.assertTrue(DividersItem.carried(p) == m, "the workbench would not find the measurement");
        p.setShiftKeyDown(true);
        use(p, h, to);
        h.assertTrue(p.getMainHandItem().get(LogosMod.DIVIDERS.get()) == null, "sneaking did not fold the dividers up");
        h.succeed();
    }
}
