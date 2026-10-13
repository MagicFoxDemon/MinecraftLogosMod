package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.ClientCharges;
import dev.logos.client.WandTips;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Element;
import dev.logos.lang.Status;
import dev.logos.runtime.Charging;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
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
 * Holding the real use key charges a wand: the client sees itself using it at full speed, the crosshair
 * ring follows the server's charge, and letting go casts.
 */
@LDLRegisterClient(name = "logos_wand_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WandChargeScenario implements UIScenario {
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
                .server("hand over an oak wand with a 100-mana page", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.maxMana = m.mana = 1000;
                    m.trace = false;
                    ItemStack wand = new ItemStack(LogosMod.WOOD_WANDS.getFirst().get());
                    wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                            SpellPages.inscribed(PageTier.CRYSTAL, "(spell :name \"蓄\" :mana 100 (mark caster #charged))", null))));
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, wand);
                })
                .ticks(5)
                .step("show the HUD", ctx -> Minecraft.getInstance().options.hideGui = false)
                .step("hold right-click", ctx -> Minecraft.getInstance().options.keyUse.setDown(true))
                .ticks(12)
                .step("charging, not slowed", ctx -> {
                    var player = Minecraft.getInstance().player;
                    if (!player.isUsingItem()) throw new AssertionError("the client should be using the wand");
                    if (player.getUseItem().getOrDefault(DataComponents.USE_EFFECTS, null).speedMultiplier() != 1f)
                        throw new AssertionError("charging must not slow the player");
                    var ring = ClientCharges.of(player);
                    if (ring == null || ring.full || ring.progress(0) <= 0 || ring.progress(0) >= 1)
                        throw new AssertionError("the ring should show a partial charge");
                    if (ring.element != Element.ARCANE || ring.tier != 1) throw new AssertionError("a 100-mana mark is an elaborate arcane charge");
                    tipNearHand(player, "first person");
                })
                .server("part-way charged", sc -> {
                    var c = Charging.of(sc.player());
                    if (c == null || c.full() || c.injected <= 0) throw new AssertionError("expected a partial charge, got " + (c == null ? null : c.injected));
                })
                .screenshot("charging")
                .ticks(30)
                .server("full and holding", sc -> {
                    var c = Charging.of(sc.player());
                    if (c == null || !c.full()) throw new AssertionError("the charge should be full by now");
                    if (StatusData.stacks(sc.player(), new Status("#charged")) != 0) throw new AssertionError("cast before release");
                    // 100 paid, about 0.3 a tick regained meanwhile; after the cast its leftovers come back as free mana.
                    double left = PlayerMagic.of(sc.player()).mana;
                    if (left < 880 || left > 920) throw new AssertionError("expected about 900 mana left, got " + left);
                })
                .step("ring full", ctx -> {
                    var ring = ClientCharges.of(Minecraft.getInstance().player);
                    if (ring == null || !ring.full) throw new AssertionError("the ring should show a full charge");
                })
                .screenshot("charged")
                .step("look from the front", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(3)
                .step("third-person tip", ctx -> tipNearHand(Minecraft.getInstance().player, "third person"))
                .screenshot("charged_front")
                .step("back to first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .step("let go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(5)
                .server("released and cast", sc -> {
                    if (Charging.of(sc.player()) != null) throw new AssertionError("the charge should be spent");
                    if (StatusData.stacks(sc.player(), new Status("#charged")) != 1) throw new AssertionError("letting go should cast");
                })
                .step("ring gone", ctx -> {
                    if (ClientCharges.of(Minecraft.getInstance().player) != null) throw new AssertionError("the ring should be gone after the cast");
                })
                .screenshot("released");
    }

    /** The wand head was drawn just now, ahead of the player and to their right, between chest and eyes. */
    private static void tipNearHand(net.minecraft.world.entity.player.Player player, String view) {
        var tip = WandTips.sample(player.getId());
        if (tip == null) throw new AssertionError(view + ": the wand head was not reported");
        var local = tip.subtract(player.getEyePosition());
        dev.logos.LogosMod.LOGGER.info("[wand tip] {}: {} from the eyes", view, local);
        var look = player.getLookAngle();
        var right = look.cross(new net.minecraft.world.phys.Vec3(0, 1, 0)).normalize();
        if (local.length() > 1.6 || local.y > 0.4 || local.y < -1.2 || local.dot(look) < 0.2 || local.dot(right) < 0.1)
            throw new AssertionError(view + ": wand head at " + local + " from the eyes, not ahead and to the right");
    }
}
