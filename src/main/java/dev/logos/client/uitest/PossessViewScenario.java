package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.PossessionView;
import dev.logos.runtime.Possession;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of possession: the player goes into a cow and looks out of its eyes (no cow
 * model, no hands of their own), walks it with their own keys, sees it from behind in third person
 * with their body standing where they left it (drawn as the player, helmet and all), and sneaks
 * back into that body.
 * Run: {@code gradlew runClient -PldTest=logos_possess_view}.
 */
@LDLRegisterClient(name = "logos_possess_view", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PossessViewScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private Cow cow;
    private Vec3 walkedFrom;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "possess").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-10, 0, -8), ORIGIN.offset(10, 0, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(ORIGIN.offset(-2, 1, 10), ORIGIN.offset(2, 3, 10), Blocks.OAK_PLANKS.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, 10)
                .server("go into a cow in front", sc -> {
                    var p = sc.player();
                    // A mannequin never wears the player's things: a helmet on the body shows it is drawn as the player.
                    p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                    cow = EntityType.COW.create(p.level(), EntitySpawnReason.TRIGGERED);
                    cow.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 3.5, 0, 0);
                    p.level().addFreshEntity(cow);
                    if (!Possession.start(p, cow, 60)) throw new AssertionError("the possession did not start");
                })
                .ticks(20)
                .step("the client knows it is inside", ctx -> {
                    var p = Minecraft.getInstance().player;
                    if (!Possession.possessing(p)) throw new AssertionError("the client does not see the possession");
                    double eyeGap = Math.abs(p.getEyeY() - p.getVehicle().getEyeY());
                    System.out.printf("[logos] possess: player eye %.3f off the cow's%n", eyeGap);
                    if (eyeGap > 0.05) throw new AssertionError("the player's eyes are " + eyeGap + " off the cow's");
                    var bodies = p.level().getEntitiesOfClass(Mannequin.class, new AABB(ORIGIN.above()).inflate(1));
                    if (bodies.size() != 1 || PossessionView.owner(bodies.getFirst()) != p) throw new AssertionError("the body is not drawn as its owner: " + bodies);
                })
                .screenshot("first_person")
                .step("walk forward", ctx -> {
                    walkedFrom = Minecraft.getInstance().player.getVehicle().position();
                    Minecraft.getInstance().options.keyUp.setDown(true);
                })
                .ticks(20)
                .step("check it moved", ctx -> {
                    Minecraft.getInstance().options.keyUp.setDown(false);
                    double perTick = Minecraft.getInstance().player.getVehicle().position().distanceTo(walkedFrom) / 20;
                    System.out.printf("[logos] possessed cow walks %.3f blocks/tick%n", perTick);
                    if (perTick < 0.05) throw new AssertionError("the cow barely moved: " + perTick + " blocks/tick");
                })
                .step("look from behind", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(5)
                .screenshot("third_person")
                .step("turn to the body", ctx -> {
                    var p = Minecraft.getInstance().player;
                    p.setYRot(180);
                    p.setXRot(15);
                })
                .ticks(5)
                .screenshot("the_body")
                .step("sneak out", ctx -> Minecraft.getInstance().options.keyShift.setDown(true))
                .ticks(10)
                .step("back in the body", ctx -> {
                    Minecraft.getInstance().options.keyShift.setDown(false);
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                    var p = Minecraft.getInstance().player;
                    if (Possession.possessing(p) || p.getVehicle() != null) throw new AssertionError("still inside the cow");
                    if (p.position().distanceTo(Vec3.atBottomCenterOf(ORIGIN.above())) > 0.1) throw new AssertionError("not back at the body: " + p.position());
                })
                .ticks(5)
                .screenshot("back");
    }
}
