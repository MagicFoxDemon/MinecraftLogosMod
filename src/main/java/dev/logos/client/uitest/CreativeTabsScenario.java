package dev.logos.client.uitest;

import java.util.function.Supplier;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;

/** The five Logos creative tabs, each opened in the creative inventory, with a check of what each holds. */
@LDLRegisterClient(name = "logos_creative_tabs", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CreativeTabsScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "items").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .ticks(5)
                .step("open the creative inventory", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false));
                })
                .awaitScreen(CreativeModeInventoryScreen.class);
        tab(s, "spell_supplies", LogosMod.TAB::get, LogosMod.WAND.get(), LogosMod.ALCHEMY_TABLE_ITEM.get(), LogosMod.ALCHEMICAL_COLOSSUS_SPAWN_EGG.get());
        tab(s, "spell_examples", LogosMod.EXAMPLES_TAB::get, LogosMod.INSCRIBED_PAGE.get(), LogosMod.WAND.get());
        tab(s, "great_work", LogosMod.OPUS_TAB::get, LogosMod.ANCHOR_NAIL.get(), LogosMod.SPELL_CODEX.get());
        tab(s, "spawn_eggs", LogosMod.SPAWN_EGGS_TAB::get, LogosMod.MANA_LEECH_SPAWN_EGG.get(), LogosMod.ALCHEMY_TABLE_ITEM.get());
        tab(s, "potions", LogosMod.POTIONS_TAB::get, net.minecraft.world.item.Items.TIPPED_ARROW, LogosMod.ALCHEMY_TABLE_ITEM.get());
        s.step("logos potions are only in their tab", ctx -> {
            var potions = LogosMod.POTIONS_TAB.get().getDisplayItems();
            if (potions.isEmpty() || !potions.stream().allMatch(dev.logos.alchemy.BuffPotions::isLogosPotion))
                throw new AssertionError("the potions tab should hold Logos potions only");
            var food = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(net.minecraft.world.item.CreativeModeTabs.FOOD_AND_DRINKS)
                    .getDisplayItems();
            if (food.stream().anyMatch(dev.logos.alchemy.BuffPotions::isLogosPotion) || food.stream().noneMatch(st -> st.is(net.minecraft.world.item.Items.POTION)))
                throw new AssertionError("food and drinks should keep vanilla potions and lose Logos ones");
        });
        s.step("close", ctx -> Minecraft.getInstance().setScreen(null));
    }

    /** Opens {@code tab} after checking it holds {@code in} and none of {@code out}. */
    private static void tab(ScenarioBuilder s, String name, Supplier<CreativeModeTab> tab, Item in, Item... out) {
        s.step("open " + name, ctx -> {
                    CreativeModeTab t = tab.get();
                    boolean has = t.getDisplayItems().stream().anyMatch(st -> st.is(in));
                    boolean stray = t.getDisplayItems().stream().anyMatch(st -> java.util.Arrays.stream(out).anyMatch(st::is));
                    if (!has || stray) throw new AssertionError(name + ": expected " + in + " and none of " + java.util.Arrays.toString(out));
                    try {
                        var select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
                        select.setAccessible(true);
                        select.invoke(ctx.screen(), t);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .ticks(3)
                .screenshot("creative_tab_" + name);
    }
}
