package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import dev.logos.runtime.Lifeforms;
import dev.logos.runtime.ManaLifeform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client check of the mana lifeform's GeckoLib model: a level-1 and a level-5 one awake,
 * casting, dormant (shut down on the ground), waking, and the core panel.
 * Run: {@code gradlew runClient -PldTest=logos_lifeform}.
 */
@LDLRegisterClient(name = "logos_lifeform", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeformScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private ManaLifeform small, big;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "lifeform").guiScale(2);
    }

    private ManaLifeform spawn(ServerLevel level, net.minecraft.server.level.ServerPlayer p, int lv, double x) {
        ManaLifeform lf = LogosMod.MANA_LIFEFORM.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        lf.snapTo(ORIGIN.getX() + x, ORIGIN.getY() + 1.2, ORIGIN.getZ() + 2.2, 180, 0);
        lf.setLifeLevel(lv);
        lf.setMana(ManaLifeform.maxMana(lv));
        // Left without a master: its mind then keeps it where it is, for the camera.
        level.addFreshEntity(lf);
        return lf;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 5, 6), 8)
                .fill(ORIGIN.offset(-10, 0, -6), ORIGIN.offset(10, 0, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                // Within 6 blocks of both, so they hover in place instead of walking up to the camera.
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() - 0.5, 0, 25)
                .server("summon a level-1 and a level-5 lifeform", sc -> {
                    var p = sc.player();
                    small = spawn(p.level(), p, 1, -0.3);
                    big = spawn(p.level(), p, 5, 1.4);
                    small.core.setItem(0, SpellPages.write(new ItemStack(LogosMod.SPELL_PAGE.get()), "(spell :name \"灵弹\" :mana 20 (damage (foe) 6))", null));
                })
                .ticks(30)
                .screenshot("lifeform_awake")
                .server("both cast", sc -> {
                    small.setCasting(true);
                    big.setCasting(true);
                })
                .ticks(24)
                .screenshot("lifeform_cast_release")
                .server("run both dry", sc -> {
                    small.setCasting(false);
                    big.setCasting(false);
                    small.setMana(0.01);
                    big.setMana(0.01);
                })
                .ticks(100)
                .screenshot("lifeform_dormant")
                .server("feed both", sc -> {
                    small.receiveMana(500);
                    big.receiveMana(5000);
                })
                .ticks(40)
                .screenshot("lifeform_waking")
                .ticks(60)
                .screenshot("lifeform_awake_again")
                .server("open the level-5 core", sc -> Lifeforms.openCore(sc.player(), big))
                .ticks(5)
                .screenshot("lifeform_core");
    }
}
