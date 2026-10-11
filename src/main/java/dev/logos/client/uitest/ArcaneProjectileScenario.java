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

/** QFX Comet flight, sixteen concurrent shots and independent default/look/trail combinations. */
@LDLRegisterClient(name = "logos_arcane_projectile", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public class ArcaneProjectileScenario implements UIScenario {
    private record Shot(int id, float size, boolean curve, Element element, boolean overrides, int count) {}
    private static Shot active;
    private static int frame;

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Shot shot = active;
        if (shot == null) return;
        float x = -5.75f + frame * .5f;
        float y = 102.4f + (shot.curve ? (float)Math.sin(frame * .18) * 1.1f : 0);
        float dy = shot.curve ? (float)Math.cos(frame * .18) * .198f : 0;
        boolean end = frame == 24;
        for (int i = 0; i < shot.count; i++) {
            int look = !shot.overrides || (i & 1) == 0 ? -1 : BuiltInRegistries.ITEM.getId(Items.DIAMOND);
            int trail = !shot.overrides || (i & 2) == 0 ? -1 : BuiltInRegistries.PARTICLE_TYPE.getId(ParticleTypes.END_ROD);
            LogosFx.handle(new FxPayload((byte)(end ? FxPayload.Kind.TRAIL_END : FxPayload.Kind.TRAIL).ordinal(),
                    (byte)shot.element.ordinal(), (byte)(shot.curve ? Motion.HOMING : Motion.FORWARD).ordinal(),
                    x + (shot.count == 16 ? (i % 4 - 1.5f) * .9f : 0),
                    y + (shot.overrides ? i * .9f : shot.count == 16 ? i / 4 * .65f : 0),
                    3, .5f, dy, 0, shot.size, shot.id + i, look, 0, trail));
        }
        if (end) active = null;
        else frame++;
    }

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "arcane").guiScale(2); }

    private static void shot(ScenarioBuilder s, int id, float size, boolean curve, String name) {
        s.step("start arcane flight", ctx -> {
            active = new Shot(id, size, curve, Element.ARCANE, false, 1);
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
            active = new Shot(id, .3f, false, element, true, 4);
            frame = 0;
        }).ticks(13).check("independent head and trail " + element, ctx -> {
                    try { return overridesWork(id, visual); }
                    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                })
                .screenshot(visual + "_projectile_overrides").ticks(36);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.teardown("stop arcane preview", ctx -> active = null);
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 12)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0, 101, -2, 0, 0).ticks(10);
        shot(s, 16101, .3f, false, "arcane_projectile_day");
        shot(s, 16102, .7f, false, "arcane_projectile_large");
        shot(s, 16103, .3f, true, "arcane_projectile_curve");
        s.runCommand("time set midnight").ticks(4);
        shot(s, 16104, .7f, false, "arcane_projectile_night");
        s.runCommand("time set noon");
        overrides(s, 16200, Element.ARCANE, "arcane");
        // Let the deliberately overridden vanilla trail expire before the next showcase.
        s.ticks(80);
        s.step("preview sixteen arcane projectiles", ctx -> {
            active = new Shot(16300, .3f, false, Element.ARCANE, false, 16);
            frame = 0;
        }).ticks(13).screenshot("arcane_projectile_volley").ticks(36)
                .check("volley completed", ctx -> active == null);
        s.check("control spell infers arcane", ctx -> Casting.compile(
                "(projectile :speed 0.4 :life 3 (on-hit (mark target #star) (push target 1)))")
                .body().getFirst().element == Element.ARCANE);
        s.server("cast a real arcane projectile", sc -> {
            var player = sc.player();
            PlayerMagic magic = PlayerMagic.of(player);
            magic.maxMana = magic.mana = 10000;
            Casting.cast(player, Casting.compile("(projectile :speed 0.4 :life 3 (on-hit (mark target #star) (push target 1)))"), null, null);
        }).ticks(12).screenshot("arcane_projectile_cast").ticks(45);
    }
}
