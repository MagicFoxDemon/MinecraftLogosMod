package dev.logos.gametest;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.ManaLifeform;
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

/** All five egg tiers, full pools, ownership, native consumption, water and dispensers. */
final class LifeformSpawnEggGameTests {
    private LifeformSpawnEggGameTests() {}

    private static InteractionResult place(GameTestHelper h, ServerPlayer p) {
        BlockPos floor = h.absolutePos(new BlockPos(5, 1, 5));
        return p.getMainHandItem().getItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)));
    }

    private static ManaLifeform guardian(GameTestHelper h, ServerPlayer p) {
        var mine = Familiars.of(p).stream().filter(m -> m instanceof ManaLifeform).toList();
        h.assertTrue(mine.size() == 1, "one lifeform expected, got " + mine.size());
        return (ManaLifeform) mine.getFirst();
    }

    private static void full(GameTestHelper h, ManaLifeform lf, int level) {
        h.assertTrue(lf.lifeLevel() == level, "egg must raise level " + level + ", got " + lf.lifeLevel());
        // Mana is synced as a float; allow its rounding at the irrational level-2/3/5 capacities.
        h.assertTrue(Math.abs(lf.mana() - lf.maxMana()) < 0.001, "level " + level + " must start full, got " + lf.mana() + "/" + lf.maxMana());
        h.assertTrue(lf.getHealth() == ManaLifeform.health(level) && !lf.isDormant(), "a healthy, awake lifeform expected");
    }

    /** All tiers keep their level and name, start full, and obey the same limit as spell summoning. */
    static void levels(GameTestHelper h) {
        int old = LogosConfig.MAX_LIFEFORMS.getAsInt();
        try {
            LogosConfig.MAX_LIFEFORMS.set(1);
            ServerPlayer p = FamiliarGameTests.master(h);
            ManaLifeform previous = null;
            h.assertTrue(LogosMod.MANA_LIFEFORM_SPAWN_EGGS.size() == 5, "five separate eggs expected");
            for (int level = 1; level <= 5; level++) {
                ItemStack eggs = new ItemStack(LogosMod.MANA_LIFEFORM_SPAWN_EGGS.get(level - 1).get(), 2);
                eggs.set(DataComponents.CUSTOM_NAME, Component.literal("Lifeform " + level));
                p.setItemInHand(InteractionHand.MAIN_HAND, eggs);
                h.assertTrue(SpawnEggItem.spawnsEntity(eggs, LogosMod.MANA_LIFEFORM.get()), "native egg entity data expected");
                h.assertTrue(place(h, p) == InteractionResult.SUCCESS && eggs.getCount() == 1, "survival must spend one level " + level + " egg");
                ManaLifeform lf = guardian(h, p);
                full(h, lf, level);
                h.assertTrue(p.getUUID().equals(Familiars.masterOf(lf)) && lf.isPersistenceRequired(), "persistent player binding expected");
                h.assertTrue(Component.literal("Lifeform " + level).equals(lf.getCustomName()), "egg name must carry over");
                if (previous != null) h.assertTrue(previous.isRemoved(), "oldest lifeform must make room at the limit");
                previous = lf;
            }
            p.setGameMode(GameType.CREATIVE);
            h.assertTrue(place(h, p) == InteractionResult.SUCCESS && p.getMainHandItem().getCount() == 1, "creative must keep the egg");
            full(h, guardian(h, p), 5);
            h.assertTrue(previous.isRemoved(), "creative placement also obeys the owner limit");
        } finally {
            LogosConfig.MAX_LIFEFORMS.set(old);
        }
        h.succeed();
    }

    /** Each tier also works from a dispenser, full and with no invented master. */
    static void dispenser(GameTestHelper h) {
        FamiliarGameTests.master(h);
        for (int level = 1; level <= 5; level++) {
            BlockPos at = h.absolutePos(new BlockPos(4, 2, level * 2));
            var state = Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST);
            h.getLevel().setBlockAndUpdate(at, state);
            var block = (DispenserBlockEntity) h.getLevel().getBlockEntity(at);
            ItemStack eggs = new ItemStack(LogosMod.MANA_LIFEFORM_SPAWN_EGGS.get(level - 1).get(), 2);
            var behavior = DispenserBlock.DISPENSER_REGISTRY.get(eggs.getItem());
            h.assertTrue(behavior != null, "tier " + level + " dispenser behavior must be registered");
            behavior.dispense(new BlockSource(h.getLevel(), at, state, block), eggs);
            int tier = level;
            var spawned = h.getLevel().getEntitiesOfClass(ManaLifeform.class, new AABB(at.east()).inflate(2), lf -> lf.lifeLevel() == tier);
            h.assertTrue(eggs.getCount() == 1 && spawned.size() == 1, "one lifeform for one tier " + level + " egg expected");
            full(h, spawned.getFirst(), level);
            h.assertTrue(Familiars.masterOf(spawned.getFirst()) == null, "a dispenser has no master");
        }
        h.succeed();
    }

    /** A zero lifeform limit refuses every tier without spending any eggs. */
    static void disabled(GameTestHelper h) {
        int old = LogosConfig.MAX_LIFEFORMS.getAsInt();
        try {
            LogosConfig.MAX_LIFEFORMS.set(0);
            ServerPlayer p = FamiliarGameTests.master(h);
            for (var item : LogosMod.MANA_LIFEFORM_SPAWN_EGGS) {
                ItemStack egg = new ItemStack(item.get());
                p.setItemInHand(InteractionHand.MAIN_HAND, egg);
                h.assertTrue(place(h, p) == InteractionResult.FAIL && egg.getCount() == 1, "disabled placement must keep the egg");
            }
            h.assertTrue(Familiars.of(p).isEmpty(), "disabled lifeforms must not spawn");
        } finally {
            LogosConfig.MAX_LIFEFORMS.set(old);
        }
        h.succeed();
    }

    /** Source-water targeting preserves the highest egg's level and full pool. */
    static void water(GameTestHelper h) {
        ServerPlayer p = FamiliarGameTests.master(h);
        h.setBlock(5, 2, 5, Blocks.WATER);
        Vec3 above = h.absoluteVec(new Vec3(5.5, 5, 5.5));
        p.snapTo(above.x, above.y, above.z, 0, 90);
        ItemStack eggs = new ItemStack(LogosMod.MANA_LIFEFORM_SPAWN_EGGS.get(4).get(), 2);
        p.setItemInHand(InteractionHand.MAIN_HAND, eggs);
        var result = eggs.getItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(result == InteractionResult.SUCCESS && eggs.getCount() == 1, "source-water use must spend one egg");
        full(h, guardian(h, p), 5);
        h.succeed();
    }
}
