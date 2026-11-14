package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Real-client look at the 术士: master warlocks standing front, back and sideways before the camera, a zombie one,
 * and a master's trades. Run: {@code gradlew runClient -PldTest=logos_warlock -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_warlock", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class WarlockScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(2);
    }

    /** A still warlock (or zombie warlock) at {@code x, z} facing {@code yaw}. */
    private static <T extends Mob & VillagerDataHolder> void stand(ServerLevel level, EntityType<T> type, double x, double z, float yaw) {
        T mob = type.create(level, EntitySpawnReason.COMMAND);
        if (mob == null) return;
        var warlock = level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION).getOrThrow(LogosMod.WARLOCK.getKey());
        mob.setVillagerData(mob.getVillagerData().withProfession(warlock).withLevel(5));
        mob.snapTo(x, ORIGIN.getY() + 1, z, yaw, 0);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon").runCommand("weather clear").runCommand("difficulty normal")
                .clearArea(ORIGIN.offset(0, 4, 3), 7)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 8), Blocks.SMOOTH_STONE.defaultBlockState())
                .server("stand four warlocks", sc -> {
                    ServerLevel level = sc.player().level();
                    stand(level, EntityType.VILLAGER, -1.5, 3.5, 180);       // facing the camera
                    stand(level, EntityType.VILLAGER, 0.5, 3.5, 0);          // its back
                    stand(level, EntityType.VILLAGER, 2.5, 3.5, 90);         // its side
                    // Under a roof, so the noon sun does not set it alight.
                    level.setBlockAndUpdate(ORIGIN.offset(-4, 3, 3), Blocks.SMOOTH_STONE.defaultBlockState());
                    stand(level, EntityType.ZOMBIE_VILLAGER, -3.5, 3.5, 180);
                })
                .teleportPlayer(0.0, ORIGIN.getY() + 1, -1.8, 0, 12)
                .ticks(20)
                .screenshot("warlocks")
                .teleportPlayer(-1.5, ORIGIN.getY() + 1.4, 1.6, 0, 18)
                .ticks(10)
                .screenshot("warlock_close")
                .server("trade with the master", sc -> {
                    var player = sc.player();
                    var villager = player.level().getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, player.getBoundingBox().inflate(6)).stream()
                            .filter(v -> v.getYRot() == 180).findFirst().orElseThrow();
                    villager.setNoAi(false);
                    villager.interact(player, InteractionHand.MAIN_HAND, villager.position());
                })
                .awaitScreen(MerchantScreen.class)
                .ticks(5)
                .screenshot("warlock_trades")
                .step("close", ctx -> Minecraft.getInstance().setScreen(null));
    }
}
