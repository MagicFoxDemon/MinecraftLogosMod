package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.CastPoints;
import dev.logos.client.WandTips;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
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

/**
 * A beam, a ray and a projectile cast from an oak wand leave from its head, not the eyes: the wand
 * head is known after the charge has gone off, the beam starts there, first person and third, and the
 * projectile is drawn from the head at first and onto its real path a few ticks later.
 */
@LDLRegisterClient(name = "logos_wand_beam", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WandBeamScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand", "beam").guiScale(3);
    }

    private static void selectAndHold(int page) {
        var player = Minecraft.getInstance().player;
        // The server's copy is set too, through the same packet the spell wheel sends.
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new dev.logos.net.SelectSpellPayload(page));
        Minecraft.getInstance().options.keyUse.setDown(true);
    }

    /** The wand head is known now that nothing charges, and the cast point is it. */
    private static void startsAtWandHead(String view) {
        var player = Minecraft.getInstance().player;
        var tip = WandTips.sample(player.getId());
        if (tip == null) throw new AssertionError(view + ": the wand head should be known outside a charge too");
        var from = CastPoints.of(player, 1);
        if (from.distanceTo(tip) > 1e-6) throw new AssertionError(view + ": spells should leave from the wand head, not " + from);
        var local = tip.subtract(player.getEyePosition());
        LogosMod.LOGGER.info("[cast point] {}: {} from the eyes", view, local);
        if (local.dot(player.getLookAngle()) < 0.2) throw new AssertionError(view + ": the wand head should be ahead, at " + local);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 8), 10)
                .fill(ORIGIN.offset(-8, 0, -6), ORIGIN.offset(8, 0, 24), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(ORIGIN.offset(-3, 1, 16), ORIGIN.offset(3, 5, 16), Blocks.STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 0)
                .server("hand over an oak wand with a beam and a ray", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.maxMana = m.mana = 1000;
                    m.trace = false;
                    ItemStack wand = new ItemStack(LogosMod.WOOD_WANDS.getFirst().get());
                    wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                            SpellPages.inscribed(PageTier.CRYSTAL, "(spell :name \"光柱\" :mana 120 (beam :radius 0.3 :life 4 :every 0.5 (on-hit (mark target #lit))))", null),
                            SpellPages.inscribed(PageTier.CRYSTAL, "(spell :name \"射线\" :mana 20 (ray :range 24))", null),
                            SpellPages.inscribed(PageTier.CRYSTAL, "(spell :name \"弹\" :mana 20 (projectile :speed 0.4 :life 30))", null))));
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, wand);
                })
                .step("show the HUD", ctx -> Minecraft.getInstance().options.hideGui = false)
                .ticks(3)
                .step("charge the beam", ctx -> selectAndHold(0))
                .ticks(40)
                .step("let it go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(10)
                .step("first person: from the wand head", ctx -> startsAtWandHead("first person"))
                .screenshot("beam_first_person")
                .step("look from the side", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(5)
                .step("third person: from the wand head", ctx -> startsAtWandHead("third person"))
                .screenshot("beam_third_person")
                .ticks(60)
                .step("charge the ray", ctx -> selectAndHold(1))
                .ticks(15)
                .step("let it go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(2)
                .screenshot("ray_third_person")
                .step("back to first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .ticks(20)
                .step("charge the projectile", ctx -> selectAndHold(2))
                .ticks(15)
                .step("let it go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(2)
                .step("it starts at the wand head", ctx -> projectileOffPath(true))
                .screenshot("projectile_leaving")
                .ticks(12)
                .step("and joins its real path", ctx -> projectileOffPath(false))
                .screenshot("projectile_on_path");
    }

    /**
     * How far the flying body is drawn from the line it really flies (the gaze from the eyes): off it
     * toward the wand head just after leaving, on it once it has joined.
     */
    private static void projectileOffPath(boolean leaving) {
        var player = Minecraft.getInstance().player;
        var trails = dev.logos.client.LogosFx.drawnTrails();
        if (trails.size() != 1) throw new AssertionError("expected one body in flight, got " + trails.size());
        var at = trails.getFirst().subtract(player.getEyePosition());
        var gaze = player.getLookAngle();
        double off = at.subtract(gaze.scale(at.dot(gaze))).length();
        LogosMod.LOGGER.info("[projectile] {}: {} off its path, {} along it", leaving ? "leaving" : "later", off, at.dot(gaze));
        if (leaving && off < 0.2) throw new AssertionError("just after leaving it should be drawn off its path, at the wand head; it is " + off + " off");
        if (!leaving && off > 0.05) throw new AssertionError("by now it should be drawn on its path; it is " + off + " off");
    }
}
