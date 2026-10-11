package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.LogosFx;
import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** A short, isolated client preview of fire projectiles at two sizes, on a curve and at night. */
@LDLRegisterClient(name = "logos_fire_projectile", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public class FireProjectileScenario implements UIScenario {
    private record Shot(int id, float size, boolean curve, int count) {}
    private static Shot active;
    private static int frame;

    /** A real client tick drives flight; test step transitions and screenshot waits cannot interrupt it. */
    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Shot shot = active;
        if (shot == null) return;
        float x = -5.75f + frame * .5f;
        float y = 102.4f + (shot.curve ? (float) Math.sin(frame * .18) * 1.1f : 0);
        float dy = shot.curve ? (float) Math.cos(frame * .18) * .198f : 0;
        boolean end = frame == 24;
        for (int i = 0; i < shot.count; i++) {
            float offsetX = shot.count == 1 ? 0 : (i % 4 - 1.5f) * 1.1f;
            float offsetY = shot.count == 1 ? 0 : (i / 4) * .6f;
            LogosFx.handle(new FxPayload((byte) (end ? FxPayload.Kind.TRAIL_END : FxPayload.Kind.TRAIL).ordinal(),
                (byte) Element.FIRE.ordinal(), (byte) (shot.curve ? Motion.HOMING : Motion.FORWARD).ordinal(),
                x + offsetX, y + offsetY, 3, .5f, dy, 0, shot.size, shot.id + i, -1, 0, -1));
        }
        if (end) active = null;
        else frame++;
    }
    @Override
    public void configure(ScenarioOptions options) {
        options.tags("logos", "vfx", "fire").guiScale(2);
    }

    /** Feed the same packets as a moving spell, with a fixed camera for comparable silhouettes. */
    private static void shot(ScenarioBuilder s, int id, float size, boolean curve, String screenshot) {
        s.step("start fire projectile " + id, ctx -> {
            active = new Shot(id, size, curve, 1);
            frame = 0;
        }).ticks(13).screenshot(screenshot).ticks(36);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.teardown("stop preview flight", ctx -> active = null);
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 12)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0, 101, -2, 0, 0)
                .ticks(10);
        shot(s, 8101, 0.3f, false, "fire_projectile_day");
        shot(s, 8102, 0.7f, false, "fire_projectile_large");
        shot(s, 8103, 0.3f, true, "fire_projectile_curve");
        s.runCommand("time set midnight").ticks(4);
        shot(s, 8104, 0.7f, false, "fire_projectile_night");
        s.runCommand("time set noon")
                .step("preview sixteen fire projectiles", ctx -> {
                    active = new Shot(8200, .3f, false, 16);
                    frame = 0;
                })
                .ticks(13).screenshot("fire_projectile_volley").ticks(36)
                .check("volley completed", ctx -> active == null)
                .server("cast a real fire projectile", sc -> {
                    var player = sc.player();
                    PlayerMagic magic = PlayerMagic.of(player);
                    magic.maxMana = magic.mana = 10000;
                    Casting.cast(player, Casting.compile("(projectile :speed 0.4 :life 3 (on-hit (damage target 6 fire)))"), null, null);
                })
                .ticks(12)
                .screenshot("fire_projectile_cast")
                .ticks(50);
    }
}
