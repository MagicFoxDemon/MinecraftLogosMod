package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.block.CultivationJarBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client look at cultivation jars: vanilla crops and the jar's own plants at several stages, two
 * of them on a medium (raw mash, solar amalgam) whose coloured layer lies on the soil; then the screen
 * of the jar growing iron, short of mana.
 * Run: {@code gradlew runClient -PldTest=logos_cultivation_jar}.
 */
@LDLRegisterClient(name = "logos_cultivation_jar", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CultivationJarScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    /** The iron jar, whose screen is opened. */
    private static final BlockPos IRON = ORIGIN.offset(8, 1, 1);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(4, 4, 1), 8)
                .fill(ORIGIN.offset(-2, 0, -3), ORIGIN.offset(10, 0, 5), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("plant a row of jars", sc -> {
                    var level = sc.player().level();
                    // Seed, stages to grow, medium, row.
                    Object[][] jars = {
                            {new ItemStack(Items.WHEAT_SEEDS), 4, new ItemStack(LogosMod.RAW_MASH.get()), 0},
                            {new ItemStack(Items.CARROT), 7, new ItemStack(LogosMod.SOLAR_AMALGAM.get()), 0},
                            {new ItemStack(LogosMod.NITER_SPROUT.get()), 3, ItemStack.EMPTY, 0},
                            {new ItemStack(LogosMod.MANDRAKE_SEED.get()), 3, ItemStack.EMPTY, 0},
                            {new ItemStack(LogosMod.JarMetal.COPPER.seed.get()), 3, ItemStack.EMPTY, 2},
                            {new ItemStack(LogosMod.JarMetal.GOLD.seed.get()), 2, ItemStack.EMPTY, 2},
                            {new ItemStack(LogosMod.MERCURY_SPORE.get()), 3, ItemStack.EMPTY, 2},
                            {new ItemStack(LogosMod.STARLIGHT_MOSS_SPORE.get()), 3, ItemStack.EMPTY, 2}};
                    for (int i = 0; i < jars.length; i++) {
                        BlockPos pos = ORIGIN.offset(i % 4 * 2, 1, (int) jars[i][3]);
                        level.setBlock(pos, LogosMod.CULTIVATION_JAR.get().defaultBlockState(), 3);
                        if (!(level.getBlockEntity(pos) instanceof CultivationJarBlockEntity jar)) continue;
                        jar.plant((ItemStack) jars[i][0]);
                        ItemStack medium = (ItemStack) jars[i][2];
                        if (!medium.isEmpty()) jar.pour(medium, sc.player());
                        jar.receiveMana(CultivationJarBlockEntity.MAX_MANA);
                        for (int g = 0; g < (int) jars[i][1]; g++) jar.grow();
                    }
                    level.setBlock(IRON, LogosMod.CULTIVATION_JAR.get().defaultBlockState(), 3);
                    if (level.getBlockEntity(IRON) instanceof CultivationJarBlockEntity jar) {
                        jar.plant(new ItemStack(LogosMod.JarMetal.IRON.seed.get()));
                        jar.receiveMana(25);
                        jar.grow();
                        jar.grow();
                        jar.reserve().set(0, net.neoforged.neoforge.transfer.item.ItemResource.of(LogosMod.RAW_MASH.get()), 2);
                    }
                })
                .server("fly, so the camera stays where it is put", sc -> {
                    sc.player().getAbilities().flying = true;
                    sc.player().onUpdateAbilities();
                })
                .ticks(10)
                .teleportPlayer(ORIGIN.getX() + 3.5, ORIGIN.getY() + 2.6, ORIGIN.getZ() + 6.2, 180, 28)
                .ticks(30)
                .screenshot("jars_from_front")
                // The metal, mushroom and moss jars, from the south; the crops and media, from the north.
                .teleportPlayer(ORIGIN.getX() + 3.5, ORIGIN.getY() + 1.5, ORIGIN.getZ() + 4.6, 180, 20)
                .ticks(15)
                .screenshot("back_row_close")
                .teleportPlayer(ORIGIN.getX() + 3.5, ORIGIN.getY() + 1.5, ORIGIN.getZ() - 2.6, 0, 20)
                .ticks(15)
                .screenshot("front_row_close")
                .teleportPlayer(ORIGIN.getX() + 3.5, ORIGIN.getY() + 2.6, ORIGIN.getZ() + 6.2, 180, 28)
                .server("open the iron jar", sc -> BlockUIMenuType.openUI((ServerPlayer) sc.player(), IRON))
                .awaitModularUI()
                .ticks(25)
                .checkExists("#jar_seed")
                .checkTextContains("#jar_status", "10")
                .screenshot("iron_jar_screen")
                .closeScreen();
    }
}
