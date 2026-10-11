package dev.logos.gametest;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Familiars;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Egg placement, native item consumption, ownership limits and a dispenser without a master. */
final class ColossusSpawnEggGameTests {
    private ColossusSpawnEggGameTests() {}

    private static InteractionResult place(GameTestHelper h, ServerPlayer p, int x) {
        BlockPos floor = h.absolutePos(new BlockPos(x, 1, 5));
        return LogosMod.ALCHEMICAL_COLOSSUS_SPAWN_EGG.get().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)));
    }

    private static AlchemicalColossus guardian(GameTestHelper h, ServerPlayer p) {
        var mine = Familiars.of(p).stream().filter(m -> m instanceof AlchemicalColossus).toList();
        h.assertTrue(mine.size() == 1, "one guardian expected, got " + mine.size());
        return (AlchemicalColossus) mine.getFirst();
    }

    private static void full(GameTestHelper h, AlchemicalColossus c) {
        h.assertTrue(c.mana() == 50000 && c.maxMana() == 50000, "egg must start at 50000 mana, got " + c.mana());
        h.assertTrue(!c.isDormant() && !c.isBerserk() && c.getHealth() == 800, "an awake, healthy normal colossus expected");
        h.assertTrue(c.act() == AlchemicalColossus.Act.STARTUP, "startup animation expected");
    }

    /** Survival spends one egg; creative keeps it, while both use the command's one-guardian limit. */
    static void player(GameTestHelper h) {
        int old = LogosConfig.MAX_COLOSSI.getAsInt();
        try {
            LogosConfig.MAX_COLOSSI.set(1);
            ServerPlayer p = FamiliarGameTests.master(h);
            ItemStack eggs = new ItemStack(LogosMod.ALCHEMICAL_COLOSSUS_SPAWN_EGG.get(), 2);
            eggs.set(DataComponents.CUSTOM_NAME, Component.literal("Copper Guardian"));
            p.setItemInHand(InteractionHand.MAIN_HAND, eggs);
            h.assertTrue(SpawnEggItem.getType(eggs) == LogosMod.ALCHEMICAL_COLOSSUS.get(), "native egg entity data expected");
            h.assertTrue(SpawnEggItem.byId(LogosMod.ALCHEMICAL_COLOSSUS.get()).orElseThrow().value() == eggs.getItem(), "native egg lookup expected");
            h.assertTrue(place(h, p, 4) == InteractionResult.SUCCESS && eggs.getCount() == 1, "survival must spend one egg");
            AlchemicalColossus first = guardian(h, p);
            full(h, first);
            h.assertTrue(p.getUUID().equals(Familiars.masterOf(first)) && first.isPersistenceRequired(), "persistent player binding expected");
            h.assertTrue(Component.literal("Copper Guardian").equals(first.getCustomName()), "egg name must carry over");
            h.assertTrue(Math.abs(first.getY() - h.absolutePos(new BlockPos(4, 2, 5)).getY()) < 0.01, "must stand on the clicked floor");
            p.setGameMode(GameType.CREATIVE);
            h.assertTrue(place(h, p, 7) == InteractionResult.SUCCESS && eggs.getCount() == 1, "creative must keep its egg");
            AlchemicalColossus second = guardian(h, p);
            full(h, second);
            h.assertTrue(second != first && first.isRemoved(), "oldest guardian must be replaced at the configured limit");
        } finally {
            LogosConfig.MAX_COLOSSI.set(old);
        }
        h.succeed();
    }

    /** A dispenser spends one egg and raises a full colossus, with no player to bind it to. */
    static void dispenser(GameTestHelper h) {
        FamiliarGameTests.master(h);
        BlockPos at = h.absolutePos(new BlockPos(4, 2, 5));
        var state = Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST);
        h.getLevel().setBlockAndUpdate(at, state);
        var block = (DispenserBlockEntity) h.getLevel().getBlockEntity(at);
        ItemStack eggs = new ItemStack(LogosMod.ALCHEMICAL_COLOSSUS_SPAWN_EGG.get(), 2);
        var behavior = DispenserBlock.DISPENSER_REGISTRY.get(eggs.getItem());
        h.assertTrue(behavior != null, "colossus egg dispenser behavior must be registered");
        behavior.dispense(new BlockSource(h.getLevel(), at, state, block), eggs);
        var spawned = h.getLevel().getEntitiesOfClass(AlchemicalColossus.class, new AABB(at.east()).inflate(2));
        h.assertTrue(eggs.getCount() == 1 && spawned.size() == 1, "one colossus for one egg expected");
        full(h, spawned.getFirst());
        h.assertTrue(Familiars.masterOf(spawned.getFirst()) == null, "dispenser must not invent a master");
        h.succeed();
    }

    /** The config's zero limit refuses placement without spending an egg. */
    static void disabled(GameTestHelper h) {
        int old = LogosConfig.MAX_COLOSSI.getAsInt();
        try {
            LogosConfig.MAX_COLOSSI.set(0);
            ServerPlayer p = FamiliarGameTests.master(h);
            ItemStack egg = new ItemStack(LogosMod.ALCHEMICAL_COLOSSUS_SPAWN_EGG.get());
            p.setItemInHand(InteractionHand.MAIN_HAND, egg);
            h.assertTrue(place(h, p, 4) == InteractionResult.FAIL && egg.getCount() == 1, "disabled colossus must not spend its egg");
            h.assertTrue(Familiars.of(p).isEmpty(), "disabled colossus must not spawn");
        } finally {
            LogosConfig.MAX_COLOSSI.set(old);
        }
        h.succeed();
    }

    /** Native source-water targeting also raises a full guardian and spends only one egg. */
    static void water(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        h.setBlock(5, 2, 5, Blocks.WATER);
        Vec3 above = h.absoluteVec(new Vec3(5.5, 5, 5.5));
        p.snapTo(above.x, above.y, above.z, 0, 90);
        ItemStack eggs = new ItemStack(LogosMod.ALCHEMICAL_COLOSSUS_SPAWN_EGG.get(), 2);
        p.setItemInHand(InteractionHand.MAIN_HAND, eggs);
        var result = eggs.getItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(result == InteractionResult.SUCCESS && eggs.getCount() == 1, "source-water use must spend one egg");
        full(h, guardian(h, p));
        h.succeed();
    }
}
