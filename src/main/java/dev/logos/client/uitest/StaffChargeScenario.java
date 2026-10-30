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
import dev.logos.item.StaffItem;
import dev.logos.lang.Element;
import dev.logos.lang.Status;
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
 * Holding the real attack key fills a staff: the charge effects show at its head in the selected spell's
 * element, graded by what the staff holds; letting go keeps the mana in it, and right-click then casts at once.
 */
@LDLRegisterClient(name = "logos_staff_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class StaffChargeScenario implements UIScenario {
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
                .server("hand over an empty oak staff with a 100-mana page", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.maxMana = m.mana = 1000;
                    m.trace = false;
                    ItemStack staff = new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get());
                    staff.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                            SpellPages.inscribed(PageTier.CRYSTAL, "(spell :name \"蓄\" :mana 100 (mark caster #staff_cast))", null))));
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, staff);
                })
                .ticks(5)
                .step("show the HUD", ctx -> Minecraft.getInstance().options.hideGui = false)
                .step("hold left-click", ctx -> Minecraft.getInstance().options.keyAttack.setDown(true))
                .ticks(12)
                .step("filling", ctx -> {
                    var player = Minecraft.getInstance().player;
                    var ring = ClientCharges.of(player);
                    if (ring == null || ring.full || ring.progress(0) <= 0 || ring.progress(0) >= 1)
                        throw new AssertionError("the charge effects should show a part-filled staff");
                    if (ring.element != Element.ARCANE || ring.tier != 0) throw new AssertionError("under 100 held is an ordinary charge in the spell's element");
                    tipNearHand(player, "first person");
                })
                .server("part-way filled", sc -> {
                    double stored = StaffItem.stored(sc.player().getMainHandItem());
                    if (stored <= 0 || stored >= 100) throw new AssertionError("expected a part-filled staff, got " + stored);
                })
                .screenshot("filling")
                .ticks(50)
                .server("full", sc -> {
                    double stored = StaffItem.stored(sc.player().getMainHandItem());
                    if (Math.abs(stored - 150) > 1e-6) throw new AssertionError("the staff should hold its 150, got " + stored);
                    if (StatusData.stacks(sc.player(), new Status("#staff_cast")) != 0) throw new AssertionError("filling must not cast");
                })
                .step("full and graded by what it holds", ctx -> {
                    var ring = ClientCharges.of(Minecraft.getInstance().player);
                    if (ring == null || !ring.full || ring.tier != 1) throw new AssertionError("150 held is a full, elaborate charge");
                })
                .screenshot("filled")
                .step("look from the front", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(3)
                .step("third-person tip", ctx -> tipNearHand(Minecraft.getInstance().player, "third person"))
                .screenshot("filled_front")
                .step("back to first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .step("let go", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .ticks(5)
                .server("the mana stays in the staff", sc -> {
                    if (PlayerMagic.of(sc.player()).fill != null) throw new AssertionError("letting go should end the fill");
                    double stored = StaffItem.stored(sc.player().getMainHandItem());
                    if (Math.abs(stored - 150) > 1e-6) throw new AssertionError("letting go should keep the 150, got " + stored);
                })
                .step("effects gone", ctx -> {
                    if (ClientCharges.of(Minecraft.getInstance().player) != null) throw new AssertionError("the charge effects should end with the fill");
                })
                .step("right-click", ctx -> Minecraft.getInstance().options.keyUse.setDown(true))
                .ticks(3)
                .step("let go of right-click", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(3)
                .server("cast at once from the staff", sc -> {
                    if (StatusData.stacks(sc.player(), new Status("#staff_cast")) != 1) throw new AssertionError("right-click should cast once");
                    double stored = StaffItem.stored(sc.player().getMainHandItem());
                    if (Math.abs(stored - 50) > 1e-6) throw new AssertionError("the cast should take 100 of the 150, left " + stored);
                })
                .step("released", ctx -> {
                    var end = dev.logos.client.ChargeEffects.last();
                    var player = Minecraft.getInstance().player;
                    if (end == null || !end.released() || end.entityId() != player.getId() || end.element() != Element.ARCANE || end.tier() != 1)
                        throw new AssertionError("the cast should play an elaborate arcane release, got " + end);
                })
                .screenshot("released");
    }

    /** The staff head was drawn just now, ahead of the player and to their right, near eye height. */
    private static void tipNearHand(net.minecraft.world.entity.player.Player player, String view) {
        var tip = WandTips.sample(player.getId());
        if (tip == null) throw new AssertionError(view + ": the staff head was not reported");
        var local = tip.subtract(player.getEyePosition());
        dev.logos.LogosMod.LOGGER.info("[staff tip] {}: {} from the eyes", view, local);
        var look = player.getLookAngle();
        var right = look.cross(new net.minecraft.world.phys.Vec3(0, 1, 0)).normalize();
        if (local.length() > 2 || local.y > 0.8 || local.y < -1.2 || local.dot(look) < 0.1 || local.dot(right) < 0.05)
            throw new AssertionError(view + ": staff head at " + local + " from the eyes, not ahead and to the right");
    }
}
