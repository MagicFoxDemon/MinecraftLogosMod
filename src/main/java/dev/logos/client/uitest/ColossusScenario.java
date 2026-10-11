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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Real-client check of the alchemical colossus: standing guard, the palm cannon gathering and
 * firing, a grab, the frenzy's scorched glowing shell, and burnt out. Run: {@code gradlew runClient -PldTest=logos_colossus}.
 * <p>
 * While it charges and fires, its palm must stay raised from one act to the next: switching acts once
 * blended the arm toward its rest pose for a few frames, so it visibly dropped and rose again. Firing,
 * its arm must point at the husk (below it and off to the side), not straight ahead.
 */
@LDLRegisterClient(name = "logos_colossus", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ColossusScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private AlchemicalColossus colossus;
    /** Lowest the raised palm was seen (above its feet) while charging or firing, sampled every client tick. */
    private static double lowestPalm = Double.MAX_VALUE;
    private static int watchedId = -1;

    static {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            var level = Minecraft.getInstance().level;
            if (watchedId < 0 || level == null || !(level.getEntity(watchedId) instanceof AlchemicalColossus c)) return;
            if (c.act() != AlchemicalColossus.Act.CANNON_CHARGE && c.act() != AlchemicalColossus.Act.CANNON_FIRE) return;
            Vec3 palm = c.drawnBone("attachment_muzzle_right");
            if (palm != null) lowestPalm = Math.min(lowestPalm, palm.y - c.getY());
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
                .step("watch its palm", ctx -> {
                    lowestPalm = Double.MAX_VALUE;
                    watchedId = colossus.getId();
                })
                .ticks(45)
                .screenshot("cannon_gathering")
                .ticks(40)
                .step("its arm points at the husk", ctx -> {
                    if (!(Minecraft.getInstance().level.getEntity(colossus.getId()) instanceof AlchemicalColossus c)) throw new AssertionError("no colossus on the client");
                    Vec3 shoulder = c.drawnBone("right_upper_arm"), palm = c.drawnBone("attachment_muzzle_right");
                    Vec3 husk = Vec3.atBottomCenterOf(ORIGIN.offset(9, 1, 14)).add(0, 1, 0);
                    double off = Math.toDegrees(Math.acos(palm.subtract(shoulder).normalize().dot(husk.subtract(shoulder).normalize())));
                    System.out.printf("[logos] cannon arm is %.1f degrees off the husk%n", off);
                    if (off > 8) throw new AssertionError("the cannon arm points " + off + " degrees away from its target");
                    // The server fires from where it reckons the turned palm is: that must be the drawn palm.
                    double miss = c.palm().distanceTo(palm);
                    System.out.printf("[logos] reckoned palm is %.2f blocks from the drawn one%n", miss);
                    if (miss > 0.3) throw new AssertionError("the reckoned palm is " + miss + " blocks from the drawn one");
                })
                .screenshot("cannon_firing")
                .step("its palm stayed raised", ctx -> {
                    watchedId = -1;
                    System.out.printf("[logos] lowest palm while charging and firing: %.2f%n", lowestPalm);
                    // Raised it is about 2.75 above its feet (a little lower aimed down at the husk); hanging at rest, about 1.1.
                    if (lowestPalm < 2.0) throw new AssertionError("the raised palm dropped to " + lowestPalm + " between cannon acts");
                });
    }
}
