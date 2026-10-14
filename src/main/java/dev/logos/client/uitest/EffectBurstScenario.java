package dev.logos.client.uitest;

import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.client.WardSpheres;
import net.minecraft.client.Minecraft;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The shared damage core in each damage element's colours, its stages, real routing of every act from the server, and the ward sphere following a real shield. */
@LDLRegisterClient(name = "logos_effect_burst", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class EffectBurstScenario implements UIScenario {
    private static final Element[] DAMAGING = {Element.PHYSICAL, Element.FIRE, Element.FROST, Element.LIGHTNING, Element.VOID};
    private static final Vec3 EYE = new Vec3(.5, 102.62, -2);
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst").guiScale(2); }

    private static void burst(Element e, Vfx.Burst what, float x, float y, float z, Vec3 from, float scale) {
        Vec3 dir = new Vec3(x, y, z).subtract(from).normalize();
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) e.ordinal(), (byte) what.ordinal(),
                x, y, z, (float) dir.x, (float) dir.y, (float) dir.z, scale, 0, -1, 0, -1));
    }

    /** One damage hit of every damaging element in a row in front of the player. */
    private static void row(float scale) {
        for (int i = 0; i < DAMAGING.length; i++) burst(DAMAGING[i], Vfx.Burst.DAMAGE, (i - 2) * 2.2f + .5f, 102.2f, 5, EYE, scale);
    }

    private static Map<?, ?> cache() {
        try {
            var field = LogosFx.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void cast(net.minecraft.server.level.ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 3), 12)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        // Fresh rows for every age sample keep screenshot readback time out of the next sample.
        s.step("contact", ctx -> row(1)).ticks(1).screenshot("burst_contact").ticks(24)
                .step("swirl age sample", ctx -> row(1)).ticks(3).screenshot("burst_swirl").ticks(24)
                .step("arcs age sample", ctx -> row(1)).ticks(6).screenshot("burst_arcs").ticks(24)
                .step("fade age sample", ctx -> row(1)).ticks(10).screenshot("burst_fade")
                .ticks(20).screenshot("burst_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> row(1)).ticks(3).screenshot("burst_night").ticks(24)
                .step("large targets", ctx -> row(2)).ticks(3).screenshot("burst_large").ticks(24);
        s.runCommand("time set noon").teleportPlayer(-5.5, 101, 5, -90, 0).ticks(5)
                .step("side view of a hit along +Z", ctx -> {
                    burst(Element.FIRE, Vfx.Burst.DAMAGE, .5f, 102.2f, 3.5f, new Vec3(.5, 102.2, -2), 1);
                    burst(Element.VOID, Vfx.Burst.DAMAGE, .5f, 102.2f, 7.5f, new Vec3(.5, 102.2, 2), 1);
                }).ticks(3).screenshot("burst_side").ticks(24)
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(5);

        s.step("reset routing observation", ctx -> cache().clear())
                .server("real fire damage on a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 3);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    cast(sc.player(), "(damage (nearest :radius 12) 1 fire)");
                }).ticks(3).screenshot("burst_real_damage").ticks(20)
                .check("damage takes the element's damage burst", ctx -> cache().containsKey("effect_burst/FIRE/" + Vfx.Burst.DAMAGE.ordinal())
                        && !cache().containsKey("burst/FIRE/0"))
                .server("damage still applies", sc -> {
                    if (target == null || target.getHealth() >= target.getMaxHealth())
                        throw new IllegalStateException("spell damage did not apply");
                })
                .step("reset heal observation", ctx -> cache().clear())
                .server("real heal on the cow", sc -> cast(sc.player(), "(heal (nearest :radius 12) 2)"))
                .ticks(10)
                .check("heal, until drawn, keeps the generic burst", ctx -> cache().containsKey("burst/LIFE/0")
                        && !cache().containsKey("effect_burst/LIFE/" + Vfx.Burst.HEAL.ordinal()))
                .step("reset force and potion observation", ctx -> cache().clear())
                .server("real push and potion on the cow", sc -> {
                    cast(sc.player(), "(push (nearest :radius 12) 0.5)");
                    cast(sc.player(), "(potion (nearest :radius 12) slowness 2)");
                })
                .ticks(10)
                .check("push and potion, until drawn, keep the generic burst", ctx -> cache().containsKey("burst/PHYSICAL/0")
                        && cache().containsKey("burst/ARCANE/0"))
                .server("real shield on the cow", sc -> cast(sc.player(), "(shield (nearest :radius 12) 4)"))
                .ticks(5)
                .check("the client keeps a ward sphere for the shielded cow", ctx -> {
                    var seen = target == null ? null : Minecraft.getInstance().level.getEntity(target.getId());
                    return seen != null && WardSpheres.holds(seen);
                })
                .server("break the shield", sc -> target.hurtServer(sc.level(), target.damageSources().generic(), 5))
                .ticks(5)
                .check("the sphere goes with the shield", ctx -> {
                    var seen = target == null ? null : Minecraft.getInstance().level.getEntity(target.getId());
                    return seen != null && !WardSpheres.holds(seen);
                });
        s.teardown("remove preview target", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
