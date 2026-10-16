package dev.logos.client.uitest;

import java.util.List;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.LogosMod;
import dev.logos.client.LogosFx;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * A real lightning beam from a held wand and the wake it leaves: the beam holding, the moment it ends,
 * the wake re-striking, breaking up and thinning out, and gone; in first person against a wall, and seen
 * from the side of an open lane through an invisible armour stand's eyes (the beam follows the caster's
 * gaze, so the caster stays put). Game time is frozen and stepped one tick at a time around the end, and
 * the wake's age is read from its own birth tick, so each capture names its exact age. Every sequence is
 * a fresh cast after {@link LogosFx#clearRays()}.
 */
@LDLRegisterClient(name = "logos_lightning_beam_wake", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LightningBeamWakeScenario implements UIScenario {
    private static final String SPELL = "(beam :radius 0.35 :range 24 :life 2 (on-hit (damage target 1 lightning)))";
    /** Lane floors (the player stands one above): a wall 14 blocks out, and an open lane. */
    private static final int WALL = 101, OPEN = 141;
    private static long castAt, wakeAt;
    private static int eyeId = -1;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "lightning", "beam").guiScale(2); }

    private static void cast(ServerPlayer player) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(SPELL), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static long now() { return Minecraft.getInstance().level.getGameTime(); }

    private static void step(long ticks) {
        Minecraft.getInstance().player.connection.sendCommand("tick step " + Math.max(1, ticks));
    }

    /** The birth tick of the newest live ray or wake, or -1. */
    private static long newestBorn() {
        try {
            var f = LogosFx.class.getDeclaredField("RAYS");
            f.setAccessible(true);
            List<?> rays = (List<?>)f.get(null);
            if (rays.isEmpty()) return -1;
            Object last = rays.getLast();
            var born = last.getClass().getDeclaredMethod("born");
            born.setAccessible(true);
            return (long)born.invoke(last);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void lane(ScenarioBuilder s, int y) {
        s.clearArea(new BlockPos(0, y + 3, 12), 16)
                .fill(new BlockPos(-3, y - 1, -3), new BlockPos(3, y - 1, 28), Blocks.SMOOTH_STONE.defaultBlockState())
                .fill(new BlockPos(-15, y - 1, 2), new BlockPos(-8, y - 1, 20), Blocks.SMOOTH_STONE.defaultBlockState());
        if (y == WALL) s.fill(new BlockPos(-3, y, 14), new BlockPos(3, y + 4, 14), Blocks.STONE.defaultBlockState());
    }

    /** Fresh cast down a lane; captures the beam holding, then steps a tick at a time until it ends. */
    private static void castAndEnd(ScenarioBuilder s, int lane, String prefix) {
        s.step("clear wakes", ctx -> LogosFx.clearRays())
                .teleportPlayer(.5, lane, .5, 0, 0).ticks(3)
                .server("cast the beam", sc -> cast(sc.player()))
                .ticks(2).step("note the cast", ctx -> { castAt = now(); wakeAt = -1; })
                .step("let the beam hold", ctx -> step(20))
                .waitUntil("beam is 20 ticks old", ctx -> now() - castAt >= 20)
                .waitUntil("the beam is showing", ctx -> !LogosFx.drawnBeamLengths().isEmpty()).ticks(2)
                .screenshot(prefix + "_1_beam_holding");
        for (int i = 0; i < 40; i++)
            s.step("one tick toward the end", ctx -> { if (LogosFx.liveRays() == 0) step(1); }).ticks(2);
        s.step("note the wake", ctx -> wakeAt = newestBorn())
                .check("the beam ended and left a wake", ctx -> wakeAt >= 0 && LogosFx.drawnBeamLengths().isEmpty());
    }

    /** Steps until the wake is {@code age} ticks old, then captures. */
    private static ScenarioBuilder shoot(ScenarioBuilder s, int age, String shot) {
        return s.step("step to " + shot, ctx -> { if (now() - wakeAt < age) step(age - (now() - wakeAt)); })
                .waitUntil(shot + ": wake is " + age + " ticks old", ctx -> now() - wakeAt >= age)
                .ticks(2)
                .step("age of " + shot, ctx -> System.out.println("[lightning beam wake] " + shot + " at age "
                        + (now() - wakeAt) + ", live " + LogosFx.liveRays()))
                .screenshot(shot);
    }

    private static void stages(ScenarioBuilder s, String prefix, int restrike) {
        shoot(s, 0, prefix + "_2_just_ended");
        shoot(s, restrike, prefix + "_3_restrike");
        shoot(s, 40, prefix + "_4_dashes");
        shoot(s, 90, prefix + "_5_sparse");
        shoot(s, 130, prefix + "_6_gone").check(prefix + ": the wake is gone", ctx -> LogosFx.liveRays() == 0);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.settleMs(0).setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear");
        lane(s, WALL);
        lane(s, OPEN);
        s.setHeldItem(new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()))
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .teleportPlayer(.5, WALL, .5, 0, 0).ticks(20)
                .runCommand("tick freeze").ticks(3);

        // First person against the wall.
        castAndEnd(s, WALL, "01_fp");
        stages(s, "01_fp", 5);

        // From the side of the open lane, through an armour stand's eyes.
        s.server("place the side camera", sc -> {
            ArmorStand stand = EntityType.ARMOR_STAND.create(sc.level(), EntitySpawnReason.COMMAND);
            if (stand == null) throw new IllegalStateException("armour stand creation failed");
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.snapTo(-13.5, OPEN - .2, 12.5, -90, 4);
            stand.setYHeadRot(-90);
            sc.level().addFreshEntity(stand);
            eyeId = stand.getId();
        }).ticks(5).step("look through it", ctx -> {
            Entity eye = Minecraft.getInstance().level.getEntity(eyeId);
            if (eye == null) throw new IllegalStateException("side camera not tracked");
            Minecraft.getInstance().setCameraEntity(eye);
            Minecraft.getInstance().options.hideGui = true;
        });
        castAndEnd(s, OPEN, "02_side");
        stages(s, "02_side", 14);

        s.step("back to the player", ctx -> {
                    Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().player);
                    Minecraft.getInstance().options.hideGui = false;
                })
                .runCommand("tick unfreeze");
        s.teardown("restore", ctx -> {
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().player);
        });
        s.teardownServer("unfreeze", sc -> {
            sc.level().getServer().tickRateManager().setFrozen(false);
            Entity eye = eyeId >= 0 ? sc.level().getEntity(eyeId) : null;
            if (eye != null) eye.discard();
        });
    }
}
