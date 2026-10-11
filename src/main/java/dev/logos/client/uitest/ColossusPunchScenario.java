package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Colossi;
import dev.logos.runtime.Familiars;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Real-client check that the colossus's fist lands on what it strikes: three husks crowd before it,
 * so it swings at the one it was set on, and its fist must come to that husk's middle as the blow
 * lands (where the world is held still for a picture).
 * Run: {@code gradlew runClient -PldTest=logos_colossus_punch}.
 */
@LDLRegisterClient(name = "logos_colossus_punch", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ColossusPunchScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    /** How close its fist must come to the husk's middle. */
    static final double WITHIN = 0.4;
    private static AlchemicalColossus colossus;
    private static int targetId = -1, punchingSince = -1;
    private static double closest = Double.MAX_VALUE;
    private static boolean frozen;

    static {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            var level = Minecraft.getInstance().level;
            if (colossus == null || targetId < 0 || level == null || !(level.getEntity(colossus.getId()) instanceof AlchemicalColossus c)) return;
            // Measured up to the blow (struck, the husk is thrown back).
            if (c.act() != AlchemicalColossus.Act.PUNCH || !(level.getEntity(targetId) instanceof LivingEntity t) || t.hurtTime > 0) return;
            if (punchingSince < 0) punchingSince = c.tickCount;
            Vec3 fist = c.drawnBone("attachment_muzzle_right");
            if (fist != null) closest = Math.min(closest, fist.distanceTo(t.getBoundingBox().getCenter()));
            // A tick before the blow lands: hold the world still for the picture.
            if (!frozen && c.tickCount - punchingSince >= 17) {
                frozen = true;
                var server = Minecraft.getInstance().getSingleplayerServer();
                if (server != null) server.execute(() -> server.tickRateManager().setFrozen(true));
            }
        });
    }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "colossus").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("difficulty normal")
                .clearArea(ORIGIN.offset(0, 6, 2), 10)
                .fill(ORIGIN.offset(-8, 0, -8), ORIGIN.offset(8, 0, 10), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 8.5, ORIGIN.getY() + 2, ORIGIN.getZ() + 3, 90, 10)
                .server("raise a colossus", sc -> {
                    Colossi.summon(sc.player(), sc.level(), Vec3.atBottomCenterOf(ORIGIN.offset(0, 1, 1)), null);
                    for (var m : Familiars.of(sc.player())) if (m instanceof AlchemicalColossus c) colossus = c;
                    Familiars.sendTo(colossus, Vec3.atBottomCenterOf(ORIGIN.offset(0, 1, 1)));
                    colossus.setYRot(0);
                    colossus.yBodyRot = 0;
                })
                .ticks(70)
                .server("three husks before it", sc -> {
                    Husk target = null;
                    for (double x : new double[] {-0.8, 0.5, 1.8}) {
                        Husk h = EntityType.HUSK.create(sc.level(), EntitySpawnReason.COMMAND);
                        h.snapTo(ORIGIN.getX() + x, ORIGIN.getY() + 1, ORIGIN.getZ() + 4.3, 180, 0);
                        h.setNoAi(true);
                        sc.level().addFreshEntity(h);
                        if (x == 0.5) target = h;
                    }
                    targetId = target.getId();
                    Familiars.order(colossus, target);
                })
                .ticks(80)
                .screenshot("punch_lands")
                .server("let the world go on", sc -> sc.level().getServer().tickRateManager().setFrozen(false))
                .ticks(5)
                .step("its fist landed", ctx -> {
                    System.out.printf("[logos] fist came within %.2f of the husk's middle%n", closest);
                    if (closest > WITHIN) throw new AssertionError("the fist only came within " + closest + " of the husk's middle");
                });
    }
}
