package dev.logos.client.uitest;

import java.util.List;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.LogosMod;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Vfx;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The fire ray from a real cast: in the open, into a wall and into a cow; first person (the muzzle must
 * not cover the view), from the side, third person over the full 64-block range, at night and in a quick
 * volley. Every stage sample is a fresh cast, and the last one checks the smoke really goes away.
 */
@LDLRegisterClient(name = "logos_fire_ray", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FireRayScenario implements UIScenario {
    private static final String RAY = "(spell :mana 10 (ray :range 30 (on-hit (damage target 1 fire))))";
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "ray", "fire").guiScale(2); }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 100000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static boolean last(Vfx.RayEnd end) {
        LogosFx.FiredRay ray = LogosFx.lastRay();
        return ray != null && ray.element() == Element.FIRE && ray.end() == end;
    }

    /** Ends every ray still showing, so the next stage sample starts from a clean line. */
    @SuppressWarnings("unchecked")
    private static void clearRays() {
        try {
            var field = LogosFx.class.getDeclaredField("RAYS");
            field.setAccessible(true);
            List<Object> rays = (List<Object>) field.get(null);
            for (Object live : rays) {
                var visual = live.getClass().getDeclaredMethod("visual");
                visual.setAccessible(true);
                Object v = visual.invoke(live);
                var end = v.getClass().getDeclaredMethod("end");
                end.setAccessible(true);
                end.invoke(v);
            }
            rays.clear();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 106, 16), 22)
                .fill(new BlockPos(-20, 100, -10), new BlockPos(20, 100, 40), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .ticks(8);

        // A wall at z = 12.
        s.fill(new BlockPos(-2, 101, 12), new BlockPos(2, 105, 12), Blocks.STONE.defaultBlockState())
                .step("clear", ctx -> clearRays())
                .server("ray into the wall", sc -> cast(sc.player(), RAY))
                .ticks(1).screenshot("fp_block_peak")
                .check("a blocked ray ends on the block", ctx -> last(Vfx.RayEnd.BLOCK))
                .check("the fire ray draws itself", ctx -> LogosFx.liveRays() > 0)
                .ticks(10).screenshot("fp_block_trail");

        // Seen from the side: the whole line into the wall, flame then smoke.
        s.step("clear", ctx -> clearRays())
                .server("ray into the wall", sc -> cast(sc.player(), RAY))
                .teleportPlayer(-13.5, 102, 5, -90, 8)
                .screenshot("side_block_peak")
                .ticks(10).screenshot("side_block_trail")
                .ticks(60).screenshot("side_block_dissipating")
                .teleportPlayer(.5, 101, -2, 0, 0)
                .fill(new BlockPos(-2, 101, 12), new BlockPos(2, 105, 12), Blocks.AIR.defaultBlockState()).ticks(2);

        // A cow at z = 8.
        s.step("clear", ctx -> clearRays())
                .server("ray at a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 8);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    cast(sc.player(), RAY);
                })
                .ticks(1).screenshot("fp_creature_peak")
                .check("a ray stopped by the cow ends on it", ctx -> last(Vfx.RayEnd.CREATURE))
                .ticks(10).screenshot("fp_creature_trail")
                .server("remove the cow", sc -> { if (target != null) { target.discard(); target = null; } });

        // Third person, the longest range.
        s.step("third person", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(2)
                .step("clear", ctx -> clearRays())
                .server("64-block ray", sc -> cast(sc.player(), "(spell :mana 10 (ray :range 64 (on-hit (damage target 1 fire))))"))
                .ticks(1).screenshot("tp_long_peak")
                .ticks(10).screenshot("tp_long_trail")
                .step("first person", ctx -> camera(CameraType.FIRST_PERSON))
                .step("clear", ctx -> clearRays())
                .server("64-block ray, seen from high on the side", sc -> cast(sc.player(), "(spell :mana 10 (ray :range 64 (on-hit (damage target 1 fire))))"))
                .teleportPlayer(-24, 118, 26, -90, 34)
                .ticks(1).screenshot("side_long_peak")
                .ticks(10).screenshot("side_long_trail")
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(2);

        // Night, and a quick volley fanned out.
        s.runCommand("time set midnight").ticks(2)
                .step("clear", ctx -> clearRays())
                .server("night ray", sc -> cast(sc.player(), RAY))
                .ticks(1).screenshot("night_peak")
                .ticks(10).screenshot("night_trail")
                .runCommand("time set noon")
                .step("clear", ctx -> clearRays())
                .server("volley 1", sc -> { sc.player().setYRot(-14); cast(sc.player(), RAY); }).ticks(3)
                .server("volley 2", sc -> { sc.player().setYRot(0); cast(sc.player(), RAY); }).ticks(3)
                .server("volley 3", sc -> { sc.player().setYRot(14); cast(sc.player(), RAY); })
                .ticks(1).screenshot("volley")
                .server("face ahead", sc -> sc.player().setYRot(0))
                .ticks(12).screenshot("volley_trail");

        // Stages in first person, each a fresh cast into the open.
        s.step("clear", ctx -> clearRays()).teleportPlayer(.5, 101, -2, 0, 0).ticks(2)
                .server("open ray", sc -> cast(sc.player(), RAY))
                .screenshot("fp_open_launch")
                .check("an open ray ends in the open", ctx -> last(Vfx.RayEnd.OPEN))
                .step("clear", ctx -> clearRays())
                .server("open ray", sc -> cast(sc.player(), RAY))
                .ticks(1).screenshot("fp_open_peak")
                .step("clear", ctx -> clearRays())
                .server("open ray", sc -> cast(sc.player(), RAY))
                .ticks(10).screenshot("fp_open_trail")
                .step("clear", ctx -> clearRays())
                .server("open ray", sc -> cast(sc.player(), RAY))
                .ticks(68).screenshot("fp_open_dissipating")
                .check("the smoke is still there", ctx -> LogosFx.liveRays() > 0)
                .ticks(72).screenshot("fp_open_gone")
                .check("every ray is gone once its smoke has drifted off", ctx -> LogosFx.liveRays() == 0);
        s.teardown("remove the cow, reset the camera", ctx -> {
            camera(CameraType.FIRST_PERSON);
            if (target != null) { target.discard(); target = null; }
        });
    }
}
