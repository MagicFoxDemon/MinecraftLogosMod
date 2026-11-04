package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.CastPoints;
import dev.logos.client.CastPoses;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.runtime.CastPose;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The beam pose: a wand let go into a beam stays pointed down the gaze, and a staff cast into one is
 * held out level, until the beam ends; then the arm goes back. First person, from behind and from the front.
 */
@LDLRegisterClient(name = "logos_beam_pose", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class BeamPoseScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private static final String BEAM = "(spell :name \"光柱\" :mana 120 (beam :radius 0.3 :life 3 :every 0.5))";

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "wand", "beam", "pose").guiScale(3);
    }

    private static ItemStack holding(ItemStack item) {
        item.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(SpellPages.inscribed(PageTier.CRYSTAL, BEAM, null))));
        return item;
    }

    private static void camera(CameraType type) {
        Minecraft.getInstance().options.setCameraType(type);
    }

    /** Whether the player shows the beam pose now, and the beam leaves from the wand head as it is drawn posed. */
    private static void posed(String view, boolean expected) {
        var player = Minecraft.getInstance().player;
        var a = CastPoses.of(player, 0);
        boolean on = a != null && a.pose().equals(CastPose.BEAM);
        LogosMod.LOGGER.info("[beam pose] {}: {}", view, a);
        if (on != expected) throw new AssertionError(view + ": the beam pose should be " + (expected ? "on" : "off") + ", got " + a);
        if (expected) {
            var local = CastPoints.of(player, 1).subtract(player.getEyePosition());
            LogosMod.LOGGER.info("[beam pose] {}: wand head {} from the eyes", view, local);
            if (local.dot(player.getLookAngle()) < 0.2) throw new AssertionError(view + ": the wand head should be ahead, at " + local);
        }
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 8), 10)
                .fill(ORIGIN.offset(-8, 0, -6), ORIGIN.offset(8, 0, 24), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 0)
                .server("hand over an oak wand with a beam", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.maxMana = m.mana = 1000;
                    m.trace = false;
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, holding(new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())));
                })
                .step("show the HUD (and with it the hand)", ctx -> Minecraft.getInstance().options.hideGui = false)
                .ticks(3)
                .step("charge the beam", ctx -> Minecraft.getInstance().options.keyUse.setDown(true))
                .ticks(40)
                .step("let it go", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(8)
                .step("wand, first person", ctx -> posed("wand first person", true))
                .screenshot("wand_first_person")
                .step("from behind", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .ticks(3)
                .step("wand, from behind", ctx -> posed("wand third person", true))
                .screenshot("wand_back")
                .step("from the front", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                .ticks(3)
                .screenshot("wand_front")
                .ticks(60)
                .step("the beam is over: the arm goes back", ctx -> posed("wand after", false))
                .screenshot("wand_after")
                .server("swap to a filled oak staff", sc -> {
                    ItemStack staff = holding(new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get()));
                    StaffItem.store(staff, 150);
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, staff);
                })
                .step("back to first person", ctx -> camera(CameraType.FIRST_PERSON))
                .ticks(5)
                .step("cast from the staff", ctx -> Minecraft.getInstance().options.keyUse.setDown(true))
                .ticks(2)
                .step("let go of the button", ctx -> Minecraft.getInstance().options.keyUse.setDown(false))
                .ticks(8)
                .step("staff, first person", ctx -> posed("staff first person", true))
                .screenshot("staff_first_person")
                .step("from behind", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .ticks(3)
                .screenshot("staff_back")
                .step("from the front", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                .ticks(3)
                .step("staff, from the front", ctx -> posed("staff third person", true))
                .screenshot("staff_front")
                .ticks(60)
                .step("the beam is over: the arm goes back", ctx -> posed("staff after", false))
                // From the side, on a mannequin holding the pose (any caster may), with each item in hand.
                .server("a mannequin holds the beam pose with a wand", sc -> {
                    var level = sc.player().level();
                    Mannequin body = EntityType.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED);
                    body.snapTo(0.5, 101, 4.5, -90, 0);
                    body.setYHeadRot(-90);
                    body.setYBodyRot(-90);
                    body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
                    level.addFreshEntity(body);
                    side = CastPose.hold(body, CastPose.BEAM);
                })
                .teleportPlayer(0.5, 101, 7.5, 180, 0)
                .step("first person", ctx -> camera(CameraType.FIRST_PERSON))
                .ticks(8)
                .screenshot("side_wand")
                .server("and with a staff", sc -> {
                    var bodies = sc.player().level().getEntitiesOfClass(Mannequin.class, new AABB(ORIGIN).inflate(8));
                    bodies.forEach(b -> b.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get())));
                })
                .ticks(4)
                .screenshot("side_staff")
                .server("let go", sc -> side.release())
                .ticks(8)
                .screenshot("side_after");
    }

    private static CastPose.Hold side;
}
