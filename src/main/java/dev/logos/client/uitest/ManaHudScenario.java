package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

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
 * The client-drawn mana HUD: mana and spell on their own line above the hotbar, with an action-bar message
 * from elsewhere readable beneath it at the same time, gone with F1, and fading once the wand is put away.
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
        Component line = ManaHudLayer.text();
        if (line == null) throw new AssertionError("the HUD should be showing");
        for (String part : parts) {
            if (!line.getString().contains(part)) throw new AssertionError("the HUD says \"" + line.getString() + "\", without \"" + part + "\"");
        }
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 8)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 10)
                .server("hand over an oak wand with a page", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.maxMana = 1000;
                    m.mana = 640;
                    m.trace = false;
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, holding(LogosMod.WOOD_WANDS.getFirst().get(), "(spell :name \"蓄\" :mana 100 (mark caster #hud))"));
                })
                .ticks(8)
                .step("show the HUD", ctx -> Minecraft.getInstance().options.hideGui = false)
                .ticks(2)
                .step("the HUD names mana and spell", ctx -> says("☿ ", "/1000", "《蓄》注入 100", "稳定 "))
                .screenshot("wand")
                .server("a message from elsewhere on the action bar", sc -> sc.player().sendOverlayMessage(
                        Component.literal("来自其他模组的提示 · A message from another mod").withStyle(ChatFormatting.GOLD)))
                // Longer than the old HUD's half-second refresh, which would have overwritten it by now.
                .ticks(25)
                .step("the HUD keeps updating beneath the message", ctx -> says("《蓄》注入 100"))
                .screenshot("with_action_bar")
                .server("hand over a staff holding 30, too little for its spell", sc -> {
                    ItemStack staff = holding(LogosMod.WOOD_STAFFS.getFirst().get(), "(spell :name \"杖\" :mana 100 (mark caster #hud))");
                    StaffItem.store(staff, 30);
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, staff);
                })
                .ticks(8)
                .step("the HUD shows what the staff holds", ctx -> says("杖内 30/", "《杖》注入 100"))
                .screenshot("staff_short")
                .step("press F1", ctx -> Minecraft.getInstance().options.hideGui = true)
                .ticks(2)
                .screenshot("f1_hidden")
                .step("press F1 again", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("put the staff away", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
                .ticks(48)
                .step("still fading", ctx -> says("杖内 30/"))
                .screenshot("fading")
                .ticks(25)
                .step("gone", ctx -> {
                    if (ManaHudLayer.text() != null) throw new AssertionError("the HUD should be gone, not \"" + ManaHudLayer.text().getString() + "\"");
                })
                .screenshot("gone");
    }
}
