package dev.logos.gametest;

import java.util.function.BooleanSupplier;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;

import dev.logos.LogosMod;
import dev.logos.runtime.Colossi;
import dev.logos.runtime.Lifeforms;
import dev.logos.runtime.PossessionInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Every interface opened the way the server opens it. A server builds the whole UI as well, to hold its slots and its
 * data, so nothing on that path may need the client: a dedicated server has no such classes, and a player opening one
 * would take the server down with them.
 */
final class ServerUiGameTests {
    private ServerUiGameTests() {}

    private static ServerPlayer player(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(0.5, 2, 0.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        return p;
    }

    /**
     * Opens an interface: the UI is built, and then a mock player's connection refuses the packet telling the client to
     * show it, which is as far as this has to go. Any other failure is a failure to build it.
     */
    private static void assertBuilds(GameTestHelper h, String what, BooleanSupplier open) {
        try {
            h.assertTrue(open.getAsBoolean(), what + " has no interface to open");
        } catch (UnsupportedOperationException e) {
            h.assertTrue(e.getMessage() != null && e.getMessage().contains("may not be sent"), what + " failed to build: " + e);
        }
    }

    static void alchemyTable(GameTestHelper h) {
        AlchemyGameTests.placeTable(h);
        ServerPlayer p = player(h);
        assertBuilds(h, "the alchemy table", () -> BlockUIMenuType.openUI(p, h.absolutePos(AlchemyGameTests.MAIN)));
        h.succeed();
    }

    static void devices(GameTestHelper h) {
        ServerPlayer p = player(h);
        int x = 2;
        for (Block block : new Block[] {LogosMod.COPY_TABLE.get(), LogosMod.CULTIVATION_JAR.get(), LogosMod.MANA_COLLECTOR.get(),
                LogosMod.MANA_SPREADER.get(), LogosMod.MANA_STORAGE_TANK.get()}) {
            BlockPos at = new BlockPos(x++, 2, 2);
            h.setBlock(at, block);
            assertBuilds(h, block.getDescriptionId(), () -> BlockUIMenuType.openUI(p, h.absolutePos(at)));
        }
        h.succeed();
    }

    static void heldItems(GameTestHelper h) {
        ServerPlayer p = player(h);
        for (ItemStack held : new ItemStack[] {new ItemStack(LogosMod.WAND.get()), new ItemStack(LogosMod.SPELL_CODEX.get())}) {
            p.setItemInHand(InteractionHand.MAIN_HAND, held);
            assertBuilds(h, held.getItem().toString(), () -> HeldItemUIMenuType.openUI(p, InteractionHand.MAIN_HAND));
        }
        h.succeed();
    }

    static void panels(GameTestHelper h) {
        ServerPlayer p = player(h);
        Lifeforms.opening(p, h.spawn(LogosMod.MANA_LIFEFORM.get(), new Vec3(4.5, 2, 4.5)).getId());
        assertBuilds(h, "a lifeform's core", () -> PlayerUIMenuType.openUI(p, Lifeforms.CORE_UI));
        Lifeforms.opening(p, h.spawn(LogosMod.ALCHEMICAL_COLOSSUS.get(), new Vec3(8.5, 2, 8.5)).getId());
        assertBuilds(h, "a colossus's devices", () -> PlayerUIMenuType.openUI(p, Colossi.DEVICES_UI));
        Cow cow = h.spawn(EntityType.COW, new Vec3(2.5, 2, 8.5));
        h.assertTrue(PossessionInventory.menu(1, p.getInventory(), cow) != null, "a possessed creature's inventory should build");
        h.succeed();
    }
}
