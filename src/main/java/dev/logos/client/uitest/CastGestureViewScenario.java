package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.CastPoses;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.runtime.CastPose;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The cast gestures in first person. A projectile charged and let go from a wand plays the projectile
 * gesture (the whole way from the cast to the hand), and a page that leads with no form plays the plain
 * cast; then each gesture is played and caught mid-way.
 */
@LDLRegisterClient(name = "logos_cast_gestures_view", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class CastGestureViewScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand", "pose").guiScale(3);
    }

    private static void plays(String what, Identifier expected) {
        var a = CastPoses.of(Minecraft.getInstance().player, 0);
        LogosMod.LOGGER.info("[cast gesture] {}: {}", what, a);
        if (a == null || !a.pose().equals(expected)) throw new AssertionError(what + " should play " + expected + ", shows " + a);
    }

    private static void select(int page) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.logos.net.SelectSpellPayload(page));
    }

    private static void play(ScenarioBuilder s, String name, Identifier gesture, int at, int length) {
        // Caught by the pose's own clock (a scenario tick is not a client tick), which stops for the screenshot.
        s.server("play " + name, sc -> CastPose.play(sc.player(), gesture))
                .waitUntil(name + " reaches " + at, ctx -> {
                    var a = CastPoses.of(Minecraft.getInstance().player, 0);
                    if (a == null || !a.pose().equals(gesture) || a.age() < at) return false;
                    dev.logos.client.ClientCharges.freeze(true);
                    return true;
                })
                .screenshot(name)
                .step("start the clock", ctx -> dev.logos.client.ClientCharges.freeze(false))
                .waitUntil(name + " is over", ctx -> CastPoses.of(Minecraft.getInstance().player, 0) == null);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 8), 10)
                .fill(ORIGIN.offset(-8, 0, -6), ORIGIN.offset(8, 0, 24), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 0)
                .server("hand over an oak wand with a projectile and a formless page", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.maxMana = m.mana = 1000;
                    m.trace = false;
                    ItemStack wand = new ItemStack(LogosMod.WOOD_WANDS.getFirst().get());
                    wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                            SpellPages.inscribed(PageTier.CRYSTAL, "(spell :name \"弹\" :mana 20 (projectile :speed 0.6 :life 2))", null),
                            SpellPages.inscribed(PageTier.CRYSTAL, "(spell :name \"愈\" :mana 20 (heal caster 1))", null))));
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, wand);
                })
                .step("show the HUD (and with it the hand)", ctx -> Minecraft.getInstance().options.hideGui = false)
                .ticks(3)
                .step("charge the projectile", ctx -> {
                    select(0);
                    Minecraft.getInstance().options.keyUse.setDown(true);
                })
                .ticks(30)
                .step("let it go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(3)
                .step("the projectile gesture plays", ctx -> plays("a projectile cast", CastPose.PROJECTILE))
                .screenshot("cast_projectile")
                .ticks(15)
                .step("charge the formless page", ctx -> {
                    select(1);
                    Minecraft.getInstance().options.keyUse.setDown(true);
                })
                .ticks(30)
                .step("let it go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(3)
                .step("the plain cast plays", ctx -> plays("a formless cast", CastPose.CAST))
                .ticks(15);
        play(s, "ray", CastPose.RAY, 3, 7);
        play(s, "field", CastPose.FIELD, 13, 26);
        play(s, "construct", CastPose.CONSTRUCT, 6, 12);
        play(s, "vein", CastPose.VEIN, 5, 13);
        play(s, "vein_flick", CastPose.VEIN, 9, 13);
        play(s, "imbue", CastPose.IMBUE, 8, 18);
        play(s, "cast", CastPose.CAST, 5, 8);
    }
}
