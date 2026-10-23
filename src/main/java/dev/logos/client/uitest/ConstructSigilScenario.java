package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.runtime.Casting;
import dev.logos.runtime.ConstructEntity;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Shields wearing :sigil show their element's field circles in place of glass; a glass shield beside them for comparison. */
@LDLRegisterClient(name = "logos_construct_sigil", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ConstructSigilScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "vfx").guiScale(2);
    }

    private static void cast(ServerContext sc, String src) {
        var p = sc.player();
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 10000;
        m.trace = false;
        Casting.cast(p, Casting.compile(src), null, null);
    }

    /** A shield of {@code element} 7 ahead, 1 up and {@code x} to the right, facing along the caster's view. */
    private static String shield(double x, String element, boolean sigil) {
        return "(construct :shape disk :radius 1.6 :life 20 :at (offset 7 1 " + x + ")"
                + (sigil ? " :sigil" : "") + " (on-block (damage target 1 " + element + ")))";
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 5, 6), 8)
                .fill(ORIGIN.offset(-10, 0, -6), ORIGIN.offset(10, 0, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 2, ORIGIN.getZ() + 0.5, 0, 0)
                .ticks(10)
                .server("raise four shields", sc -> {
                    cast(sc, shield(-4.5, "fire", true));
                    cast(sc, shield(-1.5, "frost", true));
                    cast(sc, shield(1.5, "lightning", true));
                    cast(sc, shield(4.5, "lightning", false));
                })
                .ticks(20)
                .step("three wear the circle, one is glass", ctx -> {
                    var level = net.minecraft.client.Minecraft.getInstance().level;
                    var shields = ConstructEntity.near(level, new AABB(ORIGIN).inflate(16));
                    ctx.check("four shields arrived", shields.size() == 4);
                    ctx.check("three wear :sigil", shields.stream().filter(ConstructEntity::sigil).count() == 3);
                })
                .screenshot("construct_sigil");
    }
}
