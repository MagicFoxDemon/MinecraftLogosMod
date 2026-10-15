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
 * The arcane ray from real casts of a spell inferred arcane (a ray that only makes its target glow): into
 * the open, into a wall and onto a cow; its stages from fresh casts, first and third person, the full
 * length from the side, at night and several in a row; and that it leaves nothing behind.
 */
@LDLRegisterClient(name = "logos_arcane_ray", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ArcaneRayScenario implements UIScenario {
    private static final String OPEN = "(ray :range 14 (on-hit (glow target 3)))";
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "ray", "arcane").guiScale(2); }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static boolean last(Vfx.RayEnd end) {
        LogosFx.FiredRay ray = LogosFx.lastRay();
        return ray != null && ray.element() == Element.ARCANE && ray.end() == end;
    }

    /**
     * Drops every live ray so the next stage sample starts from a fresh cast with nothing left over
     * (test only: reaches LogosFx's private list and ends each visual the way LogosFx itself would).
     */
    private static void clearRays() {
        try {
            var field = LogosFx.class.getDeclaredField("RAYS");
            field.setAccessible(true);
            List<?> rays = (List<?>) field.get(null);
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

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(-4, 104, 10), 22)
                .fill(new BlockPos(-24, 100, -8), new BlockPos(14, 100, 30), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10)
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .ticks(20);

        // Stages, each from a fresh cast into the open, first person.
        s.server("open: launch", sc -> cast(sc.player(), OPEN)).ticks(1).screenshot("arcane_ray_1_launch")
                .check("the spell is inferred arcane and its ray ends in the open", ctx -> last(Vfx.RayEnd.OPEN))
                .check("an arcane ray is drawn by its own visual", ctx -> LogosFx.liveRays() > 0)
                .step("clear", ctx -> clearRays())
                .server("open: peak", sc -> cast(sc.player(), OPEN)).ticks(3).screenshot("arcane_ray_2_peak")
                .step("clear", ctx -> clearRays())
                .server("open: residue", sc -> cast(sc.player(), OPEN)).ticks(14).screenshot("arcane_ray_3_residue")
                .step("clear", ctx -> clearRays())
                .server("open: fading", sc -> cast(sc.player(), OPEN)).ticks(70).screenshot("arcane_ray_4_fading")
                .step("clear", ctx -> clearRays());

        // Into a wall and onto a creature.
        s.fill(new BlockPos(-2, 101, 9), new BlockPos(2, 105, 9), Blocks.STONE.defaultBlockState())
                .server("wall", sc -> cast(sc.player(), OPEN)).ticks(2).screenshot("arcane_ray_block_hit")
                .check("a wall stops the arcane ray", ctx -> last(Vfx.RayEnd.BLOCK))
                .ticks(14).screenshot("arcane_ray_block_residue")
                .step("clear", ctx -> clearRays())
                .fill(new BlockPos(-2, 101, 9), new BlockPos(2, 105, 9), Blocks.AIR.defaultBlockState())
                .server("cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 6);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                }).ticks(3)
                .server("ray at the cow", sc -> cast(sc.player(), OPEN)).ticks(2).screenshot("arcane_ray_creature_hit")
                .check("the cow stops the arcane ray", ctx -> last(Vfx.RayEnd.CREATURE))
                .ticks(14).screenshot("arcane_ray_creature_residue")
                .step("clear", ctx -> clearRays())
                .server("remove the cow", sc -> { if (target != null) { target.discard(); target = null; } });

        // Third person from behind, then the whole line from the side.
        // Cast from behind, then turn the view: along the look axis the line hides behind the caster's head.
        s.step("third person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK)).ticks(3)
                .server("third person cast", sc -> cast(sc.player(), OPEN)).ticks(1)
                .teleportPlayer(.5, 101, -2, 38, 8).ticks(2).screenshot("arcane_ray_third_person")
                .teleportPlayer(.5, 101, -2, 0, 0)
                .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .step("clear", ctx -> clearRays())
                .server("long ray", sc -> cast(sc.player(), "(ray :range 26 (on-hit (glow target 3)))")).ticks(1)
                .teleportPlayer(-17, 101, 10, -90, 0).ticks(2).screenshot("arcane_ray_side_burst")
                .ticks(12).screenshot("arcane_ray_side_residue")
                .ticks(30).screenshot("arcane_ray_side_fading")
                .step("clear", ctx -> clearRays())
                .teleportPlayer(.5, 101, -2, 0, -2).ticks(3)
                .server("longest ray", sc -> cast(sc.player(), "(ray :range 64 (on-hit (glow target 3)))")).ticks(3)
                .screenshot("arcane_ray_far")
                .step("clear", ctx -> clearRays());

        // Night, and several in a row.
        s.runCommand("time set midnight").teleportPlayer(.5, 101, -2, 0, 0).ticks(3)
                .server("night", sc -> cast(sc.player(), OPEN)).ticks(3).screenshot("arcane_ray_night_peak")
                .ticks(14).screenshot("arcane_ray_night_residue")
                .step("clear", ctx -> clearRays())
                .runCommand("time set noon").teleportPlayer(.5, 101, -2, -14, 0).ticks(2)
                .server("volley 1", sc -> cast(sc.player(), OPEN)).teleportPlayer(.5, 101, -2, 0, -4).ticks(2)
                .server("volley 2", sc -> cast(sc.player(), OPEN)).teleportPlayer(.5, 101, -2, 14, 0).ticks(2)
                .server("volley 3", sc -> cast(sc.player(), OPEN)).teleportPlayer(.5, 101, -2, 0, 0).ticks(2)
                .screenshot("arcane_ray_volley")
                .check("each ray of the volley is live", ctx -> LogosFx.liveRays() >= 3)
                .ticks(10).screenshot("arcane_ray_volley_residue")
                // 142 ticks after the last cast: past the 136-tick residue, inside the 200-tick cap.
                .ticks(130)
                .check("everything the rays left is gone", ctx -> LogosFx.liveRays() == 0)
                .screenshot("arcane_ray_5_gone");
        s.teardown("restore", ctx -> {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            if (target != null) { target.discard(); target = null; }
        });
    }
}
