package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Colossi;
import dev.logos.runtime.Familiars;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Real-client check of the alchemical colossus up close: a grab and a slam, then the frenzy and
 * its scorched glowing shell, and burnt out (split from the cannon scenario to keep each run short). Run: {@code gradlew runClient -PldTest=logos_colossus_melee}.
 */
@LDLRegisterClient(name = "logos_colossus_melee", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ColossusMeleeScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private AlchemicalColossus colossus;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "colossus").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                // Hostile targets must not vanish on peaceful.
                .runCommand("difficulty normal")
                .clearArea(ORIGIN.offset(0, 6, 8), 12)
                .fill(ORIGIN.offset(-14, 0, -6), ORIGIN.offset(14, 0, 24), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() - 1.5, 0, 8)
                .server("raise a colossus and post it ahead", sc -> {
                    var p = sc.player();
                    Vec3 at = Vec3.atBottomCenterOf(ORIGIN.offset(0, 1, 8));
                    Colossi.summon(p, p.level(), at, null);
                    for (var m : Familiars.of(p)) if (m instanceof AlchemicalColossus c) colossus = c;
                    // Posted more than six blocks off, it stands there rather than at the player's side.
                    Familiars.sendTo(colossus, at);
                })
                .ticks(70)
                .screenshot("colossus_guard")
                .server("a zombie right in front of it", sc -> {
                    Zombie z = EntityType.ZOMBIE.create(sc.level(), EntitySpawnReason.COMMAND);
                    // Squarely between it and the camera, within reach.
                    z.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 5.6, 180, 0);
                    z.setNoAi(true);
                    z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
                    z.setHealth(200);
                    sc.level().addFreshEntity(z);
                    Familiars.order(colossus, z);
                })
                .ticks(60)
                .screenshot("grab_hold")
                .ticks(45)
                .screenshot("grab_slam")
                .server("into a frenzy", sc -> {
                    sc.level().getEntitiesOfClass(Zombie.class, colossus.getBoundingBox().inflate(30)).forEach(e -> e.discard());
                    colossus.frenzy(null);
                })
                .ticks(30)
                .screenshot("frenzy")
                .server("struck down mid-frenzy", sc -> colossus.hurtServer(sc.level(), sc.level().damageSources().generic(), 100000))
                .ticks(90)
                .screenshot("burnt_out");
    }
}
