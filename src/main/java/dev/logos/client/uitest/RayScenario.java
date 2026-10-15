package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Real rays and links from the server reach the client with how they ended: in the open, on a block, on a creature, or as a link. */
@LDLRegisterClient(name = "logos_ray", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class RayScenario implements UIScenario {
    private static Cow target;

    @Override
    public void configure(ScenarioOptions options) { options.tags("logos", "vfx", "ray").guiScale(2); }

    private static void cast(net.minecraft.server.level.ServerPlayer player, String source) {
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 10000;
        if (Casting.cast(player, Casting.compile(source), null, null) == null)
            throw new IllegalStateException("real test spell was refused");
    }

    private static boolean last(Element element, Vfx.RayEnd end) {
        LogosFx.FiredRay ray = LogosFx.lastRay();
        return ray != null && ray.element() == element && ray.end() == end;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear")
                .clearArea(new BlockPos(0, 104, 6), 14)
                .fill(new BlockPos(-14, 100, -8), new BlockPos(14, 100, 22), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(.5, 101, -2, 0, 0).ticks(10);
        s.server("ray into the open", sc -> cast(sc.player(), "(ray :range 12 (on-hit (damage target 1 fire)))"))
                .ticks(3).screenshot("ray_open").ticks(10)
                .check("an open ray ends in the open", ctx -> last(Element.FIRE, Vfx.RayEnd.OPEN));
        s.fill(new BlockPos(-1, 101, 6), new BlockPos(1, 104, 6), Blocks.STONE.defaultBlockState())
                .server("ray into a wall", sc -> cast(sc.player(), "(ray :range 12 (on-hit (damage target 1 frost)))"))
                .ticks(3).screenshot("ray_block").ticks(10)
                .check("a blocked ray ends on the block", ctx -> last(Element.FROST, Vfx.RayEnd.BLOCK))
                .fill(new BlockPos(-1, 101, 6), new BlockPos(1, 104, 6), Blocks.AIR.defaultBlockState());
        s.server("ray at a cow", sc -> {
                    target = EntityType.COW.create(sc.level(), EntitySpawnReason.COMMAND);
                    if (target == null) throw new IllegalStateException("cow creation failed");
                    target.setPos(.5, 101, 4);
                    target.setNoAi(true);
                    sc.level().addFreshEntity(target);
                    cast(sc.player(), "(ray :range 12 (on-hit (damage target 1 lightning)))");
                })
                .ticks(3).screenshot("ray_creature").ticks(10)
                .check("a ray stopped by a creature ends on it", ctx -> last(Element.LIGHTNING, Vfx.RayEnd.CREATURE))
                .server("drain the cow", sc -> cast(sc.player(), "(drain (nearest :radius 12) 1)"))
                .ticks(3).screenshot("ray_link").ticks(10)
                .check("drain draws a void link", ctx -> last(Element.VOID, Vfx.RayEnd.LINK));
        s.teardown("remove the cow", ctx -> {
            if (target != null) { target.discard(); target = null; }
        });
    }
}
