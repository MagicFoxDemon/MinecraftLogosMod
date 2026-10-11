package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Familiars;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Shows the actual egg texture in inventory and the full-mana colossus it raises. */
@LDLRegisterClient(name = "logos_colossus_spawn_egg", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ColossusSpawnEggScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private AlchemicalColossus colossus;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "colossus", "spawn_egg").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 6), 12)
                .fill(ORIGIN.offset(-14, 0, -6), ORIGIN.offset(14, 0, 24), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, -1.5, 0, 8)
                .server("use the colossus egg", sc -> {
                    var p = sc.player();
                    ItemStack eggs = new ItemStack(LogosMod.ALCHEMICAL_COLOSSUS_SPAWN_EGG.get(), 2);
                    p.setItemInHand(InteractionHand.MAIN_HAND, eggs);
                    BlockPos floor = ORIGIN.offset(0, 0, 8);
                    var result = eggs.getItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)));
                    if (result != InteractionResult.SUCCESS || eggs.getCount() != 1) throw new AssertionError("egg placement failed");
                    for (var m : Familiars.of(p)) if (m instanceof AlchemicalColossus c) colossus = c;
                    if (colossus == null || colossus.mana() != 50000) throw new AssertionError("egg must raise a 50000-mana colossus");
                    Familiars.sendTo(colossus, colossus.position());
                })
                .ticks(70)
                .screenshot("colossus_from_egg")
                .step("open inventory", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .awaitScreen(InventoryScreen.class)
                .step("hover the egg", ctx -> ctx.input().moveTo((ctx.screen().width - 176) / 2f + 16, (ctx.screen().height - 166) / 2f + 151))
                .ticks(3)
                .screenshot("colossus_spawn_egg_inventory")
                .step("close inventory", ctx -> Minecraft.getInstance().setScreen(null));
    }
}
