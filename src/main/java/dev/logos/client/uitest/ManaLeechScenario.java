package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaLeech;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The mana leech: an empty one beside one swollen and glowing with 45 mana, then one that hunts the
 * player, coils, leaps and clings to their face — seen from the player's own eyes, in darkness.
 */
@LDLRegisterClient(name = "logos_mana_leech", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ManaLeechScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private ManaLeech empty, full;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "mana_leech").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                // A peaceful world removes monsters as soon as they appear.
                .runCommand("difficulty normal")
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 6), 12)
                .fill(ORIGIN.offset(-10, 0, -6), ORIGIN.offset(10, 0, 16), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, -1.5, 0, 25)
                .server("an empty and a swollen leech", sc -> {
                    empty = place(sc.level(), -1.2, 0);
                    full = place(sc.level(), 1.2, 45);
                })
                .ticks(60)
                .screenshot("mana_leech_empty_and_swollen")
                .server("one hunts the player", sc -> {
                    empty.discard();
                    full.discard();
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.maxMana = m.mana = 100;
                    ManaLeech hunter = LogosMod.MANA_LEECH.get().create(sc.level(), EntitySpawnReason.COMMAND);
                    hunter.snapTo(0.5, 101, 3.5, 180, 0);
                    sc.level().addFreshEntity(hunter);
                })
                .ticks(28)
                .screenshot("mana_leech_coiling")
                .ticks(30)
                .screenshot("mana_leech_on_face");
    }

    /** A still leech holding {@code mana}, at {@code x} across the camera's view, facing it. */
    private static ManaLeech place(net.minecraft.server.level.ServerLevel level, double x, double mana) {
        ManaLeech l = LogosMod.MANA_LEECH.get().create(level, EntitySpawnReason.COMMAND);
        l.snapTo(0.5 + x, 101, 1.5, 150 + (x < 0 ? 0 : 60), 0);
        l.setNoAi(true);
        l.setMana(mana);
        level.addFreshEntity(l);
        return l;
    }
}
