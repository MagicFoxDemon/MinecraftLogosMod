package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.client.ManaHudLayer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The pixel-drawn mana HUD: in its corner with a wand in hand, caught halfway through its slide, in each of the four
 * corners and with an offset, short of a staff's spell, alone while the pool refills with no wand, gone with F1, and
 * slid out once the pool is full and still. An action-bar message from elsewhere stays readable the whole time.
 */
@LDLRegisterClient(name = "logos_mana_hud", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaHudScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand", "hud").guiScale(3);
    }

    private static ItemStack holding(Item item, String src) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(SpellPages.inscribed(PageTier.CRYSTAL, src, null))));
        return s;
    }

    private static void says(String... parts) {
        String line = ManaHudLayer.line();
        if (line == null) throw new AssertionError("the HUD should be showing");
        for (String part : parts) {
            if (!line.contains(part)) throw new AssertionError("the HUD says \"" + line + "\", without \"" + part + "\"");
        }
    }

    private static void slidIn() {
        if (ManaHudLayer.slide() < 1) throw new AssertionError("the HUD should be all the way in, is at " + ManaHudLayer.slide());
    }

    private static void corner(LogosConfig.Corner corner, int x, int y) {
        LogosConfig.MANA_HUD_CORNER.set(corner);
        LogosConfig.MANA_HUD_OFFSET_X.set(x);
        LogosConfig.MANA_HUD_OFFSET_Y.set(y);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 8)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 10)
                .step("default corner, the HUD shown", ctx -> {
                    corner(LogosConfig.Corner.BOTTOM_LEFT, 0, 0);
                    Minecraft.getInstance().options.hideGui = false;
                })
                .server("hand over an oak wand with a page, 640 of 1000 mana", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.baseMaxMana = 1000;
                    m.mana = 640;
                    m.trace = false;
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, holding(LogosMod.WOOD_WANDS.getFirst().get(), "(spell :name \"蓄\" :mana 100 (mark caster #hud))"));
                    sc.player().sendOverlayMessage(Component.literal("来自其他模组的提示 · A message from another mod").withStyle(ChatFormatting.GOLD));
                })
                .ticks(24)
                .step("the HUD names mana and spell, all the way in", ctx -> {
                    says("/10", "蓄");
                    slidIn();
                })
                .screenshot("wand_bottom_left")
                .step("hold it halfway through its slide", ctx -> ManaHudLayer.holdSlide(0.5f))
                .ticks(2)
                .screenshot("sliding_halfway")
                .step("let it move again", ctx -> ManaHudLayer.holdSlide(null))
                .step("top left, with the recipe and tutorial toasts cleared from the top right", ctx -> {
                    corner(LogosConfig.Corner.TOP_LEFT, 0, 0);
                    Minecraft.getInstance().getToastManager().clear();
                })
                .ticks(2)
                .screenshot("corner_top_left")
                .step("top right", ctx -> {
                    corner(LogosConfig.Corner.TOP_RIGHT, 0, 0);
                    Minecraft.getInstance().getToastManager().clear();
                })
                .ticks(2)
                .screenshot("corner_top_right")
                .step("bottom right", ctx -> corner(LogosConfig.Corner.BOTTOM_RIGHT, 0, 0))
                .ticks(2)
                .screenshot("corner_bottom_right")
                .step("bottom left, moved in 24 by 40", ctx -> corner(LogosConfig.Corner.BOTTOM_LEFT, 24, 40))
                .ticks(2)
                .screenshot("offset_bottom_left")
                .step("back to the default", ctx -> corner(LogosConfig.Corner.BOTTOM_LEFT, 0, 0))
                .server("hand over a staff holding 30, too little for its spell", sc -> {
                    ItemStack staff = holding(LogosMod.WOOD_STAFFS.getFirst().get(), "(spell :name \"杖\" :mana 100 (mark caster #hud))");
                    StaffItem.store(staff, 30);
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, staff);
                })
                .ticks(24)
                .step("the HUD shows what the staff holds", ctx -> says("30/", "杖"))
                .screenshot("staff_short")
                .step("press F1", ctx -> Minecraft.getInstance().options.hideGui = true)
                .ticks(2)
                .screenshot("f1_hidden")
                .step("press F1 again", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("put the staff away, 300 of 1000 mana left", sc -> {
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    PlayerMagic.of(sc.player()).mana = 300;
                })
                .ticks(24)
                .step("with no wand, a pool short of full still shows", ctx -> {
                    says("/10");
                    if (ManaHudLayer.line().contains("蓄") || ManaHudLayer.line().contains("杖")) throw new AssertionError("with no wand the HUD should name no spell: " + ManaHudLayer.line());
                    slidIn();
                })
                .screenshot("refilling_no_wand")
                .server("fill the pool", sc -> PlayerMagic.of(sc.player()).mana = PlayerMagic.maxMana(sc.player()))
                .ticks(30)
                .step("a full pool lingers a while after it changed", ctx -> slidIn())
                .ticks(60)
                .step("then it is gone", ctx -> {
                    if (ManaHudLayer.slide() > 0) throw new AssertionError("the HUD should have slid out, is at " + ManaHudLayer.slide());
                    if (ManaHudLayer.text() != null) throw new AssertionError("the HUD should be gone, not \"" + ManaHudLayer.line() + "\"");
                })
                .screenshot("gone");
    }
}
