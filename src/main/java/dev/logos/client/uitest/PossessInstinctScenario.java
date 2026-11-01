package dev.logos.client.uitest;

import java.util.Arrays;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.Possession;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of the instinct key: inside a wolf, looking at a cow, the player holds V and the
 * wolf runs at the cow by itself (their client no longer steers it); letting go, they steer again.
 * Run: {@code gradlew runClient -PldTest=logos_possess_instinct}.
 */
@LDLRegisterClient(name = "logos_possess_instinct", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PossessInstinctScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private Vec3 from;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "possess").guiScale(2);
    }

    private static KeyMapping instinctKey() {
        return Arrays.stream(Minecraft.getInstance().options.keyMappings).filter(k -> k.getName().equals("key.logos.instinct"))
                .findFirst().orElseThrow();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .clearArea(ORIGIN.offset(0, 6, 4), 10)
                .fill(ORIGIN.offset(-8, 0, -8), ORIGIN.offset(8, 0, 16), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, 10)
                .server("go into a wolf, with a cow ahead", sc -> {
                    var p = sc.player();
                    Wolf wolf = EntityType.WOLF.create(p.level(), EntitySpawnReason.TRIGGERED);
                    wolf.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 2.5, 0, 0);
                    p.level().addFreshEntity(wolf);
                    Cow cow = EntityType.COW.create(p.level(), EntitySpawnReason.TRIGGERED);
                    cow.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 10.5, 180, 0);
                    cow.setNoAi(true);
                    p.level().addFreshEntity(cow);
                    if (!Possession.start(p, wolf, 60)) throw new AssertionError("the possession did not start");
                })
                .ticks(10)
                .step("look at the cow and hold the instinct key", ctx -> {
                    var p = Minecraft.getInstance().player;
                    p.setYRot(0);
                    p.setXRot(0);
                    from = p.getVehicle().position();
                    instinctKey().setDown(true);
                })
                .ticks(10)
                .step("the wolf acts on its own", ctx -> {
                    var p = Minecraft.getInstance().player;
                    if (!Possession.instinct(p.getVehicle())) throw new AssertionError("the client does not see the instinct");
                    if (p.getVehicle().getControllingPassenger() != null) throw new AssertionError("the client still steers the wolf");
                    Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
                })
                .ticks(30)
                .step("it ran at the cow", ctx -> {
                    double moved = Minecraft.getInstance().player.getVehicle().position().distanceTo(from);
                    System.out.printf("[logos] instinct: the wolf moved %.2f blocks by itself%n", moved);
                    if (moved < 2) throw new AssertionError("the wolf only moved " + moved + " blocks");
                })
                .screenshot("instinct")
                .step("let go", ctx -> instinctKey().setDown(false))
                .ticks(10)
                .step("steering again", ctx -> {
                    var p = Minecraft.getInstance().player;
                    if (Possession.instinct(p.getVehicle()) || p.getVehicle().getControllingPassenger() != p) throw new AssertionError("the player does not steer again");
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                });
    }
}
