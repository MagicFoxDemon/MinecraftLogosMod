package dev.logos.client.uitest;

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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of possessed creatures' skills on the use button, where the possessor's own
 * client moves the creature: an enderman teleports where they look and a goat charges, and their
 * client follows the move instead of snapping back; an evoker raises its fangs. Each look is set a
 * few ticks before the click, so the server has it (as it always does in play, the look being sent
 * every tick).
 * Run: {@code gradlew runClient -PldTest=logos_possess_skills}.
 */
@LDLRegisterClient(name = "logos_possess_skills", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PossessSkillsScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private Vec3 from;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "possess").guiScale(2);
    }

    /** Into a new creature of {@code type}, the last one sent away so it is not in the shot. */
    private static void possess(ServerPlayer p, EntityType<? extends Mob> type, double z) {
        var last = p.getVehicle();
        Possession.end(p, false);
        if (last != null) last.discard();
        Mob mob = type.create(p.level(), EntitySpawnReason.TRIGGERED);
        mob.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + z, 0, 0);
        p.level().addFreshEntity(mob);
        if (!Possession.start(p, mob, 60)) throw new AssertionError("the " + type + " possession did not start");
    }

    private static void look(float pitch) {
        var p = Minecraft.getInstance().player;
        p.setYRot(0);
        p.setXRot(pitch);
    }

    private static void use() {
        KeyMapping.click(Minecraft.getInstance().options.keyUse.getKey());
    }

    private static Vec3 vehicle() {
        return Minecraft.getInstance().player.getVehicle().position();
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("difficulty normal")
                .runCommand("time set noon")
                .clearArea(ORIGIN.offset(0, 6, 8), 12)
                .fill(ORIGIN.offset(-8, 0, -4), ORIGIN.offset(8, 0, 24), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 0.5, 0, 0)
                .server("go into an enderman", sc -> possess(sc.player(), EntityType.ENDERMAN, 2.5))
                .ticks(10)
                .step("look down the floor ahead", ctx -> look(30))
                .ticks(3)
                .step("use", ctx -> {
                    from = vehicle();
                    use();
                })
                .ticks(10)
                .step("it went where they looked", ctx -> {
                    double moved = vehicle().distanceTo(from);
                    System.out.printf("[logos] possessed enderman teleported %.2f blocks%n", moved);
                    if (moved < 2) throw new AssertionError("the enderman stayed put on the possessor's client: " + moved);
                })
                .server("go into a goat", sc -> possess(sc.player(), EntityType.GOAT, 2.5))
                .ticks(10)
                .step("look ahead", ctx -> look(0))
                .ticks(3)
                .step("charge", ctx -> {
                    from = vehicle();
                    use();
                })
                .ticks(8)
                .step("it charged", ctx -> {
                    double moved = vehicle().distanceTo(from);
                    System.out.printf("[logos] possessed goat charged %.2f blocks%n", moved);
                    if (moved < 2) throw new AssertionError("the goat did not charge on the possessor's client: " + moved);
                })
                .server("go into an evoker", sc -> possess(sc.player(), EntityType.EVOKER, 6.5))
                .ticks(10)
                .step("look ahead from behind", ctx -> {
                    look(35); // the fangs follow the gaze's bearing; looking down shows them past the evoker
                    Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
                })
                .ticks(3)
                .step("cast", ctx -> use())
                .ticks(12)
                .screenshot("evoker_fangs")
                .step("first person again", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .server("leave", sc -> Possession.end(sc.player(), false));
    }
}
