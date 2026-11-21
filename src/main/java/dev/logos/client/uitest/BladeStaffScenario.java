package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.BladeStaffStance;
import dev.logos.client.ClientCharges;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * The blade staff's two holds: on a mannequin from the side and the front, crown up at rest, blade forward after a
 * swing and still forward after the next, crown up again once the blows stop; then in first person, and charging
 * a spell (crown up, the charge at the crown) in first and third person.
 */
@LDLRegisterClient(name = "logos_blade_staff", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class BladeStaffScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand", "pose").guiScale(3);
    }

    private static List<Mannequin> mannequins(net.minecraft.world.level.Level level) {
        return level.getEntitiesOfClass(Mannequin.class, new AABB(ORIGIN).inflate(8));
    }

    private static void hold(ScenarioBuilder s, Item item) {
        s.server("hand the mannequin " + item, sc -> mannequins(sc.player().level())
                .forEach(b -> b.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item))));
    }

    private static void swing(ScenarioBuilder s) {
        s.server("the mannequin swings", sc -> mannequins(sc.player().level()).forEach(b -> b.swing(InteractionHand.MAIN_HAND, true)));
    }

    private static float mannequinBlade() {
        for (var e : Minecraft.getInstance().level.entitiesForRendering()) {
            if (e instanceof Mannequin) return BladeStaffStance.blade(e.getId(), true, 0);
        }
        return -1;
    }

    private static float playerBlade() {
        return BladeStaffStance.blade(Minecraft.getInstance().player.getId(), true, 0);
    }

    private static void expect(String what, float actual, float wanted) {
        if (Math.abs(actual - wanted) > 1e-3) throw new AssertionError("expected " + what + " " + wanted + ", got " + actual);
    }

    /** Rest, a swing, a second swing while the blade is forward, then the turn back once the blows stop. */
    private static void stances(ScenarioBuilder s, String view) {
        s.ticks(5).step(view + " rests crown up", ctx -> expect("the rest hold", mannequinBlade(), 0)).screenshot(view + "_rest");
        swing(s);
        s.waitUntil(view + " starts turning", ctx -> {
            if (mannequinBlade() <= 0) return false;
            ClientCharges.freeze(true);
            return true;
        }).screenshot(view + "_turning").step("start the clock", ctx -> ClientCharges.freeze(false));
        s.waitUntil(view + " turns the blade forward", ctx -> mannequinBlade() >= 1).ticks(4).screenshot(view + "_blade");
        swing(s);
        s.ticks(6).step(view + " keeps the blade forward", ctx -> expect("the hold after a second swing", mannequinBlade(), 1));
        s.waitUntil(view + " turns back", ctx -> mannequinBlade() <= 0).screenshot(view + "_back");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-8, 0, -6), ORIGIN.offset(8, 0, 16), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("a mannequin facing east", sc -> {
                    var level = sc.player().level();
                    Mannequin body = EntityType.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED);
                    body.snapTo(0.5, 101, 4.5, -90, 0);
                    body.setYHeadRot(-90);
                    body.setYBodyRot(-90);
                    level.addFreshEntity(body);
                })
                .teleportPlayer(0.5, 101, 7.5, 180, 0)
                .step("hide the HUD", ctx -> Minecraft.getInstance().options.hideGui = true);
        hold(s, LogosMod.BLADE_STAFFS.getFirst().get());
        stances(s, "side");
        s.teleportPlayer(3.5, 101, 4.5, 90, 0);
        stances(s, "front");
        // A layered model: its floating pieces and runes turn with the body.
        s.teleportPlayer(0.5, 101, 7.5, 180, 0);
        hold(s, LogosMod.BLADE_STAFFS.get(1).get());
        s.ticks(5).screenshot("crystal_rest");
        swing(s);
        s.waitUntil("the crystal blade turns forward", ctx -> mannequinBlade() >= 1).screenshot("crystal_blade");

        // First person, then charging a spell: crown up, the charge gathering at the crown.
        s.server("remove the mannequin", sc -> mannequins(sc.player().level()).forEach(b -> b.discard()))
                .step("show the hand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .teleportPlayer(0.5, 101, 0.5, 0, 10)
                .server("give the player an oak blade staff with a page", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.baseMaxMana = m.mana = 100000;
                    m.trace = false;
                    ItemStack staff = new ItemStack(LogosMod.BLADE_STAFFS.getFirst().get());
                    staff.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                            SpellPages.inscribed(PageTier.BASIC, "(spell :name \"焰\" :mana 50 (damage (enemies :radius 4) standard fire))", null))));
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, staff);
                })
                .ticks(20)
                .step("first person rests crown up", ctx -> expect("the rest hold", playerBlade(), 0))
                .screenshot("view_rest")
                .step("swing", ctx -> Minecraft.getInstance().player.swing(InteractionHand.MAIN_HAND))
                .waitUntil("the blade starts turning in view", ctx -> {
                    if (playerBlade() <= 0) return false;
                    ClientCharges.freeze(true);
                    return true;
                })
                .screenshot("view_turning")
                .step("start the clock", ctx -> ClientCharges.freeze(false))
                .waitUntil("the blade turns forward in view", ctx -> playerBlade() >= 1)
                .ticks(4)
                .screenshot("view_blade")
                .waitUntil("the crown turns back up in view", ctx -> playerBlade() <= 0)
                .ticks(4)
                .screenshot("view_back")
                .step("hold right-click", ctx -> Minecraft.getInstance().options.keyUse.setDown(true))
                .waitUntil("charging turns the crown up", ctx -> ClientCharges.of(Minecraft.getInstance().player) != null && playerBlade() <= 0)
                .ticks(2)
                .screenshot("view_charge")
                .step("look from the front", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(2)
                .screenshot("front_charge")
                .step("let go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(3)
                .step("back to first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }
}
