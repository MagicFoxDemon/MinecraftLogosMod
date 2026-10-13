package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.ClientCharges;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Element;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Grand (fire, 1000) and colossal (frost, 3200) charges on the master wand, seen from the front. */
@LDLRegisterClient(name = "logos_wand_charge_tiers", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WandChargeTiersScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand", "charge").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 8)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 10)
                .server("hand over a master wand with a grand and a colossal page", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    // A slow pool: surge grows with max mana, so keep it small enough to see the charge build.
                    m.maxMana = m.mana = 100000;
                    m.trace = false;
                    ItemStack wand = new ItemStack(LogosMod.MASTER_WAND.get());
                    wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                            SpellPages.inscribed(PageTier.CHALK, "(spell :name \"焰\" :mana 1000 (damage (enemies :radius 4) standard fire))", null),
                            SpellPages.inscribed(PageTier.FIRMAMENT, "(spell :name \"霜\" :mana 3200 (damage (enemies :radius 4) standard frost))", null))));
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, wand);
                })
                .step("show the HUD, look from the front", ctx -> {
                    Minecraft.getInstance().options.hideGui = false;
                    Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                })
                .ticks(5)
                .step("hold right-click", ctx -> Minecraft.getInstance().options.keyUse.setDown(true))
                .ticks(4)
                .step("grand fire", ctx -> {
                    var c = ClientCharges.of(Minecraft.getInstance().player);
                    if (c == null || c.tier != 2 || c.element != Element.FIRE) throw new AssertionError("expected a grand fire charge");
                })
                .screenshot("grand_fire")
                .step("let go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(5)
                .server("select the colossal page", sc -> sc.player().getMainHandItem().set(LogosMod.SELECTED.get(), 1))
                .ticks(2)
                .step("hold right-click", ctx -> Minecraft.getInstance().options.keyUse.setDown(true))
                .ticks(6)
                .step("colossal frost", ctx -> {
                    var c = ClientCharges.of(Minecraft.getInstance().player);
                    if (c == null || c.tier != 3 || c.element != Element.FROST) throw new AssertionError("expected a colossal frost charge");
                })
                .screenshot("colossal_frost")
                .step("let go", ctx -> {
                    Minecraft.getInstance().options.keyUse.setDown(false);
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                });
    }
}
