package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaLifeform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The five real egg models and the correctly sized, awake lifeforms they place. */
@LDLRegisterClient(name = "logos_lifeform_spawn_eggs", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeformSpawnEggScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "lifeform", "spawn_egg").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 5), 12)
                .fill(ORIGIN.offset(-14, 0, -8), ORIGIN.offset(14, 0, 20), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, -2.5, 0, 5)
                .server("place all five egg tiers", sc -> {
                    for (int tier = 1; tier <= 5; tier++) {
                        ItemStack eggs = new ItemStack(LogosMod.MANA_LIFEFORM_SPAWN_EGGS.get(tier - 1).get(), 2);
                        BlockPos floor = ORIGIN.offset((tier - 3) * 2, 0, 5);
                        // No master here: all five stay for the camera without evicting one another.
                        var result = eggs.getItem().useOn(new UseOnContext(sc.level(), null, InteractionHand.MAIN_HAND, eggs,
                                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)));
                        if (result != InteractionResult.SUCCESS || eggs.getCount() != 1) throw new AssertionError("egg tier " + tier + " failed");
                        int lv = tier;
                        var lifeforms = sc.level().getEntitiesOfClass(ManaLifeform.class, new AABB(floor.above()).inflate(1), lf -> lf.lifeLevel() == lv);
                        if (lifeforms.size() != 1) throw new AssertionError("no tier " + tier + " lifeform");
                        var lf = lifeforms.getFirst();
                        if (Math.abs(lf.mana() - lf.maxMana()) > 0.001) throw new AssertionError("egg must start full");
                        lf.setYRot(180);
                        lf.yBodyRot = lf.yHeadRot = 180;
                        lf.setCustomName(Component.literal("Lv. " + tier));
                        lf.setCustomNameVisible(true);
                        sc.player().getInventory().setItem(tier - 1, eggs);
                    }
                })
                .ticks(100)
                .step("client received all five levels", ctx -> {
                    var level = Minecraft.getInstance().level;
                    for (int tier = 1; tier <= 5; tier++) {
                        int lv = tier;
                        var lifeforms = level.getEntitiesOfClass(ManaLifeform.class, new AABB(-10, 100, -2, 10, 110, 12),
                                lf -> lf.lifeLevel() == lv);
                        if (lifeforms.size() != 1 || lifeforms.getFirst().isDormant())
                            throw new AssertionError("client must show one awake tier " + tier + " lifeform");
                    }
                })
                .screenshot("lifeforms_from_eggs")
                .step("open inventory", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .awaitScreen(InventoryScreen.class)
                .step("show all five eggs", ctx -> ctx.input().moveTo(2, 2))
                .ticks(3)
                .screenshot("lifeform_spawn_eggs_all_levels")
                .step("hover level 1", ctx -> ctx.input().moveTo((ctx.screen().width - 176) / 2f + 16, (ctx.screen().height - 166) / 2f + 151))
                .ticks(3)
                .screenshot("lifeform_spawn_egg_level_1")
                .step("hover level 2", ctx -> ctx.input().moveTo((ctx.screen().width - 176) / 2f + 34, (ctx.screen().height - 166) / 2f + 151))
                .ticks(3)
                .screenshot("lifeform_spawn_egg_level_2")
                .step("hover level 3", ctx -> ctx.input().moveTo((ctx.screen().width - 176) / 2f + 52, (ctx.screen().height - 166) / 2f + 151))
                .ticks(3)
                .screenshot("lifeform_spawn_egg_level_3")
                .step("hover level 4", ctx -> ctx.input().moveTo((ctx.screen().width - 176) / 2f + 70, (ctx.screen().height - 166) / 2f + 151))
                .ticks(3)
                .screenshot("lifeform_spawn_egg_level_4")
                .step("hover level 5", ctx -> ctx.input().moveTo((ctx.screen().width - 176) / 2f + 88, (ctx.screen().height - 166) / 2f + 151))
                .ticks(3)
                .screenshot("lifeform_spawn_egg_level_5")
                .step("close inventory", ctx -> Minecraft.getInstance().setScreen(null));
    }
}
