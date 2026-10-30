package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Holding the real use key with a chant charm hung on the wand keeps casting: a wand fires each charge as
 * it fills and starts the next, a staff fires every shortest charge time out of what it holds.
 */
@LDLRegisterClient(name = "logos_chant_charm", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ChantCharmScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    /** 20 a cast: on an oak wand with a full 1000 pool it charges in about 0.4 s. */
    private static final String SPELL = "(spell :name \"连\" :mana 20 (mark caster #chant))";

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand", "charge").guiScale(3);
    }

    /** {@code item} holding the 20-mana page and a chant charm. */
    private static ItemStack charmed(Item item) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed(PageTier.CRYSTAL, SPELL, null), new ItemStack(LogosMod.CHANT_CHARM.get()))));
        return s;
    }

    private static void hold(boolean down) {
        Minecraft.getInstance().options.keyUse.setDown(down);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 8)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 10)
                .server("hand over a charmed oak wand", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.maxMana = m.mana = 1000;
                    m.trace = false;
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, charmed(LogosMod.WOOD_WANDS.getFirst().get()));
                })
                .ticks(5)
                .step("show the HUD", ctx -> Minecraft.getInstance().options.hideGui = false)
                .step("hold right-click", ctx -> hold(true))
                .ticks(40)
                .server("the wand kept casting", sc -> {
                    // About 0.4 s a charge: three or four in two seconds, each paying 20 (regeneration aside).
                    double paid = 1000 - PlayerMagic.of(sc.player()).mana;
                    if (paid < 50 || paid > 120) throw new AssertionError("expected three or four casts of 20 in two seconds, paid " + paid);
                    if (!sc.player().isUsingItem()) throw new AssertionError("the wand should still be charging the next one");
                })
                .screenshot("wand_chanting")
                .step("let go", ctx -> hold(false))
                .ticks(5)
                .server("hand over a charmed oak staff holding 100", sc -> {
                    ItemStack staff = charmed(LogosMod.WOOD_STAFFS.getFirst().get());
                    StaffItem.store(staff, 100);
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, staff);
                })
                .ticks(5)
                .step("hold right-click", ctx -> hold(true))
                .ticks(12)
                .server("the staff casts every shortest charge time", sc -> {
                    // Cast on press, then every 5 ticks: three casts in the first 10-odd ticks.
                    double stored = StaffItem.stored(sc.player().getMainHandItem());
                    if (stored < 20 || stored > 60) throw new AssertionError("expected two or three casts of 20 so far, holding " + stored);
                })
                .screenshot("staff_chanting")
                .ticks(20)
                .server("until the staff runs out", sc -> {
                    ServerPlayer p = sc.player();
                    double stored = StaffItem.stored(p.getMainHandItem());
                    if (stored > 1e-6) throw new AssertionError("five casts should empty the staff, holding " + stored);
                    if (p.isUsingItem()) throw new AssertionError("an empty staff should be let go");
                })
                .step("let go", ctx -> hold(false));
    }
}
