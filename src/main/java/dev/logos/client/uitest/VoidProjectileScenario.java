package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.LogosMod;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Curse/starfield flight and the four independent default/look/trail combinations. */
@LDLRegisterClient(name = "logos_void_projectile", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public class VoidProjectileScenario implements UIScenario {
    private record Shot(int id, float size, boolean curve, Element element, boolean overrides) {}
    private static Shot active;
    private static int frame;

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Shot shot = active;
        if (shot == null) return;
        float x = -5.75f + frame * .5f;
        float y = 102.4f + (shot.curve ? (float)Math.sin(frame * .18) * 1.1f : 0);
        float dy = shot.curve ? (float)Math.cos(frame * .18) * .198f : 0;
        if (shot.id == 10300) { x = 0; y = 102.6f; }
        boolean end = frame == 24;
        for (int i = 0; i < (shot.overrides ? 4 : 1); i++) {
            int look = (i & 1) == 0 ? -1 : BuiltInRegistries.ITEM.getId(Items.DIAMOND);
            int trail = (i & 2) == 0 ? -1 : BuiltInRegistries.PARTICLE_TYPE.getId(ParticleTypes.END_ROD);
            LogosFx.handle(new FxPayload((byte)(end ? FxPayload.Kind.TRAIL_END : FxPayload.Kind.TRAIL).ordinal(),
                    (byte)shot.element.ordinal(), (byte)(shot.curve ? Motion.HOMING : Motion.FORWARD).ordinal(),
                    x, y + i * .9f, 3, shot.id == 10300 ? 0 : .5f, dy, shot.id == 10300 ? .5f : 0,
                    shot.size, shot.id + i, look, 0, trail));
        }
        if (end) active = null;
        else frame++;
    }

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "void").guiScale(2); }

    private static void shot(ScenarioBuilder s, int id, float size, boolean curve, String name) {
        s.step("start void flight", ctx -> {
            active = new Shot(id, size, curve, Element.VOID, false);
            frame = 0;
        }).ticks(13).screenshot(name).ticks(36);
    }

    // Inspect the real packet routing, rather than a second copy of the selection rules.
    private static boolean overridesWork(int id, String visual) throws ReflectiveOperationException {
        var field = LogosFx.class.getDeclaredField("TRAILS");
        field.setAccessible(true);
        Map<?, ?> trails = (Map<?, ?>)field.get(null);
        for (int i = 0; i < 4; i++) {
            Object entry = trails.get(id + i);
            if (entry == null) return false;
            for (String key : new String[]{"look", "particle", "runtime", "visual"}) {
                var member = entry.getClass().getDeclaredField(key);
                member.setAccessible(true);
                boolean present = member.get(entry) != null;
                boolean expected = switch (key) {
                    case "look" -> (i & 1) != 0;
                    case "particle" -> (i & 2) != 0;
                    case "runtime" -> (i & 2) == 0;
                    default -> i != 3;
                };
                if (present != expected) return false;
                if (key.equals("visual") && present && !member.get(entry).getClass().getSimpleName()
                        .equalsIgnoreCase(visual + "ProjectileVisual")) return false;
            }
        }
        return true;
    }

    private static void overrides(ScenarioBuilder s, int id, Element element, String visual) {
        s.step("start independent overrides " + element, ctx -> {
            active = new Shot(id, .3f, false, element, true);
            frame = 0;
        }).ticks(13).check("independent head and trail " + element, ctx -> {
                    try { return overridesWork(id, visual); }
                    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                })
                .screenshot(visual + "_projectile_overrides").ticks(36);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.teardown("stop void preview", ctx -> active = null);
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 12)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0, 101, -2, 0, 0).ticks(10);

        shot(s, 10101, .3f, false, "void_projectile_day");
        shot(s, 10102, .7f, false, "void_projectile_large");
        shot(s, 10103, .3f, true, "void_projectile_curve");
        s.runCommand("time set midnight").ticks(4);
        shot(s, 10104, .7f, false, "void_projectile_night");
        s.runCommand("time set noon");
        overrides(s, 10200, Element.VOID, "void");
        s.runCommand("time set midnight").teleportPlayer(0, 101, 5, 180, 0);
        shot(s, 10300, .7f, false, "void_projectile_starfield_front");
        s.teleportPlayer(0, 101, -2, 0, 0);
        s.server("cast a real void projectile", sc -> {
            var player = sc.player();
            PlayerMagic magic = PlayerMagic.of(player);
            magic.maxMana = magic.mana = 10000;
            Casting.cast(player, Casting.compile("(projectile :speed 0.4 :life 3 (on-hit (damage target 6 void)))"), null, null);
        }).ticks(12).screenshot("void_projectile_cast").ticks(45);
    }
}
