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
 * Real-client check of the alchemical colossus: standing guard, the palm cannon gathering and
 * firing, a grab, the frenzy's scorched glowing shell, and burnt out. Run: {@code gradlew runClient -PldTest=logos_colossus}.
 */
@LDLRegisterClient(name = "logos_colossus", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ColossusScenario implements UIScenario {
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
                .server("a husk far off to its side", sc -> {
                    Husk h = EntityType.HUSK.create(sc.level(), EntitySpawnReason.COMMAND);
                    h.snapTo(ORIGIN.getX() + 9.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 14.5, 0, 0);
                    h.setNoAi(true);
                    h.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400);
                    h.setHealth(400);
                    sc.level().addFreshEntity(h);
                    Familiars.order(colossus, h);
                })
                .ticks(45)
                .screenshot("cannon_gathering")
                .ticks(40)
                .screenshot("cannon_firing");
    }
}
