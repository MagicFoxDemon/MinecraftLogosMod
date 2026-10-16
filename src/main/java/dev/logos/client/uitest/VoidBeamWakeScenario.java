package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.LogosMod;
import dev.logos.client.LogosFx;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * A real void beam from the held wand, sampled on frozen ticks through its whole life: reaching out,
 * held, the wake it leaves narrowing away, and nothing left. First person (the wand tip must not cover the
 * view) and from the side, by day and by night.
 */
@LDLRegisterClient(name = "logos_void_beam_wake", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoidBeamWakeScenario implements UIScenario {
    private static final String SPELL = "(beam :radius 0.35 :range 24 :life 2 (on-hit (damage target 1 void)))";
    private int viewer = -1;
    private boolean previousHideGui;

    @Override public void configure(ScenarioOptions o) { o.tags("logos", "vfx", "void", "beam").guiScale(2); }

    private static void cast(ServerPlayer player) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(SPELL), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    /** One full beam life on frozen ticks: the beam lives 40 ticks, then its wake about 100. */
    private static void life(ScenarioBuilder s, String tag) {
        s.step("clear wakes before " + tag, ctx -> LogosFx.clearRays())
                .server("cast " + tag, sc -> cast(sc.player()))
                .runCommand("tick step 2").ticks(4).screenshot("void_beam_" + tag + "_1_reach")
                .runCommand("tick step 14").ticks(16).screenshot("void_beam_" + tag + "_2_held")
                .runCommand("tick step 28").ticks(30)
                .check(tag + ": the beam is gone and its wake is showing",
                        ctx -> LogosFx.drawnBeamLengths().isEmpty() && LogosFx.liveRays() > 0)
                .screenshot("void_beam_" + tag + "_3_wake_early")
                .runCommand("tick step 40").ticks(42).screenshot("void_beam_" + tag + "_4_wake_mid")
                .runCommand("tick step 35").ticks(37).screenshot("void_beam_" + tag + "_5_wake_late")
                .runCommand("tick step 40").ticks(42)
                .check(tag + ": nothing is left", ctx -> LogosFx.liveRays() == 0)
                .screenshot("void_beam_" + tag + "_6_gone");
    }

    private static void firstPerson(ScenarioBuilder s) {
        s.step("first person with the wand drawn", ctx -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setCameraEntity(mc.player);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = false;
        }).ticks(2);
    }

    private void side(ScenarioBuilder s) {
        s.step("watch from the side", ctx -> {
            Minecraft mc = Minecraft.getInstance();
            var e = mc.level.getEntity(viewer);
            if (e == null) throw new AssertionError("side viewer not tracked");
            // Facing +X, across the beam's line; the head angle is what the camera follows.
            e.setYRot(-90); e.yRotO = -90; e.setXRot(4); e.xRotO = 4;
            e.setYHeadRot(-90);
            if (e instanceof net.minecraft.world.entity.LivingEntity l) { l.yBodyRot = -90; l.yBodyRotO = -90; l.yHeadRotO = -90; }
            mc.setCameraEntity(e);
            mc.options.hideGui = true;
        }).ticks(2);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .step("remember GUI", ctx -> previousHideGui = Minecraft.getInstance().options.hideGui)
                .clearArea(new BlockPos(0, 106, 10), 24)
                .fill(new BlockPos(-20, 100, -10), new BlockPos(20, 100, 30), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -3, 0, 0)
                .server("hold a wand and place the side viewer", sc -> {
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
                    ArmorStand stand = EntityType.ARMOR_STAND.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (stand == null) throw new AssertionError("no armor stand");
                    stand.snapTo(-13.5, 100.6, 9, -90, 4);
                    stand.setYHeadRot(-90);
                    stand.setYBodyRot(-90);
                    stand.setInvisible(true);
                    stand.setNoGravity(true);
                    sc.level().addFreshEntity(stand);
                    viewer = stand.getId();
                }).ticks(10)
                .runCommand("tick freeze").ticks(2);
        firstPerson(s);
        s.step("warm up", ctx -> {}).server("warm-up cast", sc -> cast(sc.player()))
                .runCommand("tick step 60").ticks(62).step("clear warm-up", ctx -> LogosFx.clearRays());
        life(s, "fp_day");
        side(s);
        life(s, "side_day");
        s.runCommand("time set midnight");
        firstPerson(s);
        life(s, "fp_night");
        side(s);
        life(s, "side_night");
        s.runCommand("tick unfreeze").runCommand("time set noon");
        firstPerson(s);
        s.teardown("restore camera, GUI and remove the viewer", ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.setCameraEntity(mc.player);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = previousHideGui;
            var server = mc.getSingleplayerServer();
            if (server != null) server.execute(() -> {
                server.tickRateManager().setFrozen(false);
                var e = server.overworld().getEntity(viewer);
                if (e != null) e.discard();
            });
        });
    }
}
