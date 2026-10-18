package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;

/**
 * The alchemy table's recipes in JEI, and the recipes that make an echo page. Needs JEI in the
 * run, so it is kept out of the logos group: {@code gradlew runClient -Pjei -PldTest=logos_jei_alchemy}.
 */
@LDLRegisterClient(name = "logos_jei_alchemy", group = "logos_jei", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class JeiAlchemyScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "jei", "alchemy").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .step("JEI is installed", ctx -> {
                    if (!ModList.get().isLoaded("jei")) throw new AssertionError("run with -Pjei");
                })
                .ticks(40)
                .step("show the alchemy recipes", ctx -> dev.logos.compat.jei.LogosJeiPlugin.showAlchemyRecipes())
                .ticks(10)
                .screenshot("jei_alchemy")
                .step("show how an echo page is made", ctx -> dev.logos.compat.jei.LogosJeiPlugin.showRecipesFor(dev.logos.item.PageTier.ECHO.inscribed()))
                .ticks(10)
                .screenshot("jei_echo_page");
    }
}
