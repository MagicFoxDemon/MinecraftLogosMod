package dev.logos.client.uitest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The potion burst on cows: harmful, beneficial and neutral side by side through their stages, at night,
 * on a large target, from the side, and real {@code potion} spells routed to the right kind.
 */
@LDLRegisterClient(name = "logos_potion_burst", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PotionBurstScenario implements UIScenario {
    private static final Vfx.Burst[] KINDS = {Vfx.Burst.POTION_HARMFUL, Vfx.Burst.POTION_BENEFICIAL, Vfx.Burst.POTION_NEUTRAL};
    private static final float[] X = {3.1f, .5f, -2.1f};
    private static final List<Cow> cows = new ArrayList<>();

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "burst", "potion").guiScale(2); }

    /** No direction: the burst sits at the body's centre with local +Z up, as the server sends it. */
    private static void burst(Vfx.Burst kind, float x, float y, float z, float scale) {
        LogosFx.handle(new FxPayload((byte) FxPayload.Kind.BURST.ordinal(), (byte) Element.ARCANE.ordinal(), (byte) kind.ordinal(),
                x, y, z, 0, 0, 0, scale, 0, -1, 0, -1));
    }

    /** Harmful, beneficial and neutral, left to right, each on a cow's body. */
    private static void row(float scale) {
        for (int i = 0; i < KINDS.length; i++) burst(KINDS[i], X[i], 101.7f, 2.5f, scale);
    }

    private static Map<?, ?> cache() {
        try {
            var field = LogosFx.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void cast(ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused: " + source);
    }

    private static boolean routed(Vfx.Burst kind) {
        return cache().containsKey("effect_burst/" + Element.ARCANE + "/" + kind.ordinal());
    }

    /** A real {@code potion} spell on the nearest cow: it takes the effect and the burst of {@code kind}. */
    private static void real(ScenarioBuilder s, String effect, Holder<MobEffect> holder, Vfx.Burst kind) {
        s.step("reset routing", ctx -> cache().clear())
                .server("real " + effect, sc -> cast(sc.player(), "(potion (nearest :radius 12) " + effect + " 3)"))
                .server(effect + " applies", sc -> {
                    if (cows.stream().noneMatch(c -> c.hasEffect(holder)))
                        throw new IllegalStateException(effect + " did not apply");
                })
                .ticks(5).screenshot("potion_real_" + effect).ticks(20)
                .check(effect + " takes the " + kind + " burst", ctx -> routed(kind));
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 2), 10)
                .fill(new BlockPos(-10, 100, -6), new BlockPos(10, 100, 14), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 8)
                .server("three cows", sc -> {
                    for (float x : X) {
                        Cow cow = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                        if (cow == null) throw new IllegalStateException("cow creation failed");
                        cow.setPos(x, 101, 2.5);
                        cow.setYRot(90);
                        cow.setNoAi(true);
                        sc.level().addFreshEntity(cow);
                        cows.add(cow);
                    }
                }).ticks(10);
        // A fresh row for every age sample keeps screenshot readback time out of the next sample.
        s.step("appear", ctx -> row(1)).ticks(2).screenshot("potion_appear").ticks(20)
                .step("peak", ctx -> row(1)).ticks(5).screenshot("potion_peak").ticks(20)
                .step("dissipate", ctx -> row(1)).ticks(10).screenshot("potion_dissipate").ticks(20)
                .step("last wisps", ctx -> row(1)).ticks(15).screenshot("potion_wisps")
                .ticks(10).screenshot("potion_cleared");
        s.runCommand("time set midnight").ticks(3)
                .step("night", ctx -> row(1)).ticks(5).screenshot("potion_night").ticks(20)
                .runCommand("time set noon").ticks(3)
                .step("large targets", ctx -> row(2)).ticks(5).screenshot("potion_large").ticks(20);
        s.teleportPlayer(-5, 101, 2.5, -90, 8).ticks(5)
                .step("side view", ctx -> row(1)).ticks(5).screenshot("potion_side").ticks(20)
                .teleportPlayer(.5, 101, -2, 0, 8).ticks(5);

        real(s, "poison", MobEffects.POISON, Vfx.Burst.POTION_HARMFUL);
        real(s, "speed", MobEffects.SPEED, Vfx.Burst.POTION_BENEFICIAL);
        real(s, "glowing", MobEffects.GLOWING, Vfx.Burst.POTION_NEUTRAL);
        s.teardown("remove preview cows", ctx -> {
            cows.forEach(Cow::discard);
            cows.clear();
        });
    }
}
