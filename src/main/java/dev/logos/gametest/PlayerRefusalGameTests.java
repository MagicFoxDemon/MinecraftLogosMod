package dev.logos.gametest;

import dev.logos.runtime.Possession;
import dev.logos.runtime.TimeStop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** A player's own hands: all five uses are refused inside a creature and with their time stopped, and none otherwise. */
final class PlayerRefusalGameTests {
    private PlayerRefusalGameTests() {}

    static void refused(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        FamiliarGameTests.forceAround(h, 1);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.connection.markClientLoaded();
        Vec3 pos = h.absoluteVec(new Vec3(0.5, 2, 0.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        var pig = h.spawn(EntityType.PIG, new BlockPos(6, 2, 6));
        var cow = h.spawn(EntityType.COW, new BlockPos(2, 2, 6));

        expect(h, p, cow, false, "a free player");
        h.assertTrue(Possession.start(p, pig, 30), "the possession should start");
        expect(h, p, cow, true, "a player inside a creature");
        Possession.end(p, false);
        expect(h, p, cow, false, "a player back in their body");
        TimeStop.stop(p, 0.5);
        expect(h, p, cow, true, "a stopped player");
        h.succeedWhen(() -> {
            h.assertTrue(!TimeStop.stopped(p), "time should resume");
            expect(h, p, cow, false, "a player whose time has resumed");
        });
    }

    private static void expect(GameTestHelper h, ServerPlayer p, Entity target, boolean refused, String who) {
        BlockPos floor = h.absolutePos(new BlockPos(3, 1, 3));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false);
        check(h, new AttackEntityEvent(p, target), refused, who, "an attack");
        check(h, new PlayerInteractEvent.RightClickItem(p, InteractionHand.MAIN_HAND), refused, who, "using an item");
        check(h, new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, floor, hit), refused, who, "using a block");
        check(h, new PlayerInteractEvent.LeftClickBlock(p, floor, Direction.UP, PlayerInteractEvent.LeftClickBlock.Action.START), refused, who, "hitting a block");
        check(h, new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, target), refused, who, "handling a creature");
    }

    private static <E extends Event & ICancellableEvent> void check(GameTestHelper h, E event, boolean refused, String who, String what) {
        h.assertTrue(NeoForge.EVENT_BUS.post(event).isCanceled() == refused, who + (refused ? " should be refused " : " should be allowed ") + what);
    }
}
