package dev.logos.client.uitest;

import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.LogosMod;
import dev.logos.client.CastPoints;
import dev.logos.client.LogosFx;
import dev.logos.client.WandTips;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** ShockBlueRay strip/caps, persistent ShockBlueRay splashes, released wand mounts and ordinary beam packet expiry. */
@LDLRegisterClient(name = "logos_lightning_beam", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LightningBeamScenario implements UIScenario {
    @Override public void configure(ScenarioOptions o) { o.tags("logos", "vfx", "lightning", "beam").guiScale(2); }

    private static void show(float radius) {
        LogosFx.handle(new FxPayload((byte)FxPayload.Kind.BEAM_HOLD.ordinal(), (byte)Element.LIGHTNING.ordinal(),
                (byte)0, -3, 102.4f, 4, 4, 102.4f, 4, radius, 27600, -1, 0, -1));
    }

    private static void stop() {
        LogosFx.handle(new FxPayload((byte)FxPayload.Kind.BEAM_END.ordinal(), (byte)Element.LIGHTNING.ordinal(),
                (byte)0, 4, 102.4f, 4, 0, 0, 0, .5f, 27600, -1, 0, -1));
    }

    private static Map<?, ?> beams() {
        try {
            var f = LogosFx.class.getDeclaredField("BEAMS"); f.setAccessible(true);
            return (Map<?, ?>)f.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static boolean mounted() {
        var player = Minecraft.getInstance().player;
        Vec3 tip = WandTips.sample(player.getId());
        if (tip == null || CastPoints.of(player, 0).distanceTo(tip) > 1e-5) return false;
        try {
            for (var b : beams().values()) {
                var anchor = b.getClass().getDeclaredField("anchor"); anchor.setAccessible(true);
                if (anchor.getInt(b) != player.getId()) continue;
                var fire = b.getClass().getDeclaredField("lightning"); fire.setAccessible(true);
                Object visual = fire.get(b);
                if (visual == null) continue;
                var from = visual.getClass().getDeclaredField("lastFrom"); from.setAccessible(true);
                return ((Vec3)from.get(visual)).distanceTo(tip) < .3;
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return false;
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 105, 3), 16)
                .fill(new BlockPos(-14, 100, -12), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -3, 0, 0).ticks(5)
                .step("warm up material pipelines", ctx -> show(.5f)).ticks(6)
                .step("ShockBlueRay side view", ctx -> show(.5f)).ticks(6)
                .step("sustain day volume", ctx -> show(.5f)).ticks(6).screenshot("lightning_beam_day")
                .step("end the fixed beam", ctx -> stop()).ticks(24)
                .runCommand("time set midnight")
                .step("night flow", ctx -> show(.5f)).ticks(6)
                .step("sustain night volume", ctx -> show(.5f)).ticks(6).screenshot("lightning_beam_night")
                .step("renew before timeout", ctx -> show(.5f)).ticks(6).screenshot("lightning_beam_splash_end")
                .step("stop emission", ctx -> stop()).ticks(24)
                .check("beam end removes the persistent beam", ctx -> !beams().containsKey(27600))
                .step("larger beam", ctx -> show(.85f)).ticks(6).screenshot("lightning_beam_large")
                .step("stop large beam", ctx -> stop()).ticks(24)
                .fill(new BlockPos(-1, 101, 3), new BlockPos(1, 104, 3), Blocks.STONE.defaultBlockState())
                .step("beam behind opaque pillar", ctx -> show(.5f)).ticks(6).screenshot("lightning_beam_occluded")
                .step("stop occlusion beam", ctx -> stop()).ticks(24)
                .fill(new BlockPos(-1, 101, 3), new BlockPos(1, 104, 3), Blocks.AIR.defaultBlockState())
                .fill(new BlockPos(-8, 101, 8), new BlockPos(8, 105, 8), Blocks.STONE.defaultBlockState())
                .runCommand("time set noon")
                .step("draw the held wand", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("released wand fires a real beam into a wall", sc -> {
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()));
                    var magic = PlayerMagic.of(sc.player()); magic.maxMana = magic.mana = 10000;
                    if (Casting.cast(sc.player(), Casting.compile("(beam :radius 0.35 :range 18 :life 2 (on-hit (damage target 1 lightning)))"), null, null) == null)
                        throw new AssertionError("beam spell refused");
                }).ticks(10)
                .check("first-person beam uses the released wand tip", ctx -> mounted())
                .screenshot("lightning_beam_first_person")
                .step("third-person mount", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .ticks(5).check("third-person beam uses the held wand tip", ctx -> mounted())
                .screenshot("lightning_beam_third_person")
                .step("return to first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                .ticks(48)
                .check("ended real beams are removed", ctx -> beams().isEmpty())
                .screenshot("lightning_beam_cleared");
        s.teardown("restore camera", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }
}
