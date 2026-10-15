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
import net.minecraft.world.phys.Vec3;

/**
 * Frost rays from real casts: into a wall through every stage of the burst and the frost it leaves, into
 * the open, on a cow, as a drain link; first person, third person, the full length from the side, at
 * night and several in a row; and every ray is gone again once its frost has cleared.
 */
@LDLRegisterClient(name = "logos_frost_ray", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FrostRayScenario implements UIScenario {
    private static final String RAY = "(ray :range 30 (on-hit (damage target 1 frost)))";
    private static final Vec3 SIDE_FROM = new Vec3(.5, 102.4, -1), SIDE_TO = new Vec3(.5, 102.4, 13.95);
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "ray", "frost").guiScale(2); }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static boolean last(Vfx.RayEnd end) {
        LogosFx.FiredRay ray = LogosFx.lastRay();
        return ray != null && ray.element() == Element.FROST && ray.end() == end;
    }

    /** Drops the rays still lingering, so each stage sample shows only the ray cast for it. */
    private static void clearRays() {
        try {
            var field = LogosFx.class.getDeclaredField("RAYS");
            field.setAccessible(true);
            ((List<?>) field.get(null)).clear();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    /** Clears what is left, casts a fresh frost ray from the player and waits for it to arrive. */
    private static void fresh(ScenarioBuilder s, String name) {
        s.step("clear before " + name, ctx -> clearRays())
                .server("cast " + name, sc -> cast(sc.player(), RAY))
                .waitUntil(name + " arrives", ctx -> LogosFx.liveRays() > 0);
    }

    /** The same ray sent straight through the public entry, from a fixed point, for views away from the caster. */
    private static void sideRay(ScenarioBuilder s, String name) {
        s.step("clear before " + name, ctx -> clearRays())
                .server("send " + name, sc -> Vfx.ray(sc.level(), SIDE_FROM, SIDE_TO, Element.FROST, Vfx.NONE, Vfx.RayEnd.BLOCK))
                .waitUntil(name + " arrives", ctx -> LogosFx.liveRays() > 0);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 10), 16)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 30), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(new BlockPos(-3, 101, 14), new BlockPos(3, 105, 14), Blocks.PACKED_ICE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0)
                .server("hand over a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .ticks(8);

        // Stages of one ray into the wall, first person; every sample is a fresh cast.
        fresh(s, "launch");
        s.screenshot("fp_1_launch")
                .check("the frost ray is drawn by its element", ctx -> LogosFx.liveRays() > 0 && last(Vfx.RayEnd.BLOCK));
        fresh(s, "peak");
        s.ticks(3).screenshot("fp_2_peak");
        fresh(s, "trail");
        s.ticks(14).screenshot("fp_3_trail");
        fresh(s, "dissipating");
        s.ticks(68).screenshot("fp_4_dissipating")
                .ticks(66).screenshot("fp_5_gone")
                .check("the ray is dropped once its frost has cleared", ctx -> LogosFx.liveRays() == 0);

        // A cow in the way, then a drain link to it.
        s.server("a cow in the way", sc -> {
            target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
            if (target == null) throw new IllegalStateException("cow creation failed");
            target.setPos(.5, 101, 6);
            target.setNoAi(true);
            sc.level().addFreshEntity(target);
        }).ticks(3);
        fresh(s, "cow");
        s.ticks(3).screenshot("fp_cow_peak")
                .check("a ray stopped by the cow ends on it", ctx -> last(Vfx.RayEnd.CREATURE))
                .ticks(12).screenshot("fp_cow_trail")
                .step("clear before drain", ctx -> clearRays())
                .server("drain the cow", sc -> cast(sc.player(), "(drain (nearest :radius 12) 1)"))
                .ticks(3).check("drain draws a link", ctx -> LogosFx.lastRay() != null && LogosFx.lastRay().end() == Vfx.RayEnd.LINK)
                .screenshot("fp_link")
                .server("remove the cow", sc -> { if (target != null) { target.discard(); target = null; } })
                .fill(new BlockPos(-3, 101, 14), new BlockPos(3, 105, 14), Blocks.AIR.defaultBlockState()).ticks(2);

        // Into the open: no hit, the end thins out.
        fresh(s, "open");
        s.ticks(3).screenshot("fp_open_peak")
                .check("an open ray ends in the open", ctx -> last(Vfx.RayEnd.OPEN))
                .ticks(14).screenshot("fp_open_trail")
                .fill(new BlockPos(-3, 101, 14), new BlockPos(3, 105, 14), Blocks.PACKED_ICE.defaultBlockState()).ticks(2);

        // Third person, from behind the caster.
        s.step("third person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(2);
        fresh(s, "third person peak");
        s.ticks(3).screenshot("tp_peak");
        fresh(s, "third person trail");
        s.ticks(16).screenshot("tp_trail")
                .step("first person again", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));

        // The full length from the side.
        s.teleportPlayer(-6.5, 101, 6.5, -90, 3).ticks(3);
        sideRay(s, "side peak");
        s.ticks(3).screenshot("side_1_peak");
        sideRay(s, "side trail");
        s.ticks(16).screenshot("side_2_trail");
        sideRay(s, "side fading");
        s.ticks(72).screenshot("side_3_dissipating");

        // Night.
        s.runCommand("time set midnight").ticks(2);
        sideRay(s, "night peak");
        s.ticks(3).screenshot("night_1_peak");
        sideRay(s, "night trail");
        s.ticks(16).screenshot("night_2_trail")
                .runCommand("time set noon");

        // Several in a row from the caster.
        s.step("clear before the volley", ctx -> clearRays()).teleportPlayer(.5, 101, -2, -14, 0);
        for (int i = 0; i < 4; i++) {
            s.server("volley " + i, sc -> cast(sc.player(), RAY)).ticks(3);
            if (i < 3) s.teleportPlayer(.5, 101, -2, -14 + 9 * (i + 1), -2 * i);
        }
        s.ticks(2).screenshot("fp_volley")
                .check("several frost rays live at once", ctx -> LogosFx.liveRays() >= 3)
                .ticks(140).screenshot("fp_volley_gone")
                .check("every frost ray is gone again", ctx -> LogosFx.liveRays() == 0);
        s.teardown("remove the cow, first person", ctx -> {
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            if (target != null) { target.discard(); target = null; }
        });
    }
}
