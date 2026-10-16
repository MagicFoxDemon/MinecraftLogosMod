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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real healing beams (inferred as life) from a held wand, sampled by ticks from each fresh cast: the end
 * reaching out, the beam held, its wake fading and gone; first person, from the side (through a watcher's
 * eyes), third person, into a wall and onto a cow, and at night. Each stage clears what earlier beams left.
 */
@LDLRegisterClient(name = "logos_life_beam", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeBeamScenario implements UIScenario {
    private static final String HOLD = "(beam :radius 0.45 :range 24 :life 3 (on-hit (heal target 1)))";
    private static final String REACH = "(beam :radius 0.45 :range 60 :life 1 (on-hit (heal target 1)))";
    private static final String SHORT = "(beam :radius 0.45 :range 24 :life 1 (on-hit (heal target 1)))";
    private static Cow target;
    private static long lastWakeAge = -1;
    private static ArmorStand watcher, shoulder;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "beam", "life").guiScale(2); }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 100000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static double longest() {
        return LogosFx.drawnBeamLengths().stream().mapToDouble(Double::doubleValue).max().orElse(-1);
    }

    /** How long the life beam's wake has been showing, in game ticks, or -1 when none is. */
    @SuppressWarnings("unchecked")
    private static long wakeAge() {
        try {
            var f = LogosFx.class.getDeclaredField("RAYS");
            f.setAccessible(true);
            for (Object live : (java.util.List<Object>) f.get(null)) {
                var visual = live.getClass().getDeclaredMethod("visual");
                visual.setAccessible(true);
                if (!visual.invoke(live).getClass().getSimpleName().equals("LifeBeamWake")) continue;
                var born = live.getClass().getDeclaredMethod("born");
                born.setAccessible(true);
                long age = Minecraft.getInstance().level.getGameTime() - (long) born.invoke(live);
                lastWakeAge = age;
                return age;
            }
            return -1;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Looks through {@code stand}'s eyes, turned to {@code yaw}/{@code pitch}; null looks through the caster's again. */
    private static void view(ArmorStand stand, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = stand != null ? mc.level.getEntity(stand.getId()) : mc.player;
        if (e != null && e != mc.player) {
            // An armor stand's head does not follow its yaw on its own.
            e.setYRot(yaw); e.setYHeadRot(yaw); e.setXRot(pitch);
            e.yRotO = yaw; e.xRotO = pitch;
        }
        mc.setCameraEntity(e != null ? e : mc.player);
    }

    /** From the side, square to the line. */
    private static void view(boolean side) { view(side ? watcher : null, -90, 6); }

    private static ArmorStand stand(net.minecraft.server.level.ServerLevel level, double x, double y, double z) {
        ArmorStand a = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        if (a == null) throw new IllegalStateException("armor stand creation failed");
        a.snapTo(x, y, z, 0, 0);
        a.setInvisible(true);
        a.setNoGravity(true);
        level.addFreshEntity(a);
        return a;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .runCommand("gamerule doDaylightCycle false")
                .fill(new BlockPos(-14, 101, -8), new BlockPos(14, 112, 34), Blocks.AIR.defaultBlockState())
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 34), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0)
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("hold a wand", sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND,
                        new ItemStack(LogosMod.WOOD_WANDS.getFirst().get())))
                .server("watchers beside the line and over the caster's shoulder", sc -> {
                    watcher = stand(sc.level(), -13.5, 101.5, 10);
                    shoulder = stand(sc.level(), -2.5, 101.2, -6.5);
                })
                .ticks(10);

        // Reaching out and held, in first person; each stage is waited for by what is drawn (screenshots take a
        // few ticks to read back, so a sample lands a little after its mark).
        s.step("clear", ctx -> LogosFx.clearRays())
                .server("cast a long reaching beam", sc -> cast(sc.player(), REACH))
                .waitUntil("the end is on its way", ctx -> longest() >= 8)
                .check("the heal beam is drawn and still reaching out", ctx -> longest() < 40)
                .screenshot("life_beam_1_reach")
                .ticks(60).step("clear", ctx -> LogosFx.clearRays())
                .server("cast a held beam", sc -> cast(sc.player(), HOLD))
                .ticks(20).screenshot("life_beam_2_hold_first_person")
                .check("the beam has reached its full length", ctx -> longest() > 20);
        // The same beam from the side and in third person.
        s.step("watch from the side", ctx -> view(true)).ticks(2).screenshot("life_beam_3_hold_side")
                .step("back to the caster", ctx -> view(false))
                // Over the caster's shoulder (straight behind, a beam that follows the gaze hides behind their head).
                .step("third person", ctx -> view(shoulder, -9, 4)).ticks(2).screenshot("life_beam_4_hold_third_person")
                .step("back to the caster", ctx -> view(false));
        // It ends: its wake from the side, sampled by the wake's own age, until it is gone.
        s.step("watch from the side", ctx -> view(true))
                .waitUntil("the beam ends and leaves a wake", ctx -> wakeAge() >= 0)
                .check("the beam has gone", ctx -> LogosFx.drawnBeamLengths().isEmpty())
                .screenshot("life_beam_5_wake_0s_side")
                .waitUntil("half a second", ctx -> wakeAge() >= 10).screenshot("life_beam_6_wake_0s5_side")
                .waitUntil("two seconds", ctx -> wakeAge() >= 40).screenshot("life_beam_7_wake_2s_side")
                .waitUntil("four seconds", ctx -> wakeAge() >= 80).screenshot("life_beam_8_wake_4s_side")
                .waitUntil("six seconds", ctx -> wakeAge() >= 120).screenshot("life_beam_9_wake_6s_side")
                .waitUntil("the wake is dropped", ctx -> wakeAge() < 0)
                .check("the wake lasted under seven and a half seconds", ctx -> lastWakeAge < 150)
                .screenshot("life_beam_10_gone_side")
                .step("back to the caster", ctx -> view(false));
        // A short beam's wake in first person.
        s.step("clear", ctx -> LogosFx.clearRays())
                .server("cast a short beam", sc -> cast(sc.player(), SHORT))
                .ticks(30).screenshot("life_beam_11_wake_first_person");

        // Into a wall, and onto a cow: the bloom where it ends.
        s.fill(new BlockPos(-3, 101, 12), new BlockPos(4, 106, 12), Blocks.STONE.defaultBlockState()).ticks(40)
                .step("clear", ctx -> LogosFx.clearRays())
                .server("beam into a wall", sc -> cast(sc.player(), HOLD))
                .ticks(20).screenshot("life_beam_12_wall")
                .step("watch from the side", ctx -> view(true)).ticks(2).screenshot("life_beam_13_wall_side")
                .step("back to the caster", ctx -> view(false))
                .ticks(45)
                .fill(new BlockPos(-3, 101, 12), new BlockPos(4, 106, 12), Blocks.AIR.defaultBlockState());
        s.step("clear", ctx -> LogosFx.clearRays())
                .server("a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 7);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                }).ticks(3)
                .server("beam onto the cow", sc -> cast(sc.player(), HOLD))
                .ticks(20).screenshot("life_beam_14_cow")
                .ticks(45)
                .server("remove the cow", sc -> { target.discard(); target = null; });

        // Night: held, from the side, and its wake.
        s.runCommand("time set midnight").ticks(3).step("clear", ctx -> LogosFx.clearRays())
                .server("night beam", sc -> cast(sc.player(), HOLD))
                .ticks(20).screenshot("life_beam_15_night_first_person")
                .step("watch from the side", ctx -> view(true)).ticks(2).screenshot("life_beam_16_night_side")
                .ticks(60).screenshot("life_beam_17_night_wake_side")
                .step("back to the caster", ctx -> view(false))
                .runCommand("time set noon").ticks(80).ticks(80)
                .check("everything has faded", ctx -> LogosFx.liveRays() == 0 && LogosFx.drawnBeamLengths().isEmpty());
        s.teardown("clean up", ctx -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (mc.player != null) mc.setCameraEntity(mc.player);
            var server = mc.getSingleplayerServer();
            if (server != null) server.execute(() -> {
                if (target != null) { target.discard(); target = null; }
                if (watcher != null) { watcher.discard(); watcher = null; }
                if (shoulder != null) { shoulder.discard(); shoulder = null; }
            });
        });
    }
}
