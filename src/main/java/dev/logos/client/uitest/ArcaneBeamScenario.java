package dev.logos.client.uitest;

import java.util.Map;
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
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The arcane beam from real casts of a spell inferred arcane (a beam that only makes what it touches glow):
 * reaching out, held, and the wake it leaves until it is gone, in first person, over the shoulder and from the
 * side (through invisible armour stands' eyes), by day and by night, into the open and into a wall.
 * Ticks are frozen and stepped by hand so each sample is taken at a known age.
 */
@LDLRegisterClient(name = "logos_arcane_beam", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ArcaneBeamScenario implements UIScenario {
    /** Lives 2 s (40 ticks): it ends, and leaves its wake, at about tick 41. */
    private static final String BEAM = "(beam :radius 0.4 :range 24 :life 2 (on-hit (glow target 3)))";
    /** Invisible armour stands to look through: from the side, and over the caster's shoulder. */
    private static int side = -1, shoulder = -1;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "beam", "arcane").guiScale(2); }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static Map<?, ?> beams() {
        try {
            var f = LogosFx.class.getDeclaredField("BEAMS");
            f.setAccessible(true);
            return (Map<?, ?>) f.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    /** Every live beam is arcane and drawn by ArcaneBeamVisual (not the generic tube). */
    private static boolean arcaneDrawn() {
        if (beams().isEmpty()) return false;
        try {
            for (Object b : beams().values()) {
                var element = b.getClass().getDeclaredField("element"); element.setAccessible(true);
                var arcane = b.getClass().getDeclaredField("arcane"); arcane.setAccessible(true);
                if (element.get(b) != Element.ARCANE || arcane.get(b) == null) return false;
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return true;
    }

    /** Lets {@code n} frozen ticks run, then waits for the client to see them. */
    private static ScenarioBuilder run(ScenarioBuilder s, int n) {
        return s.runCommand("tick step " + n).ticks(n + 3);
    }

    private static int stand(net.minecraft.server.level.ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        ArmorStand eye = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        if (eye == null) throw new IllegalStateException("armour stand creation failed");
        eye.setInvisible(true);
        eye.setNoGravity(true);
        eye.snapTo(x, y, z, yaw, pitch);
        level.addFreshEntity(eye);
        return eye.getId();
    }

    /** Looks through {@code eye} (or the player when -1); the HUD and hand are hidden while borrowing a stand's eyes. */
    private static void look(int eye) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = eye >= 0 ? mc.level.getEntity(eye) : null;
        if (e != null) {
            // The stand comes over with its body turned, not its view: point its eyes the way they were placed.
            float yaw = eye == side ? -90 : 12, pitch = eye == side ? 4 : 6;
            e.setYRot(yaw); e.yRotO = yaw; e.setXRot(pitch); e.xRotO = pitch; e.setYHeadRot(yaw);
        }
        mc.setCameraEntity(e != null ? e : mc.player);
        mc.options.hideGui = e != null;
    }

    /** One fresh cast sampled through its life: reaching, held, the wake early, midway and late, then gone. */
    private static void stages(ScenarioBuilder s, String prefix, boolean full) {
        s.step("clear", ctx -> LogosFx.clearRays())
                .server(prefix + " cast", sc -> cast(sc.player(), BEAM));
        run(s, 2).screenshot("arcane_beam_" + prefix + "_1_reach")
                .check("the beam is inferred arcane and drawn by its own visual", ctx -> arcaneDrawn());
        run(s, 18).screenshot("arcane_beam_" + prefix + "_2_hold");
        run(s, 24).check("the ended beam is gone", ctx -> beams().isEmpty())
                .check("it leaves a wake", ctx -> LogosFx.liveRays() > 0)
                .screenshot("arcane_beam_" + prefix + "_3_wake_early");
        if (!full) return;
        run(s, 40).screenshot("arcane_beam_" + prefix + "_4_wake_mid");
        run(s, 50).screenshot("arcane_beam_" + prefix + "_5_wake_late");
        run(s, 50).check("the wake is gone within the cap", ctx -> LogosFx.liveRays() == 0)
                .screenshot("arcane_beam_" + prefix + "_6_gone");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(-4, 104, 10), 22)
                .fill(new BlockPos(-24, 100, -8), new BlockPos(14, 100, 30), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10)
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("hold a wand, and set an eye at the side", sc -> {
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
                    side = stand(sc.level(), -11, 100.6, 9, -90, 4);
                    shoulder = stand(sc.level(), 2.2, 100.9, -5.6, 12, 6);
                }).ticks(20)
                .runCommand("tick freeze").ticks(3);

        // Day: first person through its whole life, then from the side, then over the shoulder (third person).
        stages(s, "day_fp", true);
        s.step("side view", ctx -> look(side));
        stages(s, "day_side", true);
        s.step("over the shoulder", ctx -> look(shoulder));
        stages(s, "day_tp", false);
        s.step("back to the player", ctx -> look(-1));

        // Into a wall: the end halo sits on the face.
        s.fill(new BlockPos(-3, 101, 11), new BlockPos(3, 105, 11), Blocks.STONE.defaultBlockState());
        stages(s, "wall_fp", false);
        s.step("side view", ctx -> look(side));
        stages(s, "wall_side", false);
        s.step("back to the player", ctx -> look(-1))
                .fill(new BlockPos(-3, 101, 11), new BlockPos(3, 105, 11), Blocks.AIR.defaultBlockState());

        // Night.
        s.runCommand("time set midnight").ticks(3);
        stages(s, "night_fp", false);
        s.step("side view", ctx -> look(side));
        stages(s, "night_side", true);
        s.step("back to the player", ctx -> look(-1)).runCommand("tick unfreeze").runCommand("time set noon").ticks(3);

        s.teardown("restore", ctx -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (mc.player != null) mc.setCameraEntity(mc.player);
            mc.options.hideGui = false;
            side = shoulder = -1;
        });
    }
}
