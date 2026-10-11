package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.AlchemicalColossus;
import dev.logos.runtime.Colossi;
import dev.logos.runtime.Familiars;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Real-client check that the colossus's grabbing hand reaches the creature it grabs, whatever its
 * size: a warden standing tall here, a cave spider low on the ground in {@link Low}. While it reaches,
 * the palm (between its fingers) must come to where it should close on the creature.
 * Run: {@code gradlew runClient -PldTest=logos_colossus_reach} (and {@code logos_colossus_reach_low}).
 */
@LDLRegisterClient(name = "logos_colossus_reach", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ColossusReachScenario implements UIScenario {
    /** The cave spider, low on the ground. */
    @LDLRegisterClient(name = "logos_colossus_reach_low", group = "logos", registry = UIScenario.REGISTRY,
            environment = RegistrationEnvironment.DEV_ONLY)
    public static class Low extends ColossusReachScenario {
        @Override
        EntityType<? extends Mob> creature() {
            return EntityType.CAVE_SPIDER;
        }

        @Override
        double within() {
            return LOW_WITHIN;
        }
    }

    /**
     * How close the palm must come: to a tall creature's middle, and to a small one's top. Its arm,
     * with the torso bowed as far as it goes, ends about half a block above the ground: a small creature
     * on the ground is at the very edge of its reach.
     */
    static final double WITHIN = 0.35, LOW_WITHIN = 0.35;

    EntityType<? extends Mob> creature() {
        return EntityType.WARDEN;
    }

    double within() {
        return WITHIN;
    }

    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private static AlchemicalColossus colossus;
    /** The creature being reached for, and the closest the palm came to where it should close on it while reaching. */
    private static int victimId = -1;
    private static double closest = Double.MAX_VALUE;
    /** When it began reaching (client ticks), and whether the world was frozen at the moment of the catch for a picture. */
    private static int reachingSince = -1;
    private static boolean frozen;

    static {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            var level = Minecraft.getInstance().level;
            if (colossus == null || victimId < 0 || level == null || !(level.getEntity(colossus.getId()) instanceof AlchemicalColossus c)) return;
            if (c.act() != AlchemicalColossus.Act.GRAB_REACH || !(level.getEntity(victimId) instanceof LivingEntity v) || v.isPassenger()) return;
            if (reachingSince < 0) reachingSince = c.tickCount;
            Vec3 palm = c.drawnBone("attachment_muzzle_right");
            Vec3 aim = c.reachPoint();
            if (palm != null && aim != null) closest = Math.min(closest, palm.distanceTo(aim));
            // A tick before its hand closes: hold the world still for the picture.
            if (!frozen && c.tickCount - reachingSince >= 16) {
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

    private static void offer(ServerContext sc, EntityType<? extends Mob> type) {
        Mob m = type.create(sc.level(), EntitySpawnReason.COMMAND);
        // Just within its hand's reach (its edge 0.15 from the colossus's, clear of its feet), so it reaches at once.
        m.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 1.5 + 0.95 + m.getBbWidth() / 2 + 0.15, 180, 0);
        m.setNoAi(true);
        sc.level().addFreshEntity(m);
        victimId = m.getId();
        closest = Double.MAX_VALUE;
        reachingSince = -1;
        frozen = false;
        Familiars.order(colossus, m);
    }

    private void check() {
        String what = creature().toShortString();
        System.out.printf("[logos] palm came within %.2f of where it should close on the %s%n", closest, what);
        if (closest > within()) throw new AssertionError("the palm only came within " + closest + " of the " + what);
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
                    // Posted where it stands (well away from the player), facing the spot the creatures appear at.
                    Familiars.sendTo(colossus, Vec3.atBottomCenterOf(ORIGIN.offset(0, 1, 1)));
                    colossus.setYRot(0);
                    colossus.yBodyRot = 0;
                })
                .ticks(70)
                .server("a creature before it", sc -> offer(sc, creature()))
                .ticks(80)
                .screenshot("reaching")
                .server("let the world go on", sc -> sc.level().getServer().tickRateManager().setFrozen(false))
                .ticks(5)
                .step("reached it", ctx -> check());
    }
}
